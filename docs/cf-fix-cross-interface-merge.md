# cf-fix-cross-interface-merge

## Recovery anchor

- 目标：修复“URL 不同、接口名不同的接口产生播放记录后被误并入身份主空间”的服务端缺陷。
- 验收：host 级弱匹配（hostMatchKey）不再被自动 confirm 合并；单一 host 指纹命中不再触发 Pass 1 收养；同接口强匹配自动合并行为保持不变；Pass 2 按名称折叠保持不变。
- 状态：代码修复完成，待部署后线上 curl 验证。
- 下一步：guard finish 提交 → push → CF 部署 → curl 验证 → 复核同 Token（1c4751a4…e05c）残留别名。

## 背景

用户面板截图显示“饭太硬（locale）”等与本接口无关的空间被标为“身份主空间”，图食/王二小/肥猫等空间“已并入 4c1c5c5b”（摸鱼主空间）。Token 全程唯一，说明这些空间曾被错误合并（后被人工拆开）。服务代码曾被其他设备修改更新过（8 个提交引入 resolve 阶段自动 confirm 与 ingest/pull 阶段 autoUnifyIdentity）。

## 根因（两个缺陷 + 一个放大器）

1. **缺陷 A — `resolveWithAutoMerge` 无差别自动 confirm**：官方协议对 `confirm_required` 有意停下等用户确认；`hostMatchKey`（仅域名哈希）命中即弱匹配。自动补发 `confirm: true` 把 host 级碰撞静默合并。多个接口共用同一 gh-proxy 代理域名时 host 哈希相同 → 跨接口合并。
2. **缺陷 B — `autoUnifyIdentity` Pass 1 单指纹收养**：App 端 `rememberAddressAliases`（Config.java L306-314）把 strict+endpoint+host 三层哈希与 URL 哈希混存于 addressMatchAliases，`X-WebHTV-Config-Aliases` 头无类型标签。服务端只要“恰好 1 个指纹命中”就收养 → 仅共享代理域名的无关接口（只有 1 个 host 指纹命中）被并入。该路径在每次 ingest（L210）和 pull（L244）都执行。
3. **放大器 — 空间名不稳定 + Pass 2**：canonical 空间显示名取最新记录 configName；Pass 2 `planNameUnifyGroups` 按名称折叠未注册空间 → 一次误合并后可雪崩放大。

注：`groupRegisteredIdentities`（面板分组）本就排除 host-only，面板分组逻辑无此问题；webhook 不发送 Aliases 头，收养仅发生在带别名的 pull 侧（新版 App）。

## 变更（serverless/webhtv-remote-cloudflare/src/playback-sync.js）

1. `resolveWithAutoMerge`：confirm_required 自动补发 confirm 前检查 `result.body.matchedBy`，仅 `strictAddressKey` / `endpointMatchKey` / `legacyConfigKey` 放行；`hostMatchKey` 原样返回 confirm_required（App 保留自身身份，面板手动并入兜底）。
2. `autoUnifyIdentity` Pass 1：改为按 canonical 统计命中指纹数（`hits` Map，指纹去重），仅当唯一 canonical 命中 ≥2 个不同指纹时收养。同接口设备至少 URL 哈希+host 哈希同时命中（≥2）；仅域名碰撞只有 1 个命中，不再收养。
3. Pass 2 按名称折叠保留不动（此前正确合并过 b27dbd82 老摸鱼空间）。

## 影响面

- 强匹配自动合并（同接口多设备互通，含 has_data 自动 confirm）不受影响。
- pull 指纹重写（d0281a1ee0）、pullRewriteKey 三级选取不受影响。
- 单指纹命中场景从“自动收养”变为“保持独立”：失败模式是少合并（安全侧），需并入时走面板手动。
- 极端场景：canonical 身份只存有 URL 哈希而无 host 键（理论上 addIdentityKeys 总是全存，实际不出现）时同接口也只 1 命中 → 不自动收养，可手动并入。

## 验证

- 部署后 curl：构造仅 hostMatchKey 线索的 identity resolve（host 哈希命中已注册身份、strict/endpoint/legacy 均不命中）→ 断言返回 `confirm_required` + `matchedBy: hostMatchKey`，而非 `merge`/`keep via alias`。
- curl：构造 ≥2 指纹（URL 哈希 + host 哈希）命中同一 canonical 的 resolve（sourceDataState=empty）→ 断言仍 `adopt`/`merge`（同接口互通不回归）。
- 复核线上 spaces：确认无错误别名残留（非摸鱼系 key 指向 4c1c5c5b）。

## 回滚

单文件两处独立修改；`git revert` 本次提交即可整体回滚。Pass 1 误收养产生的 alias 可通过面板/维护端点拆分（迁移为复制不删源，可逆）。

## 续：存量污染清理（cf-clean-identity-pollution，2026-10-06）

新代码部署（4ae61e96）后用户实测：饭太硬播放记录仍落入主空间。线上 `/identity/spaces` 证实根因是**存量污染**而非新合并：

- canonical `4c1c5c5b-7dd0`（6 条，最新记录名"饭太硬"）仍持有王二小/潇洒/肥猫/胖猫/摸鱼(3c264f95) 等别名绑定
- 早前错误合并把无关接口的 interfaceKey、URL/端点/域哈希写进了 canonical 的 aliases 与身份密钥列表
- 设备每次同步都 resolve，服务端对已绑定 key 直接答 "keep via alias"，无需新合并即继续写主空间——代码修复拦不住存量绑定

新增两个 token 鉴权维护 op（`POST /api/playback/sync/maintenance`）：

1. `adminInspectIdentity`：只读导出注册表（identities 全部地址密钥列表 + aliases 全部绑定），可带 `configKey` 附返回该空间原始行。
2. `adminUnbindIdentity`：按 `targets: [{canonical, keep: [key...]}]` 外科摘除——删除指向 canonical 且 key 不在 keep 内的 alias 条目，并从 canonical 身份四个密钥列表剥离不在 keep 内的 key。不动播放行；误摘的设备密钥下次 resolve 自愈（addIdentityKeys 从 URL 重导出）。

清理流程：inspect → 依据摸鱼系 URL 哈希（本地面行计算）+ 保留别名的 legacyConfigKeys 确定 keep 集合 → unbind → spaces 复核分离 → 处理主空间内残留的饭太硬测试行（adminDeleteItem 可用）。
