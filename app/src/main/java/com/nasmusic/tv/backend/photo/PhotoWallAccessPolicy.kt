package com.nasmusic.tv.backend.photo

import com.nasmusic.tv.util.PermissionHelper.PhotoPermissionState

/**
 * 照片墙「访问授权」的**纯决策逻辑**（§6.2 / §9.4 / §9.6）
 *
 * ## 为什么单独抽一个对象
 *
 * 授权相关的判断有三处容易写错、且写错后**只在真机上才看得出来**
 * （模拟器 / 单测都不会报错），因此把「判据」与「动作」分开：
 * 本对象只回答「该不该」，不碰 Context、不碰 DataStore、不产生文案。
 * 动作（写开关、拉起系统对话框、写目录 URI）在 `VisualizerViewModel`。
 *
 * ## 三个必须写对的点（§9.6）
 *
 * | # | 规则 | 写错的后果 |
 * |---|---|---|
 * | 1 | 回弹判据必须**只**对 `DENIED` 生效 | 用二态判定（`!= FULL`）会把「仅选择照片」误判成被拒 ⇒ 下次启动把开关弹回去（风险 R27） |
 * | 2 | 「部分授权」必须算**可读** | 用户明明选了照片却拿到空列表 |
 * | 3 | 目录 URI 的有效性只能查 `persistedUriPermissions`，**不能自己存标志** | 用户在系统设置里撤销后标志仍为 true，UI 显示「已选目录」但读不到 |
 */
object PhotoWallAccessPolicy {

    /**
     * 图库开关是否必须**回弹为关**（§6.2）
     *
     * ⛔ 只有「确实被拒」才回弹。`PARTIAL`（Android 14+「仅选择照片」）
     * 是**有效授权** —— 其 `READ_MEDIA_IMAGES` 虽只是会话级临时授予，
     * 但 `READ_MEDIA_VISUAL_USER_SELECTED` 是持久的，用户选中的那批照片可读。
     */
    fun shouldRollbackGallerySwitch(
        galleryEnabled: Boolean,
        state: PhotoPermissionState,
    ): Boolean = galleryEnabled && state == PhotoPermissionState.DENIED

    /** 打开图库开关时是否需要先走授权流程（`PARTIAL` 已可用，不重复弹窗） */
    fun needsPermissionRequest(state: PhotoPermissionState): Boolean =
        state == PhotoPermissionState.DENIED

    /** 是否处于「部分授权」⇒ 设置页要给「重新选择照片」入口 + 说明（§9.6 规则 3） */
    fun isPartial(state: PhotoPermissionState): Boolean =
        state == PhotoPermissionState.PARTIAL

    /** 当前权限是否足以读取图库（`DENIED` 以外都算） */
    fun grantsGalleryAccess(state: PhotoPermissionState): Boolean =
        state != PhotoPermissionState.DENIED

    /**
     * 已保存的 SAF 目录 URI 是否**已被系统撤销** ⇒ 需要清空设置里的目录
     *
     * ⚠️ 只能查 `contentResolver.persistedUriPermissions`（§9.4）。
     * 自己存「已授权」标志会与系统状态不一致：用户在系统设置里撤销后标志仍为 true。
     *
     * @param storedUri 设置里保存的 tree URI（空串表示从未选过）
     * @param persistedUris 系统当前持有的持久授权 URI 列表
     */
    fun shouldClearDirectoryUri(storedUri: String, persistedUris: Collection<String>): Boolean {
        if (storedUri.isBlank()) return false          // 从未选过 ⇒ 无可清
        return persistedUris.none { it == storedUri }
    }
}
