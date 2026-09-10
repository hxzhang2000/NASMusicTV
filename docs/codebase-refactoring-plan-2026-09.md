# NASMusicTV 代码库重构修复方案

> 基于全代码库审阅产出的系统性重构方案，覆盖架构拆分、资源管理、安全性、性能优化等维度。
> 版本：v1.6 | 审阅基线：v2.27.0 (versionCode=121) | 最后更新：2026-09-10
>
> **v1.6 修订说明（N 系列人工复核与定案标注）**：对 v1.5 新增的 N 系列 5 项发现做逐条代码级复核（数据全部复核通过），并修正其中的错误关联与定案冲突，主要变更：
> - **N-1**：修复方案删除 `hiltViewModel()` 提法（与 R-8 定案不实施 Hilt 冲突），改为手动 DI 获取子 VM
> - **N-4**：关联修正 R-2→R-5（原计划的 R-2 是 SettingsScreen 拆分，PlayerManager 对应 R-5）；补注 R-5「HQ 编排保留 PlayerManager 防接口爆炸」定案，四阶段拆分降级为待所有者决策项
> - **N-5**：补注 R-10 定案（Jellyfin 保持原状**勿再作为待办启动**）；修正 R-6 关联描述（R-6 仅做 OkHttp 池化，注册机制系既有）；补记 Navidrome 已全量委托 SubsonicRestClient
> - **N-2**：弱化「违反 Kotlin 惯例」表述（Kotlin 无此硬性惯例），降级为可选规范项
> - 问题总览表 N 系列状态列同步修订；新增附录 H v1.6 修订记录
>
> **v1.5 修订说明（二次深度代码审阅——重构实施验证）**：对 v1.4 方案全部 R/F 项的实施结果做逐项代码级验证，并扫描新发现问题，主要变更：
> - **R-1~R-10 / F-1~F-7 实施验证**：逐项对照源码确认实施状态与质量，R-4 实际为 13 个子 Prefs（v1.4 记 12，LyricsPrefs/PlayerPrefs/ServerPrefs 独立文件 + DomainPrefs.kt 内 10 个类）；R-1 兼容转发层约 350 行膨胀问题识别
> - 新增「**二次审阅新发现（N 系列）**」章节：5 项新发现——N-1 MainViewModel 兼容转发层膨胀（P1）、N-2 DomainPrefs 多类单文件（P2）、N-3 AppRoot 123 处 collectAsState 上帝 Composable（P1）、N-4 PlayerManager 62.6KB 未拆分（P1）、N-5 BackendAdapter 巨型文件未拆（P2）
> - 附录 B 代码质量评分上调（架构设计 4→4.5、代码组织 3→3.5、资源管理 4→4.5、安全性 4→4.5、可维护性 3→3.5），反映重构实施成效
> - 附录 A 审阅覆盖文件清单更新为二次审阅实测数据
>
> **v1.4 修订说明（深度全库代码审阅）**：对 backend/player/ui/data/util/net/lyrics 全模块做了超越 R-1~R-10 覆盖面的深度审阅（并发、生命周期、日志安全、缓存上限、Compose 重组），主要变更：
> - 新增「**深度代码审阅发现（F 系列）**」章节：8 项新发现，全部带 file:line 证据。最重要两项：F-1 敏感凭证 URL 经 `AppLog.w/e` 泄露到 release logcat（AppLog 的 w/e 级别不剥离）；F-2 AppRoot 顶层 `progress/duration` 收集每秒驱动全树重组
> - R-7 表格修正：`getLyricsKugouBaseUrlSync`/`getLyricsNeteaseBaseUrlSync` 的真实调用点是 **主线程急切求值**（NasMusicApp:123-124 在 Application.onCreate、MainViewModel:138-142 在 VM 构造），并非「provider 回调线程」——R-7 表原表述低估了这两处
> - R-9 修复标记表补全：B15（StorageMonitor 反射防护）、H-4（端口冲突）、H-5（原子写盘）此前未登记
> - 同时确认了 11 个既有修复标记（M-1/2/5/7/9/14a-d/15/16、H-2/3/4/5、B4/B11/C-1）在代码中真实存在且有效；SearchAggregator 各源均有 withTimeoutOrNull；歌词/MV 持久缓存均有 LRU 上限——这些作为「已验证无问题项」记录，防止未来误报
>
> **实施进度（2026-09-09，分支 refactor/r1-viewmodel-split）**：
> - F-1 已完成（提交 9985bf9）：UrlSanitizer + 5 适配器 + BackendRegistry 脱敏，UrlSanitizerTest 8/8 绿
> - R-1 四步拆分完成（提交 59dc6b9/e03b4bc/adbcb37/9efa630）：13 个子 VM 全部落位，MainViewModel 5451→3186 行；剩余收尾（≤600 行目标、AppRoot 重组、手测回归）见「问题总览」状态列与 R-1 迁移检查清单
> - R-2 完成：9 个 Section 迁至 ui/screens/settings/（State/Actions 签名），主文件 2529→924 行
> - R-3 完成：NAS 浏览 5 Tab 迁至 library/browse/，主文件 1695→647 行，详情页复用现役实现
> - R-7 + F-3 完成：语言键 SharedPreferences 双写镜像、8 provider 键 @Volatile 内存镜像（ProviderMirrorTest 6/6）、Baidu 系 @WorkerThread、LyricsManager baseUrl 改 provider
> - R-6 完成：BackendRegistry 共享 ConnectionPool/Dispatcher，5 适配器 close() 移除 shutdown/evictAll
> - R-5 部分：SeparationMode 上提 player 层（typealias 兼容）+ VocalSeparationController 落位；HQ 编排保留 PlayerManager
> - R-9/F-2/F-4/F-5/F-6/F-7 完成；R-4 完成（12 子 Prefs 门面 + 全库调用点迁移，270 单测全绿）；R-10 定案：Subsonic 公共层完成，Jellyfin 域拆分经所有者确认保持原状不实施；R-8 定案：不实施（所有者确认——min SDK 22 + Kotlin 2.2.10 的 kapt/ksp 兼容性风险，手动 DI 运转良好）
>
> **v1.3 修订说明（开发前最终完善）**：本版目标是让方案**达到可直接开发的层次**，主要变更：
> - 新增「**W0 接口冻结清单**」章节：12 个子 ViewModel 的包路径/构造签名骨架、跨 VM 事件契约（sealed class 全量定义）、State/Actions 分组样板——动工前逐项勾选冻结，拆分期间以此仲裁
> - 新增「**开发实施规约**」章节：Git 分支/提交规范（对齐 `.opencode/rules.md`）、每步验证命令（Windows 环境实测路径）、进度标记规范、CHANGELOG/技术文档同步要求
> - 新增「**各 R 项 Definition of Done**」章节：每个 R 项的可验证完成标准 + 自动化测试边界（本项目 CI 只跑 assembleDebug，单测目录为 `app/src/test/`，Robolectric JUnit4）
> - R-1 各子 VM 增补包路径与文件名；R-4 增补子 Prefs 类命名与键归属映射表；R-6 增补分步实施顺序；R-7 增补第一/二类修复的落地代码骨架
> - 实施路线图增补「每步编译/提交节拍」要求（每拆一个域必须 assembleDebug 通过后单独提交，禁止跨域囤积）
>
> **v1.2 修订说明**：本版由第二轮独立审阅（对 v1.1 全部量化断言逐项对照源码复核）修订而成，主要变更：
> - R-7 调用点统计口径澄清：物理 `runBlocking(` 调用点为 **11 处**（v1.1 记 14 处系按逻辑读法合并统计；`grep -c runBlocking` 为 15 = 11 处调用 + 1 处 import + 2 处注释 + 1 处 `return@runBlocking` 标签）
> - R-1 新增「实施注意」：12 个子 ViewModel 颗粒度偏细，建议分批验证 + W0 冻结 Player/Search 交互契约
> - R-2/R-3 明确采用 data class 分组参数（State+Actions），避免拆分后参数列表爆炸
> - R-3 补充已核实结论：顶层 `AlbumDetailScreen`/`ArtistDetailScreen` 为现役实现（AppRoot.kt:881 调用），详情页拆分应复用迁移而非重新提取
> - R-8 明确为「可选项，非路线图必达项」；实施路线图补充 30% 工时缓冲建议
> - R-9 补记修复标记清单罗列不全（DaoliyuAdapter/FeiniuAdapter/BaiduHttpDataSourceFactory/MetingApiService/BilibiliMvService 等未逐一登记对应标记）
>
> **v1.1 修订说明**：本版由代码级逐项复核（文件行数/方法名/调用点 grep 验证）修订而成，主要变更：
> - 新增「关键架构决策」章节，明确 AppRoot 状态流去向，消除 R-1/R-2/R-3 方案间的矛盾
> - R-7 按真实方法名与调用点**完全重写**（v1.0 中 `getServerConfigSync()`、`getPlayModeSync()` 为虚构方法，实际不存在；`saveCloudDriveConfigBlocking()` 实为 `saveCloudDriveConfigSync()`）
> - R-2/R-3 示例代码改为匹配现有「AppRoot 参数+回调下发」架构，并修正 Composable 数量（25→13）
> - R-3 补充现有 `ui/screens/library/` 目录（DiscoverTab/JamendoTab/RadioTab/SearchTab）的处置说明
> - R-5 补 `switchToOriginal()` 迁移与 `AppPreferences.SeparationMode` 枚举归属说明
> - R-6 补充共享 Dispatcher 后 `close()` 禁止 shutdown 的关键陷阱
> - 新增 R-10（后端适配器巨型文件），补齐 v1.0 对 backend/impl 层的覆盖缺口

**状态图例**：
| 标记 | 含义 |
|------|------|
| 🔴 P0 | 严重影响可维护性/可测试性，建议优先实施 |
| 🟡 P1 | 中等影响，建议近期实施 |
| 🟢 P2 | 轻微改进，可择机实施 |
| ✅ | 已完成 |
| 🔶 | 实施中 |

---

## 目录

- [问题总览](#问题总览)
- [关键架构决策：AppRoot 状态流去向](#关键架构决策approot-状态流去向)
- [R-1 MainViewModel 拆分（🔴 P0）](#r-1-mainviewmodel-拆分p0)
- [R-2 SettingsScreen 拆分（🔴 P0）](#r-2-settingsscreen-拆分p0)
- [R-3 LibraryScreen 拆分（🔴 P0）](#r-3-libraryscreen-拆分p0)
- [R-4 AppPreferences 按领域拆分（🟡 P1）](#r-4-apppreferences-按领域拆分p1)
- [R-5 PlayerManager 人声分离逻辑提取（🟡 P1）](#r-5-playermanager-人声分离逻辑提取p1)
- [R-6 OkHttpClient 资源池化（🟡 P1）](#r-6-okhttpclient-资源池化p1)
- [R-7 runBlocking 使用风险修复（🟡 P1）](#r-7-runblocking-使用风险修复p1)
- [R-8 手动 DI 迁移到 Hilt（🟢 P2）](#r-8-手动-di-迁移到-hiltp2)
- [R-9 代码质量微调（🟢 P2）](#r-9-代码质量微调p2)
- [R-10 后端适配器巨型文件拆分（🟡 P1）](#r-10-后端适配器巨型文件拆分p1)
- [实施路线图](#实施路线图)
- [深度代码审阅发现（F 系列）](#深度代码审阅发现f-系列)
- [二次审阅新发现（N 系列）](#二次审阅新发现n-系列)
- [W0 接口冻结清单（开发前必读）](#w0-接口冻结清单开发前必读)
- [开发实施规约](#开发实施规约)
- [各 R 项 Definition of Done](#各-r-项-definition-of-done)

---

## 问题总览

| 编号 | 问题 | 严重级别 | 影响范围 | 预计工时 | 状态 |
|------|------|---------|---------|---------|------|
| R-1 | MainViewModel 5451行巨型文件 | 🔴 P0 | 可维护性/可测试性 | 5-8天（+联动改造AppRoot，见架构决策） | 🔶 四步拆分完成（13 子 VM 落位，5451→3186 行），MainViewModel ≤600 行目标未达 |
| R-2 | SettingsScreen 135.9KB 单文件 | 🔴 P0 | UI可维护性 | 2-3天 | ✅ 2026-09-09 完成（按 9 个实际侧栏分区拆分，主文件 2529→924 行） |
| R-3 | LibraryScreen 76.4KB 单文件 | 🔴 P0 | UI可维护性 | 2-3天 | ✅ 2026-09-09 完成（library/browse/ 5 文件，主文件 1695→647 行，详情页复用现役实现） |
| R-4 | AppPreferences 58KB 单类 | 🟡 P1 | 偏好管理可维护性 | 2-3天 | ✅ 2026-09-09 完成（13 领域子 Prefs 门面 + 全库调用点迁移，270 单测全绿；旧 API 保留为转发实现，未标 @Deprecated——门面与旧 API 并存，新代码走子域） |
| R-5 | PlayerManager 62.6KB 人声分离耦合 | 🟡 P1 | 播放器可维护性 | 1-2天 | 🔶 SeparationMode 上提 + VocalSeparationController 落位（DoD ①②），HQ 编排保留 PlayerManager，手测待设备 |
| R-6 | OkHttpClient 每适配器独立实例 | 🟡 P1 | 资源管理 | 1天 | ✅ 2026-09-09 完成（共享池注入 + 5 适配器 close() 移除 shutdown/evictAll），连续切后端手测待设备 |
| R-7 | AppPreferences 11 处 runBlocking 物理调用点 | 🟡 P1 | ANR风险 | 1-2天 | ✅ 2026-09-09 完成（语言双写镜像 + 8 provider 键 @Volatile 镜像 + Baidu 系 @WorkerThread + F-3），ProviderMirrorTest 6/6 绿 |
| R-8 | 手动DI vs Hilt/Dagger | 🟢 P2 | 依赖管理 | 3-5天 | ✅ 定案：不实施（所有者确认——min SDK 22 + Kotlin 2.2.10 的 kapt/ksp 兼容性风险，手动 DI 运转良好） |
| R-9 | 重复import/颜色硬编码/注释残留 | 🟢 P2 | 代码规范 | 0.5天 | ✅ 2026-09-09 完成（重复 import 已删；颜色硬编码/修复标记按计划保持现状） |
| R-10 | JellyfinAdapter 1215行 / NavidromeAdapter 932行 | 🟡 P1 | 后端层可维护性 | 3-5天 | ✅ 定案：Subsonic 公共层完成（SubsonicRestClient，Navidrome 全量委托/Subsonic 部分）；Jellyfin 域拆分经所有者确认**保持原状不实施**（实例状态耦合深，拆分回归风险高于收益）——勿再作为待办启动 |
| F-1 | 敏感凭证 URL 泄露到 release 日志 | 🔴 P0 | 安全 | 0.5天 | ✅ 2026-09-09 完成 |
| N-1 | MainViewModel 兼容转发层膨胀 | 🟡 P1 | 可维护性 | 1-2天 | 待实施（建议 AppRoot 直接引用子 VM，消除 ~350 行转发层；v1.6 注：须经手动 DI 获取子 VM，不引入 Hilt——R-8 定案） |
| N-2 | DomainPrefs 多类单文件 | 🟢 P2 | 代码规范 | 0.5天 | 待实施（10 个子 Prefs 类挤在 DomainPrefs.kt 179 行，建议按领域拆独立文件） |
| N-3 | AppRoot 123 处 collectAsState 上帝 Composable | 🟡 P1 | UI性能/可维护性 | 3-5天 | 待实施（F-2 仅解决 progress/duration 每秒重组，整体架构未变；建议按域分组提取子 Composable） |
| N-4 | PlayerManager 62.6KB 未拆分 | 🟡 P1 | 播放器可维护性 | 3-5天 | 🔶 v1.6 降级为待所有者决策（70 方法涵盖播放控制/队列/人声分离编排/音高变速；与 R-5「HQ 编排保留 PlayerManager 防接口爆炸」定案同类回归风险，未经确认不得启动） |
| N-5 | BackendAdapter 巨型文件未拆 | 🟢 P2 | 后端层可维护性 | 3-5天 | ✅ v1.6 归入 R-10 定案关闭（Jellyfin 保持原状勿再启动；Navidrome 已全量委托 SubsonicRestClient，属已定案关闭项非待办） |

---

## 关键架构决策：AppRoot 状态流去向

> **本章节必须在 R-1/R-2/R-3 动工前确认。** v1.0 的方案样例在 R-1 推荐各 Screen 直接 `viewModel()` 注入，而 R-2 的 Section 又签名为主 ViewModel 参数，两者矛盾且均与现状不符。本版统一决策如下。

### 现状（已核实）

`AppRoot.kt`（1079 行，**123 处 `collectAsState`**）集中订阅 MainViewModel 的全部 StateFlow，再以「参数 + 回调」的形式下发给各 Screen：

```kotlin
// AppRoot.kt:497 / 713 —— 实际签名（状态上提，Screen 无状态）
LibraryScreen(albums = albums, songs = songs, onPlayAlbum = viewModel::playAlbum, ...)
SettingsScreen(settings = settings, onToggleDarkTheme = viewModel::toggleDarkTheme, ...)
```

各 Screen（LibraryScreen:140、SettingsScreen:88）**不持有任何 ViewModel 引用**，是纯函数式 Composable。

### 决策：保持状态上提架构，分两层演进

| 阶段 | 做法 | 理由 |
|------|------|------|
| **R-1/R-2/R-3 实施期** | AppRoot 仍集中订阅，但改为按域分组订阅各子 ViewModel 的 StateFlow；Screen 仍走「参数+回调」 | 改动面最小、D-Pad 焦点与重组行为不变、每步可独立回归 |
| **后续（可选，R-8 之后）** | 视情况评估各 Screen 局部改为 `viewModel()` 注入 | 仅当 AppRoot 分组后仍臃肿时再做，不与本轮重构耦合 |

**推论**：
- 拆分 MainViewModel 必然联动改写 AppRoot 的 123 处 `collectAsState` 与所有 `viewModel::xxx` 引用——**这部分工作量已计入 R-1 工时**。
- R-2/R-3 拆出的 Section/Tab Composable 保持「参数+回调」签名（见各节修订后示例），**不引入 ViewModel 注入**。
- v1.0 中「方案B：UI层直接引用多个ViewModel」降级为远期选项，不再作为本轮推荐。

---

## R-1 MainViewModel 拆分（🔴 P0）

### 1.1 现状分析

**文件**：[`MainViewModel.kt`](app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt:122)

**问题量化**（已核实）：
- 总行数：5451 行（wc -l 实测）
- 函数总数：241（v1.0 记「公开方法 240+」，实测 `fun` 出现 241 次）
- 实现接口：`RemoteCallbacks`
- 直接依赖组件：`PlayerManager`、`BackendRegistry`、`LyricsManager`、`RemoteControlServer`、`WeatherApi`、`WeatherRadioManager`（**可空延迟创建，见下**）、`MvSearchManager`、`SearchAggregator`、`AppPreferences`、`SongDownloadManager`、`AutoDownloadController`、`LocalMusicRepository`、`StorageMonitor`、`ModelDownloadManager`、`ExportCoordinator`、`BaiduOAuthClient`、`BaiduPanApi`、`BaiduFileIndexCache`

**注意事项（v1.0 遗漏）**：
- `weatherRadioManager` 是 `var ...: WeatherRadioManager? = null`（MainViewModel.kt:159），**按需延迟创建**——无 NAS 连接时不实例化（天气电台允许在无后端时工作）。拆分 WeatherRadioViewModel 时必须保留此可空语义，不可改为构造期固定依赖。
- 百度组件通过属性委托延迟访问：`private val baiduOAuth get() = nasMusicApp.baiduOAuthClient`（MainViewModel.kt:4847-4849），拆 NetworkMusicViewModel 时同样保留。
- AppRoot.kt 有 123 处 `collectAsState` 引用本类状态，拆分后需同步重组（见架构决策章节）。

**职责域分析**（按方法分组统计，v1.0 原表保留）：

| 职责域 | 方法数（约） | 行数（约） | StateFlow数 |
|--------|------------|-----------|------------|
| 导航与屏幕状态 | 5 | 120 | 3 |
| 服务器连接/断开 | 8 | 200 | 5 |
| 首页仪表盘 | 3 | 150 | 1 |
| 曲库浏览（专辑/艺术家/年份/流派） | 20 | 800 | 10+ |
| 曲库搜索（NAS+网络+聚合） | 15 | 600 | 8 |
| 网络音乐搜索/播放/收藏 | 12 | 500 | 6 |
| 百度网盘索引/搜索 | 8 | 300 | 4 |
| 天气电台 | 8 | 300 | 7 |
| 播放控制（播放/暂停/上下曲/模式） | 15 | 500 | 0（委托PlayerManager） |
| 人声分离/K歌/MTV模式 | 20 | 700 | 8 |
| 下载管理 | 8 | 200 | 3 |
| 本地歌单CRUD | 8 | 200 | 2 |
| 播放记录/统计 | 6 | 200 | 3 |
| 备份导入/导出 | 7 | 200 | 2 |
| 遥控服务器 | 3 | 50 | 1 |
| MV搜索 | 12 | 400 | 4 |
| 封面滤镜 | 3 | 50 | 3 |
| 网络状态监听 | 2 | 50 | 0 |

### 1.2 拆分方案

#### 目标架构

```
ui/viewmodel/                    — 全部与 MainViewModel 同包（不建子包）
MainViewModel.kt（精简协调者，预计 ~500 行，v1.0 估 300 行偏乐观：
             仅 RemoteCallbacks 委托 + 18 个职责域协调代码即超过 300 行）
├── NavigationViewModel.kt        — 导航与屏幕状态
├── ServerViewModel.kt           — 服务器连接/断开/版本
├── LibraryViewModel.kt          — 曲库浏览/专辑/艺术家/年份/流派
├── SearchViewModel.kt           — NAS搜索+网络搜索+聚合搜索
├── NetworkMusicViewModel.kt     — 网络音乐搜索/播放/收藏/百度网盘
├── WeatherRadioViewModel.kt     — 天气电台全部逻辑（保留 manager 可空语义）
├── PlayerViewModel.kt           — 播放控制/队列/模式
├── VocalSeparationViewModel.kt  — 人声分离/K歌/MTV模式
├── DownloadViewModel.kt         — 下载管理/模型下载
├── PlaylistViewModel.kt         — 本地歌单CRUD
├── PlayHistoryViewModel.kt      — 播放记录/统计
├── BackupViewModel.kt           — 备份导入/导出
├── MvSearchViewModel.kt         — MV搜索/候选切换
└── ViewModelEvents.kt           — 跨域事件契约（W0 冻结，见专门章节）
```

#### 拆分步骤

**第一步：提取独立职责域（低耦合，优先提取）**

1. **WeatherRadioViewModel**（~300行）
   - 迁移：`fetchWeather()`、`switchWeatherMood()`、`playWeatherRadioAll()`、`buildWeatherRadioDeduped()`、`loadRadioForDefaultMood()` 及所有 `_weather*` StateFlow
   - 依赖：`WeatherApi`、`WeatherRadioManager`（**可空延迟创建**）、`NetworkMusicManager`、`PlayerManager`（仅播放）
   - MainViewModel保留：`weatherData`/`weatherRadioQueue` 的只读 StateFlow 引用（通过 `combine` 转发）

2. **BackupViewModel**（~200行）
   - 迁移：`refreshBackupFiles()`、`exportBackup()`、`importBackup()`、`restoreBackupFromJson()`、`deleteBackup()`、`consumeBackupMessage()` 及 `_backupMessage` StateFlow
   - 依赖：`AppPreferences`、`Context`（文件操作）
   - 几乎无外部依赖，最容易提取

3. **PlaylistViewModel**（~200行）
   - 迁移：`createLocalPlaylist()`、`renameLocalPlaylist()`、`deleteLocalPlaylist()`、`addSongToPlaylist()`、`removeSongFromPlaylist()`、`playLocalPlaylist()` 及 `_localPlaylists` StateFlow
   - 依赖：`AppPreferences`
   - 几乎无外部依赖

**第二步：提取中等耦合职责域**

4. **DownloadViewModel**（~200行）
   - 迁移：`refreshDownloadStats()`、`refreshModelStatus()`、`downloadModel()`、`deleteModel()`、`toggleSeparationMode()`（下载部分）及 `_downloadStats`/`_modelStatus` StateFlow
   - 依赖：`SongDownloadManager`、`ModelDownloadManager`、`AutoDownloadController`

5. **MvSearchViewModel**（~400行）
   - 迁移：`triggerMvSearch()`、`enterMvMode()`、`exitMvMode()`、`onMvPlaybackError()`、`onMvPlaybackEnded()`、`onMvPrevious()`、`onMvNext()`、`onSwitchOrResearch()`、`onSearchBilibili()` 及 `_mvState`/`_mvMessage` StateFlow
   - 依赖：`MvSearchManager`、`PlayerManager`（MV模式控制）

6. **VocalSeparationViewModel**（~700行）
   - 迁移：`toggleVocalRemoval()`、`setSeparationMode()`、`applySeparationMode()`、`loadPitchSpeedFromPrefs()`、`setPitchSemitones()`、`setPlaybackSpeed()`、`resetPitch()`、`resetSpeed()`、`enterKaraoke()`、`exitKaraoke()` 及所有分离/K歌相关 StateFlow
   - 依赖：`PlayerManager`（人声分离控制）、`AppPreferences`（模式持久化）

**第三步：提取高耦合职责域（需仔细处理交互）**

7. **SearchViewModel**（~600行）
   - 迁移：`searchSongsOnServer()`、`clearSearch()`、`searchNetworkSongs()`、`shuffleNetworkSearch()`、`clearNetworkSearch()`、`addAllSearchResultsToQueue()`、`toggleSearchSource()`、`enableAllSearchSources()` 及所有 `_search*`/`_networkSearch*` StateFlow
   - 依赖：`BackendRegistry`、`SearchAggregator`、`NetworkMusicManager`、`PlayerManager`（添加到队列）
   - **交互点**：搜索结果添加到播放队列需回调 MainViewModel 或通过 SharedFlow 通信

8. **LibraryViewModel**（~800行）
   - 迁移：`loadLibrary()`、`loadSongsFirstPage()`、`loadSongsNextPage()`、`loadAllSongsBackground()`、`loadRandomSongs()`、`loadArtists()`、`loadYears()`、`loadAlbumSongs()`、`loadArtistSongs()`、`openAlbumDetail()`、`openArtistDetail()`、`refreshLibrary()`、`updateMergedData()` 及所有 `_albums`/`_artists`/`_songs` StateFlow
   - 依赖：`BackendRegistry`、`LocalMusicRepository`、`BaiduFileIndexCache`
   - **交互点**：专辑详情/艺术家详情导航需回调

9. **NetworkMusicViewModel**（~500行）
   - 迁移：`playNetworkSong()`、`playNetworkBatch()`、`toggleNetworkFavorite()`、`isNetworkFavorite()` 及百度网盘相关逻辑
   - 依赖：`NetworkMusicManager`、`BaiduOAuthClient`、`BaiduPanApi`、`PlayerManager`

**第四步：精简 MainViewModel**

10. **PlayerViewModel**（~500行）
    - 迁移：`playSong()`、`playQueue()`、`playPause()`、`next()`、`previous()`、`seekTo()`、`togglePlayMode()`、`setPlayMode()`、`addSongToQueue()`、`removeFromQueue()`、`moveQueueItem()`、`resolveAndPlayCurrentSong()`、`resolveStreamUrl()`、`recordPlay()`、`recordPlayEvent()`
    - 依赖：`PlayerManager`（核心委托）
    - 注意：PlayerManager 的 playMode 是**方法参数**（`next(playMode)`、`peekNextSong(playMode)` 等，PlayerManager.kt:896-1007），由 ViewModel 计算后传入——迁移时保持该边界，勿让 PlayerManager 直接读 AppPreferences。

11. **ServerViewModel**（~200行）
    - 迁移：`connectToServer()`、`disconnect()`、`connectToSavedServer()`、`refreshApiVersions()`、`dismissConnectPrompt()` 及连接相关 StateFlow

12. **MainViewModel 精简为协调者**
    - 保留：Application引用、ViewModel间协调、`RemoteCallbacks`实现（委托各子ViewModel）
    - 通过 `val weatherViewModel: WeatherRadioViewModel` 等属性暴露子ViewModel给 AppRoot

#### 通信模式（v1.1 修订：匹配现有状态上提架构）

> **v1.2 实施注意（颗粒度与分批策略）**：
> 1. **12 个子 ViewModel 一次拆满是本方案最大的执行风险**。子 VM 之间的 SharedFlow 交互点（搜索加队列、详情页导航等）会显著增加联调复杂度。强烈建议按第一、二步先提取 6 个低/中耦合域（WeatherRadio/Backup/Playlist/Download/MvSearch/VocalSeparation）验证通信模式，跑稳后再动第三、四步。
> 2. **第三/四步（Search/Library/Player）是最容易翻车的部分**：谁持有播放队列、谁发事件、谁消费，边界模糊。建议在 W0 冻结接口约定时，把 **Player 与 Search 的交互契约**（事件类型清单 + 数据流向图）一并成文，作为拆分期间的仲裁依据。

子ViewModel 之间需要联动时（如搜索结果加入播放队列），用 SharedFlow 事件：

```kotlin
class SearchViewModel : ViewModel() {
    private val _events = MutableSharedFlow<SearchEvent>()
    val events: SharedFlow<SearchEvent> = _events.asSharedFlow()   // v1.0 笔误 SharedSharedFlow 已更正

    fun addToQueue(song: Song) {
        _events.tryEmit(SearchEvent.AddToQueue(song))
    }
}

// MainViewModel 订阅子ViewModel事件
init {
    viewModelScope.launch {
        searchViewModel.events.collect { event ->
            when (event) {
                is SearchEvent.AddToQueue -> playerViewModel.addSongToQueue(event.song)
            }
        }
    }
}
```

**AppRoot 侧改动**（对应「关键架构决策」）：AppRoot 改为按域分组订阅各子 ViewModel，Screen 签名保持「参数+回调」不变，例如：

```kotlin
@Composable
fun LibraryScreen(
    // 状态仍由 AppRoot 上提后下发，签名不变，仅数据来源从单一 viewModel 拆为多个
    albums: List<Album>,
    songs: List<Song>,
    onPlayAlbum: (Album) -> Unit,
    // ...
)
```

#### 迁移检查清单

- [x] 每个拆出的ViewModel独立编译通过 ✅ 2026-09-09（四步均 assembleDebug 通过）
- [ ] AppRoot 的 123 处 `collectAsState` 与全部 `viewModel::xxx` 引用已按域重组，无遗漏（迁移期过渡访问器形态，见 W0「AppRoot 重组约定」）
- [ ] 原有UI功能不退化（逐Screen验证）（待 TV 手测回归）
- [x] StateFlow生命周期正确（ViewModel作用域内）✅ 2026-09-09（子 VM 均为 AndroidViewModel，随 MainViewModel 持有）
- [x] RemoteCallbacks接口实现正确委托 ✅ 2026-09-09（仍由 MainViewModel 收口，经转发调用 PlayerViewModel/PlayerManager）
- [x] WeatherRadioManager 可空延迟创建语义保留（无 NAS 时不实例化）✅ 2026-09-09
- [ ] 内存无泄漏（onCleared正确释放资源）（待手测）
- [x] 并发安全（共享状态访问同步）✅ 2026-09-09（逐行搬迁，共享状态仍经 PlayerManager/AppPreferences 等单例）

---

## R-2 SettingsScreen 拆分（🔴 P0）

### 2.1 现状分析

**文件**：[`SettingsScreen.kt`](app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:88)

**问题量化**（已核实，v1.1 修正）：
- 文件大小：135.9KB（实测 139,186 字节）；共 2529 行
- **Composable 函数数：13**（v1.0 记 25，实测 13 —— 1 个主函数 + 12 个私有辅助，@Composable 注解位于 87/2167/2178/2190/2209/2251/2282/2313/2352/2432/2453/2476/2498 行）
- 主函数 `SettingsScreen` 从第 88 行到第 2166 行（~2078 行）
- 涵盖：服务器配置、播放设置、歌词设置、网络音乐设置、百度网盘设置、Jamendo设置、MV搜索设置、本地音乐设置、下载设置、导出设置、天气电台设置、K歌设置、频谱/均衡器设置、备份恢复、关于页面
- 主函数签名为「参数+回调」式（`settings: AppSettings, onToggleDarkTheme: (Boolean) -> Unit, ...`），**不持有 ViewModel**（见架构决策章节）

### 2.2 拆分方案

#### 目标结构

```
ui/screens/settings/
├── SettingsScreen.kt           — 主入口，Section 容器（~200行）
├── ServerSettingsSection.kt    — 服务器连接配置
├── PlayerSettingsSection.kt    — 播放模式/自动播放/人声分离
├── LyricsSettingsSection.kt    — 歌词偏移/缓存/源配置
├── NetworkMusicSection.kt      — 网络音乐源/Meting API
├── BaiduPanSettingsSection.kt  — 百度网盘OAuth/文件索引
├── JamendoSettingsSection.kt   — Jamendo Client ID
├── MvSettingsSection.kt        — MV搜索API配置
├── LocalMusicSection.kt        — 本地音乐扫描/存储
├── DownloadSettingsSection.kt  — 下载路径/自动下载/模型管理
├── ExportSettingsSection.kt    — USB/SD导出配置
├── WeatherRadioSection.kt      — 天气API/电台配置
├── KaraokeSettingsSection.kt   — K歌/人声分离模式
├── VisualizerSection.kt        — 频谱主题/均衡器
├── BackupSection.kt            — 备份导入/导出
└── AboutSection.kt             — 版本信息/开源许可
```

#### 拆分步骤

1. **创建 `ui/screens/settings/` 目录**

2. **提取各Section为独立Composable文件**，每个文件包含：
   - 一个公开的 `@Composable fun XxxSection(...)` 函数，**保持「参数+回调」签名**（与现有架构一致，v1.0 示例的 `viewModel: MainViewModel` 参数已废弃）
   - 该Section内部使用的私有辅助Composable

3. **SettingsScreen 主文件精简为容器**（v1.1 修订示例）：
```kotlin
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onToggleDarkTheme: (Boolean) -> Unit,
    onToggleAnimations: (Boolean) -> Unit,
    // ... 回调参数由 AppRoot 继续下发，数据来源在 R-1 完成后
    //     可改为按域分组的多个 stateholder 参数
) {
    LazyColumn {
        item { ServerSettingsSection(...) }
        item { PlayerSettingsSection(...) }
        item { LyricsSettingsSection(...) }
        // ...
    }
}
```

4. **共享组件提取到 `ui/components/`**（v1.1 修订：现状核实）：
   - `SectionTitle`（SettingsScreen.kt:2168）、`SubSectionTitle`（:2179）、`SettingSwitch`（:2210）、`SettingActionButton`（:2314）、`AdjustButton`（:2477）、`AboutRow`（:2433）、`InfoRow`（:2499）→ 全部为 SettingsScreen 内 `private`，提取到 `ui/components/SettingsComponents.kt` 并放开可见性
   - 注意：`ui/components/SongInfoPanel.kt:146` 已有一个同名 `private fun InfoRow`，迁移时处理命名冲突（重命名其一或用包隔离）
   - v1.0 所称「SettingSwitch 已存在于 ui/components」不属实——它目前是 SettingsScreen 的私有函数，需随本次迁移

5. **（v1.2 新增）Section 参数采用 data class 分组，禁止裸参数透传**：
   - 现状 `SettingsScreen` 主函数签名已是十几个参数；拆成 16 个 Section 后，若每个 Section 都从主函数逐层透传裸参数，参数列表将进一步爆炸，拆分收益被签名复杂度吃掉。
   - **约定**：每个 Section 定义成对的数据类——`data class XxxSettingsState(...)`（只读状态）+ `data class XxxSettingsActions(...)`（回调 holder），AppRoot 按 R-1 拆分后的各子 ViewModel 构造 State/Actions 后下发：

   ```kotlin
   data class ServerSettingsState(val baseUrl: String, val backendType: Int, ...)
   data class ServerSettingsActions(
       val onSave: (String, Int) -> Unit,
       val onTestConnection: (String, Int) -> Unit,
   )

   @Composable
   fun ServerSettingsSection(state: ServerSettingsState, actions: ServerSettingsActions) { ... }
   ```

   - 好处：Section 签名恒为 2 参数；新增设置项只改 data class 不改调用链；State/Actions 可整体由 AppRoot 从子 ViewModel 映射产出，与「状态上提」架构无缝衔接。

#### 迁移检查清单

- [ ] 每个Section独立编译通过
- [ ] SettingsScreen整体布局不变
- [ ] 焦点导航（TV D-Pad）在Section间正常工作
- [ ] 所有设置项功能正常
- [ ] 与 AppRoot 的参数/回调接口保持兼容（或与 R-1 同步演进）
- [ ] （v1.2）每个 Section 采用 State/Actions data class 分组签名，无裸参数超过 5 个的 Section

---

## R-3 LibraryScreen 拆分（🔴 P0）

### 3.1 现状分析

**文件**：[`LibraryScreen.kt`](app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt:140)（v1.0 锚点 129 行指向 `adaptiveColumns`，v1.1 更正为 140）

**问题量化**（已核实）：
- 文件大小：76.4KB（实测 78,184 字节）；共 1695 行
- Composable函数数：12
- 主函数签名为「参数+回调」式（albums/songs/genres/.../onPlayAlbum 等，共 15+ 参数）
- 涵盖：专辑网格、艺术家列表、年份列表、流派列表、歌曲列表（分页）、搜索栏、浏览维度选择器、详情页

**⚠️ 目录现状（v1.0 遗漏，必须处理）**：`ui/screens/library/` **目录已存在**，内含 4 个文件：

```
ui/screens/library/
├── DiscoverTab.kt   — 网络音乐「发现」Tab
├── JamendoTab.kt    — Jamendo Tab
├── RadioTab.kt      — 电台 Tab
└── SearchTab.kt     — 网络搜索 Tab
```

这些属于**网络音乐域**（NetworkMusic，与 `ui/screens/netdisk/` 同源），并非 NAS 曲库域。本项拆分的 NAS 曲库 Tab 与它们同名共存会造成职责混淆。

### 3.2 拆分方案

#### 目标结构（v1.1 修订）

```
ui/screens/library/            — 现有 4 个网络音乐 Tab 保持不动（或后续迁往
│                                 ui/screens/network/，作为独立小项，不混入本项）
├── DiscoverTab.kt             — [现有，保留]
├── JamendoTab.kt              — [现有，保留]
├── RadioTab.kt                — [现有，保留]
└── SearchTab.kt               — [现有，保留]

ui/screens/library/browse/     — 本项新增：NAS 曲库浏览子包（避开命名冲突）
├── BrowseScreen.kt            — 主入口，Tab容器+搜索栏（~150行）
├── AlbumGrid.kt               — 专辑网格展示
├── ArtistList.kt              — 艺术家列表
├── YearList.kt                — 年代列表
├── GenreList.kt               — 流派列表
├── SongList.kt                — 歌曲列表（含分页加载）
├── BrowseNavigator.kt         — 多维浏览导航器
├── AlbumDetailPage.kt         — 专辑详情页
├── ArtistDetailPage.kt        — 艺术家详情页
└── LibrarySearchBar.kt        — 曲库内搜索栏
```

> 备选：若不接受新增 `browse/` 子包，可将现有 4 个网络 Tab 迁至 `ui/screens/network/` 后腾出 `library/`——但那是额外一次大改动（4 文件 + 引用点），不建议与本项目捆绑。

#### 拆分步骤

1. **创建 `ui/screens/library/browse/` 目录**

2. **按Tab维度拆分**：每个Tab对应一个独立Composable文件，签名保持「参数+回调」

3. **详情页独立**：`AlbumDetailPage` 和 `ArtistDetailPage` 逻辑较重，单独提取（注意 `ui/screens/AlbumDetailScreen.kt`、`ArtistDetailScreen.kt` 已存在于 screens/ 顶层——迁移前先确认与本次提取的详情页是同一实现还是两套，避免重复）

   > **v1.2 已核实定案**：顶层 `AlbumDetailScreen.kt` / `ArtistDetailScreen.kt` 为**现役实现**（AppRoot.kt:881 实际调用 `AlbumDetailScreen(...)`），LibraryScreen 内仅为详情导航入口。因此本项拆分**不新写详情页**，直接把这两个现役文件迁移入 `library/browse/`（或保留顶层仅调整引用），严禁从 LibraryScreen 中再提取一份造成双实现。

4. **主入口精简为Tab容器**（v1.1 修订示例，匹配现有架构）：
```kotlin
@Composable
fun BrowseScreen(
    // 状态上提：AppRoot 订阅 LibraryViewModel 后按参数下发
    selectedTab: LibraryTab,
    albums: List<Album>,
    artists: List<Artist>,
    years: List<Int>,
    genres: List<Genre>,
    songsPaging: SongsPagingState,
    onSelectTab: (LibraryTab) -> Unit,
    onPlayAlbum: (Album) -> Unit,
    // ...
) {
    Column {
        LibraryTabRow(selectedTab, onSelectTab)
        when (selectedTab) {
            LibraryTab.ALBUMS -> AlbumGrid(albums, onPlayAlbum)
            LibraryTab.ARTISTS -> ArtistList(artists, onPlayArtist)
            LibraryTab.YEARS -> YearList(years, onPlayYear)
            LibraryTab.GENRES -> GenreList(genres, onPlayGenre)
            LibraryTab.SONGS -> SongList(songsPaging, onLoadMore, onPlaySong)
        }
    }
}
```

#### 迁移检查清单

- [ ] 每个Tab页独立编译通过
- [ ] Tab切换焦点恢复正确
- [ ] 分页加载（SongList）正常工作
- [ ] 搜索栏在所有Tab中可用
- [ ] 专辑/艺术家详情页导航正常
- [ ] 现有 `library/` 4 个网络音乐 Tab 未受影响（DiscoverTab/JamendoTab/RadioTab/SearchTab）
- [ ] 与顶层 `AlbumDetailScreen.kt`/`ArtistDetailScreen.kt` 的关系已厘清，无重复实现（v1.2 已定案：复用现役文件迁移，不新写）
- [ ] （v1.2）拆出的 Tab Composable 采用 State/Actions data class 分组签名（同 R-2 第 5 条约定）

---

## R-4 AppPreferences 按领域拆分（🟡 P1）

### 4.1 现状分析

**文件**：[`AppPreferences.kt`](app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt:47)

**问题量化**（已核实，v1.1 微调 + v1.2 口径澄清）：
- 文件大小：58KB（实测 59,354 字节）；共 1403 行
- `runBlocking` 物理调用点：**11 处**（另含 1 处 import、2 处注释、1 处 `return@runBlocking` 标签，故 `grep -c runBlocking` 为 15）。v1.1 记「14 处」系按逻辑读法（含普通方法委托 `getBaiduConfigSync()` 的组合读法）合并统计，口径偏宽——实施时**必须按物理 `runBlocking(` 位置复查**，避免漏改藏在委托链下的调用点。详见 R-7。
- 偏好键定义：**约 54 个**（v1.0 记「60+」，实测略少）
- 职责域：服务器配置、播放设置、歌词设置、网络音乐、百度Token、本地歌单、播放记录、搜索历史、下载配置、导出配置、天气配置、K歌配置、频谱/均衡器、备份

### 4.2 拆分方案

#### 目标结构

```
data/prefs/
├── AppPreferences.kt          — 门面类，聚合各子Prefs（~100行）
├── ServerPrefs.kt             — 服务器连接配置
├── PlayerPrefs.kt             — 播放模式/自动播放/人声分离/音高/速度
├── LyricsPrefs.kt             — 歌词偏移/缓存/源URL
├── NetworkMusicPrefs.kt       — 网络音乐源/Meting API/Jamendo/MV API
├── BaiduPrefs.kt              — 百度网盘Token/配置
├── LocalMusicPrefs.kt         — 本地音乐扫描/存储路径
├── DownloadPrefs.kt           — 下载路径/自动下载/WiFiOnly
├── ExportPrefs.kt             — 导出树URI/配置
├── WeatherPrefs.kt            — 天气API Key/城市
├── VisualizerPrefs.kt         — 频谱主题/均衡器预设
├── HistoryPrefs.kt            — 搜索历史/播放记录/播放统计
└── PlaylistPrefs.kt           — 本地歌单序列化
```

#### 门面模式

```kotlin
class AppPreferences internal constructor(private val context: Context) {
    // 共享 DataStore 实例
    internal val dataStore: DataStore<Preferences> by lazy { ... }

    // 子Prefs门面
    val server: ServerPrefs by lazy { ServerPrefs(dataStore) }
    val player: PlayerPrefs by lazy { PlayerPrefs(dataStore) }
    val lyrics: LyricsPrefs by lazy { LyricsPrefs(dataStore) }
    val network: NetworkMusicPrefs by lazy { NetworkMusicPrefs(dataStore) }
    val baidu: BaiduPrefs by lazy { BaiduPrefs(dataStore) }
    val local: LocalMusicPrefs by lazy { LocalMusicPrefs(dataStore) }
    val download: DownloadPrefs by lazy { DownloadPrefs(dataStore) }
    val export: ExportPrefs by lazy { ExportPrefs(dataStore) }
    val weather: WeatherPrefs by lazy { WeatherPrefs(dataStore) }
    val visualizer: VisualizerPrefs by lazy { VisualizerPrefs(dataStore) }
    val history: HistoryPrefs by lazy { HistoryPrefs(dataStore) }
    val playlist: PlaylistPrefs by lazy { PlaylistPrefs(dataStore) }

    companion object { ... } // 单例
}
```

#### 调用方迁移

```kotlin
// 旧：prefs.getPlayMode()
// 新：prefs.player.getPlayMode()

// 旧：prefs.getServerBaseUrl()
// 新：prefs.server.getBaseUrl()
```

#### 键归属映射（v1.3 补充：迁移时的分键对照）

> 拆分时按此表把 `AppPreferences` 现有 54 个键分入各子 Prefs；**同名键不迁移、只搬访问器**（DataStore 键对象保留在门面类 companion 中，子 Prefs 通过构造注入引用，保证同一 DataStore 文件不拆分）。

| 子 Prefs | 承接的键域（对应现有 Sync/Flow 读法） |
|---|---|
| ServerPrefs | 服务器地址/端口/类型/凭据（AES-GCM 加密存储的凭据整体迁入，勿解耦加密逻辑） |
| PlayerPrefs | 播放模式/自动播放/人声分离模式（含 `SeparationMode` 枚举序列化）/音高/速度 |
| LyricsPrefs | 歌词偏移/缓存开关/Kugou/Netease baseUrl |
| NetworkMusicPrefs | 网络音乐源/Meting baseUrl/Jamendo clientId/MV baseUrl |
| BaiduPrefs | CloudDriveConfig（含 Token）/enabled/musicRootDir/mvDir/customAppKey/customSecretKey |
| LocalMusicPrefs | 本地扫描路径/存储开关 |
| DownloadPrefs | 下载路径/自动下载/WiFiOnly |
| ExportPrefs | 导出树 URI/导出配置 |
| WeatherPrefs | 天气 API Key/城市 |
| VisualizerPrefs | 频谱主题/均衡器预设 |
| HistoryPrefs | 搜索历史/播放记录/播放统计 |
| PlaylistPrefs | 本地歌单 JSON 序列化 |

**v1.1 补充——lambda provider 注入点必须同步迁移**：`NasMusicApp.kt` 大量以 lambda 形式把 Sync 读法注入非协程组件（NetworkMusicManager:201-207、MvSearchManager:222、LyricsManager:123-124、Jamendo provider:178 等），拆分时这些 provider lambda 中的 `appPreferences.getXxxSync()` 需改为 `appPreferences.<domain>.getXxxSync()`，并保持 lambda 签名不变（这些组件接口不是 suspend）。

#### 迁移检查清单

- [ ] 每个子Prefs独立编译通过
- [ ] DataStore单例共享正确（同一文件只一个实例）
- [ ] 所有调用方迁移到新API（含 NasMusicApp 的全部 lambda provider）
- [ ] 旧API标记 `@Deprecated`，保留一个版本过渡期
- [ ] runBlocking问题同步修复（见R-7）

---

## R-5 PlayerManager 人声分离逻辑提取（🟡 P1）

### 5.1 现状分析

**文件**：[`PlayerManager.kt`](app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt)

**问题量化**（已核实）：
- 文件大小：62.6KB（实测 64,117 字节）；共 1510 行
- 人声分离相关方法（实际行号）：`setVocalRemovalProcessor`（:146）、`setVocalRemovalEnabled`（:157）、`isVocalRemovalEnabled`（:162）、`setDemucsSeparator`（:169）、`setAccompanimentCache`（:174）、`setSeparationMode`（:184）、`switchToAccompaniment`（:464，**private**）、`switchToOriginal`（:493，**private，v1.0 遗漏**）、`clearAccompanimentCache`（:553）
- 人声分离相关状态：`SpectralMaskProcessor`、`DemucsSeparator`、`AccompanimentCache`

**v1.1 更正**：`SeparationMode` 实际是 **`AppPreferences.SeparationMode` 嵌套枚举**（见 `setSeparationMode(mode: AppPreferences.SeparationMode)` 签名）。提取时需决定枚举归属：建议随本次迁移将其提升为顶层 `player/SeparationMode.kt`，AppPreferences 保留 typealias 或同步改名，避免 player 层反向依赖 data.prefs。

### 5.2 拆分方案

#### 目标架构

```kotlin
/**
 * 人声分离控制器
 * 管理快速DSP（SpectralMaskProcessor）和高质量ONNX（DemucsSeparator）双模式，
 * 以及伴奏缓存（AccompanimentCache）。
 */
class VocalSeparationController(
    private val playerManager: PlayerManager,
    private val prefs: AppPreferences
) {
    // 模式管理
    private val _separationMode = MutableStateFlow(SeparationMode.OFF)
    val separationMode: StateFlow<SeparationMode> = _separationMode.asStateFlow()

    // DSP处理器
    private var spectralMaskProcessor: SpectralMaskProcessor? = null

    // ONNX分离器
    private var demucsSeparator: DemucsSeparator? = null

    // 伴奏缓存
    private var accompanimentCache: AccompanimentCache? = null

    // 核心方法
    fun setSeparationMode(mode: SeparationMode) { ... }
    fun toggleVocalRemoval() { ... }
    fun switchToAccompaniment(path: String) { ... }
    fun switchToOriginal() { ... }      // v1.1 补充：与 switchToAccompaniment 成对
    fun clearAccompanimentCache(): Int { ... }
    fun onSongChanged(song: Song) { ... }  // 切歌时处理
    fun release() { ... }
}
```

#### PlayerManager 精简

```kotlin
class PlayerManager(...) {
    // 人声分离委托
    var vocalSeparation: VocalSeparationController? = null

    // 原有方法精简为委托调用
    fun setSeparationMode(mode: SeparationMode) {
        vocalSeparation?.setSeparationMode(mode)
    }

    // ... 其他播放核心逻辑保持不变
}
```

**注意**：`switchToAccompaniment`/`switchToOriginal` 目前是 PlayerManager 的 `private` 方法，由内部播放状态机（切歌/结束回调）调用。提取后这些内部调用点需改为 `vocalSeparation?.switchTo...`，且 PlayerManager 与 Controller 互相持有对方引用会形成环——建议 Controller 持有 PlayerManager 的窄接口（只暴露切源所需方法），避免强环引用。

#### 迁移检查清单

- [ ] VocalSeparationController 独立编译通过
- [ ] `AppPreferences.SeparationMode` 枚举归属迁移完成，无 player→data.prefs 反向依赖
- [ ] 快速DSP模式正常工作
- [ ] 高质量ONNX模式正常工作
- [ ] 伴奏缓存命中/失效正确
- [ ] 切歌时分离状态正确重置（switchToOriginal 路径）
- [ ] K歌模式进出正常

---

## R-6 OkHttpClient 资源池化（🟡 P1）

### 6.1 现状分析

**当前状态**（已核实）：
- 5 个后端适配器（Jellyfin:48 / Navidrome:50 / Subsonic:55 / Daoliyu:65 / Feiniu:80）各自 `by lazy` 创建独立 `OkHttpClient`
- `BackendRegistry.initialize()` 替换适配器时通过 `close()` 释放旧适配器资源；以 JellyfinAdapter 为例，`close()`（:1024）执行 `dispatcher.executorService.shutdown()` + `connectionPool.evictAll()`
- 问题：切换频繁时，新旧适配器可能短暂共存，累积多套 OkHttpClient

### 6.2 优化方案

#### 方案A：共享连接池（推荐）

> **v1.3 分步实施顺序**（每步独立提交，见「开发实施规约」）：
> 1. `BackendRegistry` 增补 `sharedConnectionPool`/`sharedDispatcher`（本步不动适配器，编译提交）
> 2. 逐个适配器注入（Jellyfin → Navidrome → Subsonic → Daoliyu → Feiniu，每个独立提交，便于二分定位回归）
> 3. 逐个适配器删除 close() 中的 `shutdown()+evictAll()`（与第 2 步同提交，不拆开——半状态最危险）
> 4. testConnection 临时适配器路径验证（BackendRegistry.kt:126/148/153）

```kotlin
// BackendRegistry 中提供共享连接池
class BackendRegistry {
    // 共享连接池：5个空闲连接，5分钟keep-alive
    internal val sharedConnectionPool = ConnectionPool(
        maxIdleConnections = 5,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    // 共享Dispatcher（限制最大并发请求数）
    internal val sharedDispatcher = Dispatcher().apply {
        maxRequests = 16
        maxRequestsPerHost = 8
    }

    // 适配器创建时注入共享资源
    suspend fun initialize(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        val adapter = when (config.backendType) {
            TYPE_JELLYFIN -> JellyfinAdapter(sharedConnectionPool, sharedDispatcher)
            TYPE_NAVIDROME -> NavidromeAdapter(sharedConnectionPool, sharedDispatcher)
            // ...
        }
        // ...
    }
}
```

#### 适配器修改

```kotlin
class JellyfinAdapter(
    private val connectionPool: ConnectionPool,
    private val dispatcher: Dispatcher
) : BackendAdapter {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .addInterceptor(authInterceptor)
            .build()
    }

    override fun close() {
        // ⚠️ v1.1 关键陷阱：共享后【禁止】调用
        //   client.dispatcher.executorService.shutdown()  → 会废掉全局线程池
        //   client.connectionPool.evictAll()               → 会清掉其他适配器的连接
        // 仅清除本适配器的认证缓存/cookie 等私有状态
    }
}
```

> **v1.1 补充**：现有 5 个适配器的 `close()` 全部含 `shutdown()+evictAll()`（BackendRegistry.kt:67-69 的注释说明了这是历史上「WiFi 栈过载」问题的修复）。池化后必须逐个适配器删除这两行，否则第一次切换后端就会使全局 Dispatcher/连接池失效。同时 `BackendRegistry.testConnection()`（:148,153）创建的临时适配器也走同一 close 路径，需一并验证。

#### 迁移检查清单

- [ ] 共享连接池和Dispatcher正常工作
- [ ] **所有适配器的 close() 已移除 shutdown/evictAll（5 个适配器逐一检查）**
- [ ] **切换后端后其余请求仍可正常发出（防全局线程池被误关）**
- [ ] testConnection() 临时适配器不再触发共享资源回收
- [ ] 各适配器独立拦截器配置不受影响
- [ ] 并发请求限制合理

---

## R-7 runBlocking 使用风险修复（🟡 P1）

> **v1.1 本章完全重写**。v1.0 所列 `getServerConfigSync()`、`getPlayModeSync()` 及「PlayerManager 初始化读取播放模式」**均不存在**（PlayerManager 的 playMode 是方法参数，由 ViewModel 传入）；`saveCloudDriveConfigBlocking()` 实为 `saveCloudDriveConfigSync()`。以下为逐调用点核实后的真实清单。

### 7.1 现状分析

**文件**：[`AppPreferences.kt`](app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt)

**真实分布**（**11 处物理 `runBlocking(` 调用点**，覆盖下表全部逻辑读法；`getBaiduEnabledSync` 等组合读法内部委托 `getBaiduConfigSync()`(:1162) 的 runBlocking(:1151)，非独立调用点）：

| 方法（行号） | 真实调用点 | 调用线程语境 | 风险评级 |
|--------------|-----------|------------|---------|
| `getLanguageSync()` (:164) | `MainActivity.attachBaseContext`（MainActivity.kt:92） | **主线程，且 attachBaseContext 无法挂起** | 🔴 高（见下方专项） |
| `getWeatherApiKeySync()` (:640) | `MainViewModel.fetchWeather`（MainViewModel.kt:476） | **viewModelScope（Main dispatcher）内** → runBlocking 阻塞主线程 | 🔴 高 |
| `getMusicSourceSync()` (:564) | NasMusicApp 注入 NetworkMusicManager 的 lambda provider（:202） | provider 回调线程（视调用方） | 🟡 中 |
| `getDefaultNetworkSourceSync()` (:689) | NasMusicApp 注入 NetworkMusicManager（:207） | 同上 | 🟡 中 |
| `getJamendoClientIdSync()` (:586) | NasMusicApp 注入 Jamendo clientIdProvider（:178） | 同上 | 🟡 中 |
| `getMetingApiBaseUrlSync()` (:704) | NasMusicApp 注入（:201）+ AppSettings 默认值 | 同上 | 🟡 中 |
| `getMvApiBaseUrlSync()` (:719) | NasMusicApp 注入 MvSearchManager（:222）；BilibiliMvService 运行时切换 | 同上 | 🟡 中 |
| `getLyricsKugouBaseUrlSync()` (:731) | NasMusicApp 注入 LyricsManager（:123） | **主线程急切求值**（v1.4 更正：Application.onCreate 直接传值非 lambda，且 MainViewModel:138-142 构造期再次调用） | 🟡 中 |
| `getLyricsNeteaseBaseUrlSync()` (:742) | NasMusicApp 注入 LyricsManager（:124） | 同上 | 🟡 中 |
| `getCloudDriveConfigSync()` (:1149) | BaiduOAuthClient / BaiduNetdiskService / BaiduMvFileService / MainViewModel | 部分在 withContext(IO) 内，部分不确定 | 🟡 中 |
| `saveCloudDriveConfigSync()` (:1187) | MainViewModel（百度授权回写） | 调用处多为协程 | 🟢 低 |
| `getBaiduTokensSync()` (:1194) | BaiduOAuthClient（:192/:211/:225，均在 IO 上下文） | IO 线程 | 🟢 低 |
| `getBaiduEnabledSync/MusicRootDirSync/MvDirSync/CustomAppKeySync/CustomSecretKeySync` (:1227-1260) | BaiduOAuthClient.resolveAppKey/resolveSecretKey（:42-43）等 | 混合 | 🟡 中 |

**总体风险**：
- `runBlocking(Dispatchers.IO)` 阻塞的是**调用者线程**（runBlocking 本身挂起当前线程等待内部协程），Dispatchers.IO 只影响块内执行——因此「在 Main dispatcher 协程里调 get*Sync」等于冻住主线程，DataStore 首读涉及磁盘 IO 时足以触发 ANR。
- DataStore 官方推荐 Flow 异步读取，`runBlocking` 仅应在无法避免的初始化场景使用并加防护。

### 7.2 修复方案

#### 分类处理

**第一类：真正的启动期主线程调用（`getLanguageSync`，专项处理）**

`attachBaseContext` 无法改为 suspend，这是 Android 已知约束。建议：
- 读语言偏好改用普通 `SharedPreferences`（同步读、且有内存缓存），或
- DataStore 侧加「上次值」内存缓存：Application onCreate 后台预热，attachBaseContext 命中缓存则零 IO，未命中（冷启动首帧前）退回默认语言并打点。
- 不建议保留现状——语言初始化发生在每次 attachBaseContext，属于高频主线程 runBlocking。

**v1.3 落地骨架（推荐混合方案）**：语言键双写（SharedPreferences 镜像 + DataStore 事实源），设置语言时同步写两处；`attachBaseContext` 只读 SharedPreferences：

```kotlin
// AppPreferences 内新增（镜像只服务冷启动语言，其余设置项不搞双写）
private const val LANGUAGE_MIRROR = "language_mirror"

fun setLanguage(value: String) { ... /* DataStore 写 + mirrorPrefs.edit().putString(...).apply() */ }
fun getLanguageCold(): String = mirrorPrefs.getString(LANGUAGE_MIRROR, DEFAULT) ?: DEFAULT
// MainActivity.attachBaseContext:92 改调 getLanguageCold()；NasMusicApp:192 的 applyLocale 同步改
```

**第二类：Main 协程内的 Sync 调用（`getWeatherApiKeySync` 等，必须改）**

```kotlin
// 旧（MainViewModel.kt:476，fetchWeather 内）：
val apiKey = nasMusicApp.appPreferences.getWeatherApiKeySync()   // 阻塞主线程

// 新：
val apiKey = withContext(Dispatchers.IO) { nasMusicApp.appPreferences.getWeatherApiKey() }  // suspend 版本
// 或 UI 层 collectAsState 订阅 weatherPrefs.apiKey Flow
```

排查方式：全库 grep `get*Sync(` 出现在 `viewModelScope.launch`/`Dispatchers.Main` 语境的调用点，逐一改 suspend/Flow。

**第三类：lambda provider 注入的非协程组件（核心难点，v1.0 未识别）**

`NetworkMusicManager`、`MvSearchManager`、`LyricsManager`、Jamendo provider 的接口签名是 `() -> String` 普通 lambda（NasMusicApp.kt:123-124/178/201-222），**不能直接改 suspend**。两条路：
- **路A（推荐，改动小）**：AppPreferences 为这些键维护 `@Volatile` 内存镜像——DataStore Flow 常驻收集（Application scope）更新镜像，Sync 读改为读镜像（无 IO、无阻塞）。语义等价于「设置页改了立即生效」。
- **路B（彻底，改动大）**：组件接口改为 `suspend fun` 或注入 `Flow<String>`，逐组件重构调用链。适合与 R-4 拆分同期做。

**v1.3 落地骨架（路A）**：

```kotlin
// AppPreferences 内（8 个 provider 键统一走此模式）
@Volatile private var cachedMusicSource: String = DEFAULT
val musicSourceMirror: String get() = cachedMusicSource

init {
    // Application scope 常驻收集（scope 由 NasMusicApp 传入）
    scope.launch {
        dataStore.data.map { it[keyMusicSource] ?: DEFAULT }
            .distinctUntilChanged()
            .collect { cachedMusicSource = it }
    }
}
fun getMusicSourceSync(): String = musicSourceMirror   // 读法签名不变，调用点零改动
```

**第四类：已在 IO 上下文的调用（`getBaiduTokensSync` 等，可暂留）**

BaiduOAuthClient 内的调用大多已在 `withContext(IO)` 内（:211 等），runBlocking 退化为「IO 线程上的同步等待」，无 ANR 风险。处理：保留，但统一加 `@WorkerThread` 注解与文档注释，防止未来被主线程误用。

#### 迁移优先级（v1.1 重排）

| 优先级 | 方法 | 调用位置 | 修复方式 |
|--------|------|---------|---------|
| 🔴高 | `getLanguageSync()` | MainActivity.attachBaseContext:92 | SharedPreferences/内存缓存（第一类） |
| 🔴高 | `getWeatherApiKeySync()` | MainViewModel:476（Main协程内） | 改 suspend / Flow（第二类） |
| 🟡中 | `getMusicSourceSync` 等 7 个 provider 读法 | NasMusicApp lambda 注入点 | 内存镜像 or 接口 Flow 化（第三类，路A起步） |
| 🟡中 | `getCloudDriveConfigSync` 及百度系列 | Baidu 系组件 | 核实各调用点线程语境，Main 语境改 suspend；IO 语境暂留+注解 |
| 🟢低 | `saveCloudDriveConfigSync` | MainViewModel 协程内 | 改 suspend |

#### 迁移检查清单

- [ ] `getLanguageSync` 主线程 IO 已消除（冷启动语言正确）
- [ ] Main 协程内所有 `get*Sync` 已消除（grep 复查 `viewModelScope` 语境）
- [ ] lambda provider 注入点改内存镜像或 Flow，设置页改动即时生效
- [ ] 保留的 IO 语境 Sync 调用加 `@WorkerThread` 注释
- [ ] 功能回归测试通过

---

## R-8 手动 DI 迁移到 Hilt（🟢 P2）

### 8.1 现状分析

**文件**：[`NasMusicApp.kt`](app/src/main/java/com/nasmusic/tv/NasMusicApp.kt)

**问题**（已核实）：
- `NasMusicApp` 作为手动DI容器，在 `onCreate` 中按固定顺序初始化全部组件
- 组件间依赖关系隐式（通过构造参数传递，但初始化顺序靠代码排列保证）
- 百度组件（baiduOkHttpClient/baiduOAuthClient/baiduPanApi/baiduStreamFactory 等，:130-172）使用 `lazy` 延迟构造，其余组件直接构造，初始化策略不一致
- 测试时无法轻松替换依赖

### 8.2 迁移方案（长期规划）

此为长期改进项，建议在R-1~R-7、R-10完成后再考虑。

**阶段一：引入Hilt基础架构**
1. 添加Hilt依赖（`hilt-android`、`hilt-compiler`、`hilt-viewmodel`）
2. `NasMusicApp` 添加 `@HiltAndroidApp` 注解
3. `MainActivity` 添加 `@AndroidEntryPoint` 注解

**阶段二：逐组件注册到Hilt**
1. `AppPreferences` → `@Module` + `@Provides` + `@Singleton`
2. `BackendRegistry` → `@Module` + `@Provides` + `@Singleton`
3. `PlayerManager` → `@Module` + `@Provides` + `@Singleton`
4. 其他全局单例逐步迁移

**阶段二：ViewModel注入**
1. 各ViewModel（R-1拆分后的子ViewModel）添加 `@HiltViewModel` 注解
2. 构造参数添加 `@Inject` 注解
3. 移除手动从 `NasMusicApp` 获取依赖的代码
4. 届时再评估是否将部分 Screen 从「AppRoot 状态上提」切换为局部 `viewModel()` 注入（见架构决策章节的远期选项）

**v1.2 地位澄清：本项为可选项，非路线图必达项**。理由：
- min SDK 22 + Kotlin 2.2.10 的 kapt/ksp 兼容性需先行验证，本身即是不确定性成本；
- 手动 DI（NasMusicApp）+ 状态上提架构目前运转良好；R-1 完成后「可测试性 ⭐⭐」的主要扣分项（巨型 ViewModel）已消除大半，Hilt 的边际收益有限；
- 若未来确需引入，应先在一个非核心组件（如 `AppPreferences`）上做试点，验证构建链路与 min SDK 22 兼容后再扩大范围。

**风险**：
- 迁移期间手动DI和Hilt共存，需确保单例唯一性
- DataStore单例需特殊处理（Hilt + DataStore 集成需额外配置）
- min SDK 22 + kapt/ksp 配置需验证与 Kotlin 2.2.10 的兼容性

---

## R-9 代码质量微调（🟢 P2）

### 9.1 重复import

**文件**：[`MainViewModel.kt`](app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt:85)

**问题**：第85行和第92行重复导入 `kotlinx.coroutines.async`（已核实）

**修复**：删除第92行重复import

### 9.2 颜色硬编码

**文件**：[`Theme.kt`](app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt:32)

**现状**：`NasMusicColors` 对象硬编码所有颜色值，与CSS变量一一对应（已核实，:32 起）

**建议**：当前设计合理（TV应用通常不需要运行时切换主题色），保持现状。如未来需要动态主题，可改为从DataStore读取色值。

### 9.3 历史修复注释残留

**现状**：代码中保留 B4/B11/C-1/H-2 等修复标记（已核实存在于 DaoliyuAdapter、FeiniuAdapter、NavidromeAdapter、LocalMusicRepository、BaiduHttpDataSourceFactory、BaiduOAuthClient、MetingApiService、BilibiliMvService、AppPreferences、PlayerManager 等文件）

**建议**：
- 保留注释中的修复说明（有助于代码考古）
- 在注释中添加修复日期和issue/PR编号（如有）
- 不需要强制清理

### 9.4 修复标记汇总

| 标记 | 位置 | 说明 | 状态 |
|------|------|------|------|
| B4 | LocalMusicRepository | USB歌曲卷未挂载时不误判为已删除 | ✅ 已修复，注释保留 |
| B11 | NavidromeAdapter | salt固定，避免封面URL不稳定 | ✅ 已修复，注释保留 |
| B15 | StorageMonitor | API 24–29 反射 getPath 异常防护（:103-107） | ✅ 已修复，注释保留（v1.4 补录） |
| C-1 | 网络层 | 移除trust-all证书校验 | ✅ 已修复，注释保留（2026-09-09 恢复TLS验签，勿再引入trust-all） |
| H-2 | AppPreferences | java.io.tmpdir改用cacheDir | ✅ 已修复，注释保留 |
| H-3 | AppPreferences | 百度配置Flow替代runBlocking同步读 | ✅ 已修复，注释保留 |
| H-4 | ModelTransferServer | 18082端口被遥控服务器占用→改18083（:31） | ✅ 已修复，注释保留（v1.4 补录） |
| H-5 | BaiduFileIndexCache | 索引原子写盘（临时文件+rename，:77） | ✅ 已修复，注释保留（v1.4 补录） |
| M-1 | MainActivity | 退出时 logout 限时1.5s 防ANR（:209） | ✅ 已修复，注释保留（v1.4 补录） |
| M-2 | MainActivity | onDestroy 仅 isFinishing 时清理，防误杀后台播放（:350） | ✅ 已修复，注释保留（v1.4 补录） |
| M-5 | MainActivity | Android 13+ POST_NOTIFICATIONS 运行时权限（:125） | ✅ 已修复，注释保留（v1.4 补录） |
| M-7 | RemoteControlServer | 跨源搜索限时10s 防worker线程占用（:154） | ✅ 已修复，注释保留（v1.4 补录） |
| M-9 | MainViewModel | 换一批集合硬上限4000防无限增长（:1898） | ✅ 已修复，注释保留（v1.4 补录） |
| M-14a/b/c/d | BaiduPanApi/FileIndexCache/CoverProvider/LyricsProvider | 分页游标停滞保护/补传start/清目录倒排/只接受206 | ✅ 已修复，注释保留（v1.4 补录） |
| M-15 | BaiduLyricsProvider | dlink 补 access_token 防403（:52/:67） | ✅ 已修复，注释保留（v1.4 补录） |
| M-16 | BaiduStreamFactory | dlink 失败 errno 识别与降级（:36） | ✅ 已修复，注释保留（v1.4 补录） |

---

## R-10 后端适配器巨型文件拆分（🟡 P1）

> **v1.1 新增**。v1.0 标题为「代码库重构」却未覆盖 backend/impl 层，而附录自列的 JellyfinAdapter 54KB / NavidromeAdapter 41.7KB / SearchAggregator 17.8KB 是仅次于三大 UI 巨型文件的问题。

### 10.1 现状分析

| 文件 | 规模（实测） | 问题 |
|------|------------|------|
| `backend/impl/JellyfinAdapter.kt` | 1215 行 / 54KB | 单类承担：认证、专辑/艺术家/流派/年份/歌曲分页、搜索、流URL、歌词、封面、快速扫描、close 资源管理 |
| `backend/impl/NavidromeAdapter.kt` | 932 行 / 41.7KB | 同上（Subsonic 系 API） |
| `backend/impl/SubsonicAdapter.kt` / `DaoliyuAdapter.kt` / `FeiniuAdapter.kt` | — | Navidrome/Subsonic 逻辑高度相似，存在跨适配器重复 |
| `backend/SearchAggregator.kt` | 413 行 / 17.8KB | 尚可，暂不动 |

### 10.2 拆分方案（方向性，实施前需专项细化）

1. **公共层下沉**：Subsonic 系三个适配器（Navidrome/Subsonic/道理鱼）的 salt/token、分页、封面URL 构造等共性逻辑提取到 `SubsonicBaseAdapter` 或工具类，子类只保留差异（endpoint、字段映射）。
2. **JellyfinAdapter 按 API 域拆分**：认证拦截器、浏览（Items 查询构造）、歌词、封面/流URL 各自成文件（`jellyfin/JellyfinAuth.kt`、`JellyfinItemsApi.kt` 等），Adapter 保留为组装层。
3. **与 R-6 协同**：拆分时顺带注入共享 ConnectionPool/Dispatcher，避免二次返工。

### 10.3 迁移检查清单

- [ ] 五个后端连接/浏览/搜索/播放回归（每后端至少手动过一遍核心路径）
- [ ] GBK 编码兜底（EncodingUtils）行为不回退
- [ ] 与 R-6 共享资源改造合并验证

---

## 实施路线图

### 阶段一：P0 巨型文件拆分（预计 9-14 天 + AppRoot 联动 2-3 天）

| 周次 | 任务 | 产出 |
|------|------|------|
| W0 | **确认架构决策章节 + W0 接口冻结清单逐项勾选**（子 VM 签名/事件契约/State-Actions 样板） | 决策记录 + 冻结清单 |
| W1-W2 | R-1 MainViewModel拆分（第一步+第二步，每域一提交） | WeatherRadioViewModel, BackupViewModel, PlaylistViewModel, DownloadViewModel, MvSearchViewModel, VocalSeparationViewModel |
| W3 | R-1 MainViewModel拆分（第三步+第四步）+ **AppRoot 123 处 collectAsState 重组** | SearchViewModel, LibraryViewModel, NetworkMusicViewModel, PlayerViewModel, ServerViewModel, 精简MainViewModel |
| W3-W4 | R-2 SettingsScreen拆分 | 16个独立Section文件 + SettingsComponents.kt |
| W4 | R-3 LibraryScreen拆分 | library/browse/ 下 9 个独立Composable文件 |

### 阶段二：P1 架构优化（预计 6-9 天）

| 周次 | 任务 | 产出 |
|------|------|------|
| R-7 | 12个子Prefs类 + 门面类（含 lambda provider 迁移） |
| W4.5 | **F-1 日志凭证脱敏（v1.4 新增，独立小项可先行）** | sanitizeUrl 公共函数 + 5 适配器接入 + 单测 |
| W4.5 | **F-2 AppRoot 重组热点（v1.4 新增，与 R-1 W3 同周——AppRoot 反正要动）** | progress/duration 收集下沉 |
| W5 | R-7 runBlocking风险修复（先于 R-4，消除 ANR；**含 F-3 LyricsManager lambda 化/单例化**） | 11 处物理调用点 + F-3 处理完毕 |
| W6 | R-5 PlayerManager人声分离提取 | VocalSeparationController |
| W6 | R-6 OkHttpClient资源池化 | 共享ConnectionPool + Dispatcher（5适配器close()改造） |
| W7 | R-10 后端适配器拆分 | Subsonic 公共层 + Jellyfin 域拆分 |

### 阶段三：P2 长期改进（按需安排）

| 任务 | 预计工时 |
|------|---------|
| R-8 Hilt迁移 | 3-5天 |
| R-9 代码质量微调 | 0.5天 |

> **工时说明（v1.1 + v1.2）**：v1.0 估算仅覆盖「搬代码」，未计入 AppRoot/MainActivity 联动改写、逐职责域手动回归（本项目 CI 只跑 `assembleDebug`，功能验证全靠手测——见 AGENTS.md）。上表已上调；**v1.2 补充：TV 设备手测回归（D-Pad 焦点逐项过）成本高，实际排期建议在上表基础上再预留 30% 缓冲**。如时间紧张，优先保证回归质量而非进度。

---

## 深度代码审阅发现（F 系列）

> **v1.4 新增**。本系列来自对 backend/player/ui/data/util/net/lyrics 全模块的深度审阅（并发与线程安全、生命周期与资源释放、日志安全、缓存上限、Compose 重组效率、API 误用），**不与 R-1~R-10 重叠**。每项均有源码行号佐证（2026-09-09 基线核实）。发现总体结论：代码库健康度好于预期——已修复标记真实有效、缓存均有上限、搜索均有超时；真正的增量问题集中在**日志泄露**与**AppRoot 重组效率**两处，其余为低危改进项。

### F-1 敏感凭证 URL 泄露到 release 日志（🔴 P0，安全）

**问题**：`AppLog` 的 `d/i` 级别有 `BuildConfig.DEBUG` 门禁，但 **`w/e` 级别始终输出**（AppLog.kt:29-40），而 release 构建 proguard 只剥离 `android.util.Log.d/v`（proguard-rules.pro:37-39）。以下调用把含凭证的完整 URL 打进 w/e 日志，release 包连 logcat 即可读取：

| 位置 | 泄露内容 |
|---|---|
| JellyfinAdapter.kt:1094 / :1098 | `executeJsonRequest failed for $url` / `... for $url` —— `getSongTechnicalInfo` 等传入的 URL 含 `api_key=$apiToken`（:497 等多处拼装） |
| NavidromeAdapter.kt:909 | `executeRequest failed for $url` —— Subsonic 系 URL 含 `u=<user>&t=<md5(password+salt)>&s=<salt>`（:873-876 现场计算） |
| SubsonicAdapter.kt:829 | 同上 |
| FeiniuAdapter.kt:539 / :564 | `GET error url=${url.take(80)}` —— 80 字符前缀足以覆盖 URL 中的登录 token |
| BackendRegistry.kt:48 | `username=${config.username}, hasPw=...` —— `AppLog.d` 有 DEBUG 门禁，**暂不泄露**；但注意此行在 release 无输出，仅 debug 泄露用户名（低危，可顺手脱敏） |
| FeiniuAdapter.kt:171 | `token=${token.take(20)}` —— `AppLog.d` 门禁内，release 不输出（低危） |

对比正面案例：DaoliyuAdapter:473/:498 已有 `sanitizeUrl(url)` 防护——**说明团队已意识到该问题但只修了一个适配器**。

**修复方案**（0.5 天）：
1. 全库 5 个适配器的错误日志统一走 `sanitizeUrl()`（把 Daoliyu 的实现提为 util 公共函数），`api_key`/`token`/`t=`（Subsonic md5）/`password` 参数值替换为 `***`；
2. BackendRegistry.kt:48 的 username 改为只记 `hasUser=true/false`；
3. 检查项加入「开发实施规约」禁改清单旁的**新增日志规范**：任何含 URL 的 w/e 日志必须过 sanitizeUrl。

### F-2 AppRoot 顶层 progress/duration 收集每秒驱动全树重组（🟡 P1，性能）

**问题**：AppRoot.kt:102-103 在**顶层作用域**（when(currentScreen) 之外）收集 `viewModel.progress`/`duration`。PlayerManager 的进度由 1000ms Handler 轮询驱动（PlayerManager.kt:103），因此**播放期间每秒产生一次 state 变化 → AppRoot 整个 Composable 重组**——包括当前不显示的分支外层 Box、导航栏（当前歌名/进度条 :164 附近也用它）等。TV 设备重组开销敏感（D-Pad 焦点搜索、LazyColumn 状态保持）。

对比正面案例：AppRoot 已做过同类优化——频谱流 20fps 数据改到 NowPlaying 分支内收集（AppRoot.kt:309 注释「修复（H-3）：20fps 频谱流只在本页收集，不再驱动 AppRoot 全树重组」），证明本项与既有优化方向一致。

**修复方案**（0.5 天）：
1. 把 `progress`/`duration` 收集从顶层移入使用处——仅两处消费：NowPlayingScreen 分支（:348-349）与顶栏迷你进度条。顶栏若仅在 NowPlaying/播放中可见，可与 NowPlayingScreen 参数一起下沉，或将迷你进度条抽为独立 Composable 在其内部收集；
2. 可选进阶：`collectAsState` 换 `collectAsStateWithLifecycle`（需加 `lifecycle-runtime-compose` 依赖，app/build.gradle.kts:121 目前只有 runtime-ktx/viewmodel-compose）——后台时暂停收集，顺带降低待机功耗。

### F-3 LyricsManager 构造期主线程 runBlocking×2 + 每 VM 实例重建（🟡 P1）

**问题**：MainViewModel.kt:138-142 构造 `LyricsManager(app, backendRegistry, networkMusicManager, kugouBaseUrl = prefs.getLyricsKugouBaseUrlSync(), neteaseBaseUrl = prefs.getLyricsNeteaseBaseUrlSync())`——**急切求值**（非 lambda），构造期主线程两次 runBlocking DataStore 首读。同时 NasMusicApp:123-124 也传了同样两值——**同一配置被两处以急切值注入**，设置页改了歌词源 URL 后，两处注入值均不会更新（设置生效需重启，与 R-7 路 A「改设置立即生效」目标冲突）。

另注意：`LyricsManager` 每随 MainViewModel 实例化而重建（非 NasMusicApp 单例），其内部 `LyricsPersistentCache` 也随之重建并重新 loadIndex（LyricsManager.kt:45）——当前 MainViewModel 与 Activity 同生命周期重建时（配置变更已被 M-2 规避，实际影响小），但 **R-1 拆分时若把歌词逻辑分散到多个子 VM，会创建多个 LyricsManager 实例并发读写同一缓存目录**（saveIndex/loadIndex 无跨实例互斥，LyricsPersistentCache.kt:168-176 为全量覆盖写）。

**修复方案**（并入 R-1/R-7 实施）：
1. 构造参数改 lambda provider（与 NasMusicApp 其余注入一致），或 LyricsManager 上升为 NasMusicApp 单例；
2. R-1 拆分时 LyricsManager/PersistentCache 只允许一个所有者（建议 LyricsManager 收归 NasMusicApp 持有，子 VM 注入引用），并在 W0 冻结清单明确归属。

### F-4 ExportCoordinator / AccompanimentCache 自建 scope 无取消路径（🟢 P2）

**问题**：`ExportCoordinator.kt:45` 与 `AccompanimentCache.kt:42` 各自 `CoroutineScope(SupervisorJob()+Dispatchers.IO)`，但两者**均无 scope.cancel() 调用**（grep 零命中）——ExportCoordinator.cancel()（:145）只取消当前导出任务不取消 scope。二者均为 NasMusicApp 持有的进程级单例，进程死亡时线程池由 OS 回收，**实际泄漏影响有限**；但与 `CoverUrlPersistentCache.kt:91`（有 saveScope.cancel）风格不一致，且未来若改为可重建组件会成真泄漏。

**修复方案**：保持现状可接受；若 R-1~R-3 期间顺手处理，统一改为注入 `NasMusicApp.applicationScope`（NasMusicApp.kt:186 已存在 SupervisorJob+IO scope），删除私有 scope。注意 applicationScope 也不 cancel——单例场景这是正确语义。

### F-5 LyricsPersistentCache 无跨实例/跨线程写互斥（🟢 P2，依赖 F-3 决策）

**问题**：`saveIndex()`（LyricsPersistentCache.kt:168）全量 `writeText` 覆盖写，`put/remove/clear/exportAll` 均触发；ConcurrentHashMap 只保证索引内存安全，**文件写无互斥**——同一实例内 LyricsManager 各方法均 `withContext(IO)` 并发调用时（切歌时 put 与备份 exportAll 并发），存在索引文件交错写风险（概率低：写的是小 JSON，单次 writeText 原子性依赖 FS）。风险被 F-3 放大：若出现多实例，交错概率上升。

**修复方案**：与 F-3 合并处理——单例化后，给 saveIndex 加 `synchronized(this)` 或 Mutex 即可（H-5 已做原子写盘，本项补写互斥）。注意 BaiduFileIndexCache:77 的 H-5 修复模式可直接复用。

### F-6 NetworkUtils.getLocalIpAddress 取首个 IPv4 不分接口优先级（🟢 P2）

**问题**：NetworkUtils.kt:23-34 `firstOrNull()` 不区分网卡（有线/无线/USB tethering）——TV 常见双网卡（以太网+WiFi）场景下，遥控/传输二维码可能展示错误网段的 IP，手机扫码后连不上（RemoteControlServer.kt:47、BackupTransferServer、ModelTransferServer 均用此函数生成 URL）。

**修复方案**（0.5 天）：优先返回「有默认路由的接口」的 IPv4（`ConnectivityManager.activeNetwork` → `linkProperties`，或按 interface name 排序 eth/wlan 优先）；兜底保持现状。验收：双网卡设备扫码可达。

### F-7 天气 API Key 明文存 DataStore（🟢 P2，与安全基线一致性）

**问题**：`keyWeatherApiKey`（AppPreferences.kt:110）明文存储，而服务器密码/token（:245-259）与百度 token（:1146 注释声明）均走 CryptoUtils AES-GCM。OpenWeatherMap key 属付费资源凭证，泄露可被刷量。当前 baseline「凭据 AES-GCM 加密存储」未覆盖此键。

**修复方案**：迁移至 CryptoUtils.encrypt（改键名 `weather_openweathermap_api_key_enc` 并做一次性迁移读旧值）。低优先——家庭局域网场景 + key 本身在 URL 明文传输（OpenWeatherMap API 限制），收益主要是防本地提取。

### F-8 下载无前台服务/通知保活（🟢 P2，需产品确认）

**问题**：SongDownloadManager 无任何 startForeground/Notification（全库 startForeground 仅 PlaybackService:226 一处；下载进度通知依赖 M-5 申请的 POST_NOTIFICATIONS 但 grep 未见下载通知实现——实际通知链路缺失）。TV 设备通常不熄屏，且下载在 applicationScope 执行，进程存活期间下载可持续；但若用户按电源待机，Doze 模式下网络受限可能中断大文件下载且**无任何用户可见状态**。

**修复方案**：产品决策项——若接受「TV 场景常亮」假设则记录取舍即可；若要修，需要一个 `dataSync` 类型的前台服务 + 进度通知（manifest 仅声明了 mediaPlayback 类型 :69，需扩展）。**建议先与所有者确认，勿默认实施**（与「本地 HTTP 服务无鉴权是所有者接受的取舍」同类的场景取舍）。

### 已验证无问题项（防止未来误报，v1.4 记录）

以下疑点经核实**不构成问题**，列出以免后续审阅重复调查：

| 疑点 | 核实结论 |
|---|---|
| GlobalScope 滥用 | 全库零使用 ✅ |
| 多处自建 CoroutineScope | 6 处全部 SupervisorJob+IO/Main 正确组合 ✅ |
| SearchAggregator 聚合搜索无超时 | 各源均 withTimeoutOrNull（NAS/NETWORK/BAIDU/JAMENDO 各分支 :128/:149/:180/:206）✅ |
| 歌词/MV 持久缓存无限增长 | LyricsPersistentCache MAX_ENTRIES=2000 LRU（:38）；MvPersistentCache MAX_ENTRIES=5000（:31）✅ |
| cachedCandidates（歌词候选）泄漏 | loadLyricsForCurrentSong 每次切歌 clearCachedCandidates（MainViewModel:4070）✅ |
| PlaybackService 生命周期 | onDestroy 顺序正确（scope.cancel → PM.release → session.release → stopForeground :234-258）；onTaskRemoved 保留播放符合媒体应用预期 ✅ |
| MainActivity 退出 runBlocking | M-1 已加 1.5s withTimeout，退出路径可接受 ✅ |
| RemoteControlServer 备份恢复 runBlocking | MVM:2995 在 NanoHTTPD worker 线程桥接（非主线程），且有明确注释 ✅ |
| NanoHTTPD 三服务端口冲突 | 遥控 18082 / 备份 18081 / 模型 18083，H-4 已修 ✅ |
| DataStore 多实例 | AppPreferences DCL 单例（:52-57），注释明确 ✅ |
| PlaybackService startForeground 时机 | 延迟到 onIsPlayingChanged 首次播放（:64），从 Activity startService 进入（MainActivity:334），无后台启动限制 ✅ |

---

### 回归测试策略

每个R项完成后需验证：

1. **编译通过**：`./gradlew assembleDebug` 无错误（注意：CI 绿灯仅代表可编译，见 AGENTS.md）
2. **功能回归**：
   - 服务器连接/断开（五种后端各过一遍核心路径）
   - 曲库浏览（专辑/艺术家/年份/流派/歌曲）
   - 搜索（NAS/网络/聚合）
   - 播放控制（播放/暂停/上下曲/模式切换）
   - 人声分离（快速DSP/高质量ONNX）
   - K歌模式
   - MV模式
   - 天气电台（含无 NAS 连接场景——验证 manager 可空语义）
   - 下载管理
   - 备份导入/导出
   - 设置页面各选项（改设置后立即生效——验证 R-7 内存镜像方案）
   - 语言设置（冷启动后生效——验证 getLanguageSync 改造）
3. **TV焦点导航**：D-Pad在各Screen/Section间正常工作
4. **内存无泄漏**：反复切换Screen后内存稳定
5. **后端切换压力**：连续切换后端数次后播放/封面请求仍正常（验证 R-6 共享资源未被 close 误伤）

---

## 二次审阅新发现（N 系列）

> 以下问题在 v1.5 二次深度代码审阅中发现，是对 R/F 系列的补充。R 系列关注架构级拆分，F 系列关注功能级修复，N 系列关注**已实施重构后仍残留的结构性问题**——它们不影响运行时正确性，但会持续增加维护成本。

### N-1 MainViewModel 兼容转发层膨胀

| 属性 | 值 |
|------|------|
| **优先级** | 🟡 P1（可维护性） |
| **预估工期** | 1-2 天 |
| **状态** | 待实施 |
| **关联** | R-1（ViewModel 拆分） |

**现状证据**（二次审阅实测）：

- [`MainViewModel.kt`](app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt) 仍 3186 行（R-1 目标 ≤600 行）
- 兼容转发层结构：`_weatherRadioVM` / `_serverVM` / `_searchVM` / `_netVM` / `_downloadVM` / `_vocalVM` / `_mvVM` 等私有子 VM 实例
- 属性委托暴露只读 StateFlow（如 `weatherData get() = _weatherRadioVM.weatherData`）
- 方法转发（如 `fetchWeather() = _weatherRadioVM.fetchWeather()`）
- 转发层约占 350 行，且随子 VM 增多持续膨胀

**问题分析**：

R-1 拆分已将业务逻辑下沉至 13 个子 VM，但 MainViewModel 保留了完整的兼容转发层——每个子 VM 的公开 StateFlow 和方法都在 MainViewModel 有一对一的委托转发。这保证了 UI 层零改动兼容，但代价是 MainViewModel 行数无法降至目标值，且每次新增子 VM 都需同步增加转发代码。

**修复方案**：

1. **分阶段移除转发层**（推荐）：
   - Phase 1：UI 层逐步改为直接引用子 VM（经手动 DI——AppRoot 已持有 NasMusicApp 的容器引用，可将子 VM 经 `viewModel()` 工厂从 MainViewModel 容器获取，**不引入 Hilt**，与 R-8 定案一致）
   - Phase 2：每完成一个 Screen 的迁移，删除 MainViewModel 中对应转发代码
   - Phase 3：MainViewModel 最终仅保留跨域事件路由（ViewModelEvents）和真正需要聚合的状态

2. **保留转发层但压缩体积**（保守）：
   - 使用 `by` 委托 + 泛型工厂方法减少样板代码
   - 将转发代码移至独立文件 `MainViewModelForwarding.kt`（extension function）

**DoD**：
- [ ] MainViewModel ≤ 600 行（含事件路由）
- [ ] 无一对一属性委托转发（`xxx get() = _xxxVM.xxx`）
- [ ] UI 层直接引用子 VM 的 Screen ≥ 50%
- [ ] 编译通过 + 全功能回归测试通过

---

### N-2 DomainPrefs 多类单文件

| 属性 | 值 |
|------|------|
| **优先级** | 🟢 P2（代码规范） |
| **预估工期** | 0.5 天 |
| **状态** | 待实施 |
| **关联** | R-4（Prefs 门面模式） |

**现状证据**（二次审阅实测）：

- [`DomainPrefs.kt`](app/src/main/java/com/nasmusic/tv/data/prefs/DomainPrefs.kt) 179 行，包含 10 个子 Prefs 类：
  - `NetworkMusicPrefs`(24行) / `BaiduPrefs`(28行) / `DownloadPrefs`(12行) / `WeatherPrefs`(6行) / `VisualizerPrefs`(20行) / `HistoryPrefs`(19行) / `PlaylistPrefs`(12行) / `QueuePrefs`(6行) / `LanguagePrefs`(7行) / `BackupPrefs`(4行)
- 所有类签名：`internal class XxxPrefs internal constructor(private val prefs: AppPreferences)`
- 键不迁移，只搬访问器

**问题分析**：

R-4 已将 13 个子 Prefs 按领域拆分，但其中 10 个仍挤在 `DomainPrefs.kt` 单文件中。Kotlin 并无「一个文件一个类」的硬性惯例（多小类单文件是可接受的口味问题），此项为**可选规范项**，收益主要是降低 Git 合并冲突面与文件定位成本。独立文件的 3 个（LyricsPrefs / PlayerPrefs / ServerPrefs）行数也较少（17-24行），但至少遵循了单文件单类原则。

**修复方案**：

1. 将 DomainPrefs.kt 中 10 个类各拆至独立文件：
   - `NetworkMusicPrefs.kt` / `BaiduPrefs.kt` / `DownloadPrefs.kt` / `WeatherPrefs.kt` / `VisualizerPrefs.kt` / `HistoryPrefs.kt` / `PlaylistPrefs.kt` / `QueuePrefs.kt` / `LanguagePrefs.kt` / `BackupPrefs.kt`
2. 保留 `DomainPrefs.kt` 作为包级索引文件（仅含 `// 此文件已拆分，各类见同包下独立文件` 注释），或直接删除
3. 更新所有 import 语句（Kotlin 类名未变，仅文件位置变化，IDE 自动重构）

**DoD**：
- [ ] 每个子 Prefs 类独占一个 `.kt` 文件
- [ ] `DomainPrefs.kt` 已删除或仅保留索引注释
- [ ] 全量 import 自动重构无遗漏
- [ ] 编译通过

---

### N-3 AppRoot 123 处 collectAsState 上帝 Composable

| 属性 | 值 |
|------|------|
| **优先级** | 🟡 P1（UI 性能 / 可维护性） |
| **预估工期** | 3-5 天 |
| **状态** | 待实施 |
| **关联** | F-2（AppRoot 重组优化） |

**现状证据**（二次审阅实测）：

- [`AppRoot.kt`](app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt) 含 123 处 `collectAsState` 调用
- F-2 已将 `progress` / `duration` 从顶层下沉至播放页分支（避免每秒驱动全树重组）
- 但 `isPlaying` 等高频变化状态仍在顶层收集
- 123 处订阅意味着 AppRoot 感知了几乎所有应用状态，任何状态变化都可能触发重组

**问题分析**：

F-2 修复了最严重的性能问题（progress/duration 每秒重组），但 AppRoot 仍然是「上帝 Composable」——它承担了过多状态订阅职责。理想架构中，AppRoot 应只负责顶级导航和主题，各 Screen/Section 应自行订阅所需状态。

**修复方案**：

1. **状态下沉**（核心策略）：
   - 将各 Screen 专属的 `collectAsState` 移至对应 Screen 内部
   - AppRoot 仅保留：导航状态、主题状态、全局弹窗状态
   - 目标：AppRoot 的 `collectAsState` ≤ 20 处

2. **分批迁移**（降低风险）：
   - Batch 1：播放相关状态 → `PlayerScreen` / `MiniPlayer`
   - Batch 2：搜索相关状态 → `SearchScreen`
   - Batch 3：设置相关状态 → `SettingsScreen`
   - Batch 4：库相关状态 → `LibraryScreen`

3. **验证手段**：
   - 每批迁移后使用 Compose Compiler Metrics 检查重组范围
   - 确认无「顶层状态变化 → 全树重组」的情况

**DoD**：
- [ ] AppRoot 的 `collectAsState` ≤ 20 处
- [ ] 各 Screen 自行订阅其专属状态
- [ ] Compose Compiler Metrics 确认无全树重组
- [ ] 编译通过 + TV 焦点导航回归通过

---

### N-4 PlayerManager 62.6KB 未拆分

| 属性 | 值 |
|------|------|
| **优先级** | 🟡 P1（播放器可维护性） |
| **预估工期** | 3-5 天 |
| **状态** | 待实施 |
| **关联** | R-5（人声分离提取）——v1.6 修正：v1.5 原文误标 R-2，原计划的 R-2 是 SettingsScreen 拆分 |

**现状证据**（二次审阅实测，v1.6 复核通过）：

- [`PlayerManager.kt`](app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt) 62.6KB（64117 字节，v1.6 实测一致），仍为单体文件，70 个 fun 声明（v1.6 实测一致）
- PlayerManager 承担了播放核心、队列管理、媒体会话、音频焦点、耳机断连处理等多重职责

> ⚠️ **v1.6 定案标注**：R-5 实施时已定案「HQ 编排保留 PlayerManager **防接口爆炸**」——PlayerCore/PlayerQueue/PlayerMediaSession/PlayerAudioFocus 四阶段拆分（v1.5 原文称出自"R-2 原方案"，该说法有误，原计划并无此方案）与该定案的回归风险逻辑相同。**本项降级为待所有者决策项**，未经所有者重新确认不得启动；启动前须评估播放核心回归成本（播放/队列/媒体会话/音频焦点全路径手测）。

**问题分析**：

PlayerManager 是仅次于 MainViewModel 的第二大单体文件。R-1 优先处理了 ViewModel 拆分，R-2 的 PlayerManager 拆分尚未排期。62.6KB 的文件在 Code Review 和合并冲突中都是痛点，且其多职责耦合增加了播放器相关 bug 的定位难度。

**修复方案**（待所有者决策后方可启动）：

四阶段拆分（v1.5 原文误称"R-2 原方案"，实为 v1.5 新提）：

1. **Phase 1 — PlayerCore**：播放核心（ExoPlayer 实例管理、prepare/play/pause/seek/release）
2. **Phase 2 — PlayerQueue**：队列管理（播放列表、上下曲、模式切换、shuffle/repeat）
3. **Phase 3 — PlayerMediaSession**：媒体会话（MediaSession 连接、通知、蓝牙/耳机事件）
4. **Phase 4 — PlayerAudioFocus**：音频焦点管理

每个 Phase 独立 PR，确保播放功能回归通过后再进入下一 Phase。

**DoD**：
- [ ] PlayerManager.kt ≤ 15KB（仅保留协调逻辑）
- [ ] PlayerCore / PlayerQueue / PlayerMediaSession / PlayerAudioFocus 各自独立文件
- [ ] 各子组件通过接口解耦（参考 VocalSeparationController 的 PlayerAdapter 窄接口模式）
- [ ] 编译通过 + 播放全功能回归通过

---

### N-5 BackendAdapter 巨型文件未拆

| 属性 | 值 |
|------|------|
| **优先级** | 🟢 P2（后端层可维护性） |
| **预估工期** | 3-5 天 |
| **状态** | 待实施 |
| **关联** | R-10（后端适配器拆分）——v1.6 修正：v1.5 原文误标 R-6；R-6 仅做 OkHttp 资源池化，适配器注册机制（BackendRegistry）系重构前既有 |

**现状证据**（二次审阅实测，v1.6 复核通过）：

- BackendAdapter 实现类仍为巨型文件（JellyfinAdapter 55.7KB/1218 行、NavidromeAdapter 41.6KB/902 行、SubsonicAdapter 37.6KB/833 行、FeiniuAdapter 28.3KB/663 行、DaoliyuAdapter 24.0KB/559 行——v1.6 实测一致）
- 每个适配器包含：认证、曲库浏览、搜索、封面、流媒体 URL 等全部接口实现

> ⚠️ **v1.6 定案标注**：R-10 已定案——Subsonic 公共层（SubsonicRestClient）已完成，**JellyfinAdapter 经所有者确认保持原状不实施域拆分，勿再作为待办启动**。且 NavidromeAdapter 的 902 行中大部分是对 SubsonicRestClient 的委托代码（v1.5 原文忽略此点）。本项仅剩理论上的拆分空间，**属已定案的关闭项，非待办**。

**问题分析**：

R-6 解决了适配器间的资源共享和注册机制问题，但单个适配器仍然是「上帝类」——一个文件实现 BackendAdapter 的全部接口方法。当新增后端类型时，需要在单个文件中实现所有接口方法，增加了出错概率和 Code Review 难度。

**修复方案**：

1. **按接口能力拆分**（推荐）：
   - `AuthCapability` — 认证相关方法
   - `BrowseCapability` — 曲库浏览方法
   - `SearchCapability` — 搜索方法
   - `CoverCapability` — 封面获取方法
   - `StreamCapability` — 流媒体 URL 方法
   - 每个适配器通过组合（composition）而非继承实现各 Capability

2. **保持 BackendAdapter 接口不变**：
   - 外部调用方无感知
   - 适配器内部实现从单文件拆为多文件组合

**DoD**：
- [ ] 每个适配器单文件 ≤ 500 行
- [ ] Capability 接口定义清晰，各适配器按能力组合
- [ ] BackendAdapter 顶层接口不变，外部调用方零改动
- [ ] 编译通过 + 五种后端核心路径回归通过

---

## W0 接口冻结清单（开发前必读）

> 本章节是 v1.3 新增的**开工前置交付物**。按 R-1「v1.2 实施注意」要求，Player 与 Search 的交互契约在此冻结；所有子 ViewModel 的包路径、文件名、构造签名在动工前逐项勾选确认，拆分期间任何接口变更必须回写本章节并注明日期，否则视为未冻结。

### 1. 子 ViewModel 落位表（包路径 + 文件名 + 构造签名骨架）

> 统一放置于 `app/src/main/java/com/nasmusic/tv/ui/viewmodel/` 下（与 MainViewModel 同包，避免 import 面扩散；不新建子包，保持与现有 data/model 平铺风格一致）。

| 子 ViewModel | 文件名 | 构造签名骨架（冻结） | 承接 MainViewModel 职责域 |
|---|---|---|---|
| NavigationViewModel | NavigationViewModel.kt | `(app: Application)` | 导航/屏幕状态（currentScreen、返回栈） |
| ServerViewModel | ServerViewModel.kt | `(app: Application, backendRegistry: BackendRegistry)` | 连接/断开/版本（AppPreferences 经 NasMusicApp 取） |
| LibraryViewModel | LibraryViewModel.kt | `(app: Application, backendRegistry: BackendRegistry, localMusicRepository: LocalMusicRepository, baiduIndexCache: BaiduFileIndexCache)` | 曲库浏览/详情数据 |
| SearchViewModel | SearchViewModel.kt | `(app: Application, backendRegistry: BackendRegistry, searchAggregator: SearchAggregator)` | NAS+网络+聚合搜索 |
| NetworkMusicViewModel | NetworkMusicViewModel.kt | `(app: Application)`（百度组件经 `get() = nasMusicApp.xxx` 属性委托，与现状一致） | 网络音乐/百度网盘 |
| WeatherRadioViewModel | WeatherRadioViewModel.kt | `(app: Application, playerManager: PlayerManager, networkMusicManager: NetworkMusicManager)`（WeatherApi/WeatherRadioManager 内部延迟创建，**保持可空语义**） | 天气电台 |
| PlayerViewModel | PlayerViewModel.kt | `(app: Application, playerManager: PlayerManager)` | 播放控制/队列 |
| VocalSeparationViewModel | VocalSeparationViewModel.kt | `(app: Application, playerManager: PlayerManager)` | 人声分离/K歌 |
| DownloadViewModel | DownloadViewModel.kt | `(app: Application, songDownloadManager: SongDownloadManager, modelDownloadManager: ModelDownloadManager, autoDownloadController: AutoDownloadController)` | 下载/模型管理 |
| PlaylistViewModel | PlaylistViewModel.kt | `(app: Application)`（AppPreferences 经 NasMusicApp 取） | 本地歌单 CRUD |
| PlayHistoryViewModel | PlayHistoryViewModel.kt | `(app: Application)` | 播放记录/统计 |
| BackupViewModel | BackupViewModel.kt | `(app: Application)` | 备份导入/导出 |
| MvSearchViewModel | MvSearchViewModel.kt | `(app: Application, mvSearchManager: MvSearchManager, playerManager: PlayerManager)` | MV 搜索/模式切换 |

**冻结检查**：

- [x] 13 个文件名与包路径确认（含 MainViewModel 保留为协调者）✅ 2026-09-09 全部落位
- [x] 各构造签名评审通过（依赖方向：子 VM 不得互相持有构造期引用，跨域通信只走事件）✅ 2026-09-09（迁移期以 init 注入回调实现，未引入构造期相互引用）
- [x] WeatherRadioViewModel 不在构造期创建 WeatherRadioManager（保留 :159 可空延迟语义）✅ 2026-09-09
- [x] NetworkMusicViewModel 保留百度属性委托（:4847-4849 形式）✅ 2026-09-09

### 2. 跨 ViewModel 事件契约（sealed class 全量冻结）

> 新建 `app/src/main/java/com/nasmusic/tv/ui/viewmodel/ViewModelEvents.kt`，集中定义全部跨域事件。**MainViewModel 作为唯一事件路由器**（init 中 collect 各子 VM 事件并转发给目标子 VM），子 VM 之间不直接互调。

```kotlin
/** 跨域事件契约（W0 冻结版）。新增事件必须回写本文件与文档本节。 */
sealed interface SearchEvent {
    data class AddToQueue(val song: Song) : SearchEvent          // 搜索结果加入播放队列 → PlayerViewModel
    data class AddAllToQueue(val songs: List<Song>) : SearchEvent
}

sealed interface LibraryEvent {
    data class OpenAlbumDetail(val album: Album) : LibraryEvent  // 详情导航 → NavigationViewModel
    data class OpenArtistDetail(val artist: Artist) : LibraryEvent
    data class PlayRequested(val songs: List<Song>, val startIndex: Int) : LibraryEvent  // 浏览页播放入口 → PlayerViewModel
}

sealed interface WeatherRadioEvent {
    data class PlayRequested(val songs: List<Song>) : WeatherRadioEvent  // 电台播放 → PlayerViewModel
}

sealed interface PlayerEvent {
    data class SongStarted(val song: Song) : PlayerEvent        // 播放记录上报 → PlayHistoryViewModel
    data class ModeChanged(val playMode: PlayMode) : PlayerEvent // 播放模式持久化 → AppPreferences（经 PlayerViewModel 内部）
}

sealed interface NetworkMusicEvent {
    data class PlayRequested(val song: Song) : NetworkMusicEvent // 网络歌曲播放（需先解析流地址）→ PlayerViewModel
    data class PlayBatchRequested(val songs: List<Song>, val startIndex: Int) : NetworkMusicEvent
}
```

**冻结检查**：

- [ ] 事件类型清单评审通过（实现中如发现缺失，先补契约再写代码，禁止子 VM 间直接方法调用绕过事件）
- [ ] 事件流向图成文（谁 emit / 谁路由 / 谁消费），随本文件提交
- [ ] RemoteCallbacks（手机遥控）回调全部由 MainViewModel 收口后按事件路由，不经子 VM 直连

### 3. State/Actions 分组样板（R-2/R-3 Section 签名统一规范）

> 所有新拆出的 Section/Tab Composable 遵循「2 参数」签名；State 与 Actions data class 定义在各 Section 文件顶部。AppRoot 负责从子 VM 构造 State/Actions。

```kotlin
// 文件头示例：ui/screens/settings/ServerSettingsSection.kt

data class ServerSettingsState(
    val baseUrl: String,
    val backendType: Int,
    val connecting: Boolean,
    val connectMessage: String?,
)

data class ServerSettingsActions(
    val onSave: (baseUrl: String, backendType: Int) -> Unit,
    val onTestConnection: (baseUrl: String, backendType: Int) -> Unit,
    val onDismissMessage: () -> Unit,
)

@Composable
fun ServerSettingsSection(state: ServerSettingsState, actions: ServerSettingsActions) { ... }
```

**冻结检查**：

- [ ] Section 签名恒为 `(state, actions)` 两参数
- [ ] Actions 内回调一律「参数自足」（所需上下文随回调传入，不闭包捕获外部可变状态）
- [ ] 同名私有组件冲突已排期处理（SettingsScreen.InfoRow vs SongInfoPanel.InfoRow:146 → 迁移时统一重命名为 `SettingsInfoRow`）

### 4. AppRoot 重组约定

- AppRoot 按域分组订阅：`val weather by weatherVM.weatherData.collectAsState()` 等分组注释块（`// region: Weather`），保持 123 处订阅可按域检索。
- 迁移期允许 `viewModel.searchVM` 形式的过渡访问器（MainViewModel 暴露子 VM 只读属性），R-1 全部完成后收敛为 AppRoot 直接持有各子 VM。

---

## 开发实施规约

### 1. Git 分支与提交（对齐 `.opencode/rules.md`）

- **总分支**：`refactor/r1-viewmodel-split`（R-1 专用）；后续每个 R 项独立分支 `refactor/r2-settings-split`、`refactor/r4-prefs-split` 等。当前本地在 `main` 且领先 origin 4 个提交——**先推送 main 再切分支**，避免重构混入未发布提交。
- **提交前缀**：一律 `refactor:`（如 `refactor: extract WeatherRadioViewModel from MainViewModel`）；纯文档更新用 `docs:`。
- **提交节拍（硬性要求）**：每拆出一个域 → `assembleDebug` 通过 → `git status`/`git diff` 核对范围 → 只 stage 相关文件 → 单独提交。**禁止跨域囤积改动**；单次提交理想 ≤800 行 diff。
- **合并时机**：每完成一个 R 项并完成手测回归后合回 `dev`；`main` 只接收经过完整回归的批次。

### 2. 每步验证命令（Windows，本机实测路径）

```powershell
# 每次提交前
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew.bat assembleDebug

# 相关单测（R-7/R-4 触及 prefs 逻辑时）
./gradlew.bat test --tests "*CloudDriveConfigTest" --tests "*UiStateTest"

# 安装到电视手测（每 R 项完成时）
& "C:\Users\hxzha\AppData\Local\Android\Android\Sdk\platform-tools\adb.exe" connect 192.168.0.114:5555
& "C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 192.168.0.114:5555 install -r app\build\outputs\apk\debug\app-debug.apk
```

> 注意：gradle wrapper 指向本地 `file:///C:/Users/hxzha/Downloads/gradle-9.5.0-bin.zip`（离线构建，勿改）；CI 绿灯仅代表可编译。

### 3. 进度标记规范

- 每完成一个子域（如 WeatherRadioViewModel 提取并回归通过），在本方案文档对应位置将 `[ ]` 改为 `[x]`，并在行尾追加 `✅ YYYY-MM-DD`。
- R 项整体完成时更新「问题总览」表的状态列（新增「状态」语义复用图例 ✅/🔶）。
- 中断恢复：每次开工先读本方案「实施路线图」+ 问题总览状态列，确认上一完成点。

### 4. 记录同步（对齐 `.opencode/rules.md`）

- 每个 R 项合入 `dev` 时：更新 `CHANGELOG.md`（`refactor` 条目）+ `docs/technical-overview.md` §10 修改记录（仅已验证变更）。
- 涉及行为变更的项（R-6 资源池化、R-7 runBlocking 修复）必须在 CHANGELOG 写明用户可感知影响（后端切换行为、ANR 消除）。

### 5. 禁改清单（重构期间冻结）

以下既有约定**重构期间不得顺手修改**，发现疑似问题单独记录、单独评估：

- gradle wrapper 的本地 file:// 分发 URL
- `AndroidManifest.xml` 的 `usesCleartextTraffic=true` 与 Leanback required
- TinyPinyin 依赖（min SDK 22 约束，勿换 ICU）
- `proguard-rules.pro` 现有 `-keep` 规则（新增序列化模型时只增不减）
- EncodingUtils 的 GBK 兜底逻辑（R-10 拆分适配器时逐字保留）
- 连接页默认预填的局域网 IP

---

## 各 R 项 Definition of Done

> 判定标准：**全部勾选才算完成**。「编译」指 assembleDebug；「回归」指按「回归测试策略」执行相关子集；「手测」指 adb 安装到电视实测。

| R 项 | Definition of Done（可验证） | 自动化测试边界 |
|---|---|---|
| R-1 | ① 13 个子 VM 文件落位且构造签名与 W0 冻结一致 ② MainViewModel ≤600 行且不再持有业务 StateFlow ③ AppRoot 无未分组 collectAsState ④ 全部回归项手测通过 ⑤ ViewModelEvents.kt 与实际事件一一对应 | 新增子 VM 不写单测（依赖 Android 运行时）；PlayerViewModel 队列逻辑若可抽纯函数则补 Robolectric 测试 |
| R-2 | ① settings/ 目录 16 Section + SettingsComponents.kt 落位 ② 每个 Section 恰为 (state, actions) 签名 ③ 主文件 ≤250 行 ④ 设置页逐项手测（含改设置立即生效）⑤ D-Pad 全 Section 焦点遍历正常 | 无新单测；`SettingsInfoRow` 重命名后编译即验证 |
| R-3 | ① library/browse/ 下文件落位且详情页复用现役 AlbumDetailScreen/ArtistDetailScreen ② LibraryScreen.kt ≤300 行 ③ Tab 切换/分页/搜索/详情导航手测通过 ④ 现有 4 个网络音乐 Tab 无回归 | 无新单测 |
| R-4 | ① 12 个子 Prefs + 门面落位 ② 旧 API 全部 @Deprecated 且编译无调用残留 ③ NasMusicApp lambda provider 全部迁移 ④ grep 无裸 `appPreferences.getXxxSync()` 旧路径调用 | CloudDriveConfigTest 等 prefs 相关既有单测全绿；可为 ServerPrefs 补序列化往返测试 |
| R-5 | ① VocalSeparationController 独立编译 ② SeparationMode 迁至 player 层且无 player→data.prefs 反向依赖 ③ 快速 DSP / ONNX / K 歌 / 切歌重置手测通过 | 若切歌状态机可抽纯函数则补测试；否则仅手测 |
| R-6 | ① 5 个适配器 close() 均无 shutdown/evictAll ② 共享池/Dispatcher 注入 ③ 连续切换后端 ≥5 次后播放/封面正常（手测）④ testConnection 临时适配器路径验证 | 无单测（网络层）；手测为准 |
| R-7 | ① getLanguageSync 主线程 IO 消除（冷启动语言正确）② grep `runBlocking(` 在 Main 协程语境零残留 ③ 内存镜像方案：设置页改源/端点立即生效（手测）④ 保留项均有 @WorkerThread 注解 | 补一个镜像一致性 Robolectric 测试（写 DataStore 后镜像 ≤1s 内更新） |
| R-8 | （可选）试点组件经 Hilt 注入且与手动 DI 单例唯一性验证 | 暂不设置（非必达项） |
| R-9 | ① 重复 import 删除 ② 修复标记表补全登记 | 编译即验证 |
| R-10 | ① Subsonic 公共层提取且 3 个 Subsonic 系适配器回归通过 ② JellyfinAdapter ≤400 行 ③ GBK 兜底行为不回退（中文名曲库手测）④ 与 R-6 合并验证 | SubsonicAdapterTest 既有单测保持全绿并随公共层迁移更新 |
| **F-1** | ① 5 适配器 w/e 日志全部过 sanitizeUrl ② BackendRegistry:48 username 脱敏 ③ release 包 logcat 无凭证残留（adb logcat 抓取验证） | 新增 SanitizeUrlTest（纯函数，进 app/src/test/） |
| **F-2** | ① progress/duration 收集下沉后 Library 页播放期间不每秒重组（用 Layout Inspector 或日志计数验证）② 播放进度/顶栏显示不回归 | 无单测；手测 + 重组观察 |
| **F-3** | ① LyricsManager baseUrl 改 lambda/单例 ② 设置页改歌词源即时生效 ③ 全库仅一个 LyricsManager 实例（R-1 拆分后 grep 验证构造点唯一） | 无 |
| **F-4/F-5** | （可选项）scope 统一注入 applicationScope；saveIndex 加互斥 | 单例化后并发 put/exportAll 压测无交错（手测备份导入导出） |
| **F-6** | 双网卡设备遥控二维码 IP 可达（如有测试环境） | 无 |
| **F-7** | key 加密落盘 + 旧值迁移成功 | CryptoUtils 既有模式复用 |
| **F-8** | 待所有者决策后再定 | 无 |

---

## 附录

### A. 审阅覆盖文件清单

| 包 | 文件 | 大小 |
|----|------|------|
| 根 | NasMusicApp.kt | 19.8KB |
| 根 | NasMusicVersion.kt | - |
| backend | BackendAdapter.kt | 6.5KB |
| backend | BackendRegistry.kt | 6.9KB |
| backend | SearchAggregator.kt | 17.8KB（413行） |
| backend/impl | JellyfinAdapter.kt | 54KB（1215行） |
| backend/impl | NavidromeAdapter.kt | 41.7KB（932行） |
| backend/impl | SubsonicAdapter.kt | - |
| backend/impl | DaoliyuAdapter.kt | - |
| backend/impl | FeiniuAdapter.kt | - |
| backend/network | NetworkMusicManager.kt | - |
| backend/network | MetingApiService.kt | - |
| backend/network/baidu | BaiduOAuthClient.kt | 14.8KB |
| backend/network/baidu | BaiduPanApi.kt | - |
| backend/local | LocalMusicRepository.kt | - |
| backend/local | MusicScanner.kt | - |
| backend/download | SongDownloadManager.kt | 21.2KB |
| backend/export | SongExporter.kt | - |
| backend/weather | WeatherRadioManager.kt | - |
| player | PlayerManager.kt | 62.6KB（1510行） |
| player | PlaybackService.kt | - |
| player | DemucsSeparator.kt | 23.5KB |
| player | SpectrumAnalyzer.kt | - |
| player | VocalRemovalProcessor.kt | - |
| lyrics | LyricsManager.kt | - |
| net | RemoteControlServer.kt | - |
| ui | MainActivity.kt | 403行 |
| ui/components | AppRoot.kt | 1079行（123处collectAsState） |
| ui/viewmodel | MainViewModel.kt | 3186行（v1.5 实测，含兼容转发层约350行） |
| ui/screens | NowPlayingScreen.kt | - |
| ui/screens | HomeScreen.kt | - |
| ui/screens | LibraryScreen.kt | 76.4KB（1695行） |
| ui/screens | SettingsScreen.kt | 135.9KB（2529行，13个Composable） |
| ui/screens | MineScreen.kt | - |
| ui/screens | ServerConnectScreen.kt | - |
| ui/screens/library/ | DiscoverTab/JamendoTab/RadioTab/SearchTab | v1.1 补录 |
| ui/screens/netdisk/ | NetdiskScreen/BaiduAuthDialog | v1.1 补录 |
| ui/components | KaraokePlaybackScreen.kt | - |
| ui/theme | Theme.kt | - |
| data/model | Song.kt | - |
| data/model | SourceIdentifier.kt | - |
| data/model | AppSettings.kt | - |
| data/prefs | AppPreferences.kt | 58KB（1403行，约54个键） |

### B. 代码质量评分

> v1.5 更新：基于二次深度审阅对 R-1~R-10 / F-1~F-7 实施结果的逐项验证，上调部分维度评分。

| 维度 | v1.4 评分 | v1.5 评分 | 变化 | 说明 |
|------|----------|----------|------|------|
| 架构设计 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐☆ | ↑ | ViewModel 拆分 13 子 VM + ViewModelEvents 事件契约 + ProviderMirror 内存镜像 + BackendRegistry 共享资源池，架构清晰度提升 |
| 代码组织 | ⭐⭐⭐ | ⭐⭐⭐☆ | ↑ | 子 VM / 子 Prefs 已拆分，但 MainViewModel 转发层膨胀(N-1)、DomainPrefs 多类单文件(N-2)、PlayerManager 未拆(N-4) 仍拖累 |
| 错误处理 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | → | 各适配器异常捕获完善，无变化 |
| 资源管理 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐☆ | ↑ | F-4 scope 统一注入 + F-5 缓存写互斥 + R-6 OkHttp 共享资源池，资源安全度提升 |
| 安全性 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐☆ | ↑ | F-1 日志凭证脱敏 + F-7 天气 Key 加密存储，安全基线提升 |
| 可测试性 | ⭐⭐ | ⭐⭐ | → | 手动 DI 仍为主因，子 VM 拆分对可测试性改善有限（需 DI 框架迁移） |
| 可维护性 | ⭐⭐⭐ | ⭐⭐⭐☆ | ↑ | F 系列修复 + R 系列拆分改善可维护性，但 N 系列残留问题（AppRoot 上帝 Composable / PlayerManager 未拆）仍需跟进 |

### C. v1.1 审阅修订记录

| 修订项 | v1.0 原文 | v1.1 修订 | 依据 |
|--------|----------|----------|------|
| R-7 方法清单 | 含 `getServerConfigSync()`/`getPlayModeSync()`/`saveCloudDriveConfigBlocking()` | 删除虚构项，按 14 处真实调用重写并附线程语境 | grep 核实：前两者不存在，实为 `saveCloudDriveConfigSync`(:1187) |
| R-7 难点 | 未识别 lambda provider 注入 | 新增第三类：NasMusicApp.kt:123-222 的 `() -> String` 注入无法直接 suspend | NasMusicApp.kt 实测 |
| R-7 新增高危点 | 未提及 | `getLanguageSync` 在 attachBaseContext（主线程）、`getWeatherApiKeySync` 在 Main 协程内 | MainActivity.kt:92 / MainViewModel.kt:476 |
| 架构决策 | 缺失，R-1 方案B 与 R-2 示例矛盾 | 新增章节，统一为「保持状态上提，分两层演进」 | AppRoot.kt 123 处 collectAsState、Screen 实际签名 |
| R-2 Composable 数 | 25 | 13 | @Composable 注解计数 |
| R-2 共享组件 | 「SettingSwitch 已存在于 ui/components」 | 更正：均为 SettingsScreen 私有，需迁移并处理 InfoRow 同名冲突 | SettingsScreen.kt:2168-2499 / SongInfoPanel.kt:146 |
| R-3 目录 | 未提及现有 library/ | 补录 4 个网络音乐 Tab，新增 browse/ 子包方案 | 目录实测 |
| R-3 锚点 | LibraryScreen:129 | 更正为 :140 | 129 行为 adaptiveColumns |
| R-5 方法清单 | 8 个方法 | 补 `switchToOriginal()`(:493)；更正 SeparationMode 为 AppPreferences 嵌套枚举 | PlayerManager.kt 实测 |
| R-6 陷阱 | 未提及 | 补充共享后 close() 禁止 shutdown/evictAll（5 适配器逐一改） | JellyfinAdapter.close():1024 等实测 |
| R-10 | 无 | 新增后端适配器拆分项 | 附录自列数据 54KB/41.7KB 却无对应 R 项 |
| 笔误 | `SharedSharedFlow` | `SharedFlow` | 文档 :173 |
| 量化 | 偏好键 60+ / MainViewModel ~300行 / runBlocking 15处 | 约54个 / ~500行 / 14处调用+1 import | 各项实测 |

### D. v1.2 审阅修订记录（第二轮独立审阅，全部量化断言逐项对照源码复核）

| # | 修订项 | 修订前 | 修订后 | 依据 |
|--|--------|--------|--------|------|
| 8 | 颗粒度与分批策略 | 未提示拆满风险 | 新增「12 子 VM 分批验证 + W0 冻结 Player/Search 交互契约」实施注意 | R-1 通信模式章节 |
| 9 | Section 签名约定 | 未约定参数组织方式 | 新增 R-2 第 5 条：State/Actions data class 分组签名，禁止裸参数透传 | R-2/R-3 检查清单同步更新 |
| 10 | R-3 详情页归属 | 提出待确认 | 已核实定案：复用现役 `AlbumDetailScreen`/`ArtistDetailScreen`（AppRoot.kt:881 调用），不新写 | AppRoot.kt 实测 |
| 11 | R-7 调用点口径 | 14 处（逻辑读法） | 澄清为 11 处物理 `runBlocking(` 调用点（grep 总数 15 = 11+1import+2注释+1标签），实施按物理位置复查 | AppPreferences.kt 实测 |
| 12 | R-8 地位 | P2 按需安排 | 明确为「可选项，非路线图必达项」，附试点建议 | 第二轮审阅评估 |
| 13 | 工时缓冲 | 已上调但无缓冲 | 补充 TV 手测回归成本，建议再留 30% 缓冲 | 实施路线图章节 |

### E. v1.3 修订记录（开发前最终完善）

| # | 修订项 | 内容 | 位置 |
|--|--------|------|------|
| 14 | W0 接口冻结清单 | 新增章节：13 个子 VM 包路径/文件名/构造签名骨架、ViewModelEvents.kt 跨域事件契约（sealed interface 全量定义）、State/Actions 样板、AppRoot 重组约定 | 实施路线图之后 |
| 15 | 开发实施规约 | 新增章节：Git 分支/提交节拍（每域一提交，禁跨域囤积）、Windows 验证命令实测路径、进度标记规范、记录同步（CHANGELOG + technical-overview §10）、禁改清单（wrapper file:// / cleartext / TinyPinyin / proguard / GBK 兜底等） | 实施路线图之后 |
| 16 | 各 R 项 DoD | 新增章节：10 个 R 项的可验证完成标准 + 自动化测试边界（依托 app/src/test/ 既有 Robolectric 套件） | 实施路线图之后 |
| 17 | R-1 目标架构 | 补 13 个子 VM 文件名与 ViewModelEvents.kt 落位；明确同包不建子包 | R-1 §1.2 |
| 18 | R-4 键归属映射 | 新增 12 个子 Prefs 的键域对照表；明确「键不迁移只搬访问器、DataStore 文件不拆分」 | R-4 §4.2 |
| 19 | R-6 分步顺序 | 新增 4 步独立提交的实施顺序（注入与删 shutdown 必须同提交） | R-6 方案A |
| 20 | R-7 落地骨架 | 新增第一类（语言 SharedPreferences 镜像双写）与第三类（@Volatile 内存镜像）的代码骨架 | R-7 §7.2 |
| 21 | 路线图 W0 | W0 产出明确为「决策记录 + 冻结清单勾选」 | 实施路线图 |

### F. v1.4 修订记录（深度全库代码审阅）

| # | 修订项 | 内容 | 位置 |
|--|--------|------|------|
| 22 | F 系列发现 | 新增「深度代码审阅发现」章节：8 项新发现（F-1 日志凭证泄露 P0 / F-2 AppRoot 每秒重组 P1 / F-3 LyricsManager 急切求值 P1 / F-4 scope 无取消 P2 / F-5 缓存写互斥 P2 / F-6 双网卡 IP P2 / F-7 天气 Key 明文 P2 / F-8 下载无保活 P2-待决策），全部带 file:line 证据 | 实施路线图之后 |
| 23 | 已验证无问题项 | 12 个疑点核实为无问题（GlobalScope 零使用、搜索超时齐全、缓存 LRU 上限齐全、PlaybackService 生命周期正确等），防止未来误报 | F 章节末 |
| 24 | R-7 表格修正 | Kugou/Netease baseUrl Sync 调用点更正为「主线程急切求值」（NasMusicApp:123-124 + MainViewModel:138-142 双处），原表述低估 | R-7 §7.1 |
| 25 | R-9 标记表补全 | 补录 B15/H-4/H-5/M-1/M-2/M-5/M-7/M-9/M-14a-d/M-15/M-16 共 11 个已存在但未登记的修复标记 | R-9 §9.4 |
| 26 | 路线图 + DoD 增补 | F-1/F-2 入阶段二排期（W4.5），F 系列全部入 DoD 表 | 实施路线图 / DoD |

### G. v1.5 修订记录（二次深度代码审阅——重构实施验证）

> v1.5 是基于对 R-1~R-10 / F-1~F-7 全部实施结果的逐项代码级验证，确认实施状态、发现残留问题、更新评分。

| # | 修订项 | 内容 | 位置 |
|--|--------|------|------|
| 27 | R-1~R-10 逐项验证 | 10 项 R 系列全部逐项代码级验证，确认实施状态与质量；R-1 验证 13 子 VM 落位但 MainViewModel 仍 3186 行（兼容转发层约 350 行膨胀）；R-4 验证实际 13 个子 Prefs（非文档记载的 12 个）；R-7 验证 runBlocking 物理调用点从 11 处降至 4 处 | 各 R 项章节 |
| 28 | F-1~F-7 逐项验证 | 7 项 F 系列全部验证实施完成，代码注释清晰标注 R/F 编号 | F 系列章节 |
| 29 | N 系列新发现 | 新增 5 项二次审阅发现：N-1 MainViewModel 兼容转发层膨胀(P1) / N-2 DomainPrefs 多类单文件(P2) / N-3 AppRoot 123处collectAsState 上帝Composable(P1) / N-4 PlayerManager 62.6KB 未拆分(P1) / N-5 BackendAdapter 巨型文件未拆(P2) | 新增 N 系列章节 |
| 30 | R-4 计数修正 | 子 Prefs 门面计数从 12 修正为 13（3 独立文件 + DomainPrefs.kt 内 10 个类） | R-4 §4.1 / 问题总览 |
| 31 | 附录 B 评分上调 | 架构设计 4→4.5、代码组织 3→3.5、资源管理 4→4.5、安全性 4→4.5、可维护性 3→3.5；错误处理与可测试性不变 | 附录 B |
| 32 | 附录 A 更新 | AppRoot.kt 补录 123 处 collectAsState 实测数据；MainViewModel.kt 行数更正为 3186 行（二次审阅实测） | 附录 A |

### H. v1.6 修订记录（N 系列人工复核与定案标注）

> v1.6 是对 v1.5 新增 N 系列 5 项发现的逐条代码级人工复核：**数据断言全部复核通过**（行数/字节数/collectAsState 计数/fun 计数均与实测一致），但修正其中的错误关联与定案冲突，防止后续会话误启动已定案关闭项。

| # | 修订项 | 内容 | 位置 |
|--|--------|------|------|
| 33 | N-1 方案修正 | 修复方案删除 `hiltViewModel()` 提法（与 R-8 定案不实施 Hilt 冲突），改为手动 DI 获取子 VM；总览表状态列补注 | N-1 / 问题总览 |
| 34 | N-2 定性弱化 | 「违反 Kotlin 一个文件一个公开类的惯例」表述不准确（Kotlin 无此硬性惯例），改为可选规范项 | N-2 |
| 35 | N-4 关联修正 + 降级 | 关联 R-2→R-5（原计划 R-2 是 SettingsScreen 拆分；PlayerCore/PlayerQueue 四阶段方案系 v1.5 新提而非"R-2 原方案"）；补注 R-5「HQ 编排保留 PlayerManager 防接口爆炸」定案，降级为待所有者决策项，未经确认不得启动 | N-4 / 问题总览 |
| 36 | N-5 关联修正 + 归案关闭 | 关联 R-6→R-10（R-6 仅做 OkHttp 池化，注册机制系既有）；补注 R-10 定案（Jellyfin 保持原状勿再启动）；补记 Navidrome 902 行大部分为 SubsonicRestClient 委托代码；归入 R-10 定案关闭，非待办 | N-5 / 问题总览 |
| 37 | 数据复核确认 | N 系列 5 项数据断言逐条实测复核：MainViewModel 3186 行、DomainPrefs 179 行 10 类、AppRoot 123 处 collectAsState、PlayerManager 64117 字节/70 fun、5 适配器字节数——全部一致 | 附录 H 本表 |
