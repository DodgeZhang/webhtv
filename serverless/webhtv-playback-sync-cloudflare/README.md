# WebHTV 观影记录同步 Cloudflare Worker

> 本文档为 [WebHTV](https://github.com/liu673cn/WebHTV) 项目提供基于 **Cloudflare Workers + KV 存储** 的观影记录同步服务端部署方案。

> ☁️ **推荐部署方式**：[Cloudflare Connect to Git](#-cloudflare-connect-to-git-推荐) — 在 Cloudflare Dashboard 点击几下即可完成，之后每次 `git push` 自动部署到 Cloudflare Workers。

## 目录

- [功能概述](#功能概述)
- [工作原理](#工作原理)
- [前置准备](#前置准备)
- [☁️ Cloudflare Connect to Git（推荐）](#-cloudflare-connect-to-git推荐)
  - [📋 快速概览](#-快速概览)
  - [步骤 1：创建 KV 命名空间](#步骤-1创建-kv-命名空间)
  - [步骤 2：配置 wrangler.toml 并推送到 GitHub](#步骤-2配置-wranglertoml-并推送到-github)
  - [步骤 3：在 Cloudflare Dashboard 连接 GitHub 部署](#步骤-3在-cloudflare-dashboard-连接-github-部署)
  - [步骤 4：配置环境变量（ACCESS_TOKEN 等）](#步骤-4配置环境变量access_token-等)
  - [步骤 5：推送代码并自动部署 ✅](#步骤-5推送代码并自动部署-)
  - [后续更新 Worker](#后续更新-worker)
  - [更换 GitHub 仓库或分支](#更换-github-仓库或分支)
  - [故障排查](#故障排查)
- [API 接口说明](#api-接口说明)
- [App 端配置指南](#app-端配置指南)
- [进阶配置](#进阶配置)
- [常见问题排查](#常见问题排查)
- [Cloudflare 免费额度说明](#cloudflare-免费额度说明)

---

## 功能概述

本 Worker 为 WebHTV App 提供**观影记录（播放进度）同步中心**服务：

- 📥 **Webhook 推送接收**：任何 WebHTV 设备在播放时自动将观影记录推送到此服务
- 📤 **远端同步拉取**：其他设备从此服务拉取观影记录，实现多设备进度同步
- 🔄 **双向同步**：支持 Webhook 推送 + 远端拉取两种模式可独立或组合使用
- 🖥️ **Web 管理控制台**：通过浏览器访问 Worker URL 即可直观管理数据，查看统计、浏览记录、删除条目
- 🔒 **Token 鉴权**：可选 `ACCESS_TOKEN` 保护，防止未授权访问
- 🧹 **自动清理**：基于时间戳自动过期，避免存储无限增长
- 📊 **去重合并**：基于 `dedupeKey` 实现幂等写入，新记录覆盖旧记录

## 工作原理

```
┌─────────────┐   Webhook POST    ┌──────────────────┐   单键 JSON   ┌─────────────┐
│  WebHTV TV   │ ──────────────► │  Cloudflare       │ ───────────► │  KV:        │
│  (设备 A)    │  /api/playback/ │  Worker           │  存储所有记录 │  all_records│
└─────────────┘  webhook         │                  │              └─────────────┘
                                 │                  │                    ▲
┌─────────────┐   Remote Sync    │                  │                    │
│  WebHTV 手机 │ ◄────────────── │                  │ ◄─────────────────┘
│  (设备 B)    │  GET /api/      └──────────────────┘  读取后过滤/排序
└─────────────┘  playback/records
```

1. **设备 A（TV）** 播放节目时，通过 Webhook 将播放进度推送到 Worker
2. Worker 将所有记录存储在 KV 的一个 JSON 数组中（键名 `all_records`），以 `dedupeKey` 做去重合并
3. **设备 B（手机/平板）** 定期（或启动时）通过远端同步拉取 Worker 中的观影记录
4. Worker 读取 KV 单键数据 → 内存中过滤/排序 → 返回 JSON 数组
5. App 解析后写入本地数据库

## 前置准备

| 要求 | 说明 |
| --- | --- |
| Cloudflare 账号 | [免费注册](https://dash.cloudflare.com/sign-up) |
| GitHub 账号 | 需要将代码推送到 GitHub 仓库 |
| 本项目代码 | Worker 代码在 `serverless/webhtv-playback-sync-cloudflare/` 子目录 |
| 代码已推送到 GitHub | 你需要把 WebHTV 项目推送到 GitHub 仓库 |

> 💡 使用 **Cloudflare Connect to Git** 方式时，**无需** 在本地安装 Node.js 或 Wrangler CLI，所有构建和部署都由 Cloudflare 自动完成。

## ☁️ Cloudflare Connect to Git（推荐）

> 这是 Cloudflare 原生的一键部署方式——在 Dashboard 页面点击 **Continue with GitHub** 连接仓库，之后每次 `git push` 都会自动触发部署，**全程无需本地 Node.js 或 Wrangler CLI 环境**。

### 📋 快速概览

整个流程只需 5 步，约 5 分钟完成：

| 步骤 | 操作 | 说明 |
| --- | --- | --- |
| ① | 创建 KV 命名空间 | 在 Cloudflare Dashboard 获取 32 位 Namespace ID |
| ② | 配置 `wrangler.toml` | 将 KV ID 写入配置文件，推送到 GitHub |
| ③ | 连接 GitHub 部署 | Dashboard → Create application → Continue with GitHub |
| ④ | 设置环境变量 | 在 Dashboard 中加密配置 `ACCESS_TOKEN` |
| ⑤ | 完成 ✅ | 之后每次 `git push` 自动部署 |

---

### 步骤 1：创建 KV 命名空间

Worker 使用 Cloudflare KV 存储观影记录。需要先在 Dashboard 创建 KV 命名空间并获取 ID：

1. 登录 [Cloudflare Dashboard](https://dash.cloudflare.com)
2. 左侧菜单 → **Workers & Pages**
3. 点击 **KV** 标签页（或在 Workers 页面点击 "Manage R2, D1, KV, Queues..." 中的 KV）
4. 点击 **Create a namespace**
5. 填写名称：`PLAYBACK_KV` → 点击 **Create**
6. 创建成功后，**复制 Namespace ID**（32 位十六进制字符串，类似 `abc123def456...`）

> ⚠️ **重要**：请妥善保存这个 ID，下一步需要填入 `wrangler.toml`。如果丢失，可在 KV 页面重新查看。

### 步骤 2：配置 wrangler.toml 并推送到 GitHub

将上一步获取的 KV Namespace ID 填入 `wrangler.toml` 并推送到 GitHub：

1. 打开项目中的 `wrangler.toml` 文件
2. 将 `REPLACE_WITH_YOUR_KV_NAMESPACE_ID` 替换为实际的 32 位 ID：

```toml
name = "webhtv-playback-sync"
main = "src/index.js"
compatibility_date = "2024-12-01"

kv_namespaces = [
  { binding = "PLAYBACK_KV", id = "your-kv-namespace-id-here" }
]

[vars]
# ACCESS_TOKEN 留空，后面在 Cloudflare Dashboard 中加密配置
ACCESS_TOKEN = ""

MAX_ITEMS = "1000"
RETENTION_DAYS = "90"
MAX_RECORDS = "500"
```

1. 保存文件并提交到 GitHub：

```bash
git add serverless/webhtv-playback-sync-cloudflare/wrangler.toml
git commit -m "feat: 配置 KV namespace ID"
git push origin main
```

> ⚠️ **安全提示**：`ACCESS_TOKEN` **不要**写入 `wrangler.toml`。敏感变量统一在 Cloudflare Dashboard 的 **Variables and secrets** 中配置，加密存储且不会暴露到仓库。

### 步骤 3：在 Cloudflare Dashboard 连接 GitHub 部署

这是核心步骤——在 Cloudflare Dashboard 中通过 "Continue with GitHub" 连接你的仓库：

1. 登录 [Cloudflare Dashboard](https://dash.cloudflare.com)
2. 左侧菜单 → **Workers & Pages**
3. 点击右上角 **Create application**
4. 你会看到两个选项：
  - **Workers**：创建 Worker（本项目使用此方式）
  - **Pages**：创建 Pages 站点
5. 选择 **Workers** 标签页 → 点击 **Create Worker**
6. 在创建页面中，你会看到 **"Continue with GitHub"** 按钮，点击它
7. 在弹出的 GitHub 授权页面中：
  - **选择你的 WebHTV 仓库**（在下拉菜单中找到 `你的用户名/WebHTV`）
  - **Root directory** 设置为 `serverless/webhtv-playback-sync-cloudflare`
> 💡 这是关键一步！因为 Worker 代码在仓库子目录中。如果代码在根目录则留空。
- **Framework preset** 选择 **None**（这是纯 Worker，无框架）
  - **Build command** 留空（Worker 不需要构建步骤）
  - **Output directory** 留空
1. 点击 **Deploy** 按钮
2. 等待几秒（通常 10-30 秒），部署完成后会自动跳转到 Worker 页面

> 💡 如果部署失败，Cloudflare 会显示错误日志。常见原因是 Root directory 设置错误或 `wrangler.toml` 中 KV ID 为占位符。

### 步骤 4：配置环境变量（ACCESS_TOKEN 等）

Worker 部署完成后，需要配置 `ACCESS_TOKEN` 等敏感变量。**不要**将这些值写入 `wrangler.toml`（会暴露到 GitHub），而是在 Cloudflare Dashboard 中配置：

1. 打开刚创建的 Worker 页面
2. 点击 **Settings** 标签页
3. 左侧菜单 → **Variables and secrets**
4. 点击 **Add variable**，添加以下变量：

| Variable name | Value | Encrypt | 说明 |
| --- | --- | --- | --- |
| `ACCESS_TOKEN` | 你的自定义密钥 | ✅ 勾选 | 访问令牌，App 端配置时使用 |
| `MAX_ITEMS` | `1000` | 否 | 单次查询最大返回条数 |
| `RETENTION_DAYS` | `90` | 否 | 数据保留天数 |
| `MAX_RECORDS` | `500` | 否 | 最大存储记录数 |

> 💡 **生成随机密钥**：在本地终端运行 `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"` 可生成 64 位随机十六进制字符串。
> 
> 💡 **没有 Node.js？** 也可以在网上搜索 "random hex generator" 生成随机字符串，或使用密码管理器生成。

### 步骤 5：推送代码并自动部署 ✅

现在，当你推送 Worker 相关代码到 GitHub 时，Cloudflare 会自动拉取最新代码并部署：

```bash
# 修改 Worker 代码后
git add serverless/webhtv-playback-sync-cloudflare/
git commit -m "fix: 修复观影记录同步 bug"
git push origin main
```

推送完成后：
1. Cloudflare 检测到 GitHub 仓库有新 commit
2. 自动拉取代码（使用 Root directory `serverless/webhtv-playback-sync-cloudflare`）
3. 读取 `wrangler.toml` 配置（KV 绑定、环境变量等）
4. 自动部署 Worker
5. 通常 30 秒到 2 分钟完成

你可以在 Cloudflare Dashboard → Worker → **Deployments** 标签页查看部署历史和状态。

---

### 后续更新 Worker

只需修改 `serverless/webhtv-playback-sync-cloudflare/` 下的代码并 `git push`，Cloudflare 自动重新部署，**无需任何额外操作**。

如需重新配置 KV 绑定或环境变量，直接在 Cloudflare Dashboard 修改即可，无需重新连接 GitHub。

如需手动触发部署：在 Worker → Settings → Git → 点击 **"Force deploy"** 按钮。

### 更换 GitHub 仓库或分支

如果需要更换仓库或分支：
1. Worker → Settings → Git → 点击 **"Disconnect"** 断开当前连接
2. 重新点击 **"Connect to Git"** 选择新仓库或分支

---

## 🖥️ Web 管理控制台

部署完成后，你可以直接通过浏览器访问 Worker 根路径，使用内置的 Web 管理控制台：

### 访问地址

```
https://你的-worker-subdomain.workers.dev/
```

或直接访问：`https://你的-worker-subdomain.workers.dev/admin`

### 功能特性

| 功能 | 说明 |
| --- | --- |
| 📊 **数据概览** | 显示总记录数、站点数量、设备数量、数据保留天数等统计信息 |
| 📋 **记录列表** | 分页浏览所有观影记录，显示影片名、站点、进度、观看状态、更新时间 |
| 🔍 **搜索过滤** | 按影片名、站点名快速搜索记录 |
| 🗑️ **单条删除** | 点击记录右侧删除按钮即可删除单条记录 |
| 💥 **批量清空** | 一键清空全部观影记录（带二次确认） |
| 🔄 **自动刷新** | 每 30 秒自动刷新数据，也可手动点击刷新按钮 |
| 📱 **响应式设计** | 支持桌面、平板、手机等多种设备 |

### 安全说明

- 如果你配置了 `ACCESS_TOKEN`，管理页面会自动嵌入 token，无需额外登录
- 建议在 **Cloudflare Dashboard → Worker → Settings → Triggers → Custom Domains** 绑定自定义域名，通过 HTTPS 加密传输
- 管理页面的所有操作（删除、清空）都会经过 token 鉴权

> 💡 管理页面内置统计 API：`GET /api/stats`，返回总记录数、站点数、客户端数等统计数据。支持 `?token=xxx` 查询参数鉴权。

---

### 故障排查

| 错误/问题 | 原因 | 解决 |
| --- | --- | --- |
| `No module named` / `Build failed` | Root directory 未正确设置 | 在 Worker → Settings → Git → 重新设置 Root directory 为 `serverless/webhtv-playback-sync-cloudflare` |
| `KV storage not configured` | wrangler.toml 中 KV ID 错误或 KV 不存在 | 检查 ID 是否为 32 位十六进制，确认 KV namespace 在 Dashboard 中存在 |
| Worker 部署成功但无法访问 | 首次部署绑定未完全生效 | 等待 30 秒后刷新 Worker 页面 |
| `Invalid token` | 未在 Dashboard 配置 ACCESS_TOKEN 或值不匹配 | 到 Settings → Variables and secrets 添加或修改变量值 |
| 推送代码后未自动部署 | GitHub 连接断开 | 在 Worker → Settings → Git → 重新连接 |
| `wrangler.toml` 中的 KV ID 为占位符 | 未替换为真实 ID | 替换后 commit 并 push |
| 部署成功但 API 返回 500 | KV 绑定问题或代码错误 | 查看 Worker → Logs 标签页的实时日志 |
| GitHub 授权失败 | 权限问题 | 在 GitHub Settings → Applications → 撤销 Cloudflare 授权后重试 |

---

## API 接口说明

### 接收观影记录（Webhook 推送）

`POST /api/playback/webhook`

**请求头：**
```
Content-Type: application/json
X-WebHTV-Token: <你的访问令牌>        # 如果配置了 ACCESS_TOKEN
X-WebHTV-Webhook-Id: <事件 ID>         # 可选，用于幂等
X-WebHTV-Dedupe-Key: <去重键>         # 可选
X-WebHTV-Config-Key: <接口 Key>        # 可选
X-WebHTV-Config-Name: <接口名称>       # 可选
```

**请求体：**
```json
{
  "schema": "webhtv.playback.v1",
  "event": "progress",
  "eventId": "uuid-string",
  "timestamp": 1722000000000,
  "sessionId": "session-001",
  "dedupeKey": "sha256-hash-of-content",
  "cid": 1,
  "configKey": "default",
  "configName": "默认接口",
  "historyKey": "csp/movie_001/1",
  "siteKey": "csp",
  "siteName": "示例站点",
  "vodId": "movie_001",
  "vodName": "流浪地球2",
  "vodPic": "https://example.com/poster.jpg",
  "flag": "4K",
  "episodeName": "正片",
  "episodeUrl": "https://example.com/play.mp4",
  "episodeIndex": 1,
  "state": "playing",
  "positionMs": 3600000,
  "durationMs": 5400000,
  "progress": 0.6667,
  "speed": 1.25,
  "completed": false,
  "appVersion": "1.0.0",
  "client": "tv",
  "clientKey": "sha256-of-uuid"
}
```

**响应：**
```json
{
  "ok": true,
  "result": {
    "ok": true,
    "skipped": false,
    "dedupeKey": "sha256-hash-of-content",
    "historyKey": "csp/movie_001/1"
  }
}
```

> 💡 此接口兼容 `POST /api/playback/progress`，两个地址功能完全相同。

### 批量接收观影记录

`POST /api/playback/progress/batch`

**请求体：** 直接传 JSON 数组或带 `items`/`data`/`records` 包装的对象。

```json
{
  "items": [
    { "dedupeKey": "key1", "siteKey": "csp", "vodId": "v1", "vodName": "电影1", "episodeName": "正片", "positionMs": 1000, "durationMs": 2000, "timestamp": 1722000000000 },
    { "dedupeKey": "key2", "siteKey": "csp", "vodId": "v2", "vodName": "电影2", "episodeName": "正片", "positionMs": 1000, "durationMs": 2000, "timestamp": 1722000000001 }
  ]
}
```

**响应：**
```json
{
  "ok": true,
  "total": 2,
  "applied": 2,
  "skipped": 0,
  "failed": 0,
  "results": [...]
}
```

### 查询观影记录（远端同步拉取）

`GET /api/playback/records`

**请求头：**
```
X-WebHTV-Token: <你的访问令牌>
X-WebHTV-Config-Key: <接口 Key>      # 可选
X-WebHTV-Config-Name: <接口名称>      # 可选
```

**查询参数：**
| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `maxItems` | int | 否 | 返回最大条数，默认 1000，最大 2000 |
| `siteKey` | string | 否 | 按站点过滤 |
| `configKey` | string | 否 | 按接口 Key 过滤 |
| `vodId` | string | 否 | 按视频 ID 过滤 |

**响应：**
```json
{
  "ok": true,
  "total": 42,
  "items": [
    {
      "historyKey": "csp/movie_001/1",
      "siteKey": "csp",
      "vodId": "movie_001",
      "vodName": "流浪地球2",
      "vodPic": "https://example.com/poster.jpg",
      "flag": "4K",
      "episodeName": "正片",
      "episodeUrl": "https://example.com/play.mp4",
      "positionMs": 3600000,
      "durationMs": 5400000,
      "progress": 0.6667,
      "speed": 1.25,
      "completed": false,
      "updatedAt": 1722000000000,
      "cid": 1,
      "configKey": "default",
      "configName": "默认接口",
      "configUrl": "",
      "clientKey": "sha256-of-uuid"
    }
  ],
  "server": {
    "serverMode": "cloudflare",
    "serverName": "WebHTV Playback Sync Worker"
  }
}
```

> 💡 此接口兼容 `GET /api/playback/progress`。返回的 JSON 数组可直接被 WebHTV App 的 `PlaybackProgressInput.listFromJson()` 解析。

### 删除观影记录

`DELETE /api/playback/progress` 或 `POST /api/playback/progress/delete`

**请求体（按 dedupeKey 删除）：**
```json
{
  "dedupeKey": "sha256-hash-to-delete"
}
```

**请求体（按 historyKey 删除）：**
```json
{
  "historyKey": "csp/movie_001/1"
}
```

**请求体（按 siteKey + vodId 批量删除）：**
```json
{
  "siteKey": "csp",
  "vodId": "movie_001"
}
```

**请求体（按站点全量删除）：**
```json
{
  "siteKey": "csp",
  "scope": "site",
  "confirm": true
}
```

**请求体（全部清空）：**
```json
{
  "confirm": true,
  "scope": "all"
}
```

**响应：**
```json
{
  "ok": true,
  "deleted": 5
}
```

### 健康检查和能力查询

```
GET /api/health
→ { "ok": true, "time": 1722000000000 }

GET /api/server/capabilities
→ {
    "ok": true,
    "serverMode": "cloudflare",
    "serverName": "WebHTV Playback Sync Worker",
    "time": 1722000000000,
    "capabilities": { ... },
    "kvBound": true
  }
```

### 数据统计

```
GET /api/stats
→ {
    "ok": true,
    "totalRecords": 156,
    "uniqueSites": 5,
    "uniqueClients": 3,
    "retentionDays": 90,
    "oldestRecordAt": 1720000000000,
    "newestRecordAt": 1722000000000,
    "kvBound": true,
    "serverMode": "cloudflare",
    "serverName": "WebHTV Playback Sync Worker"
  }
```

支持 `?token=xxx` 查询参数鉴权（当 `ACCESS_TOKEN` 已配置时）。

### Web 管理页面

```
GET / 或 GET /admin
→ 返回内置的 Web 管理控制台 HTML 页面
```

当 `ACCESS_TOKEN` 已配置时，页面会自动嵌入 token，所有 API 调用自动鉴权。

---

## App 端配置指南

### 配置 Webhook 推送

1. 打开 WebHTV App → **设置** → **观影记录同步**
2. 开启 **观影记录同步** 开关
3. 找到 **Webhook 推送** 区域 → 点击 **添加**
4. 填写配置：
  - **名称**：随意，如 `Cloudflare 同步`
  - **URL**：`https://你的-worker-subdomain.workers.dev/api/playback/webhook`
  - **Token**：填入在 Cloudflare Dashboard 设置的 `ACCESS_TOKEN`
  - **事件**：勾选 `progress`、`ended`（建议全选）
  - **站点过滤**：留空表示同步所有站点
5. 保存后，播放节目时会自动推送记录到 Worker

### 配置远端同步拉取

1. 打开 WebHTV App → **设置** → **观影记录同步**
2. 找到 **远端同步** 区域 → 点击 **添加**
3. 填写配置：
  - **名称**：随意，如 `家庭影院同步`
  - **URL**：`https://你的-worker-subdomain.workers.dev/api/playback/records`
  - **Token**：填入在 Cloudflare Dashboard 设置的 `ACCESS_TOKEN`
  - **站点过滤**：留空表示同步所有站点
  - **接口过滤**：可选，按 configKey 过滤
  - **启动时同步**：建议开启
  - **定时同步间隔**：建议设为 30-60 分钟
  - **最大记录数**：100-500
4. 保存后，App 会在启动时和定时拉取 Worker 中的观影记录

### 两种模式对比

| 特性 | Webhook 推送 | 远端同步拉取 |
| --- | --- | --- |
| 数据流向 | App → Worker | Worker → App |
| 实时性 | 播放时即刻推送 | 定时/启动时拉取 |
| 适用场景 | 将本机记录备份到云端 | 从云端恢复/同步到本机 |
| 推荐策略 | TV 端开启推送 | 手机/平板开启拉取 |
| 带宽消耗 | 低（仅事件推送） | 中（批量拉取） |
| 依赖 | Worker 可访问 | Worker 可访问 |

**最佳实践**：
- TV 设备：**开启 Webhook 推送**（播放时自动上报）
- 手机设备：**开启远端同步拉取**（消费 TV 推送的记录）
- 两台设备都开：实现双向闭环同步

---

## 进阶配置

### 访问令牌保护

在 Cloudflare Dashboard → Worker → Settings → Variables and secrets 中添加加密变量 `ACCESS_TOKEN`。

启用后，所有 API 请求都需携带 `X-WebHTV-Token` 头：

```bash
curl -H "X-WebHTV-Token: my-secret-token-12345" https://your-worker.workers.dev/api/playback/records
```

> ⚠️ **强烈建议生产环境一定设置 ACCESS_TOKEN**，否则任何人都可以读写你的观影记录。

### 数据保留策略

通过 `RETENTION_DAYS` 环境变量控制数据保留天数（在 Dashboard Variables 中设置）。

Worker 会根据记录的 `timestamp`/`updatedAt` 字段自动过滤过期数据。

### 限流与安全

Cloudflare Workers 自带基础 DDoS 防护。如需更严格的限流：

1. 在 Cloudflare Dashboard 中为 Worker 配置 **Rate Limiting**
2. 启用 **WAF**（Web Application Firewall）规则
3. 使用 Cloudflare Access 限制特定 IP 访问

### 绑定自定义域名（解决 workers.dev 访问问题）

如果你的 TV 无法访问 `*.workers.dev` 域名（国内部分 ISP 可能限制），绑定自定义域名是最佳解决方案：

1. **准备一个已解析的域名**（例如 `example.com`）
2. 登录 Cloudflare Dashboard → **Workers & Pages** → 点击你的 Worker
3. 进入 **Settings** 标签页 → 左侧菜单 **Triggers** → **Custom Domains**
4. 点击 **Add Custom Domain**
5. 输入你想使用的子域名（建议：`sync.example.com`）
6. Cloudflare 会自动创建 DNS 记录并启用，或提示你添加 CNAME 记录
7. 绑定成功后，在 App 中将远端同步 URL 改为：`https://sync.example.com/api/playback/records`

> 💡 **没有域名？** 可以在 Cloudflare 上直接购买，或使用阿里云/腾讯云等国内服务商购买便宜的域名（约 10-30 元/年）。国内域名需备案才能在国内 CDN 使用，但绑定 Cloudflare Worker 无需备案。

---

## 常见问题排查

### Q1: 部署后收到 KV storage not configured 错误

**原因**：首次部署时 KV 绑定还未完全生效。

**解决**：等待 10-30 秒后刷新页面，或在 Worker → Settings → KV 中检查绑定状态。

### Q2: Invalid token 错误

**原因**：请求中未携带正确的 `X-WebHTV-Token` 头。

**解决**：
1. 检查 App 端 Webhook/远端同步配置中的 Token 是否与 Dashboard 中设置的 `ACCESS_TOKEN` 完全一致
2. 如果不需要鉴权，在 Dashboard Variables 中删除 `ACCESS_TOKEN` 变量

### Q3: App 端收不到同步数据

**排查步骤**：
1. 确认 Worker URL 正确（`https://你的子域.workers.dev`）
2. 在浏览器或 curl 中测试 `GET /api/health` 是否正常
3. 测试 `GET /api/playback/records` 是否有返回数据
4. 确认 Webhook 推送端（TV）已正确配置并在推送数据
5. 检查 App 日志中是否有相关错误

### Q4: Cloudflare Workers 域名无法在电视上访问

**现象**：App 提示 `failed to connect to xxx.workers.dev/IP(port 443) after 10000ms`，但在电脑浏览器上 Worker 可正常访问。

**原因**：
- 部分 ISP（尤其是国内）可能限制了对 Cloudflare `workers.dev` 域名的访问
- TV 系统的网络功能可能有更多限制
- 路由器/防火墙可能拦截了出站 HTTPS 连接到特定 IP 段

**排查步骤**：

1. **先验证 Worker 本身正常**：在同一网络的手机/电脑浏览器访问 `https://你的子域.workers.dev/api/health`
  - ✅ 能访问 → Worker 正常，问题在 TV 网络 → 用方案 B 或 C
  - ❌ 不能访问 → 你的网络整体屏蔽了 `workers.dev` → 必须用方案 A

1. **方案 A（推荐）：绑定自定义域名**
  - 如果你有自己的域名，在 Cloudflare Dashboard → Worker → Settings → Triggers → Custom Domains 绑定
  - 绑定后 App 端 URL 改为 `https://sync.你的域名.com/api/playback/records`

1. **方案 B：修改 TV 的 DNS**
  - 将 TV 的 DNS 改为 `1.1.1.1`（Cloudflare）或 `8.8.8.8`（Google）或 `114.114.114.114`（114 DNS）
  - 部分 ISP 默认 DNS 可能解析 Cloudflare 到不可达的 IP

1. **方案 C：检查路由器**
  - 临时关闭路由器防火墙测试
  - 检查是否有 Cloudflare IP 段的拦截规则

1. **方案 D：手机热点测试**
  - 将 TV 连接到手机热点（4G/5G）测试
  - 如果移动网络可用，说明是宽带线路问题

### Q5: 如何重置所有数据

```bash
# 通过 API 删除全部数据
curl -X POST https://your-worker.workers.dev/api/playback/progress/delete \
  -H "X-WebHTV-Token: your-token" \
  -H "Content-Type: application/json" \
  -d '{"confirm": true, "scope": "all"}'

# 或在 Cloudflare Dashboard → KV → 手动删除所有记录
```

---

## Cloudflare 免费额度说明

| 资源 | 免费额度 | 说明 |
| --- | --- | --- |
| Worker 调用 | 每天 100,000 次 | 个人用户完全够用 |
| KV 存储 | 1 GB 总计 | 约可存储 10,000+ 条观影记录 |
| KV 读写 | 每天 100,000 次 | 个人用户完全够用 |

> 💡 **个人用户完全免费**。如果家庭使用（2-3 台设备，每天几百次调用），免费额度绰绰有余。

---

## 技术架构

```
src/index.js
├── fetch()                    # Worker 入口（CORS + 错误处理）
├── handleRequest()            # 路由分发
│   ├── GET  / 或 /admin                         # 🆕 Web 管理控制台
│   ├── GET  /api/health                         # 健康检查
│   ├── GET  /api/stats                          # 🆕 数据统计
│   ├── GET  /api/server/capabilities             # 能力查询
│   ├── POST /api/playback/webhook              # Webhook 推送入口
│   ├── POST /api/playback/progress             # (同上，兼容)
│   ├── POST /api/playback/progress/batch       # 批量写入
│   ├── POST /api/playback/records/batch        # (同上，兼容)
│   ├── GET  /api/playback/records              # 远端同步查询
│   ├── GET  /api/playback/progress             # (同上，兼容)
│   ├── DELETE /api/playback/progress           # 删除记录
│   ├── DELETE /api/playback/records             # (同上，兼容)
│   └── POST /api/playback/progress/delete      # (同上，兼容)
├── getStats()                 # 🆕 数据统计（总数/站点数/客户端数）
├── loadAllRecords()           # O(1) 读取 KV 单键 JSON 数组
├── saveAllRecords()           # 写入 KV 单键（含 TTL）
├── upsertRecord()             # 单条 upsert（按 dedupeKey 去重）
├── upsertBatch()              # 批量 upsert（一次性保存）
├── findRecordIndex()          # 按 dedupeKey/historyKey 查找
├── buildStoreRecord()         # 构建规范化存储结构
├── validateRecord()           # 校验必填字段
├── deleteRecords()            # 多种删除策略（dedupeKey/historyKey/siteKey）
├── filterRecords()             # 按条件过滤 + 过期过滤
├── toProgressInput()          # 转换为 App 可解析格式
└── checkToken()               # Token 鉴权（支持 Header + Query 参数）

src/dashboard.js 🆕
├── DASHBOARD_HTML             # 管理控制台 HTML 模板（单文件 SPA）
├── getDashboardHtml()         # 嵌入 token 生成完整 HTML
└── getDashboardResponse()     # 返回 HTML Response

存储设计：
┌───────────────────────────────────────────────┐
│  Cloudflare KV Namespace: PLAYBACK_KV          │
│  ┌─────────────────────────────────────────┐  │
│  │ Key: "all_records"                       │  │
│  │ Value: JSON Array [ record1, record2 ]  │  │
│  │ TTL: 90 days (RETENTION_DAYS)           │  │
│  └─────────────────────────────────────────┘  │
│                                                │
│  优势:                                        │
│  • 读取: O(1) 单次 KV get                     │
│  • 写入: 读-改-写 单次 KV put                 │
│  • 配额: 比 per-key 方案节省 90%+ 读写次数   │
└───────────────────────────────────────────────┘
```

---

## 更新日志

- **v1.4.0** - 新增 Web 管理控制台（`/`、`/admin`），支持数据概览、记录浏览、搜索过滤、删除操作；新增 `/api/stats` 统计接口；token 鉴权支持查询参数传递
- **v1.3.0** - 优化 Cloudflare Connect to Git 部署教程，增加快速概览、5 步流程详解、更换仓库指南和扩展故障排查
- **v1.2.0** - 改为 Cloudflare Connect to Git 部署方案，敏感变量迁移到 Dashboard Variables
- **v1.1.0** - 重构为单 KV key 存储方案，大幅减少 KV 读写次数；添加字段校验
- **v1.0.0** - 初始版本，支持 Webhook 推送、远端同步拉取、批量操作、Token 鉴权、TTL 自动清理
