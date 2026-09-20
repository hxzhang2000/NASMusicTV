# K 歌 / ONNX 人声分离 专项审查报告

**日期**：2026-09-14
**范围**：`player/DemucsSeparator.kt`(671)、`player/HqSeparationOrchestrator.kt`(555)、`player/ModelDownloadManager.kt`(238)、`ui/viewmodel/VocalSeparationViewModel.kt`(288)、`player/{VocalRemovalProcessor,SpectralMaskProcessor,PcmTapProcessor,SeparationMode}.kt`
**动机**：全项目最大盲区——上一轮全量审阅报告完全未覆盖该模块；本轮 lint 又在同一模块的依赖上暴露出 `NativeLibraryAlignment`，佐证此前无人细看。
**结论**：架构设计整体是清醒的（单飞锁、延迟释放、临时文件落盘降内存、失败清残file），但存在 **2 个用户可感知的正确性缺陷**、**2 个资源/安全缺口**，以及若干可清理项。

---

## 一、P0 —— 无采样率归一化，48kHz 源会「变调 + 降质」

**位置**：`DemucsSeparator.kt:437-449`（解码）、`:631-654`（写头）

**事实链**：

1. `codec.configure(format, null, null, 0)` 直接把提取器的轨道格式交给解码器，**没有**改写 `KEY_SAMPLE_RATE`。MediaCodec 解码器不做重采样，输出即源采样率。
2. `decodeAudioToTempFile` 把源采样率读出来放进 `DecodeResult.sampleRate`（:441-443），但 `separate()` 只在算 `durationMs` 时用它（:372）。
3. 写入 WAV 头时 `writeWavHeader` **硬编码 `SAMPLE_RATE` = 44100**（:646-647）。
4. 全项目 grep 无任何重采样实现（唯一命中的 `resample()` 在可视化渲染器里，无关）。

**后果**（对 48kHz 源，如大量 FLAC / 现代母带 / 视频提取音轨）：

- **模型侧**：HT-Demucs 在 44.1kHz 上训练，内部 STFT 的频点映射以 44100 为基准。喂 48kHz 内容 ⇒ 频点整体偏移，学到的滤波器不再对应 ⇒ **分离质量劣化**。
- **播放侧**：输出 PCM 的样本数 = 源帧数，但 WAV 头声明 44100 ⇒ 播放时被按 44100 解释 ⇒ 时长拉伸 48000/44100 ≈ **1.088 倍（慢 8.8%）**，音高下降约 1.5 个半音。伴奏与原始音轨**无法对齐**，K 歌场景下直接不可用。

**修复建议**（择一）：

- 首选：解码后插入重采样到 44100（线性/多相均可，量不大）；`writeWavHeader` 随之固定 44100。
- 次选（成本最低）：在 `separate()` 入口检测 `sampleRate != 44100`，直接 `lastError = "该曲目为 ${sampleRate}Hz，高质量分离仅支持 44.1kHz"` 并回退快速模式——把静默错误变成明确提示。

**备注**：`durationMs` 用源采样率算是对的，修完重采样后要一起改回 44100。

---

## 二、P0 —— 单声道源输出错乱（帧数减半、播放翻倍速）

**位置**：`DemucsSeparator.kt:444-446`、`:452`、`:499-503`

**事实链**：

- `val channelCount = format.getInteger(KEY_CHANNEL_COUNT)` 被读出后**从未使用**（全文件仅此一处 + `DecodeResult` 字段声明）。
- `val outChannels = 2` 硬编码（:452），解码循环无条件按 L/R **成对**读取（:500-503），`totalSamples = totalFloatsWritten / outChannels`（:552）。
- 单声道流的 MediaCodec 输出是 1 声道 PCM。

**后果**：单声道源（部分老 MP3、单声道录音）会被当作「相邻两个样本 = 左右声道」处理 ⇒ 实际帧数只有真实时长的一半，而 WAV 头声明 2 声道 44100 ⇒ **播放速度翻倍、音高升八度**；同时喂给模型的是错位交织数据，分离结果无意义。

**修复建议**：按 `channelCount` 分支——单声道时把每个样本复制成 L/R 对写入（保持帧数正确），并让模型拿到合法的立体声；或明确拒绝并提示。

---

## 三、P1 —— `processSegmentFromBuffer` 无 finally，异常时 native 张量泄漏

**位置**：`DemucsSeparator.kt:582-611`

```kotlin
val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputBuf), INPUT_SHAPE)
val output = session.run(mapOf(modelInputName to inputTensor))
val outputData = output[0].value as Array<Array<Array<FloatArray>>>   // ← 强转可能抛
...
inputTensor.close()   // ← 仅成功路径可达
output.close()
```

**风险**：`session.run()` 抛异常，或 :598 的强转抛 `ClassCastException`（模型输出布局与预期不符——自定义 URL 下载的模型完全可能），则：

- 输入张量（`2 × 343980 × 4B ≈ 2.75MB` native）泄漏；
- `OrtSession.Result`（约 11MB native，含 4 stems 输出）泄漏。

异常被 `separate()` 的 `catch (e: Exception)`（:384）吞掉后返回 null，**泄漏静默累积**，每段一次。长音频（多段）下不可忽略。

**修复**：`inputTensor.use { }` / try-finally 包住，或把 `session.run` 的 Result 也用 `use`。

---

## 四、P1 —— 模型完整性只校验大小，且下载源可由用户指向任意 URL

**位置**：`ModelDownloadManager.kt:42-43`、`:72-75`、`:123-129`、`:113-114`

- `isModelDownloaded()` 仅判断 `file.length() > EXPECTED_SIZE_BYTES * 0.8`（166MB × 0.8 ≈ **133MB**）。
- 下载完成后同样只比大小（:123）。
- **无 SHA-256 / 无任何内容校验**。

**后果**：截断到 140MB 的残缺文件、内容错误但体积达标的文件、镜像返回的 HTML 错误页（若恰好够大）都会被判定为「已下载」，随后交给 ONNX Runtime 解析。而 `customUrlProvider()` 允许用户把下载地址指向**任意自建地址**（:113-114），等于引入了一条「加载任意 ONNX 模型」的路径——ONNX Runtime 解析畸形模型属已知风险面（可致崩溃，理论上可内存破坏）。

**修复建议**：

1. 记录期望 SHA-256（上游 HF 仓库可提供），下载完成后校验一次；校验失败删除并报错。
2. `isModelDownloaded()` 至少把阈值收紧到 ±1% 而非 −20%（FP16 权重文件大小是确定的，不需要 20% 余量）。
3. 若保留自定义 URL，考虑在 UI 上标注「仅使用你信任的源」。

---

## 五、P1 —— `release()` 的 `pendingRelease` 握手存在竞态窗口

**位置**：`DemucsSeparator.kt:144-155`（release）、`:388-399`（separate 的 finally）

```
release():  if (!opMutex.tryLock()) { pendingRelease = true; return }   // 放弃
separate(): try { ... } finally {
                if (pendingRelease) releaseInternal()   // ← 检查
            }                                            // ← 此后 withLock 才解锁
```

**窗口**：`release()` 若恰好在「finally 已检查完 `pendingRelease`」与「`opMutex` 解锁」之间执行，则 `tryLock()` 失败 → 置位 `pendingRelease = true` → 返回。此时：

1. **本次不再有人消费该标志** ⇒ session 未释放，166MB 模型 + ONNX 运行时驻留；
2. **标志残留** ⇒ 下一次 `separate()` 结束时会命中 `if (pendingRelease) releaseInternal()`，把刚用完的 session 意外关闭，`isReady()` 变 false，后续调用需重新初始化（用户表现为「分离成功一次后又提示需要加载模型」）。

**修复建议**：把「检查 + 释放」整体挪进 `opMutex.withLock` 保护的临界区（即用一个 `withLock` 包住，确保检查与解锁原子），或改为代际计数（generation counter）而非布尔标志。

---

## 六、P2 —— `emit()` 每帧 4 次小数组分配

**位置**：`DemucsSeparator.kt:275-282`、`:665-670`

`emit()` 每个声道样本调用一次 `shortToByteArray(...)` 并 `write(ByteArray)`。按 44100 帧/秒算，每秒 **17.6 万次** 2 字节数组分配；4 分钟歌曲约 **4200 万次**。该函数正处于 CPU 已饱和的推理热路径上，纯属额外 GC 压力（TV 盒子堆本就紧张，代码自己也做了 150MB 内存预检）。

**修复**：复用 8 字节缓冲（或按段攒一个 ByteArray 批量写），与 `decodeAudioToTempFile` 里已经采用的 64KB 批量写思路保持一致。

---

## 七、P2 —— 其余可清理项

| 项 | 位置 | 说明 |
|---|---|---|
| `OUTPUT_SHAPE` 死常量 | `DemucsSeparator.kt:62` | 声明后全文件无引用（lint 的 `UnusedResources` 抓不到 Kotlin 私有常量，所以一直没暴露）。可直接删 |
| 未校验模型输入 shape | `:121-122` | `inputName` 取 `inputInfo.keys.firstOrNull()`，多输入模型下取「第一个」是任意的；且从未校验模型实际输入 shape 与硬编码的 `INPUT_SHAPE` 是否一致。建议在 `initialize()` 里校验并在不一致时直接失败，而不是跑到第 N 段推理时才崩 |
| `totalSegments` 与实际迭代数可能不一致 | `:267` | `ceil(totalSamples/hop)` 是估算，实际循环因末段 `segLen > hop` 会多跑一轮。仅影响进度百分比，非正确性问题 |
| onnxruntime 原生库非 16KB 页对齐 | `build.gradle.kts:217` | lint `NativeLibraryAlignment` × 3，`com.microsoft.onnxruntime:onnxruntime-android:1.17.1` 三个 ABI 均未对齐。当前 targetSdk 34 + 侧装不阻塞；升 targetSdk 35 或上架需换版本 |
| `deleteModel` 与下载并发 | `ModelDownloadManager.kt:214` | 下载中删除模型：最终文件被删后又被 `renameTo` 重建，用户看到「删了又回来」。UX 问题，非数据损坏 |
| `lastError` 非 `@Volatile` | `DemucsSeparator.kt:79` | 当前所有读取点都紧跟 `withContext`（协程调度天然建立 happens-before），**实际安全**；但属于隐性契约，若将来出现非协程读取点会立刻变成可见性 bug。可加 `@Volatile` 消除隐患 |

---

## 八、已核对为**正确**的设计（不建议改动）

避免后人误"修"：

1. **`OrtEnvironment` 故意不 close**（`:157-168`）：`getEnvironment()` 是进程级单例，关掉后同进程再也无法创建任何 ONNX 会话，而 TV 上 `PlaybackService` 启停不重启进程。原注释已说清，判断正确。
2. **`opMutex` 单飞 + 会话快照**（`:206`、`:222-225`）：`separate()` 全程用局部引用，避免 `release()` 置空字段导致 NPE；`opMutex` 同时挡住并发写同一 WAV。
3. **末段缓冲区清零**（`:295-300`）：不做的话会把上一段陈旧采样当末段内容送进模型，这个修复是必要的。
4. **overlap-add 边界处理**（`:307-363`）：逐行核对过——`hop = SEGMENT - OVERLAP`、`pending` 保存 `[hop, hop+tailLen)`、末段 flush 用 `gi = startSample + j` 配 `origIdx = hop + j` 与 `segmentInputBuf` 对齐，以及 `segLen <= hop` 时 `pending = null` 不会造成丢帧（该轮已写满 `[0, segLen)`）。**均正确**。
5. **失败删除残缺 WAV**（`:393-396`）：避免「44 字节头 + 部分 PCM」被伴奏缓存误判为有效。必要。
6. **临时 PCM 文件在 finally 删除**（`:389`）、**MediaCodec/MediaExtractor 异常路径也释放**（`:569-571`）、**取消单独 catch 并 rethrow**（`HqSeparationOrchestrator.kt:426-429`）：均正确。
7. **`_modelDownloading` 重复点击防护**（`DownloadViewModel.kt:238`）：ViewModel 方法只在主线程调用，read-then-set 无竞态，有效。
8. **内存预检**（`:213-220`）+ **PCM 落盘而非驻留堆**（`:408`）：166MB 模型 + 大曲目下这套取舍是对的。

---

## 九、建议处置顺序

| 优先级 | 项 | 预估 |
|---|---|---|
| 1 | 采样率归一化（§一）——影响所有 48kHz 曲目，用户可感知 | 2–3h |
| 2 | 单声道分支（§二） | 0.5h |
| 3 | 张量 finally 释放（§三） | 0.5h |
| 4 | `pendingRelease` 竞态（§五） | 0.5h |
| 5 | 模型 SHA-256 校验（§四） | 1h |
| 6 | `emit()` 批量写 + 死常量 + 输入 shape 校验（§六、§七） | 1h |
| — | 16KB 对齐：升 targetSdk 35 前处理即可 | 待定 |

---

## 十、审查方法与可复现性

- 静态通读全部 5 个相关文件；对 overlap-add 与末帧 flush 做了逐分支推演。
- 用 grep 交叉验证关键怀疑点：`channelCount` 是否被使用（否）、`OUTPUT_SHAPE` 是否被使用（否）、全项目是否存在重采样（无）、`downloadModel` 调用方与并发防护（有防护，已排除误报）。
- **未做**：真机/模拟器实际跑一次分离（本机 `testDebugUnitTest` 与资源管线受 Gradle 守护进程问题阻塞，且本模块无单测覆盖）。**§一、§二 的结论基于代码推演，建议在真机上用一首 48kHz 曲目和一首单声道曲目各验证一次再动手改。**
