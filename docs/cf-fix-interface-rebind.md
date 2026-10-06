# cf-fix-interface-rebind — 设备切换接口后同步 409 死锁修复

> Recovery anchor
> - 目标：设备 interfaceKey 绑定旧接口后切换到另一接口时，`/api/playback/identity/resolve` 不再返回硬 409 conflict，而是安全 rebind 到新接口 canonical。
> - 验收：① 两台设备切到饭太硬后同步不再报 HTTP 409；② 饭太硬记录仍归饭太硬 canonical；③ 摸鱼空间不混入饭太硬数据；④ 多目标强匹配仍 409（防歧义）。
> - 状态：逻辑验证已通过（Exec V8 模拟 7 步场景全部符合预期）；待提交推送部署与线上验证。
> - 变更文件：`serverless/playback-identity-fixtures/identity.js`、`serverless/playback-identity-fixtures/identity.test.js`（本文件）。
> - 回滚：`git revert` 该提交或重置至 base HEAD `0bcf65c7c7`；identity.js 的 rebind 分支无持久化副作用（解绑/改绑仅在 compareAndSet 成功时落库）。

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

## 5. 提交与发布

- guard：`cf-fix-interface-rebind`（standard），scope=docs + serverless/playback-identity-fixtures，base HEAD `0bcf65c7c7`。
- 提交后推送 main 触发 CF 自动构建（语法门禁），部署约 2-4 分钟。
