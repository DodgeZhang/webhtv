# cf-purge-history-tombstones — 清理历史删除墓碑，解除有效进度记录的分页阻塞

## Recovery anchor

- 目标：清除服务端 611 条实验遗留墓碑（610 item + 1 all），让设备 pull 直接得到 5 条有效 progress，A/B 记录互通。
- 验收：维护端点部署后 curl 调用返回 purged=611（左右）；pull 全量只剩 5 条 upsert；设备删除重加同步源后同步 applied/created > 0，本地出现对方记录。
- 状态：端点代码已提交待部署；调用与设备验证在部署后进行。
- 回滚锚：revert 本次 commit 回到 b8c83b17a1。

## 证据（2026-10-04 09:0x，配额 08:00 重置后服务恢复）

- pull 全量（limit=1000，A 设备 key 3c264f95）：616 行 = **611 delete + 5 upsert**，hasMore=false。
- 5 条 progress（zhiqiu 兰香如故 / fishhxq 背着善宰跑 / FishMuou 给阿嬷的情书 / Wogg 八仙！/ Rebo 深渊无间），configKey 均已正确重写为请求设备 key。
- 墓碑：610 条 scope=item + 1 条 scope=all（deletedAt=10-03 23:15:54，用户主动清空实验）；范围 08-02 ~ 10-04 00:48。
- 5 条 progress updatedAt 全部晚于 scope=all 墓碑（最新 10-04 09:07），不会被 App 端墓碑压制（updatedAt <= deletedAt 才 skip）。
- App 计数逻辑（PlaybackProgressWriter）：delete 事件 cid 匹配后，本地无对应行 → skipped("本地记录不存在")。611 条墓碑对应的记录在设备上早已不存在 → 必然几乎全 skip。
- 设备每次同步只拉 100 条（maxItems 默认值），5 条有效记录被 611 条墓碑挡在后面；历史 maxItems=1000 测试（211/518）也未越过墓碑区。
- 结论：cid 匹配与 configKey 重写均正常；唯一障碍是墓碑占页。墓碑是"清空后同步"实验遗产，被删记录在所有设备上均无有效副本，清理不丢任何进度。

## 变更

新增 token 鉴权维护端点 `POST /api/playback/sync/maintenance`（`/playback/sync/maintenance` 同义）：

- Body：`{"op":"purgeTombstones","beforeDeletedAt":<ms>,"configType":"vod"}`
- 仅删除 `playback_tombstones.deleted_at < beforeDeletedAt` 的行；items/events 不触碰。
- DO 实例按 token hash 隔离，只有 token 持有者能清理自己的空间。
- vod 键无前缀（用 `NOT LIKE 'live:%' AND NOT LIKE 'wall:%'`），live/wall 有 `type:` 前缀（normalizeConfigType 只允许 vod/live/wall）。
- 返回 `{"ok":true,"op","configType","purged":N}`。

## 调用

```sh
curl -X POST https://webhtv-remote.dodge.cc.cd/api/playback/sync/maintenance \
  -H "X-WebHTV-Token: <token>" -H "Content-Type: application/json" \
  --data '{"op":"purgeTombstones","beforeDeletedAt":<Date.now()>,"configType":"vod"}'
```

## 验证

1. 调用后 purged ≈ 611；pull limit=1000 只剩 5 条 upsert。
2. A、B 设备各自删除并重新添加 WebhtvRemote 同步源（游标归零，否则可能已越过或停在墓碑区），maxItems 保持默认即可。
3. 同步后：拉取 5 · 处理（created）> 0 · 跳过为本地已有的较旧/相同记录；A 本地出现「背着善宰跑」等 B 侧记录，B 同理。

## 后续（不在本次范围）

- 产品级改进：pull SQL 可让 upsert 优先于墓碑排序（ORDER BY kind），避免未来墓碑再次占页。
- 设备本地 PlaybackDeleteTombstoneStore 已 record 的部分墓碑无需清理（时间早于 5 条 progress，不压制新记录）。
- cf-pull-legacy-key-rewrite 原任务关闭：本次证据证明当前"重写为 submittedConfigKey"已足够（设备 key=interfaceKey，cidForKey 三重匹配第一重即命中），无需 legacy URL-hash 重写。
