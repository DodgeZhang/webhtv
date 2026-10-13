# WEBHTV-UI-CONFORMANCE-AUDIT-20261013：UI 规范符合性审计

## Recovery anchor

- 目标：按设计文档 §7、§8、§9.1–§9.9、§13、§20 逐条对照**当前实现**，产出一张真实的覆盖表（满足 / 部分满足 / 未满足 / 不适用），回答"UI 优化是否已经全面"。
- 性质：**只读评估**。本任务不改生产代码、不改资源、不改构建；唯一交付物是本文件。
- 保护面：任务开始时工作树仅 `.design-evidence/` 未跟踪（预存脏路径 0 个）；分支 `dev4`，HEAD `77d7daf44bd`。
- 当前状态：**§1–§5、§7 已完成**（作者亲测）；**§6 组件章节（§7/§8/§9.1–§9.8）由三个并行审计子代理产出，进行中**。
- 下一步唯一动作：子代理返回后把三张组件覆盖表并入 §6，并把 §7 的优先修复清单按判定排序。

## 1. 方法与工具

### 1.1 为什么不能只靠"我做过哪些批次"

本会话此前交付了 15 个功能提交，全部是**缺陷驱动**的：发现一个问题、修一个问题、验证、提交。这条路能收敛（例如"删除后不驱动状态机"已收敛成不可能再犯），但它**不能证明覆盖完整**——它只覆盖"恰好撞见的"。所以本次改为**按规范逐条对照**。

### 1.2 等价重实现静态门槛（关键）

设计文档 §13.1 把 `scripts/check_ui_tokens.sh` 列为**每阶段必做**的静态验证，§19 给出了它的检查契约。本机**没有可用的 bash**（仅 WSL，`HCS_E_SERVICE_NOT_AVAILABLE`），因此该脚本在本会话的 15 个提交里**一次都没有运行过**。

为了拿到真实数字，我按脚本源码**等价重实现**了它的五项检查（放在未跟踪的 `.design-evidence/check-ui-tokens-audit.ps1`，不进入仓库）：

1. token 完整性（49 个必需 token 在浅色/深色两张表都存在）
2. style 完整性（12 个必需 style）
3. 对比度（18 组 × 2 调色板 + `player_control_active`）
4. 固定色扫描（layout / drawable / color / assets，带 allowlist）
5. 遗留 `Theme.WebHTV.LightDialog` / `Widget.WebHTV.LightDialog` 扫描

**自校验**：设计文档 §13.1 的历史记录写的是 `38 组对比度 0 失败，min=4.28`（§13.1 L109 还注明最低是 `outline/surface=4.28:1`）。我的重实现测得 **`pairs=38 failures=0 min=4.28`**——**精确复现**。这证明重实现与原脚本的判定一致，其结论可用。

## 2. 静态门槛实测结果

```
TOKENS   light=56 dark=56 required=49 missing=0          → 通过
STYLES   required=12 missing=0                            → 通过
CONTRAST pairs=38 failures=0 min=4.28                     → 通过（与设计文档记录一致）
BASELINE layouts=385 hex_layouts=1 drawables=559 hex_drawables=0 colors=60 hex_colors=0 allowlisted=190
STRICT   violations=1 legacy=0
         VIOLATION app/src/mobile/res/layout/item_following.xml
STATUS   FAIL
```

### 2.1 结论一：**这道门槛现在是失败的**，而它从未被运行

设计文档 §13.1 自己的验收标准写的是 `violations=0 legacy=0`、`hex_layouts/hex_drawables/hex_colors` 均为 0。实测 **`violations=1`、`hex_layouts=1`**。

也就是说：**在 Phase F 基线之后有人引入了固定色，而本该拦住它的门槛再没跑过**（因为这台机器没有 bash）。这正是"缺陷驱动"路线的盲区——没人跑门槛，门槛就等于不存在。

资源规模也印证了时间差：文档记录 378 布局 / 549 drawable / 47 color，实测 **385 / 559 / 60**，即此后新增了 7 个布局、10 个 drawable、13 个 color state list（其中一部分来自本会话的批次）。

### 2.2 结论二：唯一违规已完全量化（§5.3 不达标 + §5 语义化不达标）

`app/src/mobile/res/layout/item_following.xml`（手机端"追更"卡片）：

| 位置 | 内容 | 写法 | 浅色底 `#E7E8EF` | 深色底 `#2A2F34` | 门槛 |
|---|---|---|---|---|---|
| L73 | 「新 N 集」角标（12sp 粗体） | `android:textColor="#174EA6"` | 6.42:1 ✓ | **1.72:1** ✗ | 4.5:1 |
| L124 | 「检查失败」错误文字（11sp） | `android:textColor="#B3261E"` | 5.35:1 ✓ | **2.07:1** ✗ | 4.5:1 |

两个关键事实：

1. **底材是语义 token**（`@color/following_card_bg` → `webhtv_color_surface_container_high`，浅色 `#E7E8EF` / 深色 `#2A2F34`），**文字却是硬编码**——底材跟着主题走、文字不跟，于是深色模式下这两处基本不可读。这同时违反 §5（必须消费语义 attr/style）与 §5.3（正文 ≥4.5:1）。
2. **`main` 版同名文件在同一位置用的是 `?attr/colorPrimary` / `?attr/colorError`**（语义化、两端达标）。所以这不是"还没迁移的历史包袱"，而是**手机端独有的偏离**。

注意 `#B3261E` 正是**浅色表的 M3 错误红**——而它承载的文字是「检查失败」，即 §9.9 管辖的错误态。这与本会话 M-3 修掉的设置页问题是**同一类**，只是发生在追更卡片上。

### 2.3 结论三：门槛本身有**范围盲区**——只扫资源，不扫 Java

`check_ui_tokens.sh` 的扫描范围是 `res/layout`、`res/drawable`、`res/color` 与 `assets/*.css|html`，**不含 Java**。而 Java 里有大量硬编码颜色：`Color.parseColor("#...")` 与 `setTextColor(0x...)` 字面量各在**数十个文件**中出现（`ui/dialog/`、`ui/custom/`、`player` 相关与 `lab/` 尤其集中）。

**这不违反设计文档**（§13.1/§19 没有要求扫 Java），但它是门槛的一个已知盲区。需要强调的是：**不能简单地对 Java 一刀切禁止固定色**——播放器/OSD/深玻璃面板上的固定白色是有意为之（底材是任意视频画面，语义色反而给不出保证）。合理的做法是把 Java 侧也纳入扫描、但用 allowlist 明确豁免这些场景。

## 3. 对我自己此前批次的复核（含一处更正）

审计不只对外，也对我自己。复核本会话交付的状态面时发现两件事，**一正一负**：

### 3.1 更正：错误态标题**是达标的**，我此前记错了

我在 `docs/TV-ERROR-STATE-20261012.md` 里把"错误色压在壁纸上最差 3.23:1"记为**已知缺口**，并按正文 4.5:1 衡量。

**这是错的。** 设计文档 §5.3 明确写着：

> 普通正文/正文按钮：`on*` 与背景 ≥ 4.5:1。
> **大字（≥18sp 或 ≥14sp bold）、图标、焦点环、非文本 UI：≥ 3:1。**

空态/错误态的标题是 **Display 档（电视 40sp）**，属"大字"，门槛是 **3:1**：

| 底材（实机取样） | `#FFDAD6` 标题对比度 | 门槛 3:1 |
|---|---|---|
| 最暗 L=0.1217 | 4.73:1 | ✓ |
| 平均 L=0.1476 | 4.11:1 | ✓ |
| 最亮 L=0.2019 | **3.23:1** | ✓ |

**通过。** 所以 TV-5/TV-6 那条"缺口"应从已知问题清单里划掉。我在审计中主动更正自己此前的记录。

### 3.2 发现：空态/错误态的**描述文字不达标**，而这是我自己引入的

描述文字用的是 `?attr/webhtvColorOnWallpaperVariant` = `#CCFFFFFF`（80% 白），字号 12–16sp（**正文**，门槛 4.5:1）：

| 底材（实机取样） | `#CCFFFFFF` 合成后对比度 | 门槛 4.5:1 |
|---|---|---|
| 最暗 L=0.1217 | 4.61:1 | ✓ |
| 平均 L=0.1476 | **4.08:1** | ✗ |
| 最亮 L=0.2019 | **3.30:1** | ✗ |

**在平均与最亮壁纸上不达标。** 这条影响本会话交付的多个面：TV-4 的空态解释、TV-5 的错误原因、M-1/M-5 的手机端对应文案，以及 TV-9 的历史页解释。

**这与 TV-6 对"取值文字"的处理是同一类问题**：TV-6 把设置页的取值文字搬到**实心深底**上（8.84:1）解决了它，但**描述文字没有跟着处理**，仍直接压在壁纸上。修法方向相同（换更亮的色或加底材），但需要一次独立的决定，本审计只记录不实施。

## 4. §9.9（加载/空态/错误态）覆盖情况

这是本会话投入最多的章节，也是唯一接近"完成"的章节。

| 条目 | 端 | 判定 | 证据 | 差在哪 |
|---|---|---|---|---|
| 空态 = 标题 + 解释 + 主动作 | 电视 | 部分满足 | `KeepActivity`/`HistoryActivity` 的 `setEmptyContent`；`TvStateSurfaceContractTest` | `FileActivity` 未接入（见下） |
| 空态三件套 | 手机 | 部分满足 | `KeepActivity`/`HistoryActivity` 的 `setEmptyContent`；`MobileStateSurfaceContractTest` | 同上 |
| 空态标题属 Display 档 | 电视 | 满足 | `view_empty.xml` 40sp；`TvStateSurfaceContractTest` 断言 40–48sp | — |
| 空态标题属 Display 档 | 手机 | 满足 | `view_empty.xml` 32sp | — |
| 加载态统一 spinner、不用多种颜色动画 | 两端 | 满足 | `view_progress.xml` 指示色改为 `?attr/webhtvColorOnWallpaper` | — |
| 错误态 = 错误色 + 原因 + 重试动作 | 电视 | 满足 | `ProgressLayout.applyErrorContent()` + `showError(...)`；`TvErrorStateContractTest` | — |
| 错误态 | 手机 | 满足 | 同上 + `VodFragment.showError(...)`；`MobileHomeStateContractTest` | — |
| 错误取值需可读的底材 | 电视 | 满足 | `shape_error_value.xml`（实心 `#4A3438`）+ 8.84:1 | — |
| 错误取值底材 | 手机 | 满足 | 复用同一份 drawable（已迁至 `main`） | — |
| 播放器错误层有重试动作 | 电视 | 满足 | `view_widget_vod.xml` 的 `@id/retry`；`TvPlayerErrorRetryContractTest` | — |
| 播放器错误层有重试动作 | 手机 | 满足 | `@id/errorRetry`；`MobilePlayerErrorRetryContractTest` | — |
| 播放器错误层有"错误色" | 两端 | **未满足（有意）** | 文字仍为 `@color/white` | 底材是任意视频画面，固定白最稳；要满足 §9.9 需给它不透明实心底，属独立观感决策 |
| **空态描述文字 ≥4.5:1** | 两端 | **未满足** | §3.2 实测 4.08 / 3.30:1 | 见 §3.2 |
| 空态/错误态接入完整 | 两端 | **未满足** | `FileActivity`（两个 flavor）无 `setEmptyContent` | 主动作是导航/产品决策（返回上级 vs 重新扫描） |

## 5. 流程性缺口（比组件缺口更值得注意）

| 要求 | 出处 | 执行情况 |
|---|---|---|
| `scripts/check_ui_tokens.sh` 每阶段必做 | §13.1 | ❌ **从未运行**（无 bash）；等价实测**当前为 FAIL** |
| `git diff --check` | §13.1 | ❌ 未运行 |
| 定向 JVM 测试 | §13.1 | ✅ 每个批次都跑了（含跨 flavor 契约） |
| 编译任务 | §13.1 | ✅ 两个 flavor 均 assemble 成功 |
| §13.2 视觉场景矩阵（7 行） | §13.2 | ❌ 未系统执行：**Light 模式从未验证**；壁纸 seed、Android 9 fallback、长文本/错误、播放页控制未按矩阵逐行做 |
| §20.2 设备验收矩阵（9 行） | §20.2 | ❌ 未系统执行：**B-M-L / B-M-D / B-T-D / C-M / C-T / D-M / D-T / E-W 都没有按行验收**；仅部分场景被顺带覆盖（如 5561 是 API 28 = Android 9，部分覆盖 B-A9） |
| §13.3 验收标准（7 条） | §13.3 | ❌ 未逐条判定；其中"Web 管理页/WebHome/reader 浅深一致"完全没查 |
| §10.3 迁移映射表（文档标为"核心"） | §10.3 | ❌ 本会话完全未触及 |
| §11 Web / WebTheme | §11 | ❌ 未触及 |

**最重要的判断**：§20.2 的九行设备验收矩阵**没有一行被完整执行过**。我在会话中做的真机验证都是**针对单个改动的定向观测**（例如"断网后首页是否显示错误态"），那不是矩阵验收。所以"全面优化完毕"在流程上**没有依据**。

## 6. 组件章节审计（§7、§8、§9.1–§9.8）

由三个并行审计子代理产出，每个自读规范正文、自核当前实现、要求给出 `文件:行号` 证据与四档判定（满足 / 部分满足 / 未满足 / 不适用）。

- **§6.1 子代理 B：§9.1 按钮 / §9.2 输入框 / §9.3 列表项与分组 / §9.4 卡片与海报 / §9.5 Chip·Tab·标签 —— 已完成**
- §6.2 子代理 A：§7 形状·层级·动效 + §8 间距·布局·安全区 —— 进行中
- §6.3 子代理 C：§9.6 Switch·Checkbox·Radio / §9.7 Dialog·BottomSheet·全屏浮层 / §9.8 Player·OSD·控制层 —— 进行中

### 6.1 §9.1–§9.5（子代理 B）

**总体结论**：语义 token 层、TV 焦点环分层、卡片尺寸稳定性做得扎实且有契约测试；但**组件样式普遍未被消费**——`Widget.WebHTV.Button.Filled`/`Card`/`ListItem` 与整族 `webhtv_selector_*` **零引用**；48dp 按钮高度、2:3/16:9 比例、Chip 的 container 配对基本未落地；详情页仍走硬编码调色板。

**跨章节的关键事实（对整改最有价值）**：设计系统自己的组件状态色族 `main/res/color/webhtv_selector_*.xml`（`button_filled`/`button_outlined`/`input`/`list_item`/`tmdb_card`/`tmdb_search_item`，共 6 个文件）**全库零引用**，仅被 `app/src/test/.../UiStyleSourceTest.java:32` 的文本断言读取。它们已正确覆盖 disabled/pressed/focused/selected（`webhtv_selector_button_filled.xml:2-6`），是**现成的语义状态层，但没有接到任何视图上**。

| 规范条目 | 端 | 判定 | 证据（file:line / 符号） | 差在哪 / 备注 |
|---|---|---|---|---|
| §9.1 Filled：primary/onPrimary，normal/focus/pressed/disabled | 两端 | 部分满足 | `main/res/values/webhtv_styles.xml:174-177`；实际用色 `main/res/layout/dialog_about.xml:108,124,142`（`?attr/colorPrimary`） | `Widget.WebHTV.Button.Filled` **全库零引用**（仅 `test/.../UiStyleSourceTest.java:19` 列出）；状态色靠 `color/dialog_primary_button_bg.xml:3` 等实现 |
| §9.1 Filled/Tonal/Outlined/Text 均 48dp 高 | 两端 | 未满足 | 全库 375 个按钮：48dp 仅 10 个（`leanback/dialog_ad_block_stats.xml:813,822`、`mobile/dialog_ad_block_stats.xml:791,800` 等），40dp 113 个、36dp 55 个、44dp 32 个 | `@dimen/webhtv_size_button`=48dp 只被 `Widget.WebHTV.ListItem` 引用，**没有任何按钮 style 用它**；`dialog_about.xml:101,104,135,137`=44dp |
| §9.1 Tonal 用 primaryContainer/onPrimaryContainer | 两端 | 未满足 | `webhtv_styles.xml:179-182` | 实际用 `?attr/colorSecondaryContainer`/`colorOnSecondaryContainer`，与规范写的 primaryContainer 不符（TV 焦点填色见 `color/dialog_tonal_button_bg.xml:4`，走 secondary_container） |
| §9.1 Tonal 与 Filled 不混用为同级 | 两端 | 部分满足 | `dialog_about.xml:99,115,133` 同屏 3 个 primary 填充按钮 | 见下条"最多两个强调动作" |
| §9.1 Outlined：outline/onSurface 描边有对比度 | 两端 | 部分满足 | `webhtv_styles.xml:184-187`；`color/dialog_outlined_button_stroke.xml:6`=?attr/colorOutline | 描边 ✓；文字用 `?attr/colorPrimary` 而规范写 onSurface |
| §9.1 Text：48dp 点击区，不用于主确认 | 两端 | 部分满足 | `main/res/layout/dialog_theme_import.xml:94-99`(wrap_content)、`dialog_group_rule_title.xml:20-29`(44dp) | 未达 48dp；仅用于取消/低强调动作，**未见用于主确认** ✓ |
| §9.1 Icon：40–48dp 且必须 contentDescription | 两端 | 部分满足 | ImageButton 29/29 有 CD；反例：`leanback/dialog_episode.xml:27`(34×34 无 CD)、`main/dialog_one_key_sync.xml:29`(40×40 无 CD)、`mobile/adapter_site.xml:37,49`、`mobile/adapter_config.xml:31` 无 CD | 尺寸：44dp×9、40dp×6 为主，个别 34dp/36dp 低于 40dp；`leanback/adapter_ad_rule.xml:69` 的 CD 由 `leanback/.../AdRuleAdapter.java:217` 补 |
| §9.1 Long/Image 仅移动端；TV 图像按钮加渐变 scrim | 两端 | 不适用 | 无 Long/Image 按钮组件（无对应 style/布局模式） | 唯一的图像叠加是海报删除遮罩 `leanback/adapter_vod.xml:27` = `@color/webhtv_color_player_scrim`（平铺 scrim，非渐变） |
| §9.1 一屏只允许一个最高强调动作 | 两端 | 未能确定 | — | 需运行态/整屏判断，静态无法判定 |
| §9.1 弹窗底部最多两个强调动作 | 两端 | 未满足 | `leanback/dialog_update.xml`、`mobile/dialog_update.xml`、`main/dialog_about.xml` 各含 3 个 primary 填充 MaterialButton | 见"Filled"行同屏 3 个高强调动作 |
| §9.1 危险操作使用 error 并放在明确位置 | 两端 | 部分满足 | `main/res/layout/item_following.xml:245-246`（`?attr/colorErrorContainer` + `@color/focus_ring_error`） | `color/following_button_danger_text.xml:3-4` 焦点/按下文字写死 `@android:color/white`，而填充 `following_danger`=`webhtv_color_error`（夜表 `#FFB4AB`），对比度约 1.9:1，与仓库已修的 `following_button_primary_text` 是同一类缺陷 |
| §9.1 禁用态改对比度/透明度并提供不可用语义 | 两端 | 部分满足 | 有：`color/dialog_primary_button_bg.xml:3`、`dialog_outlined_button_text.xml:3`、`about_primary_action_bg.xml:4`；无：`color/tmdb_action_button_bg.xml`、`following_button_*.xml`、`dialog_card_*.xml` 整族无 `state_enabled` | 全库 **无任何 `stateDescription`**（grep 0 命中），"不可用语义"未实现 |
| §9.1 焦点/选中/按下分别定义；TV 焦点须 ring 或 1.1x | 电视 | 部分满足 | ring ✓：`leanback/drawable/shape_item_focused.xml`、`shape_vod_focused.xml`；1.1x：`main/.../helper/TvFocusScale.java:51`（调用方仅 `KeepAdapter.java:98`、`HistoryAdapter.java:118`、`TmdbVideoAdapter.java:113`、`TmdbVideoPresenter.java:80`） | 站点/分类/选集等 TV 列表只有 ring 无缩放；密集宿主用 1.04（`TvFocusScale.java:44`，有裁切反证，属有意例外） |
| §9.1 焦点一律由边框环表达（填充不得作为唯一线索） | 电视 | 部分满足 | 应用表面 drawable 全部带 `<stroke>`（`shape_item_focused.xml:29-31` 等） | 例外：`main/res/drawable/selector_mpv_tab_focus.xml:4-6` 焦点态写死 `#E8F0FE` 填充 + `#1A73E8` 2dp 描边，被 TV Tab 使用（`leanback/.../AdBlockStatsDialog.java:131`、`main/.../UpdateSettingsDialog.java:168`、`PlaybackPerformanceDialog.java:403`） |
| §9.1 环宽唯一来源 `@dimen/webhtv_focus_ring_width` = **3dp** | 电视 | 部分满足 | token：`main/res/values/webhtv_dimens.xml:26`；引用面：leanback 全部焦点描边 + `TvFocusRingContractTest.noLeanbackFocusRingHardcodesItsWidth:264` | **取值是 1.5dp 而非规范写的 3dp**（`webhtv_dimens.xml:15-25` 注明 3dp 已被 2026-10-10 实机判为"太粗"）。唯一来源 ✓，字面值 ✗ |
| §9.1 环色 = 焦点态**实际**填充的配对 on-色 | 电视 | 满足 | `main/res/values/colors.xml` `focus_ring_on_primary`/`_secondary_container`/`_error_container`；契约 `TvFocusRingContractTest.everyRingColourKeepsNonTextContrastAgainstItsFocusFill:89`（day/night 双表 ≥3:1） | `color/focus_ring_error.xml` 明确用 on_error_container 而非 on_error |
| §9.1 非聚焦态保留同宽透明描边 | 电视 | 满足 | `color/focus_ring_primary.xml`/`_secondary`/`_error`（`@android:color/transparent`，契约 `TvFocusRingContractTest.java:130`） | leanback `shape_*_normal` 与 focused 使用同一 padding，无尺寸跳变 |
| §9.1 宿主分层规则（调色板/固定明暗/视频三层） | 电视 | 满足 | `TvFocusRingContractTest.appSurfaceFocusRingsShareOneWidthTokenAndAThemeColour:167`（themed 11 个 vs palette-independent 12 个名单）；`webhtv_styles.xml:67-71,118-122,152-156` | 与 `?attr/tvFocusRing` vs `?attr/tvPlayerRing` 两族的有意分工一致 |
| §9.1 宽度收口为全库不变量（仅 2 个豁免） | 电视 | 部分满足 | 测试只扫 `app/src/leanback/res/drawable`（`TvFocusRingContractTest.java:268-288`） | ① 豁免名单已过期：`selector_video_item.xml:42,56` 现在已用 token；② **`main/res/drawable` 不在扫描范围**，`selector_mpv_tab_focus.xml` 的 2dp 写死得以存活 |
| §9.2 统一 `Widget.Material3.TextInputLayout.OutlinedBox` 或 `Widget.WebHTV.Input.Outlined` | 两端 | 部分满足 | OutlinedBox 27 处（`main/dialog_update_settings.xml:97`、`dialog_lab_settings.xml:60,93,254,273`）；**无 style 64 处**（`leanback/dialog_ad_rule_edit.xml:27,50,77,104`、`main/dialog_playback_webhook.xml` 等） | 无 style 的走主题默认（M3 默认 FilledBox）；部分用 `app:boxBackgroundMode="outline"` 补。迁移后名字是 `Widget.WebHTV.Input`，非规范写的 `Widget.WebHTV.Input.Outlined` |
| §9.2 默认 surface/onSurface、聚焦 primary、错误 error、辅助 onSurfaceVariant | 两端 | 满足 | `main/res/drawable/selector_dialog_input.xml:9`(focused→`?attr/colorPrimary`)、`:18`(常态 `?attr/colorOutline`)；`webhtv_styles.xml:212-213`；`:169-172`(`Widget.WebHTV.Helper`=onSurfaceVariant)；错误走 `setError` | 错误色由主题 `colorError` 提供（`webhtv_styles.xml:17`） |
| §9.2 输入背景不得写死白，须用 `colorSurfaceContainerHighest` 或 token | 两端 | 满足 | `selector_dialog_input.xml:5,14` = `?attr/colorSurfaceContainerHighest` | 扫描全部 EditText：**0 处** `android:background` 写死 white/#FFFFFF |
| §9.2 长文本允许换行/滚动；错误信息不被裁剪 | 两端 | 部分满足 | 换行 ✓：`main/dialog_ai_prompt_config.xml:27,49,71,93,115`(maxLines=8)、`dialog_web_home_extension_debug.xml:146,169,376,405`(SafeScrollEditText) | 大量字段仍固定 44dp+maxLines=1（`main/dialog_tmdb_source.xml:59,87,126,172,228`）；"错误信息不被裁剪"未取得证据 |
| §9.2 光标/选择柄/选中背景用 primary 派生色 | 两端 | 部分满足 | 光标 ✓：`main/res/drawable/shape_cursor_dialog.xml:7`=`?attr/colorPrimary`，接线于 `webhtv_styles.xml:214` 与 `:149` | `textSelectHandle` 全库未声明；`colorControlHighlight` **未在 Theme.WebHTV 映射**（不在 `ThemeBaseWiringTest.ACTIVITY_ROLES:21-32` 名单） |
| §9.3 默认 surface；选中/聚焦 secondaryContainer 或 primaryContainer；危险 errorContainer | 两端 | 部分满足 | ✓ `leanback/drawable/shape_site_item_normal.xml:5`、`shape_site_item_selected.xml:16`；`mobile/res/color/selector_item.xml:3`；✗ `leanback/shape_item_normal.xml:5`=black_20、`shape_item_focused.xml:26`/`shape_item_activated.xml:5`=black_40 | 手机旧族完全偏离：`mobile/res/color/selector_video_item.xml:3-7`=black_60/50/30、`selector_video_text.xml:3-7`=yellow_500/white（仍被 `mobile/adapter_flag.xml:7,14`、`adapter_episode_group.xml:8,14`、`adapter_quality.xml:7,11` 使用） |
| §9.3 主标题 onSurface、副标题 onSurfaceVariant、第三行 labelSmall | 两端 | 部分满足 | ✓ 电视卡片 `leanback/adapter_vod.xml:119`/`:134`；✗ `leanback/adapter_vod_list.xml:37,47` 两行同为 OnWallpaper；✗ `mobile/adapter_vod.xml:109,124` 同为 OnWallpaper（`:77-82` 注释说明曾改 onSurfaceVariant 后回退） | `webhtv_on_wallpaper_variant` 已存在（`webhtv_tokens.xml:114`）且实测 6.99:1，手机卡片副标题未采用 |
| §9.3 列表项分隔 outlineVariant；卡片边框 outline | 两端 | 部分满足 | 分隔 ✓：`MaterialDivider` 默认色即 colorOutlineVariant；卡片边框实际用 **outlineVariant**（`main/adapter_tmdb_video.xml:17` 等 40+ 处） | 与规范"卡片边框使用 outline"相反；另有硬编码分隔 `main/.../custom/MpvConfigEditText.java:37`=`#E1E5EA` |
| §9.3 选中标记不能只靠颜色 | 两端 | 部分满足 | ✓ 电视站点列表有勾选框 `leanback/adapter_site.xml:33-40`；✗ 手机 `mobile/adapter_type.xml:11`、`adapter_flag.xml:14`、`adapter_value.xml` 仅换文字色 | 电视 `adapter_vod.xml` 用进度条+角标表达状态，属形状反馈 |
| §9.3 手机长列表支持 ripple；TV 用 focus scale/ring + 可读选中态 | 手机 | 满足 | `mobile/res/drawable/shape_item.xml:2`、`shape_item_round.xml:2`、`shape_vod.xml:2`、`shape_video_item.xml:2`、`shape_type_dialog_item.xml:2` 均为 `<ripple>` | — |
| §9.3 同上（TV 部分） | 电视 | 部分满足 | ring ✓；scale 仅 3 个 adapter（见 §9.1 焦点行） | 站点/分类/选集/文件等 TV 列表无缩放 |
| §9.4 手机卡片圆角 12dp、内边距 12–16dp、整体 surfaceContainer | 手机 | 未满足 | 圆角：`main/res/values/styles.xml:7-10` `Vod.Grid`=8dp（`mobile/adapter_vod.xml:16`）；卡片本体无 background（`mobile/adapter_vod.xml:5-8` 只有 `android:foreground` ripple）；`android:layout_margin="8dp"`(`:7`)、文字区 padding 6dp(`:94-97`) | 圆角差 4dp；内边距 6–8dp 低于 12dp；无 surfaceContainer 卡面 |
| §9.4 TV 卡片圆角 8–12dp，焦点缩放约 1.1x，横向间距 20dp | 电视 | 部分满足 | 圆角 ✓ `styles.xml:7-10,12-14`(8dp) + `leanback/drawable/shape_vod_list.xml:6`；间距 ✗ `leanback/java/.../Product.java:29`=`dp2px(48)+dp2px(16*(column-1))`（16dp） | `@dimen/webhtv_size_tv_card_gap`=20dp（`webhtv_dimens.xml:11`）**全库零引用**；缩放见 §9.1 |
| §9.4 海报 2:3、横向 16:9、人物头像 1:1 | 两端 | 部分满足 | 头像 ✓ `main/java/.../bean/Style.java:43,52`(oval 默认 1.0)；海报 ✗ `Style.java:34` `rect()` ratio=**0.75**；横向 ✗ `Style.java:42` `land==1` 默认 **1.33** | 实际比例由源站 `ratio` 字段决定（`Style.java:41-52`），默认值不是 2:3 / 16:9 |
| §9.4 图片内文字使用 overlayDark 渐变或 scrim | 两端 | 部分满足 | TV 卡带 `leanback/drawable/shape_vod_name.xml:20`=`@color/black_40`；手机卡带 `mobile/drawable/shape_vod_name.xml`=`@color/black_20`；渐变 scrim 仅 TMDB：`main/drawable/shape_tmdb_recommendation_info.xml:5`、`shape_tmdb_episode_overlay.xml:6` | 卡片主色带用裸 alpha 常量而非 `overlayDark` token；`webhtvColorOverlayDark`/`OverlayLight` attr 已声明（`webhtv_attrs.xml:55-56`）但 **Theme.WebHTV 从未赋值**，只能用 `@color/webhtv_color_overlay_*` |
| §9.4 加载失败/空海报/长标题不得改变卡片尺寸 | 两端 | 满足 | 尺寸由列宽+比例预先算出并写入 LayoutParams：`leanback/Product.java:34-38` → `leanback/.../VodRectHolder.java:24-25`；`mobile/Product.java:51-55`；标题单行/跑马灯 | 尺寸与图片/文本内容无关 |
| §9.5 普通标签 surfaceContainerHighest + onSurfaceVariant | 两端 | 未满足 | TV chip **无填充**：`leanback/drawable/shape_chip_normal.xml`、`shape_chip_round_normal.xml` 无 `<solid>`；文字 `?attr/webhtvColorOnWallpaper` | 另有硬编码：`main/drawable/shape_recommendation_source_chip.xml:4-7`=`#E8F0FE`/`#D2E3FC`（暗色表下填充不变）；`main/drawable/shape_lab_group_tab_normal.xml:5`=`@color/black_20` |
| §9.5 选中 secondaryContainer/onSecondaryContainer 或 primaryContainer/onPrimaryContainer | 两端 | 未满足 | TV chip 选中态只有描边：`leanback/drawable/shape_chip_activated.xml:13-15`、`shape_chip_round_activated.xml:13-15`（`?attr/tvPlayerCurrentRing`），无容器填充 | TMDB 详情 chip 用硬编码调色板 `TmdbDetailActivity.java:13654-13692`（`chipActive` 0x6630A86B/0xFFE5F7EC），不经语义 token |
| §9.5 Tab：选中 primary + indicator，未选中 onSurfaceVariant | 两端 | 部分满足 | ✓ `main/dialog_update_settings.xml:68-72`、`leanback/dialog_ad_block_stats.xml:27-34`、`mobile/dialog_episode_grid.xml:57-62`；✗ `mobile/dialog_ad_block_stats.xml:29` `app:tabTextColor="@color/dialog_outlined_button_text"`（该 selector 无 selected 分支，选中/未选中文字同色） | `mobile/res/color/dialog_outlined_button_text.xml:4-5` 默认与 focused 均为 colorPrimary |
| §9.5 TV 必须扩大焦点区域 | 电视 | 部分满足 | 代码接管：`leanback/.../AdBlockStatsDialog.java:123-145`、`main/.../UpdateSettingsDialog.java:161-176`、`PlaybackPerformanceDialog.java:395+` | 焦点表达靠换背景（`selector_mpv_tab_focus.xml` 写死 #E8F0FE/#1A73E8/2dp），不是扩大命中区；未接管的 Tab（如 `main/dialog_mpv_config_manager.xml:54`）无 TV 焦点配置 |
| §9.5 状态标签（成功/警告/错误）用对应 container/on-container 配对 | 两端 | 未满足 | `colorSuccessContainer`/`colorWarningContainer`/`onSuccessContainer`/`onWarningContainer` 仅声明于 `webhtv_attrs.xml:18-23`，**全库无任何布局/代码引用**；error 侧仅 2 处 | 成功/警告状态标签未实现容器配对 |

**子代理 B 的"未能确定"清单**（6 项）：§9.1 一屏一个最高强调动作（需运行态）；§9.1 TV 图标按钮放大；§9.2 错误信息不被裁剪；§9.2 选择柄取色（`textSelectHandle` 全库未声明，由 Material 默认派生）；§9.4 手机卡片内边距的完整覆盖（部分由代码设置）；§9.5 TV chip 是否有代码另设 backgroundTint。

## 7. 优先修复清单（按审计证据排序）

| 优先级 | 问题 | 位置 | 依据 | 性质 |
|---|---|---|---|---|
| **P0** | **设计系统的组件层"只声明、没接线"**：整族 `webhtv_selector_*.xml`（6 个文件）与 `Widget.WebHTV.Button.Filled/Tonal/Outlined/Text`、`Widget.WebHTV.Card`、`Widget.WebHTV.ListItem` **全库零引用**，只被 `UiStyleSourceTest` 的文本断言读取。它们是现成的、已覆盖 disabled/pressed/focused/selected 的语义状态层，却没接到任何视图上 | `app/src/main/res/color/webhtv_selector_*.xml`、`webhtv_styles.xml` | §6.1 | **架构级**——这是绝大多数组件级缺口的共同根因 |
| **P0** | 让静态门槛能跑起来，并把当前唯一的违规修掉 | `scripts/check_ui_tokens.sh` 的可用执行路径；`app/src/mobile/res/layout/item_following.xml` L73/L124 | §2.1、§2.2；§13.1 要求每阶段必做 | 既有问题 + 流程缺口 |
| **P0** | 空态/错误态**描述文字**达标（4.08 / 3.30:1 → ≥4.5:1） | `view_empty.xml` 的 `@id/description` 与 `ProgressLayout` 的错误态 | §3.2 | **本会话引入** |
| **P0** | 已声明但**从未赋值/引用**的语义层：`colorSuccessContainer`/`colorWarningContainer`/`onSuccessContainer`/`onWarningContainer` 零引用；`webhtvColorOverlayDark`/`OverlayLight` attr 从未在 `Theme.WebHTV` 赋值；`@dimen/webhtv_size_tv_card_gap`=20dp 零引用 | `webhtv_attrs.xml`、`webhtv_styles.xml`、`webhtv_dimens.xml` | §6.1 | 声明与实现脱节 |
| P1 | 按钮高度：全库 375 个按钮里仅 **10 个**达 48dp（40dp×113、36dp×55、44dp×32）；`@dimen/webhtv_size_button`=48dp **没有任何按钮 style 用它** | `app/src/*/res/layout/*` | §6.1 §9.1 | 规范未落地 |
| P1 | `FileActivity`（两端）空态三件套 | `app/src/{leanback,mobile}/.../activity/FileActivity.java` | §4 | 遗留项，卡在主动作决策 |
| P1 | 把 §20.2 的九行设备验收矩阵真正跑一遍 | 设备 | §5 | 流程缺口 |
| P1 | 手机端卡片：圆角 8dp（规范 12dp）、内边距 6–8dp（规范 12–16dp）、无 surfaceContainer 卡面 | `main/res/values/styles.xml:7-10`、`mobile/res/layout/adapter_vod.xml` | §6.1 §9.4 | 规范未落地 |
| P1 | 手机端仍在用旧族偏离色：`selector_video_item.xml`=black_60/50/30、`selector_video_text.xml`=yellow_500/white | `mobile/res/color/`；调用方 `adapter_flag.xml`、`adapter_episode_group.xml`、`adapter_quality.xml` | §6.1 §9.3 | 既有问题 |
| P2 | 硬编码/写死的焦点与面板色：`selector_mpv_tab_focus.xml`（#E8F0FE/#1A73E8/2dp）、`shape_recommendation_source_chip.xml`（#E8F0FE/#D2E3FC，暗色表不变）、`shape_lab_group_tab_normal.xml`=black_20；且 **`main/res/drawable` 不在焦点环宽度扫描范围内** | `main/res/drawable/` | §6.1 §9.1/§9.5 | 既有问题 + 门槛盲区 |
| P2 | 危险按钮文字：`following_button_danger_text.xml` 焦点/按下写死 `@android:color/white`，而填充是夜表 `#FFB4AB`，约 **1.9:1** | `main/res/color/following_button_danger_text.xml` | §6.1 §9.1 | 与仓库已修过的同类缺陷 |
| P2 | 手机端卡片副标题未采用已存在的 `webhtv_on_wallpaper_variant`（实测 6.99:1），仍与主标题同色 | `mobile/res/layout/adapter_vod.xml:109,124` | §6.1 §9.3 | 规范未落地 |
| P2 | 卡片边框用 `outlineVariant` 而规范写 `outline`（40+ 处）；海报默认比例 0.75 / 横向 1.33 而非 2:3 / 16:9 | `main/adapter_tmdb_*.xml`、`bean/Style.java:34,42` | §6.1 §9.3/§9.4 | 规范与实现口径不一致 |
| P2 | 弹窗底部强调动作超过两个（`dialog_update.xml`、`dialog_about.xml` 各 3 个）；全库**无任何 `stateDescription`** | `app/src/*/res/layout/dialog_update.xml` 等 | §6.1 §9.1 | 规范未落地 |
| P2 | 把 Java 侧固定色纳入扫描 + allowlist 豁免播放器场景 | `check_ui_tokens.sh` | §2.3 | 门槛盲区 |
| P2 | 播放器错误层的"错误色"决策 | `view_widget_vod.xml` | §4 | 需观感决策 |
| P3 | §10.3 迁移映射表、§11 Web/WebTheme | — | §5 | 未触及的大块 |

## 8. 结论

**不能说"全面优化完毕"。**

- **§9.9（状态面）**：基本完成，剩 `FileActivity` 与描述文字对比度两项，且发现一处**我自己的记录错误**（错误标题其实是达标的）与一处**我自己引入的缺陷**（描述文字不达标）。
- **§9.1–§9.5**：审计已完成（§6.1）。判定以"部分满足 / 未满足"为主。**最重要的结论是根因性的**：设计系统的组件层（`webhtv_selector_*` 状态色族、`Widget.WebHTV.Button.*`/`Card`/`ListItem`、成功/警告容器角色、`webhtv_size_tv_card_gap`、`overlayDark`/`OverlayLight`）**大量只声明、没接线**——规范写好了、资源建好了、契约测试也钉住了它们的**存在**，但视图实际消费的是另一套东西。所以"规范符合度"低不是因为规范没写，而是因为**声明层与消费层之间缺一次真正的迁移**（正是 §10.3 迁移映射表要做的事，而它本会话完全未触及）。
- **§7、§8、§9.6–§9.8**：审计进行中，尚无结论。
- **§10.3、§11**：本会话完全未触及。
- **流程**：§13.1 要求的静态门槛从未运行且**当前失败**；§13.2/§20.2 的场景与设备矩阵**没有一行被完整执行**。

"全面"缺的不是更多批次，而是**先让门槛能跑、再按矩阵验收**，以及**把声明层真正接到消费层**。P0 四项是明确、可执行的起点。
