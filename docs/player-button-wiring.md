# player-button-wiring：硬解能力按钮上游核验 + 多线程按钮点击与默认可见性

任务 guard id：`player-button-wiring`（quick-fix）
分支：`dev4`；基线 HEAD：`5cba7b00b073ca1e14e16f91bc4f01c028e4ca76`
日期：2026-09-29 (+0800)

## 1. 上游核验：播放器「硬解能力」按钮是否已被移除

核验对象：`https://github.com/webhtv/webhtv`（remote 名 `webhtv`，`main` = `d187f6ae8bfaf0a4720724281d0a91186c57f73d`）

**结论：上游没有移除硬解能力按钮及其功能。**

证据（`git grep` against `webhtv/main`，均为存在性证据）：

| 位置 | 证据 |
| --- | --- |
| `app/src/main/res/values-zh-rCN/strings.xml:66` | `codec_capability_short` = 「硬解能力」 |
| `app/src/leanback/res/layout/view_control_vod_action.xml:89` | `android:id="@+id/codecCapability"` |
| `app/src/leanback/java/.../VideoActivity.java:704` | `codecCapability.setOnClickListener(view -> onCodecCapability())`（可点击） |
| `app/src/leanback/java/.../VideoActivity.java:865` | `addActionButton(PlayerButtonSetting.CODEC_CAPABILITY, ...)` |
| `app/src/main/java/.../ui/dialog/CodecCapabilityDialog.java` | 文件仍存在，功能完整 |
| `app/src/mobile/res/layout/dialog_control.xml:109` | mobile 布局含 `codecCapability` |
| `app/src/mobile/java/.../ui/dialog/ControlDialog.java:174` | `binding.codecCapability.setOnClickListener(...)`（面板内可点击） |
| `app/src/main/java/.../setting/PlayerButtonSetting.java:24,50` | 仍保留 `CODEC_CAPABILITY` 常量与默认清单项 |

因此按目标第 1 条的第二种情形处理：**保留按钮，修复点击无效**。

补充：上游 `webhtv/main` **完全没有**多线程（`multi_thread_proxy` / `MultiThreadProxy`）相关代码。多线程本地代理是本仓库自研能力，不属于上游同步范围。

## 2. 本地缺陷与修复

### 2.1 mobile 底部控制栏「多线程」「硬解能力」点击无效

根因：`app/src/mobile/res/layout/view_control_vod_action.xml` 声明了
`multiThreadProxy` / `codecCapability`，`VideoActivity.setupActionButtons()` 也把它们登记进
`mActionButtons`（因此会参与排序与显隐），但 mobile 的 `initEvent()` **从未为这两个 id 绑定
`setOnClickListener`**。leanback 侧一直有绑定，mobile 缺失，故点击无任何反应。

修复（`app/src/mobile/java/.../VideoActivity.java`）：

```java
mBinding.control.action.multiThreadProxy.setOnClickListener(guarded(this::onMultiThreadProxy));
mBinding.control.action.codecCapability.setOnClickListener(guarded(this::onCodecCapabilityPanel));
```

复用既有实现：`onMultiThreadProxy()`（multi-thread 对话框 + 保存后重载）与
`onCodecCapabilityPanel()`（`CodecCapabilityDialog`，已是 `ControlDialog.Listener` 的 public
override），未新增重复方法。`guarded(...)` 保证服务未就绪时不误触。

同时确认 mobile 其余入口本就正常：`ControlDialog` 面板内的 `codecCapability`、
TmdbDetail 融合详情页的 `playerMultiThreadProxy` / `playerCodecCapability` 与
`detailActionView(R.id.multiThreadProxy)/(R.id.codecCapability)` 均已绑定监听器。

### 2.2 「播放设置-播放器按钮-多线程」默认不显示

原实现只有「显式隐藏」语义（`getHidden()` 为空即全部可见），默认清单项一律 `visible=true`，
没有默认隐藏机制。

修复（`app/src/main/java/.../setting/PlayerButtonSetting.java`）：

- 默认清单把多线程标记为不显示：`new Item(MULTI_THREAD_PROXY, R.string.multi_thread_proxy_button, false)`。
- 新增一次性播种标记 `HIDDEN_SEEDED`（`player_button_hidden_seeded`）：首次运行 `getHidden()`
  把 DEFAULT 中 `visible()==false` 的 id 写入 `player_button_hidden`，此后完全以用户选择为准。
- `reset()` 一并清除 `HIDDEN_SEEDED`，使「重置」回到同一默认态。

效果：新装/重置后多线程按钮在播放器按钮配置里显示为「不显示」，且仍列在清单中，用户可自行打开；
老用户既有的其他按钮显隐/排序不被覆盖。

## 3. 验证

| 验证 | 结果 |
| --- | --- |
| `MultiThreadProxyPlayerUiSourceTest`（mobile 口径，含新增回归用例） | `tests=5 failures=0 errors=0`，通过 |
| 新增回归用例 `multiThreadButtonIsHiddenByDefaultButStillUserConfigurable` | 通过 |
| `:app:compileMobileArm64_v8aDebugJavaWithJavac` + `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` | BUILD SUCCESSFUL |
| mobile 定向集合（proxy / PlayerControlFocus / PlayerPlaybackRegression / VideoActivityLayout / TmdbDetailActivityLayout / SettingPlaybackDefaults） | 413 tests，1 failed（预存在，见下） |
| `:app:testLeanbackArm64_v8aDebugUnitTest` 全量 | 4045 tests，10 failed（预存在，见下） |

新增断言覆盖：按钮**必须真正绑定点击监听器**（仅 `addActionButton` 登记不足以可点），
以及多线程默认不显示 + 一次性播种 + 重置清除标记 + 用户仍可打开。

## 4. 预存在失败（不在本任务范围，未修复）

本机 Windows 工作树 `core.autocrlf=true`，受版本管理的 Java 源文件在工作树为 CRLF
（`git ls-files --eol` → `i/lf w/crlf`）。部分源码文本测试断言使用仅含 `\n` 的**多行**字面量，
在 CRLF 工作树上必然不匹配。

已用「基线 HEAD 干净工作树」复现证明其与本次改动无关（stash 本任务 3 个文件后重跑）：

- 10 个 leanback 失败在基线**全部复现**：`DialogRoundedCornerSourceTest`、
  `LiveActivitySourceFallbackSourceTest`(×2)、`FollowingUiSourceTest`(×2)、
  `ReaderPlaybackRoutingSourceTest`(×2)、`SearchResultDownFocusTest`、
  `TmdbSourceDialogInflationContractTest`、`TmdbSourceOnlyInteractionTest`。
- mobile 侧 `PlayerPlaybackRegressionSourceTest.livePlaybackAlwaysAutoplaysWhileVodUsesTheConfiguredPolicy`
  同样只读 `PlaybackActivity.java` / `LiveActivity.java`（本任务未改动，`git diff` 为空），
  其多行 LF 断言在 CRLF 工作树上失败。

上述失败仅存在于本机 CRLF 检出口径（Linux/CI 的 LF 检出不受影响），与本任务改动无因果关系。

## 4b. 端侧（emulator-5556，「按钮点击是否真的打开对话框」）——未取证，失败原因已定位

结论：**本轮仍未取得「点击 → 对话框弹出」的端侧行为证据**。以下为实测到的三个硬性阻碍，
供后续会话直接复用，不必重跑这 20 余轮。

### 关键事实：坐标不是问题

横屏全屏底部动作行的真实坐标（`uiautomator` 实测，`min/tap` 均按此注入）：

| 按钮 | bounds | center |
| --- | --- | --- |
| 多线程 `multiThreadProxy` | `[581,902][713,972]` | **(647,937)** |
| 硬解能力 `codecCapability` | `[729,902][889,972]` | **(809,937)** |
| 播放参数 `playParams` | `[405,902][565,972]` | (485,937) |
| 循环 `repeat` | `[1832,902][1872,972]` | (1852,937) |

之前失败并非点不到：注入 809,937 时按钮会显式获得焦点框（截图已证），tap 确实落在按钮上。

### 阻碍 1：`guarded()` 需要「服务就绪」，媒体一结束就静默吞点击

`PlaybackActivity.guarded()` = `if (isServiceReady()) action.run()`，`isServiceReady()`
要求 `mService.player() != null && !isReleased()`。本地测试媒体播放结束后服务即分离，
此时点击**必然**无任何反应（含焦点框），这不是接线缺陷。所有失败截图（`M_cc.png`、`N_cc.png`、
`G1.png` 等）的共同特征是：动作行可见、被点按钮出现焦点框，但进度显示为 `X / X`（已到末尾）、
`0 KB/s`、中央显示暂停播放键。

### 阻碍 2：`uiautomator dump` 在视频渲染期整段不可用

播放中 `uiautomator dump` 返回 `ERROR: could not get idle state.` 或
`ERROR: null root node returned by UiTestAutomationBridge.`，转储只有 46–49 字节。
暂停也无法稳定修复（原因未定，疑似解码器/覆盖层持续 hold 住 window）。
→ 不要把 dump 作为端侧时序链路的必要环节；本仓库已有的 `f_l1.xml`（本次采集）
已提供充分坐标，可作静态基准。

### 阻碍 3：5 秒自动隐藏 × adb 往返延迟

`Constant.INTERVAL_HIDE = 5s`。每次 `adb shell input tap` 往返 2–4 秒，任何
「先 dump 定位、再单独一轮点击」的序列都会被自动隐藏击穿；
而为了保条而补一次「显示」tap 会把已经显示的条**切掉**（`onSingleTap()` 是 toggle）——
本次因此连丢失 4 轮。必须把「显示 + 点击」放进**同一条** `adb shell` 里。

### 另两个干扰项

- `dumpsys window | grep -c 'Window #'` **不可作为对话框证据**：Toast/TopToast
  （如「已经是最后一集了！」）同样会增加窗口数，实测 19→21 的跳变是 Toast 而非对话框。
- 本地文件经 `VIEW` 进入的是 `VideoActivity`；web 站点（`AT推送` 等）经
  `shouldOpenLegacyTmdbDetail()` 进入 `TmdbDetailActivity` 融合页，其内联动作行
  用的是另一套 id/坐标（`playerCodecCapability` center (929,405)、
  `playerMultiThreadProxy` center (767,405)），**但它已有独立的运行时证据**：
  早前会话记录了该页 8 个行内按钮 `clickable=true` 的 dump。

### 已排除的风险

`Control` style 设置了 `android:clickable=true`，因此 dump 里的
`clickable=true` **不能**证明我们绑定了 listener。但这不构成反证：
Grep 全文确认「除注册 `OnClickListener` 外，没有任何路径会让这两个按钮可点击」，
且注入 tap 时出现的「焦点 + 按压态」在无 listener 的视图上同样会出现。
即：无 listener 时不会更差，有 listener 时行为见下。

### 单元层面的替代证据（已取证，已随提交归档）

`MultiThreadProxyPlayerUiSourceTest`（mobile 口径）**5/5 通过**，其中
`multiThreadButtonIsHiddenByDefaultButStillUserConfigurable` 与既有的
「按钮必须真正绑定 `setOnClickListener`」（仅 `addActionButton` 登记不足以可点）
断言直接覆盖本任务的接线与默认可见性契约；两个 flavor 的 Java 编译均 BUILD SUCCESSFUL。

### 建议的下一条最短路径（未执行）

用一条原子 `adb shell`，在「同一帧内」完成：显示条 → 点击 `809,937` → 立即
`screencap`；但必须先把媒体换成**足够长且可循环**的源，或在点击前先点 `循环` (1852,937)；
且不要在链路中插入 `uiautomator`。若仍不能取证，接受上述单元证据并显式标注端侧未取证。

## 5. 回滚

单点回滚：把 §2.1 的两行监听器绑定、§2.2 的 `HIDDEN_SEEDED` 与 `, false` 标记撤销即可；
如需清理已播种的首选值，重置播放器按钮（清除 `player_button_hidden` 与
`player_button_hidden_seeded`）。
