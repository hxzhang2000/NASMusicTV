package com.nasmusic.tv.visualizer.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * **门禁 G4**（§14.4）—— 枚举 ↔ 注册表一致性
 *
 * ## 这道门禁防的是什么
 *
 * `PhotoTransitionId` 一次写全 76 项（规划），但代码里只实现了一部分。
 * 抽池走 `PhotoTransitionRegistry.available()`，所以**池里不会有空转场**；
 * 但如果「枚举声明的分期」与「注册表实际实现」不一致，会出现两类静默故障：
 *
 * 1. **该实现的漏了** —— 某个 P0 效果忘了注册。`available()` 里没有它，
 *    所以不会抽到它（不炸），但**用户选它时没有任何反应**（`get()` 返回 `null`），
 *    表现为「设置里选了 A，画面却一直在用 B」。
 * 2. **不该有的多了** —— 把 P1 的效果提前注册了。功能上无害，但
 *    「P0 = 15 种」这个分期声明、以及 §14.3 的参数表就与代码脱节了。
 *
 * ## 为什么用「分期前沿」而不是「每个 id 都有实现」
 *
 * §14.4 表格里 G4 的原文是「**每个 `PhotoTransitionId` 都能 `get()` 到实现**」——
 * 那是**终态**（提交 12 后 43 种）的写法。本方案 P2 的 33 种**不在计划内**
 * （阶段 12 只补到 P1 = 43），所以「76 项全都有实现」从阶段 5 到收尾都**不可能成立**。
 *
 * ⇒ 门禁改成：**注册表恰好等于「≤ 当前已完成分期」的全部项**。
 * 这样从阶段 5 到收尾**一直为真**，且「漏一个 / 多一个」都能判出。
 *
 * ⚠️ 前沿用 [EXPECTED_FRONTIER] **硬编码**而不是从注册表反推 ——
 * 若从 `available` 反推（`available.maxOf { it.phase }`），把**整期**注册项误删后
 * 前沿会一起下沉，门禁静默通过（28 个 P1 效果全没了也不报）。
 * 硬编码的代价是：**阶段 12 完成 P1 后必须把这里改成 `Phase.P1`**。
 *
 * ## 为什么需要 Robolectric
 *
 * `PhotoTransitionRegistry` 的初始化会**真的构造**全部 15 个转场实例，其中
 * `IrisCircleTransition` 持有 `Path()`（`androidx.compose.ui.graphics.Path` → `AndroidPath`
 * → `android.graphics.Path`）、`NoiseDissolveTransition` 持有 `Paint()`。
 * 纯 JVM 下 `android.*` 是桩类，构造即抛 —— 所以本测试必须跑在 Robolectric 上。
 * （对比：`PhotoScaleModeTest` 只碰纯几何，所以是纯 JVM。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoTransitionRegistryTest {

    // ────────────────────── ① 枚举本身 ──────────────────────

    /** §4.2 完整清单 = 76 项（A4 + B9 + C7 + D10 + E8 + F6 + G8 + H7 + I5 + J7 + K5） */
    @Test
    fun `enum declares all 76 planned effects`() {
        assertEquals(
            "枚举项数应与 §4.2 清单一致（少一项 ⇒ 某个效果永远不会出现在选择器里）",
            76,
            PhotoTransitionId.entries.size,
        )
        val p0 = PhotoTransitionId.implemented(PhotoTransitionId.Phase.P0)
        assertEquals("P0 应为 15 项（§14.3 参数表）", 15, p0.size)
        val p1 = PhotoTransitionId.implemented(PhotoTransitionId.Phase.P1)
        assertEquals("P1 累计应为 43 项（15 + 28）", 43, p1.size)
    }

    /**
     * T5.2 验收原文：「76 项全部有 `phase`」。
     *
     * `phase` 是构造参数、类型非空 ⇒ 「有没有」编译期就保证了；这里验的是
     * **三个分期都真的有人**（`phase` 全部写成同一个值也能编译通过），
     * 以及元信息本身合法（时长 > 0、显示名非空）。
     */
    @Test
    fun `every entry has usable metadata and all three phases are populated`() {
        for (id in PhotoTransitionId.entries) {
            assertTrue("$id 的基准时长应为正数，实际 ${id.baseDurationMs}", id.baseDurationMs > 0)
            assertTrue("$id 的显示名不能为空", id.displayName.isNotBlank())
        }
        for (phase in PhotoTransitionId.Phase.entries) {
            val n = PhotoTransitionId.entries.count { it.phase == phase }
            assertTrue("分期 $phase 一项都没有 —— phase 参数疑似被抄成同一个值", n > 0)
        }
        assertEquals(
            "三个分期的项数之和应等于总项数（15 + 28 + 33）",
            76,
            PhotoTransitionId.Phase.entries.sumOf { p -> PhotoTransitionId.entries.count { it.phase == p } },
        )
    }

    /** 枚举里不允许出现重复显示名 —— 选择器上会出现两个一模一样的条目 */
    @Test
    fun `display names are unique`() {
        val dup = PhotoTransitionId.entries.groupBy { it.displayName }.filterValues { it.size > 1 }
        assertTrue("显示名重复：${dup.keys}", dup.isEmpty())
    }

    // ────────────────────── ② 注册表 ↔ 枚举（G4 核心）──────────────────────

    @Test
    fun `registry exactly matches every effect up to the completed phase`() {
        val available = PhotoTransitionRegistry.available()
        val violations = registryViolations(available, EXPECTED_FRONTIER)
        assertTrue(
            "注册表与分期声明不一致：$violations（漏注册 ⇒ 选中它没有任何反应；越期注册 ⇒ 分期声明与代码脱节）",
            violations.isEmpty(),
        )
    }

    /** 「当前分期应实现的」必须**逐项**能取到实现（§14.4 G4 原文的当前分期版） */
    @Test
    fun `every effect of the completed phase has an implementation`() {
        val available = PhotoTransitionRegistry.available()
        for (id in PhotoTransitionId.implemented(EXPECTED_FRONTIER)) {
            assertNotNull(
                "$id 属于已完成分期 ${EXPECTED_FRONTIER}，注册表却没有实现 —— " +
                    "用户选中它时 get() 返回 null，画面会一直停在上一个效果",
                PhotoTransitionRegistry.get(id),
            )
            assertTrue("$id 应出现在 available() 里（随机池由它构造）", id in available)
        }
        assertTrue(
            "已实现数量应 ≥ 15（P0）",
            PhotoTransitionRegistry.implementedCount >= PhotoTransitionId.implemented(PhotoTransitionId.Phase.P0).size,
        )
    }

    /**
     * `get()` 必须是一次**真查找** —— 未注册的 id 返回 `null`，不是兜底返回某个效果。
     *
     * ⚠️ 这条断言在**任何分期**都成立（对未实现的 id 一律 `null`），所以不用随分期改。
     */
    @Test
    fun `get returns null exactly for the ids that are not registered`() {
        val available = PhotoTransitionRegistry.available()
        for (id in PhotoTransitionId.entries) {
            val impl = PhotoTransitionRegistry.get(id)
            if (id in available) {
                assertNotNull("$id 在 available() 里，get() 却返回 null", impl)
            } else {
                assertTrue(
                    "$id 不在 available() 里，get() 却返回了实现（${impl?.id}）⇒ " +
                        "注册表与 available() 不同步，G4 会失去判别力",
                    impl == null,
                )
            }
        }
        assertTrue(
            "本方案只实现到 P1（43 种），不可能 76 项全部注册 —— 若全部注册了，" +
                "说明有人实现了 P2 却没更新本门禁的分期前沿",
            available.size < PhotoTransitionId.entries.size,
        )
    }

    /** 实现回报的 `id` 必须与注册键一致（否则降级判断 / 选择器高亮会错位） */
    @Test
    fun `each implementation reports the id it is registered under`() {
        for (id in PhotoTransitionRegistry.available()) {
            val impl = PhotoTransitionRegistry.get(id)
            assertNotNull("$id 应能取到实现", impl)
            assertEquals("$id 的实现回报了不同的 id —— 会导致随机池与降级判断错位", id, impl!!.id)
        }
    }

    // ────────────────────── ③ 时长参数（T5.3 验收）──────────────────────

    /**
     * §14.3 参数表逐项对齐。
     *
     * ⚠️ `BLINDS_*` 是 **900ms + 逐块 stagger 300ms** ⇒ 基准总时长取 `1_200`。
     * 写成 `900` 会让末块还没滑完就被切断。
     */
    @Test
    fun `base durations match the parameter table`() {
        val expected = mapOf(
            PhotoTransitionId.CROSSFADE to 800,
            PhotoTransitionId.FADE_BLACK to 800,
            PhotoTransitionId.SLIDE_LEFT to 500,
            PhotoTransitionId.SLIDE_RIGHT to 500,
            PhotoTransitionId.SLIDE_UP to 500,
            PhotoTransitionId.SLIDE_DOWN to 500,
            PhotoTransitionId.ZOOM_IN to 700,
            PhotoTransitionId.ZOOM_OUT to 700,
            PhotoTransitionId.IRIS_CIRCLE to 700,
            PhotoTransitionId.WIPE_LINEAR to 700,
            PhotoTransitionId.BLINDS_H to 1_200,
            PhotoTransitionId.BLINDS_V to 1_200,
            PhotoTransitionId.NOISE_DISSOLVE to 1_000,
            PhotoTransitionId.LIGHT_SWEEP to 600,
            PhotoTransitionId.SPECTRUM_WIPE to 700,
        )
        assertEquals("P0 参数表应覆盖全部 15 项", 15, expected.size)
        for ((id, ms) in expected) {
            assertEquals("$id 的基准时长应为 ${ms}ms（§14.3）", ms, id.baseDurationMs)
        }
        // BLINDS 的 900 + 300 分解：基准必须大于单块时长，否则末块被截断
        assertEquals("百叶窗基准时长 = 单块 900ms + 错落 300ms", 900 + 300, PhotoTransitionId.BLINDS_H.baseDurationMs)
    }

    /** `requiresSequential` 只应出现在「暗场语义」的项上（§5.2） */
    @Test
    fun `sequential flags match the dark frame semantics`() {
        val sequential = PhotoTransitionId.entries.filter { it.requiresSequential }.map { it.name }.toSet()
        assertTrue(
            "经黑场 / 白闪 / 电影黑边收缩 必须走串行模型（交叉模型画不出「经黑场」），实际 $sequential",
            sequential.containsAll(setOf("FADE_BLACK", "FADE_WHITE", "CINEMATIC_BARS")),
        )
        assertTrue("CROSSFADE 不该是串行（它是最典型的交叉）", !PhotoTransitionId.CROSSFADE.requiresSequential)
    }

    // ────────────────────── ④ 绘制路径无 createBitmap（T5.2 验收）──────────────────────

    /**
     * T5.2 验收原文：「绘制路径无 `createBitmap`」。
     *
     * ⚠️ 口径：**只扫 `transitions/` 目录**（= 全部 `render` 实现所在处）。
     * `MaskCache.kt` 里**确实有** `createBitmap`，那是**允许**的 —— 它只在
     * `prepare()`（切转场 / 切画质时）跑一次，不在每帧的绘制路径上。
     * 简单全文搜 `createBitmap` 会把这条正确写法一起判违规。
     */
    @Test
    fun `transitions never allocate bitmaps on the draw path`() {
        val dir = transitionsDir()
        val files = dir.listFiles { f -> f.isFile && f.extension == "kt" }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("转场实现目录为空（扫描路径不对？）：${dir.absolutePath}", files.size >= 7)

        // 空转防线：目录里必须真有代码可扫，否则「0 命中」毫无意义
        val totalLines = files.sumOf { it.readLines().size }
        assertTrue("转场目录只有 $totalLines 行可扫，太少 —— 疑似扫到了空目录", totalLines >= 400)

        val hits = files.flatMap { file -> scanForbidden(file).map { "${file.name}:$it" } }
        assertTrue(
            "绘制路径不得创建位图（位图必须来自 MaskCache，它只在 prepare 里生成）：$hits",
            hits.isEmpty(),
        )
    }

    /**
     * **负向自证**：证明上面那个扫描器**不是空转的**。
     *
     * 往一个临时文件里写上真正的禁用调用，扫描器必须报出来；
     * 同时给一个干净文件，必须报 0 —— 两个方向都验，才算证明了判别力。
     */
    @Test
    fun `negative proof - the draw path scanner is live`() {
        val dirty = File.createTempFile("photo-draw-scan", ".kt")
        val clean = File.createTempFile("photo-draw-scan-clean", ".kt")
        try {
            dirty.writeText(
                """
                // 故意写满禁用调用
                val a = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                val b = Bitmap.createScaledBitmap(a, 128, 128, true)
                val c = BitmapFactory.decodeStream(stream)
                val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                """.trimIndent(),
            )
            clean.writeText(
                """
                // 干净写法：位图从缓存取，绘制期零分配
                val mask = masks.noise()
                drawImage(mask, srcRect, dstRect)
                """.trimIndent(),
            )

            val dirtyHits = scanForbidden(dirty)
            val cleanHits = scanForbidden(clean)
            assertTrue("扫描器漏判：禁用调用一个都没扫到 ⇒ 它是空转的", dirtyHits.size >= 4)
            assertTrue("扫描器误报：干净文件被判出 $cleanHits", cleanHits.isEmpty())
        } finally {
            dirty.delete()
            clean.delete()
        }
    }

    // ────────────────────── ⑤ 负向自证（G4 判别力）──────────────────────

    /**
     * **负向自证**：漏注册**必须**被判出。
     *
     * §14.4 G4 原文的负向自证是「注释掉一个注册项 ⇒ 断言失败」。
     * 这里不真的去改生产代码（测试不能改源码），而是把「注释掉 `CROSSFADE`」
     * 这件事**如实模拟成 `available - CROSSFADE`**，喂给同一套判定逻辑。
     */
    @Test
    fun `negative proof - a missing registration is caught`() {
        val real = PhotoTransitionRegistry.available()
        assertTrue("对照组：真实注册表不该有违例", registryViolations(real, EXPECTED_FRONTIER).isEmpty())

        val broken = real - PhotoTransitionId.CROSSFADE
        val violations = registryViolations(broken, EXPECTED_FRONTIER)
        assertTrue("注释掉 CROSSFADE 后必须判出问题，实际 $violations", violations.isNotEmpty())
        assertTrue(
            "违例信息应指名道姓说是 CROSSFADE，实际 $violations",
            violations.any { it.contains("CROSSFADE") },
        )
        assertEquals("去掉一项后注册表少一项", real.size - 1, broken.size)
    }

    /**
     * **负向自证**：越期注册**必须**被判出。
     *
     * 把 P1 的 `FADE_WHITE` 提前塞进 P0 注册表 —— 功能上无害，
     * 但「P0 = 15 种」的分期声明就不再成立了。
     */
    @Test
    fun `negative proof - a stray registration is caught`() {
        val real = PhotoTransitionRegistry.available()
        assertTrue("前置条件：FADE_WHITE 属 P1，本分期不该注册", PhotoTransitionId.FADE_WHITE !in real)

        val broken = real + PhotoTransitionId.FADE_WHITE
        val violations = registryViolations(broken, EXPECTED_FRONTIER)
        assertTrue("越期注册 FADE_WHITE 后必须判出问题，实际 $violations", violations.isNotEmpty())
        assertTrue(
            "违例信息应指名道姓说是 FADE_WHITE，实际 $violations",
            violations.any { it.contains("FADE_WHITE") },
        )
    }

    /**
     * **负向自证**：分期前沿**硬编码**这件事本身是有意义的。
     *
     * 若改成从注册表反推前沿（`available.maxOf { it.phase }`），
     * 把**整期**注册项删掉后前沿会一起下沉、门禁静默通过。
     *
     * ⚠️ 这里的前沿用 **`Phase.P1` 显式给出**，而不是 [EXPECTED_FRONTIER] ——
     * 因为当前阶段 [EXPECTED_FRONTIER] 就是 P0，「删掉 P1」在 P0 阶段本就无影响，
     * 拿它当断言会变成一条**恒真**的空转用例（首轮实测即因此失败）。
     * 显式给 P1 之后，本用例在**任何阶段**都验的是同一个性质：
     * 「声明应做到 P1，但 P1 整批丢失」。
     */
    @Test
    fun `negative proof - a derived frontier would silently pass a wiped phase`() {
        // 模拟：注册表里 P1 的项被整批误删（只剩 P0）
        val wiped = PhotoTransitionRegistry.available()
            .filter { it.phase == PhotoTransitionId.Phase.P0 }
            .toSet()

        // ① 硬编码前沿（本项目采用）：声明「应做到 P1」⇒ 判出全部 28 项缺失
        val hardcoded = registryViolations(wiped, PhotoTransitionId.Phase.P1)
        assertTrue("硬编码前沿 P1 必须判出「整期被删」，实际 $hardcoded", hardcoded.isNotEmpty())
        assertTrue(
            "应报出 28 项漏注册（P1 累计 43 − P0 的 15），实际 ${hardcoded.size}：$hardcoded",
            hardcoded.size == 28,
        )

        // ② 反推前沿（被否决的写法）：前沿跟着下沉到 P0 ⇒ 一条都不报
        val derivedFrontier = wiped.maxOf { it.phase }
        assertTrue(
            "反推前沿在这个场景下会下沉到 P0，实际 $derivedFrontier",
            derivedFrontier == PhotoTransitionId.Phase.P0,
        )
        assertTrue(
            "反推前沿会静默通过（28 项全没了也不报）—— 这正是本门禁不采用反推的原因",
            registryViolations(wiped, derivedFrontier).isEmpty(),
        )
    }

    // ────────────────────── 内部实现 ──────────────────────

    /**
     * 一致性判定（**与生产代码无关，纯粹是门禁的判别逻辑**）。
     *
     * ⚠️ 之所以抽成纯函数，是为了能对**故意做坏的输入**跑同一套判定
     * （见上面三个负向自证）—— 否则「断言通过」只证明「当前数据恰好符合」，
     * 不证明「这道门禁真的会拦人」。
     */
    private fun registryViolations(
        available: Set<PhotoTransitionId>,
        frontier: PhotoTransitionId.Phase,
    ): List<String> {
        val expected = PhotoTransitionId.implemented(frontier).toSet()
        return buildList {
            (expected - available).sortedBy { it.ordinal }.forEach { add("漏注册：$it") }
            (available - expected).sortedBy { it.ordinal }.forEach { add("越期注册：$it") }
        }
    }

    /** 定位 `transitions/` 源码目录（口径与 `SmallTouchTargetScanTest` 一致） */
    private fun transitionsDir(): File {
        val root = mainSourceRoot()
        val dir = File(root, "visualizer/photo/transitions")
        assertTrue("找不到转场实现目录：${dir.absolutePath}", dir.isDirectory)
        return dir
    }

    /**
     * AGP 单测的 `user.dir` 是**模块目录**（`…/app`），但为稳妥起见同时尝试仓库根。
     * 定位失败时**直接失败**而不是跳过 —— 否则这道门禁会被静默禁用。
     */
    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertNotNull(
            "找不到源码目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found,
        )
        return found!!
    }

    /** 返回命中行号（1 起） */
    private fun scanForbidden(file: File): List<Int> =
        file.readLines().withIndex()
            .filter { (_, line) -> FORBIDDEN.any { line.contains(it) } }
            .map { (i, _) -> i + 1 }

    private companion object {

        /**
         * 本阶段已完成的最高分期。
         *
         * ⛔ **阶段 12 补齐 P1 后，这里要改成 `Phase.P1`** —— 忘记改的话，
         * 那 28 个新注册的 P1 效果会被判成「越期注册」而报红（不会静默漏过）。
         */
        val EXPECTED_FRONTIER = PhotoTransitionId.Phase.P0

        /**
         * 绘制路径上禁止出现的**位图分配**调用。
         *
         * ⚠️ 只列「分配位图」这一类，不列 `Paint(` / `Path(` / `Rect(` ——
         * 那些在 `prepare` 里作为**成员变量**构造是正确写法（每帧复用），
         * 全文搜会误报；而「每帧 new 一个 Path」这种真正的问题靠**代码审查**把关
         * （T5.1 验收原文即「`render` 内无任何分配（代码审查）」）。
         */
        val FORBIDDEN = listOf(
            "createBitmap",
            "createScaledBitmap",
            "BitmapFactory.decode",
        )
    }
}
