# cf-feature-forget-identity — 控制台「注销身份」

任务 ID：`cf-feature-forget-identity`
类别：WebHTV 本地运维能力（Cloudflare Worker + Durable Object + Dashboard）
状态：实现完成，本地验证通过；待提交/推送/线上验收
创建时间：2026-10-06 20:57 +08:00
基线 HEAD：`42eb16580894f14daf01205d382f9f567b5001ea`

## 1. 目标与完成句

**完成句**：新增 `adminForgetIdentity` 维护操作与 Dashboard「注销身份」按钮，从身份注册表中真正删除指定 canonical 条目（连带指向它的别名），使空身份条目从列表消失。

**用户问题**：`adminClearSpace`（见 [cf-feature-clear-space-button.md](cf-feature-clear-space-button.md)）只能清播放记录。身份主空间 `69d526ce`、`e2534da7`、虎斑 `f0de0fdc` 的记录数为 0，清除返回 `deletedRows: 0`，条目仍留在列表里。

**根因**：`listIdentitySpaces` 会刻意列出「已注册但 0 条记录」的 canonical，注释即写明 *"List registered canonical identities that hold no rows yet so they can still be picked as a merge target."* —— 列表里的这类条目是**身份注册表**内容，不是播放记录，所以清记录清不掉。

**允许路径**：
- `serverless/webhtv-remote-cloudflare/src/playback-sync.js`
- `serverless/webhtv-remote-cloudflare/src/dashboard.js`
- `docs/cf-feature-forget-identity.md`

受保护脏文件：无。

## 2. 用户决策

| 议题 | 用户选择 |
|---|---|
| 三个 0 条已注册身份怎么处理 | 加「注销身份」——从身份注册表里真正删掉该 canonical（连带别名），列表随之消失 |

## 3. 设计研究

本改动是 WebHTV 本地运维操作，不改变同步协议响应形状、不改变播放行为、不引入新依赖。按 AGENTS.md 第 7 节，评估过各证据类是否适用：

| 证据类 | 是否适用 | 说明 |
|---|---|---|
| 上游源码/commit/test | 适用 | 仓库内即可取证：[identity.js](file:///d:/交投资料/相关文档/07-Git文件/Github/WebHTV/webhtv/serverless/playback-identity-fixtures/identity.js) 是身份协议权威参考 |
| 官方规范/平台文档 | 不适用 | Cloudflare Durable Object storage API 用法在既有代码中已定型，无新 API |
| 上游 PR/issue/revert | 不适用 | 非上游合并候选，是本地新增运维端点 |
| 成熟同类项目 | 不适用 | 身份注册表为 WebHTV 自有结构 |
| 论文/技术博客/benchmark | 不适用 | 无性能或算法议题 |

**本地代码取证**（实际读取，非检索片段）：

1. `identity.js` L265-270 —— resolve 时对不存在的 canonical **自动重建**：
   `let identity = next.identities[resolution.canonicalInterfaceKey]; if (!identity) { identity = normalizeIdentity({}, ...); next.identities[...] = identity; }`
   → 注销是**自愈**的：仍在用该接口的设备下次 resolve 会重新注册。最坏情况是条目重现，不会损坏同步。

2. `normalizeIdentityRegistry`（identity.js L207-231）会自动剔除孤儿别名：
   `if (!canonical || !registry.identities[canonical]) delete registry.aliases[key]`
   → 注销时必须**同时**删掉指向该 canonical 的别名，否则留下悬挂别名。

3. `mergeIdentityRegistry`（playback-sync.js L1923-1987）——既有 registry 改法范例（写 `registry.epoch += 1`、CAS 保存）。

4. `runAdminUnbindIdentity`（playback-sync.js L706-750）——既有「手术式解绑」先例：同样的 `load → normalize → 改 → compareAndSet` 5 次重试循环，同样的 `runMaintenance` 分发型。本 op 沿用同一风格。**区别**：unbind 只删别名 + 剥离地址键，**保留** canonical 本体；forget 删的正是 canonical 本体。

5. `identityStore(request)`（playback-sync.js L1101-1124）——DO 自实现的 SQLite 版 `load` / `compareAndSet`。

**对齐的比较与决策**：

| 方案 | 结论 |
|---|---|
| 不做变更，只清记录 | 不可行：0 条身份条目本就没有记录可清 |
| 复用 `adminUnbindIdentity` | 不可行：它保留 canonical，条目不会消失 |
| 复用 `adminClearAll` | 不可行：它先走 `resolveConfigKey` 身份归一，会把操作打到别名指向的真实主空间上 |
| **新增 `adminForgetIdentity`（选定）** | 语义精确、只动身份注册表、自我可逆（设备重连即重建） |

## 4. 改动清单

### 4.1 `playback-sync.js`

- `runMaintenance` 分发行（L505）：
  `if (op === 'adminForgetIdentity') return this.runAdminForgetIdentity(request, body, configType);`
- 新增 `runAdminForgetIdentity(request, body, configType)`（L765-800，36 行）。要点：
  - 只带 Token（`/maintenance` 端点本身只校验 Token，无需 `X-WebHTV-Config-Key`）。
  - `targets` 经 `normalizeOptionalKey` 校验去重；传别名 key 也能工作（先解析成它的 canonical）。
  - 删 `registry.identities[canonical]`，再遍历删掉所有 `alias.canonicalInterfaceKey === canonical` 的别名。
  - 一个都没删（全 missing）时**不写库**，直接返回 `{forgotten:[], missing:[...]}`。
  - 写库走 `registry.epoch += 1` / `updatedAt` / `compareAndSet`，5 次重试后 503。
  - **不触碰 `playback_items`**，也不写墓碑。

### 4.2 `dashboard.js`

- `renderSpaceRow` 新增「注销身份」按钮（L624-634），仅 `cfg.identity === 'canonical'` 时渲染；`onclick` 先 `stopPropagation()` 再调 `forgetIdentityConfirm(cfg)`。
- 新增 `forgetIdentityConfirm(cfg)`（L841-879）：模态二次确认，列出接口名与条数，按 `items` 分支说明后果（有记录 → 变「未注册」但仍可见；0 条 → 条目消失），并提示设备仍在用会重新注册，显示 configKey。
- 新增 `doForgetIdentity(cfg)`（L881-906）：`POST {baseUrl}/api/playback/sync/maintenance`，body `{op:'adminForgetIdentity', configType, targets:[cfg.configKey]}`，只带 `X-WebHTV-Token`，成功后 toast + `findConfigs()`。

## 5. 验证

### 5.1 本地（Exec 沙箱语法解析 + 内容锚点）

无 node/npm/wrangler，采用沙箱 `new Function` 语法解析。注意 dashboard.js 内前端代码位于 `DASHBOARD_HTML` 模板字面量中，解析前必须反转义（否则 `.replace(/\\/+$/,'')` 会被当成正则/除法歧义）。

结果：

```
dispatch: OK
backendMethod: OK (36 lines)
backendNoTemplateLeak: OK
function forgetIdentityConfirm: OK (39 lines)
async function doForgetIdentity: OK (26 lines)
buttonGate: OK
requestBody: OK
tokenOnly: OK
```

`tokenOnly` 另做精确复核：`doForgetIdentity` 函数体内含 `X-WebHTV-Token`、不含 `X-WebHTV-Config-Key`。

### 5.2 线上验收（已执行，全部通过）

提交 `a0b40badbba242a2c4f15183958595e33f1a45e3`，tag `recovery/cf-feature-forget-identity/20261006125804-a0b40badbba2`，已推送 `origin/main`。

**（1）op 就绪探测**——无 `targets` 调用，不产生任何写入：

- 部署中就绪：`HTTP 400 {"ok":false,"error":"adminForgetIdentity requires targets"}`
  （若未部署会返回 `Unknown maintenance op`，故该响应即证明新 op 已上线）

**（2）真实注销**——对用户指定的三个 0 条身份执行：

```
POST /api/playback/sync/maintenance
{"op":"adminForgetIdentity","configType":"vod","targets":[
  "f0de0fdc-b6cd-4690-9d52-e0f5824256d2",   // 虎斑
  "69d526ce-9fd7-4f84-a91b-72f761bc114e",
  "e2534da7-7f77-4ec7-a4fa-d9df0c61c267"]}
→ HTTP 200 {"ok":true,"forgotten":[三个 key],"missing":[]}
```

`/api/playback/sync/identity/spaces` 前后对比，条目数 **9 → 6**，三个目标全部消失：

| 接口 | 注销前 | 注销后 |
|---|---|---|
| 摸鱼 `b27dbd82` | canonical, 32 条 | 保留 |
| 饭太硬 `4c1c5c5b` | canonical, 4 条 | 保留 |
| 潇洒 `242fdcb3` | unregistered, 8 条 | 保留 |
| 肥猫 `f8909b38` | unregistered, 1 条 | 保留 |
| 胖猫 `ab5445c1` | unregistered, 10 条 | 保留 |
| 豆儿 `ba4fc89e` | unregistered, 1 条 | 保留 |
| 虎斑 `f0de0fdc` | canonical, 0 条 | **消失** |
| `69d526ce` | canonical, 0 条 | **消失** |
| `e2534da7` | canonical, 0 条 | **消失** |

**幂等复测**——同请求重放：`HTTP 200 {"ok":true,"forgotten":[],"missing":[三个 key]}`。一个都没删到时不写库，符合 §4.1 设计。

**（3）浏览器验证**（真实页面 https://webhtv-remote.dodge.cc.cd/ ，6 行全部渲染）：

| 检查 | 结果 | 证据 |
|---|---|---|
| A 按钮渲染范围 | PASS | 摸鱼 / 饭太硬 行按钮为 `["并入…","注销身份","清除"]`；潇洒 / 肥猫 / 胖猫 / 豆儿 行为 `["并入…","清除"]`，无「注销身份」 |
| B 二次确认弹窗 | PASS | 标题「⚠️ 注销该身份注册」，正文含「摸鱼」「32 条」 |
| C 取消即关闭 | PASS | 点「取消」后弹窗关闭，列表仍 6 行、名称与条数不变 |
| D 控制台 | PASS | 无任何 console 消息 |

验收全程未点击「确认注销」（该按钮会真实改动线上数据）。

**（4）验收中发现的一个坑**：首次浏览器验证报告「按钮在所有行都缺失」，但服务端直接拉取 HTML 已确认包含 `注销身份` / `forgetIdentityConfirm` / `adminForgetIdentity`，且响应头为 `Cache-Control: no-store`。原因是浏览器标签页持有旧文档。用 `?cachebust=<随机数>` 强制缓存失效重载后，`typeof forgetIdentityConfirm` 返回 `function`，全部检查通过。**结论：验证此类前端改动前必须先确认页面加载的是新文档，否则会得到假阴性。**

## 6. 已知坑

- **Exec 沙箱 `tools.Read` 返回 `{content:"..."}` 而非字符串**，每行带 `数字\t` 前缀；直接当字符串用会得到 `[object Object]`，所有内容锚点误判 miss。
- **模板字面量反转义**：必须先 `\\` → 占位符 → `` \` `` → `` \${ `` → 还原 `\`，再解析，否则假报 `Unexpected token ','`。
- **验证窗口越界假失败**：按 offset/limit 读窗口做「函数体内不含 X」这类否定断言时，窗口若越过函数结尾会把后面的代码算进来。本次 `tokenOnly` 首轮 FAIL 即由此产生（窗口延伸到了 `authHeaders()` 里的 `X-WebHTV-Config-Key`），收窄到函数本体后 OK。

## 7. 风险与回滚

| 风险 | 评估 |
|---|---|
| 注销后同步损坏 | 无。设备下次 resolve 由 identity.js L265-270 自动重建 canonical，最坏是条目重现 |
| 播放记录丢失 | 无。本 op 不触碰 `playback_items` |
| 误删他人身份 | 需 Token 鉴权；按 canonical 精确删除；非 canonical 行不显示按钮 |
| 留下悬挂别名 | 已规避：删除 canonical 的同时删掉所有指向它的别名 |
| 并发写冲突 | `compareAndSet` 5 次重试，失败返回 503 |

**回滚**：`git revert` 对应 commit，重新部署即可；已执行的注销无数据副作用（记录未动），受影响的只是身份注册表，设备重连即自愈。

## 8. 状态与下一动作

**状态：完成。** 代码提交 `a0b40badbb` + tag `recovery/cf-feature-forget-identity/20261006125804-a0b40badbba2` 已推送 `origin/main`，CF 已部署，本地验证（§5.1）与线上验收（§5.2）全部通过，用户指定的三个 0 条身份已从列表消失。

**下一动作：无。** 等待用户反馈。
