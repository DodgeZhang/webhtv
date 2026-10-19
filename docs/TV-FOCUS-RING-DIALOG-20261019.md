# TV-FOCUS-RING-DIALOG-20261019：版本更新弹窗的主按钮没有焦点环，关于弹窗的齿轮图标在两种主题下都看不见

## Recovery anchor

- 目标：用户实拍两张照片圈出的控件，在 TV 深色模式下都能明确看出是否选中 —— ① 版本更新弹窗的 `stableConfirm`/`betaConfirm`/`cancel` 补上统一焦点环；② 关于弹窗右下角齿轮图标从未聚焦的 1.05:1 提升到面板上的可读前景色。
- 允许路径：`app/src/leanback/res/layout/dialog_update.xml`、`app/src/mobile/res/layout/dialog_update.xml`、`app/src/main/res/layout/dialog_about.xml`、`app/src/main/res/color/about_primary_icon_tint.xml`、`app/src/testLeanback/java/com/fongmi/android/tv/ui/activity/TvFocusRingContractTest.java`、本文件。
- 保护面：任务开始时工作树仅 `.design-evidence/`（未跟踪，预存脏路径）；分支 `dev4`，HEAD `cef7ba60432b4c0a9415f4a31f77ad8df6fc8474`。
- 验收：① 版本更新弹窗三个主按钮都带 `@color/focus_ring_primary` + `@dimen/webhtv_focus_ring_width`；② 齿轮未聚焦态与面板 ≥3:1、聚焦态与主题色填充 ≥3:1；③ 契约测试新增 3 项并全绿（22/22）；④ 5561 真机对照：齿轮 1.05:1 → 7.93:1，`cancel` 焦点环 `#062E6F` 3px 与关于弹窗一致。
- 回滚：撤销本任务原子提交即可；无数据迁移。
- 下一步唯一动作：无。已知遗留（未获授权，未改动）：其余消费 `dialog_primary_button_bg` 但无环的弹窗，见 §6。

## 1. 现象与根因

用户提供两张 TV 深色模式实拍照片，圈出"几乎无法判断是否选中"的按钮。诊断后是两个**互不相同**的缺陷：

| 照片 | 圈出控件 | 实测 | 结论 |
|---|---|---|---|
| 2（版本更新） | `更新测试版` | `dialog_update.xml` 两个 flavor **完全没有 `strokeColor`** | 焦点**完全不可见** |
| 1（关于） | 右下角齿轮 | 未聚焦图标 `?attr/colorOnPrimary` 画在 `#2A2F34` 面板上 = **1.05:1**（浅色 **1.22:1**） | 两种主题下图标**都看不见** |
| 1（关于） | `检查更新`/`加速源`/`我已知悉` | 环存在，`#062E6F` on `#A8C7FA` = **7.50:1** | 已正确，无需改动 |

### 1.1 版本更新弹窗：填充状态表在聚焦前后是同一个颜色

`dialog_primary_button_bg.xml` 的 `state_focused` 与默认项都解析到 `webhtv_color_primary`，而 `colorFocus == colorPrimary`（`docs/TV-FOCUS-RING-20261004-unified-theme-ring.md` §2.1 已记录同一根因）。所以**边框环是唯一的焦点线索**，而这三个按钮恰恰没有环 —— 2026-10-04 的统一环任务没有覆盖到 `dialog_update.xml`（该文件自 `0af2340d495` 起未被触碰）。

### 1.2 齿轮图标：着色与它实际所处的背景不配对

`about_primary_icon_button.xml` 在未聚焦时填充全透明，图标直接贴在 `colorSurfaceContainerHigh` 面板上；而 `dialog_about.xml` 硬写 `app:tint="?attr/colorOnPrimary"`。on-primary 是"画在主题色填充上"的配对色，夜间是深蓝 `#062E6F`、浅色是纯白 `#FFFFFF`，两者画在各自的面板上分别是 1.05:1 和 1.22:1 —— 等于隐形。

仓库里其实**已经存在**正确的状态表 `about_primary_icon_tint.xml`，但没有任何布局引用它（全仓库 grep 零命中），是死代码；而且它的分支也是反的（聚焦态取 `colorOnSurface`）。

## 2. 证据来源

| 来源 | 地址/修订 | 证据等级 | 结论与决策影响 |
|---|---|---|---|
| 用户实拍照片 1/2 | sha256 `04114aa3…`/`a1a2080e…`，2560x1440 | A（用户提供） | 确认两个缺陷的现场表现 |
| 5561 模拟器实拍 | `about1.png`、`gear-focused.png`、`v-*.png` | A（本地设备） | 像素级前后对照 |
| `app/src/leanback|mobile/res/layout/dialog_update.xml` | HEAD `cef7ba60432` | A（本地） | 三个主按钮只有 `app:backgroundTint`，无 `strokeColor` |
| `app/src/main/res/color/dialog_primary_button_bg.xml` | HEAD `cef7ba60432` | A（本地） | focused 与 default 同色，环是唯一线索 |
| `app/src/main/res/color/about_primary_icon_tint.xml` | HEAD `cef7ba60432` | A（本地） | 正确状态表已存在但零引用；分支反了 |
| `docs/TV-FOCUS-RING-20261004-unified-theme-ring.md` | HEAD `cef7ba60432` | A（本地） | 同一根因的既有记录；§5.2 规定宽度 1.5dp 不得再加粗 |
| WCAG 2.2 SC 1.4.11 / SC 2.4.13 | 沿用仓库既有记录（访问日期 2026-10-04，本次未重新抓取） | A（间接） | 非文本对比度 ≥3:1，焦点外观 ≥3:1 |

不适用类别记录：不涉及解码/渲染/ABI/打包/网络，无需上游播放器依赖类证据。

## 3. 方案比较与采用

| 方案 | 优点 | 缺点/风险 | 结论 |
|---|---|---|---|
| 1. 不变更 | 零风险 | 用户报告的缺陷继续存在 | 拒绝 |
| 2. 加粗焦点环到 3dp 提升对比 | 直观 | **违反 §5.2**：用户已明确否决 3dp（"太粗"） | 拒绝 |
| 3. 改 `dialog_primary_button_bg` 的聚焦填充色 | 一处改动覆盖全仓库 | 会改变**已验收**的关于弹窗外观；且填充色变化属于另一套机制，与"边框环是唯一机制"的既有契约冲突 | 拒绝 |
| 4. **补环 + 修正齿轮着色配对**（采用） | 与已验收的关于弹窗完全同一套规范；每个分支都与它实际所处的背景配对，换主题不失效 | 需要同时改两个 flavor | **采用** |

## 4. 实施

1. `app/src/leanback/res/layout/dialog_update.xml`：`stableConfirm`、`betaConfirm`、`cancel` 各补 `app:strokeColor="@color/focus_ring_primary"` + `app:strokeWidth="@dimen/webhtv_focus_ring_width"`。
2. `app/src/mobile/res/layout/dialog_update.xml`：同上（移动版同缺陷）。
3. `app/src/main/res/color/about_primary_icon_tint.xml`：改为正确配对 —— 聚焦/按下取 `?attr/colorOnPrimary`（此时背景已被换成 `colorPrimary`），常态取 `?attr/colorOnSurfaceVariant`（背景透明，图标落在面板上）。
4. `app/src/main/res/layout/dialog_about.xml`：齿轮 `app:tint` 由硬写的 `?attr/colorOnPrimary` 改为引用该状态表，激活这份死代码。
5. `TvFocusRingContractTest` 新增 3 项：`updateDialogPrimaryButtonsShowABorderRingWhenFocused`、`migratedDialogsRingEveryPrimaryButton`、`aboutGearIconTintPairsWithTheBackgroundItActuallySitsOn`（含两个分支各自的 ≥3:1 对比度回归）。

## 5. 不变量与回滚

- 不改动 `webhtv_focus_ring_width` 的取值（1.5dp），遵守 `TV-FOCUS-RING-20261004` §5.2。
- 不改动 `dialog_primary_button_bg` / `focus_ring_primary` / 主题 token 的任何取值。
- 不改动关于弹窗三个按钮（已验收正确）与 `selector_update_version_item`（版本行已有 2dp 环）。
- 不改动播放器/视频层焦点环族。
- 回滚：撤销本任务原子提交即可；无数据迁移。

## 6. 已知遗留（未获授权，本任务未改动）

同一缺陷族：**消费 `dialog_primary_button_bg` 但没有 `strokeColor`** 的其它弹窗。已静态清点（`Select-String` 全量扫描 `app/src/*/res/layout/*.xml`），约 48 个按钮站点，分布在：

`dialog_ad_block_stats.xml`(leanback/mobile)、`dialog_ad_rule_preview.xml`、`dialog_ad_rule_edit.xml`、`dialog_apk_push_progress.xml`、`dialog_site_health_report.xml`、`dialog_site_health.xml`、`dialog_config.xml`、`dialog_custom_csp.xml`、`dialog_login_state_*.xml`、`dialog_playback_remote_sync.xml`、`dialog_playback_webhook.xml`、`dialog_remote_trust_*.xml`、`dialog_shell_proxy.xml`、`dialog_sync_path.xml`、`dialog_ua.xml`、`dialog_viewing_record_sync.xml`、`dialog_web_home_extension*.xml`、`dialog_mpv_config_*.xml`、`dialog_ai_recommendation_info.xml`、`dialog_manage_page*.xml`、`dialog_one_key_sync.xml`、`activity_crash.xml`、`activity_following.xml` 等。

这些站点在 TV 上同样"聚焦前后填充同色、无环"，属于同一个可见性缺陷。建议作为独立的后续任务统一处理（一次机械补齐，或抽成 `Widget.WebHTV.Button.Filled` 的 `strokeColor` 默认值）。本任务按 `AGENTS.md` §2「范围默认封闭」未扩大改动面。

## 7. 验证记录

### 7.1 定向 JVM 测试

`.\gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests "com.fongmi.android.tv.ui.activity.TvFocusRingContractTest" --console=plain --no-build-cache` → **BUILD SUCCESSFUL in 6m 38s**。

报告 `app/build/test-results/testLeanbackArm64_v8aDebugUnitTest/TEST-com.fongmi.android.tv.ui.activity.TvFocusRingContractTest.xml`：`tests=22 failures=0 errors=0 skipped=0`，含新增的 `updateDialogPrimaryButtonsShowABorderRingWhenFocused`、`migratedDialogsRingEveryPrimaryButton`、`aboutGearIconTintPairsWithTheBackgroundItActuallySitsOn` 三项全 PASS。

### 7.2 构建

`.\gradlew.bat :app:assembleLeanbackArm64_v8aDebug --console=plain --no-build-cache` → **BUILD SUCCESSFUL in 26s**。

### 7.3 5561 真机对照（TV 深色模式，`Night mode: yes`）

`adb install -r -d` → `Success`；应用内版本戳 `5.6.0-202610191321 · leanback/arm64_v8a`。

| 控件 | 修复前 | 修复后 | 判定 |
|---|---|---|---|
| 齿轮图标（未聚焦） | `#062E6F` on `#2A2F34` = **1.05:1** | `#C4C6D0` on `#2A2F34` = **7.93:1** | 达标 |
| 齿轮图标（聚焦） | — | `#A8C7FA` 填充 + `#062E6F` 环 | 达标 |
| `cancel`（版本更新，聚焦） | 无环，填充 `#98B8EC` | 焦点环 `#062E6F` **3px**（y=755-757） | 达标 |
| `stableConfirm`/`betaConfirm` | 无环 | 与 `cancel` 同一组属性（静态测试锁定） | 达标 |

`cancel` 的 3px `#062E6F` 环与关于弹窗 `confirm` 的实测完全一致（同为 1.5dp × 480dpi ≈ 3px），确认两个弹窗现在走的是同一套规范。

**环境限制**：本会话仍无可用的 `bash`（仅 WSL，`HCS_E_SERVICE_NOT_AVAILABLE`），`scripts/check_ui_tokens.sh` 与 `.codex/scripts/task_guard.sh` 无法执行，按同等意图手工暂存/提交/打 tag。本次未新增裸十六进制。
