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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E29 `ORBITAL_RINGS` — 太阳系
 *
 * 视觉：完整太阳系——中央恒星随音乐缩放律动、悬于深空星野之上，八大行星
 * （水星→海王星）沿**倾斜视点下的椭圆**轨道公转，轨道间距/尺寸/周期按真实顺序
 * 压缩映射（内快外慢、开普勒味），初相黄金比分摊 → 永不连成一线；土星带明显
 * 倾斜（20°）的椭圆光环，按「远侧半弧 → 行星盘 → 近侧半弧」三段绘制，
 * 遮挡关系正确。每颗行星自带卫星系统（绕行星公转、内快外慢、跟随行星移动）：
 * 地球月球、火卫一/二、木星四颗伽利略卫星、土卫六、天卫 Titania/Oberon、
 * 海卫一（偏粉）——卫星环更细更淡、半径恒小于行星轨道；卫星本体同样按轨道
 * 远/近侧**分两段**绘制（远侧画在行星盘之前 ⇒ 转到行星背后时被盘遮住）。
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
 *  - **星野（视觉层级受 §13.5-D5 约束，⛔ 不得改回均匀分布）**：固定种子 LCG 在 [onEnter]
 *    生成一次存 FloatArray（永不重掷 → 零闪烁）；位置按 w/h 归一化 ⇒ **星野不参与倾斜**，
 *    只有星点半径随 scale。半径 = **立方幂律** `STAR_R_MIN + u³ × STAR_R_SPAN`
 *    （上限 `0.00150f` = 场景最小实体（火卫一 Phobos `0.0024f`）的 **62.5%**）⇒ 绝大多数是
 *    亚像素暗星、只有约 **3.5%** 落在上限 10% 区间；alpha = `u²` **同源** + 25% 抖动
 *    （上限 `0.50f`）⇒ 大星更亮、层级自然，且不会出现「小而亮」的孤立亮点（§四 G18 第 4 条）；
 *    **中心静默区** `QUIET_R = 0.15f`（内太阳系不出现前景亮星，符合真实行星际空间）。
 *    ⚠️ 视觉层级链（世界单位）：太阳 `0.0420` > Jupiter `0.0225` > Mercury `0.0060` >
 *    Phobos `0.0024` > **最大星 `0.00150`**；
 *  - **程序化质感增强**（全部纯代码，⛔ 零贴图 / 零 assets / 零 native 堆 / 零 fx import）：
 *    ① **晨昏线**（MEDIUM+）—— 光源在画面中心，每颗行星按 `atan2(画面中心 − 行星位置)`
 *    把预烘的单位圆夜侧遮罩转到背日一侧，叠一层压暗的同色（分界是带蒙影鼓出的浅弧，不是硬切）；
 *    ② **大气边缘光**（HIGH）—— 单元空间径向渐变（透明→白→透明）经 canvas 缩放摆到盘缘外一圈；
 *    ③ **木星 / 土星带状云纹**（MEDIUM+）—— **米白带区（Zone）与红棕带条（Belt）按纬度交替**
 *    （不是「同色平行条纹」），且**宽度随纬度递减**（赤道附近最宽、向两极收窄成细带）；
 *    每条带 = 上下两条折线围成的透镜带：**公共扰动同相**（整条带一起蜿蜒、带厚恒定）+
 *    **上下边缘各自异相/异频的褶皱**（K < 0.5 ⇒ 两边缘永不相交，带不会被自己掐断），
 *    横向半宽按圆盘弦长收缩 ⇒ 恒不溢出盘面，四层正弦近似 fbm 随 `elapsed` 漂移；
 *    ④ **太阳米粒组织**（HIGH）—— 96 段噪声圆替代纯色圆盘（提交数不变）；
 *    ⑤ **土星环卡西尼缝**（HIGH）—— 同一条 Path 追加同心内圈，两圈之间留白即缝；
 *    ⑥ **彗星**（全档）—— 见 [drawComet]，`elapsed` 的纯函数、禁 `Random`；
 *    **柔边锥形双尾**（离子尾蓝白笔直、严格背日 + 尘埃尾淡黄弯曲、偏向行进反侧），
 *    每条尾由 3 层同形状递减 alpha 的锥形叠加 ⇒ 尾缘柔和、宽度连续收窄、尾尖同点收尖
 *    （⛔ 旧版是「根/中/尖」三点折线拼的硬边多边形，观感是「贴上去的纸片」），
 *    外加**彗发（coma）**：缓存的单元圆径向渐变经 canvas 缩放摆位 ⇒ 柔边弥散光晕，
 *    尾从光晕里长出来，而不是从一个硬点长出来；
 *    ⑦ **木星大红斑**（HIGH）—— 见 [drawGreatRedSpot]，南纬 ~20° 的横向涡旋，
 *    单元空间径向渐变经 canvas 缩放摆放 ⇒ 柔边（非硬边椭圆），随木星自转做「近中央快、
 *    近盘缘慢 + 横向压扁 + 边缘淡出」的透视漂移，**画在行星盘之后、晨昏线之前**（夜侧被正确压暗）；
 *  - **画质档**：八行星任何档全量保留。LOW 70 星 + 跳过可选卫星（天卫/海卫）+
 *    太阳纯圆层 + 无晨昏线 / 无云带 / 无大气光 / 彗星只画核、双尾与彗发（不画轨道弧）；
 *    MEDIUM 140 星 + 全部 11 卫星 + 太阳径向渐变 + 晨昏线 + 云带（木 4 / 土 3）+ 彗星轨道（32 段）；
 *    **彗星（双尾 + 彗发）三档完全一致**（它只在约 1/4 的时间出现 ⇒ 无需分档）；
 *    HIGH 220 星 + 日冕层 + 行星斜上高光 + 大气边缘光 + 米粒组织 + 卡西尼缝 +
 *    云带（木 10 / 土 8）+ **木星大红斑** + 彗星轨道（64 段）与彗核致密晕。
 *    HIGH 220 星 + 日冕层 + 行星斜上高光 + 大气边缘光 + 米粒组织 + 卡西尼缝 +
 *    云带（木 10 / 土 8）+ **木星大红斑** + 彗星轨道（64 段）与彗头光晕。
 *
 * 性能红线：draw 内零分配——轨道线/卫星环/土星环 Stroke 与太阳渐变 Brush 按
 * (w, h, scale) 缓存重建、土星环 Path 成员复用（手工旋转，不用捕获 lambda 的
 * withTransform）、行星屏幕 y 与绘制次序用成员 FloatArray/IntArray 插入排序；
 * List 一律下标遍历（不用迭代器）；坐标全为基本类型局部量。晨昏线 / 大气光 / 彗尾
 * 需要「缩放 + 旋转摆放一份单元几何」时，一律走原生
 * `drawContext.canvas.save → translate → rotate/scale → draw… → restore`
 * （同 [BatchFourRenderers] 的齿轮组整组缩放范式）⇒ 零分配，且不受 Compose
 * `DrawScope` 只有块版本变换（每帧分配捕获 lambda）的限制。
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

    internal companion object {
        /** Double 版 2π：elapsed/period 全程 Double，取模后三角输入恒 < 2π（长会话精度） */
        const val TAU_D = Math.PI * 2.0
        /** Float 版 π：彗尾肩部鼓出剖面用（⛔ 避免在每帧路径里混入 Double 字面量） */
        const val PI_F = 3.14159265f
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

        // ── 星野（§C1 第 0 条 P0 · §四 G18 / §13.5-D5 钉死；⛔ 不得改回均匀分布）─────

        /**
         * 星点半径下限（世界单位）。1080p 上 ≈ **0.64 px** ⇒ 真正的**亚像素暗星**做衬底
         * （改造前下限 `0.0010f` ≈ 1.8 px —— 最小星也是一个可见圆点）。
         */
        const val STAR_R_MIN = 0.00035f
        /**
         * 星点半径区间：上限 = `STAR_R_MIN + STAR_R_SPAN` = **0.00150f**
         * = 场景最小实体（火卫一 Phobos `0.0024f`）的 **62.5%**
         * ⇒ 恢复「星 < 卫星 < 行星 < 太阳」。⛔ 不得抬回改造前的 `0.0030f`（越界值）。
         */
        const val STAR_R_SPAN = 0.00115f
        /** 星点 alpha 下限（几乎看不见的底噪）。⛔ 不得低于 `0.08f` ⇒ 远景一片纯黑（§九 R19） */
        const val STAR_A_MIN = 0.12f
        /** 星点 alpha 区间：上限 = `STAR_A_MIN + STAR_A_SPAN` = **0.50f**（改造前 `0.90f`） */
        const val STAR_A_SPAN = 0.38f
        /**
         * 中心静默区半径（**世界单位**）—— 介于金星轨道 `0.134` 与地球轨道 `0.176` 之间
         * ⇒ 「内太阳系」不出现前景亮星（真实行星际空间也没有）。
         */
        const val QUIET_R = 0.15f
        /** 静默区中心的 alpha 压制比例（区内线性 `0.25 → 1.0`；⛔ 不是"关掉"，只是压暗） */
        const val QUIET_FLOOR = 0.25f

        /**
         * 星野生成（**纯函数**：只写传入数组，与 [OrbitalRingsRenderer.onEnter] 共用同一份实现，
         * 门禁可直接调用 ⇒ 不复制算法）。
         *
         * ⚠️ **LCG 消耗顺序 = `u → x → y → jitter`**（每星 4 次）：`u` 同时驱动**半径**
         * （`u³` 立方幂律）与**亮度**（`u²` 同源），`jitter` 只给 alpha 加 25% 抖动。
         * ⛔ 顺序不可交换 —— 交换会改变整批星的位置（位置本身无意义，但**同源性**有意义）。
         *
         * 参数化形参（`sizePow` / `alphaShared` 等）**只为门禁的负向自证**提供
         * 「同一份谓词 + 不同生成参数」的对照；生产调用一律走默认值。
         */
        internal fun fillStars(
            starX: FloatArray, starY: FloatArray, starR: FloatArray, starA: FloatArray,
            rMin: Float = STAR_R_MIN, rSpan: Float = STAR_R_SPAN,
            aMin: Float = STAR_A_MIN, aSpan: Float = STAR_A_SPAN,
            sizePow: Int = 3, alphaShared: Boolean = true,
        ) {
            var rng = 0x5EEDF00Du
            fun nextRand(): Float {
                rng = rng * 1664525u + 1013904223u
                return (rng shr 8).toFloat() / 16777216f
            }
            var s = 0
            while (s < starX.size) {
                val u = nextRand()                            // ① 尺寸 / 亮度主参数（幂律）
                starX[s] = nextRand()                         // ②
                starY[s] = nextRand()                         // ③
                val j = nextRand()                            // ④ alpha 抖动
                val uu = if (sizePow >= 3) u * u * u else if (sizePow == 2) u * u else u
                starR[s] = rMin + uu * rSpan
                // 亮度与尺寸同源（u²）+ 25% 抖动 ⇒ 大星更亮、层级自然，又不完全共线
                val k = if (alphaShared) u * u * 0.75f + j * 0.25f else j
                starA[s] = aMin + k * aSpan
                s++
            }
        }

        /**
         * 中心静默区 alpha 归一化（**纯函数**，零 `sqrt` / 零除法 / 零分配）。
         *
         * [e] = **椭圆归一化距离的平方**（`0` = 画面中心，`1` = 静默区边界）：
         * 区内线性压到 [QUIET_FLOOR]，区外恒 `1f`。
         * ⛔ 必须保留 `e >= 1f` 的**截断** —— 去掉它静默区外也会被压暗（那等于全场降亮度）。
         */
        internal fun quietAlpha(e: Float): Float =
            if (e >= 1f) 1f else QUIET_FLOOR + (1f - QUIET_FLOOR) * e

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
        const val SPECULAR = 0x59FFFFFF.toInt()
        const val STAR_BLUE = 0xFFBFD4FF.toInt()
        /**
         * 木星云带**亮带（Zone）基色**——不透明 ⇒ alpha 完全由逐条系数控制。
         * 真实木星的「带区」是米白/浅奶油色（赤道带区 EZ 最亮），不是暗棕。
         */
        const val JUPITER_ZONE = 0xFFD8C4A0.toInt()
        /**
         * 木星云带**暗带（Belt）基色**——红棕（真实木星的 NEB/SEB/NTB/STB）。
         * 旧版「所有带同色（暗棕）、只靠 alpha 交替」正是「扁平行条纹」观感的根因：
         * 明暗必须来自**基色**（米白 vs 红棕），alpha 只做浓淡微调。
         */
        const val JUPITER_BELT = 0xFF8B4A2F.toInt()
        /** 极区（最外一圈云带）：真实木星两极是灰暗的「极区罩」，比温带更暗更中性 */
        const val JUPITER_POLAR = 0xFF7A6E62.toInt()
        /** 土星云带亮/暗基色（土星是奶油+淡金 ⇒ 明度差比木星小，观感更柔和） */
        const val SATURN_ZONE = 0xFFE6D3A8.toInt()
        const val SATURN_BELT = 0xFF8A6A38.toInt()
        const val SATURN_POLAR = 0xFF9A8C72.toInt()
        const val COMET_TAIL = 0xFFCFE3FF.toInt()
        const val COMET_NUCLEUS = 0xFFFFFFFF.toInt()
        /** 大红斑：柔边晕圈（橙红，低 alpha）/ 涡核（砖红，更饱和）。均为不透明基色 + 逐层 alpha */
        const val GRS_HALO = 0xFFC1502E.toInt()
        const val GRS_CORE = 0xFFB23A1E.toInt()

        // ── 程序化增强常量（E29 纯代码渲染，零贴图/零 assets/零 native 堆/零 fx import）──────
        /** 晨昏线：夜侧叠加 alpha */
        const val PLANET_NIGHT_ALPHA = 0.6f
        /** 晨昏线夜侧的颜色压暗系数（0 = 纯黑、1 = 原色；⛔ 不可用原色，同色叠加等于没画） */
        const val PLANET_NIGHT_SHADE = 0.34f
        /** 晨昏线过渡带宽度（单位圆半径分数，兼作蒙影带鼓出日侧的量） */
        const val PLANET_TERMINATOR_SMOOTH = 0.08f
        /** 晨昏线遮罩离散段数（单位圆，烘焙一次，与画布尺寸无关） */
        const val TERMINATOR_STEPS = 20
        /** 大气边缘光：光晕半径 = 行星盘半径 × 此系数（仅 HIGH） */
        const val ATMOSPHERE_GLOW_R = 1.18f
        /** 大气边缘光渐变的峰值 alpha（与 [ATMOSPHERE_GLOW_ALPHA] 相乘后 ≈ 0.12） */
        const val ATMOSPHERE_GLOW_PEAK_A = 0.65f
        /** 大气边缘光最大 alpha（仅 HIGH 档绘制） */
        const val ATMOSPHERE_GLOW_ALPHA = 0.18f
        /** 木星带状云纹条数（HIGH / MEDIUM 档） */
        const val JUPITER_BAND_COUNT = 10
        const val SATURN_BAND_COUNT = 8
        const val JUPITER_BAND_COUNT_MED = 4
        const val SATURN_BAND_COUNT_MED = 3
        /** 云带纬度覆盖范围（起算纬度 / 总跨度，木星更宽、土星更窄） */
        const val JUPITER_BAND_LAT_TOP = -0.45f
        const val JUPITER_BAND_LAT_SPAN = 0.90f
        const val SATURN_BAND_LAT_TOP = -0.40f
        const val SATURN_BAND_LAT_SPAN = 0.80f
        /**
         * 云带厚度 / 间距（< 1 ⇒ 带间留缝）。这是**赤道亮带的**最大**厚度**，
         * 逐带还要乘 [bandWidthFactor]（向两极收窄）⇒ 实际厚度恒 ≤ 本值。
         *
         * ⚠️ 调参会同时抬高门禁 ⑤ 的 `lim = max(|top|,|bot|)`，而 `bandInsideDisc`
         * 要求 `chord² + (lim + amp)² ≤ 1`：木星 HIGH 的最坏带在纬度 ±0.405，
         * 故 `0.405 + 厚/2 + amp` 必须留出余量 ⇒ 本值与 [JUPITER_BAND_AMP] 联合受限。
         */
        const val BAND_THICK_K = 0.56f
        /** 云带横向半宽上限（行星盘半径分数，配合弦长收缩 ⇒ 恒不溢出盘面） */
        const val BAND_WIDTH_K = 0.92f
        /** 云带扰动幅度上限（行星半径分数）：公共扰动 + 边缘扰动之和恒 ≤ 本值（门禁 ⑤ 的判据） */
        const val JUPITER_BAND_AMP = 0.105f
        const val SATURN_BAND_AMP = 0.055f
        /**
         * 逐类云带 alpha。基色不透明 ⇒ 明暗主要来自**色相/明度**（米白 vs 红棕），
         * alpha 只做浓淡微调；暗带略高于亮带 ⇒ 与木星「暗带压得住、亮带透得出」一致。
         */
        const val JUPITER_ZONE_ALPHA = 0.62f
        const val JUPITER_BELT_ALPHA = 0.66f
        const val JUPITER_POLAR_ALPHA = 0.42f
        const val SATURN_ZONE_ALPHA = 0.40f
        const val SATURN_BELT_ALPHA = 0.42f
        const val SATURN_POLAR_ALPHA = 0.26f
        /**
         * 逐带厚度向两极收窄的**上限比例**（0 = 不收窄；0.5 = 极区只剩赤道的 50%）——
         * 真实木星赤道带区（EZ）宽厚、南北温带收窄、极区细碎。
         */
        const val BAND_POLAR_NARROW = 0.50f
        /** 暗带（Belt）相对亮带（Zone）的收窄（真实木星的带条比带区窄） */
        const val BAND_BELT_NARROW = 0.84f
        /** 扰动中「整条带一起蜿蜒（上下同相）」的占比，其余给上下边缘各自的褶皱 */
        const val BAND_SHARE_WHOLE = 0.45f
        /**
         * 逐带的**公共**相位步进（rad）。⛔ 必须很小 —— 相邻两带的公共相位差直接变成
         * 两条带的**相对**纵向位移（≈ `2 × wholeAmp × sin(步进/2)`），大了会让相邻带互相穿插。
         * 取 0.12 ⇒ 相对位移仅 ~0.6% 半径 ⇒ 全部云带像一整层流体同步起伏（真实木星的带区
         * 本就是同一套纬向急流），而**逐带差异**交给 [BAND_EDGE_PHASE_STEP] 那份边缘褶皱。
         */
        const val BAND_PHASE_STEP = 0.12f
        /**
         * 逐带的**边缘褶皱**相位步进（rad）——比公共部分大得多 ⇒ 相邻带的边缘涡卷明显不同相，
         * 这正是「不像扁平行条纹」的关键观感来源。
         */
        const val BAND_EDGE_PHASE_STEP = 1.37f
        /**
         * 边缘褶皱幅度 / **本带厚度** 的上限。⛔ 必须 < 0.5 —— 上下两边缘最大相对位移
         * `2 × 本值 × 厚度`，≥ 厚度时带会自己掐断（Path 自交 ⇒ 填充出怪形）。
         * 取 0.40 ⇒ 留 20% 厚度余量，带永不掐断。
         */
        const val BAND_EDGE_MAX = 0.40f
        /**
         * 边缘褶皱幅度 / **带间距** 的上限——保证相邻带不互相粘连成一整片。
         * 推导：相邻带的缝隙 = `间距 − 两带厚度均值`，两条带边缘最大相向位移
         * `2 × 本系数 × 间距`（再加公共部分那 ~0.6%），必须 < 缝隙。
         */
        const val BAND_EDGE_GAP_K = 0.13f
        /** 边缘褶皱的频率倍率（相对公共部分）——上下边缘不同频 ⇒ 湍流涡卷质感 */
        const val BAND_EDGE_FREQ_K = 1.47f
        const val BAND_STEPS = 96
        /** 云带漂移速率（rad/s，木星快、土星慢） */
        const val JUPITER_BAND_DRIFT = 0.055
        const val SATURN_BAND_DRIFT = 0.033

        // ── ⑦ 木星大红斑（仅 HIGH；纯 Path + 单元空间渐变，零贴图 / 零 Bitmap 烘焙）──
        /** 中心纬度（归一化，−1 = 南极）——南纬约 20°，落在南温带区的带面上 */
        const val GRS_LAT = -0.22f
        /** 长/短半轴（行星盘半径分数）⇒ 宽 ≈ 盘直径 30%、高 ≈ 盘直径 12%（真实 GRS ≈ 2.5:1） */
        const val GRS_RX = 0.30f
        const val GRS_RY = 0.12f
        /**
         * 自转漂移的经度行程半幅（盘半径单位）：一个周期内从 `+GRS_LON_FAR` 单调走到
         * `−GRS_LON_FAR` ⇒ 观感是**持续向西漂移**（真实大红斑就是随木星自转向西漂）。
         */
        const val GRS_LON_FAR = 0.42f
        /**
         * 近盘缘淡出上限（|经度| ≥ 本值 ⇒ alpha = 0 ⇒ 完全隐去）。⛔ 必须 < [GRS_LON_FAR]
         * ⇒ 行程两端都有一段**完全不可见**的区间，回绕（+FAR 跳回 +FAR）因此无跳变。
         */
        const val GRS_LIMB_FADE = 0.36f
        /** 近盘缘的横向压扁量（球面透视：越靠边缘越「侧过去」） */
        const val GRS_SQUEEZE = 0.45f
        /**
         * 大红斑漂移周期（秒）。⛔ 禁 `Random`——纯 `elapsed` 的线性扫掠（可回放、零闪烁）。
         * 取木星公转周期（`buildSystem()` 里 Jupiter 的 `period = 90`）的 **1/4** = 22.5 s，
         * ⇒ 大红斑绕木星盘一周恰好是木星公转的 1/4 周，二者节奏协调。
         */
        const val GRS_ROT_PERIOD = 22.5
        /** 同一条带**上下边缘**之间的相位差（rad）——真实木星带边缘上下的褶皱并不对称 */
        const val BAND_EDGE_SKEW = 2.37f
        /** 柔边晕圈的半径倍率与逐层 alpha（晕圈淡、涡核浓 ⇒ 柔和涡旋而非硬边椭圆） */
        const val GRS_HALO_K = 1.45f
        const val GRS_HALO_ALPHA = 0.55f
        const val GRS_CORE_ALPHA = 0.88f
        /** 涡旋柔边渐变的中/外缘停靠点（与核心同色系、alpha 递减 ⇒ 边缘柔和到几乎透明） */
        const val GRS_MID = 0x80C1502E.toInt()
        const val GRS_EDGE = 0x00C1502E.toInt()
        /** 太阳米粒组织：离散段数 / 噪声振幅 / 漂移速率 / 半径调制系数 */
        const val SUN_GRANULE_STEPS = 96
        const val SUN_GRANULE_CONTRAST = 0.35f
        const val SUN_GRANULE_DRIFT = 0.18
        const val SUN_GRANULE_R_GAIN = 0.20f
        /** 卡西尼缝：内圈（B 环）半径 / 主环（A 环）长半轴，两圈之间的空白即缝 */
        const val SATURN_RING_INNER_K = 0.72f

        // ── 彗星（功能 6：⛔ 禁 Random，全部参数来自窗口序号 hash ⇒ 可回放、零闪烁）──
        /** 彗星窗口周期（秒）：第 k 个窗口 = `[k·W, (k+1)·W)`，`k = floor(elapsed / W)` */
        const val COMET_WINDOW = 45.0
        /** 窗口内的起始延迟（秒）与单次经过的时长区间 ⇒ 相邻间隔约 24~44 s 不定 */
        const val COMET_START_SPAN = 8.0
        const val COMET_DUR_MIN = 9.0
        const val COMET_DUR_SPAN = 4.0
        /** 轨道半长轴（画面半幅分数）/ 离心率区间 */
        const val COMET_A_MIN = 0.82f
        const val COMET_A_SPAN = 0.18f
        const val COMET_E_MIN = 0.60f
        const val COMET_E_SPAN = 0.24f
        /** 彗核半径（世界单位，× scale 得像素）与近日点加成区间 */
        const val COMET_NUCLEUS_R = 0.0032f
        const val COMET_NUCLEUS_SPAN = 0.0022f
        /** 彗尾长度（世界单位）：远日只留底长、近日线性加长到 `MIN + SPAN`（双尾共用此基准长） */
        const val COMET_TAIL_MIN = 0.055f
        const val COMET_TAIL_SPAN = 0.150f

        // ── 双尾：离子尾（细长笔直、蓝白）+ 尘埃尾（宽而淡黄、滞后弯曲）────────────
        /** 离子尾基色：蓝白（CO⁺ 在可见光下的冷蓝），⛔ 不透明 + 逐层 alpha */
        const val COMET_ION = 0xFFBBD6FF.toInt()
        /** 尘埃尾基色：偏暖的淡黄（尘埃散射日光 ⇒ 黄白），⛔ 不透明 + 逐层 alpha */
        const val COMET_DUST = 0xFFE7D2A6.toInt()
        /**
         * **尾根半宽**（⛔ 半宽，不是全宽）——**以尾长为主、彗核半径为下限**：
         * [COMET_ION_ROOT_F] / [COMET_DUST_ROOT_F] 是**尾长的分数**，[COMET_ION_ROOT_MIN] /
         * [COMET_DUST_ROOT_MIN] 是**彗核半径的倍数下限**。
         *
         * ⛔ **必须以尾长为主**：彗核半径的世界单位只有 0.0032~0.0054、而尾长在近日点可达
         * 0.2 世界单位（相差 40 倍）⇒ 若只用「几倍核半径」，近日时尾会宽成一根**短棍**
         * （长度:全宽 ≈ 1.5:1，完全不像尾）。改成「尾长分数 + 核半径下限」后：
         * 近日的**离子尾**长宽比约 **12:1**（细长笔直的针，真实离子尾就是一根细针），
         * **尘埃尾**约 **3.4:1**（宽扇，真实尘埃尾确实是宽而短的扇面），
         * 而远日（尾短）时由核半径下限托住 ⇒ 不会细到看不见。
         */
        const val COMET_ION_ROOT_F = 0.020f
        const val COMET_ION_ROOT_MIN = 0.8f
        const val COMET_DUST_ROOT_F = 0.055f
        const val COMET_DUST_ROOT_MIN = 1.6f
        /** 尾长倍率（相对 `COMET_TAIL_MIN + COMET_TAIL_SPAN·q` 算出的基准长）：离子尾最长、尘埃尾短一截 */
        const val COMET_ION_LEN_K = 1.06f
        const val COMET_DUST_LEN_K = 0.78f
        /** 两条尾的 alpha 上限（再乘逐层倍率与近日程度；⛔ 三层叠加后仍远低于 1，不会糊成白块） */
        const val COMET_ION_ALPHA = 0.30f
        const val COMET_DUST_ALPHA = 0.19f
        /**
         * **双尾夹角**（度）：尘埃尾相对背日方向的滞后角 ∈ `[MIN, MIN + SPAN]`。
         *
         * 真实成因：离子尾被太阳风推 ⇒ 严格沿背日方向且**笔直**；尘埃尾带轨道惯性 ⇒
         * 偏向**行进方向的反侧**（因此是**弯曲**的）。⛔ 本实现里「运动反侧」**不取 hash
         * 的正负号**，而是由 `cometPoint` 的**真实屏幕速度**（相邻偏近点角的前向差分取反）
         * 得到 ⇒ 夹角恒开在正确的一侧；近日时速度更快 ⇒ 尾更滞后（与真实彗星一致），
         * 而离子尾的笔直由几何直接保证（弯曲量恒为 0）。
         */
        const val COMET_SPREAD_MIN = 5.0f
        const val COMET_SPREAD_SPAN = 13.0f
        /** 尘埃尾**末端弯曲量 / 尾长**（抛物线剖面 `bend·t²`：根部曲率最大、向尾尖渐直） */
        const val COMET_TAIL_BEND = 0.16f
        /** 尘埃尾弯曲剖面的取样步进（偏近点角差分，屏幕速度；⛔ 不改变任何轨道/节律语义） */
        const val COMET_VEL_DT = 0.01f

        // ── 尾形：柔边锥形（尾轴多点采样 + 逐层递减 alpha 叠加）──────────────────
        /** 锥度指数 p：尾半宽 ∝ `(1 − t)^p`（t = 尾轴归一化位置，0 = 尾根、1 = 尾尖） */
        const val COMET_TAIL_TAPER = 1.15f
        /**
         * 逐层**锥度递减**步长（外层指数更小 ⇒ 收窄更晚 ⇒ 尾「甩」得更开、更像飘散出去的
         * 尘埃流，而不是三根等宽的锥形叠在一起）。
         */
        const val COMET_TAIL_TAPER_STEP = 0.18f
        /**
         * 尾根**肩部鼓出**（宽度再乘 `1 + FLARE·sin(πt)`）：最宽处落在彗发下游一点，
         * ⛔ 而不是从彗核硬邦邦长出一个等宽的根（旧版三段折线的「根部突兀」正源于此）。
         */
        const val COMET_TAIL_FLARE = 0.45f
        /** 柔边**分层数**（每层一条同形状锥形：外层最淡最大 → 内层最浓最小 ⇒ 层间过渡即柔边） */
        const val COMET_TAIL_LAYERS = 3
        /** 逐层横向宽度倍率 = `1 + 层号·W_STEP` ⇒ 1.00 / 1.62 / 2.24（越外越宽） */
        const val COMET_TAIL_LAYER_W_STEP = 0.62f
        /** 逐层 alpha 倍率 = `1 / (1 + 层号·A_LIN + 层号²·A_QUAD)` ⇒ 1.00 / 0.44 / 0.24（越外越淡） */
        const val COMET_TAIL_LAYER_A_LIN = 0.90f
        const val COMET_TAIL_LAYER_A_QUAD = 0.35f
        /** 每条尾**每侧边**沿尾轴的采样段数（14 ⇒ 单层 30 个点；⛔ 逐帧现算，不缓存任何点集） */
        const val COMET_TAIL_STEPS = 14

        /** 轨道弧离散段数 / 进出场淡入淡出占窗口进度的比例 */
        const val COMET_ORBIT_STEPS = 64
        const val COMET_ORBIT_STEPS_MED = 32
        const val COMET_FADE = 0.12f
        /** 轨道弧的 alpha 上限 */
        const val COMET_ORBIT_ALPHA = 0.16f
        /**
         * **彗发（coma）**——彗核外的弥散光晕，柔边由**缓存的单元空间径向渐变**经 canvas
         * 缩放摆出（与大气边缘光 / 大红斑同一套手法 ⇒ 零逐帧分配）。
         *  - [COMET_COMA_K]：晕半径 / 彗核半径（真实彗发比核大好几倍 ⇒ 尾从光晕里长出来）；
         *  - [COMET_COMA_MIN_W]：晕半径 ≥ **最外层**离子尾根半宽 × 本值（⛔ 尾根恒落在晕内
         *    ⇒ 无硬接缝；⛔ 本项**不乘近日系数**）；
         *  - [COMET_COMA_ALPHA]：逐帧 alpha 上限（近日时彗发最亮）；
         *  - [COMET_COMA_CORE_A] / [COMET_COMA_MID_A] / [COMET_COMA_EDGE_A]：渐变三段停靠
         *    的 alpha（致密核心 → 弥散中段 → 近乎透明的外缘 ⇒ 边缘化开）。
         */
        const val COMET_COMA_K = 3.1f
        const val COMET_COMA_MIN_W = 1.15f
        const val COMET_COMA_ALPHA = 0.85f
        const val COMET_COMA_CORE_A = 0.62f
        const val COMET_COMA_MID_A = 0.34f
        const val COMET_COMA_EDGE_A = 0.10f
        /**
         * HIGH 档的内层致密晕（小半径、低 alpha，压在柔边彗发之上）。
         * ⛔ 半径刻意压到 [COMET_COMA_K] 之内、alpha 压到 0.13 —— 它是**致密核心**而不是
         * 第二个光晕，否则会在柔边彗发上留下一圈可见的硬边圆盘。
         */
        const val COMET_HEAD_GLOW_K = 1.6f
        const val COMET_HEAD_GLOW_ALPHA = 0.13f
        /** hash 盐（同一序号在不同盐位上取互不相关的参数） */
        const val COMET_SALT_START = 901
        const val COMET_SALT_DUR = 907
        const val COMET_SALT_A = 911
        const val COMET_SALT_E = 919
        const val COMET_SALT_ROT = 929
        const val COMET_SALT_DIR = 937
        const val COMET_SALT_R = 941
        const val COMET_SALT_BEND = 947
        /** 双尾夹角专用盐（⛔ 与 [COMET_SALT_BEND] 分开取 ⇒ 弯向与张角互不相关） */
        const val COMET_SALT_SPLIT = 953

        /**
         * 确定性 hash → `[0,1)`（同 E43 海边 `SeasideWaves.hash32` 的思路，本类自带一份，
         * ⛔ 不 import fx / 不引 `Random`）。彗星的出现时刻、轨道形状、方向、大小
         * 全部由「窗口序号 + 盐」推出 ⇒ 同一 `elapsed` 恒得同一颗彗星（可回放、零闪烁）。
         */
        fun hashUnit(i: Int, salt: Int): Float {
            var x = ((i + 1) * -1640531535) xor ((salt + 7) * 40503)
            x = (x xor 61) xor (x ushr 16)
            x = x + (x shl 3)
            x = x * 0x27d4eb2d
            x = x xor (x ushr 15)
            return (x.toLong() and 0xFFFFFFFFL) * 2.3283064365386963E-10f
        }

        /** ⑥ 第 k 个彗星窗口的起始延迟（秒）—— 纯函数，门禁与生产共用同一份实现 */
        internal fun cometStartAt(k: Int): Float =
            hashUnit(k, COMET_SALT_START) * COMET_START_SPAN.toFloat()

        /** ⑥ 第 k 颗彗星的经过时长（秒） */
        internal fun cometDuration(k: Int): Float =
            (COMET_DUR_MIN + hashUnit(k, COMET_SALT_DUR).toDouble() * COMET_DUR_SPAN).toFloat()

        /**
         * ⑥ 一阶开普勒方程：平近点角 `M` → 偏近点角 `E = M + e·sin M`
         * （近日快、远日慢 ⇒ 不是匀速椭圆）。
         */
        internal fun cometAnomaly(m: Float, e: Float): Float = m + e * sin(m)

        /** ⑥ 轨道半径 `r = a(1 − e·cos E)` */
        internal fun cometRadius(a: Float, e: Float, eccentric: Float): Float =
            a * (1f - e * cos(eccentric))

        /**
         * ⑥ 彗尾**锥度剖面**：尾轴归一化位置 `t`（0 = 尾根、1 = 尾尖）处的**半宽系数**。
         *
         * - `(1 − t)^[power]` ⇒ 宽度**从尾根到尾尖连续收窄**（⛔ 旧版是「根 / 中 / 尖」三个
         *   点连成的折线 ⇒ 中段突然折一下、根部与中段宽度对不上，视觉上就是一块贴上去的纸片）；
         * - `× (1 + [COMET_TAIL_FLARE]·sin(πt))` ⇒ 最宽处**落在彗发下游一点**（真实彗尾从
         *   彗发**展开**，不是在彗核处等宽地「长」出来）。
         *
         * 恒有 `f(0) = 1`、`f(1) = 0`（⛔ 尾尖必收成一个点，多层叠加时三层同点收尖 ⇒
         * 尾尖没有硬切边）。
         */
        internal fun cometTailProfile(t: Float, power: Float): Float =
            (1f - t).coerceAtLeast(0f).pow(power) * (1f + COMET_TAIL_FLARE * sin(PI_F * t))

        /** ⑥ 第 [layer] 层柔边锥形的**横向宽度倍率**（层号 0 = 最内最浓，越外越宽） */
        internal fun cometLayerWidthK(layer: Int): Float = 1f + layer * COMET_TAIL_LAYER_W_STEP

        /**
         * ⑥ 第 [layer] 层柔边锥形的 **alpha 倍率**：线性项 + 平方项 ⇒ 0/1/2 层分别
         * 1.00 / 0.44 / 0.24（越外越淡，⛔ 相邻层差值不大 ⇒ 层与层之间看起来是渐变而非色阶）。
         */
        internal fun cometLayerAlphaK(layer: Int): Float =
            1f / (1f + layer * COMET_TAIL_LAYER_A_LIN + layer * layer * COMET_TAIL_LAYER_A_QUAD)

        /**
         * ① 晨昏线夜侧方位角（度）：入参是**光源方向** `(ldx, ldy) = 画面中心 − 行星位置`，
         * 夜侧取其反向 ⇒ 返回 `atan2(−ldy, −ldx)` 的角度。canvas 旋转该角度后，
         * 单位圆遮罩的 +x（夜方向）正好指向背日一侧（屏幕坐标 y 向下，`rotate` 顺时钟为正）。
         */
        internal fun nightSideDegrees(ldx: Float, ldy: Float): Float =
            Math.toDegrees(atan2(-ldy.toDouble(), -ldx.toDouble())).toFloat()

        /**
         * ③ 云带横向半宽（**行星盘半径分数**）：弦长由上下边界里更靠极的那条决定，
         * 再乘 [BAND_WIDTH_K] 留边 ⇒ 整条带恒在圆盘内（不需要 clip）。
         */
        internal fun bandChordFraction(top: Float, bot: Float): Float {
            val lim = max(abs(top), abs(bot)).coerceAtMost(1f)
            return sqrt(1f - lim * lim) * BAND_WIDTH_K
        }

        /** ③ 第 [idx] 条云带的中心纬度（归一化：−1 = 南极、+1 = 北极） */
        internal fun bandLatCenter(count: Int, idx: Int, latTop: Float, latSpan: Float): Float =
            latTop + latSpan * (idx + 0.5f) / count

        /**
         * ③ 云带厚度（归一化纬度；`< latSpan / count` ⇒ 带间留缝）。
         *
         * ⛔ **这是「最宽那条带」的厚度上限**，逐带还要乘 [bandWidthFactor] 收窄
         * ⇒ 实际厚度恒 ≤ 本值 ⇒ 门禁 ⑤ 用本值做「含扰动上限仍在盘内」的判据依然成立。
         */
        internal fun bandThickness(count: Int, latSpan: Float): Float =
            latSpan / count * BAND_THICK_K

        /**
         * ③ 距赤道的**带序**（0 = 最靠近赤道那条，1 = 再外一条…），左右严格对称。
         *
         * ⛔ 用 `floor(|idx − 中心|)` 而不是 `min(idx, count−1−idx)` —— 后者在偶数条时
         * 会把「夹住赤道的那一对」判成带序 1（暗带），而真实木星**赤道带区 EZ 恰恰是最亮的一条**。
         * 本式对奇/偶条数都给出「赤道 = 带序 0」，且纯整数运算、无浮点余数误差。
         */
        internal fun bandRing(count: Int, idx: Int): Int =
            floor(abs(idx - (count - 1) * 0.5f)).toInt()

        /** ③ 最外一圈云带的带序（[bandRing] 的取值上界）——用于判定「极区」 */
        internal fun bandMaxRing(count: Int): Int = bandRing(count, 0)

        /**
         * ③ 云带**色调**（0 = 亮带 Zone / 1 = 暗带 Belt / 2 = 极区 muted）——
         * 明暗**交替**（真实木星就是米白带区与红棕带条相间，不是「所有带同色」）。
         *
         * - 带序 0 = **赤道带区 EZ**（最宽最亮，夹住赤道的那一对）；
         * - 奇数带序 = 带条 NEB/SEB/NTB/STB（红棕暗带）；
         * - 偶数带序（≥2）= 温带带区 NTZ/STZ（米白亮带）；
         * - 最外一圈（[maxRing]）= 极区（灰暗，真实木星是两极的暗色极区罩）。
         *
         * ⛔ `maxRing >= 2` 才启用极区档 —— MEDIUM 档（木 4 / 土 3 条）最外圈就是带序 1，
         * 若也判成极区就会丢掉唯一的暗带、变成「暗-亮-亮-暗」以外的排布 ⇒ 只按奇偶交替。
         *
         * 纬度对称 ⇒ 木星观感左右一致；土星同一套规则（只是明度差更小）。
         */
        internal fun bandTone(ring: Int, maxRing: Int): Int =
            if (maxRing >= 2 && ring == maxRing) 2 else if (ring and 1 == 1) 1 else 0

        /**
         * ③ 逐带**宽度收窄系数**（乘在 [bandThickness] 上）：赤道最宽（1.0），
         * 向两极线性收窄到 `1 − [BAND_POLAR_NARROW]`；[dark] 再乘 [BAND_BELT_NARROW]。
         *
         * [lat] 是带中心纬度（归一化），[latTop] + [latSpan] / 2 是覆盖区半跨度。
         * ⛔ 返回值恒 ∈ (0, 1] ⇒ 实际厚度恒 < [bandThickness] ⇒ 带间必然留缝、
         * 且**不会**比门禁 ⑤ 判据里那条最宽的带更容易溢出盘面。
         */
        internal fun bandWidthFactor(lat: Float, latTop: Float, latSpan: Float, dark: Boolean): Float {
            val halfSpan = abs(latTop) + latSpan * 0.5f          // 覆盖区到极点的距离（恒 > 0）
            val t = if (halfSpan > 0f) (abs(lat) / halfSpan).coerceIn(0f, 1f) else 0f
            val polar = 1f - BAND_POLAR_NARROW * t
            return if (dark) polar * BAND_BELT_NARROW else polar
        }
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

    /*
     * ⚠️ 这里**故意不**提供「场景最小实体半径」的成员/常量：门禁（§八 G14 断言 ①）需要它，
     * 而单测**不能构造本类**（字段初始化会建 `Path()` → `android.graphics.Path`，JVM 单测抛
     * 「not mocked」）。⇒ 门禁改为**扫 [buildSystem] 源码**解析出全部实体半径再取 min，
     * 行星表一改判据跟着变，**同样不漂移**。
     *
     * ⚠️ 用普通块注释（不是 KDoc）—— 否则它会被当成**下一个属性**的文档。
     */

    private val bgColor = Color(BG)
    private val orbitLineColor = Color(ORBIT_LINE)
    private val moonOrbitLineColor = Color(MOON_ORBIT_LINE)
    private val sunCore = Color(SUN_CORE)
    private val sunCoreHot = Color(SUN_CORE_HOT)
    private val sunHalo = Color(SUN_HALO)
    private val ringBack = Color(RING_BACK)
    private val ringFront = Color(RING_FRONT)
    private val earthLand = Color(EARTH_LAND)
    private val jupiterZone = Color(JUPITER_ZONE)
    private val jupiterBelt = Color(JUPITER_BELT)
    private val jupiterPolar = Color(JUPITER_POLAR)
    private val saturnZone = Color(SATURN_ZONE)
    private val saturnBelt = Color(SATURN_BELT)
    private val saturnPolar = Color(SATURN_POLAR)
    private val cometTailColor = Color(COMET_TAIL)
    private val cometIonColor = Color(COMET_ION)
    private val cometDustColor = Color(COMET_DUST)
    private val cometNucleusColor = Color(COMET_NUCLEUS)
    private val specular = Color(SPECULAR)
    private val starWhite = Color.White
    private val starBlue = Color(STAR_BLUE)

    /**
     * 星野（[onEnter] 固定种子生成一次 → 零闪烁；坐标为 w/h 归一化 0..1）。
     * 半径为**生成期定值**（立方幂律，见 [fillStars]）、alpha 同为生成期定值，
     * `draw` 内只读。
     */
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
    /** 彗星轨道弧（比行星轨道更淡更细，同按 scale 缓存） */
    private var cometOrbitStroke: Stroke = Stroke(1f)

    /**
     * 中心静默区的**椭圆归一化分母的倒数**（[ensureLayout] 只在尺寸 / scale 变化时算一次）：
     * `quietInvX = 1 / (s × QUIET_R)`、`quietInvY = 1 / (s × QUIET_R × TILT)`。
     * ⇒ [drawStars] 每星只做 `(px − cx) × quietInvX` 的乘法，**无 `sqrt` / 无除法 / 无分配**。
     */
    private var quietInvX = 0f
    private var quietInvY = 0f

    /** 土星环半弧复用缓冲（成员 Path，reset 后逐帧重画，零分配） */
    private val ringBuf = Path()

    /**
     * 晨昏线夜侧遮罩（**单位圆**空间，夜方向 = +x；烘焙一次，与画布尺寸无关）。
     * 逐帧用原生 canvas 的 `save → translate → rotate → scale → drawPath → restore`
     * 摆到行星位置 ⇒ 角度连续（不跳档）、零分配。
     */
    private val terminatorPath = Path()
    private var terminatorBaked = false
    /** 带状云纹 / 太阳米粒复用 Path（逐条 rewind，零分配） */
    private val bandBuf = Path()
    /** 彗星轨道弧 / 彗尾锥形复用 Path（零分配；每层一次 rewind，同一条缓冲轮流装三条尾的 3 层） */
    private val cometOrbitBuf = Path()
    private val cometTailBuf = Path()
    /**
     * 彗发（coma）柔边渐变（**单位圆空间**，与画布尺寸无关 ⇒ 只建一次）：
     * 不透明核心 → 弥散中段 → 近乎透明的外缘。逐帧经 canvas 缩放摆到彗核位置
     * ⇒ 柔边光晕（⛔ 硬边圆 = 「贴上去的圆盘」）。
     */
    private var cometComaBrush: Brush? = null
    /** 大气边缘光渐变（单元空间径向渐变，仅 HIGH 档建；尺寸无关 ⇒ 只建一次） */
    private var atmoBrush: Brush? = null
    /**
     * 大红斑柔边渐变（**单元椭圆空间**的径向渐变，仅 HIGH 档建一次）：
     * 不透明砖红（0）→ 半透明橙红（中）→ 完全透明（1）。
     * 逐帧用 canvas 缩放到目标椭圆 ⇒ **边缘柔和**（不是硬边椭圆），
     * 且 Brush 只在 [ensureLayout] 建、逐帧只读 ⇒ 零分配。
     */
    private var redSpotBrush: Brush? = null

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
        atmoBrush = null
        redSpotBrush = null
        cometComaBrush = null
        brushW = -1f
        brushH = -1f
        brushScale = -1f
        strokeScale = -1f
        // 0 ⇒ 尺寸未定前不构成有效归一化（drawStars 恒在 ensureLayout 之后调用）
        quietInvX = 0f
        quietInvY = 0f
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
        //    半径 = 立方幂律（绝大多数是亚像素暗星）、alpha 与半径 u² 同源（见 [fillStars]）
        fillStars(starX, starY, starR, starA)
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
        //    三路信号均低通平滑（E29 旧版直接用原始值；邻居 RadarGrid 同款 bassSmooth 写法）
        //    M10 修复（2026-10-06）：EMA 系数乘 dt 参数化——旧实现固定系数等价于隐含
        //    「60fps 基准」，帧率不稳时鼓点跟随忽快忽慢。改为 1-(1-k)^(dt·60)（与原系数
        //    在 60fps 下逐帧等价），dtSec 上文已算好（clamp 0.1s）。
        val bassK = 1f - Math.pow((1f - BASS_FOLLOW).toDouble(), (dtSec * 60f).toDouble()).toFloat()
        val pulseK = 1f - Math.pow((1f - PULSE_FOLLOW).toDouble(), (dtSec * 60f).toDouble()).toFloat()
        val energyK = 1f - Math.pow((1f - ENERGY_FOLLOW).toDouble(), (dtSec * 60f).toDouble()).toFloat()
        bassSmooth += (frame.bass - bassSmooth) * bassK
        pulseSmooth += (frame.pulse - pulseSmooth) * pulseK
        energySmooth += (frame.energy - energySmooth) * energyK
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
        // ④ 太阳米粒组织（仅 HIGH）：96 段噪声圆替代纯色圆盘，亮核 drawCircle 保留
        //    ⇒ 提交数与 LOW/MEDIUM 的两层完全一致（1 路径 + 1 圆），音频律动不受影响
        if (tier == 2) {
            bandBuf.rewind()
            val drift = (elapsed * SUN_GRANULE_DRIFT).toFloat()
            val tauF = TAU_D.toFloat()
            var g = 0
            while (g <= SUN_GRANULE_STEPS) {
                val a = tauF * g / SUN_GRANULE_STEPS
                // 四层正弦近似 fbm（权重和 = 1 ⇒ n ∈ [-1,1]）；31 频在 96 段下仍每周期 ≥3 采样
                val n = (sin(a * 6f + drift) * 0.45f + sin(a * 11f - drift * 0.7f) * 0.28f +
                    sin(a * 19f + drift * 1.6f) * 0.17f + sin(a * 31f - drift * 0.4f) * 0.10f)
                val r = sunR * (1f + n * SUN_GRANULE_CONTRAST * SUN_GRANULE_R_GAIN)
                val x = center.x + cos(a) * r
                val y = center.y + sin(a) * r
                if (g == 0) bandBuf.moveTo(x, y) else bandBuf.lineTo(x, y)
                g++
            }
            bandBuf.close()
            drawPath(bandBuf, color = sunCore)
            drawCircle(sunCoreHot, radius = sunR * 0.62f, center = center, alpha = 0.95f)
        } else {
            drawCircle(sunCore, radius = sunR, center = center)
            drawCircle(sunCoreHot, radius = sunR * 0.62f, center = center, alpha = 0.95f)
        }

        // ── 近侧行星（屏幕 y ≥ 中线）：画在太阳之后 → 可遮住太阳（用户要求的前后遮挡）──
        while (i < planets.size) {
            drawPlanetAt(order[i], center, scale, extentYScreen)
            i++
        }

        // ── ⑥ 彗星（最前景）：不定间隔掠过，画轨道弧 + 背日彗尾，出画后轨道随之消失 ──
        drawComet(w, h, center, scale)
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
     * 绘制单颗行星系统（**远侧元素 → 行星盘 → 近侧元素**）：
     * 卫星轨道环 → 土星远弧 → **远侧卫星本体** → 行星盘 → 细节 → 高光 →
     * 土星近弧 → 近侧卫星本体。
     *
     * **卫星本体按轨道远/近侧分两段绘制**（2026-10-05 修「卫星转到行星背后却盖在上面」）：
     * 卫星屏幕 y 与行星盘屏幕 y 之差 = `sin(ma) × orbit × scale × TILT`（两者世界 x 只差
     * `cos(ma) × orbit`，而 [project] 只把 y 乘 [TILT]）⇒ **`sin(ma) < 0` 即屏幕上方**，
     * 与行星级深度分层同一约定（[draw] 里屏幕 y < 中线 = 远侧、画在太阳之前）⇒
     * `sin(ma) < 0` 的卫星在轨道远侧，**必须画在行星盘之前**。
     *
     * ⛔ **不再加「卫星是否落在行星轮廓内」的判据**：行星盘是不透明遮挡体，先画远侧卫星
     * 后画盘 ⇒ 落在盘内的部分自然被吃掉、露在盘外的部分自然露出，正是正确的遮挡
     * （部分可见本身就是深度线索）。若改成「只在完全落入盘内才提前画」，卫星会在跨越
     * 盘边缘的瞬间整颗跳变/闪烁。
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

        // 远侧卫星本体（sin(ma) < 0 ⇒ 屏幕 y 在盘之上 ⇒ 轨道远侧）：画在行星盘之前，
        // 盘内的部分被行星遮住、盘外的部分露出（遮挡正确，见本函数 KDoc 判定式）
        mi = 0
        while (mi < p.moons.size) {
            val mo = p.moons[mi]
            if (tier > 0 || !mo.optional) {
                val ma = angleAt(elapsed, mo.period, mo.phase)
                val sma = sin(ma)
                if (sma < 0f) {
                    drawCircle(
                        mo.color,
                        radius = mo.radius * scale * depth,
                        center = project(wx + cos(ma) * mo.orbit, wy + sma * mo.orbit, center, scale)
                    )
                }
            }
            mi++
        }

        drawCircle(p.color, radius = pr, center = pos)

        // 行星细节（LOW 跳过）
        if (tier > 0) {
            when (idx) {
                EARTH -> drawCircle(
                    earthLand, radius = pr * 0.40f,
                    center = Offset(px + 0.30f * pr, py - 0.18f * pr)
                )
                JUPITER -> // ③ 木星云带：HIGH 10 条 / MEDIUM 4 条；米白带区 + 红棕带条交替、宽度向两极收窄
                    drawCloudBands(
                        px, py, pr,
                        if (tier == 2) JUPITER_BAND_COUNT else JUPITER_BAND_COUNT_MED,
                        JUPITER_BAND_LAT_TOP, JUPITER_BAND_LAT_SPAN, JUPITER_BAND_AMP,
                        (elapsed * JUPITER_BAND_DRIFT).toFloat(),
                        jupiterZone, jupiterBelt, jupiterPolar,
                        JUPITER_ZONE_ALPHA, JUPITER_BELT_ALPHA, JUPITER_POLAR_ALPHA
                    )
                SATURN -> // ③ 土星云带：HIGH 8 条 / MEDIUM 3 条，色差更小、更窄更淡、漂移更慢
                    drawCloudBands(
                        px, py, pr,
                        if (tier == 2) SATURN_BAND_COUNT else SATURN_BAND_COUNT_MED,
                        SATURN_BAND_LAT_TOP, SATURN_BAND_LAT_SPAN, SATURN_BAND_AMP,
                        (elapsed * SATURN_BAND_DRIFT).toFloat(),
                        saturnZone, saturnBelt, saturnPolar,
                        SATURN_ZONE_ALPHA, SATURN_BELT_ALPHA, SATURN_POLAR_ALPHA
                    )
            }
        }
        // ⑦ 大红斑（仅 HIGH）：⛔ 必须画在「盘 + 云带」之后、**晨昏线之前** ⇒ 夜侧被正确压暗。
        //    MEDIUM 只有 4 条带、已经够忙 ⇒ 大红斑只在 HIGH 画（见 [drawGreatRedSpot]）。
        if (tier == 2 && idx == JUPITER) drawGreatRedSpot(px, py, pr)
        // HIGH：统一斜上高光（偏移 + 半径 ≤ 0.71r → 恒在盘内）
        if (tier == 2) {
            drawCircle(
                specular, radius = pr * 0.20f,
                center = Offset(px - 0.36f * pr, py - 0.36f * pr)
            )
        }

        // ① 晨昏线（MEDIUM + HIGH）：夜侧（背向太阳的一侧）叠一层压暗的同色
        //    光源在画面中心 ⇒ 光线方向 = normalize(画面中心 − 行星位置)，atan2 取方位角；
        //    夜侧 = 该方向的反向。遮罩是**单位圆**空间预烘 Path（[terminatorPath]，
        //    含 PLANET_TERMINATOR_SMOOTH 的蒙影鼓出量），逐帧用原生 canvas 的
        //    save → translate → rotate → scale → drawPath → restore 摆放：
        //    无捕获 lambda、无对象分配，且角度**连续**（预烘 N 档方位角会让分界线每档突跳一次）。
        //    ⛔ 夜侧颜色必须压暗：与行星盘同色叠加 = 像素不变，等于没画。
        //    ⛔ 必须画在「盘 + 地表 / 云带 / 高光」**之后**，否则夜侧的细节仍是全亮 ⇒ 假立体感。
        if (tier > 0) {
            val ldx = center.x - px
            val ldy = center.y - py
            if (ldx != 0f || ldy != 0f) {
                bakeTerminator()
                val pc = p.color
                val nightColor = Color(
                    pc.red * PLANET_NIGHT_SHADE,
                    pc.green * PLANET_NIGHT_SHADE,
                    pc.blue * PLANET_NIGHT_SHADE,
                    PLANET_NIGHT_ALPHA
                )
                val cvs = drawContext.canvas
                cvs.save()
                cvs.translate(px, py)
                cvs.rotate(nightSideDegrees(ldx, ldy))
                cvs.scale(pr, pr)
                drawPath(terminatorPath, color = nightColor)
                cvs.restore()
            }
        }

        // ② 大气边缘光（仅 HIGH）：单元空间径向渐变（透明→白→透明）经 canvas 缩放摆到盘缘
        //    Brush 构造期建好（[atmoBrush]，与画布尺寸无关）⇒ 逐帧只多一次变换进出，零分配
        //    峰值落在 1.0 pr 处 ⇒ 盘缘外一圈微光晕（光晕半径 = pr × [ATMOSPHERE_GLOW_R]）
        if (tier == 2) {
            val ab = atmoBrush
            if (ab != null) {
                val cvs = drawContext.canvas
                val gr = pr * ATMOSPHERE_GLOW_R
                cvs.save()
                cvs.translate(px, py)
                cvs.scale(gr, gr)
                drawCircle(brush = ab, radius = 1f, center = Offset.Zero, alpha = ATMOSPHERE_GLOW_ALPHA)
                cvs.restore()
            }
        }

        if (idx == SATURN) drawSaturnRing(px, py, pr, ringStroke, back = false)

        // 近侧卫星本体（sin(ma) ≥ 0 ⇒ 轨道近侧）：画在行星盘之后，可盖住盘缘；
        // 世界坐标整体过 project（含 TILT），半径同享 depth 系数
        mi = 0
        while (mi < p.moons.size) {
            val mo = p.moons[mi]
            if (tier > 0 || !mo.optional) {
                val ma = angleAt(elapsed, mo.period, mo.phase)
                val sma = sin(ma)
                if (sma >= 0f) {
                    drawCircle(
                        mo.color,
                        radius = mo.radius * scale * depth,
                        center = project(wx + cos(ma) * mo.orbit, wy + sma * mo.orbit, center, scale)
                    )
                }
            }
            mi++
        }
    }

    /**
     * ③ 程序化带状云纹：每条带 = 上下两条折线围成的透镜带，写入成员 [bandBuf]
     * 后一次 `drawPath` ⇒ **每条带 1 次提交、零分配**（替代原来的 2 次 `drawOval` 横纹）。
     *
     *  - **明暗两类带交替**（[bandTone]）：亮带 = 米白带区（Zone）、暗带 = 红棕带条（Belt），
     *    最外一圈是灰暗的极区。基色各自不透明、alpha 只做浓淡微调 ⇒ 观感来自**色相/明度**，
     *    而不是「同色带 + alpha 交替」那种扁平行条纹（旧实现的根因）。
     *  - **宽度不等**（[bandWidthFactor]）：赤道带区最宽，向两极收窄（暗带再窄一档）
     *    ⇒ 与真实木星「赤道带区 EZ 宽厚、温带收窄、极区细碎」一致。
     *  - 横向半宽按行星圆盘的**弦长**收缩（`sqrt(1 − lat²)`）再乘 [BAND_WIDTH_K]
     *    ⇒ 高纬度带自动变短、整条带恒不溢出盘面（不需要 clip）。
     *  - **公共扰动 + 边缘褶皱两段**：公共段上下边缘**同相**（整条带一起蜿蜒、带厚恒定），
     *    边缘段上下**异相异频**（[bandWave] / [bandEdgeWave]，下边缘相位再偏 [BAND_EDGE_SKEW]）
     *    ⇒ 带边缘有木星那种卷曲的涡卷/褶皱，而不是整体平移的「慢起伏」。
     *    逐带的**公共**相位几乎相同（[BAND_PHASE_STEP] 很小）⇒ 全部云带像同一层流体同步起伏；
     *    逐带的**边缘**相位强烈错开（[BAND_EDGE_PHASE_STEP]）⇒ 每条带的涡卷各不相同。
     *    两段幅度之和恒 = [amp] ⇒ 门禁 ⑤ 的「含扰动上限仍在盘内」依然成立；
     *    边缘段幅度另受 `BAND_EDGE_MAX × 本带厚度` 与 `BAND_EDGE_GAP_K × 带间距` 双重封顶
     *    ⇒ 既不会自交掐断，也不会与相邻带粘连。
     *  - 相位由 `elapsed` 驱动 ⇒ 条纹沿纬向缓慢流动。
     *
     * ⛔ 噪声链一律 `Float`（`kotlin.math` 的 Float 重载）：混进一个字面量 `0.5` 就整条
     * 升成 Double，`Path.moveTo` 直接编译不过。
     */
    private fun DrawScope.drawCloudBands(
        px: Float, py: Float, pr: Float, count: Int,
        latTop: Float, latSpan: Float, amp: Float, drift: Float,
        zoneColor: Color, beltColor: Color, polarColor: Color,
        zoneAlpha: Float, beltAlpha: Float, polarAlpha: Float
    ) {
        val maxThick = bandThickness(count, latSpan)
        val spacing = latSpan / count
        val maxRing = bandMaxRing(count)
        var b = 0
        while (b < count) {
            val lat = bandLatCenter(count, b, latTop, latSpan)
            val tone = bandTone(bandRing(count, b), maxRing)
            val dark = tone == 1
            val polar = tone == 2
            // 宽度不等：赤道最宽，向两极收窄；暗带再窄一档
            val thick = maxThick * bandWidthFactor(lat, latTop, latSpan, dark)
            val top = lat - thick * 0.5f
            val bot = lat + thick * 0.5f
            // 弦宽由上下边界里更靠极的那条决定 ⇒ 整带在盘内
            val chord = bandChordFraction(top, bot) * pr
            // 公共段 / 边缘段的幅度拆分（**逐条**按本带厚度与带间距封顶，见 KDoc）：
            // 边缘段 ≤ 0.40 × 本带厚度 ⇒ 上下两边缘永不掐断；
            // 边缘段 ≤ 0.13 × 带间距 ⇒ 相邻带永不粘连。两段之和恒 = [amp]。
            val edgeAmp = min(amp * (1f - BAND_SHARE_WHOLE), min(thick * BAND_EDGE_MAX, spacing * BAND_EDGE_GAP_K))
            val wholeAmp = amp - edgeAmp
            // 逐带相位：公共部分几乎同相（整层一起起伏），边缘褶皱强烈错开（涡卷各不相同）
            val phW = b * BAND_PHASE_STEP
            val phE = b * BAND_EDGE_PHASE_STEP
            val color = if (dark) beltColor else if (polar) polarColor else zoneColor
            val alpha = if (dark) beltAlpha else if (polar) polarAlpha else zoneAlpha
            bandBuf.rewind()
            var k = 0
            while (k <= BAND_STEPS) {
                val u = -1f + 2f * k / BAND_STEPS
                val x = px + u * chord
                val y = py + (top + wholeAmp * bandWave(u, drift, phW) +
                    edgeAmp * bandEdgeWave(u, drift, phE)) * pr
                if (k == 0) bandBuf.moveTo(x, y) else bandBuf.lineTo(x, y)
                k++
            }
            var j = BAND_STEPS
            while (j >= 0) {
                val u = -1f + 2f * j / BAND_STEPS
                // 下边缘：公共段同相（带厚恒定）+ 边缘段异相（[BAND_EDGE_SKEW] 相位差）
                val y = py + (bot + wholeAmp * bandWave(u, drift, phW) +
                    edgeAmp * bandEdgeWave(u, drift + BAND_EDGE_SKEW, phE)) * pr
                bandBuf.lineTo(px + u * chord, y)
                j--
            }
            bandBuf.close()
            drawPath(bandBuf, color = color, alpha = alpha)
            b++
        }
    }

    /**
     * ⑦ 木星**大红斑**（Great Red Spot，仅 HIGH）：木星最标志性的特征。
     *
     * 形态：**横向椭圆涡旋**，长轴沿纬向、宽 ≈ 盘直径 30% / 高 ≈ 盘直径 12%（真实 GRS ≈ 2.5:1），
     * 画在南纬 ~20°（[GRS_LAT]，落在南温带区的带面上）、随自转在盘面上**偏西**漂移
     * （起始经度 `+[GRS_LON_FAR]` 即盘东侧，逐帧向西扫到 `−[GRS_LON_FAR]`）。
     *
     * **柔边而非硬边**：只用**一个**缓存好的**单元空间径向渐变** [redSpotBrush]
     * （`ensureLayout` 里建一次），逐帧经原生 canvas `save → translate → 非等比 scale →
     * drawCircle → restore` 摆成椭圆 ⇒ 渐变被拉成椭圆、边缘化开到近乎透明；
     * 里面再叠一次**更小、更靠内、alpha 更高**的同一渐变当**涡核** ⇒ 共 **2 次提交**。
     * ⛔ 不画硬边 `drawOval`：那是「贴上去的椭圆贴图」感，不是涡旋。
     *
     * 漂移（⛔ 禁 `Random`，纯 `elapsed` 的线性扫掠 ⇒ 可回放、零闪烁）：
     *  - **经度**在一个周期 [GRS_ROT_PERIOD] 内从 `+GRS_LON_FAR` **单调**走到
     *    `−GRS_LON_FAR`（周期取木星公转的 1/4 ⇒ 与行星节奏协调）⇒ 观感是持续的**西漂**，
     *    而不是「来回摆」（大红斑真实行为就是随木星自转向西漂）；
     *  - 越靠近盘缘越**淡出**（|经度| ≥ [GRS_LIMB_FADE] ⇒ alpha = 0）⇒ 转到盘背时不硬切，
     *    且因为 `GRS_LIMB_FADE < GRS_LON_FAR`，行程两端各有一段完全不可见的区间 ⇒ 回绕无跳变；
     *  - 越靠近盘缘**横向压扁**（[GRS_SQUEEZE]）⇒ 球面透视，越到边缘越「侧过去」。
     *
     * 层级：⛔ 必须画在木星盘/云带之后、晨昏线之前（夜侧才会被正确压暗）。
     */
    private fun DrawScope.drawGreatRedSpot(px: Float, py: Float, pr: Float) {
        val sb = redSpotBrush
        if (sb == null) return
        // 单调西漂：`sweep` ∈ [0,1) 线性扫掠（`elapsed` 是 Double，只在此处取模并转一次 Float）
        val sweep = ((elapsed / GRS_ROT_PERIOD) % 1.0).toFloat()
        val lon = GRS_LON_FAR * (1f - 2f * sweep)
        val lonAbs = abs(lon)
        // 近盘缘淡出（线性到 0）+ 横向压扁（球面透视）
        val edgeFade = (1f - lonAbs / GRS_LIMB_FADE).coerceIn(0f, 1f)
        if (edgeFade <= 0f) return
        val squeeze = 1f - GRS_SQUEEZE * lonAbs
        val cy = py + GRS_LAT * pr
        val cx = px + lon * pr
        val cvs = drawContext.canvas
        // ① 柔边晕圈（横向放大 [GRS_HALO_K] 倍、alpha 低）—— 外围弥散的橙红
        val haloRx = pr * GRS_RX * GRS_HALO_K * squeeze
        val haloRy = pr * GRS_RY * GRS_HALO_K
        cvs.save()
        cvs.translate(cx, cy)
        cvs.scale(haloRx, haloRy)
        drawCircle(brush = sb, radius = 1f, center = Offset.Zero, alpha = GRS_HALO_ALPHA * edgeFade)
        cvs.restore()
        // ② 涡核（本体、不放大、alpha 高）—— 浓的砖红核心
        val coreRx = pr * GRS_RX * squeeze
        val coreRy = pr * GRS_RY
        cvs.save()
        cvs.translate(cx, cy)
        cvs.scale(coreRx, coreRy)
        drawCircle(brush = sb, radius = 1f, center = Offset.Zero, alpha = GRS_CORE_ALPHA * edgeFade)
        cvs.restore()
    }

    /**
     * ③ 云带的**公共**扰动（近似 fbm 的四层正弦，权重和 = 1 ⇒ 值域 ±1）：上下边缘**同相** ⇒
     * 整条带一起蜿蜒而带厚恒定（不会自交）。频率比旧版（6.2/12.7/23.1）整体提高一档。
     * [u] = 带内归一化经度；[drift] = 随 `elapsed` 漂移的相位；[ph] = 逐带相位错开。
     */
    private fun bandWave(u: Float, drift: Float, ph: Float): Float =
        sin(u * 11.3f + drift + ph) * 0.40f +
            sin(u * 19.7f - drift * 0.8f + ph * 1.7f) * 0.28f +
            sin(u * 31.1f + drift * 1.5f - ph * 0.6f) * 0.19f +
            sin(u * 47.9f - drift * 0.5f + ph * 2.3f) * 0.13f

    /**
     * ③ 云带边缘的**褶皱**扰动（同样四层正弦、权重和 = 1 ⇒ 值域 ±1）：
     * 频率是公共段的 [BAND_EDGE_FREQ_K] 倍（更细的卷曲），并与公共段**异相**
     * ⇒ 上下边缘的褶皱不对称，得到木星带边缘那种湍流涡卷感而不是整齐的平行波。
     */
    private fun bandEdgeWave(u: Float, drift: Float, ph: Float): Float =
        sin(u * 11.3f * BAND_EDGE_FREQ_K + drift * 1.3f + ph * 0.9f) * 0.38f +
            sin(u * 19.7f * BAND_EDGE_FREQ_K - drift * 1.1f - ph * 1.4f) * 0.29f +
            sin(u * 31.1f * BAND_EDGE_FREQ_K + drift * 1.9f + ph * 2.1f) * 0.20f +
            sin(u * 47.9f * BAND_EDGE_FREQ_K - drift * 0.7f - ph * 1.1f) * 0.13f

    /**
     * ⑥ 彗星：偶尔经过太阳系的一颗彗星（不定间隔约 24~44 s），带轨道弧与背日彗尾。
     *
     * **`elapsed` 的纯函数**（⛔ 禁 `Random` ⇒ 可回放、零闪烁）：
     * 窗口序号 `k = floor(elapsed / COMET_WINDOW)` 定出这一颗的全部参数
     * （起始延迟、时长、半长轴、离心率、朝向、运行方向、彗核大小、尾弯向，各取一路
     * [hashUnit] 盐位），窗口内进度 `p` 定出它在轨道上的位置。相邻两颗的间隔因此
     * 不固定，但同一时刻永远算出同一颗。
     *
     * 几何：**太阳位于焦点** ⇒ 近日点 `a(1−e)` 落在内太阳系、远日点 `a(1+e)` 在画面之外；
     * 平近点角 `M = π + 2π·p` 从远日点起扫一整圈回到远日点 ⇒ **进画与出画都在画面外**；
     * 一阶开普勒 `E = M + e·sin M` ⇒ 近日快、远日慢（不是匀速椭圆）。
     * 轨道平面仍走 [project]（含 [TILT]）⇒ 与行星轨道同一倾斜约定。
     *
     * **彗发的三重身份**：柔边由**缓存渐变**摆出、尾由**逐层锥形叠加**柔化、彗核外围有
     * **弥散光晕（coma）** ⇒ 尾从光晕里长出来，而不是从一个硬点「长」出一条硬边多边形。
     *
     * **双尾**：离子尾（蓝白、笔直、细长，严格背日）+ 尘埃尾（淡黄、弯曲、宽而短，
     * 偏向行进反侧）；夹角由一路 hash 盐给出、张向由**尾向 × 运动方向**的几何关系定出。
     *
     * 提交数：轨道弧 1 次（LOW 不画）+ 彗发 1 次 + 彗尾 3 层 × 2 尾 = 6 次 + 彗核 1 次
     * （HIGH 再 +1 次致密内核晕）⇒ LOW 8 / MEDIUM 9 / HIGH 10。
     * ⛔ 每次提交都写进**同一条**成员 Path（逐层 `rewind`），零堆分配。
     * 彗星完全走出画面（窗口结束）后轨道随之消失；进出场各留 [COMET_FADE] 的淡入淡出。
     */
    private fun DrawScope.drawComet(w: Float, h: Float, center: Offset, scale: Float) {
        val k = floor(elapsed / COMET_WINDOW).toInt()
        val uWin = (elapsed - k * COMET_WINDOW).toFloat()          // 窗口内秒（小数位仍精确）
        val startAt = cometStartAt(k)
        val dur = cometDuration(k)
        if (uWin < startAt || uWin > startAt + dur) return
        val p = ((uWin - startAt) / dur).coerceIn(0f, 1f)
        val fade = (min(p, 1f - p) / COMET_FADE).coerceIn(0f, 1f)

        val a = (COMET_A_MIN + hashUnit(k, COMET_SALT_A) * COMET_A_SPAN) *
            max(w * 0.5f / scale, h * 0.5f / (scale * TILT))       // 世界单位半长轴
        val e = COMET_E_MIN + hashUnit(k, COMET_SALT_E) * COMET_E_SPAN
        val rot = hashUnit(k, COMET_SALT_ROT) * TAU_D.toFloat()    // 轨道在平面内的朝向
        val dir = if (hashUnit(k, COMET_SALT_DIR) >= 0.5f) 1f else -1f
        val bb = sqrt(1f - e * e)                                  // 短轴 / 半长轴
        val cosR = cos(rot)
        val sinR = sin(rot)
        val rPeri = a * (1f - e)
        val rApo = a * (1f + e)

        // 当前位置（M 从远日点起扫一整圈）
        val m0 = (Math.PI + TAU_D * p).toFloat()
        val eccentric = cometAnomaly(m0, e)
        val coreR = (COMET_NUCLEUS_R + hashUnit(k, COMET_SALT_R) * COMET_NUCLEUS_SPAN) * scale
        val pos = cometPoint(eccentric, a, bb, e, cosR, sinR, dir, center, scale)
        // 彗星在画面外（远日段）时整颗不画，但轨道弧仍画（淡入淡出兜住）
        val q = ((rApo - cometRadius(a, e, eccentric)) / (rApo - rPeri)).coerceIn(0f, 1f) // 近日程度

        // ① 轨道弧（LOW 不画）
        if (tier > 0) {
            val steps = if (tier == 2) COMET_ORBIT_STEPS else COMET_ORBIT_STEPS_MED
            cometOrbitBuf.rewind()
            var s = 0
            while (s <= steps) {
                val m = TAU_D.toFloat() * s / steps
                val pt = cometPoint(
                    cometAnomaly(m, e), a, bb, e, cosR, sinR, dir, center, scale
                )
                if (s == 0) cometOrbitBuf.moveTo(pt.x, pt.y) else cometOrbitBuf.lineTo(pt.x, pt.y)
                s++
            }
            cometOrbitBuf.close()
            drawPath(cometOrbitBuf, color = cometTailColor, style = cometOrbitStroke, alpha = COMET_ORBIT_ALPHA * fade)
        }

        // ② 彗核尺寸 + **彗发（coma）**：彗核外围的弥散光晕，三档都画。
        //    柔边来自**缓存的单元圆空间径向渐变** [cometComaBrush] 经 canvas 缩放摆位
        //    （与大气边缘光 / 大红斑同一套手法 ⇒ 零逐帧分配、⛔ 零硬边圆盘）。
        //    晕半径恒 ≥ 离子尾根半宽 × [COMET_COMA_MIN_W] ⇒ 尾从光晕里「长出来」而非接缝。
        val hr = coreR * (0.75f + 0.55f * q)
        // 两条尾的长与根半宽（⛔ 以尾长为主、核半径为下限，见 [COMET_ION_ROOT_F] 的 KDoc）
        val ionLen = (COMET_TAIL_MIN + COMET_TAIL_SPAN * q) * scale * COMET_ION_LEN_K
        val dustLen = (COMET_TAIL_MIN + COMET_TAIL_SPAN * q) * scale * COMET_DUST_LEN_K
        val ionRoot = max(ionLen * COMET_ION_ROOT_F, coreR * COMET_ION_ROOT_MIN)
        val dustRoot = max(dustLen * COMET_DUST_ROOT_F, coreR * COMET_DUST_ROOT_MIN)
        // 彗发半径 = max（**最外层**离子尾的根半宽 × [COMET_COMA_MIN_W]，彗核半径 ×[COMET_COMA_K] × 近日系数）
        // ⇒ 无论哪一颗彗星、哪一档画质、近日还是远日，尾根都落在光晕里（⛔ 不留硬接缝）。
        //    ⛔ **max 的第二项不乘近日系数** —— 远日时彗核变小，若整体缩小就会缩到容不下尾根，
        //    尾与光晕之间重新露出硬接缝（这正是旧版「尾从硬点长出来」的观感）。
        val comaR = max(
            coreR * COMET_COMA_K * (0.75f + 0.35f * q),
            ionRoot * cometLayerWidthK(COMET_TAIL_LAYERS - 1) * COMET_COMA_MIN_W
        )
        val cb = cometComaBrush
        if (cb != null) {
            val cvs = drawContext.canvas
            cvs.save()
            cvs.translate(pos.x, pos.y)
            cvs.scale(comaR, comaR)
            drawCircle(brush = cb, radius = 1f, center = Offset.Zero, alpha = COMET_COMA_ALPHA * fade)
            cvs.restore()
        }

        // ③ **双尾**（⛔ 零分配：2 条尾 × 3 层 = 6 条锥形 Path，逐条现算、复用同一条 [cometTailBuf]）
        //
        //   **柔边怎么来的**：一条尾 = [COMET_TAIL_LAYERS]（3）条**同形状**锥形 Path 叠加，
        //   外层最淡最大、内层最浓最小（[cometLayerWidthK] / [cometLayerAlphaK]）⇒ 层与层
        //   之间形成渐变过渡，尾缘柔和。⛔ 不描边、⛔ 不用硬边多边形收尾（那正是旧版
        //   「贴上去的纸片」观感的根因）。
        //
        //   **锥度怎么来的**：沿尾轴 [COMET_TAIL_STEPS]+1 点采样，半宽 = `rootW ×
        //   [cometLayerWidthK] × (1−t)^[锥度] × (1 + 肩部鼓出)`（见 [cometTailProfile]），
        //   宽度**从尾根到尾尖连续递减**、尾尖三层**同点收尖**（无硬切）。
        //
        //   **两条尾的差别**（真实成因，不是随机装饰）：
        //    - **离子尾**（[COMET_ION] 蓝白）：被太阳风推 ⇒ **笔直**、细（根半宽 [COMET_ION_ROOT_F]
        //      × 尾长，见常量 KDoc）、长（基准长 ×[COMET_ION_LEN_K]）⇒ 方向严格 =
        //      `normalize(彗星 − 画面中心)`（背日），弯曲量恒 0。
        //    - **尘埃尾**（[COMET_DUST] 淡黄）：带轨道惯性 ⇒ 偏向**行进方向的反侧**、
        //      **弯曲**、宽（[COMET_DUST_ROOT_F]，≈ 离子的 2.8 倍）、更淡更短（×[COMET_DUST_LEN_K]）。
        //      夹角大小由 [hashUnit] 的 [COMET_SALT_SPLIT] 决定，而**张向哪一侧是几何的**：
        //      尾向与**屏幕速度**（[cometPoint] 的前向差分）的叉乘符号定出恒定的滞后侧
        //      ⇒ 不管彗星往哪边飞，尘埃尾永远甩在**背后**（与真实彗星一致）。
        val tvx = pos.x - center.x
        val tvy = pos.y - center.y
        val tvLen = sqrt(tvx * tvx + tvy * tvy)
        if (tvLen > 1f) {
            val ux = tvx / tvLen                                // 背日单位向量（太阳在画面中心）
            val uy = tvy / tvLen
            val px1 = -uy                                       // 背日方向的左法线（垂向）
            val py1 = ux

            // **屏幕速度** = cometPoint 在 +dE 处的有限差分（⛔ 纯函数、零分配、`Offset`
            // 是 value class）。滞后侧 = dot(−v, 左法线) 的符号（⛔ 退化时按 [COMET_SALT_BEND]
            // 的 hash 定向，不会除零也不会方向不定）。
            val pv = cometPoint(eccentric + COMET_VEL_DT, a, bb, e, cosR, sinR, dir, center, scale)
            val vx = pv.x - pos.x
            val vy = pv.y - pos.y
            val lagSign = if (vx * uy - vy * ux != 0f) {
                if (vx * uy - vy * ux > 0f) 1f else -1f
            } else {
                if (hashUnit(k, COMET_SALT_BEND) >= 0.5f) 1f else -1f
            }
            // 双尾夹角（度）：⛔ 只做小幅张角，不改变「离子尾严格背日」的硬约定
            val splitDeg = COMET_SPREAD_MIN + hashUnit(k, COMET_SALT_SPLIT) * COMET_SPREAD_SPAN
            val splitRad = splitDeg * DEG2RAD
            val cs = cos(splitRad)
            val sn = sin(splitRad)
            // 尘埃尾方向 = 背日方向朝**滞后侧**旋 [splitDeg]
            val dx = ux * cs + px1 * sn * lagSign
            val dy = uy * cs + py1 * sn * lagSign
            val qFade = (0.35f + 0.65f * q) * fade                // 近日越近越旺 + 进出场淡入淡出

            var layer = COMET_TAIL_LAYERS - 1
            while (layer >= 0) {
                val wK = cometLayerWidthK(layer)
                val aK = cometLayerAlphaK(layer)
                // 锥度逐层递减（外层收窄更晚 ⇒ 尾甩得更开）
                val taper = COMET_TAIL_TAPER - layer * COMET_TAIL_TAPER_STEP

                // 离子尾：笔直（弯曲量恒 0）
                cometTailBuf.rewind()
                buildCometTail(cometTailBuf, pos.x, pos.y, ux, uy, ionLen, ionRoot * wK, taper, 0f)
                drawPath(cometTailBuf, color = cometIonColor,
                    alpha = (COMET_ION_ALPHA * aK * qFade).coerceAtMost(1f))

                // 尘埃尾：末端弯曲（抛物线剖面，根部曲率最大）。⛔ 弯曲方向恒取 `+bend`：
                //    [buildCometTail] 内部的横向偏移是沿**尾向自身的左法线**（`−uy, ux`），
                //    而那条法线已被上面的张角旋转带到了滞后侧 ⇒ 再乘一次 [lagSign] 会把
                //    尾巴弯到**外侧**去（与真实「尘埃甩在滞后侧」相反）。故此处恒正。
                cometTailBuf.rewind()
                buildCometTail(cometTailBuf, pos.x, pos.y, dx, dy, dustLen, dustRoot * wK,
                    taper, COMET_TAIL_BEND * dustLen)
                drawPath(cometTailBuf, color = cometDustColor,
                    alpha = (COMET_DUST_ALPHA * aK * qFade).coerceAtMost(1f))
                layer--
            }
        }

        // ④ 彗核（HIGH 再叠一层致密内核晕，压在柔边彗发之上）
        if (tier == 2) {
            drawCircle(cometTailColor, radius = hr * COMET_HEAD_GLOW_K, center = pos,
                alpha = COMET_HEAD_GLOW_ALPHA * fade)
        }
        drawCircle(cometNucleusColor, radius = hr, center = pos, alpha = fade)
    }

    /**
     * 沿尾轴采样出一条**锥形彗尾**的闭合轮廓并写入 [buf]（⛔ 调用方负责 `rewind()`）。
     *
     * 半宽剖面 = [cometTailProfile]（`(1−t)^[taper] × (1 + 肩部鼓出)`），⛔ 从尾根到尾尖
     * **连续收窄**、尾尖三层同点收尖。侧向偏移 = `bend · t²`（抛物线：根部曲率最大）⇒
     * 弯曲是**连续**的（⛔ 旧版靠「中段 + 尖端」两点折线 ⇒ 尾上有明显折角）。
     *
     * 两侧边缘各 [COMET_TAIL_STEPS]+1 点 → 每层约 30 个顶点；单帧共 6 层
     * （双尾 × [COMET_TAIL_LAYERS]）≈ 180 个 `lineTo`，**全部写入同一条成员 Path**、
     * 逐层 `drawPath` ⇒ 零堆分配。
     *
     * ⛔ 噪声链全程 Float（[kotlin.math] 的 Float 重载）：混入一个字面量 `0.5` 就整条
     * 升成 Double，`Path.lineTo` 直接编译不过。
     */
    private fun buildCometTail(
        buf: Path,
        rootX: Float, rootY: Float,
        ux: Float, uy: Float,
        len: Float, halfWidth: Float, taper: Float, bend: Float
    ) {
        val px = -uy
        val py = ux
        val steps = COMET_TAIL_STEPS
        var i = 0
        while (i <= steps) {
            val t = i / steps.toFloat()
            val hw = halfWidth * cometTailProfile(t, taper)
            val off = bend * t * t
            val cx = rootX + ux * len * t + px * off
            val cy = rootY + uy * len * t + py * off
            if (i == 0) buf.moveTo(cx + px * hw, cy + py * hw) else buf.lineTo(cx + px * hw, cy + py * hw)
            i++
        }
        // 下缘：从尾尖回到尾根（t 递减）。⛔ 必须是 `+off − hw`（中线偏移同侧、半宽取反），
        // 写成 `−(hw + off)` 会让弯曲方向与上缘相反 ⇒ 尾在弯曲处**自交掐断**（Path 自交
        // 填充出怪形），是最容易写错的一处符号。
        var j = steps
        while (j >= 0) {
            val t = j / steps.toFloat()
            val hw = halfWidth * cometTailProfile(t, taper)
            val off = bend * t * t
            buf.lineTo(rootX + ux * len * t + px * (off - hw), rootY + uy * len * t + py * (off - hw))
            j--
        }
        buf.close()
    }

    /** 偏近点角 → 屏幕坐标：焦点在原点，平面内旋转 [cosR]/[sinR]，再经 [project] 压扁 */
    private fun cometPoint(
        eccentric: Float, a: Float, bb: Float, e: Float, cosR: Float, sinR: Float,
        dir: Float, center: Offset, scale: Float
    ): Offset {
        val lx = a * (cos(eccentric) - e)
        val ly = dir * a * bb * sin(eccentric)
        return project(lx * cosR - ly * sinR, lx * sinR + ly * cosR, center, scale)
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
     *
     * HIGH 档：同一条 Path 里追加同心内圈 ⇒ 卡西尼缝（两圈之间的空白），提交数不增。
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
        // ⑤ 土星环分层：主环（A 环）之外再叠一条同心内圈（B 环），两圈之间的空白即**卡西尼缝**。
        //    用 moveTo 起**独立子路径** ⇒ 不与主环弧相连（否则描边会多出一条把两圈连起来的
        //    径向线），仍是同一条 Path、同一次 drawPath ⇒ 提交数不增。
        //    缝是整圈同心分布的（真实卡西尼缝如此），不只在长轴端点。
        if (tier == 2) {
            val rxIn = rx * SATURN_RING_INNER_K
            val ryIn = rxIn * RING_Y
            k = 0
            while (k <= RING_STEPS) {
                val a = (a0 + stepDeg * k) * DEG2RAD
                val ex = cos(a) * rxIn
                val ey = sin(a) * ryIn
                val x = px + ex * cosT - ey * sinT
                val y = py + ex * sinT + ey * cosT
                if (k == 0) ringBuf.moveTo(x, y) else ringBuf.lineTo(x, y)
                k++
            }
        }
        drawPath(ringBuf, color = if (back) ringBack else ringFront, style = stroke)
    }

    /**
     * 星野（固定种子、[onEnter] 生成 → 位置永不变化、零闪烁；每 4 颗一颗偏蓝）。
     *
     * 尺寸 / 亮度是**生成期**定值（立方幂律 + `u²` 同源，见 [fillStars]）；本函数只做
     * 「**中心静默区**」压制：椭圆归一化距离落在 [QUIET_R] 内时 alpha 线性降到 [QUIET_FLOOR]
     * （消除「太阳边上一个白点」）。成本：每星 5 乘 + 1 加 + 1 比较 ——
     * **无 `sqrt` / 无分配 / 无 JNI / 不新增 draw 调用**。
     * ⛔ **不得改回均匀分布**（§四 G18 / §13.5-D5）：那正是「星光太大压住主体」的根因。
     */
    private fun DrawScope.drawStars(w: Float, h: Float, scale: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        var s = 0
        while (s < starCount) {
            val px = starX[s] * w
            val py = starY[s] * h
            val dx = (px - cx) * quietInvX
            val dy = (py - cy) * quietInvY
            // 0 = 中心，1 = 静默区边界（[quietAlpha] 内已含 `e >= 1f` 截断）
            val quiet = quietAlpha(dx * dx + dy * dy)
            drawCircle(
                color = if ((s and 3) == 0) starBlue else starWhite,
                radius = starR[s] * scale,
                center = Offset(px, py),
                alpha = starA[s] * quiet
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
     * Stroke（含彗星轨道弧）按 scale 缓存、太阳径向渐变按 (w, h, scale) 缓存（其分配型 →
     * 必须重建节流）、大气边缘光的**单元空间**径向渐变只建一次（与画布尺寸无关，逐帧用
     * canvas 缩放摆放）；晨昏线遮罩同样是单位圆空间、终身只烘一次（[bakeTerminator]）。
     * 其余帧直接复用（draw 零分配）。LOW 不建渐变（sunBrush/atmoBrush 保持 null）。
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
        if (tier == 2 && atmoBrush == null) {
            // ② 大气边缘光：**单元空间**径向渐变（透明 → 白 → 透明），逐帧用 canvas 缩放摆放
            //    ⇒ 与画布尺寸无关，只建一次；峰值落在 0.85 处 × `pr × ATMOSPHERE_GLOW_R`
            //    ≈ 行星盘缘（1.0 pr），内外各余一段柔和过渡
            atmoBrush = Brush.radialGradient(
                0f to Color.Transparent,
                0.72f to Color.Transparent,
                0.85f to Color(1f, 1f, 1f, ATMOSPHERE_GLOW_PEAK_A),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f
            )
        }
        if (tier == 2 && redSpotBrush == null) {
            // ⑦ 大红斑柔边渐变：**单元空间**径向渐变（砖红实心 → 橙红半透 → 完全透明），
            //    逐帧用 canvas 非等比缩放摆成横向椭圆 ⇒ 柔和涡旋（⛔ 非硬边椭圆）。
            //    峰值在 0.55 处（略靠内）⇒ 涡核浓、外缘化开，与真实 GRS 的「浓核 + 弥散边」一致。
            redSpotBrush = Brush.radialGradient(
                0f to Color(GRS_CORE),
                0.42f to Color(GRS_HALO),
                0.68f to Color(GRS_MID),
                1f to Color(GRS_EDGE),
                center = Offset.Zero,
                radius = 1f
            )
        }
        if (cometComaBrush == null) {
            // ⑥ 彗发柔边渐变：**单元圆空间**径向渐变（致密核心 → 弥散中段 → 近乎透明外缘），
            //    逐帧经 canvas 缩放摆到彗核 ⇒ 边缘化开到近乎透明（⛔ 非硬边圆盘）。
            //    三档都建（彗发是彗星的本体特征，LOW 档也该有），与画布尺寸无关 ⇒ 只建一次。
            cometComaBrush = Brush.radialGradient(
                0f to cometTailColor.copy(alpha = COMET_COMA_CORE_A),
                0.34f to cometTailColor.copy(alpha = COMET_COMA_MID_A),
                0.68f to cometTailColor.copy(alpha = COMET_COMA_EDGE_A),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f
            )
        }
        if (strokeScale != s) {
            strokeScale = s
            orbitStroke = Stroke((s * 0.0016f).coerceAtLeast(1.2f))
            moonStroke = Stroke((s * 0.0011f).coerceAtLeast(1f))
            ringStroke = Stroke(s * RING_W)
            cometOrbitStroke = Stroke((s * 0.0011f).coerceAtLeast(1f))
            // 中心静默区：椭圆归一化分母的倒数（与轨道同为 TILT 压扁 ⇒ 静默区也是椭圆）
            quietInvX = 1f / (s * QUIET_R)
            quietInvY = 1f / (s * QUIET_R * TILT)
        }
        return s
    }

    /**
     * ① 晨昏线遮罩烘焙（**单位圆**空间，夜方向 = +x；只烘一次，与画布尺寸/画质档无关）。
     *
     * 形状 = 夜侧半圆（外弧，沿 `−90° → +90°`，半径 1）+ 一条鼓向**日侧**的浅弧回边
     * （在 `a = 0` 处越过圆心 [PLANET_TERMINATOR_SMOOTH]）⇒ 明暗分界不是硬切的直线直径，
     * 而是带一段晨昏蒙影的浅弧（蒙影自然延伸到几何晨昏线以东）。
     */
    private fun bakeTerminator() {
        if (terminatorBaked) return
        terminatorBaked = true
        val path = terminatorPath
        path.rewind()
        val half = (Math.PI * 0.5).toFloat()
        var k = 0
        while (k <= TERMINATOR_STEPS) {
            val a = -half + Math.PI.toFloat() * k / TERMINATOR_STEPS
            val x = cos(a)
            val y = sin(a)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            k++
        }
        var j = TERMINATOR_STEPS
        while (j >= 0) {
            val a = -half + Math.PI.toFloat() * j / TERMINATOR_STEPS
            path.lineTo(-PLANET_TERMINATOR_SMOOTH * cos(a), sin(a))
            j--
        }
        path.close()
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
