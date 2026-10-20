@echo off
chcp 936 >nul
rem ============================================================
rem  蓝序 FinView 一键启动（后端 :8080 + 前端 :5173）
rem  用法：双击本文件即可；只想看端口占用、不启动：dev.bat check
rem  注意：本文件是 GBK 编码，别用编辑器另存成 UTF-8，否则中文会变乱码、
rem        甚至 cmd 解析错乱（UTF-8 批处理在 65001 代码页下会吃字符）。
rem  后端用 mvnw -o（离线）；若刚往 pom.xml 加了新依赖，把启动行里的
rem  -o 去掉再跑一次，否则会报找不到依赖。
rem ============================================================
setlocal enabledelayedexpansion
title 蓝序 FinView 启动器

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "DRYRUN="
if /i "%~1"=="check" set "DRYRUN=1"

echo.
echo   ============================================
echo     蓝序 FinView   一键启动
echo   ============================================
echo.
echo   项目目录: %ROOT%
echo.

call :ensurePort 8080 后端
call :ensurePort 5173 前端
echo.

if not exist "%ROOT%\Frontend\node_modules" (
    echo   [提示] Frontend\node_modules 不存在，先去 Frontend 目录跑一次 pnpm install。
    echo.
)

if defined DRYRUN (
    echo   [check] 只检查端口，未启动任何服务。
    echo   [check] 后端: 在 "%ROOT%\Backend" 里执行 .\mvnw.cmd -o spring-boot:run
    echo   [check] 前端: 在 "%ROOT%\Frontend" 里执行 npm run dev
    echo.
    pause
    exit /b 0
)

rem  下面 mvnw 必须写成 .\mvnw.cmd：有的环境（比如从 Git Bash 里拉起 cmd）
rem  带 NoDefaultCurrentDirectoryInExePath，cmd 就不在当前目录找可执行文件了，
rem  写成光杆 mvnw.cmd 会直接「不是内部或外部命令」。
echo   [1/2] 启动后端 ...  Spring Boot, http://localhost:8080/api
start "蓝序-后端" /d "%ROOT%\Backend" cmd /k .\mvnw.cmd -o spring-boot:run

echo   [2/2] 启动前端 ...  Vite,        http://localhost:5173
start "蓝序-前端" /d "%ROOT%\Frontend" cmd /k npm run dev

echo.
echo   两个独立窗口已打开，关掉窗口 = 停掉对应服务。
echo   后端要 10~20 秒才就绪；稍后自动用浏览器打开前端页面。
echo.
ping -n 7 127.0.0.1 >nul
start "" http://localhost:5173
exit /b 0


rem ------------------------------------------------------------
rem  :ensurePort <端口> <中文名>
rem  端口被占用时打印占用进程，询问是否结束（默认不结束）
rem ------------------------------------------------------------
:ensurePort
set "PORT=%~1"
set "SVC=%~2"
set "FOUND_PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr "LISTENING" ^| findstr /c:":%PORT% "') do (
    if not defined FOUND_PID set "FOUND_PID=%%p"
)
if not defined FOUND_PID (
    echo   [空闲] 端口 %PORT%   可以启动%SVC%
    exit /b 0
)
set "FOUND_NAME=未知进程"
for /f "tokens=1 delims=," %%n in ('tasklist /FI "PID eq !FOUND_PID!" /FO CSV /NH 2^>nul') do set "FOUND_NAME=%%~n"
echo   [占用] 端口 %PORT%（%SVC%）被占:  PID !FOUND_PID!   !FOUND_NAME!
if defined DRYRUN exit /b 1
set "ANS="
set /p "ANS=         结束它再启动？ (y/N) "
if /i not "!ANS!"=="y" (
    echo          已跳过。残留进程不结束，%SVC% 可能起不来。
    echo          前端尤其注意：vite 会静默换到 5174，5173 上跑的还是旧代码。
    exit /b 1
)
taskkill /PID !FOUND_PID! /F >nul 2>&1
if errorlevel 1 (
    echo          结束失败：权限不足，试试右键「以管理员身份运行」本文件。
) else (
    echo          已结束 PID !FOUND_PID!，端口 %PORT% 已释放。
)
exit /b 0
