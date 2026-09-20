# 统一音乐源架构与界面重设计

## TL;DR

> **概要**: 将 NASMusicTV 的 8+ 个数据源（NAS 后端、网络音乐、百度网盘、电台、Jamendo）的所有共用功能提取到统一界面组件中，实现"一个搜索搜所有源、一个页面管所有功能"的体验。
>
> **交付物**:
> - 统一搜索页面（跨全部数据源）
> - 统一"发现"页面（网络多维度浏览 + Jamendo 风格浏览 + 热门推荐）
> - 统一 UI 组件库（SongRow、AlbumCard、CoverImage、SourceBadge 等）
> - 百度网盘和电台融入统一 UI 架构
> - 页面导航结构优化
>
> **预估工作量**: 大型（29 个任务 + 4 个验证, 4 个波次）
> **并行执行**: 是 - 4 个波次, 波次 1（7 任务）, 波次 2（12 任务）, 波次 3（10 任务）, 波次 FINAL（4 任务）
> **关键路径**: 任务 1 → 任务 7 → 任务 9 → 任务 11 → 任务 18 → 任务 25 → 26 → 27 → F1-F4 → 用户确认

---

## 背景

### 原始需求
用户希望将所有数据源的共用功能提取到统一界面：歌曲搜索及搜索结果、天气电台、以及其他能统一的内容。重新设计界面，每个功能都用同一个页面。

### 访谈摘要
**关键决策**:
- 统一搜索覆盖全部源：NAS 后端 + 网络音乐 + 百度网盘 + Jamendo，结果混合展示并标注来源
- 天气电台功能移除（UI 入口去掉，后台代码保留供首页天气卡片使用）
- 浏览/发现功能统一为"发现"页面，用来源选择器切换 NAS 曲库浏览、网络多维度浏览、Jamendo 风格浏览
- 百度网盘和电台融入统一 UI 架构，歌曲列表和搜索统一，目录浏览适配统一组件模板

**调研发现**:
- 已有 Song 统一数据模型（isNetworkSong, networkSource, networkId），为统一提供基础
- 播放队列、歌单、收藏已统一处理所有来源的歌曲
- 当前有 2 个独立 Tab 体系：NetworkMusicContainer（6 子 Tab）和 LibraryScreen（8 子 Tab）
- 歌曲卡片/列表组件在多个屏幕中重复实现（HomeScreen、BrowseSubTab、SearchSubTab 等）
- 百度网盘有独立 Screen（NetdiskScreen），与主架构隔离

---

## 工作目标

### 核心目标
将所有音乐源的所有共用功能提取到统一 UI 组件和页面中，实现"一处搜索、一处浏览、一处管理"的统一体验，同时消除重复代码。

### 具体交付物
- `ui/components/song/` — 统一歌曲组件包（SongRow, SongCard, SongGrid, SourceBadge）
- `ui/components/common/` — 统一通用组件包（CoverImage, LoadingError, SectionHeader, ActionBar）
- `ui/components/album/` — 统一专辑卡片组件
- `ui/screens/LibraryScreen.kt` — 改造后的曲库页（新增搜索/发现子 Tab）
- `ui/screens/library/` — 曲库子 Tab 目录（SearchTab, DiscoverTab, 原有 Tab 等）
- `data/model/SourceIdentifier.kt` — 统一来源标识系统
- `backend/SearchAggregator.kt` — 跨源搜索聚合器

### 完成标准
- [ ] 所有 Screen 共用统一 SongRow 组件（无副本）
- [ ] 曲库页搜索 Tab 一次输入即可搜索全部源，结果带来源标签
- [ ] 曲库页发现 Tab 支持三种模式切换（网络音乐/Jamendo/热门推荐）
- [ ] 百度网盘和电台的歌曲列表使用统一组件
- [ ] 主 Tab 为 5 个：首页 / 曲库 / 播放队列 / 我的 / 设置
- [ ] `./gradlew.bat assembleDebug` 编译通过
- [ ] 无重复的 SongRow 实现（grep "SongRow\|SongCard" 只应有 1 个定义）

### 必须包含
- 曲库页内搜索 Tab 覆盖全部数据源（NAS + 网络音乐 + 百度网盘 + Jamendo）
- 统一 SongRow 组件替换所有页面的独立实现
- 曲库页内发现 Tab 覆盖网络音乐多维度筛选、Jamendo 独立音库、热门推荐（不重复 NAS 曲库 Tab）
- 曲库页内电台 Tab 可正常使用
- 统一 CoverImage 组件（Coil + 音乐符号 fallback 模式）
- 百度网盘融入统一 UI
- "我的"页面聚合所有源的收藏、最近播放、歌单（NAS 后端 + 网络音乐 + 本地）
- 曲库页不包含"收藏/最近/歌单"Tab（已移入"我的"）
- 主 Tab 为 5 个：首页 / 曲库 / 播放队列 / 我的 / 设置（移除网络音乐 Tab）

### 禁止事项（防护栏）
- 不改动数据层核心接口（BackendAdapter、NetworkMusicService、BaiduPanApi 保持现有接口签名）
- 不改动现有播放器链路（PlayerManager、PlaybackService、ExoPlayer 逻辑）
- 不改动歌词系统（LyricsManager、Karaoke 组件）
- 不改动 MV/MTV 系统（MvSearchManager、MvPlaybackScreen）
- 不改动现有数据持久化格式（DataStore key 不做迁移）
- 不引入新的 DI 框架（保持 Manual DI 在 NasMusicApp 中）
- 主 Tab 为 5 个（首页/曲库/播放队列/我的/设置），移除网络音乐 Tab
- 不引入第三方 UI 库（保持 TV Compose material3）

---

## 界面调整方案

### 当前导航结构（调整前）

```
Screen 枚举:
  Home → NowPlaying → Library → Mine → Queue → Settings → ServerConnect
  → AlbumDetail → ArtistDetail → Equalizer → PlaylistManagement
  → Network (含 6 子 Tab: DISCOVER/WEATHER/SEARCH/BROWSE/RADIO/JAMENDO)
  → NetworkPlaylistDetail → Netdisk

问题:
  1. 网络音乐页和曲库页各自独立，各有自己的 Tab 体系
  2. 搜索入口分散（网络音乐页有搜索 Tab，曲库页有搜索栏）
  3. 百度网盘是完全独立的 Screen
  4. 歌曲列表/卡片组件在各页面中重复实现，样式不完全一致
  5. 天气电台实际使用率低，占用了 Tab 空间
```

### 调整后导航结构

```
主 Tab 共 5 个（首页 / 曲库 / 播放队列 / 我的 / 设置）:
  首页 → 欢迎页 + 统计卡片 + 最近播放 + 快捷操作
  曲库 → 统一搜索 / 统一发现 / 电台 / 专辑 / 艺术家 / 歌曲 / 流派 / 年代
       ← 统一发现：网络音乐多维度筛选 + Jamendo 独立音库 + 热门推荐（各源聚合）
       ← 不重复专辑/艺术家/歌曲/流派/年代（那些是 NAS 曲库浏览，留在各自的 Tab 中）
       ← 网络音乐所有功能已合并至此，不再需要独立"网络音乐"Tab
       ← 收藏/最近/歌单已移出到"我的"
       ← 天气电台功能已移除（代码保留，UI 入口去掉）
  播放队列 → 当前播放列表（所有源歌曲统一管理，排序/移除/清空）
  我的 → 收藏（所有源统一） / 最近播放（所有源） / 歌单（NAS 歌单 + 本地歌单）
       ← 自动读取 NAS 后端和网络音乐的各源收藏/歌单，统一展示
  设置 → 服务器配置 / 均衡器 / 歌词 / 封面 / 缓存 / 网络音乐端点 / MV 端点 / 天气 API Key / 关于

Screen 枚举（不新增顶级 Screen，统一功能放在曲库内）:
  首页 Home
  正在播放 NowPlaying
  播放队列 Queue  ← 主 Tab 入口：所有源歌曲统一管理
  曲库 Library  ← 主 Tab 入口：改造，内部包含所有源的所有功能
  我的 Mine  ← 主 Tab 入口：聚合收藏/最近播放/歌单（所有源统一）
  设置 Settings  ← 主 Tab 入口
  服务器连接 ServerConnect
  专辑详情 AlbumDetail      ← 保留：统一组件替换内部卡片
  艺术家详情 ArtistDetail    ← 保留：统一组件替换内部卡片
  均衡器 Equalizer
  歌单管理 PlaylistManagement
  网络歌单详情 NetworkPlaylistDetail
  百度网盘 Netdisk          ← 保留：目录浏览特殊，歌曲列表使用统一组件
  # 网络音乐 Network  ← 移除：所有功能已迁移到曲库页

曲库页面内部 Tab 设计:
┌─────────────────────────────────────────────────┐
│  搜索 ｜ 发现 ｜ 电台 ｜ 专辑 ｜ 艺术家 ｜ 歌曲 ｜ 流派 ｜ 年代  │
└─────────────────────────────────────────────────┘
  ↑ 新增              ↑ 原有 5 个 NAS 曲库 Tab，保留

操作流程示例:
  首页 → 曲库 → 搜索 Tab → 输入关键词 → 同时显示 NAS/网络/百度/Jamendo 结果
  首页 → 曲库 → 发现 Tab → 选择模式（网络音乐/Jamendo/热门推荐）→ 浏览
  首页 → 曲库 → 电台 Tab → 搜索/浏览全球电台 → 播放
  首页 → 曲库 → 专辑 Tab → 浏览 NAS 专辑 → 进入详情
  首页 → 我的 → 收藏 Tab → 查看所有源的收藏歌曲 → 播放
  首页 → 我的 → 歌单 Tab → 查看 NAS 歌单 + 本地歌单 → 播放
```

### 组件层级结构

```
ui/components/
├── common/                    ← 通用基础组件
│   ├── CoverImage.kt          ← 统一封面（Coil + 音乐符号 fallback）
│   ├── SourceBadge.kt         ← 来源标签（不同颜色标识各源）
│   ├── ListStateIndicators.kt ← 加载中/错误/空状态
│   ├── SectionHeader.kt       ← 节标题 + 计数 + 查看全部
│   └── ActionBar.kt           ← 操作栏（播放全部/加入队列/收藏）
├── song/                      ← 歌曲相关组件
│   ├── UnifiedSongRow.kt      ← 歌曲行（MODE_ROW / MODE_CARD / MODE_COMPACT）
│   └── UnifiedSongGrid.kt     ← 歌曲网格（响应式列数）
├── album/                     ← 专辑相关组件
│   └── UnifiedAlbumCard.kt    ← 专辑卡片
├── artist/                    ← 艺术家相关组件
│   └── UnifiedArtistCard.kt   ← 艺术家卡片
└── playlist/                  ← 歌单相关组件
    ├── UnifiedPlaylistCard.kt ← 歌单卡片
    └── UnifiedPlaylistGrid.kt ← 歌单网格
```

### 统一搜索页布局

```
┌─────────────────────────────────────────┐
│  🔍 [搜索框（自动聚焦）]             [搜索] │
│  搜索历史：周杰伦 林俊杰 陈奕迅 ...        │
├─────────────────────────────────────────┤
│  来源: [NAS] [网络音乐] [百度盘] [Jamendo] │  ← 点亮模式，选中=高亮
│        [全部点亮]                         │  ← 一键全部点亮
├─────────────────────────────────────────┤
│  🎵 晴天 - 周杰伦              [NAS] 03:45 │  ← 统一 SongRow 组件
│  🎵 晴天 - 周杰伦           [网络] 04:12 │     + 来源标签（去重后保留多条）
│  🎵 稻香 - 周杰伦           [百度] 03:30 │
│  ...                                    │
├─────────────────────────────────────────┤
│  [▶ 播放全部]  [+ 加入队列]    共 25 首  │  ← 统一 ActionBar
└─────────────────────────────────────────┘

交互逻辑:
  - 来源按钮默认全部点亮（= 搜索所有源）
  - 点击某个来源切换点亮/熄灭 → 熄灭的源不参与本次搜索
  - 只点亮一个源 → 只搜索该源
  - 全部熄灭 → 搜索按钮置灰
  - "全部点亮"按钮 → 一键全部点亮
  - 搜索结果统一混合展示，不按来源分组
  - 每首歌曲显示来源标签（SourceBadge）标识来自哪个源
  - 同一歌曲名不同来源的结果全部保留（如 NAS 和网络都搜到"晴天"）
```

### 统一搜索逻辑详解

#### 整体流程

```
用户输入关键词 → 回车/点击搜索
                        │
                        ▼
               ┌──────────────────┐
               │  SearchAggregator │  ← 跨源搜索聚合器
               │  并行搜索所有源    │
               └────────┬─────────┘
                        │
         ┌──────────────┼──────────────┐
         ▼              ▼              ▼
   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
   │ NAS 搜索  │  │ 网络搜索  │  │百度盘搜索 │  │Jamendo搜索│  ← 并行执行，互不影响
   │ 5s 超时  │  │ 5s 超时  │  │ 8s 超时  │  │ 5s 超时  │
   └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
        │              │              │
        ▼              ▼              ▼
   ┌──────────┐  ┌──────────┐  ┌──────────┐
   │ 结果列表  │  │ 结果列表  │  │ 结果列表  │
   │ +来源标签 │  │ +来源标签 │  │ +来源标签 │
   └────┬─────┘  └────┬─────┘  └────┬─────┘
        │              │              │
        └──────────────┼──────────────┘
                       ▼
              ┌──────────────────┐
│  合并去重          │
               │  按歌曲名去重      │
               │  跨源相同歌曲名保留 │
              └────────┬─────────┘
                       ▼
              ┌──────────────────┐
              │  排序 + 分页       │
              │  默认按源优先级    │
              │  NAS → 网络 → 百度 │
              └────────┬─────────┘
                       ▼
              ┌──────────────────┐
              │  返回给 UI 层      │
              │  SearchTab 展示   │
              └──────────────────┘
```

#### 各源搜索实现

| 数据源 | 搜索方法 | 超时时间 | 分页支持 | 备注 |
|--------|---------|---------|---------|------|
| NAS 后端 | `BackendAdapter.searchSongs(query)` | 5 秒 | 否（全量返回） | Jellyfin/Navidrome/Subsonic 统一接口 |
| 网络音乐 | `NetworkMusicManager.search(query)` | 5 秒 | 否 | Meting-API 多端点 fallback |
| 百度网盘 | `BaiduNetdiskService.search(keyword)` | 8 秒 | 否 | 优先本地索引，fallback API |
| Jamendo | Jamendo API 搜索 | 5 秒 | 是（分页） | 需配置 Client ID |

#### 并行搜索策略

```
SearchAggregator.search(keyword, sources=[NAS, 网络, 百度, Jamendo]):
  1. 为每个源创建独立协程
  2. 所有协程同时启动（并行）
  3. 每个协程带独立超时计时器
  4. 超时的源返回空结果（不抛异常）
  5. 等待所有协程完成（或超时）
  6. 合并结果集
```

```kotlin
// 伪代码逻辑
suspend fun search(keyword: String): SearchResult {
    return coroutineScope {
        val nasDeferred = async {
            withTimeout(5000) { backendAdapter?.searchSongs(keyword) ?: emptyList() }
        }
        val networkDeferred = async {
            withTimeout(5000) { networkMusicManager.search(keyword) }
        }
        val baiduDeferred = async {
            withTimeout(8000) { baiduService.search(keyword) }
        }
        val jamendoDeferred = async {
            withTimeout(5000) { jamendoApi.search(keyword) }
        }
        
        // 收集结果，超时的源返回空列表
        val results = listOf(
            nasDeferred.safeAwait()?.map { it.toRankedSong(MusicSourceType.NAS) } ?: emptyList(),
            networkDeferred.safeAwait()?.map { it.toRankedSong(MusicSourceType.NETWORK_MUSIC) } ?: emptyList(),
            baiduDeferred.safeAwait()?.map { it.toRankedSong(MusicSourceType.BAIDU_PAN) } ?: emptyList(),
            jamendoDeferred.safeAwait()?.map { it.toRankedSong(MusicSourceType.JAMENDO) } ?: emptyList()
        ).flatten()
        
        SearchResult(
            allResults = results,
            sourceBreakdown = results.groupBy({ it.source }, { it.song })
                .mapValues { it.value.size }
        )
    }
}
```

#### 去重逻辑

```
去重依据: 歌曲名 + 歌手（归一化后小写对比）
去重策略:
  - 同一源内：相同歌曲名 + 相同歌手的只保留第一个
  - 同一源内：相同歌曲名 + 不同歌手的全部保留（用户可自行选择想听的版本）
  - 跨源：相同歌曲名 + 相同歌手的全部保留（来源不同，播放地址不同）
  - 跨源：相同歌曲名 + 不同歌手的全部保留
  - 例如：NAS 搜到"晴天-周杰伦"和"晴天-周杰伦(演唱会版)" → 歌手相同，保留第一个
  - 例如：NAS 搜到"晴天-周杰伦"、网络搜到"晴天-周杰伦" → 两条都保留，标注不同来源
  - 例如：网络搜到"晴天-周杰伦"和"晴天-蔡依林" → 歌手不同，两条都保留

排序规则:
  - 默认按来源优先级：NAS > 网络音乐 > 百度盘 > Jamendo
  - 同一源内按匹配度降序
  - 相同歌曲名+不同歌手时，按歌手名排序
```

#### 来源选择逻辑（点亮模式）

```
来源点亮/熄灭决定"搜索哪些源"，而不是"过滤已搜到的结果"。

默认状态: [NAS] [网络音乐] [百度盘] [Jamendo] 全部点亮
  → 回车搜索时，SearchAggregator 并行搜索所有 4 个源

用户熄灭"百度盘":
  [NAS] [网络音乐] [百度盘] [Jamendo]  → 百度盘不参与搜索
  → 回车搜索时，只搜索 NAS + 网络音乐 + Jamendo

用户只点亮"网络音乐":
  [NAS] [网络音乐] [百度盘] [Jamendo]  → 只搜索网络音乐
  → 回车搜索时，只搜索网络音乐

"全部点亮"按钮:
  → 点击后全部点亮（回到默认状态）

关键行为:
  - 来源选择影响搜索范围，而非搜索结果过滤
  - 每次搜索都按当前点亮的源重新请求
  - 搜索结果统一展示在列表中，不按来源分组
  - 每首歌曲通过 SourceBadge 标识来源
  - 同一歌曲名跨源出现时保留多条，各自标注来源
  - 搜索历史中的关键词再次搜索时，也使用当前点亮的源
```

#### 搜索历史

```
- 每次搜索成功后记录到 DataStore（复用 AppPreferences.recordSearch）
- 30 天 TTL 自动清理
- 超过 200 条时裁剪尾部
- 搜索结果为空时也记录（用户可能想再试）
- 搜索历史显示在搜索框下方，可点击重新搜索
```

#### 分页与加载更多

```
- 首次搜索：加载所有源的前 N 条结果（N 可配置，默认 50）
- 加载更多：每个源各自翻页（网络音乐/Jamendo 支持分页）
- 滚动到底部时自动触发加载更多
- 加载更多时只对当前选中的来源 Tab 翻页
- 显示"已加载 X / 共 Y 首"的计数
```

#### 错误处理

```
NAS 超时 → 跳过，不阻塞其他源，不显示错误提示
网络音乐超时 → 跳过，不阻塞
百度盘超时 → 跳过，不阻塞
Jamendo 未配置 → 跳过，显示"未配置"提示
所有源都失败 → 显示"搜索无结果，请检查网络连接"
```

### 统一发现页布局

```
┌─────────────────────────────────────────┐
│  网络音乐 ｜ Jamendo ｜ 热门推荐           │  ← 来源/模式 Tab
├─────────────────────────────────────────┤
│  （网络音乐模式 - 多维度筛选）             │
│  语种: [粤语] [国语] [英语] [日语] [韩语] │  ← 筛选行
│  纯音乐: [萨克斯] [笛子] [吉他] [钢琴]    │
│  年代: [70后] [80后] [90后] [00后]        │
│  情怀: [红歌] [草原] [民歌]               │
│  风格: [民谣] [摇滚] [古风] [说唱]        │
├─────────────────────────────────────────┤
│  🎵 歌曲名 - 歌手名           [网络] 03:45 │  ← 统一 SongRow
│  🎵 歌曲名 - 歌手名           [网络] 04:12 │
│  ...                                    │
├─────────────────────────────────────────┤
│  [▶ 播放全部]  [+ 加入队列]  ↻ 换一批     │  ← 统一 ActionBar
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│  （Jamendo 模式 - 独立音库）              │
│  风格: [流行] [摇滚] [电子] [爵士] [古典]  │  ← 风格筛选
├─────────────────────────────────────────┤
│  🎵 歌曲名 - 艺术家            [Jamendo]  │  ← 统一 SongRow + SourceBadge
│  ...                                    │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│  （热门推荐模式 - 聚合各源）               │
│  NAS 新歌 ｜ 网络热门 ｜ Jamendo 热门     │  ← 子分类
├─────────────────────────────────────────┤
│  🎵 歌曲名 - 歌手名           [NAS] 03:45 │  ← 统一 SongRow + SourceBadge
│  🎵 歌曲名 - 歌手名        [网络] 04:12 │
│  🎵 歌曲名 - 艺术家       [Jamendo] 03:30 │
│  ...                                    │
└─────────────────────────────────────────┘

注意：发现 Tab 只包含"网络音乐多维度筛选 + Jamendo + 热门推荐"，
不与 NAS 曲库的专辑/艺术家/歌曲/流派/年代 Tab 重复。
NAS 曲库浏览保留在各自的 Tab 中。
```

### 统一组件替换对照表

| 旧页面/组件 | 替换为 | 所在波次 |
|-----------|--------|---------|
| HomeScreen.HomeSongCard | UnifiedSongRow(MODE_CARD) | 波次 2 |
| HomeScreen.HomeAlbumCard | UnifiedAlbumCard | 波次 2 |
| HomeScreen.SectionHeader | 统一 SectionHeader | 波次 2 |
| HomeScreen.NowPlayingCard | 统一 CoverImage | 波次 2 |
| NetworkMusicContainer（整个页面） | 迁移到曲库子 Tab，页面可删除 | 波次 3 |
| SearchSubTab 独立 SongRow | UnifiedSongRow(MODE_ROW)（曲库搜索 Tab） | 波次 3 |
| BrowseSubTab 独立 SongGrid | UnifiedSongGrid（曲库发现 Tab） | 波次 3 |
| BrowseSubTab 操作栏 | 统一 ActionBar | 波次 3 |
| RadioSubTab | 曲库电台 Tab | 波次 3 |
| JamendoSubTab | 曲库发现 Tab 内 Jamendo 模式 | 波次 3 |
| LibraryScreen 歌曲/专辑/艺术家/流派/年代卡片 | 统一组件 | 波次 3 |
| NetdiskScreen 文件列表 | UnifiedSongRow + SourceBadge | 波次 3 |
| AlbumDetailScreen 列表 | UnifiedSongRow + UnifiedSongGrid | 波次 3 |
| ArtistDetailScreen 列表 | UnifiedSongRow + UnifiedSongGrid | 波次 3 |
| MineScreen 收藏/最近/歌单（多源聚合） | UnifiedSongRow + UnifiedPlaylistGrid | 波次 3 |
| QueueScreen 歌曲行 | UnifiedSongRow(MODE_COMPACT) | 波次 2 |
| PlaylistManagementScreen | UnifiedPlaylistGrid | 波次 3 |
| NetworkPlaylistDetailScreen | UnifiedSongRow + ActionBar | 波次 3 |

### 导航流程变化

```
调整前:
  首页 → 曲库（仅 NAS）→ 专辑/艺术家/歌曲详情 → 播放页
  首页 → 网络音乐（搜索/发现/天气/电台/Jamendo）→ 播放页  ← 独立 Tab
  首页 → 百度网盘 → 目录 → 播放页

调整后:
  首页 → 曲库（所有源统一）→ 搜索 Tab → 跨源结果 → 播放页
  首页 → 曲库 → 发现 Tab → 多维度筛选/热门推荐/Jamendo → 播放
  首页 → 曲库 → 电台 Tab → 搜索/浏览全球电台 → 播放
  首页 → 曲库 → 专辑/艺术家/歌曲/流派/年代 Tab（NAS 曲库浏览，不变）
  首页 → 播放队列 → 查看/管理当前播放列表
  首页 → 我的 → 查看收藏/最近播放/歌单
  首页 → 设置 → 配置服务器/均衡器/歌词等
  首页 → 百度网盘（目录浏览保留，歌曲列表统一组件）
  # 发现 Tab 不重复 NAS 曲库内容——它只做网络音乐多维度筛选 + Jamendo + 热门推荐
  # 无需"网络音乐"主 Tab——所有功能已在曲库中
  # 天气电台功能已移除
```

---

## 验证策略

### 测试决策
- **测试基础设施**: 已存在（Robolectric JUnit4）
- **自动化测试**: 实现后补充（UI 组件变化后补充测试）
- **测试框架**: Robolectric JUnit4
- **说明**: 主要验证方式为 Agent 执行的 QA 场景（编译 + 组件渲染验证）

### QA 策略
- **编译验证**: `./gradlew.bat assembleDebug` 必须通过
- **UI 组件验证**: 通过 Playwright 或截图对比验证组件渲染
- **搜索验证**: 验证搜索页能同时显示 NAS + 网络搜索结果
- **导航验证**: 验证新页面可以通过 D-Pad 导航到达

---

## 执行策略

### 并行执行波次

```
波次 1（基础建设 - 基础组件 + 数据层）:
├── 任务 1: 统一来源标识系统 SourceIdentifier
├── 任务 2: 统一 SongRow 组件
├── 任务 3: 统一 CoverImage 组件
├── 任务 4: 统一 SourceBadge 来源标签组件
├── 任务 5: 统一 AlbumCard / ArtistCard 组件
├── 任务 6: 统一 Loading/Error/Empty 状态组件
├── 任务 7: 跨源搜索聚合器 SearchAggregator

波次 2（核心页面, 最大并行）:
├── 任务 8: 曲库页 - 搜索 Tab 界面（SearchTab）
├── 任务 9: 曲库页 - 搜索 Tab ViewModel 集成
├── 任务 10: 曲库页 - 发现 Tab 界面（DiscoverTab）
├── 任务 11: 曲库页 - 发现 Tab ViewModel 集成
├── 任务 12: 统一 ActionBar 组件（播放全部/加入队列/收藏）
├── 任务 15: 统一 SectionHeader 组件
├── 任务 16: 统一 SongGrid 网格组件
├── 任务 17: 替换 HomeScreen 使用统一组件
├── 任务 18: 替换 NowPlayingScreen 使用统一组件
├── 任务 19: 替换 QueueScreen 使用统一组件

波次 3（迁移旧页面 + 移除网络音乐 Tab）:
├── 任务 20: 迁移网络音乐功能到曲库，移除 NetworkMusicContainer
├── 任务 21: 改造 LibraryScreen 集成新子 Tab
├── 任务 22: 替换 NetdiskScreen 使用统一组件
├── 任务 23: 替换 AlbumDetailScreen / ArtistDetailScreen
├── 任务 24: 替换 MineScreen 使用统一组件
├── 任务 25: 替换 PlaylistManagementScreen 使用统一组件
├── 任务 26: 替换 NetworkPlaylistDetailScreen
├── 任务 27: 导航结构调整 - 新增 Screen 枚举条目
├── 任务 28: AppRoot.kt 导航路由更新
├── 任务 29: 删除旧组件和重复代码

波次 FINAL（验证）:
├── 任务 F1: 编译通过 + lint 检查
├── 任务 F2: 功能完整性 QA
├── 任务 F3: 代码质量审查
├── 任务 F4: 范围一致性检查
-> 展示结果 -> 获取用户明确确认

关键路径: 任务 1 → 任务 7 → 任务 9 → 任务 11 → 任务 18 → 任务 25 → 26 → 27 → F1-F4 → 用户确认
```

### Agent 分派汇总

| 波次 | 任务数 | Agent 配置 |
|------|--------|-----------|
| 1 | 7 | T1-T2 → `deep`, T3-T6 → `visual-engineering`, T7 → `deep` |
| 2 | 12 | T8 → `visual-engineering`, T9 → `deep`, T10 → `visual-engineering`, T11 → `deep`, T12 → `visual-engineering`, T13 → `visual-engineering`, T14 → `visual-engineering`, T15 → `visual-engineering`, T16 → `visual-engineering`, T17-T19 → `deep` |
| 3 | 10 | T18-T24 → `deep`, T25 → `quick`, T26 → `deep`, T27 → `quick` |
| FINAL | 4 | F1 → `build`, F2 → `unspecified-high`, F3 → `unspecified-high`, F4 → `oracle` |

---

## TODOs

- [ ] 1. 统一来源标识系统 SourceIdentifier

  **执行内容**:
  - 创建 `data/model/SourceIdentifier.kt`，定义统一来源枚举/密封类，涵盖所有已知源：
    - `MusicSourceType` 枚举: NAS, NETWORK_MUSIC, BAIDU_PAN, RADIO, JAMENDO, WEATHER_RADIO
  - 每个类型含 `displayName`（中文显示名）、`icon`（图标字符）、`color`（主题色）
  - 添加 `Song.sourceType` 扩展属性，从 `isNetworkSong` + `networkSource` 自动推导
  - 更新 `BackendAdapter` 子类返回对应类型标识
  - 更新 `NetworkMusicService` 子类返回对应类型标识

  **禁止事项**:
  - 不要改动现有 `Song` 数据类字段（只用扩展属性）
  - 不要删除 `isNetworkSong` / `networkSource` 字段（向后兼容）

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 需要跨模块理解所有数据源的类型体系
  - **技能**: `[]`
  - **评估后省略的技能**: N/A

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 1 (与任务 2, 3, 4, 5, 6, 7)
  - **阻塞后续**: Tasks 8, 9, 10, 11, 12, 13
  - **被阻塞**: None

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/data/model/Song.kt:12-33` - 现有 Song 模型，需理解 isNetworkSong/networkSource 字段
  - `app/src/main/java/com/nasmusic/tv/data/model/NetworkSource.kt` - 现有网络来源枚举，需整合
  - `app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt` - NAS 后端接口
  - `app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicService.kt` - 网络音乐服务接口
  - `app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduNetdiskService.kt` - 百度网盘服务
  - `app/src/main/java/com/nasmusic/tv/data/model/RadioStation.kt:47` - isRadioSong() 函数

  **验收标准**:
  - [ ] `MusicSourceType` 枚举定义存在，涵盖所有已知源
  - [ ] `Song.sourceType` 扩展属性正确推导所有源类型
  - [ ] `BackendAdapter` 子类（Jellyfin/Navidrome/Subsonic）返回 `MusicSourceType.NAS`
  - [ ] `NetworkMusicService` 子类（Meting/Baidu）返回对应类型
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **QA 场景**:
  ```
  场景: 验证 Song.sourceType 对 NAS 歌曲返回正确
    工具: Bash (bun test)
    前置条件: 有 Song 对象，isNetworkSong=false
    步骤:
      1. 创建 Song(id="nas_123", title="test", isNetworkSong=false)
      2. 验证 song.sourceType == MusicSourceType.NAS
    预期结果: sourceType 正确推导为 NAS
    证据文件: .omo/evidence/task-1-source-type-nas.txt

  场景: 验证 Song.sourceType 对网络歌曲返回正确
    工具: Bash (bun test)
    前置条件: 有 Song 对象，isNetworkSong=true, networkSource="meting"
    步骤:
      1. 创建 Song(id="ntwk_meting_456", title="test", isNetworkSong=true, networkSource="meting")
      2. 验证 song.sourceType == MusicSourceType.NETWORK_MUSIC
    预期结果: sourceType 正确推导为 NETWORK_MUSIC
    证据文件: .omo/evidence/task-1-source-type-network.txt

  场景: 验证 Song.sourceType 对百度网盘歌曲返回正确
    工具: Bash (bun test)
    前置条件: 有 Song 对象，isNetworkSong=true, networkSource="baidu"
    步骤:
      1. 创建 Song(id="ntwk_baidu_789", title="test", isNetworkSong=true, networkSource="baidu")
      2. 验证 song.sourceType == MusicSourceType.BAIDU_PAN
    预期结果: sourceType 正确推导为 BAIDU_PAN
    证据文件: .omo/evidence/task-1-source-type-baidu.txt
  ```

  **需捕获的证据**:
  - [ ] task-1-source-type-nas.txt
  - [ ] task-1-source-type-network.txt
  - [ ] task-1-source-type-baidu.txt

  **提交**: 是
  - 提交信息: `feat(unified): add MusicSourceType unified source identifier`
  - 文件: `data/model/SourceIdentifier.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 2. 统一 SongRow 组件

  **执行内容**:
  - 创建 `ui/components/song/UnifiedSongRow.kt`
  - 统一所有页面的歌曲行展示模式：
    - 封面缩略图（使用统一 CoverImage 组件）
    - 歌曲名（1 行，溢出省略）
    - 歌手名（1 行，溢出省略）
    - 来源标签（SourceBadge 组件）
    - 时长（格式化 mm:ss）
    - 焦点态：放大 + 高亮边框
  - 支持操作按钮（使用显式参数控制显示）：
    - 播放 / 加入队列 / 收藏 / 添加到歌单
  - 支持多种布局模式（通过参数控制）：
    - `MODE_ROW` — 水平行（封面+文字+操作，用于列表）
    - `MODE_CARD` — 卡片（封面在上，文字在下，用于网格）
    - `MODE_COMPACT` — 紧凑行（无封面，仅文字，用于队列）

  **禁止事项**:
  - 不要内联业务逻辑（播放/收藏等操作通过回调传递）
  - 不要改动现有 Screen 的调用代码（留到后续替换任务）

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: TV UI 组件，需要 D-Pad 焦点系统 + 动画效果
  - **技能**: `[]`
  - **评估后省略的技能**: N/A

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 1 (与任务 1, 3, 4, 5, 6, 7)
  - **阻塞后续**: Tasks 17, 18, 19, 20, 21, 22, 23, 24, 25, 26
  - **被阻塞**: None (Task 3 的 CoverImage 和 Task 4 的 SourceBadge 是依赖，但可以先定义接口)

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/HomeScreen.kt:542-598` - HomeSongCard 现有实现
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/BrowseSubTab.kt:246-258` - SongRow 现有实现
  - `app/src/main/java/com/nasmusic/tv/ui/components/FocusableSurface.kt` - 统一焦点表面组件
  - `app/src/main/java/com/nasmusic/tv/ui/theme/` - 主题颜色定义

  **验收标准**:
  - [ ] `UnifiedSongRow` 组件支持 MODE_ROW / MODE_CARD / MODE_COMPACT 三种模式
  - [ ] 三种模式渲染无 crash
  - [ ] D-Pad 聚焦正确，有放大 + 高亮效果
  - [ ] 来源标签正确显示
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **QA 场景**:
  ```
  场景: 验证三种模式都能渲染
    工具: Bash (bun test with Robolectric)
    前置条件: 测试项目已配置
    步骤:
      1. 创建测试用例，分别渲染 MODE_ROW / MODE_CARD / MODE_COMPACT
      2. 验证不抛出异常
    预期结果: 三种模式均正常渲染
    证据文件: .omo/evidence/task-2-three-modes.txt

  场景: 验证焦点态效果
    工具: Bash (bun test)
    前置条件: 测试组件渲染
    步骤:
      1. 模拟焦点获取
      2. 验证 scale 变化和高亮色应用
    预期结果: 焦点态有放大和高亮效果
    证据文件: .omo/evidence/task-2-focus.txt
  ```

  **需捕获的证据**:
  - [ ] task-2-three-modes.txt
  - [ ] task-2-focus.txt

  **提交**: 是
  - 提交信息: `feat(unified): add UnifiedSongRow component with MODE_ROW/CARD/COMPACT`
  - 文件: `ui/components/song/UnifiedSongRow.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 3. 统一 CoverImage 组件

  **执行内容**:
  - 创建 `ui/components/common/CoverImage.kt`
  - 统一专辑封面/歌曲封面显示逻辑：
    - Coil 异步加载图片
    - 加载失败时显示音乐符号（♫）fallback
    - 加载中显示骨架屏/占位符
    - 支持自定义尺寸（`size` 参数）
    - 支持圆角（`roundedCorner` 参数）
    - 支持封面候选列表（coverCandidates 轮播功能移入组件）
  - 删掉 HomeScreen 中 HomeAlbumCard 和 HomeSongCard 的独立封面实现

  **禁止事项**:
  - 不要移除现有的 coverUrl 字段（用候选列表时优先使用第一个）
  - 不要改动 Coil 的全局配置

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: UI 组件，需要处理图片加载状态
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 1 (与任务 1, 2, 4, 5, 6, 7)
  - **阻塞后续**: Tasks 2, 5, 17, 18, 20
  - **被阻塞**: None

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/HomeScreen.kt:501-518` - HomeAlbumCard 封面实现
  - `app/src/main/java/com/nasmusic/tv/ui/screens/HomeScreen.kt:562-579` - HomeSongCard 封面实现
  - Coil Compose 文档: https://coil-kt.github.io/coil/compose/

  **验收标准**:
  - [ ] CoverImage 组件支持加载成功/失败/加载中三种状态
  - [ ] 加载失败时显示音乐符号 fallback
  - [ ] 支持自定义尺寸和圆角
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-3-cover-states.txt

  **提交**: 是
  - 提交信息: `feat(unified): add unified CoverImage composable with loading states`
  - 文件: `ui/components/common/CoverImage.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 4. 统一 SourceBadge 来源标签组件

  **执行内容**:
  - 创建 `ui/components/common/SourceBadge.kt`
  - 显示歌曲来源标签：
    - 不同来源不同颜色（NAS=蓝色, 网络音乐=绿色, 百度盘=橙色, 电台=紫色, Jamendo=粉色）
    - 紧凑型 Chip 样式
    - 使用 `MusicSourceType` 的 displayName 和 icon
  - 在 UnifiedSongRow 中集成

  **禁止事项**:
  - 不要显示没有来源的歌曲的标签（isNetworkSong=false 且无 networkSource 时隐藏）

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: UI 小组件，需要色彩搭配
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 1 (与任务 1, 2, 3, 5, 6, 7)
  - **阻塞后续**: Tasks 2, 17, 20
  - **被阻塞**: Task 1

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/data/model/SourceIdentifier.kt` - 统一来源标识（Task 1）
  - `app/src/main/java/com/nasmusic/tv/ui/theme/` - 主题颜色定义

  **验收标准**:
  - [ ] SourceBadge 支持所有 MusicSourceType 的显示
  - [ ] 各来源有不同颜色
  - [ ] 无来源时隐藏
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-4-badge-all-sources.txt

  **提交**: 是
  - 提交信息: `feat(unified): add SourceBadge composable for music source labeling`
  - 文件: `ui/components/common/SourceBadge.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 5. 统一 AlbumCard / ArtistCard 组件

  **执行内容**:
  - 创建 `ui/components/album/UnifiedAlbumCard.kt`
  - 统一专辑卡片：
    - 方形封面（使用统一 CoverImage）
    - 专辑名（1行，溢出省略）
    - 艺术家名（1行，溢出省略）
    - 焦点态：放大 + 高亮
    - 支持点击进入详情和播放两种操作
  - 创建 `ui/components/artist/UnifiedArtistCard.kt`
  - 统一艺术家卡片（类似布局，不显示艺术家名行）

  **禁止事项**:
  - 不要改动现有 Screen 的调用代码

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: UI 卡片组件
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 1 (与任务 1, 2, 3, 4, 6, 7)
  - **阻塞后续**: Tasks 17, 18, 20, 21, 23, 24
  - **被阻塞**: Task 3

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/HomeScreen.kt:480-537` - HomeAlbumCard 现有实现
  - `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt` - 曲库页的专辑/艺术家卡片

  **验收标准**:
  - [ ] UnifiedAlbumCard 渲染正常
  - [ ] UnifiedArtistCard 渲染正常
  - [ ] 焦点态有放大 + 高亮效果
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-5-album-card.txt
  - [ ] task-5-artist-card.txt

  **提交**: 是
  - 提交信息: `feat(unified): add unified AlbumCard and ArtistCard components`
  - 文件: `ui/components/album/UnifiedAlbumCard.kt`, `ui/components/artist/UnifiedArtistCard.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 6. 统一 Loading/Error/Empty 状态组件

  **执行内容**:
  - 创建 `ui/components/common/ListStateIndicators.kt`
  - 统一加载状态显示：
    - `LoadingIndicator` — 居中加载动画 + 可选的文字
    - `ErrorDisplay` — 错误图标 + 错误信息 + 重试按钮
    - `EmptyState` — 空状态图标 + 提示文字 + 可选操作按钮
  - 创建 `ui/components/common/SectionHeader.kt`（从 HomeScreen 提取）
  - 统一 SectionHeader 样式：标题 + 计数 + 可选"查看全部"

  **禁止事项**:
  - 不要引入新的动画库（使用现有的 Compose Animation）

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: UI 通用组件
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 1 (与任务 1, 2, 3, 4, 5, 7)
  - **阻塞后续**: Tasks 17, 18, 20, 21, 24
  - **被阻塞**: None

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/HomeScreen.kt:432-475` - 现有 SectionHeader 实现
  - `app/src/main/java/com/nasmusic/tv/data/model/UiState.kt` - 统一 UI 状态密封类

  **验收标准**:
  - [ ] LoadingIndicator 渲染正常
  - [ ] ErrorDisplay 含重试按钮
  - [ ] EmptyState 含可选的提示文字和操作按钮
  - [ ] SectionHeader 从 HomeScreen 提取到公共组件
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-6-states.txt

  **提交**: 是
  - 提交信息: `feat(unified): add unified Loading/Error/Empty state indicators and SectionHeader`
  - 文件: `ui/components/common/ListStateIndicators.kt`, `ui/components/common/SectionHeader.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 7. 跨源搜索聚合器 SearchAggregator

  **执行内容**:
  - 创建 `backend/SearchAggregator.kt`
  - 统一搜索接口，聚合所有数据源：
    - 接收搜索关键词 + 来源列表（可选过滤）
    - 并行搜索所有选定源
    - 结果合并去重（同源内按歌曲名去重，跨源相同歌曲名保留多条）
    - 结果带来源标签（使用 MusicSourceType）
    - 支持分页加载（每源各自分页，统一返回）
    - 超时保护（单个源搜索超时不影响其他源）
  - 搜索策略：
    - NAS 后端：`BackendAdapter.searchSongs()`
    - 网络音乐：`NetworkMusicManager.search()`
    - 百度网盘：`BaiduNetdiskService.search()`
    - Jamendo: Jamendo API 搜索
  - 返回 `SearchAggregatorResult` 数据类，包含：
    - `allResults: List<RankedSong>`（带来源标签和匹配分）
    - `sourceBreakdown: Map<MusicSourceType, Int>`（各源命中数）

  **禁止事项**:
  - 不要修改现有搜索接口签名
  - 不要添加网络请求逻辑（聚合器只协调现有服务）

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 需要理解所有数据源的搜索接口，设计并行协调逻辑
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 1 (与任务 1, 2, 3, 4, 5, 6)
  - **阻塞后续**: Tasks 9, 11
  - **被阻塞**: Task 1

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt:101` - searchSongs()
  - `app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt:61` - search()
  - `app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt:164` - search()
  - `app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduNetdiskService.kt:39` - search()
  - `app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt:1587` - 现有网络搜索逻辑

  **验收标准**:
  - [ ] SearchAggregator 并行搜索所有源
  - [ ] 搜索结果去重（相同 title+artist 合并）
  - [ ] 结果带来源标签
  - [ ] 单个源超时不影响其他源
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **QA 场景**:
  ```
  场景: 验证跨源搜索返回结果
    工具: Bash (bun test)
    前置条件: 测试 mock 各源搜索接口
    步骤:
      1. Mock NAS 搜索返回 2 条结果
      2. Mock 网络音乐搜索返回 3 条结果
      3. 调用 SearchAggregator.search("周杰伦")
      4. 验证返回 5 条结果，sourceBreakdown 包含 NAS=2, NETWORK_MUSIC=3
    预期结果: 正确聚合各源结果
    证据文件: .omo/evidence/task-7-aggregator.txt

  场景: 验证单源超时容错
    工具: Bash (bun test)
    前置条件: Mock 某个源搜索超时
    步骤:
      1. Mock NAS 搜索超时（抛出异常）
      2. Mock 网络音乐搜索返回 2 条
      3. 调用 search("test")
      4. 验证返回 2 条结果，NAS 源的错误不影响其他源
    预期结果: 超时源被跳过，其他源正常返回
    证据文件: .omo/evidence/task-7-timeout.txt
  ```

  **需捕获的证据**:
  - [ ] task-7-aggregator.txt
  - [ ] task-7-timeout.txt

  **提交**: 是
  - 提交信息: `feat(unified): add SearchAggregator for cross-source search`
  - 文件: `backend/SearchAggregator.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 8. 曲库页 - 搜索 Tab 界面 (SearchTab)

  **执行内容**:
  - 创建 `ui/screens/library/SearchTab.kt`
  - 作为曲库页的子 Tab，搜索 UI：
    - 顶部搜索输入框（自动聚焦，支持 D-Pad 输入）
    - 来源点亮行：NAS、网络音乐、百度盘、Jamendo（默认全部点亮）
      - 每个来源为可聚焦的 Chip 按钮，点亮态高亮（类似歌词来源选择器样式）
      - 点击切换点亮/熄灭
      - "全部点亮"按钮：一键全部点亮
      - 全部熄灭时搜索按钮置灰，提示"请至少点亮一个来源"
    - 搜索历史（最近搜索关键词，可点击）
    - 搜索结果区：
      - 统一 SongRow 组件（MODE_ROW）
      - 来源标签（SourceBadge）展示每首歌曲的来源
      - 分页加载（滚动到底部加载更多）
      - 结果不按来源分组，统一混合展示
    - 操作栏（搜索全部播放 / 全部加入队列）
    - 加载/错误/空状态使用统一组件

  **禁止事项**:
  - 不要使用来源分类 Tab（改为复选项，搜索前选择，不是搜索后过滤）
  - 不要包含搜索逻辑（只做 UI 布局，通过 ViewModel 回调通信）

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: TV UI 搜索页面，需要输入框 + 结果列表 + 分类 Tab
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 9, 10, 11, 12, 13, 14, 15, 16)
  - **阻塞后续**: None
  - **被阻塞**: Tasks 2, 3, 4, 6

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/SearchSubTab.kt` - 现有网络搜索 UI
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/NetworkMusicContainer.kt:244-263` - 现有搜索 Tab 集成
  - `app/src/main/java/com/nasmusic/tv/data/model/SearchHistoryItem.kt` - 搜索历史数据模型

  **验收标准**:
  - [ ] 搜索输入框可输入和提交
  - [ ] 搜索历史显示并可点击
  - [ ] 结果按来源分类 Tab 展示
  - [ ] 使用统一 SongRow 组件
  - [ ] 来源标签正确显示
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-8-search-ui.txt

  **提交**: 是
  - 提交信息: `feat(unified): add unified SearchScreen with source category tabs`
  - 文件: `ui/screens/SearchScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 9. 曲库页 - 搜索 Tab ViewModel 集成

  **执行内容**:
  - 在 `MainViewModel` 中新增搜索相关状态和方法：
    - `_librarySearchQuery: MutableStateFlow<String>`
    - `_librarySearchResults: MutableStateFlow<UiState<SearchAggregatorResult>>`
    - `_librarySearchSourceFilter: MutableStateFlow<MusicSourceType?>`
    - `fun librarySearch(query: String)` — 调用 SearchAggregator
    - `fun librarySearchFilterSource(type: MusicSourceType?)` — 按来源过滤
    - `fun librarySearchPlayAll()` — 播放全部搜索结果
    - `fun librarySearchAddAllToQueue()` — 全部加入队列
  - 集成搜索历史记录（复用 AppPreferences.recordSearch）
  - 曲库 Tab 切换时触发搜索（不需要独立导航）

  **禁止事项**:
  - 不要删除现有的 searchNetworkSongs 方法（旧页面仍用）
  - 不要改变现有 ViewModel 结构

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: ViewModel 集成，需要理解现有状态管理
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 8, 10, 11, 12, 13, 14, 15, 16)
  - **阻塞后续**: None
  - **被阻塞**: Tasks 7, 8

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt:1587-1612` - 现有网络搜索逻辑
  - `app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt:864` - navigateTo 方法
  - `app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt` - 导航路由
  - `app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt:863` - recordSearch

  **验收标准**:
  - [ ] unifiedSearch() 调用 SearchAggregator 并更新状态
  - [ ] 搜索结果可按来源过滤
  - [ ] 搜索历史被记录
  - [ ] navigateToUnifiedSearch() 可用
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-9-vm-integration.txt

  **提交**: 是 (groups with 8)
  - 提交信息: `feat(unified): integrate SearchScreen with MainViewModel and SearchAggregator`
  - 文件: `ui/viewmodel/MainViewModel.kt`, `ui/components/AppRoot.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 10. 曲库页 - 发现 Tab 界面 (DiscoverTab)

  **执行内容**:
  - 创建 `ui/screens/library/DiscoverTab.kt`
  - 作为曲库页的子 Tab，定位为"发现新内容"，不与 NAS 曲库 Tab 重复：
    - 顶部模式 Tab：网络音乐 / Jamendo / 热门推荐
    - **网络音乐模式**: 多维度筛选 + 歌曲列表（从 BrowseSubTab 迁移）
      - 筛选维度：语种、纯音乐、年代、情怀、风格
      - 筛选后调用 Meting API 搜索
    - **Jamendo 模式**: 热门榜 + 风格筛选（从 JamendoSubTab 迁移）
      - 未配置 Client ID 时显示引导卡
    - **热门推荐模式**: 聚合各源的热门/新歌
      - NAS 最近添加歌曲
      - 网络音乐热门搜索推荐
      - Jamendo 热门榜
      - 使用统一 SongRow + SourceBadge 标识来源
  - 使用统一组件：SongRow, SectionHeader, Loading/Error/Empty, ActionBar

  **禁止事项**:
  - 不要包含 NAS 曲库的专辑/艺术家/歌曲/流派/年代浏览（这些在各自的 Tab 中已有）
  - 不要混合不同模式的数据（模式切换时清除上一模式的结果）

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: TV UI 页面，需要 Tab 切换 + 多内容布局
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 8, 9, 11, 12, 13, 14, 15, 16)
  - **阻塞后续**: Tasks 21, 22, 26
  - **被阻塞**: Tasks 2, 3, 4, 5, 6

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt` - 现有 NAS 曲库 UI
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/BrowseSubTab.kt` - 现有网络多维度浏览
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/JamendoSubTab.kt` - Jamendo 浏览
  - `app/src/main/java/com/nasmusic/tv/ui/screens/netdisk/NetdiskScreen.kt` - 百度网盘浏览

  **验收标准**:
  - [ ] 模式 Tab 切换正常（网络音乐/Jamendo/热门推荐）
  - [ ] 网络音乐模式显示多维度筛选
  - [ ] Jamendo 模式显示热门榜
  - [ ] 热门推荐模式显示各源热门歌曲
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-10-discover-ui.txt

  **提交**: 是
  - 提交信息: `feat(unified): add unified DiscoverScreen with source selector tabs`
  - 文件: `ui/screens/DiscoverScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 11. 曲库页 - 发现 Tab ViewModel 集成

  **执行内容**:
  - 在 `MainViewModel` 中新增发现相关状态和方法：
    - `_libraryDiscoverMode: MutableStateFlow<DiscoverMode>` — 当前模式（网络音乐/Jamendo/热门推荐）
    - 各模式的数据状态：
      - 网络音乐模式：多维度筛选选项 + 搜索结果（复用 BrowseSubTab 逻辑）
      - Jamendo 模式：热门榜 + 风格筛选 + 搜索结果（复用 JamendoSubTab 逻辑）
      - 热门推荐模式：NAS 最近添加 + 网络热门 + Jamendo 热门
    - `fun librarySelectDiscoverMode(mode: DiscoverMode)` — 切换模式
    - `fun libraryDiscoverSearch(dimensions: List<Int>)` — 按维度筛选搜索
    - 集成现有数据加载逻辑（复用 BrowseSubTab 和 JamendoSubTab 的 ViewModel 逻辑）
  - 曲库 Tab 切换到发现时自动加载默认数据

  **禁止事项**:
  - 不要删除现有的 LibraryScreen 和 NetworkMusicContainer 的 ViewModel 逻辑（旧页面仍用）

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: ViewModel 集成，需要复用现有数据加载逻辑
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 8, 9, 10, 12, 13, 14, 15, 16)
  - **阻塞后续**: Tasks 21, 22, 26
  - **被阻塞**: Tasks 10

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt` - 现有 ViewModel 数据加载逻辑
  - `app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt:345` - selectNetworkSubTab
  - `app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt` - 导航路由

  **验收标准**:
  - [ ] selectDiscoverSource() 切换来源并加载对应数据
  - [ ] navigateToDiscover() 可用
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-11-discover-vm.txt

  **提交**: 是 (groups with 10)
  - 提交信息: `feat(unified): integrate DiscoverScreen with MainViewModel`
  - 文件: `ui/viewmodel/MainViewModel.kt`, `ui/components/AppRoot.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 12. 统一 ActionBar 组件

  **执行内容**:
  - 创建 `ui/components/common/ActionBar.kt`
  - 统一歌曲列表操作栏：
    - 播放全部按钮
    - 加入队列按钮
    - 收藏当前列表按钮
    - 歌曲计数显示
  - 支持自定义按钮（通过 slot/参数扩展）
  - 适配 TV 遥控器 D-Pad 导航

  **禁止事项**:
  - 不要包含业务逻辑（通过回调传递）

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: UI 组件
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 8, 9, 10, 11, 12, 13, 15, 16)
  - **阻塞后续**: Tasks 17, 18, 20, 24
  - **被阻塞**: None

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/HomeScreen.kt:355-388` - QuickActionRow
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/BrowseSubTab.kt:132-184` - 操作栏

  **验收标准**:
  - [ ] ActionBar 支持播放全部/加入队列/收藏按钮
  - [ ] D-Pad 导航正常
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-14-actionbar.txt

  **提交**: 是
  - 提交信息: `feat(unified): add unified ActionBar composable for song list operations`
  - 文件: `ui/components/common/ActionBar.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 13. 统一 SectionHeader 组件

  **执行内容**:
  - 从 HomeScreen 提取 SectionHeader 到公共组件
  - 创建 `ui/components/common/SectionHeader.kt`
  - 统一 SectionHeader 样式：标题 + 计数 + 可选"查看全部"
  - 支持 D-Pad 导航

  **禁止事项**:
  - 不要包含业务逻辑

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: UI 组件
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 8, 9, 10, 11, 12, 14, 15, 16)
  - **阻塞后续**: Tasks 17, 18, 20, 21, 24
  - **被阻塞**: None

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/HomeScreen.kt:432-475` - 现有 SectionHeader 实现

  **验收标准**:
  - [ ] SectionHeader 从 HomeScreen 提取到公共组件
  - [ ] 支持标题 + 计数 + 可选"查看全部"
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-13-section-header.txt

  **提交**: 是
  - 提交信息: `feat(unified): add unified SectionHeader composable`
  - 文件: `ui/components/common/SectionHeader.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 14. 统一焦点表面组件

  **执行内容**:
  - 创建 `ui/components/common/FocusableSurface.kt`
  - 统一 D-Pad 焦点交互：
    - 焦点获取/失去时的视觉反馈（放大 + 高亮边框）
    - 支持自定义焦点颜色
    - 支持点击回调
  - 替换各组件中重复的焦点处理代码

  **禁止事项**:
  - 不要包含业务逻辑

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: UI 基础组件，需要处理 D-Pad 焦点
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 8, 9, 10, 11, 12, 13, 15, 16)
  - **阻塞后续**: Tasks 17, 18, 20, 21, 24
  - **被阻塞**: None

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/components/FocusableSurface.kt` - 现有焦点组件

  **验收标准**:
  - [ ] FocusableSurface 支持焦点获取/失去视觉反馈
  - [ ] 支持自定义焦点颜色
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-14-focusable-surface.txt

  **提交**: 是
  - 提交信息: `feat(unified): add unified FocusableSurface composable for D-Pad focus`
  - 文件: `ui/components/common/FocusableSurface.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 15. 统一 SongGrid 网格组件

  **执行内容**:
  - 创建 `ui/components/song/UnifiedSongGrid.kt`
  - 统一歌曲网格展示：
    - 使用 LazyVerticalGrid 或 StaggeredGrid
    - 每个 item 使用 UnifiedSongRow（MODE_CARD）
    - 响应式列数（TV 横屏 4-5 列，手机横屏 3-4 列，手机竖屏 2 列）
    - 支持分页加载（滚动到底部触发加载更多）
    - 支持空状态/加载中/错误状态

  **禁止事项**:
  - 不要包含业务逻辑

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: UI 网格组件，需要响应式布局
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 8, 9, 10, 11, 12, 13, 14, 16)
  - **阻塞后续**: Tasks 17, 18, 20, 21, 24
  - **被阻塞**: Tasks 2, 6

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/BrowseSubTab.kt:110-265` - 现有网格实现
  - `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt` - 曲库网格

  **验收标准**:
  - [ ] SongGrid 渲染正常
  - [ ] 响应式列数正常工作
  - [ ] 分页加载正常
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-15-songgrid.txt

  **提交**: 是
  - 提交信息: `feat(unified): add unified SongGrid composable with responsive columns`
  - 文件: `ui/components/song/UnifiedSongGrid.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 16. 统一 PlaylistCard 组件

  **执行内容**:
  - 创建 `ui/components/playlist/UnifiedPlaylistCard.kt`
  - 统一歌单卡片展示：
    - 封面（使用统一 CoverImage，无封面时显示歌单图标）
    - 歌单名
    - 歌曲数量
    - 焦点态
  - 创建 `ui/components/playlist/UnifiedPlaylistGrid.kt`
  - 统一歌单网格展示

  **禁止事项**:
  - 不要包含歌单操作逻辑（通过回调传递）

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: UI 卡片组件
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 8, 9, 10, 11, 12, 13, 14, 15)
  - **阻塞后续**: Tasks 20, 21, 24
  - **被阻塞**: Tasks 3, 5

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/NetworkSubTabViews.kt` - 现有歌单卡片
  - `app/src/main/java/com/nasmusic/tv/ui/screens/PlaylistManagementScreen.kt` - 歌单管理
  - `app/src/main/java/com/nasmusic/tv/data/model/Playlist.kt` - 歌单数据模型

  **验收标准**:
  - [ ] UnifiedPlaylistCard 渲染正常
  - [ ] UnifiedPlaylistGrid 渲染正常
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-16-playlist.txt

  **提交**: 是
  - 提交信息: `feat(unified): add unified PlaylistCard and PlaylistGrid components`
  - 文件: `ui/components/playlist/UnifiedPlaylistCard.kt`, `ui/components/playlist/UnifiedPlaylistGrid.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 17. 替换 HomeScreen 使用统一组件

  **执行内容**:
  - 重构 `HomeScreen.kt`:
    - 替换 HomeSongCard → UnifiedSongRow(MODE_CARD)
    - 替换 HomeAlbumCard → UnifiedAlbumCard
    - 替换 SectionHeader → 统一 SectionHeader 组件
    - 替换 NowPlayingCard → 使用统一 CoverImage 组件
    - 替换 QuickActionRow → 统一 ActionBar（或保留为首页特有组件）
    - 替换 StatCard → 保留（首页特有统计卡片）
    - 删除 HomeSongCard、HomeAlbumCard、NowPlayingCard、SectionHeader 的本地实现

  **禁止事项**:
  - 不要改变首页的布局结构（WelcomeSection 等保留）
  - 不要改变首页的数据参数

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 需要理解现有 HomeScreen 所有组件并替换
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 18, 19)
  - **阻塞后续**: Task 29 (删除旧代码)
  - **被阻塞**: Tasks 2, 3, 4, 5, 6, 14, 15

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/HomeScreen.kt` - 完整文件
  - `app/src/main/java/com/nasmusic/tv/ui/components/song/UnifiedSongRow.kt` - 统一组件
  - `app/src/main/java/com/nasmusic/tv/ui/components/common/CoverImage.kt` - 统一封面
  - `app/src/main/java/com/nasmusic/tv/ui/components/common/SectionHeader.kt` - 统一标题

  **验收标准**:
  - [ ] 首页使用 UnifiedSongRow 替代 HomeSongCard
  - [ ] 首页使用 UnifiedAlbumCard 替代 HomeAlbumCard
  - [ ] 首页使用统一 SectionHeader
  - [ ] 删除 HomeSongCard/HomeAlbumCard 本地实现
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-17-home.txt

  **提交**: 是
  - 提交信息: `refactor(unified): replace HomeScreen with unified components`
  - 文件: `ui/screens/HomeScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 18. 替换 NowPlayingScreen 使用统一组件

  **执行内容**:
  - 重构 `NowPlayingScreen.kt`:
    - 替换封面显示 → 统一 CoverImage 组件
    - 替换歌曲信息显示 → 统一 SongRow 组件（如有）
    - 注意：NowPlayingScreen 是特殊页面，保留其独特布局（封面+歌词+控制条）
    - 只替换可复用的子组件（封面、歌曲信息行等）

  **禁止事项**:
  - 不要改变 NowPlayingScreen 的整体布局
  - 不要改动歌词和卡拉 OK 相关组件

  **推荐 Agent 配置**:
  - **类型**: `visual-engineering`
    - 原因: UI 页面重构
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 17, 19)
  - **阻塞后续**: Task 29
  - **被阻塞**: Tasks 3

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/NowPlayingScreen.kt`
  - `app/src/main/java/com/nasmusic/tv/ui/components/common/CoverImage.kt`

  **验收标准**:
  - [ ] NowPlayingScreen 使用统一 CoverImage
  - [ ] 布局不变
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-18-nowplaying.txt

  **提交**: 是 (groups with 17)
  - 提交信息: `refactor(unified): replace NowPlayingScreen cover with unified CoverImage`
  - 文件: `ui/screens/NowPlayingScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 19. 替换 QueueScreen 使用统一组件

  **执行内容**:
  - 重构 `QueueScreen.kt`:
    - 替换歌曲行 → 统一 SongRow 组件（MODE_COMPACT）
    - 替换操作按钮 → 统一 ActionBar 组件
    - 保留队列特有的拖拽排序功能

  **禁止事项**:
  - 不要改变队列的排序功能

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 需要理解队列特有的交互
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 2 (与任务 17, 18)
  - **阻塞后续**: Task 29
  - **被阻塞**: Tasks 2, 14

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt`

  **验收标准**:
  - [ ] QueueScreen 使用统一 SongRow
  - [ ] 排序功能保持正常
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-19-queue.txt

  **提交**: 是 (groups with 17, 18)
  - 提交信息: `refactor(unified): replace QueueScreen song rows with UnifiedSongRow`
  - 文件: `ui/screens/QueueScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 20. 迁移网络音乐功能到曲库，移除 NetworkMusicContainer

  **执行内容**:
  - 将网络音乐各子 Tab 的功能迁移到曲库页：
    - **搜索功能** → 曲库搜索 Tab（SearchTab，任务 8-9 已创建）
    - **浏览/发现功能** → 曲库发现 Tab（DiscoverTab，任务 10-11 已创建）
    - **天气电台** → 功能移除，后台代码保留（WeatherApi 供首页天气卡片使用）
    - **电台（RadioBrowser）** → 曲库新增电台 Tab 或放入发现 Tab 内
    - **Jamendo** → 曲库发现 Tab 内作为来源选择之一
  - 迁移数据加载逻辑到 `MainViewModel` 曲库相关方法
  - 迁移完成后，删除 `NetworkMusicContainer.kt` 及其所有子 Tab 文件
  - 更新 `AppRoot.kt`：移除 `Screen.Network` 路由
  - 更新 `Screen.kt`：移除 `Network` 枚举条目

  **禁止事项**:
  - 不要删除数据加载逻辑（只迁移位置）
  - 不要在主 Tab 中保留"网络音乐"入口

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 大范围迁移，需要理解所有子 Tab 的数据逻辑
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 3 (与任务 21, 22, 23, 24, 25, 26)
  - **阻塞后续**: Task 29
  - **被阻塞**: Tasks 2, 3, 4, 5, 14, 15, 16

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/NetworkMusicContainer.kt`
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/SearchSubTab.kt`
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/BrowseSubTab.kt`
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/NetworkSubTabViews.kt`
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/WeatherSubTab.kt`
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/RadioSubTab.kt`
  - `app/src/main/java/com/nasmusic/tv/ui/screens/network/JamendoSubTab.kt`
  - `app/src/main/java/com/nasmusic/tv/ui/screens/library/` - 曲库子 Tab 目录

  **验收标准**:
  - [ ] 网络音乐所有功能已迁移到曲库页
  - [ ] NetworkMusicContainer 及其子 Tab 文件已删除
  - [ ] Screen.Network 枚举条目已移除
  - [ ] 主 Tab 中无"网络音乐"入口
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-20-migrate-network.txt

  **提交**: 是
  - 提交信息: `refactor(unified): migrate network music to library, remove NetworkMusicContainer`
  - 文件: 多个文件
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 21. 改造 LibraryScreen 集成新子 Tab

  **执行内容**:
  - 重构 `LibraryScreen.kt`:
    - 新增 2 个子 Tab：搜索（SearchTab）、发现（DiscoverTab）
    - 保留原有 5 个子 Tab：专辑、艺术家、歌曲、流派、年代（收藏/最近/歌单已移入"我的"页面）
    - 将所有子 Tab 的歌曲列表 → 统一 SongGrid / SongRow
    - 替换专辑卡片 → 统一 AlbumCard
    - 替换艺术家卡片 → 统一 ArtistCard
    - 替换歌单列表 → 统一 PlaylistGrid
    - 替换操作栏 → 统一 ActionBar
    - 替换 SectionHeader → 统一组件
    - 创建 `ui/screens/library/` 目录，存放所有子 Tab 组件

  **禁止事项**:
  - 不要改变现有数据加载逻辑（只替换 UI 组件和新增 Tab）

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 曲库页重构，需要理解所有 Tab
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 3 (与任务 20, 22, 23, 24, 25, 26)
  - **阻塞后续**: Task 29
  - **被阻塞**: Tasks 2, 3, 5, 14, 15, 16

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`
  - `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryTab.kt` (if exists)

  **验收标准**:
  - [ ] LibraryScreen 使用统一 SongGrid
  - [ ] 专辑/艺术家使用统一卡片
  - [ ] 操作栏使用统一 ActionBar
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-21-library.txt

  **提交**: 是 (groups with 20)
  - 提交信息: `refactor(unified): replace LibraryScreen with unified components`
  - 文件: `ui/screens/LibraryScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 22. 替换 NetdiskScreen 使用统一组件

  **执行内容**:
  - 重构 `NetdiskScreen.kt`:
    - 替换歌曲文件列表 → 统一 SongRow 组件（MODE_ROW）
    - 替换目录浏览 → 保持目录树结构，但文件列表使用统一组件
    - 添加来源标签（SourceBadge 显示"百度网盘"）
    - 替换操作栏 → 统一 ActionBar（播放全部等）

  **禁止事项**:
  - 不要改变百度网盘的目录浏览结构和 OAuth 流程
  - 不要改动 BaiduAuthDialog

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 百度网盘页面重构
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 3 (与任务 20, 21, 23, 24, 25, 26)
  - **阻塞后续**: Task 29
  - **被阻塞**: Tasks 2, 3, 4, 14

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/netdisk/NetdiskScreen.kt`

  **验收标准**:
  - [ ] NetdiskScreen 文件列表使用统一 SongRow
  - [ ] 来源标签显示"百度网盘"
  - [ ] 目录浏览功能正常
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-22-netdisk.txt

  **提交**: 是 (groups with 20, 21)
  - 提交信息: `refactor(unified): replace NetdiskScreen with unified components`
  - 文件: `ui/screens/netdisk/NetdiskScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 23. 替换 AlbumDetailScreen / ArtistDetailScreen 使用统一组件

  **执行内容**:
  - 重构 `AlbumDetailScreen.kt`:
    - 替换歌曲列表 → 统一 SongGrid 或 SongRow
    - 替换专辑封面 → 统一 CoverImage
    - 替换操作栏 → 统一 ActionBar
  - 重构 `ArtistDetailScreen.kt`:
    - 替换歌曲列表 → 统一 SongGrid
    - 替换操作栏 → 统一 ActionBar

  **禁止事项**:
  - 不要改变详情页的布局和数据加载逻辑

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 详情页重构
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 3 (与任务 20, 21, 22, 24, 25, 26)
  - **阻塞后续**: Task 29
  - **被阻塞**: Tasks 2, 3, 5, 14, 15

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/AlbumDetailScreen.kt`
  - `app/src/main/java/com/nasmusic/tv/ui/screens/ArtistDetailScreen.kt`

  **验收标准**:
  - [ ] 详情页使用统一 SongRow/SongGrid
  - [ ] 详情页使用统一 CoverImage
  - [ ] 详情页使用统一 ActionBar
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-23-detail.txt

  **提交**: 是 (groups with 20, 21, 22)
  - 提交信息: `refactor(unified): replace AlbumDetail/ArtistDetail screens with unified components`
  - 文件: `ui/screens/AlbumDetailScreen.kt`, `ui/screens/ArtistDetailScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 24. 改造 MineScreen 聚合多源收藏/最近/歌单

  **执行内容**:
  - 重构 `MineScreen.kt`:
    - 新增 Tab 结构：收藏 / 最近播放 / 歌单
    - **收藏 Tab**: 聚合所有源的收藏歌曲
      - NAS 后端：`BackendAdapter.getFavorites()`
      - 网络音乐：`NetworkFavoriteItem`（本地持久化）
      - 百度网盘：本地收藏标记
      - 使用统一 SongRow + SourceBadge 标识来源
    - **最近播放 Tab**: 聚合所有源的播放记录
      - 使用 `AppPreferences.recentSongIds` + `getSongsByIds()` 回填
      - 使用统一 SongRow + SourceBadge
    - **歌单 Tab**: 聚合所有源歌单 + 本地歌单
      - NAS 后端：`BackendAdapter.getPlaylists()`
      - 本地：`LocalPlaylist`（本地持久化）
      - 使用统一 PlaylistGrid
    - 替换操作栏 → 统一 ActionBar

  **禁止事项**:
  - 不要删除现有数据源（保持向后兼容）
  - 收藏/歌单写入操作保持各源独立

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 多源数据聚合，需要理解各源的收藏/歌单接口
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 3 (与任务 20, 21, 22, 23, 25, 26)
  - **阻塞后续**: Task 29
  - **被阻塞**: Tasks 2, 3, 14, 15, 16

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/MineScreen.kt`
  - `app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt:144-146` - getFavorites()
  - `app/src/main/java/com/nasmusic/tv/data/model/NetworkFavoriteItem.kt` - 网络收藏
  - `app/src/main/java/com/nasmusic/tv/data/model/LocalPlaylist.kt` - 本地歌单
  - `app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt` - 最近播放记录

  **验收标准**:
  - [ ] 收藏 Tab 显示 NAS + 网络音乐 + 百度盘的收藏歌曲，带来源标签
  - [ ] 最近播放 Tab 显示所有源的播放记录
  - [ ] 歌单 Tab 显示 NAS 歌单 + 本地歌单
  - [ ] 统一 SongRow + SourceBadge + PlaylistGrid
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-24-mine.txt

  **提交**: 是 (groups with 20-23)
  - 提交信息: `feat(unified): aggregate multi-source favorites/recent/playlists in MineScreen`
  - 文件: `ui/screens/MineScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 25. 替换 PlaylistManagementScreen 使用统一组件

  **执行内容**:
  - 重构 `PlaylistManagementScreen.kt`:
    - 替换歌单列表 → 统一 PlaylistGrid
    - 替换歌曲列表 → 统一 SongRow
    - 替换操作栏 → 统一 ActionBar

  **禁止事项**:
  - 不要改变歌单管理的创建/删除功能

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 歌单管理页面重构
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 3 (与任务 20, 21, 22, 23, 24, 26)
  - **阻塞后续**: Task 29
  - **被阻塞**: Tasks 2, 14, 15, 16

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/PlaylistManagementScreen.kt`

  **验收标准**:
  - [ ] PlaylistManagementScreen 使用统一组件
  - [ ] 创建/删除歌单功能正常
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-25-playlist-mgmt.txt

  **提交**: 是 (groups with 20-24)
  - 提交信息: `refactor(unified): replace PlaylistManagementScreen with unified components`
  - 文件: `ui/screens/PlaylistManagementScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 26. 替换 NetworkPlaylistDetailScreen 使用统一组件

  **执行内容**:
  - 重构 `NetworkPlaylistDetailScreen.kt`:
    - 替换歌曲列表 → 统一 SongRow
    - 替换操作栏 → 统一 ActionBar
    - 替换封面 → 统一 CoverImage

  **禁止事项**:
  - 不要改变歌单详情的数据加载逻辑

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 网络歌单详情页重构
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 3 (与任务 20, 21, 22, 23, 24, 25)
  - **阻塞后续**: Task 29
  - **被阻塞**: Tasks 2, 3, 14

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/NetworkPlaylistDetailScreen.kt`

  **验收标准**:
  - [ ] NetworkPlaylistDetailScreen 使用统一组件
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-26-network-playlist.txt

  **提交**: 是 (groups with 20-25)
  - 提交信息: `refactor(unified): replace NetworkPlaylistDetailScreen with unified components`
  - 文件: `ui/screens/NetworkPlaylistDetailScreen.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 27. 曲库页 LibraryTab 枚举扩展

  **执行内容**:
  - 在 `LibraryTab` 枚举中新增 2 个子 Tab 条目：
    - `SEARCH` — 统一搜索
    - `DISCOVER` — 统一发现
  - 保留原有子 Tab 条目（ALBUMS、ARTISTS、SONGS、GENRES、YEARS）
  - 原有 FAVORITES、RECENT、PLAYLISTS 条目标记为已迁移至"我的"页面（保留枚举值避免编译错误）
  - 更新 `MainViewModel` 中曲库 Tab 切换逻辑

  **禁止事项**:
  - 不要删除现有 LibraryTab 条目
  - 不要改变 Screen 枚举（不新增顶级 Screen）

  **推荐 Agent 配置**:
  - **类型**: `quick`
    - 原因: 简单枚举扩展
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 3 (与任务 20, 21, 22, 23, 24, 25, 26, 28, 29)
  - **阻塞后续**: None
  - **被阻塞**: None

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryTab.kt` - 子 Tab 枚举定义

  **验收标准**:
  - [ ] LibraryTab 新增 3 个子 Tab 条目
  - [ ] 原有子 Tab 条目保留
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-27-library-tab.txt

  **提交**: 是
  - 提交信息: `feat(unified): extend LibraryTab with search/discover/weather tabs`
  - 文件: `ui/screens/LibraryTab.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 28. AppRoot.kt 曲库路由更新

  **执行内容**:
  - 更新 `AppRoot.kt`:
    - 曲库页面（LibraryScreen）的 when 分支保持不变
    - 确保曲库页的 Tab 切换逻辑正确路由到 SearchTab / DiscoverTab
    - 更新首页快捷操作中"曲库"按钮的导航逻辑（打开曲库页并默认切换到搜索 Tab）
    - 首页天气卡片（仅展示天气信息，天气电台功能已移除）

  **禁止事项**:
  - 不要新增 Screen 枚举路由
  - 不要删除现有路由

  **推荐 Agent 配置**:
  - **类型**: `deep`
    - 原因: 需要理解 AppRoot 的导航结构
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 3 (与任务 20, 21, 22, 23, 24, 25, 26, 27, 29)
  - **阻塞后续**: None
  - **被阻塞**: Tasks 8, 10, 12, 27

  **参考文件**:
  - `app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt`

  **验收标准**:
  - [ ] 曲库页 Tab 切换正常
  - [ ] 首页快捷操作打开曲库页并切换到对应 Tab
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-28-approot.txt

  **提交**: 是 (groups with 27)
  - 提交信息: `feat(unified): update AppRoot for library tab navigation`
  - 文件: `ui/components/AppRoot.kt`
  - 提交前检查: `./gradlew.bat assembleDebug`

- [ ] 29. 删除旧组件和重复代码

  **执行内容**:
  - 搜索并删除已被统一组件替代的旧代码：
    - 删除 `HomeScreen.kt` 中的 HomeSongCard、HomeAlbumCard、NowPlayingCard、SectionHeader 内部实现
    - 删除 NetworkSubTabViews 中重复的歌曲卡片代码
    - 删除各子 Tab 中独立的 SongRow 实现
    - 删除 LibraryScreen 中独立的歌曲/专辑/艺术家卡片
    - 确认所有页面使用统一组件后，删除旧组件文件
  - 注意：不要删除还在使用的代码

  **禁止事项**:
  - 不要删除仍然被引用的代码
  - 每次删除后运行 `assembleDebug` 确保不破坏编译

  **推荐 Agent 配置**:
  - **类型**: `quick`
    - 原因: 代码清理
  - **技能**: `[]`

  **并行化**:
  - **可并行**: 是
  - **并行分组**: 波次 3 (与任务 20, 21, 22, 23, 24, 25, 26, 27, 28)
  - **阻塞后续**: None
  - **被阻塞**: Tasks 17, 18, 19, 20, 21, 22, 23, 24, 25, 26

  **参考文件**:
  - 所有替换后的旧文件

  **验收标准**:
  - [ ] 所有旧组件代码被删除
  - [ ] `grep "SongRow\|SongCard"` 只返回 1 个定义（统一组件）
  - [ ] `./gradlew.bat assembleDebug` 编译通过

  **需捕获的证据**:
  - [ ] task-29-cleanup.txt

  **提交**: 是
  - 提交信息: `cleanup(unified): remove duplicate song/album card implementations`
  - 文件: 多个文件
  - 提交前检查: `./gradlew.bat assembleDebug`

---

## 最终验证波次

> 4 个审核 agent 并行运行。全部通过后，汇总结果给用户并获得明确确认。

- [ ] F1. **计划合规审计** — `oracle`
  读取计划。对每个"必须包含"验证实现是否到位（读文件、运行命令）。对每个"禁止事项"搜索代码库中禁止的模式。检查证据文件存在于 .omo/evidence/ 中。对比交付物与计划。
  输出: `必须包含 [N/N] | 禁止事项 [N/N] | 任务 [N/N] | 裁决: 通过/拒绝`

- [ ] F2. **功能完整性 QA** — `unspecified-high`
  从干净状态开始。执行所有任务的 QA 场景。测试跨任务集成。测试边界情况。
  输出: `场景 [N/N 通过] | 集成 [N/N] | 裁决`

- [ ] F3. **代码质量审查** — `unspecified-high`
  运行构建和测试命令。检查所有修改文件：类型抑制、空捕获、调试日志、注释代码、未使用导入。检查 AI 生成痕迹。
  输出: `构建 [通过/失败] | 编译 [通过/失败] | 文件 [N 干净/N 问题] | 裁决`

- [ ] F4. **范围一致性检查** — `deep`
  对每个任务：读"执行内容"，读实际 diff。验证 1:1 —— 计划中所有内容都已构建（无遗漏），没有超出计划的内容（无范围蔓延）。检查"禁止事项"合规性。
  输出: `任务 [N/N 合规] | 污染 [干净/N 问题] | 未说明 [干净/N 文件] | 裁决`

---

## 提交策略

遵循约定式提交前缀规范：
- `feat(unified)` — 新功能/组件
- `refactor(unified)` — 重构/替换
- `style(unified)` — UI 样式调整
- `cleanup(unified)` — 删除旧代码

每次提交前运行：`./gradlew.bat assembleDebug`

---

## 成功标准

### 验证命令
```bash
./gradlew.bat assembleDebug  # Expected: BUILD SUCCESSFUL
```

### 最终检查清单
- [ ] 所有"必须包含"已实现
- [ ] 所有"禁止事项"未违反
- [ ] `assembleDebug` 编译通过
- [ ] 无重复的 SongRow 实现
- [ ] 曲库页搜索 Tab 跨源搜索可用
- [ ] 曲库页发现 Tab 三种模式切换可用（网络音乐多维度/Jamendo/热门推荐）
- [ ] 曲库页电台 Tab 可正常使用
- [ ] "我的"页面聚合所有源的收藏、最近播放、歌单
- [ ] 曲库页无"收藏/最近/歌单"Tab（已移入"我的"）
- [ ] 主 Tab 为 5 个：首页 / 曲库 / 播放队列 / 我的 / 设置
- [ ] 网络音乐 Tab 已移除，所有功能在曲库中
- [ ] 百度网盘使用统一歌曲列表组件