// WebHTV 观影记录同步管理控制台 — 适配 webhtv-remote-cloudflare (Durable Object + SQLite)
// 与旧版 KV 版本的关键差异：
//   1. 认证：X-WebHTV-Token (可选，留空 = 无 Token 公共命名空间) + X-WebHTV-Config-Key (必填)，token 由用户自行生成，不写入环境变量
//   2. 数据接口：统一使用 /api/playback/sync，GET 拉取增量、POST 写入/删除
//   3. 删除：通过 POST 发送 event=playback.deleted 的墓碑事件，而非 DELETE 方法
//   4. 统计：使用 /api/playback/sync/status 而非 /api/stats
//   5. 分页：基于单调游标 (since/nextSince)，而非简单的 maxItems 列表
//   6. 接口空间合并：由 App 的 /api/playback/identity/resolve 身份协议自动完成
//      （旧版手动 /merge 与 /settings 同标题去重端点已随官方脚本升级移除）

const DASHBOARD_HTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>WebHTV 观影记录同步 · 管理控制台</title>
<style>
  :root {
    --bg: #0f1221;
    --bg-elevated: #181b2e;
    --bg-card: #1e2240;
    --border: #2a2f55;
    --text: #e4e6f4;
    --text-secondary: #9aa0c3;
    --text-muted: #6b7094;
    --accent: #6c7cff;
    --accent-hover: #8b9bff;
    --accent-glow: rgba(108, 124, 255, 0.25);
    --success: #34d399;
    --success-bg: rgba(52, 211, 153, 0.12);
    --warning: #fbbf24;
    --warning-bg: rgba(251, 191, 36, 0.12);
    --danger: #f87171;
    --danger-bg: rgba(248, 113, 113, 0.12);
    --gradient-1: linear-gradient(135deg, #6c7cff 0%, #8b5cf6 100%);
    --gradient-2: linear-gradient(135deg, #34d399 0%, #10b981 100%);
    --gradient-3: linear-gradient(135deg, #fbbf24 0%, #f59e0b 100%);
    --gradient-4: linear-gradient(135deg, #f87171 0%, #ef4444 100%);
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
    background: var(--bg);
    color: var(--text);
    line-height: 1.6;
    min-height: 100vh;
  }
  body::before {
    content: '';
    position: fixed;
    top: -50%;
    left: -50%;
    width: 200%;
    height: 200%;
    background: radial-gradient(circle at 30% 20%, rgba(108, 124, 255, 0.08) 0%, transparent 50%),
                radial-gradient(circle at 70% 80%, rgba(139, 92, 246, 0.06) 0%, transparent 50%);
    pointer-events: none;
    z-index: 0;
  }
  .container {
    position: relative;
    z-index: 1;
    max-width: 1280px;
    margin: 0 auto;
    padding: 32px 24px;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 32px;
    padding-bottom: 24px;
    border-bottom: 1px solid var(--border);
  }
  .logo { display: flex; align-items: center; gap: 14px; }
  .logo-icon {
    width: 44px; height: 44px;
    background: var(--gradient-1);
    border-radius: 12px;
    display: flex; align-items: center; justify-content: center;
    font-size: 22px;
    box-shadow: 0 8px 24px var(--accent-glow);
  }
  .logo-text h1 { font-size: 20px; font-weight: 700; letter-spacing: -0.5px; }
  .logo-text p { font-size: 13px; color: var(--text-secondary); }
  .status-badge {
    display: inline-flex; align-items: center; gap: 8px;
    padding: 8px 16px; border-radius: 100px;
    font-size: 13px; font-weight: 500;
    background: var(--success-bg); color: var(--success);
    border: 1px solid rgba(52, 211, 153, 0.3);
  }
  .status-badge.error { background: var(--danger-bg); color: var(--danger); border-color: rgba(248, 113, 113, 0.3); }
  .status-badge .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; animation: pulse 2s ease-in-out infinite; }
  @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.4; } }

  .stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 32px; }
  .stat-card {
    background: var(--bg-card); border: 1px solid var(--border);
    border-radius: 16px; padding: 24px;
    transition: all 0.2s ease; position: relative; overflow: hidden;
  }
  .stat-card:hover { transform: translateY(-2px); border-color: rgba(108, 124, 255, 0.4); box-shadow: 0 12px 32px rgba(0,0,0,0.3); }
  .stat-card::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; }
  .stat-card:nth-child(1)::before { background: var(--gradient-1); }
  .stat-card:nth-child(2)::before { background: var(--gradient-2); }
  .stat-card:nth-child(3)::before { background: var(--gradient-3); }
  .stat-card:nth-child(4)::before { background: var(--gradient-4); }
  .stat-label { font-size: 13px; color: var(--text-secondary); margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }
  .stat-icon { width: 32px; height: 32px; border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 16px; }
  .stat-value { font-size: 32px; font-weight: 700; letter-spacing: -1px; margin-bottom: 4px; }
  .stat-sub { font-size: 12px; color: var(--text-muted); }

  .section { background: var(--bg-elevated); border: 1px solid var(--border); border-radius: 16px; padding: 24px; margin-bottom: 24px; }
  .section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; flex-wrap: wrap; gap: 12px; }
  .section-title { font-size: 16px; font-weight: 600; display: flex; align-items: center; gap: 10px; }
  .section-title .icon { width: 28px; height: 28px; border-radius: 8px; background: var(--accent-glow); display: flex; align-items: center; justify-content: center; font-size: 14px; }
  .toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  .search-input {
    background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px;
    padding: 8px 14px; color: var(--text); font-size: 14px; width: 220px; outline: none; transition: border-color 0.2s;
  }
  .search-input:focus { border-color: var(--accent); }
  .search-input::placeholder { color: var(--text-muted); }

  .btn {
    display: inline-flex; align-items: center; gap: 6px;
    padding: 8px 16px; border-radius: 8px; font-size: 14px; font-weight: 500;
    cursor: pointer; border: 1px solid var(--border); background: var(--bg-card); color: var(--text);
    transition: all 0.15s ease; white-space: nowrap;
  }
  .btn:hover { border-color: var(--accent); background: var(--bg-elevated); }
  .btn-primary { background: var(--gradient-1); border: none; color: white; }
  .btn-primary:hover { filter: brightness(1.1); box-shadow: 0 4px 12px var(--accent-glow); }
  .btn-danger { background: var(--danger-bg); border-color: rgba(248, 113, 113, 0.3); color: var(--danger); }
  .btn-danger:hover { background: var(--danger); color: white; border-color: var(--danger); }
  .btn-sm { padding: 5px 10px; font-size: 12px; }

  .table-wrapper { overflow-x: auto; border-radius: 12px; border: 1px solid var(--border); }
  table { width: 100%; border-collapse: collapse; font-size: 14px; }
  thead { background: var(--bg-card); }
  th { padding: 14px 16px; text-align: left; font-size: 12px; font-weight: 600; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 0.5px; border-bottom: 1px solid var(--border); }
  th.sortable { cursor: pointer; user-select: none; transition: color 0.15s, background 0.15s; }
  th.sortable:hover { color: var(--accent); background: var(--bg-elevated); }
  th.sortable .sort-arrow { display: inline-block; margin-left: 4px; font-size: 10px; opacity: 0.3; transition: opacity 0.15s; }
  th.sortable.sorted .sort-arrow { opacity: 1; color: var(--accent); }
  th.sortable:hover .sort-arrow { opacity: 0.7; }
  td { padding: 14px 16px; border-bottom: 1px solid var(--border); vertical-align: top; }
  tbody tr { transition: background 0.15s; }
  tbody tr:hover { background: var(--bg-card); }
  tbody tr:last-child td { border-bottom: none; }

  .vod-name { font-weight: 600; color: var(--text); max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .vod-meta { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
  .site-badge { display: inline-block; padding: 3px 10px; border-radius: 6px; font-size: 12px; background: var(--accent-glow); color: var(--accent); font-weight: 500; }
  .progress-bar { width: 120px; height: 6px; background: var(--bg); border-radius: 3px; overflow: hidden; margin: 6px 0 4px; }
  .progress-fill { height: 100%; border-radius: 3px; transition: width 0.3s; }
  .progress-text { font-size: 12px; color: var(--text-secondary); }
  .time-cell { font-size: 12px; color: var(--text-secondary); white-space: nowrap; }
  .time-cell .date { color: var(--text); font-weight: 500; }
  .time-cell .relative { color: var(--text-muted); }
  .completed-tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 500; background: var(--success-bg); color: var(--success); }
  .playing-tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 500; background: var(--warning-bg); color: var(--warning); }
  .deleted-tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 500; background: var(--danger-bg); color: var(--danger); }

  .empty-state { text-align: center; padding: 48px 24px; color: var(--text-muted); }
  .empty-state .icon { font-size: 48px; margin-bottom: 16px; opacity: 0.5; }

  .pagination { display: flex; justify-content: space-between; align-items: center; margin-top: 20px; gap: 16px; flex-wrap: wrap; }
  .pagination-info { font-size: 13px; color: var(--text-secondary); }
  .pagination-controls { display: flex; gap: 8px; align-items: center; }
  .page-btn { min-width: 36px; height: 36px; border-radius: 8px; border: 1px solid var(--border); background: var(--bg-card); color: var(--text); cursor: pointer; font-size: 14px; transition: all 0.15s; }
  .page-btn:hover:not(:disabled) { border-color: var(--accent); }
  .page-btn.active { background: var(--accent); border-color: var(--accent); color: white; }
  .page-btn:disabled { opacity: 0.4; cursor: not-allowed; }

  .login-overlay { position: fixed; inset: 0; background: var(--bg); display: flex; z-index: 50; overflow-y: auto; padding: 24px 0; }
  .login-card { background: var(--bg-elevated); border: 1px solid var(--border); border-radius: 16px; padding: 36px; max-width: 440px; width: 90%; margin: auto; }
  .login-card h2 { font-size: 22px; margin-bottom: 8px; }
  .login-card p { color: var(--text-secondary); font-size: 14px; margin-bottom: 24px; }
  .form-group { margin-bottom: 16px; }
  .form-group label { display: block; font-size: 13px; color: var(--text-secondary); margin-bottom: 6px; }
  .form-input { width: 100%; background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 10px 14px; color: var(--text); font-size: 14px; outline: none; transition: border-color 0.2s; }
  .form-input:focus { border-color: var(--accent); }
  .form-input::placeholder { color: var(--text-muted); }
  .form-hint { font-size: 12px; color: var(--text-muted); margin-top: 4px; }

  .modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.7); display: flex; align-items: center; justify-content: center; z-index: 100; backdrop-filter: blur(4px); }
  .modal { background: var(--bg-elevated); border: 1px solid var(--border); border-radius: 16px; padding: 28px; max-width: 420px; width: 90%; box-shadow: 0 24px 64px rgba(0,0,0,0.5); }
  .modal h3 { margin-bottom: 12px; font-size: 18px; }
  .modal p { color: var(--text-secondary); margin-bottom: 20px; font-size: 14px; }
  .modal-actions { display: flex; gap: 10px; justify-content: flex-end; }

  .toast { position: fixed; bottom: 24px; right: 24px; padding: 14px 20px; border-radius: 10px; font-size: 14px; z-index: 200; animation: slideIn 0.3s ease; box-shadow: 0 12px 32px rgba(0,0,0,0.4); }
  .toast.success { background: var(--gradient-2); color: white; }
  .toast.error { background: var(--gradient-4); color: white; }
  .toast.info { background: var(--gradient-1); color: white; }
  @keyframes slideIn { from { transform: translateY(20px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }

  .loading { display: inline-block; width: 16px; height: 16px; border: 2px solid var(--border); border-top-color: var(--accent); border-radius: 50%; animation: spin 0.6s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }

  .config-key-display { font-size: 12px; color: var(--text-muted); margin-left: 8px; cursor: pointer; }
  .config-key-display:hover { color: var(--accent); }
  .token-badge {
    font-size: 12px;
    padding: 4px 10px;
    border-radius: 6px;
    background: #7c2d1226;
    color: #fb923c;
    border: 1px solid #fdba7466;
    white-space: nowrap;
  }
  .token-badge::before { content: '⚠️ 无 Token · 公共命名空间'; }

  footer { text-align: center; padding: 32px 24px; color: var(--text-muted); font-size: 13px; }
  footer a { color: var(--accent); text-decoration: none; }
  footer a:hover { text-decoration: underline; }

  @media (max-width: 900px) {
    .stats-grid { grid-template-columns: repeat(2, 1fr); }
    header { flex-direction: column; gap: 16px; align-items: flex-start; }
    .toolbar { width: 100%; }
    .search-input { flex: 1; min-width: 0; }
  }
  @media (max-width: 560px) {
    .stats-grid { grid-template-columns: 1fr; }
    .container { padding: 20px 16px; }
    .stat-value { font-size: 26px; }
    table { font-size: 13px; }
    th, td { padding: 10px 12px; }
  }
</style>
</head>
<body>

<div id="loginOverlay" class="login-overlay">
  <div class="login-card">
    <h2>🎬 观影记录同步</h2>
    <p>连接到 WebHTV Remote Cloudflare Worker</p>
    <div class="form-group">
      <label>Worker 地址</label>
      <input type="text" id="loginUrl" class="form-input" placeholder="https://your-worker.workers.dev" value="">
      <div class="form-hint">部署后的 Worker 域名，无需加 /api 路径</div>
    </div>
    <div class="form-group">
      <label>Token（访问令牌，可选）</label>
      <input type="password" id="loginToken" class="form-input" placeholder="留空 = 无 Token 模式（与其他无 Token 用户共享命名空间）" value="">
      <div class="form-hint">用 <code>openssl rand -hex 32</code> 生成，与 App 中填写的一致。留空时使用公共命名空间（无数据隔离）。</div>
    </div>
    <div class="form-group">
      <label>Config Key 或 点播接口 URL</label>
      <div style="display:flex;gap:8px;">
        <input type="text" id="loginConfigKey" class="form-input" placeholder="interfaceKey (UUID) 或 接口 URL" value="" style="flex:1;">
        <button type="button" class="btn" onclick="toggleConfigMode()" id="configModeBtn" style="white-space:nowrap;">URL→Key</button>
      </div>
      <div class="form-hint" id="configKeyHint">新版 App 发送稳定 interfaceKey（UUID 格式），直接粘贴即可。旧版可输入接口 URL 自动计算 SHA-256 configKey</div>
    </div>
    <button class="btn btn-primary" style="width:100%;justify-content:center;padding:12px;" onclick="doLogin()">
      🔗 连接
    </button>
    <button class="btn" style="width:100%;justify-content:center;padding:10px;margin-top:8px;" onclick="findConfigs()">
      🔍 查询已有接口（App 上报的 configKey）
    </button>
    <div id="configListResult" class="form-hint" style="margin-top:8px;display:none;max-height:46vh;overflow-y:auto;"></div>
  </div>
</div>

<div class="container" id="mainContent" style="display:none;">
  <header>
    <div class="logo">
      <div class="logo-icon">🎬</div>
      <div class="logo-text">
        <h1>观影记录同步</h1>
        <p>WebHTV Playback Sync · Durable Object + SQLite</p>
      </div>
    </div>
    <div style="display:flex;gap:12px;align-items:center;">
      <span id="tokenBadge" class="token-badge" style="display:none;" title="当前未使用 Token"></span>
      <span class="config-key-display" id="configKeyDisplay" onclick="showLogin()" title="点击切换"></span>
      <div id="statusBadge" class="status-badge">
        <span class="dot"></span>
        <span id="statusText">连接中...</span>
      </div>
      <button class="btn btn-sm" onclick="showLogin()">⚙️</button>
    </div>
  </header>

  <div class="stats-grid">
    <div class="stat-card" title="点击查看记录列表" onclick="scrollToRecords()" style="cursor:pointer">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--accent-glow); color: var(--accent);">📺</span>
        活跃记录
      </div>
      <div class="stat-value" id="totalCount">-</div>
      <div class="stat-sub">当前 configKey 下的进度记录 · 点击查看</div>
    </div>
    <div class="stat-card" id="tombstoneCard" title="点击清理当前接口的删除墓碑" onclick="confirmPurgeTombstones()" style="cursor:pointer">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--danger-bg); color: var(--danger);">🗑️</span>
        删除墓碑
      </div>
      <div class="stat-value" id="tombstoneCount">-</div>
      <div class="stat-sub">90 天内的删除记录 · 点击清理</div>
    </div>
    <div class="stat-card" title="什么是同步游标" onclick="showCursorInfo()" style="cursor:pointer">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--success-bg); color: var(--success);">📊</span>
        同步游标
      </div>
      <div class="stat-value" id="nextSince">-</div>
      <div class="stat-sub">最新序列号 (nextSince) · 点击说明</div>
    </div>
    <div class="stat-card" title="清理事件去重记录" onclick="confirmPurgeEvents()" style="cursor:pointer">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--warning-bg); color: var(--warning);">⏱️</span>
        去重记录
      </div>
      <div class="stat-value" id="eventCount">-</div>
      <div class="stat-sub">条 · 全部接口合计 · 点击清理</div>
    </div>
  </div>

  <div class="section" id="recordsSection">
    <div class="section-header">
      <div class="section-title">
        <span class="icon">📋</span>
        观影记录列表
      </div>
      <div class="toolbar">
        <input type="text" id="searchInput" class="search-input" placeholder="搜索影片、站点...">
        <button class="btn" id="refreshBtn" onclick="loadData()">🔄 刷新</button>
        <button class="btn btn-danger" onclick="confirmClearAll()">🗑️ 清空全部</button>
      </div>
    </div>

    <div class="table-wrapper">
      <table>
        <thead>
          <tr>
            <th class="sortable" data-sortkey="vodName" onclick="sortBy('vodName')">影片<span class="sort-arrow">↕</span></th>
            <th class="sortable" data-sortkey="siteKey" onclick="sortBy('siteKey')">站点<span class="sort-arrow">↕</span></th>
            <th class="sortable" data-sortkey="progress" onclick="sortBy('progress')">进度<span class="sort-arrow">↕</span></th>
            <th class="sortable" data-sortkey="status" onclick="sortBy('status')">状态<span class="sort-arrow">↕</span></th>
            <th class="sortable" data-sortkey="updatedAt" onclick="sortBy('updatedAt')">更新时间<span class="sort-arrow">↕</span></th>
            <th style="width: 60px;"></th>
          </tr>
        </thead>
        <tbody id="recordsBody">
          <tr><td colspan="6" class="empty-state"><div class="loading"></div></td></tr>
        </tbody>
      </table>
    </div>

    <div class="pagination" id="pagination" style="display:none;">
      <div class="pagination-info" id="paginationInfo"></div>
      <div class="pagination-controls" id="paginationControls"></div>
    </div>
  </div>

  <footer>
    <p>WebHTV Playback Sync · Durable Object + SQLite · 部署在 Cloudflare Workers</p>
  </footer>
</div>

<div id="modal" style="display:none;"></div>

<script>
// ============ 配置与状态 ============
const STORAGE_KEY = 'webhtv_sync_credentials';

const state = {
  baseUrl: '',
  token: '',
  configKey: '',
  records: [],
  status: null,
  page: 1,
  pageSize: 20,
  search: '',
  filtered: [],
  sortKey: 'updatedAt',
  sortDir: 'desc'
};

// ============ 登录与凭证管理 ============
// configKey 输入模式：'url' = 输入接口URL自动计算sha256，'key' = 直接输入configKey
let configKeyMode = 'url';

function loadCredentials() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
    if (saved.baseUrl) document.getElementById('loginUrl').value = saved.baseUrl;
    if (saved.token) document.getElementById('loginToken').value = saved.token;
    if (saved.configKey) document.getElementById('loginConfigKey').value = saved.configKey;
    if (saved.configKeyMode) { configKeyMode = saved.configKeyMode; updateConfigModeUI(); }
  } catch (e) {}
}

function saveCredentials(baseUrl, token, configKey) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({ baseUrl, token, configKey, configKeyMode }));
}

function toggleConfigMode() {
  configKeyMode = configKeyMode === 'url' ? 'key' : 'url';
  updateConfigModeUI();
  // 切换时清空当前输入，避免混淆
  document.getElementById('loginConfigKey').value = '';
}

function updateConfigModeUI() {
  const btn = document.getElementById('configModeBtn');
  const input = document.getElementById('loginConfigKey');
  const hint = document.getElementById('configKeyHint');
  if (configKeyMode === 'url') {
    btn.textContent = 'URL→Key';
    input.placeholder = 'https://example.com/config.json';
    hint.textContent = '旧版模式：输入接口 URL 自动计算 SHA-256 configKey。新版 App 推荐直接粘贴 interfaceKey';
  } else {
    btn.textContent = 'Key模式';
    input.placeholder = 'interfaceKey (UUID) 或 sha256 configKey';
    hint.textContent = '直接输入 App 发送的 X-WebHTV-Config-Key 值。新版 App 为 interfaceKey（UUID），旧版为 64 位 SHA-256';
  }
}

// SHA-256 计算（浏览器原生 Web Crypto API）
async function computeConfigKey(url) {
  const trimmed = (url || '').trim();
  if (!trimmed) return '';
  const data = new TextEncoder().encode(trimmed);
  const hashBuffer = await crypto.subtle.digest('SHA-256', data);
  return Array.from(new Uint8Array(hashBuffer)).map(b => b.toString(16).padStart(2, '0')).join('');
}

// 判断输入是否已经是 configKey（64位十六进制 = sha256 结果）
function isSha256Hex(value) {
  return /^[0-9a-f]{64}$/.test((value || '').trim().toLowerCase());
}
// 判断输入是否是 interfaceKey（UUID 格式，新版 App 稳定身份标记）
function isInterfaceKey(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test((value || '').trim().toLowerCase());
}

function showLogin() {
  document.getElementById('loginOverlay').style.display = 'flex';
  document.getElementById('mainContent').style.display = 'none';
}

async function doLogin() {
  const baseUrl = document.getElementById('loginUrl').value.trim().replace(/\\/+$/, '');
  const token = document.getElementById('loginToken').value.trim();
  const rawInput = document.getElementById('loginConfigKey').value.trim();

  if (!baseUrl) { showToast('请填写 Worker 地址', 'error'); return; }
  if (!rawInput) { showToast('请填写点播接口 URL 或 Config Key', 'error'); return; }
  // Token 留空时给出提示（无数据隔离，与其他无 Token 用户共享命名空间）
  if (!token) { showToast('当前使用无 Token 模式：与其他未配置 Token 的用户共享命名空间（无数据隔离）', 'warn'); }

  // 智能识别：interfaceKey (UUID) > 旧 sha256 configKey > URL→sha256 计算 > 直接使用
  let configKey;
  if (isInterfaceKey(rawInput)) {
    // 新版 App 稳定身份标记（interfaceKey），直接使用
    configKey = rawInput.toLowerCase();
    showToast('已识别 interfaceKey: ' + configKey.substring(0, 13) + '...', 'info');
  } else if (isSha256Hex(rawInput)) {
    configKey = rawInput.toLowerCase();
  } else if (configKeyMode === 'url' || rawInput.startsWith('http')) {
    configKey = await computeConfigKey(rawInput);
    if (!configKey) { showToast('Config Key 计算失败', 'error'); return; }
    showToast('已从 URL 计算 configKey（旧模式）: ' + configKey.substring(0, 12) + '...', 'warn');
  } else {
    // key 模式下输入了非 sha256 的值，当作普通 configKey 使用
    configKey = rawInput.toLowerCase();
  }

  state.baseUrl = baseUrl;
  state.token = token;
  state.configKey = configKey;
  saveCredentials(baseUrl, token, configKey);

  // 更新无 Token 徽章
  const tokenBadge = document.getElementById('tokenBadge');
  if (tokenBadge) tokenBadge.style.display = token ? 'none' : 'inline-block';

  try {
    await loadData();
    document.getElementById('loginOverlay').style.display = 'none';
    document.getElementById('mainContent').style.display = 'block';
    document.getElementById('configKeyDisplay').textContent = '接口: ' + configKey.substring(0, 12) + '...';
  } catch (e) {
    showToast('连接失败: ' + e.message, 'error');
  }
}

// 查询当前 Token 命名空间下所有记录空间（含身份注册状态与同名接口分组）。
// /identity/spaces 在旧 /configs 基础上附带 identity（canonical / alias /
// unregistered）、canonicalKey 与 group（强线索分组，host 线索不参与，
// 避免误合并同一代理主机上的不同接口）。同名接口可一键归一。
let lastSpaces = [];
let lastGroups = [];
async function findConfigs() {
  const baseUrl = document.getElementById('loginUrl').value.trim().replace(/\\/+$/, '');
  const token = document.getElementById('loginToken').value.trim();
  const box = document.getElementById('configListResult');
  if (!baseUrl) { showToast('请先填写 Worker 地址', 'error'); return; }
  box.style.display = 'block';
  box.textContent = '查询中...';
  try {
    const res = await fetch(baseUrl + '/api/playback/sync/identity/spaces', {
      headers: token ? { 'X-WebHTV-Token': token } : {}
    });
    const text = await res.text();
    let data = {};
    try { data = text ? JSON.parse(text) : {}; } catch (e) {}
    if (!res.ok || !data.ok) {
      box.textContent = res.status === 404
        ? '查询失败 HTTP 404：Worker 版本过旧，请重新部署 (npm run deploy) 后再试'
        : '查询失败 HTTP ' + res.status + ': ' + (data.error || text.slice(0, 120));
      return;
    }
    const spaces = data.spaces || [];
    const groups = data.groups || [];
    if (!spaces.length) {
      box.textContent = '该 Token 命名空间下暂无记录。请先在 App 播放/阅读一次并开启 Webhook 上报，再回来查询。';
      return;
    }
    lastSpaces = spaces;
    lastGroups = groups;
    box.innerHTML = '';
    const title = document.createElement('div');
    title.textContent = '点击任意空间自动填入并连接；同名接口（地址线索匹配）可一键合并：';
    title.style.marginBottom = '6px';
    box.appendChild(title);
    const inGroup = new Set();
    for (const g of groups) {
      const members = spaces.filter((s) => s.group === g.id);
      if (!members.length) continue;
      members.forEach((s) => inGroup.add(s.configKey));
      box.appendChild(renderGroupHeader(g, members));
      for (const cfg of members) box.appendChild(renderSpaceRow(cfg));
    }
    for (const cfg of spaces) {
      if (inGroup.has(cfg.configKey)) continue;
      box.appendChild(renderSpaceRow(cfg));
    }
  } catch (e) {
    box.textContent = '查询失败: ' + e.message;
  }
}

function shortKey(key) {
  const text = String(key || '');
  return text.length > 13 ? text.substring(0, 13) + '…' : text;
}

// 组头：显示"疑似同一接口"，提供一键合并。目标优先选身份主空间（canonical），
// 其次选记录数最多的空间；已并入（alias）的空间不再是合并对象。
function renderGroupHeader(group, members) {
  const head = document.createElement('div');
  head.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:8px;margin:10px 0 2px;padding:6px 10px;border:1px solid var(--border,#333);border-radius:6px;font-size:12px;background:rgba(15,220,120,.08);';
  const label = document.createElement('div');
  label.textContent = '🔗 疑似同一接口 · ' + members.length + ' 个空间';
  head.appendChild(label);
  const target = pickGroupTarget(members);
  const mergeable = members.filter((s) => s.identity !== 'alias' && s.configKey !== target);
  if (mergeable.length >= 1) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn btn-sm';
    btn.textContent = '一键合并该组';
    btn.onclick = () => mergeSpacesConfirm(target, mergeable.map((s) => s.configKey), members[0].configType);
    head.appendChild(btn);
  }
  return head;
}

function pickGroupTarget(members) {
  const canonicals = members.filter((s) => s.identity === 'canonical');
  const pool = canonicals.length ? canonicals : members.filter((s) => s.identity !== 'alias');
  const sorted = (pool.length ? pool : members).slice().sort((a, b) => (b.items - a.items) || (b.latest - a.latest));
  return sorted[0].configKey;
}

function identityBadge(cfg) {
  if (cfg.identity === 'alias') {
    return { text: '已并入 ' + shortKey(cfg.canonicalKey), color: 'var(--text-muted,#888)', title: '已并入 ' + cfg.canonicalKey + '（源数据保留，可反向合并回滚）' };
  }
  if (cfg.identity === 'canonical') {
    return { text: '身份主空间', color: 'var(--success,#1dc981)', title: '此 key 是注册身份，设备同步时会直接命中' };
  }
  return { text: '未注册', color: 'var(--warning,#efaa17)', title: '此空间未注册身份（可能是旧协议或历史遗留），设备无法自动归一到它' };
}

function renderSpaceRow(cfg) {
  const isUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(cfg.configKey);
  const badge = identityBadge(cfg);
  const row = document.createElement('div');
  row.style.cssText = 'cursor:pointer;padding:8px 10px;margin:6px 0;border:1px solid var(--border,#333);border-radius:6px;' + (cfg.identity === 'alias' ? 'opacity:.65;' : '');
  // 三行布局：第一行 = 接口名 + 类型标签 + 记录数 + 身份徽章；第二行 = configKey；
  // 第三行 = 操作按钮（并入其他空间 / 清除）。按钮单独一行是因为登录卡片很窄，
  // 挤在第一行会把接口名压成省略号。configName 来自用户数据，必须用 textContent 注入。
  const head = document.createElement('div');
  head.style.cssText = 'display:flex;align-items:flex-start;gap:8px;flex-wrap:wrap;';
  const name = document.createElement('div');
  // 名字允许换行而不是省略号截断：按钮已移到下面独立一行，这里不再和按钮抢宽度。
  name.style.cssText = 'font-size:13px;font-weight:600;flex:1;min-width:0;word-break:break-word;';
  name.textContent = (cfg.name || (isUuid ? '未命名接口' : '旧版接口'))
    + '  ' + (isUuid ? '(新版 interfaceKey)' : '(旧版 sha256)')
    + '  · ' + cfg.items + ' 条'
    + (cfg.configType && cfg.configType !== 'vod' ? ' · ' + cfg.configType : '');
  const badgeEl = document.createElement('span');
  badgeEl.textContent = badge.text;
  badgeEl.title = badge.title || '';
  badgeEl.style.cssText = 'font-size:11px;color:' + badge.color + ';border:1px solid currentColor;border-radius:999px;padding:1px 8px;white-space:nowrap;';
  head.appendChild(name);
  head.appendChild(badgeEl);
  const actions = document.createElement('div');
  actions.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap;margin-top:8px;';
  // 已并入的空间不需要再合并；其余提供手动选择目标（兜底老空间）。
  if (cfg.identity !== 'alias') {
    const mergeBtn = document.createElement('button');
    mergeBtn.type = 'button';
    mergeBtn.className = 'btn btn-sm';
    mergeBtn.textContent = '并入其他空间';
    mergeBtn.onclick = (e) => { e.stopPropagation(); pickMergeTarget(cfg.configKey); };
    actions.appendChild(mergeBtn);
  }
  // 清除：一个按钮做两件事。已注册身份（canonical）连带注销身份，历史遗留的未注册
  // 空间只清记录——所以标签跟着这一行实际要做的事变，避免按钮默默多做一个动作。
  // 已并入（alias）的行绝不能注销：它指向的 canonical 才是真正的主空间，注销会把主
  // 空间一起带走，因此只清记录。这也是这个标志由前端按行决定、而不由服务端猜的原因。
  const forgetsIdentity = cfg.identity === 'canonical';
  const clearBtn = document.createElement('button');
  clearBtn.type = 'button';
  clearBtn.className = 'btn btn-sm btn-danger';
  clearBtn.textContent = forgetsIdentity ? '清除并注销' : '清除';
  clearBtn.title = forgetsIdentity
    ? '清空该接口的全部记录，并从身份注册表中注销该身份'
    : '清空该空间的全部记录（该空间未注册身份，无身份可注销）';
  clearBtn.onclick = (e) => { e.stopPropagation(); clearSpaceConfirm(cfg); };
  actions.appendChild(clearBtn);
  const keyLine = document.createElement('div');
  keyLine.style.cssText = 'font-family:monospace;font-size:11px;color:var(--text-muted,#888);word-break:break-all;margin-top:3px;';
  keyLine.textContent = cfg.configKey;
  row.appendChild(head);
  row.appendChild(keyLine);
  row.appendChild(actions);
  row.onclick = () => {
    document.getElementById('loginConfigKey').value = cfg.configKey;
    document.getElementById('configListResult').style.display = 'none';
    doLogin();
  };
  return row;
}

// 手动合并：从其余空间中选择一个作为合并目标（仅同 configType，排除已并入的）。
function pickMergeTarget(sourceKey) {
  const source = (lastSpaces || []).find((s) => s.configKey === sourceKey);
  if (!source) { showToast('空间信息已过期，请重新查询', 'error'); return; }
  const candidates = (lastSpaces || []).filter((s) => s.configType === source.configType
    && s.configKey !== sourceKey
    && s.configKey !== source.canonicalKey
    && s.identity !== 'alias');
  if (!candidates.length) { showToast('没有可并入的目标空间', 'info'); return; }
  const node = document.createElement('div');
  const h3 = document.createElement('h3');
  h3.textContent = '并入哪个空间？';
  node.appendChild(h3);
  const p = document.createElement('p');
  const strong = document.createElement('strong');
  strong.textContent = shortKey(sourceKey) + '（' + source.items + ' 条）';
  p.appendChild(strong);
  p.appendChild(document.createTextNode(' 并入：'));
  node.appendChild(p);
  const list = document.createElement('div');
  list.style.cssText = 'max-height:40vh;overflow-y:auto;';
  for (const s of candidates) {
    const item = document.createElement('div');
    item.style.cssText = 'cursor:pointer;padding:8px 10px;margin:6px 0;border:1px solid var(--border,#333);border-radius:6px;';
    item.textContent = shortKey(s.configKey) + ' · ' + s.items + ' 条'
      + (s.name ? ' · ' + s.name : '')
      + (s.identity === 'canonical' ? ' · 身份主空间' : '');
    item.onclick = () => mergeSpacesConfirm(s.configKey, [sourceKey], s.configType);
    list.appendChild(item);
  }
  node.appendChild(list);
  node.appendChild(modalCancelActions());
  showModalNode(node);
}

function mergeSpacesConfirm(targetKey, sourceKeys, configType) {
  const target = (lastSpaces || []).find((s) => s.configKey === targetKey);
  const sourcesText = sourceKeys.map((key) => {
    const s = (lastSpaces || []).find((item) => item.configKey === key);
    return shortKey(key) + '（' + (s ? s.items : '?') + ' 条）';
  }).join('、');
  const node = document.createElement('div');
  const h3 = document.createElement('h3');
  h3.textContent = '合并记录空间';
  node.appendChild(h3);
  const p1 = document.createElement('p');
  p1.appendChild(document.createTextNode('把 '));
  const sourceStrong = document.createElement('strong');
  sourceStrong.textContent = sourcesText;
  p1.appendChild(sourceStrong);
  p1.appendChild(document.createTextNode(' 并入 '));
  const targetStrong = document.createElement('strong');
  targetStrong.textContent = shortKey(targetKey) + '（' + (target ? target.items : '?') + ' 条）';
  p1.appendChild(targetStrong);
  p1.appendChild(document.createTextNode('。'));
  node.appendChild(p1);
  const p2 = document.createElement('p');
  p2.textContent = '规则：两边都有的影片按较新进度保留；删除记录一并迁移；源 key 之后自动路由到目标空间。完成后请在每台设备上各同步一次即可互通。此操作可回滚（反向合并）。';
  node.appendChild(p2);
  const actions = document.createElement('div');
  actions.className = 'modal-actions';
  const cancel = document.createElement('button');
  cancel.className = 'btn';
  cancel.textContent = '取消';
  cancel.onclick = hideModal;
  const ok = document.createElement('button');
  ok.className = 'btn btn-danger';
  ok.textContent = '确认合并';
  ok.onclick = () => doMerge(targetKey, sourceKeys, configType);
  actions.appendChild(cancel);
  actions.appendChild(ok);
  node.appendChild(actions);
  showModalNode(node);
}

function modalCancelActions() {
  const actions = document.createElement('div');
  actions.className = 'modal-actions';
  const cancel = document.createElement('button');
  cancel.className = 'btn';
  cancel.textContent = '取消';
  cancel.onclick = hideModal;
  actions.appendChild(cancel);
  return actions;
}

async function doMerge(targetKey, sourceKeys, configType) {
  const baseUrl = document.getElementById('loginUrl').value.trim().replace(/\\/+$/, '');
  const token = document.getElementById('loginToken').value.trim();
  hideModal();
  try {
    const res = await fetch(baseUrl + '/api/playback/sync/identity/merge', {
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { 'X-WebHTV-Token': token } : {}),
      body: JSON.stringify({ configType: configType || 'vod', targetKey: targetKey, sourceKeys: sourceKeys })
    });
    const text = await res.text();
    let data = {};
    try { data = text ? JSON.parse(text) : {}; } catch (e) {}
    if (!res.ok || !data.ok) {
      showToast('合并失败 HTTP ' + res.status + ': ' + (data.error || text.slice(0, 120)), 'error');
      return;
    }
    const mergedCount = (data.merged || []).length;
    showToast(mergedCount
      ? '已合并 ' + mergedCount + ' 个空间 → ' + shortKey(data.canonical) + '。请在每台设备上各同步一次完成互通。'
      : '所选空间已在同一身份下，无需合并', mergedCount ? 'success' : 'info');
    findConfigs();
  } catch (e) {
    showToast('合并失败: ' + e.message, 'error');
  }
}

// 清除某个接口空间。危险操作：先弹窗写清接口名、条数与后果，确认后才执行。
// 请求只带 Token，不发送 X-WebHTV-Config-Key，服务端也就不会做身份归一——避免
// 误清到该行"已并入"指向的真实主空间。
//
// 已注册身份（canonical）的行会连带注销身份，弹窗必须把两件事都写出来；未注册空间
// 只清记录。注销是可自愈的（设备仍在用就会重新注册），记录删除才是不可恢复的那半。
function clearSpaceConfirm(cfg) {
  const forgetsIdentity = cfg.identity === 'canonical';
  const node = document.createElement('div');
  const h3 = document.createElement('h3');
  h3.textContent = forgetsIdentity ? '⚠️ 清除该接口的记录并注销身份' : '⚠️ 清除该接口的全部记录';
  node.appendChild(h3);
  const p1 = document.createElement('p');
  p1.appendChild(document.createTextNode('将删除 '));
  const strong = document.createElement('strong');
  strong.textContent = (cfg.name || '未命名接口') + '（' + cfg.items + ' 条）';
  p1.appendChild(strong);
  p1.appendChild(document.createTextNode(' 的全部观影记录，此操作不可恢复。'));
  node.appendChild(p1);
  const p2 = document.createElement('p');
  if (forgetsIdentity) {
    p2.textContent = '该空间是「身份主空间」，同时会从身份注册表中注销这个身份（连带指向它的别名），'
      + '所以注销后这一行会从列表消失。播放记录不会因此额外删除。';
    node.appendChild(p2);
    const p3 = document.createElement('p');
    p3.textContent = '注销是可自愈的：若仍有设备在使用该接口，它下次同步会重新注册这个身份，这一行可能重新出现。';
    node.appendChild(p3);
  } else {
    p2.textContent = '该空间未注册身份，因此只清记录，没有可注销的身份条目。';
    node.appendChild(p2);
  }
  const p4 = document.createElement('p');
  p4.textContent = '同时写入删除指令：仍在使用该接口的设备下次同步时会一并删除这些记录。';
  node.appendChild(p4);
  const keyLine = document.createElement('div');
  keyLine.style.cssText = 'font-family:monospace;font-size:11px;color:var(--text-muted,#888);word-break:break-all;margin:6px 0;';
  keyLine.textContent = cfg.configKey;
  node.appendChild(keyLine);
  const actions = document.createElement('div');
  actions.className = 'modal-actions';
  const cancel = document.createElement('button');
  cancel.className = 'btn';
  cancel.textContent = '取消';
  cancel.onclick = hideModal;
  const ok = document.createElement('button');
  ok.className = 'btn btn-danger';
  ok.textContent = forgetsIdentity ? '确认清除并注销' : '确认清除';
  ok.onclick = () => doClearSpace(cfg);
  actions.appendChild(cancel);
  actions.appendChild(ok);
  node.appendChild(actions);
  showModalNode(node);
}

async function doClearSpace(cfg) {
  const baseUrl = document.getElementById('loginUrl').value.trim().replace(/\\/+$/, '');
  const token = document.getElementById('loginToken').value.trim();
  const forgetsIdentity = cfg.identity === 'canonical';
  hideModal();
  try {
    const res = await fetch(baseUrl + '/api/playback/sync/maintenance', {
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { 'X-WebHTV-Token': token } : {}),
      body: JSON.stringify({
        op: 'adminClearSpace',
        configType: cfg.configType || 'vod',
        configKey: cfg.configKey,
        forgetIdentity: forgetsIdentity
      })
    });
    const text = await res.text();
    let data = {};
    try { data = text ? JSON.parse(text) : {}; } catch (e) {}
    if (!res.ok || !data.ok) {
      showToast('清除失败 HTTP ' + res.status + ': ' + (data.error || text.slice(0, 120)), 'error');
      return;
    }
    showToast(clearSpaceResultText(data, forgetsIdentity), data.forgetError ? 'error' : 'success');
    findConfigs();
  } catch (e) {
    showToast('清除失败: ' + e.message, 'error');
  }
}

// 记录已删、身份注销是另一个表上的第二次写入，两者不能同事务，所以服务端可能只完成
// 前半段。这种情况必须如实告诉用户「记录已清、身份没注销」，不能报成完全成功。
function clearSpaceResultText(data, forgetsIdentity) {
  const deleted = data.deletedRows || 0;
  if (data.forgetError) {
    return '记录已清除 ' + deleted + ' 条，但注销身份失败：' + data.forgetError + '（可稍后重试）';
  }
  const forgotten = (data.forgotten || []).length;
  if (forgetsIdentity && forgotten) {
    return deleted
      ? '已清除 ' + deleted + ' 条记录并注销身份（删除指令将同步到设备）'
      : '该空间没有记录，已注销身份';
  }
  if (forgetsIdentity) {
    return deleted
      ? '已清除 ' + deleted + ' 条记录（该身份已不在注册表中）'
      : '该空间没有记录，身份也已不在注册表中';
  }
  return deleted
    ? '已清除 ' + deleted + ' 条记录（删除指令将同步到设备）'
    : '该空间没有记录，无需清除';
}

// ============ API 调用（适配 Durable Object 后端） ============
function authHeaders() {
  return {
    'Content-Type': 'application/json',
    'X-WebHTV-Token': state.token,
    'X-WebHTV-Config-Key': state.configKey
  };
}

async function fetchJSON(path, options = {}) {
  const url = state.baseUrl + path;
  const res = await fetch(url, {
    ...options,
    headers: { ...authHeaders(), ...options.headers }
  });
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch (e) { throw new Error('HTTP ' + res.status + ': ' + text.slice(0, 200)); }
  if (!res.ok) throw new Error(data.error || data.message || ('HTTP ' + res.status));
  return data;
}

async function loadData() {
  try {
    // 并行拉取状态和增量记录
    const [statusData, syncData] = await Promise.all([
      fetchJSON('/api/playback/sync/status'),
      pullAllChanges()
    ]);
    state.status = statusData;
    // 只展示 action=upsert 的记录，过滤掉 delete 墓碑
    state.records = syncData.filter(c => c.action !== 'delete');
    applyFilter();
    updateStatus(true);
    renderStats();
    renderRecords();
  } catch (e) {
    updateStatus(false);
    showToast('加载失败: ' + e.message, 'error');
    throw e;
  }
}

// 基于游标分页拉取全部增量变更。
// 去重通过后端物理删除 + 墓碑实现，pull 返回的 upsert/delete 已是最终一致的数据，
// APP 与 dashboard 收到完全相同的增量，无需前端额外过滤。
async function pullAllChanges() {
  const all = [];
  let since = 0;
  for (let i = 0; i < 20; i++) {
    const data = await fetchJSON('/api/playback/sync?since=' + since + '&limit=1000');
    if (data.changes && data.changes.length) all.push(...data.changes);
    since = Number(data.nextSince || 0);
    if (!data.hasMore) break;
  }
  return all;
}

function updateStatus(ok) {
  const badge = document.getElementById('statusBadge');
  const text = document.getElementById('statusText');
  if (ok) { badge.classList.remove('error'); text.textContent = '服务在线'; }
  else { badge.classList.add('error'); text.textContent = '连接异常'; }
}

function renderStats() {
  if (!state.status) return;
  const s = state.status;
  document.getElementById('totalCount').textContent = s.items ?? 0;
  document.getElementById('tombstoneCount').textContent = s.tombstones ?? 0;
  document.getElementById('nextSince').textContent = s.nextSince ?? '-';
  document.getElementById('eventCount').textContent = s.events ?? 0;
}

function applyFilter() {
  const q = state.search.toLowerCase();
  if (!q) { state.filtered = [...state.records]; }
  else {
    state.filtered = state.records.filter(r =>
      (r.vodName || '').toLowerCase().includes(q) ||
      (r.siteKey || '').toLowerCase().includes(q) ||
      (r.siteName || '').toLowerCase().includes(q)
    );
  }
  applySort();
  state.page = 1;
}

// 排序：点击列头切换排序字段和方向
function sortBy(key) {
  if (state.sortKey === key) {
    state.sortDir = state.sortDir === 'asc' ? 'desc' : 'asc';
  } else {
    state.sortKey = key;
    state.sortDir = (key === 'updatedAt') ? 'desc' : 'asc';
  }
  applySort();
  renderRecords();
  updateSortIndicators();
}

// 计算单条记录在当前排序键下的比较值
function getSortValue(r, key) {
  switch (key) {
    case 'vodName':  return (r.vodName || '').toLowerCase();
    case 'siteKey':  return (r.siteName || r.siteKey || '').toLowerCase();
    case 'progress': return r.progress || 0;
    case 'status':   return (r.completed || (r.progress && r.progress >= 0.95)) ? 1 : 0;
    case 'updatedAt':return r.updatedAt || r.updated_at || 0;
    default:         return 0;
  }
}

function applySort() {
  const key = state.sortKey;
  const dir = state.sortDir === 'asc' ? 1 : -1;
  state.filtered.sort((a, b) => {
    const va = getSortValue(a, key);
    const vb = getSortValue(b, key);
    if (va < vb) return -1 * dir;
    if (va > vb) return  1 * dir;
    return 0;
  });
}

// 更新表头排序箭头指示器
function updateSortIndicators() {
  document.querySelectorAll('th.sortable').forEach(th => {
    const arrow = th.querySelector('.sort-arrow');
    if (th.dataset.sortkey === state.sortKey) {
      th.classList.add('sorted');
      arrow.textContent = state.sortDir === 'asc' ? '↑' : '↓';
    } else {
      th.classList.remove('sorted');
      arrow.textContent = '↕';
    }
  });
}

function renderRecords() {
  const body = document.getElementById('recordsBody');
  const data = state.filtered;

  if (!data.length) {
    body.innerHTML = '<tr><td colspan="6" class="empty-state"><div class="icon">📭</div><div>暂无观影记录' + (state.search ? '（没有匹配的结果）' : '') + '</div></td></tr>';
    document.getElementById('pagination').style.display = 'none';
    return;
  }

  const totalPages = Math.ceil(data.length / state.pageSize);
  if (state.page > totalPages) state.page = totalPages;
  const start = (state.page - 1) * state.pageSize;
  const pageData = data.slice(start, start + state.pageSize);

  body.innerHTML = pageData.map(r => {
    const progress = r.progress ? Math.round(r.progress * 100) : 0;
    const isCompleted = r.completed || progress >= 95;
    const duration = r.durationMs ? formatDuration(r.durationMs) : '-';
    const position = r.positionMs ? formatDuration(r.positionMs) : '-';
    const time = r.updatedAt || r.updated_at || 0;
    return \`
      <tr>
        <td>
          <div class="vod-name">\${escape(r.vodName || '未知影片')}</div>
          <div class="vod-meta">\${escape(r.episodeName || '')} · \${position} / \${duration}</div>
        </td>
        <td><span class="site-badge">\${escape(r.siteKey || '-')}</span></td>
        <td>
          <div class="progress-bar"><div class="progress-fill" style="width: \${progress}%; background: \${progress >= 95 ? 'var(--gradient-2)' : 'var(--gradient-1)'};"></div></div>
          <div class="progress-text">\${progress}%</div>
        </td>
        <td>\${isCompleted ? '<span class="completed-tag">已看完</span>' : '<span class="playing-tag">观看中</span>'}</td>
        <td class="time-cell"><div class="date">\${formatDate(time)}</div><div class="relative">\${relativeTime(time)}</div></td>
        <td><button class="btn btn-sm btn-danger" onclick="deleteRecord('\${escape(r.historyKey || '')}', '\${escape(r.siteKey || '')}', '\${escape(r.vodId || '')}')" title="删除">🗑️</button></td>
      </tr>
    \`;
  }).join('');

  renderPagination(totalPages);
  updateSortIndicators();
}

function renderPagination(totalPages) {
  const pagination = document.getElementById('pagination');
  const info = document.getElementById('paginationInfo');
  const controls = document.getElementById('paginationControls');
  const data = state.filtered;
  if (totalPages <= 1) { pagination.style.display = 'none'; return; }
  pagination.style.display = 'flex';
  const start = (state.page - 1) * state.pageSize + 1;
  const end = Math.min(state.page * state.pageSize, data.length);
  info.textContent = \`显示 \${start}-\${end} / 共 \${data.length} 条\`;
  let html = '';
  html += \`<button class="page-btn" \${state.page === 1 ? 'disabled' : ''} onclick="goPage(\${state.page - 1})">‹</button>\`;
  const maxShown = 7;
  let startPage = Math.max(1, state.page - 3);
  let endPage = Math.min(totalPages, startPage + maxShown - 1);
  startPage = Math.max(1, endPage - maxShown + 1);
  if (startPage > 1) { html += \`<button class="page-btn" onclick="goPage(1)">1</button>\`; if (startPage > 2) html += '<span style="color:var(--text-muted)">...</span>'; }
  for (let i = startPage; i <= endPage; i++) html += \`<button class="page-btn \${i === state.page ? 'active' : ''}" onclick="goPage(\${i})">\${i}</button>\`;
  if (endPage < totalPages) { if (endPage < totalPages - 1) html += '<span style="color:var(--text-muted)">...</span>'; html += \`<button class="page-btn" onclick="goPage(\${totalPages})">\${totalPages}</button>\`; }
  html += \`<button class="page-btn" \${state.page === totalPages ? 'disabled' : ''} onclick="goPage(\${state.page + 1})">›</button>\`;
  controls.innerHTML = html;
}

function goPage(n) { state.page = n; renderRecords(); }

// 删除单条记录 — 管理端点按权威身份物理删行（服务器时间），并写墓碑同步给设备
async function deleteRecord(historyKey, siteKey, vodId) {
  if (!historyKey && (!siteKey || !vodId)) { showToast('无法删除：缺少唯一标识', 'error'); return; }
  if (!confirm('确定要删除这条记录吗？')) return;
  const payload = { op: 'adminDeleteItem', configKey: state.configKey };
  if (historyKey) payload.historyKey = historyKey;
  if (siteKey) payload.siteKey = siteKey;
  if (vodId) payload.vodId = vodId;
  try {
    const res = await fetchJSON('/api/playback/sync/maintenance', { method: 'POST', body: JSON.stringify(payload) });
    showToast(res.deletedRows > 0 ? '删除成功（已同步删除指令到设备）' : '服务端未找到该记录，已刷新', 'success');
    loadData();
  } catch (e) { showToast('删除失败: ' + e.message, 'error'); }
}

// 清空全部 — 发送 scope=all 的删除墓碑，并注销该接口的身份（与行内「清除并注销」一致）
async function confirmClearAll() {
  const count = state.filtered.length;
  if (!count) { showToast('没有可清空的记录', 'info'); return; }
  showModal(\`
    <h3>⚠️ 清空全部记录并注销身份</h3>
    <p>即将删除当前接口下所有 <strong>\${count}</strong> 条观影记录，同时从身份注册表中注销该接口的身份，此操作不可恢复。</p>
    <p>注销是可自愈的：若仍有设备在使用该接口，它下次同步会重新注册这个身份，条目可能重新出现——最坏情况只是重现，不会损坏同步。</p>
    <div class="modal-actions">
      <button class="btn" onclick="hideModal()">取消</button>
      <button class="btn btn-danger" onclick="clearAll()">确认清空并注销</button>
    </div>
  \`);
}

async function clearAll() {
  hideModal();
  try {
    const res = await fetchJSON('/api/playback/sync/maintenance', {
      method: 'POST',
      // forgetIdentity：与行内「清除并注销」一致，清记录的同时注销该接口身份。
      // 这里的 configKey 已经过身份归一，服务端解析到的是 canonical 本身，注销安全。
      body: JSON.stringify({ op: 'adminClearAll', configKey: state.configKey, forgetIdentity: true })
    });
    if (res.forgetError) {
      showToast('已清空 ' + (res.deletedRows || 0) + ' 条记录，但注销身份失败：' + res.forgetError + '（可稍后重试）', 'error');
    } else {
      showToast('已清空 ' + (res.deletedRows || 0) + ' 条记录并注销身份（删除指令将同步到设备）', 'success');
    }
    loadData();
  } catch (e) { showToast('清空失败: ' + e.message, 'error'); }
}

// 清理当前接口的删除墓碑 — 仅清当前 configKey，与卡片计数口径一致。
// 按保留期清理：只删除比保留期更早的墓碑，最近这几十天的删除指令照旧留给设备，
// 因此不存在「刚删完就清墓碑」导致未同步设备把记录传回来（复活）的窗口。
let purgeTombstoneDays = 30;

function confirmPurgeTombstones() {
  const count = parseInt(document.getElementById('tombstoneCount').textContent, 10) || 0;
  if (!count) { showToast('当前接口没有可清理的删除墓碑', 'info'); return; }
  purgeTombstoneDays = 30;
  showModal(\`
    <h3>⚠️ 清理删除墓碑</h3>
    <p>当前接口有 <strong>\${count}</strong> 条删除墓碑（服务端的删除同步历史，只保留最近 90 天）。墓碑是「这条记录已被删除」的指令，用来自动清掉其他设备上残留的同名记录。</p>
    <p>选择保留期后只清除更早的墓碑，保留期内的删除指令仍会照常下发给设备：</p>
    <div style="display:flex; gap:8px; flex-wrap:wrap; margin:12px 0;">
      \${[30, 60, 90].map((d) => \`<button class="btn day-btn\${d === purgeTombstoneDays ? ' active' : ''}" data-days="\${d}" onclick="selectPurgeTombstoneDays(\${d})">保留 \${d} 天</button>\`).join('')}
    </div>
    <p id="purgeTombstoneHint" style="color: var(--text-muted);"></p>
    <p style="color: var(--warning);">保留期内没有同步过的设备收不到这些删除指令，其本地残留记录之后可能重新同步回服务端。</p>
    <div class="modal-actions">
      <button class="btn" onclick="hideModal()">取消</button>
      <button class="btn btn-danger" onclick="purgeTombstones()">确认清理</button>
    </div>
  \`);
  selectPurgeTombstoneDays(purgeTombstoneDays);
}

function selectPurgeTombstoneDays(days) {
  purgeTombstoneDays = days;
  document.querySelectorAll('.day-btn').forEach((btn) => {
    const on = Number(btn.dataset.days) === days;
    btn.classList.toggle('active', on);
    btn.style.background = on ? 'var(--accent)' : '';
    btn.style.borderColor = on ? 'var(--accent)' : '';
    btn.style.color = on ? '#fff' : '';
  });
  const hint = document.getElementById('purgeTombstoneHint');
  if (hint) hint.textContent = '保留最近 ' + days + ' 天的删除指令，清除更早的墓碑。'
    + (days >= 90
      ? ' 服务端上限为 90 天，此项等于清掉全部存量墓碑。'
      : ' 保留期内的删除同步历史照常下发，未在保留期内同步过的设备才可能漏掉删除。');
}

async function purgeTombstones() {
  hideModal();
  const days = purgeTombstoneDays;
  const before = Date.now() - days * 86400000;
  try {
    const res = await fetchJSON('/api/playback/sync/maintenance', {
      method: 'POST',
      body: JSON.stringify({
        op: 'purgeTombstones',
        configKey: state.configKey,
        beforeDeletedAt: before
      })
    });
    showToast('已清理 ' + (res.purged || 0) + ' 条 ' + days + ' 天前的删除墓碑', 'success');
    loadData();
  } catch (e) { showToast('清理失败: ' + e.message, 'error'); }
}

// 活跃记录卡片 — 滚动到记录列表并聚焦搜索框
function scrollToRecords() {
  const section = document.getElementById('recordsSection');
  if (section) section.scrollIntoView({ behavior: 'smooth', block: 'start' });
  const search = document.getElementById('searchInput');
  if (search) setTimeout(() => search.focus({ preventScroll: true }), 350);
}

// 同步游标卡片 — 纯说明弹窗（游标不可在服务端重置，见文案）
function showCursorInfo() {
  const value = document.getElementById('nextSince').textContent || '0';
  showModal(\`
    <h3>📊 同步游标说明</h3>
    <p>当前值：<strong>\${escape(value)}</strong></p>
    <p>这是服务端的全局变更序号：每新增一条进度或删除墓碑就加一。每台设备本地各自记住自己拉取到的位置，下次同步只取比自己记住的序号更大的变更，因此数字大小只反映历史变更总量，不影响性能。</p>
    <p style="color: var(--warning);">它不能在服务端重置：清零后新变更会从小序号重新编号，而设备仍在等待更大的序号，将永远收不到新同步且没有任何报错。只有所有设备重新添加同步配置、或更换 Token 时才会安全归零。</p>
    <div class="modal-actions">
      <button class="btn" onclick="hideModal()">我知道了</button>
    </div>
  \`);
}

// 去重记录卡片 — 按天清理事件去重记录（playback_events）
let purgeEventDays = 7;

function confirmPurgeEvents() {
  purgeEventDays = 7;
  showModal(\`
    <h3>⏱️ 清理事件去重记录</h3>
    <p>「事件去重记录」只用于判断同一条<strong>删除上报</strong>是否已处理过（进度上报不再产生去重记录），<strong>不含任何观影记录</strong>。清理它不影响进度同步、删除墓碑与设备拉取内容。</p>
    <p>服务端每天会自动清理 30 天前的记录。此处选择保留期，将立即清除全部接口中更早的去重记录：</p>
    <div style="display:flex; gap:8px; flex-wrap:wrap; margin:12px 0;">
      \${[1, 3, 7, 15, 30].map((d) => \`<button class="btn day-btn\${d === purgeEventDays ? ' active' : ''}" data-days="\${d}" onclick="selectPurgeEventDays(\${d})">\${d} 天</button>\`).join('')}
    </div>
    <p id="purgeEventHint" style="color: var(--text-muted);">保留最近 7 天的去重记录。</p>
    <div class="modal-actions">
      <button class="btn" onclick="hideModal()">取消</button>
      <button class="btn btn-danger" onclick="purgeEvents()">确认清理</button>
    </div>
  \`);
  selectPurgeEventDays(purgeEventDays);
}

function selectPurgeEventDays(days) {
  purgeEventDays = days;
  document.querySelectorAll('.day-btn').forEach((btn) => {
    const on = Number(btn.dataset.days) === days;
    btn.classList.toggle('active', on);
    btn.style.background = on ? 'var(--accent)' : '';
    btn.style.borderColor = on ? 'var(--accent)' : '';
    btn.style.color = on ? '#fff' : '';
  });
  const hint = document.getElementById('purgeEventHint');
  if (hint) hint.textContent = '保留最近 ' + days + ' 天的去重记录，清除更早的。'
    + (days <= 3 ? ' 注意：窗口较短，异常重发事件的兜底时间会同步缩短。' : '');
}

async function purgeEvents() {
  hideModal();
  const days = purgeEventDays;
  const before = Date.now() - days * 86400000;
  let deleted = 0;
  let rounds = 0;
  try {
    // 服务端单次最多 20000 行，免费版每日写入额度 10 万行，故最多 3 轮后停手交由用户决定
    while (rounds < 3) {
      const res = await fetchJSON('/api/playback/sync/maintenance', {
        method: 'POST',
        body: JSON.stringify({ op: 'purgeEvents', beforeReceivedAt: before, limit: 20000 })
      });
      deleted += res.deleted || 0;
      rounds++;
      if (!res.hasMore) break;
    }
    showToast('已清理 ' + deleted + ' 条 ' + days + ' 天前的事件去重记录'
      + (rounds >= 3 ? '（仍有剩余，可再次点击继续）' : ''), 'success');
    loadData();
  } catch (e) { showToast('清理失败: ' + e.message, 'error'); }
}

function showModal(html) {
  const modal = document.getElementById('modal');
  modal.innerHTML = '<div class="modal-overlay"><div class="modal">' + html + '</div></div>';
  modal.style.display = 'block';
}
// 合并确认框用 DOM API 构建（闭包传参，避免内联 onclick 的引号转义问题）。
function showModalNode(node) {
  const modal = document.getElementById('modal');
  modal.innerHTML = '<div class="modal-overlay"><div class="modal"></div></div>';
  const body = modal.querySelector('.modal');
  if (body) body.appendChild(node);
  modal.style.display = 'block';
}
function hideModal() { document.getElementById('modal').style.display = 'none'; }

let toastTimer;
function showToast(msg, type = 'info') {
  let toast = document.querySelector('.toast');
  if (toast) toast.remove();
  toast = document.createElement('div');
  toast.className = 'toast ' + type;
  toast.textContent = msg;
  document.body.appendChild(toast);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.remove(), 3000);
}

function escape(s) { const div = document.createElement('div'); div.textContent = String(s ?? ''); return div.innerHTML; }
function formatDuration(ms) {
  if (!ms || ms <= 0) return '-';
  const h = Math.floor(ms / 3600000), m = Math.floor((ms % 3600000) / 60000), s = Math.floor((ms % 60000) / 1000);
  if (h > 0) return \`\${h}h \${m}m\`;
  if (m > 0) return \`\${m}m \${s}s\`;
  return \`\${s}s\`;
}
function formatDate(ts) {
  if (!ts) return '-';
  const d = new Date(ts), now = new Date();
  const isToday = d.toDateString() === now.toDateString();
  const y = new Date(now); y.setDate(y.getDate() - 1);
  const isYesterday = d.toDateString() === y.toDateString();
  const time = d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  if (isToday) return '今天 ' + time;
  if (isYesterday) return '昨天 ' + time;
  return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + time;
}
function relativeTime(ts) {
  if (!ts) return '';
  const diff = Date.now() - ts;
  const m = Math.floor(diff / 60000);
  if (m < 1) return '刚刚';
  if (m < 60) return m + ' 分钟前';
  const h = Math.floor(m / 60);
  if (h < 24) return h + ' 小时前';
  const d = Math.floor(h / 24);
  if (d < 30) return d + ' 天前';
  return Math.floor(d / 30) + ' 个月前';
}

document.getElementById('searchInput').addEventListener('input', (e) => {
  state.search = e.target.value; applyFilter(); renderRecords();
});

// 初始化
loadCredentials();
// 如果有已保存的凭证，自动尝试连接（Token 为空但 URL+ConfigKey 有值也自动连接）
const saved = (() => { try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}'); } catch (e) { return {}; } })();
if (saved.baseUrl && saved.configKey) {
  state.baseUrl = saved.baseUrl; state.token = saved.token || ''; state.configKey = saved.configKey;
  doLogin().then(() => {
    document.getElementById('loginOverlay').style.display = 'none';
    document.getElementById('mainContent').style.display = 'block';
    document.getElementById('configKeyDisplay').textContent = '接口: ' + state.configKey.substring(0, 12) + '...';
    // 恢复 Token 徽章
    const tokenBadge = document.getElementById('tokenBadge');
    if (tokenBadge) tokenBadge.style.display = state.token ? 'none' : 'inline-block';
  }).catch(() => {});
}
</script>
</body>
</html>`;

export function getDashboardResponse() {
  return new Response(DASHBOARD_HTML, {
    status: 200,
    headers: {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-store'
    }
  });
}

export default { getDashboardResponse };
