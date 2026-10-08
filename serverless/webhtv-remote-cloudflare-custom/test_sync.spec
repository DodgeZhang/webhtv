# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller 打包配置 - WebHTV 观影记录同步测试脚本
生成命令：pyinstaller test_sync.spec
产物：dist/test_sync.exe (单文件，保留控制台以便 CLI 模式使用)
"""

block_cipher = None

a = Analysis(
    ['test_sync.py'],
    pathex=[],
    binaries=[],
    datas=[],
    hiddenimports=[
        # Tkinter 相关（Windows 自带 Python 会自动包含，这里显式声明避免丢失）
        'tkinter',
        'tkinter.ttk',
        'tkinter.scrolledtext',
        'tkinter.filedialog',
        'tkinter.messagebox',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        # 排除不需要的大体积模块以减小 exe 体积
        'numpy', 'pandas', 'matplotlib', 'scipy', 'PIL',
        'pytest', 'unittest', 'IPython', 'notebook',
        'PyQt5', 'PyQt6', 'PySide2', 'PySide6', 'wx',
    ],
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='test_sync',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    # 必须保留控制台窗口：
    #   - CLI 模式通过 stdout 输出日志和诊断报告
    #   - 无参数/--gui 模式时同时启动 Tk 窗口，控制台可用于调试
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    # 未提供图标文件时不设置 icon，避免打包失败
    # icon='app.ico',
)
