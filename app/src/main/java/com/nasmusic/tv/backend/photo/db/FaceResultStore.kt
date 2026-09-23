package com.nasmusic.tv.backend.photo.db

/**
 * 人脸结果的存取（又一层接缝）
 *
 * `FaceScanManager` 只认这个接口，不认 Room —— 这样「中断 / 续跑 / 进度 / 拔盘不清表」
 * 这些逻辑可以在**纯 JVM** 上用内存实现验证，不必拉起 Robolectric 建库。
 *
 * ⛔ 与 `PhotoFaceDao` 的差异：这里返回 `Set`（调用方要用 `in` 判交集），
 * 且**批量**写入（扫描是分片的，一次写一片）。
 */
interface FaceResultStore {

    /** 「检出人脸」的照片 key 集合（`PhotoRef.id`） */
    suspend fun faceKeys(): Set<String>

    /** **所有**已检测过的 key（含 `hasFace = false`）—— 续跑时用来跳过 */
    suspend fun allKeys(): Set<String>

    suspend fun upsertAll(entities: List<PhotoFaceEntity>)

    suspend fun clear()

    suspend fun count(): Int
}

/** Room 实现（生产） */
class RoomFaceResultStore(private val dao: PhotoFaceDao) : FaceResultStore {
    override suspend fun faceKeys(): Set<String> = dao.faceKeys().toSet()
    override suspend fun allKeys(): Set<String> = dao.allKeys().toSet()
    override suspend fun upsertAll(entities: List<PhotoFaceEntity>) = dao.upsertAll(entities)
    override suspend fun clear() = dao.clear()
    override suspend fun count(): Int = dao.count()
}
