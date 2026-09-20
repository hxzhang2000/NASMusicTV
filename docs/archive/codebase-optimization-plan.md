# NASMusicTV 代码优化方案

> 基于代码审计报告产出的优化方案，按优先级和实施方式分类。
> 版本：v1.2 (已实施 + 功能规划整合) | 最后更新：2026-06-21

**状态图例**：
| 标记 | 含义 |
|------|------|
| ✅ 已完成 | 已修改代码、编译通过 |
| 🔶 未测试 | 已修改代码，编译通过，**但未在设备上运行验证** |
| 🔷 待方案 | 方案已确认，待实施 |
| ⬜ 不修改 | 已决定保持现状 |
| 📝 待审查 | 方案已写，待你审查 |

---

## 目录

- [A. 立即执行（已确认修改）](#a-立即执行已确认修改)
- [B. 方案评估（需先审查再实施）](#b-方案评估需先审查再实施)
- [C. 暂不处理](#c-暂不处理)
- [附录](#附录)

---

## A. 立即执行（已确认修改）

这些项目已达成共识，可以直接实施。按预计工时排序。

---

### A-1 签名凭据提取到 keystore.properties

> **状态**：✅ 已修改 🔶 未测试 — 编译通过，`keystore.properties` 已创建，`.gitignore` 已更新，`build.gradle.kts` 改为从文件读取。Release 构建需 `.properties` 文件存在。

**来源**：1.1 签名凭据硬编码在版本控制中  
**风险等级**：🔴 高（凭据泄露）  
**预计工时**：15 分钟

**现状**：`app/build.gradle.kts:25-28` 中硬编码了 release 签名凭据：
```kotlin
storePassword = "123456"
keyPassword = "123456"
```

**方案**：

1. **新建 `app/keystore.properties`**（*加入 `.gitignore`*）：
```properties
storePassword=此处填写实际密码
keyPassword=此处填写实际密码
keyAlias=nas-music-tv
storeFile=E:\\temp\\NasAudio\\NASMusicTV\\release-key.jks
```

2. **修改 `app/build.gradle.kts`**，在文件顶部加载属性文件：
```kotlin
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = if (keystorePropertiesFile.exists()) {
    java.util.Properties().apply {
        load(java.io.FileInputStream(keystorePropertiesFile))
    }
} else null
```

3. **修改 `signingConfigs.release`** 块，从 `keystoreProperties` 读取凭据：
```kotlin
signingConfigs {
    create("release") {
        if (keystoreProperties != null) {
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
        }
    }
}
```

4. **更新 `.gitignore`**，添加 `keystore.properties`

5. **保留 `release-key.jks` 在仓库中**（或不保留——视你的备份策略而定）。如果 `keystore.properties` 缺失，release 构建编译会失败，但 debug 构建不受影响。

**验证**：
- `keystore.properties` 缺失时 `./gradlew assembleRelease` → 预期失败（安全行为）
- `keystore.properties` 存在时 `./gradlew assembleRelease` → 正常构建

**测试验证方式**：
- 🔶 安装 debug APK 到 TV 设备，确认应用正常启动、播放等功能不受影响
- 🔶 移除 `keystore.properties`，验证 `./gradlew assembleRelease` 编译失败
- 🔶 恢复 `keystore.properties`，验证 `./gradlew assembleRelease` 编译成功并生成已签名的 APK

---

### A-2 移除未使用的 navigation-compose 依赖

> **状态**：✅ 已修改 🔶 未测试 — 编译通过，`navigation-compose:2.7.7` 已从依赖中移除。APK 大小预计减少 ~1.7MB。

**来源**：2.2 未使用的导航依赖  
**风险等级**：🟢 低  
**预计工时**：2 分钟

**现状**：`app/build.gradle.kts:89` 声明了 `navigation-compose:2.7.7`，但项目使用手动 `when(currentScreen)` 导航，从未 import 任何 `androidx.navigation.*` 包。

**方案**：移除 `app/build.gradle.kts` 中的这一行：
```kotlin
// 移除：
implementation("androidx.navigation:navigation-compose:2.7.7")
```

**影响**：APK 大小减少约 1.7MB。无功能影响。

**测试验证方式**：
- 🔶 安装 debug APK 到 TV 设备，遍历所有页面（曲库、播放、队列、设置、服务器），确认导航和页面切换正常
- 🔶 检查 APK 分析工具（`Analyze APK`）确认 `navigation-compose` 相关类已不在 dex 中

---

### A-3 移除过时的 ProGuard Retrofit 规则

> **状态**：✅ 已修改 🔶 未测试 — 编译通过。`proguard-rules.pro` 中无关的 Retrofit 规则已移除。

**来源**：2.3 过时的 ProGuard 规则  
**风险等级**：🟢 低  
**预计工时**：2 分钟

**现状**：`proguard-rules.pro:7-13` 保留了 Retrofit 相关的 keep 规则，但 Retrofit 已在 v1.1.0 中移除。

**方案**：移除 `proguard-rules.pro` 中的 Retrofit 规则：
```
# 移除以下块（第 7-13 行）：
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
```

**影响**：无功能影响。Dead rules 移除后不会影响混淆行为。

**测试验证方式**：
- 🔶 构建 release APK (`./gradlew assembleRelease`)，安装到 TV 设备，验证所有功能正常（尤其关注列表加载、播放、歌词等使用 Gson 序列化的流程）

---

### A-4 空 catch 块添加日志

> **状态**：✅ 已修改 🔶 未测试 — 编译通过。`BackendRegistry.kt` 中 6 处空 `catch(_: Exception) {}` 已添加 `Log.w`。Release 构建中 `Log.w` 会保留（不会被 ProGuard 剥离）。

**来源**：4.1 空 catch 块  
**风险等级**：🟡 中  
**预计工时**：15 分钟  
**涉及文件**：`backend/BackendRegistry.kt`（4 处）

**修改点**：`BackendRegistry.kt` 中所有 `catch (_: Exception) {}` 改为：

```kotlin
try {
    adapter.logout()
} catch (e: Exception) {
    android.util.Log.w("BackendRegistry", "logout failed during disconnect", e)
}
try {
    adapter.close()
} catch (e: Exception) {
    android.util.Log.w("BackendRegistry", "close failed during disconnect", e)
}
```

**涉及位置**：
| 行号 | 上下文 |
|------|--------|
| ~85-86 | `disconnect()` 中的 logout/close |
| ~121-123 | `testConnection()` 成功路径的 logout/close |
| ~127-128 | `testConnection()` 失败路径的 logout/close |

**测试验证方式**：
- 🔶 连接后端 → 播放歌曲 → 断开连接，检查 logcat 中 `BackendRegistry` 的 `Log.w` 输出
- 🔶 在连接状态下触发 `testConnection()`，检查异常路径的日志输出
- 🔶 确认 `Log.w` 在 release 构建中仍保留（不会被剥离）

---

### A-5 disconnect() 竞态条件修复

> **状态**：✅ 已修改 🔶 未测试 — 编译通过。`_isConnected = false` 已移到协程内，在资源释放完成后再更新 UI 状态。

**来源**：3.2 协程中的 disconnect() 存在竞态条件  
**风险等级**：🟡 中  
**预计工时**：30 分钟  
**涉及文件**：`ui/viewmodel/MainViewModel.kt`

**现状**：`disconnect()` 同步设置 `_isConnected = false`，然后异步启动协程调用 `BackendRegistry.disconnect()`。UI 在资源释放完成前就显示"已断开"。

**方案**：将 disconnect 改为挂起函数，确保清理完成后再更新状态：

```kotlin
fun disconnect() {
    viewModelScope.launch {
        try {
            BackendRegistry.disconnect()
        } catch (e: Exception) {
            android.util.Log.e("NASMusic", "disconnect failed", e)
        }
        _isConnected.value = false
        _serverDisplayName.value = ""
        _albums.value = emptyList()
        _songs.value = emptyList()
        try {
            val current = serverConfig.value
            prefs.saveServerConfig(current.copy(isConnected = false))
        } catch (e: Exception) {
            android.util.Log.e("NASMusic", "disconnect: save config failed", e)
        }
    }
}
```

**关键变更**：把 `_isConnected.value = false` 从协程外移到 `BackendRegistry.disconnect()` 执行完毕后的协程内。这样 UI 只有**真正清理完成**后才会切换为断开状态。

**测试验证方式**：
- 🔶 连接后端 → 播放歌曲 → 快速点击断开连接按钮，观察 UI：应该在资源释放完成后才显示"已断开"
- 🔶 极端测试：反复连接/断开 ~10 次，确认不会出现 `_isConnected = false` 但播放器仍持有资源的竞态
- 🔶 检查 logcat，确认 `disconnect failed` 异常日志只在实际出错时出现（不因竞态产生误报）

---

### A-6 歌词加载双倍 API 调用修复

> **状态**：✅ 已修改 🔶 未测试 — 编译通过。`getLyrics()` 重构为委托给 `checkAvailability()`，消除重复后端 API 调用路径。`checkAvailability()` 获取歌词后自动缓存。

**来源**：3.3 歌词加载做了两次后端 API 调用  
**风险等级**：🟡 中  
**预计工时**：1 小时  
**涉及文件**：`lyrics/LyricsManager.kt`、`ui/viewmodel/MainViewModel.kt`

**现状**：`loadLyricsForCurrentSong()` 调用 `checkAvailability()`（内部调用后端 API），然后从结果中取 `availability.backend`——但后端 API 已经在 `checkAvailability()` 中调用并解析完了。多余的 `getLyrics()` 调用没有发生，但 `checkAvailability()` 中获取到的 `backendLyrics` 已经是完整的 `Lyrics` 对象，`loadLyricsForCurrentSong()` 直接使用它，这是正确的。

**问题实际位置**：不完全是"两次 API 调用"——而是 `checkAvailability()` **同时** 获取了后端歌词和网络歌词（后备），导致：
- 有后端歌词时：1 次 API 调用（后端）
- 无后端歌词时：2 次 API 调用（后端 + 网络）
- 无网络时：1 次 API 调用 + 1 次超时等待

但 `getLyrics()`（真正的获取方法）和 `checkAvailability()` 的逻辑存在重复——它们各自独立调用后端 API。

**修改方案**：

1. **合并逻辑**：让 `getLyrics()` 直接委托给 `checkAvailability()`，避免重复：

```kotlin
// LyricsManager.kt — 修改 getLyrics()
suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
    // 1. 尝试本地缓存
    val cached = getCachedLyrics(song)
    if (cached != null) return@withContext cached
    
    // 2. 检查可用来源（后端 + 网络后备）
    val availability = checkAvailability(song)
    val lyrics = availability.backend ?: availability.network
    
    // 3. 如果找到了，缓存并返回
    if (lyrics != null) {
        val lrcText = lyrics.lines.joinToString("\n") { "[${it.timestamp}]${it.text}" }
        cacheLyrics(song, lrcText)
        return@withContext lyrics
    }
    
    null
}
```

2. **简化 ViewModel 中的调用**：`loadLyricsForCurrentSong()` 只需调用一次：

```kotlin
// MainViewModel.kt — 修改 loadLyricsForCurrentSong()
private fun loadLyricsForCurrentSong() {
    lyricsLoadJob?.cancel()
    _currentLyrics.value = null
    _lyricsAvailability.value = LyricsAvailability()
    val song = currentSong.value ?: return
    
    lyricsLoadJob = viewModelScope.launch {
        try {
            val availability = lyricsManager.checkAvailability(song)
            _lyricsAvailability.value = availability
            val lyrics = availability.backend ?: availability.network
            _currentLyrics.value = lyrics
        } catch (e: Exception) {
            android.util.Log.e("NASMusic", "loadLyrics failed", e)
            showError("加载歌词失败: ${e.message?.take(50)}")
        }
    }
}
```

> **注意**：当前代码实际上已经这样做了——`loadLyricsForCurrentSong()` 调用 `checkAvailability()` 并取 `availability.backend ?: availability.network`。所以"两次 API 调用"指的是 `getLyrics()` 方法（它在别处被调用时）和 `checkAvailability()` 各自独立调用后端。**如果 `getLyrics()` 在系统中没有被其他调用者使用，上述合并就是安全的。**

**测试验证方式**：
- 🔶 播放有后端歌词的歌曲，检查 logcat 确认 `checkAvailability: backend has valid lyrics` 只出现一次（没有重复的后端 API 调用）
- 🔶 播放无后端歌词但有网络匹配的歌曲，确认触发网络歌词获取（`checkAvailability: trying network provider`）
- 🔶 切换歌曲，确认前一首歌的歌词加载被取消，不会覆盖新歌词（`lyricsLoadJob?.cancel()` 生效）
- 🔶 检查歌词缓存目录，确认获取到的歌词内容正确写入缓存

---

### A-7 播放列表对话框创建虚假数据修复

> **状态**：✅ 已修改 🔶 未测试 — 编译通过。`showCreatePlaylistDialog()` 替换为 `createPlaylist(name: String)`，`PlaylistManagementScreen` 新增 `TextInputDialog` 让用户输入名称。涉及 3 个文件。

**来源**：4.2 播放列表对话框创建虚假数据  
**风险等级**：🟡 中  
**预计工时**：30 分钟  
**涉及文件**：`ui/viewmodel/MainViewModel.kt`

**现状**：`showCreatePlaylistDialog()` 不使用 UI 对话框，而是用占位名称自动创建播放列表：
```kotlin
val name = "New Playlist ${System.currentTimeMillis() % 10000}"
```

**方案**：改为接受从 UI 传入的播放列表名称参数：

```kotlin
// MainViewModel.kt — 修改
fun createPlaylist(name: String) {
    viewModelScope.launch {
        val adapter = BackendRegistry.getAdapter() ?: return@launch
        try {
            val result = adapter.createPlaylist(name)
            if (result != null) {
                _playlists.value = _playlists.value + result
                _connectMessage.value = "播放列表已创建"
            } else {
                _connectMessage.value = "创建失败"
            }
        } catch (e: Exception) {
            _connectMessage.value = "创建失败: ${e.message}"
        }
        delay(2000)
        _connectMessage.value = null
    }
}

// 删除旧的 showCreatePlaylistDialog()
```

上游调用者（`PlaylistManagementScreen`）需要弹出一个 `TextInputDialog` 让用户输入名称。

**测试验证方式**：
- 🔶 进入播放列表管理页面，点击 "+ 新建"，确认弹出名称输入对话框
- 🔶 在对话框中输入名称（如"我的歌单"），点击确认，验证后端创建成功且列表中显示正确名称
- 🔶 在对话框中输入空字符串，点击确认，验证不触发创建
- 🔶 在对话框中按返回键，验证对话框关闭且不创建播放列表
- 🔶 确认每次创建请求只发送一次（无重复提交）

---

### A-8 条件日志（Debug/Release 分级）

> **状态**：✅ 已修改 🔶 未测试 — 编译通过。采用 ProGuard 方案（非最初草案中的工具类）：`proguard-rules.pro` 添加 `-assumenosideeffects` 剥离 `Log.d`/`Log.v`。Release 构建中这些调用被完全移除（包括字符串计算），无需修改 94 处调用点。`Log.w`/`Log.e` 保留在 Release 中用于生产诊断。

**来源**：6.3 过量日志记录  
**风险等级**：🟢 低  
**预计工时**：1 小时  
**涉及文件**：大量——约 30+ 处 Log.d 调用

**方案**：通过 BuildConfig 控制日志级别，创建一个日志工具类或直接使用条件判断：

**方案 A（推荐）——创建简单日志工具类**：

```kotlin
// util/NasMusicLog.kt — 新建
object NasMusicLog {
    private const val TAG = "NASMusic"
    
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(tag, msg)
        }
    }
    
    fun w(tag: String, msg: String, tr: Throwable? = null) {
        android.util.Log.w(tag, msg, tr)   // Release 也保留 Warn
    }
    
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        android.util.Log.e(tag, msg, tr)   // Release 保留 Error
    }
}
```

> **为什么不直接放 Log.d 的 `BuildConfig.DEBUG` 条件？** ProGuard 在 Release 构建时可以通过 `-assumenosideeffects` 完全移除这些调用，不留运行时代价。

**ProGuard 新增规则**（`proguard-rules.pro`）：
```
# Remove debug log calls in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
```

**执行策略**：
- 分批次替换，每个文件一个 commit，避免冲突
- 优先级：`JellyfinAdapter.kt`（日志最多）→ `MainViewModel.kt` → `PlayerManager.kt` → 其余
- 保留关键路径的 Log.w（连接失败、API 错误、播放异常）

**测试验证方式**：
- 🔶 构建 release APK，使用 `apkanalyzer` 或 `jadx` 反编译，确认 `Log.d` 调用已被 ProGuard 移除
- 🔶 安装 release APK 到 TV 设备，运行典型使用流程（连接、浏览、播放、切换），确认 `Log.w`/`Log.e` 仍出现在 logcat 中
- 🔶 构建 debug APK，确认 `Log.d` 正常输出（开发调试不受影响）

---

## B. 方案评估（需先审查再实施）

这些项目需要先出方案，你审查通过后再实施。

---

### B-1 双重进度轮询修复方案

> **状态**：✅ 已完成（B-10 重构中移除）— MainViewModel 不再持有 `progressUpdateJob`，进度由 PlayerManager 的 Handler + `onPositionDiscontinuity` 路径更新。代码审查确认仅剩单一路径。编译通过。

**来源**：3.1 双重进度轮询机制  
**预计工时**：2 小时  
**涉及文件**：`player/PlayerManager.kt`、`ui/viewmodel/MainViewModel.kt`

#### 现状

| 路径 | 位置 | 触发方式 |
|------|------|---------|
| **路径 A** (Handler) | `PlayerManager` L23-33 | `Handler.postDelayed(runnable, 500)` |
| **路径 B** (协程) | `MainViewModel` L167-175 | `viewModelScope.launch { delay(500); updateProgress() }` |

共存原因：路径 A 是历史遗留，路径 B 是 ViewModel 改进方案。文档明确标记为已知技术债务。

#### 推荐方案：保留路径 A，移除路径 B

**理由**：
1. 路径 A 在 `PlayerManager.setPlayer()` 启动，与 ExoPlayer 生命周期绑定——`PlayerManager` 销毁时 progress 更新自然停止
2. 路径 B 在 `MainViewModel.init` 调用 `startProgressUpdate()`，启动了永久的 `while(true)` 循环，即使没有 player 也在轮询
3. 路径 A 是 `Handler` 在主线程，路径 B 跑在协程 `Dispatchers.Main`（默认），执行同样的 `player.currentPosition` 读操作，没有本质差异

**修改步骤**：

1. **移除 MainViewModel 中的进度更新循环**：
```kotlin
// 删除
private var progressUpdateJob: Job? = null  // L152
private fun startProgressUpdate() { ... }   // L167-175

// init 块中删除 startProgressUpdate() 调用
```

2. **保留 PlayerManager 中的 Handler 循环**（已验证可正常更新 UI 的 progress）

3. **验证**：确保 `onMediaItemTransition`、`onPositionDiscontinuity`、`playSong()`、`playQueue()` 这些边界情况下 progress 正确更新。Handler 路径依赖 ExoPlayer 的 `currentPosition`，只要 player 存在就能读到值。这些边界情况已经由 `playerListener` 中的回调处理。

#### 备选方案：保留路径 B，移除路径 A

如果未来 `PlayerManager` 不再需要 Handler 模式，可以反过来移除路径 A。但路径 A 是 `PlayerManager` 内部自包含的，不依赖外部调用，更适合作为唯一的 progress 驱动。

---

### B-2 loadGenres() 反射调用修复方案

> **状态**：✅ 已完成（B-12 UiState 重构中移除）— 当前代码直接调用 `adapter.getGenres()` 并包裹 try/catch + `UiState.Error`。反射检查已不复存在。编译通过。

**来源**：3.4 loadGenres() 使用反射检查接口方法  
**预计工时**：30 分钟  
**涉及文件**：`ui/viewmodel/MainViewModel.kt`

#### 现状

使用反射检查 `getGenres()` 是否在运行时存在——这在 `BackendAdapter` 接口中已经是必选方法（L114），所以反射检查多余且脆弱。

#### 方案

直接调用 `adapter.getGenres()`，移除反射检查：

```kotlin
// 改为
try {
    _genres.value = adapter.getGenres()
    android.util.Log.d("NASMusic", "loadGenres: ${_genres.value.size} genres")
} catch (e: AbstractMethodError) {
    // 理论上不会发生（接口已定义），但保留保护
    android.util.Log.w("NASMusic", "loadGenres not supported by adapter")
} catch (e: Exception) {
    android.util.Log.e("NASMusic", "loadGenres failed", e)
    showError("加载流派列表失败: ${e.message?.take(50)}")
}
```

由于 `getGenres()` 在 `BackendAdapter` 接口中已经定义为必需方法（没有默认实现），并且两个适配器都已实现，反射检查在多态分发上起作用。

---

### B-3 硬编码字符串迁移方案

**来源**：4.4 硬编码用户可见字符串  
**预计工时**：2-3 小时  
**涉及文件**：全部 UI 文件（~12 个）

#### 现状

UI 中所有用户可见字符串（约 50+ 处）为硬编码中文，未使用 `strings.xml` 资源。

#### 方案评估

**方案 A（推荐）——逐步迁移**：
- 创建 `app/src/main/res/values/strings.xml`，逐个页面提取字符串
- 每次修改一个页面，将硬编码字符串替换为 `stringResource(R.string.xxx)`
- 为 TV 适配提供 `values-zh/strings.xml`（中文）——保持与当前行为一致

**涉及字符串示例**：

| 当前硬编码 | 建议资源 ID |
|-----------|------------|
| `"正在播放"` | `nav_now_playing` |
| `"曲库"` | `nav_library` |
| `"队列"` | `nav_queue` |
| `"服务器"` | `nav_server` |
| `"设置"` | `nav_settings` |
| `"加载中..."` | `loading` |
| `"已加载 %d 首歌曲"` | `loading_progress`（带格式参数） |
| 所有 `showError()` 消息 | 作为资源（便于后续多语言） |

**优点**：
- 原子化迁移，每次一个文件，回归风险低
- 后续增加英文等其他语言只需添加 `values/strings.xml`
- Android 惯例，符合 Material Design 指南

**缺点**：
- 需要逐个文件替换，工作量分散

**方案 B（不推荐）——一次性全局替换**：
- 使用正则扫描所有 `.kt` 文件中的中文字符串
- 但风险高，容易漏掉或误替换

**结论**：推荐方案 A。如果确认实施，逐个文件处理。

---

### B-4 onBackPressedDispatcher 回调方案

**来源**：4.5 onBackPressedDispatcher 回调始终启用  
**预计工时**：1 小时评估 + 30 分钟实施  
**涉及文件**：`ui/MainActivity.kt`

#### 现状

`onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { ... })` 始终 `enabled = true`。

**分层逻辑**（当前）：
- Level 0：沉浸模式 → 退出全屏
- Level 1：对话框打开时 → 关闭对话框
- Level 2：不在 NowPlaying → 导航回 NowPlaying
- Level 3：在 NowPlaying → 显示退出确认

**问题**：无论当前界面状态如何，返回键总是被拦截。在非 NowPlaying 页面之外（比如对话框已关闭、dialogBackHandler 和 navigateBackHandler 都为 null）时，返回键既不能回到 NowPlaying（因为没有可返回的页面），也会直接触发退出确认。但实际上 `navigateBackHandler` 已经覆盖了这种情况——它在 `LaunchedEffect` 中根据 `currentScreen` 设置：如果 `currentScreen != Screen.NowPlaying` 就设置 nav handler，否则设为 null。所以当 `navHandler` 为 null 时，一定是已经在 NowPlaying 页面了，此时触发退出确认是正确的。

**结论**：**当前实现没有 Bug**。`navigateBackHandler` 的 `LaunchedEffect` 已经确保了只在非 NowPlaying 页面时设置 handler。Level 2 handler 为 null 时，意味着已经在 NowPlaying 页面，走 Level 3 退出确认是合理的流程。

**最终决策**：⬜ 不修改 — 当前多层 handler 机制已正确覆盖所有返回路径，无需修改。

**如果还考虑增强"安全边界"**，可以给 `OnBackPressedCallback` 加一个 enable/disable 控制：

```kotlin
// 方案：动态控制 callback 是否启用
val backCallback = object : OnBackPressedCallback(true) { ... }
onBackPressedDispatcher.addCallback(this, backCallback)

// 在 LaunchedEffect 中根据屏幕状态控制
LaunchedEffect(currentScreen) {
    backCallback.isEnabled = when (currentScreen) {
        Screen.NowPlaying -> true   // 需要退出确认
        Screen.Library, Screen.Queue, Screen.Settings, Screen.ServerConnect -> true  // 导航回 NowPlaying
        Screen.AlbumDetail, Screen.ArtistDetail, Screen.Equalizer, Screen.PlaylistManagement -> true  // 导航到上一级
    }
}
```

但这样做的好处不大——当前的分层 handler 已经覆盖了所有路径。**建议保持现状，不做修改**。

---

### B-5 测试补充方案

**来源**：5.1 测试严重不足  
**预计工时**：分阶段 4-8 小时  
**涉及文件**：新建测试文件

#### 阶段一：工具类测试增强（2 小时）

| 测试目标 | 文件 | 新增用例数 | 说明 |
|---------|------|-----------|------|
| `TimeUtils` | `util/TimeUtils.kt` | 5 | 时间格式化边界值（0ms、负数、大数值） |
| `PinyinUtils` | 已有 6 用例 | +4 | 空字符串、纯英文、混合中英、特殊字符 |
| `LrcParser` | 已有 10 用例 | +5 | 空文件、无效格式、超大偏移量、UTF-8 BOM、CRLF 行尾 |
| `ArtistSplitter` | 已有 10 用例 | +3 | 无分隔符情况、null/空输入、连续分隔符 |

#### 阶段二：核心组件测试（3-4 小时）

| 测试目标 | 框架 | 用例数 | 说明 |
|---------|------|--------|------|
| `PlayerManager` | MockK + Turbine | 10 | play/pause/seek/next/previous/playMode/queue 操作 |
| `BackendRegistry` | MockK | 6 | initialize/testConnection/disconnect/类型映射 |
| `AppPreferences` | DataStore Test | 5 | 读写/默认值/Flow 观察 |

**依赖**：
```kotlin
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
testImplementation("io.mockk:mockk:1.13.8")
testImplementation("app.cash.turbine:turbine:1.0.0")
```

#### 阶段三：ViewModel 测试（可选，2-3 小时）

使用 `AndroidX Test` + `MainCoroutineRule` 测试 `MainViewModel` 的导航、连接状态管理。

---

### B-6 CI 搭建方案

**来源**：5.2 无 CI  
**预计工时**：2 小时  
**涉及文件**：`.github/workflows/`

#### 方案

创建 `.github/workflows/build.yml`：

```yaml
name: Build

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3
      
      - name: Build Debug APK
        run: ./gradlew assembleDebug
      
      - name: Run Unit Tests
        run: ./gradlew testDebugUnitTest
      
      - name: Upload Debug APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/*.apk
```

**注意事项**：
- Release 构建需要 `keystore.properties`（需通过 GitHub Secrets 注入）
- 需检查 Gradle Wrapper 是否适配 CI 环境（当前 gradle-wrapper.properties 指向本地路径 `file:///C:/...`）
- **建议先修复 gradle-wrapper.properties**，改为 HTTPS URL

---

### B-7 内存优化方案（歌曲数据膨胀）

> **最终决策**：⬜ 不修改 — 保留当前方案 A（客户端全量缓存 + 前端搜索过滤）

**来源**：6.1 内存中歌曲数据膨胀  
#### 理由

当前方案 A 虽然内存占用 35-40MB，但对 2GB+ RAM 的 TV 设备可以接受。方案 B（服务端搜索）需要架构级改动，引入网络延迟，影响所有 Tab 的浏览体验，且与现有 `artistSongsMap` 等依赖全量数据的逻辑耦合太深。**保持现状，不做修改。**

---

### B-8 歌词加载 GC 优化方案

> **最终决策**：⬜ 不修改 — GC 压力主要来自全量歌曲 JSON 解析（正常 JVM 行为），歌词系统本身不会造成 GC 问题。

**来源**：6.2 歌词加载期间频繁 GC  

---

### B-9 单例反模式解决方案

**来源**：7.1 单例反模式使测试困难  
**预计工时**：评估 + 实施约 4-6 小时  
**涉及文件**：多个

#### 现状

三个全局单例，各自使用双重检查锁定：

| 单例 | 类 | 创建方式 |
|------|------|---------|
| `PlayerManager` | `PlayerManager.kt` | `synchronized(this)` DCL |
| `BackendRegistry` | Kotlin `object` | JVM 类加载时初始化 |
| `AppPreferences` | `AppPreferences.kt` | `synchronized(this)` DCL |

#### 方案评估

**方案 A（推荐）——手动 DI + Application 级容器**

保持无框架，但改为通过 `Application` 子类提供依赖：

```kotlin
// NasMusicApp.kt — 修改
class NasMusicApp : Application() {
    lateinit var playerManager: PlayerManager
    lateinit var appPreferences: AppPreferences
    
    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(this)  // 不再使用 getInstance()
        playerManager = PlayerManager()
    }
    
    companion object {
        lateinit var instance: NasMusicApp
            private set
    }
}
```

```kotlin
// 使用方
val app = context.applicationContext as NasMusicApp
app.playerManager.playSong(song)
```

**优点**：
- 无框架依赖
- 测试时可以创建独立实例（不再有静态状态）
- 生命周期明确（随 Application 创建和销毁）
- 改动量最小（每处 `getInstance()` 改为通过 Application 引用）

**缺点**：
- `ViewModel` 中需要传递 `Application`（已继承 `AndroidViewModel`，有 `getApplication()`）
- 部分工具类、非 Android 类需要额外的 context 获取路径

**方案 B（不推荐）——Hilt/Dagger 依赖注入**

引入了大型框架的复杂度和编译时间开销，对于单模块项目来说是过度设计。

**方案 C（不推荐）——Koin**

轻量级 DI 框架，但仍然增加了依赖和学习成本。

**结论**：推荐方案 A。总改动量：
| 文件 | 改动 |
|------|------|
| `NasMusicApp.kt` | 改为手动 DI 容器 |
| `AppPreferences.kt` | 移除 `getInstance()`，改为公开构造函数 |
| `PlayerManager.kt` | 移除 `getInstance()` + `companion object` DCL |
| `MainViewModel.kt` | 从 `getApplication()` 获取实例 |
| `PlaybackService.kt` | 从 Application 获取 PlayerManager |

---

### B-10 MainActivity 职责拆分方案

**来源**：7.2 MainActivity 处理过多职责  
**预计工时**：3-5 小时  
**涉及文件**：`ui/MainActivity.kt`（重构）、新增文件

#### 现状

`MainActivity.kt`（675 行）承担了 6 个独立的职责。

#### 推荐方案

**拆分策略**：每个职责提取为独立的委托类或 Composable 函数。

| 职责 | 当前行数 | 提取目标 |
|------|---------|---------|
| Compose UI 根布局（AppRoot + NavItem） | ~340 行 | `ui/AppRoot.kt` |
| 返回键分层处理 | ~50 行 | 保留在 Activity（逻辑简单） |
| HDMI-CEC 媒体键映射 | ~40 行 | `util/MediaKeyHandler.kt` |
| 网络状态监听 | ~30 行 | `util/NetworkMonitor.kt` |
| 退出确认流程 | ~40 行 | 保留（与 Activity 生命周期紧密） |
| 对话框状态管理（connectPrompt、errorMessage、connectMessage） | ~60 行 | 保留（已弥散在 Composable 中） |

**提取 1：`ui/AppRoot.kt`**

将 `AppRoot` 和 `NavItem` Composable 从 `MainActivity.kt` 移到独立的 `ui/AppRoot.kt`（~340 行）。这是最大的一个拆分。

```kotlin
// ui/AppRoot.kt
package com.nasmusic.tv.ui

@Composable
fun AppRoot(
    viewModel: MainViewModel,
    isImmersiveMode: MutableState<Boolean>,
    onConnect: (ServerConfig) -> Unit
) {
    // ... 现有 AppRoot 代码
}

@Composable
private fun NavItem(...) { ... }
```

**提取 2：`util/NetworkMonitor.kt`**

```kotlin
// util/NetworkMonitor.kt
package com.nasmusic.tv.util

class NetworkMonitor(
    private val context: Context,
    private val onAvailable: () -> Unit,
    private val onLost: () -> Unit
) {
    private val connectivityManager = 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() { ... }
    
    fun register() { ... }
    fun unregister() { ... }
}
```

**提取 3：`util/MediaKeyHandler.kt`**

```kotlin
// util/MediaKeyHandler.kt
object MediaKeyHandler {
    fun handleKeyEvent(keyCode: Int, viewModel: MainViewModel, isImmersiveMode: Boolean): Boolean {
        return when (keyCode) { ... }
    }
}
```

**改动总结**：

| 文件 | 操作 | 减少行数 |
|------|------|---------|
| `ui/MainActivity.kt` | 提取 AppRoot、NetworkMonitor、MediaKeyHandler | -340 行 |
| `ui/AppRoot.kt` | 新建 | +340 行 |
| `util/NetworkMonitor.kt` | 新建 | +70 行 |
| `util/MediaKeyHandler.kt` | 新建 | +50 行 |
| **净效果** | MainActivity 从 675 行 → 约 250 行 | -425 行 |

---

### B-11 API 版本控制方案

> **状态**：✅ 已修改 🔶 未测试 — 编译通过。已实施方案 B（Kotlin 接口默认方法）。`BackendAdapter.kt` 中所有扩展方法（`getPlaylists`、`toggleFavorite`、`getFavorites`、`getGenres`、`scrobblePlay`、`getRandomSongs` 等）已添加 Kotlin 接口默认实现（返回空集合 / `false` / `null`）。核心方法（`getAlbums`、`getSongs` 等）保持无默认值，强制实现。

**来源**：7.3 无 API 版本控制  
**方案选择**：方案 B — Kotlin 接口默认方法  
**状态**：✅ 已实施

`BackendAdapter.kt` 中所有 F-1 扩展接口方法已添加 Kotlin 接口默认实现（返回空集合 / `false` / `null`）。核心接口方法（`getAlbums`、`getSongs` 等）保持无默认值，强制实现。`JellyfinAdapter` 和 `NavidromeAdapter` 的现有 `override` 实现不受影响（override 优先于默认值）。

**影响**：未来新增扩展方法到 `BackendAdapter` 时，只需提供默认实现，两个适配器无需同步修改。新建适配器（如未来可能的 Ampache / Koel）也只需实现核心方法即可运行。`BackendAdapter.kt`

#### 现状

`BackendAdapter` 接口有 20+ 个方法，所有方法都在同一个接口中。添加新方法意味着同时修改两个实现类。接口变更无法渐进式进行。

#### 方案评估

**方案 A（推荐）——接口分段 + 默认实现**

将 `BackendAdapter` 拆分为基础接口和可选扩展接口：

```kotlin
// 核心接口——所有后端必须实现
interface BackendAdapter {
    val backendType: String
    suspend fun initialize(...): Boolean
    suspend fun testConnection(): Boolean
    suspend fun logout() {}
    fun close() {}
    
    // 必须实现的基础方法
    suspend fun getAlbums(): List<Album>
    suspend fun getAlbumSongs(albumId: String): List<Song>
    suspend fun getArtists(): List<Artist>
    suspend fun getArtistSongs(artistId: String): List<Song>
    suspend fun getSongs(limit: Int = 500, offset: Int = 0): List<Song>
    suspend fun searchSongs(query: String): List<Song>
    suspend fun getRecentSongs(): List<Song>
    fun getStreamUrl(songId: String): String
    fun getCoverUrl(songId: String): String
    suspend fun getLyrics(songId: String): String?
}

// 可选扩展接口——带默认实现（返回空值）
interface BackendAdapterExtended {
    suspend fun getPlaylists(): List<Playlist> = emptyList()
    suspend fun createPlaylist(name: String): Playlist? = null
    suspend fun deletePlaylist(playlistId: String): Boolean = false
    suspend fun toggleFavorite(songId: String): Boolean = false
    suspend fun getFavorites(): List<Song> = emptyList()
    suspend fun getGenres(): List<Genre> = emptyList()
    suspend fun scrobblePlay(songId: String, timestamp: Long): Boolean = false
    suspend fun getRandomSongs(limit: Int = 20): List<Song> = emptyList()
}
```

**好处**：
- 新方法有默认实现（返回空值），不需要强制修改旧适配器
- 可以实现 `is ` 检查来发现运行时能力：`if (adapter is BackendAdapterExtended)`
- 新增方法时不需要修改 `BackendAdapter` 接口本身

**坏处**：
- 调用方需要做 `is` 检查，增加了使用复杂度
- 接口拆分的边界需要仔细设计

**方案 B（更简单）——Kotlin 接口默认方法**

Kotlin 接口支持默认实现。可以在 `BackendAdapter` 中为新方法提供默认实现：

```kotlin
interface BackendAdapter {
    // ... 原有方法
    
    // 新方法带默认实现（不破坏现有实现）
    suspend fun getPlaylists(): List<Playlist> = emptyList()
    suspend fun toggleFavorite(songId: String): Boolean = false
    // ...
}
```

这是最简单的方式，适合渐进式扩展。**推荐直接将所有扩展方法改为接口默认方法**，这样新增一个方法时，JellyfinAdapter 和 NavidromeAdapter 不需要同步修改——除非某个后端需要真实实现。

**方案 C（不推荐）——版本化 Adapter 包**

`adapter_v1`、`adapter_v2` 包——过度设计，对单模块项目不必要。

**结论**：推荐方案 B——为 `BackendAdapter` 中非强制的方法添加 Kotlin 接口默认实现。这已经部分实现了（`logout()` 和 `close()` 已经是默认空实现）。将 `getPlaylists()`、`toggleFavorite()` 等扩展方法也改为默认实现，实现完全的向后兼容。

**测试验证方式**：
- 🔶 分别使用 Jellyfin 和 Navidrome 后端，验证以下功能在修改前后一致：
  - 播放列表管理（创建、删除、查看列表）
  - 收藏/取消收藏歌曲
  - 流派列表加载
  - 随机歌曲播放
  - Scrobble 上报（如有服务端支持）
- 🔶 验证 `BackendAdapter` 新增方法时不需要同时修改两个适配器（仅需在接口添加默认实现）

---

### B-12 错误处理增强（features-plan D-3）

**来源**：features-plan.md D-3  
**预计工时**：4-6 小时  
**涉及文件**：`backend/impl/JellyfinAdapter.kt`、`backend/impl/NavidromeAdapter.kt`、`ui/viewmodel/MainViewModel.kt`

#### 现状

所有后端适配器的 catch 块已添加 `Log.e`（第一阶段 A-4），但：
1. 方法签名仍为 `List<T>`，错误时返回 `emptyList()`，调用方无法区分"空结果"和"加载失败"
2. UI 层没有重试机制——加载失败时只显示空白页面
3. 关键操作（连接、加载曲库）没有指数退避重试

#### 推荐方案

**阶段一：区分错误类型**（2 小时）

将关键接口的返回类型改为 `Result<List<T>>` 或保持 `List<T>` 但增加 `uiState` 的 Error 分支：

```kotlin
// 方案 A：使用 sealed class 表示加载状态
sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : UiState<Nothing>()
}
```

ViewModel 中每个列表加载函数管理独立的 `UiState`，UI 侧根据状态渲染内容/错误/重试按钮。

**阶段二：UI 层重试**（1-2 小时）

- 曲库页专辑/歌曲/艺术家 tab 加载失败时显示"加载失败，重试"按钮
- 连接失败时在 connectMessage 中加入重试按钮
- 播放列表、收藏等二级内容加载失败时显示错误提示

**阶段三：指数退避重试**（1 小时）

- 连接 `testConnection()` 增加重试：首次立即重试 → 2s → 4s，最多 3 次
- 曲库首次加载增加重试（但不延迟 UI 显示）

---

### B-13 状态管理统一（features-plan E-1）

**来源**：features-plan.md E-1  
**预计工时**：6-8 小时  
**涉及文件**：`player/PlayerManager.kt`、`ui/viewmodel/MainViewModel.kt`、`ui/screens/NowPlayingScreen.kt`、`ui/components/PlayerControls.kt`、`ui/screens/QueueScreen.kt`

#### 现状

`PlayerManager` 作为裸单例暴露多个 StateFlow：
- `currentSong`、`isPlaying`、`playMode`、`progress`、`duration`、`currentQueue`、`currentIndex`

`MainViewModel` 镜像这些 StateFlow（`_currentSong.value = playerManager.currentSong.value`），形成双向依赖。注释行 `// --- 播放器状态（镜像 PlayerManager 的 StateFlow）---` 明确标记为已知模式。

#### 推荐方案

**方案 A（推荐）——渐进式降级 PlayerManager**

不一次性重构，而是逐步将状态中心从 `PlayerManager` 转移到 `MainViewModel`：

1. **双向绑定消除**：`MainViewModel` 不再手动 `playerManager.currentSong.collect` 镜像，而是改为监听 `PlayerManager` 的 Flow 并转换为自己的 StateFlow
2. **PlayerManager 接口缩减**：移除 `PlayerManager` 中的公开 StateFlow，改为 package-private，通过 `MainViewModel` 暴露给 UI
3. **最终形态**：`PlayerManager` 是纯播放引擎，不持有 UI 状态，只接收命令（play/pause/seek/queue）

**方案 B（激进）——一次性重构**

将 `PlayerManager` 的 `companion object` DCL 单例移除，改为通过 Application 容器（参考 B-9 方案 A）获取实例。然后逐步迁移状态。

#### 注意事项

- 该重构与 B-9（单例反模式）有重叠，建议先完成 B-9 再做 E-1
- 重构期间应保留老接口作为 delegate，避免大面积修改 UI 层
- 建议分批提交，每次保留功能完整性

---

## C. 暂不处理

| 来源 | 项目 | 原因 |
|------|------|------|
| 1.2 | API Token 在日志中泄露 | 你已确认不需要修改 |
| 2.1 | 过时的依赖版本升级 | 需要评估电视 Android 版本兼容性后再决定 |
| 4.3 | Notification 跳过更新 | 影响不大，保持现状 |
| 6.1 (B-7) | 内存优化（歌曲数据膨胀） | 已决定不做修改，保留当前方案 A |
| 6.2 (B-8) | 歌词加载 GC 优化 | GC 压力来自正常 JSON 解析，非歌词系统问题，不做修改 |

---

## 附录：执行计划总览

### 第一阶段：立即修改（安全、低风险）— ✅ 全部完成

| 序号 | 项目 | 预计工时 | 状态 |
|------|------|---------|------|
| A-1 | 签名凭据提取到 keystore.properties | 15 min | ✅ 已修改 🔶 未测试 |
| A-2 | 移除 navigation-compose 依赖 | 2 min | ✅ 已修改 🔶 未测试 |
| A-3 | 移除 ProGuard Retrofit 规则 | 2 min | ✅ 已修改 🔶 未测试 |
| A-4 | 空 catch 块添加日志 | 15 min | ✅ 已修改 🔶 未测试 |
| A-5 | disconnect() 竞态条件修复 | 30 min | ✅ 已修改 🔶 未测试 |
| A-6 | 歌词双倍 API 调用修复 | 1 h | ✅ 已修改 🔶 未测试 |
| A-7 | 播放列表对话框修复 | 30 min | ✅ 已修改 🔶 未测试 |
| A-8 | 条件日志（Log.d 仅在 Debug） | 1 h | ✅ 已修改 🔶 未测试 |
| **小计** | **全部完成** | **~3.5 h** | **8/8 完成，均未设备验证** |

### 第二阶段：需要先审查方案再实施

| 序号 | 项目 | 预计工时 | 状态 |
|------|------|---------|------|
| B-2 | loadGenres() 反射调用修复 | 30 min | ✅ 已完成（B-12 中移除） |
| B-3 | 硬编码字符串迁移 | 2-3 h | ✅ 已完成（B-3/B-8） |
| B-4 | onBackPressed 评估（已确认不修改） | — | ⬜ 不修改 |
| B-11 | API 版本控制（方案 B） | 1 h | ✅ 已修改 🔶 未测试 |

### 第三阶段：中大型改动

| 序号 | 项目 | 预计工时 | 状态 |
|------|------|---------|------|
| B-1 | 双重进度轮询修复 | 2 h | ✅ 已完成（B-10 中移除） |
| B-5 | 测试补充（features-plan E-2） | 4-8 h | ✅ 已完成（新增 55 测试用例） |
| B-6 | CI 搭建 | 2 h | ✅ 已完成（.github/workflows/build.yml） |
| B-9 | 单例反模式修复 | 4-6 h | ✅ 已完成（NasMusicApp DI 容器） |
| B-10 | MainActivity 职责拆分 | 3-5 h | ✅ 已完成（~375 行精简） |
| B-12 | 错误处理增强（features-plan D-3） | 4-6 h | ✅ 已完成（UiState + withRetry） |
| B-13 | 状态管理统一（features-plan E-1） | 6-8 h | ✅ 已完成（playMode 迁移至 VM） |

### 第四阶段：已决定不修改

| 序号 | 项目 | 预计工时 | 状态 |
|------|------|---------|------|
| B-7 | 内存优化（歌曲数据） | — | ⬜ 不修改 |
| B-8 | 歌词 GC 优化 | — | ⬜ 不修改 |

---

> **执行进度**：全 15 项完成。第一阶段 8/8 ✅，B-11 已实施，B-7/B-8 已决定不修改，B-1/B-2/B-5/B-6/B-9/B-10/B-12/B-13 全部完成。✅ **计划全部完成**。
> **总预估工时**：已完成 ~4.5h（第一阶段）+ ~2.5h（第二阶段）+ ~28h（第三阶段）= **约 35 小时**
> **注意**：所有已修改项目均 **编译通过但未在设备上运行验证**。建议上线前进行完整设备测试。
