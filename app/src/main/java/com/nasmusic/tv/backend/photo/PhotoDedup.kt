package com.nasmusic.tv.backend.photo

/**
 * 跨来源**指纹去重**（§6.8）
 *
 * ## 为什么必须做（虽然已经排除了内部存储）
 *
 * | # | 原因 |
 * |---|---|
 * | 1 | **外接 SD 卡通常也会被 MediaStore 索引** —— 手机上相册本来就能看到 SD 卡里的照片 ⇒ 用户选了 SD 卡目录，同一张照片出现两次 |
 * | 2 | **USB OTG 一般不被索引，但这只是「建议」** —— AOSP 只是建议厂商不要把瞬态卷编入索引，厂商可自行决定 ⇒ 不能假设「外接一定不重叠」 |
 *
 * ⇒ 去重从「大概率触发」降为「兜底」，但**代码不能省**。
 *
 * ## 指纹选型
 *
 * 指纹 = `(size, lastModified秒, lowercase(displayName))`。
 *
 * ⛔ **不要用路径去重**：MediaStore 在 API 29+ 拿不到可靠的 `DATA` 路径；
 * `/sdcard` 与 `/storage/emulated/0` 是同一位置的不同写法；SAF 的 tree URI 也不是路径
 * ⇒ 指纹比路径稳。
 *
 * ## ⛔ 时间单位契约（本类**不做**归一化，这是刻意的）
 *
 * `MediaStore.DATE_MODIFIED` 是**秒**，`DocumentFile.lastModified()` / `File.lastModified()`
 * 是**毫秒**。归一化**必须在各 [PhotoSource] 实现内**完成（[PhotoRef.lastModified] 对外
 * 统一为秒），**不能留到比较时做** —— 否则本类无从判断某条记录的单位，
 * 指纹永不匹配、**去重静默失效**。
 *
 * ⇒ 本类假设入参**已是秒**。单测 G1 用「故意喂毫秒 ⇒ 断言去重失效」来证明这一依赖真实存在
 * （见 `PhotoDedupTest` 的负向自证）。
 *
 * ## ⛔ JELLYFIN 项不参与去重
 *
 * 服务端 `itemId` 与本机文件没有对应关系（NAS 照片通常不来自本机），拿本机指纹去比毫无意义，
 * 反而会误杀。⇒ JELLYFIN 项**总是保留**，且**不写入指纹集合**（否则会吃掉本机同指纹项）。
 *
 * 本类**纯逻辑、无 Android 依赖**，是单测门禁对象（G1）。
 */
class PhotoDedup {

    /** 指纹：字节数 + 修改时间（**秒**）+ 小写文件名 */
    private data class Key(val size: Long, val sec: Long, val name: String)

    /**
     * 保留**首次出现**，丢弃后续同指纹项。
     *
     * @param refs 顺序敏感 —— 「首次出现」由入参顺序决定，调用方应传入稳定的合并顺序
     *   （[PhotoSourceAggregator] 按 [PhotoSourceKind] 枚举序合并）。
     */
    fun distinct(refs: List<PhotoRef>): List<PhotoRef> {
        if (refs.size < 2) return refs
        val seen = HashSet<Key>(refs.size * 2)
        val out = ArrayList<PhotoRef>(refs.size)
        for (ref in refs) {
            if (ref.source == PhotoSourceKind.JELLYFIN) {
                // 不查、不写 —— 完全不参与去重
                out.add(ref)
                continue
            }
            val key = Key(ref.size, ref.lastModified, ref.displayName.lowercase())
            if (seen.add(key)) out.add(ref)
        }
        return out
    }
}
