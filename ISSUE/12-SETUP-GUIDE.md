# 12-A / 12-B 准备指南 — 自托管 Runner + 阿里云 ACR

> 本指南说明 12-A（自托管 Runner）和 12-B（阿里云 ACR）两项 CI/CD 优化的前置准备步骤。
> 两者独立，可按需选择执行。12-A 优先（直接复用服务器，省构建等待时间），12-B 可选（国内拉取镜像加速）。

---

## 目录

- [12-A 自托管 Runner](#12-a-自托管-runner)
- [12-B 阿里云 ACR](#12-b-阿里云-acr)
- [对比与建议](#对比与建议)

---

## 12-A 自托管 Runner

### 概述

将 GitHub Actions 的构建/部署 job 从 GitHub 托管 runner 迁移到你自己控制的服务器上。
好处：构建速度快（本地 Docker 缓存）、不受 GitHub 分钟数限制、可控制构建环境。

### 前置条件

- 一台 Linux 服务器（Ubuntu 22.04/24.04 或 CentOS Stream 9）
- 服务器能访问 GitHub（拉代码）和你的部署目标
- 服务器有 Docker 环境（构建镜像需要）

### 服务器配置要求

| 资源 | 最低配置 | 推荐配置 |
|---|---|---|
| CPU | 4 核 | 8 核（Buildx 并行构建吃 CPU） |
| 内存 | 8 GB | 16 GB（Docker daemon + Maven 编译峰值） |
| 磁盘 | 50 GB | 100 GB（镜像缓存 + 构建产物） |
| 网络 | 能访问 GitHub | 有固定公网 IP |

> 注意：Runner 可以和业务容器共存于同一台服务器，不额外占用资源时仅占 ~200MB 内存。

### 安装步骤

#### 1. 安装 Docker

```bash
# Ubuntu
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker

# 将当前用户加入 docker 组（Runner 进程需要无密码访问 Docker）
sudo usermod -aG docker $USER
```

验证：

```bash
docker run --rm hello-world
```

#### 2. 在 GitHub 仓库注册 Runner

1. 打开 GitHub 仓库 → **Settings** → **Actions** → **Runners**
2. 点击 **New runner**
3. 选择操作系统（Linux x64）
4. 按页面提示在服务器上执行：

```bash
# 创建目录
mkdir actions-runner && cd actions-runner

# 下载 Runner（版本号以页面显示为准）
curl -o actions-runner-linux-x64-2.320.0.tar.gz \
  -L https://github.com/actions/runner/releases/download/v2.320.0/actions-runner-linux-x64-2.320.0.tar.gz

# 解压
tar xzf actions-runner-linux-x64-2.320.0.tar.gz

# 配置（将 <TOKEN> 替换为 GitHub 页面显示的 token）
./config.sh --url https://github.com/<你的用户名>/zxyz --token <TOKEN>

# 安装为 systemd 服务（开机自启）
sudo ./svc.sh install github-runner
sudo ./svc.sh start

# 验证状态
sudo systemctl status actions.runner.<用户名>.zxyz
```

#### 3. 验证 Runner 连接

回到 GitHub 仓库 → **Settings** → **Actions** → **Runners**，看到 runner 状态为 **Idle** 即表示连接成功。

触发一次 push 到 dev 分支，观察 workflow 是否跑在自托管 runner 上：

```bash
# 在服务器上查看 Runner 日志
journalctl -u actions.runner.<用户名>.zxyz -f
```

### 修改 CI/CD 配置

目前 `ci-cd.yml` 中 5 个 job 使用 `runs-on: ubuntu-latest`，需要改为 `runs-on: self-hosted`。

**建议策略**：仅将需要 Docker 构建的 job 改为 `self-hosted`，`detect-changes` job（只跑 paths-filter，轻量）可以保留 `ubuntu-latest`。

```yaml
# .github/workflows/ci-cd.yml

jobs:
  detect-changes:
    runs-on: ubuntu-latest   # 保留 GitHub 托管（轻量）

  build-and-push:
    runs-on: self-hosted     # 改为自托管（需要 Docker Buildx）
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.repository_owner }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: .
          file: ZXYZdatabaseBack/Dockerfile
          push: true
          tags: ghcr.io/${{ github.repository_owner }}/zxyz-project-service:${{ inputs.tag }}

  deploy:
    runs-on: self-hosted     # 改为自托管（需要 SSH + Docker）
```

### Runner 维护

```bash
# 查看运行状态
sudo systemctl status actions.runner.<用户名>.zxyz

# 查看实时日志
journalctl -u actions.runner.<用户名>.zxyz -f

# 停止 Runner
cd actions-runner && ./svc.sh stop

# 更新 Runner 版本（GitHub 发布新版本后）
cd actions-runner
./svc.sh stop
./config.sh remove --token <TOKEN>
# 重新下载新版本 + config.sh + svc.sh install + svc.sh start
```

### 注意事项

- **安全**：Runner 执行的是仓库代码，请确保仓库权限可控（私有仓库即可）
- **资源**：构建时 CPU/内存占用较高，建议在低峰期推送代码
- **自恢复**：systemd 服务配置后，服务器重启 Runner 会自动恢复
- **token 有效期**：GitHub 生成的 token 默认 1 小时过期，Runner 注册后用 systemd 管理不受影响

---

## 12-B 阿里云 ACR

### 概述

将镜像仓库从 GitHub Container Registry (GHCR) 切换到阿里云容器镜像服务（ACR）。
好处：国内服务器拉取镜像速度快（华东/华南区域延迟低），避免 GHCR 的跨国带宽瓶颈。

### 前置条件

- 阿里云账号（已完成实名认证）
- 服务器在国内（或网络能稳定访问阿里云）

### 开通 ACR

#### 1. 注册阿里云并开通 ACR

1. 登录 [阿里云控制台](https://www.aliyun.com/)，完成实名认证
2. 搜索 **容器镜像服务 ACR** → 点击开通
3. 选择实例类型：
   - **个人版**：免费额度（最大 2 个镜像仓库，每个 10GB 存储），够用
   - **企业版**：按需付费，适合团队/生产

#### 2. 创建命名空间和镜像仓库

1. 进入 ACR 控制台 → **镜像仓库** → **命名空间**
2. 创建命名空间：`zxyz`
3. 在命名空间下创建镜像仓库（跟项目名称对应即可，如 `zxyz-project-service`）

#### 3. 获取 ACR 登录凭证

**方式一：固定密码**

1. ACR 控制台 → **镜像仓库** → **访问凭证**
2. 用户名：阿里云账号 ID（在账号中心查看）
3. 密码：设置固定密码

**方式二：AccessKey（推荐）**

1. 创建 RAM 子账号，权限仅限 ACR 操作（`AliyunContainerRegistryFullAccess`）
2. 生成 AccessKey ID 和 Secret

> 建议创建 RAM 子账号，不要使用主账号密码。

### 配置 GitHub Secrets

仓库 → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**：

| Secret 名称 | 值 | 说明 |
|---|---|---|
| `ACR_USERNAME` | 阿里云账号 ID 或 RAM 用户名 | ACR 登录用户名 |
| `ACR_PASSWORD` | ACR 访问密码或 AccessKey Secret | ACR 登录密码 |
| `ALIYUN_REGISTRY` | `registry.cn-shenzhen.aliyuncs.com` | 深圳区域地址（固定） |

### 在服务器上登录 ACR

部署服务器上需要先 `docker login` 才能拉取 ACR 镜像：

```bash
docker login registry.cn-shenzhen.aliyuncs.com \
  -u <ACR_USERNAME> \
  -p <ACR_PASSWORD>
```

> 持久化登录：`docker login` 会将凭证写入 `~/.docker/config.json`，Docker daemon 启动时自动加载。

### 执行切换脚本

项目已提供自动切换脚本 `scripts/setup-acr.sh`：

```bash
cd D:\code\databaseZXYZ\zxyz

# 切换到阿里云 ACR
bash scripts/setup-acr.sh enable
```

脚本会自动修改以下文件：

| 文件 | 变更内容 |
|---|---|
| `.github/workflows/ci-cd.yml` | `registry: ghcr.io` → `registry: registry.cn-shenzhen.aliyuncs.com`；镜像 tag 前缀切换 |
| `docker-compose.yml` | 镜像引用从 `ghcr.io/...` → `registry.cn-shenzhen.aliyuncs.com/zxyz/...` |
| `.env` | `IMAGE_PREFIX` 切换为 `registry.cn-shenzhen.aliyuncs.com/zxyz/` |

完成后提交：

```bash
git add -A
git commit -m "chore: 切换到阿里云 ACR 镜像源"
git push
```

### 切回 GHCR

```bash
bash scripts/setup-acr.sh disable
```

### 注意事项

- **镜像 tag 一致性**：切换前后 tag 格式不同（ACR 用 `registry.cn-shenzhen.aliyuncs.com/zxyz/<service>:<tag>`），确保切换时没有正在运行的部署
- **权限**：GitHub Secrets 中的 `ACR_USERNAME`/`ACR_PASSWORD` 仅对 CI/CD workflow 可见，不会暴露到日志
- **免费额度**：个人版 ACR 每月有免费额度（2 仓库 × 10GB），超出后按量付费，价格约 ¥0.18/GB/月

---

## 对比与建议

| 维度 | 12-A 自托管 Runner | 12-B 阿里云 ACR |
|---|---|---|
| **成本** | 服务器费用（已有的服务器即可复用） | 个人版免费额度够用，超出约 ¥0.18/GB/月 |
| **准备时间** | ~30 分钟（装 Docker + 配 Runner） | ~1 小时（注册 + 建仓库 + 配 Secrets） |
| **核心价值** | 构建速度更快（本地 Docker 层缓存），省 GitHub Actions 分钟数 | 国内拉取镜像速度快，避免 GHCR 跨国瓶颈 |
| **前置条件** | 需要一台 Linux 服务器 | 需要阿里云账号 |
| **能否跳过** | 能 — GitHub 托管 runner 完全可用 | 能 — GHCR 是默认方案，国内基本可用 |
| **风险** | 低 — Runner 只执行你仓库的代码 | 低 — 镜像仓库可随时切回 GHCR |

### 推荐顺序

```
第一步：12-A 自托管 Runner
  ↓ 复用现有服务器，30 分钟配置完，立竿见影省构建等待时间

第二步：12-B 阿里云 ACR（可选）
  ↓ 如果 GHCR 拉取速度不满意，再开 ACR 加速
```

> 两者都跳过也不影响功能 — 当前默认配置（GHCR + GitHub 托管 runner）已经可以正常 CI/CD。
