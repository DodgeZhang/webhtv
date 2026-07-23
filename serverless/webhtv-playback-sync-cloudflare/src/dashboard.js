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
  .logo {
    display: flex;
    align-items: center;
    gap: 14px;
  }
  .logo-icon {
    width: 44px;
    height: 44px;
    background: var(--gradient-1);
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 22px;
    box-shadow: 0 8px 24px var(--accent-glow);
  }
  .logo-text h1 {
    font-size: 20px;
    font-weight: 700;
    letter-spacing: -0.5px;
  }
  .logo-text p {
    font-size: 13px;
    color: var(--text-secondary);
  }
  .status-badge {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 8px 16px;
    border-radius: 100px;
    font-size: 13px;
    font-weight: 500;
    background: var(--success-bg);
    color: var(--success);
    border: 1px solid rgba(52, 211, 153, 0.3);
  }
  .status-badge.error { background: var(--danger-bg); color: var(--danger); border-color: rgba(248, 113, 113, 0.3); }
  .status-badge .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: currentColor;
    animation: pulse 2s ease-in-out infinite;
  }
  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.4; }
  }

  .stats-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 20px;
    margin-bottom: 32px;
  }
  .stat-card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 24px;
    transition: all 0.2s ease;
    position: relative;
    overflow: hidden;
  }
  .stat-card:hover {
    transform: translateY(-2px);
    border-color: rgba(108, 124, 255, 0.4);
    box-shadow: 0 12px 32px rgba(0, 0, 0, 0.3);
  }
  .stat-card::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 3px;
  }
  .stat-card:nth-child(1)::before { background: var(--gradient-1); }
  .stat-card:nth-child(2)::before { background: var(--gradient-2); }
  .stat-card:nth-child(3)::before { background: var(--gradient-3); }
  .stat-card:nth-child(4)::before { background: var(--gradient-4); }
  .stat-label {
    font-size: 13px;
    color: var(--text-secondary);
    margin-bottom: 12px;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .stat-icon {
    width: 32px;
    height: 32px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 16px;
  }
  .stat-value {
    font-size: 32px;
    font-weight: 700;
    letter-spacing: -1px;
    margin-bottom: 4px;
  }
  .stat-sub {
    font-size: 12px;
    color: var(--text-muted);
  }

  .section {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 24px;
    margin-bottom: 24px;
  }
  .section-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    flex-wrap: wrap;
    gap: 12px;
  }
  .section-title {
    font-size: 16px;
    font-weight: 600;
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .section-title .icon {
    width: 28px;
    height: 28px;
    border-radius: 8px;
    background: var(--accent-glow);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 14px;
  }
  .toolbar {
    display: flex;
    gap: 10px;
    align-items: center;
    flex-wrap: wrap;
  }
  .search-input {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 8px 14px;
    color: var(--text);
    font-size: 14px;
    width: 220px;
    outline: none;
    transition: border-color 0.2s;
  }
  .search-input:focus { border-color: var(--accent); }
  .search-input::placeholder { color: var(--text-muted); }

  .btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 8px 16px;
    border-radius: 8px;
    font-size: 14px;
    font-weight: 500;
    cursor: pointer;
    border: 1px solid var(--border);
    background: var(--bg-card);
    color: var(--text);
    transition: all 0.15s ease;
    white-space: nowrap;
  }
  .btn:hover { border-color: var(--accent); background: var(--bg-elevated); }
  .btn-primary {
    background: var(--gradient-1);
    border: none;
    color: white;
  }
  .btn-primary:hover { filter: brightness(1.1); box-shadow: 0 4px 12px var(--accent-glow); }
  .btn-danger {
    background: var(--danger-bg);
    border-color: rgba(248, 113, 113, 0.3);
    color: var(--danger);
  }
  .btn-danger:hover { background: var(--danger); color: white; border-color: var(--danger); }
  .btn-sm { padding: 5px 10px; font-size: 12px; }

  .table-wrapper {
    overflow-x: auto;
    border-radius: 12px;
    border: 1px solid var(--border);
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 14px;
  }
  thead {
    background: var(--bg-card);
  }
  th {
    padding: 14px 16px;
    text-align: left;
    font-size: 12px;
    font-weight: 600;
    color: var(--text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.5px;
    border-bottom: 1px solid var(--border);
  }
  td {
    padding: 14px 16px;
    border-bottom: 1px solid var(--border);
    vertical-align: top;
  }
  tbody tr {
    transition: background 0.15s;
  }
  tbody tr:hover { background: var(--bg-card); }
  tbody tr:last-child td { border-bottom: none; }

  .vod-name {
    font-weight: 600;
    color: var(--text);
    max-width: 240px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .vod-meta {
    font-size: 12px;
    color: var(--text-muted);
    margin-top: 2px;
  }
  .site-badge {
    display: inline-block;
    padding: 3px 10px;
    border-radius: 6px;
    font-size: 12px;
    background: var(--accent-glow);
    color: var(--accent);
    font-weight: 500;
  }
  .progress-bar {
    width: 120px;
    height: 6px;
    background: var(--bg);
    border-radius: 3px;
    overflow: hidden;
    margin: 6px 0 4px;
  }
  .progress-fill {
    height: 100%;
    border-radius: 3px;
    transition: width 0.3s;
  }
  .progress-text {
    font-size: 12px;
    color: var(--text-secondary);
  }
  .time-cell {
    font-size: 12px;
    color: var(--text-secondary);
    white-space: nowrap;
  }
  .time-cell .date { color: var(--text); font-weight: 500; }
  .time-cell .relative { color: var(--text-muted); }
  .completed-tag {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 11px;
    font-weight: 500;
    background: var(--success-bg);
    color: var(--success);
  }
  .playing-tag {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 11px;
    font-weight: 500;
    background: var(--warning-bg);
    color: var(--warning);
  }

  .empty-state {
    text-align: center;
    padding: 48px 24px;
    color: var(--text-muted);
  }
  .empty-state .icon {
    font-size: 48px;
    margin-bottom: 16px;
    opacity: 0.5;
  }

  .pagination {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 20px;
    gap: 16px;
    flex-wrap: wrap;
  }
  .pagination-info {
    font-size: 13px;
    color: var(--text-secondary);
  }
  .pagination-controls {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .page-btn {
    min-width: 36px;
    height: 36px;
    border-radius: 8px;
    border: 1px solid var(--border);
    background: var(--bg-card);
    color: var(--text);
    cursor: pointer;
    font-size: 14px;
    transition: all 0.15s;
  }
  .page-btn:hover:not(:disabled) { border-color: var(--accent); }
  .page-btn.active {
    background: var(--accent);
    border-color: var(--accent);
    color: white;
  }
  .page-btn:disabled { opacity: 0.4; cursor: not-allowed; }

  .modal-overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.7);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 100;
    backdrop-filter: blur(4px);
  }
  .modal {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 28px;
    max-width: 420px;
    width: 90%;
    box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
  }
  .modal h3 { margin-bottom: 12px; font-size: 18px; }
  .modal p { color: var(--text-secondary); margin-bottom: 20px; font-size: 14px; }
  .modal-actions { display: flex; gap: 10px; justify-content: flex-end; }

  .toast {
    position: fixed;
    bottom: 24px;
    right: 24px;
    padding: 14px 20px;
    border-radius: 10px;
    font-size: 14px;
    z-index: 200;
    animation: slideIn 0.3s ease;
    box-shadow: 0 12px 32px rgba(0, 0, 0, 0.4);
  }
  .toast.success { background: var(--gradient-2); color: white; }
  .toast.error { background: var(--gradient-4); color: white; }
  .toast.info { background: var(--gradient-1); color: white; }
  @keyframes slideIn {
    from { transform: translateY(20px); opacity: 0; }
    to { transform: translateY(0); opacity: 1; }
  }

  .loading {
    display: inline-block;
    width: 16px;
    height: 16px;
    border: 2px solid var(--border);
    border-top-color: var(--accent);
    border-radius: 50%;
    animation: spin 0.6s linear infinite;
  }
  @keyframes spin { to { transform: rotate(360deg); } }

  footer {
    text-align: center;
    padding: 32px 24px;
    color: var(--text-muted);
    font-size: 13px;
  }
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
<div class="container">
  <header>
    <div class="logo">
      <div class="logo-icon">🎬</div>
      <div class="logo-text">
        <h1>观影记录同步</h1>
        <p>WebHTV Playback Sync · Cloudflare Worker</p>
      </div>
    </div>
    <div id="statusBadge" class="status-badge">
      <span class="dot"></span>
      <span id="statusText">连接中...</span>
    </div>
  </header>

  <div class="stats-grid">
    <div class="stat-card">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--accent-glow); color: var(--accent);">📺</span>
        总观影记录
      </div>
      <div class="stat-value" id="totalCount">-</div>
      <div class="stat-sub">所有同步的播放记录</div>
    </div>
    <div class="stat-card">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--success-bg); color: var(--success);">🌐</span>
        站点数量
      </div>
      <div class="stat-value" id="siteCount">-</div>
      <div class="stat-sub">涉及的不同站点</div>
    </div>
    <div class="stat-card">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--warning-bg); color: var(--warning);">📱</span>
        同步设备
      </div>
      <div class="stat-value" id="clientCount">-</div>
      <div class="stat-sub">不同的客户端标识</div>
    </div>
    <div class="stat-card">
      <div class="stat-label">
        <span class="stat-icon" style="background: var(--danger-bg); color: var(--danger);">⏱️</span>
        数据保留
      </div>
      <div class="stat-value" id="retentionDays">-</div>
      <div class="stat-sub">天 · 超期自动清理</div>
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
        <button class="btn" id="refreshBtn" onclick="loadData()">
          🔄 刷新
        </button>
        <button class="btn btn-danger" onclick="confirmClearAll()">
          🗑️ 清空全部
        </button>
      </div>
    </div>

    <div class="table-wrapper">
      <table>
        <thead>
          <tr>
            <th>影片</th>
            <th>站点</th>
            <th>进度</th>
            <th>状态</th>
            <th>更新时间</th>
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
    <p>WebHTV Playback Sync · 部署在 Cloudflare Workers</p>
  </footer>
</div>

<div id="modal" style="display:none;"></div>

<script>
const API = {
  health: '/api/health',
  stats: '/api/stats',
  records: '/api/playback/records',
  delete: '/api/playback/progress/delete'
};

const state = {
  records: [],
  stats: null,
  page: 1,
  pageSize: 20,
  search: '',
  filtered: []
};

async function fetchJSON(url, options = {}) {
  const fullUrl = API.token ? (url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(API.token)) : url;
  const res = await fetch(fullUrl, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...options.headers }
  });
  return res.json();
}

async function loadData() {
  try {
    const [statsData, recordsData] = await Promise.all([
      fetchJSON(API.stats),
      fetchJSON(API.records + '?maxItems=2000')
    ]);
    state.stats = statsData;
    state.records = recordsData.items || [];
    applyFilter();
    updateStatus(true);
    renderStats();
    renderRecords();
  } catch (e) {
    updateStatus(false);
    showToast('加载失败: ' + e.message, 'error');
  }
}

function updateStatus(ok) {
  const badge = document.getElementById('statusBadge');
  const text = document.getElementById('statusText');
  if (ok) {
    badge.classList.remove('error');
    text.textContent = '服务在线';
  } else {
    badge.classList.add('error');
    text.textContent = '连接异常';
  }
}

function renderStats() {
  if (!state.stats) return;
  const s = state.stats;
  document.getElementById('totalCount').textContent = s.totalRecords ?? 0;
  document.getElementById('siteCount').textContent = s.uniqueSites ?? 0;
  document.getElementById('clientCount').textContent = s.uniqueClients ?? 0;
  document.getElementById('retentionDays').textContent = s.retentionDays ?? '-';
}

function applyFilter() {
  const q = state.search.toLowerCase();
  if (!q) {
    state.filtered = [...state.records];
  } else {
    state.filtered = state.records.filter(r =>
      (r.vodName || '').toLowerCase().includes(q) ||
      (r.siteKey || '').toLowerCase().includes(q) ||
      (r.siteName || '').toLowerCase().includes(q)
    );
  }
  state.page = 1;
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
    const time = r.updatedAt || 0;

    return \`
      <tr>
        <td>
          <div class="vod-name">\${escape(r.vodName || '未知影片')}</div>
          <div class="vod-meta">
            \${escape(r.episodeName || '')} · \${position} / \${duration}
          </div>
        </td>
        <td>
          <span class="site-badge">\${escape(r.siteKey || '-')}</span>
        </td>
        <td>
          <div class="progress-bar">
            <div class="progress-fill" style="width: \${progress}%; background: \${progress >= 95 ? 'var(--gradient-2)' : 'var(--gradient-1)'};"></div>
          </div>
          <div class="progress-text">\${progress}%</div>
        </td>
        <td>
          \${isCompleted ? '<span class="completed-tag">已看完</span>' : '<span class="playing-tag">观看中</span>'}
        </td>
        <td class="time-cell">
          <div class="date">\${formatDate(time)}</div>
          <div class="relative">\${relativeTime(time)}</div>
        </td>
        <td>
          <button class="btn btn-sm btn-danger" onclick="deleteRecord('\${escape(r.dedupeKey || '')}', '\${escape(r.historyKey || '')}')" title="删除">🗑️</button>
        </td>
      </tr>
    \`;
  }).join('');

  renderPagination(totalPages);
}

function renderPagination(totalPages) {
  const pagination = document.getElementById('pagination');
  const info = document.getElementById('paginationInfo');
  const controls = document.getElementById('paginationControls');
  const data = state.filtered;

  if (totalPages <= 1) {
    pagination.style.display = 'none';
    return;
  }

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

  if (startPage > 1) {
    html += \`<button class="page-btn" onclick="goPage(1)">1</button>\`;
    if (startPage > 2) html += '<span style="color:var(--text-muted)">...</span>';
  }
  for (let i = startPage; i <= endPage; i++) {
    html += \`<button class="page-btn \${i === state.page ? 'active' : ''}" onclick="goPage(\${i})">\${i}</button>\`;
  }
  if (endPage < totalPages) {
    if (endPage < totalPages - 1) html += '<span style="color:var(--text-muted)">...</span>';
    html += \`<button class="page-btn" onclick="goPage(\${totalPages})">\${totalPages}</button>\`;
  }

  html += \`<button class="page-btn" \${state.page === totalPages ? 'disabled' : ''} onclick="goPage(\${state.page + 1})">›</button>\`;
  controls.innerHTML = html;
}

function goPage(n) {
  state.page = n;
  renderRecords();
}

async function deleteRecord(dedupeKey, historyKey) {
  const body = {};
  if (dedupeKey) body.dedupeKey = dedupeKey;
  else if (historyKey) body.historyKey = historyKey;
  else { showToast('无法删除：缺少唯一标识', 'error'); return; }

  if (!confirm('确定要删除这条记录吗？')) return;

  try {
    const res = await fetchJSON(API.delete, {
      method: 'POST',
      body: JSON.stringify(body)
    });
    if (res.ok) {
      showToast('删除成功', 'success');
      loadData();
    } else {
      showToast('删除失败: ' + (res.error || ''), 'error');
    }
  } catch (e) {
    showToast('请求失败: ' + e.message, 'error');
  }
}

async function confirmClearAll() {
  const count = state.filtered.length;
  if (!count) { showToast('没有可清空的记录', 'info'); return; }

  showModal(\`
    <h3>⚠️ 清空全部记录</h3>
    <p>即将删除所有 <strong>\${count}</strong> 条观影记录，此操作不可恢复。</p>
    <div class="modal-actions">
      <button class="btn" onclick="hideModal()">取消</button>
      <button class="btn btn-danger" onclick="clearAll()">确认清空</button>
    </div>
  \`);
}

async function clearAll() {
  hideModal();
  try {
    const res = await fetchJSON(API.delete, {
      method: 'POST',
      body: JSON.stringify({ confirm: true, scope: 'all' })
    });
    if (res.ok) {
      showToast(\`已清空 \${res.deleted} 条记录\`, 'success');
      loadData();
    } else {
      showToast('清空失败: ' + (res.error || ''), 'error');
    }
  } catch (e) {
    showToast('请求失败: ' + e.message, 'error');
  }
}

function showModal(html) {
  const modal = document.getElementById('modal');
  modal.innerHTML = '<div class="modal-overlay"><div class="modal">' + html + '</div></div>';
  modal.style.display = 'block';
}
function hideModal() {
  document.getElementById('modal').style.display = 'none';
}

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

function escape(s) {
  const div = document.createElement('div');
  div.textContent = String(s ?? '');
  return div.innerHTML;
}

function formatDuration(ms) {
  if (!ms || ms <= 0) return '-';
  const h = Math.floor(ms / 3600000);
  const m = Math.floor((ms % 3600000) / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  if (h > 0) return \`\${h}h \${m}m\`;
  if (m > 0) return \`\${m}m \${s}s\`;
  return \`\${s}s\`;
}

function formatDate(ts) {
  if (!ts) return '-';
  const d = new Date(ts);
  const now = new Date();
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
  const mo = Math.floor(d / 30);
  return mo + ' 个月前';
}

document.getElementById('searchInput').addEventListener('input', (e) => {
  state.search = e.target.value;
  applyFilter();
  renderRecords();
});

loadData();
setInterval(loadData, 30000);
</script>
</body>
</html>`;

export function getDashboardHtml(token) {
  if (!token) return DASHBOARD_HTML;
  const safeToken = token.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  return DASHBOARD_HTML
    .replace('const API = {',
      'const API = {\n  token: "' + safeToken + '",');
}

export function getDashboardResponse(env) {
  const token = env.ACCESS_TOKEN || '';
  const html = getDashboardHtml(token);
  return new Response(html, {
    status: 200,
    headers: {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-store'
    }
  });
}

export default { getDashboardHtml, getDashboardResponse };
