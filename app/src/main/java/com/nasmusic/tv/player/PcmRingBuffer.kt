package com.nasmusic.tv.player

/**
 * PCM 环形缓冲 —— 单写（播放线程）/ 单读（FFT 线程）。
 *
 * 用于 P6 降级通道：AudioSink 的 PCM 数据由播放线程写入，
 * [PcmSpectrumTap] 的后台线程读取。所有公开方法都用实例锁串行化 ——
 * 单次读/写都是 1024 点量级的连续拷贝（微秒级），锁竞争可忽略，
 * 换来的是「不用自己写无锁环形缓冲」的可靠性。
 *
 * **容量必须为 2 的幂**：用位与代替取模。
 */
class PcmRingBuffer(val capacity: Int) {

    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "PcmRingBuffer 容量必须为 2 的幂，当前 $capacity"
        }
    }

    private val mask = capacity - 1
    private val buf = FloatArray(capacity)

    /** 下一个写入位置 */
    private var head = 0

    /** 已累积的有效样本数（上限 = capacity） */
    private var filled = 0

    /** 写入计数——供读取方判断「是否有新数据」，避免暂停时反复分析同一段 */
    private var version = 0L

    /** 批量写入。只拷贝前 [n] 个样本（超过容量时保留最新的一段）。 */
    @Synchronized
    fun write(src: FloatArray, n: Int) {
        val len = minOf(n, src.size, capacity)
        if (len <= 0) return
        // 超过容量的部分只保留尾段（最新数据）
        val from = n - len
        var pos = head
        for (i in from until from + len) {
            buf[pos] = src[i]
            pos = (pos + 1) and mask
        }
        head = pos
        filled = minOf(capacity, filled + len)
        version++
    }

    /**
     * 取最近 `dst.size` 个样本（按时间顺序写入 [dst]）。
     *
     * @return true = 数据充足；false = 尚未填满，[dst] 已置 0 且调用方应跳过本帧
     */
    @Synchronized
    fun readLatest(dst: FloatArray): Boolean {
        val need = dst.size
        if (need <= 0) return false
        if (need > capacity) {
            dst.fill(0f)
            return false
        }
        if (filled < need) {
            dst.fill(0f)
            return false
        }
        var pos = (head - need) and mask
        for (i in 0 until need) {
            dst[i] = buf[pos]
            pos = (pos + 1) and mask
        }
        return true
    }

    /** 写入计数（单调递增）。用于跳过无新数据的帧。 */
    @Synchronized
    fun version(): Long = version

    @Synchronized
    fun available(): Int = filled

    @Synchronized
    fun clear() {
        head = 0
        filled = 0
        version = 0L
        buf.fill(0f)
    }
}
