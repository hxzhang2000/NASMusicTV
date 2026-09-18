package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 单曲音质覆盖（多码率方案 §2.3.1 / D3 两级模型）。
 *
 * 键 = `networkSource:networkId`（与 `NetworkMusicManager.playUrlKey` 的
 * source:id 部分一致，便于排查），值 = 音质档位 + 最后修改时间。
 *
 * 设计要点：
 * - 存于**独立 DataStore 文件**（`quality_overrides`），与主偏好的读写频率隔离，
 *   避免热路径互相干扰；
 * - 容量上限 [MAX_ENTRIES] 条，超出按 [Entry.updatedAt] 淘汰最旧 ——
 *   单曲覆盖是"极少数例外"，无界增长无意义；
 * - 内存镜像 [cache] 保证 `tierOf()` 同步可读（NetworkMusicManager 在 IO 线程
 *   同步查询覆盖值，不能 suspend）。
 */
class QualityOverrides(context: Context) {

    data class Entry(val tier: Int, val updatedAt: Long)

    companion object {
        private const val TAG = "QualityOverrides"

        /** 容量上限：超出按 updatedAt 淘汰最旧 */
        const val MAX_ENTRIES = 500

        private const val DATASTORE_NAME = "quality_overrides"

        private val KEY_OVERRIDES = stringPreferencesKey("song_quality_overrides")
    }

    private val gson = Gson()

    private val dataStore = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) {
        File(context.filesDir, "datastore/$DATASTORE_NAME.preferences_pb")
    }

    /** 内存镜像：键 → 覆盖值。@Volatile 保证跨线程可见性。 */
    @Volatile
    private var cache: Map<String, Entry> = emptyMap()

    init {
        // 启动时同步加载一次，保证 tierOf() 立即可用（首帧不会漏覆盖）
        cache = runBlocking(Dispatchers.IO) { load() }
        // ⚠️ 这里**刻意不注册** dataStore.data 的后台 collect 镜像。
        //
        // 原实现注册了 `dataStore.data.collect { cache = decode(it) }`，但那会让
        // `cache` 同时被两条路径写入：本类的 put/remove/clearAll（同步）与 collector（异步）。
        // 连续两次 put 时，第一次 persist 触发的 collector 回调可能在第二次 put 之后才到达，
        // 把更新的内存值**回退成旧快照** —— 用户选完「仅本次播放 999」立刻播放，
        // 可能读到上一次的档位（QualityOverridesTest 捕获到该竞态）。
        //
        // 本应用是**单进程**，且所有写入都必须经过本类的方法（方法内已同步更新 cache），
        // 因此不存在"外部改盘、内存不知"的场景，collector 没有存在必要。
        // 去掉后 tierOf() 在 put() 返回后立刻可读到新值，语义更强。
    }

    private suspend fun load(): Map<String, Entry> =
        decode(dataStore.data.first()[KEY_OVERRIDES] ?: "")

    private fun decode(json: String): Map<String, Entry> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, Entry>>() {}.type
            gson.fromJson<Map<String, Entry>>(json, type) ?: emptyMap()
        }.getOrElse {
            AppLog.w(TAG, "decode failed, reset to empty: ${it.message}")
            emptyMap()
        }
    }

    private fun keyOf(source: String, id: String) = "$source:$id"

    /**
     * 查询某曲的覆盖档位。
     *
     * @return 覆盖值；null 表示无覆盖，调用方应回退全局默认档位
     */
    fun tierOf(source: String, id: String): Int? = cache[keyOf(source, id)]?.tier

    /** 全部覆盖（供设置页统计 / 列表徽标批量查询） */
    fun all(): Map<String, Int> = cache.mapValues { it.value.tier }

    /** 覆盖条目数（供设置页展示） */
    fun size(): Int = cache.size

    /** 写入覆盖（同键覆盖旧值并刷新 updatedAt） */
    suspend fun put(source: String, id: String, tier: Int) {
        if (!QualityTiers.isValid(tier)) {
            AppLog.w(TAG, "put: invalid tier=$tier, ignored")
            return
        }
        val key = keyOf(source, id)
        val updated = cache.toMutableMap().apply {
            put(key, Entry(tier, System.currentTimeMillis()))
        }
        // 超出上限：淘汰最旧（LRU 近似，按 updatedAt）
        val trimmed = if (updated.size > MAX_ENTRIES) {
            updated.entries
                .sortedByDescending { it.value.updatedAt }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        } else {
            updated
        }
        cache = trimmed
        persist(trimmed)
    }

    /** 移除单曲覆盖 */
    suspend fun remove(source: String, id: String) {
        val key = keyOf(source, id)
        if (!cache.containsKey(key)) return
        val updated = cache.toMutableMap().apply { remove(key) }
        cache = updated
        persist(updated)
    }

    /** 清除全部覆盖（设置页入口） */
    suspend fun clearAll() {
        if (cache.isEmpty()) return
        cache = emptyMap()
        persist(emptyMap())
    }

    private suspend fun persist(data: Map<String, Entry>) {
        val json = gson.toJson(data)
        runCatching {
            dataStore.edit { it[KEY_OVERRIDES] = json }
        }.onFailure { AppLog.w(TAG, "persist failed: ${it.message}") }
    }
}
