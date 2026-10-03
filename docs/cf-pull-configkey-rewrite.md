# cf-pull-configkey-rewrite — pull 响应 configKey 按请求设备重写

## Recovery anchor

- **目标**：修复"服务端归一/迁移成功但设备仍收不到对方记录"——App 按事件 `configKey` 反查本地接口（`PlaybackConfigIdentity.cidForKey`），迁入事件保留原设备 key，请求设备不认识即跳过"接口不匹配"。
- **验收**：A/B 设备各手动同步一次后，能应用对方设备的播放记录；线上 curl 模拟拉取可见每条 change 的 configKey 等于请求者自己的 key。
- **回滚**：revert 本提交（单点改动，pull 响应恢复原 payload configKey）。
- **状态**：已实施，线上冒烟通过。

## 根因（2026-10-04 线上诊断）

- 服务端自动归一（cf-identity-auto-merge）已生效：`3c264f95`（A）与 `b27dbd82`（老空间 28 条）均为 alias，canonical 为 `4c1c5c5b`（B），迁移成功（canonical 含 A 2 条 + 老空间 5 条 + B 1 条，其余被 610 条历史墓碑删除）。
- 但 App 端 `applyFromRemoteSync` 用 `cidForKey(event.configKey)` 找本地 cid：只匹配本地接口的 interfaceKey / URL 哈希 / legacyConfigKeys（PlaybackConfigIdentity.java L54-64）。迁入事件带原设备 key（如 B 的 `4c1c5c5b`），A 设备查不到 → "接口不匹配" → 全部跳过。
- 官方不会遇到此场景：官方靠首次 resolve 的 `adopt` 换 key（PlaybackIdentityResolver.apply L125-126 仅 adopt/migration_pending 才 setInterfaceKey），"服务端事后合并 + keep via alias"是 WebHTV 新增能力。

## 实施

`serverless/webhtv-remote-cloudflare/src/playback-sync.js` DO pull 方法（changes 构造循环）：解析 payload 后将 `change.configKey` 重写为 `submittedConfigKey`（请求 header `X-WebHTV-Config-Key` 原值）。upsert 与 tombstone 一并覆盖；损坏行仍按原逻辑跳过。

## 修正（cf-pull-legacy-key-rewrite，2026-10-04 01:2x）

**重写目标由"请求设备本地 key"改为"canonical 身份的 legacyConfigKeys[0]"**（无则回退 submitted key）。

- 依据：A 设备删除重加后全量拉取 619 条仍全跳过（fetched=619 applied=0 skipped=619 identity=keep），而服务端已验证每条 configKey=设备本地 key（3c264f95）。git 考古发现 `cidForKey` 早期版本（5b0500d51b）**只匹配 keyForUrl（URL SHA-256）**，identity 版（cc85ad350c）才加入 interfaceKey/legacy 三重匹配——设备 APK 构建版本的行为介于两者之间。
- legacyConfigKeys[0] 是 App 自己上报的旧协议 URL 哈希 key，同接口两台设备算出同一值：旧版 cidForKey 经 `keyForUrl(config.getUrl())` 分支命中，新版经 `getLegacyConfigKeys().contains` 分支命中，两版通吃。
- 验证：部署后 curl 确认 A/B 视角拉取事件 configKey = canonical 身份的 legacy 哈希 key；A 设备同步后日志 applied>0 或本地出现对方记录。

## 边界与影响

- App 推送链路不变（ingest 端 configKey 校验仍按提交 key）。
- dashboard 拉取展示的 configKey 变为查询所用 key，无功能影响。
- 游标、eventId 幂等、seq 语义均不变。
