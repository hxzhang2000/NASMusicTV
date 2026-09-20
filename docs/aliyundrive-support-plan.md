# 阿里云盘接入开发方案

> 创建：2026-09-01
> 状态：**规划中，遭遇硬阻塞，需决策**
> 关联：`docs/百度网盘音乐播放开发方案.md`（百度网盘架构蓝本）、`docs/archive/daoliyu-feiniu-backend-plan.md`（多后端并行接入先例）

## ⚠️ 关键阻塞：阿里云盘 PDS 个人开发者申请已暂停

> **2025年7月1日起，阿里云盘暂停个人开发者申请**。企业开发者需邮件联系 `jinshenghui.jsh@alibaba-inc.com`。
>
> 这意味着原方案基于的 **官方 PDS OAuth 2.0 + Domain 注册** 路径对个人/开源项目**不可行**。

### 可行替代方案对比

| 方案 | 原理 | 优势 | 劣势 | 推荐度 |
|------|------|------|------|--------|
| **A. 企业开发者申请** | 邮件申请企业资质 | 官方支持、稳定、合规 | 需企业资质、周期长、个人项目不适合 | ⭐ |
| **B. 逆向 Web/API + refresh_token** | 抓包阿里云盘官方 Web/App API，用用户自行获取的 refresh_token | 无需开发者资质、现有开源项目验证可行 | 非官方、随时可能失效、账号封禁风险、需用户手动获取 token | ⭐⭐⭐ |
| **C. WebDAV 桥接** | 用户自建 alist / aliyundrive-webdav，本项目接入 WebDAV 协议 | 复用现有成熟方案、用户可控 | 需用户自建服务、增加部署复杂度、本项目需先实现 WebDAV 后端 | ⭐⭐ |
| **D. 接入其他网盘（115/夸克/天翼云）** | 类似百度网盘，用其开放平台 API | 有官方开放平台、相对稳定 | 需各自申请、开发工作量重复 | ⭐⭐ |
| **E. 暂缓阿里云盘，优先 WebDAV 通用后端** | 实现通用 WebDAV 后端，用户可挂载任意网盘（含阿里云盘） | 一次开发、支持所有网盘、用户自主性强 | 需设计 WebDAV 协议适配、播放直链处理 | ⭐⭐⭐⭐ |

> **建议**：方案 E（WebDAV 通用后端）性价比最高。一次实现 WebDAV 客户端，用户可通过 alist/aliyundrive-webdav/rclone 等桥接任意网盘（阿里云盘、百度网盘、115、夸克、OneDrive 等），规避单一网盘 API 变更/封禁风险。

---

## 1. 概述

### 1.1 目标

在现有网盘层（百度网盘）基础上新增**阿里云盘**接入，让用户无需 NAS 后端即可播放阿里云盘中的音乐文件。同时统一整理所有已接入后端的 API 版本号，在「关于」页面集中展示。

### 1.2 范围

- 阿里云盘 PDS（网盘与相册服务）OAuth 2.0 授权
- 文件列表 / 搜索 / 下载地址 / 流媒体播放
- 连接 UI、按钮颜色规范
- 关于页面新增「API 版本号」展示区

### 1.3 非目标

- 阿里云盘上传 / 删除 / 移动（只读播放，不修改用户网盘）
- 相册服务（只接网盘文件，不接图片/视频相册）
- 阿里云盘「分享」功能
- 其他网盘（如 115、夸克）的接入

---

## 2. API 版本号清单（关于页面展示）

### 2.1 全量 API 版本号表

> 关于页面集中展示此表，让用户和开发者都能快速识别当前接入的版本。

| 后端 | URL 路径版本 | OpenAPI 规范版本 | 获取方式 | 备注 |
|------|------------|----------------|---------|------|
| **阿里云盘 PDS** | `/v2/`（如 `/v2/file/list`） | `2022-03-01` | 硬编码常量 | 阿里云 OpenAPI 门户规范版本（日期制） |
| **百度网盘** | `/rest/2.0/xpan/...` | 无显式版本号 | 硬编码常量 | 接口静默演进，用 `ApiProbe` 字段指纹检测漂移（见 [BaiduNetdiskConfig.API_PROBE_BASELINE](app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduNetdiskConfig.kt:60)） |
| **Jellyfin** | 无路径版本 | 无统一规范 | 运行时 `/System/Info/Public` | 实例版本由服务器返回 |
| **Navidrome** | `/rest/`（Subsonic 协议） | Subsonic API 1.x | 运行时 `rest/ping.view` 返回 `SubsonicResponse.version` | 兼容 Subsonic 协议 |
| **Subsonic** | `/rest/` | Subsonic API 1.x | 运行时 `rest/ping.view` | 同 Navidrome |
| **道理鱼** | `/api/` | 无 | 运行时 `/health` → `version` 字段 | 见 [DaoliyuAdapter.fetchApiVersion()](app/src/main/java/com/nasmusic/tv/backend/impl/DaoliyuAdapter.kt:158) |
| **飞牛音乐** | `/music/api/v1/` | `v1` | 硬编码（`UNCONFIRMED`） | 见 [FeiniuAdapter.fetchApiVersion()](app/src/main/java/com/nasmusic/tv/backend/impl/FeiniuAdapter.kt:201)，版本号获取方式待抓包确认 |
| **Jamendo** | `/v3/` | `3.0` | 硬编码常量 | CC 授权独立音源 |
| **OpenWeatherMap** | `/data/2.5/` | `2.5` | 硬编码常量 | 备用天气源 |
| **Open-Meteo** | `/v1/` | `1.0` | 硬编码常量 | 默认天气源 |
| **Meting-API** | 无 | 无统一版本 | 端点自部署，版本不可控 | 多端点 fallback |
| **Bilibili MV** | 无 | 无统一版本 | 反向工程，无官方承诺 | 随 B 站改版可能失效 |

### 2.2 关于页面改造

现有「关于」页面（`SettingsScreen.kt` 子区块）已展示后端类型、连接状态。需扩展为：

```
关于
─────────────────────
后端类型：Jellyfin
连接状态：已连接
API 版本号：10.9.11（运行时获取）

网盘
─────────────────────
百度网盘：rest/2.0/xpan · 接口静默演进（漂移检测：未固化基线）
阿里云盘：PDS 2022-03-01 · URL /v2/

其他服务
─────────────────────
Jamendo API：v3.0
Open-Meteo：v1.0（默认天气源）
OpenWeatherMap：v2.5（备用天气源）
Meting-API：端点自部署，版本不可控
Bilibili MV：反向工程，无版本承诺
```

**实现要点**：
- `MainViewModel` 暴露 `apiVersionInfo: StateFlow<ApiVersionInfo>`，聚合各后端的版本号
- `ApiVersionInfo` 数据类区分「硬编码常量」「运行时获取」「不可控」三类
- 阿里云盘接入时，新增 `AliyunDriveConfig.API_DOC_VERSION = "2022-03-01"` 常量

---

## 3. 架构设计（参考百度网盘）

### 3.1 目录结构

仿照 [backend/network/baidu/](app/src/main/java/com/nasmusic/tv/backend/network/baidu/) 的分层：

```
backend/network/aliyundrive/
├── AliyunDriveConfig.kt         # 常量表（端点、版本号、category 映射）
├── AliyunDriveOAuthClient.kt    # OAuth 2.0 授权码交换、刷新
├── AliyunDriveApi.kt            # 文件列表 / 搜索 / 下载地址
├── AliyunDriveNetdiskService.kt # 高层服务（供 SearchAggregator / NasMusicApp 使用）
├── AliyunDriveStreamFactory.kt  # ExoPlayer DataSource.Factory（注入播放头）
├── AliyunDriveCoverProvider.kt  # 封面获取
├── AliyunDriveLyricsProvider.kt # 歌词侧车（LRC + 网络匹配 fallback）
├── AliyunDriveMvFileService.kt  # MV 关联（同目录同名 + 歌手歌名搜索）
└── AliyunDriveFileIndexCache.kt # 本地索引缓存（首次扫描后毫秒级搜索）
```

### 3.2 数据模型

新增 `data/model/AliyunDriveFile.kt`：

```kotlin
data class AliyunDriveFile(
    val fileId: String,         // PDS 全局唯一 file_id（跨重命名/移动稳定）
    val driveId: String,         // 空间 id
    val name: String,           // 文件名（含扩展名）
    val type: String,           // "file" | "folder"
    val category: String?,      // "audio" | "video" | "image" | "doc" | ...
    val size: Long,
    val parentFileId: String,   // 父文件夹 id，根目录为 "root"
    val contentHash: String?,   // 云端哈希
    val updatedAt: String       // ISO 8601
) {
    fun toSong(durationMs: Long = 0L, coverUrl: String? = null): Song {
        val (artist, title) = BaiduFilenameParser.parse(name)  // 复用百度文件名解析器
        return Song(
            id = "ntwk_aliyun_${fileId}",
            title = title,
            artist = artist,
            coverUrl = coverUrl,
            streamUrl = null,
            durationMs = durationMs,
            isNetworkSong = true,
            networkSource = "aliyundrive",
            networkId = fileId,
            path = name  // PDS 无路径概念，用 name 展示
        )
    }
}
```

### 3.3 配置常量

`AliyunDriveConfig.kt`：

```kotlin
object AliyunDriveConfig {
    // ---- API 版本号 ----
    /** URL 路径版本 */
    const val API_PATH_VERSION = "v2"
    /** 阿里云 OpenAPI 规范版本（日期制）*/
    const val API_DOC_VERSION = "2022-03-01"
    /** 关于页面展示用 */
    const val DISPLAY_VERSION = "PDS $API_DOC_VERSION · URL /$API_PATH_VERSION/"

    // ---- API 端点（基于 domainId 拼接）----
    fun baseUrl(domainId: String) = "https://$domainId.api.aliyunpds.com"
    const val OAUTH_AUTHORIZE_PATH = "/v2/oauth/authorize"
    const val OAUTH_TOKEN_PATH = "/v2/oauth/token"
    const val FILE_LIST_PATH = "/v2/file/list"
    const val FILE_SEARCH_PATH = "/v2/file/search"
    const val GET_DOWNLOAD_URL_PATH = "/v2/file/get_download_url"

    // ---- 文件分类（PDS 用字符串枚举，非数字码）----
    const val CATEGORY_AUDIO = "audio"
    const val CATEGORY_VIDEO = "video"

    // ---- 分页 ----
    const val PAGE_SIZE = 100       // PDS 上限 100（百度是 1000，差异显著）
    const val SEARCH_PAGE_SIZE = 100

    // ---- Token ----
    const val ACCESS_TOKEN_TTL_SEC = 7200L   // 2 小时
    const val REFRESH_THRESHOLD_SEC = 600L   // 剩余 10 分钟内自动刷新

    // ---- 播放头 ----
    /** PDS 下载直链的 User-Agent（无强制要求，但建议带浏览器 UA 避免被 OSS 拒绝）*/
    const val DOWNLOAD_UA = "Mozilla/5.0 (Linux; Android 11) NASMusicTV"
}
```

### 3.4 OAuth 2.0 授权流程

阿里云盘 PDS 支持 Authorization Code、Implicit、Password、Client Credentials 四种模式。**TV 端无浏览器、无键盘输入**，采用**设备码模式**（类似百度网盘）最契合。

⚠️ **关键差异**：PDS 官方文档未明确提供设备码端点（`/v2/oauth/device`），需抓包确认或改用「扫码登录 + 手机端回调」混合模式：

1. TV 端调起「授权页 URL」（含 `client_id` + `redirect_uri` + `state`）
2. 生成二维码（电视显示），用户手机扫码打开授权页
3. 用户手机登录阿里云盘账号并同意授权
4. 回调地址（`redirect_uri`）收到 `code`
5. TV 端轮询或二维码页面 postMessage 获取 `code`
6. TV 端用 `code` 换 `access_token` + `refresh_token`

**实施前的硬阻塞**：需要在 [阿里云 PDS 控制台](https://pds.console.alibabacloud.com) 注册应用，获取 `AppId` 和 `AppSecret`，配置 `redirect_uri`。此步骤必须由项目维护者完成，**写入 `keystore.properties`（gitignored）**，与百度网盘的 `baiduAppId` / `baiduAppSecret` 模式一致。

### 3.5 文件列表 / 搜索

PDS `POST /v2/file/list`（参考阿里云官方文档）：

- `drive_id`：空间 id（默认 `1`，用户首次授权后可从 `/v2/drive/list` 获取）
- `parent_file_id`：父文件夹 id，根目录为 `"root"`
- `limit`：1~100
- `marker`：分页游标（next_marker）
- `category`：可选 `audio` 过滤
- `type`：`file` / `folder`

搜索 `POST /v2/file/search`，请求体含 `query`（如 `name contains '周杰伦'`）。

### 3.6 流媒体播放

PDS 不直接返回文件流，而是返回**签名直链**（OSS 预签名 URL，有效期 15 分钟）。流程：

1. `POST /v2/file/get_download_url` 传 `file_id` + `drive_id`
2. 响应含 `url`（OSS 直链）+ `expires`（Unix 秒）
3. ExoPlayer 用 `AliyunDriveStreamFactory` 包装 `DefaultHttpDataSource.Factory`，在 URL 即将过期时（剩余 < 2 分钟）重新调 `get_download_url` 获取新直链
4. 不需要像百度网盘那样注入特殊 UA（PDS 直链是 OSS，用普通浏览器 UA 即可）

⚠️ **与百度网盘的关键差异**：百度 dlink 有效期 8 小时，PDS 直链仅 15 分钟。`PlayerManager` 的 1000ms 轮询循环需要检查「直链是否即将过期」，过期前主动刷新，否则会出现播放中途卡死。

### 3.7 ApiProbe 漂移检测

阿里云盘 PDS 有明确的版本号（`2022-03-01`），但仍建议复用 [ApiProbe](app/src/main/java/com/nasmusic/tv/backend/network/baidu/ApiProbe.kt) 的字段指纹机制，检测接口是否静默演进：

- `AliyunDriveConfig.API_PROBE_BASELINE = ""`（上线前实测后回填）
- 在 OAuth 登录成功后，调用 `ApiProbe.computeFieldFingerprint()` 计算响应指纹
- 与基线不一致时，通过 `AppPreferences.aliyunApiDriftNotified` 去重提示

---

## 4. UI 接入

### 4.1 连接入口

复用现有 [NetdiskScreen.kt](app/src/main/java/com/nasmusic/tv/ui/screens/netdisk/NetdiskScreen.kt) 框架，增加「网盘源切换」Tab：

```
网盘
├─ 百度网盘（现有）
└─ 阿里云盘（新增）
```

切换逻辑：
- `MainViewModel.netdiskSource: StateFlow<NetdiskSource>`（`BAIDU` | `ALIYUN`）
- `NetdiskScreen` 顶部增加源切换 Row，选中态高亮
- 切换时清空当前目录、重载新源的根目录

### 4.2 ⚠️ 按钮颜色规范（用户明确要求）

**新增界面中的所有按钮必须显式指定文字颜色，未选中状态用亮色**。

参考 [SettingsScreen.kt:352](app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:352) 修复后的规范：

```kotlin
Text(
    text = label,
    color = LocalFocusableContentColor.current,  // 强制指定，随焦点状态变化
    fontSize = FontSize.body(),
    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
)
```

**阿里云盘相关界面必须遵守的规则**：

| 组件 | 选中态 | 未选中态 |
|------|--------|---------|
| 网盘源切换 Tab | 背景 `Primary.copy(alpha=0.18f)`，文字加粗 | 背景透明，**文字 `NasMusicColors.TextPrimary`（亮色）** |
| 目录浏览按钮 | `Primary` 背景，文字 `TextPrimary` | 文字 `TextPrimary`（亮色） |
| 搜索按钮 | `Primary` 背景 | 文字 `TextPrimary`（亮色） |
| 全部播放 / 上级目录 | `Primary` 背景 | 文字 `TextPrimary` |

**禁止**：使用默认 Material3 文字颜色（可能是 `LocalContentColor.current` 默认值，对深色背景对比度不足）。

**Code Review 检查项**：阿里云盘新增界面 PR 必须包含「按钮颜色规范检查」一项，未显式指定 `color` 的 `Text` 一律打回。

---

## 5. 数据层

### 5.1 AppPreferences 扩展

在 [AppPreferences.kt](app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt) 新增：

```kotlin
// --- 阿里云盘配置 ---
private val keyAliyunDriveDomainId = stringPreferencesKey("aliyun_drive_domain_id")
private val keyAliyunDriveAppId = stringPreferencesKey("aliyun_drive_app_id")  // 仅缓存，主源在 BuildConfig
private val keyAliyunDriveTokens = stringPreferencesKey("aliyun_drive_tokens")  // 加密存储
private val keyAliyunDriveDefaultDriveId = stringPreferencesKey("aliyun_drive_default_drive_id")
private val keyAliyunApiDriftNotified = booleanPreferencesKey("aliyun_api_drift_notified")

val aliyunDriveTokens: Flow<AliyunTokens?> = dataStore.data.map { ... }
suspend fun saveAliyunDriveTokens(tokens: AliyunTokens) { ... }
suspend fun clearAliyunDriveTokens() { ... }
```

`AliyunTokens` 数据类：

```kotlin
data class AliyunTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,        // Unix 毫秒
    val defaultDriveId: String,
    val userId: String
)
```

### 5.2 CloudDriveType 扩展

[CloudDriveType](app/src/main/java/com/nasmusic/tv/data/model/CloudDriveType.kt) 已有枚举，增加：

```kotlin
enum class CloudDriveType(val key: String, val displayName: String) {
    BAIDU("baidu", "百度网盘"),
    ALIYUN("aliyundrive", "阿里云盘");  // 新增
}
```

### 5.3 SearchAggregator 扩展

[SearchAggregator](app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt) 增加阿里云盘源，跨源搜索时与百度网盘并行查询、合并去重。

---

## 6. 实施分批

> 每批独立可验证、可回滚。批与批之间通过 PR 隔离。

### Batch 1：基础设施（无 UI）

- [ ] `AliyunDriveConfig.kt` 常量表
- [ ] `AliyunTokens`、`AliyunDriveFile` 数据类
- [ ] `AliyunDriveOAuthClient.kt`（OAuth 2.0 流程）
- [ ] `AppPreferences` 阿里云盘配置字段
- [ ] `keystore.properties` 增加 `aliyunAppId` / `aliyunAppSecret` / `aliyunDomainId`
- [ ] `build.gradle.kts` 读取并注入 `BuildConfig`
- [ ] 单元测试：OAuth 流程、Token 刷新

### Batch 2：文件列表 + 搜索

- [ ] `AliyunDriveApi.kt`（listFile / searchFile）
- [ ] `AliyunDriveFileIndexCache.kt`（本地索引）
- [ ] `MainViewModel` 阿里云盘目录状态
- [ ] `NetdiskScreen` 增加网盘源切换 Tab（遵守按钮颜色规范）
- [ ] 单元测试：文件列表解析、分页

### Batch 3：流媒体播放

- [ ] `AliyunDriveStreamFactory.kt`（ExoPlayer DataSource）
- [ ] `PlayerManager` 直链过期检测 + 自动刷新
- [ ] `AliyunDriveCoverProvider.kt`、`AliyunDriveLyricsProvider.kt`、`AliyunDriveMvFileService.kt`
- [ ] 真机测试：播放 30 分钟以上长曲，验证直链自动刷新

### Batch 4：关于页面 API 版本号

- [ ] `ApiVersionInfo` 数据类 + `MainViewModel.apiVersionInfo`
- [ ] `SettingsScreen` 关于页面改造，展示第 2.1 节的全量 API 版本号表
- [ ] `BaiduNetdiskConfig` 增加 `API_DOC_VERSION = "无显式版本号"` 常量（与阿里云对齐展示规范）
- [ ] `JellyfinAdapter` / `NavidromeAdapter` / `SubsonicAdapter` / `DaoliyuAdapter` / `FeiniuAdapter` 暴露 `apiVersion` 给 ViewModel

### Batch 5：漂移检测

- [ ] `AliyunDriveConfig.API_PROBE_BASELINE` 实测后回填
- [ ] `ApiProbe` 适配阿里云响应结构
- [ ] 漂移提示 UI

---

## 7. 风险

### 7.1 PDS 设备码模式不确定

阿里云 PDS 官方文档只描述 Authorization Code、Implicit、Password、Client Credentials 四种模式，**未明确设备码模式**。TV 端无键盘输入，必须用设备码或扫码混合模式。

**缓解**：Batch 1 前先做一次抓包/原型验证，确认 PDS 是否支持设备码；若不支持，改用「二维码 + 手机浏览器授权 + 回调拦截」混合方案（电视显示二维码，用户手机扫码，TV 端轮询 token 端点或拦截 callback）。

### 7.2 直链有效期短

PDS 下载直链仅 15 分钟有效（百度 dlink 8 小时）。长曲播放或暂停后恢复可能卡死。

**缓解**：`PlayerManager` 的 1000ms 轮询循环增加「直链剩余有效期」检查，剩余 < 2 分钟时主动调 `get_download_url` 刷新，无缝衔接。

### 7.3 Domain 注册门槛

阿里云 PDS 需用户在控制台创建 Domain，获取 `domainId`。普通用户可能无法完成。

**缓解**：项目维护者提供共享 Domain + AppId（写入 `keystore.properties`），用户只需授权登录；或文档引导用户自建 Domain（高级用户）。

### 7.4 接口静默演进

PDS 虽有版本号 `2022-03-01`，但阿里云可能在不改版本号的情况下增删字段。

**缓解**：Batch 5 的 `ApiProbe` 字段指纹基线固化，漂移时一次性提示。

### 7.5 按钮颜色规范遗漏

新增界面容易遗漏显式 `color` 指定，导致未选中按钮文字对比度不足。

**缓解**：Code Review 检查项强制；可加 Lint 规则（或简单的 AST 检查脚本）扫描 `Text` 未指定 `color` 的情况。

---

## 8. 验收清单

- [ ] TV 端能用阿里云盘登录（扫码或设备码）
- [ ] 能浏览阿里云盘目录、搜索歌曲
- [ ] 能播放阿里云盘音频文件，30 分钟以上不卡死
- [ ] 阿里云盘 + 百度网盘跨源搜索合并去重正确
- [ ] 关于页面展示全量 API 版本号表
- [ ] 阿里云盘相关界面所有按钮文字显式指定颜色，未选中态为亮色
- [ ] 切换语言后阿里云盘界面文案正确（i18n 完整）
- [ ] `keystore.properties` 不含明文密钥（gitignored）

---

## 9. 参考资料

- 阿里云 PDS 官方文档：https://help.aliyun.com/zh/pds/drive-and-photo-service-dev/developer-reference/api-pds-2022-03-01-overview
- OAuth 2.0 移动端接入：https://help.aliyun.com/zh/pds/drive-and-photo-service-dev/user-guide/oauth-2-0-access-process-for-mobile-applications-and-desktop-applications
- 百度网盘架构蓝本：[docs/百度网盘音乐播放开发方案.md](docs/百度网盘音乐播放开发方案.md)
- 多后端并行接入先例：[docs/archive/daoliyu-feiniu-backend-plan.md](docs/archive/daoliyu-feiniu-backend-plan.md)
- 现有按钮颜色规范：[SettingsScreen.kt:352](app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:352)
