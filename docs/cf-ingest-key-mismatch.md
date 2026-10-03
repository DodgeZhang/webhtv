# cf-ingest-key-mismatch — webhook 400（ingest 校验 key 错位）修复

## Recovery anchor

- **目标**：修复归一生效后 App webhook 上报全部 HTTP 400（"播放过一会儿后 webhook 停止工作"）。
- **验收**：模拟 A 设备（header 与 payload.configKey 均为设备本地 key `3c264f95`）POST /api/playback/sync 返回 200；用户设备重新启用 webhook 后播放上报恢复。
- **回滚**：revert 本提交（单行）。
- **状态**：已实施，线上验证通过（200 + 自清理测试记录）。

## 根因（2026-10-04 00:59 线上诊断）

- App webhook 事件的 `payload.configKey` 用设备本地 key（`PlaybackRecord` L102/L141 `keyForCid`），header `X-WebHTV-Config-Key` 同值。
- 服务端 `ingest`（playback-sync.js L183 原实现）先 `resolvePlaybackConfigKey` 把提交 key 解析成 canonical，再用 **canonical** 校验 `payload.configKey`（normalizePlaybackEvent 的 `configKey does not match` → 400）。
- 归一部署前 `3c264f95` 自己就是 canonical → 校验通过；cf-identity-auto-merge 生效后它变成 alias（canonical=`4c1c5c5b`）→ **每条 webhook 必然 400**，App 侧暂停 webhook。时间线与"播放过一会儿后停止工作"吻合。

## 实施

`serverless/webhtv-remote-cloudflare/src/playback-sync.js` ingest：`normalizePlaybackEvent(raw, submittedConfigKey, ...)`——校验与事件归一用请求者提交的 key；存储归属不变（`event.storageConfigKey = canonical 的 scoped key`，L188）。

## 验证

- 线上 curl：以 A 的 key 作 header + payload.configKey POST 一条 `__verify__` 测试进度 → 200（修复前 400），随后发 item 墓碑自清理。
- 端到端：用户在 App 重新启用 webhook 后播放，调试日志无 400。
- 与 cf-pull-configkey-rewrite 组合：推送（webhook）与拉取两条链路在"设备本地 key ↔ canonical"双向翻译下均可用。

## 遗留观察

- 设备拉取方向"跳过 518"（服务端全量 618 且 configKey 重写已验证生效）原因未定，需 App 调试日志中 `playback-remote-sync` 的 skip 原因（接口不匹配/记录已被删除/远端记录不新于本地）定位；"拉取 518"与全量 618 的差值 100 也待日志解释（疑似设备游标未真正重置）。
