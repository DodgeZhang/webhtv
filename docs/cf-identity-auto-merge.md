# cf-identity-auto-merge — resolve 触发的服务端懒自动归一

## Recovery anchor

- **目标**：设备 resolve 身份时由 Worker 服务端自动完成同名接口的合并归一，无需用户在面板手动操作；与 `cf-identity-merge-panel` 手动面板共存。
- **验收**：已注册多成员强线索组在 resolve 前被折叠（pass1）；未注册 + 本地有数据 + 强线索唯一命中的 confirm_required 被自动补发 `confirm:true` 完成官方 merge（pass2）；合并失败不阻塞 App 同步。
- **回滚**：把 `src/playback-sync.js` 顶部 `AUTO_MERGE_IDENTITIES` 置 `false`（恢复官方行为，面板不受影响），或整体 revert 本任务提交。
- **状态**：实现与测试就绪（本机无 node，`npm test` 待补跑）；提交/tag 见文末。

## 背景

官方身份协议存在 confirm_required 死锁：App 端 `PlaybackIdentityResolver.request()` 从不发送 `confirm:true`，而已注册身份在 `chooseIdentity`（identity.js L481-486）走 bound→keep 短路。两台设备同一接口各生成独立 key 后永远无法自愈。任务 `cf-identity-merge-panel`（commit `2f6cc090160f`）交付了手动合并面板；本任务把归一下沉到服务端自动执行。

## 设计（两段式懒自动归一）

新逻辑全部放在 WebHTV 适配层（`src/playback-sync.js`），不改官方共享模块 `identity.js`（避免上游合并丢失，media_type 教训）。

1. **pass1 组归一预处理**：resolve 进入时先 `planAutoMergeGroups`（union-find 强线索分组，strict/endpoint/legacy，排除 host 级——cnb.cool/gh-proxy.org 上不同接口会误合并），对成员 ≥2 的组按记录数最多者选 target，逐组 `mergeIdentityRegistry`（CAS 5 轮 + `migrateIdentitySpaces`）。B 类已注册身份经 alias 解析到组目标，keep 短路返回的已是归一后结果。
2. **pass2 confirm_required 自动补发**：官方 resolve 返回 `confirm_required`（A 类未注册 + has_data 场景）时，换 `requestId`（原值 + `-auto` 后缀，避开 `registry.requests` 幂等缓存）补发一次 `confirm:true`，走官方 merge 路径（addAlias + migrateIdentitySpaces，可回滚、不删源）。
3. **尽力而为**：任何一步失败均吞掉异常不阻塞 App 同步，下次 resolve 懒重试；CAS 保证并发 resolve 下归一收敛。

### 边界

- **未注册孤儿空间无法自动归一**（如部署前 28 条老记录 b27dbd82 若未注册）：无注册身份无线索，分组看不到，需面板手动"并入"一次。
- 误合并仅可能来自 SHA-256 线索碰撞或接口 URL 确实相同（后者即正确语义）。
- `AUTO_MERGE_IDENTITIES = true` 为总开关。

## 实施

- `src/playback-sync.js`：
  - 常量 `AUTO_MERGE_IDENTITIES`（L25）；resolve 路由改调 `resolveIdentityWithAutoMerge`（L71）
  - DO 方法 `resolveIdentityWithAutoMerge` / `autoMergeIdentityGroups` / `spaceCounts`（L381-410，SQL IN 动态占位符计数各身份空间记录数）
  - 导出 `planAutoMergeGroups`（L1087）/ `resolveWithAutoMerge`（L1106）
- `test/playback-sync.test.js`：新增 3 用例——组计划目标选择（9 条 key-b 胜出）、confirm_required 自动补发端到端（断言 merge + alias + `req-1-auto` 缓存项）、开关关闭保持 confirm_required。

## 验证

- 本机无 node/npm（PowerShell 环境缺失，已探测 Program Files/fnm/nvm/Volta/scoop/choco/Trae 均无 node.exe）：测试用例静态自查通过，待有 node 环境 `npm test`（serverless/webhtv-remote-cloudflare 目录）补跑。
- push 后 Cloudflare 构建以 esbuild 抓语法/导入错误；线上冒烟：设备 resolve 后 `GET /api/playback/sync/identity/spaces` 应观察到组消失、alias 出现；两台设备各同步一次验证记录互通。

## 使用与回滚

- 无需任何手动操作：设备每次同步/打开 App 触发 resolve 即自动归一。
- 回滚：`AUTO_MERGE_IDENTITIES = false` 立即恢复官方行为（面板与已完成的合并/alias 不受影响）；代码级回滚 revert 本提交；数据级回滚可在面板反向合并。
