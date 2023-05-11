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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 * nio socket channel 的父类
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ); //注册OP_READ事件，表示对channel的读感兴趣
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }
    // 与【连接的字节数据读写】相关的Unsafe
    protected class NioByteUnsafe extends AbstractNioUnsafe {
        // 在 NIO 传输模式下，当 SocketChannel 的 read 操作返回-1时，有两种情况：a)【对端】已经关闭了连接，即 SocketChannel 被关闭了；b) 【当前端】主动执行了 socketChannel.shutdownInput()
        private void closeOnRead(ChannelPipeline pipeline) {
            // input 关闭了么？没有
            if (!isInputShutdown0()) { // 不是【当前端】主动执行了 socketChannel.shutdownInput()，是【对端】主动关闭了连接
                // 是否支持半关？如果是，关闭读，触发事件
                if (isAllowHalfClosure(config())) { // 支持半关，通过 option(ChannelOption.ALLOW_HALF_CLOSURE, true) 启用了 ChannelOption.ALLOW_HALF_CLOSURE 配置
                    shutdownInput(); // 远端主动关闭，此时执行 Channel 的输入源关闭
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE); // 触发一个用户自定义的 入站 ChannelInputShutdownEvent.INSTANCE事件，异常顺序调用 ChannelInboundHandler 的 userEventTriggered 方法
                } else { // 不支持半关，我们没配置相关属性
                    close(voidPromise()); // 关闭 channel
                }
            } else { // 【当前端】主动执行了 socketChannel.shutdownInput()
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);// 触发一个用户自定义的 入站 ChannelInputShutdownEvent.INSTANCE事件，异常顺序调用 ChannelInboundHandler 的 userEventTriggered 方法
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) { // 已读取到数据
                if (byteBuf.isReadable()) { // 数据可读
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else { // 数据不可读
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);

            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public final void read() {
            final ChannelConfig config = config();
            if (shouldBreakReadReady(config)) {
                clearReadPending();
                return;
            }
            final ChannelPipeline pipeline = pipeline();
            final ByteBufAllocator allocator = config.getAllocator(); // 获取缓冲区分配器
            // io.netty.channel.DefaultChannelConfig 中设置 RecvByteBufAllocator ，默认 AdaptiveRecvByteBufAllocator
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            allocHandle.reset(config); // 重置一些变量，将 maxMessagePerRead重置为16，totalMessages、totalBytesRead重置为0；

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                do {
                    // 分配一个ByteBuf，尽可能分配合适的大小：guess，第一次就是 1024 byte。【每次读循环都会构建一个新的ByteBuf】
                    byteBuf = allocHandle.allocate(allocator); // 根据提供的缓冲区分配器 allocator，创建一个由AdaptiveRecvByteBufAllocator.HandleImpl推测的容量大小的ByteBuf，并使用该ByteBuf来接收接下来要读取的数据。第一次读循环，默认的大小为1024
                    // 读并且记录读了多少，如果读满了，下次continue的话就直接扩容。
                    allocHandle.lastBytesRead(doReadBytes(byteBuf)); // doReadBytes(byteBuf)，委托到所在的外部类NioSocketChannel。将真实读取的字节数设置到 AdaptiveRecvByteBufAllocator.HandleImpl 中的 lastBytesRead 属性
                    if (allocHandle.lastBytesRead() <= 0) { // 没有读取到数据，则释放缓冲区。当没有数据可读取时为0，当远端已经关闭时为-1
                        // nothing was read. release the buffer. 数据清理
                        byteBuf.release(); // 因为没有读取到数据，因此调用 byteBuf.release() 来释放 bytebuf（因为，此时 bytebuf 不会在通过 channelPipeline 进行传输了，也就是说，这个 bytebuf 最后使用的地方就是当前方法，因此应该调用 release() 方法来声明对其的释放
                        byteBuf = null;
                        close = allocHandle.lastBytesRead() < 0; // 读取数据量为负数表示对端已经关闭。close 变量会被标记为 true
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        break; // 退出读循环操作
                    }
                    // 读到数据了，在执行完消息的读取后，记录一下读了几次，仅仅一次，底层就是将totalMessages值+1
                    allocHandle.incMessagesRead(1); // 读取的总信息++
                    readPending = false; // 标识 readPending 为 false，表示本次读操作已经读取到了有效数据，无需等待再一次的读操作
                    // 触发事件，将会引发pipeline的 入站 ChannelRead 事件传播，pipeline上执行，业务逻辑的处理就是在这个地方，将得到的数据 byteBuf(writeIndex->readIndex间数据) 传递出去
                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;
                } while (allocHandle.continueReading()); //  在进行下一次循环进行消息的读取前，会先执行该判断，判断是否可以继续的去读取消息。
                // 标识本次读循环（整个 while 循环）结束，记录这次读循环(while循环包含多次多操作)总共读了多少数据，方便计算【下次】分配大小
                allocHandle.readComplete(); // 因为是在 while 循环外部记录，所以上面的 allocHandle.allocate(allocator) 每次分配的 ByteBuf 的大小是一样的
                // 相当于完成本地读事件的处理，数据多的话，可能会读（read）很多次（16次），都会通过 fireChannelRead 传播出去，这里是读取完成，仅仅一次
                pipeline.fireChannelReadComplete(); // 触发 入站 ChannelReadComplete 事件，用于表示当前读操作的最后一个消息已经被 ChannelRead 所消费

                if (close) { // 说明对端已经关闭了连接。(即，读操作中读取的字节数量为-1，则表示远端已经关闭了)，则执行 closeOnRead(pipeline)
                    closeOnRead(pipeline); // 执行 shutdownInput() 或关闭 channel
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) { // 此时本次读操作已经读取到有效数据（读操作不被允许）&& 没有配置autoRead
                    removeReadOp(); // 说明此时用户不希望 Selector 去监听当前 SocketChannel 的读事件，用户可以根据业务逻辑的需要，用户希望在需要时，自己添加 OP_READ 事件到 Selector 中
                } // 那么此时需要将 OP_READ 事件从所感兴趣的事件中移除，这样 Selector 就不会继续监听该 SocketChannel 的读事件了。
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }
    // 同filterOutboundMessage(msg)呼应，只能写ByteBuf或FileRegion类型的数据
    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            final int localFlushedAmount = doWriteBytes(buf); // 将当前节点写出，模板方法，子类实现细节，就是通过 jdk channel 写出
            if (localFlushedAmount > 0) { // NIO在非阻塞模式下写操作可能返回0表示未写入数据
                in.progress(localFlushedAmount); // 记录进度
                if (!buf.isReadable()) {
                    in.remove(); // 写完之后，将当前节点删除
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }

            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();  // 其他类型不支持
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount(); // 1.拿到自旋锁迭代次数
        do {
            Object msg = in.current(); // 2.拿到第一个需要flush的节点的数据
            if (msg == null) { // 数据已全部写完
                // Wrote all messages.
                clearOpWrite(); // 清除OP_WRITE事件
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg); // 3.不断的自旋调用doWriteInternal方法，直到自旋次数小于或等于0为止
        } while (writeSpinCount > 0);

        incompleteWrite(writeSpinCount < 0); // 如果写了16次，还没写完， writeSpinCount < 0 = true
    }
    // 过滤非ByteBuf和FileRegion的对象
    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

            return newDirectBuffer(buf); // 所有的非直接内存转换成直接内存DirectBuffer
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) { // setOpWrite = writeSpinCount < 0
        // Did not write completely.
        if (setOpWrite) { // 写缓冲区已经满，还没写完
            setOpWrite(); // 设置继续关心OP_WRITE事件，缓存区满了，写不进去了，注册写事件，这样当写缓存有空间来继续接受数据时，该写事件就会被触发
        } else {  // 此时已进行写操作次数 writeSpinCount，但并没有写完
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.
            clearOpWrite(); // 写完后，一定要记得将OP_WRITE事件注销

            // Schedule flush again later so other tasks can be picked up in the meantime
            eventLoop().execute(flushTask); // 写完了，再提交一个flush()任务
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
