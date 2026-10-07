# E29 太阳系效果增强 — 功能清单与当前编译错误

> 目标效果：E29 轨道（太阳系），实现文件 `app/src/main/java/com/nasmusic/tv/visualizer/renderers/BatchTwoRenderers.kt`（`OrbitalRingsRenderer`）。
>
> ✅ **状态（2026-10-08 回填）：功能 ①–⑥ 已全部落地，22 处编译错误清零。** 版本号未提升（实现落 v2.38.4 段）。
> 实施结果、**与本文原规格的 6 处偏离及其理由**、每档新增提交数、门禁 `OrbitalProceduralEnhanceTest` 契约清单，
> 全部见 `docs/technical-overview.md` **§10.219** —— ⛔ **本文第二节的历史错误表已过期，不要再照它排查**。
>
> 原文状态记录：程序化渲染增强代码已落地（工作区未提交），**编译失败**，错误清单见文末第二节；彗星功能未开始。

## 一、功能详细描述

### 1. 晨昏线（MEDIUM / HIGH 档生效）
行星盘上叠加明暗分界，营造立体光照感：

- 光源定在画面中心的太阳 ⇒ 每颗行星的光线方向 = `normalize(画面中心 - 行星位置)`，由 `atan2` 得方位角。
- 夜侧（背向太阳的一侧）用行星色加深叠加（`color.copy(alpha = 0.6f)`），与日侧形成明暗对比。
- 分界处带柔和过渡带（`PLANET_TERMINATOR_SMOOTH = 0.08`），不是硬切边。
- 实现上预烘 **16 个方位角**的单位圆半圆遮罩 `Path`（`terminatorPaths`，含过渡带弧度），逐帧只选最近档位绘制，避免每帧 `withTransform`/`rotate` 的 lambda 分配。
- 缩放绘制：`save() → translate(px, py) → scale(pr) → drawPath → restoreToCount()`。

### 2. 大气边缘光（仅 HIGH 档）
行星边缘一圈微光晕，增强写实质感：

- `Brush.sweepGradient`（透明→白色 30%→透明）按 `(w, h, scale)` 键在 `ensureLayout` 缓存（`atmoBrush`），渐变 Brush 构造期建好 ⇒ 逐帧零分配。
- 逐帧 `drawCircle(radius = pr * 1.04f, brush = atmoBrush, alpha = ATMOSPHERE_GLOW_ALPHA = 0.18f)`，光晕略大于行星盘。
- LOW/MEDIUM 档不绘制（`atmoBrush` 保持 null，不建渐变）。

### 3. 木星 / 土星带状云纹（MEDIUM / HIGH 档生效）
程序化波浪云带替代原来的简单横纹，按真实外观区分：

- **木星**：10 条云带（HIGH）/ 4 条（MEDIUM），替代原 2 次 `drawOval` 横纹。
- **土星**：8 条云带（HIGH）/ 3 条（MEDIUM）。
- 每条云带：纬度固定 `lat`，经度向叠加**多层正弦**近似 fbm 噪声（`elapsed` 驱动缓慢漂移），64 段写入成员 `bandBuf`（每带 `rewind()` 复用），`drawPath` 一次提交。
- 条带颜色 alpha 交替（0.18 / 0.12 等），呈明暗相间的平行条纹。

### 4. 太阳米粒组织（仅 HIGH 档）
太阳表面程序化噪点纹理（米粒组织），替代纯色圆盘：

- HIGH 档用 `bandBuf` 生成 96 段噪声圆：多层正弦叠加 `SUN_GRANULE_CONTRAST = 0.35f` 振幅 + 边缘柔和衰减 `smoothstep(0.92, 1.0)`，`drawPath` 填充 `sunCore`。
- **保留** `sunCoreHot` 亮核 `drawCircle`（第 2 次提交，维持原有视觉层级）。
- LOW/MEDIUM 维持原两层 `drawCircle` 不变；`sunScale`/`glowAlpha` 的音频律动不受影响。

### 5. 土星环分层 — 卡西尼缝（仅 HIGH 档）
土星环上增加卡西尼缝暗带：

- 在 `drawSaturnRing` 的成员 `ringBuf` 主环弧之后**追加缝楔形段**（内边界 `rx * 0.55`、外边界 `rx * 0.65`，角宽 `SATURN_RING_GAP_ANGLE = 0.25`），同一 `Path` 一次 `drawPath` ⇒ 提交数不增。
- 缝位于环长轴端点附近（a ≈ 0/180°），与真实土星环结构对应。

### 6. 彗星（未开始）
- **偶尔经过的彗星**：不定间隔（如 25~45 s）从画面外进入，划过太阳系。
- **画出彗星轨道**：彗星出现期间绘制其运行轨道（椭圆弧）。
- **轨道消失**：彗星完全走出画面范围后，轨道随之消失。
- 设计约束：禁 `Random` ⇒ 出现时刻/方向/速度/大小由 `elapsed` 的确定性 hash 生成（同 E43 螃蟹的 `hash2` 思路），可回放、零闪烁；彗核 `drawCircle` + 彗尾 `drawPath`（尾部背日方向）；轨道/尾路径用成员 `Path` 复用，`draw()` 内零分配。

## 二、（历史快照）当时的 22 处编译错误 —— ⛔ 已于 2026-10-08 全部修复，勿再照此排查

共 22 处，全部在 `BatchTwoRenderers.kt`：

| # | 位置 | 错误 | 所属功能 |
|---|------|------|---------|
| 1–4 | `:515:44 / :515:47 / :515:70 / :515:73` | `Argument type mismatch: actual type is 'Double', but 'Float' was expected.` | 太阳米粒（噪声圆路径点，`noise` 为 Double 污染 `x/y`） |
| 5 | `:639:33` | `Unresolved reference 'save'` | 晨昏线缩放绘制 |
| 6 | `:640:17` | `Unresolved reference 'translate'` | 晨昏线缩放绘制 |
| 7 | `:641:17` | `Unresolved reference 'scale'` | 晨昏线缩放绘制 |
| 8 | `:643:17` | `Unresolved reference 'restoreToCount'` | 晨昏线缩放绘制 |
| 9–12 | `:677:56 / :677:59 / :677:82 / :677:85` | `Argument type mismatch: actual type is 'Double', but 'Float' was expected.` | 木星云带（`bandBuf.moveTo/lineTo` 路径点） |
| 13–14 | `:688:44 / :688:47` | `Argument type mismatch: actual type is 'Double', but 'Float' was expected.` | 木星云带 |
| 15–18 | `:712:56 / :712:59 / :712:82 / :712:85` | `Argument type mismatch: actual type is 'Double', but 'Float' was expected.` | 土星云带 |
| 19–20 | `:722:44 / :722:47` | `Argument type mismatch: actual type is 'Double', but 'Float' was expected.` | 土星云带 |
| 21 | `:907:31` | `None of the following candidates is applicable`（`Brush.sweepGradient`：当前 Compose 版本无 `colors: IntArray` + `startAngle/endAngle` 重载，仅 `vararg colorStops` 或 `colors: List<Color>`） | 大气边缘光 |
| 22 | `:910:39 / :911:52 / :912:39` | `Unresolved reference 'toArgb'`（需 `import androidx.compose.ui.graphics.toArgb`） | 大气边缘光（`Color.Transparent.toArgb()` 等 3 处） |

**错误归因**（供后续修复参考，未修改代码）：

- **Double/Float 不匹配（14 处）**：噪声采样（`fbmNorm`/米粒）返回 `Double`（或混用 `Math.*`），一路污染到 `bandBuf.moveTo/lineTo` 的坐标参数 —— 需把噪声链收敛为 `Float`。
- **晨昏线 4 处 Unresolved（639–643）**：`save()/translate()/scale()/restoreToCount()` 无块重载在当前 Compose BOM 2024.02.00 的 `DrawScope` 上不存在（仓库既有范式是块版本 `translate(...) { }` 或原生 `Canvas` 的 `cvs.save()/translate/scale`）—— 需改画法（如直接按 `pr` 缩放坐标写入成员 Path，零分配）。
- **大气边缘光 4 处（907–912）**：`sweepGradient` 重载用法与 `toArgb` 扩展 import 缺失。

> 验证命令：`JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat compileDebugKotlin --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"`（完整日志 `logs_temp/e29_compile_check.log`）。
