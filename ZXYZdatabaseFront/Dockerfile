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

# 运行时基础镜像使用官方 nginx:1.27-alpine（minor 版本 tag + 不可变 @sha256 digest 双重钉住，非 :latest）。
# digest 刷新方式见后端 Dockerfile 顶部说明。
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10

ENV TZ=Asia/Shanghai

# envsubst 用于启动时将环境变量注入 nginx 配置模板（如 OSS 域名）。
RUN apk add --no-cache gettext

# 站点配置作为模板，启动时由 entrypoint.sh 用 envsubst 替换变量。
COPY deploy/nginx/default.conf /etc/nginx/templates/default.conf.template
COPY deploy/nginx/entrypoint.sh /docker-entrypoint-custom.sh

COPY --from=builder /workspace/dist /usr/share/nginx/html

EXPOSE 80

# 创建用户、预创建输出文件并赋权（entrypoint 以 appuser 运行时可直接写入）。
RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && mkdir -p /var/cache/nginx/client_temp /var/cache/nginx/proxy_temp /var/cache/nginx/fastcgi_temp \
    && chown -R appuser:appgroup /var/cache/nginx /var/run /run \
    && touch /etc/nginx/conf.d/default.conf \
    && chown appuser:appgroup /etc/nginx/conf.d/default.conf \
    && chmod +x /docker-entrypoint-custom.sh
USER appuser

ENTRYPOINT ["/docker-entrypoint-custom.sh"]
