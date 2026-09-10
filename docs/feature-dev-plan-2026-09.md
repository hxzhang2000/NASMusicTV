# 播放功能增强开发计划（F2 系列）

> 版本：v1.0（2026-09-10）
> 状态：待所有者评审
> 前置阅读：`docs/technical-overview.md`、`AGENTS.md`
> 本文基于代码实测现状撰写，所有引用路径均已核对。

## 0. 总览

用户从候选清单中选定 6 项播放功能增强，按"价值 × 成本"排定实施顺序：

| 序号 | 功能 | 工作量 | 优先级 | 依赖 |
|---|---|---|---|---|
| F2-1 | 播放统计面板 | 0.5 天 | P1 | 无（数据已存在，纯展示） |
| F2-2 | 通知栏增强 | 1 天 | P1 | 睡眠定时器核心（本计划内含最小实现） |
| F2-3 | 智能电台 / 相似歌单 | 1.5 天 | P2 | NAS 已连接（网络歌曲流派数据不足） |
| F2-4 | 断线续播 | 1.5 天 | P2 | 无（NetworkMonitor 已就绪） |
| F2-5 | 跨曲交叉淡入淡出 | 2-3 天 | P3 | 无 |
| F2-6 | 音质/码率分级 | 2 天 | P3 | 无 |

**排期原则**：
- F2-1/F2-2 先行——纯增量、零架构改动、用户感知直接；
- F2-3/F2-4 次之——涉及播放链路与数据联动，但风险面可控；
- F2-5/F2-6 最后——F2-5 需双路播放器改造（本计划中最复杂单项），F2-6 跨多个网络源、需逐源验证。
- 建议版本：F2-1+F2-2 → v2.29.0；F2-3+F2-4 → v2.30.0；F2-5+F2-6 → v2.31.0。也可按用户节奏调整。

---

## 1. F2-1 播放统计面板

### 1.1 目标
播放数据已在记录但从未展示。新增一个统计页面，展示"本月最爱歌手 / 流派分布"等聚合视图，让历史数据产生价值。

### 1.2 现状证据（代码实测）

- 播放次数：`data/prefs/AppPreferences.kt:191` `keyPlayCounts`，JSON `Map<String, Int>`（songId → 次数），`recordPlayWithSong()` 单次 DataStore edit 原子更新（:500-522）
- 播放明细：`data/model/PlayRecord.kt`——含 `songId/title/artist/album/coverUrl`；`PlayHistoryViewModel.recordPlayEvent()`（:36-38）**少于 5 秒不计入**（防误触）
- 最近播放：`recentSongObjects` 完整 Song 对象列表（网络歌曲 streamUrl 置空）
- NAS 适配器已支持流派：`JellyfinAdapter.kt:1121-1122`（Genres 数组首个）、`NavidromeAdapter.kt:748-756`（subsonic genres 端点）
- `Song` 已有 `genre: String?` 字段（`data/model/Song.kt:28`），但 **play_counts 无时间维度**——现有 keyPlayCounts 是累计值，无按月统计能力

### 1.3 详细设计

**数据层——新增 PlayStatsRepository（`data/stats/PlayStatsRepository.kt`）**

```kotlin
class PlayStatsRepository(private val prefs: AppPreferences) {
    // 新增 DataStore 键 keyPlayStatsMonthly = "play_stats_monthly"
    // JSON: { "2026-09": { songId: count } }
    val monthlyStats: Flow<Map<String, Map<String, Int>>>
    suspend fun recordMonthlyPlay(songId: String)
    suspend fun getMonthTop(limit: Int, month: String = currentMonth()): List<Pair<Song, Int>>
}
```

- `recordPlayWithSong()` 内同步追加月度键写入（同一次 DataStore edit，保持原子性）
- **不做历史回填**：存量 play_counts 无时间维度，回填只能估，不做。

**聚合层——新增 `data/stats/PlayStatsAggregator.kt`**

- 输入：月度 songId 次数表 + Song 元数据（recentSongObjects / NAS adapter.getSongsByIds / PlayRecord 历史查）
- 输出：`StatsBundle(topArtists: List<ArtistStat>, genreDistribution: List<GenreStat>, totalPlays: Int, activeDays: Int)`
- ArtistStat/GenreStat data class 放 `data/model/StatsModels.kt`
- 聚合在 ViewModel 层用 `viewModelScope.launch { withContext(Dispatchers.Default) {...} }`，避免阻塞主线程

**UI 层——新增统计页 `ui/screens/stats/PlayStatsScreen.kt`**

- 入口：设置页新增"播放统计"入口（SettingsScreen ABOUT 分区前插入）；导航走既有 `Screen` 枚举新增 `STATS`
- 页面结构：
  - 顶部：本月播放总次数 / 活跃天数 / 覆盖歌曲数（3 个 KPI 卡）
  - 中部："本月最爱歌手" Top 10 横向列表（封面 + 次数）
  - 下部："流派分布"横向条形图（Compose Canvas 绘制，不引第三方图表库）
- D-Pad 焦点顺序：KPI → 歌手列表 → 流派图 → 返回
- 歌手/流派点击可跳转 Library 对应筛选（复用既有筛选参数）

### 1.4 任务拆解

| # | 任务 | 验收标准 | 预估 |
|---|---|---|---|
| 1 | PlayStatsRepository：月度统计键 + recordPlayWithSong 联动写入 | 单测：写入后 monthlyStats Flow 发射正确值 | 2h |
| 2 | PlayStatsAggregator：topArtists/genreDistribution 聚合 | 单测：空数据/重复歌手聚合/流派为 null 归入"未分类" | 2h |
| 3 | StatsModels + PlayStatsScreen UI | TV D-Pad 可达所有焦点区 | 4h |
| 4 | SettingsScreen 入口 + Screen.STATS 导航 + 三级返回 | 从设置进/出统计页，BACK 回设置 | 1h |
| 5 | 文档三件套 + 提交 | CHANGELOG/technical-overview §10 更新 | 1h |

### 1.5 测试方案
- 单测（JUnit4）：Repository 写入/读取/聚合边界（空月、跨月滚动、artist 大小写归并策略）
- Robolectric：PlayStatsScreen 首次渲染 + 空态（无播放记录）
- TV 手测：播放 3-5 首后进统计页看数据正确性

### 1.6 风险与边界
- **artist 归并**：同一歌手名可能因 GBK 编码问题出现 mojibake 变体（`util/EncodingUtils.kt` 已知问题），聚合时按精确字符串匹配，不强行归并——文档明确记录该取舍。
- play_counts 存量数据只进"累计"视图（页面加一个 Tab 切换"本月/累计"），月度数据从上线起记。

---

## 2. F2-2 通知栏增强

### 2.1 目标
现有通知栏仅 3 按钮（上一首/播放暂停/下一首）。增加：显示下一首、播放模式循环切换、定时关闭入口，并提升锁屏信息量。

### 2.2 现状证据（代码实测）

- 通知构建：`player/PlaybackService.kt:347-399`——MediaStyle + 3 按钮（prev/play-pause/next），`setShowActionsInCompactView(0,1,2)`
- 按钮事件：`buildMediaButtonPendingIntent(keyCode)`（:407-417）经 `ACTION_MEDIA_BUTTON` + KeyEvent 转发 MediaSession，`MediaLibraryService.onStartCommand` 自动处理
- `MediaSession` 延迟创建（onCreate 不建，首次播放才建，:148）
- **无定时关闭/睡眠定时器**：全库无 sleep/timer 键（已核实）
- 播放模式状态在 `PlayerViewModel._playMode`（StateFlow），应用层持有；ExoPlayer 的 repeatMode 只是镜像
- 通知仅在播放中刷新（`lastNotificationState` 防重复刷新）

### 2.3 详细设计

**核心前置——睡眠定时器（SleepTimerController，`player/SleepTimerController.kt`）**

```kotlin
class SleepTimerController(private val handler: Handler) {
    sealed interface State { object Off; data class Running(val endsAtMs: Long); data class Finished(val atMs: Long) }
    val state: StateFlow<State>
    fun start(minutes: Int)          // postDelayed 到期 pause()
    fun cancel()
    fun remaining(): Long
}
```
- 持有者：PlayerManager（与 progressHandler 同生命周期），到期回调 `PlayerManager.pause()` + 发通知栏更新
- 持久化：断电重启后定时丢失可接受（电视场景定时 = 今晚，重启即重置），**不持久化**，降低复杂度
- 预设档位：15/30/60/90 分钟 + 自定义

**通知栏改造（PlaybackService）**

按钮从 3 → 5（MediaStyle compact view 仍只显示 3 个核心位）：
1. 上一首（既有）
2. 播放/暂停（既有）
3. 下一首（既有）
4. **播放模式循环切换**：SEQUENTIAL → REPEAT_ONE → REPEAT_ALL → SHUFFLE 循环；图标随模式变化（repeat/repeat_one/repeat_all/shuffle）
5. **定时关闭**：点按弹出 chooser（15/30/60/90 分钟/取消）；运行中通知按钮显示剩余分钟数（每分钟刷新）

- 4/5 两按钮**不走 KeyEvent**（无对应系统键码语义），改用 `PendingIntent.getBroadcast` + 自定义 action（`ACTION_TOGGLE_PLAY_MODE` / `ACTION_SLEEP_TIMER`），BroadcastReceiver 在 PlaybackService 内注册（`onStartCommand` 处理或独立 receiver + LocalBroadcast 语义，注意 API 22 兼容用 `ContextCompat.registerReceiver`）
- **显示下一首**：`buildNotification` 时从 PlayerManager 读 `queue[currentIndex+1]`，`setSubText("下一首：xxx")`（Android 5+ 可用，API 22 兼容）
- **歌词开关**：语义说明——TV 端歌词即主界面（NowPlaying 常驻歌词区），通知栏歌词开关仅对手机蓝牙遥控场景有意义。**首期不做通知栏歌词开关**，在 NowPlaying 遥控菜单加"隐藏/显示歌词"项替代（避免通知栏堆 6 按钮溢出）。
- 刷新节流：定时运行中每分钟刷新一次剩余时间（同一 `lastNotificationState` 机制扩展带分钟粒度 key）

**NowPlaying UI 联动**

- 定时关闭状态显示：NowPlaying 顶栏加小图标 + 剩余时间（点击弹设置对话框）
- SleepTimerController.state 被 PlayerViewModel collect，暴露给 UI

### 2.4 任务拆解

| # | 任务 | 验收标准 | 预估 |
|---|---|---|---|
| 1 | SleepTimerController 纯逻辑 + 单测 | start/cancel/到期/剩余时间正确；单测覆盖状态流转 | 2h |
| 2 | PlayerManager 集成（持有 + 到期 pause） | 到期后播放暂停、通知更新 | 1h |
| 3 | 通知栏 5 按钮 + 自定义 action 接线 | 点击播放模式按钮循环切换并生效；定时按钮弹档位选择 | 3h |
| 4 | 显示下一首 subText | 通知显示下一首标题；队尾/无队列不显示 | 1h |
| 5 | NowPlaying 定时状态图标 + 设置对话框 | 图标显示剩余时间，点击可改/取消 | 2h |
| 6 | 文档三件套 + 提交 | CHANGELOG/technical-overview §10 更新 | 1h |

### 2.5 测试方案
- 单测：SleepTimerController 状态机（用 fake Handler/时间注入）
- Robolectric：通知构建（5 按钮、subText、模式图标映射）
- TV 手测：电视遥控暂停后按通知操作（电视通知入口 = 长按 HOME 呼出）；手机蓝牙/AVRCP 场景同步验

### 2.6 风险与边界
- **电视端通知可见性**：Android TV 通知常驻性弱于手机（无下拉栏常驻），通知栏增强的主要受益者是**手机 AVRCP/蓝牙遥控**场景；电视本体入口在 NowPlaying 同步做（任务 5）。
- API 22 `registerReceiver` 运行时注册兼容性用 ContextCompat；API 33+ 动态 receiver 需 `RECEIVER_NOT_EXPORTED` 标志（`ContextCompat.registerReceiver` 自动处理）。
- 定时到期时若正在 K 歌（人声分离中），pause 主播放器即可，分离进程无音频输出不受影响。

---

## 3. F2-3 智能电台 / 相似歌单

### 3.1 目标
基于当前播放歌曲的流派 + 歌手，从曲库自动生成"越听越对味"的随机流（智能电台），比纯随机更像电台。

### 3.2 现状证据（代码实测）

- `Song.genre: String?`（`data/model/Song.kt:28`）；NAS 侧 Jellyfin/Navidrome/Subsonic 均有流派数据
- 曲库获取：`BackendAdapter` 各适配器 `getSongsByGenre/Artist/Album` 系列已存在（Library 页流派浏览在用）
- 纯随机播放：`PlayerManager.playRandom()`（:921-948）+ `shuffleHistory` 去重
- 天气电台已有先例：`backend/weather/WeatherRadioManager` 按 mood 匹配歌曲——**本功能复用其架构模式**（Manager + 匹配策略 + 注入队列）
- 网络歌曲（Meting/百度）大多无 genre 字段，智能电台**首期仅限 NAS 曲库**

### 3.3 详细设计

**SmartRadioManager（`backend/radio/SmartRadioManager.kt`）**

```kotlin
class SmartRadioManager(
    private val backendRegistry: BackendRegistry,
    private val scope: CoroutineScope
) {
    sealed interface Seed { data class SongSeed(song: Song); data class GenreSeed(genre: String); data class ArtistSeed(artistId: String, name: String) }
    val state: StateFlow<RadioState>  // Idle / Generating / Playing(seed) / Exhausted
    fun startFromCurrentSong(song: Song)
    fun skip()                        // 换一批（重新生成）
    fun stop()
}
```

**匹配策略（可单测的纯函数 `RadioSongScorer.kt`）**

打分制，不硬分类：
- 同 artist：+50
- 同 genre：+30
- 同 album（非当前专辑）：+10
- 同年代（year 差 ≤3）：+5
- play_counts 高（用户偏好信号）：+ min(count, 20)
- 已在 shuffleHistory：-100（强排除）
- 加权随机采样（分数做权重），每批 20 首，播完自动续批

**种子来源**：NowPlaying 当前歌（"为我播 Similar"按钮）/ Library 歌手/流派详情页（"以此开电台"）/ 天气电台页（新增"智能电台"Tab 与天气电台并列）。

**队列接入**：生成批次经 `PlayerViewModel.playQueue()` 入队；`Exhausted`（曲库耗尽）时降级纯随机。

**与现有天气电台关系**：并列功能，不合并——天气按环境选歌，智能电台按音乐本体选歌，UI 上同页 Tab 切换。

### 3.4 任务拆解

| # | 任务 | 验收标准 | 预估 |
|---|---|---|---|
| 1 | RadioSongScorer 纯函数 + 单测 | 打分规则逐条单测；加权采样分布合理 | 3h |
| 2 | SmartRadioManager（种子解析/批次生成/续批/停止） | 批次为空降级纯随机；连续 skip 不重复批次 | 4h |
| 3 | 曲库获取路径（getSongsByGenre/Artist 汇聚 + 缓存） | NAS 未连接时按钮隐藏或提示 | 2h |
| 4 | UI：NowPlaying"智能电台"入口 + 天气电台页 Tab | D-Pad 可达；生成中有 loading 态 | 3h |
| 5 | 文档三件套 + 提交 | | 1h |

### 3.5 测试方案
- 单测：Scorer 全规则 + 边界（genre null、artist 同名不同人、空曲库）
- Robolectric：Manager 状态流转（Idle→Generating→Playing→Exhausted→降级）
- TV 手测：NAS 曲库 500+ 首场景连续播 3 批，观察批次相似度与新鲜度

### 3.6 风险与边界
- 大曲库全量拉取耗时：曲库 >5000 首时按流派先筛再打分（两级查询）；
- Jellyfin genre 为数组首元素、Navidrome 为单值——聚合时统一小写 trim 归并；
- 网络歌曲无 genre → 首期不支持，UI 明示"智能电台需 NAS 曲库"。

---

## 4. F2-4 断线续播

### 4.1 目标
NAS/网络音乐播放中遇到断网，当前会停在错误状态（v2.28.1 已修 IDLE 恢复，但**断网期间的错误风暴仍会触发跳歌**——链接解析失败自动跳下一首，恢复后歌已经跳走了）。加网络感知：断网暂停 + 恢复后回到断点继续。

### 4.2 网络感知（代码实测）

- `util/NetworkMonitor.kt`：防抖 NetworkCallback（onAvailable 转换触发/onLost 触发），已在 `MainActivity:337` 注册
- `MainViewModel.onNetworkAvailable/onNetworkLost`（:2171-2200）：已有自动重连（3 次上限）+ 提示，但**不感知播放器状态**——断网时播放器侧的错误处理照常跑（跳歌）
- PlayerManager `onPlayerError` → 重解析一次 → 失败跳歌（v2.28.1 已带 IDLE 恢复）

### 4.3 详细设计

**核心思路：断网 → 冻结跳歌；恢复 → 断点续播。**

**PlayerManager 侧（`player/PlayerManager.kt`）**

新增 `@Volatile var networkLost = false`：
- `onPlayerError`：若 `networkLost == true`，**不跳歌不重解析**，记录 `pendingResume = ResumePoint(index, positionMs, wasPlaying)`，`_buffering.value = true`（UI 显示缓冲中），并置 player IDLE-safe 暂停
- `NetworkMonitor.onNetworkAvailable` → MainViewModel 调用新 `playerVM.onNetworkRestored()`：
  1. `networkLost = false`
  2. 若 `pendingResume != null`：等 2 秒（网络栈稳定）→ 重解析当前歌 URL（复用 `resolveAndPlayByIndex`）→ 从 `pendingResume.positionMs` seek 续播（`wasPlaying=false` 则只加载不播）
  3. NAS 源歌曲：先确认 adapter 已重连（`connectToSavedServer(silent)` 完成后再解析）

**恢复去抖**：断网恢复 2 秒内可能再抖（WiFi 切换），`onNetworkRestored` 用 `delay(2000)` + 取消旧 job（新恢复事件取消旧的等待）。

**K 歌/MTV 模式**：MTV 独立播放器（MvPlaybackScreen 自有 ExoPlayer）不受 PlayerManager 冻结影响，其 `onPlaybackError` 走既有 `onMvPlaybackError` 路径——**首期不动 MTV 路径**，文档记录该边界。

**队列持久化配合**：断网期间用户手动退 App → 下次启动恢复队列（既有 `keyLastQueue`），断点在 `PlaybackPositionStore`（若无则从 0）——查实无 position 持久化则首期仅会话内续播。

### 4.4 任务拆解

| # | 任务 | 验收标准 | 预估 |
|---|---|---|---|
| 1 | PlayerManager networkLost + onPlayerError 冻结路径 | 断网中错误不跳歌；pendingResume 记录正确 | 2h |
| 2 | onNetworkRestored 断点续播（含去抖 + NAS 重连前置） | 恢复后从断点继续；wasPlaying=false 只加载 | 3h |
| 3 | MainViewModel onNetworkAvailable 接线到 playerVM | 既有重连逻辑不回归 | 1h |
| 4 | UI 提示（断网横幅已有 D-2 提示，补"等待网络恢复..."文案） | 断网中 NowPlaying 显示等待态 | 1h |
| 5 | 文档三件套 + 提交 | | 1h |

### 4.5 测试方案
- 单测：pendingResume 记录/恢复（fake player）；去抖期间二次恢复不双跑
- Robolectric：networkLost=true 时 onPlayerError 分支
- TV 手测：播放 NAS 歌 → 路由器拨 WAN 断（或关 AP）→ 等错误 → 恢复网络 → 验证回到断点继续播

### 4.6 风险与边界
- 电视 WiFi 断/复与 DHCP 续租产生的秒级抖动，依赖 NetworkMonitor 既有防抖 + 2 秒恢复延迟；
- 长断网（>5 分钟）：恢复时原 streamUrl 必过期，走既有重解析路径（v2.28.1 修复后可靠）；
- 百度 dlink 8h 过期 ≠ 断网，语义区分由 networkLost 标志严格界定。

---

## 5. F2-5 跨曲交叉淡入淡出（crossfade）

### 5.1 目标
切歌时旧曲淡出、新曲淡入重叠过渡，电视音响听感明显提升。

### 5.2 现状与约束（代码实测）

- 播放器：单 ExoPlayer 实例（PlaybackService 创建，PlayerManager 管理）；**无第二实例**
- ExoPlayer 无内建 crossfade；media3 的 `MediaItemsList` 间隙切换不支持重叠输出
- 音频处理器链：`SpectralMaskProcessor`（K 歌人声消除）已注入 `DefaultAudioSink`（PlaybackService:111-123）——**crossfade 音量斜坡与 K 歌处理器同链，需确认互斥**（crossfade 中禁用人声消除或同链叠加均可，设计上选禁用避免双重处理）
- 变速不变调 `PlaybackParameters`（setSpeed/setPitch）证明本项目的 AudioTrack 参数链路成熟

### 5.3 详细设计

**双实例方案（选定）**：新建第二 ExoPlayer（`crossfadePlayer`），切歌窗口内旧歌在主 player 淡出、新歌在 crossfadePlayer 淡入，窗口结束切主 player 到新歌。

```kotlin
class CrossfadeController(
    context: Context,
    mainPlayerProvider: () -> ExoPlayer?,
    onCrossfadeComplete: (Int) -> Unit   // 新歌 index 回调主队列
) {
    val state: StateFlow<CrossfadeState>  // Off / Fading(durationMsLeft)
    var enabled: Boolean                  // 设置项 keyCrossfadeEnabled
    var durationSec: Int                  // keyCrossfadeDurationSec，默认 4s，0-12
    fun maybeStartCrossfade(currentIndex: Int, nextIndex: Int)
}
```

**触发点**：进度轮询（progressUpdateRunnable）检测 `duration - position <= durationSec` 且 `enabled` 且非 K 歌/MTV 模式：
1. 主 player 记录当前歌参数（speed/pitch）
2. crossfadePlayer.setMediaItem(下一首) + prepare + play，初始 volume=0
3. 双 Handler 每 50ms 步进：主 player.volume ↓、crossfadePlayer.volume ↑（线性）
4. 窗口结束：`onCrossfadeComplete` → PlayerManager `transitionToIndex(nextIndex)`（v2.28.1 新增方法，正好复用）+ crossfadePlayer.stop/release 资源
5. 音频焦点：crossfadePlayer 也 setAudioAttributes(USAGE_MEDIA, handleAudioFocus=false)——**焦点仍由主 player 独占持有**

**与现有机制冲突消解**：
- IDLE 恢复/链接过期重解析：crossfade 窗口开始前先确认下一首 streamUrl 有效（网络歌曲先重解析），无效则放弃 crossfade 走普通切换
- 手动切歌（用户按 next/previous）：立即中断进行中的 crossfade（资源释放 + 直接切换）——不做"优雅等待"
- K 歌/MTV 模式：`suppressPlayback` 为 true 期间禁止 crossfade
- 单曲循环 REPEAT_ONE：不 crossfade（同曲重叠无意义）
- 队列只有 1 首：不 crossfade

**设置项**：设置页播放分区新增"交叉淡入淡出"开关 + 时长滑条（0-12s，默认 4s）；键 `keyCrossfadeEnabled/keyCrossfadeDurationSec`（PlayerPrefs 域）。

### 5.4 任务拆解

| # | 任务 | 验收标准 | 预估 |
|---|---|---|---|
| 1 | CrossfadeController 双实例 + 音量斜坡 + 单测 | 斜坡线性、中断清理彻底（无僵尸 player） | 4h |
| 2 | 触发集成（progressUpdateRunnable 钩子 + 边界条件矩阵） | 全部边界条件单测覆盖 | 4h |
| 3 | 与 IDLE 恢复/重解析路径消解 | 网络歌曲 crossfade 前置解析；失败降级普通切歌 | 3h |
| 4 | 设置项 + PlayerPrefs 键 | UI 可开关、时长可调、立即生效 | 2h |
| 5 | TV 音质验证 + 文档三件套 + 提交 | 实际听感确认无爆音/时钟漂移 | 2h |

### 5.5 测试方案
- 单测：状态机全路径（触发/中断/完成/K歌禁用/REPEAT_ONE 跳过/URL 无效降级）
- Robolectric：双 player 资源生命周期（无泄漏）
- TV 手测（重点）：FLAC + 电视音响 HDMI 输出连续播 30 首，听感 + 观察内存

### 5.6 风险与边界
- **双 AudioTrack 时钟漂移**：两实例独立 AudioTrack 可能微漂移——4 秒窗口内可闻性极低，若手测发现可改 `ExoPlayer` 的 `frameComparator` 或共享 `AudioSink`（复杂度高，预留为二期）
- 低端电视（armeabi-v7a 2GB）双解码内存：FLAC 双解码峰值 +约 30MB，2GB 设备可承受；x86_64 仿真器验证无碍
- **电视端体验考量**：crossfade 对"客厅背景音乐"场景增益大，对"专注听歌"见仁见智——默认关闭，用户自开

---

## 6. F2-6 音质/码率分级

### 6.1 目标
网络源（Meting-API、百度网盘）按带宽/设置选源；NAS 本地原品质直传。

### 6.2 现状证据（代码实测）

- Meting 源：`MetingApiService.resolvePlayUrl()`（:221-248）——`type=url` 直链解析，**未传 `br`（bitrate）参数**；Meting-API 协议支持 `br=128/192/320/740/999`（999 = 无损）档位
- 百度网盘：`BaiduNetdiskService.resolvePlayUrl()`（:91-93）——fs_id → dlink，文件本体唯一（转码不适用），**无码率可选**——百度侧分级 = 文件级（同名多品质文件选择），首期不做
- Jamendo：搜索结果自带直链（:93-95），无码率档
- `Song.bitrate: Int`（Song.kt:29）字段已有，BaiduPanApi 解析时已填（BaiduPanApi.kt:371）
- NAS（Jellyfin）：`JellyfinAdapter.getStreamUrl()`（`JellyfinAdapter.kt:534-535`）——`/Audio/{id}/stream.mp3` 端点**原品质直传**（未用转码参数）；Jellyfin 另有 `/Audio/{id}/universal` 端点支持 `AudioCodec/BitRate` 转码参数，当前实现未使用——分级改造即切换/追加参数的问题
- 无带宽检测：`NetworkMonitor` 只报通断，无下行带宽估计

### 6.3 详细设计

**分级策略：设置驱动 + 带宽探测双因子。**

**设置层（PlayerPrefs 新增）**

```kotlin
enum class QualityTier(val label: String, val metingBr: Int?) {
    AUTO("自动（按带宽）", null),
    LOSSLESS("无损优先", 999),
    HIGH("高音质 320k", 320),
    STANDARD("标准 128k", 128)
}
// keyQualityTier：默认 AUTO
```

**Meting 侧改造（`MetingApiService.resolvePlayUrl`）**

- 请求 URL 追加 `&br=$tier`（AUTO 时不传）
- **AUTO 带宽探测**：应用级 `BandwidthEstimator`（`util/BandwidthEstimator.kt`）——滑动窗口记录近期 HTTP 下载字节数/耗时（OkHttp 拦截器无侵入统计，复用 BackendRegistry.sharedDispatcher 链路的既有 client），窗口均值：
  - > 10 Mbps → 999（无损）
  - 2-10 Mbps → 320
  - < 2 Mbps → 128
- 结果缓存 5 分钟（TV 网络状态变化不频繁），NetworkMonitor 断网事件清零
- **降级路径**：请求 `br=999` 失败（该歌无无损）自动回退 `br=320` 再 `br=128`（Meting 各源对 br 支持不一，降级链保命中）

**NAS 侧（JellyfinAdapter，可选二期）**

- `getStreamUrl()` 追加 `&bitrate=<kbps>` 或改走 universal 端点（按 QualityTier 映射）——仅在用户显式选非 AUTO 档时改；AUTO 时 NAS 原品质（用户选 NAS 通常就是为原品质）
- Navidrome/Subsonic：subsonic 协议 `maxBitRate` 参数同理，二期

**UI**：设置 → 播放 → "音质分级"单选（4 档 + 说明文案）；NowPlaying 不加显示（避免 TV 界面噪音），关于页可查当前生效档。

### 6.4 任务拆解

| # | 任务 | 验收标准 | 预估 |
|---|---|---|---|
| 1 | QualityTier + PlayerPrefs 键 + 设置 UI | 单选生效并持久化 | 2h |
| 2 | BandwidthEstimator（OkHttp 拦截器 + 滑动窗口） | 单测：窗口滑动/清零/分级映射正确 | 3h |
| 3 | MetingApiService br 参数 + 降级链 | 三档命中；失败逐级降级；AUTO 走 estimator | 3h |
| 4 | Jellyfin getStreamUrl bitrate 参数（显式档） | 显式档生效；AUTO 不带参数 | 2h |
| 5 | 文档三件套 + 提交 | | 1h |

### 6.5 测试方案
- 单测：Estimator 窗口逻辑/分级边界（10M/2M 阈值）；Meting URL 构造（各档位参数）
- Robolectric：降级链重试次序
- TV 手测：同首歌切档对比起播速度与音质（128 vs 999 起播耗时差异应明显）；弱网（限速）下 AUTO 选 128

### 6.6 风险与边界
- **Meting 源 br 支持参差**：不同 `server`（netease/qq/...）对 br 响应不一——降级链是必要保障，测试矩阵覆盖主流源
- 无损（br=999）实际为 FLAC/大文件，起播耗时 + 缓冲增加——AUTO 模式用带宽阈值避免"高速网络选无损但源慢"的边缘情况（源慢 ≠ 带宽低，estimator 只测本机带宽）
- 百度网盘/Jamendo 无码率可选，UI 档位说明中明示适用范围（"Meting 源"）
- 转码产生 NAS 端 CPU 负载：Jellyfin 显式档转码建议仅高带宽环境使用，AUTO 默认原品质规避

---

## 7. 里程碑与发布策略

| 里程碑 | 内容 | 版本 | 预估 |
|---|---|---|---|
| M1 | F2-1 统计面板 + F2-2 通知栏增强（含睡眠定时器） | v2.29.0 | 3.5 天 |
| M2 | F2-3 智能电台 + F2-4 断线续播 | v2.30.0 | 3.5 天 |
| M3 | F2-5 crossfade + F2-6 音质分级 | v2.31.0 | 5.5 天 |

**每里程碑统一 DoD**：
1. `assembleDebug` + `testDebugUnitTest` 全绿（既有 270+ 用例零回归）
2. 新增功能单测覆盖核心路径（各功能测试方案节）
3. TV 手测回归清单通过（重点：D-Pad 焦点、播放全路径、K 歌/MTV 回归）
4. 文档三件套：CHANGELOG + technical-overview §10 + 本计划状态列更新
5. 本地提交 + tag，推送经所有者确认

**依赖关系图**：
```
F2-1 ──┐
F2-2 ──┼─→ M1 ─→ v2.29.0
       │
F2-3 ──┐│      （F2-3 依赖 M1 的 v2.29 基线，无代码依赖）
F2-4 ──┼┘→ M2 ─→ v2.30.0
       │
F2-5 ──┐│      （F2-5 复用 v2.28.1 的 transitionToIndex）
F2-6 ──┼┘→ M3 ─→ v2.31.0
```

**回滚预案**：所有功能均带设置开关或独立入口，发布后出现问题可设置默认值回退，不必回滚版本。

---

## 8. 状态跟踪

| 功能 | 状态 | 计划 | 实际 | 备注 |
|---|---|---|---|---|
| F2-1 播放统计面板 | ✅ 完成（M1） | 0.5 天 | | 档位对话框未做，见偏差说明 |
| F2-2 通知栏增强 | ✅ 完成（M1） | 1 天 | | 含睡眠定时器；歌词开关按计划不做 |
| F2-3 智能电台 | ⬜ 未开始 | 1.5 天 | | 首期仅 NAS 曲库 |
| F2-4 断线续播 | ⬜ 未开始 | 1.5 天 | | 复用 NetworkMonitor |
| F2-5 crossfade | ⬜ 未开始 | 2-3 天 | | 默认关闭 |
| F2-6 音质分级 | ⬜ 未开始 | 2 天 | | Meting 先行，百度不适用 |

> 状态图例：⬜ 未开始 / 🔶 进行中 / ✅ 完成 / ❌ 定案不做

---

## 9. 附录：本计划核实过的关键代码位置

| 引用 | 位置 |
|---|---|
| play_counts 存储 | `data/prefs/AppPreferences.kt:191`（keyPlayCounts）、:500-522（recordPlayWithSong） |
| PlayRecord 明细 | `data/model/PlayRecord.kt`、`ui/viewmodel/PlayHistoryViewModel.kt:36-38`（<5s 不计） |
| Song.genre/bitrate | `data/model/Song.kt:28-29` |
| 通知构建 | `player/PlaybackService.kt:347-399` |
| KeyEvent 转发 | `player/PlaybackService.kt:407-417` |
| NetworkMonitor | `util/NetworkMonitor.kt`（全文件）、注册于 `ui/MainActivity.kt:337` |
| 网络重连 | `ui/viewmodel/MainViewModel.kt:2171-2200`（3 次上限） |
| IDLE 恢复 | `player/PlayerManager.kt` transitionToIndex（v2.28.1，本计划 F2-4/F2-5 复用） |
| playRandom | `player/PlayerManager.kt:921-948` |
| Meting resolvePlayUrl | `backend/network/MetingApiService.kt:221-248` |
| 百度 resolvePlayUrl | `backend/network/baidu/BaiduNetdiskService.kt:91-93` |
| 人声消除处理器注入 | `player/PlaybackService.kt:111-123` |
| 天气电台先例 | `backend/weather/WeatherRadioManager` |
