# =============================================================================
# ZXYZ 本地开发环境启动脚本 (Windows PowerShell)
# 仅启动基础设施（MySQL / Nacos / Redis / RabbitMQ），不启动 Java 服务。
# Java 服务通过 IDE 本地运行，连接容器网络。
#
# 用法:
#   .\scripts\dev-up.ps1              # 启动基础设施
#   .\scripts\dev-up.ps1 down         # 停止基础设施
#   .\scripts\dev-up.ps1 reset        # 重置数据卷（清空所有数据）
#   .\scripts\dev-up.ps1 logs         # 查看所有服务日志
#   .\scripts\dev-up.ps1 logs mysql   # 查看指定服务日志
# =============================================================================

param(
    [Parameter(Position = 0)]
    [ValidateSet("up", "start", "down", "stop", "reset", "logs")]
    [string]$Action = "up",

    [Parameter(Position = 1)]
    [string]$Service = ""
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$ComposeDev = Join-Path $ProjectRoot "docker-compose.dev.yml"
$ComposeBase = Join-Path $ProjectRoot "docker-compose.yml"

# --- 环境检查 ---

# 检查 .env 是否存在
$EnvFile = Join-Path $ProjectRoot ".env"
if (-not (Test-Path $EnvFile)) {
    Write-Host "ERROR: .env 不存在，请先复制 .env.example: Copy-Item .env.example .env" -ForegroundColor Red
    exit 1
}

# 检查 Docker 是否运行
try {
    $null = docker info 2>&1
    if ($LASTEXITCODE -ne 0) { throw }
} catch {
    Write-Host "ERROR: Docker 未运行，请先启动 Docker Desktop" -ForegroundColor Red
    exit 1
}

# --- 辅助函数 ---

function Invoke-Compose {
    param([string[]]$Args)
    docker compose -f $ComposeBase -f $ComposeDev @Args
}

function Get-EnvValue {
    param([string]$Key, [string]$Default)
    $line = Get-Content $EnvFile | Where-Object { $_ -match "^$Key=(.*)$" } | Select-Object -First 1
    if ($line -and $line -match "^$Key=(.*)$") {
        $val = $Matches[1].Trim()
        if ($val) { return $val }
    }
    return $Default
}

# --- 操作分发 ---

switch ($Action) {
    { $_ -in "up", "start" } {
        Write-Host "启动本地开发基础设施（MySQL / Nacos / Redis / RabbitMQ）..." -ForegroundColor Cyan
        Invoke-Compose @("up", "-d")

        Write-Host ""
        Write-Host "等待服务就绪..." -ForegroundColor Cyan
        Invoke-Compose @("ps")

        $mysqlPort = Get-EnvValue "MYSQL_PORT" "3306"
        $nacosPort = Get-EnvValue "NACOS_PORT" "8848"
        $nacosConsolePort = Get-EnvValue "NACOS_CONSOLE_PORT" "8080"
        $redisPort = Get-EnvValue "REDIS_PORT" "6379"
        $rabbitMgmtPort = Get-EnvValue "RABBITMQ_MGMT_PORT" "15672"
        $rabbitUser = Get-EnvValue "RABBITMQ_USER" "guest"

        Write-Host ""
        Write-Host "服务地址：" -ForegroundColor Green
        Write-Host "  MySQL:    localhost:$mysqlPort"
        Write-Host "  Nacos:    http://localhost:${nacosPort}/nacos"
        Write-Host "  Nacos UI: http://localhost:$nacosConsolePort"
        Write-Host "  Redis:    localhost:$redisPort"
        Write-Host "  RabbitMQ: http://localhost:$rabbitMgmtPort ($rabbitUser)"
        Write-Host ""
        Write-Host "IDE 中运行服务时，配置 Nacos 地址为: localhost:$nacosPort" -ForegroundColor Yellow
    }
    { $_ -in "down", "stop" } {
        Write-Host "停止本地开发基础设施..." -ForegroundColor Cyan
        Invoke-Compose @("down")
    }
    "reset" {
        Write-Host "WARNING: 这将删除所有数据卷（数据库、Nacos、Redis、RabbitMQ 数据）！" -ForegroundColor Yellow
        $confirm = Read-Host "确认？(yes/no)"
        if ($confirm -ne "yes") {
            Write-Host "已取消"
            exit 0
        }
        Invoke-Compose @("down", "-v")
        Write-Host "数据卷已清空" -ForegroundColor Green
    }
    "logs" {
        if ($Service) {
            Invoke-Compose @("logs", "-f", $Service)
        } else {
            Invoke-Compose @("logs", "-f")
        }
    }
}
