package com.nasmusic.tv.backend.photo.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一张照片的人脸检测结果（`photo_face` 表）
 *
 * ⛔ 主键就是 `PhotoRef.id`（§6.1 的 `"<kind>:<payload>"`）—— **不另建自增 id**：
 * 照片的身份在跨来源合并后已经全局唯一，另建 id 只会让「按 id 查」变成「先查来源再查 id」。
 *
 * ## 与「文件修改时间」的关系
 *
 * [fileModifiedSec] 记的是**检测那一刻**的 `PhotoRef.lastModified`（秒）。
 * 重扫时若同一 `photoKey` 的 `lastModified` 变了 ⇒ 照片内容变了 ⇒ 需要重检。
 * ⚠️ 本阶段**只写不判**（判重要在扫描入口做，见 `FaceScanManager` 的 KDoc）——
 * 先落库，避免第一次接线上就引入「为什么这张没被重检」的复杂度。
 *
 * @param photoKey `PhotoRef.id`
 * @param hasFace 是否检出人脸
 * @param faceCount 检出的人脸数（NMS 之后）；`hasFace == (faceCount > 0)`
 * @param detectedAt 检测时刻（ms，`System.currentTimeMillis()`）
 * @param fileModifiedSec 检测时该照片的 `lastModified`（秒）；`-1` = 未知
 */
@Entity(tableName = "photo_face")
data class PhotoFaceEntity(
    @PrimaryKey val photoKey: String,
    val hasFace: Boolean,
    val faceCount: Int,
    val detectedAt: Long,
    val fileModifiedSec: Long,
)
