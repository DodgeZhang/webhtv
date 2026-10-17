# PC-REALTIME-SYNC-01 · 安卓侧实时同步契约补齐

- 状态：**A + C 已实施并验证**（改动 B 已勘误撤回；D 暂缓、E 待实验）
- 日期：2026-10-17
- 分支：`dev4`，HEAD `18a43b46f4c`
- 上游需求方：WebHTV PC（`docs/phase7/`，Phase 7「安卓实时同步与长连接保活」）
- 关联上游设计：`docs/playback-sync-identity-matching-design.md`（身份解析协议，已实现客户端侧）
- 关联上游设计：`docs/playback-history-delete-sync-design.md`（删除同步）

## Recovery anchor

- **目标**：让 PC 端 Phase 7 的实时同步（路径 A 轮询 / 路径 B 反向拉取）在安卓侧有可用契约。
- **验收标准**：见 §6 验收矩阵；每项都有可执行的定向验证。
- **当前状态**：改动 A（`GET /api/playback/history`）与改动 C（`apiSafe()` 追加 `episodeId`）已实施，
  双 flavor 编译 + 定向单测 + 变异验证已通过，并已在 `192.168.50.3:5561` 上覆盖安装实测。
  证据见 §10 实施记录。
- **下一步**：改动 E 需先做 `03` §3.2 的 logcat 实验；改动 D 暂缓（由对侧重算）。

---

## 1. 背景：PC 端需要什么

PC 端 `docs/phase7/design/01` 定义了实时同步的两条路径：

| 路径 | 方向 | 依赖的安卓侧能力 |
| --- | --- | --- |
| A | PC 定时轮询安卓 | 一个能返回「某站点历史」的**读取端点** |
| B | 安卓定时拉取 PC | PC 侧实现端点（PC 自己的事），但安卓会**额外**请求身份解析端点 |

PC 端原设计假设 `/api/playback/current` 就是路径 A 的读取端点。**本次侦察推翻了该假设**。

---

## 2. 只读侦察结论（源码级证据）

### 2.1 结论一：`/api/playback/current` **不是**「该站点最近一条历史」

| 事实 | 证据 | 等级 |
| --- | --- | --- |
| `PlaybackApi.current(siteKey)` 的数据源是 `PlaybackRuntime.currentHistory()` | `PlaybackApi.java:19-27` | 源码 |
| `PlaybackRuntime.currentHistory` 是一个 **static volatile 内存字段**，由 `PlaybackEventCollector.updateHistory()` 在**播放中**写入 | `PlaybackRuntime.java:36-39`、`PlaybackEventCollector.onProgress/onPlaybackStateChanged` | 源码 |
| 该字段**不落库**，进程重启即丢失；未在播放时恒为 `null` | `PlaybackRuntime.java:14`、`currentHistory()` | 源码 |
| `PlaybackApi.current` 在 `history == null` 时返回 `null` → 端点 404 | `PlaybackApi.java:20`、`PlaybackRecordApi.java:34-35` | 源码 |
| 全仓库**唯一**调用方是 `PlaybackRecordApi` | `grep -rn "PlaybackApi.current"` → 仅 1 处 | 源码 |

**推论**：安卓**息屏或未在播放**时，`/api/playback/current` 必然 404。
PC 的路径 A 因此**不可用**——而用户的核心诉求恰恰是「息屏后仍能同步」。

### 2.2 结论二：安卓已有完整的**身份解析协议**，但 PC 侧未实现

| 事实 | 证据 | 等级 |
| --- | --- | --- |
| 安卓侧 `PlaybackIdentityResolver` 已实现，并在同步前调用 | `PlaybackRemoteSyncer.sync()` 第 81 行 | 源码 |
| 它会 `POST` 到 `<远端>/api/playback/identity/resolve` | `PlaybackIdentityResolver.identityEndpoint()` | 源码 |
| 请求头含 `X-WebHTV-Identity-Version: 2`、`X-WebHTV-Address-Match-Version: 1`、`X-WebHTV-Request-Id` | `PlaybackIdentityResolver.resolve()` | 源码 |
| 响应 `404/405/501` → 标记 `unsupported`，进入旧服务端兼容模式 | `PlaybackIdentityResolver.resolve()` | 源码 |
| 协议规范已写入设计文档 §6.1–§6.6 | `docs/playback-sync-identity-matching-design.md` | 源码 |
| 五套服务端参考实现**已存在**（Cloudflare/Deno/Vercel/Go/Rust） | `serverless/webhtv-remote-*/` | 源码 |
| Go 参考实现完整，含 `identityResolve`、能力声明、别名路由 | `serverless/webhtv-remote-go/playback_identity.go`、`playback.go:120-138` | 源码 |
| 共享 fixture 已存在 | `serverless/playback-identity-fixtures/{identity.js,resolve-cases.json,canonical-addresses.json}` | 源码 |
| 身份键算法：`strict`/`endpoint`/`host` 三层 SHA-256，加 `legacy` URL 哈希 | `PlaybackConfigIdentity.material()` | 源码 |

**推论**：PC 作为服务端，**必须**实现 `POST /api/playback/identity/resolve` 与
`GET /api/playback/sync/status`，否则安卓每次同步都会打一次 resolve 拿到 404，
被记为 `unsupported`——功能可用但**永久失去跨设备身份匹配**，且用户可见状态是
「服务端不支持自动身份匹配」。

### 2.3 结论三：`apiSafe()` 不含 `episodeUrl`，导致 PC 的集消歧失效

| 事实 | 证据 | 等级 |
| --- | --- | --- |
| `apiSafe()` = `base()` − `event` − `eventId`；`base()` 含 `OBJECT` + `PROGRESS` + `PROTOCOL` | `PlaybackFieldPolicy.java:25-30` | 源码 |
| `OBJECT` 含 `episodeName` 但**不含** `episodeUrl` | `PlaybackFieldPolicy.java:11` | 源码 |
| `episodeUrl` 只在 `FULL` / `OPTIONAL` / `CUSTOM_SAFE`（webhook 用）里 | `PlaybackFieldPolicy.java:14-15` | 源码 |
| PC 的 `SyncHistoryItem.matchKey` = `(siteKey, vodId, flag, episodeId)`，`episodeId` 来自 `episodeUrl` | PC `lib/core/android_sync.dart:190`、`tryFromJson` | 源码（PC） |
| 现有单测只断言 `sourceBindingKey` / `speedOverride` 在 `apiSafe` 内 | `PlaybackSeasonSyncTest`、`PlaybackSpeedSyncTest` | 源码 |

**推论**：PC 从 `apiSafe` 响应里拿不到 `episodeUrl` → `episodeId` 为空 →
**同一剧的所有集塌缩成同一个 `matchKey`**，看第 3 集会覆盖第 1 集的记录。

### 2.4 结论四：`completed` 阈值两端不等价

| 事实 | 证据 | 等级 |
| --- | --- | --- |
| 安卓 `PlaybackRecord.completed` = `position >= duration \|\| progress >= 0.95` | `PlaybackRecord.java:403-407` | 源码 |
| PC `upsertHistory` / `SyncHistoryItem.completed` = `position >= duration - 5000` | PC `storage.dart:682-684`、`android_sync.dart:196` | 源码（PC） |
| 安卓已有 `History.isNearEnding()`，阈值是 `min(30s, max(5s, duration/100))` | `History.java:1053-1058` | 源码 |
| **同一仓库内已存在两套完播语义**（`PlaybackRecord` 的 0.95 与 `History` 的 near-ending） | 上两行 | 源码 |

**推论**：45 min 的剧集，安卓在剩余 135 s 就判完播，PC 要剩余 5 s。
跨端同步时会出现「安卓认为播完、PC 认为没播完」。

### 2.5 结论五：`STATE_ENDED` 路径**没有**「真播完」闸门

| 事实 | 证据 | 等级 |
| --- | --- | --- |
| `VideoActivity.onStateChanged(STATE_ENDED)` → `checkEnded(true)` → `checkNext` → `onNext` | `VideoActivity.java:6066-6072`、`checkEnded` 7846-7848 | 源码 |
| `checkEnded` 只转发到 `checkNext`，**不校验** position/duration | `VideoActivity.java:7846-7848` | 源码 |
| `PlaybackService` 在无人接管导航时 `navigateItem(1)` | `PlaybackService.listener.onPlaybackStateChanged` | 源码 |
| `resolveInitialPlaybackPosition()` 有 `isNearEnding()` 保护（重置位置） | `VideoActivity.java:11919-11928` | 源码 |
| 但 `isNearEnding()` **只**保护「续播位置」，**不**保护「是否切下一集」 | 上两行对比 | 源码 |
| `HttpEofRecoveryDataSource` 只允许**连续** 3 次 EOF 重连 | `HttpEofRecoveryDataSource.java:24` | 源码 |
| 缓存用 `FLAG_IGNORE_CACHE_ON_ERROR`（缓存读失败回源，不报错） | `MediaSourceFactory.getCacheDataSource` | 源码 |

**推论**：若提前 EOF 让 media3 报出 `STATE_ENDED`（服务端声明长度 == 实际可读长度时
media3 认为是正常结束），安卓会**直接切下一集**，且用户唤醒后点「继续播放」
看到的就是下一集——与用户实测完全吻合。

---

## 3. 不改动方案的边界（必须声明）

以下**不属于**本任务，PC 侧不得依赖：

| 项 | 原因 |
| --- | --- |
| 新增 WebSocket / SSE 端点 | 超出范围；PC 端设计已明确不做（`docs/phase7/design/00` §3.1） |
| 让 `Server` 在息屏后强制存活 | 属系统/厂商后台策略，代码层无法保证（`docs/phase7/design/02` §2.1） |
| 修改 `Backup.restore` 语义 | 破坏性，PC 端已明确不采用 `force` 清表 |
| 复制 PC 端爬虫 | 与 PC 端 P1 冲突，且与本任务无关 |

---

## 4. 现状与问题证据汇总

| # | 问题 | 用户可见后果 | 严重度 |
| --- | --- | --- | --- |
| P1 | `/api/playback/current` 只返回播放中的内存态 | 息屏后对端完全无法拉取历史 | **高**（阻塞 PC 路径 A） |
| ~~P2~~ | ~~缺 `identity/resolve` 与 `sync/status` 服务端契约~~ | **已勘误**：那是 PC 侧职责（见 §5.2） | — |
| P3 | `apiSafe()` 缺集消歧字段 | 同剧多集记录互相覆盖 | **高** |
| P4 | `completed` 阈值两端不等价 | 跨端进度错位 | 中 |
| P5 | `STATE_ENDED` 无「真播完」闸门 | 提前 EOF 后跳下一集（用户实测） | **高** |

---

## 5. 建议改动（逐项待批准）

### 5.1 改动 A（P1）：新增「按站点读历史」端点

**方案**：新增 `GET /api/playback/history?siteKey=<key>&limit=<n>`，从**数据库**读，
不依赖播放内存态。

| 项 | 设计 |
| --- | --- |
| 路径 | `/api/playback/history` 与 `/playback/history`（双路径，对齐既有风格） |
| 方法 | `GET`（`OPTIONS` → 204，其他 → 405） |
| 参数 | `siteKey` 必填（缺失 → 400 `siteKey不能为空`）；`limit` 可选（默认 20，上限 100） |
| 数据源 | `HistoryDao` 新增 `findBySiteKey(cid, keyPrefix, limit)`，用 `substr(\`key\`,1,length(:keyPrefix)) = :keyPrefix` 精确前缀匹配（**不用 LIKE**，见下表） |
| 字段策略 | 复用 `PlaybackFieldPolicy.apiSafe()`（**含改动 C 追加的 `episodeId`**，不含 `episodeUrl`） |
| 无记录 | 200 + 空数组（**不是 404**）——「没有历史」与「端点不存在」必须可区分 |
| 鉴权 | 与 `/api/playback/current` 一致（无鉴权），但**只读**、不泄露非该站点数据 |
| 隐私 | 遵循 `Setting.isIncognito()`：隐身模式下返回空数组 |

**为什么是新增而不是改 `current`**：`current` 有既有语义（"当前正在播什么"），
被 `PlaybackRecordApi` 独占使用；改它会破坏既有行为（AGENTS.md §2 禁止）。

**为什么不用 `key LIKE` 直接查**：`History` 主键是 `siteKey@@@vodId@@@cid`，
`siteKey` 可能含 `%`/`_`（SQL LIKE 通配符）。必须转义或用 `substr` 精确切分。
**建议**：新增 DAO 方法用 `substr(\`key\`, 1, length(:prefix)) = :prefix` 精确匹配，
避免 LIKE 注入。

### 5.2 改动 B（**已撤回，不属于安卓侧**）

**勘误（2026-10-17，实施期发现）**：本文档初稿把「实现身份解析服务端」列为安卓侧改动，**这是错的**。

| 事实 | 证据 |
| --- | --- |
| Android 本机 `Server` **没有** `/api/playback/sync` 路由 | `grep -rn "playback/sync" app/src/main/java/com/fongmi/android/tv/server/` → 0 命中 |
| Android 本机 `Server` **没有** `/api/playback/identity/resolve` 路由 | `grep -rn "identity/resolve" app/src/main/java/com/fongmi/android/tv/server/` → 0 命中 |
| 上游设计文档 §12.2 把「服务端」明确列为 `serverless/webhtv-remote-*` 五套实现 | `docs/playback-sync-identity-matching-design.md` §12.2 |
| `PlaybackIdentityResolver` 是**客户端**，`POST` 到 `RemoteSyncConfig.url` 指向的**远端** | `PlaybackIdentityResolver.resolve()`、`RemoteSyncConfig.url` |

**结论**：Android 是同步的**客户端**，不是服务端。在 PC 桥接场景下，
`RemoteSyncConfig.url` 由用户手填为 **PC 的地址**，因此
`/api/playback/identity/resolve` 与 `/api/playback/sync/status` 是 **PC 侧**的职责。

**处置**：改动 B 从本任务**移除**，转由 PC 端 Phase 7 实现
（`docs/phase7/design/01` §3.2.1 已完整描述该协议）。
本任务只保留 A（按站点读历史）与 C（集消歧），
它们确实是安卓侧（本机 Server）的职责。

### 5.3 改动 C（P3）：`apiSafe()` 补 `episodeUrl`

**方案**：把 `episodeUrl` 加入 `apiSafe()` 的字段集。

| 项 | 说明 |
| --- | --- |
| 改动 | `apiSafe()` 中 `fields.add("episodeUrl")` |
| 风险 | `episodeUrl` 是**播放地址**，可能含时效签名/CDN token |
| 缓解 | ① 该端点本就无鉴权，且已有 `vodName`/`vodPic` 等敏感度相当的数据；② 提供开关（默认关）让用户在「观影记录同步」里选择是否暴露单集地址 |
| 兼容 | 既有单测只断言 `sourceBindingKey`/`speedOverride` 在集合内，**追加**不会破坏 |

**替代方案（更保守）**：新增 `episodeId` 字段，值为 `episodeUrl` 的 **SHA-256**
（与 `PlaybackRecord.sha256` 同源）。这样 PC 能稳定区分集，但**拿不到真实地址**。
**建议采用替代方案**——它同时解决 P3 且不引入地址泄露。

### 5.4 改动 D（P4）：统一完播阈值

**方案**：把 `PlaybackRecord.completed` 的 `progress >= 0.95` 改为复用
`History.isNearEnding()` 的阈值语义。

| 项 | 说明 |
| --- | --- |
| 现状 | `PlaybackRecord` 用 0.95；`History.isNearEnding()` 用 `min(30s, max(5s, duration/100))` |
| 建议 | 抽一个共享判定（如 `PlaybackCompletionPolicy.isCompleted(position, duration)`），两处都调用 |
| 风险 | 改变 `completed` 会改变 webhook 与既有同步的语义；**必须**先确认无下游依赖 0.95 |
| 缓解 | 保留 `state == "ended"` 早返回（真播完仍判 `true`） |

**注意**：这是**行为变更**，影响面大于 A/B/C。若你希望最小化风险，可**暂缓 D**，
由 PC 侧按自己的公式重算（PC 端设计已如此：`docs/phase7/design/03` §4.2）。

### 5.5 改动 E（P5）：`STATE_ENDED` 加「真播完」闸门

**方案**：在 `VideoActivity.checkEnded` 与 `PlaybackService` 的 ENDED 分支
加同一判定：只有 `position >= duration - END_MARGIN`（或 `isNearEnding()`）才切下一集。

| 项 | 说明 |
| --- | --- |
| 现状 | `checkEnded` 直接 `checkNext`，无校验 |
| 建议 | `checkEnded` 先判 `mHistory` 的位置与时长；未达阈值则**停在当前集**并记日志 |
| 风险 | 若某些源的 `duration` 不准（偏大），会导致**该切却不切**，用户需手动下一集 |
| 缓解 | 阈值用 `isNearEnding()`（已含 `duration/100` 自适应 + 5–30 s 夹取），而非固定值；并在日志里写明判定依据 |

**这是行为变更**，且直接影响播放体验。**建议先做实验**（PC 端设计 `03` §3.2 的
logcat 实验）确认提前 EOF 是否真的报 `STATE_ENDED`，再决定是否实施。

---

## 6. 验收矩阵

| # | 改动 | 验证 | 判据 |
| --- | --- | --- | --- |
| V1 | A | `HistoryDao` 定向单测：`findBySiteKey` 对含 `%`/`_` 的 siteKey 精确匹配 | 断言不误命中 |
| V2 | A | `PlaybackHistoryApiTest`：无记录 → 200 空数组；缺 siteKey → 400；非 GET → 405 | 状态码与体精确 |
| V3 | A | `PlaybackApi.history` 的 limit 夹取（0 → 默认、>100 → 100） | 边界精确 |
| V4 | C | `apiSafe()` 含 `episodeId`，且**不含** `episodeUrl` | 两项都断言 |
| V5 | C | `PlaybackRecord.episodeId(url)`：空串 → 空串；非空 → 稳定 64 位 hex | 确定性与长度 |
| V6 | C | `PlaybackProgressInput` 能解析 `episodeId`，且有 url 无 id 时自动补 | 往返一致 |
| V7 | C | `PlaybackSeasonSyncTest`/`SpeedSyncTest` 仍绿 | 既有断言不破 |
| V8 | 全部 | `bash ./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac --no-daemon` | 双 flavor 编译通过 |
| V9 | 全部 | 定向单测：`--tests PlaybackHistoryApiTest --tests PlaybackEpisodeIdTest` | 全绿 |

**变异验证（AGENTS.md §4 要求）**：每项改动至少 1 组变异探针证明断言非空转。

---

## 7. 回滚路径

| 改动 | 回滚 |
| --- | --- |
| A | 删除 `PlaybackHistoryApi` 与 `Nano` 注册行；DAO 方法保留无害 |
| C | 从 `apiSafe()` 移除 `API_SAFE_EXTRA`；`episodeId` 字段保留无害 |
| D | 恢复 `PlaybackRecord.completed` 的 0.95 字面量 |
| E | 恢复 `checkEnded` 直接 `checkNext` |

全部改动**只新增文件 + 少量追加**，无 schema 迁移、无既有行为删除，
可逐项独立回滚。

---

## 8. 建议的实施顺序与范围

| 阶段 | 内容 | 风险 | 建议 |
| --- | --- | --- | --- |
| 1 | 改动 A + C（读取端点 + 集消歧） | 低（纯新增 + 字段追加） | ✅ **本次实施** |
| ~~2~~ | ~~改动 B（身份解析服务端）~~ | — | **已撤回**：属 PC 侧职责（§5.2） |
| 3 | 改动 E（STATE_ENDED 闸门） | 中（改播放行为） | 先做 logcat 实验再定 |
| 4 | 改动 D（完播阈值统一） | 中高（影响 webhook 与既有同步） | ⏸️ **暂缓**，由对侧重算 |

---

## 9. 决策状态

| # | 问题 | 状态 |
| --- | --- | --- |
| Q1 | 改动 A 是否实施？ | ✅ **已实施**（用户"按建议来"） |
| ~~Q2~~ | ~~改动 B 是否实施？~~ | **无需决策**：已勘误为 PC 侧职责（§5.2） |
| Q3 | 改动 C 用 `episodeUrl` 还是 `episodeId`（哈希）？ | ✅ **已采用哈希**（`episodeId`，不泄露播放地址） |
| Q4 | 改动 D 是否实施？ | ⏸️ **暂缓**（按建议，由对侧重算） |
| Q5 | 改动 E 是否实施？ | 🔬 **先做实验**（`03` §3.2 的 logcat 实验） |

---

## 10. 实施记录（改动 A + C）

### 10.1 交付物

| 类别 | 路径 | 说明 |
| --- | --- | --- |
| 新增端点 | `app/src/main/java/com/fongmi/android/tv/server/process/PlaybackHistoryApi.java` | `GET /api/playback/history`（含 `/playback/history` 别名）；`OPTIONS`→204、非 GET→405、缺 `siteKey`→400、无记录→`200 {"items":[],"total":0}` |
| 新增 DAO | `HistoryDao.findBySiteKey(cid, keyPrefix, limit)` | `substr(\`key\`,1,length(:keyPrefix)) = :keyPrefix`，**不用 LIKE** |
| 新增逻辑 | `PlaybackApi.history(siteKey, limit)`、`PlaybackApi.effectiveHistoryLimit(limit)` | 从 `HistoryDao` 读；隐身→空；`limit<=0`→20，`>100`→100；读库/单条异常均记日志后跳过，不让整批失败 |
| 新增字段 | `PlaybackFieldPolicy.API_SAFE_EXTRA = {"episodeId"}` | 只给集指纹，不给 `episodeUrl`；**声明必须排在 `CUSTOM_SAFE` 之前**（Java 静态字段按文本顺序初始化，写后面会 NPE） |
| 新增字段值 | `PlaybackRecord.episodeId(url)` + `from()` 自动派生 | `episodeUrl` 的 SHA-256（64 位小写 hex）；空串→空串 |
| 路由注册 | `app/src/main/java/com/fongmi/android/tv/server/Nano.java` | 追加一行，未改既有路由 |
| 单测 | `PlaybackEpisodeIdTest`、`PlaybackHistoryLimitTest`、`PlaybackHistoryApiTest` | 17 个用例 |
| 新增入参 | `PlaybackProgressInput.episodeId` | 对端回推时可带集指纹 |

### 10.2 验证证据

| # | 项 | 命令/方法 | 结果 |
| --- | --- | --- | --- |
| 1 | 双 flavor 编译 | `gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac` | BUILD SUCCESSFUL |
| 2 | 定向单测 | `gradlew :app:testMobileArm64_v8aDebugUnitTest --tests "com.fongmi.android.tv.playback.*" --tests "com.fongmi.android.tv.server.*"` | 全绿（含既有 `PlaybackSeasonSyncTest`/`SpeedSyncTest`） |
| 3 | 变异 1：`apiSafe` 不放行 `episodeId` | 移除 `fields.addAll(API_SAFE_EXTRA)` | `PlaybackEpisodeIdTest` 如期转红，已还原 |
| 4 | 变异 2：`from()` 不派生 `episodeId` | 注释掉 `record.episodeId = episodeId(record.episodeUrl);` | 如期转红，已还原 |
| 5 | 变异 3：`limit` 不夹上限 | 去掉 `Math.min(limit, MAX_HISTORY_LIMIT)` | `PlaybackHistoryLimitTest` 如期转红，已还原 |
| 6 | 真机覆盖安装 | `adb -s 192.168.50.3:5561 install -r app-mobile-arm64_v8a-debug.apk` | Success（保留数据，未卸载） |
| 7 | 真机路由 | `adb forward tcp:19978 tcp:9978` + `curl /device` | 200，`OnePlus HD1910` |
| 8 | 缺 `siteKey` | `GET /api/playback/history` | `400 {"code":400,"message":"siteKey不能为空"}` |
| 9 | 无记录 | `?siteKey=__nonexistent__` / `?siteKey=CupFox` | `200 {"items":[],"total":0}`（**不是 404**） |
| 10 | 有记录 | `?siteKey=kw_music_t4` / `push_agent` / `rebang` | `200`，返回真实落库记录，字段含 `episodeId` 且**不含** `episodeUrl` |

第 10 项的真实响应（`rebang`，节选）：

```json
{"siteKey":"rebang","items":[{"historyKey":"rebang@@@75112@@@3","siteKey":"rebang",
"vodId":"75112","vodName":"扫毒风暴","flag":"线路1(nby)","episodeName":"1. 西港惊现冰毒大案",
"episodeId":"86d2c06c9ae40dbe4716416777ea26e54b9e88ab44c0605b08141f5efd1a3697",
"positionMs":855470,"durationMs":2787160,"completed":false}],"total":1}
```

### 10.3 实测中发现并已修正的问题

| # | 问题 | 修正 |
| --- | --- | --- |
| 1 | `PlaybackFieldPolicy` 静态初始化 NPE | `API_SAFE_EXTRA` 移到 `CUSTOM_SAFE` 之前（Java 静态字段按文本顺序初始化） |
| 2 | 读库异常时静默返回空数组，把「库挂了」伪装成「没有历史」 | `catch` 内补 `SpiderDebug.log` |
| 3 | 单条坏记录会让整批读取失败 | 单条 `try/catch` + 记日志后跳过 |

### 10.4 已知限制（不构成本任务缺陷）

`PlaybackApi.history` 只查当前 `VodConfig.getCid()`（当前线路配置）的历史。
实测设备上历史分散在多个 `cid`（`1`/`3`/`18`…，对应不同订阅配置源），
因此对**非当前配置**下的历史，本端点返回空数组。
这与既有「按当前配置读历史」的语义一致（`History.find` 同源）；
对端 PC 的路径 A 只能拉到与当前配置同源的记录。

**若后续需要跨配置读取**（PC 端不切换配置也能拿到全部历史），
应作为独立改动评估：新增「按 `siteKey` 跨 `cid`」的查询 + 明确返回 `cid` 归属，
而不是改动本端点的既有语义。
