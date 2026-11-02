# openresty 外层反代（66 号二.2 收口件）

## 现状

宿主机 80/443 由 openresty 常驻（自 2026-09），回源 `frontend-nginx:8081`（compose 映射 `${HTTP_PORT:-8081}`）。
62d P1-3 审计确认三个问题：①配置只在服务器本地（换机即丢）；②外层若覆盖 XFF 会破坏
gateway 的按 IP 风控；③两跳零监控。

## 收口内容（2026-10-20）

1. **配置入库**：`deploy/openresty/zxyz.conf.tmpl` 为外层推荐基线模板
   （vhost/TLS/WS 升级 map/XFF 追加/real_ip 还原/外层限流分桶/stub_status 监控端点）。
2. **XFF 语义**：外层必须用 `proxy_add_x_forwarded_for`（追加），与内层
   `snippets/proxy-headers.conf` 的覆盖式写法分层——内层是最后一跳（gateway 前），
   覆盖是正确的；外层是第一跳，覆盖会抹掉客户端真实链。
3. **限流分桶**：外层用 `real_ip` 还原真实客户端 IP 后再分桶，避免全站共享单桶 429。
4. **监控端点**：openresty `stub_status` 绑 127.0.0.1:8082，后续接 node_exporter /
   prometheus 抓取（待宿主采集器扩展，登记 66 号二.1 后续）。

## 部署步骤（人工，一次性）

```bash
# 1. 拷贝模板到服务器 openresty 配置目录并填充占位符
scp deploy/openresty/zxyz.conf.tmpl root@160.202.46.118:/usr/local/openresty/nginx/conf/conf.d/zxyz.conf
# 2. 服务器上：替换 {{SERVER_NAME}} / {{BACKEND_HOST}}（默认 127.0.0.1:8081），
#    按实际证书路径调整 ssl_certificate 行
# 3. nginx -t && nginx -s reload
```

## 为什么不自动化部署

openresty 不在本仓 docker-compose 编排内（宿主机常驻进程），且证书/域名是环境事实。
自动化同步需运维决策（是否纳入 deploy-on-server.sh 同步白名单——与 render-alertmanager
同型，已登记）。**当前状态：配置有版本化源头（本目录），换机不再丢。**

## 2026-11-02 复审修正（69 号 §十一-6）

模板此前从未被完整审过；本次逐行复审并用**真实 `nginx -t`** 实测，修掉三处
会直接导致配置加载失败/行为不符预期的问题：

| # | 问题 | 后果 | 修正 |
|---|---|---|---|
| 1 | `limit_req_zone` 写在 `server{}` 内 | `nginx -t` 直接报 `"limit_req_zone" directive is not allowed here`（它是 **http 级**指令） | 上移到文件顶部 http 级区域 |
| 2 | `map '$http_upgrade' '$connection_upgrade'` 变量名带引号 | 引号会被当字面量，`$connection_upgrade` 取不到值 ⇒ WebSocket 升级头错误 | 改为不带引号 |
| 3 | `http2 on;` 未标注版本要求 | OpenResty/nginx < 1.25.1 不识别该指令 ⇒ 配置加载失败 | 注释明确版本要求与旧版写法（`listen 443 ssl http2;`） |

复现验证（本次实际执行）：

```bash
# 占位符替换 + 自签证书后，用容器跑真实 nginx -t
docker run --rm -v <dir>/conf.d:/etc/nginx/conf.d:ro \
  -v <dir>/nginx.conf:/etc/nginx/nginx.conf:ro \
  -v <dir>/certs:/etc/nginx/certs:ro nginx:alpine nginx -t
# 修正前：emerg "limit_req_zone" directive is not allowed here
# 修正后：syntax is ok / test is successful
```

> 结论：**「配置写出来」≠「配置能加载」**。这类模板必须以 `nginx -t` 为验收标准，
> 只读 diff 看不出来（69 号 §附二 的同一教训）。


## 遗留

- 外层 stub_status 接入 prometheus（需宿主侧抓取通道，与二.1 告警链扩容同批）；
- `{{SERVER_NAME}}`/证书路径的实际值回填后建议把填充结果再拷回本目录一份存档（脱敏域名可选）。
