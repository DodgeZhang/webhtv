# C58：dev4 合并远端 beta 最新代码并复评已修改代码（含已提交未推送）

## Recovery anchor

- **目标**：把远端 `beta` 最新代码安全合入 `dev4`，不得复活 beta 已移除/回退内容；评审合并结果、本地已提交未推送的屏显对比度改动及本轮修复，验证通过后提交、推送并创建中文 `dev4 -> beta` PR，**只创建不合并**。
- **允许路径**：`app/`、`docs/`；task guard 启动时工作区干净，0 个预先存在脏路径。
- **分支与回滚锚点**：`dev4`；合并前 HEAD `fec5af7aa54ff36df45ce919151a4d7fc9b74f9c`。
- **最终 beta 权威 tip**：`fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5`；最终 fetch 前后未变化。
- **当前状态**：合并无冲突；已完成三轮代码评审，发现的 Custom CSP 动态主题问题已修复并通过定向编译/测试；Gradle daemon、构建输出和本轮仓库临时日志已清理；待 task guard finish 创建合并提交和 recovery tag。
- **下一动作**：执行 task guard finish，随后推送 `dev4`/recovery tag 并创建保持 OPEN 的中文 beta PR。

## 1. 合并台账

| 项 | 值 |
| --- | --- |
| 本地分支 | `dev4` |
| 合并前 HEAD | `fec5af7aa54ff36df45ce919151a4d7fc9b74f9c` |
| 合并前旧 `origin/beta` | `819003db8fef2ea63420e9a72eb4fcac8ee468ba5` |
| 最终 `origin/beta` | `fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5` |
| MERGE_HEAD | `fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5` |
| 自动合并树 / 索引树（修复前） | `b52c5aedfbb06824fa36f8d920a59e646e170c30`，两者一致 |
| 冲突 | 0 |
| task guard | `beta-sync-review-dev4-20261011`，scope=`app docs` |

### beta 增量 disposition

以下提交均通过 merge 第二父纳入，不执行 cherry-pick，不把已回退内容单独带回：

| 完整 commit ID | 处置 |
| --- | --- |
| `34a93e243f660df19b2b23eef724f0f882002e8b` | 纳入 beta 合并历史，随第二父进入 |
| `df7f7ff5808d1e555abca8a5aa31104624650ffd` | 纳入 beta 合并历史，随第二父进入 |
| `b6b8e7a375276975cc160ceeb6257cf058298ebb` | 纳入 beta 合并历史，随第二父进入 |
| `839809ee1e0f56f2ea2b8537ddc11949b280e060` | 纳入 beta 合并历史，随第二父进入 |
| `f2ccce36c9aba490f40fb3800638a14749dd0090` | 纳入 beta 合并历史，随第二父进入 |
| `d7b9439201cef70c9229b98c7356e0a7397103eb` | 纳入 beta 合并历史，随第二父进入 |
| `7809ff4952788142008d0953697bfe29ded062b3` | 纳入 beta 合并历史，随第二父进入 |
| `4319403502a64ad8064781f4e88f21391e6abc77` | 纳入 beta 合并历史，随第二父进入 |
| `fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5` | 最新 beta tip，作为最终合并提交第二父 |

## 2. 复活与净差异审计

- 当前合并索引树与 `git merge-tree --write-tree fec5af7aa54ff36df45ce919151a4d7fc9b74f9c fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5` 一致。
- 合并结果树比 beta 树多出的 8 个路径全部是本地屏显对比度任务文件：
  - `app/src/main/java/com/fongmi/android/tv/theme/PlayerSettingsRowTheme.java`
  - `app/src/main/res/color/player_settings_icon_tint.xml`
  - `app/src/main/res/drawable/selector_dialog_surface_row.xml`
  - `app/src/main/res/drawable/selector_player_settings_row.xml`
  - `app/src/main/res/drawable/selector_player_settings_switch.xml`
  - `app/src/test/java/com/fongmi/android/tv/ui/dialog/PlayerSettingsContrastTest.java`
  - `docs/TV-DISPLAY-DIALOG-CONTRAST-20261011.md`
  - `docs/TV-PLAYER-SETTINGS-CONTRAST-20261010.md`
- 当前净差异只在 `app/` 与 `docs/`，没有 native、依赖锁、Release 正式包或仓库外路径。
- beta 中原有的 `ABANDONED-REQUEST-LOADING-RELEASE-20261009.md`、`C57-beta-merge-review-dev2-20261010.md`、`INLINE-LOADING-RELEASE-20261009.md`、`LOADING-SPINNER-OMISSION-SWEEP-20261009.md`、`OSD-REBUFFER-ENGINE-BRANCH-20261009.md`、`PLAYBACK-LOADING-GUARD-20261009.md` 均保留在合并树中；它们相对合并前 HEAD 的删除状态只是“beta 尚未合入”的净差异表现，不是最终合并树删除。
- 主题行锚定的远端回退/撤销扫描未发现需要重新恢复的文件；未使用不锚定提交正文的 grep，避免把本任务文档误判为 revert 路径。

## 3. 代码评审与修复

### 第 1 轮

评审对象：本地两个已提交未推送提交 `bd78d7b528fb61c4dc89f8eb0655085c878b4a87`、`fec5af7aa54ff36df45ce919151a4d7fc9b74f9c`，以及 beta 合入的加载态、OSD 诊断、电池控制栏改动。

结论：

- `VideoActivity.setPlayer(null)` 与 `!canApplyPlayerResult()` 均释放加载守卫；
- `TmdbDetailActivity` 的内联取址请求使用代际归属，旧回调不能清除新请求标记；
- `PlayerOsdController` 对非 Exo 引擎读取 `PlayerManager` 的重缓冲追踪器；
- beta mobile 电池块复用 `BatteryUtil`、`Formatters.TIME`，可见性不受 `PlayerButtonSetting` 误控；
- `testMobile/VideoActivityLayoutTest.java` 的 beta 与本地 hunk 不相交；
- 未发现第一轮必修缺陷。

### 第 2 轮：发现并修复

发现的问题：`CustomCspDialog` 中运行时创建的识别/排序输入、状态徽章、站点行背景、状态文字、危险按钮和图标仍使用固定 Google 浅色值；暗色或自定义主题下会与动态文本/对话框面板不匹配。

修复内容：

- 使用 `ThemeController.current()` / `ThemeTokens` 为输入文字、hint、输入框背景/描边、识别标题、标签、状态色、徽章和图标提供语义颜色；
- 焦点/按下/激活的站点行改用 `primaryContainer + readable(primary)`，常态使用 surface/errorContainer/outline 语义；
- 登录状态徽章 `shape_login_state_badge.xml` 改用 `?attr/colorPrimaryContainer`；
- `PlayerSettingsContrastTest` 增加 Custom CSP 与登录状态徽章的回归契约，并禁止固定浅色重新出现。

第 3 轮复评结论：

- Custom CSP 生产代码编译通过；
- 新增回归断言通过；
- 检查后仅剩 `Color.TRANSPARENT` 用于窗口透明背景，不属于主题面板颜色，不构成问题；
- 没有发现新的必修问题。

## 4. 验证证据

### 4.1 定向代码验证

- leanback `PlayerSettingsContrastTest`：5/5，0 failure，0 error；
- leanback `PlayerDisplaySettingSyncTest`：8/8，0 failure，0 error；
- leanback `TmdbDetailDirectPlayTransitionSourceTest`：4/4，0 failure，0 error；
- mobile `PlayerSettingsContrastTest`：5/5，0 failure，0 error；
- mobile `PlayerDisplaySettingSyncTest`：8/8，0 failure，0 error；
- mobile `TmdbDetailDirectPlayTransitionSourceTest`：4/4，0 failure，0 error；
- mobile `VideoActivityLayoutTest`：155/155，0 failure，0 error；
- Custom CSP 修复后的 leanback/mobile Java 编译：`BUILD SUCCESSFUL`；
- 双 flavor AndroidTest Java 编译：`BUILD SUCCESSFUL`；
- `git diff --check`、`git diff --cached --check`、task guard `check`：通过；无冲突标记。

### 4.2 全量测试环境失败分类

一次全量 leanback JVM 任务额外出现 2 个失败：

- `MpvFontConfigTest.writeIfChanged_replacesStaleConfigurationAndCleansTemporaryFile`
- `MpvHlsCacheCoordinatorTest.successfulCommitPublishesCompleteFileAndReleasesReservation`

两者均属于既有 Windows 文件/原子 rename 环境类失败，失败路径不触及本任务改动；相关源码、native、缓存实现不在本任务修改范围。定向受影响测试全部通过，mobile 定向任务整体 `BUILD SUCCESSFUL`。该全量环境失败不作为本任务回归。

### 4.3 资源回收

最终验证后执行并成功：

```text
gradlew.bat --stop
gradlew.bat --no-daemon clean
```

已停止 1 个 Gradle daemon，`clean` 成功；已删除本轮仓库临时 Gradle 日志；未生成正式包，不卸载或安装模拟器包。

## 5. 回滚与交付

- 回滚：对最终 merge commit 执行 `git revert -m 1 <merge-commit>`，恢复到合并前 `fec5af7aa54ff36df45ce919151a4d7fc9b74f9c`。
- 无数据库迁移、网络协议、ABI、native、Release 产物或用户偏好格式变化。
- 最终提交、recovery tag、推送结果和 PR 编号由本任务结束动作补入交付摘要；PR 必须保持 OPEN，不自动合并。
- **当前下一步**：task guard finish 创建合并提交与 recovery tag。
