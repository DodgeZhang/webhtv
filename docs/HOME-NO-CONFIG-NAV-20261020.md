# HOME-NO-CONFIG-NAV-20261020：清数据后首页只剩一个按钮，点播/直播/设置整栏消失

## Recovery anchor

- 目标：无配置（重置/重装/恢复出厂）时首页仍是**正常首页**——导航可用、有常驻的「添加接口」入口；不再由全屏状态浮层接管首页。两端（leanback / mobile）同一规则。
- 允许路径：`app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`、`app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`、`app/src/mobile/java/com/fongmi/android/tv/ui/fragment/VodFragment.java`、两端 `home-state` 契约测试、本文件。
- 保护面：任务开始时工作树干净（仅未跟踪 `.wh_diag/`）；分支 `dev2`，HEAD `5591f92612`。
- 验收：①5557 上 `pm clear` 后冷启动，首页渲染出导航行（含「添加接口」）而不是空态三件套；②点「添加接口」能进入接口列表；③手机端底部导航同样保留且有该入口；④两端契约测试全绿。
- 回滚：撤销本任务原子提交即回到「无配置 = 全屏空态」的旧行为；无数据迁移、无 schema 变更。

## 0. 现象与根因

现象（用户截图 + 5557 现场 `uiautomator dump`）：`pm clear` 后只有「还没有配置接口 / 添加一个点播接口后即可浏览内容。/ 添加接口」，**点播/直播/设置整栏消失**。

根因不是"被藏起来了"，而是**导航行本身就在被隐藏的内容里**：

- 电视端的点播/直播/设置不是独立导航栏，而是首页 `VerticalGridView` 的第一行：
  `HomeActivity.setAdapter()` 把 `mFuncAdapter` 加成一个 `ListRow`，`setFunc()` 才往里填按钮，而 `setFunc()` 只由 `showContent()` 等路径调用。
- `showNoConfig()` 走的是 `progressLayout.showEmpty()` → `switchState(EMPTY)` → `setContentVisibility(false)` → `hideView(recycler)`（`INVISIBLE`）。**导航行随内容一起被隐藏**；即使不隐藏，`setFunc()` 从未执行，这一行也是空的。

历史：`f838870134` 引入全屏错误态、`0f1dc0c615` 引入全屏无配置空态，两次都把"用状态浮层接管首页"当作统一表达；`f838870134` 之前失败路径是 `Notify.show(msg)` + `showContent()`，导航是在的。因此**裸 revert `0f1dc0c615` 修不了导航**：会退回 `error("")` → 全屏错误态，导航照样没有，还会复活已修的两个 bug。

## 1. 决策

保留 `0f1dc0c615` 的语义修正（无配置 ≠ 失败；`BaseConfig` 不再发空 `error("")`；配置可用时自收状态浮层），**只改呈现**：

| 端 | 无配置时的行为 |
|---|---|
| leanback | `showNoConfig()` → `showContent()`（导航/标题/logo 全部就位）；`setFunc()` 在配置为空时追加一个「添加接口」按钮（点击 → `HistoryDialog.vod().manage()`） |
| mobile | `VodFragment.showNoConfig()` → `hideState()` + `showNativeContent()`（不再独占整屏）；`HomeActivity` 在底部导航栏动态加一个「添加接口」项（点它开接口管理并保持当前选中页） |

两端同一条规则：**引导放进用户已经在看的那一条导航栏，而不是拿一个铺满首页的空态把导航一起接管。**

手机端实现要点（真机踩到过）：`HistoryDialog.onAttach` 会把宿主强转 `ConfigListener`，手机端宿主是 `VodFragment`，所以入口必须经 `VodFragment.openVodConfig()` 承载；直接用 Activity 承载会 `ClassCastException`（首次真机验证就崩了，已修）。

## 2. 验证（证据）

构建：`assembleLeanbackArm64_v8aDebug` + `assembleMobileArm64_v8aDebug` 成功；
契约测试：`TvErrorStateContractTest`（14 条）+ `MobileHomeStateContractTest` 全绿。

**5557（SM-N9700 / 1920x1080，TV 包）**：`install -r` → `pm clear` → 冷启动 → `uiautomator dump`：

```
text="搜索"  text="收藏"  text="推送"  text="设置"  text="添加接口"
text="最近观看"  text="更新推荐"  text="默影视"  text="18:24:40"
```

- 导航行回来了；「添加接口」是这一行的第 5 个 chip，`focused="true" selected="true"` 可聚焦。
- 按 `DPAD_CENTER` → 进入接口列表（整屏只剩 `text="新增"`），说明主动作真的能出去。
- 注：清数据后 **直播** 仍不显示（`LiveConfig.hasUrl()` 为假）、**点播** chip 默认不显示（`HomeButton.getDefaultButtons()` 在"首页自动加载点播"开启时不含 id 0）——两者都是既有设计，不是本次回归。

**emulator-5560（手机包）**：`install -r` → `pm clear` → 冷启动 → dump：

```
content-desc="点播"  content-desc="设置"  content-desc="添加接口"(clickable="true")
```

点「添加接口」→ 弹出接口列表（`text="新增"`），无新 crash；顶部 `appBar/typeBar/pager` 与底部导航同时存在，首页不再是全屏空态。

## 3. 未在设备上覆盖的残留

- 「配置就绪后入口自动消失」只在代码路径上验证（`setNavigation()` 由 `onConfigEvent`/`onResume` 触发；电视端 `onResume()` 会重算 `setFunc()`），未在真机上走一遍"新增接口→返回→入口消失"。
- 两端"错误态"（配置存在但加载失败）仍走全屏错误浮层，导航依旧不可用——这是 `f838870134` 的既有行为，本次按范围未动；如需统一，另开任务。

## 4. 下一步唯一动作

合并/推送由用户决定；如需继续，候选任务是上面的"错误态也保留导航"。
