#!/bin/sh
# 渲染 nginx 配置：仅替换 ${OSS_PUBLIC_BASE_URL}，保留 nginx 自身的 $host、$remote_addr 等。
#
# - HTTP-only（默认，TLS_ENABLED 未置 true）：用镜像内置模板渲染 conf.d/default.conf。
# - TLS（TLS_ENABLED=true）：default-ssl.conf 由 docker-compose.tls.yml 以「只读模板」
#   挂载到 /etc/nginx/templates/default-ssl.conf.template，此处渲染到 conf.d/default.conf。
#
# 共享片段 snippets/proxy-locations.conf 同样含 ${OSS_PUBLIC_BASE_URL}（CSP 内），
# 必须一并渲染——envsubst 仅处理主文件，不会递归替换 include 进来的片段。

set -e

CONF=/etc/nginx/conf.d/default.conf
SNIPPET_TPL=/etc/nginx/templates/snippets/proxy-locations.conf.template
SNIPPET_OUT=/etc/nginx/snippets/proxy-locations.conf

mkdir -p /etc/nginx/snippets

# 渲染共享 location 片段（含 CSP 中的 ${OSS_PUBLIC_BASE_URL}）。
envsubst '${OSS_PUBLIC_BASE_URL}' < "$SNIPPET_TPL" > "$SNIPPET_OUT"

if [ "${TLS_ENABLED}" = "true" ]; then
    # TLS 模式：从只读模板渲染到镜像内可写（已 chown appuser）的 conf.d/default.conf。
    # 不能就地渲染绑定挂载文件（容器内为 root 所有，appuser 无写权限）。
    envsubst '${OSS_PUBLIC_BASE_URL}' \
        < /etc/nginx/templates/default-ssl.conf.template \
        > "$CONF"
else
    # HTTP-only：渲染镜像内置模板（与历史行为完全一致）。
    envsubst '${OSS_PUBLIC_BASE_URL}' \
        < /etc/nginx/templates/default.conf.template \
        > "$CONF"
fi

exec nginx -g 'daemon off;'
