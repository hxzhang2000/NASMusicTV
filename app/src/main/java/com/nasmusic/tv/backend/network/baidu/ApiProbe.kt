package com.nasmusic.tv.backend.network.baidu

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nasmusic.tv.util.AppLog
import java.security.MessageDigest

/**
 * 百度网盘 API 版本探针
 *
 * 检测百度 API 是否静默升级（字段新增/缺失/重命名）。
 * 纯计算逻辑，不联网——网络调用由调用方（如 [BaiduNetdiskService] / [com.nasmusic.tv.ui.viewmodel.MainViewModel]）
 * 在登录成功后执行，结果通过 [ApiProbe.computeFieldFingerprint] 计算指纹后写入 [CloudDriveConfig.apiProbeResult]。
 *
 * 基线指纹固化在 [BaiduNetdiskConfig.API_PROBE_BASELINE]（上线前实测验证后回填），
 * 对比 [CloudDriveConfig.apiDrifted] 判定是否漂移，一次性提示经 [AppPreferences.baiduApiDriftNotified] 去重。
 *
 * @see docs/百度网盘音乐播放开发方案.md §3.0 版本管理机制
 */
object ApiProbe {

    private const val TAG = "ApiProbe"

    /** 递归收集 JSON 结构中的字段名路径 */
    private fun collectKeys(element: JsonElement, prefix: String, keys: MutableSet<String>) {
        when (element) {
            is JsonObject -> {
                for (key in element.keySet()) {
                    val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
                    keys.add(fullKey)
                    collectKeys(element.get(key), fullKey, keys)
                }
            }
            is JsonArray -> {
                if (element.size() > 0) {
                    // 仅取第一个元素推断结构（数组元素结构一致）
                    collectKeys(element[0], "$prefix[0]", keys)
                }
            }
            // 基本类型不贡献字段名
        }
    }

    /**
     * 计算 JSON 响应的字段指纹。
     *
     * 递归提取所有字段名的全路径（数组仅取第一个元素），排序后 SHA-256 十六进制。
     * 同一结构 → 稳定输出；字段增减/重命名 → 变化。
     *
     * @param json 原始 JSON 字符串
     * @return SHA-256 十六进制字符串；解析失败返回 null
     */
    fun computeFieldFingerprint(json: String): String? {
        return try {
            val obj = Gson().fromJson(json, JsonObject::class.java) ?: return null
            computeFieldFingerprint(obj)
        } catch (e: Exception) {
            AppLog.w(TAG, "computeFieldFingerprint parse error", e)
            null
        }
    }

    /**
     * 计算已解析 JsonObject 的字段指纹。
     */
    fun computeFieldFingerprint(obj: JsonObject): String {
        val keys = sortedSetOf<String>()
        collectKeys(obj, "", keys)
        val canonical = keys.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * API 是否漂移：基线与实测指纹均非空且不一致。
     *
     * 语义与 [CloudDriveConfig.apiDrifted] 保持同步。
     */
    fun isDrifted(baseline: String?, result: String?): Boolean =
        !baseline.isNullOrBlank() && !result.isNullOrBlank() && baseline != result

    /**
     * 是否应弹出一次性漂移提示。
     *
     * @param apiDrifted 当前是否漂移
     * @param alreadyNotified 该版本是否已提示过（写入 [AppPreferences] 的 baiduApiDriftNotified 标记）
     */
    fun shouldNotifyDrift(apiDrifted: Boolean, alreadyNotified: Boolean): Boolean =
        apiDrifted && !alreadyNotified
}