# syntax=docker/dockerfile:1.7

# -----------------------------------------------------------------------------
# 前端 + Nginx Dockerfile
# 构建阶段打包 Vite 静态资源，运行阶段用 Nginx 提供静态文件并反向代理后端。
# -----------------------------------------------------------------------------

# 前端构建阶段使用官方 node:22-alpine（版本 tag + 不可变 @sha256 digest 双重钉住，非 :latest）。
# digest 刷新方式见后端 Dockerfile 顶部说明。
FROM node:26-alpine@sha256:ef24c5053d50fdc3e4e56eb4e7ddb7861874ab0fdc797046ba897581deb8e868 AS builder

WORKDIR /workspace

COPY ZXYZdatabaseFront/package*.json ./
RUN npm ci

COPY ZXYZdatabaseFront/ ./
RUN npm run build

# 运行时基础镜像使用官方 nginx:1.27-alpine（minor 版本 tag + 不可变 @sha256 digest 双重钉住，非 :latest）。
# digest 刷新方式见后端 Dockerfile 顶部说明。
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10

ENV TZ=Asia/Shanghai

# envsubst 用于启动时将环境变量注入 nginx 配置模板（如 OSS 域名）。
#
# 构建期把运行时基础镜像（nginx:1.27-alpine）内【已安装】的 OS 包升级到最新安全修复版。
# 为什么必须这么做（2026-09-16 实测，见 ISSUE/22 §4.4）：
#   FROM 行的 @sha256 digest 把构建当时的 Alpine 包版本一并冻死了 —— 本镜像是 Alpine 3.21.3，
#   带 openssl 3.3.3-r0 / c-ares 1.34.5-r0 / libexpat 2.7.0-r0 / libpng 1.6.47-r0，
#   而 Alpine 3.21 分支早已发布修复包（3.3.7-r0 / 1.34.8-r0 / 2.8.4-r0 / 1.6.57-r0）
#   ⇒ Trivy 判 37 条 HIGH/CRITICAL（含 CRITICAL CVE-2026-31789）并作为 deploy 硬前置阻断上线。
#   刷新 digest 解决不了：nginx:1.27-alpine 上游最后一次更新是 2025-04-16，digest 至今未变
#   （1.27.5 是 1.27 线最后一个版本，该 tag 不会再被重建）。
# 仅升级已安装包、不引入新包，也不动 FROM 行的 @sha256 digest 钉定策略
# —— 与 ZXYZdatabaseBack/Dockerfile:70 采用同一做法，保持前后端一致（此前只有前端漏了这一步，
#    因为前端镜像在 F-2 之前从未被 Trivy 扫过）。
RUN apk add --no-cache gettext && apk upgrade --no-cache

# 站点配置作为模板，启动时由 entrypoint.sh 用 envsubst 替换变量。
COPY deploy/nginx/default.conf /etc/nginx/templates/default.conf.template
# TLS 站点配置同样作为模板内置（TLS_ENABLED=true 时由 entrypoint 渲染）。
# 内置而非由 overlay 从宿主机挂载：避免绑定挂载路径缺失/文件在容器内归 root 导致
# appuser 无法写入。overlay 只需挂载证书目录 + 设 TLS_ENABLED=true。
COPY deploy/nginx/default-ssl.conf /etc/nginx/templates/default-ssl.conf.template
# 共享片段：
# - http-common.conf 为静态 http 级指令（limit_req_zone / gzip），无占位符，直接内置。
# - proxy-locations.conf 含 ${OSS_PUBLIC_BASE_URL}（CSP 内），作为模板由 entrypoint 渲染到
#   /etc/nginx/snippets/proxy-locations.conf；HTTP 与 HTTPS 两套配置共用，避免漂移。
COPY deploy/nginx/snippets/http-common.conf /etc/nginx/snippets/http-common.conf
COPY deploy/nginx/snippets/proxy-locations.conf /etc/nginx/templates/snippets/proxy-locations.conf.template
COPY deploy/nginx/entrypoint.sh /docker-entrypoint-custom.sh

COPY --from=builder /workspace/dist /usr/share/nginx/html

EXPOSE 80

# 创建用户、预创建输出文件并赋权（entrypoint 以 appuser 运行时可直接写入）。
# 注意：/etc/nginx/snippets 必须一并 chown —— entrypoint 会把 proxy-locations.conf
# 渲染写入该目录；若仍归 root 所有，appuser 写入被拒（set -e 下直接导致容器退出，
# 连纯 HTTP 部署也会起不来）。
RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && mkdir -p /var/cache/nginx/client_temp /var/cache/nginx/proxy_temp /var/cache/nginx/fastcgi_temp /etc/nginx/snippets \
    && chown -R appuser:appgroup /var/cache/nginx /var/run /run /etc/nginx/snippets \
    && touch /etc/nginx/conf.d/default.conf \
    && chown appuser:appgroup /etc/nginx/conf.d/default.conf \
    && chmod +x /docker-entrypoint-custom.sh
USER appuser

ENTRYPOINT ["/docker-entrypoint-custom.sh"]
