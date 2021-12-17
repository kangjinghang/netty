/*
 * Copyright 2020 The Netty Project
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

import static io.netty.buffer.PoolThreadCache.*;

/**
 * SizeClasses requires {@code pageShifts} to be defined prior to inclusion,
 * and it in turn defines:
 * <p>
 *   LOG2_SIZE_CLASS_GROUP: Log of size class count for each size doubling.
 *   LOG2_MAX_LOOKUP_SIZE: Log of max size class in the lookup table.
 *   sizeClasses: Complete table of [index, log2Group, log2Delta, nDelta, isMultiPageSize,
 *                 isSubPage, log2DeltaLookup] tuples.
 *     index: Size class index.
 *     log2Group: Log of group base size (no deltas added).
 *     log2Delta: Log of delta to previous size class.
 *     nDelta: Delta multiplier.
 *     isMultiPageSize: 'yes' if a multiple of the page size, 'no' otherwise.
 *     isSubPage: 'yes' if a subpage size class, 'no' otherwise.
 *     log2DeltaLookup: Same as log2Delta if a lookup table size class, 'no'
 *                      otherwise.
 * <p>
 *   nSubpages: Number of subpages size classes.
 *   nSizes: Number of size classes.
 *   nPSizes: Number of size classes that are multiples of pageSize.
 *
 *   smallMaxSizeIdx: Maximum small size class index.
 *
 *   lookupMaxclass: Maximum size class included in lookup table.
 *   log2NormalMinClass: Log of minimum normal size class.
 * <p>
 *   The first size class and spacing are 1 << LOG2_QUANTUM.
 *   Each group has 1 << LOG2_SIZE_CLASS_GROUP of size classes.
 *
 *   size = 1 << log2Group + nDelta * (1 << log2Delta)
 *
 *   The first size class has an unusual encoding, because the size has to be
 *   split between group and delta*nDelta.
 *
 *   If pageShift = 13, sizeClasses looks like this:
 *
 *   (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
 * <p>
 *   ( 0,     4,        4,         0,       no,             yes,        4)
 *   ( 1,     4,        4,         1,       no,             yes,        4)
 *   ( 2,     4,        4,         2,       no,             yes,        4)
 *   ( 3,     4,        4,         3,       no,             yes,        4)
 * <p>
 *   ( 4,     6,        4,         1,       no,             yes,        4)
 *   ( 5,     6,        4,         2,       no,             yes,        4)
 *   ( 6,     6,        4,         3,       no,             yes,        4)
 *   ( 7,     6,        4,         4,       no,             yes,        4)
 * <p>
 *   ( 8,     7,        5,         1,       no,             yes,        5)
 *   ( 9,     7,        5,         2,       no,             yes,        5)
 *   ( 10,    7,        5,         3,       no,             yes,        5)
 *   ( 11,    7,        5,         4,       no,             yes,        5)
 *   ...
 *   ...
 *   ( 72,    23,       21,        1,       yes,            no,        no)
 *   ( 73,    23,       21,        2,       yes,            no,        no)
 *   ( 74,    23,       21,        3,       yes,            no,        no)
 *   ( 75,    23,       21,        4,       yes,            no,        no)
 * <p>
 *   ( 76,    24,       22,        1,       yes,            no,        no)
 */
abstract class SizeClasses implements SizeClassesMetric {

    static final int LOG2_QUANTUM = 4;

    private static final int LOG2_SIZE_CLASS_GROUP = 2;
    private static final int LOG2_MAX_LOOKUP_SIZE = 12;

    private static final int INDEX_IDX = 0;
    private static final int LOG2GROUP_IDX = 1;
    private static final int LOG2DELTA_IDX = 2;
    private static final int NDELTA_IDX = 3;
    private static final int PAGESIZE_IDX = 4;
    private static final int SUBPAGE_IDX = 5;
    private static final int LOG2_DELTA_LOOKUP_IDX = 6;

    private static final byte no = 0, yes = 1;

    protected SizeClasses(int pageSize, int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
        this.pageSize = pageSize; // 这个是8192
        this.pageShifts = pageShifts; // 用于辅助计算的 13  ===> 2 ^ 13 = 8192
        this.chunkSize = chunkSize; // 16M  chunk 大小
        this.directMemoryCacheAlignment = directMemoryCacheAlignment; // 对齐基准

        int group = log2(chunkSize) + 1 - LOG2_QUANTUM;

        //generate size classes
        //[index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
        sizeClasses = new short[group << LOG2_SIZE_CLASS_GROUP][7];
        nSizes = sizeClasses();

        //generate lookup table
        sizeIdx2sizeTab = new int[nSizes];
        pageIdx2sizeTab = new int[nPSizes];
        idx2SizeTab(sizeIdx2sizeTab, pageIdx2sizeTab);

        size2idxTab = new int[lookupMaxSize >> LOG2_QUANTUM];
        size2idxTab(size2idxTab);
    }

    protected final int pageSize;
    protected final int pageShifts;
    protected final int chunkSize;
    protected final int directMemoryCacheAlignment;

    final int nSizes;
    int nSubpages;
    int nPSizes;

    int smallMaxSizeIdx;

    private int lookupMaxSize;

    private final short[][] sizeClasses;

    private final int[] pageIdx2sizeTab;

    // lookup table for sizeIdx <= smallMaxSizeIdx
    private final int[] sizeIdx2sizeTab;

    // lookup table used for size <= lookupMaxclass
    // spacing is 1 << LOG2_QUANTUM, so the size of array is lookupMaxclass >> LOG2_QUANTUM
    private final int[] size2idxTab;

    private int sizeClasses() {
        int normalMaxSize = -1;

        int index = 0;
        int size = 0;

        int log2Group = LOG2_QUANTUM; // log2Group = LOG2_QUANTUM = 4
        int log2Delta = LOG2_QUANTUM; // log2Delta = LOG2_QUANTUM = 4
        int ndeltaLimit = 1 << LOG2_SIZE_CLASS_GROUP; // ndeltaLimit = 1 << 2 = 4，内存块size以4个为一组进行分组

        //First small group, nDelta start at 0.
        //first size class is 1 << LOG2_QUANTUM
        int nDelta = 0; // 初始化第0组
        while (nDelta < ndeltaLimit) { // nDelta从0开始
            size = sizeClass(index++, log2Group, log2Delta, nDelta++); // sizeClass方法计算sizeClasses每一行内容
        }
        log2Group += LOG2_SIZE_CLASS_GROUP; // 第0组后log2Group增加LOG2_SIZE_CLASS_GROUP = 6，而log2Delta = 4 不变
        // 所以 log2Group += LOG2_SIZE_CLASS_GROUP，等价于 log2Group = log2Delta + LOG2_SIZE_CLASS_GROUP
        //All remaining groups, nDelta start at 1.
        while (size < chunkSize) { // 初始化后面的size
            nDelta = 1; // nDelta从1开始

            while (nDelta <= ndeltaLimit && size < chunkSize) {
                size = sizeClass(index++, log2Group, log2Delta, nDelta++);
                normalMaxSize = size;
            }
            // 每组log2Group+1，log2Delta+1
            log2Group++;
            log2Delta++;
        }

        //chunkSize must be normalMaxSize
        assert chunkSize == normalMaxSize;

        //return number of size index
        return index;
    }

    //calculate size class
    private int sizeClass(int index, int log2Group, int log2Delta, int nDelta) {
        short isMultiPageSize;
        if (log2Delta >= pageShifts) {
            isMultiPageSize = yes;
        } else {
            int pageSize = 1 << pageShifts;
            int size = (1 << log2Group) + (1 << log2Delta) * nDelta; // 计算公式
            // 因为上文中可以得到，log2Group = log2Delta + LOG2_SIZE_CLASS_GROUP
            // 所以，int size = (1 << (log2Delta + LOG2_SIZE_CLASS_GROUP)) + (1 << log2Delta) * nDelta =  (2 ^ LOG2_SIZE_CLASS_GROUP + nDelta) * (1 << log2Delta)
            isMultiPageSize = size == size / pageSize * pageSize? yes : no;
        }

        int log2Ndelta = nDelta == 0? 0 : log2(nDelta);

        byte remove = 1 << log2Ndelta < nDelta? yes : no;

        int log2Size = log2Delta + log2Ndelta == log2Group? log2Group + 1 : log2Group;
        if (log2Size == log2Group) {
            remove = yes;
        }

        short isSubpage = log2Size < pageShifts + LOG2_SIZE_CLASS_GROUP? yes : no;

        int log2DeltaLookup = log2Size < LOG2_MAX_LOOKUP_SIZE ||
                              log2Size == LOG2_MAX_LOOKUP_SIZE && remove == no
                ? log2Delta : no;

        short[] sz = {
                (short) index, (short) log2Group, (short) log2Delta,
                (short) nDelta, isMultiPageSize, isSubpage, (short) log2DeltaLookup
        };

        sizeClasses[index] = sz;
        int size = (1 << log2Group) + (nDelta << log2Delta);

        if (sz[PAGESIZE_IDX] == yes) {
            nPSizes++;
        }
        if (sz[SUBPAGE_IDX] == yes) {
            nSubpages++;
            smallMaxSizeIdx = index;
        }
        if (sz[LOG2_DELTA_LOOKUP_IDX] != no) {
            lookupMaxSize = size;
        }
        return size;
    }

    private void idx2SizeTab(int[] sizeIdx2sizeTab, int[] pageIdx2sizeTab) {
        int pageIdx = 0;

        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            int log2Group = sizeClass[LOG2GROUP_IDX];
            int log2Delta = sizeClass[LOG2DELTA_IDX];
            int nDelta = sizeClass[NDELTA_IDX];

            int size = (1 << log2Group) + (nDelta << log2Delta);
            sizeIdx2sizeTab[i] = size;

            if (sizeClass[PAGESIZE_IDX] == yes) {
                pageIdx2sizeTab[pageIdx++] = size;
            }
        }
    }

    private void size2idxTab(int[] size2idxTab) {
        int idx = 0;
        int size = 0;

        for (int i = 0; size <= lookupMaxSize; i++) {
            int log2Delta = sizeClasses[i][LOG2DELTA_IDX];
            int times = 1 << log2Delta - LOG2_QUANTUM;

            while (size <= lookupMaxSize && times-- > 0) {
                size2idxTab[idx++] = i;
                size = idx + 1 << LOG2_QUANTUM;
            }
        }
    }

    @Override
    public int sizeIdx2size(int sizeIdx) {
        return sizeIdx2sizeTab[sizeIdx];
    }

    @Override
    public int sizeIdx2sizeCompute(int sizeIdx) {
        int group = sizeIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = sizeIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int groupSize = group == 0? 0 :
                1 << LOG2_QUANTUM + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int lgDelta = shift + LOG2_QUANTUM - 1;
        int modSize = mod + 1 << lgDelta;

        return groupSize + modSize;
    }

    @Override
    public long pageIdx2size(int pageIdx) {
        return pageIdx2sizeTab[pageIdx];
    }

    @Override
    public long pageIdx2sizeCompute(int pageIdx) {
        int group = pageIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = pageIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        long groupSize = group == 0? 0 :
                1L << pageShifts + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int log2Delta = shift + pageShifts - 1;
        int modSize = mod + 1 << log2Delta;

        return groupSize + modSize;
    }

    @Override
    public int size2SizeIdx(int size) {
        if (size == 0) {
            return 0;
        }
        if (size > chunkSize) { // 大于chunkSize，则返回nSizes，代表申请的是Huge内存块。
            return nSizes;
        }

        if (directMemoryCacheAlignment > 0) { // directMemoryCacheAlignment默认为0， >0 表示不使用sizeClasses表格，直接将申请内存大小转换为directMemoryCacheAlignment的倍数。
            size = alignSize(size);
        }
        // SizeClasses将一部分较小的size与对应index记录在size2idxTab作为位图，这里直接查询size2idxTab，避免重复计算
        if (size <= lookupMaxSize) { // size2idxTab中保存了(size-1)/(2^LOG2_QUANTUM) --> idx的对应关系。
            //size-1 / MIN_TINY。  从sizeClasses方法可以看到，sizeClasses表格中每个size都是(2^LOG2_QUANTUM) 的倍数。
            return size2idxTab[size - 1 >> LOG2_QUANTUM];
        }
        // 对申请内存大小进行log2的向上取整，就是每组最后一个内存块size。-1是为了避免申请内存大小刚好等于2的指数次幂时被翻倍。
        int x = log2((size << 1) - 1); // 将log2Group = log2Delta + LOG2_SIZE_CLASS_GROUP，nDelta=2^LOG2_SIZE_CLASS_GROUP代入计算公式，可得lastSize = 1 << (log2Group + 1)，即x = log2Group + 1
        int shift = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1 //  shift，当前在第几组，从0开始（sizeClasses表格中0~3行为第0组，4~7行为第1组，以此类推，不是log2Group）
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM); // x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1，即log2Group < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM，满足该条件的是第0组的size，这时shift固定是0。
        //从sizeClasses方法可以看到，除了第0组，都满足shift = log2Group - LOG2_QUANTUM - (LOG2_SIZE_CLASS_GROUP - 1)。
        int group = shift << LOG2_SIZE_CLASS_GROUP; // group = shift << LOG2_SIZE_CLASS_GROUP，就是该组第一个内存块size的索引
        // 计算log2Delta。第0组固定是LOG2_QUANTUM。除了第0组，将nDelta = 2^LOG2_SIZE_CLASS_GROUP代入计算公式，lastSize = ( 2^LOG2_SIZE_CLASS_GROUP + 2^LOG2_SIZE_CLASS_GROUP ) * (1 << log2Delta)
        // lastSize = (1 << log2Delta) << LOG2_SIZE_CLASS_GROUP << 1
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
        // 前面已经定位到第几组了，下面要找到申请内存大小应分配在该组第几位，这里要找到比申请内存大的最小size。申请内存大小可以理解为上一个size加上一个不大于(1 << log2Delta)的值，即
        //(nDelta - 1 + 2^LOG2_SIZE_CLASS_GROUP) * (1 << log2Delta) + n， 备注：0 < n <= (1 << log2Delta)。注意，nDelta - 1就是mod
        int deltaInverseMask = -1 << log2Delta;
        int mod = (size - 1 & deltaInverseMask) >> log2Delta & // & deltaInverseMask，将申请内存大小最后log2Delta个bit位设置为0，可以理解为减去n。>> log2Delta，右移log2Delta个bit位，就是除以(1 << log2Delta)，结果就是(nDelta - 1 + 2 ^ LOG2_SIZE_CLASS_GROUP)
                  (1 << LOG2_SIZE_CLASS_GROUP) - 1; // & (1 << LOG2_SIZE_CLASS_GROUP) - 1， 取最后的LOG2_SIZE_CLASS_GROUP个bit位的值，结果就是mod
        // size - 1，是为了申请内存等于内存块size时避免分配到下一个内存块size中，即n == (1 << log2Delta)的场景。
        return group + mod; // 第0组由于log2Group等于log2Delta，代入计算公式如下：1 << log2Delta + (nDelta - 1) * (1 << log2Delta) + n， 备注：0 < n <= (1 << log2Delta)
        // nDelta * (1 << log2Delta) + n，所以第0组nDelta从0开始，mod = nDelta
    }

    @Override
    public int pages2pageIdx(int pages) {
        return pages2pageIdxCompute(pages, false);
    }

    @Override
    public int pages2pageIdxFloor(int pages) {
        return pages2pageIdxCompute(pages, true);
    }

    private int pages2pageIdxCompute(int pages, boolean floor) {
        int pageSize = pages << pageShifts;
        if (pageSize > chunkSize) {
            return nPSizes;
        }

        int x = log2((pageSize << 1) - 1);

        int shift = x < LOG2_SIZE_CLASS_GROUP + pageShifts
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + pageShifts);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + pageShifts + 1?
                pageShifts : x - LOG2_SIZE_CLASS_GROUP - 1;

        int deltaInverseMask = -1 << log2Delta;
        int mod = (pageSize - 1 & deltaInverseMask) >> log2Delta &
                  (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int pageIdx = group + mod;

        if (floor && pageIdx2sizeTab[pageIdx] > pages << pageShifts) {
            pageIdx--;
        }

        return pageIdx;
    }

    // Round size up to the nearest multiple of alignment.
    private int alignSize(int size) {
        int delta = size & directMemoryCacheAlignment - 1; // 计算余数，directMemoryCacheAlignment总是为2的幂次方，delta = size % directMemoryCacheAlignment
        return delta == 0? size : size + directMemoryCacheAlignment - delta;
    }

    @Override
    public int normalizeSize(int size) {
        if (size == 0) {
            return sizeIdx2sizeTab[0];
        }
        if (directMemoryCacheAlignment > 0) {
            size = alignSize(size);
        }

        if (size <= lookupMaxSize) {
            int ret = sizeIdx2sizeTab[size2idxTab[size - 1 >> LOG2_QUANTUM]];
            assert ret == normalizeSizeCompute(size);
            return ret;
        }
        return normalizeSizeCompute(size);
    }

    private static int normalizeSizeCompute(int size) {
        int x = log2((size << 1) - 1);
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
        int delta = 1 << log2Delta;
        int delta_mask = delta - 1;
        return size + delta_mask & ~delta_mask;
    }
}
