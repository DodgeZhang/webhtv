# E-SP10：Exo 外部回环代理下的 EOF 恢复退避与预加载让位

- 任务 ID：`E-SP10`
- 类别：Exo 性能/网络稳健性
- 唯一文档：`docs/E-SP10-exo-eof-recovery-backoff-and-preload-yield.md`
- 评估日期：2026-10-17（Asia/Shanghai）
- 当前状态：**设计就绪，等待用户批准**。未修改任何代码、锁、补丁或产物。
- 授权边界：本轮为 assessment-only，只允许改本文件与评估索引；实现需用户明确批准后再开 `upstream` lane。
- 依据现场证据：`G:\下载\webhtv-debug-log (25).txt`（3.68 MB / 5458 行；2279 条 av-diag + 3071 条普通诊断行；192 s 播放窗口）
- 故障包：`v5.6.0-beta-202610170951`，gitRevision `323afa1a5a9b962749bd3e78840a4f729a2f4701`，当贝 X7 Ultra / Android 14 / API 34 / arm64_v8a，Media3 `1.11.0-alpha01-fongmi`

## 完成目标

在不降低前台播放质量、不改变解码器选择、不改变 ABR、不改变缓存格式的前提下，降低「外部本地代理反复截断 HTTP 响应体」这一输入侧故障对前台播放的伤害：

1. 消除 EOF 恢复的**无退避重试风暴**（实测连续重连间隔 p50 = 47 ms、min = 23 ms）；
2. 让预加载在**检测到前台连接被截断**时主动让位，而不是只在缓冲量不足时让位；
3. 让网络保护降速**区分「带宽不足」与「连接被截断」**，避免对后者做无效降速（实测 1.000→0.919，用户可感知变慢）。

## 1. 现场证据

### 1.1 掉帧是真实的，但量小

Media3 渲染管线口径（`playback-metrics droppedFrames=/total=`，即 `onDroppedVideoFrames`），全片 192 s 累计 **32 帧**：

| 时刻 | 单次 | positionMs |
| --- | --- | --- |
| 53.0 s | 12 | 1452723 |
| 57.4 s | 9 | 1456679 |
| 99.3 s | 3 | 1707295（seek 后） |
| 111.5 s | 5 | 1717486 |
| 135.3 s | 3 | 2078386（seek 后） |

解码与渲染本身健康：视频解码 PTS 斜率各 epoch 均为 1.000；`play.clock` 位置斜率除暂停/seek 外均为 1.000；音频 playhead `underruns=0`；`thermal=nominal`、`power=normal`、内存 `pressure=normal`；A/V 内容漂移 < 0.32 s。

### 1.2 根因：EOF 恢复与预加载并发强相关

`HttpEofRecoveryDataSource` 在 192 s 内触发 **177 次** `unexpected end of stream` 恢复（`consecutiveAttempt` 1/2/3 各 68/54/55），伴随 209 次 `ProtocolException`、226 次 206 响应。

把 177 次恢复与预加载任务区间对齐：

| 时段 | 预加载 | 前台 loader | EOF 次数 |
| --- | --- | --- | --- |
| 18.6–55.6 s | task1 活跃 37 s | 在拉 | **37** |
| 60.7–94.6 s | 5 个短任务（1.7–4.0 s 完成） | 空闲 | **0** |
| 106.2–135.2 s | task7 活跃 29 s | 在拉 | **79** |
| **135.2–172.6 s** | **被抑制（seek-suppressed）** | **满速在拉（≈1.84× 实时）** | **0** |
| 172.7–192.1 s | task8 活跃 19.4 s | 在拉 | **60** |

**176/177 次落在预加载活跃期**。决定性反证是 135.2–172.6 s 那 37.4 秒：预加载被 `wait-recovery-buffer` 抑制，前台 loader 满速下载，**EOF 为 0**；预加载在 172.65 s 释放、172.68 s 启动 task8，**2.6 秒后 EOF 立刻密集**（175.3 s 起 18 次）。

这排除了「只要有下载就 EOF」与「那段时间网络差」两种解释——前台单独跑无问题，两个并发请求才出问题。

按来源线程分类 177 次恢复：前台 `ExoPlayer:Loader` 94 次（53%）、优先级等待线程 `pool-138-thread-1` 77 次（44%）、预加载 `PreCacheHelper:Loader` 6 次（3%）。

`input.route` 记录：`EXTERNAL_LOOPBACK_PROXY` / `EXTERNAL_OR_UNKNOWN_LOOPBACK` / `UNREPORTED_LOOPBACK_PORT`，`observedLeg=app-to-local-endpoint-only upstreamVisibility=opaque-external-process`，即外部本地代理在 app→代理这一段截断响应体。

### 1.3 优先级机制在运行，但不足以消除并发

预加载数据源经 `PriorityTaskDataSource`（`C.PRIORITY_PLAYBACK_PRELOAD` + `waitWhenPreempted=true`）注册到共享 `PLAYBACK_PRIORITY_MANAGER`，前台为 `C.PRIORITY_PLAYBACK`。日志中的 `waitCount` / `waitTotalMs` 证实它确实被抢占过：

| waitCount | waitTotalMs |
| --- | --- |
| 0 | 0 ms |
| 4 | 12 954 ms |
| 5 | 13 059 ms |
| 13 | 30 683 ms |

即预加载在整个 session 累计等待 **30.7 s**。但优先级只在前台**正在 read** 时阻塞预加载；前台的 read 一返回，预加载立刻恢复。两个请求仍会交替占用同一条上游链路，这正是 EOF 的并发窗口。

### 1.4 三个可优化点的代码定位

**(a) EOF 恢复无退避** — `app/src/main/java/com/fongmi/android/tv/player/exo/HttpEofRecoveryDataSource.java`

```java
private static final int MAX_CONSECUTIVE_RECONNECTS = 3;   // 行 26

private void reconnectAtCurrentPosition() throws IOException {   // 行 89-98
    try { upstream.close(); } catch (IOException ignored) {}
    long remaining = remainingLength();
    DataSpec retrySpec = remaining == C.LENGTH_UNSET
            ? dataSpec.subrange(bytesRead) : dataSpec.subrange(bytesRead, remaining);
    upstream.open(retrySpec);        // 行 96 ← 无 sleep / 无退避
    if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-network", ...);
}
```

实测连续重连间隔：attempt 递增间隔 n=32，**min 23 ms、p50 47 ms、max 123 ms**；相邻恢复事件间隔 n=176，**83% < 100 ms**。同一 offset 在 ~150 ms 内关连接、建连接、重发 range 三次（如 offset 2843368138 在 49.02 / 49.12 / 49.28 s），随后抛给上层 Loader 再重试。

**(b) 外部回环路由下预加载策略失效** — `app/src/main/java/com/fongmi/android/tv/player/exo/AutoPreloadPolicy.java`

`ExoThroughputPathPolicy` 对 `EXTERNAL_LOOPBACK` 直接判 `Trust.BLOCKED`（`ExoThroughputPathPolicy.java:60-61`），导致所有吞吐量判断被跳过：

```java
// AutoPreloadPolicy.java:180-181（hardPauseReason 内，行 138 起）
if (throughput.usable()
        && throughput.pathTrust() != ExoThroughputPathPolicy.Trust.BLOCKED   // ← 恒假
        && input.mediaBitrateBitsPerSecond() > 0) { ... }
```

外部回环路由退化为只看缓冲量（`EXTERNAL_LOOPBACK_NORMAL_BUFFER_MS = 20_000`，行 26）。实测 buffered 全程 24–37 s，始终高于 20 s，故预加载**从不因吞吐不足而暂停**。策略里也没有任何「检测到连接被截断就暂停预加载」的入口。

决策日志证实：`domain=preload ... reason=task-start ... trust:blocked ... contention:true`——`contention:true` 已知却未参与暂停判定。

**(c) 网络保护降速不区分故障类型** — `app/src/main/java/com/fongmi/android/tv/player/exo/ExoNetworkGuardController.java`

124–135 s 把速度从 1.000 压到 0.919（`deadline-emergency-rescue`），依据是 `slopeMsPerSecond ≈ -326`：

| 时刻 | 速度变化 | reason |
| --- | --- | --- |
| 127.1 s | 1.000→0.990 | deadline-emergency-rescue |
| 128.1 s | 0.990→0.980 | deadline-emergency-rescue |
| 129.1 s | 0.980→0.970 | deadline-emergency-rescue |
| 130.2 s | 0.970→0.960 | sustained-risk |
| 131.2 s | 0.960→0.950 | deadline-emergency-rescue |
| 132.2 s | 0.950→0.939 | deadline-emergency-rescue |
| 134.3 s | 0.939→0.919 | sustained-risk |
| 135.2 s | 0.919→1.000 | discontinuity-1（seek 后恢复） |

`play.clock` 在 130.9 s 记录实际 `speed=0.96`，用户可感知。但根因是连接截断而非带宽不足：0.919× 相对 15.4 Mbps 码率只省 8% 带宽，且该判定基于已被 BLOCKED 的吞吐估算。

### 1.5 既有保护（已存在，不重复实现）

- **优先级抢占**：`PriorityTaskDataSource`（`waitWhenPreempted=true`）+ 共享 `PLAYBACK_PRIORITY_MANAGER`，已生效（见 1.3）。
- **预加载失败熔断**：`PreCache` 已有 `openExternalCircuit` / `openPreloadErrorCircuit`（阈值 2 次连续失败）/ `openDiskCircuit`。但本日志中 `circuit-open` 出现 **0 次**，task-end 全为 `completed`（7 次）或 `cancelled`（2 次）——因为 `HttpEofRecoveryDataSource` 把 EOF 恢复成功，上层从未看到失败，**熔断器被恢复机制掩盖而永不触发**。
- **seek 期预加载抑制**：`wait-recovery-buffer` + `seek-suppressed-release`，已生效（135.2–172.6 s 的 EOF=0 正是它的功劳）。

## 2. Best-practice 调研

### 2.1 证据类别覆盖

| 类别 | 状态 | 来源与结论 |
| --- | --- | --- |
| 精确上游源码 | 已查 | Media3 `PriorityTaskManager`（sources.jar，见 2.2）、`PreCacheHelper.Factory`（sources.jar）、本地 fork 全部相关类 |
| 官方平台/项目文档 | 已查 | Android Developers「Preload manager concepts」+「A deep dive into Media3's PreloadManager - Part 2」（见 2.3） |
| 上游 PR/issue/revert/维护者讨论 | 已查 | Media3 官方博客明确把「预加载与播放器竞争带宽导致当前视频卡顿」列为**必须共享组件**的失败场景 |
| 成熟相关项目实现 | 已查 | AWS / Google Cloud 官方重试指南（见 2.4） |
| 论文/技术文章/基准/现场报告 | 已查 | AWS Architecture Blog「Exponential Backoff And Jitter」（见 2.4） |
| 不适用类别 | 已记录 | 无学术论文类证据；本问题属工程实践，论文类不改变决策 |

### 2.2 Media3 `PriorityTaskManager` 语义（本地 sources.jar 实读）

`third_party/maven/androidx/media3/media3-common/1.11.0-alpha01-fongmi/media3-common-1.11.0-alpha01-fongmi-sources.jar` → `androidx/media3/common/PriorityTaskManager.java`：

```java
public boolean proceedNonBlocking(@C.Priority int priority) {
    synchronized (lock) { return highestPriority == priority; }
}
```

**关键语义**：只有在**没有任何更高优先级任务已注册**时才返回 true。这意味着优先级抢占**只在注册期内有效**，无法阻止两个请求在同一时间窗内交替发起——与 1.3 的实测一致。

### 2.3 官方文档（决定性证据）

来源：<https://developer.android.com/blog/posts/elevating-media-playback-a-deep-dive-into-media3-s-preload-manager-part-2>（访问日期 2026-10-17，证据等级：官方一手）

> "If the PreloadManager and the player use separate instances, they are unaware of each other's network activity, which can lead to failure scenarios. For example, consider the scenario where a user is watching a video, their network connection degrades, and the preloading MediaSource simultaneously initiates an aggressive download for a future video. The preloading MediaSource's activity would consume bandwidth needed by the active player, causing the current video to stall. **A stall during playback is a significant user experience failure.**"
>
> "By sharing a single BandwidthMeter, the TrackSelector is able to select tracks of highest quality given the current network conditions and the state of the buffer, during preloading or playback. It can then make **intelligent decisions to protect the active playback session** and ensure a smooth experience."
>
> "In times of contention, the player will prioritize playback of the current item displayed on the screen."

**WebHTV 适用性核对**：`PreCache.createHelper()`（`PreCache.java:764-770`）用 `PreCacheHelper.Factory` 创建 helper，传入了 cache、upstream factory、renderersFactory、looper、downloadExecutor、listener——**没有传 BandwidthMeter，也没有传 LoadControl**。`PreCacheHelper.Factory` 的 4 个公开构造函数（sources.jar 行 134/153/173/197）**均不提供** `setBandwidthMeter` / `setLoadControl` 入口（该能力属于 `DefaultPreloadManager.Builder`，不是 `PreCacheHelper`）。因此官方推荐的「共享 BandwidthMeter」在当前使用的 `PreCacheHelper` API 下**不可实现**，除非改用 `DefaultPreloadManager`——那属于架构级替换，超出本任务范围。

**决策影响**：官方处方不可直接套用，但官方对**失败模式**的定性成立且与本日志完全吻合。因此本项目需要的是**在现有 `PreCacheHelper` 架构内的等效保护**：让预加载感知「前台正在被截断」并主动让位。

### 2.4 重试退避（官方指南）

来源：<https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/>（访问日期 2026-10-17，证据等级：云厂商官方工程指南）；<https://docs.cloud.google.com/storage/docs/retry-strategy>（同）

- 指数退避 + 抖动是瞬时故障重试的标准做法；无退避的立即重试会放大对已脆弱服务端的压力（重试风暴 / thundering herd）。
- 抖动的作用是打散并发重试时刻；单客户端场景下抖动价值有限，但**退避本身**是核心。

**WebHTV 适用性**：`HttpEofRecoveryDataSource` 当前对**同一 offset 连续 3 次**无延迟重连，实测间隔 23–123 ms，正是该指南描述的放大模式。注意：该源已用 `consecutiveReconnects` 在**成功后清零**（行 60-62），注释明确「Progress proves the resumed connection is healthy」——这是正确的设计，退避不应破坏它。

### 2.5 方案对比

| 方案 | 内容 | 正确性 | 兼容性 | 性能 | 风险 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| **A. no change** | 保持现状 | 无回归 | 无 | 现状：32 帧掉帧 + 177 次风暴 + 无效降速 | 无 | 不采纳（用户已报告掉帧） |
| **B. 改用 `DefaultPreloadManager`** | 按官方处方共享 BandwidthMeter/LoadControl | 理论最优 | **破坏性**：需替换 `PreCacheHelper` 全部生命周期、seek 隔离、熔断、磁盘缓存接线 | 不确定 | 高，且 `E-SP3` 的 seek 隔离成果可能丢失 | **拒绝**（架构级替换，超出范围） |
| **C. 窄适配（推荐）** | ① EOF 恢复加退避；② 预加载感知前台截断并让位；③ 降速区分故障类型 | 保持现有架构与全部既有保护 | 不改 API、不改 ABR、不改缓存格式 | 降低重试风暴与并发压力 | 中低，可独立回滚 | **采纳** |

## 3. 设计（方案 C）

### 3.1 改动一：EOF 恢复退避（最小、最独立）

**文件**：`app/src/main/java/com/fongmi/android/tv/player/exo/HttpEofRecoveryDataSource.java`

**设计**：在 `reconnectAtCurrentPosition()` 中、`upstream.open()` **之前**插入按 `consecutiveReconnects` 递增的退避等待。

- 退避序列：第 1 次 100 ms、第 2 次 250 ms、第 3 次 500 ms（上限 500 ms）。
- 保持 `MAX_CONSECUTIVE_RECONNECTS = 3` 不变（不扩大重试次数，只拉开间隔）。
- 保持「成功即清零」语义不变（行 57-59 的 `consecutiveReconnects = 0`）。
- 等待期间响应线程中断：捕获 `InterruptedException` → `Thread.currentThread().interrupt()` → 抛出 `InterruptedIOException`（与 `PriorityTaskDataSource` 行 91-94 的既有风格一致）。
- 等待不得发生在持有锁的路径上；`read()` 每次调用可能触发一次等待，最坏单次 read 增加 500 ms。

**不做**：不加抖动（单客户端无 thundering herd 问题，增加不可测复杂度）；不增加重试次数；不改 `isRecoverableEof` 判定。

**预期效果**：同一 offset 的 3 次重连从 ~150 ms 拉开到 ~850 ms，给上游代理恢复窗口，同时降低对代理的请求速率。前台 read 在等待期不占用连接。

### 3.2 改动二：预加载感知前台截断并让位（收益最大）

**文件**：`app/src/main/java/com/fongmi/android/tv/player/exo/AutoPreloadPolicy.java`（主）、`PreCache.java`（接线）

**设计**：新增一个「前台连接健康度」输入，让预加载在**前台正在经历 EOF 恢复**时暂停一个冷却窗口。

- 在 `HttpEofRecoveryDataSource` 中增加一个**进程级、按路由隔离的**轻量计数器：记录「最近一次 EOF 恢复发生的 elapsedRealtime」与「累计次数」。仅统计**前台**（非预加载）数据源实例；预加载自身的恢复不计入。
- 前台/预加载的区分方式：`HttpEofRecoveryDataSource` 目前不感知角色。最小改法是让 `MediaSourceFactory` 在创建前台数据源（行 308/319-320 路径）与预加载数据源（行 102 路径）时传入不同的角色标记。
- `AutoPreloadPolicy.Inputs` 增加 `foregroundEofRecoveryAgeMs`；在 `hardPauseReason()` 中新增判据：若 `ageMs <= EOF_YIELD_WINDOW_MS`（建议 15 s，与 `WEAK_RESUME_DELAY_MS` 同量级）则返回新 `Reason.FOREGROUND_EOF_RECOVERY`。
- 新增 `Reason.FOREGROUND_EOF_RECOVERY("foreground-eof-recovery")`，加入 `Reason` 枚举（行 735 起）。
- 该判据必须放在 `hardPauseReason()`（行 138 起）的**硬暂停**分支（与 `NETWORK_UNAVAILABLE` / `POWER_SAVE` 同级），确保 `evaluate()` 立即暂停而非仅在 caution 路径生效。

**为什么这是等效保护**：官方处方是「共享 BandwidthMeter 让预加载感知播放器状态」。本项目无法共享 BandwidthMeter，但**前台 EOF 恢复是比带宽估算更直接、更确定的「播放器正在受损」信号**——它由实际发生的 `ProtocolException` 触发，不依赖估算置信度（当前该估算恰为 `trust:blocked` / `confidence:low`）。

**保持不变的契约**：

- 不改 `pathTrust=BLOCKED` 的判定（那是防止基于不可信估算做 ABR 决策的正确设计，不应放宽）。
- 不改 `EXTERNAL_LOOPBACK_NORMAL_BUFFER_MS` 等既有缓冲阈值。
- 不改既有 `preloadErrorCircuitOpen` / `openExternalCircuit` 熔断语义。
- 让位是**临时暂停**（冷却窗口后自动恢复），不是永久熔断。

### 3.3 改动三：网络保护降速区分故障类型（改动面最大，建议后置）

**文件**：`app/src/main/java/com/fongmi/android/tv/player/exo/ExoNetworkGuardController.java`（判据）、`PlayerManager.java`（接线）

**设计**：当检测到前台处于 EOF 恢复活跃窗口时，网络保护**不降速**（因为降速对连接截断无效），只记录 `reason=foreground-eof-recovery-hold` 并保持当前速度。

- `ExoNetworkGuardController.Input` 增加 `foregroundEofRecoveryAgeMs`。
- 在 `evaluate()` 中，若 `ageMs <= EOF_YIELD_WINDOW_MS` 且当前速度已 < 1（即已降过速），则走恢复路径而非继续降速；若当前速度为 1，则 `hold` 并标记新 reason。
- 新增 `State` 或复用 `WARNING` + 新 reason 字符串，避免改动状态机枚举（降低 UI 文案与测试影响）。

**风险**：这是三者中唯一会改变用户可见播放速度行为的改动，且与 `ExoNetworkGuardBufferPolicy`、`PlaybackTelemetry` 决策发布、OSD 文案（`networkProtectionEligibilityText`）耦合。**建议在改动一、二验证有效后再单独评估**。

### 3.4 明确不做

- 不改成 `DefaultPreloadManager`（架构级替换，见 2.5）。
- 不放宽 `pathTrust=BLOCKED` 判定（正确设计）。
- 不改 ABR / 轨道选择 / 解码器选择 / 缓存格式 / 隧道模式。
- 不修 49.17 / 52.94 / 57.36 s 三次 `playWhenReady=false→true` 抖动——`reason=1`（USER_REQUEST）不记录按键来源，日志无法归因，需独立复现，不属本任务。
- 不触碰外部本地代理本身（不在本仓库范围内）。

## 4. 验收标准

1. **改动一**：同一 offset 的连续重连间隔最小值 ≥ 100 ms；`consecutiveAttempt` 分布不劣化（仍为 1/2/3 各约 1/3）；EOF 恢复总数不增加；前台播放位置斜率仍为 1.000。
2. **改动二**：构造「前台正在 EOF 恢复」场景时，预加载在 15 s 窗口内不启动新任务（`exo-preload` 出现 `foreground-eof-recovery` reason 且无 `task-start`）；窗口过后预加载自动恢复；正常网络下预加载行为与改动前逐项一致（不误暂停）。
3. **改动三**（若实施）：EOF 恢复活跃窗口内 `play.clock.speed` 保持 1.000；无 EOF 恢复时降速行为与改动前一致。
4. **全局不回归**：解码器仍为 `c2.mtk.hevc.decoder`；`pathTrust` 仍为 `blocked`；缓存/ABR/字幕/音轨行为不变；掉帧数不增加。

## 5. 最小实施步骤（分阶段，各自可独立回滚）

#### 阶段 1（改动一，约 15 分钟）

1. 改 `HttpEofRecoveryDataSource.reconnectAtCurrentPosition()`，加退避常量与等待。
2. 加单元测试：`remainingLength` / `isRecoverableEof` / `canRecoverConsecutively` 既有测试保持通过；新增退避序列断言（用可注入的时钟或 `SystemClock` 间接断言）。

#### 阶段 2（改动二，约 40 分钟）

1. `HttpEofRecoveryDataSource` 增加角色标记与进程级「前台 EOF 恢复时刻」发布。
2. `MediaSourceFactory` 三处创建点传入角色（前台 2 处、预加载 1 处）。
3. `AutoPreloadPolicy` 增加 `Inputs.foregroundEofRecoveryAgeMs`、`Reason.FOREGROUND_EOF_RECOVERY`、`hardPauseReason()` 判据。
4. `PreCache` 接线：把前台 EOF 恢复年龄传入 `AutoPreloadPolicy.Inputs`。
5. 加策略单测：窗口内暂停、窗口外恢复、正常网络不误暂停。

#### 阶段 3（改动三，约 30 分钟，可选后置）

1. `ExoNetworkGuardController.Input` 增加字段与新 reason；`PlayerManager` 接线；OSD 文案补新 reason。
2. 加控制器单测。

## 6. 预计验证

- **编译**：`bash ./gradlew :app:compileLeanbackDebugJavaWithJavac`（或仓库既有等价目标）。
- **定向单测**：`HttpEofRecoveryDataSource` 相关 + `AutoPreloadPolicy` 相关 + `ExoNetworkGuardController` 相关，全部通过。
- **静态检查**：`git diff --check`。
- **设备验证（需用户配合，最决定性）**：用同一外部代理与同一片源复现，采集新 AV diagnostics 导出，核对：
  - EOF 恢复间隔拉长（改动一）；
  - 预加载活跃期与 EOF 的时间相关性下降（改动二）；
  - 掉帧总数相对本次 32 帧下降。
- **回滚**：三个阶段各自独立，回滚为单文件还原；`HttpEofRecoveryDataSource` 的两个历史提交（`c8a34e856b`、`34753212de`）是行为基线。

## 7. 未解决的门禁

1. **改动二的角色标记方案**需确认 `MediaSourceFactory` 三处创建点能否无歧义区分前台/预加载（行 102 是预加载路径，行 308/319-320 是前台路径，但行 319-320 的 `getDataSourceFactory(headers)` 是否也被预加载复用需实现时核对）。
2. **15 s 冷却窗口**的取值需设备实测校准；过短无效，过长会牺牲切集预加载收益（`EXTERNAL_LOOPBACK_DURATION_MS = 40_000` 是预加载单任务时长）。
3. **改动三**是否值得做，取决于改动一、二的实际效果；若掉帧已显著下降，改动三可降级为「暂缓」。
4. 本任务**不解决**外部代理本身的截断行为；即使三项全部实施，代理脆弱时仍会有掉帧，只是幅度下降。

## 8. 建议

**建议：实施阶段 1 + 阶段 2，阶段 3 暂缓待阶段 1/2 设备验证结果。**

理由：阶段 1 独立、低风险、直接消除重试风暴；阶段 2 是收益最大项，且是官方处方在本架构下唯一可行的等效实现；阶段 3 是唯一改变用户可见播放速度的改动，应在确认前两项效果后单独决策。

## 9. 用户决策

- [ ] 批准实施阶段 1（EOF 恢复退避）
- [ ] 批准实施阶段 2（预加载让位）
- [ ] 批准实施阶段 3（降速区分故障类型）
- [ ] 仅批准设计，暂不实施
- [ ] 其他：______

## 10. 下一步动作

等待用户对第 9 节作出选择。若批准，开 `upstream` lane，按阶段 1 → 2 →（可选 3）顺序实施，每阶段独立验证与原子提交。

## 附：证据溯源

| 结论 | 证据位置 |
| --- | --- |
| 32 帧掉帧 | 日志 `playback-metrics droppedFrames=/total=` 5 条（t=53.0/57.4/99.3/111.5/135.3） |
| 177 次 EOF 恢复 | 日志 `exo-network ... consecutiveAttempt=` 177 条 |
| 176/177 落在预加载活跃期 | 预加载 task-start/task-end 区间与恢复时刻对齐计算 |
| 135.2–172.6 s EOF=0 | 同期 `wait-recovery-buffer` 抑制预加载 + 前台满速 |
| 重连间隔 p50=47 ms | 177 条恢复事件相邻间隔统计（83% < 100 ms） |
| 预加载累计被抢占 30.7 s | `exo-preload task-start` 行的 `waitCount=13 waitTotalMs=30683` |
| 降速 1.000→0.919 | `exo-network-protection speed ...->...` 8 条（t=127.1–135.2） |
| `trust:blocked` 跳过吞吐判据 | `playback-decision domain=preload ... trust:blocked`；`ExoThroughputPathPolicy.java:60-61` |
| 熔断器从未触发 | 日志 `circuit-open` 出现 0 次；task-end 全为 completed/cancelled |
| 官方失败模式定性 | Android Developers PreloadManager Part 2（见 2.3） |
| `PreCacheHelper` 无 BandwidthMeter 入口 | sources.jar `PreCacheHelper.java` 行 134/153/173/197 |
| `proceedNonBlocking` 语义 | sources.jar `PriorityTaskManager.java` |
