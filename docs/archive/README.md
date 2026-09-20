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

## 未归档（仍留 `docs/` 根，2 个）—— 全部是活文档

> 计数口径：`docs/` 根只剩 2 篇 Markdown，**已无任何在办方案文档**。
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
| 移入 `docs/archive/` | 36 | 6 | 13 | **55** |
| 移入 `docs/articles/` | 5 | 0 | 0 | **5** |
| 同步改写引用 | 52 处 / 20 文件 | 57 处 / 41 文件 | 68 处 / 29 文件 | **177 处**（涉及 65+ 文件，各轮有重叠） |
| `docs/` 根目录 | 62 → 21 | 21 → 15 | 15 → **2** | **−60**（仅剩 2 篇活文档） |

> 上表只计「三轮归档」。另有 **2 份审查报告**自 `logs_temp/` 迁入 `docs/archive/`
> （见上文「附：验证证据迁移」），故 `docs/archive/` 现共 **57 篇文档 + 本索引**。

## 回滚

归档为**纯移动**（`git mv`，历史保留），未删除任何内容。整体回滚：

```bash
git checkout -- docs CHANGELOG.md AGENTS.md
git clean -fd docs/archive docs/articles   # 若需连新目录一并撤销
```
