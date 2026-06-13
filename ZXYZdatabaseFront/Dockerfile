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

# 替换默认站点配置：包含 Vue history fallback、HTTP API 代理和 WebSocket 代理。
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/dist /usr/share/nginx/html

EXPOSE 80

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

CMD ["nginx", "-g", "daemon off;"]
