# Changelog

> 所有显著的版本变更记录在此文件。
>
> 格式基于 [Keep a Changelog](https://keepachangelog.com/)，
> 版本管理遵循 [Semantic Versioning](https://semver.org/)。
>
> 类型：`Added`（新增） | `Changed`（变更） | `Fixed`（修复） | `Removed`（移除）

## [Unreleased]

### Added

- 版本控制系统：`NasMusicVersion` 统一管理版本号，设置页"关于"显示版本信息
- 技术方案文档：`docs/features-plan.md` 记录未来功能规划
- 技术架构文档：`docs/technical-overview.md` 记录当前完整的架构与实现细节

### Fixed

- Jellyfin 封面图 fallback 逻辑：当 `ImageTags.Primary` 为 null 时自动回退到无 tag 的封面 URL
- D-pad 左右键跳转修复：处理 `KeyDown` → `KeyUp` 事件类型适配不同 Android TV 固件

### Changed

- Debug 编译下歌曲加载数量限制为 10 首，Release 下为 100,000 首
- "播放全部"按钮从仅在专辑 tab 显示改为常驻显示
- 设置页"关于"区域的版本号从硬编码改为读取 `NasMusicVersion`

---

## [1.0.0] - 初始发布

### Added

- Jellyfin 后端连接与音乐浏览（专辑、歌曲、演唱者）
- Navidrome 后端连接与音乐浏览（通过 Subsonic API）
- ExoPlayer 音频播放引擎（Media3）
- 播放模式支持：顺序播放、单曲循环、列表循环、随机播放
- 播放队列管理：添加、移除、清空
- 歌词系统：LRC 解析、多来源获取（MP3 内嵌、本地缓存、本地文件、网络匹配）、来源切换
- 封面图显示：MP3 内嵌元数据提取 + 后端 URL + fallback 继承
- 曲库浏览：专辑网格、演唱者网格、歌曲列表
- 搜索功能：拼音首字母匹配 + 子串匹配
- 启动连接提示对话框
- 设置：暗色主题、界面动画、自动播放下一首、播放模式、歌词缓存、封面缓存、歌词偏移
- 服务器连接管理与配置持久化（DataStore）
- 后台播放服务（MediaLibraryService）
- 三层 BACK 键处理（关闭弹窗 → 回到播放页 → 退出确认）
- Android TV D-pad 完整导航支持
