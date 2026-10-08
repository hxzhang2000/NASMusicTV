# docs/archive — 已完成文档归档

> **归档日期**：2026-09-20（三轮）
> **归档依据**：见 `AGENTS.md` → Conventions → *Doc lifecycle* 与
> `docs/technical-overview.md` §10.166–§10.167。
>
> 本目录存放**已完成使命**的文档：功能已发布、审查已闭环、方案已实施、方案已废弃。
> 它们**不再维护**，保留在此仅作历史追溯与决策依据。

## ⛔ 三条判定原则（都不是「是否被引用」）

**原则 1 — 文档自身的「状态」标记会过期，以 `CHANGELOG.md` / `technical-overview.md` / 所有者结论为准。**
标记只说明「文档没回填」，不说明「功能没做」。第三轮的 10 篇**全部**自称
「待评审 / 可开发状态 / 方案提案 / Phase 7 未开始」，实际都已落地 —— 这是本仓库最密集的一次反例。

**原则 2 — 「被源码 / `AGENTS.md` 引用」不是归档障碍。**
移动时会在同一次操作里改写**全部**引用（含 `app/src/**` 的 KDoc、`AGENTS.md`、
`CHANGELOG.md`、跨文档链接），路径依然有效，**不会断链**。把「被引用」当否决
会白留一堆已完成的文档 —— 这正是第二轮归档（6 个文档、57 处引用）纠正的错误。

**原则 3 — 方案「永久无法实现 / 主动放弃」同样可归档。**
归档不等于「成功」：目标已失效的方案留着只会持续误导（看着像「还能做」）。
判据是**所有者已明确结论**，例如：
- 阿里云盘 —— 官方已关闭第三方应用访问，**永久不可实现**；
- `network-music-upgrade-plan`（对接 Go Music API）—— 无公开 API 端点、必须自行部署，**主动放弃**。

> 反例边界：只是「暂时没排期」的**在办**方案不适用本条，仍留 `docs/` 根。

---

## 第一轮（41 个）

### 代码审查 / 快照（10）

| 文档 | 判定依据 |
|---|---|
| `code-review-2026-06-26.md` | 审查报告；问题已处理（v2.4.2 修复 9 项，其余已评估） |
| `code-review-2026-06-30.md` | 审查报告；结论已并入后续优化方案 |
| `code-review-2026-09-03.md` + `.docx` | 审查报告；问题已修复并记录于 `technical-overview.md` §10.14x |
| `code-review-2026-09-07.md` | 离线下载功能审查报告；结论已落地 |
| `code-review-2026-09-16.md` + `.html` | 审查报告；问题已修复 |
| `code-review-deferred-analysis-2026-09-04.md` + `.docx` | 复审暂缓项分析；三项均已处理 |
| `codebase-architecture.html` | 架构快照，一次性产物 |

### 已落地的功能方案（22）

| 文档 | 判定依据（CHANGELOG / 源码） |
|---|---|
| `pinyin-search-plan.md` | 拼音搜索已实现（v2.25.7） |
| `subsonic-support-plan.md` | `SubsonicAdapter.kt` / `SubsonicRestClient.kt` 已存在 |
| `daoliyu-feiniu-backend-plan.md` | `DaoliyuAdapter.kt` / `FeiniuAdapter.kt` / `FeiniuUrl.kt` 已存在 |
| `radio-and-jamendo-source-plan.md` | 电台 + Jamendo 音源已在 CHANGELOG 记录 |
| `local-music-feature-plan.md` | 本地音乐已在 CHANGELOG 记录 |
| `optimization-plan-lyrics-cover.md` | 逐字歌词 / 封面轮播已在 CHANGELOG 记录 |
| `library-ui-optimization-plan.md` | 专辑 / 艺术家页面 UI 已在 CHANGELOG 记录 |
| `歌曲离线下载与本地存储管理开发方案.md` + `.docx` | 离线下载已在 CHANGELOG 记录 |
| `K歌开发方案.md` | `KaraokePlaybackScreen.kt` / `KaraokeLyricsView.kt` 已存在 |
| `network-music-tab-plan.md` | 网络音乐顶级 Tab 已在 CHANGELOG 记录 |
| `network-music-feature-proposal.md` | 网络搜索歌曲已在 CHANGELOG 记录 |
| `music-api-solution.md` | 免费音乐 API → Meting 层已落地 |
| `multi-bitrate-playback-download-plan.md` | 网络音乐多码率已在 CHANGELOG 记录 |
| `network-music-failover-plan.md` | 播放失败多级降级已在 CHANGELOG 记录 ⚠️ 文档原写「尚未开发」= 过期标记 |
| `backend-api-version-display-plan.md` | 关于页「API 版本号」展示区已在 CHANGELOG 记录 |
| `remote-control-design.md` | 手机点歌 / 队列编辑已在 CHANGELOG 记录 ⚠️ 文档原写「待开发」= 过期标记 |
| `vocal-removal-approach-c-ai.md` | AI 人声分离（Demucs）已落地 |
| `vocal-separation-pitch-speed-plan.md` | K 歌页面变速控制已在 CHANGELOG 记录 |
| `nas-music-tv-v2.6-feature-proposal-from-mineradio.md` | v2.6.0 已于 2026-07-03 发布 |
| `codebase-optimization-plan.md` | 文档自述 v1.2 已实施 |
| `github-actions-android-ci-cd.md` | CI/CD 已落地（见 `AGENTS.md`） |

### 其他一次性产物（4）

| 文档 | 判定依据 |
|---|---|
| `regression-test.md` | 回归测试清单；被 `CHANGELOG.md` / `technical-overview.md` 引作历史依据 |
| `v2.7.0-feature-test.md` | v2.7.0 功能测试计划，该版本早已发布 |
| `roadmap.md` | 2026-07-01 待办清单，11 项完成 10 项 |
| `百度网盘音乐播放开发方案-评审意见.md` | 评审意见 15 项已全部回填至方案 v2 |

### 对外文章 → `docs/articles/`（5）

`zhihu-introduction.md`、`zhihu-introduction-v2.16.md`、`zhihu-article-v2.18-to-v2.22.md`、
`zhihu-article-2026-09-v2.22-to-v2.32.md` + `.html`

---

## 第二轮（6 个）—— 纠正「被引用即否决」的过度保守

这 6 个都**被源码 KDoc 或 `AGENTS.md` 引用**，第一轮被误判为「不可归档」。
移动时同步改写了 **57 处引用 / 41 个文件**（其中 30+ 是源码），死链 0 新增。

| 文档 | 判定依据 | 被谁引用（已改写） |
|---|---|---|
| `phone-portrait-ui-plan.md` | 竖屏适配已实施（v2.36.0） | `strings.xml`、`UiModeTest.kt` 等 |
| `playlist-import-feature-plan.md` | 歌单导入已落地（§10.161 二维码 + URL 远程上传） | `backend/playlist/*.kt` 等 20+ 处 |
| `feiniu-backend-improvement-plan.md` | 飞牛后端已落地 | `FeiniuAdapter.kt`、`FeiniuUrl.kt`、`AGENTS.md` |
| `mv-karaoke-feature-proposal.md` | MV / K 歌已落地 | `backend/network/mv/*.kt`、`MvPlaybackScreen.kt` 等 |
| `phone-support-plan.md` | 电视 + 手机双端支持已落地 | `AGENTS.md` |
| `催眠频谱效果开发方案.md` | 催眠可视化已落地（CHANGELOG「新增催眠 E25」） | `FormulaLayout.kt` |

---

## 第三轮（13 个）—— 所有者确认：状态标记全部过期

这 13 个（12 篇 md + 1 个 docx 对照件）中的前 11 个是我第二轮**判定为「未落地」而保留**的，
所有者随后逐条确认**全部已完成或已放弃**；后 2 个是我第二轮判为「活文档」而保留的，
所有者确认**开发工作早已完成**。判定依据从「CHANGELOG 交叉验证」升级为
**所有者结论**（CHANGELOG 只覆盖一部分，方案变更类确实查不到）。

| 文档 | 文档自称状态 | 实际结论 | 所有者依据 |
|---|---|---|---|
| `aliyundrive-support-plan.md` | 「规划中，遭遇硬阻塞，需决策」 | ❌ **永久无法实现** | 阿里云盘已确认不支持其他应用访问 |
| `百度网盘音乐播放开发方案.md` | 「开发进行中（Phase 7 未开始）」 | ✅ 已完成 | 已完成 |
| `android-auto-plan.md` | 「阶段 1 已实施；阶段 2/3/4 未实施」 | ✅ 已开发完成 | 已完成，无实机无法测试 |
| `music-visualizer-dev-plan.md`（+ `.docx`） | 「v6.0（**可开发状态**）」 | ✅ 已开发完成 | 效果库已开发完成 |
| `unified-source-architecture.md` | （无状态标记） | ✅ 已开发完成 | 已开发完成 |
| `audition-lyrics-solution.md` | （无状态标记） | ✅ 已完成（**方案变更**） | 后续方案已完成 |
| `metadata-search-service-solution.md` | （无状态标记） | ✅ 已完成（**方案变更**） | 后续方案已完成 |
| `network-music-upgrade-plan.md` | 「方案提案」 | ❌ **主动放弃** | Go Music API 无公开端点，须自部署 |
| `feature-dev-plan-2026-09.md` | 「待所有者评审」 | ✅ 已开发完成 | 已开发完成 |
| `phone-media-display-plan.md` | 「方案设计（**待评审**）」 | ✅ 已开发完成 | 已开发完成 |
| `codebase-refactoring-plan-2026-09.md` | 「v1.6，含**待所有者决策项**」 | ✅ 已重构完成 | 早已重构完成 |
| `vocal-removal-approach-b-dsp.md` | 「已实施（v3.2 已按实测调优）」 | ✅ 已开发完成 | 已开发完成 |

> ⚠️ **`codebase-refactoring-plan-2026-09.md` 归档时仍含 2 项未闭环的所有者决策项**，
> 归档表示「不再作为在办方案维护」，**不等于这 2 项已决定不做**：
> - **R-5**：PlayerManager 四阶段拆分（PlayerCore / PlayerQueue / PlayerMediaSession /
>   PlayerAudioFocus）—— v1.6 已**降级为待所有者决策项**，「未经所有者重新确认不得启动」；
>   现有定案是「HQ 编排保留 PlayerManager **防接口爆炸**」。
> - **F-8**：下载无保活（P2）——「待所有者决策后再定」。
>
> 若日后要启动任一项，请从归档件恢复评估（播放/队列/媒体会话/音频焦点全路径手测成本）。

> ⚠️ **`vocal-removal-approach-b-dsp.md` 的特殊性**：§10.152 曾把它列为「算法复原依据」
> （`VocalRemovalProcessor.kt` 删除时「先把算法归档再删」）。归档**不削弱该角色** ——
> 它仍是完整算法文档（Mid/Side 流程图、最终参数、`queueInput` 伪代码），
> 只是位置从 `docs/` 根移到 `docs/archive/`，引用已同步改写，复原路径不变。

> ⭐ **方法论修正（本轮最大收获）**：CHANGELOG 交叉验证对「已上线功能」有效，但对
> **方案变更**（`audition-lyrics` / `metadata-search`：功能以另一种形态落地，原关键词自然零命中）
> 与**主动放弃**（`network-music-upgrade-plan`）**必然漏判**。
> 这两类只能问所有者 —— **不要因为「CHANGELOG 零命中」就断言「未落地」**。
> 第二轮判「留」的 10 个**全被推翻**，正是这条漏判造成的。

同步改写 **68 处引用 / 29 个文件**（含 `ApiProbe.kt`、`BaiduNetdiskConfig.kt`、
`PlayStatsAggregator.kt`、`PlayStatsRepository.kt`、`RadioSongScorer.kt`、
`MediaLibraryTree.kt`、`SleepTimerController.kt`、`BeatDetectorTest.kt`、
`ViewModelEvents.kt`、`HqSeparationOrchestrator.kt`、`SpectralMaskProcessor.kt`、
`AGENTS.md`、`CHANGELOG.md`、`technical-overview.md`）。死链 **0 新增**。

> ⚠️ **工具盲区（本轮实测）**：改写只覆盖带 `docs/` 前缀的引用，
> **裸文件名**（如 `vocal-removal-approach-b-dsp.md`）**不会被匹配**，
> 必须 `grep <basename>` 手工清扫（本轮手工修了 3 处：`AGENTS.md`、本 README、
> `technical-overview.md` 的活文档清单，以及 `mv-karaoke-feature-proposal.md` 的关联链接）。

---

## 第四轮（2026-10-02，1 个）—— 所有者指示：不再维护

| 文档 | 判定依据 |
|---|---|
| `starry-sky-visualizer-plan.md` | ✅ **已落地**：E42「星空星轨」已实现并上机（创维 Android 5.1.1 / API 22），门禁 `testDebugUnitTest` **1582 例 / 0 失败**、`lintDebug` **0 Error**；记录见 `technical-overview.md` §10.206。⚠️ **所有者 2026-10-02 明确指示「归档吧，不用再更新了」** —— 该文档在实现期被高频回写（v1.0→v1.5 共 5 版，含 16 行偏差记录），继续维护的成本已超过其作为「决策依据」的价值，故停止更新。实现细节以**源码 + §10.206** 为准，本文档仅作历史追溯 |

**归档时仍未关闭的决策项**（⚠️ 归档不等于已解决，特此留痕）：

1. ⛔ **真机验收未完成**。§十 的 V4–V12 只做到 V4（v1.4 实测 **29.7 fps**）；**v1.5 的「spray 弧场」层从未上机验证** —— 其「帧率与 v1.4 持平（约 29.7 fps）」是**预期而非实测**。v5 的分布密度、亮弧数量观感均未经真机确认。
2. ⛔ **风险 R1（API 22 填充率）仅被「规避」而非「量化」**。v1.4 删掉逐帧全屏缓冲后暴露面大降，但 `dumpsys gfxinfo framestats` 的正式实测始终没做。
3. ⛔ **两处「~1.3 倍台阶 / hash 精度」隐患未在真机判读**：
   - v1.4 的加色叠加在亮线段起点约 1.3× 的台阶（`SEG_GAMMA` / `RING_ALPHA` 一行可调）；
   - v1.5 的 `hash01` 只有约 3e3 个可区分 Float 值，1920 个 `(band,k,n)` 三元组中会有碰撞。fix-3 判断无害（四路参数流相互独立），**但若真机读作「规律重复」，正解是换 32 位整数 hash，而非调参**。
4. ✅ `docs/starry-sky-preview.html`（浏览器原型）**已删除**（2026-10-02，所有者决定）。它是累积缓冲机制的历史原型，Kotlin 侧 v1.4 起已改为「底环 + 亮线段」，**从来不是契约来源**；其驱动过的设计决策（`t^0.72` 半径映射、流星必须用圆弧、枯树替代人物剪影、地平线占底部 1/4、虚化平台被否决等）已完整记入 `docs/archive/starry-sky-visualizer-plan.md` 与 `technical-overview.md` §10.207，原型本体不再保留。

> 引用改写：2 处（均在 `technical-overview.md` §10.206），路径 `docs/starry-sky-visualizer-plan.md` → `docs/archive/starry-sky-visualizer-plan.md`。死链 **0 新增**（裸文件名 grep 已扫，无遗漏）。
>
> ⚠️ 该文件归档时**尚未纳入 git**（全程 untracked），故用普通文件移动而非 `git mv`——**无历史可保留**，首次提交即以归档路径入库。

---

## 第五轮（2026-10-08，1 个）—— 所有者裁决：不再推进

| 文档 | 判定依据 |
|---|---|
| `visualizer-texture-upgrade-plan.md` | ⚠️ **本篇不是「已落地」，而是所有者判断「不再推进」**（原话「这个文档归档吧，我判断是没啥可做的了」，随后在「整篇归档 / 拆分遗留」两案中选定**整篇归档**）。**已落地的部分**：批次 A/B（S0–S4）质感改造全部落码并过门禁、E29 太阳系 §C1 六条已落地（`13767a1` / §10.219）、低画质门控方案 C 与列条合并已真机复验。**未落地的部分见下面四条遗留** ⇒ 按本仓规矩，「只是暂时没排期的在办方案」**不满足**归档判据，本轮是**所有者裁决覆盖该判据的例外**，务必不要读成「做完了」。移动方式 = `git mv`（历史保留、内容一字未删）；恢复推进的入口是文内 **§12.3 任务清单**与 **§十一 上机验收清单**；实现现状一律以**源码 + `docs/technical-overview.md` §10.203–§10.219** 为准，⛔ 不要拿本文的规格文本当契约 |

**归档时仍未关闭的遗留（⛔ 归档 ≠ 已解决，四条逐条留痕）**：

1. ⛔ **批次 C 的 6 套质感改造至今 0 套开工**：T5.2 E33 齿轮 / T5.3 E37 分子 / T5.4 E38 怀旧 /
   T5.5 E39 照片墙 / T5.6 E40 DNA / T5.7 E41 世界。⛔ **更要紧的是基类迁移面也是 0 套** ——
   欠账的 **E29 / E33 / E37 / E40** 四套至今仍直接实现 `VisualizerRenderer`（不是 `RendererFx`），
   各自持有 `lastMs` 自算 `dt`、自己的 `rng` / 内联 LCG、自己的后处理释放。
   ⚠️ 这四套**不是**裁决排除（有意排除的是 E38 / E39 / E41 三套），别混进豁免理由。
2. ⛔ **真机观感验收几乎整张未跑：27 条判据未勾**。§11.1 的 7 条通用判据只有 `U1`/`U4` 过
   （余 `U2`/`U3`/`U5`/`U6`/`U7`）；§11.2 的 22 条逐效果判据**全部未勾** —— 唯一曾勾过的 `V1`
   属于**已删除的 E03** ⇒ 现役 21 套里 13 套的质感改动 + E29 的增强**从未逐套上机看过**
   （唯一例外 E16 数字雨，因排查崩溃被反复上机）。
   ⚠️ 附带风险：E05 / E13 / E15 / E17 **没有专用单测**（只在 `covered` 名单里）⇒
   观感改动被后来的提交抹掉时**不会有任何测试失败**。
   恢复验收的方法与基线（改造前 = `ca23876`，逐像素不变的最后一次提交；创维 API 22）在 §11.3。
3. ⛔ **§15.7 待修 7 条**（#1 / #3 / #20 / #21 / #23 / #24 只需改注释或已复核仍在源码）；
   **#22 需所有者拍板** —— `ParticlePool.updateAttract()` **零调用方**：删函数 vs 保留复用能力。
4. ⛔ **阶段 6 离屏层接线未做**：T6.1 `drawBloom` **代码已写完但零消费方**（刻意保持未勾）、
   T6.2 / T6.3 屏幕级 bloom 的接线与实测未做；另**裁决项 4 的 600ms 交叉淡入至今未落码**
   （`VisualizerStage.kt:205` 的第 3 实参仍硬编码 `false`）。

> 引用改写 **13 处 / 6 个文件**（`technical-overview.md` ×2、`visualizer-effects-list.md` ×2、
> `archive/music-visualizer-dev-plan.md` ×1、`archive/seaside-visualizer-plan.md` ×3、
> `archive/starry-sky-visualizer-plan.md` ×4、`archive/verification/scripts/verify_linerefs.py` ×1）
> + 本文自引 2 处；其中 **4 处是裸文件名**，靠 `grep <basename>` 手工补的 `docs/` 前缀（第四次印证本 README 的「工具盲区」条）。死链 **0 新增**。
>
> ⚠️ **行号锚点已失效**（这是本篇独有的，其余归档件没有）：`seaside-visualizer-plan.md:9,147` 与
> `starry-sky-visualizer-plan.md:11,160,211,216` 引用该方案的 6 处 `:NNNN` 锚点，因该文档自身的整节删除
> 而**正负双向漂移**，无法用一个偏移量换算。正确行号已逐条列在该文档的 **v1.50 行 ⑥**。
> ⇒ ⛔ **今后引用已归档文档一律用章节号（§ / T 编号），不要用行号**；两份引用方文档同为归档历史件，按规矩不回写。

---

## 未归档（仍留 `docs/` 根，2 个）—— 全部是活文档

> 计数口径：⛔ **本节原先写「`docs/` 根只剩 2 篇 Markdown」——那已经过期**（`visualizer-effects-list.md`
> 与 `visualizer-texture-upgrade-plan.md` 是之后新增的）。2026-10-07 实测 `docs/` 根 = **4 篇 Markdown**；
> **2026-10-08 第五轮把 `visualizer-texture-upgrade-plan.md` 归档后 = 3 篇**：
> **活文档 2 篇**（下表）+ **在册清单 1 篇**（`visualizer-effects-list.md` —— 它是 `VisualizerTheme`
> 的现役效果名册，随效果增删持续维护 ⇒ 属活文档性质，但不在下表的「约定类活文档」里）。
> ⇒ ⛔ 原文「**在办方案 2 篇**（`visualizer-texture-upgrade-plan.md` 仍在办，故**不满足**归档判据）」
> 那句**已作废**：该方案已于 2026-10-08 经所有者裁决整篇归档，遗留四项见**第五轮**。
> `docs/snapshot/`（16 张截图）不是文档，不计入。
> `docs/*.miora` / `docs/*_assets/`（gitignored 的 WorkBuddy 设计画布素材）已于
> 2026-09-20 经所有者确认删除（对应功能早已上线，素材无引用）。

| 文档 | 为什么是「活」的 |
|---|---|
| `technical-overview.md` | 全项目主索引，§10.N 持续追加（当前最大 §10.167） |
| `conventions-adaptive-ui.md` | 自适应 UI 约定，随新组件持续维护 |

> 其余文档一律视为「已完成 / 已放弃」→ `docs/archive/`。
> 若日后需要恢复某个已归档方案（如 R-5 四阶段拆分、F-8 下载保活），
> 从 `docs/archive/` 取出评估即可 —— 归档是**纯移动**，内容一字未删。

---

## 附：验证证据迁移（2026-09-20，来自 gitignored 的 `logs_temp/`）

`logs_temp/` 是项目约定的临时目录（`.gitignore:87`），但其中有一批文件**被已入库文档引用**
（`docs/technical-overview.md` §10.14x–§10.16x、`CHANGELOG.md`、`AGENTS.md` 等）。
一旦清理该目录，这些引用会**悬空**，其中全量审查报告的「未完成项」更是**唯一记录**。
故把其中的**证据类文件**迁入版本控制（`logs_temp/` 保留为空目录，约定不变）。

### 迁到 `docs/archive/`（与既有 `code-review-*.md` 同级）

| 文件 | 被谁引用 |
|---|---|
| `code-review-full-report-2026-09-13.md` | §10.147 / §10.148 / §10.152–§10.156 的「来源」 |
| `code-review-karaoke-onnx-2026-09-14.md` | §10.146 / §10.158 的「来源」 |

### 迁到 `docs/archive/verification/`

| 内容 | 被谁引用 |
|---|---|
| `verify4.log` | `AGENTS.md` / §10.165（lint 内部异常栈为既有现象） |
| `render_auto_icons.py`、`render_car_icon.py` | `CHANGELOG.md`（Android Auto 图标形状推导） |
| `parse_lint.py` | 解析 lint HTML 报告（可复用工具） |
| `_depsrc/`（media3-session 1.2.1 源码，51 文件） | §10.165 的证据链 |
| `verify_resampler/`、`verify_feiniu_url/`、`verify_auth_headers/`、`verify_ort/` | `CHANGELOG.md` / §10.146 / §10.155 的验证 harness（源码与说明，不含编译产物） |
| `scripts/seaside_wave_harness.js` | **E43 海边原型的帧驱动 harness**（2026-10-03 归档）。`vm` 沙箱桩掉 DOM/canvas/`Path2D`，打桩 `requestAnimationFrame` 驱动**真实页面代码**，注入探针读浪队列闭包状态。产出交接跳变 / 退水深长 / 浪数 / 每帧位移上限等全部量化阈值，`docs/archive/seaside-visualizer-plan.md` §7 把它列为 Kotlin 端单测的断言口径来源 |
| `scripts/seaside_hole_continuity_check.js` | 泡沫破洞场连续性（可见洞逐帧位移 0.74px = 0.31× 洞半径，8.4× 优于旧实现） |
| `scripts/seaside_caustic_geom_check.py` | 焦散胞壁网 vs 旧孤立短划的 1:1 栅格化对比（PIL） |
| `scripts/seaside_visual_driver.mjs` | 真浏览器 CDP 驱动（`snap`/`series`/`waterline`/`cost`），rAF 打桩、确定性推进；截图落 `output/seaside_frames/` |
| `scripts/html_syntax_check.js` | 从 HTML 里抽出内联 `<script>` 做 `new Function` 语法检查（不落盘临时文件） |

**未迁入**（可重新生成的构建产物与一次性日志，已移出 `logs_temp/`）：
`*.class`、`*.kotlin_module`、`aapt2-out/*.zip`、`iconprobe/`（37 M）、`adb_verify/`（22 M）、
`r8probe/`（9 M）、`_depsrc_common/`（1.8 M）及全部构建/测试日志。

**同步改写引用 30 处 / 5 个文件**（`AGENTS.md`、`CHANGELOG.md`、`technical-overview.md`、
`android-auto-plan.md`、`feiniu-backend-improvement-plan.md`），
并修正 7 处「该文件不入库 / 在 gitignore 临时目录」的过时表述。
另有 2 处测试 KDoc 原误指向 `logs_temp/` 的脚本副本（正本在项目根），一并改正。

---

## 汇总

| 项 | 第一轮 | 第二轮 | 第三轮 | 合计 |
|---|---|---|---|---|
| 移入 `docs/archive/` | 36 | 6 | 13 | **56** |
| 移入 `docs/articles/` | 5 | 0 | 0 | **5** |
| 同步改写引用 | 52 处 / 20 文件 | 57 处 / 41 文件 | 68 处 / 29 文件 | **179 处**（涉及 65+ 文件，各轮有重叠） |
| `docs/` 根目录 | 62 → 21 | 21 → 15 | 15 → **2** | **−60**（仅剩 2 篇活文档） |
| `seaside-visualizer-plan.md` | E43 海边实现方案 v1.3。**已 shipped**（`SeasideRenderer.kt` / `SeasideWaves.kt` / `SeasideOpBudget.kt`，v2.38.2；视觉由所有者真机逐项确认后定稿）。**归档时仍带未决项**：§12.5「仍未解决 / 未验证」的 **U1**（单列尖峰 10.77 px，成因未定位）、**U8**（G13 与 §4.7① 在 4K 上冲突，**待裁定**）、**U9**（两处规格歧义，已按「不猜」记录待确认），以及 §4.7 表「逐帧水线最大位移 10.77 px ⛔ 未解决」。接替记录：`docs/archive/seaside-preview.html` 是只读规格基准；全部已验证结论已落 `technical-overview.md` §10.209–§10.211 |
| `photo-spectrum-effect-plan.md` | 照片墙转场与频谱方案。**已 shipped**（`PHOTO_WALL` = E39，在册于 `VisualizerTheme`）。**归档时待确认项为 0**（文档 §统计自述「待确认 0 项」）；但 §实现期记录着两条**用户上机报告的缺陷**：「竖版图片总是无法占满屏幕」「很多时候进入动画效果还未完成…」，**是否已修复未在文档中回填** —— 需要时回查源码或重上机。⛔ 已知遗留：转场打断（手动切歌/切图时从当前 blend 反向插值）明确留给阶段 10 |
| `seaside-preview.html` | E43 海边的**浏览器原型**（2663 行单文件）。**所有者 2026-10-07 裁决：「归档吧，不用了」** ⇒ 它不再是视觉基准。⭐ **这不是「方案未完成」，而是「职责转移」**：原型已完成使命（v2.38.2 真机逐项对齐后 `SeasideRenderer.kt` 定稿），此后**真机实现是唯一权威**。⚠️ 后果已显形：`SeasideRenderer.kt` 与 `SeasideOpBudget.kt` 里有 **100+ 处 `seaside-preview.html:NNNN` 形式的行号引用**，它们此后只能指向**一份冻结的快照**——⛔ **不要再拿原型去「对齐」真机**，行号会骗人。**归档时仍带 3 项未决**：① `verification/scripts/seaside_doc_consistency_check.py` 的 `MUST` 断言 `lerp(spawnFar, shoreYs[i], tAdv)` 在原型里**已不存在**（实测 `常量不一致数: 1`）——脚本比原型旧，⛔ 别信它的绿灯；② `seaside_wave_harness.js` 的 5b/5c 段判定已失效（仍在检查被 `wiS` 取代的 `wi`，见 `seaside-visualizer-plan.md` U4）；③ 5 个脚本仍可跑（路径已同步），但它们验的是**原型**，不再覆盖真机 |
| `permission-and-signing-plan.md` | 权限与签名统一方案 v1.5。**已 shipped**（S0–S8 全批次实施，v2.38.3；`ReleaseSigningGateTest` / `LocalMusicGateTest` 三道门禁 + CI fail-fast 全部落地）。**归档时待裁决项为 0**（§状态自述「可开工，无待裁决项」，`technical-overview.md` §10.214 已回填实施结果）。⚠️ **它的引用密度是全 `docs/` 最高的**：`AGENTS.md`、`.github/workflows/build.yml`、`app/build.gradle.kts`、3 个门禁测试的**断言失败消息**都在引它 ⇒ 全部已改写为 `docs/archive/` 路径（12 处里占 6 处） |
| `e41-tv-blackscreen-fix-plan.md` | E41「世界」电视端黑屏的**诊断与裁决记录**。**❌ 修复已永久放弃**（所有者 2026-10-07 裁决：方案 A / B 均否决，「改回原来很好效果的代码……就是黑屏吧」）。定位到的真根因是**宿主**而非渲染层：该机 WebView 被 Compose `AndroidView` 承载时**只画首帧、永不更新**（2D canvas 同样不上屏），故任何页面内改动都救不了。**最终落地**：`WORLD` 由 `Tier.ADV` 提到 `Tier.ULTRA`，仅最高画质档提供（v2.38.4）。**归档时仍带未决项**：§八 的**真机验收 U1/U2** 待所有者执行——① 电视上确认 MEDIUM/LOW 档不再出现 E41；② 手机上确认观感与回退前一致。全部已验证结论已落 `technical-overview.md` §10.215 |
| `solar-system-upgrade-plan.md` | E29 太阳系程序化质感增强**功能清单 + 当时的编译错误清单**。**已 shipped**（2026-10-08：功能 ①–⑥ 全部落地，22 处错误清零，`OrbitalProceduralEnhanceTest` 11 例门禁；版本号按所有者指示**未提升**）。⚠️ **归档时必须提醒的两点**：① 本文第二节那张错误表**已过期**，头部与二级标题均已标注「历史快照」，⛔ 不要再照它排查；② 实现**偏离原规格 6 处**（预烘 16 档角度→连续角度、原色夜色→压暗、sweepGradient→径向渐变、1.04→1.18、smoothstep 边缘柔化→半径调制、楔形缝→整圈同心内圈），理由逐条记在 `technical-overview.md` §10.219 的表里 —— 原规格里 ①②④⑤ 四项**照写就会画错**。**归档时仍带未决项**：§四 的每档新增提交（LOW +2 / MED +18 / HIGH +38）是**静态估算**，真机帧率待所有者上机（§九 R18）|

> **2026-10-05 追加归档 2 份**（所有者裁决）：`seaside-visualizer-plan.md`、`photo-spectrum-effect-plan.md` → `docs/archive/`。同步改写引用 **5 处 / 4 文件**（`SeasideWaves.kt`、`PhotoWallSettingsSection.kt`、`docs/archive/README.md`、`docs/archive/starry-sky-visualizer-plan.md`）。⚠️ 其中 **1 处是真正会跑坏的脚本**：`verification/scripts/seaside_doc_consistency_check.py` 的硬编码绝对路径用的是**反斜杠**，因此 `docs/` 正斜杠扫描扫不到——归档时必须手动扫**裸文件名**。⇒ 此论在本轮已被实证两次。

> **2026-10-07 追加归档 1 份**：`e41-tv-blackscreen-fix-plan.md` → `docs/archive/`。
> 同步改写引用 **2 处 / 2 文件**（`AppSettings.kt` 的 `WORLD` KDoc、`technical-overview.md` §10.215）。

> **2026-10-08 追加归档 1 份（第五轮）**：`visualizer-texture-upgrade-plan.md` → `docs/archive/`
> （可视化质感升级方案，批次 A/B 已落码、批次 C 与真机验收未跑 ⇒ **所有者裁决「不再推进」而归档，
> 非「已完成」**，遗留四条见上方**第五轮**）。移动方式 = `git mv`（本篇**已在版本库**，与同日
> `solar-system-upgrade-plan.md` 的 untracked 情形不同）；引用改写 **13 处 / 6 文件 + 自引 2 处**；
> `docs/` 根 4 → **3 篇 Markdown**。⚠️ 唯一副作用：另有 2 份归档件对它的 **6 处 `:NNNN` 行号锚点已失效**
> （该文档历史上做过整节删除），换算表在其 **v1.50 行 ⑥**。

> **2026-10-08 追加归档 1 份**：`solar-system-upgrade-plan.md` → `docs/archive/`（E29 太阳系程序化增强，已 shipped）。
> 该文档**归档前从未入库**（工作区新增），故用普通 `mv` 而非 `git mv` —— **无历史可保留**，
> 引用改写 **1 处 / 1 文件**（`OrbitalProceduralEnhanceTest.kt` 的 KDoc 指向 `docs/archive/` 路径）。
> ⚠️ 本篇是**同一次会话内先建后归档**，故上表「`docs/` 根目录」一列未变（净增删为 0），
> 只更新了「移入」与「同步改写引用」的合计。

> **2026-10-07 追加归档 2 份**（所有者裁决）：`seaside-preview.html`、`permission-and-signing-plan.md` → `docs/archive/`。
> 同步改写引用 **12 处 / 7 文件**（`technical-overview.md`、`docs/archive/README.md`、`SeasideWaves.kt`、`SeasideWavesTest.kt`、`app/build.gradle.kts`、`ReleaseSigningGateTest.kt`、`LocalMusicGateTest.kt`）+ **4 处脚本路径**。
> ⚠️ **`seaside-preview.html` 归档会跑坏 4 个脚本，且 3 个是上一轮同一个坑的第三次复现**：
> `seaside_wave_harness.js` / `seaside_hole_continuity_check.js` 用 `__dirname` 上溯**三级**取页面
> （三级 ⇒ `docs/`，须改**两级**）；`seaside_visual_driver.mjs` 用 `resolve(ROOT,'docs',…)`；
> `seaside_doc_consistency_check.py` 是**硬编码绝对路径**⇒ **正斜杠 `docs/` 扫描永远扫不到它**，
> 本轮已改成按 `__file__` 解析（顺带修掉「换机即失效」这个旧债）。
> ⇒ **`docs/` 前缀扫描 + 裸文件名扫描都不够，必须单独问「有没有脚本按路径找这份文件」。**
> ⚠️ `SeasideRenderer.kt` / `SeasideOpBudget.kt` 里的 `seaside-preview.html:NNNN` **行号引用本轮未改**
> —— 那两个文件当时正被蟹迹 lane 占用；它们是**裸文件名 + 行号**（非路径），归档不改内容 ⇒ 引用**仍然为真**，
> 但请按上面那行的告诫读：**原型已冻结，不再是真机基准**。

> 上表只计「三轮归档」。另有 **2 份审查报告**自 `logs_temp/` 迁入 `docs/archive/`
> （见上文「附：验证证据迁移」），故 `docs/archive/` 现共 **60 篇文档 + 本索引**。

## 回滚

归档为**纯移动**（`git mv`，历史保留），未删除任何内容。整体回滚：

```bash
git checkout -- docs CHANGELOG.md AGENTS.md
git clean -fd docs/archive docs/articles   # 若需连新目录一并撤销
```
