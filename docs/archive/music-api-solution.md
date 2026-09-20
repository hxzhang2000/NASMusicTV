# Android 音乐播放器 - 免费音乐 API 技术方案

## 一、方案概述

本方案针对 Android 应用开发，提供免费、无需自建后端的音乐搜索与播放解决方案。重点考虑国内网络环境的可用性。

---

## 二、API 方案对比

### 2.1 方案总览

| 方案 | 类型 | 国内访问 | 需要Key | 搜索 | 播放 | 歌词 | 封面 | 稳定性 | 推荐指数 |
|------|------|---------|---------|------|------|------|------|--------|---------|
| **Meting-API** | 网易云/QQ音乐 | ✅ 快 | ❌ | ✅ | ✅ | ✅ | ✅ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **ALAPI** | 网易云 | ✅ 快 | ❌ | ✅ | ✅ | ✅ | ✅ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **JioSaavn** | 印度音乐 | ⚠️ 慢 | ❌ | ✅ | ✅ | ✅ | ✅ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **Spotify (数据)** | 元数据 | ⚠️ 慢 | ❌ | ✅ | ❌ | ❌ | ✅ | ⭐⭐⭐⭐ | ⭐⭐ |
| **Deezer** | 试听 | ⚠️ 慢 | ❌ | ✅ | ⚠️30秒 | ❌ | ✅ | ⭐⭐⭐ | ⭐⭐ |
| **MusicBrainz** | 元数据 | ⚠️ 慢 | ❌ | ✅ | ❌ | ❌ | ❌ | ⭐⭐⭐⭐⭐ | ⭐⭐ |

### 2.2 推荐方案

#### 🥇 首选方案：Meting-API（公益服务）

**优点：**
- 国内服务器，访问速度快
- 支持网易云音乐 + QQ音乐
- 接口简洁，无需认证
- 搜索、播放链接、歌词、封面全覆盖

**缺点：**
- 公益服务，可能不稳定
- 无SLA保障

**适用场景：** 个人项目、快速原型、学习开发

---

#### 🥈 备选方案：ALAPI

**优点：**
- 国内服务，访问稳定
- 文档完善
- 网易云音乐接口丰富

**缺点：**
- 可能有调用频率限制
- 仅支持网易云

**适用场景：** 仅需网易云音乐数据的项目

---

#### 🥉 补充方案：JioSaavn

**优点：**
- 完全免费，无需Key
- 曲库大（印度+国际音乐）
- 社区活跃，SDK完善

**缺点：**
- 国内访问速度慢
- 主要是印度音乐内容

**适用场景：** 面向国际用户、需要大曲库

---

## 三、Meting-API 详细接口文档

### 3.1 基础信息

- **API 地址：** `https://meting.mikus.ink/api`
- **请求方式：** GET
- **认证方式：** 无需认证
- **响应格式：** JSON / 302重定向

### 3.2 请求参数

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| server | string | 否 | tencent | 音乐平台：`netease`（网易云）、`tencent`（QQ音乐） |
| type | string | 否 | playlist | 请求类型：`song`/`playlist`/`artist`/`search`/`url`/`lrc`/`pic` |
| id | string | 否 | - | 资源ID（歌单ID、歌曲ID、搜索关键词等） |

### 3.3 接口列表

#### 3.3.1 搜索歌曲

```
GET /api?server=netease&type=search&id={关键词}
```

**示例：**
```
https://meting.mikus.ink/api?server=netease&type=search&id=周杰伦
```

**响应：**
```json
[
  {
    "name": "稻香",
    "artist": "周杰伦",
    "url": "https://meting.mikus.ink/api?server=netease&type=url&id=123456",
    "pic": "https://meting.mikus.ink/api?server=netease&type=pic&id=123456",
    "lrc": "https://meting.mikus.ink/api?server=netease&type=lrc&id=123456",
    "id": "123456"
  }
]
```

#### 3.3.2 获取播放链接

```
GET /api?server=netease&type=url&id={歌曲ID}
```

**响应：** 302 重定向到音频文件 URL

**示例：**
```
https://meting.mikus.ink/api?server=netease&type=url&id=123456
→ 302 → https://music.126.net/song/xxx.mp3
```

#### 3.3.3 获取歌词

```
GET /api?server=netease&type=lrc&id={歌曲ID}
```

**响应：**
```
[00:00.00] 作词 : 周杰伦
[00:01.00] 作曲 : 周杰伦
[00:10.50]对这个世界如果你有太多的抱怨
[00:15.30]跌倒了就不敢继续往前走
```

#### 3.3.4 获取封面图片

```
GET /api?server=netease&type=pic&id={歌曲ID}
```

**响应：** 302 重定向到图片 URL

#### 3.3.5 获取歌单详情

```
GET /api?server=netease&type=playlist&id={歌单ID}
```

**响应：** 歌单内所有歌曲的 JSON 列表

#### 3.3.6 获取歌手歌曲

```
GET /api?server=netease&type=artist&id={歌手ID}
```

#### 3.3.7 获取歌曲详情

```
GET /api?server=netease&type=song&id={歌曲ID}
```

### 3.4 错误码

| 状态码 | 说明 |
|--------|------|
| 200 | 成功 |
| 302 | 重定向（播放链接/封面） |
| 400 | 参数错误 |
| 403 | 无法获取资源（可能版权限制） |

---

## 四、ALAPI 详细接口文档

### 4.1 基础信息

- **API 地址：** `https://alapi.cn/api/music`
- **请求方式：** GET / POST
- **认证方式：** 无需认证（可能有频率限制）

### 4.2 接口列表

#### 4.2.1 搜索歌曲

```
GET /api/music/search?keyword={关键词}&limit=10&offset=0
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | string | 是 | 搜索关键词 |
| limit | int | 否 | 返回数量，默认10 |
| offset | int | 否 | 偏移量，用于分页 |
| type | int | 否 | 搜索类型：1单曲，10专辑，100歌手，1000歌单 |

#### 4.2.2 获取播放链接

```
GET /api/music/url?id={歌曲ID}
```

#### 4.2.3 获取歌曲详情

```
GET /api/music/detail?ids={歌曲ID}
```

#### 4.2.4 获取歌词

```
GET /api/music/lrc?id={歌曲ID}
```

#### 4.2.5 获取热门评论

```
GET /api/music/comment/hot?id={歌曲ID}&limit=10&offset=0
```

---

## 五、JioSaavn 接口文档

### 5.1 基础信息

- **API 地址：** `https://saavn.me` 或 `https://jiosaavn.sumitkolhe.io`
- **请求方式：** GET
- **认证方式：** 无需认证

### 5.2 接口列表

#### 5.2.1 搜索歌曲

```
GET /search?song={关键词}
```

**示例：**
```
https://saavn.me/search?song=tum+ko
```

#### 5.2.2 获取歌曲详情

```
GET /songs?id={歌曲ID}
```

#### 5.2.3 获取专辑详情

```
GET /albums?id={专辑ID}
```

#### 5.2.4 获取歌词

```
GET /lyrics?id={歌曲ID}
```

### 5.3 SDK 支持

| SDK | 地址 | 特点 |
|-----|------|------|
| jiosaavn-sdk | npm: jiosaavn-sdk | TypeScript，支持React Native |
| @saavn-labs/sdk | npm: @saavn-labs/sdk | 类型安全，完整API |
| JiosaavnPy | github: ZingyTomato/JiosaavnPy | Python版本 |

---

## 六、Android 集成方案

### 6.1 技术架构

```
┌─────────────────────────────────────────────┐
│                  Android App                │
├─────────────────────────────────────────────┤
│  UI Layer (Jetpack Compose)                 │
├─────────────────────────────────────────────┤
│  ViewModel (MVVM)                           │
├─────────────────────────────────────────────┤
│  Repository Layer                           │
│  ├─ MetingApiService (首选)                 │
│  ├─ AlApiService (备选)                     │
│  └─ JioSaavnService (补充)                  │
├─────────────────────────────────────────────┤
│  Network Layer (OkHttp + Gson)              │
└─────────────────────────────────────────────┘
```

### 6.2 核心代码示例

#### 6.2.1 数据模型

> **v2.2.0 适配**：网络歌曲与 NAS 歌曲共用统一的 `Song` 数据类，通过 `isNetworkSong`、`networkSource`、`networkId` 三个字段区分来源（详见 `network-music-feature-proposal.md` 第 3.1 节）。下方为简化示例，实际开发使用项目现有 `Song` 模型。

```kotlin
// data/model/Song.kt（项目现有模型，网络歌曲复用）
data class Song(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val coverUrl: String? = null,
    val streamUrl: String? = null,      // 网络歌曲播放时实时解析赋值
    val durationMs: Long = 0L,
    // ... 其他 NAS 字段
    // ↓↓↓ 网络歌曲扩展字段 ↓↓↓
    val isNetworkSong: Boolean = false,
    val networkSource: String? = null,  // "meting" | "alapi" | "jiosaavn"
    val networkId: String? = null       // 在源平台的原始 ID
)
```

> **歌词数据说明**：歌词相关 API 返回原始 LRC 文本（`String?`），不返回解析后的歌词行对象。歌词解析由项目现有 `LrcParser.parse(lrcText)` 统一处理，输出 `List<LyricsLine>`（字段：`time: Long`、`text: String`、`wordTimestamps`），无需在网络层定义额外的歌词行数据类。

#### 6.2.2 API 接口定义

> **v2.2.0 适配**：项目已移除 Retrofit，API 接口定义为普通 Kotlin 接口（不使用 `@GET` / `@Query` 注解），具体 HTTP 请求由实现类用 OkHttp + Gson 完成。
>
> 注：下方为简化示例接口。实际开发使用 `network-music-feature-proposal.md` 中定义的 `NetworkMusicService` 接口和 `MetingApiService` 实现类。

```kotlin
// backend/network/MetingApiService.kt
interface MetingApiService {

    /** 搜索歌曲 */
    suspend fun search(
        server: String = "netease",
        keyword: String
    ): List<Song>

    /** 获取真实播放链接（处理 302 重定向，返回 Location header） */
    suspend fun getPlayUrl(
        server: String = "netease",
        songId: String
    ): String?

    /** 获取歌词（LRC 文本） */
    suspend fun getLyrics(
        server: String = "netease",
        songId: String
    ): String?

    /** 获取歌单内所有歌曲 */
    suspend fun getPlaylist(
        server: String = "netease",
        playlistId: String
    ): List<Song>
}

// backend/network/AlApiService.kt（备选方案）
interface AlApiService {
    suspend fun search(keyword: String): List<Song>
    suspend fun getPlayUrl(songId: String): String?
    suspend fun getLyrics(songId: String): String?
}

// backend/network/JioSaavnService.kt（补充方案）
interface JioSaavnService {
    suspend fun search(keyword: String): List<Song>
    suspend fun getPlayUrl(songId: String): String?
    suspend fun getLyrics(songId: String): String?
}
```

> 注：具体实现参考 `network-music-feature-proposal.md` 第四章 `MetingApiService` 完整实现（含守护线程池、OkHttpClient、Gson 解析、`MetingSongItem` data class）。

#### 6.2.3 Repository / Manager 实现

> **v2.2.0 适配**：
> - 移除 Retrofit（项目 v2.1.0 已移除 Retrofit 依赖），改用 OkHttp + Gson，与现有 JellyfinAdapter / NavidromeAdapter 一致
> - OkHttpClient 使用守护线程池（`isDaemon = true`），防止阻止进程退出
> - 日志使用 `AppLog`（Release 构建自动抑制），不用 `e.printStackTrace()`
> - 通过 `NasMusicApp` 手动 DI 注入，不使用静态 `getInstance()`
>
> 实际开发使用 `NetworkMusicManager`（见 `network-music-feature-proposal.md` 第五章），通过 `services: Map<String, NetworkMusicService>` 路由到不同 API 实现。完整实现参考主方案文档，本方案不再重复。

#### 6.2.4 ViewModel 实现

> **v2.2.0 适配**：
> - ViewModel 通过 `AndroidViewModel(app)` + `NasMusicApp` DI 获取 `NetworkMusicManager`，不使用静态 `getInstance()`
> - 搜索状态使用 `UiState<T>` 密封类（Loading/Success/Error），与现有曲库加载逻辑一致
> - 日志使用 `AppLog`

```kotlin
// MainViewModel.kt（实际集成位置，简化示例）
// 以下为 MainViewModel 中网络音乐相关的方法

// 搜索网络歌曲
private val _networkSearchResults = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
val networkSearchResults: StateFlow<UiState<List<Song>>> = _networkSearchResults.asStateFlow()

fun searchNetworkSongs(keyword: String) {
    if (keyword.isBlank()) {
        _networkSearchResults.value = UiState.Success(emptyList())
        return
    }
    _networkSearchResults.value = UiState.Loading
    viewModelScope.launch {
        try {
            val results = networkMusicManager.search(keyword)
            _networkSearchResults.value = UiState.Success(results)
        } catch (e: Exception) {
            AppLog.e("NASMusic", "searchNetworkSongs failed", e)
            _networkSearchResults.value = UiState.Error("搜索失败: ${e.message?.take(50)}")
        }
    }
}

// 播放网络歌曲
fun playNetworkSong(song: Song) {
    viewModelScope.launch {
        // 1. 解析真实播放链接（302 重定向）
        val playUrl = networkMusicManager.resolvePlayUrl(song) ?: run {
            showError("无法获取播放链接")
            return@launch
        }
        // 2. 组装 Song 对象并复用现有 PlayerManager 播放
        val playable = song.copy(streamUrl = playUrl)
        playerManager.playSong(playable)
        // 3. 记录播放历史
        prefs.recordPlay(playable.id)
        // 4. 歌词由 currentSong.collect 统一触发 loadLyricsForCurrentSong()
    }
}
```

### 6.3 音频播放实现

> **v2.2.0 适配**：项目已有 `PlayerManager`（基于 Media3 1.2.1），网络歌曲播放应复用现有 `PlayerManager.playSong(song)`，无需新建播放器。完整播放流程见 `network-music-feature-proposal.md` 第七章。

```kotlin
// 使用 ExoPlayer 播放（示例，实际复用 PlayerManager）
class MusicPlayerManager(private val context: Context) {

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build()
    }

    fun play(url: String) {
        val mediaItem = MediaItem.fromUri(url)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun release() {
        exoPlayer.release()
    }
}
```

---

## 七、多源容错方案

### 7.1 策略说明

为提高稳定性，`NetworkMusicManager` 已内置多源容错机制：

```
搜索请求 → NetworkMusicManager.search()
           ├─ 优先：使用默认源（如 Meting-API）
           └─ 失败：依次尝试备选源（ALAPI → JioSaavn）
```

### 7.2 实现说明

多源容错逻辑已集成在 `NetworkMusicManager` 中（见 `network-music-feature-proposal.md` 第五章），无需单独的 `MultiSourceRepository` 类。

**关键设计**：
- `NetworkMusicManager` 持有所有 API 服务的实例（`services: Map<String, NetworkMusicService>`）
- `search()` 方法按优先级依次尝试各源，返回第一个成功的结果
- `resolvePlayUrl()` 和 `resolveLyrics()` 根据歌曲的 `networkSource` 字段路由到对应服务
- 所有服务通过 `NasMusicApp` 手动 DI 注入，不使用静态工厂方法

**NasMusicApp 注册示例**：

```kotlin
// NasMusicApp.kt
class NasMusicApp : Application() {
    lateinit var networkMusicManager: NetworkMusicManager
        private set

    override fun onCreate() {
        super.onCreate()
        // 初始化网络音乐管理器（内部创建各 API 服务实例）
        networkMusicManager = NetworkMusicManager(this, appPreferences)
    }
}
```

---

## 八、注意事项

### 8.1 法律风险

- 这些 API 均为非官方接口，可能随时失效
- 仅限个人学习使用，**禁止商业用途**
- 部分接口可能涉及版权问题，请遵守当地法律法规

### 8.2 技术风险

- 公益服务无SLA保障，可能不稳定
- 接口可能随时变更或下线
- 建议实现多源容错机制

### 8.3 性能优化

- 实现本地缓存（DataStore + Gson 序列化，与现有 AppPreferences 一致，避免引入 Room 增加 APK 体积和 KAPT 编译复杂度）
- 使用协程处理并发请求
- 预加载下一首歌的播放链接
- 图片使用 Coil/Glide 加载并缓存

### 8.4 推荐的依赖库

> **v2.2.0 适配**：项目已移除 Retrofit 和 Room，统一使用 OkHttp + Gson + DataStore，与现有 Adapter / AppPreferences 保持一致。

```gradle
// build.gradle.kts
dependencies {
    // 网络（OkHttp + Gson，与 JellyfinAdapter / NavidromeAdapter 一致）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // 播放器
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")

    // 图片加载
    implementation("io.coil-kt:coil-compose:2.5.0")

    // 本地缓存（DataStore，替代 Room）
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

---

## 九、总结

### 推荐方案

| 场景 | 推荐方案 | 理由 |
|------|---------|------|
| **快速原型/学习** | Meting-API | 国内访问快，接口简单 |
| **仅需网易云** | ALAPI | 文档完善，接口丰富 |
| **面向国际** | JioSaavn | 曲库大，SDK完善 |
| **生产环境** | 自建后端 + Meting-API | 需要稳定性保障 |

### 最终建议

1. **开发阶段**：使用 Meting-API 快速验证功能
2. **测试阶段**：接入多源容错机制
3. **上线前**：评估是否需要自建后端（根据用户量决定）
4. **持续关注**：API 可能失效，需要定期检查和更新

---

## 十、参考链接

- Meting-API 文档：https://meting.mikus.ink/
- ALAPI 文档：https://alapi.cn/
- JioSaavn SDK：https://github.com/2004durgesh/jiosaavn-sdk
- ExoPlayer 文档：https://developer.android.com/media/media3/exoplayer
- Jetpack Compose：https://developer.android.com/compose
