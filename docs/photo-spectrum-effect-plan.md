# 照片频谱效果库开发方案

> **状态**：待排期（2026-09-23 评估完成，用户决定「只要评估，暂不开发」）
> **版本基线**：v2.36.7
> **评估日期**：2026-09-23

---

## 一、需求概述

在现有可视化（35 个频谱效果）基础上，新增一套**照片效果库**：用动态转场效果轮播展示系统相册中的照片（**不含视频**）。

| # | 需求 | 用户口径（2026-09-23 确认） |
|---|---|---|
| 1 | 频谱效果库，用动态效果切换显示照片 | 照片来源 = **三个**：**图库**（手机）/ **外接存储 USB·SD 卡**（电视主通道）/ **Jellyfin**；三者各自开关、可任意组合 |
| 2 | 启动前提示读取照片库权限，一键允许，授权后不再询问 | 见 §九（Jellyfin 通道**无需授权**） |
| 3 | 很多种图片切换效果 + 随机变化 | 「切换效果」= **照片之间的转场动画**；随机粒度与随机池见 §5.9 |
| 4 | 能否简单判断是否有人？评估资源消耗 | 「人」= **照片里有没有人脸** |
| **5** | **能指定读取 USB 的哪个目录** | 设置项：`photoWallDirUri`（SAF 授权）+「仅扫 DCIM/Pictures」开关 |
| **6** | **是否只显示含人像的照片** | 设置项：`photoWallFacesOnly`（依赖需求 4 的人脸检测结果） |
| **7** | **设置集中管理** | 新增设置分区「**照片墙**」承载全部上述设置 |
| **8** | **三个来源各自独立开关，可任意组合** | 图库 / 外接存储（USB·SD 卡）/ Jellyfin 三个**独立开关**；**开几个就混几个**，合并成一个池随机展示（见 §6.8） |
| **9** | **原「总开关」取消** | ✅ 用户决定：总开关**降级为手机端「图库」开关** —— 不打勾 = 不启用图库、**不申请权限**；打勾才弹授权（见 §7.4） |
| **10** | **照片停留时长可配置，默认 8 秒** | ✅ 用户决定：设置项 `photoWallHoldMs`（范围 3–30s，默认 `8000` ms）；与音乐速度**无关**（§5.3 / §5.5） |

**核心原则**：照片转场**复用现有可视化架构**（`VisualizerRenderer` / `RendererSwapper` / `AudioFrame`），不新建一套渲染管线。音频分析层**零改动**。

⚠️ **平台差异是本需求的一等约束**（不是附加项）：

| 来源 | 手机 | 电视（API 22 / 5.1.1） | 授权 | 可指定目录 |
|---|---|---|---|---|
| **图库**（`MediaStore.Images`） | ✅ | ❌ **电视无图库** | 手机**运行时权限**（可拒绝） | ❌ |
| **外接存储**（USB / SD 卡） | ✅ **用户指定目录** | ✅ **电视主通道**（自动探测） | 电视**安装时已授予**；手机 / 电视的「指定目录」走 **SAF URI 授权** | ✅ **可指定目录**（**手机必填**） |
| **Jellyfin** | ✅ | ✅ | **无需权限**（复用已登录 token） | ❌（服务端建库） |

⇒ **手机 3 个来源 / 电视 2 个来源**（无图库）。
三者的**枚举方式、路径来源、授权流程都不同**，必须统一抽象（见 §六）；
且**三者可任意组合、开几个混几个**（见 §6.8）。

---

## 二、现状盘点（已有基础设施）

评估中最有价值的发现：**约 90% 的基础设施已存在**。

| 能力 | 位置 | 状态 |
|---|---|---|
| 可插拔渲染器接口 | `visualizer/VisualizerRenderer.kt` | ✅ 设计上即「新增效果 = 实现类 + 枚举值」 |
| 效果注册表 | `VisualizerRendererFactory.kt` | ✅ 35 个 when 分支 |
| 效果枚举（分档） | `data/model/AppSettings.kt` `VisualizerTheme` | ✅ 35 项，`Tier.BASIC/ADV/ULTRA` + `VisualQuality.supports` 门控 |
| **交叉淡入** | `visualizer/RendererSwapper.kt` | ✅ 含单测 —— 照片转场的核心机制 |
| 帧循环宿主 | `ui/components/VisualizerStage.kt`（583 行） | ✅ `withFrameNanos` |
| 频谱/节拍数据 | `visualizer/AudioFrame.kt` | ✅ `spectrum` / `waveform` / `bass` / `mid` / `treble` / `energy` / `sectionEnergy` / **`beat`** / **`pulse`** / **`bpm`** |
| 节拍检测 | `visualizer/BeatDetector.kt` | ✅ |
| 段落能量追踪 | `visualizer/SectionEnergyTracker.kt` | ✅ |
| **独立随机源** | `visualizer/VisualizerRandom.kt` | ✅ 有 seed、双层序列互不消耗 |
| 粒子池 | `visualizer/ParticlePool.kt` | ✅ 可用于停留期叠加 |
| 封面调色板 | `visualizer/CoverPalette.kt` + `CoverPaletteProvider.kt` | ✅ 可复用于照片主色提取 |
| 图片加载 | Coil 2.5.0（`coil-compose`） | ✅ |
| 位图降采样范式 | `backend/local/LocalCoverExtractor.kt` | ✅ `inJustDecodeBounds` + `inSampleSize` |
| 音频 MediaStore 扫描 | `backend/local/MusicScanner.kt` | ✅ 照片可照此写 `PhotoScanner` |
| 权限基础设施 | `util/PermissionHelper.kt` + `MainActivity`（178–200 行） | ✅ 请求链路现成（⚠️ 那两处是 `onCreate` **无条件**请求，照片权限**不能**照搬，见 §9.4） |
| **ONNX 推理** | `player/DemucsSeparator.kt` + `onnxruntime-android:1.17.1` | ✅ **依赖已在**（人脸检测可直接用） |
| **USB 文件遍历范式** | `backend/local/MusicScanner.kt` `scanPath()` | ✅ 注释即写「USB 挂载点专用」，纯 `File.walkTopDown` + `.nomedia` 过滤，**照片可照搬** |
| USB 插拔监听 | `backend/local/StorageMonitor.kt` | ⚠️ 广播链路可用，但**设备枚举在 API 22 上失效**（见 G7） |
| USB 定向扫描 | `backend/local/LocalMusicRepository.scanUsbDevice()` | ⚠️ 实现完好，但**在电视上永不被触发**（见 G7） |
| SAF 目录选择 | `MainActivity` `OpenDocumentTree`（79 行） | ✅ 已可用，但**目前只服务「导出」**（`exportCoordinator.onTreeGranted`），需扩展出「导入」通道 |

### 缺口清单

| # | 缺口 | 说明 |
|---|---|---|
| G1 | `RenderContext.cover` 只有**单张**位图 | 照片轮播需要「当前 + 下一张」双缓冲 |
| G2 | 无照片扫描 | `MusicScanner` 只扫音频；但其 `scanPath()` 的 USB 文件遍历范式**可直接复用** |
| G3 | 无 `READ_MEDIA_IMAGES` 权限声明 | API 33+ 必需 |
| G4 | 无「自动/随机切换」机制 | `AUTO_DIRECTOR` 档已删除（见 `AppSettings.kt:108` 注释「AUTO_DIRECTOR 档已删除」；⚠️ **项目中不存在 `VisualizerTheme.kt`**） |
| G5 | 无转场策略层 | 现为「1 效果 = 1 渲染器类」，76 种转场照此会类爆炸 |
| G6 | 无 assets 目录 | 人脸模型（337KB）需新建 assets 打包 |
| **G7** | ⚠️ **电视上 USB 链路是断的（既有缺陷，非本需求引入）** | 见下方专节 |
| **G8** | SAF 只有「导出」方向 | `OpenDocumentTree` 目前只服务导出，需扩展出「导入照片目录」通道 |

### ⚠️ G7 专节：电视上 USB 存储枚举失效（既有缺陷）

`StorageMonitor.refreshStorageDevices()` 首行即短路：

```kotlin
// StorageManager.getStorageVolumes() 需要 API 24+，低版本跳过
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
    _storageDevices.value = emptyList()   // ← API 22 永远走到这里
    return
}
```

**断链推导**（已逐环核对源码）：

```
API 22 → storageDevices = emptyList()
       → 广播回调里 filter { isMounted && type == USB } 结果为空
       → _onDeviceMounted.tryEmit() 从不执行
       → MainViewModel.kt:893 的 localMusicRepository.scanUsbDevice(device.path) 永不调用
       → 电视上插 U 盘，音乐扫不到
```

**影响**：这是**既有缺陷**（`scanUsbDevice` 实现本身是完好的，只是没人触发），
与本需求无关，但**照片功能会踩同一个坑** —— 电视读 USB 照片必须先把这条链路修通。

✅ **已确定纳入本次范围**（用户 2026-09-23 确认「一起修掉」，见 §13.1-4）。

**修复方案（成本很低）**：`ACTION_MEDIA_MOUNTED` 广播的 `intent.data` **就是挂载点 URI**（`file:///mnt/usb0`）。
现有代码第 64 行已经在打这个日志，却没用它：

```kotlin
AppLog.d(TAG, "Device mounted: ${intent.data}")   // ← data 就是挂载点，白白丢掉了
```

⇒ 在广播回调里直接用 `intent.data?.path` 构造 `StorageDevice`，即可绕过 `getStorageVolumes()` 的 API 24 限制。
（另可叠加常见路径探测 `/mnt/usb*`、`/storage/usb*`、`/mnt/media_rw/*` 作兜底。）

---

## 三、目标架构

```
数据层
├─ PhotoSource（三来源，各自独立开关）
│    ├─ MediaStorePhotoSource   图库     · 手机（需授权，由开关触发）
│    ├─ ExternalFilePhotoSource    外接存储 · USB / SD 卡（用户指定目录）
│    │      └ 取路径方式：File 遍历（主）/ SAF 目录授权（兜底）
│    └─ JellyfinPhotoSource     Jellyfin · 复用已登录 token，无需权限
├─ PhotoSourceAggregator  取「开关打开的来源」→ 各自扫描 → 跨来源去重 → 合并成一个池（§6.8）
├─ PhotoBuffer            后台解码 → inSampleSize 降采样 → 双缓冲(A/B) → LRU
└─ FaceScanManager        ONNX + YuNet 后台批处理 + hasFace 缓存

                          ▼  photoA / photoB / blend

渲染层（复用现有）
├─ RenderContext（扩展 3 字段）
├─ PhotoRenderer（1 个类，统一驱动）
│    ├─ PhotoTransition 策略库（76 种，参数化）
│    └─ TransitionClock 时序状态机（ENTER / HOLD / EXIT）
└─ VisualizerRendererFactory（加枚举值 + 分支）
```

**关键设计决策**：转场做成**策略层**（`PhotoTransition` 接口 + 参数化实现），而非独立渲染器类。
否则 76 种转场 = 76 个类，未来再乘视觉风格会彻底失控。

📁 **逐文件清单（新增 23 个 / 修改 13 个，含包路径、预估行数、依赖顺序）见 §14.1。**

---

## 四、转场效果库

### 4.1 实现机制分档（关键技术约束）

⚠️ **本项目用 Compose `DrawScope` 绘制（CPU/Skia），没有 AGSL shader**（AGSL 需 API 33+，本项目 minSdk 22）。
**逐像素 CPU 处理不可行** —— 1080p 单帧 200 万像素，逐像素运算会掉到个位数帧率。

因此所有效果必须归入以下五种机制之一：

| 机制 | 手段 | 单帧成本 | 可用性 |
|---|---|---|---|
| **M1 裁剪遮罩** | `clipRect` / `clipPath` + `alpha` | 极低 | 全版本 |
| **M2 变换** | `drawImage` + matrix / scale / rotate / `alpha` | 低 | 全版本 |
| **M3 遮罩位图** | 预生成遮罩 + `BlendMode.DstIn/DstOut` | 中（1 次合成） | 全版本 |
| **M4 分块** | 多次 `drawImage` 带偏移/裁剪 | 中（∝ 块数） | 全版本，块数需限制 |
| **M5 Shader** | AGSL `RuntimeShader` | 低（GPU） | ⚠️ **仅 API 33+**，老设备需降级 |

**结论**：溶解/扭曲类**不能**用逐像素实现，必须走 **M3（预生成遮罩位图）**；
真正的 shader 效果（M5）在电视（API 22）上不可用，须提供 M3 降级版或从可用列表剔除。

### 4.2 完整效果清单（76 种）

标注：**机制** / **优先级**（P0 = 必做，P1 = 推荐，P2 = 可选）

#### A. 淡化类（4）

| # | 标识 | 名称 | 机制 | 优先级 |
|---|---|---|---|---|
| 1 | `CROSSFADE` | 交叉淡化 | M1 | **P0** |
| 2 | `FADE_BLACK` | 经黑场 | M1 | **P0** |
| 3 | `FADE_WHITE` | 白闪 | M1 | P1 |
| 4 | `FADE_COLOR` | 主题色过渡（取 `CoverPalette` 主色） | M1 | P1 |

#### B. 滑动 / 位移类（9）

| # | 标识 | 名称 | 机制 | 优先级 |
|---|---|---|---|---|
| 5 | `SLIDE_LEFT` | 左滑 | M2 | **P0** |
| 6 | `SLIDE_RIGHT` | 右滑 | M2 | **P0** |
| 7 | `SLIDE_UP` | 上滑 | M2 | **P0** |
| 8 | `SLIDE_DOWN` | 下滑 | M2 | **P0** |
| 9 | `PUSH` | 推挤（两图同步移动） | M2 | P1 |
| 10 | `COVER` | 覆盖（新图滑入，旧图静止） | M2 | P1 |
| 11 | `REVEAL` | 揭示（旧图滑出，新图静止） | M2 | P1 |
| 12 | `SLIDE_DIAGONAL` | 对角滑动 | M2 | P1 |
| 13 | `PARALLAX_SLIDE` | 视差滑动（双层不同速度） | M2 | P2 |

#### C. 缩放 / 深度类（7）

| # | 标识 | 名称 | 机制 | 优先级 |
|---|---|---|---|---|
| 14 | `ZOOM_IN` | 缩放进入 | M2 | **P0** |
| 15 | `ZOOM_OUT` | 缩放退出 | M2 | **P0** |
| 16 | `CROSS_ZOOM` | 交叉缩放（旧图放大出画 + 新图放大入画） | M2 | P1 |
| 17 | `ZOOM_THROUGH` | 穿越（放大旧图至穿越点，新图自中心展开） | M2 | P1 |
| 18 | `DEPTH_BLUR` | 景深虚化过渡（模糊副本 + 渐变遮罩） | M3 | P1 |
| 19 | `PERSPECTIVE_PUSH` | 3D 纵深推拉（Z 轴 + 透视） | M2 | P2 |
| 20 | `DOLLY_ZOOM` | 希区柯克变焦（缩放 + 反向位移） | M2 | P2 |

#### D. 遮罩形状类（10）

| # | 标识 | 名称 | 机制 | 优先级 |
|---|---|---|---|---|
| 21 | `IRIS_CIRCLE` | 圆形光圈 | M1 | **P0** |
| 22 | `IRIS_DIAMOND` | 菱形展开 | M1 | P1 |
| 23 | `IRIS_STAR` | 星形展开 | M1 | P1 |
| 24 | `IRIS_HEXAGON` | 六边形蜂巢 | M1 | P1 |
| 25 | `IRIS_TRIANGLE` | 三角形展开 | M1 | P2 |
| 26 | `SHAPE_RANDOM` | 随机形状池（每次随机选形状） | M1 | P1 |
| 27 | `WIPE_LINEAR` | 线性擦除（支持 8 方向） | M1 | **P0** |
| 28 | `WIPE_CLOCK` | 时钟擦除（径向扫描） | M1 | P1 |
| 29 | `WIPE_SPIRAL` | 螺旋擦除 | M1 | P2 |
| 30 | `WIPE_CROSS` | 十字 / 双向擦除 | M1 | P1 |

#### E. 条纹 / 分块类（8）

| # | 标识 | 名称 | 机制 | 优先级 |
|---|---|---|---|---|
| 31 | `BLINDS_H` | 横向百叶窗 | M4 | **P0** |
| 32 | `BLINDS_V` | 竖向百叶窗 | M4 | **P0** |
| 33 | `CHECKERBOARD` | 棋盘格 | M4 | P1 |
| 34 | `BLOCKS_RANDOM` | 随机方块消融 | M4 | P1 |
| 35 | `GRID_FLIP` | 网格 3D 翻转 | M4 | P2 |
| 36 | `MOSAIC` | 马赛克渐显 | M4 | P2 |
| 37 | `TILE_CASCADE` | 瓦片错落（逐块延迟） | M4 | P1 |
| 38 | `PUZZLE` | 拼图碎片 | M4 | P2 |

> ⚠️ **M4 性能红线**：块数 × 每块 1 次 drawImage。建议**总块数 ≤ 24×14（336 块）**，
> 老电视（ARMv7）降至 ≤ 12×7（84 块）。超限时自动合并块。

#### F. 溶解 / 噪点类（6）

| # | 标识 | 名称 | 机制 | 优先级 |
|---|---|---|---|---|
| 39 | `NOISE_DISSOLVE` | 噪声溶解（柏林噪声遮罩位图） | M3 | **P0** |
| 40 | `THRESHOLD_SWEEP` | 阈值扫过（亮度阈值推进） | M3 | P1 |
| 41 | `SCANLINE_DISSOLVE` | 扫描线溶解 | M3 | P1 |
| 42 | `GRAIN_DISSOLVE` | 颗粒溶解 | M3 | P2 |
| 43 | `PIXELATE` | 像素化过渡 | M4 | P2 |
| 44 | `HALFTONE` | 半调网点 | M3 | P2 |

> ⚠️ 溶解类的遮罩位图**预生成一次**（256×256 灰度），运行时仅做 `BlendMode` 合成 —— 零逐像素成本。

#### G. 扭曲 / 形变类（8）

| # | 标识 | 名称 | 机制 | 优先级 |
|---|---|---|---|---|
| 45 | `RIPPLE` | 波纹扭曲 | M5 / M3 降级 | P2 |
| 46 | `WAVE_WARP` | 波浪位移 | M5 / M3 降级 | P2 |
| 47 | `SWIRL` | 漩涡 | M5 / M3 降级 | P2 |
| 48 | `LIQUIFY` | 液化 | M5 | P2（老设备剔除） |
| 49 | `KALEIDO` | 万花筒转场 | M4（镜像分块） | P1 |
| 50 | `SHATTER` | 玻璃破碎 | M4 | P2 |
| 51 | `VORONOI` | Voronoi 碎片化 | M4 | P2 |
| 52 | `MELT` | 融化流淌 | M5 / M3 降级 | P2 |

#### H. 色彩 / 光效类（7）

| # | 标识 | 名称 | 机制 | 优先级 |
|---|---|---|---|---|
| 53 | `LIGHT_SWEEP` | 光扫（高光扫过） | M2 + `BlendMode` | **P0** |
| 54 | `CHROMATIC_SPLIT` | RGB 色彩分离 | M2（3 层偏移） | P1 |
| 55 | `RGB_SLIDE` | 色彩分离滑入 | M2 | P1 |
| 56 | `EXPOSURE_FLASH` | 曝光闪白 | M1 | P1 |
| 57 | `BLOOM_TRANSITION` | 光晕绽放 | M2 + `BlendMode` | P2 |
| 58 | `COLOR_BURN` | 色彩烧灼 | M3 | P2 |
| 59 | **`SPECTRUM_WIPE`** | **频谱擦除**（擦除边界 = 实时频谱包络） | M1 + 频谱 | **P0** ★ |

#### I. 音频反应类（5）★ 特色但**默认不启用**

⚠️ 这类效果的**形态**由音频数据驱动，但**切换时机仍走独立定时**（见 §5.5 决策）。
它们是「可选彩蛋」—— 用户主动选中才生效，**不是默认行为**。

| # | 标识 | 名称 | 驱动数据 | 机制 | 优先级 |
|---|---|---|---|---|---|
| 60 | `SPECTRUM_BARS` | 频谱条带切换（新图按柱高逐条进入） | `spectrum[]` | M4 | P1 ★ |
| 61 | `BEAT_CUT` | 节拍硬切（无动画，卡点直切） | `beat` | M1 | P1 ★ |
| 62 | `BASS_BLOOM` | 低频绽放（`bass` 峰值触发径向展开） | `bass` | M1 | P2 |
| 63 | `WAVEFORM_WIPE` | 波形擦除（边界 = 实时波形） | `waveform[]` | M1 | P2 |
| 64 | `PULSE_DISSOLVE` | 脉动溶解（`pulse` 驱动遮罩推进） | `pulse` | M3 | P2 |

⚠️ 这 5 种**从 P0 降为 P1/P2** —— 「不卡节拍」的决策（§5.5）使它们不再是核心卖点。

#### J. 风格化类（7）

| # | 标识 | 名称 | 机制 | 优先级 |
|---|---|---|---|---|
| 65 | `GLITCH` | 故障风（RGB 位移 + 扫描线 + 块错位） | M4 + M2 | P1 |
| 66 | `FILM_ROLL` | 胶片卷动 | M4 | P2 |
| 67 | `CINEMATIC_BARS` | 电影黑边收缩 | M1 | P1 |
| 68 | `PAGE_FLIP` | 3D 翻页 | M2（matrix 变形） | P2 |
| 69 | `MATRIX_OVERLAY` | 数字雨覆盖 | M2 + 粒子 | P2 |
| 70 | `NEON_TRACE` | 霓虹描边 | M2 | P2 |
| 71 | `COMIC_PANEL` | 漫画分格 | M4 | P2 |

#### K. 有机 / 模拟类（5）

| # | 标识 | 名称 | 机制 | 优先级 |
|---|---|---|---|---|
| 72 | `INK_SPREAD` | 泼墨扩散 | M3 | P2 |
| 73 | `WATERCOLOR` | 水彩晕染 | M3 | P2 |
| 74 | `SAND_DISSOLVE` | 沙化 | M3 | P2 |
| 75 | `BURN` | 火焰燃烧 | M3 | P2 |
| 76 | `FROST` | 冰冻结晶 | M3 | P2 |

### 4.3 数量汇总

| 优先级 | 数量 | 说明 |
|---|---|---|
| **P0** | **15** | 覆盖全部 5 种机制，能验证性能与内存边界 |
| P1 | 28 | 效果丰富度主体 |
| P2 | 33 | 高级效果，含需 M3 降级项 |
| **合计** | **76** | 其中音频反应类 5 种（**可选**，见 §5.5） |

### 4.4 M5 shader 效果的降级策略（✅ 已确定：提供 M3 近似）

**决策（2026-09-23 用户确认）**：M5（AGSL shader，需 API 33+）效果在电视（API 22）上
**提供 M3（预生成遮罩位图）降级近似**，而不是从列表剔除。

| M5 效果 | 老设备 M3 降级近似 | 视觉差距 |
|---|---|---|
| `RIPPLE` 波纹扭曲 | 同心圆遮罩位图 + 逐环延迟 | 无真实位移扭曲，但波纹扩散感保留 |
| `WAVE_WARP` 波浪位移 | 正弦条带遮罩 + 相位偏移 | 无位移，但波浪推进感保留 |
| `SWIRL` 漩涡 | 螺旋遮罩位图旋转展开 | 无旋转扭曲，但螺旋展开感保留 |
| `MELT` 融化流淌 | 上密下疏渐变遮罩 + 下垂偏移 | 无流淌形变，但「融化」方向感保留 |
| `LIQUIFY` 液化 | ⚠️ 无合理近似 → **降级为 `NOISE_DISSOLVE`** | 差距明显，设置页需标注 |

**实现要点**：
- 遮罩位图**预生成一次**（256×256 灰度），运行时仅 `BlendMode` 合成 → **零逐像素成本**
- 运行时按 `Build.VERSION.SDK_INT >= 33` 选择 M5 或 M3 路径
- ⚠️ 降级后效果名称不变，但设置页转场选择器应标注「（简化版）」
- ⚠️ 降级路径需**单独单测**：同一 mask 参数下，M5 / M3 两条路径的进度曲线必须一致

---

## 五、切换流程时序设计（进入 — 停留 — 退出）

### 5.1 状态机

```
[IDLE] ──trigger──▶ [ENTER] ──done──▶ [HOLD] ──done──▶ [EXIT] ──▶ 回到 [IDLE]

- 触发源：定时器（主）／手动切图／beat（可选，**默认不卡节拍** —— 见 §5.5）
- ENTER / EXIT：由转场策略驱动；HOLD：停留期运动（Ken Burns / 呼吸 / 脉冲）+ 可选粒子叠加（§5.6）
```

**阶段语义**

| 阶段 | 作用 | 参与层 |
|---|---|---|
| **ENTER（进入）** | 新图入场动画 | 转场策略 |
| **HOLD（停留）** | 静态展示 + 停留期运动 + 可选叠加 | 运动策略 + 粒子 |
| **EXIT（退出）** | 旧图退场动画 | 转场策略 |

### 5.2 两种流程模型

**模型 1 — 串行（Sequential）**

```
时间轴 ▶
旧图  |------HOLD------|--EXIT--|
                                  （间隙：黑场/白场）
新图                              |--ENTER--|------HOLD------|
```

- 阶段分明，EXIT 与下一 ENTER 之间有间隙
- 适用：`FADE_BLACK` / `FADE_WHITE` / `CINEMATIC_BARS`（需要「暗场」语义的效果）

**模型 2 — 交叉（Crossfade）★ 默认**

```
时间轴 ▶
旧图  |------HOLD------|--EXIT--|
                       ↕ 重叠（同时进行）
新图                   |--ENTER--|------HOLD------|
```

- 旧图 EXIT 与新图 ENTER **同时进行**，无黑场，画面连续
- 适用：绝大多数效果
- ⛔ **不能复用 `RendererSwapper`** —— 见下方「两处最容易搞错的『复用』」

**⛔ 两处最容易搞错的「复用」**（2026-09-23 核对源码后修正 —— 原写「`RendererSwapper` 已有 crossfade 语义 → 直接复用」是**错的**）：

| 误解 | 事实 |
|---|---|
| 「照片 A/B 交叉淡入可直接用 `RendererSwapper`」 | ❌ `RendererSwapper` 交换的是**主题级渲染器**（`VisualizerRenderer` 实例），不是同一个渲染器内的两张照片。照片 A/B 交叉**必须在 `PhotoRenderer` 内自实现**（两张 `ImageBitmap` 各按 alpha 叠绘，§14.2.4） |
| 「`RendererSwapper` 的 crossfade 是现成可用的」 | ⚠️ 它的 crossfade 分支**当前在 UI 层被硬编码关闭**：`VisualizerStage.kt:139-141` 的注释写明「自动导演档删除后 UI 层已无使用场景，因此恒为 `false`」⇒ 这条路径**目前没有真实调用者**，只有单测覆盖（`RendererSwapperTest.kt` 存在） |

✅ `RendererSwapper` 真正的复用点是**另一件事**：从别的主题切到 `PHOTO_WALL`（或离开）时的 **600ms 主题级交叉**；
⚠️ 但要走这条路径需先把 `VisualizerStage.kt:141` 的 `crossfade` 参数改回 `true`。

### 5.3 时长参数表

**ENTER 时长（按效果类别）**

| 效果类别 | 默认时长 | 缓动 | 理由 |
|---|---|---|---|
| 淡化 | 0.8s | `easeInOutQuad` | 柔和，不宜太快（⚠️ §5.4 无裸 `easeInOut`，见 §14.3） |
| 滑动 / 位移 | 0.5s | `easeOutCubic` | 干脆利落，拖沓会显笨重 |
| 缩放 / 深度 | 0.7s | `easeInOutCubic` | 制造重量感 |
| 遮罩形状 | 0.7s | `easeInOutQuad` | — |
| 条纹 / 分块 | 0.9s **+ 逐块 stagger 0.3s** | 每块 `easeOut` | 总时长 = 1.2s |
| 溶解 / 噪点 | 1.0s | `linear` | 溶解需均匀，缓动会破坏质感 |
| 扭曲 / 形变 | 0.7s | `easeInOutSine` | — |
| 色彩 / 光效 | 0.6s | `easeOutQuad` | — |
| 音频反应类 | 0.7s | `easeInOutSine` | 与普通效果同 —— **不卡节拍**（见 §5.5） |
| 风格化 | 0.8s | 按子类型 | — |
| 有机 / 模拟 | 1.2s | `easeOutSine` | 自然过程本就缓慢 |

**HOLD 时长**

| 模式 | 时长 | 触发条件 |
|---|---|---|
| 默认 | **8.0s** | ✅ 用户确认（2026-09-23） |
| 快切 | 3s | 用户把停留时长调到下限 |
| 长停留 | 30s | 用户把停留时长调到上限，Ken Burns 主导 |
| 用户配置 | **3–30s** | 设置项 `photoWallHoldMs` |

✅ **默认 8.0s 已由用户确认**（2026-09-23）—— 即设置项 `photoWallHoldMs` 默认值 `8000`（§7.3），
在 §7.2 布局里渲染为 `[ - ] 8.0s [ + ]`，步进可调。

⚠️ HOLD 时长**不由音乐速度决定**（见 §5.5）—— 固定可配置，与 BPM / 节拍无关。

**EXIT 时长**：交叉模式下与下一张的 ENTER 等长（同一时段内完成两件事），无需独立配置。

### 5.4 缓动函数库

需要新增（`visualizer/Easing.kt`）：

```
linear
easeInQuad / easeOutQuad / easeInOutQuad
easeInCubic / easeOutCubic / easeInOutCubic
easeInSine / easeOutSine / easeInOutSine
easeInBack / easeOutBack / easeInOutBack
easeOutElastic / easeOutBounce
stagger(index, total, maxDelay)      // 分块错落专用
```

### 5.5 与音乐的关系（✅ 已确定：独立时序 + 轻度音频反应）

**决策（2026-09-23 用户确认）**：照片轮播**不卡音乐节拍** ——
目的是「听音乐时动态浏览以前的照片，心情好」，而非「让视觉跟随音乐律动」。

**评估结论（四条理由）**：

| # | 理由 |
|---|---|
| 1 | **目的不同**：频谱效果（现有 35 个）是「强化音乐体验」；照片墙是「唤起回忆 / 营造氛围」。硬卡鼓点会**打断观感** —— 一张有纪念意义的照片没看够就被切走，体验是负面的 |
| 2 | **照片需要「阅读时间」**：照片有信息量（人 / 景 / 事件），需时间辨认。若按 16 拍算，120BPM = 8s 但 180BPM = 5.3s ⇒ **展示时长被音乐速度绑架**，而照片的信息量并没有变 |
| 3 | **不依赖 BPM 可删掉一大坨不可靠逻辑**：BPM 抖动 / 检测失败 / 拍点漂移三类问题及其对策（滑动平均 + 每拍校正 + 固定兜底）**全部消失** ⇒ 时序状态机退化为纯固定时长驱动（约少 150 行 + 相关单测） |
| 4 | **但轻度音频反应值得保留**：随 `bass` 做 ±2% 呼吸缩放，**不改变切换时机**，只让静态照片「活」一点，与音乐产生微妙呼应（`bass` 数据现成，成本极低） |

**方案对照**：

| 维度 | 结论 |
|---|---|
| **切换时机** | ✅ **独立定时**：停留时长可配置（默认 8s，范围 3–30s），**不依赖 BPM / 节拍** |
| **转场时长** | 固定可配置（默认 0.7s），不随 BPM 变化 |
| 轻度音频反应（可选，**默认关**） | 随 `bass` 呼吸缩放（`photoWallBreathe`）+ 随 `pulse` 脉冲缩放（`photoWallPulseZoom`）—— **不影响切换时机**（§7.3） |
| `BEAT_CUT` 节拍硬切 | 保留为**可选效果**（用户主动选中才卡鼓点），**不作默认** |

⚠️ **实现影响**：§5.3 时长表中「频谱专属 = 由节拍决定」一行**作废**，
所有效果统一走固定可配置时长；§5.4 缓动库不变。

### 5.6 停留期运动（HOLD 内）

⚠️ **重要区分**：Ken Burns **不是转场**，而是 HOLD 阶段的持续运动。两者不可混为一谈。

| 标识 | 名称 | 参数 | 音频反应 | 默认 |
|---|---|---|---|---|
| `KEN_BURNS` | 缓慢推近 + 平移 | 缩放 1.00 → 1.08，平移 ±3% | 否 | ✅ 开 |
| `DRIFT` | 微漂移（防静态呆板） | 平移 ±1.5% | 否 | 可选 |
| `BREATHE` | 呼吸缩放 | 缩放 ±2% | ✅ 随 `bass` | 可选 |
| `PULSE_ZOOM` | 脉冲缩放 | +1.5% 后回落 | ✅ 随 `pulse`（**非节拍同步**） | 可选 |
| `PARALLAX` | 视差层（背景 / 前景不同速度） | 双层速度比 0.5 | 否 | 可选 |
| `NONE` | 静止 | — | — | 可选 |

> ⚠️ **本次只暴露 3 种**：`KEN_BURNS`（`photoWallKenBurns`）、`BREATHE` / `PULSE_ZOOM`
> （音频反应分组下的两个开关，§7.3）。`DRIFT` / `PARALLAX` / `NONE` 为 P2 备选，**无对应字段、本次不暴露**。

**HOLD 期可选叠加**（复用 `ParticlePool`）：
- 浮尘粒子 / 光斑
- 边缘呼吸光晕（随音频能量）
- 细网格 / 暗角

### 5.7 完整时间轴示例

**场景**：停留 8.0s，转场 0.7s，效果 `CROSS_ZOOM`，运动 `KEN_BURNS`，交叉模式

```
t = 0.00s   定时器触发 → ENTER 启动（0.7s）
t = 0.00s   Ken Burns 启动（1.00 → 1.08，贯穿 ENTER + HOLD 全程）
t = 0.70s   ENTER 完成 → HOLD 启动（8.0s）
t = 0.70s   旧图 EXIT 完成（与 ENTER 同时段，此刻完成交替）
t = 0.70~8.70s   HOLD：Ken Burns 持续推进；若开启音频反应，随 bass 呼吸缩放
t = 8.70s   HOLD 结束 → 触发下一张的 ENTER + 本张 EXIT
t = 9.40s   下一张 HOLD 启动
```

⚠️ 切换由**定时器**驱动，与音乐播放位置无关 —— 换歌、暂停、切歌都不影响照片轮播节奏。

### 5.8 特殊流程变体

| 变体 | 行为 |
|---|---|
| **硬切** | `BEAT_CUT`：ENTER / EXIT 时长 = 0（用户主动选中该效果时） |
| **快切** | 用户把停留时长调到下限（3s）→ 连续快切 |
| **长停留** | 用户把停留时长调到上限（30s），Ken Burns 主导 |
| **转场打断** | 用户手动切歌 / 切图时若 ENTER 未完成 → **从当前 blend 值反向插值**，禁止从 0 重启（会跳变） |
| **无音乐时** | 照片墙不依赖 `AudioFrame` ⇒ **无音乐 / 暂停时同样可轮播**（音频反应自动失效） |
| **抽到需「暗场」的效果** | 自动切到**模型 1（串行）** —— 见 §5.9「流程模型由效果决定」 |

### 5.9 转场随机化规则（2026-09-23 补充定义）

**先澄清最容易误解的一点：ENTER 与 EXIT 不是两个独立的动画。**

| 事实 | 依据 |
|---|---|
| 一次切换 = **一个转场效果**，它**同时或先后**驱动旧图退出 + 新图进入 | §5.2（交叉 = 重叠；串行 = 先后，中间是黑/白场） |
| EXIT 时长 = 同一时段的 ENTER 时长，**无需独立配置** | §5.3 末行 |
| ENTER 与 EXIT 阶段语义不同，但由**同一个 `PhotoTransition` 实例 + 同一个进度 `p`** 驱动 | §5.1 阶段语义表 |

⇒ 「随机」抽的是**这一次切换的转场**，**不是**「入场抽一个 + 退出抽一个」。

`FADE_BLACK` 就是最好的反例：它的语义是「旧图淡到黑 → 新图从黑淡出」，
**退场与进场必须同属一个效果** —— 拆开各随机一次，就画不出「经黑场」这件事。

**那「同一张照片的入场和退场动画会不一样吗？」—— 会，但原因是另一件事：**

一张照片会参与**两次相邻的切换**（先作为「新图」进场，再作为「旧图」退场），
这两次是**两次独立抽取** ⇒ 同一张照片的入场动画与退场动画**可以不同**：

```
切换 1：A ──[左滑]──▶ B          ⇒ B 的入场 = 左滑
切换 2：B ──[圆形光圈]──▶ C       ⇒ B 的退场 = 圆形光圈
```

**随机粒度**：`photoWallRandomTransition = true` 时**每次切换重新抽**，上一次用过的效果不影响本次抽取。
（等价说法：每张照片「入场那一刻」抽定 —— 因为转场只在切换瞬间存在，HOLD 期并没有转场在跑。）

**随机池（⚠️ 必须与手动选择器完全一致，否则出现「看得到抽不到 / 抽得到选不到」）**：

| 规则 | 说明 |
|---|---|
| 池 = **当前已实现的效果** | 跟着分期走：P0 = 15 种 → P1 = 43 种 → P2 = 76 种（§十二）。§7.2 布局图里那句「仅列 P0 15 种」即此意 |
| ⛔ **排除音频反应类 5 种** | `SPECTRUM_BARS` / `BEAT_CUT` / `BASS_BLOOM` / `WAVEFORM_WIPE` / `PULSE_DISSOLVE` —— §4.2 I 类与 §5.5 已定「**用户主动选中才生效，不是默认行为**」；放进随机池等于违背该决策 |
| ⛔ **按平台用「降级后」的标识** | 电视（API 22）无 AGSL，M5 走 M3 降级（§4.4）；⚠️ `LIQUIFY` 降级后实际就是 `NOISE_DISSOLVE` ⇒ 池里要按**降级后的标识**去重，否则同一个效果会被抽到两次 |
| 池内去重 | 同一效果只出现一次。`SHAPE_RANDOM` / `WIPE_LINEAR` 这类「**内部再随机**」的效果算**一个**池元素 —— 它们内部那次随机与本节无关，别混为一谈 |

**避免连续重复**：记录**最近 2 次**用过的效果，抽到就重抽；重试 N 次仍命中则**放行**
（池很小时必须有放行，否则死循环）。⚠️ 只记 1 次会退化成 `A-B-A-B` 交替，记 2 次才压得住。约 10 行。

**流程模型由效果决定（⚠️ 原先也未定义）**：§5.2 说 `FADE_BLACK` / `FADE_WHITE` / `CINEMATIC_BARS`
需要「暗场」语义 ⇒ 这 3 种**强制走模型 1（串行）**，其余效果走模型 2（交叉）。
⇒ 需要 `PhotoTransition.requiresSequential: Boolean`（或等价映射表）；
⛔ **模型必须在「抽定转场的那一刻」确定，并贯穿本次切换全程** —— 不能在切换进行中改模型
（会把正在跑的两张图从交叉切成串行，画面跳变）。

**随机源**：复用 `visualizer/VisualizerRandom.kt`（§二：已有 seed、双层序列互不消耗），
**不要**新建 `Random()` —— 否则「随机转场」会与「随机洗牌照片」（§6.7）互相消耗随机序列。

**与 `photoWallFixedTransition` 的关系**：`photoWallRandomTransition = false` 时固定用该效果，
此时本节**除「模型映射」外的规则全部不适用**（模型映射仍生效：固定成 `FADE_BLACK` 也得走串行）。

> ✅ **2026-09-23 用户确认：只保留「随机 / 固定」两档，不加「顺序轮播」。**
> ⇒ 字段维持 `photoWallRandomTransition`（**Boolean**），**不改为 `photoWallTransitionMode` 枚举**
> （原先预留的「第三档」方案作废）。固定档长期看同一效果的单调问题，
> 由用户自己在「随机」与「指定效果」之间切换解决。

---

## 六、照片来源（三来源 · 各自开关）

⚠️ **本需求最大的平台差异**：手机有系统相册（图库），**电视没有** —— 电视照片存在 U 盘 / SD 卡里。

| 来源 | 手机 | 电视 | 扫描机制 | 授权方式 |
|---|---|---|---|---|
| **图库** | ✅ | ❌ 无 | `MediaStore.Images` 查询 | 手机运行时权限（**由开关驱动**，见 §7.4） |
| **外接存储** | ✅ **指定目录** | ✅ **主** | 手机 **SAF 遍历**；电视**文件系统遍历**（+ SAF 兜底） | 电视**安装时已授予** |
| **Jellyfin** | ✅ | ✅ | HTTP API（`IncludeItemTypes=Photo`） | **无需权限**（复用 token） |

⚠️ **三来源不是「优先级链」，而是「独立开关 + 合并池」**（✅ 2026-09-23 用户决定）—— 详见 §6.8。

### 6.1 统一抽象：`PhotoSource`

三个来源的**枚举方式、URI 形态、读取方式都不同**，必须统一抽象，上层（`PhotoBuffer` / 转场 / 人脸检测）不感知来源：

```kotlin
/** 一张照片的轻量引用（不含位图） */
data class PhotoRef(
    val id: String,          // 稳定标识（用于缓存 key / 人脸结果关联）
    val displayName: String,
    val width: Int,
    val height: Int,
    val size: Long,          // 字节数 —— 跨来源去重指纹用（§6.8）
    val lastModified: Long,  // ⚠️ 统一为「秒」：MediaStore 天然是秒；DocumentFile / Jellyfin 需 /1000
    val dateAdded: Long,
    val source: PhotoSourceKind,
)

interface PhotoSource {
    /** 列出全部照片（只读元数据，不加载位图） */
    suspend fun listPhotos(): List<PhotoRef>
    /** 打开原始字节流（供 PhotoBuffer 降采样解码） */
    suspend fun openStream(ref: PhotoRef): InputStream?
}
```

⚠️ **单位归一化的责任方**：`size` / `lastModified` / `dateAdded` 的单位转换**必须在各 `PhotoSource` 实现内完成**
（对外契约统一为「秒」），**不能留到聚合层比较时再做** —— 聚合层收到的记录来自不同实现，无从判断某条是秒还是毫秒（§6.8 坑 1）。

| 实现 | 来源 | 平台 | 底层 | 开关字段 | 优先级 |
|---|---|---|---|---|---|
| `MediaStorePhotoSource` | **图库** | 手机 | `ContentResolver.query(MediaStore.Images...)` | `photoWallGalleryEnabled` | ✅ **P0** |
| `ExternalFilePhotoSource` | **外接存储** | 手机 + 电视 | **仅外接卷（USB / SD 卡）**；**手机**：`DocumentFile.fromTreeUri` 遍历用户指定目录（+ 卷 ID 校验拒绝内部存储）；**电视**：`File.walkTopDown`（复用 `MusicScanner.scanPath` 范式，黑名单过滤内部存储）为主 + SAF 兜底 | `photoWallExternalEnabled` | ✅ **P0** |
| `JellyfinPhotoSource` | **Jellyfin** | 手机 + 电视 | Jellyfin `Items` + `Images/Primary` HTTP API | `photoWallJellyfinEnabled` | ✅ **P0**（接入成本极低，见 §6.4） |

⚠️ **`SafPhotoSource` 不再是独立来源** —— 它是**外接存储来源的第二种「取路径方式」**
（`File` 遍历失败时改走 `DocumentFile.fromTreeUri`），与「来源」不在同一个维度。
⇒ 合并后**来源只有 3 个，实现也只有 3 个**，比原设计少一层。

**`PhotoSourceKind` 枚举（3 值）**：`GALLERY` / `EXTERNAL` / `JELLYFIN`

⚠️ **`PhotoRef.id` 必须全局唯一**（人脸结果、缓存、去重都拿它当 key）：
格式建议 `"<kind>:<payload>"` —— `gallery:1234` / `local:/storage/XXXX/DCIM/a.jpg` / `jellyfin:<itemId>`。
若只存文件名，不同来源的同名文件会**互相污染人脸结果**。

**收益**：`PhotoBuffer` 只需面对 `openStream()`；人脸检测、色彩匹配、去重逻辑全部通用。

### 6.2 图库来源：MediaStore（仅手机）

仿 `MusicScanner`，只取元数据**不加载位图**：

- 字段：`_ID` / `DISPLAY_NAME` / `WIDTH` / `HEIGHT` / `DATE_ADDED` / `BUCKET_NAME`
- ⚠️ **API 版本分支**（`MusicScanner` 已有此写法）：
  - API 29+ → `MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)`
  - API 23–28 → `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`

**⚠️ 开关与授权的耦合（✅ 用户明确要求「打开了才需要授权」）**：

| 状态 | 行为 |
|---|---|
| `photoWallGalleryEnabled == false` | **不查询、不申请权限** —— 开关本身就是「同意读图库」的表达 |
| 打开开关 | 先 `checkSelfPermission`；未授权 → 弹说明 + 系统对话框 |
| ⚠️ 用户**拒绝**授权 | **开关自动回弹为关闭** —— 不能留一个「开着但没数据」的开关 |
| ⚠️ 用户事后在系统设置里**撤销**权限 | **`onResume` 即回弹并提示** —— 官方明确授权可在 `onStart` / `onResume` 之间被改（App 不重启）⇒ **不能只在启动时判一次** |

#### ⚠️ 图库（MediaStore）覆盖不到什么 —— 这决定「外接存储」来源的真实价值

**答案：不是「内部芯片上的图片都会被索引」。** MediaStore 是「**扫描器 + 数据库**」，
它会跳过一批位置与文件（2026-09-23 据 AOSP / Android 官方文档核实）：

| 不被图库索引的情况 | 说明 | 图库是否覆盖 |
|---|---|---|
| **含 `.nomedia` 的目录** | 整个目录被扫描器跳过（官方推荐的「别让我进相册」机制） | ❌ 不覆盖 |
| **隐藏目录**（`.` 开头） | 如 `.thumbnails` | ❌ 不覆盖 |
| **`Android/data/<pkg>/`** | Android 11+ 不再索引；其他 App 也读不到 | ❌ 不覆盖（**SAF 亦禁止选**） |
| **`Android/obb/<pkg>/`** | 同上 | ❌ 不覆盖（**SAF 亦禁止选**） |
| **`Android/media/<pkg>/`** | Android 11 新增的「共享媒体目录」 | ✅ **会索引** |
| **应用内部私有存储** `/data/data/<pkg>/` | 不属于外部存储（如 `getFilesDir()`） | ❌ 不覆盖 |
| **非媒体扩展名 / 无扩展名** | MediaStore 按 MIME 判定，`photo.bin` 不算图片 | ❌ 不覆盖 |
| **损坏 / 零字节文件** | 扫描时跳过 | ❌ 不覆盖 |
| **刚写入的文件** | 扫描是**异步**的，可能延迟出现 | ⏳ 延迟覆盖 |
| **Android 14+ 部分授权**（「仅选择照片」） | 照片**已索引**，但 App 只能看到用户勾选的那部分 | ⚠️ **部分可见**（见 §9.6） |

**⇒ 关键结论**：手机内部存储里，图库**唯一**漏掉的是 **`.nomedia` / 隐藏目录**；
而那些目录里放的是**功能性图片**（漫画页、App 缓存封面、下载器分片），
**不是「回忆照片」** ⇒ **排除内部存储是对的**（见 §6.3），不是妥协。

**典型场景**：各类 App 的**图片缓存**多放在自己的 `Android/data/<pkg>/` 下
（微信的聊天图片缓存即在此），**不进相册** —— 但那一类**系统禁止任何 App 读取**，
不是我们没做。

### 6.3 外接存储来源：USB / SD 卡（用户指定目录）

✅ **定位（2026-09-23 用户决定）**：**只读「外接存储卷」（USB / SD 卡），不允许选内部存储** ——
**两个平台一致**。

| 平台 | 扫描范围 | 取路径方式 | 未指定目录时 |
|---|---|---|---|
| **电视** | **仅外接卷**（USB / SD 卡）；自动探测或用户指定子目录 | `File` 遍历（主，需先修 G7）+ SAF（兜底） | 自动探测**外接卷**（过滤掉 `/storage/emulated/*`） |
| **手机** | **仅外接卷**（USB OTG / SD 卡）；⚠️ **必须由用户指定目录** | **SAF**（`DocumentFile.fromTreeUri`） | ⚠️ **该来源为空**，提示「请先选择目录」 |

**为什么排除内部存储**（理由比「省事」更强）：

| 平台 | 理由 |
|---|---|
| **手机** | 内部存储**绝大部分已被图库覆盖**；图库**唯一**漏掉的是 `.nomedia` / 隐藏目录，而那些目录里放的是**功能性图片**（漫画页、App 缓存封面、下载器分片），**根本不是「回忆照片」** ⇒ **排除它们反而是对的**，不是妥协（详见 §6.2 索引边界表） |
| **电视** | 电视**没有图库**，但内部存储里放照片的概率极低（TV 内部存储容量小、无文件管理器、无相机）⇒ 收益极小；而 API 22 上「内部 / 外部」卷的路径探测**本就不可靠**（与 G7 同一片雷区）⇒ 排除可显著降风险 |

⚠️ **代价（如实记录）**：电视上若真有照片存在内部存储，将**完全读不到**（无图库兜底）。
解法：把照片拷到 U 盘。该限制需写进 README。

#### ⛔ 强制手段：不能只靠文案，必须代码拦截

**SAF 选择器无法限制可选范围**（用户仍能进到内部存储）⇒ 必须在选完后**校验卷 ID**：

```kotlin
// DocumentsContract 的 docId 形如 "<volumeId>:<path>"；"primary" = 内部存储
val treeDocId  = DocumentsContract.getTreeDocumentId(treeUri)
val volumeId   = treeDocId.substringBefore(':')
val isInternal = volumeId == "primary"        // ⇒ 拒绝并提示
```

⚠️ `"primary"` 是 AOSP `ExternalStorageProvider` 的约定；别与 `MediaStore.VOLUME_EXTERNAL_PRIMARY`
（值是 `"external_primary"`）混用 —— **不是同一个字符串**。外接卷的 volumeId 是 UUID 形式（如 `1A2B-3C4D`）。

拒绝文案必须明确：*「请选择 U 盘或 SD 卡上的文件夹（不支持内部存储）」*。

**电视端自动探测**同样要过滤：

```kotlin
// 黑名单排除内部存储前缀
val INTERNAL_PREFIXES = listOf("/storage/emulated", "/sdcard", "/mnt/sdcard", "/storage/self")
fun isExternal(path: String) = INTERNAL_PREFIXES.none { path.startsWith(it) }
```

⚠️ **用黑名单，不要用白名单** —— 电视（API 22）的 USB 挂载点形态多样
（`/storage/XXXX-XXXX`、`/mnt/usbhost/*`、`/storage/usbotg`…），
白名单（只认某几种）会在陌生 ROM 上**漏掉整盘**。

⚠️ **去重仍然必须保留**：外接 **SD 卡通常也会被 MediaStore 索引**（相册里能看到 SD 卡照片），
⇒ 与图库重叠仍可能发生，只是概率降低（见 §6.8）。

#### ⛔ SAF 目录选择的硬限制（本项目 `targetSdk 34` ⇒ Android 11+ 规则生效）

> ⚠️ 下表是**系统强加**的限制（我们无法绕过）；
> 而「**不许选内部存储**」是**我们自己加的**产品限制（见上方「强制手段」）——
> 两者叠加后，用户实际可选范围 = **外接卷的子目录，且不能是 `Download` / `Android/data` / `Android/obb`**。

Android 11（API 30）起，`ACTION_OPEN_DOCUMENT_TREE` **不允许**选择以下位置
（系统文件选择器会**置灰**或干脆不显示）：

| 禁止选择 | 影响 |
|---|---|
| **内部存储卷的根目录** | ⛔ 用户**不能**直接选 `/storage/emulated/0` ⇒ **必须选一个子目录** |
| **可靠 SD 卡卷的根目录** | ⛔ 同理：U 盘 / SD 卡也只能选**子目录** |
| **`Download` 目录** | ⛔ 不能选（好在它**已被图库索引**，不缺） |
| **`Android/data/` 及其所有子目录** | ⛔ **完全无法访问**（含其他 App 的图片缓存） |
| **`Android/obb/` 及其所有子目录** | ⛔ 同上 |

⚠️ **对 UI 的硬要求**：「选择照片目录」入口必须**提示用户选到子目录**，
否则用户选了根目录会被系统静默拦住、不知道发生了什么。
建议文案：*「请选择存放照片的**具体文件夹**（不能选存储根目录）」*。

**两条取路径路线（按平台选择）**：

| 平台 | 主路线 | 原因 |
|---|---|---|
| **电视** | **A 文件系统遍历** | 挂载点可直接 `File` 访问，最快；API 22 权限安装时已授予 |
| **手机** | **B SAF 目录授权** | 用户指定的目录**没有可用的文件系统路径**（SAF 给的是 tree URI），只能走 `DocumentFile` |

#### 路线 A — 文件系统遍历（**电视主路线**，复用现有范式）

`MusicScanner.scanPath()` 的写法可直接照搬（该函数注释即写「USB 挂载点专用」）：

```kotlin
root.walkTopDown()
    .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
    .filter { /* 跳过隐藏目录 / .nomedia 目录 */ }
```

- ✅ **快**：纯 `File` 遍历，无 `ContentResolver` 开销
- ✅ **无需用户操作**：插盘即扫
- ✅ **API 22 权限已满足**：`READ_EXTERNAL_STORAGE` 安装时已授予
- ⚠️ **依赖挂载点路径可得** → 必须先修 G7（`StorageMonitor` 的 API 22 短路）

#### 路线 B — SAF 目录授权（**手机唯一路线** / 电视兜底）

`ACTION_OPEN_DOCUMENT_TREE`（项目已在用，但目前**只服务导出**）：

- 用户用遥控器选 U 盘目录 → `takePersistableUriPermission()` 持久化 → **之后不再需要选**（满足需求 2 的「授权后无需再次授权」）
- ✅ 不依赖 ROM 挂载路径、不依赖 MediaStore 索引
- ⚠️ `DocumentFile` 遍历比 `File` 慢（每层都要走 `ContentResolver`）
- ⚠️ 电视上需确认系统文件选择器**遥控器可操作**（部分 ROM 的 SAF UI 对 D-Pad 不友好）

#### 建议：电视 A 为主 + B 兜底；手机只走 B

```
电视：检测到 USB 挂载
  ├─ 路线 A 扫描成功且有照片 → 直接用
  └─ A 失败（路径不可见 / ROM 限制）→ 引导用户走 B（SAF 选目录）

手机：用户点「选择照片目录」→ 路线 B
  └─ 未选目录 ⇒ 该来源为空，设置页提示「请先选择目录」
```

⚠️ **SAF 的持久化对两条平台都成立**：`takePersistableUriPermission()` 后重启仍有效
（满足需求 2「授权后无需再次授权」），无需自己存「已授权」标志。

### 6.4 Jellyfin 来源：NAS 照片库（接入成本最低）

✅ **可行性确认**（2026-09-23 核实）：

| 项 | 结论 |
|---|---|
| Jellyfin 支持照片库 | ✅ **原生支持** —— 建库用 `CollectionType: photos`，源码有 `Photo` 实体（`Jellyfin.Database/.../Entities/Libraries/Photo.cs`） |
| 项目现有基础 | ✅ **两个关键 API 都已有先例**（见下表） |
| 接入成本 | ✅ **极低** —— `JellyfinPhotoSource` 约 120 行（`PhotoSource` 接口已设计好） |
| **实际数据** | ✅ **用户确认 NAS 上已存有照片**（2026-09-23），无需额外准备素材 |

**API 对照（项目已有写法 → 照片所需）**：

| 用途 | 项目现有（音乐） | 照片需要 |
|---|---|---|
| 列出条目 | `IncludeItemTypes=Audio` / `MusicAlbum` | `IncludeItemTypes=Photo` |
| 取图片 | `Images/Backdrop/0?maxWidth=512&quality=90&api_key=` | `Images/Primary?maxWidth=1920&quality=90&api_key=` |

> 现有代码位置：`backend/impl/JellyfinAdapter.kt:233`（Backdrop 拼 URL）、`buildCoverUrl(id, tag)`（Primary）

**四个额外优势**（外接存储通道没有的）：

| # | 优势 |
|---|---|
| 1 | **内存风险大幅降低** —— `maxWidth` 让**服务端降采样**，客户端拿到的就是屏幕尺寸图 ⇒ `PhotoBuffer` 的 `inSampleSize` 解码逻辑基本不需要 |
| 2 | **缓存现成** —— Coil 已配置全局 `ImageLoader`（内存 + 磁盘缓存，`NasMusicApp.newImageLoader()`），不需要自建 LRU |
| 3 | **完全绕开 G7** —— 不碰 `StorageMonitor` / USB 路径 / ROM 兼容 |
| 4 | **无需 SAF 授权** —— 走 Jellyfin `api_key`，省掉遥控器选目录那一步 |

⚠️ **前置条件**：需在 **Jellyfin 服务器上建一个「照片」类型的媒体库**（`CollectionType: photos`），指向 NAS 照片目录。
**若只建了音乐库，`IncludeItemTypes=Photo` 会返回空。** 该库与音乐库并列，不影响现有音乐功能。

**验证命令**（服务器上先确认一次，比在应用里试更快）：
```bash
curl -s "http://<jellyfin>:8096/Items?IncludeItemTypes=Photo&Recursive=true&limit=5&api_key=<TOKEN>" | head -c 500
```
返回含 `Items` 数组即通道可用。

⚠️ **限制**：依赖 NAS 在线 ⇒ **离线不可用**（与外接存储通道互补）。

**与外接存储通道对比**：

| 维度 | 外接存储（USB / SD 卡） | Jellyfin |
|---|---|---|
| 接入成本 | 中（~220 行 + G7 修复） | ✅ 低（~120 行） |
| 内存风险 | ⚠️ 高（客户端解码大图） | ✅ 低（服务端降采样） |
| 缓存 | 需自建 LRU | ✅ Coil 现成 |
| 依赖 G7 修复 | ✅ 必须 | ❌ 不需要 |
| 离线可用 | ✅ | ❌ |
| ROM 兼容风险 | ⚠️ 有（路径差异） | ✅ 无 |

### 6.5 图片格式支持（API 22 约束）

| 格式 | 支持 | 说明 |
|---|---|---|
| JPG / JPEG | ✅ | 主力格式 |
| PNG | ✅ | |
| BMP | ✅ | |
| WebP | ✅ | 有损（API 14+）；无损需 API 30+，电视不支持 |
| GIF | ⚠️ 仅静态首帧 | `BitmapFactory` 只解首帧，**无动画播放** |
| **HEIC / HEIF** | ❌ | 需 API 28+ → **电视不支持**，扫描时直接过滤掉 |
| 视频 | ❌ | 需求明确不做 |

⇒ 扫描时按扩展名过滤：`jpg/jpeg/png/bmp/webp/gif`（**排除 `heic/heif`**，否则扫到也解不出）。

### 6.6 外接存储拔插处理（USB 卸载 / 挂载）

**电视（走挂载点）**：复用 `StorageMonitor.onDeviceUnmounted`：

- 拔盘 → **立即清空 `PhotoBuffer` 的 LRU 与双缓冲**（否则会继续显示已拔出的照片）
- 拔盘 → 人脸检测任务**中断**（正在读的文件会失效）
- 插盘 → 重新扫描 + 重建列表
- ⚠️ 若当前正在播放的照片来自该盘 → 切到下一张可用照片或回退到无照片状态

**手机（走 SAF tree URI）**：拿不到卸载广播，改为**惰性失效**：

- `openStream()` 失败（`FileNotFoundException` / `SecurityException`）→ 把该 `PhotoRef` **标记为失效并跳过**
- 连续 N 张失败 → 提示「所选目录当前不可用」，暂停轮播
- ⚠️ 用户指定的目录若在**外接 U 盘**上，拔盘后**不要自动清除** `photoWallDirUri`
  —— 重新插回同一 U 盘时 URI 通常仍有效，清掉会让用户白选一次

### 6.7 播放顺序策略

| 策略 | 说明 |
|---|---|
| 顺序 | 按 `dateAdded` |
| 倒序 | 最新优先 |
| **随机洗牌** | 无重复遍历（Fisher-Yates + 游标），避免连续重复 |
| 按文件夹 | 限定目录（USB 场景常用，如只看 `DCIM`） |
| **仅含人脸** | ✅ 需求 4 的实际用途 —— 过滤 `hasFace == true` |
| **色彩匹配** | 优先选与当前封面调色板（`CoverPalette`）接近的照片，画面更协调 |
| **来源均衡** | 见 §6.8「混合公平性」—— 多来源混合时，避免小来源被大来源淹没 |

### 6.8 多来源共存：独立开关 + 合并池

> ✅ **2026-09-23 用户决定**：三个来源各配**一个独立开关**，开几个就混几个；
> **取代**原「优先级 + 回落链」设计。

**设计**：不做「优先级」，做「**集合**」—— 开关打开的来源全部参与，结果**合并成一个池**随机展示。

| 开关 | 手机默认 | 电视默认 | 说明 |
|---|---|---|---|
| **图库** | **关** | —（无此来源） | 打开才申请权限（§6.2 / §7.4） |
| **外接存储** | 关 | **开** | **仅外接卷（USB / SD 卡）**；手机端还需**指定目录**（§6.3） |
| **Jellyfin** | 关 | 关 | 需服务端已建照片库（§6.4） |

> ✅ **默认值（2026-09-23 确认，采纳建议）**：电视端「外接存储」默认**开** —— 无授权成本、
> 无隐私顾虑，插盘即用，开箱即可感知功能存在；手机端三个默认**关** —— 图库涉及隐私授权，
> 应由用户主动开启。
> ⚠️ 理由：**总开关取消后，三个都默认关时用户压根看不到「照片墙」这个效果**
> （效果列表里没有），发现成本比原方案更高。

**合并流程**：

```
读取三个开关
  ├─ 图库     开 → MediaStorePhotoSource.listPhotos()
  ├─ 外接存储 开 → ExternalFilePhotoSource.listPhotos()   ← 仅外接卷；手机：用户指定目录；电视：指定目录优先，否则自动探测
  └─ Jellyfin 开 → JellyfinPhotoSource.listPhotos()    ← 需 NAS 在线且已建照片库
        ↓
   跨来源去重（见下）
        ↓
   合并成一个 List<PhotoRef>，统一洗牌（Fisher-Yates + 游标）
```

**相比原「优先级 + 回落链」的收益**（✅ 这是本次改动最大的价值）：

| # | 收益 |
|---|---|
| 1 | **语义直白** —— 开关是开是关一目了然，不需要理解「优先 / 回落 / 仅」三套语义 |
| 2 | **「静默换源」困惑整类消失** —— 原设计要额外加「仅 XXX 不回落」+「回落必须可见」两条约束，现在都不需要 |
| 3 | **天然容错** —— 拔盘 / NAS 断连时，其余开关的来源继续工作，无需任何回落逻辑 |
| 4 | **少一个枚举** —— `PhotoSourceMode`（5 值）作废，省掉它的 Gson 前向兼容负担 |
| 5 | **少一层抽象** —— `PhotoSourceRegistry` 的优先级排序 → `PhotoSourceAggregator` 的简单聚合 |

**跨来源去重（⚠️ 必须做，非可选）**：

**为什么仍然必须**（虽然已排除内部存储，但重叠**没有消失**）：

| # | 原因 |
|---|---|
| 1 | **外接 SD 卡通常也会被 MediaStore 索引** —— 手机上相册本来就能看到 SD 卡里的照片 ⇒ 用户选了 SD 卡目录，同一张照片**出现两次** |
| 2 | **USB OTG 一般不被索引，但这只是「建议」** —— AOSP 只是**建议**设备厂商不要把瞬态卷编入索引，**厂商可自行决定** ⇒ 不能假设「外接 = 一定不重叠」 |

⇒ 去重从「大概率触发」降为「**兜底**」，但**代码不能省** —— 省掉后在索引了 SD 卡的机型上会重复。
这是「独立开关可任意组合」这个设计**必须配套**的代价。

| 情况 | 去重键 |
|---|---|
| 图库 ↔ 外接存储 | **`(size, lastModified秒, lowercase(displayName))`** |
| 外接存储 ↔ 外接存储（多卷 / 多目录） | 同上 —— **用指纹比路径更稳**（见坑 2） |
| 涉及 Jellyfin | **不去重**（服务端 `itemId` 与本机文件无对应关系；NAS 照片通常不来自本机） |

⛔ **两个必须注意的实现坑**：

| # | 坑 |
|---|---|
| 1 | **时间单位不一致** —— `MediaStore.DATE_MODIFIED` 是**秒**，`DocumentFile.lastModified()` 是**毫秒**。⛔ **归一化必须在各 `PhotoSource` 实现内完成**（`PhotoRef.lastModified` 对外统一为秒，§6.1），**不能留到比较时做** —— 否则聚合层无从判断某条记录的单位，指纹永不匹配、**去重静默失效** |
| 2 | **不要用路径去重** —— MediaStore 在 API 29+ 拿不到可靠的 `DATA` 路径；`/sdcard` 与 `/storage/emulated/0` 是同一位置的不同写法；SAF 的 tree URI 也不是路径 ⇒ **指纹比路径稳** |

去重成本 O(n) 哈希，在**扫描阶段**一次完成，不进入绘制路径。
⚠️ 指纹理论上可能撞（同尺寸 + 同一秒 + 同名）—— 概率极低，且后果只是「少显示一张」，可接受。

**混合公平性（✅ 已确定：默认自然混合，另提供「来源均衡」选项）**：

若 Jellyfin 有 5,000 张、U 盘只有 50 张，**合并池随机 ⇒ 99% 都是 Jellyfin**，
用户插上 U 盘想「重温这次旅行」却几乎看不到。

| 策略 | 行为 | 适用 |
|---|---|---|
| **自然混合**（默认） | 合并后统一洗牌 ⇒ 按各来源**数量加权** | 「把所有照片混在一起看」的直觉语义 |
| **来源均衡** | 每次先**等概率选一个来源**，再在该来源内随机 ⇒ 小来源出现频率被抬高 | 「确保每个来源都能看到」 |

⇒ ✅ **已定**：加设置项 `photoWallSourceBalance`（默认**关** = 自然混合），成本约 20 行。
开关打开即切「来源均衡」。

**实现成本**：约 100 行 —— `PhotoSourceAggregator.collect(enabled: Set<PhotoSourceKind>): List<PhotoRef>`
（聚合 + 去重 + 可选均衡）。

⚠️ **本次作废的设计**（原方案遗留，全部删除）：
- `PhotoSourceMode` 枚举（5 值）及 `photoWallSourceMode` / `photoWallAutoSource` 字段
- `PhotoSourceRegistry.resolve()` 的优先级排序
- 「回落链」—— 以及随之而来的「回落必须可见」UI 约束（改为纯展示的「已启用来源」信息行，无回落语义）
- 「仅 XXX 不静默回落」的约束

---

## 七、设置页设计（新增「照片墙」分区）

### 7.1 现有设置页机制（复用，不新建）

设置页是「**一级分区列表 + 二级详情页**」结构（`SettingsSectionList.kt` 注释即「竖屏设置页一级」）：

- 分区由 `SettingsSection` 枚举驱动，现有 **9 个**：GENERAL / PLAYBACK / DOWNLOAD / SERVER / CACHE / NETWORK / NETDISK / DATA / ABOUT
- 路由：`SettingsScreen.kt` 的 `when (activeSection) { SettingsSection.XXX -> item { XXXSection(...) } }`（475–681 行）
- 可复用控件（`SettingsComponents.kt`）：

| 控件 | 签名要点 | 用途 |
|---|---|---|
| `SettingSwitch` | `(label, description, checked, onClick, enabled)` | 开关。✅ **三个来源开关 + 各自的子项**都用它；`enabled` 用于「Jellyfin 未连接时置灰」等 |
| `SettingActionButton` | 动作按钮 | 「选择照片目录」「开始人脸检测」「重新扫描」 |
| `SettingsInfoRow` | `(label, value)` | 显示照片数量、扫描进度、当前目录 |
| `AdjustButton` | `(text, onClick)` | 转场时长 / 停留时长的 `[ - ] 值 [ + ]` |
| `SectionTitle` / `SubSectionTitle` | 分组标题 | 分区内分组 |
| `PlayModeSelector` | 多选一（当前项高亮） | 转场效果选择器**可照此写** |

⇒ **新增「照片墙」分区 = 枚举加 1 项 + 新建 1 个 Section 文件 + 路由加 1 分支 + strings 中英双份**，成本很低。

### 7.2 分区内容布局

```
照片墙（设置分区）
├─ 照片来源（可多选，开几个混几个）
│   ├─ [ ] 图库（系统相册）                      ← 仅手机；打开才申请权限
│   ├─ [x] 外接存储（USB / SD 卡）
│   │   ├─ [ 选择照片目录 ]  U盘 / SD 卡 · 手机必填 · 只能选子目录
│   │   └─ [x] 仅扫描 DCIM / Pictures 目录        ← 仅电视自动探测时生效
│   ├─ [ ] 来源均衡（避免小来源被淹没）
│   └─ [ ] Jellyfin 照片库                       ← 需 NAS 已建照片库
├─ 已启用：外接存储
├─ 照片数量：图库 0 · 外接 812 · Jellyfin 2,341
├─ 合并后（去重）：3,153 张
├─ [ 重新扫描 ]
│
├─ 显示筛选
│   ├─ [ ] 仅显示含人像的照片
│   ├─ [ 开始人脸检测 ]   进度 0 / 1,234
│   └─ [ 清除检测结果 ]
│
├─ 转场效果
│   ├─ [x] 随机切换转场效果                        ← 只有「随机 / 固定」两档，无「顺序轮播」（用户确认）
│   ├─ [交叉淡化] [左滑] [圆形光圈] [噪声溶解] …   ← 关闭随机时可选（列**当前已实现**的全部；P0 阶段 = 15 种，随机池规则见 §5.9）
│   ├─ 转场时长   [ - ]  0.7s  [ + ]
│   ├─ 停留时长   [ - ]  8.0s  [ + ]               ← 默认 8.0s，范围 3–30s（✅ 用户确认）
│   └─ 画面适配   [ 满屏 ] / [ 完整 ]              ← CROP / FIT · 四端均暴露（✅ 用户确认）
│
├─ 停留期运动
│   └─ [x] Ken Burns 缓慢推近
│
└─ 音频反应（默认关）
    ├─ [ ] 启用音频反应
    ├─ [x] 随节拍缩放      ← 置灰（受上一项控制；值本身默认 true，见 §7.3）
    └─ [x] 随低频呼吸      ← 置灰（同上）
        仅改变照片观感，不影响切换时机
```

> ⚠️ 上图为**示意**（把「仅手机」与「仅电视」的行画在了一起）。实际渲染差异：
> - **「图库」行仅手机** —— 电视端**不渲染**此行（§7.3）
> - **「仅扫描 DCIM / Pictures」仅电视自动探测时生效** —— 手机端（只走 SAF）不渲染或置灰
> - **各开关默认值按平台不同**（§6.8）：电视「外接存储」默认**开**，手机三个默认**关**
> - ✅ **「画面适配」四端均暴露**（2026-09-23 用户澄清）：语义就是「**显示图片时满屏显示、还是带黑边**」，
>   **与横屏 / 竖屏无关** —— 电视横屏放竖幅照片同样有 CROP / FIT 取舍。
>   原字段名 `photoWallPortraitFit` 带 `Portrait` 前缀是**误导**，已改名 `photoWallScaleMode`（§7.3）

### 7.3 设置项清单与持久化字段

⚠️ 字段名沿用项目现有风格（如 `downloadEnabled` / `autoDownloadOnPlay`）。

| 设置项 | 控件 | 字段（`AppSettings`） | 类型 | 默认 | 说明 |
|---|---|---|---|---|---|
| **图库**（仅手机） | `SettingSwitch` | `photoWallGalleryEnabled` | Boolean | `false` | ✅ **原「总开关」降级而来**（见 §7.4）；**打开才申请权限**；电视端不渲染此行。⚠️ Android 14+ 可能只拿到「部分授权」⇒ 需显示「重新选择照片」入口（§9.6） |
| **外接存储** | `SettingSwitch` | `photoWallExternalEnabled` | Boolean | 电视 `true` / 手机 `false` | USB / SD 卡（**不含内部存储**，见 §6.3）；✅ 默认值已确认，理由见 §6.8 |
| **Jellyfin** | `SettingSwitch` | `photoWallJellyfinEnabled` | Boolean | `false` | 需 NAS 在线**且已建照片库**；未连接 NAS 时此行置灰 |
| 已启用来源 | `SettingsInfoRow` | —（运行时派生） | String | — | 显示「外接存储」/「外接存储 + Jellyfin」，让用户确认当前生效组合 |
| **来源均衡** | `SettingSwitch` | `photoWallSourceBalance` | Boolean | `false` | 关 = 自然混合（按数量加权）；开 = 每次等概率选来源 —— 见 §6.8「混合公平性」 |
| 指定照片目录 | `SettingActionButton` | `photoWallDirUri` | String | `""` | 外接存储的目录；SAF URI，`takePersistableUriPermission` 持久化。⚠️ **手机端必填**（未选则该来源为空并提示）；电视端可留空走自动探测。⛔ **仅限外接卷（USB / SD 卡）**，且**只能选子目录**（内部存储由我们主动拒绝；卷根目录 / `Download` / `Android/data` / `Android/obb` 被系统禁止，见 §6.3） |
| 仅扫常见目录 | `SettingSwitch` | `photoWallCommonDirsOnly` | Boolean | `true` | **仅电视自动探测时生效**：限定 `DCIM` / `Pictures`，避免整盘递归过慢 |
| 照片数量（分来源） | `SettingsInfoRow` | —（运行时派生） | String | — | 「图库 0 · 外接 812 · Jellyfin 2,341 · 合并 3,153」 |
| **仅显示含人像** | `SettingSwitch` | `photoWallFacesOnly` | Boolean | `false` | 需先完成人脸检测 |
| 人脸检测进度 | `SettingsInfoRow` | `photoWallFaceScanDone` | Boolean | `false` | 显示 `已完成 N / M` |
| **随机切换转场** | `SettingSwitch` | `photoWallRandomTransition` | Boolean | `true` | 打开 = **每次切换重新抽**；随机池与排除规则见 §5.9；关闭后用下方指定效果。✅ **只有「随机 / 固定」两档，无「顺序轮播」**（2026-09-23 用户确认 ⇒ 字段维持 Boolean） |
| 指定转场效果 | 多选一（照 `PlayModeSelector`） | `photoWallFixedTransition` | `PhotoTransition` 枚举 | `CROSSFADE` | 仅随机关闭时生效；⚠️ 其**流程模型映射仍生效**（选 `FADE_BLACK` 就走串行，§5.9） |
| 转场时长 | `AdjustButton` | `photoWallTransitionMs` | Int | `700` | 300–2000 ms |
| 停留时长 | `AdjustButton` | `photoWallHoldMs` | Int | **`8000`** | ✅ **默认 8.0s**（2026-09-23 用户确认）；范围 3000–30000 ms；与音乐速度**无关**（§5.5） |
| **画面适配** | 多选一 | `photoWallScaleMode` | `PhotoScaleMode` 枚举 | `CROP` | ✅ 用户可选：`CROP`（**满屏**，裁掉超出部分）/ `FIT`（**完整**，留黑边）。⚠️ **与横竖屏无关，四端（电视 / 手机横 / 手机竖）均暴露** —— 电视横屏放竖幅照片同样有取舍。原字段名 `photoWallPortraitFit` 的 `Portrait` 前缀是误导，已改名 |
| Ken Burns | `SettingSwitch` | `photoWallKenBurns` | Boolean | `true` | 停留期运动 |
| **音频反应**（分组开关） | `SettingSwitch` | `photoWallAudioReactive` | Boolean | **`false`** | ⚠️ **默认关** —— 见 §5.5「不卡节拍」决策；关闭时下方两项置灰 |
| 随节拍缩放 | `SettingSwitch` | `photoWallPulseZoom` | Boolean | `true` | 受音频反应开关控制 |
| 随低频呼吸 | `SettingSwitch` | `photoWallBreathe` | Boolean | `true` | 受音频反应开关控制 |

⚠️ 已**删除**原「转场卡鼓点」（`photoWallBeatSync`）—— 按 §5.5 决策，切换时机不卡节拍。
`BEAT_CUT` 节拍硬切**仍作为可选效果**保留（用户在转场选择器里主动选中才生效）。

### 7.4 原「总开关」取消 → 降级为手机端「图库」开关（✅ 已确定）

> ✅ **2026-09-23 用户决定**：「图片墙的总开关就不需要了，直接降级成手机端图库的开关，
> 不打勾就是不启用图库。打开了才需要授权。」

**决策**：**取消** `photoWallEnabled` 总开关，改为**三个来源开关**
（`photoWallGalleryEnabled` / `photoWallExternalEnabled` / `photoWallJellyfinEnabled`）。

| 原总开关承担的两件事 | 新方案由谁承担 |
|---|---|
| ① 停止一切照片 I/O（权限、扫描、人脸检测） | ✅ **各来源开关各自负责** —— 关掉的来源不扫描、不申请权限；三者全关即完全无 I/O |
| ② 从效果列表隐藏 `PHOTO_WALL` | ✅ **由「三开关是否全关」派生**（见下） |

**推导规则（唯一新增逻辑，成本极低）**：

```kotlin
val photoWallAvailable = photoWallGalleryEnabled ||
                         photoWallExternalEnabled ||
                         photoWallJellyfinEnabled
// VisualizerTheme.selectable 按 photoWallAvailable 过滤掉 PHOTO_WALL
```

**实现要点（4 条）**：

| # | 要点 |
|---|---|
| 1 | ⛔ `VisualizerTheme.selectable` **当前是 `val`（`AppSettings.kt:119`，直接 `= entries`）**，**读不到运行时设置** ⇒ 必须改成 **`fun selectable(photoWallAvailable: Boolean): List<VisualizerTheme>`**。⚠️ **调用点共 3 处 + 测试 1 处**（2026-09-23 全量 grep 核实）：`VisualizerStage.kt:363`（底部指示器）、`VisualizerViewModel.kt:131`（`step()` 左右键切换）、`VisualizerRendererFactory.kt:92`（`availableThemes`）、`VisualizerThemeTest.kt:54-56`（断言 `size == 35`）。⛔ **漏改 `VisualizerViewModel.step()` 的后果**：三开关全关时左右键会切到一个空效果上 |
| 2 | 关掉的来源：**不启动扫描、不预解码、不申请权限**；三个全关时 `FaceScanManager` 也不启动 |
| 3 | 三个全关且当前正显示照片墙 → **平滑切回** `VisualizerTheme.Default`（`CIRCULAR_RING`），走 `RendererSwapper` 现有 crossfade |
| 4 | 权限申请**由图库开关驱动**（不是总开关）：`photoWallGalleryEnabled` 打开才走授权流程；关闭状态**永不请求**照片权限 |

**为什么这个改法更好**（对比原「总开关」方案）：

| 维度 | 原「总开关」方案 | 新方案（来源开关） |
|---|---|---|
| 语义层级 | **两层**：总开关管「功能开关」，来源选择管「用哪个」 | ✅ **一层**：开关即「用不用这个来源」 |
| 权限逻辑 | 总开关驱动，但「总开关开 + 图库关」时权限归属含糊 | ✅ **图库开关直接驱动**，一一对应 |
| 用户心智 | 要先开总开关，再选来源 | ✅ **开哪个就用哪个**，无需先开总开关 |
| 字段数 | `photoWallEnabled` + `photoWallSourceMode` + `photoWallAutoSource` = 3 | `photoWallGalleryEnabled` + `photoWallExternalEnabled` + `photoWallJellyfinEnabled` = 3（**语义更直白，且少一个枚举**） |

⚠️ **代价（如实记录）**：三开关全关时用户**看不到「照片墙」这个效果** —— 发现成本比原方案更高
（原本设置页里至少有个总开关可见）。⇒ 这正是 §6.8 建议「**电视端外接存储默认开**」的原因。

### 7.5 与现有 35 个效果的关系（✅ 已确定：新增枚举值）

**决策（2026-09-23 用户确认）：`VisualizerTheme` 新增 `PHOTO_WALL` 枚举值**，
让照片墙成为**第 36 个效果**，而非另起一套并行模式。

| 好处 | 说明 |
|---|---|
| 融入现有切换体系 | 左右切换能切到、指示器能显示、`VisualQuality.supports()` 门控自动生效（`AppSettings.kt:147`） |
| 复用 `RendererSwapper` | 从其他效果切到照片墙时，crossfade 语义天然可用 |
| 避免割裂 | 不出现「两套可视化模式」的概念负担 |
| 零成本融入自动轮换 | 若日后恢复「效果自动切换」，照片墙自动参与 |

**实现要点**：

| # | 要点 |
|---|---|
| 1 | `VisualizerTheme` 加 `PHOTO_WALL("照片墙", Tier.ADV, "38")`（序号接现有最大 37） |
| 2 | `VisualizerRendererFactory` 加 `VisualizerTheme.PHOTO_WALL -> PhotoRenderer()` 分支 |
| 3 | `VisualizerTheme.selectable` 按 `photoWallAvailable`（三来源开关之**或**）过滤（见 §7.4） |
| 4 | ⚠️ 归 **`Tier.ADV`**：照片双缓冲 + 转场叠加在 `Tier.BASIC`（低画质 / 老设备）上风险高，必须门控 |
| 5 | 转场效果**另建 `PhotoTransition` 枚举**（见 §13.1-6），不与 `VisualizerTheme` 混用 |

### 7.6 持久化与兼容约束

| 约束 | 内容 |
|---|---|
| ⛔ **Gson 前向兼容** | `AppSettings` 新增字段**必须带默认值**（类注释明写：Gson 基于反射构造，不会为非空参数补默认值；用旧数据反序列化新代码会抛异常/置 null） |
| `AppPreferences` | 按现有模式加 `booleanPreferencesKey` / `stringPreferencesKey` / `intPreferencesKey` + getter + setter |
| `VisualizerTheme` 新增枚举值 | ✅ **新增**安全（老备份不含该值，走 `fromKey` 默认）；⛔ 若日后重命名必须进 `LEGACY_MAP`（见 `docs/technical-overview.md` §10.172） |
| `PhotoTransition` 新枚举 | 同上；且它进 `AppSettings` 后受 Gson 枚举坑约束 → 备份导入必须走 `BackupGson`（见 `docs/technical-overview.md` §10.172） |
| `PhotoSourceKind` 新枚举 | 同上（若需持久化，必须带默认值 + 走 `BackupGson`）；枚举值只用「新增安全」的名字，日后重命名必须进 `LEGACY_MAP`。⚠️ 原 `PhotoSourceMode`（5 值）**已作废**，见 §6.8 |
| 本次新增字段 | 全部为**新增字段、无历史数据** ⇒ 命名可自由确定（不受既有备份影响）；但仍须带默认值 |
| 文案 | 新增 UI 文案须**同步** `values/strings.xml` 与 `values-en/strings.xml` |
| 竖屏触摸目标 | 设置页在竖屏下需走 `portraitTouchTarget(x.dp)`，小视觉元素不得直接挂 `clickable`（门禁 `SmallTouchTargetScanTest`） |

## 八、PhotoBuffer 设计（最大技术风险）

⚠️ **位图内存是本方案的头号风险**。

| 项 | 封面（现状） | 照片 |
|---|---|---|
| 典型尺寸 | ~500×500 | 4000×3000 |
| 未压缩内存 | ~1 MB | **~48 MB** |

且接口有硬约束：`VisualizerRenderer.draw()` **明令禁止对象分配** ⇒ **解码绝不能发生在绘制路径内**。

**设计要点**

1. **降采样**：`inJustDecodeBounds` 读尺寸 → 计算 `inSampleSize` → 解码到**屏幕尺寸**（1920×1080 ARGB ≈ 8 MB）
2. **双缓冲**：`photoA`（当前）/ `photoB`（下一张），交换而非重建
3. **LRU 缓存**：额外缓存 2–3 张，避免快速切换时反复解码
4. **后台线程**：独立单线程 `Executor`（解码串行，避免多线程争抢内存峰值）
5. **低画质档降级**：`Tier.BASIC` 时降到 1280×720 或 `RGB_565`（内存减半）
6. **内存预算**：双缓冲 16 MB + LRU 24 MB ≈ **40 MB**（老电视需更保守）

⚠️ **完整 API 签名与内存预算算法见 §14.2.2**（`peek` / `request` / `prefetch` / `invalidateSource` / `close`）。
三条关键约束：
① `peek()` 未就绪**必须返回 `null`**，绝不阻塞绘制路径；
② `close()` **必须 `bitmap.recycle()`**（API 22 上 `ImageBitmap` 包装的 `Bitmap` 不会自动回收）；
③ 宽高为 `0` 的来源（`ExternalFilePhotoSource`）在**解码那一刻**用 `inJustDecodeBounds` 现算（§14.2.1「宽高契约」）。

---

## 九、照片读取授权方案

⚠️ **需求 2「启动前提示 + 一键允许」在两个平台上会呈现四种完全不同的形态** ——
不存在一个统一的「请求照片权限」实现。

### 9.1 四种形态对照

| 来源 | 场景 | 授权形态 | 用户看到什么 | 是否需自建提示 UI |
|---|---|---|---|---|
| **图库** | 手机（API 23+） | 系统运行时权限 | 系统权限对话框 | ✅ 建议先弹说明（见 §9.4）；**由图库开关触发** |
| **外接存储** | 电视（**路径遍历**，API 22） | **安装时已授予** | **什么都不弹** | ❌ 不需要 |
| **外接存储** | 手机 / 电视（**SAF 指定目录**） | 目录 URI 授权 | 系统文件夹选择器 | ⚠️ 需要引导文案（§6.3） |
| **Jellyfin** | 手机 / 电视 | **无需任何系统权限** | 什么都不弹 | ❌ 不需要（见 §9.5） |

### 9.2 ⚠️ 电视上「启动前提示授权」会失效（重要）

电视是 **API 22 / Android 5.1.1**，而**运行时权限是 API 23 才引入的**。
API 22 的权限在**安装时一次性授予**，运行时不会弹窗，`requestPermissions` 会直接回调 granted。

⇒ **需求 2 在电视「外接存储（路径遍历）」上自动满足**（用户装完即已授权）。
真正需要「提示 + 一键允许」的只有**手机上的「图库」**（系统运行时权限，由开关触发）；
**两平台走 SAF 指定目录**那条路径则只需**引导文案** —— 选目录本身即是授权动作，不涉及运行时权限。
⚠️ 手机端「外接存储」**只走 SAF**（§6.3 路线 B）⇒ **不需要** `READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES`。

### 9.3 权限矩阵

| 系统版本 | 场景 | 需要的权限 | 现状 |
|---|---|---|---|
| API 22 | 电视读 USB | `READ_EXTERNAL_STORAGE` | ✅ 已声明（`maxSdkVersion=32`），安装时授予 |
| API 23–32 | **手机图库**（唯一需运行时权限的场景） | `READ_EXTERNAL_STORAGE` | ✅ 已声明（`maxSdkVersion=32`），**需运行时请求**（由开关触发） |
| **API 33** | **手机图库** | **`READ_MEDIA_IMAGES`** | ❌ **缺失，必须新增**（由图库开关触发） |
| **API 34+** | **手机图库（部分授权）** | **`READ_MEDIA_VISUAL_USER_SELECTED`** | ❌ **缺失，必须新增** —— 系统对话框「仅选择照片」分支，见 §9.6 |
| 任意 | SAF 目录（两平台的「外接存储」） | 无 Manifest 权限 | ✅ 走 URI 授权（`takePersistableUriPermission`） |

### 9.4 实现（成本极低，~70 行 + SAF 扩展）

| 步骤 | 内容 |
|---|---|
| Manifest | 加 `<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />` |
| `PermissionHelper` | 仿现有 `hasLocalMusicPermission` / `getLocalMusicPermissions`，加 `hasPhotoPermission` / `getPhotoPermissions`（换 `READ_MEDIA_IMAGES`） |
| 手机 UI | 复用 `MainActivity` 已有请求链路（178–200 行）；建议先弹一句说明再触发系统对话框（提升通过率） |
| 电视 SAF | 扩展 `exportTreeLauncher` 的既有模式，新增「选择照片目录」入口；选中后 `takePersistableUriPermission` |
| ⛔ **不要照搬「启动即请求」** | `MainActivity` 178–200 行的 `POST_NOTIFICATIONS` / `RECORD_AUDIO` 是 **`onCreate` 里无条件请求**；照片权限**必须由「图库」开关触发**（官方原文：*"Request these permissions when the app needs storage access, instead of at startup."*），**不能**放进那段 |

⚠️ **权限请求的触发点是「图库」开关，不是总开关**（总开关已取消，见 §7.4）：
`photoWallGalleryEnabled` 由关变开的那一刻才走授权流程；**拒绝授权则开关自动回弹为关闭**（§6.2）。

⛔ **「授权后再次启动无需授权」不要自己存标志位** —— 只需每次 `checkSelfPermission` 判断。
自己存标志会与系统状态不一致（用户在设置里撤销后标志仍为 true）。

⚠️ **Android 14+ 要说得更准确**：「允许全部」时权限确实长期有效，但**「仅选择照片」下 `READ_MEDIA_IMAGES`
只是会话级临时授予、重启后失效**（系统记住的是**用户的选择**，不是权限状态）⇒
所以**更不能**自己存「已授权」标志，必须走 §9.6 的三态判定。

✅ **SAF 路径同理**：`takePersistableUriPermission()` 后重启仍有效，**不需要自己存「已授权」标志**，
只需在启动时检查 `contentResolver.persistedUriPermissions` 是否还包含该目录。

### 9.5 Jellyfin 来源：授权侧完全免费

**结论：Jellyfin 照片通道不需要任何新增权限、不需要任何授权 UI、不触发需求 2 的任何逻辑。**

原因：

| 环节 | 现状 |
|---|---|
| 网络访问 | `INTERNET` 权限早已声明（音乐后台在用） |
| 认证 | 复用现有 `JellyfinAdapter` 的 `apiToken`（登录时已取得） |
| 照片 API | 与音乐 API **同一 host、同一 token**，只是 `IncludeItemTypes` 换成 `Photo` |
| 存储访问 | **不涉及外接存储** —— 照片由服务端返回 HTTP 流，客户端不读文件系统 |

⇒ 对 §9.4 的实现清单**零改动**。且它还有一个隐性收益：
**在用户拒绝照片权限、或电视外接存储枚举失效（G7）时，Jellyfin 来源仍可用** ——
是「照片墙完全没有照片」的一个天然兜底。

⚠️ 唯一相关项：**需要用户先在 Jellyfin 服务端建一个 `CollectionType: photos` 的照片库**。
设置页里 Jellyfin 通道的可用性判断要区分两种情况并给出不同文案：

| 情况 | 设置页提示 |
|---|---|
| 未连接 NAS | 「未连接 NAS」 |
| 已连接但无照片库 | 「NAS 上未找到照片库 —— 请在 Jellyfin 中添加「照片」类型媒体库」 |

### 9.6 ⚠️ Android 14+ 部分授权（「仅选择照片」）

`targetSdk 34` ⇒ 本项目在 **Android 14（API 34）+** 上会触发 **Selected Photos Access**
（2026-09-23 据 Android 官方文档核实）。系统对话框变成**三选一**：

| 用户选择 | `READ_MEDIA_IMAGES` | `READ_MEDIA_VISUAL_USER_SELECTED` | 我们能看到的照片 |
|---|---|---|---|
| 允许全部 | ✅ 已授予 | — | 全部 |
| **仅选择照片**（"Select photos and videos"） | ⚠️ **仅本次会话临时授予**（退到后台 / 杀进程后系统最终撤销，**行为等同一次性权限**） | ✅ **持久授予** | **只有用户勾选的那批**（`MediaStore` 查询被系统自动裁剪） |
| 不允许 | ❌ | ❌ | 无 |

⛔ **三个必须写对的点**：

| # | 规则 | 写错的后果 |
|---|---|---|
| 1 | **回弹判据必须含 `READ_MEDIA_VISUAL_USER_SELECTED`** —— 做**三态判定**（完全 / 部分 / 拒绝），而非二态 | 「仅选择照片」下 `READ_MEDIA_IMAGES` 只是**会话级临时授予**，**App 重启后即失效** ⇒ 只判 `READ_MEDIA_IMAGES` 会把**已经授权了部分照片的用户**在下次启动时误判为「拒绝」、把开关弹回去（§6.2 的回弹规则需按此修正） |
| 2 | Manifest **声明** `READ_MEDIA_VISUAL_USER_SELECTED` | 不声明**也能用**（系统对话框照样给「仅选择照片」），但系统会把 App 置于**兼容模式**、**由系统接管「重新选择」**；声明后我们才能**自己提供「重新选择照片」入口**（见规则 3） |
| 3 | 设置页在「部分授权」态提供**「重新选择照片」**按钮（再次触发权限请求即唤起系统的 reselection UI），并加说明：*「当前仅可访问你选中的照片」* | 用户没有回到「允许全部」的路径，只能自己去系统设置翻 |

**官方推荐的三态判定写法**（本项目只需 `READ_MEDIA_IMAGES` 一支）：

```kotlin
when {
    SDK_INT >= 33 && checkSelfPermission(READ_MEDIA_IMAGES) == GRANTED -> Full
    SDK_INT >= 34 && checkSelfPermission(READ_MEDIA_VISUAL_USER_SELECTED) == GRANTED -> Partial
    SDK_INT <  33 && checkSelfPermission(READ_EXTERNAL_STORAGE) == GRANTED -> FullLegacy
    else -> Denied
}
```

⚠️ **两条与官方一致、但本文档原先漏掉的约束**：

| # | 约束 |
|---|---|
| 1 | **「需要时才请求，不要启动时请求」** —— 官方原文：*"Request these permissions when the app needs storage access, instead of at startup."* ✅ 与 §7.4-4「由图库开关触发」一致；**不要**把照片权限塞进 `MainActivity` `onCreate` 那段（§9.4） |
| 2 | **权限可能在 `onStart` / `onResume` 之间变化**（用户可在系统设置里改，且 App 不重启）⇒ 三态判定**至少要在 `onResume` 刷新**；§6.2 的「下次启动再回弹」应理解为「`onResume` 即回弹」 |

⚠️ **不要自己缓存权限状态**（官方原文：*"Don't store the permission state in a permanent way, including `SharedPreferences` or `DataStore`."*）
—— 与 §9.4 既有结论一致。

---

## 十、人脸检测方案（需求 4）

**用途**：判断照片里有没有人脸 → 支持「仅含人脸」的照片过滤 / 优先展示合影。

### 10.1 方案对比

| 方案 | 可行性 | 单张耗时 | 说明 |
|---|---|---|---|
| ML Kit Face Detection | ❌ **不可用** | — | 依赖 Google Play Services，**电视无 GMS** |
| **ONNX Runtime + YuNet（~337 KB）** | ✅ **推荐** | 10–30 ms（手机）<br>50–150 ms（老电视 ARMv7） | **`onnxruntime-android:1.17.1` 已在依赖**，`DemucsSeparator` 已验证 `OrtSession` 用法 |
| `android.media.FaceDetector` | ⚠️ 不推荐 | 100–300 ms | 2005 年算法，仅正面、需灰度，准确率低 |

### 10.2 资源消耗评估

| 项 | 结论 |
|---|---|
| **运行时成本** | ✅ **零** —— 结果缓存，播放时只读缓存 |
| **一次性成本** | ⚠️ 扫描时后台批处理。1 万张 × 100 ms ≈ **17 分钟**（老电视） |
| **关键优化** | ✅ **只对 320×320 缩略图检测**，不要全尺寸 → 耗时降一个数量级 |
| **必须配套** | 后台分片 + 可中断 + 进度 UI |
| **模型分发** | 337 KB 直接**打包进 assets**（新建 assets 目录）；Demucs 那套 166 MB 下载链路**不需要** |
| **老设备策略** | ARMv7 性能差 → 低画质档建议关闭该功能 |
| **结果存储** ✅ | **新增 Room 表**（见 §10.3）—— 照片可达上万，DataStore 存 JSON 不适合 |

### 10.3 检测结果存储（✅ 已确定：新增 Room 表）

**决策（2026-09-23 用户确认）**：人脸检测结果存**新增的 Room 表**。
✅ **2026-09-23 细化：改为独立建库 `PhotoFaceDatabase`（`photo_face.db` v1）**，不并入 `LocalMusicDatabase` —— 理由与完整实体/DAO 定义见 §14.2.6。

**理由**：
- 照片量可达**上万**，DataStore 存 JSON 会：① 每次读写全量序列化；② 无法按条件查询（如「只取含人像的」）；③ 体积膨胀
- Room 天然支持按 `hasFace` 过滤、分页、增量更新
- 项目已有 Room（`LocalMusicDatabase` v3，schema 落盘 `app/schemas/`）

**表设计（建议）**：

| 列 | 类型 | 说明 |
|---|---|---|
| `photoKey` | String (PK) | 照片稳定标识（`PhotoRef.id`） |
| `hasFace` | Boolean | 是否检出人脸 |
| `faceCount` | Int | 人脸数量（可用于「单人照 / 合影」筛选） |
| `detectedAt` | Long | 检测时间（用于判断是否需重扫） |

⚠️ **必须注意**：
- ✅ **独立建库** `PhotoFaceDatabase`（见 §14.2.6）—— **不需要**动 `LocalMusicDatabase` 的版本；新库的 schema 仍需**落盘 `app/schemas/` 并入库**（项目硬约定，`app/build.gradle.kts:295` 已配 `room.schemaLocation`）
- 拔 U 盘后照片不可达 → 表中条目**不应立即删除**（重插后仍有效），但查询时需与 `PhotoSource` 结果做交集
- 照片文件变更（同路径不同内容）→ 用 `detectedAt` + 文件修改时间判断是否需重检

---

## 十一、风险清单

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | **位图 OOM**（48 MB/张 vs 封面 1 MB） | 🔴 高 | 降采样到屏幕尺寸 + 双缓冲 + LRU；低画质档 `RGB_565` |
| R2 | **`draw()` 禁止对象分配**（接口硬约束） | 🔴 高 | 后台预解码，绘制路径只做 draw |
| R3 | 逐像素效果在 CPU 上不可行 | 🔴 高 | 溶解类走 M3（预生成遮罩位图），禁用逐像素实现 |
| **R4** | **电视上 USB 设备枚举失效（G7 既有缺陷）** | 🔴 高 | ✅ **已纳入本次范围**；改用 `ACTION_MEDIA_MOUNTED` 的 `intent.data` 取挂载点，**建议独立提交先行** |
| R5 | 老电视 ARMv7 帧率 | 🟠 中 | 照片墙固定归 **`Tier.ADV`**（§7.5-4）；M4 块数上限减半 |
| R6 | 效果数量爆炸（76 种） | 🟠 中 | 转场做策略层，不逐个建渲染器类 |
| R7 | 人脸检测耗时 | 🟠 中 | 缩略图检测 + 分片 + 进度 UI + 可中断 |
| ~~R8~~ | ~~BPM 检测不可靠~~ | ✅ **已消除** | **随 §5.5 决策一并作废** —— 照片墙**不使用 BPM / 节拍**决定切换时机，其对策（滑动平均 / 每拍校正 / 固定兜底）不再需要 |
| R9 | **外接盘拔掉后缓存失效** | 🟠 中 | **电视**：监听 `onDeviceUnmounted` → 清空 `PhotoBuffer` + 中断人脸任务；**手机**：拿不到卸载广播 ⇒ **惰性失效**（`openStream()` 失败即跳过，连续失败则暂停，§6.6） |
| R10 | **部分 ROM 的 SAF 选择器对遥控器不友好** | 🟠 中 | 电视优先走**路径遍历**（路线 A），A 不可行再引导 SAF；⚠️ 遥控器「手动输入路径」不现实 ⇒ **最终兜底 = 打开 Jellyfin 来源**（无需 SAF，§6.4） |
| R11 | 各厂商 USB 挂载路径不一致 | 🟠 中 | 多候选路径探测 + SAF 兜底（双保险） |
| R12 | 新增枚举与老备份兼容 | 🟡 低 | **新增**枚举值安全；若日后重命名必须走 `LEGACY_MAP`（见 `docs/technical-overview.md` §10.172） |
| R13 | HEIC 在电视上不可解码 | 🟢 低 | 扫描时按扩展名过滤 |
| R14 | 电视无运行时权限（API 22） | 🟢 低 | 系统已处理，无需特殊逻辑（是**行为差异**而非风险） |
| **R15** | **NAS 离线 / 未建照片库 ⇒ Jellyfin 来源为空** | 🟡 低 | **无需回落** —— 其余开关的来源照常工作；设置页该行显示「未连接 NAS」/「NAS 上未找到照片库」（§6.8 / §9.5） |
| **R16** | **多来源混合后，用户不知照片从哪来** | 🟡 低 | 设置页「已启用来源」信息行 + **分来源照片数量**（§7.3）；来源组合由开关直接表达，无隐藏逻辑 |
| **R17** | Jellyfin 照片数量大 → 首次列表拉取慢 | 🟡 低 | 分页拉取（`Limit`/`StartIndex`）+ 只取 `Id`/`Name`/`Width`/`Height` 轻字段（⚠️ `Width`/`Height` **能免费拿到就必须填** —— Jellyfin 与 MediaStore 都免费；`ExternalFilePhotoSource` 填 `0`、解码时现算，见 §14.2.1「宽高契约」），封面按需请求 `Images/Primary` |
| **R18** | **跨来源重复照片**（**外接 SD 卡通常也被图库索引** ⇒ 选 SD 卡目录即重叠） | 🟠 中 | **指纹去重 `(size, 时间秒, 名称)` 保留为兜底**（§6.8）；⚠️ 注意 `DATE_MODIFIED` **秒** vs `lastModified()` **毫秒**的单位不一致 |
| **R19** | **混合后小来源被淹没**（Jellyfin 5000 张 vs U 盘 50 张） | 🟠 中 | 默认「自然混合」（按数量加权）；提供 `photoWallSourceBalance`「来源均衡」选项（§6.8） |
| **R20** | **图库开关开着但用户拒绝授权** ⇒ 开关状态与实际不符 | 🟡 低 | 拒绝授权后**开关自动回弹为关闭**；被系统事后撤销权限时同样回弹并提示（§6.2） |
| **R21** | 三开关全关 ⇒ 用户**找不到「照片墙」入口** | 🟡 低 | 电视端「外接存储」默认开（§6.8）；README / 首次启动提示补充说明 |
| **R22** | 手机所选目录在**外接 U 盘**上，拔盘后 SAF URI 不可达 | 🟡 低 | **惰性失效**：`openStream()` 失败即跳过该图；连续失败则暂停并提示。⚠️ **不要自动清除 `photoWallDirUri`**（插回同一盘通常仍有效）（§6.6） |
| **R23** | **用户想选的目录被 SAF 禁止**（卷根目录 / `Download` / `Android/data` / `Android/obb`）⇒ 选不到、且不知为何 | 🟠 中 | 入口文案**明确要求选子目录**；对 `Android/data` 等被禁位置**主动说明原因**，避免用户反复尝试（§6.3） |
| **R24** | 用户误以为「手机上的照片图库全都有」⇒ 开了图库却找不到某些图（`.nomedia` 目录 / 其他 App 私有缓存） | 🟡 低 | 设置页「照片来源」区加一句说明 + 分来源数量显示，让用户看出「外接存储」确实多出了照片（§6.2） |
| **R25** | **电视内部存储的照片彻底读不到**（无图库兜底，且我们主动排除了内部存储） | 🟡 低 | **已知限制**：写进 README，解法是「把照片拷到 U 盘」。理由见 §6.3（TV 内部存储放照片概率极低，而 API 22 内外卷判别不可靠） |
| **R26** | 用户选到内部存储被拒，但**看不懂为什么**（SAF 允许选、我们拒绝） | 🟡 低 | 拒绝文案必须说明理由：*「请选择 U 盘或 SD 卡上的文件夹（不支持内部存储）」*；⚠️ 不要只说「无效目录」（§6.3） |
| **R27** | **Android 14+「仅选择照片」被误判为拒绝** ⇒ 开关被错误回弹（`READ_MEDIA_IMAGES` 只是**会话级临时授予**，重启即失效） | 🟠 中 | 三态判定（完全 / 部分 / 拒绝），**回弹判据必须含 `READ_MEDIA_VISUAL_USER_SELECTED`**；Manifest 声明该权限以自控「重新选择」；设置页提供「重新选择照片」入口（§9.6） |

---

## 十二、工作量与分期

**合计约 3920–4520 行新代码 + 约 700 行单测。**

| 分期 | 内容 | 预估 | 目的 |
|---|---|---|---|
| **P0** | 权限 + **三来源 `PhotoSource`（含 `StorageMonitor` API 22 修复 G7 + `JellyfinPhotoSource`）** + `PhotoSourceAggregator`（聚合 + 去重） + `PhotoBuffer` + **15 种** P0 转场 + 时序状态机 + **「照片墙」设置分区（三个来源开关 + 目录 + 转场基础项）** | ~2070 行 | **最早暴露内存 / 帧率 / USB 兼容性三个最大风险**；Jellyfin 接入成本极低（~120 行）故直接进 P0，作为外接存储不可用时的兜底 |
| **P1** | 补齐至 **43 种**（P0+P1）+ 停留期运动 + 音频反应 + 设置项补齐 | ~950 行 | 在验证过的基础上扩充 |
| **P2** | 剩余 **33 种**（P2，含 M3 降级）+ 人脸检测 + Room 表 + 「仅显示人像」开关 | ~1100 行 | 人脸检测独立可单独排期 |

> ⚠️ 分期表（合计 **4120 行**）与下方模块表（合计 **4250 行**）是**两次独立粗估**、口径不同
> （分期按「里程碑交付物」、模块按「文件 / 功能」），差 ~3% 属正常；**排期以模块表为准**，两者都不是承诺值。

| 模块 | 新代码 | 难度 |
|---|---|---|
| 权限（含 SAF 导入通道 + Android 14+ 三态判定） | ~150 行 | ⭐ 低 |
| **`PhotoSource` 三实现** —— MediaStore ~110 / 外接存储 ~220 / Jellyfin ~120 | **~450 行** | ⭐⭐⭐ 高（ROM 差异集中在 `ExternalFilePhotoSource`） |
| **`PhotoSourceAggregator`（多来源聚合 + 跨来源去重 + 可选均衡）** | ~100 行 | ⭐⭐ 中（去重指纹） |
| **`StorageMonitor` API 22 修复（G7）** | ~80 行 | ⭐⭐ 中（需真机验证） |
| `PhotoBuffer`（解码 / 降采样 / 双缓冲 / LRU） | ~300 行 | ⭐⭐⭐ 高（内存 / 线程） |
| 时序状态机 + 缓动库（**无 BPM 联动**，已简化） | ~200 行 | ⭐⭐ 中 |
| **转场随机化**（§5.9：随机池 + 避免最近 2 次重复 + 串行模型映射） | ~60 行 | ⭐ 低 |
| 转场策略库（76 种） | ~1200 行 | ⭐⭐ 中 |
| **M3 降级遮罩**（5 种 M5 效果的降级近似 + 预生成遮罩位图） | ~200 行 | ⭐⭐ 中 |
| `PhotoRenderer` 统一驱动 | ~200 行 | ⭐⭐ 中 |
| 人脸检测（ONNX 推理 + 后台批处理） | ~550 行 | ⭐⭐⭐ 高（ONNX 集成） |
| **Room 表 + 数据库迁移**（`LocalMusicDatabase` 升版 + schema 入库） | ~150 行 | ⭐⭐ 中（迁移需谨慎） |
| **「照片墙」设置分区**（**19 行**设置项 ⇒ **17 个持久化字段** + 2 行运行时派生展示；含目录选择 + 人脸进度 UI） | ~450 行 | ⭐⭐ 中（控件已有，主要是接线） |
| `AppSettings` / `AppPreferences` 字段扩展 | ~160 行 | ⭐ 低 |
| 单测门禁（**清单见 §14.4**：11 个门禁类，每个含负向自证） | ~750 行 | ⭐⭐ 中 |

**建议**：P0 阶段优先把 **`PhotoBuffer`（内存）** 与 **外接存储来源（真机兼容性）** 做扎实并上机实测，
再决定后续效果的实现深度 —— 这两项决定整个功能的可行性上限。

📁 **本章的「行数」是粗估；逐文件拆解 + 12 个提交的验收点 + 上机验收清单见 §十四。**

**关于 Jellyfin 来源的排期理由**：它**接入成本约 120 行、且不依赖任何新权限/新扫描逻辑**
（复用 `JellyfinAdapter` 现有 HTTP + token 模式），同时能**绕开 G7 与 SAF 两条最不确定的路径**。
因此建议直接放 P0 —— 在外接存储来源真机验证受阻时，它提供一条立即可用的对照路径，
避免整个功能卡在 ROM 兼容性上。⚠️ 注意它**不是「次优先级」而是「另一条独立来源」**：
三个开关平级，用户开哪个就用哪个（§6.8）。

✅ **G7 已纳入本次范围**（用户确认「一起修掉」）。但仍建议**作为独立提交先行落地**：
它同时修复**电视上 USB 音乐扫不到**这一既有缺陷，与照片功能解耦，可单独验证与发布 ——
避免照片功能的问题与 G7 的问题在真机上互相掩盖，排查时误判。

---

## 十三、决策记录

### 13.1 2026-09-23 最终决定（14 项）

| # | 议题 | 决定 | 影响 |
|---|---|---|---|
| 1 | 照片与音乐的关系 | **独立时序，不卡节拍**；音频反应作为**可选**（默认关） | §5.5 重写；删 BPM 联动逻辑（约 −150 行）；`BEAT_CUT` 降为可选效果 |
| 2 | **画面适配策略** | **放设置页让用户选**（`CROP` 满屏 / `FIT` 留黑边，默认 `CROP`）；✅ **与横竖屏无关，四端均暴露** | §7.3 新增 `photoWallScaleMode`（原 `photoWallPortraitFit` 改名，`Portrait` 前缀误导） |
| 3 | `AGSL` shader（M5）降级 | **提供 M3 降级近似**，不从列表剔除 | §4.4 新增降级映射表（5 种 M5 效果） |
| 4 | G7 是否一并修 | **一起修掉**（纳入本次范围） | §二 G7 专节；§十二 含 ~80 行；仍建议独立提交先行 |
| 5 | 人脸检测结果存储 | **新增 Room 表** | §10.3 新增表设计；需升 `LocalMusicDatabase` 版本 + schema 入库 |
| 6 | `PhotoTransition` 枚举 | **新建独立枚举**，不与 `VisualizerTheme` 混用 | §7.5 实现要点 5；避免 35 + 76 = 111 项挤在同一选择器 |
| 7 | **Jellyfin 能否作照片源** | **可以** —— 新增 `JellyfinPhotoSource` | §6.4 新增专节；`IncludeItemTypes=Photo` + `Images/Primary`，复用现有 token，**无需新权限** |
| 8 | ~~多通道优先级~~ → **已被第 9 项取代** | ~~USB 优先，Jellyfin 其次；设置中选来源~~ | ~~`photoWallSourceMode`（5 档）~~ ⇒ **作废** |
| 9 | **来源选择机制** | ✅ **改为「独立开关 + 合并池」** —— 三个来源各一个开关，**开几个混几个** | §6.8 **整节重写**；作废 `PhotoSourceMode` 枚举（5 值）、优先级排序、回落链、「仅 XXX 不回落」约束、「回落可见」UI 约束 |
| 10 | **原「总开关」** | ✅ **取消**，降级为**手机端「图库」开关** | §7.4 **整节重写**；`photoWallEnabled` → `photoWallGalleryEnabled` + `photoWallExternalEnabled` + `photoWallJellyfinEnabled`；`PHOTO_WALL` 可见性改由「三开关之**或**」派生 |
| 11 | **手机端「外接存储」的定位** | ✅ **由用户指定一个目录** —— **不是**自动扫外接卷 | §6.3 扫描范围表重写；§7.3 `photoWallDirUri` **手机端必填** |
| 12 | **是否允许读内部存储** | ✅ **不允许** —— 两平台统一**只读外接卷（USB / SD 卡）** | §6.3 重写；字段改名 `photoWallLocalEnabled` → **`photoWallExternalEnabled`**；来源名「本地存储」→「**外接存储**」；新增**代码级拦截**（SAF 卷 ID 校验 + 自动探测黑名单）；§6.8 去重降为**兜底但仍保留**（SD 卡也会被索引） |
| 13 | **转场随机化档位** | ✅ **只保留「随机 / 固定」两档，不加「顺序轮播」** | §5.9 末段「待定」转为确认；`photoWallRandomTransition` 维持 **Boolean**，**不**改为 `photoWallTransitionMode` 枚举 |
| 14 | **照片停留时长** | ✅ **新增设置项 `photoWallHoldMs`，默认 8.0s**（范围 3–30s） | §一 需求 10；§5.3 HOLD 时长表标注用户确认；§7.2 布局；§7.3 默认值 `8000` |

**用户原话要点**：
- 「我的想法是在听音乐时**动态浏览以前的照片心情好**，未必需要与音乐节奏相关」（→ 第 1 项）
- 「本来就**不是同一个使用场景**」（→ 第 6 项）
- 「如果已经连了 jellyfin 的音乐后台，是否可以**直接从 jellyfin 的图片库中读取照片**，做照片墙？」（→ 第 7 项）
- 「**USB 优先，Jellyfin 其次**，最好在设置中能够**选择图片来源**」（→ 第 8 项）

### 13.2 已确定议题汇总（32 项）

| 议题 | 结论 | 位置 | 来源 |
|---|---|---|---|
| 照片来源 | **三来源**：图库（手机）/ 外接存储 USB·SD 卡（手机+电视）/ Jellyfin（手机+电视） | §六 | ✅ 用户确认 |
| **来源选择机制** | ✅ **三个独立开关 + 合并池**；开几个混几个，随机展示（**取代**原「优先级 + 回落链」） | §6.8 / §7.3 | ✅ 用户确认 |
| 设置集中 | 新增设置分区「**照片墙**」 | §七 | ✅ 用户确认 |
| **原「总开关」** | ✅ **取消**，降级为**手机端「图库」开关**；`PHOTO_WALL` 可见性由「三开关之或」派生 | §7.4 | ✅ 用户确认 |
| 照片墙与 35 个效果的关系 | `VisualizerTheme` 新增 **`PHOTO_WALL`**（第 36 个，`Tier.ADV`） | §7.5 | ✅ 用户确认 |
| 指定 USB 目录 | `photoWallDirUri`（SAF + `takePersistableUriPermission`） | §7.3 | ✅ 用户确认 |
| 仅显示人像 | `photoWallFacesOnly`（依赖人脸检测结果） | §7.3 | ✅ 用户确认 |
| **照片与音乐的关系** | **独立时序不卡节拍** + 可选音频反应（默认关） | §5.5 | ✅ 用户确认 |
| **转场随机化规则** | ✅ **每次切换重新抽**（抽的是「本次切换的转场」，不是「入场 / 退出各抽一次」）；池 = **当前已实现效果**（与手动选择器一致）；⛔ **排除音频反应类 5 种**；按平台用**降级后**标识去重；**避免最近 2 次重复**；`FADE_BLACK`/`FADE_WHITE`/`CINEMATIC_BARS` **强制串行模型**；复用 `VisualizerRandom`；✅ **档位只有「随机 / 固定」两档，无「顺序轮播」** | §5.9 | ✅ 用户确认 |
| **画面适配** | 设置页可选 `CROP`（满屏）/ `FIT`（留黑边），默认 `CROP`；✅ **与横竖屏无关，四端均暴露**（原写「竖屏专属」已纠正） | §7.3 | ✅ 用户确认 |
| **M5 降级** | **提供 M3 近似**（5 种效果映射表） | §4.4 | ✅ 用户确认 |
| **G7 修复** | **一起修掉**（纳入本次范围） | §二 / §十二 | ✅ 用户确认 |
| **人脸结果存储** | **新增 Room 表** + 数据库迁移 | §10.3 | ✅ 用户确认 |
| **`PhotoTransition` 枚举** | **新建独立枚举**，不与 `VisualizerTheme` 混用 | §7.5 | ✅ 用户确认 |
| **Jellyfin 照片通道** | **可行**（`IncludeItemTypes=Photo` + `Images/Primary`），无需新权限 | §6.4 | ✅ 用户确认 |
| 外接存储取路径 | **A 文件遍历为主 + B SAF 兜底**（**均为同一来源的取路径方式**，非两个来源） | §6.3 | 评估建议 |
| 授权形态 | **四种**（手机运行时权限 / 电视安装时已授予 / SAF 目录 URI / Jellyfin 免授权）；**只有「手机图库」需运行时权限，且由图库开关触发** | §九 | 评估建议 |
| **手机外接存储的定位** | ✅ **由用户指定一个目录**（外接 USB / SD 卡）；**不是**自动扫外接卷 | §6.3 | ✅ 用户确认 |
| **内部存储是否可读** | ✅ **不读** —— 两平台统一**只读外接卷**；手机因「图库已覆盖绝大部分」，电视因「内部存储放照片概率极低 + API 22 内外卷判别不可靠」 | §6.3 | ✅ 用户确认 |
| **三开关默认值** | 电视：外接存储 `开`；手机：三个全 `关` | §6.8 | ✅ 采纳建议 |
| **混合公平性** | 默认「自然混合」（按数量加权）；`photoWallSourceBalance` 开则「来源均衡」 | §6.8 | ✅ 采纳建议 |
| **跨来源去重** | ✅ **必须做**（用户可指定已被图库索引的目录）；键 = `(size, 时间秒, 名称)` | §6.8 | ✅ 用户澄清推导 |
| **MediaStore 索引边界** | ✅ **并非内部存储的图片都被索引**：`.nomedia` 目录 / 隐藏目录 / `Android/data` / `Android/obb` / 其他 App 私有目录 / 非媒体扩展名 **均不在图库中**；而漏掉的那些装的是**功能性图片**（漫画页 / App 缓存），不是「回忆照片」 | §6.2 | 核实 AOSP + Android 官方文档 |
| **SAF 目录选择限制** | ✅ Android 11+（本项目 `targetSdk 34`）**禁止选择**：卷根目录、`Download`、`Android/data/*`、`Android/obb/*` ⇒ UI 必须引导用户选**子目录** | §6.3 | 核实 Android 官方文档 |
| **Android 14+ 部分授权** | ✅ 系统对话框为**三选一**（全部 / **仅选择照片** / 不允许）；「仅选择照片」下 `READ_MEDIA_IMAGES` 只是**会话级临时授予**、`READ_MEDIA_VISUAL_USER_SELECTED` 才是持久标识 ⇒ **回弹判据必须含后者**，否则重启后误判 | §9.6 | 核实 Android 官方文档 |
| USB 扫描范围 | 设置项 `photoWallCommonDirsOnly`（默认限定 `DCIM`/`Pictures`） | §7.3 | 评估建议 |
| 电视照片入口 | 设置页「**选择照片目录**」（SAF），非纯自动扫描 | §7.3 | 评估建议 |
| 转场与停留时长 | 可配置：转场 300–2000 ms（默认 700 ms）/ **停留 3000–30000 ms（✅ 默认 8.0s，用户确认）** | §5.3 / §7.3 | ✅ 用户确认 |
| 图片格式 | `jpg/jpeg/png/bmp/webp/gif`；**排除 HEIC**（电视 API 22 解不了） | §6.5 | 评估建议 |
| **人脸结果库归属** | ✅ **独立建库 `PhotoFaceDatabase`**（`photo_face.db` v1）—— `LocalMusicDatabase` 开了 `fallbackToDestructiveMigration(true)`，不该让它连带清掉重建成本高（1 万张 ≈ 17 分钟）的人脸结果；沿用 `DownloadDatabase` 既有的「独立建库」约定 | §10.3 / §14.2.6 | 评估建议 |
| **照片 A/B 交叉的实现归属** | ✅ **在 `PhotoRenderer` 内自实现**，**不复用** `RendererSwapper` —— 后者交换的是**主题级渲染器**，且其 crossfade 分支当前被硬编码关闭（`VisualizerStage.kt:141` 传 `false`） | §5.2 / §14.2.4 | 核实源码 |
| **块注释未闭合护栏（G11）** | ✅ **新增门禁** `KotlinBlockCommentBalanceTest` —— Kotlin 块注释**支持嵌套**，KDoc 里「斜杠紧邻星号」（最常见是路径通配符）会开一层永不闭合的注释、吞掉其后全部代码，且报错行号落在**文件 EOF**（误导性极强）。本项目已发生 2 次 | §14.4 / §15.3 T1.1 | 🔧 实现期新增 |

**统计**：共 **32** 项 —— 用户确认 **18** + 采纳建议 **2**（小计 20）/ 评估建议 **6** / 核实事实 **4** / 用户澄清推导 **1** / 实现期新增 **1**（**待确认 0 项**）。

---

## 十四、开发实施手册（可开工级）

> 前十三章是「设计意图」，本章是「照着写代码」的粒度：
> 文件清单 / 完整接口签名 / 逐文件改造点 / P0 转场参数 / 单测门禁 / 提交顺序 / 上机验收。
> **本章所有 `file:line` 均于 2026-09-23 逐条核对过当前源码**（不是转述）。

### 14.1 文件清单

#### 新增（22 个 Kotlin 源文件 + 1 个模型资产）

| # | 路径 | 预估 | 内容 |
|---|---|---|---|
| 1 | `backend/photo/PhotoSource.kt` | ~70 | `PhotoSourceKind` / `PhotoSourceStatus` / `PhotoRef` / `PhotoSource` |
| 2 | `backend/photo/MediaStorePhotoSource.kt` | ~110 | 图库（仅手机） |
| 3 | `backend/photo/ExternalFilePhotoSource.kt` | ~220 | 外接存储（`File` 遍历主 + SAF 兜底） |
| 4 | `backend/photo/JellyfinPhotoSource.kt` | ~120 | Jellyfin |
| 5 | `backend/photo/PhotoSourceAggregator.kt` | ~100 | 聚合 + 可选均衡 |
| 6 | `backend/photo/PhotoDedup.kt` | ~40 | 指纹去重（**单独成文件便于单测**） |
| 7 | `backend/photo/PhotoScaleMode.kt` | ~15 | `CROP` / `FIT` |
| 8 | `backend/photo/FaceScanManager.kt` | ~300 | ONNX 批处理（P2） |
| 9 | `backend/photo/db/PhotoFaceDatabase.kt` | ~40 | Room 库（**独立建库**，见 §14.2.6） |
| 10 | `backend/photo/db/PhotoFaceEntity.kt` | ~30 | 实体 |
| 11 | `backend/photo/db/PhotoFaceDao.kt` | ~40 | DAO |
| 12 | `visualizer/photo/PhotoBuffer.kt` | ~300 | 解码 / 降采样 / 双缓冲 / LRU |
| 13 | `visualizer/photo/PhotoTransition.kt` | ~90 | 接口 + `PhotoMechanism` + `PhotoGeometry` |
| 14 | `visualizer/photo/PhotoTransitionId.kt` | ~110 | 76 值枚举（**元信息唯一来源**） |
| 15 | `visualizer/photo/PhotoTransitionRegistry.kt` | ~130 | id → 实现 |
| 16 | `visualizer/photo/PhotoTransitionPicker.kt` | ~70 | 随机池 + 避免最近 2 次 |
| 17 | `visualizer/photo/PhotoTransitionClock.kt` | ~130 | ENTER / HOLD / EXIT 状态机 |
| 18 | `visualizer/photo/MaskCache.kt` | ~150 | 预生成遮罩位图（256×256） |
| 19 | `visualizer/photo/PhotoRenderer.kt` | ~200 | `VisualizerRenderer` 实现 |
| 20 | `visualizer/photo/PhotoWallController.kt` | ~250 | 编排（开关 → 扫描 → 缓冲 → 时钟） |
| 21 | `visualizer/photo/transitions/*.kt` | ~1200 | 76 种转场（P0 先 15 个 ≈ 320 行） |
| 22 | `ui/screens/settings/PhotoWallSettingsSection.kt` | ~450 | 设置分区 |
| 23 | `app/src/main/assets/models/yunet_face.onnx` | 337 KB | 模型（⚠️ **`assets/` 目录当前不存在，需新建**） |

#### 修改（13 个）

| # | 文件 | 改动 | 关键行号（当前） |
|---|---|---|---|
| 1 | `AndroidManifest.xml` | +`READ_MEDIA_IMAGES` +`READ_MEDIA_VISUAL_USER_SELECTED` | — |
| 2 | `data/model/AppSettings.kt` | `VisualizerTheme` +`PHOTO_WALL`；`selectable` 由 `val` 改 `fun`；新增 17 个 `photoWall*` 字段 | `selectable` 在 **119 行**；`VisualQuality.supports` 在 **147 行** |
| 3 | `data/prefs/AppPreferences.kt` | +17 个 key + getter/setter（新增 `photoWall` 分组，照 `visualizer` 分组的写法） | 分组声明 **63 行**；key 区 **288–289 行**；读写 **737–738 / 1821–1822 行** |
| 4 | `visualizer/RenderContext.kt` | +7 个 `photo*` 字段（**不加进 `update()`**） | `update()` 在 **49–83 行** |
| 5 | `visualizer/VisualizerRendererFactory.kt` | +`PHOTO_WALL` 分支；`availableThemes` 改调用新函数 | `availableThemes` 在 **91–92 行** |
| 6 | `ui/components/VisualizerStage.kt` | 帧循环 +1 行；绘制前 +1 行 `applyTo` | 帧循环 **159–172 行**；绘制 **247–266 行**；`selectable` 调用 **363 行** |
| 7 | `ui/viewmodel/VisualizerViewModel.kt` | 持有 `PhotoWallController`；`step()` 用过滤后列表 | `selectable` 调用 **131 行** |
| 8 | `ui/screens/SettingsScreen.kt` | `when (displaySection)` +1 分支 | `when` 在 **474 行**，分支 475–681 |
| 9 | `ui/screens/settings/SettingsSection.kt` | 枚举 +`PHOTO_WALL` | 枚举 **32–42 行** |
| 10 | `util/PermissionHelper.kt` | +`hasPhotoPermission` / `getPhotoPermissions` | 现有 `hasLocalMusicPermission` **20 行** |
| 11 | `ui/MainActivity.kt` | +照片目录选择 launcher（照 `exportTreeLauncher`）+ 三态刷新 | `exportTreeLauncher` **78–83 行**；权限请求 **178–200 行** |
| 12 | `backend/local/StorageMonitor.kt` | **G7 修复** | 短路 **103–106 行**；`intent.data` 日志 **64 行** |
| 13 | `app/src/test/java/com/nasmusic/tv/data/model/VisualizerThemeTest.kt` | **5 处 `35` → `36`**；`selectable` 改函数调用 | 断言在 **53–56 / 60–70 行** |

⚠️ **`VisualizerThemeTest` 的 5 处硬断言**（漏改必挂）：`entries.size` / `selectable.size` / `selectable.distinct().size` / `ordinalLabel.distinct().size` / `displayName.distinct().size`。

### 14.2 核心接口定义（可直接落地）

#### 14.2.1 数据层

```kotlin
// backend/photo/PhotoSource.kt
package com.nasmusic.tv.backend.photo

enum class PhotoSourceKind { GALLERY, EXTERNAL, JELLYFIN }

/**
 * 来源可用性 —— ⚠️ 用枚举而不是 String：
 * 数据层不产出面向用户的文案（文案在 strings.xml，项目硬约定）。
 */
enum class PhotoSourceStatus {
    OK,                  // 可用
    DISABLED,            // 开关关着
    PERMISSION_DENIED,   // 权限被拒（仅图库）
    PARTIAL_PERMISSION,  // Android 14+「仅选择照片」（仅图库，§9.6）
    NO_DIRECTORY,        // 手机外接存储未选目录
    NOT_CONNECTED,       // Jellyfin：未连 NAS
    NO_PHOTO_LIBRARY,    // Jellyfin：已连但无照片库
    UNAVAILABLE,         // 其他（盘已拔 / 目录不可达）
}

data class PhotoRef(
    val id: String,          // "<kind>:<payload>" —— 全局唯一（§6.1）
    val displayName: String,
    val width: Int,          // ⚠️ 0 = 未探测（见下「宽高契约」）
    val height: Int,
    val size: Long,          // 字节
    val lastModified: Long,  // ⚠️ 秒（各实现内归一化，§6.1）
    val dateAdded: Long,     // ⚠️ 秒
    val source: PhotoSourceKind,
)

interface PhotoSource {
    val kind: PhotoSourceKind
    /** 当前状态（供设置页显示原因；不申请权限、不扫描） */
    suspend fun status(): PhotoSourceStatus
    /** 只读元数据，不加载位图 */
    suspend fun listPhotos(): List<PhotoRef>
    /** 打开原始字节流（供 PhotoBuffer 降采样解码）；失败返回 null */
    suspend fun openStream(ref: PhotoRef): InputStream?
}
```

**⛔ 宽高契约（修正 §十一 R17 的表述）**：`width` / `height` 是**可选元数据**，`0` 表示未探测。

| 来源 | 能否免费拿到宽高 | 取值 |
|---|---|---|
| `MediaStorePhotoSource` | ✅ 查询直接返回 `WIDTH` / `HEIGHT` | 填真实值 |
| `JellyfinPhotoSource` | ✅ `fields=Width,Height` 一并返回 | 填真实值 |
| `ExternalFilePhotoSource` | ❌ 需 `inJustDecodeBounds` **每个文件开一次流** | **填 0**（5000 个文件开 5000 次流不划算） |

⇒ `PhotoBuffer` **必须容忍 `0`**：为 0 时在解码那一刻用 `inJustDecodeBounds` 现算（反正已经拿到流了）。
⇒ 原 §十一 R17 那句「`Width`/`Height` **不能省**」应理解为「**能免费拿到就必须填**」，不是「所有来源都必须填」。

```kotlin
// backend/photo/PhotoSourceAggregator.kt
data class AggregateResult(
    val photos: List<PhotoRef>,
    val perSource: Map<PhotoSourceKind, Int>,
    val statuses: Map<PhotoSourceKind, PhotoSourceStatus>,
)

class PhotoSourceAggregator(
    private val sources: Map<PhotoSourceKind, PhotoSource>,
    private val dedup: PhotoDedup = PhotoDedup(),
) {
    /** 只扫描 [enabled] 里的来源；跨来源去重；可选来源均衡 */
    suspend fun collect(
        enabled: Set<PhotoSourceKind>,
        balance: Boolean = false,
    ): AggregateResult
}
```

```kotlin
// backend/photo/PhotoDedup.kt  —— 独立成文件，纯逻辑，单测门禁对象
class PhotoDedup {
    private class Key(val size: Long, val sec: Long, val name: String) {
        override fun hashCode() = ...   // 或直接用 Triple
        override fun equals(other: Any?) = ...
    }
    /**
     * 保留首次出现；丢弃后续同指纹项。
     * ⚠️ 输入必须是**已归一化为秒**的 PhotoRef（归一化责任在各 PhotoSource，§6.1）。
     * ⚠️ 涉及 JELLYFIN 的项**不去重**（§6.8）。
     */
    fun distinct(refs: List<PhotoRef>): List<PhotoRef>
}
```

#### 14.2.2 缓冲层

```kotlin
// visualizer/photo/PhotoBuffer.kt
class PhotoBuffer(
    private val sourceProvider: (PhotoSourceKind) -> PhotoSource?,
    private val targetWidth: Int,          // 屏幕宽（CROP 时取 max(屏宽, 屏高)）
    private val targetHeight: Int,
    private val maxCached: Int = 3,        // 双缓冲 2 + LRU 1~3
    private val allowRgb565: Boolean = false,  // Tier.BASIC 档降级
) {
    /** 同步取已解码位图；**未就绪返回 null，绝不阻塞**（draw 路径安全） */
    fun peek(ref: PhotoRef): ImageBitmap?
    /** 请求解码（幂等：已在队列 / 已缓存则忽略） */
    fun request(ref: PhotoRef)
    /** 预取下一张（在切换前 1~2 个 HOLD 周期调用） */
    fun prefetch(ref: PhotoRef?)
    /** 拔盘 / 换目录 → 丢弃该来源全部缓存 */
    fun invalidateSource(kind: PhotoSourceKind)
    fun clear()
    fun close()                            // 关闭 Executor + 释放全部 Bitmap
    val estimatedBytes: Long               // 供内存预算断言（单测用）
}
```

**实现要点**

| # | 要点 |
|---|---|
| 1 | 单线程 `Executors.newSingleThreadExecutor()` —— 解码串行，**避免多线程争抢内存峰值** |
| 2 | 解码结果回主线程写入缓存（`Handler(Looper.getMainLooper())`）⇒ `peek()` 与 `draw()` 同线程，**无可见性问题** |
| 3 | `inSampleSize` 计算：宽高为 0 → 先 `inJustDecodeBounds` 现算；否则由 `width/height` 直接算（**不要为了拿 bounds 再开一次流**，Jellyfin 是 HTTP 请求） |
| 4 | `ImageBitmap` 由 `Bitmap.asImageBitmap()` 得到；⚠️ 必须 `inPreferredConfig = ARGB_8888`（`RGB_565` 仅低画质档） |
| 5 | 内存预算：1080p ARGB_8888 单张 = 1920×1080×4 ≈ **8.3 MB** ⇒ 双缓冲 2 张 + LRU 3 张（`maxCached = 3`）≈ **41 MB**（与 §八 的 40 MB 口径一致）；`Tier.BASIC` 降到 1280×720 或 `RGB_565` ≈ 20 MB |
| 6 | **`close()` 必须 `bitmap.recycle()`**（API 22 上 `ImageBitmap` 包装的 `Bitmap` 不会自动回收） |

#### 14.2.3 转场层（本章最核心）

```kotlin
// visualizer/photo/PhotoTransition.kt
enum class PhotoMechanism { M1_CLIP, M2_TRANSFORM, M3_MASK_BITMAP, M4_TILES, M5_SHADER }

/** 一次绘制所需的几何（复用实例，零分配） */
class PhotoGeometry {
    var canvasW = 0f
    var canvasH = 0f
    var minDim = 0f
    var safeAreaPx = 0f
    /** CROP / FIT 计算出的目标矩形（复用，避免 Rect 分配） */
    val dstA = Rect()
    val dstB = Rect()
    val src = Rect()          // 恒为 IntRect.Zero（整图）
    fun update(ctx: RenderContext, scaleMode: PhotoScaleMode, w: Int, h: Int) { ... }
}

interface PhotoTransition {
    val id: PhotoTransitionId
    /** 尺寸 / 画质变化时调用一次；**在此预分配 mask / Paint / Path** */
    fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {}
    /** ⚠️ 零分配：不得 new / 不得创建 List / 不得装箱 / 不得字符串拼接 */
    fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry)
    fun release() {}
}
```

**为什么 `mechanism` / `requiresSequential` / `baseDurationMs` 放在枚举而不是实现类**：
三者都是 `PhotoTransitionPicker`（抽池）与 `PhotoTransitionClock`（定时长 / 定模型）**在实例化之前就要读**的元信息。
放枚举 ⇒ ① 单一来源；② 可写「枚举 ↔ 注册表一致性」门禁单测（§14.4 G4）。

```kotlin
// visualizer/photo/PhotoTransitionId.kt
enum class PhotoTransitionId(
    val displayName: String,
    val mechanism: PhotoMechanism,
    val baseDurationMs: Int,
    val phase: Phase,
    val requiresSequential: Boolean = false,   // 需「暗场」语义 ⇒ 强制模型 1（§5.9）
    val audioReactive: Boolean = false,        // 排除出随机池（§5.9）
    val degradeTo: PhotoTransitionId? = null,  // 老平台（API<33）降级目标（§4.4）
) {
    CROSSFADE("交叉淡化", PhotoMechanism.M1_CLIP, 800, Phase.P0),
    FADE_BLACK("经黑场", PhotoMechanism.M1_CLIP, 800, Phase.P0, requiresSequential = true),
    // … 共 76 项，完整参数见 §14.3（P0 15 种）
    ;
    enum class Phase { P0, P1, P2 }

    companion object {
        /** 当前已实现（跟着分期走） */
        fun implemented(phase: Phase): List<PhotoTransitionId> =
            entries.filter { it.phase.ordinal <= phase.ordinal }

        /** 当前平台的实际标识（M5 在老平台降级后返回 degradeTo） */
        fun effective(id: PhotoTransitionId, sdkInt: Int): PhotoTransitionId =
            if (sdkInt < 33) id.degradeTo ?: id else id

        /**
         * 随机池 —— §5.9 全部规则集中在此，便于单测：
         * ① 排除 audioReactive；② 按平台降级后标识去重；③ 池内去重
         */
        fun randomPool(phase: Phase, sdkInt: Int): IntArray
    }
}
```

```kotlin
// visualizer/photo/PhotoTransitionPicker.kt —— 纯逻辑，无 Compose 依赖（照 RendererSwapper 的先例，可单测）
class PhotoTransitionPicker(private val random: VisualizerRandom) {
    /** 最近 2 次抽中的**池内下标**；-1 = 空 */
    private val recent = IntArray(2) { -1 }

    fun reset() { recent.fill(-1) }

    /**
     * 从 [pool] 抽一个下标。
     * 抽到 recent 里的值就重抽，[retries] 次后**放行**（池很小时必须放行，否则死循环）。
     */
    fun pick(pool: IntArray, retries: Int = 8): Int
}
```

⚠️ `VisualizerRandom` **当前只有 `next()` / `nextSigned()`**（`VisualizerRandom.kt:30/36`），
抽下标需要新增一个零分配方法（约 3 行）：

```kotlin
// visualizer/VisualizerRandom.kt（新增方法）
/** 下一个下标，范围 [0, bound)；bound <= 0 时返回 0 */
fun nextIndex(bound: Int): Int =
    if (bound <= 1) 0 else (next() * bound).toInt().coerceIn(0, bound - 1)
```

```kotlin
// visualizer/photo/PhotoTransitionClock.kt —— 纯逻辑，无 Compose 依赖（单测门禁对象）
class PhotoTransitionClock {
    enum class Phase { IDLE, ENTER, HOLD, EXIT }

    var phase: Phase = Phase.IDLE; private set
    var transitionId: PhotoTransitionId? = null; private set
    /** 当前阶段原始进度 0..1 */
    var progress: Float = 0f; private set
    /** 缓动后进度 0..1（交给转场用） */
    var eased: Float = 0f; private set
    /** HOLD 内 0..1（Ken Burns 用） */
    var holdT: Float = 0f; private set
    /** 本次切换是否已把「新图」提升为「当前图」 */
    var slotSwapped: Boolean = false; private set
    /** 串行模型下处于「间隙」（黑场） */
    var inGap: Boolean = false; private set

    fun start(nowMs: Long, id: PhotoTransitionId, enterMs: Int, holdMs: Int, sequential: Boolean)
    /** 每帧推进；dt 内部由 [nowMs] 差值算并**钳上限** */
    fun advance(nowMs: Long)
    fun reset()

    companion object {
        /** dt 上限 —— 切后台回来 / 掉帧不暴走（记忆里 E37 的同一条规则） */
        const val MAX_DT_MS = 100L
    }
}
```

⛔ **三条必须写对的实现约束**（全部来自真实事故，见项目记忆）：

| # | 约束 | 写错的后果 |
|---|---|---|
| 1 | **相位一律 `+= dt`，绝不用 `nowMs × 系数`** | `ctx.nowMs` 是 `System.currentTimeMillis()`（1.7e12 量级）或 `frame.timeMs`（单调毫秒），乘上任何实时系数会把每帧抖动放大千万倍 → 画面闪现跳档 |
| 2 | **`dt` 必须钳上限**（`MAX_DT_MS = 100`） | 切后台再回来，一帧 dt 可能是几十秒 → 相位瞬间跳到结束 |
| 3 | **模型（交叉 / 串行）必须在 `start()` 那一刻定死并贯穿本次切换** | 切换进行中改模型 → 正在跑的两张图从交叉切成串行，画面跳变（§5.9） |

⚠️ **用哪个时钟**：`frame.timeMs`（`AudioFrame` 的单调时钟，契约即「动画相位用」）。
**不要用 `ctx.nowMs`** —— 它在 `VisualizerStage` 三个调用点语义不一致（`135 行`传 `System.currentTimeMillis()`，`248/283 行`传 `f.timeMs`）。

#### 14.2.4 渲染层

```kotlin
// visualizer/RenderContext.kt（新增 7 个字段 —— 刻意**不加进 update()**）
/**
 * ── 照片墙专用 ──
 * 只有 [VisualizerTheme.PHOTO_WALL] 的渲染器读这些字段，其余 35 个渲染器完全不受影响。
 * 由 PhotoWallController.applyTo(ctx) 每帧写入。
 */
var photoA: ImageBitmap? = null
var photoB: ImageBitmap? = null
var photoProgress: Float = 0f                        // 缓动后 0..1
var photoTransition: PhotoTransitionId? = null
var photoHoldT: Float = 0f                           // HOLD 内 0..1（Ken Burns）
var photoScaleMode: PhotoScaleMode = PhotoScaleMode.CROP
var photoAudioBoost: Float = 0f                      // 音频反应叠加（0 = 关）
```

⚠️ **为什么不加进 `update()`**：`update()` 已有 16 个位置参数、**3 个调用点**
（`VisualizerStage.kt:134 / 247 / 282`），且被 35 个渲染器共用。
往里塞照片字段 = 触碰全部现有渲染器的调用面，风险与收益完全不成比例。
⇒ 改为在 `update()` **之后**单独写：

```kotlin
// VisualizerStage.kt 绘制块（约 251 行 update(...) 之后）
photoWall.applyTo(renderCtx)      // 零分配，只赋值 7 个字段
```

```kotlin
// visualizer/photo/PhotoRenderer.kt
class PhotoRenderer : VisualizerRenderer {
    override val theme = VisualizerTheme.PHOTO_WALL

    private val geom = PhotoGeometry()
    private val masks = MaskCache()
    private val paint = Paint()                 // 成员变量 ⇒ draw 内零分配
    private var bound: PhotoTransition? = null
    private var lastTransitionId: PhotoTransitionId? = null
    private var lastQuality: VisualQuality? = null

    override fun onEnter(ctx: RenderContext) { /* 预分配 + prepare 当前转场 */ }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val a = ctx.photoA ?: return            // 无照片 → 直接返回（底层已是暗底）
        val b = ctx.photoB
        val id = ctx.photoTransition ?: return
        if (id != lastTransitionId || ctx.quality != lastQuality) {
            // 切转场 / 切画质：重新 prepare（唯一允许「看起来像分配」的时刻，且不在稳态每帧）
            bound?.release()
            bound = PhotoTransitionRegistry.get(id)
            bound?.prepare(geom, ctx.quality, masks)
            lastTransitionId = id; lastQuality = ctx.quality
        }
        geom.update(ctx, ctx.photoScaleMode, a.width, a.height)
        bound?.render(a, b ?: a, ctx.photoProgress, geom)
    }

    override fun onExit() { bound?.release(); masks.release() }
}
```

⚠️ **`bound?.render(...)` 里的 `?: a` 是刻意的**：单图场景（只有一张照片 / 下一张未就绪）时 `b == null`，
此时把 `a` 当 `b` 传，转场退化为「同图自转场」—— 视觉上是轻微运动而非闪黑，比 `return` 体验好。

#### 14.2.5 控制器（编排层）

```kotlin
// visualizer/photo/PhotoWallController.kt
class PhotoWallController(
    private val appContext: Context,
    private val prefs: AppPreferences,
    private val sources: Map<PhotoSourceKind, PhotoSource>,
) {
    // ── 由 VisualizerStage 的帧循环每帧调用（主线程）──
    /** 推进时钟；到点则换图 / 抽转场；维护 PhotoBuffer 的预取 */
    fun onFrame(frame: AudioFrame)
    /** 把本帧要画的东西写进 RenderContext（零分配，7 次赋值） */
    fun applyTo(ctx: RenderContext)

    // ── 生命周期 / 设置变更 ──
    fun onSettingsChanged(settings: AppSettings, quality: VisualQuality, sdkInt: Int)
    fun onThemeEntered()     // 切到 PHOTO_WALL：开始扫描（若未扫过）
    fun onThemeExited()      // 离开：停时钟、停预取（**不释放缓存**，便于切回）
    fun rescan()             // 「重新扫描」按钮
    fun close()              // 页面销毁：停线程、释放位图

    // ── 供设置页读取（StateFlow）──
    val perSourceCount: StateFlow<Map<PhotoSourceKind, Int>>
    val mergedCount: StateFlow<Int>
    val statuses: StateFlow<Map<PhotoSourceKind, PhotoSourceStatus>>
}
```

**接线（`VisualizerStage.kt` 两处，共 +3 行）**

```kotlin
// ① 帧循环（现有 159–172 行）内追加一行
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos { ns ->
            tick = ns
            // …现有 crossfade 逻辑不动…
            photoWall.onFrame(frame())        // ← 新增：主线程、与 draw 同帧
        }
    }
}

// ② 绘制块（现有 247–251 行 update(...) 之后）追加一行
renderCtx.update(/* …现有 16 个参数不动… */)
photoWall.applyTo(renderCtx)              // ← 新增
```

⚠️ **线程说明**：`withFrameNanos` 与 `Canvas` 的 draw lambda 都在**主线程**（Compose 的帧时钟与绘制阶段），
所以 `onFrame` 写、`draw` 读之间**不需要任何同步原语**；解码结果由 `PhotoBuffer` 经主线程 Handler 回写。

#### 14.2.6 人脸检测存储（✅ 决策细化：**独立建库**）

⚠️ **修正 §10.3 的表述**：原写「新增 Room 表（升 `LocalMusicDatabase` 版本）」——
**改为独立数据库 `PhotoFaceDatabase`（`photo_face.db`，version = 1）**。理由：

| # | 理由 |
|---|---|
| 1 | `LocalMusicDatabase` **开启了 `fallbackToDestructiveMigration(true)`**（`LocalMusicDatabase.kt:44`）⇒ 任何 schema 变更都会**破坏性重建**；人脸结果重建成本高（1 万张 ≈ 17 分钟，§10.2），不该被音乐索引的 schema 变更连带清掉 |
| 2 | 项目**已有独立建库的先例与明文约定**：`DownloadDatabase` KDoc 写「**独立建库，绝不并入 `LocalMusicDatabase`**」并解释了同一理由 |
| 3 | `app/schemas/` 下已有 2 个库的目录，新增第 3 个不引入新机制 |

**迁移策略**：人脸结果**可由重扫重建** ⇒ 允许 `fallbackToDestructiveMigration(true)`（省掉 Migration 代码）。
⚠️ 但须在 KDoc 写明：**若日后该表开始承载不可重建的数据（如用户手动标记的人脸），必须改为显式 Migration 并去掉 fallback**（与 `LocalMusicDatabase` 的告诫同构）。

```kotlin
// backend/photo/db/PhotoFaceEntity.kt
@Entity(tableName = "photo_face")
data class PhotoFaceEntity(
    @PrimaryKey val photoKey: String,   // = PhotoRef.id（§6.1 全局唯一）
    val hasFace: Boolean,
    val faceCount: Int,
    val detectedAt: Long,               // 检测时刻（ms）
    val fileModifiedSec: Long,          // 检测时的文件修改时间（秒）—— 判是否需重检
)

// backend/photo/db/PhotoFaceDao.kt
@Dao
interface PhotoFaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)     // 照 LocalMusicDao 的既有写法（Room 2.7.1）
    suspend fun upsert(e: PhotoFaceEntity)

    @Query("SELECT photoKey FROM photo_face WHERE hasFace = 1")
    suspend fun faceKeys(): List<String>

    @Query("SELECT * FROM photo_face WHERE photoKey = :key")
    suspend fun find(key: String): PhotoFaceEntity?

    @Query("SELECT COUNT(*) FROM photo_face")
    suspend fun count(): Int

    @Query("DELETE FROM photo_face")
    suspend fun clear()
}
```

### 14.3 P0 15 种转场参数表（照此实现）

⚠️ 缓动统一用 §5.4 列表里的函数；§5.3 表里那个裸 `easeInOut` **不在列表中** ⇒ **统一用 `easeInOutQuad`**（§5.3 该处需同步修正）。

| # | `id` | 机制 | ENTER 基准 | 缓动 | 参数 | 串行 | 遮罩资源 |
|---|---|---|---|---|---|---|---|
| 1 | `CROSSFADE` | M1 | 800ms | `easeInOutQuad` | — | 否 | — |
| 2 | `FADE_BLACK` | M1 | 800ms | `easeInOutQuad` | 底色 `#000` | **是** | — |
| 3 | `SLIDE_LEFT` | M2 | 500ms | `easeOutCubic` | 位移 +X 整屏宽 | 否 | — |
| 4 | `SLIDE_RIGHT` | M2 | 500ms | `easeOutCubic` | 位移 −X | 否 | — |
| 5 | `SLIDE_UP` | M2 | 500ms | `easeOutCubic` | 位移 +Y | 否 | — |
| 6 | `SLIDE_DOWN` | M2 | 500ms | `easeOutCubic` | 位移 −Y | 否 | — |
| 7 | `ZOOM_IN` | M2 | 700ms | `easeInOutCubic` | 旧 `1.00→1.15` + 新 `0.85→1.00` | 否 | — |
| 8 | `ZOOM_OUT` | M2 | 700ms | `easeInOutCubic` | 旧 `1.00→0.85` + 新 `1.15→1.00` | 否 | — |
| 9 | `IRIS_CIRCLE` | M1 | 700ms | `easeInOutQuad` | 半径 `0 → 对角线/2` | 否 | — |
| 10 | `WIPE_LINEAR` | M1 | 700ms | `easeInOutQuad` | 8 方向，**每次随机取 1**（池内算 1 个元素） | 否 | — |
| 11 | `BLINDS_H` | M4 | 900 + 300ms | 每块 `easeOut` | 块数 = `min(14, 屏高/64)` | 否 | — |
| 12 | `BLINDS_V` | M4 | 900 + 300ms | 每块 `easeOut` | 块数 = `min(24, 屏宽/64)` | 否 | — |
| 13 | `NOISE_DISSOLVE` | M3 | 1000ms | `linear` | 阈值 `0→1` | 否 | `noise_256` |
| 14 | `LIGHT_SWEEP` | M2 + `BlendMode` | 600ms | `easeOutQuad` | 高光带宽 `0.25 × minDim` | 否 | — |
| 15 | `SPECTRUM_WIPE` | M1 + 频谱 | 700ms | `easeInOutSine` | 擦除边界 = `spectrum[]` 包络（64 段） | 否 | — |

**M4 块数红线**（§4.2）：电视（ARMv7）≤ **84**（12×7）；手机 / 横屏 ≤ **336**（24×14）。超限自动合并块。

**遮罩资源**：`MaskCache` 在 `onEnter` 时按需生成 256×256 灰度 `ImageBitmap`，**全进程只生成一次**（`LIQUIFY` 等 M3 降级项复用同一张）。
⚠️ 生成用 `Bitmap.createBitmap` + `IntArray` 逐像素写（**只在 onEnter 跑一次，不在绘制路径**）。

### 14.4 单测门禁清单

⚠️ 项目硬约定：**单测护栏一律 `assertTrue(...)`**（Kotlin `assert` 在测试 JVM 是空操作）；
**每个门禁必须有负向自证**（把错误行为如实模拟进去，断言旧写法必须被判出问题）。

| # | 测试类 | 断言内容 | 负向自证 |
|---|---|---|---|
| G1 | `PhotoDedupTest` | 同 `(size, 秒, 名称)` 只留 1 条；`JELLYFIN` 项不参与去重 | 把一条记录的 `lastModified` 故意设成**毫秒** ⇒ 断言「去重失效（2 条都在）」，证明确实依赖归一化 |
| G2 | `PhotoTransitionPickerTest` | 连续 N 次抽取不出现最近 2 次用过的值；池大小 2 时**必定放行**不死循环 | 把 `recent` 容量改成 1 ⇒ 断言出现 `A-B-A-B` 交替 |
| G3 | `PhotoTransitionClockTest` | ① `dt = 10s` 时相位推进不超过 `MAX_DT_MS`；② 串行模型下 `EXIT` 起点晚于 `ENTER` 终点；③ 交叉模型下两者重叠 | 去掉 dt 钳制 ⇒ 断言「一帧跳到结束」 |
| G4 | `PhotoTransitionRegistryTest` | **每个 `PhotoTransitionId` 都能 `get()` 到实现**（枚举 ↔ 注册表一致性） | 注释掉一个注册项 ⇒ 断言失败 |
| G5 | `PhotoTransitionPoolTest` | 池内**无音频反应类**；老平台（`sdkInt = 22`）下 `LIQUIFY` 与 `NOISE_DISSOLVE` **不同时出现** | 用 `sdkInt = 33` 跑同一断言 ⇒ 两者可共存（证明降级去重确实生效） |
| G6 | `PhotoScaleModeTest` | `CROP` 的 dst 覆盖全屏；`FIT` 的 dst 保持宽高比且不出界 | 故意用错比例 ⇒ 断言 dst 出界 |
| G7 | `VisualizerThemeTest`（改） | `entries.size == 36`；`selectable(available = false)` **不含** `PHOTO_WALL` | 传 `true` ⇒ 含 `PHOTO_WALL` |
| G8 | `PhotoWallAvailabilityTest` | 三来源开关「或」派生；三关 ⇒ `false` | 只开 Jellyfin ⇒ `true` |
| G9 | `PhotoRefIdTest` | `id` 形如 `<kind>:<payload>`，跨来源同名文件不冲突 | 两个来源同名文件 ⇒ 断言 `id` 不同 |
| G10 | `PhotoBufferBudgetTest` | 双缓冲 + LRU 的 `estimatedBytes` ≤ 预算 | 把 `maxCached` 调大 ⇒ 断言超预算 |
| **G11** | `KotlinBlockCommentBalanceTest` | **源码无未闭合块注释**（见下「实现期新增」） | 在 KDoc 里写「斜杠紧邻星号」⇒ 断言判出未闭合 |

⚠️ **G11 是实现期新增（2026-09-23，阶段 1）** —— 不在原设计里。理由：Kotlin 块注释**支持嵌套**，
KDoc 里出现「斜杠紧邻星号」（最常见是路径通配符）会开一层永不闭合的注释、吞掉其后全部代码。
本项目已发生**两次**（2026-09-20、2026-09-23）。症状极具误导性：报 `Unclosed comment` 且
**行号落在文件 EOF**（实测报 `:149:1`，而文件只有 148 行），同时依赖该文件的其他文件报一堆
`Unresolved reference` —— 看起来像「新文件没被编译」。护栏用**真词法扫描**（跳过行注释 /
块注释带嵌套深度 / 三引号原始字符串 / 字符串 / 字符字面量），**不是数注释符号个数**
（项目里 7 个文件在字符串字面量里含 glob 模式，计数法会全部误报）。
含负向自证 + 4 条误报防线 + 空转断言。文件：`app/src/test/java/com/nasmusic/tv/util/`。

### 14.5 提交顺序（12 个提交，每个都可独立验证）

| # | 提交 | 内容 | 验收点 |
|---|---|---|---|
| 1 | `fix(storage): 修复 API 22 上 USB 存储枚举失效` | **G7**（§二专节），**独立提交先行** | 电视插 U 盘 → 音乐能扫到（既有缺陷先修好，与照片解耦） |
| 2 | `feat(photo): 新增 PhotoSource 抽象与三来源实现` | 文件 1–4、7 | 单测 G1 / G9 绿；设置页暂不接 |
| 3 | `feat(photo): 新增跨来源聚合与去重` | 文件 5–6 | 单测 G1 绿 |
| 4 | `feat(photo): 新增 PhotoBuffer` | 文件 12 | 单测 G10 绿；手写临时页验证解码不 OOM |
| 5 | `feat(visualizer): 新增转场策略层与 15 种 P0 转场` | 文件 13–15、18、21 | 单测 G4 / G6 绿 |
| 6 | `feat(visualizer): 新增转场随机抽取与时钟` | 文件 16–17 + `VisualizerRandom.nextIndex` | 单测 G2 / G3 / G5 绿 |
| 7 | `feat(visualizer): 新增 PhotoRenderer 与 PHOTO_WALL 枚举值` | 文件 19 + 改 2 / 4 / 5 / 6 | 单测 G7 / G8 绿；**门禁基线 902 → 需同步更新计数** |
| 8 | `feat(settings): 新增「照片墙」设置分区` | 文件 22 + 改 3 / 8 / 9 | 中英 strings 双份齐全；竖屏触摸目标门禁绿 |
| 9 | `feat(photo): 新增照片权限与 SAF 目录导入通道` | 改 1 / 10 / 11 | 真机：拒绝授权 → 开关回弹；选内部存储 → 被拒 + 提示 |
| 10 | `feat(photo): 新增 PhotoWallController 编排与帧循环接线` | 文件 20 + 改 6 / 7 | **首个可上机版本**（P0 完成） |
| 11 | `feat(photo): 新增人脸检测与「仅显示含人像」` | 文件 8–11 | 单测：Room schema 落盘 `app/schemas/` 且入库 |
| 12 | `feat(photo): 补齐 P1 转场至 43 种` | 文件 21 扩充 | 池同步扩容（§5.9：池跟分期走） |

⚠️ **提交纪律（项目约定）**：不要 `git add .`；提交前 `git log --oneline -5`（有并发会话）；
conventional commit + 中文正文；`assembleRelease` **不编译 test 源码** ⇒ 改测试文件后必须跑 `testDebugUnitTest`。

### 14.6 上机验收清单（P0 完成后逐条走）

> 项目约定：**产物就绪后由用户安装**，不要自己往电视上装包；**不要自动运行应用**。
> 上机一律用 **release 包**（`Log.d/v` 被 strip，`assembleDebug` 的日志行为不代表线上）。

**电视（192.168.0.114:5555，SDK 22 / Android 5.1.1 / armeabi-v7a）**

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 插 U 盘（内含 `DCIM`）→ 打开应用 | 「照片墙」效果**可见**（外接存储默认开，§6.8） |
| 2 | 左右切到「照片墙」 | 开始轮播；默认停留 **8.0s**、转场 **0.7s** |
| 3 | 连续观察 20 次切换 | 转场有变化；**不出现连续 2 次相同**（§5.9） |
| 4 | 手动指定 `FADE_BLACK` | 走**串行模型**（有黑场间隙，§5.9） |
| 5 | 拔 U 盘 | **不崩**；缓存清空；切回其他效果正常 |
| 6 | 连续轮播 30 分钟 | `adb shell dumpsys meminfo com.nasmusic.tv` 无持续增长；不 OOM |
| 7 | 帧率观测 | 老电视 ≥ 24fps（`Tier.ADV` 已在 LOW 档隐藏，§7.5） |
| 8 | 把「画面适配」切到「完整」 | 竖幅照片**完整显示 + 左右黑边**（不拉伸、不裁切）；切回「满屏」则铺满并裁掉超出部分 |

**手机（竖屏 + 横屏各一轮）**

| # | 步骤 | 期望 |
|---|---|---|
| 8 | 开「图库」开关 | 弹说明 → 系统权限对话框；**拒绝 → 开关自动回弹为关**（§6.2） |
| 9 | 「选择照片目录」→ 选**内部存储** | 被拒 + 文案说明理由（§6.3） |
| 10 | 选 U 盘 / SD 卡**子目录** | 选中生效；重启应用后**无需重选**（`takePersistableUriPermission`） |
| 11 | 选存储**根目录** | 系统拦下（Android 11+ 硬限制，§6.3） |
| 12 | Android 14 机型：选「**仅选择照片**」→ 杀进程重开 | 开关**不被误回弹**；设置页出现「重新选择照片」入口（§9.6） |
| 13 | 竖屏 / 横屏**各切一次「画面适配」** | 两个方向都生效（**不是竖屏专属**）；布局与触摸目标正常（`portraitTouchTarget`，门禁 `SmallTouchTargetScanTest`） |

**门禁基线（每次提交前）**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat testDebugUnitTest lintDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

⚠️ 基线会从 **902 例**往上走（本章新增约 11 个门禁测试类）——**计数漂移先看是不是本轮的**。

---

## 十五、开发任务清单（可勾选 · 进度追踪）

> **本章是开发期的唯一进度来源**。§14.5 只解释「为什么是这个顺序」，本章是「做到哪了」。
> 每完成一项把 `- [ ]` 改成 `- [x]`，并在行尾追加 `✅ YYYY-MM-DD`。
> ⛔ **不要凭记忆打勾** —— 打勾前必须复跑该行的「验收」判据。

### 15.1 打勾与回顾规程

| 时机 | 动作 |
|---|---|
| 完成**单项** | ① 复跑该行的「验收」判据；② `- [ ]` → `- [x]`；③ 行尾追加 `✅ YYYY-MM-DD` |
| 完成**一个阶段** | ① 复跑该阶段门禁命令；② 勾上阶段级 `- [ ] **阶段完成**`；③ 在 15.2 总览表把该行状态改成 `✅`；④ **回看该阶段的任务描述，若与实现有偏差（改参数 / 换方案 / 加任务），把偏差补写进对应小节**（不是只改勾） |
| 任务被**拆分或放弃** | ⛔ **不要删行** —— 保留行并改成 `- [x] ~~原任务~~ → 实际做法（原因）`。删行会丢掉「为什么当初这么想」 |
| 发现**文档有错** | 就地修正对应小节，并在 §13.2 加一行（来源写「实现期核实」） |

**阶段门禁命令（每个阶段收尾必跑）**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat testDebugUnitTest lintDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

⚠️ 门禁基线从 **902 例 / 0 失败 / lint 0 Error / 272 Warning** 起算。
**计数漂移先确认是不是本轮新增的**（本项目有并发会话改同一工作区的先例）。

### 15.2 进度总览

| 阶段 | 对应提交 | 任务数 | 状态 |
|---|---|---|---|
| 1 G7 修复（前置） | `fix(storage): …` | 2 | 🟨 |
| 2 PhotoSource 抽象与三来源 | `feat(photo): …` | 5 | ✅ |
| 3 聚合与去重 | `feat(photo): …` | 2 | ✅ |
| 4 PhotoBuffer | `feat(photo): …` | 3 | ✅ |
| 5 转场策略层 + 15 种 P0 | `feat(visualizer): …` | 4 | ⬜ |
| 6 随机抽取与时钟 | `feat(visualizer): …` | 4 | ⬜ |
| 7 PhotoRenderer 与 PHOTO_WALL | `feat(visualizer): …` | 5 | ⬜ |
| 8 设置分区 | `feat(settings): …` | 4 | ⬜ |
| 9 权限与 SAF 目录 | `feat(photo): …` | 4 | ⬜ |
| 10 Controller 与帧循环接线 ★ | `feat(photo): …` | 4 | ⬜ |
| 11 人脸检测 | `feat(photo): …` | 4 | ⬜ |
| 12 P1 转场至 43 种 | `feat(photo): …` | 3 | ⬜ |
| **合计** | | **44** | |
| **15.4 全部完成后**（收尾，无独立提交） | — | 3 | ⬜ |
| **总计** | | **47** | |

状态取值：`⬜ 未开始` / `🟨 进行中` / `✅ 已完成` / `⛔ 已放弃`。

⚠️ 上表「任务数」与 §15.3 的 checkbox 数量**逐阶段对得上**（脚本核算：阶段 1~12 = 2/5/2/3/4/4/5/4/4/4/4/3 = **44**）。
15.4 的 3 项**不属于任何阶段**（是全部阶段完成后的文档收尾），因此单列一行，合计 **47**。
⛔ **改任务清单后必须同步改这张表** —— 两处数字不一致时，以 §15.3 的 checkbox 实测为准，并回头修表。

**任务粒度约定**（2026-09-23 按用户要求由 88 项合并为 47 项）

一项 = **一个可独立验证的交付单元**（约 100–400 行代码），不是「一个文件」也不是「一个方法」。
每项下面的**缩进行是该项的要点清单**（⛔ 易错点 / `file:line` / 参数），**不是独立任务、不打勾** ——
合并粒度时靠它保证不丢细节。⛔ 若把要点也改成 checkbox，粒度就又变回细的了。

### 15.3 任务清单

> 缩进要点**不是任务**（不打勾），只是该项的要点清单 —— 见 §15.2「任务粒度约定」。

#### 阶段 1 —— G7 修复（前置，独立提交）　`提交 1`　**2 项**

- [x] **T1.1** G7 修复：`StorageMonitor` 改用广播 `intent.data` + 挂载点探测兜底 + 单测 ✅ 2026-09-23
  - ⛔ `intent.data?.path` **就是挂载点 URI**（`StorageMonitor.kt:64` 已在打这个日志却没使用）
  - 叠加常见挂载点探测兜底：`/mnt/usb0`、`/storage/usb0`、`/mnt/media_rw/sda1` 这类
  - 新增单测覆盖广播解析路径
  - **验收**：API 22 上 `storageDevices` 非空；构造 `ACTION_MEDIA_MOUNTED` intent（`file:///mnt/usb0`）→ 设备列表含该项；新测试类绿
  - **实际**：新增 `backend/local/LegacyStorageProbe.kt`（纯逻辑，黑名单判定 + 探测兜底）；`StorageMonitor` 增 `broadcastMounts` 集合 + `refreshLegacyDevices()`。门禁 `LegacyStorageProbeTest` **13 例绿**；`testDebugUnitTest` 编译 0 错误
  - ⚠️ **实现期新增**：黑名单必须**精确匹配** `/storage/sdcard0`（内部存储）—— 用 `/storage/sdcard` 前缀会误杀 `/storage/sdcard1`（外接 SD 卡）。已写成单测
- [ ] **T1.2** **阶段完成** —— 真机：电视插 U 盘 → **音乐能扫到**（既有缺陷复验，与照片解耦）
  - ⏳ **代码就绪，待用户真机复验**（2026-09-23）：按项目约定不由 AI 装包/运行

#### 阶段 2 —— PhotoSource 抽象与三来源实现　`提交 2`　**5 项**

- [x] **T2.1** 抽象层与共享契约（§14.2.1）✅ 2026-09-23
  - `PhotoSourceKind` / `PhotoSourceStatus` / `PhotoRef` / `PhotoSource`
  - `PhotoScaleMode` 枚举（`CROP` / `FIT`）
  - 图片扩展名白名单 `jpg/jpeg/png/bmp/webp/gif`，**排除 `heic/heif`**（电视 API 22 解不了）
  - ⛔ **单位归一化在三个实现内完成**：`lastModified` / `dateAdded` 一律输出**秒**
  - **验收**：`PhotoRef` 8 字段齐全；扫到 `.heic` 被过滤；G1 负向自证能判出毫秒记录
  - **实际**：`PhotoSource.kt`（168 行）+ `PhotoScaleMode.kt`（82 行，含 `SafDirectoryPolicy` 与 `PhotoIds`）
  - ⚠️ **实现期新增（单测抓到）**：`isSupportedPhotoName` 增加「**以点开头一律拒绝**」——
    首轮单测挂在这条：`.jpg`（点开头且**没有基名**，`lastIndexOf('.') == 0`）被误判成照片。
    顺带修掉一处**不一致**：`ExternalFilePhotoSource` 的 SAF 路线单独写了隐藏文件过滤、File 路线漏了。
    ⇒ 该判定现收口到 `isSupportedPhotoName` 一处（三条来源共用），KDoc 已写明
- [x] **T2.2** `MediaStorePhotoSource`（仅手机）✅ 2026-09-23
  - API 29+ 走 `getContentUri(VOLUME_EXTERNAL)`，23–28 走 `EXTERNAL_CONTENT_URI`
  - **验收**：手机真机列出图库数量 > 0
  - **实际**：130 行；`status()` 把 `PhotoPermissionState.PARTIAL` 映射成 `PARTIAL_PERMISSION`
    （**算可用**，§9.6）；`listPhotos()` 仍过白名单（图库里也可能有 HEIC）
  - ⏳ **代码就绪，待用户真机复验**（2026-09-23）：按项目约定不由 AI 装包/运行
- [x] **T2.3** `ExternalFilePhotoSource`（手机 + 电视）✅ 2026-09-23
  - `File` 遍历路线复用 `MusicScanner.scanPath` 范式（`walkTopDown` + `.nomedia` 过滤 + `MAX_SCAN_DEPTH = 8`）
  - SAF 兜底路线 `DocumentFile.fromTreeUri`
  - ⛔ 内部存储拦截：SAF 卷 ID 校验 `treeDocId.substringBefore(':') == "primary"` → 拒绝
  - ⛔ 电视自动探测用**黑名单** `INTERNAL_PREFIXES`（`/storage/emulated` / `/sdcard` / `/mnt/sdcard` / `/storage/self`）—— **不用白名单**
  - **验收**：电视 U 盘列出照片；手机选目录后列出照片；选内部存储被拒 + 文案正确；探测结果不含内部存储
  - **实际**：238 行。构造参数全是 **provider lambda**（`fileRootsProvider` / `safTreeUriProvider` /
    `commonDirsOnlyProvider`）⇒ 设置页改开关 / 换目录后无需重建实例
  - ⚠️ **实现期偏差 1（依赖）**：**不用 `DocumentFile`** —— `androidx.documentfile` **不在依赖里**，
    改用 `DocumentsContract` 直接 `query()` 子节点（零新增依赖，符合项目「依赖面刻意收窄」取向）。
    ⇒ 遍历改成 `ArrayDeque` **迭代式**（不用递归，避免深目录爆栈）
  - ⚠️ **实现期偏差 2（复用）**：内部存储判定**没有**另写 `INTERNAL_PREFIXES`，而是复用阶段 1 的
    `LegacyStorageProbe.isInternal`（同一判定只有一处实现，与 T1.1 的 G7 修复共用）
  - ⚠️ **实现期取舍**：`commonDirsOnly = true` 时若 `DCIM` / `Pictures` **一个都不存在**，
    回落到整盘扫描 —— 否则「照片放在别处」的用户会看到**一张都不显示**
  - ⏳ **代码就绪，待用户真机复验**（2026-09-23）：电视 U 盘 / 手机选目录 / 选内部存储被拒 三条均为真机项
- [x] **T2.4** `JellyfinPhotoSource` ✅ 2026-09-23
  - `IncludeItemTypes=Photo` + `fields=Width,Height` + `Limit/StartIndex` 分页 + `Images/Primary?maxWidth=1920`
  - **验收**：NAS 上照片库能列出条目
  - **实际**：234 行。`status()` 用 `@Volatile lastKnownTotal` 保持轻量（不每次发请求）；
    文件末尾 `isoToEpochSeconds` 用 `SimpleDateFormat`（⛔ `java.time` 需 API 26，minSdk 22 用不了）
  - ⚠️ **实现期偏差（封装）**：`JellyfinAdapter` 的 `baseUrl` / `apiToken` / `userId` 是私有的 ⇒
    不破封装，改在 `BackendAdapter` 加两个**通用**成员 `buildAuthenticatedUrl(path, query)` /
    `currentUserId`（默认实现 ⇒ `NavidromeAdapter` 不受影响），`JellyfinAdapter` 覆写
  - ⏳ **代码就绪，待用户真机复验**（2026-09-23）：需 NAS 上有照片库
- [x] **T2.5** **阶段完成** —— 门禁 G9（`PhotoRefIdTest`）绿 + 阶段门禁命令全绿 ✅ 2026-09-23
  - **实际**：`PhotoRefIdTest` **19 例**绿（含 3 组负向自证：name-only id 撞车 / `external_primary ≠ primary` / 秒 vs 毫秒）；
    全量 `testDebugUnitTest` + `lintDebug` 绿

#### 阶段 3 —— 跨来源聚合与去重　`提交 3`　**2 项**

- [x] **T3.1** `PhotoSourceAggregator` + `PhotoDedup` ✅ 2026-09-23
  - `collect(enabled, balance)` 只扫已开来源
  - `PhotoDedup` 指纹 `(size, 秒, 名称)`；⛔ **`JELLYFIN` 项不参与去重**
  - 来源均衡（`balance = true` 时每次等概率选来源）
  - **验收**：G1 绿（**含负向自证：喂毫秒记录 → 断言去重失效**）；关掉的来源**不扫描**（可断点/日志确认）；Jellyfin 5000 + U 盘 50 时两者出现频率同量级
  - **实际**：`PhotoDedup.kt`（88 行）+ `PhotoSourceAggregator.kt`（207 行）；单测 `PhotoDedupTest` **10 例** +
    `PhotoSourceAggregatorTest` **11 例**，全绿
  - ⚠️ **实现期偏差 1（语义补充）**：`AggregateResult` 三个字段的**精确含义**在文档里没写清，实现时定死：
    `photos` = 已按 `balance` 排好序的**最终池**（上层用**游标顺序消费**，§6.7「Fisher-Yates + 游标」，
    ⛔ 不要每次重新随机取，那会让「无重复遍历」失效）；`perSource` = **去重后**各来源条数
    （`sum == photos.size`，只含「本平台存在且已开启」的来源）；`statuses` 里关掉的来源由**聚合器**
    填 `DISABLED`（`PhotoSource.status()` 自己不知道开关状态）
  - ⚠️ **实现期偏差 2（算法）**：「来源均衡」**不是**加权交错 —— 加权交错只是把序列排整齐，
    **不会**提高小来源在**序列前段**的密度，而「用户看几分钟就退出」正是本选项要解决的场景。
    实现是「**每轮等概率选一个还没取完的来源**」⇒ Jellyfin 5000 + U 盘 50 时前 100 项里两者**各约 50**
    （单测同时给出**对照**：自然混合下前 100 项 U 盘只有个位数 ⇒ 证明均衡确实改变了分布）
  - ⚠️ **实现期偏差 3（随机源）**：`collect` 的洗牌**不用 `list.shuffled()`**（它走全局 `Random.Default`），
    改为注入 `VisualizerRandom`（§6.5：随机序列只有一处）—— 生产环境由上层注入**共享实例**
  - ⚠️ **实现期新增（负向自证）**：`statusCalls == 0` 这类断言**天然会被空转实现满足**，
    故补一例「**同一来源在开启时确实被调用**」；两半合起来才能证明计数是活的（见 `PhotoSourceAggregatorTest` ⑤）
- [x] **T3.2** **阶段完成** —— 阶段门禁命令全绿 ✅ 2026-09-23
  - **实际**：全量 `testDebugUnitTest` + `lintDebug` 绿（与 T2.5 同一次门禁覆盖）

#### 阶段 4 —— PhotoBuffer　`提交 4`　**3 项**

- [x] **T4.1** 解码管线 ✅ 2026-09-23
  - 单线程 `Executors.newSingleThreadExecutor()` + 解码结果经主线程 `Handler` 回写
  - `inSampleSize`：`width/height == 0` 时用 `inJustDecodeBounds` 现算；非 0 时直接算（**不再开流**）
  - **验收**：`peek()` 与 `draw()` 同线程；Jellyfin 来源只发 1 次 HTTP
  - **实际**：`visualizer/photo/PhotoBuffer.kt`（含同文件的 `internal object PhotoBufferMath`，共 ~350 行）
  - ⚠️ **实现期偏差（关键）**：文档说的「**不再开流**」不够 —— `BitmapFactory` 的流**只能读一次**，
    而拿宽高要「先 `inJustDecodeBounds` 再解码」。**两次开流 = Jellyfin 两次 HTTP**。
    ⇒ 实现改为「**读一次字节 → 全部解码走 `decodeByteArray`**」：有尺寸直接算采样率，无尺寸先读头部再解，
    **两条路都只开 1 次流**。代价是内存里多一份原始字节（只在解码期间存在）。
    单测直接数 `openStream` 调用次数验证（`PhotoBufferTest`：有尺寸 / 无尺寸两条路各一例）
  - ⚠️ **实现期新增（注入点）**：构造参数加 `decoder: ((ref, bytes) -> Bitmap?)?`（默认 null ⇒ 走真实
    `BitmapFactory`）。只为单测存在 —— Robolectric 的 `BitmapFactory` 不真解码、拿不到确定尺寸，
    验「缓存 / LRU / `close()` 释放」这些**与像素无关**的逻辑反而会被它干扰
- [x] **T4.2** 缓存与释放 ✅ 2026-09-23
  - 双缓冲 + LRU（`maxCached = 3`），`inPreferredConfig = ARGB_8888`
  - 低画质档降级（1280×720 或 `RGB_565`）
  - `close()` 释放 Executor + **逐张 `bitmap.recycle()`**
  - **验收**：G10 预算 ≤ 41 MB；`Tier.BASIC` 下内存 ≈ 20 MB；反复进出照片墙 `dumpsys meminfo` 不增长
  - **实际**：LRU 用 `LinkedHashMap(accessOrder = true)`，**淘汰时立即 `recycle()`**；
    缓存条目同时持有 `Bitmap`（供 `recycle`）与 `ImageBitmap`（供 draw 路径**零分配**读取 ——
    若每次 `peek` 都 `asImageBitmap()` 就每帧分配一个包装对象）
  - ⚠️ **实现期偏差 1（口径区分）**：`estimatedBytes` = **预算上限**（`maxCached` × 满尺寸），
    不是当前实际占用 —— 否则「把 `maxCached` 调大 ⇒ 断言超预算」这条 G10 负向自证无法成立。
    实际占用另开 `cachedBytes` / `cachedCount` 两个只读属性供观察
  - ⚠️ **实现期偏差 2（世代号）**：`clear()` / `invalidateSource()` 会**自增世代号**，
    丢弃「解码启动时那一代」的在途结果 —— 否则拔盘后已发出的解码任务完成时会把
    **已失效的照片**写回缓存（用户看到「拔了盘还在显示」）
  - ⚠️ **实现期偏差 3（降级位置）**：`RGB_565` 由 `allowRgb565` 参数开关，`1280×720` 由调用方
    调小 `targetWidth/targetHeight` 实现 —— `PhotoBuffer` 不读 `VisualQuality`（保持无 UI 依赖）
  - ⚠️ **实现期新增（保险）**：`peek` 检查 `bitmap.isRecycled` ⇒ 宁可返回 `null`（画面回落）
    也不把已回收的位图交给 Skia
- [x] **T4.3** **阶段完成** —— 门禁 G10 绿 + 手写临时入口验证 1080p 解码不 OOM ✅ 2026-09-23
  - **实际**：G10 `PhotoBufferBudgetTest` **11 例**绿（纯 JVM，不起 Robolectric）；
    另加行为测试 `PhotoBufferTest` **17 例**绿（覆盖 T4.1 / T4.2 的验收判据）
  - ⏳ **待用户真机复验**：「1080p 真实照片解码不 OOM」与「反复进出 `dumpsys meminfo` 不增长」
    都需要真实图片解码与真机观测 —— 按项目约定不由 AI 装包/运行

#### 阶段 5 —— 转场策略层与 15 种 P0 转场　`提交 5`　**4 项**

- [ ] **T5.1** 转场接口层 + 缓动库
  - `PhotoMechanism` / `PhotoGeometry` / `PhotoTransition` 接口（§14.2.3）
  - `visualizer/Easing.kt` 缓动库（§5.4 全量）
  - **验收**：`render` 内无任何分配（代码审查）；每个缓动函数端点值正确（`f(0)=0`、`f(1)=1`）
- [ ] **T5.2** 枚举与注册表
  - `PhotoTransitionId` 76 值枚举，元信息齐：`mechanism` / `baseDurationMs` / `phase` / `requiresSequential` / `audioReactive` / `degradeTo`
  - `PhotoTransitionRegistry`（id → 实现单例）
  - `MaskCache`：256×256 灰度遮罩，**全进程只生成一次**，仅在 `onEnter` 生成
  - **验收**：76 项全部有 `phase`；G4 绿；绘制路径无 `createBitmap`
- [ ] **T5.3** 15 种 P0 转场实现
  - 参数逐项对齐 §14.3
  - ⛔ M4 块数红线：电视 ≤ 84（12×7），手机 ≤ 336（24×14），超限自动合并
  - **验收**：`CROSSFADE` 800ms / `SLIDE_*` 500ms / `BLINDS_*` 900+300ms 等；老电视上 `BLINDS_*` 不掉帧
- [ ] **T5.4** **阶段完成** —— 门禁 G4 / G6 绿

#### 阶段 6 —— 转场随机抽取与时钟　`提交 6`　**4 项**

- [ ] **T6.1** `VisualizerRandom.nextIndex(bound)` + `PhotoTransitionPicker`
  - `nextIndex` 零分配（约 3 行）
  - Picker 记**最近 2 次**、重试 8 次后**放行**
  - **验收**：`bound = 1` 返回 0 不抛异常；G2 绿（含负向：容量改 1 → `A-B-A-B`）
- [ ] **T6.2** `PhotoTransitionClock`（ENTER / HOLD / EXIT + `slotSwapped` + `inGap`）
  - ⛔ 相位 `+= dt` 累加（**绝不 `nowMs × 系数`**）+ `dt` 钳 `MAX_DT_MS = 100`
  - ⛔ 模型（交叉 / 串行）在 `start()` 定死并贯穿本次切换
  - **验收**：G3 绿；喂 `dt = 10s` 时相位推进不超过一帧步长；切换中改设置不导致画面跳变
- [ ] **T6.3** `PhotoTransitionId.randomPool(phase, sdkInt)`
  - 排除音频反应类 5 种 + 按**降级后**标识去重
  - **验收**：G5 绿
- [ ] **T6.4** **阶段完成** —— 门禁 G2 / G3 / G5 绿

#### 阶段 7 —— PhotoRenderer 与 PHOTO_WALL 枚举值　`提交 7`　**5 项**

- [ ] **T7.1** 枚举值与工厂分支
  - `VisualizerTheme` +`PHOTO_WALL("照片墙", Tier.ADV, "38")`
  - `VisualizerRendererFactory` +`PHOTO_WALL -> PhotoRenderer()` 分支
  - **验收**：序号不与现有 37 冲突；`when` 穷尽编译通过
- [ ] **T7.2** `RenderContext` +7 个 `photo*` 字段（**不加进 `update()`**）
  - **验收**：其余 35 个渲染器零改动
- [ ] **T7.3** ⛔ `selectable` 由 `val` 改函数 + **全量同步调用点**（最易漏的一项）
  - 签名改 `fun selectable(photoWallAvailable: Boolean)`
  - ⛔ **3 个调用点**：`VisualizerStage.kt:363` / `VisualizerViewModel.kt:131`（`step()`）/ `VisualizerRendererFactory.kt:92`
  - ⛔ `VisualizerThemeTest` **5 处硬断言 `35` → `36`**（`entries.size` / `selectable.size` / `selectable.distinct().size` / `ordinalLabel.distinct().size` / `displayName.distinct().size`）+ `selectable` 改函数调用
  - **验收**：三开关全关时列表**不含** `PHOTO_WALL`；三关时左右键**不会**切到照片墙；该测试类绿
- [ ] **T7.4** `PhotoRenderer.draw()`
  - 读 `ctx.photo*`；切转场 / 切画质时 `prepare`；`b ?: a` 单图退化
  - **验收**：只有 1 张照片时不闪黑；门禁 G7 / G8 绿
- [ ] **T7.5** **阶段完成** —— 门禁基线更新（`testDebugUnitTest` 全绿，**涨幅先确认来自本轮**）

#### 阶段 8 —— 「照片墙」设置分区　`提交 8`　**4 项**

- [ ] **T8.1** 设置分区骨架
  - `SettingsSection` 枚举 +`PHOTO_WALL`（含 `titleRes` + `icon`）
  - `SettingsScreen` 的 `when (displaySection)` +1 分支（现有 `when` 在 **474 行**，分支 475–681）
  - **验收**：一级列表出现该分区；点击进入详情页
- [ ] **T8.2** 持久化层
  - `AppPreferences` 新增 `photoWall` 分组 + **17 个 key** + getter/setter（照 `visualizer` 分组写法）
  - ⛔ `AppSettings` 新增 17 个字段，**每个都必须带默认值**（Gson 前向兼容硬约束）
  - **验收**：读写往返一致；用旧备份反序列化不抛异常
- [ ] **T8.3** `PhotoWallSettingsSection` 设置页
  - 按 §7.2 布局实现 **19 行**设置项
  - 文案同步 `values/strings.xml` + `values-en/strings.xml` **双份**
  - 竖屏触摸目标统一走 `portraitTouchTarget(x.dp)`
  - **验收**：逐行对照 §7.2 无遗漏；`check_chinese.py` 通过；门禁 `SmallTouchTargetScanTest` 绿
- [ ] **T8.4** **阶段完成** —— 「画面适配」在**四端（电视 / 手机横 / 手机竖）都渲染**（§7.2 注）

#### 阶段 9 —— 照片权限与 SAF 目录导入通道　`提交 9`　**4 项**

- [ ] **T9.1** Manifest + 权限工具 + 三态判定
  - Manifest +`READ_MEDIA_IMAGES` +`READ_MEDIA_VISUAL_USER_SELECTED`
  - `PermissionHelper.hasPhotoPermission` / `getPhotoPermissions`（照 `hasLocalMusicPermission`，**20 行**）
  - ⛔ 三态判定（`Full` / `Partial` / `FullLegacy` / `Denied`，§9.6 写法）
  - **验收**：`aapt2 dump badging` 可见；Android 14 选「仅选择照片」→ 判为 `Partial` 而非 `Denied`
- [ ] **T9.2** ⛔ 授权时机与刷新（两处最容易做错）
  - ⛔ 授权**由「图库」开关驱动**（**不是** `onCreate` 无条件请求）；拒绝 → 开关**自动回弹为关**
  - ⛔ `onResume` 刷新权限状态（**不能只在启动判一次**）
  - **验收**：真机拒绝后开关变关；系统设置里撤销 → 回到应用即回弹并提示
- [ ] **T9.3** 「部分授权」入口 + 目录选择 launcher
  - 「部分授权」态显示**「重新选择照片」**入口
  - 照片目录选择 launcher（照 `exportTreeLauncher`，**78–83 行**）+ `takePersistableUriPermission`
  - **验收**：点击唤起系统 reselection UI；重启应用无需重选
- [ ] **T9.4** **阶段完成** —— 真机三连：拒绝回弹 / 选内部存储被拒 / 选根目录被系统拦（§14.6 第 8–11 条）

#### 阶段 10 —— PhotoWallController 与帧循环接线　`提交 10`　★ 首个可上机版本　**4 项**

- [ ] **T10.1** `PhotoWallController` + 帧循环接线
  - 全 API：`onFrame` / `applyTo` / `onSettingsChanged` / `onThemeEntered` / `onThemeExited` / `rescan` / `close`
  - `VisualizerStage` 帧循环（**159–172 行**）内 +1 行 `photoWall.onFrame(frame())`
  - `VisualizerStage` 绘制块（**247–251 行** `update(...)` 之后）+1 行 `photoWall.applyTo(renderCtx)`
  - **验收**：`applyTo` 零分配（7 次赋值）；主线程、与 draw 同帧；其余渲染器行为不变
- [ ] **T10.2** 切换驱动链路 + 拔盘处理
  - HOLD 到点 → 抽转场 → 请求下一张 → 换槽
  - 拔盘：清 `PhotoBuffer` 缓存 + 中断预取 + 切回其他效果**不崩**
  - **验收**：默认停留 8.0s / 转场 0.7s 实测吻合；§14.6 电视第 5 条
- [ ] **T10.3** 上机验收：**电视 8 条**（§14.6）
  - **验收**：逐条勾完，**release 包**
- [ ] **T10.4** **阶段完成** —— 上机验收：**手机 13 条**（§14.6），竖屏 + 横屏各一轮

#### 阶段 11 —— 人脸检测与「仅显示含人像」　`提交 11`　**4 项**

- [ ] **T11.1** 模型资产 + 人脸结果库
  - 新建 `app/src/main/assets/models/` 并放入 `yunet_face.onnx`（337 KB）—— ⚠️ `assets/` 目录当前**不存在**
  - `PhotoFaceDatabase`（**独立建库**，`photo_face.db` v1）+ Entity + DAO（§14.2.6）
  - ⛔ schema 落盘 `app/schemas/` 并**入库**（项目硬约定）
  - **验收**：**不**动 `LocalMusicDatabase` 版本；`app/schemas/…PhotoFaceDatabase/1.json` 存在且被 git 跟踪
- [ ] **T11.2** `FaceScanManager`
  - ONNX + **只喂 320×320 缩略图** + 后台分片 + 可中断 + 进度上报
  - **验收**：1 万张进度可中断、可续跑
- [ ] **T11.3** 「仅显示含人像」接线 + 拔盘不清表
  - 按 `hasFace` 过滤
  - 拔盘**不清表**，查询时与 `PhotoSource` 结果做交集
  - **验收**：开开关后池内只剩检出人脸的；重插 U 盘后结果仍有效
- [ ] **T11.4** **阶段完成** —— 低画质档（ARMv7）该功能置灰关闭

#### 阶段 12 —— 补齐 P1 转场至 43 种　`提交 12`　**3 项**

- [ ] **T12.1** 补齐 28 种 P1 转场 + 随机池扩容
  - 随机池同步扩容（`PhotoTransitionId.implemented(Phase.P1)`）
  - **验收**：注册表项数 = 43；G5 复跑绿
- [ ] **T12.2** 设置页转场选择器列出**当前已实现**的全部（§7.2）
  - **验收**：选择器项数 = 43
- [ ] **T12.3** **阶段完成** —— 门禁 G4 / G5 复跑绿（池扩容后去重仍正确）

---

### 15.4 全部完成后　**3 项**

- [ ] **T13.1** 回填文档实测值
  - §14.1 的**实际行数**（对照预估，标注偏差）
  - §12 分期表的实际投入
- [ ] **T13.2** 对外文档
  - README 补「已知限制」：电视内部存储的照片读不到（解法：拷到 U 盘，§6.3）
  - `CHANGELOG.md` 新增当版节（只写「做了什么」）
- [ ] **T13.3** 打 tag + 推送（⚠️ **发布行为，需用户明确要求**）
