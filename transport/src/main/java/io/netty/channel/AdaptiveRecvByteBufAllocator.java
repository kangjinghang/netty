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
package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64; // 默认缓冲区的最小容量大小为64
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;  // 默认缓冲区的容量大小为1024
    static final int DEFAULT_MAXIMUM = 65536; // 默认缓冲区的最大容量大小为65536
    // 在调整缓冲区大小时，若是增加缓冲区容量，那么增加的索引值。比如，当前缓冲区的大小为SIZE_TABLE[20],若预测下次需要创建的缓冲区需要增加容量大小，则新缓冲区的大小为SIZE_TABLE[20 + INDEX_INCREMENT]
    private static final int INDEX_INCREMENT = 4;
    private static final int INDEX_DECREMENT = 1; // 在调整缓冲区大小时，若是减少缓冲区容量，那么减少的索引值。比如，当前缓冲区的大小为SIZE_TABLE[20],若预测下次需要创建的缓冲区需要减小容量大小，新缓冲区的大小为SIZE_TABLE[20 - INDEX_DECREMENT]
    // 因为AdaptiveRecvByteBufAllocator作用是可自动适配每次读事件使用的buffer的大小。这样当需要对buffer大小做调整时，只要根据一定逻辑从SIZE_TABLE中取出值，然后根据该值创建新buffer即可
    private static final int[] SIZE_TABLE;  // 为预定义好的以从小到大的顺序设定的可分配缓冲区的大小值的数组

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) { // 依次往sizeTable添加元素：[16 , (512-16)]之间16的倍数。即，16、32、48...496
            sizeTable.add(i);
        }
        // 再往sizeTable中添加元素：[512 , 512 * (2^N))，N > 1; 直到数值超过Integer的限制(2^31 - 1)
        // Suppress a warning since i becomes negative when an integer overflow happens
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i);
        }
        // 根据sizeTable长度构建一个静态成员常量数组SIZE_TABLE，并将sizeTable中的元素赋值给SIZE_TABLE数组
        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();
    // 二分查找法，查找size在 SIZE_TABLE 中的位置
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) { // 如果size存在于 SIZE_TABLE 中，则返回对应的索引值
                return mid;
            } else {
                return mid + 1; // 返回接近于size大小的 SIZE_TABLE 数组元素的索引值
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex; // 缓冲区最小容量对应于 SIZE_TABLE 中的下标位置，同外部类AdaptiveRecvByteBufAllocator是一个值
        private final int maxIndex; // 缓冲区最大容量对应于SIZE_TABLE中的下标位置，同外部类AdaptiveRecvByteBufAllocator是一个值
        private int index; // 缓冲区默认容量对应于SIZE_TABLE中的下标位置，外部类AdaptiveRecvByteBufAllocator记录的是容量大小值，而HandleImpl中记录是其值对应于SIZE_TABLE中的下标位置
        private int nextReceiveBufferSize; // 下一次创建缓冲区时的其容量的大小。
        private boolean decreaseNow; // 在record()方法中使用，用于标识是否需要减少下一次创建的缓冲区的大小。

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex; // 给成员变量 minIndex、maxIndex 赋值
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial); // 通过getSizeTableIndex(initial) 计算出初始容量在 SIZE_TABLE 的索引值，将其赋值为成员变量index
            nextReceiveBufferSize = SIZE_TABLE[index]; // 将初始容量大小(即，SIZE_TABLE[index])赋值给成员变量 nextReceiveBufferSize
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() { // 返回推测的缓冲区容量大小，即，返回成员变量nextReceiveBufferSize的值
            return nextReceiveBufferSize;
        }

        /**
         * 接受数据 buffer 的容量会尽可能的足够大以接受数据
         */
        private void record(int actualReadBytes) {
            // 尝试是否可以减小分配的空间仍然满足需求：
            // 尝试方法：当前实际读取的 size 是否小于或等于打算缩小的尺寸
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) { // decreaseNow：连续2次尝试减小都可以
                    index = max(index - INDEX_DECREMENT, minIndex); // 减小。重新给成员变量 index 赋值为为 index - 1
                    nextReceiveBufferSize = SIZE_TABLE[index]; // 根据算出来的新的index索引，给成员变量 nextReceiveBufferSize 重新赋值 SIZE_TABLE[index]
                    decreaseNow = false; // 将 decreaseNow 置为 false，该字段用于表示是否有 "连续" 的两次真实读取的数据满足可减少容量大小的情况
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) { // 本次读循环真实读取的字节总数 >= 预测的缓冲区大小
                index = min(index + INDEX_INCREMENT, maxIndex); // 进行增加预测的缓冲区容量大小。新的index为 index + 4。若 index + 4 > maxIndex，则 index 新值为 maxIndex
                nextReceiveBufferSize = SIZE_TABLE[index]; // 根据算出来的新的index索引，给成员变量 nextReceiveBufferSize 重新赋值 SIZE_TABLE[index]
                decreaseNow = false; // 将decreaseNow置位false
            }
        }
        // 每次读循环完成后，会调用该方法。根据本次读循环读取的字节数来调整预测的缓冲区容量大小
        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex; // 缓冲区最小容量对应于SIZE_TABLE中的下标位置
    private final int maxIndex; // 缓冲区最大容量对应于SIZE_TABLE中的下标位置
    private final int initial;  // 缓冲区默认容量大小

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters. 使用指定的参数创建AdaptiveRecvByteBufAllocator对象。
     * 其中minimum、initial、maximum是正整数。然后通过 getSizeTableIndex()方法 获取相应容量在 SIZE_TABLE 中的索引位置。并将计算出来的索引赋值给相应的成员变量 minIndex、maxIndex。同时保证「SIZE_TABLE[minIndex] >= minimum」以及「SIZE_TABLE[maxIndex] <= maximum」
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() { // 创建一个 HandleImpl 对象，参数为  minIndex，maxIndex以及 initial 为AdaptiveRecvByteBufAllocator对象的成员变量值
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
