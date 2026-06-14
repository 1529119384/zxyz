# syntax=docker/dockerfile:1.7

# -----------------------------------------------------------------------------
# 前端 + Nginx Dockerfile
# 构建阶段打包 Vite 静态资源，运行阶段用 Nginx 提供静态文件并反向代理后端。
# -----------------------------------------------------------------------------

FROM node:22-alpine AS builder

WORKDIR /workspace

COPY ZXYZdatabaseFront/package*.json ./
RUN npm ci

COPY ZXYZdatabaseFront/ ./
RUN npm run build

FROM nginx:1.27-alpine

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
