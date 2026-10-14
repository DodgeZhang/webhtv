# TV-SITE-DIALOG-PANEL-20261014：电视版站点选择弹窗从全屏改为居中浮层面板

## Recovery anchor

- 目标：TV 版站点选择弹窗（`SiteDialog`）不再铺满整屏，改为居中浮层面板 + 电视安全边距 + 内容自适应高度；站点卡片内容左对齐、健康点紧贴站名、末行不再被裁切。
- 范围：`app/src/leanback/**` 的站点弹窗、卡片适配器与主题 helper、对应布局/样式资源、三语言字符串、定向布局契约测试、本任务文档。
- 保护面：任务 guard 启动时工作区干净（`git status` 为空），无预先存在脏路径。基线 HEAD `2ea9700e5d`，分支 `dev3`。
- 验收：① 窗口宽高不再是 `MATCH_PARENT`，改为居中 + 屏宽减安全边距 + `WRAP_CONTENT`；② 面板四周留出安全边距且宽度有上限；③ 网格高度收敛到整数行，末行不被裁；④ 卡片常态填充比面板高一层、内容左对齐、健康点紧贴站名；⑤ 定向测试与 leanback 编译通过。
- 回滚：撤销本任务原子提交即可恢复全屏站点弹窗行为。

## 1. 用户报告与根因

用户截图显示 TV 端打开站点选择后弹窗直接铺满全屏，观感突兀。

根因链（全部为本地代码证据）：

1. `SiteDialog.applyWindow()` 把窗口宽高硬设为 `WindowManager.LayoutParams.MATCH_PARENT`。该行为由 commit `98890d15ed`（`feat(tv): TV版站源选择弹窗改为全屏展示并聚焦当前站点`）引入，同一提交把 `dialog_site.xml` 根布局高度也改成 `match_parent`。
2. `dialog_site.xml` 根布局 `layout_height="match_parent"`，网格容器用 `layout_weight="1"` 吃掉剩余空间，因此内容被强行拉伸到全屏高度。
3. 卡片常态填充 `shape_site_item_normal.xml` 用 `?attr/colorSurfaceVariant`，而 `ThemeOverlay` 把 `colorSurfaceVariant` 映射到 `webhtv_color_surface_container`——与面板底色 `colorSurfaceContainer` 完全相同，卡片边界在面板上不可见；运行时 `SiteDialogTheme.siteItem()` 的常态分支同样用 `colorSurfaceContainer` 且描边 0。
4. `SiteAdapter` 的两个 ViewHolder 构造器用 `binding.text.setGravity(Gravity.CENTER)` 覆盖了 XML 的居中设置，导致健康点被甩到卡片左边缘、站名居中、右侧留大片空白。
5. 网格高度按 `layout_weight` 拉伸后，非整数行时最后一行被裁掉半截。

## 2. 设计研究

访问日期：2026-10-14（Asia/Shanghai）。网络检索在本会话不可用（搜索端点返回 401），因此本节依据本地既有设计契约与平台惯例，并记录未取回的证据类别。

| 来源 | 修订/地址 | 证据等级 | 结论与决策影响 |
|---|---|---:|---|
| WebHTV 本地设计契约 | `docs/THEME-TV-SITE-PALETTE-20261001.md` | A（本地） | 站点弹窗两条构造路径都必须 `ThemeController.bindDialog`，卡片/文字/图标消费当前 `ThemeController.current()`；本次改动保持该不变量，只改尺寸与层次。 |
| WebHTV 焦点环契约 | `docs/TV-FOCUS-RING-20261004-unified-theme-ring.md`（由 `shape_site_item_focused/selected` 注释引用） | A（本地） | 焦点/选中环宽唯一来源 `@dimen/webhtv_focus_ring_width`；本次不改环宽，避免选中↔焦点切换尺寸跳变。 |
| 移动端同组件对照实现 | `app/src/mobile/java/.../SiteDialog.java:322-336` | A（本地） | 手机端早已是「居中 + 屏宽比例 + 宽度上限 + `WRAP_CONTENT` 高度」。TV 端本次采用同一形态，只是把比例换成安全边距 + 绝对上限，两个 flavor 的窗口策略因此重新一致。 |
| Material 3 对话框规范 | `https://m3.material.io/components/dialogs/specs`（仅取回标题，正文未取回） | C（未取回） | 未作为决策依据；仅确认「对话框是浮层而非全屏」这一平台方向与本次改动一致。 |
| Android TV 设计指南 | `https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv?hl=en`（正文未取回） | C（未取回） | 未作为决策依据；电视安全边距取值改用仓库既有惯例（见下）。 |
| 仓库既有安全边距惯例 | `SiteHealthReportDialog.onStart()` 用 `ResUtil.dp2px(24)` 作为横屏边距 | A（本地） | `PANEL_MARGIN_DP = 24` 直接对齐该既有取值，不新造第二套边距标准。 |

未取回的类别与原因：官方规范正文（网络受限）、上游 PR/issue 讨论（本次为本地 UI 形态调整，无上游依赖变更）。这两类不改变本设计决策，因为改动不涉及播放、解码、依赖或公共 API。

## 3. 方案比较与采用

1. **不变更**：保留全屏。拒绝，用户明确要求不铺满，且全屏下卡片边界不可见、末行被裁。
2. **直接回退 `98890d15ed`**：拒绝。该提交同时带来了「打开时自动聚焦当前站点」这一有效改进，整体回退会丢掉它；且回退后 `dialog_site.xml` 的 `maxHeight=360dp` 等旧约束与后续主题改造已不匹配。
3. **居中浮层面板（采用）**：
   - 窗口：`gravity=CENTER`、宽度 = 屏宽 × 0.82（上限 1180dp、下限 480dp）、高度 `WRAP_CONTENT`、`dimAmount=0.62` 暗化背景。
   - 尺寸用**比例**而非 dp 边距：电视常见 density 2.0，24dp 边距在 1080p 上只有 2.5% 屏宽，面板仍会占满横向——第一版实现踩过这个坑，已改为比例约束。
   - 布局：根布局高度改 `wrap_content`，网格容器去掉 `layout_weight`，新增标题行（标题 + 站点计数）。
   - 网格高度：按「整数行 × 行高 + 行间距」计算，预算 = 可用高度 × 0.74 − 面板固定内容高度；行高常量与 `adapter_site*.xml` 的 `52dp`、`SpaceItemDecoration(GRID_COUNT, 16)` 的 16 对齐。
   - 卡片：常态填充改 `colorSurfaceContainerHigh` + `colorOutlineVariant` 描边（比面板高一层），内容左对齐，健康点紧贴站名；移除 Java 里覆盖 XML 的 `setGravity(CENTER)`。
   - 面板外壳：补回 1dp `colorOutlineVariant` 轮廓（全屏时代面板边缘即屏幕边缘，0 描边看不出问题；收窄成浮层后必须可见）。

## 4. 实施步骤

1. `dialog_site.xml`：根布局改 `wrap_content`，新增 `header`/`title`/`count`，网格容器去掉权重，操作列改 `wrap_content` + `layout_gravity="top"`，间距按浮层重新排布。
2. `SiteDialog.java`：`applyWindow()` 改为居中定宽；新增 `panelWidth()`/`gridHeight()`/`gridBudgetHeight()`/`windowVisibleHeight()`/`panelChromeHeight()`/`fixedHeight()`/`updateCountText()`；`setRootWidth()`/`setRecyclerHeight()` 改为内容自适应；首帧后重算一次网格高度与计数；移除失效的 `setMaxHeight(Integer.MAX_VALUE)`。
3. `adapter_site.xml` / `adapter_site_switch.xml`：行高统一 52dp，文字左对齐，健康点紧贴站名，文字色改语义 `?attr/colorOnSurface`。
4. `SiteDialogTheme.java`：面板外壳补 1dp 轮廓；卡片常态分支改 `colorSurfaceContainerHigh` + `colorOutlineVariant` + 1dp 描边。
5. `shape_site_dialog.xml` / `shape_site_item_normal.xml`：XML 回退样式与运行时样式保持一致。
6. `SiteAdapter.java`：移除两处覆盖 XML 的 `setGravity(Gravity.CENTER)` 与不再使用的 `Gravity` 导入。
7. 三语言字符串新增 `site_dialog_title` / `site_dialog_title_action` / `site_dialog_count`。
8. 更新 `SiteDialogLayoutTest`：把「必须全屏 MATCH_PARENT」的旧断言改为「必须是居中浮层且不得铺满」，并新增安全边距/宽度上限、整数行高度两条断言。
9. **实机验证中修掉一个自己引入的崩溃**：`updateCountText()` 原本用 `Fragment.getString()` 取计数文案，但 TV 版走的是裸 `Dialog`（`showDirect`），Fragment 从未 attach 到 FragmentManager，`getString()/requireContext()` 会抛 `IllegalStateException: not attached to a context`。改为 `binding.getRoot().getContext().getString(...)`。同文件其余取字符串处本来就走 `getDialogActivity()`，未受影响。

## 5. 不变量与回滚

- 打开时自动聚焦当前站点（`focusSelectedSite()`）、左右键翻组（`onItemKeyHorizontal`）、长按分组排序、搜索/切换/全选/取消等既有交互全部保留。
- `ThemeController.bindDialog` 两条构造路径绑定不变；主题 profile 切换仍即时生效。
- 焦点/选中环宽仍为唯一来源 `@dimen/webhtv_focus_ring_width`，未改动。
- `GRID_COUNT = 3` 与 `SpaceItemDecoration(GRID_COUNT, 16)` 保持不变（`HomeMenuDialogSourceTest` 断言依赖）。
- 回滚：撤销本任务原子提交。

## 6. 验证记录

### 6.1 静态与单元测试

- 编译：`./gradlew :app:assembleLeanbackArm64_v8aDebug` 通过（仅既有 deprecation/unchecked 提示）。
- 定向 JVM 测试（leanback）：`SiteDialogActionFocusTest` 3/3、`SiteDialogThemeSourceTest` 2/2、`HomeMenuDialogSourceTest` 5/5 通过。
- 定向 JVM 测试（mobile，含本次新增/改写断言）：`SiteDialogLayoutTest` 3/3、`SiteDialogThemeSourceTest` 5/5、`SiteDialogSelectionScrollSourceTest` 1/1 通过。
- 相邻契约测试：`DialogRoundedCornerSourceTest` 2/2、`TouchOptimizationHelperSourceTest` 11/11 通过。
- 合计 32 个测试、0 失败（逐套件读取 `build/test-results` XML 核对，非仅看 Gradle 退出码）。

### 6.2 实机验证（1080p，density 2.0）

设备：`127.0.0.1:5559`（与 `emulator-5558` 同一实例，1920x1080、`wm density 280`、应用实际按 density 2.0 渲染）。安装产物：`leanbackArm64_v8aDebug`（`adb install -r` 覆盖，未卸载、未清数据）。

用 `uiautomator dump` 读到的实测坐标（屏幕 1920x1080）：

| 项 | 实测 | 判定 |
|---|---|---|
| 面板宽度 | 1558px = 屏宽 **81%** | 命中 `PANEL_WIDTH_RATIO=0.82`，不再铺满 ✓ |
| 面板高度 | 706px = 屏高 **65%** | 低于 `PANEL_MAX_HEIGHT_RATIO=0.74` 上限 ✓ |
| 居中 | 左右各 181px、上下各 187px | 完全居中 ✓ |
| 网格高度 | 376px = 3×52dp + 2×16dp | 恰好整数 3 行，末行未被裁 ✓ |
| 标题/计数 | `header` 88px、`count`「共 170 个站点」 | 新增标题行生效 ✓ |

观感与交互实测：

- 面板为带圆角与 1dp 轮廓的浮层，四周可见背景，暗化遮罩生效；不再是全屏网格。
- 三列卡片边界清晰可见（常态 `colorSurfaceContainerHigh` + 描边），健康点紧贴左对齐的站名。
- 焦点态（`colorPrimary` 深蓝填充 + 白字）与选中态（`colorPrimaryContainer` 浅蓝 + 主色字）区分明确；按遥控右键焦点在网格内正确移动。
- 点分组标签「4K」后计数变为「共 1 个站点」，**面板高度自动收缩到贴合单行内容**——内容自适应行为正确。
- 全流程 `logcat` 无 `FATAL EXCEPTION`。

### 6.3 过程中的失败与结论

- `testMobile` 首次因 Gradle 构建缓存锁被另一 daemon 占用（`Timeout waiting to lock Build cache`）失败，属环境冲突；原样重试即通过。
- 首版尺寸用 dp 边距，自查发现 1080p 上面板仍占 95% 宽，改为比例约束后重跑全部测试。
- **实机首次打开弹窗即崩溃**（`Fragment ... not attached to a context`），根因是 `updateCountText()` 用了 `Fragment.getString()`；单元测试与编译都无法发现这一类「TV 版不是真正的 Fragment 宿主」的运行时约束。修复后重新构建、覆盖安装并复测通过。

未覆盖：真机电视的遥控器实体按键与过扫描裁切未验证（本会话只有平板形态模拟器）。

## 7. 当前状态与下一步

- 状态：实现、静态验证与 1080p 实机验证均已完成；实机发现的崩溃已修复并复测通过。
- 下一步：等用户在真实电视上确认遥控器手感与过扫描观感；确认后执行 task guard `finish` 原子提交并创建恢复标签。
