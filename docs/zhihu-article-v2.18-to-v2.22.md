# NASMusicTV 一个月狂飙：从 v2.18 到 v2.22，手机端全适配 + 网盘播放 + 电台/独立音乐

> 从一个纯 TV 音乐播放器，到手机/平板/电视三端通吃的全能播放器——NASMusicTV 过去一个月经历了 15 个版本迭代，功能密度拉满。本文梳理 v2.18.1 到 v2.22.0 的完整演进。

## 项目简介

NASMusicTV 是一款开源的 Android 音乐播放器，连接你的 NAS（Jellyfin / Navidrome / Subsonic）播放本地音乐，同时支持网络音乐搜索、百度网盘播放、天气电台、K歌伴奏、MTV 音乐视频等。技术栈：Kotlin + Jetpack Compose for TV + Media3/ExoPlayer。

[GitHub 开源地址](https://github.com/hxzhang2000/NASMusicTV)

---

## 一、手机端全适配（v2.20.0）

这是版本里程碑最大的一次。此前应用仅支持 Android TV，v2.20.0 实现了**同一 APK 同时支持 TV 与手机/平板**。

### 核心改动

- **运行时自动检测**：`hasSystemFeature("android.software.leanback")` 判断设备类型，TV 走顶部导航 + 遥控器焦点体系，手机走触屏交互
- **手机底部导航栏**：首页 / 曲库 / 网络音乐 / 我的，4 个底栏 Tab，TV 保持原有顶部导航不变
- **MiniPlayer 迷你播放条**：手机端非播放页底部常驻迷你播放条（封面 / 歌名 / 播放暂停 / 下一首 / 细进度条），点击进入播放页
- **触摸进度条**：播放页进度条支持点击与拖拽 seek（TV 遥控器左右键 seek 保持不变）
- **手机端默认横屏**：全界面 `SENSOR_LANDSCAPE`，布局贴近 TV

### 适配细节

- 曲库响应式网格：列数随屏幕宽度自适应（TV / 手机横屏 / 手机竖屏三档）
- `FocusableSurface` 重写：从 `androidx.tv.material3.Surface` 改为 `Box + combinedClickable`，触摸点击 + 遥控器 OK 键双兼容
- TV 专属功能按设备隐藏：手机端自动隐藏"手机遥控"二维码、HDMI-CEC 等 TV 硬件入口

---

## 二、百度网盘音乐播放（v2.18.0）

**无需 NAS 后端，直接播放百度网盘里的音乐。**

### 核心能力

- **设备码 OAuth 鉴权**：手机扫码授权，TV 端输入设备码完成登录
- **文件列表与搜索**：目录浏览 + 关键词搜索，支持递归 BFS 扫描
- **音乐串流**：dlink 直链播放（补 `access_token` + `Referer` + `User-Agent` 三头）
- **歌词与封面**：侧车 LRC + 内嵌 ID3 USLT/APIC，网络匹配 fallback
- **MV 关联**：同目录同名匹配 + 歌手歌名搜索，支持 MTV 全屏播放
- **本地索引缓存**：BFS 扫描 + 60ms 节流，增量更新，首次扫描后毫秒级搜索
- **API 版本探测**：字段指纹 SHA-256 检测百度 API 静默升级，异常时一次性提示用户

---

## 三、Subsonic 后端支持（v2.19.0）

三大 NAS 后端全部集齐：Jellyfin + Navidrome + Subsonic。

- 新增 `SubsonicAdapter`，支持 lx-server、Navidrome、Airsonic 等 Subsonic 兼容服务器
- 标准 `md5(password + salt)` 认证方式
- 完整 API：专辑/歌手/歌曲/搜索/收藏/播放列表/流派/随机歌曲/歌词/封面流
- 13 个单元测试覆盖认证逻辑和 API 调用
- 服务器连接页新增 Subsonic 服务器类型选项，URL 占位符根据类型动态切换

---

## 四、电台 + 独立音乐（v2.21.0）

网络音乐从单一的网易云搜索，扩展为**三大内容源**：搜索 + 电台 + 独立音乐。

### 电台（radio-browser.info）

- 全球公开电台目录（含中文电台，默认热度排序）
- 标签快捷筛选与关键词搜索
- 点击即点即播直播流
- 播放页进度条新增"直播"态（● LIVE），隐藏进度填充与滑块，禁用 seek
- 纯公共 API，无 key、不建后台

### Jamendo（CC 独立音乐）

- 50 万+ 知识共享授权音库（官方开放 API）
- 热门榜 + 风格标签筛选（氛围/电子/爵士/电影配乐等）+ 搜索
- 设置页填 Client ID 即用（devportal.jamendo.com 免费注册），未配置显示引导卡
- LRU 结果缓存（30 条 / 10 分钟）控制官方 API 月度配额（35,000 次）

## 开源地址

**GitHub**: [https://github.com/hxzhang2000/NASMusicTV](https://github.com/hxzhang2000/NASMusicTV)

GPL v3 | Kotlin + Jetpack Compose for TV | minSdk 22 | targetSdk 34

欢迎 star、issue、PR。
