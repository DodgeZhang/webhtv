import dashboard from './dashboard.js';

const SERVER_NAME = 'WebHTV Playback Sync Worker';
const SERVER_MODE = 'cloudflare';
const STORE_KEY = 'all_records';
const MAX_RECORDS = 5000;
const MAX_RECORD_SIZE = 8192;
const DEFAULT_MAX_ITEMS = 1000;
const DEFAULT_RETENTION_DAYS = 90;

const CAPABILITIES = {
  webhookReceive: true,
  remoteSyncQuery: true,
  batchWrite: true,
  batchDelete: true,
  tokenAuth: true,
  ttlRetention: true,
  dedupeKey: true,
  singleKeyStorage: true,
  webDashboard: true
};

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return cors(new Response(null, { status: 204 }));
    try {
      return cors(await handleRequest(request, env));
    } catch (e) {
      const status = e && e.status ? e.status : 500;
      return cors(json({ ok: false, error: e && e.message ? e.message : String(e) }, status));
    }
  }
};

async function handleRequest(request, env) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, '') || '/';
  const method = request.method.toUpperCase();

  if (method === 'GET' && (path === '/' || path === '/admin' || path === '/index.html')) {
    return dashboard.getDashboardResponse(env);
  }

  if (method === 'GET' && path === '/api/health') return json({ ok: true, time: Date.now() });

  if (method === 'GET' && path === '/api/stats') {
    return getStats(request, env);
  }

  if (method === 'GET' && path === '/api/server/capabilities') {
    return json({
      ok: true,
      serverMode: SERVER_MODE,
      serverName: SERVER_NAME,
      time: Date.now(),
      capabilities: CAPABILITIES,
      kvBound: !!(getKV(env))
    });
  }

  if (method === 'GET' && (path === '/api/playback/records' || path === '/api/playback/progress')) {
    return getRecords(request, env);
  }

  if (method === 'POST' && (path === '/api/playback/webhook' || path === '/api/playback/progress')) {
    return receiveWebhook(request, env);
  }

  if (method === 'POST' && (path === '/api/playback/progress/batch' || path === '/api/playback/records/batch')) {
    return receiveBatchWebhook(request, env);
  }

  if (method === 'DELETE' && (path === '/api/playback/progress' || path === '/api/playback/records')) {
    return deleteRecords(request, env);
  }

  if (method === 'POST' && path === '/api/playback/progress/delete') {
    return deleteRecords(request, env);
  }

  return json({ ok: false, error: 'Not found' }, 404);
}

async function getRecords(request, env) {
  const kv = requireKV(env);
  const token = readToken(request);
  checkToken(env, token);

  const url = new URL(request.url);
  const maxItems = Math.min(
    parseInt(url.searchParams.get('maxItems') || env.MAX_ITEMS || String(DEFAULT_MAX_ITEMS), 10) || DEFAULT_MAX_ITEMS,
    2000
  );
  const siteKeyFilter = (url.searchParams.get('siteKey') || '').trim();
  const configKeyFilter = (url.searchParams.get('configKey') || '').trim();
  const vodIdFilter = (url.searchParams.get('vodId') || '').trim();

  const records = await loadAllRecords(kv);

  const filtered = records.filter((r) => {
    if (siteKeyFilter && r.siteKey && r.siteKey !== siteKeyFilter) return false;
    if (configKeyFilter && r.configKey && r.configKey !== configKeyFilter) return false;
    if (vodIdFilter && r.vodId && r.vodId !== vodIdFilter) return false;
    return true;
  });

  const now = Date.now();
  const retentionMs = (parseInt(env.RETENTION_DAYS || String(DEFAULT_RETENTION_DAYS), 10) || DEFAULT_RETENTION_DAYS) * 24 * 3600 * 1000;
  const valid = filtered.filter((r) => {
    const ts = r.timestamp || r.updatedAt || 0;
    return ts <= 0 || (now - ts) <= retentionMs;
  });

  const sorted = valid.sort((a, b) => (b.timestamp || b.updatedAt || 0) - (a.timestamp || a.updatedAt || 0));
  const limited = sorted.slice(0, maxItems);
  const items = limited.map(toProgressInput);

  return json({
    ok: true,
    total: items.length,
    items,
    server: {
      serverMode: SERVER_MODE,
      serverName: SERVER_NAME
    }
  });
}

async function receiveWebhook(request, env) {
  const kv = requireKV(env);
  const token = readToken(request);
  checkToken(env, token);

  const record = await readJson(request);
  if (!record || typeof record !== 'object') throw httpError(400, 'Invalid record body');

  const validationError = validateRecord(record);
  if (validationError) throw httpError(400, validationError);

  const result = await upsertRecord(kv, record, env);
  return json({ ok: true, result });
}

async function receiveBatchWebhook(request, env) {
  const kv = requireKV(env);
  const token = readToken(request);
  checkToken(env, token);

  const body = await readJson(request);
  const items = Array.isArray(body) ? body : (body.items || body.data || body.records || body.list || []);
  if (!Array.isArray(items)) throw httpError(400, 'items must be an array');

  const results = [];
  const validItems = [];
  for (const item of items) {
    if (!item || typeof item !== 'object') {
      results.push({ ok: false, error: 'Invalid record' });
      continue;
    }
    const validationError = validateRecord(item);
    if (validationError) {
      results.push({ ok: false, error: validationError });
      continue;
    }
    validItems.push(item);
  }

  if (validItems.length > 0) {
    const upsertResults = await upsertBatch(kv, validItems, env);
    for (const r of upsertResults) results.push(r);
  }

  const applied = results.filter((r) => r.ok && !r.skipped).length;
  const skipped = results.filter((r) => r.skipped).length;
  const failed = results.length - applied - skipped;

  return json({ ok: true, total: results.length, applied, skipped, failed, results });
}

async function deleteRecords(request, env) {
  const kv = requireKV(env);
  const token = readToken(request);
  checkToken(env, token);

  const body = await readJson(request);
  const historyKey = String(body.historyKey || body.key || '').trim();
  const dedupeKey = String(body.dedupeKey || '').trim();
  const siteKey = String(body.siteKey || '').trim();
  const vodId = String(body.vodId || '').trim();
  const configKey = String(body.configKey || '').trim();
  const confirm = body.confirm === true;
  const scope = String(body.scope || '').trim().toLowerCase();

  const records = await loadAllRecords(kv);
  let updated = records;

  if (dedupeKey) {
    updated = records.filter((r) => !matchesDedupeKey(r, dedupeKey));
  } else if (historyKey) {
    updated = records.filter((r) => r.historyKey !== historyKey);
  } else if (siteKey && vodId) {
    updated = records.filter((r) => !(r.siteKey === siteKey && r.vodId === vodId));
  } else if (siteKey && (scope === 'site' || confirm)) {
    updated = records.filter((r) => r.siteKey !== siteKey);
  } else if (siteKey) {
    throw httpError(400, '按站点清理需要scope=site或confirm=true');
  } else if (configKey) {
    updated = records.filter((r) => r.configKey !== configKey);
  } else if (confirm && scope === 'all') {
    updated = [];
  } else {
    throw httpError(400, 'historyKey、dedupeKey、siteKey+vodId、siteKey或confirm=true必需提供一个');
  }

  const deleted = records.length - updated.length;
  await saveAllRecords(kv, updated, env);

  return json({ ok: true, deleted, remaining: updated.length });
}

async function getStats(request, env) {
  const kv = getKV(env);
  const token = readToken(request);
  if (env.ACCESS_TOKEN) checkToken(env, token);

  if (!kv) {
    return json({
      ok: true,
      totalRecords: 0,
      uniqueSites: 0,
      uniqueClients: 0,
      retentionDays: env.RETENTION_DAYS || String(DEFAULT_RETENTION_DAYS),
      kvBound: false,
      serverMode: SERVER_MODE,
      serverName: SERVER_NAME
    });
  }

  const records = await loadAllRecords(kv);

  const sites = new Set();
  const clients = new Set();
  let oldestTs = 0;
  let newestTs = 0;

  for (const r of records) {
    if (r.siteKey) sites.add(r.siteKey);
    if (r.clientKey) clients.add(r.clientKey);
    const ts = r.timestamp || r.updatedAt || 0;
    if (ts > 0) {
      if (!oldestTs || ts < oldestTs) oldestTs = ts;
      if (!newestTs || ts > newestTs) newestTs = ts;
    }
  }

  return json({
    ok: true,
    totalRecords: records.length,
    uniqueSites: sites.size,
    uniqueClients: clients.size,
    retentionDays: parseInt(env.RETENTION_DAYS || String(DEFAULT_RETENTION_DAYS), 10) || DEFAULT_RETENTION_DAYS,
    oldestRecordAt: oldestTs || null,
    newestRecordAt: newestTs || null,
    kvBound: true,
    serverMode: SERVER_MODE,
    serverName: SERVER_NAME
  });
}

function matchesDedupeKey(record, dedupeKey) {
  if (record.dedupeKey === dedupeKey) return true;
  if (record.clientKey && record.historyKey) {
    return false;
  }
  return false;
}

function validateRecord(record) {
  const siteKey = String(record.siteKey || '').trim();
  const vodId = String(record.vodId || '').trim();
  const vodName = String(record.vodName || '').trim();
  const episodeName = String(record.episodeName || '').trim();
  const positionMs = Number(record.positionMs || 0);
  const durationMs = Number(record.durationMs || 0);

  if (!siteKey) return 'siteKey不能为空';
  if (!vodId) return 'vodId不能为空';
  if (!vodName) return 'vodName不能为空';
  if (!episodeName) return 'episodeName不能为空';
  if (positionMs <= 0) return 'positionMs必须大于0';
  if (durationMs <= 0) return 'durationMs必须大于0';

  const dedupeKey = String(record.dedupeKey || '').trim();
  const historyKey = String(record.historyKey || '').trim();
  if (!dedupeKey && !historyKey) return 'dedupeKey或historyKey必需提供一个';

  return '';
}

async function upsertRecord(kv, record, env) {
  const records = await loadAllRecords(kv);
  const { index, matched } = findRecordIndex(records, record);

  if (matched) {
    const oldTs = (matched.timestamp || matched.updatedAt || 0);
    const newTs = (record.timestamp || record.updatedAt || 0);
    if (newTs > 0 && oldTs > 0 && newTs <= oldTs) {
      return { ok: true, skipped: true, reason: '记录不新于已有记录', dedupeKey: record.dedupeKey };
    }
  }

  const toStore = buildStoreRecord(record);

  if (index >= 0) {
    records[index] = toStore;
  } else {
    records.push(toStore);
  }

  const trimmed = trimRecords(records, env);
  await saveAllRecords(kv, trimmed, env);

  return { ok: true, skipped: false, dedupeKey: toStore.dedupeKey, historyKey: toStore.historyKey, total: trimmed.length };
}

async function upsertBatch(kv, items, env) {
  const records = await loadAllRecords(kv);
  const results = [];
  let changed = false;

  for (const item of items) {
    const { index, matched } = findRecordIndex(records, item);

    if (matched) {
      const oldTs = (matched.timestamp || matched.updatedAt || 0);
      const newTs = (item.timestamp || item.updatedAt || 0);
      if (newTs > 0 && oldTs > 0 && newTs <= oldTs) {
        results.push({ ok: true, skipped: true, reason: '记录不新于已有记录', dedupeKey: item.dedupeKey });
        continue;
      }
    }

    const toStore = buildStoreRecord(item);
    if (index >= 0) {
      records[index] = toStore;
    } else {
      records.push(toStore);
    }
    results.push({ ok: true, skipped: false, dedupeKey: toStore.dedupeKey });
    changed = true;
  }

  if (changed) {
    const trimmed = trimRecords(records, env);
    await saveAllRecords(kv, trimmed, env);
  }

  return results;
}

function findRecordIndex(records, record) {
  const dedupeKey = String(record.dedupeKey || '').trim();
  const historyKey = String(record.historyKey || '').trim();

  if (dedupeKey) {
    for (let i = 0; i < records.length; i++) {
      if (records[i].dedupeKey === dedupeKey) {
        return { index: i, matched: records[i] };
      }
    }
  }

  if (historyKey) {
    for (let i = 0; i < records.length; i++) {
      if (records[i].historyKey === historyKey && records[i].siteKey === (record.siteKey || '')) {
        return { index: i, matched: records[i] };
      }
    }
  }

  return { index: -1, matched: null };
}

function buildStoreRecord(record) {
  const ts = record.timestamp || record.updatedAt || Date.now();
  return {
    schema: record.schema || 'webhtv.playback.v1',
    event: record.event || '',
    eventId: record.eventId || '',
    timestamp: ts,
    sessionId: record.sessionId || '',
    dedupeKey: String(record.dedupeKey || '').trim(),
    cid: record.cid || 0,
    configKey: record.configKey || '',
    configName: record.configName || '',
    configUrl: record.configUrl || '',
    historyKey: String(record.historyKey || '').trim(),
    siteKey: String(record.siteKey || '').trim(),
    siteName: record.siteName || '',
    vodId: String(record.vodId || '').trim(),
    vodName: String(record.vodName || '').trim(),
    vodPic: record.vodPic || '',
    flag: record.flag || '',
    episodeName: String(record.episodeName || '').trim(),
    episodeUrl: record.episodeUrl || '',
    episodeIndex: record.episodeIndex || null,
    state: record.state || '',
    positionMs: Number(record.positionMs || 0),
    durationMs: Number(record.durationMs || 0),
    progress: record.progress || 0,
    speed: record.speed || 1,
    completed: record.completed || false,
    appVersion: record.appVersion || '',
    client: record.client || '',
    clientKey: record.clientKey || '',
    storedAt: Date.now(),
    serverVersion: '1.1'
  };
}

function trimRecords(records, env) {
  const maxRecords = parseInt(env.MAX_RECORDS || String(MAX_RECORDS), 10) || MAX_RECORDS;
  if (records.length <= maxRecords) return records;

  const sorted = [...records].sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
  return sorted.slice(0, maxRecords);
}

async function loadAllRecords(kv) {
  try {
    const raw = await kv.get(STORE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed;
    return [];
  } catch (e) {
    return [];
  }
}

async function saveAllRecords(kv, records, env) {
  const value = JSON.stringify(records);
  const retentionDays = parseInt(env.RETENTION_DAYS || String(DEFAULT_RETENTION_DAYS), 10) || DEFAULT_RETENTION_DAYS;
  const ttlSeconds = Math.min(retentionDays * 24 * 3600, 60 * 60 * 24 * 365);
  if (ttlSeconds > 0) {
    await kv.put(STORE_KEY, value, { expirationTtl: ttlSeconds });
  } else {
    await kv.put(STORE_KEY, value);
  }
}

function toProgressInput(record) {
  return {
    historyKey: record.historyKey || '',
    siteKey: record.siteKey || '',
    vodId: record.vodId || '',
    vodName: record.vodName || '',
    vodPic: record.vodPic || '',
    flag: record.flag || '',
    episodeName: record.episodeName || '',
    episodeUrl: record.episodeUrl || '',
    positionMs: record.positionMs || 0,
    durationMs: record.durationMs || 0,
    progress: record.progress || 0,
    speed: record.speed || 1,
    completed: record.completed || false,
    updatedAt: record.timestamp || record.updatedAt || 0,
    cid: record.cid || 0,
    configKey: record.configKey || '',
    configName: record.configName || '',
    configUrl: record.configUrl || '',
    clientKey: record.clientKey || '',
    dedupeKey: record.dedupeKey || '',
    event: record.event || '',
    eventId: record.eventId || ''
  };
}

function getKV(env) {
  return env.PLAYBACK_KV || env.PLAYBACK_HISTORY || null;
}

function requireKV(env) {
  const kv = getKV(env);
  if (!kv) throw httpError(501, 'KV storage not configured');
  return kv;
}

function readToken(request) {
  const url = new URL(request.url);
  const queryToken = (url.searchParams.get('token') || '').trim();
  if (queryToken) return queryToken;
  const headerToken = request.headers.get('x-webhtv-token') || request.headers.get('X-WebHTV-Token') || '';
  if (headerToken) return headerToken.trim();
  const auth = request.headers.get('authorization') || '';
  const match = auth.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : '';
}

function checkToken(env, token) {
  const expected = env.ACCESS_TOKEN || env.TOKEN || '';
  if (!expected) return;
  if (!token) throw httpError(401, 'Missing authentication token');
  if (token !== expected) throw httpError(403, 'Invalid token');
}

async function readJson(request) {
  const text = await request.text();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch (e) {
    throw httpError(400, 'Invalid JSON body');
  }
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store'
    }
  });
}

function cors(response) {
  const headers = new Headers(response.headers);
  headers.set('access-control-allow-origin', '*');
  headers.set('access-control-allow-methods', 'GET,POST,DELETE,OPTIONS');
  headers.set('access-control-allow-headers', 'authorization,content-type,x-webhtv-origin,x-webhtv-token,x-webhtv-webhook-id,x-webhtv-dedupe-key,x-webhtv-config-key,x-webhtv-config-name,x-device-id,x-device-token');
  headers.set('access-control-max-age', '86400');
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  throw error;
}