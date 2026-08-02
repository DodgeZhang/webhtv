# WebHTV Remote Cloudflare 一键部署教程

本教程指导你从零开始，通过 GitHub + Cloudflare 部署 WebHTV 观影记录同步服务。部署完成后，你将获得一个支持观影进度同步、删除墓碑、增量拉取的云端服务。

---

## 目录

1. [环境准备](#1-环境准备)
2. [GitHub 仓库准备](#2-github-仓库准备)
3. [Cloudflare 账户准备](#3-cloudflare-账户准备)
4. [一键部署（推荐）](#4-一键部署推荐)
5. [手动部署（Wrangler CLI，备选方案）](#5-手动部署wrangler-cli备选方案)
6. [App 端配置](#6-app-端配置)
7. [管理控制台使用](#7-管理控制台使用)
8. [验证部署](#8-验证部署)
9. [常见问题排查](#9-常见问题排查)

---

## 1. 环境准备

### 必需条件

| 条件 | 说明 |
|------|------|
| GitHub 账号 | 用于 Fork 仓库和触发自动部署 |
| Cloudflare 账号 | 免费计划即可，无需绑定信用卡 |
| WebHTV App | 已安装最新版 WebHTV 的 TV 端或手机端 |

### 技术要求

- 本服务**不需要** KV、R2、D1 或外部数据库
- 观影记录由 **Durable Object 内置 SQLite** 持久化
- 免费计划额度：Workers 请求 10 万次/天、Durable Object 读取 10 万次/天

### 本地工具（可选，仅手动部署需要）

- Node.js 18+
- npm 或 pnpm
- Wrangler CLI（`npm install -g wrangler`）

---

## 2. GitHub 仓库准备

### 方式 A：Fork 官方仓库

1. 访问 [https://github.com/fish2018/webhtv](https://github.com/fish2018/webhtv)
2. 点击右上角 **Fork** 按钮，将仓库复制到你的 GitHub 账号下
3. Fork 完成后，你将拥有 `https://github.com/<你的用户名>/webhtv`

### 方式 B：克隆后推送（适合需要自定义的场景）

```bash
git clone https://github.com/fish2018/webhtv.git
cd webhtv
git remote set-url origin https://github.com/<你的用户名>/webhtv.git
git push -u origin main
```

---

## 3. Cloudflare 账户准备

1. 访问 [https://dash.cloudflare.com/sign-up](https://dash.cloudflare.com/sign-up) 注册账户
2. 免费计划即可，无需选择付费方案
3. 登录后进入 Dashboard 主页
4. 记住你的 **Account ID**（右侧边栏 → Account ID），后续部署需要

---

## 4. 一键部署（推荐）

通过 Cloudflare Dashboard 的 **Workers Builds** 功能，全程在浏览器中完成部署，无需安装任何本地工具。

### 步骤 1：Fork 仓库

参见 [第 2 节](#2-github-仓库准备)，确保你的 GitHub 账号下已有 Fork 的 `webhtv` 仓库。

### 步骤 2：在 Cloudflare 创建 Worker 并连接 GitHub

1. 登录 [Cloudflare Dashboard](https://dash.cloudflare.com)
2. 左侧菜单点击 **Workers 和 Pages**
3. 点击 **创建** 按钮
4. 选择 **Workers** 选项卡
5. 点击 **连接到 Git**

### 步骤 3：授权 Cloudflare 访问 GitHub

1. 弹出 GitHub 授权页面，点击 **授权 Cloudflare Workers and Pages**
2. 选择 **Only select repositories**，在列表中搜索并勾选你 Fork 的 `webhtv` 仓库
3. 点击 **安装和授权**
4. 回到 Cloudflare 页面后，在仓库列表中选择 `webhtv`

### 步骤 4：配置构建和部署

在 **构建配置** 页面填写以下信息：

| 配置项 | 填写值 |
|--------|--------|
| **项目名称** | `webhtv-remote-cloudflare`（可自定义） |
| **生产分支** | `main` |
| **根目录（高级）** | `serverless/webhtv-remote-cloudflare` |
| **构建命令** | `npm install` |
| **部署命令** | `npx wrangler deploy` |

> ⚠️ **根目录必须填写** `serverless/webhtv-remote-cloudflare`，这是 Worker 代码所在的子目录。如果不填，Cloudflare 会从仓库根目录构建，导致找不到 `wrangler.toml`。

填写完成后，点击 **保存并部署**。

### 步骤 5：等待构建完成

Cloudflare 会自动执行以下操作：

1. 克隆你的 GitHub 仓库
2. 进入 `serverless/webhtv-remote-cloudflare` 目录
3. 运行 `npm install` 安装依赖
4. 运行 `npx wrangler deploy` 部署 Worker
5. 自动执行 Durable Object 迁移（创建 `RELAY_DO` 和 `PLAYBACK_DO`）

构建过程通常需要 1-2 分钟。在 **部署** 页面可以实时查看构建日志。

部署成功后，你会在页面顶部看到 Worker 地址：

```
https://webhtv-remote-cloudflare.<你的子域名>.workers.dev
```

### 步骤 6：验证 Durable Object 绑定

部署完成后，打开新浏览器标签页，访问以下地址验证 PLAYBACK_DO 是否正确绑定：

```
https://webhtv-remote-cloudflare.<你的子域名>.workers.dev/api/server/capabilities
```

响应中应包含 `"playbackSync": true`，表示观影记录同步已就绪。

如果看到 `"playbackSync": false`，说明 Durable Object 迁移未生效，请检查仓库中 `wrangler.toml` 是否包含 `[[migrations]]` 配置。

### 步骤 7：后续更新（自动）

配置完成后，每次你向 GitHub 仓库的 `main` 分支推送代码，Cloudflare 会**自动触发重新部署**。你也可以在 Workers 和 Pages → 你的项目 → 部署 页面手动点击 **重试部署**。

---

## 5. 手动部署（Wrangler CLI，备选方案）

> 如果第 4 节的网页部署遇到问题，可以使用命令行方式手动部署。此方式需要本地安装 Node.js 和 Wrangler。

### 步骤 1：安装 Wrangler

```bash
npm install -g wrangler
# 或在项目目录内
cd webhtv/serverless/webhtv-remote-cloudflare
npm install
```

### 步骤 2：创建 wrangler.toml

```bash
cp wrangler.toml.example wrangler.toml
```

打开 `wrangler.toml`，确认内容如下：

```toml
name = "webhtv-remote-cloudflare"
main = "src/index.js"
compatibility_date = "2026-07-23"

# 远程托管 Durable Object
[[durable_objects.bindings]]
name = "RELAY_DO"
class_name = "WebHTVRemoteRelayDO"

# 观影记录同步 Durable Object
[[durable_objects.bindings]]
name = "PLAYBACK_DO"
class_name = "WebHTVPlaybackSyncDO"

# 迁移：v1 创建 RELAY_DO，v2 创建 PLAYBACK_DO
# 首次部署时两个迁移都会自动执行
[[migrations]]
tag = "v1"
new_sqlite_classes = ["WebHTVRemoteRelayDO"]

[[migrations]]
tag = "v2"
new_sqlite_classes = ["WebHTVPlaybackSyncDO"]
```

> **重要**：如果你之前已部署过旧版本（只有 RELAY_DO），**不要修改** `v1` migration tag，只需追加 `v2` 迁移即可。

### 步骤 3：登录并部署

```bash
npx wrangler login
npm run deploy
```

### 步骤 4：配置自定义域名（可选）

默认的 `*.workers.dev` 域名可以直接使用。如需自定义域名：

1. 在 Cloudflare Dashboard 中添加你的域名（需将 DNS 迁至 Cloudflare）
2. 进入 Workers & Pages → 你的 Worker → Settings → Triggers
3. 添加 Custom Domain，例如 `webhtv.yourdomain.com`
4. 保存后 Cloudflare 会自动配置 DNS 和 SSL 证书

---

## 6. App 端配置

### 生成 Token

在终端中生成一个安全的随机 Token：

```bash
# Linux / macOS
openssl rand -hex 32

# Windows PowerShell
-join ((48..57)+(97..102) | Get-Random -Count 64 | ForEach-Object {[char]$_})
```

将生成的 Token 保存好，**不要公开**。Token 就是你的用户空间凭证，不同 Token 隔离不同数据。

### 配置远端同步源

在 WebHTV App 中：

1. 进入 **增强功能 → 观影记录同步 → 远端同步**
2. 点击 **新增同步源**
3. 填写：
   - **URL**：`https://<你的 Worker 域名>/api/playback/sync`（完整 API 地址）
   - **Token**：上一步生成的 Token
4. 保存

> ⚠️ **必须填写完整路径** `/api/playback/sync`。App 会直接使用你填写的 URL 发起请求，不会自动拼接路径。如果只填基地址（如 `https://your-worker.workers.dev`），App 会访问根路径收到 HTML 页面，导致 `MalformedJsonException` 报错。

### 配置 Webhook 上报

1. 进入 **增强功能 → 观影记录同步 → Webhook 上报**
2. 点击 **新增端点**
3. 填写：
   - **URL**：与远端同步源**完全相同**的完整 API 地址（含 `/api/playback/sync`）
   - **Token**：与远端同步源**完全相同**的 Token
   - **字段预设**：选择「基础」「标准」或「完整」（匿名预设不支持）
4. 保存

### 多设备同步

在其他设备上填写**相同的 URL 和 Token**，即进入同一个用户空间。不同用户应使用不同 Token。

---

## 7. 管理控制台使用

部署后，直接在浏览器中访问你的 Worker 域名即可打开管理控制台：

```
https://<你的 Worker 域名>/
```

### 首次登录

控制台会显示登录表单，填写三项信息：

| 字段 | 说明 |
|------|------|
| **Worker 地址** | `https://your-worker.workers.dev`（不含 /api 路径） |
| **Token** | 你生成的访问令牌 |
| **Config Key** | App 中点播接口的 configKey（小写） |

凭证会保存在浏览器 localStorage 中，下次访问自动连接。

### 功能说明

- **统计面板**：显示活跃记录数、删除墓碑数、同步游标、保留天数
- **记录列表**：展示当前 configKey 下所有观影进度，支持搜索和分页
- **删除记录**：点击单条记录的 🗑️ 按钮发送删除墓碑
- **清空全部**：发送 `scope=all` 删除墓碑，清空当前 configKey 下所有记录

> **注意**：控制台按 configKey 隔离数据。切换不同点播接口时，需要重新填写对应的 configKey。

---

## 8. 验证部署

### 快速验证（curl）

```bash
# 1. 健康检查
curl https://<你的 Worker 域名>/api/health
# 期望: {"ok":true,"time":...}

# 2. 服务器能力
curl https://<你的 Worker 域名>/api/server/capabilities
# 期望: capabilities.playbackSync = true

# 3. 写入测试进度
curl -X POST 'https://<你的 Worker 域名>/api/playback/sync' \
  -H 'Content-Type: application/json' \
  -H 'X-WebHTV-Token: <你的 token>' \
  -H 'X-WebHTV-Config-Key: <你的 configKey>' \
  -d '{
    "event": "playback.progress",
    "eventId": "verify-1",
    "timestamp": 1781170000000,
    "historyKey": "site@@@vod@@@1",
    "siteKey": "site",
    "vodId": "vod",
    "vodName": "验证影片",
    "episodeName": "第1集",
    "positionMs": 60000,
    "durationMs": 600000
  }'
# 期望: {"ok":true,"received":1,"applied":1,...}

# 4. 拉取增量
curl 'https://<你的 Worker 域名>/api/playback/sync?since=0&limit=100' \
  -H 'X-WebHTV-Token: <你的 token>' \
  -H 'X-WebHTV-Config-Key: <你的 configKey>'
# 期望: {"changes":[...],"nextSince":"1","hasMore":false}

# 5. 查看状态
curl 'https://<你的 Worker 域名>/api/playback/sync/status' \
  -H 'X-WebHTV-Token: <你的 token>' \
  -H 'X-WebHTV-Config-Key: <你的 configKey>'
# 期望: {"ok":true,"items":1,"tombstones":0,"nextSince":"1",...}
```

### Python 测试脚本

使用项目自带的测试脚本进行全面验证：

```bash
cd webhtv/serverless/webhtv-remote-cloudflare

# CLI 模式（config-key 可直接填点播接口 URL，自动计算 SHA-256）
python test_sync.py \
  --url https://<你的 Worker 域名> \
  --token <你的 token> \
  --config-key <你的 configKey 或点播接口 URL>
```

测试脚本会运行 9 项检查：网络连通性、健康检查、服务器能力、同步状态、写入进度、拉取增量、删除墓碑、批量写入、认证验证，并在末尾输出诊断报告。

GUI 模式（直接运行，弹出图形界面输入配置）：

```bash
python test_sync.py
```

---

## 9. 常见问题排查

### Q1: 部署后 playbackSync 为 false

**原因**：PLAYBACK_DO Durable Object 未正确绑定。

**解决**：

1. 检查 `wrangler.toml` 是否包含 `PLAYBACK_DO` 绑定和 `v2` 迁移
2. 确认 `new_sqlite_classes = ["WebHTVPlaybackSyncDO"]` 拼写正确
3. 重新部署：`npm run deploy`
4. 如果之前部署过旧版本且修改了 `v1` tag，需要删除 Worker 后重新创建

### Q2: 401 Missing X-WebHTV-Token

**原因**：请求未携带 Token 或 Token 为空。

**解决**：

- Token 由用户自行生成，**不写入** Worker 环境变量
- 确认 App/Webhook 配置中的 Token 与你生成的完全一致
- Token 通过 `X-WebHTV-Token` 请求头传递，不是 URL 参数

### Q3: 400 Missing X-WebHTV-Config-Key

**原因**：请求未携带 configKey。

**解决**：

- configKey 是点播接口的唯一标识，必须通过 `X-WebHTV-Config-Key` 头传递
- App 端会自动携带此头，手动测试时需要手动添加
- configKey 不区分大小写，服务端会自动转为小写

### Q4: 400 configKey does not match X-WebHTV-Config-Key

**原因**：请求体中的 configKey 与请求头不一致。

**解决**：

- 请求头和请求体中的 configKey 必须一致
- 或仅在请求头中提供 configKey，请求体中省略该字段

### Q5: 400 scope=all must be explicit

**原因**：删除请求未指定 scope=all 但又缺少条目标识。

**解决**：

- 删除单条：提供 `historyKey` 或 `siteKey + vodId`，scope 自动推断为 `item`
- 删除整个站点：提供 `siteKey` 并设 `scope: "site"`
- 清空全部：必须**显式**设 `scope: "all"`，不能省略

### Q6: Worker 部署后访问返回 403 或 Error 1010

**原因**：Cloudflare WAF/Bot Fight Mode 拦截了请求。Bot Fight Mode 会拦截所有非浏览器 User-Agent（如 Python-urllib、curl 默认 UA），返回 `error code: 1010`。

**判断方法**：用浏览器直接访问 `/api/health`，如果返回 `{"ok":true}` 但 curl/Python 返回 403+1010，即可确认。

**解决**：

1. 进入 Cloudflare Dashboard → 选择你的域名 → 安全性 → 自动程序
2. 关闭 **Bot 战斗模式（Bot Fight Mode）**
3. 如使用自定义域名，进入 安全性 → WAF → 自定义规则
4. 创建放行规则：
   - 表达式：`(http.host eq "你的域名" and starts_with(http.request.uri.path, "/api/"))`
   - 操作：跳过（Skip）→ 勾选所有 WAF 检查
5. 部署规则后立即生效

> **注意**：App 端使用自定义 User-Agent（OkHttp），一般不会触发 Bot Fight Mode。但测试脚本和 curl 需要设置浏览器 UA 或关闭 Bot Fight Mode。

### Q7: 迁移失败 "cannot modify migration tag"

**原因**：修改了已发布的 migration tag。

**解决**：

- **永远不要修改**已发布过的 migration tag
- 新增功能时只追加新 tag（如 `v2`、`v3`）
- 如果必须重置，需要删除整个 Worker 后重新创建（会丢失数据）

### Q8: 免费额度用尽

Cloudflare 免费计划限额：

| 资源 | 免费额度 | 重置周期 |
|------|---------|---------|
| Workers 请求 | 100,000 次/天 | UTC 0 点（北京时间 8:00） |
| Durable Object 请求 | 100,000 次/天 | UTC 0 点 |
| Durable Object 持续时间 | 400,000 GB·秒/天 | UTC 0 点 |

**优化建议**：

- TV 端上报间隔设为 ≥ 30 秒
- 使用批量接口减少请求次数
- 拉取间隔设为 ≥ 60 秒
- 如额度不足，升级至 Workers Paid（$5/月，额度大幅提升）

### Q9: 多设备数据不同步

**排查步骤**：

1. 确认所有设备使用**相同的 URL 和 Token**
2. 确认 App 自动携带了 `X-WebHTV-Config-Key` 头
3. 不同点播接口的 configKey 不同，数据按 configKey 隔离
4. 使用管理控制台查看当前 configKey 下的记录数
5. 使用 `GET /api/playback/sync/status` 检查 nextSince 游标

### Q10: 数据丢失

**重要**：Durable Object 的 SQLite 存储是持久的，但以下情况可能导致数据不可用：

- 删除并重建 Worker（迁移 tag 变化会创建新的 DO 实例）
- 长时间不访问（Cloudflare 可能回收闲置 DO，但数据保留）
- 删除墓碑超过 90 天会被清理（墓碑本身，不影响已删除的数据）

**备份建议**：定期使用 `GET /api/playback/sync?since=0&limit=1000` 拉取全量数据导出。

---

## 附录：API 速查表

| 方法 | 路径 | 用途 | 必需请求头 |
|------|------|------|-----------|
| GET | `/api/health` | 健康检查 | 无 |
| GET | `/api/server/capabilities` | 服务器能力 | 无 |
| GET | `/` | 管理控制台 | 无 |
| GET | `/api/playback/sync` | 拉取增量 | Token, Config-Key |
| GET | `/api/playback/sync/status` | 查看状态 | Token, Config-Key |
| POST | `/api/playback/sync` | 写入/删除 | Token, Config-Key |

### 拉取增量请求头

| 请求头 | 说明 | 默认值 |
|--------|------|--------|
| `X-WebHTV-Since` | 游标 (上次拉取的 nextSince) | 0 |
| `X-WebHTV-Limit` | 单次拉取上限 | 100 (最大 1000) |

### 写入进度请求体

```json
{
  "event": "playback.progress",
  "eventId": "唯一事件ID",
  "timestamp": 1781170000000,
  "historyKey": "site@@@vod@@@1",
  "siteKey": "site",
  "vodId": "vod",
  "vodName": "影片名",
  "episodeName": "第1集",
  "positionMs": 120000,
  "durationMs": 600000
}
```

### 删除墓碑请求体

```json
{
  "event": "playback.deleted",
  "eventId": "唯一事件ID",
  "scope": "item",
  "historyKey": "site@@@vod@@@1",
  "siteKey": "site",
  "vodId": "vod",
  "deletedAt": 1781170005000
}
```

| scope | 必需字段 | 说明 |
|-------|---------|------|
| `item` | historyKey 或 siteKey+vodId | 删除单条记录 |
| `site` | siteKey | 删除整个站点的记录 |
| `all` | 无 (清空当前 configKey) | 必须显式指定 |

### 批量写入

POST 请求体直接发送数组即可，最多 100 条：

```json
[
  { "event": "playback.progress", "eventId": "batch-1", ... },
  { "event": "playback.deleted", "eventId": "batch-2", "scope": "item", ... }
]
```
