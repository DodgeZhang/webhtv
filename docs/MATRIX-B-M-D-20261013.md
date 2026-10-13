# MATRIX-B-M-D-20261013：B-M-D 行验收（mobile / dark）——发现未修复缺陷

## Recovery anchor

- 目标：执行 §20.2 矩阵的 B-M-D 行（mobile / dark，通过标准「无浅色孤岛」）。
- 性质：**验收 + 缺陷定位**。本任务**不改生产代码**——定位到的缺陷需要独立修复，且我的三次修法尝试均未生效，已按纪律停止并撤销。
- 保护面：任务开始时工作树仅 `.design-evidence/` 未跟踪；分支 `dev4`，HEAD `9e2da076091`。
- 结论：**不通过**。发现整族 BottomSheet（33 个子类）在深色模式下渲染成浅色面板。
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

### 1.2 不通过的部分：BottomSheet 是浅色孤岛

| 弹窗 | 结果 | 证据 |
|---|---|---|
| 「一键同步」BottomSheet | **浅灰白面板**（`#E7E8EF`）压在深色应用上 | `B-M-D-08-dialog.png` |

**像素级证据**：面板中心采样 `#E7E8EF`，而深色表的 `webhtv_color_surface_container_high` 是 `#2A2F34`。`#E7E8EF` 正是**浅色表**的值。

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

### 2.3 为什么 `setStyle` 不解决问题

`DialogFragment.setStyle` 设的是 `mTheme`，`getTheme()` 返回它。但 `BottomSheetDialogFragment.onCreateDialog` 把它作为 `themeResId` 传给 `BottomSheetDialog` 构造器——**当它为 0 时走上面那条"从上下文解析"的路径**。

实测验证：我加了 `setStyle(STYLE_NORMAL, R.style.ThemeOverlay_WebHTV_BottomSheet)` 后重新构建安装，**截图与修复前 sha256 完全相同**（`7ca7c53c...`，86683 字节）——行为零变化。

## 3. 三次修法尝试与它们为什么失败（诚实记录）

| # | 尝试 | 结果 | 为什么失败 |
|---|---|---|---|
| 1 | 新增 `ThemeOverlay.WebHTV.BottomSheet`（父主题 = `ThemeOverlay.Material3.BottomSheetDialog`）+ 基类 `setStyle` | 截图字节**完全相同** | ① `setStyle` 在这条路径上不参与解析（§2.3）；② 父主题选错——`ThemeOverlay.*` 是**叠加层**（parent 为空，不含基础主题颜色），会落到系统默认 |
| 2 | 把父主题改为 `Theme.Material3.DayNight.BottomSheetDialog`（**完整主题**） | 截图字节**完全相同** | 父主题改对了，但接线点仍只有 `setStyle`，未解决 §2.3 |
| 3 | 在 `Theme.WebHTV` 里加 `<item name="bottomSheetDialogTheme">` | 截图字节**完全相同** | **未查明**。这条是 Material 官方推荐做法，`aapt2 dump resources` 也确认 APK 里有该资源，但运行时面板仍是浅色。可能的原因：`Theme.WebHTV.Mobile`（`<style name="Theme.WebHTV.Mobile" parent="Theme.WebHTV" />`）或 Activity 实际用的主题不是 `Theme.WebHTV`；也可能解析发生在另一个 context 上 |

**三次尝试全部撤销**（`git checkout`），因为留着无效改动会让人误以为已修——这比没有改动更糟。

## 4. 为什么停止而不是继续

- 已用 3 轮修法尝试 + 多轮定位，**每次都以"截图字节完全相同"告终**；
- 按仓库纪律（`AGENTS.md` §3：同一假设两次失败后必须换路径；§4：不为同一结论重复检查），继续试错是低效的；
- 更重要的：**这个缺陷的修复需要先确认 Activity 实际使用的主题**（一个我尚未验证的事实），那是新的调查方向，不是继续改同一个文件。

## 5. 修复建议（供后续批次）

1. **先确认 Activity 实际主题**：`Theme.WebHTV.Mobile` 是 `Theme.WebHTV` 的空子类，但 manifest 里 Activity 可能指向别的主题。用 `dumpsys activity top` 或临时在 `BaseBottomSheetDialog` 里打印 `requireContext().getTheme()` 的 `resourceId` 来确认。
2. **确认 `bottomSheetDialogTheme` 的解析上下文**：`BottomSheetDialogFragment.onCreateDialog` 用的是 `requireContext()`，而 Fragment 的 context 在 `onAttach` 后是 Activity——理论上与 Activity 主题一致。若不一致，说明 Fragment 被 attach 到了别的 context（如 `ContextThemeWrapper`）。
3. **可选的兜底方案**：在 `BaseBottomSheetDialog.onCreateDialog` 里**不依赖 Material 的解析**，直接 `new BottomSheetDialog(requireContext(), R.style.ThemeOverlay_WebHTV_BottomSheet)` 并自己 `setContentView`——绕过 `getThemeResId` 的回落逻辑。这是最确定的做法，但偏离了 Material 的推荐用法。
4. **修复后必须重跑 B-M-D**：本行的通过标准是"无浅色孤岛"，而 33 个子类都要覆盖（至少抽查 3–4 个不同类型的）。

## 6. 相邻发现

1. **Alert 对话框家族是正常的**：`ThemeOverlay.WebHTV.Dialog` 通过 `materialAlertDialogTheme` 接线，且 §20.2 的其它行此前已验证过 Alert 弹窗深色正常——所以问题**只在 BottomSheet 家族**。
2. **`webhtv_styles.xml:79-81` 的注释是准确的**：它明确写了"两者都不继承本主题"，只是当时只处理了 Alert 那一半（`materialAlertDialogTheme` 有接线），BottomSheet 那一半从未接。
3. **这一行验收的价值**：它发现的是**整族 33 个弹窗**的问题，而不是单个页面的瑕疵——这正是"按矩阵系统执行"相对"针对单个改动定向观测"的价值所在。此前所有批次都没碰到它，因为没人打开过 BottomSheet。
