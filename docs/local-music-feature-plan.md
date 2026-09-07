# 本地音乐功能开发方案

## 一、需求概述

支持 Android TV 设备播放本地音乐文件，作为与 NAS/网络并列的独立数据源集成到现有曲库中：

1. **USB 音乐 U 盘**：插入 U 盘后自动扫描音乐文件
2. **设备本地存储**：扫描内置存储和外部 SD 卡的音乐文件
3. **本地音乐搜索**：参与跨源搜索（NAS / 网络 / 百度 / Jamendo / 本地）
4. **本地音乐播放**：支持播放、暂停、上/下一首、队列管理
5. **合并管理**：同名艺术家/专辑自动合并 NAS 与本地的歌曲
6. **索引持久化**：Room 数据库存储索引，启动时后台增量更新，搜索时只查询索引
7. **扩展功能**：本地 LRC 歌词、专辑封面提取、播放统计（整合现有系统）、收藏（整合现有系统）

**核心原则**：本地音乐是独立数据源，**不实现 `BackendAdapter` 接口**（该接口是 NAS 后端抽象）。通过 `LocalMusicRepository` 提供 API，由 `MainViewModel` 负责与 NAS 数据合并。

---

## 二、架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                      UI Layer                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   Library   │  │  NowPlaying │  │   Queue     │         │
│  │   Screen    │  │    Screen   │  │   Screen    │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
│        │                                                      │
│        ▼                                                      │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  SearchTab (来源: NAS/网络/百度/Jamendo/本地)         │   │
│  │  SongsTab  (合并后歌曲，SourceBadge 自动标识来源)     │   │
│  │  AlbumsTab (合并后专辑：同名专辑聚合 NAS+本地歌曲)    │   │
│  │  ArtistsTab(合并后艺术家：同名艺术家聚合 NAS+本地歌曲) │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                     ViewModel Layer                         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                  MainViewModel                      │   │
│  │  - localSongs: StateFlow<List<Song>>                │   │
│  │  - mergedAlbums / mergedArtists (合并 NAS + 本地)     │   │
│  │  - searchLocalMusic() → LocalMusicRepository        │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                      Data Layer                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ LocalMusic   │  │ MusicScanner  │  │ StorageMonitor│     │
│  │ Repository   │  │ (MediaStore + │  │ (USB 插拔监听) │     │
│  │ (Room DAO)   │  │  文件扫描)    │  │               │     │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

**数据流**：

```
启动 → LocalMusicRepository.loadFromCache() → MainViewModel._localSongs
     → mergeAlbums(nasAlbums, localAlbums) → LibraryScreen.AlbumsTab
     → mergeArtists(nasArtists, localArtists) → LibraryScreen.ArtistsTab
     → 后台 incrementalScan() → 更新 _localSongs → 重新 merge

搜索 → SearchAggregator.search()
     ├── NAS 搜索（BackendAdapter）
     ├── 网络搜索（MetingApiService）
     ├── 百度搜索（BaiduPanApi）
     ├── Jamendo 搜索（JamendoService）
     └── 本地搜索（LocalMusicRepository.search() — 查询 Room 索引）
```

---

## 三、数据模型扩展

### 3.1 MusicSourceType 枚举追加 LOCAL

**修改文件**：`app/src/main/java/com/nasmusic/tv/data/model/SourceIdentifier.kt`

```kotlin
enum class MusicSourceType(
    val displayName: String,
    val icon: String,
    val color: Color
) {
    NAS("NAS", "🎵", Color(0xFF60A5FA)),
    NETWORK_MUSIC("网络", "🌐", Color(0xFF34D399)),
    BAIDU_PAN("百度", "☁", Color(0xFFFBBF24)),
    RADIO("电台", "📻", Color(0xFFA78BFA)),
    JAMENDO("Jamendo", "♪", Color(0xFFF472B6)),
    WEATHER_RADIO("天气电台", "🌤", Color(0xFF67E8F9)),
    LOCAL("本地", "📱", Color(0xFFFB923C));   // 新增

    companion object {
        val DEFAULT_SEARCH_SOURCES: Set<MusicSourceType> = setOf(
            NAS,
            NETWORK_MUSIC,
            BAIDU_PAN,
            JAMENDO,
            LOCAL   // 新增：本地音乐参与默认搜索
        )
    }
}
```

### 3.2 sourceType 扩展属性追加 LOCAL 分支

**修改文件**：`app/src/main/java/com/nasmusic/tv/data/model/SourceIdentifier.kt`

```kotlin
val Song.sourceType: MusicSourceType
    get() = when {
        // 新增：本地音乐优先识别
        isLocalSong -> MusicSourceType.LOCAL
        !isNetworkSong -> MusicSourceType.NAS
        networkSource == RadioStation.SOURCE_ID -> MusicSourceType.RADIO
        networkSource == "baidu" -> MusicSourceType.BAIDU_PAN
        networkSource == "weather" -> MusicSourceType.WEATHER_RADIO
        networkSource == "jamendo" -> MusicSourceType.JAMENDO
        isNetworkSong -> MusicSourceType.NETWORK_MUSIC
        else -> MusicSourceType.NETWORK_MUSIC
    }
```

### 3.3 RankedSong.SOURCE_PRIORITY 追加 LOCAL

**修改文件**：`app/src/main/java/com/nasmusic/tv/data/model/SourceIdentifier.kt`

```kotlin
private val SOURCE_PRIORITY = mapOf(
    MusicSourceType.LOCAL to 0,          // 新增：本地音乐最高优先级
    MusicSourceType.NAS to 1,            // 原 0 → 1
    MusicSourceType.NETWORK_MUSIC to 2,  // 原 1 → 2
    MusicSourceType.BAIDU_PAN to 3,       // 原 2 → 3
    MusicSourceType.JAMENDO to 4,        // 原 3 → 4
    MusicSourceType.RADIO to 5,           // 原 4 → 5
    MusicSourceType.WEATHER_RADIO to 6   // 原 5 → 6
)
```

### 3.4 Song 数据模型扩展

**修改文件**：`app/src/main/java/com/nasmusic/tv/data/model/Song.kt`

复用现有 `path` 字段（百度网盘已用，语义为"文件绝对路径"，本地音乐同语义不冲突）。新增 `isLocalSong` 和 `storageType` 两个字段：

```kotlin
data class Song(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val album: String = "",
    val albumId: String? = null,
    val coverUrl: String? = null,
    val streamUrl: String? = null,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val year: Int? = null,
    val genre: String? = null,
    val bitrate: Int = 0,
    // 网络歌曲扩展字段
    val isNetworkSong: Boolean = false,
    val networkSource: String? = null,
    val networkId: String? = null,
    // 文件绝对路径（百度网盘 / 本地音乐共用）
    val path: String? = null,
    // 新增：本地音乐标识
    val isLocalSong: Boolean = false,
    val storageType: String? = null    // "INTERNAL" / "EXTERNAL" / "USB"
)
```

**说明**：
- `path` 字段百度网盘和本地音乐共用，语义都是"文件绝对路径"
- `isLocalSong = true` 时 `path` 为本地文件路径，`streamUrl` 为 `file://` URI
- **不新增** `isFavorite` / `playCount` / `lastPlayTime` 字段——`UnifiedSongRow` 已通过 `isFavorited` 参数接收收藏状态，播放统计由 `AppPreferences.recordPlay(songId)` 统一管理

### 3.5 本地音乐专用数据类

**新增文件**：`app/src/main/java/com/nasmusic/tv/data/model/LocalMusic.kt`

```kotlin
package com.nasmusic.tv.data.model

/** 存储类型 */
enum class StorageType {
    INTERNAL,   // 内置存储
    EXTERNAL,   // 外部 SD 卡
    USB,        // USB 存储
    UNKNOWN
}

/** 扫描结果（增量扫描返回） */
data class ScanResult(
    val newSongs: List<Song>,
    val deletedPaths: List<String>,
    val updatedSongs: List<Song>
) {
    fun hasChanges(): Boolean = newSongs.isNotEmpty() || deletedPaths.isNotEmpty() || updatedSongs.isNotEmpty()
}

/** 存储设备信息 */
data class StorageDevice(
    val path: String,
    val name: String,
    val type: StorageType,
    val isMounted: Boolean,
    val availableSpace: Long
)
```

---

## 四、权限配置

### 4.1 AndroidManifest.xml

**修改文件**：`app/src/main/AndroidManifest.xml`

```xml
<!-- 本地音乐权限 -->
<!-- Android 13+ 分区存储：只读音频文件 -->
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<!-- Android 12 及以下：读外部存储 -->
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<!-- Android 9 及以下：写外部存储（修改元数据用，本方案只读不需要，但保留兼容） -->
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />

<!-- USB 设备特性（可选，TV 设备可能无 USB 口） -->
<uses-feature
    android:name="android.hardware.usb.host"
    android:required="false" />
```

**注意**：`MOUNT_UNMOUNT_FILESYSTEMS` 是系统权限，普通应用无法获取。USB 挂载事件通过 `Intent.ACTION_MEDIA_MOUNTED` 广播监听即可。

### 4.2 运行时权限请求

**新增文件**：`app/src/main/java/com/nasmusic/tv/util/PermissionHelper.kt`

```kotlin
object PermissionHelper {
    fun hasLocalMusicPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getLocalMusicPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
}
```

---

## 五、音乐扫描器

**新增文件**：`app/src/main/java/com/nasmusic/tv/backend/local/MusicScanner.kt`

```kotlin
class MusicScanner(private val context: Context) {

    companion object {
        private const val TAG = "MusicScanner"
        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "m4a", "ogg", "wav", "aac", "wma")
    }

    /**
     * 扫描所有可用卷上的音乐文件（通过 MediaStore）
     * Android 10+ 不再返回 DATA 列的文件路径，改用 content URI
     */
    suspend fun scanAllMusic(): List<ScannedSong> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<ScannedSong>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.RELATIVE_PATH,   // Android 10+
            MediaStore.Audio.Media.VOLUME_NAME
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            // 列索引（兼容旧版 Android 无 RELATIVE_PATH 的情况）
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val volumeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME)
            val relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)  // 可能 -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val volumeName = cursor.getString(volumeCol) ?: ""
                val storageType = resolveStorageType(volumeName)

                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )

                songs.add(
                    ScannedSong(
                        mediaStoreId = id,
                        title = cursor.getString(titleCol) ?: "Unknown",
                        artist = cursor.getString(artistCol) ?: "Unknown",
                        album = cursor.getString(albumCol) ?: "Unknown",
                        albumId = cursor.getLong(albumIdCol),
                        duration = cursor.getLong(durationCol),
                        size = cursor.getLong(sizeCol),
                        dateAdded = cursor.getLong(dateAddedCol),
                        mimeType = cursor.getString(mimeCol) ?: "",
                        contentUri = uri,
                        volumeName = volumeName,
                        storageType = storageType
                    )
                )
            }
        }

        AppLog.d(TAG, "Scanned ${songs.size} songs via MediaStore")
        songs
    }

    /**
     * 扫描指定路径（USB 设备挂载点）
     * 使用文件系统遍历 + MediaMetadataRetriever 提取元数据
     */
    suspend fun scanPath(rootPath: String, storageType: StorageType = StorageType.USB): List<ScannedSong> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<ScannedSong>()
            val root = File(rootPath)
            if (!root.exists() || !root.isDirectory) return@withContext songs

            root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                .forEach { file ->
                    val song = scanFile(file, storageType)
                    if (song != null) songs.add(song)
                }
            songs
        }

    private suspend fun scanFile(file: File, storageType: StorageType): ScannedSong? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()

            ScannedSong(
                mediaStoreId = file.absolutePath.hashCode().toLong(),
                title = title,
                artist = artist,
                album = album,
                albumId = 0L,
                duration = duration,
                size = file.length(),
                dateAdded = file.lastModified() / 1000,
                mimeType = guessMimeType(file.extension),
                contentUri = Uri.fromFile(file),
                volumeName = "",
                storageType = storageType
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to scan ${file.absolutePath}: ${e.message}", e)
            null
        }
    }

    private fun resolveStorageType(volumeName: String): StorageType = when {
        volumeName.contains("usb", ignoreCase = true) -> StorageType.USB
        volumeName.contains("sd", ignoreCase = true) -> StorageType.EXTERNAL
        else -> StorageType.INTERNAL
    }

    private fun guessMimeType(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "wma" -> "audio/x-ms-wma"
        else -> "audio/*"
    }
}

/** 扫描器输出的中间数据结构（不直接给 UI 用） */
data class ScannedSong(
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val mimeType: String,
    val contentUri: Uri,
    val volumeName: String,
    val storageType: StorageType
)
```

---

## 六、索引持久化（Room）

### 6.1 Entity 定义

**新增文件**：`app/src/main/java/com/nasmusic/tv/backend/local/db/LocalSongEntity.kt`

```kotlin
@Entity(tableName = "local_songs", indices = [Index("path", unique = true)])
data class LocalSongEntity(
    @PrimaryKey val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val mimeType: String,
    val contentUri: String,       // 序列化 URI
    val volumeName: String,
    val storageType: String,      // StorageType.name
    val path: String,             // 文件绝对路径（用于 LRC 查找 / streamUrl）
    val lastModified: Long,       // 用于增量扫描判断文件变更
    val coverPath: String? = null // 提取的封面缓存路径（可选）
)
```

### 6.2 DAO 接口

**新增文件**：`app/src/main/java/com/nasmusic/tv/backend/local/db/LocalMusicDao.kt`

```kotlin
@Dao
interface LocalMusicDao {
    @Query("SELECT * FROM local_songs")
    suspend fun getAllSongs(): List<LocalSongEntity>

    @Query("SELECT path FROM local_songs")
    suspend fun getAllPaths(): List<String>

    @Query("SELECT * FROM local_songs WHERE mediaStoreId = :id")
    suspend fun getSongById(id: Long): LocalSongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<LocalSongEntity>)

    @Query("DELETE FROM local_songs WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM local_songs")
    suspend fun deleteAll()

    @Query("DELETE FROM local_songs WHERE storageType = :storageType")
    suspend fun deleteByStorageType(storageType: String)

    /**
     * 搜索：标题/艺术家/专辑任意匹配
     * 用 LIKE 实现简单子串匹配，配合 FTS 可优化（暂不引入）
     */
    @Query("""
        SELECT * FROM local_songs
        WHERE title LIKE '%' || :query || '%'
           OR artist LIKE '%' || :query || '%'
           OR album LIKE '%' || :query || '%'
        ORDER BY title ASC
    """)
    suspend fun search(query: String): List<LocalSongEntity>
}
```

### 6.3 Database 定义

**新增文件**：`app/src/main/java/com/nasmusic/tv/backend/local/db/LocalMusicDatabase.kt`

```kotlin
@Database(
    entities = [LocalSongEntity::class],
    version = 1,
    exportSchema = true   // 导出 schema 用于迁移
)
abstract class LocalMusicDatabase : RoomDatabase() {
    abstract fun localMusicDao(): LocalMusicDao

    companion object {
        @Volatile private var INSTANCE: LocalMusicDatabase? = null

        fun get(context: Context): LocalMusicDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalMusicDatabase::class.java,
                    "local_music.db"
                )
                .fallbackToDestructiveMigrationOnDowngrade()  // 降级时重建
                .build().also { INSTANCE = it }
            }
    }
}
```

### 6.4 LocalMusicRepository

**新增文件**：`app/src/main/java/com/nasmusic/tv/backend/local/LocalMusicRepository.kt`

```kotlin
class LocalMusicRepository(
    private val context: Context,
    private val dao: LocalMusicDao,
    private val scanner: MusicScanner
) {
    companion object { private const val TAG = "LocalMusicRepo" }

    /** 启动时：从缓存加载（毫秒级） */
    suspend fun loadFromCache(): List<Song> = withContext(Dispatchers.IO) {
        dao.getAllSongs().map { it.toSong() }
    }

    /** 启动时：后台增量扫描更新索引 */
    suspend fun incrementalScan(): ScanResult = withContext(Dispatchers.IO) {
        val scanned = scanner.scanAllMusic()
        val scannedPaths = scanned.map { it.path }.toSet()
        val cachedPaths = dao.getAllPaths().toSet()

        val newSongs = scanned.filter { it.path !in cachedPaths }
        val deletedPaths = (cachedPaths - scannedPaths).toList()

        // 新增入库
        dao.insertAll(newSongs.map { it.toEntity() })
        // 删除已不存在的
        if (deletedPaths.isNotEmpty()) dao.deleteByPaths(deletedPaths)

        ScanResult(
            newSongs = newSongs.map { it.toSong() },
            deletedPaths = deletedPaths,
            updatedSongs = emptyList()
        )
    }

    /** 搜索：只查询索引，不扫描文件 */
    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        dao.search(query.trim()).map { it.toSong() }
    }

    /** 全量扫描（手动刷新时） */
    suspend fun fullScan(): List<Song> = withContext(Dispatchers.IO) {
        dao.deleteAll()
        val scanned = scanner.scanAllMusic()
        dao.insertAll(scanned.map { it.toEntity() })
        scanned.map { it.toSong() }
    }

    /** USB 设备变更时的增量扫描 */
    suspend fun scanUsbDevice(devicePath: String): ScanResult = withContext(Dispatchers.IO) {
        val scanned = scanner.scanPath(devicePath, StorageType.USB)
        val scannedPaths = scanned.map { it.path }.toSet()

        // 删除该 USB 设备旧的本地歌曲（路径前缀匹配会漏，用 storageType 筛选更可靠）
        dao.deleteByStorageType(StorageType.USB.name)
        dao.insertAll(scanned.map { it.toEntity() })

        ScanResult(
            newSongs = scanned.map { it.toSong() },
            deletedPaths = emptyList(),
            updatedSongs = emptyList()
        )
    }

    // ── 转换函数 ──

    private fun ScannedSong.toEntity(): LocalSongEntity = LocalSongEntity(
        mediaStoreId = mediaStoreId,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        duration = duration,
        size = size,
        dateAdded = dateAdded,
        mimeType = mimeType,
        contentUri = contentUri.toString(),
        volumeName = volumeName,
        storageType = storageType.name,
        path = contentUri.path ?: "",   // file:// URI 的 path 即文件路径
        lastModified = dateAdded,
        coverPath = null    // 由 LocalCoverExtractor 延迟填充
    )

    private fun LocalSongEntity.toSong(): Song = Song(
        id = "local_$mediaStoreId",
        title = title,
        artist = artist,
        album = album,
        albumId = albumId.toString(),
        durationMs = duration,
        coverUrl = coverPath ?: run {
            // MediaStore 专辑封面 URI
            if (albumId > 0) "content://media/external/audio/albumart/$albumId"
            else null
        },
        streamUrl = contentUri,    // ExoPlayer 直接吃 content:// URI
        path = path,
        isLocalSong = true,
        storageType = storageType
    )
}
```

**关键点**：
- `streamUrl` 用 `contentUri`（`content://` 或 `file://`），ExoPlayer 直接可播
- `id` 用 `"local_$mediaStoreId"` 前缀，避免与 NAS/网络歌曲 ID 冲突
- `path` 保留文件路径用于 LRC 歌词查找
- `coverUrl` 优先用已提取的封面缓存，回退到 MediaStore albumart URI

---

## 七、存储设备监听

**新增文件**：`app/src/main/java/com/nasmusic/tv/backend/local/StorageMonitor.kt`

```kotlin
class StorageMonitor(private val context: Context) {

    companion object { private const val TAG = "StorageMonitor" }

    private val _storageDevices = MutableStateFlow<List<StorageDevice>>(emptyList())
    val storageDevices: StateFlow<List<StorageDevice>> = _storageDevices.asStateFlow()

    private val _onDeviceMounted = MutableSharedFlow<StorageDevice>()
    val onDeviceMounted: SharedFlow<StorageDevice> = _onDeviceMounted.asSharedFlow()

    private val _onDeviceUnmounted = MutableSharedFlow<StorageDevice>()
    val onDeviceUnmounted: SharedFlow<StorageDevice> = _onDeviceUnmounted.asSharedFlow()

    private var receiver: BroadcastReceiver? = null

    fun startListening() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addDataScheme("file")
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_MEDIA_MOUNTED,
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        AppLog.d(TAG, "Device mounted: ${intent.data}")
                        refreshStorageDevices()
                        _storageDevices.value
                            .filter { it.isMounted && it.type == StorageType.USB }
                            .forEach { _onDeviceMounted.tryEmit(it) }
                    }
                    Intent.ACTION_MEDIA_UNMOUNTED,
                    Intent.ACTION_MEDIA_REMOVED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        AppLog.d(TAG, "Device removed: ${intent.data}")
                        _storageDevices.value
                            .filter { it.type == StorageType.USB }
                            .forEach { _onDeviceUnmounted.tryEmit(it) }
                        refreshStorageDevices()
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
        refreshStorageDevices()
    }

    fun stopListening() {
        receiver?.let { context.unregisterReceiver(it); receiver = null }
    }

    fun refreshStorageDevices() {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val devices = sm.storageVolumes.mapNotNull { volume ->
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.absolutePath ?: return@mapNotNull null
            } else {
                @Suppress("DEPRECATION")
                volume.getPath(context) ?: return@mapNotNull null
            }
            val type = when {
                path.contains("usb", ignoreCase = true) -> StorageType.USB
                path.contains("sd", ignoreCase = true) -> StorageType.EXTERNAL
                else -> StorageType.INTERNAL
            }
            StorageDevice(
                path = path,
                name = volume.getDescription(context) ?: path,
                type = type,
                isMounted = volume.isMounted,
                availableSpace = getAvailableSpace(path)
            )
        }
        _storageDevices.value = devices
        AppLog.d(TAG, "Found ${devices.size} storage devices")
    }

    private fun getAvailableSpace(path: String): Long = try {
        val stat = StatFs(path)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) { 0L }
}
```

---

## 八、搜索集成

### 8.1 SearchAggregator 接入本地搜索

**修改文件**：`app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt`

构造函数注入 `LocalMusicRepository`（可为 null，权限未授予时）：

```kotlin
class SearchAggregator(
    private val backendRegistry: BackendRegistry,
    private val networkMusicManager: NetworkMusicManager?,
    private val baiduPanApi: BaiduPanApi?,
    private val jamendoService: JamendoService?,
    private val localMusicRepository: LocalMusicRepository? = null,   // 新增
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "SearchAggregator"
        private const val NAS_TIMEOUT = 8000L
        private const val NETWORK_TIMEOUT = 6000L
        private const val LOCAL_TIMEOUT = 2000L   // 本地搜索应该很快
    }

    suspend fun search(
        keyword: String,
        sources: Set<MusicSourceType> = MusicSourceType.DEFAULT_SEARCH_SOURCES,
        // ... 其他现有参数 ...
    ): SearchAggregatorResult = coroutineScope {
        // ... 现有 NAS / 网络 / 百度 / Jamendo 搜索 ...

        // 新增：本地搜索（只查索引）
        val localDeferred = async {
            if (MusicSourceType.LOCAL in sources && localMusicRepository != null) {
                try {
                    withTimeoutOrNull(LOCAL_TIMEOUT) {
                        localMusicRepository.search(keyword)
                            .map { RankedSong(song = it, source = MusicSourceType.LOCAL) }
                    } ?: run {
                        AppLog.w(TAG, "Local search timed out")
                        emptyList()
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Local search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        val localResults = localDeferred.await()

        val allResults = (nasResults + networkResults + baiduResults + jamendoResults + localResults)
            .distinctBy { it.song.id }
            .let { RankedSong.sortByPriority(it) }

        // ... 返回 SearchAggregatorResult ...
    }
}
```

### 8.2 NasMusicApp 注入

**修改文件**：`app/src/main/java/com/nasmusic/tv/NasMusicApp.kt`

```kotlin
class NasMusicApp : Application() {
    lateinit var backendRegistry: BackendRegistry
    lateinit var appPreferences: AppPreferences
    lateinit var playerManager: PlayerManager
    lateinit var networkMusicManager: NetworkMusicManager
    lateinit var searchAggregator: SearchAggregator          // 新增
    lateinit var localMusicRepository: LocalMusicRepository   // 新增
    lateinit var storageMonitor: StorageMonitor               // 新增

    override fun onCreate() {
        super.onCreate()
        // ... 现有初始化 ...

        val localMusicDao = LocalMusicDatabase.get(this).localMusicDao()
        val musicScanner = MusicScanner(this)
        localMusicRepository = LocalMusicRepository(this, localMusicDao, musicScanner)
        storageMonitor = StorageMonitor(this)
        storageMonitor.startListening()

        searchAggregator = SearchAggregator(
            backendRegistry = backendRegistry,
            networkMusicManager = networkMusicManager,
            baiduPanApi = baiduPanApi,
            jamendoService = jamendoService,
            localMusicRepository = localMusicRepository
        )
    }
}
```

---

## 九、曲库合并管理

### 9.1 合并函数

**新增文件**：`app/src/main/java/com/nasmusic/tv/backend/local/MusicMerger.kt`

```kotlin
object MusicMerger {
    /**
     * 合并 NAS 专辑和本地专辑（按 albumName 去重）
     * 同名专辑的歌曲列表 = NAS 歌曲 + 本地歌曲
     */
    fun mergeAlbums(
        nasAlbums: List<Album>,
        localSongs: List<Song>
    ): List<Album> {
        val albumMap = linkedMapOf<String, Album>()

        // 添加 NAS 专辑
        nasAlbums.forEach { album ->
            val key = album.name.lowercase().trim()
            albumMap[key] = album
        }

        // 合并本地专辑（从 localSongs 提取专辑分组）
        localSongs.groupBy { it.album.lowercase().trim() }
            .forEach { (key, songs) ->
                if (key.isBlank()) return@forEach
                val firstSong = songs.first()
                if (key in albumMap) {
                    // 同名专辑：合并歌曲
                    val existing = albumMap[key]!!
                    albumMap[key] = existing.copy(
                        songs = existing.songs + songs
                    )
                } else {
                    // 新专辑
                    albumMap[key] = Album(
                        id = "local_album_${firstSong.albumId ?: firstSong.id}",
                        name = firstSong.album,
                        artist = firstSong.artist,
                        coverUrl = firstSong.coverUrl,
                        songs = songs
                    )
                }
            }

        return albumMap.values.toList()
    }

    /**
     * 合并 NAS 艺术家和本地艺术家（按 artistName 去重）
     */
    fun mergeArtists(
        nasArtists: List<Artist>,
        localSongs: List<Song>
    ): List<Artist> {
        val artistMap = linkedMapOf<String, Artist>()

        nasArtists.forEach { artist ->
            val key = artist.name.lowercase().trim()
            artistMap[key] = artist
        }

        localSongs.groupBy { it.artist.lowercase().trim() }
            .forEach { (key, songs) ->
                if (key.isBlank()) return@forEach
                val firstSong = songs.first()
                if (key in artistMap) {
                    val existing = artistMap[key]!!
                    artistMap[key] = existing.copy(
                        songs = existing.songs + songs,
                        songCount = (existing.songCount ?: 0) + songs.size
                    )
                } else {
                    artistMap[key] = Artist(
                        id = "local_artist_${firstSong.artist ?: firstSong.id}",
                        name = firstSong.artist,
                        songCount = songs.size,
                        albumCount = songs.map { it.album }.distinct().size,
                        songs = songs
                    )
                }
            }

        return artistMap.values.toList()
    }
}
```

### 9.2 MainViewModel 集成

**修改文件**：`app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt`

```kotlin
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NasMusicApp

    // 新增：本地歌曲状态
    private val _localSongs = MutableStateFlow<List<Song>>(emptyList())
    val localSongs: StateFlow<List<Song>> = _localSongs.asStateFlow()

    // 新增：合并后的专辑/艺术家
    private val _mergedAlbums = MutableStateFlow<List<Album>>(emptyList())
    val mergedAlbums: StateFlow<List<Album>> = _mergedAlbums.asStateFlow()

    private val _mergedArtists = MutableStateFlow<List<Artist>>(emptyList())
    val mergedArtists: StateFlow<List<Artist>> = _mergedArtists.asStateFlow()

    init {
        // ... 现有初始化 ...

        // 新增：启动本地音乐加载
        viewModelScope.launch {
            // 1. 立即从缓存加载（毫秒级）
            _localSongs.value = app.localMusicRepository.loadFromCache()
            updateMergedData()

            // 2. 后台增量扫描（不阻塞 UI）
            launch(Dispatchers.IO) {
                try {
                    val result = app.localMusicRepository.incrementalScan()
                    if (result.hasChanges()) {
                        _localSongs.value = app.localMusicRepository.loadFromCache()
                        updateMergedData()
                        AppLog.i(TAG, "Local scan: +${result.newSongs.size} new, -${result.deletedPaths.size} deleted")
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Local incremental scan failed: ${e.message}", e)
                }
            }

            // 3. 监听 USB 设备插拔
            app.storageMonitor.onDeviceMounted
                .onEach { device ->
                    AppLog.i(TAG, "USB mounted: ${device.name}, scanning...")
                    val result = app.localMusicRepository.scanUsbDevice(device.path)
                    if (result.hasChanges()) {
                        _localSongs.value = app.localMusicRepository.loadFromCache()
                        updateMergedData()
                    }
                }
                .catch { e -> AppLog.e(TAG, "USB mount listener error: ${e.message}", e) }
                .launchIn(viewModelScope)
        }
    }

    /** 刷新合并后的专辑/艺术家 */
    private fun updateMergedData() {
        val nasAlbums = _uiState.value.albums
        val nasArtists = _uiState.value.artists
        val local = _localSongs.value

        _mergedAlbums.value = MusicMerger.mergeAlbums(nasAlbums, local)
        _mergedArtists.value = MusicMerger.mergeArtists(nasArtists, local)
    }

    /** 手动刷新本地音乐库 */
    fun refreshLocalMusic() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.localMusicRepository.fullScan()
                _localSongs.value = app.localMusicRepository.loadFromCache()
                updateMergedData()
            } catch (e: Exception) {
                AppLog.e(TAG, "Full scan failed: ${e.message}", e)
            }
        }
    }
}
```

### 9.3 LibraryScreen 使用合并数据

**修改文件**：`app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`

`AlbumsTab` 和 `ArtistsTab` 的数据源从 `uiState.albums` / `uiState.artists` 改为 `mergedAlbums` / `mergedArtists`：

```kotlin
@Composable
fun LibraryScreen(...) {
    val mergedAlbums by viewModel.mergedAlbums.collectAsState()
    val mergedArtists by viewModel.mergedArtists.collectAsState()

    when (currentTab) {
        LibraryTab.ALBUMS -> AlbumsTab(
            albums = mergedAlbums,    // 合并后的列表
            onPlayAlbum = { ... },
            onOpenAlbumDetail = { ... }
        )
        LibraryTab.ARTISTS -> ArtistsTab(
            artists = mergedArtists,  // 合并后的列表
            // ...
        )
        // ...
    }
}
```

`AlbumsTab` / `ArtistsTab` / `SongsTab` 内部逻辑不变——`UnifiedSongRow` 会通过 `SourceBadge(song)` 自动显示 "本地" / "NAS" 标签。

---

## 十、搜索页来源选项

**修改文件**：`app/src/main/java/com/nasmusic/tv/ui/screens/library/SearchTab.kt`

`SearchSourceBar` 已通过 `DEFAULT_SEARCH_SOURCES.forEach` 遍历 chips，添加 LOCAL 到枚举后自动出现"本地"chip。无需改动 `SearchSourceBar` 本身。

只需确认 `SearchTab` 的来源开关状态初始值包含 LOCAL：

```kotlin
// MainViewModel 或 SearchTab 中初始化 enabledSources
val initialSources = MusicSourceType.DEFAULT_SEARCH_SOURCES.toMutableSet()
// 用户可通过 chip 关闭 LOCAL 来源
```

---

## 十一、播放集成

### 11.1 PlayerManager 播放本地歌曲

**修改文件**：`app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt`

本地歌曲的 `streamUrl` 已是 `content://` 或 `file://` URI，ExoPlayer 直接可播。无需 `onNeedResolveStreamUrl` 回调。

```kotlin
fun playSong(song: Song) {
    val mediaItem = when {
        song.isLocalSong -> {
            // 本地歌曲：streamUrl 是 content:// 或 file:// URI
            MediaItem.Builder()
                .setUri(song.streamUrl ?: song.path?.let { "file://$it" })
                .setMediaId(song.id)
                .build()
        }
        song.isNetworkSong && song.streamUrl.isNullOrBlank() -> {
            // 网络歌曲：需要解析 streamUrl
            onNeedResolveStreamUrl?.invoke(song)
            return
        }
        else -> {
            // NAS 歌曲：streamUrl 已就绪
            MediaItem.Builder()
                .setUri(song.streamUrl)
                .setMediaId(song.id)
                .build()
        }
    }
    exoPlayer.setMediaItem(mediaItem)
    exoPlayer.prepare()
    exoPlayer.playWhenReady = true

    // 统一记录播放（复用现有 AppPreferences.recordPlay）
    viewModelScope?.launch {
        appPreferences.recordPlay(song.id)
    }
}
```

### 11.2 权限检查

播放本地歌曲前确保权限已授予：

```kotlin
// MainViewModel 或 LibraryScreen 入口
if (!PermissionHelper.hasLocalMusicPermission(context)) {
    // 弹出权限请求对话框
    onRequestPermission()
    return
}
```

---

## 十二、扩展功能

### 12.1 本地 LRC 歌词

**新增文件**：`app/src/main/java/com/nasmusic/tv/lyrics/LocalLyricsProvider.kt`

```kotlin
class LocalLyricsProvider {
    /** 查找同目录下同名 .lrc 文件 */
    fun findLrcFile(audioPath: String): File? {
        val audioFile = File(audioPath)
        val parent = audioFile.parentFile ?: return null
        val baseName = audioFile.nameWithoutExtension
        return File(parent, "$baseName.lrc").takeIf { it.exists() }
    }

    /** 读取 LRC 内容（尝试 UTF-8，回退 GBK） */
    fun readLrc(lrcFile: File): String? = try {
        lrcFile.readText(Charsets.UTF_8)
    } catch (e: Exception) {
        try {
            lrcFile.readBytes().toString(Charsets.GBK)  // GBK 回退
        } catch (e2: Exception) { null }
    }

    fun getLocalLyrics(audioPath: String): String? =
        findLrcFile(audioPath)?.let { readLrc(it) }
}
```

**集成到 LyricsManager**：

```kotlin
// LyricsManager.getLyrics() 优先级调整
suspend fun getLyrics(song: Song): String? {
    // 1. 本地 LRC 文件（仅本地歌曲）
    if (song.isLocalSong && song.path != null) {
        localLyricsProvider.getLocalLyrics(song.path)?.let { return it }
    }
    // 2. 后端 API（NAS 歌曲）
    if (!song.isNetworkSong && !song.isLocalSong) {
        backendAdapter?.getLyrics(song.id)?.let { return it }
    }
    // 3. 网络匹配
    return networkLyricsManager.searchLyrics(song.title, song.artist)
}
```

### 12.2 专辑封面提取

**新增文件**：`app/src/main/java/com/nasmusic/tv/backend/local/LocalCoverExtractor.kt`

```kotlin
class LocalCoverExtractor(private val context: Context) {

    /** 从音频文件内嵌元数据提取封面 */
    fun extractEmbeddedCover(audioPath: String): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(audioPath)
        val art = retriever.embeddedPicture
        retriever.release()
        art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    } catch (e: Exception) { null }

    /** 保存封面到缓存目录 */
    private fun saveCoverToCache(songId: Long, bitmap: Bitmap): File? = try {
        val cacheDir = File(context.cacheDir, "album_covers").apply { mkdirs() }
        val file = File(cacheDir, "$songId.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file
    } catch (e: Exception) { null }

    /** 提取并缓存封面，返回路径 */
    fun extractAndCache(song: LocalSongEntity): String? {
        extractEmbeddedCover(song.path)?.let { bitmap ->
            saveCoverToCache(song.mediaStoreId, bitmap)?.let { return it.absolutePath }
        }
        return null
    }
}
```

**延迟提取策略**：封面不在扫描时提取（避免启动慢），而是在 `LocalSongEntity.toSong()` 转换时按需提取并更新 `coverPath` 字段。

### 12.3 播放统计（整合现有系统）

**不新建独立表**。现有 `AppPreferences.recordPlay(songId: String)` 已统一处理所有歌曲（NAS + 网络 + 本地）的播放次数和最近播放记录，存到 DataStore。

本地歌曲的 `songId` 是 `"local_$mediaStoreId"`，直接调用：

```kotlin
// PlayerManager.playSong() 中（见 §11.1）
appPreferences.recordPlay(song.id)   // song.id = "local_xxx"
```

**最近播放列表**：现有 `AppPreferences.getRecentSongIds()` 已返回所有歌曲的 ID 列表，本地歌曲 ID 自动包含其中。`RecentSongObjectsData` 存储完整 Song 对象，本地歌曲加入即可。

**无需改动**：播放统计完全复用现有系统。

### 12.4 收藏（统一模型）

**设计原则**：所有歌曲（NAS / 网络 / 百度 / Jamendo / 本地）共用同一套收藏机制，**不为本地歌曲单独建表**。收藏项通过 `source` 字段标识来源，UI 展示时复用 `SourceBadge` 自动显示来源标签。

**统一收藏模型**：

扩展现有 `NetworkFavoriteItem`，使其成为通用收藏项。`source` 字段已存在，本地歌曲填 `"local"`：

```kotlin
// data/model/NetworkFavoriteItem.kt（保留类名，语义扩展为通用收藏）
data class NetworkFavoriteItem(
    val songId: String,         // "local_xxx" / "ntwk_xxx" / NAS ID
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String? = null,
    val source: String,         // "nas" / "network" / "baidu" / "jamendo" / "local"
    val addedAt: Long = System.currentTimeMillis()
)
```

**收藏操作**（在 `MainViewModel` 中，复用现有 `AppPreferences` 方法）：

```kotlin
fun toggleFavorite(song: Song) {
    viewModelScope.launch {
        val favorites = appPreferences.getNetworkFavorites()
        val existing = favorites.find { it.songId == song.id }
        if (existing != null) {
            appPreferences.removeNetworkFavorite(song.id)
        } else {
            appPreferences.addNetworkFavorite(
                NetworkFavoriteItem(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    coverUrl = song.coverUrl,
                    source = when {
                        song.isLocalSong -> "local"
                        song.isNetworkSong -> song.networkSource ?: "network"
                        else -> "nas"
                    }
                )
            )
        }
    }
}
```

**UI 集成 — 歌曲条目标明来源即可**：

`UnifiedSongRow` 已通过 `SourceBadge(song = song)` 显示来源标签（"本地" / "NAS" / "网络" / "百度" / "Jamendo"），无需额外标识。收藏列表页（MineScreen 的"我的收藏"）展示收藏项时：

```kotlin
// MineScreen 收藏列表
LazyColumn {
    items(favorites) { favorite ->
        // 将 NetworkFavoriteItem 转回 Song，复用 UnifiedSongRow
        val song = favorite.toSong()
        UnifiedSongRow(
            song = song,
            onClick = { onPlay(song) },
            isFavorited = true,
            onToggleFavorite = { viewModel.toggleFavorite(song) },
            // SourceBadge 会根据 song.sourceType 自动显示来源标签
        )
    }
}

// NetworkFavoriteItem → Song 转换（用于展示）
fun NetworkFavoriteItem.toSong(): Song = Song(
    id = songId,
    title = title,
    artist = artist,
    album = album,
    coverUrl = coverUrl,
    isLocalSong = source == "local",
    isNetworkSong = source in listOf("network", "meting", "alapi", "jiosaavn", "baidu", "jamendo", "weather"),
    networkSource = source.takeIf { it != "nas" && it != "local" },
    // SourceBadge 通过 sourceType 扩展属性自动推导来源
)
```

**关键点**：
- `NetworkFavoriteItem.source` 字段持久化来源标识
- 展示时转回 `Song`，`sourceType` 扩展属性自动推导出 `MusicSourceType`
- `SourceBadge` 渲染对应的 displayName + color（本地 → "本地" 橙色）
- 收藏列表无需额外的"来源列"或"来源图标"，`SourceBadge` 已承载此信息

**无需改动**：`UnifiedSongRow`、`SourceBadge`、`AppPreferences.addNetworkFavorite` / `removeNetworkFavorite` / `getNetworkFavorites` 全部复用。

---

## 十三、测试计划

### 13.1 单元测试

1. **MusicScanner 测试**
   - MediaStore 扫描返回正确字段
   - 文件系统扫描支持所有音频格式
   - 存储类型识别（USB / SD / Internal）

2. **LocalMusicDao 测试**（Robolectric）
   - insertAll / getAllSongs / search / deleteByPaths
   - 增量扫描后数据一致性
   - 重复 path 唯一索引约束

3. **LocalMusicRepository 测试**
   - loadFromCache 返回缓存数据
   - incrementalScan 正确识别新增/删除
   - fullScan 清空后重建
   - search 只查索引不扫描文件（验证不调用 scanner）

4. **MusicMerger 测试**
   - mergeAlbums 同名专辑合并歌曲
   - mergeArtists 同名艺术家合并
   - 大小写不敏感去重
   - 空白名过滤

5. **LocalLyricsProvider 测试**
   - LRC 文件查找（同目录同名）
   - UTF-8 / GBK 编码回退

6. **LocalCoverExtractor 测试**
   - 内嵌封面提取
   - 缓存文件生成

### 13.2 集成测试

1. **启动流程测试**
   - 首次启动触发全量扫描
   - 二次启动先返回缓存再增量
   - USB 插入触发 scanUsbDevice

2. **搜索集成测试**
   - 本地源参与跨源搜索
   - 关闭 LOCAL 来源后不返回本地结果
   - 搜索结果按 SOURCE_PRIORITY 排序

3. **播放测试**
   - 本地歌曲 ExoPlayer 直接播放
   - 播放后 recordPlay 记录到 DataStore
   - 队列中混合 NAS + 本地歌曲

4. **合并管理测试**
   - LibraryScreen 显示合并后专辑/艺术家
   - SourceBadge 正确显示 "本地" 标签
   - 同名专辑点击进入显示 NAS + 本地歌曲

### 13.3 设备测试

1. **USB 设备测试**
   - USB 插入自动扫描
   - USB 拔出删除相关索引
   - 大容量 U 盘（1000+ 首）扫描性能

2. **权限测试**
   - 首次启动请求 READ_MEDIA_AUDIO
   - 权限拒绝后空状态提示
   - 权限授予后重新扫描

3. **性能测试**
   - 1000+ 首歌曲扫描时间 < 5 秒
   - 搜索响应 < 100ms
   - 内存占用稳定

---

## 十四、开发时间估算

| 阶段 | 任务 | 工时（天） |
|------|------|-----------|
| 1 | 数据模型扩展（Song、SourceIdentifier） | 0.5 |
| 2 | 权限配置 + PermissionHelper | 0.5 |
| 3 | MusicScanner（MediaStore + 文件扫描） | 2 |
| 4 | Room 数据库（Entity / DAO / Database） | 1.5 |
| 5 | LocalMusicRepository（缓存 + 增量 + 搜索） | 2 |
| 6 | StorageMonitor（USB 插拔监听） | 1 |
| 7 | SearchAggregator 接入本地搜索 | 0.5 |
| 8 | MusicMerger + MainViewModel 集成 | 1.5 |
| 9 | LibraryScreen 使用合并数据 | 1 |
| 10 | PlayerManager 本地歌曲播放 | 0.5 |
| 11 | 本地 LRC 歌词 + LyricsManager 集成 | 1 |
| 12 | 专辑封面提取 | 1 |
| 13 | 收藏功能整合 | 0.5 |
| 14 | 测试编写 | 3 |
| 15 | 设备测试 + 调试 | 2 |
| **总计** | | **18.5 天** |

---

## 十五、注意事项

### 15.1 性能优化

1. **启动顺序**：先返回缓存（毫秒级），再后台增量扫描，不阻塞 UI
2. **搜索只查索引**：`LocalMusicRepository.search()` 调用 DAO `@Query`，不触发文件扫描
3. **封面延迟提取**：扫描时不提取内嵌封面，仅在 UI 展示时按需提取并缓存
4. **USB 增量扫描**：USB 插拔只扫描该设备，不全量重建
5. **Room 索引**：`path` 字段加唯一索引，加速增量扫描的路径比对

### 15.2 兼容性

1. **Android 10+ 分区存储**：使用 `content://` URI，不依赖 `MediaStore.DATA` 列
2. **Android 13+ 权限细分**：`READ_MEDIA_AUDIO` 替代 `READ_EXTERNAL_STORAGE`
3. **音频格式**：MP3 / FLAC / M4A / OGG / WAV / AAC / WMA
4. **歌词编码**：LRC 文件 UTF-8 优先，GBK 回退（参考现有 `EncodingUtils`）
5. **设备差异**：TV 设备 USB 口支持差异，`uses-feature required=false`

### 15.3 错误处理

1. **权限拒绝**：LibraryScreen 显示空状态 + 引导用户授权
2. **扫描失败**：捕获异常，记录日志，不影响现有 NAS / 网络功能
3. **文件损坏**：`MediaMetadataRetriever` 抛异常时跳过该文件
4. **数据库损坏**：`fallbackToDestructiveMigrationOnDowngrade` + 提供手动重建入口
5. **URI 失效**：USB 拔出后播放失败时，从队列移除并提示

### 15.4 用户体验

1. **扫描进度**：Settings 页显示扫描状态和歌曲数
2. **空状态**：无本地歌曲时友好提示
3. **手动刷新**：Settings 页提供"重新扫描本地音乐"按钮
4. **收藏反馈**：复用现有 `UnifiedSongRow` 的 `onToggleFavorite` 视觉反馈
5. **来源标签**：`SourceBadge` 自动显示"本地"橙色标签

---

## 十六、总结

本方案实现本地音乐作为独立数据源集成到曲库：

| 设计原则 | 实现 |
|----------|------|
| 独立数据源 | 不实现 `BackendAdapter`，通过 `LocalMusicRepository` 提供 API |
| 索引持久化 | Room 数据库存储，启动时缓存加载 + 后台增量扫描 |
| 搜索性能 | 搜索只查索引，毫秒级响应 |
| 合并管理 | `MusicMerger` 按 `name.lowercase().trim()` 去重合并 NAS + 本地 |
| 系统整合 | 播放统计复用 `AppPreferences.recordPlay`，收藏扩展 `NetworkFavoriteItem` |
| 扩展功能 | 本地 LRC 歌词、专辑封面提取、USB 热插拔 |
| UI 复用 | `SourceBadge` 自动显示标签，`UnifiedSongRow` 收藏按钮已存在 |

### 功能清单

| 功能 | 描述 | 状态 |
|------|------|------|
| 本地音乐扫描 | MediaStore + 文件系统双通道扫描 | ✅ 规划完成 |
| 索引持久化 | Room 数据库，启动增量更新 | ✅ 规划完成 |
| 跨源搜索 | 本地参与 `SearchAggregator` 跨源搜索 | ✅ 规划完成 |
| 合并管理 | 同名艺术家/专辑自动合并 | ✅ 规划完成 |
| 本地歌词 | 同目录 LRC 文件，UTF-8/GBK 回退 | ✅ 规划完成 |
| 专辑封面 | 内嵌封面提取 + MediaStore 封面回退 | ✅ 规划完成 |
| 播放统计 | 整合 `AppPreferences.recordPlay` | ✅ 规划完成 |
| 收藏功能 | 扩展 `NetworkFavoriteItem` 支持本地 | ✅ 规划完成 |
| USB 热插拔 | `StorageMonitor` 监听挂载广播 | ✅ 规划完成 |
| 权限处理 | `READ_MEDIA_AUDIO` 运行时请求 | ✅ 规划完成 |

预计开发周期 18.5 天，按阶段递进实现：数据模型 → 扫描器 → 索引 → 合并 → UI → 扩展功能。
