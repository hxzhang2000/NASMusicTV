# 音乐多码率播放与下载开发方案

- **状态**：✅ 决策已确认 + 源码核验完成，**可进入实施**（Q1–Q8 全部定稿，见 §8；v1.3 修正四处硬伤，见顶部修订说明）
- **日期**：2026-09-18（同日完成决策确认、v1.2 需求修订、v1.3 可开发性审阅）
- **参考实现**：`D-Music-main`（GD 音乐台聚合 API 客户端）
- **目标模块**：`backend/network/`（播放）、`backend/download/`（下载）、`ui/`（界面）
- **前置阅读**：`docs/network-music-upgrade-plan.md`、`docs/歌曲离线下载与本地存储管理开发方案.md`

> **v1.1 定稿修订（2026-09-18）**：三项决策已确认——Q1 做两级音质模型、Q2 走零重建迁移、Q3 非无损档追加档位后缀。同步修订 §2.3、§4.2、§4.3、§6、§7、§8、§9、§10.1。**其中 Q2 有一处实现修正**：「新增 `(songId, quality)` 唯一索引」无法解除主键自身的唯一约束，实际落地为「`songKey` 追加 `:q<quality>` 后缀 + 新增 `songId` 普通索引」，仍满足零重建零回填目标，详见 §4.2.3 与 §8.1。

> **v1.3 可开发性审阅修订（2026-09-18）**：对照源码逐行核验后修正四处硬伤——① **`downloadKey` 真实格式是下划线**（`ntwk_${source}_${id}`，`DownloadKeys.kt:21-26`），本文档此前的 `ntwk:<id>` 冒号写法全部更正（§4.2.2/§4.2.5/§4.4/§4.5/§10.1）；② 下载数据库当前 `version = 1`（`DownloadDatabase.kt:28`），迁移编号由 `MIGRATION_5_6` 更正为 `MIGRATION_1_2`（§4.2.4）；③ §3.3 接口默认实现与 §3.2 覆写返回类型矛盾（`String?` vs `ResolveResult`），拆分为两个方法（§2.5/§3.2/§3.3）；④ `QualityTiers` 伪代码改写为合法 Kotlin（`object` + 函数，`fallbackChainOf(LOSSLESS)` 补 192）。同时补齐实现接线细节：StreamUrlResolver 构造 lambda 变更（`NasMusicApp.kt:339-343`）、下载循环中 key 的切换时机（§4.4.2）、解析失败落 FAILED 行的现状差异（§4.4.3）、已下载本地文件优先与切档的交互（§3.6）、`quality` 与既有 `bitrate` 字段的边界（§4.2.1）。

---

## 0. 摘要与一个必须先说清的结论

### 0.1 摘要

本方案让网络音乐（Meting-API 源）支持 **5 个音质档位**（自动 / 128 / 192 / 320 / 999-FLAC）的**播放**与**下载**，档位粒度为**全局默认 + 单曲覆盖两级模型**，并保证同一首歌的多档位在播放缓存、下载索引、文件名三个维度上**互不冲突**。

**v1.2 交互与降级语义（已定稿）**：播放与下载**一律静默降级**到可用档位——播放时给出一次性提示，下载时把实际档位写进文件名（`03 - 南方姑娘 (320).mp3`）且不报错、状态仍记为成功；单曲下载点击时**先探测可用档位**，多档则弹码率选择面板、仅一档则直接入队、零档则提示错误；自动下载不探测、不弹窗。

**v1.4 实施状态（2026-09-18，已落地）**：Phase 1-4 除 §5.2.2 外全部实施完毕，
单测 + lint + release 编译均通过，版本号 `2.34.4 → 2.35.0`。
**§5.2.2 批量下载档位已取消**（需求方决定）——本项目 UI 无批量下载入口，
该节属新功能而非补全断点。剩余唯一待办是 §10.3 真机回归（需实机执行）。

### 0.2 关键修正：这不是"从零支持"，而是"补全断点"

排查源码后发现，NASMusicTV **已经落地了一个 F2-6 音质档位骨架**，但它是**半成品**：设置页能选、请求里能带 `br`，但**播放缓存不带码率、下载链路完全无码率概念**。因此本方案的工作量集中在"补全"而非"新建"。

**已存在（勿重复实现）：**

| 能力 | 位置 | 说明 |
|---|---|---|
| 4 个档位常量 | `data/prefs/AppPreferences.kt:74-77` | `QUALITY_TIER_AUTO=0` / `LOSSLESS=999` / `HIGH=320` / `STANDARD=128` |
| 持久化 + 响应式 | `AppPreferences.kt:892, 906-919` | `settings_quality_tier` key、`qualityTier: Flow<Int>`、`setQualityTier()`、`getQualityTierSync()` |
| 播放层转发 | `data/prefs/PlayerPrefs.kt:20-21` | `qualityTier` / `setQualityTier` |
| DI 注入 | `NasMusicApp.kt:283` | `qualityTierProvider = { appPreferences.getQualityTierSync() }` |
| Meting 请求带 `br` | `backend/network/MetingApiService.kt:48-51, 230-237` | 构造参数 + URL 拼接 `&br=$br` |
| br 降级链 | `MetingApiService.kt:232` | `listOf(tier, 320, 128).distinct()`，AUTO 时 `listOf(null)` 不传 |
| 设置页档位按钮 | `ui/screens/settings/PlayerSettingsSection.kt:120-131` | 四个 `SettingActionButton` |
| Settings 分支接线 | `ui/components/branches/SettingsBranch.kt:50, 107-108` | `collectAsState` + `onChangeQualityTier` |

**缺失（本方案要补的 6 个断点）：**

| # | 断点 | 证据 | 后果 |
|---|---|---|---|
| G1 | 播放链接缓存 key 不含码率 | `NetworkMusicManager.kt:54` `playUrlCache: ConcurrentHashMap<String, CachedPlayUrl>`，:106 签名 `resolvePlayUrl(song, forceRefresh)` | 切档位后 5 分钟内（`TTL=5min`，:29）仍命中旧档位直链，**音质切换静默失效** |
| G2 | 下载入口无码率参数 | `SongDownloadManager.kt:117` `enqueue(song: Song, auto: Boolean)` | 网络歌曲下载永远只能用端点默认码率 |
| G3 | 下载索引无码率维度 | `DownloadSongEntity.kt:19-56` 无 quality 字段（仅 `containerExt: String` @ :46）；索引见 :22-24 | 同曲多档下载会互相覆盖 / 无法区分 |
| G4 | 文件名扩展名靠 URL 猜 | `DownloadPathBuilder.kt:97-108` `extOf(url, song)` 取 URL 后缀 | 无损直链常无 `.flac` 后缀，会被误存成 `.mp3` 容器 |
| G5 | 档位变化不失效缓存 | `NetworkMusicManager` 无档位观察入口，`playUrlCache` 无清除调用点 | 与 G1 叠加，档位切换后必须重启 App 才生效 |
| G6 | 档位粒度只有全局 | 档位只存在于 `AppPreferences` 全局单值 | 无法满足"这首歌用无损、其余用高品"的合理需求（详见 §2.3） |
| G7 | **已下载歌曲强制播本地，切档失效** | `PlayerViewModel.kt:228-230, 301-307`：`playableLocalUri(song) ?: resolvePlayUrl(...)` | 已下载的歌曲**永远播本地文件**，用户切到无损/128 无任何效果（详见 §3.6） |

> 结论：**G1/G5/G7 是必须修的缺陷**（功能正确性），**G2/G3/G4 是必做的能力**，**G6 是本方案的增量设计**。其中 G7 是本轮（v1.3）新识别——它不在最初的六项排查里，但会直接让"切档位"对已下载歌曲完全失效，且修改它会影响既有播放行为（风险最高，见 §9）。

---

## 1. 参考实现：D-Music 的多码率机制

D-Music 是**单音源**（GD 音乐台聚合 API）架构，比 NASMusicTV 的**多源路由**架构简单，因此它的设计是"干净的单源样本"，但不能直接照搬。

### 1.1 音源契约

默认端点与四组接口（`data/MusicApi.kt:22-27`）：

```
baseUrl = https://music-api.gdstudio.xyz/api.php

搜索   ?types=search&source=netease&name=keyword&count=30&pages=1
播放   ?types=url&id=xxx&source=netease&br=320
歌词   ?types=lyric&id=xxx&source=netease
封面   ?types=pic&id=xxx&source=netease&size=300
```

`urlOf()`（:121-131）负责参数拼接与 UTF-8 编码。

### 1.2 核心函数：`resolveUrl(song, br)`

`data/MusicApi.kt:72-85`：

```kotlin
suspend fun resolveUrl(song: Song, br: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val body = httpGet(
            urlOf(
                "types" to "url",
                "id" to song.id,
                "source" to song.source,
                "br" to br
            )
        ).trim()
        JSONObject(body).optString("url")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
```

要点：**`br` 是调用方传入的显式参数，不参与任何默认值推断**。失败返回 `null`，由调用方决定是否降级。

### 1.3 码率定义

`data/Song.kt:66-75`：

```kotlin
object Qualities {
    val all = listOf(
        Quality("128", "标准", "128 kbps"),
        Quality("192", "高品", "192 kbps"),
        Quality("320", "极高", "320 kbps"),
        Quality("999", "无损", "FLAC")
    )
}
data class Quality(val value: String, val label: String, val description: String)
```

**`999` 是 API 约定的无损 FLAC 标识符，不是真实比特率**——这一点必须在代码注释和 UI 文案中保持一致，否则日志/下载记录里出现 "bitrate=999 kbps" 会很荒谬。

### 1.4 下载：码率参与任务 ID 与文件名

`data/DownloadManager.kt:37-48`：

```kotlin
data class DownloadTask(
    val song: Song,
    val quality: String,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Float = 0f,
    val filePath: String? = null,
    val error: String? = null
) {
    val id: String get() = "${song.source}:${song.id}:$quality"
    val fileName: String
        get() = "${song.artistName} - ${song.displayName}.${if (quality == "999") "flac" else "mp3"}"
}
```

两条设计规则值得直接继承：

1. **任务 ID 三元组** `source:id:quality` → 同曲不同档互不冲突；
2. **扩展名由 quality 决定，而非由 URL 后缀猜测** → 这正是 NASMusicTV 的 G4 缺陷的反面教材。

并发控制：`DownloadManager.kt:70` 使用 `Semaphore(permits = 3)`，防止批量下载打满网络。

### 1.5 缓存：音质参与缓存 key

`player/PlaybackCache.kt:17` 注释明确写出设计意图，:87 给出实现：

```kotlin
/** 构造缓存 key：source:id:br（音质参与，切音质不串）。 */
fun keyOf(source: String, id: String, br: String): String = "$source:$id:$br"
```

缓存本体是 Media3 `SimpleCache` + LRU 淘汰（:39-42），上限可配置，卸载自动清理。

### 1.6 D-Music 完整调用链路

```
用户在 UI 选码率
      │
      ▼
MusicApi.resolveUrl(song, br)      ← br 作为显式参数
      │
      ▼
GD API 返回对应码率直链
      │
      ├──▶ 播放：直链 + 缓存 key "source:id:br"
      │
      └──▶ 下载：DownloadTask(song, quality)
                     ├─ id = "source:id:quality"
                     └─ fileName = "歌手 - 标题.(flac|mp3)"
```

### 1.7 与 NASMusicTV 的架构差异（决定了不能照搬）

| 维度 | D-Music | NASMusicTV |
|---|---|---|
| 音源 | 单源（GD 音乐台），`object MusicApi` | 多源路由：`NetworkMusicService` 接口 + `NetworkMusicManager` 路由层 |
| 播放解析 | `resolveUrl(song, br)` 直接返回直链 | `resolvePlayUrl(song)` 返回 302 `Location`（`MetingApiService.kt:226-259`） |
| 缓存 | Media3 `SimpleCache`，key=`source:id:br` | `ConcurrentHashMap` 内存缓存，key=`song.id`，TTL 5min |
| 下载存储 | 单文件写公共目录，无数据库 | Room `downloads.db` + `download_songs` 索引表 + 目录结构 |
| 码率档位 | 4 档（128/192/320/999） | 当前 4 个值（0/999/320/128，**缺 192**） |

**因此：D-Music 的 `br` 参数设计、`source:id:br` 缓存键、`source:id:quality` 下载 ID 三条可以原样继承；但调用入口必须适配 NASMusicTV 的多源路由，不能把 `br` 塞进 `Song` 模型。**

---

## 2. 设计决策

### 2.1 决策 D1：档位数 —— 补齐 192，保持 5 个档位值

现状 4 个值（含 AUTO），D-Music 是 4 档（不含 AUTO）。方案：**保留 AUTO，补齐 192，最终 5 个值**。

| 值 | 标签 | API 语义 | 现状 | 本方案 |
|---|---|---|---|---|
| `0` | 自动 | 不传 `br`，端点默认 | 已有 | 保留 |
| `128` | 标准 | `br=128` | 已有 | 保留 |
| `192` | 高品 | `br=192` | **缺失** | **新增** |
| `320` | 极高 | `br=320` | 已有 | 保留 |
| `999` | 无损 | `br=999` → FLAC | 已有 | 保留 |

理由：

- 192 kbps 是网易云音源的**主流默认档**，也是老电视盒子（10-15 Mbps 网络 + 弱 SoC）最省流的可用档；只有 128/320 会让用户在"极差"和"较贵"之间二选一。
- 常量集中定义在 `AppPreferences.kt:74-77`，新增一行常量成本极低；但设置页按钮列表 `PlayerSettingsSection.kt:120-131` 是**硬编码**的（不是遍历 `Qualities`），必须一并改。
- **建议把硬编码列表抽成单一真相源**（见 §5.3），避免"常量加了一个、UI 忘了加"这类漂移。

### 2.2 决策 D2：`br` 的类型与命名

沿用现状的 `Int`（`qualityTierProvider: () -> Int`），**不改成 D-Music 的 `String`**。

- 现状 `Int` 已在 6 处接线（§0.2 表），改成 `String` 是纯开销；
- Meting URL 拼接处 `MetingApiService.kt:236` `if (br != null) "&br=$br"` 本来就只要求 `String.format` 兼容；
- `999` 的语义由**单一映射函数**处理，不外泄到调用方：

```kotlin
// 建议新增：backend/network/QualityTiers.kt（新文件）
/**
 * 音质档位：单一真相源。
 *
 * 注意 [LOSSLESS] 的 value=999 是 Meting-API 约定的 FLAC 标识符，
 * 不是真实比特率——不要用于 bitrate 显示、文件大小估算或统计。
 */
object QualityTiers {
    const val AUTO:     Int = 0
    const val STANDARD: Int = 128   // 标准
    const val GOOD:     Int = 192   // 高品（v1.3：由 HIGH_QUAL 更名，避免与 HIGH=320 混淆）
    const val HIGH:     Int = 320   // 极高
    const val LOSSLESS: Int = 999   // 无损（FLAC 标识符，非真实码率）

    /** 全部档位（含 AUTO），设置页遍历用 */
    val all: List<Int> = listOf(AUTO, STANDARD, GOOD, HIGH, LOSSLESS)

    /** 探测/降级链用：排除 AUTO 的真实码率档，降序 */
    val availableTiers: List<Int> = listOf(LOSSLESS, HIGH, GOOD, STANDARD)

    /** null 表示"不传 br，走端点默认" */
    fun isNoBr(tier: Int): Boolean = tier == AUTO

    /** 档位值 → 期望容器扩展名（无损=flac，其余=mp3） */
    fun preferredExtOf(tier: Int): String = if (tier == LOSSLESS) "flac" else "mp3"

    /** 档位值 → 展示标签（供 UI 复用，勿在 Compose 里硬编码） */
    fun labelResOf(tier: Int): Int = when (tier) {
        LOSSLESS -> R.string.quality_tier_lossless
        HIGH     -> R.string.quality_tier_high
        GOOD     -> R.string.quality_tier_good       // 新增 string
        STANDARD -> R.string.quality_tier_standard
        else     -> R.string.quality_tier_auto
    }

    /**
     * 降级链：目标档失败时依次尝试更低的档。
     * AUTO 返回 listOf(null) —— 不传 br，完全交给端点。
     * 与现状 MetingApiService.kt:232 的 listOf(tier, 320, 128) 语义一致，但补齐 192。
     */
    fun fallbackChainOf(tier: Int): List<Int?> = when (tier) {
        AUTO     -> listOf(null)
        LOSSLESS -> listOf(999, 320, 192, 128)   // v1.3 更正：补 192，与"补齐 192"的决策一致
        HIGH     -> listOf(320, 192, 128)
        GOOD     -> listOf(192, 128)
        else     -> listOf(tier, 128)
    }
}
```

> **保留 `AppPreferences.kt:74-77` 的旧常量作为别名**（`const val QUALITY_TIER_LOSSLESS = QualityTiers.LOSSLESS`），避免破坏已有引用；新代码一律引用 `QualityTiers`。

### 2.3 决策 D3：档位粒度 —— 全局默认 + 单曲覆盖（两级）【已定稿】

> **决策状态**：Q1 已确认要做两级模型，纳入正式排期（见 §7 Phase 4）。

**两级模型：**

```
实际档位 = 单曲覆盖值 ?: 全局默认值
```

- **全局默认**（现有 `AppPreferences.qualityTier`）：影响所有新播放、自动下载、下载面板默认选项。
- **单曲覆盖**（新增）：用户在播放器音质面板勾选「仅本次播放」时生效，存于 `songQualityOverrides`，以 `networkSource:networkId` 为键。

**为什么不直接做纯单曲粒度：** 电视端遥控焦点操作成本高，用户不希望"每次换歌都要重选码率"；全局默认 + 单曲例外是最低认知负担的形态。

**为什么不直接做纯全局：** 无损 FLAC 单首 20-30MB，用户可能只想给一两张收藏曲下载无损；纯全局会让"下载面板"每次都被迫二次确认。

#### 2.3.1 覆盖值存储

新增 `data/prefs/QualityOverrides.kt`：

```kotlin
/**
 * 单曲音质覆盖（D3）。
 *
 * 键 = "networkSource:networkId"（与 [NetworkMusicManager.playUrlKey] 的
 * source:id 部分一致，便于排查），值 = 音质档位。
 *
 * 容量上限 500 条，超出按最后修改时间淘汰最旧——单曲覆盖是"极少数例外"，
 * 无界增长无意义。存于独立的 DataStore 文件（settings_quality_overrides），
 * 与主偏好的读写频率隔离，避免热路径互相干扰。
 */
class QualityOverrides(context: Context) {

    data class Entry(val tier: Int, val updatedAt: Long)

    fun tierOf(source: String, id: String): Int?
    suspend fun put(source: String, id: String, tier: Int)
    suspend fun remove(source: String, id: String)
    fun all(): Map<String, Int>          // 供列表徽标批量查询
    suspend fun clearAll()
}
```

**不存进 Room `downloads.db`**：覆盖值是"播放偏好"，不是"下载索引"；下载表按 `songKey`（含 `:q<quality>` 后缀）唯一键组织（§4.2.2），若把覆盖值塞进去会引入跨表语义耦合。

#### 2.3.2 生效优先级与边界

| 场景 | 使用的档位 |
|---|---|
| 手动播放网络歌曲 | 单曲覆盖 ?: 全局默认 |
| 自动下载 | **仅全局默认**（覆盖值不被消费） |
| 下载面板默认选项 | 单曲覆盖 ?: 全局默认 |
| 批量下载 | 批次统一档位（显式选择，覆盖前两者） |
| NAS / 本地 / 网盘 / 电台 | 不适用，UI 隐藏码率控件 |

**清除时机**：单曲覆盖条目在用户于下载面板选择「全部歌曲」范围时**不清除**（避免误删用户明确表达的单曲偏好）；提供设置页「清除全部单曲覆盖」入口。

### 2.4 决策 D4：档位变化时的缓存失效

必须做，否则 G1 修了也白搭。

触发点：`AppPreferences.setQualityTier()` 成功写入后。

行为：**清除 `NetworkMusicManager.playUrlCache` 全量条目**。

理由：直链是"某音源 × 某歌 × 某码率"三元组绑定的时效性资源，档位一变，全部旧链接的语义都失效。而 `playUrlCache` 容量上限只有 500 条（`NetworkMusicManager.kt:31`），全量清除的代价可忽略，不需要精细的按档过滤。

> 顺带修 G5：当前档位变化**没有任何**缓存清理动作，这是缺陷不是优化。

### 2.5 决策 D5：哪些源支持多码率

| 源 | `networkSource` | 是否支持 br | 处理 |
|---|---|---|---|
| Meting-API | `meting` | ✅ | 完整支持 |
| Jamendo | `jamendo` | ❌ | 忽略档位，忽略不报错 |
| 百度网盘 | `baidu` | ❌ | 文件即原始码率，UI 隐藏码率选择 |
| NAS 后端 | （非网络） | ❌ | 已有文件码率，UI 隐藏 |
| 本地歌曲 | （本地） | ❌ | 同上 |
| 天气电台 | `weather` | ❌ | 纯流媒体，不可下载 |

**接口层用默认参数实现"能力差异"**，而不是让不支持的源抛异常：

```kotlin
interface NetworkMusicService {
    // 现状签名（NetworkMusicService.kt:55）—— 不动，所有既有实现与调用方零改动
    suspend fun resolvePlayUrl(song: Song): String?

    // 新增 1：带 quality 的字符串版本。默认实现**忽略 quality** 委托旧签名，
    // 因此 Jamendo / 百度网盘 / 其他源无需任何改动，也不会因为"不支持 br"而报错。
    suspend fun resolvePlayUrl(song: Song, quality: Int): String? = resolvePlayUrl(song)

    // 新增 2：需要"实际命中档位"（降级信号）时走这个。默认实现包一层，
    // actualQuality 恒等于请求档位——对不支持多码率的源这是正确语义（没有降级）。
    suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult =
        ResolveResult(resolvePlayUrl(song, quality), quality)
}
```

**为什么是两个方法而不是一个**：`resolvePlayUrl` 在 `PlayerViewModel`、`StreamUrlResolver`、`NasMusicApp` 等 6 处被调用，全部期望 `String?`；若把接口签名直接改成返回 `ResolveResult`，这 6 处全部编译失败，且 `BaiduStreamFactory` 等非 Meting 实现也要跟着改。拆成两个方法后：**播放主路径继续用 `String?`**（`NetworkMusicManager` 内部调用 detailed 版本拿降级信号，见 §3.2），只有真正需要降级信息的调用方用 detailed 版本。

只有 `MetingApiService` 覆盖这两个新方法。这符合 AGENTS.md 里"接口向后兼容、避免破坏既有调用方"的项目惯例。

### 2.6 决策 D6：降级语义 —— 播放与下载均静默降级，反馈渠道不同【已修订 v1.2】

`br=999` 在**非 VIP / 非无损版权曲**上大概率返回空或 404。统一采用**静默降级**：

| 路径 | 行为 | 用户可见反馈 |
|---|---|---|
| 播放 | 走 §2.2 `fallbackChain` 自动降级到可获取的最高档 | **提示**：Toast/通知"该曲无无损版，已降级为 320 kbps"（一次性） |
| 手动下载 | 自动降级到可获取的最高档，任务**标记成功** | **文件名体现实际码率**（§4.3），不弹错误 |
| 自动下载 | 同手动下载 | **文件名体现实际码率**；批次完成后汇总提示"其中 N 首为降级码率" |

**为什么下载也静默降级**：用户表达的是"我要这首歌的离线副本"，而非"我只要 FLAC 容器"。降级保存一个可用文件，优于返回 `FAILED` 让用户改档重试——后者会在下载面板堆出大量 FAILED 条目，反而阻塞用户完成下载。

**但降级不是无痕**，两条持久化记录：

1. 文件名里的实际码率后缀（`03 - 南方姑娘 (320).mp3`）——用户在文件管理器里一眼可见；
2. `DownloadSongEntity.quality` 记录**实际落盘档位**，而非请求档位（见 §4.4）。

**边界条件**：`fallbackChainOf()` 全部失败（所有档都返回空）→ 任务标记 `FAILED`，`errorMsg` 沿用现状文案"无法获取下载链接"（`SongDownloadManager.kt:219`）。这不是降级失败，是**无源可降**。

> **注意播放与下载的失败文案不同**：播放侧走 `NetworkMusicManager`（无专门文案，由上层提示"解析失败"），下载侧是 `SongDownloadManager.kt:219` 的"无法获取下载链接"。不要为了统一而改动下载侧文案——它已被既有单测/UI 依赖。

---

## 3. 播放链路详细设计

### 3.1 缓存键（修 G1 / G5）

`NetworkMusicManager.kt` 当前：

```kotlin
// :54
private val playUrlCache = ConcurrentHashMap<String, CachedPlayUrl>()
// :106
suspend fun resolvePlayUrl(song: Song, forceRefresh: Boolean = false): String?
```

改为：

```kotlin
private val playUrlCache = ConcurrentHashMap<String, CachedPlayUrl>()

/** 缓存 key：networkSource:networkId:quality（音质参与，切档位不串） */
private fun playUrlKey(song: Song, quality: Int): String =
    "${song.networkSource}:${song.networkId}:$quality"

/**
 * 有效档位解析（D3 两级模型，§2.3）：单曲覆盖优先，回退全局默认。
 *
 * 两个 provider 均由 NasMusicApp 以函数形式注入，与现有
 * `qualityTierProvider`（NasMusicApp.kt:283）风格一致；
 * NetworkMusicManager 不持有 AppPreferences 引用（见 §6.3）。
 */
private fun effectiveQuality(song: Song): Int {
    val src = song.networkSource ?: return qualityTierProvider()
    val id = song.networkId ?: return qualityTierProvider()
    return songQualityOverrideProvider?.tierOf(src, id) ?: qualityTierProvider()
}

/**
 * 无 quality 参数：档位由 D3 两级模型自动解析。
 * ⚠️ 内部实现名带 Impl 后缀 —— 见下方"重载歧义"说明。
 */
suspend fun resolvePlayUrl(song: Song, forceRefresh: Boolean = false): String? =
    resolvePlayUrlImpl(song, quality = effectiveQuality(song), forceRefresh = forceRefresh)

/**
 * 带降级信号的解析（播放主路径内部使用，供 §3.4 的降级提示消费）。
 *
 * @param quality 显式指定档位；null 表示走 D3 两级模型（单曲覆盖 ?: 全局默认）。
 *        批量下载、下载面板明确选择档位时传具体值。
 * @param forceRefresh 为 true 时强制绕过缓存、走完整降级链重新解析。
 * @return url 为 null 表示解析失败；actualQuality ≠ 请求档位表示发生降级
 */
suspend fun resolvePlayUrlDetailed(song: Song, quality: Int, forceRefresh: Boolean = false): ResolveResult {
    if (!song.isNetworkSong) return ResolveResult(song.streamUrl, quality)
    val q = quality
    ...  // 现有路由逻辑不变，改为取 svc.resolvePlayUrlDetailed(song, q)，缓存 key 用 playUrlKey(song, q)
}

/** 内部实现：quality 非空版本（命名带 Impl 以避免重载歧义） */
private suspend fun resolvePlayUrlImpl(
    song: Song,
    quality: Int,
    forceRefresh: Boolean
): String? = resolvePlayUrlDetailed(song, quality, forceRefresh).url

/** 档位变化时由 AppPreferences.setQualityTier() 调用，清空全部播放直链缓存 */
fun clearPlayUrlCache() { playUrlCache.clear() }
```

> ⚠️ **重载歧义（实施时必踩）**：如果同时存在 `resolvePlayUrl(song, forceRefresh: Boolean = false)` 与 `resolvePlayUrl(song, quality: Int? = null, forceRefresh: Boolean = false)`，那么 `resolvePlayUrl(song)` 这类调用会落到"哪个重载"上变得不直观，且 `resolvePlayUrl(song, 320)` 与 `resolvePlayUrl(song, true)` 仅靠类型区分、极易写错。**因此不要用"同名的可空 Int 重载"**：显式档位入口一律命名为 `resolvePlayUrlDetailed(song, quality)`（quality 为**非空 Int**），无参入口保留 `resolvePlayUrl(song, forceRefresh)`。这样两个入口名字不同、语义清晰，也不会影响既有 6 处调用方。

要点：

1. **保留旧签名**做转发，避免改动所有调用方（`PlayerViewModel.kt:306`、`StreamUrlResolver`、`NasMusicApp.kt:341-342` 等）；
2. 缓存 key 用 `networkSource:networkId` 而非 `song.id`——`song.id` 的格式是 `ntwk_meting_<id>`（`MetingApiService.kt:409`），已经含 source，但用原始字段更清晰且不受 ID 格式变更影响。**注意此处的分隔符是冒号，与下载主键的下划线格式（§4.2.2）属于两个独立命名空间**，不要试图统一（统一会改动缓存 key 语义，无收益）；
3. `clearPlayUrlCache()` 的调用点挂在 `AppPreferences.setQualityTier()` 之后（见 §6.2 依赖方向）。

### 3.2 `MetingApiService.resolvePlayUrl` 改造

现状 `MetingApiService.kt:226-259` 已经在做 `brChain` 遍历，改造点很少：

```kotlin
// 现状 :230-237
val tier = qualityTierProvider()
val brChain = if (tier <= 0) listOf(null) else listOf(tier, 320, 128).distinct()
for (endpoint in endpoints) {
    for (br in brChain) {
        val brParam = if (br != null) "&br=$br" else ""
        val url = "$endpoint?server=$server&type=url&id=${...}$brParam"
        ...
    }
}
```

改为覆盖新签名 + 复用 `QualityTiers.fallbackChainOf` + **返回实际生效档位**：

```kotlin
// MetingApiService.kt —— 覆盖 detailed 版本（含降级信号）
override suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult {
    val netId = song.networkId ?: return ResolveResult(song.streamUrl, quality)
    val chain = QualityTiers.fallbackChainOf(quality)   // 含 192 档；AUTO → listOf(null)
    val endpoints = buildEndpointFallbackOrder(baseUrl)

    for (endpoint in endpoints) {
        for (br in chain) {
            try {
                val brParam = br?.let { "&br=$it" } ?: ""
                val url = "$endpoint?server=$server&type=url&id=${URLEncoder.encode(netId, "UTF-8")}$brParam"
                noRedirectClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val playUrl = when (resp.code) {
                        302 -> resp.header("Location")
                        200 -> resp.body?.string()?.let { extractUrlFromJson(it) }
                        else -> null
                    }
                    if (!playUrl.isNullOrBlank()) {
                        // 实际命中档位 ≠ 请求档位 → 上游需要提示用户
                        // br == null（AUTO 档）时 actualQuality 记为 AUTO 本身，不产生降级提示
                        return ResolveResult(playUrl, br ?: quality)
                    }
                }
            } catch (e: Exception) { /* 现有日志 */ }
        }
    }
    return ResolveResult(null, quality)
}

// 同时覆盖 String 版本：忽略降级信号，供只关心 url 的调用方使用
override suspend fun resolvePlayUrl(song: Song, quality: Int): String? =
    resolvePlayUrlDetailed(song, quality).url

/**
 * 解析结果：url 可能为 null；actualQuality 为实际命中的档位
 * （降级发生时 ≠ 请求档位，UI 需据此提示"已降级"）。
 *
 * AUTO 档（quality=0）请求时 chain 为 listOf(null)，actualQuality 恒为 0，
 * 因此 AUTO 档**永远不触发降级提示**——端点默认给什么就是什么，这是正确语义。
 */
data class ResolveResult(val url: String?, val actualQuality: Int)
```

**注意现有双层循环顺序**：`endpoints` 在外、`br` 在内，意味着"同端点把档降级到底，才换下一个端点"。这个顺序**符合预期**（同一端点的不同档位直链通常同时有效），保持不变。

> ⚠️ **探测（§5.2.1）与解析必须复用同一实现**：`probeAvailableQualities()` 内部应当调用 `resolvePlayUrlDetailed(song, tier).url != null` 判定单档可用性，而**不是**另写一套 URL 拼接。否则两处对 302/200/JSON 三种响应形态的处理可能漂移，出现"探测说有、下载拿不到"的矛盾。代价是探测一次会走完整 fallback 链——但传入单档 `tier` 时链长仅为 1~2 档，开销可控。

### 3.3 `NetworkMusicService` 接口扩展（保持兼容）

```kotlin
// NetworkMusicService.kt:55 现状 —— 不动
suspend fun resolvePlayUrl(song: Song): String?

// 新增 1：带 quality 的 String 版本；默认实现忽略 quality（不支持多码率的源无需改动）
suspend fun resolvePlayUrl(song: Song, quality: Int): String? = resolvePlayUrl(song)

// 新增 2：带降级信号的版本；默认实现包一层，actualQuality 恒等于请求档
// （对不支持多码率的源，"没有降级"正是正确语义）
suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult =
    ResolveResult(resolvePlayUrl(song, quality), quality)
```

`NetworkMusicManager` 按 `networkSource` 精确路由（现状 :112-115），改为调用 `resolvePlayUrlDetailed` 以便拿到 `actualQuality` 用于降级提示，对外仍暴露 `String?`（§3.1）。

### 3.4 播放侧数据流

```
PlayerSettingsSection / 播放器音质按钮
        │ onChangeQualityTier(999)
        ▼
MainViewModel.setQualityTier(999, scope="ALL")
        │
        ├──▶ PlayerPrefs.setQualityTier(999)          ← AppPreferences.kt:908
        │        └──▶ NetworkMusicManager.clearPlayUrlCache()   ← 修 G5
        │
        └──▶ 若正在播放网络歌曲 → PlayerViewModel.replayWithQuality(999)
                     │
                     ▼
        NetworkMusicManager.resolvePlayUrl(song, quality=999, forceRefresh=true)
                     │  ← forceRefresh=true 强制绕过缓存
                     ▼
        MetingApiService.resolvePlayUrl(song, 999) → ResolveResult(url, actualQuality=320)
                     │
                     ├──▶ url != null → 更新 ExoPlayer 播放
                     └──▶ actualQuality ≠ 999 → Snackbar "该曲无无损版，已降级为 320 kbps"
```

> `forceRefresh=true` 的必要性：现状代码注释（`NetworkMusicManager.kt:99-101`）已明确说明，重试路径必须传 true，否则命中"已过期但仍在 5min TTL 内"的旧链接。**档位切换属于同类场景**。

### 3.5 音质徽标：UI 需要知道"实际在放什么"

新增到 `Song` 模型的一个**非持久化**字段（`Song.kt:15-39`）：

```kotlin
val bitrate: Int = 0,               // 已有 :29，NAS 歌曲会填（**真实码率**，勿与档位混用，见 §4.2.1）
val resolvedQuality: Int = 0,       // 新增：网络歌曲解析后实际命中的档位（0/128/192/320/999）
```

用途：列表/播放器显示"♫ 320"或"FLAC"徽标。`resolvedQuality` 只用于显示，**不参与缓存键和下载键**（缓存键见 §3.1，下载键见 §4.2.2，两者都用"请求/实际解析档位"而非此显示字段）。

**实施记录（v2.35.0 已完成）**：`resolvedQuality` 的**消费点**已接线——
`MainViewModel.replayCurrentWithQuality()` 在档位切换重播时写入
（`song.copy(streamUrl = result.url, resolvedQuality = result.actualQuality)`），
`UnifiedSongRow` 在渲染档位徽标时读取（优先取 `downloadState.quality`，回退 `resolvedQuality`）。
即"解析后实际命中的档位"会在歌曲行上可见，而不只是一个被写入后闲置的字段。

> **`Song` 是 `data class`，新增带默认值的字段是二进制兼容的**（不破坏 `copy()` 的既有调用）。但注意 `Song` 被 `data.model` 的 ProGuard keep 规则保护（AGENTS.md），且历史上有过"Gson 类型擦除导致 release 崩溃"的先例（v2.5.1）——**新增字段必须确认它不会被 Gson 序列化后反序列化**。`resolvedQuality` 是纯运行时显示字段、不落盘、不参与 JSON，因此风险为无；实施时不要把它写进任何持久化模型。

### 3.6 已下载本地文件优先 vs 切档：一个必须处理的冲突

**现状行为**（`PlayerViewModel.kt:305-306`、`:228-230`）：

```kotlin
song.isNetworkSong -> {
    nasMusicApp.downloadRepository.playableLocalUri(song)     // ← 已下载优先播本地文件
        ?: nasMusicApp.networkMusicManager.resolvePlayUrl(song, forceRefresh)
}
```

即"**只要该曲已下载，就播本地文件，根本不走网络解析**"。这与多码率方案直接冲突：

| 场景 | 现状结果 | 期望结果 |
|---|---|---|
| 已下载 320，用户切到无损 | 仍播本地 320 文件，**切档无任何效果** | 解析并播放在线无损流 |
| 已下载 320，用户切到 128 | 仍播本地 320 文件 | 解析并播放在线 128 流 |
| 已下载 320，全局默认本就是 320 | 播本地 320 文件（**离线可播，正确**） | 不变 |
| 已下载 320，用户切到无损但该曲无无损 | 播本地 320 | 降级到 320 → **此时应回到本地文件**（内容一致） |

**处理规则（建议）**：

```
if (song.isNetworkSong) {
    val requested = effectiveQuality(song)              // 单曲覆盖 ?: 全局默认
    val local = downloadRepository.playableLocalUri(song, requested)   // ← 按档位查本地
    if (local != null) return local                     // 该档已下载 → 播本地（离线优先）
    // 该档未下载 → 走网络解析
    val r = networkMusicManager.resolvePlayUrlDetailed(song, requested, forceRefresh)
    if (r.url == null) return downloadRepository.playableLocalUriAny(song)  // 网络失败 → 任意已下载档兜底
    if (r.actualQuality != requested) {
        // 降级发生：若降级后的档恰好已下载，优先本地（省流量 + 离线一致性）
        downloadRepository.playableLocalUri(song, r.actualQuality)?.let { return it }
    }
    return r.url
}
```

需要新增一个**按档位查本地文件**的仓储方法（现有 `playableLocalUri(song)` 无档位参数，`DownloadRepository` 内按 `song.downloadKey` 查）：

```kotlin
/**
 * 该曲在指定档位是否已有可播本地文件。
 * quality=0(AUTO) 时退化为现有行为（查存量行 / AUTO 行）。
 */
suspend fun playableLocalUri(song: Song, quality: Int): String? =
    get(song.downloadKeyOf(quality))?.takeIf { it.status == DownloadStatus.COMPLETED.name }
        ?.audioPath?.let { pathToUri(it) }

/** 任意档位的本地文件（网络解析彻底失败时的兜底，保证"能播就行"） */
suspend fun playableLocalUriAny(song: Song): String? =
    dao.bySongId(song.id).firstOrNull { it.status == DownloadStatus.COMPLETED.name && it.audioPath != null }
        ?.audioPath?.let { pathToUri(it) }
```

> ⚠️ **这是本方案里唯一会改变"已下载歌曲播放行为"的地方**，回归风险最高：用户已下载的歌曲在升级后，若全局默认档与其下载档不一致，会从"播本地"变成"走网络"。真机回归必须覆盖（§10.3 已加对应项）。若需求方希望**完全保持现状**（已下载就永远播本地、切档对已下载歌曲无效），则应把 `resolvedQuality` 徽标也改为显示"本地 320"，并在 UI 上禁用已下载歌曲的切档入口——**这是一个产品决策，当前按"切档生效"实现**。

---

## 4. 下载链路详细设计

### 4.1 入口签名（修 G2）

现状 `SongDownloadManager.kt:117`：

```kotlin
fun enqueue(song: Song, auto: Boolean)
```

改为：

```kotlin
/**
 * @param quality 音质档位；仅网络歌曲生效。
 *        为 null 时跟随全局默认档位；本地/网盘/NAS 歌曲忽略此参数。
 * @param auto 是否自动下载（配额只统计 auto=true 且 COMPLETED，见 DownloadSongEntity.kt:53）
 */
fun enqueue(song: Song, auto: Boolean, quality: Int? = null)
```

`StreamUrlResolver.kt:32` 同步扩展。**注意构造函数签名也要改**（`NasMusicApp.kt:339-343` 的接线处）：

```kotlin
// 现状（NasMusicApp.kt:339-343）
val downloadResolver = StreamUrlResolver(
    adapter = { backendRegistry.getAdapter() },
    network = { song -> networkMusicManager.resolvePlayUrl(song) },
    baidu = { song -> networkMusicManager.resolvePlayUrl(song) }
)
```

`network` / `baidu` 两个 lambda 当前是 `suspend (Song) -> String?`。要拿到 `actualQuality`，需要**新增一个返回 `ResolveResult` 的 lambda**，而不是把现有 lambda 改签名（后者会连带改动 `BaiduStreamFactory` 调用点）：

```kotlin
class StreamUrlResolver(
    private val adapter: () -> BackendAdapter?,
    private val network: suspend (Song) -> String?,
    private val baidu: suspend (Song) -> String? = { null },
    /** 新增：带降级信号的网络解析（默认委托 network，actualQuality 恒等于请求档） */
    private val networkDetailed: (suspend (Song, Int) -> ResolveResult)? = null
) {
    /**
     * 带档位的解析（下载主路径）。
     * @return url 为 null 表示无源可降；actualQuality 为实际命中档位
     */
    suspend fun resolveDetailed(song: Song, quality: Int): ResolveResult = when {
        !song.isNetworkSong -> ResolveResult(resolve(song), quality)
        networkDetailed == null -> ResolveResult(network(song), quality)
        else -> withContext(Dispatchers.IO) {
            runCatching { networkDetailed.invoke(song, quality) }
                .getOrElse { ResolveResult(null, quality) }
        }
    }

    /** 保留旧签名，委托 resolveDetailed（忽略降级信号） */
    suspend fun resolve(song: Song, quality: Int? = null): String? =
        if (quality == null) resolve(song) else resolveDetailed(song, quality).url
}
```

接线处（`NasMusicApp.kt:339-343`）改为多传一个 lambda：

```kotlin
val downloadResolver = com.nasmusic.tv.backend.download.StreamUrlResolver(
    adapter = { backendRegistry.getAdapter() },
    network = { song -> networkMusicManager.resolvePlayUrl(song) },
    baidu = { song -> networkMusicManager.resolvePlayUrl(song) },
    networkDetailed = { song, q -> networkMusicManager.resolvePlayUrlDetailed(song, q) }
)
```

> 之所以用**可空 lambda + 默认 null** 而不是必填参数：`StreamUrlResolver` 在单测里被大量构造（`test/.../download/` 下），加必填参数会一次性打断这些测试；可空默认值保持向后兼容。

### 4.2 下载索引：新增 quality 字段（修 G3）【方案 B 定稿，零重建迁移】

> **决策状态**：Q2 已确认走方案 B。本节是方案 B 的实施细节，含一处对原始表述的**技术纠正**（见 §4.2.3）。

#### 4.2.1 Entity 变更

`DownloadSongEntity.kt:19-56` 新增一个字段 + 一个索引（其余保持现状）：

```kotlin
@Entity(
    tableName = "download_songs",
    indices = [
        Index(value = ["songKey"], unique = true),   // :22 现有，不变
        Index(value = ["dedupeKey"]),                 // :23 现有，不变
        Index(value = ["status"]),                    // :24 现有，不变
        Index(value = ["songId"])                     // 新增：查询"该曲所有已下载档"
    ]
)
data class DownloadSongEntity(
    @PrimaryKey val songKey: String,
    val dedupeKey: String,
    val songId: String,
    ...
    val fileSize: Long = 0L,
    val durationMs: Long = 0L,
    val bitrate: Int = 0,                   // :45 现有：**媒体文件真实码率**（来自 Song.bitrate），勿混用
    val containerExt: String = "",          // :46 现有
    val quality: Int = 0,                   // 新增：**请求/实际命中的档位档位值**（0/128/192/320/999）
    ...
)
```

> ⚠️ **`quality` 与既有 `bitrate` 是两个不同的量，不要合并、不要互相赋值**：
> - `bitrate`（:45，已存在）是**媒体文件的真实比特率**（如 320、1411），由 `Song.bitrate` 带入（`DownloadRepository.kt:198`），NAS 歌曲会填，网络歌曲通常为 0；
> - `quality`（新增）是**档位标识值**，取值只可能是 `0/128/192/320/999`，其中 **`999` 不是真实码率**（是 FLAC 约定标识符，见 §2.2 注释）；
> - 因此**禁止**把 `quality` 写进 `bitrate` 列（会让 FLAC 显示成 "999 kbps"），也**禁止**用 `bitrate` 反推档位（降级到 320k 的文件真实码率可能是 320 也可能是 319，且 AUTO 档无从判断）；
> - 下载列表的档位徽标读 `quality`，文件详情里的码率读 `bitrate`，两者各自独立。

#### 4.2.2 `songKey` 推导规则

`backend/download/model/DownloadKeys.kt:21-26` 的 `downloadKey` 扩展属性**当前真实实现**是：

```kotlin
val Song.downloadKey: String
    get() = when {
        isLocalSong -> "local_$id"
        isNetworkSong -> "ntwk_${networkSource ?: "unknown"}_${networkId ?: id}"
        else -> "nas_$id"
    }
```

> ⚠️ **分隔符是下划线，不是冒号**。网络歌曲的 key 形如 `ntwk_meting_1234567`（三段下划线连接），不是 `ntwk:1234567`。本方案此前草稿中的冒号写法已全部更正——实现时若照抄冒号格式，存量数据将**全部查不到**（`get()` 返回 null → 已下载歌曲被判定为未下载 → 重复下载 + 目录堆积）。

新增**带档位的重载**（不改动无参版本，避免破坏 `AutoDownloadController.kt:65`、`DownloadRepository.kt:57,187`、`SongDownloadManager.kt:147,215` 五处既有调用）：

```kotlin
/**
 * 带音质档位的下载主键。
 *
 * - quality > 0（128/192/320/999）→ 追加 ":q<quality>" 后缀，实现同曲多档共存
 * - quality == 0（AUTO）→ **返回与旧实现完全相同的字符串**，因此
 *   ① 存量行天然兼容，无需回填；
 *   ② AUTO 档重复下载能被既有行拦下，不会重复下载。
 * - 本地 / NAS / 非 Meting 网络源 → 忽略 quality，返回旧格式
 */
fun Song.downloadKeyOf(quality: Int): String = when {
    isLocalSong -> downloadKey
    isNetworkSong -> {
        val base = downloadKey
        // 仅 Meting 支持多码率（§2.5）；Jamendo/百度等忽略档位
        if (networkSource == METING_SOURCE && quality > 0) "$base:q$quality" else base
    }
    else -> downloadKey
}

private const val METING_SOURCE = "meting"
```

落地后的 key 形态：

| 源 | 现状 key | 新 key（quality=0 / AUTO） | 新 key（quality>0） |
|---|---|---|---|
| 本地 | `local_<songId>` | 不变 | 不变（忽略档位） |
| NAS | `nas_<songId>` | 不变 | 不变（忽略档位） |
| Jamendo | `ntwk_jamendo_<id>` | 不变 | 不变（忽略档位） |
| 百度网盘 | `ntwk_baidu_<id>` | 不变 | 不变（忽略档位） |
| Meting（AUTO） | `ntwk_meting_<id>` | **`ntwk_meting_<id>`（与旧格式相同）** | — |
| Meting（320） | — | — | `ntwk_meting_<id>:q320` |
| Meting（999） | — | — | `ntwk_meting_<id>:q999` |

> **AUTO 档为何不加 `:q0` 后缀**：加了会让 AUTO 档的 key 与存量行（同为"端点默认码率"语义）字符串不等，导致升级后**每首已下载歌曲被重下一次**。不加后缀则 AUTO 天然复用存量行，这正是"零迁移"目标的必要组成部分。§4.2.5 的查询函数据此实现。

这与 D-Music 的 `source:id:quality`（`DownloadManager.kt:45`）语义一致，只是前缀沿用现有 `ntwk_<source>_<id>` 约定，且 AUTO 档不写后缀。

#### 4.2.3 一处必要的技术纠正

Q2 的原始表述是「新增 `(songId, quality)` 唯一索引」。**仅新增该索引不足以解决问题**：

```
songKey 仍是 @PrimaryKey → SQLite 强制 songKey 全局唯一
同曲两档 = 两行 = 相同 songKey = 主键冲突 → 插入直接失败
```

唯一索引只能约束「非主键列的组合唯一性」，无法解除主键自身的唯一约束。因此方案 B 的正确实现是：

| 手段 | 是否采用 | 作用 |
|---|---|---|
| `songKey` 追加 `:q<quality>` 后缀 | ✅ 采用 | 真正提供 (songId, quality) 维度的唯一性（经主键 unique 索引生效） |
| 新增 `(songId, quality)` 唯一索引 | ❌ 不采用 | 与主键约束重复，冗余 |
| 新增 `songId` 普通索引 | ✅ 采用 | 支持「该曲所有已下载档」查询（§5.2.3 徽标） |

**效果等价、风险更低**，仍属"零重建迁移"范畴。

#### 4.2.4 Room 迁移：只做一条 ADD COLUMN

> ⚠️ **版本号已核对源码**：`DownloadDatabase.kt:28` 当前是 `version = 1`，因此新增迁移是 **`MIGRATION_1_2`**（不是草稿里的 `MIGRATION_5_6`）。实施时同步把 `@Database(version = 1)` 改为 `2` 并把迁移注册进 `RoomDatabase.Builder.addMigrations(...)`。

```kotlin
// DownloadDatabase.kt
object MIGRATION_1_2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // SQLite 支持非破坏性加列：不重建表、不回填数据、不重写页。
        // songKey 存量值格式与新增列互不冲突（旧行无 :q 后缀，新行有），
        // 唯一索引不会因迁移失败。
        db.execSQL(
            "ALTER TABLE download_songs ADD COLUMN quality INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_download_songs_songId ON download_songs (songId)")
    }
}
```

注册（`DownloadDatabase.kt` 的 `build()` 内）：

```kotlin
Room.databaseBuilder(context, DownloadDatabase::class.java, "downloads.db")
    .addMigrations(MIGRATION_1_2)      // ← 新增，缺这行会在升级时抛 IllegalStateException
    .build()
```

> **必须验证迁移不丢数据**：用 `MigrationTestHelper`（Room 自带，`androidx.room:room-testing`）写一条迁移测试：v1 建库插入 3 条存量行（含 `ntwk_meting_x` 无后缀 key）→ 跑 `MIGRATION_1_2` → 断言 3 行仍在、`quality` 全为 0、`songKey` 未被改写。这是"零重建"承诺的唯一硬证据。

**对比方案 A（改 songKey 格式 + 整表重建）：**

| | 方案 A | 方案 B（定稿） |
|---|---|---|
| 表结构 | 删表 + 重建 + 回填 | 一条 `ALTER TABLE ADD COLUMN` |
| 存量数据 | 需批量改写 songKey，**必须先建备份表** | 原样保留 |
| 数据丢失风险 | **高**（回填逻辑出错即丢下载索引） | **无** |
| 存量行 quality 值 | 0（回填时写入） | 0（列默认值） |
| 代码一致性 | 存量与新数据格式统一 | 存量无 `:q` 后缀，查询层需兼容两种格式 |

**代价**：查询层必须兼容两种 `songKey` 格式。这是本方案唯一需要接受的权衡。

#### 4.2.5 查询层双格式兼容

新增到 `DownloadRepository`（key 由 `downloadKeyOf()` 统一生成，**调用方禁止自行拼字符串**）：

```kotlin
/**
 * 判断"该曲在该档是否已下载"。
 *
 * key 一律经 Song.downloadKeyOf(quality) 推导（§4.2.2），与落库时用的是同一函数，
 * 因此不存在"查询格式与写入格式不一致"的可能。
 *
 * 双格式兼容的实质：quality=0(AUTO) 的 key 与存量行格式**完全相同**
 * （都是 ntwk_meting_<id>），因此 AUTO 档天然能命中存量行；
 * 而 quality>0 的行 key 带 :q 后缀，与存量行字符串不等，互不干扰。
 */
suspend fun isDownloaded(song: Song, quality: Int): Boolean =
    get(song.downloadKeyOf(quality)) != null

/**
 * 该曲所有已下载档位（供列表徽标 / 下载面板"已下载"状态批量查询）。
 * 走 songId 索引，不走 songKey 前缀匹配。
 */
suspend fun downloadedQualitiesOf(songId: String): List<DownloadSongEntity> =
    dao.bySongId(songId)
```

DAO 新增（`DownloadSongDao.kt`）：

```kotlin
@Query("SELECT * FROM download_songs WHERE songId = :songId ORDER BY quality DESC")
suspend fun bySongId(songId: String): List<DownloadSongEntity>
```

**明确不做的事**：不写一次性回填任务把存量 `ntwk_meting_<id>` 改成 `ntwk_meting_<id>:q0`。理由：

1. 回填属于写操作，失败即数据损坏，与"零迁移风险"目标直接冲突；
2. **AUTO 档不写后缀**（§4.2.2）使存量行天然就是合法的新格式，回填没有必要；
3. 存量行会随用户删除旧下载自然消失，兼容性负担随时间递减。

### 4.3 文件名与扩展名（修 G4）【Q3 已定稿：非无损档追加档位后缀，取实际码率】

`DownloadPathBuilder.kt:97-108` 现状靠 URL 后缀猜扩展名。改为 quality 优先。

> **关键**：本节的 `quality` 参数一律是 **`ResolveResult.actualQuality`（实际解析成功的档位）**，不是用户请求的档位。请求 `999` 而降级到 `320` 时，落盘文件名是 ` (320).mp3`，不是 ` (999).flac`。这样文件名永远与实际内容一致（§2.6 静默降级的持久化记录之一）。

```kotlin
/**
 * 扩展名优先级：quality 显式指定 > URL 后缀 > 默认 mp3。
 *
 * @param quality 实际解析成功的档位（ResolveResult.actualQuality），非请求档位
 *
 * 无损档（999）必须落成 .flac——无损直链 URL 常无扩展名，
 * 靠 URL 后缀猜会把 FLAC 存成 .mp3 容器（D-Music 用 quality 判定规避了此坑）。
 * 若请求无损但降级到 320，此处收到 320，自然落 .mp3。
 */
fun extOf(url: String, song: Song, quality: Int = 0): String {
    if (quality == QualityTiers.LOSSLESS) return "flac"
    if (quality == QualityTiers.AUTO) {
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (fromUrl in AUDIO_EXTS) return fromUrl
        return defaultExtOfSong(song)   // 现有 song.path 后缀判定逻辑（DownloadPathBuilder.kt:101-107）
    }
    // 有码率的非无损档：一律 mp3
    return "mp3"
}

/**
 * 文件名基名（不含扩展名）：非无损档追加档位后缀，无损档不加。
 *
 * @param quality 实际解析成功的档位（ResolveResult.actualQuality），非请求档位
 *
 * 后缀规则：
 * - 999 无损 → 不加后缀（扩展名 .flac 已足以区分）
 * - 128/192/320 → 追加 " (128)" / " (192)" / " (320)"
 * - 0 AUTO / 本地 / NAS / 网盘 → 不加后缀（无档位概念）
 *
 * 空格 + 圆括号形式，与 Android 现有 uniqueFile() 的 " (2)" 去重后缀风格一致。
 *
 * 降级场景示例：请求无损、实际拿到 320 → "03 - 南方姑娘 (320).mp3"
 * （不是 "03 - 南方姑娘.flac"，也不是 FAILED）。
 */
fun baseNameWithQuality(song: Song, quality: Int): String {
    val base = if (song.trackNumber > 0)
        "%02d - %s".format(song.trackNumber, sanitize(song.title).ifBlank { "未命名" })
    else
        sanitize(song.title).ifBlank { "未命名" }

    val suffix = when (quality) {
        QualityTiers.STANDARD -> " (128)"
        QualityTiers.GOOD     -> " (192)"
        QualityTiers.HIGH     -> " (320)"
        else                  -> ""
    }
    return base + suffix
}
```

落地后的目录形态：

```
<root>/赵雷/理想国/03 - 南方姑娘.flac          ← 无损（q999）
<root>/赵雷/理想国/03 - 南方姑娘 (320).mp3     ← 极高（q320）
<root>/赵雷/理想国/03 - 南方姑娘 (128).mp3     ← 标准（q128）
<root>/赵雷/理想国/03 - 南方姑娘.mp3           ← 存量旧下载 / AUTO 档（q0，无后缀）
```

**与 D-Music 的差异**：D-Music 只靠扩展名区分（`DownloadManager.kt:47` 仅判 `flac`/`mp3`），同曲两档 MP3 会互相覆盖；本方案加档位后缀，支持同曲多档 MP3 共存。

**与 `uniqueFile()` 的交互**（`DownloadPathBuilder.kt:70-78`）：现有去重后缀 ` (2)` 基于文件存在性判断，档位后缀追加在基名后、去重后缀之后追加，二者不冲突：

```
03 - 南方姑娘 (320).mp3        ← 首次下载 320
03 - 南方姑娘 (320).mp3 → 已存在 → 03 - 南方姑娘 (320) (2).mp3   ← 同档重复下载
```

> 同档重复下载本应被 `isDownloaded(songId, quality)` 拦截，不会走到这里；` (2)` 仅作防御兜底。

### 4.4 降级下载流程（修 G6）【静默降级，v1.2 修订】

请求档位 ≠ 实际档位时，下载链路按"实际档位"贯穿到底，任务不失败：

```
用户请求 quality=999（无损）
        │
        ▼
StreamUrlResolver.resolve(song, 999)
        │  走 fallbackChainOf(999)：999 → 320 → 192 → 128
        ▼
ResolveResult(url, actualQuality=320)     ← 实际拿到 320k
        │
        ├─▶ songKey    = ntwk_meting_<id>:q320  （用实际档位，见 §4.2.2）
        ├─▶ extOf()    = "mp3"                  （用实际档位，§4.3）
        ├─▶ baseName   = "03 - 南方姑娘 (320)"   （用实际档位，§4.3）
        ├─▶ entity.quality = 320                 （实际落盘档位，非请求档位）
        └─▶ entity.status  = COMPLETED           （任务成功，不是 FAILED）
```

要点：

1. **`songKey` 必须用实际档位**。若用请求档位（`:q999`）建行，而 `entity.quality=320`，则下次用户请求 320k 时 `isDownloaded(song, 320)` 查不到，会重复下载同一份 320k 文件。这是本方案最容易犯的错。
2. **`entity.quality` 记录实际落盘档位**。下载列表（§5.2.3）显示"极高 320k"，与文件内容一致。
3. **不产生 FAILED 条目**。降级成功即 COMPLETED；只有 `fallbackChain` 全链失败才 FAILED（§2.6 边界条件）。
4. **降级原因需可查**：`entity` 无需新增列，`requestedQuality` 可通过 `quality != 文件名后缀` 或独立日志推断；若后续需要"降级次数统计"，再加 `requestedQuality` 列（当前不做，避免二次迁移）。

### 4.4.1 `requestedQuality` 与 `quality` 的取舍

| 方案 | 说明 | 采用 |
|---|---|---|
| 只存 `quality`（实际档位） | 迁移只加一列；文件名已承载"实际"信息 | ✅ 采用 |
| 同时存 `requestedQuality` + `quality` | 可精确统计降级率，但需多一列、多一处迁移 | ❌ 后续版本再议 |

理由：文件名后缀 + 下载日志已足够定位降级情况；为"降级率统计"这一锦上添花需求付出二次迁移成本不划算。若确需统计，可在 Phase 5 通过 `requestedQuality` 列补上（届时是 ADD COLUMN，仍非重建）。

### 4.4.2 下载循环中的 key 切换时机（实施必读）

`SongDownloadManager` 现有实现是**在下载开始前就用 `song.downloadKey` 建行**（`SongDownloadManager.kt:147` 取 key，`:215` 再次取 key，`:234` 建 DOWNLOADING 记录），而档位要等**解析直链之后**才知道（`singleAttempt` 第 1 步 `resolver.resolve(song)`，`:218`）。两者顺序是"先 key 后解析"，这与降级需求直接冲突。

**必须调整的执行顺序**（`singleAttempt` 内部）：

```
1. 解析直链 → 得到 ResolveResult(url, actualQuality)     ← 提前到最前面
   ├─ url == null → 落 FAILED（见 §4.4.3）
   └─ url != null → 继续
2. actual = result.actualQuality
3. key = song.downloadKeyOf(actual)                       ← key 在解析之后才确定
4. 幂等检查 repo.get(key)：已 COMPLETED → 返回 Already
5. ext = paths.extOf(url, song, actual)
6. p = paths.build(song, ext)（基名用 baseNameWithQuality(song, actual)）
7. entity = repo.newDownloadingEntity(..., quality = actual)   ← 建行时写入 quality
8. 下载 → 内嵌 → rename → 完成
```

**连带需要改的三处**：

| 位置 | 现状 | 改法 |
|---|---|---|
| `SongDownloadManager.kt:147` `val key = song.downloadKey` | 在 `executeDownload` 开头 | 移到解析之后；但 `executeDownload` 开头的**重试循环**仍需要一个 key 来更新状态——用 `song.downloadKeyOf(requestedQuality)` 作为"占位 key"更新进度，解析成功后若 `actual != requested` 则同时把占位行清理/迁移（见下） |
| `SongDownloadManager.kt:215` `val key = song.downloadKey` | `singleAttempt` 内 | 改为 `song.downloadKeyOf(actual)` |
| `DownloadRepository.kt:187` `songKey = song.downloadKey` | `newDownloadingEntity` 内 | 新增 `quality: Int` 参数，内部改用 `song.downloadKeyOf(quality)`，并写入 `quality = quality` |

**占位行处理（避免残留）**：若解析前已用请求档建了 DOWNLOADING 行，而实际降级到别的档，则会出现一条永远不会完成的孤儿行。两种处理方式，**推荐前者**：

1. **不在解析前建行**（推荐）：把"建 DOWNLOADING 行"整体挪到第 7 步，解析期间只在内存里 `_downloadStates` 标记 Downloading，UI 有进度反馈但不落库。解析失败则连行都不用建，直接通知 UI（对应 §4.4.3）。
2. 解析前建行 + 解析后按实际档迁移：需要 `DELETE` 占位行再 `INSERT` 新行，且中间失败会留孤儿，复杂度更高。

### 4.4.3 解析失败时的落库差异（现状 vs 本方案）

现状代码在解析失败时**直接返回 `Failure("无法获取下载链接")`（`SongDownloadManager.kt:218-219`），并未落 FAILED 行**——因为 FAILED 落库发生在重试耗尽之后（`:207`），而该行是用请求档 key 建的，需要能查到。本方案的静默降级会改变这一路径：

| 场景 | 现状行为 | 本方案行为 |
|---|---|---|
| 解析返回 null（全链失败） | 返回 Failure，重试耗尽后按请求档 key 落 FAILED 行 | 与现状一致，但 key 用 `downloadKeyOf(requested)`（无降级信息，只能按请求档记） |
| 解析成功但降级 | 不存在此概念 | 按**实际档** key 落 COMPLETED 行（§4.4） |
| 重试期间档位变化 | 无 | 每次重试**重新解析**（`singleAttempt` 内解析），因此第二次重试可能命中不同档位；key 以最后一次成功的 `actual` 为准 |

> ⚠️ **重试 + 降级叠加的边界**：第一次尝试解析到 320 但下载中途失败，第二次尝试解析到 128（上游波动）——最终落库 key 是 `:q128`，而文件名也是 `(128)`，二者一致（都是最后一次实际成功的档位），**不会出现 key 与文件名不一致**。但要确保 `_downloadStates` 里旧的 `:q320` 进度条目被清除，否则 UI 会残留一条"下载中"。实现时在每次 `singleAttempt` 入口按当前 key 清理上一轮状态条目。

### 4.5 自动下载与配额

`AutoDownloadController` 当前不感知档位（已 grep 确认无 `quality` 字样）。规则：

- 自动下载统一使用**全局默认档位**，不使用单曲覆盖（覆盖值是"用户在 UI 上明确表达的单曲偏好"，不该被自动下载消费）；
- **去重判定改用 `isDownloaded(song, quality)`，不再走 `dedupeKey`**（Q4 已采纳：同曲不同档视为不同资源）。`dedupeKey` 列保留不动（仍用于 UI 跨源识别），但**不再作为下载前置拦截条件**；
- **静默降级后必须按实际档二次去重**（见下方实现要点，这是本方案的必踩坑）；
- 配额计算（`autoDownloaded=true 且 COMPLETED`，`DownloadSongEntity.kt:53`）不受档位影响，仍按首数计，不按字节；
- 建议后续版本把配额改为按字节（无损档单首 25MB vs 128k 首 5MB，同配额下流量差 5 倍）。

实现要点：

```kotlin
// AutoDownloadController：入队 → 解析 → 二次去重 → 落库
val requested = quality                                 // 全局默认档，如 999
if (repo.isDownloaded(song, requested)) return          // 前置去重

val result = resolver.resolveDetailed(song, requested)  // 走完整降级链
    ?: return                                           // 全链失败才放弃
val actual = result.actualQuality                       // 如 320（静默降级，§2.6）

if (repo.isDownloaded(song, actual)) return             // ⚠ 二次去重：按实际档
// 落库一律用 actual：songKey = ntwk_meting_<id>:q320，entity.quality = 320
manager.enqueue(song, auto = true, quality = actual)
```

两条去重都不能省：

| 只保留哪条 | 后果 |
|---|---|
| 只前置去重（按 `requested`） | 全局默认 999 的用户，首次自动下载 320-only 歌曲 → 落 `:q320`；**下次运行再次请求 999 → 再次降级到 320 → 插入 `:q320` 主键冲突**（方案 B 下 `songKey` 是主键，冲突直接抛异常） |
| 只二次去重（按 `actual`） | 每首已下载歌曲仍要发一次网络探测请求，浪费流量 |

> 注意**不要**用 `downloadedQualitiesOf(songId).isNotEmpty()` 判定——那会把"已下载 128k"误判为"320k 也已完成"，导致无损自动下载永久跳过。
>
> 另注意：`AutoDownloadController.kt:65` 现状是 `if (repo.get(song.downloadKey) != null) return@launch`（**一次网络请求都不发**就跳过）。改成"先解析再判档"后，**已下载歌曲也会发一次解析请求**——这是二次去重的固有代价（见上表第二行）。若想避免，可在解析前先做一次廉价的 `downloadedQualitiesOf(songId)` 预判：**仅当该曲已有任一档下载、且全局默认档就是该档**时才跳过，否则才解析。这属于可选优化，v1 可先不做。

---

## 5. 界面设计

NASMusicTV 是 TV Compose 应用，同时支持电视与手机（`AndroidManifest.xml` 三个 feature 均 `required="false"`，`MainActivity` 为 `screenOrientation="fullSensor"`）。所有设计必须**遥控焦点友好**：单一焦点轨道、键盘/方向键可达、无小目标、无鼠标依赖。

### 5.1 播放器音质切换器（NowPlaying 主路径）

**位置**：现在播放条（`ui/components/branches/NowPlayingBranch.kt`）右侧功能区，与"随机/顺序"按钮同级。

**形态**：单按钮循环切换 + 弹窗详情。TV 端不做横向排布 5 个档位按钮（焦点距离过长）。

```
┌───────────────────────────────────────────────────────────────────┐
│  ┌────────┐                                                        │
│  │  封面  │   南方姑娘                        [♪ 无损 FLAC]        │
│  │  320x  │   赵雷 · 理想国                     [🔁 顺序] [🔀 随机] │
│  │        │   02:34 ━━━━━━●━━━━━━━━━━ 04:21  [⏮] [▶] [⏭]          │
│  └────────┘                                                        │
└───────────────────────────────────────────────────────────────────┘
                            ▲
                     焦点项：[♪ 无损 FLAC]
                     点击 → 弹出音质选择面板
```

**音质选择面板（Dialog）：**

```
┌─────────────────────────────────────────┐
│          音质                            │
│                                         │
│   ◉ 自动（端点决定）                     │   ← 焦点
│   ○ 标准      128 kbps                  │
│   ○ 高品      192 kbps                  │
│   ○ 极高      320 kbps                  │
│   ○ 无损      FLAC                      │
│                                         │
│  范围：  [☑ 仅本次播放]  ☐ 全部歌曲       │
│                                         │
│              [ 取消 ]        [ 确定 ]    │
└─────────────────────────────────────────┘
```

交互规则：

| 操作 | 行为 |
|---|---|
| OK 焦点键 | 应用所选档位（范围按复选框） |
| 方向键 ↑/↓ | 在 5 档间移动焦点 |
| ←/→ | 在"仅本次播放 / 全部歌曲"范围间切换 |
| BACK | 取消，不修改 |

**范围映射到 §2.3 的两级模型：**

- 「全部歌曲」→ 写 `AppPreferences.setQualityTier()` + `clearPlayUrlCache()`；
- 「仅本次播放」→ 写 `songQualityOverrides[网络源ID]`，仅当前曲生效，不污染全局默认。

**降级提示**（对应 §2.6）：

```
┌───────────────────────────────────────────────────────────┐
│ ⚠  该曲无无损版本，已降级为 320 kbps          [知道了]     │
└───────────────────────────────────────────────────────────┘
```

不阻塞播放，3 秒自动消失（TV 端 Snackbar 语义）。

### 5.2 下载音质选择

#### 5.2.1 单曲下载【v1.2 修订：按可用码率数决定是否弹窗】

入口：歌曲列表行长按 / 遥控器菜单键 → 操作菜单 → 点「下载到本地」。

**交互规则（本次修订核心）**：

| 探测结果 | 行为 |
|---|---|
| 可用码率 **> 1** | 自动弹出码率选择，**只列出可用档**；默认选中最高可用档 |
| 可用码率 **== 1** | **不弹窗**，直接按该档下载（用户无感） |
| 可用码率 **== 0** | 弹错误提示"无法获取该曲的播放直链"，不入队 |

```
点「下载到本地」
        │
        ▼
┌──────────────────────┐      探测中（≈1s）
│  正在获取可用音质...  │
└──────────────────────┘
        │
        ├─ 多档可用 ──▶ 弹码率选择（见下）
        ├─ 仅一档 ───▶ 直接入队下载，仅 Toast "开始下载 320 kbps"
        └─ 零档 ────▶ 错误提示，不入队
```

**为什么必须探测而不能查表**：GD 音乐台 API **没有"查询该曲可用码率"的接口**（参考实现 `MusicApi.kt` 只有 `search` / `resolveUrl` / `fetchLyric` / `fetchPicUrl` 四个端点，无 quality-list 类端点）。可用码率只能靠"逐档请求 `resolveUrl` 看是否返回非空"探测得出。

**探测实现**（放在新增文件 `backend/download/QualityProbe.kt`）：

```kotlin
const val PROBE_TIMEOUT_MS = 1200L   // 单档探测超时，整链并发因此总耗时 ≈1.2s

/**
 * 探测该曲实际可获取的码率档位。
 *
 * 并发探测整条链（排除 AUTO），每档独立短超时；
 * 只请求 URL 解析接口，不下载音频数据，因此开销极小。
 *
 * ⚠️ 内部必须复用 resolvePlayUrlDetailed(song, tier)，不要另写一套 URL 拼接——
 * 否则两处对 302 / 200-JSON / 空响应 三种形态的处理会漂移，
 * 出现"探测说有、下载拿不到"的矛盾（见 §3.2 末注）。
 *
 * @return 可用档位列表，按码率从高到低排序；空列表表示无源可降
 */
suspend fun probeAvailableQualities(
    song: Song,
    tiers: List<Int> = QualityTiers.availableTiers   // [999, 320, 192, 128]
): List<Int> = withContext(Dispatchers.IO) {
    tiers.map { tier ->
        async {
            tier to (runCatching {
                withTimeout(PROBE_TIMEOUT_MS) {
                    resolvePlayUrlDetailed(song, tier).url != null
                }
            }.getOrDefault(false))
        }
    }.awaitAll()
     .filter { it.second }
     .map { it.first }      // 输入已降序，输出保持降序
}
```

> **并发必须用 `async`/`awaitAll`，不能用 `map { ... }` 串行调用**：后者是 4 × 单档耗时（最坏 4 × 1.2s = 4.8s），与"总耗时 ≈1.2s"的设计目标不符。注意 `tiers.map { }` 若直接在里面调 suspend 函数是**顺序执行**的，这是最容易写错的地方。

**探测开销评估**：整链 4 档并发 = 4 个并发小 GET（响应体只是 JSON 里的 URL 字符串，非音频流），实测该 API 的 URL 解析响应在百毫秒级；受 1200ms 超时封顶，用户感知的"正在获取可用音质"提示约 0.3-1.2s。**不预缓存探测结果**——直链有时效性，且探测本身不产生可复用数据。

> ⚠️ **探测会经过端点 fallback 链**：`resolvePlayUrlDetailed` 内部对每个 tier 遍历全部端点（§3.2），因此单档探测的最坏耗时是 `端点数 × 单请求耗时`，而 `PROBE_TIMEOUT_MS` 是对**整个单档调用**封顶（`withTimeout` 包在最外层）。这意味着**弱网 + 多端点时，单档超时会导致该档被判为不可用**——即使它在第一个端点其实是可用的。这是 §9 已记录的"探测误判"风险的机制来源，实现时不要试图给每个端点单独设超时（会让总耗时不可控）。

#### 5.2.1.1 码率选择面板（多档可用时弹出）

```
┌───────────────────────────────┐
│   选择音质 · 南方姑娘           │
├───────────────────────────────┤
│  ◉ 极高 320 kbps               │   ◀ 默认选中（最高可用档）
│  ○ 标准 128 kbps               │
│                                │
│  ─────────────────────────     │
│  ● 已下载 320 kbps             │   灰色，不可选
│                                │
│        [ 取消 ]    [ 下载 ]     │
└───────────────────────────────┘
```

面板规则：

- **只列出探测到的可用档**——不展示"无损 FLAC"这种该曲实际拿不到的选项（避免用户点了才失败）；
- **默认选中最高可用档**，而非全局默认档（全局默认档可能在该曲不可用）；
- **已下载档位置灰并标注**，避免重复下载（判定用 `isDownloaded(songId, quality)`，§4.2.5）；
- 若所有可用档都已下载 → 面板显示"该曲所有可用音质均已下载"，仅保留「取消」；
- **不展示估算字节数**：Meting/GD API 不返回文件大小，任何字节数都是 `durationMs × bitrate / 8` 的估算值，v1 一律省略，避免展示不实数据。

#### 5.2.2 批量下载【已取消，不做】

> ⚠️ **本节不实施**（需求方决定，2026-09-18）。
> 原因：本项目 UI **不存在批量下载入口**，本节描述的是"先新建批量下载 UI、再叠加档位"，
> 属新功能而非本方案的补全断点。以下内容仅作**设计留档**，供将来真的要做批量下载时参考；
> 其中"逐曲独立静默降级"这一条已由单曲路径（§4.4）实现并可复用。

下载面板（`DownloadScreen` 同级）增加顶部档位选择：

```
┌──────────────────────────────────────────────────────────────┐
│  下载                              批量音质： [极高 320 ▾]    │
├──────────────────────────────────────────────────────────────┤
│  ▶ 全选 (12)                                                  │
│                                                               │
│  ☑ 南方姑娘 · 赵雷            实际 320      9.6 MB   [⏸]     │
│  ☑ 理想国 · 赵雷              实际 128 ↓   3.7 MB   100%     │
│  ☐ 成都 · 赵雷                请求 320      待开始             │
│  ☐ 画 · 赵雷                  请求 320      待开始             │
│                                                               │
│  并发 3 路 │ 总计 1.9 GB │ [暂停全部] [取消全部]              │
└──────────────────────────────────────────────────────────────┘
```

规则：

- **批量音质为本批次统一请求档位**，覆盖全局默认；逐行仍可按单曲菜单（§5.2.1）单独改。
- **每首歌独立静默降级**（§2.6）：请求 320 但该曲最高只有 128 → 落 ` (128).mp3`，任务成功，行内标注「实际 128 ↓」（`↓` 表示低于请求档）。
- **批量下载不做前置探测**：单曲路径探测是为了"决定弹不弹窗"，批量路径已在批次级选定档位，直接入队 + 逐曲降级即可，避免 12 首 × 4 档 = 48 个探测请求。
- **批次完成汇总提示**："12 首已完成，其中 3 首为降级码率"，可点开查看明细。
- **不展示估算字节数**（同 §5.2.1）：Meting/GD API 不返回文件大小；上图为示意，v1 实际隐藏该列或按已下载文件实测大小回填（仅完成后可知）。

#### 5.2.3 已下载列表的档位展示

```
┌─────────────────────────────────────────────────────────────┐
│  本地下载                                  [按歌手] [按专辑]  │
├─────────────────────────────────────────────────────────────┤
│  南方姑娘 · 赵雷     FLAC 无损    24.8 MB  04:21   [▶][🗑]   │
│  南方姑娘 · 赵雷     极高 320      9.6 MB  04:21   [▶][🗑]   │
│  成都 · 赵雷         极高 320      8.1 MB  03:47   [▶][🗑]   │
└─────────────────────────────────────────────────────────────┘
```

同曲两行是**预期行为**（对应 `songKey` 的 `ntwk_meting_<id>:q999` / `ntwk_meting_<id>:q320` 两个索引项）。必须在 UI 上做视觉区分，否则用户会认为"重复下载了"。

**显示的档位是 `entity.quality`（实际落盘档）**，不是请求档位。若某曲请求无损但只拿到 320k，列表里显示「极高 320」而不是「无损」——这与文件名后缀、实际音频内容三者一致。可选加一个 `↓` 角标标识"该曲为降级结果"（需 `requestedQuality`，见 §4.4.1；v1 可省略）。

**不展示估算字节数**：上图为示意。API 不返回文件大小，v1 对**已完成**的行按本地文件实测大小显示（可靠），对**进行中/待开始**的行显示 `—`，不显示估算值。

**去重提示**：`DownloadSongEntity.dedupeKey`（:29）现有"跨源去重"语义是"同曲跨平台不重复下载"。多码率会与该语义冲突——见 §8 决策记录 Q4（已按推荐值采纳：**同曲不同档视为不同资源，`dedupeKey` 不参与去重判定**）。实现上：下载去重判定走 `isDownloaded(song, quality)`（§4.2.5），**不走** `dedupeKey`；`AutoDownloadController` 需同步调整。

> ⚠️ **`dedupeKey` 的 UI 语义需要重新确认**：`DownloadRepository.kt:42` 的 `findCompletedByDedupe(dedupe)` 目前在别处仍被用于"UI 显示 ✓ / 不再发起下载"（`DownloadKeys.kt:32` 注释）。把去重判定改走 `isDownloaded()` 后，**`findCompletedByDedupe` 的调用点必须一并排查**：若某处 UI 仍用它判断"已下载"，会出现"显示了 ✓ 但该档其实没下载"的不一致。实施时先 grep `findCompletedByDedupe` 与 `dedupeKey` 的全部调用点，逐个决定保留或改走档位判定。

**实施记录（v2.35.0 已完成）**：

1. **`findCompletedByDedupe` 排查结论**：实施时 grep 全部调用点，确认它**只被 `AutoDownloadController` 使用**（已改为 `isDownloaded(song, quality)` 两段式判定），**没有任何 UI 依赖它**。因此不存在"显示 ✓ 但该档没下载"的风险。该 DAO 方法保留未删（供未来跨源去重语义复用）。
2. **徽标接线方式**（与本节示意图的差异）：本节原设计是"已下载列表按行显示档位列"。实施时发现**下载歌曲实际是通过 `local_songs`（`storageType=DOWNLOAD`）合并进本地曲库展示的**，UI 层没有直接读 `download_songs` 表的列表页（全仓库 `DownloadSongEntity` 仅被 `QualityBadge` 的注释引用）。因此改为**在通用歌曲行 `UnifiedSongRow` 上渲染档位徽标**：
   - 数据通道：`DownloadState.Completed` 新增 `quality` 字段（由 `SongDownloadManager`/`DownloadRepository` 从 `entity.quality` 带入），复用已有的 `downloadStates` 传递链路，**无需 UI 层新增查询**；
   - 取档位优先级：`downloadState.quality`（已下载）→ `song.resolvedQuality`（已解析但未下载）；
   - `quality == AUTO(0)` 时不渲染徽标（存量行 / 本地 / NAS / 网盘无档位概念）；
   - 该方案同时覆盖本地曲库、搜索结果、专辑/艺术家详情、网盘等**全部**使用 `UnifiedSongRow` 的列表，比原设计的"仅下载列表"覆盖面更广。
3. **`↓` 降级角标**：按本节原计划（"v1 可省略"）未实现。`QualityBadge` 已预留 `downgraded` 参数，需要时传入即可。

### 5.3 设置页音质区块改造

现状 `PlayerSettingsSection.kt:120-131` 是硬编码 4 按钮，且**不含 192**：

```kotlin
listOf(
    0 to stringResource(R.string.quality_tier_auto),
    999 to stringResource(R.string.quality_tier_lossless),
    320 to stringResource(R.string.quality_tier_high),
    128 to stringResource(R.string.quality_tier_standard),
).forEach { (tier, label) -> ... }
```

改为遍历 `QualityTiers.all`，并补 `192`：

```
┌─────────────────────────────────────────────────────────────┐
│  播放设置                                                     │
│                                                              │
│  音质档位（网络音乐）                                          │
│  [▶ 自动]  [标准 128]  [高品 192]  [极高 320]  [无损 FLAC]   │
│                                                              │
│  说明：无损仅限支持 FLAC 的曲库；不可用时自动降级并提示         │
│                                                              │
│  [均衡器]                                                     │
│  ── 人声分离模式 ──                                           │
└─────────────────────────────────────────────────────────────┘
```

TV 端 5 个按钮横向排布可能超出焦点区宽度，建议改为**两行**或**单选列表**：

```
  音质档位：▶ 无损 FLAC
  [自动] [标准] [高品] [极高] [无损]      ← 一行 5 个，按钮宽度均分
```

**同时补充两条说明文案**（降低用户困惑）：

1. 「无损仅限支持 FLAC 的曲库；不可用时自动降级并提示」——对应 §2.6；
2. 「本档位仅影响网络音乐；NAS / 本地 / 网盘歌曲按其原始码率播放」——对应 §2.5。

**实施记录（v2.35.0 已完成）**：两条文案已落地为
`quality_tier_hint_lossless` / `quality_tier_hint_scope`（中英双语），
在 `PlayerSettingsSection` 的档位按钮组下方以 `FontSize.small()` 渲染。

**新增子区块（仅当 Q1 评审通过时）：**

```
  单曲音质覆盖
  [ 启用 ]   启用后，在播放器中选择「仅本次播放」可单独指定某首歌的音质
```

---

## 6. 组件实现结构

### 6.1 新增文件

| 文件 | 职责 |
|---|---|
| `backend/network/QualityTiers.kt` | 档位单一真相源：常量、标签、扩展名映射、降级链（§2.2） |
| `backend/network/ResolveResult.kt` | 解析结果封装（url + actualQuality），承载降级信号 |
| `ui/components/QualityPickerDialog.kt` | 音质选择弹窗（播放器路径复用） |
| `ui/components/QualityBadge.kt` | 档位徽标组件（列表行、下载列表复用） |
| `ui/components/DownloadQualityMenu.kt` | 下载音质菜单（单曲路径，按探测结果动态渲染，§5.2.1.1） |
| `backend/download/QualityProbe.kt` | `probeAvailableQualities()`：并发探测可用档 + 单档短超时（§5.2.1）；GD API 无 quality-list 端点，必须靠探测 |
| `data/prefs/QualityOverrides.kt` | 单曲覆盖映射持久化：独立 DataStore 文件、500 条 LRU 上限、`tierOf/put/remove/all/clearAll`（§2.3.1） |
| `test/.../MigrationTest.kt` | `MIGRATION_1_2` 迁移测试（`MigrationTestHelper`）：v1 数据 → v2 后行数/内容不变（§4.2.4） |

### 6.2 修改文件清单

| 文件 | 改动 | 对应缺口 |
|---|---|---|
| `data/prefs/AppPreferences.kt` | 新增 `QUALITY_TIER_GOOD=192`；`setQualityTier()` 增加缓存清理回调；旧常量改为 `QualityTiers` 别名 | G6 / D1 / D4 |
| `data/prefs/PlayerPrefs.kt` | 转发新增方法 | — |
| `backend/network/NetworkMusicService.kt` | 新增 `resolvePlayUrl(song, quality)` + `resolvePlayUrlDetailed(song, quality)`（均有默认实现） | 兼容层 |
| `backend/network/NetworkMusicManager.kt` | 新增 `resolvePlayUrlDetailed()`；`playUrlKey()` 含 quality；新增 `clearPlayUrlCache()`；**不新增同名 Int 重载**（§3.1 重载歧义） | **G1 / G5** |
| `backend/network/MetingApiService.kt` | 覆盖两个新方法；降级链改用 `QualityTiers.fallbackChainOf()`（补 192）；返回 `ResolveResult` | — |
| `NasMusicApp.kt` | 接入 `clearPlayUrlCache` 回调（DI 处 :283 附近）；`StreamUrlResolver` 构造处（:339-343）新增 `networkDetailed` lambda | G5 / G2 |
| `backend/download/StreamUrlResolver.kt` | 新增 `resolveDetailed(song, quality)`；构造新增可空 `networkDetailed`（默认 null，保持单测兼容） | **G2** |
| `backend/download/SongDownloadManager.kt` | `enqueue(song, auto, quality)`；**把"建 DOWNLOADING 行"挪到解析之后**（§4.4.2）；写入 `entity.quality = actualQuality`；落库前按实际档二次去重（§4.4、§4.5） | **G2 / G3 / Q6** |
| `backend/download/db/DownloadSongEntity.kt` | 新增 `quality: Int = 0` | **G3** |
| `backend/download/db/DownloadDatabase.kt` | **`version = 1 → 2`** + `MIGRATION_1_2`（一条 `ALTER TABLE ADD COLUMN quality` + `songId` 索引）+ `addMigrations()` 注册，**零重建**（§4.2.4） | **G3** |
| `backend/download/db/DownloadSongDao.kt` | 新增 `bySongId(songId)` 查询 | G3 |
| `backend/download/model/DownloadKeys.kt` | 新增 `downloadKeyOf(quality)`；**下划线格式，AUTO 档不加后缀**；存量不回填（§4.2.2） | **G3** |
| `backend/download/DownloadRepository.kt` | 新增 `isDownloaded(song, quality)` / `downloadedQualitiesOf(songId)` / `playableLocalUri(song, quality)` / `playableLocalUriAny(song)`；`newDownloadingEntity()` 新增 `quality` 参数 | G3 / §3.6 |
| `backend/download/DownloadPathBuilder.kt` | `extOf(url, song, quality)` quality 优先 + `baseNameWithQuality()` 非无损档追加档位后缀（§4.3） | **G4** |
| `backend/download/AutoDownloadController.kt` | 去重改走 `isDownloaded(song, quality)`；**前置去重 + 按实际档二次去重**（§4.5）；`get(song.downloadKey)` 调用点（:65）需改造 | Q4 / Q6 |
| `data/model/Song.kt` | 新增 `resolvedQuality: Int = 0`（仅显示用，不入持久化） | §3.5 |
| `ui/viewmodel/PlayerViewModel.kt` | `resolveStreamUrl()`（:301-307）改为**按档位查本地优先 + 降级后回退本地**（§3.6） | §3.6 |
| `ui/screens/settings/PlayerSettingsSection.kt` | 遍历 `QualityTiers.all`，补 192，加说明文案 | G6 |
| `ui/components/branches/SettingsBranch.kt` | 接线新方法 | — |
| `ui/viewmodel/MainViewModel.kt` | `setQualityTier()` 编排（写偏好 → 清缓存 → 重播）；新增 `setQualityTier(tier, scope)` 支持「仅本次播放 / 全部歌曲」 | — |
| `ui/viewmodel/NetworkMusicViewModel.kt` | 档位状态暴露 | — |
| `ui/components/branches/NowPlayingBranch.kt` | 音质按钮 + 面板 | 5.1 |
| `ui/screens/downloads/*` | 批量档位选择 + 行内徽标 + 单曲下载探测/弹窗接线 | 5.2 |
| `res/values/strings.xml` | 5 档标签（含新增 `quality_tier_good`）+ 说明文案 + 降级提示文案 | — |

### 6.3 依赖方向（避免循环依赖）

```
AppPreferences ──写事件──▶ NetworkMusicManager.clearPlayUrlCache()
      │
      │ Flow<Int>（全局默认档位）
      ▼
PlayerPrefs  ──▶ MainViewModel  ──▶ MetingApiService
      │                 │
      │                 └──▶ SongDownloadManager.enqueue(..., quality)
      │
QualityOverrides ──读 tierOf()──▶ NetworkMusicManager.effectiveQuality()
      ▲                                   （单曲覆盖优先，§2.3）
      │ 写 put()/remove()
      │
MainViewModel（播放器音质面板「仅本次播放」）
```

**禁止**反向依赖：

- `NetworkMusicManager` 不得持有 `AppPreferences` 引用（现状通过 `qualityTierProvider` 函数注入，保持该形态）；
- 单曲覆盖通过新增的 `songQualityOverrideProvider` **函数注入**，同样是 provider 风格，不引入 `QualityOverrides` 类型依赖（避免 `backend/network` 反向依赖 `data/prefs`）；
- 档位变化通知使用**回调函数**而非事件总线，与 `NasMusicApp.kt:283` 现有注入风格一致。

---

## 7. 分期实施

### Phase 1：修缺陷（0.5 人日，**必须先做**）

目标：让现有的 4 档设置真正生效。

- [x] `NetworkMusicManager` 缓存 key 加入 quality（G1）
- [x] 新增 `clearPlayUrlCache()`，挂到 `setQualityTier()`（G5）
- [x] 档位切换后 `forceRefresh=true` 重解析
- [ ] 单测：切换档位后旧档直链不被复用

**Phase 1 是纯缺陷修复，无 UI 变更，可独立发布。**

### Phase 2：码率能力补齐（1.5 人日）

- [x] 新增 `QualityTiers.kt` 单一真相源（`object` + `fallbackChainOf()`），补 192 档
- [x] `fallbackChainOf(LOSSLESS)` 补 192（§2.2 已更正为 `999 → 320 → 192 → 128`）
- [x] `NetworkMusicService` 新增 `resolvePlayUrl(song, quality)` + `resolvePlayUrlDetailed(song, quality)` + `MetingApiService` 覆盖 + `ResolveResult`
- [x] 降级提示（Snackbar）
- [x] 设置页遍历 `QualityTiers.all`
- [x] 单测：`QualityTiers` 全部分支、`ResolveResult` 降级信号（`QualityTiersTest` / `ResolveResultTest`）

### Phase 3：下载多码率（2.5 人日）

- [x] `enqueue(song, auto, quality)` + `StreamUrlResolver.resolveDetailed()` + `networkDetailed` lambda 接线（`NasMusicApp.kt:339-343`）
- [x] `DownloadSongEntity.quality` + **`version 1→2` + `MIGRATION_1_2` + `addMigrations()` 注册**（零重建，§4.2.4）
- [x] `downloadKeyOf(quality)`：Meting 网络歌曲追加 `:q<quality>`，**用实际档、AUTO 档不加后缀、下划线前缀**；存量不回填
- [x] `DownloadRepository.isDownloaded()` / `downloadedQualitiesOf()` 双格式兼容
- [x] `extOf(url, song, quality)` + `baseNameWithQuality()`，参数一律取 `actualQuality`（§4.3）
- [x] **下载循环重排**：把"建 DOWNLOADING 行"挪到解析之后，避免降级时留孤儿行（§4.4.2）
- [x] **静默降级落库链路**：`songKey` / `extOf` / `baseName` / `entity.quality` 全部用实际档；全链失败才 FAILED（§4.4，Q6）
- [x] `AutoDownloadController`：前置去重（按请求档）+ **落库前二次去重（按实际档）**，两条都不可省（§4.5）
- [x] **排查 `dedupeKey` / `findCompletedByDedupe` 的全部调用点**（§5.2.3 末注）
- [x] 单测：同曲多档不冲突（`DownloadKeyTest`）、扩展名判定矩阵（`DownloadQualityPathTest`）、
      双格式查询兼容（`DownloadKeyTest`）、**迁移测试**（`DownloadDatabaseMigrationTest`）
      > ⚠️ **"降级落库键一致"未单独写类**（原计划 `DowngradePersistTest`）。
      > 该性质目前由 `DownloadKeyTest` 的"实际档位→key"断言 + `ResolveResultTest` 的降级信号
      > + `DownloadQualityPathTest` 的"降级后文件名含实际档"三条**组合覆盖**，
      > 但**未端到端验证**"解析降级 → 落库 key/quality/文件名三者一致"这一完整链路。
      > **实施偏差（已定稿）**：迁移测试**未使用 `MigrationTestHelper`**。原因：它要求把 schema JSON 挂到
      > 测试 assets，而开启 `unitTests.isIncludeAndroidResources = true` 会破坏既有
      > `BaiduMvFileServiceTest`（实测对照：仅开启该配置后该类才出现 `UncaughtExceptionsBeforeTest`）。
      > 改为**手工按 v1 原始 DDL 建库 + `Room.databaseBuilder` + `MIGRATION_1_2` 打开**——
      > 走的正是生产升级路径，Room 的 schema 校验照样生效，且零全局配置副作用。
      > 实测 8 例通过；`DownloadDatabase` 的 `exportSchema` 已改为 `true`，
      > `app/schemas/.../DownloadDatabase/{1,2}.json` 作为权威基线入库。

### Phase 4：界面 + 单曲覆盖粒度（3 人日）

- [x] 播放器音质切换器 + 面板（5.1）+ 降级提示
- [x] **`PlayerViewModel.resolveStreamUrl()` 按档位查本地优先**（§3.6，**回归风险最高的改动**）
- [x] `QualityProbe.probeAvailableQualities()` 并发探测（`async`/`awaitAll`）+ 1200ms 单档超时（§5.2.1）
- [x] **单曲下载自动弹窗判定**：多档弹菜单（仅列可用档）、单档直下、零档报错（§5.2.1，Q7）
- [ ] ~~批量下载逐曲独立降级 + 批次完成汇总提示（§5.2.2）~~ **【已取消，不做】**
      > **需求方决定（2026-09-18）**：本项**不做**。补充排查依据——本项目 UI 确实
      > **不存在批量下载入口**（全仓库仅 `onDownloadSong` 单曲下载，无 `downloadAll`/批量选择 UI），
      > §5.2.2 描述的是"先新建批量下载 UI、再叠加档位"，属新功能而非本方案的补全断点。
      > 后续若新增批量下载入口，单曲路径的逐曲降级（§4.4）已可直接复用
      > `enqueue(song, auto, quality)`，无需重新设计。
- [x] 已下载列表档位徽标（显示实际档，§5.2.3）
- [x] 设置页改造（5.3）
- [x] **单曲覆盖**：`QualityOverrides.kt` 持久化（§2.3.1）+ 面板「仅本次播放 / 全部歌曲」范围选项（5.1）+ 设置页「清除全部单曲覆盖」入口
- [ ] 真机遥控焦点走查
      > **待人工执行**：需在基准设备（创维 5.1.1 开发机）实机走查 §10.3 的 21 项清单，
      > 其中含本方案**回归风险最高**的"已下载歌曲切档是否生效"（§3.6 改变了既有播放行为）。

**合计**：Phase 1-4 约 **7.5 人日**（Phase 3 由 2 增至 2.5，用于下载循环重排与 `dedupeKey` 调用点排查；Phase 4 维持 3 人日）。

> **实际工作量**：§5.2.2 批量下载已取消（需求方决定），实际实施 **≈7 人日**。
> 另额外投入约 1 人日用于测试补强（`DownloadDatabaseMigrationTest`、`QualityProbeTest`）
> 与"已实现但未接线"的三处收尾（档位徽标、设置页说明文案、`resolvedQuality` 消费点）。

---

## 8. 决策记录

Q1 / Q2 / Q3 / Q6 / Q7 / Q8 / Q9 由需求方确认，Q4 / Q5 按文档推荐值**直接采纳执行**（低影响项，无需等待确认）。

| # | 问题 | 结论 | 状态 | 落地位置 |
|---|---|---|---|---|
| Q1 | 是否要做"全局默认 + 单曲覆盖"两级模型（D3）？ | **做** | ✅ 需求方确认 | §2.3、§2.3.1、§2.3.2；`QualityOverrides.kt`；并入 Phase 4 |
| Q2 | 下载索引迁移方案？ | **方案 B：零重建迁移**（实现细节见 §8.1） | ✅ 需求方确认 | §4.2.1-§4.2.5 |
| Q3 | 非无损档下载文件名是否追加档位后缀？ | **追加** `(128)` / `(192)` / `(320)` | ✅ 需求方确认 | §4.3 `baseNameWithQuality()` |
| Q4 | `dedupeKey` 是否排除"同曲不同码率"？ | **排除**（同曲不同档视为不同资源），去重判定改走 `isDownloaded(songId, quality)` | ✅ 已按推荐采纳 | §4.5、§5.2.3、`AutoDownloadController` |
| Q5 | 192 档展示标签 | **"高品"**（沿用 D-Music 文案，保持用户认知一致） | ✅ 已按推荐采纳 | `strings.xml` |
| Q6 | 无损下载失败语义 | **静默降级**到可获取的最高档，任务成功；文件名体现实际码率 | ✅ 需求方确认（v1.2 修订，反转原推荐值） | §2.6、§4.4、§4.4.1 |
| Q7 | 单曲下载的码率选择时机 | 点击下载后**先探测可用档**：多档则弹窗（仅列可用档），单档直接下载，零档报错 | ✅ 需求方确认 | §5.2.1、§5.2.1.1 |
| Q8 | 降级反馈的统一原则 | 播放与下载均静默降级；**播放提示，下载写入文件名** | ✅ 需求方确认 | §2.6、§4.3 |
| Q9 | §5.2.2 批量下载档位是否实施？ | **不做**（本项目 UI 无批量下载入口，属新功能而非补全断点） | ✅ 需求方决定（2026-09-18） | §5.2.2、§7 |

### 8.1 Q2 结论的一处实现修正（务必阅读）

Q2 的原始表述是「新增 `(songId, quality)` 唯一索引」。实施时发现**仅新增该索引无法解决问题**：

```
songKey 仍是 @PrimaryKey → SQLite 强制 songKey 全局唯一
同曲两档 = 两行 = 相同 songKey = 主键冲突 → 插入直接失败
```

唯一索引只能约束非主键列的组合唯一性，不能解除主键自身的唯一约束。因此方案 B 落地为：

| 手段 | 采用 |
|---|---|
| `songKey` 追加 `:q<quality>` 后缀（经主键 unique 索引生效） | ✅ |
| 新增 `songId` 普通索引（支持"该曲所有已下载档"查询） | ✅ |
| 新增 `(songId, quality)` 唯一索引 | ❌ 冗余，不采用 |

**效果与"零迁移"目标一致**：仍无整表重建、无数据回填、无备份表需求。完整对照见 §4.2.3。

### 8.2 关于 Q2 实现修正的处置

该修正**不改变需求方的决策意图**，因此按默认继续执行，不再阻塞等待确认：

- 需求方选方案 B 的目标是"**零重建迁移、不丢下载数据**"；
- 原表述的"新增唯一索引"无法达成该目标（主键冲突会让功能直接不可用）；
- §4.2.3 的落地方式完整达成该目标，且风险更低（无冗余索引、无回填逻辑）；
- 回退成本：若需求方坚持字面方案，只需改 `downloadKey()` 一处 + 追加一条 `CREATE UNIQUE INDEX`，不影响其他章节。

**本文档当前无未决项**：Q1/Q2/Q3 已在初稿阶段确认，Q6/Q7/Q8（静默降级语义、单曲下载探测弹窗、降级反馈分工）于 v1.2 由需求方确认，Q4/Q5 按推荐值采纳。Phase 1 可直接开工。

---

## 9. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| Room 迁移丢数据 | **低**（方案 B 已定稿） | §4.2.4 仅一条 `ALTER TABLE ADD COLUMN`，无整表重建、无回填，SQLite 非破坏性加列；`MigrationTest` 断言行数与内容不变 |
| **迁移版本号写错**（误用 `MIGRATION_5_6`） | **中** | DB 当前 `version = 1`（`DownloadDatabase.kt:28`），迁移必须命名 `MIGRATION_1_2` 并同步 `@Database(version = 2)` + `addMigrations()`；漏注册会抛 `IllegalStateException` |
| **`downloadKey` 分隔符写错**（冒号 vs 下划线） | **高** | 真实格式是 `ntwk_<source>_<id>`（`DownloadKeys.kt:21-26`）。写错则存量数据全部查不到 → 已下载歌曲被重复下载。缓解：key 一律经 `downloadKeyOf()` 生成，禁止调用方拼字符串；`DownloadKeyTest` 断言 AUTO 档返回值与旧 `downloadKey` **完全相等** |
| **AUTO 档加了 `:q0` 后缀 → 升级后全量重下** | **高** | §4.2.2 明确 AUTO 档不加后缀，使存量行天然等于新格式；`DownloadKeyTest` 有专项断言 |
| 存量 `songKey` 双格式共存导致查询遗漏（旧行无 `:q` 后缀） | **中** | §4.2.5 `isDownloaded()` / `downloadedQualitiesOf()` 统一收口，禁止调用方自行拼 key；单测覆盖双格式 |
| **已下载歌曲的播放行为被改变**（§3.6） | **高** | 现状"已下载就播本地"会让切档对已下载歌曲失效；改为"按档位查本地优先"后，升级用户的已下载歌曲可能从"播本地"变为"走网络"。这是**唯一改变既有播放行为**的改动，真机回归必测；若需求方要保现状，则需在 UI 上禁用已下载歌曲的切档入口 |
| **降级时残留孤儿 DOWNLOADING 行** | **中** | §4.4.2 把"建行"挪到解析之后；`DowngradePersistTest` 断言无残留；真机回归检查下载列表无卡住的"下载中" |
| 无损直链大面积返回空（非 VIP / 非无损版权曲） | 中 | §2.6 静默降级，播放侧提示 + 下载侧文件名体现实际码率，不产生 FAILED 条目；设置页写明"仅支持 FLAC 的曲库" |
| **降级后落库键错位 → 主键冲突** | **高** | 请求 `:q999` 降级到 `:q320`，若 `songKey` 用请求档则下次自动下载二次插入 `:q320` 冲突。缓解：§4.4 全链用实际档 + §4.5 落库前按实际档**二次去重**；单测覆盖该场景 |
| 探测误判为"单档"而跳过弹窗（探测超时 ≠ 该档不可用） | 中 | §5.2.1 `PROBE_TIMEOUT_MS=1200` 封顶（对**单档整个 fallback 链**封顶，非单端点）；直链最终仍走完整 `fallbackChain`，探测只决定 UI 形态，不影响下载正确性。弱网下用户可能错过选档机会，属可接受降级 |
| **探测误用串行 `map` 而非 `async`** | 中 | §5.2.1 已给出 `async`/`awaitAll` 实现；串行会让总耗时变成 4×单档（最坏 4.8s）。`QualityProbeTest` 有并发耗时断言 |
| 探测增加下载点击延迟（4 档并发小请求） | 低 | 仅 URL 解析请求（非音频流），总耗时 ≈1.2s 封顶；「正在获取可用音质」提示明确告知；批量路径不做前置探测（§5.2.2） |
| **`dedupeKey` 改判定后 UI 显示不一致** | 中 | `findCompletedByDedupe` 的既有调用点需逐个排查（§5.2.3 末注），避免出现"显示 ✓ 但该档未下载" |
| 用户认为"我要无损却下到 320k"是欺骗 | 中 | 文件名 `(320)` 后缀即持久化记录（§2.6）；播放侧有可见提示；下载面板汇总"其中 N 首为降级码率" |
| 档位切换后正在播放的歌曲不生效 | 中 | 编排层显式 `replayWithQuality` + `forceRefresh=true` |
| `br` 参数被端点忽略（部分公共端点不支持） | 中 | `ResolveResult.actualQuality` 与请求档位比对；不一致即提示 |
| **`quality` 与既有 `bitrate` 字段混用** | 中 | §4.2.1 明确两者语义边界（档位标识 vs 真实码率）；禁止互相赋值，尤其不要把 `999` 写进 `bitrate` |
| 多源并存时 UI 显示"无损"但实际走 Jamendo/百度（不支持 br） | 中 | §2.5 能力差异用默认参数；UI 按 `networkSource` 隐藏码率控件 |
| 无损下载流量 5-8 倍于 128k，自动下载配额失真 | 中 | Phase 3 明确"自动下载只用全局默认档，不消费单曲覆盖"（§2.3.2）；后续改按字节配额 |
| 单曲覆盖条目无界增长 | 低 | §2.3.1 `QualityOverrides` 500 条 LRU 上限 + 设置页清除入口 |
| 档位后缀 `(320)` 与 `uniqueFile()` 去重后缀 `(2)` 混淆 | 低 | §4.3 明确追加顺序；同档重复下载本应被 `isDownloaded()` 拦截，`(2)` 仅作防御 |
| 硬编码档位列表再次漂移（`PlayerSettingsSection.kt:120-131`） | 低 | 抽 `QualityTiers.all` 单一真相源，UI 一律遍历 |
| Meting API 公共端点失效（社区维护，可能随时挂） | 低-中 | 现状已有 `buildEndpointFallbackOrder()` 多端点降级（`MetingApiService.kt:174-185`），不变 |

---

## 10. 测试清单

### 10.1 单元测试（`testDebugUnitTest`，本机可跑）

> **实施状态（v2.35.0，2026-09-18）**：本节共列出 13 个测试类，实际落地 **7 个**（全部通过）：
> `QualityTiersTest` / `ResolveResultTest` / `DownloadKeyTest` / `DownloadQualityPathTest` /
> `DownloadStateLookupTest` / `DownloadDatabaseMigrationTest` / `QualityProbeTest`。
> **未写的 6 个**及原因：
>
> | 未写的测试类 | 状态 | 说明 |
> |---|---|---|
> | `PlayUrlCacheKeyTest` | ❌ 未写 | 缓存键含档位已由代码实现，但**无自动化测试**；真机回归第 2 项覆盖 |
> | `QualityOverridesTest` | ❌ 未写 | 单曲覆盖的 LRU 淘汰/持久化隔离**无自动化测试** |
> | `MetingResolveTest` | ❌ 未写 | 需 MockWebServer 打桩，**`br` 参数拼接无自动化测试** |
> | `LocalPlaybackPriorityTest` | ❌ 未写 | §3.6 是**回归风险最高的改动**，却无测试覆盖，只能靠真机回归 |
> | `AutoDownloadDedupeTest` | ❌ 未写 | §4.5 两段式去重（含主键冲突回归）**无自动化测试** |
> | `DowngradePersistTest` | ❌ 未写 | 降级落库键一致性仅由 3 条组合断言间接覆盖（见 §7 Phase 3） |
>
> 因此下方清单中的 `- [ ]` **不代表"待办"**，而是"本节设计但未落地"。
> 若要补齐，优先级建议：`LocalPlaybackPriorityTest` > `AutoDownloadDedupeTest` >
> `PlayUrlCacheKeyTest` > 其余（前两者对应高风险路径，后三者属实现细节）。

**新增 `QualityTiersTest`**（覆盖 §2.2）：

- [ ] `fallbackChainOf(AUTO) == listOf(null)`
- [ ] `fallbackChainOf(LOSSLESS) == listOf(999, 320, 192, 128)`（**含 192，v1.3 更正点**）
- [ ] `fallbackChainOf(HIGH) == listOf(320, 192, 128)`
- [ ] `fallbackChainOf(GOOD) == listOf(192, 128)`
- [ ] `fallbackChainOf(STANDARD) == listOf(128)`
- [ ] `preferredExtOf()`：999→flac，其余→mp3
- [ ] `all` 恰含 5 个值且含 192；`availableTiers` 恰含 4 个值且**不含 AUTO**
- [ ] `isNoBr(AUTO) == true`，其余档 `== false`

**新增 `PlayUrlCacheKeyTest`**（覆盖 G1）：

- [ ] 同歌不同档 → 缓存 key 不同
- [ ] 同歌同档 → 缓存 key 相同（TTL 内复用）
- [ ] `clearPlayUrlCache()` 后不再命中
- [ ] TTL 过期 + `forceRefresh=false` → 重新解析（现状行为回归）
- [ ] `forceRefresh=true` 时绕过未过期缓存（现状回归，`NetworkMusicManager.kt:99-101`）
- [ ] `resolvePlayUrlDetailed()` 与 `resolvePlayUrl()` 对同曲同档**命中同一缓存条目**（防止两个入口各自缓存一份）

**新增 `DownloadExtTest`**（覆盖 G4）：

- [ ] `quality=999` → `flac`（即使 URL 无扩展名）
- [ ] `quality=320` → `mp3`
- [ ] `quality=192` → `mp3`
- [ ] `quality=0(AUTO)` + URL 含 `.flac` → `flac`
- [ ] `quality=0(AUTO)` + URL 无扩展名 → 回退 `song.path` 判定（`DownloadPathBuilder.kt:101-107`）
- [ ] 回归：NAS/本地/网盘路径不受 quality 影响

**新增 `BaseNameQualityTest`**（覆盖 Q3 定稿）：

- [ ] `quality=999` → 无档位后缀，扩展名 `flac`
- [ ] `quality=320` → 后缀 ` (320)`，扩展名 `mp3`
- [ ] `quality=192` → 后缀 ` (192)`
- [ ] `quality=128` → 后缀 ` (128)`
- [ ] `quality=0` / 本地 / NAS / 网盘 → 无档位后缀
- [ ] `trackNumber=3` → `%02d - ` 前缀与档位后缀共存：`03 - 南方姑娘 (320)`
- [ ] 超长标题截断后档位后缀仍追加在末尾（`DownloadPathBuilder.MAX_SEGMENT=80`）
- [ ] 档位后缀与 `uniqueFile()` 的 ` (2)` 共存：`03 - 南方姑娘 (320) (2).mp3`

**新增 `DownloadKeyTest`**（覆盖 G3 + 方案 B 定稿）：

- [ ] `downloadKeyOf(999)` = `ntwk_meting_<id>:q999`（**下划线前缀，v1.3 更正点**）
- [ ] `downloadKeyOf(320)` = `ntwk_meting_<id>:q320`，与上条**不冲突**
- [ ] `downloadKeyOf(0)` = `ntwk_meting_<id>`，**与旧实现 `downloadKey` 返回值完全相等**（AUTO 档零迁移的关键断言）
- [ ] 非 Meting 源（Jamendo / 网盘）key **不含** `:q` 后缀，且与 `downloadKey` 相等
- [ ] 本地 / NAS 歌曲 key 不含 `:q` 后缀
- [ ] 存量格式 `ntwk_meting_<id>`（无后缀）与新格式 `ntwk_meting_<id>:q999` 字符串不等 → 主键 unique 不冲突
- [ ] `isDownloaded(song, 999)`：新格式命中 → true；未命中 → false
- [ ] `isDownloaded(song, 0)`：命中存量无后缀行 → true（**双格式兼容关键用例**）
- [ ] `downloadedQualitiesOf(songId)`：同曲两档 + 一条存量行 → 返回 3 条，走 `songId` 索引
- [ ] 断言调用方不自行拼 key：`downloadKeyOf` / `isDownloaded` / `downloadedQualitiesOf` 为唯一入口

**新增 `MigrationTest`**（覆盖 §4.2.4 零重建承诺，**必做**）：

- [ ] v1 建库插入 3 条存量行（含 `ntwk_meting_x` 无后缀 key、`quality` 列不存在）→ 跑 `MIGRATION_1_2` → 3 行仍在、`songKey` 未被改写
- [ ] 迁移后 `quality` 列全部为 `0`
- [ ] 迁移后 `index_download_songs_songId` 存在
- [ ] 迁移后能正常插入 `ntwk_meting_x:q320`（与存量行并存，不撞主键）
- [ ] `@Database(version = 2)` 与迁移注册一致（漏 `addMigrations` 时抛 `IllegalStateException`）

**新增 `AutoDownloadDedupeTest`**（覆盖 Q4 + Q6 定稿）：

- [ ] 已下载 `q128` → 自动下载 `q320` **仍应入队**（不被误判为已完成）
- [ ] 已下载 `q320` → 自动下载 `q320` → 跳过
- [ ] 已下载存量行（无 `:q` 后缀、quality=0）→ 自动下载 `q0`（全局默认为 AUTO）→ 跳过
- [ ] 断言去重判定**不读取** `dedupeKey`（同曲跨源仍可各自下载）
- [ ] 断言自动下载路径**不读取**单曲覆盖（§2.3.2）
- [ ] **主键冲突回归**：请求 `q999`、该曲仅 320 可用 → 落 `ntwk_meting_<id>:q320`；再次运行自动下载请求 `q999` → 降级到 `q320` → 命中**二次去重** → 跳过，**不抛主键冲突**（§4.5 必踩坑）

**新增 `DowngradePersistTest`**（覆盖 Q6 / Q8 定稿：静默降级落库链路）：

- [ ] 请求 `999`、实际 `320` → `entity.quality == 320`（不是 999）
- [ ] 同上 → `songKey == "ntwk_meting_<id>:q320"`（不是 `q999`）
- [ ] 同上 → 文件名 `03 - 南方姑娘 (320).mp3`（不是 `.flac`，不是 `(999)`）
- [ ] 同上 → `entity.status == COMPLETED`（**不是 FAILED**）
- [ ] 请求 `999`、全链失败 → 任务失败且 `errorMsg` 含"无法获取下载链接"（现状文案，`SongDownloadManager.kt:219`）
- [ ] 请求 `320`、实际 `320` → 无降级，行为与现状一致
- [ ] 播放路径降级 → 产生一次可见提示（`ResolveResult.actualQuality != requested`）
- [ ] 下载路径降级 → **不**产生错误提示，仅文件名体现
- [ ] 降级场景下**不残留孤儿 DOWNLOADING 行**（§4.4.2：验证建行发生在解析之后）

**新增 `QualityProbeTest`**（覆盖 Q7 定稿：可用码率探测与弹窗判定）：

- [ ] 4 档全部可解析 → 返回 `[999, 320, 192, 128]`，降序
- [ ] 仅 `320`/`128` 可解析 → 返回 `[320, 128]`，**不含** 999/192
- [ ] 仅 `128` 可解析 → 返回 `[128]`（单元素 → UI 不弹窗直下）
- [ ] 全部不可解析 → 返回空列表（UI 报错，不入队）
- [ ] 单档探测超时（>1200ms）→ 该档判定为不可用，不阻塞其他档
- [ ] **并发验证**：4 档探测总耗时 ≈ 单档耗时，而非 4 倍（用 `MockWebServer` 的 `Dispatcher` + 每档固定 delay 断言；串行实现会失败）
- [ ] 探测**不落盘、不写下载索引**（只读 URL 解析接口）
- [ ] 探测复用 `resolvePlayUrlDetailed`（打桩验证只走一个实现，§3.2 末注）
- [ ] ~~批量下载路径**不调用** `probeAvailableQualities()`~~ **【不适用：§5.2.2 已取消，无批量入口】**
- [ ] 判定矩阵：>1 → 弹窗；==1 → 直下；==0 → 报错

**新增 `LocalPlaybackPriorityTest`**（覆盖 §3.6，回归风险最高）：

- [ ] 已下载 320 + 请求 320 → 返回本地文件 URI，**不发网络请求**
- [ ] 已下载 320 + 请求 999 → **走网络解析**（不是直接播本地 320）
- [ ] 已下载 320 + 请求 999 且降级到 320 → 返回**本地文件 URI**（降级后命中已下载档，省流量）
- [ ] 已下载 320 + 请求 999 且网络解析彻底失败 → 返回本地 320 兜底（保证"能播就行"）
- [ ] 无任何本地文件 → 全部走网络解析
- [ ] `quality=0(AUTO)` + 存量行存在 → 返回本地文件（与现状行为一致）

**新增 `QualityOverridesTest`**（覆盖 D3 两级模型，Q1 定稿）：

- [ ] `tierOf()` 无覆盖 → 返回 null（调用方回退全局默认）
- [ ] `put()` 后 `tierOf()` 命中覆盖值
- [ ] `remove()` 后 `tierOf()` 返回 null
- [ ] `put()` 同键两次 → `updatedAt` 递增，`all()` 不产生重复键
- [ ] 写入 501 条 → 淘汰最旧 1 条，容量恒 ≤ 500（§2.3.1）
- [ ] `all()` 返回的 Map 键格式为 `networkSource:networkId`
- [ ] `clearAll()` 后 `all()` 为空
- [ ] 存储隔离：覆盖值写入独立 DataStore 文件，不污染主偏好文件
- [ ] 生效优先级：单曲覆盖存在时优先于全局默认；自动下载路径**不读取**覆盖值（§2.3.2）

**新增 `MetingResolveTest`**（MockWebServer 或 OkHttp Mock）：

- [ ] `quality=999` 请求 URL 含 `&br=999`
- [ ] `quality=192` 请求 URL 含 `&br=192`
- [ ] `quality=0` 请求 URL **不含** `br` 参数
- [ ] 999 返回空 → 降级到 320 → `ResolveResult(actualQuality=320)`
- [ ] 999 → 320 → 192 → 128 全空 → `url=null`，`actualQuality == 999`
- [ ] `quality=0(AUTO)` 命中 → `actualQuality == 0`（**AUTO 档不触发降级提示**）
- [ ] 端点 A 失败 → 端点 B 命中（现状回归）
- [ ] 302 响应取 `Location`；200 响应走 `extractUrlFromJson`（两种形态各一例）

### 10.2 Lint / 构建门禁

按 AGENTS.md：

```powershell
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat assembleDebug lintDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

- lint 现为 **blocking**（errors 必须为 0，warnings 允许）；
- **R8 注意**：新增 `QualityTiers` 是纯对象常量类，`proguard-rules.pro` 已 keep `data.model`、`data.prefs`；`backend/network` 已被 keep 规则覆盖，但**新文件 `backend/network/QualityTiers.kt` 若被 Gson 序列化会踩 v2.5.1 的老坑（Gson 类型擦除）——本方案不使用 Gson 序列化 QualityTiers，风险为无**。若未来需序列化，必须加 `-keep class com.nasmusic.tv.backend.network.QualityTiers { *; }`。

### 10.3 真机回归

> **状态：未执行**（v2.35.0 交付时）。本清单共 21 项，需在基准设备上人工走查。
> 其中**最高优先级**是「已下载歌曲切档」一项——§3.6 改变了既有播放行为
> （此前已下载歌曲永远播本地，现按档位查本地），且**无自动化测试覆盖**（见 §10.1）。

基准设备：创维 5.1.1 开发机（`adb connect 192.168.0.114:5555`）。

- [ ] 播放器切 128→320→999，实际音质变化（用频谱/文件大小验证）
- [ ] 切档位后 5 分钟内**不**复用旧直链（关键：Phase 1 验收点）
- [ ] **已下载歌曲切档**：已下载 320 的歌曲 → 切到无损 → **确实播放在线无损流**（不是仍播本地 320）；切回 320 → 恢复播本地文件（§3.6，**回归风险最高项**）
- [ ] 无损不可用曲 → 静默降级播放不中断，**出现降级提示**（toast/状态条），提示文案含请求档位与实际档位
- [ ] 播放连续切歌，中间某曲降级 → 播放不中断、不弹阻断弹窗，仅提示
- [ ] 单曲下载，多档可用 → 点击「下载」弹出码率选择面板（含全部可用档 + 当前选中项高亮）
- [ ] 单曲下载，仅 1 档可用 → 点击「下载」**不弹窗**，直接入队
- [ ] 单曲下载，0 档可用 → 点击「下载」提示错误，不入队
- [ ] 单曲下载探测耗时 → 从点击到弹窗 ≤ 2s（弱网下允许更久但不卡 UI）
- [ ] ~~批量下载**不弹**码率面板~~ **【不适用：§5.2.2 已取消，无批量入口】**
- [ ] 无损不可用曲下载 → 静默降级为可用码率，文件名含**实际**码率（如 `03 - 南方姑娘 (320).mp3`），非 `.flac`、非 `(999)`，状态显示成功（非失败）
- [ ] 降级下载再次触发 → 不重复下载、**不出现主键冲突**（§4.5 二次去重）
- [ ] 降级下载后检查下载列表**无残留"下载中"孤儿行**（§4.4.2）
- [ ] ~~批量下载中部分曲目降级 → 批量结果提示「其中 N 首为降级码率」~~ **【不适用：§5.2.2 已取消】**
- [ ] 下载无损 → 落 `.flac` 且可正常播放
- [ ] 下载 320 → 落 `03 - 南方姑娘 (320).mp3`，与同曲无损 `.flac` 并存不冲突
- [ ] **同曲三档并存**：无损 + 320 + 128 三个文件同时存在，下载列表显示三行且档位徽标各不相同
- [ ] **AUTO 档不重复下载**：全局默认设为「自动」，对已下载（存量行）的歌曲再次触发下载 → 判定为已下载，**不重复下载**
- [ ] 单曲覆盖：面板选「仅本次播放」→ 仅该曲生效，切歌后回退全局默认
- [ ] 单曲覆盖：设置页「清除全部单曲覆盖」→ 覆盖条目清空且不再回退
- [ ] **存量数据升级**：升级前已有若干下载（无 `:q` 后缀）→ 升级后旧行仍正常显示/播放，且**不被重复下载**
- [ ] 设置页 5 档焦点可达、方向键循环、BACK 退出
- [ ] ~~下载面板批量档位切换 → 队列中未开始任务按新档位解析~~ **【不适用：§5.2.2 已取消】**
- [ ] 非网络源（NAS/本地/网盘）不显示码率控件
- [ ] 遥控焦点走查：所有新增交互（含码率选择面板）无需鼠标
- [ ] **手机端**：竖屏下音质面板与下载码率面板布局不错乱、可触控

---

## 11. 附录

### 11.1 `br` 契约速查

| 参数 | 值 | 含义 | Meting | GD (D-Music) |
|---|---|---|---|---|
| 标准 | `128` | 128 kbps MP3 | ✅ | ✅ |
| 高品 | `192` | 192 kbps MP3 | ✅ | ✅ |
| 极高 | `320` | 320 kbps MP3 | ✅ | ✅ |
| 无损 | `999` | FLAC（约定标识符，非真实码率） | ✅ | ✅ |
| 自动 | `0` | 端点默认（**不保证等于请求档**） | ✅ | — |

两个 API 的 `br` 语义一致，因此 `QualityTiers` 可以无差异复用于未来接入 GD 源（`NetworkMusicService` 新增实现即可）。

**降级语义（v1.2 定稿，播放与下载一致）**：

| 项 | 行为 |
|---|---|
| 请求档不可用 | 静默降级到可用的最高档，**不报错、不阻断** |
| 播放反馈 | 出现一次性提示（toast/状态条），文案含请求档与实际档 |
| 下载反馈 | **不提示错误**；实际档位体现在文件名（`03 - 南方姑娘 (320).mp3`） |
| 落库档位 | 记录**实际**档位（`entity.quality` / `songKey` 后缀），不是请求档 |
| 任务状态 | 降级成功仍为 `COMPLETED`；只有全链失败才 `FAILED` |
| 文件扩展名 | 随实际档位（无损→`.flac`，其余→`.mp3`），不由请求档决定 |

**已核实的音源限制**：GD 音乐台 API（`https://music-api.gdstudio.xyz/api.php`，见 `D-Music-main/.../data/MusicApi.kt`）**只有 4 个端点：`search` / `url` / `lyric` / `pic`，不存在"列出可用码率"的接口**。因此本方案的可用档位判断必须走 §5.2.1 的**逐档探测**（`QualityProbe`），不能指望服务端一次性返回清单。探测仅决定 UI 形态（弹窗/直下/报错），**不决定下载正确性**——真实下载仍走完整 fallback 链，因此探测超时最多导致"弹窗少列一档"，不会导致下载失败。

### 11.2 参考实现索引（D-Music）

| 关注点 | 文件:行 |
|---|---|
| 音源契约 | `data/MusicApi.kt:22-27` |
| `resolveUrl(song, br)` | `data/MusicApi.kt:72-85` |
| URL 拼接 | `data/MusicApi.kt:121-131` |
| 码率定义 `Qualities` | `data/Song.kt:66-75` |
| 下载任务 ID 含 quality | `data/DownloadManager.kt:37-48` |
| 并发下载 3 路 | `data/DownloadManager.kt:70` |
| 缓存 key `source:id:br` | `player/PlaybackCache.kt:17, 87` |

### 11.3 现状索引（NASMusicTV）

> 行号于 2026-09-18 对照源码核验。

| 关注点 | 文件:行 |
|---|---|
| 档位常量（缺 192） | `data/prefs/AppPreferences.kt:74-77` |
| 档位镜像缓存 | `data/prefs/AppPreferences.kt:125, 166-169` |
| 偏好持久化 | `data/prefs/AppPreferences.kt:892, 906-919` |
| DI 注入 provider | `NasMusicApp.kt:283` |
| 下载解析器接线 | `NasMusicApp.kt:339-343` |
| 请求带 `br` + 降级链 | `backend/network/MetingApiService.kt:231-237` |
| 播放缓存（**无 br**） | `backend/network/NetworkMusicManager.kt:29-31, 54, 106` |
| 服务接口签名 | `backend/network/NetworkMusicService.kt:55` |
| 网络歌曲 ID 格式 `ntwk_<source>_<id>` | `backend/network/MetingApiService.kt:409` |
| 下载入口（**无 quality**） | `backend/download/SongDownloadManager.kt:117` |
| 下载循环取 key（**解析前**） | `backend/download/SongDownloadManager.kt:147, 215, 234` |
| 解析失败文案 | `backend/download/SongDownloadManager.kt:218-219` |
| 下载索引（**无 quality**） | `backend/download/db/DownloadSongEntity.kt:19-56` |
| DB 版本（**当前 = 1**） | `backend/download/db/DownloadDatabase.kt:28` |
| **下载主键真实格式（下划线）** | `backend/download/model/DownloadKeys.kt:21-26` |
| 跨源去重键 | `backend/download/model/DownloadKeys.kt:34-36` |
| `newDownloadingEntity` 拼 key | `backend/download/DownloadRepository.kt:186-203` |
| 按 dedupe 查已完成 | `backend/download/DownloadRepository.kt:42-43` |
| 自动下载去重入口 | `backend/download/AutoDownloadController.kt:65` |
| 扩展名推断（**靠 URL**） | `backend/download/DownloadPathBuilder.kt:97-108` |
| `uniqueFile()` 去重后缀 | `backend/download/DownloadPathBuilder.kt:70-78` |
| 直链解析 | `backend/download/StreamUrlResolver.kt:32-46` |
| 已下载本地优先（**切档冲突点**） | `ui/viewmodel/PlayerViewModel.kt:228-230, 301-307` |
| 设置页档位按钮（**硬编码，缺 192**） | `ui/screens/settings/PlayerSettingsSection.kt:120-131` |
| 端点 fallback | `backend/network/MetingApiService.kt:174-185` |

### 11.4 与既有文档的关系

- `docs/network-music-upgrade-plan.md`：F2-6 音质档位的原始需求来源，本方案是其**收尾与扩展**；
- `docs/歌曲离线下载与本地存储管理开发方案.md`：下载链路（`DownloadPathBuilder`/`StreamUrlResolver`/Room 索引）的既有设计，§4 在其基础上追加 quality 维度；
- `docs/technical-overview.md`：技术总览，本方案落地后需同步更新"网络音乐"章节的码率说明。
