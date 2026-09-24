package com.nasmusic.tv.visualizer.photo

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer
import com.nasmusic.tv.visualizer.photo.transitions.applyHoldMotion

/**
 * 照片墙渲染器（§14.2.4）
 *
 * ## 它只做四件事
 *
 * 1. 从 [RenderContext] 读本帧的照片 / 转场 / 进度（`photo*` 字段，由 `PhotoWallController` 写入）
 * 2. **在 `prepare` 之前**把几何算好（尺寸变化时重算）
 * 3. 切转场 / 切画质 / **尺寸变化**时重新 `prepare`（这是唯一允许「看起来像分配」的时刻）
 * 4. 把这一帧交给 [PhotoTransition.render]
 *
 * 所有「什么时候换照片 / 用哪个转场 / 该不该换」都在 `PhotoWallController` 里，
 * 本类**没有自己的时钟**、也不碰 `PhotoBuffer` —— 保持「渲染器只管画」的既有约定。
 *
 * ## ⛔ 三个必须写对的地方
 *
 * | # | 约束 | 写错的后果 |
 * |---|---|---|
 * | 1 | **`geom.update(...)` 必须在 `prepare(...)` 之前** | `NoiseDissolveTransition.prepare` 用 `geom.canvasW/canvasH` 算 `layerBounds`；顺序反了会拿到 `0×0` ⇒ `render` 里 `layerBounds.width <= 0` 直接 return，而 `prepare` 之后不再被调用 ⇒ **噪声溶解永远不显示** |
 * | 2 | **画布尺寸变化要重新 `prepare`** | 转屏 / 换分辨率后 `layerBounds` 还是旧尺寸 ⇒ 溶解只在旧区域生效 |
 * | 3 | **`b ?: a` 的单图退化** | `photoB == null`（只有一张照片 / 下一张未就绪）时若直接 `return`，画面会在切图瞬间**闪一下黑**；把 `a` 当 `b` 传则退化为「同图自转场」，视觉上只是轻微运动 |
 * | 4 | **`geom.update(...)` 必须把 B 的尺寸也传进去** | 只传 A 的尺寸时 `bW`/`bH` 落到默认值（= A 的尺寸）⇒ B 的 `srcB`/`dstB` 按 **A 的宽高比**算：竖版新图被按横版裁切（或反之 `srcB` 越出 B 的位图边界、留下一块没画到的黑边）。症状是「竖版图片铺不满屏幕」「入场动画走完了照片还没归位」——见 §10.182 |
 *
 * ## 零分配
 *
 * 稳态每帧只有 `geom.update(...)`（原地写 `Float` 字段）+ `render(...)`（各转场自己保证零分配）。
 * `ctx.photoB ?: a` 是引用比较，不构造对象。
 *
 * ## `onSwapStart()` 由谁调用
 *
 * ⛔ **不是本类**。`WIPE_LINEAR` 的 8 方向、`SHAPE_RANDOM` 的形状需要「每次切换重新随机」，
 * 而「一次切换开始」这个事件只有控制器知道（它才知道该抽新转场了）。
 * ⇒ `PhotoWallController` 在抽定新转场后调用 `PhotoTransitionRegistry.get(id)?.onSwapStart()`。
 *
 * ⚠️ 注册表里是**全进程单例**，所以「同时存在两个 `PhotoRenderer`」是不允许的
 * —— 好在主题级交叉淡入时新旧两层主题不同，只可能有一层是 `PHOTO_WALL`。
 */
class PhotoRenderer : VisualizerRenderer {

    override val theme: VisualizerTheme = VisualizerTheme.PHOTO_WALL

    /** 复用的几何（零分配） */
    private val geom = PhotoGeometry()

    /** 当前绑定的转场实现（来自注册表，**不是**本类 own 的对象 ⇒ `onExit` 不能 recycle 它） */
    private var bound: PhotoTransition? = null

    private var lastTransitionId: PhotoTransitionId? = null
    private var lastQuality: com.nasmusic.tv.data.model.VisualQuality? = null

    /** 上次 `prepare` 时的画布尺寸 —— 尺寸变化要重新 `prepare`（约束 2） */
    private var lastCanvasW = 0f
    private var lastCanvasH = 0f

    // ── 停留期运动（§5.6，Ken Burns + 音频呼吸）──
    //
    // ⛔ **运动进度绑定「图」而不是「时钟周期」**：photoHoldT 在新周期被时钟清零，
    // 但 A（旧图）在转场中仍以**上一周期推完的位置**显示 —— 不冻结的话，
    // 换槽瞬间 A 会从 1.08 跳回 1.0（8% 的画面突缩，CROSSFADE 垫底时尤其明显）。
    // ⇒ 检测「progress 回落」（新周期开始）时把上一帧运动量冻结给 A。
    private var frozenAMotion = 0f

    /** 上一帧 B 的运动进度（换周期时变成 A 的冻结值） */
    private var lastMotion = 0f

    /** 上一帧的 progress（检测回落 = 新周期开始；⛔ 不能比 id —— 固定转场模式下 id 不变） */
    private var lastProgress = 0f

    override fun onEnter(ctx: RenderContext) {
        // 此刻还不知道照片尺寸（要等第一帧 `photoA`），所以几何留到 `draw` 里算。
        // 只把「上一次的残留」清掉，避免复用同一个实例时带着旧尺寸。
        lastCanvasW = 0f
        lastCanvasH = 0f
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val a = ctx.photoA ?: return          // 无照片 ⇒ 直接返回（底层已是暗底）
        val id = ctx.photoTransition ?: return // 未在切换中（理论上不该发生，防御性返回）
        val b: ImageBitmap = ctx.photoB ?: a   // 单图退化（约束 3）

        // ⛔ 顺序：先算几何，再 prepare（约束 1）
        // ⛔ 必须传 **B 自己的尺寸**（约束 4）—— `bW`/`bH` 的默认值就是 A 的尺寸，
        //    漏传等于「按上一张的宽高比裁下一张」：
        //    · CROP：`srcB` 的比例按 A 算 ⇒ 新图被裁成 A 的形状（竖版图铺不满屏幕）
        //    · A 比 B「高」时 `srcB` 还会越出 B 的位图边界 ⇒ 有一块永远画不到（黑边）
        //    · HOLD 期 `photoB` 仍是刚入场的那张、`p` 恒为 1 ⇒ 整个停留期都在按错误几何绘制，
        //      直到下一次切换把它换成 `a` 才「跳」回正确形状（用户读作「入场动画没走完」）
        geom.update(ctx, ctx.photoScaleMode, a.width, a.height, b.width, b.height)
        geom.spectrum = frame.spectrum         // 只存引用，不复制

        if (id != lastTransitionId || ctx.quality != lastQuality || ctx.canvasSize.width != lastCanvasW ||
            ctx.canvasSize.height != lastCanvasH
        ) {
            bound?.release()
            bound = PhotoTransitionRegistry.get(id)
            // `MaskCache` 是全进程单例（object），不是本类 own 的对象 ⇒ 不 release 它
            bound?.prepare(geom, ctx.quality, MaskCache)
            lastTransitionId = id
            lastQuality = ctx.quality
            lastCanvasW = ctx.canvasSize.width
            lastCanvasH = ctx.canvasSize.height
        }

        val t = bound ?: return

        // ── 停留期运动（§5.6）：作用在几何上，转场自身无感知 ──
        // 检测新周期：progress 回落（HOLD 的 1 → 新 ENTER 的 0）
        if (ctx.photoProgress < lastProgress) frozenAMotion = lastMotion
        lastProgress = ctx.photoProgress
        val motion = ctx.photoHoldT
        lastMotion = motion
        val boost = ctx.photoAudioBoost
        // ⛔ 平移只在 CROP 下（FIT 平移会露出黑边，见 applyHoldMotion 的 KDoc）
        val pan = ctx.photoScaleMode == PhotoScaleMode.CROP
        if (motion > 0f || boost > 0f || frozenAMotion > 0f) {
            if (b !== a) applyHoldMotion(geom.dstA, frozenAMotion, boost, geom.canvasW, geom.canvasH, pan)
            applyHoldMotion(geom.dstB, motion, boost, geom.canvasW, geom.canvasH, pan)
        }

        // ⛔ 必须用 `run` 把 `t` 放成**隐式**接收者，不能写成 `t.render(...)`：
        //   `PhotoTransition.render` 的声明是 `fun DrawScope.render(...)` —— 一个
        //   **成员扩展函数**（dispatch receiver = PhotoTransition，extension receiver = DrawScope）。
        //   写成 `t.render(...)` 时，Kotlin 会把显式接收者 `t` 当作**扩展接收者**去匹配
        //   `DrawScope` ⇒ 类型不匹配 ⇒ `Unresolved reference 'render'`（本次实测踩坑）。
        //   放进 `run { }` 后 `t` 变成隐式 dispatch receiver，外层的 `DrawScope` 才被当作扩展接收者。
        //   项目既有同款写法：`VisualizerStage.kt:265` 的 `with(cur) { draw(f, renderCtx) }`。
        //   ⚠️ `run` 是 inline ⇒ 零分配，不违反「每帧零分配」红线。
        //
        // ⛔ 外层 `clipRect` 是 Ken Burns 的前置条件：推近 8% 后 dst 超出画布，
        // 而 Compose 绘制**默认不裁剪**（`ZoomTransition` 自己加 clipRect 正是这个原因）；
        // 统一在这里兜底，所有转场不必各自关心。
        clipRect(0f, 0f, geom.canvasW, geom.canvasH) {
            t.run { render(a, b, ctx.photoProgress, geom) }
        }
    }

    override fun onExit() {
        // ⚠️ 只释放「本实例自己占的」——注册表里的实现是共享单例，
        // `release()` 只清它自己的缓冲引用；`MaskCache` 是 object，绝不能在这里回收
        bound?.release()
        bound = null
        lastTransitionId = null
        lastQuality = null
        lastCanvasW = 0f
        lastCanvasH = 0f
        frozenAMotion = 0f
        lastMotion = 0f
        lastProgress = 0f
    }
}
