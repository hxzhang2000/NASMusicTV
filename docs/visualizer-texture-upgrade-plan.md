# 可视化效果「质感 / 真实感」升级 —— 开发方案

> **目标**：对现有 **28 套**频谱效果做一轮**以显示质感为核心**的优化 —— 提高材质感、
> 纵深、光照与光效的真实度，**不新增效果、不改音频分析层、不新增权限**。
>
> **范围边界**：① 不改 `SpectrumRepository` / `PcmSpectrumTap`（音频层零改动）；
> ② 不改 `VisualizerTheme` 枚举的成员集合（28 项不变，避免动 `VisualizerThemeTest` 的
> 6 处计数断言与 `LEGACY_MAP`）；③ 不引入新依赖、不引入 GLSL/OpenGL。
>
> **状态**：**可开工**（§十五 已补至可开发级：逐文件清单 / 完整可粘贴签名 / 逐文件改造点
> `file:line` / 源码级 API 核实结论 **A1–A36**）。
> ⚠️ 但 §十三 的 **7 项取舍尚未拍板**，其中「改造深度」直接决定做 11 套还是 28 套
> ⇒ **开工前先看 §十三**。
>
> **基线**：v2.37.6（versionCode 168）｜门禁 `testDebugUnitTest` **1177 例 / 115 类 /
> 0 失败 / 0 错误 / 0 跳过**（来源 `build_test_counts.txt`，实测汇总）；
> `lintDebug` **0 Error / 279 Warning**（v2.37.0 口径，**本方案开工前必须复跑确认**）。
> ⚠️ 计数漂移先确认是不是本轮新增的文件 —— 本项目存在并发会话改同一工作区的情况。

### 文档版本跟踪

| 文档版本 | 日期 | 变更摘要 | 状态 |
|---|---|---|---|
| v1.0 | 2026-09-28 | 初稿：28 套效果质感体检表（含 `file:line`）／10 条共性缺口诊断／`fx/` 工具箱设计与完整签名／逐效果优化清单（A 重做 11 套 · B 增强 10 套 · C 精修 7 套）／关键参数表／单测门禁 7 类（含负向自证）／提交顺序 7 步／上机验收清单／可勾选任务清单／开源参考 10 项 | 待裁决 |
| v1.1 | 2026-09-28 | **补至可开发级**：新增 **§十五 开发实施手册**（§15.0 七项自检表 · §15.1 逐文件清单 6 新增 + 20 修改含预估行数 · §15.2 完整可粘贴签名 6 个文件 · §15.3 逐文件改造点 20 行 + 复用核实补 6 条 · §15.4 源码级 API 核实结论 A1–A20 · §15.5 既有断言同步项 · §15.6 逐步验证命令 · §15.7 陈旧注释 7 条）。**修正既有错误 3 处**：① §2.2 的 `LIGHT_BEAMS` 行号 `:1566-…` → `:1566-1718`；② §5.3 的 `Shading2D.shadeBrush` 按字面实现会每帧分配 `Brush`（`Brush.radialGradient` 首参是 `vararg Pair`，`Brush.kt:296-306`）⇒ 改为 `shadeBrushCached`（静态主体）+ `ProceduralTexture.sphereSprite`（移动主体）；③ §六 A5 第 3 条「`withTransform` 每帧分配 lambda」与源码不符（ui-graphics 1.6.1 里 `withTransform` 是 `inline`，`DrawScope.kt:259`），改为"少一次 save/restore" | **可开工**（4 项取舍见 §十三） |
| v1.2 | 2026-09-29 | **修正「数字雨字符集」方向性错误 + 新增已裁决节**。§B3/§2.2/V14/T4.3/§15.3 共 **7 处**原写「字符集扩到 `0/1/A–F`」「字符不再只有 0/1」—— 与**设计本意相反**（v2.30.4 误扩为 `0-9`，用户指出后已于 2026-09-13 撤销，`cbfaa8e`）。已全部改为「**字符集固定 0/1**；质感靠字形描边 + 垂直渐变 + 档位 4→5（8 → 10 张）、头部光晕、垂直拖影来做」。新增 **§13.5 已裁决（不再讨论）**：D1 数字雨字符集固定 0/1 / D2 `VisualizerTheme` 28 项不变 / D3 切换维持硬切。§15.7 新增第 8 条（`AdvancedRenderers.kt:343-353` 陈旧 KDoc 写「0-9 / 10 数字」与实现不符） | **可开工** |
| v1.3 | 2026-09-29 | **E19 / E23 的性能优化（用户要求"不增加性能消耗 + 做性能优化"）**。新增 **§G11**（离屏采样用逐像素 `getPixel()`）与 **§G12**（`Path.addOval(Rect)` 每帧分配）两条共性缺口 → §四 由 10 条变 **12 条**；新增 **§15.3.2 #15**（零分配批量圆点：`dotPath` + `addPath` + `rewind()`，含 `drawRawPoints` 陷阱）；新增 **§15.4.4 A21–A29**（`Bitmap.getPixels` 自 API 1 可用 / `Rect` 是 data class 而 `Offset` 是 value class / `Canvas.drawRawPoints` 实为逐点循环 / `Path.addPath` 与 `rewind` 等，全部本机 jar 实测）；**重写 §B5（E19）与 §B7（E23）**，改为"先砍三条性能红线、再用腾出的预算做观感"，含 before→after 成本账；R6 原口径作废、新增 R11/R12；V16/V18 加入性能判据；T4.5/T4.7 改为"顺序不可反"的分步；§15.7 新增第 9/10 条。**关键修正**：`docs/archive/music-visualizer-dev-plan.md:876` 把 `getPixel()` 标为"唯一可用"**是错的**（`getPixels` 自 API 1 可用），此错误导致 E19/E23 长期逐像素采样 | **可开工**（E23 为性能优化优先） |
| v1.4 | 2026-09-29 | **§2.2 补齐"启动 Toast 显示名"**（用户反馈"对应不上"）。① §2.2 新增 **「名称 ↔ 枚举 ↔ 编号」速查表**（28 行，名称即 `VisualizerTheme.displayName`），并注明 Toast 出处 `VisualizerStage.kt:406-428`、`序` 列 = `ordinalLabel`（**UI 不显示**，只用于单测与文档编号）；② 宽表表头 `名称` → **`名称（Toast 原文）`**；③ **修正 E29 名称**：`轨道（太阳系）` → **`轨道`**（Toast 只显示"轨道"，括号是设计说明）—— 这是 28 项里**唯一**与 `displayName` 不一致的一处；④ §2.3 汇总与 §15.3.1 / §15.6 / §十 的 `E<nn>` 编号**全部补上中文名称**（34 处 `E<nn>：` + 11 处散落引用），此后全文**不存在**只有编号没有名称的引用。已用脚本逐字比对：体检表 28 行 / 速查表 28 行 / 枚举 28 项，**0 处不一致** | **可开工** |

| v1.5 | 2026-09-29 | **E24 心跳「波形真实化」**（用户要求「ECG_WAVE 心跳，要考虑画出的波形更像真实的心跳波形」）。① **§2.2 的 E24 行缺口列改写**：原来只写"网格/余辉/CRT 感"，**完全没提波形形状**；实测 `EcgWaveRenderer.kt:250-259` 的 `HB_T`/`HB_V` 只有 **14 点**且每段首尾相接成锯齿 —— 全表**没有任何一段等电位平线**（PR 段实测是 +0.099→−0.105 的**下坡**、ST 段是 −0.215→+0.175 的**上坡**），R 峰落在 0.214s（19.26 列）**不被任何显示列命中 ⇒ 采样峰值只有 0.897**，T 波仅 3 点/122ms 而 QRS 104ms ⇒ **T/QRS 宽度比 1.17**（真实 1.8）。② **§A8 新增 P0-0「心搏波形真实化」**（原 5 条顺延为 1–5）：`HB_T`/`HB_V` 两张表 → **单表 `HB`（50 点 = 一列一个采样）**，`heartbeatAt()` 由"线性扫描 + 线性插值"退化为 **O(1) 整数索引（零插值）**；同时把 `colMs`/`beatAtMs`/`lastFireMs` 三个**累加 float** 换成**整数列号** `colIdx`/`beatCol`/`lastFireCol`（顺带**删掉** `REBASE_AT_MS` 精度回绕 hack）；按临床时程重排（PR 178 / QRS 89 / ST 111 / T 160 / QT 360 ms，P +0.14 / Q −0.11 / R +1.00 / S −0.28 / T +0.27），**R 峰对齐第 19 列**（采样峰值 0.897 → **1.000**）；**零新增分配、零新增 draw**。③ 新增 **§八 G8 `EcgWaveformTest`**（7 条断言 + 负向自证：把旧表喂进去，等电位/宽度比/峰值三条必须挂）。④ 新增 **§15.4.4 A30**（`colMs` 是累加 float ⇒ 必须改整数列号，且表**一列一个采样**就够）。⑤ 新增 **§十三 裁决项 5**（显示增益 `AMP_FRACTION` 0.26 → 0.16 与否，并写明「横向压缩是固有的、不要试图修」）。⑥ V8 / T3.4 / §15.1.2 / §15.3.1 / §15.6-S3 同步；§八 由 **7 类 → 8 类**。⑦ **修正方案文档自身 1 处错误**：§A8 原第 5 条要求"增强扫掠头"，而 `EcgWaveRenderer.kt:45` 的 KDoc 明确「**峰顶无帽**：不绘制任何节拍点/扫描头圆点」⇒ **该条已作废**（§15.7 新增第 12 条记录此教训：写方案前先读被改类的 KDoc）。⑧ §15.7 新增第 11 条（E24 改动后会失真的 6 处注释）。⑨ **修正 §2.3 的计数复核手法**：原注写"全文件范围数只多出 §十四 一行"**已过期** —— 实测全文件匹配 `^\| \d\d \| \`` 的行数是 **54**（§15.1.2 / §15.5 / §15.7 也是"两位序号 + 反引号"表），已改为给出**区间截取**的可靠手法（实测 = 28 ✓） | **可开工**（E24 波形为纯数据 + 表驱动，可独立先做） |

| v1.6 | 2026-09-29 | **E30 雷达「真实 PPI 行为」**（用户要求「真实的雷达应该是一条线扫描，然后扫描出现的点，**在下次扫描后会变化位置**」）。① **§2.2 的 E30 行缺口列改写**：原来只写"等亮度/无余辉/无 CRT"，**没提"这个雷达没有真实 PPI 的核心行为"**；逐行核对后**四条真实行为一条都不成立** —— 回波被**焊在扫掠线后方**（`:171-172` `sweepAngle − i*TAU/segs*2f`）随扫线一起转、**无磷光余辉保持**（亮度 = 瞬时频谱 `v*0.8f`，`:182`）⇒ **不可能"下一圈换位置"**、弧跨度 `TAU/segs*2`×32 段 = **2 整圈**（互相重叠两遍、方位角不承载信息）；② **发现并记录 1 个真 bug**：`ensureBrush`（`:64-75`）在尺寸未变时直接 `return`、停靠点写死 ⇒ **扫掠光晕钉死在 252°–360° 从不旋转**（画面上"在转的"只有那条 2.5 px 主扫线）；③ **§A9 新增 P0-0「真实 PPI 重建」**（原 5 条压成 1–3）：目标池 `FloatArray(24×8)`（`BEARING/RANGE/DRIFT_B/DRIFT_R/BIN/BRIGHT/PEAK/LIFE`）+ `crossed()` **左开右闭区间判定**（抗跨 0 回绕）+ `BRIGHT` 线性衰减（`holdSec ≈ 1.15 × 周期`，撑到下一圈）+ 连续漂移 + **寿命到重生（不是消失）** + 回波按 `BEARING` 画短弧（`drawArc` + `Size`）+ 距离衰减；**删掉 `drawSpectrumArcs` 整段**；转速改**恒定**、treble 改去缩短余辉；**修扫掠扇**用 `withTransform` 旋转（⛔ 不重建 `Brush`）。④ 新增 **§八 G9 `RadarSweepTest`**（7 条断言 + 4 条负向自证）⇒ §八 由 **8 类 → 9 类**，计数净增改为 **62–85 例**。⑤ 新增 **§15.4.4 A31–A33**（`withTransform` 零分配旋转预分配 Brush / `Size` 是 `value class` 而 `Rect` 是 `data class` / `SweepGradient` 角度 0 在 3 点钟且顺时针，与 `(cos,sin)` 同向 ⇒ 不需补 90°）。⑥ 新增 **§十三 裁决项 6**（方位/距离是否与频率绑定，**默认解耦**）。⑦ **修正 §15.7 第 6 条**：原写"删除 4 个文件的未使用 `withTransform` import" —— **必须排除 `BatchThreeRenderers.kt`**（本版 E30 会重新用上它）。⑧ V9 / T3.5 / §15.1.2（+1 测试文件、BatchThree +110/−40 → **+165/−70**）/ §15.3.1 / §15.6-S3 / §1.1 需求表（补第 ⑦ 条）全部同步。⑨ **发现并标注一处既有编号冲突**：**§八 的门禁号 `G1–G9` 与 §四 的共性缺口号 `G1–G12` 同字母不同含义**（§六 里所有 `（G2）`/`（G5）`/`（G9）` 括注都指 §四 缺口）—— 已在 §八 头部加**编号消歧**说明，并把 §九 R2 的 `G5 门禁护住` 改写为 **`§八 G5`**；建议后续把门禁号改成 `GT1–GT9` 彻底消除歧义（本次未改，避免动到既有交叉引用） | **可开工**（E30 的 P0 与 E24 的 P0 都是纯逻辑，可各自独立先做） |

| v1.7 | 2026-09-29 | **E32 阶梯「全柱独立频段映射」**（用户要求「这个只有几个柱在动，其他不会动，如何让所有的柱都随音乐波动，真实反应音乐频谱」）。① **数值审计**（`logs_temp/staircase_mapping_audit.py`，逐字复算 `BatchThreeRenderers.kt:462-470` 的 `src` 映射）**三条硬事实**：⛔ 三种画质下**只覆盖 bin 0..28 / 29 / 30**（`n / 2 = 32` 半区）⇒ **缺失 33–35 个桶**，连 `BASS_END = 39` 的低音区都没覆盖完，**中高频完全不可见**；⛔ 命中**不同桶 = 10 / 14 / 18**，恰好是列数（20 / 28 / 36）的**一半**，且三种画质下 `src(i) == src(cols-1-i)` **恒成立（左右严格镜像）** ⇒ 每对镜像列同高、视觉分辨率减半；⛔ **16 档量化下 `v ∈ [0, 0.125)` 的列高度完全相同（都是 1 格）**，只有 alpha 不同 ⇒ 中高频那一片是"恒定 1 格矮柱"。三条叠加 = 用户看到的"只有几个柱在动"。② **§2.2 的 E32 行缺口列改写**（原来只写"硬边/无发光/碎裂机械"，**完全没提映射**）。③ **§A11 新增 P0-0「全柱独立频段映射」**（原 5 条顺延为 1–5）：删掉 `n / 2` 半区 → 覆盖 `0..63` 全频段；`cols` 列 → `cols` 个**互不重叠的桶区间**、每列取**区间均值**（`onEnter` 里预建 `IntArray(cols + 1)` 边界表，⛔ draw 内零分配）；区间按**感知（对数）划分**（`k ≈ 1.6`，低频占更多列）；`STEPS` 16 → **24** 并**分离水平/垂直缝**（现用同一个 `gap` 同时减宽减高，`:450-453`）；块亮度由"按 `steps` 归一"改为"**按绝对格位**归一"（现 `level = (st+1)/steps` ⇒ 同一格位在不同 `steps` 下 alpha 抖动 = 闪烁）；静音列门槛改用 `SpectrumContract.MIN_AMPLITUDE`。④ 新增 **§八 G10 `StaircaseMappingTest`**（6 条断言 + 3 条负向自证）⇒ §八 由 **9 类 → 10 类**，计数净增改为 **74–99 例**。⑤ 新增 **§15.4.4 A34–A36**（`Math.pow`/`Double.pow` 自 API 1 可用且 `onEnter` 只调一次 ⇒ 不违反 draw 零分配 / `IntArray` 边界表比 `FloatArray` 省一半内存且整数比较无浮点误差 / ⛔ **E32 的镜像与 `BarSpectrumRenderer.mirrored` 语义不同**：基类注释写"低频居中"，而 E32 的 `x = i * cellW`（`:471`）明确 `i = 0` 是最左列 ⇒ **低频在两侧、中频在中心**，两者相反）。⑥ 新增 **§十三 裁决项 7**（**镜像 vs 全宽展开**，默认 **A 全宽展开** —— 镜像会让列分辨率减半）。⑦ V11 / T3.7 / §15.1.2（BatchThree **+165/−70 → +210/−95**、+1 测试文件）/ §15.3.1 / §15.6-S3 / §1.1 需求表（补第 ⑧ 条）全部同步。⑧ §15.7 新增第 13 条：`BatchThreeRenderers.kt:463` 注释写「**低频居中**（与柱状频谱基类一致的空间分布）」，与实现相反（同源的 `BasicRenderers.kt:58` 也有此说法） | **可开工**（纯映射逻辑，可独立先做） |

> ⛔ 本文件每次修订必须在此表**追加一行**；文档版本号只增不改。

---

## 一、目标与核心结论

### 1.1 这一轮到底在解决什么问题

用户原话拆成八条需求：

| 需求 | 本方案的对应章节 |
|---|---|
| ① 近期没完善的、没修的效果，都做个优化方案 | §二（体检表）+ §六（逐效果清单，A/B 两批共 21 套） |
| ② 偏重显示效果，质感提高、真实感加强 | §四（共性缺口诊断，10 条全是"看起来假"的根因）+ §五（工具箱） |
| ③ 参考网上其他开源代码 | §十四（10 项真实开源参考 + 逐条"借鉴什么"） |
| ④ 已改过的效果也评估一下 | §六 批次 C（7 套：齿轮/轨道/怀旧/分子/照片墙/DNA/世界） |
| ⑤ **E19 粒子文字 / E23 歌词点阵：不增加性能消耗的前提下做画面优化，还要做性能优化** | §四 **G11/G12**（两条性能缺口）+ §六 **§B5 / §B7**（含 before→after 成本账）+ §八 G7 + §15.3.2 #15（零分配批量圆点）+ **§15.4.4 A21–A29** |
| ⑥ **E24 心跳：波形要更像真实的心跳波形** | §六 **§A8 第 0 条（P0）**（临床时程重排 + 一列一采样表 + 整数列索引）+ §八 **G8** + **§15.4.4 A30** + §十三 裁决项 5 |
| ⑦ **E30 雷达：真实雷达应是"一条线扫描，扫出的点下次扫描后换位置"** | §六 **§A9 第 0 条（P0）**（目标池 + 磷光余辉保持 + 连续漂移 + 修"扫掠扇不转"的真 bug）+ §八 **G9** + **§15.4.4 A31–A33** + §十三 裁决项 6 |
| ⑧ **E32 阶梯：只有几个柱在动，其他不会动 ⇒ 要让所有柱都随音乐波动、真实反映频谱** | §六 **§A11 第 0 条（P0）**（覆盖全 64 桶 + 列列独立桶区间 + 区间均值 + 感知分桶 + `STEPS` 16→24 + 亮度按绝对格位归一）+ §八 **G10** + **§15.4.4 A34–A36** + §十三 裁决项 7 |

### 1.2 核心结论（先看这五条）

| 问题 | 结论 |
|---|---|
| 28 套效果里，**有质感后处理**（暗角/颗粒/色差）的占几套？ | **3 套**（世界、齿轮、怀旧）。其余 25 套"贴在纯色底上"，缺纵深与胶片感 ⇒ 这是**收益最大的单点** |
| 是不是每套都要重写？ | **不是**。28 套里 **11 套**只差"叠一层后处理 + 描边加明暗"，属于**低成本高收益**（批次 A）；真正要动结构的是少数 |
| 有没有能一次改动、28 套同时受益的杠杆？ | **有**，两个：**① 共享 `fx/` 工具箱**（§五）；**② 扫描线/颗粒改用预生成 tile 平铺**（把 N 次 `drawLine` 换成 1 次 `drawImage`，**既提质又提速**） |
| 会不会拖慢电视（Android 5.1 / 弱 GPU）？ | 风险真实存在。方案把后处理分成 **零离屏叠加层**（默认全档可用，1–3 次 draw 调用）与 **离屏层**（仅 HIGH 档，可选）两级，见 §5.3、§九 |
| 会不会破坏现有门禁？ | 不新增枚举成员、不改 `update(...)` 签名 ⇒ `VisualizerThemeTest`(28/27)、`RendererSwapperTest` 等**零改动**。新增门禁见 §八 |

### 1.3 三条设计原则（写进 KDoc，实现时不得违反）

1. **质感的来源是"光"，不是"更多层"**。
   现在的做法普遍是"再加一层描边/再加一圈同心圆"（`BasicRenderers.kt:198-212` 的
   `glowLayers` 循环、`MoleculeRenderer.kt:513-515` 的三层同心圆）。这在物理上是错的：
   真实物体的明暗来自**光照方向**与**表面法线**。所以本方案统一引入
   **主光方向常量 + 法线明暗 + 边缘光**，用**更少的绘制调用**换更好的观感。
2. **后处理必须与效果解耦，但必须按档位门控**。
   暗角/颗粒/扫描线/色差在 25 套里完全缺失，逐套实现会写成 25 份不一致的代码。
   统一进 `fx/OverlayFx`；但**LOW 档一律 OFF**（电视弱 GPU 的第一杀手是填充率）。
3. **⛔ 每帧零分配红线不放松**。所有纹理在 `onEnter`/首次 `ensure` 时生成并缓存，
   `draw` 内只允许复用成员与值类型（`Offset`/`Color`/`Size` 均为 value class）。
   ⛔ 新写的 `Path`/`List`/lambda 一律视为缺陷。

---

## 二、现状盘点：28 套效果的「质感体检」

### 2.1 怎么读这张表

- **最后实质改动**：只统计**改画面**的提交；被"移除 11 个效果"这类重构扫到的文件不算改动
  （`BasicRenderers.kt` / `AdvancedRenderers.kt` / `ParticleRenderers.kt` 在 `0e7e45c`
  只被删代码，实际画面逻辑停留在 09-13 / 09-14）。
- **质感技法**：只列**实际用于提升观感**的技法（深度排序、渐变、光晕层、纹理、色差…）。
- **缺口等级**：`★★★` = 一眼看得出廉价（平涂、硬边、无纵深）；`★★` = 结构对但缺光效；
  `★` = 已经不错，只差后期精修。
- **批次**：`A` 重做级（11 套）／`B` 增强级（10 套）／`C` 精修级（7 套）。

### 2.2 体检表（按枚举顺序）

> **⚠️ 「名称」列 = 启动该效果时屏幕上弹出的 Toast 原文**，即 `VisualizerTheme.displayName`
> —— Toast 代码在 `ui/components/VisualizerStage.kt:406-428`（`text = theme.displayName`，
> 居中半透明黑底、20sp 白字）。**用它就能和你在电视上看到的文字一一对上。**
>
> 「序」列 = `VisualizerTheme.ordinalLabel`（`AppSettings.kt:104-108`）—— ⚠️ **UI 上不显示**，
> 只用于单测（`VisualizerThemeTest.kt:95`）与本文档的编号引用（**`E16` 就是 `ordinalLabel "16"`**）。

**速查：名称 ↔ 枚举 ↔ 编号**（先在这里定位你要的那一套，再回下面体检表看细节）

| 名称（Toast 原文） | 枚举 | 编号 |
|---|---|---|
| 隧道穿越 | `TUNNEL_FLY` | E03 |
| 圆形频谱环 | `CIRCULAR_RING` | E05 |
| 频率山峦 | `FREQUENCY_MOUNTAIN` | E07 |
| 星系螺旋 | `GALAXY_SPIRAL` | E11 |
| 频谱瀑布 | `SPECTRO_WATERFALL` | E12 |
| 液态网格 | `LIQUID_GRID` | E13 |
| 节拍烟花 | `BEAT_FIREWORK` | E14 |
| 液态涟漪 | `LIQUID_RIPPLE` | E15 |
| 数字雨 | `MATRIX_RAIN` | E16 |
| 星座 | `CONSTELLATION` | E17 |
| 反馈残像 | `MILKDROP_FEEDBACK` | E18 |
| 粒子文字 | `PARTICLE_TEXT` | E19 |
| 等离子流场 | `PLASMA_FLOW` | E20 |
| 歌词点阵 | `LYRICS_DOT_MATRIX` | E23 |
| 心跳 | `ECG_WAVE` | E24 |
| 催眠 | `HYPNOTIC_FUNCTION` | E25 |
| 轨道 | `ORBITAL_RINGS` | E29 |
| 雷达 | `RADAR_GRID` | E30 |
| 折纸 | `ORIGAMI_POLY` | E31 |
| 阶梯 | `STAIRCASE_WAVE` | E32 |
| 齿轮 | `CONCENTRIC_GEARS` | E33 |
| 分形 | `FRACTAL_TREE` | E34 |
| 光轴 | `LIGHT_BEAMS` | E35 |
| 分子 | `MOLECULE` | E37 |
| 怀旧 | `VINTAGE_TV` | E38 |
| 照片墙 | `PHOTO_WALL` | E39 |
| DNA 双螺旋 | `DNA` | E40 |
| 世界 | `WORLD` | E41 |

> ⚠️ 上表 28 行的「名称」与 `AppSettings.kt` 的 `displayName` **逐字一致**（已用脚本比对）。
> 历史坑：本表曾把 E29 写成「轨道（太阳系）」，而 Toast 只显示「**轨道**」——
> 括号里的是设计说明，不是显示名，容易对不上。

**体检表正文**

| 序 | 枚举 | 名称（Toast 原文） | 渲染器（`file:line`） | 最后实质改动 | 现有质感技法 | 缺口 | 批次 |
|---|---|---|---|---|---|---|---|
| 03 | `TUNNEL_FLY` | 隧道穿越 | `BasicRenderers.kt:64-125` | 09-13 | 伪 3D 透视（`scaleAt`）、按深度 3 桶合并点、`treble` 驱动环亮度 | 环是**单色描边**、无雾/无纵深衰减色、无中心光晕、点按桶合并后**丢逐点 alpha** | ★★★ | A |
| 05 | `CIRCULAR_RING` | 圆形频谱环 | `BasicRenderers.kt:137-281` | 09-13 | hue 周向渐变、峰值帽 8 桶合并、`glowLayers` 加宽描边、中心圆盘 3 层 | 条体**无明暗两侧**；"光晕"是同色加宽描边（**假光晕**，边缘硬）；环是 8 段 `drawArc` 拼的（有色阶断层） | ★★★ | A |
| 07 | `FREQUENCY_MOUNTAIN` | 频率山峦 | `BasicRenderers.kt:292-327` | 09-13 | 5 层半透明面积叠加、双色交替 | **无底色/无纵深**；层间只有 alpha 差、**无边缘辉光**；折线是 `lineTo` 硬折（无平滑） | ★★★ | A |
| 11 | `GALAXY_SPIRAL` | 星系螺旋 | `AdvancedRenderers.kt:37-86` | 09-14 | 16 臂对数螺线、亮度分 2 桶、中心核 | 星点是**等大小圆点**（无闪烁/无星芒）；**无星云/尘埃带**；中心核是纯色圆 | ★★ | B |
| 12 | `SPECTRO_WATERFALL` | 频谱瀑布 | `AdvancedRenderers.kt:100-159` | 09-14 | 乒乓 `ImageBitmap` 滚动、hue 映射能量 | 128×200 缓冲**放大后严重糊**（无插值控制）；**无网格/刻度**；无衰减拖尾 | ★★★ | A |
| 13 | `LIQUID_GRID` | 液态网格 | `AdvancedRenderers.kt:171-263` | 09-14 | 三频正弦位移、单 `Path` 连线、顶点 4 段 hue | 连线**单色 1px 硬线**（无高光）；顶点是圆点（**无水面反光/无折射感**）；无底色 | ★★★ | A |
| 14 | `BEAT_FIREWORK` | 节拍烟花 | `ParticleRenderers.kt:29-117` | 09-14 | 粒子池、`Plus` 叠加、方向由低频最强柱决定 | 粒子是**纯色圆点**（无拖尾/无重力尾迹）；爆炸**无冲击波环/无闪光**；静音期几乎全黑 | ★★ | B |
| 15 | `LIQUID_RIPPLE` | 液态涟漪 | `AdvancedRenderers.kt:274-337` | 09-14 | 40 环池、三频分速、按种类分宽 | 环是**单色细描边**（无厚度/无高光）；**无折射/无水面纹理**；无中心亮斑 | ★★★ | A |
| 16 | `MATRIX_RAIN` | 数字雨 | `AdvancedRenderers.kt:354-475` | 09-14 | 字形**预渲染 8 张 Bitmap** + `nativeCanvas` blit、4 档绿、列速随频谱；字符集 **0/1 二进制雨**（设计本意，⛔ 不扩，见 §13.5） | 头部**无光晕/无拖尾辉光**；**无垂直拖影**（真数字雨的核心）；字形**纯色平涂**（无描边/无渐变） | ★★ | B |
| 17 | `CONSTELLATION` | 星座 | `AdvancedRenderers.kt:486-568` | 09-14 | 160 星点池、邻近自动连线（单 `Path`）、生命衰减 | 星点是**纯色小圆**（无星芒/无闪烁）；连线**单色 1.7px**（无渐隐）；O(n²) 连线有性能上限 | ★★★ | A |
| 18 | `MILKDROP_FEEDBACK` | 反馈残像 | `UltraRenderers.kt:32-144` | 09-14 | **唯一真帧缓冲反馈**（1280×720 乒乓）、缩放+旋转+位移回绘、频谱环叠加 | 反馈层**无色调映射**（长时间叠加会糊成灰白）；无 `blur`（只有几何变换）；色相流动单调 | ★★ | B |
| 19 | `PARTICLE_TEXT` | 粒子文字 | `ParticleRenderers.kt:134-265` | 09-14 | 离屏 `Bitmap` 采样字轮廓、粒子吸附、`beat` 炸散 | ⛔ **每帧 350 次独立 `drawCircle(Plus)`**（逼近 hwui 320 崩溃阈值）；⛔ **采样 16,280 次 `getPixel()` JNI**；⛔ **行优先 + `n >= cap` 提前 break ⇒ 粒子只覆盖文字上部少数几行**（E23 已修同一问题，E19 未修）；⛔ **约 4.2s 后粒子耗尽 ⇒ 画面永久空白**；固定 `textSize=150` 无测量 ⇒ 长标题被裁、短标题偏左；全字同色平涂 + `Plus` 高 alpha ⇒ 过曝死白 | ★★ | B |
| 20 | `PLASMA_FLOW` | 等离子流场 | `UltraRenderers.kt:156-256` | 09-14 | value noise 流场（16×9，3 帧更新）、双线性采样、`Plus` 粒子 | 噪声网格 **16×9 极粗**（流场呈块状）；**无等离子底色纹理**；粒子纯色圆点 | ★★ | B |
| 23 | `LYRICS_DOT_MATRIX` | 歌词点阵 | `LyricsDotMatrixRenderer.kt:394-612`（采样 `:184-355`） | 09-14 | 离屏字形采样（自适应步长 + 按行配额）、6 条 `Path` 分 3 组色（暗/中/亮）、逐字消散/凝聚、自适应字号 | ⛔ **每帧最多 14,400 个 `Rect` 分配**（`:698`/`:703`，每点 core+glow 各 1 个；`cap = 3600` × 2 行）≈ **27 MB/s 垃圾**（`Rect` 是 data class，见 §G12）；⛔ **采样两遍 `getPixel()` ≈ 71,000 次 JNI**（≈ 35–70 ms，且 `sampleLine` 在 `draw` 内 ⇒ **每次换行卡 2–4 帧**，见 §G11）；⛔ 每帧 7200 次 `sin` 中有一半是**循环内重算全局量**；⛔ `phaseVal` / `textLen` / `baseSize` 是**死代码**（`:641`/`:626`/`:307`）；点阵**无景深**；字边缘无光晕；亮档 `Plus` alpha 达 0.98 ⇒ **过曝** | ★★ | B |
| 24 | `ECG_WAVE` | 心跳 | `EcgWaveRenderer.kt:50-215`（波形表 `:250-259`） | 09-13 | 网格线（`Plus`）、波形 `Path` 双层（辉光 + 主线）；⛔ 设计上**无扫描头/无节拍点**（KDoc `:45`「峰顶无帽」，既有裁决） | ⛔ **波形本身不像心电图**（最要命的一条）：`HB_T`/`HB_V` 只 **14 点**，且**每段首尾相接成锯齿 —— 全表没有一段等电位平线**（实测 PR 段是 `+0.099→−0.105` 的**下坡**、ST 段是 `−0.215→+0.175` 的**上坡**）；R 峰在 0.214s（19.26 列）**不落在任何显示列上 ⇒ 采样峰值只有 0.897**（每拍都矮 10%）；**T 波 122ms / 3 点 vs QRS 104ms ⇒ 宽度比 1.17**（真实 ≈1.8）⇒ 视觉是"两个差不多宽的三角尖"而不是"窄高尖峰 + 宽低圆峰"；P 波前 48ms 是平线（起点错位）。**其余**：网格是**等亮度细线**（无纵深/无透视）；波形**无余辉拖尾**；**无 CRT 感**（网格线没有衰减） | ★★★ | A |
| 25 | `HYPNOTIC_FUNCTION` | 催眠 | `HypnoticFunctionRenderer.kt:342-538` | 09-13 | 函数曲线 `Path` 双层（辉光 `Plus` + 主线）、描线头光点、网格/坐标轴/刻度 | 网格与坐标轴**同色单线**；曲线**无沿法线的厚度感**；**无纸张/无背景纹理** | ★★ | B |
| 29 | `ORBITAL_RINGS` | 轨道 | `BatchTwoRenderers.kt:63-632` | **09-28** | 深度排序遮挡、近大远小、太阳 3 层光晕+日冕、行星斜上高光、地球陆地/木星条纹、土星环前后半弧 | 行星是**纯色圆 + 单点高光**（无**径向光照**、无昼夜明暗线、无**边缘光**）；土星环**单色**（无环缝/无 alpha 渐变）；**无颗粒/暗角** | ★ | C |
| 30 | `RADAR_GRID` | 雷达 | `BatchThreeRenderers.kt:30-188` | 09-13 | `SweepGradient` 扫掠、射线余弦衰减、频谱短弧、单色雷达绿 | ⛔ **没有真实 PPI 的核心行为**（原理性错误）：① 回波**焊在扫掠线后方**（`:171-172` `sweepAngle − i*TAU/segs*2f`）⇒ 随扫线一起转、**从不留在原地**；② **无磷光余辉保持**（亮度 = 瞬时频谱 `v*0.8f`，`:182`）⇒ 频谱一掉就没，因此**不可能"下一圈换位置"**；③ 弧角度跨度 `TAU/segs*2` × 32 段 = **2 整圈**⇒ 弧互相重叠两遍、方位角不承载信息；④ ⛔ **扫掠光晕根本不转**（`ensureBrush :64-75` 在尺寸未变时直接 `return`，停靠点写死）⇒ 亮扇区**钉死在 252°–360°**（真 bug）；⑤ 转速被 treble 拉 6×（`:102`）⇒ 失去"天线匀速转"的机械感。**其余**：同心圆/射线**等亮度**（无内亮外暗）；**无 CRT/荧光屏质感**（无颗粒、无扫描线、无暗角） | ★★★ | A |
| 31 | `ORIGAMI_POLY` | 折纸 | `BatchThreeRenderers.kt:201-403` | 09-13 | 顶点抖动低多边形、折叠 `cos` 投影、明暗面（0.62×）、冷暖 hue 插值 | 三角**纯色平涂**（无渐变/无折痕高光）；**无投影**（叠纸悬浮感）；折痕是硬边 | ★★★ | A |
| 32 | `STAIRCASE_WAVE` | 阶梯 | `BatchThreeRenderers.kt:417-516` | 09-13 | 垂直量化、碎裂 2×2、越高的块越亮 | ⛔ **频谱映射是错的（最要命的一条）**：① `src` 只取 `n / 2 = 32` 的**前半区**（`:466`/`:468`）⇒ 实测三种画质**只覆盖 bin 0..28 / 29 / 30**，**缺失 33–35 个桶** —— 连 `BASS_END = 39` 的低音区都没覆盖完，**中高频完全不可见**（镲片/齿音时右侧毫无反应）；② 命中**不同桶 = 10 / 14 / 18**，恰好是列数（20 / 28 / 36）的**一半**，且三种画质下 `src(i) == src(cols-1-i)` **恒成立** ⇒ 左右严格镜像、每对镜像列同高 ⇒ 视觉分辨率减半（这就是用户说的"**只有几个柱在动**"）；③ **16 档量化下 `v ∈ [0, 0.125)` 的列高度完全相同（都是 1 格）**，只有 alpha 不同 ⇒ 中高频那一片是"恒定 1 格矮柱"。**其余**：方块是**硬边 `drawRect`**（无圆角/无抗锯齿）；**无发光溢出**；碎裂是 4 个方块的机械拼贴（无位移/无角度）；块亮度 `level = (st+1)/steps`（`:487`）随 `steps` 变 ⇒ 同一格位在不同高度下 alpha 抖动 = **闪烁**；水平/垂直**共用同一个 `gap`**（`:450-453`）⇒ 两方向的缝宽比例不一致 | ★★★ | A |
| 33 | `CONCENTRIC_GEARS` | 齿轮 | `BatchFourRenderers.kt:107-1321` | **09-28** | 随机三级啮合布局、参数化齿形、双层描边、径向纵深、暗角、颗粒 tile、星野、啮合火花、`Plus` | 齿廓描边**单色**（无沿齿面的法线明暗 → **金属感缺失**）；**无 AO/接触阴影**；轴心高光是**同心硬边圆**（非方向性 specular） | ★ | C |
| 34 | `FRACTAL_TREE` | 分形 | `BatchFourRenderers.kt:1334-1565` | 09-13 | 递归分形结构、层级色变 | 枝干**单色描边**（无明暗/无锥度渐变）；**无叶/无花/无粒子**；**无背景纵深** | ★★ | B |
| 35 | `LIGHT_BEAMS` | 光轴 | `BatchFourRenderers.kt:1566-1718` | 09-13 | 光束多边形、`Plus` 叠加 | 光束是**纯色多边形**（无 `gradient` 衰减 → 硬边光柱）；**无体积雾/无尘埃**；无镜头光斑 | ★★★ | B |
| 37 | `MOLECULE` | 分子 | `MoleculeRenderer.kt:317-538` | 09-26 | 原子/键/键上光点、3 层同心辉光、`nativeCanvas` 分子式、下标修正 | 辉光是**三层同心圆**（物理上错、边缘可见台阶）；原子**纯色**（无**球面光照/高光/环境反射**）；键是**单色线** | ★ | C |
| 38 | `VINTAGE_TV` | 怀旧 | `VintageTvRenderer.kt:286-326` | **09-27** | 扫描线、噪点 buffer、滚动暗带、**RGB 色差 `ColorFilter`**、vignette、圆角、胶片孔、OSD | 扫描线是 **240 次 `drawLine`**（硬边 + 昂贵）；噪点是**稀疏方块**非真 grain；色差是**整层 tint**（非真通道分离）；**无桶形畸变/无磷光余晖** | ★ | C |
| 39 | `PHOTO_WALL` | 照片墙 | `photo/PhotoRenderer.kt:87-…` | 09-27 | 43 种转场、Ken Burns、音频呼吸、双缓冲 | 转场多为几何变换（**无光效类转场**）；**停留期无画面后期**（无暗角/颗粒/色差 → 与"老照片"气质不符） | ★ | C |
| 40 | `DNA` | DNA 双螺旋 | `DnaRenderer.kt:467-681` | **09-28** | Catmull-Rom 中心路径、深度 z 排序遮挡、4 桶线宽/alpha、3 层辉光、星野 | 骨架是**单色 `drawLine`**（无沿法线的明暗 → **无绸缎/体积感**）；背景**纯色**（无径向纵深）；**无暗角/无颗粒/无色差** | ★ | C |
| 41 | `WORLD` | 世界 | `WorldRenderer.kt:582-702` | **09-28** | 12 层管线（径向纵深/vignette/grain/星野/双层描边/昼夜带/城市光点/航线/彗尾/涟漪） | 陆地是**单一中性色**填充+描边（无地形纹理/无海岸明暗）；平面投影**无球面光照/无 AO**；grain tile **平铺有接缝风险** | ★ | C |

### 2.3 汇总（计数自洽校验）

| 缺口等级 | 套数 | 枚举（名称 + 编号，名称即 Toast 原文） |
|---|---|---|
| ★★★ 一眼廉价 | **11** | 隧道穿越 E03、圆形频谱环 E05、频率山峦 E07、频谱瀑布 E12、液态网格 E13、液态涟漪 E15、星座 E17、心跳 E24、雷达 E30、折纸 E31、阶梯 E32 |
| ★★ 结构对但缺光效 | **10** | 星系螺旋 E11、节拍烟花 E14、数字雨 E16、反馈残像 E18、粒子文字 E19、等离子流场 E20、歌词点阵 E23、催眠 E25、分形 E34、光轴 E35 |
| ★ 已不错，差精修 | **7** | 轨道 E29、齿轮 E33、分子 E37、怀旧 E38、照片墙 E39、DNA 双螺旋 E40、世界 E41 |
| **合计** | **28** | 与 §2.2 表格行数一致。**复核手法**：取「`| 序 | 枚举 | 名称（Toast 原文） |` 表头」到下一个 `### ` 标题之间的区间，数匹配 `^\| \d\d \| \`` 的行数，应为 **28**（2026-09-29 实测 = 28 ✓）。⚠️ **不要全文件计数** —— 本文件另有 §15.1.2（20 行）／§15.5／§15.7（12 行）等同样以「两位序号 + 反引号」开头的编号表，**全文件计数实测 = 54**，属误报（旧注写的"只多出 §十四 一行"已过期）；⚠️ §2.2 的**速查表**首列是名称、不匹配该正则，不影响计数 |

---

## 三、最容易搞错的「复用」与「调用点」（先读这一节再动手）

> 这一节是本项目历史返工最多的一类问题（见技能 `design-doc-dev-ready` 的"最高价值动作"）。
> 每条都**已打开源码确认**，写成「误解 / 事实 / 正确做法」三列。

| # | 可能的误解 | 事实（含 `file:line`） | 正确做法 |
|---|---|---|---|
| 1 | 「暗角/颗粒/扫描线已经有现成实现，直接复用」 | ❌ **没有共享实现**。全项目 `grep vignette\|grain` 只命中 3 处：`WorldRenderer.kt:1446`、`BatchFourRenderers.kt:944/985`、`VintageTvRenderer.kt:600` —— **三份各写各的**，参数与实现都不同（World 用 `Brush.radialGradient`，Vintage 用 `Brush.radialGradient` + 按 `w` 缓存，齿轮用 `Bitmap` tile） | 新建 `fx/OverlayFx`（§五），把三者统一；**旧实现保留到该效果被改造时再替换**，不要一次性删 |
| 2 | 「`RendererSwapper` 有 600ms 交叉淡入，效果切换可以顺便启用」 | ⚠️ **能力存在但 UI 侧硬编码关闭**：`RendererSwapper.sync(theme, quality, crossfade, …)`（`RendererSwapper.kt:79-117`）确实实现了 crossfade，但唯一调用点 `VisualizerStage.kt:178` 传的是 **`false`**（硬切） | 若要启用，改 `VisualizerStage.kt:178` 的第 3 个实参；**但这与"用户按键后需要即时反馈"的既有裁决冲突** → 列入 §十三 裁决项 4，不要擅自改 |
| 3 | 「给 `VisualQuality` 加一个 `fxLevel` 字段就行」 | ⚠️ 会连锁：`VisualQuality` 是 7 参枚举（`AppSettings.kt:243-255`），`VisualizerThemeTest.kt:158` 有 `assertEquals(32, VisualQuality.LOW.barCount)` 这类**按名访问**的断言；新增字段本身不破测试，但会让"档位语义"分散在两处 | **不新增字段**。用 `FxBudget.of(quality)` 从既有字段**推导**（`allowFramebuffer` + `maxParticles` + `glowLayers` 已足够区分三档），见 §5.1 |
| 4 | 「效果计数改了要同步 `VisualizerThemeTest`」 | ✅ **本轮不需要改** —— 本方案**不增删枚举成员**，28 项不变；`VisualizerThemeTest.kt:51/57/58/89/90/96/103` 的 6 处计数断言保持原值 | 若某套效果的**显示名**变了（`displayName`），需同步 `VisualizerThemeTest.kt:103` 的 `names.distinct()` 相关断言与 `values/strings.xml` + `values-en/strings.xml` |
| 5 | 「`RenderContext.update(...)` 加个 `fxLevel` 参数」 | ⛔ **代价高**：`update()` 有 16 个位置参数、3 个调用点（`RenderContext.kt:47-51` KDoc 明确写了这是刻意设计）；加参数要动全部调用点 | 渲染器内部 `val fx = FxBudget.of(ctx.quality)` 即可，**零调用点改动** |
| 6 | 「`PhotoRenderContractScanTest` 会扫到新文件报错」 | ✅ 它只扫 `PhotoGeometry.update` 的实参个数（`PhotoRenderContractScanTest.kt:70-88`），新增 `fx/` 文件**不会被误判** | 但如果你**新写**源码扫描型门禁，必须按 §八 的自证要求写（6 条，含空转断言） |
| 7 | 「加后处理就是把整个画面画到离屏再处理」 | ⚠️ **离屏是电视上的填充率杀手**：`MilkdropRenderer.kt:28-31` 的 KDoc 明写"每帧 2 次全屏 drawImage，是 TV 填充率杀手"，因此**仅 HIGH 档**且降采样到 720p | 分两级：**叠加层（零离屏）默认全档**；**离屏层仅 HIGH**（§5.3）。⛔ 不要把离屏做成所有效果的默认路径 |
| 8 | 「`BlendMode.Plus` 叠加越多越亮，越多越好看」 | ⚠️ 会**过曝成白块**：`BatchThreeRenderers.kt:393-396`、`MoleculeRenderer.kt:513-515`、`ParticleRenderers.kt:111` 大量 `Plus` | 叠加层数必须与**曝光上限**挂钩：新增 `fx/Shading2D.toneMap()`（Reinhard）或至少把 `Plus` 层 alpha 按层数归一（§5.4） |

---

## 四、共性缺口诊断（12 条 —— 这一节是"看起来假"的根因清单）

> G1–G10 是**观感**根因；**G11–G12 是"性能伪装成观感问题"的两条** —— 它们让效果为了
> 省开销而主动放弃质量（粗步长采样、单档平涂），是本轮**零成本提质**的主战场。

> 每条给出：**证据**（`file:line`）／**现象**（用户看到什么）／**业界做法**／**参考**（§十四 编号）。
> 这 10 条与"某套效果"无关，是**全库共性**；§六 的逐效果清单本质上是这 10 条在具体效果上的落法。

### G1 · 没有共享后处理层 ⇒ 25/28 套"贴在纯色底上"

- **证据**：`grep -rn "vignette\|grain" app/src/main/java/com/nasmusic/tv/visualizer/` 仅 3 个文件命中。
- **现象**：画面四角与中心一样亮、没有颗粒、没有色差 ⇒ 像"矢量插画"而不是"影像"。
  这是最容易被感知为"廉价"的单点。
- **业界做法**：后处理链固定顺序 —— 色调映射 → 色差 → 暗角 → 颗粒 → 扫描线。
  暗角用 `radialGradient` 一次 `drawRect`；颗粒用**预生成 tile + `TileMode.Repeated`**。
- **参考**：#2（Butterchurn 的 `compositeShader` 链）、#5（libretro CRT shaders）。

### G2 · 描边/填充是单色平涂 ⇒ 无材质感（金属/绸缎/玻璃全都做不出来）

- **证据**：
  - `DnaRenderer.kt:605/611` —— 骨架按深度分 4 桶线宽/alpha，但**颜色恒定**；
  - `BatchFourRenderers.kt:1238` —— 齿轮描边单色；
  - `BasicRenderers.kt:200-212` —— 频谱条 `drawLine` 单色（`barColor` 只随 hue 变，不随角度变）；
  - `BatchTwoRenderers.kt:425` —— 行星 `drawCircle` 纯色。
- **现象**：所有圆形/线条看起来像"塑料贴纸"，没有厚度、没有反射。
- **业界做法**：2D 里做材质只需要三样东西 ——
  ① **沿法线的明暗**（把物体边缘按"朝向光源与否"分成亮侧/暗侧）；
  ② **内部渐变**（`Brush.linearGradient` 沿光照方向，中心亮→边缘暗）；
  ③ **边缘光 rim**（背面边缘一圈亮线，模拟环境反射）。
  这三样都**不需要离屏、不需要 shader**，`drawPath` + `Brush` 即可。
- **参考**：#1（projectM 的 `specular` / `edge glow` 语义）、#3（GLava 用 `smoothstep` 做边缘衰减）。

### G3 · 高光位置固定，不随光源/主体朝向 ⇒ 塑料感

- **证据**：`BatchTwoRenderers.kt:449-454`（行星高光恒在"斜上"）、`DnaRenderer.kt:665`（前景节点高光恒在"斜上"）、
  `BatchFourRenderers.kt:1282-1287`（轴心高光是同心硬边圆）。
- **现象**：物体转动/运动时高光"钉"在同一个屏幕位置，大脑立刻判定为假。
- **业界做法**：定义**唯一主光方向**（建议 `315°`，即左上），高光位置 = 主体中心 +
  `rotate(光向, 主体朝向)` × `r`；specular 强度用 `pow(max(0, dot(reflect, view)), shininess)`，
  2D 里退化为"高光点沿法线偏移 + 指数衰减"。
- **参考**：#1、#4（audio-reactive-shaders 的光照参数化）。

### G4 · 无 AO / 接触阴影 ⇒ 主体悬浮

- **证据**：齿轮组、行星、DNA 节点、折纸三角、山峦层**全都没有任何投影**。
- **现象**：物体像"贴"在背景上，缺少空间关系。
- **业界做法**：每个主体画 1 层偏移暗椭圆（偏移 = `r × 0.12`，`alpha 0.22–0.32`），
  成本 = **每个主体 +1 次 `drawOval`**。折纸/山峦这类"层叠"效果收益最大。
- **参考**：#2（MilkDrop 预设里的 `shadow` 项）。

### G5 · 光晕靠"多层同心描边"模拟 ⇒ 廉价且过曝

- **证据**：`BasicRenderers.kt:198-212`（`glowLayers` 次加宽 `drawLine`）、
  `MoleculeRenderer.kt:513-515`（3 层同心 `drawCircle`）、
  `BatchTwoRenderers.kt:348-356`（太阳 3 层同心 + 日冕）。
- **现象**：光晕边缘能看到**台阶**（同心圆的轮廓），且多层同色叠加后中心过曝发白。
- **业界做法**：光晕的物理本质是**高斯衰减**。2D 里的正解是
  **1 次 `drawCircle` + `Brush.radialGradient(中心亮 → 0.6r 处透明)`**，
  比 N 层同心圆**更平滑、更省 draw 调用**（3 次 → 1 次）。
- **参考**：#3、#8（Kawase 降采样 bloom 的衰减形状）。

### G6 · 没有 bloom 后处理 ⇒ 亮部不能"溢出"

- **证据**：全项目无屏幕级离屏合成；`VisualQuality.allowFramebuffer`（`AppSettings.kt:250`）
  只被 `MilkdropRenderer` 使用。
- **现象**：高光"亮到某个程度就停住"，没有真实光源那种"溢出到周围"的辉光。
- **业界做法**：亮度阈值提取 → 多级降采样模糊（Kawase 双滤波）→ 加法合成。
  **本项目的代价**：至少 3 张离屏缓冲（原图 + 2 级降采样），按 `MilkdropRenderer` 的
  实测经验，**仅 HIGH 档可开**，且必须降采样。
- **参考**：#8、#2。

### G7 · 抗锯齿 / 亚像素：硬边 `drawLine` / `drawRect`

- **证据**：`VintageTvRenderer.kt:331-352`（**240 条**扫描线 `drawLine`）、
  `BatchThreeRenderers.kt:504-513`（阶梯方块硬边 `drawRect`）、
  `AdvancedRenderers.kt:242`（网格 `Stroke(width = 1f)` 单色）。
- **现象**：1080p 下能看到锯齿与**摩尔纹**（扫描线尤其明显）；细线在缩放时闪烁。
- **业界做法**：① 周期性重复图案（扫描线/网格/颗粒）一律改**预生成 tile + 平铺**，
  一次 `drawImage` 替代 N 次 `drawLine` —— **既消除摩尔纹又大幅降 draw 调用**；
  ② 单像素线用 `Stroke(width = 1f)` 时注意设备像素对齐；③ 方块类改
  `drawRoundRect(radius = 1px)` 或加 0.5px 描边以柔化边缘。
- **参考**：#5、#6。

### G8 · 无色彩管理 / 无色调映射 ⇒ `Plus` 叠加过曝成白块

- **证据**：`BatchThreeRenderers.kt:393-396`、`MoleculeRenderer.kt:513-515`、
  `ParticleRenderers.kt:111`、`AdvancedRenderers.kt:244`（`PlasmaFlow`）。
- **现象**：鼓点密集处一片死白，丢掉了所有细节与颜色。
- **业界做法**：在合成阶段做 **Reinhard 色调映射**（`c' = c / (1 + c)`）或
  **ACES 近似**；轻量做法是把 `Plus` 层的 `alpha` 与"同时叠加的层数"反比归一。
- **参考**：#8。

### G9 · 背景层次单一 ⇒ 缺纵深

- **证据**：`DnaRenderer.kt:577`（纯色 `drawRect`）、`AdvancedRenderers.kt:300-336`（涟漪无底）、
  `BasicRenderers.kt:298-326`（山峦无底）、`EcgWaveRenderer.kt`（网格底）。
- **现象**：主体"悬"在均匀色块上，没有"空间"。
- **业界做法**：统一**三段式背景** ——
  ① 径向纵深渐变（中心比边缘亮 8–14%，色相偏冷 6–12°）；
  ② 程序化纹理 tile（颗粒/拉丝/纸纹）；
  ③ 远景元素（星野/尘埃/微粒）。
  三段全部可缓存，`draw` 内成本 = 1 次 `drawRect` + 1 次平铺 + 1 次 `Path`。
- **参考**：#1、#2。

### G10 · 音频驱动维度贫乏 + 平滑参数各写各的

- **证据**：
  - `AudioFrame`（`AudioFrame.kt:16-58`）暴露 `spectrum / waveform / bass / bassRaw / mid / treble /
    energy / sectionEnergy / beat / pulse / bpm` **共 11 个信号**，但多数效果只用 3–4 个；
    `sectionEnergy`（8s 滑动均值，**段落呼吸**）在 28 套里几乎未用；
    `bassRaw`（未归一化，**保留鼓点强弱差异**）只在心跳用过；
  - 每个效果自己写 EMA：`RadarGridRenderer.kt:88-89`、`OrigamiPolyRenderer.kt:302-303`、
    `BatchThreeRenderers.kt`（同上）、`WorldRenderer` 分频段 EMA —— **系数各不相同、无统一语义**。
- **现象**：所有效果都是"每 4 拍一个循环"，听不出**段落结构**（主歌/副歌/桥段）；
  同一首歌里各效果的"灵敏度"不一致，切换效果时观感跳变。
- **业界做法**：① 统一 `AudioSmoother`（**attack / release 分离**，鼓点用快 attack、
  段落用慢 release）；② 统一"频段 → 视觉参数"映射表（§7.2）；
  ③ 用 `sectionEnergy` 驱动**段落级**变化（配色温度、密度、镜头距离），
  用 `bpm` 驱动**周期性**运动（旋转基线速度），把"拍"与"段"两个时间尺度分开。
- **参考**：#2（MilkDrop 的 `per_frame` / `per_pixel` 公式体系）、#4。

### G11 · 离屏采样用逐像素 `getPixel()`（把"能用"当成了"唯一可用"）

- **证据**：
  - `LyricsDotMatrixRenderer.kt:277`（第一遍数每行有效像素）+ `:300`（第二遍按配额抽取）
    —— **两遍全图** `b.getPixel(x, y)`；
  - `ParticleRenderers.kt:181` —— 单遍全图 `bmp.getPixel(x, y)`，步长 3；
  - 两者的 KDoc 都写着"只能退回 `Bitmap.getPixel()` 全图扫描"
    （`ParticleRenderers.kt:129-131`），源头是
    `docs/archive/music-visualizer-dev-plan.md:874-878` 的表格把 `getPixel()` 标为
    "**✅ 唯一可用**"。
- **真相**：`Bitmap.getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height)`
  **自 API 1 就存在**（本机 `android-34/android.jar` `javap` 实测；`docs/archive/...:876` 的
  "唯一可用"结论**是错的**）。当时被否掉的是 `ImageBitmap.readPixels()`（API 29）与
  `Path.getSegment()`（API 24），**并不等于"只能用 `getPixel()`"**。
  本项目自己就有正确用法的先例：`backend/photo/YuNetFaceDetector.kt:239` `scaled.getPixels(...)`。
- **代价**：每次 `getPixel()` 都是一次 **JNI 调用 + 边界检查 + 像素格式转换**（≈ 0.5–1 µs）；
  `getPixels()` 一次调用搬整行/整图。
  - E23 歌词点阵：`2 × ceil(bmpW/step) × ceil(bmpH/step)` ≈ **71,000 次 JNI**（1080p、`step=3` 典型值）
    ⇒ **35–70 ms**，且 `sampleLine()` 是在 `draw()` 里调的（`:501`）⇒ **每次歌词换行卡 2–4 帧**；
  - E19 粒子文字：`74 × 220` ≈ **16,280 次 JNI** ⇒ 8–20 ms（在 `draw` 内，`:210`）。
- **做法**：**逐行批读** `getPixels(IntArray(bmpW), 0, bmpW, 0, y, bmpW, 1)`，
  然后在 `IntArray` 上做纯数组扫描。内存只要 **`IntArray(bmpW)` ≈ 7 KB**，
  JNI 次数从"每像素一次"降到 **"每行一次"**（E23 歌词点阵：71,000 → **116**，−99.8%）。
  ⛔ 不要整图读成 `IntArray(bmpW*bmpH)`（1080p 下 ≈ 1.4 MB 常驻）。
- **参考**：本仓库既有正确用法 `YuNetFaceDetector.kt:239`；§15.4-A21/A22。

### G12 · `Path.addOval(Rect)` / `addRoundRect(RoundRect)` 每帧分配形状对象

- **证据**：全仓库 **8 处**在用（`grep -rn "addOval" app/src/main/java/com/nasmusic/tv/visualizer/renderers`）：
  `AdvancedRenderers.kt:75`（E11 星系，**1440 点**）、`:77`、`:252`（E13 频谱山）、`:566`（E17 星座）、
  `BasicRenderers.kt:113`（E01 Bloom）、`:219`（E05 频谱环）、
  `LyricsDotMatrixRenderer.kt:698` + `:703`（E23 歌词点阵，**每点 2 个**）。
- **真相**：`androidx.compose.ui.geometry.Rect` 是 **`data class`**
  （`ui-geometry` `Rect.kt:31-32`）⇒ `Rect(...)` **每次构造都分配对象**（≈ 32 B）；
  `RoundRect` 是 `value class` 但包着 `FloatArray` ⇒ 同样分配。
  ⛔ 而 `Offset` 是 **`value class` over `Long`**（`Offset.kt:61`）⇒ `Offset(x, y)` **不分配**。
- **代价**：E23 每帧最多 `2 × 7200 = 14,400` 个 `Rect` ⇒ **≈ 460 KB/帧 ⇒ 27 MB/s 垃圾**
  （`cap = 3600` × 2 行）；E11 每帧 1440 个。这是"每帧零分配"红线的**实质违规**。
- **现象**：不是崩溃，而是**周期性 GC 抖动** —— 弱 GPU + 小堆的电视上表现为
  "每隔几秒卡一下"，很容易被误判成"GPU 不行"。
- **本项目已知但未贯彻**：`photo/transitions/IrisTransitions.kt:26-28` 的注释已经写清
  「圆用 **64 边形**逼近，而不是 `Path.addOval(Rect)` —— Compose 的 `Rect` 是不可变 data class，
  每帧构造一个来算 `addOval` 会破坏『绘制路径零分配』的约束」，
  **但只有那一个文件照做了**，渲染器侧 8 处仍照旧。
- **做法（零分配批量圆点，§15.3.2 #15）**：先建**一个**"点"路径 `dotPath`
  （`moveTo` + 4 段 `cubicTo` 画圆，**全 float 参数、零分配**），
  再用 `Path.addPath(dotPath, Offset(x, y))` 把每个点**平移追加**到目标 Path
  （`Path.kt:205`；`Offset` 不分配），最后 **1 次 `drawPath`**。
  ⇒ `Rect` 分配 **14,400 → 0**，`draw` 调用数不变。
- **参考**：§15.4-A23/A24/A25；`IrisTransitions.kt:26-28`。

---

## 五、升级架构：三层工具箱（一次建设，28 套受益）

### 5.1 为什么是「工具箱」而不是「逐套改」

25 套缺后处理、20 套缺光照模型。如果逐套实现：

- 会写出 25 份参数不一致的暗角、20 份语义不同的高光 ⇒ **观感不统一**（用户会觉得"风格乱"）；
- 每套各自缓存纹理 ⇒ 内存与 `recycle` 生命周期失控（本项目已有两次 `Bitmap` 泄漏教训：
  `MilkdropRenderer.kt:53-56`、`AdvancedRenderers.kt:146-150`）；
- 无法整体按档位降级（LOW 档要逐个关，必然漏）。

所以先建工具箱（§5.2），再逐套接入（§六）。**工具箱本身不含任何效果逻辑**。

### 5.2 新增文件清单（`app/src/main/java/com/nasmusic/tv/visualizer/fx/`）

| 文件 | 职责 | 预估行数 | 是否含离屏 |
|---|---|---|---|
| `FxLevel.kt` | 档位枚举 `FxLevel{OFF,LITE,FULL}` + `FxBudget.of(quality)` 推导 | ~40 | 否 |
| `OverlayFx.kt` | **零离屏**叠加后处理：暗角 / 颗粒 / 扫描线 / 纹理平铺 / 曝光归一 | ~260 | 否 |
| `Shading2D.kt` | 2D 光照与材质：主光常量、`specular`、`rimLight`、`bevelStroke`、`contactShadow`、`toneMap` | ~230 | 否 |
| `ProceduralTexture.kt` | 程序化纹理生成与缓存：grain tile / scanline tile / brushed metal / paper / star field | ~300 | 否（生成期） |
| `AudioSmoother.kt` | 统一的 attack/release 包络平滑器（替代各效果手写 EMA） | ~90 | 否 |
| `OffscreenFx.kt` | **离屏**层（仅 `FxLevel.FULL`）：bloom（三级降采样）/ 真通道色差 / 径向模糊 | ~340 | **是** |

> ⛔ 6 个文件**全部新增**，**不改任何既有渲染器的签名**；既有渲染器只在 `draw` 末尾
> **追加一行** `OverlayFx.draw(...)`（或按需调 `Shading2D`）。

### 5.3 关键接口签名（可直接粘贴）

```kotlin
// ─────────────────────────────────────────────────────────────
// fx/FxLevel.kt
// ─────────────────────────────────────────────────────────────
package com.nasmusic.tv.visualizer.fx

import com.nasmusic.tv.data.model.VisualQuality

/**
 * 后处理档位。**不从 VisualQuality 新增字段推导以外的任何地方读取**。
 *
 * ⛔ 为什么不用 `VisualQuality` 新增字段：该枚举有 7 个构造参数、被
 * `VisualizerThemeTest.kt:158` 等按名断言，且 `RenderContext.update(...)` 有 16 个
 * 位置参数与 3 个调用点（`RenderContext.kt:47-51`）—— 加字段的连锁代价远大于收益。
 * 现有字段已足够区分三档：LOW(`maxParticles==0`,`glowLayers==1`,`allowFramebuffer==false`)、
 * MEDIUM(150,2,false)、HIGH(350,3,true)。
 */
enum class FxLevel { OFF, LITE, FULL }

object FxBudget {
    fun of(quality: VisualQuality): FxLevel = when {
        quality.allowFramebuffer -> FxLevel.FULL          // HIGH
        quality.glowLayers >= 2 -> FxLevel.LITE           // MEDIUM
        else -> FxLevel.OFF                               // LOW
    }
}
```

```kotlin
// ─────────────────────────────────────────────────────────────
// fx/OverlayFx.kt（节选：对外只暴露这 6 个方法）
// ─────────────────────────────────────────────────────────────
package com.nasmusic.tv.visualizer.fx

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.visualizer.RenderContext

/**
 * **零离屏**叠加式后处理。所有方法都是 `DrawScope` 扩展，可在任意渲染器 `draw` 末尾调用。
 *
 * 三条约束：
 *  ① 单次调用 ≤ 3 个 draw 指令（暗角 1、颗粒 1、扫描线 1）；
 *  ② 纹理 tile 在首次调用时生成并缓存，`onExit` 由舞台统一释放；
 *  ③ 档位 `OFF` 时**全部方法直接 return**（LOW 档零成本）。
 */
object OverlayFx {

    /** 暗角：径向渐变，中心透明 → 边缘压暗 + 轻微偏冷。`strength` 建议 0.32–0.48 */
    fun DrawScope.drawVignette(ctx: RenderContext, strength: Float = 0.42f, coolShiftDeg: Float = 8f)

    /** 颗粒：预生成 8 张 128×128 tile 循环取用（用 `seq` 索引，保证确定性且逐帧变化） */
    fun DrawScope.drawGrain(ctx: RenderContext, seq: Long, intensity: Float = 0.035f)

    /** 扫描线：预生成 1×`period` 高度 tile，`TileMode.Repeated` 平铺（替代 N 次 drawLine） */
    fun DrawScope.drawScanlines(ctx: RenderContext, periodPx: Int = 3, dark: Float = 0.16f)

    /** 通用纹理平铺：把 [ProceduralTexture] 的 tile 以给定 alpha 铺满画布 */
    fun DrawScope.drawTexture(
        ctx: RenderContext,
        textureId: Int,
        alpha: Float,
        blendMode: androidx.compose.ui.graphics.BlendMode = androidx.compose.ui.graphics.BlendMode.SrcOver
    )

    /** 曝光归一：`Plus` 叠加层数多时压低整体亮度，避免死白（Reinhard 近似，见 Shading2D.toneMap） */
    fun DrawScope.drawExposureCompensation(ctx: RenderContext, accumulatedLayers: Int)

    /** 释放全部 tile（由 `VisualizerStage` 在舞台离开时调用一次） */
    fun release()
}
```

```kotlin
// ─────────────────────────────────────────────────────────────
// fx/Shading2D.kt（节选）
// ─────────────────────────────────────────────────────────────
package com.nasmusic.tv.visualizer.fx

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** 2D 光照与材质工具。**纯计算 + 少量 draw，全部零分配**。 */
object Shading2D {

    /** 全项目唯一主光方向：315°（左上）。⛔ 所有高光必须由它推导，不得各写各的 */
    const val LIGHT_ANGLE_DEG = 315f

    /** 主光单位向量（`cos/sin` 预算一次） */
    val lightDir: Offset get() = Offset(-0.7071f, -0.7071f)

    /**
     * 沿法线的明暗：返回"面向光源程度" 0..1。
     * @param normalAngleDeg 该元素表面法线方向（如圆环上某点的外法线 = 该点极角 + 90°）
     */
    fun lambert(normalAngleDeg: Float): Float

    /** 镜面高光强度：2D 退化为"法线与光向夹角 → 指数衰减" */
    fun specular(normalAngleDeg: Float, shininess: Float = 24f): Float

    /** 边缘光强度：法线背向光源时最强（模拟环境反射的轮廓亮线） */
    fun rim(normalAngleDeg: Float): Float

    /**
     * 双层描边（"倒角"效果）：亮侧画一条高光描边、暗侧画一条暗描边。
     * 成本 = 2 次 `drawPath`，但观感从"平涂"变成"有厚度的实体"。
     * ⛔ 只对**闭合轮廓**有意义（齿轮齿廓 / 折纸三角 / 节点 / 行星）。
     */
    fun DrawScope.bevelStroke(
        path: androidx.compose.ui.graphics.Path,
        base: Color,
        lightAlpha: Float = 0.55f,
        darkAlpha: Float = 0.45f,
        width: Float = 1.6f
    )

    /** 接触阴影：主体下方偏移一层暗椭圆（`offset = r × 0.12`，`alpha 0.22–0.32`） */
    fun DrawScope.contactShadow(
        center: Offset, radius: Float, dropK: Float = 0.12f, alpha: Float = 0.28f
    )

    /**
     * ⛔ **不要**做成"任意调用都新建 Brush"的 API —— `Brush.radialGradient` 的首参是
     * `vararg colorStops: Pair<Float, Color>`（`Brush.kt:296-306`），每帧新建会分配
     * Pair + 内部 `List<Color>`/`List<Float>`，违反零分配红线。
     *
     * 正确形态（**以 §15.2.4 的签名为准**）：
     *  ① **移动主体**（行星 / 原子 / 齿轮轴心）→ 用 `ProceduralTexture.sphereSprite(color)`
     *     预烘球面贴图（按颜色缓存），draw 期只 `drawImage` ⇒ 零分配；
     *  ② **静态主体**（太阳 / 中心光源）→ `shadeBrushCached(key, ...)`，
     *     按 `(w, h)` 缓存，与 `RadarGridRenderer.ensureBrush`（`BatchThreeRenderers.kt:64-75`）同范式。
     */
    fun shadeBrushCached(
        key: Long, center: Offset, radius: Float, base: Color, contrast: Float = 0.42f
    ): Brush

    /** Reinhard 色调映射：`c / (1 + c)`。⛔ `Plus` 叠加层数 ≥ 3 时必须调用 */
    fun toneMap(c: Float): Float = c / (1f + c)
}
```

```kotlin
// ─────────────────────────────────────────────────────────────
// fx/AudioSmoother.kt（节选）
// ─────────────────────────────────────────────────────────────
package com.nasmusic.tv.visualizer.fx

/**
 * 统一频段包络平滑器 —— 替代各效果手写的 EMA（`RadarGridRenderer.kt:88-89`、
 * `OrigamiPolyRenderer.kt:302-303` 等，系数互不相同且无统一语义）。
 *
 * **attack / release 分离**是关键：鼓点要**快 attack**（跟得上瞬态）、
 * 段落要**慢 release**（不会一帧掉下去）。
 */
class AudioSmoother(
    private val attack: Float = 0.35f,   // 上升系数（大 = 跟得快）
    private val release: Float = 0.06f   // 下降系数（小 = 落得慢）
) {
    var value: Float = 0f
        private set

    /** 每帧推进一次；零分配 */
    fun update(target: Float): Float {
        val k = if (target >= value) attack else release
        value += (target - value) * k
        return value
    }

    fun reset() { value = 0f }
}
```

```kotlin
// ─────────────────────────────────────────────────────────────
// fx/OffscreenFx.kt（节选 —— 仅 FxLevel.FULL 使用，见 §5.4）
// ─────────────────────────────────────────────────────────────
package com.nasmusic.tv.visualizer.fx

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 离屏后处理（**仅 HIGH 档**）。代价参照 `MilkdropRenderer.kt:28-31` 的实测结论：
 * 全屏离屏回绘是 TV 填充率杀手 ⇒ 必须降采样，且必须 `onExit` 显式 `recycle`。
 *
 * 不变式（与 `MilkdropRenderer` 同）：`currCanvas` 恒包装 `curr`，交换时同步交换。
 */
class OffscreenFx(private val downscale: Int = 3) {
    private var a: ImageBitmap? = null
    private var b: ImageBitmap? = null
    private var aCanvas: Canvas? = null
    private var bCanvas: Canvas? = null

    fun ensure(w: Int, h: Int)
    fun onExit()                                    // 显式 recycle 全部缓冲
    fun DrawScope.drawBloom(threshold: Float = 0.72f, strength: Float = 0.55f, radiusK: Float = 0.035f)
    fun DrawScope.drawChroma(offsetPx: Float, alpha: Float = 0.55f)   // 真通道分离
}
```

### 5.4 三级降级策略（性能预算）

| 档位 | `FxLevel` | 可用能力 | 单帧额外成本（1080p） | 适用设备 |
|---|---|---|---|---|
| LOW | `OFF` | **全部关闭**。渲染器内 `OverlayFx` 直接 return | 0 | 老电视（Android 5.1 / 弱 GPU） |
| MEDIUM | `LITE` | 暗角 + 颗粒 + 扫描线 + 纹理平铺（**全零离屏**） | 约 3 次 draw 调用；无额外填充率 | 主流电视 / 手机 |
| HIGH | `FULL` | `LITE` 全部 + 离屏 bloom + 真色差（降采样 1/3） | 约 2 张 1/3 分辨率离屏 + 4 次 `drawImage` | 手机 / 较强电视 |

**接线方式**（零调用点改动）：

```kotlin
// 任意渲染器 draw() 末尾
val fx = FxBudget.of(ctx.quality)
if (fx != FxLevel.OFF) {
    OverlayFx.drawVignette(ctx, strength = 0.42f)
    OverlayFx.drawGrain(ctx, seq = frame.seq, intensity = 0.035f)
}
```

⚠️ **不做的事**（明确排除，避免实现期跑偏）：

- ⛔ 不做"每套效果各自决定要不要 bloom" —— bloom 是**屏幕级**的，应在
  `VisualizerStage` 层做一次（若采纳 §十三 裁决项 2），而不是 28 套各做一次。
- ⛔ 不引入 AGSL `RuntimeShader`（`android.graphics.RuntimeShader` 需 **API 33+**，
  本项目 `minSdk 22`，只能作为**未来可选加速路径**，见 §十四 #7）。
- ⛔ 不引入任何新依赖（`build.gradle.kts` 零改动）。
- ⛔ 不改 `RenderContext.update(...)` 的签名（见 §三 #5）。

---

## 六、逐效果优化清单

> 每条格式固定：**现状**（`file:line`）／**质感问题**（编号，均可在源码上指认）／
> **优化**（编号，每条 = 一个可独立实现的技法 + 参数）／**验收**（可判定的观感判据）。
> 参数凡写数字的，一律进 §七 参数表；凡引用开源实现的，编号对应 §十四。

### 批次 A · 重做级（11 套 · 缺口 ★★★）

#### A1 · E03 隧道穿越 `TUNNEL_FLY`（`BasicRenderers.kt:64-125`）

- **现状**：24 个同心环沿 Z 轴迎面飞来（`offset += 1.2f + bass*6f`，`:76`），环用
  `drawCircle + Stroke` 单色描边（`:96-102`），每 3 环在环上撒 24 个频谱驱动小点
  （`:104-116`），小点按 z 深度分 3 桶合并成 3 条 `Path`（`:113`）。
- **质感问题**：
  ① **环是单色等亮度**（`:96-102`）—— 没有"越近越亮、越远越淡入雾"的纵深；
  ② **"合并 3 桶"丢掉了逐点 alpha**（`:119-121` 用桶中心 alpha 近似），远处的点与近处一样实；
  ③ 环上小点是**圆形点**（`:113`），不是"隧道壁灯"，无辉光；
  ④ 无中心光源、无雾、无速度线 ⇒ 没有"在管道里飞"的速度感。
- **优化**：
  1. **环的纵深色**：环颜色按 `z` 插值 —— 近端 = `palette.accent` 提亮 20%，
     远端 = 背景色（`darken(accent, 0.15f)`），用 `lerp` 在 `draw` 内算（零分配）；
     并在 `fade < 0.35` 时把环宽收到 `1f`，形成"雾化"。
  2. **恢复逐点 alpha**：把 3 桶改为 **6 桶**（`(fade * 6f).toInt()`），桶数增加但仍是
     6 次 `drawPath`（远少于 192 次 `drawCircle`）；桶中心 alpha 从"线性"改为
     **`fade²`**（`fade*fade`），让远端真正淡下去。
  3. **壁灯辉光**：每个小点改为"实心点 + 1 层径向渐变光晕"——
     光晕用 `Shading2D.shadeBrush`（1 次 `drawCircle(brush=)`），
     ⛔ 不要用同心圆（那是 G5 的错误做法）。
  4. **速度线**：沿半径方向在环外画 8 条极淡的径向短线（`alpha = 0.06 + bass*0.10`），
     长度随 `bass`，方向固定不随环转 —— 提供"气流"参照。
  5. **中心光源**：`cx,cy` 处加 1 层 `radialGradient` 光斑（半径 `baseR*0.5`，`alpha 0.18`），
     颜色随 `pulse` 提亮 —— 隧道尽头的光。
  6. **收尾后处理**：`drawVignette(0.46f)` + `drawGrain(seq, 0.030f)`。
- **验收**：静止时近端环明显亮于远端环（肉眼可辨 3 档以上）；远端环边缘**看不出同心圆台阶**；
  电视上 1080p 播放时无环"闪烁"。

#### A2 · E05 圆形频谱环 `CIRCULAR_RING`（`BasicRenderers.kt:137-281`）· 默认效果

- **现状**：`barCount` 根条从 `rStart` 向外辐射（`:177-213`），hue 随角度渐变（`:188-191`），
  超过外环的条"刺破"外圈并改用更细更亮的高光段（`:194-205`），
  峰值帽 8 桶合并（`:215-230`），外圈 8 段 `drawArc` 拼的渐变环（`:257-280`），
  中心 3 层圆盘（`:246-253`）。
- **质感问题**：
  ① **条体单色**（`:190` 只有 `barColor` + `highlight` 两色，且 `highlight` 只用在刺破段）
  ⇒ 条没有"圆柱"感，像扁片；
  ② **"光晕"是同色加宽描边**（`:198-202` 的 `glowLayers` 循环）—— 这是 **G5 的假光晕**，
     边缘是硬的，且 3 层叠加在鼓点处过曝；
  ③ 外圈环由 **8 段 `drawArc` 拼成**（`:257-280`）⇒ 段间有色阶断层（8 个台阶可见）；
  ④ 中心圆盘 3 层同心圆（`:246-253`）⇒ 同心台阶；
  ⑤ 峰值帽是 2.5px 硬圆点（`:219-222`）。
- **优化**：
  1. **条体圆柱化**：把每根条的 `drawLine` 换成**两次 `drawLine` 错开 0.35×wdt**：
     先画暗侧（`hsl(hue, sat, lit-0.22)`，偏移 −0.35×wdt 沿切向），
     再画亮侧（`hsl(hue, sat, lit+0.20)`，偏移 +0.35×wdt）——
     等效于"沿法线的明暗"，2 次 draw 换圆柱感（G2 ①）。
  2. **真光晕替换假光晕**：`glowLayers` 循环（`:198-202` / `:207-211`）整段删除，
     改为每根条 1 次 `drawLine`，`strokeWidth = wdt * 2.6f`，`alpha = 0.10 + pulse*0.18`，
     ⛔ 且**只在 `FxLevel != OFF` 时画**（LOW 档省掉）。
  3. **外圈环改径向渐变环**：删掉 8 段 `drawArc`，改为 1 次
     `drawCircle(brush = sweepGradient(...))`（`Brush.sweepGradient` 已在
     `BatchThreeRenderers.kt:68-74` 有可用范式）⇒ 消除段间断层，且 8 次 draw → 1 次。
  4. **中心圆盘改径向渐变**：3 层同心圆 → 1 次 `drawCircle(brush = Shading2D.shadeBrush(...))`，
     中心亮 → 0.85r 透明，配 1 条 0.5px 的亮边（rim light）。
  5. **峰值帽加辉光**：2.5px 圆点保留，但**在其下方叠 1 层 6px 的同色 `alpha 0.18` 圆**
     （并入已有 8 桶 `Path`，零额外 draw 调用）。
  6. **后处理**：`drawVignette(0.40f)` + `drawGrain(seq, 0.028f)`。
- **验收**：单根条在放大截图上能看出**亮侧与暗侧**；外圈环**无 8 段接缝**；
  连续鼓点时中心**不出现死白**（对比改造前截图）。

#### A3 · E07 频率山峦 `FREQUENCY_MOUNTAIN`（`BasicRenderers.kt:292-327`）

- **现状**：5 层半透明面积图叠加（`:307-325`），每层 `moveTo/lineTo` 折线 + 收底闭合，
  `alpha 0.16` 填充 + `alpha 0.14` 描边，双色（`accent`/`secondary`）交替。
- **质感问题**：
  ① **无底色**（`:298-326` 全程没有背景）⇒ 山峦悬在纯黑上，没有"天空/远山"的纵深；
  ② 折线是 `lineTo` 硬折（`:318`）⇒ 频谱抖动时山脊呈"锯齿"，不是山；
  ③ 层与层之间只有 alpha 差，**无边缘辉光**（`:323-324`）⇒ 像 5 张半透明纸片；
  ④ 5 层用同一 `amp` 缩放（`:311` 仅 `1-t*0.25`）⇒ 层间几乎平行，没有"近山高远山低"的透视。
- **优化**：
  1. **三段式背景**（G9）：先铺 ① 径向纵深渐变（中心亮 10%，色相偏冷 8°）；
     ② 星野/尘埃 tile（`ProceduralTexture`，`alpha 0.20`）。成本 2 次 draw。
  2. **山脊平滑**：把 `lineTo` 改为 **Catmull-Rom 二次采样**（`DnaRenderer.kt:467-681`
  有可复用的中心路径范式）—— 每个相邻点对之间插 2 个中间点（零分配，`path.quadraticBezierTo`），
   `barCount=64` 时共 128 段，仍在单 `Path` 内。
  3. **层间透视**：`amp` 改为 `amp * (1f - t*0.25f) * (0.65f + t*0.35f)`，
     同时 `baseY` 按 `t` 上移 `h*0.03f` —— 形成"近山（低、大）→ 远山（高、小）"。
  4. **边缘辉光**：每层描边改为 2 条 —— 主线（`alpha 0.30`，`width 1.6f`）+
     上层偏移 1px 的高光线（`towardWhite(col, 0.5f)`，`alpha 0.22`），
     即"山脊受光面"。成本 +1 次 `drawPath`/层。
  5. **接触阴影**（G4）：每层向下的填充改用**垂直 `linearGradient`**（顶 `alpha 0.20` →
  底 `alpha 0.02`），比现在的均匀 `alpha 0.16` 更有"雾气下沉"感。
  6. **后处理**：`drawVignette(0.44f)` + `drawGrain(seq, 0.030f)`。
- **验收**：山脊放大后**无锯齿台阶**；能明显看出 5 层的前后关系（不是 5 张平行纸）；
  静音时画面**不是纯黑**（有背景纵深与星野）。

#### A4 · E12 频谱瀑布 `SPECTRO_WATERFALL`（`AdvancedRenderers.kt:100-159`）

- **现状**：128×200 的乒乓 `ImageBitmap`，每帧上移 1px 并写底部新行（`:120-136`），
  色相 `hueGradient(60°, 195°, v)`、亮度 `0.50 + v*0.40`（`:130-131`），
  最后 `drawImage` 铺满画布（`:139-141`）。
- **质感问题**：
  ① **128 px 宽放大到 1920 px（15×）**（`:112` / `:139-141`）⇒ 双线性插值后严重模糊，
  色块糊成一团，看不出频段细节；
  ② **无网格/刻度/频率标注**（真频谱瀑布的核心是"可读性"）；
  ③ **无衰减拖尾**：`cb.drawImage(p, Offset(0, -1))`（`:122`）是纯位移，历史行亮度不衰减
  ⇒ 高频持续时底部一片白；
  ④ 每行是 1px 高的纯色 `drawRect`（`:132-135`），无行内插值 ⇒ 行间有横向条带。
- **优化**：
  1. **提高横向分辨率**：`w` 从 `128` 提到 `barCount × 4`（HIGH 档 = 256），
     行内改为**相邻桶线性插值**填充（`:128-136` 内按 4 个子列插值）——
     成本不变（仍是 `n×4` 次 `drawRect`，但可合并为单 `Path`，见 3）。
  2. **衰减**：在 `cb.drawImage(p, Offset(0,-1))` 之后叠一层
     `drawRect(color = Color.Black, alpha = 0.06f)`（`BlendMode.Darken` 或普通叠加）——
     让历史行**按距离衰减**，消除底部死白。⚠️ 该叠加会同时压暗新行，故新行亮度
     需反向补偿 `+0.06`（参数见 §七）。
  3. **底部新行合并为单 `Path`**：现在 `n` 次 `drawRect`（`:132-135`）在 HIGH 档 = 64 次
     独立指令；改为按 16 个 hue 桶合并进 16 条 `Path`，**16 次 `drawPath`**。
  4. **网格与刻度**：叠 1 张 `ProceduralTexture` 的**水平细线 tile**（`alpha 0.10`）+
     4 条等分垂直参考线（1 次 `drawPath`），让画面有"仪器感"。
  5. **亮度曲线**：`0.50 + v*0.40`（`:131`）改为 `0.34 + pow(v, 0.72f)*0.52` ——
     低能量段更暗、高能量段更亮，动态范围更"活"。
  6. **后处理**：`drawVignette(0.38f)` + `drawScanlines(periodPx = 3, dark = 0.12f)`
     （扫描线让它与 CRT 仪器气质一致）。
- **验收**：放大截图上**能数出至少 8 个频段的色带边界**（不再是糊块）；
  持续高频段落底部**不出现死白**；行与行之间**无横向条带**。

#### A5 · E13 液态网格 `LIQUID_GRID`（`AdvancedRenderers.kt:171-263`）

- **现状**：`cols×rows` 顶点被三频正弦叠加推动（`:204-222`），
  连线合成单 `Path` 一次绘制（`:225-243`，`alpha 0.28`，`Stroke(1f)`），
  顶点按列分 4 段 hue 合并成 4 条 `Path`（`:245-261`，`alpha 0.65 + energy*0.30`）。
- **质感问题**：
  ① **连线单色 1px 硬线**（`:242`）⇒ 像 CAD 线框，不像水面；
  ② **顶点是圆点**（`:252`）⇒ 没有"水面反光"（真实水面高光是**拉长的镜面反射**，不是圆点）；
  ③ **无底色/无环境反射**（G9）⇒ 网格浮在纯黑上；
  ④ `wave` 用 `frame.timeMs` 直接乘系数（`:210-213`：`sin(x*0.3f + t*0.002f)`）
  —— ⚠️ **这是本项目 §10.176 明令禁止的"绝对时间 × 系数"写法**：
  `timeMs` 是开机毫秒（1e7 量级），`t*0.002f` 在大基数下 float 精度丢失 ⇒
  **长时间运行后波纹会冻结/跳变**。虽未在真机复现，但属于已知红线。
- **优化**：
  1. **⛔ 修红线**：`t` 改为 `elapsed` 累加（`elapsed += dt`，`dt` 钳 0.1s），
     与 `BatchTwoRenderers`（太阳系）、`DnaRenderer`、`WorldRenderer` 一致。
  2. **连线改"受光网格"**：连线颜色按**顶点高度**（`wave`）插值 ——
     `lerp(darken(accent,0.5f), towardWhite(accent,0.35f), waveNorm)`，
     并按高度分 4 桶合并进 4 条 `Path`（复用现有 4 段结构，零额外 draw 调用）。
  3. **顶点改"镜面反光"**：圆点 → 沿"光向的垂线方向"拉长的**椭圆**
     （`drawOval`，长轴 = 短轴 × 2.4，旋转角 = `LIGHT_ANGLE_DEG + 90°`）。
     ⚠️ **源码级更正（2026-09-28 核实，见 §15.4-A7）**：`DrawScope.rotate` 与
     `DrawScope.withTransform` 在 ui-graphics **1.6.1 里都是 `inline`**
     （`DrawScope.kt:137` / `:259`），lambda 被内联展开、**不产生 lambda 对象** ——
     所以"用 `withTransform` 会每帧分配 lambda"这条既有认知与源码不符。
     本项仍建议走**参数方程 + `addOval`**，但理由改为：少一次 `canvas.save()/restore()`，
     且 `drawOval(topLeft, size)` 全是值类型、更直观；⛔ **不是**"因为会分配"。
  4. **三段式背景**：径向纵深渐变 + 水下颗粒 tile（`alpha 0.18`）。
  5. **水下焦散**：叠 1 层低频驱动的**缓慢移动的宽条纹**（`ProceduralTexture` 的
     caustic tile，`alpha = 0.06 + bass*0.08`）—— 成本 1 次 `drawImage`。
  6. **后处理**：`drawVignette(0.46f)` + `drawGrain(seq, 0.032f)`。
- **验收**：静置 30 分钟后波纹**不冻结**（对照 §10.176 的判据）；网格能看出"高处的线更亮"；
  顶点是**拉长的反光**而非圆点。

#### A6 · E15 液态涟漪 `LIQUID_RIPPLE`（`AdvancedRenderers.kt:274-337`）

- **现状**：40 环池（`:282`），`treble > 0.20` 随机位置生成小涟漪、
  `bass > 0.35` 与 `beat` 在中心生成大波纹（`:307-316`），
  每环 `drawCircle + Stroke`（`:328-334`），`kind==1` 时宽 3px / `alpha 0.45`，否则 1.2px / 0.24。
- **质感问题**：
  ① **环是单色细描边**（`:328-334`）⇒ 没有"水波的厚度"（真实水波是**内暗外亮的双边**）；
  ② **无折射/无水面纹理**（G9）⇒ 环浮在纯黑上；
  ③ 环的 `alpha` 只随 `life` 线性衰减（`:332`）⇒ 消失是"整体变淡"，不是"扩散变薄"；
  ④ **无中心亮斑/无高光**：`beat` 时生成 5 个同心环（`:314-316`）但都是同色细线 ⇒ 没有冲击感；
  ⑤ `frame.timeMs % 8 < 2`（`:311`）用绝对时间取模做节流 —— 虽不涉相位精度，
     但与 `dt` 累加体系不一致（可接受，但建议统一）。
- **优化**：
  1. **双边水波**：每环改为 2 次 `drawCircle` —— 内圈（`darken(accent,0.45f)`，
     半径 `r`，`width 1.0`）+ 外圈（`towardWhite(accent,0.45f)`，半径 `r + width`，
     `width 0.8`），形成"波峰受光、波谷背光"（G2）。
  2. **扩散变薄**：`Stroke.width` 改为 `base * (1f - progress)`（`progress = 1 - life`），
     让环在扩散中**逐渐变细**，配合 alpha 衰减更像水波。
  3. **水面底纹**：叠 1 层 `ProceduralTexture` 的**水纹 tile**（`alpha = 0.14`）+
     径向纵深渐变。
  4. **中心高光**：`beat` 时在中心加 1 次 `drawCircle(brush = shadeBrush(...))`
     白亮光斑（半径 `minDim*0.10`，`alpha 0.35`，随 `pulse` 衰减），
     替代现在的"5 个同心细环"（`:314-316`，改成 2 个 + 光斑）。
  5. **后处理**：`drawVignette(0.44f)` + `drawGrain(seq, 0.030f)`。
- **验收**：单个涟漪能看出**内暗外亮的双边**；环在扩散过程中**明显变细**；
  `beat` 时中心有可见光斑（对比改造前只有细环）。

#### A7 · E17 星座 `CONSTELLATION`（`AdvancedRenderers.kt:486-568`）

- **现状**：160 星点池（`:494`），节拍生成 9 个 + 能量持续补星（`:512-524`），
  生命线性衰减（`:527-532`），邻近连线合成单 `Path`（`:535-554`，`alpha 0.42+energy*0.30`，`width 1.7f`），
  星点合并单 `Path`（`:557-566`）。
- **质感问题**：
  ① **星点是纯色小圆**（`:564`）⇒ 没有"星芒"（真实亮星有十字/六芒衍射）；
  ② **连线单色等宽**（`:554`）⇒ 没有"近亮远暗"的空间感，也没有渐隐；
  ③ **O(n²) 连线**（`:541` 双重循环 160×160 = 12720 次距离判定）⇒ HIGH 档下是
     本项目最重的纯 CPU 循环之一（虽合并成单 `Path`，但**判定次数**仍在）；
  ④ **无背景星野**（G9）⇒ 生成的星与"什么都没有"的背景之间没有层次。
- **优化**：
  1. **星芒**：只对 `size > 3.2f` 的亮星（约 20%）额外画 1 条十字光芒
     （`drawLine` ×2，长度 = `r*3.2`，`alpha 0.22`）—— 成本可控（≤ 30 次 draw）。
  2. **连线分级**：按距离分 2 档（`d < 0.5×linkDist` → 亮且宽 2.0f；否则 1.2f），
     合并为 2 条 `Path`（零额外 draw 调用），并按 `life` 乘积作为"两星都亮才亮"的权重
     （用 `min(lifeI, lifeJ)` 做 3 桶 alpha）。
  3. **降复杂度**：把 `linkDist` 判定改为**空间网格分桶**（`cell = linkDist`，
     `cols×rows` 桶，只查邻接 9 桶）—— 判定次数从 12720 降到约 160×9 = 1440
     （**9 倍**）。⚠️ 需要预分配桶数组（`IntArray(160)` 记录所属桶 + 桶头链表），
     ⛔ 不许用 `List<Int>`（违反零分配）。
  4. **背景星野**：叠 1 层静态远景星野（`ProceduralTexture`，`alpha 0.28`，**不衰减**）
     与动态星区分开 ⇒ 立刻有"深空"纵深。
  5. **后处理**：`drawVignette(0.50f)`（星座类暗角要重一些）+ `drawGrain(seq, 0.026f)`。
- **验收**：亮星可见**十字星芒**；连线有**明显两档粗细**；HIGH 档下
  `ConstellationRenderer.draw` 耗时相对改造前**下降**（用 `adb shell dumpsys gfxinfo` 对比）。

#### A8 · E24 心跳 `ECG_WAVE`（`EcgWaveRenderer.kt:50-215`）

- **现状**：波形表 `HB_T`/`HB_V`（**14 点**，`:250-259`）+ `heartbeatAt()` 线性扫描插值（`:217-229`）、
  网格线（中线 + 上下偏移线 + 等分竖线，全部 `BlendMode.Plus`，`:194-208`）、
  波形 `Path` 双层（辉光 `alpha 0.18` + 主线 `alpha 0.95`，`:178-187`）。
- **质感问题**：
  ⓪ ⛔ **波形形状本身不像心电图（本效果最要命的一条，原先漏诊）**。逐列模拟
     显示网格（1 列 = `1000/90` = 11.111 ms）后实测：
     - **全表没有任何一段等电位平线** —— 14 个点**首尾相接成锯齿**。PR 段（列 9–15）实测是
       `+0.099 → +0.065 → +0.030 → −0.005 → −0.047 → −0.088 → −0.105` 的**下坡**；
       ST 段（列 25–33）是 `−0.215 → … → +0.175` 的**上坡**。真实 ECG 的 PR 段与 ST 段
       **都是水平等电位线** ⇒ 视觉上整条曲线是"连绵起伏的锯齿"，而不是"平段之间穿插尖峰"。
     - **R 峰被削平**：峰顶在 `0.214s`（= **19.26 列**）⇒ **不落在任何显示列上**，
       采样峰值只有 **0.897**（每拍稳定地矮 10%）。
     - **T 波与 QRS 宽度比是错的**：旧表 T 波 122 ms（3 个点）、QRS 104 ms ⇒
       **比 1.17**；真实 ECG 的 T 波（140–180 ms）约为 QRS（80–120 ms）的 **1.8 倍**。
       现状是"两个差不多宽的三角尖"，不是"**窄高尖峰 + 宽低圆峰**"。
     - **P 波起点错位**：旧表 `HB_T[0]=0.000` 与 `[1]=0.048` 的值**都是 0** ⇒ 前 48 ms 是平线，
       P 波实际只有 84 ms 且是**三角**（真实是 100 ms 的圆钝小丘）。
     - **Q 波后先回基线再起 R**（`0.164→0.186` 回 0，`0.186→0.214` 才升到 1.0）⇒ 像双峰。
  ① **网格等亮度**（`:194-208`）⇒ 没有"示波器屏幕"的内亮外暗；竖线等分 8 格 = 88.9 ms/格，
     与真实心电纸的 0.2 s 大格不对应；
  ② **无余辉拖尾**：波形只画当前 `Path`，历史波形不保留 ⇒ 缺少 ECG 最关键的"余晖"；
  ③ **无 CRT 感**：没有颗粒、没有扫描线、没有暗角（G1）⇒ 像矢量图不像仪器；
  ④ 辉光靠"加宽描边"（`:178-181`）—— 同 G5 的假光晕。

- **优化（⛔ 第 0 条是 P0，与 1–5 无依赖，可单独先做、单独提交）**：
  0. **心搏波形真实化（P0，纯数据 + 表驱动，零新增分配 / 零新增 draw）**
     - **核心洞察**：renderer **每写一列才产生一个可见折线顶点** ⇒ 波形的**时间分辨率
       就等于 1 列**（`1000/90` = 11.111 ms）。所以表**只需要「一列一个采样」**，
       索引 = **整数列龄**，**根本不需要插值**。旧表的 14 点/线性插值在这个分辨率下
       既浪费（点比列还少）又走形（折线首尾相接成锯齿）。
     - **改法 A：把 `colMs`/`beatAtMs` 换成整数列号**（这一步同时**消灭**一个隐患）：
       现在 `ageSec = (colMs − beatAtMs) * 0.001f` 是**两个累加 float 相减**，
       播放几十分钟后累积误差可达 ~1 ms，会让 `heartbeatAt` 的查表位置抖动；
       改为整数列号后 **`ageCols` 完全精确**，并且 `REBASE_AT_MS`（`:248`）这套
       float 精度回绕 hack **可以直接删掉**。

       | 成员 | 现在 | 改为 |
       |---|---|---|
       | `colMs: Float` | 虚拟时间 ms（`:64`） | **`colIdx: Int`** —— 已写列数（单调递增） |
       | `colAccum: Float` | 保留（`:66`） | 不变（仍是"本帧要写几列"的小数累加器） |
       | `beatAtMs: Float = NONE` | `:68` | **`beatCol: Int = NO_BEAT`** |
       | `lastFireMs: Float = NONE` | `:70` | **`lastFireCol: Int = LAST_FIRE_NONE`** |
       | `REBASE_AT_MS` | `:248` | **删除**（不再需要） |
       | `MIN_GAP_MS = 800f` | `:239` | **`MIN_GAP_COLS = 72`**（`72 × 1000/90 = 800 ms` 精确） |

       ```kotlin
       // 鼓点判定（:135 那行）改为整数列比较，语义完全等价
       if ((frame.beat || pulseRising) &&
           (colIdx - lastFireCol) >= MIN_GAP_COLS) { … beatCol = colIdx; lastFireCol = colIdx }

       // 写列循环（:144-152）—— ageCols 是整数，精确无漂移
       var k = 0
       while (k < n) {
           hist[rightPtr] = heartbeatAt(colIdx - beatCol) * spikeAmp
           rightPtr = (rightPtr + 1) % cols
           colIdx++
           k++
       }
       ```
     - **改法 B：`HB_T`/`HB_V` 两张表合并成一张 50 点的 `HB`**，按临床时程重排
       （⛔ **`HB` 表长与 `speed` 绑定**：表长 = 心搏占用的列数）。

       | 段 | 列区间 | 时长 | 幅值 | 形状 | 临床正常值 |
       |---|---|---|---|---|---|
       | **P 波** | `[0, 9]` | 100 ms | `+0.140` | 升余弦（圆钝小丘，峰在列 4–5） | 80–100 ms / 0.10–0.20 |
       | **PR 段** | `[9, 16]` | 78 ms | `0` | **等电位平线** | PR 间期 120–200 ms |
       | **Q** | `[16, 17]` | 11 ms | `−0.110` | 折线（浅而窄） | −0.05–−0.15 |
       | **R** | `[17, 19]` | 22 ms | **`+1.000`** | 折线（陡升） | — |
       | **R 下降** | `[19, 22]` | 33 ms | → `−0.280` | 折线（**缓降**，升快降慢） | — |
       | **S 回基线** | `[22, 24]` | 22 ms | → `0` | 折线 | QRS 总宽 80–120 ms |
       | **ST 段** | `[24, 34]` | 111 ms | `0` | **等电位平线** | 80–120 ms |
       | **T 波** | `[34, 48]` | 160 ms | `+0.270` | **非对称升余弦**（升 65 ms / 降 95 ms，峰在列 40） | 140–180 ms / 0.20–0.35 |
       | 尾 | `[48, 49]` | 11 ms | → `0` | — | — |

       ⇒ 合计 **50 列 = 555.6 ms**（`QT` = 360 ms、`T/QRS` 宽度比 = **1.80**、
       `R/T` 幅值比 = **3.70**、`R/P` = **7.14**，全部落在临床区间内）。
       ⛔ **R 峰故意放在第 19 列**（`19 × 11.111 = 211.1 ms`）：这样它**精确落在显示列上**，
       采样峰值恒为 `1.000`（旧表是 0.897）。

       ```kotlin
       // 心搏波（P-QRS-T）逐列采样值：共 50 列 = 555.6 ms @ 90 列/秒。
       // 索引 = 距鼓点的**列数**；[19] = R 峰、[17] = Q 谷、[22] = S 谷、[40] = T 峰。
       // ⛔ 表长与 speed 绑定：HB.size / speed * 1000 必须 < MIN_GAP_MS（门禁 EcgWaveformTest）。
       val HB = floatArrayOf(
           +0.000000f, +0.016377f, +0.057845f, +0.105000f, +0.135778f, +0.135778f, +0.105000f, +0.057845f, +0.016377f, +0.000000f,
           +0.000000f, +0.000000f, +0.000000f, +0.000000f, +0.000000f, +0.000000f, +0.000000f, -0.110000f, +0.445000f, +1.000000f,
           +0.573333f, +0.146667f, -0.280000f, -0.140000f, +0.000000f, +0.000000f, +0.000000f, +0.000000f, +0.000000f, +0.000000f,
           +0.000000f, +0.000000f, +0.000000f, +0.000000f, +0.000000f, +0.018268f, +0.069392f, +0.138987f, +0.207459f, +0.255532f,
           +0.269846f, +0.258530f, +0.230722f, +0.190136f, +0.142189f, +0.093283f, +0.049945f, +0.017963f, +0.001604f, +0.000000f,
       )
       ```
     - **改法 C：`heartbeatAt` 变成 O(1) 整数索引**（**删掉整个线性扫描 + 插值**）：
       ```kotlin
       /** 列龄（列）→ 幅值。O(1) 整数索引，无插值、无浮点、无分配。 */
       internal fun heartbeatAt(ageCols: Int): Float =
           if (ageCols >= 0 && ageCols < HB.size) HB[ageCols] else 0f
       ```
       ⚠️ **不要写 `if (ageCols in HB.indices)`** —— 虽然 Kotlin 对 `array.indices` 的
       `in` 有边界检查优化，但显式比较更不容易被后来者改坏（本效果对"每帧零分配"敏感）。
       ⚠️ **可见性**：`HB` 与 `heartbeatAt` 必须能被单测直接读/调 ⇒ 把 `private companion object`
       改为 **`internal companion object`**、`private fun heartbeatAt` 改为
       **`internal fun heartbeatAt`**。这是本项目既有范式
       （`HypnoticFunctionRenderer.kt:48` companion + `:81` `internal enum class Phase`，
       单测直接调 `HypnoticFunctionRenderer.nextPhase(...)`，见 `HypnoticPhaseTest.kt`）。
       ⛔ 不要为了让单测访问而把表复制到测试里 —— 那就失去了"门禁盯住生产表"的意义。
     - **哨兵值**：`beatCol` 用 `NO_BEAT = -1_000_000`（列龄恒 > 表长 ⇒ 自然返回 `0f`，
       不会在开播瞬间漏出 `HB[1] = 0.016` 这种假信号）；`lastFireCol` 用
       `LAST_FIRE_NONE = -(MIN_GAP_COLS + 1)`（保证第一拍必定通过间隔判定）。
       ⛔ 不要用 `Int.MIN_VALUE`（`colIdx - Int.MIN_VALUE` 会溢出）。
     - **同步改 KDoc**：`:41-43` 的「拉长到约 0.50s」→「**50 列 ≈ 556 ms**」；
       `:63` 的「当前最右列的虚拟时间（ms）」→「已写列数」；`:47-48` 的「滚动与心搏展开
       按列虚拟时间基准匀速」→「按**列计数**基准」。
     - ⛔ **不改动**：`spikeAmp` 的强弱映射（`:137-139`，`bassRaw/自身均值` → `0.45..1`）、
       鼓点双通道判定、`AMP_FRACTION`、`speed`。心搏的**强弱差异**与**形状真实化**是两件事。
     - ⛔ **不新增扫描头 / 节拍圆点**：KDoc `:45` 已明确「**峰顶无帽**：不绘制任何节拍点/
       扫描头圆点」——**原第 5 条「扫掠头增强」与源码既有裁决冲突，本版作废**。
  1. **网格纵深**：网格线颜色按"距中心距离"衰减（中心 `alpha 0.30` → 边缘 `alpha 0.10`），
     并把 `Plus` 改为普通叠加（避免网格叠出死白）；中线保留 `Plus`。
     **竖线间距改为 0.2 s（= 18 列）对齐真实心电纸大格**，并补 5 等分小格（3.6 列 ≈ 7 px）；
     ⚠️ 与波形表一样，竖线间距也由 `speed` 决定 ⇒ 若 `speed` 改动需同步（写成一个
     `private val gridStepCols = 18` 常量并加注释，别硬编码 px）。
  2. **余辉拖尾**：**新增**一条"延迟波形" —— 维护一个 `FloatArray(cols)` 的逐帧衰减副本
     （`decay = 0.90f`，在 `draw` 内原地乘，零分配），作为第 3 层 `Path` 绘制
     （`alpha 0.16`，`strokeWidth 3f`，`Plus`）⇒ 波形扫过后留下逐渐淡去的拖影
     （**这是本效果收益最大的一条观感改造**，与第 0 条互不依赖）。
  3. **辉光改径向**：`:178-181` 的加宽描边改为"主线 + 1 层 3× 宽的 `alpha 0.10`"，
     并仅在 `FxLevel != OFF` 时画。
  4. **CRT 后处理**：`drawScanlines(periodPx = 3, dark = 0.14f)` +
     `drawVignette(0.48f)` + `drawGrain(seq, 0.030f)` —— 让整个画面像"老式监护仪"。
  5. **基线漂移（呼吸波，可选 P1）**：真实 ECG 的等电位段**不是数学零**，有随呼吸的缓慢上下漂移。
     在写列时给 `heartbeatAt(...)` 的返回值叠加 `wander`：`wander = sin(phase) * 0.012f`，
     `phase += dt * 0.9f`（≈7 s 周期）。⛔ **必须用相位累加器**，禁止 `nowMs × 系数`（§R1 红线）；
     ⛔ 幅度 ≤ `0.015`，否则会把"等电位平段"重新毁掉（第 0 条刚修好的东西）。
- **验收**：① **波形形状**（第 0 条）：放大截图上 **QRS 是"陡升缓降"的窄尖峰（R 峰 8 列内完成）、
  T 波是宽而低的圆钝峰（宽约 14 列）**，两者宽度肉眼可辨；**PR 段与 ST 段是水平直线**
  （不是上坡/下坡）；P 波是圆钝小丘而非三角；**R 峰顶不被削平**（对比旧画面可见峰顶变高）。
  ② 波形扫过后有**可见拖影**（放大截图上主线后方有 2–3 档渐弱的副本）；
  ③ 网格四角**明显暗于中心**；④ 整体有扫描线质感（在 1080p 下**无摩尔纹**）。

#### A9 · E30 雷达 `RADAR_GRID`（`BatchThreeRenderers.kt:30-188`）

- **现状**：5 个同心圆（`:106-113`，最外圈亮度随 `energy`）+ 12 条射线
  （`:116-134`，扫掠角余弦衰减）+ `SweepGradient` 扫掠扇（`:136-145`）+
  扫掠主线（`:147-151`）+ 频谱余辉短弧（`:161-187`，32 段）+ 中心点。
- **质感问题**：
  ⓪ ⛔ **原理性错误：这个"雷达"没有真实 PPI 的核心行为**（用户原话：
     「真实的雷达应该是一条线扫描，然后扫描出现的点，**在下次扫描后会变化位置**」）。
     逐行核对 `draw()` + `drawSpectrumArcs()` 后，四条真实行为**一条都不成立**：
     1. ⛔ **回波焊在扫掠线上、永远不留在原地**：`:171-172` 把每段弧的角度算成
        `sweepAngle − i*TAU/segs*2f` ⇒ 弧**始终跟在扫线后方固定偏移处、随扫线一起转**。
        真实 PPI 上，扫线过去后回波**留在目标自己的方位角**上原地衰减，直到下一圈才被刷新。
     2. ⛔ **没有任何"保持"机制** ⇒ 也就**不可能有"下一圈换位置"**：弧的亮度直接是
        `v * 0.8f`（`:182`，**瞬时**频谱值），频谱一掉弧就消失。真实雷达靠**磷光余辉**把一个
        目标保持到下一圈 —— 有"保持"才有"位置"可言。
     3. ⛔ **角度跨度写错 2 倍**：`TAU/segs*2` × 32 段 = **2 整圈** ⇒ 32 段弧在圆周上
        **互相重叠两遍**，且 `bin = i*bins/segs`（`:168`）让"方位角"与"频段"的对应关系
        转两圈后自我重复（bass 与 treble 落到同一位置）⇒ 方位角**不承载任何信息**。
     4. ⛔ **扫掠光晕根本不在转（真 bug，不是观感问题）**：`ensureBrush`（`:64-75`）在
        `brushW/brushH` 未变时**直接 `return`**，而 `sweepBrush` 的 `SweepGradient` 停靠点是
        写死的（`0.70→Transparent / 0.95→alpha 0.5 / 1.0→Transparent`）⇒ **那个亮扇区
        钉死在 252°–360° 从不旋转**。画面上"在转的"只有那条 2.5 px 主扫线（`:150-151`）。
  ① **同心圆/射线等亮度**（`:110`、`:126-132`）⇒ 没有"雷达屏中心亮边缘暗"的荧光感；
  ② **无 CRT 质感**：无颗粒、无扫描线、无暗角（G1）；单色雷达绿是刻意的，但"绿得太平"；
  ③ **扫速被 treble 拉 6 倍**（`:102` `SWEEP_BASE_SPEED + trebleSmooth * 6f`）⇒
     真实雷达天线**转速恒定**（典型 2–5 s/圈），现状 0.83–3.9 s/圈剧烈变速，
     失去"雷达在匀速转"的机械感。

- **目标：按真实 PPI（Plan Position Indicator）重建四条行为**

  | # | 真实 PPI 行为 | 现状 | 改后 |
  |---|---|---|---|
  | 1 | 一条**匀速**旋转的扫线（天线转速固定） | 有扫线，转速被 treble 拉 6× | `SWEEP_SPEED = 1.6f` rad/s 恒定（3.93 s/圈） |
  | 2 | 扫线经过目标时，在**目标自己的方位角**打出回波（短弧，弧长 = 波束宽度） | 回波焊在扫线后方，方位角无意义 | 回波画在 `BEARING[t]`，弧宽 `BEAM ≈ 9°` |
  | 3 | 回波**留在原地衰减**（磷光余辉），可撑到下一圈 | 无保持，频谱一掉就没 | `BRIGHT` 线性衰减（`holdSec ≈ 1.15 × 周期`） |
  | 4 | 下一圈再扫到时目标**已经移动** ⇒ 回波换位置 | 不存在（原地重复） | 目标连续漂移 + 寿命重生 |

- **优化（⛔ 第 0 条是 P0，与 1–3 无依赖，可单独先做、单独提交）**：
  0. **真实 PPI 重建（P0）**
     - **目标池**（零分配）：`FloatArray(N * STRIDE)`，`N = 24`，`STRIDE = 8`：

       | 槽 | 含义 | 初值 |
       |---|---|---|
       | `BEARING` | 方位角（rad，`[0, TAU)`） | 均匀随机 |
       | `RANGE` | 距离（`0.25..1.0` 的半径比例） | 随机 |
       | `DRIFT_B` | 角漂移速率（rad/s） | `±(0.03..0.10)`（≈ 每圈 7–23°） |
       | `DRIFT_R` | 距离漂移速率（/s） | `±(0.005..0.02)` |
       | `BIN` | 绑定的频谱桶（决定回波强度/粗细） | `0..bins-1` |
       | `BRIGHT` | 回波亮度 `0..1`（磷光余辉） | `0` |
       | `PEAK` | 上次被扫到时的频谱值（决定弧宽/线宽） | `0` |
       | `LIFE` | 剩余寿命（s） | `6..20` |

     - **每帧逻辑**（`draw` 内，零分配）：
       ```kotlin
       val prev = sweepAngle
       sweepAngle += SWEEP_SPEED * dtSec            // ⛔ 恒定转速，不再乘 treble
       if (sweepAngle >= TAU) sweepAngle -= TAU
       var t = 0
       while (t < activeCount) {                    // activeCount = 8 + (energy*16).toInt()
           val o = t * STRIDE
           // ① 磷光衰减（线性；HOLD_SEC 略大于一圈 ⇒ 撑到下一圈）
           pool[o + BRIGHT] = (pool[o + BRIGHT] - dtSec / holdSec).coerceAtLeast(0f)
           // ② 漂移（连续 ⇒ 下一圈位置不同）
           pool[o + BEARING] = wrapTau(pool[o + BEARING] + pool[o + DRIFT_B] * dtSec)
           pool[o + RANGE] = bounce(pool[o + RANGE] + pool[o + DRIFT_R] * dtSec, 0.25f, 1f)
           // ③ 扫线穿越 → 点亮（区间判定，抗跨角/回绕）
           if (crossed(prev, sweepAngle, pool[o + BEARING])) {
               pool[o + BRIGHT] = 1f
               pool[o + PEAK] = frame.spectrum[pool[o + BIN].toInt()]
           }
           // ④ 寿命到 → 重生（换位置，**不是消失**）
           pool[o + LIFE] -= dtSec
           if (pool[o + LIFE] <= 0f) respawn(o)
           t++
       }
       ```
     - **穿越判定**（抽成 `internal` 纯函数，可单测）：
       ```kotlin
       /** 扫线本帧从 [prev] 扫到 [cur]（可能跨 0 回绕），是否扫过 [target] 方位。
        *  区间取左开右闭 (prev, cur] —— 保证同一圈不重复点亮、也不漏点。 */
       internal fun crossed(prev: Float, cur: Float, target: Float): Boolean {
           val span = cur - prev
           if (span >= TAU) return true          // 一帧扫满一圈 → 全部命中（兜底）
           val d0 = wrapTau(prev)
           val d1 = wrapTau(cur)
           val d = wrapTau(target)
           return if (d1 >= d0) d > d0 && d <= d1 else d > d0 || d <= d1
       }
       ```
       ⚠️ `dt` 已钳 0.1 s、转速 1.6 rad/s ⇒ 单帧最多 0.16 rad，`span >= TAU` 走不到；
       但**单测必须覆盖**（防后人把 `SWEEP_SPEED` 调大）。
     - **绘制**（零分配；`Offset`/`Size` 都是 `value class`，见 §15.4-A24/A32）：
       ```kotlin
       val b = pool[o + BRIGHT]
       if (b > 0.02f) {
           val ang = pool[o + BEARING]
           val rr = r * pool[o + RANGE]
           val peak = pool[o + PEAK]
           val half = BEAM * (0.7f + peak * 0.6f) * 0.5f
           drawArc(gridColor,
               startAngle = (ang - half) * RAD2DEG, sweepAngle = half * 2f * RAD2DEG,
               useCenter = false,
               topLeft = Offset(cx - rr, cy - rr), size = Size(rr * 2f, rr * 2f),
               style = Stroke(width = 2.5f + peak * 3.5f, cap = StrokeCap.Round),
               alpha = b * (0.35f + peak * 0.5f) * (1f - 0.35f * pool[o + RANGE]))
       }
       ```
       ⛔ **不要**用 `addOval(Rect)` 画回波圆点 —— `Rect` 是 `data class`，每帧分配（§G12）；
       短弧用 `drawArc` + `Size`（value class）即可。
       ⚠️ `alpha` 末尾的 `(1 − 0.35 × RANGE)` 是**距离衰减**：外圈回波更暗 ⇒ 顺手把
       "荧光屏中心亮边缘暗"做出来了（对应下面第 1 条）。
     - **修扫掠余辉扇（`:64-75` + `:136-145`）**：停靠点固定在「1.0 之前」一段，
       然后用 `withTransform` 按 `sweepAngle` 旋转 ⇒ **零分配、真正跟着转**：
       ```kotlin
       // ensureBrush 里（停靠点写死在 0.86–1.0，不再依赖 sweepAngle）
       sweepBrush = Brush.sweepGradient(
           0.86f to Color.Transparent,
           0.97f to gridColor.copy(alpha = 0.45f),
           1.00f to Color.Transparent,
           center = Offset(cx, cy))
       // draw 里
       withTransform({ rotate(sweepAngle * RAD2DEG, Offset(cx, cy)) }) {
           drawCircle(sweepBrush!!, radius = r, center = Offset(cx, cy), alpha = 0.9f)
       }
       ```
       ⚠️ **`withTransform` 是 `inline`**（`DrawScope.kt:259`，§15.4-A7 已核实），
       内部 `with(drawContext)` + `canvas.save()/restore()`，**不分配对象**
       （`drawContext.transform` 是缓存成员）；`DrawTransform.rotate(degrees, pivot)` 是
       接口成员（`DrawTransform.kt:158`）。
       ⚠️ **`SweepGradient` 的角度 0 在 3 点钟方向、顺时针增大**，与代码里
       `(cos a, sin a)`（屏幕 y 向下 ⇒ 同样顺时针）**同向** ⇒ 直接
       `rotate(sweepAngleDeg)` 就对齐，**不需要加 90° 偏移**。
       ⚠️ **降级方案**（若不想引坐标变换）：把余辉扇画成 **12–16 段递减 `alpha` 的 `drawArc`**
       —— 同样零分配（`Size` 是 value class），代价是有轻微阶梯感。
       ⚠️ ⛔ **`BatchThreeRenderers.kt:11` 的 `withTransform` import 因此会重新被用上**
       ⇒ §15.7 第 6 条「删除 4 个文件的未使用 import」**必须排除本文件**（只删另外 3 个）。
     - **音频映射（保持"音乐可视化"身份，但机制改为物理正确）**：
       - `PEAK` = 被扫到那一刻的 `spectrum[BIN]` ⇒ **频谱越强的桶，回波越亮越粗**
         （原"频谱融合"的意图保留，但不再是"弧跟着扫线跑"）
       - **`BIN` 与 `RANGE` 解耦**（不再"低频=内圈"）：真实雷达的方位/距离与频率无关；
         写死"低频=近、高频=远"会让图案退化成同心圆、失去散点感（见 §十三 裁决项 6）
       - 目标数量随能量变化：`activeCount = 8 + (energy * 16).toInt()`（`8..24`）
         —— 安静时稀疏、激烈时满屏，**替代原来"32 段弧全亮"**
       - 转速**恒定**；treble 改为**缩短余辉**：`holdSec = 1.15f / (1f + trebleSmooth * 0.8f)`
         —— 高频多时余辉变短、画面更"脆"，仍与音乐相关，但**不破坏匀速旋转的机械感**
     - **性能**：24 个目标 × 1 次 `drawArc` = **24 次 drawArc**（现状 32 次，反而少 8 次）；
       随机数只在 `respawn` 时消耗 ⇒ **每帧零分配**。
  1. **荧光屏纵深**：同心圆 alpha 按半径衰减（`0.28 → 0.14`），
     中心加 1 层极淡 `radialGradient` 绿光（`alpha 0.10`）。
     （第 0 条的回波距离衰减已经贡献了一半效果。）
  2. **网格分档明度**：保留单色雷达绿（`0xFF39D97A`），但引入 3 档明度
     （`gridDim` / `gridColor` / `towardWhite(gridColor, 0.5f)`）区分"静态网格 / 活动回波 / 扫掠"。
     ⛔ **不再新增"第二层滞后 SweepGradient"** —— 原方案的第 2 条（滞后 12° 的第二扇）
     已被第 0 条取代：真实雷达的"拖尾"来自**磷光余辉**，不是两个扇。
  3. **CRT 后处理**：`drawScanlines(3, 0.15f)` + `drawVignette(0.52f)` + `drawGrain(seq, 0.034f)`。
- **验收**：① **回波留在原地**（暂停播放后，扫线过去的目标仍亮着并**逐渐变暗**，
  相隔 1 s 两张截图位置不变、亮度下降）；② **下一圈换位置**（连续两圈的同一目标回波
  **方位角差 ≥ 10°**）；③ **扫线匀速**（一圈耗时稳定在 3.9 ± 0.4 s，**不随音乐变化**）；
  ④ **余辉扇跟着扫线转**（相隔 1/4 圈的两张截图，亮扇区跟着移动 —— 现状是静止亮块）；
  ⑤ 回波是**短弧**（宽约 9°）而不是长弧/整圈，且**亮度可辨**（频谱强的更亮更粗）；
  ⑥ 四角明显暗于中心；扫描线无摩尔纹。

#### A10 · E31 折纸 `ORIGAMI_POLY`（`BatchThreeRenderers.kt:201-403`）

- **现状**：`cols×rows×2` 个三角（LOW 16 / MED 36 / HIGH 64 个，`:237-249`），
  顶点抖动（`:266-283`），折叠 `cos` 投影（`:335-369`），
  明暗面 `lightness = baseL or baseL*0.62`（`:374`），
  冷暖 hue 插值 + 低饱和莫兰迪色（`:377-380`），`pulse` 全屏微光（`:392-396`）。
- **质感问题**：
  ① **三角纯色平涂**（`:387`）⇒ 纸的"纤维质感"与"折痕受光"都没有；
  ② **无投影**（G4）⇒ 折叠的三角没有"抬起来"的阴影，立体感全靠明暗差（弱）；
  ③ **折痕是硬边**：相邻三角同色边界无描边、无高光（`:382-387`）⇒ 看不出折痕线；
  ④ 全屏 `Plus` 微光（`:392-396`）在 `pulse` 峰值会把整屏提亮 ⇒ 与 G8 同族问题。
- **优化**：
  1. **折痕高光**：对每个三角，沿"折边"（即 `triFoldDir` 决定的那条边）额外画
     1 条 `alpha 0.16` 的亮线（`towardWhite(color, 0.55f)`，`width 1.2f`）——
     纸的折痕受光。⚠️ 64 个三角 = 64 次 `drawLine`，建议**合并进单 `Path`**（同色可合并）。
  2. **接触阴影**：对"折叠中"（`foldK` 从 1 → −1 过程中 `|foldK| < 0.9`）的三角，
     在其下方偏移 `4f` 画 1 层 `darken(color, 0.35f)`、`alpha 0.22` 的同形三角
     （合并进单 `Path`）⇒ 折叠瞬间有"抬起"的立体感。
  3. **纸张纹理**：叠 1 层 `ProceduralTexture` 的**纸纹 tile**（`alpha 0.10`，
     `BlendMode.Overlay`）—— 一次 `drawImage` 让整屏从"塑料色块"变"纸"。
  4. **折痕渐变**：三角填充由纯色改为**沿折叠方向的 `linearGradient`**
     （受光侧亮 12% → 背光侧暗 12%）—— ⛔ 不能用 `withTransform`（每帧分配），
     用 `Brush.linearGradient(start = a, end = 中点)`，`Brush` 需**按三角缓存**？
     ⚠️ 64 个三角 × 每帧新建 `Brush` = 违反零分配 ⇒ **降级方案**：
     保留纯色填充，只做 ①②③ 三条 + 明暗面细化（`0.62f` → 按 `lambert(法线)` 动态算）。
  5. **后处理**：`drawVignette(0.42f)` + `drawGrain(seq, 0.030f)`；`pulse` 全屏微光
     alpha 从 `0.06` 降到 `0.035`（避免过曝）。
- **验收**：折叠中的三角**可见下方投影**；折边有**受光亮线**；整屏有**纸纹质感**
  （放大可见细微纹理，且**不是规则网格**）。

#### A11 · E32 阶梯 `STAIRCASE_WAVE`（`BatchThreeRenderers.kt:417-516`）

- **现状**：`cols` 列（LOW 20 / MED 28 / HIGH 36，`:443-447`）从底边向上堆叠方块
  （`:462-495`），量化到 `STEPS=16` 档（`:474`），越高的块 alpha 越高（`:487-488`），
  `treble > 0.45` 时顶部 2 块碎裂成 2×2（`:499-515`）。
- **⛔ 功能问题（P0-0 · 必做 —— 用户报的"只有几个柱在动，其他不会动"就是这一条）**：

  用 `logs_temp/staircase_mapping_audit.py` 逐字复算 `:462-470` 的 `src` 映射，实测：

  | 画质 | `cols` | 命中**不同桶** | 覆盖 bin | **缺失桶数** | `src(i) == src(cols-1-i)` |
  |---|---|---|---|---|---|
  | LOW | 20 | **10** | 0..28 | **35** | ✔ 恒成立 |
  | MEDIUM | 28 | **14** | 0..29 | **34** | ✔ 恒成立 |
  | HIGH | 36 | **18** | 0..30 | **33** | ✔ 恒成立 |

  根因链**三条叠加**（任一条单独都不致命，合起来就是"只有几个柱在动"）：

  ① **只映射前半区**：`src = i / half * (n / 2)`（`:466`/`:468`）里的 `n / 2 = 32` 把映射
     死死限制在**前 32 个桶**（因 `half` 除法取整，实际最大只到 28/29/30）。
     ⇒ **bin 31..63 完全不可见**，连 `SpectrumContract.BASS_END = 39` 的低音区都没覆盖完。
     ⇒ 高音镲片 / 齿音 / 弦乐泛音**一响，画面上没有任何柱子响应**（这是"真实反映频谱"最硬的一条不成立）。

  ② **列数 > 桶数的一半 ⇒ 多列共用同一桶**：命中不同桶恰好是列数的**一半**
     （10/20、14/28、18/36）。又因为 `src(i) == src(cols-1-i)` **恒成立**（左右严格镜像），
     ⇒ 画面上是"**若干对同高的柱子**"，**视觉分辨率直接减半**；
     ⚠️ 现状注释（`:463`）写「低频居中（与柱状频谱基类一致的空间分布）」，
     但 `x = i * cellW`（`:471`）明确 `i = 0` 是**屏幕最左列**、`src = 0` 是最低频
     ⇒ **低频在左右两侧、中频在中心**，注释与实现**相反**（§15.7 第 13 条）。

  ③ **16 档量化吃掉小动态**：`steps = (v * STEPS).toInt()`（`:474`，`STEPS = 16f`）
     ⇒ 每档宽 `1/16 = 0.0625`。实测 `v ∈ [0, 0.125)` 的列**高度完全相同（都是 1 格）**，
     只有 alpha 不同（`steps == 0` → 0.10，`steps == 1` → 0.95）。
     而 `spectrum` 是 `boost()` 峰值跟随归一化后的显示通道，**中高频桶的 `v` 常年落在这一区间**
     ⇒ 那一片就是"**恒定 1 格高的矮柱**"，永远不动。

  > 📌 一句话：**低频几个桶在跳（鼓点），其余桶要么共用、要么被量化成同一高度** ⇒ 观感"只有几个柱在动"。
  > ⛔ 这不是"幅度不够"，**改增益/加放大是治不好的** —— 必须修映射。
- **质感问题**：
  ① **方块硬边**（`:504`、`:510-513`）⇒ 1080p 下边缘生硬，且相邻方块之间**没有接缝线**
  ⇒ 相邻列同高度时糊成一片，看不出"格子"；
  ② **无发光溢出**（G6）⇒ 方波美学要求"发光块"，现在只是半透明色块；
  ③ 碎裂是 4 个小方块的机械拼贴（`:510-513`），**无位移/无旋转** ⇒ 不像"碎";
  ④ 无背景纵深（G9）。
- **优化**：
  0. **全柱独立频段映射（P0 · 必做）** —— 把"10/14/18 个高度"变成"**列列独立、覆盖全 64 桶**"。
     分七小步，**顺序不可反**（先修映射，再调观感）：

     **0.1 覆盖全频段**：`(n / 2)` → `n`（`n = frame.spectrum.size = SpectrumContract.BAR_COUNT = 64`）。

     **0.2 列列独立 + 区间均值**：把 `cols` 列映射到 `cols` 个**互不重叠**的桶区间，
     每列取**区间均值**（而不是现状的"单点取值 + 多列共用"）。
     边界表在 `onEnter` 里按画质**建一次**，draw 内只读 ⇒ ⛔ **不违反"draw 内零分配"**（§15.4-A34）。

     ```kotlin
     // ── 成员（新增）──
     private var band = IntArray(0)      // 单调递增边界，size = cols + 1，band[cols] == BAR_COUNT
     private var bandCols = 0

     private fun colsOf(q: VisualQuality): Int = when (q) {
         VisualQuality.LOW -> COLS_LOW
         VisualQuality.MEDIUM -> COLS_MED
         VisualQuality.HIGH -> COLS_HIGH
     }

     override fun onEnter(ctx: RenderContext) {
         lastShatter = false
         rebuildBands(colsOf(ctx.quality))          // ← 唯一建表点
     }

     /**
      * 感知（对数）分桶：低频占更多列（人耳对数感知）。
      * `PERCEPT_K = 1.0` 退化为线性划分；`> 1` 让低频展开。
      * 保证：① 每列至少 1 个桶；② 单调递增；③ `band[cols] == BAR_COUNT`。
      */
     private fun rebuildBands(cols: Int) {
         val n = SpectrumContract.BAR_COUNT          // 64
         val b = IntArray(cols + 1)
         var prev = 0
         for (i in 1 until cols) {
             var x = (n * (i.toDouble() / cols).pow(PERCEPT_K)).toInt()
             if (x <= prev) x = prev + 1             // 每列至少 1 桶
             if (x > n - (cols - i)) x = n - (cols - i)   // 给后面每列各留 1 桶（防御）
             b[i] = x
             prev = x
         }
         b[cols] = n
         band = b
         bandCols = cols
     }
     ```

     **0.3 感知（对数）分桶**：`PERCEPT_K = 1.6f`（新增常量）。
     实测效果（`logs_temp/staircase_mapping_audit.py` T3）：前 8 个桶（0–2.5 kHz，**含全部基频**）
     在 LOW 下占 **6 列 / 20**（线性划分只占 3 列）；MEDIUM **9 / 28**；HIGH **11 / 36**。
     ⇒ 低频细节不再被压成 1–2 列，且**高柱不再全挤在左边**。
     ⚠️ 若观感偏"低频过宽"，把 `PERCEPT_K` 降到 `1.2`；`1.0` 即线性（最"教科书"）。

     **0.4 镜像决策**（→ **§十三 裁决项 7**）：现状是**严格左右镜像**。
     - **选 A（保留镜像）**：`band` 表按 `cols / 2` 列建，draw 内
       `val bi = if (i < cols / 2) i else cols - 1 - i` ⇒ 每对镜像列同高（保持"建筑立面"对称美学），
       但每列要平均 `64 / (cols/2)` = **6.4 / 4.6 / 3.6** 个桶。
     - **选 B（全宽展开，默认推荐）**：`bi = i` ⇒ 低频在左、高频在右，
       每列平均 `64 / cols` = **3.2 / 2.3 / 1.8** 个桶 ⇒ **列分辨率翻倍**，最贴合"真实反映频谱"。

     **0.5 小信号可见性**：`STEPS` `16f` → **`24f`**（每档 `1/24 ≈ 0.0417`），
     并把静音门槛从 `steps <= 0` 改为 `v < SpectrumContract.MIN_AMPLITUDE`（`0.02f`）：

     ```kotlin
     val steps = if (v < SpectrumContract.MIN_AMPLITUDE) 0
                 else (v * STEPS).toInt().coerceIn(1, STEPS.toInt())   // ⛔ 下限 1 格
     ```
     ⇒ 任何**有信号**的列至少画 1 格**正常亮度**块（不再掉进"基础格"），
     只有**真静音**（含底噪）才画 `alpha 0.10` 的轮廓格。

     **0.6 亮度改按「绝对格位」归一**（顺带修闪烁）：现状 `level = (st + 1f) / steps`（`:487`）
     里 `steps` 每帧变 ⇒ **同一格位在不同高度下 alpha 抖动**（视觉闪烁）。改为：

     ```kotlin
     val level = (st + 1f) / STEPS                       // ⛔ 分母是常量 STEPS，不是 steps
     val alpha = (0.22f + level * 0.72f).coerceAtMost(0.95f)
     ```
     ⇒ 每个格位亮度**恒定**，"越高越亮"仍然成立，且**高列整体更亮**（能量感更强）。

     **0.7 分离水平/垂直缝**（顺带修比例）：现状 `:450-453` 用**同一个 `gap`**
     同时 `blockW = cellW - gap` 与 `blockH = cellH - gap` ⇒ 两个方向的缝宽**比例不同**
     （`STEPS` 提到 24 后 `cellH` 变小，垂直缝会显得特别宽）。改为：

     ```kotlin
     val gapX = cellW * CELL_GAP
     val gapY = cellH * CELL_GAP
     val blockW = cellW - gapX
     val blockH = cellH - gapY
     val x = i * cellW + gapX * 0.5f
     ```

     **性能账**（实测口径）：
     - drawRect 次数 = `Σ steps`，最坏 `cols × STEPS`：**576（现状 16 档）→ 864（24 档）**；
       典型值 ≈ **180–300 → 250–450**。⚠️ 与 §九 R3 的 hwui 阈值（320+ **独立 `drawCircle`**）不是一回事 ——
       `drawRect` 更轻且同一色/同 alpha 的矩形会被 hwui 合并；但**若电视上实测帧耗时上升 > 1 ms，按 §九 R13 降级**。
     - 新增每帧成本：区间求和 `Σ (hi − lo) = 64` 次 float 加法（**恰好等于桶总数**，与 `cols` 无关）+ 1 次整数除。
     - 新增内存：`IntArray(37)` ×1（`onEnter` 建一次，**比 `FloatArray` 省一半**，§15.4-A35）。
     - ⛔ **不得为了"减少 drawRect"而回退到"多列共用同一桶"** —— 那正是本条的根因。
  1. **圆角 + 接缝**：`drawRect` → `drawRoundRect(cornerRadius = 1.5px)`，
     并在每块**左侧**画 1 条 `alpha 0.20` 的暗线（模拟"块与块之间的缝"）——
     ⛔ 用 `CornerRadius` 值类型，零分配。
  2. **发光块**：每块下方叠 1 层同色、`alpha 0.12`、向外扩 `2f` 的圆角矩形
     （即"外发光"），仅在 `FxLevel != OFF` 时画 ⇒ 立即有"LED 阵列"感。
  3. **碎裂加位移**：碎裂时 4 个碎块按 `(seq + col) and 3` 做**确定性位移**
     （±2.5f），并按 `seq` 做 ±0.6 rad 的**整数度旋转**（手工算 4 个角点，
     不用 `withTransform`）⇒ 真的像"崩开"。
  4. **三段式背景**：径向纵深 + 星野 tile（`alpha 0.16`）。
  5. **后处理**：`drawVignette(0.46f)` + `drawGrain(seq, 0.030f)` +
     `drawScanlines(4, 0.10f)`（阶梯是"数字/建筑"气质，轻扫描线更贴合）。
- **验收（P0-0 · 必过）**：① **相邻柱高度普遍不同**（不再是几档高度成对重复）；
  ② **低/中/高频段都有柱在动** —— 放高音镲片明显的曲子，**右侧柱必须亮**
  （现状：`bin ≥ 31` 全灭，右侧永远只有"轮廓格"）；
  ③ 同一首歌连续播放时，`cols` 个柱的**起伏包络互不相同**（不是成对同高）；
  ④ 静音段落里所有柱**一起回落到轮廓格**（不是有的高有的低卡住不动）。
- **验收（观感）**：相邻列之间**可见接缝**；发光块在暗背景下**有可见外溢**；
  碎裂瞬间碎块**有明显位移与角度**（不是 4 个原地小方块）；
  同一高度的柱在不同帧下**不再闪烁**（P0-0 的 0.6）。

### 批次 B · 增强级（10 套 · 缺口 ★★）

#### B1 · E11 星系螺旋 `GALAXY_SPIRAL`（`AdvancedRenderers.kt:37-86`）

- **问题**：星点是等大小圆点（`:75-77`）；无星云/尘埃带；中心核是纯色圆（`:83-84`）；
  `rotation += 0.15f + bpm/1200f`（`:46`）—— 逐帧常量累加，**与帧率绑定**
  （60fps 与 30fps 下转速差 2 倍），应改 `dt` 累加。
- **优化**：① 星点按 `v` 分 4 桶（现在 2 桶），远臂星点半径 ×0.6 并降 alpha（纵深）；
  ② 新增**尘埃带**：沿对数螺线叠 1 条 `Path`（`alpha 0.10`，`darken(accent,0.4f)`，
  `strokeWidth = maxR*0.10`）—— 成本 1 次 draw，立刻有"星系"结构；
  ③ 中心核改 `shadeBrush` 径向渐变 + 1 层 `towardWhite` 内高光；
  ④ `dt` 化旋转；⑤ 后处理 `drawVignette(0.46f)` + `drawGrain(seq, 0.028f)`。
- **验收**：能看到**尘埃带**穿过旋臂；中心核有**径向渐变**（无同心台阶）；
  同一首歌在 30fps / 60fps 下**转速一致**。

#### B2 · E14 节拍烟花 `BEAT_FIREWORK`（`ParticleRenderers.kt:29-117`）

- **问题**：粒子纯色圆点（`:106-112`）；爆炸无冲击波/闪光（`:70-92`）；
  静音期只剩 `alpha 0.12` 的底纹（`:67`）⇒ 长时间"几乎全黑"。
- **优化**：① 粒子加**拖尾**：在 `ParticlePool` 的 `update` 之后，
  按 `(x - vx*k, y - vy*k)` 画 1 条短 `drawLine`（`k = 2.5f`，`alpha = life*0.35`）——
  ⚠️ 需确认 `ParticlePool` 的 `STRIDE` 是否含 `VX/VY`（**已含**，见 `ParticleRenderers.kt:240-241` 用法）；
  ⛔ 若要合并为 `Path`，须按 hue 分 8 桶；
  ② 爆炸时加 **1 个冲击波环**（半径随 `pulse` 从 0 扩散到 `0.25×minDim`，
  `alpha` 从 0.55 衰减，`width 3f`）+ **1 次中心闪光**（`shadeBrush` 径向白亮，
  `alpha = pulse*0.45`）；
  ③ 静音期底纹 alpha 从 `0.12` 提到 `0.18` 并加径向纵深，避免全黑；
  ④ 后处理 `drawVignette(0.44f)` + `drawGrain(seq, 0.030f)`。
- **验收**：粒子有明显**拖尾**（放大可见尾迹）；`beat` 瞬间有**冲击波环 + 闪光**；
  静音段画面**不是纯黑**。

#### B3 · E16 数字雨 `MATRIX_RAIN`（`AdvancedRenderers.kt:354-475`）

- **问题**：头部无光晕/无拖尾（`:419-425` 只是 4 档绿字形）；字形是**纯色平涂**
  （`:432-464` 生成时只 `drawText` 一次、无描边无渐变，放大看是色块）；
  无垂直拖影（`:414-428` 每格独立 blit，格间无过渡）。
- **优化**：① **头部光晕**：头部字形 blit 后叠 1 层 `shadeBrush` 绿光斑
  （半径 = `gH*0.9`，`alpha 0.30`）—— 每列 1 次，HIGH 档 48 列 = 48 次 draw（可接受，
  或按亮度分 4 桶合并）；② **垂直拖影**：在头部上方叠 1 条垂直 `linearGradient`
  （`alpha 0.35 → 0`，高 = `cellH*3`）—— 1 次 `drawRect(brush=)`；
  ③ **字形质感**（⛔ **字符集保持 0/1**，见 §13.5）：预渲染阶段给每张字形加
  「深绿 1px 外描边（`Stroke`）+ 中心偏白的垂直渐变填充」，并把档位由 4 档细化为
  **5 档**（新增「白热头部」`rgb(235,255,235)`）⇒ 张数 8 → 10（仅 +2 张小图），
  **每帧 draw 次数与 blit 路径完全不变**；④ 后处理 `drawScanlines(3, 0.16f)` +
  `drawVignette(0.50f)` + `drawGrain(seq, 0.030f)`。
- **验收**：每列头部有**可见光晕**；头部上方有**拖影渐隐**；
  字形**有描边与内部明暗**（放大可见，不再是纯色块）；字符集**仍为 0/1**。

#### B4 · E18 反馈残像 `MILKDROP_FEEDBACK`（`UltraRenderers.kt:32-144`）

- **问题**：反馈层无色调映射（`:91` `alpha 0.88–0.94` 长时间叠加会糊成灰白）；
  无模糊（只有几何变换，`:96-107`）；色相流动单调（`:90`）。
- **优化**：① **加衰减色调**：在 `cb.drawImageRect(p, ...)` 之后叠 1 层
  `drawRect(Color.Black, alpha = 0.06f)`（在离屏缓冲内，成本可忽略）——
  抑制灰白累积；② **轻度径向模糊**：把 `p` 分 3 次以不同 `scale`
  （`1.0 / 1.012 / 1.024`）叠加绘制（`alpha 0.6/0.25/0.15`）——
  等效 3-tap 模糊，成本 +2 次离屏 draw；③ 色相加入 `sectionEnergy` 驱动
  （段落级缓慢色温漂移，见 G10）；④ 频谱环的条改**双边明暗**（同 A2）。
- **验收**：连续播放 5 分钟后画面**不发灰发白**；有可见的**柔化/拖影**（不是硬拷贝）；
  色温随段落有缓慢变化。

#### B5 · E19 粒子文字 `PARTICLE_TEXT`（`ParticleRenderers.kt:134-265`）

> E19 与 E23（§B7）共用同一批技法；**E23 是这套技法的已验证参照实现**（它的按行配额采样是对的）。
> ⚠️ E19 的问题比 E23 **更严重**（含一个"画面永久空白"的缺陷），但**代码量小得多**，是这套技法的低成本试点。

- **问题**（按严重度排序）：
  1. ⛔ **约 4.2 秒后画面永久空白**：`ParticlePool.updateAttract`（`ParticlePool.kt:104-110`）
     每帧 `LIFE -= 0.004f`，初值 `1.0f` ⇒ **250 帧（≈ 4.2 s @60fps）后 `count` 归零**；
     而本渲染器**只在 `caption != lastCaption` 时 `spawn`**（`:208-220`），此后**无任何重生路径**
     ⇒ 粒子全灭后屏幕全黑且不再恢复（直到切效果 / 换歌）。
     ⚠️ 同时暴露一个**目标错位**：`removeAt` 是 swap-remove，`count` 一降，
     槽位 `i` 对应的 `targets[i]` 就不再是那个粒子的目标点 ⇒ 文字逐渐"糊掉"。
  2. ⛔ **每帧 350 次独立 `drawCircle(..., blendMode = Plus)`**（`:250-259`）——
     E19 是 `Tier.ULTRA` ⇒ 只在 HIGH 档可用 ⇒ `maxParticles` 恒为 **350**，
     **逼近本项目 hwui region 合并 SIGSEGV 的 320 阈值**（§R3，`AdvancedRenderers.kt:57-58` 有历史记录）。
  3. ⛔ **采样 16,280 次 `getPixel()` JNI**（`:178-188`，`step = 3`）⇒ 8–20 ms，且在 `draw` 内（`:210`）。
  4. ⛔ **行优先 + `n >= poolCap` 提前 `break`**（`:178-188`）⇒ 采样点集中在**文字上部少数几行**，
     字形下半部没有粒子。**E23 已修同一问题**（`LyricsDotMatrixRenderer.kt:257-260` 的注释 + `calculateRowQuota`）。
  5. 固定 `textSize = 150f` + `x = 20f` + 默认 `Align.LEFT`（`:171-174`）⇒ **长标题被 660px 裁掉**、
     短标题偏左不居中。
  6. 全字同色平涂 + `Plus` 高 alpha（`:256`）⇒ 粒子密集处**过曝死白**，无纵深。

- **优化（P = 性能，全部为净降耗；Q = 观感，全部零额外 draw/分配）**：
  - **P1 · 死亡改重生（修缺陷 1）**：`updateAttract` 里 `LIFE <= 0` 时**不 `removeAt`**，
    改为把该粒子**从画布外缘随机位置重新投放并复位 `LIFE = 1f`**
    ⇒ `count` 恒 == `capacity`，`i → targets[i]` **永不错位**，文字稳定且有轻微流动感。
    ⚠️ 这是 `ParticlePool` 的**共享**方法（E19 专用，其余 4 个使用方走 `update()`）⇒ 改动不影响 E14/E08/E09。
  - **P2 · 350 × `drawCircle` → 2–3 × `drawPath`（§G12 / §15.3.2 #15）**：
    E19 的所有粒子**半径与颜色完全相同**（`radius = 2.4 + pulse*2.6`、`color = palette.accent`、
    `alpha = 0.35 + energy*0.5` —— 三者都只依赖帧级标量）⇒ 可**像素级等价地**合并：
    `dotPath`（`moveTo` + 4 段 `cubicTo`，零分配）+ `N × addPath(dotPath, Offset(x, y))` + **1 次 `drawPath`**。
    ⇒ **每帧 draw 350 → 1（−99.7%）**，**`Rect` 分配 0**，hwui 崩溃风险消除。
    ⚠️ **一处刻意的观感变化**：350 次独立 `Plus` 会在重叠处**累加**，合并成 1 条 Path 后重叠只算一次
    ⇒ 密集区不再"曝白"。**这正是缺陷 6 想要的**，但要作为"有意的观感变化"登记（§R10）。
  - **P3 · 采样 `getPixel()` → 逐行 `getPixels()`（§G11）**：`IntArray(bmpW)` 成员缓冲，
    JNI **16,280 → 220**（`bmpH/step` 次）；耗时 8–20 ms → **< 0.5 ms**。
  - **P4 · 照搬 E23 的按行配额采样（修缺陷 4）**：两遍结构 + `calculateRowQuota`
    （`LyricsDotMatrixRenderer.kt:832-841`）⇒ 粒子**垂直方向完整覆盖字形**。
    P3 之后两遍的额外成本可忽略。
  - **P5 · `step` 3 → 1（修缺陷 5 的"漏"）**：P3 之后采样已 < 0.5 ms，
    `step = 1` 仍在 1–3 ms 内；`poolCap = 350` 的"填满即停"逻辑保留
    ⇒ **候选点数 ×9，但为凑满 350 点所需扫描的位置数基本不变** ⇒ 近乎免费。
  - **Q1 · 文本测量 + 居中（修缺陷 5）**：`sample()` 里先 `measureText` 定
    `textSize = min(150f, (bmp.width - 40) / (measureText(150f) / 150f))`，
    并改 `textAlign = CENTER` + `x = bmp.width / 2`。**采样期 1–2 次调用，不在每帧路径**。
  - **Q2 · 按墨迹包围盒适配缩放**：P3 已经在扫像素数组 ⇒ **同一趟循环**顺手记
    `minX/maxX/minY/maxY`（4 次 `min`/`max`，零额外成本），
    把 `:226-228` 的"固定 0.78 宽度比"改成"按墨迹长宽比适配画布 78%×50% 且不超出"，
    居中改为按墨迹中心 ⇒ **任意长度标题都完整、居中、大小一致**。
  - **Q3 · 内/外圈双 Path 出纵深（用 P2 省下的 349 次 draw 预算）**：按"距墨迹中心的归一化距离"
    分 2 组，各 1 条 `Path`（**共 2 次 draw**）：内圈 `accent` 提亮 + `alpha +0.10`；
    外圈 `accent × 0.62` + `alpha −0.10` ⇒ 字有**中心亮、边缘暗**的体积感。
  - **Q4 · `Plus` 只留给高亮子集（修缺陷 6）**：主体 2 条 Path 用 `BlendMode.SrcOver`；
    再对"内圈且 `pulse > 0.5f`"的子集另开 1 条 Path 走 `Plus` ⇒ **共 3 次 draw**。
    亮部仍有溢出辉光，但不再整片死白。
  - **Q5 · 后处理**：`drawVignette(0.46f)` + `drawGrain(seq, 0.030f)`（`OverlayFx.LITE`，2 次 draw）。
  - **Q6 · `sectionEnergy` 接入**：段落级缓慢色温漂移（复用 §G10 的 `AudioSmoother`），**零 draw 成本**。

- **成本账**：

  | 项 | 现状 | 改造后 |
  |---|---|---|
  | 每帧 `draw` 调用 | **350**（`drawCircle`，含 hwui 崩溃风险） | **3 + 1 光晕 + 2 后处理 = 6**（−98%） |
  | 每帧 `Rect` 分配 | 0 | **0** |
  | 采样 JNI | **16,280** | **220** |
  | 采样耗时 | 8–20 ms | **< 0.5 ms** |
  | 粒子存活 | **250 帧后归零 ⇒ 永久空白** | 恒 350（重生） |
  | 采样点分布 | 只在字形上部几行 | 全字形均匀（E23 同款） |

- **验收**：见 **V16**（§11.2）—— 必须含"**连续播放 5 分钟画面不消失**"这一条（对应缺陷 1）。

#### B6 · E20 等离子流场 `PLASMA_FLOW`（`UltraRenderers.kt:156-256`）

- **问题**：噪声网格 16×9 极粗（`:163-164`）⇒ 流场呈块状；无等离子底色纹理
  （`:249-250` 只有 1 个纯色圆）；粒子纯色圆点（`:239-245`）。
- **优化**：① 网格 16×9 → **24×14**（噪声更新每 3 帧一次，`:214-221` 成本
  +21%，可接受）；并在 `sampleFlow` 内加 1 次 **fbm 双倍频**（`noise + 0.5*noise2`）
  —— 需第二个 `FloatArray`；② **等离子底色**：叠 1 层 `ProceduralTexture` 的
  等离子 tile（3 通道噪声合成，`alpha = 0.16 + energy*0.10`）+
  中心径向渐变；③ 粒子加**按速度方向的拉长**（`drawOval` 长轴 = 短轴 × 1.8）；
  ④ 后处理 `drawVignette(0.48f)` + `drawGrain(seq, 0.030f)`。
- **验收**：流场**无块状**（运动轨迹连续）；背景有**流动的等离子纹理**；
  粒子呈**短条**而非圆点。

#### B7 · E23 歌词点阵 `LYRICS_DOT_MATRIX`（`LyricsDotMatrixRenderer.kt`，采样 `:184-355`、绘制 `:394-613`、加点 `:617-706`）

> **用户需求（2026-09-29）**：**这一套以性能优化为主**；画面优化**不得增加性能消耗**。
> 因此本节顺序是 **先砍掉"为了省开销而牺牲质量"的三条红线，再用腾出来的预算做观感**。

**⛔ 三条性能红线（必须先修，否则后面每一条优化都会被成本否决）**

| # | 现状（`file:line`） | 实测/推算成本 | 根因 |
|---|---|---|---|
| **P0-1** | `:698` + `:703` 每点 `addOval(Rect(...))`；core 1 个 + glow（`tier >= 1`）1 个 | `cap = 3600` × 2 行 = **7200 点** ⇒ **每帧最多 14,400 个 `Rect` 分配**（≈ 460 KB/帧 ⇒ **27 MB/s 垃圾**）+ 14,400 次 native `Path.addOval` | `Rect` 是 `data class`，**不可复用**（§G12） |
| **P0-2** | `:277`（第一遍数每行像素）+ `:300`（第二遍按配额抽取）**两遍全图 `getPixel()`** | `2 × ceil(bmpW/step) × ceil(bmpH/step)`；1080p 典型（`fontSize ≈ 108`、`textWidth ≈ 1600` ⇒ `step = sqrt(108×1600×0.20/3600) = 3.1 → 3`、`bmpW ≈ 1840`、`bmpH ≈ 173`）⇒ **≈ 71,000 次 JNI ≈ 35–70 ms**，且 `sampleLine()` 在 `draw()` 内调用（`:501`）⇒ **每次歌词换行卡 2–4 帧** | 把 `getPixel()` 当成"唯一可用"（§G11） |
| **P0-3** | `:636-705` 循环内重算**全局量** + 死代码 | `sin(globalT * 2.0f)`（`:683`）与 `amp`/`bassCue`（`:690-691`）**每个点算一次** ⇒ 冗余 ≈ **28,800 次浮点运算/帧**；`phaseVal`（`:641`）**从未被使用** ⇒ 7200 次无谓数组读；`textLen`（`:626`）是**未使用参数**；`arr[o + SIZE]` 恒为 `1.0f`（唯一写入点 `:307`）⇒ 7200 次读常量 | 历史迭代遗留 |

**性能优化（P1–P6，按收益排序）**

- **P1 · 消除 `Rect` 分配（对应 P0-1，§G12）**
  - 建 **`dotPath` 池**：`onEnter` 里 `new` 出 `3 tier × 2 层 × R 半径档` 个 `Path`（**R = 6**，
    共 36 个；R 是唯一需要调的质量旋钮，见下"精度"）。每帧 `rewind()` 后各写
    **`moveTo` + 4 段 `cubicTo`**（Bézier 圆，`k = 0.5523f`）—— 全 float 参数，**零分配**。
  - 每个点改为：算出 `size` → **量化** `bucket = (((size - rMin) / (rMax - rMin)) * (R - 1)).roundToInt()`，
    然后 `paths[tier * 2 + 层].addPath(dotPath[tier][层][bucket], Offset(px, py))`。
  - ⚠️ **`Offset` 是 `value class` ⇒ `Offset(px, py)` 不分配**（§15.4-A24），这是本方案成立的前提。
  - ⛔ **不要**用 `Canvas.drawRawPoints(PointMode.Points, FloatArray, Paint)` 替代：
    它的 Android 实现是 `while (…) internalCanvas.drawPoint(x, y, paint)` 的**逐点循环**
    （`AndroidCanvas.android.kt:373-381`）⇒ 编译期像"批量"，运行期仍是 7200 个 native 绘制指令，
    **hwui region 崩溃风险（§R3）一点没降**（§15.4-A26）。
  - **收益**：`Rect` 分配 **14,400 → 0**；每帧 JNI `14,400 → 7200(addPath) + 216(重建 dotPath)`。
  - **精度**：`R = 6` 时半径最大误差 = 档宽/2 = `rMax/12` ≈ **8%**（`rMax` 见下）；
    肉眼不可辨。若真机放大截图发现点径"跳档"，把 `R` 提到 12（代价仅 +216 次 JNI）。
    `rMax = 1.0f × breath² × bassPulse × 1.25f`（`baseSize` 恒为 1.0，见 P0-3）。
- **P2 · 采样改逐行批读（对应 P0-2，§G11）**
  - 把两遍里的 `b.getPixel(x, y)` 换成**每行一次**
    `b.getPixels(rowBuf, 0, bmpW, 0, y, bmpW, 1)`（`rowBuf` 是成员 `IntArray(bmpW)`，跨次复用），
    行内改为 `rowBuf[x]` 纯数组下标访问。**两遍结构保留**（它是正确的，见下 Q1），
    但每遍的 JNI 从 `bmpW/step` 次降到 **1 次**。
  - **收益**：JNI **≈ 71,000 → 2 × ceil(bmpH/step) ≈ 116**（−99.8%）；
    耗时 **35–70 ms → < 1 ms**；额外内存仅 **`IntArray(bmpW)` ≈ 7 KB**（`maxRows ≈ 58` 的 `IntArray` 已在用）。
  - ⛔ 不要整图读成 `IntArray(bmpW * bmpH)`（≈ 1.4 MB 常驻，且 API 22–25 上大数组更易触发 GC）。
- **P3 · 把全局量提到循环外（对应 P0-3）**
  - 在 `addLineToPaths` 入口（或 `draw` 里算好传参）算一次：
    `sinB = sin(globalT * 2.0f)`、`bassCue = frame.bass.coerceIn(0f, 1f)`、`amp = 2.2f * (0.35f + bassCue * 2.2f)`、
    `pulse = frame.pulse`；循环内只留**真正逐点**的项（`localX` 相关的 `sin`）。
  - **收益**：每帧省 **≈ 14,400 次 `sin`** 与 **≈ 14,400 次乘加**（7200 点 × 2 处）。
    ⛔ 注意 `breath` 里含 `brightness`（逐点），只能把 `sinB` 提出来，**不能整条提前**。
- **P4 · 删死代码（对应 P0-3）**
  - 删 `val phaseVal = arr[o + PHASE]`（`:641`，零引用）；
    删 `textLen` 形参（`:626`）与两处实参（`:557`/`:582`）；
    `arr[o + SIZE]` 恒为 `1.0f` ⇒ 删该槽（`STRIDE` 8 → 7）或至少删读取。
  - ⚠️ `STRIDE` 变更要同步 `:637` 的 `o = i * STRIDE`、`onEnter` 的 `FloatArray(cap * STRIDE)`、
    `:307` 的写入、`initCoalesce`/`updateLine*` 里的偏移常量 —— **建议本轮只删读取，不动 `STRIDE`**（风险更低）。
  - **收益**：省 7200 次数组读 + 一处未使用参数。
- **P5 · `p.reset()` → `p.rewind()`**（`:543` `for (p in paths) p.reset()`）
  - `rewind()` **保留内部数据结构**供快速复用（`Path.kt:220-228`），`reset()` 会丢弃。
    每帧 6 条 Path × 7000+ 段，这是实打实的差异。**零风险**（语义等价）。
- **P6 · 采样变快之后，把 `step` 降到 1（这一条是"性能换质量"，顺序必须在 P2 之后）**
  - `:250-253` 的 `idealStep = sqrt(estPixels / cap).coerceIn(1f, 3f)` 是为了**限制采样耗时**
    而故意加粗的；P2 之后采样耗时已 < 1 ms ⇒ **`coerceIn(1f, 1f)`（恒 1）**。
  - ⛔ **点总数不变**（仍 `cap = 3600`/行）⇒ **每帧绘制成本完全不变**，只是同样 3600 个点
    **覆盖更完整**（`idealStep` 变粗正是"点阵有空洞/笔画断"的直接原因）。
  - ⚠️ 采样循环次数会上升（`bmpW × bmpH` 而非 `/9`），但已全是数组下标 ⇒ 仍在 **< 3 ms**。
    若实测 > 5 ms，退回 `step = 2`。

**零成本画面优化（Q1–Q4；每一条都不增加 draw 次数、不增加分配）**

- **Q1 · 保留并明确"按行配额"（这是本项目已验证的正确算法，⛔ 不要退回行优先截断）**
  - `:257-260` 的注释已经写明：**不能**按行从上往下扫、满了就 `break`（会丢字形下半部）。
    E23 现在用的是"第一遍数每行有效像素 → 按 `cnt × cap / total` 分配每行配额 → 第二遍等距抽取"
    （`calculateRowQuota`，`:832-841`）。**这是对的，P2 只换读取方式、不动这个结构**。
  - ⚠️ 反过来：**E19 至今还是行优先 + `n >= cap` 提前 break**（`ParticleRenderers.kt:178-188`）
    ⇒ 粒子只覆盖文字上部少数几行。**E19 应照搬 E23 这一段**（§B5）。
- **Q2 · 亮档去 `Plus` 过曝（零成本）**
  - 现状 `:609-612` 亮档 `alpha = 0.18 + pulse*0.1`（glow）+ `0.80 + pulse*0.18`（core）
    ⇒ core 峰值 **0.98**，叠 3 档 `BlendMode.Plus` ⇒ 演唱字与相邻字**糊成一片白**（§G8 / §R5）。
  - 改：亮档 core `alpha = 0.62f + pulse * 0.14f`（峰值 0.76），把省下的亮度**给"外辉光"**
    —— 即 `paths[5]` 的 `alpha` 从 `0.18` 提到 `0.26`，并让它的半径档**比 core 大 2.2×**（已是）。
  - 效果：亮部**仍是亮的，但有边界**（"发光"而不是"曝白"）。**draw 次数不变（仍 6 次）**。
- **Q3 · 4 档亮度（零成本，只是改阈值）**
  - 现在 `:676-680` 的 `tier` 只有 3 档（`> 0.88` / `> 0.45` / else）。
    改成 4 档只需**多切一个阈值**并把 `paths` 从 6 条扩到 **8 条**（4 tier × 2 层）
    ⇒ draw 次数 **6 → 8**（仍远低于 §7.4 的预算），换来"待唱 → 临近 → 演唱中 → 刚唱过"
    四个明度层次，**卡拉OK 的"推进感"显著增强**。
  - ⚠️ 这会让 P1 的 `dotPath` 池从 36 个涨到 **48 个**（4 × 2 × 6）。**收益/代价都小，列为可选**。
- **Q4 · 后处理（`OverlayFx.LITE`，2 次 draw）**：`drawVignette(0.44f)` + `drawGrain(seq, 0.026f)`。
  ⚠️ 这两次 draw 是**新增**的；E23 已用 6 次，加后为 8 次 —— 仍在本方案 draw 预算内（§7.4）。

**成本账（1080p / `cap = 3600` 每行 / 两行都有歌词）**

| 项 | 现状 | 改造后 | 变化 |
|---|---|---|---|
| 每帧 `Rect` 分配 | **≤ 14,400** | **0** | −100%（GC 抖动消除） |
| 每帧 native `Path` 追加 | ≤ 14,400（`addOval`） | 7,200（`addPath`）+ 216（重建 dotPath） | −48% |
| 每帧 `sin` 调用 | 14,400 | ~7,200（只留逐点的那个） | −50% |
| 每帧 `draw` 调用 | 6 | 6（Q4 后 8） | 持平 / +2 |
| 每帧数组读（死代码） | 7,200 | 0 | −100% |
| **采样 JNI（每次换行）** | **≈ 71,000** | **≈ 116** | **−99.8%** |
| **采样耗时（每次换行）** | **35–70 ms（卡 2–4 帧）** | **< 1 ms** | **−97%+** |
| 采样额外内存 | 0 | `IntArray(bmpW)` ≈ 7 KB | +7 KB |
| 点阵覆盖完整度 | 受 `step = 3` 限制 | `step = 1` | **提升（P6）** |

- **验收**：见 **V18**（§11.2）—— 除观感判据外，**必须**含"换行时无可见卡顿"与
  "连续播放 10 分钟帧耗时无周期性尖峰（GC 抖动消失）"两条**性能判据**。

#### B8 · E25 催眠 `HYPNOTIC_FUNCTION`（`HypnoticFunctionRenderer.kt:342-538`）

- **问题**：网格/坐标轴同色单线（`:623-664`）；曲线无沿法线的厚度感（`:428-429` 双层描线）；
  无背景纹理。
- **优化**：① 网格分 3 档明度（主刻度 / 次刻度 / 细网格，`alpha 0.55/0.30/0.14`）；
  ② 曲线改"双层 + 法线明暗"：主线（`alpha 0.9`，`width 2.2f`）+ 上方偏移 0.8px 的
  高光线（`towardWhite(mainColor, 0.6f)`，`width 0.9f`）⇒ 曲线像"有厚度的绳"；
  ③ 叠 1 层**方格纸纹理** tile（`alpha 0.10`）—— 与"数学函数"气质匹配；
  ④ 后处理 `drawVignette(0.44f)` + `drawGrain(seq, 0.028f)`。
- **验收**：曲线有**受光侧**（不再是一条平色线）；网格有**三级明度**；有纸张纹理。

#### B9 · E34 分形 `FRACTAL_TREE`（`BatchFourRenderers.kt:1334-1565`）

- **问题**：枝干单色描边（无明暗/无锥度渐变）；无叶/无花/无粒子；无背景纵深。
- **优化**：① **锥度**：递归时每级 `strokeWidth` 按 `0.72f` 递减（若已是则确认），
  并让颜色随层级**向亮端插值**（顶梢更亮 = 受光）⇒ 立刻有"生长感"；
  ② **叶/花**：在最末级节点按 `spectrum` 分桶画 3 档大小的**叶形**（椭圆，
  长轴 = 短轴 × 2.2，按 `LIGHT_ANGLE_DEG` 定向），合并为 3 条 `Path`；
  ③ **背景纵深**：径向纵深 + 星野/微粒 tile；
  ④ 后处理 `drawVignette(0.48f)` + `drawGrain(seq, 0.030f)`。
- **验收**：枝干**越往梢越细越亮**；末级有**叶**（不是光秃秃的线）；
  背景有纵深。

#### B10 · E35 光轴 `LIGHT_BEAMS`（`BatchFourRenderers.kt:1566-…`）

- **问题**：光束是纯色多边形 + `Plus`（硬边光柱）；无体积雾/无尘埃；无镜头光斑。
- **优化**：① **光束改渐变**：每个光束多边形填充改为
  `Brush.linearGradient`（起点亮 `alpha 0.42` → 终点透明）——
  ⛔ `Brush` 需按光束缓存（光束数固定，可在 `ensureLayout` 时建好，
  ⚠️ 但 `Brush.linearGradient` 的 `start/end` 依赖画布尺寸 ⇒ 按 `(w,h)` 缓存，
  与 `RadarGridRenderer.ensureBrush`（`:64-75`）同范式）；
  ② **体积雾**：叠 1 层 `ProceduralTexture` 的雾 tile（`alpha 0.12`，
  随 `sectionEnergy` 缓慢漂移）；③ **尘埃**：加 40–60 个极小亮点（1–1.6px，
  `alpha 0.20–0.45`，`Plus`），沿光束方向缓慢漂移 —— 合并为 3 条 `Path`；
  ④ **镜头光斑**：在光束交汇处加 1 组"光晕 + 六芒"（1 次 `shadeBrush` + 3 条 `drawLine`）；
  ⑤ 后处理 `drawVignette(0.50f)` + `drawGrain(seq, 0.030f)`。
- **验收**：光束**有明暗渐变**（不是硬边色块）；可见**漂浮尘埃**；交汇处有**镜头光斑**。

### 批次 C · 精修级（7 套 · 近期已重做，缺口 ★）

> 这 7 套在 2026-09-25 ~ 09-28 刚重做过（见 §2.2），**结构已经对**，
> 只做"补光 + 补后期 + 统一参数"，**不重写**。目标是让它们与批次 A/B 改造后的观感**一致**。

#### C1 · E29 轨道（太阳系）`ORBITAL_RINGS`（`BatchTwoRenderers.kt:63-632`）

- **已做得好**：深度排序遮挡（`:305-329`）、近大远小（`:400-403`）、太阳 3 层光晕 + 日冕、
  行星斜上高光（`:449-454`）、地球陆地 / 木星条纹（`:430-445`）、土星环前后半弧遮挡（`:423/456, 495-516`）。
- **可改进**：
  1. **行星球面光照**（G2 ① + G3）：现在 `drawCircle` 纯色 + 单点高光（`:425/450`）
     ⇒ 改为 1 次 `drawCircle(brush = Shading2D.shadeBrush(center, r, base, contrast = 0.55f))`，
     `brush` 中心**沿主光方向偏移 0.35r**（这样高光随行星位置变化，解决"高光钉死"）；
     再叠 1 条**昼夜明暗线**（垂直于光向的 `Path`，`alpha 0.30`）——
     这是"球体感"的关键。
  2. **边缘光 rim**：行星背光侧边缘 1 条 `alpha 0.22` 的亮弧（`:449` 附近）
     ⇒ 立刻有"大气散射"感（地球尤其明显）。
  3. **土星环分层**：`:495-516` 的单色环改为 **3 档 alpha + 1 条环缝**
     （中间留 8% 空隙）—— 成本不变（同一条 `Path` 拆 2 条）。
  4. **轨道线辉光**：`:296-301` 单色椭圆 → 加 1 层 `alpha 0.10`、宽 3× 的同形椭圆。
  5. **补后处理**（这是最大缺口）：`drawVignette(0.48f)` + `drawGrain(seq, 0.030f)` +
     `drawScanlines` ❌ 不需要（太空题材不加扫描线）。
- **验收**：行星有明显**球面明暗**（放大可见亮面→暗面过渡）；高光位置**随行星公转位置变化**；
  土星环**有环缝**；画面四角有暗角。

#### C2 · E33 齿轮 `CONCENTRIC_GEARS`（`BatchFourRenderers.kt:107-1321`）

- **已做得好**：随机三级啮合布局、参数化齿形 + 轮毂/辐条/轮缘、双层描边、
  径向纵深（`:937`）、暗角（`:944`）、环境光环（`:950`）、颗粒 tile（`:985-999`）、
  星野（`:1034-1052`）、啮合火花（`:1293-1316`）、`Plus` 合成。
- **可改进**（**核心是"金属感"，目前完全缺失**）：
  1. **齿廓法线明暗**（G2 ①）：`:1238` 的单色描边改为**按齿廓点的法线**分两侧 ——
     左半齿廓 `towardWhite(color, 0.55f)`、右半齿廓 `darken(color, 0.30f)`。
     ⚠️ 齿轮顶点已是参数化生成（`buildGearVerts`），可在**生成期**就把顶点按法线分成
     两个 `Path`（`lightPath` / `darkPath`），`draw` 内只 `drawPath` ×2 ⇒ **零额外计算**。
  2. **齿面高光扫过**（G3）：每个齿轮在**齿顶圆**上叠 1 段弧（角度 = 该齿轮当前转角 +
     光向偏移，跨 40°），`alpha 0.22`，`width 2.4f` ⇒ 齿轮转动时高光沿齿面移动，
     这是"金属"最强的视觉线索。
  3. **AO / 接触阴影**（G4）：每个齿轮在**下一个啮合齿轮方向**的背面加 1 层
     偏移暗弧（`alpha 0.20`，`width 4f`）—— 让齿轮组之间有"相互遮挡"的层次。
  4. **轴心改方向性 specular**：`:1282-1287` 的同心硬边圆 → 1 次
     `drawCircle(brush = shadeBrush(轴心沿光向偏移 0.3r, ...))` + 1 个
     沿光向偏移的高光小点（`specular` 指数 24）。
  5. **参数统一**：把齿轮自己的 vignette/grain 参数（`:944` / `:985-999`）
     换成 `OverlayFx` 的共享参数，保证与其他 27 套一致。
- **验收**：齿轮齿廓**左右两侧明暗不同**（放大可辨）；转动时**高光沿齿面移动**；
  齿轮之间有**遮挡阴影**；轴心高光**方向性**（不是同心圆）。

#### C3 · E37 分子 `MOLECULE`（`MoleculeRenderer.kt:317-538`）

- **已做得好**：原子/键/键上光点、`nativeCanvas` 分子式渲染 + 下标修正、深度分层。
- **可改进**：
  1. **原子球面光照**（G2）：`:476-490` 的原子 `drawCircle` 纯色 → 改为
     `shadeBrush` 径向渐变（中心沿光向偏移 0.3r），并加 1 个
     `towardWhite(color, 0.75f)` 的小高光点（半径 = `r*0.28`）⇒ 原子从"圆片"变"球"。
  2. **辉光改径向**（G5）：`:513-515` 的 3 层同心圆 → 1 次
     `drawCircle(brush = shadeBrush(...))`（中心亮 → 1.6r 透明）；
     ⛔ 若必须保留"多层"观感，改为 2 层 + 径向渐变，不要 3 层同心。
  3. **键的明暗**：`:454-459` 的双线键（`ax±ox`）改为"上线亮 / 下线暗"
     （`towardWhite` / `darken`），形成"化学键的立体感"。
  4. **分子式光晕**：`nativeCanvas` 画分子式（`:492`）后叠 1 层同位置
     `alpha 0.18` 的模糊副本（用 `Paint` 的 `setShadowLayer` 或偏移重绘 3 次）
     ⇒ 文字有"发光"感（⚠️ `setShadowLayer` 需硬件加速，API 22 电视上**可能不生效**，
     降级为偏移重绘）。
  5. **补后处理**：`drawVignette(0.46f)` + `drawGrain(seq, 0.028f)`。
- **验收**：原子**有球面明暗 + 高光点**；辉光**无同心台阶**；键有**上下明暗**；
  分子式有发光感。

#### C4 · E38 怀旧 `VINTAGE_TV`（`VintageTvRenderer.kt:286-326`）

- **已做得好**：扫描线、噪点、滚动暗带、RGB 色差（`ColorFilter`）、vignette、
  圆角屏面、胶片孔 + 滚动、OSD、反色文字。
- **可改进**（**这一套已经是全库质感最好的，优化方向是"更真 + 更省"**）：
  1. **扫描线改 tile 平铺**（G7，**提质 + 提速双赢**）：`:331-352` 的 **240 次
     `drawLine`** → 预生成 1 张 `1 × periodPx` 的 `ImageBitmap`，
     用 `Brush` 的 `TileMode.Repeated` 一次 `drawRect(brush=)`。
     收益：240 次 draw → 1 次，且**消除摩尔纹**（tile 的亚像素采样比整数像素线更柔）。
  2. **噪点改真 grain**（G1）：`:357-383` 的稀疏方块 → `ProceduralTexture` 的
     8 张 128×128 grain tile 循环（`seq` 索引），叠加 `BlendMode.Overlay`，
     `alpha 0.06` ⇒ 真正的"胶片颗粒"而非"雪花点"。
  3. **真通道色差**：`:511-531` 现在是**整层 `ColorFilter` tint**（伪色差）——
     若采纳 §十三 裁决项 2（HIGH 档开离屏），改用 `OffscreenFx.drawChroma(1.2f)`；
     否则保留现状但把偏移量随 `energy` 调制（`0.6 → 1.8px`）。
  4. **桶形畸变**（G7 / 参考 #5 #6）：把整屏内容做轻微桶形/枕形畸变
     （`r' = r × (1 + k1·r²)`，`k1 ≈ 0.012`）—— ⛔ 真畸变需离屏；
     **降级方案**：对"胶片孔层"与"圆角遮罩层"做轻微内缩（`scale 0.985`）即可
     产生"屏幕是凸的"的错觉，成本 0。
  5. **磷光余晖**（参考 #5 的 `halation`）：把歌词/OSD 层的亮度做 1 层
     `alpha 0.12`、偏移 1.5px 的模糊副本 ⇒ 荧光屏"拖影"。
  6. **⛔ 修潜在缺陷**：`:600-613` 的 vignette `Brush` **只按 `w` 缓存**
     （`brushW == w` 判定）—— 高度变化时不重建，竖屏/窗口尺寸变化会导致暗角形状错。
     改为按 `(w, h)` 双键缓存。
- **验收**：扫描线在 1080p / 4K 下**无摩尔纹**；颗粒是**不规则胶片颗粒**（不是方块雪花）；
  竖屏与横屏切换后**暗角形状正确**；帧耗时**不高于改造前**（`dumpsys gfxinfo`）。

#### C5 · E39 照片墙 `PHOTO_WALL`（`photo/PhotoRenderer.kt:87-…`）

- **已做得好**：43 种转场、Ken Burns、音频呼吸、双缓冲、`CROP/FIT` 适配、人脸过滤。
- **可改进**（**照片墙的"质感"= 照片本身的呈现质量**）：
  1. **停留期画面后期**（最大缺口）：`PhotoRenderer.draw` 末尾叠
     `drawVignette(0.34f)`（照片墙的暗角要**比频谱效果轻**，否则像"滤镜过重"）+
     `drawGrain(seq, 0.018f)`（颗粒也要轻）—— 让照片有"胶片/相纸"质感。
  2. **转场加光效类**（`photo/transitions/`）：现在 43 种转场多为几何变换
     （`SlideTransitions` / `ZoomTransitions` / `BlindsTransitions`），
     `LightTransitions.kt` / `EffectsP1Transitions.kt` 里已有少量光效。
     建议**新增 6 种"光效转场"**：闪光白闪、漏光（`linearGradient` 扫过）、
     光斑过曝、色差爆闪、暗角收缩、胶片刮痕。
     ⚠️ 新增转场须同步 `PhotoTransitionRegistry` + `PhotoTransitionId` +
     `PhotoTransitionRegistryTest`（**测试里有计数断言**，见 §三 #4 的同类问题）。
  3. **Ken Burns 加轻微色温漂移**：停留期让整体色温缓慢偏移 ±120K（用
     `ColorFilter` 的 `matrix` 或叠 1 层极淡暖/冷色，`alpha 0.04`）⇒
     "老照片/回忆"感。
  4. **转场期的运动模糊**：快速转场（`p < 0.35`）时叠 1 层 `alpha 0.12` 的
     上一帧偏移副本 ⇒ 运动感更真（⚠️ 需要上一帧缓冲，`PhotoRenderer` 已有 A/B 双图，
     可直接复用 `photoA` 做偏移重绘，**零新增缓冲**）。
- **验收**：停留期有**可见但不过重的**暗角与颗粒；新增光效转场在真机上**不卡**
  （LOW 档自动跳过）；转场期有**轻微运动模糊**。

#### C6 · E40 DNA 双螺旋 `DNA`（`DnaRenderer.kt:467-681`）

- **已做得好**：Catmull-Rom 中心路径、深度 z 排序遮挡（`:560-574`）、
  4 桶线宽/alpha（`:605/612`）、3 层辉光（`:656-661`）、前景节点斜上高光（`:665`）、星野（`:670-681`）。
- **可改进**（**核心是"绸缎感"**）：
  1. **骨架沿法线明暗**（G2 ①）：`:605/611` 的单色 `drawLine` 按深度分桶，但**颜色恒定**
     ⇒ 改为：每条骨架段额外画 1 条**偏移 0.35×width、`towardWhite(color, 0.55f)`、
     `alpha 0.5`** 的平行线（即"受光的窄边"）。⚠️ 骨架段数量 = 螺旋点数 ×2，
     需确认总量（若 > 200 段，改为**按深度桶合并进 2 条 `Path`**：
     亮侧 `Path` + 暗侧 `Path`，零额外 draw 调用）。
  2. **背景径向纵深**（G9）：`:577` 的纯色 `drawRect` → 1 次
     `drawRect(brush = radialGradient)`（中心亮 12%，色相偏冷 8°）。
  3. **碱基对横档的发光**（G5）：`:660` 附近的横档 → 每根横档 1 层
     `shadeBrush` 柔光（`alpha 0.16`），或按 4 色分 4 条 `Path` 合并。
  4. **补后处理**（最大缺口）：`drawVignette(0.50f)` + `drawGrain(seq, 0.028f)`。
  5. **`specular` 方向化**：`:665` 的前景节点高光改为沿 `LIGHT_ANGLE_DEG` 计算
     （现在是固定"斜上"，实际已是左上，但需**随节点朝向微调**以配合螺旋角度）。
- **验收**：骨架有**受光窄边**（放大可辨明暗两色）；背景有**径向纵深**（不是纯色）；
  画面四角有暗角；节点高光方向与主光一致。

#### C7 · E41 世界 `WORLD`（`WorldRenderer.kt:582-702`）

- **已做得好**：12 层管线（`KDoc:52-67` 有完整清单）、径向纵深（`:1439`）、
  vignette（`:1446`）、大气渐变环（`:1453`）、grain tile（`:783-792`）、
  32 支城市光点 `Brush`（`:1481`）、双层描边（`:691-692`）、
  彗尾 5 段渐隐（`:975-981`）、涟漪 3 同心环（`:1015-1017`）、昼夜 96 色带（`:810-833`）。
  **这是全库技术最完整的一套**，是其他效果改造的**参考实现**。
- **可改进**：
  1. **陆地地形纹理**（G2 / G9）：`:679` 陆地是单一中性色填充 + 描边
     ⇒ 叠 1 层**极淡的地形明暗**：按纬度做 `linearGradient`（赤道略暖、两极略冷，
     `alpha 0.12`），并用 `ProceduralTexture` 的"大陆颗粒" tile 以
     **陆地 `Path` 裁剪**（`clipPath`）叠 `alpha 0.08` ⇒ 陆地不再"平"。
     ⚠️ `clipPath` 每帧有成本，建议**只在 HIGH 档**做。
  2. **球面光照错觉**（G3）：现在 `canvas` 有能量微缩放（`:663-667`），
     但陆地**没有球面明暗**。加 1 层**沿主光方向的 `linearGradient` 覆盖层**
     （`alpha 0.14`，从左上亮到右下暗）—— 2D 里制造"球"的最省手段。
  3. **grain tile 接缝**（G7 / 已有风险）：`:783-792` 的平铺用 `roundToInt`
     计算行列数，**尺寸非整数倍时会有接缝**。改为
     `Brush` 的 `TileMode.Repeated`（自动无缝）或 tile 尺寸取画布尺寸的整除数。
  4. **城市光点的 bloom**（G6）：`:1481` 的 32 支 `Brush` 已是渐变；
     可在 HIGH 档叠 1 层 `alpha 0.20` 的**放大小光斑**（`radius ×2.2`）⇒
     城市像"从太空看的地球夜景"。
  5. **vignette 中心跟随焦点**（G3）：`:1446` 的 vignette 中心固定在画布中心；
     可让其中心随 `sectionEnergy` 极缓慢漂移（±3% 画布）⇒ 画面"活"起来。
  6. **参数统一**：把本效果自己的 vignette/grain 参数换成 `OverlayFx` 共享参数
     （观感一致性），但**保留 12 层管线的私有部分**。
- **验收**：陆地**有明暗层次**（不是单色块）；整体有**球面感**；
  grain **无可见接缝**（放大截图四角检查）；城市光点在 HIGH 档有**外溢辉光**。

---

## 七、关键参数表

> ⛔ 实现时**必须用这里的数值**，不得写"约 0.4 左右"。所有数值集中在
> `fx/` 包内以 `const val` 暴露（便于调参与单测断言）。

### 7.1 后处理参数（`OverlayFx` / `OffscreenFx`）

| 参数 | 符号/常量名 | 建议值 | 取值范围 | 说明 |
|---|---|---|---|---|
| 暗角强度 | `VIGNETTE_STRENGTH` | `0.42` | 0.32–0.52 | 星座/雷达/世界可到 0.50–0.52；照片墙降到 0.34 |
| 暗角起始半径 | `VIGNETTE_INNER_R` | `0.62 × minDim` | 0.55–0.70 | 内圈完全透明 |
| 暗角冷偏 | `VIGNETTE_COOL_DEG` | `8°` | 0–14 | 边缘向冷色偏（色相偏移） |
| 颗粒强度 | `GRAIN_INTENSITY` | `0.030` | 0.018–0.036 | 照片墙 0.018；雷达/怀旧 0.034 |
| 颗粒 tile 尺寸 | `GRAIN_TILE` | `128 × 128` | 64–256 | 8 张循环（`seq and 7`） |
| 颗粒混合模式 | `GRAIN_BLEND` | `Overlay` | — | ⛔ 不用 `Plus`（会过曝） |
| 扫描线周期 | `SCANLINE_PERIOD` | `3 px` | 2–4 | tile 高度 = period |
| 扫描线暗度 | `SCANLINE_DARK` | `0.14` | 0.10–0.18 | 怀旧 0.16；阶梯 0.10 |
| 色差偏移 | `CHROMA_OFFSET` | `0.6 + energy × 1.2 px` | 0.4–2.2 | 仅 HIGH 档真色差 |
| 色差 alpha | `CHROMA_ALPHA` | `0.55` | 0.40–0.70 | — |
| bloom 阈值 | `BLOOM_THRESHOLD` | `0.72` | 0.62–0.80 | 亮度低于此不进 bloom |
| bloom 强度 | `BLOOM_STRENGTH` | `0.55` | 0.35–0.70 | — |
| bloom 半径 | `BLOOM_RADIUS_K` | `0.035 × minDim` | 0.025–0.05 | 三级降采样 |
| 离屏降采样 | `OFFSCREEN_DOWNSCALE` | `3` | 2–4 | ⛔ 不得为 1（TV 填充率） |

### 7.2 光照与材质参数（`Shading2D`）

| 参数 | 常量名 | 建议值 | 说明 |
|---|---|---|---|
| 主光方向 | `LIGHT_ANGLE_DEG` | `315°`（左上） | ⛔ **全库唯一**，所有高光由它推导 |
| 光向单位向量 | `lightDir` | `(-0.7071, -0.7071)` | 预算一次 |
| specular 指数 | `SPECULAR_SHININESS` | `24` | 金属 32、塑料 16、水 48 |
| specular 强度 | `SPECULAR_GAIN` | `0.55` | 乘到基色亮度上 |
| 边缘光强度 | `RIM_GAIN` | `0.35` | 背光侧轮廓 |
| 倒角亮侧 alpha | `BEVEL_LIGHT_ALPHA` | `0.55` | `bevelStroke` |
| 倒角暗侧 alpha | `BEVEL_DARK_ALPHA` | `0.45` | `bevelStroke` |
| 倒角宽度 | `BEVEL_WIDTH` | `1.6 px` | 随主体尺寸 ×`scale` |
| 接触阴影偏移 | `SHADOW_DROP_K` | `0.12 × r` | 沿光向反方向 |
| 接触阴影 alpha | `SHADOW_ALPHA` | `0.28` | 0.22–0.32 |
| 球面渐变对比 | `SPHERE_CONTRAST` | `0.42` | 行星 0.55、原子 0.48 |
| 球面高光偏移 | `SPHERE_SPEC_OFFSET_K` | `0.30 × r` | 沿光向 |

### 7.3 音频映射参数（`AudioSmoother` 与频段映射）

| 视觉参数 | 驱动信号 | attack | release | 说明 |
|---|---|---|---|---|
| 条长 / 振幅 | `spectrum[i]` | 直接取用 | — | 显示通道，已 gamma 压缩，**不再平滑** |
| 整体缩放 | `energy` | `0.25` | `0.08` | 全屏呼吸 |
| 段落级变化 | `sectionEnergy` | `0.02` | `0.02` | 8s 均值，**只用于配色温度/密度**（G10） |
| 周期性速度 | `bpm` | — | — | `rate = bpm / 1200f`（旋转基线） |
| 瞬态（鼓点强弱） | `bassRaw` | `0.45` | `0.10` | ⛔ **不用 `bass`** —— 它在鼓点时刻恒为 1.0（`AudioFrame.kt:36-44`） |
| 脉冲 | `pulse` | 直接取用 | — | 快起慢落包络，已由分析层提供 |
| 低频 | `bass` | `0.35` | `0.06` | 用于幅度（**不用于瞬态强弱**） |
| 中频 | `mid` | `0.30` | `0.06` | 用于中速运动 |
| 高频 | `treble` | `0.40` | `0.08` | 用于细节/碎裂/涟漪 |

> ⚠️ **统一约束**：所有相位必须 `phase += dt * rate` 累加（`dt` 钳 `0.1s`），
> ⛔ 严禁 `nowMs × 系数`（本项目 §10.176 / §10.191 的既有红线）。
> 涉及效果：`LiquidGridRenderer.kt:210-213`（**现存违规**，批次 A5 修）、
> `GalaxySpiralRenderer.kt:46`（帧率绑定，批次 B1 修）。

### 7.4 性能预算（1080p · 单帧额外 draw 调用上限）

| 档位 | 后处理额外 draw | 单效果总 draw 上限 | 备注 |
|---|---|---|---|
| LOW | 0 | ≤ 60 | 后处理全关 |
| MEDIUM | ≤ 3 | ≤ 120 | 暗角 1 + 颗粒 1 + 纹理 1 |
| HIGH | ≤ 8（含离屏 4） | ≤ 200 | 沿用既有"单帧 ≤200 独立绘制指令"约束（`AdvancedRenderers.kt:34-35`） |

---

## 八、单测门禁清单（10 类，含负向自证）

> ⚠️ **编号消歧（重要）**：本节的 `G1–G10` 是**门禁号**（Gate），与 **§四 的 `G1–G12`**（**共性缺口号**，Gap）
> **同字母不同含义**。§六 里所有 `（G2）`/`（G5）`/`（G9）` 形式的括注都指 **§四 的缺口**，
> 不是本节的门禁。⇒ **引用本节门禁时必须写「§八 G<n>」**（§一/§15.1.2/§15.6 已按此写法）。
> 📌 后续可考虑把本节门禁号改成 `GT1–GT10` 以彻底消除歧义（本次未改，避免动到既有交叉引用）。

> ⛔ 本项目规矩：**源码扫描型 / 契约型门禁必须有负向自证**（否则"0 违规"可能只是空转，
> 见 `PhotoRenderContractScanTest.kt:44-57` 的 6 条自证要求）。
> ⚠️ 单测护栏一律 `assertTrue(...)`（Kotlin `assert` 在测试 JVM 是空操作）。

| # | 测试类 | 断言（正向） | **负向自证**（缺一不可） |
|---|---|---|---|
| G1 | `FxBudgetTest` | `LOW→OFF` / `MEDIUM→LITE` / `HIGH→FULL`；`OFF` 时 `OverlayFx` 各方法**不产生任何 draw** | 把 `of()` 的分支对调（`allowFramebuffer→OFF`）必须判失败 |
| G2 | `Shading2DTest` | `lambert(315°) > lambert(135°)`（面向光源更亮）；`specular` 峰值出现在反射角；`rim` 在背光侧最大 | 把 `LIGHT_ANGLE_DEG` 改成 135° 后 `lambert(315°)` 必须**小于** `lambert(135°)` |
| G3 | `GrainDeterminismTest` | 同 `(seed, seq)` 生成的 tile **逐像素相同**；`seq` 差 1 时**不相同** | 去掉 `seq` 索引（恒用第 0 张）必须被判"不同 seq 出同图" |
| G4 | `ScanlineTileTest` | tile 平铺版与"旧 240 次 `drawLine`"版在**等距 12 个采样点**的亮度差 `≤ 1/255` | 把 tile 周期改成 4px 后必须**判失败**（说明门禁真的在比亮度） |
| G5 | `ProceduralTextureRecycleTest` | `release()` 后所有 tile 的 `asAndroidBitmap().isRecycled == true`；重复 `release()` 不抛异常 | 去掉 `recycle()` 只置 `null` 必须判失败 |
| G6 | `AudioSmootherTest` | 上升帧数 **少于**下降帧数（attack 快 release 慢）；`reset()` 后 `value == 0f` | 把 `attack`/`release` 对调必须判失败 |
| G7 | `FxCoverageScanTest`（**源码扫描**） | 所有 `Tier.BASIC`/`Tier.ADV` 渲染器（除 `PHOTO_WALL`）的 `draw` 内**至少调用一次** `OverlayFx.*`；豁免标记 `// Fx-exempt: 理由` 可放行 | ① 删除任一渲染器的调用必须判违规；② **空转断言**：扫描到的文件数 `> 0`、调用点数 `> 0`；③ 注释里写的示例代码不得被误判 |
| G8 | `EcgWaveformTest`（E24 心搏波形表 · **形态学门禁**） | ① `HB.size == 50` **且** `HB.size / 90f * 1000f < 800f`（心搏时长必须小于 `MIN_GAP_MS`，防"改了 `speed` 忘了重建表"）；② `HB.max() == HB[19] == 1.0f`（**R 峰落在显示列上、不被削平**）；③ `HB.min() == HB[22] == -0.28f`；④ `HB[9..15]` 与 `HB[25..33]` **全为 0**（PR 段 / ST 段是**等电位平线**）；⑤ **T 波列数（34–48）> QRS 列数（16–24）× 1.5**（窄尖峰 vs 宽圆峰）；⑥ T 上升支 `HB[34..40]` 单调不减、下降支 `HB[40..48]` 单调不增；⑦ 边界：`heartbeatAt(0) == 0f`、`heartbeatAt(-1) == 0f`、`heartbeatAt(HB.size) == 0f` | **把旧表（`HB_T`/`HB_V` 14 点 + 线性插值）喂进同一组断言**：④ 必须**判失败**（旧表 PR 段是 `+0.099→−0.105` 下坡、ST 段是 `−0.215→+0.175` 上坡）、⑤ 必须**判失败**（旧表宽度比 1.17 < 1.5）、② 必须**判失败**（旧表采样峰值 **0.897** ≠ 1.0）。三条都挂 ⇒ 证明门禁真的在看"波形形状"而不是空转 |
| G9 | `RadarSweepTest`（E30 真实 PPI · 扫掠判定与目标池） | ① 穿越判定：`crossed(0f, 0.5f, 0.25f)` **真**、`crossed(0f, 0.5f, 0.75f)` **假**；② **跨 0 回绕**：`crossed(6.0f, 0.3f, 6.2f)` 与 `crossed(6.0f, 0.3f, 0.1f)` **都真**，而 `crossed(6.0f, 0.3f, 3.0f)` **假**；③ 一帧扫满一圈（`cur − prev ≥ TAU`）⇒ 任意 `target` **都真**；④ **边界左开右闭**：`target == prev` **假**、`target == cur` **真**（既不重复点亮也不漏点）；⑤ 漂移：跑 100 帧后 `BEARING` **改变**（"下一圈换位置"），且 `RANGE` **始终**在 `[0.25, 1]` 内；⑥ **余辉跨圈**：`holdSec > 周期` ⇒ 连续 N 帧不点亮后 `BRIGHT` **仍 > 0**；⑦ **重生不减少**：`LIFE` 归零后 `BEARING ∈ [0,TAU)`、`RANGE ∈ [0.25,1]`、`BIN ∈ [0,bins)`，且**池内有效目标数不下降** | ① 把 `crossed` 换成"旧写法" `abs(cur − target) < 0.1f` ⇒ **② 必须判失败**（跨 0 回绕漏检）；② 去掉 `RANGE` 的 `bounce/clamp` ⇒ **⑤ 必须判失败**；③ 把 `holdSec` 改成 `0.1 × 周期` ⇒ **⑥ 必须判失败**；④ 把"寿命到 → 重生"改成"置零 / 移出池" ⇒ **⑦ 必须判失败**（E19 的"池耗尽 ⇒ 画面永久空白"坑） |

| G10 | `StaircaseMappingTest`（E32 全柱独立频段映射 · **映射门禁**） | ① **覆盖全频段**：三种画质下 `band[cols] == 64`、`band[0] == 0`、且 `band` **严格单调递增**；② **列列独立**：每列区间非空 ⇒ 命中**不同桶数 == `cols`**（LOW **20** / MED **28** / HIGH **36**），⛔ **不再是列数的一半**；③ **不重叠无空隙**：`Σ(band[i+1] − band[i]) == 64`；④ **感知分桶生效**：`PERCEPT_K > 1` 时前 8 桶（0–2.5 kHz，含全部基频）占列数 ≥ `cols / 4`（LOW ≥ 5 列），且**前段步长 < 后段步长**（低频更密）；⑤ **小信号可见**：`v == MIN_AMPLITUDE(0.02f)` ⇒ `steps >= 1`；`v == 0.01f` ⇒ `steps == 0`；⑥ **亮度与 `steps` 无关**：`blockAlpha(st = 5, steps = s)` 在 `s ∈ {1, 8, 24}` 三次调用**返回同一值**（"按绝对格位归一"） | ① 把边界表末位改回 `32`（`n / 2` 旧口径）⇒ **① 与 ③ 必须判失败**（覆盖不全 + 区间和 32 ≠ 64）；② 把映射改回"多列共用同一桶"（`src = i / half * (n / 2)`）⇒ **② 必须判失败**（实测命中不同桶 = 10/14/18，恰为列数一半）；③ 把 `level` 改回 `(st + 1f) / steps` ⇒ **⑥ 必须判失败**（同一 `st` 在不同 `steps` 下 alpha 变了 = 闪烁）。⚠️ 三条负向都必须**实测挂掉**再提交，否则门禁是空转（§八 开头规矩） |

**门禁命令与基线**：

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat testDebugUnitTest lintDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

- 基线：`testDebugUnitTest` **1177 例 / 115 类 / 0 失败**；`lintDebug` **0 Error**
- ⛔ 本方案预期测试计数**净增约 74–99 例**（**10** 个新测试类：`fx/` 7 类 ≈ 40–60 例 +
  `EcgWaveformTest` ≈ 12 例 + `RadarSweepTest` ≈ 10–13 例 + `StaircaseMappingTest` ≈ 12–14 例）；
  改完必须**更新本节的基线数字**，并确认 `VisualizerThemeTest` 的 6 处计数断言**未变**（28/27）。

---

## 九、风险、降级与红线

| # | 风险 | 触发条件 | 影响 | 缓解 / 降级 |
|---|---|---|---|---|
| R1 | **TV 填充率崩溃**（Android 5.1 / 弱 GPU） | 离屏层开在 LOW/MEDIUM 档 | 帧率跌破 20fps，观感比不优化更差 | ① `FxLevel.OFF` 硬门控；② 离屏降采样 ≥ 2；③ 上线前在电视上实测 `dumpsys gfxinfo` 帧耗时 |
| R2 | **离屏缓冲内存**（API 22–25 位图在 native 堆，靠 finalizer 延迟回收） | `onExit` 未显式 `recycle` | 每次切换效果泄漏数 MB（本项目已有两次教训：`MilkdropRenderer.kt:53-56`、`AdvancedRenderers.kt:146-150`） | ⛔ `onExit` 必须 `recycle()`；**§八 G5**（`ProceduralTextureRecycleTest`）护住 |
| R3 | **hwui region 合并 SIGSEGV** | 单帧独立绘制指令过多（历史在 320+ `drawCircle` 崩过，见 `AdvancedRenderers.kt:57-58`） | 应用闪退 | 一律走 `Path` 合并；§7.4 的 draw 上限是硬约束 |
| R4 | **零分配红线被破坏** | `draw` 内 `Path()` / `List` / lambda / `Brush` 新建 | 每帧 GC 抖动 → 卡顿 | code review 逐条对照；⛔ `withTransform` 会捕获 lambda（`BatchTwoRenderers` 已用参数方程替代）；`Brush` 必须按 `(w,h)` 缓存 |
| R5 | **`Plus` 叠加过曝** | 后处理层 + 既有 `Plus` 层叠加 | 死白 | `Shading2D.toneMap()`；G8 |
| R6 | **离屏采样在 `draw` 内同步执行**（E19 `ParticleRenderers.kt:210`、E23 `LyricsDotMatrixRenderer.kt:501`） | 若仍用逐像素 `getPixel()`，E23 采样 ≈ 71,000 次 JNI / 35–70 ms ⇒ **每次歌词换行卡 2–4 帧** | 观感明显劣化，且用户明确要求"不增加性能消耗" | ⛔ **必须先做 §G11 的逐行 `getPixels()` 批读**（JNI −99.8%、耗时 < 1 ms），再谈任何"提高采样密度"的改动。⚠️ 本条**原口径「`step` 3→2 若 > 20ms 则放弃」已作废** —— 那是建立在"只能用 `getPixel()`"这个错误结论之上的（§G11、§15.7 #9） |
| R7 | **`LiquidGridRenderer` 的 `timeMs` 违规修复引入回归** | 改为 `dt` 累加（A5 ①） | 波纹速度语义变化 | 单测断言"喂 `dt = 10s` 时相位推进不超过一帧步长"（沿用 §10.176 的判据） |
| R8 | **显示名/枚举变动破坏计数断言** | 若某套效果改显示名 | `VisualizerThemeTest.kt:103` 等断言失败 | 本方案**默认不改显示名**；若必须改，同步 `values/strings.xml` + `values-en/strings.xml` + 断言 |
| R9 | **照片墙新增转场破坏注册表测试** | 新增 6 种光效转场（C5 ②） | `PhotoTransitionRegistryTest` 计数断言失败 | 新增转场时**同步改测试断言**（§三 #4 的同类问题） |
| R10 | **观感"改坏了"无法客观判断** | 质感改动主观性强 | 返工 | ① 每批次**上机前截图存档**（改造前 / 改造后对比）；② §十一 的判据全部写成**可在截图上指认**的现象，不写"更好看" |
| R11 | **`dotPath` + `addPath` 在 API 22 弱 GPU 上的实际收益未实测** | §G12 的零分配批量圆点（E19/E23） | 若 `addPath` 的 native 拷贝比预期贵，可能"分配没了但更慢" | ① **先在 E19（350 点）试点**，用 `dumpsys gfxinfo` 对比改造前后帧耗时；② 达标（≤ 1.05×）再推广到 E23（7200 点）；③ 若不达标，E19 退回 350 `drawCircle`（本就零分配），E23 退回"只修 `Rect` 分配的一半"（glow 层不画） |
| R12 | **`IntArray` 采样缓冲常驻内存** | §G11 的逐行批读 | 误用整图读会常驻 ≈ 1.4 MB（API 22–25 大数组更易触发 GC） | ⛔ 一律**逐行读**（`IntArray(bmpW)` ≈ 7 KB）；code review 检查是否出现 `IntArray(bmpW * bmpH)` |
| R13 | **E32 提升 `STEPS` 后绘制指令数上升**（最坏 576 → **864** 次 `drawRect`） | §A11 第 0 条的 0.5（`STEPS` 16 → 24） | 弱 GPU 上帧耗时上升；极端情况逼近 hwui 的 region 合并开销（与 R3 同源，但 `drawRect` 比 `drawCircle` 轻得多，且同色同 alpha 的矩形会被合并） | ① 上机量 `dumpsys gfxinfo`（判据：帧耗时 ≤ 改造前 **+1 ms**）；② 超标则 `STEPS` 降到 **20**（576 → 720）；③ 再超标降到 **16** —— 此时"只有几个柱在动"**已经被 0.1/0.2/0.3/0.4 解决**（`STEPS` 只影响小信号的**可见度**，不影响"列列独立"）；④ ⛔ **绝不可回退"多列共用同一桶"** —— 那是根因，回退等于没修 |

**明确不做（范围守卫）**：

- ⛔ 不新增/删除效果（28 项不变）
- ⛔ 不改音频分析层（`SpectrumRepository` / `PcmSpectrumTap` / `AudioFrame`）
- ⛔ 不引入新依赖、不引入 GLSL/OpenGL、不用 AGSL（API 33+，本项目 minSdk 22）
- ⛔ 不改 `RenderContext.update(...)` 签名
- ⛔ 不做"每套效果各写一份后处理"

---

## 十、提交顺序（每步可独立验证）

| 步 | 提交内容 | 验收点 |
|---|---|---|
| S1 | 新增 `fx/` 6 个文件 + 7 个测试类（**零渲染器接入**） | 门禁绿；测试计数净增 40–60；**画面零变化**（重要：证明工具箱本身无副作用） |
| S2 | 接入批次 A 前 4 套（E03 / E05 / E07 / E12） | 上机看 4 套；draw 调用数不超上限；帧耗时 ≤ 改造前 |
| S3 | 接入批次 A 其余 7 套（E13 / E15 / E17 / E24 / E30 / E31 / E32）。⚠️ 其中 **E24 / E30 / E32 的「第 0 条 P0」**（波形真实化 / 真实 PPI 重建 / 全柱独立频段映射）**建议各拆成一个独立提交** —— 三者都是纯逻辑 + 表/池/映射驱动，互不依赖、可各自独立验证（详见 §15.6-S3 的逐步命令） | 上机看 7 套；`FxCoverageScanTest` 覆盖 A 批 11 套；三条 P0 各自的单测门禁（G8 / G9 / G10）全绿 |
| S4 | 接入批次 B 10 套 | 上机看 10 套；重点验 E18 反馈残像（Milkdrop）与 E20 等离子流场 在 HIGH 档不卡 |
| S5 | 批次 C 精修 7 套（E29 轨道 / E33 齿轮 / E37 分子 / E38 怀旧 / E39 照片墙 / E40 DNA 双螺旋 / E41 世界） | 上机看 7 套；重点验 E38 怀旧 扫描线无摩尔纹、E33 齿轮 齿面高光、E39 照片墙 光效转场 |
| S6 | （可选 · 需裁决项 2）`OffscreenFx` + `VisualizerStage` 接线 | HIGH 档 bloom 可见；LOW/MEDIUM 档**零额外成本**；电视上实测帧耗时 |
| S7 | 文档同步：`docs/technical-overview.md` 新增 `§10.N`（N 递增）、`CHANGELOG.md` 新增节（插到文件最前）、`README.md` 若涉及功能描述、本文件 §12 回填 | 复检：`§` 引用无失效、计数与表格行数吻合、围栏成对 |

> ⛔ 每步都必须**先复跑门禁再提交**；⛔ 改测试文件必须跑 `testDebugUnitTest`
> （`assembleRelease` 不编译 test 源码，坏文件会直接进 HEAD）。
> ⛔ 提交前先 `git log --oneline -5`（本项目有并发会话改同一工作区）。

---

## 十一、上机验收清单（可逐条勾 · 真机）

> 设备：电视 `192.168.0.114:5555`（Android 5.1.1 / SDK 22 / armeabi-v7a）+ 手机。
> ⛔ 上机一律 **release 包**；⛔ **不要自己往电视上装包**，产物就绪后告知用户安装。
> ⛔ **不要自动运行应用**，也不要为截图启动它。

### 11.1 通用（每批次都跑）

- [ ] **U1** 电视上连续播放 ≥ 30 分钟，无闪退、无 ANR
- [ ] **U2** 切换全部已改造效果，切换过程**无黑屏/无卡顿**
- [ ] **U3** `adb shell dumpsys gfxinfo com.nasmusic.tv` 帧耗时：改造后**不高于**改造前（同效果对比）
- [ ] **U4** LOW 档下后处理**完全不生效**（对照截图：LOW 档画面应与改造前一致）
- [ ] **U5** 竖屏（手机）下暗角形状**正确**（不是按宽度算的扁暗角）

### 11.2 逐效果判据（在截图上可指认）

- [ ] **V1** E03 隧道：近端环明显亮于远端环（≥3 档明度差）；远端**无同心台阶**
- [ ] **V2** E05 圆形频谱环：单根条**可辨亮侧/暗侧**；外圈**无 8 段接缝**
- [ ] **V3** E07 频率山峦：山脊**无锯齿**；5 层**有前后关系**；静音时**不是纯黑**
- [ ] **V4** E12 瀑布：**可数出 ≥8 个频段边界**；持续高频**底部不死白**
- [ ] **V5** E13 液态网格：**高处的线更亮**；顶点是**拉长反光**；静置 30 分钟**不冻结**
- [ ] **V6** E15 涟漪：单环**内暗外亮**；扩散中**变细**；`beat` 时中心**有光斑**
- [ ] **V7** E17 星座：亮星有**十字星芒**；连线有**两档粗细**；背景有**静态星野**
- [ ] **V8** E24 心跳：**波形形状（P0）**——① 放大可见 **QRS 是陡升缓降的窄尖峰、T 波是宽而低的圆钝峰**（两者宽度肉眼可辨，不再是两个等宽三角）；② **PR 段与 ST 段是水平直线**（不是上坡/下坡）；③ **P 波是圆钝小丘**（不是三角）；④ **R 峰顶不被削平**（对比改造前可见峰顶变高）。**观感**——⑤ 波形扫过后**有拖影**；⑥ 网格四角**暗于中心**；⑦ 无摩尔纹
- [ ] **V9** E30 雷达：**真实 PPI 行为（P0，必过）**——① **回波留在原地**（暂停播放后，扫线过去的目标仍亮着并**逐渐变暗**；相隔 1 s 两张截图**位置不变、亮度下降**）；② **下一圈换位置**（连续两圈同一目标回波**方位角差 ≥ 10°**）；③ **扫线匀速**（一圈耗时稳定 **3.9 ± 0.4 s**，**不随音乐变化**）；④ **余辉扇跟着扫线转**（相隔 1/4 圈两张截图，亮扇区跟着移动 —— 现状是静止亮块）。**观感**——⑤ 回波是**短弧**（宽约 9°）而非长弧/整圈，且**亮度可辨**（频谱强的更亮更粗）；⑥ 四角暗于中心；⑦ 无摩尔纹
- [ ] **V10** E31 折纸：折叠中三角**有下方投影**；折边有**受光亮线**；有**纸纹**
- [ ] **V11** E32 阶梯：**频谱映射（P0，必过）**——① **相邻柱高度普遍不同**（不再是几档高度**成对重复**；现状是"若干对同高柱"，因 `src(i) == src(cols-1-i)` 恒成立）；② **高音段有响应**（放镲片/齿音明显的曲子，**右侧柱必须亮**；现状 `bin ≥ 31` 全灭）；③ 静音段落**所有柱一起回落到轮廓格**（不是有的高有的低卡住）。**观感**——④ 相邻列**有接缝**；⑤ 发光块**有外溢**；⑥ 碎裂**有位移与角度**；⑦ 同高度柱**不再闪烁**
- [ ] **V12** E11 星系：可见**尘埃带**；中心核**径向渐变**；30/60fps 下**转速一致**
- [ ] **V13** E14 烟花：粒子**有拖尾**；`beat` 有**冲击波环 + 闪光**
- [ ] **V14** E16 数字雨：列头**有光晕**；头上有**拖影**；字形**有描边与内部明暗**；字符集**仍为 0/1**
- [ ] **V15** E18 残像：连续 5 分钟**不发灰白**；有**柔化**；色温随段落变化
- [ ] **V16** E19 粒子文字：**连续播放 5 分钟画面不消失**（修 4.2s 空白）；粒子覆盖**整个字形**（不再只有上部）；**不出现整片死白**；字有**中心亮/边缘暗**的纵深；换歌时**无可见卡顿**
- [ ] **V17** E20 等离子：流场**无块状**；背景**有流动纹理**；粒子呈**短条**
- [ ] **V18** E23 歌词点阵：**性能判据（必过）**——① 每次歌词换行**无可见卡顿**（`dumpsys gfxinfo` 无 > 32 ms 帧）；② 连续播放 10 分钟**帧耗时无周期性尖峰**（`Rect` 分配归零后 GC 抖动消失）；③ 改造前后**每帧 draw 调用数不增加**（除 Q4 的 2 次后处理）。**观感判据**——④ 亮档**不曝白**（演唱字与相邻字之间有边界）；⑤ 点阵**覆盖整个字形**（不再有空洞）；⑥ 待唱/演唱中/刚唱过**明度层次可辨**
- [ ] **V19** E25 催眠：曲线**有受光侧**；网格**三级明度**；有纸纹
- [ ] **V20** E34 分形：枝干**越梢越细越亮**；末级**有叶**
- [ ] **V21** E35 光轴：光束**有渐变**；可见**尘埃**；交汇处**有光斑**
- [ ] **V22** E29 轨道：行星**有球面明暗**；高光**随位置变化**；土星环**有环缝**
- [ ] **V23** E33 齿轮：齿廓**左右明暗不同**；转动时**高光沿齿面移动**；有**遮挡阴影**
- [ ] **V24** E37 分子：原子**有球面明暗 + 高光点**；辉光**无同心台阶**；键**上下明暗**
- [ ] **V25** E38 怀旧：1080p/4K **无摩尔纹**；颗粒是**不规则胶片颗粒**；竖横切换**暗角正确**
- [ ] **V26** E39 照片墙：停留期暗角/颗粒**可见但不过重**；光效转场**不卡**；转场有**轻微运动模糊**
- [ ] **V27** E40 DNA：骨架**有受光窄边**；背景**有径向纵深**；有暗角
- [ ] **V28** E41 世界：陆地**有明暗层次**；有**球面感**；grain **无接缝**；城市**有外溢辉光**

---

## 十二、开发任务清单（可勾选 · 进度追踪）

### 12.1 打勾与回顾规程

**四类时机的动作**（缺一条这套清单就会烂掉）：

| 时机 | 动作 |
|---|---|
| 完成单项 | 复跑该项的**验收判据**再打勾；⛔ **不许凭记忆打勾** |
| 完成一个阶段 | ① 打勾阶段完成项（它本身是一次真机/门禁验收）；② **回看该阶段的任务描述，若与实现有偏差（改参数 / 换方案 / 加任务），把偏差补写进 §六/§七 对应小节** |
| 任务被拆分或放弃 | ⛔ **不要删行**，改成 `- [x] ~~原任务~~ → 实际做法（原因）` |
| 发现文档有错 | 就地修正并**在 §12.3 的偏差记录里追加一行**；若影响其他任务，同步改 |

**粒度约定**：一项 = **一个可独立验证的交付单元**（约 100–400 行代码）。
要点用 `  - ` 缩进（**不带 `[ ]`**），⛔ 若给要点也加 checkbox，粒度就退回细的了。
**计数约束**：⛔ 改任务清单后必须同步改 §12.2 总览表的"任务数"；两处不一致时
**以 checkbox 实测为准**，并回头修表。

**门禁命令与基线**（每个阶段完成时跑）：

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat testDebugUnitTest lintDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

基线：`testDebugUnitTest` **1177 例 / 115 类 / 0 失败**、`lintDebug` **0 Error / 279 Warning**。
⚠️ **计数漂移先确认是不是本轮新增的**（本项目有并发会话改同一工作区）。

### 12.2 进度总览

| 阶段 | 名称 | 提交号 | 任务数 | 状态 |
|---|---|---|---|---|
| 0 | 准备 | — | 1 | ⬜ |
| 1 | 工具箱（`fx/` 6 文件 + 7 门禁） | S1 | 7 | ⬜ |
| 2 | 批次 A 前 4 套 | S2 | 5 | ⬜ |
| 3 | 批次 A 其余 7 套 | S3 | 8 | ⬜ |
| 4 | 批次 B 10 套 | S4 | 11 | ⬜ |
| 5 | 批次 C 精修 7 套 | S5 | 8 | ⬜ |
| 6 | 离屏层（可选，需裁决项 2） | S6 | 3 | ⬜ |
| 7 | 收尾与文档同步 | S7 | 4 | ⬜ |
| **合计** | | | **47** | |

> 状态取值：`⬜` 未开始 ｜ `🟨` 进行中 ｜ `✅` 完成 ｜ `⛔` 阻塞

### 12.3 任务清单

#### 阶段 0 · 准备（1 项）

- [ ] **T0.1** 复跑基线门禁并落盘
  - `testDebugUnitTest` + `lintDebug` 各跑一次，把**实测计数**写回本文件头部与 §八
  - 确认 `VisualizerThemeTest` 6 处计数断言当前值（应为 28/27）
  - **验收**：本文件头部基线数字 = 实测数字；差异已注明原因

#### 阶段 1 · 工具箱（7 项）

- [ ] **T1.1** `fx/FxLevel.kt`：`FxLevel{OFF,LITE,FULL}` + `FxBudget.of(quality)`
  - ⛔ 不给 `VisualQuality` 加字段（§三 #3）
  - **验收**：`FxBudgetTest` 绿（含负向：分支对调必须判失败）

- [ ] **T1.2** `fx/AudioSmoother.kt`：attack/release 分离包络
  - **验收**：`AudioSmootherTest` 绿（含负向：attack/release 对调必须判失败）

- [ ] **T1.3** `fx/ProceduralTexture.kt`：6 种 tile 生成与缓存（grain / scanline / star / paper / water / caustic）
  - 全部在 `ensure(w,h)` 时一次性生成；⛔ `recycle()` 必须显式
  - **验收**：`ProceduralTextureRecycleTest` 绿（含负向：只置 null 必须判失败）

- [ ] **T1.4** `fx/Shading2D.kt`：`lambert` / `specular` / `rim` / `bevelStroke` / `contactShadow` / `shadeBrush` / `toneMap`
  - ⛔ `LIGHT_ANGLE_DEG = 315f` 是**全库唯一**光源常量
  - **验收**：`Shading2DTest` 绿（含负向：改光源角后明暗必须反转）

- [ ] **T1.5** `fx/OverlayFx.kt`：`drawVignette` / `drawGrain` / `drawScanlines` / `drawTexture` / `drawExposureCompensation` / `release`
  - `OFF` 档**全部方法直接 return**
  - **验收**：`ScanlineTileTest` 绿（含负向：周期改错必须判失败）

- [ ] **T1.6** `fx/OffscreenFx.kt` 骨架（**本阶段只建类与 `ensure`/`onExit`，不接任何效果**）
  - 不变式：`currCanvas` 恒包装 `curr`
  - **验收**：类可编译；`onExit` 后位图全部 `isRecycled`

- [ ] **T1.7** **阶段完成**：门禁全绿 + **画面零变化**
  - ⛔ 本阶段**不得有任何渲染器接入** —— 这是"工具箱无副作用"的证明
  - **验收**：`testDebugUnitTest` 全绿且计数净增 40–60；真机上任意效果画面与改造前**逐像素一致**（截图比对）

#### 阶段 2 · 批次 A 前 4 套（5 项）

- [ ] **T2.1** E03 隧道穿越（§A1 六条）
  - **验收**：V1 判据全部通过

- [ ] **T2.2** E05 圆形频谱环（§A2 六条）
  - 重点：`glowLayers` 假光晕整段删除；外圈 8 段 `drawArc` → 1 次 `sweepGradient`
  - **验收**：V2 判据全部通过

- [ ] **T2.3** E07 频率山峦（§A3 六条）
  - 重点：`lineTo` → `quadraticBezierTo` 平滑（零分配）
  - **验收**：V3 判据全部通过

- [ ] **T2.4** E12 频谱瀑布（§A4 六条）
  - 重点：底部新行 64 次 `drawRect` → 16 条 `Path`；历史行加衰减
  - **验收**：V4 判据全部通过

- [ ] **T2.5** **阶段完成**：`FxCoverageScanTest` 首次启用（仅覆盖本阶段 4 套，其余加豁免标记）
  - 电视上实测 4 套帧耗时（`dumpsys gfxinfo`）
  - **验收**：U1–U5 通用项通过；4 套帧耗时 ≤ 改造前

#### 阶段 3 · 批次 A 其余 7 套（8 项）

- [ ] **T3.1** E13 液态网格（§A5 六条）
  - ⛔ **必须修 `timeMs` 违规**（`:210-213`）→ `dt` 累加
  - **验收**：V5 通过；喂 `dt = 10s` 时相位推进不超一帧步长

- [ ] **T3.2** E15 液态涟漪（§A6 五条）
  - **验收**：V6 通过

- [ ] **T3.3** E17 星座（§A7 五条）
  - 重点：连线判定改空间网格分桶（12720 → ~1440 次）
  - **验收**：V7 通过；HIGH 档 draw 耗时下降

- [ ] **T3.4** E24 心跳（§A8 **六条**）
  - ⛔ **顺序不可反**：**先做第 0 条（波形真实化）** —— 它是纯数据 + 表驱动，
    零新增分配 / 零新增 draw，可独立提交、独立验证；再做 1–5 的观感改造
  - 第 0 条重点：`colMs`/`beatAtMs`/`lastFireMs` → 整数列号 `colIdx`/`beatCol`/`lastFireCol`
    （顺带**删掉** `REBASE_AT_MS`）、`HB_T`+`HB_V` → 单表 `HB`（50 点）、
    `heartbeatAt` → O(1) 整数索引
  - 第 2 条重点：新增延迟波形层（余辉拖尾），`FloatArray` 原地衰减
  - ⛔ 不新增扫描头 / 节拍圆点（KDoc `:45`「峰顶无帽」既有裁决；原第 5 条已作废）
  - **验收**：V8 ①②③④（波形形状，**必过**）+ ⑤⑥⑦（观感）

- [ ] **T3.5** E30 雷达（§A9 **四条**）
  - ⛔ **顺序不可反**：**先做第 0 条（真实 PPI 重建）** —— 它把"回波焊在扫线上"改成
    "目标池 + 磷光余辉"，可独立提交、独立验证；再做 1–3 的观感改造
  - 第 0 条重点：① 目标池 `FloatArray(24×8)` + `crossed()` 区间判定（左开右闭，抗跨 0 回绕）；
    ② `BRIGHT` 线性衰减（`holdSec ≈ 1.15 × 周期`）⇒ 撑到下一圈；③ 连续漂移 + 寿命重生
    （**重生不是消失** —— E19 踩过"池耗尽"的坑）；④ **修扫掠扇不转的真 bug**
    （`withTransform` 旋转，⛔ 不重建 `Brush`）；⑤ 转速改恒定，treble 改去缩短余辉
  - ⛔ **删掉 `drawSpectrumArcs`**（`:161-187` 整段）：它的"回波"是错的机制，
    由目标池的 `drawArc` 取代
  - ⛔ **不改** `:106-134` 的同心圆 / 射线结构（只在第 1 条里改 alpha 与分档明度）
  - **验收**：V9 ①②③④（真实 PPI，**必过**）+ ⑤⑥⑦（观感）

- [ ] **T3.6** E31 折纸（§A10 五条）
  - ⚠️ 第 4 条"折痕渐变"若触发零分配红线，**按文档写的降级方案执行**（保留纯色 + 法线明暗）
  - **验收**：V10 通过

- [ ] **T3.7** E32 阶梯（§A11 **六条**）
  - ⛔ **先做第 0 条（P0 全柱独立频段映射），再做 1–5 的观感** —— **顺序不可反**：
    映射没修好之前，加圆角/发光只是在"错的柱高"上化妆，V11 ① ② 必挂
  - 第 0 条重点：① 删掉 `n / 2` 半区，覆盖 `0..63`；② `cols` 列 → `cols` 个**互不重叠桶区间**
    + **区间均值**（`onEnter` 预建 `IntArray(cols + 1)`，⛔ draw 内零分配）；③ `PERCEPT_K = 1.6f`
    感知分桶；④ **镜像决策**（§十三 裁决项 7，默认**全宽展开**）；⑤ `STEPS` 16 → **24** +
    `MIN_AMPLITUDE` 门槛 + `steps` 下限 1；⑥ 亮度改 `level = (st + 1f) / STEPS`（修闪烁）；
    ⑦ 分离 `gapX` / `gapY`
  - ⛔ **不改** `drawBlock` 的碎裂机制（那是第 3 条的事）、**不改** `SHATTER_T` 与
    `CELL_GAP` 的数值语义（只把 `gap` 拆成 X/Y 两个）
  - ⛔ 为了单测可见，`rebuildBands` / `colsOf` / `blockAlpha` 需 **`internal`**（§15.4-A36）
  - **验收**：V11 ①②③（**必过**）+ ④⑤⑥⑦（观感）；`StaircaseMappingTest`（§八 G10）全绿
    且 3 条负向自证**实测挂掉**

- [ ] **T3.8** **阶段完成**：`FxCoverageScanTest` 覆盖率扩到 A 批 11 套（移除豁免）
  - **验收**：U1–U5 通过；11 套帧耗时 ≤ 改造前

#### 阶段 4 · 批次 B 10 套（11 项）

- [ ] **T4.1** E11 星系螺旋（§B1）
  - ⛔ 必须修 `dt` 化旋转（`:46` 帧率绑定）
  - **验收**：V12 通过

- [ ] **T4.2** E14 节拍烟花（§B2）
  - 拖尾需按 hue 分 8 桶合并 `Path`
  - **验收**：V13 通过

- [ ] **T4.3** E16 数字雨（§B3）
  - ⛔ **字符集固定为 0/1**（§13.5 已裁决）；只把字形档位 4 → 5、加描边 + 垂直渐变，缓存 8 → 10 张
  - 拖影/光晕与字形质感分两次改，便于回退
  - **验收**：V14 通过

- [ ] **T4.4** E18 反馈残像（§B4）
  - 重点：离屏缓冲内加衰减色调，抑制灰白累积
  - **验收**：V15 通过（连续 5 分钟）

- [ ] **T4.5** E19 粒子文字（§B5）—— ⚠️ **本节是本轮性能技法的试点，必须先于 T4.7 完成**
  - **顺序不可反**：P1 重生修复（4.2s 空白）→ P3 逐行 `getPixels` → P4 按行配额采样（照搬 E23 歌词点阵）→ P2 `dotPath` + `addPath` 批量（350 draw → 1）→ P5 `step = 1` → Q1–Q6 观感
  - ⚠️ P2 的收益必须用 `dumpsys gfxinfo` 实测（风险 R11）；不达标则退回 350 `drawCircle`（本就零分配）
  - ⛔ 不得为观感增加每帧 draw 数超过 §B5 成本账里写的 6 次
  - **验收**：V16 通过（含"连续播放 5 分钟画面不消失"）

- [ ] **T4.6** E20 等离子流场（§B6）
  - 网格 16×9 → 24×14 + fbm 双倍频
  - **验收**：V17 通过

- [ ] **T4.7** E23 歌词点阵（§B7）—— ⛔ **性能优化为主**（用户 2026-09-29 明确要求），画面优化不得增加消耗
  - **顺序不可反**：P0-1/P1 `Rect` 分配归零（14,400 → 0）→ P0-2/P2 逐行 `getPixels`（71,000 → 116 次 JNI）→ P3 提全局量到循环外 → P4 删死代码（`phaseVal`/`textLen`）→ P5 `reset()` → `rewind()` → P6 `step = 1` → Q2/Q4 观感
  - ⛔ **采样结构（两遍 + `calculateRowQuota`）保持不变**，只换读取方式 —— 它是本项目已验证的正确算法（`:257-260` 注释）
  - ⛔ 不得整图读 `IntArray(bmpW * bmpH)`（风险 R12）；一律逐行读
  - Q3（4 档亮度 → 8 条 Path / 48 个 `dotPath`）**是可选**，需先确认重建成本可接受
  - **验收**：V18 通过（**含 3 条性能判据**，缺一不可）

- [ ] **T4.8** E25 催眠（§B8）
  - **验收**：V19 通过

- [ ] **T4.9** E34 分形（§B9）
  - **验收**：V20 通过

- [ ] **T4.10** E35 光轴（§B10）
  - ⚠️ 光束 `Brush` 必须按 `(w,h)` 缓存（参考 `RadarGridRenderer.ensureBrush` 范式）
  - **验收**：V21 通过

- [ ] **T4.11** **阶段完成**：`FxCoverageScanTest` 覆盖 A+B 共 21 套
  - **验收**：U1–U5 通过；21 套帧耗时 ≤ 改造前

#### 阶段 5 · 批次 C 精修 7 套（8 项）

- [ ] **T5.1** E29 轨道（§C1 五条）
  - 重点：行星球面光照 + 高光随位置变化 + 土星环缝
  - **验收**：V22 通过

- [ ] **T5.2** E33 齿轮（§C2 五条）
  - 重点：齿廓按法线分 `lightPath`/`darkPath`（**生成期**完成，draw 期零额外计算）
  - **验收**：V23 通过

- [ ] **T5.3** E37 分子（§C3 五条）
  - 重点：原子 `shadeBrush` 球面化；3 层同心辉光 → 1 次径向渐变
  - **验收**：V24 通过

- [ ] **T5.4** E38 怀旧（§C4 六条）
  - 重点：240 次 `drawLine` → 1 张 tile 平铺；⛔ 修 vignette `Brush` 只按 `w` 缓存的缺陷（`:600-613`）
  - **验收**：V25 通过；帧耗时不高于改造前

- [ ] **T5.5** E39 照片墙（§C5 四条）
  - 新增 6 种光效转场时⛔ **必须同步 `PhotoTransitionRegistryTest` 计数断言**（风险 R9）
  - **验收**：V26 通过

- [ ] **T5.6** E40 DNA（§C6 五条）
  - **验收**：V27 通过

- [ ] **T5.7** E41 世界（§C7 六条）
  - ⛔ 必须修 grain tile 接缝风险（`:783-792`）
  - **验收**：V28 通过

- [ ] **T5.8** **阶段完成**：`FxCoverageScanTest` 覆盖全部 28 套（`PHOTO_WALL` 按需豁免）
  - **验收**：U1–U5 通过；28 套全量回归；改造前后**逐套截图对比**存档

#### 阶段 6 · 离屏层（可选 · 3 项 · 需 §十三 裁决项 2 通过）

- [ ] **T6.1** `fx/OffscreenFx.kt` 补全 `drawBloom`（三级降采样 + Kawase 双滤波）
  - **验收**：`FxBudgetTest` 保证 `OFF`/`LITE` 档**不创建任何离屏缓冲**

- [ ] **T6.2** `VisualizerStage` 接线（屏幕级 bloom，**只做一次**，不在 28 套里各做）
  - ⚠️ 需评估：`VisualizerStage` 目前直接 `Canvas { renderer.draw(...) }`
    （`VisualizerStage.kt:301/349`），接入离屏需要 `CanvasDrawScope().draw(...)` 到
    `ImageBitmap` —— **本项是唯一涉及既有 UI 层改造的任务**，风险最高，故放最后且可选
  - **验收**：HIGH 档 bloom 可见；LOW/MEDIUM **零额外成本**（帧耗时与阶段 5 一致）

- [ ] **T6.3** **阶段完成**：电视上实测离屏层帧耗时
  - **验收**：电视 HIGH 档帧耗时 ≤ 改造前的 **1.25 倍**；超出则**放弃阶段 6**（回退 S6 提交）

#### 阶段 7 · 收尾与文档同步（4 项）

- [ ] **T7.1** `docs/technical-overview.md` 新增 `§10.N`（N 递增），记录本轮全部技法与红线修复
  - 含：`fx/` 包设计、`LIGHT_ANGLE_DEG` 唯一光源、`LiquidGridRenderer` 的 `timeMs` 违规修复、
    `RadarGridRenderer` 短弧角度修复、`VintageTvRenderer` vignette 缓存缺陷修复
  - **验收**：`§` 引用无失效（含跨文档引用）；围栏成对

- [ ] **T7.2** `CHANGELOG.md` 新增节（**插到文件最前**，倒序）+ `README.md` 若涉及功能描述
  - 写作口径：CHANGELOG 每条只写"做了什么"
  - **验收**：新节在文件最前；每条只陈述事实

- [ ] **T7.3** 全量门禁 + 上机全量回归 + 本文件回填
  - 回填：§12.2 总览表状态、各阶段偏差记录、§八 基线数字
  - **验收**：本文件所有 checkbox 状态与 §12.2 表一致；偏差记录已写

- [ ] **T7.4** 顺手清理 §15.7 的 10 条陈旧注释 / 死代码 / 未用 import
  - ⚠️ 第 9 条（`ParticleRenderers.kt:129-132` 的「只能退回 `getPixel()`」）**已在 2026-09-29 修正**；第 8 条（数字雨 KDoc）亦已修正 —— 打勾时只做剩下的 8 条
  - `BatchTwoRenderers.kt:492` 的 `withTransform` 注释（+ `docs/technical-overview.md:11196` 同一说法）
  - `AdvancedRenderers.kt:343-353` 的「0-9 / 10 数字」陈旧 KDoc（实现是 **0/1 二进制雨**，§13.5-D1）
  - `VisualizerViewModel.kt:402-403` 的 crossfade 陈旧注释（§15.4-A18）
  - `AdvancedRenderers.kt:110` 空 `let`；`AdvancedRenderers.kt:13` / `BasicRenderers.kt:12` /
    `BatchThreeRenderers.kt:11` / `UltraRenderers.kt:8` 的未用 `withTransform` import
  - `BasicRenderers.kt:303/323` 缩进对齐；`BarSpectrumRenderer`（`:29`，零子类）是否删除需先确认无引用
  - ⛔ 只改注释 / import / 缩进，**不改任何行为**；⛔ 删 `BarSpectrumRenderer` 前先 grep 确认无反射与测试引用
  - **验收**：`testDebugUnitTest` 全绿且**测试计数不变**（证明零行为改动）；lint Warning 数不增加

### 12.4 偏差记录（实现期追加）

| 日期 | 任务 | 文档原写 | 实际做法 | 原因 |
|---|---|---|---|---|
| — | — | — | — | 待实现期填写 |

---

## 十三、裁决记录（7 项待拍板 · 3 项已裁决）

> 以下 7 项会显著影响实现范围与观感，**请先拍板再开工**（第 6、7 项已给默认值，可不回）。

| # | 待裁决 | 选项 A | 选项 B | 影响 |
|---|---|---|---|---|
| 1 | **改造深度** | **只做批次 A（11 套）** —— 收益最集中，工作量约 1/3 | **A+B+C 全量（28 套）** —— 观感统一，但工作量大 3 倍 | 决定 §12 任务量（批次 A = 阶段 2+3 共 **13 项** vs 全量 **47 项**） |
| 2 | **是否要真 bloom / 真色差（离屏层）** | **要** —— 追加阶段 6（高风险，需在电视上实测帧耗时，可能回退） | **不要** —— 只做零离屏叠加层（§5.3 的 `LITE`），电视绝对安全 | 决定是否有阶段 6；决定 E38 色差能否"变真" |
| 3 | **观感基调** | **"精致/电影感"** —— 暗角偏重（0.44–0.52）、颗粒明显、色差可见 | **"清爽/科技感"** —— 暗角偏轻（0.32–0.38）、颗粒极淡、无色差 | 决定 §7.1 全表参数的取值区间；决定"老照片/CRT"类效果（E38 怀旧 / E39 照片墙）的力度 |
| 4 | **效果切换是否启用 600ms 交叉淡入** | **启用** —— 改 `VisualizerStage.kt:178` 第 3 个实参为 `true`（`RendererSwapper` 已实现，`RendererSwapper.kt:102-107`） | **不启用** —— 保持硬切（既有裁决：用户按键后需即时反馈） | 与既有裁决冲突，故**不擅自改**（§三 #2） |
| 5 | **E24 心跳的显示增益 `AMP_FRACTION`** | **保持 `0.26`** —— 1 mV ≈ 281 px（1080p），R 峰冲击力强；代价是 R 波呈**近垂直尖刺**（宽 8 列 ≈ 16 px / 高 281 px ≈ 17.6:1），比真实监护仪的 10 mm/mV 观感"陡" | **降到 `0.16`** —— 1 mV ≈ 173 px，整体更接近监护仪的观感；代价是 R 峰变矮、屏幕上下留白变多 | **只影响这一套效果**，改一个常量即可回退。⚠️ **横向压缩是固有的、不要试图"修"**：全屏扫过 = `cols/speed` = 960/90 = **10.7 s**，与真实监护仪一屏 10 s 一致；因此心搏（50 列 = 100 px）在 1920 宽屏上本来就只占 5% 宽 —— 这是**对的**，不是缺陷 |
| 6 | **E30 雷达：方位角/距离 与 频率 是否绑定** | **解耦（推荐）** —— 目标的 `BEARING`/`RANGE` 随机且缓慢漂移，只把**回波强度/粗细**接频谱（`PEAK = spectrum[BIN]`）⇒ 图案是"真雷达散点"，符合用户描述的"扫描出现的点下次换位置" | **绑定** —— 低频=近、高频=远（角向按桶号排布）⇒ 图案规整可预测，但会退化成**同心圆花纹**、失去"目标在动"的散点感；且与"真实雷达"的诉求相悖 | 只影响 E30 的 §A9 第 0 条（一个 `RANGE`/`BIN` 的赋值策略）。⚠️ 用户原话是"**扫描出现的点，在下次扫描后会变化位置**" ⇒ **默认按 A（解耦）实现**，此项列出只为留痕 |

| 7 | **E32 阶梯：频谱柱保留左右镜像，还是全宽展开** | **全宽展开（推荐）** —— `cols` 列**顺序**覆盖 `0..63`（低频在左、高频在右），每列平均 `64 / cols` = **3.2 / 2.3 / 1.8** 个桶 ⇒ **列分辨率翻倍**，最贴合用户诉求"真实反映音乐频谱"；代价是画面**不再左右对称**（与 `BarSpectrumRenderer` 的镜像传统不同） | **保留镜像** —— 保持"建筑立面"对称美学（现状），但每半只有 `cols / 2` 列要覆盖 `0..63`，每列平均 `64 / (cols/2)` = **6.4 / 4.6 / 3.6** 个桶 ⇒ 分辨率减半，观感仍偏"**成对重复**"（用户报的正是这个） | 只影响 **§A11 第 0 条的 0.4**（draw 内 `bi` 的取值：`i` 还是 `if (i < cols/2) i else cols-1-i`）。⚠️ 用户原话是"**让所有的柱都随音乐波动，真实反应音乐频谱**" ⇒ **默认按 A（全宽展开）实现**，此项列出只为留痕。若改回镜像，`StaircaseMappingTest`（§八 G10）的断言 ② 需改为"命中不同桶数 == `cols / 2`"、④ 的阈值按 `cols / 4` 不变但基准列数减半 |

### 13.5 已裁决（不再讨论 · ⛔ 实现时不得改动）

> 这些是**用户已明确表态**的项，写在这里防止后续（人或 agent）按"看起来更丰富"的直觉改回去。

| # | 项 | 裁决 | 依据 |
|---|---|---|---|
| D1 | **E16 数字雨 `MATRIX_RAIN` 的字符集** | **固定为 `0`/`1` 二进制雨**（字形缓存 8 张 = 2 字符 × 4 档绿）。⛔ **不扩到 `0-9`、不扩到 `0/1/A–F`** | ① 设计本意即**二进制字符雨**（v2.30.4 曾误扩为 `0-9`，用户指出后于 **2026-09-13** 撤销，提交 `cbfaa8e`，`CHANGELOG.md:1373` 记「保持 0/1 二进制雨」）；② **2026-09-29** 用户再次确认「数字雨中字符，只需要 0 和 1」。源码现状 `AdvancedRenderers.kt:433` `charArrayOf('0','1')` 即正确形态。**"质感"靠字形本身的描边/渐变/光晕/拖影来做，不靠堆字符种类**（§B3） |
| D2 | **`VisualizerTheme` 枚举成员集合** | **28 项不变**（不新增、不删除、不改名） | 动它会连带 `VisualizerThemeTest` 6 处计数断言（`28`/`27`）与 `LEGACY_MAP`（§15.5） |
| D3 | **效果切换为硬切（`crossfade = false`）** | 维持现状，直到 §十三 裁决项 4 被显式改为"启用" | `VisualizerStage.kt:178` 既有裁决：用户按键后需即时反馈 |

---

## 十四、附：开源参考清单（真实 URL + 借鉴点）

> 本项目的渲染器是**纯 Compose Canvas（无 shader、无 OpenGL）**，所以参考的重点是
> **"技法语义"与"参数组织方式"**，而不是直接移植着色器代码。
> 每条都标注了**本方案中对应的小节**，方便实现时对照。

| # | 项目 | URL | 借鉴什么（落到本方案哪一条） |
|---|---|---|---|
| 1 | **projectM**（MilkDrop 跨平台重实现） | https://github.com/projectM-visualizer/projectm | ① `warp`/`comp` 双着色器管线 → 本方案 §G6 的"反馈 + 后处理"分层；② 预设里的 `specular` / `edge glow` 语义 → §G2/G3 的 `Shading2D.specular` / `rim`；③ per-frame 反馈缓冲的生命周期管理 → §五 `OffscreenFx` 的不变式设计 |
| 2 | **Butterchurn**（MilkDrop 的 WebGL 实现） | https://github.com/jberg/butterchurn | ① `compositeShader` 的后处理链（blur1/2/3 → 合成）→ §G1 的后处理链顺序；② `noise` 纹理与 `per_frame` 公式体系 → §G1 的 grain tile、§G10 的"频段 → 参数"映射表 |
| 3 | **GLava**（X11 OpenGL 频谱可视化器） | https://github.com/jarcode-foss/glava | radial shader 用 `smoothstep` 做边缘衰减与 glow → §G5 的"光晕 = 径向渐变"、§G2 的边缘光 |
| 4 | **TjardoOrtan/audio-reactive-shaders**（音频反应着色器集合） | https://github.com/TjardoOrtan/audio-reactive-shaders | 频段 → 视觉参数的参数化组织方式 → §G10 与 §7.3 的统一映射表 |
| 5 | **libretro CRT Shaders 文档** | https://docs.libretro.com/shader/crt/ | 扫描线 / 荫罩 / 桶形畸变 / bloom / halation 的完整参数化 → §C4（E38 怀旧）的第 1/2/4/5 条 |
| 6 | **dmoa/crt-shader** | https://github.com/dmoa/crt-shader | 交错扫描、faux barrel distortion、glow/halation 的具体实现细节 → §C4 第 4 条（桶形畸变）、§G7 |
| 7 | **Android AGSL `RuntimeShader` 文档** | https://developer.android.google.cn/reference/android/graphics/RuntimeShader | **未来可选加速路径**（API 33+）。本项目 `minSdk 22`，故本方案**不采用**，仅记录：未来若放弃 Android 5.1 支持，可用它把 `OffscreenFx` 的 bloom 换成真 shader |
| 8 | **Kawase 双滤波降采样 bloom**（Unity URP / Vulkan 后处理实践） | https://www.kiie9697.cn/writing/28-bloom-implementation/ | Prefilter → Downsample × N → Upsample → Composite 的四段结构、`threshold`/`intensity` 参数语义 → §G6、§5.3 `OffscreenFx.drawBloom` |
| 9 | **AudioGlow / WaveScope**（WebGL 音乐可视化器） | https://github.com/maohee-dev/audioglow ・ https://github.com/jasonulbright/wavescope | 同类效果的"现代观感"参考（配色、光晕力度、运动节奏）→ §六 批次 A 的观感基调；可用于改造前后的对照 |
| 10 | **GitHub `audio-reactive` / `audio-visualizer` 主题索引** | https://github.com/topics/audio-reactive ・ https://github.com/topics/audio-visualizer | 后续找"某个具体效果"的参考实现时的入口（例如想找更好的 DNA / 星球 / 齿轮实现） |

### 14.1 参考时的三条纪律

1. **只借语义，不借代码** —— 上述项目多为 GLSL/WebGL/C++，本项目的渲染路径是
   Compose `DrawScope`。直接把 shader 逻辑搬进来会撞上"零分配 + Android 5.1 hwui"两道墙。
2. **参数要落到 §七** —— 任何从参考实现借来的数值（阈值、指数、半径比）**必须**写进
   §7 参数表并成为 `const val`，⛔ 不许散落在渲染器内部当魔法数字。
3. **观感要对照截图** —— 参考实现的观感是在桌面/浏览器上、大屏高帧率下的结果；
   电视 1080p + 弱 GPU 下要**降档实现**（例如 GLava 的 12 层 glow，本项目最多 2 层）。

---

## 十五、开发实施手册（可开发级）

> 本章是「照此敲代码无需再做设计决策」的落地层。
> ⛔ 本章**不改写**前十四章的结构与编号，只补前文没有的**逐文件清单 / 完整签名 /
> 逐文件改造点 / 源码级 API 核实结论**。

### 15.0 「可开发级」7 项自检表

| # | 必需内容 | 落点 | 状态 |
|---|---|---|---|
| 1 | 逐文件清单（新增/修改，含包路径、预估行数） | **§15.1** | ✅ 本章新增 |
| 2 | 完整接口签名（Kotlin，可直接粘） | **§15.2** | ✅ 本章新增（补全 §5.3 的节选） |
| 3 | 现有文件改造点（逐文件 + `file:line`） | **§15.3** | ✅ 本章新增 |
| 4 | 关键参数表（逐项数值，不是"约 0.4"） | §七（4 张表，已全数值化） | ✅ 已具备 |
| 5 | 单测门禁清单（类名 + 断言 + **负向自证**） | §八（**10 类**）+ **§15.5**（既有断言的同步项） | ✅ 已具备 + 本章补充 |
| 6 | 提交顺序（每步可独立验证 + 验收点） | §十（S1–S7） | ✅ 已具备 |
| 7 | 上机验收清单（可逐条勾 + 期望） | §十一（U1–U5 + V1–V28） | ✅ 已具备 |
| ★ | **源码级 API 核实结论**（避免"照着写编译不过"） | **§15.4** | ✅ 本章新增（本轮实测） |

### 15.1 逐文件清单

#### 15.1.1 新增文件（6 生产 + 7 测试）

| 文件（包路径） | 预估行数 | 内容要点 |
|---|---|---|
| `app/src/main/java/com/nasmusic/tv/visualizer/fx/FxLevel.kt` | 45 | `FxLevel{OFF,LITE,FULL}` + `FxBudget.of(quality)`（纯推导，不动 `VisualQuality`） |
| `app/src/main/java/com/nasmusic/tv/visualizer/fx/AudioSmoother.kt` | 70 | attack/release 分离包络；`update(target)` 零分配 |
| `app/src/main/java/com/nasmusic/tv/visualizer/fx/ProceduralTexture.kt` | 330 | 6 类程序化 tile（GRAIN ×8 变体 / SCANLINE / STARFIELD / PAPER / WATER / CAUSTIC）+ `sphereSprite(color)`；`IntArray` 键缓存 + `release()` 显式 `recycle` |
| `app/src/main/java/com/nasmusic/tv/visualizer/fx/Shading2D.kt` | 200 | `LIGHT_ANGLE_DEG=315f` + `lambert` / `specular` / `rim` / `bevelStroke` / `contactShadow` / `shadeBrushCached` / `toneMap` |
| `app/src/main/java/com/nasmusic/tv/visualizer/fx/OverlayFx.kt` | 270 | `drawVignette` / `drawGrain` / `drawScanlines` / `drawTexture` / `drawExposureCompensation` / `release`；`OFF` 档全部 `return` |
| `app/src/main/java/com/nasmusic/tv/visualizer/fx/OffscreenFx.kt` | 340 | 离屏乒乓双缓冲 + 三级降采样 bloom + 真通道色差；**仅 `FxLevel.FULL`** |
| `app/src/test/java/com/nasmusic/tv/visualizer/fx/FxBudgetTest.kt` | 90 | 档位推导 + `OFF` 档零成本（负向：分支对调必挂） |
| `app/src/test/java/com/nasmusic/tv/visualizer/fx/Shading2DTest.kt` | 130 | 明暗单调性 / specular 峰值角 / rim 背光最强（负向：改光源角必反转） |
| `app/src/test/java/com/nasmusic/tv/visualizer/fx/GrainDeterminismTest.kt` | 90 | 同 `(seed, seq)` 同图 / 异 `seq` 异图（负向：去掉 seq 索引必挂） |
| `app/src/test/java/com/nasmusic/tv/visualizer/fx/ScanlineTileTest.kt` | 110 | tile 版 vs 240 次 `drawLine` 版采样亮度差 ≤ 1/255（负向：周期改错必挂） |
| `app/src/test/java/com/nasmusic/tv/visualizer/fx/ProceduralTextureRecycleTest.kt` | 80 | `release()` 后全部 `isRecycled`；重复 `release()` 不抛（负向：只置 null 必挂） |
| `app/src/test/java/com/nasmusic/tv/visualizer/fx/AudioSmootherTest.kt` | 60 | attack 快 release 慢（负向：对调必挂） |
| `app/src/test/java/com/nasmusic/tv/visualizer/fx/FxCoverageScanTest.kt` | 190 | **源码扫描**：28 套渲染器 `draw` 内必须调 `OverlayFx.*`；含空转断言 + 豁免标记 + 6 条自证 |

> 合计：生产 **1255 行**、测试 **880 行**（含 E32 的 `StaircaseMappingTest` 130 行）。

#### 15.1.2 修改文件（渲染器 28 处 + 接线 1 处 + 照片墙转场 3 处 + 测试 4 处）

| # | 文件（相对 `app/src/main/java/com/nasmusic/tv/`） | 现状行数 | 预估改动 | 涉及效果 |
|---|---|---|---|---|
| 1 | `visualizer/renderers/BasicRenderers.kt` | 327 | **+95 / −35** | E03 / E05 / E07 |
| 2 | `visualizer/renderers/AdvancedRenderers.kt` | 568 | **+125 / −30** | E11 / E12 / E13 / E15 / E16 / E17 |
| 3 | `visualizer/renderers/ParticleRenderers.kt` | 265 | **+45** | E14 / E19 |
| 4 | `visualizer/renderers/UltraRenderers.kt` | 256 | **+55** | E18 / E20 |
| 5 | `visualizer/renderers/EcgWaveRenderer.kt` | 261 | **+95 / −30** | E24（第 0 条波形真实化 ≈ **+55 / −25**：表 14×2 → 单表 50、`colMs`→`colIdx`、删 `REBASE_AT_MS`；第 1–5 条观感 ≈ +40 / −5） |
| 6 | `visualizer/renderers/HypnoticFunctionRenderer.kt` | 781 | **+25** | E25 |
| 7 | `visualizer/renderers/LyricsDotMatrixRenderer.kt` | 898 | **+30** | E23 |
| 8 | `visualizer/renderers/BatchThreeRenderers.kt` | 516 | **+210 / −95** | E30（第 0 条真实 PPI ≈ **+70 / −30**：目标池 `FloatArray(24×8)` + `crossed`/`wrapTau`/`bounce`/`respawn` + `withTransform` 修扇 + **删 `drawSpectrumArcs` 整段**；第 1–3 条 ≈ +25 / −5）、E31（≈ +40 / −15）、**E32**（第 0 条全柱独立频段映射 ≈ **+45 / −25**：`band` 表 + `rebuildBands` + `colsOf` + `blockAlpha` + **删 `n / 2` 映射**；第 1–5 条观感 ≈ +30 / −20） |
| 9 | `visualizer/renderers/BatchTwoRenderers.kt` | 632 | **+60 / −15** | E29 |
| 10 | `visualizer/renderers/BatchFourRenderers.kt` | 1718 | **+120 / −20** | E33 / E34 / E35 |
| 11 | `visualizer/renderers/MoleculeRenderer.kt` | 1216 | **+55 / −20** | E37 |
| 12 | `visualizer/renderers/VintageTvRenderer.kt` | 905 | **+70 / −60** | E38 |
| 13 | `visualizer/renderers/DnaRenderer.kt` | 896 | **+50 / −10** | E40 |
| 14 | `visualizer/renderers/WorldRenderer.kt` | 2106 | **+40 / −10** | E41 |
| 15 | `visualizer/photo/PhotoRenderer.kt` | 161 | **+18** | E39 |
| 16 | `visualizer/photo/PhotoTransitionId.kt` | — | **+6**（新增 6 个枚举项） | E39 |
| 17 | `visualizer/photo/PhotoTransitionRegistry.kt` | — | **+6**（注册 6 项，P1 阶段） | E39 |
| 18 | `visualizer/photo/transitions/LightFxP1Transitions.kt`（**新建**） | — | **+160** | E39 |
| 19 | `ui/components/VisualizerStage.kt` | 713 | **+4**（阶段 1：`OverlayFx.release()`）；阶段 6 可选 **+35** | 接线 |
| 20 | `app/src/test/java/.../photo/PhotoTransitionRegistryTest.kt` | — | **改 4 处断言**（76 / 15 / 43 / 15 项参数表） | E39 |
| 21 | `app/src/test/java/.../visualizer/renderers/EcgWaveformTest.kt`（**新建**） | — | **+120** | E24（§八 G8：波形表形态学门禁，含负向自证） |
| 22 | `app/src/test/java/.../visualizer/renderers/RadarSweepTest.kt`（**新建**） | — | **+140** | E30（§八 G9：扫掠穿越判定 + 目标池漂移/余辉/重生，含负向自证） |
| 23 | `app/src/test/java/.../visualizer/renderers/StaircaseMappingTest.kt`（**新建**） | — | **+130** | E32（§八 G10：全柱独立频段映射门禁 —— 覆盖全频段 / 列列独立 / 无重叠空隙 / 感知分桶 / 小信号可见 / 亮度与 `steps` 无关，含 3 条负向自证） |

> ⛔ **不新增/不删除** `VisualizerTheme` 枚举成员（28 项不变）；⛔ 不改
> `RenderContext.update(...)` 签名；⛔ 不改 `VisualQuality` 字段；⛔ 不动
> `VisualizerRenderer` 接口。

### 15.2 完整接口签名（可直接粘贴）

> ⚠️ 全部签名已按 **Compose ui-graphics 1.6.1**（本项目实际解析版本，见 §15.4-A1）
> 逐一核对过。⛔ 若发现与前文 §5.3 的节选不一致，**以本节为准**。

#### 15.2.1 `fx/FxLevel.kt`（全文）

```kotlin
package com.nasmusic.tv.visualizer.fx

import com.nasmusic.tv.data.model.VisualQuality

/**
 * 后处理档位。
 *
 * ⛔ 刻意**不**给 [VisualQuality] 新增字段：该枚举有 7 个构造参数、被
 * `VisualizerThemeTest.kt:158`（`assertEquals(32, VisualQuality.LOW.barCount)`）等
 * 按名断言；且 `RenderContext.update(...)` 有 16 个位置参数与 3 个调用点
 * （`RenderContext.kt:47-51` 的 KDoc 明写这是刻意设计）。现有字段已足够区分三档。
 */
enum class FxLevel { OFF, LITE, FULL }

object FxBudget {

    /**
     * 由既有字段推导（源码依据 `data/model/AppSettings.kt:252-254`）：
     * ```
     * HIGH(barCount=64, glowLayers=3, trail=true,  maxParticles=350, …, allowFramebuffer=true)
     * MEDIUM(64, 2, true, 150, …, false)
     * LOW(32, 1, false, 0,  …, false)
     * ```
     */
    fun of(quality: VisualQuality): FxLevel = when {
        quality.allowFramebuffer -> FxLevel.FULL
        quality.glowLayers >= 2 -> FxLevel.LITE
        else -> FxLevel.OFF
    }
}
```

#### 15.2.2 `fx/AudioSmoother.kt`（全文）

```kotlin
package com.nasmusic.tv.visualizer.fx

/**
 * 统一频段包络平滑器 —— 替代各效果手写的 EMA
 * （`BatchThreeRenderers.kt:88-89`、`:302-303` 等，系数互不相同且无统一语义）。
 *
 * **attack / release 分离**是关键：鼓点要**快 attack**（跟得上瞬态）、
 * 段落要**慢 release**（不会一帧掉下去）。
 *
 * ⛔ 参数取值见 §7.3，不得在渲染器内另写魔数。
 */
class AudioSmoother(
    private val attack: Float = 0.35f,   // 上升系数（大 = 跟得快）
    private val release: Float = 0.06f   // 下降系数（小 = 落得慢）
) {
    var value: Float = 0f
        private set

    /** 每帧推进一次；零分配 */
    fun update(target: Float): Float {
        val k = if (target >= value) attack else release
        value += (target - value) * k
        return value
    }

    fun reset() { value = 0f }
}
```

#### 15.2.3 `fx/ProceduralTexture.kt`（公开 API + 生成规格）

```kotlin
package com.nasmusic.tv.visualizer.fx

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb

/**
 * 程序化纹理仓库。**所有纹理在此生成一次并缓存**，`draw` 期只做 `drawImage`。
 *
 * ⛔ 生命周期：由 `VisualizerStage` 在舞台离开时调一次 [release]；
 * 渲染器**不得**自行调用（多个渲染器共享同一份缓存）。
 * ⛔ 缓存查找必须是**数组下标**（见 [slots]），不得用 `HashMap` —— 后者在
 * draw 路径上会因装箱产生分配。
 */
object ProceduralTexture {

    /** 可平铺的固定尺寸纹理；每类固定占 [VARIANTS] 个槽位（`variant and 7`） */
    enum class Id { GRAIN, SCANLINE, STARFIELD, PAPER, WATER, CAUSTIC }

    const val VARIANTS = 8
    const val GRAIN_TILE = 128      // px，见 §7.1
    const val SCANLINE_H = 3        // px，见 §7.1
    const val SPHERE_PX = 128       // 球面贴图边长

    private val slots = arrayOfNulls<ImageBitmap>(Id.entries.size * VARIANTS)
    /** 生成时的 (w,h,seed) 键；0L = 未生成。平铺型与画布尺寸无关，用常量键 */
    private val keys = LongArray(Id.entries.size * VARIANTS)
    /** 球面贴图按颜色缓存（行星/原子颜色是固定有限的几个） */
    private val sphereColors = IntArray(16)
    private val sphereTiles = arrayOfNulls<ImageBitmap>(16)
    private var sphereCount = 0

    /**
     * 按画布尺寸准备全屏型纹理（STARFIELD / PAPER / WATER / CAUSTIC）。
     * 平铺型（GRAIN / SCANLINE）在此首次调用时生成一次。
     *
     * ⚠️ 必须在 **`onEnter` 或尺寸变化时**调用，⛔ 不得在 `draw` 内调用。
     */
    fun ensure(w: Int, h: Int)

    /**
     * 取纹理。`draw` 期唯一入口，**零分配**（数组下标）。
     * @return 未 [ensure] 时返回 `null`（调用方须判空后跳过绘制）
     */
    fun tile(id: Id, variant: Int = 0): ImageBitmap? =
        slots[id.ordinal * VARIANTS + (variant and (VARIANTS - 1))]

    /**
     * 球面贴图（**移动主体的球面光照**，见 §15.4-A4 的设计说明）。
     *
     * 贴图内容：① 径向衰减（中心 `towardWhite(base,0.35f)` → 边缘 `darken(base,0.45f)`）；
     * ② 边缘光 rim（背光侧一圈 `alpha 0.22` 的亮弧，方向固定 315°）。
     * ⛔ **不含方向性高光** —— 高光由调用方按 `Shading2D.specular(法线)` 单独画 1 个小圆，
     * 这样行星绕日公转时高光方向才能随位置变化（§G3）。
     *
     * 缓存键 = `base.toArgb()`；颜色种类固定有限（行星/原子 ≤ 16）。
     */
    fun sphereSprite(base: Color): ImageBitmap?

    /** 释放全部纹理。可安全重复调用（与 `MilkdropRenderer.releaseBuffers` 同语义）。 */
    fun release()
}
```

**各 tile 的生成规格**（`ensure` 内一次性完成，全部用 `android.graphics.Bitmap` +
`AndroidCanvas` 画，再 `asImageBitmap()`）：

| Id | 尺寸 | 生成算法 | 用途 |
|---|---|---|---|
| `GRAIN` | 128×128 ×8 变体 | 每变体独立 LCG 种子；每像素 `alpha = rand() × 26` 的灰度噪点，`Config.ALPHA_8` 不可用（需 RGB）→ `ARGB_8888` | 胶片颗粒（§G1） |
| `SCANLINE` | 1×3 | 第 0 行 `Color.Black` `alpha 0.16`、第 1 行 `alpha 0.06`、第 2 行全透明 | 扫描线（**替代 240 次 `drawLine`**，§G7） |
| `STARFIELD` | w×h | 3 档星（半径 0.6 / 1.0 / 1.6，alpha 0.20/0.35/0.60），LCG 均匀撒点，**总数 = w×h/9000** | 深空背景（§G9） |
| `PAPER` | w×h | 两层：低频 `sin` 交叉纹（周期 40px，幅度 ±4 灰阶）+ 高频噪点 | 纸纹（E31 折纸 / E25 催眠） |
| `WATER` | w×h | 4 组不同频率/方向的 `sin` 叠加 → 水纹灰度，`alpha` 上限 0.14 | 水面底纹（E13 / E15） |
| `CAUSTIC` | w×h | 2 组 `abs(sin)` 相乘形成的细亮网（焦散），`alpha` 上限 0.10 | 水下焦散（E13 液态网格） |

#### 15.2.4 `fx/Shading2D.kt`（全文 · 纯计算 + 少量 draw）

```kotlin
package com.nasmusic.tv.visualizer.fx

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import com.nasmusic.tv.visualizer.VisualizerMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 2D 光照与材质工具。**纯计算 + 少量 draw，全部零分配**。
 *
 * ⛔ [LIGHT_ANGLE_DEG] 是**全库唯一**主光方向 —— 所有高光、明暗、投影必须由它推导。
 */
object Shading2D {

    /** 主光方向：315°（左上）。⛔ 不得在渲染器内另写光源角 */
    const val LIGHT_ANGLE_DEG = 315f

    private const val DEG = (PI / 180.0).toFloat()
    /** cos(315°) / sin(315°) 的常量（预算，避免每帧算） */
    private const val LX = -0.70710678f
    private const val LY = -0.70710678f

    /** 主光单位向量 */
    val lightDir: Offset get() = Offset(LX, LY)

    /**
     * 沿法线的明暗（Lambert 半兰伯特化）：返回 0..1。
     * @param normalAngleDeg 该点表面**外法线**方向（弧度制见下）。
     *   ⚠️ 圆环上某点的外法线 = 该点极角（`atan2(y-cy, x-cx)`）转角度。
     *   本项目极角 0° 指向正右方（与 [VisualizerMath.polar] 一致）。
     */
    fun lambert(normalAngleDeg: Float): Float {
        val a = normalAngleDeg * DEG
        val d = cos(a) * LX + sin(a) * LY
        return (d * 0.5f + 0.5f).coerceIn(0f, 1f)   // 半兰伯特：背面不会全黑
    }

    /** 镜面高光强度 0..1：法线越对准光向越强 */
    fun specular(normalAngleDeg: Float, shininess: Float = 24f): Float {
        val a = normalAngleDeg * DEG
        val d = cos(a) * LX + sin(a) * LY
        if (d <= 0f) return 0f
        return d.pow(shininess).coerceIn(0f, 1f)
    }

    /** 边缘光强度 0..1：**背向**光源时最强（模拟环境反射的轮廓亮线） */
    fun rim(normalAngleDeg: Float): Float {
        val a = normalAngleDeg * DEG
        val d = cos(a) * LX + sin(a) * LY
        val l = (d * 0.5f + 0.5f).coerceIn(0f, 1f)
        return 1f - l * l
    }

    /**
     * 双层描边（"倒角"）：亮侧沿光向偏移画高光描边、暗侧反向偏移画暗描边。
     * 成本 = 2 次 `drawPath` + 2 次 `canvas.save/restore`（`translate` 是 `inline`，见 §15.4-A7）。
     * ⛔ 只对**闭合轮廓**有意义（齿轮齿廓 / 折纸三角 / 节点 / 行星）。
     */
    fun DrawScope.bevelStroke(
        path: Path,
        base: Color,
        lightAlpha: Float = 0.55f,
        darkAlpha: Float = 0.45f,
        width: Float = 1.6f
    ) {
        val off = width * 0.5f
        translate(-LX * off, -LY * off) {
            drawPath(path, VisualizerMath.towardWhite(base, 0.55f),
                alpha = lightAlpha, style = Stroke(width = width))
        }
        translate(LX * off, LY * off) {
            drawPath(path, VisualizerMath.darken(base, 0.55f),
                alpha = darkAlpha, style = Stroke(width = width))
        }
    }

    /**
     * 接触阴影：主体下方（沿光向**反**方向）偏移一层暗椭圆。
     * 成本 = 1 次 `drawOval`。
     */
    fun DrawScope.contactShadow(
        center: Offset,
        radius: Float,
        dropK: Float = 0.12f,
        alpha: Float = 0.28f
    ) {
        val ox = -LX * radius * dropK
        val oy = -LY * radius * dropK
        val rx = radius * 0.92f
        val ry = radius * 0.72f      // 压扁 → 像地面投影而非圆盘
        drawOval(
            color = Color.Black,
            topLeft = Offset(center.x + ox - rx, center.y + oy - ry),
            size = Size(rx * 2f, ry * 2f),
            alpha = alpha
        )
    }

    /**
     * **静态主体**的球面渐变（太阳 / 中心光源）。
     *
     * ⛔ 为什么是 `Cached`：`Brush.radialGradient` 的首参是
     * `vararg colorStops: Pair<Float, Color>`（`Brush.kt:296-306`），
     * 每帧新建会分配 Pair + 两个 `List` ⇒ 违反零分配红线。
     * **移动主体请用 `ProceduralTexture.sphereSprite`**，不要用本方法。
     *
     * @param key 调用方的缓存键（惯例 = `(w.toLong() shl 32) or h.toLong()`），
     *   与 `RadarGridRenderer.ensureBrush`（`BatchThreeRenderers.kt:64-75`）同范式。
     */
    fun shadeBrushCached(
        key: Long,
        center: Offset,
        radius: Float,
        base: Color,
        contrast: Float = 0.42f
    ): Brush

    /** Reinhard 色调映射：`c / (1 + c)`。⛔ `Plus` 叠加层数 ≥ 3 时必须调用 */
    fun toneMap(c: Float): Float = c / (1f + c)

    /** 未使用但保留给圆角矩形的默认圆角（避免各处重复构造） */
    val DefaultCorner: CornerRadius get() = CornerRadius(1.5f, 1.5f)
}
```

#### 15.2.5 `fx/OverlayFx.kt`（公开 API + 关键实现约束）

```kotlin
package com.nasmusic.tv.visualizer.fx

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath

/**
 * **零离屏**叠加式后处理。全部为 `DrawScope` 扩展，在渲染器 `draw` 末尾调用。
 *
 * 四条约束：
 *  ① 单次调用 ≤ 3 个 draw 指令（暗角 1 / 颗粒 1 / 扫描线 1 / 纹理 1）；
 *  ② `Brush` 与 `Shader` 按 `(w,h)` 缓存（`Brush.radialGradient` 会分配，见 §15.4-A4）；
 *  ③ 档位 `OFF` 时**全部方法立即 return**（LOW 档零成本，由 `FxBudgetTest` 护住）；
 *  ④ ⚠️ 依赖主 Canvas 的 `CompositingStrategy.Offscreen`
 *     （`VisualizerStage.kt:305-306`）—— 否则 `Overlay`/`Plus` 在部分 API 版本
 *     退化为 `SrcOver`（见 §15.4-A11），⛔ 不得移除那两行。
 */
object OverlayFx {

    /** 暗角：径向渐变，中心透明 → 边缘压暗 + 轻微偏冷 */
    fun DrawScope.drawVignette(
        ctx: RenderContext,
        strength: Float = 0.42f,
        coolShiftDeg: Float = 8f
    )

    /** 颗粒：`ProceduralTexture.tile(GRAIN, (seq and 7).toInt())` + `ImageShader` 平铺 */
    fun DrawScope.drawGrain(ctx: RenderContext, seq: Long, intensity: Float = 0.035f)

    /** 扫描线：`ProceduralTexture.tile(SCANLINE)` + `ImageShader` 平铺（替代 N 次 drawLine） */
    fun DrawScope.drawScanlines(ctx: RenderContext, dark: Float = 0.14f)

    /** 通用纹理平铺 */
    fun DrawScope.drawTexture(
        ctx: RenderContext,
        id: ProceduralTexture.Id,
        alpha: Float,
        blendMode: BlendMode = BlendMode.SrcOver
    )

    /** 曝光归一：`Plus` 叠加层数多时压低整体亮度，避免死白（§G8） */
    fun DrawScope.drawExposureCompensation(ctx: RenderContext, accumulatedLayers: Int)

    /** 释放内部缓存（`Brush` / `Shader`）。由 `VisualizerStage` 调一次，见 §15.3 第 19 行 */
    fun release()
}
```

**关键实现约束（照抄即对）**：

```kotlin
// ✅ 正确的平铺写法（零分配：Shader 按 (w,h,id,variant) 缓存）
private var tileShader: ImageShader? = null
private var shaderKey = 0L

fun DrawScope.drawGrain(ctx: RenderContext, seq: Long, intensity: Float) {
    if (FxBudget.of(ctx.quality) == FxLevel.OFF) return
    val bmp = ProceduralTexture.tile(ProceduralTexture.Id.GRAIN, (seq and 7L).toInt()) ?: return
    val key = (size.width.toLong() shl 32) or size.height.toLong()
    if (tileShader == null || shaderKey != key) {
        // ⚠️ ImageShader(image, tileModeX, tileModeY) —— 1.6.1 只有 3 个参数，
        //    没有 filterQuality（1.7+ 才有），见 §15.4-A2
        tileShader = ImageShader(bmp, TileMode.Repeated, TileMode.Repeated)
        shaderKey = key
    }
    drawRect(
        brush = ShaderBrush(tileShader!!),
        topLeft = Offset.Zero,
        size = size,
        alpha = intensity,
        blendMode = BlendMode.Overlay   // ⛔ 依赖主 Canvas 的 Offscreen 合成策略
    )
}
```

```kotlin
// ✅ 暗角（1 次 drawRect + 缓存的 Brush）
private var vignetteBrush: Brush? = null
private var vignetteKey = 0L

fun DrawScope.drawVignette(ctx: RenderContext, strength: Float, coolShiftDeg: Float) {
    if (FxBudget.of(ctx.quality) == FxLevel.OFF) return
    val key = (size.width.toLong() shl 32) or size.height.toLong()
    if (vignetteBrush == null || vignetteKey != key) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.62f
        val edge = VisualizerMath.darken(ctx.palette.accent, 0.20f)
        vignetteBrush = Brush.radialGradient(
            0.62f to Color.Transparent,
            1.00f to edge.copy(alpha = strength),
            center = c,
            radius = size.minDimension * 0.72f
        )
        vignetteKey = key
    }
    drawRect(brush = vignetteBrush!!, topLeft = Offset.Zero, size = size)
}
```

```kotlin
// ⛔ 反例：以下写法每帧分配 Pair + List，属零分配红线违规
fun DrawScope.bad(center: Offset, r: Float, base: Color) {
    drawCircle(brush = Brush.radialGradient(
        0f to base, 1f to Color.Transparent, center = center, radius = r))  // ❌ 每帧分配
}
```

#### 15.2.6 `fx/OffscreenFx.kt`（公开 API + 不变式）

```kotlin
package com.nasmusic.tv.visualizer.fx

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 离屏后处理（**仅 `FxLevel.FULL`**）。
 *
 * 代价参照 `MilkdropRenderer.kt:28-31` 的实测结论：全屏离屏回绘是 TV 填充率杀手
 * ⇒ 必须降采样（[downscale] ≥ 2）、且必须 `onExit` 显式 `recycle`。
 *
 * **不变式（与 `MilkdropRenderer` 同）**：`currCanvas` 恒包装 `curr`，交换时同步交换
 * （`UltraRenderers.kt:38-47` 的既有范式与理由）。
 */
class OffscreenFx(private val downscale: Int = 3) {

    private var a: ImageBitmap? = null
    private var b: ImageBitmap? = null
    private var aCanvas: Canvas? = null
    private var bCanvas: Canvas? = null

    /** 画布尺寸变化时重建；尺寸未变则 no-op */
    fun ensure(w: Int, h: Int)

    /** 显式 `recycle` 全部缓冲（API 22-25 上 Bitmap 像素在 native 堆） */
    fun onExit()

    /** 亮度阈值提取 + 三级降采样（Kawase 双滤波） + 加法合成 */
    fun DrawScope.drawBloom(
        threshold: Float = 0.72f,
        strength: Float = 0.55f,
        radiusK: Float = 0.035f
    )

    /** 真通道分离色差（R 向 +x、B 向 −x），替代 `VintageTvRenderer` 的整层 tint 近似 */
    fun DrawScope.drawChroma(offsetPx: Float, alpha: Float = 0.55f)
}
```

> ⚠️ **`OffscreenFx` 的接入点是唯一涉及既有 UI 层改造的地方**，风险最高 ⇒
> 排在阶段 6（可选，需 §十三 裁决项 2 通过）。
> 若采纳，`VisualizerStage` 的改造方式是：
> ```kotlin
> // 在 Canvas 的 DrawScope 内、renderer.draw(...) 之后
> if (fxLevel == FxLevel.FULL) offscreenFx.drawBloom(...)
> ```
> 但「先把整个画面画进离屏」需要 `CanvasDrawScope().draw(density, layoutDirection, canvas, size) { ... }`
> （**已核实是 `inline`**，`CanvasDrawScope.kt:531`）—— 实现细节见 §15.4-A6。

### 15.3 现有文件改造点（逐文件 + `file:line`）

> 格式：**改哪一行 / 改什么 / 为什么**。行号为 **v2.37.6（2026-09-28）** 实测。
> ⛔ 实现前先 `git log --oneline -5` 确认没有并发会话改过这些文件。

#### 15.3.1 渲染器（按 §15.1.2 的序号）

| # | 文件 | 改造点（`file:line`） | 改什么 |
|---|---|---|---|
| 1 | `BasicRenderers.kt` | `:76` 环偏移；`:96-102` 环描边单色；`:104-116` 壁灯小点；`:113` 3 桶合并；`:119-121` 桶 alpha | E03 隧道穿越：环按 z 插值色 + 雾化；桶 3→6、alpha 改 `fade²`；壁灯加径向光晕；`cx,cy` 加中心光斑；末尾 `OverlayFx` |
| 1 | 〃 | `:154` 旋转累加；`:198-212` `glowLayers` 假光晕；`:215-230` 峰值帽；`:232-243` 节拍爆环；`:246-253` 中心 3 层圆盘；`:257-280` 8 段 `drawArc` | E05 圆形频谱环：条体改双边明暗；**删** `glowLayers` 循环改单层柔光；外圈 8 段 → 1 次 `sweepGradient`；中心盘 3 层 → 1 次径向渐变；峰值帽叠柔光 |
| 1 | 〃 | `:298-326`（整个 `draw`） | E07 频率山峦：加三段式背景；`lineTo` → `quadraticBezierTo`；层间透视；描边加高光线；填充改垂直渐变 |
| 2 | `AdvancedRenderers.kt` | `:46` `rotation += 0.15f + bpm/1200f`；`:75-77` 2 桶；`:83-84` 中心核 | E11 星系螺旋：**`dt` 化旋转**（帧率绑定缺陷）；4 桶 + 远臂缩小；加尘埃带 `Path`；中心核改径向渐变 |
| 2 | 〃 | `:112` `val w = 128`；`:122` 上移 1px；`:128-136` 底部新行 64 次 `drawRect`；`:130-131` 亮度曲线 | E12 频谱瀑布：`w` → `barCount×4`；历史行加衰减 `drawRect`；新行按 16 hue 桶合并 `Path`；亮度曲线改 `pow(v,0.72f)`；加水平线 tile |
| 2 | 〃 | `:210-213` **`timeMs × 系数`（红线违规）**；`:242` 连线单色；`:248-252` 顶点圆点 | E13 液态网格：**相位改 `dt` 累加**；连线按高度插值 + 4 桶；顶点改拉长椭圆（参数方程 + `addOval`）；加 `WATER`/`CAUSTIC` tile |
| 2 | 〃 | `:311` `timeMs % 8` 节流；`:314-316` 5 个同心细环；`:328-334` 单色细描边 | E15 液态涟漪：节流统一到 `dt`；同心细环 → 2 环 + 中心光斑；每环改内外双边；`Stroke.width` 随 `progress` 变细 |
| 2 | 〃 | `:419-425` 字形 blit；`:433` 字符集 `'0','1'`（**二进制雨，⛔ 不扩**）；`:449-462` 生成 8 张字形 | E16 数字雨：头部加径向光晕；头上加垂直 `linearGradient` 拖影；字形加描边 + 垂直渐变、档位 4 → 5（字形 8 → 10 张）；**字符集保持 0/1** |
| 2 | 〃 | `:541` 双重循环 160×160；`:554` 连线单色；`:564` 星点纯色圆 | E17 星座：连线判定改**空间网格分桶**（12720 → ~1440）；连线分 2 档；亮星加十字星芒；加 `STARFIELD` |
| 3 | `ParticleRenderers.kt` | `:67` 底纹 alpha；`:70-92` 爆发分支；`:106-112` 粒子纯色圆 | E14 节拍烟花：爆发加冲击波环 + 中心闪光；粒子加按速度的拖尾（按 hue 分 8 桶）；底纹 alpha 0.12 → 0.18 + 径向纵深 |
| 3 | 〃 | `:177-188` `step = 3` 行优先 + `n >= cap` 提前 break；`:250-259` **350 次 `drawCircle(Plus)`**；`:171-174` 固定 `textSize` + 左对齐；`:226-228` 固定 0.78 缩放；`ParticlePool.kt:104-110` `LIFE -= 0.004` 无重生 | E19 粒子文字：**P1 死亡改重生**（修 4.2s 永久空白 + 目标错位）→ **P3 逐行 `getPixels`** → **P4 按行配额采样（照搬 E23 歌词点阵）** → **P2 `dotPath` + `addPath` 批量（350 draw → 1，零 `Rect` 分配）** → P5 `step = 1` → Q1 测量居中 / Q2 墨迹包围盒适配 / Q3 内外圈双 Path / Q4 `Plus` 只留高亮 / Q5 后处理（§B5） |
| 4 | `UltraRenderers.kt` | `:91` `alpha 0.88–0.94`；`:96-107` 回绘；`:90` hue 流动 | E18 反馈残像：回绘后叠 `alpha 0.06` 黑层抑制灰白；`p` 分 3 次不同 `scale` 叠绘（3-tap 模糊）；hue 接 `sectionEnergy` |
| 4 | 〃 | `:163-164` `gw=16, gh=9`；`:214-221` 噪声更新；`:239-245` 粒子圆点 | E20 等离子流场：网格 → 24×14 + fbm 双倍频（第二个 `FloatArray`）；粒子改拉长椭圆；加等离子底纹 tile |
| 5 | `EcgWaveRenderer.kt` | `:250-259` **`HB_T`/`HB_V` 只 14 点 + 首尾相接成锯齿**（全表**无等电位平线**）；`:217-229` `heartbeatAt` 线性扫描插值；`:147` **`ageSec = (colMs − beatAtMs)` 两个累加 float 相减**（漂移）；`:64/68/70` `colMs`/`beatAtMs`/`lastFireMs`；`:239` `MIN_GAP_MS`；`:248` `REBASE_AT_MS`；`:178-187` 双层描线；`:194-208` 网格等亮度 | E24 心跳：**P0 波形真实化** —— ① `colMs`/`beatAtMs`/`lastFireMs` → **整数列号** `colIdx`/`beatCol`/`lastFireCol`（顺带**删掉** `REBASE_AT_MS`，`MIN_GAP_MS 800f` → `MIN_GAP_COLS 72`）；② `HB_T`+`HB_V` → **单表 `HB`（50 点 = 一列一个采样）**，按临床时程重排（PR 178 / QRS 89 / ST 111 / T 160 / QT 360 ms，P +0.14 / Q −0.11 / R +1.00 / S −0.28 / T +0.27，**R 峰对齐第 19 列**）；③ `heartbeatAt` → **O(1) 整数索引**（删插值）；④ `private` → `internal`（单测可见）。**观感** —— 新增延迟波形层（`FloatArray` 原地 `×0.90f` 衰减，第 3 层 `Path`）；网格按距中心距离衰减 + 竖线间距改 **0.2s（18 列）**；`Plus` 只留中线；加 `drawScanlines`；可选基线漂移（相位累加器） |
| 6 | `HypnoticFunctionRenderer.kt` | `:428-429` 曲线双层；`:623-664` 网格/坐标轴/刻度 | E25 催眠：曲线加"上方偏移高光线"；网格分 3 档明度；加 `PAPER` tile |
| 7 | `LyricsDotMatrixRenderer.kt` | `:698`/`:703` **每点 2 个 `addOval(Rect)`**（14,400/帧）；`:277`/`:300` **两遍 `getPixel`**（≈71,000 次 JNI）；`:641` `phaseVal` 死变量、`:626` `textLen` 死参数、`:307` `SIZE` 恒 1.0；`:683`/`:690-691` 循环内重算全局量；`:543` `reset()`；`:250-253` `coerceIn(1f,3f)`；`:609-612` 亮档 alpha 0.98 | E23 歌词点阵（**性能优化为主**）：P1 `dotPath` + `addPath`（`Rect` 14,400 → 0）→ P2 逐行 `getPixels`（71,000 → 116）→ P3 提全局量 → P4 删死代码 → P5 `rewind()` → P6 `step = 1` → Q2 亮档去过曝 / Q4 后处理（§B7） |
| 8 | `BatchThreeRenderers.kt` | `:64-75` **`ensureBrush` 尺寸未变即 return ⇒ 扫掠扇钉死不转（真 bug）**；`:102` 转速被 treble 拉 6×；`:136-145` 扫掠扇；`:161-187` **`drawSpectrumArcs` 把回波焊在扫掠线后方**（`:171-172` `sweepAngle − i*TAU/segs*2f`）、亮度取瞬时频谱（`:182`）、跨度 **2 整圈**；`:88-89` 手写 EMA；`:106-113` 同心圆；`:126-132` 射线 | E30 雷达：**P0 真实 PPI 重建** —— **删掉 `drawSpectrumArcs` 整段**，改**目标池**（`FloatArray(24×8)`：`BEARING/RANGE/DRIFT_B/DRIFT_R/BIN/BRIGHT/PEAK/LIFE`）+ `crossed()` 左开右闭区间判定（抗跨 0 回绕）+ `BRIGHT` 线性衰减（`holdSec ≈ 1.15 × 周期`，撑到下一圈）+ 连续漂移 + **寿命到重生**（不是消失）+ 回波按 `BEARING` 画短弧（`drawArc` + `Size`，⛔ 不用 `Rect`）+ 距离衰减；**修扫掠扇**用 `withTransform({ rotate(sweepAngleDeg, Offset(cx,cy)) })`（⛔ 不重建 `Brush`）；**转速改恒定**、treble 改去缩短余辉；`energy` 控制 `activeCount = 8..24`。**观感** —— 同心圆按半径衰减 + 中心径向绿光；3 档明度；EMA 换 `AudioSmoother`；`OverlayFx` 后处理 |
| 8 | 〃 | `:302-303` 手写 EMA；`:374` `baseL * 0.62f`；`:387` 纯色填充；`:392-396` `Plus` 微光 | E31 折纸：EMA 换 `AudioSmoother`；明暗面改 `lambert(法线)`；折叠中三角加 `contactShadow`；折边加受光亮线（合并 `Path`）；加 `PAPER` tile；微光 alpha 0.06 → 0.035 |
| 8 | 〃 | `:462-470` **`src` 只映射 `n / 2 = 32` 前半区 + 左右严格镜像 ⇒ 实测只覆盖 bin 0..28/29/30、命中不同桶仅 10/14/18（恰为列数一半）**；`:474` `STEPS = 16f` 量化（`v ∈ [0, 0.125)` 高度恒为 1 格）；`:487` `level = (st + 1f) / steps`（随 `steps` 抖动 = 闪烁）；`:450-453` 水平/垂直共用同一 `gap`；`:504` `drawRect` 硬边；`:510-513` 碎裂 4 小方块 | E32 阶梯：**P0-0 全柱独立频段映射** —— 删 `n / 2` 改覆盖 `0..63`；`band: IntArray(cols + 1)` 边界表（`onEnter` 建一次）+ `rebuildBands` + `colsOf` + `blockAlpha`（三者需 `internal` 供单测）；`PERCEPT_K = 1.6f` 感知分桶；`STEPS` 16 → **24**；静音门槛改 `MIN_AMPLITUDE` 且 `steps` 下限 1；`level = (st + 1f) / STEPS`；`gapX`/`gapY` 分离。**观感** —— `drawRect` → `drawRoundRect`（`CornerRadius(1.5f)`，⛔ 命名参数，见 §15.4-A5）；加左侧接缝暗线；加外发光；碎裂加确定性位移 + 角度 |
| 9 | `BatchTwoRenderers.kt` | `:296-301` 轨道椭圆单色；`:305-329` 深度排序；`:347-359` 太阳 3 层同心；`:423/456, 495-516` 土星环单色；`:425` 行星纯色圆；`:449-454` 固定高光 | E29 轨道：行星改 `sphereSprite` + 方向性高光（`specular`）；加昼夜明暗线 + rim；土星环拆 2 条（留环缝）；轨道线加辉光层；太阳改 `shadeBrushCached`；末尾 `OverlayFx` |
| 10 | `BatchFourRenderers.kt` | `:937` 径向纵深；`:944` vignette；`:985-999` grain tile；`:1233` 齿内 fill；`:1238` **齿轮描边单色**；`:1271-1289` 轴心同心硬边；`:1293-1316` 啮合火花 | E33 齿轮：`buildGearVerts`（生成期）按法线分 `lightPath`/`darkPath`；齿顶圆加扫过高光弧；啮合方向加偏移暗弧（AO）；轴心改 `shadeBrushCached` + 方向性高光；vignette/grain 换 `OverlayFx` |
| 10 | 〃 | `:1334-1565`（`FractalTreeRenderer` 全体） | E34 分形：枝干锥度 + 梢部提亮；末级加叶形（3 条 `Path`）；加径向纵深 + `STARFIELD` |
| 10 | 〃 | `:1566-1718`（`LightBeamsRenderer` 全体） | E35 光轴：光束改 `linearGradient`（按 `(w,h)` 缓存）；加雾 tile；加 40–60 尘埃点（3 条 `Path`）；交汇处加镜头光斑 |
| 11 | `MoleculeRenderer.kt` | `:454-459` 双线键；`:476-490` 原子纯色圆；`:492` 分子式；`:513-515` **3 层同心辉光** | E37 分子：原子改 `sphereSprite` + 高光点；键改上下明暗；辉光 3 层 → 1 次径向（或 2 层）；分子式偏移重绘发光（⛔ 不用 `setShadowLayer`，API 22 可能不生效）；末尾 `OverlayFx` |
| 12 | `VintageTvRenderer.kt` | `:331-352` **240 次 `drawLine`**；`:357-383` 稀疏方块噪点；`:511-531` 整层 `ColorFilter` 色差；`:600-613` **vignette 只按 `w` 缓存**；`:669` `nativeCanvas`；`:680-695` 胶片孔 | E38 怀旧：扫描线 → `SCANLINE` tile 平铺；噪点 → `GRAIN` tile（`Overlay`）；色差按裁决项 2 决定真/伪；**修 vignette 缓存键为 `(w,h)`**；胶片孔层加轻微内缩（伪桶形畸变）；歌词/OSD 加磷光余晖 |
| 13 | `DnaRenderer.kt` | `:577` 纯色底；`:605/611` 4 桶单色 `drawLine`；`:656-661` 3 层辉光；`:665` 固定高光 | E40 DNA 双螺旋：骨架加"受光窄边"（按深度桶合并 2 条 `Path`）；背景改径向纵深；横档加柔光；`specular` 方向化；末尾 `OverlayFx` |
| 14 | `WorldRenderer.kt` | `:679` 陆地单色 fill；`:691-692` 双层描边；`:783-792` **grain 平铺接缝风险**；`:1446` vignette；`:1481` 城市 `Brush` | E41 世界：**修 grain 平铺**（`TileMode.Repeated` 或整除数尺寸）；陆地叠纬度渐变 + 裁剪颗粒（仅 HIGH）；加球面明暗覆盖层；城市光点 HIGH 档加外溢；vignette 中心随 `sectionEnergy` 微漂 |
| 15 | `photo/PhotoRenderer.kt` | `:87-…`（`draw` 末尾） | E39 照片墙：加轻量 `drawVignette(0.34f)` + `drawGrain(seq, 0.018f)`；转场期叠 `photoA` 偏移副本做运动模糊（**复用已有双图，零新增缓冲**） |
| 16 | `photo/PhotoTransitionId.kt` | 枚举体（76 项，`Phase.P1` 段） | E39 照片墙：新增 6 项 `LIGHT_FLASH_*` / `LIGHT_LEAK_*` / `LIGHT_BLOOM_*` 等（命名遵循既有前缀风格） |
| 17 | `photo/PhotoTransitionRegistry.kt` | `P1` 注册表 | E39 照片墙：注册 6 个实现（`implemented(Phase.P1)` 从 28 → 34） |
| 18 | `photo/transitions/LightFxP1Transitions.kt`（**新建**） | — | E39 照片墙：6 个转场实现（白闪 / 漏光 / 光斑过曝 / 色差爆闪 / 暗角收缩 / 胶片刮痕），全部走 `PhotoTransition` 接口 |
| 19 | `ui/components/VisualizerStage.kt` | `:193-200` `DisposableEffect` | 阶段 1：在 `onDispose` 的 `swapper.release()` **之后**加 `OverlayFx.release()` + `ProceduralTexture.release()`（⛔ 顺序：先释放渲染器、再释放共享纹理） |
| 19 | 〃 | `:301-330` 主 Canvas 的 DrawScope | 阶段 6（可选）：在 `with(cur) { draw(f, renderCtx) }` 之后按 `FxLevel.FULL` 调 `offscreenFx.drawBloom(...)` |
| 19 | 〃 | `:305-306`、`:352-353` `compositingStrategy = CompositingStrategy.Offscreen` | ⛔ **不得删除/修改** —— 非 `SrcOver` 混合模式依赖它（§15.4-A11） |
| 19 | 〃 | `:522` `private const val ALPHA_EPS = 0.004f` | ⛔ 不得改 —— `fadeAlpha` 低于它时整个 `draw` 被跳过（`：328`/`：364`），后处理因此自动随之停止 |
| 20 | `PhotoTransitionRegistryTest.kt` | `:58-59` `76`；`:64` `15`；`:66` `43`；`:223` `15`（P0 参数表）；`:225` 时长断言 | E39 照片墙：新增 6 项转场时**必须同步**这些断言（详见 §15.5） |

#### 15.3.2 最容易搞错的「复用」（本节为 §三 的补充，全部源码级核实）

| # | 误解 | 事实（`file:line`） | 正确做法 |
|---|---|---|---|
| 9 | 「`RendererSwapper` 的 crossfade 是**没实现**的死代码」 | ⚠️ **实现了**（`RendererSwapper.kt:102-107` + `:120-126`，有单测），只是 UI 侧**刻意**恒传 `false`（`VisualizerStage.kt:176-178` 的注释：「自动导演档删除后 UI 层已无使用场景，因此恒为 false」） | 不要"顺手修好"。要启用必须走 §十三 裁决项 4 |
| 10 | 「`VisualizerViewModel.kt:402-403` 说主题一变 crossfade 就接管了，所以是开着的」 | ⛔ **该注释是陈旧的**（与 `VisualizerStage.kt:178` 的 `false` 矛盾）—— 属"移除自动导演档"时的漏网注释 | 顺手修正该注释（§15.7 第 3 条）；⛔ 不要按注释去改代码 |
| 11 | 「`onExit` 由渲染器自己调」 | ⛔ 全部由 `RendererSwapper` 调（`RendererSwapper.kt:104/111/112/124/130/131`），**渲染器从不自调** | 共享纹理（`ProceduralTexture` / `OverlayFx`）**不能**放渲染器的 `onExit` 里释放（会被多次调用 + 影响其他渲染器），必须放 `VisualizerStage.kt:193-200` |
| 12 | 「`frame.timeMs` 可以用作相位」 | ⛔ `timeMs` 是**开机毫秒**（`AudioFrame.kt:55-56`），大基数下 float 精度丢失 → 相位冻结/跳变（本项目 §10.176 红线）。现存违规：`AdvancedRenderers.kt:210-213` | 相位一律 `elapsed += dt`（`dt` 钳 0.1s），见 §7.3 末尾 |
| 13 | 「`bass` 能反映鼓点强弱」 | ⛔ `bass` 走峰值跟随归一化，**鼓点时刻恒为 1.0**（`AudioFrame.kt:36-44` 的 KDoc 明写） | 需要鼓点强弱差异时用 **`bassRaw`**（§7.3） |
| 14 | 「新增 `fx/` 文件会被 `PhotoRenderContractScanTest` 误判」 | ✅ 它只扫 `PhotoGeometry.update` 的实参个数（`PhotoRenderContractScanTest.kt:70-88`），与 `fx/` 无关 | 无需处理；但你**新写**的扫描门禁必须带 6 条自证（§八） |
| 15 | 「批量画同形状圆点，要么 `N × drawCircle`（慢），要么 `Path.addOval(Rect)`（要分配 `Rect`）」 | ⛔ **两条都不是最优**。`Rect` 是 `data class` ⇒ `addOval(Rect(...))` **每点分配**（§G12）；`Offset` 是 **`value class`** ⇒ `Offset(x, y)` **不分配**（`Offset.kt:61`）；`Path.addPath(path, offset: Offset = Offset.Zero)` 存在（`Path.kt:205`），Android 实现是 `internalPath.addPath(path, offset.x, offset.y)`（`AndroidPath.android.kt:180-182`）—— **一次 native 调用、零 Java 分配**；`Path.rewind()` 比 `reset()` 快（保留内部结构，`Path.kt:220-228`） | **零分配批量圆点**：① 建**一个** `dotPath`（`moveTo` + 4 段 `cubicTo`，`k = 0.5523f`，全 float 参数 ⇒ 零分配）；② 每个点 `targetPath.addPath(dotPath, Offset(px, py))`；③ **1 次 `drawPath`**。半径不同的场合按半径**量化分档**，每档一个 `dotPath`（§B7 P1：6 档 × 3 tier × 2 层 = 36 个）。⛔ **不要**用 `Canvas.drawRawPoints(PointMode.Points, FloatArray, Paint)` —— 它的 Android 实现是逐点 `internalCanvas.drawPoint(x, y, paint)` 循环（`AndroidCanvas.android.kt:373-381`），**draw 指令数一点没降**（§15.4-A26） |

### 15.4 源码级 API 核实结论（本轮实测 · 2026-09-28 首轮 / 2026-09-29 增补 §15.4.4）

> **为什么要有这一节**：写"可直接粘贴的签名"时，凭记忆写的 API 会**沉默地错**
> （错的重载能编译但语义全变、错的行为假设要等真机才暴露）。
> 本节全部结论来自**本机 Gradle 缓存里的 sources jar**（`androidx.compose.ui:ui-graphics-android:1.6.1`）
> 与**项目源码**，每条带 `file:line`。

#### 15.4.1 版本基线

| 编号 | 结论 | 证据 |
|---|---|---|
| **A1** | 项目实际解析到 **Compose UI / ui-graphics 1.6.1**（不是 BOM 版本号 2024.02.00 的字面值） | 实测 `:app:dependencies --configuration debugCompileClasspath` 输出 `androidx.compose.ui:ui:1.6.1` / `ui-graphics:1.6.1`；`app/build.gradle.kts:153` 为 `platform("androidx.compose:compose-bom:2024.02.00")` |

#### 15.4.2 图形 API 签名（照抄即对）

| 编号 | 结论 | 证据（sources jar 内路径:行） |
|---|---|---|
| **A2** | `ImageShader(image, tileModeX = Clamp, tileModeY = Clamp)` —— **只有 3 个参数，没有 `filterQuality`**（那是 1.7+ 才加的）。⛔ 写 4 参会**编译失败** | `commonMain/androidx/compose/ui/graphics/Shader.kt:120-124` |
| **A3** | `fun ShaderBrush(shader: Shader)` 存在；`abstract class ShaderBrush` 内部会按 `Size` 缓存 shader（`internalShader`/`createdSize`） | `Brush.kt:633-639`、`Brush.kt:646-680` |
| **A4** | ⛔ **`Brush.radialGradient` / `sweepGradient` / `linearGradient` 的首参是 `vararg colorStops: Pair<Float, Color>`** —— 每次调用都会分配 N 个 `Pair` + 内部两个 `List`。**在 `draw` 内新建 `Brush` = 零分配红线违规** | `Brush.kt:296-306`（radial）、`:372-379`（sweep）、`:72-79`（linear） |
| **A5** | ⛔ **`drawRoundRect` 两个重载的 `alpha` / `style` 顺序相反**：brush 版是 `(brush, topLeft, size, cornerRadius, alpha, style, …)`；color 版是 `(color, topLeft, size, cornerRadius, style, alpha, …)` ⇒ **必须用命名参数**，否则 `alpha` 与 `style` 会静默错位 | `DrawScope.kt:591-605`（brush）、`:616-631`（color） |
| **A6** | `CanvasDrawScope.draw(density, layoutDirection, canvas, size, block)` 存在且为 **`inline`** —— 这是"把渲染器画进离屏 `ImageBitmap`"的唯一入口 | `drawscope/CanvasDrawScope.kt:531-537` |
| **A7** | ⛔ **`DrawScope.translate` / `rotate` / `scale` / `clipPath` / `withTransform` 全部是 `inline`**（lambda 内联展开、**不产生 lambda 对象**）⇒ 修正本项目既有的"`withTransform` 每帧分配 2 对象"说法 | `DrawScope.kt:116`（translate）、`:137`（rotate）、`:174/192`（scale）、`:234`（clipPath）、`:259`（withTransform） |
| **A8** | `Path.quadraticBezierTo(x1,y1,x2,y2)` 存在；`Path.translate(offset)` 是**原地修改**；`Path.addPath(path, offset)` 存在；`Path.reset()` / `addOval(Rect)` 存在 | `Path.kt:80`、`:233`、`:205`、`:218`、`:170` |
| **A9** | `ImageBitmap(width, height, config = Argb8888, hasAlpha = true, colorSpace = Srgb)`；`Canvas(image: ImageBitmap)` | `ImageBitmap.kt:249-256`、`Canvas.kt:29` |
| **A10** | `BlendMode.Plus = BlendMode(12)`、`BlendMode.Overlay = BlendMode(15)` | `BlendMode.kt:192`、`:265` |
| **A11** | `Color.toArgb(): Int` 存在（球面贴图缓存键用它）；`value class Color(val value: ULong)` 的 `value` 是**公开**的 | `Color.kt:638`、`:119` |
| **A12** | `ImageBitmap.asAndroidBitmap()` / `Bitmap.asImageBitmap()` 存在（贴图生成与 `recycle` 用） | `AndroidImageBitmap.android.kt:61`、`:32` |
| **A13** | `DrawScope.drawImage` 有 3 个重载（`(image, topLeft, …)` / `(image, srcOffset, srcSize, dstOffset, dstSize, …)` / `(image, srcOffset, srcSize, dstOffset, dstSize, alpha, style, colorFilter, blendMode, filterQuality)`） | `DrawScope.kt:473`、`:514`、`:551` |

#### 15.4.3 项目侧约束（决定"接线点在哪"）

| 编号 | 结论 | 证据 |
|---|---|---|
| **A14** | ⛔ **非 `SrcOver` 混合模式依赖离屏合成策略**：主/旧效果层 Canvas 都设了 `compositingStrategy = CompositingStrategy.Offscreen`，源码注释写着「T9：叠加发光需要离屏层，否则部分 API 版本退化为 SrcOver」⇒ 新增的 `Overlay`/`Plus` 后处理**依赖这两处既有设置** | `VisualizerStage.kt:305-306`、`:352-353`（注释在 `:304`） |
| **A15** | `fadeAlpha.floatValue > ALPHA_EPS`（`0.004f`）不满足时**整个 `draw` 被跳过** ⇒ 后处理只要写在 `draw` 末尾，就会自动随淡入淡出停止，无需额外判断 | `VisualizerStage.kt:328`、`:364`、`:522` |
| **A16** | 渲染器 `draw` 已被 `try/catch` 包裹（异常只跳过当前帧并告警一次）⇒ 新增后处理若抛异常**不会崩溃**，但会静默丢帧 ⇒ 门禁必须覆盖 `OverlayFx` 的边界输入（`size` 为 0、`palette` 为 fallback） | `VisualizerStage.kt:333-341` |
| **A17** | `PhotoTransitionRegistryTest` 有 **5 处硬断言**依赖转场数量：`76`（枚举总数）、`15`（P0）、`43`（P1 累计）、`15`（P0 参数表）、`900+300`（百叶窗时长）⇒ C5 新增转场必改前 4 处 | `PhotoTransitionRegistryTest.kt:58-59`、`:64`、`:66`、`:223`、`:228` |
| **A18** | `VisualizerViewModel.kt:402-403` 的注释「主题一变，`RendererSwapper` 的既有 crossfade 就接管了」**与事实不符**（`VisualizerStage.kt:178` 恒传 `false`）⇒ 陈旧注释，列入 §15.7 待修 | `VisualizerViewModel.kt:402-403` vs `VisualizerStage.kt:176-178` |
| **A19** | `BatchTwoRenderers.kt:256` 附近注释「⛔ 绝不能用常量种子」是对的（切效果时 `RendererSwapper.sync` 走 `factory(theme)` 造全新实例）⇒ 本方案新增的 `ProceduralTexture` 若用常量种子，**同一进程内多次进入效果会得到相同纹理** —— 这是**期望行为**（纹理应稳定），但**粒子/布局类随机必须继续用每渲染器独立的 `VisualizerRandom`** | `BatchFourRenderers.kt:256`、`VisualizerRandom.kt` |
| **A20** | `androidx.compose.material3` **不在编译类路径**（既有结论，本轮未变）⇒ `fx/` 包只能 import `compose.ui` / `compose.foundation` / `androidx.tv.material3`；⛔ 不要 import `material3` 的 `Color`/`Brush` 相关别名 | 全仓库 `import androidx.compose.material3` 计数为 0；`docs/AGENTS.md` 已记录 |

#### 15.4.4 采样与批量绘制 API（2026-09-29 增补 —— E19 / E23 性能优化 + E24 波形表 + E30 旋转扫掠扇 + E32 频段映射专用）

> 来源：本机 `~/AppData/Local/Android/Sdk/platforms/android-34/android.jar`（`javap` 实测）
> + 本机 Gradle 缓存的 `ui-graphics-android-1.6.1-sources.jar` / `ui-geometry-android-1.6.1-sources.jar`。

| 编号 | 结论 | 证据（jar 内路径:行 / `javap` 输出） |
|---|---|---|
| **A21** | ✅ **`Bitmap.getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height)` 存在**（返回 void）。⇒ `docs/archive/music-visualizer-dev-plan.md:876` 把 `getPixel()` 标为"**唯一可用**"**是错的**，`LyricsDotMatrixRenderer.kt:277`/`:300` 与 `ParticleRenderers.kt:181` 的"只能逐像素扫"结论随之作废（§G11） | `javap -classpath android-34/android.jar android.graphics.Bitmap` 输出 `public void getPixels(int[], int, int, int, int, int, int);`；同项目已有正确用法 `backend/photo/YuNetFaceDetector.kt:239` |
| **A22** | `Bitmap.getPixel(int, int)` 是**逐点 JNI**（一次调用一次跨语言 + 边界检查 + 像素格式转换）⇒ 行批读 `getPixels(..., width=1)` 是"**每行 1 次**"而不是"每像素 1 次" | 同上 `javap` 输出 `public int getPixel(int, int);` |
| **A23** | ⛔ **`androidx.compose.ui.geometry.Rect` 是 `data class`**（4 个 `val` Float）⇒ `Rect(...)` **每次构造都分配**；`Path.addOval(oval: Rect)` **只有这一个重载**（**没有** `(left, top, right, bottom)` 版本）⇒ 想加圆就必然分配一个 `Rect` | `ui-geometry` `Rect.kt:31-32`（`@Immutable data class Rect`）；`ui-graphics` `Path.kt:170` `fun addOval(oval: Rect)`（全文无第二个 `addOval`） |
| **A24** | ✅ **`androidx.compose.ui.geometry.Offset` 是 `value class` over `Long`** ⇒ `Offset(x, y)` **不分配对象**（这是"用 `Offset` 做平移"方案成立的前提） | `ui-geometry` `Offset.kt:61` `value class Offset internal constructor(internal val packedValue: Long)` |
| **A25** | ✅ `Path.addPath(path: Path, offset: Offset = Offset.Zero)` 存在；Android 实现 `internalPath.addPath(path.asAndroidPath(), offset.x, offset.y)` —— **1 次 native 调用、零 Java 分配**。⚠️ `Path.rewind()` 存在且"**保留内部数据结构**供快速复用"（优于 `reset()`，后者丢弃）；`Path.moveTo/lineTo/quadraticBezierTo/cubicTo/relativeCubicTo` 全部是 float 参数（⇒ 建圆无需 `Rect`） | `Path.kt:205`（addPath）、`:220-228`（rewind）、`:57/67/80/95/103`（float 原语）；`AndroidPath.android.kt:180-182` |
| **A26** | ⛔ **`Canvas.drawRawPoints(pointMode, points: FloatArray, paint)` 看起来是"批量"，实际不是**：`AndroidCanvas` 的实现对 `PointMode.Points` 走 `private fun drawRawPoints(points, paint, stepBy = 2)`，内部是 `while (i < points.size - 1) { internalCanvas.drawPoint(x, y, frameworkPaint); i += stepBy }` —— **逐点一次 native `drawPoint`**。⇒ 用它**不能**降低 draw 指令数，hwui region 合并风险（§R3）**不会下降** | `AndroidCanvas.android.kt:362-381` |
| **A27** | ✅ `DrawScope.drawPath(path, color, alpha, style, colorFilter, blendMode)` 存在（6 参，全部有默认值）⇒ 一条 Path 一次调用即可带 `BlendMode.Plus` | `DrawScope.kt:809-816` |
| **A28** | ⚠️ `DrawScope.drawPoints` **只有 `List<Offset>` 重载**（两个：color 版 / brush 版）⇒ 想用 `drawPoints` 就必须造 `List<Offset>`（**分配**）。FloatArray 版只在 `Canvas` 接口上（且见 A26） | `DrawScope.kt:856-866`、`:884-894`；`Canvas.kt:566`、`:578`（`drawRawPoints`） |
| **A29** | `AndroidPaint` 在 `androidx.compose.ui.graphics` 里是 **public class**（`class AndroidPaint(private var internalPaint: android.graphics.Paint) : Paint`），`Paint.asFrameworkPaint()` 是接口成员 ⇒ 需要 native Paint 时可用；⚠️ 但 `ParticleRenderers.kt:4-5` / `LyricsDotMatrixRenderer.kt:4-5` 的 `AndroidPaint`/`AndroidCanvas` 是 **`android.graphics.*` 的 import 别名**（不是 compose 那两个同名类），改代码时别搞混 | `AndroidPaint.android.kt:38`、`:49`；`ParticleRenderers.kt:4-5` |
| **A30** | ⛔ **E24 的波形表必须"一列一个采样"，索引用整数列号** —— `EcgWaveRenderer` 的 `ageSec = (colMs − beatAtMs) * 0.001f` 是**两个累加 float 相减**：`colMs` 每列 `+= 1000/90`（`:150`），播到 16 分钟时量级 ~1e6 ms，float（24 位尾数）在此量级的 ULP ≈ **0.0625 ms**，上千次累加的误差可达 **~1 ms**（≈ 表步长的 9%）。若表按"半列步长 + 线性插值"，查表位置会在相邻两段间抖动 ⇒ **每拍的 R 峰高度忽高忽低**。⇒ 正确做法：① 用**整数列号**算列龄（完全精确）；② 表**一列一个采样**（显示分辨率 = 1 列，再细的点画不出来）；③ `heartbeatAt` 退化成 **O(1) 整数索引、零插值**。副作用：`REBASE_AT_MS`（`:248`）这套 float 回绕 hack **可以删掉** | `EcgWaveRenderer.kt:64/147/150/155-159/248`（现状）；`COL_STEP_MS = 1000/90 = 11.111 ms` ⇒ 心搏 50 列 = 555.6 ms < `MIN_GAP_MS 800` 仍成立 |
| **A31** | ✅ **`withTransform` 可以"零分配地旋转一个预分配 `Brush`"** —— `inline fun DrawScope.withTransform(transformBlock: DrawTransform.() -> Unit, drawBlock: DrawScope.() -> Unit)` 的实现是 `with(drawContext) { canvas.save(); transformBlock(transform); drawBlock(); canvas.restore() }` ⇒ ① **lambda 内联、不分配对象**；② `drawContext.transform` 是 **`DrawContext` 的缓存成员**（不是每次 `new`）；③ 只有 `canvas.save()/restore()` 两次调用。`DrawTransform.rotate(degrees: Float, pivot: Offset = center)` 是**接口成员**（不是扩展函数）。⛔ **不能**直接调 `drawContext.transform.rotate(...)` 而不配对 `save/restore` —— 那会**永久改画布矩阵**。⚠️ 这条同时解释了为什么 E30 **必须**用 `withTransform` 而不是"每帧重建 `Brush`"：`Brush.sweepGradient(vararg Pair, center)` 内部会 `List<Color>(n){...}` + `List<Float>(n){...}` **分配两个 List**（v1.1 已记录）⇒ 每帧重建 = 每帧 2 次分配，直接违反零分配红线（§A9 第 0 条） | `DrawScope.kt:259-271`（`withTransform` 全文）；`DrawTransform.kt:158`（`rotate`）、`:93`（`center`）；`Brush.kt:372-378`（`sweepGradient` 建两个 `List`） |
| **A32** | ✅ **`androidx.compose.ui.geometry.Size` 是 `value class`**（`value class Size internal constructor(@PublishedApi internal val packedValue: Long)`）⇒ `Size(w, h)` **不分配对象** ⇒ `drawArc(..., size = Size(rr*2f, rr*2f))` 的"每帧造 Size"**不是**分配问题（与 `Rect` **相反**，见 §A23 / §G12）。⚠️ 顺带确认 `Offset` 也是 `value class`（§A24）⇒ E30 回波画短弧可以放心用 `drawArc` + `Offset` + `Size`，**零分配** | `ui-geometry` `Size.kt:42`；`Offset.kt:61`；对照 `Rect.kt:32`（`data class Rect`） |
| **A33** | ✅ **`SweepGradient` 的角度 0 在 3 点钟方向（+x 轴）、顺时针增大**（compose KDoc 原文："The sweep begins relative to 3 o'clock and continues clockwise until it reaches the starting position again."）。而 `RadarGridRenderer` 的 `(cos a, sin a)`（`BatchThreeRenderers.kt:148-149`）在**屏幕 y 向下**的坐标系里**同样是顺时针** ⇒ 想按扫掠角旋转渐变扇时 `rotate(sweepAngleDeg)` **直接对齐，不需要补 90° 偏移**（补了反而差 90°）。⚠️ 这是"语义约定"而非签名，**实现后必须上机看一眼扇区是否与主扫线重合**（V9 ④） | `Brush.kt:388-391`（KDoc 原文）；`BatchThreeRenderers.kt:148-149`（现有角度用法） |
| **A34** | ✅ **`kotlin.math.pow`（内联到 `java.lang.Math.pow`）自 Android API 1 可用** —— `java.lang.Math` 是 Java 1.0 的 API，Android 全版本都有（与 `java.time`(API 26) / `putIfAbsent`(API 24) 那类"低 minSdk 陷阱"**不是一回事**，§15.4.3）。⚠️ 但 **E32 只在 `onEnter` 里调用**（建 `band` 表），⛔ **不要在 `draw` 里算 `pow`** —— `Math.pow` 是 native 调用，每帧 `cols` 次会成热点；需要"每帧变换"时应**预建查表**（本项目 `SpectrumRepository` / `VisualizerMath` 都按此约定） | `kotlin.math.pow` 定义（`inline fun Double.pow(x: Double): Double = Math.pow(this, x)`）；`BatchThreeRenderers.kt:437-496`（draw 零分配红线） |
| **A35** | ✅ **`IntArray` 边界表优于 `FloatArray`**（E32 的 `band` 表）：① 内存减半（4 B/元素 vs 8 B）；② **整数比较无浮点误差**（`band[i] < band[i+1]` 是精确判定，用 `Float` 得加 `EPS`）；③ 索引桶时本来就要整数（`s[band[i]]`）⇒ 省掉每帧 `toInt()`。⚠️ 表长是 **`cols + 1`（不是 `cols`）** —— 存的是**切点**（`cols + 1` 个边界），才能表达"最后一列的右边界 = `BAR_COUNT`"；存"每列一个起点"就丢失了右边界 | `SpectrumContract.BAR_COUNT = 64`；`BatchThreeRenderers.kt:417-516`（E32 零分配约束） |
| **A36** | ⛔ **E32 的"镜像"与 `BarSpectrumRenderer.mirrored` 语义不同，不可照抄**：基类 `mirrored(i, half)`（`BasicRenderers.kt:56-59`）返回 `env[if (i < half) i else 2 * half - 1 - i]`，KDoc 写「低频居中，向两侧递减」—— 但该类**全仓库零子类**（§15.7 第 2 条）⇒ **无从从调用方核实 `i` 的含义**。而 **E32 是明确的**：`x = i * cellW + gapX * 0.5f`（`:471`）⇒ `i = 0` 是**屏幕最左列**、`src = 0` 是**最低频** ⇒ **低频在左右两侧、中频在中心**，与注释**相反**（§15.7 第 13 条）。⚠️ `StaircaseMappingTest`（§八 G10）的断言必须按**实际语义**写，**不要照抄注释**；⚠️ 为了单测可见，`rebuildBands` / `colsOf` / `blockAlpha` 需声明为 **`internal`**（与 `HypnoticFunctionRenderer.nextPhase` 同一手法，`HypnoticPhaseTest` 已按此写） | `BasicRenderers.kt:56-59`；`BatchThreeRenderers.kt:463-471`；`HypnoticPhaseTest.kt`（既有 `internal` 可测范式） |

### 15.5 既有断言的同步项（单测）

> 本方案**不新增/不删除** `VisualizerTheme` 枚举成员，因此大部分既有断言**不受影响**。
> 下表列出"何时必须改"。

| 测试类 | 断言（`file:line`） | 本轮是否需改 | 触发条件 |
|---|---|---|---|
| `VisualizerThemeTest.kt` | `:51` `28`（`entries.size`） | ❌ 不改 | 仅当增删效果时 |
| 〃 | `:57/58` `28`（`selectable` 去重前后） | ❌ 不改 | 同上 |
| 〃 | `:89/90` `27`/`28`（`off`/`on` 计数） | ❌ 不改 | 同上 |
| 〃 | `:96` `28`（`ordinalLabel` 去重） | ❌ 不改 | 同上 |
| 〃 | `:103` `28`（`displayName` 去重） | ⚠️ 仅当改显示名 | §C 若改 `displayName` |
| 〃 | `:158` `32`（`VisualQuality.LOW.barCount`） | ❌ 不改 | ⛔ 本方案不动 `VisualQuality` 字段 |
| `PhotoTransitionRegistryTest.kt` | `:58-59` `76` | ✅ **C5 必改** | 新增 6 种转场 → `76 + 6 = 82` |
| 〃 | `:64` `15`（P0） | ❌ 不改 | 新转场属 P1 |
| 〃 | `:66` `43`（P1 累计） | ✅ **C5 必改** | `43 + 6 = 49` |
| 〃 | `:223` `15`（P0 参数表） | ❌ 不改 | — |
| 〃 | `:225` 时长断言 | ✅ **C5 必改** | 6 个新转场的 `baseDurationMs` 要进参数表 |
| `RendererSwapperTest` | crossfade 语义 | ❌ 不改 | 仅当裁决项 4 通过并改 `VisualizerStage.kt:178` |
| `PhotoRenderContractScanTest` | `PhotoGeometry.update` 实参个数 | ❌ 不改 | — |
| `SmallTouchTargetScanTest` / `FocusableSurfaceColorContractTest` | 触摸目标 / 颜色契约 | ❌ 不改 | 本方案不新增 UI 控件 |
| `KotlinBlockCommentBalanceTest` | 块注释成对 | ⚠️ 注意 | ⛔ 新增 KDoc 里引用块注释**必须成对写**（本项目踩过：裸写起始符会吞掉其后全部代码，报错行号落在 EOF） |

### 15.6 实施顺序（§十 的逐步验证命令）

| 步 | 提交 | 验证命令 | 通过判据 |
|---|---|---|---|
| S1 | `fx/` 6 文件 + 7 测试类（**零渲染器接入**） | `./gradlew.bat testDebugUnitTest lintDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process` | 全绿；测试计数净增 40–60；真机画面**逐像素不变** |
| S2 | 批次 A 前 4 套（E03 隧道穿越 / E05 圆形频谱环 / E07 频率山峦 / E12 频谱瀑布） | 同上 + 电视 `adb shell dumpsys gfxinfo com.nasmusic.tv` | V1–V4 通过；帧耗时 ≤ 改造前 |
| S3 | **先单独提交三条 P0**（都是纯逻辑 + 表/池/映射驱动，各自独立，互不依赖）：E24 的 §A8 第 0 条（波形真实化）、E30 的 §A9 第 0 条（真实 PPI 重建）、**E32 的 §A11 第 0 条（全柱独立频段映射）**；再提交批次 A 其余 6 套的观感改造（E13 液态网格 / E15 液态涟漪 / E17 星座 / E24 心跳 / E30 雷达 / E31 折纸 / E32 阶梯） | 同上 + `./gradlew.bat testDebugUnitTest --tests "*EcgWaveformTest" --tests "*RadarSweepTest" --tests "*StaircaseMappingTest" --no-daemon -Pkotlin.compiler.execution.strategy=in-process` | **V8 ①②③④ + V9 ①②③④ + V11 ①②③ 通过（必过）**；`EcgWaveformTest` 12 例 / `RadarSweepTest` 10–13 例 / `StaircaseMappingTest` 12–14 例全绿且负向自证都能挂；再 V5–V11 通过；`FxCoverageScanTest` 覆盖 11 套 |
| S4 | 批次 B 10 套（E11 星系螺旋 / E14 节拍烟花 / E16 数字雨 / E18 反馈残像 / E19 粒子文字 / E20 等离子流场 / E23 歌词点阵 / E25 催眠 / E34 分形 / E35 光轴） | 同上 | V12–V21 通过；覆盖 21 套 |
| S5 | 批次 C 精修 7 套（E29 轨道 / E33 齿轮 / E37 分子 / E38 怀旧 / E39 照片墙 / E40 DNA 双螺旋 / E41 世界） | 同上 + `PhotoTransitionRegistryTest` 单跑 | V22–V28 通过；覆盖 28 套 |
| S6 | （可选）`OffscreenFx` + `VisualizerStage` 接线 | 同上 | HIGH 档 bloom 可见；LOW/MEDIUM 帧耗时与 S5 一致；电视 HIGH 档 ≤ 1.25× |
| S7 | 文档同步 | `grep -n "§10\." docs/technical-overview.md \| tail` | `§` 引用无失效；计数与表格行数吻合 |

> ⛔ 每步先 `git log --oneline -5`（并发会话）；⛔ 改测试文件必须跑 `testDebugUnitTest`
> （`assembleRelease` 不编译 test 源码）；⛔ 提交前还原 `gradle.properties` 的临时 `-Xmx`。

### 15.7 待修的陈旧注释 / 顺带修正清单

> 这些是**核实过程中发现的、与本轮改造同文件**的问题。建议**随手改掉**（成本极低，
> 但留着会误导后来者）。⛔ 改动仅限注释，不涉及行为。

| # | 位置 | 现状 | 建议 |
|---|---|---|---|
| 1 | `BatchTwoRenderers.kt:492` | 称「不用 `withTransform`（其捕获 lambda 每帧分配 2 对象）」 | 改为「不用 `withTransform`——少一次 `canvas.save()/restore()`，且参数方程更直观」；**源码级依据**：`withTransform` 在 ui-graphics 1.6.1 是 `inline`（`DrawScope.kt:259`），lambda 内联、不分配对象（§15.4-A7）。⚠️ 同一说法也出现在 `docs/technical-overview.md:11196`，一并修正 |
| 2 | `BasicRenderers.kt:29` | `abstract class BarSpectrumRenderer` —— **全仓库零子类**（`grep -rn "BarSpectrumRenderer" app/src` 只有它自己的声明）⇒ 死代码 | 在 §六 A2 改造 E05 时顺手评估删除（`updateEnv` / `mirrored` 两个 `protected` 方法也随之无用）；⛔ 确认无反射/测试引用后再删 |
| 3 | `VisualizerViewModel.kt:402-403` | 「不需要额外动画代码 —— 主题一变，`RendererSwapper` 的既有 crossfade 就接管了」 | 改为「不需要额外动画代码 —— 切换是**硬切**（`VisualizerStage.kt:178` 恒传 `crossfade = false`），无需额外处理」（§15.4-A18） |
| 4 | `BasicRenderers.kt:303`、`:323` | **缩进异常**：`val layers = 5` 与 `drawPath(path, col, …)` 顶格，与上下文缩进不一致 | 纯格式问题，顺手对齐（⛔ 不改逻辑） |
| 5 | `AdvancedRenderers.kt:110` | `prev?.let { /* 交由 GC 回收，切换效果时 onExit 已置空 */ }` —— 空 lambda 里只有注释，且与 `:146-158` 的显式 `recycle` **语义矛盾** | 删除该空 `let`（保留 `:146-158` 的显式回收，那才是本项目审查后的正确做法） |
| 6 | `AdvancedRenderers.kt:13`、`BasicRenderers.kt:12`、`UltraRenderers.kt:8` | **未使用的 import**：`import androidx.compose.ui.graphics.drawscope.withTransform` —— 3 个文件都只有 import、无任何 `withTransform(` 调用（`BatchFourRenderers.kt` 已按 §10.192 删除该 import） | 删除这 3 行 import（消除编译期 "Unused import directive" 警告）。⛔ **不要删 `BatchThreeRenderers.kt:11`** —— §A9 第 0 条（E30 雷达修扫掠扇）**会重新用上** `withTransform`（§15.4-A31） |
| 7 | `UltraRenderers.kt:70-78` 与 `AdvancedRenderers.kt:146-158` | 两处 `releaseBuffers()` 实现几乎相同 | 可在阶段 1 顺手抽到 `fx/`（**可选**，非必需；抽的话注意 `WaterfallRenderer` 的缓冲尺寸与 Milkdrop 不同） |
| 8 | `AdvancedRenderers.kt:343-353` | `MatrixRainRenderer` 的 **KDoc 与实现不符**：写「**0-9** 数字列下落」与「数字字形（**10 数字** × 4 档绿）」—— 那是 v2.30.4 被撤销的描述；实现早已是 **0/1 二进制雨**（`:366` 注释、`:433` `charArrayOf('0','1')`、`:431`/`:448` 的 8 张缓存都写着 0/1） | 把 KDoc 改为「**0/1 二进制雨**（2 字符 × 4 档绿 = 8 张字形）」；⛔ 仅改注释（§13.5-D1）。**2026-09-29 已修** |
| 9 | `ParticleRenderers.kt:129-132` | ⛔ **KDoc 的 API 结论是错的**：「`ImageBitmap.readPixels()` 需 API 29、`Path.getSegment()` 需 API 24，二者在 minSdk 22 下均不可用 → **只能退回 `Bitmap.getPixel()` 全图扫描**」—— 前半句对，**"只能"是错的**：`Bitmap.getPixels(...)` 自 **API 1** 就可用（§15.4-A21）。这条错误结论**已导致 E19/E23 两套效果长期用逐像素 JNI 采样**（§G11），并让 `docs/archive/music-visualizer-dev-plan.md:874-878` 的"唯一可用"表格看起来"有依据" | 改为「`readPixels`(API 29) / `getSegment`(API 24) 不可用；采样改用 **`Bitmap.getPixels()` 逐行批读**（API 1），逐像素 `getPixel()` 是 JNI 热点」；⚠️ **`docs/archive/music-visualizer-dev-plan.md:876` 的"✅ 唯一可用"也一并更正**（归档文档，加一行「2026-09-29 更正」即可，不必重写）；⛔ 仅改注释/文档 |
| 10 | `LyricsDotMatrixRenderer.kt:641`、`:626`、`:307`、`:543` | **死代码与可省开销**：`val phaseVal = arr[o + PHASE]`（`:641`，**全文零引用**）、`textLen` 形参（`:626`，函数体内零引用）、`arr[o + SIZE]` 恒为 `1.0f`（唯一写入点 `:307`）⇒ `baseSize` 是常量、`for (p in paths) p.reset()`（`:543`）应为 `rewind()` | 删 `phaseVal` 与 `textLen`；`baseSize` 改为局部常量（**本轮不动 `STRIDE`**，风险更低）；`reset()` → `rewind()`（§B7 P4/P5） |
| 11 | `EcgWaveRenderer.kt:212-216`（`heartbeatAt` 的 KDoc）、`:41-43`（类 KDoc）、`:63`、`:154`、`:247`、`:250`、`:255` | **改动后会全部失真的注释**：`:215` 写「总跨度约 **0.50s**」、`:213` 写「分段线性插值」、`:63` 写「当前最右列的**虚拟时间（ms）**」、`:154` 写「Float 精度保护…整体回绕」、`:250` 写「总跨度 0.50s」、`:255` 写「R 主峰 +1.0，S 下探 −0.30，T 圆峰 +0.24」 | 随 §A8 第 0 条一起改：`0.50s` → **50 列 ≈ 556 ms**、`分段线性插值` → **整数列索引查表**、`虚拟时间(ms)` → **已写列数**、删掉 `:154-159` 的 `REBASE_AT_MS` 段与其注释、`:255` 的幅值改为 **Q −0.11 / R +1.00 / S −0.28 / T +0.27**。⛔ 仅改注释与已废弃代码 |
| 12 | `docs/visualizer-texture-upgrade-plan.md` §A8（**原第 5 条「扫掠头增强」**） | ⛔ **方案文档与源码既有裁决冲突**：原第 5 条要求"头部光点改为 `shadeBrush` 径向光斑、`beat` 时半径 ×1.4"，而 `EcgWaveRenderer.kt:45` 的 KDoc 明确写着「**峰顶无帽**：不绘制任何节拍点/扫描头圆点」——本效果**从设计上就没有扫描头**，无从"增强" | **本版已作废该条**（§A8 已改为「⛔ 不新增扫描头 / 节拍圆点」）。教训：**写方案前先读被改类的 KDoc** —— 它记录着上一轮的设计裁决，光看 `draw()` 的代码看不出"故意不画" |
| 13 | `BatchThreeRenderers.kt:463` | 注释写「**镜像展开：低频居中**（与柱状频谱基类一致的空间分布）」—— 与实现**相反**：`x = i * cellW + gap * 0.5f`（`:471`）⇒ `i = 0` 是**屏幕最左列**、`src = 0`（`:466`）是**最低频** ⇒ 实际是「**中频在中心、低频在左右两侧**」。⚠️ 同源说法也在基类 `BasicRenderers.kt:58`（`mirrored` 的 KDoc「镜像展开：低频居中，向两侧递减」），但基类**全仓库零子类**（本表第 2 条）⇒ 无从从调用方核实 `i` 的语义 | 改为「**镜像展开：中频居中、低频在两侧**（`i = 0` 是最左列 ⇒ 最低频）」；若 §十三 裁决项 7 选 A（全宽展开），则整段镜像逻辑删除、注释一并删掉。⛔ 仅改注释（行为由 §A11 第 0 条决定，§15.4-A36 已记该差异） |
