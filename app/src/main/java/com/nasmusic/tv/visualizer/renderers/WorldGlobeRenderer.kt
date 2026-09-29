package com.nasmusic.tv.visualizer.renderers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.google.gson.Gson
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.VisualizerRenderer

/**
 * E41「世界」3D 版渲染器 —— three-globe（WebView + WebGL）承载。
 *
 * 与旧 2D 版 [WorldRenderer] 的关系：**3D 版复用 E41「世界」序号**，工厂只
 * 引用本类；[WorldRenderer] 保留在源码中但不被任何调用点引用（隐藏）。效果
 * 列表仍是 28 项、E41 行不新增。
 *
 * ## 为什么用 WebView 而不是纯 Canvas / OpenGL ES
 * 需求是「三维地球 + 大圆航线 + 城市光点 + 音频驱动」。手写 OpenGL ES 球体
 * 曲面细分/光照/大圆插值成本极高；WorldWindKotlin 要求 minSdk 24（本项目锁
 * 22，含创维 5.1.1 真机回归基准）被否决。three-globe 走系统 WebView + WebGL：
 * - minSdk 22 兼容（WebGL1 兜底，three.js ≤ r162）
 * - 完全离线：three.min.js / three-globe.min.js / globe.js / cities.json 全部
 *   assets 打包，页面零网络、零远程资源
 * - 音频经 100ms 事件粒度桥接，连续动画留在 JS rAF 循环，Kotlin 侧零绘制开销
 *
 * ## API 契约（与 assets/globe/globe.js 一一对应，勿单边改动）
 * ```
 * window.WorldGlobe.initCities(cities)   // [{lat,lng,tier,name}]，页面加载后发一次
 * window.WorldGlobe.updateRoutes(routes) // [{fromLat,fromLng,toLat,toLng,klass}]
 *                                        //   klass 0=主干 / 1=支线 / 2=次要
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

    /** 活跃航线（FIFO：尾部最新，超出上限从头部移除模拟「航班离场」） */
    private val activeRoutes = ArrayDeque<WorldRouteSpec>()

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
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    // 页面脚本（含 globe.js 同步段）已执行完毕 → WorldGlobe 必然已定义
                    sendCities()
                }
            }
            loadUrl("file:///android_asset/globe/index.html")
        }
        webView = wv
        return wv
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

        // 节拍强度（驱动 pickRoute 的端点偏好）+ 能量驱动 spawn 概率
        val beat = beatClassifier.update(frame.bassRaw, nowMs, frame.beat)
        val spawnP = 0.18f + 0.62f * emaEnergy + (if (frame.beat) 0.30f else 0f)

        if (rnd.next() < spawnP.coerceIn(0f, 0.95f) || activeRoutes.isEmpty()) {
            val spec = WorldNetwork.pickRoute(rnd, focusCity, beat)
            activeRoutes.addLast(spec)
        }
        while (activeRoutes.size > cap) activeRoutes.removeFirst()

        sendRoutes()
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
        if (activeRoutes.isEmpty()) return
        val list = buildList {
            for (spec in activeRoutes) {
                val from = WorldCities.ALL[spec.from]
                val to = WorldCities.ALL[spec.to]
                add(
                    mapOf(
                        "fromLat" to from.lat,
                        "fromLng" to from.lon,
                        "toLat" to to.lat,
                        "toLng" to to.lon,
                        "klass" to spec.klass.jsIndex()
                    )
                )
            }
        }
        evalJs("WorldGlobe.updateRoutes(${gson.toJson(list)})")
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
