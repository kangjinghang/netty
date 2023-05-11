/*
 * Copyright 2015 The Netty Project
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
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;

import static io.netty.util.internal.ObjectUtil.checkPositive;

/**
 * Default implementation of {@link MaxMessagesRecvByteBufAllocator} which respects {@link ChannelConfig#isAutoRead()}
 * and also prevents overflow.
 */
public abstract class DefaultMaxMessagesRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {
    private volatile int maxMessagesPerRead;
    private volatile boolean respectMaybeMoreData = true;

    public DefaultMaxMessagesRecvByteBufAllocator() {
        this(1);
    }

    public DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead) {
        maxMessagesPerRead(maxMessagesPerRead);
    }

    @Override
    public int maxMessagesPerRead() {
        return maxMessagesPerRead;
    }

    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        checkPositive(maxMessagesPerRead, "maxMessagesPerRead");
        this.maxMessagesPerRead = maxMessagesPerRead;
        return this;
    }

    /**
     * Determine if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @param respectMaybeMoreData
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     * @return {@code this}.
     */
    public DefaultMaxMessagesRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        this.respectMaybeMoreData = respectMaybeMoreData;
        return this;
    }

    /**
     * Get if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @return
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     */
    public final boolean respectMaybeMoreData() {
        return respectMaybeMoreData;
    }

    /**
     * Focuses on enforcing the maximum messages per read condition for {@link #continueReading()}.
     */
    public abstract class MaxMessageHandle implements ExtendedHandle {
        private ChannelConfig config; // Channel的配置对象
        private int maxMessagePerRead; // 每个读循环可循环读取消息的最大次数。
        private int totalMessages; // 目前读循环已经读取的消息个数。即，在NIO传输模式下也就是读循环已经执行的循环次数
        private int totalBytesRead; // 目前已经读取到的消息字节总数
        private int attemptedBytesRead; // 本次将要进行的读操作，期望读取的字节数。也就是有这么多个字节等待被读取。
        private int lastBytesRead; // 最后一次读操作读取到的字节数。
        private final boolean respectMaybeMoreData = DefaultMaxMessagesRecvByteBufAllocator.this.respectMaybeMoreData;
        private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = new UncheckedBooleanSupplier() {
            @Override // 是不是"满载而归"，不是的话就连续读，比较贪心的一种做法
            public boolean get() { // 本次期望读取的字节数 == 真正读到的字节数，表示当前可能还有数据等待被读取
                return attemptedBytesRead == lastBytesRead; // 要读到 attemptedBytesRead 这么多才算可以停止了
            }
        };

        /**
         * Only {@link ChannelConfig#getMaxMessagesPerRead()} is used.
         */
        @Override // 重新设置config成员变量
        public void reset(ChannelConfig config) {
            this.config = config; // 重新设置config成员变量
            maxMessagePerRead = maxMessagesPerRead(); // 重置maxMessagePerRead，默认为 16
            totalMessages = totalBytesRead = 0; // 将 totalMessages、totalBytesRead 重置为0
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return alloc.ioBuffer(guess()); // 根据给定的缓冲区分配器，以及guess()所返回的预测的缓存区容量大小，构建一个新的缓冲区。
        }

        @Override
        public final void incMessagesRead(int amt) {
            totalMessages += amt;
        }
        // 设置最近一次读操作的读取字节数
        @Override
        public void lastBytesRead(int bytes) {
            lastBytesRead = bytes;
            if (bytes > 0) { // 只有当 bytes>0 时才会进行 totalBytesRead 的累加。因为当bytes<0时，不是真实的读取字节的数量了，而作为一个外部强制执行终止的标识
                totalBytesRead += bytes;
            }
        }

        @Override
        public final int lastBytesRead() {
            return lastBytesRead;
        }

        @Override
        public boolean continueReading() {
            return continueReading(defaultMaybeMoreSupplier);
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            return config.isAutoRead() && // 设置为可自动读取
                   (!respectMaybeMoreData || maybeMoreDataSupplier.get()) &&
                    // respectMaybeMoreData = false，表明不'慎重'对待可能的更多数据，只要有数据，就一直读，直到16次，当然，读不到
                    // 可能浪费一次系统call.
                    // respectMaybeMoreData = true，默认选项，会判断有更多数据的可能性（maybeMoreDataSupplier.get()），true 表示当前可能还有数据等待被读取
                    // 但是这个判断可能不是所有情况都准，所以才加了respectMaybeMoreData
                   totalMessages < maxMessagePerRead && // 已经读取的消息次数 < 一个读循环最大能读取消息的次数，即，还没读到16次
                   totalBytesRead > 0; // totalBytesRead的最大值是’Integer.MAX_VALUE’(即，2147483647)。所以，也限制了一个读循环最大能读取的字节数为2147483647
        }

        @Override
        public void readComplete() {
        }

        @Override
        public int attemptedBytesRead() {
            return attemptedBytesRead;
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            attemptedBytesRead = bytes;
        }
        // 返回已经读取的字节个数，若"totalBytesRead < 0"则说明已经读取的字节数已经操作了"Integer.MAX_VALUE"，则返回Integer.MAX_VALUE；否则返回真实的已经读取的字节数。
        protected final int totalBytesRead() {
            return totalBytesRead < 0 ? Integer.MAX_VALUE : totalBytesRead;
        }
    }
}
