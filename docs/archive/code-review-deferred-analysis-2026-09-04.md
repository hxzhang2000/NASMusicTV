# 复审暂缓项问题分析与修改方案（2026-09-04）

> 来源：09-03 代码复审收尾，三项被暂缓 / 待查的问题。本文**只做问题分析与修改方案**，不改动代码。
> 当前代码基准：v2.26.18（提交 `c617914`）。

---

## 一、B20：专辑合并后「只留 NAS id」——详情页丢本地歌

### 1.1 根因（代码实证）

- `MusicMerger.mergeAlbums`（`backend/local/MusicMerger.kt:34-52`）：NAS 专辑先入 `albumMap`，本地同名专辑命中时执行 `albumMap[key] = existing.copy(...)`。`copy` 保留的是 `existing`（NAS 专辑）的 `id`，本地 id（`local_album_xxx`）被丢弃；`songCount` 则是 `NAS + Local` 求和。
- 详情页路由靠 id 前缀判断：`MainViewModel.loadAlbumSongs`（`ui/viewmodel/MainViewModel.kt:2388`）只有 `id.startsWith("local_album_"/"baidu_album_")` 才走「按 name 匹配本地+百度」分支；否则走 `adapter.getAlbumSongs(albumId)`（同文件 `:2412`，**只取 NAS**）。
- 结果：合并后条目 `id` 是 NAS id → 详情页只返回 NAS 歌曲。列表显示 `songCount = NAS + Local`，点进去却只有 NAS 歌，**本地那部分歌不可见、不可播**。
- 艺术家不受影响：`loadArtistSongs` 按 `artistName`（不是 id）查，天然多源合并——所以这是**专辑维度专属**缺陷。

### 1.2 严重程度

数据正确性问题。触发条件：「同一专辑名同时存在于 NAS 和本地」——较窄，但真实存在；后果是静默少歌（列表计数虚高、详情实际缺歌）。

### 1.3 为什么之前判为「设计级大改」

修复需让合并专辑同时携带多源标识并做多源取数。但其实有**更省的局部修法**（见下），之前的判断偏保守。

### 1.4 修改方案（按改动量排序）

- **方案 A（推荐，局部改动）**：只改 `loadAlbumSongs` 的 NAS 分支——拿到 `adapter.getAlbumSongs(id)` 后，再用 `_selectedAlbum.name` 把本地 + 百度同名歌追加进去并去重（按 `title+artist+duration`）。不动 `Album` 模型、不加字段。代价：NAS 分支语义变成「多源」，需处理去重 / 排序。
- **方案 B（干净，模型级）**：给 `Album` 加 `sourceIds: List<String>`（或 `isHybrid` 标记），`mergeAlbums` 在碰撞时把 NAS id 与 `local_album_xxx` 都记下；`loadAlbumSongs` 据此分别取数再拼接。改动跨 `Album` / `MusicMerger` / `MainViewModel`，需评估 `Album` 的序列化（Bundle / Gson）影响。
- **方案 C（最小，但不去重）**：碰撞时不合并计数，保留两条独立条目。违背「去重合并」初衷，不推荐。

---

## 二、LocalMusicDatabase 无 migration

### 2.1 现状（实据）

- `backend/local/db/LocalMusicDatabase.kt`：`version = 1`，`exportSchema = true`，**只配了 `.fallbackToDestructiveMigrationOnDowngrade()`**（仅降级破坏性），**升级路径完全没定义**。
- `app/build.gradle.kts` 用 Room 2.7.1，但**没有配置 `room.schemaLocation`** → `exportSchema=true` 实际不产出 schema JSON → auto-migration 当前不可用。
- 当下 `version=1` 且表结构与 `@Entity` 一致，所以现在不出问题。

### 2.2 风险

未来任何 schema 变更（加列、改主键——见第三点）若直接 bump `version`，Room 启动会抛 `IllegalStateException`（无 Migration 又无破坏性回退）。

### 2.3 修改方案

- **方案 A（推荐，最省）**：把 `.fallbackToDestructiveMigrationOnDowngrade()` 换成 `.fallbackToDestructiveMigration()`（升级也破坏性重建）。本地索引可经重扫重建，丢数据可接受，零迁移代码。代价：每次升 version 会触发一次全量重扫（大 USB 库稍慢）。
- **方案 B（保留索引，免重扫）**：为每次变更写 `Migration(1,2){...}` 手动迁移。工作量大，但升级不丢索引。
- **方案 C（auto-migration）**：配 `ksp { arg("room.schemaLocation", ...) }` + 保留 v1 schema JSON + `addMigrations(AutoMigration(1,2))`。适合简单加列，但需先把 schema 导出基建补上。

---

## 三、path.hashCode() 作主键可能碰撞

### 3.1 现状（实据）

- `MusicScanner.scanFile`（`backend/local/MusicScanner.kt:145`）：`mediaStoreId = file.absolutePath.hashCode().toLong()`——**仅 USB 文件**（无 MediaStore ID）走此路径；内部 / 外部存储用的是 MediaStore 真实 Long ID（唯一，安全）。
- 该值即 `LocalSongEntity` 的 `@PrimaryKey`（`backend/local/db/LocalSongEntity.kt:21`），也是 `Song.id = "local_$mediaStoreId"` 的来源。
- `LocalMusicDao.insertAll`（`backend/local/db/LocalMusicDao.kt:23`）：`@Insert(onConflict = REPLACE)` → **主键碰撞会静默覆盖**（不崩），另一首歌从索引消失 + 元数据被顶替；同时 `Song.id` 也碰撞 → 播放队列按 id 去重时再丢一次。

### 3.2 碰撞概率

`String.hashCode()` 是 32-bit 有符号值。生日悖论下约 **7.7 万文件时碰撞概率≈50%**；普通 USB 几千首概率很低但**非零**，且后果是静默丢歌。`.toLong()` 只是符号扩展，本身不制造碰撞，仅产生负主键（无害）。

### 3.3 修改方案（与「是否改主键」强相关）

- **方案 A（推荐，免迁移）**：仍用 Long 主键，但把 `hashCode().toLong()` 换成**自写 64-bit 路径哈希**（如 `path.fold(1125899906842597L){ a, c -> (a * 31 + c.code.toLong()) and 0xFFFFFFFFFFFFFFFFL }`）。碰撞空间 2⁶⁴，百万级文件仍近乎零碰撞，**不触发 schema 变更**，与「第二点」解耦。
- **方案 B（最干净，需迁移）**：把主键从 `mediaStoreId` 改为 `path`（`path` 已是 `unique` 索引，`LocalSongEntity.kt:19`）。彻底消除碰撞类，但属于 schema 变更 → 必须先解决第二点的 migration。
- **方案 C（维持现状）**：接受极低概率风险。仅当 USB 库规模很小且无关键数据时可暂缓。

---

## 四、三项耦合关系

- 第三点若走「方案 B（改主键）」会强制触发第二点的 migration 问题；若走「方案 A（64-bit 哈希）」则完全独立、零迁移。
- B20 与后两者无耦合，可独立推进。

## 五、建议的推进顺序

1. 若只修低风险项：先做第三点方案 A（64-bit 哈希，一行级改动、免迁移）。
2. B20 按方案 A 局部改 `loadAlbumSongs`（中等改动，建议单独小版本验证）。
3. 第二点建议直接采方案 A（`.fallbackToDestructiveMigration()`），作为后续所有 schema 变更的底座；若未来确需保留索引再补手动 Migration。

> 所有改动仍遵循：本地提交不 push、编译前先提升版本号、同步更新 CHANGELOG 与 `docs/technical-overview.md` §10。
