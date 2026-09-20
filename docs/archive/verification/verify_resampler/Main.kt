// LinearResampler 数值验证（独立 JVM，绕开 Gradle 测试 worker）
//
// Resampler.kt 是从 app/src/main/java/com/nasmusic/tv/player/DemucsSeparator.kt
// 第 903-949 行**逐字抽取**的（只做了去缩进 + 去掉 private 修饰符），
// 因此这里验证的就是线上那份实现，不存在"测试与源码分叉"。
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.sin

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS" else "FAIL") + "  " + name + (if (detail.isEmpty()) "" else "   [" + detail + "]"))
    if (!ok) failures++
}

/** 理想参考实现：out[k] = 在输入坐标 k*ratio 处的线性插值 */
fun reference(input: FloatArray, k: Long, ratio: Double): Float {
    val p = k * ratio
    val i0 = floor(p).toInt()
    if (i0 < 0) return input[0]
    if (i0 >= input.size - 1) return input[input.size - 1]
    val frac = (p - i0).toFloat()
    return input[i0] + (input[i0 + 1] - input[i0]) * frac
}

/**
 * 合法输出帧数上限（**精确整数运算**，不用浮点）。
 *
 * 约束：输出 k 的输入坐标 p = k * (inRate/outRate) 必须满足 p <= n-1
 * （插值需要 floor(p)+1 号样本，不可外推）。等价于 k * inRate <= (n-1) * outRate。
 *
 * 之所以不用 `floor((n-1) / ratio) + 1`：double 除法在整除边界会给出 3968.999…，
 * 反而比实现少算一帧（实测 in=48000 out=44100 n=4321 即此情形）。
 */
fun maxOutputCount(n: Int, inRate: Int, outRate: Int): Long {
    if (n <= 0) return 0
    return (n - 1).toLong() * outRate / inRate + 1
}

fun runResampler(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
    val out = ArrayList<Float>(input.size + 16)
    val r = LinearResampler(inRate, outRate) { l, _ -> out.add(l) }
    for (v in input) r.push(v, v)
    r.flush()
    return out.toFloatArray()
}

fun sine(n: Int, freq: Double, rate: Double): FloatArray =
    FloatArray(n) { sin(2.0 * PI * freq * it / rate).toFloat() }

fun freqByZeroCrossings(x: FloatArray, rate: Double): Double {
    var crossings = 0
    var first = -1
    var last = -1
    for (i in 1 until x.size) {
        if (x[i - 1] <= 0f && x[i] > 0f) {
            if (first < 0) first = i else last = i
            crossings++
        }
    }
    if (crossings < 2) return -1.0
    return (crossings - 1) * rate / (last - first).toDouble()
}

fun maxStep(x: FloatArray): Float {
    var m = 0f
    for (i in 1 until x.size) m = maxOf(m, abs(x[i] - x[i - 1]))
    return m
}

fun main() {
    // ---------- T1: 同速率（ratio=1.0）必须逐样本 bit-exact 透传 ----------
    run {
        val n = 20000
        val input = sine(n, 440.0, 44100.0)
        val out = runResampler(input, 44100, 44100)
        check("T1a 同速率输出帧数 == 输入帧数", out.size == n, "in=$n out=${out.size}")
        var maxDiff = 0f
        for (i in 0 until minOf(out.size, n)) maxDiff = maxOf(maxDiff, abs(out[i] - input[i]))
        check("T1b 同速率逐样本一致（bit-exact）", maxDiff == 0f, "maxDiff=$maxDiff")
    }

    // ---------- T2: 48000 -> 44100，与理想插值逐点 bit-exact 比对 ----------
    // 这是最强的一条：流式实现必须等价于「整段离线按 k*ratio 插值」。
    run {
        val n = 48000
        val input = sine(n, 1000.0, 48000.0)
        val inRate = 48000
        val outRate = 44100
        val ratio = inRate.toDouble() / outRate.toDouble()
        val out = runResampler(input, inRate, outRate)

        val expectedCount = maxOutputCount(n, inRate, outRate)
        check(
            "T2a 48000→44100 输出帧数符合精确上界 (n-1)*outRate/inRate+1",
            out.size.toLong() == expectedCount,
            "expected=$expectedCount got=${out.size}"
        )
        // 输出/输入 应等于 outRate/inRate（1 秒 48k → 1 秒 44.1k）
        val lenRatio = out.size.toDouble() / n
        check(
            "T2b 输出帧数比例 = outRate/inRate（0.91875）",
            abs(lenRatio - outRate.toDouble() / inRate) < 1e-4,
            "测得=${"%.6f".format(lenRatio)} 期望=${"%.6f".format(outRate.toDouble() / inRate)}"
        )

        var maxDiff = 0f
        var worstK = -1L
        for (k in out.indices) {
            val d = abs(out[k] - reference(input, k.toLong(), ratio))
            if (d > maxDiff) { maxDiff = d; worstK = k.toLong() }
        }
        check("T2c 与理想插值逐点一致（bit-exact）", maxDiff == 0f, "maxDiff=$maxDiff @k=$worstK")
    }

    // ---------- T3: 频率保持（1000Hz 正弦经 48k→44.1k 后仍应是 1000Hz）----------
    run {
        val inRate = 48000
        val outRate = 44100
        val input = sine(inRate, 1000.0, inRate.toDouble())   // 1 秒
        val out = runResampler(input, inRate, outRate)
        val f = freqByZeroCrossings(out, outRate.toDouble())
        check("T3a 重采样后频率保持 1000Hz", abs(f - 1000.0) < 5.0, "测得=${"%.2f".format(f)}Hz")
        // 时长保持：输出样本数 / 输出速率 ≈ 1 秒
        val dur = out.size.toDouble() / outRate
        check("T3b 时长保持 1.0s", abs(dur - 1.0) < 0.001, "测得=${"%.5f".format(dur)}s")
    }

    // ---------- T4: 连续性（无块边界跳变）----------
    run {
        val inRate = 48000
        val outRate = 44100
        val input = sine(inRate, 1000.0, inRate.toDouble())
        val out = runResampler(input, inRate, outRate)
        // 1000Hz 单位幅度正弦在 44.1kHz 下的理论最大步进 ≈ 2π*1000/44100 ≈ 0.1425
        val theoretical = 2.0 * PI * 1000.0 / outRate
        check(
            "T4 最大相邻步进未超理论值（无跳变）",
            maxStep(out) < theoretical * 1.1,
            "maxStep=${"%.5f".format(maxStep(out))} 理论≈${"%.5f".format(theoretical)}"
        )
    }

    // ---------- T5: 22050 -> 44100（ratio=0.5，上采样，一次 push 出 2 帧）----------
    run {
        val n = 22050
        val input = sine(n, 500.0, 22050.0)
        val out = runResampler(input, 22050, 44100)
        val expected = maxOutputCount(n, 22050, 44100)
        check("T5a 22050→44100 帧数翻倍", out.size.toLong() == expected, "expected=$expected got=${out.size}")
        val f = freqByZeroCrossings(out, 44100.0)
        check("T5b 上采样后频率保持 500Hz", abs(f - 500.0) < 3.0, "测得=${"%.2f".format(f)}Hz")
    }

    // ---------- T6: 8000 -> 44100（ratio≈0.1814，一次 push 最多出 6 帧）----------
    run {
        val n = 8000
        val input = sine(n, 300.0, 8000.0)
        val out = runResampler(input, 8000, 44100)
        val expected = maxOutputCount(n, 8000, 44100)
        check("T6a 8000→44100 帧数 ≈ 5.51x", out.size.toLong() == expected, "expected=$expected got=${out.size}")
        val f = freqByZeroCrossings(out, 44100.0)
        check("T6b 强上采样后频率保持 300Hz", abs(f - 300.0) < 3.0, "测得=${"%.2f".format(f)}Hz")
    }

    // ---------- T7: 边界情形 ----------
    run {
        val out = runResampler(FloatArray(0), 48000, 44100)
        check("T7a 空输入 → 0 帧且不崩溃", out.size == 0, "got=${out.size}")
    }
    run {
        val out = runResampler(floatArrayOf(0.5f), 48000, 44100)
        check("T7b 单帧输入 → 恰好 1 帧", out.size == 1, "got=${out.size}")
    }
    run {
        // 3 帧输入：输出坐标 k*ratio 最大只能到 n-1（插值需要 floor(p)+1 号样本，不可外推），
        // 故上限 = floor((n-1)/ratio)+1 = floor(2/1.0884)+1 = 2。这不是丢样本，是数学上界。
        val n = 3
        val ratio = 48000.0 / 44100.0
        val out = runResampler(floatArrayOf(0.1f, 0.2f, 0.3f), 48000, 44100)
        val upper = maxOutputCount(n, 48000, 44100)
        check("T7c 3 帧输入 → 达到不可外推的上界", out.size.toLong() == upper, "upper=$upper got=${out.size}")
        // 输出 k=0 落在输入坐标 0（= v0）；k=1 落在 1.0884（在 v1、v2 之间插值），
        // 不是 v1 —— 这正是"线性插值"与"取整点采样"的区别。
        val v = floatArrayOf(0.1f, 0.2f, 0.3f)
        check(
            "T7d 3 帧输出等于理想插值（k=0 取 v0，k=1 在 v1/v2 间插值）",
            out[0] == v[0] && out[1] == reference(v, 1L, ratio),
            out.joinToString(",") + " 期望=" + listOf(v[0], reference(v, 1L, ratio)).joinToString(",")
        )
    }

    // ---------- T7e: 尾部丢失量有界（关键工程性质）----------
    // 流式实现只能在坐标 <= n-1 处产出，因此相对理想长度 n/ratio 会少最多 1+1/ratio 个样本。
    // 对 4 分钟曲目（千万帧级）而言这是 1~2 个样本，完全可忽略；但要显式断言，防止回归。
    run {
        val cases = listOf(
            Triple(48000, 44100, 48000),
            Triple(48000, 44100, 4 * 60 * 48000),   // 4 分钟
            Triple(44100, 44100, 44100),
            Triple(8000, 44100, 8000),
            Triple(22050, 44100, 22050),
            Triple(32000, 44100, 32000)
        )
        var worstLoss = 0.0
        var worstDesc = ""
        var worstBound = 0.0
        for ((inR, outR, n) in cases) {
            val ratio = inR.toDouble() / outR
            var cnt = 0L
            val r = LinearResampler(inR, outR) { _, _ -> cnt++ }
            for (i in 0 until n) r.push(0f, 0f)
            r.flush()
            val ideal = n / ratio
            val loss = ideal - cnt
            if (loss > worstLoss) { worstLoss = loss; worstDesc = "${inR}->${outR} n=$n"; worstBound = 1.0 / ratio }
        }
        check(
            "T7e 尾部丢失量 < outRate/inRate 个样本（不外推的数学上界）",
            worstLoss < worstBound,
            "最大丢失=${"%.3f".format(worstLoss)} 上界=${"%.3f".format(worstBound)} @ $worstDesc"
        )
    }

    // ---------- T7f: 属性测试——随机速率/长度组合下，输出必须与理想插值 bit-exact ----------
    run {
        val rnd = java.util.Random(20260914L)
        val rates = listOf(8000, 16000, 22050, 32000, 44100, 48000, 96000)
        var badCount = 0
        var badValue = 0
        var combos = 0
        val mismatch = StringBuilder()
        for (inR in rates) {
            for (outR in rates) {
                for (n in intArrayOf(1, 2, 3, 5, 17, 100, 1000, 4321)) {
                    combos++
                    val ratio = inR.toDouble() / outR
                    val input = FloatArray(n) { (rnd.nextFloat() * 2 - 1) }
                    val out = runResampler(input, inR, outR)
                    val expected = maxOutputCount(n, inR, outR)
                    if (out.size.toLong() != expected) {
                        badCount++
                        mismatch.append("\n      in=$inR out=$outR n=$n ratio=$ratio expected=$expected got=${out.size}")
                    }
                    for (k in out.indices) {
                        if (out[k] != reference(input, k.toLong(), ratio)) { badValue++; break }
                    }
                }
            }
        }
        check("T7f 属性测试：${combos} 组 (inRate,outRate,n) 帧数全对", badCount == 0, "不符=$badCount$mismatch")
        check("T7f 属性测试：${combos} 组输出与理想插值 bit-exact", badValue == 0, "不符=$badValue")
    }

    // ---------- T8: 长音频不漂移（模拟 5 分钟 48kHz，约 1440 万帧）----------
    run {
        val n = 14_400_000
        val inRate = 48000
        val outRate = 44100
        val ratio = inRate.toDouble() / outRate.toDouble()
        var count = 0L
        var last = 0f
        // 用锯齿（线性斜坡）而不是正弦，避免三角函数算 1440 万次拖慢
        var phase = 0.0
        val r = LinearResampler(inRate, outRate) { l, _ -> count++; last = l }
        for (i in 0 until n) {
            phase += 1.0 / 480.0
            if (phase > 1.0) phase -= 1.0
            r.push((phase * 2 - 1).toFloat(), 0f)
        }
        r.flush()
        val expected = maxOutputCount(n, inRate, outRate)
        check("T8a 1440 万帧无累积漂移（帧数精确）", count == expected, "expected=$expected got=$count")
        check("T8b 末样本落在有效范围", last >= -1.0f && last <= 1.0f, "last=$last")
    }

    // ================= 字节写出路径（P2-a 批量写）=================

    // ---------- B1: putShortLE 与 shortToByteArray 全 16-bit 域逐值一致 ----------
    // 两者分别用于「批量缓冲」和「WAV 头」，字节序必须一致，否则 WAV 头与 PCM 数据
    // 的端序不同 → 播放出刺耳噪声。遍历全部 65536 个取值做穷尽比对。
    run {
        var mismatch = 0
        val buf = ByteArray(2)
        for (i in 0..0xFFFF) {
            val s = i.toShort()
            ByteConv.putShortLE(buf, 0, s.toInt())
            val ref = ByteConv.shortToByteArray(s)
            if (buf[0] != ref[0] || buf[1] != ref[1]) mismatch++
        }
        check("B1 65536 个取值下 putShortLE 与 shortToByteArray 字节完全一致", mismatch == 0, "不符=$mismatch")
    }

    // ---------- B2: 显式小端序（PCM WAV 要求）----------
    run {
        val buf = ByteArray(2)
        fun hex(v: Int): String { ByteConv.putShortLE(buf, 0, v); return "%02X%02X".format(buf[0], buf[1]) }
        check("B2a 0x1234 → 3412（小端）", hex(0x1234) == "3412", hex(0x1234))
        check("B2b -1 → FFFF", hex(-1) == "FFFF", hex(-1))
        check("B2c -32768 → 0080（小端）", hex(-32768) == "0080", hex(-32768))
        check("B2d 32767 → FF7F（小端）", hex(32767) == "FF7F", hex(32767))
    }

    // ---------- B3: emit() 的浮点→PCM16 转换与限幅 ----------
    // 复刻 emit 里的表达式：((x * 32767f).toInt()).coerceIn(-32768, 32767)
    // 伴奏 = 原始 - 人声，两路各自都可能越界（最大 ±2.0），必须靠 coerceIn 拦住，
    // 否则 toShort() 回绕 → 满量程反向爆音。
    run {
        fun pcm(x: Float) = (x * 32767f).toInt().coerceIn(-32768, 32767)
        check("B3a 0.0 → 0", pcm(0f) == 0, "${pcm(0f)}")
        check("B3b 1.0 → 32767（满量程）", pcm(1f) == 32767, "${pcm(1f)}")
        check("B3c -1.0 → -32767", pcm(-1f) == -32767, "${pcm(-1f)}")
        check("B3d 2.0（伴奏越界）→ 32767 不回绕", pcm(2f) == 32767, "${pcm(2f)}")
        check("B3e -2.0（伴奏越界）→ -32768 不回绕", pcm(-2f) == -32768, "${pcm(-2f)}")
        check("B3f 100.0 → 32767", pcm(100f) == 32767, "${pcm(100f)}")
        var outOfRange = 0
        var x = -2.0f
        while (x <= 2.0f) {
            val v = pcm(x)
            if (v < -32768 || v > 32767) outOfRange++
            x += 0.0001f
        }
        check("B3g 扫描 [-2,2] 全部落在 int16 域内", outOfRange == 0, "越界=$outOfRange")
    }

    // ---------- B4: emit 批量缓冲的冲刷边界算术 ----------
    // 注意：emit 是 separateLocked 内的局部函数，无法单独抽取，这里**按源码结构等价建模**，
    // 验证的是「冲刷条件与帧边界对齐」这一算术性质（不是抽取真实代码）。
    // 真实代码：缓冲 8192 字节，每帧 4 字节，条件 `if (pos + 4 > size) flush()`。
    run {
        val bufSize = 8192
        val frameBytes = 4
        check("B4a 8192 能被 4 整除（永不产生半帧）", bufSize % frameBytes == 0, "8192%4=${bufSize % frameBytes}")
        val framesPerFlush = bufSize / frameBytes
        check("B4b 每次冲刷恰好 2048 帧", framesPerFlush == 2048, "$framesPerFlush")

        var pos = 0
        var flushedBytes = 0
        val flushSizes = ArrayList<Int>()
        for (f in 0 until 5000) {
            if (pos + frameBytes > bufSize) { flushSizes.add(pos); flushedBytes += pos; pos = 0 }
            pos += frameBytes
        }
        if (pos > 0) { flushedBytes += pos; flushSizes.add(pos) }
        check("B4c 5000 帧 → 冲刷总字节 = 20000", flushedBytes == 5000 * frameBytes, "got=$flushedBytes")
        check("B4d 每次冲刷都是 4 的整数倍（无半帧）", flushSizes.all { it % frameBytes == 0 }, flushSizes.joinToString(","))
        check("B4e 前两次冲刷均为满缓冲 8192", flushSizes.size >= 2 && flushSizes[0] == 8192 && flushSizes[1] == 8192, flushSizes.take(3).joinToString(","))
        check("B4f 末次冲刷 = (5000 mod 2048) 帧 × 4 = 3616 字节", flushSizes.last() == (5000 % 2048) * frameBytes, "last=${flushSizes.last()} 期望=${(5000 % 2048) * frameBytes}")
    }

    println()
    if (failures == 0) {
        println("全部通过：LinearResampler 数值行为符合预期")
        kotlin.system.exitProcess(0)
    } else {
        println("失败 $failures 项")
        kotlin.system.exitProcess(1)
    }
}
