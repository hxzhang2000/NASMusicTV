package com.nasmusic.tv.visualizer.renderers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.google.gson.Gson
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.roundToInt

/**
 * E41「世界」3D 版渲染器 —— three-globe（WebView + WebGL）承载。
 *
 * 与旧 2D 版 WorldRenderer 的关系：**3D 版复用 E41「世界」序号**，工厂只
 * 引用本类；旧 2D 版已整文件删除归档（M11 修复，2026-10-06）。效果
 * 列表仍是 28 项、E41 行不新增。
 *
 * ## 为什么用 WebView 而不是纯 Canvas / OpenGL ES
 * 需求是「三维地球 + 大圆航线 + 城市光点 + 音频驱动」。手写 OpenGL ES 球体
 * 曲面细分/光照/大圆插值成本极高；WorldWindKotlin 要求 minSdk 24（本项目锁
 * 22，含创维 5.1.1 真机回归基准）被否决。three-globe 走系统 WebView + WebGL：
 * - minSdk 22 兼容（WebGL1 兜底，three.js ≤ r162）
 * - 完全离线：index.html / polyfill.es5.js / three.es5.js / three-globe.es5.js / globe.js
 *   与 4 张贴图（earth_lit / earth_glow / earth_night / moon）全部 assets 打包，页面零网络、
 *   零远程资源。⛔ 城市数据**不走文件**——由本类经 `evalJs("WorldGlobe.initCities(...)")`
 *   注入，故 `cities.json` 已移出 assets（见 `app/src/globe-upstream/`）。
 * - 音频经 100ms 事件粒度桥接，连续动画留在 JS rAF 循环，Kotlin 侧零绘制开销
 *
 * ## API 契约（与 assets/globe/globe.js 一一对应，勿单边改动）
 * ```
 * window.WorldGlobe.initCities(cities)   // [{lat,lng,tier,name}]，页面加载后发一次
 * window.WorldGlobe.updateRoutes(routes) // [{fromLat,fromLng,toLat,toLng,klass,lane}]
 *                                        //   klass 0=主干 / 1=支线 / 2=次要
 *                                        //   lane  同一端点对的并行车道号 0..2
 *                                        //        （JS 据此分层错开，避免重叠）
 * window.WorldGlobe.setAudio(params)     // {energy,bass,mid,treble,beat} 全 0..1
 * window.WorldGlobe.isReady()            // → true
 * ```
 * 桥接粒度 100ms：航线集合每 tick 更新（数据变化才推送），音频参数 EMA 平滑后
 * 每 tick 推送；弧线 dash 流动 / 光点脉冲 / 自转 / 大气呼吸由 JS rAF 连续驱动。
 */
internal class WorldGlobeRenderer(context: Context) : VisualizerRenderer {
    override val theme: VisualizerTheme = VisualizerTheme.WORLD

    /** View 型渲染器旁路：舞台用 AndroidView 承载，draw() 不再被调用 */
    override val isViewBased: Boolean get() = true

    /** 接口要求实现 draw；View 型渲染器旁路下舞台不调用它，恒为空实现 */
    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {}

    /** 音频桥接粒度（ms） */
    private val TICK_MS = 100L

    /** EMA 平滑系数（0..1，越大越跟手） */
    private val EMA_ALPHA = 0.35f

    /** 焦点城市轮换周期（ms） */
    private val FOCUS_SWAP_MS = 20_000L

    /**
     * 抽到「端点对已满」的航线时的重抽次数上限。
     *
     * 繁忙枢纽（Tier1↔Tier1）的并行上限是 3，满员概率不低；不给重抽会让
     * 「多条并行」在最该出现的地方（繁忙城市）反而消失。
     */
    private val ROUTE_SPAWN_RETRY = 4

    // ── 航线数 ↔ 音乐强度（2026-09-29 真机反馈后引入）────────────────────

    /** 保底航线数占上限的比例：强度为 0 时仍保留这么多条航线在飞 */
    private val MUSIC_FLOOR_RATIO = 0.28f

    /** 保底航线数下限（即使 LOW 画质档上限很小，也要保证「有音乐就能看到航线」） */
    private val MUSIC_FLOOR_MIN = 8

    /** 每 tick 最多新生成几条（航线一批批进场，不会一帧内暴增） */
    private val MAX_SPAWN_PER_TICK = 3

    /** 鼓点冲量：拍点瞬间把强度顶高这么多，随后按 [BEAT_KICK_DECAY] 衰减 */
    private val BEAT_KICK_MAX = 0.30f
    private val BEAT_KICK_DECAY = 0.90f

    /** 目标航线数的平滑系数（越小变化越慢，避免航线数抖动） */
    private val TARGET_SMOOTH = 0.18f

    /**
     * 帧序号停滞多久算「没有音乐在播」。
     *
     * 100ms 一 tick ⇒ 9 tick。取 900ms 是为了容忍偶发的分析线程卡顿/切歌间隙，
     * 又要能在暂停/播完后 1 秒内把航线清空。
     */
    private val FRAME_STALE_MS = 900L

    /** 拍点冲量当前值（0..[BEAT_KICK_MAX]） */
    private var beatKick = 0f

    /** 目标航线数的平滑态（EMA） */
    private var targetRoutesEma = 0f

    /** 上次看到的 [com.nasmusic.tv.visualizer.SpectrumRepository.frameSeq] */
    private var lastFrameSeq = -1L

    /** 帧序号最后一次前进的时刻 */
    private var lastFrameSeqMs = 0L

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val rnd = VisualizerRandom()

    /** 100ms 音频事件定时器（主线程） */
    private val handler = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var attached = false
    private var pageReady = false
    private var citiesSent = false

    /** 画质档位（onEnter 注入；View 型渲染器同样走 sync → onEnter） */
    private var quality: VisualQuality = VisualQuality.HIGH

    /**
     * 一条在飞的航线：[spec] 是几何描述，[lane] 是**该端点对内的并行车道号**（0 起）。
     *
     * 车道号交给 JS 侧用来把同一对城市上的多条航线**分层错开**（不同弧高 +
     * 不同 dash 相位），否则多条航线会画在同一条大圆上、完全重叠成一条。
     */
    private class Flight(val spec: WorldRouteSpec, val lane: Int)

    /** 活跃航线（FIFO：尾部最新，超出上限从头部移除模拟「航班离场」） */
    private val activeRoutes = ArrayDeque<Flight>()

    /** 节拍分类器（复用 WorldNetwork 的算法，驱动 pickRoute 的强度档位） */
    private val beatClassifier = BeatClassifier()

    // ── 音频 EMA 平滑态（0..1，桥接给 JS）────────────────────────────────
    private var emaEnergy = 0f
    private var emaBass = 0f
    private var emaMid = 0f
    private var emaTreble = 0f
    private var emaBeat = 0f

    private var focusCity = -1
    private var lastFocusSwapMs = 0L

    /** 上次推送给 JS 的航线 JSON（内容去重，见 [sendRoutes]） */
    private var lastRoutesJson: String? = null

    // ── 生命周期（VisualizerRenderer 接口）───────────────────────────────

    override fun onEnter(ctx: RenderContext) {
        quality = ctx.quality
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun createView(context: Context): View {
        val wv = WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.domStorageEnabled = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            // ⛔ 禁焦三件套（MvPlaybackScreen 同款）：WebView 子树必须完全让出
            // Android 视图焦点，否则遥控器方向键全部路由到 WebView，Compose
            // 焦点系统（切效果/返回键）失效。
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isClickable = false
            isLongClickable = false
            webViewClient = object : WebViewClient() {
                // ⛔ file:// 页面在 Android 12+（targetSdk 30+）被禁加载其它
                //    file:// 子资源（three.min.js 等全被静默拦截 → THREED 未定义
                //    → globe.js 提前 return → 黑屏，2026-09-29 真机确诊）。
                //    方案：页面从虚拟 HTTPS 资产域加载，脚本用 shouldInterceptRequest
                //    从 assets 流式提供 —— 完全离线、零网络请求、无权限变更。
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (!url.startsWith(ASSET_BASE)) return null
                    val path = Uri.parse(url).path ?: return null
                    // ASSET_BASE = "https://appassets.androidplatform.net/globe"
                    val assetName = path.removePrefix("/globe/")
                    // favicon 是 WebView 的自动请求，assets 里没有也不影响渲染。
                    // 返回空 body 而非 null，避免它触发真联网 + 报错噪音。
                    if (assetName == "favicon.ico") {
                        return WebResourceResponse(
                            "image/x-icon", "UTF-8",
                            java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    // ⛔ 资产域内请求**永不返回 null**：返回 null 会让 WebView fallback
                    //    真联网（该保留域无公网 DNS → 国内直接失败 → 黑屏）。
                    //    解析失败也返回空 body，保持完全离线。
                    return try {
                        val stream = appContext.assets.open("globe/$assetName")
                        val mime = when {
                            assetName.endsWith(".html") -> "text/html"
                            assetName.endsWith(".js") -> "application/javascript"
                            assetName.endsWith(".json") -> "application/json"
                            assetName.endsWith(".jpg") || assetName.endsWith(".jpeg") -> "image/jpeg"
                            assetName.endsWith(".png") -> "image/png"
                            else -> "application/octet-stream"
                        }
                        WebResourceResponse(mime, "UTF-8", stream)
                    } catch (e: Exception) {
                        AppLog.e("WorldGlobe", "asset not found, serving empty: $assetName", e)
                        WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    // 页面脚本（含 globe.js 同步段）已执行完毕 → WorldGlobe 必然已定义
                    sendCities()
                }

                // 资源/页面加载失败时留痕（黑屏诊断用）
                // 本重载（WebResourceRequest / WebResourceError 参数）从 API 23 起才由系统回调，
                // 因此标记 @RequiresApi 而非在方法体内包 SDK 判断。
                @RequiresApi(Build.VERSION_CODES.M)
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    AppLog.e("WorldGlobe", "page/resource error: ${error?.errorCode} ${error?.description} url=${request?.url}")
                }
            }
            // ⛔ JS console/异常转发到 logcat：没有它，globe.js 的运行时错误
            //    （WebGL 不可用 / 库不兼容 / 契约错）全部吞掉，黑屏无从诊断。
            //    只做转发不改页面行为。
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    AppLog.e(
                        "WorldGlobe",
                        "JS[${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}"
                    )
                    return true
                }
            }
            loadUrl("$ASSET_BASE/index.html")
        }
        webView = wv
        return wv
    }

    companion object {
        /** 虚拟资产域：脚本与页面都从这里加载，绕开 file:// 子资源限制（完全离线） */
        private const val ASSET_BASE = "https://appassets.androidplatform.net/globe"
    }

    override fun onViewAttached() {
        if (attached) return
        attached = true
        // lastFocusSwapMs 保持 0：第一次 tick（frame.timeMs 必然 > 0）即触发焦点轮换
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    override fun onViewDetached() {
        attached = false
        handler.removeCallbacks(tickRunnable)
        // ⛔ 不能同步 destroy：onDispose 触发时 AndroidView 的 view 可能仍挂在
        // 组合销毁流程中，直接 destroy 会崩。post 到主线程队列（此时组合销毁
        // 已完成、view 已 detach），并先摘除父容器再销毁。
        val wv = webView
        webView = null
        wv?.post {
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            } catch (_: Exception) {
            }
        }
    }

    override fun onExit() {
        attached = false
        handler.removeCallbacks(tickRunnable)
        // WebView 销毁统一交给 onViewDetached（AndroidView 组销毁路径）
    }

    // ── 音频桥接（100ms 事件粒度）────────────────────────────────────────

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!attached || webView == null) return
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        if (webView == null) return
        if (!pageReady) return
        val frame = spectrumFrame() ?: return
        val nowMs = frame.timeMs

        // ① 音频 EMA 平滑 → setAudio
        emaEnergy = ema(emaEnergy, frame.energy)
        emaBass = ema(emaBass, frame.bass)
        emaMid = ema(emaMid, frame.mid)
        emaTreble = ema(emaTreble, frame.treble)
        emaBeat = if (frame.beat) 1f else emaBeat * 0.80f
        sendAudio()

        // ② 航线维护（复用 WorldNetwork 选线模型）
        maintainRoutes(frame, nowMs)
    }

    private fun spectrumFrame(): AudioFrame? = try {
        (appContext as NasMusicApp).playerManager.spectrumRepository.frame
    } catch (t: Throwable) {
        null
    }

    private fun ema(old: Float, v: Float): Float = old + (v - old) * EMA_ALPHA

    // ── 航线维护 ──────────────────────────────────────────────────────────

    private fun maintainRoutes(frame: AudioFrame, nowMs: Long) {
        val cap = WorldNetwork.maxActiveFlights(quality.maxParticles, TICK_MS.toFloat())
            .coerceIn(WorldNetwork.MIN_ACTIVE_FLIGHTS, WorldNetwork.MAX_ACTIVE_FLIGHTS)

        // 焦点城市轮换（hub 从 Tier1 里按权重抽）
        if (focusCity < 0 || nowMs - lastFocusSwapMs >= FOCUS_SWAP_MS) {
            focusCity = pickFocusCity()
            lastFocusSwapMs = nowMs
        }

        val beat = beatClassifier.update(frame.bassRaw, nowMs, frame.beat)

        // 拍点冲量：鼓点瞬间把强度顶上去，随后衰减（航线数会跟着鼓点涨落）
        if (frame.beat) beatKick = BEAT_KICK_MAX else beatKick *= BEAT_KICK_DECAY

        // 2026-09-29 真机反馈：「航线多少跟音乐强度结合，但有最低值只要有音乐就能
        // 看到航线，没音乐就没航线。」故不再用「固定上限 + 概率生成」，改为
        // **向目标数量收敛**：目标 = 保底 + (上限 - 保底) × 强度。
        val playing = isMusicPlaying(nowMs)
        val intensity = (emaEnergy + beatKick).coerceIn(0f, 1f)
        val target = if (playing) targetRoutes(intensity, cap) else 0
        // 平滑：增删都走同一个 EMA，避免目标抖动导致航线数忽上忽下
        targetRoutesEma += (target - targetRoutesEma) * TARGET_SMOOTH
        val goal = targetRoutesEma.toInt()

        // 补到目标：每 tick 限量（航线一批批进场，不会一帧内暴增）
        var spawned = 0
        while (activeRoutes.size < goal && spawned < MAX_SPAWN_PER_TICK) {
            val before = activeRoutes.size
            spawnRoute(beat)
            if (activeRoutes.size == before) break // 所有端点对都已排满，本 tick 到此为止
            spawned++
        }
        // 减到目标：从头部淘汰 = 最老的航班先落地
        while (activeRoutes.size > goal) activeRoutes.removeFirst()

        sendRoutes()
    }

    /**
     * 目标航线数 = 保底 + (上限 − 保底) × 强度。
     *
     * 保底（[musicFloorRoutes]）保证「只要有音乐就能看到航线」——这是用户明确要求：
     * 安静的前奏/间奏也必须有几条线在飞，不能空屏。
     */
    private fun targetRoutes(intensity: Float, cap: Int): Int {
        val floor = musicFloorRoutes(cap)
        val span = cap - floor
        return floor + Math.round(span * intensity.coerceIn(0f, 1f))
    }

    /** 保底航线数：随上限缩放，但不低于 [MUSIC_FLOOR_MIN] */
    private fun musicFloorRoutes(cap: Int): Int =
        (cap * MUSIC_FLOOR_RATIO).roundToInt().coerceIn(
            MUSIC_FLOOR_MIN.coerceAtMost(cap),
            cap
        )

    /**
     * 是否真的有音乐在播。
     *
     * 判据用 [com.nasmusic.tv.visualizer.SpectrumRepository.frameSeq]（其 KDoc 明写
     * 「渲染层轮询此值判断是否收到新帧」）：停止播放后不再有新帧写入，序号停住。
     * 只看 `energy == 0` 不可靠 —— 曲头/曲尾的静音会让能量瞬间归零，而用户要的是
     * 「没播放」才清空，不是「这一拍没声音」就清空。
     */
    private fun isMusicPlaying(nowMs: Long): Boolean {
        val seq = spectrumFrameSeq()
        if (seq != lastFrameSeq) {
            lastFrameSeq = seq
            lastFrameSeqMs = nowMs
            return true
        }
        return nowMs - lastFrameSeqMs < FRAME_STALE_MS
    }

    private fun spectrumFrameSeq(): Long = try {
        (appContext as NasMusicApp).playerManager.spectrumRepository.frameSeq
    } catch (t: Throwable) {
        0L
    }

    /**
     * 抽一条新航线入池。
     *
     * 同一对城市可同时存在多条（按 [WorldNetwork.maxParallelLanes] 的并行上限），
     * 满了就**重抽**而不是硬塞 —— 否则「并行上限」会退化成「全局上限」：
     * 第一次抽中已满的端点对就放弃，会让繁忙枢纽永远只有一条航线。
     */
    private fun spawnRoute(beat: BeatStrength) {
        var attempts = 0
        while (attempts < ROUTE_SPAWN_RETRY) {
            attempts++
            val spec = WorldNetwork.pickRoute(rnd, focusCity, beat)
            val lane = freeLaneOf(spec.from, spec.to)
            if (lane < 0) continue // 该端点对已满，重抽
            activeRoutes.addLast(Flight(spec, lane))
            return
        }
    }

    /**
     * 该端点对当前空闲的车道号；全满返回 -1。
     *
     * ⛔ **端点对按无序处理**：`A→B` 与 `B→A` 是同一条大圆走廊（只是 dash 流向
     *   相反），若分开计数就会出现两条航线叠在同一条弧上，抵消「多条并行」的意义。
     */
    private fun freeLaneOf(from: Int, to: Int): Int {
        val max = WorldNetwork.maxParallelLanes(from, to)
        val used = BooleanArray(max)
        for (f in activeRoutes) {
            val sameCorridor = (f.spec.from == from && f.spec.to == to) ||
                (f.spec.from == to && f.spec.to == from)
            if (sameCorridor && f.lane in 0 until max) used[f.lane] = true
        }
        for (i in 0 until max) if (!used[i]) return i
        return -1
    }

    /** Tier1（8 座骨干枢纽）里按权重抽一个作为 focusCity */
    private fun pickFocusCity(): Int {
        var best = 0
        var bestW = -1f
        for (i in 0 until WorldCities.COUNT) {
            val c = WorldCities.ALL[i]
            if (c.tier == 1) {
                val w = WorldCities.weightOf(i) * rnd.next()
                if (w > bestW) {
                    bestW = w
                    best = i
                }
            }
        }
        return best
    }

    // ── JS 推送 ───────────────────────────────────────────────────────────

    private fun sendCities() {
        if (citiesSent) return
        citiesSent = true
        val list = buildList {
            for (c in WorldCities.ALL) {
                add(mapOf("name" to c.name, "lat" to c.lat, "lng" to c.lon, "tier" to c.tier))
            }
        }
        evalJs("WorldGlobe.initCities(${gson.toJson(list)})")
    }

    private fun sendAudio() {
        val params = mapOf(
            "energy" to emaEnergy.coerceIn(0f, 1f),
            "bass" to emaBass.coerceIn(0f, 1f),
            "mid" to emaMid.coerceIn(0f, 1f),
            "treble" to emaTreble.coerceIn(0f, 1f),
            "beat" to emaBeat.coerceIn(0f, 1f)
        )
        evalJs("WorldGlobe.setAudio(${gson.toJson(params)})")
    }

    private fun sendRoutes() {
        // ⚠️ 空列表**也必须发**：2026-09-29 改成「没音乐就没航线」后，若这里在空时
        //   提前 return，JS 侧会一直持有最后一批航线，暂停/播完也不消失。
        val list = buildList {
            for (f in activeRoutes) {
                val spec = f.spec
                val from = WorldCities.ALL[spec.from]
                val to = WorldCities.ALL[spec.to]
                add(
                    mapOf(
                        "fromLat" to from.lat,
                        "fromLng" to from.lon,
                        "toLat" to to.lat,
                        "toLng" to to.lon,
                        "klass" to spec.klass.jsIndex(),
                        // 并行车道号：JS 侧据此分层错开，避免多条航线重叠成一条
                        "lane" to f.lane
                    )
                )
            }
        }
        // ⚠️ Kotlin 侧内容去重：tick 每 100ms 调一次，若无变化就不必做 Gson
        //   序列化 + 跨进程 evaluateJavascript（JS 侧还会再 stringify 一遍）。
        //   真机上这条固定开销与像素比同为「WebView 卡顿」来源之一。
        val json = gson.toJson(list)
        if (json == lastRoutesJson) return
        lastRoutesJson = json
        evalJs("WorldGlobe.updateRoutes($json)")
    }

    private fun WorldRouteClass.jsIndex(): Int = when (this) {
        WorldRouteClass.TRUNK -> 0
        WorldRouteClass.REGIONAL -> 1
        WorldRouteClass.FEEDER -> 2
    }

    private fun evalJs(script: String) {
        try {
            webView?.evaluateJavascript(script, null)
        } catch (t: Throwable) {
            // 页面卸载竞态等：静默忽略，下一 tick 重发
        }
    }
}
