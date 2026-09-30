package com.nasmusic.tv.visualizer

import kotlin.math.cos
import kotlin.math.sin

/**
 * 粒子池（E08 / E09 / E14 / E19 共用）。
 *
 * **性能红线**：平铺 [FloatArray] + 紧凑数组（swap-remove），
 * 严禁 `List<Particle>`——装箱与 GC 会引发爆音级卡顿。
 *
 * 布局（每粒子 6 个 float）：x, y, vx, vy, life, hue
 *
 * P1#5（2026-09-14）：随机源由宿主渲染器注入（[rng]），不再用 `VisualizerMath`
 * 的进程级共享状态——否则交叉淡入时两层粒子会互相消耗对方的随机序列。
 */
class ParticlePool(val capacity: Int, private val rng: VisualizerRandom) {

    companion object {
        const val X = 0
        const val Y = 1
        const val VX = 2
        const val VY = 3
        const val LIFE = 4
        const val HUE = 5
        const val STRIDE = 6

        /**
         * [updateAttract] 的每帧寿命衰减（§B5-P1）。
         * `1.0f / 0.004f = 250` 帧 ⇒ 60fps 下约 **4.2 s** 一个生命周期。
         */
        const val LIFE_DECAY = 0.004f
    }

    val data = FloatArray(capacity * STRIDE)

    /** 活跃粒子数，活跃粒子紧凑存放在 [0, count) */
    var count: Int = 0
        private set

    fun spawn(x: Float, y: Float, vx: Float, vy: Float, life: Float, hue: Float): Boolean {
        if (count >= capacity) return false
        val o = count * STRIDE
        data[o + X] = x
        data[o + Y] = y
        data[o + VX] = vx
        data[o + VY] = vy
        data[o + LIFE] = life
        data[o + HUE] = hue
        count++
        return true
    }

    /** 径向喷发（中心发射 + 螺旋初速） */
    fun spawnRadial(
        cx: Float, cy: Float,
        angleRad: Float, speed: Float,
        life: Float, hue: Float,
        swirl: Float = 0f
    ) {
        val vx = cos(angleRad) * speed - sin(angleRad) * swirl
        val vy = sin(angleRad) * speed + cos(angleRad) * swirl
        spawn(cx, cy, vx, vy, life, hue)
    }

    /** 随机方向爆发（烟花 / 节拍爆发） */
    fun spawnBurst(cx: Float, cy: Float, n: Int, maxSpeed: Float, hueBase: Float, hueSpan: Float) {
        for (i in 0 until n) {
            val a = rng.next() * 6.2831853f
            val sp = maxSpeed * (0.35f + rng.next() * 0.65f)
            spawnRadial(cx, cy, a, sp, 1f, hueBase + rng.nextSigned() * hueSpan * 0.5f)
        }
    }

    /**
     * 推进一帧。
     *
     * @param speedScale 速度倍率（绑 treble）
     * @param gravity    纵向重力（绑 bass）
     * @param decay      生命周期衰减（越小活得越久）
     * @param drag       阻尼，1f 表示无阻尼
     */
    fun update(speedScale: Float, gravity: Float, decay: Float, drag: Float = 0.995f) {
        var i = 0
        while (i < count) {
            val o = i * STRIDE
            data[o + VX] = data[o + VX] * drag
            data[o + VY] = data[o + VY] * drag + gravity
            data[o + X] += data[o + VX] * speedScale
            data[o + Y] += data[o + VY] * speedScale
            data[o + LIFE] -= decay
            if (data[o + LIFE] <= 0f) {
                removeAt(i)              // swap-remove，不前移
            } else {
                i++
            }
        }
    }

    /**
     * 吸附到目标点（E19 粒子文字用）。
     *
     * **§B5-P1（T4.5）：`LIFE <= 0` 时不再 `removeAt`，改为「从画布外缘重生 + 复位寿命」。**
     *
     * ⛔ 原实现是 swap-remove，同时造成两个缺陷：
     * ① `count` 每降 1，槽位 `i` 对应的 `targets[i]` 就不再是那个粒子的目标点 ⇒ **目标错位**，
     *    文字逐渐"糊掉"；
     * ② E19 **只在 `caption` 变化时 `spawn`**，粒子全灭后**没有任何重生路径**
     *    ⇒ 250 帧（≈ 4.2 s @60fps）后 `count` 归零、**画面永久空白**（直到切效果 / 换歌）。
     *
     * 改为重生后 `count` 恒 == `capacity`，`i → targets[i]` **永不错位**，
     * 且每个粒子每约 4.2 s 从外缘"流"回文字一次（轻微流动感）。
     *
     * ⚠️ **本方法专属语义** —— 其余使用方走 [update]（仍是 swap-remove），不受影响。
     *
     * @param targets 目标点（长度 ≥ `capacity * 2`，索引与粒子槽位**一一对应**）
     * @param accel   吸附加速度系数
     * @param jitter  随机抖动
     * @param w       画布宽（重生投放用）
     * @param h       画布高（重生投放用）
     * @param decay   每帧寿命衰减（默认 [LIFE_DECAY]）
     */
    fun updateAttract(
        targets: FloatArray,
        accel: Float,
        jitter: Float,
        w: Float,
        h: Float,
        decay: Float = LIFE_DECAY
    ) {
        var i = 0
        while (i < count) {
            val o = i * STRIDE
            val tx = targets[i * 2]
            val ty = targets[i * 2 + 1]
            data[o + VX] += (tx - data[o + X]) * accel + rng.nextSigned() * jitter
            data[o + VY] += (ty - data[o + Y]) * accel + rng.nextSigned() * jitter
            data[o + VX] *= 0.86f
            data[o + VY] *= 0.86f
            data[o + X] += data[o + VX]
            data[o + Y] += data[o + VY]
            data[o + LIFE] -= decay
            // ⛔ 不 removeAt、不 i++ 之外的分支：count 恒定 ⇒ 槽位与 targets 永远对齐
            if (data[o + LIFE] <= 0f) respawnFromEdge(i, w, h)
            i++
        }
    }

    /**
     * §B5-P1 从画布**四边**随机一点重新投放，并复位速度与寿命。
     *
     * 刻意**避开画布中部** ⇒ 观感是"从外向内被吸回文字"，而不是原地闪烁。
     */
    private fun respawnFromEdge(i: Int, w: Float, h: Float) {
        val o = i * STRIDE
        when ((rng.next() * 4f).toInt().coerceIn(0, 3)) {
            0 -> {
                data[o + X] = rng.next() * w
                data[o + Y] = 0f
            }
            1 -> {
                data[o + X] = rng.next() * w
                data[o + Y] = h
            }
            2 -> {
                data[o + X] = 0f
                data[o + Y] = rng.next() * h
            }
            else -> {
                data[o + X] = w
                data[o + Y] = rng.next() * h
            }
        }
        data[o + VX] = 0f
        data[o + VY] = 0f
        data[o + LIFE] = 1f
    }

    private fun removeAt(i: Int) {
        val last = count - 1
        if (i != last) {
            val dst = i * STRIDE
            val src = last * STRIDE
            for (k in 0 until STRIDE) data[dst + k] = data[src + k]
        }
        count = last
    }

    fun clear() { count = 0 }
}
