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


import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

/**
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="https://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>.
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);
    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<byte[]> heapArena;
    final PoolArena<ByteBuffer> directArena;
    // 对不同类型的pageCaches
    // Hold the caches for the different size classes, which are tiny, small and normal.
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches;
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;

    private final int freeSweepAllocationThreshold; // 缓存命中多少次后进行trim操作，和allocations一起来确定trim的时机
    private final AtomicBoolean freed = new AtomicBoolean(); // 缓存是否被释放标

    private int allocations;  // 缓存命中次数

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int smallCacheSize, int normalCacheSize, int maxCachedBufferCapacity,
                    int freeSweepAllocationThreshold) {
        checkPositiveOrZero(maxCachedBufferCapacity, "maxCachedBufferCapacity");
        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold; // 分配次数的阈值
        this.heapArena = heapArena;
        this.directArena = directArena;
        if (directArena != null) {
            smallSubPageDirectCaches = createSubPageCaches( // 创建subPage缓存数组 ，smallCacheSize = 256， PoolArena.numSmallSubpagePools = 4
                    smallCacheSize, directArena.numSmallSubpagePools);

            normalDirectCaches = createNormalCaches( // 创建Normal缓存数组 ， normalCacheSize = 64 ， maxCachedBufferCapacity = 32K
                    normalCacheSize, maxCachedBufferCapacity, directArena);

            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
        }
        if (heapArena != null) {
            // Create the caches for the heap allocations
            smallSubPageHeapCaches = createSubPageCaches(
                    smallCacheSize, heapArena.numSmallSubpagePools);

            normalHeapCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, heapArena);

            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
        }

        // Only check if there are caches in use.
        if ((smallSubPageDirectCaches != null || normalDirectCaches != null
                || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: "
                    + freeSweepAllocationThreshold + " (expected: > 0)");
        }
    }

    private static <T> MemoryRegionCache<T>[] createSubPageCaches(
            int cacheSize, int numCaches) {
        if (cacheSize > 0 && numCaches > 0) {
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[numCaches]; // 创建 MemoryRegionCache 数组，Small/Normal 数组大小是 4/12
            for (int i = 0; i < cache.length; i++) {
                // TODO: maybe use cacheSize / cache.length
                cache[i] = new SubPageMemoryRegionCache<T>(cacheSize); // 初始化
            }
            return cache;
        } else {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> MemoryRegionCache<T>[] createNormalCaches(
            int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {
        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            int max = Math.min(area.chunkSize, maxCachedBufferCapacity); // 32K 和 16m 取小的 那就是 32K
            // Create as many normal caches as we support based on how many sizeIdx we have and what the upper
            // bound is that we want to cache in general.
            List<MemoryRegionCache<T>> cache = new ArrayList<MemoryRegionCache<T>>() ;
            for (int idx = area.numSmallSubpagePools; idx < area.nSizes && area.sizeIdx2size(idx) <= max ; idx++) {
                cache.add(new NormalMemoryRegionCache<T>(cacheSize));
            }
            return cache.toArray(new MemoryRegionCache[0]);
        } else {
            return null;
        }
    }

    // val > 0
    static int log2(int val) {
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int sizeIdx) {
        return allocate(cacheForSmall(area, sizeIdx), buf, reqCapacity);
    }

    /**
     * Try to allocate a normal buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int sizeIdx) {
        return allocate(cacheForNormal(area, sizeIdx), buf, reqCapacity);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) {
        if (cache == null) {
            // no cache found so just return false here
            return false;
        }
        boolean allocated = cache.allocate(buf, reqCapacity, this);
        if (++ allocations >= freeSweepAllocationThreshold) {
            allocations = 0;
            trim();
        }
        return allocated;
    }

    /**
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    boolean add(PoolArena<?> area, PoolChunk chunk, ByteBuffer nioBuffer,
                long handle, int normCapacity, SizeClass sizeClass) {
        int sizeIdx = area.size2SizeIdx(normCapacity);
        MemoryRegionCache<?> cache = cache(area, sizeIdx, sizeClass); // 获取缓存对应的数组
        if (cache == null) {
            return false;
        }
        return cache.add(chunk, nioBuffer, handle, normCapacity); // 添加到队列中
    }

    private MemoryRegionCache<?> cache(PoolArena<?> area, int sizeIdx, SizeClass sizeClass) {
        switch (sizeClass) {
        case Normal:
            return cacheForNormal(area, sizeIdx);
        case Small:
            return cacheForSmall(area, sizeIdx);
        default:
            throw new Error();
        }
    }

    /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            free(true);
        }
    }

    /**
     *  Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     */
    void free(boolean finalizer) { // 对所有的数组进行释放
        // As free() may be called either by the finalizer or by FastThreadLocal.onRemoval(...) we need to ensure
        // we only call this one time.
        if (freed.compareAndSet(false, true)) {
            int numFreed = free(smallSubPageDirectCaches, finalizer) +
                    free(normalDirectCaches, finalizer) +
                    free(smallSubPageHeapCaches, finalizer) +
                    free(normalHeapCaches, finalizer);

            if (numFreed > 0 && logger.isDebugEnabled()) {
                logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed,
                        Thread.currentThread().getName());
            }

            if (directArena != null) {
                directArena.numThreadCaches.getAndDecrement();
            }

            if (heapArena != null) {
                heapArena.numThreadCaches.getAndDecrement();
            }
        }
    }

    private static int free(MemoryRegionCache<?>[] caches, boolean finalizer) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        for (MemoryRegionCache<?> c: caches) {
            numFreed += free(c, finalizer);
        }
        return numFreed;
    }

    private static int free(MemoryRegionCache<?> cache, boolean finalizer) {
        if (cache == null) {
            return 0;
        }
        return cache.free(finalizer);
    }

    void trim() {
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        for (MemoryRegionCache<?> c: caches) {
            trim(c);
        }
    }

    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        cache.trim();
    }

    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int sizeIdx) {
        if (area.isDirect()) {
            return cache(smallSubPageDirectCaches, sizeIdx);
        }
        return cache(smallSubPageHeapCaches, sizeIdx);
    }

    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int sizeIdx) {
        // We need to substract area.numSmallSubpagePools as sizeIdx is the overall index for all sizes.
        int idx = sizeIdx - area.numSmallSubpagePools; // 获取数组下标  比如  1024 就是 下边为 1 ， 512 是 0 ， 2048 是 2
        if (area.isDirect()) {
            return cache(normalDirectCaches, idx); // 根据数组下标，获取对应Cache
        }
        return cache(normalHeapCaches, idx);
    }

    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int sizeIdx) {
        if (cache == null || sizeIdx > cache.length - 1) {
            return null;
        }
        return cache[sizeIdx];
    }

    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {
        SubPageMemoryRegionCache(int size) {
            super(size, SizeClass.Small);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity,
                PoolThreadCache threadCache) {
            chunk.initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity,
                PoolThreadCache threadCache) {
            chunk.initBuf(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    private abstract static class MemoryRegionCache<T> {
        private final int size; // 队列长度
        private final Queue<Entry<T>> queue; // 队列
        private final SizeClass sizeClass; // Small/Normal
        private int allocations; // 分配次数

        MemoryRegionCache(int size, SizeClass sizeClass) {
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);  // 对Size进行对齐,  Small = 256
            queue = PlatformDependent.newFixedMpscQueue(this.size); // 创建MPSC队列，即多个生产者单一消费者队列，长度是Size
            this.sizeClass = sizeClass;  // 记录类型
        }

        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         */
        protected abstract void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle,
                                        PooledByteBuf<T> buf, int reqCapacity, PoolThreadCache threadCache);

        /**
         * Add to cache if not already full.
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity) {
            Entry<T> entry = newEntry(chunk, nioBuffer, handle, normCapacity); // 新建ENTRY
            boolean queued = queue.offer(entry); //  添加到队列中
            if (!queued) { // 如果队列满了，直接回收entry对象进行下一次分配，不缓存
                // If it was not possible to cache the chunk, immediately recycle the entry
                entry.recycle();
            }

            return queued;
        }

        /**
         * Allocate something out of the cache if possible and remove the entry from the cache.
         */
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity, PoolThreadCache threadCache) {
            Entry<T> entry = queue.poll(); // 从队列头部取出
            if (entry == null) {
                return false;
            }
            initBuf(entry.chunk, entry.nioBuffer, entry.handle, buf, reqCapacity, threadCache); // 在之前ByteBuf同样的内存位置分配一个新的 ByteBuf 对象
            entry.recycle();

            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            ++ allocations; // 该值是内部量，和上一方法不同
            return true;
        }

        /**
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         */
        public final int free(boolean finalizer) {
            return free(Integer.MAX_VALUE, finalizer);
        }

        private int free(int max, boolean finalizer) {
            int numFreed = 0;
            for (; numFreed < max; numFreed++) {
                Entry<T> entry = queue.poll();
                if (entry != null) {
                    freeEntry(entry, finalizer);
                } else {
                    // all cleared
                    return numFreed; // 队列中所有节点都被释放
                }
            }
            return numFreed;
        }

        /**
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         */
        public final void trim() {
            int free = size - allocations;
            allocations = 0; // allocations 表示已经重新分配出去的ByteBuf个数

            // We not even allocated all the number that are
            if (free > 0) { // 在一定阈值内还没被分配出去的空间将被释放
                free(free, false); // 释放队列中的节点
            }
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private  void freeEntry(Entry entry, boolean finalizer) {
            PoolChunk chunk = entry.chunk;
            long handle = entry.handle;
            ByteBuffer nioBuffer = entry.nioBuffer;

            if (!finalizer) {
                // recycle now so PoolChunk can be GC'ed. This will only be done if this is not freed because of
                // a finalizer.
                entry.recycle();  // 回收entry对象
            }

            chunk.arena.freeChunk(chunk, handle, entry.normCapacity, sizeClass, nioBuffer, finalizer); // 释放实际的内存空间
        }

        static final class Entry<T> {
            final Handle<Entry<?>> recyclerHandle; // 回收该对象
            PoolChunk<T> chunk; // ByteBuf之前分配所属的Chunk，chunk、handle和normCapacity这三个数据指向PoolArena中的一块内存
            ByteBuffer nioBuffer;
            long handle = -1; // ByteBuf在Chunk中的分配信息
            int normCapacity;

            Entry(Handle<Entry<?>> recyclerHandle) {
                this.recyclerHandle = recyclerHandle;
            }

            void recycle() {
                chunk = null;
                nioBuffer = null;
                handle = -1;
                recyclerHandle.recycle(this);
            }
        }

        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, ByteBuffer nioBuffer, long handle, int normCapacity) {
            Entry entry = RECYCLER.get();
            entry.chunk = chunk;
            entry.nioBuffer = nioBuffer;
            entry.handle = handle;
            entry.normCapacity = normCapacity;
            return entry;
        }

        @SuppressWarnings("rawtypes")
        private static final ObjectPool<Entry> RECYCLER = ObjectPool.newPool(new ObjectCreator<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            public Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        });
    }
}
