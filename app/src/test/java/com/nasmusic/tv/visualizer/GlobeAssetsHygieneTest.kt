package com.nasmusic.tv.visualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E41「世界」assets 目录的**打包卫生**门禁。
 *
 * ## 为什么需要这道门
 * `downlevel_libs.mjs` 要用 TypeScript + acorn 转译 ES5 产物。**2026-10-06 它的脚本本体与
 * 依赖都装在 `app/src/main/assets/globe/`** —— 而这个目录是 **assets**，Gradle 的
 * `mergeDebugAssets` 会把底下**一切**原样打进 APK。
 *
 * 2026-10-06 实测：`typescript@5.7.2` + `acorn@8.14.0` 的 `node_modules` 共 **23.3 MB**
 * 被塞进 debug APK，APK 从 ~47.9 MB 涨到 **56.2 MB**，而这些文件在运行时**一个都用不到**
 * （`shouldInterceptRequest` 只会按 `/globe/<name>` 取 assets，`node_modules` 里的东西
 * 永远不会被请求）。
 *
 * ⛔ `.gitignore` 挡不住这件事——它只管提交，不管打包。真正的护栏是本测试。
 *
 * **2026-10-07 已从根上拆掉这个雷**：脚本与两个 ES6 原版、`earth.jpg`、`cities.json`
 * 一起移出 assets，落到 `app/src/globe-upstream/`（模块内、`main` 源集之外，不进 APK）。
 * 于是「装依赖」与「打包」不再共用目录。但**搬一次不等于此后免疫**——本测试继续守住：
 * ① 依赖残留不得回 assets；② 上游原件不得回 assets（否则白白占 2.02 MiB）。
 *
 * ## ⚠️ 本测试的已知失效模式（2026-10-06 实测，勿删这段）
 * 这道门**只读文件系统、不引用任何 Gradle 输入**，因此：
 * - 若上一次 `testDebugUnitTest` 是绿的，而之后**只有** assets 目录下多了/少了文件、
 *   Kotlin/Java 源码与资源都没动，Gradle 可能把测试任务判为 **UP-TO-DATE** 而**根本不重跑** ——
 *   实测「新建一个假的 node_modules 后直接跑」就是绿的（假阴性）。
 * - 要真正复检，必须带 `--rerun-tasks`（或先 `--stop` 再跑）。
 *
 * 实践中这不构成漏网：真去装这些依赖的场景会同时改动 `app/src/globe-upstream/` 下的
 * 上游原件或重新生成 assets 里的 ES5 产物，那会真正改变 Gradle 输入、真正让本测试重跑。
 * 但**人工验证本门禁时必须记得这条**，否则会拿到一个「绿得没有意义」的结果。
 *
 * ## 为什么「定位失败要 fail 而不是 skip」
 * 与 `ScreenUiModeCoverageTest` / `FocusableSurfaceColorContractTest` 同口径：
 * 找不到目录就说明门禁本身失效了，静默跳过会让它变成一句永远为真的空话。
 */
class GlobeAssetsHygieneTest {

    @Test
    fun `node 依赖目录不得留在 assets 下（会被打进 APK）`() {
        val dir = File(globeAssetsDir(), "node_modules")
        assertFalse(
            "⛔ ${dir.absolutePath} 存在：assets 下的一切都会被 mergeDebugAssets 打进 APK。" +
                " 2026-10-06 实测多出 23.3 MB。\n" +
                "    处理：删掉它。确实需要重跑 ES5 转译时，先在别处装依赖，或装完立刻清理。\n" +
                "    参考 downlevel_libs.mjs 头部用法注释。",
            dir.exists()
        )
    }

    @Test
    fun `npm 安装产物不得留在 assets 下`() {
        val dir = globeAssetsDir()
        listOf("package.json", "package-lock.json").forEach { name ->
            val f = File(dir, name)
            assertFalse(
                "⛔ ${f.absolutePath} 存在：它是 `npm install --no-save` 的残留，" +
                    "同样会被打进 APK。用完必须清理（见 downlevel_libs.mjs 头部用法注释）。",
                f.exists()
            )
        }
    }

    /**
     * 正向自证：门禁**真的扫到了**那个目录，而不是因为路径写错而恒真。
     *
     * ⚠️ 「断言不存在」这种测试的固有风险是：路径写错 ⇒ 永远通过 ⇒ 门禁形同虚设。
     * 这里用一个**一定存在**的同目录文件证明定位有效。
     */
    @Test
    fun `正向自证 assets 目录定位有效（否则上面两条是恒真的空门禁）`() {
        val dir = globeAssetsDir()
        assertTrue(
            "定位失败：${dir.absolutePath} 不是目录（user.dir=${File("").absolutePath}）。" +
                "此时上面两条断言会**永远通过**——那比没有门禁更糟。",
            dir.isDirectory
        )
        listOf("globe.js", "index.html", "three.es5.js", "polyfill.es5.js").forEach { name ->
            assertTrue("定位失败：缺少预期资产 $name", File(dir, name).isFile)
        }
    }

    /**
     * 上游原件 / 构建输入**不得留在 assets 下**。
     *
     * 2026-10-07 实测这 5 个文件运行时**零加载**（剥注释扫 `globe.js` 的真实引用 +
     * 扫 Kotlin 侧有无别的加载路径，两者都为空），却合计占 APK **2.02 MiB / 4.2%**：
     * `earth.jpg` 1,461,877 + `three-globe.min.js` 443,800 + `three.min.js` 203,785
     * + `downlevel_libs.mjs` 4,890 + `cities.json` 730（字节为 APK 内压缩后大小）。
     *
     * ⛔ **移出而非删除**：两个 ES6 原版是 ES5 产物的**再生源与 diff 基线**
     * （`downlevel_libs.mjs` 就从它们读源），删了就永久丧失可回溯性。
     *
     * ⚠️ `cities.json` 尤其容易误判成「还在用」——它看着像静态数据，实际城市坐标是
     * Kotlin 经 `WorldGlobe.initCities(...)` 注入的，页面从不 fetch 它。
     */
    @Test
    fun `上游原件与构建输入不得留在 assets 下（不进包）`() {
        val dir = globeAssetsDir()
        UPSTREAM_MOVED_OUT.forEach { name ->
            val f = File(dir, name)
            assertFalse(
                "⛔ ${f.absolutePath} 回到了 assets 下：它是运行时**零加载**的构建输入，" +
                    "只会白白占 APK 体积（5 个合计 2.02 MiB / 4.2%）。\n" +
                    "    处理：移回 app/src/globe-upstream/。⛔ 不要删除——两个 ES6 原版是\n" +
                    "    ES5 产物的再生源与逐字节 diff 基线，删掉就永久丧失可回溯性。\n" +
                    "    参考 downlevel_libs.mjs 头部「本目录文件清单与保留策略」。",
                f.exists()
            )
        }
    }

    /**
     * 正向自证：上面那条「不得存在」不是空门禁 —— 5 个文件确实在 `app/src/globe-upstream/`。
     *
     * ⚠️ 与「定位失败要 fail 而不是 skip」同源：只断言「A 处没有」而不验证「A 真的搬走了」，
     * 一旦路径写错就永远通过。
     */
    @Test
    fun `正向自证 上游原件确实在 globe-upstream（否则上一条是恒真的空门禁）`() {
        val dir = globeUpstreamDir()
        UPSTREAM_MOVED_OUT.forEach { name ->
            assertTrue(
                "定位失败：缺少上游原件 $name（user.dir=${File("").absolutePath}）",
                File(dir, name).isFile
            )
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 2026-10-07 从 `assets/globe/` 移出到 `app/src/globe-upstream/` 的 5 个文件。 */
    private val UPSTREAM_MOVED_OUT = listOf(
        "three.min.js",
        "three-globe.min.js",
        "earth.jpg",
        "cities.json",
        "downlevel_libs.mjs"
    )

    /** 定位 `app/src/globe-upstream/`（构建输入 / 上游原件，不进 APK）。 */
    private fun globeUpstreamDir(): File {
        val candidates = listOf(
            File("src/globe-upstream"),
            File("app/src/globe-upstream"),
            File("../app/src/globe-upstream")
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到 app/src/globe-upstream 目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found != null
        )
        return found!!
    }

    /**
     * 定位 `assets/globe/`。
     *
     * AGP 单测的 `user.dir` 是**模块目录**（`…/app`），但同时尝试仓库根与相对上级。
     */
    private fun globeAssetsDir(): File {
        val candidates = listOf(
            File("src/main/assets/globe"),
            File("app/src/main/assets/globe"),
            File("../app/src/main/assets/globe")
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到 assets/globe 目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found != null
        )
        return found!!
    }
}