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
abstract class SizeClasses implements SizeClassesMetric { // 内存对齐类，为Netty内存池中的内存块提供大小对齐，索引计算等服务方法。

    static final int LOG2_QUANTUM = 4;

    private static final int LOG2_SIZE_CLASS_GROUP = 2; // 每组的行数的log2值，即每组由4行
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
        this.pageSize = pageSize; // 这个是8192，8KB
        this.pageShifts = pageShifts; // 用于辅助计算的（pageSize的最高位需要左移的次数） 13  ===> 2 ^ 13 = 8192
        this.chunkSize = chunkSize; // 16M  chunk 大小
        this.directMemoryCacheAlignment = directMemoryCacheAlignment; // 对齐基准，主要是对于Huge这种直接分配的类型的数据将其对其为directMemoryCacheAlignment的倍数
        // 计算出group的数量，24 + 1 - 4 = 19个group
        int group = log2(chunkSize) + 1 - LOG2_QUANTUM; // 19

        // generate size classes，生成sizeClasses，对于这个数组的7个位置的每一位表示的含义为
        // [index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
        sizeClasses = new short[group << LOG2_SIZE_CLASS_GROUP][7]; // sizeClasses是一个表格（二维数组），7列，19*4=76行，new short[76][7]
        nSizes = sizeClasses();
        // 生成idx对size的表格,这里的sizeIdx2sizeTab存储的就是利用(1 << log2Group) + (nDelta << log2Delta)计算的size
        //generate lookup table
        sizeIdx2sizeTab = new int[nSizes];
        pageIdx2sizeTab = new int[nPSizes]; //pageIdx2sizeTab则存储的是isMultiPageSize是1的对应的size
        idx2SizeTab(sizeIdx2sizeTab, pageIdx2sizeTab);
        // 生成size对idx的表格，这里存储的是lookupMaxSize以下的，并且其size的单位是1<<LOG2_QUANTUM
        size2idxTab = new int[lookupMaxSize >> LOG2_QUANTUM];
        size2idxTab(size2idxTab);
    }

    protected final int pageSize; // 8kb
    protected final int pageShifts; // 通常是13，因为2^13 = 8kb
    protected final int chunkSize;
    protected final int directMemoryCacheAlignment;

    final int nSizes; // 76行
    int nSubpages; // subPage的数量
    int nPSizes; // pageSize的数量

    int smallMaxSizeIdx;

    private int lookupMaxSize; // 通常是27

    private final short[][] sizeClasses; // sizeClasses是一个表格（二维数组），7列

    private final int[] pageIdx2sizeTab; // 所有是8kb倍数的索引id(行号)

    // lookup table for sizeIdx <= smallMaxSizeIdx
    private final int[] sizeIdx2sizeTab; // size索引 -> size 的数组，76行，依次是每行的size

    // lookup table used for size <= lookupMaxclass
    // spacing is 1 << LOG2_QUANTUM, so the size of array is lookupMaxclass >> LOG2_QUANTUM
    private final int[] size2idxTab;

    private int sizeClasses() {
        int normalMaxSize = -1;

        int index = 0; // 内存块size 表格的索引下标
        int size = 0;

        int log2Group = LOG2_QUANTUM; // log2Group = LOG2_QUANTUM = 4
        int log2Delta = LOG2_QUANTUM; // log2Delta = LOG2_QUANTUM = 4
        int ndeltaLimit = 1 << LOG2_SIZE_CLASS_GROUP; // ndeltaLimit = 1 << 2 = 4，表示的是每一组的数量，内存块size以4个为一组进行分组

        //First small group, nDelta start at 0.
        //first size class is 1 << LOG2_QUANTUM
        int nDelta = 0; // 初始化第0组
        while (nDelta < ndeltaLimit) { // nDelta从0开始，第0组比较特殊，4行nDelta依次是 0,1,2,3
            size = sizeClass(index++, log2Group, log2Delta, nDelta++); // sizeClass方法计算sizeClasses每一行内容
        }
        log2Group += LOG2_SIZE_CLASS_GROUP; // 从第1组开始，log2Group增加LOG2_SIZE_CLASS_GROUP，即log2Group = 6，而log2Delta = 4 暂时不变
        // 所以 log2Group += LOG2_SIZE_CLASS_GROUP，等价于 log2Group = log2Delta + LOG2_SIZE_CLASS_GROUP
        //All remaining groups, nDelta start at 1.
        while (size < chunkSize) { // 初始化后面的size
            nDelta = 1; // nDelta从1开始
            // 生成一组内存块，nDeleta从1到1 << LOG2_SIZE_CLASS_GROUP，即1到4的这一组的内存块
            while (nDelta <= ndeltaLimit && size < chunkSize) {
                size = sizeClass(index++, log2Group, log2Delta, nDelta++);
                normalMaxSize = size;
            }
            // 每组生成后log2Group+1，log2Delta+1
            log2Group++;
            log2Delta++;
        } // 每组内相邻行大小增量为(1 << log2Delta)，相邻组之间(1 << log2Delta)翻倍

        //chunkSize must be normalMaxSize
        assert chunkSize == normalMaxSize; // 进行了断言,表示chunkSize必须是sizeClass的最后一个

        //return number of size index
        return index;
    }

    //calculate size class
    private int sizeClass(int index, int log2Group, int log2Delta, int nDelta) {
        short isMultiPageSize;
        if (log2Delta >= pageShifts) { // log2Delta大于pageShifts(13)则表示size的计算的最小单位都大于pageSize(8kb)，即每一行比上一行的增量都>=8kb
            isMultiPageSize = yes; // 表示size是否为page的倍数
        } else { // 当每一行比上一行的增量<8kb 时
            int pageSize = 1 << pageShifts; // 通常是8kb
            int size = (1 << log2Group) + (1 << log2Delta) * nDelta; // 计算公式
            // 因为上文中可以得到，log2Group = log2Delta + LOG2_SIZE_CLASS_GROUP(2)
            // 所以，int size = (1 << (log2Delta + LOG2_SIZE_CLASS_GROUP)) + (1 << log2Delta) * nDelta =  (2 ^ LOG2_SIZE_CLASS_GROUP + nDelta) * (1 << log2Delta)
            isMultiPageSize = size == size / pageSize * pageSize? yes : no; // 判断是否是 8kb 的整数倍
        }

        int log2Ndelta = nDelta == 0? 0 : log2(nDelta); // nDelta=0时，log2Ndelta=0，否则 log2Ndelta=log2nDelta，得到1/2

        byte remove = 1 << log2Ndelta < nDelta? yes : no; // 第一组，nDelta=[0,3]，log2Ndelta=[0,1]，此时 remove=yes，其他组都是 true
        // 2^(log2Delta+log2Ndelta)即为(1 << log2Delta) * nDelta,故log2Delta + log2Ndelta == log2Group是size=2^(log2Group + 1)
        int log2Size = log2Delta + log2Ndelta == log2Group? log2Group + 1 : log2Group; // 第0组是(log2Group + 1 =5)，否则都是 log2Group
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
        // 计算是pageSize的数量
        if (sz[PAGESIZE_IDX] == yes) {
            nPSizes++;
        }
        if (sz[SUBPAGE_IDX] == yes) { // 计算是subPage的数量
            nSubpages++;
            smallMaxSizeIdx = index; // 这个值是用来判断分配是从subpage分配还是直接从poolchunk中进行分配
        }
        if (sz[LOG2_DELTA_LOOKUP_IDX] != no) { // 计算lookupMaxSize
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
            // 以2B为单位,每隔2B生成一个size->idx的对应关系
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
        // 组号+组内偏移
        return groupSize + modSize;
    }
    // 根据页索引(8kb倍数的size)获取对应的size
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
            //size-1 / MIN_TINY。  从sizeClasses方法可以看到，sizeClasses表格中每个size都是(2^LOG2_QUANTUM = 16) 的倍数。
            return size2idxTab[size - 1 >> LOG2_QUANTUM];
        }
        // 对申请内存大小进行log2的向上取整，就是每组最后一个内存块size。-1是为了避免申请内存大小刚好等于2的指数次幂时被翻倍。
        int x = log2((size << 1) - 1); // 将log2Group = log2Delta + LOG2_SIZE_CLASS_GROUP，nDelta=2^LOG2_SIZE_CLASS_GROUP代入计算公式，可得lastSize = 1 << (log2Group + 1)，即x = log2Group + 1
        int shift = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1 //  shift，当前在第几组，从0开始（sizeClasses表格中0~3行为第0组，4~7行为第1组，以此类推，不是log2Group）
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM); // x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1，即log2Group < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM，满足该条件的是第0组的size，这时shift固定是0。
        //从sizeClasses方法可以看到，除了第0组，都满足shift = log2Group - LOG2_QUANTUM - (LOG2_SIZE_CLASS_GROUP - 1)。
        int group = shift << LOG2_SIZE_CLASS_GROUP; // group = shift << LOG2_SIZE_CLASS_GROUP，就是该组第一个内存块size的索引（每4个一组，所以 shift / 4 定位到每一组的第一行）
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
    // 对页进行规范化计算，向下取整取最近的pages容量大小的索引
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
    // 对 size 规范化，内存对其时使用
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
