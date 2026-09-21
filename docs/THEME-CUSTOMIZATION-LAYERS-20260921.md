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
- 当前状态：Layer 1 已实施并通过自动化与设备验收（见 3.4），尚未提交；Layer 2 未启动。
- 已知任务外缺陷：`3f3ab82b1f` 在 leanback 播放器布局中引用了从未声明的 `colorOnSurface_20/70/80/90`，导致 TV 资源链接失败；Layer 1 已按用户批准的方案 A 一并补齐（见 3.4）。
- 下一步唯一动作：提交 Layer 1 并生成 recovery tag，随后等待用户对 Layer 1 设备表现确认，再评估启动 Layer 2A。

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
