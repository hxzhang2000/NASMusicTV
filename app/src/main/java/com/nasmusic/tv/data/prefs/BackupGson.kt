package com.nasmusic.tv.data.prefs

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nasmusic.tv.data.model.NetworkSource
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.util.AppLog

/**
 * 备份文件**专用**的容错 Gson（v2.36.2）。
 *
 * 只在备份的导出 / 导入两条路径上使用（[com.nasmusic.tv.ui.viewmodel.BackupViewModel]），
 * 不影响 DataStore 的读写（那一路由各枚举自带的 `fromKey()` 负责）。
 *
 * ## 为什么需要它 —— 「老备份导入失败」的根因
 *
 * 备份 JSON 里保存的是**枚举常量名**（例如 `"visualizerTheme": "CLASSICAL_WAVE"`）。
 * 历史版本删改过枚举常量：`VisualizerTheme` 现在有 34 项，`CLASSICAL_WAVE` 已不在其中。
 * 而 **Gson 默认的枚举适配器不认历史名**（`gson 2.10.1` / `TypeAdapters.EnumTypeAdapter`）：
 *
 * ```java
 * T constant = nameToConstant.get(key);
 * return (constant == null) ? stringToConstant.get(key) : constant;   // ← 找不到就返回 null
 * ```
 *
 * 注意它**不抛异常**，而是返回 `null`；Gson 再用反射把 `null` 写进字段，
 * **绕过 Kotlin 的非空检查** → 声明为非空的 `AppSettings.visualizerTheme` 变成 `null`
 * → 随后 `AppPreferences.importBackupData()` 里 `settings.visualizerTheme.name` 抛 NPE
 * → `dataStore.edit {}` 事务回滚 → **整份备份导入失败**（且已写入的 serverConfig 无法回退）。
 *
 * `VisualizerTheme.LEGACY_MAP` 原本只服务 `fromKey()`（读 DataStore 那条路），
 * **覆盖不到 Gson 反序列化这条路** —— 这正是「以前保存的备份现在导入失败」的原因。
 *
 * ## 容错策略（逐级回落，永不返回 null）
 *
 * 1. 名字能在当前枚举里找到 → 直接取值
 * 2. 该枚举自带兼容映射（见 [resolveLegacyEnumName]）→ 交给它解析
 *    （`VisualizerTheme.fromKey("CLASSICAL_WAVE")` → `CIRCULAR_RING`，即正确的迁移结果）
 * 3. 都不行 → 回落到该枚举的**第一个常量**并打警告
 *    —— 备份是用户的救命稻草，**宁可恢复出一个默认值，也不能让整份备份导入失败**
 *
 * 导出方向不受影响：写出的仍是 `enum.name`，与 Gson 默认行为逐字一致
 * （全项目没有任何 `@SerializedName`，无需兼容自定义名）。
 */
val backupGson: Gson = GsonBuilder()
    .registerTypeAdapterFactory(TolerantEnumAdapterFactory)
    .create()

private const val TAG = "BackupGson"

/**
 * 历史枚举名兼容解析（第 ② 级回落）。
 *
 * 返回值语义：
 * - `null`：本枚举没有兼容映射，交给第 ③ 级「首个常量」兜底
 * - 非 null：按兼容规则解析出的值
 *
 * ⚠️ `VisualizerTheme.fromKey()` / `VisualQuality.fromKey()` **未命中时返回各自的 `Default`**
 * （不会返回 null），这正是我们想要的迁移结果，所以它们永远走第 ② 级；
 * `NetworkSource.fromKey()` 未命中返回 `null`，会继续落到第 ③ 级。
 */
private fun resolveLegacyEnumName(clazz: Class<*>, raw: String): Enum<*>? = when (clazz) {
    VisualizerTheme::class.java -> VisualizerTheme.fromKey(raw)
    VisualQuality::class.java -> VisualQuality.fromKey(raw)
    NetworkSource::class.java -> NetworkSource.fromKey(raw)
    else -> null
}

/**
 * 把 Gson 默认的枚举适配器替换为容错版本（仅对 [backupGson] 生效）。
 *
 * Gson 在 `GsonBuilder` 里**前插**工厂，故本工厂先于内置的 `TypeAdapters.ENUM_FACTORY` 命中；
 * 非枚举类型一律返回 `null` 交回默认链路，不改变其它任何类型的读写行为。
 */
private object TolerantEnumAdapterFactory : TypeAdapterFactory {
    override fun <T : Any> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType
        if (!raw.isEnum) return null
        @Suppress("UNCHECKED_CAST")
        return TolerantEnumAdapter(raw) as TypeAdapter<T>
    }
}

/**
 * 容错枚举适配器。
 *
 * 用 `Class<*>` + `Any?` 而非 `Class<T : Enum<T>>`，是为了让工厂能对任意枚举类
 * 统一构造（`Class<out Enum<*>>` 无法满足 `T : Enum<T>` 的自引用上界）。
 */
private class TolerantEnumAdapter(private val clazz: Class<*>) : TypeAdapter<Any?>() {

    /** 当前枚举的全部常量（按声明顺序） */
    private val constants: List<Enum<*>> =
        clazz.enumConstants?.toList().orEmpty().filterIsInstance<Enum<*>>()

    private val byName: Map<String, Enum<*>> = constants.associateBy { it.name }

    /** 第 ③ 级回落目标：首个常量（枚举至少有一个常量） */
    private val fallback: Enum<*>? = constants.firstOrNull()

    override fun write(out: JsonWriter, value: Any?) {
        val e = value as? Enum<*>
        if (e == null) out.nullValue() else out.value(e.name)
    }

    // ⚠️ 形参不能叫 `in` —— `in` 是 Kotlin 硬关键字，写成 `read(in: JsonReader)` 直接语法错误。
    // Java 侧的名字是 `in`，Kotlin 覆写允许改名（不产生告警）。
    override fun read(jsonReader: JsonReader): Any? {
        if (jsonReader.peek() == JsonToken.NULL) {
            jsonReader.nextNull()
            return null
        }
        val raw = jsonReader.nextString()

        // ① 当前枚举名
        byName[raw]?.let { return it }

        // ② 历史名 / 兼容映射
        resolveLegacyEnumName(clazz, raw)?.let { resolved ->
            AppLog.w(
                TAG,
                "备份中的枚举名不在当前 ${clazz.simpleName} 中，已按兼容规则解析：" +
                    "$raw → ${resolved.name}"
            )
            return resolved
        }

        // ③ 兜底：宁可恢复出默认值，也不让整份备份导入失败
        AppLog.w(
            TAG,
            "备份中的枚举名无法识别，回落首个常量：${clazz.simpleName}.$raw → " +
                "${fallback?.name ?: "<空枚举>"}"
        )
        return fallback
    }
}
