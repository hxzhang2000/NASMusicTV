# 拼音首字母搜索功能开发方案

**创建日期**: 2026-09-02
**状态**: 已实现（v2.25.7）
**优先级**: 低（非紧急需求）

---

## 1. 需求背景

当前搜索功能仅支持**子串匹配**（精确过滤模式）和**来源点亮模式**（不过滤）。用户需要通过拼音首字母搜索歌曲（例如输入 "zjl" 匹配 "周杰伦"）。

README 第 94 行标注了"拼音首字母匹配（'zjl' 搜索 '周杰伦'）"，但代码中尚未实现。

**已有基础设施**:
- `TextInputDialog`（`app/src/main/java/com/nasmusic/tv/ui/screens/TextInputDialog.kt`）：全 App 共享的文本输入对话框，支持自定义 D-Pad 键盘（字母/符号/大小写切换）+ 系统 IME 模式（中文输入），搜索页已在使用
- `PinyinUtils`（`app/src/main/java/com/nasmusic/tv/util/PinyinUtils.kt`）：基于 TinyPinyin 的拼音转换工具，支持全拼和首字母

**需求核心**: 曲库统一搜索时，输入拼音/首字母（或中文）均能跨源搜索到结果。

---

## 2. 当前搜索实现分析

### 2.0 搜索来源点亮控制（已实现 ✅）

**确认**: 曲库统一搜索的各个来源按钮已是控制按钮，实现逻辑如下：

| 层级 | 实现位置 | 行为 |
|------|----------|------|
| **UI** | `SearchTab.kt` `SearchSourceBar` | 显示来源芯片，点亮=亮色/熄灭=暗色，点击触发 `onToggleSource(source)` |
| **状态** | `MainViewModel.kt` `_enabledSearchSources` | `MutableStateFlow<Set<MusicSourceType>>` 记录点亮来源，默认全部点亮 |
| **切换** | `MainViewModel.toggleSearchSource()` | 切换来源状态，**立即重新搜索** (`searchSongsOnServer(kw, force = true)`) |
| **聚合器** | `SearchAggregator.search(sources = _enabledSearchSources.value)` | 仅对 `sources` 集合中的源启动并行搜索协程 |
| **源级检查** | `SearchAggregator.kt` 行 81/101/121/146/167 | `if (MusicSourceType.NAS in sources && backendAdapter != null)` 每个源独立检查 |

**结果**: 
- 某来源**不点亮** → 不在 `sources` 集合中 → 对应协程直接返回 `emptyList()` → **完全不搜索该源**
- 结果列表**不会出现**未点亮来源的歌曲
- 切换后**立即生效**（自动触发重新搜索）

**无需额外开发**，现有实现已满足"来源按钮控制搜索范围"的需求。

---

### 2.1 跨源搜索聚合器

**核心类**: `SearchAggregator.kt`

```kotlin
suspend fun search(
    keyword: String,
    sources: Set<MusicSourceType> = MusicSourceType.entries.toSet(),
    directoryMode: Boolean = false,
    baiduKeyword: String? = null,
    filterMode: FilterMode = FilterMode.NONE  // PRECISE = 精细过滤（搜索页）
): SearchAggregatorResult
```

**搜索流程**:
1. 为每个源（NAS/网络/百度/Jamendo/本地）创建独立协程
2. 并行搜索各源
3. 合并去重（同源 title+artist 去重）
4. **精细过滤**（PRECISE 模式）：
   ```kotlin
   val k = keyword.trim().lowercase()
   allResults.filter { ranked ->
       val song = ranked.song
       song.title.lowercase().contains(k) ||
           song.artist.lowercase().contains(k) ||
           song.path?.lowercase()?.contains(k) == true
   }
   ```

### 2.2 各源搜索接口

所有后端适配器均实现 `BackendAdapter.searchSongs(keyword: String)` 接口，返回 `List<Song>`。

| 源 | 实现类 | 搜索接口 | 拼音支持 |
|----|--------|----------|----------|
| NAS | JellyfinAdapter / NavidromeAdapter / SubsonicAdapter / DaoliyuAdapter | `searchSongs` (OkHttp API) | ❌ 依赖后端 |
| 网络 | MetingApiService | `search` (Meting-API) | ❌ 依赖 Meting |
| 百度 | BaiduNetdiskService | `search` / `searchByDirectory` | ❌ 依赖百度 |
| Jamendo | JamendoService | `search` (Jamendo API) | ❌ 依赖 Jamendo |
| 本地 | LocalMusicRepository | `search` (Room 索引) | ❌ 需本地实现 |

**关键点**: 后端 API 只能做子串匹配，拼音匹配需在**客户端精确过滤阶段**实现。

---

## 3. 拼音搜索技术方案

### 3.1 匹配逻辑

**目标**: 输入 "zjl" 能匹配 "周杰伦"（拼音全拼 "zhoujielun"，首字母 "zjl"）

**匹配规则**（OR 关系，**互不冲突，中文输入完全保留现有行为**）:
- **精确子串**: "周杰伦" 包含 "周" → 匹配
- **拼音全拼匹配**: "zhoujielun" 包含 "zjl" → 匹配
- **拼音首字母匹配**: "zjl" 匹配 "zhoujielun" 的首字母 "zjl" → 匹配
- **混合匹配**: 输入 "zhou zjl" 同时匹配全拼 "zhou" + 首字母 "zjl" → 匹配

**实现公式**:
```
关键词 k 的每部分 p = k.trim().split("\\s+") 的任一元素

匹配条件 = 标题包含 p OR 歌手包含 p OR
           标题拼音全拼包含 p OR 歌手拼音全拼包含 p OR
           标题拼音首字母包含 p OR 歌手拼音首字母包含 p
```

**示例**:
| 输入 | 匹配 "周杰伦" | 原因 |
|------|---------------|------|
| "zjl" | ✅ | 首字母 zjl |
| "zhoujielun" | ✅ | 全拼 zhoujielun |
| "周" | ✅ | 精确子串 "周" |
| "周杰" | ✅ | 精确子串 "周杰" |
| "zhou" | ✅ | 全拼前缀 "zhou" |
| "zhouji" | ❌ | 全拼缺少 "elun"，首字母缺少 "l" |
| "zjl 周杰" | ✅ | 首字母 zjl OR 子串 "周杰" |

**中文输入完全不受影响**: 现有子串匹配逻辑保留，拼音匹配作为**额外 OR 条件**追加。

### 3.2 拼音生成性能优化

**TinyPinyin 性能**:
- 全角字符转拼音：~0.1ms/字
- 首字母提取：~0.05ms/字
- 1000 首歌拼音生成：~100ms

**优化措施**:
1. **缓存拼音结果**：同一首歌拼音只计算一次（Song 对象级缓存）
2. **延迟生成**：仅在 `filterMode == FilterMode.PRECISE` 时生成拼音
3. **关键词预处理**：关键词 k 在过滤前预处理为小写 + 分词
4. **短路退出**：只要一个条件满足即返回 true，不继续计算剩余条件

---

## 4. 实现步骤

### 4.1 数据层改动

#### 步骤 1.1: 新增 `SongWithPinyin` 扩展

**文件**: `app/src/main/java/com/nasmusic/tv/data/model/SongWithPinyin.kt`（新增）

```kotlin
package com.nasmusic.tv.data.model

import com.nasmusic.tv.util.PinyinUtils

/**
 * 歌曲拼音缓存包装器（内部使用）
 *
 * 避免搜索过滤阶段重复计算拼音，仅在 FilterMode.PRECISE 时使用
 */
data class SongWithPinyin(
    val song: Song,
    val pinyin: String = PinyinUtils.toPinyin(song.title),
    val initials: String = PinyinUtils.toPinyinInitials(song.title),
    val artistPinyin: String = PinyinUtils.toPinyin(song.artist),
    val artistInitials: String = PinyinUtils.toPinyinInitials(song.artist)
) {
    companion object {
        /**
         * 为歌曲列表生成拼音缓存（Map<songId, SongWithPinyin>）
         * 用于精确过滤时避免重复计算
         */
        fun fromSongs(songs: List<Song>): Map<String, SongWithPinyin> {
            return songs.associateBy({ it.id }) { song ->
                SongWithPinyin(song)
            }
        }
    }
}
```

#### 步骤 1.2: 新增 `PinyinMatcher` 工具类

**文件**: `app/src/main/java/com/nasmusic/tv/util/PinyinMatcher.kt`（新增）

```kotlin
package com.nasmusic.tv.util

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongWithPinyin

/**
 * 拼音匹配工具类
 *
 * 匹配逻辑：子串匹配 OR 拼音全拼匹配 OR 拼音首字母匹配
 * - 中文输入完全保留现有子串匹配行为
 * - 拼音/首字母输入作为额外匹配路径补充
 * - **仅 TV 端启用拼音匹配**（手机端触屏输入汉字方便，无需拼音）
 */
object PinyinMatcher {
    /**
     * 判断关键词是否匹配歌曲（子串 + 拼音全拼 + 拼音首字母）
     *
     * @param song 歌曲对象
     * @param keyword 搜索关键词（已 trim + lowercase）
     * @param isTVDevice 是否为 TV 设备（true=启用拼音匹配，false=仅子串匹配）
     * @param pinyinCache 拼音缓存 Map<songId, SongWithPinyin>（可选，为 null 时实时计算）
     * @return 是否匹配
     */
    fun matches(
        song: Song,
        keyword: String,
        isTVDevice: Boolean,
        pinyinCache: Map<String, SongWithPinyin>? = null
    ): Boolean {
        if (keyword.isBlank()) return false

        // 1. 标准子串匹配（标题/歌手/文件名）——中文输入走这里，完全保留现有行为
        if (matchesSubstring(song, keyword)) return true

        // 2. 非 TV 设备：仅子串匹配，不启用拼音匹配
        if (!isTVDevice) return false

        // 3. 获取拼音信息（优先缓存）
        val pinyin = pinyinCache?.get(song.id)
            ?: SongWithPinyin(song)

        // 4. 拼音全拼匹配
        if (pinyin.pinyin.contains(keyword)) return true
        if (pinyin.artistPinyin.contains(keyword)) return true

        // 5. 拼音首字母匹配（优先级低于全拼）
        if (pinyin.initials.contains(keyword)) return true
        if (pinyin.artistInitials.contains(keyword)) return true

        return false
    }

    /**
     * 子串匹配：标题/歌手/文件名包含关键词
     */
    private fun matchesSubstring(song: Song, keyword: String): Boolean {
        return song.title.lowercase().contains(keyword) ||
            song.artist.lowercase().contains(keyword) ||
            song.path?.lowercase()?.contains(keyword) == true
    }

    /**
     * 分词匹配：关键词按空格分割后，匹配任一分词即可
     *
     * 例如: "zjl 周杰" -> 匹配首字母 "zjl" OR 精确子串 "周杰"
     */
    fun matchesMultipleWords(
        song: Song,
        keyword: String,
        isTVDevice: Boolean,
        pinyinCache: Map<String, SongWithPinyin>? = null
    ): Boolean {
        val parts = keyword.trim().split("\\s+")
        if (parts.isEmpty()) return false

        // 只要有一个分词匹配即返回 true
        return parts.any { part ->
            matches(song, part, isTVDevice, pinyinCache)
        }
    }
}
```

#### 步骤 1.3: 扩展 `SearchAggregator.filterMode` 逻辑

**文件**: `app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt`

**当前代码**（~行 196-207）:
```kotlin
// 精细过滤（搜索页）：只保留标题/歌手/文件名包含关键词的歌曲
val filtered = if (filterMode == FilterMode.PRECISE) {
    val k = keyword.trim().lowercase()
    allResults.filter { ranked ->
        val song = ranked.song
        song.title.lowercase().contains(k) ||
            song.artist.lowercase().contains(k) ||
            song.path?.lowercase()?.contains(k) == true
    }
} else {
    allResults
}
```

**新增逻辑**:
```kotlin
// 精细过滤（搜索页）：子串匹配 OR 拼音全拼匹配 OR 拼音首字母匹配
// 中文输入走子串匹配，拼音输入走拼音匹配，互不干扰
// **仅 TV 端启用拼音匹配**（手机端触屏输入汉字方便，无需拼音）
val filtered = if (filterMode == FilterMode.PRECISE) {
    val k = keyword.trim().lowercase()
    val parts = k.split("\\s+")  // 支持多词搜索："zjl 周杰" -> 匹配首字母 OR 精确子串

    // 判断是否为 TV 设备
    val isTVDevice = nasMusicApp.packageManager.hasSystemFeature("android.software.leanback")

    // 仅 TV 设备且需要拼音匹配时，提前生成拼音缓存
    val pinyinCache = if (isTVDevice) {
        allResults.map { it.song }.let { songs ->
            SongWithPinyin.fromSongs(songs)
        }
    } else {
        emptyMap()
    }

    allResults.filter { ranked ->
        val song = ranked.song
        // 子串匹配 OR 拼音匹配 OR 多词匹配
        PinyinMatcher.matchesMultipleWords(
            song = song,
            keyword = k,
            isTVDevice = isTVDevice,
            pinyinCache = pinyinCache
        )
    }
} else {
    allResults
}
```

**性能考量**:
- 拼音生成仅在一次搜索时进行（过滤阶段）
- `SongWithPinyin` 对象约 200 字节，1000 首歌约 200KB 内存
- 过滤计算时间增加约 10-20ms（可接受）

### 4.2 无 UI 层改动

**不需要**:
- ❌ 设置页"拼音搜索"开关（默认开启，仅 TV 端生效）
- ❌ 发现页拼音搜索（用户已明确：不需要）
- ❌ 新增拼音输入窗口（已有 `TextInputDialog`，支持系统 IME 中文输入 + 自定义字母键盘）

**复用现有**: 搜索页使用 `TextInputDialog`（`showQrCode=true`, `showHistory=true`），用户可直接输入中文或拼音。

**设备区分**: 
- **TV 端**（`android.software.leanback` = true）：启用拼音匹配
- **手机端**：仅子串匹配，拼音匹配完全关闭（无性能开销）

---

## 5. 性能评估

### 5.1 搜索性能影响

| 场景 | 当前耗时 | TV 端拼音搜索耗时 | 手机端耗时 | 增加耗时 |
|------|----------|-------------------|------------|----------|
| 本地 100 首歌子串匹配 | ~5ms | ~15ms | ~5ms | TV:+10ms / 手机:0ms |
| NAS 5 个源并行搜索 | ~500ms | ~510ms | ~500ms | TV:+10ms / 手机:0ms |
| 网络端点搜索 | ~100ms | ~110ms | ~100ms | TV:+10ms / 手机:0ms |
| 精确过滤 1000 首歌 | ~50ms | ~60ms | ~50ms | TV:+10ms / 手机:0ms |

**结论**: 
- **TV 端**：拼音搜索性能开销小（~10ms），不影响整体搜索体验
- **手机端**：完全无额外开销（不生成拼音缓存，不执行拼音匹配逻辑）

### 5.2 内存占用

- `SongWithPinyin` 缓存: 1000 首歌 × 200 字节 ≈ 200KB
- `pinyinCache` Map: 1000 条记录 × 40 字节 ≈ 40KB
- 总内存增加: ~240KB（可接受）

---

## 6. 向后兼容性

### 6.1 默认开启拼音搜索（仅 TV 端）

无需任何开关，直接在 `FilterMode.PRECISE` 时根据设备类型自动判断。

### 6.2 API 不变更

- `BackendAdapter.searchSongs(keyword: String)` 接口不变
- 各源后端 API 调用逻辑不变
- 搜索结果格式不变

### 6.3 现有搜索行为保留

- 依赖后端 API 搜索的源（NAS/网络/百度/Jamendo）保持原样
- 仅在 `filterMode == FilterMode.PRECISE` 时应用拼音匹配
- 发现页（`FilterMode.NONE`）不受影响
- **中文输入完全保留现有子串匹配行为，不受任何影响**
- **手机端完全保持原有行为，零性能开销**

---

## 7. 实施计划

### 阶段 1: 核心功能（1 天）
1. 实现 `SongWithPinyin` 和 `PinyinMatcher` 工具类
2. 修改 `SearchAggregator` 精确过滤逻辑
3. 单元测试：验证匹配规则（关键场景全覆盖）

### 阶段 2: 测试与部署（0.5 天）
1. TV/手机端真机测试（搜索页 `TextInputDialog` 输入中文/拼音/首字母）
2. 性能压测（1000 首歌搜索）
3. 提交 PR + 发版

**总计**: 1.5 天

---

## 8. 风险与注意事项

### 8.1 性能风险

**风险**: 大量歌曲（>10000 首）生成拼音可能较慢

**缓解措施**:
1. 延迟生成：仅在精确过滤时生成
2. 缓存优先：`SongWithPinyin` 使用 `song.id` 作为 key
3. 分页加载：如果 10000+ 首歌，可分批生成拼音

### 8.2 拼音准确度

**风险**: TinyPinyin 对多音字处理可能不准确（如"行"可读 háng/xíng）

**缓解措施**:
1. TinyPinyin 已是项目中选用方案（优先 ICU，fallback TinyPinyin），无需更换
2. 用户可通过精确子串匹配绕过拼音不准确情况

### 8.3 空格分隔关键词

**风险**: 用户输入 "zjl 周杰" 时，需同时匹配首字母和精确子串

**缓解措施**:
- `PinyinMatcher.matchesMultipleWords()` 已处理分词逻辑，只要有一个分词匹配即返回 true

### 8.4 后端搜索结果过多

**风险**: 拼音搜索可能扩大召回范围

**缓解措施**:
1. 保持拼音匹配**仅在精确过滤模式**下生效（搜索页）
2. 发现页使用 `FilterMode.NONE` 不受影响
3. 用户可通过来源点亮模式控制各源搜索结果数量

---

## 9. 测试用例

### 9.1 单元测试

```kotlin
@Test
fun `pinyinMatcher matches title with initials on TV`() {
    val song = Song(
        id = "1",
        title = "周杰伦",
        artist = "周杰伦"
    )
    // TV 端：启用拼音匹配
    assertTrue(PinyinMatcher.matches(song, "zjl", isTVDevice = true))
    assertTrue(PinyinMatcher.matches(song, "zhoujielun", isTVDevice = true))
    assertTrue(PinyinMatcher.matches(song, "zhou", isTVDevice = true))
}

@Test
fun `pinyinMatcher matches title with substring on TV`() {
    val song = Song(
        id = "1",
        title = "周杰伦",
        artist = "周杰伦"
    )
    // TV 端：子串匹配也生效
    assertTrue(PinyinMatcher.matches(song, "周", isTVDevice = true))
    assertTrue(PinyinMatcher.matches(song, "杰伦", isTVDevice = true))
}

@Test
fun `pinyinMatcher matches artist with initials on TV`() {
    val song = Song(
        id = "1",
        title = "七里香",
        artist = "周杰伦"
    )
    assertTrue(PinyinMatcher.matches(song, "zjl", isTVDevice = true))
}

@Test
fun `pinyinMatcher disables pinyin on phone`() {
    val song = Song(
        id = "1",
        title = "周杰伦",
        artist = "周杰伦"
    )
    // 手机端：拼音匹配关闭，仅子串匹配
    assertFalse(PinyinMatcher.matches(song, "zjl", isTVDevice = false))
    assertFalse(PinyinMatcher.matches(song, "zhoujielun", isTVDevice = false))
    // 但子串匹配仍生效
    assertTrue(PinyinMatcher.matches(song, "周", isTVDevice = false))
    assertTrue(PinyinMatcher.matches(song, "杰伦", isTVDevice = false))
}

@Test
fun `pinyinMatcher matches multiple words on TV`() {
    val song = Song(
        id = "1",
        title = "七里香",
        artist = "周杰伦"
    )
    assertTrue(PinyinMatcher.matchesMultipleWords(song, "zjl 周杰", isTVDevice = true))
}

@Test
fun `pinyinMatcher returns false for non-matching keyword on TV`() {
    val song = Song(
        id = "1",
        title = "七里香",
        artist = "周杰伦"
    )
    assertFalse(PinyinMatcher.matches(song, "l", isTVDevice = true))
    assertFalse(PinyinMatcher.matches(song, "zhouj", isTVDevice = true))
}
```

### 9.2 UI 测试

**TV 端场景**:

**场景 1**: 搜索页输入中文 "周杰"
- 匹配 "周杰伦"（子串匹配）

**场景 2**: 搜索页输入拼音首字母 "zjl"
- 匹配 "周杰伦"（首字母匹配）

**场景 3**: 搜索页输入全拼 "zhoujielun"
- 匹配 "周杰伦"（全拼匹配）

**场景 4**: 搜索页输入 "zjl 周杰"
- 匹配 "周杰伦"（首字母 OR 子串）

**场景 5**: 输入 "zhouj"
- 不匹配（全拼缺少 "i"，首字母缺少 "l"）

**手机端场景**:

**场景 6**: 搜索页输入中文 "周杰"
- 匹配 "周杰伦"（子串匹配）

**场景 7**: 搜索页输入拼音首字母 "zjl"
- **不匹配**（手机端拼音匹配关闭）

**场景 8**: 搜索页输入全拼 "zhoujielun"
- **不匹配**（手机端拼音匹配关闭）

**场景 9**: 搜索页输入 "zjl 周杰"
- 仅匹配子串 "周杰" 部分

---

## 10. 变更文件清单

### 10.1 新增文件（2 个）
1. `app/src/main/java/com/nasmusic/tv/util/PinyinMatcher.kt` — 拼音匹配工具类
2. `app/src/main/java/com/nasmusic/tv/data/model/SongWithPinyin.kt` — 歌曲拼音缓存包装器

### 10.2 修改文件（1 个）
1. `app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt` - 精确过滤逻辑

### 10.3 文档（1 个）
1. `docs/archive/pinyin-search-plan.md` - 本方案文档

---

## 11. 验收标准

**TV 端**:
- [ ] 搜索 "zjl" 能匹配 "周杰伦"
- [ ] 搜索 "zhoujielun" 能匹配 "周杰伦"
- [ ] 搜索 "zjl 周杰" 能匹配 "周杰伦"（多词匹配）
- [ ] 搜索 "周杰" 能匹配 "周杰伦"（中文输入完全不受影响）

**手机端**:
- [ ] 搜索 "zjl" **不匹配** "周杰伦"（拼音匹配关闭）
- [ ] 搜索 "zhoujielun" **不匹配** "周杰伦"（拼音匹配关闭）
- [ ] 搜索 "周杰" 能匹配 "周杰伦"（子串匹配正常）
- [ ] 搜索 "zjl 周杰" 仅匹配子串 "周杰" 部分

**性能**:
- [ ] TV 端 1000 首歌搜索耗时增加 < 20ms
- [ ] 手机端 1000 首歌搜索耗时增加 0ms（无额外开销）
- [ ] TV/手机端测试通过（搜索页 `TextInputDialog` 输入中文/拼音/首字母均正常）

---

## 12. 后续优化方向

### 12.1 拼音首字母联想

在搜索框输入 "zj" 时，下拉显示联想词：
- "周杰伦"（首字母 zjl）
- "张杰"（首字母 zj）
- "蒋浩"（首字母 zj）

**依赖**: 需要增量加载歌曲列表，复杂度较高。

### 12.2 歌手拼音搜索

支持搜索歌手的拼音（例如搜索 "zhou" 返回 "周杰伦" 歌手的所有歌曲）。

**实现**: 在 `PinyinMatcher.matches()` 中增加歌手拼音匹配条件（已在 `SongWithPinyin` 中预留 `artistPinyin` / `artistInitials`）。

---

## 13. 参考资料

- TinyPinyin 文档: https://github.com/prome-g/TinyPinyin
- 项目现有 `PinyinUtils` 实现: `app/src/main/java/com/nasmusic/tv/util/PinyinUtils.kt`
- 当前搜索实现: `app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt`
- 搜索输入对话框: `app/src/main/java/com/nasmusic/tv/ui/screens/TextInputDialog.kt`
- 搜索页展示: `app/src/main/java/com/nasmusic/tv/ui/screens/library/SearchTab.kt`