# WebHTV 观影记录同步 Cloudflare Worker

> 本文档为 [WebHTV](https://github.com/liu673cn/WebHTV) 项目提供基于 **Cloudflare Workers + KV 存储** 的观影记录同步服务端部署方案。

> ☁️ **最快的部署方式**：[GitHub Actions 一键部署](#-github-actions-一键部署推荐) — 3 分钟完成，无需本地环境，`git push` 即自动部署到 Cloudflare Workers。

## 目录

- [功能概述](#功能概述)
- [工作原理](#工作原理)
- [前置准备](#前置准备)
- [☁️ GitHub Actions 一键部署（推荐）](#-github-actions-一键部署推荐)
  - [工作流程](#工作流程-1)
  - [第一步：在 GitHub 仓库中配置 Secrets](#第一步在-github-仓库中配置-secrets)
  - [第二步：获取 Cloudflare 凭证](#第二步获取-cloudflare-凭证)
  - [第三步：推送代码到 GitHub](#第三步推送代码到-github)
  - [第四步：查看部署结果](#第四步查看部署结果)
  - [第五步：手动触发部署（可选）](#第五步手动触发部署可选)
  - [后续更新 Worker](#后续更新-worker)
  - [工作流配置文件](#工作流配置文件)
  - [故障排查](#故障排查)
- [手动部署步骤（本地方式）](#手动部署步骤本地方式)
  - [第一步：安装 Node.js 和 Wrangler CLI](#第一步安装-nodejs-和-wrangler-cli)
  - [第二步：登录 Cloudflare](#第二步登录-cloudflare)
  - [第三步：创建 KV 命名空间](#第三步创建-kv-命名空间)
  - [第四步：配置 wrangler.toml](#第四步配置-wranglertoml)
  - [第五步：本地调试](#第五步本地调试)
  - [第六步：部署到 Cloudflare](#第六步部署到-cloudflare)
  - [第七步：配置自定义域名（可选）](#第七步配置自定义域名可选)
- [API 接口说明](#api-接口说明)
  - [接收观影记录（Webhook 推送）](#接收观影记录webhook-推送)
  - [批量接收观影记录](#批量接收观影记录)
  - [查询观影记录（远端同步拉取）](#查询观影记录远端同步拉取)
  - [删除观影记录](#删除观影记录)
  - [健康检查和能力查询](#健康检查和能力查询)
- [App 端配置指南](#app-端配置指南)
  - [配置 Webhook 推送](#配置-webhook-推送)
  - [配置远端同步拉取](#配置远端同步拉取)
  - [两种模式对比](#两种模式对比)
- [进阶配置](#进阶配置)
  - [访问令牌保护](#访问令牌保护)
  - [数据保留策略](#数据保留策略)
  - [限流与安全](#限流与安全)
- [常见问题排查](#常见问题排查)
- [Cloudflare 免费额度说明](#cloudflare-免费额度说明)

---

## 功能概述

本 Worker 为 WebHTV App 提供**观影记录（播放进度）同步中心**服务：

- 📥 **Webhook 推送接收**：任何 WebHTV 设备在播放时自动将观影记录推送到此服务
- 📤 **远端同步拉取**：其他设备从此服务拉取观影记录，实现多设备进度同步
- 🔄 **双向同步**：支持 Webhook 推送 + 远端拉取两种模式可独立或组合使用
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
|------|------|
| Cloudflare 账号 | [免费注册](https://dash.cloudflare.com/sign-up) |
| GitHub 账号 | 需要将代码推送到 GitHub 仓库 |
| 本项目代码 | 已在 `serverless/webhtv-playback-sync-cloudflare/` 目录 |

> 💡 使用 **GitHub Actions 一键部署** 方式时，**无需** 在本地安装 Node.js 或 Wrangler CLI，所有环境都由 GitHub Actions 自动配置。

> 💡 仅在选择 [手动部署步骤（本地方式）](#手动部署步骤本地方式) 时才需要安装下面的工具。

## ☁️ GitHub Actions 一键部署（推荐）

> 这是最简单的部署方式——**代码推送到 GitHub 后自动部署到 Cloudflare Workers**，无需在本地安装任何工具。

### 工作流程

```
┌──────────────┐     push      ┌──────────────┐    deploy     ┌──────────────┐
│  你的电脑    │ ───────────► │   GitHub     │ ──────────► │  Cloudflare  │
│  git push    │   main 分支  │   Actions    │   自动部署   │  Workers     │
└──────────────┘              └──────────────┘              └──────────────┘
                                       │
                                       ▼
                                  读取 Secrets
                                  (API Token,
                                   Account ID,
                                   ACCESS_TOKEN)
```

### 第一步：在 GitHub 仓库中配置 Secrets

1. 打开你的 GitHub 仓库 → **Settings** → **Secrets and variables** → **Actions**
2. 点击右上角 **New repository secret**，添加以下 4 个 Secret：

| Secret 名称 | 说明 | 获取方式 |
|---|---|---|
| `CLOUDFLARE_API_TOKEN` | Cloudflare API Token | [创建方法](#如何获取-cloudflare-api-token) |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare 账户 ID | [获取方法](#如何获取-cloudflare-account-id) |
| `CLOUDFLARE_SUBDOMAIN` | Workers 子域名 | 账户设置 → Workers & Pages → 查看 workers.dev 子域 |
| `WORKER_ACCESS_TOKEN` |  Worker 访问令牌（自定义字符串） | 自己生成一个，如 `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"` |

**可选 Secret：**
- `CLOUDFLARE_KV_NAMESPACE_ID` — 预创建 KV 命名空间 ID。不设置则首次部署时自动创建

### 第二步：获取 Cloudflare 凭证

#### 如何获取 CLOUDFLARE_API_TOKEN

1. 打开 [Cloudflare Dashboard](https://dash.cloudflare.com)
2. 右上角头像 → **My Profile** → **API Tokens**
3. 点击 **Create Token** → 选择 **Edit Cloudflare Workers** 模板
4. **Permissions** 保持默认（Account / Workers / Edit）
5. **Account Resources** → **Include** → 选择你的账户
6. 点击 **Continue to summary** → **Create Token**
7. **复制生成的 Token**（只显示一次！）→ 粘贴到 GitHub Secret

#### 如何获取 CLOUDFLARE_ACCOUNT_ID

1. 打开 [Cloudflare Dashboard](https://dash.cloudflare.com)
2. 选择你的账户 → 点击右上角头像 → **Your Profile** → **API Tokens**
3. 或直接在账户首页 URL 中找到：`https://dash.cloudflare.com/?account=<ACCOUNT_ID>`
4. 复制 `<ACCOUNT_ID>` 部分 → 粘贴到 GitHub Secret

#### 如何获取 CLOUDFLARE_SUBDOMAIN

1. 打开 [Cloudflare Dashboard](https://dash.cloudflare.com)
2. 左侧菜单 → **Workers & Pages**
3. 找到你的 workers.dev 子域，格式如 `your-name-1234.workers.dev`
4. 只取子域名部分（如 `your-name-1234`）→ 粘贴到 GitHub Secret

### 第三步：推送代码到 GitHub

```bash
# 确保你的项目已经推送到 GitHub
# Worker 代码位于 serverless/webhtv-playback-sync-cloudflare/ 目录
git add .
git commit -m "feat: 添加观影记录同步 Cloudflare Worker"
git push origin main
```

推送完成后，GitHub Actions 会自动触发部署流程。

### 第四步：查看部署结果

1. 打开 GitHub 仓库 → 顶部菜单点击 **Actions**
2. 左侧选择 **Deploy Cloudflare Worker** 工作流
3. 点击最新的运行记录查看详情
4. 等待所有步骤显示 ✅ 绿色对勾

部署成功后，Workflow 的最后一步会输出 Worker 访问地址：

```
╔══════════════════════════════════════════════════════════╗
║           🎉 Deployment Successful!                     ║
╠══════════════════════════════════════════════════════════╣
║  Worker URL:  https://webhtv-playback-sync.your-name.workers.dev
║  Health:      https://.../api/health
║  Records API:  https://.../api/playback/records
║  Webhook:     POST https://.../api/playback/webhook
╚══════════════════════════════════════════════════════════╝
```

### 第五步：手动触发部署（可选）

如果想在不推送代码的情况下重新部署：

1. GitHub 仓库 → **Actions** → **Deploy Cloudflare Worker**
2. 点击 **Run workflow** 按钮
3. 选择分支 → 点击确认

### 后续更新 Worker

只需修改 `serverless/webhtv-playback-sync-cloudflare/` 下的代码并推送到 `main` 分支，GitHub Actions 会自动重新部署：

```bash
# 修改代码后
git add serverless/webhtv-playback-sync-cloudflare/
git commit -m "fix: 修复观影记录同步 bug"
git push origin main
# 等待 1-2 分钟即可自动部署完成
```

### 工作流配置文件

完整的 GitHub Actions 工作流配置文件位于 [.github/workflows/deploy-cloudflare-worker.yml](file:///d:/交投资料/相关文档/07-Git文件/Github/WebHTV/webhtv/.github/workflows/deploy-cloudflare-worker.yml)，关键特性：

- ✅ 自动安装 Node.js 20 + Wrangler CLI
- ✅ 自动创建 KV 命名空间（首次部署）
- ✅ 从 Secrets 自动生成 `wrangler.toml`
- ✅ 部署后自动验证健康检查
- ✅ 支持手动触发 `workflow_dispatch`
- ✅ 并发保护（同时只有一个部署在运行）

### 故障排查

| 错误 | 原因 | 解决 |
|------|------|------|
| `CLOUDFLARE_API_TOKEN secret is not configured` | 未添加 Token Secret | 到仓库 Settings → Secrets 添加 |
| `Invalid API Token` | Token 错误或已过期 | 重新在 Cloudflare 生成 Token |
| `KV storage not configured` | 首次部署绑定未生效 | 等待 30 秒后重新运行 |
| Worker URL 无法访问 | `CLOUDFLARE_SUBDOMAIN` 配置错误 | 检查 Subdomain 是否正确 |
| `wrangler.toml` 中的 KV ID 为空 | Secret 未正确注入 | 检查 `CLOUDFLARE_KV_NAMESPACE_ID` 是否为空 |
| `npm ci` 失败 | Worker 目录无 `package-lock.json` | 已修复为 `npm install` |

> 💡 **注意**：如果你使用的是 fork 仓库，需要在 fork 后手动添加 Secrets。原仓库的 Secrets 不会自动同步到 fork。

> 💡 **重要**：首次部署成功后，**请将 Workflow 日志中输出的 KV Namespace ID** 保存到 GitHub Secret `CLOUDFLARE_KV_NAMESPACE_ID` 中。否则每次部署都会自动创建新的 KV 命名空间，导致旧数据丢失。在 Workflow 日志中搜索 `Created KV namespace: xxx` 即可找到。

---

## 手动部署步骤（本地方式）

> 如果你更喜欢在本地手动操作，可以使用下面的方法。

### 第一步：安装 Node.js 和 Wrangler CLI

```bash
# 检查 Node.js 版本（需要 >= 18）
node --version

# 全局安装 Wrangler CLI
npm install -g wrangler

# 验证安装
wrangler --version
```

### 第二步：登录 Cloudflare

```bash
# 在浏览器中完成登录授权
wrangler login

# 登录成功后会显示你的账号名
# 如需切换账号，使用：wrangler logout 然后重新 login
```

### 第三步：创建 KV 命名空间

```bash
# 进入项目目录
cd serverless/webhtv-playback-sync-cloudflare

# 创建 KV 命名空间（用于存储观影记录）
wrangler kv:namespace create "PLAYBACK_KV"
```

**输出示例：**
```
🪄  Creating namespace with title "PLAYBACK_KV"
✅  Success! Namespace created with ID: abc123def456abcdef78901234567890
```

> ⚠️ **请妥善保存这个 ID**，下一步需要用到。

如果你已经有一个 KV 命名空间，可以直接复用：

```bash
# 列出你已有的 KV 命名空间
wrangler kv:namespace list

# 绑定已有的命名空间（跳过创建步骤）
```

### 第四步：配置 wrangler.toml

复制示例配置并填入你的 KV 命名空间 ID：

```bash
# 如果你还没有 wrangler.toml，从示例复制
cp wrangler.toml.example wrangler.toml
```

编辑 `wrangler.toml`，填入 KV ID 和访问令牌：

```toml
name = "webhtv-playback-sync"
main = "src/index.js"
compatibility_date = "2026-06-01"

# ↓↓↓ 填入上一步获得的 KV Namespace ID ↓↓↓
kv_namespaces = [
  { binding = "PLAYBACK_KV", id = "abc123def456abcdef78901234567890" }
]

[vars]
# 设置访问令牌（App 端配置时需要填写此 token）
ACCESS_TOKEN = "你的自定义密钥-建议32位随机字符串"

# 单次查询最大返回条数
MAX_ITEMS = "1000"

# 数据保留天数（超过此时间的记录会被自动清理）
RETENTION_DAYS = "90"
```

> 💡 **获取随机密钥**：在终端运行 `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"` 生成一个 64 位十六进制字符串。

### 第五步：本地调试

```bash
# 安装依赖
npm install

# 启动本地开发服务器
npm run dev
```

**输出示例：**
```
  [INFO]  Starting local server...
  [INFO]  - http://localhost:8787
  [INFO]  Ready on http://localhost:8787
```

在另一个终端测试 API：

```bash
# 健康检查
curl http://localhost:8787/api/health

# 推送一条观影记录
curl -X POST http://localhost:8787/api/playback/webhook \
  -H "Content-Type: application/json" \
  -H "X-WebHTV-Token: 你的自定义密钥" \
  -d '{
    "schema": "webhtv.playback.v1",
    "event": "ended",
    "timestamp": 1722000000000,
    "siteKey": "csp",
    "vodId": "movie_001",
    "vodName": "流浪地球2",
    "episodeName": "正片",
    "positionMs": 5400000,
    "durationMs": 5400000,
    "progress": 1.0,
    "completed": true,
    "speed": 1.0,
    "configKey": "default",
    "dedupeKey": "unique-dedupe-key-001"
  }'

# 查询观影记录
curl http://localhost:8787/api/playback/records \
  -H "X-WebHTV-Token: 你的自定义密钥"
```

### 第六步：部署到 Cloudflare

```bash
# 一键部署
npm run deploy
```

**输出示例：**
```
  [INFO]  Deploying your worker...
  [INFO]  Uploaded ... (2.13 sec)
  [INFO]  Submitting your worker...
  [INFO]  Uploaded (2.09 sec)
  [INFO]  Deployed your worker (2.81 sec)
  [INFO]  Worker Version ID: v1-abcd1234...
  [INFO]  Worker URL: https://webhtv-playback-sync-你的子域.workers.dev
```

> ⚠️ **重要**：首次部署后，KV 命名空间绑定需要几秒钟完成。如遇 `KV storage not configured` 错误，等待 10-30 秒后重试。

**部署后验证：**

```bash
# 测试线上健康检查
curl https://你的-worker-subdomain.workers.dev/api/health

# 测试线上能力查询
curl https://你的-worker-subdomain.workers.dev/api/server/capabilities
```

### 第七步：配置自定义域名（可选）

如果已有自己的域名，可以绑定到 Worker：

```bash
# 在 Cloudflare Dashboard 中：
# 1. 进入 Workers & Pages → 选择你的 Worker
# 2. 点击 "Settings" → "Triggers" → "Custom Domains"
# 3. 点击 "Add Custom Domain" → 输入你的子域名（如 sync.yourdomain.com）
# 4. 按要求配置 DNS 记录

# 也可以通过 wrangler 命令行配置
wrangler route add "sync.yourdomain.com/*" webhtv-playback-sync
```

配置完成后，你就可以使用自定义域名作为 WebHTV App 的同步地址。

---

## API 接口说明

### 接收观影记录（Webhook 推送）

**`POST /api/playback/webhook`**

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
    "key": "sha256-hash-of-content",
    "dedupeKey": "sha256-hash-of-content",
    "historyKey": "csp/movie_001/1"
  }
}
```

> 💡 此接口兼容 `POST /api/playback/progress`，两个地址功能完全相同。

### 批量接收观影记录

**`POST /api/playback/progress/batch`**

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

**`GET /api/playback/records`**

**请求头：**
```
X-WebHTV-Token: <你的访问令牌>
X-WebHTV-Config-Key: <接口 Key>      # 可选
X-WebHTV-Config-Name: <接口名称>      # 可选
```

**查询参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
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

**`DELETE /api/playback/progress`** 或 **`POST /api/playback/progress/delete`**

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

---

## App 端配置指南

### 配置 Webhook 推送

1. 打开 WebHTV App → **设置** → **观影记录同步**
2. 开启 **观影记录同步** 开关
3. 找到 **Webhook 推送** 区域 → 点击 **添加**
4. 填写配置：
   - **名称**：随意，如 `Cloudflare 同步`
   - **URL**：`https://你的-worker-subdomain.workers.dev/api/playback/webhook`
   - **Token**：填入 `wrangler.toml` 中设置的 `ACCESS_TOKEN`
   - **事件**：勾选 `progress`、`ended`（建议全选）
   - **站点过滤**：留空表示同步所有站点
5. 保存后，播放节目时会自动推送记录到 Worker

### 配置远端同步拉取

1. 打开 WebHTV App → **设置** → **观影记录同步**
2. 找到 **远端同步** 区域 → 点击 **添加**
3. 填写配置：
   - **名称**：随意，如 `家庭影院同步`
   - **URL**：`https://你的-worker-subdomain.workers.dev/api/playback/records`
   - **Token**：填入 `wrangler.toml` 中设置的 `ACCESS_TOKEN`
   - **站点过滤**：留空表示同步所有站点
   - **接口过滤**：可选，按 configKey 过滤
   - **启动时同步**：建议开启
   - **定时同步间隔**：建议设为 30-60 分钟
   - **最大记录数**：100-500
4. 保存后，App 会在启动时和定时拉取 Worker 中的观影记录

### 两种模式对比

| 特性 | Webhook 推送 | 远端同步拉取 |
|------|-------------|-------------|
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

在 `wrangler.toml` 的 `[vars]` 中设置 `ACCESS_TOKEN`：

```toml
[vars]
ACCESS_TOKEN = "my-secret-token-12345"
```

启用后，所有 API 请求都需携带 `X-WebHTV-Token` 头：

```bash
curl -H "X-WebHTV-Token: my-secret-token-12345" https://your-worker.workers.dev/api/playback/records
```

> ⚠️ **强烈建议生产环境一定设置 ACCESS_TOKEN**，否则任何人都可以读写你的观影记录。

### 数据保留策略

通过 `RETENTION_DAYS` 环境变量控制数据保留天数：

```toml
[vars]
RETENTION_DAYS = "30"    # 只保留最近 30 天的记录
```

Worker 会根据记录的 `timestamp`/`updatedAt` 字段自动计算过期时间，过期数据自动清除。

### 限流与安全

Cloudflare Workers 自带基础 DDoS 防护。如需更严格的限流：

1. 在 Cloudflare Dashboard 中为 Worker 配置 **Rate Limiting**
2. 启用 **WAF**（Web Application Firewall）规则
3. 使用 Cloudflare Access 限制特定 IP 访问

---

## 常见问题排查

### Q1: 部署后收到 `KV storage not configured` 错误

**原因**：首次部署时 KV 绑定还未完全生效。

**解决**：等待 10-30 秒后重新部署，或检查 `wrangler.toml` 中的 KV namespace ID 是否正确。

```bash
# 验证 KV 绑定
wrangler kv:namespace list
# 确认 PLAYBACK_KV 存在且 ID 与配置一致
```

### Q2: `Invalid token` 错误

**原因**：请求中未携带正确的 `X-WebHTV-Token` 头。

**解决**：
1. 检查 App 端 Webhook/远端同步配置中的 Token 是否与 `wrangler.toml` 中的 `ACCESS_TOKEN` 完全一致
2. 如果不需要鉴权，暂时清空 `ACCESS_TOKEN` 变量

### Q3: App 端收不到同步数据

**排查步骤**：
1. 确认 Worker URL 正确（`https://你的子域.workers.dev`）
2. 在浏览器或 curl 中测试 `GET /api/health` 是否正常
3. 测试 `GET /api/playback/records` 是否有返回数据
4. 确认 Webhook 推送端（TV）已正确配置并在推送数据
5. 检查 App 日志中是否有相关错误

### Q4: Cloudflare Workers 域名无法在电视上访问

**原因**：部分电视浏览器/系统可能无法访问 `*.workers.dev` 域名。

**解决**：
1. 绑定自定义域名到 Worker（参考第七步）
2. 确认电视 DNS 解析正常
3. 改用 `webhtv-remote-cloudflare`（Durable Object 版本）作为中转方案

### Q5: 数据量很大时查询变慢

**原因**：单键存储方案在数据量 > 5000 条时，JSON 文件体积会较大（接近 25MB KV 限制）。

**优化建议**：
1. 使用 `siteKey`/`configKey` 参数过滤，减少返回量
2. 通过 `MAX_RECORDS` 环境变量限制最大记录数（默认 500）
3. 定期通过 API 清理旧数据
4. 如需更大规模（>5000 条），考虑迁移到 Cloudflare D1 数据库

### Q6: 如何重置所有数据

```bash
# 方法一：通过 API 删除
curl -X POST https://your-worker.workers.dev/api/playback/progress/delete \
  -H "X-WebHTV-Token: your-token" \
  -H "Content-Type: application/json" \
  -d '{"confirm": true, "scope": "all"}'

# 方法二：通过 wrangler 清空 KV
wrangler kv:namespace delete --namespace-id=your-namespace-id
# 然后重新创建
wrangler kv:namespace create "PLAYBACK_KV"
```

---

## Cloudflare 免费额度说明

| 资源 | 免费额度 | 说明 |
|------|---------|------|
| Worker 调用 | 每天 100,000 次 | 个人用户完全够用 |
| KV 存储 | 1 GB 总计 | 约可存储 10,000+ 条观影记录 |
| KV 读写 | 每天 100,000 次 | 个人用户完全够用 |
| Durable Object | 免费额度内 | 本项目不使用 DO |

> 💡 **个人用户完全免费**。如果家庭使用（2-3 台设备，每天几百次调用），免费额度绰绰有余。

---

## 技术架构

```
src/index.js
├── fetch()                    # Worker 入口（CORS + 错误处理）
├── handleRequest()            # 路由分发
│   ├── GET  /api/health                       # 健康检查
│   ├── GET  /api/server/capabilities           # 能力查询
│   ├── POST /api/playback/webhook              # Webhook 推送入口
│   ├── POST /api/playback/progress             # (同上，兼容)
│   ├── POST /api/playback/progress/batch       # 批量写入
│   ├── POST /api/playback/records/batch        # (同上，兼容)
│   ├── GET  /api/playback/records              # 远端同步查询
│   ├── GET  /api/playback/progress             # (同上，兼容)
│   ├── DELETE /api/playback/progress           # 删除记录
│   ├── DELETE /api/playback/records             # (同上，兼容)
│   └── POST /api/playback/progress/delete      # (同上，兼容)
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
└── checkToken()               # Token 鉴权

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

- **v1.1.0** - 重构为单 KV key 存储方案，大幅减少 KV 读写次数；添加字段校验；添加 MAX_RECORDS 自动裁剪
- **v1.0.0** - 初始版本，支持 Webhook 推送、远端同步拉取、批量操作、Token 鉴权、TTL 自动清理
