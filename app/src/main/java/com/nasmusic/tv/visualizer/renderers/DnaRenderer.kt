package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E40 `DNA` — DNA 双螺旋
 *
 * 视觉：一条**自由弯曲**的 DNA 双螺旋缎带横贯画面、**两端伸出左右屏幕**（不是笔直
 * 竖线/横线，也不是「整链完整可见」—— 旧需求已于 2026-09-28 推翻）——
 * 中心路径是穿过 12 个动态控制点的均匀 Catmull-Rom 样条，控制点 y 由**双频正弦合成**
 * 持续缓慢**形状演化**；青（骨架 A [COL_A]）/ 紫（骨架 B [COL_B]）两条骨架绕路径
 * **180° 反相**螺旋，碱基对横档按**弧长等间距**连接双链（4 色 A/T/G/C 循环 [RUNG_COLS]），
 * 节点处有小球 + 柔和光晕、横档中点有小球。深近黑底 + 慢速星野，与
 * 「轨道 · 太阳系」（[OrbitalRingsRenderer]）同款风格：固定种子 LCG 星野、
 * 70/140/220 星按档、0.9 **垂直**留白满屏公式。
 *
 * **2026-09-28 第三轮 TV 试看结构性变更**（前两轮调参见 §10）：
 *  ① 扭曲再收窄：[TURNS] 3 → **2**、[HELIX_AMP] 0.115 → **0.097**（缠绕更松、更不易拧麻花）；
 *  ② **两端出屏**：缩放改为垂直主导 `scale = 0.9×(h/2)/WORLD_EXTENT_Y`（不再取与横向
 *    的 min），路径半跨度 [pathHalfSpan] 按画布宽动态外推 ⇒ 链两端恒伸出左右屏外 ≥6%，
 *    垂直方向仍完整落在 0.9 留白内 —— **推翻了此前「整链完整落在屏幕内」的旧规**；
 *  ③ 横档按**弧长**导出（`RUNG_SPACING_LOW/MED/HIGH` 目标间距 0.090/0.065/0.050 世界
 *    单位），链加长后不再固定 20/30/40 根而变稀；随之 [STRIDE] 64 → **128**、
 *    [EL_CAPACITY] 256 → **512**、[NODE_R] 0.020 → **0.017**（前景峰间隙 ≥1–2px）；
 *  ④ 中心路径弃固定 S 形，改为**双频正弦形状演化**（见 [shapeY]），数十秒内肉眼可见地
 *    缓慢变形（上一轮的 ±15% 幅度缩放被判定「曲线并没有变化」）。
 *
 * **几何模型**：
 *  - **中心路径** [pathPoint]：均匀 Catmull-Rom（端点复制法，[cr] 基函数）。
 *    控制点每帧由 [updateControlPoints] 预写入成员 cpX/cpY ——
 *    y = [shapeY] 双频正弦（k₁=[K1]、k₂=[K2] 沿 x̂ = x/pathHalfSpan ∈ [−1,1] 合成，
 *    相位 φ₁/φ₂ 以 [PHI_P1]=47s / [PHI_P2]=73s 两个互质长周期推进 ⇒ **波形沿链移动 +
 *    包络呼吸 = 形状真的在缓慢演化**，几十秒内可辨；确定性、零分配、无 Perlin 依赖）
 *    × bend（弯曲调制 = **自主慢漂移** ±15% / 37s × 音频 ±10%，只改整体幅度，
 *    连乘式见 [draw]）+ 双频正弦噪声（[NOISE_P1]/[NOISE_P2] = 11s/17s，互质 ⇒ 极慢且
 *    无可见循环）；x = 在 [−pathHalfSpan, pathHalfSpan] 等距 ± [NOISE_X] 微漂移。
 *    [pathTangent] 走同一段的**解析导数**（单位化；法向 = (−Ty, Tx)）。
 *  - **螺旋点** [helixPoint]：世界面内偏移 = 法向 × cos θ × amp，带符号深度
 *    z = sin θ ∈ [−1,1]（正 = 朝向观众）。θ = TURNS·2π·t + phase，骨架 B 传
 *    phase + π ⇒ 恒反相。**出口处做唯一的 world→screen 投影**（见「布局」）后写入
 *    成员 [helixOut]（FloatArray 值载体 —— ⛔ 不用 Triple/data class，规避逐点装箱）。
 *  - **全局旋转**：elapsed 驱动 phase —— [ROTATION_PERIOD] = 16s 转满一周
 *    （规格 12–20s 取中）；螺旋转数 [TURNS] = **2**（第三轮由 3 收窄 ⇒ 扭曲更少）。
 *  - **深度分层（z 分批，后 → 前）**：每帧把全部元素（骨架段 / 横档 / 节点）的
 *    z 装进成员 elZ/elCode 并**原地插入排序**（同太阳系写法，零分配、稳定），
 *    依序绘制：z<0 背景元素 → 横档（z=0 层）→ z>0 前景元素。前景更粗更亮更大
 *    （线宽 4 桶、alpha 0.40..1.00、节点半径 0.65..1.35×），背景反之。
 *
 * **音频驱动（慢呼吸，⛔ 不跟拍）**：
 *  - 唯一输入 [AudioFrame.energy]（复用既有分析层；**不新增录音/权限**）。经强
 *    指数平滑 [SMOOTHING_FACTOR]（0.08/帧，≈0.42s 时间常数 @30fps，**可调**）
 *    得到 audioEnergy，只做**小幅**调制：螺旋振幅 ±10%（[AMP_ENERGY_GAIN]，
 *    规格 8–12%）、路径弯曲的音频因子 ±10%（[BEND_ENERGY_GAIN]，energized 更弯；
 *    其上再连乘**独立于音频**的 [BEND_DRIFT_GAIN] 慢漂移 ⇒ 整链曲率数十秒缓慢
 *    自变化）、横档亮度 +12% 上限、骨架亮度 +6% 上限外加 9s 正弦慢起伏
 *    （[UNDUL_GAIN]）。
 *    ⛔ 不读 beat/pulse、无逐帧跳变、无节拍闪烁 —— 「缓慢呼吸」而非「随拍起舞」。
 *
 * **布局（自适应满屏，2026-09-28 第三轮需求变更）**：
 *  - 世界包围盒半高 [WORLD_EXTENT_Y] —— 样条越界（Beizer 凸包定界）+ 漂移 + 螺旋振幅
 *    + 节点光晕 + 线宽的**保守上界**，推导见常量注释；
 *  - **垂直 fit（0.9 留白）**：`layoutScale = [SCALE_MARGIN] × (h/2) / [WORLD_EXTENT_Y]`
 *    —— 只由高度定 scale，**不再取与横向的 min** ⇒ 垂直方向恒完整在屏内：
 *    **1920×1080 → scale 648.0，半高 486.0 = 0.9×540 ✓**；
 *    **1080×1920 → scale 1152.0，半高 864.0 = 0.9×960 ✓**；
 *  - **两端出屏（横向不再做 fit）**：路径半跨度 [pathHalfSpan] 由 [ensureLayout] 按
 *    `max([MIN_PATH_HALF], [OVERSHOOT]×(w/2)/layoutScale + [END_MARGIN])` 动态算出，
 *    控制点 x 以它等距铺开 ⇒ 任意宽高比两端均出屏 ≥6%：
 *    **1920×1080 → span 1.7104，标称端点 x = 1108.3px vs 960（+15.5%）**，
 *    最坏（控制点漂移 + 法向振幅，按 [END_MARGIN]=0.14 取上界）= 1017.6px 仍 **+6.0%** ✓；
 *    **1080×1920 → span 1.3000（MIN_PATH_HALF 下限生效），标称端点 x = 1497.6px
 *    vs 540（+177.3%）**，最坏 = 1336.3px 仍 **+147.5%** ✓；
 *    ⚠️ 旧 `WORLD_EXTENT_X = 1.35`（横向 fit 约束）已**删除** —— 「整链完整落在屏幕内」
 *    旧需求被推翻，横向只剩出屏校验，不再参与缩放；
 *  - **world→screen 投影**：控制点 / 样条 / 螺旋全程在**世界单位**下计算，仅在
 *    [helixPoint] 出口一处投影 `screen = center + world × scale`（center/scale 由
 *    [ensureLayout] 每帧缓存为成员 centerX/centerY/layoutScale，太阳系/齿轮款同约定）
 *    ⇒ nodeX/nodeY 存**像素**，绘制原语直接取用；半径/线宽各自 × scale **恰好一次**
 *    （上面两组适配数字正是按"投影存在"推导的，投影为仿射且 scale 已计入 ⇒ 仍成立）；
 *  - 线宽/节点半径/振幅全部由 scale（垂直 fit 轴）派生，横档数由 [pathHalfSpan] 决定的
 *    弧长派生，居中绘制；
 *  - 画布尺寸变化只走 [ensureLayout]（重建线宽缓存 + 重算 [pathHalfSpan]/[rungs]），
 *    **绝不触碰 elapsed** —— 转屏动画连续不跳（elapsed 仅在 onEnter 重置）。
 *  - **画质档**：横档数按弧长导出（`RUNG_SPACING_LOW/MED/HIGH` 目标间距 0.090 / 0.065 /
 *    0.050 世界单位，夹在 [RUNGS_MIN]24..[RUNGS_MAX]96），星数 70/140/220；
 *    LOW 无光晕无中点球、MEDIUM 双层光晕 + 中点球、HIGH 三层光晕 + 前景节点高光；
 *    任何档都渲染完整双螺旋（1920×1080 实际 46/64/83 根）。
 *
 * 性能红线（draw 内零分配）：
 *  - 元素 z 排序用成员 FloatArray/IntArray 插入排序（同太阳系）；
 *  - 螺旋/切向载体是成员 FloatArray（[helixOut]/[tangentOut]），Offset/Color 是
 *    `@JvmInline value class`（打包 Long，不落堆）；
 *  - 线宽缓存为成员 FloatArray（键 = scale + pathHalfSpan，仅其一变化才重建）；`drawLine(color, …)`
 *    的 color 重载直接配置 CanvasDrawScope **复用的** strokePaint（源码
 *    `obtainStrokePaint()` 单例）⇒ 不产生 Stroke 对象；`drawCircle` 走 Fill 单例；
 *  - 无 lambda 捕获、无 Path 分配、无 Triple/装箱、无迭代器、无 List 遍历。
 */
class DnaRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.DNA

    // ── 可调参数（全部集中在 companion，统一调参入口）─────────────────────────

    private companion object {
        /** Double 版 2π：elapsed 全程 Double，取模后再转 Float → 三角输入恒 < 2π（长会话精度） */
        const val TAU_D = Math.PI * 2.0
        /** Float 版 2π / π（螺旋扭转角；t∈[0,1]、TURNS=2 ⇒ 输入恒 < 4π，精度无忧） */
        const val TAU_F = 6.2831855f
        const val PI_F = 3.1415927f

        // ── 中心样条控制点 ──
        /**
         * 控制点数（x 等距于 [−pathHalfSpan, pathHalfSpan]，归一化 x̂ ∈ [−1, 1]；y 见
         * [BEND_BASE]/[shapeY]）。第三轮由 8 提到 **12**：k₂ = 2.5π 的空间频率在 12 点下
         * 每周期 ≈4.4 个采样（8 点只有 2.8，接近混叠），样条才跟得上双正弦形状。
         */
        const val CP_N = 12
        /**
         * 路径弯曲的**基础振幅**（世界单位）—— 双频正弦合成的包络系数（0.6/0.4 归一 ⇒
         * 形状函数最大 |值| = 1.0），取代上一轮的固定 S 形曲线（`BASE_Y` 数组，已删除）。
         * 用于 [WORLD_EXTENT_Y] 推导：|cpY| ≤ BEND_BASE × maxBend(1.265) + [NOISE_AMP]。
         */
        const val BEND_BASE = 0.30f
        /**
         * 形状演化的两个**空间**角频率（弧度 / x̂ 单位）：k₁ = 1.5π ⇒ 全链 1.5 个周期、
         * k₂ = 2.5π ⇒ 2.5 个周期；0.6/0.4 权重合成。与 [CP_N]=12 的采样密度见上。
         */
        const val K1 = PI_F * 1.5f
        const val K2 = PI_F * 2.5f
        /**
         * 形状演化的两个**时间**相位周期（秒，互质长周期）：φ₁/φ₂ 各自推进 2π，
         * ⇒ 波形沿链移动 + 0.6/0.4 包络相互呼吸，**形状持续缓慢演化**（10s 内 φ₁ 走
         * 1.35 rad ⇒ 波形平移约全链 14%，几十秒内肉眼可见）。47/73 与既有的
         * 11/16/17/37/9s **均无整倍/谐波关系**（刻意避开 9/11/16/17/37），无可见循环。
         */
        const val PHI_P1 = 47.0
        const val PHI_P2 = 73.0
        /** 路径弯曲的音频增益：audioEnergy → 弯曲因子 ∈ [1, 1.10]（±10% 小幅，能量大更弯） */
        const val BEND_ENERGY_GAIN = 0.10f
        /**
         * 路径弯曲的**自主慢漂移**增益（独立于音频，elapsed 驱动）：弯曲乘性项 ∈
         * [0.85, 1.15]。与 [BEND_ENERGY_GAIN] 连乘 ⇒ 最终 bend ∈ [0.85, 1.265] ——
         * 下界 0.85 ⇒ 弯曲**永不接近 0**。第三轮起 bend 只调**整体幅度**，
         * 形状本身由 [shapeY] 的相位推进演化（两者解耦）。
         */
        const val BEND_DRIFT_GAIN = 0.15f
        /**
         * 弯曲漂移周期（秒）：37 为质数，与噪声 11s/17s、起伏 9s、旋转 16s **均无
         * 整倍/谐波关系**（36 会与 9s 成 4 倍频，故取 37）⇒ 极慢且无可见循环。
         */
        const val BEND_DRIFT_PERIOD = 37.0
        /** 控制点 y 漂移幅度（世界单位；双频正弦之和的包络上界，确定性零分配） */
        const val NOISE_AMP = 0.05f
        /** 控制点 x 漂移幅度（世界单位） */
        const val NOISE_X = 0.03f
        /** 漂移噪声周期 1 / 2（秒，互质 ⇒ 无可见循环） */
        const val NOISE_P1 = 11.0
        const val NOISE_P2 = 17.0

        // ── 双螺旋 ──
        /**
         * 螺旋振幅（世界单位）：屏幕上 ≈ amp×scale 的法向摆幅
         * （0.16 → 0.115 → **0.097**：TV 试看三轮反馈"扭曲过强"逐轮收窄线圈半径；
         * 横档透视缩短逻辑不变 —— 端点反相 ⇒ θ→90° 时横档收成一点）。
         */
        const val HELIX_AMP = 0.097f
        /** 振幅的音频增益：±10%（规格 8–12% 区间内） */
        const val AMP_ENERGY_GAIN = 0.10f
        /**
         * 沿链的完整螺旋圈数（**2 圈**，第三轮由 3 收窄 ⇒ 扭曲更少、缠绕更松）：
         * 1920×1080 @HIGH 83 根横档 ⇒ 每圈 ≈41.5 根；因两端出屏，屏内可见 ≈1.45 圈，
         * 足以读出双螺旋；@LOW 46 根 ⇒ 每圈 ≈23 根。
         */
        const val TURNS = 2f
        /** 全局旋转一周耗时（秒，规格 12–20s 取 16） */
        const val ROTATION_PERIOD = 16.0

        // ── 横档 / 节点（数量按弧长导出 + 缓冲容量） ──
        /**
         * 档位**目标横档间距**（世界单位 / 根）—— 链加长后固定根数会变稀，改为按弧长导出：
         * `rungs = round(弧长 × [ARC_FUDGE] / 间距)` 夹在 [RUNGS_MIN]..[RUNGS_MAX]。
         * 1920×1080 弧长 4.174 ⇒ LOW/MED/HIGH = **46 / 64 / 83** 根，
         * 实际间距 0.0907 / 0.0652 / 0.0503（与目标偏差 ≤1%）。
         */
        const val RUNG_SPACING_LOW = 0.090f
        const val RUNG_SPACING_MED = 0.065f
        const val RUNG_SPACING_HIGH = 0.050f
        /** 折线弧长 → 真实弧长的修正系数（12 段弦长会低估弯曲，×1.1 补偿，偏差 ≤±15%） */
        const val ARC_FUDGE = 1.1f
        /** 横档数下/上限（上限 = 缓冲容量的依据，见 [EL_CAPACITY]） */
        const val RUNGS_MIN = 24
        const val RUNGS_MAX = 96
        /**
         * 节点数组步长（取 2 的幂 ⇒ elCode 解码用位运算）：**128** > [RUNGS_MAX] = 96，
         * 且类型段按 type×STRIDE 编码 ⇒ idx 恒 < STRIDE、type 恒在 bit≥7。
         * （第三轮 64 → 128：横档上限 40 → 96，64 装不下；[STRIDE_SHIFT] 同步 6 → 7。）
         */
        const val STRIDE = 128
        /** elCode 解码位移 = log2(STRIDE)：idx = rest and (STRIDE−1)、type = rest shr 本值 */
        const val STRIDE_SHIFT = 7
        /**
         * z 排序元素表容量：每帧元素数 = 2×(rungs−1) 段 + rungs 横档 + 2×rungs 节点
         * = 5×rungs−2（上限 rungs=96 ⇒ **478**）；取 512 留足头量，仍是成员数组零分配
         * （478 元素插入排序最坏 ≈11.4 万次移位/帧，全部是成员数组内移位，零分配可接受）。
         */
        const val EL_CAPACITY = 512
        /** elCode 类型段 */
        const val TYPE_SEG = 0
        const val TYPE_RUNG = 1
        const val TYPE_NODE = 2

        /**
         * 节点球基础半径（世界单位）。第三轮 0.020 → **0.017**：HIGH 目标横档间距 0.050，
         * 前景峰（×1.35）直径 0.054 会相触（1920×1080 s=648 下 **−2.4px 重叠**）；
         * 取 0.017 ⇒ 直径 0.0459、峰间净距 **0.0044 世界单位 = 2.8px ≥ 1–2px** ✓
         * （MED 间距 0.0652 ⇒ 净距 12.5px、LOW 0.0907 ⇒ 29.0px 更宽；竖屏 s=1152 同比放大）。
         */
        const val NODE_R = 0.017f
        /** 节点半径随深度 0.65×..1.35×（前大后小；1.35× 已计入 EXTENT） */
        const val NODE_SIZE_LO = 0.65f
        const val NODE_SIZE_GAIN = 0.70f
        /** 横档中点小球半径（世界单位，MEDIUM+）：直径 0.022 ≪ 间距 0.050 ✓ 不会相触 */
        const val MID_R = 0.011f
        /** 节点光晕层：半径倍数 / 透明度（相对节点半径；LOW 无光晕） */
        const val GLOW_R1 = 2.60f
        const val GLOW_A1 = 0.10f
        const val GLOW_R2 = 1.55f
        const val GLOW_A2 = 0.18f
        const val GLOW_R3 = 1.18f
        const val GLOW_A3 = 0.26f
        /**
         * 光晕最大半径（世界单位，EXTENT 推导用）= NODE_R × 1.35 × GLOW_R1
         * = 0.017 × 1.35 × 2.60 = **0.05967**（随 NODE_R 下调由 0.0702 重推）。
         * 光晕层（半径 0.0597、直径 ≈0.119 ≈ 2.4× 横档间距）必然相融，
         * 这是低透明度柔光的预期效果，只作接受、不为它放宽 [WORLD_EXTENT_Y]。
         */
        const val GLOW_MAX = 0.0597f

        /** 深度 → 透明度：alpha = LO + GAIN × depth01（后 0.40 → 前 1.00） */
        const val DEPTH_ALPHA_LO = 0.40f
        const val DEPTH_ALPHA_GAIN = 0.60f

        // ── 线宽（键 = scale 的缓存；drawLine(color) 重载零分配） ──
        /** z∈[−1,1] → 4 个线宽桶（后细前粗） */
        const val STROKE_BUCKETS = 4
        val STROKE_FACTOR = floatArrayOf(0.75f, 0.92f, 1.10f, 1.32f)
        const val BB_STROKE_K = 0.0052f
        const val BB_STROKE_MIN = 1.6f
        const val RUNG_STROKE_K = 0.0034f
        const val RUNG_STROKE_MIN = 1.2f

        // ── 音频（慢呼吸） ──
        /**
         * [AudioFrame.energy] 的指数平滑系数（每帧）。**可调**：越小越钝 ——
         * 0.05 ≈ 0.66s、0.12 ≈ 0.28s 时间常数 @30fps；当前 0.08 ≈ 0.42s。
         */
        const val SMOOTHING_FACTOR = 0.08f
        /** 横档基础透明度 / 能量增益（+12% 上限） */
        const val RUNG_BASE_ALPHA = 0.80f
        const val RUNG_ALPHA_GAIN = 0.12f
        /** 横档中点球透明度（乘横档 alpha） */
        const val MID_ALPHA = 0.65f
        /** 骨架亮度的能量增益（+6% 上限）与 9s 慢起伏幅度（±5%） */
        const val COLOR_ENERGY_GAIN = 0.06f
        const val UNDUL_GAIN = 0.05f
        const val UNDUL_PERIOD = 9.0

        // ── 世界包围盒（半高）+ 两端出屏（2026-09-28 需求变更） ──

        // ⛔ 旧 `WORLD_EXTENT_X = 1.35`（横向 fit 约束）已**删除**：旧需求「整链完整落在
        //    屏幕内」被推翻，链两端改为**伸出左右屏幕**（见类 KDoc 第三轮变更 ②）。
        //    横向现在只剩「出屏校验」，由下面三个常量 + [pathHalfSpan] 表达，不参与缩放。

        /**
         * 路径半跨度**下限**（世界单位）—— 不随画布缩小而把链压短，保证任意宽高比下
         * 都有足够长的链身可读（1920×1080 的 1.7104、竖屏 1080×1920 的 1.3000 皆由它
         * 或下式给出，见类 KDoc「两端出屏」两组数字）。
         */
        const val MIN_PATH_HALF = 1.3f
        /**
         * 两端出屏比例：`OVERSHOOT × (w/2) / layoutScale` ⇒ 链端**标称**屏幕 x ≥ 1.06×(w/2)
         * （即至少超出左右屏 6%）。
         */
        const val OVERSHOOT = 1.06f
        /**
         * 出屏**最坏情形**余量（世界单位）= 控制点 x 漂移 [NOISE_X] + 螺旋法向振幅最大
         * 横向投影 HELIX_AMP×(1+AMP_ENERGY_GAIN) = 0.03 + 0.1067 = 0.1367 → 取 **0.14**。
         * 加进 span 后，端点即使取到最内侧也仍 ≥ 6% 出屏（1920×1080 实测 +6.0%）。
         */
        const val END_MARGIN = 0.14f

        /**
         * 世界系包围盒**半高**（**唯一**参与缩放的 extent）。推导
         * （bend 上界 = (1+[BEND_DRIFT_GAIN])×(1+[BEND_ENERGY_GAIN]) = 1.15×1.10 = **1.265**）：
         *  ① 控制点 |y| ≤ [BEND_BASE]×1.265 + [NOISE_AMP] = 0.30×1.265 + 0.05 = 0.4295
         *     （双频正弦 0.6/0.4 归一 ⇒ 包络系数恒 ≤ 1.0；噪声在 bend 之外另加）；
         *  ② 均匀 Catmull-Rom 段 ≡ 三次 Bezier（控制顶点 P1、P1+(P2−P0)/6、
         *     P2−(P3−P1)/6、P2）⇒ 凸包越界 ≤ max|P2−P0|/6 ≤ 2×0.4295/6 = 0.1432
         *     → 路径 |y| ≤ 0.5727；
         *  ③ + 螺旋振幅上界 [HELIX_AMP]×1.10 = 0.097×1.10 = 0.1067；
         *  ④ + 节点光晕上界 [GLOW_MAX] = 0.0597；⑤ + 线宽半宽 ≈ 0.0034。
         *  合计 0.5727 + 0.1067 + 0.0597 + 0.0034 = **0.7425 → 取 0.75**（余量 0.0075）。
         *  （横档中点球在路径上：0.5727 + [MID_R] = 0.5837 ≪ 0.75 ✓；LOW 档无光晕更小 ✓）
         *
         * **垂直 fit 验证**（scale 由本值直接定义 ⇒ 半高恒 = 0.9×(h/2)，代数恒等）：
         *  - 1920×1080 → scale = 0.9×540/0.75 = **648.0**，半高 0.75×648.0 = **486.0 = 0.9×540** ✓
         *  - 1080×1920 → scale = 0.9×960/0.75 = **1152.0**，半高 0.75×1152.0 = **864.0 = 0.9×960** ✓
         */
        const val WORLD_EXTENT_Y = 0.75f
        /** 满屏缩放留白系数（太阳系同款：边缘留 10% 空白；此处只约束**垂直**） */
        const val SCALE_MARGIN = 0.9f

        const val STAR_MAX = 220

        // ── 配色 ──
        const val BG = 0xFF05070D.toInt()      // 深空底（与太阳系同款）
        const val COL_A = 0xFF00E5FF.toInt()    // 骨架 A：青
        const val COL_B = 0xFFB388FF.toInt()    // 骨架 B：紫
        const val MID_DOT = 0xFFB2EBF2.toInt()  // 横档中点球：浅青白
        const val STAR_BLUE = 0xFFBFD4FF.toInt() // 星野：每 4 颗一颗偏蓝（同太阳系）
        /** 碱基对横档 4 色循环（A/T/G/C，柔色） */
        val RUNG_COLS = intArrayOf(
            0xFF4DB6AC.toInt(),   // A 青绿
            0xFFFF8A65.toInt(),   // T 珊瑚
            0xFF9575CD.toInt(),   // G 薰衣草
            0xFFFFD54F.toInt()    // C 琥珀
        )
    }

    // ── 构造期一次生成，draw 只读 ─────────────────────────────────────────────

    private val bgColor = Color(BG)
    private val colA = Color(COL_A)
    private val colB = Color(COL_B)
    private val midDotColor = Color(MID_DOT)
    private val starWhite = Color.White
    private val starBlue = Color(STAR_BLUE)
    /** 横档 4 色（实例化一次；Array<Color> 元素装箱发生在构造期，draw 只读不分配） */
    private val rungCols = Array(RUNG_COLS.size) { i -> Color(RUNG_COLS[i]) }

    /** 星野（onEnter 固定种子生成一次 → 零闪烁；坐标 w/h 归一化 0..1） */
    private val starX = FloatArray(STAR_MAX)
    private val starY = FloatArray(STAR_MAX)
    private val starR = FloatArray(STAR_MAX)
    private val starA = FloatArray(STAR_MAX)
    private var starCount = 0

    /** 中心样条控制点（每帧 [updateControlPoints] 先于一切 pathPoint 写入） */
    private val cpX = FloatArray(CP_N)
    private val cpY = FloatArray(CP_N)

    /** 段索引暂存（[pathPoint]/[pathTangent] 共用，成员字段零分配） */
    private var segU = 0f
    private var segI0 = 0
    private var segI1 = 0
    private var segI2 = 0
    private var segI3 = 0

    /** [helixPoint] 输出载体：[0]=屏幕 x、[1]=屏幕 y、[2]=归一化深度 z∈[−1,1]（正=朝向观众） */
    private val helixOut = FloatArray(3)
    /** [pathTangent] 输出载体（单位切向量） */
    private val tangentOut = FloatArray(2)

    // ── 节点缓冲（每帧写入，单位=像素 —— [helixPoint] 出口已投影；下标 = b*STRIDE + i，b=0 骨架 A / 1 骨架 B）──

    private val nodeX = FloatArray(2 * STRIDE)
    private val nodeY = FloatArray(2 * STRIDE)
    private val nodeZ = FloatArray(2 * STRIDE) // 归一化深度 [−1,1]

    // ── z 分批绘制缓冲（成员复用 → 每帧插入排序零分配）──────────────────────

    /** 元素 z（段 = 两端均值、横档 = 0、节点 = 自身），容量 [EL_CAPACITY] = 512 ≥ 5×96−2 = 478 */
    private val elZ = FloatArray(EL_CAPACITY)
    /** 打包元素码：((type×STRIDE + idx) shl 1) or b —— 解码见 draw() */
    private val elCode = IntArray(EL_CAPACITY)
    private var elCount = 0

    // ── 线宽缓存（键 = scale；仅 ensureLayout 检测到 scale 变化才重建）────────

    private var strokeScale = -1f
    private val bbWidth = FloatArray(STROKE_BUCKETS)
    private val rungWidth = FloatArray(STROKE_BUCKETS)

    // ── world→screen 投影缓存（ensureLayout 每帧写入，helixPoint 出口唯一读取处）──
    //    约定与 [ConcentricGearsRenderer] 相同：screen = center + world × scale

    /** 画布中心 x（像素）= w/2 */
    private var centerX = 0f
    /** 画布中心 y（像素）= h/2 */
    private var centerY = 0f
    /** 世界单位 → 像素的投影缩放（= ensureLayout 返回值 s） */
    private var layoutScale = 0f

    /**
     * 路径半跨度（世界单位）：控制点 x 铺满 [−pathHalfSpan, pathHalfSpan]，由
     * [ensureLayout] 按画布宽算出 —— 这是「两端出屏」的唯一来源。
     * ⛔ 赋值必须先于 [updateControlPoints] —— draw 内 `ensureLayout(w, h)` 先于
     * `updateControlPoints(elapsed, bend)` 调用，顺序已保证。
     */
    private var pathHalfSpan = MIN_PATH_HALF
    /** 本档目标横档间距（世界单位/根，onEnter 按 tier 定；rungs 由 ensureLayout 按弧长算出） */
    private var rungSpacing = RUNG_SPACING_MED

    // ── 逐帧状态 ──────────────────────────────────────────────────────────────

    private var lastMs = 0L
    /** 统一动画时钟（秒，Double，逐帧 dt 累加 —— ⛔ 禁 nowMs × 速率，float 精度会掉） */
    private var elapsed = 0.0
    /** 0 = LOW / 1 = MEDIUM / 2 = HIGH（onEnter 解析一次） */
    private var tier = 1
    /** 本档横槽数（**首帧由 [ensureLayout] 按弧长 × rungSpacing 覆写**；三档都是完整双螺旋） */
    private var rungs = RUNGS_MIN
    /** 强平滑后的音频能量（全场唯一音频状态） */
    private var audioEnergy = 0f

    override fun onEnter(ctx: RenderContext) {
        lastMs = 0L
        elapsed = 0.0
        audioEnergy = 0f
        strokeScale = -1f // 线宽缓存失效 → 首帧重建（同一个 if 块内也重算 pathHalfSpan/rungs）
        // ⚠️ 此处是唯一重置 elapsed 的地方；ensureLayout/尺寸变化路径不碰 elapsed

        tier = when (ctx.quality) {
            VisualQuality.LOW -> 0
            VisualQuality.MEDIUM -> 1
            VisualQuality.HIGH -> 2
        }
        // 横档数不再固定：只定目标间距，rungs 由 ensureLayout 按弧长导出
        rungSpacing = when (tier) {
            0 -> RUNG_SPACING_LOW
            1 -> RUNG_SPACING_MED
            else -> RUNG_SPACING_HIGH
        }
        starCount = when (tier) {
            0 -> 70
            1 -> 140
            else -> STAR_MAX
        }

        // ── 星野：固定种子 LCG 一次性生成，之后永不重掷（零闪烁；与太阳系同款）──
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
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        // ── 时间：逐帧 dt（clamp 0.1s）累加为 Double —— elapsed 的唯一推进路径 ──
        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        elapsed += dtSec.toDouble()

        // 自适应满屏 scale + pathHalfSpan + rungs（画布尺寸/档位变化即重建线宽缓存与
        // 横档数；⛔ 不触碰 elapsed）。必须先于 updateControlPoints —— 后者读 pathHalfSpan
        val s = ensureLayout(w, h)

        // ── 音频：唯一输入 energy，强指数平滑（SMOOTHING_FACTOR 可调）→ 慢呼吸 ──
        audioEnergy += (frame.energy - audioEnergy) * SMOOTHING_FACTOR
        val energy = audioEnergy

        // ── 全局旋转相位：16s 一周；Double 取模后转 Float → 三角输入 < 2π ──
        val rot = (elapsed % ROTATION_PERIOD / ROTATION_PERIOD * TAU_D).toFloat()

        // 音频小幅调制：振幅 ±10%；路径弯曲 = **自主慢漂移**（±15%、37s、独立于
        // 音频，elapsed 驱动）× 音频 ±10%（energy 大 → 更弯）
        // ⇒ bend ∈ [0.85, 1.265]，永不过 0；取模后转 Float ⇒ 长会话 Double 精度
        val amp = HELIX_AMP * (1f + energy * AMP_ENERGY_GAIN)
        val drift = 1f + BEND_DRIFT_GAIN *
            sin((elapsed % BEND_DRIFT_PERIOD) / BEND_DRIFT_PERIOD * TAU_D).toFloat()
        val bend = drift * (1f + energy * BEND_ENERGY_GAIN)

        // 控制点（漂移 + 弯曲调制）—— 必须先于一切 pathPoint / helixPoint
        updateControlPoints(elapsed, bend)

        // 骨架颜色慢起伏：9s 正弦 ±5% + 能量 +6%（Color 为 value class，零分配）
        val undul = 1f +
            UNDUL_GAIN * sin((elapsed % UNDUL_PERIOD) / UNDUL_PERIOD * TAU_D).toFloat() +
            energy * COLOR_ENERGY_GAIN
        val backboneA = tint(colA, undul)
        val backboneB = tint(colB, undul)

        // ── 节点：每档横档两端各一颗；骨架 B 相位 +π ⇒ 与 A 恒反相 ──
        var i = 0
        while (i < rungs) {
            val t = i / (rungs - 1f)
            helixPoint(t, rot, amp)
            nodeX[i] = helixOut[0]
            nodeY[i] = helixOut[1]
            nodeZ[i] = helixOut[2]
            helixPoint(t, rot + PI_F, amp)
            nodeX[STRIDE + i] = helixOut[0]
            nodeY[STRIDE + i] = helixOut[1]
            nodeZ[STRIDE + i] = helixOut[2]
            i++
        }

        // ── 元素表：段（先入表 ⇒ 等 z 时节点后画、盖住段端）→ 横档(z=0) → 节点 ──
        //    上限 = 5×rungs−2 ≤ 5×96−2 = **478** ≤ [EL_CAPACITY]（512）——索引不越界
        var n = 0
        var bk = 0
        while (bk < 2) {
            val off = bk * STRIDE
            var seg = 0
            while (seg < rungs - 1) {
                elZ[n] = (nodeZ[off + seg] + nodeZ[off + seg + 1]) * 0.5f
                elCode[n] = (seg shl 1) or bk // TYPE_SEG = 0
                n++
                seg++
            }
            bk++
        }
        i = 0
        while (i < rungs) {
            // A/B 反相 ⇒ 横档中点恰在中心路径上、整体 z = 0（排在后/前两批之间）
            elZ[n] = 0f
            elCode[n] = ((TYPE_RUNG * STRIDE + i) shl 1)
            n++
            i++
        }
        bk = 0
        while (bk < 2) {
            val off = bk * STRIDE
            var j = 0
            while (j < rungs) {
                elZ[n] = nodeZ[off + j]
                elCode[n] = ((TYPE_NODE * STRIDE + j) shl 1) or bk
                n++
                j++
            }
            bk++
        }
        elCount = n

        // ── z 升序插入排序（后 → 前；成员数组原地、零分配；稳定 ⇒ 等 z 保持入表序）──
        var k = 1
        while (k < elCount) {
            val zKey = elZ[k]
            val cKey = elCode[k]
            var j = k - 1
            while (j >= 0 && elZ[j] > zKey) {
                elZ[j + 1] = elZ[j]
                elCode[j + 1] = elCode[j]
                j--
            }
            elZ[j + 1] = zKey
            elCode[j + 1] = cKey
            k++
        }

        // ── 深空底 + 星野（固定种子 → 零闪烁）──
        drawRect(bgColor)
        drawStars(w, h, s)

        // ── z 分批绘制：背景（z<0）→ 横档（z=0）→ 前景（z>0）──
        val rungAlpha = RUNG_BASE_ALPHA * (1f - RUNG_ALPHA_GAIN + energy * RUNG_ALPHA_GAIN)
        k = 0
        while (k < elCount) {
            val code = elCode[k]
            val z = elZ[k]
            val rest = code shr 1
            val idx = rest and (STRIDE - 1)
            val type = rest shr STRIDE_SHIFT
            val b = code and 1
            when (type) {
                TYPE_SEG -> drawSeg(b, idx, z, if (b == 0) backboneA else backboneB)
                TYPE_RUNG -> drawRung(idx, rungAlpha, s)
                else -> drawNode(b, idx, z, if (b == 0) backboneA else backboneB, s)
            }
            k++
        }
    }

    // ── 绘制原语（z 分批内逐元素调用；全部原生参数，零分配）──────────────────

    /** 骨架段：z → 线宽桶（4 级）与透明度（0.40..1.00），前粗前亮、后细后暗 */
    private fun DrawScope.drawSeg(b: Int, i: Int, z: Float, color: Color) {
        val off = b * STRIDE
        val depth = z * 0.5f + 0.5f
        val bucket = ((z + 1f) * 0.5f * STROKE_BUCKETS).toInt().coerceIn(0, STROKE_BUCKETS - 1)
        drawLine(
            color = color,
            start = Offset(nodeX[off + i], nodeY[off + i]),
            end = Offset(nodeX[off + i + 1], nodeY[off + i + 1]),
            strokeWidth = bbWidth[bucket],
            cap = StrokeCap.Round,
            alpha = DEPTH_ALPHA_LO + DEPTH_ALPHA_GAIN * depth
        )
    }

    /**
     * 横档：A → B 直线 + 中点小球（MEDIUM+）。
     * 屏幕长度 = 2×amp×|cos θ|×scale —— 端点反相 ⇒ 自然透视缩短（θ→90° 时收成一点，
     * 圆头线帽正好呈横档正对观众的截面，物理上自洽）；z=0 ⇒ 恒走中层线宽桶。
     */
    private fun DrawScope.drawRung(i: Int, alphaBase: Float, s: Float) {
        val a0 = i
        val b0 = STRIDE + i
        val x0 = nodeX[a0]
        val y0 = nodeY[a0]
        val x1 = nodeX[b0]
        val y1 = nodeY[b0]
        drawLine(
            color = rungCols[i and 3],
            start = Offset(x0, y0),
            end = Offset(x1, y1),
            strokeWidth = rungWidth[STROKE_BUCKETS / 2],
            cap = StrokeCap.Round,
            alpha = alphaBase * (DEPTH_ALPHA_LO + DEPTH_ALPHA_GAIN * 0.5f)
        )
        if (tier > 0) {
            drawCircle(
                color = midDotColor,
                radius = MID_R * s,
                center = Offset((x0 + x1) * 0.5f, (y0 + y1) * 0.5f),
                alpha = alphaBase * MID_ALPHA
            )
        }
    }

    /** 节点球：深度 → 半径（0.65..1.35×）与透明度；光晕层数按档（LOW 无光晕） */
    private fun DrawScope.drawNode(b: Int, i: Int, z: Float, color: Color, s: Float) {
        val off = b * STRIDE + i
        val x = nodeX[off]
        val y = nodeY[off]
        val depth = z * 0.5f + 0.5f
        val r = NODE_R * s * (NODE_SIZE_LO + NODE_SIZE_GAIN * depth)
        val alpha = DEPTH_ALPHA_LO + DEPTH_ALPHA_GAIN * depth
        val c = Offset(x, y)
        if (tier >= 1) {
            drawCircle(color, r * GLOW_R1, c, alpha = alpha * GLOW_A1)
            drawCircle(color, r * GLOW_R2, c, alpha = alpha * GLOW_A2)
        }
        if (tier == 2) {
            drawCircle(color, r * GLOW_R3, c, alpha = alpha * GLOW_A3)
        }
        drawCircle(color, r, c, alpha = alpha)
        if (tier == 2 && z > 0.2f) {
            // HIGH：前景节点斜上高光（半径 0.36r、偏移 0.30r ⇒ 恒在盘内）
            drawCircle(starWhite, r * 0.36f, Offset(x - r * 0.30f, y - r * 0.30f), alpha = 0.72f)
        }
    }

    /** 星野（固定种子、onEnter 生成 → 位置永不变化、零闪烁；每 4 颗一颗偏蓝） */
    private fun DrawScope.drawStars(w: Float, h: Float, s: Float) {
        var st = 0
        while (st < starCount) {
            drawCircle(
                color = if ((st and 3) == 0) starBlue else starWhite,
                radius = starR[st] * s,
                center = Offset(starX[st] * w, starY[st] * h),
                alpha = starA[st]
            )
            st++
        }
    }

    // ── 统一路径 / 螺旋计算（纯函数；数据与绘制分离）──────────────────────────

    /**
     * 中心路径的**形状函数**（世界单位）—— 双频正弦合成：
     * `shapeY(x̂, φ₁, φ₂) = [BEND_BASE] × (0.6·sin([K1]·x̂ + φ₁) + 0.4·sin([K2]·x̂ + φ₂))`
     *  - x̂ = x/pathHalfSpan ∈ [−1,1]（空间轴；含 x 噪声时约 ±1.02）；
     *  - φ₁/φ₂ 是**时间相位**（[PHI_P1]=47s / [PHI_P2]=73s，互质长周期）——持续推进
     *    ⇒ 波形沿链移动 + 两个分量相互错拍 ⇒ **曲线形状真的在缓慢演化**，几十秒内
     *    肉眼可见（10s 内 φ₁ 走 1.35 rad ⇒ 波形平移约全链 14%；上一轮只做 ±15% 幅度
     *    缩放，被判定「曲线并没有变化」，故改为形状演化）；
     *  - 0.6 + 0.4 = 1 ⇒ 包络系数 ≤ 1 ⇒ |shapeY| ≤ BEND_BASE（[WORLD_EXTENT_Y] 推导前提）。
     * 确定性、零分配（每帧 [CP_N]×2 ≈ 24 次 sin）。传 φ₁=φ₂=0 即**名义形状** ——
     * [ensureLayout] 用它估弧长 ⇒ 横档数只随尺寸/档位变，**不随帧**。
     */
    private fun shapeY(xh: Float, phi1: Double, phi2: Double): Float =
        (BEND_BASE * (0.6 * sin(K1 * xh + phi1) + 0.4 * sin(K2 * xh + phi2))).toFloat()

    /**
     * 每帧一次：把 [CP_N] 个控制点写入成员 cpX/cpY。
     *  x = 在 [−pathHalfSpan, pathHalfSpan] 等距 ± [NOISE_X] 漂移（权重 0.6/0.4 两频，
     *    包络 ≤ NOISE_X）—— 半跨度由 [ensureLayout] 按画布宽定 ⇒ **两端出屏**；
     *  y = [shapeY]（双频正弦形状演化，相位 47s/73s 推进）
     *    × bend（自主慢漂移 ±15%/37s × 音频 ±10%，在 draw 中算好传入 —— 第三轮起
     *    bend **只调整体幅度**，形状交给 shapeY 的相位演化，两者解耦）
     *    + 双频正弦噪声（[NOISE_P1]/[NOISE_P2] = 11s/17s，包络 ≤ [NOISE_AMP]）。
     * 时间先对各自周期取模再转 Double 参与三角 ⇒ 三角输入恒 < 2π（长会话精度）。
     * ⛔ 必须在 [ensureLayout] 之后调用（读成员 [pathHalfSpan]）。
     */
    private fun updateControlPoints(time: Double, bend: Float) {
        val phi1 = (time % PHI_P1) / PHI_P1 * TAU_D
        val phi2 = (time % PHI_P2) / PHI_P2 * TAU_D
        val w1 = (time % NOISE_P1) / NOISE_P1 * TAU_D
        val w2 = (time % NOISE_P2) / NOISE_P2 * TAU_D
        val half = pathHalfSpan
        val spacing = 2f * half / (CP_N - 1)
        var i = 0
        while (i < CP_N) {
            val p1 = i * 1.7
            val p2 = i * 2.9
            cpX[i] = -half + i * spacing +
                (NOISE_X * (0.6 * sin(w2 + p2 + 1.1) + 0.4 * sin(w1 + p1 + 2.3))).toFloat()
            cpY[i] = shapeY(cpX[i] / half, phi1, phi2) * bend +
                NOISE_AMP * (0.62 * sin(w1 + p1) + 0.38 * sin(w2 + p2)).toFloat()
            i++
        }
    }

    /** 定位 t 所在段（端点复制法），写入段成员 segU/segI0..segI3（零分配） */
    private fun segment(t: Float) {
        val last = CP_N - 1 // 段数 = 点数 − 1
        val sf = t.coerceIn(0f, 1f) * last
        var st = sf.toInt()
        if (st >= last) st = last - 1
        segU = sf - st
        segI0 = if (st > 0) st - 1 else 0
        segI1 = st
        segI2 = st + 1
        segI3 = if (st + 2 < CP_N) st + 2 else CP_N - 1
    }

    /**
     * 均匀 Catmull-Rom 基函数（Horner 形式）：
     * `0.5·(2P1 + u·((−P0+P2) + u·((2P0−5P1+4P2−P3) + u·(−P0+3P1−3P2+P3))))`
     */
    private fun cr(p0: Float, p1: Float, p2: Float, p3: Float, u: Float): Float {
        val a = -p0 + p2
        val b = 2f * p0 - 5f * p1 + 4f * p2 - p3
        val c = -p0 + 3f * p1 - 3f * p2 + p3
        return 0.5f * (2f * p1 + u * (a + u * (b + u * c)))
    }

    /**
     * 中心路径点：t ∈ [0,1] → **世界坐标**（投影在 [helixPoint] 出口完成，本函数不投影）。
     * 前置条件：本帧已调用 [updateControlPoints]
     * （时间依赖已预折进控制点 —— 每帧一次而非每采样一次，[CP_N]×6 = 72 个正弦省成
     * ~1150 个；192 次 helixPoint × 每点 6 个）。
     * `Offset` 是 `@JvmInline value class`（打包 Long）⇒ 返回零分配。
     */
    private fun pathPoint(t: Float): Offset {
        segment(t)
        val x = cr(cpX[segI0], cpX[segI1], cpX[segI2], cpX[segI3], segU)
        val y = cr(cpY[segI0], cpY[segI1], cpY[segI2], cpY[segI3], segU)
        return Offset(x, y)
    }

    /** 路径单位切向量：同段解析导数，写入 [tangentOut]（法向 = (−Ty, Tx)） */
    private fun pathTangent(t: Float) {
        segment(t)
        val u = segU
        val x0 = cpX[segI0]
        val x1 = cpX[segI1]
        val x2 = cpX[segI2]
        val x3 = cpX[segI3]
        val y0 = cpY[segI0]
        val y1 = cpY[segI1]
        val y2 = cpY[segI2]
        val y3 = cpY[segI3]
        // d/du = 0.5·((−P0+P2) + 2(2P0−5P1+4P2−P3)u + 3(−P0+3P1−3P2+P3)u²)
        val dx = 0.5f * ((-x0 + x2) + 2f * (2f * x0 - 5f * x1 + 4f * x2 - x3) * u +
            3f * (-x0 + 3f * x1 - 3f * x2 + x3) * u * u)
        val dy = 0.5f * ((-y0 + y2) + 2f * (2f * y0 - 5f * y1 + 4f * y2 - y3) * u +
            3f * (-y0 + 3f * y1 - 3f * y2 + y3) * u * u)
        val len = sqrt(dx * dx + dy * dy)
        if (len > 1e-6f) {
            tangentOut[0] = dx / len
            tangentOut[1] = dy / len
        } else { // x 单调的控制点下实际不会触发，防御性回退
            tangentOut[0] = 1f
            tangentOut[1] = 0f
        }
    }

    /**
     * 螺旋点：t ∈ [0,1]、相位 [phase]、振幅 [amp] → 写入 [helixOut]：
     * [0] = 屏幕 x、[1] = 屏幕 y、[2] = 带符号深度 z ∈ [−1,1]（正 = 朝向观众）。
     * 世界面内偏移 = 法向(−Ty, Tx) × cos θ × amp；深度 = sin θ；
     * θ = TURNS·2π·t + phase（骨架 B 传 phase + π ⇒ 与 A 恒差 180° 反相）。
     * **投影**：全链路唯一的世界→屏幕出口 —— `screen = center + world × scale`，
     * center/scale 由 [ensureLayout] 每帧缓存（centerX/centerY/layoutScale）；
     * 切向是方向量，均匀缩放不改变方向 ⇒ 法向/偏移无需改。
     * ⛔ 载体是成员 FloatArray —— 不用 Triple/data class（规避逐点装箱）。
     */
    private fun helixPoint(t: Float, phase: Float, amp: Float) {
        val p = pathPoint(t)
        pathTangent(t)
        val nx = -tangentOut[1]
        val ny = tangentOut[0]
        val theta = t * TURNS * TAU_F + phase
        val ct = cos(theta)
        val st = sin(theta)
        val wx = p.x + nx * ct * amp
        val wy = p.y + ny * ct * amp
        helixOut[0] = centerX + wx * layoutScale
        helixOut[1] = centerY + wy * layoutScale
        helixOut[2] = st
    }

    // ── 缓存维护 / 小工具 ──────────────────────────────────────────────────────

    /**
     * 自适应满屏缩放 + 路径半跨度 + 横档数 + 投影缓存 + 线宽缓存维护，返回本帧 scale。
     *
     * **① 垂直主导 scale（2026-09-28 第三轮）**：
     * `layoutScale = [SCALE_MARGIN] × (h/2) / [WORLD_EXTENT_Y]` —— **不再取与横向的 min**
     * ⇒ 垂直方向恒等于 0.9 留白（代数恒等：EXTENT_Y × scale = 0.9×h/2），任意宽高比下
     * 整条链（含光晕/线宽）的**垂直**投影恒在 0.9 边界内（1920×1080 → 648.0/半高 486.0、
     * 1080×1920 → 1152.0/半高 864.0，推导与数字见类 KDoc「布局」）。
     *
     * **② 路径半跨度（两端出屏）**：
     * `pathHalfSpan = max([MIN_PATH_HALF], [OVERSHOOT]×(w/2)/layoutScale + [END_MARGIN])`
     * ⇒ 控制点 x 铺满 ±pathHalfSpan，两端恒伸出左右屏外（1920×1080 标称 +15.5%、
     * 最坏 +6.0%；1080×1920 +177% / +148%）。⚠️ 这一步**必须先于** [updateControlPoints]。
     *
     * **③ 横档数按弧长导出（只随尺寸/档位，不随帧）**：
     * 控制点取名义形状（[shapeY] φ=0）折线长 × [ARC_FUDGE] 估弧长，
     * `rungs = round(弧长 / [rungSpacing])` 夹在 [RUNGS_MIN]..[RUNGS_MAX]。
     *
     * 同时缓存投影参数 centerX = w/2、centerY = h/2、layoutScale = s —— [helixPoint] 出口
     * 据此做 `screen = center + world × scale`（与 [ConcentricGearsRenderer] 的
     * `cx/cy + world × unit` 同约定）。⛔ 本函数不触碰 elapsed —— 尺寸变化不会让动画
     * 倒退/重置。线宽/横档数缓存键 = (scale, pathHalfSpan)：仅其中之一变化才重建
     * （转屏/分屏/档位切换 —— onEnter 会把 strokeScale 置 −1 强制首帧重建）。
     */
    private fun ensureLayout(w: Float, h: Float): Float {
        // ① 垂直 fit（0.9 留白）—— 只由高度定 scale
        val s = SCALE_MARGIN * (h * 0.5f) / WORLD_EXTENT_Y
        // ② 两端出屏：标称 ≥6% 超出 ±(w/2)，再加最坏情形余量 END_MARGIN
        val span = maxOf(MIN_PATH_HALF, OVERSHOOT * (w * 0.5f) / s + END_MARGIN)
        centerX = w * 0.5f
        centerY = h * 0.5f
        layoutScale = s
        // 缓存键 = (scale, span)：尺寸变化 / 档位切换（strokeScale = −1）才进入
        if (strokeScale != s || pathHalfSpan != span) {
            pathHalfSpan = span // ⛔ 必须先于本帧后续的 updateControlPoints（读成员）
            strokeScale = s
            val bb = (s * BB_STROKE_K).coerceAtLeast(BB_STROKE_MIN)
            val rg = (s * RUNG_STROKE_K).coerceAtLeast(RUNG_STROKE_MIN)
            var i = 0
            while (i < STROKE_BUCKETS) {
                val f = STROKE_FACTOR[i]
                bbWidth[i] = bb * f
                rungWidth[i] = rg * f
                i++
            }
            // ③ 名义弧长（控制点折线 ×1.1）→ 横档数：只随尺寸/档位变化，⛔ 不随帧
            var arc = 0f
            var px = -span
            var py = shapeY(-1f, 0.0, 0.0)
            var cpi = 1
            while (cpi < CP_N) {
                val xh = -1f + 2f * cpi / (CP_N - 1)
                val cx = xh * span
                val cy = shapeY(xh, 0.0, 0.0)
                val dx = cx - px
                val dy = cy - py
                arc += sqrt(dx * dx + dy * dy)
                px = cx
                py = cy
                cpi++
            }
            rungs = (arc * ARC_FUDGE / rungSpacing + 0.5f).toInt()
                .coerceIn(RUNGS_MIN, RUNGS_MAX) // ≤ 96 ⇒ 元素表 ≤ 478 ≤ EL_CAPACITY(512)
        }
        return s
    }

    /** 亮度调制（value class 运算，零分配）；k 略 > 1 轻微提亮，超界由 coerce 截断 */
    private fun tint(c: Color, k: Float): Color = Color(
        (c.red * k).coerceIn(0f, 1f),
        (c.green * k).coerceIn(0f, 1f),
        (c.blue * k).coerceIn(0f, 1f),
        c.alpha
    )
}
