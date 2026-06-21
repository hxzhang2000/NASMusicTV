# NowPlaying UI 调整路线图

> **目标版本**：v2.1.0  
> **状态**：待开发  
> **日期**：2026-06-20

---

## 概述

调整 NowPlaying 播放界面的布局，优化 D-Pad 导航流和视觉层次。

---

## 任务分解

### Task 1: 播放控制按钮下移

**当前**：播放/暂停、上一首、下一首、播放模式 4 个按钮位于专辑封面右侧/歌词上方。

**目标**：将这 4 个按钮移到专辑封面下方、进度条上方，形成"封面 → 控制按钮 → 进度条"的纵向排列。

**涉及文件**：
- `ui/screens/NowPlayingScreen.kt` — 重新编排 Column 布局，控制按钮移至封面下方
- `ui/components/PlayerControls.kt` — 可能需要调整按钮组的样式或水平间距

---

### Task 2: 进度条横向占满

**当前**：进度条宽度有限，未充分利用屏幕宽度。

**目标**：将进度条扩展到屏幕横向占满（或接近占满），与底部对齐。

**涉及文件**：
- `ui/components/PlayerControls.kt` — 进度条 `Modifier.fillMaxWidth()` 或自定义宽度
- `ui/screens/NowPlayingScreen.kt` — 为进度条留出足够横向空间

---

### Task 3: 专辑名称移至封面图上方

**当前**：专辑名称和艺术家合并在封面下方一行：
```
歌曲标题
   封面图
艺术家 · 专辑名
```

**目标**：将专辑名拆出，放在封面图上方、歌曲标题下方：
```
歌曲标题
   专辑名      ← 新增/移至此位置
   封面图
艺术家          ← 仅保留艺术家
```

**涉及文件**：
- `ui/screens/NowPlayingScreen.kt` — `CoverColumn` 内重组 `Column` 子元素顺序
- `ui/theme/Type.kt` 或 inline `TextStyle` — 专辑名可能需要不同的字号/颜色（比标题小，比艺术家灰浅）

---

## 影响范围

| 文件 | 变更类型 |
|------|----------|
| `ui/screens/NowPlayingScreen.kt` | 布局重构（CoverColumn 内部 Column 子元素重排） |
| `ui/components/PlayerControls.kt` | 样式/尺寸调整（Task 1, 2） |
| `app/src/main/res/...` | 可能无需修改 |

## 验证方式

1. `./gradlew assembleDebug` 编译通过
2. 真机部署确认封面图上下元素排列顺序正确
3. 专辑名字号/颜色视觉层次合理
4. 左右键 seek 正常（此前修复不退化）
