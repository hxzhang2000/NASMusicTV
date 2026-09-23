package com.nasmusic.tv.visualizer.photo

import com.nasmusic.tv.visualizer.photo.transitions.BlindsTransition
import com.nasmusic.tv.visualizer.photo.transitions.CrossfadeTransition
import com.nasmusic.tv.visualizer.photo.transitions.FadeBlackTransition
import com.nasmusic.tv.visualizer.photo.transitions.IrisCircleTransition
import com.nasmusic.tv.visualizer.photo.transitions.LightSweepTransition
import com.nasmusic.tv.visualizer.photo.transitions.NoiseDissolveTransition
import com.nasmusic.tv.visualizer.photo.transitions.SlideTransition
import com.nasmusic.tv.visualizer.photo.transitions.SpectrumWipeTransition
import com.nasmusic.tv.visualizer.photo.transitions.WipeLinearTransition
import com.nasmusic.tv.visualizer.photo.transitions.ZoomTransition

/**
 * 转场注册表（id → 实现）
 *
 * ## 「单例」的含义
 *
 * 每个实现只创建**一个**实例并全进程复用 —— 所以实现类**必须无状态**，
 * 或者只持有可被 `prepare()` 按几何重建的缓冲（见 `PhotoTransition` 的约束 2）。
 * P0 的 15 个实现全部满足：需要的只有 `geom`（每帧传入）、`p`（每帧传入）
 * 与 `MaskCache`（全进程共享的只读遮罩）。
 *
 * ⚠️ 有状态的实现（例如「记住上一次的方向」）**不能**放进这里 ——
 * 主题级交叉淡入时新旧两层 `PhotoRenderer` 会同时用它，状态会互相踩。
 * 需要「每次切换重新决定」的东西请用 [PhotoTransition.onSwapStart]。
 *
 * ## 为什么要有「枚举 ↔ 注册表」门禁（G4）
 *
 * `PhotoTransitionId` 有 76 项（规划），但代码里只实现了一部分。
 * 若某个 id 被 `PhotoTransitionPicker` 抽中、`get()` 却返回 `null`，
 * 结果是**画面卡在上一张不动**（静默故障，最难查的一类）。
 * ⇒ 池必须由 [available] 构造，门禁断言「当前分期应实现的每一项都在 [available] 里」。
 */
object PhotoTransitionRegistry {

    /**
     * P0 的 15 种（§14.3 参数表）。
     *
     * ⚠️ 新增实现时**必须**同时加进这里，否则抽不到（G4 会拦住「加了枚举没加注册」）。
     */
    private val impls: Map<PhotoTransitionId, PhotoTransition> = mapOf(
        // A 淡化
        PhotoTransitionId.CROSSFADE to CrossfadeTransition(),
        PhotoTransitionId.FADE_BLACK to FadeBlackTransition(),

        // B 滑动（dirX/dirY = 新图入场方向）
        PhotoTransitionId.SLIDE_LEFT to SlideTransition(PhotoTransitionId.SLIDE_LEFT, 1f, 0f),
        PhotoTransitionId.SLIDE_RIGHT to SlideTransition(PhotoTransitionId.SLIDE_RIGHT, -1f, 0f),
        PhotoTransitionId.SLIDE_UP to SlideTransition(PhotoTransitionId.SLIDE_UP, 0f, 1f),
        PhotoTransitionId.SLIDE_DOWN to SlideTransition(PhotoTransitionId.SLIDE_DOWN, 0f, -1f),

        // C 缩放（aFrom, aTo, bFrom, bTo）
        PhotoTransitionId.ZOOM_IN to ZoomTransition(PhotoTransitionId.ZOOM_IN, 1.00f, 1.15f, 0.85f, 1.00f),
        PhotoTransitionId.ZOOM_OUT to ZoomTransition(PhotoTransitionId.ZOOM_OUT, 1.00f, 0.85f, 1.15f, 1.00f),

        // D 遮罩形状
        PhotoTransitionId.IRIS_CIRCLE to IrisCircleTransition(),
        PhotoTransitionId.WIPE_LINEAR to WipeLinearTransition(),

        // E 条纹分块
        PhotoTransitionId.BLINDS_H to BlindsTransition(PhotoTransitionId.BLINDS_H, horizontal = true),
        PhotoTransitionId.BLINDS_V to BlindsTransition(PhotoTransitionId.BLINDS_V, horizontal = false),

        // F 溶解
        PhotoTransitionId.NOISE_DISSOLVE to NoiseDissolveTransition(),

        // H 色彩光效
        PhotoTransitionId.LIGHT_SWEEP to LightSweepTransition(),
        PhotoTransitionId.SPECTRUM_WIPE to SpectrumWipeTransition(),
    )

    /** 取实现；未实现的 id 返回 `null`（⚠️ 调用方必须能处理，抽池请用 [available]） */
    fun get(id: PhotoTransitionId): PhotoTransition? = impls[id]

    /** 当前**真有实现**的效果集合 —— 随机池只能由它构造（§5.9「池跟着分期走」） */
    fun available(): Set<PhotoTransitionId> = impls.keys

    /** 已实现数量（P0 = 15；观察 / 单测用） */
    val implementedCount: Int get() = impls.size
}
