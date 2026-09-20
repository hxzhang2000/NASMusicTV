# 方案 C：AI 预分离人声消除（Spleeter）

> 状态：设计文档，待评审  
> 日期：2026-08-07  
> 关联：方案 B（实时 DSP）见 `docs/vocal-removal-approach-b-dsp.md`

## 1. 原理

使用深度学习模型（Spleeter 2-stem）将原始音频文件分离为"人声"和"伴奏"两个独立音轨，播放时选择伴奏音轨。与实时 DSP 方案不同，AI 分离是**预处理**模式：提前生成伴奏文件并缓存，播放时直接使用缓存文件。

## 2. 技术选型

### 模型：Spleeter 2-stem

| 属性 | 值 |
|------|-----|
| 分离轨道 | 2 轨（vocals + accompaniment） |
| 模型格式 | TensorFlow SavedModel（原始）/ TF Lite（移动端） |
| 模型大小 | ~26MB（SavedModel）/ ~15MB（TF Lite） |
| APK 增量 | ~20-25MB（含 TF Lite 运行时依赖） |
| 质量评级 | ⭐⭐⭐（足够好，远超 DSP 方案） |
| 输入 | 立体声 WAV / MP3（任意采样率，内部重采样到 44100Hz） |
| 输出 | 两个 WAV 文件（vocals.wav + accompaniment.wav），时长与原文件一致 |

### 为什么选 Spleeter 2-stem 而非其他

- **vs Spleeter 4-stem/5-stem**：2-stem 足够（只需人声+伴奏），模型更小、速度更快
- **vs Demucs v4**：Demucs 质量更高但依赖 PyTorch（移动端不友好），模型 80-160MB 太大
- **vs MDX-Net (UVR)**：面向桌面端 GPU 设计，不适合 Android TV

## 3. 算力与性能预估

### 3.1 处理速度

| 运行环境 | 4分钟歌曲处理时间 | 说明 |
|---------|------------------|------|
| TV 盒子本地（ARM CPU, TF Lite） | 48-120 秒 | 无 GPU/NPU 加速，CPU 密集型 |
| TV 盒子 + NNAPI 委托 | 30-60 秒 | 仅当芯片支持 NNAPI 硬件加速（多数 TV 盒子不支持） |
| NAS 服务器（Docker, x86 CPU） | 15-30 秒 | 最快路径，服务器 CPU 性能远优于 TV |
| 桌面开发机（x86 CPU） | 12-24 秒 | 基准参考 |

### 3.2 内存占用

- TF Lite 推理峰值内存：~200-400MB（取决于歌曲时长）
- TV 盒子通常有 2-4GB RAM，足够运行
- 处理完成后内存立即释放

### 3.3 电量/发热

- CPU 满载 1-2 分钟，会产生一定热量
- 对 TV 盒子无实质影响（非电池供电）

## 4. 架构设计

### 4.1 混合处理策略

```
用户点击"伴奏模式"
    │
    ├─ 检查本地缓存是否已有该歌曲的伴奏文件
    │   ├─ 有 → 直接播放伴奏文件（零等待）
    │   └─ 无 → 进入预处理流程
    │
    ├─ 预处理流程：
    │   ├─ NAS 歌曲 → 优先请求 NAS 端处理（如有 Spleeter 服务）
    │   │   ├─ NAS 有服务 → 等待 15-30 秒，下载伴奏文件
    │   │   └─ NAS 无服务 → 回退到 TV 本端处理
    │   │
    │   └─ 网络歌曲 → TV 本端 TF Lite 处理（48-120 秒）
    │
    ├─ 预处理期间：
    │   ├─ 先播放原唱版本（用户不用干等）
    │   ├─ UI 显示进度提示："正在生成伴奏... (预计 60 秒)"
    │   └─ 生成完成后自动切换到伴奏版本
    │
    └─ 缓存管理：
        ├─ 新伴奏文件写入缓存目录
        ├─ 检查缓存数量是否已达上限（默认 10 首）
        │   └─ 超限 → 删除最久未播放的伴奏文件（LRU）
        └─ 更新缓存索引
```

### 4.2 组件划分

```
app/src/main/java/com/nasmusic/tv/
├── player/
│   └── PlaybackService.kt          # [修改] 播放伴奏文件替代原文件
├── karaoke/                         # [新增]
│   ├── KaraokeManager.kt            # 伴奏缓存管理 + 预处理调度
│   ├── SpleeterProcessor.kt         # TF Lite 模型推理封装
│   ├── KaraokeCache.kt              # LRU 缓存管理
│   └── NasKaraokeClient.kt          # NAS 端处理请求（可选）
├── ui/screens/
│   └── NowPlayingScreen.kt          # [修改] 添加"原唱/伴奏"切换按钮
└── ui/viewmodel/
    └── MainViewModel.kt             # [修改] 暴露 karaoke 状态给 UI
```

### 4.3 缓存设计

#### 缓存目录结构

```
/data/data/com.nasmusic.tv/files/karaoke_cache/
├── index.json                       # 缓存索引（songId → 文件名 + 时间戳）
├── abc123.mp3                       # 伴奏文件（以 songId 命名）
├── def456.mp3
└── ...
```

#### 缓存索引格式（index.json）

```json
{
  "maxSize": 10,
  "entries": [
    {
      "songId": "abc123",
      "songTitle": "示例歌曲",
      "songArtist": "示例歌手",
      "fileName": "abc123.mp3",
      "createdAt": 1723000000000,
      "lastPlayedAt": 1723001000000,
      "source": "local"           // "local" | "nas" | "network"
    }
  ]
}
```

#### LRU 淘汰策略

1. 新伴奏生成时，检查 `entries.size >= maxSize`
2. 若超限，找到 `lastPlayedAt` 最小的条目，删除对应文件
3. 从索引中移除该条目
4. 写入新条目

#### 缓存大小

- 每首伴奏 MP3（128kbps）约 3-5MB
- 10 首约 30-50MB
- 可在设置页配置上限：5 / 10 / 20 首

### 4.4 NAS 端处理（可选增强）

如果 NAS（Jellyfin/Navidrome 服务器）支持 Docker，可以部署 Spleeter API 服务：

```
NAS Docker:
  spleeter-api (Python Flask/FastAPI)
    POST /separate
      Body: { "songId": "abc123", "streamUrl": "http://nas/music/abc123.mp3" }
      Response: 伴奏文件二进制流

TV App:
  KaraokeManager 请求 NAS API
  -> 下载伴奏文件 -> 写入缓存
```

**优点**：处理速度比 TV 本端快 3-5 倍，不消耗 TV 资源  
**限制**：需要用户自行在 NAS 上部署服务，门槛较高；可作为增强功能后续迭代

## 5. TF Lite 模型集成

### 5.1 模型文件放置

```
app/src/main/assets/
└── spleeter_2stem.tflite            # TF Lite 模型文件（~15MB）
```

### 5.2 依赖

```kotlin
// app/build.gradle.kts
dependencies {
    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    // 可选：NNAPI 委托（硬件加速）
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
}
```

### 5.3 推理流程（SpleeterProcessor.kt 伪代码）

```kotlin
class SpleeterProcessor(context: Context) {
    private val interpreter: Interpreter

    init {
        val modelBuffer = loadModelFile(context, "spleeter_2stem.tflite")
        val options = Interpreter.Options()
        options.setNumThreads(4)  // 利用多核
        // options.setUseNNAPI(true)  // 可选：尝试 NNAPI 加速
        interpreter = Interpreter(modelBuffer, options)
    }

    /**
     * 分离音频文件
     * @param inputPath 原始音频文件路径（MP3/WAV）
     * @param outputDir 输出目录
     * @return 伴奏文件路径
     */
    fun separate(inputPath: String, outputDir: String): String {
        // 1. 解码音频文件为 PCM（用 MediaExtractor + MediaCodec）
        val pcmData = decodeToPcm(inputPath)
        // 2. 重采样到 44100Hz 立体声（如需要）
        val resampled = resampleIfNeeded(pcmData, targetRate = 44100)
        // 3. 分块送入模型（Spleeter 处理固定长度片段，如 30秒）
        val accompaniment = processInChunks(resampled)
        // 4. 编码为 MP3（用 MediaCodec 或第三方库）
        val outputPath = "$outputDir/accompaniment.mp3"
        encodeToMp3(accompaniment, outputPath)
        return outputPath
    }
}
```

### 5.4 Spleeter 模型转换

原始 Spleeter 模型是 TensorFlow SavedModel 格式，需要转换为 TF Lite：

```bash
# 在开发机上执行（一次性）
pip install spleeter tensorflow

# 导出 2-stem 模型
spleeter download -o output_dir 2stems

# 转换为 TF Lite
python convert_to_tflite.py
# 脚本内容：
# import tensorflow as tf
# converter = tf.lite.TFLiteConverter.from_saved_model("output_dir/2stems")
# converter.optimizations = [tf.lite.Optimize.DEFAULT]
# tflite_model = converter.convert()
# with open("spleeter_2stem.tflite", "wb") as f:
#     f.write(tflite_model)
```

## 6. 用户交互设计

### 6.1 NowPlaying 页面

- 在播放控制栏添加"原唱/伴奏"切换按钮（类似"单曲循环"按钮的位置）
- 按钮状态：
  - **原唱模式**（默认）：图标 ♪，文字"原唱"
  - **伴奏模式**：图标 ♪̲（带删除线），文字"伴奏"
- 点击切换时：
  - 切换到伴奏：检查缓存 → 有则直接切换播放源 / 无则启动预处理
  - 切换回原唱：直接切换回原文件播放

### 6.2 预处理进度提示

- 顶部 SnackBar 或进度条："正在生成伴奏...（预计 60 秒）"
- 预处理期间继续播放原唱，用户可随时取消
- 完成后 Toast 提示"伴奏已就绪，已自动切换"

### 6.3 设置页

- 新增"卡拉OK设置"分区：
  - 伴奏缓存上限：5 / 10 / 20 首（默认 10）
  - 清空伴奏缓存按钮
  - 缓存占用空间显示

## 7. 播放切换实现

### 7.1 替换播放源

当用户切换到伴奏模式时，需要将 ExoPlayer 的当前 MediaItem 替换为伴奏文件，同时保持播放位置不变：

```kotlin
// KaraokeManager.kt
fun switchToAccompaniment(song: Song) {
    val cacheFile = cache.get(song.id) ?: return
    val currentPosition = playerManager.getCurrentPosition()
    
    // 替换 MediaItem 为伴奏文件
    val mediaItem = MediaItem.fromUri(cacheFile.toUri())
    playerManager.player?.apply {
        setMediaItem(mediaItem)
        seekTo(currentPosition)  // 保持播放位置
        prepare()
        play()
    }
}
```

### 7.2 歌词同步

伴奏文件与原曲时间轴完全一致，无需偏移校正。`LyricsManager` 的歌词高亮逻辑不需要任何修改。

## 8. 存储与资源估算

| 项目 | 大小 |
|------|------|
| TF Lite 模型文件（assets） | ~15MB |
| TF Lite 运行时依赖 | ~5-10MB（APK 增量） |
| 伴奏缓存（10 首） | ~30-50MB（运行时存储） |
| 推理峰值内存 | ~200-400MB（处理期间） |
| **总 APK 增量** | **~20-25MB** |

## 9. 风险与限制

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| TV 盒子 CPU 太弱，处理时间过长 | 用户体验差（等待 2 分钟以上） | 优先使用 NAS 端处理；限制最大歌曲时长；超时回退到方案 B |
| TF Lite 模型在某些芯片上不兼容 | 功能不可用 | 检测兼容性，不兼容时禁用功能并提示 |
| 伴奏文件质量不如预期 | 用户不满意 | 后续可升级到 Demucs 模型或集成 UVR |
| 网络歌曲码率低，分离效果差 | 伴奏质量下降 | 网络歌曲建议使用方案 B（实时 DSP） |
| 缓存管理不当导致存储满 | 应用崩溃 | 设置页显示缓存大小，提供一键清理 |

## 10. 与方案 B 的对比

| 维度 | 方案 B（实时 DSP） | 方案 C（AI 预分离） |
|------|-------------------|-------------------|
| 音质 | ⭐⭐⭐ | ⭐⭐⭐⭐ |
| 首次等待 | 零（实时） | 30-120 秒 |
| 存储开销 | 零 | 30-50MB（10 首缓存） |
| APK 增量 | 零 | ~20-25MB |
| CPU 开销 | 低（实时滤波） | 高（预处理时满载） |
| 网络歌曲支持 | ✅ 实时生效 | ✅ 但需等待本地处理 |
| 实现复杂度 | 中（~200 行） | 高（~800-1000 行 + 模型文件） |
| 歌词同步 | ✅ | ✅ |
| 可同时使用 | 可作为方案 C 的回退 | - |

## 11. 实施计划（如决定推进）

| 阶段 | 内容 | 预计工时 |
|------|------|---------|
| 1 | 模型转换（Spleeter → TF Lite），验证推理正确性 | 2-3 小时 |
| 2 | SpleeterProcessor 封装（PCM 解码 + 推理 + MP3 编码） | 4-6 小时 |
| 3 | KaraokeCache（LRU 缓存管理） | 2 小时 |
| 4 | KaraokeManager（调度 + 播放源切换） | 3-4 小时 |
| 5 | UI（NowPlaying 切换按钮 + 进度提示 + 设置页） | 3-4 小时 |
| 6 | NAS 端处理（可选） | 4-6 小时 |
| 7 | 测试 + 调优 | 3-4 小时 |
| **合计** | | **~20-30 小时** |

---

*文档版本：1.0  
*创建日期：2026-08-07*
