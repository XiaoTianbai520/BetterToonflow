@echo off
setlocal EnableExtensions
chcp 65001 >nul
title ToonFlow Dev Launcher

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "ENV_FILE=%ROOT%\.env.local"
set "PG_BIN=%ROOT%\.tmp\localDevelopment\package\native\bin"
set "PG_DATA=%ROOT%\data\localDevelopment\postgres"

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
echo %TOONFLOW_DATA_DIR% | findstr /R "^[A-Za-z]:[/\\]" >nul || set "TOONFLOW_DATA_DIR=%ROOT%\data\localDevelopment"

rem 工具/IDE 会注入这两个变量，Spring relaxed binding 会把它们当成 server.port / server.address
set "SERVER__PORT="
set "SERVER__HOST="

rem 清掉宿主注入的 Node 钩子：safe-delete 会拦截 vite 的依赖优化目录，导致 dev server 直接退出
set "NODE_OPTIONS="
set "ELECTRON_RUN_AS_NODE="

rem 本地 ComfyUI 必须直连：一旦进程带着 HTTP_PROXY，127.0.0.1:8188 会走代理并返回 502
set "HTTP_PROXY="
set "HTTPS_PROXY="
set "ALL_PROXY="
set "http_proxy="
set "https_proxy="
set "all_proxy="
set "NO_PROXY=localhost,127.0.0.1,::1"
set "no_proxy=localhost,127.0.0.1,::1"

if not defined TOONFLOW_INTERNAL_TOKEN (
  echo [x] Missing TOONFLOW_INTERNAL_TOKEN in .env.local
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
echo  bun      : %BUN_EXE%
echo.

rem ---------- 1. PostgreSQL :54329 ----------
call :port 54329
if not errorlevel 1 (
  echo [=] PostgreSQL already running
) else (
  echo [^>] starting PostgreSQL ...
  start "ToonFlow DB :54329" /D "%ROOT%" cmd /k ""%PG_BIN%\postgres.exe" -D "%PG_DATA%" -p 54329"
  call :wait 54329 30
  if errorlevel 1 (
    echo [x] PostgreSQL not ready in 30s
    pause
    exit /b 1
  )
  echo [ok] PostgreSQL ready
)

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

:stop
echo Stopping ToonFlow dev services ...
for %%P in (3000 3001 5173) do call :killport %%P
if exist "%PG_BIN%\pg_ctl.exe" (
  "%PG_BIN%\pg_ctl.exe" -D "%PG_DATA%" -m fast stop >nul 2>&1
  echo   PostgreSQL -^> stopped
)
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
