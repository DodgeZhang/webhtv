# HOME-STATE-NO-CONFIG-20261018：重置 / 恢复备份后首页卡在「内容源加载失败」，杀进程才恢复

## Recovery anchor

- 目标：①「还没配接口」不再被当成加载失败——首页显示可执行的空态（主动作=添加接口）而不是「内容源加载失败 + 重试」；②配置在**别的路径**（恢复备份、远程指令、设置页、推送）变可用时，首页的状态浮层自己收起，不必杀进程冷启动。
- 允许路径：`app/src/main/java/com/fongmi/android/tv/{api/config/BaseConfig.java,api/config/LiveConfig.java,event/StateEvent.java,impl/Callback.java}`、`app/src/{leanback,mobile}/java/com/fongmi/android/tv/ui/{activity/HomeActivity.java,fragment/VodFragment.java}`、`app/src/main/res/values{,-zh-rCN,-zh-rTW}/strings.xml`、`app/src/testLeanback/java/.../TvErrorStateContractTest.java`、`app/src/testMobile/java/.../MobileHomeStateContractTest.java`、本文件。
- 保护面：任务开始时工作树**干净**（预存脏路径 0 个）；分支 `dev3`，HEAD `44e2dad6b0d73cca92e57ba253b04bd3a8605c0a`。
- 验收：①重置/无配置启动不再出现错误态与死重试；②两端契约测试全绿；③`BaseConfig` 不再把空地址发成 `error("")`；④配置可用后状态浮层能自收（用户在错误态上完成恢复备份能直接回到内容）。
- 回滚：撤销本任务原子提交即可回到「无配置=error("")第」的旧行为；无数据迁移、无 schema 变更。
- 交付坐标：提交 `0f1dc0c615b`（`dev3`），recovery tag `recovery/HOME-STATE-NO-CONFIG-20261018/20261018125200`；未推送。
- 下一步唯一动作：无（本批已交付）。后续候选：`LiveConfig` 的同类空态、`StateEvent.content()` 目前仍无生产者（见 §5）。

## 0. 真机验证（决定性证据）

现网设备全都已带配置，所以**没有**在现网设备上清数据，而是用一个新的 Android 用户做干净安装（非破坏性，验完即删）：

```powershell
adb -s emulator-5554 shell pm create-user noconfig          # → user 11
adb -s emulator-5554 shell pm install-existing --user 11 com.silent.android.webhtv
adb -s emulator-5554 shell am switch-user 11
adb -s emulator-5554 shell am start --user 11 -n com.silent.android.webhtv/com.fongmi.android.tv.ui.activity.HomeActivityCurrent
```

`uiautomator dump` 结果（`F:\temp\rea-webhtv-shots\ui_user11_empty.xml`）：

| resource-id | text | clickable |
|---|---|---|
| `id/text` | 还没有配置接口 | false |
| `id/description` | 添加一个点播接口后即可浏览内容。 | false |
| `id/action` | 添加接口 | **true** |

- 截图 `F:\temp\rea-webhtv-shots\user11_home2.png`：空态三件套齐全、**标题不是错误色**、插画（空箱子）保留、**没有「内容源加载失败」也没有「重试」**。
- 点「添加接口」→ 打开接口列表（`id/add` = 新增，列表为空）→ 点「新增」→ 出现「新增点播 / 接口名称 / 请输入配置…」输入框：**主动作真的能把用户带出去**。
- 反向回归：同一台设备在**有配置**的用户 0 上启动，首页照常加载出真实内容（`id/title` = `YouTube[官]` 加分类与推荐列表），说明改动没有影响正常路径。
- 测试用户已删除（`pm remove-user 11`），设备回到 user 0。

## 1. 现象

用户报告（附截图：居中「内容源加载失败」+「重试」，没有原因行）：

> 重置 app 就有问题了，应该是 bug
>
> 实测卸载后重新安装再通过备份恢复也会出现类似情况必现杀进程后重进才行，应该是没有及时刷新状态吧

两个可复现事实：**重置后出现错误态**；**恢复备份后必须杀进程重进**。

## 2. 证据

| 来源 | 位置 | 结论 |
|---|---|---|
| `BaseConfig.loadConfig` | `BaseConfig.java:127`（改动前） | `if (TextUtils.isEmpty(config.getUrl())) App.post(() -> callback.error(""))` ——**没有地址被当成"失败"**，且 message 为空 |
| 截图 | 用户提供 | 只有标题 + 重试、**没有原因行** → 正是 `reason == null/空` 的分支（`ProgressLayout.applyErrorContent` 里空原因会 `setVisibility(GONE)`）；真实异常会带 `error_config_get`「配置取得失败」 |
| 重置设备实测 | `192.168.50.3:5555`（mobile） | `config` 表 0 行、无 `config_0` 偏好；重置后重启首页是**空的**（无错误态、无重试），因为该机安装的 APK 早于错误态提交 |
| 设备实测 | `192.168.50.3:5557`（leanback） | `config` 表 22 行含 `config_0`，两端字符串齐备 |
| `VodConfig.postEvent` → `ConfigEvent.vod` | `VodConfig.java:167` | 配置加载结束发的是 `ConfigEvent`，**不经过首页的状态出口** |
| 状态出口 | `leanback HomeActivity.showContent()` / `VodFragment.onStateEvent(PROGRESS/CONTENT)` | 浮层只被「又一次启动 / 点重试」收掉 |
| 恢复备份路径 | `AppBackup.reload()` → `VodConfig…load(new Callback())` + `RefreshEvent.home()`（`AppBackup.java:275`） | 走的是 `RefreshEvent.home()`，**没有**任何一步收状态浮层 → 配置已可用而首页仍顶着状态卡 |

**根因两条**

1. **语义错位**：接口列表为空不是失败（没有地址可请求、没有失败原因、重试永远不会成功），却被编码成 `error("")`。错误态忠实地把它渲染成「内容源加载失败 + 重试」——一个不存在的故障加一个点了没用的按钮。
2. **状态出口缺失**：状态浮层只有「启动成功」与「点重试」两个出口，而配置可能在恢复备份、远程指令、设置页切换、推送接收这些路径上变可用（它们发 `ConfigEvent` / `RefreshEvent.home()`）。出口不覆盖这些路径 → 必须杀进程冷启动。

## 3. 改动

| 文件 | 改动 |
|---|---|
| `impl/Callback.java` | 新增 `noConfig()` 钩子（默认空实现，不破坏既有 53 处匿名实现） |
| `api/config/BaseConfig.java` | 空地址 ⇒ `callback::noConfig`（原来是 `callback.error("")`） |
| `event/StateEvent.java` | 新增 `NO_CONFIG` 类型与 `noConfig()` 工厂；与 `ERROR` 的区别写进注释 |
| `mobile HomeActivity.getCallback()` | 实现 `noConfig()`：重置 chrome → `StateEvent.noConfig()`（**不弹吐司**，这不是失败） |
| `mobile VodFragment` | `case NO_CONFIG:` ⇒ `showNoConfig()`：三件套空态，主动作 `openVodConfig()`（`HistoryDialog…manage()`，自带新增） |
| `mobile VodFragment.onRefreshEvent` | `case HOME:` 先 `dismissStateIfConfigured()`：配置真有站点才收浮层 |
| `leanback HomeActivity.getCallback()` | 实现 `noConfig()`：`showNoConfig()` 走 `ProgressLayout` 空态 + `openVodConfig()` |
| `leanback HomeActivity.onRefreshEvent` | `case HOME:` 先 `dismissStateIfConfigured()`：`isEmpty()/isError()` 且配置有站点时 `showContent()` |
| 三语 `strings.xml` | `home_no_config_title/description/action`（简中「还没有配置接口 / 添加一个点播接口后即可浏览内容。/ 添加接口」） |
| 两端契约测试 | 新增 `missingConfigIsNotTreatedAsAFailure`、`aConfiguredSourceDismissesAStaleStateOverlay`；leanback 补 `methodBody` 辅助方法 |

**刻意不做的**：不改 `error(String)` 的语义（真失败仍走错误态 + 原因 + 重试）；不复用 `EMPTY`（它表达"确实没有内容"，且 TV 端的 `EMPTY` 主动作是"重新加载"，对无配置同样无效）；不删 `Notify.show(msg)`（既有行为）。

## 4. 验证

- `testMobileArm64_v8aDebugUnitTest --tests *MobileHomeStateContractTest`：**9 tests, 0 failures**。
- `testLeanbackArm64_v8aDebugUnitTest --tests *TvErrorStateContractTest`：**13 tests, 0 failures**。
- `compileMobileArm64_v8aDebugJavaWithJavac compileLeanbackArm64_v8aDebugJavaWithJavac`：通过。
- 全量移动端单测另有 **6 个既有失败**，均读取本任务未触碰的文件（`VideoActivity` 的 TMDB source-only、`PlaybackActivity/NovelRouter` 阅读器、`assets/css/ui.css` 令牌作用域、`mpvplayer` 的 `MpvFontConfig/MpvHlsCacheCoordinator`），已核对其读取路径与本 diff 无交集 → **预存失败，不在本任务范围**（按 AGENTS.md §2/§4 报告不修）。
- 真机验证：见「下一步唯一动作」。

## 5. 已知边界

- **任务守卫未运行**：本机 `bash` 不可用（`C:\WINDOWS\system32\bash.exe -c 'echo bash-ok'` → `HCS_E_SERVICE_NOT_AVAILABLE`），按下文「许可路径」逐项手工核对后提交（与 `TV-ERROR-STATE-20261012` 等既有任务同一限制）。
- 手机端 `StateEvent.content()` 仍**没有生产者**（`MOBILE-HOME-ERROR-STATE-20261013.md` §6.5 已记录）。本次的收口放在 `RefreshEvent.HOME`，是"配置可用"必然伴随的事件；若将来有路径只改配置而不发 HOME，需要在 `onConfigEvent(VOD)` 里补同一条判断。
- TV 端 `dismissStateIfConfigured()` 用 `isEmpty() || isError()` 判定，是因为 `ProgressLayout` 没有 `isContent()` 之外的"已在内容态"语义；它只在 HOME 刷新时执行，不会影响 PROGRESS。
- `LiveConfig` 未新增 `noConfig` 覆写：直播没有配置时不进首页状态机（`LiveActivity` 自成一套），本次不扩大范围。
- 空态的主动作在**没有任何接口**时会打开一个空列表（带「新增」）而不是直接弹新增框；多一步但不会误导（`ConfigAdapter` 的 `onAdd` 就在列表里）。做成直连需要动 `HistoryDialog` 的既有行为，不在本次范围。