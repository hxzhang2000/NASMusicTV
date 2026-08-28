# 人声伴奏分离 + 升降调 + 变速 功能分析方案

> **状态**：需求分析（待决策，尚未编码）
> **日期**：2026-08-27
> **关联**：用户反馈 ColorOS 声音分轨效果远超当前 Mid/Side DSP 方案，希望升级

---

## 1. 用户需求总结

### 1.1 人声伴奏分离（核心诉求）

用户反馈当前 NASMusicTV 的 `VocalRemovalProcessor`（Mid/Side 声道分离）效果不够好——"人声只是小了一点点"，希望达到 ColorOS 系统级声音分轨的效果。

**参考对比**：

| 方案 | 效果 | 用户体验 |
|------|------|----------|
| ColorOS 系统分轨 | ⭐⭐⭐⭐⭐ | 完全消除主唱人声，保留和声，仅支持 QQ音乐/网易/酷狗/酷我 |
| 动听 App（付费） | ⭐⭐⭐⭐ | 效果比 NASMusicTV 好，但不如 ColorOS |
| NASMusicTV（当前） | ⭐⭐ | 人声只小了一点点，不够彻底 |
| 箭头音乐 | ⭐⭐⭐ | 支持 NAS 音乐 + 升降调 + 变速 |

**用户期望**：K 歌页面点击"伴奏人声"按钮，实时切换 K 歌伴奏模式，效果接近 ColorOS

### 1.2 升降调功能

- **场景**：学习歌曲时原 Key 过高，普通人唱不上去，需要降调
- **参考**：箭头音乐已实现，支持 NAS 音乐播放时升降调
- **需求**：K 歌页面提供升降调按钮（如 -12 ~ +12 半音）

### 1.3 变速功能

- **场景**：学习快歌时调慢速度便于练习
- **参考**：箭头音乐已实现
- **需求**：K 歌页面提供变速按钮（如 0.5x ~ 1.5x）

---

## 2. 技术现状分析

### 2.1 当前人声消除方案：VocalRemovalProcessor

**原理**：Mid/Side 声道分离 + 频段衰减
- 分离立体声 Mid（L+R）和 Side（L−R）
- Mid 包含人声和居中乐器，在 200Hz-4kHz 深度衰减（保留 15%）
- Side 轻度削减（保留 50%）保住立体声宽度

**局限**：
- 纯 DSP 频域处理，无法区分人声和乐器（人声频段也有吉他/钢琴/贝斯）
- 人声残留明显，乐器也受影响
- 没有 AI 模型参与，本质是"衰减"而非"分离"

### 2.2 ColorOS 声音分轨原理

> **重要说明**：ColorOS 声音分轨的技术细节 OPPO 官方**从未公开**，以下为基于公开信息的效果特征推断，**不能确定**具体技术方案。

#### 2.2.1 效果特征分析

从用户实际体验可观察到以下效果特征：

| 效果特征 | 指向的推断 |
|---------|-----------|
| 能区分主唱和和声（都是人声但分别处理） | 只有 AI 模型能学习"主唱 vs 伴唱"的声学特征差异，传统 DSP 不可能做到 |
| 仅支持 QQ音乐/网易/酷狗/酷我 | 可能需要版权方提供分轨数据，或仅对受 DRM 保护的音频流处理 |
| 效果远超传统 DSP | Mid/Side 或频段衰减不可能达到"完全消除主唱保留和声"的精度 |
| 实时切换无延迟 | 可能是端侧模型 + NPU 加速，也可能是云端预处理 |
| 保留伴奏完整性（乐器不受损） | 说明不是简单的频段衰减，而是声源级别的分离 |

#### 2.2.2 可能的技术方案

| 可能方案 | 可能性 | 依据 |
|---------|-------|------|
| **端侧 AI 模型（Spleeter/Demucs 类）+ NPU 加速** | ⭐⭐⭐ 高 | 效果特征与 Spleeter 一致；OPPO 手机使用 MediaTek 天玑芯片有 APU 加速；全民K歌已联合 MediaTek 发布"端侧 AI 纯净人声萃取"（2025-04） |
| **云端分离** | ⭐⭐ 中 | 仅支持正版 App 可能意味着音频上传云端处理；但实时切换体验不像有网络延迟 |
| **NPU/DSP 硬件加速 + 自研轻量模型** | ⭐⭐⭐ 高 | OPPO 有自研 AI 算力平台；ColorOS 可能内置专用音频分离 NPU 模型 |
| **传统 DSP 升级版（频谱遮罩）** | ⭐ 低 | 效果太好，传统 DSP 达不到"保留和声消除主唱"的精度 |

#### 2.2.3 最接近的公开案例

| 案例 | 技术方案 | 效果 | 与 ColorOS 的关联 |
|------|---------|------|-----------------|
| 全民K歌 + MediaTek + vivo | 端侧 AI 模型 + 天玑 APU 加速 | 实时分离人声和环境噪音 | OPPO 也用联发科芯片，技术路线高度一致 |
| 华为音频编辑服务 | STFT + U-Net 云端 AI | 近实时分离 | 需要 HMS 生态，非通用方案 |
| Spleeter ONNX（鸿蒙社区） | Spleeter 2-stem + ONNX Runtime | 端侧推理 3-5s/3min 歌曲，85-90% 质量 | 公开可用的最接近方案 |
| UVR（Ultimate Vocal Remover） | VR/MDX-Net/Demucs 三引擎 | 专业级分离，PC 端 | 桌面端标杆，模型太大不适合移动端 |

#### 2.2.4 结论

**不能确定 ColorOS 是端侧还是云端，但几乎可以确定是基于 AI 模型的**——效果特征（区分主唱和声、保留乐器完整性）只有 AI 能做到。

最可能的技术路线：**端侧轻量 AI 模型 + NPU/APU 硬件加速**，与全民K歌联合 MediaTek 的"端侧 AI 纯净人声萃取"方案一脉相承。OPPO 可能拥有自研模型（非公开），配合正版 App 提供的分轨数据做后处理优化。

#### 2.2.5 对 NASMusicTV 的可参考性

| 对比项 | ColorOS | NASMusicTV 可达到 |
|--------|---------|-------------------|
| 分离质量 | 95%+（主唱完全消除，和声保留） | 85-90%（Spleeter ONNX） |
| 实时性 | 实时切换 | 首次 3-5s 等待 + 缓存后零延迟 |
| 模型 | 自研（非公开） | Spleeter 2-stem（开源） |
| 硬件加速 | NPU/APU | CPU（部分 ARM64 有 NEON 加速） |
| 正版分轨数据 | 有（QQ/网易/酷狗/酷我提供） | 无 |
| 适用设备 | ColorOS 手机/一加 | 任意 Android TV/手机 |

**NASMusicTV 无法完全复现 ColorOS 效果**，但 Spleeter ONNX 方案可达到 85-90% 的分离质量，远超当前 Mid/Side DSP 方案。

### 2.3 升降调 / 变速现状

当前 NASMusicTV 的 ExoPlayer 已内置 `SonicAudioProcessor`，支持：
- `setPitch(float)` — 升降调（0.1x ~ 8.0x，1.0 = 原调）
- `setSpeed(float)` — 变速（0.1x ~ 8.0x，1.0 = 原速）
- 可独立设置 pitch 和 speed（变速不变调 / 变调不变速）

**但未暴露 UI**——需要在 K 歌页面新增升降调/变速按钮。

---

## 3. 技术方案分析

### 3.1 人声伴奏分离方案对比

| 方案 | 分离质量 | 实时性 | APK 体积 | TV 适用性 | 实现复杂度 |
|------|---------|--------|---------|----------|-----------|
| **当前 Mid/Side DSP** | ⭐⭐ | ✅ 实时 | 无 | ✅ | 低（已实现） |
| **频谱遮罩（STFT + 自适应遮罩）** | ⭐⭐⭐ | ✅ 实时 | 无 | ✅ | 中 |
| **Spleeter ONNX（端侧）** | ⭐⭐⭐⭐ | ❌ 有延迟 2-5s | +24MB | ⚠️ 中高端 TV 可行 | 高 |
| **Demucs ONNX（端侧）** | ⭐⭐⭐⭐⭐ | ❌ 延迟更大 | +80MB | ❌ 太重 | 很高 |
| **华为音频编辑服务** | ⭐⭐⭐⭐ | ✅ 近实时 | 需 HMS SDK | ⚠️ 需 HMS 生态 | 中 |
| **服务端分离** | ⭐⭐⭐⭐⭐ | ✅ 预处理后 | 无 | ✅ | 高（需后端配合） |

### 3.2 推荐方案：双模式（频谱遮罩 + Spleeter ONNX）

**快速模式**：频谱遮罩（STFT + 自适应遮罩）——升级替换当前 Mid/Side DSP，实时零延迟，质量 ⭐⭐⭐
**高质量模式**：Spleeter ONNX 端侧分离——首次 3-5s 处理 + 缓存后零延迟，质量 ⭐⭐⭐⭐

### 3.3 升降调 / 变速方案

**完全使用 ExoPlayer 内置 SonicAudioProcessor**，无需额外库。UI 仅放在 K 歌页面。

---

## 4. 实施方案

### 4.1 人声分离（双模式）

#### 4.1.1 依赖与模型

| 依赖 | 大小 | 说明 |
|------|------|------|
| ONNX Runtime Android AAR | ~8MB | 模型推理引擎，打包在 APK 内 |
| vocals.fp16.onnx | ~8MB | 人声模型（FP16 量化），打包在 APK `assets/spleeter/` 内 |
| accompaniment.fp16.onnx | ~8MB | 伴奏模型（FP16 量化），打包在 APK `assets/spleeter/` 内 |
| **总计** | **~24MB** | 全部打包在 APK 内，无网络下载依赖 |

> **APK 体积影响**：采用方案 A（内置 APK），APK 体积增加约 24MB（ONNX Runtime AAR 8MB + 模型文件 16MB）。模型文件放 `app/src/main/assets/spleeter/`，Gradle 打包时自动包含。ONNX Runtime AAR 通过 `build.gradle.kts` 依赖引入。
>
> **模型文件准备流程**：
> 1. 从 Spleeter 官方仓库下载预训练 `.h5` 模型
> 2. 用 `tf2onnx` 转换为 ONNX 格式
> 3. 用 `onnxruntime` 量化工具做 FP16 量化（体积减半）
> 4. 下载到本地后提交到 GitHub 仓库 `app/src/main/assets/spleeter/` 目录
> 5. Gradle 打包时自动包含到 APK 中

#### 4.1.2 新增文件

```
player/
├── VocalRemovalProcessor.kt     # 现有 Mid/Side DSP（将被频谱遮罩替换）
├── SpectralMaskProcessor.kt     # 🆕 快速模式：STFT + 自适应遮罩（替换 VocalRemovalProcessor）
├── SpleeterSeparator.kt         # 🆕 高质量模式：Spleeter ONNX 分离器
├── SpleeterDsp.kt               # 🆕 STFT/iSTFT/Wiener 归一化（Spleeter 专用 DSP 层）
└── AccompanimentCache.kt        # 🆕 伴奏文件缓存管理 + 预分离队列

ui/components/
└── KaraokePlaybackScreen.kt     # 修改：K 歌页面 UI（模式切换 + 调/速按钮）

data/prefs/
└── AppPreferences.kt             # 修改：新增 pitch/speed + 默认分离模式 持久化
```

#### 4.1.3 分离流程（高质量模式）

```
K 歌页面 → 切换到"高质量"模式
  ↓
检查 AccompanimentCache 是否有该 songId 的伴奏文件
  ├─ 有 → ExoPlayer 切换到伴奏文件（零延迟）
  └─ 无 → 显示"正在分离人声..."进度条
            ↓
          获取 streamUrl → 下载到临时文件
            ↓
          SpleeterSeparator.separate(inputWav, outputDir)
            → STFT → ONNX 推理 × 2 → Wiener 归一化 → iSTFT
            → 输出 vocals.wav + accompaniment.wav
            ↓
          缓存 accompaniment.wav（关联 songId）
            ↓
          ExoPlayer 切换到伴奏文件
```

#### 4.1.4 缓存策略

- 伴奏文件缓存到 `context.cacheDir/accompaniment/`
- 文件名：`{songId}.wav`
- LRU 淘汰：总缓存上限 500MB（无损伴奏文件较大）
- 设置页"缓存管理"新增"伴奏缓存"大小显示 + 清除按钮
- 备份/恢复纳入伴奏缓存列表（仅列表，不备份音频文件）

#### 4.1.5 双模式设计

| 模式 | 实现 | 延迟 | 质量 | 适用场景 |
|------|------|------|------|---------|
| **快速模式** | `SpectralMaskProcessor`（STFT + 自适应遮罩） | 零延迟实时 | ⭐⭐⭐ | 即时试听、快速切换 |
| **高质量模式** | `SpleeterSeparator`（ONNX 推理） | 首次 3-5s + 缓存后零延迟 | ⭐⭐⭐⭐ | 正式 K 歌 |

**默认模式设置**：
- 设置页新增"K 歌默认分离模式"选项（快速 / 高质量），持久化到 `AppPreferences`
- K 歌页面进入时使用默认模式，用户可在 K 歌页面内切换

**K 歌页面 UI**：
- 用**独立状态按钮**切换"快速/高质量"模式（不是原唱/伴奏按钮）
- 模式切换不影响原唱/伴奏状态
- 快速模式切换瞬时生效；高质量模式切换时若缓存已有则瞬时、无缓存则显示进度条

#### 4.1.6 预分离下一首

为避免切歌时等待分离，在当前歌曲播放期间提前处理队列中的下一首：

```
当前歌曲播放中（高质量模式）
  ↓
后台预分离队列下一首（按播放模式确定：顺序/随机）
  ↓
  ├─ 顺序模式：预分离 currentIndex + 1
  └─ 随机模式：预分离 playerManager.peekNextSong(playMode)
  ↓
分离结果写入 AccompanimentCache
  ↓
当前歌曲播完 → 切歌 → 检查缓存 → 已有伴奏 → 零延迟切换 ✅
```

**预分离时机**：
- 当前歌曲播放进度 > 50% 时触发预分离
- 或当前歌曲剩余时间 < 30s 时触发
- 预分离在后台协程执行，不阻塞播放
- 预分离结果加入缓存，切歌时直接命中

**与 MTV 连播预搜的协同**：
- MTV 也有预搜下一首机制，两者并行不冲突
- 预分离只处理伴奏文件，不影响 MTV 视频搜索

### 4.2 升降调

#### 4.2.1 实现

```kotlin
// PlayerManager.kt
private val sonicAudioProcessor = SonicAudioProcessor()

fun setPitch(semitones: Int) {
    val pitchFactor = Math.pow(2.0, semitones.toDouble() / 12.0).toFloat()
    player?.let { p ->
        p.playbackParameters = p.playbackParameters.withPitch(pitchFactor)
    }
}
```

#### 4.2.2 UI（仅 K 歌页面）

> **参考设计**：箭头音乐（Amcfy Music）采用滑块控件（Flutter `Slider`）无级调节音高/速度，音高用半音单位显示（-12~+12），速度用倍率显示（0.5x~2.0x），三个控件（音量/音高/速度）放在同一面板。NASMusicTV 改为 TV 适配的**步进按钮**设计。

K 歌页面控制栏新增"调"按钮：
- 点击弹出 `-12 ~ +12` 半音选择器（步进 1）
- 当前值显示在按钮上（如 `调: -3`）
- 0 = 原调，负值降调，正值升调
- 重启后恢复上次设置（全局记忆）
- **仅 K 歌页面显示**，普通播放页暂不增加

**K 歌页面 UI 布局**：

```
┌──────────────────────────────────────────────────┐
│                  歌词区域                         │
│                                                  │
│          ♪ 前一句歌词（白色预览）                  │
│          ♪ 当前行歌词（黄色高亮逐字推进）           │
│                                                  │
├──────────────────────────────────────────────────┤
│  [快速] [高质量]    调: -3  速: 0.8x  [原唱/伴奏]  │
│  ← 返回    ← 上一首  ▶/⏸  下一首 →               │
└──────────────────────────────────────────────────┘
```

- **"调: -3"** 按钮：点击弹出 -12~+12 选择器，D-Pad 上下步进
- **"速: 0.8x"** 按钮：点击弹出 0.5~1.5 选择器，D-Pad 上下步进
- **[快速]/[高质量]** 按钮：切换人声分离模式（独立状态按钮）
- **[原唱/伴奏]** 按钮：切换原唱/伴奏播放
- **[原调]/[原速]** 快速复位按钮

| 设计点 | 箭头音乐（Flutter 手机端） | NASMusicTV（Compose TV） |
|--------|---------------------------|--------------------------|
| 控件类型 | 滑块（`Slider`） | 步进按钮（`FocusableSurface` + −/+），D-Pad 友好 |
| 音高范围 | 独立调节 | -12 ~ +12 半音，步进 1 |
| 速度范围 | 0.5x ~ 2.0x 无级 | 0.5x ~ 1.5x，步进 0.1 |
| 面板位置 | 播放页弹出面板 | K 歌页面底部工具栏内嵌 |
| 独立性 | 音高/速度独立 | 同样独立（ExoPlayer `withPitch`/`withSpeed` 分开设置） |
| 重置 | 有重置按钮 | 有"原调"/"原速"快速复位按钮 |
| 持久化 | 自动记录 | 全局记忆（AppPreferences） |

#### 4.2.3 持久化

```kotlin
// AppPreferences
val pitchSemitones: Flow<Int> = context.dataStore.data.map { it[keyPitch] ?: 0 }
suspend fun setPitchSemitones(semitones: Int) { ... }
```

### 4.3 变速

#### 4.3.1 实现

```kotlin
// PlayerManager.kt
fun setPlaybackSpeed(speed: Float) {
    player?.let { p ->
        p.playbackParameters = p.playbackParameters.withSpeed(speed)
    }
}
```

#### 4.3.2 UI（仅 K 歌页面）

> **参考设计**：箭头音乐（Amcfy Music）采用滑块控件（Flutter `Slider`）无级调节速度（0.5x~2.0x）。NASMusicTV 改为 TV 适配的**步进按钮**设计。

K 歌页面控制栏新增"速"按钮：
- 点击弹出 `0.5x ~ 1.5x` 选择器（步进 0.1）
- 当前值显示在按钮上（如 `速: 0.8x`）
- 1.0x = 原速
- **仅 K 歌页面显示**，普通播放页暂不增加

**UI 设计要点**：
- 用 `FocusableSurface` 实现步进按钮，D-Pad 上下步进调节
- 按钮内显示当前值（如 `速: 0.8x`），点击后弹出选择器
- 选择器为纵向列表（`LazyColumn`），D-Pad 上下导航，中间确认键选择
- 1.0x 快速复位按钮（"原速"）
- 与音高控制独立（可以变速不变调，也可以变调不变速）
- 全局记忆上次设置（AppPreferences）

#### 4.3.3 歌词同步

变速后歌词高亮需要同步：
- `PlayerManager` 的进度轮询已返回 `progressMs`（实际播放位置）
- 变速时 `progressMs` 是 ExoPlayer 的 `currentPosition`（已含变速效果）
- 歌词定位按 `progressMs` 查找，**不需要额外计算**——ExoPlayer 自动处理
- 但歌词逐字高亮的刷新间隔需要调整（变速后实际时间 != 媒体时间）

---

## 5. 任务分解

### 阶段一：升降调 + 变速（优先，简单高价值）

| # | 任务 | 文件 | 预估 |
|---|------|------|------|
| 1 | PlayerManager 新增 setPitch / setSpeed | `PlayerManager.kt` | 1h |
| 2 | AppPreferences 新增 pitch/speed 持久化 | `AppPreferences.kt` | 0.5h |
| 3 | MainViewModel 新增 pitch/speed 状态 + 回调 | `MainViewModel.kt` | 1h |
| 4 | K 歌页面 UI 新增"调"/"速"按钮 + 弹窗 | `KaraokePlaybackScreen.kt` | 3h |
| 5 | 编译 + 测试 | — | 1h |
| | **小计** | | **~6.5h** |

### 阶段二：频谱遮罩快速模式（升级替换 Mid/Side DSP）

| # | 任务 | 文件 | 预估 |
|---|------|------|------|
| 6 | SpectralMaskProcessor：STFT + 自适应遮罩实现 | `SpectralMaskProcessor.kt` | 4h |
| 7 | 替换 VocalRemovalProcessor 为 SpectralMaskProcessor | `PlayerManager.kt` / `PlaybackService.kt` | 1h |
| 8 | 编译 + 测试对比效果 | — | 1h |
| | **小计** | | **~6h** |

### 阶段三：Spleeter ONNX 高质量模式

| # | 任务 | 文件 | 预估 |
|---|------|------|------|
| 9 | 下载 Spleeter 模型 + ONNX 转换 + FP16 量化 | 本地 + GitHub | 2h |
| 10 | 集成 ONNX Runtime Android 依赖 | `build.gradle.kts` | 0.5h |
| 11 | 模型文件放入 assets/spleeter/ | `assets/spleeter/` | 0.5h |
| 12 | SpleeterDsp：STFT/iSTFT/Wiener 归一化 | `SpleeterDsp.kt` | 4h |
| 13 | SpleeterSeparator：ONNX 推理 + 信号重构 | `SpleeterSeparator.kt` | 4h |
| 14 | AccompanimentCache：伴奏缓存 + 预分离队列 | `AccompanimentCache.kt` | 3h |
| 15 | PlayerManager 集成：高质量模式切换 + 预分离触发 | `PlayerManager.kt` | 2h |
| 16 | K 歌页面 UI：快速/高质量模式切换按钮 + 进度条 | `KaraokePlaybackScreen.kt` | 2h |
| 17 | 设置页：默认分离模式选项 + 伴奏缓存管理 | `SettingsScreen.kt` | 1.5h |
| 18 | AppPreferences：默认模式持久化 | `AppPreferences.kt` | 0.5h |
| 19 | 编译 + TV 实测 + 性能调优 | — | 4h |
| | **小计** | | **~23h** |

### 总预估

| 阶段 | 工时 |
|------|------|
| 升降调 + 变速 | ~6.5h |
| 频谱遮罩快速模式 | ~6h |
| Spleeter 高质量模式 | ~23h |
| **合计** | **~35.5h** |

---

## 6. 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| TV 盒子性能不足，ONNX 推理太慢 | 分离延迟 > 10s | 性能检测：推理时间 > 10s 提示用户用快速模式 |
| APK 体积增加 24MB | 用户下载意愿降低 | ONNX Runtime AAR + 模型文件共 24MB，可接受；若太大改为首次使用时下载 |
| ONNX Runtime 兼容性 | 部分 TV 盒子崩溃 | 提供 ARM64/ARMv7 两套预编译库；崩溃时回退频谱遮罩快速模式 |
| 伴奏文件占存储空间 | 用户存储不足 | LRU 淘汰 500MB 上限 + 设置页清除按钮 |
| 预分离时机不当 | 浪费 CPU/存储 | 仅在播放进度 > 50% 且剩余 < 30s 时触发，后台协程不阻塞播放 |
| 变速后歌词不同步 | 歌词高亮错位 | ExoPlayer 的 currentPosition 已含变速效果，进度轮询直接用 |
| 升降调后音质下降 | 高频失真 | Sonic 算法已有成熟处理，范围限制 -12 ~ +12 半音 |
| Spleeter 模型对非 44100Hz 音频崩溃 | 采样率不匹配 | 输入前重采样到 44100Hz |
| 频谱遮罩效果不如预期 | 快速模式仍不够好 | 频谱遮罩比 Mid/Side DSP 质量提升明显（⭐⭐→⭐⭐⭐），但若仍不满意可引导用户用高质量模式 |

---

## 7. 变更文件清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `build.gradle.kts` | 修改 | 新增 ONNX Runtime 依赖 |
| `app/src/main/assets/spleeter/vocals.fp16.onnx` | **新增** | 人声模型（打包在 APK 内） |
| `app/src/main/assets/spleeter/accompaniment.fp16.onnx` | **新增** | 伴奏模型（打包在 APK 内） |
| `player/SpectralMaskProcessor.kt` | **新增** | 快速模式：STFT + 自适应遮罩（替换 VocalRemovalProcessor） |
| `player/SpleeterDsp.kt` | **新增** | Spleeter 专用 STFT/iSTFT/Wiener DSP 层 |
| `player/SpleeterSeparator.kt` | **新增** | 高质量模式：ONNX 推理 + 信号重构 |
| `player/AccompanimentCache.kt` | **新增** | 伴奏文件缓存管理 + 预分离队列 |
| `player/PlayerManager.kt` | 修改 | setPitch/setSpeed + 双模式切换 + 预分离触发 |
| `player/PlaybackService.kt` | 修改 | SonicAudioProcessor + SpectralMaskProcessor 注册 |
| `ui/components/KaraokePlaybackScreen.kt` | 修改 | 快速/高质量模式切换 + 调/速按钮 + 进度条 |
| `ui/viewmodel/MainViewModel.kt` | 修改 | pitch/speed 状态 + 分离任务调度 + 预分离触发 |
| `data/prefs/AppPreferences.kt` | 修改 | pitch/speed + 默认分离模式持久化 |
| `ui/screens/SettingsScreen.kt` | 修改 | 默认分离模式选项 + 伴奏缓存管理 |
| `proguard-rules.pro` | 修改 | keep ONNX Runtime 相关类 |
| `CHANGELOG.md` | 修改 | 记录变更 |

---

## 8. 备选方案

### 8.1 华为音频编辑服务（HMS）

如果目标设备有 HMS 生态，可集成华为音频编辑服务的音源分离 SDK：
- 质量高、近实时
- 无需自带模型文件
- **但**：依赖 HMS 生态，非华为设备不可用，TV 盒子大多没有 HMS

### 8.2 服务端分离

道理鱼音乐后端已内置 FFmpeg，未来可集成 Spleeter/Demucs：
- 客户端零负担
- 需要后端开发者配合
- 非道理鱼/飞牛后端无法使用

### 8.3 Demucs（未来升级）

如果 Spleeter 质量不够且设备性能允许，可升级到 Demucs HDemucs 模型：
- 4 源分离（人声/鼓/贝斯/其他），质量 SOTA
- 模型 ~80MB，端侧推理更慢
- 适合未来高性能 TV 盒子（6GB+ RAM + NPU）
