// WebHTV 观影记录同步管理控制台 — 适配 webhtv-remote-cloudflare (Durable Object + SQLite)
// 与旧版 KV 版本的关键差异：
//   1. 认证：X-WebHTV-Token (必填) + X-WebHTV-Config-Key (必填)，token 由用户自行生成，不写入环境变量
//   2. 数据接口：统一使用 /api/playback/sync，GET 拉取增量、POST 写入/删除
//   3. 删除：通过 POST 发送 event=playback.deleted 的墓碑事件，而非 DELETE 方法
//   4. 统计：使用 /api/playback/sync/status 而非 /api/stats
//   5. 分页：基于单调游标 (since/nextSince)，而非简单的 maxItems 列表

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

  .login-overlay { position: fixed; inset: 0; background: var(--bg); display: flex; align-items: center; justify-content: center; z-index: 50; }
  .login-card { background: var(--bg-elevated); border: 1px solid var(--border); border-radius: 16px; padding: 36px; max-width: 440px; width: 90%; }
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

  /* -------- 同标题去重：设置卡片 + 开关 -------- */
  .settings-card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 20px 24px;
    margin-bottom: 28px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 24px;
    flex-wrap: wrap;
    position: relative;
    overflow: hidden;
  }
  .settings-card::before {
    content: '';
    position: absolute;
    top: 0; left: 0; right: 0; height: 3px;
    background: linear-gradient(90deg, var(--accent) 0%, #22d3ee 100%);
  }
  .settings-left {
    display: flex; align-items: center; gap: 16px;
    min-width: 0; flex: 1 1 420px;
  }
  .settings-icon {
    flex-shrink: 0;
    width: 44px; height: 44px; border-radius: 12px;
    background: rgba(108, 124, 255, 0.15);
    color: var(--accent);
    display: flex; align-items: center; justify-content: center;
    font-size: 22px;
    box-shadow: inset 0 0 0 1px rgba(108, 124, 255, 0.25);
  }
  .settings-text { min-width: 0; }
  .settings-title { font-size: 15px; font-weight: 600; margin-bottom: 4px; display: flex; align-items: center; gap: 8px; }
  .settings-subtitle { font-size: 12px; color: var(--text-muted); line-height: 1.6; max-width: 560px; }
  .settings-subtitle .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; background: var(--bg); padding: 1px 6px; border-radius: 4px; border: 1px solid var(--border); color: var(--text-secondary); font-size: 11px; }
  .switch-wrap { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; flex-shrink: 0; }
  .switch-label-state { font-size: 11px; font-weight: 600; letter-spacing: 0.4px; text-transform: uppercase; }
  .switch-label-state.on  { color: var(--success); }
  .switch-label-state.off { color: var(--text-muted); }

  /* iOS 风格 switch，高对比双态视觉反馈 */
  .switch {
    --w: 56px;
    --h: 30px;
    --p: 3px;
    --knob: calc(var(--h) - var(--p) * 2);
    position: relative;
    display: inline-block;
    width: var(--w);
    height: var(--h);
    flex-shrink: 0;
  }
  .switch input { opacity: 0; width: 0; height: 0; }
  .slider {
    position: absolute; cursor: pointer;
    inset: 0;
    background: var(--border);
    border: 1px solid rgba(255,255,255,0.04);
    border-radius: 999px;
    transition: background 0.25s ease, box-shadow 0.25s ease, border-color 0.25s ease;
    box-shadow: inset 0 1px 2px rgba(0,0,0,0.3);
  }
  .slider::before {
    content: '';
    position: absolute;
    height: var(--knob);
    width: var(--knob);
    left: var(--p);
    top: 50%;
    transform: translateY(-50%);
    background: white;
    border-radius: 50%;
    box-shadow: 0 2px 6px rgba(0,0,0,0.35), 0 0 0 1px rgba(0,0,0,0.06);
    transition: transform 0.25s cubic-bezier(.4,.2,.2,1), width 0.15s ease;
  }
  .switch input:checked + .slider {
    background: linear-gradient(135deg, #6c7cff 0%, #22d3ee 100%);
    border-color: rgba(108, 124, 255, 0.45);
    box-shadow: 0 0 0 3px rgba(108, 124, 255, 0.15), inset 0 1px 2px rgba(0,0,0,0.15);
  }
  .switch input:checked + .slider::before {
    transform: translate(calc(var(--w) - var(--knob) - var(--p) * 2), -50%);
  }
  .switch input:focus-visible + .slider {
    box-shadow: 0 0 0 3px rgba(108, 124, 255, 0.3), inset 0 1px 2px rgba(0,0,0,0.3);
  }
  .switch input:disabled + .slider { cursor: not-allowed; opacity: 0.55; }
  .stat-effective-hint { font-size: 11px; color: var(--text-muted); margin-top: 2px; }

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
        <input type="text" id="loginConfigKey" class="form-input" placeholder="接口 URL 或 configKey (sha256)" value="" style="flex:1;">
        <button type="button" class="btn" onclick="toggleConfigMode()" id="configModeBtn" style="white-space:nowrap;">URL→Key</button>
      </div>
      <div class="form-hint" id="configKeyHint">App 中点播接口的 URL，控制台自动计算 configKey（与 App 自动携带的 X-WebHTV-Config-Key 一致）</div>
    </div>
    <button class="btn btn-primary" style="width:100%;justify-content:center;padding:12px;" onclick="doLogin()">
      🔗 连接
    </button>
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
    <div class="stat-card">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--accent-glow); color: var(--accent);">📺</span>
        活跃记录
      </div>
      <div class="stat-value" id="totalCount">-</div>
      <div class="stat-sub">当前 configKey 下的进度记录</div>
    </div>
    <div class="stat-card">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--danger-bg); color: var(--danger);">🗑️</span>
        删除墓碑
      </div>
      <div class="stat-value" id="tombstoneCount">-</div>
      <div class="stat-sub">90 天内的删除记录</div>
    </div>
    <div class="stat-card">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--success-bg); color: var(--success);">📊</span>
        同步游标
      </div>
      <div class="stat-value" id="nextSince">-</div>
      <div class="stat-sub">最新序列号 (nextSince)</div>
    </div>
    <div class="stat-card">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--warning-bg); color: var(--warning);">⏱️</span>
        数据保留
      </div>
      <div class="stat-value" id="retentionDays">-</div>
      <div class="stat-sub">天 · 超期自动清理</div>
    </div>
  </div>

  <!-- 同标题去重：展示层开关（不物理删除） -->
  <div class="settings-card" id="dedupeSettingsCard" aria-live="polite">
    <div class="settings-left">
      <div class="settings-icon" aria-hidden="true">🪞</div>
      <div class="settings-text">
        <div class="settings-title">
          <span>启用同标题去重</span>
        </div>
        <div class="settings-subtitle">
          按 <span class="mono">vodName</span> 去重：同片名只保留 <strong>更新时间最新</strong> 的一条，旧记录被物理删除并生成墓碑。
          APP 通过同步收到删除通知后会同步清理本地副本，确保两端展示一致。开启瞬间会立即清理历史重复数据。
        </div>
      </div>
    </div>
    <div class="switch-wrap">
      <label class="switch" title="启用 / 关闭 同标题去重">
        <input type="checkbox" id="dedupeToggle" aria-label="启用同标题去重" autocomplete="off">
        <span class="slider"></span>
      </label>
      <div class="switch-label-state" id="dedupeLabelState">OFF</div>
    </div>
  </div>

  <div class="section">
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
  sortDir: 'desc',
  dedupeEnabled: false
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
    hint.textContent = 'App 中点播接口的 URL，控制台自动计算 configKey（与 App 自动携带的 X-WebHTV-Config-Key 一致）';
  } else {
    btn.textContent = 'Key模式';
    input.placeholder = 'a1b2c3d4...（64位 sha256）';
    hint.textContent = '直接输入 configKey（sha256 哈希值），与 App 发送的 X-WebHTV-Config-Key 头完全一致';
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

  // 智能识别：如果输入已经是 64 位 sha256，直接使用；否则按 URL 计算
  let configKey;
  if (isSha256Hex(rawInput)) {
    configKey = rawInput.toLowerCase();
  } else if (configKeyMode === 'url' || rawInput.startsWith('http')) {
    configKey = await computeConfigKey(rawInput);
    if (!configKey) { showToast('Config Key 计算失败', 'error'); return; }
    showToast('已从 URL 计算 configKey: ' + configKey.substring(0, 12) + '...', 'info');
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
    if (typeof statusData.dedupeEnabled === 'boolean') {
      setDedupeToggleState(statusData.dedupeEnabled, true);
    }
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
  document.getElementById('retentionDays').textContent = s.retentionDays ?? '-';
}

// -------- 同标题去重：开关 UI + API --------

function setDedupeToggleState(enabled, silent) {
  state.dedupeEnabled = Boolean(enabled);
  const toggle = document.getElementById('dedupeToggle');
  const label  = document.getElementById('dedupeLabelState');
  if (toggle) {
    toggle.checked = state.dedupeEnabled;
    toggle.setAttribute('aria-checked', state.dedupeEnabled ? 'true' : 'false');
  }
  if (label) {
    label.textContent = state.dedupeEnabled ? 'ON' : 'OFF';
    label.classList.toggle('on',  state.dedupeEnabled);
    label.classList.toggle('off', !state.dedupeEnabled);
  }
  // 避免首次从 statusData 回填时再触发一次 onChange 无限递归
  if (!silent) renderStats();
}

async function onDedupeToggleChange(evt) {
  const desired = Boolean(evt.target.checked);
  const toggle = document.getElementById('dedupeToggle');
  if (toggle) toggle.disabled = true;
  try {
    const res = await fetchJSON('/api/playback/sync/settings', {
      method: 'POST',
      body: JSON.stringify({ dedupeEnabled: desired })
    });
    setDedupeToggleState(res.dedupeEnabled, false);
    const cleaned = Number(res.cleanedCount || 0);
    showToast(
      res.dedupeEnabled
        ? (cleaned > 0
            ? \`已启用同标题去重，已清理 \${cleaned} 条重复记录，APP 将在下次同步时收到删除通知\`
            : '已启用同标题去重：后续同名记录将只保留最新一条')
        : '已关闭同标题去重：后续记录不再去重',
      'success'
    );
    // 切换后立即应用：无刷新重新拉一次列表和统计
    await loadData();
  } catch (e) {
    // 回滚 UI 到实际状态
    setDedupeToggleState(state.dedupeEnabled, false);
    showToast('切换失败: ' + e.message, 'error');
  } finally {
    if (toggle) toggle.disabled = false;
  }
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

// 删除单条记录 — 发送 playback.deleted 墓碑事件（scope=item）
async function deleteRecord(historyKey, siteKey, vodId) {
  if (!historyKey && (!siteKey || !vodId)) { showToast('无法删除：缺少唯一标识', 'error'); return; }
  if (!confirm('确定要删除这条记录吗？')) return;
  const payload = {
    event: 'playback.deleted',
    scope: 'item',
    deletedAt: Date.now(),
    configKey: state.configKey
  };
  if (historyKey) payload.historyKey = historyKey;
  if (siteKey) payload.siteKey = siteKey;
  if (vodId) payload.vodId = vodId;
  try {
    await fetchJSON('/api/playback/sync', { method: 'POST', body: JSON.stringify(payload) });
    showToast('删除成功', 'success');
    loadData();
  } catch (e) { showToast('删除失败: ' + e.message, 'error'); }
}

// 清空全部 — 发送 scope=all 的删除墓碑
async function confirmClearAll() {
  const count = state.filtered.length;
  if (!count) { showToast('没有可清空的记录', 'info'); return; }
  showModal(\`
    <h3>⚠️ 清空全部记录</h3>
    <p>即将删除当前 configKey 下所有 <strong>\${count}</strong> 条观影记录，此操作不可恢复。</p>
    <div class="modal-actions">
      <button class="btn" onclick="hideModal()">取消</button>
      <button class="btn btn-danger" onclick="clearAll()">确认清空</button>
    </div>
  \`);
}

async function clearAll() {
  hideModal();
  try {
    await fetchJSON('/api/playback/sync', {
      method: 'POST',
      body: JSON.stringify({ event: 'playback.deleted', scope: 'all', deletedAt: Date.now(), configKey: state.configKey })
    });
    showToast('已发送清空指令', 'success');
    loadData();
  } catch (e) { showToast('清空失败: ' + e.message, 'error'); }
}

function showModal(html) {
  const modal = document.getElementById('modal');
  modal.innerHTML = '<div class="modal-overlay"><div class="modal">' + html + '</div></div>';
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

// 同标题去重开关：切换即实时生效
(() => {
  const toggle = document.getElementById('dedupeToggle');
  if (toggle) toggle.addEventListener('change', onDedupeToggleChange);
})();

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
