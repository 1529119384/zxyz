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

# --- CSP 域名 fail-closed（审计 I-18，2026-10-03）---
# 背景：docker-compose.yml 原先给 frontend-nginx 的 OSS_PUBLIC_BASE_URL 兜底了一个**硬编码**
#   默认桶域名（违反 CLAUDE.md「勿硬编码 OSS 域名」）。该默认值已被移除（改为空串），
#   因为它的失败模式是「静默放行一个错误域名」：nginx 的 CSP connect-src 只放行
#   ${OSS_PUBLIC_BASE_URL}，而浏览器是直传到 <OSS_BUCKET>.<OSS_ENDPOINT 主机>。
#   两者不一致时上传会被 CSP 拦掉，前端只报 "OSS upload failed: network error"（极难排查）。
# ⇒ 这里做**响亮失败**：值为空时直接退出，容器起不来（docker 会显示 exited），
#   而不是渲染出一份放行了错误域名的 CSP 让站点"看起来正常"。
#   判定口径与 nginx 无关，纯粹是"这个变量有没有被填"。
# 注意：本检查**不**校验它与 OSS_BUCKET/OSS_ENDPOINT 是否匹配 —— 那是部署前门禁
#   scripts/validate-env.sh 的 _oss_csp_check 的职责（它能看到 .env 里的全部取值）。
if [ -z "${OSS_PUBLIC_BASE_URL:-}" ]; then
    echo "FATAL: OSS_PUBLIC_BASE_URL 未设置或为空 —— nginx CSP 的 connect-src 必须放行" >&2
    echo "       浏览器实际上传的域名（<OSS_BUCKET>.<OSS_ENDPOINT 主机>），否则前端上传会被" >&2
    echo "       CSP 静默拦截（前端只报 network error）。" >&2
    echo "       请在 .env 里设置 OSS_PUBLIC_BASE_URL，例如：" >&2
    echo "         OSS_PUBLIC_BASE_URL=https://<your-bucket>.<your-endpoint-host>" >&2
    echo "       再执行 docker compose up -d frontend-nginx（restart 不会重载 env）。" >&2
    echo "       部署前预检：bash scripts/validate-env.sh .env" >&2
    exit 1
fi

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
