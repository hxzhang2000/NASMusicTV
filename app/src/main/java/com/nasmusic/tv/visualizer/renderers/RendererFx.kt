package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.VisualizerRenderer
import com.nasmusic.tv.visualizer.fx.FxBudget
import com.nasmusic.tv.visualizer.fx.FxLevel
import com.nasmusic.tv.visualizer.fx.OverlayFx
import com.nasmusic.tv.visualizer.fx.ProceduralTexture

/**
 * 渲染器基类（模板方法）。
 *
 * ## 为什么要有它（§四 G13）
 * 29 个渲染器原本**全部直接实现 [VisualizerRenderer]** ⇒ 后处理 / `dt` 时钟 / 资源释放
 * 三类共性问题各写各的：grain tile 逐字重复 2 份、vignette 手写 3 份、`dt` 钳制 6 种写法。
 *
 * ## 三条**语法级**保证（子类绕不过）
 * 1. [draw] 是 `final` ⇒ 子类只能实现 [drawContent]，**无法漏调后处理**；
 * 2. `dt` 只能从 [FrameClock] 取 ⇒ **无法自己拿 `ctx.nowMs` 算差**（§四 G13 重复 ⑥）；
 * 3. [onExit] 是 `final` 且内部 `release()` ⇒ **无法漏释放**共享纹理（§九 R2）。
 *
 * ## 迁移的零风险性
 * [postFx] 默认 [PostFx.NONE] ⇒ 迁移后**画面逐像素不变**；观感改动与迁移解耦。
 *
 * ## ⛔ 不继承本类的两类
 * - **View 型**（`isViewBased = true`，如 `WorldGlobeRenderer`）：`draw` 根本不被调用；
 * - **后处理与内容交错**的（`VintageTvRenderer`：扫描线在背景之后、vignette 在歌词之后，
 *   不是"末尾一次"）—— 且其画面已定稿（§C4）。
 */
abstract class RendererFx : VisualizerRenderer {

    /**
     * 后处理配置。**默认全关** ⇒ 迁移后画面逐像素不变。子类覆写即为"有意改动观感"。
     *
     * ⚠️ `internal`（原 `protected`）：单测需要**直接读到 `postFx` 本体**才能给
     * "某个效果确实把参数接上了"这类判据把门（`MatrixRainTest` ⑨）。
     * Kotlin 里 override 不写可见性即沿用被覆盖成员的可见性 ⇒ 21 处子类无需改动。
     */
    internal open val postFx: PostFx get() = PostFx.NONE

    /** 效果主体。子类**只实现这个**，不再实现 [draw] */
    protected abstract fun DrawScope.drawContent(
        frame: AudioFrame,
        ctx: RenderContext,
        fx: FxFrame,
    )

    /**
     * 本帧**暗角强度**覆盖（§八 `energy → 暗角` 这类"后处理也要跟着音频走"的需求）。
     *
     * 默认 [NO_VIGNETTE_OVERRIDE] ⇒ 沿用 [postFx] 的静态值，**其余 21 套效果逐像素不变**
     * （与 [PostFx.vignetteEdge] 的 `null` 默认同一个套路：加一条默认关闭的通道，
     * 而不是把 `postFx` 改成每帧新建 —— 后者会让 `FxCoverageScanTest` 的数字字面量判据失效）。
     *
     * ⚠️ 覆写它的效果必须**同时**保留 `postFx` 里的字面量数值：那个数是
     * ① [FxCoverageScanTest] 认定"本效果走了基类后处理"的唯一依据，
     * ② [needsDamageCoalescer] 的输入（脏区合并的账），
     * ③ 静默帧的锚（覆盖值必须复现它，否则"接入调制"会顺手改掉静音时的观感）。
     * ⛔ 不要因为"反正逐帧覆盖"就把 `postFx.vignette` 写成 0f 或具名常量。
     */
    protected open fun vignetteOverride(fx: FxFrame): Float = NO_VIGNETTE_OVERRIDE

    // ── 模板方法（final ⇒ 不可绕过）──────────────────────────────

    final override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        // ① 时钟：只接受 frame.timeMs，内部算差 + 钳上限（照抄 PhotoTransitionClock 的正确做法）
        val fx = clock.advance(frame)
        fx.level = FxBudget.of(ctx.quality)
        // ② 效果主体
        drawContent(frame, ctx, fx)
        // ③ 后处理：postFx 为 NONE 或档位 OFF 时**整段零开销**（不进入任何 draw 调用）
        if (fx.level != FxLevel.OFF) applyPostFx(ctx, fx, postFx)
        // ④ 脏区合并占位（§11.3.6 P-3）：本帧没有任何全屏绘制项时补一次不可见全屏 drawRect，
        //    否则 LOW 档大量碎矩形会把系统 HWUI 的 Region 处理推进原生 SIGSEGV
        if (needsDamageCoalescer(fx.level, postFx)) with(OverlayFx) { drawDamageCoalescer() }
    }

    final override fun onEnter(ctx: RenderContext) {
        clock.reset()                 // 尺寸/时长状态一并复位
        onEnterContent(ctx)
    }

    final override fun onExit() {
        onExitContent()
        OverlayFx.release()           // ⛔ 共享纹理必须释放（API 22–25 位图在 native 堆）
        // H4 修复（2026-10-06）：ProceduralTexture 在效果切离时释放——旧实现生产代码
        // 零调用点，全屏位图（6 张 1080p ARGB ≈ 47MB）在 native 常驻且无自愈路径。
        // 时序前提（当前成立）：生产路径 crossfade 恒为 false（VisualizerStage 硬切），
        // 时序为 fresh.onEnter → old.onExit → 新渲染器首帧 drawContent 才 ensure，
        // 故此处释放不会波及新渲染器已烘纹理。⚠️ 若日后开启 crossfade：淡出结束
        // （RendererSwapper.advance）时旧渲染器 onExit 晚于新渲染器首帧 ensure，
        // 需把本释放点移到 RendererSwapper 层并保证在新渲染器重烘之后；好在逐槽
        // 记账修复（ensure 对空槽会重烘）下，最坏情况也只是缺一帧后自愈。
        ProceduralTexture.release()
    }

    /** 子类自己的 onEnter（**不要**再写 `override fun onEnter`） */
    protected open fun onEnterContent(ctx: RenderContext) {}

    /** 子类自己的 onExit（**不要**再写 `override fun onExit`） */
    protected open fun onExitContent() {}

    // ── 基类提供的公共设施（子类直接用，不要再各声明一份）──────────

    /** 每渲染器一个实例，零分配（原本 9 处各自 `private val rng = VisualizerRandom()`） */
    protected val rng = VisualizerRandom()

    /** `(w, h)` 双键缓存 —— §C4 O2 那 2 处 bug 的**根治手段** */
    private val sizeCache = SizeCache()

    private val clock = FrameClock()

    /** 末尾一次性后处理（vignette / grain / scanline，按 [postFx] 配置；任一为 0 即跳过） */
    private fun DrawScope.applyPostFx(ctx: RenderContext, fx: FxFrame, postFx: PostFx) {
        with(OverlayFx) {
            if (postFx.vignette > 0f) {
                // ⭐ 子类可用 vignetteOverride 逐帧改暗角强度（E44 §八 `energy → 暗角`）；
                //    默认 NaN ⇒ 走 postFx 静态值，其余效果逐像素不变。0f 也**算有效覆盖**
                //    （= 本帧不画暗角），所以判据是 isNaN 而不是 > 0。
                val v = vignetteOverride(fx)
                val strength = if (v.isNaN()) postFx.vignette else v
                if (strength > 0f) drawVignette(ctx, strength, edgeOverride = postFx.vignetteEdge)
            }
            if (postFx.grain > 0f) drawGrain(ctx, fx.seq, postFx.grain)
            if (postFx.scanline > 0f) drawScanlines(ctx)
        }
    }

    companion object {
        /**
         * [vignetteOverride] 的"不覆盖"哨兵。
         *
         * ⚠️ 刻意用 **NaN** 而不是 `-1f` / `0f`：`0f` 是**有效值**（"本帧不画暗角"），
         * 拿它当哨兵就等于把"关掉后处理"这个动作变成不可表达 —— 而 `NaN` 与任何强度比较都
         * 不成立，天然只能靠 [Float.isNaN] 判，误用（直接拿去比大小）会当场暴露。
         */
        internal val NO_VIGNETTE_OVERRIDE: Float = Float.NaN

        /**
         * 本帧是否需要补一次**脏区合并占位绘制**（§11.3.6 P-3，纯函数供门禁直接验证）。
         *
         * 成立条件：**这一帧确定不会发生任何全屏绘制** ——
         *  · 档位 `OFF`（LOW）⇒ [applyPostFx] 整段跳过，即使 `postFx` 配了全屏项也不会画；
         *  · 或 `postFx` 三项全 0 ⇒ [applyPostFx] 进了也是零 draw。
         * 反之（LITE/FULL 且至少一项全屏）由 [OverlayFx] 的那次 `drawRect` 天然把脏区并掉，
         * ⛔ 不要再补一次，白白多一遍全屏填充。
         */
        internal fun needsDamageCoalescer(level: FxLevel, postFx: PostFx): Boolean =
            level == FxLevel.OFF || !postFx.hasFullScreenPass()
    }
}

/**
 * 后处理配置。字段名与 [OverlayFx] 的参数一一对应。
 *
 * ⚠️ 默认全关 ⇒ 迁移后画面逐像素不变。
 */
data class PostFx(
    val vignette: Float = 0f,      // 0 = 关
    val grain: Float = 0f,         // 0 = 关
    val scanline: Float = 0f,      // 0 = 关
    /**
     * 暗角边色覆盖（§11.3.6 P-2）。`null` = 沿用封面 `palette.accent`（20 套效果的既有行为）。
     *
     * 非 null 用于**有固定身份色**的效果：accent 暗角会随换歌漂移，把整幅画面染成封面色
     * —— 数字雨实测被蓝紫封面压成蓝紫底，"黑客帝国"感全失（`vignette = 0.50` 全屏叠加）。
     */
    val vignetteEdge: Color? = null,
) {
    /**
     * 是否含**整屏绘制**项（§11.3.6 P-3 的判据之一）。
     *
     * 三项在 [OverlayFx] 里各自都以一次 `drawRect(… size = size)` 收尾 ⇒ 只要有一项 > 0，
     * 本帧的脏区就会被并成一整块矩形（这正是 MEDIUM 档 448 个碎 blit 不崩的原因）。
     * ⚠️ 只看"配了没有"，不看档位 —— 档位为 `OFF` 时这些绘制一次都不会发生。
     */
    internal fun hasFullScreenPass(): Boolean = vignette > 0f || grain > 0f || scanline > 0f

    companion object { val NONE = PostFx() }
}

/**
 * 帧时钟 —— **渲染器版 `PhotoTransitionClock`**（`photo/PhotoTransitionClock.kt` 是它的先例）。
 *
 * ⛔ **只接受 [AudioFrame.timeMs]，绝不用 `ctx.nowMs`** —— 后者在 `VisualizerStage`
 * 三个调用点语义不一致（`:165` 墙钟 / `:315`·`:359` 单调毫秒，见 §四 G13 重复 ⑥）。
 *
 * [maxDtMs] 暴露出来是**为了负向自证**（与 `PhotoTransitionClock` 同一手法）：
 * 传一个大值 ⇒ 断言相位会一帧跳到结束 ⇒ 证明钳制那一行不是冗余代码。
 */
internal class FrameClock(private val maxDtMs: Long = MAX_DT_MS) {
    private var initialized = false   // ⛔ 不用 lastMs==0L 哨兵：首帧 timeMs 可能恰为 0
    private var lastMs = 0L
    private val frame = FxFrame()          // 复用单例，零每帧分配

    fun advance(frameIn: AudioFrame): FxFrame {
        val now = frameIn.timeMs
        val dtMs = if (!initialized) 0L else (now - lastMs).coerceIn(0L, maxDtMs)   // ⛔ 上界钳制，方向不可反
        initialized = true
        lastMs = now
        frame.dt = dtMs / 1000f
        frame.nowMs = now
        frame.seq = frameIn.seq
        return frame
    }

    fun reset() { initialized = false; lastMs = 0L; frame.dt = 0f }

    companion object { const val MAX_DT_MS = 100L }   // 与 PhotoTransitionClock 对齐
}

/** 每帧复用单例。⛔ 渲染层不得跨帧持有（`AudioFrame` 的同一红线） */
class FxFrame internal constructor() {
    var dt = 0f
        internal set
    var nowMs = 0L
        internal set
    var seq = 0L
        internal set

    /** 当前档位（由 `FxBudget.of(quality)` 推出，见 §5.4） */
    var level: FxLevel = FxLevel.OFF
        internal set
}

/**
 * `(w, h)` 双键缓存。⛔ **缓存键的维度必须 ⊇ 被缓存对象实际依赖的维度**
 * —— `VintageTvRenderer` 的 `vignetteBrush` 只判 `w` 而 `radius` 依赖 `h`，
 * `rollBandH` 只在 `onEnter` 重置，就是这条没做到的后果（§C4 O2）。
 *
 * ⚠️ [get] 的 `build` lambda 是**构造期**捕获的（每渲染器 1 个实例，**不是每帧**）
 * ⇒ 不违反零分配红线。
 */
internal class SizeCache {
    private var w = -1f
    private var h = -1f
    private var value: Any? = null

    @Suppress("UNCHECKED_CAST")
    fun <T> get(w: Float, h: Float, build: (Float, Float) -> T): T {
        if (value == null || this.w != w || this.h != h) {
            value = build(w, h); this.w = w; this.h = h
        }
        return value as T
    }
}
