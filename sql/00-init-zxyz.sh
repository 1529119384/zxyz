#!/bin/bash
set -e

# MySQL 官方镜像只会在数据目录首次为空时自动执行本脚本。
# 本脚本仅负责创建数据库，表结构由各服务的 Flyway 迁移脚本自动管理。
#
# ⚠️ 2026-10-03 修复（审计 I-14）：原写法 `mysql -uroot -p"${MYSQL_ROOT_PASSWORD}"`
#   把 root 口令放进 **argv** —— 同机任意进程可从 `ps` / `/proc/<pid>/cmdline` 读到，
#   且 MySQL 客户端在命令行带 -p 时会打印 "Warning: Using a password on the command
#   line interface can be insecure"（本仓其余脚本已全部改走 MYSQL_PWD，仅 initdb 残留口径不一致）。
#   改为经 MYSQL_PWD 环境变量传递：口令不进任何进程的 argv，也不产生该条警告。
#   兼容性：MYSQL_PWD 是 MySQL 官方客户端长期支持的环境变量（5.x~8.4 均可用）。
#
# 🔴 刻意**不**加 `-h127.0.0.1`（审计建议里的可选项，实测会引入回归）：
#   本脚本由 MySQL 官方镜像的 docker-entrypoint 在**临时 server** 上执行，而该临时 server
#   是以 `--skip-networking` 启动的（见官方 8.4/docker-entrypoint.sh 的
#   docker_temp_server_start）。MySQL 8.4 官方手册 8.2.22 明确：
#     "If the server was started with the skip_networking system variable enabled,
#      no TCP/IP connections are accepted."
#   ⇒ 加 -h127.0.0.1 会强制走 TCP ⇒ 首次初始化直接 "Can't connect to MySQL server on
#     '127.0.0.1'"，数据库根本建不出来（且只在**全新部署**时才暴露，最难排查）。
#   故保持默认的 Unix socket 连接（容器内 entrypoint 已把 socket 建好），只改口令传递方式。
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?缺少 MYSQL_ROOT_PASSWORD}"
mysql --default-character-set=utf8mb4 -uroot <<'SQL'
CREATE DATABASE IF NOT EXISTS zxyz_project
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS zxyz_im
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS zxyz_email
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS zxyz_share
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS zxyz_file
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS zxyz_team
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS zxyz_user
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS nacos
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS zxyz_audit
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS zxyz_config
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
SQL
