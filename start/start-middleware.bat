@echo off
title ZXYZ Middleware Starter

:: === Auto-elevate to admin if not already ===
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator privileges...
    powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

setlocal enabledelayedexpansion

:: === Path Config ===
set REDIS_PATH=D:\soft\Redis-x64-3.0.504
set RABBITMQ_PATH=D:\soft\RabbitMQ\rabbitmq_server-3.13.6
set NACOS_PATH=D:\soft\nacos

echo ================================
echo   ZXYZ Middleware Starter
echo ================================
echo.

:: --- Stop Redis ---
echo [1/6] Stopping Redis...
%REDIS_PATH%\redis-cli.exe shutdown >nul 2>&1
taskkill /F /IM redis-server.exe >nul 2>&1
timeout /t 1 /nobreak >nul
echo       Done

:: --- Stop RabbitMQ ---
echo [2/6] Stopping RabbitMQ...
:: Stop via Windows service manager (now we have admin)
net stop RabbitMQ >nul 2>&1
:: Fallback: use rabbitctl + taskkill
cmd /c %RABBITMQ_PATH%\sbin\rabbitmqctl.bat stop >nul 2>&1
timeout /t 3 /nobreak >nul
taskkill /F /IM beam.smp.exe >nul 2>&1
taskkill /F /IM erl.exe >nul 2>&1
taskkill /F /IM epmd.exe >nul 2>&1
timeout /t 2 /nobreak >nul
echo       Done

:: --- Stop Nacos ---
echo [3/6] Stopping Nacos...
:: Kill ALL Java processes (Nacos is the only Java app in this middleware stack)
taskkill /F /IM java.exe >nul 2>&1
:: Also try shutdown.cmd as backup
cmd /c %NACOS_PATH%\bin\shutdown.cmd >nul 2>&1
:: Wait for ALL Nacos ports to be released (8848, 7848)
set /a _nacos_wait=0
:wait_nacos_port
set _nacos_busy=0
netstat -ano | findstr ":8848 " | findstr "LISTENING" >nul 2>&1 && set _nacos_busy=1
netstat -ano | findstr ":7848 " | findstr "LISTENING" >nul 2>&1 && set _nacos_busy=1
if !_nacos_busy! equ 1 (
    set /a _nacos_wait+=1
    if !_nacos_wait! geq 15 (
        echo       WARNING: Nacos ports still in use after 15s
        goto :nacos_port_ready
    )
    timeout /t 1 /nobreak >nul
    goto :wait_nacos_port
)
:nacos_port_ready
echo       Done
echo.

:: --- Start Redis ---
echo [4/6] Starting Redis...
start "Redis" cmd /k "%REDIS_PATH%\redis-server.exe %REDIS_PATH%\redis.windows.conf"
timeout /t 2 /nobreak >nul
netstat -ano | findstr ":6379" >nul 2>&1 && echo       Redis:    localhost:6379 (running) || echo       Redis:    start failed

:: --- Start RabbitMQ ---
echo [5/6] Starting RabbitMQ...
:: Try Windows service start first, fallback to direct start
net start RabbitMQ >nul 2>&1 || start "RabbitMQ" cmd /k "%RABBITMQ_PATH%\sbin\rabbitmq-server.bat"
timeout /t 15 /nobreak >nul
netstat -ano | findstr ":5672" >nul 2>&1 && echo       RabbitMQ: localhost:5672 (running) || echo       RabbitMQ: start failed

:: --- Start Nacos ---
echo [6/6] Starting Nacos (standalone)...
:: Final port check before starting
netstat -ano | findstr ":7848 " | findstr "LISTENING" >nul 2>&1 && (
    echo       ERROR: Port 7848 still in use! Nacos cannot start.
    goto :nacos_start_done
)
start "Nacos" cmd /k "%NACOS_PATH%\bin\startup.cmd -m standalone"
timeout /t 15 /nobreak >nul
:nacos_start_done
netstat -ano | findstr ":8848" >nul 2>&1 && echo       Nacos:    localhost:8848 (running) || echo       Nacos:    start failed
echo.

echo ================================
echo   All middleware started
echo ================================
echo.
pause
