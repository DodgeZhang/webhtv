# MATRIX-B-M-D-20261013：B-M-D 行验收（mobile / dark）——发现未修复缺陷

## Recovery anchor

- 目标：执行 §20.2 矩阵的 B-M-D 行（mobile / dark，通过标准「无浅色孤岛」）。
- 性质：**验收 + 缺陷定位**。本任务**不改生产代码**——定位到的缺陷需要独立修复，且我的三次修法尝试均未生效，已按纪律停止并撤销。
- 保护面：任务开始时工作树仅 `.design-evidence/` 未跟踪；分支 `dev4`，HEAD `9e2da076091`。
- 结论：**不通过**。发现整族 BottomSheet（33 个子类）的**主题与当前模式完全相反**（见 §1.3 的修正结论）。
- 下一步唯一动作：无（本任务已交付结论）。修复见 §5。

## 1. 验收结论

| 项 | 结果 |
|---|---|
| 设备 | `192.168.50.3:5561`，API 28，1080×1920 竖屏 |
| 模式 | mobile flavor + `ui_night_mode=1`（深色） |
| 通过标准 | 文字、输入、按钮、Dialog 对比度达标；**无浅色孤岛** |
| **判定** | **不通过** |

### 1.1 通过的部分

| 页面 | 结果 | 证据 |
|---|---|---|
| 首页（卡片网格） | 深色正常 | `B-M-D-01-home.png` |
| 设置页（分组、取值、图标） | 深色正常 | `B-M-D-02-settings.png` |
| 增强功能页 | 深色正常 | `B-M-D-03-enhance.png` |
| 实验室页（卡片列表） | 深色正常 | `B-M-D-04-lab.png` |
| 实验室详情页（php） | 深色正常 | `B-M-D-05-lab-dialog.png` |

### 1.2 不通过的部分

| 弹窗 | 结果 | 证据 |
|---|---|---|
| 「一键同步」BottomSheet（深色模式） | **浅灰白面板**（`#E7E8EF`）压在深色应用上 | `B-M-D-08-dialog.png` |

### 1.3 **结论修正**：不是"深色孤岛"，而是**主题反转**

在 B-M-L 行（浅色模式）里打开**同一个** BottomSheet 后，得到决定性的对照：

| 模式 | 面板实测色 | 对应 token | 判定 |
|---|---|---|---|
| **深色** | `#E7E8EF` | **浅色表**的 `surface_container_high` | ❌ 用了反的表 |
| **浅色** | `#2A2F34` | **深色表**的 `surface_container_high` | ❌ 用了反的表 |

**BottomSheet 家族的主题与当前模式完全相反。** 所以：

- 它在**两种模式下都是错的**，不只是深色模式；
- 深色模式下表现为"浅色孤岛"（醒目），浅色模式下表现为"深色面板"（同样错，只是用户在浅色下更容易接受深色面板而忽略）；
- 这解释了为什么三次修法都无效——问题不是"没接线"，而是**接到了错误的方向**。

证据：`B-M-D-08-dialog.png`（深色模式，面板 `#E7E8EF`）与 `B-M-L-03-sheet.png`（浅色模式，面板 `#2A2F34`）。

## 2. 根因（已定位到具体机制）

### 2.1 影响面：33 个 BottomSheet 子类

`BaseBottomSheetDialog` 有 **33 个子类**（`OneKeySyncDialog`、`CastDialog`、`ControlDialog`、`FilterDialog`、`TypeDialog`、`EpisodeGridDialog`、`SubtitleDialog`、`TrackDialog`、`QuickSearchDialog`、`LiveDialog` 等），它们全部继承同一个基类，因此**全部受影响**。

### 2.2 机制：`BottomSheetDialogFragment` 不继承应用主题

`webhtv_styles.xml:79-81` 的既有注释**早就写明**：

> BottomSheet 用的是 Material 的 `bottomSheetDialogTheme`，Alert 对话框用的是 `Theme.Material3.DayNight.Dialog.Alert`，**两者都不继承本主题**。

反编译 Material 1.14.0 的 `BottomSheetDialog.getThemeResId(Context, int)` 得到确切的回落逻辑：

```java
private static int getThemeResId(Context context, int themeResId) {
    if (themeResId == 0) {
        TypedValue outValue = new TypedValue();
        if (context.getTheme().resolveAttribute(R.attr.bottomSheetDialogTheme, outValue, true)) {
            themeResId = outValue.resourceId;
        } else {
            themeResId = R.style.Theme_Design_Light_BottomSheetDialog;   // ← 硬编码浅色
        }
    }
    return themeResId;
}
```

即：**themeResId 为 0 时，它从上下文主题解析 `bottomSheetDialogTheme`；解析不到就硬编码回落到浅色主题。**

**但 §1.3 的对照结果说明：真正的机制比这条回落更复杂**——面板在深色模式下取到了浅色表的值、在浅色模式下取到了深色表的值。这不是"回落到硬编码浅色"能解释的（那会让两种模式都是浅色）。

**修正后的假设**：`shape_one_key_sync_dialog` 的 `?attr/colorSurfaceContainerHigh` 是在**某个固定配置的 context** 上解析的，而那个 context 的 `uiMode` 与当前模式相反。最可能的原因是 `BottomSheetDialog` 拿到的 context 来自 **`ThemeController` 的静态 `current`（默认 `ThemeTokens.light()`）**，或某个缓存了旧配置的 `ContextThemeWrapper`。**这一点尚未验证**，是修复前必须确认的第一个事实（见 §5.1）。

### 2.3 为什么 `setStyle` 不解决问题

`DialogFragment.setStyle` 设的是 `mTheme`，`getTheme()` 返回它。但 `BottomSheetDialogFragment.onCreateDialog` 把它作为 `themeResId` 传给 `BottomSheetDialog` 构造器——**当它为 0 时走上面那条"从上下文解析"的路径**。

实测验证：我加了 `setStyle(STYLE_NORMAL, R.style.ThemeOverlay_WebHTV_BottomSheet)` 后重新构建安装，**截图与修复前 sha256 完全相同**（`7ca7c53c...`，86683 字节）——行为零变化。

## 3. 三次修法尝试与它们为什么失败（诚实记录）

| # | 尝试 | 结果 | 为什么失败 |
|---|---|---|---|
| 1 | 新增 `ThemeOverlay.WebHTV.BottomSheet`（父主题 = `ThemeOverlay.Material3.BottomSheetDialog`）+ 基类 `setStyle` | 截图字节**完全相同** | ① `setStyle` 在这条路径上不参与解析（§2.3）；② 父主题选错——`ThemeOverlay.*` 是**叠加层**（parent 为空，不含基础主题颜色），会落到系统默认 |
| 2 | 把父主题改为 `Theme.Material3.DayNight.BottomSheetDialog`（**完整主题**） | 截图字节**完全相同** | 父主题改对了，但接线点仍只有 `setStyle`，未解决 §2.3 |
| 3 | 在 `Theme.WebHTV` 里加 `<item name="bottomSheetDialogTheme">` | 截图字节**完全相同** | **未查明**。`aapt2 dump resources` 确认 APK 里有该资源，但运行时面板仍反转。结合 §1.3 的新证据，最可能是**解析用的 context 配置本身就是反的**——那样改主题属性当然无效 |

**三次尝试全部撤销**（`git checkout`），因为留着无效改动会让人误以为已修——这比没有改动更糟。

## 4. 为什么停止而不是继续

- 已用 3 轮修法尝试 + 多轮定位，**每次都以"截图字节完全相同"告终**；
- §1.3 的对照结果**改变了问题的性质**（不是缺接线，是配置反转），意味着需要先确认一个新事实，而不是继续改同一个文件；
- 按仓库纪律（`AGENTS.md` §3：同一假设两次失败后必须换路径；§4：不为同一结论重复检查），继续试错是低效的。

## 5. 修复建议（供后续批次）

1. **先确认解析用的 context 配置**（修复前必须做的第一件事）：在 `BaseBottomSheetDialog.onCreateDialog` 里临时打印
   `requireContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK`
   以及 `getTheme().resolveAttribute(R.attr.colorSurfaceContainerHigh, ...)` 的解析结果。
   若 context 的 `uiMode` 与系统相反，根因就在 context 来源（`ContextThemeWrapper` / 缓存的 `ThemeTokens`）。
2. **检查 `ThemeController` 的静态默认值**：`ThemeController.java:23-24` 是
   `private static volatile ThemeTokens current = ThemeTokens.light();` / `baseline = ThemeTokens.light();`
   ——若某个 inflate 路径在 `ThemeController` 初始化之前跑，就会拿到 `light()`。
3. **可选的兜底方案**：在 `BaseBottomSheetDialog.onCreateDialog` 里不依赖 Material 的解析，直接
   `new BottomSheetDialog(requireContext(), R.style.<完整主题>)` 并自己 `setContentView`——绕过 `getThemeResId` 的回落逻辑。
4. **修复后必须重跑 B-M-D 与 B-M-L 两行**：因为缺陷是**双向**的，只验一行会漏掉另一半。抽查 3–4 个不同类型的子类（表单类、列表类、播放控制类）。

## 6. 相邻发现

1. **Alert 对话框家族是正常的**：`ThemeOverlay.WebHTV.Dialog` 通过 `materialAlertDialogTheme` 接线，且此前已验证过 Alert 弹窗深色正常——所以问题**只在 BottomSheet 家族**。
2. **设置页行底是半透明压在壁纸上**：B-M-L 实测行底 `#675E79`，而浅色表 `surface_container_high` 是 `#E7E8EF`——差得很远，说明行底不是纯 token 色而是壁纸透出。白字在其上 **6.03–6.08:1** ✓ 达标。这也解释了为什么设置页在两种模式下视觉相近（都被壁纸主导）。
3. **B-M-L 的其余部分**：首页、设置页在浅色模式下渲染正常（`B-M-L-01-home.png`、`B-M-L-02-settings.png`）；对比度实测达标。**B-M-L 的完整判定待全部场景跑完后给出**。
4. **这一行验收的价值**：它发现的是**整族 33 个弹窗**的问题，且**双向**——这正是"按矩阵系统执行"相对"针对单个改动定向观测"的价值。此前所有批次都没碰到它，因为没人打开过 BottomSheet。
