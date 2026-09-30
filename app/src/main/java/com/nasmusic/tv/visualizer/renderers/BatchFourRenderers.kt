package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRenderer
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import com.nasmusic.tv.visualizer.fx.Shading2D
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E33 `CONCENTRIC_GEARS` — 同心齿轮系（主组 13 轮 · 三级啮合链 · 随机布局 + 卫星组）
 *
 * 视觉：暗金 + 冷灰线框齿轮系，每个啮合节点上有一粒青色电火花——机械钟表
 * 质感带一点电流感。布局按**恒定模数**（MODULE = 0.024 世界单位/齿）构造三级
 * 啮合链：中心轮（26–34T 随机）→ 内圈 5 轮（12–18T 随机）→ 外圈 7 轮（8–13T 随机）。
 * 齿数 ∝ 节圆半径，任意啮合副齿距一致，中心距 = rp_a + rp_b + 齿隙（0.5×模数），
 * 因此齿/槽在接触点恒定交错。
 *
 * **随机主组（onEnter 换种子 ⇒ 每次进效果一套全新布局；尺寸变化只换 unit、不重掷）**：
 * 齿数、外圈父轮挂载（每个内圈保底 1 个子轮、其余随机撒后再洗牌）、安装角全部由
 * 布局 LCG 生成。安装角只能落在**父轮齿中心栅格**上（这是接触点相位标定的前提），
 * 每个候选槽位都做**兄弟碰撞检查** —— 两子轮中心距 ≥ tip₁+tip₂+齿隙（等价于
 * cos 定理给出的角间隙），外加 reach 上限；单轮 ≤ `PLACE_RETRY`(32) 次重试，
 * 仍失败则走确定性兜底（齿数从区间下限逐档降到 6T × 父轮全栅格扫描，取余量最大的
 * 槽位；原父轮仍放不下时改挂中心轮 / 内圈的稳定槽位）⇒ 不规则的有机感，但绝不穿透、
 * 绝不越界。
 *
 * 运动学（唯一驱动角 `mainAngle`，其余全部由齿数比推导）：
 * ```
 * angle_i = phase_i + ratio_i · mainAngle
 * ratio_i = (±) N_center / N_i   符号 = 外啮合次数奇偶（链深 1→反向、2→同向）
 * phase_i = mod step_i (β_i + π) β_i = 父轮指向该轮的安装角（接触点落齿槽中心）
 * ```
 * 相位按接触点标定、速比按齿数锁定 ⇒ **啮合相位永不漂移**。`mainAngle` 只有
 * 两路合成，且**必须分开累加**：匀速基线 = `BASE_SPEED·elapsed` 绝对求值
 * （TAU/56 ≈ 6.4°/s，帧帧恒正推进、不回退）+ 鼓点棘轮（pulse 上升沿推进一个
 * 中心齿距，120ms 缓动到位；缓动只作用在棘轮**自己的**累加器上、只加不减）。
 * ⛔ 绝不能把棘轮弹簧直接加在含基线的 `mainAngle` 上 —— `ratchetTarget` 里没有
 * 基线，弹簧会把基线每帧减回去，整组在鼓点之间冻成静止（只在鼓点跳一齿）。
 * 子轮没有任何独立调速，整链刚性跟随。`elapsed` 由**渲染时钟**
 * （`SystemClock.uptimeMillis` 逐帧差分）推进，⛔ 不读 `ctx.nowMs` —— 后者是
 * 25Hz 分析线程取样的 `f.timeMs`，暂停/PCM 停摆时冻结会让整组停转。
 *
 * **卫星组（独立小齿轮组，不与主组相接、不共用 mainAngle）**：随机散布在主组外围
 * 空域，组数随档位 LOW 1 / MED 2 / HIGH 4，组内 1–3 齿（模数 SAT_MODULE 0.016，
 * 齿数 6–11）。组内 ≥2 齿复用主组同一套啮合数学（中心距 = rp_a+rp_b+齿隙、
 * 相位标定、速比 = (±)N锚/N齿 ⇒ 反向传动），驱动角却是**组自己的 ω·elapsed**：
 * 纯 elapsed 连续转、**永不停止**（不接棘轮、不接 mainAngle）。每组圈速 20–90s，
 * 两两差 ≥8s 且避开主组 56s ⇒ 各组速率互不相同。锚点与组内成员、组↔组、组↔主组、
 * 组↔表圈全部做 tip 圆碰撞检查（+ 齿隙）。
 *
 * 分档只**裁剪外圈尾部**（外圈生成后按齿数降序 ⇒ 永远先砍最小的外围轮；父轮恒为
 * 中心/内圈，即索引 0–5、永不参与该排序 ⇒ 裁剪后父子索引依然有效）：LOW 8 / MED 11 /
 * HIGH 13 主组齿轮，卫星组
 * LOW 1 / MED 2 / HIGH 4 组；布局本身不变 ⇒ 降档无几何跳变（不再要求对径对称）。
 *
 * 适配（FIT_K 0.43 / WORLD_EXTENT 1.06 / 峰值呼吸 ×1.05）：世界预算
 * `max_world = 0.9×1.06 / (2×0.43×1.05) ≈ 1.0565`。为此主组世界坐标整体 **×0.70**
 * 收缩（原 reach 1.0420 → ≤0.74；等比 ⇒ 中心距/齿隙/相位/速比同比，啮合数学一行
 * 未动），腾出的卫星带半径 0.78–1.038（齿顶不越表圈 1.05，表圈留作外框）。
 * 1080p 短边（半短边 540px，unit = 438.1px）静态→峰值：主组 60.0%→63.0%、
 * 卫星 ≤84.2%→88.4%、表圈 85.2%→89.4%（89.4% 即全图最外缘）⇒ 均 ≤ 90% 安全线；
 * 横竖屏 minDim 同值 ⇒ 两向同数，4K 等比（百分比与分辨率无关）。
 *
 * ## 纹理层（自底向上，绘制顺序即此顺序）
 * 1. **背景空间感**（整屏底层，画在整组缩放之外，全按屏幕尺寸计算）：近黑基底
 *    `#020306` → 中心 `#0B0E15` 极淡径向纵深 → 暗角 vignette（半对角线为半径、
 *    四角 α0.55，纯径向渐变，⛔ 无模糊）→ 颗粒 grain（128/256px 预渲染 tile 位图
 *    `drawImage` 平铺，LOW 省略）→ 固定种子星点（缓慢椭圆漂移、全局微脉动）。
 * 2. **光层**：环境光晕（齿轮群外围一圈径向渐变环）→ 表圈刻度 → 节圆导引 →
 *    **啮合父子轮中心连线**（最弱档，画在齿轮层之前）→ 齿内径向渐变填充 →
 *    **双层描边**（外层宽而淡的金色光晕 + 内层亮金细线）→ 轴毂/辐条 →
 *    **轴心核心光晕**（2 层渐隐同心圆 + 原脉动点，中心轮最亮、向外递减）→ 火花。
 *
 * 性能红线：draw 内零分配。**缓存点**（onEnter / 尺寸变化时建，draw 只读）：
 * 齿廓顶点（主组 13 + 卫星 ≤9）、背景三 Brush（纵深/暗角/环境光）+ 齿内填充 Brush
 * （容量 22 = 13 主组 + 9 卫星）、颗粒 tile 位图、5 个 Stroke（含双层描边外遍）、
 * `gearPath`、星点 FloatArray×5、**布局数组（含 `gearAngVel`）与卫星布局**。
 * ⛔ 布局 LCG（`lr`/`li`）**只在 onEnter 调用**，draw 内一次都不会碰；尺寸变化只重建
 * Brush（`ensureLayout`），不重掷布局、不重置 elapsed/相位/平滑。整组能量
 * 缩放走 `canvas.save/translate/scale/restore`（原生变换，零分配）—— **坐标与
 * 运动学一行未动**，路径与齿内填充 Brush 同处该变换内 ⇒ 两者恒对齐。齿廓顶点
 * onEnter 预生成（5 点/齿：root@0.00/0.22、tip@0.36/0.64、root@0.78 ⇒ 齿槽跨
 * 0.78→1.22，横向余量经 backlash 核验），每帧手算世界坐标烘焙旋转（绝不用
 * withTransform({ rotate })——其默认 pivot=画布中心，会让所有齿轮绕同一固定点
 * 公转）。卫星组的自转角 = `phase + angVel·elapsed`（线性、无缓动、无上限 ⇒ 恒速连续；
 * 与主组基线**共用同一个 `elapsed` 时间基**，同一 dt 钳位、同一归零时机）。
 */
class ConcentricGearsRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.CONCENTRIC_GEARS

    private companion object {
        const val TAU = (2 * Math.PI).toFloat()
        const val MODULE = 0.024f              // 模数（世界单位/齿）
        const val MESH_CLEAR = MODULE * 0.5f   // 齿隙 = 0.012
        const val DEPTH_CAP = MODULE * 1.05f   // 齿厚（单侧）上限 = 0.0252
        const val ROOT_REL = 0.16f             // 小齿齿厚 ≤ 0.16·rp（防细齿）
        const val WORLD_EXTENT = 1.06f         // 世界半径（适配分母，见 KDoc）
        const val FIT_K = 0.43f                // unit = 0.43·minDim / 1.06（峰值×1.05 ⇒ 89.4% 半短边）
        const val RATCHET_MS = 120f            // 棘轮缓动时长
        const val RATCHET_THRESH = 0.18f       // pulse 上升沿阈值
        const val MAIN_PERIOD = 56f           // 主组基线圈速（s/圈）—— 卫星圈速要避开它
        const val BASE_SPEED = TAU / MAIN_PERIOD // 匀速基线：56s/圈 ≈ 0.112 rad/s
        const val SMOOTH = 0.20f               // 音频平滑系数

        // ── 背景空间感（整屏底层；全按屏幕尺寸计算，画在整组缩放之外）──────
        const val BG_BASE = 0xFF020306L        // ① 近黑基底
        const val BG_CORE = 0xFF0B0E15L        // ② 中心深灰蓝（极淡，视觉聚焦/纵深）
        const val BG_CENTER_STOP = 0.40f       // 中心色平台（占半对角线比例）
        const val VIG_START = 0.50f            // ③ 暗角起始半径（占半对角线比例）
        const val VIG_EDGE_ALPHA = 0.55f       //    暗角最外 α（四角最深）
        const val GRAIN_TILE = 128             // ④ 颗粒 tile 边长 px（短边 <1440）
        const val GRAIN_TILE_HI = 256          //    4K：tile 放大 ⇒ 平铺 call 数恒 ≈135
        const val GRAIN_TILE_LARGE_MIN = 1440f //    判 4K 的短边阈值
        const val GRAIN_ALPHA_MED = 0.045f     //    颗粒整体 α（MED）
        const val GRAIN_ALPHA_HIGH = 0.060f    //    颗粒整体 α（HIGH；LOW 省略）
        const val STAR_MAX = 110               // ⑤ 星点数组上限
        const val STAR_DRIFT = 0.012f          //    漂移幅度（归一化屏幅，缓）
        const val STAR_DRIFT_PERIOD = 41f      //    漂移周期 s
        const val STAR_PULSE_PERIOD = 13f      //    全局微脉动周期 s

        // ── energy → 整组缩放 / 发光总强度（独立小 α EMA，慢于 SMOOTH）─────
        const val SCALE_EMA = 0.06f            // groupEnergy 的平滑系数（红线要求 ≈0.06）
        const val SCALE_BASE = 0.990f          // 缩放中值（静音时呼吸中心）
        const val SCALE_BREATH = 0.010f        // 基础呼吸幅度（静音也保持极缓呼吸）
        const val SCALE_BREATH_PERIOD = 9f     // 呼吸周期 s
        const val SCALE_ENERGY_GAIN = 0.050f   // energy → 缩放增益
        const val SCALE_MIN = 0.98f            // 缩放下限
        const val SCALE_MAX = 1.05f            // 缩放上限
        const val GLOW_GAIN_BASE = 0.85f       // 发光总强度 = base + gain·groupEnergy
        const val GLOW_GAIN_ENERGY = 0.55f
        const val GLOW_GAIN_MAX = 1.40f
        const val GLOW_STROKE_BASE = 0.10f     // 外层光晕描边：静态基础 α
        const val GLOW_STROKE_ENERGY = 0.17f   //                     + energy 缓升
        const val GLOW_STROKE_MAX = 0.30f
        const val GLOW_STROKE_W = 4.6f         // 外层光晕描边宽 px（内层仍 1.6/2.2）
        const val AMBIENT_ALPHA_BASE = 0.055f  // 环境光晕 α：静态基础
        const val AMBIENT_ALPHA_ENERGY = 0.075f//                     + energy 缓升
        const val AMBIENT_ALPHA_MAX = 0.135f
        const val AMBIENT_RADIUS = 1.45f       // 环境光半径 × unit（齿轮群外圈）
        const val AMBIENT_INNER = 0.55f        // 渐变内侧透明截止（半径占比，避免糊住中心）
        const val AMBIENT_PEAK = 0.78f         // 渐变峰值（≈ 齿轮群外缘 1.13·unit）
        const val LINK_ALPHA_BASE = 0.045f     // 啮合连线 α：全层**最弱档**
        const val LINK_ALPHA_ENERGY = 0.085f
        const val LINK_ALPHA_MAX = 0.14f
        const val FILL_CENTER_ALPHA = 0.16f    // 齿内填充：中心 α（边缘 0）
        const val FILL_MID_ALPHA = 0.07f       //                中途 α
        const val FILL_MID_STOP = 0.55f        //                中途位置
        const val CORE_RING_ALPHA_OUT = 0.055f // 核心光晕外层 α（× ringBoost × glowGain）
        const val CORE_RING_ALPHA_IN = 0.115f  // 核心光晕内层 α
        const val CORE_RING_R_OUT = 4.6f       // 核心光晕外层半径 × 脉动点半径
        const val CORE_RING_R_IN = 2.4f        // 核心光晕内层半径 × 脉动点半径

        // ── 主组规模 + 随机生成（布局每次 onEnter 重掷；索引顺序 = 绘制顺序 =
        //    降档裁剪顺序：0 中心 / 1–5 内圈 / 6–12 外圈（外圈按齿数降序））──────
        const val N_MAIN = 13                // 主组固定 13 轮（中心 1 + 内圈 5 + 外圈 7）
        const val N_INNER = 5
        const val N_OUTER = 7
        const val CENTER_TEETH_MIN = 26      // 中心轮齿数区间（原固定 30T）
        const val CENTER_TEETH_MAX = 34
        const val INNER_TEETH_MIN = 12       // 内圈齿数区间（原固定 16/15/16/14/15T）
        const val INNER_TEETH_MAX = 18
        const val OUTER_TEETH_MIN = 8        // 外圈齿数区间（原固定 12..9T）
        const val OUTER_TEETH_MAX = 13
        const val PLACE_RETRY = 32           // 单轮随机重试上限（超限 → 确定性兜底）
        const val FALLBACK_ABS_MIN = 6       // 兜底阶梯的齿数绝对下限（仅兜底路径用到）

        // ── 构图：主组收缩 + 卫星带（fit 数学见类 KDoc）────────────────────
        const val MAIN_SHRINK = 0.70f        // 主组世界坐标整体收缩系数（等比 ⇒ 啮合不变）
        const val MAIN_REACH_MAX = 0.74f     // 主组收缩后最外 reach 上限（世界）
        const val MAIN_REACH_RAW = MAIN_REACH_MAX / MAIN_SHRINK  // 生成期上限 ≈1.0571
        const val BEZEL_R = 1.05f            // 表圈半径（世界；峰值 ×1.05 ⇒ 89.4% 半短边）
        const val SAT_MODULE = 0.016f        // 卫星组模数（比主组细 ⇒ 独立小齿轮）
        const val SAT_DEPTH_CAP = SAT_MODULE * 1.05f
        const val SAT_TEETH_MIN = 6
        const val SAT_TEETH_MAX = 11
        const val SAT_R_MIN = 0.78f          // 卫星锚点/成员半径下限（> 主组 reach 0.74）
        const val SAT_MAX_GEARS = 9          // 卫星齿轮总上限（数组容量余量）
        const val SAT_RETRY = 32             // 卫星单轮重试上限（同 PLACE_RETRY 量级）
        const val SAT_SCAN = 72              // 卫星兜底扫描的角步数
        const val SAT_GROUP_LOW = 1          // 各档卫星**组数**
        const val SAT_GROUP_MED = 2
        const val SAT_GROUP_HIGH = 4
        const val SAT_GEAR_CAP_LOW = 2       // 各档卫星**齿数**上限
        const val SAT_GEAR_CAP_MED = 5
        const val SAT_GEAR_CAP_HIGH = SAT_MAX_GEARS
        const val SAT_GEAR_MAX_PER_GROUP = 3 // 单组齿数上限
        const val SAT_PERIOD_MIN = 20f       // 卫星组圈速区间（s/圈，互不相同且不等于 56）
        const val SAT_PERIOD_MAX = 90f
        const val SAT_PERIOD_GAP = 8f        // 圈速最小间隔（s）
        const val SAT_PERIOD_TRIES = 24      // 圈速抽样重试上限（超限 → 下表兜底）
        /** 圈速兜底表（两两差 ≥8s 且均避开 56s；按需取用） */
        val SAT_PERIOD_FALLBACK = floatArrayOf(31f, 47f, 68f, 84f)
    }

    // 暗金 + 冷灰 + 青色火花（固定配色，不用封面色——机械质感自成体系）
    private val gold = Color(0xFFC9A227)
    private val goldDim = Color(0xFF8A7418)
    private val coldGray = Color(0xFF8B95A1)
    private val cyanSpark = Color(0xFF54E0E6)
    // 星点（固定种子 onEnter 生成；每 4 颗一颗偏蓝）
    private val starWhite = Color(0xFF9FB4CC)
    private val starBlue = Color(0xFF6E8AD6)

    // draw 内复用的描边/路径（零分配红线：绝不在 draw 里 new）
    private val strokeBezel = Stroke(1f)
    private val strokeGear = Stroke(1.6f)
    private val strokeGearFat = Stroke(2.2f)   // bass 强时的加粗档（量化切换，免分配）
    private val strokeGlow = Stroke(GLOW_STROKE_W)  // 双层描边的外遍：宽而淡的光晕
    private val strokeHub = Stroke(1.2f)
    private val gearPath = Path()

    // ── 背景 / 光层缓存（ensureLayout：仅画布尺寸变化时重建；draw 只读）──────
    private var bgDepthBrush: Brush? = null    // ② 中心纵深径向渐变（基底之上）
    private var vignetteBrush: Brush? = null   // ③ 暗角（整屏半对角线定界）
    private var ambientBrush: Brush? = null    // 环境光晕：齿轮群外围径向渐变环
    /** 齿内填充 Brush（容量 = N_MAIN 13 + 卫星 9；只画 [0, gearCount) ∪ [N_MAIN, …)） */
    private val fillBrushes: Array<Brush?> = arrayOfNulls(N_MAIN + SAT_MAX_GEARS)
    private var layoutW = -1f                  // 尺寸缓存键（-1 = 待建）
    private var layoutH = -1f

    // ── 颗粒 tile 位图（预渲染一次 → drawImage 平铺；LOW 不建、onExit 归还）────
    private var grainBitmap: ImageBitmap? = null
    private var grainTilePx = 0

    // ── 星点（onEnter 固定种子 LCG 生成；x/y 归一化 0..1，之后永不重掷）────────
    private val starX = FloatArray(STAR_MAX)
    private val starY = FloatArray(STAR_MAX)
    private val starR = FloatArray(STAR_MAX)
    private val starA = FloatArray(STAR_MAX)
    private val starPh = FloatArray(STAR_MAX)  // 漂移相位（每颗不同 ⇒ 漂移不同步）
    private var starCount = 0
    private var tier = 1                       // 0=LOW / 1=MED / 2=HIGH（onEnter 解析）

    // ── 布局 LCG（⛔ 仅 onEnter 调用；draw 内绝不触碰 ⇒ 零分配红线不受影响）────
    //    种子**时间派生**（VisualizerRandom.defaultSeed，与项目种子策略一致）。
    //    ⛔ 绝不能用常量种子：切效果时 RendererSwapper.sync 走 `factory(theme)` 造**全新
    //    实例**（离开舞台还会 release）⇒ 常量种子会让每次进效果都是同一套「随机」布局。
    //    实例建好后每次 onEnter 再推进一次种子 ⇒ 同实例重入（切画质）也换新布局；
    //    画布尺寸变化不走这里（只重建 Brush）。
    private var lrng = VisualizerRandom.defaultSeed()

    // ── 主组 + 卫星组布局（onEnter 计算，draw 只读）──────────────────────────
    //    索引约定：[0, N_MAIN) = 主组（0 中心 / 1–5 内圈 / 6–12 外圈，外圈按齿数
    //    降序 ⇒ 裁尾即砍最小齿）；[N_MAIN, N_MAIN+satCount) = 卫星组（锚轮在前）。
    //    容量恒定 22，onEnter 只填充、不重分配。
    private val gearTeeth = IntArray(N_MAIN + SAT_MAX_GEARS)
    private val gearParent = IntArray(N_MAIN + SAT_MAX_GEARS)
    private val gearRing = IntArray(N_MAIN + SAT_MAX_GEARS)
    private val gearX = FloatArray(N_MAIN + SAT_MAX_GEARS)   // 安装中心（世界，屏心为原点）
    private val gearY = FloatArray(N_MAIN + SAT_MAX_GEARS)
    private val gearRp = FloatArray(N_MAIN + SAT_MAX_GEARS)  // 节圆半径
    private val gearTip = FloatArray(N_MAIN + SAT_MAX_GEARS) // 齿顶半径
    private val gearRoot = FloatArray(N_MAIN + SAT_MAX_GEARS)// 齿根半径
    private val gearRatio = FloatArray(N_MAIN + SAT_MAX_GEARS) // ω 比（主组相对 mainAngle）
    private val gearPhase = FloatArray(N_MAIN + SAT_MAX_GEARS) // 安装相位（接触点标定）
    private val gearStep = FloatArray(N_MAIN + SAT_MAX_GEARS)  // 齿距角 = TAU / teeth
    private val gearAngVel = FloatArray(N_MAIN + SAT_MAX_GEARS) // 卫星恒定角速度 ω（主组不用）
    private val sparkX = FloatArray(N_MAIN + SAT_MAX_GEARS)  // 啮合节点（世界坐标，预计算）
    private val sparkY = FloatArray(N_MAIN + SAT_MAX_GEARS)
    private var gearVerts = mutableListOf<FloatArray>()  // 齿廓顶点（世界半径已烘焙）
    private var orbit1R = 0f                // 内圈节圆导引半径（平均）
    private var orbit2R = 0f                // 外圈节圆导引半径（平均）
    private var gearCount = 0               // 主组当前档位绘制数（8 / 11 / 13）
    private var satCount = 0                // 卫星齿轮数（0 / ≤2 / ≤5 / ≤9）；生成期随放置即时回写

    private var mainAngle = 0f        // 唯一驱动角（绘制用；= 基线 BASE·elapsed + 棘轮分量）
    private var ratchetAngle = 0f     // 棘轮缓动分量（独立累加：只加不减，⛔ 不碰基线）
    private var ratchetTarget = 0f    // 棘轮目标角
    private var lastPulse = 0f
    private var lastMs = 0L
    private var elapsed = 0f          // 进入后累计秒（只在 onEnter 归零）
    private var bassS = 0f
    private var midS = 0f
    private var trebleS = 0f
    private var energyS = 0f
    /** energy 的第二级慢 EMA（α≈0.06）→ 整组缩放与发光总强度；尺寸变化不碰，只 onEnter 归零 */
    private var groupEnergy = 0f

    override fun onEnter(ctx: RenderContext) {
        lastPulse = 0f
        lastMs = 0L
        elapsed = 0f
        bassS = 0f
        midS = 0f
        trebleS = 0f
        energyS = 0f
        groupEnergy = 0f
        mainAngle = 0f
        ratchetAngle = 0f
        ratchetTarget = 0f

        // ⚠️ 画质变化时 RendererSwapper 会对**同一实例**重入 onEnter（不走 onExit）：
        //    先还旧颗粒位图（API22-25 Bitmap 像素在 native 堆，仅靠 finalizer 延迟回收），
        //    并把尺寸缓存键清成 -1 ⇒ 下一帧按当前尺寸重建全部 Brush / 颗粒 tile。
        //    ⛔ 这条路径**不碰** elapsed / 相位 / 音频平滑（上方已按只在 onEnter 归零的
        //    字段处理；尺寸变化本身更不会重置它们）。
        releaseGrain()
        layoutW = -1f
        layoutH = -1f

        // ── 分档：主组 LOW 8 / MED 11 / HIGH 13（裁外圈尾部；布局全量 13 轮不变）──
        //    卫星组 LOW 1 / MED 2 / HIGH 4 组（见 buildSatellites）──
        gearCount = when (ctx.quality) {
            VisualQuality.LOW -> 8
            VisualQuality.MEDIUM -> 11
            else -> 13
        }
        // 装饰层分档（星点密度 / 颗粒）：齿轮数与它各自独立
        tier = when (ctx.quality) {
            VisualQuality.LOW -> 0
            VisualQuality.MEDIUM -> 1
            else -> 2
        }

        // ── 布局 RNG：每次 onEnter 先推进一次种子 ⇒ 每次进效果/切画质一套全新布局。
        //    ⛔ 画布尺寸变化**不走这里**（只走 ensureLayout，仅换 unit）⇒ 布局世界坐标、
        //    elapsed、相位、音频平滑全部原样保留（见下方 ensureLayout 的注释）。
        lrng = lrng * 1664525u + 1013904223u
        gearVerts = mutableListOf()
        satCount = 0

        // ── 中心轮：随机齿数（26–34T）──────────────────────────────────────
        setGeom(0, li(CENTER_TEETH_MIN, CENTER_TEETH_MAX), MODULE, DEPTH_CAP)
        gearParent[0] = -1
        gearRing[0] = 0
        gearRatio[0] = 1f
        gearPhase[0] = 0f
        gearX[0] = 0f
        gearY[0] = 0f
        sparkX[0] = 0f
        sparkY[0] = 0f

        // ── 内圈 5 轮：随机齿数（12–18T）× 随机安装角（中心轮齿栅格随机槽位）
        //    兄弟碰撞检查 + reach 上限，≤ PLACE_RETRY 次重试 → fallbackMain ──
        var i = 1
        while (i <= N_INNER) {
            gearParent[i] = 0
            gearRing[i] = 1
            placeMainGear(i, 0, INNER_TEETH_MIN, INNER_TEETH_MAX)
            i++
        }

        // ── 外圈父轮：每个内圈保底 1 个子轮，其余随机撒 ⇒ 挂载结构也不对称 ──
        val outerParent = IntArray(N_OUTER)
        var k = 0
        while (k < N_INNER) {
            outerParent[k] = 1 + k
            k++
        }
        while (k < N_OUTER) {
            outerParent[k] = 1 + li(0, N_INNER - 1)
            k++
        }
        k = N_OUTER - 1                       // Fisher–Yates 洗牌（子轮不按内圈排队）
        while (k > 0) {
            val jj = li(0, k)
            val tmp = outerParent[k]
            outerParent[k] = outerParent[jj]
            outerParent[jj] = tmp
            k--
        }

        // ── 外圈 7 轮：随机齿数（8–13T）× 随机父轮齿栅格槽位，逐槽碰撞 + reach ──
        i = N_INNER + 1
        while (i < N_MAIN) {
            gearParent[i] = outerParent[i - (N_INNER + 1)]
            gearRing[i] = 2
            placeMainGear(i, gearParent[i], OUTER_TEETH_MIN, OUTER_TEETH_MAX)
            i++
        }

        // ── 速比：ratio = (±) N_center/N_i，符号 = 链深奇偶（外啮合一次反一次）──
        i = 1
        while (i < N_MAIN) {
            var d = 0
            var p = gearParent[i]
            while (p >= 0) {
                p = gearParent[p]
                d++
            }
            val r = gearTeeth[0].toFloat() / gearTeeth[i]
            gearRatio[i] = if (d % 2 == 0) r else -r
            i++
        }

        // ── 外圈按齿数降序（插入排序）：降档裁尾 ⇒ 永远先砍最小的外围轮；
        //    父轮恒为中心/内圈（索引 0–5，**永不参与本排序**）⇒ 裁剪后父子索引依然
        //    有效、位置不动 ⇒ 无几何跳变 ──
        i = N_INNER + 2
        while (i < N_MAIN) {
            var b = i
            while (b > N_INNER + 1 && gearTeeth[b] > gearTeeth[b - 1]) {
                swapGear(b, b - 1)
                b--
            }
            i++
        }

        // ── 主组整体收缩（等比 ⇒ 中心距/齿隙/相位/速比同比，啮合数学一行未动）──
        //    腾出 0.74→0.78 之外的卫星带；reach 上限在生成期用 MAIN_REACH_RAW 把关。
        i = 0
        while (i < N_MAIN) {
            gearX[i] *= MAIN_SHRINK
            gearY[i] *= MAIN_SHRINK
            gearRp[i] *= MAIN_SHRINK
            gearTip[i] *= MAIN_SHRINK
            gearRoot[i] *= MAIN_SHRINK
            sparkX[i] *= MAIN_SHRINK
            sparkY[i] *= MAIN_SHRINK
            i++
        }

        // 节圆导引环：穿过内圈 / 外圈各轮心的平均半径（仅主组，卫星不参与）
        var s1 = 0f
        var c1 = 0
        var s2 = 0f
        var c2 = 0
        i = 1
        while (i < N_MAIN) {
            val od = sqrt(gearX[i] * gearX[i] + gearY[i] * gearY[i])
            if (gearRing[i] == 1) {
                s1 += od; c1++
            } else {
                s2 += od; c2++
            }
            i++
        }
        orbit1R = if (c1 > 0) s1 / c1 else 0f
        orbit2R = if (c2 > 0) s2 / c2 else 0f

        // 齿廓顶点预生成（世界半径烘焙；5 点/齿，见类 KDoc）
        i = 0
        while (i < N_MAIN) {
            gearVerts.add(buildGearVerts(gearTip[i], gearRoot[i], gearTeeth[i]))
            i++
        }

        // ── 卫星组：主组外围的独立小组（世界坐标在此一次写定，尺寸变化不重掷）──
        buildSatellites()

        // ── 星野：固定种子 LCG 一次性生成，之后永不重掷（零闪烁；与太阳系同款）──
        //    ⚠️ 本地 fun 捕获可变 rng ⇒ 仅 onEnter 分配一次（draw 内零分配不受影响）
        var rng = 0x5EEDF00Du
        fun nextRand(): Float {
            rng = rng * 1664525u + 1013904223u
            return (rng shr 8).toFloat() / 16777216f
        }
        var s = 0
        while (s < STAR_MAX) {
            // 四周留 0.03 余量 ⇒ 漂移 ±0.012 后仍不出屏
            starX[s] = 0.03f + nextRand() * 0.94f
            starY[s] = 0.03f + nextRand() * 0.94f
            starR[s] = 0.0010f + nextRand() * 0.0018f   // × minDim ⇒ 1.1~3.0px@1080p
            starA[s] = 0.14f + nextRand() * 0.26f        // 弱亮度 0.14..0.40
            starPh[s] = nextRand() * TAU
            s++
        }
        starCount = when (tier) {
            0 -> 40
            1 -> 70
            else -> STAR_MAX
        }
    }

    /** 退出：归还颗粒 tile 位图（可重复调用；纹理只此一处重资源） */
    override fun onExit() {
        releaseGrain()
    }

    // ══ 布局生成（⛔ 全部只在 onEnter 调用；draw 内零分配红线不受影响）════════

    /** 布局 LCG 随机数 0..1（仅 onEnter 路径调用） */
    private fun lr(): Float {
        lrng = lrng * 1664525u + 1013904223u
        return (lrng shr 8).toFloat() / 16777216f
    }

    /** 闭区间随机整数 [min, max]（仅 onEnter 路径调用） */
    private fun li(min: Int, max: Int): Int {
        if (max <= min) return min
        val span = max - min + 1
        return min + (lr() * span).toInt().coerceIn(0, span - 1)
    }

    /** 写入齿数派生量（节圆 / 齿顶 / 齿根 / 齿距）；模数不同 ⇒ 主组与卫星组分别传入 */
    private fun setGeom(i: Int, teeth: Int, mod: Float, depthCap: Float) {
        val rp = mod * teeth / 2f
        val depth = minOf(depthCap, rp * ROOT_REL)
        gearTeeth[i] = teeth
        gearRp[i] = rp
        gearTip[i] = rp + depth
        gearRoot[i] = rp - depth
        gearStep[i] = TAU / teeth
    }

    /**
     * 与 [0, [upto]) 已放置齿轮的**最小余量**（>0 有间隙、=0 刚好贴合、<0 穿透）。
     * 判据：中心距 ≥ tip₁ + tip₂ + 齿隙（两 tip 圆不相交）。
     * ⚠️ [parent] 是啮合父轮 —— 中心距本就是 rp₁+rp₂+齿隙（< tip₁+tip₂+齿隙），
     * 属于「必须啮合」而非「必须分开」，必须排除。
     */
    private fun clearanceTo(upto: Int, x: Float, y: Float, tip: Float, parent: Int): Float {
        var best = Float.MAX_VALUE
        var j = 0
        while (j < upto) {
            if (j != parent) {
                val dx = x - gearX[j]
                val dy = y - gearY[j]
                val need = tip + gearTip[j] + MESH_CLEAR
                val m = sqrt(dx * dx + dy * dy) - need
                if (m < best) best = m
            }
            j++
        }
        return best
    }

    /**
     * 卫星轮的余量检查：已画主组 [0, gearCount) + 已放卫星 [N_MAIN, N_MAIN+satCount)。
     * [skip] = 自己的啮合父轮（组内锚点/上一齿），无父轮传 −1。
     */
    private fun satClearance(x: Float, y: Float, tip: Float, skip: Int): Float {
        var best = clearanceTo(gearCount, x, y, tip, skip)
        var j = N_MAIN
        while (j < N_MAIN + satCount) {
            if (j != skip) {
                val dx = x - gearX[j]
                val dy = y - gearY[j]
                val need = tip + gearTip[j] + MESH_CLEAR
                val m = sqrt(dx * dx + dy * dy) - need
                if (m < best) best = m
            }
            j++
        }
        return best
    }

    /** 接触点标定 + 火花节点（主组 / 卫星组共用同一公式）：β = 父轮指向该轮的安装角 */
    private fun finishMesh(i: Int, parent: Int, beta: Float) {
        // 从本轮中心看接触方向 = β + π；相位把该方向对准齿槽中心（父轮侧则是齿中心）
        val local = beta + TAU / 2f
        val st = gearStep[i]
        gearPhase[i] = local - st * floor(local / st)
        sparkX[i] = gearX[parent] + cos(beta) * gearRp[parent]
        sparkY[i] = gearY[parent] + sin(beta) * gearRp[parent]
    }

    /**
     * 主组单轮放置：随机齿数 [teethLo, teethHi] × 随机**父轮齿中心栅格**槽位
     * （栅格是接触点相位标定的前提，所以安装角只能从槽位里挑，不能连续取）。
     * 每个候选做两道检查 —— ① 与全部已放置轮（父轮除外）的 tip 圆余量 ≥ 0；
     * ② reach = |中心|+tip ≤ [MAIN_REACH_RAW]。≤ [PLACE_RETRY] 次随机重试，
     * 全部落空则走确定性兜底 [fallbackMain]，最后标定相位与火花节点。
     */
    private fun placeMainGear(i: Int, parent: Int, teethLo: Int, teethHi: Int) {
        var beta = 0f
        var placed = false
        var attempt = 0
        while (!placed && attempt < PLACE_RETRY) {
            setGeom(i, li(teethLo, teethHi), MODULE, DEPTH_CAP)
            val c = gearRp[parent] + gearRp[i] + MESH_CLEAR   // 啮合中心距 = rp_a+rp_b+齿隙
            val b = gearPhase[parent] + (li(0, gearTeeth[parent] - 1) + 0.5f) * gearStep[parent]
            val x = gearX[parent] + cos(b) * c
            val y = gearY[parent] + sin(b) * c
            if (sqrt(x * x + y * y) + gearTip[i] <= MAIN_REACH_RAW &&
                clearanceTo(i, x, y, gearTip[i], parent) >= 0f
            ) {
                beta = b
                gearX[i] = x
                gearY[i] = y
                placed = true
            }
            attempt++
        }
        if (!placed) beta = fallbackMain(i, parent, teethLo)
        finishMesh(i, gearParent[i], beta)   // 兜底可能改挂父轮 ⇒ 以 gearParent 为准
    }

    /**
     * 主组确定性兜底：先按**原父轮**把齿数从 [teethLo] 逐档降到 [FALLBACK_ABS_MIN]、
     * 每档扫父轮**全部齿槽**，取「碰撞余量 ∧ reach 余量」最大的槽位；原父轮全放不下时，
     * 再依次改挂到中心轮 / 任一内圈（索引 0–N_INNER ⇒ **恒不参与外圈重排**，改挂不会
     * 让 swapGear 的父指针失效）。任一档余量 ≥0 即停。
     * 返回安装角 β，并写入本轮几何、位置与 [gearParent]（兜底可能改挂）。
     *
     * ⚠️ 实测（logs_temp/gears_layout_check_v2.py，3 档 × 400 种子 = 1200 布局 / 14400 轮）：
     * 随机重试成功率 97.7%（兜底 333 次，其中改挂中心/内圈 40 次、降到区间下限以下
     * 145 轮）；**负余量 = 0**、主组非啮合最小余量 +0.000068、reach ≤0.7399。
     */
    private fun fallbackMain(i: Int, parent: Int, teethLo: Int): Float {
        var bestMargin = Float.NEGATIVE_INFINITY
        var bestParent = parent
        var bestTeeth = teethLo
        var bestBeta = 0f
        // 候选父轮：原父轮优先；外圈轮（索引 > N_INNER）可改挂中心轮/内圈，内圈轮恒挂中心
        var ci = -1                                   // −1 = 原父轮，其后 0..N_INNER
        while (ci <= N_INNER) {
            val pi = if (ci < 0) parent else ci
            val usable = ci < 0 || (i > N_INNER && pi != parent)
            if (usable) {
                var t = teethLo
                while (t >= FALLBACK_ABS_MIN) {
                    setGeom(i, t, MODULE, DEPTH_CAP)
                    val c = gearRp[pi] + gearRp[i] + MESH_CLEAR
                    val slots = gearTeeth[pi]
                    var kk = 0
                    while (kk < slots) {
                        val b = gearPhase[pi] + (kk + 0.5f) * gearStep[pi]
                        val x = gearX[pi] + cos(b) * c
                        val y = gearY[pi] + sin(b) * c
                        var m = clearanceTo(i, x, y, gearTip[i], pi)
                        val reachSlack = MAIN_REACH_RAW - (sqrt(x * x + y * y) + gearTip[i])
                        if (reachSlack < m) m = reachSlack
                        if (m > bestMargin) {
                            bestMargin = m
                            bestParent = pi
                            bestTeeth = t
                            bestBeta = b
                        }
                        kk++
                    }
                    if (bestMargin >= 0f) break
                    t--
                }
            }
            if (bestMargin >= 0f) break
            ci++
        }
        setGeom(i, bestTeeth, MODULE, DEPTH_CAP)
        gearParent[i] = bestParent
        val c = gearRp[bestParent] + gearRp[i] + MESH_CLEAR
        gearX[i] = gearX[bestParent] + cos(bestBeta) * c
        gearY[i] = gearY[bestParent] + sin(bestBeta) * c
        return bestBeta
    }

    /** 交换两轮的全部布局字段（外圈按齿数降序的插入排序用；onEnter 内调用） */
    private fun swapGear(a: Int, b: Int) {
        var iv = gearTeeth[a]; gearTeeth[a] = gearTeeth[b]; gearTeeth[b] = iv
        iv = gearParent[a]; gearParent[a] = gearParent[b]; gearParent[b] = iv
        iv = gearRing[a]; gearRing[a] = gearRing[b]; gearRing[b] = iv
        var fv = gearX[a]; gearX[a] = gearX[b]; gearX[b] = fv
        fv = gearY[a]; gearY[a] = gearY[b]; gearY[b] = fv
        fv = gearRp[a]; gearRp[a] = gearRp[b]; gearRp[b] = fv
        fv = gearTip[a]; gearTip[a] = gearTip[b]; gearTip[b] = fv
        fv = gearRoot[a]; gearRoot[a] = gearRoot[b]; gearRoot[b] = fv
        fv = gearRatio[a]; gearRatio[a] = gearRatio[b]; gearRatio[b] = fv
        fv = gearPhase[a]; gearPhase[a] = gearPhase[b]; gearPhase[b] = fv
        fv = gearStep[a]; gearStep[a] = gearStep[b]; gearStep[b] = fv
        fv = sparkX[a]; sparkX[a] = sparkX[b]; sparkX[b] = fv
        fv = sparkY[a]; sparkY[a] = sparkY[b]; sparkY[b] = fv
    }

    /**
     * 卫星组生成 —— 主组**外围**的独立小齿轮组（与主组零啮合、零接触）。
     *
     * - 组数随档位：LOW 1 / MED 2 / HIGH 4；组内 1–3 齿（总齿数 ≤ 档位上限）。
     * - 锚轮：极坐标随机（半径 [SAT_R_MIN, 表圈−齿隙−tip]），≤ SAT_RETRY 次随机
     *   抽样，失败转 72 角 × 3 半径全扫描取最大余量。
     * - 组内成员：复用主组啮合数学（父轮齿栅格槽位 + 中心距 = rp_a+rp_b+齿隙 +
     *   相位标定 + 速比 (±)N锚/N齿 ⇒ 反向/同向传动），但驱动角是**组自己的
     *   ω·elapsed**：纯线性、无棘轮、无缓动 ⇒ 恒速连续、永不停止。
     * - 碰撞：组↔组、组↔主组（当前档位绘制集）、组↔表圈全部 tip 圆 + 齿隙检查；
     *   成员另受半径带 [SAT_R_MIN, 表圈−齿隙−tip] 约束。
     * - 圈速：20–90s，彼此差 ≥8s 且避开主组 56s（超时走 [SAT_PERIOD_FALLBACK]）。
     *
     * 全部世界坐标在此一次写定 ⇒ 画布尺寸变化只换 unit，**不重掷、不重置相位**。
     */
    private fun buildSatellites() {
        val groupCount = when (tier) {
            0 -> SAT_GROUP_LOW
            1 -> SAT_GROUP_MED
            else -> SAT_GROUP_HIGH
        }
        val gearCap = when (tier) {
            0 -> SAT_GEAR_CAP_LOW
            1 -> SAT_GEAR_CAP_MED
            else -> SAT_GEAR_CAP_HIGH
        }

        // 每组圈速（s/圈）：互不相同、也不同于主组 56s ⇒ 各组速率各不相同
        val periods = FloatArray(groupCount)
        var g = 0
        while (g < groupCount) {
            var ok = false
            var t = 0
            while (!ok && t < SAT_PERIOD_TRIES) {
                val p = SAT_PERIOD_MIN + lr() * (SAT_PERIOD_MAX - SAT_PERIOD_MIN)
                ok = abs(p - MAIN_PERIOD) >= SAT_PERIOD_GAP
                var o = 0
                while (ok && o < g) {
                    if (abs(p - periods[o]) < SAT_PERIOD_GAP) ok = false
                    o++
                }
                if (ok) periods[g] = p
                t++
            }
            if (!ok) {
                // 兜底 ①：固定表里挑一个对**已选圈速**也满足的（表自身两两差 ≥8s、避开 56s）
                var idx = 0
                while (!ok && idx < SAT_PERIOD_FALLBACK.size) {
                    val p2 = SAT_PERIOD_FALLBACK[idx]
                    if (abs(p2 - MAIN_PERIOD) >= SAT_PERIOD_GAP) {
                        var hit = false
                        var o = 0
                        while (o < g) {
                            if (abs(p2 - periods[o]) < SAT_PERIOD_GAP) hit = true
                            o++
                        }
                        if (!hit) {
                            periods[g] = p2
                            ok = true
                        }
                    }
                    idx++
                }
                // 兜底 ②：0.5s 步长全线扫描（窗口 70s、每选一个圈速封掉 16s ⇒ 必有空位）
                var probe = SAT_PERIOD_MIN
                while (!ok && probe <= SAT_PERIOD_MAX + 1e-3f) {
                    if (abs(probe - MAIN_PERIOD) >= SAT_PERIOD_GAP) {
                        var hit = false
                        var o = 0
                        while (o < g) {
                            if (abs(probe - periods[o]) < SAT_PERIOD_GAP) hit = true
                            o++
                        }
                        if (!hit) {
                            periods[g] = probe
                            ok = true
                        }
                    }
                    probe += 0.5f
                }
                if (!ok) periods[g] = SAT_PERIOD_FALLBACK[g % SAT_PERIOD_FALLBACK.size] // 理论不可达
            }
            g++
        }

        var placed = 0
        g = 0
        while (g < groupCount && placed < gearCap) {
            // 组驱动角 ω：符号随机 ⇒ 有的顺时针有的逆时针，幅值 = TAU/圈速
            val omega = (if (lr() < 0.5f) -1f else 1f) * TAU / periods[g]
            val want = li(1, minOf(SAT_GEAR_MAX_PER_GROUP, gearCap - placed))

            // ── 锚轮（组内第 0 齿）：极坐标随机散布 ──
            val lead = N_MAIN + placed
            var lx = 0f
            var ly = 0f
            var ok = false
            var attempt = 0
            while (!ok && attempt < SAT_RETRY) {
                setGeom(lead, li(SAT_TEETH_MIN, SAT_TEETH_MAX), SAT_MODULE, SAT_DEPTH_CAP)
                val tip = gearTip[lead]
                val rMax = BEZEL_R - MESH_CLEAR - tip
                if (rMax > SAT_R_MIN) {
                    val r = SAT_R_MIN + lr() * (rMax - SAT_R_MIN)
                    val th = lr() * TAU
                    val x = cos(th) * r
                    val y = sin(th) * r
                    if (satClearance(x, y, tip, -1) >= 0f) {
                        lx = x
                        ly = y
                        ok = true
                    }
                }
                attempt++
            }
            if (!ok) {
                // 兜底：3 条半径 × SAT_SCAN 个方向全扫描，取余量最大的落点
                setGeom(lead, SAT_TEETH_MIN, SAT_MODULE, SAT_DEPTH_CAP)
                val tip = gearTip[lead]
                val rLo = SAT_R_MIN + 0.02f
                val rHi = (BEZEL_R - MESH_CLEAR - tip - 0.02f).coerceAtLeast(rLo)
                var bestM = Float.NEGATIVE_INFINITY
                var ri = 0
                while (ri < 3) {
                    val r = rLo + (rHi - rLo) * ri * 0.5f
                    var a2 = 0
                    while (a2 < SAT_SCAN) {
                        val th = a2 * TAU / SAT_SCAN
                        val x = cos(th) * r
                        val y = sin(th) * r
                        val m = satClearance(x, y, tip, -1)
                        if (m > bestM) {
                            bestM = m
                            lx = x
                            ly = y
                        }
                        a2++
                    }
                    ri++
                }
            }
            gearX[lead] = lx
            gearY[lead] = ly
            gearParent[lead] = lead        // 锚轮自指 ⇒ 无啮合父轮（火花循环据此跳过）
            gearRing[lead] = 2
            gearRatio[lead] = 1f           // 组内速比以锚轮为基准
            gearPhase[lead] = 0f
            gearAngVel[lead] = omega       // 锚轮 = 组驱动轮：ω·elapsed，恒速永不停
            sparkX[lead] = 0f
            sparkY[lead] = 0f
            gearVerts.add(buildGearVerts(gearTip[lead], gearRoot[lead], gearTeeth[lead]))
            placed++
            // ⚠️ 必须即时回写：satClearance 的卫星段读的是 satCount（不是局部 placed）。
            //    只在函数末尾回写一次 ⇒ 锚轮/成员放置期间卫星段恒为空 ⇒ KDoc 承诺的
            //    「组↔组 / 组内非父轮 tip 圆碰撞检查」会静默失效（验证脚本按增量列表
            //    建模，测不出这个差异）。
            satCount = placed

            // ── 组内后续齿：链式啮合（锚 → 齿1 → 齿2），速比 = (±)N锚/N齿 ──
            var parent = lead
            var mi = 1
            while (mi < want && placed < gearCap) {
                val idx = N_MAIN + placed
                var beta = 0f
                var okM = false
                var att = 0
                while (!okM && att < SAT_RETRY) {
                    setGeom(idx, li(SAT_TEETH_MIN, SAT_TEETH_MAX), SAT_MODULE, SAT_DEPTH_CAP)
                    val c = gearRp[parent] + gearRp[idx] + MESH_CLEAR
                    val b = gearPhase[parent] + (li(0, gearTeeth[parent] - 1) + 0.5f) *
                        gearStep[parent]
                    val x = gearX[parent] + cos(b) * c
                    val y = gearY[parent] + sin(b) * c
                    val rad = sqrt(x * x + y * y)
                    if (rad >= SAT_R_MIN &&
                        rad + gearTip[idx] <= BEZEL_R - MESH_CLEAR &&
                        satClearance(x, y, gearTip[idx], parent) >= 0f
                    ) {
                        beta = b
                        gearX[idx] = x
                        gearY[idx] = y
                        okM = true
                    }
                    att++
                }
                if (!okM) break   // 该方向放不下 ⇒ 本组到此为止（组仍有效，只是齿更少）
                var depth = 1     // 组内链深（锚 = 0）：奇 ⇒ 反向、偶 ⇒ 同向
                var pp = parent
                while (gearParent[pp] != pp) {
                    pp = gearParent[pp]
                    depth++
                }
                val mag = gearTeeth[lead].toFloat() / gearTeeth[idx]
                val ratio = if (depth % 2 == 1) -mag else mag
                gearParent[idx] = parent
                gearRing[idx] = 2
                gearRatio[idx] = ratio
                gearAngVel[idx] = ratio * omega
                finishMesh(idx, parent, beta)
                gearVerts.add(buildGearVerts(gearTip[idx], gearRoot[idx], gearTeeth[idx]))
                placed++
                satCount = placed   // 同上：让下一颗成员/下一组锚轮能看到已放卫星
                parent = idx
                mi++
            }
            g++
        }
        satCount = placed
    }

    /**
     * 生成齿廓顶点 [x0,y0,...]（绕轮心的世界半径偏移）。
     * 每齿 5 点（相对齿距）：root@0.00、root@0.22、tip@0.36、tip@0.64、root@0.78
     * ⇒ 齿顶宽 0.28 齿距、齿槽跨 0.78→1.22（宽 0.44），两侧留横向余量（backlash）。
     */
    private fun buildGearVerts(tip: Float, root: Float, teeth: Int): FloatArray {
        val verts = FloatArray(teeth * 5 * 2)
        val st = TAU / teeth
        var o = 0
        var t = 0
        while (t < teeth) {
            val base = t * st
            var k = 0
            while (k < 5) {
                val frac = when (k) {
                    0 -> 0f
                    1 -> 0.22f
                    2 -> 0.36f
                    3 -> 0.64f
                    else -> 0.78f
                }
                val rad = if (k == 2 || k == 3) tip else root
                val a = base + frac * st
                verts[o] = cos(a) * rad
                verts[o + 1] = sin(a) * rad
                o += 2
                k++
            }
            t++
        }
        return verts
    }

    // ══ 纹理层缓存（全部只在 onEnter / 画布尺寸变化时建；draw 内零分配）════════

    /**
     * 背景 / 光层缓存 —— **只在画布尺寸变化时**重建（[onEnter] 会把键清成 −1）。
     * ⛔ 这里不碰 elapsed / mainAngle / 啮合相位 / 音频平滑 / groupEnergy。
     *
     * 缓存点：[bgDepthBrush] 中心纵深、[vignetteBrush] 暗角（两者以**半对角线**为
     * 半径 ⇒ 任意宽高比四角恰好落在渐变末端、整屏铺满）、[ambientBrush] 环境光晕环
     * （半径 ∝ unit）、[fillBrushes] 齿内渐变（主组 13 + 卫星 ≤9 支，绝对坐标 ⇒ 必须随
     * 尺寸重建；坐标为 scale=1 世界坐标，与齿轮路径同处整组缩放变换内 ⇒ 能量缩放时恒对齐）、
     * 颗粒 tile 位图（短边档不变则复用；LOW 不建）。
     * ⛔ 本函数**不重掷布局**（布局是世界坐标，尺寸变化只换 unit）。
     */
    private fun ensureLayout(w: Float, h: Float, minDim: Float, cx: Float, cy: Float, unit: Float) {
        if (layoutW == w && layoutH == h) return
        layoutW = w
        layoutH = h

        val halfDiag = sqrt(w * w + h * h) * 0.5f
        val depthCore = Color(BG_CORE)
        bgDepthBrush = Brush.radialGradient(
            0f to depthCore,
            BG_CENTER_STOP to depthCore,
            1f to depthCore.copy(alpha = 0f),
            center = Offset(cx, cy),
            radius = halfDiag
        )
        vignetteBrush = Brush.radialGradient(
            VIG_START to Color.Black.copy(alpha = 0f),
            1f to Color.Black.copy(alpha = VIG_EDGE_ALPHA),
            center = Offset(cx, cy),
            radius = halfDiag
        )
        ambientBrush = Brush.radialGradient(
            0f to goldDim.copy(alpha = 0f),
            AMBIENT_INNER to goldDim.copy(alpha = 0f),
            AMBIENT_PEAK to goldDim.copy(alpha = 1f),
            1f to goldDim.copy(alpha = 0f),
            center = Offset(cx, cy),
            radius = AMBIENT_RADIUS * unit
        )

        // 齿内能量场：中心略亮、边缘透明（主组 13 + 卫星各一支；Brush 持绝对坐标）
        var g = 0
        while (g < N_MAIN + satCount) {
            val col = if (gearRing[g] == 2) coldGray else gold
            fillBrushes[g] = Brush.radialGradient(
                0f to col.copy(alpha = FILL_CENTER_ALPHA),
                FILL_MID_STOP to col.copy(alpha = FILL_MID_ALPHA),
                1f to col.copy(alpha = 0f),
                center = Offset(cx + gearX[g] * unit, cy + gearY[g] * unit),
                radius = gearTip[g] * unit
            )
            g++
        }

        // 颗粒 tile 边长只由「短边档」定（1080p→128 / 4K→256）⇒ 平铺 call 数两种
        // 分辨率都 ≈135；tile 图案与画布尺寸无关 ⇒ 档不变就不重建
        if (tier > 0) {
            val tile = if (minDim >= GRAIN_TILE_LARGE_MIN) GRAIN_TILE_HI else GRAIN_TILE
            if (tile != grainTilePx) buildGrainTile(tile)
        }
    }

    /**
     * 颗粒 tile 预渲染（固定种子 ⇒ 图案可复现、稳态不闪）。
     * 白 speckle + r² 偏斜（多数像素近乎透明）⇒ 细而不"沙"；整体 α 在 [drawGrain] 上。
     */
    private fun buildGrainTile(tile: Int) {
        releaseGrain()
        val px = IntArray(tile * tile)
        var rng = 0x9E3779B9u
        var i = 0
        while (i < px.size) {
            rng = rng * 1664525u + 1013904223u
            val r = (rng shr 8).toFloat() / 16777216f
            val a = (r * r * 255f).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or 0x00FFFFFF
            i++
        }
        val bmp = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, tile, 0, 0, tile, tile)
        grainBitmap = bmp.asImageBitmap()
        grainTilePx = tile
    }

    /** 归还颗粒位图（onEnter 重入 / onExit）—— API22-25 像素在 native 堆，必须显式 recycle */
    private fun releaseGrain() {
        try {
            grainBitmap?.asAndroidBitmap()?.recycle()
        } catch (_: Exception) {
        }
        grainBitmap = null
        grainTilePx = 0
    }

    /** 颗粒层：tile 平铺整屏（`IntOffset` 是 value class，循环体零分配） */
    private fun DrawScope.drawGrain(w: Float, h: Float) {
        val bmp = grainBitmap ?: return
        val tile = grainTilePx
        if (tile <= 0) return
        val a = if (tier >= 2) GRAIN_ALPHA_HIGH else GRAIN_ALPHA_MED
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                drawImage(bmp, dstOffset = IntOffset(x, y), alpha = a, blendMode = BlendMode.SrcOver)
                x += tile
            }
            y += tile
        }
    }

    /**
     * 星野：固定种子位置（onEnter 生成 ⇒ 零闪烁）+ **缓慢椭圆漂移**（同一周期、
     * 每颗相位不同 ⇒ 不同步）+ 全局微脉动。半径 × minDim、位置 × w/h ⇒ 横竖屏自适应。
     */
    private fun DrawScope.drawStars(w: Float, h: Float, minDim: Float) {
        if (starCount == 0) return
        val t = (elapsed % STAR_DRIFT_PERIOD) / STAR_DRIFT_PERIOD * TAU
        val pulse = 0.85f + 0.15f * sin((elapsed % STAR_PULSE_PERIOD) / STAR_PULSE_PERIOD * TAU)
        val dx = STAR_DRIFT * w
        val dy = STAR_DRIFT * h
        var s = 0
        while (s < starCount) {
            val ph = starPh[s]
            drawCircle(
                color = if ((s and 3) == 0) starBlue else starWhite,
                radius = starR[s] * minDim,
                center = Offset(starX[s] * w + cos(t + ph) * dx, starY[s] * h + sin(t + ph) * dy),
                alpha = starA[s] * pulse,
                blendMode = BlendMode.Plus
            )
            s++
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f || gearCount == 0) return

        // ── dt 累加器（elapsed 只在 onEnter 归零）────────────────────────
        // 时间基 = 渲染时钟。⛔ 不用 ctx.nowMs —— 它是 25Hz 分析线程取样的
        // f.timeMs：暂停后只补发一帧静音就再也不更新（PcmSpectrumTap 版本号不变
        // 直接 return）、PCM 卡顿同理 ⇒ elapsed 停走、整组停转；且 40ms 量化步进
        // 与 60fps 渲染不合拍。与 WorldRenderer 同一约定（时间推进自己取样）。
        val now = SystemClock.uptimeMillis()
        if (lastMs == 0L) lastMs = now
        val dt = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        elapsed += dt

        // ── 音频平滑（律动通道，线性）──────────────────────────────────
        bassS += (frame.bass - bassS) * SMOOTH
        midS += (frame.mid - midS) * SMOOTH
        trebleS += (frame.treble - trebleS) * SMOOTH
        energyS += (frame.energy - energyS) * SMOOTH
        // 整组缩放/发光走**第二级**更慢 EMA（独立小 α ≈0.06；尺寸变化不碰）
        groupEnergy += (energyS - groupEnergy) * SCALE_EMA

        val cx = w * 0.5f
        val cy = h * 0.5f
        val unit = FIT_K * ctx.minDim / WORLD_EXTENT   // 85% 半短边适配（峰值×1.05 ⇒ 89.4%），横竖屏同值

        // 背景/光层缓存：仅尺寸变化时重建（⛔ 不碰 elapsed/相位/音频平滑）
        ensureLayout(w, h, ctx.minDim, cx, cy, unit)

        // ── 棘轮：pulse 上升沿推进一个中心齿距 ──────────────────────────
        if (frame.pulse - lastPulse > RATCHET_THRESH) {
            ratchetTarget += gearStep[0]
        }
        lastPulse = frame.pulse

        // ── 驱动角 = 匀速基线（绝对式）+ 棘轮缓动分量（只加不减）──────────
        //    ⚠️ 两段必须**分开累加**（TV 第 4 轮反馈「齿轮并未一直旋转、偶尔停顿、
        //    偶尔反转抽搐」的根因）：旧实现把棘轮弹簧直接加在含基线的 mainAngle 上，
        //    而 ratchetTarget 里只有棘轮、没有基线 ⇒ 弹簧每帧把刚加上去的基线又
        //    减回去，稳态收敛到 mainAngle ≈ ratchetTarget + BASE·RATCHET_MS/1000、
        //    净速率为 0 —— 鼓点之间整组冻住（停顿），只有鼓点时才"跳一齿"（抽搐），
        //    反向子轮（gearRatio<0）在每次跳变里看着像倒抽（反转）；无鼓点素材
        //    更是几分钟纹丝不动。
        //    现在：基线 = BASE_SPEED·elapsed 绝对求值（elapsed 单调 ⇒ 每帧恒正推进、
        //    天然不回退、无累积漂移）；棘轮只在自己的 ratchetAngle 上缓动（目标恒
        //    ≥ 当前、ease<1 ⇒ 只加不减、不过冲）；两者相加 ⇒ 帧帧推进、永不停顿、
        //    永不反转。基线与卫星组共用**同一个 elapsed**（同一时间基、同一 dt 钳位）。
        //    ⚠️ 不接 treble/mid 调速：帧间抖动会让角速度忽快忽慢；
        //    子轮无独立自转，全部 angle = phase + ratio·mainAngle 刚性跟随。
        //    不做 TAU 回绕：子轮回绕会跳 ratio·TAU（齿对齿但辐条可见跳位），
        //    且浮点增长 24h 内相位误差仍 < 1% 齿距。
        val ease = (dt * 1000f / RATCHET_MS).coerceIn(0f, 1f)
        ratchetAngle += (ratchetTarget - ratchetAngle) * ease
        mainAngle = BASE_SPEED * elapsed + ratchetAngle

        // ── 音频映射 → 强度（α 公式 + 上限，见交付报告映射表）──────────
        val gearAlpha = (0.62f + bassS * 0.33f).coerceAtMost(0.95f)
        val bezelAlpha = (0.10f + midS * 0.22f).coerceAtMost(0.32f)
        val orbitAlpha = (0.06f + energyS * 0.14f).coerceAtMost(0.20f)
        val sparkAlpha = (0.15f + trebleS * 0.65f).coerceAtMost(0.80f)
        val gStroke = if (bassS > 0.55f) strokeGearFat else strokeGear   // 1.6 ↔ 2.2px

        // ── energy → 整组缩放 / 发光总强度（全部读 groupEnergy：慢 EMA + 上限）──
        val glowGain = (GLOW_GAIN_BASE + GLOW_GAIN_ENERGY * groupEnergy).coerceAtMost(GLOW_GAIN_MAX)
        val glowStrokeAlpha = (GLOW_STROKE_BASE + GLOW_STROKE_ENERGY * groupEnergy)
            .coerceAtMost(GLOW_STROKE_MAX)
        val ambientAlpha = (AMBIENT_ALPHA_BASE + AMBIENT_ALPHA_ENERGY * groupEnergy)
            .coerceAtMost(AMBIENT_ALPHA_MAX)
        val linkAlpha = (LINK_ALPHA_BASE + LINK_ALPHA_ENERGY * groupEnergy).coerceAtMost(LINK_ALPHA_MAX)
        val fillAlpha = ((0.50f + bassS * 0.45f) * glowGain).coerceAtMost(1f)
        // 均匀缩放 = 静音基础呼吸（±1.0%、9s）+ energy 增益（0~5%）⇒ 区间 [0.98, 1.05]
        val breathPh = (elapsed % SCALE_BREATH_PERIOD) / SCALE_BREATH_PERIOD * TAU
        val groupScale = (SCALE_BASE + SCALE_BREATH * sin(breathPh) +
            SCALE_ENERGY_GAIN * groupEnergy).coerceIn(SCALE_MIN, SCALE_MAX)

        // ══ 背景空间感（整屏底层；画在整组缩放之外，全按屏幕尺寸计算）═════════
        drawRect(Color(BG_BASE))                 // ① 近黑基底（任意宽高比恒铺满）
        drawRect(bgDepthBrush!!)                 // ② 中心 #0B0E15 极淡径向纵深
        drawRect(vignetteBrush!!)                // ③ 暗角：四角/边缘压暗（纯径向渐变）
        drawGrain(w, h)                          // ④ 颗粒 tile 平铺（LOW 省略）
        drawStars(w, h, ctx.minDim)              // ⑤ 固定种子星点（缓漂移）

        // ══ 整组能量缩放：绕画面中心的**均匀**缩放（canvas 原生变换，零分配）═══
        //    所有半径/中心距同比缩放 ⇒ 啮合/相位/运动学一行未动；
        //    齿内填充 Brush 按 scale=1 坐标缓存，与路径同处本变换内 ⇒ 恒对齐。
        val cvs = drawContext.canvas
        cvs.save()
        cvs.translate(cx, cy)
        cvs.scale(groupScale, groupScale)
        cvs.translate(-cx, -cy)

        // ── 层 0 · 环境光晕：齿轮群外围一圈极淡的大范围光（径向渐变环，禁模糊）──
        drawCircle(ambientBrush!!, radius = AMBIENT_RADIUS * unit, center = Offset(cx, cy),
            alpha = ambientAlpha, blendMode = BlendMode.Plus)

        // ── 层 1 · 表圈：刻度环（60 格，每 5 格长刻度）──────────────────
        //    半径 = BEZEL_R(1.05) ⇒ 生成期卫星带以它为外框（tip 圆不得越过）
        val bezelR = BEZEL_R * unit
        drawCircle(goldDim, radius = bezelR, center = Offset(cx, cy),
            style = strokeBezel, alpha = bezelAlpha, blendMode = BlendMode.Plus)
        var tk = 0
        while (tk < 60) {
            val a = tk * TAU / 60f
            val cs = cos(a)
            val sn = sin(a)
            val long = tk % 5 == 0
            val len = if (long) 0.034f * unit else 0.016f * unit
            drawLine(goldDim,
                Offset(cx + cs * (bezelR - len), cy + sn * (bezelR - len)),
                Offset(cx + cs * bezelR, cy + sn * bezelR),
                strokeWidth = if (long) 1.4f else 1f,
                alpha = (bezelAlpha * (if (long) 1.6f else 1f)).coerceAtMost(0.5f),
                blendMode = BlendMode.Plus)
            tk++
        }

        // ── 层 2 · 节圆导引：中心 / 内圈 / 外圈三道淡环 ──────────────────
        drawCircle(goldDim, radius = gearRp[0] * unit, center = Offset(cx, cy),
            style = strokeBezel, alpha = orbitAlpha * 0.7f, blendMode = BlendMode.Plus)
        drawCircle(goldDim, radius = orbit1R * unit, center = Offset(cx, cy),
            style = strokeBezel, alpha = orbitAlpha, blendMode = BlendMode.Plus)
        drawCircle(goldDim, radius = orbit2R * unit, center = Offset(cx, cy),
            style = strokeBezel, alpha = orbitAlpha, blendMode = BlendMode.Plus)

        // ── 层 2.5 · 啮合连线：父子轮中心连线（星群/分子结构感）──────────
        //    强度取全层最弱档（linkAlpha）；⛔ 必须画在齿轮层之前
        var lg = 1
        while (lg < gearCount) {
            val p = gearParent[lg]
            drawLine(goldDim,
                Offset(cx + gearX[p] * unit, cy + gearY[p] * unit),
                Offset(cx + gearX[lg] * unit, cy + gearY[lg] * unit),
                strokeWidth = 1f, alpha = linkAlpha, blendMode = BlendMode.Plus)
            lg++
        }

        // ── 层 3 · 齿轮：齿内填充 + 双层描边 + 轴毂 + 辐条。
        //    ⚠️ 旋转烘焙进顶点（手算世界坐标），绝不用 withTransform({ rotate })：
        //    Compose DrawTransform.rotate 默认 pivot=画布中心，会让所有齿轮绕同一
        //    固定点公转（历史反馈"整体绕右下角旋转"的根因）。cos/sin 矩阵作用于
        //    轮心偏移后的顶点，齿轮只绕自身轴心自转。
        //    两段连续绘制：主组 [0, gearCount)、卫星 [N_MAIN, N_MAIN+satCount)；
        //    idx 换算是纯算术 ⇒ 零分配。主组角 = phase + ratio·mainAngle（含棘轮），
        //    卫星角 = phase + ω·elapsed（恒速、永不停、不接棘轮）。
        val drawCount = gearCount + satCount
        var g = 0
        while (g < drawCount) {
            val idx = if (g < gearCount) g else N_MAIN + (g - gearCount)
            val tipPx = gearTip[idx] * unit
            val rootPx = gearRoot[idx] * unit
            val ring = gearRing[idx]
            val color = if (ring == 2) coldGray else gold   // 内芯金、外圈冷灰
            val alpha = (gearAlpha - ring * 0.08f).coerceAtLeast(0.45f)
            val angle = if (idx < N_MAIN) {
                mainAngle * gearRatio[idx] + gearPhase[idx]
            } else {
                gearPhase[idx] + gearAngVel[idx] * elapsed
            }
            val x = cx + gearX[idx] * unit
            val y = cy + gearY[idx] * unit
            val cosA = cos(angle)
            val sinA = sin(angle)

            // 轮廓：顶点 → 世界坐标烘焙旋转，复用单例 Path（零分配）
            val verts = gearVerts[idx]
            gearPath.reset()
            var vi = 0
            while (vi < verts.size) {
                val ux = verts[vi]
                val uy = verts[vi + 1]
                val wx = x + (ux * cosA - uy * sinA) * unit
                val wy = y + (ux * sinA + uy * cosA) * unit
                if (vi == 0) gearPath.moveTo(wx, wy) else gearPath.lineTo(wx, wy)
                vi += 2
            }
            gearPath.close()
            // 齿内能量场：中心略亮、边缘透明的径向渐变（Brush 按尺寸缓存，draw 不新建）
            drawPath(gearPath, fillBrushes[idx]!!, alpha = fillAlpha, blendMode = BlendMode.Plus)
            // 双层描边 · 外遍：宽而淡的金色光晕（静态基础 α + energy 缓升；成员 Stroke 复用）
            drawPath(gearPath, gold, style = strokeGlow, alpha = glowStrokeAlpha,
                blendMode = BlendMode.Plus)
            // 双层描边 · 内遍：亮金细线（原 gStroke，1.6 ↔ 2.2px 量化）
            drawPath(gearPath, color, style = gStroke, alpha = alpha,
                blendMode = BlendMode.Plus)

            // 轴毂：显式绝对中心，**无逐轮 rotate 变换**（半径/stroke 均为屏幕像素；
            // 只受整组均匀缩放影响，不随齿轮自转）
            drawCircle(color, radius = tipPx * 0.26f, center = Offset(x, y),
                style = strokeHub, alpha = alpha * 0.8f, blendMode = BlendMode.Plus)

            // 辐条：中 6 / 内 4 / 外 3 根（卫星 ring=2 ⇒ 3 根），端点手算旋转
            val spokeN = when (ring) {
                0 -> 6
                1 -> 4
                else -> 3
            }
            val r0 = tipPx * 0.30f
            val r1 = rootPx * 0.94f
            var sp = 0
            while (sp < spokeN) {
                val sa = sp * TAU / spokeN + angle
                val cs = cos(sa)
                val ss = sin(sa)
                drawLine(color,
                    Offset(x + r0 * cs, y + r0 * ss),
                    Offset(x + r1 * cs, y + r1 * ss),
                    strokeWidth = 1.0f, alpha = alpha * 0.7f, blendMode = BlendMode.Plus)
                sp++
            }
            g++
        }

        // ── 层 4 · 轴心脉动点 + 核心发光光晕（轴静止不随齿轮旋转；pulse 驱动半径。
        //    光晕 2 层渐隐同心圆，Plus 累加 ⇒ 核心亮、外围渐隐；
        //    ringBoost 1.0 / 0.6 / 0.4 ⇒ 中心轮最亮、向外递减（卫星 ring=2 ⇒ 0.4））──
        g = 0
        while (g < drawCount) {
            val idx = if (g < gearCount) g else N_MAIN + (g - gearCount)
            val x = cx + gearX[idx] * unit
            val y = cy + gearY[idx] * unit
            val coreR = gearTip[idx] * unit * (0.05f + frame.pulse * 0.025f)
            val ringBoost = when (gearRing[idx]) {
                0 -> 1f
                1 -> 0.6f
                else -> 0.4f
            }
            drawCircle(gold, radius = coreR * CORE_RING_R_OUT, center = Offset(x, y),
                alpha = CORE_RING_ALPHA_OUT * ringBoost * glowGain, blendMode = BlendMode.Plus)
            drawCircle(gold, radius = coreR * CORE_RING_R_IN, center = Offset(x, y),
                alpha = CORE_RING_ALPHA_IN * ringBoost * glowGain, blendMode = BlendMode.Plus)
            drawCircle(goldDim, radius = coreR, center = Offset(x, y),
                alpha = 0.9f, blendMode = BlendMode.Plus)
            g++
        }

        // ── 层 5 · 青色啮合火花：每个接触点一粒（treble 调 α、pulse 放大、
        //    elapsed 微闪）。主组：每个啮合节点一粒 ─────────────────────────
        var s = 1
        while (s < gearCount) {
            val tw = 0.65f + 0.35f * sin(elapsed * 2.4f + s * 1.7f)
            val rad = (1.8f + frame.pulse * 1.6f) * tw
            drawCircle(cyanSpark, radius = rad,
                center = Offset(cx + sparkX[s] * unit, cy + sparkY[s] * unit),
                alpha = (sparkAlpha * tw).coerceAtMost(0.80f), blendMode = BlendMode.Plus)
            s++
        }

        // 层 5（续）· 卫星组内啮合火花：同一公式、同一 α 上限；锚轮无接触点
        // （gearParent[idx] == idx ⇒ 跳过）。⛔ 参数与主组完全一致，只是覆盖新齿轮。
        var q = 0
        while (q < satCount) {
            val idx = N_MAIN + q
            if (gearParent[idx] != idx) {
                val tw = 0.65f + 0.35f * sin(elapsed * 2.4f + idx * 1.7f)
                val rad = (1.8f + frame.pulse * 1.6f) * tw
                drawCircle(cyanSpark, radius = rad,
                    center = Offset(cx + sparkX[idx] * unit, cy + sparkY[idx] * unit),
                    alpha = (sparkAlpha * tw).coerceAtMost(0.80f), blendMode = BlendMode.Plus)
            }
            q++
        }

        // 整组缩放变换收尾（与上方 save 成对；两行之间无 early-return，恒平衡）
        cvs.restore()
    }
}

/**
 * E34 `FRACTAL_TREE` — 分形 · 极简分形树
 *
 * 视觉：屏幕底部中心一根不断分叉的树状结构。低音让主干变粗、分支向外生长
 * （深度逐层展开）；高频让分支尖端闪烁电弧。
 *
 * 实现：**不每帧递归**。`onEnterContent` 把分叉拓扑拍平进数组（每段记录深度/父段/
 * 角度系数），每帧只做端点计算 + 深度门控绘制。生长 = bass 驱动"展开深度"整数推进。
 *
 * ## §B9 质感升级（T4.9）
 * ① **锥度 + 受光**：线宽由「线性递减」改为**几何递减** `TRUNK_STROKE_W · TAPER^d`
 *    （`TAPER = 0.72f`，与每层长度衰减同系数）；**颜色随层级向亮端插值**
 *    （`depthColorArgb` 表：第 0 层 = `accent`，第 `MAX_DEPTH` 层 =
 *    `towardWhite(accent, TIP_LIGHT_MIX)`）⇒ **越往梢越细越亮**。
 *    ⚠️ 同时把 alpha 曲线由「明显递减」改为**近平** —— 否则「顶梢更亮」会被 alpha
 *    的衰减抵消（见 §12.4）。
 * ② **叶/花**：在当前**生长前沿**的末级节点画**旋转椭圆叶形**（长轴 = 短轴 ×
 *    `LEAF_ASPECT` = 2.2，长轴按 `Shading2D.LIGHT_ANGLE_DEG` 定向）。tip 按下标
 *    **固定分 3 桶**（`leafBucketOf`），每桶的叶长由**一条 spectrum 频段**驱动
 *    （`LEAF_BANDS` = 低 / 中 / 高）⇒ **3 档大小**；每桶合批成 **1 条 `Path`**（共 3 条）。
 * ③ **背景纵深**：`Shading2D.shadeBrushCached` 的**径向纵深**（树根处微亮 → 四角暗，
 *    半径 = 半对角线）+ `ProceduralTexture.Id.STARFIELD` 星野 tile。
 * ④ **后处理**：`postFx = PostFx(vignette = 0.48f, grain = 0.030f)`。
 *
 * ## ⛔ 迁移到 [RendererFx] 时顺带修掉的缺陷
 * 旧实现用 `ctx.nowMs` + `lastMs == 0L` 哨兵算帧间差；[FrameClock] 的 KDoc 明确
 * **不得用 `lastMs == 0L` 当"未初始化"哨兵**（首帧 `timeMs` 可能恰为 0）⇒ 改走
 * `fx.dt`，哨兵由 `FrameClock.initialized` 承担。⚠️ 本效果的生长量
 * （`depthF += growRate · dt`）**本来就是 dt 化的**，无帧率绑定缺陷（与 T4.1–T4.8 不同）。
 *
 * ## ⛔ 叶形的成本（有意取舍，见 §12.4）
 * 每片叶 = `moveTo` + 4 × `cubicTo` —— ⛔ **不能**用 `Path.addOval(Rect)`：Compose 的
 * `addOval` **只有对象重载**（每片叶一次 `Rect` 分配），且**轴对齐**椭圆表达不了
 * 「按光向定向」。MEDIUM 档终态叶数 = 生长前沿节点数 = `2^7 = 128` ⇒ 路径顶点写入
 * ≈ **640 次/帧**。换来的是 §B9 的验收「末级有**叶**」。⛔ 叶**合批成 3 条 `Path`**
 * （不是 128 次 `drawPath`）。⚠️ §7.5 的成本表**低估**了这一项（脚本不把「循环体内
 * 调用的 helper」按调用次数放大，见 §四 G15 同族）。
 *
 * 性能红线：draw 内零分配。
 */
class FractalTreeRenderer : RendererFx() {

    override val theme = VisualizerTheme.FRACTAL_TREE

    /** §B9-④ 后处理（⛔ 数值字面量，基类要求） */
    override val postFx = PostFx(vignette = 0.48f, grain = 0.030f)

    internal companion object {
        const val MAX_DEPTH = 8

        /** 展开深度缓动速率（bass 系数） */
        const val DEPTH_SPEED = 1.8f

        /** 安静段落也缓慢生长的底盘速率（深度/秒） */
        const val DEPTH_BASE = 0.15f

        /** 正在展开的那一层（分数层）的长度 / 叶尺寸折算系数 */
        const val GROW_FRACTIONAL_K = 0.8f

        /** 每层长度衰减 */
        const val LEN_K = 0.72f

        const val ARC_JAG = 3             // 电弧折线段数

        // ── §B9-① 锥度 + 受光 ────────────────────────────────────────
        /** 主干线宽（px） */
        const val TRUNK_STROKE_W = 2.6f

        /** 每层线宽衰减系数（几何级数；与 [LEN_K] 同值） */
        const val TAPER = 0.72f

        /** 线宽下限（保证末梢仍可见） */
        const val MIN_STROKE_W = 0.7f

        /** 顶梢向白插值的比例 ⇒ 越往梢越亮（= 受光） */
        const val TIP_LIGHT_MIX = 0.55f

        /** 主干 alpha */
        const val SEG_ALPHA_BASE = 0.90f

        /** 每层 alpha 衰减（⛔ 只留很小斜率，见类 KDoc） */
        const val SEG_ALPHA_FALLOFF = 0.025f

        /** alpha 下限 */
        const val SEG_ALPHA_MIN = 0.60f

        // ── §B9-② 叶形 ──────────────────────────────────────────────
        /** 叶桶数（= 3 档大小） */
        const val LEAF_BUCKETS = 3

        /** 叶长轴 / 短轴（§B9-②） */
        const val LEAF_ASPECT = 2.2f

        /** 叶半长轴基准（px） */
        const val LEAF_R_MIN = 2.4f

        /** 叶半长轴受频谱驱动的增益 */
        const val LEAF_R_GAIN = 2.1f

        /** 叶填充色向白插值的比例（比枝干更亮 ⇒ 受光） */
        const val LEAF_LIGHT_MIX = 0.72f

        /** 椭圆 4 段三次贝塞尔的 kappa（标准值 0.5523） */
        const val KAPPA = 0.5522847f

        /** 椭圆的三次贝塞尔段数（每段 3 个控制点 ⇒ 共 12 个采样点） */
        const val LEAF_CUBIC_SEGS = 4

        /** 3 桶各自的填充 alpha（"大"桶更实 ⇒ 叶有层次） */
        val LEAF_ALPHAS = floatArrayOf(0.55f, 0.74f, 0.94f)

        /** 3 桶各自绑定的 spectrum 频段（低 / 中 / 高） */
        val LEAF_BANDS = intArrayOf(6, 21, 42)

        /** 单位椭圆 12 个采样点的**长轴方向**局部坐标 */
        val LEAF_U = floatArrayOf(
            1f, 1f, KAPPA, 0f, -KAPPA, -1f, -1f, -1f, -KAPPA, 0f, KAPPA, 1f
        )

        /** 单位椭圆 12 个采样点的**短轴方向**局部坐标 */
        val LEAF_V = floatArrayOf(
            0f, KAPPA, 1f, 1f, 1f, KAPPA, 0f, -KAPPA, -1f, -1f, -1f, -KAPPA
        )

        // ── §B9-③ 背景纵深 ───────────────────────────────────────────
        /** 径向纵深整体 alpha */
        const val BG_DEPTH_ALPHA = 0.30f

        /** 星野 tile alpha */
        const val STAR_ALPHA = 0.32f

        /** 背景纵深底色（accent）的暗化系数 */
        const val BG_DARKEN = 0.62f

        /**
         * `Shading2D.shadeBrushCached` 的**具名盐**。⛔ `Shading2D` 是 Kotlin **object**
         * ⇒ 它的 16 槽 Brush 缓存**进程级共享**；不带盐会在切换效果后复用别人的
         * 半径与基色（§四 G4 / §12.4）。
         */
        const val E34_KEY_SALT = 0x34343434L

        // ── 纯函数（供门禁直调，⛔ 不复制算法）────────────────────────

        /** 生长：`depthF` 的 dt 化推进（30 / 60 / 120 fps 下 1 秒累计量恒等） */
        internal fun advanceDepth(depthF: Float, dtSec: Float, bass: Float, maxDepth: Int): Float =
            (depthF + (DEPTH_BASE + bass * DEPTH_SPEED) * dtSec).coerceAtMost(maxDepth.toFloat())

        /** 深度门控：本段可见长度系数（正在展开的层做分数长度 = 生长感） */
        internal fun growAt(d: Int, depthInt: Int, depthFrac: Float): Float = when {
            d <= depthInt -> 1f
            d == depthInt + 1 -> depthFrac * GROW_FRACTIONAL_K
            else -> 0f
        }

        /** 叶桶下标（按下标**固定**分桶 ⇒ 桶不逐帧跳变，避免叶尺寸闪烁） */
        internal fun leafBucketOf(index: Int): Int {
            val b = index % LEAF_BUCKETS
            return if (b < 0) b + LEAF_BUCKETS else b
        }

        /** 某桶的叶半长轴（由该桶绑定的 spectrum 频段驱动） */
        internal fun leafRadiusOf(spectrumValue: Float): Float =
            LEAF_R_MIN * (1f + spectrumValue.coerceIn(0f, 1f) * LEAF_R_GAIN)

        /**
         * 逐层受光色：第 0 层 = [baseArgb]、第 [depthCap] 层 = [tipArgb]，中间按**通道**
         * 线性插值。⛔ 不用 `androidx.compose.ui.graphics.lerp`（它会走色彩空间转换）——
         * 通道直插更省且**可单测**。
         */
        internal fun depthColorArgbOf(baseArgb: Int, tipArgb: Int, depth: Int, depthCap: Int): Int {
            val t = if (depthCap <= 0) 0f else (depth.toFloat() / depthCap).coerceIn(0f, 1f)
            return (chan(baseArgb, tipArgb, t, 24) shl 24) or
                (chan(baseArgb, tipArgb, t, 16) shl 16) or
                (chan(baseArgb, tipArgb, t, 8) shl 8) or
                chan(baseArgb, tipArgb, t, 0)
        }

        /** 单通道线性插值（含 0..255 夹紧） */
        private fun chan(baseArgb: Int, tipArgb: Int, t: Float, shift: Int): Int {
            val b = (baseArgb shr shift) and 0xFF
            val p = (tipArgb shr shift) and 0xFF
            return (b + (p - b) * t).toInt().coerceIn(0, 255)
        }
    }

    /** 拓扑段数组（onEnterContent 预生成，最大 2^9-1 = 511 段） */
    private var segDepth = IntArray(0)      // 深度 0=主干
    private var segParent = IntArray(0)     // 父段索引（-1=根）
    private var segSide = IntArray(0)       // -1 左枝 / +1 右枝 / 0 主干
    private var segAngleK = FloatArray(0)   // 相对父段的角度偏移系数
    private var segCount = 0

    /** 每帧计算的端点（供深度门控画线用） */
    private var endX = FloatArray(0)
    private var endY = FloatArray(0)
    private var endAng = FloatArray(0)

    /** 当前展开深度（0..MAX_DEPTH，bass 驱动缓慢推进） */
    private var depthF = 0f

    /** 当前展开深度上限（onEnterContent 时固化，避免每帧扫描） */
    private var maxDepth = 8

    /** §B9-① 逐层受光色（ARGB 表；仅在 accent 变化时重算 ⇒ draw 期零分配零 JNI） */
    private val depthColorArgb = IntArray(MAX_DEPTH + 1)
    private var depthColorAccent = Int.MIN_VALUE
    private var leafColorArgb = 0

    /** §B9-② 叶形合批：3 桶各 1 条 `Path`（构造期建一次，draw 期只 `rewind`） */
    private val leafPaths = arrayOf(Path(), Path(), Path())

    protected override fun onEnterContent(ctx: RenderContext) {
        depthF = 0f
        depthColorAccent = Int.MIN_VALUE     // 强制下一帧重算逐层色

        // ── 画质分档：LOW 深度 6 / MED 7 / HIGH 8 ──
        maxDepth = when (ctx.quality) {
            VisualQuality.LOW -> 6
            VisualQuality.MEDIUM -> 7
            VisualQuality.HIGH -> MAX_DEPTH
        }

        // 生成拓扑（前序遍历）
        val cap = (1 shl (maxDepth + 1)) - 1
        segDepth = IntArray(cap)
        segParent = IntArray(cap)
        segSide = IntArray(cap)
        segAngleK = FloatArray(cap)
        endX = FloatArray(cap)
        endY = FloatArray(cap)
        endAng = FloatArray(cap)
        segCount = 0

        // ⛔ 局部量**不叫 `rng`** —— 基类有 `protected val rng`（名字遮蔽会被 lint 记警告）
        var topoRng = 0xA11CEu
        fun nextRand(): Float {
            topoRng = topoRng * 1664525u + 1013904223u
            return (topoRng shr 8).toFloat() / 16777216f
        }

        // 迭代式前序生成（深度优先，父段先于子段）
        fun addSeg(depth: Int, parent: Int, side: Int, angleK: Float) {
            if (segCount >= cap) return
            segDepth[segCount] = depth
            segParent[segCount] = parent
            segSide[segCount] = side
            segAngleK[segCount] = angleK
            segCount++
        }

        // 用显式栈模拟递归（Kotlin 局部函数递归也可，这里用数组栈避免深层调用）
        val stackDepth = IntArray(cap)
        val stackParent = IntArray(cap)
        val stackSide = IntArray(cap)
        val stackAngleK = FloatArray(cap)
        var sp = 0
        stackDepth[sp] = 0; stackParent[sp] = -1; stackSide[sp] = 0; stackAngleK[sp] = 0f
        sp++
        while (sp > 0) {
            sp--
            val d = stackDepth[sp]
            val par = stackParent[sp]
            val side = stackSide[sp]
            val ak = stackAngleK[sp]
            val idx = segCount
            addSeg(d, par, side, ak)
            if (d < maxDepth) {
                // 子段压栈：先压右（后画），再压左（先画）→ 左枝先序
                // 右枝
                stackDepth[sp] = d + 1; stackParent[sp] = idx
                stackSide[sp] = 1
                stackAngleK[sp] = (0.45f + nextRand() * 0.30f)
                sp++
                // 左枝
                stackDepth[sp] = d + 1; stackParent[sp] = idx
                stackSide[sp] = -1
                stackAngleK[sp] = -(0.45f + nextRand() * 0.30f)
                sp++
            }
        }
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        // ── 生长：bass 驱动展开深度（含底盘速率保证安静歌也缓慢生长）──
        // ⛔ dt 只从 fx 取（首帧 / 负差 / 上界钳制由 FrameClock 统一承担）
        depthF = advanceDepth(depthF, fx.dt, frame.bass, maxDepth)
        val depthInt = depthF.toInt()
        val depthFrac = depthF - depthInt

        val accent = ctx.palette.accent
        ensureDepthColors(accent)

        // ── 主干基准（树根：底部中心）──
        val trunkLen = h * 0.22f
        val baseX = w * 0.5f
        val baseY = h * 0.88f

        // ── §B9-③ 背景纵深：① 径向纵深（树根处微亮 → 四角暗）② 星野 tile ──
        // 与 E13 / E15 / E32 同范式：背景**不随 FxLevel 关闭**（它承担"不再浮在纯黑上"），
        // 按档位关的是 vignette / grain（见 postFx）。
        val iw = w.toInt().coerceIn(1, 4096)
        val ih = h.toInt().coerceIn(1, 4096)
        ProceduralTexture.ensure(iw, ih)
        drawRect(
            brush = Shading2D.shadeBrushCached(
                key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                    accent.toArgb().toLong() xor E34_KEY_SALT,
                center = Offset(baseX, baseY),
                radius = sqrt(w * w + h * h) * 0.5f,
                base = VisualizerMath.darken(accent, BG_DARKEN),
                contrast = 0.10f
            ),
            alpha = BG_DEPTH_ALPHA
        )
        ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = STAR_ALPHA)
        }

        // ── §B9-② 叶形：3 桶各合批 1 条 `Path`（尺寸由 3 条 spectrum 频段驱动）──
        val sp = frame.spectrum
        val leafR0 = leafRadiusOf(bandAt(sp, LEAF_BANDS[0]))
        val leafR1 = leafRadiusOf(bandAt(sp, LEAF_BANDS[1]))
        val leafR2 = leafRadiusOf(bandAt(sp, LEAF_BANDS[2]))
        val ld = Shading2D.lightDir            // ⛔ 全库唯一主光方向
        val ux = ld.x; val uy = ld.y           // 叶长轴方向
        val vx = -uy; val vy = ux              // 叶短轴方向（法向）
        var lb = 0
        while (lb < LEAF_BUCKETS) {
            leafPaths[lb].rewind()
            lb++
        }

        // ── 逐段计算端点（前序序保证父先于子）──
        var i = 0
        while (i < segCount) {
            val d = segDepth[i]
            val par = segParent[i]
            val px: Float; val py: Float; val pa: Float
            if (par < 0) {
                px = baseX; py = baseY; pa = -1.5707964f   // -90°（向上）
            } else {
                px = endX[par]; py = endY[par]; pa = endAng[par]
            }
            // 角度：父角 + 分叉系数 + 随 music 轻微摆动
            val sway = frame.treble * 0.10f * d
            val ang = pa + segAngleK[i] * (0.85f + 0.15f * frame.mid) + sway * segSide[i]
            // 深度门控：正在展开的层做分数长度（生长感）
            val grow = growAt(d, depthInt, depthFrac)
            if (grow <= 0f) {
                endX[i] = px; endY[i] = py; endAng[i] = ang
                i++
                continue
            }
            val len = trunkLen * LEN_K.pow(d) * grow * (0.9f + 0.2f * frame.bass)
            endX[i] = px + cos(ang) * len
            endY[i] = py + sin(ang) * len
            endAng[i] = ang
            i++
        }

        // ── 绘制：逐段画线；§B9-① 主干粗、末梢细且**更亮** ──
        i = 0
        while (i < segCount) {
            val d = segDepth[i]
            val par = segParent[i]
            val grow = growAt(d, depthInt, depthFrac)
            if (grow <= 0f) { i++; continue }
            val px: Float; val py: Float
            if (par < 0) { px = baseX; py = baseY } else { px = endX[par]; py = endY[par] }
            val ex = endX[i]; val ey = endY[i]

            // §B9-① 锥度 = 几何递减；受光 = 逐层色表；主干由低音加粗
            val baseW = (TRUNK_STROKE_W * TAPER.pow(d)).coerceAtLeast(MIN_STROKE_W)
            val width = baseW * (1f + frame.bass * 0.9f)
            val alpha = (SEG_ALPHA_BASE - d * SEG_ALPHA_FALLOFF).coerceAtLeast(SEG_ALPHA_MIN)
            val segColor = Color(depthColorArgb[d])

            drawLine(segColor, Offset(px, py), Offset(ex, ey), strokeWidth = width, alpha = alpha)

            // ── 电弧：末梢段（最外两层）+ treble 超阈值时确定性抖动 ──
            if (d >= depthInt - 1 && depthInt >= 2 && frame.treble > 0.40f) {
                drawArcJitter(px, py, ex, ey, width, segColor, frame.seq, i)
            }

            // ── §B9-② 生长前沿的末级节点长叶（尺寸 ∝ grow ⇒ 抽芽感）──
            if (d >= depthInt) {
                val bucket = leafBucketOf(i)
                val r = when (bucket) {
                    0 -> leafR0
                    1 -> leafR1
                    else -> leafR2
                } * grow
                buildLeaf(leafPaths[bucket], ex, ey, r, ux, uy, vx, vy)
            }
            i++
        }

        // ── §B9-② 3 条叶 Path 一次性落笔（⛔ 不是 128 次 `drawPath`）──
        val leafColor = Color(leafColorArgb)
        lb = 0
        while (lb < LEAF_BUCKETS) {
            drawPath(leafPaths[lb], leafColor, alpha = LEAF_ALPHAS[lb])
            lb++
        }
    }

    /**
     * 把一片**旋转椭圆叶**追加进 [path]（中心 = 枝端 `(cx, cy)`；半长轴 [r] 沿主光向）。
     *
     * 成本 = 1 `moveTo` + [LEAF_CUBIC_SEGS] 次 `cubicTo`；⛔ 全程**零分配**
     * （局部坐标表 [LEAF_U] / [LEAF_V] 是常量，`Path` 是构造期建好的成员）。
     */
    private fun buildLeaf(
        path: Path,
        cx: Float, cy: Float, r: Float,
        ux: Float, uy: Float, vx: Float, vy: Float,
    ) {
        if (r <= 0.01f) return
        val b = r / LEAF_ASPECT
        val ax = r * ux; val ay = r * uy
        val bx = b * vx; val by = b * vy
        path.moveTo(cx + LEAF_U[0] * ax + LEAF_V[0] * bx, cy + LEAF_U[0] * ay + LEAF_V[0] * by)
        var k = 0
        while (k < LEAF_CUBIC_SEGS) {
            val i1 = k * 3 + 1
            val i2 = i1 + 1
            val i3 = (i1 + 2) % 12
            path.cubicTo(
                cx + LEAF_U[i1] * ax + LEAF_V[i1] * bx, cy + LEAF_U[i1] * ay + LEAF_V[i1] * by,
                cx + LEAF_U[i2] * ax + LEAF_V[i2] * bx, cy + LEAF_U[i2] * ay + LEAF_V[i2] * by,
                cx + LEAF_U[i3] * ax + LEAF_V[i3] * bx, cy + LEAF_U[i3] * ay + LEAF_V[i3] * by,
            )
            k++
        }
        path.close()
    }

    /**
     * §B9-① 逐层受光色 + 叶色（**仅在 `accent` 变化时重算** ⇒ draw 期零分配、零 JNI）。
     * ⛔ 键就是 `accent.toArgb()`：色表**只**依赖 accent（不含 w/h / 能量）。
     */
    private fun ensureDepthColors(accent: Color) {
        val argb = accent.toArgb()
        if (argb == depthColorAccent) return
        depthColorAccent = argb
        val tip = VisualizerMath.towardWhite(accent, TIP_LIGHT_MIX).toArgb()
        for (k in 0..MAX_DEPTH) depthColorArgb[k] = depthColorArgbOf(argb, tip, k, MAX_DEPTH)
        leafColorArgb = VisualizerMath.towardWhite(accent, LEAF_LIGHT_MIX).toArgb()
    }

    /** 取频谱某频段（越界自动环绕；`barCount` 变档也不崩） */
    private fun bandAt(sp: FloatArray, band: Int): Float =
        if (sp.isEmpty()) 0f else sp[band % sp.size]

    /** 末梢电弧：3 段折线确定性抖动（零分配） */
    private fun DrawScope.drawArcJitter(
        x0: Float, y0: Float, x1: Float, y1: Float,
        baseW: Float, color: Color, seq: Long, idx: Int
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val nx = -dy
        val ny = dx
        val nLen = sqrt(nx * nx + ny * ny).coerceAtLeast(0.001f)
        var prevX = x0
        var prevY = y0
        var s = 1
        while (s <= ARC_JAG) {
            val t = s.toFloat() / ARC_JAG
            val jx = x0 + dx * t
            val jy = y0 + dy * t
            // 确定性抖动：帧序号 + 段索引做种子
            val seed = (seq * 31 + idx * 7 + s) and 0xFFFFL
            val mag = ((seed % 200L) / 200f - 0.5f) * baseW * 2.2f
            val ox = nx / nLen * mag
            val oy = ny / nLen * mag
            val cx = jx + ox
            val cy = jy + oy
            drawLine(color, Offset(prevX, prevY), Offset(cx, cy),
                strokeWidth = baseW * 0.6f, alpha = 0.85f, blendMode = BlendMode.Plus)
            prevX = cx; prevY = cy
            s++
        }
    }

    private fun Float.pow(n: Int): Float {
        var r = 1f
        var b = this
        var e = n
        while (e > 0) {
            if (e and 1 == 1) r *= b
            b *= b
            e = e shr 1
        }
        return r
    }
}

/**
 * E35 `LIGHT_BEAMS` — 光轴 · 旋转光轴
 *
 * 视觉：几束极细光束从屏幕边缘向中心射出（激光灯交叉扫射），带轻微扇形区域。
 * 低音改变光束仰角（缓变），高频让光束瞬间碎裂成虚线（手动分段，避开
 * dashPathEffect 的每帧分配）。
 *
 * 性能红线：draw 内零分配。
 */
class LightBeamsRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.LIGHT_BEAMS

    private companion object {
        const val TAU = (2 * Math.PI).toFloat()
        const val BEAMS = 6
        const val SEGMENTS = 14            // 虚线分段数
        const val ELEV_SMOOTH = 0.06f      // 仰角低通
        const val MAX_ELEV_DEG = 14f
    }

    /** 每束光的静态参数 */
    private var beamPhase = FloatArray(0)
    private var beamSpeed = FloatArray(0)
    private var beamBaseAng = FloatArray(0)
    private var beamCount = 0

    private var elevSmooth = 0f
    private var lastMs = 0L
    private var trebleSmooth = 0f

    override fun onEnter(ctx: RenderContext) {
        elevSmooth = 0f
        lastMs = 0L
        trebleSmooth = 0f

        beamCount = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> 4
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> 6
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> 8
        }
        beamPhase = FloatArray(beamCount)
        beamSpeed = FloatArray(beamCount)
        beamBaseAng = FloatArray(beamCount)
        var i = 0
        while (i < beamCount) {
            beamPhase[i] = i * 2.399f        // 黄金角错相位
            beamSpeed[i] = 0.35f + (i % 3) * 0.18f
            beamBaseAng[i] = i * TAU / beamCount
            i++
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now

        elevSmooth += (frame.bass - elevSmooth) * ELEV_SMOOTH
        trebleSmooth += (frame.treble - trebleSmooth) * 0.25f

        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxLen = kotlin.math.sqrt(w * w + h * h) * 0.55f
        val accent = ctx.palette.accent
        val secondary = ctx.palette.secondary
        val tSec = frame.timeMs * 0.001f

        // ── 逐束绘制 ──
        var i = 0
        while (i < beamCount) {
            // 旋转：各束不同速慢转 + treble 加速
            val ang = beamBaseAng[i] + tSec * beamSpeed[i] * (0.5f + trebleSmooth * 0.8f) +
                beamPhase[i] * 0.1f
            // 仰角扰动：低音驱动（低通后）+ 每束相位差
            val elev = elevSmooth * MAX_ELEV_DEG * 0.01745f * sin(tSec * 0.6f + beamPhase[i])
            val finalAng = ang + elev
            val color = if (i % 2 == 0) accent else secondary
            val broken = frame.treble > 0.50f   // 碎裂触发

            val dx = cos(finalAng)
            val dy = sin(finalAng)
            // 从边缘向中心：起点在半径 1.1×外接圆处，终点在中心附近
            val startR = maxLen * 1.05f
            val endR = maxLen * 0.06f

            if (!broken) {
                // 连续光束：宽淡辉光 + 细亮芯线
                drawLine(
                    color,
                    Offset(cx + dx * startR, cy + dy * startR),
                    Offset(cx + dx * endR, cy + dy * endR),
                    strokeWidth = 7f, alpha = 0.10f, blendMode = BlendMode.Plus
                )
                drawLine(
                    color,
                    Offset(cx + dx * startR, cy + dy * startR),
                    Offset(cx + dx * endR, cy + dy * endR),
                    strokeWidth = 1.6f, alpha = 0.75f, blendMode = BlendMode.Plus
                )
                // 扇形区域（极弱）
                drawFan(cx, cy, finalAng, maxLen, color, 0.05f + frame.energy * 0.05f)
            } else {
                // 碎裂虚线：手动分段 + 确定性闪烁（避开 dashPathEffect 每帧分配）
                val segLen = (startR - endR) / SEGMENTS
                var s = 0
                while (s < SEGMENTS) {
                    val r0 = startR - s * segLen
                    val r1 = r0 - segLen * 0.55f   // 55% 占空比
                    // 确定性明暗
                    val seed = (frame.seq * 17 + i * 31 + s) and 0xFFFFL
                    val flick = ((seed % 5L) < 3L)
                    if (flick) {
                        val a = 0.55f + (seed % 4L) / 4f * 0.35f
                        drawLine(
                            color,
                            Offset(cx + dx * r0, cy + dy * r0),
                            Offset(cx + dx * r1, cy + dy * r1),
                            strokeWidth = 1.8f, alpha = a.toFloat(),
                            blendMode = BlendMode.Plus
                        )
                    }
                    s++
                }
            }
            i++
        }

        // ── 中心光核：pulse 脉动 ──
        val coreR = 6f + frame.pulse * 14f
        drawCircle(accent, radius = coreR * 1.8f, center = Offset(cx, cy), alpha = 0.12f, blendMode = BlendMode.Plus)
        drawCircle(accent, radius = coreR, center = Offset(cx, cy), alpha = 0.55f, blendMode = BlendMode.Plus)
    }

    /** 极弱扇形（光束的面积感） */
    private fun DrawScope.drawFan(
        cx: Float, cy: Float, ang: Float, r: Float, color: Color, alpha: Float
    ) {
        if (alpha <= 0.01f) return
        val halfW = 0.045f
        val p = pathBuf
        p.reset()
        p.moveTo(cx, cy)
        val steps = 6
        var s = 0
        while (s <= steps) {
            val a = ang - halfW + (2 * halfW) * s / steps
            p.lineTo(cx + cos(a) * r, cy + sin(a) * r)
            s++
        }
        p.close()
        drawPath(p, color, alpha = alpha)
    }

    private val pathBuf = Path()
}

