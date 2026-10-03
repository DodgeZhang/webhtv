# cf-do-quota-rows-written — DO 免费版每日写入配额耗尽事故与集合式迁移修复

## Recovery anchor

- 目标：修复 2026-10-04 凌晨全局 1101（两个 DO 同时故障），根因为 DO 免费版每日 rows written 配额耗尽；防止再次耗尽。
- 验收：服务恢复（status/health/pull 返回 JSON）；一次迁移重放（已归一空间再次 resolve）的 rows written ≈ 0；A/B 设备同步验证另行任务。
- 状态：代码已改，待 CF 构建部署；服务恢复依赖配额 UTC 0 点（北京 08:00）重置或用户升级 Workers Paid。
- 回滚锚：revert 本次 commit 即回到 5c9a4be597（= cf-ingest-key-mismatch 3d804f5972 状态）。

## 时间线与证据

- 2026-10-04 ~01:23 部署 cf-pull-legacy-key-rewrite（f9f7e434c6）后，`/playback/sync/*` 与 `/api/health` 全部 `error code: 1101`，`/`（纯静态 dashboard）正常。
- f9f7e434c6 代码静态审查完全异常安全（全程 try/catch、无新 import、`catch {}` 语法现有代码已在用），且 relay.js 未改也挂 → 排除代码逻辑问题，立即 revert（5c9a4be597）。
- revert 部署成功（版本 3a353868）后仍 1101 → 排除构建产物损坏。
- DO Metrics：错误 46 次"Worker 未捕获异常"、CPU/内存超限均为 0、**存储操作 2M（+266%）**。
- DO Logs 异常原文：**"Exceeded allowed rows written in Durable Objects free tier."**（免费版 10 万行写入/天，UTC 0 点重置）。
- 结论：写入配额耗尽 → 任何存储写操作抛异常 → DO 构造器（`blockConcurrencyWhile(migrate)`，含 `INSERT OR IGNORE` 写操作）失败 → 该 DO 实例所有请求 1101。构造器在 try/catch 之外，故表现为未捕获异常。RELAY_DO 同理（独立 hammering）。

## 写入量元凶（本仓库代码）

`migrateIdentitySpaces`（playback-sync.js）按行循环迁移：

- 每条 item：SELECT current + `nextSequence()`（1 写）+ INSERT（1 写）≈ 2 写/行
- 每条墓碑：SELECT + seq 写 + INSERT 写 + **每条墓碑再执行一次 `DELETE FROM playback_items ... updated_at <= ?`**
- 610 条历史墓碑（上次"清空后同步"实验遗产）+ 28 条老空间记录 → **单次合并 ≈ 6000+ 行写入、~1900 条 SQL 语句**
- 今晚叠加：用户多次手动合并测试 + cf-identity-auto-merge 自动归一 + webhook 400 风暴期间的重试推送 → 突破 10 万行/天

## 修复（本 commit）

`migrateIdentitySpaces` 改为集合式 SQL，语义不变（较新者胜、不删源、resetSince=migrated）：

1. items：`INSERT ... SELECT ... ROW_NUMBER() OVER (ORDER BY updated_at, item_key) + base ... ON CONFLICT DO UPDATE ... WHERE excluded.updated_at > playback_items.updated_at` —— 只写严格更新的行，重放写 0 行。
2. tombstones：同构，冲突目标 (config_key, marker_key)，`WHERE excluded.deleted_at > ...`。
3. 序列推进：`base = sequenceValue()`；items 占 base+1..base+rowsRead，tombstones 占其后的 rowsRead 段；最后一次 `UPDATE playback_meta SET value = tombstoneBase + tombstoneScan`（扫过的行都消耗 seq，与旧行为的全局递增等价，UNION pull 光标不会跨表撞号）。
4. 删除清扫：旧的"每条墓碑一次 DELETE"合并为一条 `DELETE ... WHERE EXISTS（墓碑覆盖判定，与 applyUpsert/applyDelete 语义一致）`，重放写 0 行。
5. events：`INSERT OR IGNORE ... SELECT`，只写新行。

单次合并从 ~6k 行写入降到：首次 = 实际新增/更新行数；重放 ≈ 0 行。

## 验证

- 本机无 node/python，无法跑 `npm test`（exit 9009）；静态审查 SQL（upsert 子句带冲突目标 + SELECT 带 WHERE 消歧义、窗口函数、参数绑定）+ CF 构建通过为语法验证。
- 服务恢复验证（配额重置后）：`curl /playback/sync/status`、`/api/health` 返回 JSON 而非 1101。
- 防复发验证：任一设备触发 resolve（已归一空间重放合并）后，DO Metrics 存储操作增量应接近 0。

## 遗留与后续

- 服务恢复时间：北京时间 08:00（UTC 0 点配额重置）自动恢复；或用户升级 Workers Paid（$5/月）立即恢复。
- cf-pull-legacy-key-rewrite 的原始目标（A 设备拉取全跳过，cidForKey 只认 URL 哈希）仍未解决，待服务恢复后单独继续。
- App 端 PlaybackRemoteSyncer.java 冲突标记、serverless 目录历史冲突标记清理：独立任务。
- 附注：f9f7e434c6 的 revert（5c9a4be597）为紧急操作，未走 guard，代码与 3d804f5972 等价。
