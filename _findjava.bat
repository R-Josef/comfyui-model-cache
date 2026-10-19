@echo off
rem ============================================================
rem  由 run.bat / run_console.bat 调用，不单独使用。
rem  成功时设置：JAVA_EXE / JAVAW_EXE / JAVA_SOURCE
rem  注意：这里故意不写 setlocal，变量要留给调用方。
rem ============================================================

set "JAVA_EXE="
set "JAVAW_EXE="
set "JAVA_SOURCE="
set "SYS_JAVA="
set "JAVA_OK="

rem ---------- 1) 优先使用本文件夹里已经解压好的 Java ----------
if exist "%~dp0jdk-21\bin\java.exe" (
    call :check_version "%~dp0jdk-21\bin\java.exe"
    if defined JAVA_OK goto :use_local
)

rem ---------- 2) 其次使用系统 PATH 里的 java ----------
for /f "delims=" %%p in ('where.exe java 2^>nul') do if not defined SYS_JAVA set "SYS_JAVA=%%p"
if not defined SYS_JAVA goto :need_download
call :check_version "%SYS_JAVA%"
if not defined JAVA_OK goto :need_download

set "JAVA_EXE=%SYS_JAVA%"
for %%q in ("%SYS_JAVA%") do if exist "%%~dpqjavaw.exe" set "JAVAW_EXE=%%~dpqjavaw.exe"
set "JAVA_SOURCE=系统 PATH"
goto :done

:use_local
set "JAVA_EXE=%~dp0jdk-21\bin\java.exe"
if exist "%~dp0jdk-21\bin\javaw.exe" set "JAVAW_EXE=%~dp0jdk-21\bin\javaw.exe"
set "JAVA_SOURCE=本文件夹的 jdk-21"
goto :done

rem ---------- 3) 都没有：自动下载 ----------
:need_download
echo.
echo 没有检测到 Java 21 或更高版本。
call "%~dp0setup-java.bat"
if errorlevel 1 goto :fail
if not exist "%~dp0jdk-21\bin\java.exe" goto :fail
call :check_version "%~dp0jdk-21\bin\java.exe"
if not defined JAVA_OK goto :fail
set "JAVA_EXE=%~dp0jdk-21\bin\java.exe"
if exist "%~dp0jdk-21\bin\javaw.exe" set "JAVAW_EXE=%~dp0jdk-21\bin\javaw.exe"
set "JAVA_SOURCE=刚下载的 jdk-21"

:done
if not defined JAVAW_EXE set "JAVAW_EXE=%JAVA_EXE%"
if not defined JAVA_EXE goto :fail
exit /b 0

:fail
echo.
echo 没有找到可用的 Java 21，无法启动。
exit /b 1

rem ------------------------------------------------------------
rem :check_version <java路径>
rem 读取 java -version 的主版本号，>= 21 时设置 JAVA_OK=1
rem ------------------------------------------------------------
:check_version
set "JAVA_OK="
set "VER_RAW="
set "VER_MAJOR="
set "VER_NUM=0"
rem 切到 java.exe 所在目录再以文件名调用，避免带空格的路径在 for /f 里被 cmd 拆坏
pushd "%~dp1" 2>nul
for /f "tokens=3" %%v in ('%~nx1 -version 2^>^&1 ^| findstr /i "version"') do if not defined VER_RAW set "VER_RAW=%%v"
popd
if not defined VER_RAW goto :eof
set "VER_RAW=%VER_RAW:"=%"
for /f "tokens=1 delims=." %%a in ("%VER_RAW%") do set "VER_MAJOR=%%a"
if "%VER_MAJOR%"=="1" for /f "tokens=2 delims=." %%b in ("%VER_RAW%") do set "VER_MAJOR=%%b"
if not defined VER_MAJOR goto :eof
set /a "VER_NUM=%VER_MAJOR%" >nul 2>&1
if %VER_NUM% GEQ 21 set "JAVA_OK=1"
goto :eof
