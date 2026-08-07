# 方案 B：实时 DSP 人声消除（Mid-Side + 分频段处理）

> 状态：设计文档，待评审  
> 日期：2026-08-07  
> 关联：方案 C（AI 预分离）见 `docs/vocal-removal-approach-c-ai.md`  
> 关联：K 歌整体方案见 `docs/K歌开发方案.md`

## 1. 原理

利用立体声音频中人声通常居中（左右声道幅度近似相等）的特性，通过 Mid-Side 编码 + 分频段处理来消除人声，同时保留低频和高频的居中乐器。

### 1.1 处理流程

```
输入：立体声 PCM (L, R)
  │
  ├─ Mid-Side 编码
  │   Mid  = (L + R) / 2    ← 包含所有居中内容（人声 + 贝斯 + 底鼓 + ...）
  │   Side = (L - R) / 2    ← 包含所有非居中内容（立体声宽度的伴奏）
  │
  ├─ 对 Mid 信号分频段处理
  │   低通滤波（200Hz）-> lowMid    ← 保留贝斯、底鼓
  │   高通滤波（5kHz） -> highMid   ← 保留镲片、空气感
  │   ┌─────────────────────────────┐
  │   │ 200Hz-5kHz 频段被跳过       │ ← 人声主要能量所在频段，被消除
  │   └─────────────────────────────┘
  │   newMid = lowMid + highMid
  │
  ├─ 重组立体声
  │   L_out = newMid + Side
  │   R_out = newMid - Side
  │
  └─ 输出：处理后的立体声 PCM (L_out, R_out)
```

### 1.2 为什么比简单 L-R 更好

| 方案 | 人声消除 | 贝斯保留 | 高频保留 | 立体声宽度 |
|------|---------|---------|---------|-----------|
| 简单 L-R（`L-R` 输出单声道） | ✅ 干净 | ❌ 丢失 | ❌ 丢失 | ❌ 变单声道 |
| Mid-Side 分频（本方案） | ⚠️ 大部分消除 | ✅ 保留 | ✅ 保留 | ✅ 保留 |

简单 L-R 会把所有居中内容一起消除（贝斯、底鼓通常也在中央），导致伴奏变薄。本方案只消除人声频段（200Hz-5kHz）的居中内容，保留两端。

## 2. 与 K 歌整体方案的关系

`docs/K歌开发方案.md` 定义了三种播放模式：

| 模式 | 触发条件 | 音轨切换方式 |
|------|---------|-------------|
| 🎵 MUSIC | 纯音频文件（MP3/FLAC） | 无（单音轨） |
| 🎬 MV | 视频文件，1 条音轨 | 无（单音轨） |
| 🎤 KARAOKE | 视频文件，≥2 条音轨 | `DefaultTrackSelector` 切换原唱/伴奏音轨 |

**方案 B 的定位**：当用户播放的是**纯音频文件（MUSIC 模式）**且该文件只有 1 条音轨（无法通过音轨切换实现 K 歌）时，方案 B 提供"软件人声消除"能力，让纯音频歌曲也能享受 K 歌体验。

**三模式下的伴奏来源**：

```
用户点击"伴奏"按钮
  │
  ├─ KARAOKE 模式（视频 ≥2 音轨）
  │   -> 切换到第 2 条音轨（硬件切换，零损耗，最佳质量）
  │
  ├─ MUSIC 模式（纯音频，单音轨）
  │   -> 启用 VocalRemovalProcessor（方案 B，实时 DSP）
  │   -> 自动切换到 KARAOKE 全屏页面
  │
  └─ MV 模式（视频，单音轨）
      -> 启用 VocalRemovalProcessor（方案 B，实时 DSP）
      -> 自动切换到 KARAOKE 全屏页面
```

> **注意**：方案 B 和 KARAOKE 模式的音轨切换是**互斥**的。如果当前是 KARAOKE 模式（已有双音轨），不需要启用 DSP 处理；只有在单音轨场景下才启用 DSP。

## 3. UI 界面设计

### 3.1 设计思路

参考 `docs/K歌开发方案.md` §2.5 的 maidong-ktv 设计模式，**为伴奏模式设计独立的全屏 KARAOKE 页面**，而非在现有 NowPlayingScreen 上添加按钮。

**核心交互流程**：

```
NowPlayingScreen（正常播放）
  │
  │  用户点击"🎤 伴奏"按钮
  │  -> 启用 VocalRemovalProcessor
  │  -> 自动切换到 KaraokePlaybackScreen（全屏 KARAOKE 页面）
  │
  KaraokePlaybackScreen（伴奏模式）
  │  - 大幅封面图轮播（全屏）
  │  - 歌词逐字高亮（底部 1-2 行）
  │  - 精简控制栏
  │
  │  用户点击"原唱"按钮
  │  -> 关闭 VocalRemovalProcessor
  │  -> 自动切换回 NowPlayingScreen
  │
  NowPlayingScreen（正常播放，恢复原唱）
```

### 3.2 KaraokePlaybackScreen 布局设计

参照现有 NowPlayingScreen 的**沉浸模式**（点击封面图后的全屏模式），封面图填满整个屏幕作为背景：

```
┌─────────────────────────────────────────────────────┐
│                                                       │
│                                                       │
│              全屏封面图（ContentScale.Crop）            │
│              复用沉浸模式的背景加载逻辑                   │
│              （rememberAsyncImagePainter + blur）       │
│                                                       │
│           ┌───────────────────────────┐               │
│           │  暗色渐变遮罩（三段渐变）     │               │
│           │  顶部 0xCC0C1222 (80%)    │               │
│           │  中部 0x990C1222 (60%)    │  ← 确保文字可读 │
│           │  底部 0xCC0C1222 (80%)    │               │
│           └───────────────────────────┘               │
│                                                       │
│                  歌曲名（27sp, Bold）                  │
│                  歌手名（20sp, Secondary）             │
│                                                       │
│  ┌───────────────────────────────────────────────┐   │
│  │  ★ 当前行逐字高亮歌词（25sp, Primary, 居中）     │   │
│  │    下一行歌词预览（18sp, Secondary, 居中）       │   │
│  └───────────────────────────────────────────────┘   │
│                                                       │
│   [⏮]    [⏸/▶]    [⏭]    [🎤 原唱]                  │
│                                                       │
│  ████████████████░░░░░░░░  2:30 / 4:00               │
└─────────────────────────────────────────────────────┘
```

**布局要点**：

1. **全屏封面背景**：复用现有沉浸模式（`isImmersiveMode`）的背景加载逻辑--`rememberAsyncImagePainter` 加载 `coverCandidates.firstOrNull() ?: currentSong?.coverUrl`，`ContentScale.Crop` 填满屏幕，可选应用 `blur` 模糊滤镜（跟随用户的封面滤镜设置）
2. **暗色渐变遮罩**：复用沉浸模式的三段垂直渐变遮罩（`0xCC0C1222` -> `0x990C1222` -> `0xCC0C1222`），确保歌词和歌曲信息在任意封面上都可读
3. **多封面轮播**：背景封面支持 `coverCandidates` 多候选图轮播（复用 `CoverCarousel` 的轮播逻辑），与沉浸模式行为一致
4. **歌曲信息**：在屏幕中上部居中显示歌名（27sp Bold）+ 歌手（20sp），文字直接叠加在封面背景上
5. **歌词区**：屏幕中下部显示**当前行 + 下一行**，仅 2 行，逐字高亮模式，大字号高可读性
6. **控制栏**：精简为 4 个按钮（上一首 / 播放暂停 / 下一首 / 原唱切换），居中排列
7. **进度条**：最底部全宽，时间标签在两端

### 3.3 歌词显示设计

KARAOKE 模式下的歌词与正常模式不同--不使用滚动列表，而是**固定显示当前行 + 下一行**：

```kotlin
// KaraokeLyricsView - 新建组件
@Composable
fun KaraokeLyricsView(
    lyrics: Lyrics?,
    currentLineIndex: Int,
    highlightMode: LyricsHighlightMode,  // 强制逐字模式
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 当前行：大字号 + 逐字高亮
        lyrics?.lines?.getOrNull(currentLineIndex)?.let { line ->
            // 逐字高亮 AnnotatedString（复用现有 LyricsView 的逐字渲染逻辑）
            val annotatedText = buildKaraokeAnnotatedString(line, ...)
            Text(
                text = annotatedText,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // 下一行预览：小字号 + 暗色
        lyrics?.lines?.getOrNull(currentLineIndex + 1)?.let { nextLine ->
            Text(
                text = nextLine.text,
                fontSize = 18.sp,
                color = NasMusicColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }
}
```

**关键设计**：
- 强制逐字模式（`LyricsHighlightMode.WORD_BY_WORD`），即使全局设置是逐行模式
- 当前行 25sp 大字号 + 逐字高亮渐变色（复用现有 `NasMusicBrushes.lyricsHighlightBrush`）
- 下一行 18sp 暗色预览，帮助用户准备
- 歌词居中对齐，宽度撑满屏幕

### 3.4 "原唱/伴奏"切换按钮

参考 K 歌方案 §2.5.6 的红色 accent 按钮设计：

```kotlin
@Composable
fun VocalToggleButton(
    isKaraokeMode: Boolean,      // true = 伴奏模式, false = 原唱模式
    onClick: () -> Unit
) {
    // 始终红色 accent，不随状态变色，只切换文字
    Surface(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFFFD3359),           // 红色 #FD3359
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFE8316F),    // 聚焦偏粉
            focusedContentColor = Color.White
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                // 伴奏模式 -> 显示"原唱"（提示可切回）
                // 原唱模式 -> 显示"伴奏"（提示可切到伴奏）
                text = if (isKaraokeMode) "原唱" else "伴奏",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
```

### 3.5 页面切换机制

**不新增 Screen 枚举值**，而是在 NowPlayingScreen 内部根据 `vocalRemovalEnabled` 状态条件渲染：

```kotlin
@Composable
fun NowPlayingScreen(
    // ... 现有参数 ...
    vocalRemovalEnabled: Boolean = false,
    onToggleVocalRemoval: () -> Unit = {},
    // ...
) {
    if (vocalRemovalEnabled) {
        // 伴奏模式 -> 全屏 KARAOKE 布局
        KaraokePlaybackScreen(
            currentSong = currentSong,
            isPlaying = isPlaying,
            playMode = playMode,
            progressMs = progressMs,
            durationMs = durationMs,
            lyrics = lyrics,
            coverCandidates = coverCandidates,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onSeek = onSeek,
            onToggleVocalRemoval = onToggleVocalRemoval,  // 切回原唱
            playPauseFocusRequester = playPauseFocusRequester
        )
    } else {
        // 正常模式 -> 现有 NowPlaying 布局（不变）
        // ... 现有代码 ...
    }
}
```

**优点**：
- 无需修改导航路由（`AppRoot.kt` 的 `Screen.NowPlaying` 分支不变）
- 页面切换是 Compose 状态驱动的，切换瞬间完成，播放不中断
- `vocalRemovalEnabled` 由 `MainViewModel` 管理，持久化后跨重启保持

### 3.6 NowPlayingScreen 上的"伴奏"入口按钮

在正常模式（非 KARAOKE）的 NowPlayingScreen 控制栏中，添加"伴奏"按钮作为入口：

```
现有控制栏（compact 模式）：
┌──────────────────────────────────────┐
│  [⏮]  [⏸/▶]  [⏭]  [🔁]  [🎤 伴奏]  │
└──────────────────────────────────────┘
```

- "伴奏"按钮放在播放模式按钮（`[🔁]`）右侧
- compact 模式 48dp，全尺寸 72dp
- 红色 accent `#FD3359` 圆角矩形，文字"伴奏"
- 点击后：`vocalRemovalEnabled = true` -> 自动切换到 KaraokePlaybackScreen

### 3.7 KaraokePlaybackScreen 的"原唱"返回按钮

在 KARAOKE 全屏页面的控制栏中，"原唱"按钮既是功能切换也是返回入口：

```
KARAOKE 控制栏：
┌──────────────────────────────────────┐
│  [⏮]  [⏸/▶]  [⏭]  [🎤 原唱]        │
└──────────────────────────────────────┘
```

- 点击后：`vocalRemovalEnabled = false` -> 自动切换回 NowPlayingScreen 正常布局
- 按钮样式与入口按钮一致（红色 accent），文字改为"原唱"

### 3.8 不可用状态的隐藏逻辑

以下场景"伴奏"按钮应该**隐藏**：

1. **KARAOKE 模式**（未来 K 歌方案）：已有双音轨切换，不需要 DSP
2. **单声道音频**：DSP 无法处理，自动 bypass
3. **当前歌曲为 null**：无歌曲播放时不需要显示
4. **无歌词时**：KARAOKE 页面核心是歌词跟唱，无歌词时仍可使用但体验打折（可选保留）

```kotlin
val showVocalButton = currentSong != null &&
    playbackMode != PlaybackMode.KARAOKE  // 未来集成 K 歌方案后加上此条件
```

> **注意**：当前阶段（未实施 K 歌方案）`playbackMode` 始终为 MUSIC，所以按钮始终显示。未来集成 K 歌方案后加上条件判断即可。

## 4. 技术实现

### 4.1 ExoPlayer AudioProcessor 接入

ExoPlayer/Media3 提供 `AudioProcessor` 接口，允许在音频渲染管线中插入实时 PCM 处理。当前项目的 ExoPlayer 构建在 `PlaybackService.kt:74`：

```kotlin
// 当前代码（无 AudioProcessor）
val player = ExoPlayer.Builder(this)
    .setMediaSourceFactory(mediaSourceFactory)
    .setAudioAttributes(audioAttributes, true)
    .setHandleAudioBecomingNoisy(true)
    .build()
```

需要改为使用自定义 `RenderersFactory`，在 `AudioSink` 中注入 `VocalRemovalProcessor`。

### 4.2 需要修改的文件

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `player/VocalRemovalProcessor.kt` | **新增** | AudioProcessor 实现（核心 DSP 逻辑） |
| `player/PlaybackService.kt` | **修改** | ExoPlayer.Builder 注入处理器 |
| `player/PlayerManager.kt` | **修改** | 添加 `setVocalRemovalEnabled()` 开关方法 + 状态暴露 |
| `ui/components/KaraokePlaybackScreen.kt` | **新增** | 全屏 KARAOKE 布局（封面 + 歌词 + 控制栏） |
| `ui/components/KaraokeLyricsView.kt` | **新增** | 固定 2 行歌词 + 逐字高亮组件 |
| `ui/components/VocalToggleButton.kt` | **新增** | 红色"伴奏/原唱"切换按钮组件（可复用） |
| `ui/components/PlayerControls.kt` | **修改** | `ControlButtonsRow` 新增伴奏入口按钮参数 |
| `ui/screens/NowPlayingScreen.kt` | **修改** | 条件渲染 KaraokePlaybackScreen + 传递状态 |
| `ui/components/AppRoot.kt` | **修改** | 连接 ViewModel 状态到 NowPlayingScreen |
| `ui/viewmodel/MainViewModel.kt` | **修改** | 暴露 `vocalRemovalEnabled` 状态 + `toggleVocalRemoval()` |
| `data/prefs/AppPreferences.kt` | **修改**（可选） | 持久化开关状态 |
| `res/values/strings.xml` | **修改** | 添加按钮文案 |

### 4.3 各文件改动详情

#### 4.3.1 新增：`VocalRemovalProcessor.kt`（~180 行）

核心类，实现 `androidx.media3.common.audio.AudioProcessor` 接口。

**关键设计**：
- `isActive()` 始终返回 `true`（配置成功后），这样运行时切换不需要重新配置 AudioSink
- `setEnabled(false)` 时走 bypass 路径（直接拷贝输入到输出，零开销）
- 内部使用两个二阶 IIR 滤波器（RBJ Audio EQ Cookbook 公式）
- 仅支持 16-bit PCM 立体声，其他格式自动 bypass

**核心处理逻辑**（`queueInput` 方法）：

```kotlin
override fun queueInput(inputBuffer: ByteBuffer) {
    // ... 缓冲区管理 ...

    if (!enabled) {
        // Bypass：直接拷贝
        buffer.put(inputBuffer)
        buffer.flip()
        outputBuffer = buffer
        return
    }

    while (inputBuffer.remaining() >= 4) {  // 4 bytes = 1 frame (L + R)
        val left = inputBuffer.short.toInt()
        val right = inputBuffer.short.toInt()

        // Mid-Side 编码
        val mid = (left + right) / 2
        val side = (left - right) / 2

        // 对 Mid 分频：低通 200Hz + 高通 5kHz，跳过人声频段
        val midF = mid.toFloat()
        val newMid = (lpFilter.process(midF) + hpFilter.process(midF)).toInt()

        // 重组立体声
        buffer.putShort(clamp(newMid + side))
        buffer.putShort(clamp(newMid - side))
    }
    buffer.flip()
    outputBuffer = buffer
}
```

**二阶 IIR 滤波器**（BiquadFilter 内部类）：

使用 RBJ Audio EQ Cookbook 公式计算系数：
- 低通：截止 200Hz, Q=0.707 (Butterworth)
- 高通：截止 5000Hz, Q=0.707

Direct Form I 实现：
```
y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] - a1·y[n-1] - a2·y[n-2]
```

#### 4.3.2 新增：`KaraokePlaybackScreen.kt`（~150 行）

全屏 KARAOKE 布局组件，复用沉浸模式的全屏封面背景逻辑：

```kotlin
@Composable
fun KaraokePlaybackScreen(
    currentSong: Song?,
    isPlaying: Boolean,
    playMode: PlayMode,
    progressMs: Long,
    durationMs: Long,
    lyrics: Lyrics?,
    coverCandidates: List<String>,
    coverFilterEnabled: Boolean = false,
    coverFilterBlurRadius: Float = 8f,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleVocalRemoval: () -> Unit,
    playPauseFocusRequester: FocusRequester
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
    ) {
        // ── 全屏封面背景（复用沉浸模式逻辑）──
        val bgUrl = coverCandidates.firstOrNull() ?: currentSong?.coverUrl
        if (bgUrl != null) {
            val painter = rememberAsyncImagePainter(model = bgUrl)
            Image(
                painter = painter,
                contentDescription = "Karaoke Fullscreen Cover",
                contentScale = ContentScale.Crop,           // 填满屏幕，裁剪溢出
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (coverFilterEnabled && coverFilterBlurRadius > 0f)
                            Modifier.blur(coverFilterBlurRadius.dp)  // 跟随用户封面滤镜设置
                        else Modifier
                    )
            )
        }
        // 暗色渐变遮罩（三段渐变，确保文字可读）
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xCC0C1222),   // 顶部 80% 遮罩
                        Color(0x990C1222),   // 中部 60% 遮罩（歌词区更透，露出封面）
                        Color(0xCC0C1222)    // 底部 80% 遮罩
                    )
                )
            )
        )

        // ── 前景内容 ──
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 上部：歌曲信息（直接叠加在封面背景上）
            Spacer(Modifier.weight(0.5f))   // 顶部留白
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = currentSong?.title ?: "",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = NasMusicColors.TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = currentSong?.artist ?: "",
                    fontSize = 20.sp,
                    color = NasMusicColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            // 中部：歌词（当前行 + 下一行）
            KaraokeLyricsView(
                lyrics = lyrics,
                progressMs = progressMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center   // 歌词垂直居中
            )

            // 底部：控制栏 + 进度条
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPrevious, icon = { Icon(Icons.Filled.SkipPrevious, ...) })
                    Spacer(Modifier.width(20.dp))
                    IconButton(onClick = onPlayPause, primary = true, ...)
                    Spacer(Modifier.width(20.dp))
                    IconButton(onClick = onNext, icon = { Icon(Icons.Filled.SkipNext, ...) })
                    Spacer(Modifier.width(20.dp))
                    VocalToggleButton(
                        isKaraokeMode = true,
                        onClick = onToggleVocalRemoval
                    )
                }
                Spacer(Modifier.height(16.dp))
                ProgressSection(
                    progressMs = progressMs,
                    durationMs = durationMs,
                    onSeek = onSeek
                )
            }
        }
    }
}
```

> **关键复用**：全屏封面背景的 `rememberAsyncImagePainter` + `ContentScale.Crop` + `blur` + 三段渐变遮罩逻辑，直接从 `NowPlayingScreen.kt` 沉浸模式（第 128-168 行）提取，确保视觉效果与沉浸模式完全一致。

#### 4.3.3 新增：`KaraokeLyricsView.kt`（~100 行）

固定 2 行歌词显示 + 逐字高亮：

```kotlin
@Composable
fun KaraokeLyricsView(
    lyrics: Lyrics?,
    progressMs: Long,
    modifier: Modifier = Modifier
) {
    val currentLineIndex = remember(lyrics, progressMs) {
        lyrics?.let { findCurrentLineIndex(it, progressMs) } ?: -1
    }

    Column(
        modifier = modifier.padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 当前行：逐字高亮
        lyrics?.lines?.getOrNull(currentLineIndex)?.let { line ->
            val annotatedText = buildKaraokeAnnotatedString(
                line = line,
                progressMs = progressMs,
                highlightColor = NasMusicColors.Primary
            )
            Text(
                text = annotatedText,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(8.dp))

        // 下一行预览
        lyrics?.lines?.getOrNull(currentLineIndex + 1)?.let { nextLine ->
            Text(
                text = nextLine.text,
                fontSize = 18.sp,
                color = NasMusicColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
```

> **复用现有逻辑**：逐字高亮的 `AnnotatedString` 构建逻辑从现有 `LyricsView.kt` 中提取复用，确保高亮效果一致。

#### 4.3.4 新增：`VocalToggleButton.kt`（~60 行）

红色 accent 切换按钮组件，参考 §3.4 设计。放在 `ui/components/` 下，可在 NowPlayingScreen（入口）和 KaraokePlaybackScreen（返回）中复用。

#### 4.3.5 修改：`PlaybackService.kt`

```kotlin
// 新增字段
private lateinit var vocalRemovalProcessor: VocalRemovalProcessor

// onCreate() 中修改 ExoPlayer 构建
override fun onCreate() {
    super.onCreate()
    // ... 现有代码 ...

    vocalRemovalProcessor = VocalRemovalProcessor()

    // 自定义 RenderersFactory，注入 AudioProcessor
    val renderersFactory = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(vocalRemovalProcessor))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
    }

    val player = ExoPlayer.Builder(this)
        .setRenderersFactory(renderersFactory)        // ← 新增
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(audioAttributes, true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    // ... 现有代码 ...

    // 将 processor 引用传给 PlayerManager
    (application as NasMusicApp).playerManager.setVocalRemovalProcessor(vocalRemovalProcessor)
}
```

**需要新增的 import**：
```kotlin
import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
```

#### 4.3.6 修改：`PlayerManager.kt`

```kotlin
class PlayerManager() {
    // ... 现有字段 ...

    private var vocalRemovalProcessor: VocalRemovalProcessor? = null

    /** 由 PlaybackService 注入 */
    fun setVocalRemovalProcessor(processor: VocalRemovalProcessor) {
        vocalRemovalProcessor = processor
    }

    /** 开关人声消除 */
    fun setVocalRemovalEnabled(enabled: Boolean) {
        vocalRemovalProcessor?.setEnabled(enabled)
    }

    /** 查询当前状态 */
    fun isVocalRemovalEnabled(): Boolean {
        return vocalRemovalProcessor?.isEnabled() ?: false
    }
}
```

#### 4.3.7 修改：`NowPlayingScreen.kt`

**函数签名新增参数**：

```kotlin
@Composable
fun NowPlayingScreen(
    // ... 现有参数 ...
    // === 新增 ===
    vocalRemovalEnabled: Boolean = false,
    onToggleVocalRemoval: () -> Unit = {},
    // ...
)
```

**条件渲染逻辑**：

```kotlin
fun NowPlayingScreen(...) {
    if (vocalRemovalEnabled) {
        // 伴奏模式 -> 全屏 KARAOKE 布局
        KaraokePlaybackScreen(
            currentSong = currentSong,
            isPlaying = isPlaying,
            // ... 传递所有必要参数 ...
            onToggleVocalRemoval = onToggleVocalRemoval
        )
        return
    }

    // 正常模式 -> 现有布局（不变）
    Box(...) {
        // ... 现有 NowPlaying 布局代码 ...
    }
}
```

**ControlButtonsRow 调用处新增伴奏入口按钮**：

```kotlin
ControlButtonsRow(
    isPlaying = isPlaying,
    playMode = playMode,
    onPlayPause = onPlayPause,
    onNext = onNext,
    onPrevious = onPrevious,
    onTogglePlayMode = onTogglePlayMode,
    // 新增
    showVocalButton = currentSong != null,
    onToggleVocalRemoval = onToggleVocalRemoval,
    compact = true,
    playPauseFocusRequester = playPauseFocusRequester
)
```

#### 4.3.8 修改：`PlayerControls.kt` - `ControlButtonsRow`

```kotlin
@Composable
fun ControlButtonsRow(
    isPlaying: Boolean,
    playMode: PlayMode,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayMode: () -> Unit,
    // === 新增参数 ===
    showVocalButton: Boolean = false,
    onToggleVocalRemoval: () -> Unit = {},
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    playPauseFocusRequester: FocusRequester? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ... 现有的 Previous / PlayPause / Next / PlayMode 按钮 ...

        // 新增：伴奏入口按钮
        if (showVocalButton) {
            Spacer(modifier = Modifier.width(if (compact) 12.dp else 20.dp))
            VocalToggleButton(
                isKaraokeMode = false,   // 在 NowPlayingScreen 上显示"伴奏"
                onClick = onToggleVocalRemoval
            )
        }
    }
}
```

#### 4.3.9 修改：`AppRoot.kt`

在 `Screen.NowPlaying` 分支中传递 ViewModel 状态：

```kotlin
Screen.NowPlaying -> {
    val vocalRemovalEnabled by viewModel.vocalRemovalEnabled.collectAsState(initial = false)
    NowPlayingScreen(
        // ... 现有参数 ...
        vocalRemovalEnabled = vocalRemovalEnabled,
        onToggleVocalRemoval = { viewModel.toggleVocalRemoval() },
        // ...
    )
}
```

#### 4.3.10 修改：`MainViewModel.kt`

```kotlin
private val _vocalRemovalEnabled = MutableStateFlow(false)
val vocalRemovalEnabled: StateFlow<Boolean> = _vocalRemovalEnabled

fun toggleVocalRemoval() {
    val newState = !_vocalRemovalEnabled.value
    _vocalRemovalEnabled.value = newState
    playerManager.setVocalRemovalEnabled(newState)
}
```

#### 4.3.11 可选：持久化开关状态

在 `AppPreferences` 中添加：

```kotlin
val vocalRemovalEnabled: Flow<Boolean> = dataStore.data
    .map { it.vocalRemovalEnabled ?: false }

suspend fun setVocalRemovalEnabled(enabled: Boolean) {
    dataStore.edit { it.vocalRemovalEnabled = enabled }
}
```

`MainViewModel.init` 中读取持久化值并应用到 `PlayerManager`。切歌时自动关闭伴奏模式（避免上一首的 DSP 状态带到下一首）。

## 5. 性能分析

### 5.1 CPU 开销

- 每个音频采样：2 次滤波器运算（低通 + 高通），每次约 5 次乘加
- 44100 Hz 立体声：每秒 44100 帧 × 10 次乘加 = 441,000 次浮点运算/秒
- 现代 ARM CPU 可轻松处理（占用 <1% CPU）

### 5.2 延迟

- 处理延迟：零（实时处理，逐采样输出）
- 缓冲区延迟：一个 AudioTrack 缓冲区大小（通常 20-50ms），与正常播放相同
- 切换响应：`setEnabled()` 后下一帧立即生效（<50ms）
- 页面切换：Compose 状态驱动，瞬间完成，播放不中断

### 5.3 内存

- 滤波器状态：4 个 float 变量 × 2 个滤波器 = 32 字节
- 缓冲区：与输入等大的 ByteBuffer（通常几 KB）
- KARAOKE 页面 UI：与 NowPlayingScreen 相当，无额外内存负担
- 总增量：可忽略

## 6. 质量预期

### 6.1 效果好的场景

- **专业录音室混音**：人声严格居中，消除效果明显
- **流行/摇滚乐**：人声频段清晰，伴奏保留良好
- **立体声宽度大的混音**：Side 信号丰富，伴奏完整度高

### 6.2 效果一般的场景

- **人声不在正中**（偏左/偏右混音）：消除不干净
- **双轨人声/和声**：消除不完整
- **单声道录音**：Side = 0，无效果（自动 bypass）
- **人声频段与其他乐器重叠**：200Hz-5kHz 内的居中乐器也会被消除

### 6.3 已知限制

- 消除后会有轻微"空洞感"（人声频段缺失）
- 残留人声回声/混响（混响通常有立体声宽度，不会被消除）
- 无法完全消除和声伴唱
- **质量不如 KARAOKE 模式的双音轨切换**（方案 B 是有损处理，音轨切换是无损）

## 7. 与方案 C 的关系

方案 B 和方案 C 可以**共存**：

- **方案 B 作为默认**：实时切换，零等待，适合临时跟唱
- **方案 C 作为增强**：高质量伴奏，适合需要更好效果的场景
- 用户可以在"原唱（无处理）/ 实时伴奏（方案 B）/ AI 伴奏（方案 C）"三者间切换

如果先实施方案 B，后续添加方案 C 时不需要修改 B 的代码，只需在 UI 上增加第三个选项。KARAOKE 全屏页面可以同时适配方案 B 和方案 C 的伴奏源。

## 8. 实施计划

| 步骤 | 内容 | 预计工时 |
|------|------|---------|
| 1 | 新建 `VocalRemovalProcessor.kt`（DSP 核心） | 1 小时 |
| 2 | 修改 `PlaybackService.kt`（注入处理器） | 30 分钟 |
| 3 | 修改 `PlayerManager.kt`（开关方法） | 15 分钟 |
| 4 | 新建 `VocalToggleButton.kt`（红色按钮组件） | 30 分钟 |
| 5 | 新建 `KaraokeLyricsView.kt`（2 行逐字歌词） | 1 小时 |
| 6 | 新建 `KaraokePlaybackScreen.kt`（全屏布局） | 1.5 小时 |
| 7 | 修改 `PlayerControls.kt`（ControlButtonsRow 集成入口按钮） | 20 分钟 |
| 8 | 修改 `NowPlayingScreen.kt`（条件渲染 + 参数传递） | 30 分钟 |
| 9 | 修改 `AppRoot.kt` + `MainViewModel.kt`（状态连接） | 20 分钟 |
| 10 | 可选：持久化开关状态 + 切歌自动关闭 | 20 分钟 |
| 11 | 构建 + 真机测试 + 参数调优 | 1.5 小时 |
| **合计** | | **~8 小时** |

## 9. 测试要点

### DSP 音频质量
- [ ] 开关切换时无明显爆音/断裂
- [ ] 原唱模式下音质与未集成处理器前完全一致（bypass 正确）
- [ ] 伴奏模式下人声明显衰减，贝斯/镲片可闻
- [ ] 快进/快退后滤波器状态正确重置（无异常噪声）
- [ ] 单声道文件自动 bypass，不产生异常
- [ ] 网络歌曲（HTTP 流）正常工作
- [ ] 与均衡器、频谱分析器无冲突
- [ ] 长时间播放无内存泄漏

### UI 交互
- [ ] 点击"伴奏"按钮后立即切换到 KARAOKE 全屏页面
- [ ] 点击"原唱"按钮后立即切换回 NowPlaying 正常布局
- [ ] 页面切换时播放不中断、无爆音
- [ ] KARAOKE 页面封面轮播正常工作
- [ ] KARAOKE 页面歌词逐字高亮正确（与进度同步）
- [ ] KARAOKE 页面 D-Pad 焦点导航正确（控制按钮间可切换）
- [ ] 进度条 Seek 在 KARAOKE 页面正常工作
- [ ] 无歌曲时"伴奏"按钮隐藏
- [ ] 切歌时自动关闭伴奏模式（如启用持久化）

### 边界情况
- [ ] 无歌词时 KARAOKE 页面仍可用（歌词区显示占位）
- [ ] 封面加载失败时 KARAOKE 页面有 fallback
- [ ] 沉浸模式与 KARAOKE 模式不冲突（KARAOKE 优先）

---

*文档版本：3.1  
*创建日期：2026-08-07  
*更新日期：2026-08-07（v3.1：全屏封面背景改为复用沉浸模式逻辑）  
*更新日期：2026-08-07（v3.0：独立全屏 KARAOKE 页面设计）  
*更新日期：2026-08-07（v2.0：参考 K 歌开发方案完善 UI 设计）*
