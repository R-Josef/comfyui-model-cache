@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

call "%~dp0_findjava.bat"
if errorlevel 1 goto :fail

rem 优先用 target 目录里的 jar（Maven 构建产物），其次用本目录的 jar（Release 包）
set "JAR="
for %%f in ("%~dp0target\*.jar") do set "JAR=%%~ff"
if not defined JAR for %%f in ("%~dp0*.jar") do set "JAR=%%~ff"
if not defined JAR (
    echo.
    echo 找不到 jar 文件。
    echo 请把 ComfyUI模型缓存.jar 放在本文件夹，或者先在源码目录执行 mvn package。
    goto :fail
)

start "" "%JAVAW_EXE%" -jar "%JAR%" %*
exit /b 0

:fail
echo.
pause
exit /b 1
