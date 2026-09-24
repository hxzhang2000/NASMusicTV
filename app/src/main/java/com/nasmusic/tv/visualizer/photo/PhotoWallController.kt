package com.nasmusic.tv.visualizer.photo

import android.os.Build
import com.nasmusic.tv.backend.photo.AggregateResult
import com.nasmusic.tv.backend.photo.PhotoRef
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.backend.photo.PhotoSource
import com.nasmusic.tv.backend.photo.PhotoSourceAggregator
import com.nasmusic.tv.backend.photo.PhotoSourceKind
import com.nasmusic.tv.backend.photo.PhotoSourceStatus
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 照片墙编排层（§14.2.5）—— 「开关 → 扫描 → 缓冲 → 时钟」四件事的唯一协调者。
 *
 * ## 职责边界
 *
 * | 层 | 负责 | 不负责 |
 * |---|---|---|
 * | `PhotoSourceAggregator` | 扫哪些来源、去重、排序 | 什么时候扫 |
 * | [PhotoBuffer] | 解码、降采样、LRU | 该解码哪一张 |
 * | [PhotoTransitionClock] | 相位推进、缓动、模型 | 该用哪个转场 |
 * | [PhotoTransitionPicker] | 从池里抽一个不重复的 | 池里该有谁 |
 * | **本类** | **把上面四者按帧串起来** | 画像素（那是 `PhotoRenderer`） |
 *
 * ## 线程模型（⚠️ 读这段能省掉一次「为什么没照片」排查）
 *
 * `onFrame` / `applyTo` 都跑在**主线程**（`withFrameNanos` 的帧回调与 `Canvas` 的 draw lambda
 * 都是主线程），所以本类**没有任何同步原语**；解码结果由 [PhotoBuffer] 经主线程 Handler 回写。
 * 扫描是 `suspend`（内部各自 `withContext(Dispatchers.IO)`），只在主线程读写状态。
 *
 * ## 帧内顺序（决定「第一张照片何时出现」）
 *
 * ```
 * onFrame(frame)   ① 低频重建缓冲（尺寸 / 画质变了）② 推进时钟 ③ 到点换图 / 预取
 * applyTo(ctx)     ④ 把 7 个字段写进 RenderContext（零分配）
 * ```
 *
 * ①在 ④ 里只**暂存**画布尺寸（两次 `Int` 写），真正的缓冲重建放到下一帧的 `onFrame` ——
 * 避免在**绘制阶段**创建线程池 / Handler。
 *
 * ## ⛔ 三条必须写对的实现约束
 *
 * | # | 约束 | 写错的后果 |
 * |---|---|---|
 * | 1 | **只换「已解码好」的图**（`peek != null` 才 `start`） | 转场跑到一半新图才解码出来 ⇒ 画面在 `p` 中途「啪」地换脸 |
 * | 2 | **`applyTo` 里只做 7 次赋值**（`let` / `?:` 都是 inline） | 绘制路径上分配 = 每帧产生垃圾 ⇒ GC 抖动掉帧 |
 * | 3 | **`start()` 绝不在窗口进行中重复调用** | `elapsedMs` 归零 = 从 0 重启，画面跳变（§5.8） |
 *
 * 约束 1 的落地：`tryStartSwap` 在 `peek` 未命中时**不启动时钟**，只 `request` 后返回，
 * 下一帧再试（`clock.finished` 会保持 `true`，因为没人调 `start`）⇒ 画面停在上一张，
 * 等新图就绪才开始转场。
 *
 * ## 与「转场时长」设置项的关系（⚠️ 实现期明确的偏差，见 §15.3 阶段 10）
 *
 * §14.2.3 规定 `PhotoTransitionClock` **在拿到转场实例之前**就要读 `baseDurationMs`
 * （每效果的固有节奏，§14.3 表：0.5s ~ 1.2s）；§7.3 又有用户可调的 `photoWallTransitionMs`
 * （300–2000ms，默认 700）。两者是**基准 × 缩放**的关系：
 *
 * ```
 * enterMs = id.baseDurationMs × (photoWallTransitionMs / NEUTRAL_TRANSITION_MS)
 * ```
 *
 * `NEUTRAL_TRANSITION_MS = 700` 即设置项默认值 ⇒ 默认档 = 1.0 倍，各效果走 §14.3 的固有节奏。
 * ⚠️ 因此 §14.6 电视第 2 条写的「转场 0.7s」在默认档实际观测为 **0.5–1.2s**（取决于抽到哪个效果）；
 * 把设置调到 300 / 2000 则是 0.43× / 2.86× 的整体快慢。
 *
 * ## 构造签名与 §14.2.5 的差异
 *
 * 原设计写 `(appContext, prefs, sources)`。实现期去掉前两个：
 * - `appContext` 无用 —— 三个 `PhotoSource` 由 ViewModel 构造好注入，本类不碰 `ContentResolver`；
 * - `prefs` 无用 —— 设置的**唯一入口**是 [onSettingsChanged]（由 `appsettings` 流推入），
 *   本类既不读盘也不落盘（计数 / 状态是 `StateFlow`，属运行时事实，见 `PhotoWallRuntimeState`）。
 * ⇒ 保留它们只会得到两个未使用字段。
 *
 * @param sources 已构造好的来源（**没有**的键 = 该平台没有这个来源，如电视无图库）。
 *   来源的构造放在 ViewModel：它才拿得到 `Context` / `BackendRegistry` / `StorageMonitor`。
 * @param random 共享随机源（§6.5 / §5.9：洗牌与抽转场**共用**一个序列，不要各自 new）
 * @param externalScope 扫描协程作用域；`null`（默认）= 自建，由 [close] 取消。
 *   注入时（单测 / ViewModel 托管）本类**不**取消它。
 * @param bufferFactory 解码缓冲工厂。⚠️ 只为单测存在：注入一个「同步执行 + 假解码器」的
 *   `PhotoBuffer`（它的 `decoderExecutor` / `mainHandler` / `decoder` 三个参数本就是为此留的）
 *   才能在不真解码像素的前提下验证「换图链路 / 池空 / 拔盘」。
 */
class PhotoWallController(
    private val sources: Map<PhotoSourceKind, PhotoSource>,
    private val random: VisualizerRandom = VisualizerRandom(),
    private val externalScope: CoroutineScope? = null,
    /**
     * 「仅显示含人像」的 key 集合提供者（阶段 11）。
     *
     * ⛔ 与 `FaceScanManager` 的接缝设计同一条理由：让「按 hasFace 过滤」可以被
     * 纯 JVM 单测钉住（注入一个假集合），不必拉起 Room / ONNX。
     * `null`（默认）= 本平台没有人脸检测，`photoWallFacesOnly` 形同虚设。
     */
    private val faceKeysProvider: (suspend () -> Set<String>)? = null,
    private val bufferFactory: (targetWidth: Int, targetHeight: Int, allowRgb565: Boolean) -> PhotoBuffer? =
        { w, h, rgb ->
            PhotoBuffer(
                sourceProvider = { kind -> sources[kind] },
                targetWidth = w,
                targetHeight = h,
                allowRgb565 = rgb,
            )
        },
) {

    // ────────────────────────── 内部件 ──────────────────────────

    private val aggregator = PhotoSourceAggregator(sources = sources, random = random)
    private val clock = PhotoTransitionClock()
    private val picker = PhotoTransitionPicker(random)
    private val pool = PhotoWallPool()

    private val ownScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scope: CoroutineScope get() = externalScope ?: ownScope

    // ────────────────────────── 运行时状态 ──────────────────────────

    private val _perSourceCount = MutableStateFlow<Map<PhotoSourceKind, Int>>(emptyMap())
    /** 去重后各来源实际贡献的条数（设置页「照片数」行，§7.2） */
    val perSourceCount: StateFlow<Map<PhotoSourceKind, Int>> = _perSourceCount.asStateFlow()

    private val _mergedCount = MutableStateFlow(0)
    /**
     * 合并去重后的总数（设置页「合并后（去重）」行）。
     *
     * ⛔ 与 `perSourceCount` 的**口径必须一致**（都不过人脸过滤）—— 否则三个分来源相加
     * 不等于「合并后」，用户会以为去重把照片吃掉了（v2.37.0 上机复验的真实困惑：
     * 只开图库 6938 张、开着「仅显示含人像」⇒ 合并行显示 2657，看起来像去重丢了 4281 张）。
     */
    val mergedCount: StateFlow<Int> = _mergedCount.asStateFlow()

    private val _displayCount = MutableStateFlow(0)
    /**
     * 人脸过滤之后**实际会展示**的张数（设置页「仅含人像（实际展示）」行）。
     *
     * ⚠️ 没开「仅显示含人像」/ 人脸扫描未完成时等于 [mergedCount]（`filterByFaces` 原样返回）。
     */
    val displayCount: StateFlow<Int> = _displayCount.asStateFlow()

    /**
     * 当前生效的照片列表（**过滤之后**的那份）。
     *
     * ⚠️ 阶段 11 新增：`FaceScanManager` 的「开始扫描」要从这里拿待扫清单
     * （设置页不在照片墙上时，控制器也可能已有扫描结果）。
     * ⚠️ 它是**快照**语义：`FaceScanManager.start()` 里会复制一份，之后池变了不影响本轮。
     */
    private val _photos = MutableStateFlow<List<PhotoRef>>(emptyList())
    val photos: StateFlow<List<PhotoRef>> = _photos.asStateFlow()

    private val _statuses = MutableStateFlow<Map<PhotoSourceKind, PhotoSourceStatus>>(emptyMap())
    /** 各来源状态（设置页「为什么不可用」） */
    val statuses: StateFlow<Map<PhotoSourceKind, PhotoSourceStatus>> = _statuses.asStateFlow()

    // ────────────────────────── 可变字段（全部主线程） ──────────────────────────

    private var settings: AppSettings = AppSettings()
    private var quality: VisualQuality = VisualQuality.Default
    private var sdkInt: Int = Build.VERSION.SDK_INT

    /** 当前是否在照片墙效果上（`false` = 帧循环空转，不推进时钟、不换图） */
    private var active = false

    /** 「待扫描」标志：开关 / 目录 / 均衡方式变了就置起，进入效果或正在效果上时立刻扫 */
    private var needScan = true
    private var scanJob: Job? = null

    private var buffer: PhotoBuffer? = null
    private var bufferW = 0
    private var bufferH = 0
    private var bufferRgb565 = false

    /** 由 [applyTo] 暂存的画布尺寸（`Int` 写，零分配），[onFrame] 里据此重建缓冲 */
    private var canvasW = 0
    private var canvasH = 0

    /** 已展示的图（转场里的 `a`） */
    private var currentRef: PhotoRef? = null

    /** 正在入场的图（转场里的 `b`） */
    private var incomingRef: PhotoRef? = null

    /** 等「下一张」解码的连续帧数（超上限则判定解不出来 ⇒ 跳过这张） */
    private var swapWaitFrames = 0

    /** 本帧的音频反应叠加（§5.6）；`0` = 关 */
    private var audioBoost = 0f

    /** 上一次已生效的「会导致池变化」的设置，用于判断要不要重扫 */
    private var lastEnabled: Set<PhotoSourceKind> = emptySet()
    private var lastDirUri: String? = null
    private var lastCommonDirsOnly: Boolean? = null
    private var lastBalance: Boolean? = null

    /** 阶段 11：这两个变化会改变过滤后的池（见 [onSettingsChanged]） */
    private var lastFacesOnly: Boolean? = null
    private var lastFaceScanDone: Boolean? = null

    // ══════════════════════════════════════════════════════════════════════
    //  ① 帧循环（主线程、与 draw 同帧）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 每帧推进一次（由 `VisualizerStage` 的 `withFrameNanos` 回调调用）。
     *
     * 顺序不能换：**先重建缓冲**（尺寸 / 画质可能刚变）→ **再推进时钟** → **最后换图**。
     */
    fun onFrame(frame: AudioFrame) {
        reconcileBuffer()
        if (!active) return

        audioBoost = computeAudioBoost(frame)
        clock.advance(frame.timeMs)

        when {
            // 尚未起第一张（刚进入 / 刚扫完 / 缓冲刚重建）⇒ 立即尝试
            clock.transitionId == null -> tryStartSwap(frame.timeMs)
            // HOLD 走完 ⇒ 换下一张。⚠️ 失败时 `finished` 保持 true，下一帧自动重试
            clock.finished -> tryStartSwap(frame.timeMs)
        }
    }

    /**
     * 把本帧要画的东西写进 [RenderContext]（§14.2.4 的 7 个字段）。
     *
     * ⛔ **零分配**：`?.let` / `?:` / `coerceIn` 全是 inline，`IntOffset` / `Size` 是 value class。
     * 唯一的「看起来像分配」是 `ctx.canvasSize.width.toInt()` —— 基本类型运算，不构造对象。
     *
     * ⚠️ 无论是否 [active] 都写：切走效果时 `PhotoRenderer` 可能仍在交叉淡出层上读这些字段，
     * 写 `null` 会让它直接 `return`（画面瞬黑）。同时 `peek` 会挡掉已被 `recycle` 的位图，
     * 所以「拔盘后画一张已回收的图」这条崩溃路径也在这里被堵住。
     */
    fun applyTo(ctx: RenderContext) {
        // 只暂存尺寸；真正的重建在下一帧的 `onFrame` 里做（不在绘制阶段建线程池）
        canvasW = ctx.canvasSize.width.toInt()
        canvasH = ctx.canvasSize.height.toInt()

        ctx.photoA = currentRef?.let { buffer?.peek(it) }
        ctx.photoB = incomingRef?.let { buffer?.peek(it) }
        ctx.photoProgress = clock.eased
        ctx.photoTransition = clock.transitionId
        // photoHoldT 语义 = 「停留期运动进度」（§5.6）：Ken Burns 关闭时恒 0，
        // 渲染器不需要知道开关状态（同 photoAudioBoost 的「关时恒 0」约定）
        ctx.photoHoldT = if (settings.photoWallKenBurns) clock.holdT else 0f
        ctx.photoScaleMode = settings.photoWallScaleMode
        ctx.photoAudioBoost = audioBoost
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ② 生命周期 / 设置变更
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 设置 / 画质变化。
     *
     * ⚠️ 每次 `appSettings` 发射都会调到这里，所以**必须自带「要不要重扫」的判断**
     * （只有来源开关 / 目录 / 均衡方式变了才重扫）—— 否则每改一个开关都会重新遍历 U 盘。
     *
     * ⛔ 转场时长 / 停留时长 / 随机与否**只影响下一次切换**：正在跑的这次切换不打断（§5.8）。
     */
    fun onSettingsChanged(settings: AppSettings, quality: VisualQuality, sdkInt: Int) {
        this.settings = settings
        this.quality = quality
        this.sdkInt = sdkInt

        val enabled = enabledKinds(settings)
        val changed = enabled != lastEnabled ||
            settings.photoWallDirUri != lastDirUri ||
            settings.photoWallCommonDirsOnly != lastCommonDirsOnly ||
            settings.photoWallSourceBalance != lastBalance ||
            // 阶段 11：这两个都可能改变「过滤后」的池（开关翻转 / 扫描完成）
            settings.photoWallFacesOnly != lastFacesOnly ||
            settings.photoWallFaceScanDone != lastFaceScanDone

        lastEnabled = enabled
        lastDirUri = settings.photoWallDirUri
        lastCommonDirsOnly = settings.photoWallCommonDirsOnly
        lastBalance = settings.photoWallSourceBalance
        lastFacesOnly = settings.photoWallFacesOnly
        lastFaceScanDone = settings.photoWallFaceScanDone

        if (!changed) return
        needScan = true
        // 正在照片墙上看的时候立刻重扫；不在看的时候留到 `onThemeEntered`（懒扫描）
        if (active) startScan()
    }

    /**
     * 切到 `PHOTO_WALL`。
     *
     * ⚠️ **不重置时钟**：`currentRef` / `incomingRef` 与 `PhotoBuffer` 的缓存都保留 ⇒
     * 切走再切回来是**瞬时**的（不需要重新解码）。只有 `close()` 才真正释放。
     */
    fun onThemeEntered() {
        active = true
        if (needScan) startScan()
    }

    /**
     * 离开 `PHOTO_WALL`：停时钟推进、停预取，**不释放缓存**（便于切回）。
     *
     * ⛔ **不 `clock.reset()`**：切走的那一帧 `PhotoRenderer` 可能还在交叉淡出层上绘制，
     * 把 `transitionId` 清成 `null` 会让它读到 `null` 直接 `return`（画面瞬黑）。
     */
    fun onThemeExited() {
        active = false
        swapWaitFrames = 0
    }

    /**
     * 重新扫描（设置页「重新扫描」按钮，§7.2）。
     *
     * 与 [onSettingsChanged] 触发的重扫的区别：**这个会取消正在跑的扫描并立刻重来**
     * （用户显式点了按钮，不该被「上一次还没扫完」吞掉）。
     */
    fun rescan() {
        scanJob?.cancel()
        scanJob = null
        needScan = true
        startScan()
    }

    /**
     * 外接存储挂载状态变化（插 / 拔 U 盘、SD 卡）。
     *
     * ⛔ §14.6 电视第 5 条要求「拔盘 → 不崩；**缓存清空**；切回其他效果正常」。
     * 仅靠「下次解码失败」是不够的：缓存里那张图仍在，会一直画一张**已经不存在的盘**上的照片。
     * ⇒ 必须主动 `invalidateSource(EXTERNAL)`（同时自增世代号，丢弃在途解码结果）+ 重扫。
     *
     * ⚠️ 这个方法**不在** §14.2.5 的 API 列表里，是实现期补的（见 §15.3 阶段 10 偏差）——
     * 没有它，`StorageMonitor` 的广播就没人消费，那条验收项无法满足。
     */
    fun onExternalStorageChanged() {
        buffer?.invalidateSource(PhotoSourceKind.EXTERNAL)
        scanJob?.cancel()
        scanJob = null
        needScan = true
        if (active) startScan()
    }

    /** 页面销毁：停线程、释放位图。幂等。 */
    fun close() {
        active = false
        scanJob?.cancel()
        scanJob = null
        ownScope.cancel()
        clock.reset()
        resetSlots()
        pool.clear()
        buffer?.close()
        buffer = null
        bufferW = 0
        bufferH = 0
        bufferRgb565 = false
        canvasW = 0
        canvasH = 0
        _perSourceCount.value = emptyMap()
        _mergedCount.value = 0
        _displayCount.value = 0
        _photos.value = emptyList()
        _statuses.value = emptyMap()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ③ 扫描
    // ══════════════════════════════════════════════════════════════════════

    private fun startScan() {
        if (scanJob?.isActive == true) return
        needScan = false
        scanJob = scope.launch {
            val enabled = enabledKinds(settings)
            if (enabled.isEmpty()) {
                // 「完全无照片 I/O」：三来源全关 ⇒ 连 status() 都不调（§6.8）
                pool.clear()
                resetSlots()
                _photos.value = emptyList()
                _perSourceCount.value = emptyMap()
                _mergedCount.value = 0
                _displayCount.value = 0
                _statuses.value = emptyMap()
                return@launch
            }
            val result: AggregateResult = aggregator.collect(
                enabled = enabled,
                balance = settings.photoWallSourceBalance,
            )
            // ⛔ 过滤（阶段 11）必须在「建池」**之前**：池里放的必须是用户实际会看到的那批。
            val filtered = filterByFaces(result.photos)
            _perSourceCount.value = result.perSource
            _photos.value = filtered
            // ⛔ 两个计数**口径不同**，刻意分开（v2.37.0 上机复验后调整，见 §10.183）：
            //   「合并后（去重）」= 去重后、**人脸过滤前** ⇒ 与分来源行同口径（三者相加 == 它）
            //   「实际展示」     = 人脸过滤后        ⇒ 用户真正会看到的张数
            //   合并成一个数会出现「分来源 6938 / 合并 2657」的错觉：看起来像去重丢了 4281 张。
            _mergedCount.value = result.photos.size
            _displayCount.value = filtered.size
            _statuses.value = result.statuses
            pool.reset(filtered)
            picker.reset()
            resetSlots()
            if (result.photos.isEmpty()) {
                AppLog.w(TAG, "scan produced an empty pool: ${result.statuses}")
            }
        }
    }

    private fun enabledKinds(s: AppSettings): Set<PhotoSourceKind> {
        val out = LinkedHashSet<PhotoSourceKind>(PhotoSourceKind.entries.size)
        if (s.photoWallGalleryEnabled) out.add(PhotoSourceKind.GALLERY)
        if (s.photoWallExternalEnabled) out.add(PhotoSourceKind.EXTERNAL)
        if (s.photoWallJellyfinEnabled) out.add(PhotoSourceKind.JELLYFIN)
        return out
    }

    /**
     * 「仅显示含人像」（阶段 11，T11.3）。
     *
     * ⛔ **拔盘不清表**（§10.3 / R9）：`FaceResultStore` 里的条目在盘重插后仍有效，
     * 所以这里**只做交集**（`pool ∩ faceKeys`），盘上已不存在的照片被自然剔除，
     * 不需要也不应该删表。
     *
     * ⛔ **只有「扫描已完成」才过滤**（`photoWallFaceScanDone`）：
     * 开着开关但一张都没扫过 ⇒ 过滤结果是空 ⇒ 照片墙整面黑。
     * 在那之前按「不过滤」处理（设置页的进度行会告诉用户扫到哪了）。
     */
    private suspend fun filterByFaces(photos: List<PhotoRef>): List<PhotoRef> {
        if (!settings.photoWallFacesOnly) return photos
        if (!settings.photoWallFaceScanDone) return photos
        val provider = faceKeysProvider ?: return photos
        val keys = provider()
        if (keys.isEmpty()) return emptyList()
        return photos.filter { it.id in keys }
    }

    private fun resetSlots() {
        currentRef = null
        incomingRef = null
        swapWaitFrames = 0
        clock.reset()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ④ 换图链路（T10.2 的核心）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 尝试开始一次切换。**返回 `false` 时什么都没动**（调用方下一帧再试）。
     *
     * 步骤：取下一张 → 必须**已解码**（约束 1）→ 抽转场 → 换槽 → `start()` → 预取再下一张。
     */
    private fun tryStartSwap(nowMs: Long): Boolean {
        val next = nextRef()
        if (next == null) {
            // 池空（还没扫完 / 三来源都不可用 / 全部照片都读不出来）：保持现状，不启动时钟
            return false
        }

        if (buffer?.peek(next) == null) {
            buffer?.request(next)
            if (swapWaitFrames++ < SWAP_WAIT_MAX_FRAMES) return false
            // 等太久 ⇒ 这张解不出来（拔盘 / 坏文件 / 网络失败）—— 跳过它，否则整墙卡死
            AppLog.w(TAG, "skip undecodable photo: ${next.displayName}")
            swapWaitFrames = 0
            pool.advance()
            return false
        }
        swapWaitFrames = 0
        pool.advance()

        val id = pickTransition()
        // ⛔ 「一次切换开始」这个事件只有本类知道 ⇒ WIPE_LINEAR 的 8 方向 / 形状池的重新随机
        //    必须在这里触发（`PhotoRenderer` 只负责画，见它的 KDoc）
        PhotoTransitionRegistry.get(id)?.onSwapStart()

        val first = currentRef == null
        if (first) {
            // ⛔ 第一张**没有旧图**可转场 ⇒ 它必须是 `a` 而不是 `b`：
            //   `PhotoRenderer` 读 `ctx.photoA`，`photoA == null` 时直接 `return`（什么都不画）。
            //   写成「currentRef 留空、incomingRef = next」会得到一面永远黑着的照片墙。
            currentRef = next
            incomingRef = null
        } else {
            currentRef = incomingRef ?: currentRef
            incomingRef = next
        }

        val holdMs = settings.photoWallHoldMs.coerceAtLeast(0)
        if (first) {
            // 第一张没有「上一张」可转场 ⇒ 硬切起步，只为把 HOLD 计时器拉起来
            clock.start(nowMs, id, enterMs = 0, holdMs = holdMs, sequential = false)
        } else {
            clock.start(
                nowMs, id,
                enterMs = enterMsOf(id),
                holdMs = holdMs,
                sequential = id.requiresSequential,
            )
        }

        // 预取「再下一张」：本帧活跃 2 张（a / b）+ 预取 1 张 = `PhotoBuffer.maxCached`
        buffer?.prefetch(pool.peek())
        return true
    }

    /** 池里的下一张；走到末尾先重洗一轮（§6.7「Fisher-Yates + 游标」的无重复遍历） */
    private fun nextRef(): PhotoRef? {
        if (pool.isEmpty) return null
        if (pool.peek() == null) pool.beginNewRound(random)
        return pool.peek()
    }

    /**
     * 抽本次切换的转场。
     *
     * - 固定档：用户指定的那个（走 [PhotoTransitionId.effective] 做老平台降级）
     * - 随机档：从 `registry.available()` 构造的池里抽（§5.9 三条规则都在 `randomPool` 里）
     */
    private fun pickTransition(): PhotoTransitionId {
        val s = settings
        if (!s.photoWallRandomTransition) {
            return PhotoTransitionId.effective(s.photoWallFixedTransition, sdkInt)
        }
        val candidates = PhotoTransitionId.randomPool(PhotoTransitionRegistry.available(), sdkInt)
        val idx = picker.pick(candidates)
        if (idx < 0) return PhotoTransitionId.effective(PhotoTransitionId.Default, sdkInt)
        return PhotoTransitionId.entries[candidates[idx]]
    }

    /**
     * 转场窗口时长（ms）。
     *
     * `baseDurationMs`（§14.3 每效果固有节奏）× 用户缩放（§7.3 的 `photoWallTransitionMs`）。
     * 见类 KDoc 的「与转场时长设置项的关系」。
     *
     * ⛔ [PhotoTransitionId.BEAT_CUT] 是**硬切**（§5.8：窗口为 0），不走 `baseDurationMs` ——
     * 它不在随机池里（`audioReactive`），但**备份导入**可以把 `photoWallFixedTransition`
     * 设成任意枚举值，所以这条分支必须留。
     */
    private fun enterMsOf(id: PhotoTransitionId): Int {
        if (id == PhotoTransitionId.BEAT_CUT) return 0
        val scaled = id.baseDurationMs.toFloat() *
            (settings.photoWallTransitionMs.toFloat() / NEUTRAL_TRANSITION_MS)
        return scaled.roundToInt().coerceIn(MIN_ENTER_MS, MAX_ENTER_MS)
    }

    /**
     * 音频反应叠加（§5.6，默认关）。
     *
     * ⛔ **只影响运动幅度，不影响切换时机**（§5.5：展示时长不由音乐速度决定）。
     * ⚠️ 本阶段只把值写进 `RenderContext.photoAudioBoost`；消费它的是 Ken Burns / 音频反应
     * 运动（阶段 12）。
     */
    private fun computeAudioBoost(frame: AudioFrame): Float {
        val s = settings
        if (!s.photoWallAudioReactive) return 0f
        var v = 0f
        if (s.photoWallPulseZoom) v += frame.pulse
        if (s.photoWallBreathe) v += frame.bass * BREATHE_WEIGHT
        return v.coerceIn(0f, 1f)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ⑤ 解码缓冲的重建（低频：转屏 / 切画质 / 切画面适配）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 画布尺寸 / 画质变了就重建 [PhotoBuffer]。
     *
     * ⚠️ 只在 [onFrame] 里调（**不在**绘制路径上）：`PhotoBuffer` 的构造会建 `ExecutorService`
     * 与 `Handler`，放在 `applyTo` 里等于在 draw 阶段分配。
     *
     * 解码目标（§14.2.2 / §八）：
     * - `CROP`：**方形**，边长 = `max(画布宽, 画布高)`（裁切可能用到长边）
     * - `FIT` ：画布矩形（整图都要放进来）
     * - `Tier` 降级（`VisualQuality.LOW`）：长边压到 1280，并切 `RGB_565`（内存减半）
     *
     * ⛔ `MAX_DECODE_SIDE` 是实现期加的**安全阀**（§15.3 阶段 10 偏差）：`inSampleSize` 是 2 的幂，
     * 解码结果最坏是目标的 2 倍边长（= 4 倍像素）。不钳制时 4K 画布的目标会到 3840² ⇒
     * 单张 59 MB、三张 177 MB，必然 OOM。2048 对现有屏幕（≤1080p）**不改变行为**。
     */
    private fun reconcileBuffer() {
        if (canvasW <= 0 || canvasH <= 0) return

        val longSide = maxOf(canvasW, canvasH).coerceAtMost(MAX_DECODE_SIDE)
        var w: Int
        var h: Int
        if (settings.photoWallScaleMode == PhotoScaleMode.CROP) {
            w = longSide
            h = longSide
        } else {
            w = canvasW.coerceAtMost(MAX_DECODE_SIDE)
            h = canvasH.coerceAtMost(MAX_DECODE_SIDE)
        }
        // `VisualQuality.LOW` ⇒ 低画质档：再压到 720p 量级 + RGB_565
        val rgb565 = quality == VisualQuality.LOW
        if (rgb565) {
            w = w.coerceAtMost(LOW_QUALITY_SIDE)
            h = h.coerceAtMost(LOW_QUALITY_SIDE)
        }

        if (buffer != null && w == bufferW && h == bufferH && rgb565 == bufferRgb565) return

        buffer?.close()
        buffer = bufferFactory(w, h, rgb565)
        bufferW = w
        bufferH = h
        bufferRgb565 = rgb565
        AppLog.d(TAG, "buffer rebuilt: ${w}x$h rgb565=$rgb565 (quality=$quality)")

        // 旧缓冲已释放 ⇒ 两张活跃图都没了，重新请求；并从头开始这次切换
        swapWaitFrames = 0
        currentRef?.let { buffer?.request(it) }
        incomingRef?.let { buffer?.request(it) }
    }

    private companion object {
        const val TAG = "PhotoWallController"

        /**
         * 「转场时长」设置项的中性值（= 1.0 倍）。
         *
         * 与 §7.3 的默认值 `700` **必须一致** —— 改了这里不改 `AppPreferences` 的默认值，
         * 默认档就不再是 1.0 倍（§14.3 的每效果节奏会被整体缩放）。
         */
        const val NEUTRAL_TRANSITION_MS = 700f

        /** 转场窗口下限 / 上限（挡住「备份导入一个 0 或 99999」） */
        const val MIN_ENTER_MS = 120
        const val MAX_ENTER_MS = 4_000

        /**
         * 等「下一张」解码的帧数上限（约 2 秒 @60fps）。
         *
         * 超过就判定这张解不出来并跳过 —— 没有这条，拔盘 / 坏文件会让照片墙**永久卡在**
         * 上一张（`clock.finished` 一直为 true 但永远 `start` 不了）。
         */
        const val SWAP_WAIT_MAX_FRAMES = 120

        /** 解码目标长边安全阀（见 [reconcileBuffer] 的 KDoc） */
        const val MAX_DECODE_SIDE = 2_048

        /** 低画质档的解码长边（§八「1280×720 或 RGB_565」） */
        const val LOW_QUALITY_SIDE = 1_280

        /** 呼吸（`bass`）相对脉冲（`pulse`）的权重 —— 呼吸是「微妙」的，不该和鼓点一样猛 */
        const val BREATHE_WEIGHT = 0.5f
    }
}

/**
 * 照片游标（§6.7「Fisher-Yates + 游标」的游标那一半）。
 *
 * ## 为什么单独成类（而不是并进 `PhotoSourceAggregator`）
 *
 * 聚合器负责「池里有哪些、什么顺序」——它**每次扫描都重来一遍**；
 * 游标负责「这一轮走到哪、下一轮怎么开始」——它**跨扫描存活**。
 * 混在一起会让「无重复遍历」这条逻辑没法单独验证（门禁 G14）。
 *
 * ## 「无重复遍历」是怎么保证的
 *
 * 洗牌一次 → 顺序消费 → 走完一轮再洗一次。**不是**每次随机取一张 ——
 * 那样会出现「同一张连出三次」和「几百张里有些永远抽不到」。
 *
 * ⛔ 与 [PhotoSourceAggregator.shuffled] 同款：用注入的 [VisualizerRandom]，
 * **不要** `List.shuffled()`（它走全局 `Random.Default`，会破坏「随机序列只有一处」的约定）。
 *
 * ⛔ 与 `PhotoTransitionPicker` 同款的一个坑：**新一轮的首张可能等于上一轮的末张**
 * （洗牌是独立的，概率 1/N）。用户视角就是「这张刚才不是刚看过吗」⇒ [beginNewRound]
 * 里做一次交换避让。
 *
 * ⚠️ `internal`（不是 `private`）：门禁 G14 要直接构造它。
 */
internal class PhotoWallPool {

    private var order: List<PhotoRef> = emptyList()
    private var cursor = 0
    private var lastShownId: String? = null

    val size: Int get() = order.size

    val isEmpty: Boolean get() = order.isEmpty()

    /** 换一批照片（扫描结果）。游标归零、避让记忆清空（新池与旧池无可比性） */
    fun reset(photos: List<PhotoRef>) {
        order = photos
        cursor = 0
        lastShownId = null
    }

    fun clear() {
        order = emptyList()
        cursor = 0
        lastShownId = null
    }

    /** 游标当前项（**不消费**）；已到末尾返回 `null`（调用方先 [beginNewRound]） */
    fun peek(): PhotoRef? = order.getOrNull(cursor)

    /** 消费当前项：记录「刚展示过谁」并把游标前移一位 */
    fun advance() {
        if (cursor < order.size) {
            lastShownId = order[cursor].id
            cursor++
        }
    }

    /**
     * 开新一轮：重新 Fisher-Yates 洗牌 + 游标归零 + **避开上一轮的末张**。
     *
     * ⚠️ 池里只有 1 张时无从避让（也确实没有「重复」这回事），直接归零返回。
     */
    fun beginNewRound(random: VisualizerRandom) {
        cursor = 0
        if (order.size < 2) return

        val a = ArrayList(order)
        for (i in a.size - 1 downTo 1) {
            val j = random.nextIndex(i + 1)
            if (i != j) {
                val tmp = a[i]
                a[i] = a[j]
                a[j] = tmp
            }
        }
        val last = lastShownId
        if (last != null && a[0].id == last) {
            val tmp = a[0]
            a[0] = a[1]
            a[1] = tmp
        }
        order = a
    }
}
