package com.nasmusic.tv.backend.photo

import com.nasmusic.tv.util.PermissionHelper.PhotoPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 照片墙授权判据门禁（§6.2 / §9.4 / §9.6）
 *
 * ## 为什么这些判据值得单独做门禁
 *
 * 它们写错之后**编译过、lint 过、单测过**，只在真机上以「功能静默失效」的形式出现：
 *
 * | 写错的写法 | 真机症状 |
 * |---|---|
 * | 二态判定（`state != FULL` 即回弹） | Android 14+ 选了「仅选择照片」的用户，**每次重启都被弹回关**（风险 R27） |
 * | 把 `PARTIAL` 当被拒 | 用户明明选了照片，图库来源却打不开 |
 * | 用「存过目录没有」代替查 `persistedUriPermissions` | 目录授权被系统回收后，设置页仍显示「已选目录」但一张都读不到 |
 *
 * ⇒ 每个判据都配一条**负向自证**：把错误写法如实模拟进来，断言正确判据必须与它分歧。
 * 若分歧数为 0，说明门禁根本区分不出这两种写法（空转），此时断言失败。
 */
class PhotoWallAccessPolicyTest {

    /** ⚠️ 用 `entries` 而不是手写四个常量：以后新增状态时，本门禁会自动覆盖到它 */
    private val allStates: List<PhotoPermissionState> = PhotoPermissionState.entries.toList()

    // ── ① 回弹判据（§6.2） ────────────────────────────────────────────────

    @Test
    fun `only a denied permission rolls the gallery switch back`() {
        val rolledBack = allStates.filter {
            PhotoWallAccessPolicy.shouldRollbackGallerySwitch(galleryEnabled = true, state = it)
        }
        assertEquals(
            "开关开着时，只有「被拒」才该回弹 —— PARTIAL 是有效授权（§9.6）",
            listOf(PhotoPermissionState.DENIED),
            rolledBack,
        )
    }

    @Test
    fun `negative proof - both wrong predicates disagree exactly where they should`() {
        val correct: (PhotoPermissionState) -> Boolean = {
            PhotoWallAccessPolicy.shouldRollbackGallerySwitch(galleryEnabled = true, state = it)
        }

        // ── 错法 ①「二态判定」：只认「全部允许」为已授权，把 PARTIAL 当未授权 ──
        //
        // 这正是风险 R27 的成因：Android 14+「仅选择照片」下 `READ_MEDIA_IMAGES`
        // 只是**会话级**授予，重启后它就是 DENIED ⇒ 只判它的实现会把
        // 「已经授权了部分照片」的用户误判成被拒、把开关弹回去。
        //
        // ⚠️ 如实建模时**必须把 `FULL_LEGACY` 也算作已授权** —— 真实的错误实现是
        //    「API ≤32 查 `READ_EXTERNAL_STORAGE`」，那一路本来就会判成已授权。
        //    若写成 `state != FULL`，那是**另一个**错法（见 ②）。
        val wrongTwoState: (PhotoPermissionState) -> Boolean = {
            it != PhotoPermissionState.FULL && it != PhotoPermissionState.FULL_LEGACY
        }
        assertEquals(
            "「二态判定」必须**恰好**在 PARTIAL 一处与正确判据分歧 —— " +
                "为 0 说明本门禁区分不出这两种写法（空转）",
            listOf(PhotoPermissionState.PARTIAL),
            allStates.filter { wrongTwoState(it) != correct(it) },
        )

        // ── 错法 ②「漏了 legacy 态」：把 API ≤32 的 `FULL_LEGACY` 也当成未授权 ──
        //
        // 这是同一片雷区里的另一种错法：API ≤32 上 `READ_EXTERNAL_STORAGE` 已授予，
        // 照片完全可读，却被判成要回弹。
        // ⚠️ 本条是**实测补出来的** —— 首版门禁只写了错法 ①，跑出 2 处分歧才发现
        //    `FULL_LEGACY` 也在里面。留着它，两种错法就都被钉住了。
        val wrongIgnoreLegacy: (PhotoPermissionState) -> Boolean = {
            it != PhotoPermissionState.FULL
        }
        assertEquals(
            "「漏了 legacy 态」必须恰好在 PARTIAL 与 FULL_LEGACY 两处与正确判据分歧",
            listOf(PhotoPermissionState.PARTIAL, PhotoPermissionState.FULL_LEGACY),
            allStates.filter { wrongIgnoreLegacy(it) != correct(it) },
        )
    }

    @Test
    fun `an already-off switch never triggers a rollback`() {
        allStates.forEach { state ->
            assertFalse(
                "开关本来就是关的，不该产生回弹（否则每次 onResume 都会平白弹一次提示）: $state",
                PhotoWallAccessPolicy.shouldRollbackGallerySwitch(galleryEnabled = false, state = state),
            )
        }
    }

    // ── ② 是否需要申请 / 是否可读（§9.4 / §9.6） ──────────────────────────

    @Test
    fun `permission is requested only when denied`() {
        assertEquals(
            "只有被拒时才弹系统对话框；PARTIAL 已有可用授权，重复弹窗只会烦人",
            listOf(PhotoPermissionState.DENIED),
            allStates.filter { PhotoWallAccessPolicy.needsPermissionRequest(it) },
        )
    }

    @Test
    fun `partial access counts as readable`() {
        assertEquals(
            "只有被拒才不可读",
            listOf(PhotoPermissionState.DENIED),
            allStates.filterNot { PhotoWallAccessPolicy.grantsGalleryAccess(it) },
        )
        assertTrue(
            "部分授权下「用户已选中的那批」是可读的 ⇒ 必须算可读",
            PhotoWallAccessPolicy.grantsGalleryAccess(PhotoPermissionState.PARTIAL),
        )
    }

    @Test
    fun `only partial access shows the reselect entry`() {
        assertEquals(
            "「重新选择照片」入口只在部分授权态出现（§9.6 规则 3）",
            listOf(PhotoPermissionState.PARTIAL),
            allStates.filter { PhotoWallAccessPolicy.isPartial(it) },
        )
    }

    // ── ③ SAF 目录授权的有效性（§9.4） ───────────────────────────────────

    @Test
    fun `directory uri is cleared only when the system no longer persists it`() {
        assertFalse(
            "从未选过目录 ⇒ 无可清",
            PhotoWallAccessPolicy.shouldClearDirectoryUri(storedUri = "", persistedUris = listOf(TREE_URI)),
        )
        assertFalse(
            "仍在系统持久授权列表里 ⇒ 不能清（否则用户每次启动都要重选，违背「授权后无需再次授权」）",
            PhotoWallAccessPolicy.shouldClearDirectoryUri(TREE_URI, listOf(TREE_URI)),
        )
        assertTrue(
            "已被系统回收 ⇒ 必须清（否则设置页显示「已选目录」却一张都读不到）",
            PhotoWallAccessPolicy.shouldClearDirectoryUri(TREE_URI, emptyList()),
        )
        assertTrue(
            "列表里只有别的目录 ⇒ 同样算失效",
            PhotoWallAccessPolicy.shouldClearDirectoryUri(TREE_URI, listOf(OTHER_TREE_URI)),
        )
    }

    @Test
    fun `negative proof - ignoring the persisted list would keep a dead directory forever`() {
        // 错误写法：只看「存过没有」，完全不查系统持久授权列表。
        fun wrongNeverClears(storedUri: String, persistedUris: Collection<String>): Boolean =
            storedUri.isBlank()

        assertFalse(
            "错误写法在「授权已被系统回收」这一行上判错（认为仍有效）—— 若为 true 说明本门禁空转",
            wrongNeverClears(TREE_URI, emptyList()),
        )
        assertTrue(
            "正确判据必须与错误写法在这一行上分歧",
            PhotoWallAccessPolicy.shouldClearDirectoryUri(TREE_URI, emptyList()),
        )
    }

    private companion object {
        /** 外接卷（UUID 形式卷 ID，非 `primary`） */
        const val TREE_URI =
            "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3APhotos"

        const val OTHER_TREE_URI =
            "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM"
    }
}
