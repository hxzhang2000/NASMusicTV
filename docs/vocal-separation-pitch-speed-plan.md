# 人声伴奏分离 + 升降调 + 变速 功能分析方案

> **状态**：v2 — 模型升级为 HT-Demucs FT + 模型与 APK 分离
> **日期**：2026-08-28
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

## 2. 技术方案概要（v2 更新）

### 2.1 人声分离模型：从 Spleeter 升级到 HT-Demucs FT

| 对比项 | Spleeter (2019) | HT-Demucs FT (2026) |
|--------|----------------|---------------------|
| 人声 SDR | 6.9 dB | **9.19 dB**（开源最高） |
| 许可证 | Apache 2.0 | MIT |
| ONNX 官方导出 | ❌ 需自己转换 | ✅ HuggingFace 直接下载 |
| 推荐版本 | — | `htdemucs_ft_vocals_fp16weights.onnx` (166MB) |
| 下载链接 | — | https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx |
| Android 适配 | 需要 STFT/iSTFT DSP 层 | 内置 STFT，输入原始 PCM 即可 |

**结论**：HT-Demucs FT Vocals Specialist 比 Spleeter 人声分离质量高 2.3 dB，且已有官方 ONNX 导出，MIT 许可证与 GPL v3 兼容。

### 2.2 模型与 APK 分离架构

**核心原则**：APK 不内置模型文件，模型在设置页独立下载到设备存储。

| 设计点 | 说明 |
|--------|------|
| APK 体积 | 仅含 ONNX Runtime 引擎 (~14MB .so)，不含模型文件 |
| 模型下载 | 设置页提供下载按钮，从 HuggingFace 下载到 `context.getExternalFilesDir(null)/models/` |
| 模型存储 | 外部存储 `Android/data/com.nasmusic.tv/files/models/htdemucs_ft_vocals.onnx` |
| 快速模式 | 无需模型，SpectralMaskProcessor 实时 DSP，始终可用 |
| 高质量模式 | 仅模型已下载时可用，未下载时按钮置灰 + 提示下载 |
| 模型检查 | `ModelDownloadManager.checkModelExists()` 启动时 + 切模式时检查 |
| 进度显示 | 下载时显示进度条 + 速度 + 百分比 |

### 2.3 双模式设计

| 模式 | 实现 | 延迟 | 质量 | 模型依赖 |
|------|------|------|------|---------|
| **快速模式** | `SpectralMaskProcessor`（STFT + 自适应遮罩） | 零延迟实时 | ⭐⭐⭐ | 无 |
| **高质量模式** | `DemucsSeparator`（HT-Demucs FT ONNX 推理） | 首次 ~60s + 缓存后零延迟 | ⭐⭐⭐⭐⭐ | 需下载 166MB 模型 |

---

## 3. 实施方案

### 3.1 模型下载管理

#### 3.1.1 新增文件

```
player/
├── ModelDownloadManager.kt    # 🆕 模型下载/状态检查/文件管理
├── DemucsSeparator.kt         # 🆕 重命名自 SpleeterSeparator，适配 HT-Demucs
├── DemucsDsp.kt               # 🆕 重命名自 SpleeterDsp，HT-Demucs 专用 DSP
├── SpectralMaskProcessor.kt   # 快速模式（不变）
└── AccompanimentCache.kt      # 伴奏缓存（不变）
```

#### 3.1.2 ModelDownloadManager 设计

```kotlin
class ModelDownloadManager(private val context: Context) {

    companion object {
        // HT-Demucs FT Vocals Specialist (FP16, 166MB)
        // 下载候选 URL：优先国内镜像 hf-mirror.com，失败回退 huggingface.co
        private val MODEL_URLS = listOf(
            "https://hf-mirror.com/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx",
            "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx"
        )
        private const val MODEL_FILENAME = "htdemucs_ft_vocals.onnx"
        private const val EXPECTED_SIZE_BYTES = 166_000_000L  // ~166MB
    }

    /** 模型文件路径 */
    private fun getModelFile(): File {
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        return File(modelsDir, MODEL_FILENAME)
    }

    /** 检查模型是否已下载 */
    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > EXPECTED_SIZE_BYTES * 0.9  // 允许 10% 误差
    }

    /** 获取模型文件路径（已下载时返回，否则返回 null） */
    fun getModelPath(): String? {
        return if (isModelDownloaded()) getModelFile().absolutePath else null
    }

    /** 下载模型（带进度回调） */
    suspend fun downloadModel(
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // 1. 创建 models 目录
        // 2. 下载到临时文件 .download
        // 3. 校验文件大小
        // 4. 重命名为最终文件名（原子操作）
        // 5. 返回成功/失败
    }

    /** 删除模型文件 */
    fun deleteModel(): Boolean {
        return getModelFile().delete()
    }

    /** 获取模型文件大小（MB） */
    fun getModelSizeMB(): Double {
        val file = getModelFile()
        return if (file.exists()) file.length() / (1024.0 * 1024.0) else 0.0
    }
}
```

#### 3.1.3 下载流程

```
设置页 → 点击"下载高质量分离模型"
  ↓
显示下载进度条 + 速度 + 百分比
  ↓
ModelDownloadManager.downloadModel()
  ↓
  ├─ 成功 → 更新 UI 状态 → 高质量模式可用
  └─ 失败 → 显示错误提示 → 重试按钮
```

### 3.2 HT-Demucs 模型适配

#### 3.2.1 输入格式

HT-Demucs FT 输入：
- 格式：立体声 PCM (float32)
- 采样率：44100 Hz
- 分段：每次 7.81 秒（343980 采样点）
- Shape：`[1, 2, 343980]`（batch=1, channels=2, samples=343980）

#### 3.2.2 输出格式

HT-Demucs FT 输出：
- Shape：`[1, 4, 2, 343980]`（4 stems: drums, bass, other, vocals）
- 我们只需要 vocals stem（第 3 行，index=3）
- 输出采样率：44100 Hz

#### 3.2.3 与现有代码的差异

| 现有 SpleeterSeparator | HT-Demucs DemucsSeparator |
|------------------------|---------------------------|
| 输入：单声道 STFT 频谱 | 输入：立体声原始 PCM |
| 模型输入 shape: `[1, 1, numBins, numFrames]` | 模型输入 shape: `[1, 2, 343980]` |
| 输出：vocals + accompaniment 两路 | 输出：4 stems，取 vocals |
| 需要 STFT/iSTFT DSP 层 | HT-Demucs 内部处理，无需外部 STFT |
| N_FFT=4096, HOP=1024 | 无需配置（模型内置） |

### 3.3 K 歌页面 UI（更新）

```
┌──────────────────────────────────────────────────┐
│                  歌词区域                         │
│                                                  │
│          ♪ 前一句歌词（白色预览）                  │
│          ♪ 当前行歌词（黄色高亮逐字推进）           │
│                                                  │
├──────────────────────────────────────────────────┤
│  [快速] [高质量🔒]   调: -3  速: 0.8x  [原唱/伴奏]  │
│  ← 返回    ← 上一首  ▶/⏸  下一首 →               │
└──────────────────────────────────────────────────┘
```

- **[高质量🔒]**：模型未下载时显示锁图标，点击弹出"请先在设置中下载模型"提示
- **[高质量]**：模型已下载时正常显示，点击切换高质量模式
- **[快速]**：始终可用，零延迟

### 3.4 设置页 UI（新增模型管理区）

```
设置页 → 人声分离
├── 分离模式：快速 / 高质量（单选）
├── 高质量分离模型
│   ├── 状态：✅ 已下载 (166MB) / ❌ 未下载
│   ├── [下载模型] / [删除模型] / [重新下载]
│   └── 下载进度：████████░░ 80% (133MB/166MB) 2.1MB/s
└── 伴奏缓存管理
    ├── 缓存大小：2.3GB
    └── [清除缓存]
```

---

## 4. 任务分解

### 阶段一：升降调 + 变速（已完成 ✅）

### 阶段二：频谱遮罩快速模式（已完成 ✅）

### 阶段三：HT-Demucs 高质量模式 + 模型分离

| # | 任务 | 文件 | 状态 |
|---|------|------|------|
| 1 | 新增 ModelDownloadManager | `player/ModelDownloadManager.kt` | 🆕 |
| 2 | 修改 AppPreferences — 模型下载状态 | `data/prefs/AppPreferences.kt` | 🆕 |
| 3 | 重命名 SpleeterSeparator → DemucsSeparator，适配 HT-Demucs | `player/DemucsSeparator.kt` | 🆕 |
| 4 | 重命名 SpleeterDsp → DemucsDsp | `player/DemucsDsp.kt` | 🆕 |
| 5 | 修改 SettingsScreen — 模型下载 UI | `ui/screens/SettingsScreen.kt` | 🆕 |
| 6 | 修改 PlayerManager — 高质量模式检查模型 | `player/PlayerManager.kt` | 🆕 |
| 7 | 修改 MainViewModel — 暴露模型状态+下载操作 | `ui/viewmodel/MainViewModel.kt` | 🆕 |
| 8 | 修改 AppRoot — Settings 作用域传递模型状态 | `ui/components/AppRoot.kt` | 🆕 |
| 9 | 修改 KaraokePlaybackScreen — 高质量按钮根据模型禁用 | `ui/components/KaraokePlaybackScreen.kt` | 🆕 |
| 10 | 修改 build.gradle.kts — 如需要 | `app/build.gradle.kts` | 🆕 |
| 11 | 编译验证（debug + release） | — | 🆕 |

---

## 5. 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| HuggingFace 下载速度慢（中国大陆） | 用户等待时间长/下载失败 | **镜像回退机制**：优先 hf-mirror.com（国内加速），失败后回退 huggingface.co（见 §7） |
| 模型文件损坏 | 推理失败 | 下载后校验文件大小；异常时提示重新下载 |
| TV 盒子存储不足 | 无法下载模型 | 下载前检查可用空间；提示用户清理存储 |
| HT-Demucs 推理太慢 | 首次分离等待 ~60s | 显示进度条；缓存后零延迟；提供快速模式作为备选 |
| ONNX Runtime 兼容性 | 部分 TV 盒子崩溃 | 崩溃时回退到快速模式；已在 ProGuard 中 keep 相关类 |

---

## 6. 变更文件清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `player/ModelDownloadManager.kt` | **新增** | 模型下载/状态检查/文件管理 |
| `player/DemucsSeparator.kt` | **新增** | 重命名自 SpleeterSeparator，适配 HT-Demucs |
| `player/DemucsDsp.kt` | **新增** | 重命名自 SpleeterDsp，HT-Demucs 专用 DSP |
| `data/prefs/AppPreferences.kt` | 修改 | 新增模型下载状态持久化 |
| `ui/screens/SettingsScreen.kt` | 修改 | 新增模型下载 UI |
| `player/PlayerManager.kt` | 修改 | 高质量模式前检查模型 |
| `ui/viewmodel/MainViewModel.kt` | 修改 | 暴露模型状态+下载操作 |
| `ui/components/AppRoot.kt` | 修改 | Settings 作用域传递模型状态 |
| `ui/components/KaraokePlaybackScreen.kt` | 修改 | 高质量按钮根据模型禁用 |
| `CHANGELOG.md` | 修改 | 记录变更 |

---

## 7. 中国大陆镜像下载策略

### 7.1 问题

HuggingFace (`huggingface.co`) 在中国大陆无法直接访问，TV 盒子无法设置系统代理或环境变量，用户无法下载高质量分离模型。

### 7.2 方案：代码级镜像回退

在 `ModelDownloadManager` 中硬编码候选 URL 列表，优先使用 `hf-mirror.com`（国内 HuggingFace 镜像站），失败后回退官方 `huggingface.co`：

```kotlin
private val MODEL_URLS = listOf(
    "https://hf-mirror.com/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx",
    "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx"
)
```

下载流程：依次尝试每个 URL，首个成功即停止；全部失败返回 false。

### 7.3 为什么不用其他方案

| 方案 | 不适用原因 |
|------|-----------|
| `HF_ENDPOINT` 环境变量 | TV 盒子无法设置环境变量 |
| 系统代理 | Android TV 没有全局代理设置入口 |
| 用户手动配置镜像地址 | TV 遥控器输入 URL 体验极差 |
| 自建中转服务器 | 维护成本高，单开发者无法承担 |
| 打包模型到 APK | APK 从 ~20MB 膨胀到 ~186MB，所有用户承担 |

### 7.4 hf-mirror.com 可靠性

- 由国内社区维护的 HuggingFace 镜像，缓存机制：首次请求从 HuggingFace 拉取后缓存
- URL 路径与 HuggingFace 完全一致，只需替换域名
- 支持大文件下载（断点续传）
- 已被多个国内 AI 项目采用（如 ChatGLM、ModelScope 文档推荐）

### 7.5 实现细节

- `downloadModel()` 循环遍历 `MODEL_URLS`，每个 URL 调用 `tryDownloadUrl()`
- `tryDownloadUrl()` 封装单次 HTTP 下载，含连接超时 (15s) + 读取超时 (30s)
- 单个 URL 失败后删除临时文件，继续尝试下一个 URL
- 所有 URL 均失败时，最终返回 false（UI 显示错误 + 重试按钮）
