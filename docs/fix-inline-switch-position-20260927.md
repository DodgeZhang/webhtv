# 详情直放/沉浸融合切集沿用上一集进度修复（2026-09-27）

## Recovery anchor

- **目标**：修复「详情直放、沉浸融合模式下，当前集播到某一位置后切下一集，下一集直接从上一集的位置开始播放」的问题；其他模式不受影响，正常续播语义保持不变。
- **验收**：中途手动切下一集从 0 开始；片尾自动连播从 0 开始；同集退出重进仍按 history 续播；从头重播（onReplay/refresh）真正从头开始；集数位置缓存不再被切集窗口期污染；新增源码回归测试通过；leanback/mobile 编译通过；设备覆盖安装实测通过。
- **当前状态**：已实现并完成设备实测；提交与恢复标签待创建。
- **下一动作**：`task_guard.sh finish` 提交并打恢复标签。

## 现象与根因

用户实测：只有**详情直放**与**沉浸融合**两个模式出现「当前集播到哪，切下一集就从哪开始」（如第 1 集播到 1 分钟，切第 2 集直接跳到 1 分钟）；其他模式正常。

根因是这两个模式特有的**异步切集窗口期进度回写**：

1. `playAdjacentEpisode`/`selectInlineEpisode` → `onPlay()` → `saveInlineHistory()`（按旧集正确保存）→ `updateInlineHistory(新集)`（history 重指向新集、position 重置为 `TIME_UNSET`）→ `playInline()` 提交**异步**解析。
2. 窗口期内（数百 ms～数秒）播放器仍在播旧集，`inlinePlaybackEpisode` 仍是旧集；`onTimeChanged` 每秒触发，`canUpdateProgress = inlineStartPositionApplied(旧集遗留 true) || getInlineStartPosition() <= 0` → `updateInlineHistoryProgress` 把**旧集实时位置写进已指向新集的 history**；每 5 秒 `syncInlineHistory` 落库。
3. 解析返回 → `startInlinePlayer` → `inlineStartPosition = getInlineResumePosition()` = 被污染的旧集位置 → 新集 READY 后 `seekTo(上一集位置)`。

只有沉浸融合/详情直放走 `TmdbDetailActivity` 的内联异步播放链路（其他模式由 `VideoActivity` 同步切集，无此窗口），与用户「只有这两个模式复现」完全吻合。

附带缺陷（同一根因）：`onReplay`/`refreshInlinePlayback` 同集从头重播时，窗口期 tick 会把清零前的实时位置回填，「重播」退化为「续播」。

## 最小修复

`TmdbDetailActivity.java` 引入窗口态 `inlinePlaybackSettled`（默认 true）：

- **开窗**（false）：`updateInlineHistory` 中切集（`!sameEpisode`）或同集换线路（`sameEpisode && !sameFlag`）时；`onReplay`、`refreshInlinePlayback` 清零重播时。
- **关窗**（true）：`startInlinePlayer` 顶部（解析结果接管播放器；必须在 `NovelRouter` 拦截之前，防止阅读器路径泄漏窗口态）；`stopInlinePlayerForReload`（播放器清空）；`closeDetailFullscreenPlayer`（退出播放）。
- **消费守卫**：
  - `onTimeChanged`：`canUpdateProgress` 追加 `isInlinePlayerSettledOnSelection()` 校验。
  - `updateInlineHistoryProgress(long,...)`（所有进度写入的单点汇聚）：窗口期只更新 `createTime`，不写 position/duration。
  - `saveInlineHistory`：窗口期跳过 `EpisodePositionCache` 写入（此时 `currentInlineHistoryCacheKey()` 已指向新集，写入即污染缓存）。

## 验证

- **单元/源码回归**：`TmdbDetailActivityLayoutTest` 新增 `inlineEpisodeSwitchDoesNotCarryPreviousEpisodePosition`，锁住字段、开窗点、关窗点顺序（先于 NovelRouter）、三处守卫、安全默认恢复与重播开窗；`:app:testMobileArm64_v8aDebugUnitTest` 该类 129 项全通过（0 failure、0 error）。
- **基线对照**：`FollowingUiSourceTest` 等 4 个类 6 个失败在改动前的基线同样失败（既有 stale 断言，与本改动无关文件），非本任务引入；与本改动相关的测试类全部通过。
- **编译**：`:app:assembleLeanbackArm64_v8aDebug` 成功；leanback 变体单测编译执行通过。
- **设备实测（emulator-5556，覆盖安装未卸载）**：详情直放模式播《雀骨》：
  1. 片尾自动连播（第 1 集 45:00 → 第 2 集）：新集从 0 开始 ✅
  2. 第 2 集播放中手动选集切第 3 集（用户反馈场景）：新集从 0 连续递增（33s→95s→…，无跳变）✅
  3. 第 3 集播到 6:29 手动切第 4 集：第 4 集从 0 开始连续播放（起播时刻与 5:35 读数时间吻合），**未跳到第 3 集的 6:29** ✅
  4. 退出详情页重进第 4 集点「继续播放」：从退出时的 380312ms 续播（首读 381946ms）✅ 正常续播未被误伤
- **设备状态还原**：`detail_open_mode` 已还原为 1；临时 UI dump 已清理；logcat 已清空。

## 风险与回滚

- 改动仅限 `TmdbDetailActivity` 内联播放链路，不触及 `VideoActivity`、`PlayerManager`、引擎与公共 API。
- 窗口期跳过的进度写入不丢数据：`onPlay()` 在窗口开启前已按旧集完整保存。
- 回滚：整体 revert 本提交即可。
