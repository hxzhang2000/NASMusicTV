# NASMusicTV 音乐元数据搜索服务开发方案

> 基于「接口探索.md」的 API 调研成果，为 NASMusicTV 设计可配置、可回退的元数据搜索服务

**版本**: v1.0 · 最后更新: 2026-07-12

---

## 一、需求概述

### 1.1 背景

NASMusicTV 当前已有 `NetworkMusicManager` + `MetingApiService` 的网络音乐搜索架构，但该架构**仅针对"播放"场景**（网络音乐搜索→播放），缺少一个**独立的元数据补全服务**用于：

- 本地 NAS 音乐文件元数据补全（专辑名、发行年份、流派、封面）
- 歌曲详情展示（从文件名提取的歌名/歌手 → 查询补充完整元数据）
- 封面获取（本地文件缺封面时自动从网络获取）

### 1.2 核心需求

| 需求 | 说明 |
|:----|:------|
| **可配置** | 搜索 API 的启用顺序、启用/禁用可通过设置界面配置 |
| **优先级顺序** | 可配置各 API 的搜索优先级（如 iTunes 优先 → MusicBrainz 降级） |
| **失败回退** | 当前 API 搜索无结果或异常时自动降级到下一个 API |
| **支持中文** | 优先考虑中文歌曲覆盖好的 API |
| **完全免费** | 所有底层 API 必须免费可用，无需付费或商业授权 |
| **异步非阻塞** | 搜索操作在协程中执行，不阻塞 UI |

### 1.3 与现有架构的关系

```
现有架构:
  NetworkMusicManager ─→ MetingApiService（用于网络播放场景）

新增架构:
  MetadataSearchService ─→ iTunesSearchApi
                        ├→ MusicBrainzApi + CoverArtArchiveApi
                        ├→ MetingApi（可选，中文补充）
                        └→ ...（可扩展）
```

两者**职责分离**：
- `NetworkMusicManager` → 处理网络歌曲的**搜索+播放**（已有）
- `MetadataSearchService` → 处理**元数据补全**（本方案新增）

---

## 二、API 选型分析

### 2.1 最终选型

基于「接口探索.md」的验证结果，按优先级排序：

| 优先级 | API | 中文曲库 | 封面 | 元数据 | 稳定性 | 是否需 Key |
|:-----:|:----|:--------:|:----:|:------:|:------:|:----------:|
| 1 | **Apple iTunes Search API** | ★★★★★ | 高清 1200x1200 | 专辑/年份/流派 | ★★★★★ | 否 |
| 2 | **MusicBrainz + Cover Art Archive** | ★★★ | 通过 CAA 获取 | ISRC/发行信息/多版本 | ★★★★★ | 否（仅 UA） |
| 3 | **Meting-API**（可选） | ★★★★★ | 高清 | 专辑/歌词 | ★★☆ | 否 |

> iTunes 实测返回华语歌曲覆盖极好（测试"稻香 周杰伦"返回专辑名《魔杰座》、2008-10-14、高清封面）。
> MusicBrainz 实测搜索"稻香 周杰伦"返回 54 条录音记录。
> TheAudioDB、Deezer、Discogs 中文曲库偏弱，不作为主要数据源，但可作为封面补充渠道保留。

### 2.2 不纳入主搜的原因

| API | 排除原因 |
|:----|:---------|
| TheAudioDB | 中文曲库弱，搜索"稻香 周杰伦"返回空 |
| Discogs | 侧重实体唱片，中文搜索效果差 |
| Deezer 公共搜索 | 中文曲库弱，会返回大量无关结果 |
| FreeAPI / injahow | 逆向接口，不稳定且不合规，只做备选 |

---

## 三、架构设计

### 3.1 模块结构

```kotlin
// 包路径: com.nasmusic.tv.metadata

metadata/
├── MetadataSearchService.kt      // 门面类：对外提供统一搜索接口
├── MetadataProvider.kt           // 接口：所有元数据提供者的抽象
├── MusicMetadata.kt              // 数据模型：统一的元数据结果
├── MetadataSearchConfig.kt       // 配置：API 优先级顺序、启用状态
├── provider/
│   ├── iTunesSearchProvider.kt   // iTunes Search API 实现
│   ├── MusicBrainzProvider.kt    // MusicBrainz + Cover Art Archive 实现
│   └── MetingMetadataProvider.kt // Meting-API 元数据补充实现（可选）
└── resolver/
    └── CoverUrlResolver.kt       // 封面 URL 尺寸转换工具
```

### 3.2 MetadataProvider 接口定义

```kotlin
/**
 * 音乐元数据提供者接口
 *
 * 所有提供者实现此接口，由 MetadataSearchService 按优先级调度。
 * 设计为单方法接口，每个 provider 只关注"搜索元数据"这一件事。
 */
interface MetadataProvider {
    /** 提供者唯一标识，如 "itunes" / "musicbrainz" / "meting" */
    val providerId: String

    /** 提供者名称（用于 UI 显示） */
    val displayName: String

    /**
     * 根据歌名和歌手搜索元数据。
     *
     * @param title 歌曲标题
     * @param artist 歌手名
     * @return 搜索到的元数据；null 表示未找到或失败
     */
    suspend fun search(title: String, artist: String): MusicMetadata?
}
```

### 3.3 MusicMetadata 数据模型

```kotlin
/**
 * 统一元数据搜索结果。
 * 各 provider 返回的数据映射为此模型，上层统一使用。
 */
data class MusicMetadata(
    /** 歌曲标题 */
    val title: String,
    /** 歌手名 */
    val artist: String,
    /** 专辑名 */
    val album: String = "",
    /** 封面 URL（尽可能大尺寸的直链） */
    val coverUrl: String? = null,
    /** 发行年份 */
    val releaseYear: Int? = null,
    /** 发行日期（完整 ISO 日期） */
    val releaseDate: String? = null,
    /** 流派 */
    val genre: String? = null,
    /** 曲目编号 */
    val trackNumber: Int = 0,
    /** 专辑总曲目数 */
    val trackCount: Int = 0,
    /** 数据来源 provider ID */
    val source: String,
    /** 封面来源说明（用于 UI 调试/显示） */
    val coverSource: String? = null
)
```

### 3.4 MetadataSearchConfig 配置模型

```kotlin
/**
 * 元数据搜索配置。
 * 存储在 AppPreferences 中，支持用户在设置页面调整。
 */
data class MetadataSearchConfig(
    /**
     * 启用的 provider ID 列表（优先级顺序）。
     * 第一个 provider 优先搜索，失败后按列表顺序降级。
     * 默认：["itunes", "musicbrainz"]
     */
    val providerOrder: List<String> = listOf("itunes", "musicbrainz"),

    /** 是否启用 MusicBrainz 自动降级（默认启用） */
    val enableMusicBrainzFallback: Boolean = true,

    /** 是否启用 Meting-API 作为封面补充源（默认禁用） */
    val enableMetingCoverFallback: Boolean = false
)
```

### 3.5 MetadataSearchService 门面类

```kotlin
/**
 * 元数据搜索服务门面。
 *
 * 职责：
 * 1. 按配置的优先级顺序依次调用各 provider
 * 2. 当前 provider 失败时自动降级到下一个
 * 3. 日志记录各 provider 的命中/失败情况
 * 4. 可选的封面二次解析（如 iTunes 的小图 → 大图）
 */
class MetadataSearchService(
    private val providers: Map<String, MetadataProvider>,
    private val configProvider: () -> MetadataSearchConfig
) {
    /**
     * 搜索音乐元数据。
     * 按配置的优先级顺序尝试，成功即返回，失败则降级。
     */
    suspend fun search(title: String, artist: String): MusicMetadata? {
        val config = configProvider()
        for (providerId in config.providerOrder) {
            val provider = providers[providerId] ?: continue
            try {
                val result = provider.search(title, artist)
                if (result != null) {
                    logHit(providerId, title, artist)
                    return result
                }
                logMiss(providerId, title, artist)
            } catch (e: Exception) {
                logError(providerId, title, artist, e)
            }
        }
        return null
    }
}
```

---

## 四、各 Provider 实现方案

### 4.1 iTunesSearchProvider

**接口端点**:
```http
GET https://itunes.apple.com/search?term={歌曲名}+{歌手名}&media=music&country=cn&limit=5
```

**实现要点**:
- 关键词拼接格式：`{title}+{artist}`，URL 编码
- 指定 `country=cn` 获取华语曲库
- 解析返回的 JSON，取第一个 track 数据
- 封面 URL 尺寸转换：`100x100bb` → `600x600bb`
- 返回字段映射：

| iTunes 字段 | MusicMetadata 字段 |
|:-----------|:------------------|
| `trackName` | `title` |
| `artistName` | `artist` |
| `collectionName` | `album` |
| `releaseDate` | `releaseDate` |
| `artworkUrl100` | `coverUrl`（替换尺寸） |
| `primaryGenreName` | `genre` |
| `trackNumber` | `trackNumber` |
| `trackCount` | `trackCount` |

**封面尺寸转换逻辑**:
```kotlin
// iTunes 封面 URL 示例：
// https://is1-ssl.mzstatic.com/image/thumb/.../100x100bb.jpg
// 替换 100x100bb → 600x600bb 或 1200x1200bb 获取大图
private fun resolveCoverUrl(artworkUrl100: String?): String? {
    if (artworkUrl100 == null) return null
    return artworkUrl100.replace("100x100bb", "600x600bb")
}
```

### 4.2 MusicBrainzProvider + Cover Art Archive

**接口端点**:
```http
# 搜索录音（返回关联 release MBID）
GET https://musicbrainz.org/ws/2/recording?query=artist:{歌手} AND recording:{歌名}&fmt=json

# 用 release MBID 获取封面
GET https://coverartarchive.org/release/{release_mbid}/front
```

**实现要点**:
- 必须设置 `User-Agent` 头
- 严格限流 1 req/s（需实现请求节流）
- Lucene 查询语法：`AND` 连接多个条件
- 从 recording 结果中提取 release MBID
- 用 release MBID 请求 Cover Art Archive 获取封面

**限流控制**:
```kotlin
private val rateLimiter = RateLimiter(1.0) // 1 request per second

suspend fun search(title: String, artist: String): MusicMetadata? {
    rateLimiter.acquire() // 等待限流许可
    // ... 发起请求
}
```

### 4.3 MetingMetadataProvider（可选补充源）

**接口端点**:
```http
GET {meting_url}?server=netease&type=search&id={关键词}
```

**功能限定**: 仅用作封面补充源，不作为主搜。
- 当 iTunes 和 MusicBrainz 都搜不到歌曲时，可尝试用 Meting-API 搜索封面 URL
- 搜索结果中的 `pic` 字段可直接作为封面（Coil 支持 302 跟随）

---

## 五、配置与设置界面

### 5.1 AppPreferences 新增字段

```kotlin
// --- 元数据搜索设置 ---
private val keyMetadataProviderOrder = stringPreferencesKey("metadata_provider_order")
private val keyMetadataEnableMusicBrainz = booleanPreferencesKey("metadata_enable_musicbrainz")
private val keyMetadataEnableMetingCover = booleanPreferencesKey("metadata_enable_meting_cover")
```

### 5.2 设置界面布局

```
【音乐元数据搜索】 ← 新设置区块
  ┌─────────────────────────────────┐
  │ 搜索 API 优先级顺序              │
  │ ┌─ ① iTunes Search API ──────┐ │
  │ │ 状态: 已启用                  │ │
  │ │ 搜索优先级: 1（最高）          │ │
  │ └─────────────────────────────┘ │
  │ ┌─ ② MusicBrainz ────────────┐ │
  │ │ 状态: 已启用 ☑               │ │
  │ │ 搜索优先级: 2（降级备选）      │ │
  │ └─────────────────────────────┘ │
  │ ┌─ ③ Meting-API（封面补充）──┐ │
  │ │ 状态: 未启用 ☐               │ │
  │ └─────────────────────────────┘ │
  │                                  │
  │ [重置为默认优先级]               │
  └─────────────────────────────────┘
```

### 5.3 默认配置

```kotlin
val DEFAULT_METADATA_CONFIG = MetadataSearchConfig(
    providerOrder = listOf("itunes", "musicbrainz"),
    enableMusicBrainzFallback = true,
    enableMetingCoverFallback = false
)
```

---

## 六、与现有系统的集成

### 6.1 初始化流程

```kotlin
// NasMusicApp.onCreate() 中注册
val metadataProviders: Map<String, MetadataProvider> = mapOf(
    "itunes" to iTunesSearchProvider(),
    "musicbrainz" to MusicBrainzProvider()
)

val metadataSearchService = MetadataSearchService(
    providers = metadataProviders,
    configProvider = { appPreferences.getMetadataSearchConfig() }
)
```

### 6.2 调用场景

**场景 1：歌曲详情页面展示**
```kotlin
// MainViewModel / 详情页中
val metadata = metadataSearchService.search(song.title, song.artist)
// metadata?.album -> 补充专辑名
// metadata?.coverUrl -> 补充封面
// metadata?.releaseYear -> 补充年份
```

**场景 2：自动补全本地音乐文件元数据**
```kotlin
// 后台扫描时，对缺失元数据的文件：
val metadata = metadataSearchService.search(title, artist)
if (metadata != null) {
    // 写入 ID3 标签：专辑名、年份、流派
    // 下载封面到本地缓存
}
```

**场景 3：封面轮播增强**
```kotlin
// NowPlayingScreen 的封面轮播中，增加网络来源的封面候选：
launch {
    val metadata = metadataSearchService.search(song.title, song.artist)
    metadata?.coverUrl?.let { addToCoverCarousel(it) }
}
```

### 6.3 与现有 LyricsManager 的关系

现有 `LyricsManager` 中已有一个 `LyricsNetworkProvider`（搜索网络歌词），`MetadataSearchService` 与此不重叠。歌词搜索走现有路径，元数据搜索走新路径。

---

## 七、错误处理与限流

### 7.1 错误处理策略

| 错误类型 | 处理方式 |
|:---------|:---------|
| HTTP 超时 | 跳过当前 provider，降级到下一个 |
| HTTP 4xx/5xx | 跳过，降级 |
| JSON 解析失败 | 跳过，降级 |
| 搜索结果为空 | 跳过，降级 |
| 所有 provider 都失败 | 返回 null，调用方自行处理 |

### 7.2 限流方案

| Provider | 限流要求 | 实现方式 |
|:---------|:---------|:---------|
| iTunes Search | 无官方限流 | 不做硬限流，2 req/s 内的合理控制 |
| MusicBrainz | 严格 1 req/s | 使用 RateLimiter（令牌桶），超限返回 null |
| Cover Art Archive | 有速率限制 | 跟随 MusicBrainz 限流 |

### 7.3 缓存策略

| 缓存内容 | TTL | 存储 |
|:---------|:---:|:----|
| 搜索结果（轻量元数据） | 24h | 内存 LRU Cache（128 条） |
| 封面 URL | 7 天 | 内存 LRU Cache（256 条） |
| 封面图片文件 | 永久 | 磁盘缓存（应用缓存目录） |

---

## 八、实现计划（roadmap）

| 阶段 | 内容 | 预估工作量 |
|:---:|:-----|:----------:|
| **Phase 1** | 基础数据结构：`MusicMetadata`、`MetadataProvider` 接口、`MetadataSearchConfig` | 1 天 |
| **Phase 2** | `iTunesSearchProvider` 实现 + 单元测试 | 1 天 |
| **Phase 3** | `MusicBrainzProvider` + Cover Art Archive 实现 + 单元测试 | 2 天 |
| **Phase 4** | `MetadataSearchService` 门面 + 降级路由 + 日志 | 1 天 |
| **Phase 5** | 设置界面集成（AppPreferences 新增配置 + UI 界面） | 1 天 |
| **Phase 6** | 集成到歌曲详情页 / 封面轮播 / 本地文件扫描 | 2 天 |
| **Phase 7** | 缓存策略实现 + 限流控制 | 1 天 |
| **Phase 8** | `MetingMetadataProvider`（可选补充源）+ 端到端测试 | 1 天 |

**总计**: 约 10 个工作日

---

## 九、变更日志

| 版本 | 日期 | 变更内容 |
|:----:|:----:|:---------|
| v1.0 | 2026-07-12 | 初版：基于「接口探索.md」和「IDEA.md」需求，完成 MetadataSearchService 完整方案设计 |
