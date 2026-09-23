package com.nasmusic.tv.visualizer.photo

/**
 * 照片墙可用性（§7.4 的**唯一新增逻辑**）
 *
 * ## 为什么是「或」而不是「与」
 *
 * 原方案有一个 `photoWallEnabled` 总开关，2026-09-23 用户决定**取消总开关**，
 * 降级为三个**来源开关**（图库 / 外接存储 / Jellyfin）：
 *
 * > 「图片墙的总开关就不需要了，直接降级成手机端图库的开关，不打勾就是不启用图库。
 * > 打开了才需要授权。」
 *
 * ⇒ 「照片墙这个效果该不该出现在列表里」由「**三个来源是不是全关**」派生：
 * **开任何一个来源，照片墙就可用**。写成「与」的话，用户必须三个全开才能看到效果 ——
 * 而绝大多数用户只会用一个来源（电视：U 盘；手机：图库；有 NAS 的：Jellyfin）。
 *
 * ## 三个来源开关各自的含义
 *
 * | 参数 | 设置项 | 对应 `PhotoSourceKind` |
 * |---|---|---|
 * | [galleryEnabled] | 手机端「图库」 | `MEDIA_STORE`（仅手机；打开才申请照片权限） |
 * | [externalEnabled] | 「外接存储」 | `EXTERNAL_FILE`（U 盘 / SD 卡 / SAF 目录） |
 * | [jellyfinEnabled] | 「NAS 照片库」 | `JELLYFIN`（需已连接 NAS） |
 *
 * ## 为什么单独成文件
 *
 * ① 它是**纯函数** ⇒ 可纯 JVM 单测（门禁 G8）；
 * ② 三个来源开关的读取分散在 UI 层（`AppSettings`）与控制器（`PhotoWallController`）两处，
 * 把判定收口到一处，避免「UI 按或、控制器按与」这类不一致。
 */
object PhotoWallAvailability {

    /**
     * 三来源开关之「或」。
     *
     * @return `true` = `PHOTO_WALL` 应出现在效果列表里、左右键可以切到它
     */
    fun isAvailable(
        galleryEnabled: Boolean,
        externalEnabled: Boolean,
        jellyfinEnabled: Boolean,
    ): Boolean = galleryEnabled || externalEnabled || jellyfinEnabled

    /** 三个全关 —— 「完全无照片 I/O」的判据（关掉的来源不扫描、不预解码、不申请权限） */
    fun isFullyDisabled(
        galleryEnabled: Boolean,
        externalEnabled: Boolean,
        jellyfinEnabled: Boolean,
    ): Boolean = !isAvailable(galleryEnabled, externalEnabled, jellyfinEnabled)
}
