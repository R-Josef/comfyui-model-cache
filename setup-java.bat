@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================
echo   ComfyUI 模型缓存 - 自动安装 Java 21
echo ============================================
echo.

if exist "%~dp0jdk-21\bin\java.exe" (
    echo 已经存在 jdk-21，无需重复下载。
    exit /b 0
)

set "ARCH=x64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=aarch64"

if not defined COMFYUI_JAVA_URL set "COMFYUI_JAVA_URL=https://api.adoptium.net/v3/binary/latest/21/ga/windows/%ARCH%/jre/hotspot/normal/eclipse"

echo 正在下载 Java 21 运行环境（约 50 MB）...
echo 地址：%COMFYUI_JAVA_URL%
echo 解压到：%~dp0jdk-21
echo.

set "ZIP=%~dp0.jdk21-download.zip"
set "TMP=%~dp0.jdk21-temp"
if exist "%ZIP%" del /q "%ZIP%"
if exist "%TMP%" rd /s /q "%TMP%"

curl.exe -L --fail --retry 3 --connect-timeout 20 --progress-bar -o "%ZIP%" "%COMFYUI_JAVA_URL%"
if errorlevel 1 (
    echo.
    echo curl 下载失败，改用 PowerShell 重试...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri '%COMFYUI_JAVA_URL%' -OutFile '%ZIP%' } catch { Write-Host $_.Exception.Message; exit 1 }"
    if errorlevel 1 goto :fail
)

echo.
echo 正在解压...
mkdir "%TMP%" 2>nul
tar.exe -xf "%ZIP%" -C "%TMP%"
if errorlevel 1 (
    echo tar 解压失败，改用 PowerShell 解压...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%TMP%' -Force } catch { Write-Host $_.Exception.Message; exit 1 }"
    if errorlevel 1 goto :fail
)

set "EXTRACTED="
for /d %%d in ("%TMP%\jdk-*") do if not defined EXTRACTED set "EXTRACTED=%%d"
if not defined EXTRACTED for /d %%d in ("%TMP%\*") do if exist "%%d\bin\java.exe" set "EXTRACTED=%%d"
if not defined EXTRACTED goto :fail

move "%EXTRACTED%" "%~dp0jdk-21" >nul
if errorlevel 1 goto :fail

rd /s /q "%TMP%"
del /q "%ZIP%"

if not exist "%~dp0jdk-21\bin\java.exe" goto :fail
echo.
echo Java 21 已准备好：%~dp0jdk-21
exit /b 0

:fail
echo.
echo 自动安装 Java 失败，请检查网络后重试。
echo 也可以手动安装 Java 21，或把 JDK/JRE 解压到本文件夹并命名为 jdk-21。
if exist "%TMP%" rd /s /q "%TMP%"
if exist "%ZIP%" del /q "%ZIP%"
exit /b 1
