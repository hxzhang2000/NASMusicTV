# Subsonic 协议支持开发方案

## 需求背景

用户场景：外出露营时携带音箱，使用手机 KTV 更方便，家中 NAS 有外网链接。希望支持 Subsonic 协议，因为：

1. **lx-server 项目**（https://github.com/XCQ0607/lxserver）支持洛雪音源 + Subsonic 协议，可免费获取全网音乐，无需本地 NAS 存储音乐文件
2. **Subsonic 是广泛使用的音乐协议**，与 Navidrome 几乎一样（Navidrome 本身就是 Subsonic 兼容服务器）
3. 支持 Subsonic 后可连接多种服务端：Navidrome、lx-server、Airsonic、Madsonic 等

## 技术分析

### 现有架构

```
BackendAdapter (接口)
├── JellyfinAdapter   (Jellyfin 专用 API)
└── NavidromeAdapter  (Subsonic API，已完整实现)
```

**关键发现**：`NavidromeAdapter` 已经完整实现了 Subsonic API（`ping`、`getAlbumList2`、`getArtists`、`search3`、`stream` 等），因为 Navidrome 本身就是 Subsonic 兼容服务器。

### Subsonic API 核心端点

| 端点 | 功能 | 对应 BackendAdapter 方法 |
|------|------|--------------------------|
| `ping` | 连接测试 | `testConnection()` |
| `getAlbumList2` | 专辑列表 | `getAlbums()` |
| `getAlbum` | 专辑歌曲 | `getAlbumSongs()` |
| `getArtists` | 歌手列表 | `getArtists()` |
| `getArtist` | 歌手歌曲 | `getArtistSongs()` |
| `search3` | 搜索 | `searchSongs()` |
| `stream` | 播放流 | `getStreamUrl()` |
| `getCoverArt` | 封面 | `getCoverUrl()` |
| `getLyrics` | 歌词 | `getLyrics()` |
| `star/unstar` | 收藏 | `toggleFavorite()` |
| `getStarred2` | 收藏列表 | `getFavorites()` |
| `getPlaylists` | 播放列表 | `getPlaylists()` |

### Subsonic 认证机制

```kotlin
// Token 认证（推荐）
token = md5(password + salt)
// 请求参数: u=username, t=token, s=salt, v=1.16.1, c=NASMusicTV, f=json
```

### lx-server 配置

- 默认端口：`9527`
- Subsonic 路径：`/rest`
- 默认认证：用户名密码
- Docker 部署：`xcq0607/lxserver:latest`

### Navidrome 与通用 Subsonic 的差异

| 特性 | Navidrome | 通用 Subsonic |
|------|-----------|---------------|
| API 协议 | Subsonic 1.16.1 | Subsonic 1.16.1 |
| 认证方式 | 用户名+密码 | 用户名+密码+token |
| 响应格式 | JSON | JSON/XML |
| 流播放 | 直接 stream URL | stream URL + auth params |
| 封面 | `getCoverArt` | `getCoverArt` |
| 歌词 | `getLyrics` | `getLyrics` |

**结论**：差异极小，SubsonicAdapter 可复用 NavidromeAdapter 的绝大部分代码。

## 实现方案

### 方案选择：独立 SubsonicAdapter（推荐）

虽然 Subsonic 和 Navidrome API 几乎相同，但选择**独立实现**而非继承，原因：

1. **认证差异**：Subsonic 需要 token+salt 认证，Navidrome 可能使用简化认证
2. **服务器名称**：需要区分显示"Subsonic" vs "Navidrome"
3. **未来扩展**：不同 Subsonic 兼容服务器可能有细微差异
4. **代码清晰**：独立类更易维护和测试

### 文件变更清单

#### 1. 新增文件

| 文件 | 说明 |
|------|------|
| `backend/impl/SubsonicAdapter.kt` | Subsonic 后端适配器（核心） |
| `backend/SubsonicApiClient.kt` | Subsonic API 客户端封装（可选，抽离公共逻辑） |

#### 2. 修改文件

| 文件 | 变更 |
|------|------|
| `data/model/ServerConfig.kt` | 新增 `TYPE_SUBSONIC = "subsonic"` |
| `backend/BackendRegistry.kt` | 注册 SubsonicAdapter，添加 Subsonic 类型支持 |
| `ui/screens/ServerConnectScreen.kt` | 新增 Subsonic 连接选项 UI |
| `ui/components/AppRoot.kt` | 服务器设置路由 |
| `res/values/strings.xml` | 新增 Subsonic 相关字符串 |

### SubsonicAdapter 核心实现

```kotlin
package com.nasmusic.tv.backend.impl

class SubsonicAdapter : BackendAdapter {
    
    override val backendType: String = "subsonic"
    override var serverName: String = "Subsonic"
    
    private var baseUrl: String = ""
    private var username: String = ""
    private var password: String = ""
    
    // Subsonic 认证参数
    private var apiToken: String = ""
    private var salt: String = ""
    
    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean {
        this.baseUrl = baseUrl.removeSuffix("/")
        this.username = username
        this.password = password
        
        // 生成 token 认证参数
        salt = generateSalt()
        apiToken = md5(password + salt)
        
        return testConnection()
    }
    
    override suspend fun testConnection(): Boolean {
        // GET /rest/ping?u=user&t=token&s=salt&v=1.16.1&c=NASMusicTV&f=json
        val url = buildRestUrl("ping")
        // ... 解析 subsonic-response.status == "ok"
    }
    
    private fun buildRestUrl(endpoint: String): String {
        return "$baseUrl/rest/$endpoint" +
               "?u=$username&t=$apiToken&s=$salt" +
               "&v=1.16.1&c=NASMusicTV&f=json"
    }
    
    private fun generateSalt(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    }
    
    private fun md5(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

### 认证流程对比

```
Navidrome:
  直接使用密码或 token 认证
  ?u=user&p=password&v=1.16.1&c=app&f=json

Subsonic (lx-server 等):
  Token + Salt 认证
  ?u=user&t=md5(password+salt)&s=salt&v=1.16.1&c=app&f=json
```

### UI 变更

#### ServerConnectScreen 新增选项

```
┌─────────────────────────────────────┐
│  服务器类型                          │
│  ○ Jellyfin                        │
│  ○ Navidrome                       │
│  ● Subsonic          ← 新增        │
│                                     │
│  服务器地址                          │
│  [http://your-server:9527]          │
│                                     │
│  用户名                              │
│  [admin]                            │
│                                     │
│  密码                                │
│  [••••••]                           │
└─────────────────────────────────────┘
```

### 测试计划

#### 单元测试

| 测试类 | 覆盖内容 |
|--------|----------|
| `SubsonicAdapterTest` | API 调用、响应解析、认证参数生成 |
| `SubsonicAuthTest` | MD5 计算、Salt 生成、Token 验证 |

#### 集成测试

| 测试场景 | 验证点 |
|----------|--------|
| 连接 Navidrome | 通过 Subsonic 协议连接 Navidrome |
| 连接 lx-server | 通过 Subsonic 协议连接 lx-server |
| 搜索功能 | search3 端点正确返回结果 |
| 播放功能 | stream 端点正确返回音频流 |
| 收藏功能 | star/unstar 正确同步 |

#### 手动测试

1. **lx-server Docker 部署**
   ```bash
   docker run -d -p 9527:9527 xcq0607/lxserver:latest
   ```

2. **连接测试**
   - 输入服务器地址：`http://192.168.1.100:9527`
   - 输入用户名密码
   - 点击测试连接

3. **功能验证**
   - 浏览专辑列表
   - 搜索歌曲
   - 播放音乐
   - 查看歌词
   - 收藏歌曲

## 实施步骤

### Phase 1: 核心适配器（3-5天）

1. **创建 SubsonicAdapter**
   - 实现 BackendAdapter 接口
   - 实现 Subsonic 认证（token+salt）
   - 实现核心 API 调用

2. **注册到 BackendRegistry**
   - 添加 TYPE_SUBSONIC 常量
   - 在 initialize/testConnection 中添加 Subsonic 分支

3. **基础测试**
   - 单元测试覆盖认证逻辑
   - 集成测试连接 lx-server

### Phase 2: UI 集成（2-3天）

1. **ServerConnectScreen**
   - 新增 Subsonic 类型选项
   - 调整连接表单（无需 API Token 字段）

2. **字符串资源**
   - 添加 Subsonic 相关 UI 文本

3. **测试**
   - 验证 UI 流程完整

### Phase 3: 功能完善（2-3天）

1. **歌词支持**
   - 实现 getLyrics 调用
   - 处理歌词格式

2. **收藏同步**
   - 实现 star/unstar
   - 实现 getFavorites

3. **播放列表**
   - 实现 getPlaylists
   - 实现 getPlaylistSongs

### Phase 4: 测试与优化（2天）

1. **完整测试**
   - 所有功能手动测试
   - 边界情况处理

2. **性能优化**
   - 缓存策略
   - 并发请求优化

3. **文档更新**
   - 更新 README
   - 更新技术文档

## 风险与注意事项

### 1. 认证兼容性

**风险**：不同 Subsonic 服务器的认证实现可能有差异。

**缓解**：
- 实现标准 token+salt 认证
- 提供密码明文认证 fallback
- 测试多种服务器（Navidrome、lx-server、Airsonic）

### 2. API 版本差异

**风险**：不同服务器支持的 API 版本不同。

**缓解**：
- 使用较低版本 API（1.13.0+）
- 检查服务器返回的 version 字段
- 实现 API 版本检测和降级

### 3. 流播放认证

**风险**：stream 端点可能需要额外认证参数。

**缓解**：
- 在 stream URL 中包含认证参数
- 处理 401/403 错误
- 实现 token 刷新机制

### 4. 网络延迟

**风险**：外网访问 NAS 可能延迟较高。

**缓解**：
- 实现请求超时控制
- 添加加载状态提示
- 实现离线缓存（可选）

## 预期收益

1. **支持 lx-server**：免费获取全网音乐，无需本地存储
2. **扩大兼容性**：支持所有 Subsonic 兼容服务器
3. **代码复用**：NavidromeAdapter 已验证 Subsonic API 可行性
4. **用户场景**：露营时通过手机热点连接家中 NAS

## 总结

本方案利用现有 NavidromeAdapter 的 Subsonic API 实现经验，创建独立的 SubsonicAdapter，支持 lx-server 等 Subsonic 兼容服务器。核心工作量在于认证机制适配和 UI 集成，预计 10-13 天完成。
