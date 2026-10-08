@echo off
REM ============================================================
REM  WebHTV 观影记录同步测试脚本 - EXE 一键打包 (Windows)
REM  产物: dist\test_sync.exe (单文件; 双击启动 GUI, cmd 加参数使用 CLI)
REM ============================================================
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo.
echo ============================================================
echo   WebHTV test_sync.py - 打包为 EXE
echo ============================================================
echo.

REM ---------- 1. 检查 Python ----------
where python >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 Python。请先安装 Python 3.8+ 并勾选 "Add to PATH"。
    echo 下载: https://www.python.org/downloads/
    pause
    exit /b 1
)
for /f "tokens=*" %%i in ('python --version 2^>^&1') do set PYVER=%%i
echo [1/4] Python 环境: %PYVER%

REM ---------- 2. 安装/更新 PyInstaller ----------
echo.
echo [2/4] 检查 PyInstaller ...
python -m pip show pyinstaller >nul 2>nul
if errorlevel 1 (
    echo 未检测到 PyInstaller，开始安装 ...
    python -m pip install --upgrade pip
    python -m pip install --upgrade pyinstaller
    if errorlevel 1 (
        echo [错误] PyInstaller 安装失败，请检查网络。
        pause
        exit /b 1
    )
) else (
    for /f "tokens=2" %%v in ('python -m pip show pyinstaller 2^>^&1 ^| findstr /r /c:"^Version:"') do set PIVER=%%v
    echo 已安装 PyInstaller %PIVER%
    REM 可选：保持最新版（注释下一行可跳过升级）
    python -m pip install --upgrade pyinstaller >nul 2>nul
)

REM ---------- 3. 清理旧产物 ----------
echo.
echo [3/4] 清理旧的构建目录 ...
if exist build rd /s /q build
if exist __pycache__ rd /s /q __pycache__
REM 保留 dist 但删除旧 exe，避免打包完成后误判
if exist dist\test_sync.exe del /f /q dist\test_sync.exe >nul 2>nul

REM ---------- 4. 开始打包 ----------
echo.
echo [4/4] 开始打包 (onefile + console) ...
echo   - 脚本:   test_sync.py
echo   - 配置:   test_sync.spec
echo   - 产物:   dist\test_sync.exe
echo.
echo 打包过程预计需要 30~90 秒，请耐心等待 ...
echo.

python -m PyInstaller --noconfirm --clean test_sync.spec
set BUILD_RC=%ERRORLEVEL%

REM ---------- 5. 结果 ----------
echo.
if %BUILD_RC% EQU 0 (
    if exist dist\test_sync.exe (
        echo ============================================================
        echo   打包成功！
        echo ============================================================
        echo   EXE 路径: "%~dp0dist\test_sync.exe"
        for %%A in ("dist\test_sync.exe") do set SIZE=%%~zA
        set /a SIZE_MB=!SIZE! / 1048576
        echo   文件大小: !SIZE! 字节 ^(!SIZE_MB! MB^)
        echo.
        echo   使用方式：
        echo     1) 双击运行             -^> 启动 GUI
        echo     2) test_sync.exe --help  -^> 查看 CLI 参数
        echo     3) CLI 运行:
        echo        test_sync.exe --url https://xxx.workers.dev ^
        echo                      --token YOUR_TOKEN ^
        echo                      --config-key SHA256_OR_URL
        echo.
        echo   配置文件保存在 %%USERPROFILE%%\.webhtv-sync-test\config.json
        echo ============================================================
    ) else (
        echo [警告] 退出码为 0 但未找到 dist\test_sync.exe
        echo 请查看 build/ 目录下日志定位问题。
    )
) else (
    echo ============================================================
    echo   [错误] 打包失败 (退出码 %BUILD_RC%)
    echo ============================================================
    echo 常见原因:
    echo   1. 杀毒软件误拦截 PyInstaller 临时文件 -^> 关闭实时防护后重试
    echo   2. Python 不完整（缺失 Tkinter）-^> 重新安装完整 Python
    echo   3. 路径包含中文或特殊字符 -^> 将项目移动到纯英文路径再试
    echo.
)

echo.
pause
endlocal
exit /b %BUILD_RC%
