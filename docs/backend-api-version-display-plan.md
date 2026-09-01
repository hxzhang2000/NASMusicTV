# 后端 API 版本号统一展示方案

> 创建：2026-09-01
> 状态：规划中，未实施
> 目标：在「设置 → 关于」页面集中展示所有已接入后端/服务的 API 版本号

---

## 1. 概述

### 1.1 背景

目前「关于」页面已展示：
- 后端类型（Jellyfin / Navidrome / Subsonic / 道理鱼 / 飞牛 / 百度网盘 / 无）
- 连接状态（已连接 / 未连接）

缺失：**各后端的 API 版本号**。用户/开发者无法快速识别当前接入的版本，排查兼容性问题困难。

### 1.2 目标

在「关于」页面新增「API 版本号」展示区，覆盖所有已接入的后端与外部服务。

---

## 2. 版本号来源清单

### 2.1 已接入后端

| 后端 | 类型 | 版本号来源 | 当前实现 | 展示示例 |
|------|------|-----------|---------|---------|
| **Jellyfin** | 媒体服务器 | 运行时 `/System/Info/Public` → `Version` | `JellyfinAdapter` 无 `apiVersion` 字段 | `10.9.11` |
| **Navidrome** | 音乐服务器 | 运行时 `rest/ping.view` → `SubsonicResponse.version` | `NavidromeAdapter` 无 `apiVersion` 字段 | `0.52.0` (Subsonic 1.16.0) |
| **Subsonic** | 标准协议 | 运行时 `rest/ping.view` → `version` | `SubsonicAdapter` 无 `apiVersion` 字段 | `1.16.1` |
| **道理鱼** | 私有 API | 运行时 `/health` → `version` | `DaoliyuAdapter.fetchApiVersion()` ✅ | `Daoliyu API 1.2.3` |
| **飞牛音乐** | 私有 API | 硬编码 / 待确认 | `FeiniuAdapter.fetchApiVersion()` 仅返回 `"飞牛音乐 API"` | `飞牛音乐 API (v1)` |
| **百度网盘** | 开放平台 | URL 路径版本 `/rest/2.0/xpan/` | `BaiduNetdiskConfig` 仅有 `API_PROBE_BASELINE` | `PCS rest/2.0 (无显式版本号)` |

### 2.2 外部服务（非后端，同样需列出）

| 服务 | 版本号 | 来源 | 展示示例 |
|------|-------|------|---------|
| **Jamendo** | `3.0` | 硬编码 (`/v3/`) | `Jamendo · v3.0` |
| **Open-Meteo** | `1.0` | 硬编码 (`/v1/`) | `Open-Meteo · v1.0 (默认天气源)` |
| **OpenWeatherMap** | `2.5` | 硬编码 (`/data/2.5/`) | `OpenWeatherMap · v2.5 (备用天气源)` |
| **Meting-API** | — | 端点自部署，版本不可控 | `Meting-API` |
| **Bilibili MV** | — | 反向工程，无版本承诺 | `Bilibili MV` |

> 规则：所有后端/外部服务**均需列出**。能获取版本号的显示 `服务名 · 版本号`；无法获取版本号的仅显示 `服务名`。

---

## 3. 数据结构设计

### 3.1 VersionInfo 数据类

```kotlin
// app/src/main/java/com/nasmusic/tv/data/model/VersionInfo.kt
package com.nasmusic.tv.data.model

/**
 * API 版本号信息
 */
sealed interface VersionInfo {
    /** 硬编码常量版本（如 Jamendo v3.0、百度网盘 rest/2.0） */
    data class Static(
        val serviceName: String,
        val version: String,
        val description: String = ""
    ) : VersionInfo

    /** 运行时从服务器获取（如 Jellyfin、Navidrome、道理鱼） */
    data class Runtime(
        val serviceName: String,
        val version: String,      // 实时获取的版本号
        val endpoint: String,     // 获取端点，用于调试
        val lastUpdated: Long     // 最后成功获取时间戳
    ) : VersionInfo

    /** 版本不可控/未知（保留扩展，当前无使用场景） */
    data class Unknown(
        val serviceName: String,
        val reason: String
    ) : VersionInfo

    /** 无版本号服务（如 Meting-API、Bilibili MV）——仅展示服务名 */
    data class NoVersion(
        val serviceName: String
    ) : VersionInfo

    /** 未连接（后端已配置但当前未连接） */
    data class Disconnected(
        val serviceName: String,
        val expectedVersion: String? = null  // 配置中预期的版本
    ) : VersionInfo
}
```

### 3.2 聚合 StateFlow

```kotlin
// MainViewModel 新增
val apiVersions: StateFlow<List<VersionInfo>> = ...

// 每个后端 Adapter 暴露
interface BackendAdapter {
    // 现有方法...
    
    /** 获取当前 API 版本号（阻塞式，供版本号聚合调用） */
    suspend fun getApiVersion(): VersionInfo
}
```

---

## 4. 后端 Adapter 改造

### 4.1 JellyfinAdapter

```kotlin
override suspend fun getApiVersion(): VersionInfo {
    return try {
        val json = executeGet("$baseUrl/System/Info/Public") ?: return VersionInfo.Disconnected("Jellyfin")
        val version = json.get("Version")?.asString ?: "未知"
        VersionInfo.Runtime("Jellyfin", version, "/System/Info/Public", System.currentTimeMillis())
    } catch (e: Exception) {
        VersionInfo.Disconnected("Jellyfin")
    }
}
```

### 4.2 NavidromeAdapter / SubsonicAdapter

```kotlin
override suspend fun getApiVersion(): VersionInfo {
    return try {
        val json = executeGet("$baseUrl/rest/ping.view?u=$username&t=$token&s=$salt&v=1.16.1&c=NASMusicTV&f=json")
        val version = json.get("subsonic-response")?.asJsonObject?.get("version")?.asString ?: "未知"
        VersionInfo.Runtime(serviceName, version, "rest/ping.view", System.currentTimeMillis())
    } catch (e: Exception) {
        VersionInfo.Disconnected(serviceName)
    }
}
```

### 4.3 道理鱼 / 飞牛

复用现有 `fetchApiVersion()` 逻辑，返回 `VersionInfo.Runtime` 或 `Disconnected`。

### 4.4 百度网盘

```kotlin
override suspend fun getApiVersion(): VersionInfo {
    return VersionInfo.Static("百度网盘", "PCS rest/2.0", "接口静默演进，无显式版本号；已启用 ApiProbe 字段指纹漂移检测")
}
```

### 4.5 外部服务（静态常量）

在 `MainViewModel` 初始化时直接注入静态版本号，无需网络请求：

```kotlin
// MainViewModel.init { ... }
private val externalVersions = listOf(
    VersionInfo.Static("Jamendo", "v3.0"),
    VersionInfo.Static("Open-Meteo", "v1.0", "默认天气源"),
    VersionInfo.Static("OpenWeatherMap", "v2.5", "备用天气源"),
    VersionInfo.NoVersion("Meting-API"),
    VersionInfo.NoVersion("Bilibili MV"),
)
```

聚合时：`apiVersions = backendVersions + externalVersions`。

---

## 5. UI 设计

### 5.1 关于页面结构

```
关于
═══════════════════════════════

后端连接
──────────────────────────────
[图标] Jellyfin          已连接 · v10.9.11
[图标] Navidrome         已连接 · v0.52.0 (Subsonic 1.16.0)
[图标] 百度网盘          已连接 · PCS rest/2.0
[图标] 飞牛音乐          未连接

外部服务
──────────────────────────────
Jamendo                    v3.0
Open-Meteo                 v1.0 (默认天气源)
OpenWeatherMap             v2.5 (备用天气源)
Meting-API
Bilibili MV

应用信息
──────────────────────────────
版本：2.25.5 (75)
...
```

### 5.2 视觉规范

- **已连接**：版本号用 `NasMusicColors.TextPrimary`，旁标绿点
- **未连接**：版本号用 `NasMusicColors.TextSecondary`，显示"未连接"
- **未知/不可控**：用 `NasMusicColors.TextSecondary`，显示原因描述
- **获取中**：显示骨架屏或 `...`

### 5.3 按钮颜色规范（参考 SettingsScreen 修复）

版本号区域若有按钮（如"刷新版本"），必须显式指定文字颜色：

```kotlin
Text(
    text = label,
    color = LocalFocusableContentColor.current,  // 必须指定
    fontSize = FontSize.body(),
    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
)
```

---

## 6. 实施步骤

### Batch 1：数据层

- [ ] 新增 `VersionInfo.kt` 密封接口
- [ ] `BackendAdapter` 接口新增 `suspend fun getApiVersion(): VersionInfo`
- [ ] `JellyfinAdapter`、`NavidromeAdapter`、`SubsonicAdapter` 实现 `getApiVersion()`
- [ ] `DaoliyuAdapter`、`FeiniuAdapter` 适配现有 `fetchApiVersion()` 返回 `VersionInfo`
- [ ] `MainViewModel` 新增 `apiVersions: StateFlow<List<VersionInfo>>`，定时聚合（建议 30 秒刷新一次，或仅在进入关于页时触发）

### Batch 2：UI 层

- [ ] `SettingsScreen.kt` 关于页面区块重构，渲染 `apiVersions`
- [ ] 按「后端连接」/「外部服务」/「应用信息」三段式布局
- [ ] 遵守按钮颜色规范（显式 `color = LocalFocusableContentColor.current`）

### Batch 3：完善

- [ ] 版本号获取失败时的降级显示
- [ ] 点击版本号可复制/查看详情（可选）
- [ ] 单元测试：`VersionInfo` 序列化、各 Adapter 版本号解析

---

## 7. 验收清单

- [ ] 进入「设置 → 关于」能看到所有后端的版本号
- [ ] Jellyfin/Navidrome/Subsonic 显示运行时获取的真实版本
- [ ] 道理鱼/飞牛显示现有 `fetchApiVersion()` 结果
- [ ] 百度网盘显示静态版本号 + 说明
- [ ] 外部服务：Jamendo v3.0 / Open-Meteo v1.0 / OpenWeatherMap v2.5 显示版本号
- [ ] 外部服务：Meting-API / Bilibili MV 仅显示服务名（无版本号）
- [ ] 未连接的后端显示"未连接"
- [ ] 切换语言后版本号区域文案正确（i18n 已在 strings.xml）
- [ ] 所有新增按钮文字显式指定颜色，未选中态为亮色

---

## 8. 参考文件

- 现有关于页面：[SettingsScreen.kt](app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt) 搜索 "关于"
- 道理鱼版本获取：[DaoliyuAdapter.kt:158](app/src/main/java/com/nasmusic/tv/backend/impl/DaoliyuAdapter.kt:158)
- 飞牛版本获取：[FeiniuAdapter.kt:201](app/src/main/java/com/nasmusic/tv/backend/impl/FeiniuAdapter.kt:201)
- 百度网盘配置：[BaiduNetdiskConfig.kt](app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduNetdiskConfig.kt)
- 按钮颜色规范：[SettingsScreen.kt:352](app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:352)