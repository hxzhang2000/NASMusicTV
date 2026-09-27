package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.cos
import kotlin.math.sin

/**
 * E29 `ORBITAL_RINGS` — 轨道 · 太阳系
 *
 * 视觉：完整太阳系——中央恒星随音乐缩放律动、悬于深空星野之上，八大行星
 * （水星→海王星）沿**倾斜视点下的椭圆**轨道公转，轨道间距/尺寸/周期按真实顺序
 * 压缩映射（内快外慢、开普勒味），初相黄金比分摊 → 永不连成一线；土星带明显
 * 倾斜（20°）的椭圆光环，按「远侧半弧 → 行星盘 → 近侧半弧」三段绘制，
 * 遮挡关系正确。每颗行星自带卫星系统（绕行星公转、内快外慢、跟随行星移动）：
 * 地球月球、火卫一/二、木星四颗伽利略卫星、土卫六、天卫 Titania/Oberon、
 * 海卫一（偏粉）——卫星环更细更淡、半径恒小于行星轨道。
 *
 *  - **倾斜视点（[TILT] = 0.5）**：不是「正圆俯视」，而是斜俯视——轨道线、
 *    行星/卫星位置的 **y 分量统一乘 [TILT]** ⇒ 每条轨道都是椭圆，
 *    短轴/长轴恒为 1 : 0.5。[TILT] 含义：**1.0 = 正上方俯视（正圆）、
 *    0.0 = 退化成一条线（⚠️ 永不取 0）**，推荐区间 0.3~0.6；
 *  - **统一投影**：所有位置（行星、卫星、土星环锚点、轨道线包围盒）一律经
 *    [project] 由世界坐标 → 像素，杜绝某条路径漏掉 TILT；
 *    `Offset` 是 `@JvmInline value class`（打包 Long）⇒ 返回零分配；
 *  - **自适应满屏缩放**：`scale = min(w/2/extentX, h/2/extentY) × 0.9`
 *    （0.9 = 留白系数），画布尺寸变化即在 [ensureLayout] 重算，横竖屏各自贴满
 *    （旧版 `unit = minDim` 在横屏只用到约一半宽度）。⛔ **单一统一 scale + 仅
 *    y 方向乘 TILT**——禁止按宽高比分别缩 x/y，椭圆形状只能由 [TILT] 决定；
 *  - **深度分层**：每帧按屏幕 y 把 8 颗行星插入排序进成员 `IntArray`（零分配），
 *    绘制顺序 = 星野 → 轨道线 → 上半（远）行星 → 太阳 → 下半（近）行星：
 *    远侧行星被太阳遮、近侧行星可反过来遮住太阳（自然前后遮挡）；
 *  - **近大远小**：行星半径按自身屏幕 y 微调（[NEAR_FAR_K]，clamp ±10%），
 *    该系数**整体**施加到「星体盘 + 卫星环 + 卫星 + 土星光环」⇒ 环/盘比例不变；
 *  - **天体纯时间驱动**：行星/卫星/轨道/星野完全忽略音频——位置是
 *    `f(elapsed, period, phase)` 的纯函数，数据/绘制分离；
 *  - **中央太阳随音乐律动**（全场唯一消费音频的天体，与旧版一致）：低音 + 节拍
 *    脉冲低通平滑 → 太阳半径涨落（bass×0.14 + pulse×0.11，上限 +25%），总能量
 *    低通 → 辉光强度（0.72..1.0）。⛔ 原始 bass/pulse 逐帧跳动大，必须低通滤波
 *    （BASS_FOLLOW/PULSE_FOLLOW/ENERGY_FOLLOW），直接用会抖成筛子；
 *  - **精度**：elapsed 由逐帧 dt（clamp 0.1s）累加为 Double（⛔ 禁 nowMs × 速率，
 *    长会话 float 精度会掉），角度先对周期取模再算三角 → 输入恒 < 2π；
 *  - **星野**：固定种子 LCG 在 onEnter 生成一次存 FloatArray（永不重掷 → 零闪烁）；
 *    位置按 w/h 归一化 ⇒ **星野不参与倾斜**，只有星点半径随 scale；
 *  - **画质档**：八行星任何档全量保留。LOW 70 星 + 跳过可选卫星（天卫/海卫）+
 *    太阳纯圆层；MEDIUM 140 星 + 全部 11 卫星 + 太阳径向渐变 + 地表/木星条纹；
 *    HIGH 220 星 + 日冕层 + 行星斜上高光。
 *
 * 性能红线：draw 内零分配——轨道线/卫星环/土星环 Stroke 与太阳渐变 Brush 按
 * (w, h, scale) 缓存重建、土星环 Path 成员复用（手工旋转，不用捕获 lambda 的
 * withTransform）、行星屏幕 y 与绘制次序用成员 FloatArray/IntArray 插入排序；
 * List 一律下标遍历（不用迭代器）；坐标全为基本类型局部量。
 */
class OrbitalRingsRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.ORBITAL_RINGS

    /** 卫星（name 仅内部标识、不渲染；半径/轨道为**世界单位**（绘制时 × scale），周期为秒） */
    private data class Moon(
        val name: String,
        val color: Color,
        val radius: Float,
        val orbit: Float,
        val period: Double,
        val phase: Double,
        /** true = 可选卫星，LOW 档跳过 */
        val optional: Boolean
    )

    /** 行星（同 [Moon]；轨道为倾斜视点下的椭圆，短轴 = 长轴 × [TILT]，见 [project]） */
    private data class Planet(
        val name: String,
        val color: Color,
        val radius: Float,
        val orbit: Float,
        val period: Double,
        val phase: Double,
        val moons: List<Moon>
    )

    private companion object {
        /** Double 版 2π：elapsed/period 全程 Double，取模后三角输入恒 < 2π（长会话精度） */
        const val TAU_D = Math.PI * 2.0
        /**
         * 视点倾角（y 分量统一乘此系数 ⇒ 轨道线短轴 = 长轴 × TILT，位置 y 同乘）。
         * **1.0 = 正上方俯视（正圆）、0.0 = 退化成一条线（⚠️ 永不取 0）**，
         * 推荐区间 0.3~0.6；当前 0.5（用户实测「正圆俯视」感太强后的取值）。
         * 形状只由它决定——⛔ 不得混入画布宽高比。
         */
        const val TILT = 0.5f
        /** 满屏缩放留白系数（边缘留 10% 空白，不顶到安全区/裁切） */
        const val SCALE_MARGIN = 0.9f
        /** 近大远小强度：半径 × (1 ± NEAR_FAR_K)，即最近/最远行星半径差 ±10% */
        const val NEAR_FAR_K = 0.10f
        /** 土星光环平面倾角（度）——明显可见的斜环 */
        const val SATURN_TILT = 20f
        /** 光环长半轴 × 土星半径 */
        const val RING_X = 1.75f
        /** 光环投影短轴/长轴（~22° 俯角正弦近似）；必须 < 1，否则近弧不与行星盘交叠 */
        const val RING_Y = 0.38f
        /** 光环带宽（scale 分数） */
        const val RING_W = 0.0060f
        /** 光环半弧手工离散段数（24 段弦误差 < 0.1px，肉眼不可见） */
        const val RING_STEPS = 24
        const val DEG2RAD = 0.017453292f
        const val SUN_R = 0.042f
        const val SUN_GLOW_R = 0.070f

        /** 太阳律动低通系数（原始 bass/pulse/energy 逐帧跳动 → 不滤波会抖成筛子） */
        const val BASS_FOLLOW = 0.10f
        const val PULSE_FOLLOW = 0.25f     // 节拍要跟手，平滑稍轻
        const val ENERGY_FOLLOW = 0.08f
        /** 半径律动 = 1 + bass×0.14 + pulse×0.11（上限 +25%，TV 上保守防不适） */
        const val RADIUS_BASS_GAIN = 0.14f
        const val RADIUS_PULSE_GAIN = 0.11f
        /** 辉光强度 = 0.72 + energy×0.28（0.72..1.0） */
        const val GLOW_ALPHA_BASE = 0.72f
        const val GLOW_ALPHA_GAIN = 0.28f

        const val STAR_MAX = 220

        /** 行星表下标常量（buildSystem 顺序） */
        const val EARTH = 2
        const val JUPITER = 4
        const val SATURN = 5

        const val BG = 0xFF05070D.toInt()
        const val ORBIT_LINE = 0x2E9FB4D8.toInt()
        const val MOON_ORBIT_LINE = 0x249FB4D8.toInt()
        const val SUN_CORE = 0xFFFFCE64.toInt()
        const val SUN_CORE_HOT = 0xFFFFF0B8.toInt()
        const val SUN_HALO = 0xFFFFB84D.toInt()
        const val RING_BACK = 0x8CF5E2B8.toInt()
        const val RING_FRONT = 0xE0F5E2B8.toInt()
        const val EARTH_LAND = 0xB056C07A.toInt()
        const val JUPITER_BAND = 0x5F6B4526.toInt()
        const val SPECULAR = 0x59FFFFFF.toInt()
        const val STAR_BLUE = 0xFFBFD4FF.toInt()
    }

    // ── 数据（构造期一次性生成，draw 只读）─────────────────────────────────

    private val planets: List<Planet> = buildSystem()

    /**
     * 世界系最远水平半径（构造期由 [planets] 派生一次，与画布尺寸无关）。
     * 每颗行星取两种口径的较大者：
     *  ① **卫星可达** = `orbit + max(行星盘半径, 最外卫星 orbit + 卫星半径) × (1 + NEAR_FAR_K)`
     *  ② **光环可达**（土星）= `orbit + radius × RING_X × (1 + NEAR_FAR_K)`
     * 再取全局 max。`× (1 + NEAR_FAR_K)` 是把近大远小的放大上限一并算进 ⇒ 实绘
     * 最远点恒 ≤ `0.9 × 画布半宽/半高`（`extentY = extentX × TILT` 同理竖向成立）。
     * 注：只写「orbit + maxMoonOrbit」会漏掉卫星星体半径与近大远小放大，
     * 边缘会溢出 0.9 留白边界约 1~3%。
     */
    private val extentX: Float = buildExtentX()

    /** 世界系垂直半径 = 水平半径 × [TILT]（椭圆比例只由 TILT 决定，与画幅无关） */
    private val extentY: Float = extentX * TILT

    private val bgColor = Color(BG)
    private val orbitLineColor = Color(ORBIT_LINE)
    private val moonOrbitLineColor = Color(MOON_ORBIT_LINE)
    private val sunCore = Color(SUN_CORE)
    private val sunCoreHot = Color(SUN_CORE_HOT)
    private val sunHalo = Color(SUN_HALO)
    private val ringBack = Color(RING_BACK)
    private val ringFront = Color(RING_FRONT)
    private val earthLand = Color(EARTH_LAND)
    private val jupiterBand = Color(JUPITER_BAND)
    private val specular = Color(SPECULAR)
    private val starWhite = Color.White
    private val starBlue = Color(STAR_BLUE)

    /** 星野（onEnter 固定种子生成一次 → 零闪烁；坐标为 w/h 归一化 0..1，半径/透明度定值） */
    private val starX = FloatArray(STAR_MAX)
    private val starY = FloatArray(STAR_MAX)
    private val starR = FloatArray(STAR_MAX)
    private val starA = FloatArray(STAR_MAX)
    private var starCount = 0

    // ── 画布尺寸相关的缓存（仅转屏/尺寸变化重建）──────────────────────────

    /** 太阳径向渐变（RadialGradient 分配型 → 必须缓存，按 (w, h, scale) 失效重建） */
    private var sunBrush: Brush? = null
    private var brushW = -1f
    private var brushH = -1f
    private var brushScale = -1f

    /** Stroke 宽度按统一 scale 推出 ⇒ 缓存键就是 scale（尺寸变化 → scale 变化 → 重建） */
    private var strokeScale = -1f
    private var orbitStroke: Stroke = Stroke(1f)
    private var moonStroke: Stroke = Stroke(1f)
    private var ringStroke: Stroke = Stroke(1f)

    /** 土星环半弧复用缓冲（成员 Path，reset 后逐帧重画，零分配） */
    private val ringBuf = Path()

    // ── 深度分层缓冲（成员复用 → 每帧插入排序零分配）──────────────────────

    /** 每颗行星的屏幕 y（仅排序用；x/y 由 drawPlanetAt 按同一 [project] 重算，纯函数恒等） */
    private val posY = FloatArray(planets.size)
    /** 按屏幕 y 升序（远 → 近）的行星下标，成员 IntArray 原地插入排序 */
    private val order = IntArray(planets.size)

    // ── 逐帧状态 ────────────────────────────────────────────────────────────

    private var lastMs = 0L
    /** 统一动画时钟（秒，Double，由逐帧 dt 累加——⛔ 不用 nowMs × 速率） */
    private var elapsed = 0.0
    /** 0 = LOW / 1 = MEDIUM / 2 = HIGH（onEnter 解析一次） */
    private var tier = 1

    // ── 太阳律动信号（低通平滑；仅太阳段读取，其余天体不受影响）──
    private var bassSmooth = 0f
    private var pulseSmooth = 0f
    private var energySmooth = 0f

    override fun onEnter(ctx: RenderContext) {
        lastMs = 0L
        elapsed = 0.0
        bassSmooth = 0f
        pulseSmooth = 0f
        energySmooth = 0f
        sunBrush = null
        brushW = -1f
        brushScale = -1f
        strokeScale = -1f
        // ⚠️ 此处是唯一重置 elapsed 的地方（onEnter）；ensureLayout/尺寸变化路径不碰 elapsed

        // ── 画质分档：八行星任何档全量保留，只降装饰 ──
        tier = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> 0
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> 1
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> 2
        }
        starCount = when (tier) {
            0 -> 70
            1 -> 140
            else -> STAR_MAX
        }

        // ── 星野：固定种子 LCG 一次性生成，之后永不重掷（零闪烁）──
        var rng = 0x5EEDF00Du
        fun nextRand(): Float {
            rng = rng * 1664525u + 1013904223u
            return (rng shr 8).toFloat() / 16777216f
        }
        var s = 0
        while (s < STAR_MAX) {
            starX[s] = nextRand()
            starY[s] = nextRand()
            starR[s] = 0.0010f + nextRand() * 0.0020f
            starA[s] = 0.25f + nextRand() * 0.65f
            s++
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        // frame 仅在下方「中央恒星」段被读取（太阳律动）；行星/卫星/轨道/星野纯时间驱动
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        elapsed += dtSec.toDouble()   // ⛔ 禁 nowMs × 速率：大时间基数 float 精度会掉
        // ⚠️ 上面两行是 elapsed 的唯一推进路径；尺寸变化只走 ensureLayout（不碰 elapsed）

        // 自适应满屏统一缩放（画布尺寸变化即重算；⛔ 不得分别缩 x/y）
        val scale = ensureLayout(w, h)
        val center = Offset(w * 0.5f, h * 0.5f)
        // 系统在屏幕上的垂直半径 —— 近大远小的归一化分母（横竖屏观感一致）
        val extentYScreen = extentY * scale

        // ── 深空底色 + 星野（位置 onEnter 固定 → 零闪烁；星野按 w/h 归一化、不倾斜）──
        drawRect(bgColor)
        drawStars(w, h, scale)

        // ── 轨道线（倾斜视点椭圆：短轴 = 长轴 × TILT，包围盒统一经 project）──
        var pi = 0
        while (pi < planets.size) {
            val r = planets[pi].orbit
            val tl = project(-r, -r, center, scale)
            val br = project(r, r, center, scale)
            drawOval(
                color = orbitLineColor,
                topLeft = tl,
                size = Size(br.x - tl.x, br.y - tl.y),
                style = orbitStroke
            )
            pi++
        }

        // ── 行星屏幕 y 预计算 + 按 y 升序插入排序（深度分层，成员数组零分配）──
        var i = 0
        while (i < planets.size) {
            val p = planets[i]
            val pa = angleAt(elapsed, p.period, p.phase)
            posY[i] = project(cos(pa) * p.orbit, sin(pa) * p.orbit, center, scale).y
            i++
        }
        i = 0
        while (i < planets.size) {
            order[i] = i
            i++
        }
        i = 1
        while (i < planets.size) {            // 插入排序（8 元素，原地，零分配）
            val key = order[i]
            val ky = posY[key]
            var j = i - 1
            while (j >= 0 && posY[order[j]] > ky) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = key
            i++
        }

        // ── 远侧行星（屏幕 y < 中线）：画在太阳之前 → 被太阳遮挡 ──
        i = 0
        while (i < planets.size && posY[order[i]] < center.y) {
            drawPlanetAt(order[i], center, scale, extentYScreen)
            i++
        }

        // ── 中央恒星：全场唯一随音乐律动的天体（低音+节拍 → 半径，能量 → 辉光强度）
        //    三路信号均低通平滑（E29 旧版直接用原始值；邻居 RadarGrid 同款 bassSmooth 写法）──
        bassSmooth += (frame.bass - bassSmooth) * BASS_FOLLOW
        pulseSmooth += (frame.pulse - pulseSmooth) * PULSE_FOLLOW
        energySmooth += (frame.energy - energySmooth) * ENERGY_FOLLOW
        val sunScale = 1f + bassSmooth * RADIUS_BASS_GAIN + pulseSmooth * RADIUS_PULSE_GAIN
        val sunR = scale * SUN_R * sunScale
        val glowAlpha = GLOW_ALPHA_BASE + energySmooth * GLOW_ALPHA_GAIN

        if (tier == 0) {
            drawCircle(sunHalo, radius = sunR * 2.6f, center = center, alpha = 0.10f + bassSmooth * 0.08f)
            drawCircle(sunHalo, radius = sunR * 1.7f, center = center, alpha = 0.20f + bassSmooth * 0.10f)
        } else {
            val sb = sunBrush
            // 辉光渐变几何按 (w, h, scale) 烘焙固定 → 只调 alpha 做强度律动（放大半径裁不出渐变外圈）
            if (sb != null) drawCircle(brush = sb, radius = scale * SUN_GLOW_R, center = center, alpha = glowAlpha)
            if (tier == 2) {
                drawCircle(sunHalo, radius = sunR * 3.1f, center = center, alpha = 0.12f + bassSmooth * 0.06f)
            }
        }
        drawCircle(sunCore, radius = sunR, center = center)
        drawCircle(sunCoreHot, radius = sunR * 0.62f, center = center, alpha = 0.95f)

        // ── 近侧行星（屏幕 y ≥ 中线）：画在太阳之后 → 可遮住太阳（用户要求的前后遮挡）──
        while (i < planets.size) {
            drawPlanetAt(order[i], center, scale, extentYScreen)
            i++
        }
    }

    /**
     * 统一投影：世界坐标（轨道平面，单位与 [planets] 表一致）→ 画布像素。
     * x 乘 [scale]，y 乘 `scale × TILT` ⇒ 所有圆在屏幕上都是同一个 1 : TILT 的
     * 椭圆（**形状只取决于 TILT，与画布宽高比无关**）。位置、轨道线包围盒、
     * 土星环锚点一律经此函数，杜绝漏掉倾斜。
     *
     * `Offset` 在 compose-ui-geometry 1.6.1 中是 `@kotlin.jvm.JvmInline
     * value class Offset(packedValue: Long)` ⇒ 返回/传参均**零分配**
     * （仅在泛型/可空/接口场景才装箱，本类不涉及）。
     */
    private fun project(worldX: Float, worldY: Float, center: Offset, scale: Float): Offset =
        Offset(center.x + worldX * scale, center.y + worldY * scale * TILT)

    /**
     * 绘制单颗行星系统（内部顺序固定，不可调整）：
     * 卫星环 → 土星远弧 → 行星盘 → 细节 → 高光 → 土星近弧 → 卫星本体。
     *
     * @param idx 行星下标（由深度分层排序结果给出）
     * @param extentYScreen 系统屏幕垂直半径，近大远小的归一化分母
     */
    private fun DrawScope.drawPlanetAt(
        idx: Int, center: Offset, scale: Float, extentYScreen: Float
    ) {
        val p = planets[idx]
        val pa = angleAt(elapsed, p.period, p.phase)
        val wx = cos(pa) * p.orbit          // 世界坐标（卫星在此基础上叠加）
        val wy = sin(pa) * p.orbit
        val pos = project(wx, wy, center, scale)
        val px = pos.x
        val py = pos.y

        // 近大远小：按行星自身屏幕 y 归一化并 clamp ±NEAR_FAR_K（幅度小，不会盖住邻近轨道）
        val depth = (1f + NEAR_FAR_K * (py - center.y) / extentYScreen)
            .coerceIn(1f - NEAR_FAR_K, 1f + NEAR_FAR_K)
        // ⚠️ 单一系数整体作用于 盘 + 卫星环 + 卫星 + 土星光环 ⇒ 各环/盘比例恒定
        val pr = p.radius * scale * depth

        // 卫星轨道环（细淡线；椭圆包围盒经 project，与行星轨道同一倾斜约定）
        var mi = 0
        while (mi < p.moons.size) {
            val mo = p.moons[mi]
            if (tier > 0 || !mo.optional) {
                val tl = project(wx - mo.orbit, wy - mo.orbit, center, scale)
                val br = project(wx + mo.orbit, wy + mo.orbit, center, scale)
                drawOval(
                    color = moonOrbitLineColor,
                    topLeft = tl,
                    size = Size(br.x - tl.x, br.y - tl.y),
                    style = moonStroke
                )
            }
            mi++
        }

        // 土星：远侧半弧 → 行星盘 → 近侧半弧（遮挡正确）
        if (idx == SATURN) drawSaturnRing(px, py, pr, ringStroke, back = true)

        drawCircle(p.color, radius = pr, center = pos)

        // 行星细节（LOW 跳过）
        if (tier > 0) {
            when (idx) {
                EARTH -> drawCircle(
                    earthLand, radius = pr * 0.40f,
                    center = Offset(px + 0.30f * pr, py - 0.18f * pr)
                )
                JUPITER -> {
                    drawOval(
                        jupiterBand,
                        topLeft = Offset(px - 0.85f * pr, py - 0.62f * pr),
                        size = Size(1.70f * pr, 0.30f * pr)
                    )
                    drawOval(
                        jupiterBand,
                        topLeft = Offset(px - 0.88f * pr, py + 0.27f * pr),
                        size = Size(1.76f * pr, 0.26f * pr)
                    )
                }
            }
        }
        // HIGH：统一斜上高光（偏移 + 半径 ≤ 0.71r → 恒在盘内）
        if (tier == 2) {
            drawCircle(
                specular, radius = pr * 0.20f,
                center = Offset(px - 0.36f * pr, py - 0.36f * pr)
            )
        }

        if (idx == SATURN) drawSaturnRing(px, py, pr, ringStroke, back = false)

        // 卫星本体：世界坐标整体过 project（含 TILT），半径同享 depth 系数
        mi = 0
        while (mi < p.moons.size) {
            val mo = p.moons[mi]
            if (tier > 0 || !mo.optional) {
                val ma = angleAt(elapsed, mo.period, mo.phase)
                drawCircle(
                    mo.color,
                    radius = mo.radius * scale * depth,
                    center = project(wx + cos(ma) * mo.orbit, wy + sin(ma) * mo.orbit, center, scale)
                )
            }
            mi++
        }
    }

    // ── 纯函数 / 缓存维护 ────────────────────────────────────────────────────

    /**
     * 纯函数：统一时钟 [time] → 轨道角（rad）。
     * 先 `time % period` 再乘 2π → 三角输入恒 < 2π，Double 全程（长会话精度）。
     */
    private fun angleAt(time: Double, period: Double, phase: Double): Float {
        val t = ((time % period) / period + phase) % 1.0
        return (t * TAU_D).toFloat()
    }

    /**
     * 土星倾斜椭圆光环半弧：back = 远侧半弧（画在行星盘之前），false = 近侧半弧（之后）。
     *
     * 几何是**行星本地屏幕坐标**（`rx = pr × RING_X`，[pr] 已含近大远小系数），
     * 旋转 SATURN_TILT 后锚定在 [project] 给出的行星中心 (px, py) 上——
     * ⚠️ 视点倾斜 [TILT] **不作用于光环自身的平面**（否则会被压两遍、近弧不再与盘交叠）。
     *
     * 手工参数方程 + 逐点旋转写入成员 [ringBuf]——不用 `withTransform`（其捕获 lambda
     * 每帧分配 2 个对象，违反零分配红线），Path 复用零分配。
     */
    private fun DrawScope.drawSaturnRing(
        px: Float, py: Float, pr: Float, stroke: Stroke, back: Boolean
    ) {
        val rx = pr * RING_X
        val ry = rx * RING_Y
        val rad = SATURN_TILT * DEG2RAD
        val cosT = cos(rad)
        val sinT = sin(rad)
        val a0 = if (back) 180f else 0f      // 180..360 过 12 点 = 远侧
        val stepDeg = 180f / RING_STEPS
        ringBuf.reset()
        var k = 0
        while (k <= RING_STEPS) {
            val a = (a0 + stepDeg * k) * DEG2RAD
            val ex = cos(a) * rx             // 环平面局部坐标
            val ey = sin(a) * ry
            val x = px + ex * cosT - ey * sinT   // 绕行星中心旋转 SATURN_TILT
            val y = py + ex * sinT + ey * cosT
            if (k == 0) ringBuf.moveTo(x, y) else ringBuf.lineTo(x, y)
            k++
        }
        drawPath(ringBuf, color = if (back) ringBack else ringFront, style = stroke)
    }

    /** 星野（固定种子、onEnter 生成 → 位置永不变化、零闪烁；每 4 颗一颗偏蓝） */
    private fun DrawScope.drawStars(w: Float, h: Float, scale: Float) {
        var s = 0
        while (s < starCount) {
            drawCircle(
                color = if ((s and 3) == 0) starBlue else starWhite,
                radius = starR[s] * scale,
                center = Offset(starX[s] * w, starY[s] * h),
                alpha = starA[s]
            )
            s++
        }
    }

    /**
     * 自适应满屏缩放 + 画布缓存维护，返回本帧统一 scale（世界单位 → 像素）。
     *
     * `scale = min(w/2/extentX, h/2/extentY) × SCALE_MARGIN`（0.9 = 留白系数），
     * 画布尺寸变化（转屏/分屏）即重算；横竖屏各自贴到 0.9 边界。
     * ⛔ 单一统一 scale：x/y **绝不**按宽高比分别缩放，椭圆形状只由 [TILT] 决定。
     * ⛔ 本函数不触碰 `elapsed` —— 尺寸变化不会让动画倒退/重置。
     *
     * Stroke 按 scale 缓存、太阳径向渐变按 (w, h, scale) 缓存（其分配型 →
     * 必须重建节流），其余帧直接复用（draw 零分配）。LOW 不建渐变（sunBrush 保持 null）。
     */
    private fun ensureLayout(w: Float, h: Float): Float {
        val s = minOf(w * 0.5f / extentX, h * 0.5f / extentY) * SCALE_MARGIN
        if (tier > 0 && (sunBrush == null || brushW != w || brushH != h || brushScale != s)) {
            brushW = w
            brushH = h
            brushScale = s
            sunBrush = Brush.radialGradient(
                0f to Color(0xFFFFEBB4.toInt()),
                0.35f to Color(0x9FFFB347.toInt()),
                1f to Color(0x00FF9020.toInt()),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = s * SUN_GLOW_R
            )
        }
        if (strokeScale != s) {
            strokeScale = s
            orbitStroke = Stroke((s * 0.0016f).coerceAtLeast(1.2f))
            moonStroke = Stroke((s * 0.0011f).coerceAtLeast(1f))
            ringStroke = Stroke(s * RING_W)
        }
        return s
    }

    /**
     * 构造期由 [planets] 派生世界系最远水平半径（见 [extentX] 注释）。
     * 两种可达口径取 max：卫星系（盘/最外卫星）与土星光环。
     */
    private fun buildExtentX(): Float {
        var maxReach = 0f
        var pi = 0
        while (pi < planets.size) {
            val p = planets[pi]
            var local = p.radius                    // ① 行星盘
            var mi = 0
            while (mi < p.moons.size) {             // ② 最外卫星（环半径 + 星体半径）
                val reach = p.moons[mi].orbit + p.moons[mi].radius
                if (reach > local) local = reach
                mi++
            }
            if (pi == SATURN) {                     // ③ 土星光环长半轴
                val ringReach = p.radius * RING_X
                if (ringReach > local) local = ringReach
            }
            val reach = p.orbit + local * (1f + NEAR_FAR_K)
            if (reach > maxReach) maxReach = reach
            pi++
        }
        return maxReach
    }

    /**
     * 行星/卫星数据表（构造期一次性生成；name 仅内部标识、不参与绘制）。
     * 半径/轨道 = **世界单位**（绘制时统一 × [scale]；远轨道更大、大天体更大、卫星轨道 < 行星轨道、卫星 < 行星）；
     * 周期为显示秒（真实顺序压缩：内快外慢）；初相黄金比分摊 → 永不连成一线。
     */
    private fun buildSystem(): List<Planet> {
        fun orbitPhase(i: Int): Double = (i * 0.618033988749895 + 0.17) % 1.0
        var moonIdx = 0
        fun moonPhase(): Double = ((moonIdx++) * 0.754877666246693 + 0.41) % 1.0

        return listOf(
            Planet("Mercury", Color(0xFF9C9A94.toInt()), 0.0060f, 0.095f, 12.0, orbitPhase(0), emptyList()),
            Planet("Venus", Color(0xFFF2E3B8.toInt()), 0.0095f, 0.134f, 20.0, orbitPhase(1), emptyList()),
            Planet("Earth", Color(0xFF4A93E0.toInt()), 0.0105f, 0.176f, 30.0, orbitPhase(2), listOf(
                Moon("Moon", Color(0xFFCFD2D6.toInt()), 0.0040f, 0.0270f, 8.0, moonPhase(), optional = false)
            )),
            Planet("Mars", Color(0xFFC45A3A.toInt()), 0.0075f, 0.216f, 45.0, orbitPhase(3), listOf(
                Moon("Phobos", Color(0xFFA3968A.toInt()), 0.0024f, 0.0150f, 3.5, moonPhase(), optional = false),
                Moon("Deimos", Color(0xFFB5A99C.toInt()), 0.0028f, 0.0218f, 5.5, moonPhase(), optional = false)
            )),
            Planet("Jupiter", Color(0xFFC9A063.toInt()), 0.0225f, 0.282f, 90.0, orbitPhase(4), listOf(
                Moon("Io", Color(0xFFE8D46A.toInt()), 0.0068f, 0.0349f, 6.0, moonPhase(), optional = false),
                Moon("Europa", Color(0xFFEAE6DE.toInt()), 0.0060f, 0.0416f, 9.0, moonPhase(), optional = false),
                Moon("Ganymede", Color(0xFFBFA98C.toInt()), 0.0078f, 0.0484f, 13.0, moonPhase(), optional = false),
                Moon("Callisto", Color(0xFF8C7B6B.toInt()), 0.0074f, 0.0518f, 19.0, moonPhase(), optional = false)
            )),
            Planet("Saturn", Color(0xFFE0C089.toInt()), 0.0195f, 0.356f, 150.0, orbitPhase(5), listOf(
                Moon("Titan", Color(0xFFE0A85C.toInt()), 0.0068f, 0.0429f, 24.0, moonPhase(), optional = false)
            )),
            Planet("Uranus", Color(0xFFB3E3E8.toInt()), 0.0135f, 0.417f, 240.0, orbitPhase(6), listOf(
                Moon("Titania", Color(0xFFC9CBC8.toInt()), 0.0040f, 0.0209f, 30.0, moonPhase(), optional = true),
                Moon("Oberon", Color(0xFFB0AAA4.toInt()), 0.0038f, 0.0236f, 42.0, moonPhase(), optional = true)
            )),
            Planet("Neptune", Color(0xFF5C7CE8.toInt()), 0.0125f, 0.452f, 380.0, orbitPhase(7), listOf(
                Moon("Triton", Color(0xFFE3A9B8.toInt()), 0.0045f, 0.0169f, 26.0, moonPhase(), optional = true)
            ))
        )
    }
}
