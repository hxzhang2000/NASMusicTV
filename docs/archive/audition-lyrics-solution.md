# NASMusicTV 播放试听与歌词编辑页面开发方案

> 支持歌曲30秒试听预览、同步滚动歌词显示、歌词时间轴拖拽调整

**版本**: v1.0 · 最后更新: 2026-07-12

---

## 一、需求概述

### 1.1 背景

NASMusicTV 现有 `NowPlayingScreen` 已支持歌词滚动显示，但缺少以下功能：

1. **独立的播放试听页面** — 从元数据搜索结果进入，支持歌曲 30 秒试听预览
2. **滚动歌词显示** — 带有时间同步的滚动歌词（已有基础，需强化）
3. **歌词时间拖拽调整** — 当歌词时间不同步时，用户可拖动歌词行调整时间戳

### 1.2 核心需求

| 需求 | 说明 |
|:-----|:------|
| **试听预览** | 点击元数据搜索结果中的歌曲，可播放 30 秒 iTunes Preview |
| **同步滚动歌词** | 使用 LRCLIB API 获取同步 LRC 歌词，随播放进度自动滚动高亮 |
| **时间轴拖拽** | 歌词行时间不对时，用户可拖拽��整行的时间戳，支持批量微调 |
| **歌词离线缓存** | 已获取的歌词缓存到本地，避免重复请求 |
| **多歌词版本** | 同一歌曲可能有多个歌词版本，用户可选择切换 |
| **音量/播放控制** | 试听页面的基础播放控制（播放/暂停、进度条） |

### 1.3 与现有 NowPlayingScreen 的关系

```
NowPlayingScreen（现有）
    完整播放器界面，用于播放队列中的歌曲
    已有 LyricsView 组件（逐行高亮）
    已支持歌词加载（LyricsManager）

AuditionScreen（新增）
    轻量试听页面，从元数据搜索结果进入
    iTunes 30秒 Preview 试听
    强化歌词编辑：拖拽调整时间
    可保存调整后的歌词到离线缓存
```

两者**互补不冲突**：
- `NowPlayingScreen` → 全功能播放（NAS/网络歌曲）
- `AuditionScreen` → 轻量试听 + 歌词编辑调整

---

## 二、页面流程与交互设计

### 2.1 用户操作流程

```
元数据搜索结果列表
        │
        ├── 点击"试听"按钮 ──→ AuditionScreen
        │                        │
        │                        ├── 播放 iTunes 30s Preview（或完整歌曲）
        │                        ├── 歌词自动滚动（同步播放进度）
        │                        ├── 用户发现歌词时间不同步
        │                        │       │
        │                        │       └── 拖拽歌词行 ↔ 调整时间戳
        │                        │               │
        │                        │               └── 自动保存调整到本地缓存
        │                        │
        │                        └── 点击关闭 → 返回元数据搜索结果
        │
        └── 点击"播放"按钮 ──→ 加入播放队列（现有流程）
```

### 2.2 页面布局设计

```
┌─────────────────────────────────────────────┐
│ ← 返回             歌曲标题              [×] │  ← 顶部栏
├─────────────────────────────────────────────┤
│                                             │
│       ┌─────────────────────────┐           │
│       │   专辑封面（较大）        │           │  ← 封面区域
│       │                         │           │
│       └─────────────────────────┘           │
│                                             │
│  歌曲名 — 歌手名                             │  ← 歌曲信息
│  《专辑名》 · 发行年份 · 流派                 │
│                                             │
│  ═══════●══════════════════╪═══════  00:23   │  ← 播放进度条
│  ┌─────┐                                   │
│  │ ▶/⏸ │                                   │  ← 播放控制按钮
│  └─────┘                                   │
│                                             │
│  ┌─歌词（滚动区域）─────────────────────┐    │
│  │ [00:31] 在这个世界如果你有太多的抱怨    │    │  ← 歌词区域
│  │ [00:34] 跌倒了就不敢继续往前走          │    │  （已过去：白字+大字号）
│  │ [00:37] 为什么人要这么的脆弱堕落        │    │  （当前行：高亮+加粗+放大）
│  │ [00:41] 请你打开电视看看                │    │  （未到：灰字+小字号）
│  │ [00:42] 多少人为生命在努力勇敢的走下去  │    │
│  │       ↕ 可拖拽调整时间                   │    │
│  └────────────────────────────────────┘    │
│                                             │
│  提示：歌词时间不准？长按歌词条拖动调整       │  ← 底部提示
├─────────────────────────────────────────────┤
│  歌词来源: LRCLIB  |  已缓存  |  版本 1/2   │  ← 底部状态栏
└─────────────────────────────────────────────┘
```

### 2.3 歌词时间调整交互

| 操作 | 效果 | 视觉反馈 |
|:-----|:-----|:---------|
| **长按歌词行** | 进入编辑模式 | 歌词行出现蓝色边框 + 时间戳数字变为可编辑 |
| **上下拖动歌词行时间戳** | 微调该行时间（±1秒/格） | 时间戳数字实时变化，半透明预览 |
| **点击歌词行** | 跳转到该时间点播放 | 播放进度跳到该行对应时间 |
| **左滑/右滑歌词区域** | 批量偏移所有歌词时间 | 显示偏移量浮层 "+2.0s" |
| **编辑模式中点击"重置"** | 恢复原始歌词时间 | 瞬移回原始时间 |

---

## 三、技术架构设计

### 3.1 新增模块

```
com.nasmusic.tv.audition/
├── AuditionScreen.kt            // 试听页面 Composable
├── AuditionViewModel.kt         // 试听页面 ViewModel
├── LyricEditController.kt       // 歌词编辑控制器（拖拽调整逻辑）
│
com.nasmusic.tv.audio/
├── PreviewPlayer.kt             // 轻量播放器（iTunes Preview + 完整音频）
│
com.nasmusic.tv.lyrics/
├── LrcTimeAdjuster.kt           // 歌词时间调整器（偏移/单行调整）
├── LrcCache.kt                  // 歌词本地持久化缓存
│
# 以下由 LyricsManager 已有或增强：
#   LyricEditController.kt     ← 新增包装拖拽逻辑
#   LyricsView.kt              ← 增强（编辑模式、拖拽交互）
```

### 3.2 AuditionViewModel

```kotlin
/**
 * 试听页面状态
 */
data class AuditionUiState(
    val song: Song? = null,                    // 当前试听歌曲
    val isPlaying: Boolean = false,             // 是否播放中
    val progress: Long = 0L,                    // 播放进度 (ms)
    val duration: Long = 30_000L,               // 试听时长 (默认30s)
    val lyrics: List<LyricsLine> = emptyList(),  // 歌词行列表
    val currentLyricIndex: Int = -1,            // 当前高亮歌词行索引
    val isEditMode: Boolean = false,            // 歌词编辑模式
    val editedLyrics: List<LyricsLine>? = null, // 用户编辑后的歌词
    val timeOffset: Long = 0L,                  // 全局偏移 (ms)
    val isLoadingLyrics: Boolean = false         // 歌词加载中
)

/**
 * 试听页面 ViewModel
 */
class AuditionViewModel(
    private val previewPlayer: PreviewPlayer,
    private val lyricsManager: LyricsManager,
    private val lrcCache: LrcCache
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuditionUiState())
    val uiState: StateFlow<AuditionUiState> = _uiState.asStateFlow()

    /**
     * 初始化试听（加载歌曲 + 获取歌词）
     */
    fun startAudition(song: Song) {
        // 1. 设置歌曲信息
        // 2. 获取歌词（优先 LRCLIB，降级到现有 LyricsManager）
        // 3. 开始播放 Preview
        // 4. 启动进度监听
    }

    /**
     * 切换播放/暂停
     */
    fun togglePlayPause()

    /**
     * 跳转到指定时间
     */
    fun seekTo(timeMs: Long)

    /**
     * 切换歌词编辑模式
     */
    fun toggleEditMode()

    /**
     * 调整单行歌词时间（拖拽）
     */
    fun adjustSingleLine(lineIndex: Int, newTimeMs: Long)

    /**
     * 批量偏移所有歌词时间（滑动）
     */
    fun offsetAllLyrics(offsetMs: Long)

    /**
     * 撤销编辑，恢复原始歌词
     */
    fun resetLyrics()

    /**
     * 保存编辑后的歌词
     */
    fun saveLyrics()
}
```

### 3.3 PreviewPlayer — 轻量试听播放器

**特点**: 与 `PlayerManager` 分离，独立管理试听音频，不干扰播放队列。

```kotlin
/**
 * 试听预览播放器。
 *
 * 职责：
 * 1. 播放 iTunes 30 秒 Preview URL
 * 2. 提供播放状态、进度、时长
 * 3. 支持跳转（seekTo）
 * 4. 播放结束后自动停止
 */
class PreviewPlayer(context: Context) {

    private val exoPlayer: ExoPlayer

    /**
     * 播放音频 URL
     * @param url 音频流 URL（如 iTunes previewUrl）
     * @param onProgress 进度回调 (ms)
     * @param onCompletion 播放完成回调
     */
    fun play(url: String, onProgress: (Long) -> Unit, onCompletion: () -> Unit)

    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()
    fun isPlaying(): Boolean
    fun getCurrentPosition(): Long

    /**
     * 释放资源
     */
    fun release()
}
```

### 3.4 LrcTimeAdjuster — 歌词时间调整器

```kotlin
/**
 * 歌词时间调整器。
 *
 * 职责：
 * 1. 单行时间戳精确调整（拖拽）
 * 2. 全局批量偏移（滑动）
 * 3. 验证时间戳合法性（单调递增检查）
 * 4. 生成调整后的歌词副本
 */
class LrcTimeAdjuster {

    /**
     * 调整单行歌词的时间戳。
     *
     * @param lyrics 原始歌词（不可变副本）
     * @param lineIndex 要调整的行索引
     * @param newTimeMs 新的时间戳（毫秒）
     * @return 调整后的歌词列表
     */
    fun adjustLineTime(lyrics: List<LyricsLine>, lineIndex: Int, newTimeMs: Long): List<LyricsLine>

    /**
     * 批量偏移所有歌词时间。
     *
     * @param lyrics 原始歌词
     * @param offsetMs 偏移量（正数=延迟，负数=提前）
     * @return 偏移后的歌词列表
     */
    fun offsetAll(lyrics: List<LyricsLine>, offsetMs: Long): List<LyricsLine>

    /**
     * 根据行索引找到最合适的歌词行。
     *
     * @param lyrics 歌词列表
     * @param progressMs 当前播放进度
     * @return 当前应高亮的歌词行索引（-1 表示无匹配）
     */
    fun findCurrentLine(lyrics: List<LyricsLine>, progressMs: Long): Int
}
```

### 3.5 LrcCache — 歌词本地缓存

```kotlin
/**
 * 歌词本地持久化缓存。
 *
 * 通过 songId 或 (title+artist) 哈希作为键，
 * 缓存已获取的原始 LRC 文本和用户编辑后的版本。
 *
 * 存储位置: app 内部 files 目录下的 lyrics_cache/
 * 格式: JSON 文件 + LRC 原始文件
 */
class LrcCache(private val context: Context) {

    private val cacheDir: File
        get() = File(context.filesDir, "lyrics_cache").also { it.mkdirs() }

    /**
     * 保存歌词到本地缓存（自动检测是否有编辑版本要保存）
     */
    fun saveLyrics(songId: String, lines: List<LyricsLine>)

    /**
     * 从本地缓存读取歌词
     * @return null 表示缓存未命中
     */
    fun loadLyrics(songId: String): List<LyricsLine>?

    /**
     * 检查歌词是否存在且有效
     */
    fun hasCachedLyrics(songId: String): Boolean

    /**
     * 保存用户编辑后的版本（区别于原始版本）
     */
    fun saveEditedVersion(songId: String, lines: List<LyricsLine>)

    /**
     * 读取用户编辑版本
     */
    fun loadEditedVersion(songId: String): List<LyricsLine>?

    /**
     * 清除过期缓存（7天以上）
     */
    fun clearExpired()
}
```

---

## 四、歌词获取流程

### 4.1 歌词获取优先级

```
1. 本地缓存检查
   ├─ 有编辑版本 → ���用编辑版本（用户自定义时间）
   ├─ 有原始版本 → 使用原始版本
   └─ 无缓存 → 继续获取

2. LRCLIB API（新方案，首选）
   ├─ search: GET /api/search?track_name={title}&artist_name={artist}
   │  └─ 返回匹配列表，取第一个有 syncedLyrics 的结果
   └─ get: GET /api/get/{id}
      └─ 获取完整 syncedLyrics

3. 现有 LyricsManager 降级
   └─ 通过 MetingApiService 获取歌词（网易云等）
      └─ 解析为 LyricsLine 列表

4. 全部失败 → 显示"暂无歌词"
```

### 4.2 LRCLIB 集成

```kotlin
/**
 * LRCLIB 歌词提供者。
 *
 * 验证结论 (2026-07-12):
 * - GET /api/search?track_name=稻香&artist_name=周杰伦 → 20条结果
 * - GET /api/get?artist_name=周杰伦&track_name=稻香&album_name=魔杰座&duration=243 → 54行同步歌词
 * - 完全免费，无限流，无API Key
 */

suspend fun search(title: String, artist: String): LrcResult? {
    val encodedTitle = URLEncoder.encode(title, "UTF-8")
    val encodedArtist = URLEncoder.encode(artist, "UTF-8")

    // 1. 搜索匹配
    val searchUrl = "https://lrclib.net/api/search?track_name=$encodedTitle&artist_name=$encodedArtist"
    val searchResponse = httpClient.get(searchUrl)
    val results = searchResponse.parseJson<List<LrcSearchResult>>()

    // 2. 取第一个有同步歌词的结果
    val bestMatch = results.firstOrNull { it.syncedLyrics != null }
        ?: results.firstOrNull()

    if (bestMatch == null) return null

    // 3. 解析 syncedLyrics 为 LyricsLine 列表
    return parseSyncedLyrics(bestMatch.syncedLyrics)
}
```

### 4.3 歌词解析（增强 LRC 解析器）

现有 `LrcParser.kt` 已支持基本 LRC 解析，新增能力：

| 能力 | 原有 LrcParser | 增强后 |
|:-----|:--------------:|:-------|
| 标准 LRC 格式 `[MM:SS.xx]` | ✅ | ✅ |
| 增强格式 `[MM:SS.xxx]` | ❌ | ✅ |
| 多歌词版本支持 | ❌ | ✅ |
| 时间戳编辑输出 | ❌ | ✅（输出合法 LRC） |

---

## 五、AuditionScreen UI 分步实现

### 5.1 布局结构（Compose TV）

```kotlin
@Composable
fun AuditionScreen(
    song: Song,
    onBack: () -> Unit,
    viewModel: AuditionViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // 初始化
    LaunchedEffect(song) {
        viewModel.startAudition(song)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部导航栏
        AuditionTopBar(song.title, onBack)

        // 主要内容
        Row(modifier = Modifier.weight(1f)) {
            // 左侧：封面 + 歌曲信息 + 播放控制
            AuditionLeftPanel(uiState, onPlayPause = { viewModel.togglePlayPause() })

            // 右侧：歌词区域（支持拖拽编辑）
            AuditionLyricsPanel(
                lyrics = uiState.lyrics,
                currentIndex = uiState.currentLyricIndex,
                isEditMode = uiState.isEditMode,
                onLineClick = { index -> viewModel.seekTo(uiState.lyrics[index].time) },
                onLineDrag = { index, newTime -> viewModel.adjustSingleLine(index, newTime) }
            )
        }

        // 底部状态栏
        AuditionBottomBar(
            lyricsSource = "LRCLIB",
            isEditMode = uiState.isEditMode,
            onToggleEditMode = { viewModel.toggleEditMode() },
            onReset = { viewModel.resetLyrics() },
            onSave = { viewModel.saveLyrics() }
        )
    }
}
```

### 5.2 歌词组件增强

现有 `LyricsView.kt` 已有逐行高亮能力，需增强：

| 增强点 | 说明 |
|:-------|:------|
| **编辑模式** | 长按歌词行进入编辑模式，时间戳变为可交互 |
| **拖拽指示器** | 时间戳旁边显示上下箭头拖拽手柄 |
| **时间编辑** | 方向键/遥控器上下键微调时间（±100ms/按） |
| **视觉反馈** | 编辑中的行显示蓝色边框，时间戳数字高亮 |
| **偏移模式** | 进入全局偏移模式后，整体歌词向左/右平移 |

### 5.3 TV 遥控器交互适配

| 操作 | 遥控器按键 | 效果 |
|:-----|:-----------|:------|
| 播放/暂停 | 确定键（点击） | 切换播放状态 |
| 快进 | 方向键→ | 前进 5 秒 |
| 快退 | 方向键← | 后退 5 秒 |
| 选歌词行 | 方向键↑↓ | 上下移动歌词焦点 |
| 跳转到歌词 | 确定键（在歌词行上） | 播放跳到该行时间 |
| 进入编辑 | 长按确定键 | 编辑模式开关 |
| 微调时间 | 编辑模式中 ↑↓ | ±100ms 步进 |
| 批量偏移 | 左滑/右滑手势 | ±1 秒步进 |

---

## 六、数据流

### 6.1 歌词加载数据流

```
AuditionViewModel.startAudition(song)
    │
    ├─ 1. song 写入 uiState
    │
    ├─ 2. 检查 LrcCache
    │   ├─ 有编辑版本 → 直接使用 _uiState.lyrics
    │   └─ 无缓存 → 进入第3步
    │
    ├─ 3. LRCLIB 搜索
    │   ├─ 成功 → 解析 syncedLyrics → 写入 LrcCache → _uiState.lyrics
    │   └─ 失败 → 进入第4步
    │
    ├─ 4. LyricsManager 降级
    │   ├─ 成功 → 解析 → 写入 LrcCache → _uiState.lyrics
    │   └─ 失败 → _uiState.lyrics = emptyList()
    │
    └─ 5. 启动 PreviewPlayer
        └─ progress 更新 → LrcTimeAdjuster.findCurrentLine → _uiState.currentLyricIndex
```

### 6.2 歌词编辑数据流

```
用户编辑操作
    │
    ├─ 调整单行
    │   ├─ LrcTimeAdjuster.adjustLineTime → 新歌词列表
    │   ├─ 更新 _uiState.editedLyrics
    │   └─ 自动保存到 LrcCache（防丢失）
    │
    ├─ 批量偏移
    │   ├─ LrcTimeAdjuster.offsetAll → 新歌词列表
    │   ├─ 更新 _uiState.timeOffset
    │   └─ (不自动保存，需用户确认)
    │
    └─ 保存编辑
        ├─ LrcCache.saveEditedVersion(songId, editedLyrics)
        └─ _uiState.isEditMode = false
```

---

## 七、错误处理与边界情况

| 场景 | 处理方式 |
|:-----|:---------|
| iTunes Preview URL 不可用 | 显示"试听资源不可用"，仅展示歌词 |
| 歌词一个都搜不到 | 显示"暂无歌词，以后可手动导入" |
| LRCLIB 请求超时 | 3 秒超时，超时后走 LyricsManager 降级 |
| 歌词行数过多（>200行） | 虚拟列表，仅渲染可见区域 ±20 行 |
| 歌词行时间不单调递增 | 自动排序 + 标记异常行供用户修正 |
| 用户编辑后时间冲突 | 实时校验，行间时间间隔 < 100ms 时警告 |
| TV 焦点丢失 | 保存当前编辑状态，恢复时继续 |
| 歌曲切换到另一首 | 清理当前歌词编辑状态 |

---

## 八、实现计划（roadmap）

| 阶段 | 内容 | 预估工作量 |
|:----:|:-----|:----------:|
| **Phase 1** | 歌词获取增强：集成 LRCLIB 搜索 + 同步歌词解析 | 1 天 |
| **Phase 2** | `PreviewPlayer` 轻量播放器实现 + iTunes Preview 试听 | 1 天 |
| **Phase 3** | `AuditionViewModel` + `AuditionUiState` 状态管理 | 1 天 |
| **Phase 4** | `AuditionScreen` Composable（封面、歌曲信息、播放控制） | 1.5 天 |
| **Phase 5** | `LyricEditController` + `LrcTimeAdjuster` 拖拽调整逻辑 | 1.5 天 |
| **Phase 6** | 歌词组件增强：编辑模式、拖拽交互、视觉反馈 | 1.5 天 |
| **Phase 7** | `LrcCache` 本地持久化 + 多版本支持 | 1 天 |
| **Phase 8** | TV 遥控器适配、集成到元数据搜索结果页 | 1 天 |

**总计**: 约 9.5 个工作日

---

## 九、依赖分析

### 9.1 新增依赖

| 依赖 | 用途 | 是否需要新加 |
|:-----|:-----|:------------:|
| `androidx.media3:media3-exoplayer` | PreviewPlayer 播放 | 项目已有 ✅ |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | LRCLIB JSON 解析 | 项目可能已有 |
| `com.squareup.okhttp3:okhttp` | LRCLIB HTTP 请求 | 项目已有 ✅ |
| `androidx.compose.foundation` | 手势（拖拽、滑动） | 项目已有 ✅ |

### 9.2 无需新增的依赖

| 组件 | 所属 | 复用方式 |
|:-----|:-----|:---------|
| `LyricsView` | `ui.components` | 增强（加编辑模式参数） |
| `LrcParser` | `lyrics` | 直接复用 |
| `LyricsManager` | `lyrics` | 降级歌词获取 |
| `PlayerManager` | `player` | 不冲突，PreviewPlayer 独立的 |
| `Song` 数据模型 | `data.model` | 直接复用 |

---

## 十、变更日志

| 版本 | 日期 | 变更内容 |
|:----:|:----:|:---------|
| v1.0 | 2026-07-12 | 初版：包含 AuditionScreen 完整设计、LRCLIB 歌词获取、拖拽调整、PreviewPlayer、LrcCache |
