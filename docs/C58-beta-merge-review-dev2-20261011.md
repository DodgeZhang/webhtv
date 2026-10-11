# C58：dev2 合并远端 beta 最新代码并复评已修改代码（含已提交未推送）

## Recovery anchor

- **目标**：把远端 `beta` 最新代码合入 `dev2`（**远端已移除/回退的提交不得顺带带回**）；复评 dev2 全部已修改代码（含**已提交未推送**的 `97c7ae68ab`「退出播放页必定停播」修复）；发现问题修复并验证通过；循环评审直至通过；然后提交、推送 `dev2`、创建 `dev2 -> beta` 中文 PR（**只创建，不合并**）。
- **验收标准**：① `dev2` 必须包含合并时刻的 `origin/beta` tip；② 远端被移除/回退内容**零复活**、beta 增量**零丢失**、dev2 既有改动**零丢失**；③ 双 flavor Java 编译 + 双 flavor AndroidTest Java 编译通过；④ 双 flavor 全量 JVM 套件失败集与未触碰基线**逐类一致**（无新增失败）；⑤ 复评发现的问题已修复并锁定（或按 `AGENTS.md` §2 明确记录处置）；⑥ 提交 + recovery tag、`dev2` 已推送、PR 已创建且**未合并**，PR 文件集与 `git diff --name-only origin/beta HEAD` 逐项一致。
- **当前状态**：**本轮无需合并**（`origin/beta` tip 已是 `dev2` 的直系父提交，见「合并台账」）；2 轮复评完成，**未发现必修缺陷、未改一行生产代码**；双 flavor 编译 + 双 flavor 全量 JVM 套件与文档基线**逐类一致**（0 新增失败）；零复活三层证据通过。
- **交付坐标**：见文末「交付坐标」与「闭环记录」。
- **下一动作**：无（本任务已闭环）。

## 时间与设备

- 任务开始时本地时间：2026-10-11 11:28（Asia/Shanghai）；记录时 12:0x。
- 任务开始时工作区**干净**（`git status --porcelain` 为空；仅有两份被 `.gitignore:27 *.log` 忽略的验证日志，未进入版本控制）。
- 设备：**本轮未使用真机/模拟器**。理由（风险相符原则，`AGENTS.md` §4）：本轮**未改一行生产代码**——复评对象是已提交未推送的 `97c7ae68ab`，其改动面只有 `PlaybackActivity.stopPlayback()/startPlayer()` 两处语义与两条源码契约测试；本轮唯一新增物是本任务文档。该提交的退出停播语义由全量套件（双 flavor）与定向契约用例覆盖，故未申请机位、未重装 APK。

## 合并台账

| 项 | 值 |
| --- | --- |
| 本地分支 | `dev2`（worktree `F:/Workspace/webtv2/webhtv`） |
| 任务开始时 HEAD | `97c7ae68abd9be76d2bdd1d79e34d93a9f6925ae` |
| 任务开始时 `origin/dev2` | `34a93e243f660df19b2b23eef724f0f882002e8b` |
| **`origin/beta` tip（`git ls-remote` 权威）** | `fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5`（Merge pull request #433 from Silent1566/dev2） |
| `git merge-base dev2 origin/beta` | `fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5`（**= beta tip 本身**） |
| `HEAD` 的父提交 | `fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5`（`git rev-list --parents -n1 HEAD`） |
| 是否需要新合并提交 | **不需要**：`git merge-base --is-ancestor origin/beta dev2` **EXIT=0**（beta tip 已在 dev2 祖先链）；反向 `--is-ancestor dev2 origin/beta` **EXIT=1**（dev2 仅比 beta 多 1 个提交） |
| dev2 相对 beta 的净差异 | `git diff --name-status origin/beta HEAD` = **2 路径**（均为修改）：`PlaybackActivity.java`、`PlaybackOwnershipSourceTest.java` |
| 推送方式 | **快进**：`git merge-base --is-ancestor origin/dev2 dev2` EXIT=0；`git push --dry-run origin dev2` = `34a93e243f..97c7ae68ab dev2 -> dev2` |
| 历史完整性 | 本轮**未使用** `git reset`/`--amend`/`rebase`；`HEAD` 单父 = beta tip；上一轮 amend 掉的悬空提交 `3c5fc73d21` **不在** `HEAD` 祖先链（`--is-ancestor` EXIT=1） |
| 初始脏路径 | **无**（0 条），故无需 `--adopt-dirty` |
| 回滚锚点 | `fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5`（= 本轮 beta tip，也是 HEAD 的父提交） |

### 为什么"无需合并"仍然满足目标第①条

目标要求"dev2 包含合并时刻的 `origin/beta` tip"。本轮取证表明该条件**已经成立**：`origin/beta` tip `fb4fd7d99c` 正是 `dev2` HEAD `97c7ae68ab` 的**直系父提交**，即 dev2 = beta tip + 1 个本地提交。上一轮把 dev2 定位到 PR #433 的 beta 合并提交（该合并提交本身已含 `origin/dev2` 的 `34a93e243f`，故 `git rev-list 34a93e243f --not fb4fd7d99c` 输出 **0 行**，无 dev2 工作丢失），随后在其上提交本次复评对象。因此本轮**不产生合并提交**，也不存在"把 beta 侧提交当成本分支改动"的退化风险。

### 零复活证据（三层，判据与 C57 同构）

| 层 | 判据 | 结果 |
| --- | --- | --- |
| L1 结构性 | ① 本轮无合并提交（beta tip 即 HEAD 父提交，不存在"合并带回"通道）；② `git diff --name-status origin/beta HEAD` 只含 dev2 自身 **2** 路径；③ `git diff --diff-filter=D --name-status origin/beta HEAD` **为空**（无删除、无覆盖） | 全部通过 |
| L2 路径级（含集合基数） | 取**主题行锚定**的 revert 提交集（`git log --all --format='%H%x09%s'` 过滤 `^(Revert\|revert\|回退\|撤销\|剔除)`）共 **183** 个；对每个提交取 `git show --diff-filter=D --name-only` 的删除路径，去重得 **66** 条；其中 **19** 条仍存在于 `origin/beta` 树（属"删后重建/部分回退"）。**本次 PR 的 2 个路径与这 66 条移除路径的交集 = 0** | **严格复活数 = 0** |
| L3 关键移除目标 | 对 beta 已移除能力关键词在本次净差异中 `Select-String` 计数：`Fingerprint`/`fingerprint`/`SpeechRecognizer`/`VoiceAd`/`AdAudioDetect`/`SiteAdMarker`/`ThemeController` | 全部为 **0** 命中 |

结论：**零复活、零丢失**。本次 PR 的改动面是"播放页显式退出的停播确定性与退出后拒绝再起播"，与 beta 上被移除/回退的音频指纹、语音广告识别、动态主题等能力**不相交**。

## 复评第 1 轮（对象：唯一已提交未推送提交 `97c7ae68ab`）

复评对象：`git diff --stat origin/beta HEAD` = 2 文件、75 插入 / 4 删除（`PlaybackActivity.java` +24/-4，`PlaybackOwnershipSourceTest.java` +51/-0）。

### 生产代码

| 文件 / 符号 | 检查项 | 结论 |
| --- | --- | --- |
| `PlaybackActivity.stopPlayback()` | 三条分支是否覆盖"退出必定停播"：服务在且本页归属 → `shutdown()`；服务在但本页非归属 → `suspend()`；服务不在 → 控制器兜底 `mController.stop()` | 是。旧实现 `mService != null && isOwner() ? shutdown() : mController != null ? mController.stop()` 的两个漏播窗口被堵住：控制器异步连接未就绪时整次停播为空操作；播放器 IDLE（取流中退出）时会话 stop 被忽略；且旧路径从不撤前台通知。`PlaybackService.suspend()`（`PlaybackService.java:267`）= `stopAndClear()`（停播 + `clearMediaItems()`）+ `removeForeground()`，正好对应"还有声音/通知栏仍显示播放中"两个症状 |
| `stopPlayback()` 与 `PlaybackServiceReleasePolicy` 的分层 | 非归属方停播是否与既有"非归属方离场不停播"契约（`PlaybackServiceReleasePolicy.decide` 的 `DETACH`/`RESET_SESSION`，及其测试 `PlaybackServiceReleasePolicyTest.keepsTheExistingReleaseMatrix`）冲突 | **不冲突，属两层语义**：`stopPlayback()` 只在**显式退出**路径被调用（返回键/系统返回/通知栏停止/退出首页），语义是"退出即停播"；`PlaybackServiceReleasePolicy` 管的是 `onDestroy → releasePlaybackService()` 的**释放**路径（非归属方 `DETACH`/`RESET_SESSION`），本轮**未改动**该策略与其测试。旧实现经 `mController.stop()` 表达的同样是"显式退出停播"意图，新实现只是把它变得确定（且更强：清媒体项 + 撤通知），**非新增语义**。残留边界已记录于「残留风险」 |
| `PlaybackActivity.startPlayer(...)`（六参重载） | 退出/结束/销毁后是否拒绝再起播；守卫位置是否早于既有分流（阅读器路由、DRM、parse/start） | 是。守卫在方法体首部，早于 `NovelRouter.isReaderUrl(result)` 路由与 `player().parse()/start()`；被拦的是自动换源、换线路、取流结果这类"迟到链路"，避免页面已退出仍在服务里起播并挂前台通知。守卫未影响非退出态的正常起播（`isPlaybackExiting()` 仅由 `markPlaybackExiting()` 置位，调用点均为退出路径） |
| 两 flavor 覆盖 | 修复位于 `app/src/main`（两 flavor 共享基类），leanback/mobile 出口路径是否都经 `stopPlayback()` | 是。leanback `VideoActivity.finishVideoPlayback()`（`:8563`）与 mobile `onBackInvoked`/`finishVideoPlaybackFromSystemBack`（`:9849`、`:11345`）均为 `markPlaybackExiting() → stopPlayback()`；`finishPlayback()`（基类 `:141`）同样是 `markPlaybackExiting() → stopPlayback() → finish()` |
| 未触碰面 | 是否改动 native、依赖锁、`res/layout`、播放器链路、统计写入 | 否。净差异仅上述 2 文件；`PlaybackService`、`PlayerManager`、`PlaybackServiceReleasePolicy` 均未改动 |

### 测试代码

| 用例 | 检查项 | 结论 |
| --- | --- | --- |
| `exitingThePlayerPageAlwaysStopsThroughTheService` | 是否对**修复前**源码可证伪；断言是否稳定（CRLF 检出下不误报） | 可证伪：旧方法体不含 `mService.suspend();`，该断言在 base 必然变红。稳定：断言只用**单行**字面量与 `"\n    }"` 行首边界（CRLF 检出下 `\n    }` 仍成立），不含裸 `\n` 多行字面量（本机 CRLF 失败族正是后者所致） |
| `anExitingPlayerPageNeverStartsPlaybackAgain` | 是否锁定"退出后拒绝起播"且不误伤阅读器路由 | 可证伪：旧代码无该守卫，`guard.contains("isPlaybackExiting()")` 在 base 为假。断言取 `startPlayer` 方法体到 `NovelRouter.isReaderUrl(result)` 之间的片段，既锁定守卫存在，又要求阅读器路由仍是首个分流 |
| 是否削弱既有断言 | 测试文件改动是否只增不删 | 是。`--numstat` = `51 0`（0 删除），未删除或放宽任何既有断言 |

**生产代码无必修缺陷，本轮未改动生产代码。** 复评未发现需要修复的问题，故不进入"修复 → 验证"分支。

## 复评第 2 轮（验证通过后的复审）

| 复审项 | 结论 |
| --- | --- |
| 验证结果与代码语义是否一致 | 是。双 flavor 全量套件中 `PlaybackOwnershipSourceTest` **18/18 通过（failures=0）**，含两条新增用例；失败集与文档基线逐类一致，未出现与本次改动相关的新失败 |
| 是否有新增失败被"环境性"掩盖 | 否。逐类核对失败类集合与 C57 文档基线**完全一致**（mobile 6 类 / leanback 7 类），且测试总数恰好 **+2**（= 本次新增 2 条契约测试），说明既无回归也无被吞掉的用例 |
| 失败是否确为环境性 | 是。以 `SearchResultDownFocusTest:85` 为证：断言用裸 `\n` 多行字面量 `"@Override\n    public void onItemClick"`，而本机 `core.autocrlf=true` 检出为 CRLF ⇒ `indexOf` 必然失配；该失败类与本次改动文件无关。另一族 `MpvFontConfigTest`/`MpvHlsCacheCoordinatorTest` 依赖 Windows 原子重命名语义 |
| 合并/历史是否被破坏 | 否。`HEAD` 单父 = beta tip；本轮无 `reset`/`amend`/`rebase`；工作区干净；无冲突标记、无 unmerged 路径 |
| 文档与实现是否一致 | 是。本文件记录的语义、坐标与验证数字均取自本轮命令输出 |
| 是否引入无关改动 | 否。本轮唯一改动 = 本任务文档；生产代码与测试代码 0 改动 |

## 验证

### 4.1 双 flavor 编译 + AndroidTest 编译 + 双 flavor 全量 JVM 套件

分两次 Gradle 调用（第一次失败即停，故用 `--continue` 补齐；避免重复检查）：

```bash
cmd /c gradlew.bat --no-daemon \
  :app:testLeanbackArm64_v8aDebugUnitTest :app:testMobileArm64_v8aDebugUnitTest \
  :app:compileLeanbackArm64_v8aDebugJavaWithJavac :app:compileMobileArm64_v8aDebugJavaWithJavac \
  :app:compileLeanbackArm64_v8aDebugAndroidTestJavaWithJavac :app:compileMobileArm64_v8aDebugAndroidTestJavaWithJavac
```

4 个编译目标任务 **BUILD SUCCESSFUL**；两个测试任务因**既有环境失败**报 FAILED（下表）。

| flavor | tests | failures | errors | skipped | 文档基线（C57） | 差值 |
| --- | --- | --- | --- | --- | --- | --- |
| leanback | **4150** | **8** | 0 | 3 | 4148 / 8 失败 / 7 类 | **同类同数（0 新增失败）**；+2 例 = 本次新增契约测试 |
| mobile | **4985** | **7** | 0 | 3 | 4983 / 7 失败 / 6 类 | **同类同数（0 新增失败）**；+2 例 = 本次新增契约测试 |

失败类集合（逐类核对，与基线一致）：

- mobile（6 类 / 7 例）：`MpvFontConfigTest`、`MpvHlsCacheCoordinatorTest`、`PlayerPlaybackRegressionSourceTest`、`ReaderPlaybackRoutingSourceTest`×2、`TmdbSourceOnlyInteractionTest`、`WebThemeTokenSourceTest`
- leanback（7 类 / 8 例）：`MpvFontConfigTest`、`MpvHlsCacheCoordinatorTest`、`NativeEnhancedPlaybackStyleFocusTest`、`ReaderPlaybackRoutingSourceTest`×2、`SearchResultDownFocusTest`、`TmdbSourceOnlyInteractionTest`、`WebThemeTokenSourceTest`

环境性根因（本轮取证）：**CRLF 检出 + 裸 `\n` 断言**（如 `SearchResultDownFocusTest.java:85` 的 `"@Override\n    public void onItemClick"`）与 **Windows `renameTo` 语义**（`MpvFontConfigTest`、`MpvHlsCacheCoordinatorTest`）。该族已在 `docs/C51-beta-merge-review-dev2-20261009.md` 与 `docs/C57-beta-merge-review-dev2-20261010.md` 多次记载，四个 dev 工作区一致。

### 4.2 定向用例（本次改动面）

| 用例类 | 结果 |
| --- | --- |
| `PlaybackOwnershipSourceTest`（mobile flavor） | **18/18**，failures=0，含 `exitingThePlayerPageAlwaysStopsThroughTheService`、`anExitingPlayerPageNeverStartsPlaybackAgain` 均 **PASSED** |
| `PlaybackOwnershipSourceTest`（leanback flavor） | **18/18**，failures=0，同上两条 **PASSED** |

（用例数取自 `app/build/test-results/**/TEST-com.fongmi.android.tv.ui.activity.PlaybackOwnershipSourceTest.xml`。）

### 4.3 静态与结构校验

- `git diff --check origin/beta HEAD` 退出码 **0**；
- `git grep -nE '^(<<<<<<<|>>>>>>>)' -- app/src docs` 命中 **0**；`git diff --name-only --diff-filter=U` 为空；
- `HEAD` 父提交 == `origin/beta` tip（`fb4fd7d99c`），无合并退化；
- 本轮未改动 `res/layout`、native、依赖锁文件、播放器链路、统计写入；
- 零复活三层证据通过：L2 中 66 条 beta 移除路径与本次 2 个 PR 路径**交集 0**，L3 关键词命中 **0**，`--diff-filter=D` **空**；
- 推送安全性：`git push --dry-run origin dev2` = `34a93e243f..97c7ae68ab`（快进，无需强推）。

### 4.4 回滚

- 本轮唯一改动是新增文档 `docs/C58-beta-merge-review-dev2-20261011.md`，`git revert <commit>` 或删除该文件即可完全回退；
- 本轮**无生产代码改动**，回滚无运行时影响；无数据迁移、无 ABI/native 变更、无依赖变更、无播放行为变更；
- 若需整体回到本轮起点：`git reset --hard fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5`（回滚锚点）。

## 残留风险（按 `AGENTS.md` §2 记录，不在本轮范围内展开）

1. **显式退出时非归属方的停播强度提升**：`stopPlayback()` 的非归属分支由旧的"经控制器停会话（控制器未连上时为空操作）"变为"经服务确定停播（`suspend()`：停播 + 清媒体项 + 撤前台通知）"。在"本页非归属方且服务正被另一会话使用"的极端组合下，新实现比旧实现更可能停掉那次会话；但旧实现在控制器已连接时同样会停掉它，且非归属分支的触发前提是本页正在**显式退出**，故本轮判定为**可接受的语义强化**而非回归。`onDestroy` 释放路径仍由 `PlaybackServiceReleasePolicy` 保护（非归属方 `DETACH`/`RESET_SESSION`）。
2. **`suspend()` 无 `running` 守卫**（`shutdown()` 有 `if (!running) return;`）：经核查 `mService != null` 蕴含绑定仍在 ⇒ 服务未销毁 ⇒ 播放器未 `release()`（`player.release()` 只在 `PlaybackService.onDestroy()`），故 `suspend()` 不会作用在已释放的播放器上；`onServiceDisconnected` 会把 `mService` 置空，其后走控制器兜底分支。判定为无实际风险，未做改动。

## 改动清单（C58 相对本轮起点）

| 路径 | 类型 | 说明 |
| --- | --- | --- |
| `docs/C58-beta-merge-review-dev2-20261011.md` | 新增 | 本任务文档 |

（本次 PR 另含已提交未推送的 `97c7ae68ab`：`PlaybackActivity.java` +24/-4、`PlaybackOwnershipSourceTest.java` +51/-0。）

## 交付坐标

| 项 | 值 |
| --- | --- |
| 任务起始 HEAD | `97c7ae68abd9be76d2bdd1d79e34d93a9f6925ae` |
| `origin/beta` tip（提交前后一致） | `fb4fd7d99ca1547ea624f3d5f1b7f3cd81b880a5` |
| 本轮提交 | 见「闭环记录」 |
| recovery tag | 见「闭环记录」 |
| 推送 | `git push origin dev2`（快进）+ tag 推送 |
| PR | `dev2 -> beta`，中文正文，**只创建未合并** |

## 闭环记录

- 2026-10-11（Asia/Shanghai）：`git fetch origin` 后确认 `origin/beta` tip = `fb4fd7d99c` = `dev2` HEAD 的父提交 ⇒ 本轮**无需合并**；2 轮复评未发现必修缺陷，未改生产代码。
- 验证：双 flavor 编译（4 个编译目标任务 BUILD SUCCESSFUL）+ 双 flavor 全量 JVM 套件（leanback 4150/8 失败、mobile 4985/7 失败，失败类集合与文档基线逐类一致，0 新增失败）；`PlaybackOwnershipSourceTest` 双 flavor 18/18 通过。
- 待续：`task_guard.sh finish` 生成提交 + recovery tag → `git push origin dev2` + tag → `gh pr create --base beta --head dev2`（**未合并**）→ 校验 PR 文件集与 `git diff --name-only origin/beta HEAD` 逐项一致。
