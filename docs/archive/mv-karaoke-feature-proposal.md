# K 歌 MTV 视频版 — 技术方案

> 状态：待评审（未开始开发）
>
> 本文档描述在现有 K 歌（人声消除 + 逐字歌词）功能之上，新增"音乐视频（MTV）"能力的技术方案。覆盖：免费 MV 搜索接口选型、后台自动搜索、MTV 按钮亮/暗、独立 MTV 页面与歌词显隐。
>
> 关联文档：`vocal-removal-approach-b-dsp.md`（人声消除方案 B）、`technical-overview.md` §4/§10。

---

## 一、方案概述

### 1.1 需求（来自用户描述）

1. 找到能搜索音乐视频的**免费接口**（硬约束：**国内可直连**）
2. 播放某首歌时，**后台自动搜索**这首歌的音乐视频
3. 搜到 → 界面出现一个 **MTV 按钮并亮起**，用户点击后**进入独立 MTV 页面**播放该音乐视频
4. 没搜到 → MTV 按钮**置暗、不可点击**
5. MTV 播放页面**默认不显示歌词字幕**，页面增加**「歌词」按钮**，点击显示/隐藏歌词

### 1.2 已拍板的决策

| 决策点 | 结论 |
|---|---|
| MV 数据源 | **Bilibili 非官方 API**（国内直连、免登录、直链 mp4）为 v1 唯一主源 |
| 页面形态 | **独立 MTV 页面**（新增 `MvPlaybackScreen`），不复用/不侵入 K 歌页（K 歌页零回归风险） |
| 播放模型 | **独立第二个 ExoPlayer** 播 MV；进 MTV 页面时**暂停主播放器**，退出时恢复 |
| 搜索时机 | 歌曲开始播放/切歌时**只搜一次**；结果仅**内存缓存**（MV URL 有有效期，不做持久化） |
| 人声分离 | MV 音频无 Mid/Side 可分离 → MTV 页面**不提供**「原唱/伴奏」切换 |
| 歌词默认状态 | MTV 页面默认隐藏歌词；点「歌词」按钮切换显示（仅按进度百分比粗略对齐，逐字不保证准） |

---

## 二、接口选型调研（免费 · 国内可访问）

### 2.1 结论：Bilibili 非官方 API 是唯一现实选项

| 候选 | 国内可用 | 免登录 | 播放可行性 | 结论 |
|---|---|---|---|---|
| **Bilibili 非官方 API** | ✅ 直连 | ✅ | 直链 mp4，ExoPlayer 直接播 | **选用** |
| 网易云 MV（Meting 公共端点） | ✅ | ❌ | 公共 Meting 实例基本挡 MV 类接口（登录态/加密） | 弃用（v2 自制服务端再议） |
| YouTube Data API | ❌ 不可达 | 需 API Key | 需爬 playerResponse | 排除 |
| Archive.org | ✅ | ✅ | 无中文歌 MV | 排除 |

### 2.2 Bilibili 三步取流（实施时需实测验证）

1. **搜索** → 拿 `bvid`
   - 接口：`GET https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=video&keyword=<title+artist>`
   - 未登录有风控（web log Q 参数 / 验证码风险），**实施时需实测**；备选：按 `video` 类型过滤 + 结果按标题相似度排序取第一条
2. **拿 cid**（视频分 P 唯一 ID）
   - 接口：`GET https://api.bilibili.com/x/web-interface/view?bvid=<bvid>`
   - 返回 `cid`、`title`、`pic`（封面）、`duration`
3. **取播放直链**
   - 接口：`GET https://api.bilibili.com/x/player/playurl?bvid=<bvid>&cid=<cid>&fnval=1`
   - `fnval=1` → m4s/mp4 分片列表（`durl[].url`，通常 2 段：视频 + 音频，音频需分离自行合并的为高码率档；`fnval=16` 为 DASH）
   - 免登录时最高清晰度受限（一般 ≤ 540p/720p），对电视 K 歌足够
   - **防盗链**：播放 URL 校验 `Referer: https://www.bilibili.com` 与 UA，OkHttp 需设置请求头
   - 直链有**有效期**（小时级）→ 缓存必须带时间戳，过期重建

> ⚠️ 以上端点与参数以实施时实测为准；B 站风控/离线接口变动是常态，方案需内置失败回退（见 §6 风险）。

---

## 三、架构设计

```
                                      现有层（不动）
    ┌────────────────────────────────────────────────────────┐
    │ NasMusicApp (手动 DI 容器)                               │
    │ ├─ networkMusicManager: NetworkMusicManager  ← 已有      │
    │ ├─ playerManager: PlayerManager          ← 已有          │
    │ └─ mvSearchManager: MvSearchManager      ← 新增         │
    └────────────────────────────────────────────────────────┘

                                      新增层（backend/network/mv/）
    ┌────────────────────────────────────────────────────────┐
    │ MvSearchManager (NasMusicApp.onCreate 初始化)            │
    │ ├─ services: List<MvSearchService>                      │
    │ ├─ searchMv(song) → MvInfo?         (后台协程)           │
    │ └─ 内存缓存: ConcurrentHashMap<key, CachedMv>（TTL）      │
    ├────────────────────────────────────────────────────────┤
    │ MvSearchService (接口)                                   │
    │ └─ BilibiliMvService (v1 唯一实现)                       │
    │    ├─ searchByKeyword(title, artist) → bvid              │
    │    ├─ getVideoInfo(bvid) → MvInfo (cid/title/cover)      │
    │    └─ getPlayUrl(bvid, cid) → 直链 mp4                   │
    └────────────────────────────────────────────────────────┘

                                      融合层
    ┌────────────────────────────────────────────────────────┐
    │ MainViewModel                                            │
    │ ├─ mvState: StateFlow<MvAvailability> (搜索中/有/无)     │
    │ ├─ triggerMvSearch() ← 播放/切歌时自动调用               │
    │ └─ showMv bool (进入 MTV 页面)                            │
    ├────────────────────────────────────────────────────────┤
    │ KaraokePlaybackScreen (音频 K 歌，不动)                  │
    ├────────────────────────────────────────────────────────┤
    │ MvPlaybackScreen (新增独立 MTV 页面，与 K 歌页并列)      │
    │ ├─ PlayerView 全屏视频 (AndroidView + 独立 ExoPlayer)    │
    │ ├─ 歌词切换按钮（默认隐藏）                              │
    │ └─ 无原唱/伴唱按钮（MV 音频不可分离）                    │
    ├────────────────────────────────────────────────────────┤
    │ NowPlayingScreen                                         │
    │ ├─ ControlButtonsRow 增加 MTV 按钮（亮/暗）              │
    │ └─ showMv → 切换到 MvPlaybackScreen                      │
    └────────────────────────────────────────────────────────┘
```

### 3.1 关键设计决策

1. **独立 MTV 页面 + 独立视频播放器（不污染主音频链路）**
- 页面形态与 K 歌页**完全解耦**：新增独立 `MvPlaybackScreen`，K 歌页零改动（K 歌功能零回归风险）
- 现有 `PlayerManager` 单实例 ExoPlayer 承载原位 DSP、均衡器、进度轮询——MV 视频必须用**第二个独立 ExoPlayer**：
  - 进入 MTV 页面 → `PlayerManager.pause()`（主播放器暂停，保留播放位置）
  - 退出 MTV 页面 → 恢复主播放器播放
  - 视频播放器不注册到 `PlayerManager`、不触碰 DSP 管线
- MTV 页面内进度条/播放暂停控制**作用于视频播放器自身**

2. **数据流单向**：`MvSearchManager` 只产出 `MvInfo`（URL 直链）；UI 只消费 `mvState`；切歌即触发重搜并失效旧缓存。

3. **MTV 按钮状态机**（三态）：
   - `SEARCHING`（灰/转圈）→ 搜索完成后定格
   - `AVAILABLE`（亮，可点击）
   - `NOT_FOUND`（暗，禁用）

---

## 四、数据模型变更

### 4.1 新增 `data/model/MvInfo.kt`

```kotlin
data class MvInfo(
    val bvid: String,
    val title: String,          // MV 标题（用于调试/展示）
    val coverUrl: String?,      // 封面
    val videoUrl: String,       // 直链 mp4
    val durationMs: Long = 0L,
    val fetchedAt: Long = System.currentTimeMillis()  // 用于缓存过期判断
)
```

### 4.2 新增 UI 状态（`MainViewModel` 内）

```kotlin
sealed interface MvAvailability {
    object Idle : MvAvailability            // 无歌曲/不需要搜索
    object Searching : MvAvailability
    data class Ready(val mv: MvInfo) : MvAvailability
    object NotFound : MvAvailability
}
```

---

## 四、分步实施计划（按文件）

### Step 1 — 后端搜索层（新增包 `backend/network/mv/`）

| 文件 | 内容 |
|---|---|
| `MvSearchService.kt` | 接口：`suspend fun searchMv(title: String, artist: String): MvInfo?` |
| `BilibiliMvService.kt` | 三步实现（搜索 → view → playurl）；OkHttp + `Referer: https://www.bilibili.com` + 浏览器 UA；超时/错误吞掉返回 null |
| `MvSearchManager.kt` | 多源 fallback 骨架（v1 单源）；内存缓存 `ConcurrentHashMap<String, CachedMv>`，TTL 建议 30–60 分钟；`searchMvFor(song)` 组合 `title+artist` 构造关键词 |

**接入点**：`NasMusicApp.onCreate` 构建并持有（参照 `networkMusicManager` 注入方式）。

### Step 2 — 视图模型接线

`MainViewModel`：
- 新增 `mvState: StateFlow<MvAvailability>`
- 监听歌曲切换（现有 `currentSong` collector）→ 自动调 `triggerMvSearch()`：置 `Searching` → `mvSearchManager.searchMvFor(song)` → `Ready/NotFound`
- 新增 `enterMvMode()` / `exitMvMode()`（切换 MTV 页面显隐 + 暂停/恢复主播放器）

### Step 3 — 播放页 MTV 按钮

`NowPlayingScreen.kt` 的 `ControlButtonsRow`：
- 新增 MTV 图标按钮（建议 `Icons.Filled.PlayCircle` / 自定义 MTV 字样）
- `available = mvState is Ready`；非 Ready 时 `enabled=false` + 半透明
- 点击 → `enterMvMode()`（切到独立 MTV 页面）

### Step 4 — 独立 MTV 页面

**新增 `ui/screens/network/MvPlaybackScreen.kt`，K 歌页零改动，与 `KaraokePlaybackScreen` 并列**：

- 页面结构（视频 + 控制 + 歌词三要素）
  - 全屏视频 `AndroidView(PlayerView)` 铺底（`AspectRatio.RESIZE_MODE_FIT` → 铺满），前景叠加暗色渐变遮罩（透明度调低保证视频可见）
  - 顶部返回键（退出 MTV 页面 → 恢复主播放器）；底部迷你控制条（播放/暂停、进度）
- 视频播放器生命周期：
  - `DisposableEffect`/`remember` 内创建 ExoPlayer + `PlayerView`；`mv.videoUrl` 变化时 `setMediaItem` + `prepare` + `play`
  - `DisposableEffect` onDispose → `release()`；退出页时恢复主播放器
- **歌词**：「歌词」按钮（控制条右侧）toggle `showMvLyrics: remember { mutableStateOf(false) }`；显示时叠加现有 `KaraokeLyricsView`（半透明底，仅按 progressMs 粗略对齐，逐字不保证准）
- **无原唱/伴奏按钮**（MV 音频不可分离，不在 MTV 页渲染）

### Step 5 — 依赖与 ProGuard

- `app/build.gradle.kts`：确认已有 `media3-exoplayer`（播放核心）；若用 `PlayerView` 需加 `androidx.media3:media3-ui`（或纯 `SurfaceView` + `Player.setVideoSurfaceView` 零新依赖——**首选后者**，Kotlin 端最简）
- `proguard-rules.pro`：Bilibili DTO 类保留（若用 Gson 解析）——沿用现有 `-keep` data.model 风格

### Step 6 — 测试与文档

- 单测：`MvSearchManagerTest`（缓存 TTL、key 组合、fallback 空结果）、Bilibili 响应解析（用本地 JSON fixture，不联网）
- 文档：`technical-overview.md` §10 追加记录、README 更新功能列表

---

## 5. 验收标准

1. 播放任意有 MV 的歌（限 B站覆盖范围内的歌）→ MTV 按钮亮起可点击
2. 播放无 MV 的歌 → MTV 按钮暗、按不动
3. 点击 MTV → 进入**独立 MTV 页面**播放视频，主播放器暂停；退出 → 音乐恢复
4. MTV 页面默认无歌词；按「歌词」按钮显示/隐藏
5. MTV 页面无「原唱/伴唱」按钮
6. 切歌后 MTV 页面自动重新搜索新歌 MV（或退回普通播放模式，见 Open 问题）

---

## 6. 风险与注意事项

| 风险 | 影响 | 对策 |
|---|---|---|
| B 站接口变动/风控（不签名搜索可能 412） | MTV 按钮常暗 → 功能失去意义 | 实施前**实测三种搜索路径**（sign 参数、wbi 签名、`danmaku` 替代接口）；失败降级静默（按钮置暗即可） |
| 直链有效期（小时级） | 缓存 URL 中途失效 | 缓存 TTL 30–60 分钟；播放失败 → 清缓存重搜一次 |
| 高清档需要登录 | 画质低 | 电视 K 场景 540p 可接受；不登录（保住"免费"约束） |
| 视频页歌词只能"大致对齐" | 显示时歌词与画面音不同步 | 需求已确认默认隐藏；显示时仅做进度对齐，标注"粗略" |
| 额外流量 | 电视端流量消耗 | 视频仅按需加载；退出即 release，不预载 |

---

## 7. 待评审问题

1. B 站搜索接口免登录风控是最大不确定点——是否接受"MTV 偶发不可用"作为 v1 现状？
2. MTV 页面切歌行为：A. 保持 MTV 页面继续播新歌视频（首推）；B. 退回普通播放模式
3. 是否需要在设置页添加"启用 MTV"开关（防弱网电视）？

---

*由 Sisyphus 起草, 2026-08-08。尚未实现。*