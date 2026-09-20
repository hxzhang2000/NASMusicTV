# 网络音乐层升级方案：对接 Go Music API

> 版本：v1 — 2026-08-10
> 状态：方案提案

---

## 1. 背景

NASMusicTV 当前网络音乐层依赖 **Meting-API** 第三方代理端点（Mikus / Redcha / Qijieya），实现网易云/QQ/酷狗/酷我等平台的搜索、播放 URL 解析、歌词获取与封面加载。该方案在项目早期快速上线了网络音乐功能，但在实际使用中暴露出以下问题：

- **端点稳定性依赖第三方**：Meting-API 由社区维护，端点可能随时失效，需要用户手动切换或等待维护者修复
- **平台覆盖有限**：仅支持 6 个平台，且无法独立控制单个平台的接入/修复
- **无无损/FLAC 支持**：即使源平台有 FLAC，Meting 端点也无法保证透传
- **无歌词精细化**：仅返回单语言 LRC，不支持原文/译文/罗马音逐字
- **无歌单/专辑完整链路**：缺少歌单分类浏览、个人歌单、专辑搜索、链接解析、每日推荐等能力
- **无单曲换源**：搜索结果不可跨平台找替代音源

**Go Music DL**（`guohuiyuan/go-music-dl`）及其底层库 `music-lib` 和 HTTP API 层 `go-music-api` 提供了一个成熟的替代方案——12+ 平台独立实现、FLAC 支持、扫码登录、歌词精细化、全链路歌单/专辑/链接解析，且架构上天然适合作为后端服务被消费。

---

## 2. 现状架构

```
┌─────────────────────────────────────────────────────┐
│                  NASMusicTV (Android TV)              │
│                                                       │
│  NetworkMusicManager (搜索编排 + 播放 URL 缓存)        │
│       │                                               │
│       ├── MetingApiService (默认源)                    │
│       │       ├── https://meting.mikus.ink/api         │
│       │       ├── https://meting.api.redcha.cn/api     │
│       │       └── https://api.qijieya.cn/meting        │
│       │                                               │
│       └── (预留接口位，当前无其他实现)                   │
│                                                       │
│  LyricsManager (缓存→后端API→网络匹配 四级回退)         │
│       │                                               │
│       └── NetworkMusicManager.resolveLyrics()          │
│                                                       │
└─────────────────────────────────────────────────────┘
```

**接口定义**（`NetworkMusicService`）：

| 方法 | 说明 |
|------|------|
| `search(keyword, limit)` | 搜索歌曲 |
| `resolvePlayUrl(song)` | 解析播放链接 |
| `resolveLyrics(song)` | 获取歌词 |
| `resolveCoverUrl(song)` | 获取封面 URL |
| `searchCoverUrl(title, artist)` | 按标题+艺术家搜封面 |
| `getPlaylist(playlistId)` | 获取歌单歌曲 |

---

## 3. Go Music API 能力矩阵

`go-music-api` 是基于 `music-lib` 的统一 HTTP API 服务，提供标准 RESTful 接口（`/api/v1/*`）与兼容接口（`/music/*`）。

### 3.1 平台支持

| 平台 | 搜索 | 下载 | 歌词 | 歌单 | 专辑 | 推荐歌单 | 分类歌单 | 个人歌单 | 扫码登录 |
|------|:----:|:----:|:----:|:----:|:----:|:--------:|:--------:|:--------:|:--------:|
| 网易云音乐 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| QQ 音乐 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 酷狗音乐 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 酷我音乐 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| 咪咕音乐 | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| 千千音乐 | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| 汽水音乐 | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ⚠️ |
| Bilibili | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ |
| 5sing | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Jamendo | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| JOOX | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| Apple Music | ✅ | ⚠️ | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |

### 3.2 核心 API 端点

| 端点 | 功能 | 与 NASMusicTV 的对应关系 |
|------|------|--------------------------|
| `GET /api/v1/music/search?q=...&type=song` | 搜索歌曲 | → `NetworkMusicService.search()` |
| `GET /api/v1/music/search?q=...&type=playlist` | 搜索歌单 | 新增能力 |
| `GET /api/v1/music/search?q=...&type=album` | 搜索专辑 | 新增能力 |
| `GET /api/v1/music/stream` | 代理音频流/下载 | → `resolvePlayUrl()` 的替代方案 |
| `GET /api/v1/music/url` | 获取音频裸直链 | → `resolvePlayUrl()` 的直接映射 |
| `GET /api/v1/music/inspect` | 探测音频可用性/大小/码率 | 新增能力（Range 探测） |
| `GET /api/v1/music/switch` | 智能切换可用音源 | 新增能力（单曲换源） |
| `GET /api/v1/music/lyric` | 获取 JSON 格式歌词 | → `resolveLyrics()` |
| `GET /api/v1/music/lyric/file` | 下载 LRC 歌词文件 | → `resolveLyrics()` 的替代格式 |
| `GET /api/v1/music/cover` | 代理下载封面 | → `resolveCoverUrl()` |
| `GET /api/v1/music/playlist` | 歌单详情 | → `getPlaylist()` |
| `GET /api/v1/music/album` | 专辑详情 | 新增能力 |
| `GET /api/v1/music/recommend` | 推荐歌单 | 新增能力 |
| `GET /api/v1/music/playlist_categories` | 歌单分类 | 新增能力 |
| `GET /api/v1/music/category_playlists` | 分类歌单 | 新增能力 |
| `GET /api/v1/music/user_playlists` | 个人歌单 | 新增能力（需扫码登录） |
| `GET /api/v1/music/qr_login/:source` | 扫码登录 | 新增能力 |
| `GET /api/v1/music/switch_source` | 智能换源 | 新增能力 |

---

## 4. 方案对比

### 方案 A：直接对接 go-music-api（推荐）

**思路**：在 NASMusicTV 中新增 `GoMusicApiService` 实现 `NetworkMusicService`，将 go-music-api 部署为独立服务（Docker 或自建），通过 HTTP 调用其 REST 端点。

```
┌──────────────┐    HTTP    ┌──────────────────┐
│ NASMusicTV   │ ────────→  │ go-music-api     │
│ (Android TV) │ ←────────  │ (Docker 服务)     │
│              │  REST JSON │  │               │
│ GoMusicApi   │            │  └── music-lib   │
│ Service      │            │                  │
└──────────────┘            └──────────────────┘
```

**优点**：
- 零侵入：NASMusicTV 只做 HTTP 调用，不改底层架构
- 解耦：go-music-api 独立演进，更新平台逻辑不涉及 App 发版
- 快速上线：`NetworkMusicService` 只需一个实现类，API 映射清晰
- 多端复用：同一 go-music-api 实例可供手机/桌面/TV 共用
- 用户可选：保留 Meting 端点作为 fallback，用户可切换

**缺点**：
- 需要额外部署一个 Docker 服务（可接受，NAS 用户本身就有 Docker 环境）
- 新增网络依赖（但 NASMusicTV 本身就需要网络连接）

### 方案 B：直接内嵌 music-lib (Go 库)

**思路**：将 music-lib 编译为 Android NDK 可调用的 C 库，通过 JNI 桥接。

**优点**：无外部服务依赖
**缺点**：
- 开发成本极高（Go → C → JNI 链路）
- 需要保留 Go 交叉编译工具链
- 每次更新平台逻辑都需要发版 App
- 严重不推荐，否决

### 方案 C：保持现状，优化 Meting 端点的容错

**思路**：增加更多 Meting 预设端点，改进错误处理。

**优点**：改动最小
**缺点**：治标不治本，第三方代理的稳定性、平台覆盖、无损支持等根本问题无法解决

---

## 5. 推荐方案：方案 A（对接 go-music-api）

### 5.1 新增接口映射

`GoMusicApiService` 实现 `NetworkMusicService`，关键映射：

| NASMusicTV 方法 | go-music-api 调用 | 说明 |
|----------------|-------------------|------|
| `search(keyword, limit)` | `GET /api/v1/music/search?q={keyword}&type=song&limit={limit}` | 多源并发搜索，聚合结果 |
| `resolvePlayUrl(song)` | `GET /api/v1/music/url?id={id}&source={source}` | 获取裸直链 |
| `resolveLyrics(song)` | `GET /api/v1/music/lyric/file?id={id}&source={source}` | 下载 LRC |
| `resolveCoverUrl(song)` | 返回 null，直接使用 song.coverUrl | 搜索结果已含封面 URL |
| `searchCoverUrl(title, artist)` | 调用 search 取第一条结果 coverUrl | 与现有逻辑一致 |
| `getPlaylist(playlistId)` | `GET /api/v1/music/playlist?id={id}` | 歌单详情 |

### 5.2 新增界面能力

除了接口映射，go-music-api 还带来了以下新增能力，可逐步在 UI 中开放：

**Phase 1（基础替换）**：
- 网络音乐搜索源切换：Meting ↔ GoMusicAPI
- 单曲换源按钮（智能切换可用音源）
- 搜索结果中显示码率/文件大小（Range 探测）

**Phase 2（歌单/专辑深化）**：
- 歌单分类浏览（按平台查看分类）
- 专辑搜索与详情页
- 歌单/专辑链接解析（粘贴链接自动解析）

**Phase 3（登录与推荐）**：
- 扫码登录（Web 面板生成二维码，手机扫码）
- 个人歌单同步
- 每日推荐聚合
- 歌词升级（原文/译文/罗马音逐字高亮）

### 5.3 配置项

设置页新增"Go Music API 服务地址"输入框（默认空，用户填入端点 URL，如 `http://192.168.1.100:8080`），与现有 Meting 端点配置并列。

新增"网络音乐源"选择器（Meting / GoMusicAPI / 自动），默认自动。

---

## 6. 实施计划

### 阶段 1：基础设施（预估 2-3 天）

1. 创建 `GoMusicApiService.kt`，实现 `NetworkMusicService` 接口
2. 实现 `search()` 调用 go-music-api 搜索端点
3. 实现 `resolvePlayUrl()` 调用 `/api/v1/music/url`
4. 实现 `resolveLyrics()` 调用 `/api/v1/music/lyric/file`
5. 实现 `getPlaylist()` 调用 `/api/v1/music/playlist`
6. 在 `NetworkMusicManager` 注册新服务
7. 设置页新增服务地址配置项
8. 网络音乐 Tab 源选择器
9. 单元测试（mock go-music-api 响应）

### 阶段 2：增强能力（预估 2-3 天）

1. 单曲换源按钮 → `/api/v1/music/switch`
2. 搜索结果码率/大小显示 → `/api/v1/music/inspect`
3. 歌单分类浏览 → `/api/v1/music/playlist_categories` + `/api/v1/music/category_playlists`
4. 专辑搜索 + 详情页 → `/api/v1/music/search?type=album` + `/api/v1/music/album`
5. 链接解析（粘贴歌单/专辑链接自动识别）

### 阶段 3：高级功能（预估 3-4 天）

1. 扫码登录流程（在 Web 面板生成二维码，手机扫码后 Cookie 存入服务端）
2. 个人歌单同步
3. 每日推荐聚合
4. 歌词升级：双语逐字 LRC 渲染
5. 端到端测试 + 回归测试

---

## 7. 风险与权衡

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| go-music-api 服务不可用 | 网络音乐功能降级 | 保留 Meting 作为 fallback 源，自动切换 |
| go-music-api 响应延迟 | 搜索/播放体验变慢 | 超时控制（5s），缓存策略（播放 URL 5min TTL 沿用现有机制） |
| 用户不愿部署 Docker 服务 | 无法使用新功能 | **go-music-api 无公开 API 端点，必须自部署**（详见 §8）；部署成本极低（一个 docker-compose），NAS 用户基本零门槛 |
| 歌词格式差异 | 渲染兼容问题 | go-music-api 可同时返回 JSON 和 LRC 格式，兼容现有 `LrcParser` |
| 版权合规 | 法律风险 | 与现有 Meting 方案一致，网络搜索结果仅用于播放，不持久化下载 |

---

## 8. 附录：go-music-api 部署参考

### 8.1 公共实例搜索结果

**go-music-api 没有公开的 API 端点，必须自己部署。** 经过多轮搜索（含中文/英文/社区论坛/GitHub fork 仓库），全网未发现任何个人或第三方部署的公开 API 实例。所有搜索结果均为部署教程，无人在线提供服务。

**作者公开实例**（均不暴露 API）：

| 域名 | 状态（2026-08-10 实测） | 是否暴露 API |
|------|------------------------|:---:|
| `https://music.zkkp.nyc.mn`（**当前主页**） | ✅ 可正常访问，功能完整（12 平台聚合搜索、扫码登录、歌单/专辑/每日推荐） | ❌ `/api/v1/*` 与 `/music/*` 均返回 404 |
| `https://music.kukuqaq.com`（旧域名） | ⚠️ Cloudflare 拦截（HTTP 567），程序化访问被挡 | ❌ |

实测 `music.zkkp.nyc.mn`：搜索"晴天"返回 54 首，`qq/kugou/kuwo/qianqian/migu` 多平台聚合，歌词、封面、播放均正常——但**搜索结果是服务端渲染的 HTML，不是 JSON**，`/api/v1/music/search`、`/api/v1/music/url`、`/api/v1/music/recommend`、`/music/search?format=json`、`/api/search` 全部 404。作者只把 Web 播放器开放给用户用，未把后端 API 开放（公开 API 意味着任何人可借其代理下载资源，流量与合规风险不可控）。

**GitHub fork 仓库分析**：项目有多个 fork（`SimFantasy`、`cooblog`、`Hsintao`、`Furina-super-user`、`yyds-music`、`306026185`、`zhangyiming748` 等），但主页全部指向作者实例，无独立部署的公共实例。

**结论**：go-music-api 是专为自部署设计的服务，无"公共 SaaS"版本。NAS 用户通过 Docker 部署的成本极低（一个 `docker-compose.yml`），与现有 Meting 端点配置方式一致。

### 8.2 自部署（推荐）

```yaml
# docker-compose.yml
services:
  go-music-api:
    image: guohuiyuan/go-music-api:latest
    container_name: go-music-api
    ports:
      - "8080:8080"
    volumes:
      - ./data:/app/data
    environment:
      - TZ=Asia/Shanghai
    restart: unless-stopped
```

### 8.3 替代方案：go-music-dl 镜像

go-music-dl 的 Web 后端**内部同样暴露 `/api/v1/music/*` 路由**，可作为替代后端部署，多一个 Web 播放器功能、不多占端口：

```yaml
# docker-compose.yml
services:
  music-dl:
    image: guohuiyuan/go-music-dl:latest
    container_name: music-dl
    ports:
      - "8080:8080"
    volumes:
      - ./data:/home/appuser/data
    environment:
      - TZ=Asia/Shanghai
    restart: unless-stopped
    command: ["./music-dl", "web", "--port", "8080", "--no-browser"]
```

> 注：8.3 的 go-music-dl 镜像是否暴露 `/api/v1/*` 需实际部署验证；若仅暴露 `/music/*` 兼容路由，NASMusicTV 的 `GoMusicApiService` 只需切换 base path，接口映射不变。

### 8.4 轻量替代：Go 版 Meting-API

如果用户觉得部署 go-music-api 太重，也可以选择**升级现有的 Meting 代理层**——`honmaple/meting-api` 是一个 Go 重写的 Meting-API，支持网易云 + QQ 音乐，Docker 部署，接口格式与现有 Meting 端点完全兼容：

```yaml
services:
  meting-api:
    image: honmaple/meting-api:latest
    container_name: meting-api
    ports:
      - "8000:8000"
    volumes:
      - ./cache:/opt/meting-api/cache
    restart: unless-stopped
```

```bash
# 使用方式（与现有 Meting 端点完全一致，只需把设置页的端点地址指向自部署实例）
# 搜索：http://192.168.1.100:8000/?server=netease&type=search&id=周杰伦
# 播放：http://192.168.1.100:8000/?server=netease&type=url&id=123456
# 歌词：http://192.168.1.100:8000/?server=netease&type=lrc&id=123456
```

**与 go-music-api 的对比**：

| 维度 | Go 版 Meting-API | go-music-api |
|------|-----------------|--------------|
| 平台覆盖 | 网易云 + QQ 音乐 | 12 个平台 |
| 接入成本 | 与现有 Meting 端点完全兼容，改地址即可 | 需要新增 `GoMusicApiService` 实现 |
| 歌词 | 普通 LRC | 原文/译文/罗马音逐字 LRC |
| 歌单/专辑 | 基本支持 | 完整链路（分类/个人/链接解析/每日推荐） |
| 换源 | 无 | 智能换源 + Range 探测 |
| 扫码登录 | 无 | 网易云/QQ/酷狗/B站 |

**选择建议**：只想稳定核心搜索 + 歌词的用户可先用 Go 版 Meting-API 替换现有 Meting 端点（零代码改动），需要完整能力的用户再上 go-music-api。

---

## 9. 附录：`GoMusicApiService` 接口设计草图

```kotlin
/**
 * Go Music API 网络音乐服务实现
 *
 * 对接 go-music-api 的 REST 端点，提供多平台音乐搜索、播放 URL 解析、
 * 歌词获取、封面获取、歌单/专辑详情等能力。
 *
 * 部署要求：用户需在 Docker 中运行 go-music-api 服务，或在设置页填入服务端地址。
 * 兼容性：保留 Meting 端点为 fallback 源，用户可自由切换。
 */
class GoMusicApiService(
    private val client: OkHttpClient,
    /** 获取当前 go-music-api 服务地址，如 http://192.168.1.100:8080 */
    private val baseUrlProvider: () -> String,
    /** 默认搜索源，如 "netease" / "qq" / "auto" */
    private val defaultSourceProvider: () -> String = { "auto" }
) : NetworkMusicService {

    override val sourceId = "go-music-api"

    // ── 搜索 ──
    // GET /api/v1/music/search?q={keyword}&type=song&source={source}&limit={limit}
    override suspend fun search(keyword: String, limit: Int): List<Song> = ...

    // ── 播放 URL ──
    // GET /api/v1/music/url?id={id}&source={source}
    // 或直接使用 stream 代理：GET /api/v1/music/stream?id={id}&source={source}
    override suspend fun resolvePlayUrl(song: Song): String? = ...

    // ── 歌词 ──
    // GET /api/v1/music/lyric/file?id={id}&source={source}
    // 返回 LRC 文本
    override suspend fun resolveLyrics(song: Song): String? = ...

    // ── 封面 ──
    // 搜索结果中的 coverUrl 可直接使用（go-music-api 返回封面直链）
    override suspend fun resolveCoverUrl(song: Song): String? = null

    // ── 按标题+艺术家搜封面 ──
    // 调用 search 取第一条结果
    override suspend fun searchCoverUrl(title: String, artist: String): String? = ...

    // ── 歌单歌曲 ──
    // GET /api/v1/music/playlist?id={playlistId}
    override suspend fun getPlaylist(playlistId: String): List<Song> = ...

    // ── 扩展能力（非接口方法，供 UI 直接调用） ──

    /** 智能换源：GET /api/v1/music/switch?id={id}&source={source} */
    suspend fun switchSource(song: Song): Song? = ...

    /** 音频探测：GET /api/v1/music/inspect?url={encodedUrl} */
    suspend fun inspectAudio(url: String): AudioInfo? = ...

    /** 专辑详情：GET /api/v1/music/album?id={id} */
    suspend fun getAlbumDetail(albumId: String): AlbumDetail? = ...

    /** 歌单推荐：GET /api/v1/music/recommend */
    suspend fun getRecommendPlaylists(): List<Playlist> = ...

    /** 歌单分类：GET /api/v1/music/playlist_categories */
    suspend fun getPlaylistCategories(): List<PlaylistCategory> = ...

    /** 每日推荐：GET /api/v1/music/recommend/daily?source={source} */
    suspend fun getDailyRecommendations(): List<Song> = ...
}
```