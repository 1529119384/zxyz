# GitHub 按钮式手动部署

## 目标

- `push main` 时只执行构建与测试，不自动部署
- 需要上线时，在 GitHub Actions 页面手动触发 `Deploy`

## 仓库新增内容

- `.github/workflows/ci.yml`
- `.github/workflows/deploy.yml`
- `deploy/deploy.sh`
- `deploy/zxyz-database.service`

## 服务器准备

### 1. 安装运行环境

- Java 17
- `systemd`
- `curl`

### 2. 创建目录

```bash
sudo mkdir -p /opt/zxyz-database/{bin,current,incoming,releases,shared}
sudo mkdir -p /var/log/zxyz-database
sudo useradd -r -s /usr/sbin/nologin zxyz || true
sudo chown -R zxyz:zxyz /opt/zxyz-database /var/log/zxyz-database
```

### 3. 安装部署脚本

将仓库中的 `deploy/deploy.sh` 放到服务器：

```bash
sudo cp deploy/deploy.sh /opt/zxyz-database/bin/deploy.sh
sudo chmod +x /opt/zxyz-database/bin/deploy.sh
sudo chown zxyz:zxyz /opt/zxyz-database/bin/deploy.sh
```

### 4. 配置生产环境变量

生产环境推荐使用 Docker Compose 部署（参见仓库根目录 `docker-compose.yml` 和 `.env.example`）。

如需单独部署 project-service，在 `/opt/zxyz-database/shared/app.env` 中维护配置，例如：

```bash
PROJECT_DATASOURCE_URL=jdbc:mysql://mysql-prod:3306/zxyz_project
PROJECT_DATASOURCE_USERNAME=prod_user
PROJECT_DATASOURCE_PASSWORD=replace_me
REDIS_HOST=redis-prod
REDIS_PORT=6379
REDIS_PASSWORD=replace_me
USER_SERVICE_BASE_URL=http://user-service:18083
TEAM_SERVICE_BASE_URL=http://team-service:18086
FILE_SERVICE_BASE_URL=http://file-service:18085
IM_SERVICE_BASE_URL=http://im-service:18081
EMAIL_SERVICE_BASE_URL=http://email-service:18082
INTERNAL_SERVICE_TOKEN=replace_me
```

说明：

- 生产配置不进入 Git 仓库
- 微服务架构下各服务有独立数据库和配置，完整环境变量参见 `.env.example`

### 5. 安装 systemd 服务

```bash
sudo cp deploy/zxyz-database.service /etc/systemd/system/zxyz-database.service
sudo systemctl daemon-reload
sudo systemctl enable zxyz-database.service
```

首次部署成功后再执行：

```bash
sudo systemctl start zxyz-database.service
```

## GitHub Secrets

在仓库 `Settings -> Secrets and variables -> Actions` 中创建以下 secrets：

- `SERVER_HOST`：服务器 IP 或域名
- `SERVER_PORT`：SSH 端口，例如 `22`
- `SERVER_USER`：部署用户，要求能执行 `sudo systemctl restart zxyz-database.service`
- `SERVER_SSH_KEY`：部署用户对应的私钥
- `DEPLOY_PATH`：固定为 `/opt/zxyz-database`
- `SERVICE_NAME`：固定为 `zxyz-database.service`

## 工作流行为

### CI

- 触发条件：`push` 到 `main`
- 执行内容：
  - `mvn test`
  - `mvn package -DskipTests`

### Deploy

- 触发条件：GitHub Actions 页面手动点击 `Run workflow`
- 执行内容：
  - 检出 `main`
  - 构建最新 jar
  - 上传到 `/opt/zxyz-database/incoming`
  - 调用 `/opt/zxyz-database/bin/deploy.sh`
  - 重启服务并检查 `/actuator/health`

## 回滚机制

- 每次部署都会把新包保存在 `/opt/zxyz-database/releases/<timestamp>/app.jar`
- 如果当前已有运行版本，会在同一发布目录下备份为 `previous-app.jar`
- 健康检查失败时，脚本会自动恢复旧版本并重启服务

## 手动验证

### 验证 CI

提交到 `main` 后确认：

- GitHub Actions 中 `CI` 成功
- 不会自动出现服务重启

### 验证 Deploy

在 GitHub Actions 页面手动运行 `Deploy`，确认：

- `incoming` 中收到新 jar
- `systemctl status zxyz-database.service` 正常
- `curl http://127.0.0.1:18080/actuator/health` 返回 `UP`

### 验证回滚

可故意提供错误生产配置，确认：

- 新版本启动失败
- 脚本自动恢复到上一个 jar
- 服务最终恢复可用
