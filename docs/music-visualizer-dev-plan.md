# NASMusicTV 音乐可视化升级方案（开发规格）

> **版本**：v6.0（可开发状态）
> **日期**：2026-09-11
> **基线**：`versionName 2.29.3` / `versionCode 127` / media3 `1.2.1` / minSdk 22 / targetSdk 34 / Kotlin 2.2.10
> **范围**：① 数据层硬伤修复；② `AudioFrame` 统一契约；③ **20 套效果全量实现（不分期）**；④ 独立全屏舞台；⑤ TV 左右键 / 手机滑动切换
> **前序**：v1.0 否决 WebView；v2.0 根因与技法；v3.0 效果库与交互；v4.0 呼吸感引擎；v5.0 `AudioFrame` 契约

---

## 0. 结论先行

| 项 | 结论 |
|----|------|
| 渲染技术 | **Compose Canvas**（不引入 WebView，§15.1） |
| 数据契约 | `AudioFrame` **单例复用 + 零分配**；渲染层**只读**，无 `barCount` 参数 |
| 效果库 | **20 套全量实现 + 1 个自动导演模式**，不分"首批/备选"（§4） |
| "不好看"主因 | `barCount=96` vs `BAR_COUNT=32` → 右侧 2/3 死掉（①） |
| **呼吸感缺失主因** | ⑨ 归一化分母=当前帧低频峰值（低频柱恒 1.0）+ **⑨-b gamma 压缩 `^0.75` 进一步压扁差异** |
| 本轮新增硬伤 | ⑫ 每帧 3 处数组分配（110KB/s）；⑬ `StateFlow<FloatArray>` 强制重组；⑭ 静音返回空数组；⑮ 枚举兼容（§1.2） |
| 主推效果 | `CIRCULAR_RING` 圆形频谱环（默认主题） |
| 入口 | `PlayerControls.kt` **PlayMode 与 K 歌之间** |
| 工作量 | P0 修硬伤 **1 天**；P0–P5 全量 **16–19 天** |

---

## 1. 现状与根因（核准版）

### 1.1 现有实现

| 层 | 组件 | 关键参数 |
|----|------|---------|
| 采集 | `SpectrumAnalyzer`（`Visualizer` API） | `captureSize` 写死 1024 → 512 bins；回调 **50000µs（20fps）** |
| 映射 | `processFft()` | 512 bins → **32 柱**感知翘曲（20–250Hz 占 20 根） |
| 发布 | `_spectrumData: StateFlow<FloatArray>` | **每帧 new 数组 + emit**（`SpectrumAnalyzer.kt:91`） |
| 渲染 | `VisualEqualizer`（Compose Canvas） | 33ms（30fps），分区 Attack/Release，3 主题 |
| 展示 | `NowPlayingScreen` | 48dp 高，`:477` 传 `barCount = 96` |
| 主题枚举 | `AppSettings.kt:48` `VisualizerTheme` | 仅 3 值：`COLOR_FLOW` / `NEON_PULSE` / `CLASSICAL_WAVE` |

### 1.2 根因：15 项（v6.0 核准，新增 5 项标 🆕）

| # | 问题 | 证据 | 后果 |
|---|------|------|------|
| **①** | **柱数不匹配** | `BAR_COUNT=32` vs `NowPlayingScreen.kt:477` 传 96；`VisualEqualizer.kt:82` 越界填 `0.02` | 🔥 右侧 2/3 是死的 |
| ② | 高度仅 48dp | `VisualEqualizer.kt:128` | 10-foot 不可见 |
| ③ | 无辉光 | 纯色实心矩形 | 扁平 |
| ④ | 无拖尾 | 每帧全清 | 动态生硬 |
| ⑤ | 配色写死 | 硬编码 `0xFF34d399` | 每首歌一个样 |
| ⑥ | 采集 20fps vs 渲染 30fps | 50ms / 33ms | 顿挫 |
| ⑦ | Attack 过冲 | `VisualEqualizer.kt:109` attack=0.96 | "跳"不"弹" |
| ⑧ | 无峰值帽 | 未维护 peak | 缺节奏锚点 |
| **⑨** | **归一化分母=当前帧低频峰值** | `SpectrumAnalyzer.kt:216-217`：`lowBandPeak = result.sliceArray(5..19).max()` | 🔥 低频柱**恒为 1.0**，轻/重鼓点无差别 |
| **⑨-b** 🆕 | **gamma 压缩再压一刀** | `SpectrumAnalyzer.kt:221-222`：`sqrt(x).pow(1.5)` = `x^0.75` | 🔥 轻:重 由 5:1 压到 **3.3:1**——即使修好 ⑨，差异仍被削掉 1/3 |
| **⑨-c** 🆕 | **`runningPeak` 算了没用** | `:186` 已算出全局 AGC 峰值，`:228` 仅用于日志，归一化并未引用 | 半成品。改造成本极低——把分母换成"低频区运行峰值"即可 |
| ⑩ | 时间采样率不足 | 回调 50000µs（20Hz），而 1024 窗口仅覆盖 23ms → 每周期漏 54% 音频 | 鼓点 attack 5–10ms，**整拍漏掉** |
| ⑩-b | captureSize 被写死 | `:68` `if (maxSize >= 1024) 1024` | 支持 2048 的设备被压回，bass 区 bin 数 5→11 白白损失 |
| ⑪ | 分析层与渲染层耦合 | 渲染侧自行指定 `barCount`、自行解释归一化 | ① 复发的结构性原因 |
| **⑫** 🆕 | **每帧 3 处数组分配** | `:148` `FloatArray(512)`、**`:216` `sliceArray(5..19)`**、`:197` `FloatArray(32)` | 约 **2.2KB/帧 × 50fps ≈ 110KB/s** → 持续 GC 抖动，TV 上表现为周期性卡顿 |
| **⑬** 🆕 | **`StateFlow<FloatArray>` 强制重组** | `:91` `_spectrumData.value = processed`（新引用） | `FloatArray` 用引用比较，每帧 emit 必触发重组；且 30fps 全树 diff |
| **⑭** 🆕 | **静音返回空数组** | `:180` `return FloatArray(0)` 且 `runningPeak = 1f` | 渲染层柱数变 0 → **频谱整体消失**；恢复播放时前 1–2s 被 `runningPeak=1f` 压制 |
| **⑮** 🆕 | **主题枚举不兼容** | `AppSettings.kt:48` 现有 3 值；老用户 DataStore 存 `"COLOR_FLOW"` 等 | 新 21 值枚举上线后 `fromKey` 解析失败 → 需明确回落规则 |

---

## 2. 架构设计

### 2.1 分层与数据流

```
┌── L1 采集层 ─────────────────────────────────────────────────┐
│ Visualizer(20ms) / PcmTapProcessor(降级)                      │
│ • 零分配：所有缓冲预分配复用                                   │
└──────────────────────────┬───────────────────────────────────┘
┌── L2 分析层 ──────────────▼───────────────────────────────────┐
│ SpectrumAnalyzer                                              │
│  FFT 幅值 → 感知分桶(64柱) → 低频 AGC(修⑨) → 双通道输出        │
│    ├─ 显示通道：spectrum[]  (gamma ^0.75，保证小信号可见)      │
│    └─ 律动通道：bass/mid/treble/energy (线性，保证动态范围)    │
│ BeatDetector → pulse / beat / bpm                             │
└──────────────────────────┬───────────────────────────────────┘
┌── L3 契约层 ──────────────▼───────────────────────────────────┐
│ SpectrumRepository：唯一写入方，fill(AudioFrame 单例)          │
│ 发布：frameSeq: Long （不是数组！修⑬）                         │
└──────────────────────────┬───────────────────────────────────┘
┌── L4 渲染层 ──────────────▼───────────────────────────────────┐
│ VisualizerStage 读 frameSeq 触发重组 → 直接读 AudioFrame 单例  │
│ VisualizerRenderer.draw(frame) —— 21 个实现，只读             │
└──────────────────────────────────────────────────────────────┘
```

**⑬ 的关键修法**（这一步决定了能不能跑到稳定 30fps）：

```kotlin
// ❌ 现状：每帧新数组 → 必触发重组
private val _spectrumData = MutableStateFlow(FloatArray(0))
_spectrumData.value = processed

// ✅ 改为：只发布帧序号，数据从单例读
private var _frameSeq by mutableLongStateOf(0L)   // 无装箱
internal fun publish() { _frameSeq++ }
val frameSeq: Long get() = _frameSeq
```

渲染侧 `Canvas` 用 `LaunchedEffect` + `withFrameNanos` 驱动绘制循环（不走 Compose 重组），仅 `frameSeq` 变化时读取一次 `AudioFrame`。

### 2.2 `AudioFrame` 契约

```kotlin
package com.nasmusic.tv.visualizer

/**
 * 音频分析层的唯一输出契约。
 *
 * 三条铁律：
 *   ① 全局单例复用 —— 运行期零分配
 *   ② 只有 SpectrumRepository 可写；渲染层只读
 *   ③ 渲染层不得持有引用跨帧使用（需要历史帧的效果自行预分配环形缓冲拷贝）
 */
class AudioFrame(barCount: Int, wavePoints: Int) {

    // ── 显示通道（已 gamma 压缩，保证小信号可见）──────────────
    /** 64 段，0..1。渲染柱长/半径直接用这个 */
    val spectrum: FloatArray = FloatArray(barCount)
    /** 128 点，-1..1 时域波形 */
    val waveform: FloatArray = FloatArray(wavePoints)

    // ── 律动通道（线性、未压缩，保证动态范围）─────────────────
    /** 20–250 Hz 线性能量 0..1 —— 节拍主源 */
    var bass: Float = 0f
    /** 250 Hz–3 kHz */
    var mid: Float = 0f
    /** 3 k–20 kHz */
    var treble: Float = 0f
    /** 全频段线性总能量 */
    var energy: Float = 0f
    /** 8s 滑动均值，段落呼吸用 */
    var sectionEnergy: Float = 0f

    // ── 节拍 ────────────────────────────────────────────────
    /** 本帧命中节拍（仅一帧为 true） */
    var beat: Boolean = false
    /** 0..1 快起慢落脉冲包络 —— 主力律动源 */
    var pulse: Float = 0f
    /** 估算 BPM，0 = 未稳定 */
    var bpm: Float = 0f

    // ── 元信息 ──────────────────────────────────────────────
    var timeMs: Long = 0L
    var seq: Long = 0L

    fun reset() {
        spectrum.fill(0f); waveform.fill(0f)
        bass = 0f; mid = 0f; treble = 0f; energy = 0f; sectionEnergy = 0f
        beat = false; pulse = 0f; bpm = 0f
    }
}
```

> **为什么必须双通道（⑨-b 的对策）**：
> 显示需要 `^0.75` gamma 让小信号看得见；律动需要线性值让强弱差异真实。
> **用同一个值必然二选一失败**——这正是现有实现"看着有反应但就是没劲"的原因。

### 2.3 渲染器接口

```kotlin
interface VisualizerRenderer {
    val theme: VisualizerTheme

    /** 进入效果：分配缓冲、重置状态 */
    fun onEnter(ctx: RenderContext) {}

    /** 每帧绘制。frame 为复用单例，不得跨帧持有 */
    fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext)

    /** 退出：释放 ImageBitmap 等重资源（E8/E13/E20 必须实现） */
    fun onExit() {}
}

data class RenderContext(
    val quality: VisualQuality,
    val palette: CoverPalette,     // 封面取色（T5），未就绪时提供回落色
    val cover: ImageBitmap?,
    val canvasSize: Size,
    val safeAreaPx: Float,         // overscan 安全边距，≥ 5%
    val nowMs: Long
)
```

**分发与生命周期**：

```kotlin
// VisualizerStage 内
val renderer = remember(theme) { theme.createRenderer() }
DisposableEffect(theme) {
    renderer.onEnter(ctx)
    onDispose { renderer.onExit() }
}
```

### 2.4 信号 → 视觉统一映射表

> 21 套效果共用同一套语义，切换时观感才连贯。

| 信号 | 映射到 | 幅度 | 说明 |
|------|--------|------|------|
| `spectrum[i]` | 长度 / 高度 / 半径 | 主体 | 效果骨架 |
| `bass` | 整体缩放、基准半径、旋转速度、粒子重力 | **2–8%** | 最"有劲"的信号 |
| `mid` | 涟漪振幅、饱和度 | 中 | 人声段主驱动 |
| `treble` | 亮度、细闪、粒子速度、拖尾长度 | 小 | 天然抖动大，需平滑 |
| `energy` | 粒子发射量、背景亮度 | — | 与 `bass` 区分：响度 vs 低频冲击 |
| `beat` | 爆环、闪白、烟花、网格外扩 | **瞬时** | 用来"点" |
| `pulse` | 通用脉冲（缩放/辉光） | **6–8%** | 主力律动源，比 `beat` 平滑 |
| `sectionEnergy` | 段落明暗 | ±15% | 8s 尺度 |
| `bpm` | 自转/滚动/推进速度 | — | 对上曲速 |
| `waveform` | 波形曲线 | — | 仅波形类效果消费 |

**纪律（Code Review 检查项）**：
1. 每套效果**至少响应 3 个信号**（推荐 `spectrum` + `bass` + `pulse`）
2. 同一信号跨效果映射到**同类**参数（如 `bass` 一律控"尺度"，不得某处控尺度、某处控颜色）

### 2.5 视觉增强技法 T1–T9

| # | 技法 | 做法 | 成本 |
|---|------|------|------|
| T1 | **拖尾残影** | 不 clear，每帧画 `Color.Black.copy(alpha=0.18f)` 覆盖 | 1 次 drawRect |
| T2 | **三层辉光** | 同形状画 3 层：宽淡 → 窄亮（**不用** `BlurMaskFilter`） | 3× 绘制 |
| T3 | **镜像对称** | 32 柱 → 左右 64 柱 | 0 |
| T4 | **峰值保持帽** | 快升慢降 peak + 顶部 3dp 亮线 | n 次 drawRect |
| T5 | **封面取色** | `androidx.palette:palette-ktx`，切歌时异步一次 + 缓存 | 切歌一次 |
| T6 | **多层视差背景** | 封面低分辨率放大（天然模糊）+ 暗化遮罩 | 低 |
| T7 | **节拍缩放** | 低频驱动 2%–5% 呼吸（**>5% 会眩晕**） | 0 |
| T8 | **参数调优** | attack 0.96→**0.62**、release 0.12→**0.18** | 0 |
| T9 | **叠加发光** | `BlendMode.Plus`（= JS `lighter`） | 0 |

> **T9 必须包离屏层**，否则部分 API 版本退化为 `SrcOver`：
> ```kotlin
> Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
> ```

### 2.6 公共数学工具（`VisualizerMath.kt`）

```kotlin
/** 伪 3D 透视投影：z=0 为屏幕平面，z 越大越小 */
fun project(x: Float, y: Float, z: Float, f: Float, cx: Float, cy: Float): Offset {
    val s = f / (f + z)
    return Offset(cx + x * s, cy + y * s)
}

/** 快起慢落包络：target 突变时瞬时到达，随后按 decay 缓慢回落 */
fun envelope(current: Float, target: Float, decay: Float): Float =
    maxOf(target, current * decay)

/** 0..1 → 高度，带最小可见量 */
fun barHeight(v: Float, maxH: Float, minH: Float = 2f): Float =
    minH + v * (maxH - minH)

/** 封面取色回落：Palette 未就绪时用中性色 */
fun CoverPalette.accentOr(fallback: Color): Color = accent ?: fallback
```

---

## 3. 数据层改造规格

### 3.1 `SpectrumContract`

```kotlin
object SpectrumContract {
    const val BAR_COUNT = 64            // 32 → 64
    const val WAVE_POINTS = 128
    const val CAPTURE_INTERVAL_US = 20_000   // 50000 → 20000（50Hz）
    const val EMIT_INTERVAL_MS = 33L         // 30fps 渲染节流
    const val MIN_AMPLITUDE = 0.02f
}
```

### 3.2 `SpectrumAnalyzer` 逐处改造

| 位置 | 现状 | 改为 |
|------|------|------|
| `:68` | `if (maxSize >= 1024) 1024 else maxSize` | `targetSize = maxSize` |
| `:76-97` | `setDataCaptureListener(..., 50000, false, true)` | `(..., CAPTURE_INTERVAL_US, true, true)`（波形+FFT 双开） |
| `:148` | `FloatArray(numBins)` 每帧 new | 预分配 `magnitudeBuf`，`magnitudes` 复用 |
| `:197` | `FloatArray(BAR_COUNT)` 每帧 new | 复用 `resultBuf` |
| `:216` | `sliceArray(5..19)` 每帧 new | 循环取 `maxOf`，不切片 |
| `:217` | 分母 = 当前帧 `lowBandPeak` | 分母 = **低频运行峰值** `lowRunningPeak`（修 ⑨） |
| `:180` | 静音 `return FloatArray(0)` | 返回**全 0 但长度正确**的数组（修 ⑭） |
| `:221-222` | `sqrt(x).pow(1.5)` 单通道 | **双通道**：`spectrum[]` 用 `^0.75`；`bass/mid/treble` 用线性（修 ⑨-b） |
| `:91` | `_spectrumData.value = processed` | 写入 `AudioFrame` 单例 + `publish()`（修 ⑬） |
| `:227` | 每帧 `Log.d` | 删除或降为 `AppLog.v`（release 已剥离，但 debug 仍拖慢） |

**⑨ 的精确修法**（利用已存在但没用上的 `runningPeak` 机制，成本极低）：

```kotlin
// 新增字段（与现有 runningPeak 并列，专门锚定低频区）
private var lowRunningPeak = 0.05f

// 替换 :216-217
var lowBandPeak = 0f
for (b in 5..19) if (result[b] > lowBandPeak) lowBandPeak = result[b]
// 慢衰减：每帧 0.5% ≈ 3s 时间常数；短时内视为恒定 → 保留瞬时动态
lowRunningPeak = maxOf(lowRunningPeak * 0.995f, lowBandPeak, 0.05f)
val safeDenominator = lowRunningPeak
```

效果对比：

| 场景 | 现状 | 修复后 |
|------|------|--------|
| 重鼓点 | 1.0 | **1.0** |
| 轻鼓点 | 1.0 | **≈0.45** |
| 弱间奏 | 1.0 | **≈0.2** |

> 副作用处理：切歌时重置 `lowRunningPeak = 0.05f`；持续偏低时允许缓慢上浮（已由 `maxOf(..., 0.05f)` 兜底下限）。

**⑨-b 的双通道输出**：

```kotlin
// 显示通道：gamma ^0.75，小信号可见
for (bar in 0 until BAR_COUNT) {
    val n = (result[bar] / safeDenominator).coerceIn(0f, 1f)
    displayBuf[bar] = n.pow(0.75f).coerceIn(MIN_AMPLITUDE, 1f)
}
// 律动通道：线性均值，保留真实强弱（归一化但不再 gamma）
bass   = mean(displayLinear, 0, 31)   // 64 柱下低频区为 0–39，见下表
mid    = mean(displayLinear, 40, 55)
treble = mean(displayLinear, 56, 63)
```

> **64 柱后的频段边界**（原 32 柱边界 ×2）：Bass `0–39`（20–250Hz）／Mid `40–55`（250Hz–3kHz）／Treble `56–63`（3k–20kHz）。

### 3.3 `BeatDetector`

（完整实现见 v5.0 §2.5.3，此处仅列适配点与验收）

```kotlin
class BeatDetector {
    companion object {
        private const val HISTORY = 43          // ≈1s @43fps
        private const val MIN_BEAT_MS = 240L    // 上限 250 BPM
        private const val PULSE_DECAY = 0.90f   // ≈250ms 回落
    }
    fun onFrame(bars: FloatArray, nowMs: Long): BeatInfo
    fun reset()
}
```

**64 柱适配**：`bass = mean(bars, 0, 39)`、`mid = mean(40,55)`、`treble = mean(56,63)`。

> 阈值系数用**方差自适应** `c ∈ [1.15, 1.9]`（强节奏曲自动降阈值防漏拍、平稳曲自动抬阈值防误触）。
> 若实测调优困难，可退化为固定 `1.35`（原方案简化版），但默认用自适应。

**单元测试** `BeatDetectorTest`：

| 用例 | 输入 | 期望 |
|------|------|------|
| 稳定 120BPM | 每 500ms 一个 bass 尖峰，共 20 拍 | 检出 ≥ 18 拍，估值 115–125 BPM |
| 静音 | 全 0 bass | 检出 0 拍 |
| 恒定能量（无节奏） | 恒定 bass=0.5 | 检出 ≤ 1 拍（前 43 帧后应完全不触发） |
| 快歌 180BPM | 每 333ms 一尖峰 | 检出 ≥ 16 拍 |

### 3.4 `SpectrumRepository`（唯一写入方）

```kotlin
class SpectrumRepository(private val contract: SpectrumContract) {
    val frame = AudioFrame(BAR_COUNT, WAVE_POINTS)   // 单例
    private val beatDetector = BeatDetector()

    /** 由 SpectrumAnalyzer 回调，零分配 */
    fun onFft(spectrum: FloatArray, waveform: FloatArray?,
              bass: Float, mid: Float, treble: Float,
              energy: Float, nowMs: Long) {
        System.arraycopy(spectrum, 0, frame.spectrum, 0, BAR_COUNT)
        waveform?.let { System.arraycopy(it, 0, frame.waveform, 0, WAVE_POINTS) }
        frame.bass = bass; frame.mid = mid; frame.treble = treble
        frame.energy = energy
        frame.sectionEnergy = section.update(energy)

        val info = beatDetector.onFrame(spectrum, nowMs)
        frame.beat = info.isBeat
        frame.pulse = info.pulse
        frame.bpm = info.bpm

        frame.timeMs = nowMs
        frame.seq++
        if (nowMs - lastEmitMs >= EMIT_INTERVAL_MS) { lastEmitMs = nowMs; publish() }
    }

    fun reset() { beatDetector.reset(); section.reset(); frame.reset() }
}
```

> **节流策略**：`BeatDetector` 用**全部帧**（50Hz，提升检出率），`publish()` 节流到 33ms（渲染 30fps）。这正是提高采集频率的收益所在。

### 3.5 PCM 降级通道（P5，可选）

部分国产 TV 的 `Visualizer` **绑定成功却持续返回全 0**。

- 新增 `PcmTapProcessor : AudioProcessor`，挂入 `PlaybackService.kt:212` 已有的 `arrayOf(...)`（置于最前，取 EQ 前原始信号）
- `queueInput()` **只做** memcpy 到预分配环形缓冲（< 20µs），**严禁在此做 FFT**（阻塞播放线程 → 爆音）
- FFT 在专用 `HandlerThread`（`THREAD_PRIORITY_BACKGROUND`）每 40ms 执行；自实现 radix-2（N=1024 < 1ms），**不引入 JTransforms**
- 仲裁：`Visualizer` 连续 20 帧全 0 且 `isPlaying` → 切 Tap；两者皆败 → 回落随机动画

> `PlaybackService.kt:205-217` **已正确实现** `DefaultAudioSink.Builder.setAudioProcessors()`，可直接复用。

---

## 4. 效果库：20 套全量实现（不分期）

### 4.0 总表与档位矩阵

```kotlin
enum class VisualizerTheme(
    val displayName: String,
    val tier: Tier,
    val ordinalLabel: String
) {
    IMMERSIVE_BLOOM   ("沉浸辉光",   Tier.BASIC, "01"),
    SONIC_TERRAIN     ("声景山脉",   Tier.BASIC, "02"),
    TUNNEL_FLY        ("隧道穿越",   Tier.BASIC, "03"),
    CIRCULAR_NEBULA   ("环形星云",   Tier.BASIC, "04"),
    CIRCULAR_RING     ("圆形频谱环", Tier.BASIC, "05"),   // ← 默认
    RADIAL_BURST      ("径向星芒",   Tier.BASIC, "06"),
    FREQUENCY_MOUNTAIN("频率山峦",   Tier.BASIC, "07"),
    PARTICLE_STORM    ("粒子风暴",   Tier.ADV,   "08"),
    PARTICLE_GALAXY   ("粒子银河",   Tier.ADV,   "09"),
    MIRROR_KALEIDO    ("万花筒",     Tier.ADV,   "10"),
    GALAXY_SPIRAL     ("星系螺旋",   Tier.ADV,   "11"),
    SPECTRO_WATERFALL ("频谱瀑布",   Tier.ADV,   "12"),
    LIQUID_GRID       ("液态网格",   Tier.ADV,   "13"),
    BEAT_FIREWORK     ("节拍烟花",   Tier.ADV,   "14"),
    LIQUID_RIPPLE     ("液态涟漪",   Tier.ADV,   "15"),
    MATRIX_RAIN       ("数字雨",     Tier.ADV,   "16"),
    CONSTELLATION     ("星座",       Tier.ADV,   "17"),
    MILKDROP_FEEDBACK ("反馈残像",   Tier.ULTRA, "18"),
    PARTICLE_TEXT     ("粒子文字",   Tier.ULTRA, "19"),
    PLASMA_FLOW       ("等离子流场", Tier.ULTRA, "20"),
    AUTO_DIRECTOR     ("自动导演",   Tier.MODE,  "AUTO"),
    ;

    enum class Tier { BASIC, ADV, ULTRA, MODE }
}
```

**画质档位矩阵**：

```kotlin
enum class VisualQuality(
    val barCount: Int, val glowLayers: Int, val trail: Boolean,
    val maxParticles: Int, val gridCols: Int, val gridRows: Int,
    val allowFramebuffer: Boolean
) {
    HIGH  (64, 3, true,  200, 32, 18, true ),
    MEDIUM(64, 2, true,   80, 24, 14, false),   // 默认
    LOW   (32, 1, false,   0, 16, 10, false),
}
```

| Tier | HIGH | MEDIUM | LOW |
|------|------|--------|-----|
| BASIC（7 套） | ✅ | ✅ | ✅（柱数降 32） |
| ADV（10 套） | ✅ | ✅（粒子 80、网格 24×14） | ❌ 禁用 |
| ULTRA（3 套） | ✅ | ❌ 跳过并提示 | ❌ 跳过并提示 |
| MODE（自动导演） | ✅ | ✅（在 BASIC+ADV 内调度） | ✅（仅 BASIC） |

> 低档下切到禁用效果：指示器跳过该位并 Toast「当前画质不支持：反馈残像」。

---

### 4.1 E01 `IMMERSIVE_BLOOM` 沉浸辉光 ★

**概述**：柱状频谱 + 镜像对称 + 倒影 + 三层辉光。最稳妥的通用款，作为效果库的基准实现。

| 映射 | 参数 |
|------|------|
| `spectrum[i]` | 柱高 |
| `bass` | 整体纵向缩放 3% |
| `pulse` | 辉光强度 +40%、柱高 +18% |

| 参数 | 值 |
|------|-----|
| 柱数 | `quality.barCount`（镜像后 ×2） |
| 柱宽 | `w / n * 0.62` |
| 圆角 | `barWidth * 0.4` |
| 拖尾 alpha | 0.18 |
| 倒影高度 | 柱高 × 0.35，alpha 0.25 |

**成本**：64×3 辉光 + 64 倒影 + 64 峰值线 ≈ 320 次绘制，约 3–4ms

---

### 4.2 E02 `SONIC_TERRAIN` 声景山脉 ★★

**概述**：Joy Division《Unknown Pleasures》风格，历史帧构成滚动山脉，摄像机向前飞越。公认最有设计感。

| 映射 | 参数 |
|------|------|
| `spectrum[i]` | 山峰高度 |
| `bass` | 整体起伏幅度 ×(1+bass) |
| `beat` | 最近一行山脊闪白 |
| `pulse` | 摄像机推进速度 |

| 参数 | 值 |
|------|-----|
| 历史帧数 | 40（高）/ 24（中） |
| 行间距 | `h * 0.018` |
| 焦距 `f` | `w * 0.9` |
| 深度步长 | `f * 0.06` |
| alpha | 由远及近 0.25 → 1.0 |

**关键实现**：
1. `ArrayDeque<FloatArray>` 环形，**预分配 40×64**，不得每帧 new
2. 从**远到近**绘制，每行画完**用背景色填充折线下方至画布底** → 自然遮挡，形成山脊线
3. 折线用 `Path` + `cubicTo` 平滑

**成本**：40 次 `drawPath` + 40 次填充，约 3–5ms

---

### 4.3 E03 `TUNNEL_FLY` 隧道穿越 ★（伪 3D，性价比最高）

**概述**：同心环沿 Z 轴迎面飞来。WebGL 霓虹隧道的 2D 平替（§15.2）。

| 映射 | 参数 |
|------|------|
| `bass` | 推进速度 `offset += 1.2 + bass * 6` |
| `spectrum[i]` | 环上局部半径扰动 |
| `beat` | 额外突进 `offset += 8` + 闪白 |
| `treble` | 环线亮度 |

| 参数 | 值 |
|------|-----|
| 环数 N | 24 |
| 基准半径 | `D * 0.30` |
| `maxZ` | `N * 60` |
| 线宽 | 近端 3dp / 远端 0.8dp |
| alpha | `1 - z/maxZ` |

**成本**：24 次 `drawCircle`(Stroke)，约 1–2ms

---

### 4.4 E04 `CIRCULAR_NEBULA` 环形星云 ★

**概述**：中心旋转封面 + 外圈放射条 + 粒子环公转。

| 映射 | 参数 |
|------|------|
| `bass` | 旋转速度 `0.15° + bass*0.9°/帧` |
| `spectrum[i]` | 放射条长度 |
| `pulse` | 放射条外辉光 alpha |
| `treble` | 粒子亮度 |

**参数**：`rCover = D*0.12`（1 圈/30s）、放射条 64 根、粒子环半径 `D*0.34`、粒子 60 个

**成本**：64×2 drawLine + 60 drawCircle，约 2–3ms

---

### 4.5 E05 `CIRCULAR_RING` 圆形频谱环 ★（**默认主题**）

**概述**：中心封面圆 → 频谱条紧贴封面**向外辐射** → 外围细线环 → **长条刺破外圈**。「约束 + 突破」的张力。

```
              ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌              ← 外圈细线环 (rRing)
           ╱      ▌    ╱▌╲      ╲
         ╱    ╱──────────────╲    ╲           ▌ = 刺破外圈的条尖
        │   ╱    ┌────────┐    ╲   │
        │  │     │        │     │  │
        │  │  ●  │  封面  │  ●  │  │          ● = 条起点，紧贴封面外围
        │  │     │        │     │  │
        │   ╲    └────────┘    ╱   │
         ╲    ╲──────────────╱    ╱
           ╲      ▌    ╲▌╱      ╱
              ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
```

**半径参数**（`D = min(w, h)`，1080p 示例）：

| 参数 | 取值 | 1080p | 说明 |
|------|------|-------|------|
| `rCover` | `D × 0.13` | 140 px | 封面圆 |
| `rStart` | `rCover + D × 0.006` | 146 px | 条起点，留 6px 呼吸缝 |
| `rRing` | `D × 0.28` | 302 px | 细线环 1.5–2dp |
| `maxLen` | `(rRing − rStart) × 1.4` | 218 px | **能量 0.71 触环，>0.71 刺破约 20%** |

**z-order（自下而上）**：背景 → 频谱条 → **外圈细线环**（画在条之上，环始终完整，视觉上条从环后穿出）→ 封面圆+光晕

**刺破段差异化**：

| 区段 | 样式 |
|------|------|
| 环内 | 正常宽度 `w`、主题色、带辉光（T2） |
| 环外 | 宽度 `w × 0.6`、**lerp 至白 50%**、无外辉光 → 锐利如针 |

```kotlin
val outerR = rStart + len
if (outerR > rRing) {
    val t = ((rRing - rStart) / len).coerceIn(0f, 1f)
    val split = Offset(cx + cos * (rStart + len * t), cy + sin * (rStart + len * t))
    drawLine(color,  inner, split, w,        cap = StrokeCap.Round)
    drawLine(bright, split, outer, w * 0.6f, cap = StrokeCap.Round)
} else {
    drawLine(color, inner, outer, w, cap = StrokeCap.Round)
}
```

**律动**：环组 `scale = 1 + pulse*0.07`；封面 `rCover ×(1+pulse*0.03)`；自转 `0.15° + bass*0.9°/帧`，`beat` 时 `+2.5°` 顿挫

**成本**：64×2 drawLine + 环 + 封面 ≈ 2–3ms

---

### 4.6 E06 `RADIAL_BURST` 径向星芒 ★

**概述**：从中心放射 N 条线，长度=对应频段能量，末端加圆点。极简但干净。

| 映射 | 参数 |
|------|------|
| `spectrum[i]` | 线长 |
| `bass` | 末端圆点半径 |
| `pulse` | 整体缩放 5% |

**参数**：N=64、基准半径 `D*0.10`、最大长 `D*0.32`、线宽 2–5dp、末端圆点 3–8dp

**成本**：64 line + 64 circle，约 2ms

---

### 4.7 E07 `FREQUENCY_MOUNTAIN` 频率山峦 ★

**概述**：半透明多层填充面积图叠加，极光/山峦感（比 E02 更柔和、更"氛围"）。

| 映射 | 参数 |
|------|------|
| `spectrum[i]` | 层高 |
| `sectionEnergy` | 层数（4–8 层）与整体透明度 |
| `bass` | 层间位移量 |

**参数**：层数 5、层间垂直偏移 `h*0.02`、每层 alpha 0.12、`cubicTo` 平滑闭合填充

**成本**：5 次 `drawPath` 填充，约 2ms

---

### 4.8 E08 `PARTICLE_STORM` 粒子风暴 ★★

**概述**：底部发射器，低频超阈值时批量喷发，受重力、带拖尾。

| 映射 | 参数 |
|------|------|
| `bass` | 发射触发（> 阈值）+ 初速 |
| `energy` | 每帧发射数 |
| `treble` | 横向速度抖动 |
| `beat` | 一次爆发 60 个 |

**性能红线**：`FloatArray(200 * 6)` 平铺（x,y,vx,vy,life,hue）+ 对象池。**严禁 `List<Particle>`**

**档位**：HIGH 200 / MEDIUM 80 / LOW 0（禁用）

---

### 4.9 E09 `PARTICLE_GALAXY` 粒子银河 ★★

**概述**：中心径向发射 + 螺旋初速 + **`BlendMode.Plus` 叠加发光**。与 E08 区别：E08 是底部+重力（瀑布感），E09 是中心+螺旋（星系感）。

| 映射 | 参数 |
|------|------|
| `energy` | 发射数 `energy * 20` |
| `treble` | 速度 `×(1 + treble*3)` |
| `bass` | 向心引力 `vy += bass*0.2`、尺寸 |
| `beat` | 爆发 200 个 + 初速翻倍 |
| `spectrum[i]` | 色相 + 初始方向角 |

**必用 T9**：`BlendMode.Plus` 让重叠处累加出高光——这是"银河感"的来源，零额外成本

**成本**：200 次 drawCircle（Plus 混合），约 3–5ms

---

### 4.10 E10 `MIRROR_KALEIDO` 万花筒 ★★

**概述**：只画 1/8 扇区，`rotate(45°)` 循环 8 次 + 镜像翻转。

| 映射 | 参数 |
|------|------|
| `spectrum[i]` | 扇区内放射条长度 |
| `bass` | 扇区整体缩放 |
| `beat` | 翻转切换（镜像/非镜像交替） |

**参数**：扇区角 45°、扇区内条数 16、拖尾 alpha 0.22

**实现**：`for (k in 0..7) { withTransform({ rotate(k*45f); if (k%2==1) scale(-1f,1f) }) { drawSector() } }`

**成本**：1 份内容 × 8 = 128 次绘制，约 3ms

---

### 4.11 E11 `GALAXY_SPIRAL` 星系螺旋 ★★

**概述**：4 条对数螺旋臂，星点沿臂分布、越远角速度越慢（开普勒感）。

| 映射 | 参数 |
|------|------|
| `spectrum[i]` | 星点亮度/大小 |
| `energy` | 核心亮度与半径 |
| `bpm` | 整体角速度 |

**参数**：臂数 4、`r = a * e^(b*θ)`（a=`D*0.03`、b=0.18）、每臂 80 星点、核心 `D*0.06`

**成本**：320 次 drawCircle，约 4–6ms

---

### 4.12 E12 `SPECTRO_WATERFALL` 频谱瀑布 ★★

**概述**：横轴=频率、纵轴=时间（自上而下滚动），能量→色相。

**性能红线（硬约束）**：**必须**缓存为 `ImageBitmap`，每帧 `drawImage` 整体上移 1px + 底部画新行。
**禁止逐格 `drawRect`**（60×64 = 3840 次调用必崩）。

| 映射 | 参数 |
|------|------|
| `spectrum[i]` | 新行色相/亮度 |
| `bpm` | 滚动速度（1–3 px/帧） |

**参数**：色相映射 低=深蓝(220°) / 中=青绿(160°) / 高=品红(300°)；缓冲 `ImageBitmap(w, 240)`

**`onExit()` 必须 `bitmap.recycle()`**（否则切换效果后泄漏）

**成本**：1 次全屏 drawImage + 64 次 drawRect（仅新行），约 2–3ms

---

### 4.13 E13 `LIQUID_GRID` 液态网格 ★★

**概述**：网格顶点被三频正弦叠加推动，形成液体表面（波纹 shader 的 2D 离散平替）。

| 映射 | 参数 |
|------|------|
| `bass` | 大波浪 `sin(x*0.3 + t*0.002) * bass * 30` |
| `mid` | 中涟漪 `sin(y*0.5 + t*0.003) * mid * 20` |
| `treble` | 细抖动 `sin((x+y)*0.8 + t*0.01) * treble * 8` |
| `beat` | 网格外扩 `scale += 0.06`（250ms 回落） |

**密度（唯一风险点，必须按档位）**：

| 档位 | 网格 | 顶点 | 绘制 |
|------|------|------|------|
| HIGH | 32×18 | 576 | 点 + 横纵连线 |
| MEDIUM | 24×14 | 336 | 点 + 横纵连线 |
| LOW | 16×10 | 160 | **仅点，不连线** |

**降级规则**：实测超预算时，优先"只画点不连线"（视觉损失小）

**成本**：中档约 336 circle + 600 line，约 5–8ms（**本套是 ADV 中最贵的，优先优化对象**）

---

### 4.14 E14 `BEAT_FIREWORK` 节拍烟花 ★★（强烈推荐）

**概述**：**刻意"留白"**——安静时近乎空屏，鼓点一到炸开满屏。静动反差是冲击力最强的手法，且平时极省电。

| 映射 | 参数 |
|------|------|
| `beat` | 触发爆炸，方向由 `argmax(spectrum[0..39])` 决定 |
| `bass` | 数量 `120 + bass*80`、初速 `5 + rand*15*(1+bass)` |
| `treble` | 拖尾长度 |
| `spectrum[i]` | 爆炸色相 |

**常态**：极暗背景 + 频谱底纹 alpha ≤ 0.15

**风险与兜底**：无鼓点曲会长时间空屏 → 接 §3.3 兜底呼吸；**连续 6s 无 beat 自动补发一簇弱烟花**（数量 ×0.3）

**成本**：峰值 200 粒子；非节拍帧近乎零

---

### 4.15 E15 `LIQUID_RIPPLE` 液态涟漪 ★★

**概述**：低频产生大波纹、高频产生小涟漪，多组同心圆扩散叠加（与 E13 网格版互补，更"水"）。

| 映射 | 参数 |
|------|------|
| `bass` | 每 N 帧生成一个大波纹（半径增长快、线宽粗） |
| `treble` | 每帧生成小涟漪（半径增长慢、线细） |
| `beat` | 一次性生成 3 个同心波 |

**参数**：波纹池 24 个、生命周期 2.5s、alpha 随半径衰减、最大半径 `D*0.5`

**成本**：24 次 drawCircle(Stroke)，约 2ms

---

### 4.16 E16 `MATRIX_RAIN` 数字雨 ★★

**概述**：字符列下落，速度/亮度/色彩由该列绑定频段能量驱动。

| 映射 | 参数 |
|------|------|
| `spectrum[i]` | 该列速度、亮度 |
| `bass` | 新字符生成率 |
| `beat` | 随机若干列"刷新"为亮白 |

**参数**：列数 = `barCount`、每列 20 字、字符池 `アイウエオカキクケコ...0-9`、字号 14–20sp

**性能红线**：`drawText` 是重操作 → **预渲染字符到 `ImageBitmap` 图集**，用 `drawImage` 绘制；禁止每字符 `drawText`

**成本**：64 列 × 20 = 1280 次 `drawImage`，偏高 → **中档降为 32 列 × 14 字**

---

### 4.17 E17 `CONSTELLATION` 星座 ★★

**概述**：节拍生成星点，邻近星点自动连线，随时间淡出。

| 映射 | 参数 |
|------|------|
| `beat` | 生成 3–5 个星点（位置由 `spectrum` 决定） |
| `energy` | 连线距离阈值 `40 + energy*60` |
| `pulse` | 星点亮度 |

**参数**：星点池 80、生命周期 6s、连线 alpha 随距离衰减

**成本**：80 circle + O(n²) 连线（80² = 3200 次距离计算，**仅计算不绘制**，实际连线约 100 条），约 3ms

---

### 4.18 E18 `MILKDROP_FEEDBACK` 反馈残像 ★★★

**概述**：把上一帧缩放/旋转/平移后回绘，再叠加当前频谱 → 无限递归流光。经典 Winamp MilkDrop。

| 映射 | 参数 |
|------|------|
| `bass` | 缩放系数 1.015–1.03 |
| `mid` | 旋转 0.3–0.8°/帧 |
| `beat` | 一次性位移 + 色相跳变 |
| `pulse` | 叠加内容的辉光 |

**关键参数（决定成败）**：

| 参数 | 安全区间 | 越界后果 |
|------|---------|---------|
| 缩放 | 1.015–1.03 | >1.03 迅速糊成一片 |
| 旋转 | 0.3–0.8°/帧 | >1° 眩晕 |
| alpha | 0.88–0.94 | <0.85 残影太快消散；>0.96 累积过曝 |

**实现**：两个 `ImageBitmap` 乒乓；每帧 `drawImage(prev, 放大+旋转+位移, alpha)` → 叠加当前频谱 → 拷回 `prev` → swap。
拷回优先 `GraphicsLayer.record()`，备选 `drawIntoCanvas { nativeCanvas.drawBitmap(...) }`。

**性能红线**：每帧 2 次**全屏 drawImage**，是 TV 填充率杀手
- **仅 HIGH 档**；中低档跳过并提示
- **离屏缓冲降采样至 720p** 再放大回绘（省约 55% 填充，视觉几乎无损）

**`onExit()` 必须 recycle 两个 bitmap**

---

### 4.19 E19 `PARTICLE_TEXT` 粒子文字 ★★★

**概述**：歌名/歌手采样为粒子目标点，`energy` 高时吸向目标、`beat` 时炸散。

| 映射 | 参数 |
|------|------|
| `energy` | 吸附加速度 `0.02 + energy*0.08` |
| `beat` | 随机散开 `±100px` |
| `treble` | 粒子抖动 |
| `pulse` | 粒子尺寸 |

**采样 API 兼容性（minSdk 22 的主要风险）**：

| API | 最低版本 | 可用性 |
|-----|---------|--------|
| `ImageBitmap.readPixels()` | **29** | ❌ minSdk 22 不可用 |
| `Path.getSegment()` | **24** | ❌ 不可用 |
| `Bitmap.getPixel()` | 1 | ✅ **唯一可用** |

**降级方案**：`Canvas.drawText()` 到 `Bitmap` → 逐像素 `getPixel()` 扫描采样（目标 576 点，约 5–15ms）
- **仅在切歌或进入效果时异步算一次**，缓存 `Map<text, FloatArray>`
- 未就绪时先渲染 E01，就绪后淡入切换

**成本**：576 粒子 + 采样一次性开销

---

### 4.20 E20 `PLASMA_FLOW` 等离子流场 ★★★

**概述**：自实现简化 value noise 生成流场，粒子沿流场运动。

| 映射 | 参数 |
|------|------|
| `bass` | 流场振幅 |
| `mid` | 噪声演化速度 |
| `treble` | 粒子速度 |
| `beat` | 流场整体旋转 15° |

**参数**：噪声网格 16×9（双线性插值）、粒子 150、value noise 约 40 行实现

**成本**：16×9 噪声计算（可每 3 帧更新一次）+ 150 粒子，约 4–6ms

---

### 4.21 `AUTO_DIRECTOR` 自动导演（**模式**，非第 21 套效果）

**概述**：按能量自动调度场景。**不是效果**，是指示器最右一个特殊档位。

```kotlin
val scene = when {
    energy > 0.80f && beat -> Scene.EXPLOSION   // 副歌 → E14 烟花 / E09 银河
    energy > 0.50f         -> Scene.TUNNEL      // 主歌 → E03 隧道 / E02 山脉
    else                   -> Scene.RING        // 前奏/间奏 → E05 圆形频谱环
}
```

**滞回（必须，否则疯狂跳变）**：

| 约束 | 值 |
|------|-----|
| 最小驻留 | 切换后 ≥ **8s** 不切换 |
| 阈值回差 | 升档 0.80 / 降档 0.65 |
| 过渡 | 交叉淡入 600ms，**禁止硬切** |

**与手动的关系（明确规则）**：
- 切到「AUTO」档 → 启用
- **手动切任意具体效果 → 立即退出自动档**
- 自动档下按 ←/→ = 退出自动并切到相邻效果（不做"下一个自动场景"）

**分屏布局**：原方案建议的四分屏（左封面歌词/中频谱/右粒子/底历史）**默认不启用**——每区尺寸减半后 10-foot 下细节全丢。仅作为后续可选增强。

---

## 5. 全屏舞台

### 5.1 三层结构

```
┌─────────────────────────────────────────────────┐
│ ③ 前景层（Compose 组件，脱离 30fps 绘制域）      │
│    顶部歌词 / 左下歌曲信息 / 底部控制栏+指示器    │
├─────────────────────────────────────────────────┤
│ ② 效果层（Canvas，VisualizerRenderer.draw）      │
│    E01–E20 之一，只读 AudioFrame                 │
├─────────────────────────────────────────────────┤
│ ① 背景层（复用：模糊封面 + 暗化遮罩 + 视差）     │
└─────────────────────────────────────────────────┘
```

```kotlin
@Composable
fun VisualizerStage(
    song: Song?, isPlaying: Boolean,
    lyrics: Lyrics?, progressMs: Long,
    theme: VisualizerTheme, quality: VisualQuality,
    isTV: Boolean,
    onExit: () -> Unit, onNextTheme: () -> Unit, onPrevTheme: () -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ① 封面背景（低分辨率放大 → 天然模糊）+ 暗化遮罩 ≥ 0.85
        // ② 全屏 Canvas —— safeArea padding ≥ 5%
        // ③ 顶部歌词行（§5.7）
        // ④ 中下部：效果名 Toast（2.5s 淡出）
        // ⑤ 左下：歌名 / 歌手 / 专辑
        // ⑥ 底部：指示器 + 控制栏（3s 自动隐藏）
        // ⑦ 左上（仅手机）：返回按钮
    }
}
```

**绘制循环（不走 Compose 重组）**：

```kotlin
Canvas(Modifier.fillMaxSize()) {
    val frame = repository.frame          // 直接读单例
    with(renderer) { draw(frame, ctx) }
}
// 驱动：LaunchedEffect(Unit) { while(true) { withFrameNanos { invalidateTick++ } } }
```

> 背景暗化 ≥ 0.85：`Modifier.blur` 在 API<31 是 no-op（K 歌页已在用），**不能依赖模糊保证可读性**。

### 5.2 入口按钮

**现状**（`PlayerControls.kt:299-349`）：`Previous → PlayPause → Next → PlayMode → [K歌] → [MTV]`
**目标**：`Previous → PlayPause → Next → PlayMode → [频谱] → [K歌] → [MTV]`

| 文件 | 改动 |
|------|------|
| `PlayerControls.kt` | `ControlButtonsRow` 新增 `showVisualizerButton: Boolean = false`、`onEnterVisualizer: () -> Unit = {}`；在 `onTogglePlayMode` 之后、`showVocalButton` 之前插入 |
| 按钮实现 | 复用 `VocalToggleButton`（与 K歌/MTV 视觉一致）：`label = stringResource(R.string.player_visualizer)`；可前置 `Icons.Filled.GraphicEq` |
| `values/strings.xml` | 新增 `<string name="player_visualizer">频谱</string>`（参照 `player_karaoke`：733） |
| `NowPlayingScreen.kt` | `:314` `ControlButtonsRow` 调用处传入回调 |
| `NowPlayingBranch.kt` | `:110-112` 传 `showVisualizerButton = true`、`onEnterVisualizer = { viewModel.visualizerVM.enterVisualizer() }` |
| 显隐条件 | 与 K 歌一致：**非电台 && 有当前歌曲** |

### 5.3 ViewModel

```kotlin
class VisualizerViewModel : ViewModel() {
    private val _showVisualizer = MutableStateFlow(false)
    val showVisualizer: StateFlow<Boolean> = _showVisualizer.asStateFlow()

    private val _theme = MutableStateFlow(VisualizerTheme.CIRCULAR_RING)
    val theme: StateFlow<VisualizerTheme> = _theme.asStateFlow()

    fun enterVisualizer() { _showVisualizer.value = true }
    fun exitVisualizer()  { _showVisualizer.value = false }

    fun nextTheme() { step(+1); persist() }
    fun prevTheme() { step(-1); persist() }

    /** 步进时跳过当前画质不支持的效果（返回 false 表示无可选项） */
    private fun step(dir: Int) {
        var t = _theme.value
        repeat(VisualizerTheme.entries.size) {
            t = next(t, dir, wrap = true)
            if (isSupported(t, quality.value)) { _theme.value = t; return }
        }
    }
}
```

### 5.4 AppRoot 接入

```kotlin
// :128
val showVisualizer by viewModel.visualizerVM.showVisualizer.collectAsState(initial = false)

// :148 BACK 仲裁（优先级仅次于沉浸模式）
val handler: (() -> Unit)? = when {
    isImmersiveMode.value -> exitImmersive
    showVisualizer -> exitVisualizer          // ← 新增
    showKaraoke    -> exitKaraoke
    showMv         -> exitMv
    ...
}
// :139 LaunchedEffect 依赖数组追加 showVisualizer

// :163 导航栏隐藏
if (!isImmersiveMode.value && !showMv && !showVisualizer) { ... }
```

### 5.5 TV：遥控器左右键

```kotlin
Modifier
    .fillMaxSize()
    .focusable()
    .onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown) {
            when (e.key) {
                Key.DirectionLeft  -> { onPrevTheme(); true }
                Key.DirectionRight -> { onNextTheme(); true }
                Key.DirectionUp    -> { showControls(); true }
                else -> false
            }
        } else false
    }
```

| 问题 | 方案 |
|------|------|
| 左右键与焦点导航冲突 | 控制栏**默认隐藏**（进入 3s 后自动隐藏，照 `KaraokePlaybackScreen.activateControls()`）。隐藏时 ←/→ = 切效果；显示时 = 移动焦点。上/下/OK 唤出控制栏 |
| 拦截时机 | 必须 `onPreviewKeyEvent`（子控件消费前），返回 `true` |
| **焦点黑洞** | 页面**必须**保留 ≥1 个可获焦元素（底部控制栏 `FocusRequester` 默认获焦），否则遥控器完全失灵 |
| BACK | 走 AppRoot 仲裁，页面内不重复处理 |

**切换反馈**：顶部中央显示效果名（`displayName`），2.5s 淡出；底部指示器 `● ○ ○ …`（含 AUTO 档）；新效果 `alpha 0→1` + `scale 0.96→1`，200ms

### 5.6 手机：左右滑动

```kotlin
var totalDrag by remember { mutableFloatStateOf(0f) }
Modifier.pointerInput(Unit) {
    detectHorizontalDragGestures(
        onDragStart = { totalDrag = 0f },
        onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
        onDragEnd = {
            val threshold = 80.dp.toPx()
            when {
                totalDrag >  threshold -> onPrevTheme()   // 右滑 = 上一个
                totalDrag < -threshold -> onNextTheme()   // 左滑 = 下一个
            }
        }
    )
}
```

| 项 | 说明 |
|----|------|
| 阈值 | 80dp；低于阈值视为点击（唤出控制栏） |
| 退出 | 手机无 BACK → **左上角必须放返回按钮**（照 `KaraokePlaybackScreen:380`） |
| 竖屏 | E02 山脉行數降至 24、E13 网格降密度 |

> `isTV` 判定沿用 `packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)`（`AppRoot.kt:96`）

### 5.7 顶部歌词行

```kotlin
/** 二分查找当前歌词行；未开始时返回 -1 */
fun findCurrentLyricLine(lines: List<LyricsLine>, progressMs: Long, offsetMs: Long = 0L): Int {
    if (lines.isEmpty()) return -1
    val t = progressMs - offsetMs
    var lo = 0; var hi = lines.lastIndex; var result = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].time <= t) { result = mid; lo = mid + 1 } else hi = mid - 1
    }
    return result
}
```

| 项 | 设计 |
|----|------|
| 位置 | 顶部居中，`padding(top = 5%)`（安全区内） |
| 单行 | `maxLines = 1` + `TextAlign.Center` |
| 字号 | 26–30sp |
| 可读性 | 白色 + 半透明黑底条（alpha 0.35 圆角）+ 文字辉光 |
| 无歌词 | 回落「歌曲名 — 艺术家」；电台显示电台名 |
| 过渡 | 行变化 `alpha 0→1` + `translationY 8dp→0`，220ms |
| **性能（关键）** | 必须 `derivedStateOf` 隔离，否则被 30fps 带着重组 |
| | `val idx by remember { derivedStateOf { findCurrentLyricLine(lines, progressMs, offset) } }` |

---

## 6. 配置与持久化

### 6.1 DataStore 键

| 键 | 类型 | 默认 | 说明 |
|----|------|------|------|
| `visualizer_theme` | String | `"CIRCULAR_RING"` | 当前主题枚举名 |
| `visualizer_quality` | String | `"MEDIUM"` | 画质档位 |
| `visualizer_quality_auto` | Boolean | `false` | 是否曾被自动降级（用于避免重复提示） |

### 6.2 枚举兼容（⑮）

老用户 DataStore 存的是 `COLOR_FLOW` / `NEON_PULSE` / `CLASSICAL_WAVE`，新枚举无此三值 → `fromKey` 必须回落：

```kotlin
companion object {
    /** 历史枚举名 → 新主题（避免老用户启动后主题失效） */
    private val LEGACY_MAP = mapOf(
        "COLOR_FLOW"     to CIRCULAR_RING,
        "NEON_PULSE"     to IMMERSIVE_BLOOM,
        "CLASSICAL_WAVE" to SONIC_TERRAIN,
    )
    fun fromKey(key: String?): VisualizerTheme =
        entries.find { it.name == key }
            ?: LEGACY_MAP[key?.uppercase()]
            ?: CIRCULAR_RING            // 兜底
    fun next(t: VisualizerTheme, dir: Int, wrap: Boolean): VisualizerTheme { ... }
}
```

> 三个旧枚举名可从新枚举中**移除**（改名后 `fromKey` 走 `LEGACY_MAP`），DataStore 老值自动迁移，**无需显式 migration**。

### 6.3 自动降级

连续 30 帧 > 20ms → 自动降一档，写 `visualizer_quality_auto = true`，**仅提示一次**（避免反复打扰）。

---

## 7. 构建配置

### 7.1 依赖（`app/build.gradle.kts`）

```kotlin
// 新增（唯一新增依赖）
implementation("androidx.palette:palette-ktx:1.0.0")
```

> 不引入 WebView、不引入 JTransforms、不引入任何 3D/图表库。

### 7.2 ProGuard（`proguard-rules.pro`）

```
# 可视化：渲染器通过枚举名反射/工厂创建，需保留
-keep class com.nasmusic.tv.visualizer.** { *; }
-keepclassmembers enum com.nasmusic.tv.data.model.VisualizerTheme { *; }
-keepclassmembers enum com.nasmusic.tv.data.model.VisualQuality { *; }
```

> 沿用现有 `-keep data.model` 惯例；R8 曾因 Gson 类型擦除导致 v2.5.1 崩溃，新增枚举务必保留。

---

## 8. 设置页清理

入口按钮已承担全部职责，设置页的频谱开关与主题选择器冗余。

| 文件 | 位置 | 动作 |
|------|------|------|
| `AppSettings.kt` | `:32` `spectrumEnabled` | 删除字段 |
| `AppSettings.kt` | `:48-57` `VisualizerTheme` | **重写**为 21 值（§4.0） |
| `AppPreferences.kt` | `:248` `keySpectrumEnabled`、`:642` 读取、`:1528` 备份写入、`:837` `setSpectrumEnabled()` | 删除 |
| `VisualizerPrefs.kt` | `:14` `setSpectrumEnabled()` | 删除 |
| `MainViewModel.kt` | `:2764-2765` `updateSpectrumEnabled()` | 删除 |
| `PlayerSettingsSection.kt` | `:33` 参数、`:57` 回调、`:85` `SettingSwitch`、`:86-87` 主题选择器 | 删除（**画质档位保留**） |
| `SettingsScreen.kt` | `:156-157`、`:403`/`:425`、`:198-199` | 删除 |
| `SettingsBranch.kt` | `:117-118` | 删除 |
| `NowPlayingScreen.kt` | `:107` 参数、`:471-481` 条件块 | 删除（48dp 小频谱条随之移除） |
| `NowPlayingBranch.kt` | `:128` | 删除 |
| `values/strings.xml` | `settings_spectrum`(337)、`settings_spectrum_desc`(338)、`settings_spectrum_theme`(548) | 删除 |

**连带**：NowPlaying 的 48dp 小频谱条**一并移除**——它正是"不好看"的判定对象，且已由全屏舞台取代。

**保留**：设置页保留「可视化画质」档位（性能选项，非开关）。

**兼容**：`settings_spectrum_enabled` 残留键无害；确认备份/恢复的 Gson **能容忍未知字段**（默认可容忍）。

---

## 9. 性能预算

### 9.1 红线

| 指标 | 目标 |
|------|------|
| 单帧绘制 | **≤ 16ms**（30fps） |
| 每帧对象分配 | **0**（`Paint`/`Path`/`Color` 提到 `remember`；粒子平铺 `FloatArray`；`AudioFrame` 单例） |
| 音频线程分配 | **0** |
| 内存 | 连续 30min 无单调增长 |

### 9.2 分配审计清单（Code Review 必查）

| 位置 | 现状 | 要求 |
|------|------|------|
| `SpectrumAnalyzer:148` | `FloatArray(512)` 每帧 | 预分配复用 |
| `SpectrumAnalyzer:216` | `sliceArray(5..19)` 每帧 | 循环取 max |
| `SpectrumAnalyzer:197` | `FloatArray(32)` 每帧 | 预分配复用 |
| `SpectrumAnalyzer:91` | emit 新数组 | 改帧序号 |
| 各 Renderer | — | 绘制函数内禁止 `new`；历史帧用预分配环形缓冲 |
| 粒子系统 | — | 平铺 `FloatArray` + 对象池，**禁止 `List<Particle>`** |

---

## 10. 测试计划

### 10.1 单元测试（`app/src/test/`）

| 测试类 | 用例 |
|--------|------|
| `BeatDetectorTest` | 120BPM 稳定检出 ≥18/20；静音 0 拍；恒定能量 ≤1 拍；180BPM ≥16/20 |
| `SpectrumAnalyzerTest` | AGC：轻鼓点 ≈0.45、重鼓点 1.0（验证 ⑨）；静音返回**长度正确**的全 0 数组（验证 ⑭）；`captureSize` 取 `maxSize` |
| `FindCurrentLyricLineTest` | 空列表 → -1；未开始 → -1；跨行边界；超长歌词（1000 行）性能 |
| `VisualizerThemeTest` | `fromKey("COLOR_FLOW")` → `CIRCULAR_RING`（验证 ⑮）；未知 key → 默认；`next()` 环绕且跳过不支持档位 |
| `AudioFrameTest` | `reset()` 后全字段归零；数组长度符合契约 |

### 10.2 手工验收（TV 真机必测）

见 §12 验收标准。

---

## 11. 实施计划与 DoD

| 阶段 | 内容 | 预估 | 完成判据（DoD） |
|------|------|------|----------------|
| **P0 修硬伤** | ①柱数契约、⑨低频 AGC、⑨-b 双通道、⑩`captureSize`+20ms、⑫零分配、⑬帧序号、⑭静音全 0、删除 `Log.d` | **1 天** | NowPlaying 频谱全宽响应；轻/重鼓点柱高明显不同；Allocation Tracker 60s 无增长 |
| **P1 契约与骨架** | `AudioFrame`、`RenderContext`、`VisualizerRenderer`、`SpectrumRepository`、`BeatDetector`、`VisualizerTheme`(21)、`VisualQuality`、DataStore、`LEGACY_MAP` | **2 天** | 单测全绿；写死数据的 Mock 效果能渲染；老用户主题正确迁移 |
| **P2 基础 7 套** | E01–E07（BASIC） | **3 天** | 7 套均可渲染且随音乐律动；低档不掉帧 |
| **P3 进阶 10 套** | E08–E17（ADV） | **4–5 天** | 10 套可渲染；E12/E16/E13 性能达标；粒子零 GC |
| **P4 高阶 3 套** | E18 MilkDrop、E19 粒子文字、E20 等离子 | **2–3 天** | 仅高档可用；`onExit` 无泄漏；E19 采样降级路径验证 |
| **P5 舞台与交互** | `VisualizerStage` 三层、入口按钮、VM、AppRoot、TV 左右键、手机滑动、歌词行、指示器、设置页清理 | **3 天** | 全部交互验收通过；BACK 优先级正确 |
| **P6 自动导演 + 降级** | `AUTO_DIRECTOR` 滞回调度、`PcmTapProcessor` 通道 | **1–2 天** | 自动档 8s 内不跳变；`Visualizer` 全 0 时能切 Tap |
| **合计** | P0–P5 | **16–19 天** | |

> **最低可行版本**：**P0 仅 1 天** —— 修完 AGC + 双通道，现有频谱当场会呼吸。
> **推荐首发**：P0 + P1 + E05 圆形频谱环 + P5 ≈ **8 天**。
> **首个动手项**：`AudioFrame` 契约与 `VisualizerRenderer` 接口（P1 前半）——接口钉死后，P2–P4 的 20 套效果可**并行开发、互不阻塞**。

---

## 12. 验收标准

**呼吸感（最高优先级）**
- [ ] 轻鼓点与重鼓点柱高**明显不同**（⑨ AGC 生效）
- [ ] 弱间奏显著回落，不再恒顶格
- [ ] 鼓点瞬间整体脉冲 6–8%，~250ms 平滑回落
- [ ] >140 BPM 不漏拍；慢歌不误触
- [ ] 静音 3s 后转缓慢正弦呼吸，画面不"死"
- [ ] **静音恢复后频谱不消失、不被压制**（⑭）

**视觉**
- [ ] 频谱全宽响应，无贴底直线（①）
- [ ] 切歌后配色随封面变化
- [ ] **20 套效果全部可渲染且随音乐律动**
- [ ] E05：条紧贴封面辐射；能量 >0.71 刺破外圈；外圈环完整不被遮断
- [ ] E14：安静段近乎空屏，鼓点炸开
- [ ] E18（高档）：MilkDrop 递归残影，不糊成一片
- [ ] 切效果时 `bass` 始终控尺度类参数，观感连贯

**交互**
- [ ] 按钮顺序 `… → 播放模式 → 频谱 → K歌 → MTV`
- [ ] TV：←/→ 切效果，2.5s 提示淡出
- [ ] TV：控制栏唤出后 ←/→ 恢复焦点移动
- [ ] 手机：滑动切换（阈值 80dp），短按不误触
- [ ] 手机：左上角返回可退出
- [ ] BACK：优先退出本页，不触发应用退出确认
- [ ] 主题持久化，重启保持；老用户（COLOR_FLOW）正确迁移

**性能**
- [ ] 中档在 1GB RAM 老盒子 ≥30fps，单帧 ≤16ms
- [ ] 高档 + E18 在主流 TV ≥25fps
- [ ] **连续 30min 无爆音 / ANR / 内存单调增长**
- [ ] Allocation Tracker：绘制循环与音频线程**零分配**

**回归**
- [ ] MV / K 歌 / 沉浸模式 BACK 优先级未破坏
- [ ] 同步更新 `CHANGELOG.md` 与 `docs/technical-overview.md` §10

---

## 13. 风险登记册

| 风险 | 等级 | 应对 |
|------|------|------|
| 粒子 GC 导致播放爆音 | **高** | 平铺 `FloatArray` + 对象池；Code Review 必查 |
| 焦点黑洞致遥控器失灵 | **高** | 底部控制栏 `FocusRequester` 默认获焦；`onPreviewKeyEvent` 兜底 |
| 左右键与焦点导航冲突 | **高** | 控制栏 3s 自动隐藏；仅隐藏态拦截 |
| **E18 全屏 drawImage 填充率爆炸** | **高** | 仅高档；离屏降采样 720p；中低档跳过 |
| **E13 网格顶点过多** | 中 | 三档密度表；超预算只画点不连线 |
| **E14 无鼓点曲空屏** | 中 | 兜底呼吸 + 6s 补发弱烟花 |
| **E19 采样 API 不兼容 minSdk 22** | 中 | `readPixels`（API29）/ `getSegment`（API24）均不可用 → 降级 `Bitmap.getPixel()`；异步预热 |
| **E16 `drawText` 过慢** | 中 | 预渲染字符图集 + `drawImage`；中档降列数 |
| **E12 bitmap 泄漏** | 中 | `onExit()` 强制 `recycle()` |
| **E21 自动导演频繁跳变** | 中 | 滞回：8s 驻留 + 0.80/0.65 回差 + 600ms 淡入 |
| **⑮ 老枚举值失效** | 中 | `LEGACY_MAP` 映射 + 兜底默认 |
| 拖尾残影不散 | 中 | 暂停/切歌主动 clear；alpha ≥ 0.12 |
| `Palette` 取色慢致切歌卡顿 | 中 | 异步 + `Map<songId, Palette>` 缓存 |
| `Modifier.blur` 在 API<31 为 no-op | 中 | 暗化遮罩 ≥0.85，不依赖模糊 |
| `BlendMode.Plus` 部分版本退化 | 低 | 包 `graphicsLayer { compositingStrategy = Offscreen }` |
| R8 破坏新增枚举 | 低 | §7.2 `-keep` 规则 |
| 低端 TV 掉帧 | 中 | 三档 + 自动降级 |

---

## 14. 文件清单

**新增**

```
├── visualizer/
│   ├── AudioFrame.kt              # P1 契约（单例，零分配）
│   ├── RenderContext.kt           # P1
│   ├── VisualizerRenderer.kt      # P1 接口
│   ├── VisualizerMath.kt          # P1 投影/包络/HSL
│   ├── BeatDetector.kt            # P1
│   ├── SpectrumContract.kt        # P0
│   ├── SpectrumRepository.kt      # P1（唯一写入方）
│   ├── PeakHoldTracker.kt         # P1
│   ├── CoverPaletteProvider.kt    # P1
│   ├── ParticlePool.kt            # P3（E08/E09/E14/E19 共用）
│   ├── SectionEnergyTracker.kt    # P1（8s 滑动均值）
│   └── renderers/                 # P2–P4（20 套）
│       ├── BloomRenderer.kt / TerrainRenderer.kt / TunnelRenderer.kt
│       ├── CircularNebulaRenderer.kt / CircularRingRenderer.kt   # ★默认
│       ├── RadialBurstRenderer.kt / FrequencyMountainRenderer.kt
│       ├── ParticleStormRenderer.kt / ParticleGalaxyRenderer.kt
│       ├── KaleidoRenderer.kt / GalaxySpiralRenderer.kt / WaterfallRenderer.kt
│       ├── LiquidGridRenderer.kt / BeatFireworkRenderer.kt
│       ├── LiquidRippleRenderer.kt / MatrixRainRenderer.kt
│       ├── ConstellationRenderer.kt
│       ├── MilkdropRenderer.kt / ParticleTextRenderer.kt / PlasmaFlowRenderer.kt
│       └── AutoDirector.kt        # P6 调度器（非 Renderer）
├── ui/
│   ├── components/VisualizerStage.kt
│   └── viewmodel/VisualizerViewModel.kt
└── player/
    ├── PcmTapProcessor.kt / PcmRingBuffer.kt / Radix2Fft.kt   # P6
```

**修改**

| 文件 | 改动 |
|------|------|
| `SpectrumAnalyzer.kt` | `captureSize`=`maxSize`；回调 20ms 双开；零分配；低频 AGC；双通道；静音全 0；帧序号发布 |
| `VisualEqualizer.kt` | 移除 `Log.d`；柱数取 `spectrum.size`；接入 T1–T9 |
| `NowPlayingScreen.kt` | 移除 `barCount=96`（`:477`）；传新回调；移除 48dp 小条 |
| `NowPlayingBranch.kt` | 传 `showVisualizerButton` / `onEnterVisualizer` |
| `PlayerControls.kt` | 新增频谱按钮（PlayMode 之后、K歌之前） |
| `AppSettings.kt` | **重写** `VisualizerTheme`（21 值）；新增 `VisualQuality` |
| `AppPreferences.kt` | 持久化主题 + 画质 + 自动降级标记；删除 `spectrumEnabled` 系列 |
| `AppRoot.kt` | BACK 仲裁 `:148`、导航栏 `:163`、`LaunchedEffect` 依赖 `:139` |
| `values/strings.xml` | 增 `player_visualizer`；删 `settings_spectrum*` |
| `PlaybackService.kt` | P6：注入 `pcmTapProcessor`（`:212`） |
| `build.gradle.kts` | 增 `palette-ktx` |
| `proguard-rules.pro` | 增 visualizer keep 规则 |
| `CHANGELOG.md` / `docs/technical-overview.md` | 按规范同步 |

---

## 15. 附录

### 15.1 为什么不引入 WebView

| 维度 | 分析 |
|------|------|
| 底层渲染 | WebView Canvas 2D **同为 Skia**，与 Compose Canvas 同源同 GPU 后端，无性能优势 |
| 额外开销 | 每帧多出：拼 JSON → 切主线程 → IPC → JS 解析 → GC，**5 次转换** |
| TV 可用性 | 国产 TV 常裁剪 WebView；minSdk 22 对应版本过老，Canvas 无 GPU 加速 |
| 焦点 | WebView 是 D-Pad 焦点陷阱，与现有 `FocusableSurface` 体系冲突 |
| WebGL | 唯一优势，但目标设备拿不到；`RuntimeShader` 需 API 33+ → **两条路都堵** |
| 内存 | WebView 实例约 30–80MB，1GB RAM 盒子上与解码争抢 |

> 原方案 5 处不可编译：`DefaultRenderersFactory.setAudioProcessors()` 不存在、`TeeAudioProcessor`/`AudioBufferSink` 非 media3 可用 API、`FftCalculator` 虚构、`evaluateJavascript` 跨线程会崩。
> **`AudioFrame` 协议本身已完全采纳**——它解决的是分析层与渲染层解耦，与渲染技术无关。

### 15.2 WebGL Shader 的 2D 平替

| Shader 效果 | 2D 平替 |
|------------|---------|
| 隧道辉光 `1/(r+0.1)` | **E03 + T9 `BlendMode.Plus`** |
| 波纹干涉 `sin(a*8+t)` | **E13 液态网格**（数学形式一致，离散到网格点） |
| 无限反馈递归 | **E18 MilkDrop**（帧缓冲乒乓，即原始实现思路） |
| 色相循环 | `Color.hsl(hue + t*0.05, 1f, 0.6f)` 直接等价 |

### 15.3 氛围灯联动（本期不做）

WLED / Hue / Nanoleaf 思路正确，但需网络权限 + 设备发现 UI + 三协议适配，工作量与主方案同量级。**预留接口**：

```kotlin
interface FrameConsumer { fun onFrame(frame: AudioFrame) }
// 未来：WledConsumer / HueConsumer / NanoleafConsumer
// 映射：bass → 红/紫亮度脉冲；mid → 绿/蓝流动；treble → 白闪；beat → 全灯闪白
```

建议独立立项，P0–P5 验收后再启动。

---

## 16. 版本记录

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-09-11 | 初稿。「Kotlin 采集 + WebView 渲染」可行性核查，否决 WebView |
| v2.0 | 2026-09-11 | 根因分析（8 项）、8 项技法、5 套主题、独立全屏页（K 歌模式） |
| v3.0 | 2026-09-11 | 网络流派调研 → 15 套效果库；入口定位于 K 歌左侧；TV 左右键 + 手机滑动 |
| v4.0 | 2026-09-11 | §呼吸感节拍驱动置于最高优先级（⑨ 归一化、⑩ 采样率）；E 圆形频谱环规格；顶部歌词行；设置页清理 |
| v5.0 | 2026-09-11 | 采纳 `AudioFrame` 契约（去 WebView/JSON）；信号映射表；效果扩至 20 套；T9 叠加发光；WebGL 平替 |
| **v6.0** | 2026-09-11 | ① **效果库不分期，20 套全量实现**（§4 含逐套参数表与实现规格）；② **核准并新增 5 项硬伤**：⑨-b gamma `^0.75` 二次压缩、⑨-c `runningPeak` 算了没用、⑫ 每帧 3 处数组分配(110KB/s)、⑬ `StateFlow<FloatArray>` 强制重组、⑭ 静音返回空数组、⑮ 枚举不兼容；③ **双通道输出**（显示 gamma / 律动线性）；④ 补完整开发规格：21 值枚举、画质档位矩阵、DataStore 键、`LEGACY_MAP`、依赖、ProGuard、单测用例、分配审计清单、分阶段 DoD；⑤ 结构重排为工程化规格 |
