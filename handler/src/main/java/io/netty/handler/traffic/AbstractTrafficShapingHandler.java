/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.traffic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.TimeUnit;

import static io.netty.util.internal.ObjectUtil.checkPositive;

/**
 * <p>AbstractTrafficShapingHandler allows to limit the global bandwidth
 * (see {@link GlobalTrafficShapingHandler}) or per session
 * bandwidth (see {@link ChannelTrafficShapingHandler}), as traffic shaping.
 * It allows you to implement an almost real time monitoring of the bandwidth using
 * the monitors from {@link TrafficCounter} that will call back every checkInterval
 * the method doAccounting of this handler.</p>
 *
 * <p>If you want for any particular reasons to stop the monitoring (accounting) or to change
 * the read/write limit or the check interval, several methods allow that for you:</p>
 * <ul>
 * <li><tt>configure</tt> allows you to change read or write limits, or the checkInterval</li>
 * <li><tt>getTrafficCounter</tt> allows you to have access to the TrafficCounter and so to stop
 * or start the monitoring, to change the checkInterval directly, or to have access to its values.</li>
 * </ul> // 它允许你使用 TrafficCounter 来实现几乎实时的带宽监控，TrafficCounter 会在每个检测间期（checkInterval）调用这个处理器的doAccounting 方法
 */ // AbstractTrafficShapingHandler 允许限制全局的带宽（见GlobalTrafficShapingHandler）或者每个 session 的带宽（见ChannelTrafficShapingHandler）作为流量整形
public abstract class AbstractTrafficShapingHandler extends ChannelDuplexHandler {
    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractTrafficShapingHandler.class);
    /**
     * Default delay between two checks: 1s
     */
    public static final long DEFAULT_CHECK_INTERVAL = 1000;

   /**
    * Default max delay in case of traffic shaping
    * (during which no communication will occur).
    * Shall be less than TIMEOUT. Here half of "standard" 30s
    */
    public static final long DEFAULT_MAX_TIME = 15000;

    /**
     * Default max size to not exceed in buffer (write only).
     */
    static final long DEFAULT_MAX_SIZE = 4 * 1024 * 1024L;

    /**
     * Default minimal time to wait: 10ms
     */
    static final long MINIMAL_WAIT = 10;

    /**
     * Traffic Counter
     */
    protected TrafficCounter trafficCounter;

    /**
     * Limit in B/s to apply to write
     */
    private volatile long writeLimit;

    /**
     * Limit in B/s to apply to read
     */
    private volatile long readLimit;

    /**
     * Max delay in wait
     */
    protected volatile long maxTime = DEFAULT_MAX_TIME; // default 15 s

    /**
     * Delay between two performance snapshots
     */
    protected volatile long checkInterval = DEFAULT_CHECK_INTERVAL; // default 1 s

    static final AttributeKey<Boolean> READ_SUSPENDED = AttributeKey
            .valueOf(AbstractTrafficShapingHandler.class.getName() + ".READ_SUSPENDED");
    static final AttributeKey<Runnable> REOPEN_TASK = AttributeKey.valueOf(AbstractTrafficShapingHandler.class
            .getName() + ".REOPEN_TASK");

    /**
     * Max time to delay before proposing to stop writing new objects from next handlers
     */
    volatile long maxWriteDelay = 4 * DEFAULT_CHECK_INTERVAL; // default 4 s
    /**
     * Max size in the list before proposing to stop writing new objects from next handlers
     */
    volatile long maxWriteSize = DEFAULT_MAX_SIZE; // default 4MB

    /**
     * Rank in UserDefinedWritability (1 for Channel, 2 for Global TrafficShapingHandler).
     * Set in final constructor. Must be between 1 and 31
     */
    final int userDefinedWritabilityIndex;

    /**
     * Default value for Channel UserDefinedWritability index
     */
    static final int CHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX = 1;

    /**
     * Default value for Global UserDefinedWritability index
     */
    static final int GLOBAL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX = 2;

    /**
     * Default value for GlobalChannel UserDefinedWritability index
     */
    static final int GLOBALCHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX = 3;

    /**
     * @param newTrafficCounter
     *            the TrafficCounter to set
     */
    void setTrafficCounter(TrafficCounter newTrafficCounter) {
        trafficCounter = newTrafficCounter;
    }

    /**
     * @return the index to be used by the TrafficShapingHandler to manage the user defined writability.
     *              For Channel TSH it is defined as {@value #CHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX},
     *              for Global TSH it is defined as {@value #GLOBAL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX},
     *              for GlobalChannel TSH it is defined as
     *              {@value #GLOBALCHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX}.
     */
    protected int userDefinedWritabilityIndex() {
        return CHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX;
    }

    /**
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     * @param checkInterval
     *            The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed.
     * @param maxTime
     *            The maximum delay to wait in case of traffic excess.
     *            Must be positive.
     */
    protected AbstractTrafficShapingHandler(long writeLimit, long readLimit, long checkInterval, long maxTime) {
        this.maxTime = checkPositive(maxTime, "maxTime");

        userDefinedWritabilityIndex = userDefinedWritabilityIndex();
        this.writeLimit = writeLimit;
        this.readLimit = readLimit;
        this.checkInterval = checkInterval;
    }

    /**
     * Constructor using default max time as delay allowed value of {@value #DEFAULT_MAX_TIME} ms.
     * @param writeLimit
     *            0 or a limit in bytes/s
     * @param readLimit
     *            0 or a limit in bytes/s
     * @param checkInterval
     *            The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed.
     */
    protected AbstractTrafficShapingHandler(long writeLimit, long readLimit, long checkInterval) {
        this(writeLimit, readLimit, checkInterval, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using default Check Interval value of {@value #DEFAULT_CHECK_INTERVAL} ms and
     * default max time as delay allowed value of {@value #DEFAULT_MAX_TIME} ms.
     *
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     */
    protected AbstractTrafficShapingHandler(long writeLimit, long readLimit) {
        this(writeLimit, readLimit, DEFAULT_CHECK_INTERVAL, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using NO LIMIT, default Check Interval value of {@value #DEFAULT_CHECK_INTERVAL} ms and
     * default max time as delay allowed value of {@value #DEFAULT_MAX_TIME} ms.
     */
    protected AbstractTrafficShapingHandler() {
        this(0, 0, DEFAULT_CHECK_INTERVAL, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using NO LIMIT and
     * default max time as delay allowed value of {@value #DEFAULT_MAX_TIME} ms.
     *
     * @param checkInterval
     *            The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed.
     */
    protected AbstractTrafficShapingHandler(long checkInterval) {
        this(0, 0, checkInterval, DEFAULT_MAX_TIME);
    }

    /**
     * Change the underlying limitations and check interval.
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param newWriteLimit The new write limit (in bytes)
     * @param newReadLimit The new read limit (in bytes)
     * @param newCheckInterval The new check interval (in milliseconds)
     */
    public void configure(long newWriteLimit, long newReadLimit,
            long newCheckInterval) {
        configure(newWriteLimit, newReadLimit);
        configure(newCheckInterval);
    }

    /**
     * Change the underlying limitations.
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param newWriteLimit The new write limit (in bytes)
     * @param newReadLimit The new read limit (in bytes)
     */ // 配置新的写限制、读限制、检测间期。该方法会尽最大努力进行此更改，这意味着已经被延迟进行的流量将不会使用新的配置，它仅用于新的流量中
    public void configure(long newWriteLimit, long newReadLimit) {
        writeLimit = newWriteLimit;
        readLimit = newReadLimit;
        if (trafficCounter != null) {
            trafficCounter.resetAccounting(TrafficCounter.milliSecondFromNano());
        }
    }

    /**
     * Change the check interval.
     *
     * @param newCheckInterval The new check interval (in milliseconds)
     */
    public void configure(long newCheckInterval) {
        checkInterval = newCheckInterval;
        if (trafficCounter != null) {
            trafficCounter.configure(checkInterval);
        }
    }

    /**
     * @return the writeLimit
     */
    public long getWriteLimit() {
        return writeLimit;
    }

    /**
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param writeLimit the writeLimit to set
     */
    public void setWriteLimit(long writeLimit) {
        this.writeLimit = writeLimit;
        if (trafficCounter != null) {
            trafficCounter.resetAccounting(TrafficCounter.milliSecondFromNano());
        }
    }

    /**
     * @return the readLimit
     */
    public long getReadLimit() {
        return readLimit;
    }

    /**
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param readLimit the readLimit to set
     */
    public void setReadLimit(long readLimit) {
        this.readLimit = readLimit;
        if (trafficCounter != null) {
            trafficCounter.resetAccounting(TrafficCounter.milliSecondFromNano());
        }
    }

    /**
     * @return the checkInterval
     */
    public long getCheckInterval() {
        return checkInterval;
    }

    /**
     * @param checkInterval the interval in ms between each step check to set, default value being 1000 ms.
     */
    public void setCheckInterval(long checkInterval) {
        this.checkInterval = checkInterval;
        if (trafficCounter != null) {
            trafficCounter.configure(checkInterval);
        }
    }

    /**
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param maxTime
     *            Max delay in wait, shall be less than TIME OUT in related protocol.
     *            Must be positive.
     */
    public void setMaxTimeWait(long maxTime) {
        this.maxTime = checkPositive(maxTime, "maxTime");
    }

    /**
     * @return the max delay in wait to prevent TIME OUT
     */
    public long getMaxTimeWait() {
        return maxTime;
    }

    /**
     * @return the maxWriteDelay
     */
    public long getMaxWriteDelay() {
        return maxWriteDelay;
    }

    /**
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param maxWriteDelay the maximum Write Delay in ms in the buffer allowed before write suspension is set.
     *              Must be positive.
     */
    public void setMaxWriteDelay(long maxWriteDelay) {
        this.maxWriteDelay = checkPositive(maxWriteDelay, "maxWriteDelay");
    }

    /**
     * @return the maxWriteSize default being {@value #DEFAULT_MAX_SIZE} bytes.
     */
    public long getMaxWriteSize() {
        return maxWriteSize;
    }

    /**
     * <p>Note that this limit is a best effort on memory limitation to prevent Out Of
     * Memory Exception. To ensure it works, the handler generating the write should
     * use one of the way provided by Netty to handle the capacity:</p>
     * <p>- the {@code Channel.isWritable()} property and the corresponding
     * {@code channelWritabilityChanged()}</p>
     * <p>- the {@code ChannelFuture.addListener(new GenericFutureListener())}</p>
     *
     * @param maxWriteSize the maximum Write Size allowed in the buffer
     *            per channel before write suspended is set,
     *            default being {@value #DEFAULT_MAX_SIZE} bytes.
     */
    public void setMaxWriteSize(long maxWriteSize) {
        this.maxWriteSize = maxWriteSize;
    }

    /**
     * Called each time the accounting is computed from the TrafficCounters.
     * This method could be used for instance to implement almost real time accounting.
     *
     * @param counter
     *            the TrafficCounter that computes its performance
     */
    protected void doAccounting(TrafficCounter counter) {
        // NOOP by default
    }

    /**
     * Class to implement setReadable at fix time
     */
    static final class ReopenReadTimerTask implements Runnable {
        final ChannelHandlerContext ctx;
        ReopenReadTimerTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            Channel channel = ctx.channel();
            ChannelConfig config = channel.config();
            if (!config.isAutoRead() && isHandlerActive(ctx)) { // 如果 Channel的autoRead 为 false，并且 AbstractTrafficShapingHandler 的 READ_SUSPENDED 属性设置为 null 或 false
                // If AutoRead is False and Active is True, user make a direct setAutoRead(false)
                // Then Just reset the status
                if (logger.isDebugEnabled()) {
                    logger.debug("Not unsuspend: " + config.isAutoRead() + ':' +
                            isHandlerActive(ctx));
                }
                channel.attr(READ_SUSPENDED).set(false); // 直接将 Channel 的 READ_SUSPENDED 属性设置为 false
            } else {
                // Anything else allows the handler to reset the AutoRead
                if (logger.isDebugEnabled()) {
                    if (config.isAutoRead() && !isHandlerActive(ctx)) { // Channel 的 autoRead 为 true &&  AbstractTrafficShapingHandler 的 READ_SUSPENDED 属性设置为true
                        if (logger.isDebugEnabled()) {
                            logger.debug("Unsuspend: " + config.isAutoRead() + ':' +
                                    isHandlerActive(ctx));
                        }
                    } else {  // Channel 的 autoRead 为 true || AbstractTrafficShapingHandler 的 READ_SUSPENDED 属性设置为true
                        if (logger.isDebugEnabled()) {
                            logger.debug("Normal unsuspend: " + config.isAutoRead() + ':'
                                    + isHandlerActive(ctx));
                        }
                    }
                }
                channel.attr(READ_SUSPENDED).set(false); // 将 READ_SUSPENDED 属性设置为false
                config.setAutoRead(true); // 核心，恢复读，（该操作底层会将该Channel的OP_READ事件重新注册为感兴趣的事件，这样Selector就会监听该Channel的读就绪事件了）
                channel.read();
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Unsuspend final status => " + config.isAutoRead() + ':'
                        + isHandlerActive(ctx));
            }
        }
    }

    /**
     * Release the Read suspension
     */
    void releaseReadSuspended(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        channel.attr(READ_SUSPENDED).set(false);
        channel.config().setAutoRead(true);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        // 如果 TS 的handler 放错了位置，接收的不是 ByteBuf 之类的，则直接跳过了
        long size = calculateSize(msg);
        long now = TrafficCounter.milliSecondFromNano();
        if (size > 0) { // 当数据不是 ByteBuf 时，size 计算出是-1，所以不会走到流量整形里面，所以 handler 的位置很重要
            // compute the number of ms to wait before reopening the channel
            long wait = trafficCounter.readTimeToWait(size, readLimit, maxTime, now); // 根据数据的大小、设定的readLimit、最大延迟时间等计算得到下一次开启读操作需要的延迟时间（距当前时间而言）wait(毫秒)
            wait = checkWaitReadTime(ctx, wait, now); // 只有当计算出的下一次读操作的时间大于了MINIMAL_WAIT(10毫秒)，并且当前Channel是自动读取的，且“读操作”处于“开启”状态时，才会去暂停读操作
            if (wait >= MINIMAL_WAIT) { // At least 10ms seems a minimal，如果小于最小等待时间，就意义不大了
                // time in order to try to limit the traffic
                // Only AutoRead AND HandlerActive True means Context Active
                Channel channel = ctx.channel();
                ChannelConfig config = channel.config();
                if (logger.isDebugEnabled()) {
                    logger.debug("Read suspend: " + wait + ':' + config.isAutoRead() + ':'
                            + isHandlerActive(ctx));
                }
                if (config.isAutoRead() && isHandlerActive(ctx)) { // 当前Channel为自动读取 && 当前的READ_SUSPENDED标识为null或false（即，读操作未被暂停）
                    config.setAutoRead(false); // 设置 autoRead 标记，（该操作底层会将该Channel的OP_READ事件从感兴趣的事件中移除，这样Selector就不会监听该Channel的读就绪事件了）
                    channel.attr(READ_SUSPENDED).set(true); // 将READ_SUSPENDED标识为true（说明，接下来的读操作会被暂停）
                    // Create a Runnable to reactive the read if needed. If one was create before it will just be
                    // reused to limit object creation
                    Attribute<Runnable> attr = channel.attr(REOPEN_TASK);
                    Runnable reopenTask = attr.get();
                    if (reopenTask == null) {
                        reopenTask = new ReopenReadTimerTask(ctx);
                        attr.set(reopenTask);
                    }
                    // 过 wait 时间后，重新打开"读"功能
                    ctx.executor().schedule(reopenTask, wait, TimeUnit.MILLISECONDS);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Suspend final status => " + config.isAutoRead() + ':'
                                + isHandlerActive(ctx) + " will reopened at: " + wait);
                    }
                }
            }
        }
        informReadOperation(ctx, now);
        ctx.fireChannelRead(msg); // 将当前的消息发送给 ChannelPipeline 中的下一个 ChannelInboundHandler
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        if (channel.hasAttr(REOPEN_TASK)) {
            //release the reopen task
            channel.attr(REOPEN_TASK).set(null);
        }
        super.handlerRemoved(ctx);
    }

    /**
     * Method overridden in GTSH to take into account specific timer for the channel.
     * @param wait the wait delay computed in ms
     * @param now the relative now time in ms
     * @return the wait to use according to the context
     */
    long checkWaitReadTime(final ChannelHandlerContext ctx, long wait, final long now) {
        // no change by default
        return wait;
    }

    /**
     * Method overridden in GTSH to take into account specific timer for the channel.
     * @param now the relative now time in ms
     */
    void informReadOperation(final ChannelHandlerContext ctx, final long now) {
        // default noop
    }

    protected static boolean isHandlerActive(ChannelHandlerContext ctx) {
        Boolean suspended = ctx.channel().attr(READ_SUSPENDED).get();
        return suspended == null || Boolean.FALSE.equals(suspended);
    }

    @Override
    public void read(ChannelHandlerContext ctx) {
        if (isHandlerActive(ctx)) {
            // For Global Traffic (and Read when using EventLoop in pipeline) : check if READ_SUSPENDED is False
            ctx.read();
        }
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
            throws Exception {
        long size = calculateSize(msg); // 如果 TS 的handler 放错了位置，接收的不是 ByteBuf 之类的，则直接跳过了
        long now = TrafficCounter.milliSecondFromNano();
        if (size > 0) { // 需要"写"暂停
            // compute the number of ms to wait before continue with the channel
            long wait = trafficCounter.writeTimeToWait(size, writeLimit, maxTime, now);  // 根据数据的大小、设定的readLimit、最大延迟时间等计算得到下一次开启读操作需要的延迟时间（距当前时间而言）wait(毫秒)
            if (wait >= MINIMAL_WAIT) { // >= 10ms
                if (logger.isDebugEnabled()) {
                    logger.debug("Write suspend: " + wait + ':' + ctx.channel().config().isAutoRead() + ':'
                            + isHandlerActive(ctx));
                }
                submitWrite(ctx, msg, size, wait, now, promise); // 抽象方法，子类重写
                return;
            }
        }
        // to maintain order of write，注意这里，delay 是0，表示不等待
        submitWrite(ctx, msg, size, 0, now, promise);
    }

    @Deprecated
    protected void submitWrite(final ChannelHandlerContext ctx, final Object msg,
            final long delay, final ChannelPromise promise) {
        submitWrite(ctx, msg, calculateSize(msg),
                delay, TrafficCounter.milliSecondFromNano(), promise);
    }

    abstract void submitWrite(
            ChannelHandlerContext ctx, Object msg, long size, long delay, long now, ChannelPromise promise);

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        setUserDefinedWritability(ctx, true);
        super.channelRegistered(ctx);
    }
    // 将 ChannelOutboundBuffer 中的 unwritable 属性值的相应标志位置位（unwritable关系到isWritable方法是否会返回true。以及会在unwritable从0到非0间变化时触发ChannelWritabilityChanged事件）
    void setUserDefinedWritability(ChannelHandlerContext ctx, boolean writable) {
        ChannelOutboundBuffer cob = ctx.channel().unsafe().outboundBuffer();
        if (cob != null) {
            cob.setUserDefinedWritability(userDefinedWritabilityIndex, writable); // 将 ChannelOutboundBuffer 中的 unwritable 属性值的相应标志位置位，触发 ChannelWritabilityChanged 事件
        }
    }

    /**
     * Check the writability according to delay and size for the channel.
     * Set if necessary setUserDefinedWritability status.
     * @param delay the computed delay
     * @param queueSize the current queueSize
     */
    void checkWriteSuspend(ChannelHandlerContext ctx, long delay, long queueSize) {
        // 可能会 OOM 了，或者需要"等待"的时间长了，就不建议再写了。
        // 类似景点，发现排队时间过长，或者人满为患了，这时候发出公告，不建议大家再进来了
        if (queueSize > maxWriteSize || delay > maxWriteDelay) { // 检查单个 Channel 待发送的数据包是否超过了 maxWriteSize（默认4M），或者延迟时间是否超过了 maxWriteDelay（默认4s）
            setUserDefinedWritability(ctx, false);
        }
    }
    /**
     * Explicitly release the Write suspended status.
     */
    void releaseWriteSuspended(ChannelHandlerContext ctx) { // 来释放写暂停
        setUserDefinedWritability(ctx, true); // 方法底层会将 ChannelOutboundBuffer 中的 unwritable 属性值相应的标志位重置
    }

    /**
     * @return the current TrafficCounter (if
     *         channel is still connected)
     */
    public TrafficCounter trafficCounter() {
        return trafficCounter;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(290)
            .append("TrafficShaping with Write Limit: ").append(writeLimit)
            .append(" Read Limit: ").append(readLimit)
            .append(" CheckInterval: ").append(checkInterval)
            .append(" maxDelay: ").append(maxWriteDelay)
            .append(" maxSize: ").append(maxWriteSize)
            .append(" and Counter: ");
        if (trafficCounter != null) {
            builder.append(trafficCounter);
        } else {
            builder.append("none");
        }
        return builder.toString();
    }

    /**
     * Calculate the size of the given {@link Object}.
     *
     * This implementation supports {@link ByteBuf}, {@link ByteBufHolder} and {@link FileRegion}.
     * Sub-classes may override this.
     * @param msg the msg for which the size should be calculated.
     * @return size the size of the msg or {@code -1} if unknown.
     */
    protected long calculateSize(Object msg) { // 计算本次读取到的消息的字节数，不是 ByteBuf 之类的，返回-1
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        return -1;
    }
}
