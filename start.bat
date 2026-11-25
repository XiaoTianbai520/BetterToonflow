@echo off
setlocal EnableExtensions
chcp 65001 >nul
title ToonFlow Dev Launcher

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "ENV_FILE=%ROOT%\.env.local"

if /i "%~1"=="stop" goto stop

echo ============================================
echo  ToonFlow dev launcher
echo ============================================

if not exist "%ENV_FILE%" (
  echo [x] Missing %ENV_FILE%
  pause
  exit /b 1
)

rem ---------- 读取 .env.local（跳过空行与 # 注释） ----------
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
  if not "%%~A"=="" if not "%%~B"=="" set "%%A=%%B"
)

rem 数据目录必须是绝对路径：相对路径会被各子进程按自己的 cwd 解析
echo %TOONFLOW_DATA_DIR% | findstr /R "^[A-Za-z]:[/\\]" >nul || set "TOONFLOW_DATA_DIR=%ROOT%\data"
if not defined TOONFLOW_DB_URL set "TOONFLOW_DB_URL=jdbc:postgresql://192.168.1.102:5432/toonflow"
if not defined TOONFLOW_DB_USERNAME set "TOONFLOW_DB_USERNAME=root"

rem 工具/IDE 会注入这两个变量，Spring relaxed binding 会把它们当成 server.port / server.address
set "SERVER__PORT="
set "SERVER__HOST="

rem 清掉宿主注入的 Node 钩子：safe-delete 会拦截 vite 的依赖优化目录，导致 dev server 直接退出
set "NODE_OPTIONS="
set "ELECTRON_RUN_AS_NODE="

rem 本地 ComfyUI 与局域网 PostgreSQL 必须直连：避免走 HTTP_PROXY 返回 502
set "HTTP_PROXY="
set "HTTPS_PROXY="
set "ALL_PROXY="
set "http_proxy="
set "https_proxy="
set "all_proxy="
set "NO_PROXY=localhost,127.0.0.1,::1,192.168.1.102"
set "no_proxy=localhost,127.0.0.1,::1,192.168.1.102"

if not defined TOONFLOW_INTERNAL_TOKEN (
  echo [x] Missing TOONFLOW_INTERNAL_TOKEN in .env.local
  pause
  exit /b 1
)

if not defined TOONFLOW_DB_PASSWORD (
  echo [x] Missing TOONFLOW_DB_PASSWORD in .env.local
  pause
  exit /b 1
)

for /f "delims=" %%B in ('where bun 2^>nul') do if not defined BUN_EXE set "BUN_EXE=%%B"
if not defined BUN_EXE (
  echo [x] bun not found in PATH
  pause
  exit /b 1
)

echo  data dir : %TOONFLOW_DATA_DIR%
echo  db url   : %TOONFLOW_DB_URL%
echo  bun      : %BUN_EXE%
echo.

rem ---------- 1. Check remote PostgreSQL ----------
call :checkdb
if errorlevel 1 (
  echo [x] Remote PostgreSQL unreachable: %TOONFLOW_DB_URL%
  pause
  exit /b 1
)
echo [ok] Remote PostgreSQL reachable

rem ---------- 2. Bun agentRuntime :3001 ----------
call :port 3001
if not errorlevel 1 (
  echo [=] agentRuntime already running
) else (
  echo [^>] starting agentRuntime ...
  start "ToonFlow Runtime :3001" /D "%ROOT%\apps\agentRuntime" cmd /k ""%BUN_EXE%" run dev"
  call :wait 3001 60
  if errorlevel 1 (
    echo [x] agentRuntime not ready in 60s - check the Runtime window
    pause
    exit /b 1
  )
  echo [ok] agentRuntime ready
)

rem ---------- 3. Java :3000 ----------
call :port 3000
if not errorlevel 1 (
  echo [=] Java server already running
) else (
  echo [^>] starting Java server ^(first compile 20-60s^) ...
  start "ToonFlow Server :3000" /D "%ROOT%\apps\server" cmd /k mvn spring-boot:run
  call :wait 3000 180
  if errorlevel 1 (
    echo [x] Java server not ready in 180s - check the Server window
    pause
    exit /b 1
  )
  echo [ok] Java server ready
)

rem ---------- 4. Vite :5173 ----------
call :port 5173
if not errorlevel 1 (
  echo [=] Web already running
) else (
  echo [^>] starting Vite ...
  start "ToonFlow Web :5173" /D "%ROOT%" cmd /k ""%BUN_EXE%" run dev:web"
  call :wait 5173 120
  if errorlevel 1 (
    echo [x] Vite not ready in 120s
    pause
    exit /b 1
  )
  echo [ok] Web ready
)

echo.
echo Ready: http://localhost:5173
echo Stop all: start.bat stop
start "" http://localhost:5173
timeout /t 3 >nul 2>&1
exit /b 0

rem ================= subroutines =================

:port
rem %1 port - return 0 when listening
netstat -ano | findstr /C:":%~1 " | findstr /C:"LISTENING" >nul 2>&1
exit /b %errorlevel%

:wait
rem %1 port, %2 timeout seconds
set /a _i=0
:wait_loop
call :port %~1
if not errorlevel 1 exit /b 0
set /a _i+=1
if %_i% GEQ %~2 exit /b 1
timeout /t 1 /nobreak >nul 2>&1
goto wait_loop

:checkdb
rem 校验远程 PostgreSQL TCP 可达（超时 3 秒），不启动本机 PostgreSQL
powershell -NoProfile -Command "$u = $env:TOONFLOW_DB_URL; if ($u -match '^jdbc:postgresql://([^:/?]+)(?::(\d+))?/') { $h = $matches[1]; $p = if ($matches[2]) { [int]$matches[2] } else { 5432 }; $c = New-Object System.Net.Sockets.TcpClient; $ar = $c.BeginConnect($h, $p, $null, $null); $ok = $ar.AsyncWaitHandle.WaitOne(3000, $false); if ($ok) { try { $c.EndConnect($ar); $c.Close(); exit 0 } catch { exit 1 } } else { $c.Close(); exit 1 } } else { exit 1 }" >nul 2>&1
exit /b %errorlevel%

:stop
echo Stopping ToonFlow dev services ...
for %%P in (3000 3001 5173) do call :killport %%P
echo Done.
timeout /t 2 >nul 2>&1
exit /b 0

:killport
rem %1 port - kill listening process
set "_pids="
for /f "tokens=5" %%I in ('netstat -ano ^| findstr /C:":%~1 " ^| findstr /C:"LISTENING"') do set "_pids=%%I"
if not defined _pids (
  echo   port %~1 -^> not running
  exit /b 0
)
for %%I in (%_pids%) do (
  echo   port %~1 -^> kill PID %%I
  taskkill /PID %%I /T /F >nul 2>&1
)
exit /b 0
