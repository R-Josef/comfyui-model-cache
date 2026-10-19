@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

call "%~dp0_findjava.bat"
if errorlevel 1 goto :fail

set "JAR="
for %%f in ("%~dp0target\*.jar") do set "JAR=%%~ff"
if not defined JAR for %%f in ("%~dp0*.jar") do set "JAR=%%~ff"
if not defined JAR (
    echo.
    echo 找不到 jar 文件。
    echo 请把 ComfyUI模型缓存.jar 放在本文件夹，或者先在源码目录执行 mvn package。
    goto :fail
)

echo 使用 Java：%JAVA_EXE%   （来源：%JAVA_SOURCE%）
echo 启动文件：%JAR%
echo.
"%JAVA_EXE%" -jar "%JAR%" %*
echo.
echo 程序已退出。
pause
exit /b 0

:fail
echo.
pause
exit /b 1
