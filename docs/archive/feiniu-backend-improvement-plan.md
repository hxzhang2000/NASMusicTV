# 飞牛音乐（fnOS）后端对接改进开发计划

> 版本：v1.0 · 日期：2026-09-14 · 目标版本：**v2.32.4**
> 参考实现：`fn-music-tv` v1.1.2（来源 https://github.com/QiaoKes/fn-music-tv，本地副本 `D:\hxzhang\MyGithubSoftware\NasAudio\fn-music-tv-v1-1-2`）
> 相关旧文档：`docs/archive/daoliyu-feiniu-backend-plan.md`（基于逆向推断，**本文件为其勘误与替代实现方案**）

---

## 0. 一句话结论

我们现有的 `FeiniuAdapter` 是一份**基于第三方逆向文章的猜测实现**，与飞牛官方客户端的真实协议**在认证方式、分页参数、端点路径、ID 语义四个层面均不一致**——按现有代码几乎必然连不上或连上后全量 401/404。参考项目 `fn-music-tv` 是一个可运行的飞牛 TV 客户端，其 `core/data` 模块完整保留了真实协议，本计划据此重写对接层。

---

## 1. 背景与目标

### 1.1 背景

- 本仓库自 `docs/archive/daoliyu-feiniu-backend-plan.md` 阶段引入飞牛音乐后端（`ServerConfig.TYPE_FEINIU = "feiniu"`），实现文件为 `app/src/main/java/com/nasmusic/tv/backend/impl/FeiniuAdapter.kt`（约 669 行）。
- 该实现的 API 端点大量标注 `[REVERSE_ENGINEERED]` / `[UNCONFIRMED]`，来源为 `FeiNiuMusic` 项目的逆向文章，**未经真机验证**。
- 用户提供了参考项目 `fn-music-tv`（可运行的飞牛音乐 TV 客户端），其中包含完整、自洽、带契约文档的真实 API 客户端实现，可作为权威依据。

### 1.2 目标

| 编号 | 目标 |
| --- | --- |
| G1 | 用真实协议替换猜测实现，使飞牛音乐后端**可连接、可浏览、可播放、可显示封面与歌词** |
| G2 | 修复播放/封面链路**认证头不生效**的架构缺口（当前 `BackendAdapter.streamHeaders` 定义了但无人消费） |
| G3 | 补齐版本信息（`sys/config`）、技术信息（`track/metadata`）、收藏、歌单等能力 |
| G4 | 全部改动通过本地 `assembleDebug` + `lintDebug`（本机无法跑单测，见 AGENTS.md） |

### 1.3 非目标（本次不做）

- **FNID 远程连接 / 中继模式（relay）**：参考项目的 `ConnectionResolver` + `FnConnectSigner` 依赖 Release 构建期注入的签名密钥（`FN_CONNECT_AUTHX_PREFIX` / `FN_CONNECT_API_KEY`），且需访问外网 `5ddd.com` 做 FNID 解析。本仓库无此构建配置，纳入会显著扩大风险面。
- **安全码（access code）**：`x-access-code` / `x-access-source` 属可选能力，飞牛未开启时无影响；本次预留接口但不做 UI。
- **共享库（shared-library）选择**：本仓库为单服务器模型，暂不引入多库切换。

---

## 2. 真实协议提取结果（来自 fn-music-tv）

以下全部内容均有参考项目源码与 `.trellis/spec/backend/android-client-contracts.md` 契约文档双重佐证。

### 2.1 服务地址归一化

来源：`core/data/.../server/ServerUrlNormalizer.kt`

| 规则 | 说明 |
| --- | --- |
| 默认端口 | **裸主机名/IP 且无显式端口 → 5666**；显式 `http://` → 80；HTTPS 无端口 → 443；显式端口一律保留 |
| 输入清洗 | 结尾的 `/music`、`/api/v1`、隐式端口会被剥离后再展示 |
| API 基址推导 | path 以 `/music/api/v1` 结尾 → 原样；以 `/music` 结尾 → `<path>/api/v1`；path 为 `/` 或空 → `/music/api/v1`；其他 → `<path>/music/api/v1` |
| 最终形态 | `apiBase = <scheme>://<host>:<port>/music/api/v1/`（**尾部带斜杠**，端点用相对路径拼接） |
| 合法性 | 仅接受 http/https；拒绝带用户名密码、query、fragment 的 URL |

### 2.2 认证

来源：`TrimMusicApi.kt` / `ConnectionResolver.kt` / `SessionRepository.kt` / 契约文档 L176-L179

| 项 | 真实值 |
| --- | --- |
| 登录端点 | `POST {apiBase}user/password-login` |
| 登录 Body | `{"username": "<用户名>", "password": "<sha256(明文) 小写 64 位 hex>", "deviceId": "<安装级稳定 UUID>"}` |
| 登录是否需认证头 | **否**（`authenticated = false`） |
| 登录响应 | `{code, msg, data: {userToken: "<token>", user: {guid, name}}}` |
| **认证头** | **`Authorization: <userToken>`（原始值，无 `Bearer` 前缀）** |
| 中继模式附加头 | `Cookie: music-token=<token>; mode=relay`（仅 relay，本次不启用） |
| 安全码（可选） | `x-access-code: base64(code)` + `x-access-source: app`；探测 `GET {origin}/access_code_verify` |
| 令牌校验 | `GET {apiBase}user/me` → `{guid, name}` |
| 登出 | `POST {apiBase}user/logout` |

### 2.3 响应信封与错误码

来源：`api/ApiContract.kt`

```json
{ "code": 0, "msg": "", "data": { ... } }
```

- `code != 0` 即失败，`msg` 为服务端描述；`data` **可能为 `null`**（如收藏增删成功时）。
- 错误码映射：

| code | 含义 |
| --- | --- |
| `99999` / `120001` | 未认证（令牌失效） |
| `120002` | 账号被禁用 |
| `100004` | 曲目不可用 |
| `100005` | 资源不存在 |

### 2.4 端点清单（相对 `apiBase`）

来源：`api/TrimMusicApi.kt`、`api/Dto.kt`

| 功能 | 方法 | 路径 | 关键参数 | 响应 data |
| --- | --- | --- | --- | --- |
| 系统信息 | GET | `sys/config` | 无需认证 | `{serverGUID, serverName, serverVersion, mediasrvVersion}` |
| 登录 | POST | `user/password-login` | body | `{userToken, user}` |
| 当前用户 | GET | `user/me` | — | `{guid, name}` |
| 登出 | POST | `user/logout` | — | 空 |
| 歌单列表 | GET | `playlist/list` | — | `{list:[{guid,name,coverId}]}` |
| 歌单详情 | GET | `playlist/detail` | `guid` | `{guid,name,coverId,trackCount}` |
| 歌单曲目 | GET | `track/playlist-detail/list` | `playlistGUID`,`page`,`size`,`sort=trackAddedAt,desc` | `{list:[Track],total,sort}` |
| 歌手列表 | GET | `artist/list` | `page`,`size`,`sort=trackCount,desc` | `{list:[Artist],total}` |
| 歌手详情 | GET | `artist/detail` | `guid` | `Artist` |
| 歌手曲目 | GET | `track/artist-detail/list` | `artistGUID`,`page`,`size`,`sort=createdAt,desc` | `{list:[Track],total}` |
| 歌手专辑 | GET | `album/artist-detail/list` | `artistGUID`,`page`,`size`,`sort=newTrackAddedAt,desc` | `{list:[Album],total}` |
| 专辑列表 | GET | `album/list` | `page`,`size`,`sort=newTrackAddedAt,desc` | `{list:[Album],total}` |
| 专辑详情 | GET | `album/detail` | `guid` | `Album` |
| 专辑曲目 | GET | `track/album-detail/list` | `albumGUID`,`page`,`size`,`sort=trackNo,asc` | `{list:[Track],total}` |
| 全部曲目 | GET | `track/list` | `page`,`size`,`sort=createdAt,desc` | `{list:[Track],total}` |
| 收藏列表 | GET | `favorite-track/list` | `page`,`size`,`sort=favoriteAt,desc` | `{list:[Track],total}` |
| 添加收藏 | POST | `favorite-track/create` | body `{"trackGUID":"..."}` | 可 null |
| 删除收藏 | POST | `favorite-track/delete` | body `{"trackGUID":"..."}` | 可 null |
| 共享库 | GET | `shared-library/list` | — | `{list:[{guid,name,accessStatus,updatedAt}]}` |
| 曲目元数据 | GET | `track/metadata` | `guid` | `{track, audioSpec}` |
| 歌词 | GET | `lyric/list` | `trackGUID` | `{list:[{guid,content,isLRC,offset}], preferred}` |
| 漫游起始 | GET | `track/roam-start` | `deviceId` | `{current, next}` |
| 播放流 | GET | `track/stream` | **`guid`（查询参数）** | 音频二进制 |
| 封面 | GET | `static/cover` | **`coverId`, `size`** | 图片二进制 |

### 2.5 DTO 字段语义（易错点）

```kotlin
TrackDto(guid, title, coverId, duration /* 毫秒 */, isCue, album: AlbumDto?,
         artists: List<ArtistDto>, audioSpec: AudioSpecDto, accessStatus: Int?, isFavorite)
AlbumDto(guid, name, coverId, releaseDate /* "2019-05-01" 之类 */, artists, trackCount)
ArtistDto(guid, name, coverId, trackCount, albumCount)
AudioSpecDto(codec, container, duration, bitrate)
LyricDto(guid, content, isLRC, offset)
```

关键语义：

1. **所有 ID 都是 GUID 字符串**，不是数字。
2. `duration` **单位已是毫秒**，无需 ×1000。
3. 封面**按 `coverId` 取**，不是按曲目 ID；`static/cover?coverId=<id>&size=<px>`。
4. 分页参数是 **`page`（从 1 开始）+ `size`**，不是 `limit`/`offset`。
5. 排序参数格式为 `sort=字段名,方向`（如 `createdAt,desc`）。
6. `artists` 是数组，展示时需 `joinToString(" / ")`。
7. 歌词返回**列表**，`isLRC` 标记是否带时间轴，另有 `offset`（毫秒偏移）与 `preferred`（推荐项 guid）。
8. 参考项目**没有搜索端点**——搜索需客户端本地过滤。

---

## 3. 现有实现缺陷对照表

对照 `app/src/main/java/com/nasmusic/tv/backend/impl/FeiniuAdapter.kt`（当前代码）。

| # | 位置 | 现有实现 | 真实协议 | 后果 | 严重度 |
| --- | --- | --- | --- | --- | --- |
| D1 | 认证头 `executeGet`/`executePost` / `streamHeaders` | `Cookie: music-token=$token` | `Authorization: <token>`（无 Bearer） | **所有已认证请求 401** | 🔴 阻断 |
| D2 | `login()` 取令牌 | `json["token"]` 或 `data.token` | `data.userToken` | 登录永远取不到令牌 | 🔴 阻断 |
| D3 | `getSongs` 等分页 | `?page=1&limit=500` | `?page=1&size=500` | 服务端忽略 `limit`，返回默认 50 条 | 🔴 严重 |
| D4 | `getAlbums()` | `track/album-detail/list` | `album/list` | 专辑列表拿不到/拿错 | 🔴 严重 |
| D5 | `getArtists()` | `track/artist-detail/list` + 从曲目去重 | `artist/list` | 歌手列表靠曲目反推，分页 500 条后严重不全 | 🔴 严重 |
| D6 | `getPlaylistSongs()` | `playlist/{id}/songs` | `track/playlist-detail/list?playlistGUID=` | 404 | 🔴 严重 |
| D7 | `toggleFavorite()` / `getFavorites()` | `favorite/add`、`favorite/remove`、`favorite/list`，且"先 add 失败再 remove" | `favorite-track/create`、`favorite-track/delete`、`favorite-track/list` | 404；且切换语义错误（无法真正取消收藏） | 🔴 严重 |
| D8 | `getLyrics()` | `track/{id}/lyrics` 取 `lyrics` 字段 | `lyric/list?trackGUID=` → `list[].content` | 404，永远无歌词 | 🔴 严重 |
| D9 | `getStreamUrl()` | `{base}/track/{id}/stream` | `{base}/track/stream?guid={id}` | 404，无法播放 | 🔴 阻断 |
| D10 | `getCoverUrl()` | `{base}/track/{id}/cover` | `{base}/static/cover?coverId={coverId}&size=` | 404，全库无封面 | 🔴 严重 |
| D11 | `getAlbumSongs` / `getArtistSongs` 参数名 | `albumId` / `artistId`（+ `name`） | `albumGUID` / `artistGUID` | 参数被忽略，返回错误集合 | 🔴 严重 |
| D12 | ID 处理 | 假设数字 ID，`stripPrefix("feiniu_")` | GUID 字符串 | 前缀逻辑本身可用，但注释与假设误导 | 🟡 中 |
| D13 | `parseSong` 时长 | `if (durationSec > 100000) ms else sec*1000` | 已是毫秒 | 时长全部 ×1000 放大 | 🟡 中 |
| D14 | 响应解析 `extractDataArray` | 假设 `data.list` 或 `data` 数组 | `data` 为对象 `{list,total}`；`data` 可为 null | 收藏/登出等空 data 场景崩溃或误判 | 🟡 中 |
| D15 | `testConnection()` | 请求 `track/list?limit=1` | 应先 `sys/config`（免认证）或 `user/me` | 未登录时必然失败，误报"连接失败" | 🟡 中 |
| D16 | `fetchApiVersion()` | 硬编码 `"飞牛音乐 API"` / `"v1"` | `sys/config` → `serverVersion` / `mediasrvVersion` | 关于页无真实版本 | 🟢 低 |
| D17 | `getSongTechnicalInfo()` | 返回 null | `track/metadata` → `audioSpec` | 无码率/格式信息 | 🟢 低 |
| D18 | `searchSongs()` | `track/search?query=` | **服务端无搜索端点** | 404 | 🟡 中（改本地过滤） |
| D19 | `getSongsByIds()` | `track/list?ids=` 逐个回退 | `track/metadata?guid=` | 404 | 🟡 中 |
| D20 | UI 默认端口 `ServerConnectScreen.defaultPort` | `TYPE_FEINIU -> "80"` | **5666** | 用户照默认填必然连不上 | 🔴 严重 |
| D21 | 播放链路（架构缺口） | `BackendAdapter.streamHeaders` 已定义，但 `PlaybackService` / `BaiduHttpDataSourceFactory` **从未消费** | 播放流需带 `Authorization` | **即使 D1 修好，播放仍 401** | 🔴 阻断 |
| D22 | 封面链路（架构缺口） | Coil 共享 OkHttp 无后端认证头 | `static/cover` 需 `Authorization` | **封面全部加载失败** | 🔴 阻断 |

> D21 / D22 是**跨后端的架构缺口**：目前 Jellyfin 用 `api_key` query 参数、Navidrome 用 Subsonic 参数，都把凭据拼在 URL 里，所以 historically 没暴露。飞牛是第一个必须走请求头的后端。

---

## 4. 改造方案

### 4.1 总体结构

```
com.nasmusic.tv.backend
├── BackendAuthHeaders.kt          【新增】跨进程单例：当前后端的认证头 + 生效 host
├── BackendRegistry.kt             【改】连接成功/断开时同步 BackendAuthHeaders
├── impl/FeiniuAdapter.kt          【重写】真实协议实现
└── impl/FeiniuUrl.kt              【新增】URL 归一化 + 端点拼装 + 默认端口常量

com.nasmusic.tv.backend.network.baidu
└── BaiduHttpDataSourceFactory.kt  【改】拦截器合并 BackendAuthHeaders（host 匹配才注入）

com.nasmusic.tv.ui.screens
└── ServerConnectScreen.kt         【改】飞牛默认端口 80 → 5666
```

### 4.2 T1 — 新增 `FeiniuUrl`（URL 归一化与端点拼装）

文件：`backend/impl/FeiniuUrl.kt`

```kotlin
object FeiniuUrl {
    const val DEFAULT_HTTP_PORT = 5666     // fnOS 音乐服务默认 HTTP 端口
    const val DEFAULT_HTTPS_PORT = 5667    // fnOS 音乐服务默认 HTTPS 端口
    const val API_PATH = "/music/api/v1"
    fun normalize(input: String): String                       // → ".../music/api/v1/"
    fun endpoint(baseUrl: String, path: String, vararg query: Pair<String, Any?>): String
    fun coverUrl(baseUrl: String, coverId: String?, size: Int = 512): String?
}
```

归一化规则：
1. 去空白；**无 scheme 时默认补 `http://`**（`ServerConfig` 无 `useHttps` 字段，签名不接受该参数）。
2. 有显式端口 → 保留；无端口 → http 用 **5666**、https 用 **5667**。
3. 去掉 query / fragment / 用户名密码。
4. 按 §2.1 表格推导 path，保证结尾是 `/music/api/v1/`。

> **与参考项目的一处有意偏离**：`ServerUrlNormalizer` 对「显式 `http://` 但无端口」补 80、「显式 https 无端口」补 443。本方案统一补 5666/5667。
> 理由：本适配器是**飞牛音乐专用**，用户填 `http://192.168.1.100` 几乎必然指音乐服务（80 端口不会有音乐 API）；参考项目的规则服务于通用 URL 编辑器，照搬会让"填了 http 前缀"的用户反而连不上。显式端口永远优先，故不影响高级用法。

> 注意：`FeiniuAdapter.initialize()` 收到的 `baseUrl` 来自 `ServerConfig`，可能是用户手填的任意形态，必须在适配器内部做一次归一化，不能假设上游已规范。

### 4.3 T2 — 重写传输层（信封解析 / 错误码 / 认证头）

在 `FeiniuAdapter` 内实现：

```kotlin
private fun authHeaders(): Map<String, String>          // Authorization: <token>
private suspend fun <T> get(path, vararg query, parse: (JsonObject) -> T): T?
private suspend fun postUnit(path, bodyJson: String): Boolean
```

- 统一注入 `Authorization`（D1）；**不再**发送 `music-token` Cookie（非 relay 模式）。
- 统一信封解析（D14）：`code != 0` → 按 §2.3 映射错误码并记日志；`data` 为 null 视为成功空响应（收藏增删场景）。
- 401 / code `99999`/`120001` → 抛出可识别的"令牌失效"，供 T3 的自动重登使用。
- 所有 URL 走 `UrlSanitizer.sanitize()` 再落日志（沿用现有安全约定）。
- 保留 `BackendRegistry.sharedDispatcher` / `sharedConnectionPool` 注入（R-6 约定），`close()` 仍**禁止** shutdown/evictAll。

响应形态分两类，分别处理：
- **分页型**：`data = {list: [...], total: n, sort: "..."}` → 解析为 `Page<T>(list, total)`。
- **对象型**：`data = {...}` → 直接解析。
- **单元型**：`data` 可 null → 只看 `code`。

### 4.4 T3 — 修正认证流程

- `initialize()` 顺序：
  1. 归一化 `baseUrl`（T1）；
  2. 若传入 `apiToken` 非空 → `user/me` 校验，失败则用用户名密码重登；
  3. 否则 `user/password-login` → 取 **`data.userToken`**（D2）；
  4. 成功后 `sys/config` 填充版本信息（T8）。
- `deviceId`：**持久化**到 `SharedPreferences`（当前每次连接 `UUID.randomUUID()`，会随重连变化，触发服务端设备列表膨胀）。新增 `feiniu_device_id` 键，首次生成后复用。
- `logout()`：调用 `POST user/logout` 再清空内存态（当前只清 Cookie）。
- **自动重登**：`BackendAdapter` 层记录已登录的密码 SHA-256，401 时静默重登一次再重试；仍失败才上报未认证。

### 4.5 T4 — 修正曲目 / 专辑 / 歌手

| 方法 | 改造 |
| --- | --- |
| `getSongs(limit, offset)` | `track/list?page=<offset/size+1>&size=<limit>&sort=createdAt,desc`；`limit<=0` 回退默认 50（保留现有 B14 防除零修复） |
| `getSongsTotalCount()` | `track/list?page=1&size=1` → 读 `data.total` |
| `getAlbums()` | `album/list?page&size&sort=newTrackAddedAt,desc`，**分页翻完全量**（不再只取 500） |
| `getAlbumSongs(id)` | `track/album-detail/list?albumGUID=&page&size&sort=trackNo,asc`（D11），并按 `trackNo` 排序 |
| `getArtists()` | `artist/list?page&size&sort=trackCount,desc`，翻完全量（D5） |
| `getArtistSongs(id)` | `track/artist-detail/list?artistGUID=&page&size&sort=createdAt,desc`（D11） |
| `getRecentSongs()` | `track/list?page=1&size=100&sort=createdAt,desc`（现有 `sort=createdAt` 猜测恰好正确） |
| `getSongsByIds(ids)` | 逐个 `track/metadata?guid=`（D19），并发上限 4，失败项跳过 |
| `getRandomSongs(limit)` | 先取 `total`，随机 page 偏移取一页后打乱（服务端无随机端点；不引入 roam 会话态） |

**分页翻页约定**：统一提供 `fetchAllPages(pageSize = 200)` 帮助函数，循环至 `list.size < pageSize` 或 `累计 >= total` 或达到上限页数（防御 `total` 异常，上限 200 页）。

**Song 字段映射**（D13 修正）：

```
id        = "feiniu_" + guid
title     = title
artist    = artists.joinToString(" / ")（空则 ""）
artistId  = artists.firstOrNull()?.guid?.let { "feiniu_" + it }
album     = album?.name ?: ""
albumId   = album?.guid?.let { "feiniu_" + it }
coverUrl  = coverId → FeiniuUrl.coverUrl(coverId, 512)
durationMs= duration（原样，已是毫秒）
bitrate   = 0（⚠️ 单位未确认，见 §12）——**不要**直接透传
trackNumber = 由 sort=trackNo,asc 顺序保证，服务端不返回则填序号
```

### 4.6 T5 — 修正歌单

- `getPlaylists()`：`playlist/list` → `Playlist(id="feiniu_"+guid, name, coverUrls=listOf(封面URL))`。
  - ⚠️ **字段名勘误**：`data.model.Playlist` 的字段是 **`coverUrls: List<String>`**，不是 `coverUrl`（见 `app/src/main/java/com/nasmusic/tv/data/model/Playlist.kt`）。无封面时传 `emptyList()`。
  - **曲目数**需逐个 `playlist/detail?guid=` 拿 `trackCount`（列表接口不返回）。为控制请求数采用并发上限 4 的批量拉取，单歌单失败不影响整体。
- `getPlaylistSongs(id)`：`track/playlist-detail/list?playlistGUID=&page&size&sort=trackAddedAt,desc`（D6），翻完全量。

### 4.7 T6 — 修正收藏

- 语义修正（D7）：`toggleFavorite(songId, isCurrentlyFavorite)` 按**入参决定**调用 `favorite-track/create` 还是 `favorite-track/delete`，不再"先 add 失败再 remove"。
- `getFavorites()`：`favorite-track/list?page&size&sort=favoriteAt,desc`，翻完全量。
- 请求体固定 `{"trackGUID": "<guid>"}`；成功响应 `data` 可能为 null，按单元型解析（D14）。

### 4.8 T7 — 修正歌词

- `getLyrics(songId)`：`lyric/list?trackGUID=`
- 选取策略：
  1. 若 `preferred` 非空且在 `list` 中存在 → 取该项；
  2. 否则取第一条 `isLRC == true` 的；
  3. 否则取第一条。
- `offset` 非零时，在返回文本前追加一行 `[offset:<ms>]`。
  - ✅ **已验证可行**：`lyrics/LrcParser.kt` 的 `OFFSET_REGEX = Regex("\\[offset:(-?\\d+)\\]")` 会解析该头部（`LrcParser.kt:150`），且序列化时也按此格式回写（`LrcParser.kt:96-97`）。这是本仓库既有约定，不是新发明。
- `isLRC == false` 的纯文本歌词：原样返回，由 `LyricsManager` 判定。

### 4.9 T8 — 修正流与封面、补齐技术信息与版本

- `getStreamUrl(songId)`：`{apiBase}track/stream?guid=<guid>`（D9）。

- **封面链路（重要，决定实现优先级）**：
  - 经核查全仓库调用点，`BackendAdapter.getCoverUrl(songId)` **在 UI 层没有任何调用者**（仅各适配器内部实现），UI 唯一的封面入口是 `MainViewModel` 的 `adapter.getCoverUrlCandidates(song)`（`MainViewModel.kt:2586`），以及 `Song.coverUrl` 字段。
  - 因此**主路径是两条**：① `parseSong()` 时把 `coverId` 直接解析成完整 URL 写入 `Song.coverUrl`；② `getCoverUrlCandidates(song)` 返回候选列表。
  - `getCoverUrlCandidates(song)` 必须**以 `song.coverUrl` 开头**（对齐 `JellyfinAdapter.getCoverUrlCandidates` 的写法），否则主路径封面全丢。顺序：`song.coverUrl → 专辑封面 → 歌手封面`，去重、去空。
  - `getCoverUrl(songId)` 仍实现（保持接口契约），走 `songId → coverId` 的 LRU 缓存；缓存未命中时回退 `track/metadata?guid=` 取 `coverId`；仍无则返回空串（UI 降级占位图）。它不在热路径上，实现可从简。
- `getSongTechnicalInfo(songId)`：`track/metadata?guid=` → `audioSpec` 映射为 `SongTechnicalInfo(codec, durationMs, format=container, bitrate=0, channels=0, sampleRate=0)`（D17）。注意：
  - `audioSpec` **不含采样率与声道数**，填 0，不要臆造；
  - **`bitrate` 也填 0**：其单位（bps / kbps）无证据支持，而 `SongInfoPanel` 会直接渲染成 "N kbps"，猜错会显示 "320000 kbps"。详见 §12。
- `getApiVersion()`：`sys/config` → `VersionInfo.Runtime("飞牛音乐", serverVersion, "sys/config", now)`；`mediasrvVersion` 记入日志/描述（D16）。
- `serverName`：`sys/config.serverName`。

### 4.10 T9 — 搜索（服务端无端点，改本地过滤）

参考项目无搜索端点（D18）。方案：

```
searchSongs(query):
  1. 取全量曲目缓存（TTL 5 分钟，容量 1 条，key=apiBase）
  2. 本地按 title / artist / album 做大小写不敏感包含匹配
  3. 排序：前缀匹配 > 包含匹配；同分时按 title 长度升序
```

- 缓存失效时机：切换服务器、登出。
- 库很大时首搜较慢：先返回已缓存部分并异步刷新（若 UI 层支持增量更新则实现，否则仅首屏 loading）。
- 复用仓库已有的拼音搜索能力（TinyPinyin）以支持拼音搜索，与其他后端体验一致。

### 4.11 T10 — 认证头接线（修复 D21 / D22 架构缺口）

新增 `backend/BackendAuthHeaders.kt`：

```kotlin
object BackendAuthHeaders {
    fun update(headers: Map<String, String>, host: String?)
    fun clear()
    fun forHost(host: String): Map<String, String>   // 仅 host 精确匹配时返回
}
```

- `@Volatile` + `synchronized` 保证线程安全（播放线程与 UI 线程并发访问）。
- **只在 host 精确匹配时注入**，避免令牌随 302 泄漏到 CDN/第三方域名（安全底线）。

改造 `BaiduHttpDataSourceFactory`：
- 在 `buildOkClientWithBaiduInterceptor()` 的拦截器链中，于百度/B站分支**之后**追加后端认证分支：若 `BackendAuthHeaders.forHost(url.host)` 非空则合并进请求头。
- 该方法同时服务于 ExoPlayer（`create()`）与 Coil（`createOkHttpClientForCoil()`），一次改动覆盖播放与封面两条链路。

改造 `BackendRegistry`：
- `initialize()` 成功后：`BackendAuthHeaders.update(adapter.streamHeaders, host)`。
- `disconnect()` / `releaseAdapter()`：`BackendAuthHeaders.clear()`。
- `testConnection()` 的临时适配器**不更新**该单例（避免污染当前连接的认证态）。

> 说明：`streamHeaders` 目前**没有任何适配器以外的问题**——Jellyfin/Navidrome 返回空 Map，注入后行为不变，无回归风险。

### 4.12 T11 — UI 默认端口修正（D20）

1. `ServerConnectScreen.defaultPort()`（`ServerConnectScreen.kt:86`）：`TYPE_FEINIU -> "80"` 改为 `"5666"`。
2. `app/src/main/res/values/strings.xml:630` 的 `server_connect_hint_feiniu` 当前为 `http://192.168.1.100`（未体现端口），改为 `http://192.168.1.100:5666`（或 `http://192.168.1.100`，不填端口默认 5666）。当前文案已确认需要更新。

### 4.13 任务汇总

| ID | 任务 | 涉及文件 | 依赖 |
| --- | --- | --- | --- |
| T1 | `FeiniuUrl` 归一化 | 新增 `impl/FeiniuUrl.kt` | — |
| T2 | 传输层重写（信封/错误码/认证头） | `impl/FeiniuAdapter.kt` | T1 |
| T3 | 认证流程修正 + 自动重登 | `impl/FeiniuAdapter.kt` | T2 |
| T4 | 曲目/专辑/歌手端点修正 | `impl/FeiniuAdapter.kt` | T2 |
| T5 | 歌单修正 | `impl/FeiniuAdapter.kt` | T2 |
| T6 | 收藏修正 | `impl/FeiniuAdapter.kt` | T2 |
| T7 | 歌词修正 | `impl/FeiniuAdapter.kt` | T2 |
| T8 | 流/封面/技术信息/版本 | `impl/FeiniuAdapter.kt` | T2 |
| T9 | 搜索改本地过滤 | `impl/FeiniuAdapter.kt` | T4 |
| T10 | 认证头接线（播放+封面） | 新增 `BackendAuthHeaders.kt`、改 `BaiduHttpDataSourceFactory.kt`、`BackendRegistry.kt` | T2 |
| T11 | UI 默认端口 80→5666 | `ServerConnectScreen.kt`、可选 `strings.xml` | — |
| T12 | 编译 + lint 验证 | — | 全部 |
| T13 | 版本号 v2.32.4 + CHANGELOG + 提交 | `build.gradle.kts`、`CHANGELOG.md` | T12 |

---

## 5. 验收标准

### 5.1 静态验收（本机可完成）

| 项 | 标准 | 命令 |
| --- | --- | --- |
| 编译 | `assembleDebug` 成功、零错误 | 见 §7 |
| Lint | `lintDebug` 无 **Error**（256 条既有 warning 不增加新的 Error） | 见 §7 |
| 代码自查 | 无残留 `[UNCONFIRMED]` 标注的猜测端点；所有端点可对应 §2.4 表格 | 人工 |
| 日志脱敏 | 所有 URL 日志经 `UrlSanitizer.sanitize()`；令牌只打前 8 位 | 人工 |

### 5.2 动态验收（需飞牛真机，本机无法执行，交用户验收）

| # | 场景 | 期望 |
| --- | --- | --- |
| A1 | 填 `http://<飞牛IP>`（不带端口）连接 | 自动走 5666，登录成功 |
| A2 | 填 `http://<飞牛IP>:5666` 连接 | 成功 |
| A3 | 填 `http://<飞牛IP>:5666/music/api/v1` 连接 | 归一化后成功，不出现 `.../v1/v1/` |
| A4 | 首页曲目列表 | 显示全部曲目，数量与飞牛音乐 App 一致 |
| A5 | 播放任意曲目 | 出声（验证 `Authorization` 注入生效） |
| A6 | 曲目/专辑/歌单封面 | 正常显示（验证 Coil 链路注入生效） |
| A7 | 歌词页 | 显示服务端歌词；无歌词时走现有在线匹配兜底 |
| A8 | 专辑页 / 歌手页 | 列表与曲目正确 |
| A9 | 收藏 | 可加可取消，重启后状态一致 |
| A10 | 歌单 | 列表与曲目正确 |
| A11 | 搜索 | 能搜到歌（本地过滤生效） |
| A12 | 设置→关于 | 显示飞牛真实版本号（`sys/config.serverVersion`） |
| A13 | 错误密码 | 提示连接失败，不崩溃 |
| A14 | 令牌失效（服务端登出） | 自动重登一次后恢复；仍失败则回到登录页 |

> ⚠️ 本机无法运行单元测试（AGENTS.md 已记录：`testDebugUnitTest` 的 worker JVM 启动即死），因此**不能声称"测试通过"**，动态项必须由用户在真机验证。

---

## 6. 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| R1 飞牛固件版本差异导致端点微调 | 部分功能 404 | 所有请求失败统一记 `url + code + msg`，便于一次抓包定位；信封 `code` 已能区分 404 与 401 |
| R2 5666 端口在新版 fnOS 上变更 | 连不上 | T1 归一化保留用户显式端口；UI 提示"若 5666 不通请确认飞牛音乐端口" |
| R3 认证头注入到错误域名 | 令牌泄漏 | `BackendAuthHeaders.forHost()` **精确 host 匹配**，且只在已连接时非空 |
| R4 全量翻页在大曲库上慢/超时 | 首屏卡顿 | `fetchAllPages` 设 200 页上限；`getSongs` 仍支持分页入参；必要时改懒加载 |
| R5 本地搜索在超大曲库内存压力 | OOM | 搜索缓存只存 `id/title/artist/album` 四个字段，不存完整 Song；TTL 5 分钟 |
| R6 改造影响其他后端 | 回归 | T10 只对空 Map 无操作；Jellyfin/Navidrome/Subsonic/道理鱼 `streamHeaders` 均为空，行为不变 |
| R7 R8 混淆后 Gson 解析失败 | 发布版崩溃 | 新增的 DTO 若走 Gson 反序列化，须在 `proguard-rules.pro` 加 `-keep`；**本次解析全部使用手写 `JsonObject` 取值（与现有实现一致），不引入新的 Gson 模型类**，规避该风险 |

---

## 7. 构建与验证命令

按 AGENTS.md，本机必须 `--no-daemon`：

```bash
cd D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat assembleDebug lintDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

---

## 8. 回滚方案

改造集中在 `FeiniuAdapter.kt`（单文件重写）+ 3 个文件的小改动（`BackendRegistry`、`BaiduHttpDataSourceFactory`、`ServerConnectScreen`）+ 2 个新文件。

- 回滚方式：`git revert` 对应提交即可，无数据库/DataStore 结构变更（`FILE_FORMAT_VERSION` 不变）。
- 新增的 `feiniu_device_id`（SharedPreferences）为纯新增键，回滚后残留无害。

---

## 10. 开发前评审记录（2026-09-14）

评审方式：把文档中的每条论断拿源码 `file:line` 证据比对，修正无依据或不准确之处。

| # | 评审发现 | 判定 | 处置 |
| --- | --- | --- | --- |
| V1 | 原稿 T5 写 `Playlist(..., coverUrl=...)` | ❌ **错误** | `Playlist` 字段实为 `coverUrls: List<String>`（`data/model/Playlist.kt`）。已修正为 `coverUrls=listOf(...)`。若按原稿写会**编译不过** |
| V2 | 原稿 T8 把 `getCoverUrl(songId)` 当主路径设计 | ❌ **设计偏差** | 全仓库检索：`getCoverUrl(songId)` 在 UI 层**零调用**；唯一入口是 `MainViewModel.kt:2586` 的 `getCoverUrlCandidates(song)`。已改为「`Song.coverUrl` + `getCoverUrlCandidates` 双主路径」，`getCoverUrl` 降级为从简实现 |
| V3 | 原稿 T1 的 `normalize(input, useHttps)` | ❌ **参数不存在** | `ServerConfig` 无 `useHttps` 字段（`data/model/ServerConfig.kt`）。已去掉该参数；并**有意偏离**参考项目的 80/443 规则，统一用 5666/5667，理由写入 §4.2 |
| V4 | 原稿 T7 对 `[offset:N]` 写"若不支持则忽略" | ⚠️ **不确定表述** | 已核实 `LrcParser.kt:150` 的 `OFFSET_REGEX` 确实解析该头部，改为**已验证可行** |
| V5 | 原稿 T11 只提 `ServerConnectScreen` | ⚠️ **遗漏** | `strings.xml:630` 的 `server_connect_hint_feiniu` 确为无端口旧文案，已纳入 T11 |
| V6 | D21「`streamHeaders` 无人消费」 | ✅ **确认** | 全仓库仅 `BackendAdapter.kt:54` 定义 + `FeiniuAdapter.kt:91` 覆盖，无消费点；`BaiduHttpDataSourceFactory` 只注入百度/B站头。该架构缺口成立 |
| V7 | D22「Coil 链路无后端认证头」 | ✅ **确认** | `NasMusicApp.kt:474` 的 ImageLoader 用同一个 `BaiduHttpDataSourceFactory.createOkHttpClientForCoil()`，无后端头注入。成立 |
| V8 | D20「飞牛默认端口 80」 | ✅ **确认** | `ServerConnectScreen.kt:86` 确为 `"80"` |
| V9 | D2「登录取 `token` 而非 `userToken`」 | ✅ **确认** | 参考项目 `Dto.kt:31` → `LoginResultDto(userToken, user)`；我方 `FeiniuAdapter.kt:161-162` 读 `token`。成立 |
| V10 | D13「duration 单位」 | ✅ **确认** | 参考项目 `Dto.kt:112` → `durationMs = duration.takeIf { it > 0 }`，已是毫秒；我方 `FeiniuAdapter.kt:664` 做了 ×1000 放大。成立 |
| V11 | 参考项目无搜索端点 | ✅ **确认** | `TrimMusicApi.kt` 全文无 `search` 端点；契约文档亦未提。本地过滤方案成立 |
| V12 | R7「避免引入 Gson 模型类」 | ✅ **策略确认** | AGENTS.md 记载 v2.5.1 曾因 R8 + Gson 类型擦除崩溃。本次沿用现有手写 `JsonObject` 取值风格，不新增 Gson 模型，规避该风险 |

**评审结论**：文档经修正后**可进入开发**。共修正 3 处错误（V1/V2/V3）、消除 2 处不确定表述与遗漏（V4/V5），其余 7 项论断均有源码证据支撑。

---

## 11. 实施结果（2026-09-14，v2.32.4）

| ID | 任务 | 状态 | 落地位置 |
| --- | --- | --- | --- |
| T1 | `FeiniuUrl` 归一化 | ✅ 完成 | 新增 `backend/impl/FeiniuUrl.kt` |
| T2 | 传输层重写（信封 / 错误码 / 认证头） | ✅ 完成 | `FeiniuAdapter.kt`：`execute` / `dataOf` / `AuthExpiredException` |
| T3 | 认证流程 + 自动重登 + deviceId 持久化 | ✅ 完成 | `FeiniuAdapter.kt`：`login` / `verifyToken` / `withAuthRetry` / `deviceId()` |
| T4 | 曲目 / 专辑 / 歌手端点修正 | ✅ 完成 | `FeiniuAdapter.kt`：`getSongs` / `getAlbums` / `getArtists` / `fetchAllPages` |
| T5 | 歌单修正 | ✅ 完成 | `getPlaylists` / `getPlaylistSongs`（`Playlist.coverUrls`） |
| T6 | 收藏修正 | ✅ 完成 | `toggleFavorite` / `getFavorites` |
| T7 | 歌词修正 | ✅ 完成 | `getLyrics`（preferred / isLRC / offset） |
| T8 | 流 / 封面 / 技术信息 / 版本 | ✅ 完成 | `getStreamUrl` / `getCoverUrlCandidates` / `getSongTechnicalInfo` / `getApiVersion` |
| T9 | 搜索改本地过滤 | ✅ 完成 | `searchSongs` + `loadAllTracks`（5 分钟缓存） |
| T10 | 认证头接线（播放 + 封面） | ✅ 完成 | 新增 `backend/BackendAuthHeaders.kt`；改 `BaiduHttpDataSourceFactory.kt`、`BackendRegistry.kt`、`NasMusicApp.kt` |
| T11 | UI 默认端口 80→5666 | ✅ 完成 | `ServerConnectScreen.kt`、`strings.xml` |
| T12 | 编译 + lint 验证 | ✅ 完成 | `assembleDebug` / `assembleRelease`（含 R8）BUILD SUCCESSFUL；`lintDebug` **0 Error**（257 Warning，改动文件自身 0 警告） |
| T15 | R8 收缩冒烟检查 | ✅ 完成 | release APK 的 `classes.dex` 中三个类与全部协议常量均存在（见 §11） |
| T14 | `FeiniuUrl` 回归测试 | ✅ 完成 | 新增 `app/src/test/.../FeiniuUrlTest.kt`（纯 JVM，25 用例） |
| T13 | 版本号 v2.32.4 + CHANGELOG + 提交 | ✅ 完成 | `build.gradle.kts`（143 / 2.32.4）、`CHANGELOG.md` |

### 实施过程中的修正（相对原计划）

| # | 偏差 | 原因 |
| --- | --- | --- |
| E1 | `getCoverUrl(songId)` 改为**只查内存缓存，不发网络请求** | 它是非 suspend 方法，调用方线程不可控（可能主线程）；且已核查 UI 层零调用，无需为此承担 IO 风险 |
| E2 | `BackendRegistry` 新增可选 `appContext` 构造参数 | 适配器需要 Context 持久化 deviceId；项目无静态 Context 入口，按现有「NasMusicApp 手工 DI」风格显式传入，优于新增全局静态引用 |
| E3 | 标准库 `runCatching` 无法包裹 suspend 调用 | 其 block 非 suspend；自定义 `runCatchingSuspend`（suspend block）替代 |
| E4 | `getRandomSongs` 用「随机取页」而非 roam 端点 | roam 需维护漫游会话状态，收益不抵复杂度（已在 §1.3 列为非目标） |

### T14 补充说明：测试是怎么验证的

本机 `testDebugUnitTest` 无法运行（AGENTS.md 已记录：测试 worker 启动即死，exit 268435466），
因此沿用本仓库 Demucs 那次的既有做法——**绕过 Gradle，用独立 JVM 编译真实源码并跑真实测试类**：

```bash
cd docs/archive/verification/verify_feiniu_url && python run.py        # --clean 可强制重编
```

> ⚠️ 该 harness 原先放在 gitignored 的 `logs_temp/`（不入库）；2026-09-20 已随其他验证证据迁入
> `docs/archive/verification/`，**现已入库**，路径即上面的 `cd` 目标。
> 机器清理后需重建；脚本只依赖 Gradle 缓存里的 `kotlin-compiler-embeddable` / okhttp / junit，
> 路径写在 `run.py` 顶部的常量里，版本升级时改常量即可。

- `FeiniuUrl.kt` **零 Android 依赖**（只用 okhttp3 的 `HttpUrl` + JDK），无需抽取、无需打桩，
  直接编源文件 + 测试文件。`run.py` 里的 `SOURCES` 指向 `app/src/...` 的**真实路径**，不做副本
  （副本会与源码漂移）。
- 结果：**`OK (25 tests)`**。
- **反向对照**：把「旧实现的错误认知」（默认端口 80）写进断言重跑，得到 **9/25 失败、exit 1** —
  证明 harness 确实能检出错误，25 条全绿不是空跑。
- 另跑 `./gradlew compileDebugUnitTestKotlin` **BUILD SUCCESSFUL**，确认该测试在项目配置下能编译
  （CI 的 `testDebugUnitTest` 会真正执行它）。

### T15 补充说明：R8 收缩冒烟检查

AGENTS.md 记载 v2.5.1 曾因「Gson 类型擦除 + R8」崩溃，本次改动又集中在 Gson 解析密集的
适配器上，因此额外做一次 release 侧验证：

- **策略上先规避**：本次**未新增任何 Gson 模型类**，解析一律用手写 `JsonObject` 取值，
  不引入新的反射反序列化面（详见 §6 的 R7）。两个新增类都在
  `-keep class com.nasmusic.tv.backend.** { *; }` 覆盖范围内。
- **再实证**：`assembleRelease` BUILD SUCCESSFUL 后，解压
  `NASMusicTV-release-v2-32-4.apk` 的 `classes.dex`（9.35 MB，单 dex）做字符串检查，
  确认以下**均未被 R8 收缩**：
  - 类：`BackendAuthHeaders` / `FeiniuUrl` / `FeiniuAdapter`
  - 协议常量：`music/api/v1`、`Authorization`、`track/stream`、`static/cover`、
    `lyric/list`、`favorite-track/create`、`user/password-login`

> 该检查只能证明「类和常量还在」，**不能**证明混淆后行为正确（例如 Kotlin `object` 的
> 单例语义、反射调用点）。真正的 R8 行为验证仍靠真机。

### 未验证项

- **Gradle 侧未跑过测试**（本机跑不了），25 用例是独立 JVM 跑出来的；CI 是最终裁判。
- 适配器主体（`FeiniuAdapter`）依赖 Android `Context` 与网络，**未纳入单测**。
- 动态行为需按 §5.2 的 A1–A14 在飞牛真机验收，其中最关键的是 **A5（播放出声）与 A6（封面显示）** ——
  这两项直接验证 T10 的认证头注入是否真正打通。

---

## 12. 待确认项（需真机确认，勿猜）

这几项在参考项目与契约文档中**都找不到依据**，实现一律采取「宁缺勿错」策略（不展示），
而不是猜测后展示一个可能错误的值。真机验收时顺带确认即可一次性修好。

| # | 项 | 现状 | 为什么不能猜 | 如何确认 |
| --- | --- | --- | --- | --- |
| Q1 | `audioSpec.bitrate` 的单位 | 填 0（`BITRATE_UNVERIFIED`），UI 显示 "—" | 参考项目只在 `AudioSpecDto` 声明该字段、**全项目零使用**；契约文档未提。`SongInfoPanel` 会渲染成 "N kbps"，猜错即 "320000 kbps"；也不能用「>10000 当作 bps」这类启发式——24bit/192kHz 立体声 FLAC 约 9216 kbps，启发式会误判 | 找一首已知码率的曲目（如 320kbps MP3），看服务端返回是 `320` 还是 `320000`，据此改为原值或 `/1000` |
| Q2 | `AudioSpecDto` 是否含采样率 / 声道数 | 填 0，UI 显示 "—" | DTO 只有 `codec / container / duration / bitrate` 四个字段，确实没有 | 无需确认，除非 fnOS 后续版本扩充了字段 |
| Q3 | `sort` 参数各字段名的可用集合 | 沿用参考项目实际用到的 4 组 | 超出参考项目使用范围的排序字段（如按标题排序）未验证 | 需要时抓包确认 |
| Q4 | ~~中文元数据是否需要 `EncodingUtils.fixEncoding`~~ | **已决定：不应用**（2026-09-15 移除了原先的 7 处调用） | 该函数是为 **Jellyfin 特有**的「GBK 字节被当 UTF-8 存」问题设计的（AGENTS.md 明确这么写），而 fnOS 返回正常 UTF-8、参考项目完全不做这层处理。更关键的是它会**无条件剥掉结尾的 `?`**（`EncodingUtils.kt:44-50`，不看有没有乱码都执行）——用在飞牛上收益为零、损失确定（"Why?" → "Why"） | 无需确认。若将来发现飞牛确实有 GBK 脏数据，应另写一个**只修复不删字符**的分支，而不是复用本函数 |

## 13. 附：参考项目关键文件索引

| 文件 | 作用 |
| --- | --- |
| `core/data/src/main/kotlin/com/fnmusic/tv/core/data/api/TrimMusicApi.kt` | **端点总表 + 请求封装 + 密码 SHA256 + 认证头** |
| `core/data/src/main/kotlin/com/fnmusic/tv/core/data/api/Dto.kt` | 全部 DTO 字段定义与领域映射 |
| `core/data/src/main/kotlin/com/fnmusic/tv/core/data/api/ApiContract.kt` | 信封 `{code,msg,data}` + 错误码映射 |
| `core/data/src/main/kotlin/com/fnmusic/tv/core/data/server/ServerUrlNormalizer.kt` | URL 归一化 + 默认端口 5666 |
| `core/data/src/main/kotlin/com/fnmusic/tv/core/data/server/ConnectionResolver.kt` | 认证头构造（`ConnectionAccess.headers`）、FNID/中继 |
| `core/data/src/main/kotlin/com/fnmusic/tv/core/data/repository/SessionRepository.kt` | 登录/恢复/重登/登出流程 |
| `core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackService.kt` | 播放请求头构造（`playbackRequestHeaders`） |
| `.trellis/spec/backend/android-client-contracts.md` | **权威契约文档**（L145-L215 为认证与服务端收藏章节） |

## 14. 审查后修复（2026-09-15，v2.32.5）

对 v2.32.4 批次做了完整代码审查（全量 diff 精读 + 播放 / 认证链路逐层追踪 + 独立 JVM 复核），修复以下发现：

| # | 级别 | 问题 | 修复 |
| --- | --- | --- | --- |
| F-1 | **P0 阻断** | `parseTrack` 未填充 `Song.streamUrl`；全 app 的 NAS 播放解析唯一出口是 `PlayerViewModel.resolveStreamUrl` → `getSongsByIds(...).streamUrl`，导致点播 / 切歌 / 恢复队列全部「解析失败」（与认证头无关） | `parseTrack` 填 `FeiniuUrl.streamUrl(apiBase, guid)` |
| F-2 | P1 | 静默重登换新令牌后 `BackendAuthHeaders` 快照不刷新 → 播放 / 封面持续 401（A14 场景不完整） | 单例改 **provider**（每请求实时读取）+ `userToken` `@Volatile` |
| F-3 | P2 | IPv6 字面量地址 host 匹配错位（`URI.getHost()` 带方括号、OkHttp 不带）→ 认证头永不注入 | 提取 `hostOfUrl` 并剥离方括号 + 单测 |
| F-4 | P2 | `withAuthRetry` 覆盖不对称（歌词 / 收藏 / 元数据路径过期时静默失败） | 5 处补上 + 互斥 & 令牌代数去重 |
| F-6 | P3 | `normalize` 对 `http://host//music` 保留双斜杠 | 折叠重复斜杠 + 用例 |
| CI-1 | **P1** | CI 首跑暴露：`tryEmit` 返回值不可表达「有无订阅者」（有订阅者必 false / 无订阅者必 true），通知栏「切换播放模式」自 v2.32.3 起从未生效 | `extraBufferCapacity = 1`（replay 仍 0）+ `subscriptionCount` 门控；测试改真实调度器重写 |

**测试与验证**：
- `FeiniuUrlTest` 25 → 29 例 + `BackendAuthHeadersTest` 6 例（独立 JVM harness 复跑 **OK**）与 `BackendHostOfUrlTest` 6 例（随 CI `testDebugUnitTest` 执行）
- **全量单测 512 例 0 失败**（本机首次完整跑通 testDebugUnitTest，含 CI 首跑暴露并修复的 PlayModeToggle 3 例）
- `assembleDebug` / `assembleRelease`（含 R8）/ `compileDebugUnitTestKotlin` BUILD SUCCESSFUL；`lintDebug` 0 Error（257 Warning）；release dex 冒烟确认新类 / 方法未被 R8 收缩
- 真机动态项 A1–A14 仍待验收（重点 A5 / A6 / A14）

