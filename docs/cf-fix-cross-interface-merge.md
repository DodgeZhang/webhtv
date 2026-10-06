# cf-fix-cross-interface-merge

跨接口地址指纹污染导致的错误自动并入（摸鱼 → 饭太硬）的封堵与修复。

## Recovery anchor

- **目标**：阻断跨接口地址指纹污染使误并不再复发；提供把被误并空间拆回独立身份与独立记录空间的运维能力；在线上完成本次污染的修复。
- **验收标准**：
  1. 一个已绑定设备上报「自己接口 + 另一个已注册接口」的地址指纹时，另一个接口的指纹不会被并入自己的 canonical。
  2. `planAutoMergeGroups` 不再能因设备上报而把两个无关接口折叠。
  3. 线上 `4c1c5c5b`（饭太硬）不再持有摸鱼的 strict/endpoint/legacy 指纹，也不再持有本次探测留下的指纹。
  4. 摸鱼拥有独立 canonical（`b27dbd82`），其别名（`b27dbd82`、`3c264f95`）指向自己。
  5. `4c1c5c5b` 中混入的 6 条摸鱼记录被搬到摸鱼空间，无进度丢失。
- **状态**：代码已完成并通过本地验证（未提交前）；线上修复待 CF 构建完成后执行。
- **改动文件**：
  - `serverless/playback-identity-fixtures/identity.js`（`identityKeyOwner` + `addIdentityKeys` 防护）
  - `serverless/playback-identity-fixtures/identity.test.js`（新增回归用例 + 引入 `identityRegistryKey`）
  - `serverless/webhtv-remote-cloudflare/src/playback-sync.js`（`adminSplitIdentity`、`movePlaybackRows`、op 分发、引入 `normalizeKeyList`）
  - `docs/cf-fix-cross-interface-merge.md`（本文件）
- **回滚锚点**：`ba119a5ab0b023b9cc9946a65a0a7c313b283ecc`（父提交）。
- **下一步**：guard finish 提交 → recovery tag → 推送 main → 等 CF 构建 → 执行线上修复。

## 1. 现象

管理面板显示「摸鱼（新版 interfaceKey）· 2 条 / 28 条」为「已并入 `4c1c5c5b`（饭太硬身份主空间）」，尽管两者 **URL 不同、接口名称不同**。

## 2. 机制（根因）

「已并入」与 URL、接口名称无关，它只是身份注册表 `aliases` 表里的一条记录：

```
b27dbd82-…  →  canonicalInterfaceKey: 4c1c5c5b-…  kind: manual-merge
3c264f95-…  →  canonicalInterfaceKey: 4c1c5c5b-…  kind: interface
```

写入该记录的只有 `mergeIdentityRegistry`（playback-sync.js）。触发它的链条：

1. App 的同步源地址别名 **只追加不清理**（`Config.rememberAddressAliases` / `addLegacyConfigKey`），设备一旦指向过第二个接口，之后每次上报都同时携带两个接口的地址指纹。
2. `addIdentityKeys`（identity.js）把请求携带的**全部**指纹**无条件** push 进当前 canonical，不检查该指纹是否已属于另一个接口。
3. 于是 canonical 与另一个已注册身份**共享了一个指纹**。
4. `groupRegisteredIdentities` / `planAutoMergeGroups`（playback-sync.js）按 **strict / endpoint / legacy** 指纹分组，组内 ≥ 2 个成员就把记录数少的并进记录数多的。
5. `mergeIdentityRegistry` 落库（`kind: 'manual-merge'`），并把源身份 4 个 key list 复制进目标、删除源身份 → 污染扩散。

因此 URL 不同、名称不同照样会被并：判据是「指纹集合有没有交集」，而指纹集合已被污染。

**注意**：`groupRegisteredIdentities` 只使用 strict / endpoint / legacy，**从不使用 host**（避免共用代理域名的无关接口被融合）。`chooseIdentity` 也只在**没有强匹配**时才看 `hostMatchKeys`。所以共享代理 host 指纹留在任一 canonical 都不参与折叠。

## 3. 线上取证（epoch 800，修复前）

- `identities`：`4c1c5c5b`（主）、`69d526ce`、`e2534da7`。
- `4c1c5c5b.strictAddressKeys` = 摸鱼 2 枚 + 饭太硬 2 枚 + 探测 2 枚；
  `endpointMatchKeys` = 摸鱼 2 + 饭太硬 2 + 探测 2；`hostMatchKeys` = 原有 2 + 探测 2；
  `legacyConfigKeys` = 摸鱼 2 + 饭太硬 2 + 探测 3。
- `aliases` 指向 `4c1c5c5b` 的包含：`b27dbd82`(manual-merge)、`d51503ab`(manual-merge)、`b541baa1`(manual-merge)、`19304600`(manual-merge)、`2cd537a8`(interface)、`3c264f95`(interface)、`767e67f2`(interface)、`unbound-device-0001`(auto-fingerprint)。
- 空间列表：`4c1c5c5b 饭太硬 9 条`（其中 **6 条 configName=摸鱼**：星澜 21809、Wogg 130733、wogg 132153、wogg 131902、wogg 132118、wogg 128071）、`d51503ab 饭太硬 3 条`、`3c264f95 摸鱼 2 条`、`b27dbd82 摸鱼 28 条`，另有未注册的 `5c7caca3 / 242fdcb3 / f8909b38 / ab5445c1 / ba4fc89e`。

### 责任区分（如实记录）

- **由本项目诊断探测造成**：`3c264f95` 的 `interface` 并入（探测 rebind）；`b541baa1`、`19304600` 的 `manual-merge` 与 `unbound-device-0001` 垃圾别名；以及 `4c1c5c5b` 中 9 枚探测指纹（`591ca3ca` `a6352e5e` `74efbb8d` `1fa81aee` `cf6bc262` `20ce1895` `91f02c00` `74f69ac6` `9d589c71`）。
- **早于本次探测**：`b27dbd82`、`d51503ab` 的 `manual-merge` 在 epoch 777/785 快照中已存在。其具体触发者（面板「并入…」按钮 or 更早同类污染后的自动折叠）数据上不可区分 —— 两条路径写入的 `kind` 都是 `manual-merge`。
- **教训**：不得再用跨接口指纹组合做线上探测。（已停止。）

## 4. 改动

### 4.1 `addIdentityKeys` 防护（identity.js）

新增 `identityKeyOwner(registry, key)`：alias 优先，否则 key 自身是否为 canonical。`addIdentityKeys` 在 push 前检查：

```js
const owner = identityKeyOwner(registry, key);
if (owner && owner !== identity.canonicalInterfaceKey) continue;
```

**为什么仍然保留真实的合并能力**：

- 设备上报**新的**地址指纹（URL 迁移场景）时该指纹无归属 → 照常并入，单设备 URL 迁移不受影响。
- 面板手动合并走 `mergeIdentityRegistry`，完全不受影响。
- 只有「两个 canonical 已经各自存在且共享同一指纹」这一真正歧义的情形被拒绝 —— 而这正是本次把摸鱼与饭太硬融合的那条路径。

### 4.2 `adminSplitIdentity` 运维接口（playback-sync.js）

入参：`{ op, configType, canonical, from, fingerprints{4 lists}, aliases[], moveSpaces[], moveRows[{from,name}] }`。

行为：把 `fingerprints` 写到新 canonical `canonical` 上并从 `from` 的 4 个 key list（含 `selfLegacyConfigKeys`）中剥离；把 `aliases` 与全部指纹的别名强制改指到 `canonical`；随后按 `moveSpaces`（整空间）与 `moveRows`（按 `configName` 过滤）搬移播放记录。返回 `{created, stripped, repointed, movedRows}` 便于核对。CAS 重试 5 次，失败抛 503。

### 4.3 `movePlaybackRows`（playback-sync.js）

集合式拷贝 + 删除源行（`migrateIdentitySpaces` 是合并原语，刻意保留源空间；拆分必须删除，否则另一接口仍显示已不属于它的记录）。复用既有 `ON CONFLICT … WHERE excluded.updated_at > …` 与 `ROW_NUMBER()` 计数推进 `sequence` 的写法，避免按行循环烧配额。

## 5. 本地验证（Exec 沙箱，实跑）

- `identity.test.js` 全量：**TOTAL pass=8 fail=0 of 8**（此前 7 个用例）。
- 新用例 `never absorbs an address clue another canonical already owns` 是真实回归保护：
  - guard ON（当前代码）→ PASS
  - guard OFF（还原修复前 `addIdentityKeys`）→ FAIL :: `deepEqual ["stricta","strictb"] vs ["stricta"]`
- `playback-sync.js` 语法解析：OK（`AsyncFunction` 解析 2046 行正文；`runAdminSplitIdentity` / `movePlaybackRows` / op 分发均在）。

未做：本地无 node，无法运行 `npm run check` / `node --test`；由 CF 构建与线上复查覆盖。

## 6. 线上修复步骤（本次执行）

1. 推送 main → 等 CF 构建完成 → 确认新代码生效。
2. `adminSplitIdentity`：`canonical=b27dbd82-7b64-480d-a60e-f4a54e83c417`，`from=4c1c5c5b-7dd0-440d-b2c6-0e4863c95972`；
   - `fingerprints.strictAddressKeys` = 摸鱼 2 枚（`df98f2ab…`、`550f0adf…`）
   - `fingerprints.endpointMatchKeys` = 摸鱼 2 枚（`b8ca392d…`、`5921f0b2…`）
   - `fingerprints.hostMatchKeys` = **留空**（共享代理 host，不参与分组，保持在 `4c1c5c5b`）
   - `fingerprints.legacyConfigKeys` = 摸鱼 2 枚（`de872298…`、`44fb6599…`）
   - `aliases` = `b27dbd82-…`、`3c264f95-…`
   - `moveSpaces` = `3c264f95-…`（2 行）
   - `moveRows` = `[{from: 4c1c5c5b-…, name: '摸鱼'}]`（6 行）
3. `adminUnbindIdentity`：`canonical=4c1c5c5b-…`，`keep` = 饭太硬 strict `cc2f3f76` `47e39637`；endpoint `eafd7066` `d5880cad`；host `0e427618` `acfafdd5`；legacy `64e93960` `cb71041e`；别名 `d51503ab` `2cd537a8` `767e67f2`。→ 清掉 9 枚探测指纹与 `b541baa1` `19304600` `unbound-device-0001` 三个垃圾别名。
4. `adminInspectIdentity` + `/api/playback/sync/configs` 复查：`4c1c5c5b` 只含饭太硬指纹、只剩 3 行；`b27dbd82` 为独立 canonical 且含 36 行（28 + 2 + 6）；`3c264f95` 无行且为 `b27dbd82` 别名。
5. 请用户在两台设备重试同步（摸鱼源与饭太硬源各一台）确认。

## 7. 已知风险 / 注意事项

- `adminUnbindIdentity` **无法重建**已被 `mergeIdentityRegistry` 删除的 identity（`b541baa1`、`19304600`）。它们会在下次设备 resolve 时按 URL 指纹自愈重建（其空间本来就没有记录行）。
- `moveRows` 以 `payload.configName` 为唯一线索区分混入行；`movePlaybackRows` 返回的 `movedRows` 必须与预期一致（本次期望 6）后才继续。
- 搬移不带 tombstone。若源空间存在覆盖这些行的删除墓碑，拆分后该条目可能重新出现，可在 App 侧再删一次。本次涉及的 6 行均为现存 item，风险低。
- 修复后 `planAutoMergeGroups` 对**新增**污染不再可能触发；它仍保留用于修复历史遗留状态。
- `d51503ab`（饭太硬 3 条，与 `4c1c5c5b` 中 3 行重复）本次不动，其别名本来就正确指向饭太硬主空间。

## 8. 下一个动作

`guard finish` 提交并打 recovery tag，推送 main；等 CF 构建完成后执行第 6 节的线上修复与复查。
