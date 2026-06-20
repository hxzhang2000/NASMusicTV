# NAS Music TV

一款开源的 Android TV 端 NAS 音乐播放器，让电视上的歌词显示不再是奢望。

## 功能特性

- **NAS 音乐播放**：连接 Jellyfin / Navidrome 后端，播放 NAS 中的音乐
- **歌词显示**：支持逐行滚动歌词，适配电视大屏
- **歌词匹配**：自动从网络匹配歌词并本地缓存
- **专辑封面**：显示专辑封面，支持内嵌和在线获取
- **多架构支持**：ARM64 / ARMv7 / x86_64
- **遥控器优化**：完整的 D-Pad 导航和焦点系统

## 技术栈

- Kotlin + Jetpack Compose for TV
- Media3 / ExoPlayer 音频引擎
- Retrofit + OkHttp 网络层
- Coil 图片加载

## 界面预览

- 播放页：左侧专辑封面 + 右侧歌词
- 曲库页：网格卡片浏览专辑
- 队列页：播放队列管理
- 设置页：配置项管理
- 连接页：服务器配置

## 开源协议

GPL v3
