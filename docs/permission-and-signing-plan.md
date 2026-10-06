# 权限瘦身、本地音乐总开关与签名统一 —— 开发方案

> **目标**：① 去掉 3 条没有实际价值的权限声明，让**开机零权限弹窗**；② 给「本地音乐」加一个总开关，
> 只有开关打开时才申请 `READ_MEDIA_AUDIO`，顺带修掉「Android 13+ 上本地音乐实际拿不到授权」
> 这个静默 bug；③ 后台播放保活不再主动打扰用户；④ **统一发布签名**，让用户下次更新可以直接覆盖安装。
>
> **范围边界**：① 不改任何 `BackendAdapter` / 网络层；② 不改播放链路与 `MediaSession` 策略
> （§10.165 的 ⛔ 不许 `reject()` 依然生效）；③ 不改照片墙既有权限链路，只在其旁**平行新增**
> 本地音乐的同款链路；④ 不引入新依赖；⑤ 不动 `usesCleartextTraffic` 等已确认的设计取舍；
> ⑥ **签名线只改 CI 配置与工作流守卫，不改 `app/build.gradle.kts` 的签名逻辑本身**。
>
> **状态**：**可开工**（§十三 有 1 项待裁决，不阻塞 A/B/D 三条线）。
>
> ⚠️ **D 线（签名）是 B 线落地的前置条件**，不是可选项 —— 理由见 §1.3，务必先读。
>
> **基线**：v2.38.3（versionCode 172）｜`main` @ `85eaf16`｜上次实跑 `testDebugUnitTest`
> **146 个测试类 / 1479 例 / 0 失败 / 0 错误 / 0 跳过**（来源
> `app/build/test-results/testDebugUnitTest/TEST-*.xml` 逐文件累加，**不是**从构建日志抽的）。
> ⚠️ 开工前必须复跑确认——本项目存在并发会话改同一工作区的情况。
> ⚠️ **本机 Robolectric 只缓存了 SDK 34 与 SDK 30 两个 `android-all` jar**
> （`PhotoPermissionStateTest.kt:29-34` 记录）⇒ **API 33 那一支本机跑不了**，
> 这直接约束了 §七 的门禁设计。

### 文档版本跟踪

| 文档版本 | 日期 | 变更摘要 | 状态 |
|---|---|---|---|
| v1.0 | 2026-10-06 | 初稿至可开发级：§二 11 条权限完整清单 + 裁决结论／§四 权限瘦身逐行改造点（`AndroidManifest.xml` + `MainActivity.kt`）／§五 本地音乐总开关（现状链路 `file:line` 门控点 · 新增偏好 5 跳链路 · 运行期开关的 provider 化设计 · 设置页新 section）／§六 电池优化两条处置路线／§七 单测门禁 3 类（含 Robolectric SDK 限制的规避）／§九 上机验收 U1–U14／§十 4 批提交顺序／§十二 陈旧注释 6 条 | **可开工**（§十三 待裁决 1 项不阻塞） |
| v1.1 | 2026-10-06 | **新增 D 线：发布签名统一**（用户反馈「每次更新都要先卸载旧版」）。① §1.3 记根因：`gh secret list` 返回**空** ⇒ `build.yml:54` 恒走 else ⇒ `build.yml:66` 每次 `keytool -genkey` 生成一次性 `ci-keystore.jks` ⇒ **每次构建签名都不同**（v2.37.4 / v2.37.5 / v2.38.0 / v2.38.2 四次 tag 构建日志实况均为 fallback）；② 新增 **§十四**（含 ⛔ `CRYPTO_PASSPHRASE` 漏配的静默陷阱、PowerShell base64 编码陷阱、日志 grep 的 ANSI 假匹配陷阱、fail-fast 加固 D5）；③ **§7.4 新增 `G4 ReleaseSigningGateTest`**（源码扫描门禁，防 fallback 静默复发）、原 §7.4「既有测试影响」顺延为 **§7.5**；④ §9.4 新增 **V1–V6** 验收（含「首次仍需卸载一次」的历史断档说明）；⑤ §十 新增 **S0** 并声明它是 B 线上线前置；⑥ §十一 新增 4 条签名风险；⑦ §十二 新增 4 条陈旧注释（`build.yml:41-45`、`:45`、`AGENTS.md` CI 段、`app/build.gradle.kts:87-89`）；⑧ 原 §十四「明确不做」顺延为 **§十五**，并追加 3 条（不给 debug 用正式 key、不试图修复签名历史断档、不改 Gradle 签名逻辑）。⛔ **另修 v1.0 遗留的编号冲突**：§5.2 的「门控点 G1–G7」与 §七 的「单测门禁 G1–G4」同字母不同含义 ⇒ 门控点整体改名 **M1–M7**（Master gate），全文 11 处引用同步 | **可开工** |
| v1.2 | 2026-10-06 | **新增 §十六 完工后的权限全景（目标态速查）**（用户要求补「完工后剩余什么权限、干什么用、什么时候申请」）。含 §16.1 声明与申请时机总表（8 条 · 逐条标注**保护级别**与**申请时机** · 拒绝后果）／§16.2 按 Android 版本的实际弹窗矩阵（API 22–28 / 29–32 / 33 / 34+ 四档，标出全新用户最多被问 1–2 次）／§16.3 **不需要权限但仍要用户「给一下」** 的 5 项（SAF 两处 · MediaStore Downloads · 应用专属目录 · 电池优化）／§16.4 权限↔功能对照（含「已下载歌曲不受总开关影响」这条容易漏的边界）／§16.5 与 §二 的对照摘要。⛔ §16.5 明确写入一条**对外表述纪律**：dangerous 权限**数量没有减少**（仍 4 条），本方案只是把申请时机从「开机无条件」改成「按需」⇒ **发版说明不得写成「减少了权限」**。⚠️ §16.2 另标 1 项**待实测**（Android 13 权限迁移可能让老用户一个弹窗都看不到）并说明 §五 的设计对此安全 | **可开工** |

---

## §一 背景与问题

### §1.1 四个用户诉求

| # | 诉求 | 裁决 |
|---|---|---|
| 1 | 设置里加一个「本地音乐」总开关，**只有开关打开时才申请权限** | ✅ 采纳，见 §五 |
| 2 | 通知权限没必要申请——什么时候给用户发通知了？ | ✅ 采纳，**整条删除**，见 §4.1-A2 / §4.2 |
| 3 | 后台播放保活权限没必要申请，用户自行操作即可 | ✅ 采纳，改为**不自动触发**，见 §六 |
| 4 | 希望统一应用签名，**每次更新都要先卸载旧版太麻烦** | ✅ 采纳，见 §十四 |

### §1.2 现状事实（全部经源码核对，非推测）

- **权限声明 11 条**，其中 `WRITE_EXTERNAL_STORAGE` / `POST_NOTIFICATIONS` /
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 三条**没有任何实际价值**（§二）。
- **运行时弹窗只有 2 处**：
  ① `MainActivity.kt:198-210` 在 `onCreate` **无条件**申请 `POST_NOTIFICATIONS`（Android 13+），
  这是全仓**唯一**一处 `ActivityCompat.requestPermissions(` 调用（grep 确认 1 命中）；
  ② `MainActivity.kt:220-224` 由设置页「图库」开关触发的照片权限（设计正确，是本方案的模板）。
- **全仓唯一的 `notify()` 调用点**是 `PlaybackService.kt:980-981`，且是媒体通知。
  ⛔ **「下载通知」不是系统通知**：`SongDownloadManager.onNotify` / `onProgress`
  的消息走 `NasMusicApp._downloadNotifyMessage`（`:121-122`）→ `MainViewModel.kt:2428-2432`
  → `showError(msg)` → UI 上的一条提示。`MainActivity.kt:197` 那句
  「下载通知依赖 POST_NOTIFICATIONS」的注释**是错的**，必须一并修正（§十二 第 2 条）。
- **`PermissionHelper.getLocalMusicPermissions()` / `hasLocalMusicPermission()`
  （`util/PermissionHelper.kt:20-42`）在 `app/src/main` 里零调用方**
  ⇒ Android 13+ 上 `MusicScanner` 的 `MediaStore` 查询必然被拒，
  而 `MusicScanner.kt:122-124` 是宽泛的 `catch (e: Exception)`
  ⇒ **被拒与空曲库不可区分**，用户看到的是「本地音乐永远是空的」。这是本方案 B 线要修的真 bug。
- **`refreshLocalMusic()`（`MainViewModel.kt:1901-1913`）零调用方**（全 `app/src` grep 确认），
  是死代码。§五 会把它接成设置页的「重新扫描」按钮。

### §1.3 签名问题：为什么每次更新都要卸载（含根因取证）

**结论：是的，GitHub 每次构建用的签名都不一样。** 三条证据：

| # | 证据 | 内容 |
|---|---|---|
| 1 | `gh secret list`（仓库 `hxzhang2000/NASMusicTV`） | 返回**空** —— 一个 secret 都没配 |
| 2 | `.github/workflows/build.yml:54` | `if [ -n "$SIGNING_KEYSTORE_BASE64" ]` 恒为假 ⇒ 走 else 分支 ⇒ `build.yml:66` 每次构建现场 `keytool -genkey -v -keystore ci-keystore.jks -alias ci ...` 生成**全新**密钥 |
| 3 | 最近四次 tag 构建日志实况 | `v2.37.5`（run 36305889977）／`v2.38.0`（run 36964905658）／`v2.38.2`（run 37310425320）**全部**输出 `No signing secrets found, falling back to throwaway CI keystore` + `storeFile=ci-keystore.jks` |

⇒ 每次从 Releases 下载的 APK 签名证书都不同 ⇒ `adb install -r` 报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` ⇒ 只能先卸载。**与设备、ROM、versionCode 都无关。**

**⛔ 取证陷阱（我自己差点踩）**：`build.yml:53-74` 是 `run: |` 块，GitHub 会**先把整个脚本文本回显一遍**，
带 ANSI 转义（`^[[36;1m  echo "Using release keystore from GitHub Secrets"`）。
⇒ 直接 grep `Using release keystore` 会**同时命中两个分支**，看起来像走了正式签名。
**必须只匹配无 ANSI 的真实执行输出行**：
`No signing secrets found, falling back to throwaway CI keystore` 与 `storeFile=ci-keystore.jks`。

**为什么这条是 B 线的前置条件（不是可选项）**：

1. 每次强制卸载 ⇒ **DataStore 与全部本地数据被清空**。而 NAS 服务器凭据是
   AES-GCM 加密后落在本地的 ⇒ 用户每次更新都要**重新输一遍服务器密码**。
2. B 线要引入的 `local_music_enabled` 也存在 DataStore 里 ⇒ 每次卸载后回落默认值 `true`
   （§5.1-D1）⇒ 用户装到新版永远是「开」，**根本走不到「关→开申请权限」那条修复路径**，
   本方案最核心的 bug 修复等于没发货。
3. 卸载还会连带清掉 Demucs 模型缓存、下载索引、照片墙授权记录等。

⇒ **先修签名，再发权限改动。** 提交顺序见 §十 的 S0。

---

## §二 权限清单与裁决

### §2.1 完整清单（11 条声明）

| # | 权限 | 生效范围 | 用途 | 必需性 | 裁决 |
|---|---|---|---|---|---|
| 1 | `INTERNET` | 全部 | NAS / 飞牛 / 网盘 / 网络音乐 / 天气 API、本地 HTTP 遥控服务 | **必须** | 保留 |
| 2 | `ACCESS_NETWORK_STATE` | 全部 | 判断网络类型与可用性 | **必须** | 保留 |
| 3 | `READ_MEDIA_AUDIO` | Android 13+ | 本地音乐（`MusicScanner` 扫 `MediaStore`） | **必须**（功能可选） | 保留，**改为按需申请**（§五） |
| 4 | `READ_EXTERNAL_STORAGE` | `maxSdkVersion=32` | 旧版本本地音乐 + 照片墙 | **必须**（功能可选） | 保留（仅 API≤32 有效），同样按需申请 |
| 5 | `READ_MEDIA_IMAGES` | Android 13+ | 照片墙「图库」 | **必须**（功能可选） | 保留，**已**按需申请 |
| 6 | `READ_MEDIA_VISUAL_USER_SELECTED` | Android 14+ | 「仅选择照片」部分授权的持久标识 | **必须** | 保留，随图库开关一起申请 |
| 7 | `FOREGROUND_SERVICE` | Android 9+ | 后台播放前台服务 | **必须** | 保留 |
| 8 | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Android 14+ | 同上，`mediaPlayback` 类型 | **必须** | 保留 |
| 9 | `WRITE_EXTERNAL_STORAGE` | `maxSdkVersion=28` | **仅** `BackupFileUtils.kt:38-42 / 86-91` 在 API<29 往公共 Downloads 写一份**辅助**备份副本 | **可去掉** | ❌ **删除** |
| 10 | `POST_NOTIFICATIONS` | Android 13+ | 仅媒体通知（平台已豁免） | **可去掉** | ❌ **删除** |
| 11 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Android 6+ | 电池优化白名单 | **可去掉** | ❌ **删除**（§六） |

**去掉 3 条，保留 8 条。**

### §2.2 为什么 #9 可以去掉

`BackupFileUtils.export()` 的双写逻辑（`:63-101`）：

- API ≥ 29：走 `MediaStore.Downloads`（`:66-78`），**不需要任何权限**；
- API < 29：**主备份**写 `filesDir/backups/`（`:81-85`，应用内部存储，同样不需要权限）；
  公共 Downloads 副本（`:86-91`）已经被 `try/catch` 包成「尽力而为」，
  且 KDoc `:20-22` 自己就写着「部分电视 ROM 的公共存储是 RAM-backed，断电即丢失，故内部存储才是可靠主备份」。

⇒ 删掉 `WRITE_EXTERNAL_STORAGE` 的唯一后果：**API 24–28 设备少一份公共目录副本**，
主备份与「恢复」功能完全不受影响。这条本来就只是给文件管理器看的便利副本。

### §2.3 为什么 #10 可以去掉（三重依据）

1. **Media3 源码明写豁免**：`MediaNotificationManager.updateNotificationInternal()` 带
   `@SuppressLint("MissingPermission")` 并**无条件**调用 `notificationManager.notify(...)`，
   注释直接指向官方豁免条款。
2. **平台侧强制放行**：AOSP `NotificationManagerService.PostNotificationRunnable` 的 block check 是
   `if (!(notification.isMediaNotification() || ...) && (appBanned || ...)) return false;`
   —— 按 **`MediaStyle` 模板**放行，不看有没有活跃 `MediaSession`。
   CDD 的措辞是「**must** be exempt」。
3. **本项目满足模板条件**：`PlaybackService.kt:1055-1061` 设了
   `androidx.media.app.NotificationCompat.MediaStyle()` + `.setMediaSession(...)`。

官方文档「Notifications exemptions」小节原文：
> “Notifications related to media sessions are exempt from this behavior change.”

⚠️ **注意区分**：**普通**前台服务通知**不**豁免（被拒时只在任务管理器可见、通知栏不显示）。
我们能豁免靠的是 `MediaStyle` 模板，**不是**「因为是前台服务」。
⇒ 这条依据绑定在 `PlaybackService` 的通知构造上，**谁去改那段通知模板就会连带失去豁免**，
必须留注释（§十二 第 5 条）。

**删掉后仍成立的**（不依赖通知权限，全部走 `MediaSession` 这条独立的 Binder 通道）：
前台服务照常 `startForeground()`、照常播放；蓝牙 AVRCP / 锁屏 / Android Auto 系统媒体控制全部照常。
项目已有铁律 `MediaSession.Callback.onConnect` ⛔ 不许 `reject()`（§10.165）
—— 那条比通知权限重要得多，两者互不相关。

---

## §三 方案总览

三条改动线，**可独立提交**，互不阻塞：

| 线 | 内容 | 影响范围 | 风险 |
|---|---|---|---|
| **A** | 权限瘦身：删 3 条声明 + 删开机弹窗 | 权限 #9 #10 #11 | **低**（纯删除，且无任何单测引用） |
| **B** | 本地音乐总开关 + 按需申请权限 | 权限 #3 #4 的**申请时机** | **中**（新增持久化偏好 + 6 处门控点） |
| **C** | 电池优化降级 | 权限 #11 的**触发时机** | **低**（待裁决，见 §十三） |
| **D** | 发布签名统一：配齐 5 个 repo secrets + CI fail-fast 加固 | **不影响 Android 权限**，改的是「用户能否覆盖安装」 | **低**（配置项，不改代码逻辑；但配错会静默产出错包，见 §14.2） |

提交顺序见 §十。**A 线不依赖 B/C/D，可先合、先验；但 S0（D）必须最先发版**，理由见 §1.3。

---

## §四 改动线 A：权限瘦身

### §4.1 `AndroidManifest.xml`

逐行改造，共 3 处删除 + 1 处注释同步：

| 位置 | 动作 |
|---|---|
| `:6-7` | ❌ 删除 `WRITE_EXTERNAL_STORAGE`（含 `maxSdkVersion="28"`）整块 |
| `:26` | ❌ 删除 `POST_NOTIFICATIONS` |
| `:27` | ❌ 删除 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（与 §六 联动；若 §十三 裁决走 C2 则**保留**） |
| `:10` | 📝 注释补一句：`READ_MEDIA_AUDIO` 由「本地音乐」开关按需申请（`MainActivity` 的 `localMusicPermissionLauncher`），不再于启动时申请 |

⚠️ **删除顺序无所谓**（Manifest 不允许重复声明，删掉即彻底移除）。
⚠️ **不要顺手调整保留条目的顺序**——顺序无语义，但 diff 会变脏。

### §4.2 `MainActivity.kt`

**删除 `:197-210` 整块**，即：

```kotlin
        // 修复（M-5）：Android 13+ 通知运行时权限——媒体通知/下载通知依赖 POST_NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT >= 33 && ... ) {
            try {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001
                )
            } catch (e: Exception) { ... }
        }
```

连带效果：`requestCode = 2001` 这个编号在本仓**没有任何接收方**
（全仓无 `onRequestPermissionsResult` 覆写，grep 确认）⇒ 整块删除不留残迹。

删除后 `MainActivity.kt:194-227` 段只剩三件事：电池优化调用（§六）、`exportTreeLauncher` 注入、
`photoPermissionLauncher` / `photoDirectoryLauncher` 注入。**结构不变**。

---

## §五 改动线 B：本地音乐总开关

### §5.1 设计决策（先看这四条，底下都是实现）

| # | 决策 | 理由 |
|---|---|---|
| D1 | 偏好 `local_music_enabled` **默认 `true`** | **零回归**。今天的行为一字不变，也**不在启动时弹任何窗**。开关的作用是让用户能**关掉**，以及在**关→开**时触发权限申请（从而修掉 bug）。 |
| D2 | 关闭时**保留 Room 索引**，只清 `_localSongs` 内存态 | 否则用户下次开开关要重扫全盘。索引是本地缓存，不是隐私数据。 |
| D3 | 关闭时**不影响已下载歌曲** | `NasMusicApp.kt:406-451` 的 `upsertDownloaded()` 读的是**应用专属目录自己写的文件**，与 `MediaStore` / `READ_MEDIA_AUDIO` 无关；下载曲另有 `MusicSourceType.DOWNLOAD` 出口。⛔ 切勿把 `MusicSourceType.LOCAL` 的门控波及到 `DOWNLOAD`。 |
| D4 | 开关状态进 `SearchAggregator` 必须用 **provider 闭包**，不能传快照 | `SearchAggregator` 在 `NasMusicApp.kt:499` 进程内构造**一次**，而开关是运行期可变。传快照 = 改了开关搜索仍走本地源。⚠️ 本项目已有同类教训：`BackendAuthHeaders` 的 `provider` 形态就是为了不漏掉静默重登换新令牌（AGENTS.md）。 |

### §5.2 现状链路与门控点（`file:line` 全清单）

```
NasMusicApp.onCreate
 ├─ :352  localMusicRepository = LocalMusicRepository(this, dao, MusicScanner(this))
 ├─ :354  storageMonitor.startListening()          ← USB 广播接收器，进程级，活到进程结束
 └─ :494-501  searchAggregator = SearchAggregator(..., localMusicRepository = ..., ...)
                ⚠️ 进程内构造一次 ⇒ 开关必须走 provider（D4）

MainViewModel（⚠️ 有 6 个 init 块：:544 :621 :709 :2428 :3414 :3467，本节相关的是 :709 那个）
 └─ :874-913  本地音乐启动块
     ├─ 步骤1 :878  loadFromCache()                  ← 门控点 M1
     ├─ 步骤2 :888  incrementalScan()               ← 门控点 M2
     └─ 步骤3 :899  storageMonitor.onDeviceMounted.collect { scanUsbDevice() }  ← 门控点 M3
         ⚠️ 步骤3 是**同一 launch 内的非终止挂起** ⇒ 不能在步骤2 之后 return，
            只能把整个 launch 用 if 包住

其他触达 _localSongs 的点
 ├─ :815   下载完成监听 → loadFromCache()          ← 门控点 M4（关闭时不该被触发）
 ├─ :2441  downloadVM.onLocalSongsChanged → 同上   ← 门控点 M5（⚠️ 与 D3 相关，见下）
 ├─ :1901  refreshLocalMusic() → fullScan()        ← 死代码，改由设置页按钮调用
 └─ :3443  searchVM.localDeviceSongsProvider = { _localSongs.value }   ← 门控点 M6

SearchAggregator
 └─ :256   if (MusicSourceType.LOCAL in sources && localMusicRepository != null)   ← 门控点 M7

PermissionHelper
 ├─ :20-32  hasLocalMusicPermission()      ← 零调用方，本次接上
 └─ :37-42  getLocalMusicPermissions()     ← 零调用方，本次接上
```

⚠️ **M4 / M5 与 D3 的边界**：这两处是「下载完成后刷新本地曲库」。
关闭总开关时它们**仍会跑** `loadFromCache()`，而 `loadFromCache()` 读的是 Room 全表（含下载曲目）。
⇒ **设计裁定**：`_localSongs` 的内容 = **Room 全表**，不受总开关影响；
总开关只 gate **扫描行为**（M1/M2/M3）、**搜索源**（M7）与 **UI 展示**。
这样 D3「不影响下载」自动成立，无需在 M4/M5 加判断。
UI 层要隐藏时，用一个新的派生 StateFlow（见 §5.3 的 `visibleLocalSongs`）。

### §5.3 新增偏好（5 跳链路，照抄 `photoWallExternalEnabled`）

⚠️ `AppPreferences` **不是 SharedPreferences，是 DataStore**
（`AppPreferences.kt:114-118`，`filesDir/datastore/nas_music_tv.preferences_pb`）。
全文件**没有任何 `MutableStateFlow`**，也**没有 `Flow<Boolean>` 单字段访问器**。
一个布尔偏好的完整链路只有 5 跳：

| # | 文件:行 | 动作 |
|---|---|---|
| 1 | `data/prefs/AppPreferences.kt:321` 附近 | 加 `private val keyLocalMusicEnabled = booleanPreferencesKey("local_music_enabled")` |
| 2 | `AppPreferences.kt:805-806` 附近 | 在唯一的 `appSettings: Flow<AppSettings>` map 里加一行：`localMusicEnabled = prefs[keyLocalMusicEnabled] ?: true,` |
| 3 | `AppPreferences.kt:883` 附近 | 加 `suspend fun setLocalMusicEnabled(v: Boolean) = dataStore.edit { it[keyLocalMusicEnabled] = v }` |
| 4 | `data/model/AppSettings.kt:51` 附近 | 加字段 —— ⚠️ **必须给默认值** |
| 5 | `AppPreferences.kt:1970-1978` 附近 | `importBackupData()` 里加一行，否则备份/恢复不带这个字段 |

⚠️ **第 4 跳的默认值不是可选的**：`AppSettings.kt:42-48` 的 KDoc 记录了
「字段没有默认值 ⇒ Kotlin 生成的无参构造消失 ⇒ Gson 回退 `UnsafeAllocator` ⇒
所有字段变 JVM 默认值、non-null 枚举变 `null`」，**旧备份导入会整体崩**。
必须照抄 `:172-191` 那条 legacy-backup 单测作为门禁（§七 G2）。

⚠️ **不给 `local_music_enabled` 加门面类**。照片墙有 `PhotoWallPrefs` 是因为它有 17 个字段；
本地音乐只有 1 个，直接在 `AppPreferences` 上放 `setLocalMusicEnabled` 即可，
`SettingsBranch` 侧用 `appSettings` 的 `collectAsState` 读。

### §5.4 门控实现（6 个门控点 + 1 个运行期观察者）

**新增派生流**（放在 `MainViewModel` 状态字段区 `:299-309` 附近）：

```
visibleLocalSongs: StateFlow<List<Song>> = combine(_localSongs, 开关) { songs, on -> if (on) songs else emptyList() }
                                                                     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _localSongs.value)
```

⚠️ 初始值取 `_localSongs.value` 而不是 `emptyList()`，否则首帧会闪一下空列表。

**M1–M3**：`MainViewModel.kt:874-913` 整块用
`if (nasMusicApp.appPreferences.appSettings.first().localMusicEnabled)` 包住。
⚠️ `appSettings` 是 `Flow`，取一次值要 `first()`；这一步在 `viewModelScope.launch` 里，
已是挂起上下文，**不需要** `runBlocking`。
⚠️ `MainActivity.kt:181` 已有「同步读走 `@Volatile` 镜像，零 IO、零 `runBlocking`」的既定做法，
但那是 `DisplayPrefs` 专用的冷启动镜像。**本地音乐这里不需要镜像**——用 `first()` 即可，
不要为此新增第三套镜像机制。

**运行期观察者（新增，这是本方案最容易漏的一块）**：开关在**设置页**被打开时，
启动块早已跑完 ⇒ 只做「启动时判断」等于**开关打开后永远不扫描**。
必须在启动块里**无条件**注册一个观察者：

```
viewModelScope.launch {
    var wasEnabled = <启动时的初值>
    nasMusicApp.appPreferences.appSettings
        .map { it.localMusicEnabled }
        .distinctUntilChanged()
        .collect { enabled ->
            if (enabled && !wasEnabled) startLocalMusicRuntime()   // 加载缓存 + 扫描 + 订阅 USB
            if (!enabled && wasEnabled) { /* 无需动 Room 索引；visibleLocalSongs 自动清空 */ }
            wasEnabled = enabled
        }
}
```

⇒ 把 `:874-913` 的三步**抽成 `private fun startLocalMusicRuntime()`**（`viewModelScope` 内 launch），
启动块与观察者都调它。⛔ **不要**把 `wasEnabled` 写成 `var` 在 `MainViewModel` 类字段上再跨协程共享。

**M6**：`MainViewModel.kt:3443` 改为 `searchVM.localDeviceSongsProvider = { visibleLocalSongs.value }`。

**M7**：`SearchAggregator.kt:256` 的条件追加 `&& localMusicEnabled()`，
其中 `localMusicEnabled` 是构造参数 **`() -> Boolean`**（见 D4）。
`NasMusicApp.kt:494-501` 构造处传 `{ appPreferences.appSettings.firstOrNull()?.localMusicEnabled ?: true }`
—— ⚠️ 在 `SearchAggregator` 的 `async {}` 里首次求值会挂起，但 `async` 是挂起上下文，
`firstOrNull()` 可用；若嫌每次搜索都读 DataStore，加一层 `@Volatile` 快照 + pref 变化时更新
（**不要**只在构造时读一次）。

**M1/M2 的反向保护**：`MusicScanner.scanAllMusic()` 的宽泛 catch
（`:122-124`）会把 `SecurityException` 吞成空列表，而
`LocalMusicRepository.incrementalScan()` 的「空扫描保护」（`:64-68`）与
`fullScan()` 的「破坏性重建保护」（`:190-193`）又会把空列表当成「曲库全被删」而**跳过删除判定**。
⇒ 好消息是这两道保护恰好让「无权限」**不会误删曲库**；
但用户仍会看到「刷新后什么都没有」。因此 §5.5 的权限申请是**必须**的，不是可选优化。

### §5.5 权限申请接线（照抄照片墙模板，一个字都不要创新）

`PermissionHelper` 的两个函数**已经写好了**，本次只是接上，不需要改它的实现。

| # | 文件:行 | 动作 |
|---|---|---|
| 1 | `ui/MainActivity.kt:95-108` 附近 | 新增 `private val localMusicPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.localMusicPermissionResult() }`，⛔ **注释照抄 `:96-99`**（「不在这里触发，由开关驱动」） |
| 2 | `MainActivity.kt:212` 附近 | 注入 `viewModel.localMusicPermissionLauncher = { localMusicPermissionLauncher.launch(PermissionHelper.getLocalMusicPermissions()) }` |
| 3 | `MainActivity.kt:482-501` `onDestroy` | 在 `runCatching` 里加 `viewModel.localMusicPermissionLauncher = null`（⚠️ 该块有 `if (!isFinishing) return` 守卫，别改守卫） |
| 4 | `MainActivity.kt:524-537` `onResume` | 加 `viewModel.refreshLocalMusicAccess()`，与 `refreshPhotoAccess()` 同款 |
| 5 | `MainViewModel` 状态字段区 | 新增 `var localMusicPermissionLauncher: (() -> Unit)? = null`、`private val _localMusicPermissionState = MutableStateFlow(localMusicStateNow())`（**初值现查，不读缓存**，照抄 `VisualizerViewModel.kt:132-135`）、`private val _localMusicNotice = MutableSharedFlow<LocalMusicNotice>(replay = 0, extraBufferCapacity = 4)` |

**三个入口函数**（签名与行为照抄 `VisualizerViewModel.kt:511-523 / 531-538 / 547-565`）：

```
fun setLocalMusicEnabled(enabled: Boolean)
    false → 直接落盘 false，结束
    true  → 重读权限态；已授权 → 直接落盘 true；否则 requestLocalMusicPermission()

fun requestLocalMusicPermission()
    launcher == null → tryEmit(LOCAL_MUSIC_DENIED)，⛔ 绝不静默把开关打开

fun onLocalMusicPermissionResult()
    重读权限态（⛔ 忽略回调里的 Map，理由同 :99-103 的 Android 14 部分授权）
    已授权 → 落盘 true + 启动扫描；拒绝 → 落盘 false + tryEmit(LOCAL_MUSIC_DENIED)
```

⚠️ **回弹必须做**：`onResume` 里若发现开关是开但权限已被撤销 ⇒ 落盘 `false` + 提示。
这正是照片墙 `refreshPhotoAccess()`（`:621-636`）+ `applyGalleryRollback()`（`:645-655`）
带 `galleryRollbackPending` 重入守卫解决的问题，本地音乐照抄同样的守卫。

`LocalMusicNotice` 枚举照抄 `PhotoAccessNotice`（`VisualizerViewModel.kt:57-87`）的形状：
`@StringRes val messageRes: Int` + `MainViewModel` 侧 collect 后才取文案
（`MainViewModel.kt:2455-2463` 的注释说得很清楚：**数据层不产出面向用户的文案**）。

### §5.6 设置页（新 section）

现状：`SettingsBranch.kt`（377 行）**不是分区切换器**，只是一个 state-hoisting 委托，
末尾一次 `SettingsScreen(...)`；真正的分区列表是枚举
`ui/screens/settings/SettingsSection.kt:33-51`，在 `ui/screens/SettingsScreen.kt` 的
`LazyColumn` 里逐分支 dispatch（`:513 / :530 / :577 / :591 / :614 / :626 / :636 / :649 / :676`）。

**新增 `LOCAL_MUSIC` 分区**，插在 `PHOTO_WALL`（`:43`）与 `DOWNLOAD`（`:44`）之间。
⚠️ `SettingsSection.kt:36-42` 的 KDoc 已明确写了这条插入是安全的：
「本项目**没有**持久化「分区顺序」，也没有任何测试断言 `entries` 的顺序」。

配套改动（缺任一项都会漏）：
- `strings.xml` 新增 `settings_local_music`（标题）、`settings_local_music_source`（开关名）、
  `settings_local_music_source_desc`（描述，**必须写明「打开后才会申请音乐权限」**，
  与 `:313` 图库那条同款口径）、`settings_local_music_rescan` / `_desc`、
  `settings_local_music_count`（`已入库 %d 首`）、`local_music_denied`（拒绝提示）。
  ⛔ 字符串一律进资源，**不要硬编码**（HEAD `85eaf16` 刚做完全量文本资源化）。
- `SettingsScreen.kt`：新增 `when` 分支 + 该分区的焦点 requester（`:313-314` 是逐分区预建的）。
- 新建 `ui/screens/settings/LocalMusicSettingsSection.kt`：一个 `SettingSwitch` +
  一个 `SettingsInfoRow`（曲目数）+ 一个 `SettingActionButton`（重新扫描）。
  ⚠️ `SettingSwitch`（`SettingsComponents.kt:87-93`）**签名是
  `(label, description, checked, onClick, enabled)`，没有尾随 lambda**，
  且**没有 `Switch` 控件**——开关状态是用文字渲染的（`:118-122`），
  ⛔ 别按标准 Compose 习惯去套 `Switch`。
  ⚠️ 小触控目标门禁（`SmallTouchTargetScanTest`）扫 `.size(...).clickable`，
  用 `SettingSwitch` 即天然合规。

**`refreshLocalMusic()`（死代码）在这里复活**：绑定到「重新扫描」按钮，
并在 `setLocalMusicEnabled(true)` 成功后也可触发。开关关闭时该按钮 `enabled = false`。

---

## §六 改动线 C：电池优化

现状：`MainActivity.kt:195` **无条件**调
`BatteryOptimizationHelper.checkAndRequest(this)`，后者在手机端（`isPhoneDevice()` 判据）
检测到未加白名单就 `startActivity(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)`
（`BatteryOptimizationHelper.kt:49-56`）——用户一开 App 就被弹到系统设置，确实越界。

**两条处置路线，请裁决（§十三）**：

| 路线 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **C1（推荐）** | 彻底删除：`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 声明 + `MainActivity.kt:195` 调用 + `requestIgnoreBatteryOptimizations()` + `checkAndRequest()`。只保留 `isIgnoringBatteryOptimizations()` 备用 | 权限表最干净，零自动行为 | 设置页无入口，用户得自己去系统设置找 |
| **C2** | 保留声明，只把自动触发改成「设置页状态展示 + 用户点击才跳」 | 保留可达性 | 权限表仍多一条；⚠️ **声明不能删**——`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 需要该权限，否则 `SecurityException` |

**C1 的额外清理**：删掉 `checkAndRequest` 后，
`BatteryOptimizationHelper.kt:13-16` 的类 KDoc「启动时检查并请求忽略电池优化（仅手机端）」必须同步改写（§十二 第 4 条）。

**推荐 C1**，理由与用户诉求一致：「这些内容可以由用户自行操作，无需我们主动做」——
保留入口本身也是一种「替用户做决定」。

---

## §七 单测门禁

⚠️ **本机约束**：`PhotoPermissionStateTest.kt:29-34` 记录本机只缓存了 Robolectric
SDK **34** 与 **30** 两个 `android-all` jar，**API 33 跑不了**。
⇒ **不要**写「跑遍 TIRAMISU 分支」的测试。

### §7.1 G1 · `LocalMusicPermissionPolicyTest`（新增，纯 JVM）

覆盖**纯逻辑**部分，避开 Robolectric SDK 限制。把
`PermissionHelper.kt:20-42` 里那两段 `Build.VERSION.SDK_INT` 分支抽成可注入 sdkInt 的纯函数后测试：

| 断言 | 内容 |
|---|---|
| L1 | sdkInt ≥ 33 ⇒ 需要 `READ_MEDIA_AUDIO`，且**不含** `READ_EXTERNAL_STORAGE` |
| L2 | sdkInt ≤ 32 ⇒ 需要 `READ_EXTERNAL_STORAGE`，且**不含** `READ_MEDIA_AUDIO` |
| L3 | 两个分支都返回**非空**数组（防 `getLocalMusicPermissions()` 被改成空数组后静默不弹窗） |
| L4 | 负向自证：把数组喂给 `checkSelfPermission` 的假实现，缺权限时 `hasLocalMusicPermission()` 必须 false |

### §7.2 G2 · `LocalMusicPrefsTest`（新增，照抄 `PhotoWallPrefsTest`）

`app/src/test/java/com/nasmusic/tv/data/prefs/PhotoWallPrefsTest.kt`（192 行）是现成模板，
**必须包含它的第 ⑤ 段**（`:172-191` legacy backup）：

| 断言 | 内容 |
|---|---|
| L1 | 默认 `localMusicEnabled == true`（D1，零回归的断言化） |
| L2 | 写 `false` → 读回 `false`；再写 `true` → 读回 `true` |
| L3 | `AppSettings().localMusicEnabled == true`（新对象默认值，防 Gson 无参构造消失） |
| L4 | legacy JSON（缺该键）导入后仍为 `true` 且**不抛异常** |
| L5 | `importBackupData` + `exportBackupData` 往返后值不变 |

⚠️ 模板里两条硬性写法照抄：
① `private suspend fun settle() = delay(30)`（datastore 1.0.0 在 Windows + Robolectric 下
连续快速写同名文件有 rename 竞态，`:26-27` 有说明）；
② `@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])`。

### §7.3 G3 · `LocalMusicGateTest`（新增，纯 JVM，覆盖门控契约）

用「源码文本扫描」断言门控点存在——这是本仓已有的手法
（`MediaSessionAccessPolicyTest` 读 `.kt` 源码、`VisualizerQualityWiringTest.kt:78-80` 同款）：

| 断言 | 内容 |
|---|---|
| L1 | `MainViewModel` 的本地音乐启动块整体被开关包住（断言出现 `startLocalMusicRuntime()`） |
| L2 | 存在 pref `distinctUntilChanged()` 的运行期观察者（防「开关打开后不扫描」回归） |
| L3 | `SearchAggregator` 的 `LOCAL` 分支条件含 `localMusicEnabled()` |
| L4 | `AndroidManifest.xml` 中**不再**出现 `POST_NOTIFICATIONS` / `WRITE_EXTERNAL_STORAGE` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| L5 | `MainActivity.kt` 中**不再**出现 `ActivityCompat.requestPermissions`（开机零弹窗） |
| L6 | 负向自证：临时把 `POST_NOTIFICATIONS` 塞回 Manifest 文本，L4 必须挂 |

⚠️ L4/L5 是 A 线的回归护栏，**B 线依赖它们**⇒ **A 线必须先合**（§10）。

### §7.4 G4 · `ReleaseSigningGateTest`（新增，纯 JVM，防 D 线静默复发）

**为什么需要**：根因不是「没配 secret」，而是**配错/被清空时会静默回退**（`build.yml:64-73`
的 else 分支不报错、构建照样成功、APK 照样产出，只是签名是随机的一次性 key）。
⇒ 光配一次不够，必须有门禁把「静默回退」变成「响亮失败」。这是 D 线的核心防线。

沿用本仓已有的**源码文本扫描**手法（`MediaSessionAccessPolicyTest` 读 `.kt` 源码、
`VisualizerQualityWiringTest.kt:78-80` 同款），扫 `.github/workflows/build.yml`：

| 断言 | 内容 |
|---|---|
| L1 | 存在「`push` / `tag` 事件必须使用正式签名」的 fail-fast 分支（D5） |
| L2 | `keytool -genkey` 只出现在 **PR/fallback 允许的分支**里，且该分支有显式注释说明「产物不可覆盖安装」 |
| L3 | `cryptoPassphrase` 的 fallback 占位值**不得**出现在 `push`/`tag` 路径上 |
| L4 | 负向自证：把 `build.yml` 文本里 D5 的 fail-fast 段落删掉，L1 必须挂 |

⚠️ L2/L3 是**解析文本**而非执行 CI ⇒ 不需要网络、不需要 secret，单测可离线跑。

### §7.5 既有测试影响

`READ_MEDIA_AUDIO` / `POST_NOTIFICATIONS` 在 `app/src/test` 与 `app/src/androidTest` 里
**0 命中**；`AndroidManifest.xml` 也**没有任何测试解析**。
⇒ **A 线删除这两条声明不会弄坏任何既有单测**。

---

## §八 编译 / lint 验证

本机必须加 `--no-daemon`（AGENTS.md 的硬约束）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon `
  -Pkotlin.compiler.execution.strategy=in-process `
  *> logs_temp/permission_cleanup_verify.log
```

⚠️ 若报 `fileHashes.lock (拒绝访问)` → 先 `.\gradlew.bat --stop` 释放卡死守护进程，
⛔ **不要 `rm` 锁文件**。
⚠️ lint 输出里那份 `LintCliClient.analyzeOnly` → UAST visitor 的内部异常栈是**既有的**，
不影响判定，别当新问题排查。
⚠️ `lintDebug` 是**阻塞**门禁（Error 必须为 0）；`Aligged16KB` 那条 onnxruntime 告警是既有 Warning，不阻塞。

---

## §九 上机验收清单

⚠️ **必须在 API 33+ 真机与 API 30 真机各跑一轮**（SDK 分支不同），
外加一台**电视**（`isTelevisionDevice` 分支不同）。

### §9.1 U1–U6 · 权限瘦身

| # | 步骤 | 期望 |
|---|---|---|
| U1 | 全新安装（先 `adb uninstall com.nasmusic.tv`） | **首启零权限弹窗** |
| U2 | 进设置页 → 权限列表（系统「应用信息 → 权限」） | 只剩 8 条，无通知权限、无存储写入 |
| U3 | 播放 → 切后台 → 回前台 | 通知栏媒体控制**照常**显示并可用（豁免生效） |
| U4 | 锁屏 + 蓝牙音箱 AVRCP 上一首/下一首 | 照常（走 `MediaSession`，与通知权限无关） |
| U5 | API 33+ → 设置 → 数据 → 备份 → 导出 | 正常（`WRITE_EXTERNAL_STORAGE` 删除后 API≥29 走 MediaStore，无影响） |
| U6 | **API 24–28 设备**（若还持有）导出备份 | 主备份成功；公共 Downloads 副本缺失且**无崩溃** |

### §9.2 U7–U11 · 本地音乐总开关

| # | 步骤 | 期望 |
|---|---|---|
| U7 | 全新安装后进设置 → 本地音乐 | 开关**默认开**、无弹窗、曲目数与今天一致（零回归） |
| U8 | 关掉开关 | 本地曲目从曲库消失；**已下载歌曲仍在**；搜索不再命中本地源 |
| U9 | 关 → 重新打开 | 弹出系统权限对话框；同意后自动扫描并出现曲目 |
| U10 | 打开开关后在**系统设置里撤销**音乐权限 → 回 App | 开关自动回弹为关 + 提示（`onResume` 重判生效） |
| U11 | 开着开关时插 U 盘 | 自动扫描并入库（`onDeviceMounted` 观察者工作正常） |

### §9.3 U12–U14 · 电池优化

| # | 步骤 | 期望 |
|---|---|---|
| U12 | 全新安装后首启 | **不再**被弹到系统电池优化设置页 |
| U13 | C2 路线才需要：设置 → 播放 → 电池优化行 | 状态显示正确，点击才跳转 |
| U14 | 电视上打开 App | 无任何电池优化相关表现（`isPhoneDevice()` 判据仍正确） |

### §9.4 V1–V6 · 签名统一（D 线）

⚠️ **V1 之前**：手机/电视上装的是历史随机签名的包，**这一次仍然必须先卸载**
——签名历史断档无法用配置修复。**从 V1 装上这一版之后，后续更新才都能覆盖安装。**

| # | 步骤 | 期望 |
|---|---|---|
| V1 | 配齐 secrets 后打一个测试 tag，看 build job 日志 | 出现 `Using release keystore from GitHub Secrets`；⛔ **不得**出现 `No signing secrets found`（注意 §1.3 的 ANSI 假匹配陷阱，只看无 ANSI 的执行输出行） |
| V2 | 下载该 APK，用 `apksigner verify --print-certs` 取签名证书 SHA-256 | 与本地 `release-key.jks` 的证书 SHA-256 **完全一致** |
| V3 | 电视上装 V2 的包，再装下一个 tag 的包 | `adb install -r` **成功**，不报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；应用数据（服务器凭据、设置、下载索引）保留 |
| V4 | 升级前后各进一次设置页 | 升级前设过的选项仍在（证明 DataStore 未被清空，这是 §1.3 那条前置条件的直接验收） |
| V5 | 升级后拔掉 NAS 服务器，检查连接 | 凭据仍可用（不需要重输密码） |
| V6 | 故意清掉 `SIGNING_KEYSTORE_BASE64` 再推一次 `main` | ⛔ **构建应当失败**（D5 的 fail-fast 生效），而不是静默产出一个随机签名的包 |

---

## §十 任务清单与提交顺序

| 批 | 任务 | 依赖 | 验证 |
|---|---|---|---|
| **S0** | **D1–D5**：配齐 5 个 repo secrets（§14.2）+ `build.yml` fail-fast 加固（D5）+ `ReleaseSigningGateTest`（G4） | — | V1/V2/V6 + G4 |
| **S1** | A1/A2：删 `WRITE_EXTERNAL_STORAGE` + `POST_NOTIFICATIONS`；删 `MainActivity.kt:197-210`；修正错误注释 | — | U1–U6 + G3 |
| **S2** | A3 + C：删 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + 电池优化处置（按 §十三 裁决） | — | U12/U14 |
| **S3** | B-1：`AppSettings` 新字段 + `AppPreferences` 4 处 + `strings.xml` 新文案 | — | G2 |
| **S4** | B-2：`MainViewModel` 门控（M1/M2/M3/M6）+ `startLocalMusicRuntime()` 抽取 + 运行期观察者 + `visibleLocalSongs` | S3 | G3 + U7/U8/U11 |
| **S5** | B-3：`SearchAggregator` provider 化（M7） | S4 | G3 L3 |
| **S6** | B-4：权限接线（launcher / 状态 / notice / 回弹） | S4 | U9/U10 |
| **S7** | B-5：设置页新 section + `refreshLocalMusic()` 复活 | S6 | U7 |
| **S8** | 收尾：`CHANGELOG.md` + `docs/technical-overview.md` §10.N（按 `.opencode/rules.md`）+ `AGENTS.md` CI 段（§十二 第 7–9 条） | 全部 | — |

⚠️ **S1 必须先于 S3–S7 合入**：G3 的 L4/L5 断言依赖 A 线已生效，否则 B 线单独提交时门禁会红。
⚠️ **S0 必须在 S1–S7 之前发版**（不必先合，但必须先发）：否则用户装到权限新版本时仍要卸载，
DataStore 被清空 ⇒ B 线的 `local_music_enabled` 回落默认 `true` ⇒ 最核心的修复路径永远走不到（§1.3）。
⇒ **发版顺序：S0 → A/C → B**，S0 单独一个版本即可，不必等 D5 加固（但建议一起做，成本极低）。

---

## §十一 风险与回滚

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 删 `POST_NOTIFICATIONS` 后通知栏不显示 | **极低** | 中 | 平台按 `MediaStyle` 模板强制豁免，AOSP 层面放行；U3/U4 实机复核。万一复现，回滚 S1 即可 |
| 默认 `true` 让 Android 13+ 用户首次开 App 仍扫不到本地音乐 | **高**（= 今天的行为） | 低 | 这是**现状**，不是回归。U7 确认曲目数与今天一致即可；真正的修复发生在用户「关→开」时（S6） |
| 开关状态用快照传给 `SearchAggregator` | 中 | 中 | D4 已定 provider 化；G3 L3 断言把关 |
| 抽 `startLocalMusicRuntime()` 时把 USB 订阅重复注册 | 中 | 中 | `collect` 非终止 ⇒ 重复调用会**叠加多个**收集器；G3 L2 + U11 复核 |
| `AppSettings` 新字段漏默认值 | 低 | **高**（旧备份导入整体崩） | §5.3 第 4 跳已标 ⚠️；G2 的 L3/L4 专测这条 |
| 删 `WRITE_EXTERNAL_STORAGE` 影响 API 24–28 的备份副本 | 低 | 低 | 该副本本就是「尽力而为」，§2.2 已核 |
| **只配 4 个签名 secret、漏 `CRYPTO_PASSPHRASE`** | **中高** | **高** | CI 会产出「签名正确但加密口令不同」的 APK ⇒ 装得上、但已存的 NAS/百度凭据解不开。⚠️ 比签名不一致更隐蔽，因为**装上去不报错**。D2 已把 5 个 secret 列为同一批必配；D5 的 L3 断言把占位值挡在 `push`/`tag` 之外 |
| PowerShell 生成的 base64 含 `CRLF` / UTF-16 BOM | **中** | 高 | `build.yml:56` 的 `base64 -d` 可能解不出正确密钥 ⇒ 要么构建失败，要么解出坏 keystore。D3 强制用 `[Convert]::ToBase64String([IO.File]::ReadAllBytes(...))`（**精确单行、无 BOM**），并要求 V2 校验证书指纹 |
| 以后谁清了 secret ⇒ 又静默回到随机签名 | 中 | **高**（会复发且难察觉） | 这才是根因。**D5 fail-fast + G4 门禁**是唯一防线，比配一次 secret 重要 |
| 配好 secret 后用户以为能立刻覆盖安装 | **高** | 中 | 签名历史断档无法修复，**仍需卸载一次**。V1 前的那条⚠️已写明；发版说明也要写 |

**回滚**：S0–S8 各自独立提交，`git revert` 单个 commit 即可，不需要动其他批次。
⚠️ **D 线的回滚要格外小心**：删掉 secrets 会让 CI 退回「每次随机签名」，
但那只会影响**后续**新包，已发布包的签名不受影响 ⇒ 回滚是安全的，
但**必须同时把 D5 的 fail-fast 也回滚**，否则 tag 构建会直接失败。

---

## §十二 陈旧注释同步清单

| # | 位置 | 现状 | 要改成 |
|---|---|---|---|
| 1 | `AndroidManifest.xml:10` | 「Android 13+ 分区存储：只读音频文件」 | 补一句「由『本地音乐』开关按需申请，启动时不申请」 |
| 2 | `MainActivity.kt:197` | 「媒体通知/下载通知依赖 POST_NOTIFICATIONS」 | ⛔ **整块删除**——这句本来就是错的，下载通知不是系统通知 |
| 3 | `PermissionHelper.kt:9-14` 类 KDoc | 「统一处理本地音乐所需的存储权限」 | 补一句「`getLocalMusicPermissions()` 自 vX 起由『本地音乐』总开关驱动」 |
| 4 | `BatteryOptimizationHelper.kt:13-16` | 「启动时检查并请求忽略电池优化（仅手机端）」 | C1 下删除该方法 ⇒ KDoc 改为「仅提供状态查询，供设置页展示」 |
| 5 | `PlaybackService.kt:1043-1064` | 通知构造，无豁免说明 | ⛔ **必须加注释**：`MediaStyle` 模板是 `POST_NOTIFICATIONS` 豁免的**唯一依据**，删了会连带失去豁免（§2.3） |
| 6 | `BackupFileUtils.kt:18-22` | 「API < 29 … 另写一份到外部存储公共 Downloads 目录供文件管理器访问」 | 补一句：vX 起无 `WRITE_EXTERNAL_STORAGE`，API 24–28 该副本会静默失败，主备份在内部存储不受影响 |
| 7 | `.github/workflows/build.yml:41-45` | 「模式 B（fallback）：未配置 secrets 时，临时生成 throwaway ci-keystore.jks … APK 不可覆盖安装到已有数据的设备」 | ⚠️ 这段注释**准确且必须保留**，但要补一句：**push / tag 构建已改为 fail-fast，不再走模式 B**（D5）；模式 B 只留给 PR 与本地演练 |
| 8 | `.github/workflows/build.yml:45` | 「用占位值时产出的 APK 加密密钥与正式包不同，仅供 CI 验证，不可覆盖安装到已有数据的设备」 | 同上，保留；D5 后可加「正式发版路径已禁止占位值」 |
| 9 | `AGENTS.md` 的 CI 段落 | 「CI `build` job 的 signing step 是 **dual-mode**（v2.36.0）：如果仓库有 GitHub Secrets … 否则回退 throwaway」 | 补一句：vX 起 **5 个 secrets 已配齐**（含 `CRYPTO_PASSPHRASE`），正式发版走模式 A；模式 B 仅存于 PR，tag/push 走 fail-fast。**这条不更新，下一个会话会再次误判 CI 在用随机签名** |
| 10 | `app/build.gradle.kts:87-89` | 「findByName：无 keystore.properties 时返回 null（AGP 跳过签名，打包阶段才失败）」 | 签名线**不改这里**，但 D5 fail-fast 后这条注释描述的「打包阶段才失败」会提前到 workflow 的 keystore 步骤 ⇒ 可补一句指明新的失败点 |

---

## §十三 待裁决

| # | 事项 | 推荐 | 影响 |
|---|---|---|---|
| 1 | 电池优化走 **C1（彻底删除）** 还是 **C2（保留声明 + 手动跳转）** | **C1** | 只影响 S2 与 U13。**不阻塞 S1 / S3–S8**，可先按 C1 开工，之后改 C2 成本很低 |

---

## §十四 改动线 D：发布签名统一

### §14.1 现状机制：双模式早就写好了，只是从来没走过模式 A

`build.yml:46-76` 的 `Set up signing keystore` 步骤：

| 模式 | 触发条件 | 行为 |
|---|---|---|
| **A 正式签名** | `build.yml:54` `if [ -n "$SIGNING_KEYSTORE_BASE64" ]` | `base64 -d > release-key.jks`，写 `keystore.properties`（`storeFile=release-key.jks`） |
| **B 兜底** | else | `build.yml:66` `keytool -genkey -v -keystore ci-keystore.jks -alias ci ...` —— **每次构建一把新密钥** |

关键点：**两个模式都写 `keystore.properties`**，所以 `app/build.gradle.kts:68-77` 的
`signingConfigs` 照常创建、`:89` 照常签名 ⇒ **构建永远成功，问题只体现在产物签名上**。
这就是它能安静地错这么久的原因。

⇒ **D 线不需要改任何 Gradle 代码**，只改仓库配置 + workflow 守卫。

本地侧已就绪、无需改动：`keystore.properties`（`storeFile=release-key.jks`、
`keyAlias=nas-music-tv`、PKCS12、`keyPassword == storePassword`）、项目根的 `release-key.jks`
（`.gitignore` 的 `*.jks` 挡住，不会误提交）。
⚠️ `app/build.gradle.kts:91-93` 的 `debug` **没有** `signingConfig`
⇒ 用 `~/.android/debug.keystore` ⇒ debug↔release 互装必然失败（见 §14.6）。

### §14.2 D1–D2：配齐 5 个 secrets（⛔ 5 个是同一批，缺一不可）

⚠️ **`CRYPTO_PASSPHRASE` 最容易被漏**：它不是签名的一部分，但
`app/build.gradle.kts:59` 把它编进 `BuildConfig`，运行时用于 AES 派生口令。
而 `build.yml:62` 写的是 `${CRYPTO_PASSPHRASE:-ci-build-only-placeholder}`
⇒ secret 缺失时**静默**回退占位值 ⇒ 产出的 APK **签名正确（装得上）但解不开已存的凭据**。
⚠️ 这比签名不一致更隐蔽，因为它**不报错**，要等用户发现 NAS 连不上、重新输密码才暴露。

D1 取 base64 —— ⛔ **不要用** `base64`、`certutil -encode`、或 PowerShell 的 `>` / `Out-File`
（5.1 默认写 UTF-16LE；`certutil` 会额外加 `-----BEGIN CERTIFICATE-----` 头尾，三种都过不了 `base64 -d`）：

```powershell
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\release-key.jks"))
if ($b64 -match '[\r\n]') { throw "含换行，build.yml:56 的 base64 -d 会失败" }
```

D2 用 `gh secret set` 逐个设置（5 个）：
`SIGNING_KEYSTORE_BASE64` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` /
`SIGNING_KEY_PASSWORD` / `CRYPTO_PASSPHRASE`。
⚠️ 前 4 个的值全部来自 `keystore.properties`；`CRYPTO_PASSPHRASE` 也在同一文件里
（该文件同时还存着百度网盘 AppKey/Secret，⛔ **不要**把整个文件内容塞进任何 secret）。

### §14.3 D3：验证命令（V1/V2 的具体做法）

⛔ **不要把口令写在命令行** —— 会进进程列表与 shell history。从 `keystore.properties` 读进变量：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$kp = @{}
Get-Content keystore.properties -Encoding UTF8 | ForEach-Object {
  if ($_ -match '^\s*([A-Za-z]+)\s*=\s*(.+?)\s*$') { $kp[$matches[1]] = $matches[2] } }
$env:JKS_PW = $kp['storePassword']
& "$env:JAVA_HOME\bin\keytool" -list -v -keystore release-key.jks -alias $kp['keyAlias'] `
  -storepass $env.JKS_PW | Select-String 'SHA256:|Owner:|Valid from'
Remove-Item Env:\JKS_PW
```

⚠️ **本机 `keytool` 不在 PATH**，必须走 `$env:JAVA_HOME\bin\keytool`（AGENTS.md 记的 Android Studio JBR）。
⚠️ `Get-Content` **必须带 `-Encoding UTF8`** —— 该文件是 UTF-8，按默认 CP936 读会乱码
（AGENTS.md 对文本往返有更严格的同款禁令）。

APK 侧用 `apksigner` —— ⛔ **不要**用 keytool 读 APK，读不出 v2/v3 签名：

```powershell
& "$env:ANDROID_HOME\build-tools\34.0.0\apksigner.bat" verify --print-certs `
  "$env:USERPROFILE\Downloads\NASMusicTV-release-v2-38-3.apk"
```

两边 SHA-256 必须完全一致（= 验收项 V2）。

### §14.4 D4–D5：fail-fast 加固（**这才是真正的修复**）

⚠️ 根因不是「没配 secret」，而是**配错时会静默回退**：构建照样成功、APK 照样产出，
只是签名是随机的 ⇒ 没人会发现，直到用户投诉装不上。
**配一次 secret 是治标，让回退变响亮才是治本。**

在 `build.yml` 的 `Set up signing keystore` 步骤里按事件区分：

| 事件 | 要求 |
|---|---|
| `push`（`on.push.branches = [main, develop]`）／`tag`（`on.tags = ['v*']`） | **`SIGNING_KEYSTORE_BASE64` 为空即 `exit 1`**；且 **`CRYPTO_PASSPHRASE` 为空也 `exit 1`**（不接受占位值） |
| `pull_request` | 保留模式 B —— PR 不需要正式签名，且 fork PR 本来就拿不到 secrets |

⚠️ `on:` 三类事件天然可区分（`build.yml:3-8`），不需要额外判断条件。
⚠️ 副作用（**期望行为**）：从未配 secret 的 fork 将无法发版 —— fork 本就不该发正式包。
⚠️ 这会改变现有 CI 的通过/失败语义，**必须写进 `CHANGELOG.md`**（§十二 第 7–9 条同批）。

### §14.5 D6：为什么不能靠「一次配好」就收工

G4 `ReleaseSigningGateTest`（§7.4）是这层的护栏：它把「`push`/`tag` 路径上不允许静默回退」
固化成单测，⛔ 不依赖网络、不依赖 secret ⇒ 谁以后动了 `build.yml` 的这段、把 fail-fast 删了，
门禁立刻红。这是唯一能防「半年后又复发」的手段。

### §14.6 一个必须跟用户确认的岔路

`debug` 包用默认 debug keystore（`app/build.gradle.kts:91-93`）⇒ **本地 debug ↔ release 互装必须卸载**。
若用户抱怨的"每次更新"指的是**本地 debug 包来回换**（而不是 CI 下载的 release 包），
那配 secrets 完全无效。**开工前先确认他装的是哪个包**（看文件名带不带 `-debug`）。

---

## §十五 明确不做

| # | 不做的事 | 理由 |
|---|---|---|
| 1 | 不动 `MediaSession.Callback.onConnect` 的 ⛔ 不许 `reject()` 铁律 | §10.165，与本方案无关 |
| 2 | 不把 `MusicSourceType.DOWNLOAD` 并入本地音乐开关 | §5.1-D3，下载走应用专属目录，与权限无关 |
| 3 | 不给「外接存储」加任何权限 | 已走 SAF（`ACTION_OPEN_DOCUMENT_TREE` + `DocumentsContract`），用户选哪棵树就只读哪棵 |
| 4 | 不新增 `WAKE_LOCK` | 前台服务保活是正确做法 |
| 5 | 不改搜索来源 chip bar（`SearchTab.kt:188-258`）的交互 | 它是**运行期**的搜索过滤，与「是否扫描本地音乐」是两个不同维度；持久化的开关只控扫描 |
| 6 | 不引入新的镜像/缓存机制读开关 | `appSettings.first()` 足够；`@Volatile` 冷启动镜像只服务于 `DisplayPrefs` 那条既成路径 |
| 7 | **不把 `debug` 也改成用正式 key 签名** | 会让 debug 包带发布身份；且 fork PR 的 CI 拿不到正式密钥。debug↔release 互装必须卸载是 Android 固有限制，接受 |
| 8 | **不试图「修复」签名历史断档** | 已装机的随机签名包无法与未来的正式签名共存 ⇒ 过渡期**必须卸载一次**。这是 Android 签名机制决定的，不是配置能改的 |
| 9 | 不改 `app/build.gradle.kts` 的签名逻辑本身 | 双模式机制（v2.36.0）设计是对的，问题只在 workflow 侧的配置与守卫（§14.1） |

---

## §十六 完工后的权限全景（目标态速查）

> 本节是**方案落地后的最终形态**，供发版说明、隐私政策、以及下一个会话直接引用。
> 与 §二 的区别：§二 描述**现状**，本节描述**做完之后**。

### §16.1 声明与申请时机总表

| # | 权限 | 保护级别 | 生效范围 | 用途 | **申请时机** | 用户拒绝的后果 |
|---|---|---|---|---|---|---|
| 1 | `INTERNET` | normal | 全部 | NAS / 飞牛 / 网盘 / 网络音乐 / 天气 API、本地 HTTP 遥控与扫码传输 | **安装即给**，无对话框 | 无（全站不可用，但这是无网络的环境，不是拒绝） |
| 2 | `ACCESS_NETWORK_STATE` | normal | 全部 | 判断网络类型与可用性 | **安装即给** | 无 |
| 3 | `FOREGROUND_SERVICE` | normal | Android 9+ | 后台播放前台服务 | **安装即给** | 无（拿不到时无法起前台服务，但本项目不在启动时起） |
| 4 | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | normal | Android 14+ | 同上，`mediaPlayback` 类型 | **安装即给** | 无 |
| 5 | `READ_MEDIA_AUDIO` | **dangerous** | Android 13+ | 本地音乐（`MediaStore.Audio` 扫描） | ⚠️ **设置 → 本地音乐 → 总开关首次由关变开** | 本地曲库扫不到数据（`MusicScanner` 吞 `SecurityException`，表现为「空的」，无崩溃、无报错） |
| 6 | `READ_EXTERNAL_STORAGE` | **dangerous** | `maxSdkVersion=32` | 同上（Android 12L 及以下） | ⚠️ **同上，同一个开关** | 同上 |
| 7 | `READ_MEDIA_IMAGES` | **dangerous** | Android 13+ | 照片墙「图库」 | ⚠️ **设置 → 照片墙 → 图库开关首次由关变开**（先弹本项目自己的说明对话框 `:474-494`，再弹系统对话框） | 「图库」开关自动回弹为关 + 提示；照片墙的另两个来源（NAS / 外接存储）不受影响 |
| 8 | `READ_MEDIA_VISUAL_USER_SELECTED` | **dangerous** | Android 14+ | 「仅选择照片」部分授权的持久标识 | ⚠️ **与 #7 同一个系统对话框内一起申请**（只申请 #7 会导致系统不显示「仅选择照片」选项） | 同上；选「仅选择照片」时可读用户选中的那批照片，界面显示 `当前仅可访问你选中的照片` |

**结论**：
- **声明 8 条**（从 11 条降到 8 条）
- 其中 **dangerous 只有 4 条**（#5–#8），且**只在 2 个时刻**申请
- **开机弹窗数 = 0**
- 2 个申请时刻：**本地音乐总开关**、**图库开关**（两者都是「打开开关才申请」，与 Google 官方
  “Request these permissions when the app needs storage access, instead of at startup.” 一致）

### §16.2 按 Android 版本的实际弹窗矩阵

| Android 版本 | 本地音乐开关弹的权限 | 图库开关弹的权限 | 系统对话框形态 | 一个全新用户最多被问几次 |
|---|---|---|---|---|
| **API 22–28**（5.0–9） | `READ_EXTERNAL_STORAGE` | `READ_EXTERNAL_STORAGE`（同一权限，只问一次） | 两选一 | **1 次** |
| **API 29–32**（10–12L） | `READ_EXTERNAL_STORAGE` | `READ_EXTERNAL_STORAGE` | 两选一 | **1 次** |
| **API 33**（13） | `READ_MEDIA_AUDIO` | `READ_MEDIA_IMAGES` | 各两选一 | **2 次** |
| **API 34+**（14+） | `READ_MEDIA_AUDIO` | `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED` | 图库为**三选一**（全部 / 仅选择照片 / 不允许），本地音乐为两选一 | **2 次** |

⚠️ 待实测（**不要**当成既定事实写进发版说明）：Android 13 的**权限迁移**机制可能把升级前已授予的
`READ_EXTERNAL_STORAGE` 自动转成 `READ_MEDIA_*`，导致部分老用户**一个弹窗都看不到**。
需在真机（从 API 32 升级到 API 33+）确认；§五 的设计对此是**安全**的
—— `setLocalMusicEnabled(true)` 先重读状态、已授权就直接落盘不弹窗。

### §16.3 不需要权限、但仍要用户「给一下」的东西（SAF 与系统设置）

这些**不出现在权限列表里**，但去掉功能会受影响，一并列清避免漏：

| # | 能力 | 机制 | 何时触发 | 撤销后果 |
|---|---|---|---|---|
| 1 | 导出备份到外接设备 / U 盘 | SAF `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` | 用户点「导出」 | 下次导出需重选目录 |
| 2 | 照片墙「外接存储」来源 | 同上（独立的一棵树） | 用户打开该来源开关 | 照片墙少一个来源；⚠️ 该实现只 `takePersistableUriPermission(FLAG_GRANT_READ_URI_PERMISSION)`（`VisualizerViewModel.kt:586-613`），**不主动释放**，属既有设计 |
| 3 | 备份文件写到公共 `Downloads/NASMusic/` | API 29+ 走 `MediaStore.Downloads`（免权限）；API ≤28 的公共目录副本因无 `WRITE_EXTERNAL_STORAGE` **会静默失败** | 用户点「导出备份」 | 主机侧看不到副本，**主备份与应用内「恢复」不受影响** |
| 4 | 应用专属目录（下载的歌曲、模型、歌词缓存、转存） | `context.getExternalFilesDir(...)`，**任何版本都免权限** | 自动 | 卸载即清空（既有行为） |
| 5 | 电池优化白名单 | **C1 路线下本方案完全不做** ⇒ 由用户自行去系统设置 | — | 后台播放可能被系统限制（Android 官方行为，非本方案引入） |

### §16.4 完工后的「权限 vs 功能」对照

| 功能 | 依赖 | 关闭/拒绝后 |
|---|---|---|
| NAS / 飞牛 / 道理鱼 / Subsonic 播放 | 无运行时权限 | 不受影响 |
| 网络音乐（Meting）/ 百度网盘 / 天气电台 | 无运行时权限 | 不受影响 |
| **已下载歌曲**（`MusicSourceType.DOWNLOAD`） | 无运行时权限（应用专属目录） | ⛔ **不受本地音乐总开关影响**（§5.1-D3） |
| **本地音乐 / USB 扫描** | 总开关 + #5/#6 | 开关关 ⇒ 停止扫描、不进搜索源、曲库不显示；**已入库索引保留**，重开后无需重扫全盘 |
| 照片墙「图库」 | 图库开关 + #7/#8 | 开关回弹为关；NAS / 外接存储两个来源不受影响 |
| 照片墙「外接存储」 | SAF 目录授权（**无权限**） | 需重新选目录 |
| 导出 / 转存 | SAF 目录授权（**无权限**） | 需重新选目录 |
| 后台播放 + 通知栏控制 | 无运行时权限（媒体通知豁免 `POST_NOTIFICATIONS`） | 不受影响；锁屏 / 蓝牙 / Android Auto 均照常 |
| 备份与恢复 | 无运行时权限 | 不受影响（仅 API ≤28 少一份公共目录副本） |

### §16.5 与 §二 的对照摘要（一句话版）

| | 声明数 | dangerous | 开机弹窗 | 申请时机数 |
|---|---|---|---|---|
| **现状** | 11 | 4 | **1 次**（通知权限，无条件） | 2 |
| **完工后** | **8** | 4 | **0 次** | 2（都是按需） |

⚠️ **dangerous 数量没有减少**（仍是 4 条）—— 本方案**不删任何 dangerous 权限**，
只是把申请时机从「开机无条件」改成「用该功能时才问」。
⇒ 对外表述要准确：**不能说「减少了权限」**，只能说「不再开机索权、只在用该功能时申请」。
`READ_MEDIA_AUDIO` 之所以保留，是因为本地音乐是真功能，不能为了清单好看而砍掉。
