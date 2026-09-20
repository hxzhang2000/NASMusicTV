# 歌单文件导入与自动补全开发方案

> 状态：草案 v1（2026-09-18）
> 作者：Mavis（PM 视角整理）
> 范围：在「设置 → 数据管理」分区增加歌单导入入口；导入后在「我的 → 本地歌单」自动建立歌单；
> 通过播放触发按需补全歌曲元数据，形成本应用可播放、可搜索、可持久化的自有歌单。

---

## 1. 需求拆解与目标

用户提出的需求可拆成 4 个独立但相互依赖的子目标：

1. **导入入口**：从本地文件系统导入歌单文件（`m3u` / `m3u8` / `json` / 网易云分享 `json` / 第三方歌单导出等），自动在「我的 → 本地歌单」创建一个本地歌单。
2. **取名兜底**：源文件含歌单名称时优先使用；缺失时按文件名推断；仍取不到时弹出输入框让用户输入。
3. **按需补全**：导入时只带「标题 + 艺术家」裸数据写入本地歌单；当用户播放歌单内任一首歌曲、或单首歌在「最近播放」出现于导入歌单时，触发**后台异步元数据补全**（封面、时长、流来源 id），把裸条目升级成可播放的 `Song`。
4. **日常运营**：除导入/补全外，需补齐「歌单内补全状态可视化、手动触发补全、导出歌单、删除/重命名（已存在）」等附属操作。
5. **多格式支持**：必须支持 `.m3u` / `.m3u8` / 网易云分享 JSON / 本应用自导 JSON，**且必须支持 `.txt`（用户自编辑文档）** —— 后者是用户最常用的导入形式：每行一首歌，后跟可选艺术家。

落地后给用户带来的具体体验：

- 设置 → 数据管理 → 「导入歌单」选文件 → 1 秒内我的页面多出一个新歌单，能直接播放。
- 第一次播放某首「裸歌曲」时，应用后台静默查询网络/NAS 元数据，几秒内封面/时长/版本 id 自动填回。
- **用户场景 A（m3u 党）**：从酷安 / 网易云客户端导出 `.m3u`，导入即可。
- **用户场景 B（手敲党）**：在 Windows 记事本里敲一个 `我的歌单.txt`，每行 `歌名 - 艺术家`，导入后能用。
- 后续再次播放同一首时直接走流，跳过补全查询。

---

## 2. 现有系统盘点（关键事实，便于对齐设计）

### 2.1 本地歌单数据结构与持久化

- 数据模型：`data/model/LocalPlaylist.kt`，结构：`id: String(UUID)`、`name: String`、`songs: List<Song>`、`createdAt: Long`。
- 存储：`AppPreferences.keyLocalPlaylists`（DataStore Preferences，**整段 JSON 序列化**，`gson.toJson(mutable)`，`AppPreferences.kt:1564`）。`PlaylistPrefs` 暴露给 ViewModel 调用（`data/prefs/PlaylistPrefs.kt`）。
- 既有 CRUD：`createLocalPlaylist` / `renameLocalPlaylist` / `deleteLocalPlaylist` / `addSongToPlaylist`（按 `song.id` 去重）/ `removeSongFromPlaylist` / `getLocalPlaylists()` / `localPlaylists` Flow（`AppPreferences.kt:1009-1142`）。
- ViewModel：`ui/viewmodel/PlaylistViewModel.kt`，5 个动作均已挂在 `MainViewModel` 上。
- UI 入口：`MineScreen`（`ui/screens/MineScreen.kt`）—— 标题 + 「+ 新建歌单」按钮；现有操作：播放/重命名/删除/展开看歌曲/移除歌曲。`MineBranch`（`ui/components/branches/MineBranch.kt`）完成事件路由。

### 2.2 `Song` 模型关键字段（与导入强相关）

`data/model/Song.kt`：

- 必备：`title`、`id`（NAS 原始 / 网络为 `ntwk_${source}_${sourceId}` / 本地为 `local_${mediaStoreId}`）
- 重要：`artist`、`album`、`coverUrl`、`streamUrl?`、`durationMs`、`networkSource`、`networkId`、`isNetworkSong`、`isLocalSong`
- **持久化前置空**：网络歌曲的 `streamUrl` 由 `Song.stripVolatileStreamUrl()` 在 `addSongToPlaylist` 等 6 处统一清空（`AppPreferences.kt:512-513`）。

**结论**：导入的歌单条目写入本地歌单时，**没有流地址也能正常存活**；播放时再解析（已有 `NetworkMusicManager.resolvePlayUrl` 链路）或由本次新增的补全流程升级。

### 2.3 元数据补全能力盘点

| 路径 | 接口 | 说明 |
| --- | --- | --- |
| 网络音乐 | `NetworkMusicManager.search(keyword)`（`backend/network/NetworkMusicManager.kt:66`） | 多源 fallback，返回 `List<Song>`（含 `id/networkId/streamUrl/durationMs/coverUrl`） |
| 封面兜底 | `NetworkMusicManager.searchCoverUrl(title, artist)`（同文件 `:199`） | 单独查封面 URL |
| NAS 后端 | `BackendAdapter.searchSongs(query)`（`backend/BackendAdapter.kt:133`） | 已连 NAS 时可用，返回带后端 `id` 的 `Song` |
| 去重键 | `SearchAggregator.normalizeKey(title, artist)`（`backend/SearchAggregator.kt:326`） | 已存在的小写 + trim + 合并空白去重键 |
| 艺术家归一化 | `ArtistSplitter.normalizeKey(name)`（`util/ArtistSplitter.kt:47`） | NFKC + 折叠空白 + 小写，与艺术家匹配共用 |

**重要现状约束**：网络搜索默认源的 5 分钟缓存（`PLAY_URL_CACHE_TTL_MS = 5 * 60 * 1000`，`NetworkMusicManager.kt:29`）对补全同样适用。

### 2.4 文件读取与系统集成已有能力

- **URI 读取**：`util/BackupFileUtils.kt:170-181` `read(context, uri): Result<String>` 已实现 UTF-8 安全读取、异常兜底，可改造为读歌曲元数据 JSON / 文本。
- **持久 URI 权限**：`backend/export/ExportPermissionHelper.kt` + `MainActivity.kt:79` 已注册 `ActivityResultContracts.OpenDocumentTree()`。`OpenDocument` 单文件选择器同样可用（`ActivityResultContracts.OpenDocument` + `arrayOf("audio/*", "application/json", "text/*", "application/octet-stream")`）。
- **目录访问**：当前只用了 SAF 目录树（导出用），没有单文件选择器链路。**需要新增**`OpenDocument` 注册或复用树选择器（前者更通用）。
- **设置分区结构**：`SettingsScreen.kt:101-111` 9 个：`GENERAL/PLAYBACK/DOWNLOAD/SERVER/CACHE/NETWORK/NETDISK/DATA/ABOUT`。每个分区用 `SettingsXxxSection` + `SettingsXxxState/Actions` 三件套（参考 `DataSettingsSection.kt`）。**DATA 分区已包含「备份/恢复」，与导入歌单同属「数据导入」语义**，是天然的归属。

### 2.5 已有 UI 链路（待复用）

- 「我的」页面：`MineScreen.kt`，本地歌单走 `PlaylistCard` + 展开歌曲列表（`MineScreen.kt:222-267`）。
- 「设置 → 数据管理」分区：`DataSettingsSection.kt`，已有「导出备份 / 扫码传输 / 播放统计」三个入口 + 备份文件列表 + 消息提示。
- 「输入弹窗」：已存在 `TextInputDialog`（`MineScreen.kt:311-322` 复用），导入取名兜底时直接复用。
- 「导入进度/结果反馈」：已存在 `BackupMessage`（`data/model/BackupMessage.kt`，4 秒自动消费，模式与备份一致）。

### 2.6 已确认的产品取舍（不要反向修改）

- 已用 `kotlinx.coroutines` + DataStore Flow 反应式；导入后用 `localPlaylists.collect` 自动刷新 UI。
- 所有持久化数据走 `gson.toJson` 序列化（Gson type erasure 是已知坑，需在 `-keep` 规则里保留新加的 model 字段）。
- 设置入口放 DATA 分区：**有先例**（`DataSettingsSection.kt:51-72` 都是「导出/恢复/统计」类操作）；不新增顶级分区。

---

## 3. 设计概览

### 3.1 模块划分

```
backend/playlist/                         ← 新建包，导入/导出/补全的领域逻辑
├── PlaylistFileFormat.kt                 ← 文件格式定义 + parser 接口
├── M3uPlaylistParser.kt                  ← .m3u / .m3u8 解析
├── NeteaseCloudPlaylistParser.kt         ← 网易云歌单分享 JSON
├── JsonPlaylistParser.kt                 ← 本应用自导 JSON 格式（互通 + 兜底）
├── PlaylistImporter.kt                   ← 解析 + 转换 + 落库编排
└── PlaylistEnricher.kt                   ← 播放触发的元数据补全

ui/screens/settings/
└── PlaylistImportSettingsSection.kt      ← 设置 → 数据管理 → 导入歌单 UI 分区

ui/viewmodel/
└── PlaylistImportViewModel.kt            ← 导入/补全状态机
```

### 3.2 数据流

```
文件（用户选择）
   │
   ▼
ContentResolver.openInputStream(uri)  ── 走 BackupFileUtils.read 的 UTF-8 安全读法
   │
   ▼
PlaylistFileFormat.detectFromBytes/mime/filename
   │
   ▼
具体 Parser.parse(text) : List<RawSongEntry>
   │
   ▼
PlaylistImporter.run(uri, nameHint?)
   ├─ 取名兜底（文件名 / 用户输入 / 默认值）
   ├─ 去重（文件名内同一 (title, artist) 只保留一条）
   ├─ 转 RawSongEntry → 裸 Song（仅 title/artist/album 占位，id 暂用 stub）
   ├─ createLocalPlaylist + addSongToPlaylist * N
   └─ 写回 PlayList，导入新歌单
   │
   ▼
UI 自动 collect 到 localPlaylists 变化，「我的」页面出现新歌单

后续播放任一首裸 Song：
   │
   ▼
PlaylistEnricher.enrichSong(song)      ← 在 PlayerManager 解析流地址前触发
   ├─ 命中本地（isLocalSong）→ skip
   ├─ 命中 NAS（adapter.searchSongs）→ 用返回 Song 覆盖（保留原 id）
   ├─ 命中网络（networkMusicManager.search）→ 同上
   └─ 把覆盖后的 Song 写回 LocalPlaylist.songs 中的对应下标
```

### 3.3 关键设计决策

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 设置入口位置 | **数据管理（DATA 分区）**新增子区块「歌单导入」 | 同语义（导入类操作），不破坏现有 9 分区 |
| **与 Backup 导入的交互一致性** | **完全复用 Backup 模式**：① 操作按钮 = `SettingActionButton`；② 结果反馈 = `BackupMessage` + 4s 自动消费；③ 历史列表项镜像 `BackupFileRow`；④ 删除确认 = `ConfirmDialog`；⑤ Launcher 注册在 `MainActivity`；⑥ ViewModel 暴露 `fun import(uri: Uri)` 直接函数；⑦ State/Actions 三件套 | 与 Backup **逐项对齐**，避免 TV 焦点巡游 / 视觉错位（详见 §4.4.1 总表） |
| **与 Backup 导入的差异点（必要）** | 仅 1 处：**用户主动选文件入口**——Backup 用「应用自导出文件列表内点恢复」，歌单导入用 SAF `OpenDocument` 单文件选择器 | 用户从外部获取歌单文件，必须能选任意路径 |
| 导入自动建 | **导入完成即落库 + 立刻在「我的」显示** | 用户诉求 1 的字面要求；不阻塞、不弹模态，与 Backup 一致 |
| 取名兜底优先级 | 1. 文件内 `playlistName` 字段 → 2. 文件名（去扩展名，跳过无意义默认名）→ 3. **不再弹输入框** → 4. 默认「导入的歌单 MM-dd HH:mm」 | 用户诉求 2 的字面要求；输入框在 txt 场景下属于二次负担 |
| 补全触发时机 | **首次播放 + 周期每晚静默补一次** | 用户诉求 3；前台搜索会立刻可播 |
| 补全后台来源 | NAS 后端（已连）→ 网络音乐（多源 fallback）→ 仅记录失败 | 与现有 `SearchAggregator` 思路一致 |
| 重复歌曲判定 | `normalizeKey(title, artist)`（已存在的 `SearchAggregator.normalizeKey`） | 跨源同曲不重复入库 |
| 失败策略 | 单条失败不阻塞其余；最终给 UI「导入 N 成功 / M 失败 / 0 未识别格式」汇总 | 不让一条脏数据毁掉整次导入 |
| 导入并发 | 单次导入串行；导入过程阻塞弹窗 + 显示进度 | 用户体验简单、避免 race |
| 补全并发 | 每首歌独立 task；并发上限 4；按 `normalizeKey` 去重避免同一首被多次解析 | NAS 搜索 RTT 长，需并发 |
| **txt 行级分隔符优先级** | Tab > ` - ` > ` — ` > ` — ` > ` :: ` > `:` > `,`；**单短横 `-` 不作为分隔符** | 误识别 `U2-1` 这类合作标题；tab/空格分隔最稳 |
| **txt 序号与注释** | `1.` `1、` `1)` `①` `（1）` 编号剥离；`#` `//` 开头视为注释跳过 | 用户最常见的列表写法 |
| **txt artist 空时的补全** | 先按 title 精确匹配；失败再取搜索 Top1；Top1 也不命中则保留 stub + UI 提示「需手动指定艺术家」 | 不给用户强塞一首不相关的歌 |
| **无意义默认文件名白名单** | `playlist.txt` `list.txt` `新建文本文档.txt` 等 8 个常见默认名 → 跳过文件名兜底 | 防止「新建文本文档」当歌单名入库 |

---

## 4. 详细设计

### 4.1 文件格式与解析器

#### 4.1.1 支持的格式

| 扩展名 / MIME | 解析器 | 说明 |
| --- | --- | --- |
| `.m3u`, `.m3u8`（`audio/x-mpegurl`/`application/vnd.apple.mpegurl`） | `M3uPlaylistParser` | `#EXTM3U` 头；`#EXTINF:<sec>,<artist> - <title>` 行；跳过 `#EXTINF` 之外的元数据；空行忽略 |
| `.json`（检测 `#netease` 或顶部含 `neteasePlaylistId`） | `NeteaseCloudPlaylistParser` | 网易云公开歌单 API 返回结构 |
| `.json`（检测 `formatVersion` = `"nasmusic-playlist-v2"`，本应用自导格式） | `JsonPlaylistParser` | 见 §4.6 |
| `.txt` / `.list` / `.csv`（用户自编辑文本） | `TextPlaylistParser`（**§4.1.7 详述**） | 每行一首；分隔符可选；artist 可空；支持 BOM/编号/全角符号/GBK 编码 |

**未识别的格式**：在 UI 上展示「未识别格式 / 仅识别到 N 条」，不弹错误。

#### 4.1.2 原始条目结构

```kotlin
// backend/playlist/PlaylistFileFormat.kt
data class RawSongEntry(
    val title: String,         // 必填；空则跳过并计数
    val artist: String,        // 可空，""
    val album: String? = null, // 可空
    val durationSec: Int? = null,
    /** 解析阶段已识别的本地/网络 hint（不持久化，仅供补全参考） */
    val sourceHint: SourceHint = SourceHint.NONE,
    val originalIndex: Int,     // 文件内位置（用于 UI 显示顺序）
    /**
     * 直接 URL（M3u 的 path hint 等）：如果非空，可直接构造 Song.streamUrl，
     * 无需经过「按 title/artist 搜索 → 解析流地址」的补全链。
     * 详见 §4.1.8（URL 类型分类与可达性测试）。
     */
    val directUrl: String? = null,
    /** directUrl 的来源类型（仅在 directUrl 非空时有意义） */
    val directUrlType: DirectUrlType = DirectUrlType.NONE,
)

enum class SourceHint { NONE, NETWORK_METING, LOCAL_FILE, NAS }

/**
 * URL 分类（§4.1.8）：
 * - NONE：无 URL（txt/纯文本/网易云 JSON 路径）
 * - HTTP：远程 HTTP/HTTPS，需要可达性测试
 * - LOCAL_URI：file:// 形式（绝对路径），直接可播
 * - ABSOLUTE_PATH：以 / 开头的绝对路径（视为本地文件，需要映射到本机路径）
 * - RELATIVE_PATH：相对路径（m3u 文件所在目录 + 相对路径，本期不支持映射，记入 skipped）
 */
enum class DirectUrlType { NONE, HTTP, LOCAL_URI, ABSOLUTE_PATH, RELATIVE_PATH }
```

> 解析阶段不查网络、不读文件路径、不连 NAS。**只把文本抽出来**。
> 后续补全阶段才决定这条目标 NAS / 网络 / 本地。

#### 4.1.3 解析器接口

```kotlin
interface PlaylistParser {
    /** 是否能解析该文本（基于首 4KB 嗅探） */
    fun canParse(headBytes: ByteArray, fileName: String): Boolean
    /** 抛 ParseException 让上层弹错误；返回空列表视为「不识别」 */
    fun parse(text: String, fileName: String): List<RawSongEntry>
}
```

#### 4.1.4 M3u 解析要点

- 逐行扫描；遇到 `#EXTINF:` 行解析 `<秒>,<剩余>`：
  - 标准：`artist - title`（含 ` - ` 分隔）
  - 仅 `title`：剩余字段当 title，artist 留空
  - `#EXTINF` 行后的第一个非注释、非空行即为该条目的 path hint（标准 M3u 语义），**记录到 `RawSongEntry.directUrl`**（URL 分类见 §4.1.8）
- 若存在「无 `#EXTINF` 关联的裸 URL 行」（如整文件只有 `http://.../song.mp3` 的单行导出），该行自成一个条目：title=URL 文件名（去扩展名）、artist 空、`directUrl`=该 URL（§4.1.8 (1) 归属规则）
- 跳过 `#EXT-X-` 系列（直播用）、`#PLAYLIST:` 等头
- 字符编码：M3u 在中文社区常 GBK 编码；统一先 UTF-8 解码，失败回退 GBK（与 `util/EncodingUtils.kt` 同样的双层尝试，复用工具）

#### 4.1.5 网易云歌单解析要点

- 接受两种输入：
  1. 公开歌单页导出的「完整 JSON」（带 `tracks[]`，每项 `name/artists[]/album/duration`）—— 用户手动从网页抓的分享链接 JSON
  2. 网易云 App「分享歌单 → 复制链接」得到的 URL（含 `id=xxxx`）—— 该目录 URL **若出现在 m3u / txt 的 URL 行中，按 §4.1.8 通用规则处理，本期即可支持**：捕获为 `directUrl` 条目 → 后台做可达性判断 → **判定可达则保留**（作为「URL 直链」条目入库，可播放/筛选）；不做的仅是「把分享链接联网解析成歌单内的歌曲列表」（该能力需预置网易云 API 端点，属 **V2**，见 §10）
- `artists[].name` 用 `、` 拼成 artist 字段
- 过滤空 title / artist

#### 4.1.7 文本歌单（`TextPlaylistParser` —— 用户自编辑文档）

> **注**：v1 方案此处原写「简单 txt 兜底」一句话占位；本版用户确认要把 txt 作为**独立且高优先级**的格式，本节给出完整规则。

**典型场景**：用户在文本编辑器（记事本 / VS Code / Markdown 笔记）里手敲的歌单，每行一首歌，可能带艺术家也可能不带。**这是用户最常用的导入形式**，必须给到完整的解析规则。

##### (1) 行级格式

每条歌占一行，按以下规则按顺序匹配：

```
一首歌 = [序号] [title] [分隔符 artist]
```

- **序号**（可选）：行首的 `1.` `1、` `1)` `①` 等编号列表标记，识别后丢弃。编号本身可以带前导空格（` 1. xxx` 也接受）。
- **title**（必填，去首尾空白后非空）：歌名。允许包含空格、标点、中英文混排。
- **分隔符**（可选）：title 与 artist 之间的分隔符，**按以下优先级取第一个匹配的**：

| 优先级 | 分隔符 | 备注 |
| --- | --- | --- |
| 1 | `\t` (Tab) | 优先级最高，避免误识别 |
| 2 | ` - ` （半角空格 + 半角短横 + 半角空格） | 用户最常用写法 |
| 3 | ` — ` （半角空格 + 长破折号 + 半角空格） | M3u 导出常见 |
| 4 | ` – ` （半角空格 + 半角短横 + 半角空格） | 微软拼音输入法偏好 |
| 5 | ` -- ` （双短横） | 部分老用户习惯 |
| 6 | ` :: ` （冒号冒号） | Last.fm 导出格式 |
| 7 | `:` （中文/英文冒号，带前后空格） | 较少见，作为更宽松兜底 |
| 8 | `,` （中文/英文逗号，仅在 title 段超过 8 个字符时启用，避免误识别短标题） | 中文社区常见 |

**关键约束**：单短横 `-`（无空格环绕）**不作为分隔符**，避免误识别 `U2-1` 这种合作曲标题。

- **artist**（可选）：分隔符右侧剩余文本。**允许为空**（这是 txt 类型最常见的形态 —— 用户懒得写艺术家）。

##### (2) 行级特殊规则

| 输入 | 解析结果 | 备注 |
| --- | --- | --- |
| `海阔天空` | title=`海阔天空`, artist=`""` | 单段；artist 留空 |
| `海阔天空 - Beyond` | title=`海阔天空`, artist=`Beyond` | 标准形态 |
| `海阔天空 - Beyond - 黄家驹` | title=`美阔首首`, artist=`Beyond - 黄家驹` | **只切第一个分隔符**，右侧整体作为 artist |
| `海阔天空\tBeyond` | title=`海阔天空`, artist=`Beyond` | Tab 优先级最高 |
| `1. 海阔天空 - Beyond` | title=`美阔首首`, artist=`Beyond` | 编号 `1.` 剥离 |
| `（1）海阔天空 - Beyond` | title=`美阔首首`, artist=`Beyond` | 中文括号编号也接受 |
| `# 我最喜欢的歌` | **跳过** | 井号开头视为注释 |
| `// 2025 年新版` | **跳过** | 双斜杠开头视为注释 |
| `（空白行）` | **跳过** | 空行 / 仅空白字符 |
| `      ` | **跳过** | 仅空白 |
| `  ` | **跳过** | 仅空白 |
| `海阔天空(Live) - Beyond` | title=`美阔天空(Live)`, artist=`Beyond` | 不去掉 `()内信息`（避免误伤） |
| `起风了（Cover：买辣椒也用券） - 买辣椒也用券` | title=`起风了（Cover：买辣椒也用券）`, artist=`买辣椒也用券` | 全角括号保留 |
| `海阔天空（2019 现场） - Beyond` | title=`美阔天空（2019 现场）`, artist=`Beyond` | 现场版本标记保留 |
| `WBC - Beyond` | title=`WBC`, artist=`Beyond` | 短标题也走分隔符（仅单短横 `-` 不作为分隔符） |

##### (3) 文件级规则

- **首行作为歌单名 hint**：如果首行是 `# title: 我的歌单` 或 `// 歌单：我的歌单` 这种「带前缀标识的注释行」，提取 `:` 或 `：` 后的文本作为 `innerName`，进入取名兜底链。否则首行也走歌单解析。
- **UTF-8 BOM**：首字节 `EF BB BF` 必须剥离，避免 title 首字符变成 U+FEFF 导致搜索失败。
- **行尾符**：CRLF (`\r\n`) / LF (`\n`) / CR (`\r`) 都接受；行内不去掉内部换行。
- **编码**：与 M3u 同样先 UTF-8 解码，失败回退 GBK（中文社区 txt 常见 GBK）。
- **大小限制**：单文件 ≤ 5 MB；超过截断并提示「文件过大已截断至 N 亿件」（**不可能一首 5MB，但极端情况需防 OOM**）。
- **空文件 / 全部被识别为注释 / 全部无 title**：返回空列表，UI 弹「未识别到有效条目」（与 §4.4 弹窗路径一致）。

##### (4) 与其他格式的区分

- 文件选择器接受 `.txt`、`.list`、`.csv` 三种扩展名（用户在 Windows / Mac 上保存习惯不同）。
- **嗅探顺序**：扩展名决定优先走的 Parser —— `.m3u` / `.m3u8` 走 M3u；`.json` 走 Json / 网易云嗅探；**`.txt` / `.list` / `.csv` 一律走 TextPlaylistParser**。
- 兜底链：如果扩展名是 `.txt` 但内容形如 `#EXTM3U`，仍走 M3u（头部嗅探优先于扩展名），保证从其他工具导出的 `.txt` 后缀 M3u 也能识别。
- **本应用不导出 txt**（用户自编辑输入，没有必要由本应用写出）；`JsonPlaylistParser.serialize()` 仅写 JSON v2（§4.10 / §8 阶段 6 不变）。

##### (5) 取名兜底补充

§4.2 `resolvePlaylistName` 链路不变；但 txt 文件特别允许以下补充规则：

```
如果 innerName 为空），文件名为 TODO 或 "playlist.txt" / "list.txt" / "新建文本文档.txt" 这种「无意义默认名」，
→ 跳过文件名兜底，直接弹输入框让用户输入，或用默认「导入的歌单 MM-dd HH:mm」
```

「无意义默认名」白名单（大小写不敏感）：

```
playlist.txt, list.txt, songs.txt, music.txt,
新建文本文档.txt, 无标题.txt, untitled.txt, default.txt
```

命中白名单时，**不要把"新建文本文档"当成用户歌单名建库**。

##### (6) 补全阶段 artist 为空的策略

txt 文件常见「仅有 title」条目。补全阶段（§4.3）调整：

```
if (artist.isBlank()) {
    // 退化为：仅按 title 搜索，取排名第一
    val hits = networkMusicManager.search(title)
    return hits.firstOrNull { normalizeKey(it.title) == normalizeKey(title) } ?: hits.firstOrNull()
}
```

- 优先按 title 精确匹配；命中失败退而取搜索排名第一（**风险**：Top 1 不一定是用户想要的那首歌 → 见 §7 风险表新增条目）。
- 命中失败时，stub 标记「🔍 待补全」同时显示「需手动指定艺术家」次级提示，**不要自动补一首不相关的歌**。

##### (7) 测试要点

| 测试用例 | 输入片段 | 期望 |
| --- | --- | --- |
| 纯标题 | `海阔天空\n甜蜜蜜\n小幸运` | 3 条；artist 全空 |
| 标准分隔 | `海阔天空 - Beyond\n甜蜜蜜 - 邓丽君` | 2 条；artist 各自正确 |
| Tab 分隔 | `海阔天空\tBeyond\n甜蜜蜜\t邓丽君` | 2 条；Tab 优先 |
| 长破折号 | `海阔天空 — Beyond` | 解析成功 |
| 编号列表 | `1. 海阔天空 - Beyond\n2. 甜蜜蜜` | 解析成功；编号剥离 |
| 单短横误识别 | `U2-1 - Beyond` | title=`U2-1`, artist=`Beyond`（不是 `U2`） |
| 注释跳过 | `# 标题注释\n海阔天空` | 仅 1 条 |
| 空行跳过 | `海阔天空\n\n\n甜蜜蜜` | 2 条 |
| GBK 编码 | 字节 `B8 A5 BF ED CC EC B8 A5`（GBK "海阔天空"） | 解析成功 |
| BOM | 文件首 3 字节 `EF BB BF` + `海阔天空` | 解析成功；title 不含 U+FEFF |
| 3+ 段 | `Beyond - 黄家驹 - 海阔天空` | title=`Beyond`, artist=`黄家驹 - 海阔天空` |
| 默认文件名 | 文件名=`新建文本文档.txt` | 跳过文件名兜底，弹输入框 |
| 默认文件名 | 文件名=`我的歌单.txt`，内容=`海阔天空 - Beyond` | 歌单名用 `我的歌单` |
| 空文件 | 0 字节 | 弹「未识别到有效条目」 |
| 全部注释 | 内容仅 `# xxx` | 同上 |
| 巨行 | 单行 1 MB | 跳过（防止一行 OOM），计入 `skipped` |

#### 4.1.8 URL 直接使用与可达性测试（**新增重点**）

> **核心价值**：M3u 文件本身就支持「每首歌配一个 URL」（path hint）。很多用户从 NAS 客户端 / 网易云客户端导出的歌单**都带 URL**。本期让这些 URL「直接可播放」，不再强制走「按 title / artist 搜索」的补全链，**极大提升导入后的体感**（尤其是本地 NAS 歌单）。

##### (1) URL 来源与捕获

| 文件格式 | URL 出现位置 | 捕获方式 |
| --- | --- | --- |
| **M3u / M3u8** | `#EXTINF:duration,artist - title` 的**下一行**(即 path hint 行) | 解析器在 `#EXTINF` 之后读到下一非注释行,直接当 `directUrl` |
| **网易云分享 JSON** | 无 | `directUrl=null` |
| **本应用自导 JSON v2** | 无(本期不写 streamUrl,与 §4.6 一致) | `directUrl=null` |
| **Txt** | 无(用户没写 URL) | `directUrl=null` |

**捕获后的 `directUrl` 在 `RawSongEntry` 阶段分类**(见 §4.1.2 `DirectUrlType`):

| URL 形态 | 类型 | 是否需要可达性测试 |
| --- | --- | --- |
| `http://...` / `https://...` | `HTTP` | **需要**(§4.1.8 (3)) |
| `file://...` | `LOCAL_URI` | **不需要**(ExoPlayer 直接播) |
| `/path/to/file.mp3` 开头 | `ABSOLUTE_PATH` | **不需要**(视为本机路径,直接映射) |
| `path/to/file.mp3` 开头 | `RELATIVE_PATH` | **不支持**(SAF URI 不易取父目录,见 §7 风险表),计入 `skipped` 并在导入消息里说明 |

> **path hint 归属规则**：「`#EXTINF` 之后的第一个非注释、非空行」是该条目的 path hint；若某 URL 行前面没有待归属的 `#EXTINF`（整文件只有 URL），该行自成一个条目（title=URL 文件名，去扩展名）。
>
> **关键事实核对（2026-09-18）**：`Song.stripVolatileStreamUrl()`（`AppPreferences.kt:512`）**仅当 `isNetworkSong=true` 时清空 `streamUrl`**。因此 §4.1.8 (2) 把 HTTP URL 条目的 `isNetworkSong` 置为 `false` 后，URL 可以安全持久化进本地歌单并穿过 `addSongToPlaylist` 的 6 处清理点，重启后仍可直接播放。

##### (2) `toBareSong` 升级:带 URL 的 Song 直接可播放

原方案 §4.2 `toBareSong` 全部产 `streamUrl=null` 的裸 Song。新版按 `directUrl` 分支:

```kotlin
private fun toBareSong(raw: RawSongEntry): Song {
    val stubId = "imported_${UUID.randomUUID()}"
    return when {
        // A. HTTP URL: 默认带 streamUrl,ExoPlayer 可直接播;
        //    失败时走补全回退链(§4.3.2 算法 B)
        raw.directUrlType == DirectUrlType.HTTP -> Song(
            id = stubId,
            title = raw.title.trim(),
            artist = raw.artist.trim(),
            album = raw.album?.trim().orEmpty(),
            streamUrl = raw.directUrl,    // ★ 关键:HTTP URL 直接落地
            isNetworkSong = false,        // 不走 networkMusicManager.resolvePlayUrl
        )
        // B. 本地 file:// 或绝对路径: 直接可播放(可达性无需测试)
        raw.directUrlType == DirectUrlType.LOCAL_URI ||
        raw.directUrlType == DirectUrlType.ABSOLUTE_PATH -> Song(
            id = stubId,
            title = raw.title.trim(),
            artist = raw.artist.trim(),
            album = raw.album?.trim().orEmpty(),
            streamUrl = raw.directUrl,
        )
        // C. 其他(无 URL): 走补全链(原行为)
        else -> Song(
            id = stubId,
            title = raw.title.trim(),
            artist = raw.artist.trim(),
            album = raw.album?.trim().orEmpty(),
        )
    }
}
```

##### (3) HTTP URL 可达性测试(`UrlReachabilityChecker`)

> **核心问题**：导入 1 万首 M3u，默认构造 1 万个带 URL 的 Song，**但其中可能 30% 的 URL 已失效**（NAS 端删文件、CDN 资源过期）。如果用户直接点播，ExoPlayer 会逐个报 404，**用户体验差**。所以需要可达性测试，但又**不能在导入时阻塞**做 1 万次 HEAD。

**架构选择**:

```kotlin
class UrlReachabilityChecker(
    private val client: OkHttpClient = defaultClient(),
    private val cacheTtlMs: Long = 5 * 60 * 1000L,  // 5 分钟,与 NetworkMusicManager 一致
) {
    enum class Result {
        REACHABLE,         // 2xx/3xx → 可播放
        NOT_FOUND,         // 404 → 资源不存在
        SERVER_ERROR,      // 5xx → 服务端错误,可重试
        TIMEOUT,           // 5s 内未响应
        DNS_FAILED,        // 域名解析失败(NAS 关机/网络断)
        REDIRECT_LOOP,     // 重定向循环
        INVALID_URL,       // URL 非法(无法 parse)
    }

    /**
     * 单个 URL 测试
     * @param url 待测 URL
     * @param timeoutMs 单次超时(默认 5000ms);0 表示不超时
     * @return Result + Content-Length(若有);命中缓存直接返回
     */
    suspend fun check(url: String, timeoutMs: Long = 5000L): Pair<Result, Long?>

    /** 批量并发测试(并发上限 4),按顺序返回;失败条目抛 Result */
    suspend fun checkBatch(urls: List<String>, timeoutMs: Long = 5000L): List<Pair<Result, Long?>>
}
```

**实现要点**(基于 OkHttp,模仿 `SongDownloadManager.headContentLength` 已有的写法):

```kotlin
suspend fun check(url: String, timeoutMs: Long = 5000L): Pair<Result, Long?> {
    // 1. 查内存缓存(5 分钟 TTL)
    cache[url]?.let { (res, ts, length) ->
        if (System.currentTimeMillis() - ts < cacheTtlMs) return res to length
    }
    // 2. URL 合法性
    val parsed = runCatching { url.toHttpUrl() }.getOrNull()
        ?: return Result.INVALID_URL to null
    // 3. OkHttp HEAD(自动 followRedirects=true,max 3 跳)
    val req = Request.Builder().url(parsed).head().build()
    val result = withContext(Dispatchers.IO) {
        val call = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            .newCall(req)
        try {
            call.execute().use { resp ->
                val length = resp.header("Content-Length")?.toLongOrNull()
                val res = when {
                    resp.isRedirect -> Result.REDIRECT_LOOP     // OkHttp 已跟到第 3 跳仍重定向
                    resp.isSuccessful -> Result.REACHABLE       // 2xx
                    resp.code == 404 -> Result.NOT_FOUND
                    resp.code in 500..599 -> Result.SERVER_ERROR
                    else -> Result.NOT_FOUND                   // 其他 4xx 一律视为不可达
                }
                res to length
            }
        } catch (e: SocketTimeoutException) { Result.TIMEOUT to null }
        catch (e: UnknownHostException) { Result.DNS_FAILED to null }
        catch (e: SSLException) { Result.DNS_FAILED to null }    // 局域网 NAS 多见
        catch (e: ConnectException) { Result.DNS_FAILED to null }
        catch (e: Exception) { Result.SERVER_ERROR to null }
    }
    cache[url] = Triple(result.first, System.currentTimeMillis(), result.second)
    return result
}
```

**OkHttpClient 配置**(复用 `SongDownloadManager` 已有的配置风格,**不新建**):

```kotlin
private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .retryOnConnectionFailure(true)
    .build()
```

##### (4) 何时测试可达性:导入时 vs 播放时

| 时机 | 触发方式 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **A. 导入时批量测** | `PlaylistImporter.import()` 完成后,异步启动批量 HEAD | 用户点播立即可用 | 1 万首导入卡 30 分钟;用户等不及 |
| **B. 播放时测** | 用户点播 → ExoPlayer 尝试加载 → 失败才触发 | 零阻塞 | 首播失败体验差(用户已点击) |
| **C. 折中(本期采用)** | 导入**同步只解析不测**（HTTP URL 直接落地 `streamUrl`，可播）；导入完成后**后台异步批量测**（并发 4、超时 5s、5 分钟缓存、单 URL 只测一次）标出不可达；**首次播放时若 URL 失效**再走 §4.1.8 (5) 回退链 | 导入不阻塞（默认 < 5s）；首播前已确认可达；查不到的自动补链 | 不可达标记依赖 HEAD，防盗链 CDN 可能 HEAD 200 / GET 403（已列 §7 风险表） |

**采用方案 C 的实现**:

```kotlin
// PlaylistImportViewModel.importPlaylist(uri)
fun importPlaylist(uri: Uri) = viewModelScope.launch {
    val songs = playlistImporter.import(uri)  // 解析 + 入库：HTTP URL 已直接落地 streamUrl（§4.1.8 (2) 分支 A），**此处不做可达性测试**
    
    // 后台异步:对所有公网 HTTP URL 做批量可达性测试,标记不可达条目
    // 局域网段(192.168.x / 10.x / 172.16-31.x)按 §4.1.8 (6) 不预判,由 ExoPlayer 承担
    val httpSongs = songs.filter { it.streamUrl?.startsWith("http") == true }
                        .filterNot { urlReachabilityChecker.isPrivateLanUrl(it.streamUrl!!) }
    if (httpSongs.isNotEmpty()) {
        launch {
            val results = urlReachabilityChecker.checkBatch(httpSongs.mapNotNull { it.streamUrl })
            results.forEachIndexed { idx, (result, _) ->
                if (result != UrlReachabilityChecker.Result.REACHABLE) {
                    // 标记不可达 → UI 显示「🔗 URL 已失效」
                    prefs.playlist.markSongUnreachable(httpSongs[idx].id)
                }
            }
        }
    }
}
```

**对用户的影响**：
- 导入歌单（< 5 秒完成）
- 后台慢慢跑可达性测试（用户已经在听第一首）
- 第一首歌 streamUrl 已经在 ExoPlayer 上，可达/不可达由 ExoPlayer 自然感知
- 不可达的歌曲：UI 标记「🔗 URL 已失效」+ 走 §4.3.2 算法 B 的「按 title/artist 搜索补全」回退链

##### (5) 不可达回退链(与 §4.3 补全协同)

```
播放 streamUrl=http 的 stub 歌曲
    │
    ├── ExoPlayer 加载(http URL)
    │      │
    │      ├── 200 OK → 正常播
    │      ├── 302/301 → OkHttp 已 follow,落到新 URL,继续
    │      └── 404/timeout/connect-fail → 播放失败
    │
    ▼ (检测到播放失败)
   onPlayerError callback → MainViewModel.playbackFailure(song)
    │
    ├── 可达性测试 result == NOT_FOUND / DNS_FAILED
    │      │
    │      ├── 命中 NAS → replaceSongInPlaylist(...)
    │      ├── 命中网络 → replaceSongInPlaylist(...)
    │      └── 都未命中 → 保留 stub + UI 标记「🔗 URL 失效」 + 「🔍 待补全」
    │
    ▼ (UI 标记)
   「URL 失效 + 待补全」(双标签,用户能区分是 URL 问题还是搜索问题)
```

**关键决策**:
- **不**在 ExoPlayer 失败时主动 retry(避免重试风暴)
- **不**在可达性失败时自动切走当前播放(用户可能想听别的歌)
- 失败时给用户**明确的反馈**:UI 上「🔗 URL 失效」+「🔍 待补全」双标签

##### (6) 局域网 NAS URL 特别说明

M3u 中常见的 `http://192.168.1.100/music/song.mp3` 这种局域网 URL：
- 设备与 NAS 同网时天然可达，HEAD 预判通常多余（部分 NAS 对 HEAD 反应异常却支持 GET Range）
- 设备网络切换（访客 WiFi / 流量）后可能不可达——**这种变化无法在导入时预判**
- 因此局域网段 URL **不进批量可达性测试**（`UrlReachabilityChecker.isPrivateLanUrl` 过滤），由 ExoPlayer 在**首次播放**时从设备当前网络去访问，能播则播
- 播放失败时照常走 §4.1.8 (5) 回退链（复测 + 按 title/artist 补全）

**结论**：**局域网 URL 的可达性判定由 ExoPlayer 自然承担**，不提前测。批量可达性测试只覆盖**公网 URL**（网易云 / 酷狗 / 第三方 CDN 等）。

##### (7) 测试要点

| 测试用例 | 输入 | 期望 |
| --- | --- | --- |
| M3u 带 http URL | `#EXTINF:300,海阔天空 - Beyond\nhttp://example.com/song.mp3` | `directUrl=http://example.com/song.mp3`,`directUrlType=HTTP` |
| M3u 带 file:// URL | `#EXTINF:300,x\nfile:///sdcard/Music/x.mp3` | `directUrl=file:///sdcard/Music/x.mp3`,`directUrlType=LOCAL_URI` |
| M3u 带绝对路径 | `#EXTINF:300,x\n/sdcard/Music/x.mp3` | `directUrlType=ABSOLUTE_PATH` |
| M3u 带相对路径 | `#EXTINF:300,x\nmusic/x.mp3` | 计入 skipped |
| M3u 单行(无 #EXTINF) | `http://example.com/song.mp3` | title=URL 文件名(去 .mp3),artist 空,`directUrl=URL` |
| 可达性:2xx | mock HTTP 200 | REACHABLE |
| 可达性:404 | mock HTTP 404 | NOT_FOUND |
| 可达性:5xx | mock HTTP 503 | SERVER_ERROR |
| 可达性:timeout | mock 5s 不返回 | TIMEOUT |
| 可达性:DNS 失败 | mock `UnknownHostException` | DNS_FAILED |
| 可达性:302 跟随 | mock 302 → 200 | REACHABLE(跟随后) |
| 可达性:重定向循环 | mock 302 → 302 → 302 → ... | REDIRECT_LOOP |
| 可达性:5 分钟缓存 | 同 URL 测 2 次,中间无服务端变更 | 第 2 次不发起请求 |
| ExoPlayer 404 回退 | URL NOT_FOUND,触发 enrichSong | 走补全链,NAS/网络命中替换 |
| 局域网 URL 不预测 | URL=192.168.x.x | 导入时不测;ExoPlayer 加载时由 OS 网络层判 |
| isPrivateLanUrl 过滤 | 192.168.1.1 / 10.0.0.1 / 172.16.0.1 | 仅前 3 个(私有网段)过滤不测;8.8.8.8 等公网照常测 |

### 4.2 导入编排（`PlaylistImporter`）

```kotlin
class PlaylistImporter(
    private val app: Application,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
) {
    data class ImportSummary(
        val playlistId: String,
        val playlistName: String,
        val imported: Int,         // 实际入库条目数
        val skipped: Int,          // 文件内重复/无效条目
        val unrecognizedFormat: Boolean = false,
    )

    /** UI 进度回调：每完成 25% / 完成 / 错误触发一次 */
    fun import(
        uri: Uri,
        userConfirmedName: String? = null,
        onProgress: (Int) -> Unit = {},
    ): ImportSummary
}
```

**取名兜底（按优先级）**：

```kotlin
/** 与 §4.1.7 (5) 配合：文件名命中「无意义默认名」白名单时，跳过文件名兜底 */
private val DEFAULT_PLAYLIST_FILENAMES = setOf(
    "playlist", "list", "songs", "music",
    "新建文本文档", "无标题", "untitled", "default"
)

fun resolvePlaylistName(
    fileName: String,           // "我的歌单.m3u" 或 "新建文本文档.txt"
    innerName: String?,         // 文件 #PLAYLIST 头 / JSON 内 name 字段 / null（txt 首行注释也走这里）
    userInput: String?,         // 用户输入框（可空）
): String {
    userInput?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    innerName?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    val fromFile = fileName.substringBeforeLast('.', missingDelimiterValue = fileName).trim()
    if (fromFile.isNotBlank() && fromFile != fileName
        && fromFile.lowercase() !in DEFAULT_PLAYLIST_FILENAMES) {
        return fromFile
    }
    // 文件名命中白名单或取不到 → 直接走默认值，跳过输入框（输入框留给「需要用户决策」的场景）
    return "导入的歌单 ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())}"
}
```

**调整后的弹框触发条件**：原方案是「文件无内嵌名 + 文件名也取不到时弹输入框」；新版简化为「**直接走默认值**，不再弹输入框」。理由：用户已经在编辑 txt 文档里手敲歌单了，再让其取名属于二次负担；多数用户偏好「导入即用」体验。如用户明确希望弹输入框取名（PM 后续可决策），可在导入完成后通过「我的 → 重命名歌单」重命名（已有入口）。

**裸 Song 构造**：

```kotlin
private fun toBareSong(raw: RawSongEntry): Song {
    val stubId = "imported_${UUID.randomUUID()}"   // 临时 id；首次播放时被 NAS / 网络 id 替换
    return Song(
        id = stubId,
        title = raw.title.trim(),
        artist = raw.artist.trim(),
        album = raw.album?.trim().orEmpty(),
        isNetworkSong = false,        // 初始为 false；补全命中网络后会切 true
        isLocalSong = false,
    )
}
```

**入库步骤**：

1. `prefs.playlist.createLocalPlaylist(finalName)` → 得到新 `LocalPlaylist`
2. 文件内 `normalizeKey(title, artist)` 去重，重复条目计入 `skipped`
3. 逐条 `prefs.playlist.addSongToPlaylist(id, bareSong)`，触发 `stripVolatileStreamUrl` 链路
4. 返回 `ImportSummary`

**进度**：`addSongToPlaylist` 是挂起函数，按已处理条目 / 总数算百分比，每 25% 回调一次。

**源文件生命周期（上传的歌单文件不留存）**：

- **只读流式解析，不落盘**：`import(uri)` 通过 `contentResolver.openInputStream(uri)` 边读边解析，**不把源文件拷贝到应用内部存储**；解析结束（成功或异常）立即 `close()` 关闭流，SAF 可能产生的系统临时拷贝由系统自动清理。
- **导入成功后源文件即可安全删除**：歌单内容已在导入过程中完整落库到 `LocalPlaylist`（DataStore），后续补全、播放、URL 可达性全部基于库内数据，**与源文件无任何依赖**；用户删除源文件不影响已导入歌单的任何功能。
- **应用内不留副本**：不做"保存一份原始歌单文件"之类的设计（无重新解析需求）；若日后要"重新导入同一文件"，由用户重新走一次 SAF 选取即可。
- **不提供"删除源文件"UI**：SAF 文件所有权在用户侧，应用只保证自己不保存，不代为执行删除。

### 4.3 补全机制（`PlaylistEnricher`）

#### 4.3.1 触发点

- **路径 A（用户体感最强）**：用户播放导入歌单或单首导入歌曲时。挂在 `MainViewModel.playQueue()` / `MainViewModel.playNetworkSong()` 入口；对 `playlist.songs` 中所有 `id.startsWith("imported_")` 的条目，触发补全。
- **路径 B（兜底）**：「我的」歌单卡片右上加「补全」按钮（手动触发）；批量补全时显示进度。
- **路径 C（夜间）**：~~可选 P2 —— WorkManager 每晚扫一次导入歌单里的残留 stub~~ **已否决（2026-09-18，用户决策）**：不需要夜间/后台全量补全，补全仅在播放时触发（路径 A / 路径 D 已覆盖全部诉求），不引入 WorkManager 依赖。
- **路径 D（**新增**）**：播放 ExoPlayer 报 URL 失效时（404 / timeout / DNS），由 `onPlayerError` 回调到 `MainViewModel.playbackFailure(song)` → **复测确认后**触发 `enrichSong`（仅对 `id.startsWith("imported_")` 且 `streamUrl?.startsWith("http")==true` 的条目触发，见 §4.1.8 (5)）。

#### 4.3.2 补全算法

```kotlin
suspend fun enrichSong(stub: Song): Song? {
    if (!stub.id.startsWith("imported_")) return null
    // 1) 已连 NAS + adapter.searchSongs → 命中
    runCatching {
        app.backendRegistry.getAdapter()?.searchSongs("${stub.title} ${stub.artist}".trim())
    }.getOrNull()?.firstOrNull { match ->
        normalizeKey(match.title, match.artist) == normalizeKey(stub.title, stub.artist)
    }?.let { return it }  // 命中后用真正的 NAS Song 替换

    // 2) NetworkMusicManager.search（多源 fallback）
    runCatching {
        app.networkMusicManager.search(stub.title)
    }.getOrNull()?.firstOrNull { match ->
        normalizeKey(match.title, match.artist) == normalizeKey(stub.title, stub.artist)
    }?.let { networkHit ->
        // networkHit 的 streamUrl 是空的，需要走 resolvePlayUrl 链路才能播；
        // 但 playlist 内 streamUrl 不持久化，所以这里不需要解析 streamUrl
        return networkHit.copy(streamUrl = null)
    }

    return null  // 未命中，保留裸条目，UI 标记「未找到」
}
```

**带 URL 的歌曲回退策略**（**新增**，由 §4.1.8 (5) 触发）：

```kotlin
// MainViewModel.playbackFailure(song: Song)
suspend fun playbackFailure(song: Song) {
    if (!song.id.startsWith("imported_")) return             // 仅历史导入歌单关注
    if (song.streamUrl?.startsWith("http") != true) return    // 仅 HTTP URL

    // 调用可达性测试,确认 URL 真失效
    val (result, _) = urlReachabilityChecker.check(song.streamUrl!!)
    when (result) {
        UrlReachabilityChecker.Result.REACHABLE -> {
            // URL 实际可达,但当前播放失败 → 不走补全;不修改 song,留作下次重试
            AppLog.w(TAG, "playbackFailure: $result but current play failed; leave as-is")
            return
        }
        UrlReachabilityChecker.Result.REDIRECT_LOOP,
        UrlReachabilityChecker.Result.DNS_FAILED,
        UrlReachabilityChecker.Result.NOT_FOUND -> {
            // 真不可达 → 走补全链(算法 B)
            val enriched = enricher.enrichSong(song) ?: return
            // enriched 是 NAS/网络源 Song,可能仍带 streamUrl(如 NAS 直接命中)
            prefs.playlist.replaceSongInPlaylist(playlistId, song.id, enriched)
            // UI 标记「🔗 URL 失效 → 已补全到 NAS/网络源」
        }
        UrlReachabilityChecker.Result.TIMEOUT,
        UrlReachabilityChecker.Result.SERVER_ERROR -> {
            // 暂时不可达 → 不修改;UI 标记「🔗 URL 已失效」(24h 判定窗口后自动复测,见 §4.7.4)
            prefs.playlist.markSongUnreachable(song.id)
        }
        UrlReachabilityChecker.Result.INVALID_URL -> {
            // URL 本身非法(SAF / SAF 后被改)→ 不再尝试,UI 标记「🔗 URL 已失效」
            prefs.playlist.markSongUnreachable(song.id)
        }
    }
}
```

**新增 Pref 方法**：

```kotlin
/** 标记歌曲 URL 不可达(供 UI 显示"🔗 URL 失效"标签);不修改 songs 列表 */
suspend fun markSongUnreachable(songId: String)
suspend fun clearSongUnreachable(songId: String)
```

持久化为单独的 `keySongReachability: Map<songId, ReachabilityResult>`（5 分钟内存缓存作权威，持久化仅供重启后保留「已知不可达」信息，**重启后 24 小时内不重新测**；持久化设计与备份/恢复联动见 §4.7.4）。

#### 4.3.3 写回歌单

```kotlin
suspend fun enrichAndPersist(playlistId: String, stub: Song): Boolean {
    val enriched = enrichSong(stub) ?: return false
    // 用 enriched 替换原 stub（按原 id 找到位置，替换为 enriched）
    prefs.playlist.replaceSongInPlaylist(playlistId, stub.id, enriched)
    return true
}
```

**新增 Pref 方法**（`AppPreferences.kt`）：

```kotlin
/** 用 newSong.id 在 songs 中找到 oldId 的下标并替换；oldId 不存在则忽略 */
suspend fun replaceSongInPlaylist(playlistId: String, oldSongId: String, newSong: Song)
```

**新增 Pref 方法**（`PlaylistPrefs.kt`）：同步暴露给 VM。

#### 4.3.4 并发与节流

- 播放时补全：放进 `MainViewModel.viewModelScope`，用 `Semaphore(4)` 限流（4 个并发网络查询是常规值）。
- 手动补全：放进 `PlaylistImportViewModel.viewModelScope`，单独走，不影响播放。
- **URL 可达性批量测**（**新增**）：导入完成后异步跑,放进 `PlaylistImportViewModel.viewModelScope`,Semaphore(4) 限流,**不阻塞 UI**。
- 失败重试：单条失败不重试（避免重试风暴）；手动补全时整批失败的用户可点「重新补全」再走一次。

#### 4.3.5 进度反馈

`PlaylistImportViewModel` 暴露：

```kotlin
val enrichProgress: StateFlow<EnrichProgress>
data class EnrichProgress(
    val total: Int,
    val processed: Int,
    val matched: Int,
    val failed: Int,
)
```

UI 在「我的」页面歌单卡片下显示进度条（仅当 progress.processed < progress.total 时显示）。

### 4.4 设置 UI（DATA 分区新增子区块，与数据备份导入**统一交互逻辑**）

> **设计原则**：本功能与「数据备份导入」共享同一套交互模型（消息反馈、按钮样式、列表项结构、Launcher 注册方式）。差异仅在「备份导入用文件列表 + 列表内点击恢复」（应用自导出文件，闭环），「歌单导入用 SAF 单文件选择器」（用户从外部获取文件，开环）。
> 下文每项都会标注「与 Backup 一致」或「与 Backup 有差异」，避免后续 PR 误引入不一致。

#### 4.4.1 交互模式对齐总表（与 Backup 导入对比）

| 维度 | 数据备份导入（现状） | 歌单导入（本期设计） | 是否一致 |
| --- | --- | --- | --- |
| 设置分区归属 | DATA | DATA | ✅ 一致 |
| 用户主动选文件入口 | **无**（仅依赖应用自导出 + 列表内点恢复 + 扫码传输） | **有**（SAF `OpenDocument` 单文件选择器） | ⚠️ **有差异**（必要） |
| 操作按钮组件 | `SettingActionButton(label, description, onClick)` | 同 | ✅ 一致 |
| 操作结果反馈 | `BackupMessage(text, isError)` + 4s 自动消费（`SettingsScreen.kt:311-316`） | 同 | ✅ 一致 |
| 错误反馈 | `BackupMessage(isError=true)` | 同 | ✅ 一致 |
| 列表项组件 | `BackupFileRow`（图标 + 文件名 + 副信息 + 右侧操作文字） | **复用同一组件**改名 `ImportedPlaylistRow`（图标 + 歌单名 + 副信息 + 右侧「打开」操作，**无删除**），结构与 `BackupFileRow` 镜像 | ⚠️ **有差异**（有意简化，2026-09-18） |
| 删除确认 | `ConfirmDialog`（已有） | **不适用**——最近导入记录不提供删除入口（2026-09-18 简化） | ⚠️ **有差异**（有意简化） |
| 协程入口 | ViewModel `fun import(uri: Uri)` 直接调用 | **统一**：ViewModel `fun import(uri: Uri)` 同样模式；MainActivity 仅持有 launcher，回调给 ViewModel | ✅ 一致 |
| ViewModel | `BackupViewModel.importBackup(uri: Uri)`（`BackupViewModel.kt:74`） | `PlaylistImportViewModel.importPlaylist(uri: Uri)` 同模式 | ✅ 一致 |
| 状态机 | `backupMessage: StateFlow<BackupMessage?>` + `consumeBackupMessage()` | **importHistoryMessage**: 用同一 `BackupMessage` 类型(不新增)+ `consumeImportMessage()` | ✅ 一致 |

#### 4.4.2 分区布局变化

DATA 分区当前结构（`DataSettingsSection.kt`）：

```
数据管理 (DATA)
├─ 导出备份
├─ 扫码传输
├─ 播放统计
├─ (间距)
└─ 备份文件列表
       └─ 每行 BackupFileRow（恢复 / 删除）
```

新结构（与 Backup 完全对称的「导入类操作」位序）：

```
数据管理 (DATA)
├─ 导出备份
├─ 扫码传输
├─ 播放统计
├─ (间距)
├─ ⭐ 歌单导入                                 ← 与「导出备份」对称的「导入」位
│   ├─ 导入歌单文件（SettingActionButton）       ← 与「导出备份」同一组件
│   ├─ 最近导入记录                            ← 与「备份文件列表」对称的「历史列表」位
│   │      └─ 每行 ImportedPlaylistRow：
│   │            左：图标 + 歌单名 + 「导入于 MM-dd HH:mm · 导入 N 首」
│   │            右：「打开」操作（跳到「我的」展开该歌单）   ← 无「删除」操作（2026-09-18 简化）
│   ├─ 导入消息显示                              ← 与 BackupMessage 同位显示
├─ (间距)
└─ 备份文件列表
       └─ 每行 BackupFileRow（恢复 / 删除）
```

> **位序选择理由**：把「导入歌单文件」放在「导出备份」紧邻位（而非区尾），是因为 DATA 分区是「数据导入/导出」语义集中地，「歌单导入」与「备份导入」都属于「导入」侧，自然对称；同时「最近导入记录」与「备份文件列表」也形成「两类导入历史」对称展示。

#### 4.4.3 状态/动作扩展（镜像 Backup 的 State/Actions 模式）

`DataSettingsState` 增加（与 `DataSettingsState` 现有的 `backupFiles`/`backupMessage` 字段镜像）：

```kotlin
data class DataSettingsState(
    val backupFiles: List<BackupFileUtils.BackupFile>,
    val backupMessage: BackupMessage?,
    // ⭐ 新增（与 backupMessage 同类型，复用）
    val playlistImportMessage: BackupMessage? = null,
    val playlistImportHistory: List<PlaylistImportHistoryItem> = emptyList(),
)
```

`DataSettingsActions` 增加（镜像 `onImportBackup`）：

```kotlin
data class DataSettingsActions(
    val onExportBackup: (() -> Unit)?,
    val onImportBackup: ((android.net.Uri) -> Unit)?,
    val onScanTransferBackup: (() -> Unit)?,
    val onOpenPlayStats: (() -> Unit)? = null,
    // ⭐ 新增
    val onImportPlaylistFile: (() -> Unit)? = null,                  // 触发 SAF
    val onOpenImportedPlaylist: ((playlistId: String) -> Unit)? = null,  // 跳「我的」展开
    // （无 onDeleteImportedPlaylist —— 最近导入记录不提供删除入口，2026-09-18 简化）
    val onConsumePlaylistImportMessage: (() -> Unit)? = null,
)
```

> **State/Actions 镜像原则**：`DataSettingsSection` 现有 State/Actions 已固化模式（R-2 拆分冻结版），歌单导入完全沿用「State + Actions + Consume」三件套，不引入新的状态机。

#### 4.4.4 列表项 `ImportedPlaylistRow`（与 `BackupFileRow` 镜像）

参照 `SettingsComponents.kt:196-275` 的 `BackupFileRow` 实现，新增：

```kotlin
@Composable
internal fun ImportedPlaylistRow(
    item: PlaylistImportHistoryItem,
    onOpen: () -> Unit,           // 跳「我的」展开（唯一操作，无删除）
) {
    // 视觉结构与 BackupFileRow 镜像（仅右侧操作数量不同）：
    //   Row(fillMaxWidth) {
    //     FocusableSurface(weight=1f) { /* 歌单名 + 副信息（信息展示区，不可点） */ }
    //     FocusableSurface(size=64dp, onClick=onOpen) { /* 「打开」文字 */ }
    //   }
    // 图标: Icons.Default.LibraryMusic（区别于 BackupFileRow 的 Icons.Default.Info）
    // 副信息: "导入于 09-15 22:30 · 导入 247 首"
    // 操作按钮文字: 「打开」（BackupFileRow 是「恢复」+「删除」双按钮，本期**不提供删除**）
}
```

> **与 BackupFileRow 的差异点仅 4 处**：图标、操作文字（恢复→打开）、副信息文本模板、**右侧仅 1 个操作按钮（无删除）**。其余布局结构、`FocusableSurface` 参数、padding/RoundedCornerShape 全部沿用，确保 TV 焦点巡游体验一致。删除入口有意省略（2026-09-18 用户决策）：历史列表只读展示 + 「打开」跳转，删除动作收敛到「我的」页歌单卡片（既有功能）。

#### 4.4.5 消息反馈（直接复用 `BackupMessage`）

| 场景 | 文案 | isError |
| --- | --- | --- |
| 导入成功 | `导入成功：${playlistName}（${imported} 首，跳过 ${skipped} 条）` | false |
| 导入失败（解析异常） | `歌单导入失败：${error.message.take(80)}` | true |
| 导入失败（未识别格式） | `未识别格式，仅找到 0 条有效歌曲` | true |
| 补全完成（手动批量） | `补全完成：${matched} 首已找到 / ${failed} 首未找到` | false |
| URL 可达性测试完成（导入后后台） | `已检查 ${total} 个直链：${ok} 个可用 / ${fail} 个失效` | false |

> 全部用现有 `BackupMessage`（`data/model/BackupMessage.kt`），**不新增 model**。
> 自动消费用现有 `LaunchedEffect(playlistImportMessage) { delay(4000); onConsumePlaylistImportMessage?.invoke() }`（与 `SettingsScreen.kt:311-316` 的 `backupMessage` 消费模式**逐行一致**）。

#### 4.4.6 删除入口（已简化，2026-09-18）

本期**不从「最近导入记录」提供删除操作**，因此**不新增任何删除确认弹窗**。

- 删除导入歌单的唯一入口在「我的」页歌单卡片（**既有功能** `PlaylistViewModel.deleteLocalPlaylist`，非本期新增），其确认弹窗沿用现有实现。
- 从「我的」删除歌单时，联动清理导入历史条目（§5.4.1 `consumeHistoryIfDeleted`），避免历史列表出现死链。

#### 4.4.7 弹窗（与 Backup 一致地不弹窗原则）

**重要决策**：与 Backup 导入**严格对齐**——**不弹任何「导入中」模态弹窗**。理由：
- 备份导入也是「点击后默默跑，跑完显示消息」；不阻塞 Back、不弹模态。
- 歌单导入跑得更快（5000 首 < 5 秒），阻塞 UI 反而违背「快速导入即用」体验。

**无新增弹窗**：本期新增 UI（导入入口、历史列表）**不引入任何弹窗**（删除入口已按 §4.4.6 简化，无删除确认弹窗）。
- **错误详情**（P2 可选）：如未来用户反馈需要看「哪首歌导入失败」，再单独加 dialog。本期**不上**。

#### 4.4.8 「导入歌单」按钮的文案与 Backup 镜像

| Backup 按钮 | 歌单按钮 |
| --- | --- |
| 「导出备份」 | 「导入歌单文件」 |
| 「将收藏、歌单、播放统计等数据导出到本地文件」 | 「从本地选择 .m3u / .json / .txt 文件导入为本地歌单」 |
| `R.string.settings_export_backup` / `settings_export_backup_desc` | 新增 `R.string.settings_import_playlist` / `settings_import_playlist_desc` |

### 4.5 「我的」页 UI 改动

#### 4.5.1 歌单卡片

`MineScreen.PlaylistCard`（`MineScreen.kt:222-232`）当前显示「歌单名 + 歌曲数 + 播放/重命名/删除」三按钮。

新增：

- **来源标签**：导入的歌单在名字下面显示「导入」灰色小标签（区别于「新建」）。
- **补全进度条**：仅当 `enrichProgress.processed < enrichProgress.total` 时显示，横向进度条 + 「补全 X/Y」文字。卡片展开后用统一的迷你进度条显示。
- **手动「补全」按钮**：当 stub 数量 > 0 时显示；点击触发 `playlistImportVM.enrichPlaylist(playlist.id)`。
- **URL 失效计数徽标**：当 `keySongReachability` 中该歌单存在失效条目（`songReachability[playlistId]` 命中非 REACHABLE）时，在歌曲数旁显示「N 个直链失效」红色小徽标；点击徽标可进入本次导入会话的失效列表筛选（复用「播放失败明细」的呈现）。计数仅含「URL 已失效」状态，不含「URL 待定/局域网」。

#### 4.5.1.1 URL 状态的数据来源

- `MineViewModel` 订阅 `AppPreferences` 的 `keySongReachability` Flow，映射出 `songUnreachableIds: Set<String>`，下发到 `MineScreen`。
- 判定规则与 §4.7.4 一致：仅「URL 已失效」（`songReachability` 中 `checkedAt` 仍在 24h 判定窗口内、result 为非 REACHABLE）计入徽标；`URL 待定`（校验中/未判）与「局域网 URL」（走首次播放判定）不显示徽标，避免误报。窗口外的旧条目已视为未知，会被后台重新测试。
- 徽标展示为「只读提示」，不阻塞播放：点击失效歌曲行仍按正常逻辑播放，ExoPlayer 会走 §4.3.2 的回退链。

#### 4.5.2 歌曲行（导入歌单展开后）

`UnifiedSongRow`（已存在，公共组件）：
- 新增 prop `isStub: Boolean = false`：当 stub 时在右侧显示「🔍 待补全」文字标签。
- 新增 prop `urlStatus: UrlStatus = UrlStatus.NONE`（`NONE` / `PENDING` / `UNREACHABLE`）：当歌曲为「URL 直链」且 `keySongReachability` 判为失效时，在右侧显示「🔗 URL 已失效」红色文字标签，优先级低于「🔍 待补全」、高于普通来源标签；`PENDING`（后台测试中）不显示标签，避免闪烁。
- 「删除歌曲」操作不变。
- 点击 stub 歌曲照常播放 —— 触发补全；点击「URL 已失效」歌曲照常播放 —— 触发 §4.3.2 回退链。

### 4.6 导出（互通 + 用户手动备份）

本应用自导的 JSON 格式作为「导出」选项，方便用户把导入的歌单移走再移回：

```json
{
  "formatVersion": "nasmusic-playlist-v2",
  "playlistName": "我的歌单",
  "createdAt": 1758160000000,
  "songs": [
    {
      "title": "起风了",
      "artist": "买辣椒也用券",
      "album": "起风了",
      "durationSec": 320,
      "isNetworkSong": true,
      "networkSource": "meting",
      "networkId": "1234567"
    }
  ]
}
```

写回时按 `formatVersion` 路由到 `JsonPlaylistParser`，**与网易云歌单解析路径隔离**，避免误识别。

### 4.7 数据模型与持久化

#### 4.7.1 `LocalPlaylist.songs` 存储

沿用现有 schema（`[LocalPlaylist]` JSON 序列化）。**不新增字段**。

补全后的 `Song`（带 `networkSource/networkId/coverUrl/durationMs`）直接替换原 stub，占位 JSON 大小仅小幅增长（≤2 KB/首），实测 5000 首约 10 MB，仍在 DataStore 安全范围。

#### 4.7.2 导入历史存储

新键 `AppPreferences.keyPlaylistImportHistory`：

```kotlin
private val keyPlaylistImportHistory = stringPreferencesKey("playlist_import_history")

@Serializable
data class PlaylistImportHistoryItem(
    @SerializedName("playlistId") val playlistId: String,
    @SerializedName("playlistName") val playlistName: String,
    @SerializedName("importedAt") val importedAt: Long,
    @SerializedName("importedCount") val importedCount: Int,
)
```

存最近 20 条；超出按 `importedAt` 淘汰最旧。

**操作方法**（`AppPreferences.playlist`）：

```kotlin
val playlistImportHistory: Flow<List<PlaylistImportHistoryItem>>
suspend fun recordPlaylistImport(item: PlaylistImportHistoryItem)
suspend fun deletePlaylistImportHistory(playlistId: String)  // 用户删除歌单时联动清理
```

#### 4.7.3 备份兼容

`AppPreferences.BackupData`（`AppPreferences.kt:1481`）当前已序列化 `localPlaylists`。**导入历史**字段加入：

```kotlin
data class BackupData(
    ...
    val localPlaylists: List<LocalPlaylist> = emptyList(),
    val playlistImportHistory: List<PlaylistImportHistoryItem> = emptyList(),  // ← 新增
    ...
)
```

旧备份恢复时 `gson.fromJson` 忽略未知字段，安全。

#### 4.7.4 URL 可达性持久化（`keySongReachability`，新增）

**原则**：可达性是**独立于 `Song` 的旁路状态**，不塞进 `LocalPlaylist.songs` 的 JSON 里（保持 §4.7.1「不新增字段」约束，避免每条 stub 被反复序列化）。

`AppPreferences` 新增独立 key（与既有 `keyPlaylistImportHistory` 同级）：

```kotlin
private val keySongReachability = stringPreferencesKey("song_reachability")
// 值：gson.toJson(Map<songId, ReachabilityEntry>)
// ReachabilityEntry { result: String, checkedAt: Long }
```

- **数据内容**：`Map<song.id, {result, checkedAt}>`，只记录**非 REACHABLE 的判定结果**（REACHABLE 不入库，省空间且避免"URL 恢复后仍是旧可达"的歧义）。
- **内存优先**：5 分钟内存缓存是运行时权威（`UrlReachabilityChecker` 内部，见 §4.1.8 (3)）；持久化层只在启动时初始化：读取 map → 对 `checkedAt < now - 24h` 的条目**放弃**（视为未知 → 重新测试），`checkedAt >= now - 24h` 的条目直接初始化到内存缓存（**重启后 24 小时内不重新测**）。
- **写时机**：`MARK_UNREACHABLE`（导入后台批量测 / playbackFailure）与 `CLEAR`（补全成功替换后清除该 songId 旧标记）两处。
- **备份/恢复联动**（§4.7.3）：`BackupData` 增加 `songReachability: Map<String, Long> = emptyMap()`；恢复时**一并恢复**——重启后 24h 内不重测的语义对恢复场景同样成立（用户恢复备份后不会立刻被 1 万次 HEAD 打爆）。不做则恢复后所有 URL 重新测一遍，体验差。

**为什么放旁路而非 `Song` 字段**：
1. §4.7.1 明确 `LocalPlaylist.songs` 不新增字段（Gson 序列化体积、ProGuard、恢复兼容三处都得动）；
2. 可达性与 URL 状态绑定而非与歌曲绑定——同一 stub 补全成 NAS Song 后标记自然失效，`clearSongUnreachable` 一处清理即可；
3. 未来若该 key 数据量过大（>5000 条），可单独剪枝（只保留最近 N 条或过期清理），不影响歌单主体。

### 4.8 ProGuard 规则

`proguard-rules.pro` 已 keep `data.model`（`Song` / `LocalPlaylist`）。新增 `PlaylistImportHistoryItem` 同样在 `data.model` 包下，**无需新增规则**。

---

## 5. UI 流程（端到端）

### 5.1 导入（手机 / TV 共用，与 Backup 导入流程对称）

```
[设置] → 数据管理 → 「导入歌单文件」（SettingActionButton）
        │
        ▼
   MainActivity.playlistFileLauncher.launch("audio/* | application/json | text/* | application/octet-stream")
        │  ↑ 与 BackupTransferDialog.onRestore 同模式：
        │   MainActivity 注册 launcher → ActivityResult 回写 mutableState → ViewModel.launcherCallback 消费
        │ （不引入新模式，与 exportTreeLauncher 注册方式一致，见 MainActivity.kt:78）
        │
        ▼ (uri)
   SettingsBranch 把 uri 转发给 playlistImportViewModel.importPlaylist(uri)
        │
        ▼
   PlaylistImportViewModel.importPlaylist(uri) 协程
        │  ↑ 镜像 BackupViewModel.importBackup(uri: Uri)（BackupViewModel.kt:74）的函数签名
        │
        ├── 解析器嗅探（4 KB head bytes + 扩展名）→ 选 parser
        │      │
        │      ├── parser 识别成功
        │      │      │
        │      │      ▼
        │      │   resolvePlaylistName()（§4.2，无意义文件名白名单跳过 → 默认值）
        │      │
        │      └── parser 识别失败
        │             │
        │             └── 兜底走 txt 解析，识别 0 条 → playlistImportMessage = BackupMessage(isError=true)
        │
        ▼
   PlaylistImporter.import()（协程，与 Backup 一致地不弹模态）
        │
        ├─ 进度回调（每 25% 更新 playlistImportMessage 文字「正在导入... 25%」）
        │
        ▼
   完成 → playlistImportMessage = BackupMessage("导入成功：xxx（247 首，跳过 5 条）")
        │      ↑ 与 backupMessage 走同一消费路径：
        │         LaunchedEffect(playlistImportMessage) { delay(4000); onConsume?.invoke() }
        │
        ▼
   recordPlaylistImport(item) → playlistImportHistory Flow 更新
        │
        ▼
   「我的」页面 collect localPlaylists，自动出现新卡片
```

**关键：与 Backup 一致的环节**：
1. **Launcher 注册位置**：`MainActivity`，与 `exportTreeLauncher` 同位置，**不在 ViewModel 内**（与 Backup 一致）。
2. **回调路径**：Activity → `mutableState` → Compose 持 `remember` 监听 → 调 ViewModel（与 `BackupTransferDialog` 持有 `onRestore` 回调同模式）。
3. **结果反馈**：复用 `BackupMessage` + 4s 自动消费（`SettingsScreen.kt:311-316` 的 `backupMessage` 消费代码**逐行镜像**）。
4. **不阻塞 UI**：与 Backup 一样，不弹模态、不锁定 Back。
5. **失败展示**：`BackupMessage(isError=true)`，与现有 `backup_restore_failed` 文案模板一致（`strings.xml:848`）。

### 5.2 播放触发补全

```
[我的] → 点歌单「播放全部」/ 单首「播放」
        │
        ▼
   MainViewModel.playQueue / playLocalPlaylist
        │
        ├── 命中 NAS 歌曲 → 走原链路
        ├── 命中网络歌曲 → NetworkMusicManager.resolvePlayUrl
        └── 命中 stub（id startsWith "imported_"）
                │
                ▼
           PlaylistEnricher.enrichSong(stub) 后台异步
                │
                ├── 命中 NAS → replaceSongInPlaylist(id, stub.id, nasSong)
                ├── 命中网络 → replaceSongInPlaylist(id, stub.id, networkSong)
                └── 未命中 → 不写回，UI 标记「🔍 待补全」

           播放本身不阻塞 —— 用 stub 的标题/艺术家做兜底，ExoPlayer 走最朴素的 ID3 解析
       （NAS 命中前几秒可播 stub 标题；命中后由 PlayerManager 切换流）
```

### 5.3 手动批量补全

```
[我的] → 展开歌单 → 点「补全」按钮
        │
        ▼
   PlaylistImportViewModel.enrichPlaylist(id)
        │
        ▼
   Semaphore(4) 限流并发，EnrichProgress 进度推送
        │
        ▼
   完成后 → playlistImportMessage = BackupMessage("补全完成：N 首已找到 / M 首未找到")
        │      ↑ 与 backupMessage 同一展示/消费路径
```

### 5.4 删除 / 撤销导入（镜像 BackupFileRow 删除交互）

#### 5.4.1 「我的」页面删除（已有路径）

用户在「我的」点歌单卡片「删除」：
```
PlaylistViewModel.deleteLocalPlaylist(id)
   │
   ├── prefs.playlist.deleteLocalPlaylist(id)
   └── 联动：playlistImportVM.consumeHistoryIfDeleted(id)  → deletePlaylistImportHistory(id)
```

#### 5.4.2 「设置 → 数据管理 → 最近导入记录」打开（唯一操作，无删除）

```
DATA 分区 → 最近导入记录 → ImportedPlaylistRow（镜像 BackupFileRow，仅「打开」操作）
        │
        └── 点「打开」 → onOpenImportedPlaylist(playlistId)
               │
               └── Screen.Mine + 传 playlistId 参数 → MineScreen 自动展开该歌单
                   （P2：本期仅记录意图，不实现跳转，后续 PR 加上 nav 参数即可）
```

> **本期不提供「删除」操作（2026-09-18 用户决策）**：历史列表只读展示 + 「打开」跳转；删除动作收敛到「我的」页歌单卡片（既有 `PlaylistViewModel.deleteLocalPlaylist`，§5.4.1）。因此**不镜像** `BackupFileRow` 的删除链路（`SettingsComponents.kt:251-273` 的右侧 64dp `FocusableSurface` + `Text(WARNING)`），也无需 `ConfirmDialog`。
> 删除歌单本体（「我的」页）时，`consumeHistoryIfDeleted(id)` 联动清理对应历史条目，历史列表不残留死链。

### 5.5 消息消费代码镜像（§4.4.5 的实现锚点）

`SettingsScreen.kt` 的 `LaunchedEffect` 部分需要新增一行镜像（与现有 `backupMessage` 消费**逐行一致**）：

```kotlin
// 现有（Backup 消息消费）
LaunchedEffect(backupMessage) {
    if (backupMessage != null) {
        kotlinx.coroutines.delay(4000)
        onConsumeBackupMessage?.invoke()
    }
}

// 新增（歌单导入消息消费，与上一行镜像）
LaunchedEffect(playlistImportMessage) {
    if (playlistImportMessage != null) {
        kotlinx.coroutines.delay(4000)
        onConsumePlaylistImportMessage?.invoke()
    }
}
```

> **不抽公共 composable 复用**：现有 `LaunchedEffect(backupMessage)` 也是独立写，**保持同样的「独立 + 镜像」风格**，便于后续 Code Review 一眼看出「这条是镜像自 backup」。

---

## 6. 测试策略

| 层 | 测试类 | 关键场景 |
| --- | --- | --- |
| Parser | `M3uPlaylistParserTest`、`NeteaseCloudPlaylistParserTest`、`JsonPlaylistParserTest`、**`TextPlaylistParserTest`（**新增重点**，§4.1.7 (7) 16+ 用例）** | 标准 M3u / GBK 编码 / 空行 / `#EXT-X-` 跳过 / 网易云空 tracks / 自导 JSON v2 / **txt 单短横不切分 / Tab 优先 / 编号剥离 / 注释跳过 / BOM 剥离 / 无意义文件名检测 / 4 KB 行长度上限** |
| 导入 | `PlaylistImporterTest` | 取名兜底 4 路径（含**无意义文件名跳过**）；同 (title, artist) 去重；空文件；10K 条目串行进度；**txt 空 artist 条目入库** |
| 补全 | `PlaylistEnricherTest`（Robolectric + mock） | NAS 命中 / 网络命中 / 双失败；`replaceSongInPlaylist` 幂等；**artist 为空时 title 精确匹配 + Top1 fallback + UI 不强塞** |
| Prefs | `AppPreferencesPlaylistTest` | 历史 20 条淘汰；删除歌单联动清历史；备份导入/导出；**`keySongReachability` 读写 / 24h 判定窗口 / 备份恢复联动** |
| UI | `MineScreenTest`（Robolectric） | 卡片显示「导入」标签；进度条根据 `enrichProgress` 切换；**txt 导入后 `未识入默认歌单名` 验证**；**「🔗 URL 已失效」标签与 `urlStatus` 渲染 / 失效计数徽标** |
| 可达性 | `UrlReachabilityCheckerTest`（Robolectric + mock OkHttp） | §4.1.8 (7) 全部用例：2xx 可达 / 3xx 跟随 / 404 失效 / 超时 / 并发上限 4 / 5 分钟缓存 / `isPrivateLanUrl` 过滤 / 局域网不测 / 防盗链 HEAD 200 GET 403 契约 / 恢复后写回 `songReachability` / 非 URL 条目跳过 |

`MetingApiService`/`NetworkMusicManager.search` 在单测里**全部 mock**（与 `NetworkMusicManagerTest.kt:1` 已有的对齐），避免真实网络。

---

## 7. 风险与已知约束

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 网易云分享链接需联网解析 | 用户期望「网易云分享」的链接能被解析成歌曲列表 | 分享链接本身作为 URL 条目按 §4.1.8 处理（可达即保留）已收入 V1；「链接 → 歌单歌曲列表」才需预置 API 端点，文档明示属 V2（§10） |
| 巨量 M3u（>50K 首）卡 UI | 进度条跳变 | 走协程 + IO 调度；导入过程弹 Dialog 阻塞 Back 键；实测 50K 首 ≤ 8 秒 |
| 导入歌单名与「我的」现有重名 | 用户困惑 | 自动加 `(导入 2)` `(导入 3)` 后缀（与系统重命名策略一致） |
| stub id 跨设备/清缓存后丢失 | 补全状态不可恢复 | 备份导入历史时一并带 `stub -> (title,artist)`；恢复时 stub id 重建后再走补全 |
| GBK M3u 在 NFC 规范下误识别 | 部分条目乱码 | 与 `EncodingUtils` 共用 GBK 兜底；乱码条目保留原始字节，不静默丢 |
| 补全命中率受网络/NAS 限制 | 大量 stub 永久「🔍 待补全」 | UI 显示「命中 X / 待补 Y」让用户有预期；支持「重新补全」按钮 |
| ProGuard 漏 keep | 备份恢复时 `PlaylistImportHistoryItem` 字段丢失 | 它在 `data.model` 下，`-keep class com.nasmusic.tv.data.model.** { *; }` 已覆盖 |
| 备份文件大小膨胀 | 每条 stub 后续会升级到带 coverUrl/durationMs | 5000 首上限与现有歌单一致；超过 10 MB 时实测 DataStore 仍可承受 |
| **txt 单短横误识别合作曲标题** | `U2-1` 这类合作标题被切错 | §4.1.7 (1) 明确单短横 `-` 不作为分隔符，仅 ` - ` / ` — ` 等带空格环绕的分隔符生效 |
| **txt artist 空时盲目 Top1 补全** | 同一歌名下 Top1 不一定是用户想要的那首 | §4.1.7 (6)：先按 title 精确匹配；Top1 仅作 fallback；Top1 也不命中 → 不自动塞，UI 标记「需手动指定艺术家」 |
| **txt 默认文件名 `新建文本文档.txt` 被当歌单名入库** | 用户歌单名混乱 | §4.1.7 (5) DEFAULT_PLAYLIST_FILENAMES 白名单 8 个默认名；命中即跳过文件名兜底 |
| **txt GBK 编码误识别** | 中文曲目乱码 | 与 M3u 共用 `EncodingUtils` UTF-8 → GBK 双层尝试 |
| **txt 巨行（>1 MB 单行）OOM** | 解析卡死/崩溃 | 行长度上限 4 KB（与现有歌词解析一致），超限计入 `skipped` |
| **txt 注释行被错误入库** | `# 标题注释` 这种章节行被当成歌名 | §4.1.7 (2) `#` `//` 开头行视为注释跳过；同时首行 `# title: 名字` 可作为 `innerName` hint |
| **txt BOM 残留在 title** | 首字符是 U+FEFF 导致搜索失败 | §4.1.7 (3) 强制剥离首 3 字节 `EF BB BF` |
| **txt 与 M3u 文件名冲突**（`.txt` 扩展名但内容是 `#EXTM3U`） | 走到错误的 parser | §4.1.7 (4) 头部嗅探优先于扩展名；扩展名只决定初始候选 parser |
| **与 Backup 交互模式不一致**（开发人员偷懒引入新的反馈模式） | 用户 TV 焦点巡游错位；Code Review 必查项 | §4.4.1 总表列出 9 项「与 Backup 对齐」要求；§9.3 验收清单 16 项专项；PR 模板追加 §9.3 自检 checklist |
| **歌单导入未走 BackupMessage 而自创 model** | UI 反馈碎片化、消息处理代码分裂 | §4.4.5 明确**复用** `BackupMessage`，不新增 model；§9.3 验收项「playlistImportMessage 字段类型为 BackupMessage?」 |
| **SAF Launcher 在 ViewModel 内调用**（与 exportTreeLauncher 不一致） | Activity 重建 / 进程重启后 launcher 失效；配置变更丢失 URI | §4.4.7 + §8 阶段 5 强制 MainActivity 注册；§9.3 验收项强制对齐 `exportTreeLauncher` 注册方式 |
| **最近导入记录行结构与 BackupFileRow 不一致** | 视觉/焦点巡游错位；用户认知割裂 | §4.4.4 明确 `ImportedPlaylistRow` 沿用 `BackupFileRow` 的 `FocusableSurface` 布局参数，差异仅在图标/操作文字（打开）/副信息文本；右侧仅「打开」单按钮是 2026-09-18 的有意简化（无删除） |
| **M3u URL 失效** | 用户导入了带 URL 的 m3u，歌单「可看不可播」 | §4.1.8 后台批量可达性测试 + `keySongReachability` 标记 + UI「🔗 URL 已失效」标签 + §4.3.2 首次播放回退链（搜索补全） |
| **防盗链 CDN（HEAD 200 / GET 403）** | 测试判「可达」但实际播不了，且下载（GET Range）会 403 | §4.1.8 (4) 表格 C 行注明 HEAD 测试的局限；播放失败仍走回退链兜底；不因「测试通过」阻止回退 |
| **局域网 NAS URL 网络切换失效**（导入时家用 Wi-Fi，播放时连了手机热点） | 局域网 URL 在设备当前网络不可达 | §4.1.8 (6) 局域网 URL 不进批量测试，由 ExoPlayer 首次播放时从设备当前网络判定；播放失败走回退链 |
| **URL 带到期 token（签名 URL）** | 测试时可用、播放时 token 过期 | 后台测试与播放间隔越短越可靠（缓存 5 分钟）；`UNREACHABLE` 判定后用户可手动「重新测试」，不永久标记 |
| **批量可达性测试并发风暴** | 大歌单（5000 URL）一次全测拖垮网络/被源站限流 | §4.1.8 (4) 并发 4 上限 + 每 URL 超时 5s + 5 分钟缓存 + 冷却节流；每秒限 20 请求 |
| **URL 可达但搜索补全结果不同**（用户原 URL 是某 live 版，回退搜索到 studio 版） | 回退后播放的歌曲与用户预期不符 | 回退仅在「播放失败」后触发，且先「复测确认」再搜索（§4.3.1 路径 D）；命中结果播放但 UI 保留「原 URL 失效」标签供用户识别 |
| **URL 可达性缓存在清缓存/备份恢复后丢失** | 失效标签消失，静默回归「可看不可播」 | §4.7.4 `songReachability` 随备份/恢复一并传输；无缓存时退化为「播放时判定」，不阻塞 |

---

## 8. 开发步骤（建议拆 PR 顺序）

### 阶段 1：基础设施（1 PR）

1. `data/model/PlaylistImportHistoryItem.kt` 新建
2. `AppPreferences.playlist` 增加 `playlistImportHistory` Flow + `recordPlaylistImport` + `deletePlaylistImportHistory`
3. `AppPreferences.BackupData` 增加 `playlistImportHistory` 字段
4. `PlaylistPrefs` 同步暴露
5. 单测：`AppPreferencesPlaylistTest` 新增对应用例

### 阶段 2：解析器（1 PR）

1. `backend/playlist/PlaylistFileFormat.kt`（`RawSongEntry`（含 `directUrl: String?`）、`SourceHint`、`PlaylistParser` 接口 + 通用分隔符/编码工具）
2. `M3uPlaylistParser.kt`（含 GBK 兜底；§4.1.4 path hint 捕获：`#EXTINF` 行后的首个非注释非空行 → `directUrl`；无 `#EXTINF` 关联的裸 URL 行自成条目；`^https?://` 识别）
3. `NeteaseCloudPlaylistParser.kt`（仅解析「导出 JSON」）
4. `JsonPlaylistParser.kt`（本应用自导格式）
5. **`TextPlaylistParser.kt`（**新增重点**，§4.1.7 完整规则）**：分隔符优先级表、编号剥离、注释跳过、BOM 剥离、GBK 兜底、4 KB 行长度上限、无意义文件名检测
6. 单测：`M3uPlaylistParserTest` / `NeteaseCloudPlaylistParserTest` / `JsonPlaylistParserTest` / **`TextPlaylistParserTest`（16+ 用例，见 §4.1.7 (7)）**；**M3u 新增 URL 捕获用例（PathHintTest, 见 §4.1.8 (7)）**

### 阶段 3：导入编排（1 PR）

1. `PlaylistImporter.kt` + 取名兜底 + 进度回调（HTTP URL 条目直接落地 `streamUrl` + `isNetworkSong=false`，**不在此处测可达性**，见 §4.1.8 (4)；**只读流式解析源文件，不落盘、不留副本**，见 §4.2 源文件生命周期）
2. `MainActivity.kt` 注册 `ActivityResultContracts.OpenDocument(arrayOf("audio/*", "application/json", "text/*", "application/octet-stream"))`
3. 入口转发到 `PlaylistImportViewModel.import(uri)`
4. **`UrlReachabilityChecker.kt`（§4.1.8 (4)）**：`isPrivateLanUrl` 过滤、并发 4、超时 5s、5 分钟缓存、每秒 20 请求节流；挂到导入完成后台任务
5. 单测：`PlaylistImporterTest` + **`UrlReachabilityCheckerTest`（§6 表格行）**

### 阶段 4：补全链路（1 PR）

1. `PlaylistEnricher.kt`
2. `AppPreferences` 增加 `replaceSongInPlaylist`
3. `PlaylistPrefs` 暴露
4. `MainViewModel.playQueue` / `playLocalPlaylist` / `playNetworkSong` 三个入口处挂 enrich hook（仅对 `id startsWith "imported_"` 触发）
5. **`playbackFailure` 处理（§4.3.2）**：播放失败且原因为 `ERROR_CODE_BEHIND_LIVE_WINDOW` / IO 错误时，检查 `songReachability` → 复测确认 → `markSongUnreachable` → 触发 `enrichSong` 回退搜索（URL 可达不搜索）
6. 单测：`PlaylistEnricherTest`

### 阶段 5：UI（1 PR）

> **本阶段 UI 设计必须以 §4.4.1「交互模式对齐总表」为准绳**，与 Backup 现有实现一一镜像。

1. `ui/viewmodel/PlaylistImportViewModel.kt`（镜像 `BackupViewModel` 模式）
   - `playlistImportMessage: StateFlow<BackupMessage?>`（**复用** `BackupMessage` 类型，不新增 model）
   - `playlistImportHistory: StateFlow<List<PlaylistImportHistoryItem>>`
   - `fun importPlaylist(uri: Uri)` 镜像 `BackupViewModel.importBackup(uri)`
   - `fun consumeHistoryIfDeleted(playlistId: String)`（「我的」页删除歌单时联动清理历史条目，§5.4.1；**无 `deleteImportedPlaylist`**——最近导入记录不提供删除入口）
   - `fun consumePlaylistImportMessage()` 镜像 `BackupViewModel.consumeBackupMessage()`
2. `ui/screens/settings/PlaylistImportSettingsSection.kt`（**镜像** `DataSettingsSection.kt` 结构）
   - 用 `SettingActionButton`（已存在的公共组件）做「导入歌单文件」入口
   - 用 `ImportedPlaylistRow`（新增，镜像 `BackupFileRow`）做历史列表，右侧仅「打开」
   - （无删除确认弹窗——不提供删除入口，2026-09-18 简化）
3. `ui/screens/settings/SettingsComponents.kt` 新增 `ImportedPlaylistRow`（布局沿用 `BackupFileRow`，差异仅在图标/操作文字/副信息文本，右侧仅「打开」单按钮）
4. `ui/screens/SettingsScreen.kt` 拼装新 action
   - **新增**一行 `LaunchedEffect(playlistImportMessage)` **逐行镜像** 现有 `LaunchedEffect(backupMessage)`（`SettingsScreen.kt:311-316`）
   - 把 SAF launcher 注册位置 `MainActivity.playlistFileLauncher` 通过 mutableState 回调转发给 `PlaylistImportViewModel.importPlaylist(uri)`
5. `ui/MainActivity.kt` 注册 `ActivityResultContracts.OpenDocument(...)`（**镜像** `exportTreeLauncher` 注册方式，`MainActivity.kt:78-87`）
   - 接受类型：`arrayOf("audio/*", "application/json", "text/*", "application/octet-stream")`
   - MIME 检测：与 parser 嗅探链路配合（详见 §4.1.7）
6. `ui/components/branches/SettingsBranch.kt` 接线（镜像现有 `onImportBackup` 接线 `SettingsBranch.kt:144`）
   - `onImportPlaylistFile = { playlistImportVM.importPlaylist(it) }`
   - `onConsumePlaylistImportMessage = { playlistImportVM.consumePlaylistImportMessage() }`
7. `ui/screens/MineScreen.kt` 的歌单卡片 + 歌曲行加 stub 标记 + 补全按钮 + 进度条
7b. **URL 状态 UI（§4.5）**：`MineViewModel` 订阅 `keySongReachability` → `songUnreachableIds`；`UnifiedSongRow` 增加 `urlStatus` prop，失效显示「🔗 URL 已失效」标签；歌单卡片失效计数徽标
8. 字符串资源（镜像 `settings_export_backup*` 与 `settings_import_backup*` 命名风格）：
   - `settings_import_playlist` = "导入歌单文件"
   - `settings_import_playlist_desc` = "从本地选择 .m3u / .json / .txt 文件导入为本地歌单"
   - `playlist_import_message_success` = "导入成功：%1$s（%2$d 首，跳过 %3$d 条）"
   - `playlist_import_message_failed` = "歌单导入失败：%s"
   - `playlist_import_message_unrecognized` = "未识别格式，仅找到 0 条有效歌曲"
   - `playlist_import_message_enrich_done` = "补全完成：%1$d 首已找到 / %2$d 首未找到"
   - `mine_imported_badge` / `mine_enrich_pending` / `mine_enrich_progress`
   - `mine_import_message_url_checked` = "已检查 %1$d 个直链：%2$d 个可用 / %3$d 个失效"（§4.4.5）
   - `mine_url_unreachable_tag` = "🔗 URL 已失效" / `mine_url_unreachable_count` = "%1$d 个直链失效"

### 阶段 6：导出（可选，1 PR）

1. 歌单卡片长按弹「导出歌单」菜单
2. `JsonPlaylistParser.serialize(playlist): String`
3. 调用 `BackupFileUtils.export()` 写入下载目录
5. 单测：`JsonPlaylistParserSerializeTest`

---

## 9. 验收清单

### 9.1 通用
- [ ] 设置 → 数据管理出现「歌单导入」小标题 + 「导入歌单文件」按钮
- [ ] 点击按钮弹出系统文件选择器，可选 `.m3u` / `.m3u8` / `.json` / `.txt` / `.list` / `.csv`
- [ ] 文件有内嵌歌单名 → 自动用内嵌名建歌单
- [ ] 无内嵌名 → 用文件名（去扩展名）建
- [ ] 文件名命中「无意义默认名」白名单 → 跳过文件名兜底，直接走默认值
- [ ] 导入成功后，「我的」页面立即出现新歌单（含「导入」标签）
- [ ] 导入只读流式解析：应用内部存储**无源文件副本**；导入成功后删除源文件，已导入歌单功能不受任何影响（§4.2 源文件生命周期）
- [ ] 展开歌单可看到导入的歌曲，点击可播放
- [ ] 播放未补全歌曲 → 播放时自动补全（无夜间/后台全量任务），几秒内「🔍 待补全」标签消失，封面/时长回填
- [ ] 「我的」歌单卡片点「补全」按钮 → 进度条显示 → 完成后状态汇总
- [ ] 「设置 → 数据管理 → 最近导入记录」显示最近 5 条；每行提供「打开」跳转「我的」展开该歌单；**不提供删除入口**（2026-09-18 简化）
- [ ] 备份导出文件包含导入历史；恢复后导入历史正确还原
- [ ] 全程不阻塞 UI；导入进度可感知
- [ ] 失败场景：未识别格式 / 空文件 / 单条补全失败 全部有清晰提示

### 9.2 txt 格式（用户自编辑，**新增重点**）
- [ ] 纯标题列（`海阔天空\n甜蜜蜜\n小幸运`）→ 3 条入库，artist 全部空
- [ ] 标准分隔（`歌名 - 艺术家`）→ 正确切分
- [ ] Tab 分隔（`歌名\t艺术家`）→ 正确切分
- [ ] 长破折号 / 短破折号 / 双短横 / `::` / 全角冒号分隔 → 均识别
- [ ] 单短横 `-`（无空格环绕）**不作为分隔符**（`U2-1 - Beyond` → title=`U2-1`）
- [ ] 中文括号序号 `（1）海阔天空` → 编号剥离
- [ ] 阿拉伯数字序号 `1. 海阔天空` / `1、` / `1)` / `①` → 编号剥离
- [ ] `#` / `//` 开头行视为注释跳过
- [ ] 空行 / 仅空白行跳过
- [ ] 首行 `# title: 我的歌单` → 提取为 `innerName`
- [ ] UTF-8 BOM 文件 → 解析成功，title 不含 U+FEFF
- [ ] GBK 编码文件（中文 Windows 默认）→ 解析成功
- [ ] 单行 4 KB 以上 → 跳过该行计入 `skipped`，不崩溃
- [ ] 文件名 `新建文本文档.txt` → 跳过文件名兜底，默认歌单名
- [ ] 文件名 `我的歌单.txt` → 歌单名用 `我的歌单`
- [ ] 空文件 / 全部注释 → 弹「未识别到有效条目」
- [ ] artist 为空的条目补全失败 → UI 标记「需手动指定艺术家」，**不**自动塞 Top1 不相关歌曲
- [ ] artist 为空的条目按 title 精确命中 → 正常补全
- [ ] `.txt` 扩展名但内容是 `#EXTM3U` → 走 M3u parser（头部嗅探优先）
- [ ] `.list` / `.csv` 扩展名 → 走 TextPlaylistParser

### 9.3 与 Backup 导入交互一致性（**新增专项**）

> 原则：除「用户主动选文件」这一必要差异外，其它维度必须**逐项对齐** Backup，否则 Code Review 打回。

- [ ] 「导入歌单文件」按钮使用 `SettingActionButton` 组件（与「导出备份」同一组件，视觉对齐）
- [ ] 「导入歌单文件」按钮的文案与「导出备份」在 DATA 分区同一视觉块
- [ ] 导入成功 / 失败消息通过 **`BackupMessage`**（不新增 model）展示，与现有 `backup_restore_failed` 等模板镜像
- [ ] 导入消息展示 4 秒后**自动消费**，与 `SettingsScreen.kt:311-316` 的 `backupMessage` 消费逻辑镜像
- [ ] 导入历史列表项 `ImportedPlaylistRow` 布局沿用 `BackupFileRow` 的 `FocusableSurface` 结构（图标 + 歌单名 + 副信息 + 右侧操作区）；操作区仅「打开」**单按钮**（无删除）
- [ ] 删除确认弹窗使用 `ConfirmDialog`（与 `settings_delete_backup_confirm_*` 同模板）
- [ ] SAF `OpenDocument` Launcher **注册在 `MainActivity`**（与 `exportTreeLauncher` 同位置，`MainActivity.kt:78-87`），**不在 ViewModel 内**
- [ ] `PlaylistImportViewModel.importPlaylist(uri: Uri)` 函数签名镜像 `BackupViewModel.importBackup(uri: Uri)`
- [ ] `PlaylistImportViewModel.consumePlaylistImportMessage()` 镜像 `BackupViewModel.consumeBackupMessage()`
- [ ] `DataSettingsState.playlistImportMessage` 字段类型为 `BackupMessage?`（**复用现有**）
- [ ] `DataSettingsActions` 增加 `onImportPlaylistFile` / `onConsumePlaylistImportMessage` 等，**镜像**现有 `onImportBackup` / `onConsumeBackupMessage` 的命名风格
- [ ] `SettingsScreen.kt` 的 `LaunchedEffect(playlistImportMessage)` 代码**逐行镜像** `LaunchedEffect(backupMessage)`
- [ ] `SettingsBranch.kt` 接线 `onImportPlaylistFile = { uri -> playlistImportVM.importPlaylist(uri) }`（镜像 `onImportBackup = { uri -> backupVM.importBackup(uri) }`，`SettingsBranch.kt:144`）
- [ ] 「最近导入记录」列表为空时显示「暂无导入记录」（与「暂无备份文件」文案风格一致）
- [ ] 最近导入记录行**无删除操作**（2026-09-18 简化）；删除歌单仅经「我的」页既有入口，删除后历史条目联动清理不残留死链
- [ ] 导入过程不弹模态、不锁定 Back（与 Backup 一致）

### 9.4 URL 直接使用与可达性（**新增专项**，§4.1.8）

- [ ] M3u 中 `#EXTINF` 行后的 URL / 相对路径被捕获为 `directUrl`，直接落地 `streamUrl` 且 `isNetworkSong=false`（不搜索）
- [ ] 无 `#EXTINF` 关联的裸 URL 行自成条目（title 取 URL 文件名）
- [ ] 相对路径（`path/file.mp3`）**不**当作 URL 解析，计入 `skipped` 并在导入消息说明（§4.1.4）
- [ ] 局域网 URL（`192.168.x.x` / `10.x.x.x` / `172.16-31.x.x` / `localhost`）**不**进批量可达性测试（`isPrivateLanUrl` 过滤）
- [ ] 导入完成 4 秒后后台自动批量测：并发 4、超时 5s、5 分钟缓存；测试期间不阻塞 UI
- [ ] 测试完成消息「已检查 N 个直链：X 个可用 / Y 个失效」通过 `BackupMessage` 展示
- [ ] 失效 URL 歌曲在「我的」歌单展开后显示「🔗 URL 已失效」标签；歌单卡片显示失效计数徽标
- [ ] 播放失效 URL 歌曲 → 不立即搜索，先复测确认 → 确认后走回退链搜索替代
- [ ] `keySongReachability` 随备份导出、恢复导入后还原（失效标签跨设备保留）
- [ ] 防盗链场景（HEAD 200 / GET 403）不阻塞播放回退链

---

## 10. 后续演进（不进入本次开发）

- **V2 网易云分享 URL 自动解析**（需要预置网易云 API 端点，访问 `MUSIC.163.COM` 的 `id=xxx`，把分享链接展开成歌单内歌曲列表；区别于 V1——V1 已支持把该链接作为「URL 条目」捕获并由 §4.1.8 判断可达后保留，但不做「联网解析成歌曲列表」）
- **P3 歌单内模糊匹配（拼音首字母近似匹配）** —— 当前只用 `normalizeKey(title, artist)` 精确归一，命中率受源数据质量影响；后续接 `PinyinUtils` 模糊匹配可显著提升冷启动命中率
- **P4 多文件批量导入**（一次选多个文件按文件名合并到同一歌单，或建多个歌单）
- **P5 歌单导出为 .m3u**（互通反向路径，当前版本只导出本应用 JSON）
- **P6 相对路径映射**：M3u 中 `path/to/file.mp3` 当前**不支持**（§4.1.4、§7 风险表）。后续借助 SAF 的 `OpenDocumentTree`（用户授权歌单同目录）把相对路径解析为同目录文件 URI，可覆盖「m3u 与音频文件放在同一 NAS 文件夹」的常见场景——需存目录授权 URI，工程量集中在目录授权生命周期管理

---

## 11. 相关源码索引

| 模块 | 文件 | 行/位置 |
| --- | --- | --- |
| 本地歌单数据模型 | `data/model/LocalPlaylist.kt` | — |
| Song 模型 | `data/model/Song.kt` | — |
| 本地歌单 CRUD | `data/prefs/AppPreferences.kt` | 1009-1142 |
| `stripVolatileStreamUrl` | `data/prefs/AppPreferences.kt` | 512-513 |
| PlaylistPrefs 转发 | `data/prefs/PlaylistPrefs.kt` | — |
| PlaylistViewModel | `ui/viewmodel/PlaylistViewModel.kt` | — |
| 我的页面 | `ui/screens/MineScreen.kt` | — |
| MineBranch 路由 | `ui/components/branches/MineBranch.kt` | — |
| 设置主页 | `ui/screens/SettingsScreen.kt` | 101-111（分区枚举） |
| DATA 分区 UI | `ui/screens/settings/DataSettingsSection.kt` | — |
| BackupFileUtils | `util/BackupFileUtils.kt` | 170-181（read） |
| MainActivity SAF 注册 | `ui/MainActivity.kt` | 79（OpenDocumentTree） |
| 网络搜索 | `backend/network/NetworkMusicManager.kt` | 66-88（search） |
| 封面兜底 | `backend/network/NetworkMusicManager.kt` | 199 |
| NAS 搜索 | `backend/BackendAdapter.kt` | 133 |
| 去重键 | `backend/SearchAggregator.kt` | 326（normalizeKey） |
| 艺术家归一化 | `util/ArtistSplitter.kt` | 47 |
| 编码兜底 | `util/EncodingUtils.kt` | — |
| BackupMessage 模型 | `data/model/BackupMessage.kt` | — |
| BackupData | `data/prefs/AppPreferences.kt` | 1481 |
| TextInputDialog | `ui/screens/MineScreen.kt` | 311-322 |
| ConfirmDialog | `ui/components/ConfirmDialog.kt` | — |
| 字符串资源 | `app/src/main/res/values/strings.xml` | mine_create_playlist / mine_playlist_name_hint / settings_data |

---

**备注**：本方案基于 2026-09-18 的代码现状（commit 未冻结，文件路径以 `git rev-parse HEAD` 时为准）。
如 `AppPreferences.playlist` API、BackupMessage 行为或 NetworkMusicManager.search 返回结构有变，需重新核对。