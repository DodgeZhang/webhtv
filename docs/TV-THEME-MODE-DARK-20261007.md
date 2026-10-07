# TV-THEME-MODE-DARK-20261007：显式深色模式解析修复

## 根因

设备偏好中同时存在 `theme_mode=1`（显式深色）和历史主题 profile `mode=light`，系统夜间模式为 `no`。`ThemeController.resolveWith()` 原来固定使用 `ThemeMode.SYSTEM`，所以 `ThemeResolver` 按系统浅色解析；设置行显示“深色”，实际页面却是浅色。

## 修复

- `ThemeController.resolveWith()` 使用 `currentThemeMode()`，恢复显式模式优先级：显式浅色/深色覆盖系统，未设置时跟随系统。
- `ThemeController.refresh()` 同步使用显式模式，避免运行时刷新又回退到系统模式。
- 保留 `frozenPalette()`/`resolvedDark()` 的编译资源基线逻辑，不改变 ThemeBinder 的基线匹配契约。
- 回归测试在保存深色、保存浅色后调用 `ThemeController.applyFromPreferences(null)`，分别断言 `ThemeController.current()` 的 surface 和 primary 与对应 canonical palette 一致。

## 当前验证

- `:app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.theme.ThemeDialogModeTest --tests com.fongmi.android.tv.theme.ThemeControllerContractTest`：通过。
- 设备权威状态复现：`theme_mode=1`、系统 `Night mode: no`、历史 profile `mode=light`；该组合证明旧实现会显示浅色。
- 下一步：将该修复与站点筛选/选择页面主题审计合并验证，分别在显式浅色和显式深色下冷启动检查“外观与语言”和 SiteDialog。

## 回滚

回退本任务提交即可；不改变主题 profile schema、播放器路径或用户偏好格式。
