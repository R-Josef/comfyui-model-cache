@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "JAR="
if exist "target\*.jar" for %%f in ("target\*.jar") do set "JAR=%%~ff"
if not defined JAR if exist "*.jar" for %%f in ("*.jar") do set "JAR=%%~ff"

if not defined JAR (
    echo.
    echo 未找到 jar 文件，请先在项目目录里执行： mvn package
    echo.
    pause
    exit /b 1
)

java -jar "%JAR%" %*
echo.
echo 程序已退出。
pause
