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

package io.netty.buffer;

import static io.netty.buffer.PoolChunk.RUN_OFFSET_SHIFT;
import static io.netty.buffer.PoolChunk.SIZE_SHIFT;
import static io.netty.buffer.PoolChunk.IS_USED_SHIFT;
import static io.netty.buffer.PoolChunk.IS_SUBPAGE_SHIFT;
import static io.netty.buffer.SizeClasses.LOG2_QUANTUM;
// PoolSubpage负责管理Small内存块。一个PoolSubpage中的内存块size都相同，该size对应SizeClasses#sizeClasses表格的一个索引index。
final class PoolSubpage<T> implements PoolSubpageMetric { // PoolSubpage实际上就是PoolChunk中的一个Normal内存块，大小为其管理的内存块size与pageSize最小公倍数。

    final PoolChunk<T> chunk; // 所属的chunk
    private final int pageShifts; // page需要移动的位数
    private final int runOffset; // 在poolChunk所处的位置
    private final int runSize;
    private final long[] bitmap; // 每个long元素上每个bit位都可以代表一个内存块是否使用。

    PoolSubpage<T> prev; // 链表的前一个节点
    PoolSubpage<T> next; // 链表的后一个节点

    boolean doNotDestroy; // 标识这个PoolSubpage从pool移除，已经被释放了
    int elemSize; // 一块内存的大小
    private int maxNumElems; // 最大的内存数量
    private int bitmapLength; // 这个bitmap的长度
    private int nextAvail; // 下一个可用的内存块坐标缓存
    private int numAvail; // 可用的内存块的数量

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage() {
        chunk = null;
        pageShifts = -1;
        runOffset = -1;
        elemSize = -1;
        runSize = -1;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int pageShifts, int runOffset, int runSize, int elemSize) {
        this.chunk = chunk;
        this.pageShifts = pageShifts;
        this.runOffset = runOffset;
        this.runSize = runSize;
        this.elemSize = elemSize;
        bitmap = new long[runSize >>> 6 + LOG2_QUANTUM]; //  bitmap长度为runSize / 64 / QUANTUM，从《内存对齐类SizeClasses》可以看到，runSize都是2^LOG2_QUANTUM的倍数。

        doNotDestroy = true;
        if (elemSize != 0) {
            maxNumElems = numAvail = runSize / elemSize; // elemSize：每个内存块的大小，maxNumElems：内存块数量
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6; // bitmapLength：bitmap使用的long元素个数，使用bitmap中一部分元素足以管理全部内存块。
            if ((maxNumElems & 63) != 0) { // (maxNumElems & 63) != 0，代表maxNumElems不能整除64，所以bitmapLength要加1，用于管理余下的内存块。
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        addToPool(head); //  添加到PoolSubpage链表中
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (numAvail == 0 || !doNotDestroy) { // numAvail==0表示已经分配完了，而!doNotDestroy则是表示poolSubpage已经从池子里面移除了
            return -1; // 通常PoolSubpage分配完成后会从PoolArena#smallSubpagePools中移除，不再在该PoolSubpage上分配内存，所以一般不会出现这种场景。
        }
        // 在bitmap中从前开始搜索第一个bit为0的可用内存块的坐标
        final int bitmapIdx = getNextAvail();
        int q = bitmapIdx >>> 6; // 获取该内存块在bitmap数组中第q元素。由于bitMap是long[]数组，所以这个bitmapIdx的后6位的2^6=64个数则表示的是这个long的每一位的坐标
        int r = bitmapIdx & 63; // 获取该内存块是bitmap数组中第q个元素的第r个bit位
        assert (bitmap[q] >>> r & 1) == 0;
        bitmap[q] |= 1L << r; // 将bitmap中对应位置的bit标识设置为1，表示已经被分配
        // 如果所用的内存块都被分配完，则从PoolArena的池中移除当前的PoolSubpage
        if (-- numAvail == 0) {
            removeFromPool();
        }
        // 计算出对应的handle来标识这个被分配块的内存位置
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);  // 将对应bit位设置为可以使用，释放后将当前释放的内存直接缓存

        if (numAvail ++ == 0) { // 在PoolSubpage的内存块全部被使用时，释放了某个内存块，这时重新加入到PoolArena内存池中。
            addToPool(head);
            /* When maxNumElems == 1, the maximum numAvail is also 1.
             * Each of these PoolSubpages will go in here when they do free operation.
             * If they return true directly from here, then the rest of the code will be unreachable
             * and they will not actually be recycled. So return true only on maxNumElems > 1. */
            if (maxNumElems > 1) {
                return true;
            }
        }

        if (numAvail != maxNumElems) { // 未完全释放，即还存在已分配内存块，返回true
            return true;
        } else { // 处理所有内存块已经完全释放的场景。
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) { // PoolArena#smallSubpagePools链表组成双向链表，链表中只有head和当前PoolSubpage时，当前PoolSubpage的prev，next都指向head。
                // Do not remove if this subpage is the only one left in the pool.
                return true; // 这时当前PoolSubpage是PoolArena中该链表最后一个PoolSubpage，不释放该PoolSubpage，以便下次申请内存时直接从该PoolSubpage上分配。
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false; // 内存空了的话，从PoolArena中移除，并返回false，这时PoolChunk会将释放对应Page节点。
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail; // 这里是内存缓存，这个主要是free后的一个位置将其设置为nextAvail则可以提升效率
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) { // 遍历bitmap
            long bits = bitmap[i];
            if (~bits != 0) { // ~bits != 0，表示存在一个bit位不为1，即存在可用内存块。
                return findNextAvail0(i, bits); // 找到long中第一个bit位置
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) { // 遍历64个bit位，
            if ((bits & 1) == 0) { // 检查最低bit位是否为0（可用），为0则返回val
                int val = baseVal | j; // val等于 (i << 6) | j，即i * 64 + j，该bit位在bitmap中是第几个bit位。
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1; // 右移一位，处理下一个bit位
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        int pages = runSize >> pageShifts;
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) pages << SIZE_SHIFT
               | 1L << IS_USED_SHIFT
               | 1L << IS_SUBPAGE_SHIFT
               | bitmapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + runOffset + ": not in use)";
        }

        return "(" + runOffset + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + runSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return 1 << pageShifts;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
