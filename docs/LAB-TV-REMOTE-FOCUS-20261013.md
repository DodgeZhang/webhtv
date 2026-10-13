# LAB-TV-REMOTE-FOCUS-20261013 电视版实验室遥控焦点不可操作

## Recovery anchor

- 目标：电视版（leanback）实验室页面必须能用遥控器操作焦点——命令列表焦点不被 2 秒定时刷新抢走，且实验室自绘的可聚焦控件在聚焦时有可见高亮。
- 允许路径：`app/src/main/java/com/fongmi/android/tv/lab/LabDetailActivity.java`、`app/src/main/java/com/fongmi/android/tv/lab/LabCommandAdapter.java`、`app/src/main/res/drawable/shape_lab_card.xml`、`shape_lab_run_btn.xml`、`shape_lab_stop_btn.xml`、`shape_lab_click_btn.xml`、`selector_lab_group_tab.xml`、`shape_lab_group_tab_focused.xml`（新增）、`selector_lab_icon_btn.xml`（新增）、`app/src/main/res/layout/activity_lab_detail.xml`、`app/src/test/java/com/fongmi/android/tv/lab/LabTvFocusSourceTest.java`（新增）、`docs/LAB-TV-REMOTE-FOCUS-20261013.md`（本文）。
- 验收标准：进入 实验室 → 任意包详情 → 命令列表，用遥控方向键把焦点移到命令行的「运行」按钮后静置 ≥ 5 秒，焦点仍在命令行/运行按钮上（修复前 ≤ 2 秒即跳回工具栏）；命令行、运行/停止按钮、分组标签、详情页三个图标按钮在聚焦时画面出现可见变化（像素级 A/B 非零差异）。
- 当前状态：**已实施、已验证、已交付**（会话 1：列表差异刷新 + 聚焦视觉 + 图标焦点连线；会话 2：命令弹窗初始焦点锚点）。
- 下一步唯一动作：无，见 §6（含残余未知记录）。

## 1. 现象与证据（2026-10-13，leanback arm64 debug，设备 `192.168.50.3:5561`）

用户报告：**电视版实验室遥控器没法操作焦点，只能在实验室配置文件里面控制启动**。

复现环境：设备已安装 leanback flavor 包（`pm dump` 中存在 `com.fongmi.android.tv.ui.activity.SettingEnhanceActivity`，该 class 只存在于 `app/src/leanback`），`dumpsys window` 报 `mInTouchMode=false`，即真正的遥控/非触摸导航路径。用 `run-as com.silent.android.webhtv am start -n .../lab.LabDetailActivity` 直达包详情页。

### 1.1 根因 A：2 秒轮询整表重建，抢走遥控焦点

`LabDetailActivity` 用 `mRefreshHandler` 每 2000 ms 跑一次 `updateButtons()`，其中无条件调用 `commandAdapter.notifyDataSetChanged()`。RecyclerView 会回收并重新挂载全部子 View，焦点所在的「运行」按钮被移除后，框架把焦点交回第一个可聚焦控件（工具栏返回键）。

实测（`dumpsys activity top` 读 `.F......` 标志位）：

| 步骤 | 焦点落点 | 结论 |
| --- | --- | --- |
| 按 DOWN 进入命令列表 | `MaterialTextView#btnAction`（「运行」） | 命令列表本身可达 |
| 静置 ≤ 1.2 s（未再按键） | `AppCompatImageButton`（工具栏返回） | 焦点被 2 秒轮询踢回工具栏 |
| 之后再按 DOWN | `btnDownload` → `btnTerminal` → 列表 | 必须重新走一遍，按不到「运行」 |

即：遥控用户刚把焦点移到「运行」，还没按确认，焦点就被拉走；这正是「只能靠 lab 配置里的 `auto_execute` 启动命令」的成因。

### 1.2 根因 B：实验室自绘控件没有聚焦态

`adapter_lab_command_compact.xml` 的行与「运行」按钮、`adapter_lab_package.xml` 的包行、`adapter_lab_group_tab.xml` 的分组标签、`activity_lab_detail.xml` 的三个图标按钮都只有按下态（`shape_lab_card` 连按下态都没有），全仓库实验室 drawable 中 `state_focused` 命中数为 **0**。

像素实测：焦点在命令行上（`LinearLayout{77b37ff}`）与焦点在工具栏时，同一命令行区域截图逐像素对比无差异（仅壁纸动画噪声），即**遥控聚焦时画面完全不变**。对照：共享 `MaterialToolbar` 的返回键聚焦时有圆形高亮（`?attr/selectableItemBackgroundBorderless` 自带聚焦态），说明缺聚焦态是实验室自绘控件独有的问题。

### 1.3 根因 C：详情页三个图标按钮在小 UI 缩放下被焦点搜索跳过

`btnTerminal` / `btnAddCommand` / `btnRefreshCommand` 可聚焦（`dumpsys activity top` 里是 `VFED..C..`），但是否被方向键找到取决于几何：

| 设备 UI 缩放 | 从「下载安装」按 DOWN | 说明 |
| --- | --- | --- |
| 大缩放（命令行高 180px，图标位于 y 931..1015） | → `btnTerminal` | 图标是「更近」的候选 |
| 小缩放（命令行高 68px，图标 32px 高、贴右边缘） | → 第一条命令行（**跳过图标**） | `FocusFinder` 用加权距离 `13×主轴² + 次轴²` 比较：图标主轴 27px、次轴 437px（200446）比命令行主轴 73px、次轴 0px（69277）更远 |

即遥控用户在小缩放下按不到终端/新增/刷新；实测只有 TAB 能到达（电视遥控器没有 TAB）。这与本次 drawable 改动无关（纯几何 + 框架加权距离），但同属「遥控没法操作焦点」。

### 1.4 根因 D：命令弹窗的初始焦点落在输入框里，遥控出不去

在包详情页按 OK 打开命令弹窗后，`uiautomator dump` 显示初始焦点是 `sheetCommandPreview`（命令预览 EditText）。
实测在该输入框内 **DOWN / RIGHT / UP 都不移动焦点**（单行可编辑文本把方向键当光标键吃掉），只有 TAB 能到
`sheetBtnEdit`（编辑）；`sheetBtnRun`（运行）在未安装时是 disabled（本就不可聚焦）。电视遥控器没有 TAB，
所以即使命令列表已经能操作，用户打开弹窗后依然按不到「运行」——这是「只能改配置文件启动」的最后一段。

## 2. 决策

| 方案 | 结论 |
| --- | --- |
| `no change` | 遥控无法稳定操作命令列表，只能改配置文件启动，不满足验收 |
| 只修焦点高亮 | 焦点仍会被 2 秒轮询踢走，功能不可用 |
| 只修焦点抢走 | 焦点看不见落点，用户仍会认为「没法操作焦点」 |
| 删除 2 秒轮询 | 会丢掉「命令在别处启动/结束后按钮与状态标签跟随」，引入状态不刷新回归 |
| 命令预览改为不可聚焦 | 会砍掉「运行前改命令」这个既有能力，触摸端也受影响 |
| **采用（四项一起修，最小面）** | ① 命令列表改为「状态差异刷新」：状态未变时完全不触发重排，变化时只 `notifyItemChanged` 单行（复用同一 ViewHolder）；② 给实验室自绘的可聚焦控件补 `state_focused`，沿用仓库既有 TV 聚焦视觉约定（2dp 主题色描边，见 `selector_player_settings_row`）；③ 详情页三个图标按钮用 `nextFocus*` 显式连线，不再依赖缩放相关的加权距离；④ 命令弹窗在 `show()` 后一次性把初始焦点锚到「运行/编辑」按钮上，输入框仍可点选、UP 也能回去 |

## 3. 实施记录

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/fongmi/android/tv/lab/LabCommandAdapter.java` | 新增 `rowStates` 状态签名与 `refreshStates()`：逐行比较 `stateOf()`（运行态 + 名称，即 `onBindViewHolder` 的全部可视差异），未变则 `continue`，变化才 `notifyItemChanged(i)`；`setItem()` 仍走整表刷新（数据本身变了） |
| `app/src/main/java/com/fongmi/android/tv/lab/LabDetailActivity.java` | `updateButtons()` 的 `commandAdapter.notifyDataSetChanged()` 改为 `commandAdapter.refreshStates()`，并加方法注释说明「该方法由 2 秒轮询调用，禁止整表刷新」 |
| `app/src/main/res/drawable/shape_lab_card.xml` | `<shape>` → `<selector>`：新增 `state_focused`（`@color/black_40` + 2dp `@color/accent` 描边，圆角仍 12dp）；包行、命令行、详情信息卡共用，后两者可聚焦 |
| `app/src/main/res/drawable/shape_lab_run_btn.xml` | 新增 `state_focused`（`@color/accent` + 2dp 白描边），置于 `state_pressed` / `state_enabled=false` 之后 |
| `app/src/main/res/drawable/shape_lab_stop_btn.xml` | 同上（红色底 + 2dp 白描边） |
| `app/src/main/res/drawable/shape_lab_click_btn.xml` | 同上（紫色底 + 2dp 白描边） |
| `app/src/main/res/drawable/shape_lab_group_tab_focused.xml` | 新增：分组标签聚焦态（`@color/black_40` + 2dp `@color/accent`，圆角 28dp，内边距与 normal/activated 完全一致以免聚焦时尺寸跳动） |
| `app/src/main/res/drawable/selector_lab_group_tab.xml` | 新增 `state_focused` 分支（放在 `state_activated` 之前，聚焦优先） |
| `app/src/main/res/drawable/selector_lab_icon_btn.xml` | 新增：详情页图标按钮的聚焦/按下/默认三态背景 |
| `app/src/main/res/layout/activity_lab_detail.xml` | `btnTerminal` / `btnAddCommand` / `btnRefreshCommand` 的背景由 `?attr/selectableItemBackgroundBorderless`（只有按下态）改为 `@drawable/selector_lab_icon_btn`；并显式声明焦点邻居：`btnDownload`/`btnUninstall` → `nextFocusDown=btnTerminal`，`btnTerminal` → `nextFocusUp=btnDownload` + `nextFocusRight=btnAddCommand`，`btnAddCommand` → `nextFocusLeft=btnTerminal` + `nextFocusRight=btnRefreshCommand`，`btnRefreshCommand` → `nextFocusLeft=btnAddCommand` + `nextFocusUp=btnDownload` |
| `app/src/main/java/com/fongmi/android/tv/lab/LabCommandSheet.java` | 新增 `requestSheetFocus()`：`dialog.show()` + `syncRunning()` 之后一次性把初始焦点放到「运行」（不可用/未安装时退到「编辑」），避免焦点停在命令预览输入框里被方向键吃掉（见 §1.4） |
| `app/src/test/java/com/fongmi/android/tv/lab/LabTvFocusSourceTest.java` | 新增 8 条源码/资源形态契约测试（见 §4.2） |

## 4. 验证

### 4.1 设备实测（leanback arm64 debug，`127.0.0.1:5561`（即早前 `192.168.50.3:5561`），`mInTouchMode=false`）

装包：`app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk`（本次构建），`adb -P 5038 -s 127.0.0.1:5561 install -r` → `Success`，未清数据。焦点落点由 `dumpsys activity top` 的 `.F......` 标志位读取，弹窗内由 `uiautomator dump` 的 `focused="true"` 读取。

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| 命令列表焦点（按 DOWN 聚焦后静置） | 焦点进入列表后 **≤1.2 s** 即跳回工具栏返回键 | 命令行 t0 / t+2.5 s / t+5.5 s / t+6.2 s 均未移动；聚焦「运行」后 t0 / t+3 s / t+6.2 s 仍在「运行」 |
| 命令行聚焦视觉 | 与未聚焦逐像素一致（仅壁纸噪声） | 2 dp 主题色描边（截图 A/B） |
| 分组标签聚焦视觉 | 无 | 2 dp 主题色描边（`全部/运行环境/工具插件/网络工具/Ubuntu 运行环境` 中仅聚焦项有描边） |
| 包列表行聚焦视觉 | 无 | 2 dp 主题色描边 |
| 行内「运行」按钮聚焦视觉 | 无 | 2 dp 白描边 |
| 详情页图标按钮可达性 | 小 UI 缩放下 `下载安装` → DOWN 直接跳到命令行，图标只有 TAB 可达 | `下载安装` → DOWN `终端` → RIGHT `新增` → DOWN 该行「运行」：全 D-pad 可达 |
| 详情页图标按钮聚焦视觉 | 无 | 聚焦的终端图标有 2 dp 描边 |
| 命令弹窗初始焦点 | `sheetCommandPreview`（命令预览输入框）；DOWN / RIGHT / UP 都不移动，只有 TAB 能到按钮 | `sheetBtnEdit`（「运行」在未安装时 disabled，按设计回退到「编辑」）；「编辑」有 2 dp 白描边，从按钮按 UP 可回输入框 |

弹窗路径实测：命令行「运行」按 OK → 弹窗打开 → 初始焦点 = `sheetBtnEdit`，同时弹出「请先安装 php」提示（未安装包不启动，符合预期；安装后 `sheetBtnRun` 变为 enabled，锚点即落在「运行」上）。

### 4.2 静态与单测

- `.\gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests "com.fongmi.android.tv.lab.*" :app:assembleLeanbackArm64_v8aDebug` → **BUILD SUCCESSFUL**；lab 包 **27 个用例 0 失败**（新增 `LabTvFocusSourceTest` 8 条，含「周期刷新不得整表重建」「差异刷新只做局部通知」「可聚焦控件必须有聚焦态」「图标按钮必须声明焦点邻居」「弹窗初始焦点必须锚在按钮上且轮询不得抢回」）。

### 4.3 回滚

`git revert` 本任务提交即可：只改实验室详情页的刷新方式、实验室自有 drawable/布局与源码级契约测试；无数据迁移、无 native/ABI、无主题 token 变更。

## 5. 回滚

`git revert` 本任务提交即可：只改实验室详情页的刷新方式与实验室自有 drawable/布局，无数据迁移、无 native/ABI、无主题 token 变更。

## 6. 下一步唯一动作

无（本任务已交付：列表差异刷新 + 聚焦视觉 + 图标焦点连线 + 弹窗初始焦点锚点均已设备实测）。

### 6.1 已知残余未知（记录，不在本次范围）

- **状态真变化时的局部刷新未做设备实测**：需要先安装 lab 包（15–200 MB 下载）。实现用的是 `notifyItemChanged(i)` 单行刷新（复用 ViewHolder），若后续发现命令真正启动/结束时焦点仍被抢，可在 `LabDetailActivity` 增加「记录聚焦行 + 布局后回填焦点」。
- **「运行」enabled 时的弹窗锚点未做设备实测**：同样需要先安装 lab 包（`sheetBtnRun.setEnabled(installed && supported)`）。代码路径为 `sheetBtnRun.getVisibility() == View.VISIBLE && sheetBtnRun.isEnabled() ? sheetBtnRun : sheetBtnEdit`，已由源码级测试锁定。
- **实验室其它 TV 页面不在本次范围**：`LabOutputActivity`、`LabTerminalActivity`、`LabVariableDialog`、`LabCommandEditDialog`、`LabFileBrowserDialog` 未做遥控焦点走查。
