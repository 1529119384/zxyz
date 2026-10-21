# syntax=docker/dockerfile:1.7

# -----------------------------------------------------------------------------
# 前端 + Nginx Dockerfile
# 构建阶段打包 Vite 静态资源，运行阶段用 Nginx 提供静态文件并反向代理后端。
# -----------------------------------------------------------------------------

# 前端构建阶段使用官方 node:22-alpine（版本 tag + 不可变 @sha256 digest 双重钉住，非 :latest）。
# digest 刷新方式见后端 Dockerfile 顶部说明。
FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS builder

WORKDIR /workspace

COPY ZXYZdatabaseFront/package*.json ./
RUN npm ci

COPY ZXYZdatabaseFront/ ./
RUN npm run build

# 运行时基础镜像：官方 nginx Alpine 版（minor 版本 tag + 不可变 @sha256 digest 双重钉住，非 :latest）。
# 具体版本号以紧随其后的 FROM 行为唯一准绳 —— 此处**不再复写**版本号，避免注释与 FROM 漂移（C-50）。
# digest 刷新方式见后端 Dockerfile 顶部说明。
FROM nginx:1.31-alpine@sha256:62ff2089abf5a9ed33bd232895bef5e22f7bb4b200675cec49a5ebc48e3d4ac8

ENV TZ=Asia/Shanghai

# envsubst 用于启动时将环境变量注入 nginx 配置模板（如 OSS 域名）。
#
# 构建期把运行时基础镜像内【已安装】的 OS 包升级到最新安全修复版。
# 为什么必须这么做（2026-09-16 实测，见 ISSUE/22 §4.4）：
#   FROM 行的 @sha256 digest 把构建当时的 Alpine 包版本一并冻死了 ⇒ 镜像里可能带着上游
#   早已修复的漏洞包（openssl / c-ares / libexpat / libpng 等），Trivy 判 HIGH/CRITICAL
#   并作为 deploy 硬前置阻断上线（实测曾一次判出 37 条）。
#   刷新 digest 通常解决不了：上游 tag 可能长期不重建，digest 因此长期不变
#   ⇒ 只能在本层用 apk upgrade 把「已安装」的包拉到当前分支的最新修复版。
# 仅升级已安装包、不引入新包，也不动 FROM 行的 @sha256 digest 钉定策略
# —— 与 ZXYZdatabaseBack/Dockerfile 采用同一做法，保持前后端一致（此前只有前端漏了这一步，
#    因为前端镜像在 F-2 之前从未被 Trivy 扫过）。
#
# ⚠️ 此处刻意**不再列举**具体包版本号与 CVE 编号：它们随 Alpine 每次发补丁即过期，
#    写死在注释里只会变成误导（C-50）。要查镜像内实际版本，直接问镜像：
#      docker run --rm nginx:1.29-alpine apk list --installed | grep -E 'openssl|c-ares|libpng'
#
# ⚠️ 2026-09-18 补充（本行是必需的，不是可选的）：
#   GHA 构建缓存（`cache-from/to: type=gha, mode=max`）会把本 RUN 层整体复用 ——
#   实测本 RUN 层命中 `CACHED` 时**根本不会重新下载 Alpine 索引**，因此 `apk upgrade`
#   退化为 no-op，镜像里仍是旧包。症状：某次修复首次生效后，之后每次构建都复用旧层；
#   等 Alpine 再发新补丁时，镜像里还停在旧版本 ⇒ Trivy 判 HIGH ⇒ deploy 被静默阻断
#   （且只在「该镜像恰好被重建」时才暴露，极易误判为偶发）。
#   ⇒ 用 APK_UPGRADE_CACHE_BUST 打破该层缓存：CI 每轮传不同的值（github.run_id），
#      本 RUN 层因此每轮都真实执行、每次都拉到最新安全修复。
ARG APK_UPGRADE_CACHE_BUST=0
RUN apk add --no-cache gettext && apk upgrade --no-cache

# 站点配置作为模板，启动时由 entrypoint.sh 用 envsubst 替换变量。
COPY deploy/nginx/default.conf /etc/nginx/templates/default.conf.template
# TLS 站点配置同样作为模板内置（TLS_ENABLED=true 时由 entrypoint 渲染）。
# 内置而非由 overlay 从宿主机挂载：避免绑定挂载路径缺失/文件在容器内归 root 导致
# appuser 无法写入。overlay 只需挂载证书目录 + 设 TLS_ENABLED=true。
COPY deploy/nginx/default-ssl.conf /etc/nginx/templates/default-ssl.conf.template
# 共享片段：
# - http-common.conf 为静态 http 级指令（limit_req_zone / gzip），无占位符，直接内置。
# - proxy-headers.conf 为静态的共享代理请求头（Host / X-Forwarded-*），无占位符，直接内置；
#   由 proxy-locations.conf 的各个 location 用 include 引入
#   （C-51：原先同一组 5 行在 5 个 location 里各写一遍共 25 行，改一处要改 5 处、易漂移）。
# - proxy-locations.conf 含 ${OSS_PUBLIC_BASE_URL}（CSP 内），作为模板由 entrypoint 渲染到
#   /etc/nginx/snippets/proxy-locations.conf；HTTP 与 HTTPS 两套配置共用，避免漂移。
COPY deploy/nginx/snippets/http-common.conf /etc/nginx/snippets/http-common.conf
COPY deploy/nginx/snippets/proxy-headers.conf /etc/nginx/snippets/proxy-headers.conf
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
