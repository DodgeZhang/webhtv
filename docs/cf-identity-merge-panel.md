# cf-identity-merge-panel：播放记录空间手动归一面板

Recovery anchor：目标 = 在 webhtv-remote-cloudflare 的 dashboard 中提供身份感知的空间列表与手动合并操作，解决官方身份协议下同接口多空间无法归一的问题。验收 = 新端点 `/identity/spaces`、`/identity/merge` 可用，登录页空间列表显示身份徽章与分组，支持一键合并同名接口与手动互并，合并后设备无需任何操作即可同空间推拉。

## 背景（决策性研究结论，2026-10-03）

- 官方协议的死锁：App 从不发送 `confirm:true`（`PlaybackIdentityResolver.request()` 无 confirm 字段），且已注册身份在 `chooseIdentity`（playback-identity-fixtures/identity.js L481-486）走 `bound → keep/conflict` 短路，不看 confirm 也不看 sourceDataState。因此"清空记录重播"或外部重发 resolve 都无法合并已注册 key。
- 合并所需原语官方已具备：`migrateIdentitySpaces`（DO SQL 实现，playback-sync.js L279+，items/tombstones/events 较新者胜、不删源、可回滚）、alias 注册、CAS 注册表。官方仅通过 resolve 的 adopt/merge 路径暴露，dashboard 无法触达。
- 分组判定必须用强线索（strict/endpoint/legacy）交集；host 级线索会误合并同一代理主机（cnb.cool、gh-proxy.org）上的不同接口，明确排除。
- 新逻辑放在 WebHTV 适配层（src/playback-sync.js、src/dashboard.js），不改官方共享模块 identity.js，避免上游合并时本地扩展丢失（media_type 教训）。

## 实施内容

- src/playback-sync.js：
  - 路由新增 `GET /api/playback/sync/identity/spaces`（isPlaybackSyncPath 同步放行）与 `POST /api/playback/sync/identity/merge`。
  - `listIdentitySpaces`：listConfigs 聚合 + 注册表状态（canonical/alias/unregistered）+ 强线索分组；已注册但零记录的 canonical 以 items=0 列出，保证合并目标始终可见。
  - `mergeIdentityRegistry`（导出纯函数，CAS 5 轮）：target alias 解析 → 未注册 target 自动建档 → 源身份线索并集（含 host，上限 32）→ 源身份降级 alias（kind=manual-merge）→ 迁移源空间；源已解析到 target 时幂等跳过。
- src/dashboard.js：findConfigs 切换到 /identity/spaces；空间行显示身份徽章与"并入…"手动合并；同组显示"疑似同一接口"组头与"一键合并该组"（目标 = canonical 优先、记录数次之）；确认框用 DOM API 构建（规避外层模板字符串转义与内联 onclick 注入）。
- test/playback-sync.test.js：新增路由、强线索分组（host 不参与）、合并降级+线索并集、幂等/alias 目标解析、非法输入拒绝共 5 个用例。

## 验证

- 本机无 node/npm 运行时，单元测试已就绪但未本地执行（诚实限制，后续有 node 环境时 `npm test` 一键补跑）。
- 已做逐项代码自查（调用签名、错误状态传播、转义上下文、scope 内改动）；发现并修复路由漏传 `request` 一处。
- 决定性验证 = push 后 Cloudflare 构建（esbuild 抓语法/导入错误）+ 线上冒烟：`GET /api/playback/sync/identity/spaces` 应返回 ok 与 spaces/groups。

## 使用与预期效果

登录页"查询已有接口"后：同接口多个空间会聚成一组，点"一键合并该组"→ 确认 → 源空间数据迁入目标（较新者胜、墓碑同迁）、源 key 注册为 alias。之后 A、B 设备各同步一次即可互通；App 推拉经 resolveConfigKey/resolve 自动路由到 canonical，无需清空或重装。

## 回滚

- 数据层：反向合并（把原 target 作为 source 并回）即可撤销，源空间数据从未删除。
- 代码层：revert 本次提交；alias 注册表条目随反向合并清除。

## 状态

- 已完成：代码实现 + 自查 + 任务文档。
- 待办：push → CF 构建验证 → 线上冒烟 → 两台设备各同步一次做端到端确认。
