/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.stream;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ChannelHandler} that adds support for writing a large data stream
 * asynchronously neither spending a lot of memory nor getting
 * {@link OutOfMemoryError}.  Large data streaming such as file
 * transfer requires complicated state management in a {@link ChannelHandler}
 * implementation.  {@link ChunkedWriteHandler} manages such complicated states
 * so that you can send a large data stream without difficulties.
 * <p>
 * To use {@link ChunkedWriteHandler} in your application, you have to insert
 * a new {@link ChunkedWriteHandler} instance:
 * <pre>
 * {@link ChannelPipeline} p = ...;
 * p.addLast("streamer", <b>new {@link ChunkedWriteHandler}()</b>);
 * p.addLast("handler", new MyHandler());
 * </pre>
 * Once inserted, you can write a {@link ChunkedInput} so that the
 * {@link ChunkedWriteHandler} can pick it up and fetch the content of the
 * stream chunk by chunk and write the fetched chunk downstream:
 * <pre>
 * {@link Channel} ch = ...;
 * ch.write(new {@link ChunkedFile}(new File("video.mkv"));
 * </pre>
 *
 * <h3>Sending a stream which generates a chunk intermittently</h3>
 *
 * Some {@link ChunkedInput} generates a chunk on a certain event or timing.
 * Such {@link ChunkedInput} implementation often returns {@code null} on
 * {@link ChunkedInput#readChunk(ChannelHandlerContext)}, resulting in the indefinitely suspended
 * transfer.  To resume the transfer when a new chunk is available, you have to
 * call {@link #resumeTransfer()}.
 */
public class ChunkedWriteHandler
        extends ChannelDuplexHandler {

    private static final InternalLogger logger =
        InternalLoggerFactory.getInstance(ChunkedWriteHandler.class);

    private final Queue<PendingWrite> queue = new ArrayDeque<PendingWrite>();
    private final int maxPendingWrites;
    private volatile ChannelHandlerContext ctx;
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private PendingWrite currentWrite;
    public ChunkedWriteHandler() {
        this(4);
    }

    public ChunkedWriteHandler(int maxPendingWrites) {
        if (maxPendingWrites <= 0) {
            throw new IllegalArgumentException(
                    "maxPendingWrites: " + maxPendingWrites + " (expected: > 0)");
        }
        this.maxPendingWrites = maxPendingWrites;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
    }

    private boolean isWritable() {
        return pendingWrites.get() < maxPendingWrites;
    }

    /**
     * Continues to fetch the chunks from the input.
     */
    public void resumeTransfer() {
        final ChannelHandlerContext ctx = this.ctx;
        if (ctx == null) {
            return;
        }
        if (ctx.executor().inEventLoop()) {
            try {
                doFlush(ctx);
            } catch (Exception e) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Unexpected exception while sending chunks.", e);
                }
            }
        } else {
            // let the transfer resume on the next event loop round
            ctx.executor().execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        doFlush(ctx);
                    } catch (Exception e) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("Unexpected exception while sending chunks.", e);
                        }
                    }
                }
            });
        }
    }

    @Override
    public void read(ChannelHandlerContext ctx) {
        ctx.read();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        queue.add(new PendingWrite(msg, promise));
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (isWritable() || !ctx.channel().isActive()) {
            doFlush(ctx);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        doFlush(ctx);
        super.channelInactive(ctx);
    }

    private void discard(Throwable cause) {
        for (;;) {
            PendingWrite currentWrite = this.currentWrite;

            if (this.currentWrite == null) {
                currentWrite = queue.poll();
            } else {
                this.currentWrite = null;
            }

            if (currentWrite == null) {
                break;
            }
            Object message = currentWrite.msg;
            if (message instanceof ChunkedInput) {
                ChunkedInput<?> in = (ChunkedInput<?>) message;
                try {
                    if (!in.isEndOfInput()) {
                        if (cause == null) {
                            cause = new ClosedChannelException();
                        }
                        currentWrite.fail(cause);
                    } else {
                        currentWrite.promise.setSuccess();
                    }
                    closeInput(in);
                } catch (Exception e) {
                    currentWrite.fail(e);
                    logger.warn(ChunkedInput.class.getSimpleName() + ".isEndOfInput() failed", e);
                    closeInput(in);
                }
            } else {
                if (cause == null) {
                    cause = new ClosedChannelException();
                }
                currentWrite.fail(cause);
            }
        }
    }

    private void doFlush(final ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        if (!channel.isActive()) {
            discard(null);
            return;
        }
        boolean needsFlush;
        while (isWritable()) {
            if (currentWrite == null) {
                currentWrite = queue.poll();
            }

            if (currentWrite == null) {
                break;
            }
            needsFlush = true;
            final PendingWrite currentWrite = this.currentWrite;
            final Object pendingMessage = currentWrite.msg;

            if (pendingMessage instanceof ChunkedInput) {
                final ChunkedInput<?> chunks = (ChunkedInput<?>) pendingMessage;
                boolean endOfInput;
                boolean suspend;
                Object message = null;
                try {
                    message = chunks.readChunk(ctx);
                    endOfInput = chunks.isEndOfInput();

                    if (message == null) {
                        // No need to suspend when reached at the end.
                        suspend = !endOfInput;
                    } else {
                        suspend = false;
                    }
                } catch (final Throwable t) {
                    this.currentWrite = null;

                    if (message != null) {
                        ReferenceCountUtil.release(message);
                    }

                    currentWrite.fail(t);
                    if (ctx.executor().inEventLoop()) {
                        ctx.fireExceptionCaught(t);
                    } else {
                        ctx.executor().execute(new Runnable() {
                            @Override
                            public void run() {
                                ctx.fireExceptionCaught(t);
                            }
                        });
                    }

                    closeInput(chunks);
                    break;
                }

                if (suspend) {
                    // ChunkedInput.nextChunk() returned null and it has
                    // not reached at the end of input. Let's wait until
                    // more chunks arrive. Nothing to write or notify.
                    break;
                }

                if (message == null) {
                    // If message is null write an empty ByteBuf.
                    // See https://github.com/netty/netty/issues/1671
                    message = Unpooled.EMPTY_BUFFER;
                }

                pendingWrites.incrementAndGet();
                ChannelFuture f = ctx.write(message);
                if (endOfInput) {
                    this.currentWrite = null;

                    // Register a listener which will close the input once the write is complete.
                    // This is needed because the Chunk may have some resource bound that can not
                    // be closed before its not written.
                    //
                    // See https://github.com/netty/netty/issues/303
                    f.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            pendingWrites.decrementAndGet();
                            currentWrite.promise.setSuccess();
                            closeInput(chunks);
                        }
                    });
                } else if (isWritable()) {
                    f.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            pendingWrites.decrementAndGet();
                            if (!future.isSuccess()) {
                                closeInput((ChunkedInput<?>) pendingMessage);
                                currentWrite.fail(future.cause());
                            } else {
                                currentWrite.progress();
                            }
                        }
                    });
                } else {
                    f.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            pendingWrites.decrementAndGet();
                            if (!future.isSuccess()) {
                                closeInput((ChunkedInput<?>) pendingMessage);
                                currentWrite.fail(future.cause());
                            } else {
                                currentWrite.progress();
                                if (isWritable()) {
                                    resumeTransfer();
                                }
                            }
                        }
                    });
                }
            } else {
                ctx.write(pendingMessage, currentWrite.promise);
                this.currentWrite = null;
            }

            if (needsFlush) {
                ctx.flush();
            }
            if (!channel.isActive()) {
                discard(new ClosedChannelException());
                return;
            }
        }
    }

    static void closeInput(ChunkedInput<?> chunks) {
        try {
            chunks.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close a chunked input.", t);
            }
        }
    }

    private static final class PendingWrite {
        final Object msg;
        final ChannelPromise promise;
        private long progress;

        PendingWrite(Object msg, ChannelPromise promise) {
            this.msg = msg;
            this.promise = promise;
        }

        void fail(Throwable cause) {
            ReferenceCountUtil.release(msg);
            if (promise != null) {
                promise.setFailure(cause);
            }
        }

        void progress() {
            progress ++;
            if (promise instanceof ChannelProgressivePromise) {
                ((ChannelProgressivePromise) promise).tryProgress(progress, -1);
            }
        }
    }
}
