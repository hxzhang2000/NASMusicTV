# 网络音乐播放失败多级降级方案

> 状态：**待确认**（分析 + 方案，尚未开发）
> 日期：2026-09-17
> 关联问题：网络上搜索的歌曲，播放一段时间（几首歌）以后，连续好多首歌都播放不了

---

## 一、问题现象

用户报告：网络上搜索到的歌曲，播放一段时间（几首歌）以后，**连续好多首歌都播放不了**。

这是一次性集中爆发（一首失败后，后续一连串全失败），不是单首偶发，符合"链接过期 + 重试路径命中缓存"的连锁反应特征。

---

## 二、现状机制梳理（带代码位置）

### 2.1 搜索：多端点降级已存在

`NetworkMusicManager.search()`（`backend/network/NetworkMusicManager.kt:66`）
- 通过 `orderedServices()`（`NetworkMusicManager.kt:260`）按优先级依次尝试各源，首个返回非空即采用。

`MetingApiService` 的搜索走 `buildEndpointFallbackOrder()`（`MetingApiService.kt:174`）
- 当前配置端点在前，然后依次补 Mikus / Redcha / Qijieya 三个预设端点（去重）。

### 2.2 播放链接解析：同源多端点 + 音质档位降级已存在

`MetingApiService.resolvePlayUrl()`（`MetingApiService.kt:226-259`）
- 双层循环：**endpoint 降级**（当前端点 → 3 个预设端点）× **br 音质档位降级**（999 → 320 → 128，AUTO 不传 br）。
- 302 取 `Location`，200 取 JSON `url` 字段，空或异常继续下一档。

### 2.3 播放失败重试机制已存在

`PlayerManager.onPlayerError()`（`player/PlayerManager.kt:417`）
- 空 URI（懒加载未解析）→ 预期错误，直接返回（交给 onMediaItemTransition 处理）。
- 断网期间（`networkLost`）→ 冻结跳歌，记录断点待续播。
- **链接过期** → 调用 `requestStreamUrlResolution()` → `onNeedResolveStreamUrl` → `PlayerViewModel.resolveAndPlayByIndex()`。

`PlayerViewModel.resolveAndPlayByIndex()`（`ui/viewmodel/PlayerViewModel.kt:274-317`）
- 初次解析失败 → 延迟 1.5s 重试一次。
- 重试仍失败 → `showMessage` + `playerManager.next()` 跳下一首。
- 用 `resolveGeneration` 代数计数防止旧解析回滚新队列。

### 2.4 ⚠️ 核心 BUG：播放链接缓存命中过期的 URL

`NetworkMusicManager.resolvePlayUrl()`（`NetworkMusicManager.kt:102-144`）
- 维护 `playUrlCache`（`songId → (url, timestamp)`），**TTL 5 分钟**，LRU 上限 500。
- 首次解析成功即写入缓存；**后续 5 分钟内再次调用直接命中缓存返回，不再走 endpoint 降级链**。

**连锁失败链条：**

```
歌曲 A 播放 → 解析 URL，写入 5 分钟缓存
   ↓ （播放若干首歌，累计时间超过缓存 TTL 内 URL 的有效期）
歌曲 A 的 URL 过期 → 播放出错
   ↓ PlayerManager.onPlayerError
resolveAndPlayByIndex(A) → resolveStreamUrl → resolvePlayUrl
   ↓ ⚠️ 命中 5 分钟缓存，返回已过期 URL（endpoint 降级链根本没跑）
再次播放失败
   ↓ lastErrorRetryIndex 判定"本首已重试过"
playerManager.next() 跳到歌曲 B
   ↓ B 同样命中缓存（如果 B 也在缓存且过期）→ 同样失败 → 跳 C...
连续好多首全部失败
```

> 补充：`resolveAndPlayByIndex` 里的"延迟 1.5s 重试"也命中同一份缓存，所以**同一首歌两次尝试拿到的是同一个过期 URL**，白等 1.5s。

---

## 三、根因归纳

| # | 根因 | 位置 | 影响 |
|---|------|------|------|
| 1 | **播放失败重试路径命中 5 分钟 URL 缓存**，返回已过期直链 | `NetworkMusicManager.resolvePlayUrl:126` + `PlayerViewModel.resolveStreamUrl` | 主因：重试永远拿到旧 URL，必然再失败，最终连跳多首 |
| 2 | `resolveAndPlayUrl` 返回非空 URL 即缓存，未做时效性判断 | `NetworkMusicManager.resolvePlayUrl:132-139` | 把已过期/即将过期的 URL 当有效缓存 |
| 3 | **无跨源 fallback**：源（如 meting）全挂时 `services[src]` 路由返回 null，不会用歌名+艺术家在其他源重搜替换 | `NetworkMusicManager.resolvePlayUrl:108` | 满足不了需求 3/4/5 |
| 4 | 同一首歌 `resolveAndPlayByIndex` 的 1.5s 重试也是同一份缓存 | `PlayerViewModel.resolveAndPlayByIndex:284-288` | 白等，无新结果 |

---

## 四、方案设计：多级降级播放恢复

按用户需求 2→5 逐级设计，从轻到重，任一级成功即恢复播放，全部失败才跳下一首。

### 层级 0：修复缓存失效重试（问题 2 的核心）

**目标**：播放失败后的重试，绝不能命中旧缓存。

**改动 `NetworkMusicManager.resolvePlayUrl()`**：
- 新增参数 `forceRefresh: Boolean = false`。
- 当 `forceRefresh == true` 时，**跳过缓存读取**，强制走完整的 endpoint + br 降级链。
- 解析成功后**刷新**该条缓存（覆盖旧值 + 更新时间戳）。
- 解析失败时（返回 null）：**移除该 song 的缓存条目**，避免下次又命中过期项。

**改动 `PlayerViewModel.resolveStreamUrl()` / `resolveAndPlayByIndex()`**：
- 传入 `forceRefresh = true`（重试/播放失败恢复一律强制刷新）。
- 初次 `playQueue` 懒加载解析仍用默认 `false`（命中有效缓存可省一次网络请求）。

### 层级 1：同源重搜取另一首（需求 3 的第一半）

**触发**：本源的 endpoint 降级链全部跑完仍解析失败（`resolvePlayUrl` 返回 null）。

**改动 `PlayerViewModel.resolveAndPlayByIndex()`**（解析失败分支）：
- 用 `song.title` + `song.artist` 调 `NetworkMusicManager.search(keyword)`（`search` 本身已多端点降级）。
- 从结果中排除当前 `song.id`，取第一条**可解析出播放 URL**的替代曲（校验方式：对候选曲逐个调 `resolvePlayUrl` 直到成功，避免又拿到死链）。
- 找到 → `playerManager.replaceQueueSong(targetIndex, replacement)`（用替代曲替换队列中该位置）+ `playQueue` 续播。
- 找不到 → 进入层级 2。

### 层级 2：跨源重搜替换（需求 4/5）

**触发**：当前源（song.networkSource）解析全失败，同源重搜也无可用结果。

**改动 `NetworkMusicManager`**，新增方法：

```
suspend fun resolvePlayUrlWithCrossSourceFallback(
    song: Song,
    forceRefresh: Boolean = false
): CrossSourceResult?
```

- 先走原 `resolvePlayUrl`（单源，含 forceRefresh）。
- 若失败，按 `orderedServices()` 顺序遍历**除当前源之外**的其他已注册源（meting → jamendo → baidu，设置页可排序）：
  - 每个候选源用 `title + artist` 调其 `search()`。
  - 对候选结果逐个调该源的 `resolvePlayUrl()` 校验，取第一条成功解析出可播 URL 的曲目。
  - 成功 → 返回 `CrossSourceResult(replacement: Song, playUrl: String)`。
- 全部源失败 → 返回 null（进入层级 3 跳曲）。

**改动 `PlayerViewModel.resolveAndPlayByIndex()`**：
- 层级 1 失败后改调 `resolvePlayUrlWithCrossSourceFallback`。
- 拿到结果 → 用 `replacement`（带解析好的 playUrl）**替换队列中 targetIndex 位置的歌曲**，然后 `playQueue` 播放。
- 记录日志：`"cross-source failover: 原(meting:歌名) → 现(jamendo:歌名)"`，UI 可提示"已用其他源替换播放"。

### 层级 3：全部失效 → 跳下一首（现状保留）

**现状已满足**：`resolveAndPlayByIndex` 重试仍失败即 `playerManager.next()`。本方案保留，仅在层级 1/2 也失败后到达。

---

## 五、涉及文件与改动点

| 文件 | 改动 |
|------|------|
| `backend/network/NetworkMusicManager.kt` | `resolvePlayUrl` 加 `forceRefresh` + 失败清缓存；新增 `resolvePlayUrlWithCrossSourceFallback` + 候选曲可播校验 |
| `ui/viewmodel/PlayerViewModel.kt` | `resolveStreamUrl` / `resolveAndPlayByIndex` 传 `forceRefresh`；新增层级 1 同源重搜、层级 2 跨源替换；队列替换方法调用 |
| `player/PlayerManager.kt` | 新增 `replaceQueueSong(index, song)`（替换队列元素并同步 ExoPlayer MediaItem），或复用现有 `updateStreamUrl` + `replayAt` 组合 |
| `backend/network/MetingApiService.kt` | （如需）暴露当前 endpoint 是否全部失效的判定，供层级降级日志 |
| `data/model/NetworkSource.kt` | 如需让"跨源搜索"覆盖设置页可选端口，补充 ALAPI / JIOSAAVN 的注册支持（当前未实现服务） |

> 注：`NetworkSource` 枚举已声明 ALAPI / JIOSAAVN，但 `NasMusicApp` 的 `services` map 目前只注册了 meting（+ 可选 baidu / jamendo）。本方案的多级降级在当前**已注册源**范围内工作；要接入 ALAPI / JIOSAAVN 属另立功能，不在本次范围。

---

## 六、边界与取舍

1. **缓存 TTL 5 分钟是否改短**：不直接改短，因为正常播放命中有效缓存是有益的（省一次网络请求）。问题不在 TTL 长短，而在"失败重试还命中旧缓存"。用 `forceRefresh` 精确解决。
2. **跨源替换改变歌曲元数据**：替代曲的 title/artist/cover 可能与原歌不同（不同平台收录不同）。替换后 UI 显示的是替代曲信息，属可接受权衡（至少能播）。日志 + 可选 toast 提示。
3. **候选曲可播校验**：为避免"搜到但依然播不了"（需求 3 的顾虑），对候选取第一首 `resolvePlayUrl` 成功的，而不是第一首搜索结果——确保替换即能播。
4. **防死循环**：跨源替换成功后用替代曲的 id 重新计入 `lastErrorRetryIndex`；若替代曲也失败，按正常错误路径再走一轮层级 0-2，最多对"原曲 + 替代曲"各允许一次层级 2，防止无限替换循环。

---

## 七、验证计划

1. **单测**：`NetworkMusicManagerTest` — `forceRefresh=true` 跳过缓存；解析失败清缓存；跨源 fallback 顺序正确（排除当前源、按 orderedServices 顺序）。
2. **单测**：`PlayerViewModelTest` — 解析失败 → 同源重搜 → 跨源替换 → 替换后 `playQueue` 调用参数正确；全部失败 → `next()`。
3. **真机回归**：电视上连续播放 10+ 首网络歌曲，模拟端点失效（临时改配置到坏端点），验证：
   - 前几首正常播放（缓存命中）。
   - 出现失败后自动强制刷新解析（不再命中旧缓存）恢复。
   - 全端点失效时跨源替换播放（UI 提示）。
   - 全部失效时正常跳下一首，不卡死。
4. **构建**：`assembleRelease` + `testDebugUnitTest` + `lintDebug` 全绿。

---

## 八、实施顺序（确认后执行）

1. 层级 0：`NetworkMusicManager.resolvePlayUrl(forceRefresh)` + 失败清缓存 + `PlayerViewModel` 透传。
2. 层级 1：`resolveAndPlayByIndex` 解析失败分支加同源重搜（title+artist 搜索 + 可播校验）。
3. 层级 2：`NetworkMusicManager.resolvePlayUrlWithCrossSourceFallback` + `PlayerManager.replaceQueueSong`。
4. 补单测 + 真机回归。

---

> 请确认本方案（尤其层级顺序、跨源替换行为、`forceRefresh` 方案）后再开始开发。有异议可指出，我会调整后按确认结果实施。