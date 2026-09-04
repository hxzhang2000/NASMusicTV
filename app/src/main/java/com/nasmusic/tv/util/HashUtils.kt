package com.nasmusic.tv.util

/**
 * 稳定 64-bit 路径哈希，用于本地歌曲（USB / 无 MediaStore ID 的文件）的主键生成。
 *
 * 背景：原实现用 [String.hashCode]（32-bit）拓宽为 Long 作为主键。32-bit 哈希约在
 * 7.7 万文件时碰撞概率≈50%，在 `@Insert(REPLACE)` 下会静默覆盖、丢歌（复审 P2 项）。
 *
 * 这里改用 FNV-1a 64-bit：分布均匀，碰撞概率降至 (n/2^64)^2 量级（对百万级文件仍可忽略），
 * 且对同一 path 跨进程 / 跨启动 / 跨设备完全确定（不使用随机盐），保证同一文件始终得到同一 id。
 */
object HashUtils {

    // FNV-1a 64-bit 标准常量（offset basis = 0xcbf29ce484222325，写作负 hex 以避免字面量超正范围）
    private const val FNV_OFFSET_BASIS: Long = -0x340d631b7bdddcdbL
    private const val FNV_PRIME: Long = 0x100000001b3L

    /**
     * 计算 path 的 64-bit FNV-1a 哈希。
     *
     * @param path 文件绝对路径（UTF-8），即本地歌曲表的唯一业务键
     * @return 稳定且低碰撞的 64-bit 哈希值，可直接用作 Long 主键
     */
    fun stablePathHash64(path: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (b in path.encodeToByteArray()) {
            hash = hash xor (b.toLong() and 0xFFL)
            hash *= FNV_PRIME
        }
        return hash
    }
}
