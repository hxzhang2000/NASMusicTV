package com.nasmusic.tv.backend.photo

import com.nasmusic.tv.backend.photo.db.FaceResultStore
import com.nasmusic.tv.backend.photo.db.PhotoFaceEntity
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * 人脸扫描任务（§10.2：后台分片 + 可中断 + 进度上报）
 *
 * ## 为什么要有「中断 / 续跑」
 *
 * 1 万张 × 100ms ≈ **17 分钟**（老电视 ARMv7）。一个跑 17 分钟、中间不能停、
 * 停了就得从头再来的任务是不可接受的 ⇒ 三条硬要求：
 *
 * | 要求 | 落地 |
 * |---|---|
 * | **可中断** | 每片（[chunkSize] 张）之间 `yield()` ⇒ 取消请求在**片边界**生效（≤ 几十毫秒延迟） |
 * | **可续跑** | 结果**逐片落库**（不是最后一次性写）⇒ 中断时已扫的部分不丢；续跑时 [FaceResultStore.allKeys] 里的 key 直接跳过 |
 * | **进度** | 每片结束后更新 [state] 的 `done` / `total` |
 *
 * ⛔ **逐片落库是「可续跑」的前提**：如果攒到最后一次性 `upsertAll`，中断等于白跑。
 *
 * ## ⛔ 拔盘**不清表**（§10.3 / R9）
 *
 * 拔掉 U 盘后照片**读不到**，但表里那些条目在**重插之后仍然有效** ——
 * 清掉等于让用户白等 17 分钟。查询时是与 `PhotoSource` 的当前结果**做交集**的
 * （`PhotoWallController` 里做），所以盘上已不存在的照片会被自然剔除，不需要删表。
 * ⇒ 本类**没有任何**「存储变化时删数据」的路径。
 *
 * ## 失败语义
 *
 * 单张照片解码失败 / 推理失败 ⇒ **跳过**（记为「已处理、无人脸」还是「不入库」？见下）。
 * ⚠️ 这里刻意**不入库**：读不出来 ≠ 没有脸，写一条 `hasFace = false` 会让
 * 「盘插回来之后这张永远被排除」（因为续跑会跳过它）。⇒ 读不出来的照片**留待下次再试**。
 *
 * @param store 结果存取
 * @param detector 检测器（生产 = `YuNetFaceDetector`）
 * @param thumbs 缩略图提供者
 * @param scope 运行作用域；`null` = 自建（由 [close] 取消）
 * @param chunkSize 每片张数（越小 → 中断越灵敏、落库越频繁；越大 → 开销越小）
 */
class FaceScanManager(
    private val store: FaceResultStore,
    private val detector: FaceDetector,
    private val thumbs: PhotoThumbProvider,
    private val scope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {

    /** 任务阶段 */
    enum class Phase {
        /** 没在跑（初始 / 已中断 / 已停止） */
        IDLE,

        /** 正在跑 */
        RUNNING,

        /** 本轮全部跑完 */
        DONE,

        /** 模型不可用（ORT 加载失败）⇒ 不该继续跑 1 万张空推理 */
        UNAVAILABLE,
    }

    /** 对外状态（设置页进度行读这个） */
    data class FaceScanState(
        val phase: Phase = Phase.IDLE,
        val done: Int = 0,
        val total: Int = 0,
    ) {
        /** 是否要渲染进度行 */
        val showProgress: Boolean get() = total > 0

        val isRunning: Boolean get() = phase == Phase.RUNNING
    }

    // ⛔ `by lazy`：`Dispatchers.Main` 在纯 JVM 单测里**一碰就抛**
    //    （没有 Main dispatcher 实现）；注入 `scope` 的测试永远不会走到这里。
    private val ownScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    private val runScope: CoroutineScope get() = scope ?: ownScope

    private val _state = MutableStateFlow(FaceScanState())
    val state: StateFlow<FaceScanState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** 本轮要扫的照片（[start] 时快照 —— 扫描期间池变了也不影响本轮） */
    private var pending: List<PhotoRef> = emptyList()

    /**
     * 开始（或继续）扫描。
     *
     * ⚠️ 已在跑时**不重复启动**（返回 false）—— 设置页的按钮可能连点。
     *
     * @return `true` = 已启动；`false` = 正在跑 / 没有可扫的照片 / 模型不可用
     */
    fun start(refs: List<PhotoRef>): Boolean {
        if (job?.isActive == true) return false
        if (refs.isEmpty()) return false

        pending = refs
        job = runScope.launch(dispatcher) {
            runScan()
        }
        return true
    }

    /** 中断（保留已落库的结果 ⇒ 下次 [start] 自动续跑） */
    fun stop() {
        job?.cancel()
        job = null
        val s = _state.value
        if (s.phase == Phase.RUNNING) _state.value = s.copy(phase = Phase.IDLE)
    }

    /** 清除全部结果并回到初始态（设置页「清除检测结果」） */
    fun clearResults() {
        stop()
        runScope.launch(dispatcher) {
            store.clear()
            _state.value = FaceScanState()
        }
    }

    /** 「仅显示含人像」的过滤集合（与当前照片列表做交集） */
    suspend fun faceKeys(): Set<String> = store.faceKeys()

    /** 页面销毁。幂等。 */
    fun close() {
        stop()
        ownScope.cancel()
        _state.value = FaceScanState()
        pending = emptyList()
    }

    // ────────────────────────── 内部 ──────────────────────────

    private suspend fun runScan() {
        if (!detector.warmUp()) {
            _state.value = FaceScanState(phase = Phase.UNAVAILABLE)
            AppLog.w(TAG, "face detector unavailable - scan aborted")
            return
        }

        val refs = pending
        // ⛔ 续跑的关键：一次读出「已扫过谁」，之后逐片跳过
        val scanned = HashSet(store.allKeys())

        // ⚠️ 从 0 开始数：已扫过的条目在循环里也会被 `continue` 掉并计数，
        //    若这里先按 `scanned.size` 起跳就会**重复计数**（`scanned` 里可能含本轮之外的 key）
        var done = 0
        _state.value = FaceScanState(phase = Phase.RUNNING, done = done, total = refs.size)

        var i = 0
        while (i < refs.size) {
            val end = (i + chunkSize).coerceAtMost(refs.size)
            val batch = ArrayList<PhotoFaceEntity>(end - i)

            for (k in i until end) {
                val ref = refs[k]
                if (ref.id in scanned) {
                    done++
                    continue
                }
                val thumb = thumbs.decode(ref)
                if (thumb == null) {
                    // 读不出来 ⇒ **不入库**（留待下次再试，见类 KDoc「失败语义」）
                    done++
                    continue
                }
                val count = detector.detect(thumb)
                batch.add(
                    PhotoFaceEntity(
                        photoKey = ref.id,
                        hasFace = count > 0,
                        faceCount = count,
                        detectedAt = System.currentTimeMillis(),
                        fileModifiedSec = ref.lastModified,
                    )
                )
                done++
            }

            if (batch.isNotEmpty()) store.upsertAll(batch)
            i = end

            // ⛔ `runScan` 是成员 suspend 函数 ⇒ `this` 不是 CoroutineScope，
            //    必须从**当前协程上下文**取 isActive（写 `isActive` 会报 Unresolved）
            if (!currentCoroutineContext().isActive) {
                // 被取消 ⇒ 保留进度，回到 IDLE（续跑时 `done` 会由 `scanned` 重新算出）
                _state.value = FaceScanState(phase = Phase.IDLE, done = done, total = refs.size)
                return
            }
            _state.value = FaceScanState(phase = Phase.RUNNING, done = done, total = refs.size)
            // ⛔ 片之间的取消点：没有它，`cancel()` 要等到整个循环跑完才生效
            yield()
        }

        _state.value = FaceScanState(phase = Phase.DONE, done = done, total = refs.size)
        AppLog.i(TAG, "face scan done: $done/${refs.size}")
    }

    companion object {
        const val TAG = "FaceScanManager"

        /** 每片 32 张：老电视上约 3 秒一片 ⇒ 中断响应 ≤ 3 秒，落库也不至于太频繁 */
        const val DEFAULT_CHUNK_SIZE = 32
    }
}
