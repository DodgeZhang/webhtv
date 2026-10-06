# cf-feature-clear-space-button — 登录页接口列表「清除」按钮

## 1. 目标

登录页「🔍 查询已有接口（App 上报的 configKey）」列出的每个空间行，除了现有「并入…」之外，再提供一个「清除」按钮，用于清空该接口空间下的全部播放记录，把不再使用的历史接口从列表里清掉。

完成句：给列表每一行加一个「清除」按钮，二次确认后按该行自己的存储键清空其全部记录并写入删除墓碑，随后刷新列表。

## 2. 验收标准

1. 列表每一行（身份主空间 / 已并入 / 未注册）都显示「清除」按钮。
2. 点击后弹出确认框，写明接口名、记录条数、不可恢复、会下发删除指令；「取消」不产生任何请求。
3. 确认后 POST `/api/playback/sync/maintenance`（`op: 'adminClearSpace'`，仅带 `X-WebHTV-Token`），按该行自己的存储键精确清理。
4. 清理后该空间记录数归零，列表自动刷新；未注册空间直接从列表消失。
5. 空间原本没有记录时不写任何墓碑，返回 `deletedRows: 0`。
6. 不影响其他设备：清理会写入 `scope: 'all'` 删除墓碑，仍在使用该接口的设备下次同步一并删除。

## 3. 用户决策（AskUserQuestion，2026-10-06）

| 决策点 | 选择 |
| --- | --- |
| 清除粒度 | 清空整个接口空间（每行一个「清除」） |
| 是否下发设备 | 要，设备下次同步一起删（写 `scope: 'all'` 墓碑） |
| 可清范围 | 全部行都可清（身份主空间 / 已并入 / 未注册） |
| 批量 | 先只做单行清除，不做勾选批量 |

## 4. 关键设计决策：为什么新增 `adminClearSpace` 而不复用 `adminClearAll`

`runAdminClearAll`（[playback-sync.js](../../serverless/webhtv-remote-cloudflare/src/playback-sync.js)）的执行链是：

```
requireConfigKey(request, body)                     // 需要 X-WebHTV-Config-Key 或 body.configKey
  -> resolvePlaybackConfigKey(...)                   // 走身份注册表做归一解析
    -> scopedConfigKey(configType, configKey)
```

也就是说它会先把提交的 key 经身份注册表解析。列表里「已并入 ✗✗✗」那一行是**别名**，解析结果会指向真实主空间；直接复用会导致「点 A 行、清掉 B 空间的数据」这种误删。列表页又只有 Token、没有 `X-WebHTV-Config-Key`，与这个链路的假设也不一致。

因此新增 `adminClearSpace`：

- 只用 `validatedConfigKey()` 归一（trim + 小写 + 长度校验），**完全不查身份注册表**；
- 作用对象就是 `scopedConfigKey(configType, configKey)`，即面板上那一行显示的空间键 —— 所见即所清；
- 删除与写墓碑在同一 `transactionSync` 内完成；
- **仅当实际删除了行才写墓碑**：一个本来就没有记录的空间（例如已注册但无记录的身份主空间，或手滑打错的 key）不应该留下删除标记。

## 5. 改动清单

### serverless/webhtv-remote-cloudflare/src/playback-sync.js

1. `runMaintenance` 分发新增一行：
   `if (op === 'adminClearSpace') return this.runAdminClearSpace(body, configType);`
2. 新增 `runAdminClearSpace(body, configType)`，紧跟在 `runAdminClearAll` 之后。

### serverless/webhtv-remote-cloudflare/src/dashboard.js

3. `renderSpaceRow(cfg)` 在「并入…」之后追加「清除」按钮（`btn btn-sm btn-danger`），`onclick` 先 `stopPropagation()` 再调用 `clearSpaceConfirm(cfg)`，避免触发行本身的「填入并连接」。
4. 新增 `clearSpaceConfirm(cfg)`：模态确认，列出接口名与条数；身份主空间额外提示「设备仍在用则可能重新推回」；「取消」直接 `hideModal()`。
5. 新增 `doClearSpace(cfg)`：请求只带 `X-WebHTV-Token`，成功后 `showToast` 并 `findConfigs()` 刷新。

## 6. 本地验证

环境无 node/npx/wrangler，采用沙箱语法解析（`new Function`）+ 内容锚点定位，避开按行号分块读取的边界错位问题。

| 检查项 | 结果 |
| --- | --- |
| `runAdminClearSpace` 作为类成员解析 | OK（21 行） |
| `renderSpaceRow` 解析（含新增按钮） | OK（51 行） |
| `clearSpaceConfirm` + `doClearSpace` 解析 | OK（64 行） |
| `runMaintenance` 分发行存在 | OK |
| 新增代码未引入反引号 / `${`（模板字面量安全） | OK |

注意：`dashboard.js` 整体位于 `const DASHBOARD_HTML = \`...\`` 模板字面量内，源码里的 `\\` 在浏览器中才会变成单个 `\`。把原文当独立 JS 解析会误报
`Unexpected token ','`（`.replace(/\\/+$/, '')`），必须先按模板字面量规则反转义再解析。该写法在原有 `findConfigs` / `doMerge` 中已存在，不是本次引入。

## 7. 风险与回滚

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 误清仍在使用的接口 | 清除会写 `scope:'all'` 墓碑，设备下次同步一并删除 | 二次确认弹窗写明后果；身份主空间额外警告；不做批量 |
| 清理身份主空间后记录被推回 | 设备仍绑定该身份时会重新上报 | 弹窗已提示；如需彻底停用需另做「注销身份」，本次不在范围 |
| 已注册身份清空后仍留在列表 | `listIdentitySpaces` 会把 0 条记录的注册身份列为「· 0 条」以便作为合并目标 | 已知行为，未改动 |
| 回滚 | 纯新增 op + 纯新增前端函数，不改动任何既有分支 | `git revert` 本次提交即可 |

## 8. 下一个动作

推送 `main` 触发 Cloudflare 自动部署，然后：

1. 线上冒烟：用不存在的 configKey 调 `adminClearSpace`，期望 `{"ok":true,"deletedRows":0,"propagated":false}`，且不产生墓碑（因为 0 行不写标记，无副作用）。
2. 浏览器验证：打开控制台，点「查询已有接口」，确认每行出现「清除」按钮、确认弹窗可开可关。
3. 经用户确认后再对一个真实废弃空间执行一次清除，作为端到端验收。
