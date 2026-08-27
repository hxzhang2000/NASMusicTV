# 道理鱼音乐 & 飞牛音乐 后端接入开发方案

> **状态**：待实施（仅方案文档，尚未编码）
> **日期**：2026-08-27
> **关联**：扩展 NASMusicTV 后端适配器层，新增两个国产音乐服务端支持

---

## 1. 背景与目标

NASMusicTV 当前后端层支持三种 NAS 音乐服务：Jellyfin、Navidrome、Subsonic。均为国际开源项目，API 公开且有成熟文档。

用户希望新增两个**国产**音乐服务端：

| 服务端 | 类型 | API 协议 | 认证方式 | 开源 |
|--------|------|----------|----------|------|
| **道理鱼音乐** | 自部署全栈音乐管理 | 自定义 REST API（Node.js/Express） | JWT Token（管理员/用户体系） | 镜像公开（Docker Hub） |
| **飞牛音乐** | 飞牛私有云（fnOS）内置音乐服务 | 自定义 fnOS Music API | Cookie（飞牛账号登录态） | 非开源（fnOS 平台内置） |

两者均**不兼容 Subsonic / Jellyfin API**，需要编写独立的 `BackendAdapter` 实现。

### 目标

- 新增 `DaoliyuAdapter`：对接道理鱼音乐 REST API
- 新增 `FeiniuAdapter`：对接飞牛音乐 fnOS API
- 设置页新增两种后端类型选项
- **设置 → 关于页面** 增加各后端 API 支持版本号展示

---

## 2. 当前后端架构分析

### 2.1 BackendAdapter 接口

```
app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt
```

核心方法（~25 个）：

| 方法 | 用途 | 两个新后端是否需要 |
|------|------|-------------------|
| `initialize(baseUrl, apiToken, username, password)` | 连接 + 认证 | ✅ 必须 |
| `testConnection()` | 测连不改变状态 | ✅ 必须 |
| `getAlbums()` | 专辑列表 | ✅ |
| `getAlbumSongs(albumId)` | 专辑内歌曲 | ✅ |
| `getArtists()` | 歌手列表 | ✅ |
| `getArtistSongs(artistId, artistName)` | 歌手歌曲 | ✅ |
| `getSongs(limit, offset)` | 歌曲分页 | ✅ |
| `getSongsTotalCount()` | 歌曲总数 | ✅ |
| `getSongsByIds(ids)` | 按 ID 批量查 | ✅（最近播放/收藏用） |
| `getYears()` | 年份列表 | ⚠️ 可选（默认空） |
| `searchSongs(query)` | 搜索 | ✅ |
| `getRecentSongs()` | 最近添加 | ✅ |
| `getStreamUrl(songId)` | 播放流地址 | ✅ 必须 |
| `getCoverUrl(songId)` | 封面地址 | ✅ |
| `getCoverUrlCandidates(song)` | 封面候选列表 | ⚠️ 可选 |
| `getLyrics(songId)` | 歌词 | ✅ |
| `getGenres()` | 流派 | ⚠️ 可选 |
| `getSongsByGenre(genre)` | 按流派查歌 | ⚠️ 可选 |
| `getSongsByYearRange(from, to)` | 按年代查歌 | ⚠️ 可选 |
| `getPlaylists()` | 歌单列表 | ⚠️ 可选 |
| `getPlaylistSongs(playlistId)` | 歌单歌曲 | ⚠️ 可选 |
| `toggleFavorite(songId)` | 收藏切换 | ⚠️ 可选 |
| `getFavorites()` | 收藏列表 | ⚠️ 可选 |
| `getRandomSongs(limit)` | 随机歌曲 | ⚠️ 可选 |
| `getSongTechnicalInfo(songId)` | 技术信息 | ⚠️ 可选 |
| `scrobblePlay(songId, timestamp)` | 播放记录 | ⚠️ 可选 |
| `logout()` | 登出释放 session | ✅ |
| `close()` | 释放 OkHttp 资源 | ✅ |

> ⚠️ 标注的方法有默认实现（返回空），新适配器可按需覆盖，不必强制实现全部。

### 2.2 BackendRegistry

```
app/src/main/java/com/nasmusic/tv/backend/BackendRegistry.kt
```

核心逻辑：

```kotlin
val adapter = when (config.backendType) {
    TYPE_JELLYFIN -> JellyfinAdapter()
    TYPE_NAVIDROME -> NavidromeAdapter()
    TYPE_SUBSONIC -> SubsonicAdapter()
    else -> return false
}
```

需要新增两个分支：`TYPE_DAOLIYU` 和 `TYPE_FEINIU`。

### 2.3 ServerConfig

```
app/src/main/java/com/nasmusic/tv/data/model/ServerConfig.kt
```

当前常量：

```kotlin
const val TYPE_JELLYFIN = "jellyfin"
const val TYPE_NAVIDROME = "navidrome"
const val TYPE_SUBSONIC = "subsonic"
```

需要新增：

```kotlin
const val TYPE_DAOLIYU = "daoliyu"
const val TYPE_FEINIU = "feiniu"
```

### 2.4 现有适配器参考实现

| 适配器 | 文件 | API 协议 | 参考价值 |
|--------|------|----------|----------|
| JellyfinAdapter | `backend/impl/JellyfinAdapter.kt`（1108 行） | Jellyfin REST API | 全量实现，认证/搜索/歌词/封面/技术信息齐全 |
| NavidromeAdapter | `backend/impl/NavidromeAdapter.kt`（872 行） | Subsonic API（`/rest/*`） | 全量实现，REST + XML 解析 |
| SubsonicAdapter | `backend/impl/SubsonicAdapter.kt` | Subsonic API | 与 Navidrome 类似，有单测 |

---

## 3. 道理鱼音乐服务端分析

### 3.1 基本信息

| 项目 | 值 |
|------|-----|
| 官网 | https://daoliyu.cn（原 dlyu.cn 已迁移） |
| 移动端 | https://www.amcfy.com/（箭头音乐，官方合作后端） |
| 后端 | Node.js / Express（TypeScript） |
| 前端 | React（TypeScript + Vite） |
| 数据库 | PostgreSQL（推荐）/ MySQL / SQLite |
| 缓存 | Redis |
| Docker 镜像 | `msmkls/daoliyu-backend:latest`、`msmkls/daoliyu-frontend:latest` |
| 后端默认端口 | 4000 |
| 前端默认端口 | 5173（映射到 8080） |
| 当前版本 | v1.0.1（2026-08-15） |
| API 协议 | 自定义 REST API（JSON） |
| 认证 | 用户名（邮箱）+ 密码（JWT Token） |
| 飞牛 NAS | 支持 FPK 安装（应用商店搜索"道理鱼音乐"） |
| 开源 | ❌ 闭源（"不是开源项目，暂不提供 API 接口"） |
| API 文档 | ❌ 无公开文档（需反向工程） |

### 3.2 API 特性（基于公开信息推断）

**认证流程**：
1. `POST /auth/login`（或类似端点）→ 传 `{ email, password }` → 返回 `{ token, user }`
2. 后续请求带 `Authorization: Bearer <token>` 或 Cookie

**核心端点（待文档确认）**：
- 歌曲列表 / 分页
- 专辑列表 / 专辑内歌曲
- 歌手列表 / 歌手歌曲
- 搜索
- 播放流（令牌式音频流 + HLS）
- 封面 / 歌词
- 收藏 / 歌单
- 有声书 / 音乐视频

> ⚠️ 道理鱼 API 文档未完全公开，实际端点需通过以下方式确认：
> 1. 部署实例后抓包（Chrome DevTools Network）
> 2. 查看后端源码（如果开源）
> 3. 联系开发者获取 API 文档

### 3.3 技术要点

- **音频流**：支持令牌式音频流 + HLS，流地址可能需要带 token 参数
- **转码**：服务端集成 FFmpeg 实时转码，支持自动生成指定码率缓存
- **元数据**：自动解析 ID3 元数据、封面、歌词并写入数据库
- **插件系统**：支持自定义插件目录与元数据服务
- **GBK 编码**：国产服务端，可能存在 GBK/UTF-8 编码问题（参考现有 `EncodingUtils`）

---

## 4. 飞牛音乐服务端分析

### 4.1 基本信息

| 项目 | 值 |
|------|-----|
| 平台 | 飞牛私有云（fnOS） |
| 类型 | fnOS 内置音乐服务 |
| API 协议 | 自定义 fnOS Music API |
| 认证 | Cookie（飞牛账号登录态） |
| 开源 | 否（fnOS 平台内置） |
| 第三方客户端 | github.com/kuilei0926/FeiNiuMusic（Flutter，文档化了 API） |
| 增强服务 | FnMusicEnhance（端口 38200，提供歌词修改/编辑等增强功能） |
| 桥接方案 | "音桥"（fn-music-bridge）可将飞牛音乐转接为 Subsonic/Jellyfin 等协议 |

### 4.2 API 特性（FeiNiuMusic 项目逆向工程）

> **来源**：github.com/kuilei0926/FeiNiuMusic（Flutter 第三方客户端，逆向了飞牛音乐 API）
> 非官方文档，但来自实际可用的客户端，可信度高。

**认证流程**：
1. `POST /music/api/v1/user/password-login` — Body: `{ username, password: sha256(原始密码), deviceId }` → 返回 `{ token: "music-token-xxx" }`
2. 后续请求带 `Cookie: music-token=<token>; mode=relay`
3. OkHttp 需配置 CookieJar 维持登录态

**已知 API 端点**：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/music/api/v1/user/password-login` | POST | 用户登录（SHA256 密码哈希） |
| `/music/api/v1/track/list` | GET | 曲目列表（分页） |
| `/music/api/v1/track/search` | GET | 搜索曲目 |
| `/music/api/v1/track/album-detail/list` | GET | 专辑详情内曲目 |
| `/music/api/v1/track/artist-detail/list` | GET | 歌手详情内曲目 |
| `/music/api/v1/genre/list` | GET | 风格列表 |
| `/music/api/v1/track/genre-detail/list` | GET | 风格下曲目 |
| `/music/api/v1/playlist/list` | GET | 歌单列表 |
| `/music/api/v1/favorite/add` | POST | 添加收藏 |
| `/music/api/v1/favorite/remove` | POST | 取消收藏 |

**流媒体播放**：返回 HLS 相对路径（以 `/music/api/v1` 开头），需拼接 baseUrl

**特殊能力**：
- CUE 整轨支持（按 CUE 索引拆分整轨专辑）
- DLNA 投屏
- 无损格式支持（FLAC / DSF / DSD 等）

### 4.3 桥接方案备选

如果不直接对接飞牛 API，可通过"音桥"（fn-music-bridge）间接接入：
- 音桥将飞牛音乐转接为 Subsonic / Jellyfin Audio / Audio Station / Ampache API
- NASMusicTV 已有 SubsonicAdapter，用户安装音桥后可直接用 Subsonic 模式连接
- **但**：音桥是独立 FPK，需要用户额外安装，体验不如直连

---

## 5. 实施方案

### 5.1 整体架构

```
backend/
├── BackendAdapter.kt           # 接口（不变）
├── BackendRegistry.kt         # 注册表（新增两个分支）
├── SearchAggregator.kt        # 聚合器（不变）
└── impl/
    ├── JellyfinAdapter.kt     # 现有
    ├── NavidromeAdapter.kt    # 现有
    ├── SubsonicAdapter.kt     # 现有
    ├── DaoliyuAdapter.kt     # 🆕 道理鱼适配器
    └── FeiniuAdapter.kt       # 🆕 飞牛适配器
```

### 5.2 ServerConfig 变更

```kotlin
// data/model/ServerConfig.kt
companion object {
    const val TYPE_JELLYFIN = "jellyfin"
    const val TYPE_NAVIDROME = "navidrome"
    const val TYPE_SUBSONIC = "subsonic"
    const val TYPE_DAOLIYU = "daoliyu"      // 🆕
    const val TYPE_FEINIU = "feiniu"        // 🆕
}
```

### 5.3 BackendRegistry 变更

```kotlin
// backend/BackendRegistry.kt
val supportedTypes: List<String> get() = listOf(
    TYPE_JELLYFIN, TYPE_NAVIDROME, TYPE_SUBSONIC,
    TYPE_DAOLIYU, TYPE_FEINIU  // 🆕
)

// initialize / testConnection 的 when 分支新增：
TYPE_DAOLIYU -> DaoliyuAdapter()
TYPE_FEINIU -> FeiniuAdapter()

// getTypeName 新增：
TYPE_DAOLIYU -> "道理鱼音乐"
TYPE_FEINIU -> "飞牛音乐"
```

### 5.4 DaoliyuAdapter 实现计划

```
app/src/main/java/com/nasmusic/tv/backend/impl/DaoliyuAdapter.kt
```

**核心字段**：
- `baseUrl`：道理鱼后端地址（默认端口 4000）
- `token`：JWT Token
- `userId`：当前用户 ID
- `client`：OkHttpClient（守护线程池 + 信任所有证书，与 JellyfinAdapter 一致）

**必须实现的方法**：

| 方法 | 预期 API 端点 | 备注 |
|------|--------------|------|
| `initialize` | `POST /auth/login` | 传 email/password → 拿 token |
| `testConnection` | `GET /health` | 道理鱼有健康检查端点 |
| `getAlbums` | `GET /api/albums?page=&limit=` | 分页 |
| `getAlbumSongs` | `GET /api/albums/{id}/songs` | |
| `getArtists` | `GET /api/artists?page=&limit=` | 分页 |
| `getArtistSongs` | `GET /api/artists/{id}/songs` | |
| `getSongs` | `GET /api/songs?page=&limit=` | 分页 |
| `getSongsTotalCount` | `GET /api/songs?limit=0` | 取 total |
| `getSongsByIds` | `GET /api/songs?ids=` | 批量 |
| `searchSongs` | `GET /api/songs?search=` | |
| `getRecentSongs` | `GET /api/songs?sort=createdAt&order=desc` | |
| `getStreamUrl` | `GET /api/songs/{id}/stream?token=` | 令牌式流 |
| `getCoverUrl` | `GET /api/songs/{id}/cover` 或 `GET /api/albums/{id}/cover` | |
| `getLyrics` | `GET /api/songs/{id}/lyrics` | |
| `getPlaylists` | `GET /api/playlists` | |
| `getPlaylistSongs` | `GET /api/playlists/{id}/songs` | |
| `toggleFavorite` | `POST /api/songs/{id}/favorite` | |
| `getFavorites` | `GET /api/favorites` | |
| `logout` | `POST /auth/logout` | 使 token 失效 |
| `close` | — | 关闭 OkHttp 连接池 |

> ⚠️ 以上端点为**推断**，实际端点需部署实例后抓包确认。

**可选实现**（默认返回空，后续按需补充）：
- `getYears` / `getSongsByYearRange` — 道理鱼可能不支持按年代筛选
- `getGenres` / `getSongsByGenre` — 道理鱼可能不支持流派
- `getSongTechnicalInfo` — 取决于 API 是否返回码率/采样率/编码格式
- `scrobblePlay` — 道理鱼可能有自己的播放统计
- `getRandomSongs` — 道理鱼可能有随机推荐端点

### 5.5 FeiniuAdapter 实现计划

```
app/src/main/java/com/nasmusic/tv/backend/impl/FeiniuAdapter.kt
```

**核心字段**：
- `baseUrl`：飞牛 NAS 地址
- `cookie`：飞牛账号登录 Cookie（`music-token=<token>`）
- `userId`：飞牛用户 ID
- `client`：OkHttpClient（配置 CookieJar 维持登录态）

**必须实现的方法**（端点来自 FeiNiuMusic 逆向工程）：

| 方法 | API 端点 | 方法 | 备注 |
|------|----------|------|------|
| `initialize` | `/music/api/v1/user/password-login` | POST | Body: `{ username, password: sha256(pwd), deviceId }` → 拿 token |
| `testConnection` | `/music/api/v1/user/password-login` | POST | 同上，成功即通 |
| `getAlbums` | `/music/api/v1/track/album-detail/list` | GET | 分页 |
| `getAlbumSongs` | `/music/api/v1/track/album-detail/list?albumId=` | GET | |
| `getArtists` | `/music/api/v1/track/artist-detail/list` | GET | 分页 |
| `getArtistSongs` | `/music/api/v1/track/artist-detail/list?artistId=` | GET | |
| `getSongs` | `/music/api/v1/track/list` | GET | 分页 `?page=&limit=` |
| `getSongsTotalCount` | `/music/api/v1/track/list?limit=0` | GET | 取 total |
| `getSongsByIds` | `/music/api/v1/track/list?ids=` | GET | 批量（待确认是否支持） |
| `searchSongs` | `/music/api/v1/track/search?query=` | GET | |
| `getStreamUrl` | `/music/api/v1/track/{id}/stream` | GET | 返回 HLS 路径，带 Cookie |
| `getCoverUrl` | `/music/api/v1/track/{id}/cover` | GET | |
| `getLyrics` | `/music/api/v1/track/{id}/lyrics` | GET | 可能需 FnMusicEnhance（端口 38200） |
| `getPlaylists` | `/music/api/v1/playlist/list` | GET | |
| `getPlaylistSongs` | `/music/api/v1/playlist/{id}/songs` | GET | 待确认确切路径 |
| `toggleFavorite` | `/music/api/v1/favorite/add` / `/favorite/remove` | POST | |
| `getGenres` | `/music/api/v1/genre/list` | GET | |
| `getSongsByGenre` | `/music/api/v1/track/genre-detail/list?genreId=` | GET | |
| `logout` | 飞牛账号登出 | POST | 清除 Cookie |
| `close` | — | — | 关闭 OkHttp 连接池 |

> ⚠️ 标注"待确认"的端点需部署 fnOS 后抓包验证。其余端点来自 FeiNiuMusic 项目逆向，可信度高。

**特殊处理**：
- **Cookie 管理**：OkHttp 需配置 CookieJar 维持 `music-token` 登录态
- **密码哈希**：登录前需对密码做 SHA256 哈希
- **HLS 流**：飞牛返回 HLS 相对路径，需拼接 baseUrl + 处理 m3u8
- **CUE 整轨**：`getAlbumSongs` 可能返回虚拟曲目（CUE 索引），需正确映射

---

## 6. API 版本号管理

### 6.1 设计

在 `BackendAdapter` 接口新增只读属性：

```kotlin
interface BackendAdapter {
    /** 后端 API 协议版本号（供设置→关于页展示） */
    val apiVersion: String   // 🆕 例如 "Jellyfin API 10.8" / "Subsonic API 1.16" / "Daoliyu API 0.2.4"
    // ... 现有方法
}
```

各适配器覆盖：

| 适配器 | apiVersion 值 | 来源 |
|--------|---------------|------|
| JellyfinAdapter | `"Jellyfin API 10.9"` | Jellyfin 当前稳定版 API 版本 |
| NavidromeAdapter | `"Subsonic API 1.16.1"` | Navidrome 兼容的 Subsonic 版本 |
| SubsonicAdapter | `"Subsonic API 1.16"` | Subsonic 协议版本 |
| DaoliyuAdapter | `"Daoliyu API 0.2.4"` | 道理鱼服务端版本（从 `/api/version` 或 `/health` 获取） |
| FeiniuAdapter | `"fnOS Music API"` | 飞牛音乐 API 版本（待确认） |

### 6.2 设置 → 关于页面展示

在设置页的"关于"区域新增"后端信息"卡片：

```
后端信息
├── 类型：道理鱼音乐
├── API 版本：Daoliyu API 0.2.4
├── 服务端地址：http://192.168.1.100:4000
└── 连接状态：已连接
```

未连接时显示"未连接"。

### 6.3 版本号获取方式

- **Jellyfin**：`GET /System/Info/Public` → `Version` 字段
- **Navidrome**：`GET /rest/getOpenSubsonicExtensions.view` → `serverVersion`
- **道理鱼**：`GET /health` 或 `GET /api/version` → 版本号（待确认）
- **飞牛**：fnOS 系统信息或音乐服务 `/api/music/version`（待确认）

初始化连接时缓存版本号到 `BackendAdapter.apiVersion`，设置页读取展示。

---

## 7. 设置页变更

### 7.1 连接页

新增道理鱼和飞牛两个后端选项：

```
服务器类型：
○ Jellyfin
○ Navidrome
○ Subsonic
○ 道理鱼音乐    ← 🆕
○ 飞牛音乐      ← 🆕
```

选择"道理鱼音乐"时：
- 地址栏默认提示 `http://NAS_IP:4000`
- 用户名栏标签改为"邮箱"（道理鱼用 email 登录）
- 密码栏不变

选择"飞牛音乐"时：
- 地址栏默认提示 `http://NAS_IP`（飞牛默认无独立端口，走 fnOS 主端口）
- 用户名/密码为飞牛账号

### 7.2 关于页

新增"后端信息"区域，显示当前连接的后端 API 版本号（见 §6.2）。

---

## 8. 任务分解

### 阶段一：道理鱼适配器（优先）

| # | 任务 | 文件 | 预估 |
|---|------|------|------|
| 1 | 部署道理鱼实例 + 抓包确认 API 端点 | — | 2h |
| 2 | ServerConfig 新增 TYPE_DAOLIYU | `ServerConfig.kt` | 0.5h |
| 3 | BackendRegistry 新增道理鱼分支 | `BackendRegistry.kt` | 0.5h |
| 4 | DaoliyuAdapter 骨架 + 认证 + testConnection | `impl/DaoliyuAdapter.kt` | 3h |
| 5 | getAlbums / getAlbumSongs / getArtists / getArtistSongs | 同上 | 3h |
| 6 | getSongs / getSongsTotalCount / getSongsByIds / searchSongs | 同上 | 2h |
| 7 | getStreamUrl / getCoverUrl / getLyrics | 同上 | 2h |
| 8 | getPlaylists / getPlaylistSongs / toggleFavorite / getFavorites | 同上 | 2h |
| 9 | logout / close / apiVersion | 同上 | 0.5h |
| 10 | 设置页连接选项新增道理鱼 | `SettingsScreen.kt` / `ServerConnectScreen.kt` | 1h |
| 11 | 编译 + 电视实测 | — | 2h |
| | **小计** | | **~18.5h** |

### 阶段二：飞牛适配器

| # | 任务 | 文件 | 预估 |
|---|------|------|------|
| 12 | 部署 fnOS + 抓包确认音乐 API 端点（或读 FeiNiuMusic 源码） | — | 3h |
| 13 | ServerConfig 新增 TYPE_FEINIU | `ServerConfig.kt` | 0.5h |
| 14 | BackendRegistry 新增飞牛分支 | `BackendRegistry.kt` | 0.5h |
| 15 | FeiniuAdapter 骨架 + Cookie 认证 + testConnection | `impl/FeiniuAdapter.kt` | 4h |
| 16 | getAlbums / getAlbumSongs / getArtists / getArtistSongs | 同上 | 3h |
| 17 | getSongs / getSongsTotalCount / getSongsByIds / searchSongs | 同上 | 2h |
| 18 | getStreamUrl / getCoverUrl / getLyrics（+ FnMusicEnhance 歌词） | 同上 | 2h |
| 19 | getPlaylists / getPlaylistSongs | 同上 | 1h |
| 20 | logout / close / apiVersion | 同上 | 0.5h |
| 21 | 设置页连接选项新增飞牛 | `SettingsScreen.kt` / `ServerConnectScreen.kt` | 1h |
| 22 | 编译 + 电视实测 | — | 2h |
| | **小计** | | **~19.5h** |

### 阶段三：API 版本号 + 关于页

| # | 任务 | 文件 | 预估 |
|---|------|------|------|
| 23 | BackendAdapter 接口新增 `apiVersion` 属性 | `BackendAdapter.kt` | 0.5h |
| 24 | 各适配器覆盖 apiVersion（含现有三个） | 5 个适配器 | 1h |
| 25 | 初始化连接时获取并缓存服务端版本号 | 各适配器 initialize | 1h |
| 26 | 设置 → 关于页新增"后端信息"卡片 | `SettingsScreen.kt` | 2h |
| 27 | 编译 + 测试 | — | 1h |
| | **小计** | | **~5.5h** |

### 总预估

| 阶段 | 工时 |
|------|------|
| 道理鱼适配器 | ~18.5h |
| 飞牛适配器 | ~19.5h |
| API 版本号 + 关于页 | ~5.5h |
| **合计** | **~43.5h** |

---

## 9. 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| 道理鱼 API 文档不公开 | 端点不确定 | 部署实例 + 抓包确认；联系开发者 |
| 飞牛 API 非开源 | 端点不确定 | 读 FeiNiuMusic 源码；部署 fnOS 抓包 |
| 飞牛 Cookie 过期 | 连接中断 | OkHttp CookieJar + 自动续期逻辑 |
| 道理鱼 JWT 过期 | 连接中断 | 初始化时刷新 token |
| GBK 编码问题 | 歌曲名乱码 | 复用 `EncodingUtils.fixEncoding` |
| CUE 整轨拆分 | 虚拟曲目 ID 映射 | 飞牛可能返回 CUE 索引，需特殊处理 |
| ProGuard 混淆 | Release 崩溃 | `proguard-rules.pro` keep 新适配器的数据类 |
| fnOS 版本差异 | API 不兼容 | 版本探测 + 降级处理 |

---

## 10. 备选方案

如果直连飞牛 API 难度太大（API 不公开、Cookie 认证复杂），可考虑：

1. **音桥方案**：引导用户安装"音桥"FPK，通过 Subsonic 协议间接接入飞牛音乐
   - 优点：无需写 FeiniuAdapter，复用现有 SubsonicAdapter
   - 缺点：用户需额外安装音桥，体验不如直连

2. **Navidrome 方案**：引导用户在飞牛 NAS 上 Docker 部署 Navidrome
   - 优点：完全复用 SubsonicAdapter
   - 缺点：需要额外部署，不使用飞牛原生音乐服务

建议：先实施道理鱼适配器（API 相对明确），飞牛视抓包结果决定直连还是引导音桥。

---

## 11. 变更文件清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `data/model/ServerConfig.kt` | 修改 | 新增 TYPE_DAOLIYU、TYPE_FEINIU 常量 |
| `backend/BackendAdapter.kt` | 修改 | 新增 `apiVersion` 属性 |
| `backend/BackendRegistry.kt` | 修改 | 新增道理鱼/飞牛分支 |
| `backend/impl/DaoliyuAdapter.kt` | **新增** | 道理鱼适配器实现 |
| `backend/impl/FeiniuAdapter.kt` | **新增** | 飞牛适配器实现 |
| `backend/impl/JellyfinAdapter.kt` | 修改 | 覆盖 apiVersion |
| `backend/impl/NavidromeAdapter.kt` | 修改 | 覆盖 apiVersion |
| `backend/impl/SubsonicAdapter.kt` | 修改 | 覆盖 apiVersion |
| `ui/screens/SettingsScreen.kt` | 修改 | 连接选项 + 关于页后端信息 |
| `ui/screens/ServerConnectScreen.kt` | 修改 | 新增道理鱼/飞牛选项 |
| `proguard-rules.pro` | 修改 | keep 新适配器数据类 |
| `app/src/main/res/values/strings.xml` | 修改 | 新增道理鱼/飞牛相关字符串 |
| `CHANGELOG.md` | 修改 | 记录变更 |
| `docs/technical-overview.md` §10 | 修改 | 记录实施变更 |

---

## 12. 设计决策补充

> 以下为深度审阅后补充的设计决策，覆盖审阅发现的所有缺失项。

### 12.1 SearchAggregator 集成

**决策**：道理鱼和飞牛**复用 `MusicSourceType.NAS`** 类型参与跨源搜索，不新增枚举值。

**理由**：
- 两者都通过 `BackendAdapter.searchSongs(query)` 接口搜索
- `SearchAggregator` 的 NAS 分支已统一调用 `backendAdapter.searchSongs(keyword)`
- 道理鱼/飞牛连接后，`backendRegistry.getAdapter()` 返回对应适配器，自动参与 NAS 源搜索
- 用户在搜索页点亮"NAS"源时，搜索结果自然包含道理鱼/飞牛的歌曲

**无需改动**：
- `MusicSourceType` 枚举不变
- `SearchAggregator` 不变
- `_enabledSearchSources` 默认值不变

### 12.2 apiVersion 动态获取

**决策**：`apiVersion` 为 `var`（可变），在 `initialize()` 内从服务端获取后赋值。

```kotlin
interface BackendAdapter {
    /** 后端 API 协议版本号（initialize 时从服务端获取，供设置→关于页展示） */
    var apiVersion: String
        get() = "Unknown"  // 默认值，initialize 前为 Unknown
    // ... 现有方法
}
```

各适配器在 `initialize()` 内获取版本号：

| 适配器 | 获取方式 | 获取失败时 |
|--------|----------|-----------|
| JellyfinAdapter | `GET /System/Info/Public` → `Version` 字段 | `"Jellyfin (版本未知)"` |
| NavidromeAdapter | `GET /rest/ping.view` → `serverVersion` 属性 | `"Navidrome (版本未知)"` |
| SubsonicAdapter | 同 Navidrome | `"Subsonic (版本未知)"` |
| DaoliyuAdapter | `GET /health` 或 `GET /api/version`（⚠️端点待确认） | `"Daoliyu (版本未知)"` |
| FeiniuAdapter | fnOS 系统信息接口（⚠️端点待确认） | `"飞牛音乐 (版本未知)"` |

### 12.3 Song ID 格式

**决策**：道理鱼和飞牛的 Song ID 使用 `前缀_${原始ID}` 格式，与百度网盘的 `ntwk_baidu_${fs_id}` 一致。

| 后端 | Song ID 格式 | 示例 |
|------|-------------|------|
| Jellyfin | 原始 UUID（不变） | `a1b2c3d4-e5f6` |
| Navidrome | 原始 ID（不变） | `mf1` |
| 道理鱼 | `daoliyu_${原始ID}` | `daoliyu_42` |
| 飞牛 | `feiniu_${原始ID}` | `feiniu_track_123` |

**理由**：
- 加前缀避免与其他后端的 Song ID 冲突（用户可能切换后端，旧的 ID 缓存不能误匹配）
- 前缀在 `getSongsByIds` / `getStreamUrl` 内部解析回原始 ID 传给 API
- 持久化（最近播放/收藏/队列）的 ID 跨会话稳定

### 12.4 streamUrl 过期刷新策略

**决策**：道理鱼 JWT 和飞牛 Cookie 的过期刷新复用现有 `resolveAndPlayCurrentSong` 机制。

现有机制已覆盖：
- `playPause()` 检查 `streamUrl` 为空 → 调 `resolveAndPlayCurrentSong` 解析
- `playPause()` 检查 ExoPlayer `IDLE/ENDED` → 重新解析（URL 过期）
- `next()` / `previous()` 检查 `streamUrl` 为空 → 调 `resolveAndPlayByIndex`

道理鱼/飞牛适配器的 `resolvePlayUrl(song)` 方法负责：
- 道理鱼：用 JWT token 请求 `GET /api/songs/{id}/stream` 获取流地址
- 飞牛：带 Cookie 请求 `GET /music/api/v1/track/{id}/stream` 获取 HLS 路径

**JWT/Cookie 过期场景**：
- 道理鱼：`resolvePlayUrl` 返回 null（401）→ `showError("令牌过期，请重新连接")` → 用户重新连接
- 飞牛：同上（Cookie 过期）
- 不做自动续期（复杂度高，且用户重新连接一次即可）

### 12.5 HLS + Cookie 的 ExoPlayer 配置

**决策**：在 `PlayerManager` / `PlaybackService` 层面处理 HLS + Cookie 注入。

飞牛返回 HLS 流（m3u8），且需要 Cookie 认证。现有 ExoPlayer 配置需要增强：

1. **HLS 支持**：`MediaItem.Builder()` 设置 `.setMimeType(MimeTypes.APPLICATION_M3U8)`
2. **Cookie 注入**：自定义 `OkHttpDataSource.Factory`，在 `setRequestProperties` 注入 `Cookie: music-token=<token>`
3. **飞牛 Cookie 传递**：`FeiniuAdapter` 在 `getStreamUrl` 返回 URL 时，同时缓存 Cookie；`PlayerManager` 播放飞牛歌曲时注入

**实现方案**：
- `FeiniuAdapter.getStreamUrl(songId)` 返回完整 URL
- `FeiniuAdapter` 暴露 `val streamCookie: String?` 供 `PlayerManager` 读取
- `PlayerManager` 检查当前 `BackendAdapter` 是否为 `FeiniuAdapter`，是则注入 Cookie header

或更通用的方案：
- `BackendAdapter` 新增 `val streamHeaders: Map<String, String>` 默认空
- `PlayerManager` 播放时读取 `streamHeaders` 注入到 `HttpDataSource.Factory`

**采用通用方案**——`streamHeaders` 属性，所有适配器可按需覆盖。

### 12.6 飞牛 deviceId 生成策略

**决策**：App 内自动生成 UUID 并持久化到 `AppPreferences`。

```kotlin
// AppPreferences 新增
private val keyFeiniuDeviceId = stringPreferencesKey("feiniu_device_id")
val feiniuDeviceId: String = context.dataStore.data.map { it[keyFeiniuDeviceId] ?: "" }
    .stateIn(scope, SharingStarted.Eagerly, "").value.let {
        if (it.isBlank()) {
            val newId = UUID.randomUUID().toString()
            // 同步保存
            newId
        } else it
    }
```

`FeiniuAdapter.initialize()` 内读取/生成 deviceId，用于登录请求。

### 12.7 道理鱼认证设计（推断）

⚠️ 以下认证流程为**推断**，需部署实例抓包确认。

```
1. POST /api/auth/login
   Body: { email: "user@example.com", password: "明文密码" }
   Response: { token: "jwt-xxx", user: { id: "42", name: "管理员" } }

2. 后续请求 Header: Authorization: Bearer <token>
   或 Cookie: token=<jwt-xxx>

3. 登出: POST /api/auth/logout
```

**代码标注**：所有推断端点用 `// ⚠️ INFERRED: 实际端点需部署实例抓包确认` 注释标注。

### 12.8 响应 JSON 结构设计（推断）

⚠️ 道理鱼和飞牛的 API 响应 JSON 结构为**推断**。以下为预期结构，实际需抓包确认。

**道理鱼分页响应**（推断）：
```json
{
  "data": [...],
  "total": 100,
  "page": 1,
  "limit": 50
}
```

**飞牛分页响应**（从 FeiNiuMusic 逆向）：
```json
{
  "code": 0,
  "data": {
    "list": [...],
    "total": 100,
    "page": 1,
    "limit": 50
  }
}
```

**数据类设计**：
```kotlin
// 道理鱼
data class DaoliyuPaginatedResponse<T>(
    val data: List<T>,
    val total: Int,
    val page: Int,
    val limit: Int
)

// 飞牛
data class FeiniuResponse<T>(
    val code: Int,
    val data: T,
    val message: String?
)
data class FeiniuPaginatedData<T>(
    val list: List<T>,
    val total: Int,
    val page: Int,
    val limit: Int
)
```

> ⚠️ 以上数据类结构需抓包确认后调整。

### 12.9 编码约定

所有推断/未确认的端点、数据结构，在代码中用以下注释标注：

```kotlin
// ⚠️ INFERRED: POST /api/auth/login — 实际端点需部署实例抓包确认
// ⚠️ INFERRED: 响应结构 { token, user } 需抓包确认
// ⚠️ REVERSE_ENGINEERED: /music/api/v1/track/list — 来自 FeiNiuMusic 逆向，可信但非官方
// ⚠️ UNCONFIRMED: getSongsByIds 是否支持批量查询参数
```

### 12.10 测试策略

- **适配器单测**：参考 `SubsonicAdapterTest`，用 MockWebServer 模拟 API 响应
- **端点确认后**：部署实例做集成测试
- **先写骨架**：认证 + getSongs + getStreamUrl + searchSongs 四个核心方法先实现可编译，其余方法返回空（默认实现），后续逐步填充
