# cf-fix-interface-rebind — 设备切换接口后同步 409 死锁修复

> Recovery anchor
> - 目标：设备 interfaceKey 绑定某接口后，其同步源地址指纹（App 从不清理）跨多个已注册 canonical 时，`/api/playback/identity/resolve` 与 `/api/playback/sync` 都不再返回硬 409，而是认请求者自身的绑定。
> - 验收：① 两台设备切到饭太硬后同步不再报 HTTP 409；② 强指纹只命中"其他"多个 canonical 时仍 409（保留歧义防护）；③ 无任何绑定的 key 跨多 canonical 仍 409。
> - 状态：**阶段一（rebind）已提交部署但未解决用户 409**；阶段二/三修复已完成代码与回归用例，待 guard 会话提交 + 推送部署 + 线上复测（见 §6）。
> - 变更文件：`serverless/playback-identity-fixtures/identity.js`、`serverless/playback-identity-fixtures/identity.test.js`（本文件）。
> - 回滚：`git revert` 对应提交；`chooseIdentity`/`resolveConfigKey` 的改动均无持久化副作用。
> - 下一步：清理由本会话失败 `start` 产生的 `.codex/task-state/current` 残片后开启 guard 会话 → `finish` 提交并打 recovery tag → 推送 main → 线上复测 R5/R6/S1/S2。

## 1. 现象

- 两台设备切换到饭太硬接口播放并产生记录，管理页可见两台记录（已合并进饭太硬 canonical）。
- 登录页"查询已有接口"只显示一条饭太硬记录（身份合并后接口去重）。
- App 同步报 HTTP 409：`{"action":"conflict","error":"Submitted interface is bound to a different identity","matchedBy":"strictAddressKey","candidates":["d51503ab-..."]}`。

## 2. 根因

设备 interfaceKey 此前绑定（alias）到摸鱼 canonical `4c1c5c5b`。用户切换到饭太硬后，resolve 请求携带饭太硬 URL 强指纹（strict/endpoint/legacy），命中饭太硬 canonical `d51503ab`：

- `chooseIdentity` bound 分支：`bound=4c1c5c5b`，`strong.set={d51503ab}`，`!strong.set.has(bound)` → 原逻辑一律返回 `conflict` → HTTP 409。
- 官方协议把"已绑定设备命中不同 identity"视为硬冲突（防误配置），但对"设备切接口"场景是死锁：设备永远无法改绑，同步被阻塞。

在线复现（2026-10-06）：用摸鱼绑定身份 `2cd537a8`、`3c264f95` 加饭太硬 strong keys 调 resolve → 均返回 409 与上述 error，与用户现象一致。

## 3. 修复（identity.js）

### 3.1 `chooseIdentity` bound 分支新增 `rebind` 动作（L512-528）

仅当 **强匹配唯一指向另一 canonical** 且 **设备 interfaceKey 本身不是 canonical identity（只是 alias）** 时返回 `rebind`；其余（多目标强匹配、设备自身是 canonical）保持 `conflict` 409：

```js
if (strong.set.size === 1 && !registry.identities[input.interfaceKey]) {
  return { action: 'rebind', canonicalInterfaceKey: [...strong.set][0], matchedBy: strong.matchedBy, matchedKeys: strong.keys };
}
return { action: 'conflict', ... };
```

安全性：
- 只认强 URL 匹配（strict/endpoint/legacy），不涉及 host 弱匹配 → 不会重开上一修复（commit f548a45642）堵住的"共享代理域名误合并"。
- 多目标仍硬冲突 → 歧义场景不被静默改绑。

### 3.2 `resolveIdentity` 处理 rebind（L258-264, L274-276, L295-297）

- 落库前 `delete next.aliases[input.interfaceKey]` 解绑旧别名，随后 `addAlias` 改绑新 canonical（复用 adopt/merge/rebind 分支）。
- 迁移源 `migrationSourceKeys` 在 rebind 时排除 `input.interfaceKey`，仅用 `legacyConfigKeys` → 不会把旧接口（摸鱼）空间数据搬入新接口（饭太硬）空间。
- rebind 不经过 confirm_required，直接 200 返回 action=rebind。

### 3.3 连带收益

rebind 后设备 alias 指向新 canonical，pull 路径 `resolveConfigKey`（identity.js L328-346）原先"configKey+aliases 命中 ≥2 canonical 抛 409"的多归属冲突随之解除。

## 4. 验证

### 4.1 逻辑验证（code_mode Exec，V8 隔离模拟，sha256 替换为确定性 FNV-1a）

7 步场景输出，全部符合预期：

```
create-a:status=200:action=create:canonical=a
adopt-b:status=200:action=adopt:canonical=a
create-c:status=200:action=create:canonical=c
rebind-b:status=200:action=rebind:canonical=c   ← 原先此处是 409 conflict
keep-b2:status=200:action=keep:canonical=c      ← rebind 后保持绑定
conflict-b:status=409:action=conflict            ← 多目标仍硬冲突
keep-a:status=200:action=keep:canonical=a        ← canonical 自身不受影响
aliases: b → c（已改绑）; identities: a, c（b 仍是 alias，未成 canonical）
```

### 4.2 回归用例（identity.test.js）

- `rebinds a device key when its strong keys now match a different canonical`
- `keeps a hard conflict when a bound device matches multiple canonicals`

### 4.3 线上验证（部署后）

- 请用户在两台饭太硬设备重试同步（不再 409）。
- `POST /api/playback/sync/identity/spaces`（adminInspectIdentity）复核：原摸鱼绑定设备 alias 改指 `d51503ab`；摸鱼空间数据未混入饭太硬空间。

## 5. 提交与发布（阶段一，已完成）

- guard：`cf-fix-interface-rebind`（standard），scope=docs + serverless/playback-identity-fixtures，base HEAD `0bcf65c7c7`。
- 提交后推送 main 触发 CF 自动构建（语法门禁），部署约 2-4 分钟。
- **阶段一结论：不充分**。部署成功后用户两台设备重试同步仍报 HTTP 409，说明真实触发条件不是 §2 描述的"绑定旧接口 + 强指纹命中另一个 canonical"，而是 §6 的情形。

## 6. 阶段二/三 — 真实根因（已在线复现）

### 6.1 取证方法

无 node/python，PowerShell 长命令受 ExecutionPolicy 限制（改用 Git bash 脚本）。全部为对线上 Worker 的只读/幂等探测：

- `POST /api/playback/sync/maintenance {op:"adminInspectIdentity",configType:"vod"}` → 转储完整 identity/alias 注册表。
- `GET /api/playback/sync/configs` → 各空间条目数（即登录页"查询已有接口"看到的内容）。
- 逐因子探测：按设备真实指纹集合构造请求，观察状态码。

### 6.2 关键在线事实（2026-10-06，epoch 777）

- 设备 key：`3c264f95-…`、`2cd537a8-…`；两者 alias 均指向 canonical `4c1c5c5b-…`。
- `4c1c5c5b` 的 identity 里同时含有摸鱼与饭太硬两个接口的全部 strict/endpoint/legacy 指纹；`d51503ab-…` 已变成 kind=`manual-merge` 的 alias → `4c1c5c5b`（即饭太硬曾被人工并入摸鱼空间）。
- 空间条目：`4c1c5c5b` 9 条（名为饭太硬）、`d51503ab` 3 条（饭太硬）、`3c264f95` 2 条（摸鱼）、`b27dbd82` 28 条（摸鱼）。
- 所有"单 canonical"组合的探测（含累积 14 个指纹的完整集合）全部 200 —— 说明 409 与别名数量无关。

### 6.3 复现（决定性）

| 探测 | 请求 | 结果 |
| --- | --- | --- |
| R5 | resolve，key=`3c264f95`，strict=[摸鱼 strict, `b541baa1` strict] | **409** `action=conflict` |
| R6 | resolve，key=`3c264f95`，strict=[摸鱼 strict, `19304600` strict] | **409** `action=conflict` |
| R7 | resolve，key=`3c264f95`，strict=[摸鱼 strict] | 200 `action=keep` → `4c1c5c5b` |
| R8 | resolve，key=`3c264f95`，strict=[`b541baa1` strict] | 200 `action=rebind` → `b541baa1` |
| S1 | GET sync，key=`3c264f95`，aliases=[摸鱼 strict, `b541baa1` strict] | **409** |
| S2 | GET sync，key=`3c264f95`，aliases=[摸鱼 strict, `19304600` strict] | **409** |

### 6.4 根因（两个 409 出口同源）

1. `chooseIdentity` bound 分支先判 `strong.set.size > 1` 才判 `has(bound)`：

```js
if (strong && (strong.set.size > 1 || !strong.set.has(bound))) { … conflict … }
```

设备的**自身绑定 `4c1c5c5b` 就在强匹配集合里**，却因为集合 size>1 直接判为硬冲突。App 侧 `Config.rememberAddressAliases` / `addLegacyConfigKey` 只追加不清理，同步源每次改指接口都会把旧接口的 strict/endpoint/legacy 指纹永久留下，因此只要该设备历史上碰过第二个已注册接口，resolve 就永久 409 → sync 永久 409（`拉取 0·处理 0·失败 0`）。

2. `resolveConfigKey`：`candidates.size > 1` 时无条件下抛 `409 Identity alias maps to multiple canonical interfaces`，同样忽略"请求者自身 key 的绑定就在候选中"。

两个出口都违背同一原则：**请求者自身声明的绑定只要在匹配集合内，就不是歧义**。

### 6.5 修复

- `chooseIdentity`（identity.js L524-547）：bound 分支改为先判 `!strong.set.has(bound)`——绑定命中集合即 `keep`；绑定不在集合内才按"唯一强匹配→rebind / 其它→conflict"处理。
- `resolveConfigKey`（identity.js L340-356）：`candidates.size > 1` 时先取请求者自身 key 的绑定（`identities[raw]` 或 `aliases[raw].canonicalInterfaceKey`）并返回；无绑定才 409。
- 保留的防护：强匹配只指向"其它"多个 canonical，或 key 自身无任何绑定 → 仍 409（`19304600`/`b541baa1` 等跨接口误配不会被静默改绑）。

### 6.6 回归用例（identity.test.js）

- `keeps a bound device on its own canonical when its accumulated keys span several`（绑定 ∈ 强匹配集合 → keep）。
- `keeps a hard conflict when a bound device matches canonicals other than its own`（绑定 ∉ 集合且多命中 → 409）。
- `routes sync to the requesting key binding when aliases span several canonicals`（有绑定 → 返回自身绑定；无绑定 → 409）。
- 阶段一原用例 `keeps a hard conflict when a bound device matches multiple canonicals` 因语义被本次修复取代而重写。

本地执行验证（2026-10-06，Exec V8 隔离沙箱；本机无 node，注入 `TextEncoder`/`URL`/`Headers`/`crypto.subtle` 垫片后直接加载 `identity.js` 与 `identity.test.js`，哈希用确定性替身、仅比较同异）：`TOTAL pass=7 fail=0`。另按线上复现形状复跑得到 `R5_shape=200/keep/a`、`S1_shape=a`、`S1b_unbound=reject/409`、`AMBIG_unbound=409/conflict`、`REAL_SWITCH=200/rebind/c`、`AFTER_SWITCH=200/keep/c`——即 §6.3 中 R5/R6/S1/S2 的 409 形状在修复后转为 200，同时保留无绑定 key 跨 canonical 的 409 歧义防护。

### 6.7 待办 / 已知风险

- 线上复测：部署后重跑 R5/R6/S1/S2，期望全部 200（keep / 返回 `4c1c5c5b`）。
- `keep` 时 `addIdentityKeys` 仍会把请求里的全部指纹并入该 canonical，这正是 `4c1c5c5b` 同时持有两接口指纹的机制；本修复不改变该行为（改它风险更大），仅在文档记录。
- 数据侧遗留：`d51503ab` 仍有 3 条饭太硬记录未并入 `4c1c5c5b`（人工并入发生在这些行写入之前）。属于数据治理问题，不在本次代码修复范围，需要用户确认后再处理。
- 设备静态代码不可作为依据：`PlaybackRemoteSyncer.java` / `PlaybackProgressWriter.java` 在 main 上仍带未解决的冲突标记（无法编译），设备 APK 另有构建源，故请求头组合只能由线上注册表反推。
