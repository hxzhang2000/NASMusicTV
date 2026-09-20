# docs/archive — 已完成文档归档

> **归档日期**：2026-09-20
> **归档依据**：见 `AGENTS.md` → Conventions → *Doc lifecycle* 与
> `docs/technical-overview.md` §10.166。
>
> 本目录存放**已完成使命**的文档：功能已发布、审查已闭环、方案已实施。
> 它们**不再维护**，保留在此仅作历史追溯与决策依据。
>
> ⚠️ **文档自身的「状态」标记可能过期**（写了「待评审 / 尚未开发」，但功能其实已落地、
> 只是没回填）。**判定以 `CHANGELOG.md` / `docs/technical-overview.md` 为准**，
> 不以文档头部状态标记为准。

---

## 一、代码审查 / 快照（10）

| 文档 | 判定依据 |
|---|---|
| `code-review-2026-06-26.md` | 审查报告；问题已处理（v2.4.2 修复 9 项，其余已评估） |
| `code-review-2026-06-30.md` | 审查报告；结论已并入后续优化方案 |
| `code-review-2026-09-03.md` + `.docx` | 审查报告；问题已修复并记录于 `technical-overview.md` §10.14x |
| `code-review-2026-09-07.md` | 离线下载功能审查报告；结论已落地 |
| `code-review-2026-09-16.md` + `.html` | 审查报告；问题已修复 |
| `code-review-deferred-analysis-2026-09-04.md` + `.docx` | 复审暂缓项分析；三项均已处理 |
| `codebase-architecture.html` | 架构快照，一次性产物 |

## 二、已落地的功能方案（22）

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

## 三、其他一次性产物（4）

| 文档 | 判定依据 |
|---|---|
| `regression-test.md` | 回归测试清单；被 `CHANGELOG.md` / `technical-overview.md` 引作历史依据 |
| `v2.7.0-feature-test.md` | v2.7.0 功能测试计划，该版本早已发布 |
| `roadmap.md` | 2026-07-01 待办清单，11 项完成 10 项 |
| `百度网盘音乐播放开发方案-评审意见.md` | 评审意见 15 项已全部回填至方案 v2 |

---

## 未归档（仍留 `docs/` 根）

以下文档**不满足**归档条件，继续留在 `docs/` 根维护：

**① 被源码 / `AGENTS.md` / `README.md` / CI 引用（15 个）** —— 移走会断掉代码注释里的设计依据：

`technical-overview.md`、`conventions-adaptive-ui.md`、`phone-portrait-ui-plan.md`、
`phone-media-display-plan.md`、`phone-support-plan.md`、`playlist-import-feature-plan.md`、
`mv-karaoke-feature-proposal.md`、`vocal-removal-approach-b-dsp.md`、`feature-dev-plan-2026-09.md`、
`android-auto-plan.md`、`feiniu-backend-improvement-plan.md`、`百度网盘音乐播放开发方案.md`、
`codebase-refactoring-plan-2026-09.md`、`music-visualizer-dev-plan.md`（+ `.docx`）、
`催眠频谱效果开发方案.md`

**② CHANGELOG 无落地痕迹（5 个）** —— 方案尚未实施，归档会把在办工作埋掉：

| 文档 | 未落地证据 |
|---|---|
| `aliyundrive-support-plan.md` | CHANGELOG 中阿里云盘为**灰显「敬请期待」占位** |
| `audition-lyrics-solution.md` | 「试听 / 30 秒 / 歌词时间轴拖拽」在 CHANGELOG **零命中** |
| `metadata-search-service-solution.md` | 「元数据搜索」在 CHANGELOG **零命中** |
| `network-music-upgrade-plan.md` | 「对接 Go Music API」在 CHANGELOG **零命中** |
| `unified-source-architecture.md` | 「统一音乐源」在 CHANGELOG **零命中** |

---

## 回滚

本次归档为**纯移动**（`git mv`，历史保留），未删除任何内容。整体回滚：

```bash
git checkout -- docs CHANGELOG.md
git clean -fd docs/archive docs/articles   # 若需连新目录一并撤销
```
