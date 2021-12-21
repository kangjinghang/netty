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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > run   - a run is a collection of pages
 * > chunk - a chunk is a collection of runs
 * > in this code chunkSize = maxPages * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to {@link PoolArena#size2SizeIdx(int)} method.
 * This ensures that when we request for memory segments of size > pageSize the normalizedCapacity
 * equals the next nearest size in {@link SizeClasses}.
 *
 *
 *  A chunk has the following layout:
 *
 *     /-----------------\
 *     | run             |
 *     |                 |
 *     |                 |
 *     |-----------------|
 *     | run             |
 *     |                 |
 *     |-----------------|
 *     | unalloctated    |
 *     | (freed)         |
 *     |                 |
 *     |-----------------|
 *     | subpage         |
 *     |-----------------|
 *     | unallocated     |
 *     | (freed)         |
 *     | ...             |
 *     | ...             |
 *     | ...             |
 *     |                 |
 *     |                 |
 *     |                 |
 *     \-----------------/
 *
 *
 * handle:
 * -------
 * a handle is a long number, the bit layout of a run looks like:
 *
 * oooooooo ooooooos ssssssss ssssssue bbbbbbbb bbbbbbbb bbbbbbbb bbbbbbbb
 *
 * o: runOffset (page offset in the chunk), 15bit
 * s: size (number of pages) of this run, 15bit
 * u: isUsed?, 1bit
 * e: isSubpage?, 1bit
 * b: bitmapIdx of subpage, zero if it's not subpage, 32bit
 *
 * runsAvailMap:
 * ------
 * a map which manages all runs (used and not in used).
 * For each run, the first runOffset and last runOffset are stored in runsAvailMap.
 * key: runOffset
 * value: handle
 *
 * runsAvail:
 * ----------
 * an array of {@link PriorityQueue}.
 * Each queue manages same size of runs.
 * Runs are sorted by offset, so that we always allocate runs with smaller offset.
 *
 *
 * Algorithm:
 * ----------
 *
 *   As we allocate runs, we update values stored in runsAvailMap and runsAvail so that the property is maintained.
 *
 * Initialization -
 *  In the beginning we store the initial run which is the whole chunk.
 *  The initial run:
 *  runOffset = 0
 *  size = chunkSize
 *  isUsed = no
 *  isSubpage = no
 *  bitmapIdx = 0
 *
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) find the first avail run using in runsAvails according to size
 * 2) if pages of run is larger than request pages then split it, and save the tailing run
 *    for later using
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) find a not full subpage according to size.
 *    if it already exists just return, otherwise allocate a new PoolSubpage and call init()
 *    note that this subpage object is added to subpagesPool in the PoolArena when we init() it
 * 2) call subpage.allocate()
 *
 * Algorithm: [free(handle, length, nioBuffer)]
 * ----------
 * 1) if it is a subpage, return the slab back into this subpage
 * 2) if the subpage is not used or it is a run, then start free this run
 * 3) merge continuous avail runs
 * 4) save the merged run
 *
 */
final class PoolChunk<T> implements PoolChunkMetric {
    private static final int SIZE_BIT_LENGTH = 15;
    private static final int INUSED_BIT_LENGTH = 1;
    private static final int SUBPAGE_BIT_LENGTH = 1;
    private static final int BITMAP_IDX_BIT_LENGTH = 32;

    static final int IS_SUBPAGE_SHIFT = BITMAP_IDX_BIT_LENGTH;
    static final int IS_USED_SHIFT = SUBPAGE_BIT_LENGTH + IS_SUBPAGE_SHIFT;
    static final int SIZE_SHIFT = INUSED_BIT_LENGTH + IS_USED_SHIFT;
    static final int RUN_OFFSET_SHIFT = SIZE_BIT_LENGTH + SIZE_SHIFT;

    final PoolArena<T> arena; // 所处的PoolArena
    final Object base;
    final T memory; // 维护的内存块，用泛型区分是堆内存还是直接内存
    final boolean unpooled; // 表示这个PoolChunk是没进行池化操作的，主要为Huge的size的内存分配

    /**
     * store the first page and last page of each avail run
     */
    private final LongLongHashMap runsAvailMap; // 存储的是有用的run中的第一个和最后一个Page的句柄（handle）

    /**
     * manage all avail runs
     */
    private final LongPriorityQueue[] runsAvail; // 管理所有有用的run，这个数组的索引是SizeClasses中page2PageIdx计算出来的idx，就是表格里的pageIdx，即sizeClass中每个size一个优先队列进行存储

    /**
     * manage all subpages in this chunk
     */
    private final PoolSubpage<T>[] subpages; // 管理这个poolchunk中所有的poolSubPag

    private final int pageSize;  // 一个page的大小
    private final int pageShifts; // pageSize需要左移多少位
    private final int chunkSize; // 这个chunk的大小

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers; // 主要是对PooledByteBuf中频繁创建的ByteBuffer进行缓存，以避免由于频繁创建的对象导致频繁的GC

    int freeBytes; // 空闲的byte值

    PoolChunkList<T> parent; // 所处的PoolChunkLis
    PoolChunk<T> prev; // 所处双向链表前一个PoolChunk
    PoolChunk<T> next;  // 所处双向两边的后一个PoolChunk

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    @SuppressWarnings("unchecked")
    PoolChunk(PoolArena<T> arena, Object base, T memory, int pageSize, int pageShifts, int chunkSize, int maxPageIdx) {
        unpooled = false; // 不使用内存池
        this.arena = arena; // 该PoolChunk所属的PoolArena
        this.base = base; // 底层内存对齐偏移量
        this.memory = memory; // 底层的内存块，对于堆内存，它是一个byte数组，对于直接内存，它是(jvm)ByteBuffer，但无论是哪种形式，其内存大小默认都是16M。
        this.pageSize = pageSize; // page大小，默认为8K。
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize; // 整个PoolChunk的内存大小，默认为16777216，即16M。
        freeBytes = chunkSize;

        runsAvail = newRunsAvailqueueArray(maxPageIdx); // 初始化runsAvail
        runsAvailMap = new LongLongHashMap(-1); // 记录了每个内存块开始位置和结束位置的runOffset和handle映射。
        subpages = new PoolSubpage[chunkSize >> pageShifts];

        //insert initial run, offset = 0, pages = chunkSize / pageSize
        int pages = chunkSize >> pageShifts;
        long initHandle = (long) pages << SIZE_SHIFT;
        insertAvailRun(0, pages, initHandle); // 在runsAvail数组最后位置插入一个handle，该handle代表page偏移位置为0的地方可以分配16M的内存块

        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, Object base, T memory, int size) {
        unpooled = true;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        pageSize = 0;
        pageShifts = 0;
        runsAvailMap = null;
        runsAvail = null;
        subpages = null;
        chunkSize = size;
        cachedNioBuffers = null;
    }

    private static LongPriorityQueue[] newRunsAvailqueueArray(int size) {
        LongPriorityQueue[] queueArray = new LongPriorityQueue[size];
        for (int i = 0; i < queueArray.length; i++) {
            queueArray[i] = new LongPriorityQueue();
        }
        return queueArray;
    }
    // 在runsAvail数组最后位置插入一个handle，该handle代表page偏移位置为0的地方可以分配16M的内存块
    private void insertAvailRun(int runOffset, int pages, long handle) {
        int pageIdxFloor = arena.pages2pageIdxFloor(pages);
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        queue.offer(handle);

        //insert first page of run
        insertAvailRun0(runOffset, handle);
        if (pages > 1) {
            //insert last page of run
            insertAvailRun0(lastPage(runOffset, pages), handle);
        }
    }

    private void insertAvailRun0(int runOffset, long handle) {
        long pre = runsAvailMap.put(runOffset, handle);
        assert pre == -1;
    }

    private void removeAvailRun(long handle) {
        int pageIdxFloor = arena.pages2pageIdxFloor(runPages(handle));
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        removeAvailRun(queue, handle);
    }

    private void removeAvailRun(LongPriorityQueue queue, long handle) {
        queue.remove(handle);

        int runOffset = runOffset(handle);
        int pages = runPages(handle);
        //remove first page of run
        runsAvailMap.remove(runOffset);
        if (pages > 1) {
            //remove last page of run
            runsAvailMap.remove(lastPage(runOffset, pages));
        }
    }

    private static int lastPage(int runOffset, int pages) {
        return runOffset + pages - 1;
    }

    private long getAvailRunByOffset(int runOffset) {
        return runsAvailMap.get(runOffset);
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache cache) {
        final long handle;
        if (sizeIdx <= arena.smallMaxSizeIdx) { // sizeIdx表示的是sizeClass的size2sizeIdx计算的对应的sizeIdx，smallMaxSizeIdx表示的是最大的subPage的对应的索引，处理Small内存块申请
            // small
            handle = allocateSubpage(sizeIdx);
            if (handle < 0) {
                return false;
            }
            assert isSubpage(handle);
        } else { // 利用allocateRun分配多个整的page，处理Normal内存块申请，sizeIdx2size方法根据内存块索引查找对应内存块size
            // normal
            // runSize must be multiple of pageSize
            int runSize = arena.sizeIdx2size(sizeIdx);
            handle = allocateRun(runSize); // allocateRun方法负责分配Normal内存块，返回的handle存储了分配的内存块大小和偏移量。
            if (handle < 0) {
                return false;
            }
        }
        // 从cachedNioBuffers获取缓存的ByteBuffer，一个PoolChunk下的所有的这些ByteBuffer
        ByteBuffer nioBuffer = cachedNioBuffers != null? cachedNioBuffers.pollLast() : null;
        initBuf(buf, nioBuffer, handle, reqCapacity, cache); // 使用handle和底层内存类(ByteBuffer)初始化ByteBuf
        return true;
    }
    // 分配多个page操作，其主要操作是从runAvail中找到最接近当前需要分配的size的内存块，然后将其进行切分出需要分配的内存块，并将剩下的空闲块再存到runAvail和runsAvailMap中。
    private long allocateRun(int runSize) {
        int pages = runSize >> pageShifts; // 1.计算所需的page数量
        int pageIdx = arena.pages2pageIdx(pages); // 2.计算对应的pageIdx，因为runAvail这个数组用的则是这个idx为索引的。注意，pages2pageIdx方法会将申请内存大小对齐为上述Page表格中的一个size。例如申请172032字节(21个page)的内存块，pages2pageIdx方法计算结果为13，实际分配196608(24个page)的内存块。

        synchronized (runsAvail) {
            //find first queue which has at least one big enough run
            int queueIdx = runFirstBestFit(pageIdx); // 3.从当前的pageIdx开始遍历runsAvail，找到第一个handle。该handle上可以分配所需内存块。
            if (queueIdx == -1) {
                return -1;
            }

            //get run with min offset in this queue
            LongPriorityQueue queue = runsAvail[queueIdx];
            long handle = queue.poll();

            assert handle != LongPriorityQueue.NO_VALUE && !isUsed(handle) : "invalid handle: " + handle;

            removeAvailRun(queue, handle); // 4.从runsAvail，runsAvailMap移除该handle信息
            //  5.在第3步找到的handle上划分出所要的内存块。
            if (handle != -1) {
                handle = splitLargeRun(handle, pages); // 将这一块内存进行切分，剩余空闲的内存继续存储到ranAvail和runsAvailMap中
            }
            // 6.减少可用内存字节数
            freeBytes -= runSize(pageShifts, handle);
            return handle;
        }
    }

    private int calculateRunSize(int sizeIdx) {
        int maxElements = 1 << pageShifts - SizeClasses.LOG2_QUANTUM;
        int runSize = 0;
        int nElements;

        final int elemSize = arena.sizeIdx2size(sizeIdx);

        //find lowest common multiple of pageSize and elemSize
        do {
            runSize += pageSize;
            nElements = runSize / elemSize;
        } while (nElements < maxElements && runSize != nElements * elemSize);

        while (nElements > maxElements) {
            runSize -= pageSize;
            nElements = runSize / elemSize;
        }

        assert nElements > 0;
        assert runSize <= chunkSize;
        assert runSize >= elemSize;

        return runSize;
    }

    private int runFirstBestFit(int pageIdx) {
        if (freeBytes == chunkSize) {
            return arena.nPSizes - 1;
        }
        for (int i = pageIdx; i < arena.nPSizes; i++) {
            LongPriorityQueue queue = runsAvail[i];
            if (queue != null && !queue.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private long splitLargeRun(long handle, int needPages) {
        assert needPages > 0;
        // totalPages，从handle中获取的当前位置可用page数。
        int totalPages = runPages(handle);
        assert needPages <= totalPages;
        // remPages，分配后剩余的page数
        int remPages = totalPages - needPages;
        // 剩余page数大于0
        if (remPages > 0) {
            int runOffset = runOffset(handle);

            // keep track of trailing unused pages for later use
            int availOffset = runOffset + needPages; // availOffset，计算剩余page开始偏移量
            long availRun = toRunHandle(availOffset, remPages, 0); // 生成一个新的handle
            insertAvailRun(availOffset, remPages, availRun); // 将availRun插入到runsAvail，runsAvailMap中

            // not avail
            return toRunHandle(runOffset, needPages, 1);
        }

        //mark it as used
        handle |= 1L << IS_USED_SHIFT;
        return handle;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity. Any PoolSubpage created / initialized here is added to
     * subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param sizeIdx sizeIdx of normalized size
     *
     * @return index in memoryMap
     */
    private long allocateSubpage(int sizeIdx) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx); // 根据sizeIdx计算出其对应的PoolSubpage，arena以sizeIdx为key存储了一个散列表来存储PoolSubpage，其每个链表的头都是一个特殊的不做内存分配的PoolSubpage的head，对于其对应链表的操作都需要对这个链表的head加锁
        synchronized (head) {
            //allocate a new run
            int runSize = calculateRunSize(sizeIdx); // 计算第一个对这个sizeIdx对应的size与pageSize的最小公倍数
            //runSize must be multiples of pageSize
            long runHandle = allocateRun(runSize); // 分配一个Normal内存块，作为PoolSubpage的底层内存块，大小为Small内存块size和pageSize最小公倍数
            if (runHandle < 0) {
                return -1;
            }
            // 构建PoolSubpage runOffset，即Normal内存块偏移量，也是该PoolSubpage在整个Chunk中的偏移量elemSize，Small内存块size
            int runOffset = runOffset(runHandle);
            assert subpages[runOffset] == null;
            int elemSize = arena.sizeIdx2size(sizeIdx);
            // 把这个分配的runSize切分为runSize/elemSize个相同的elemSize大小的subPage。利用PoolSubpage进行分配
            PoolSubpage<T> subpage = new PoolSubpage<T>(head, this, pageShifts, runOffset,
                               runSize(pageShifts, runHandle), elemSize);

            subpages[runOffset] = subpage; // 将这个subPage存在subpages中
            return subpage.allocate(); // 在subpage上分配内存块
        }
    }

    /**
     * Free a subpage or a run of pages When a subpage is freed from PoolSubpage, it might be added back to subpage pool
     * of the owning PoolArena. If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize,
     * we can completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, int normCapacity, ByteBuffer nioBuffer) {
        if (isSubpage(handle)) { // 释放的是subPage
            int sizeIdx = arena.size2SizeIdx(normCapacity); // 查找head节点，同步
            PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);

            int sIdx = runOffset(handle);
            PoolSubpage<T> subpage = subpages[sIdx];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            synchronized (head) {
                if (subpage.free(head, bitmapIdx(handle))) { // 调用subpage#free释放Small内存块，返回true则表示这块PoolSubPage还在用，不释放PoolSubpage内存块。返回false，将继续向下执行，这时会释放PoolSubpage整个内存块
                    //the subpage is still used, do not free it
                    return;
                }
                assert !subpage.doNotDestroy;
                // Null out slot in the array as it was freed and we should not use it anymore.
                subpages[sIdx] = null;
            }
        }

        //start free run
        int pages = runPages(handle); // 计算释放的page数

        synchronized (runsAvail) {
            // collapse continuous runs, successfully collapsed runs
            // will be removed from runsAvail and runsAvailMap
            long finalRun = collapseRuns(handle); // 如果可以，将前后相邻的可用内存块进行合并
            // 插入新的handle
            //set run as not used
            finalRun &= ~(1L << IS_USED_SHIFT); // 将IS_USED和IS_SUBPAGE标志位设置为0
            //if it is a subpage, set it to run
            finalRun &= ~(1L << IS_SUBPAGE_SHIFT); // 将IS_USED和IS_SUBPAGE标志位设置为0

            insertAvailRun(runOffset(finalRun), runPages(finalRun), finalRun); //将合并后的句柄存储到runAvail和runsAvailMap中
            freeBytes += pages << pageShifts;
        }
        // 将这个ByteBuf创建的ByteBuffer存到cachedNioBuffers缓存中
        if (nioBuffer != null && cachedNioBuffers != null &&
            cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    private long collapseRuns(long handle) {
        return collapseNext(collapsePast(handle)); // collapsePast方法合并前面的可用内存块，collapseNext方法合并后面的可用内存块
    }

    private long collapsePast(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long pastRun = getAvailRunByOffset(runOffset - 1); // 从runsAvailMap中找到下一个内存块的handle。
            if (pastRun == -1) {
                return handle;
            }

            int pastOffset = runOffset(pastRun);
            int pastPages = runPages(pastRun);
            // 如果是连续的内存块，则移除下一个内存块handle，并将其page合并生成一个新的handle。
            //is continuous
            if (pastRun != handle && pastOffset + pastPages == runOffset) {
                //remove past run
                removeAvailRun(pastRun);
                handle = toRunHandle(pastOffset, pastPages + runPages, 0);
            } else {
                return handle;
            }
        }
    }

    private long collapseNext(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long nextRun = getAvailRunByOffset(runOffset + runPages);
            if (nextRun == -1) {
                return handle;
            }

            int nextOffset = runOffset(nextRun);
            int nextPages = runPages(nextRun);

            //is continuous
            if (nextRun != handle && runOffset + runPages == nextOffset) {
                //remove next run
                removeAvailRun(nextRun);
                handle = toRunHandle(runOffset, runPages + nextPages, 0);
            } else {
                return handle;
            }
        }
    }

    private static long toRunHandle(int runOffset, int runPages, int inUsed) {
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) runPages << SIZE_SHIFT
               | (long) inUsed << IS_USED_SHIFT;
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                 PoolThreadCache threadCache) {
        if (isRun(handle)) {
            buf.init(this, nioBuffer, handle, runOffset(handle) << pageShifts,
                     reqCapacity, runSize(pageShifts, handle), arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                            PoolThreadCache threadCache) {
        int runOffset = runOffset(handle);
        int bitmapIdx = bitmapIdx(handle);

        PoolSubpage<T> s = subpages[runOffset];
        assert s.doNotDestroy;
        assert reqCapacity <= s.elemSize;

        int offset = (runOffset << pageShifts) + bitmapIdx * s.elemSize;
        buf.init(this, nioBuffer, handle, offset, reqCapacity, s.elemSize, threadCache);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }

    static int runOffset(long handle) {
        return (int) (handle >> RUN_OFFSET_SHIFT);
    }

    static int runSize(int pageShifts, long handle) {
        return runPages(handle) << pageShifts;
    }

    static int runPages(long handle) {
        return (int) (handle >> SIZE_SHIFT & 0x7fff);
    }

    static boolean isUsed(long handle) {
        return (handle >> IS_USED_SHIFT & 1) == 1L;
    }

    static boolean isRun(long handle) {
        return !isSubpage(handle);
    }

    static boolean isSubpage(long handle) {
        return (handle >> IS_SUBPAGE_SHIFT & 1) == 1L;
    }

    static int bitmapIdx(long handle) {
        return (int) handle;
    }
}
