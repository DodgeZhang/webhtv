# WebHTV 主题整合与安全自定义实施、验收文档

> 状态：方案定稿候审。本文只定义实现与验收，不代表生产代码已经修改。
> 基线：`dev3@30ba0f48f92a51e5a8e5d407d63ff5ce655731fc`，起始工作树干净。
> 编写时间：2026-09-21 15:22 CST（Asia/Shanghai）。
> 适用范围：原生 `mobile`、`leanback`、共享 Dialog/列表/详情表层、内置 Web 页面的 color token。播放器视频画面、解码、渲染、字幕、音轨、播放状态机不在范围内。
> 关联文档：`docs/webhtv-unified-visual-design-system-20260920.md`、`docs/theme-color-system-design-20260907.md`、`docs/universal-webhome-theme-design.md`、`docs/universal-webhome-theme-development.md`。

## Recovery anchor

- 目标：先完成 Layer 1 主题来源整合，再以保守的 B-safe 方案开放 16 个语义槽，使用户能够稳定调整按钮、高亮、文字、表面、状态色和透明度，同时不破坏播放能力与既有页面行为。
- 已确认边界：用户同意先做 Layer 1；Layer 2 倾向 B，但由实施者选择更稳妥、更适用的子集。本方案裁定为 **B-safe：16 个语义槽 + 自动派生依赖角色 + 严格控制透明度范围**，不开放 49 个原始 token，不允许用户直接制造不可读配对。
- 验收标准：Layer 1、Layer 2 分别满足本文 DoD；静态检查、JVM 测试、mobile/leanback debug 编译、代表性设备场景、主题取消/应用/重启/回滚全部通过；播放器画面和性能不得回退。
- 当前证据：当前 `Theme.Base` 仍继承系统 Material/DynamicColors 主题；`ThemeController` 已能解析/保存快照，但没有把任意 token 应用到现有 `?attr/color*` 视图树；页面仍有 2007 个 `?attr/color*`/`?attr/webhtvColor*` 引用和 303 个直接 token 资源引用。
- 当前状态：Layer 1（3.4）、Layer 2A（4.8）、Layer 2B（4.9）、Layer 2C（4.10）均已提交并打 recovery tag；Layer 2D 的 Web 快照与备份收口已完成（见 4.11）。原生“外观→主题色”入口阻塞已由 `NAV-THEME-ENTRY-20260922` 修复并完成代表性设备验证（见 4.12）。主题色彩“只有站点弹框生效”的运行时缺陷已定位并修复（见 4.13），`AlertDialog`/`LightDialog` 家族已接入绑定（见 4.14），追更页整页接入并建立“BaseActivity 之外 Activity 必须显式豁免”的守门测试（见 4.15），背景通道扩展到 Material 形状面板并取到弹窗绑定的真实设备证据（见 4.16），共享 main 模块 125 处弹窗构造统一接入主题构建器（见 4.17）。4.17 提出的 P0 崩溃已修复并加守门测试、其错误普查结论已在 4.18 更正。对话框**窗口背景（面板）通道**已于 4.19 打通并取得设备证据。**当前剩余：半透明填充匹配策略、10 处裸 `AlertDialog.Builder`、`StateListDrawable`/`RippleDrawable` 背景、远程 Web 主题页面、4.7 节完整设备矩阵，（4.20 记录的陈旧红灯已由 4.21 修复，移动端全量 4863 个测试现为全绿）**，因此按第 8 节完成定义，尚不能宣称“全局主题自定义已完成”。
- 已知任务外缺陷：`3f3ab82b1f` 在 leanback 播放器布局中引用了从未声明的 `colorOnSurface_20/70/80/90`，导致 TV 资源链接失败；Layer 1 已按用户批准的方案 A 一并补齐（见 3.4）。
- 下一步唯一动作：为剩余直接 `MaterialAlertDialogBuilder(...).show()` 调用点建立统一收口（共享 helper + 源码守门，避免逐文件散改），并让 binder 覆盖 `MaterialShapeDrawable`（对话框面板/window 背景）；随后回到 dev3 `192.168.50.3:5559` 补第 4.7 节设备矩阵。在此之前 Layer 2 仍只能标记为“代码完成、完整设备验收未完成”。

---

## 1. 决策摘要

### 1.1 强制顺序

1. **Layer 1：先整合来源。** 让 `Theme.Base` 真正继承 WebHTV 语义主题，使 `?attr/color*`、Dialog 语义资源、Web token 快照都来自同一套默认 token。
2. **Layer 2：再开放自定义。** 用户编辑 16 个语义槽，resolver 派生完整 `ThemeTokens`，再由 Activity/Dialog/动态子视图绑定器将结果应用到已迁移的语义组件。
3. **Layer 3：暂缓。** JSON/主题市场导入导出、完全自由 49 色、任意 alpha、远程主题写回原生主题均不在本轮授权内。

Layer 1 是 Layer 2 的先决条件。跳过 Layer 1 会让部分页面继续读取 Material 默认色，导致“自定义只改到一部分界面”，形成新的视觉分叉。

### 1.2 自定义等级裁定

| 方案 | 内容 | 结论 | 原因 |
| --- | --- | --- | --- |
| A：仅预设/主色种子 | 固定 6–8 套主题，或只选一个 seed | 不够 | 无法覆盖用户明确提出的按钮、文字、高亮、弹出框和透明度 |
| **B-safe：16 个语义槽** | 每槽可独立覆盖；`on*` 自动派生；透明度独立限制 | **采用** | 覆盖主要配色诉求，同时保留对比度、状态和播放器安全边界 |
| B-full：49 个 token 全开 | 直接编辑每个语义色 | 拒绝 | 配对关系容易破坏，测试矩阵和修复成本显著增加 |
| C：完全自由 | 任意 ARGB、任意透明度、手工覆盖 on 色 | 拒绝 | 用户可制造不可读界面；播放器和特殊画面容易被误改 |

### 1.3 分阶段可独立回滚

| 阶段 | 交付物 | 可独立回滚点 |
| --- | --- | --- |
| L1 | 默认主题来源整合、Material 角色完整映射、legacy 资源语义化 | 恢复到当前 `Theme.Base` / DynamicColors 行为 |
| L2A | 16 槽 profile、校验器、resolver、持久化和迁移 | 删除 profile 读取，继续使用当前 `theme_color` 镜像 |
| L2B | `ThemeBinder`、Activity/Dialog/动态子视图接线 | 关闭 binder，仅保留 Layer 1 默认主题 |
| L2C | 自定义主题编辑器和实时预览 | 隐藏编辑入口，不影响已保存 profile 的解析兼容性 |
| L2D | Web 快照、备份兼容、设备验收和文档收口 | 回退快照附加字段，不改变已有 13 个字段 |

---

## 2. 当前实现与证据

### 2.1 主题入口现状

| 位置 | 当前行为 | 对方案的影响 |
| --- | --- | --- |
| `app/src/mobile/res/values/styles.xml` | `Theme.Base` 继承 `Theme.Material3.DynamicColors.DayNight.NoActionBar` | 页面实际颜色由系统动态色/Material baseline 决定 |
| `app/src/leanback/res/values/styles.xml` | `Theme.Base` 继承 `Theme.Material3.Dark.NoActionBar`，并把 `colorPrimary` 写为白色 | TV 主色和 token 分离 |
| `app/src/main/res/values/webhtv_styles.xml` | `Theme.WebHTV`/`Theme.WebHTV.Mobile`/`Theme.WebHTV.TV` 已定义，但没有 Manifest/Activity 消费者 | 是待接线的默认主题，不是当前运行主题 |
| `ThemeController.applyFromPreferences()` | 解析持久化快照，更新系统栏；不向现有视图树应用 token | 需要 Layer 2 增加受控绑定 |
| `BaseActivity.enableDynamicColor()` | Android 12+ 可调用 Material DynamicColors；Android 9 等低版本不会生效 | Layer 1 暂保留以避免 API 31+ 现有主题色回退，Layer 2 用统一 resolver 取代 |
| `WebThemeBridge.snapshotJson()` | 已暴露 primary/surface/onSurface/outline/status/focus 的只读快照 | Layer 2 可复用，不增加 Web 写回原生能力 |
| `ThemeDialog` | 14 个固定色圆点，写 `theme_color` 后发 `RefreshEvent.theme()` | 将由 Layer 2 编辑器替代；旧字段保留迁移兼容 |

### 2.2 代码库计数快照

以下为 2026-09-21 基线扫描，计数用于确定工作量和回归门禁，不作为永久常量：

| 指标 | 数量 | 解释 |
| --- | ---: | --- |
| `?attr/color*` + `?attr/webhtvColor*` | 2007 | 已语义化但仍由 Activity 主题解析的引用 |
| 不同颜色 attr | 29 | 包括 `colorPrimary`、`colorSurfaceContainerHigh`、`colorOnSurface` 等 |
| `@color/webhtv_*` 直接引用 | 303 | 多落在固定 selector、自定义 Drawable、播放器等特殊区域 |
| Java 8 位十六进制颜色字面量 | 1133 | 含大量特殊业务色，不能机械迁移 |
| XML 原始 hex | 约 696 行 | token 定义和豁免项占一部分，剩余需按角色处理 |
| Activity/Adapter/Dialog Java 文件 | 101/88/大量 Dialog | 决定了绑定不能靠逐个 Adapter 手工接线 |

### 2.3 已确认的技术事实

1. **Material 主题引用是编译期资源。** `?attr/colorPrimary` 在视图 inflate 时解析，普通 `SharedPreferences` 写值不会改变已经解析的颜色。
2. **公开 `Resources.Theme` API 只接受已编译 style resource。** 例如 `applyStyle(int resid, boolean force)` 不能直接传入任意 ARGB。因此“任意槽位运行时可调”不能靠一个虚构的动态 theme 属性完成。
3. **DynamicColors 有系统版本门槛。** 当前 Android 9/API 28 上 `isDynamicColorAvailable()` 不成立，所以把 DynamicColors 当唯一实现会让用户选择失效。
4. **语义资源已经基本铺开。** 2007 个颜色 attr 引用表明 Layer 2 可以以标准 Material 组件为主进行绑定，而不是逐个布局重写。
5. **特殊画面不能混入普通主题。** 播放器控制、视频画面、海报/背景图、品牌 Logo、Karaoke 结果、健康状态和 TMDB 评分品牌色存在功能语义，必须豁免或单独建模。

### 2.4 设计研究门禁结论

本方案沿用仓库既有设计研究的结论，并在实施前复核以下一手资料：

| 来源（访问日期均为 2026-09-21） | 结论 | 对本方案的约束 |
| --- | --- | --- |
| Android Developers, Styles and themes, <https://developer.android.com/develop/ui/views/theming/themes> | 主题提供可复用的属性集合；Activity 主题必须在 inflate 前确定 | `Theme.Base` 必须在 Layer 1 接入 WebHTV 主题，运行时绑定必须发生在视图创建后 |
| Android Developers, Enable users to personalize their color experience, <https://developer.android.com/develop/ui/views/theming/dynamic-colors> | Dynamic Colors 是平台能力，不能作为低版本唯一方案 | Android 9 需应用侧 token fallback；保留预设并提供稳定的应用侧 resolver |
| Android `Resources.Theme` API, <https://developer.android.com/reference/android/content/res/Resources.Theme> | `applyStyle` 接受资源 style，不提供公开的任意 ARGB 属性写入 API | Layer 2 必须使用受控 view binder，不能依赖反射改 Resources |
| Material 3 Color roles, <https://m3.material.io/styles/color/roles> | 颜色必须以角色和配对使用，语义角色比单一色值更重要 | 只开放 16 个用户槽，`on*` 与容器配对由 resolver 自动生成 |
| 仓库 `app/src/main/java/com/fongmi/android/tv/theme/ThemeContrast.java`，基线 `30ba0f48f92a51e5a8e5d407d63ff5ce655731fc` | 38 组对比度门禁已经存在，最低 `outline/surface=4.28:1` | 自定义 profile 必须复用并增加组合验证，不新增低对比度通道 |
| 仓库历史 `0503f8e3cf5a6b0e178253bdddf46860414a24fc` / `be1b02e06b22a4fa2f08c791555536e3e6154c95` 与 `docs/theme-color-system-design-20260907.md` | 全量 profile/导入导出曾独立实现并回退，范围远超当前视觉统一阶段 | 参考数据模型和安全校验，不照搬 49 色全开放或导入导出边界 |

研究结论：选择“Layer 1 + B-safe”，拒绝修改 `Resources` 内部结构、拒绝全量 49 色直接开放、拒绝把远程主题作为原生主题写入口。

---

## 3. Layer 1：主题来源整合

### 3.1 目标

完成本阶段后，未启用自定义 profile 的设备不再出现“页面 Material 紫、Dialog WebHTV token、TV 主色白色”的分裂；同一 Activity/Flavor 的 `?attr/color*`、直接语义 token、Dialog overlay 和 Web 只读快照必须表达同一套默认语义。

### 3.2 实现步骤

#### L1.1 接入 flavor 主题

修改以下文件：

- `app/src/mobile/res/values/styles.xml`
- `app/src/leanback/res/values/styles.xml`
- `app/src/main/res/values/webhtv_styles.xml`

实施规则：

1. mobile 的 `Theme.Base` 改为继承 `Theme.WebHTV.Mobile`，保留状态栏/导航栏透明、`windowDrawsSystemBarBackgrounds` 和 `materialAlertDialogTheme`。
2. leanback 的 `Theme.Base` 改为继承 `Theme.WebHTV.TV`，移除 `colorPrimary=@color/white`，保留 TV 的深色 `isLightTheme=false` 与窗口属性。
3. `Theme.WebHTV` 补齐 Material 组件实际读取的角色映射，至少包括：
   - primary/onPrimary/primaryContainer/onPrimaryContainer
   - secondary/onSecondary/secondaryContainer/onSecondaryContainer
   - tertiary/onTertiary
   - error/onError/errorContainer/onErrorContainer
   - surface/surfaceDim/surfaceBright
   - surfaceContainerLowest/Low/Container/High/Highest
   - onSurface/onSurfaceVariant
   - outline/outlineVariant
   - inverseSurface/inverseOnSurface/inversePrimary
   - `android:colorBackground`
4. 保留 `values-v27/styles.xml` 的 `Theme.App -> Theme.Base` 关系，不额外复制颜色。
5. Dialog overlay 继续使用 `ThemeOverlay.WebHTV.Dialog`；其静态默认值必须与 Layer 1 token 相同。

#### L1.2 处理遗留直连资源

按“用户语义色”与“功能色”分类，不进行全仓库盲目替换。

必须迁移到主题 attr 或 `Widget.WebHTV.*` 的范围：

- 页面根 surface、卡片、列表项、设置行、普通按钮、输入框、芯片、分隔线和 Dialog/BottomSheet 表面。
- 使用 `@color/webhtv_color_*` 但属于普通 UI 色彩的 selector/drawable。
- 仍依赖 Material baseline 的颜色型 style，例如未显式接入 WebHTV 的按钮、Toolbar、Tab 和 BottomNavigation。

保留为功能色并加入 allowlist 的范围：

- `playerControl*`、`playerScrim`、视频画面相关黑/白遮罩。
- TMDB/豆瓣/烂番茄/IMDb 等品牌评分色。
- health good/warn/bad 的语义状态，若业务要求与主题 error/success/warning 独立。
- Karaoke、LUT、Logo、产品插画和不可主题化手势指示。
- 只在单个 Canvas/Paint 内使用的业务色。

静态门禁要求：

- `bash scripts/check_ui_tokens.sh --strict` 的 allowlist 外命中必须为 0。
- `Theme.WebHTV` 不得再出现 `Theme.Material3.DynamicColors` 作为应用默认父主题。
- leanback 不得再把 `colorPrimary` 写死为白色。
- `ThemeOverlay.WebHTV.Dialog`、普通页面和 Web native token 三条路径的默认值必须一致。

#### L1.3 统一 Activity 快照时序

保留 `AppCompatDelegate` 的 mode 应用和现有 `RefreshEvent.THEME -> recreate()`，并明确时序：

1. `App.onCreate()` 调用 `ThemeController.applyNightModeToApp()`。
2. BaseActivity 在 `super.onCreate()` 后、首次 `setContentView()` 前调用 `ThemeController.applyFromPreferences(this)`，刷新 `ThemeTokens.current()`。
3. Layer 1 暂保留 Android 12+ 的 `DynamicColors.applyToActivityIfAvailable()`，以避免现有固定主题色在 API 31+ 回退；它不能作为 Layer 2 的唯一实现。
4. 主题设置变更后仍通过 `RefreshEvent.theme()` 触发一次 Activity recreate；禁止在正在渲染的播放器视图上直接重建颜色状态列表。

#### L1.4 收敛非全局 night 判断

新增 `ThemeController.isNight(Context)` 仅用于普通 UI 的“当前是否暗色”判断。替换 `VideoActivity`、`WebHomeChromeController`、`TmdbHeaderView` 等普通页面的直接 `UI_MODE_NIGHT` 判断。

不替换以下业务判断：

- `Setting.resolveTmdbDetailLightTheme(...)` 的详情页专属主题。
- 根据设备类型、DRM、视频 HDR、解码能力等作出的模式选择。
- 播放器内字幕、弹幕、画面亮度等非 UI 主题判断。

### 3.3 Layer 1 验收

#### 自动化

必须运行一次并通过：

```bash
bash scripts/check_ui_tokens.sh --strict
./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac
./gradlew :app:testMobileArm64_v8aDebugUnitTest :app:testLeanbackArm64_v8aDebugUnitTest
git diff --check
```

新增或更新源码契约测试：

- `ThemeBaseWiringTest`：mobile parent 为 `Theme.WebHTV.Mobile`，leanback parent 为 `Theme.WebHTV.TV`。
- `ThemeResourceSourceTest`：禁止 `colorPrimary=@color/white`，禁止 app base 使用 DynamicColors 父主题。
- `ThemeControllerContractTest`：`applyFromPreferences()` 仍在首次 setContentView 前调用；theme recreate 仍存在。
- `DialogThemeWiringTest`：普通 Dialog、BottomSheet、LightDialog 都使用共享 WebHTV overlay。

#### 设备验收

dev3 只使用分配的 `192.168.50.3:5559`；如必须增加 Android 12+ 或 TV 模拟器，先向用户申请，不得占用其他工作区设备。

| 场景 | 通过标准 |
| --- | --- |
| API 28 mobile 冷启动 | 默认界面不出现 Material purple；首页、设置、外观 Dialog 同源 |
| API 28 浅色/深色切换 | 所有主要页面立即重建，文本、卡片、弹窗和导航可读 |
| TV/leanback | primary 不为白色；焦点、选中、卡片、Dialog 可辨识 |
| Dialog/BottomSheet | 页面与弹窗的 surface/outline/button 角色一致 |
| WebHome/Eclipse | `data-theme-source=native` 时颜色与原生快照一致 |
| 播放器回归 | 进入真实 VOD，暂停、换线、字幕、返回正常；视频画面无变化 |
| 连续 3 次主题切换 | 无 `FATAL EXCEPTION`、无黑屏、无持续泄漏 |

Layer 1 DoD：

- 默认来源只剩一套 WebHTV 语义 token。
- 低版本不再渲染 Material baseline 紫；API 31+ 现有 theme_color 不因整合而回退。
- strict 静态门禁、单测、双 flavor Java 编译、设备代表场景全部通过。
- 独立提交并生成 recovery tag；可单独回滚，不涉及 profile 数据。

### 3.4 Layer 1 实施记录（2026-09-21）

实施范围（12 文件，全部在 task `L1-THEME-INTEGRATE-20260921` 声明路径内）：

- `app/src/mobile/res/values/styles.xml`：`Theme.Base` 由 `Theme.Material3.DynamicColors.DayNight.NoActionBar` 改为 `Theme.WebHTV.Mobile`，保留系统栏透明与 `materialAlertDialogTheme`。
- `app/src/leanback/res/values/styles.xml`：`Theme.Base` 由 `Theme.Material3.Dark.NoActionBar` 改为 `Theme.WebHTV.TV`，删除 `colorPrimary=@color/white`。
- `app/src/main/res/values/webhtv_styles.xml`：`Theme.WebHTV` 补齐布局实际使用的角色（tertiary、surfaceDim/Bright、surfaceContainer 五级、surfaceVariant、inverse 三色、controlNormal、onBackground、`android:windowBackground`）；`Theme.WebHTV.Dialog` 与 `ThemeOverlay.WebHTV.Dialog` 补齐 secondaryContainer/error/outline/surface container/control 角色，页面与弹窗同源。
- `app/src/main/res/values/webhtv_attrs.xml` + 3 个 palette 文件：新增 `colorOnSurface_20/70/80/90` 属性与 `webhtv_on_surface_20/70/80/90` 资源，RGB 与 `webhtv_color_on_surface` 严格一致、alpha 取 `0x33/0xB3/0xCC/0xE6`（沿用原 `white_20/70/80/90` 的透明度意图）。这一并修复了 `3f3ab82b1f` 遗留的 leanback 资源链接失败。
- `ThemeController.isNight(Context)`：普通 UI 夜间判断的唯一入口；`WebHomeChromeController.useDarkIcons()` 改用它，修正强制浅/深色模式下状态栏图标反色与主题不一致。
- 测试：新增 `ThemeBaseWiringTest`（5 项：继承链、Activity 角色映射、Dialog 角色映射、布局引用 attr 必须有声明且被主题赋值、alpha 资源必须跟随 onSurface token），并在 `ThemeControllerContractTest` 增加快照早于首个 `setContentView`、night 判断不得直读 `UI_MODE_NIGHT_MASK` 两项契约。

自动化证据（最终代码状态）：

- `bash scripts/check_ui_tokens.sh --strict`：`violations=0 legacy=0`，38 组对比度 0 失败（min=4.28），`layouts=378 hex_layouts=0`、`drawables=549 hex_drawables=0`、`colors=52 hex_colors=0`、`allowlisted=191`。
- mobile：`:app:testMobileArm64_v8aDebugUnitTest` 705 套件 / 4790 项 / 0 失败 / 0 错误。
- leanback：`:app:testLeanbackArm64_v8aDebugUnitTest` 614 套件 / 3917 项 / 0 失败 / 0 错误。
- `scripts/build_arm64_debug_install.sh --flavor mobile|leanback --serial 192.168.50.3:5559` 均 `BUILD SUCCESSFUL` 且覆盖安装成功；leanback 资源链接由失败恢复为通过。

设备证据（dev3 `192.168.50.3:5559`，API 28）：

- mobile：冷启动进入 `HomeActivityCurrent`，进程存活（pid 13915），`FATAL EXCEPTION` 为 0；主色实测为 token 蓝 `#0B57D0`（加载指示器、底部选中图标），不再是被动 Material 基线紫 `#6750A4`；设置弹窗卡片/文字可读。
- leanback：冷启动进入 `HomeActivityCurrent`，`FATAL EXCEPTION` 为 0；加载指示器为 dark primary `#A8C7FA`，焦点框正常，未出现白色 primary 造成的整体泛白。
- 截图：`/tmp/l1-mobile-home.png`、`/tmp/l1-mobile-personal-dialog.png`、`/tmp/l1-tv-home.png`、`/tmp/l1-mobile-final.png`。

任务外缺陷记录（不在本任务修复范围）：

- `com.fongmi.android.tv.subtitle.RealtimeSubtitleModelVerifierTest#verifiedMarkerRejectsSameSizeMutation` 在 leanback 首次全量单测中失败。根因是 `RealtimeSubtitleModelVerifier.isVerified()` 比对 `file.lastModified()`+长度，而测试在同一毫秒内写入等长内容，属时间粒度型 flaky；重跑通过。该测试来自 `59dd1d964c`，与主题无关，建议后续单独修复（改为比对内容哈希或引入 mtime 容差）。

残余未验证项（Layer 1 范围内）：

- 未在真实播放器音频面板上逐项确认 `colorOnSurface_20/70/80/90` 的渲染效果（仅验证资源链接、主题赋值、alpha 契约与启动）。完整播放器回归属于 Layer 2/播放器验收矩阵。
- 未覆盖 Android 12+ 设备的 Dynamic Colors 与显式 profile 优先级（当前设备为 API 28）。

回滚锚点：Layer 1 为单一提交，回退该提交即恢复 `Theme.Base` 的 Material/DynamicColors 继承与原有（失败的）leanback 资源状态；无数据迁移。

---

## 4. Layer 2 B-safe：16 个语义槽

### 4.1 为什么是 16 槽

用户真正需要控制的是“界面角色”，不是 49 个内部色号。B-safe 将编辑面限制为 16 个槽，其余角色由 resolver 自动派生，从而同时满足可用性和可验证性。

| # | 用户槽 | 主要影响 | 依赖角色/说明 |
| ---: | --- | --- | --- |
| 1 | `primary` | 主按钮、主高亮、选中导航、进度强调 | 自动生成 `onPrimary`、按下/焦点混合 |
| 2 | `primaryContainer` | 主选中底、主 Chip、次级强调面 | 自动生成 `onPrimaryContainer` |
| 3 | `secondaryContainer` | tonal 按钮、列表选中、次级标签 | 自动生成 `onSecondaryContainer` |
| 4 | `focus` | TV 焦点环、键盘/遥控焦点描边 | 与 surface 保证至少 3:1 |
| 5 | `surface` | 页面根背景、最底层弹窗 | 自动生成基础 onSurface fallback |
| 6 | `surfaceContainer` | 普通卡片、分组容器 | 与 onSurface 检查 4.5:1 |
| 7 | `surfaceContainerHigh` | Dialog、BottomSheet、浮层 | 与 onSurface 检查 4.5:1；受 dialogOpacity 约束 |
| 8 | `onSurface` | 正文、标题、主要图标 | 由当前模式默认或用户覆盖 |
| 9 | `onSurfaceVariant` | 次要文字、提示、未选中状态 | 与 surface/container 检查 4.5:1 |
| 10 | `outline` | 输入框、分隔线、普通边框 | 与 surface 保证至少 3:1 |
| 11 | `error` | 错误按钮、错误状态 | 自动生成 `onError`；失败状态可独立保留 |
| 12 | `success` | 成功/健康状态 | 自动生成 `onSuccess` |
| 13 | `warning` | 警告/注意状态 | 自动生成 `onWarning` |
| 14 | `scrimOpacity` | 页面/Dialog 外遮罩透明度 | 安全范围 `0.00..0.85` |
| 15 | `dialogOpacity` | AlertDialog/BottomSheet 表面透明度 | 安全范围 `0.70..1.00` |
| 16 | `overlayOpacity` | 玻璃卡片、轻遮罩、播放控制外层 | 安全范围 `0.05..0.60` |

明确不开放：

- `on*` 与 `*Container` 的人工成对覆盖。
- 任意单个 HTML/CSS/TweakCN token。
- 视频画面、字幕、播放器 active 黄、健康红绿黄、评分品牌色、Logo。
- 任意低于安全下界的透明度。

### 4.2 用户能力

用户最终可以：

1. 选择 8 套内置预设，或从 seed 生成完整主题。
2. 分别编辑浅色/深色模式的 13 个颜色槽。
3. 单独调节 3 个透明度槽。
4. 在应用前预览按钮、文字、卡片、Dialog、焦点和透明度。
5. 应用、取消、恢复默认；应用后全应用统一重建。
6. 恢复旧 `theme_color` 设置；无需手工编辑 JSON。

本轮不做：

- 导入/导出 JSON、在线主题市场、TweakCN 社区浏览。
- 任意布局/圆角/字体/间距自定义。
- Web 页面反向修改原生主题。
- 给插件或第三方内容源提供主题写入口。

### 4.3 数据模型

新增 profile，不把 16 个字段扁平塞入 `Setting` 的散键。建议模型如下：

```java
public final class ThemeProfile {
    public static final int SCHEMA_VERSION = 2;
    public String format = "webhtv-theme";
    public String id = "webhtv.local";
    public String name = "Default";
    public String mode = "system";       // system | light | dark
    public String seedSource = "none";  // none | wallpaper | custom
    public String seedColor;            // #RRGGBB, optional
    public SlotSet light = new SlotSet();
    public SlotSet dark = new SlotSet();
}

public final class SlotSet {
    public String primary;
    public String primaryContainer;
    public String secondaryContainer;
    public String focus;
    public String surface;
    public String surfaceContainer;
    public String surfaceContainerHigh;
    public String onSurface;
    public String onSurfaceVariant;
    public String outline;
    public String error;
    public String success;
    public String warning;
    public Float scrimOpacity;
    public Float dialogOpacity;
    public Float overlayOpacity;
}
```

约定：

- 颜色字符串只接受 `#RRGGBB` 或 `#AARRGGBB`，普通槽必须不透明；alpha 只能走 opacity 字段。
- `null` 表示继承该模式的内置 token 或 seed 派生结果，不表示黑色或透明。
- `mode` 继续映射 `-1/0/1` 到 system/light/dark；兼容现有 `Setting.getThemeMode()`。
- `theme_color` 不再是唯一数据源，但继续镜像 `seedSource/seedColor` 供旧版本和快速回退使用。
- B-safe profile 使用 `SCHEMA_VERSION=2` 和独立键 `theme_profile_v2_json`、`theme_profile_v2_last_good`、`theme_profile_v2_schema`，不得复用历史 v1 的 `theme_profile_json`，避免旧版 TweakCN profile 被误读。
- 若设备存在历史 v1 `theme_profile_json`，启动迁移只读取 `mode`、`seedSource`、`seedColor`，并尝试映射旧的 primary/surface/onSurface/outline/error；其余旧字段忽略并记录诊断，不直接信任 49 色历史数据。
- `theme_mode`、profile 三键必须加入 `Backup.APP_PREFS`；旧备份缺少 profile 时走迁移。

### 4.4 Resolver

Resolver 使用唯一顺序：

```text
内置 light/dark token
  -> 可选 seed 生成 M3 tonal scheme
  -> 应用该模式的 13 个颜色 override
  -> 派生 on* / container 配对
  -> 应用 3 个 opacity clamp
  -> 组合背景后执行对比度校验
  -> 成功返回 ThemeTokens；失败修正或回退到 last-known-good
```

实现契约：

1. `ThemeResolver.resolve(ThemeMode, ThemeSeed, seedColor, wallpaperColor, ThemeProfile, systemDark)` 返回完整不可变 `ThemeTokens`。
2. `onPrimary`、`onPrimaryContainer`、`onSecondaryContainer`、`onError`、`onSuccess`、`onWarning` 由 resolver 通过黑/白可读性选择，不直接暴露。
3. `focus` 与 `surface` 至少 3:1；`outline` 与 `surface` 至少 3:1；普通文本与对应 surface/container 至少 4.5:1。
4. `surface`/`surfaceContainer`/`surfaceContainerHigh` 必须按层级保持可辨识差；若用户设置相等，编辑器允许保存但 preview 提示“层级不清晰”，应用时自动做最小明度调整。
5. `dialogOpacity < 0.70`、`scrimOpacity > 0.85`、`overlayOpacity > 0.60` 或非有限数直接拒绝。
6. resolver 的任何异常都回退 `theme_profile_last_good`，再回退 Layer 1 默认；不得阻塞 Activity 启动。
7. `ThemeResolver.lastDiagnostic()` 保留，扩展为可区分 `default`、`seed`、`profile`、`corrected`、`last-good`、`fallback`。

### 4.5 运行时应用机制

#### 4.5.1 设计边界

Android 公开 API 不能把任意 ARGB 直接写到已编译的 `?attr/colorPrimary`。B-safe 采用两条明确通道：

1. **静态主题通道**：Layer 1 的 `Theme.WebHTV` 提供正确默认值和 Material 组件 attr 解析。
2. **受控组件通道**：`ThemeBinder` 在视图创建后，把 resolver 结果绑定到已迁移的标准组件、状态列表和透明度表面。

不允许：

- 反射修改 `Resources`、`AssetManager`、`ThemeImpl`。
- 为任意 hue 建成百上千套编译期主题。
- 让 binder 遍历播放器视频 Surface、TextureView、海报原图或弹幕运动视图。

#### 4.5.2 ThemeBinder 规则

新增 `ThemeBinder`，但仅作为 `ThemeController` 内部实现，不能让远程 Web 或内容源调用。

角色解析顺序：

1. 显式 `app:webhtvThemeRole="..."` 或内部 tag。
2. 标准组件类型和样式：MaterialButton、MaterialToolbar、BottomNavigationView、TabLayout、MaterialCardView、TextInputLayout、BottomSheet、DefaultTimeBar 等。
3. 已迁移组件的默认角色识别：将当前默认色与 baseline `ThemeTokens` 比较；命中唯一角色时替换。
4. 命中不唯一或属于 `player`/`media`/`logo`/`rating`/`custom` 子树时不动。

绑定动作：

- TextView：按角色设置 `ColorStateList`，正文/次要文字/错误/成功/警告分开。
- MaterialButton：按 filled/tonal/outlined/text 角色设置 background tint、text color、icon tint、stroke。
- MaterialCardView/普通卡片：设置卡片背景、描边和焦点/选中状态。
- BottomNavigationView/TabLayout：只替换语义选中/未选中色，不改变布局与导航逻辑。
- Drawable/background：仅在语义形状 drawable 上替换色值；视频、海报、图片资源不处理。
- Dialog/BottomSheet：处理 surface、outline、按钮和 scrim；dialogOpacity 只影响弹窗外壳，不影响文字 alpha。
- 焦点：使用 `ColorStateList`，支持 focused/selected/activated/disabled 状态，避免按住/遥控导航时丢色。

遍历和动态视图：

- Activity 在 `setContentView()` 后绑定一次，在 `initView()` 完成后补一次；同一个 view 以 signature 去重，避免重复调色。
- Fragment 通过 Activity 根树的 hierarchy 变化自动获得绑定。
- RecyclerView 使用 `addOnChildAttachStateChangeListener` 绑定新 child；不得要求修改 88 个 Adapter。
- ViewGroup 如需 hierarchy 监听，必须包装并转发原 listener，不得覆盖已有监听。
- binder 只处理主线程；禁止在播放器 `onFrame`、Surface 回调或解码线程执行。

#### 4.5.3 Profile 应用事务

编辑器点击“应用”时：

```text
draft copy -> 结构校验 -> resolver/对比度校验 -> 原子写入 profile + last-good
  -> 写 theme_color 兼容镜像 -> 写 theme_mode -> RefreshEvent.theme()
```

任何一步失败，不允许只写一半；当前页面保持旧 profile，并显示可理解错误。点击“取消”只丢弃 draft，不修改偏好；点击“恢复默认”删除 profile 并恢复 Layer 1 默认，保留壁纸设置。

### 4.6 编辑器 UX

设置入口继续放在“外观”，摘要显示“默认 / 预设名 / 自定义名 + 当前模式”。编辑器建议拆成四个区域：

1. **模式与预设**：跟随系统/浅色/深色；8 个预设；从图片/壁纸取 seed。
2. **主色与高亮**：primary、primaryContainer、secondaryContainer、focus。
3. **表面与文字**：surface、surfaceContainer、surfaceContainerHigh、onSurface、onSurfaceVariant、outline。
4. **状态与透明度**：error、success、warning、scrim/dialog/overlay opacity。

交互要求：

- 顶部实时预览至少包含一个 filled button、tonal button、正文、次要文字、卡片、选中 chip、输入框和一个 Dialog。
- 浅色/深色可分别编辑，未覆盖项明确显示“继承默认”。
- 颜色选择同时支持色相面板和精确 hex 输入；非法输入不写 draft。
- 低对比度实时显示原因；应用时自动修正 `on*`，不弹出无法理解的原始异常。
- 窄屏滚动、横屏 TV 遥控、返回取消、进程被杀后 draft 不污染已应用 profile。

### 4.7 Layer 2 验收

#### 自动化门禁

```bash
bash scripts/check_ui_tokens.sh --strict
./gradlew :app:testMobileArm64_v8aDebugUnitTest :app:testLeanbackArm64_v8aDebugUnitTest
./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac
bash scripts/build_arm64_debug_install.sh --flavor mobile --serial 192.168.50.3:5559
bash scripts/build_arm64_debug_install.sh --flavor leanback --serial 192.168.50.3:5559
```

新增测试至少覆盖：

- `ThemeProfileCodecTest`：合法/缺字段/未知字段/危险字段/越界值/错误类型。
- `ThemeProfileValidatorTest`：颜色格式、模式、透明度范围、空 profile、损坏 profile、last-good。
- `ThemeResolverOverrideTest`：16 槽逐项覆盖、on 色派生、light/dark 独立、seed fallback、对比度修正。
- `ThemeProfileMigrationTest`：`theme_color=-1/0/自定义`、`wall_color`、旧偏好和恢复默认。
- `ThemeBinderRoleTest`：button/card/text/dialog/focus 角色映射，播放器子树跳过，signature 去重。
- `ThemeWebBridgeTokenTest`：原 13 字段不缺失，新增 opacity 字段格式稳定。
- `BackupPreferenceFilterTest`：`theme_mode` 和 profile 键可备份/恢复。
- `ThemeControllerContractTest`：取消不写入、应用原子写入、写入失败不 refresh。

#### 设备功能矩阵

每一行必须在实际设备/模拟器执行并记录结果；未满足的环境要明确标为残余风险，不得以 JVM 测试代替。

| 场景 | 操作 | 通过标准 |
| --- | --- | --- |
| 默认 | 无 profile 冷启动 | 与 Layer 1 默认一致，无 Material baseline 紫 |
| 预设 | 每套预设应用一次 | 按钮/卡片/文字/高亮同步变化，无不可读页面 |
| 16 槽 | 每槽单独改一个明显色值 | 只影响预期角色，其他页面不出现彩虹分叉 |
| 浅深模式 | 分别覆盖 light/dark | 切换模式后使用对应槽，不需要重启 |
| 透明度 | 使用上/下界与中间值 | Dialog 文字仍可读，scrim/overlay 行为符合预期 |
| 非法输入 | 超界 alpha、低对比度、损坏 JSON | 不改变当前主题，错误可理解，进程不崩溃 |
| 取消 | 修改 draft 后取消 | 当前页面和持久化值不变 |
| 应用 | 点击应用 | 原子写入并统一重建，页面不闪白，返回后保持 |
| 重启 | 强杀后启动 | 主题持久化，无 fallback 日志或可见跳变 |
| 备份 | 备份/恢复 profile | 主题与模式恢复，壁纸不丢失 |
| 动态 UI | 进入列表、切分类、弹 Dialog、BottomSheet | RecyclerView 新 child 也使用新 token |
| TV 遥控 | 焦点移动、选择、返回 | focus/selected/disabled 状态可见且无丢色 |
| WebHome | Eclipse 首页/详情/reader/manage | 只读快照颜色同步；远程主题失败只回退页面 |
| 播放回归 | VOD/直播进入、暂停、换线、字幕、返回 | 播放内核、画面、音轨、字幕、性能行为不变 |
| 资源回收 | 连续 30 次应用/取消 | 无 FATAL、无持续增长的 Activity/View 引用 |

#### 性能门禁

- 典型 Activity 首次绑定 p95 目标 `< 8 ms`，且每个 view signature 只绑定一次；若设备基线不满足，记录基线并证明不超过基线 10%。
- 不新增启动期全量解析 49 色 JSON；profile 只解析一次并缓存，主题切换时复用已校验对象。
- 播放器播放期间 binder 调用次数必须为 0（主题变化导致的常规 Activity recreate 除外）。
- 连续 30 次主题应用后，内存和 View 引用回到基线范围，不保留旧 Activity。

Layer 2 DoD：

- B-safe 16 槽、预设、取消/应用/重置、浅深模式、持久化、备份和 Web 快照全部可用。
- 测试、编译、strict 检查、mobile/leanback 设备矩阵通过。
- 播放器画面、解码、字幕、音轨、倍速和性能未出现可复现回归。
- 每个阶段独立提交及 recovery tag；回滚不依赖数据库迁移。

---

## 5. 关键文件与实施顺序

### 5.1 Layer 1 预期改动面

| 文件/目录 | 变更 |
| --- | --- |
| `app/src/mobile/res/values/styles.xml` | `Theme.Base` 继承 WebHTV Mobile |
| `app/src/leanback/res/values/styles.xml` | `Theme.Base` 继承 WebHTV TV，移除白色 primary |
| `app/src/main/res/values/webhtv_styles.xml` | 补齐 Material 角色映射 |
| 语义 attr 尚未覆盖的 layout/drawable/style | 按 allowlist 分类迁移 |
| `app/src/main/java/com/fongmi/android/tv/theme/ThemeController.java` | 统一 night 判断与时序契约 |
| 相关 source/contract tests | 固化继承链、Dialog、night 和 token 同源 |

### 5.2 Layer 2 预期新增

建议新增或调整：

- `ThemeProfile.java`
- `ThemeProfileCodec.java`
- `ThemeProfileValidator.java`
- `ThemeProfileStore.java`
- `ThemeResolver.java`（有 profile 重载）
- `ThemeBinder.java` / `ThemeRole.java`
- `ThemePreviewView.java`
- `ThemeEditorDialog.java` / `ThemeColorPickerDialog.java`
- mobile/leanback 的 `AppearanceDialog`、`ThemeDialog`
- `ThemeWebBridge.java`、`Setting.java`、`Backup.java`
- 对应单元测试、源码契约测试和设备验收记录

不建议直接恢复历史分支中的完整 TweakCN/导入导出实现。历史实现可作为校验器、颜色转换和回退逻辑的参考，但必须按本文件 16 槽和播放器豁免边界裁剪。

---

## 6. 风险、缓解与回滚

| 风险 | 影响 | 缓解 | 回滚 |
| --- | --- | --- | --- |
| `Theme.Base` 切换改变默认视觉 | 用户看到整体主题变化 | Layer 1 先单独设备验收；保留 API 31+ DynamicColors | 回退 L1 提交 |
| 部分 attr 未映射到 token | 页面混合 Material 默认色 | 补 tag/role/source test；strict 检查 | 逐类回退资源迁移 |
| 任意色无法写回 attr | 自定义只覆盖部分组件 | 受控 binder + Layer 1 语义化；不承诺反射修改平台资源 | 关闭 binder，保留默认主题 |
| Binder 误伤播放器/海报 | 画面、字幕、性能回归 | functional subtree 豁免；禁止在 frame path 调用 | 关闭 binder 或回退 L2B |
| 自定义低对比度 | 文字不可读 | 自动派生 on 色、clamp、组合对比度、实时 preview | 恢复 last-good/default |
| profile 损坏/写入失败 | 启动回退或主题丢失 | 原子 commit、last-good、schema 校验 | 删除 profile，使用 `theme_color` |
| 透明度打开过宽 | 内容穿透、焦点不清 | 槽位范围限制、Dialog 最小 0.70 | 恢复默认 opacity |
| 远程 Web 反写原生 | 安全边界破坏 | 继续只读快照，禁止调用 ThemeController | 回退 Web 快照字段 |
| TV 焦点状态丢失 | 遥控不可用 | ColorStateList 覆盖 focused/selected/activated/disabled | 回退 focus 绑定，保留 Layer 1 |

统一回滚原则：

- Layer 1、Layer 2A、Layer 2B、Layer 2C 各自独立提交和 recovery tag。
- 回退 Layer 2B 不删除 profile；旧版本忽略未知偏好键并继续读取 `theme_color`。
- 回退 Layer 1 前先确认没有生产页面直接依赖 `?attr/colorSurfaceContainer*` 的新映射；如存在，回退该资源迁移提交而不是强改用户设置。
- 任何检查失败、scope 越界或播放器回归未解决时，不提交、不打“完成”标签。

---

## 7. 实施任务卡

### 4.8 Layer 2A 实施记录（2026-09-21）

- 任务：`L2A-THEME-PROFILE-20260921`。交付 B-safe 16 槽的数据层与解析层，**不接线编辑器、不改变任何现有页面视觉**；Layer 2B 才负责把 profile 读取接入 `ThemeController` 并绑定视图树。
- 新增文件：
  - `ThemeProfile.java`：`SCHEMA_VERSION=2`、`format/id/name/mode/seedSource/seedColor` 与 `light`/`dark` 两个 `SlotSet`，SlotSet 严格只含 13 个颜色槽 + 3 个透明度槽；`copy()` 深拷贝，`null` 表示继承内置/seed。
  - `ThemeProfileCodec.java`：Gson 编解码 + 128 KiB 字节上限 + 嵌套深度上限 + 危险键（script/css/url/path/intent 等）拒绝；因 Gson 不执行字段初始化，解析后显式回填缺省 `format/schemaVersion/light/dark`。
  - `ThemeProfileValidator.java`：schema/format 校验、模式与 seed 归一化、颜色只允许**不透明** `#RGB`/`#RRGGBB`、透明度范围 `scrim 0.00–0.85`、`dialog 0.70–1.00`、`overlay 0.05–0.60`（含 `NaN/Inf` 拒绝）、自定义 seed 必须带颜色。
  - `ThemeProfileStore.java`：v2 独立键 `theme_profile_v2_json` / `theme_profile_v2_last_good` / `theme_profile_v2_schema`；`apply()` 原子写入并同步 `theme_color` 兼容镜像；`load()` 损坏时依次回退 last-good 与旧偏好；`migrateLegacy()` 只从 v1 读取 mode/seedSource/seedColor 与 primary/surface/onSurface/outline/error 五个安全槽。
  - `ThemeTokens` 增加 `dialogOpacity` 分量（默认两套 palette 均为 `1.0f`），使 dialog 透明度成为不可变 token 契约而不是散落常量。
  - `ThemeResolver.resolve(..., ThemeProfile, systemDark)`：顺序固定为 内置 token → seed 派生 → 该模式 13 个颜色覆盖 → 派生 on*/容器 → 3 个透明度 → 对比度校验；扩展 `lastDiagnostic()` 区分 `default` / `seed` / `profile` / `last-good` / `fallback`。
- 恒等性保证（本轮最关键的不回归约束）：空 profile 的解析结果与不传 profile **逐字段相同**——只有用户真正覆盖的槽才触发派生，`onPrimary`/`onPrimaryContainer`/`onSecondaryContainer`/`onError`/`onSuccess`/`onWarning`、`onSurface`/`onSurfaceVariant`/`outline`/`focus`、`surfaceDim/Bright/ContainerHighest` 均按“相关槽是否被覆盖”门控，种子派生路径不变。
- 对比度纠正：用户把文字/描边设到不可读时自动改为黑或白（≥4.5:1，描边/焦点 ≥3:1），不拒绝保存；`surface`/`surfaceContainer`/`surfaceContainerHigh` 相等时按 6% 明度做最小层差，避免面板糊成一片。
- 播放器与品牌豁免：profile 只能触达 16 槽，`colorPlayerControl*`、`colorPlayerScrim`、`colorHealth*`、`colorOverlayDark`、`focusScale` 在所有测试中保持与默认完全一致。
- 备份：`Backup.APP_PREFS` 增加 `theme_mode` 与 profile 三键，随“设置”同步走，不随 config/spider 同步走。
- 自动化证据（本轮，mobile 与 leanback 各自独立执行）：
  - `:app:testMobileArm64_v8aDebugUnitTest --tests 'com.fongmi.android.tv.theme.*' --tests 'com.fongmi.android.tv.bean.BackupPreferenceFilterTest'` → 69 项 / 0 失败 / 0 错误。
  - `:app:testLeanbackArm64_v8aDebugUnitTest` 同范围 → 69 项 / 0 失败 / 0 错误。
  - `bash scripts/check_ui_tokens.sh --strict` → `violations=0 legacy=0`、38 组对比度 0 失败（min=4.28）、`hex_layouts/hex_drawables/hex_colors` 全 0、`allowlisted=191`。
  - 新增测试类：`ThemeProfileCodecTest`（7 项）、`ThemeProfileValidatorTest`（8 项）、`ThemeProfileMigrationTest`（7 项）、`ThemeResolverOverrideTest`（12 项），并在 `BackupPreferenceFilterTest` 增加外观键用例。
- 边界与残余风险：
  - 本轮**未**修改 `ThemeController`、任何 Activity/Dialog/布局/资源，因此运行时页面视觉与 Layer 1 完全一致；profile 只有在 Layer 2B 接线后才会影响界面。
  - 叠加在壁纸上的透明度仍沿用 Layer 1/H2 的既有取舍：极亮壁纸下浅色模式次要文字无法给出全局 4.5:1 硬保证。
  - 未覆盖 Android 12+ Dynamic Colors 与显式 profile 的优先级（当前设备 API 28）。
- 回滚锚点：回退 Layer 2A 提交即回到 Layer 1 的纯 `theme_color` 路径；v2 偏好键成为孤儿数据，旧版本会忽略，不涉及数据库迁移。

---

### 4.9 Layer 2B 实施记录（2026-09-22）

- 任务：`L2B-THEME-BINDER-20260921`。交付受控 `ThemeBinder` 与运行时应用通道：`ThemeController` 读取 v2 profile，生成 baseline/active 两份快照，并把差异绑定到已创建的视图树。
- 新增文件：`ThemeRole.java`（19 个角色；13 个用户槽 + resolver 派生的 6 个 on* 角色；`webhtv:<role>` 显式标记；播放器/媒体/健康/品牌角色不可表达）、`ThemeColorIndex.java`（基线颜色→角色索引、歧义保护、状态骨架）。
- 接入点：mobile/leanback `BaseActivity.onCreate()` 在 `setContentView()` 后与 `initView()` 后各绑定一次；`BaseBottomSheetDialog` 通过 `ThemeController.bindDialog()` 覆盖 33 个 BottomSheet；`RecyclerView.addOnChildAttachStateChangeListener` 绑定新 child；根视图布局变化且子视图数量变化时补绑定一次。
- 恒等与性能：`baseline.equals(active)` 时直接返回（默认路径零遍历、零改写）；真实 profile 下 dev3 首页 `walked=49 bound=10 costMs=6–7ms`，满足 p95 < 8ms 门禁。
- 安全边界：只改写“与冻结基线精确同色且角色唯一或共享角色一致”的颜色；状态化 ColorStateList 通过**公开构造器** `new ColorStateList(int[][], int[])` 重着色并保留各状态 alpha（pressed/disabled/focused 语义不丢失）；`player/media/logo/rating/karaoke/wall/subtitle/danmaku/surface/texture/video` 子树与 `webhtv:ignore` 显式跳过；不反射改 Resources，不使用隐藏 API。
- 设备验证（dev3 `192.168.50.3:5559`，API 28，`build_arm64_debug_install.sh --flavor mobile`）：
  - 无 profile 冷启动：无 binder 日志、画面与 Layer 1 一致、`FATAL EXCEPTION=0`。
  - 注入 13 槽 profile 冷启动：`baselineOverridden=true`、`bound=10`、无崩溃；分类列表滚动、右上菜单/对话框打开、返回均正常。
  - 覆盖测试后恢复默认 profile，确认不残留自定义状态。
- 本轮修复的两个真实设备缺陷（JVM 单测无法发现）：
  1. `Class.getRecordComponents()` 在 Android API < 33 不存在，曾导致 API 28 启动崩溃（`NoSuchMethodError`）。已改为内容哈希签名并加源码契约禁止该反射路径。
  2. `ColorStateList.createFromXml()` 需要平台 `XmlBlock$Parser`，自建 pull parser 会抛 `ClassCastException`。已改用公开构造器重建。
- 自动化证据：mobile/leanback `--tests 'com.fongmi.android.tv.theme.*'` 全部通过；`scripts/check_ui_tokens.sh --strict` → `violations=0 legacy=0`、38 组对比度 0 失败。
- 残余风险：本轮只验证了 mobile 端首页/列表/对话框；leanback 设备场景、连续 30 次切换的内存回收、播放器内嵌面板与 WebHome 同步仍属 Layer 2C/2D 验收项。
- 回滚锚点：回退 Layer 2B 提交即恢复到 Layer 1 的静态主题；已保存的 v2 profile 不被删除，旧版本忽略该键。

---

### 4.10 Layer 2C 实施记录（2026-09-22）

- 任务：`L2C-THEME-EDITOR-20260921`。交付 16 槽主题编辑器与实时预览，替换原 14 色圆点对话框。
- 新增共享实现（`app/src/main/java/com/fongmi/android/tv/theme/`）：
  - `ThemeEditor.java`：draft 深拷贝、逐槽 `set`/`clear`/`valueOf`、模式与 seed 归一化、`preview()`（只解析不落盘）、`apply()`/`reset()` 唯一写盘入口；非法颜色与越界透明度在进入 draft 前即被拒绝。
  - `ThemePreviewView.java`：纯代码构建的实时预览（filled/tonal 按钮、正文、次要文字、卡片、Dialog 面板、透明度摘要）与完整编辑面板（13 颜色槽 + 3 透明度滑杆 + 每槽“恢复继承”），mobile/leanback 共用。
  - `ThemeColorPickerDialog.java`：HSV 三滑杆 + 精确 hex 输入；确认前用 `ThemeProfileValidator.normalizeColor` 校验，非法输入不关闭对话框也不回调。
- 两端接入：mobile/leanback `ThemeDialog` 改为编辑器（模式/预设行、浅深切换、面板、应用/取消/恢复默认），`AppearanceDialog` 不再直写 `theme_color`，应用后经既有 `RefreshEvent.theme()` 统一重建。
- 事务性：取消只丢弃 draft；应用走 `ThemeProfileStore.apply()` 原子写入并同步 `theme_color` 镜像；恢复默认走 `ThemeProfileStore.reset()`。
- 新增字符串资源 46 条（默认 `values/` 英文，与仓库既有 base 语言策略一致）；新增 `ThemeEditorContractTest` 11 项覆盖逐槽往返、透明度边界、非法值拒绝、浅深独立、预览不落盘、旧 14 色路径已移除。
- 自动化证据：mobile 与 leanback `--tests 'com.fongmi.android.tv.theme.*'` 全部通过；`:app:compileMobileArm64_v8aDebugJavaWithJavac` 与 `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` 均通过；`scripts/check_ui_tokens.sh --strict` → `violations=0 legacy=0`、38 组对比度 0 失败。
- **未完成项（本阶段唯一缺口）**：编辑器 UI 的真机交互验收未完成。dev3 `192.168.50.3:5559` 上的首页被内置 WebHome 接管，按 `nav_position=1` 启动设置后界面仍由 Web 层渲染，原生“外观→主题色”入口在自动化点击路径下不可达（多次尝试均落到 WebHome 或相邻设置子页）。因此以下矩阵行仍为**未验证**：模式/预设切换、单槽改色、透明度上下界、取消不改动、应用后统一重建、重启保持、TV 遥控焦点。
- 残余风险：
  - 新增字符串只有 `values/` 英文 base，`values-zh-rCN`/`values-zh-rTW` 走系统回退；建议随 Layer 2D 一并补齐中文翻译。
  - 颜色选择器与滑杆布局在小屏手机上的滚动/触控体验未实测。
- 回滚锚点：回退 Layer 2C 提交即恢复原 14 色圆点 `ThemeDialog`；v2 profile 数据不受影响，Layer 2B 的运行时绑定继续可用。

---

### 4.11 Layer 2D 实施记录（2026-09-22）

- 任务：`L2D-THEME-CLOSURE-20260922`。完成 Web 只读快照扩展、备份键闭环与文档收口；设备矩阵未完成（见下）。
- Web 只读快照：`ThemeWebBridge.snapshotJson()` 在保留原 13 个字段与原 key 名不变的前提下，追加 `scrimOpacity` / `dialogOpacity` / `overlayOpacity` 三个两位小数字段。`HomeWebController` 通过既有 `root.add("tokens", ...)` 通道把快照交给内置与远程 WebTheme 页面，仍为**只读**，不新增任何 Web→native 写回能力。
- 新增 `ThemeWebBridgeTokenTest`（5 项）：原 13 字段完整保留、`focusScale` 格式不变、三个透明度字段存在且格式稳定（两位小数、无本地化分隔符）、取值与 `ThemeTokens` 一致、快照不泄漏 profile/seed 内部结构（不含 seed/wallpaper/profile/webhtv-theme 字样）、null 输入回退 light。
- 备份兼容：`Backup.APP_PREFS` 已含 `theme_mode`、`theme_profile_v2_json`、`theme_profile_v2_last_good`、`theme_profile_v2_schema`（Layer 2A 落地），随“设置”同步走；`BackupPreferenceFilterTest` 在 Layer 2A 与 Layer 2D 均覆盖。
- 自动化证据：mobile 与 leanback `--tests 'com.fongmi.android.tv.theme.*' --tests 'com.fongmi.android.tv.bean.BackupPreferenceFilterTest'` 全部通过；`scripts/check_ui_tokens.sh --strict` → `violations=0 legacy=0`、38 组对比度 0 失败。
- **未完成项（Layer 2 收口的唯一缺口）**：4.7 节设备功能矩阵未执行。dev3 `192.168.50.3:5559` 的首页由内置 WebHome 接管（`web_home_fullscreen`/`web_home_theme_enabled` 两种偏好组合、`nav_position=1/2/4` 直接启动、底部导航点击均已尝试），原生“外观→主题色”入口在自动化路径下持续不可达；因此编辑器交互、WebHome 快照实测、备份恢复实测、重启保持与连续 30 次切换均**未验证**。
- 残余风险：
  - 新增编辑器字符串仅提供 `values/` 英文 base，中文本地化随设备验收一并补齐。
  - 设备端未验证意味着 Layer 1–2 的“无播放回归”结论仍只有 Layer 1 的历史证据，Layer 2 绑定对播放器路径的实际影响未在设备上确认。
- 回滚锚点：回退 Layer 2D 提交仅撤销 Web 快照的三个附加字段；旧 Web 页面按 key 读取，缺失即忽略，无数据迁移。

---

### 4.12 导航入口修复与设备验证（2026-09-22）

- 任务：`NAV-THEME-ENTRY-20260922`。修复 cold start 时底部导航菜单项未初始化、无法稳定进入原生“外观→主题色”的阻塞问题。
- 根因：`HomeActivity` 的导航项在 `menu_nav.xml` 中默认 `visible=false`，而 `setNavigation()` 原先只在 `onNewIntent()`、恢复位置或 `onResume()` 的可见性差异分支调用；全新冷启动路径不会执行该初始化。另有 `onNewIntent()` 未调用 `setIntent()`，`checkAction(getIntent())` 可能继续读取旧 Intent。
- 修复：`initView()` 在设置导航选中监听后显式执行 `setNavigation()`；`onNewIntent()` 先 `setIntent(intent)` 再处理动作；`savedInstanceState == null` 且 Intent 携带 `nav_position` 时，在 `initFragment()` 后立即执行一次 `checkAction(getIntent())`。
- 设备证据（dev3 `192.168.50.3:5559`，mobile arm64 debug，覆盖安装 `Success`）：
  - `am start -n .../HomeActivityCurrent --ei nav_position 1` 冷启动进入原生设置页；`外观与语言 → 主题色彩` 可打开，编辑器、预设、13 颜色槽、3 透明度槽、预览和 Apply/Cancel/Reset 均可见。
  - 选择 `Blue` 预设后 `theme_profile_v2_json` 与 `theme_profile_v2_last_good` 写入 `seedSource=custom`、`seedColor=#0B57D0`；应用后进程无 `FATAL EXCEPTION`。
  - Light `Primary=#FF0000` 可写入；点击 `Cancel` 后持久化 profile 未改变；点击 `Apply` 后写入，强杀重启后重新打开编辑器仍显示 `#FF0000`。
  - `Dialog opacity` 上界 `1.0` 可写入并持久化；Dark 编辑模式可切换，Dark `Primary=#00FF00` 写入后强杀重启仍保留。
  - 测试结束执行 `Reset to default`，profile 回到 `seedSource=none`、槽位为 `null`；设备 `FATAL EXCEPTION=0`。
  - 非法输入：`Primary` 选择器中输入 `#GGGGGG` 并点击 `Use this hex` 后不关闭、不写入，保留 `Enter #RRGGBB` 提示；随后取消编辑器，持久化 profile 仍为默认值。设置页 TAB/方向键焦点可连续移动经过点播、直播、壁纸、增强、TMDB、AI、个性、播放、去广告、弹幕、字幕、无痕、DoH、缓存、恢复和版本行，未出现崩溃。
- 验证边界：本次修复的是主题编辑器入口和编辑器核心写盘/取消/重启/非法输入路径，**不等于完成 4.7 节完整矩阵**。TV 遥控焦点颜色、WebHome 快照、备份恢复、动态 UI、播放回归和连续 30 次应用/取消仍需继续执行。
- 回滚锚点：回退本任务的 3 行 `HomeActivity` 改动即恢复原导航初始化行为；profile 数据格式和 Layer 2A–2D 逻辑不变。

---

### 4.13 主题色彩实际生效范围修复（2026-09-23）

- 任务：`THEME-COLOR-BASELINE-20260923`。用户复测反馈“主题色彩只有站点选中弹出框有实际作用”，本节记录定位、修复、覆盖率清单和验证。
- 根因 1（决定性）：`ThemeController.applyFromPreferences()` 把 binder 的比较基线也算成了 `baseline = resolveWith(null)`，而 `resolveWith` 会读取 `theme_color`/`wall_color` 并派生 seed。页面上的 `?attr/color*` 实际解析自编译期静态 `@color/webhtv_color_*`（`ThemeTokens.light()/dark()`），因此只选预设 seed 时 `baseline.equals(current)` 成立，`ThemeBinder.bind()` 直接返回、整棵原生视图树零改写；只有站点弹框走 `Setting.getDynamicColor()` + `MaterialColors.getColorRoles` 的旧直连通道，所以只有它变色。设备实测（dev3 `192.168.50.3:5559`）确认：`theme_color=0xFF00897B`、槽位全空时，`FollowingActivity` 的按钮填充仍精确等于基线 `#0B57D0`、正文精确等于 `#1A1C1E`。
- 修复 1：新增 `ThemeController.frozenPalette()`，`baseline` 改为“编译期冻结调色板”（`ThemeSeed.NONE` + 无 profile），不再经过 `theme_color`/`wall_color`；`resolveWith()` 的 seed 语义（`current`）不变，因此 `theme_color != -1` 时 `current != baseline`，binder 恢复工作。`hasProfileOverrides()` 的注释同步改为“与冻结基线不同”。
- 根因 2：binder 只改写 `TextView` 文字、`ImageView` tint、`MaterialCardView` 填充/描边和 `GradientDrawable` 背景。`MaterialButton`（含 Filled/Tonal/Outlined）的填充在 `backgroundTintList`、描边在 `strokeColor`、图标在 `iconTint`，三条通道都不在覆盖范围内，所以“检查更新/继续看”这类主按钮永远停在编译期蓝色。
- 修复 2：`ThemeBinder` 新增 `bindBackgroundTint(view)`（覆盖所有用 `app:backgroundTint` 填充的视图：Material 按钮族、Chip、FAB、输入框容器）与 `bindButton(MaterialButton)`（描边 + 图标 tint）。两者沿用同一条“仅当颜色精确等于基线角色色且共享角色取值一致”的判定，无 profile/seed 时 binder 仍然整体 no-op。
- 新增/更新测试：
  - `ThemeBinderContractTest#seedDerivedTokensStayMappableFromTheFrozenBaseline`：seed 派生的 `current` 必须能从冻结基线映射出替换色。
  - `ThemeBinderContractTest#aSeedDerivedBaselineWouldSilentlyDisableTheBinder`：锁定“用 seed 派生基线就什么都映射不到”的旧缺陷形态。
  - `ThemeBinderContractTest#presetSeedsMustResolveToTheirOwnPaletteInsteadOfFallingBack`：6 个预设 seed 均不得静默回退到冻结调色板（`lastDiagnostic()` 不得以 `fallback` 开头）。
  - `ThemeBinderContractTest#binderRewritesTheMaterialButtonAndBackgroundTintChannels` + `theme_binder` 源码契约：锁定按钮通道修复，并钉住 `activity_following.xml` 用 `app:backgroundTint="?attr/colorPrimary"` 的真实契约。
  - `ThemeControllerContractTest`：断言基线必须是 `frozenPalette()`，且不得再出现 `baseline = resolveWith(`；同时修复 `everyActivityBindsTheThemeTreeTwice` 的过期断言（`584b64514` 改成 `View content = ...` 后旧断言失效，属既有红灯）。
- 自动化证据：`./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests 'com.fongmi.android.tv.theme.*'` → BUILD SUCCESSFUL（含上述新增用例；启动时为 83 项中 1 项失败的既有红灯已消除）。
- 设备证据（dev3 `192.168.50.3:5559`，API 28，mobile arm64 debug 覆盖安装）：在 `BaseActivity` 体系内的 `HistoryActivity` 上做三组对照（默认 / 仅 teal seed / 槽位探针 `onSurface=#FFFFFF`）：
  - 槽位探针下，默认主题中恰好 `#1A1C1E`（基线 `onSurface`）的 4008 个像素变为纯白，`onSurfaceVariant` 色像素由 31662 降到 14228 —— 与“用户槽位现在真正改写原生文字色”的预测完全一致。
  - 仅 teal seed 下，3094 个 `onSurface` 像素离开基线值 —— seed 预设现在也能驱动 binder（修复前该场景为 0 改写）。
  - 未注册 `WebHTV` 主题的 `FollowingActivity` 三组对照均无变化，证明绑定范围确实由 `BaseActivity` 决定而非噪声。
  - 测试结束后设备 `theme_color` 与 profile 已恢复到实验前原值，未留下探针配置。
- **实测覆盖清单（回答“主题色彩会应用到哪些位置”）**：
  - 已覆盖：继承 `mobile/leanback BaseActivity` 的 Activity 视图树（首次 + `initView` 后两次绑定，子树数量变化时补绑）、`BaseBottomSheetDialog` 系列、`TextView` 文字/提示、`ImageView` tint、`MaterialCardView` 填充与描边、`GradientDrawable` 背景、`backgroundTint` 族（按钮/Chip/FAB/输入框）、`RecyclerView` 动态 child，以及状态栏/导航栏颜色。
  - 直接读 token 而不依赖 binder：站点弹框（旧 `theme_color` 通道）、`AppearanceDialog`/`ThemeDialog`、TMDB cast/video presenter、`CollectFragment`/`SearchFragment`、WebHome 只读快照（`ThemeWebBridge.snapshotJson`）。
  - **未覆盖（待办）**：`AlertDialog`/`MaterialAlertDialogBuilder`/`LightDialog` 家族（`BaseAlertDialog` 的 59 个子类、121 个直接使用文件）从不调用 `bindDialog`，因此设置类弹窗仍用编译期静态色；`FollowingActivity` 不继承 `BaseActivity`，整页无绑定；`StateListDrawable`/`RippleDrawable`/`ShapeDrawable` 等非 `GradientDrawable` 背景、`Switch`/`CheckBox` 等控件通道、远程 Web 主题页面（由页面自身 CSS 决定）也尚未覆盖。
- 残余风险：API 31+ 上 `enableDynamicColor()` 仍会在 `theme_color != -1` 时用平台 DynamicColors 覆写 Activity 主题，此时视图颜色不等于编译期基线，binder 会保持 no-op（seed 仍由 DynamicColors 生效，但两条通道未统一）；本轮未在 API 31+ 设备验证。
- 回滚锚点：回退本任务即恢复“基线被 seed 污染 + 按钮通道不覆盖”的行为；不涉及 profile 数据格式、偏好键或迁移。

---

### 4.14 弹窗家族主题绑定（2026-09-23）

- 任务：`THEME-DIALOG-BIND-20260923`。补齐 4.13 节列出的最大缺口——`AlertDialog`/`LightDialog` 家族此前从不调用 `bindDialog`，设置类弹窗始终使用编译期静态色。
- 接入的两个收口点：
  - `LightDialog.apply(AlertDialog)`：MaterialAlertDialog 在 `show()` 期间由 `AlertController` 安装正文和按钮，该方法只负责注册 binder root，随后由 `ThemeBinder` 的 descendant-count watcher 在子视图出现后补 walked 一次。
  - `LightDialog.createInternal(...)`：已有的 `setOnShowListener` 中追加绑定（该 listener 原本只做 `applyWindow`/触碰优化）。
  - `BaseAlertDialog.onCreateDialog(...)`：`getBuilder().create()` 之后绑定。**故意不覆盖 dialog 自身的 `OnShowListener`**，因为 `MaterialAlertDialogBuilder` 已占用它做背景 inset；源码契约测试禁止在此处出现 `setOnShowListener`。
- 顺带修复：`LightDialog` 标题原为硬编码 `Color.parseColor("#202124")`，永远不参与主题；改为 `ThemeController.current().colorOnSurface()`。五个 dialog 按钮色 selector（`dialog_primary_button_bg`/`_text`、`dialog_outlined_button_bg`/`_text`/`_stroke`）经核对已全部指向 `@color/webhtv_color_*`，因此 binder 的精确匹配 + 状态列表重建可以直接作用。
- 新增测试：`ThemeBinderContractTest#alertDialogFamiliesBindThroughTheSharedDialogChannel` 锁定两个 LightDialog 收口点、禁止标题硬编码 hex、要求 `BaseAlertDialog` 绑定且不碰 `setOnShowListener`，并逐项校验五个按钮 selector 仍走 webhtv token。
- 自动化证据：mobile 与 leanback `--tests 'com.fongmi.android.tv.theme.*'` 均 BUILD SUCCESSFUL；`:app:compileLeanbackArm64_v8aDebugJavaWithJavac` 通过（`LightDialog`/`BaseAlertDialog` 为共享主模块代码，必须双 flavor 编译）。
- 设备证据（dev3 `192.168.50.3:5559`，API 28，mobile arm64 debug 覆盖安装）：以 `HomeActivityCurrent --ei nav_position 1` 冷启动三组对照（各 30s）：
  - 噪声基线：default-vs-default 仅 320 个变化像素（壁纸/星点动画）。
  - teal seed：9545 个变化像素；槽位探针（`onSurface=#FFFFFF`、`surfaceContainerHigh=#000000`）：22498 个变化像素。
  - 角色精确相关：default 截图中 7870 个 `#181A1C`（`?attr/colorOnSurface` 渲染值）像素在探针下变为 `#ECECEC`。
  - 测试结束已把设备 `theme_color`/profile 恢复到进入测试前的原值。
- **更正（2026-09-24）**：上述设备对照测得的页面经复看截图确认是 `HomeActivityCurrent` 的**设置内容页**，不是 `ConfigDialog`/`LinkDialog` 等对话框窗口。该页本来就继承 `BaseActivity`，所以这组像素变化**不能**作为“弹窗绑定已生效”的证据；它只证明了 Activity 树绑定。4.14 的代码改动本身仍由源码契约测试覆盖，但其真实设备可见性由 4.16 节重新取到（`LinkDialog` 面板与文字）。此处保留原文并更正结论，避免后续引用错误归因。
- 覆盖变化：设置类弹窗（`BaseAlertDialog` 的 59 个子类）与共享 `LightDialog` 路径现已纳入主题色彩作用范围。
- 仍未覆盖：直接 `new MaterialAlertDialogBuilder(...).show()` 而不经 `LightDialog.apply` 的调用点；`AlertDialog` 的 window 级背景（`MaterialAlertDialog.WebHTV.Rounded` 的 `backgroundTint` 走 window background，binder 只改写 view 树）；`FollowingActivity` 不继承 `BaseActivity`；`StateListDrawable`/`RippleDrawable`/`ShapeDrawable` 背景与远程 Web 主题页面。
- 回滚锚点：回退本任务即恢复“弹窗不参与主题”的行为，仅影响闭包 3 个文件，不涉及 profile 数据格式或偏好键。

---

### 4.15 追更页接入主题契约（2026-09-24）

- 任务：`THEME-FOLLOWING-BIND-20260924`。关闭 4.13/4.14 节记录的最后一块**整页**覆盖缺口。
- 根因：`FollowingActivity` 直接继承 `AppCompatActivity` 并自带 `setTheme(R.style.Theme_App)`，虽然它确实使用 WebHTV 主题（`Theme.App → Theme.Base → Theme.WebHTV.Mobile`），但从不调用 `ThemeController.applyFromPreferences()`/`bindTheme()`，因此整棵视图树落在绑定范围之外；这也是 4.13 节设备排查中该页三组对照“零变化”的原因。
- 修复：按 `BaseActivity` 的既有契约手工接入三处调用——`super.onCreate()` 之后、首个 `setContentView()` 之前解析快照；`setContentView()` 之后绑定一次；`initView()` 之后再绑定一次。`addWallpaper()` 插入的 `CustomWallView` 是内容根的兄弟节点且命中 binder 的 `wall` 豁免标记，不受影响。
- 新增测试：
  - `ThemeBinderContractTest#followingPageOptsIntoTheSharedAppearanceContract`：按顺序断言三处调用（含“必须在首个内容视图之前解析”“必须在 `initView()` 之后二次绑定”）。
  - `ThemeBinderContractTest#nativeActivitiesOutsideBaseActivityAreExplicitlyAccountedFor`：遍历 `src/main/java/com/fongmi/android/tv`，任何直接继承 `AppCompatActivity` 且不调用 `bindTheme(` 的 Activity 都会失败，除非在显式豁免清单里（当前为 4 个 Lab 调试活动 + `CatWebActivity`/`GameWebActivity`/`WebReaderActivity` 三个 Web 宿主）。这样新页面无法再静默脱离主题。
- 自动化证据：mobile 与 leanback `--tests 'com.fongmi.android.tv.theme.*'` 均 BUILD SUCCESSFUL。
- 设备证据（dev3 `192.168.50.3:5559`，API 28，mobile arm64 debug 覆盖安装；用**橙色预设** `#FB8C00` 对照默认 profile 采集追更页）：
  - 默认下 **14735** 个基线 `#0B57D0`（`?attr/colorPrimary`）像素，在橙色预设下**全部**离开基线值，落在 `#88511D`（同色系 14647 px，其余为抗锯齿过渡色）。
  - `显示全部` 按钮由基线 `#DFE2E6`（`colorSecondaryContainer`）变为 `#FFDCC2`；`检查更新` 按钮同步变化；`取消追更` 保持 `#552422` 不变——符合预期，因为 `error` 不参与 seed 派生。
  - 两次冷启动均 `FATAL EXCEPTION = 0`。
  - 实验后设备偏好已按字节校验恢复为进入测试前原值。
- 覆盖变化：`FollowingActivity` 整页现已纳入主题色彩作用范围；`BaseActivity` 之外的 Activity 从“无声明的隐式缺口”变为“有测试守门的显式豁免清单”。
- 仍未覆盖：绕过 `LightDialog.apply` 直接 `new MaterialAlertDialogBuilder(...).show()` 的调用点（`app/src/main` 约 54 个文件的 189 处，另 mobile 84 处、leanback 83 处，尚未一次性收口）；`AlertDialog` 的 window 级背景（`MaterialAlertDialog.WebHTV.Rounded` 的 `backgroundTint`）与 `MaterialShapeDrawable` 填充不在 binder 的 `GradientDrawable` 通道内；`StateListDrawable`/`RippleDrawable` 背景；远程 Web 主题页面。
- 回滚锚点：回退本任务即恢复追更页不参与主题的行为，仅影响 1 个代码文件，不涉及 profile 数据格式或偏好键。

---

### 4.16 背景通道覆盖 Material 形状面板（2026-09-24）

- 任务：`THEME-SHAPE-BACKGROUND-20260924`。关闭 4.14/4.15 节记录的 `MaterialShapeDrawable` 盲区，并顺带取到 4.14 弹窗绑定缺失的真实设备证据。
- 根因：`ThemeBinder.bindBackground` 只接受 `GradientDrawable`，而 Material 的面板填充走 `MaterialShapeDrawable.getFillColor()`；对话框窗口背景还被包在承载 inset 的 `InsetDrawable` 里。因此**已绑定**的窗口仍然无法改变面板颜色——文字变了、面板不变。
- 修复：`bindBackground` 抽出为递归的 `bindDrawable(...)`——`InsetDrawable` 解包后继续递归，`MaterialShapeDrawable` 走 `getFillColor()`/`setFillColor()`，`GradientDrawable` 保持原 `getColor()`/`setColor()`。三种通道共用同一条“候选色必须精确等于基线语义角色色”的安全判定，无 profile/seed 时 binder 依旧整体 no-op；不引入反射或隐藏 API（测试同时钉住 `setAccessible`/`getDeclaredField` 不出现）。
- 新增测试：`ThemeBinderContractTest#backgroundChannelCoversMaterialShapeAndInsetWrappers`。
- 自动化证据：mobile `--tests 'com.fongmi.android.tv.theme.*'` BUILD SUCCESSFUL。
- 设备证据（dev3 `192.168.50.3:5559`，API 28，mobile arm64 debug 覆盖安装）：设置页点击「点播」行打开 `LinkDialog extends BaseAlertDialog`，default 与紫色探针（`surface/surfaceContainer/surfaceContainerHigh = #7B1FA2`、前景改白）各冷启动 32s + 点击：
  - 变化 **277730** 像素，其中 **245581** 像素恰好落在探针值 `#7B1FA2`——对话框**整块面板**变色。
  - 另有 7870 像素变为 `#ADADAD`、4145 像素变为 `#FFFFFF`——对话框标题/正文/按钮文字变色。
  - 该对话框是独立 window，不在 Activity 内容树内，因此文字变色可归因于 4.14 的 `BaseAlertDialog → bindDialog` 接入；面板变色可归因于本节的 `MaterialShapeDrawable` 通道（此前 `bindBackground` 会直接跳过）。两者共同构成 4.14+4.16 的设备证据。
  - 面板未改 `primary` 时「确定」按钮仍为基线蓝色，符合预期。
- 覆盖变化：弹出框面板、`TextInputLayout` 实心输入框底（`app:boxBackgroundColor="?attr/colorSurfaceContainerHighest"`，38 个布局）与 Chip 底（8 个布局）等 Material 形状填充现已纳入作用范围。
- **仍未覆盖**：仅对“精确等于基线角色色”的填充生效，因此**半透明**面板（例如设置页行背景，渲染值是 token 的 alpha 变体）仍被安全规则跳过；要覆盖它们需要先决定“按 RGB 匹配并保留视图自身 alpha”这一更大的设计变更。另有绕过 `LightDialog.apply` 的直接 `MaterialAlertDialogBuilder(...).show()` 调用点、`StateListDrawable`/`RippleDrawable` 背景、远程 Web 主题页面。
- 回滚锚点：回退本任务即恢复“面板不参与主题”的行为，仅影响 1 个代码文件，不涉及 profile 数据格式或偏好键。

---

### 4.17 共享 main 模块接入统一主题构建器（2026-09-24）

- 任务：`THEME-DIALOG-BUILDER-MAIN-20260924`。关闭“弹窗完全没被绑定”这个最大结构性缺口：共享 `main` 源集里 **61 个文件、125 处** 直接 `new MaterialAlertDialogBuilder(...)` 全部重建为新的 `WebHtvAlertDialogBuilder`。
- 设计：`MaterialAlertDialogBuilder.show()` 内部就是 `create().show()`，而 `create()` 是虚方法。因此 `WebHtvAlertDialogBuilder` 只重写 `create()`（`super.create()` 之后调用 `ThemeController.bindDialog(dialog)`），**所有调用点无需任何额外改动**即可获得绑定。默认主题下 `baseline.equals(active)`，绑定仍是零遍历 no-op。
- 新增测试：`ThemeBinderContractTest#materialAlertDialogsAreBuiltThroughTheThemedBuilder` 扫描 `src/main`，任何裸 `new MaterialAlertDialogBuilder(` 都会失败，防止回归；`DialogRoundedCornerSourceTest` 的既有断言同步更新为 `WebHtvAlertDialogBuilder`。
- 自动化证据：`:app:compileMobileArm64_v8aDebugJavaWithJavac` 与 `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` 均 BUILD SUCCESSFUL；mobile 与 leanback `--tests 'com.fongmi.android.tv.theme.*' --tests 'com.fongmi.android.tv.ui.dialog.*'` 均通过；`scripts/check_ui_tokens.sh --strict` → `violations=0 legacy=0`、38 组对比度 0 失败。
- 设备证据（dev3 `192.168.50.3:5559`，API 28，覆盖安装）：`FollowingActivity` 顶部「导入订阅」弹窗（1 参数构建器，**此前完全未绑定**）在 default / 紫色探针之间变化 9980 像素，其中标题与正文文字由基线色变为 `#ADADAD`。修复前该弹窗为 0 改写。
- **本阶段暴露的两个新问题（均未在本阶段修复，需独立授权）**：

  **P0 — 既有崩溃，与本阶段改动无关（修法与更正见 4.18）。** `MaterialAlertDialogBuilder(context, themeResId)` 会在构造器里经 `MaterialDialogs.getDialogBackgroundInsets` → `ThemeEnforcement.checkAppCompatTheme` 校验主题，抛 `IllegalArgumentException: The style on this component requires your app theme to be Theme.AppCompat`。用 `git stash` 回到未改动的 `409c1b52b` 重新构建安装，`FollowingActivity` 的「取消追更」以**完全相同的堆栈**复现（基线 `MaterialAlertDialogBuilder.<init>` → 改动后 `WebHtvAlertDialogBuilder.<init>`），因此这是既有缺陷，不是本次迁移引入的回归。
  > **本节初稿曾声称"共享 main 内共 12 处踩中"，该结论错误**：当时的普查正则 `[^)]*` 被 `requireActivity()` 的括号截断，既漏报了大量站点，又误把只传字面量 `colorPrimary` 的 `ThemeOverlay.WebHTV.Dialog` 一并判为崩溃。4.18 给出实测后的准确结论。

  **窗口背景不在视图树内。** 上述导入弹窗的文字变色但**面板颜色不变**。原因是 Material 对话框的面板是 `MaterialAlertDialogBuilder` 构造器里创建的 `MaterialShapeDrawable`，经 `Window.setBackgroundDrawable` 挂到窗口上，由 `DecorView.onDraw` 用 `Window.mBackgroundDrawable` 绘制——它**不是任何 View 的 background**，而 `ThemeBinder` 只遍历 `View.getBackground()`，且 `Window` 没有公开的 background 读取接口。因此对话框窗口面板无法用现有 binder 通道覆盖（4.16 中变色的是 `LinkDialog` 内容视图里 `shape_shell_proxy_dialog` 这个真实 View 背景，不是窗口背景）。要覆盖它需要引入 dialog 专用的窗口背景通道（例如 binder 之外另设一条以语义 token 直接构造 `MaterialShapeDrawable` 并 `window.setBackgroundDrawable` 的路径），属于独立设计决策。

- 覆盖变化：共享 `main` 模块 125 处弹窗构造现已统一经过绑定通道（文字、按钮、图标 tint、以及 View 级背景参与主题）；mobile / leanback 两个 flavor 源集尚未迁移。
- 回滚锚点：回退本任务即恢复裸构建器调用，仅新增 1 个类并机械替换构造调用，不涉及 profile 数据格式、偏好键或资源。

---

### 4.18 对话框主题参数修复与守门（2026-09-24）

- 任务：`THEME-DIALOG-THEME-ID-FIX-20260924`。修复 4.17 记录的 P0 崩溃，并更正其错误结论。
- **更正**：4.17 的"12 处都会崩"不成立。把 `FollowingActivity` 的取消追更弹窗临时指向 `R.style.ThemeOverlay_WebHTV_Dialog` 后重新构建安装，弹窗**正常渲染且 `FATAL EXCEPTION = 0`**。机制解释：`ThemeEnforcement.checkAppCompatTheme` 只检查主题是否定义 `?attr/colorPrimary`，而 `ThemeOverlay.WebHTV.Dialog` 把该角色写成具体色值 `@color/webhtv_color_primary`，所以即使被当成完整主题使用也能通过校验。真正会崩的只有 `ThemeOverlay.WebHTV.FollowingConfirmDialog`——它把 `colorPrimary` 写成**自引用** `?attr/colorPrimary`，作为独立主题解析不到值。
- 准确普查（修正 4.17 被截断的正则后重做）：`main` 源集把 overlay 当主题参数共 **37 处 / 27 个文件**——其中 36 处传 `ThemeOverlay_WebHTV_Dialog`（实测安全，**保持原样不动**），1 处传 `FollowingConfirmDialog`（即被修复的那处）。
- 修复：
  - `FollowingActivity` 取消追更弹窗改用完整主题 `R.style.Theme_WebHTV_Dialog`（父级 `Theme.Material3.DayNight.Dialog.Alert`；与 overlay 同一套语义 token、同为 22dp 圆角），从根上消除崩溃。
  - 删除已失效且属崩溃陷阱的 `ThemeOverlay.WebHTV.FollowingConfirmDialog`（修复后无任何引用）。
- 新增守门测试：`ThemeBinderContractTest#dialogThemeArgumentsResolveColorPrimaryConcretely`。它解析全部 `res` 下 style 及其 `parent`，对每个"被当作对话主题参数使用的 `ThemeOverlay_*`"沿继承链定位**最近一次** `colorPrimary` 定义，要求其必须是具体 `@color/...` 值。规则被两条实测数据点交叉验证：`ThemeOverlay_WebHTV_Dialog` 通过且实测不崩；`ThemeOverlay_WebHTV.FollowingConfirmDialog` 失败且实测必崩。继承被正确处理——`ThemeOverlay.WebHTV.Dialog.NoInset` 自身不定义该角色、安全继承祖先的具体值，因此通过（这也是该测试第一次运行就抓到的真实边界）。
- 自动化证据：mobile 与 leanback `--tests 'com.fongmi.android.tv.theme.*'` 均 BUILD SUCCESSFUL；`:app:compileMobileArm64_v8aDebugJavaWithJavac` 与 `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` 均通过。
- 设备证据（dev3 `192.168.50.3:5559`，API 28，mobile arm64 debug 覆盖安装）：追更页点击「取消追更」→ 对话框正常出现，`FATAL EXCEPTION = 0`、`Theme.AppCompat 崩溃 = 0`（修复前同一路径 100% 复现崩溃）。
- 仍未覆盖：mobile/leanback flavor 源集内另有 16 处把 `ThemeOverlay_WebHTV_Dialog` 当主题参数（实测安全，但尚未迁移到 `WebHtvAlertDialogBuilder`，因此其弹窗仍不经主题绑定）；对话窗口背景（挂在 `Window.setBackgroundDrawable` 上的 `MaterialShapeDrawable`）仍不在 binder 可达范围；半透明填充匹配策略与 4.7 节完整设备矩阵未完成。
- 回滚锚点：回退本任务即恢复崩溃的 overlay 引用；不涉及 profile 数据格式、偏好键或构建配置。

---

### 4.19 对话框窗口背景通道（2026-09-24）

- 任务：`THEME-WINDOW-BACKGROUND-20260924`。关闭「对话框文字变色、面板不变色」这一遗留缺口。
- 根因（字节码取证）：`MaterialAlertDialogBuilder` 在构造器里创建 `MaterialShapeDrawable`，用 `MaterialDialogs.insetDrawable(...)` 包成 `InsetDrawable` 存入 `background` 字段，再在 `create()` 里 `Window.setBackgroundDrawable(background)`（javap 确认：`getfield background` → `Window.setBackgroundDrawable`）。这个 drawable 属于**窗口**而非任何 `View`，`DecorView.getBackground()` 拿不到它，所以视图树遍历永远覆盖不到——这正是 4.17 观察到"文字变了、面板不变"的机制。
- 修复：新增窗口背景通道，复用既有那条精确匹配规则，不引入第二套颜色逻辑：
  - `ThemeBinder.bindWindowBackground(Drawable, baseline, active)`：主线程 + 基线相等时直接返回；否则走同一个私有 `bindDrawable(...)`（解包 `InsetDrawable` → 改写 `MaterialShapeDrawable.getFillColor()`），因此形状与窗口 inset 完全不变，只换填充色。
  - `ThemeController.bindWindowBackground(Drawable)`：与 `bindTheme(View)` 并列的第二个受控入口。
  - `WebHtvAlertDialogBuilder.create()`：`super.create()` 之后先 `ThemeController.bindWindowBackground(getBackground())`，再 `ThemeController.bindDialog(dialog)`。`getBackground()` 返回的正是 Material 交给窗口的同一个实例，原地改色即可，无需重建 drawable。
- 新增测试：`ThemeBinderContractTest#dialogWindowBackgroundIsRecolouredOutsideTheViewTree`（钉住三个通道的签名与调用点）。
- 自动化证据：mobile 与 leanback `--tests 'com.fongmi.android.tv.theme.*'` 均 BUILD SUCCESSFUL；`:app:compileLeanbackArm64_v8aDebugJavaWithJavac` 通过。
- 设备证据（dev3 `192.168.50.3:5559`，API 28，mobile arm64 debug **覆盖安装**）：追更页「导入订阅」弹窗（1 参数构建器，与 4.17 同一场景，该场景此前**面板实测不变色**）在 default / 紫色探针间变化 **391852** 像素，其中 **364824** 像素恰好为探针值 `#7B1FA2`；面板四个平坦取样点 `(1400,400)/(1400,690)/(700,690)/(1000,720)` 全部由默认 `#CFD8EB` 变为 `#7B1FA2`；标题随 `onSurface=#FFFFFF` 变为白色。未改 `primary`，因此三个按钮仍为基线蓝色，符合预期。
- 覆盖变化：**对话框面板（窗口背景）现已成为主题色彩可达区域**，这是"主题色彩感觉只有站点弹框生效"的最后一块结构性缺口。
- 仍未覆盖：mobile/leanback flavor 源集内 16 处把 `ThemeOverlay_WebHTV_Dialog` 当主题参数、且仍用裸 `MaterialAlertDialogBuilder` 的站点（其实测安全但未接入绑定）；半透明填充匹配策略（设置页行背景 `black@15%` 属中性遮罩，按安全规则应跳过，若要覆盖需先决定策略）；绕过 `WebHtvAlertDialogBuilder` 的 10 处 `new AlertDialog.Builder(...)`；`StateListDrawable`/`RippleDrawable` 背景；远程 Web 主题页面；4.7 节完整设备矩阵。
- 回滚锚点：回退本任务即恢复"面板不随主题"的行为；仅新增 1 个公开方法 + 1 个调用点，不涉及 profile 数据格式、偏好键或资源。

---

### 4.20 mobile/leanback flavor 源集统一主题构建器（2026-09-24）

- 任务：`THEME-DIALOG-BUILDER-FLAVORS-20260924`。把 4.17 的迁移补齐到两个 flavor 源集。
- 改动：`app/src/mobile` **20 个文件 / 38 处**、`app/src/leanback` **13 个文件 / 31 处**，共 33 文件 69 处裸 `new MaterialAlertDialogBuilder(...)` 全部重建为 `WebHtvAlertDialogBuilder`；仅在类型彻底不再被引用时移除 Material 的 import。机械替换后逐一核对导入顺序——由本次改动引入的乱序为 **0**（仓库内既有乱序未触碰）。
- 守门测试扩展：`materialAlertDialogsAreBuiltThroughTheThemedBuilder` 现在扫描 `main` + `mobile` + `leanback` 三个源集，任一源集新增裸构建器都会失败。同步更新 `AdRuleManageDialogLayoutTest` 的既有断言。
- 自动化证据：mobile 与 leanback `--tests 'com.fongmi.android.tv.theme.*'` 均 BUILD SUCCESSFUL；`:app:compileMobileArm64_v8aDebugJavaWithJavac` 与 `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` 均通过。
- 设备证据（dev3 `192.168.50.3:5559`，API 28，mobile arm64 debug 覆盖安装）：mobile 版 `HistoryActivity`（`extends BaseActivity`）的「删除全部纪录？」确认框——该弹窗此前完全不经绑定——在 default / 紫色探针间，对话框区带变化 549762 像素，四个面板取样点 `(1400,420)/(1300,600)/(700,650)/(1000,680)` 全部由默认 `#CFD8EB` 变为探针值 `#7B1FA2`，其中 307808 像素精确命中该值；标题与正文同步转为白色。
- **过程中发现并纠正的一次取证失误**：第一次跑该设备用例时文字与面板都没变，一度像是"flavor 迁移无效"。实际原因是当时设备上安装的仍是上一提交（`5afd51255`）的 APK，本次 flavor 改动尚未构建安装。重新 `build_arm64_debug_install.sh --flavor mobile` 覆盖安装后即复现预期结果。记录在此以提示后续验证必须先确认设备 APK 与工作树一致。
- 覆盖变化：全仓库（3 个 Java 源集）已无裸 `MaterialAlertDialogBuilder` 调用点，125 + 69 = 194 处弹窗构造统一经过主题绑定通道。
- **已知既有缺陷（非本任务引入，未修）**：`app/src/test/java/com/fongmi/android/tv/ui/helper/TouchOptimizationHelperSourceTest.java:80` 断言 leanback `BaseActivity` 含 `setContentView(getBinding().getRoot());`，但该形态已被 `584b64514`（2026-09-23）改为 `View content = getBinding().getRoot(); setContentView(content);`。该断言自 2026-09-23 起即为陈旧红灯（与 4.13 修掉的 `ThemeBinderContractTest#everyActivityBindsTheThemeTreeTwice` 同源）。它不属于本任务 scope，未修改；因它会打断全量套件绿灯，建议单独授权一个测试收尾任务修正。
- 仍未覆盖：半透明填充匹配策略；10 处 `new AlertDialog.Builder(...)`；`StateListDrawable`/`RippleDrawable` 背景；远程 Web 主题页面；4.7 节完整设备矩阵。
- 回滚锚点：回退本任务即恢复 flavor 内的裸构建器；不涉及 profile 数据格式、偏好键或资源。

---

### 4.21 陈旧源码断言收尾（2026-09-24）

- 任务：`TEST-STALE-BASETHEME-ASSERT-20260924`。修掉 4.20 记录的既有红灯，恢复全量套件绿灯。
- 根因：`TouchOptimizationHelperSourceTest#activityAppliesOptimizationToNewContentAndRegistersFragmentsEarly` 断言 leanback `BaseActivity` 含 `setContentView(getBinding().getRoot());`。`584b64514`（2026-09-23）改成"只 inflate 一次"后，该字符串变为 `View content = getBinding().getRoot();` + `setContentView(content);`，断言自那时起必然失败。与 4.13 修掉的 `ThemeBinderContractTest#everyActivityBindsTheThemeTreeTwice` 属同一次重构遗留的两处陈旧断言。
- 修复：断言改为当前形态，并**保留原有意图**——`registerFragmentLifecycleCallbacks()` 仍在解析内容视图之前；`addCustomWall()` → `TouchOptimizationHelper.sync(...)` 仍在该重载内部有序发生。新增一条更贴合重构的断言：解析出的 `content` 必须就是交给 `setContentView(...)` 的那个实例，且发生在重载声明之前。
- 非空断言验证：新断言用 `setContent > content`，若该调用被删除则 `indexOf` 返回 `-1` 必然失败，不会静默通过。
- 自动化证据：`./gradlew :app:testMobileArm64_v8aDebugUnitTest`（全量）→ **712 个测试类 / 4863 个测试 / 0 失败 / 0 错误**，结果文件时间戳与本次运行一致。
- 回滚锚点：回退本任务即恢复陈旧断言；不涉及生产代码、profile 数据格式或偏好键。

---

### TASK L1：默认主题来源整合

- 输入：本文第 3 节。
- 输出：默认主题同源、Dialog/页面/TV 一致，strict/单测/编译/设备通过。
- 不包含：自定义 profile、编辑器、播放器画面。
- 验收：Layer 1 DoD。

### TASK L2A：B-safe profile 与 resolver

- 输入：本文第 4.3–4.4 节。
- 输出：16 槽模型、校验、迁移、resolver、last-good。
- 不接线编辑器，不改变现有页面视觉；先用 JVM 测试证明。
- 验收：所有 profile/resolver/migration 测试通过。

### TASK L2B：受控 ThemeBinder 与运行时应用

- 输入：本文第 4.5 节。
- 输出：Activity/Dialog/RecyclerView 动态 child 的安全绑定、播放器豁免、性能证据。
- 验收：角色、动态 UI、播放器回归、连续切换和性能门禁。

### TASK L2C：编辑器与预览

- 输入：本文第 4.2、4.6 节。
- 输出：mobile/leanback 入口、16 槽编辑、取消/应用/重置、浅深模式和预览。
- 验收：功能矩阵中的预设、单槽、透明度、非法输入与取消/应用。

### TASK L2D：Web、备份与收口

- 输入：本文第 4.3、4.5、4.7 节。
- 输出：Web 只读快照附加 opacity、备份恢复、完整设备记录、更新本文件和关联索引。
- 验收：WebHome、备份、重启、无崩溃和文档一致性。

---

## 8. 最终完成定义

本轮工作只有在以下条件全部满足时才算完成：

1. 默认主题不再混用 Material baseline；Layer 1 可单独回滚。
2. 用户可保存并应用 B-safe 16 槽主题，包含浅/深模式和 3 个透明度；不开放 49 色和任意 alpha。
3. 普通页面、设置、Dialog、BottomSheet、列表动态 child、TV 焦点和 Web native token 使用同一解析结果。
4. 播放器画面、解码、音轨、字幕、弹幕、倍速、渲染路径和生命周期没有可复现回归。
5. 静态检查、JVM 测试、双 flavor 编译、设备矩阵、性能记录和回滚验证全部有明确结果。
6. 工作树中只包含当前任务声明的路径；所有代码/资源提交原子化，并由 task-guard 创建唯一 recovery tag。

在此之前，不得宣称“全局主题自定义已完成”，也不得把仅通过 JVM 或仅通过 API 28 的结果描述为已全面验收。
