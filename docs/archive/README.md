# docs/archive — 已完成文档归档

> **归档日期**：2026-09-20（两轮）
> **归档依据**：见 `AGENTS.md` → Conventions → *Doc lifecycle* 与
> `docs/technical-overview.md` §10.166。
>
> 本目录存放**已完成使命**的文档：功能已发布、审查已闭环、方案已实施。
> 它们**不再维护**，保留在此仅作历史追溯与决策依据。

## ⛔ 两条判定原则（都不是「是否被引用」）

**原则 1 — 文档自身的「状态」标记会过期，以 `CHANGELOG.md` / `technical-overview.md` 为准。**
标记只说明「文档没回填」，不说明「功能没做」。

**原则 2 — 「被源码 / `AGENTS.md` 引用」不是归档障碍。**
移动时会在同一次操作里改写**全部**引用（含 `app/src/**` 的 KDoc、`AGENTS.md`、
`CHANGELOG.md`、跨文档链接），路径依然有效，**不会断链**。把「被引用」当否决
会白留一堆已完成的文档 —— 这正是第二轮归档（6 个文档、57 处引用）纠正的错误。

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

## 未归档（仍留 `docs/` 根，15 个）

> 计数口径：下列 14 篇 Markdown + `music-visualizer-dev-plan.docx`（对照件）= 15 个文件。
> `docs/snapshot/`（16 张截图）与 `*.miora` 素材目录不是文档，不计入。

### ① 活文档 —— 持续维护，永远留根（4）

| 文档 | 为什么是「活」的 |
|---|---|
| `technical-overview.md` | 全项目主索引，§10.N 持续追加（当前最大 §10.166） |
| `conventions-adaptive-ui.md` | 自适应 UI 约定，随新组件持续维护 |
| `codebase-refactoring-plan-2026-09.md` | 重构方案 v1.6 持续修订，含 R/F/N 系列状态跟踪与**待所有者决策项** |
| `vocal-removal-approach-b-dsp.md` | §10.152 明确指定它为**算法复原依据**（「算法先归档，本文保证可复原」）—— 它是归档的**目的地**，不是被归档对象 |

### ② 功能未落地 —— 归档会把在办工作埋掉（10）

| 文档 | 未落地证据 |
|---|---|
| `aliyundrive-support-plan.md` | CHANGELOG 中阿里云盘为**灰显「敬请期待」占位** |
| `audition-lyrics-solution.md` | 「试听 / 30 秒 / 歌词时间轴拖拽」在 CHANGELOG **零命中** |
| `metadata-search-service-solution.md` | 「元数据搜索」**零命中** |
| `network-music-upgrade-plan.md` | 「对接 Go Music API」**零命中**（现役仍是 Meting 层） |
| `unified-source-architecture.md` | 「统一音乐源」**零命中** |
| `android-auto-plan.md` | 文档自述「阶段 1 已实施；**阶段 2/3/4 未实施**」 |
| `music-visualizer-dev-plan.md`（+ `.docx`） | 文档自述「v6.0（**可开发状态**）」= 尚未开发 |
| `百度网盘音乐播放开发方案.md` | 文档自述「**Phase 7 未开始**」 |
| `feature-dev-plan-2026-09.md` | 「待所有者评审」，F2 系列 6 项**仅部分落地** |
| `phone-media-display-plan.md` | 「方案设计（**待评审**）」，实况窗 / 播放保活未实施 |

---

## 汇总

| 项 | 第一轮 | 第二轮 | 合计 |
|---|---|---|---|
| 移入 `docs/archive/` | 36 | 6 | **42** |
| 移入 `docs/articles/` | 5 | 0 | **5** |
| 同步改写引用 | 52 处 / 20 文件 | 57 处 / 41 文件 | **109 处**（涉及 40+ 文件，两轮有重叠） |
| `docs/` 根目录 | 62 → 21 | 21 → **15** | −47 |

## 回滚

归档为**纯移动**（`git mv`，历史保留），未删除任何内容。整体回滚：

```bash
git checkout -- docs CHANGELOG.md AGENTS.md
git clean -fd docs/archive docs/articles   # 若需连新目录一并撤销
```
