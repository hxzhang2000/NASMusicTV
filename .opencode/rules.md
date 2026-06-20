# NASMusicTV — Git 规范

## Commit 类型前缀

| 前缀 | 用途 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `refactor` | 重构 |
| `docs` | 文档 |
| `chore` | 构建、工具、配置 |

## 分支策略

- `main` — 稳定发布版
- `dev` — 日常开发
- `feat/*` — 功能分支

## 提交流程

1. 提交前先 `git status` + `git diff` 确认变更范围
2. 只 stage 目标文件，不要无差别 `git add .`
3. 格式：`<type>: <简短描述>`

## 记录同步

- 更新 `CHANGELOG.md`
- 更新 `docs/technical-overview.md` 第10节 修改记录
