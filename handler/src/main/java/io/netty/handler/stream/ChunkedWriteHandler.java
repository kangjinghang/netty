/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.stream;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBufAllocator;
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
public class ChunkedWriteHandler extends ChannelDuplexHandler { // 用于支持异步写大数据流并且不需要消耗大量内存也不会导致内存溢出错误(OutOfMemoryError)

    private static final InternalLogger logger =
        InternalLoggerFactory.getInstance(ChunkedWriteHandler.class);
    // 待发送的数据包消息队列
    private final Queue<PendingWrite> queue = new ArrayDeque<PendingWrite>();
    private volatile ChannelHandlerContext ctx;

    public ChunkedWriteHandler() {
    }

    /**
     * @deprecated use {@link #ChunkedWriteHandler()}
     */
    @Deprecated
    public ChunkedWriteHandler(int maxPendingWrites) {
        checkPositive(maxPendingWrites, "maxPendingWrites");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
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
            resumeTransfer0(ctx);
        } else {
            // let the transfer resume on the next event loop round
            ctx.executor().execute(new Runnable() {

                @Override
                public void run() {
                    resumeTransfer0(ctx);
                }
            });
        }
    }

    private void resumeTransfer0(ChannelHandlerContext ctx) {
        try {
            doFlush(ctx);
        } catch (Exception e) {
            logger.warn("Unexpected exception while sending chunks.", e);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        queue.add(new PendingWrite(msg, promise)); // write的数据包在经过 ChunkedWriteHandler 的时候，会先被存储到这个队列中，并不会立即放入到 ChannelOutboundBuffer
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        doFlush(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        doFlush(ctx);
        ctx.fireChannelInactive();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isWritable()) { // 写缓冲区又有空间了，小于了低水位标志，继续执行 flush
            // channel is writable again try to continue flushing
            doFlush(ctx);
        }
        ctx.fireChannelWritabilityChanged();
    }

    private void discard(Throwable cause) {
        for (;;) {
            PendingWrite currentWrite = queue.poll();

            if (currentWrite == null) {
                break;
            }
            Object message = currentWrite.msg;
            if (message instanceof ChunkedInput) {
                ChunkedInput<?> in = (ChunkedInput<?>) message;
                boolean endOfInput;
                long inputLength;
                try {
                    endOfInput = in.isEndOfInput();
                    inputLength = in.length();
                    closeInput(in);
                } catch (Exception e) {
                    closeInput(in);
                    currentWrite.fail(e);
                    if (logger.isWarnEnabled()) {
                        logger.warn(ChunkedInput.class.getSimpleName() + " failed", e);
                    }
                    continue;
                }

                if (!endOfInput) {
                    if (cause == null) {
                        cause = new ClosedChannelException();
                    }
                    currentWrite.fail(cause);
                } else {
                    currentWrite.success(inputLength);
                }
            } else {
                if (cause == null) {
                    cause = new ClosedChannelException();
                }
                currentWrite.fail(cause);
            }
        }
    }
    // 依次取出消息队列中的大数据包，然后拆分成一个个小数据包(ByteBuf)后发给下游的ChannelOutboundHandler，并且在每次发送完一个ByteBuf包后都会立即执行依次ctx.flush()操作将该ByteBuf发送到网络中。
    private void doFlush(final ChannelHandlerContext ctx) {
        final Channel channel = ctx.channel();
        if (!channel.isActive()) {
            discard(null);
            return;
        }

        boolean requiresFlush = true;
        ByteBufAllocator allocator = ctx.alloc();
        while (channel.isWritable()) {
            final PendingWrite currentWrite = queue.peek(); // 依次取出消息队列中的大数据包

            if (currentWrite == null) {
                break;
            }

            if (currentWrite.promise.isDone()) {
                // This might happen e.g. in the case when a write operation
                // failed, but there're still unconsumed chunks left.
                // Most chunked input sources would stop generating chunks
                // and report end of input, but this doesn't work with any
                // source wrapped in HttpChunkedInput.
                // Note, that we're not trying to release the message/chunks
                // as this had to be done already by someone who resolved the
                // promise (using ChunkedInput.close method).
                // See https://github.com/netty/netty/issues/8700.
                queue.remove();
                continue;
            }

            final Object pendingMessage = currentWrite.msg;

            if (pendingMessage instanceof ChunkedInput) { // 仅支持ChunkedInput类型的消息。也就是说，仅当消息类型是ChunkedInput时才能实现ChunkedWriteHandler提供的大数据包传输功能（ChunkedInput是一个不确定长度的数据流）
                final ChunkedInput<?> chunks = (ChunkedInput<?>) pendingMessage;
                boolean endOfInput;
                boolean suspend;
                Object message = null;
                try {
                    message = chunks.readChunk(allocator); // 大数据包拆分成一个个小数据包(ByteBuf)后发给下游的ChannelOutboundHandler
                    endOfInput = chunks.isEndOfInput();

                    if (message == null) {
                        // No need to suspend when reached at the end.
                        suspend = !endOfInput;
                    } else {
                        suspend = false;
                    }
                } catch (final Throwable t) {
                    queue.remove();

                    if (message != null) {
                        ReferenceCountUtil.release(message);
                    }

                    closeInput(chunks);
                    currentWrite.fail(t);
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

                if (endOfInput) {
                    // We need to remove the element from the queue before we call writeAndFlush() as this operation
                    // may cause an action that also touches the queue.
                    queue.remove();
                }
                // Flush each chunk to conserve memory
                ChannelFuture f = ctx.writeAndFlush(message); // 为每个发送的小数据包注册一个 listener
                if (endOfInput) {
                    if (f.isDone()) {
                        handleEndOfInputFuture(f, currentWrite); // 所有小数据包成功发送完成后调用原始大数据包的 GenericProgressiveFutureListener
                    } else {
                        // Register a listener which will close the input once the write is complete.
                        // This is needed because the Chunk may have some resource bound that can not
                        // be closed before its not written.
                        //
                        // See https://github.com/netty/netty/issues/303
                        f.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture future) {
                                handleEndOfInputFuture(future, currentWrite);
                            }
                        });
                    }
                } else {
                    final boolean resume = !channel.isWritable();
                    if (f.isDone()) {
                        handleFuture(f, currentWrite, resume);
                    } else {
                        f.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture future) {
                                handleFuture(future, currentWrite, resume);
                            }
                        });
                    }
                }
                requiresFlush = false;
            } else {
                queue.remove();
                ctx.write(pendingMessage, currentWrite.promise);
                requiresFlush = true;
            }

            if (!channel.isActive()) {
                discard(new ClosedChannelException());
                break;
            }
        }

        if (requiresFlush) {
            ctx.flush(); // 每次发送完一个 ByteBuf 包后都会立即执行依次 ctx.flush() 操作将该 ByteBuf 发送到网络中
        }
    }
    // 小数据包成功发送完成后调用原始大数据包的 GenericProgressiveFutureListener
    private static void handleEndOfInputFuture(ChannelFuture future, PendingWrite currentWrite) {
        ChunkedInput<?> input = (ChunkedInput<?>) currentWrite.msg;
        if (!future.isSuccess()) {
            closeInput(input);
            currentWrite.fail(future.cause());
        } else { // 小数据包成功发送完成后调用原始大数据包的 GenericProgressiveFutureListener
            // read state of the input in local variables before closing it
            long inputProgress = input.progress();
            long inputLength = input.length();
            closeInput(input);
            currentWrite.progress(inputProgress, inputLength);
            currentWrite.success(inputLength);
        }
    }

    private void handleFuture(ChannelFuture future, PendingWrite currentWrite, boolean resume) {
        ChunkedInput<?> input = (ChunkedInput<?>) currentWrite.msg;
        if (!future.isSuccess()) {
            closeInput(input);
            currentWrite.fail(future.cause());
        } else {
            currentWrite.progress(input.progress(), input.length());
            if (resume && future.channel().isWritable()) {
                resumeTransfer();
            }
        }
    }

    private static void closeInput(ChunkedInput<?> chunks) {
        try {
            chunks.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close a chunked input.", t);
            }
        }
    }
    // 封装了待发送的消息以及异步写操作的 promise
    private static final class PendingWrite {
        final Object msg;
        final ChannelPromise promise;

        PendingWrite(Object msg, ChannelPromise promise) {
            this.msg = msg;
            this.promise = promise;
        }

        void fail(Throwable cause) {
            ReferenceCountUtil.release(msg);
            promise.tryFailure(cause);
        }

        void success(long total) {
            if (promise.isDone()) {
                // No need to notify the progress or fulfill the promise because it's done already.
                return;
            }
            progress(total, total);
            promise.trySuccess();
        }

        void progress(long progress, long total) {
            if (promise instanceof ChannelProgressivePromise) {
                ((ChannelProgressivePromise) promise).tryProgress(progress, total);
            }
        }
    }
}
