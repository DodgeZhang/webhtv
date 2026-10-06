# cf-do-quota-rows-read-tier-a — DO 免费版每日读取配额（rows_read）A 档降读优化

## Recovery anchor

- 目标：降低 `PLAYBACK_DO` 每请求固定读取行数，缓解 Cloudflare 告警「免费套餐 Durable Objects 每日 5,000,000 rows_read 已用 90%」，且不改变任何响应内容与写决策。
- 验收：ingest/pull 返回体、跳过判定、`seq` 报告值、写入行数逐字节不变；墓碑探测在无压制时不再扫描该空间墓碑集合。
- 范围：A 档前两项（墓碑探测前置 + 覆盖索引；注册表同请求 load 去重）。A 档第三项（`cleanup()` 的 `playback_meta` 读缓存）与 B 档（Pass 2 memo）不在本次。
- 状态：代码已改并完成本地等价性穷举验证，待提交。
- 回滚锚：revert 本次 commit 即回到 `480960d58e`（= 清除/注销合并按钮任务状态）。

## 计量事实与读放大模型

- 告警指标是 **rows_read（行读取）**，与 `cf-do-quota-rows-written.md` 记录的 **rows_written（10 万行/日）** 是不同配额，重置点同为 UTC 0 点（北京 08:00）。
- 生产数据量很小：6 个空间（2 canonical + 4 未注册）、`playback_items` 56 行、未过期墓碑 24 条、`nextSince ≈ 126903`。故成本主因不是数据量，而是 **每请求固定读放大 × 请求量**。
- 固定读 ≈ 87 行/请求：
  - `spaceSnapshot` → `spaceNameIndex` **全表聚合**（该 DO 实测 56 行）。`ingest` 与 `pull` 无条件调用；代码注释自称 "Cheap short-circuit"，但短路判断位于 `planNameUnifyGroups` 内部，**扫描开销在短路之前已付**。`config_key NOT LIKE 'live:%'` 无法走索引 → 真全表扫描。
  - `applyUpsert` 墓碑 `MAX(deleted_at), MAX(seq)` 查询读该空间**全部墓碑**（24 行），**按事件**执行；墓碑保留 90 天（`TOMBSTONE_RETENTION_MS`），集合只增不减。
- 请求量来源：App 按 `progressIntervalSec`（默认 30s）在播放中每间隔推一次 `playback.progress`，每次一个独立 DO 请求。4.5M / 87 ≈ 5 万请求/天 ≈ 0.6 次/秒。
- 已排除项：`RELAY_DO`（relay.js）状态全在模块内存（`let state = defaultState`），不触任何 SQL/存储，不消耗 rows_read；其消费方只有 `PLAYBACK_DO`。

## 最佳实践研究（§7/§8 gate）

| 证据类 | 来源 | 结论 |
| --- | --- | --- |
| 官方文档 | Cloudflare Durable Objects SQLite storage / 计费（rows_read、rows_written 分列计量） | 确认两指标独立计费；索引扫描也算行读取，但索引定位返回 0 行时读放大远小于全表扫描 |
| 官方文档 | Cloudflare D1/SQLite 查询计划（composite index 前缀 + 范围条件走 range scan） | 复合索引 `(config_key, deleted_at)` 可把「该空间全部墓碑」降为「按 deleted_at 的范围探测」 |
| 上游源码 | 本仓库 `playback-sync.js`（`applyUpsert` / `autoUnifyIdentity` / `spaceSnapshot`） | 定位到两处可零语义变化的降读点 |
| 项目内先例 | `docs/cf-do-quota-rows-written.md`（2026-10-04 rows_written 事故） | 同配额族问题；教训是「集合式 SQL、禁止按行循环」，本次为读侧的同类思路 |

### 方案对比

| 方案 | 做法 | 收益 | 风险 | 结论 |
| --- | --- | --- | --- | --- |
| no change | — | 无 | 配额继续逼近上限 | 否 |
| A 档（本次） | 墓碑探测前置 + 覆盖索引；注册表同请求 load 去重 | 墓碑按事件扫描（24 行/事件）在绝大多数事件上归零；注册表 load 由 2 次减为 1 次（仅 Pass 1 真合并时重读） | 低：探测是原查询超集，命中即回落原查询 | **采纳** |
| A 档第三项 | `cleanup()` 读 `playback_meta` 缓存 | 一次读 | 低 | 本轮不做（用户只批前两项） |
| B 档 | Pass 2 增加序列号 + 注册表版本 memo | ≈ −60% | 合并判定最多延后一个请求（行为可观察变化） | 否（非零变化） |
| C 档 | 物化汇总表 | 高 | 写路径复杂化、一致性与迁移风险 | 否（风险收益不划算） |
| 零代码杠杆 | 调大 App 推送间隔 / 升级 Workers Paid | 线性 | 无 | 仅作为补充手段告知用户 |

### 语义等价性论证（A 档核心）

1. 墓碑压制条件为 `event.updatedAt <= MAX(deleted_at)`（作用域过滤后）。等价地：**存在**同空间墓碑满足 `deleted_at >= event.updatedAt`。
2. 新探测 `SELECT MAX(deleted_at) FROM playback_tombstones WHERE config_key = ? AND deleted_at >= ?` **不带 scope 过滤**，故其行集是「作用域过滤后集合」的**超集**。
3. 探测返回空 → 作用域集合中不可能存在 `deleted_at >= updatedAt` → 其 `MAX(deleted_at) < updatedAt` → 原判定 `deletedAt > 0 && event.updatedAt <= deletedAt` 必然为假 → **不跳过**。与原行为一致。
4. 探测非空 → **原 scoped 查询逐字执行**，得到与原实现完全相同的 `tombstone` 行 → 跳过判定与 `seq` 报告值完全一致。
5. `deletedAt` 在 `applyUpsert` 中仅出现 3 次（声明 1 + `if (deletedAt > 0 && ...)` 同行 2），无其它引用；未压制时 `tombstone.seq` 不被使用 → 短路安全。
6. `event.updatedAt` 类型：唯一调用点 L238 输入来自 `normalizePlaybackEvent`，其 `updatedAt = positiveTimestamp(...)` 保证为 safe integer（> 0），否则回落 `now = Date.now()`。**恒为 Number**，探测 SQL 的 INTEGER 与 TEXT 比较语义风险不存在。

## 改动清单（`serverless/webhtv-remote-cloudflare/src/playback-sync.js`，33+/4-）

1. `migrate()`：新增 `CREATE INDEX IF NOT EXISTS idx_playback_tombstones_config_deleted ON playback_tombstones (config_key, deleted_at);`（独立 `this.sql.exec`，位于原 DDL 块之后）。仅新增查询结构，不触碰既有 5 个索引与表定义。
2. `applyUpsert()`：墓碑 `MAX` 查询前插入不带 scope 的索引范围探测；探测为空则 `tombstone = null`，非空则**逐字执行原 scoped 查询**（SQL 文本与参数顺序完全未变）。
3. `autoUnifyIdentity()`：`registry` 由 `const` 改为 `let`，Pass 2 直接复用首读结果；仅在 Pass 1 的指纹合并确实写入了注册表（`merged` 未包含 submitted 且非 `alreadyMerged`）时重读一次。这是一条**保守**的简化：只有真正发生合并的极少数请求才会多付一次读，其余从 2 次降为 1 次。

## 验证

- 环境限制：本机无 node / npm / python（`python` exit 9009 为 WindowsApps 存根），无法跑项目测试或 sqlite3。
- 决定性验证在 Exec（code_mode）沙箱以纯 JS 等价模型完成：
  - 三个被改方法整体 `new Function(...)` 解析通过（`migrate` / `applyUpsert` / `autoUnifyIdentity`）。
  - 源码断言全绿：`probe_sql_present`、`scoped_scope_all`、`scoped_season_tail`、`scoped_max_both`、`scoped_gated_by_probe`、`probe_before_scoped`、`deletedAt_count=3`、`tombstone_seq_uses=1`、`index_ddl=true`、`migrate_other_indexes_intact=5`、`autoUnify_registry_loads=2`、`pass2_uses_registry`、`pass2_no_inline_reload`、`reload_after_guard`、`registry_declared_let`。
  - **等价性穷举**：随机 400000 例（`suppressed=134196`、`probeHits=268410`、`mismatches=0`）；边界扫描 260 例，覆盖 `deleted_at === updatedAt` 等号边界（`-2..+2` 偏移 × 4 种 scope），`mismatches=0`。
- 部署后建议（非阻塞）：DO Metrics 观察 rows_read 日增量，灰度对比「仅 `playback.progress` 推送」时段的读放大应从 ~87 行/请求明显下降。

## 遗留与后续

- A 档第三项（`cleanup()` 的 `playback_meta` 读缓存）未做，可独立评估。
- B 档（Pass 2 memo，≈ −60%）因其「合并判定最多延后一个请求」的可见行为变化，需用户单独批准。
- 根因仍含请求量项：App 端 `progressIntervalSec` 可调大，或升级 Workers Paid，作为零代码互补手段。
