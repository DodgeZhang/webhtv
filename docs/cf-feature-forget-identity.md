# cf-feature-forget-identity — 控制台「注销身份」

任务 ID：`cf-feature-forget-identity`
类别：WebHTV 本地运维能力（Cloudflare Worker + Durable Object + Dashboard）
状态：独立按钮版本（`a0b40badbb`）已上线并验收；随后按用户要求把「注销身份」合并进「清除」按钮，见 §9
创建时间：2026-10-06 20:57 +08:00
基线 HEAD：`42eb16580894f14daf01205d382f9f567b5001ea`（§9 的基线为 `9db00db722`）

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

---

## 9. 后续变更 — 把「注销身份」合并进「清除」按钮

基线 HEAD：`9db00db722c22cf4df2b3c79c51deb9c2863bfeb`（任务守卫 id `cf-merge-clear-forget-buttons`）
时间：2026-10-06 21:27 +08:00

### 9.1 用户要求（原文）

- 「那能不能把 清除 和 注销身份合并成一个按钮，已注册的即清除记录也注销身份，未注册的就清除记录。」
- 「登录进接口后的清空全部按钮也一样，清空记录的同时也注销身份。」
- 「在然后就是 并入... 按钮是没显示全还是啥情况，后面有三个点，看着不美观。」

### 9.2 设计决策

| 方案 | 结论 |
|---|---|
| 前端发两次 fetch（先 clear 再 forget） | 不选：两次往返、部分失败无法集中表达、中间态可被看到 |
| 新增 `adminClearSpaceAndForget` op | 不选：「清空全部」也需要同样能力，会产生两个近似 op |
| **在既有 op 上加 opt-in `forgetIdentity` 标志（选定）** | 一套机制同时覆盖「清除」与「清空全部」两条路径；不传标志时既有行为逐字节不变 |

**关键约束（为什么标志由前端按行决定，而不是服务端猜）**：

- **alias 行绝不能注销**：alias 指向的 canonical 才是真正的主空间，注销会把主空间一起带走。所以只有 `cfg.identity === 'canonical'` 的行才置 `forgetIdentity: true`。
- **两表不能同事务**：`playback_items` 与 `playback_identity_registry` 是不同表，无法共享事务。因此组合操作采用「清除先提交，注销失败不回溯」——失败原因写入 `result.forgetError`，前端如实报「记录已清、身份没注销」，而不是谎报完全成功。
- **「清空全部」与「清除」的 key 语义不同**：`adminClearSpace` 不查注册表（你点哪行就清哪行的存储键），`adminClearAll` 会先走身份归一（`X-WebHTV-Config-Key` → canonical）。两条路径的 `forgetIdentity` 因此都是安全的。
- `注销身份` 独立按钮已删除 → `forgetIdentityConfirm` / `doForgetIdentity` 成为死代码，一并删除。

### 9.3 改动清单

`playback-sync.js`：

- 分发行（L502 / L508）：`runAdminClearAll(request, body, configType)`、`runAdminClearSpace(request, body, configType)` —— `adminClearSpace` 由只传 `body` 改为同时传 `request`（注销需要 Token）。
- `runAdminClearAll` / `runAdminClearSpace` 末尾：`if (body && body.forgetIdentity === true) await this.runUnregisterAfterClear(request, configType, configKey, result);`
- `runAdminForgetIdentity` 瘦身为薄包装（校验 `targets` → 调共享 helper）。
- 新增共享 helper `forgetIdentities(request, configType, targets)`：`load → normalize → 删 canonical + 指向它的全部别名 → epoch+1 → compareAndSet`，5 次重试；一个都没命中时**不写库**直接返回（保持幂等语义）。
- 新增 `runUnregisterAfterClear(request, configType, configKey, result)`：`try/catch` 包裹 `forgetIdentities`，成功写 `result.forgotten` / `result.missing`，失败写 `result.forgetError` 且**不抛出**（避免把已提交的记录删除回滚成 5xx）。

`dashboard.js`：

- `renderSpaceRow`：删除独立「注销身份」按钮；清除按钮标签动态化 —— canonical 行显示 `清除并注销`，未注册行显示 `清除`，并用 `title` 说明各自会做什么。
- `clearSpaceConfirm`：标题、正文、确认按钮文字全部按 `forgetIdentity` 分支动态化（canonical 额外说明「注销后这一行消失」「注销可自愈」）。
- 新增 `clearSpaceResultText(data, forgetsIdentity)`：区分并注销成功 / 未注册只清 / 记录已清但注销失败 / 无记录四种文案。
- `confirmClearAll` / `clearAll`：弹窗标题改为「⚠️ 清空全部记录并注销身份」并说明可自愈；请求体加 `forgetIdentity: true`；toast 按 `res.forgetError` 分支。
- 删除死代码 `forgetIdentityConfirm` / `doForgetIdentity`。
- 「并入…」→「并入其他空间」：原来那三个点是**字面 U+2026 省略号字符**（不是 CSS 截断、也不是宽度不足），已替换为完整文字。

### 9.4 验证

**本地（Exec 沙箱，无 node/npm/wrangler）**：全量读入两个源文件后做「模板字面量反转义 → `new Function` 语法解析 + 内容锚点」。

```
frontend 20/20 PASS   (inline script 语法解析 OK, chars=36281; 含死代码已清除、无 U+2026、动态标签存在)
backend  11/11 PASS   (playback-sync.js 全量 2272 行语法解析 OK; 含 dispatch 传 request、opt-in 标志、两个新 helper)
```

**线上验收**：见 §9.5（提交后执行）。

### 9.5 线上验收（待执行）

待提交推送、CF 构建完成后填写。

### 9.6 风险与回滚

| 风险 | 评估 |
|---|---|
| 未传标志时行为改变 | 无。`forgetIdentity === true` 是严格相等判定，不传或传假值与改动前完全一致 |
| 误注销 alias 指向的主空间 | 已规避：前端只在 `identity === 'canonical'` 行置 true；后端 op 仍可单独作为「只清记录」原语使用 |
| 注销失败导致记录删除被回滚 | 无。`runUnregisterAfterClear` 吞掉异常写进 `forgetError`，删除已提交的结果照常返回 |
| 清空全部误伤别的空间 | 「清空全部」的 key 走身份归一，命中的就是 canonical 本身，注销安全 |
| 注销后同步损坏 | 无。identity.js L265-270 会在下次 resolve 自动重建 canonical（同 §3） |

**回滚**：`git revert` 本节 commit 并重新部署；已执行的注销无数据副作用（记录未动），设备重连即自愈。
