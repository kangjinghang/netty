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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link Channel} implementations which use a Selector based approach.
 */
public abstract class AbstractNioChannel extends AbstractChannel {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractNioChannel.class);

    private final SelectableChannel ch; // 包装的JDK Channel
    protected final int readInterestOp;  // Read事件，nioServerSockectChannel 的话是 OP_ACCEPT，其他OP_READ
    volatile SelectionKey selectionKey;  // JDK Channel对应的选择键。被doRegister() 时， selectionKey 中的 ops 是 0
    boolean readPending;  // 底层读事件进行标记
    private final Runnable clearReadPendingRunnable = new Runnable() {
        @Override
        public void run() {
            clearReadPending0();
        }
    };

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     */
    private ChannelPromise connectPromise;  // 连接异步结果
    private Future<?> connectTimeoutFuture; // 连接超时检测任务异步结果
    private SocketAddress requestedRemoteAddress; // 连接的远端地址

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     * @param readInterestOp    the ops to set to receive data from the {@link SelectableChannel}
     */
    protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent);
        this.ch = ch; // 保存到成员变量
        this.readInterestOp = readInterestOp; // 保存到 readInterestOp。nioServerSockectChannel 的话这里是 SelectionKey.OP_ACCEPT
        try { // 设置该channel为非阻塞模式，标准的JDK NIO编程的玩法
            ch.configureBlocking(false);
        } catch (IOException e) {
            try {
                ch.close();
            } catch (IOException e2) {
                logger.warn(
                            "Failed to close a partially initialized socket.", e2);
            }

            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    protected SelectableChannel javaChannel() {
        return ch;
    }

    @Override
    public NioEventLoop eventLoop() {
        return (NioEventLoop) super.eventLoop();
    }

    /**
     * Return the current {@link SelectionKey}
     */
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    /**
     * @deprecated No longer supported.
     * No longer supported.
     */
    @Deprecated
    protected boolean isReadPending() {
        return readPending;
    }

    /**
     * @deprecated Use {@link #clearReadPending()} if appropriate instead.
     * No longer supported.
     */
    @Deprecated
    protected void setReadPending(final boolean readPending) {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                setReadPending0(readPending);
            } else {
                eventLoop.execute(new Runnable() {
                    @Override
                    public void run() {
                        setReadPending0(readPending);
                    }
                });
            }
        } else {
            // Best effort if we are not registered yet clear readPending.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            this.readPending = readPending;
        }
    }

    /**
     * Set read pending to {@code false}.
     */
    protected final void clearReadPending() {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                clearReadPending0();
            } else {
                eventLoop.execute(clearReadPendingRunnable);
            }
        } else {
            // Best effort if we are not registered yet clear readPending. This happens during channel initialization.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            readPending = false;
        }
    }

    private void setReadPending0(boolean readPending) {
        this.readPending = readPending;
        if (!readPending) {
            ((AbstractNioUnsafe) unsafe()).removeReadOp();
        }
    }

    private void clearReadPending0() {
        readPending = false;
        ((AbstractNioUnsafe) unsafe()).removeReadOp();
    }

    /**
     * Special {@link Unsafe} sub-type which allows to access the underlying {@link SelectableChannel}
     */
    public interface NioUnsafe extends Unsafe {
        /**
         * Return underlying {@link SelectableChannel}
         */
        SelectableChannel ch(); // 对应NIO中的JDK实现的Channel

        /**
         * Finish connect
         */
        void finishConnect(); // 连接完成

        /**
         * Read from underlying {@link SelectableChannel}
         */
        void read(); // 从JDK的Channel中读取数据

        void forceFlush();
    }

    protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {

        protected final void removeReadOp() { // 将 OP_READ 从 SelectionKey 的 interestOps 集合中移除
            SelectionKey key = selectionKey();
            // Check first if the key is still valid as it may be canceled as part of the deregistration
            // from the EventLoop
            // See https://github.com/netty/netty/issues/2104
            if (!key.isValid()) { // selectionKey已被取消
                return;
            }
            int interestOps = key.interestOps();
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed
                key.interestOps(interestOps & ~readInterestOp); // 设置为不再感兴趣
            }
        }

        @Override
        public final SelectableChannel ch() {
            return javaChannel();
        }

        @Override
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) { // Channel已被关闭
                return;
            }

            try {
                if (connectPromise != null) { // 已有连接操作正在进行
                    // Already a connect in process.
                    throw new ConnectionPendingException();
                }

                boolean wasActive = isActive();
                if (doConnect(remoteAddress, localAddress)) { // 做 JDK 底层的 SocketChannel connect，返回值代表是否已经连接成功。模板方法，细节子类完成
                    fulfillConnectPromise(promise, wasActive); // 处理连接成功的情况
                } else { // 连接操作尚未完成
                    connectPromise = promise;
                    requestedRemoteAddress = remoteAddress;

                    // Schedule connect timeout. 处理连接超时的情况
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    if (connectTimeoutMillis > 0) {
                        connectTimeoutFuture = eventLoop().schedule(new Runnable() { // 用到了 NioEventLoop 的定时任务的功能
                            @Override
                            public void run() {
                                ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                                if (connectPromise != null && !connectPromise.isDone() // 定时任务执行时 connectPromise 已经失败
                                        && connectPromise.tryFailure(new ConnectTimeoutException(
                                                "connection timed out: " + remoteAddress))) {
                                    close(voidPromise()); // 关闭 channel
                                }
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }

                    promise.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            if (future.isCancelled()) { // 任务被取消
                                if (connectTimeoutFuture != null) { // 连接操作取消则连接超时检测任务取消
                                    connectTimeoutFuture.cancel(false);
                                }
                                connectPromise = null;
                                close(voidPromise());  // 关闭 channel
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                closeIfClosed();
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            if (promise == null) { // 当异步的“连接尝试”操作通过取消来关闭了，那么则直接返回。因为当“连接尝试”操作被取消时，connectPromise 会被置为 null。
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            boolean active = isActive(); // 获取当前 SocketChannel 的状态，因为此时SocketChannel.finishConnect() 已经被调用过了，因此该方法会返回true
            // 尝试将当前的异步的“连接尝试”操作标记为成功。如果用户取消了“连接尝试，那么返回 false，否则返回true。并且如果有 ChannelFutureListener 注册到了这个 promise 上，那么监听器的 operationComplete 方法将被回调
            // trySuccess() will return false if a user cancelled the connection attempt.
            boolean promiseSet = promise.trySuccess();  // False表示用户调用connect操作后，用户调用了cancel来取消该操作

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && active) { // 此时用户没有取消Connect操作
                pipeline().fireChannelActive(); // 触发【入站】ChannelActive 事件
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {
                close(voidPromise()); // 操作已被用户取消，关闭Channel，并触发 channelInactive 事件
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(cause);
            closeIfClosed();
        }
        // 注意，当连接操作既没有被取消也没有超时的情况下，该方法才会被 EventLoop 调用。
        @Override
        public final void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.

            assert eventLoop().inEventLoop();

            try {
                boolean wasActive = isActive(); // 因为此时 SocketChannel.finishConnect() 还没调用，所以 ch.isConnected() 将返回 false，因此 isActive() 的结果为 false。
                doFinishConnect(); // 模板方法，该方法会调用 SocketChannel.finishConnect() 来标识连接的完成
                fulfillConnectPromise(connectPromise, wasActive); // 首次Active触发Active事件
            } catch (Throwable t) {
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
            } finally {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
                if (connectTimeoutFuture != null) { // 员变量 connectTimeoutFuture 非空，则说明该“连接尝试”操作设置了一个连接超时时间
                    connectTimeoutFuture.cancel(false); // 因为此时连接已经完成了，我们就可以取消超时检测任务了
                }
                // 当程序执行完 fulfillConnectPromise方法中的 promise.trySuccess() 之后，以及在执行finally代码块之前，“连接尝试”的已经完成，并且 ChannelPromise 已标记为了true。
                // 但是此时设置的连接超时时间恰好到了并且连接超时任务（还没等到 connectTimeoutFuture#cancel(false) 操作，connectTimeoutFuture 就触发了）已被执行，此时超时任务发现 ChannelPromise 的状态已经被标识过了 null 也就不会进行关闭channel的操作
                connectPromise = null; //
            }
        }

        @Override
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            if (!isFlushPending()) { // 来判断 flush 操作是否需要被挂起
                super.flush0();
            }
        }

        @Override
        public final void forceFlush() {
            // directly call super.flush0() to force a flush now
            super.flush0();
        }
        // 来判断flush操作是否需要被挂起。当写缓冲区已经满了，返回 true，等写缓冲区有空间时，SelectionKey.OP_WRITE 事件就会被触发，到时 NioEventLoop 的事件循环就会调用 forceFlush() 方法来继续将为写出的数据写出
        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();// 判断当前 NioSocketChannel 的 SelectionKey.OP_WRITE 事件是否有被注册到对应的 Selector 上
            return selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0; // 如果有，则说明当前写缓冲区已经满了，返回 true
        }
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof NioEventLoop;
    }

    @Override
    protected void doRegister() throws Exception {
        boolean selected = false;
        for (;;) {
            try { // 调用 JDK 的 API，将 channel 注册到 nioEventLoop 里绑定的 selector ，ops = 0 ，同时把自己 NioChannel 作为 attachment 绑定
                selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this); // selectionKey 中的 ops 是 0
                return;
            } catch (CancelledKeyException e) {
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                    eventLoop().selectNow(); // 选择键取消重新selectNow()，清除因取消操作而缓存的选择键
                    selected = true;
                } else {
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e;
                }
            }
        }
    }

    @Override
    protected void doDeregister() throws Exception {
        eventLoop().cancel(selectionKey()); // 设置取消选择键
    }

    @Override
    protected void doBeginRead() throws Exception {
        // Channel.read() or ChannelHandlerContext.read() was called
        // 前面register步骤返回的对象，前面我们在 doRegister的时候，注册的 ops 是0
        final SelectionKey selectionKey = this.selectionKey;
        if (!selectionKey.isValid()) { // 选择键被取消而不再有效
            return;
        }

        readPending = true; // 设置底层读事件正在进行
        // 获取前面监听的 ops = 0，也就是什么都不监听。
        final int interestOps = selectionKey.interestOps();
        // 假设之前没有监听readInterestOp，则监听readInterestOp
        // 就是之前new NioServerSocketChannel 时 super(null, channel, SelectionKey.OP_ACCEPT); 传进来保存到则监听readInterestOp的
        if ((interestOps & readInterestOp) == 0) { // interestOps 不包括 readInterestOp，https://cloud.tencent.com/developer/article/1603990
            logger.info("interest ops：{}", readInterestOp);
            selectionKey.interestOps(interestOps | readInterestOp); // 前面都没准备好。真正的注册 interestOps，【做好连接的准备】了。
        }
    }

    /**
     * Connect to the remote peer
     */
    protected abstract boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception;

    /**
     * Finish the connect
     */
    protected abstract void doFinishConnect() throws Exception;

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original one.
     * Note that this method does not create an off-heap copy if the allocation / deallocation cost is too high,
     * but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(buf);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        return buf;
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the specified holder.
     * The caller must ensure that the holder releases the original {@link ByteBuf} when the holder is released by
     * this method.  Note that this method does not create an off-heap copy if the allocation / deallocation cost is
     * too high, but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ReferenceCounted holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        if (holder != buf) {
            // Ensure to call holder.release() to give the holder a chance to release other resources than its content.
            buf.retain();
            ReferenceCountUtil.safeRelease(holder);
        }

        return buf;
    }

    @Override
    protected void doClose() throws Exception {
        ChannelPromise promise = connectPromise;
        if (promise != null) { // 连接操作还在进行，但用户调用close操作
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(new ClosedChannelException());
            connectPromise = null;
        }

        Future<?> future = connectTimeoutFuture;
        if (future != null) { // 如果有连接超时检测任务，则取消
            future.cancel(false);
            connectTimeoutFuture = null;
        }
    }
}
