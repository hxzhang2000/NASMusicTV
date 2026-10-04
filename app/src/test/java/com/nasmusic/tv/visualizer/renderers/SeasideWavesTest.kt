package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * [SeasideWaves] 的帧驱动验收。
 *
 * ## 本文件的定位
 * 把 `docs/archive/verification/scripts/seaside_wave_harness.js` 的**断言口径逐条移植成
 * Kotlin**：同一个 60fps / 90 秒 / 1600×900 的驱动、同一批探针、同一批实测基线。
 * 跑的是**生产代码** [SeasideWaves]；原型跑的是真实 JS。两边只在「音频派生输入」上
 * 共用同一段确定性合成（见 [ProtoAudio]）。
 *
 * ## 为什么测试里要带一段音频合成
 * 原型的 `swashReachNow = SWASH_REACH·(0.62 + 0.48·sLow)` 与 `shoreW0 = h·SWELL_BAND_W·
 * (0.55 + 0.75·bandAmp(0))` 是 `computeShore` 的两个**入参**（外观量）。harness 无
 * AudioContext ⇒ 走 `readFallback()` + `smoothAudio()`，而 fallback 是 `performance.now()`
 * 的纯函数 ⇒ 完全确定。基线数字（6.45px / 10.77px / 14 段退水 / 83.7px …）是在**那一条
 * 输入序列**下测出来的 ⇒ 想逐位复现就必须喂同一条序列。这段合成只存在于测试里；
 * ⛔ 模拟核心内部零音频（见 [SeasideWaves] 类 KDoc）。
 *
 * ## 基线来源
 * `docs/seaside-preview.html`（原型定稿）+ 上述 harness，1600×900 / 60fps / 90s。
 * 每个用例的 KDoc 里逐条抄了 harness 的 stdout。
 */
class SeasideWavesTest {

    // ══════════════════════════════════════════════════════════════════════════
    //  原型的确定性 fallback 音频合成（只为喂两个「外观入参」，不进模拟核心）
    // ══════════════════════════════════════════════════════════════════════════

    private class ProtoAudio {
        var raw_low = 0.0
        var bass_avg = 0.20
        var set_imp = 0.0
        var low = 0.0
        var mid = 0.0
        var high = 0.0
        var energy = 0.0
        var s_low = 0.0
        var s_mid = 0.0
        var s_high = 0.0
        var s_energy = 0.0

        /** `readFallback(dt)`：`t = performance.now() / 1000`。 */
        fun read_fallback(now_sec: Double, dt: Double) {
            val kick = max(0.0, sin(now_sec * 6.8)).pow(8.0)
            raw_low = 0.22 + 0.55 * kick + 0.10 * sin(now_sec * 1.7)
            bass_avg += (raw_low - bass_avg) * (1.0 - exp(-dt * 1000.0 / 700.0))
            if (raw_low > bass_avg * 1.42 + 0.055) set_imp = min(1.0, set_imp + 0.5)
            set_imp *= exp(-dt * 1000.0 / SET_TAU_MS)
            low = min(1.0, max(0.0, raw_low))
            mid = min(1.0, max(0.0, 0.30 + 0.28 * sin(now_sec * 0.81)))
            high = min(1.0, max(0.0, 0.16 + 0.30 * max(0.0, sin(now_sec * 2.34)).pow(2.0)))
            energy = min(1.0, max(0.0, 0.34 + 0.34 * sin(now_sec * 0.345)))
        }

        /** `smoothAudio(dt)`：attack 55ms / release 260ms 一阶低通（**两端都钳**）。 */
        fun smooth(dt: Double) {
            val d = max(0.0, dt)
            s_low = k(s_low, low, d)
            s_mid = k(s_mid, mid, d)
            s_high = k(s_high, high, d)
            s_energy = k(s_energy, energy, d)
        }

        private fun k(cur: Double, tgt: Double, dt: Double): Double {
            val tau = if (tgt > cur) 55.0 else 260.0
            val f = 1.0 - exp(-dt * 1000.0 / tau)
            return min(1.0, max(0.0, cur + (tgt - cur) * f))
        }

        /** `bandAmp(L)`：⛔ `L == 0` 那支是「领头浪」，与 `lane`（恒 ≠ 0）是两件事。 */
        fun band_amp(lane: Int): Double {
            val e = if (lane == 0) s_low else (s_low * 0.70 + s_mid * 0.30)
            var a = 0.26 + 0.74 * e
            if (lane == 0) a += set_imp * 0.55
            return min(1.35, max(0.0, a))
        }

        fun swash_reach_now(): Double = SeasideWaves.SWASH_REACH * (0.62 + 0.48 * s_low)

        fun shore_w0(h: Double): Double =
            h * SeasideWaves.SWELL_BAND_W * (0.55 + 0.75 * min(1.35, band_amp(0)))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  探针（与 JS harness 同一位置：帧首 `requestAnimationFrame` 之前）
    // ══════════════════════════════════════════════════════════════════════════

    private class Sample {
        var t = 0.0
        var mean_y = 0.0
        var mean_adv = 0.0
        var stage = 0
        var retreat_t = 0.0
        var alive_n = 0
        var lead_slot = -1
        val ys = DoubleArray(NCOLS)
        val adv = DoubleArray(NCOLS)
        val p_state = IntArray(SeasideWaves.WAVE_POOL)
        val p_y = DoubleArray(SeasideWaves.WAVE_POOL)
        val p_alive_t = DoubleArray(SeasideWaves.WAVE_POOL)
        val p_fade = DoubleArray(SeasideWaves.WAVE_POOL)
        val p_ser = IntArray(SeasideWaves.WAVE_POOL)
        val p_swash_t = DoubleArray(SeasideWaves.WAVE_POOL)
        var p_n = 0
        var reached = 0
        val b_ser = IntArray(SeasideWaves.WAVE_POOL)
        val b_lead = BooleanArray(SeasideWaves.WAVE_POOL)
        val b_y = DoubleArray(SeasideWaves.WAVE_POOL)
        val b_adv = DoubleArray(SeasideWaves.WAVE_POOL)
        var b_n = 0
    }

    private class SpawnRec(val t: Double, val serial: Int, val travel_ms: Double)

    private class Sim {
        var max_mean_jump = 0.0
        var max_mean_jump_t = 0.0
        var mean_y_before = 0.0
        var mean_y_after = 0.0
        var mean_adv_before = 0.0
        var mean_adv_after = 0.0
        var max_col_jump = 0.0
        var max_adv_jump = 0.0
        var retreat_count = 0
        var dur_min = 0.0
        var dur_med = 0.0
        var dur_max = 0.0
        var drop_min = 0.0
        var drop_med = 0.0
        var drop_max = 0.0
        val alive_hist = LinkedHashMap<Int, Int>()
        val stage_hist = LinkedHashMap<Int, Int>()
        var non_finite_spawn_t = 0
        var spawn_count = 0
        val spawn_log = ArrayList<SpawnRec>()
        val travel_by_serial = LinkedHashMap<Int, Double>()
        var max_slot_dy = 0.0
        var max_slot_fade_jump = 0.0
        val max_adv_by_serial = LinkedHashMap<Int, Double>()
        var lead_switches = 0
        var lead_present = 0
        var min_shore_y = 0.0
        var max_shore_y = 0.0
        /** ⛔ 非法的状态边（§4.3.1「唯一允许的状态边」之外）。 */
        val illegal_edges = ArrayList<String>()
        /** 帧边界上观测到 `W_REACHED` 的次数（必须 0：`REACHED → FADING` 是同一帧内的事）。 */
        var reached_observed = 0
        /** `swashT` 在同一 serial 上的最大**回退**量（必须为 0：只增不减）。 */
        var swash_t_regress = 0.0
        /** `aliveT` 在同一 serial 上的最大回退量（必须为 0）。 */
        var alive_t_regress = 0.0
        val handoff_deltas = ArrayList<Double>()
        val handoff_advs = ArrayList<Double>()
        var sample_count = 0
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  驱动
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 逐帧驱动 [w]，逐帧抓状态，产出与 JS harness 同口径的全部指标。
     *
     * ⛔ 帧序照抄原型 `frame(now)`：
     * `探针 → dtReal → readFallback(dtReal) → smoothAudio(dtReal) → computeShore(t = now, …)`，
     * 且 `t` 取 `now`（⛔ 不是 `now − dt`，因为探针抓的是**上一帧末**的状态）。
     */
    private fun run(w: SeasideWaves, frames: Int = FRAMES_90S, collectBfy: Boolean = true): Sim {
        val sim = Sim()
        val audio = ProtoAudio()
        val samples = ArrayList<Sample>(frames)
        var last_now = 0.0
        var seen_spawns = 0

        for (n in 0 until frames) {
            val now = n * DT_MS

            // ── 探针（帧首）：抓上一帧末的状态 ────────────────────────────────
            val s = Sample()
            s.t = now
            var sum = 0.0
            var sum_adv = 0.0
            var i = 0
            while (i < NCOLS) {
                val y = w.shore_ys[i]
                s.ys[i] = y
                s.adv[i] = w.swash_pos[i]
                sum += y
                sum_adv += s.adv[i]
                i++
            }
            s.mean_y = sum / NCOLS
            s.mean_adv = sum_adv / NCOLS
            s.stage = w.stage
            s.retreat_t = w.retreat_t
            var slot = 0
            while (slot < SeasideWaves.WAVE_POOL) {
                val wv = w.wave_at(slot)
                if (wv != null) {
                    if (wv.state == SeasideWaves.W_REACHED) s.reached += 1
                    s.p_state[s.p_n] = wv.state
                    s.p_y[s.p_n] = wv.y
                    s.p_alive_t[s.p_n] = wv.alive_t
                    s.p_fade[s.p_n] = w.wave_fade(wv)
                    s.p_ser[s.p_n] = wv.serial
                    s.p_swash_t[s.p_n] = wv.swash_t
                    s.p_n++
                    val adv = min(1.0, max(0.0, wv.y))
                    val prev = sim.max_adv_by_serial[wv.serial]
                    if (prev == null || adv > prev) sim.max_adv_by_serial[wv.serial] = adv
                }
                slot++
            }
            s.alive_n = s.p_n
            val lead = w.lead_wave()
            if (lead != null) s.lead_slot = slot_of(w, lead)
            samples.add(s)

            // ── 帧体 ─────────────────────────────────────────────────────────
            // ⛔ ⛔ **第 0 帧也要 step**：原型 `frame()` 的探针虽在帧首抓状态，但帧体照跑
            //   （`lastNow` 初值 0 ⇒ 首帧 dtReal = 0.016，第二帧同理，第三帧才用真实 delta）。
            //   漏掉这一步会让第 1 个样本读到全 0 的 shoreYs（实测均值位移 556.89px）、
            //   并让 `spawnAcc` 晚一步到 2360（实测首次出浪 2366.67ms vs 2350ms）。
            val dt_real = if (last_now != 0.0) min(0.1, max(0.0, (now - last_now) / 1000.0)) else 0.016
            last_now = now
            audio.read_fallback(now / 1000.0, dt_real)
            audio.smooth(dt_real)
            w.step(dt_real, now, audio.swash_reach_now(), audio.shore_w0(H))

            if (w.spawn_count != seen_spawns) {
                seen_spawns = w.spawn_count
                sim.spawn_count = w.spawn_count
                val travel = w.last_spawn_travel_ms
                if (!travel.isFinite()) sim.non_finite_spawn_t++
                val serial = w.spawn_count - 1
                sim.spawn_log.add(SpawnRec(now, serial, travel))
                sim.travel_by_serial[serial] = travel
            }

            // ── 绘制层探针（原型挂在 drawSwellBands 逐列循环之后）────────────
            //    ⛔ isLead 由**状态**判定（REACHED/FADING），⛔ 不用 leadWave()。
            if (collectBfy) record_bfy(w, s, audio)
        }

        val keep = samples.subList(1, samples.size)
        sim.sample_count = keep.size
        aggregate(sim, keep)
        return sim
    }

    /** 逐浪记录「它自己那一帧的前缘 y」（列 48，与 harness 的 `bfy[48]` 同列）。 */
    private fun record_bfy(w: SeasideWaves, s: Sample, audio: ProtoAudio) {
        var k = 0
        while (k < w.active_count) {
            val wv = w.active_at(k) ?: break
            val is_lead = wv.is_beach
            val amp = if (is_lead) {
                0.55 + 0.45 * audio.band_amp(0)
            } else {
                0.46 + 0.45 * audio.band_amp(1 + (wv.wi_s % 2))
            }
            if (amp <= SILENCE_FLOOR) {
                k++
                continue
            }
            if (w.wave_fade(wv) <= FADE_CULL) {
                k++
                continue
            }
            val adv = min(1.0, max(0.0, wv.y))
            val lane = wv.lane
            w.fill_fray(s.t, lane)
            val w0 = H * SeasideWaves.SWELL_BAND_W * (0.62 + 0.62 * amp) * (if (is_lead) 1.0 else 0.92)
            if (s.b_n < SeasideWaves.WAVE_POOL) {
                s.b_ser[s.b_n] = wv.serial
                s.b_lead[s.b_n] = is_lead
                s.b_adv[s.b_n] = adv
                s.b_y[s.b_n] = w.breaker_front_y(adv, PROBE_COL, is_lead, lane, wv.seed, w0)
                s.b_n++
            }
            k++
        }
    }

    private fun slot_of(w: SeasideWaves, target: SeasideWaves.Wave): Int {
        var i = 0
        while (i < SeasideWaves.WAVE_POOL) {
            if (w.wave_at(i) === target) return i
            i++
        }
        return -1
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  指标聚合（逐条照抄 harness 的 JS）
    // ══════════════════════════════════════════════════════════════════════════

    private fun aggregate(sim: Sim, s: List<Sample>) {
        sim.min_shore_y = Double.MAX_VALUE
        sim.max_shore_y = -Double.MAX_VALUE
        for (f in s) {
            sim.alive_hist[f.alive_n] = (sim.alive_hist[f.alive_n] ?: 0) + 1
            sim.stage_hist[f.stage] = (sim.stage_hist[f.stage] ?: 0) + 1
            if (f.lead_slot >= 0) sim.lead_present++
            sim.reached_observed += f.reached
            // ⛔ 只统计**探针列 PROBE_COL**（= 48）—— harness 5d 的 shoreYs 也只取逐浪记录里的
            //    那一列，跨全部 97 列取极值会量到别的数。
            val yc = f.ys[PROBE_COL]
            if (yc < sim.min_shore_y) sim.min_shore_y = yc
            if (yc > sim.max_shore_y) sim.max_shore_y = yc
        }

        var i = 1
        while (i < s.size) {
            val cur = s[i]
            val prev = s[i - 1]
            val d = abs(cur.mean_y - prev.mean_y)
            if (d > sim.max_mean_jump) {
                sim.max_mean_jump = d
                sim.max_mean_jump_t = cur.t
                sim.mean_y_before = prev.mean_y
                sim.mean_y_after = cur.mean_y
                sim.mean_adv_before = prev.mean_adv
                sim.mean_adv_after = cur.mean_adv
            }
            var c = 0
            while (c < NCOLS) {
                val dc = abs(cur.ys[c] - prev.ys[c])
                if (dc > sim.max_col_jump) sim.max_col_jump = dc
                val da = abs(cur.adv[c] - prev.adv[c])
                if (da > sim.max_adv_jump) sim.max_adv_jump = da
                c++
            }
            // 逐浪对比（同一 serial 在相邻帧都在场时才可比）
            var k = 0
            while (k < cur.p_n) {
                var m = 0
                while (m < prev.p_n) {
                    if (prev.p_ser[m] == cur.p_ser[k]) {
                        val dy = abs(cur.p_y[k] - prev.p_y[m])
                        if (dy > sim.max_slot_dy) sim.max_slot_dy = dy
                        val df = abs(cur.p_fade[k] - prev.p_fade[m])
                        if (df > sim.max_slot_fade_jump) sim.max_slot_fade_jump = df
                        // §4.3.1「唯一允许的状态边」：EMPTY→ADVANCING→REACHED→FADING→EMPTY。
                        //   ⭐ 但 `REACHED → FADING` 是**同一帧内**发生的（`step` 内部
                        //   stepWaves → … → finishWaves），所以在**帧边界**上只能观测到
                        //   ADVANCING→ADVANCING / ADVANCING→FADING / FADING→FADING。
                        val edge = prev.p_state[m] to cur.p_state[k]
                        val legal = edge.first == edge.second ||
                            edge == (SeasideWaves.W_ADVANCING to SeasideWaves.W_FADING)
                        if (!legal && sim.illegal_edges.size < 8) {
                            sim.illegal_edges.add("ser${cur.p_ser[k]} ${edge.first}->${edge.second}")
                        }
                        val ds = prev.p_swash_t[m] - cur.p_swash_t[k]
                        if (ds > sim.swash_t_regress) sim.swash_t_regress = ds
                        val da = prev.p_alive_t[m] - cur.p_alive_t[k]
                        if (da > sim.alive_t_regress) sim.alive_t_regress = da
                        break
                    }
                    m++
                }
                k++
            }
            if (prev.lead_slot != cur.lead_slot) sim.lead_switches++
            i++
        }

        // 退水段（stage == 1 的连续帧）
        val durs = ArrayList<Double>()
        val drops = ArrayList<Double>()
        var t0 = -1.0
        var t1 = -1.0
        var mn = 1.0
        var mx = 0.0
        for (f in s) {
            if (f.stage == SeasideWaves.STAGE_RETREAT) {
                if (t0 < 0.0) {
                    t0 = f.t
                    mn = f.mean_adv
                    mx = f.mean_adv
                }
                t1 = f.t
                if (f.mean_adv < mn) mn = f.mean_adv
                if (f.mean_adv > mx) mx = f.mean_adv
            } else if (t0 >= 0.0) {
                durs.add(t1 - t0)
                drops.add((mx - mn) * REACH_PX)
                t0 = -1.0
            }
        }
        if (t0 >= 0.0) {
            durs.add(t1 - t0)
            drops.add((mx - mn) * REACH_PX)
        }
        sim.retreat_count = durs.size
        val ds = durs.sorted()
        val dp = drops.sorted()
        if (ds.isNotEmpty()) {
            sim.dur_min = pick(ds, 0.0)
            sim.dur_med = pick(ds, 0.5)
            sim.dur_max = pick(ds, 0.99)
            sim.drop_min = pick(dp, 0.0)
            sim.drop_med = pick(dp, 0.5)
            sim.drop_max = pick(dp, 0.99)
        }

        // 交接跳变：逐 serial 找「非前浪 → 前浪」那一帧
        val serials = LinkedHashSet<Int>()
        for (f in s) for (k in 0 until f.b_n) serials.add(f.b_ser[k])
        for (ser in serials) {
            i = 1
            while (i < s.size) {
                val cur = s[i]
                val prev = s[i - 1]
                var a = -1
                var b = -1
                for (k in 0 until prev.b_n) if (prev.b_ser[k] == ser) a = k
                for (k in 0 until cur.b_n) if (cur.b_ser[k] == ser) b = k
                if (a >= 0 && b >= 0 && !prev.b_lead[a] && cur.b_lead[b]) {
                    sim.handoff_deltas.add(abs(cur.b_y[b] - prev.b_y[a]))
                    sim.handoff_advs.add(cur.b_adv[b])
                }
                i++
            }
        }
    }

    /** 照抄 harness 的 `pick`：`arr[min(len-1, floor(len·q))]`（**不是**插值分位）。 */
    private fun pick(sorted: List<Double>, q: Double): Double =
        sorted[min(sorted.size - 1, (sorted.size * q).toInt())]

    /**
     * 90 秒基准跑的**共享缓存**。⛔ 不是为了让断言变松 —— 所有用例读的是同一份
     * `Sim`，任何一条指标被改动会同时反映到全部用例上；纯粹为了不把 5400 帧跑十遍。
     */
    private fun baseline(): Sim {
        var s = cached_baseline
        if (s == null) {
            s = run(SeasideWaves(WF, HF))
            cached_baseline = s
        }
        return s
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ① 逐帧水线位移（原型基线 均值 6.45px / 单列 10.77px）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `基线 逐帧水线位移 均值6_45px 单列10_77px`() {
        val sim = baseline()

        // harness: max |delta| waterline mean y / frame : 6.45 px @ t=7366.67 ms
        assertEquals("均值位移应复现原型 6.45px", 6.45, sim.max_mean_jump, 0.05)
        assertTrue("均值位移必须 < 8px（超过就读作跳变），实得 ${sim.max_mean_jump}", sim.max_mean_jump < 8.0)
        assertEquals("最大跳帧时刻", 7366.666666666667, sim.max_mean_jump_t, 1.0)

        // harness 1b) 那一帧的前后状态：meanY 613.74 -> 620.19、meanAdv 0.8669 -> 0.9611
        assertEquals(613.74, sim.mean_y_before, 0.05)
        assertEquals(620.19, sim.mean_y_after, 0.05)
        assertEquals(0.8669, sim.mean_adv_before, 5e-4)
        assertEquals(0.9611, sim.mean_adv_after, 5e-4)

        // harness: max |delta| any column y / frame : 10.77 px
        assertEquals(10.77, sim.max_col_jump, 0.05)
        // harness: max |delta| per-column adv / frame : 0.1563
        assertEquals(0.1563, sim.max_adv_jump, 5e-4)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ② 退水可见性（原型基线 14 段 / 中位 1567ms / 83.7px）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `基线 退水14段 中位1567ms 落差83_7px 满程94_5px`() {
        val sim = baseline()

        assertEquals("90 秒内退水段数", 14, sim.retreat_count)
        assertEquals("退水中位时长 1567ms", 1567.0, sim.dur_med, DT_MS)
        assertEquals("退水最短时长 1383ms", 1383.0, sim.dur_min, DT_MS)
        assertEquals("退水最长时长 1567ms", 1567.0, sim.dur_max, DT_MS)
        assertEquals("退水中位落差 83.7px", 83.7, sim.drop_med, 0.05)
        assertEquals("退水最小落差 83.7px", 83.7, sim.drop_min, 0.05)
        assertEquals("退水最大落差 84.1px", 84.1, sim.drop_max, 0.05)
        // 满程 0.105 × 900 = 94.5px ⇒ 中位落差达满程的 89%
        assertEquals(94.5, REACH_PX, 1e-9)
        assertTrue("中位落差应达满程的 ~89%", sim.drop_med / REACH_PX > 0.85)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ③ 海面浪数与阶段直方图（原型基线 {0:141, 1:1501, 2:3757}）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `基线 浪数直方图 与 阶段直方图`() {
        val sim = baseline()

        assertEquals("probe samples 应为 5399（= 5400 帧 − 首帧）", 5399, sim.sample_count)
        assertEquals(141, sim.alive_hist[0] ?: -1)
        assertEquals(1501, sim.alive_hist[1] ?: -1)
        assertEquals(3757, sim.alive_hist[2] ?: -1)
        assertTrue("不可能同时在场 3 条（WAVE_FOLLOW_Y 调度 ⇒ 稳态 1+1）", sim.alive_hist[3] == null)
        // harness: stage histogram : {"0":3945,"1":1313,"2":141}
        assertEquals(3945, sim.stage_hist[0] ?: -1)
        assertEquals(1313, sim.stage_hist[1] ?: -1)
        assertEquals(141, sim.stage_hist[2] ?: -1)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ④ 出浪时序与非有限 T（原型基线 0 / 15）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `基线 出浪时序 行程T 与 非有限T为零`() {
        val sim = baseline()

        assertEquals("spawns with non-finite T : 0 / 15", 0, sim.non_finite_spawn_t)
        assertEquals(15, sim.spawn_count)
        assertEquals(15, sim.travel_by_serial.size)

        // harness 6) 发浪日志（前 6 条）：ser / T(ms) / t(ms)
        val expectSer = intArrayOf(0, 1, 2, 3, 4, 5)
        val expectT = intArrayOf(4992, 9260, 11185, 11600, 10259, 8400)
        val expectAt = intArrayOf(2350, 5450, 11200, 18150, 25350, 31717)
        for (k in expectSer.indices) {
            val rec = sim.spawn_log[k]
            assertEquals("ser", expectSer[k], rec.serial)
            assertEquals("T(ms)", expectT[k].toDouble(), rec.travel_ms, 0.5)
            assertEquals("出浪时刻(ms)", expectAt[k].toDouble(), rec.t, 1.0)
        }

        // harness 7) 每条浪的 1/v（ms）
        val expectTravel = intArrayOf(
            4992, 9260, 11185, 11600, 10259, 8400, 11432, 8400, 10975, 8400, 10074, 8400, 10259, 9142, 9591
        )
        for (ser in expectTravel.indices) {
            assertEquals(
                "ser$ser 的行程 T", expectTravel[ser].toDouble(),
                sim.travel_by_serial[ser] ?: -1.0, 0.5
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑤ 交接连续性（原型基线 中位 1.1px / 最大 3.0px，全部 adv = 1.000）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `基线 交接不是位置突变 均值1_1px 最大3_0px 且全在adv等于1`() {
        val sim = baseline()

        assertTrue("应捕获到交接事件", sim.handoff_deltas.isNotEmpty())
        val sorted = sim.handoff_deltas.sorted()
        val mean = sorted.sum() / sorted.size
        val med = sorted[sorted.size / 2]
        // ⚠️ harness 的 `|bfy 跳变| : 中位 …` 其实算的是**算术平均**（`reduce/length`），
        //    不是中位数；基线 1.1px / 3.0px 是按它的口径记的。两个都断言，口径差异留痕。
        assertEquals("交接 |Δbfy|（harness 口径 = 算术平均）1.1px", 1.1, mean, 0.05)
        assertEquals("交接 |Δbfy| 最大 3.0px", 3.0, sorted.last(), 0.05)
        assertTrue("交接 |Δbfy| 真中位数也必须很小（实得 $med）", med < 3.0)
        for (a in sim.handoff_advs) {
            assertEquals("交接必须发生在 adv = 1.000", 1.0, a, 1e-9)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑥ 逐浪连续性（原型基线 max|Δy| 0.0033 / max|ΔwaveFade| 0.0330）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `基线 逐浪行程与淡入逐帧连续`() {
        val sim = baseline()

        // harness 4) max per-slot |delta| y / frame : 0.0033（= 最快那条浪的 v·dt）
        assertEquals(0.0033, sim.max_slot_dy, 5e-4)
        // harness 4) max per-slot |delta| waveFade / frame : 0.0330
        assertEquals(0.0330, sim.max_slot_fade_jump, 5e-3)
        // harness 4) lead identity switches : 15 次 / 5399 帧；lead present 5258 / 5399
        assertEquals(15, sim.lead_switches)
        assertEquals(5258, sim.lead_present)

        // §4.3.1「唯一允许的状态边」—— 逐帧逐浪核对，池回收复用也不算状态边
        assertTrue("出现非法状态边：${sim.illegal_edges}", sim.illegal_edges.isEmpty())
        assertEquals(
            "W_REACHED 只能活在 step 内部（同一帧就转 FADING），帧边界上不得观测到",
            0, sim.reached_observed
        )
        assertEquals("swashT 只增不减", 0.0, sim.swash_t_regress, 0.0)
        assertEquals("aliveT 只增不减", 0.0, sim.alive_t_regress, 0.0)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑦ 红线 1：后浪必须能一路到滩（⛔ 禁位置钳位）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `红线1 后浪全程自由推进 绝大多数浪抵滩`() {
        val sim = baseline()
        val reached = sim.max_adv_by_serial.values.count { it >= 0.99 }
        assertTrue("抵滩浪数应 ≥ 13（末条仍在途中），实得 $reached", reached >= 13)
        assertTrue(
            "至少一条浪必须真的走到 adv=1（⛔ 位置钳位会让它永远停在半路）",
            sim.max_adv_by_serial.values.any { it >= 0.999 }
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑧ 红线 6：身份索引终身固定 + 逐列场按 lane 自有一份
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `红线6 wiS与lane终身固定且lane恒不为零`() {
        val w = SeasideWaves(WF, HF)
        val audio = ProtoAudio()
        val lane_by_serial = HashMap<Int, Int>()
        val wi_s_by_serial = HashMap<Int, Int>()
        var last_now = 0.0
        for (n in 0 until FRAMES_90S) {
            val now = n * DT_MS
            var k = 0
            while (k < w.active_count) {
                val wv = w.active_at(k) ?: break
                val prev_lane = lane_by_serial.put(wv.serial, wv.lane)
                val prev_wi_s = wi_s_by_serial.put(wv.serial, wv.wi_s)
                if (prev_lane != null) assertEquals("lane 一生固定", prev_lane, wv.lane)
                if (prev_wi_s != null) assertEquals("wiS 一生固定", prev_wi_s, wv.wi_s)
                assertTrue("lane 恒 != 0", wv.lane != 0)
                assertEquals("wiS = serial and 3", wv.serial and 3, wv.wi_s)
                assertEquals("lane = 1 + serial % 3", 1 + wv.serial % 3, wv.lane)
                k++
            }
            val dt = if (last_now != 0.0) min(0.1, max(0.0, (now - last_now) / 1000.0)) else 0.016
            last_now = now
            audio.read_fallback(now / 1000.0, dt)
            audio.smooth(dt)
            w.step(dt, now, audio.swash_reach_now(), audio.shore_w0(H))
        }
        assertTrue("应观察到 ≥ 13 条浪", lane_by_serial.size >= 13)
    }

    @Test
    fun `红线6 撕裂场与破碎场按lane自有一份 写取不串位`() {
        val a = SeasideWaves(WF, HF)
        val solo_tear = HashMap<Int, DoubleArray>()
        val solo_fray = HashMap<Int, DoubleArray>()
        // 单独填每个 lane，记下「该 lane 自己那份」的内容
        for (lane in 0 until SeasideWaves.LANE_SLOTS) {
            solo_tear[lane] = a.fill_tears(lane).copyOf()
            solo_fray[lane] = a.fill_fray(1234.0, lane).copyOf()
        }
        // 再按绘制层的用法把所有 lane 填一遍
        for (lane in 0 until SeasideWaves.LANE_SLOTS) {
            assertSame("fill_tears 必须写进 tear_of 取的那一块", a.tear_of(lane), a.fill_tears(lane))
            assertSame("fill_fray 必须写进 fray_of 取的那一块", a.fray_of(lane), a.fill_fray(1234.0, lane))
        }
        for (lane in 0 until SeasideWaves.LANE_SLOTS) {
            assertArrayEquals(
                "lane$lane 的撕裂场必须来自 lane$lane 自己", solo_tear[lane]!!, a.tear_of(lane), 0.0
            )
            assertArrayEquals(
                "lane$lane 的破碎场必须来自 lane$lane 自己", solo_fray[lane]!!, a.fray_of(lane), 0.0
            )
        }
        // 前提：不同 lane 的内容确实不同（否则「串位」不可观测，断言会空转）
        assertTrue("lane1 与 lane2 的撕裂场必须不同", a.tear_of(1).toList() != a.tear_of(2).toList())
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑨ 红线 7：沙纹理上沿必须高于水线摆动
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `红线7 沙纹理上沿高于水线摆动全程`() {
        val sim = baseline()
        val w = SeasideWaves(WF, HF)

        assertEquals(0.46, SeasideWaves.SAND_TEX_TOP, 1e-9)
        assertEquals(414.0, w.sand_tex_top_px, 1e-9)
        assertEquals("水线摆动保守下界 = 0.484h", 435.6, w.waterline_min_bound_px, 1e-6)
        assertTrue(
            "沙纹理上沿 ${w.sand_tex_top_px}px 必须低于水线摆动下界 ${w.waterline_min_bound_px}px",
            w.sand_tex_top_px < w.waterline_min_bound_px
        )
        // harness 5d) 水线 shoreYs 实际范围 : 535.2 .. 637.2 px；纹理覆盖最小 y ✔
        assertEquals("水线最小 y（原型实测 535.2px）", 535.2, sim.min_shore_y, 0.1)
        assertEquals("水线最大 y（原型实测 637.2px）", 637.2, sim.max_shore_y, 0.1)
        assertTrue("沙纹理上沿必须 ≤ 水线最小 y", w.sand_tex_top_px <= sim.min_shore_y)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑩ 峰值包络 / 逐列记忆 / 暂停冻结 / 确定性 / 换尺寸
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `峰值包络 在抵滩那一瞬逐列等于当帧水线`() {
        val w = SeasideWaves(WF, HF)
        var checked = 0
        drive(w, FRAMES_90S) { n ->
            if (n == 0) return@drive
            // 本帧刚转 FADING 的那条：`fade_t` 只有一步 ⇒ 它在**本帧**的逐列循环里还是
            // `REACHED`，`peak[]` 正是在本帧被写入（`step` 内顺序：stepWaves → 填 peak → finishWaves）。
            var k = 0
            while (k < w.active_count) {
                val wv = w.active_at(k) ?: break
                if (wv.is_beach && wv.hit && wv.fade_t <= 25.0) {
                    val peak = wv.peak
                    assertEquals(NCOLS, peak.size)
                    assertNotNull(w.lead_peak())
                    assertEquals(NCOLS, w.peak_length)
                    var i = 0
                    while (i < NCOLS) {
                        // peak[] 与 finishWaves 在**同一次 step** 里发生 ⇒ 比的是**当帧**水线
                        assertEquals("peak[$i] = 抵滩那一瞬的水线", w.shore_ys[i], peak[i], 0.0)
                        i++
                    }
                    checked++
                }
                k++
            }
        }
        assertTrue("应至少观察到 3 次抵滩（实测 $checked）", checked >= 3)
    }

    @Test
    fun `湿沙记忆 逐列只增不减 且不越出画面`() {
        val w = SeasideWaves(WF, HF)
        for (i in 0 until NCOLS) assertEquals(-1e9, w.wet_mark[i], 0.0)
        var prev_max = -1e9
        drive(w, FRAMES_90S) {
            var mx = -1e9
            for (i in 0 until NCOLS) if (w.wet_mark[i] > mx) mx = w.wet_mark[i]
            assertTrue("wetMark 是**历史**最高水位，只增不减（上一帧 $prev_max → 本帧 $mx）", mx >= prev_max)
            prev_max = mx
        }
        for (i in 0 until NCOLS) {
            assertTrue("wetMark 必须是真实水位（> 0）", w.wet_mark[i] > 0.0)
            assertTrue("wetMark 必须在画面内", w.wet_mark[i] < H)
            assertTrue("wetAmt ∈ 0..1", w.wet_amt[i] in 0.0..1.0)
            assertTrue("wetEdge 不得低于水线", w.wet_edge[i] >= w.shore_ys[i] - 1e-9)
        }
    }

    @Test
    fun `暂停传dt等于零时队列完全冻结`() {
        val w = SeasideWaves(WF, HF)
        drive(w, 600) { }
        val y0 = DoubleArray(SeasideWaves.WAVE_POOL) { w.wave_at(it)?.y ?: -1.0 }
        val adv0 = w.swash_pos.copyOf()
        val wet0 = w.wet_amt.copyOf()
        var wet_mean0 = 0.0
        for (v in wet0) wet_mean0 += v
        wet_mean0 /= NCOLS
        val retreat0 = w.retreat_t
        // 暂停：dt = 0，但绝对时间仍在走（潮汐 / 浪脊漂移是绝对相位，照样动 —— 与原型一致）
        for (k in 1..120) w.step(0.0, 600 * DT_MS + k * DT_MS, SWASH_REACH_NOW, SHORE_W0)
        for (i in 0 until SeasideWaves.WAVE_POOL) {
            assertEquals("暂停时行程必须冻结", y0[i], w.wave_at(i)?.y ?: -1.0, 0.0)
        }
        for (i in 0 until NCOLS) {
            assertEquals("暂停时冲流推进量必须冻结", adv0[i], w.swash_pos[i], 0.0)
            // ⚠️ 沿岸 3 抽头平滑**每帧都在跑**（原型亦然），所以逐列值允许微小变化；
            //   但**绝不能被清零** —— 原型写 `decay = dt > 0 ? … : 0` ⇒ `pow(0, dryK) = 0`
            //   ⇒ 暂停一帧就把整条湿沙抹掉，与 §4.3.3「暂停时干燥完全冻结」相悖。
            assertTrue("暂停时湿沙不得被清零", w.wet_amt[i] > 0.0)
        }
        var wet_mean1 = 0.0
        for (v in w.wet_amt) wet_mean1 += v
        wet_mean1 /= NCOLS
        assertTrue(
            "暂停时湿沙平均湿润度不得明显下降（$wet_mean0 → $wet_mean1）",
            wet_mean1 > wet_mean0 * 0.98
        )
        assertEquals("暂停时退水计时必须冻结", retreat0, w.retreat_t, 0.0)
    }

    @Test
    fun `确定性 同输入两次跑出逐位相同的状态轨迹`() {
        val a = SeasideWaves(WF, HF)
        val b = SeasideWaves(WF, HF)
        drive(a, 1200) { }
        drive(b, 1200) { }
        assertEquals(a.spawn_count, b.spawn_count)
        assertEquals(a.retreat_t, b.retreat_t, 0.0)
        for (i in 0 until NCOLS) {
            assertEquals("水线必须逐位可复现", a.shore_ys[i], b.shore_ys[i], 0.0)
        }
    }

    @Test
    fun `换尺寸必须清空队列与逐列记忆`() {
        val w = SeasideWaves(WF, HF)
        drive(w, 900) { }
        assertTrue("跑 900 帧后应有在册浪", w.active_count > 0)
        w.resize(1280f, 720f)
        assertEquals("换尺寸后不得留半个队列", 0, w.active_count)
        assertEquals(0, w.spawn_count)
        assertEquals(-1.0, w.retreat_t, 0.0)
        for (i in 0 until NCOLS) {
            assertEquals("湿沙记忆必须清空", -1e9, w.wet_mark[i], 0.0)
            assertEquals(0.0, w.wet_amt[i], 0.0)
        }
        assertEquals(1920.0 / 1280.0, 1920.0 / w.w, 1e-12)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  负向自证 —— 同一批断言喂破实现必须失败（否则门禁可能是空转）
    // ══════════════════════════════════════════════════════════════════════════

    /** 破实现 A：⛔ 红线 1 —— 把后浪冻在固定行程（原型曾用 `WAVE_SPACING_Y = 0.55`）。 */
    private class ClampedWaves(w: Float, h: Float) : SeasideWaves(w, h) {
        override fun advance_wave(wv: SeasideWaves.Wave, dms: Double) {
            wv.y = min(wv.y + wv.v * dms, 0.55)
        }
    }

    /** 破实现 B：⛔ 红线 9 —— 两处起退水的守卫里**漏掉** `retreat_t < 0`。 */
    private class NoRetreatGuardWaves(w: Float, h: Float) : SeasideWaves(w, h) {
        override fun retreat_guard(): Boolean = true
    }

    /** 破实现 C：⛔ 红线 6 —— 撕裂场/破碎场的「写」与「取」落到**不同的 lane 槽位**。 */
    private class MispairedLaneWaves(w: Float, h: Float) : SeasideWaves(w, h) {
        override fun lane_slot(lane: Int): Int = if (lane == 1) 2 else lane
    }

    @Test
    fun `负向自证 位置钳位必须让后浪到不了滩`() {
        val good = baseline()
        val bad = run(ClampedWaves(WF, HF))

        fun reaches(sim: Sim) = sim.max_adv_by_serial.values.any { it >= 0.99 }
        assertTrue("真实现必须能抵滩", reaches(good))
        assertTrue("破实现（位置钳位）必须到不了滩 —— 否则这条断言是空转", !reaches(bad))
        assertTrue("破实现的 adv 应被钉在 0.55 附近", bad.max_adv_by_serial.values.all { it <= 0.55 })
        assertTrue(
            "破实现下滩上永远空着 ⇒ 退水段数应远少于真实现",
            bad.retreat_count < good.retreat_count
        )
    }

    @Test
    fun `负向自证 起退水守卫漏掉小于零必须造成单帧大跳`() {
        val good = baseline()
        val bad = run(NoRetreatGuardWaves(WF, HF))

        assertTrue("真实现：逐帧均值位移 < 8px（实得 ${good.max_mean_jump}）", good.max_mean_jump < 8.0)
        assertTrue(
            "破实现（无 retreat_t<0 守卫）必须出现单帧大跳（原型实测 78.79px），实得 ${bad.max_mean_jump}",
            bad.max_mean_jump > 8.0
        )
    }

    @Test
    fun `负向自证 lane槽位错配必须让撕裂场串位`() {
        fun mismatched(w: SeasideWaves): Boolean {
            val solo = HashMap<Int, DoubleArray>()
            for (lane in 0 until SeasideWaves.LANE_SLOTS) solo[lane] = w.fill_tears(lane).copyOf()
            for (lane in 0 until SeasideWaves.LANE_SLOTS) w.fill_tears(lane)
            for (lane in 0 until SeasideWaves.LANE_SLOTS) {
                if (!solo[lane]!!.contentEquals(w.tear_of(lane))) return true
            }
            return false
        }
        assertTrue("真实现不得串位", !mismatched(SeasideWaves(WF, HF)))
        assertTrue(
            "破实现（lane 1 读到 lane 2 的槽位）必须被同一判据抓到",
            mismatched(MispairedLaneWaves(WF, HF))
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  驱动器（帧序照抄原型 frame(now)）
    // ══════════════════════════════════════════════════════════════════════════

    private fun drive(w: SeasideWaves, frames: Int, after: (Int) -> Unit) {
        val audio = ProtoAudio()
        var last_now = 0.0
        for (n in 0 until frames) {
            val now = n * DT_MS
            val dt = if (last_now != 0.0) min(0.1, max(0.0, (now - last_now) / 1000.0)) else 0.016
            last_now = now
            audio.read_fallback(now / 1000.0, dt)
            audio.smooth(dt)
            w.step(dt, now, audio.swash_reach_now(), audio.shore_w0(H))
            after(n)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    private companion object {
        const val WF = 1600f
        const val HF = 900f
        const val H = 900.0
        const val DT_MS = 1000.0 / 60.0
        const val FRAMES_90S = 5400
        const val NCOLS = SeasideWaves.COLS + 1
        const val REACH_PX = SeasideWaves.SWASH_REACH * 900.0
        const val SET_TAU_MS = 500.0
        const val SILENCE_FLOOR = 0.012
        const val FADE_CULL = 0.02
        const val PROBE_COL = 48
        const val SWASH_REACH_NOW = SeasideWaves.SWASH_REACH * 0.62
        const val SHORE_W0 = H * SeasideWaves.SWELL_BAND_W * 1.0

        var cached_baseline: Sim? = null
    }
}