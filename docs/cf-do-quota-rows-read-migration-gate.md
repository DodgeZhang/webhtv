# cf-do-quota-rows-read-migration-gate — 身份解析迁移门限（A）+ purgeEvents 维护口（C）

## Recovery anchor

- 目标：消除每次 `POST /api/playback/identity/resolve` 无条件执行的空间迁移全表扫描（每个已别名空间约 9.5 万条 `playback_events`），并提供一个可分批回收历史事件去重行的维护操作；响应体、写决策、身份判定结果一律不变。
- 验收：resolve 在稳态（`keep` 且提交的键都已指向 canonical）时不再调用迁移；凡是仍然调用迁移的请求，其 `action`、`canonicalInterfaceKey`、`migrationRequired/Done`、`resetSince`、`nextSince`、`identityEpoch` 与改动前逐字节一致；迁移失败仍会被后续请求重试。
- 范围：A（`serverless/playback-identity-fixtures/identity.js` 迁移门限 + 待重试清单）、C（`serverless/webhtv-remote-cloudflare/src/playback-sync.js` 新增 `purgeEvents`）。B 档（事件保留期解耦）与 A 档第三项（`cleanup()` 的 `playback_meta` 读缓存）不在本次。
- 状态：代码已改，沙箱等价性验证通过，待提交。
- 回滚锚：revert 本次 commit 即回到 `c50524ba60`（= rows_read A 档前两项任务状态）。

## 计量事实（用户 Data Studio 截图 + DO 实例）

DO 实例 `user-df1b09fd3a46ec39a82ea221b7d65391cdb7c9066ba3419235c75516402201c5`（=`"user-"+sha256(token)`）：

| 表 | 行数 |
| --- | --- |
| playback_events | 194,859（`b27dbd82…`=95,525、`4c1c5c5b…`=95,323、`44fbd599…`=1,052、`242ffc3c…`=745、`5c7acac3…`=580） |
| playback_items | 10 |
| playback_tombstones | 56 |
| playback_identity_registry | 3 |
| playback_meta | 4 |

图中 `Rows Read: 194,891` 正是「一次 `SELECT COUNT(*) FROM playback_events` + 按 config_key 分组」的读量，与「每次 resolve 都要把每个已别名空间的全部事件行扫一遍」同源。

## 根因

1. `resolveIdentity` 对每次成功解析都 `next.epoch += 1`，注册表 CAS 必然成功，于是**每次** resolve（包括稳态 `keep`）都会走到迁移调用。
2. 迁移是「值拷贝」：CF 版 `migrateIdentitySpaces` 对每个源键执行 items / tombstones 的 `INSERT … SELECT`、一次 DELETE 扫描、以及 `INSERT OR IGNORE INTO playback_events … SELECT` —— 只写新行（重放写 0 行），但**读满整个源空间**。源空间的行从不删除，所以每次都是全量重扫：约 95k 行/已别名空间，两台设备的两个大空间 ≈ 19 万行/次。
3. App 每次同步都会先 resolve 身份，因此读放大 = 19 万行 × resolve 次数，逼近免费版每日 500 万 rows_read。

## 最佳实践研究（§7/§8 gate）

| 证据类 | 来源 | 结论 |
| --- | --- | --- |
| 官方文档 | Cloudflare Durable Objects SQLite storage / 计费（rows_read 与 rows_written 分列计量，均为行数） | 确认「值拷贝式迁移」的成本按被扫描行数计费，与数据是否变化无关；索引定位返回 0 行时读放大远小于全表扫描 |
| 官方文档 | SQLite `INSERT … SELECT … ON CONFLICT WHERE` / `DELETE … WHERE rowid IN (SELECT … LIMIT …)` | 确认「重放不写行」与「分批删除」都是集合式 SQL，可避免按行循环 |
| 上游源码 | 本仓库 `identity.js`（`resolveIdentity`/`migrateIdentitySpaces`）、`playback-sync.js`（CF 版迁移与 DDL） | 定位到迁移的唯一触发点是 resolve；`playback_events` 仅用于事件去重（`hasEvent`），不参与 pull 响应 |
| 项目内先例 | `docs/cf-do-quota-rows-written.md`（2026-10-04 rows_written 事故）、`docs/cf-do-quota-rows-read-tier-a.md` | 同配额族问题；写侧教训（集合式 SQL、禁止按行循环）与读侧教训（按事件的全表扫描）同构 |

### 方案对比

| 方案 | 做法 | 收益 | 风险 | 结论 |
| --- | --- | --- | --- | --- |
| no change | — | 无 | 每次 resolve 约 19 万行读 | 否 |
| A：迁移门限（本次） | 仅当本次 resolve 改变了绑定关系、或存在待重试迁移时才调用迁移 | 稳态 resolve 迁移调用 0 次（生产上即 19 万行/次归零） | 低：调用时的参数与旧实现逐字相同；失败重试由 `pendingMigrationKeys` 保留 | **采纳** |
| A′：迁移内部按表预检 | 迁移前用 `EXISTS` 判断源空间是否还有行 | 无法奏效：源空间的行从不删除，永远「有行」→ 仍要全扫 | — | 否 |
| A″：迁移后删除源行 | 拷贝完即删源 | 源空间清空后可预检 | 19 万行删除会瞬间打穿 10 万行/日写入配额 | 否 |
| C：`purgeEvents` 维护口（本次） | 维护端点分批 `DELETE` 历史事件去重行 | 让「再次发生迁移」的成本可控，同时回收死重 | 中：删除的是去重行，故必须由操作者按保留期口径决定 `beforeReceivedAt`；已与现有 90 天自动清理同构 | **采纳（分批）** |
| B：事件保留期解耦 | 单独 TTL | ≈ −60% | 合并判定最多延后一个请求（可观察变化） | 否（未获批） |

### 语义等价性论证（A 核心）

1. 迁移的源键集合是 `migrationSourceKeys = [...input.legacyConfigKeys, input.interfaceKey]`（`rebind` 时只取 `legacyConfigKeys`），与改动前**完全一致**；门限只决定「是否调用」，不改变「调用什么」。
2. 稳态 `keep` 且所有源键都已指向 canonical 时，源空间不可能再增长：任何以该键提交的写入都会经 `resolveConfigKey` 落到 canonical 空间（CF 版按 `config_key = 归一后的键` 存储与读取）。因此「再迁移一次」的语义结果必然是 `{migrated:false, pending:false, resetSince:false}` —— 这正是旧实现在稳态下反复得到的结果，跳过调用与调用得到的响应字段完全相同。
3. 反向情形（本次 resolve 新学到键：URL 迁移的新指纹、新设备 `create`/`adopt`/`merge`/`rebind`）在**变更前**注册表里还不是 canonical 的别名 → 门限放行，行为与旧实现一致。
4. 失败可重试：迁移在注册表 CAS 之后抛错时，旧实现在下一次 resolve 会重试（`next.epoch += 1` 保证 CAS 总能成功）。门限若只看别名就会永久失去这次重试，故新增 `identity.pendingMigrationKeys`：失败时记录源键、成功后清空（只在罕见的绑定变更/失败请求上多一次注册表 CAS，稳态路径仍是单次 CAS）。
5. `strictAddressKeys` / `endpointMatchKeys` / `hostMatchKeys` 从不作为迁移源键（旧实现同样如此），因此门限只检查迁移真正会用到的键，不会因它们而多跑迁移。

## 改动清单

### A. `serverless/playback-identity-fixtures/identity.js`（+34/−9）

1. `resolveIdentity`：迁移调用移入 `if (needsMigration)`，其中
   `needsMigration = resolution.action !== 'keep' || pendingKeys.length > 0 || migrationSourceKeys.some(key => !routesToCanonicalIdentity(registry, key, resolution.canonicalInterfaceKey))`。
2. 新增 `routesToCanonicalIdentity(registry, key, canonical)`：键等于 canonical，或 `registry.aliases[key].canonicalInterfaceKey === canonical`（镜像既有 `boundCanonical`）。
3. 新增 `rememberPendingMigration(store, registryKey, canonical, sourceKeys)` + `sameKeySet`：CAS 写/清 `pendingMigrationKeys`，集合相同则直接返回（不写）。
4. `normalizeIdentity` 增加 `pendingMigrationKeys: uniqueKeys(source.pendingMigrationKeys)`，使该字段在注册表读回时保留（上限沿用 `MAX_KEYS`，键形态沿用 `KEY_PATTERN`）。
5. 该文件被 cloudflare / deno / vercel 三个内置部署共用，三处同时生效。

### C. `serverless/webhtv-remote-cloudflare/src/playback-sync.js`（+34）

1. `runMaintenance` 新增分发：`if (op === 'purgeEvents') return this.runPurgeEvents(request, body, configType);`
2. 新增 `runPurgeEvents`：`beforeReceivedAt` 必填（正毫秒数）；`limit` 默认 5000、夹在 1..20000；可选 `configKey` 按身份归一后限定单空间（与 `purgeTombstones` 同口径）；语句为
   `DELETE FROM playback_events WHERE rowid IN (SELECT rowid FROM playback_events [WHERE config_key = ?] AND received_at < ? LIMIT ?)`，
   包在 `transactionSync` 内，返回 `{deleted, limit, hasMore}`。**分批是刻意的**：一次删除 19 万行会撞上免费版 10 万行/日 rows_written（先例见 `docs/cf-do-quota-rows-written.md`），由调用方按当日剩余额度反复调用。

## 验证

环境限制：本机无 node / npm / python，项目测试无法运行；决定性验证在 Exec（code_mode）沙箱内对**真实代码**完成。

- 载入方式：`tools.Read` 取 `identity.js` 当前源码，并 `git show HEAD:…` 取改动前源码，去掉行号前缀与 `export` 后在沙箱内 `new Function` 构造模块，注入内存 store（实现 `load`/`compareAndSet`/`migrateIdentitySpaces`，统计迁移调用）。
- **逐步骤等价**（15 步手工场景：create → 稳态 keep → URL 迁移新指纹 → 稳态 → adopt → confirm_required → merge → create/alias → rebind → 稳态）：响应体 `JSON.stringify` 完全相等，`responseMismatches = 0`；**凡两侧都调用迁移的步骤，参数逐字相同**（`argsEqual` 全真），差异只出现在门限跳过的稳态步骤。
- **随机穷举**：400 个随机序列 × 12 步 = 4800 次 resolve，覆盖 `create/adopt/merge/confirm_required/conflict/rebind/keep` 七种动作（样本量分别在 513/684/611/775/182/33/2002），`mismatches = 0`、`badCalls = 0`（当前实现的迁移调用数 2141 < 旧实现 3843，且每次调用都是旧实现的同参子序列）；该随机组合以「绑定变更」为主，故降幅 44% 低估了生产稳态下的收益（稳态应为 100% 跳过）。
- **失败重试**：令迁移抛错 → 两侧 `action` 均为 `migration_pending`；当前实现记录 `pendingMigrationKeys = ['hash1','dev1']`，下一次 resolve 又调用一次迁移（同参）并清空该清单，第三次 resolve 迁移调用 0 次；旧实现则每次都重试。两侧动作序列一致。
- **语法**：`identity.js`（736 行）与 `playback-sync.js`（2335 行）整文件去 import/export 后 `new Function` 解析通过，且注入 `const zz = ;` 的对照用例能被同一检查捕获（证明检查有效）。
- **C 行为**：默认 `limit=5000`、`limit=999999 → 20000`、`limit=-9 → 1`、`deleted === limit` 时 `hasMore=true`；未带 `configKey` 时 SQL 无 `config_key` 条件、绑定为 `[before, limit]`，带 `configKey` 时按 `live:canonical` 归一存储键并绑定 `[storageKey, before, limit]`；`beforeReceivedAt` 缺失/为 0 均 400；所有语句只针对 `playback_events`；分发行存在。
- 未验证（需部署后）：DO Metrics 的 rows_read 日增量对比；`purgeEvents` 只在真实 DO 上跑过才计分。

## 部署与后续操作

- 部署：推送 `main` 触发 CF 构建后生效（本次提交不自动推送，需用户授权）。
- 回收历史事件行（可选，按当日写入额度分批）：
  `POST https://webhtv-remote.dodge.cc.cd/api/playback/sync/maintenance`，头 `X-WebHTV-Token: <token>`，体 `{"op":"purgeEvents","beforeReceivedAt":<毫秒时间戳>,"limit":20000}`；返回 `hasMore=true` 即继续新一轮。建议单日不超过当日剩余 rows_written 额度（免费版 10 万行/日，UTC 0 点即北京 08:00 重置），例如每天 2 万行、约 10 天清完 19 万行。
- 事件去重行被删除的语义等同现有 90 天保留期清理：只影响「同一 eventId 被重复上报时是否被识别为重复」，pull 响应与观影记录不受影响。

## 遗留

- B 档（事件保留期解耦，≈ −60%）与 A 档第三项（`cleanup()` 的 `playback_meta` 读缓存）仍需用户单独批准。
- 请求量本身（App 30s 推送间隔）仍是最上游的乘数项，调大 `progressIntervalSec` 或升级 Workers Paid 是零代码互补手段。
