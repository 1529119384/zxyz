# Git 仓库与提交规范

## 仓库架构：根仓库为唯一提交与 CI 真相源

本项目采用 **monorepo + 嵌套独立仓库** 模式（非 git submodule）：

| 仓库 | 远程 | 分支 | 角色 |
|---|---|---|---|
| `zxyz/`（根） | `github.com/1529119384/zxyz.git` | dev | **唯一 CI/CD 真相源**：直接跟踪所有文件（含子目录源码），`.github/workflows/ci-cd.yml` 仅由根仓库 push 触发 |
| `ZXYZdatabaseBack/` | `github.com/1529119384/ZXYZdatabaseBack.git` | dev | 后端子仓库：独立开发历史，不触发 CI |
| `ZXYZdatabaseFront/` | `github.com/1529119384/ZXYZdatabaseFront.git` | main | 前端子仓库：独立开发历史，不触发 CI |

根仓库通过 `git ls-files` 直接管理 `ZXYZdatabaseBack/**` 和 `ZXYZdatabaseFront/**` 下的源码文件。子目录内的 `.git/` 被根 `.gitignore` 排除。

## CI 触发面（与 `.github/workflows/ci-cd.yml` paths-filter 一致）

根仓库 `on.push` / `on.pull_request` 的 paths 白名单：

```
ZXYZdatabaseBack/**
ZXYZdatabaseFront/**
deploy/**
docker-compose.yml
.env.example
.github/workflows/**
```

不在此白名单内的文件变更（如 `docs/**`、`nacos-config/**`、`scripts/**`、`sql/**`）**不触发** workflow。

## 提交规范与同步顺序

WHEN 修改后端代码, DO 按以下顺序双提交：
1. `cd ZXYZdatabaseBack && git add -A && git commit` — 子仓库保留开发历史
2. `cd <根目录> && git add ZXYZdatabaseBack/ && git commit` — **根仓库为 CI 真相源，遗漏此步则不触发构建**

WHEN 修改前端代码, DO 按以下顺序双提交：
1. `cd ZXYZdatabaseFront && git add -A && git commit` — 子仓库保留开发历史
2. `cd <根目录> && git add ZXYZdatabaseFront/ && git commit` — 根仓库触发 CI

WHEN 修改根目录文件（`docker-compose.yml`、`deploy/`、`.env.example`、`.github/`）, DO 仅在根目录提交（无需同步子仓库）。

WHEN 执行 git 操作, DO cd 到对应目录再执行，避免跨仓库误操作。

## 同步遗漏检查

提交后执行以下检查确认根仓库与子仓库一致：

```bash
# 在根目录执行：检查根仓库是否有未同步的子仓库变更
git status ZXYZdatabaseBack/ ZXYZdatabaseFront/

# 对比子仓库 HEAD 与根仓库跟踪内容（无输出 = 一致）
cd ZXYZdatabaseBack && git diff HEAD --stat && cd ..
cd ZXYZdatabaseFront && git diff HEAD --stat && cd ..
```

若 `git status` 显示子目录有 `modified`/`new file` 未暂存，说明子仓库已提交但根仓库遗漏同步，需补提交到根仓库。
