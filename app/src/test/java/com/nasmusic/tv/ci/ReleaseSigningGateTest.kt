package com.nasmusic.tv.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `.github/workflows/build.yml` 签名 fail-fast 的门禁测试（G4，权限与签名统一方案 §7.4 / D5）。
 *
 * ## 背景（为什么必须有这条门禁）
 *
 * v2.38.3 之前 `Set up signing keystore` 步骤是纯双模式：secrets 缺失时**静默回退**到
 * `keytool -genkey` 生成的一次性密钥 —— 构建照样成功、APK 照样产出，只是签名是随机的，
 * 错误要等到用户 `adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 才暴露。
 * 更隐蔽的是 `CRYPTO_PASSPHRASE` 缺失时的占位值回退：签名正确（装得上）但 AES 派生
 * 口令不同 ⇒ 升级后解不开已存凭据，要等用户发现 NAS 连不上、重新输密码才知道坏了。
 *
 * D5 把 push/tag 路径改成 fail-fast（secrets 缺失 `exit 1`），本门禁锁住它不被顺手删掉。
 *
 * ## 断言口径（方案 §7.4）
 *
 * | # | 内容 |
 * |---|---|
 * | L1 | 「push / tag 必须走正式签名」的 fail-fast 分支存在 |
 * | L2 | `keytool -genkey` 只出现在 PR/fallback 分支，且该分支有「不可覆盖安装」说明 |
 * | L3 | 占位 passphrase 不得出现在正式签名路径（模式 A 块）里 |
 * | L4 | 负向自证：删掉 fail-fast 事件判断后 L1 必须挂 |
 *
 * ⚠️ 解析的是**文本**而非执行 CI —— 不需要网络与 secrets，单测可离线跑。
 * ⚠️ 断言一律 `assertTrue` / `assertFalse`：Kotlin 的 `assert` 在测试 JVM 上是空操作。
 * 详见 `docs/archive/permission-and-signing-plan.md` §7.4 / §14.4。
 */
class ReleaseSigningGateTest {

    // ─────────────────── L1：fail-fast 分支存在 ───────────────────

    @Test
    fun `push与tag路径必须存在secrets缺失即失败的fail-fast`() {
        val text = buildYml()
        // 空转自证：确认读到的是 build.yml 本体（步骤名 + 构建命令）
        assertTrue(
            "build.yml 缺少 Set up signing keystore 步骤 —— 可能读错文件，扫描结果不可信",
            text.contains("Set up signing keystore"),
        )
        assertTrue(
            "build.yml 缺少 assembleRelease —— 可能读错文件，扫描结果不可信",
            text.contains("assembleRelease"),
        )

        assertTrue(
            "build.yml 里找不到「非 pull_request 即 fail-fast」的事件判断（D5）。\n" +
                "后果：push/tag 上 secrets 缺失会静默回退一次性密钥，产出装不上的 APK；\n" +
                "      CRYPTO_PASSPHRASE 缺失则产出「装得上但解不开已存凭据」的 APK。\n" +
                "正确做法见 docs/archive/permission-and-signing-plan.md §14.4。",
            hasFailFast(text),
        )
    }

    // ─────────────────── L2：keytool 只在 fallback 分支 ───────────────────

    @Test
    fun `keytool一次性密钥只允许出现在fallback分支且带不可覆盖安装说明`() {
        val text = buildYml()
        val lines = text.lines()
        val keytoolIdx = lines.indexOfFirst { it.contains("keytool -genkey") }
        assertTrue(
            "build.yml 里没有 keytool -genkey —— 模式 B（PR fallback）被删了？\n" +
                "PR（尤其 fork）拿不到 secrets，需要保留 throwaway 路径保证 CI 可构建。",
            keytoolIdx >= 0,
        )

        val lastElseBefore = lines.subList(0, keytoolIdx).indexOfLast { it.trim() == "else" }
        assertTrue(
            "keytool -genkey 不在 else 分支里 —— 一次性密钥生成只能作为模式 B 存在（D5）",
            lastElseBefore >= 0,
        )
        // else 与 keytool 之间不得出现模式 A 的签名头（证明 keytool 没被包进正式签名路径）
        val between = lines.subList(lastElseBefore, keytoolIdx).joinToString("\n")
        assertFalse(
            "keytool -genkey 与 else 之间出现了 base64 -d（模式 A 签名头）——" +
                "一次性密钥生成进了正式签名路径",
            between.contains("base64 -d"),
        )

        // fallback 分支必须显式说明「产物不可覆盖安装」
        assertTrue(
            "build.yml 缺少「不可覆盖安装」的显式说明（G4 L2）—— 这句提示是防止后人" +
                "把 throwaway 产物当正式包用的唯一防线",
            text.contains("不可覆盖安装"),
        )
    }

    // ─────────────────── L3：占位 passphrase 不进正式路径 ───────────────────

    @Test
    fun `占位passphrase不得出现在正式签名路径`() {
        val lines = buildYml().lines()
        val modeAStart = lines.indexOfFirst { it.contains("if [ -n \"\$SIGNING_KEYSTORE_BASE64\" ]") }
        assertTrue(
            "找不到模式 A 入口判断（if [ -n ... ]）—— build.yml 签名步骤结构变了，需同步更新本测试",
            modeAStart >= 0,
        )
        val elseOffset = lines.drop(modeAStart + 1).indexOfFirst { it.trim() == "else" }
        assertTrue(
            "找不到模式 A 之后的 else —— 模式 B fallback 不见了",
            elseOffset >= 0,
        )
        val modeAEnd = modeAStart + 1 + elseOffset

        val placeholderLines = lines.withIndex().filter { it.value.contains("ci-build-only-placeholder") }
        assertTrue(
            "占位值 ci-build-only-placeholder 从 build.yml 消失了 —— 若模式 B 也删了占位值，" +
                "PR 构建的 cryptoPassphrase 会是空值，packageRelease 的 guard 会把 CI 挂掉",
            placeholderLines.isNotEmpty(),
        )
        val inModeA = placeholderLines.filter { it.index in modeAStart..modeAEnd }
        assertTrue(
            "占位 passphrase 出现在模式 A（正式签名路径）：第 ${inModeA.map { it.index + 1 }} 行。\n" +
                "后果：push/tag 上漏配 CRYPTO_PASSPHRASE 时静默回退占位值 ⇒ APK 签名正确" +
                "但解不开升级前存的凭据（§14.2 最隐蔽的失败模式）。\n" +
                "正式路径的非空必须由 fail-fast 保证，占位值只允许存在于模式 B。",
            inModeA.isEmpty(),
        )
    }

    // ─────────────────── L4：负向自证 ───────────────────

    /** 负向自证：把事件判断行废掉（模拟真实回归「顺手删 fail-fast」），L1 必须判负。 */
    @Test
    fun `删掉fail-fast事件判断后L1必须判负`() {
        val text = buildYml()
        // ⚠️ 用字面串替换而非 regex：regex 里裸 $ 是行尾锚（永不匹配），
        //    raw string 里 \$ 又不拦 K2 的模板插 —— 普通串 + \$ 转义最直白。
        val failFastGate = "if [ \"\$GITHUB_EVENT_NAME\" != \"pull_request\" ]; then"
        val sabotaged = text.replace(failFastGate, "# fail-fast removed (sabotage test)")
        assertTrue(
            "负向样本没有被实际修改 —— 破坏性替换失配，负向自证在空转",
            sabotaged != text,
        )
        assertFalse(
            "护栏失效：删掉 fail-fast 事件判断后 hasFailFast 仍为 true ——" +
                "「扫描通过」只是护栏空转",
            hasFailFast(sabotaged),
        )
    }

    // ─────────────────── 辅助 ───────────────────

    /**
     * fail-fast 判定：事件门（非 pull_request）+ 两个 secrets 空检查各自 300 字符内
     * 跟着 `exit 1`。三条都成立才算存在。
     */
    private fun hasFailFast(text: String): Boolean {
        val eventGate = Regex("""GITHUB_EVENT_NAME[^\n]*!=[^\n]*pull_request""").containsMatchIn(text)
        if (!eventGate) return false
        return exitsAfterEmptyCheck(text, "-z \"\$SIGNING_KEYSTORE_BASE64\"") &&
            exitsAfterEmptyCheck(text, "-z \"\$CRYPTO_PASSPHRASE\"")
    }

    private fun exitsAfterEmptyCheck(text: String, check: String): Boolean {
        val idx = text.indexOf(check)
        if (idx < 0) return false
        val window = text.substring(idx, minOf(idx + 300, text.length))
        return Regex("""exit\s+1""").containsMatchIn(window)
    }

    private fun buildYml(): String {
        val candidates = listOf(
            File("../.github/workflows/build.yml"), // user.dir = app/
            File(".github/workflows/build.yml"), // user.dir = 仓库根
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到 build.yml（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found != null,
        )
        return found!!.readText()
    }
}
