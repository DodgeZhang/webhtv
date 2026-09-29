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

## 5. 回滚

单点回滚：把 §2.1 的两行监听器绑定、§2.2 的 `HIDDEN_SEEDED` 与 `, false` 标记撤销即可；
如需清理已播种的首选值，重置播放器按钮（清除 `player_button_hidden` 与
`player_button_hidden_seeded`）。
