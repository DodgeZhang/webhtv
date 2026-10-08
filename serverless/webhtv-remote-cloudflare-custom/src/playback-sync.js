import {
  parseIdentityRequest,
  resolveIdentity,
  resolveConfigKey,
  normalizeConfigType,
  identityCapabilities,
  identityRegistryKey,
  normalizeIdentityRegistry,
  normalizeIdentityKey,
  normalizeOptionalKey,
  normalizeKeyList
} from '../../playback-identity-fixtures/identity.js';
const PLAYBACK_SYNC_PATHS = new Set(['/api/playback/sync', '/playback/sync']);
const IDENTITY_RESOLVE_PATHS = new Set(['/api/playback/identity/resolve', '/playback/identity/resolve']);
const TOMBSTONE_RETENTION_MS = 90 * 24 * 60 * 60 * 1000;
// WebHTV adaptation: only deletions write a dedup receipt — a replayed progress
// event is fully answered by the timestamp gates alone — so this table holds one
// row per delete webhook instead of one row per progress webhook (the latter
// arrived every ~30s per playing device and was the bulk of the table). These
// rows are the sole guard against a delete replay whose payload lacks deletedAt:
// that payload is re-stamped as "now", so the timestamp gate can never catch it,
// and the replay would re-apply the deletion with a fresh deletedAt, sweeping
// rows the original deletion had no right to touch. Only a device retry reaches
// this path, so a month is deliberately generous. The delete-only change is what
// makes that affordable: the table now grows by deletions alone — hundreds of
// rows a month instead of thousands a day — so the window no longer has to be
// traded against the free tier's rows-written budget.
const EVENT_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;
const CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000;
const MAX_BODY_BYTES = 128 * 1024;
const MAX_BATCH_ITEMS = 100;
const DEFAULT_LIMIT = 100;
const MAX_LIMIT = 1000;
const PLAYBACK_SCHEMA = 'webhtv.playback.v1';
// WebHTV adaptation: lazily collapse same-interface identity groups on every
// identity resolve and auto-confirm the official merge path (the App never
// sends confirm:true). Set to false to restore the official resolver
// behaviour unchanged; the dashboard merge panel keeps working either way.
const AUTO_MERGE_IDENTITIES = true;

export function isPlaybackSyncPath(pathname) {
  const path = normalizePath(pathname);
  if (PLAYBACK_SYNC_PATHS.has(path)) return true;
  // WebHTV adaptation: also route the dashboard's read-only space list and the
  // dashboard-only identity space listing / manual merge endpoints.
  for (const base of PLAYBACK_SYNC_PATHS) {
    if (path === `${base}/status` || path === `${base}/configs`) return true;
    if (path === `${base}/identity/spaces` || path === `${base}/identity/merge`) return true;
    if (path === `${base}/maintenance`) return true;
  }
  return IDENTITY_RESOLVE_PATHS.has(path);
}

export async function handlePlaybackSyncGateway(request, env) {
  if (request.method === 'OPTIONS') return playbackCors(new Response(null, { status: 204 }));
  if (!env || !env.PLAYBACK_DO) return playbackError(503, 'PLAYBACK_DO is not configured');

  const token = playbackToken(request);
  if (token.length > 512) return playbackError(400, 'X-WebHTV-Token is too long');

  // WebHTV adaptation: keep the local no-token mode. The dashboard and legacy
  // app setups run without a configured token and share the user-no-token
  // namespace; upstream would reject them with 401.
  const namespace = token ? `user-${await sha256(token)}` : 'user-no-token';
  return env.PLAYBACK_DO.getByName(namespace).fetch(request);
}

export class WebHTVPlaybackSyncDO {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.sql = state.storage.sql;
    this.ready = state.blockConcurrencyWhile(async () => this.migrate());
  }

  async fetch(request) {
    await this.ready;
    if (request.method === 'OPTIONS') return playbackCors(new Response(null, { status: 204 }));
    try {
      this.cleanup();
      const url = new URL(request.url);
      const path = normalizePath(url.pathname);
      if (IDENTITY_RESOLVE_PATHS.has(path)) {
        if (request.method !== 'POST') return playbackError(405, 'Method not allowed');
        const input = parseIdentityRequest(await readPlaybackJson(request), request.headers);
        const result = await this.resolveIdentityWithAutoMerge(request, input);
        return playbackCors(playbackJson(result.body, result.status));
      }
      const status = [...PLAYBACK_SYNC_PATHS].some((base) => path === `${base}/status`);
      if (status) {
        if (request.method === 'GET') return playbackCors(await this.status(request, url));
        return playbackError(405, 'Method not allowed');
      }
      // WebHTV adaptation: read-only space list for the dashboard login screen.
      // No X-WebHTV-Config-Key required; aggregates across all config spaces.
      const configs = [...PLAYBACK_SYNC_PATHS].some((base) => path === `${base}/configs`);
      if (configs) {
        if (request.method === 'GET') return playbackCors(this.listConfigs());
        return playbackError(405, 'Method not allowed');
      }
      // WebHTV adaptation (dashboard): identity-aware space listing with
      // same-interface grouping, and the manual merge operation that resolves
      // the confirm_required deadlock the App cannot answer.
      const identitySpaces = [...PLAYBACK_SYNC_PATHS].some((base) => path === `${base}/identity/spaces`);
      if (identitySpaces) {
        if (request.method === 'GET') return playbackCors(await this.listIdentitySpaces(request));
        return playbackError(405, 'Method not allowed');
      }
      const identityMerge = [...PLAYBACK_SYNC_PATHS].some((base) => path === `${base}/identity/merge`);
      if (identityMerge) {
        if (request.method === 'POST') return playbackCors(await this.handleIdentityMerge(request));
        return playbackError(405, 'Method not allowed');
      }
      // WebHTV adaptation: token-gated maintenance entry point. The DO is already
      // scoped to the caller's token namespace, so only the token owner can purge
      // their own history tombstones.
      const maintenance = [...PLAYBACK_SYNC_PATHS].some((base) => path === `${base}/maintenance`);
      if (maintenance) {
        if (request.method === 'POST') return playbackCors(await this.runMaintenance(request));
        return playbackError(405, 'Method not allowed');
      }
      if (!PLAYBACK_SYNC_PATHS.has(path)) return playbackError(404, 'Not found');
      if (request.method === 'GET') return playbackCors(await this.pull(request, url));
      if (request.method === 'POST') return playbackCors(await this.ingest(request));
      return playbackError(405, 'Method not allowed');
    } catch (error) {
      const status = Number(error && error.status) || 500;
      if (status >= 400 && status < 500) return playbackError(status, error && error.message ? error.message : 'Invalid request');
      console.error('Playback sync request failed', error && error.stack ? error.stack : error);
      return playbackError(500, 'Internal server error');
    }
  }

  migrate() {
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS playback_meta (
        key TEXT PRIMARY KEY,
        value INTEGER NOT NULL
      );
      INSERT OR IGNORE INTO playback_meta (key, value) VALUES ('sequence', 0);
      INSERT OR IGNORE INTO playback_meta (key, value) VALUES ('last_cleanup', 0);

      CREATE TABLE IF NOT EXISTS playback_items (
        config_key TEXT NOT NULL,
        item_key TEXT NOT NULL,
        history_key TEXT NOT NULL,
        site_key TEXT NOT NULL,
        vod_id TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        seq INTEGER NOT NULL,
        payload TEXT NOT NULL,
        PRIMARY KEY (config_key, item_key)
      );
      CREATE INDEX IF NOT EXISTS idx_playback_items_config_seq
        ON playback_items (config_key, seq);

      CREATE TABLE IF NOT EXISTS playback_tombstones (
        config_key TEXT NOT NULL,
        marker_key TEXT NOT NULL,
        scope TEXT NOT NULL,
        history_key TEXT NOT NULL,
        site_key TEXT NOT NULL,
        vod_id TEXT NOT NULL,
        deleted_at INTEGER NOT NULL,
        seq INTEGER NOT NULL,
        payload TEXT NOT NULL,
        PRIMARY KEY (config_key, marker_key)
      );
      CREATE INDEX IF NOT EXISTS idx_playback_tombstones_config_seq
        ON playback_tombstones (config_key, seq);
      CREATE INDEX IF NOT EXISTS idx_playback_tombstones_deleted_at
        ON playback_tombstones (deleted_at);

      CREATE TABLE IF NOT EXISTS playback_events (
        config_key TEXT NOT NULL,
        event_id TEXT NOT NULL,
        received_at INTEGER NOT NULL,
        PRIMARY KEY (config_key, event_id)
      );
      CREATE INDEX IF NOT EXISTS idx_playback_events_received_at
        ON playback_events (received_at);
      CREATE TABLE IF NOT EXISTS playback_identity_registry (
        registry_key TEXT PRIMARY KEY,
        version INTEGER NOT NULL DEFAULT 0,
        state TEXT NOT NULL
      );
    `);
    // WebHTV quota optimization (Durable Objects rows_read): applyUpsert used to
    // read every tombstone of the space on every event just to learn the newest
    // deleted_at. This index lets that scan become a range probe over
    // (config_key, deleted_at) that normally proves no suppression is possible
    // without reading any tombstone. Query results are unaffected; the index is
    // only a lookup structure.
    this.sql.exec(`
      CREATE INDEX IF NOT EXISTS idx_playback_tombstones_config_deleted
        ON playback_tombstones (config_key, deleted_at);
    `);
    // WebHTV adaptation: season-scoped deletions (TV shows matched by TMDB
    // identity). The App webhook sends scope="season"; without these columns
    // the server only accepted item/site/all and answered HTTP 400, so the
    // server kept the record until a later item-scoped delete arrived — the
    // "delete locally, server keeps it until the record syncs back and I
    // delete a second time" failure.
    const tombstoneColumns = new Set(
      this.sql.exec('PRAGMA table_info(playback_tombstones)').toArray().map((column) => column.name)
    );
    if (!tombstoneColumns.has('media_type')) {
      this.sql.exec(`
        ALTER TABLE playback_tombstones ADD COLUMN media_type TEXT NOT NULL DEFAULT '';
        ALTER TABLE playback_tombstones ADD COLUMN tmdb_id INTEGER NOT NULL DEFAULT 0;
        ALTER TABLE playback_tombstones ADD COLUMN season_number INTEGER NOT NULL DEFAULT -1;
      `);
    }
  }

  async ingest(request) {
    const body = await readPlaybackJson(request);
    const submittedConfigKey = requireConfigKey(request, body);
    const configType = normalizeConfigType(request.headers.get('x-webhtv-config-type') || body.configType || 'vod');
    const submittedAliases = requestAliases(request, body);
    const rawEvents = extractPlaybackEvents(body);
    if (!rawEvents.length) throw playbackHttpError(400, 'Playback event is empty');
    if (rawEvents.length > MAX_BATCH_ITEMS) throw playbackHttpError(413, `Too many playback events; maximum is ${MAX_BATCH_ITEMS}`);

    // WebHTV adaptation: unify diverged device spaces at WRITE time. A device
    // that only pushes webhooks (or runs an older App without the identity
    // resolver, or whose resolve failed/timed out) never calls
    // /identity/resolve, so its random interfaceKey would otherwise stay an
    // isolated space forever. Every write therefore (1) adopts the submitted
    // key via strong address fingerprints and (2) collapses same-name spaces
    // as a reversible fallback (migration copies rows; dropping the alias
    // splits them again).
    const hintName = firstEventConfigName(rawEvents);
    await this.autoUnifyIdentity(request, { configType, submittedConfigKey, aliases: submittedAliases, hintName });

    const configKey = await this.resolvePlaybackConfigKey(request, submittedConfigKey, configType, submittedAliases);

    const sharedEventId = rawEvents.length === 1
      ? cleanString(request.headers.get('x-webhtv-webhook-id') || request.headers.get('idempotency-key'), 160)
      : '';
    const now = Date.now();
    // Validate the entire batch before applying any item so a malformed item cannot
    // leave earlier records committed while the request itself returns an error.
    const storageConfigKey = scopedConfigKey(configType, configKey);
    // WebHTV adaptation: validate against the requester's submitted key. The App
    // signs webhook events with its own device key (keyForCid); once a server-side
    // merge turns that key into an alias, resolving first would demand the
    // canonical key here and reject every webhook with 400.
    const events = rawEvents.map((raw) => normalizePlaybackEvent(raw, submittedConfigKey, now, sharedEventId));
    for (const event of events) event.storageConfigKey = storageConfigKey;
    const results = events.map((event) => event.kind === 'delete' ? this.applyDelete(event, now) : this.applyUpsert(event, now));
    return playbackJson({
      ok: true,
      received: results.length,
      applied: results.filter((item) => item.action === 'created' || item.action === 'updated' || item.action === 'deleted').length,
      skipped: results.filter((item) => item.action === 'skipped' || item.action === 'duplicate').length,
      results
    });
  }

  async pull(request, url) {
    const submittedConfigKey = requireConfigKey(request);
    const configType = normalizeConfigType(request.headers.get('x-webhtv-config-type') || url.searchParams.get('configType'));
    const submittedAliases = requestAliases(request);
    // WebHTV adaptation: collapse diverged device spaces on read too, so a
    // pull-only device (or the first sync after a new device pushed webhooks)
    // sees the unified space immediately.
    await this.autoUnifyIdentity(request, { configType, submittedConfigKey, aliases: submittedAliases, hintName: '' });
    const configKey = await this.resolvePlaybackConfigKey(request, submittedConfigKey, configType, submittedAliases);
    const storageConfigKey = scopedConfigKey(configType, configKey);
    const since = parseCursor(request.headers.get('x-webhtv-since') || url.searchParams.get('since'));
    const limit = parseLimit(request.headers.get('x-webhtv-limit') || url.searchParams.get('limit'));
    const cutoff = Date.now() - TOMBSTONE_RETENTION_MS;
    const rows = this.sql.exec(`
      SELECT seq, kind, history_key, site_key, vod_id, updated_at, scope, deleted_at,
             media_type, tmdb_id, season_number, payload FROM (
        SELECT seq, 'upsert' AS kind, history_key, site_key, vod_id, updated_at,
               NULL AS scope, NULL AS deleted_at,
               NULL AS media_type, NULL AS tmdb_id, NULL AS season_number, payload
          FROM playback_items
         WHERE config_key = ? AND seq > ?
        UNION ALL
        SELECT seq, 'delete' AS kind, history_key, site_key, vod_id,
               NULL AS updated_at, scope, deleted_at,
               media_type, tmdb_id, season_number, payload
          FROM playback_tombstones
         WHERE config_key = ? AND deleted_at >= ? AND seq > ?
      )
      ORDER BY seq ASC
      LIMIT ?
    `, storageConfigKey, since, storageConfigKey, cutoff, since, limit + 1).toArray();

    const hasMore = rows.length > limit;
    const selected = hasMore ? rows.slice(0, limit) : rows;
    // WebHTV adaptation: stamp every pulled change's configKey with a key the
    // REQUESTER itself reported, so its App can always map it back to the
    // local interface. Registry fingerprints are deliberately not consulted:
    // they may belong to another device's mirror address, and a device that
    // does not recognize the stamped key skips the whole batch (接口不匹配).
    const rewriteKey = this.pullRewriteKey(request, submittedConfigKey);
    const changes = [];
    for (const row of selected) {
      try {
        const change = hydratePullChange(row.kind, JSON.parse(row.payload), row);
        if (change && typeof change === 'object') change.configKey = rewriteKey;
        changes.push(change);
      } catch {
        // Ignore an individually corrupted row without breaking all other records.
      }
    }
    const nextSince = selected.length ? String(selected[selected.length - 1].seq) : String(since);
    return playbackJson({ changes, nextSince, hasMore });
  }

  // Pick the configKey stamped onto pulled changes: only keys the requester
  // itself reported on THIS request. Old builds submit the URL SHA-256 hash
  // directly; new builds submit their UUID plus their legacy hashes in the
  // alias header — a 64-hex fingerprint is preferred because every build
  // maps it back to the local interface.
  pullRewriteKey(request, submittedConfigKey) {
    return selectPullRewriteKey(submittedConfigKey, requestAliases(request));
  }

  async status(request, url) {
    const submittedConfigKey = requireConfigKey(request);
    const configType = normalizeConfigType(request.headers.get('x-webhtv-config-type') || url.searchParams.get('configType'));
    const configKey = await this.resolvePlaybackConfigKey(request, submittedConfigKey, configType, requestAliases(request));
    const storageConfigKey = scopedConfigKey(configType, configKey);
    const cutoff = Date.now() - TOMBSTONE_RETENTION_MS;
    const items = this.sql.exec('SELECT COUNT(*) AS count FROM playback_items WHERE config_key = ?', storageConfigKey).one();
    const tombstones = this.sql.exec('SELECT COUNT(*) AS count FROM playback_tombstones WHERE config_key = ? AND deleted_at >= ?', storageConfigKey, cutoff).one();
    // Unscoped on purpose: the dedup card reports the table's total weight (the
    // part that makes identity migration expensive) and the cleanup action
    // purges that same global set, so the number shown and the number removed
    // always agree.
    const events = this.sql.exec('SELECT COUNT(*) AS count FROM playback_events').one();
    const latest = this.sql.exec(`
      SELECT COALESCE(MAX(seq), 0) AS seq FROM (
        SELECT seq FROM playback_items WHERE config_key = ?
        UNION ALL
        SELECT seq FROM playback_tombstones WHERE config_key = ? AND deleted_at >= ?
      )
    `, storageConfigKey, storageConfigKey, cutoff).one();
    return playbackJson({
      ok: true,
      configKey,
      identityProtocol: 'webhtv.playback.identity.v1',
      addressMatchVersion: 1,
      capabilities: identityCapabilities(),
      items: Number(items.count || 0),
      tombstones: Number(tombstones.count || 0),
      events: Number(events.count || 0),
      nextSince: String(latest.seq || 0),
      retentionDays: Math.round(TOMBSTONE_RETENTION_MS / 86400000),
      eventRetentionDays: Math.round(EVENT_RETENTION_MS / 86400000),
      endpoint: `${url.origin}${basePlaybackPath(url.pathname)}`
    });
  }

  // WebHTV adaptation (dashboard): list every config space in this token
  // namespace with its record count. The space name is the interface name
  // carried by the MAJORITY of its records, never by its most recent one: a
  // device pushes every sync source it has into its own bound space, so a
  // 饭太硬 space holding a single newer 摸鱼 row was labelled 摸鱼 and looked
  // like a second 摸鱼 main space.
  listConfigs() {
    const configs = [...this.spaceNameIndex('1 = 1', []).values()]
      .sort((a, b) => (b.latest - a.latest) || (a.storageKey < b.storageKey ? -1 : 1))
      .map((entry) => ({
        configKey: entry.storageKey,
        name: entry.name,
        items: entry.items,
        latest: entry.latest
      }));
    return playbackJson({ ok: true, configs });
  }

  // One aggregated scan of every stored space: total record count, earliest and
  // latest timestamp, and the interface name (payload.configName) carried by
  // the largest group of rows. Ties prefer the more recently used name, then
  // key order, so a label never flickers between equally sized groups.
  // `nameItems` is the size of that winning group — readers that only label a
  // space accept it as-is, while the name-gated merge additionally requires a
  // strict majority (`nameItems * 2 > items`) before a name may decide a merge.
  spaceNameIndex(where, args) {
    const rows = this.sql.exec(`
      SELECT config_key,
             COALESCE(NULLIF(json_extract(payload, '$.configName'), ''), '') AS name,
             COUNT(*) AS items, MIN(updated_at) AS first_at, MAX(updated_at) AS latest
        FROM playback_items
       WHERE ${where}
       GROUP BY config_key, name
    `, ...args).toArray();
    const index = new Map();
    for (const row of rows) {
      const storageKey = String(row.config_key || '');
      const name = String(row.name || '');
      const items = Number(row.items || 0);
      const firstAt = Number(row.first_at || 0);
      const latest = Number(row.latest || 0);
      let entry = index.get(storageKey);
      if (!entry) {
        entry = {
          storageKey, name: '', nameItems: 0, nameFirstAt: 0, nameLatest: 0,
          items: 0, firstAt, latest
        };
        index.set(storageKey, entry);
      }
      entry.items += items;
      if (firstAt < entry.firstAt) entry.firstAt = firstAt;
      if (latest > entry.latest) entry.latest = latest;
      if (!name) continue;
      if (items > entry.nameItems
        || (items === entry.nameItems
          && (latest > entry.nameLatest || (latest === entry.nameLatest && name < entry.name)))) {
        entry.name = name;
        entry.nameItems = items;
        entry.nameFirstAt = firstAt;
        entry.nameLatest = latest;
      }
    }
    return index;
  }

  // WebHTV adaptation (dashboard): identity-aware space list. Extends the
  // listConfigs aggregation with the identity registry state (canonical /
  // alias / unregistered) and groups registered identities that share any
  // strong address clue (strict / endpoint / legacy — never host-only, which
  // would fuse unrelated interfaces on the same proxy host). Registered
  // canonical identities without any stored record are listed with items=0 so
  // a merge target always has a visible row.
  async listIdentitySpaces(request) {
    const token = playbackToken(request);
    const store = this.identityStore(request);
    const rows = [...this.spaceNameIndex('1 = 1', []).values()]
      .sort((a, b) => (b.latest - a.latest) || (a.storageKey < b.storageKey ? -1 : 1))
      .map((entry) => ({
        config_key: entry.storageKey,
        items: entry.items,
        latest: entry.latest,
        name: entry.name
      }));
    const spaces = [];
    const groups = [];
    const seen = new Set();
    const registries = new Map();
    const loadRegistry = async (configType) => {
      if (!registries.has(configType)) {
        const key = await identityRegistryKey(token, configType);
        const snapshot = await store.load(key);
        const registry = normalizeIdentityRegistry(snapshot.state);
        registries.set(configType, { registry, ...groupRegisteredIdentities(registry) });
      }
      return registries.get(configType);
    };
    const pushSpace = (configKey, configType, entry) => {
      seen.add(`${configType}:${configKey}`);
      spaces.push({
        configKey,
        configType,
        name: entry.name || '',
        items: Number(entry.items || 0),
        latest: Number(entry.latest || 0),
        identity: entry.identity,
        canonicalKey: entry.canonicalKey || '',
        group: entry.group || ''
      });
    };
    for (const row of rows) {
      const storageKey = String(row.config_key || '');
      let configType = 'vod';
      let configKey = storageKey;
      if (storageKey.startsWith('live:')) { configType = 'live'; configKey = storageKey.slice('live:'.length); }
      else if (storageKey.startsWith('wall:')) { configType = 'wall'; configKey = storageKey.slice('wall:'.length); }
      const context = await loadRegistry(configType);
      const identity = context.registry.identities[configKey];
      const alias = identity ? null : context.registry.aliases[configKey];
      const canonicalKey = identity ? configKey : alias ? alias.canonicalInterfaceKey : '';
      const group = canonicalKey ? (context.groupIdOf.get(canonicalKey) || '') : '';
      pushSpace(configKey, configType, {
        name: row.name,
        items: row.items,
        latest: row.latest,
        identity: identity ? 'canonical' : alias ? 'alias' : 'unregistered',
        canonicalKey,
        group
      });
    }
    // List registered canonical identities that hold no rows yet so they can
    // still be picked as a merge target.
    for (const [configType, context] of registries) {
      for (const key of Object.keys(context.registry.identities)) {
        if (seen.has(`${configType}:${key}`)) continue;
        pushSpace(key, configType, { identity: 'canonical', canonicalKey: key, group: context.groupIdOf.get(key) || '' });
      }
    }
    for (const [configType, context] of registries) {
      for (const group of context.groups) {
        if (group.members.length > 1) groups.push({ configType, id: group.id, members: group.members });
      }
    }
    return playbackJson({ ok: true, spaces, groups });
  }

  // WebHTV adaptation (dashboard): manual merge entry point. See the
  // mergeIdentityRegistry export below for the protocol rationale.
  async handleIdentityMerge(request) {
    const body = await readPlaybackJson(request);
    const configType = normalizeConfigType(body.configType || request.headers.get('x-webhtv-config-type') || 'vod');
    const result = await mergeIdentityRegistry(
      this.identityStore(request),
      playbackToken(request),
      configType,
      body.targetKey,
      Array.isArray(body.sourceKeys) ? body.sourceKeys : (body.sourceKey ? [body.sourceKey] : [])
    );
    return playbackJson({ ok: true, ...result });
  }

  // WebHTV adaptation: purge historical deletion tombstones older than
  // beforeDeletedAt. Thousands of experiment-era tombstones occupy every pull
  // page (the App applies at most 100 per sync and counts "no local row"
  // deletes as skipped), hiding the few real progress rows behind them. Items
  // are never touched. configType vod has no storage prefix; live/wall do.
  async runMaintenance(request) {
    const body = await readPlaybackJson(request);
    const op = cleanString(body.op, 32);
    const configType = normalizeConfigType(body.configType || request.headers.get('x-webhtv-config-type') || 'vod');
    if (op === 'purgeTombstones') return this.runPurgeTombstones(request, body, configType);
    if (op === 'purgeEvents') return this.runPurgeEvents(request, body, configType);
    if (op === 'adminDeleteItem') return this.runAdminDeleteItem(request, body, configType);
    if (op === 'adminClearAll') return this.runAdminClearAll(request, body, configType);
    if (op === 'adminInspectIdentity') return this.runAdminInspectIdentity(request, body, configType);
    if (op === 'adminUnbindIdentity') return this.runAdminUnbindIdentity(request, body, configType);
    if (op === 'adminForgetIdentity') return this.runAdminForgetIdentity(request, body, configType);
    if (op === 'adminSplitIdentity') return this.runAdminSplitIdentity(request, body, configType);
    if (op === 'adminMoveRows') return this.runAdminMoveRows(body, configType);
    if (op === 'adminClearSpace') return this.runAdminClearSpace(request, body, configType);
    throw playbackHttpError(400, 'Unknown maintenance op');
  }

  async runPurgeTombstones(request, body, configType) {
    const before = Number(body.beforeDeletedAt);
    if (!Number.isFinite(before) || before <= 0) throw playbackHttpError(400, 'beforeDeletedAt must be a positive ms timestamp');
    // Dashboard card purge: scope to the resolved interface so the cleared
    // count matches the per-configKey tombstone stat instead of every space.
    if (body.configKey) {
      const submittedConfigKey = requireConfigKey(request, body);
      const configKey = await this.resolvePlaybackConfigKey(
        request, submittedConfigKey, configType, requestAliases(request, body)
      );
      const storageConfigKey = scopedConfigKey(configType, configKey);
      const deleted = this.state.storage.transactionSync(() =>
        Number(this.sql.exec(
          'DELETE FROM playback_tombstones WHERE config_key = ? AND deleted_at < ?',
          storageConfigKey, before
        ).rowsWritten || 0)
      );
      return playbackJson({ ok: true, op: 'purgeTombstones', configType, configKey, purged: deleted });
    }
    const deleted = this.state.storage.transactionSync(() => {
      const result = configType === 'vod'
        ? this.sql.exec(
            "DELETE FROM playback_tombstones WHERE deleted_at < ? AND config_key NOT LIKE 'live:%' AND config_key NOT LIKE 'wall:%'",
            before
          )
        : this.sql.exec(
            'DELETE FROM playback_tombstones WHERE deleted_at < ? AND config_key LIKE ?',
            before,
            `${configType}:%`
          );
      return Number(result.rowsWritten || 0);
    });
    return playbackJson({ ok: true, op: 'purgeTombstones', configType, purged: deleted });
  }

  // WebHTV quota maintenance: reclaim historical webhook dedup rows ahead of the
  // 30-day retention sweep. playback_events carries no playback data — it only
  // answers "was this delete event id already applied?" (progress events write no
  // receipt at all) — but identity migration reads a source space's events in
  // full to copy them, so the ~190k experiment-era rows left behind in now-aliased
  // spaces made every binding change expensive.
  // Batched on purpose: the free tier allows 100k written rows per day and a
  // single DELETE of that size fails the statement (docs/cf-do-quota-rows-written.md),
  // so the caller repeats the call while `hasMore` stays true and the daily
  // budget is theirs to spend.
  async runPurgeEvents(request, body, configType) {
    const before = Number(body.beforeReceivedAt);
    if (!Number.isFinite(before) || before <= 0) throw playbackHttpError(400, 'beforeReceivedAt must be a positive ms timestamp');
    const limit = Math.min(Math.max(Math.floor(Number(body.limit) || 5000), 1), 20000);
    let configKey = '';
    if (body.configKey) {
      const submittedConfigKey = requireConfigKey(request, body);
      configKey = await this.resolvePlaybackConfigKey(
        request, submittedConfigKey, configType, requestAliases(request, body)
      );
    }
    const storageConfigKey = configKey ? scopedConfigKey(configType, configKey) : '';
    const deleted = this.state.storage.transactionSync(() => {
      const sql = storageConfigKey
        ? 'DELETE FROM playback_events WHERE rowid IN (SELECT rowid FROM playback_events WHERE config_key = ? AND received_at < ? LIMIT ?)'
        : 'DELETE FROM playback_events WHERE rowid IN (SELECT rowid FROM playback_events WHERE received_at < ? LIMIT ?)';
      const args = storageConfigKey ? [storageConfigKey, before, limit] : [before, limit];
      return Number(this.sql.exec(sql, ...args).rowsWritten || 0);
    });
    return playbackJson({
      ok: true, op: 'purgeEvents', configType, ...(configKey ? { configKey } : {}),
      deleted, limit, hasMore: deleted >= limit, beforeReceivedAt: before
    });
  }
  // Dashboard management delete. Unlike a device delete event this uses the
  // SERVER clock and physically removes rows with no updatedAt guard, so a
  // skewed device clock or a row whose payload identity drifted can never
  // make the dashboard button look like a no-op. A tombstone is written in
  // the same transaction so other devices still receive the deletion.
  async runAdminDeleteItem(request, body, configType) {
    const submittedConfigKey = requireConfigKey(request, body);
    const configKey = await this.resolvePlaybackConfigKey(
      request, submittedConfigKey, configType, requestAliases(request, body)
    );
    const storageConfigKey = scopedConfigKey(configType, configKey);
    const siteKey = cleanString(body.siteKey ?? body.site, 1024);
    const vodId = cleanString(body.vodId ?? body.vod_id, 8192);
    const historyKey = cleanString(body.historyKey ?? body.history_key, 4096);
    if (!((siteKey && vodId) || historyKey)) {
      throw playbackHttpError(400, 'adminDeleteItem requires siteKey+vodId or historyKey');
    }
    const itemKey = portableItemKey(historyKey, siteKey, vodId);
    const deletedAt = Date.now();
    const payload = JSON.stringify(compactObject({
      scope: 'item', siteKey, vodId, historyKey, deletedAt, origin: 'dashboard'
    }));
    const deletedRows = this.state.storage.transactionSync(() => {
      this.upsertAdminTombstone(storageConfigKey, `item\n${itemKey}`, 'item',
        historyKey, siteKey, vodId, deletedAt, payload);
      return Number(this.sql.exec(`
        DELETE FROM playback_items
         WHERE config_key = ?
           AND (item_key = ? OR (? <> '' AND history_key = ?))
      `, storageConfigKey, itemKey, historyKey, historyKey).rowsWritten || 0);
    });
    return playbackJson({ ok: true, op: 'adminDeleteItem', deletedRows });
  }

  async runAdminClearAll(request, body, configType) {
    const submittedConfigKey = requireConfigKey(request, body);
    const configKey = await this.resolvePlaybackConfigKey(
      request, submittedConfigKey, configType, requestAliases(request, body)
    );
    const storageConfigKey = scopedConfigKey(configType, configKey);
    const deletedAt = Date.now();
    const payload = JSON.stringify({ scope: 'all', deletedAt, origin: 'dashboard' });
    const deletedRows = this.state.storage.transactionSync(() => {
      this.upsertAdminTombstone(storageConfigKey, 'all', 'all', '', '', '', deletedAt, payload);
      return Number(this.sql.exec(
        'DELETE FROM playback_items WHERE config_key = ?', storageConfigKey
      ).rowsWritten || 0);
    });
    const result = { ok: true, op: 'adminClearAll', deletedRows };
    // Same opt-in unregister as adminClearSpace, for the post-login 清空全部
    // button. Unlike adminClearSpace the key here already went through identity
    // resolution, so it is the canonical itself and safe to unregister.
    if (body && body.forgetIdentity === true) {
      await this.runUnregisterAfterClear(request, configType, configKey, result);
    }
    return playbackJson(result);
  }

  // WebHTV adaptation (dashboard): clear one EXACT stored space, addressed by the
  // key shown in the pre-login interface list. runAdminClearAll resolves the
  // submitted key through the identity registry first, so clearing a row that is
  // merely an alias of another identity — or a canonical whose device reports a
  // different key — would wipe a different, real space than the one the operator
  // clicked. This op never consults the registry: the row you see is the row that
  // is cleared, which is also why it needs no X-WebHTV-Config-Key header and works
  // from the token-only pre-login list.
  //
  // A scope=all tombstone is written in the same transaction so a device that
  // still holds these rows deletes them on its next sync instead of pushing them
  // straight back. Nothing is written when the space held no rows: an already
  // empty space (or a mistyped key) must not leave a deletion marker behind.
  //
  // body.forgetIdentity additionally drops this row's identity registration once
  // the rows are gone, which is what the single merged dashboard button sends. It
  // is opt-in so the op stays usable as a records-only primitive: this row is
  // addressed by its stored key without any registry lookup, so the caller has to
  // decide whether the row it clicked is the registered identity or merely an
  // alias. Unregistering an alias would take its canonical — the real main space —
  // down with it, so the dashboard only sets the flag for canonical rows.
  async runAdminClearSpace(request, body, configType) {
    const configKey = validatedConfigKey(
      body && (body.configKey || body.config_key), 'adminClearSpace requires configKey'
    );
    const storageConfigKey = scopedConfigKey(configType, configKey);
    const deletedAt = Date.now();
    const payload = JSON.stringify({ scope: 'all', deletedAt, origin: 'dashboard' });
    const deletedRows = this.state.storage.transactionSync(() => {
      const deleted = Number(this.sql.exec(
        'DELETE FROM playback_items WHERE config_key = ?', storageConfigKey
      ).rowsWritten || 0);
      if (deleted > 0) {
        this.upsertAdminTombstone(storageConfigKey, 'all', 'all', '', '', '', deletedAt, payload);
      }
      return deleted;
    });
    const result = {
      ok: true, op: 'adminClearSpace', configType, configKey, deletedRows,
      propagated: deletedRows > 0
    };
    if (body && body.forgetIdentity === true) {
      await this.runUnregisterAfterClear(request, configType, configKey, result);
    }
    return playbackJson(result);
  }

  // Read-only registry dump for diagnosing wrong identity bindings: every
  // registered identity with its full address-key lists, every alias with its
  // canonical target, and optionally the raw rows of one space.
  async runAdminInspectIdentity(request, body, configType) {
    const token = playbackToken(request);
    const store = this.identityStore(request);
    const registryKey = await identityRegistryKey(token, configType);
    const snapshot = await store.load(registryKey);
    const registry = normalizeIdentityRegistry(snapshot.state);
    const identities = {};
    for (const [key, identity] of Object.entries(registry.identities)) {
      identities[key] = {
        strictAddressKeys: [...(identity.strictAddressKeys || [])],
        endpointMatchKeys: [...(identity.endpointMatchKeys || [])],
        hostMatchKeys: [...(identity.hostMatchKeys || [])],
        legacyConfigKeys: [...(identity.legacyConfigKeys || [])],
        updatedAt: identity.updatedAt || 0
      };
    }
    const aliases = {};
    for (const [key, alias] of Object.entries(registry.aliases)) {
      aliases[key] = {
        canonicalInterfaceKey: alias.canonicalInterfaceKey,
        kind: alias.kind || '',
        legacyConfigKeys: [...(alias.legacyConfigKeys || [])]
      };
    }
    const result = {
      ok: true, op: 'adminInspectIdentity', configType,
      epoch: registry.epoch, updatedAt: registry.updatedAt, identities, aliases
    };
    if (body.configKey) {
      const storageKey = scopedConfigKey(configType, cleanString(body.configKey, 128));
      const rows = this.sql.exec(`
        SELECT item_key, site_key, vod_id, updated_at,
               COALESCE(NULLIF(json_extract(payload, '$.configName'), ''), '') AS name
          FROM playback_items WHERE config_key = ? ORDER BY updated_at DESC
      `, storageKey).toArray();
      result.rows = rows.map((row) => ({
        itemKey: String(row.item_key || ''),
        siteKey: String(row.site_key || ''),
        vodId: String(row.vod_id || ''),
        name: String(row.name || ''),
        updatedAt: Number(row.updated_at || 0)
      }));
    }
    return playbackJson(result);
  }

  // Surgical removal of wrong identity bindings. For every {canonical, keep}
  // target: delete alias entries pointing at the canonical whose key is not
  // in keep, and strip keys not in keep from the canonical identity's address
  // lists. Playback rows are never touched; stripped device keys self-heal on
  // the next resolve (addIdentityKeys re-derives them from the URL).
  async runAdminUnbindIdentity(request, body, configType) {
    const token = playbackToken(request);
    const store = this.identityStore(request);
    const registryKey = await identityRegistryKey(token, configType);
    const targets = Array.isArray(body.targets) ? body.targets : [];
    if (!targets.length) throw playbackHttpError(400, 'adminUnbindIdentity requires targets');
    const plans = [];
    for (const entry of targets) {
      const canonical = normalizeIdentityKey(entry && entry.canonical, 'target.canonical');
      const keep = new Set((Array.isArray(entry && entry.keep) ? entry.keep : [])
        .map((item) => normalizeOptionalKey(item, 'target.keep'))
        .filter(Boolean));
      plans.push({ canonical, keep });
    }
    let removedAliases = 0;
    let strippedKeys = 0;
    for (const plan of plans) {
      for (let attempt = 0; attempt < 5; attempt++) {
        const snapshot = await store.load(registryKey);
        const registry = normalizeIdentityRegistry(snapshot.state);
        const identity = registry.identities[plan.canonical];
        if (!identity) throw playbackHttpError(404, `canonical identity not found: ${plan.canonical}`);
        let changed = false;
        for (const [key, alias] of Object.entries(registry.aliases)) {
          if (!alias || alias.canonicalInterfaceKey !== plan.canonical || plan.keep.has(key)) continue;
          delete registry.aliases[key];
          removedAliases += 1;
          changed = true;
        }
        for (const listName of ['strictAddressKeys', 'endpointMatchKeys', 'hostMatchKeys', 'legacyConfigKeys']) {
          const list = identity[listName];
          if (!Array.isArray(list) || !list.length) continue;
          const filtered = list.filter((key) => plan.keep.has(key));
          if (filtered.length !== list.length) {
            strippedKeys += list.length - filtered.length;
            identity[listName] = filtered;
            changed = true;
          }
        }
        if (!changed) break;
        if (await store.compareAndSet(registryKey, snapshot.version, registry)) break;
      }
    }
    return playbackJson({ ok: true, op: 'adminUnbindIdentity', configType, removedAliases, strippedKeys });
  }

  // WebHTV adaptation (dashboard): drop a canonical identity from the registry
  // together with every alias that pointed at it, so an unused or dangling
  // identity disappears from the space list. Clearing records cannot do this:
  // listIdentitySpaces deliberately lists a registered canonical even with
  // items=0 so a merge target always has a visible row, so a space that never
  // held records can only be removed by unregistering it.
  //
  // Playback rows are never touched: forgetting an identity that still has
  // records only turns its space into an unregistered one. Removal is also
  // self-healing — identity.js creates any missing canonical for the key it
  // resolves to, so a device that still reports this interface re-registers the
  // identity on its next resolve. The worst case is that the entry reappears,
  // never a broken sync.
  async runAdminForgetIdentity(request, body, configType) {
    const targets = [...new Set((Array.isArray(body.targets) ? body.targets : [])
      .map((item) => normalizeOptionalKey(item, 'targets'))
      .filter(Boolean))];
    if (!targets.length) throw playbackHttpError(400, 'adminForgetIdentity requires targets');
    const { forgotten, missing } = await this.forgetIdentities(request, configType, targets);
    return playbackJson({ ok: true, op: 'adminForgetIdentity', configType, forgotten, missing });
  }

  // Shared registry removal, used both by adminForgetIdentity and by the combined
  // clear+unregister dashboards buttons. Only the identity registry is touched —
  // playback rows are never read or written here, so this can safely run after a
  // row deletion that already committed in its own transaction.
  //
  // Accepts an alias key as well: it forgets the canonical that key points at,
  // otherwise the alias would be left pointing at a canonical that no longer
  // exists and would be dropped as stale on the next normalize. Returns without
  // writing when nothing matched, so a repeated call is a no-op.
  async forgetIdentities(request, configType, targets) {
    const store = this.identityStore(request);
    const registryKey = await identityRegistryKey(playbackToken(request), configType);
    for (let attempt = 0; attempt < 5; attempt++) {
      const snapshot = await store.load(registryKey);
      const registry = normalizeIdentityRegistry(snapshot.state);
      const forgotten = [];
      const missing = [];
      for (const key of targets) {
        const canonical = registry.aliases[key]?.canonicalInterfaceKey || key;
        if (!registry.identities[canonical]) { missing.push(key); continue; }
        delete registry.identities[canonical];
        for (const [aliasKey, alias] of Object.entries(registry.aliases)) {
          if (alias && alias.canonicalInterfaceKey === canonical) delete registry.aliases[aliasKey];
        }
        forgotten.push(canonical);
      }
      if (!forgotten.length) return { forgotten, missing };
      registry.epoch = Number(registry.epoch || 0) + 1;
      registry.updatedAt = Date.now();
      if (await store.compareAndSet(registryKey, snapshot.version, registry)) {
        return { forgotten, missing };
      }
    }
    throw playbackHttpError(503, 'Identity registry changed concurrently; retry');
  }

  // Rows and the identity registry live in different tables and cannot share one
  // transaction, so a combined clear+unregister reports partial success rather
  // than rolling back a deletion that already committed. The caller sees the
  // deletion count either way and can retry the unregister half on its own.
  async runUnregisterAfterClear(request, configType, configKey, result) {
    try {
      const { forgotten, missing } = await this.forgetIdentities(request, configType, [configKey]);
      result.forgotten = forgotten;
      result.missing = missing;
    } catch (error) {
      result.forgetError = error && error.message ? error.message : String(error);
    }
    return result;
  }

  // WebHTV adaptation: reverse one wrong automatic merge. Before addIdentityKeys
  // was guarded, a device whose App only ever appends sync-source aliases pushed
  // another interface's address clue into whichever canonical its request
  // resolved to; planAutoMergeGroups then folded the two unrelated interfaces
  // together on a later resolve. This op undoes exactly one such fusion: it
  // registers `canonical` as its own identity owning the given address clues,
  // takes those clues (and every alias that pointed at `from`) away from the
  // wrong canonical, re-points the listed alias keys, and moves the affected
  // playback rows so no progress is stranded in the other interface's space.
  async runAdminSplitIdentity(request, body, configType) {
    const token = playbackToken(request);
    const store = this.identityStore(request);
    const registryKey = await identityRegistryKey(token, configType);
    const canonical = normalizeIdentityKey(body.canonical, 'canonical');
    const from = normalizeIdentityKey(body.from, 'from');
    if (canonical === from) throw playbackHttpError(400, 'adminSplitIdentity needs distinct canonical and from values');
    const raw = body.fingerprints && typeof body.fingerprints === 'object' && !Array.isArray(body.fingerprints) ? body.fingerprints : {};
    const fingerprints = {
      strictAddressKeys: normalizeKeyList(raw.strictAddressKeys, 'fingerprints.strictAddressKeys'),
      endpointMatchKeys: normalizeKeyList(raw.endpointMatchKeys, 'fingerprints.endpointMatchKeys'),
      hostMatchKeys: normalizeKeyList(raw.hostMatchKeys, 'fingerprints.hostMatchKeys'),
      legacyConfigKeys: normalizeKeyList(raw.legacyConfigKeys, 'fingerprints.legacyConfigKeys')
    };
    const claimed = [...new Set([
      ...(Array.isArray(body.aliases) ? body.aliases : []).map((item) => normalizeOptionalKey(item, 'aliases')).filter(Boolean),
      ...Object.values(fingerprints).flat()
    ])];
    if (!claimed.length) throw playbackHttpError(400, 'adminSplitIdentity requires fingerprints or aliases');
    const claimedSet = new Set(claimed);

    let stripped = 0;
    let repointed = 0;
    let created = false;
    let done = false;
    for (let attempt = 0; attempt < 5 && !done; attempt++) {
      const snapshot = await store.load(registryKey);
      const registry = normalizeIdentityRegistry(snapshot.state);
      if (!registry.identities[from]) throw playbackHttpError(404, `source identity not found: ${from}`);
      if (!registry.identities[canonical]) {
        registry.identities[canonical] = {
          canonicalInterfaceKey: canonical,
          strictAddressKeys: [],
          endpointMatchKeys: [],
          hostMatchKeys: [],
          legacyConfigKeys: [],
          createdAt: Date.now(),
          updatedAt: Date.now()
        };
        created = true;
      }
      const target = registry.identities[canonical];
      const source = registry.identities[from];
      for (const listName of ['strictAddressKeys', 'endpointMatchKeys', 'hostMatchKeys', 'legacyConfigKeys']) {
        // Take every claimed clue away from the wrong canonical, then hand the
        // clues this split assigns to the new canonical one.
        const previous = source[listName];
        if (Array.isArray(previous)) {
          const kept = previous.filter((key) => !claimedSet.has(key));
          stripped += previous.length - kept.length;
          source[listName] = kept;
        }
        for (const key of fingerprints[listName]) {
          if (!target[listName].includes(key)) target[listName].push(key);
        }
      }
      if (Array.isArray(source.selfLegacyConfigKeys)) {
        source.selfLegacyConfigKeys = source.selfLegacyConfigKeys.filter((key) => !claimedSet.has(key));
      }
      for (const key of claimed) {
        const previous = registry.aliases[key];
        const entry = {
          canonicalInterfaceKey: canonical,
          kind: previous && previous.kind ? previous.kind : 'manual-merge'
        };
        if (previous && Array.isArray(previous.legacyConfigKeys) && previous.legacyConfigKeys.length) {
          entry.legacyConfigKeys = previous.legacyConfigKeys.slice(-8);
        }
        registry.aliases[key] = entry;
        repointed += 1;
      }
      target.updatedAt = Date.now();
      source.updatedAt = Date.now();
      registry.epoch = Number(registry.epoch || 0) + 1;
      registry.updatedAt = Date.now();
      done = await store.compareAndSet(registryKey, snapshot.version, registry);
    }
    if (!done) throw playbackHttpError(503, 'Identity registry changed concurrently; retry the split');

    let movedRows = 0;
    for (const source of Array.isArray(body.moveSpaces) ? body.moveSpaces : []) {
      movedRows += this.movePlaybackRows(configType, canonical, cleanString(source, 128));
    }
    for (const entry of Array.isArray(body.moveRows) ? body.moveRows : []) {
      movedRows += this.movePlaybackRows(
        configType, canonical, cleanString(entry && entry.from, 128), cleanString(entry && entry.name, 64)
      );
    }
    return playbackJson({ ok: true, op: 'adminSplitIdentity', configType, canonical, from, created, stripped, repointed, movedRows });
  }

  // Move every playback row of one space into another. migrateIdentitySpaces is a
  // merge primitive that deliberately leaves the source space intact; a split must
  // not, or the other interface keeps listing records it no longer owns. nameOf
  // optionally restricts the move to one interface name, which is the only clue a
  // stored row carries about the sync source that pushed it.
  movePlaybackRows(configType, targetKey, sourceKey, nameOf = '') {
    if (!sourceKey || sourceKey === targetKey) return 0;
    const target = scopedConfigKey(configType, targetKey);
    const source = scopedConfigKey(configType, sourceKey);
    const nameClause = nameOf ? " AND COALESCE(NULLIF(json_extract(payload, '$.configName'), ''), '') = ?" : '';
    return this.state.storage.transactionSync(() => {
      const base = this.sequenceValue();
      const copied = this.sql.exec(`
        INSERT INTO playback_items
          (config_key, item_key, history_key, site_key, vod_id, updated_at, seq, payload)
        SELECT ?, item_key, history_key, site_key, vod_id, updated_at,
               ? + ROW_NUMBER() OVER (ORDER BY updated_at, item_key), payload
          FROM playback_items WHERE config_key = ?${nameClause}
        ON CONFLICT(config_key, item_key) DO UPDATE SET
          history_key = excluded.history_key,
          site_key = excluded.site_key,
          vod_id = excluded.vod_id,
          updated_at = excluded.updated_at,
          seq = excluded.seq,
          payload = excluded.payload
        WHERE excluded.updated_at > playback_items.updated_at
      `, target, base, source, ...(nameOf ? [nameOf] : []));
      const scanned = Number(copied.rowsRead || 0);
      if (scanned > 0) {
        this.sql.exec("UPDATE playback_meta SET value = ? WHERE key = 'sequence'", base + scanned);
      }
      const removed = this.sql.exec(
        `DELETE FROM playback_items WHERE config_key = ?${nameClause}`,
        source, ...(nameOf ? [nameOf] : [])
      );
      return Number(removed.rowsWritten || 0);
    });
  }

  // WebHTV adaptation (ops): move playback rows between two EXISTING spaces
  // without touching the identity registry. adminSplitIdentity only moves rows
  // as part of creating or re-pointing an identity, so rows stranded in a space
  // that is merely an alias of the right canonical — or already present there —
  // had no cleanup tool. Rows only; identity bindings stay untouched.
  runAdminMoveRows(body, configType) {
    const requested = Array.isArray(body.moves) ? body.moves : [];
    if (!requested.length) throw playbackHttpError(400, 'adminMoveRows requires a non-empty moves array');
    let movedRows = 0;
    const moves = [];
    for (const move of requested) {
      const from = normalizeIdentityKey(move && move.from, 'moves[].from');
      const to = normalizeIdentityKey(move && move.to, 'moves[].to');
      if (from === to) throw playbackHttpError(400, 'adminMoveRows needs distinct from and to values');
      const name = cleanString(move && move.name, 64);
      const moved = this.movePlaybackRows(configType, to, from, name);
      movedRows += moved;
      moves.push({ from, to, name, moved });
    }
    return playbackJson({ ok: true, op: 'adminMoveRows', configType, movedRows, moves });
  }

  upsertAdminTombstone(storageConfigKey, markerKey, scope, historyKey, siteKey, vodId, deletedAt, payload) {
    const seq = this.nextSequence();
    this.sql.exec(`
      INSERT INTO playback_tombstones
        (config_key, marker_key, scope, history_key, site_key, vod_id, deleted_at, seq, payload,
         media_type, tmdb_id, season_number)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(config_key, marker_key) DO UPDATE SET
        scope = excluded.scope,
        history_key = excluded.history_key,
        site_key = excluded.site_key,
        vod_id = excluded.vod_id,
        deleted_at = excluded.deleted_at,
        seq = excluded.seq,
        payload = excluded.payload
    `, storageConfigKey, markerKey, scope, historyKey, siteKey, vodId, deletedAt, seq, payload,
      '', 0, -1);
  }

  // WebHTV adaptation: identity resolution with lazy auto-merge. Pass 1
  // collapses any same-interface identity group before the official resolver
  // sees the registry; pass 2 auto-answers confirm_required (the App cannot).
  // Both passes are best effort: a failure never blocks the App's sync — the
  // next resolve simply retries.
  async resolveIdentityWithAutoMerge(request, input) {
    const token = playbackToken(request);
    const store = this.identityStore(request);
    return resolveWithAutoMerge(store, token, input, {
      autoMergeGroups: AUTO_MERGE_IDENTITIES ? () => this.autoMergeIdentityGroups(store, token, input.configType) : null
    });
  }

  async autoMergeIdentityGroups(store, token, configType) {
    const registryKey = await identityRegistryKey(token, configType);
    const snapshot = await store.load(registryKey);
    const registry = normalizeIdentityRegistry(snapshot.state);
    const counts = this.spaceCounts(Object.keys(registry.identities), configType);
    const plans = planAutoMergeGroups(registry, (key) => counts.get(scopedConfigKey(configType, key)) || 0);
    for (const plan of plans) {
      await mergeIdentityRegistry(store, token, configType, plan.target, plan.sources);
    }
  }

  // WebHTV adaptation: write/read-time identity bootstrap. The official
  // protocol only unifies spaces when a device POSTs /identity/resolve;
  // webhook-only devices, older App builds, and failed/timed-out resolves
  // never do that, so their random interfaceKeys stay isolated forever. This
  // runs on every ingest/pull and is best effort — any failure must never
  // block the actual sync request.
  //
  // Pass 1 (strong): address fingerprints carried in X-WebHTV-Config-Aliases
  // adopt the submitted key only when at least two distinct fingerprints
  // resolve to the same single registered canonical identity — the strong
  // signal the official resolver adopts on empty sources. A lone host-tier
  // hit is ambiguous (unrelated interfaces share proxy hosts) and is left to
  // the dashboard.
  //
  // Pass 2 (weak, reversible): unregistered spaces whose latest records carry
  // the same exact configName are collapsed. Same-name match against multiple
  // different canonical identities is ambiguous and left alone for the
  // dashboard. Migration copies rows without deleting the source, so a wrong
  // guess can be undone by removing the alias.
  async autoUnifyIdentity(request, { configType, submittedConfigKey, aliases, hintName }) {
    try {
      const token = playbackToken(request);
      const store = this.identityStore(request);
      const submitted = normalizeIdentityKey(submittedConfigKey, 'configKey');
      if (!submitted) return;
      const registryKey = await identityRegistryKey(token, configType);
      const snapshot = await store.load(registryKey);
      // Let: the fingerprint pass re-reads this only when its merge wrote.
      let registry = normalizeIdentityRegistry(snapshot.state);
      const bound = boundCanonicalKey(registry, submitted);

      // Pass 1: fingerprint adoption for an unbound submitted key. Require at
      // least two distinct fingerprints to agree on one canonical identity:
      // the App mixes every address tier (URL hash + endpoint + host) in the
      // aliases header, so a genuine same-interface device matches through
      // several fingerprints, while an unrelated interface that only shares a
      // proxy host matches through exactly one host-tier fingerprint and must
      // stay separate (single-hit adoption caused cross-interface merges).
      if (!bound) {
        const fingerprints = [...new Set((Array.isArray(aliases) ? aliases : [])
          .map((item) => normalizeOptionalKey(item, 'config alias'))
          .filter(Boolean))];
        const hits = new Map();
        for (const fp of fingerprints) {
          const canonical = registry.identities[fp] ? fp : registry.aliases[fp]?.canonicalInterfaceKey || '';
          if (!canonical) continue;
          const list = hits.get(canonical);
          if (list) list.push(fp);
          else hits.set(canonical, [fp]);
        }
        if (hits.size === 1) {
          const [target, matched] = [...hits][0];
          if (matched.length >= 2) {
            const result = await mergeIdentityRegistry(store, token, configType, target, [submitted]);
            await this.tagAutoAliases(request, registryKey, target, [submitted], 'auto-fingerprint', fingerprints);
            if (Array.isArray(result.merged) && result.merged.includes(submitted)) return;
            if (result.alreadyMerged) return;
            // The merge above may have rewritten the registry even though this
            // key was not adopted, so the name pass below must re-read it.
            registry = normalizeIdentityRegistry((await store.load(registryKey)).state);
          }
        }
      }

      // Pass 2: name-based collapse across all stored spaces of this type.
      // Cheap short-circuit: need at least one non-empty unregistered name.
      const spaces = this.spaceSnapshot(configType);
      const plans = planNameUnifyGroups(
        registry,
        spaces,
        submitted,
        cleanString(hintName, 2048)
      );
      for (const plan of plans) {
        const result = await mergeIdentityRegistry(store, token, configType, plan.target, plan.sources);
        await this.tagAutoAliases(request, registryKey, plan.target, plan.sources, 'auto-name', []);
        if (!result.alreadyMerged && Array.isArray(result.merged)) {
          // Migration runs inside mergeIdentityRegistry; nothing else to do.
        }
      }
    } catch (error) {
      // Never break sync because of auto-unification; the next request retries.
      console.error('playback identity auto-unify failed', error && error.stack ? error.stack : error);
    }
  }

  // Best-effort relabel of auto-created aliases so the dashboard can show why
  // a merge happened and a future "split" can target them. Also attaches the
  // adopting device's own fingerprints so pullRewriteKey prefers a hash that
  // exact device build recognizes.
  async tagAutoAliases(request, registryKey, target, sources, kind, fingerprints) {
    try {
      const store = this.identityStore(request);
      const ownFingerprints = (Array.isArray(fingerprints) ? fingerprints : [])
        .map((item) => String(item || '').trim().toLowerCase())
        .filter((item) => /^[0-9a-f]{64}$/.test(item)).slice(-8);
      for (let attempt = 0; attempt < 3; attempt++) {
        const snapshot = await store.load(registryKey);
        const registry = normalizeIdentityRegistry(snapshot.state);
        let changed = false;
        for (const source of sources) {
          const alias = registry.aliases[source];
          if (!alias || alias.canonicalInterfaceKey !== target) continue;
          if (kind && alias.kind !== kind) { alias.kind = kind; changed = true; }
          if (ownFingerprints.length && !alias.legacyConfigKeys?.length) {
            alias.legacyConfigKeys = ownFingerprints;
            changed = true;
          }
        }
        if (!changed) return;
        if (await store.compareAndSet(registryKey, snapshot.version, registry)) return;
      }
    } catch {
      // Best effort.
    }
  }

  // One aggregated scan of every stored space of a config type: bare key,
  // record count, earliest/latest timestamp and the winning (majority)
  // configName plus the size of that group. Read-only; callers decide whether
  // any write is needed.
  spaceSnapshot(configType) {
    const where = configType === 'vod'
      ? "config_key NOT LIKE 'live:%' AND config_key NOT LIKE 'wall:%'"
      : 'config_key LIKE ?';
    const args = configType === 'vod' ? [] : [`${configType}:%`];
    const prefix = configType === 'vod' ? '' : `${configType}:`;
    return [...this.spaceNameIndex(where, args).values()].map((entry) => ({
      storageKey: entry.storageKey,
      key: entry.storageKey.slice(prefix.length),
      items: entry.items,
      firstAt: entry.firstAt,
      latest: entry.latest,
      name: entry.name,
      nameItems: entry.nameItems
    }));
  }

  // Record counts for identity members, keyed by storage config key
  // (bare key for vod, type-prefixed otherwise).
  spaceCounts(keys, configType) {
    const storageKeys = [...new Set((Array.isArray(keys) ? keys : []).map((key) => scopedConfigKey(configType, key)))];
    const counts = new Map();
    if (!storageKeys.length) return counts;
    const placeholders = storageKeys.map(() => '?').join(',');
    const rows = this.sql.exec(`SELECT config_key, COUNT(*) AS items FROM playback_items WHERE config_key IN (${placeholders}) GROUP BY config_key`, ...storageKeys).toArray();
    for (const row of rows) counts.set(String(row.config_key || ''), Number(row.items || 0));
    return counts;
  }

  identityStore(request) {
    const token = playbackToken(request);
    const sql = this.sql;
    return {
      persistent: true,
      isConfigured: () => true,
      async load(key) {
        const row = firstRow(sql.exec('SELECT version, state FROM playback_identity_registry WHERE registry_key = ?', key));
        if (!row) return { version: null, state: null };
        let state = null;
        try { state = JSON.parse(row.state); } catch { throw new Error('Invalid identity registry state'); }
        return { version: Number(row.version || 0), state };
      },
      async compareAndSet(key, version, state) {
        const current = firstRow(sql.exec('SELECT version FROM playback_identity_registry WHERE registry_key = ?', key));
        const currentVersion = current ? Number(current.version || 0) : null;
        if (currentVersion !== version && !(version == null && currentVersion == null)) return false;
        const nextVersion = currentVersion == null ? 1 : currentVersion + 1;
        sql.exec('INSERT INTO playback_identity_registry (registry_key, version, state) VALUES (?, ?, ?) ON CONFLICT(registry_key) DO UPDATE SET version = excluded.version, state = excluded.state', key, nextVersion, JSON.stringify(state));
        return true;
      },
      migrateIdentitySpaces: async (identityToken, configType, canonicalInterfaceKey, sourceKeys) => this.migrateIdentitySpaces(identityToken, configType, canonicalInterfaceKey, sourceKeys)
    };
  }

  async resolvePlaybackConfigKey(request, submittedConfigKey, configType, aliases = []) {
    return resolveConfigKey(this.identityStore(request), playbackToken(request), configType, submittedConfigKey, aliases);
  }

  migrateIdentitySpaces(token, configType, canonicalInterfaceKey, sourceKeys) {
    const sources = [...new Set((sourceKeys || [])
      .filter((item) => item && item !== canonicalInterfaceKey)
      .map((item) => scopedConfigKey(configType, item)))];
    const canonicalStorageKey = scopedConfigKey(configType, canonicalInterfaceKey);
    if (!sources.length) return { migrated: false, pending: false, resetSince: false };
    let migrated = false;
    this.state.storage.transactionSync(() => {
      for (const source of sources) {
        // WebHTV adaptation: set-based bulk copy. The previous row-by-row loop
        // burned ~2 rows written per stored row plus one DELETE per tombstone on
        // every merge, which exhausted the Durable Objects free-tier daily
        // rows-written quota once 610 historical tombstones made every replay
        // expensive. Each statement only writes rows strictly newer than the
        // canonical copy, so replaying a completed migration writes nothing.
        const base = this.sequenceValue();
        const items = this.sql.exec(`
          INSERT INTO playback_items
            (config_key, item_key, history_key, site_key, vod_id, updated_at, seq, payload)
          SELECT ?, item_key, history_key, site_key, vod_id, updated_at,
                 ? + ROW_NUMBER() OVER (ORDER BY updated_at, item_key), payload
            FROM playback_items WHERE config_key = ?
          ON CONFLICT(config_key, item_key) DO UPDATE SET
            history_key = excluded.history_key,
            site_key = excluded.site_key,
            vod_id = excluded.vod_id,
            updated_at = excluded.updated_at,
            seq = excluded.seq,
            payload = excluded.payload
          WHERE excluded.updated_at > playback_items.updated_at
        `, canonicalStorageKey, base, source);
        // Sequence values consumed by the scan (ROW_NUMBER covers every scanned
        // row, written or not), so the next stage starts past them.
        const itemScan = Number(items.rowsRead || 0);
        const tombstoneBase = base + itemScan;
        const tombstones = this.sql.exec(`
          INSERT INTO playback_tombstones
            (config_key, marker_key, scope, history_key, site_key, vod_id, deleted_at, seq, payload,
             media_type, tmdb_id, season_number)
          SELECT ?, marker_key, scope, history_key, site_key, vod_id, deleted_at,
                 ? + ROW_NUMBER() OVER (ORDER BY deleted_at, marker_key), payload,
                 media_type, tmdb_id, season_number
            FROM playback_tombstones WHERE config_key = ?
          ON CONFLICT(config_key, marker_key) DO UPDATE SET
            scope = excluded.scope,
            history_key = excluded.history_key,
            site_key = excluded.site_key,
            vod_id = excluded.vod_id,
            deleted_at = excluded.deleted_at,
            seq = excluded.seq,
            payload = excluded.payload,
            media_type = excluded.media_type,
            tmdb_id = excluded.tmdb_id,
            season_number = excluded.season_number
          WHERE excluded.deleted_at > playback_tombstones.deleted_at
        `, canonicalStorageKey, tombstoneBase, source);
        const tombstoneScan = Number(tombstones.rowsRead || 0);
        if (itemScan + tombstoneScan > 0) {
          this.sql.exec("UPDATE playback_meta SET value = ? WHERE key = 'sequence'", tombstoneBase + tombstoneScan);
        }
        // One set-based sweep replaces the old per-tombstone DELETE; it only
        // removes rows a tombstone actually covers, so replays write nothing.
        const sweep = this.sql.exec(`
          DELETE FROM playback_items WHERE config_key = ? AND EXISTS (
            SELECT 1 FROM playback_tombstones t
             WHERE t.config_key = playback_items.config_key
               AND t.deleted_at >= playback_items.updated_at
               AND (t.scope = 'all'
                 OR (t.scope = 'site' AND t.site_key = playback_items.site_key)
                 OR (t.scope = 'season'
                     AND json_extract(playback_items.payload, '$.mediaType') = t.media_type
                     AND json_extract(playback_items.payload, '$.tmdbId') = t.tmdb_id
                     AND json_extract(playback_items.payload, '$.seasonNumber') = t.season_number
                     AND (t.site_key = '' OR t.site_key = playback_items.site_key))
                 OR (t.scope = 'item' AND ((t.site_key = playback_items.site_key AND t.vod_id = playback_items.vod_id)
                                        OR (t.history_key <> '' AND t.history_key = playback_items.history_key))))
          )
        `, canonicalStorageKey);
        this.sql.exec(`
          INSERT OR IGNORE INTO playback_events (config_key, event_id, received_at)
          SELECT ?, event_id, received_at FROM playback_events WHERE config_key = ?
        `, canonicalStorageKey, source);
        migrated = migrated || Number(items.rowsWritten || 0) > 0
          || Number(tombstones.rowsWritten || 0) > 0 || Number(sweep.rowsWritten || 0) > 0;
      }
    });
    return { migrated, pending: false, resetSince: migrated };
  }

  sequenceValue() {
    return Number(this.sql.exec("SELECT value FROM playback_meta WHERE key = 'sequence'").one().value || 0);
  }

  nextSequence() {
    const row = firstRow(this.sql.exec("SELECT value FROM playback_meta WHERE key = 'sequence'"));
    const next = Number(row?.value || 0) + 1;
    this.sql.exec("UPDATE playback_meta SET value = ? WHERE key = 'sequence'", next);
    return next;
  }

  applyUpsert(event, receivedAt) {
    const storageConfigKey = event.storageConfigKey || event.configKey;
    return this.state.storage.transactionSync(() => {
      // WebHTV adaptation: progress events write NO dedup receipt. A replayed
      // upsert is already fully answered by the tombstone gate and the "not
      // newer" check below — both use <=, so an equal timestamp is skipped
      // without writing and without consuming a sequence number — while the
      // receipt cost one row per progress webhook (the bulk of the table, one
      // row every ~30s per playing device, forever). Receipts remain for
      // deletions, where a payload without deletedAt is re-stamped as "now" and
      // the timestamp gate therefore cannot catch a replay.
      const current = firstRow(this.sql.exec(
        'SELECT updated_at, seq FROM playback_items WHERE config_key = ? AND item_key = ?',
        storageConfigKey,
        event.itemKey
      ));
      // WebHTV quota optimization (Durable Objects rows_read): the scoped scan
      // below reads every tombstone of this space, on every event, and the set
      // only grows for as long as the 90-day retention window holds it. A
      // tombstone can only suppress this event when its deleted_at reaches the
      // event's updated_at, so one indexed range probe over
      // (config_key, deleted_at) can prove that no suppression is possible and
      // skip the scan. The probe ignores scope, so it is a superset of the
      // scoped query: whenever it does find a newer deletion the original scan
      // runs unchanged, and both the skip decision and the reported seq are
      // identical to the previous behaviour in either branch.
      const newerTombstone = firstRow(this.sql.exec(
        'SELECT MAX(deleted_at) AS deleted_at FROM playback_tombstones WHERE config_key = ? AND deleted_at >= ?',
        storageConfigKey,
        event.updatedAt
      ));
      const tombstone = Number(newerTombstone?.deleted_at || 0) > 0 ? firstRow(this.sql.exec(`
        SELECT MAX(deleted_at) AS deleted_at, MAX(seq) AS seq
          FROM playback_tombstones
         WHERE config_key = ? AND (
           scope = 'all'
           OR (scope = 'site' AND site_key = ?)
           OR (scope = 'item' AND ((site_key = ? AND vod_id = ?) OR (history_key <> '' AND history_key = ?)))
           OR (scope = 'season' AND media_type = ? AND tmdb_id = ? AND season_number = ?
               AND (site_key = '' OR site_key = ?))
         )
      `, storageConfigKey, event.siteKey, event.siteKey, event.vodId, event.historyKey,
        String(event.payload.mediaType || '').toLowerCase(),
        Number(event.payload.tmdbId || 0), Number(event.payload.seasonNumber ?? -1),
        event.siteKey)) : null;
      const deletedAt = Number(tombstone?.deleted_at || 0);
      if (deletedAt > 0 && event.updatedAt <= deletedAt) {
        return resultFor(event, 'skipped', Number(tombstone?.seq || 0), 'A newer deletion exists');
      }
      if (current && event.updatedAt <= Number(current.updated_at || 0)) {
        return resultFor(event, 'skipped', Number(current.seq || 0), 'A newer progress record exists');
      }

      const seq = this.nextSequence();
      const payload = JSON.stringify(event.payload);
      this.sql.exec(`
        INSERT INTO playback_items
          (config_key, item_key, history_key, site_key, vod_id, updated_at, seq, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(config_key, item_key) DO UPDATE SET
          history_key = excluded.history_key,
          site_key = excluded.site_key,
          vod_id = excluded.vod_id,
          updated_at = excluded.updated_at,
          seq = excluded.seq,
          payload = excluded.payload
      `, storageConfigKey, event.itemKey, event.historyKey, event.siteKey, event.vodId, event.updatedAt, seq, payload);
      return resultFor(event, current ? 'updated' : 'created', seq, '');
    });
  }

  applyDelete(event, receivedAt) {
    const storageConfigKey = event.storageConfigKey || event.configKey;
    return this.state.storage.transactionSync(() => {
      if (event.eventId && this.hasEvent(storageConfigKey, event.eventId)) {
        return resultFor(event, 'duplicate', 0, 'Event already processed');
      }

      const current = firstRow(this.sql.exec(
        'SELECT deleted_at, seq FROM playback_tombstones WHERE config_key = ? AND marker_key = ?',
        storageConfigKey,
        event.markerKey
      ));
      if (current && event.deletedAt <= Number(current.deleted_at || 0)) {
        this.recordEvent(storageConfigKey, event.eventId, receivedAt);
        return resultFor(event, 'skipped', Number(current.seq || 0), 'A newer deletion exists');
      }

      const seq = this.nextSequence();
      const payload = JSON.stringify(event.payload);
      this.sql.exec(`
        INSERT INTO playback_tombstones
          (config_key, marker_key, scope, history_key, site_key, vod_id, deleted_at, seq, payload,
           media_type, tmdb_id, season_number)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(config_key, marker_key) DO UPDATE SET
          scope = excluded.scope,
          history_key = excluded.history_key,
          site_key = excluded.site_key,
          vod_id = excluded.vod_id,
          deleted_at = excluded.deleted_at,
          seq = excluded.seq,
          payload = excluded.payload,
          media_type = excluded.media_type,
          tmdb_id = excluded.tmdb_id,
          season_number = excluded.season_number
      `, storageConfigKey, event.markerKey, event.scope, event.historyKey, event.siteKey, event.vodId,
        event.deletedAt, seq, payload, event.mediaType || '', event.tmdbId || 0,
        Number.isInteger(event.seasonNumber) ? event.seasonNumber : -1);

      let deletedRows = 0;
      if (event.scope === 'all') {
        deletedRows = this.sql.exec(
          'DELETE FROM playback_items WHERE config_key = ? AND updated_at <= ?',
          storageConfigKey,
          event.deletedAt
        ).rowsWritten;
      } else if (event.scope === 'site') {
        deletedRows = this.sql.exec(
          'DELETE FROM playback_items WHERE config_key = ? AND site_key = ? AND updated_at <= ?',
          storageConfigKey,
          event.siteKey,
          event.deletedAt
        ).rowsWritten;
      } else if (event.scope === 'season') {
        // Delete every episode snapshot of the season (TMDB identity), plus
        // the concrete row the webhook identified portably. The optional
        // siteKey narrows the TMDB match the same way the App does.
        deletedRows = this.sql.exec(`
          DELETE FROM playback_items
           WHERE config_key = ? AND updated_at <= ?
             AND (
               (json_extract(payload, '$.mediaType') = ?
                AND json_extract(payload, '$.tmdbId') = ?
                AND json_extract(payload, '$.seasonNumber') = ?
                AND (? = '' OR site_key = ?))
               OR item_key = ?
               OR (history_key <> '' AND history_key = ?)
             )
        `, storageConfigKey, event.deletedAt, event.mediaType, event.tmdbId, event.seasonNumber,
          event.siteKey || '', event.siteKey || '', event.itemKey, event.historyKey).rowsWritten;
      } else {
        deletedRows = this.sql.exec(`
          DELETE FROM playback_items
           WHERE config_key = ? AND updated_at <= ?
             AND (item_key = ? OR (history_key <> '' AND history_key = ?))
        `, storageConfigKey, event.deletedAt, event.itemKey, event.historyKey).rowsWritten;
      }
      this.recordEvent(storageConfigKey, event.eventId, receivedAt);
      return { ...resultFor(event, 'deleted', seq, ''), affected: Number(deletedRows || 0) };
    });
  }

  nextSequence() {
    this.sql.exec("UPDATE playback_meta SET value = value + 1 WHERE key = 'sequence'");
    return Number(this.sql.exec("SELECT value FROM playback_meta WHERE key = 'sequence'").one().value || 0);
  }

  hasEvent(configKey, eventId) {
    if (!eventId) return false;
    return Boolean(firstRow(this.sql.exec(
      'SELECT 1 AS found FROM playback_events WHERE config_key = ? AND event_id = ? LIMIT 1',
      configKey,
      eventId
    )));
  }

  recordEvent(configKey, eventId, receivedAt) {
    if (!eventId) return;
    this.sql.exec(
      'INSERT OR IGNORE INTO playback_events (config_key, event_id, received_at) VALUES (?, ?, ?)',
      configKey,
      eventId,
      receivedAt
    );
  }

  cleanup() {
    const now = Date.now();
    const last = Number(this.sql.exec("SELECT value FROM playback_meta WHERE key = 'last_cleanup'").one().value || 0);
    if (now - last < CLEANUP_INTERVAL_MS) return;
    const tombstoneCutoff = now - TOMBSTONE_RETENTION_MS;
    const eventCutoff = now - EVENT_RETENTION_MS;
    this.state.storage.transactionSync(() => {
      this.sql.exec('DELETE FROM playback_tombstones WHERE deleted_at < ?', tombstoneCutoff);
      this.sql.exec('DELETE FROM playback_events WHERE received_at < ?', eventCutoff);
      this.sql.exec("UPDATE playback_meta SET value = ? WHERE key = 'last_cleanup'", now);
    });
  }
}

// WebHTV adaptation: choose the configKey stamped onto pulled changes from
// keys the REQUESTER itself reported (submitted key first, then the alias
// header). The first URL SHA-256 fingerprint wins because every App build
// maps a 64-hex hash back to the local interface; otherwise the submitted
// key is returned unchanged (a UUID only a current build can have sent, and
// a current build recognizes its own UUID). Pure function for testability.
export function selectPullRewriteKey(submittedConfigKey, aliasKeys) {
  const isFingerprint = (key) => typeof key === 'string' && /^[0-9a-f]{64}$/.test(key);
  const candidates = [submittedConfigKey, ...(Array.isArray(aliasKeys) ? aliasKeys : [])];
  return candidates.find(isFingerprint) || submittedConfigKey;
}

// WebHTV adaptation: overlay authoritative column values onto a pulled
// change's stored payload. Rows written by older server builds (and merged in
// by identity migrations) can carry payload JSON that lacks deletedAt or
// carries drifted identity fields even though the canonical columns are
// correct. The App treats a remote deletion without deletedAt as
// deletedAt=now and records a local tombstone, which then blocks every
// younger upsert of the same site/item — the "fetched 38, applied 2,
// skipped 36" failure. Re-hydration makes every emitted change agree with
// the materialised snapshot. Pure function for testability.
export function hydratePullChange(kind, change, row) {
  if (!change || typeof change !== 'object') return change;
  const positive = (value) => {
    const num = Number(value);
    return Number.isFinite(num) && num > 0 ? num : 0;
  };
  const text = (value) => (value == null ? '' : String(value));
  if (kind === 'delete') {
    const deletedAt = positive(row.deleted_at);
    if (deletedAt > 0) change.deletedAt = deletedAt;
    const scope = text(row.scope).trim();
    if (scope) change.scope = scope;
    if (scope === 'all') {
      change.siteKey = '';
      change.vodId = '';
      change.historyKey = '';
    } else if (scope === 'site') {
      if (text(row.site_key)) change.siteKey = text(row.site_key);
      change.vodId = '';
      change.historyKey = '';
    } else if (scope === 'season') {
      if (text(row.site_key)) change.siteKey = text(row.site_key);
      change.vodId = '';
      change.historyKey = '';
      const mediaType = text(row.media_type).trim().toLowerCase();
      const tmdbId = positive(row.tmdb_id);
      const seasonNumber = Number(row.season_number);
      if (mediaType) change.mediaType = mediaType;
      if (tmdbId > 0) change.tmdbId = tmdbId;
      if (Number.isInteger(seasonNumber) && seasonNumber >= 0) change.seasonNumber = seasonNumber;
    } else {
      if (text(row.site_key)) change.siteKey = text(row.site_key);
      if (text(row.vod_id)) change.vodId = text(row.vod_id);
      // The stored history_key carries the ORIGIN device's local cid suffix;
      // only fall back to it when the portable site+vod identity is missing
      // (the App strips historyKey itself whenever site+vod exist).
      const portable = cleanString(change.siteKey, 1024) && cleanString(change.vodId, 8192);
      if (!portable && !cleanString(change.historyKey, 4096) && text(row.history_key)) {
        change.historyKey = text(row.history_key);
      }
    }
    change.action = 'delete';
    change.event = 'playback.deleted';
    change.deleted = true;
  } else {
    const updatedAt = positive(row.updated_at);
    if (updatedAt > 0) change.updatedAt = updatedAt;
    if (text(row.site_key)) change.siteKey = text(row.site_key);
    if (text(row.vod_id)) change.vodId = text(row.vod_id);
    const portable = cleanString(change.siteKey, 1024) && cleanString(change.vodId, 8192);
    if (!portable && !cleanString(change.historyKey, 4096) && text(row.history_key)) {
      change.historyKey = text(row.history_key);
    }
  }
  return change;
}

export function normalizePlaybackEvent(input, configKey, now = Date.now(), fallbackEventId = '') {
  const raw = unwrapPlaybackEvent(input);
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw playbackHttpError(400, 'Invalid playback event');
  configKey = validatedConfigKey(configKey, 'Missing X-WebHTV-Config-Key');
  const bodyConfigKey = normalizeConfigKey(raw.configKey || raw.config_key);
  if (bodyConfigKey.length > 256) throw playbackHttpError(400, 'configKey is too long');
  if (bodyConfigKey && bodyConfigKey !== configKey) throw playbackHttpError(400, 'configKey does not match X-WebHTV-Config-Key');

  const eventName = cleanString(raw.event, 80).toLowerCase();
  const action = cleanString(raw.action || raw.op || raw.operation, 32).toLowerCase();
  const deletion = booleanValue(raw.deleted) || eventName === 'playback.deleted'
    || action === 'delete' || action === 'deleted' || action === 'remove' || action === 'removed';
  const eventId = cleanString(raw.eventId || raw.event_id || fallbackEventId, 160);
  let historyKey = cleanString(raw.historyKey || raw.key, 4096);
  const parts = historyParts(historyKey);
  let siteKey = cleanString(raw.siteKey || raw.site || raw.site_key || parts.siteKey, 1024);
  let vodId = cleanString(raw.vodId || raw.vod_id || raw.videoId || raw.itemId || parts.vodId, 8192);

  if (deletion) {
    const requestedScope = cleanString(raw.scope, 16).toLowerCase();
    if (requestedScope && !['all', 'site', 'item', 'season'].includes(requestedScope)) {
      throw playbackHttpError(400, 'scope must be item, site, season, or all');
    }
    // WebHTV adaptation: TV season deletions are matched by TMDB identity
    // (the App deletes every episode snapshot of one season at once).
    const mediaType = cleanString(raw.mediaType || raw.media_type, 16).toLowerCase();
    const tmdbId = Math.trunc(positiveNumber(raw.tmdbId || raw.tmdb_id));
    const seasonNumberRaw = raw.seasonNumber ?? raw.season_number;
    const seasonNumber = Number.isFinite(Number(seasonNumberRaw)) ? Math.trunc(Number(seasonNumberRaw)) : -1;
    const hasSeasonIdentity = mediaType === 'tv' && tmdbId > 0 && seasonNumber >= 0;
    if (requestedScope === 'season' && !hasSeasonIdentity) {
      throw playbackHttpError(400, 'scope=season requires mediaType=tv, a positive tmdbId and seasonNumber >= 0');
    }
    const scope = requestedScope === 'season'
      ? 'season'
      : normalizeScope(raw.scope, historyKey, siteKey, vodId);
    if (!scope) throw playbackHttpError(400, 'scope=all must be explicit when no item or site identity is provided');
    if (scope === 'site' && !siteKey) throw playbackHttpError(400, 'siteKey is required for a site deletion');
    if (scope === 'item' && !historyKey && (!siteKey || !vodId)) throw playbackHttpError(400, 'historyKey or siteKey + vodId is required for an item deletion');
    if (scope === 'all') {
      historyKey = '';
      siteKey = '';
      vodId = '';
    } else if (scope === 'site') {
      historyKey = '';
      vodId = '';
    }
    const deletedAt = positiveTimestamp(raw.deletedAt || raw.deleted_at || raw.timestamp || raw.updatedAt, 0);
    if (!deletedAt) throw playbackHttpError(400, 'deletedAt or timestamp is required for a deletion');
    const itemKey = portableItemKey(historyKey, siteKey, vodId);
    const markerKey = scope === 'all'
      ? 'all'
      : scope === 'site'
        ? `site\n${siteKey}`
        : scope === 'season'
          ? `season\n${mediaType}\n${tmdbId}\n${seasonNumber}`
          : `item\n${itemKey}`;
    const payload = compactObject({
      schema: PLAYBACK_SCHEMA,
      action: 'delete',
      event: 'playback.deleted',
      eventId,
      configKey,
      historyKey,
      siteKey,
      vodId,
      scope,
      mediaType: scope === 'season' ? mediaType : undefined,
      tmdbId: scope === 'season' ? tmdbId : undefined,
      seasonNumber: scope === 'season' ? seasonNumber : undefined,
      deletedAt
    });
    return {
      kind: 'delete', configKey, eventId, historyKey, siteKey, vodId, scope,
      mediaType: scope === 'season' ? mediaType : '',
      tmdbId: scope === 'season' ? tmdbId : 0,
      seasonNumber: scope === 'season' ? seasonNumber : -1,
      deletedAt, itemKey, markerKey, payload
    };
  }

  if (!siteKey) throw playbackHttpError(400, 'siteKey is required');
  if (!vodId) throw playbackHttpError(400, 'vodId is required');
  const vodName = cleanString(raw.vodName || raw.vod_name || raw.name || raw.title, 2048);
  const episodeName = cleanString(raw.episodeName || raw.episode || raw.episodeTitle || raw.vodRemarks || raw.remarks, 2048);
  const positionMs = positiveNumber(raw.positionMs || raw.position || raw.position_ms || raw.pos);
  const durationMs = positiveNumber(raw.durationMs || raw.duration || raw.duration_ms);
  if (!vodName) throw playbackHttpError(400, 'vodName is required');
  if (!episodeName) throw playbackHttpError(400, 'episodeName is required');
  if (positionMs <= 0) throw playbackHttpError(400, 'positionMs must be greater than 0');
  if (durationMs <= 0) throw playbackHttpError(400, 'durationMs must be greater than 0');
  const updatedAt = positiveTimestamp(raw.updatedAt || raw.updated_at || raw.timestamp || raw.updateTime, now);
  const completed = eventName === 'playback.ended' || booleanValue(raw.completed);
  const suppliedProgress = boundedNumber(raw.progress, 0, 1);
  // WebHTV adaptation: preserve the TV season identity so season-scoped
  // tombstones can block or sweep episode progress rows. The episode number
  // is mandatory too: the App only stamps TMDB identity onto a synced row
  // when hasTmdbEpisodeIdentity() is true (mediaType=tv + tmdbId + season +
  // EPISODE). Without it the receiving device stores the episode without any
  // TMDB identity, so a later season-scoped delete finds no local row and is
  // skipped — deletions then never propagate to the other device.
  const upsertMediaType = cleanString(raw.mediaType || raw.media_type, 16).toLowerCase();
  const upsertTmdbId = Math.trunc(positiveNumber(raw.tmdbId || raw.tmdb_id));
  const upsertSeasonRaw = raw.seasonNumber ?? raw.season_number;
  const upsertSeason = Number.isFinite(Number(upsertSeasonRaw)) ? Math.trunc(Number(upsertSeasonRaw)) : null;
  const upsertEpisodeRaw = raw.tmdbEpisodeNumber ?? raw.episodeNumber ?? raw.episode_number ?? raw.tmdb_episode_number;
  const upsertEpisode = Number.isFinite(Number(upsertEpisodeRaw)) ? Math.trunc(Number(upsertEpisodeRaw)) : null;
  const payload = compactObject({
    schema: PLAYBACK_SCHEMA,
    action: 'upsert',
    event: eventName || undefined,
    eventId,
    configKey,
    configName: cleanString(raw.configName || raw.config_name, 2048),
    historyKey,
    siteKey,
    siteName: cleanString(raw.siteName, 2048),
    vodId,
    vodName,
    vodPic: cleanString(raw.vodPic || raw.vod_pic || raw.pic || raw.poster, 8192),
    flag: cleanString(raw.flag || raw.vodFlag || raw.line || raw.source, 2048),
    episodeName,
    episodeUrl: cleanString(raw.episodeUrl || raw.episode_url || raw.url || raw.playUrl, 8192),
    positionMs: Math.min(positionMs, durationMs),
    durationMs,
    progress: suppliedProgress > 0 ? suppliedProgress : Math.min(positionMs, durationMs) / durationMs,
    speed: positiveNumber(raw.speed) || 1,
    completed,
    mediaType: upsertMediaType || undefined,
    tmdbId: upsertTmdbId > 0 ? upsertTmdbId : undefined,
    seasonNumber: upsertSeason !== null && upsertSeason >= 0 ? upsertSeason : undefined,
    tmdbEpisodeNumber: upsertEpisode !== null && upsertEpisode > 0 ? upsertEpisode : undefined,
    updatedAt,
    clientKey: cleanString(raw.clientKey, 256)
  });
  return {
    kind: 'upsert',
    configKey,
    eventId,
    historyKey,
    siteKey,
    vodId,
    itemKey: portableItemKey(historyKey, siteKey, vodId),
    updatedAt,
    payload
  };
}

export function parseCursor(value) {
  if (value == null || String(value).trim() === '') return 0;
  const parsed = Number(String(value).trim());
  if (!Number.isSafeInteger(parsed) || parsed < 0) throw playbackHttpError(400, 'Invalid X-WebHTV-Since cursor');
  return parsed;
}

export function parseLimit(value) {
  if (value == null || String(value).trim() === '') return DEFAULT_LIMIT;
  const text = String(value).trim();
  if (!/^\d+$/.test(text)) return DEFAULT_LIMIT;
  const parsed = Number(text);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) return DEFAULT_LIMIT;
  return Math.min(MAX_LIMIT, parsed);
}

function extractPlaybackEvents(body) {
  if (Array.isArray(body)) return body;
  if (!body || typeof body !== 'object') return [];
  const changes = firstArray(body, 'changes', 'operations');
  if (changes) return changes.map((item) => inheritPlaybackFields(item, body));

  const result = [];
  const deletions = firstArray(body, 'deleted', 'deletions', 'tombstones', 'removed', 'deletedItems');
  if (deletions) {
    for (const item of deletions) {
      const value = typeof item === 'string' ? { historyKey: item } : item;
      result.push(inheritPlaybackFields({ ...value, action: 'delete' }, body));
    }
  }
  const items = firstArray(body, 'items', 'records', 'upserts', 'list');
  if (items) for (const item of items) result.push(inheritPlaybackFields(item, body));
  if (result.length) return result;
  if (Array.isArray(body.data)) return body.data.map((item) => inheritPlaybackFields(item, body));
  return [body];
}

function unwrapPlaybackEvent(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return input;
  if (!input.data || typeof input.data !== 'object' || Array.isArray(input.data)) return input;
  return inheritPlaybackFields(input.data, input);
}

function inheritPlaybackFields(input, parent) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return input;
  const output = { ...input };
  for (const key of ['action', 'op', 'operation', 'event', 'eventId', 'deleted', 'scope', 'deletedAt', 'timestamp', 'updatedAt', 'configKey', 'configName']) {
    if (output[key] == null && parent && parent[key] != null && typeof parent[key] !== 'object') output[key] = parent[key];
  }
  return output;
}

async function readPlaybackJson(request) {
  const declared = Number(request.headers.get('content-length') || 0);
  if (declared > MAX_BODY_BYTES) throw playbackHttpError(413, 'Playback payload is too large');
  const text = await request.text();
  if (new TextEncoder().encode(text).byteLength > MAX_BODY_BYTES) throw playbackHttpError(413, 'Playback payload is too large');
  if (!text.trim()) throw playbackHttpError(400, 'Playback payload is empty');
  try {
    return JSON.parse(text);
  } catch {
    throw playbackHttpError(400, 'Invalid JSON body');
  }
}

function scopedConfigKey(configType, configKey) {
  const type = normalizeConfigType(configType);
  return type === 'vod' ? configKey : `${type}:${configKey}`;
}

function requestAliases(request, body = null) {
  const header = String(request.headers.get('x-webhtv-config-aliases') || '').split(',').map((item) => item.trim()).filter(Boolean);
  const bodyAliases = body && !Array.isArray(body) && Array.isArray(body.configAliases) ? body.configAliases.map((item) => String(item || '').trim()).filter(Boolean) : [];
  if (header.length && bodyAliases.length && JSON.stringify(header) !== JSON.stringify(bodyAliases)) throw playbackHttpError(400, 'configAliases does not match X-WebHTV-Config-Aliases');
  return [...new Set([...header, ...bodyAliases])].slice(0, 16);
}

function requireConfigKey(request, body = null) {
  const header = validatedConfigKey(request.headers.get('x-webhtv-config-key'));
  const bodyKey = body && !Array.isArray(body) ? validatedConfigKey(body.configKey || body.config_key) : '';
  if (header && bodyKey && header !== bodyKey) throw playbackHttpError(400, 'configKey does not match X-WebHTV-Config-Key');
  const configKey = header || bodyKey;
  if (!configKey) throw playbackHttpError(400, 'Missing X-WebHTV-Config-Key');
  return configKey;
}

function normalizeConfigKey(value) {
  return String(value == null ? '' : value).trim().toLowerCase();
}

function validatedConfigKey(value, missingMessage = '') {
  const configKey = normalizeConfigKey(value);
  if (configKey.length > 256) throw playbackHttpError(400, 'configKey is too long');
  if (!configKey && missingMessage) throw playbackHttpError(400, missingMessage);
  return configKey;
}

function normalizeScope(value, historyKey, siteKey, vodId) {
  const scope = cleanString(value, 16).toLowerCase();
  if (scope === 'all' || scope === 'site' || scope === 'item') return scope;
  if (historyKey || (siteKey && vodId)) return 'item';
  if (siteKey) return 'site';
  return '';
}

function portableItemKey(historyKey, siteKey, vodId) {
  if (siteKey && vodId) return `${siteKey}\n${vodId}`;
  return `history\n${historyKey}`;
}

function historyParts(historyKey) {
  const parts = String(historyKey || '').split('@@@');
  return { siteKey: parts[0] || '', vodId: parts[1] || '' };
}

function positiveTimestamp(value, fallback) {
  const number = Number(value);
  return Number.isSafeInteger(number) && number > 0 ? number : fallback;
}

function positiveNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

function boundedNumber(value, min, max) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  return Math.max(min, Math.min(max, number));
}

function booleanValue(value) {
  if (value === true || value === 1) return true;
  const text = String(value == null ? '' : value).trim().toLowerCase();
  return text === 'true' || text === '1' || text === 'yes';
}

function cleanString(value, maxLength) {
  return String(value == null ? '' : value).trim().slice(0, maxLength);
}

function compactObject(value) {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined && item !== ''));
}

function firstArray(object, ...keys) {
  for (const key of keys) if (Array.isArray(object[key])) return object[key];
  return null;
}

function firstRow(cursor) {
  const result = cursor.next();
  return result.done ? null : result.value;
}

function resultFor(event, action, sequence, message) {
  return compactObject({
    action,
    sequence,
    message,
    eventId: event.eventId,
    configKey: event.configKey,
    historyKey: event.historyKey,
    siteKey: event.siteKey,
    vodId: event.vodId,
    updatedAt: event.updatedAt,
    deletedAt: event.deletedAt
  });
}

function playbackToken(request) {
  const direct = String(request.headers.get('x-webhtv-token') || '').trim();
  if (direct) return direct;
  const authorization = request.headers.get('authorization') || '';
  const match = authorization.match(/^Bearer\s+(.+)$/i);
  return match ? String(match[1] || '').trim() : '';
}

function basePlaybackPath(pathname) {
  const path = normalizePath(pathname);
  for (const base of PLAYBACK_SYNC_PATHS) if (path === base || path === `${base}/status`) return base;
  return '/api/playback/sync';
}

function normalizePath(pathname) {
  const path = String(pathname || '').replace(/\/+$/, '');
  return path || '/';
}

async function sha256(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((item) => item.toString(16).padStart(2, '0')).join('');
}

function playbackJson(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store'
    }
  });
}

function playbackError(status, message) {
  return playbackCors(playbackJson({ ok: false, error: message }, status));
}

function playbackCors(response) {
  const headers = new Headers(response.headers);
  headers.set('access-control-allow-origin', '*');
  headers.set('access-control-allow-methods', 'GET,POST,OPTIONS');
  headers.set('access-control-allow-headers', [
    'authorization',
    'content-type',
    'idempotency-key',
    'x-webhtv-token',
    'x-webhtv-config-key',
    'x-webhtv-config-name',
    'x-webhtv-config-aliases',
    'x-webhtv-config-type',
    'x-webhtv-identity-version',
    'x-webhtv-address-match-version',
    'x-webhtv-request-id',
    'x-webhtv-timestamp',
    'x-webhtv-since',
    'x-webhtv-limit',
    'x-webhtv-webhook-id',
    'x-webhtv-dedupe-key'
  ].join(','));
  headers.set('access-control-expose-headers', '*');
  headers.set('access-control-max-age', '86400');
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

// WebHTV adaptation (dashboard): group registered identities of one registry
// that share any strong address clue. Host-only clues are deliberately
// excluded: cnb.cool or gh-proxy.org host hashes would fuse unrelated
// interfaces that merely ride the same proxy. Returns { groups, groupIdOf }.
export function groupRegisteredIdentities(registry) {
  const identities = registry && registry.identities ? registry.identities : {};
  const keys = Object.keys(identities);
  const parent = new Map(keys.map((key) => [key, key]));
  const find = (key) => {
    let root = key;
    while (parent.get(root) !== root) root = parent.get(root);
    while (parent.get(key) !== root) {
      const next = parent.get(key);
      parent.set(key, root);
      key = next;
    }
    return root;
  };
  const clueOwner = new Map();
  for (const [key, identity] of Object.entries(identities)) {
    for (const clue of [
      ...(identity.strictAddressKeys || []),
      ...(identity.endpointMatchKeys || []),
      ...(identity.legacyConfigKeys || [])
    ]) {
      const owner = clueOwner.get(clue);
      if (!owner) clueOwner.set(clue, key);
      else if (owner !== key) parent.set(find(owner), find(key));
    }
  }
  const membersByRoot = new Map();
  for (const key of keys) {
    const root = find(key);
    if (!membersByRoot.has(root)) membersByRoot.set(root, []);
    membersByRoot.get(root).push(key);
  }
  const groups = [];
  const groupIdOf = new Map();
  for (const [root, members] of membersByRoot) {
    members.sort();
    groups.push({ id: root, members });
    for (const member of members) groupIdOf.set(member, root);
  }
  return { groups, groupIdOf };
}

// WebHTV adaptation: manual identity merge for the dashboard. The official App
// never sends confirm:true, and a key that is already a registered identity
// always short-circuits to keep/conflict in chooseIdentity, so same-interface
// spaces deadlock forever with confirm_required. This performs the official
// merge steps directly: create the target identity when needed, union every
// source identity's address clues into it, demote each source to an alias, and
// move the source spaces' items/tombstones/events into the target space
// (newest wins). Source rows are kept in place for rollback, matching the
// official migrateIdentitySpaces behaviour. Re-running the same merge is a
// no-op because every source then resolves to the target.
export async function mergeIdentityRegistry(store, token, configType, targetKey, sourceKeys) {
  const canonical = normalizeIdentityKey(targetKey, 'targetKey');
  const requested = [...new Set((Array.isArray(sourceKeys) ? sourceKeys : [])
    .map((item) => normalizeOptionalKey(item, 'sourceKeys'))
    .filter(Boolean))];
  if (!requested.length) throw playbackHttpError(400, 'sourceKeys is required');
  const registryKey = await identityRegistryKey(token, configType);
  for (let attempt = 0; attempt < 5; attempt++) {
    const snapshot = await store.load(registryKey);
    const registry = normalizeIdentityRegistry(snapshot.state);
    const resolvedTarget = registry.aliases[canonical]?.canonicalInterfaceKey || canonical;
    const sources = [];
    const skipped = [];
    for (const key of requested) {
      const resolved = registry.aliases[key]?.canonicalInterfaceKey || key;
      if (resolved === resolvedTarget) {
        skipped.push(key);
        continue;
      }
      if (!sources.includes(resolved)) sources.push(resolved);
    }
    if (!sources.length) {
      return { canonical: resolvedTarget, merged: [], skipped, migration: { migrated: false, resetSince: false }, alreadyMerged: true };
    }
    if (!registry.identities[resolvedTarget]) {
      registry.identities[resolvedTarget] = {
        canonicalInterfaceKey: resolvedTarget,
        strictAddressKeys: [],
        endpointMatchKeys: [],
        hostMatchKeys: [],
        legacyConfigKeys: [],
        createdAt: Date.now(),
        updatedAt: Date.now()
      };
    }
    const target = registry.identities[resolvedTarget];
    for (const source of sources) {
      const identity = registry.identities[source];
      // Preserve the merged device's own URL fingerprints when demoting it to
      // an alias, so pull keeps stamping its changes with a key it recognizes.
      const previousAlias = registry.aliases[source];
      const ownFingerprints = identity && Array.isArray(identity.selfLegacyConfigKeys) && identity.selfLegacyConfigKeys.length
        ? identity.selfLegacyConfigKeys
        : (previousAlias && Array.isArray(previousAlias.legacyConfigKeys) ? previousAlias.legacyConfigKeys : []);
      if (identity) {
        mergeKeyList(target.strictAddressKeys, identity.strictAddressKeys);
        mergeKeyList(target.endpointMatchKeys, identity.endpointMatchKeys);
        mergeKeyList(target.hostMatchKeys, identity.hostMatchKeys);
        mergeKeyList(target.legacyConfigKeys, identity.legacyConfigKeys);
        delete registry.identities[source];
      }
      const aliasEntry = { canonicalInterfaceKey: resolvedTarget, kind: 'manual-merge' };
      if (ownFingerprints.length) aliasEntry.legacyConfigKeys = ownFingerprints.slice(-8);
      registry.aliases[source] = aliasEntry;
    }
    target.updatedAt = Date.now();
    registry.epoch = Number(registry.epoch || 0) + 1;
    registry.updatedAt = Date.now();
    if (await store.compareAndSet(registryKey, snapshot.version, registry)) {
      const migration = await store.migrateIdentitySpaces(token, configType, resolvedTarget, sources);
      return { canonical: resolvedTarget, merged: sources, skipped, migration, alreadyMerged: false };
    }
  }
  throw playbackHttpError(503, 'Identity registry changed concurrently; retry the merge');
}

// WebHTV adaptation: decide which registered identity groups need collapsing
// and pick the merge target the same way the dashboard does — the member with
// the most stored records, ties broken by key order for stability. Group
// members are always registered identities (canonicals), so no alias handling
// is needed here. countOf receives a bare interface key and returns the
// record count of its space.
export function planAutoMergeGroups(registry, countOf) {
  const { groups } = groupRegisteredIdentities(registry);
  const plans = [];
  for (const group of groups) {
    if (group.members.length < 2) continue;
    const sorted = group.members.slice().sort((a, b) => (countOf(b) - countOf(a)) || (a < b ? -1 : 1));
    plans.push({ target: sorted[0], sources: sorted.slice(1) });
  }
  return plans;
}

// WebHTV adaptation: plan merges by interface name, for registered and
// unregistered spaces alike. Pure function — no I/O.
//
// Every interface is configured with a primary and a backup address. The two
// addresses share no address fingerprint at all (different scheme, host and
// path), so a device that only ever reported one of them registers its own
// canonical identity, and two such devices produce two "main spaces" of the
// same interface that no fingerprint-based grouping can ever join. The only
// clue the server holds for them is the interface name the App stamps on every
// record.
//
// Rules (conservative, reversible):
//   - exact, trimmed, case-sensitive name match; empty names never merge;
//   - a space only contributes a name when that name covers a STRICT MAJORITY
//     of its rows (`nameItems * 2 > items`). A space that mixes two interfaces
//     almost evenly carries no usable signal, and a device pushes every sync
//     source it has into its own bound space, so minority rows must never name
//     a space (this is what once made a 饭太硬 space look like 摸鱼);
//   - after the pass one name owns exactly one canonical: same-name canonicals
//     collapse first (primary/backup address pair of one interface) and the
//     unregistered spaces of that name then join the winner;
//   - a name with no canonical needs 2+ unregistered spaces to elect one;
//   - the winner is the most records, then the earliest first record, then key
//     order;
//   - each key participates in at most one plan, and the submitter's own
//     hintName group is processed first so a renamed interface cannot be
//     dragged into its stale-name group.
//
// Migration copies rows and keeps the source as an alias, so a wrong guess is
// undone by dropping the alias; playback rows are never reinterpreted.
export function planNameUnifyGroups(registry, spaces, submitted, hintName) {
  const canonicalNames = new Map(); // canonicalKey -> { name, items, firstAt }
  const unregistered = new Map();   // name -> [space]
  for (const space of Array.isArray(spaces) ? spaces : []) {
    const key = String(space && space.key || '');
    const name = String(space && space.name || '').trim();
    if (!key) continue;
    const items = Number(space && space.items || 0);
    const nameItems = Number(space && space.nameItems || 0);
    const majority = Boolean(name) && nameItems * 2 > items;
    if (registry.identities[key]) {
      if (majority) {
        canonicalNames.set(key, { name, items, firstAt: Number(space.firstAt || 0) });
      }
    } else if (!registry.aliases[key] && name) {
      if (!unregistered.has(name)) unregistered.set(name, []);
      unregistered.get(name).push(space);
    }
  }
  const nameToCanonicals = new Map(); // name -> [{ key, items, firstAt }]
  for (const [key, entry] of canonicalNames) {
    if (!nameToCanonicals.has(entry.name)) nameToCanonicals.set(entry.name, []);
    nameToCanonicals.get(entry.name).push({ key, items: entry.items, firstAt: entry.firstAt });
  }

  const elect = (members) => members.slice().sort((a, b) =>
    (Number(b.items) - Number(a.items))
    || (Number(a.firstAt) - Number(b.firstAt))
    || (String(a.key) < String(b.key) ? -1 : 1))[0].key;

  const plans = [];
  const used = new Set();
  const buildPlan = (name, extraKey) => {
    const members = (unregistered.get(name) || []).filter((m) => !used.has(m.key));
    if (extraKey && !members.some((m) => m.key === extraKey)
        && !registry.identities[extraKey] && !registry.aliases[extraKey]) {
      members.push({ key: extraKey, name, items: 0, firstAt: 0 });
    }
    const canonicals = (nameToCanonicals.get(name) || []).filter((c) => !used.has(c.key));
    let target;
    let sources;
    if (canonicals.length) {
      target = elect(canonicals);
      sources = [
        ...canonicals.map((c) => c.key).filter((k) => k !== target),
        ...members.map((m) => m.key)
      ];
    } else if (members.length >= 2) {
      target = elect(members);
      sources = members.map((m) => m.key).filter((k) => k !== target);
    } else {
      return; // a lone unregistered space with no canonical has nothing to join
    }
    sources = [...new Set(sources)].filter((k) => k && k !== target && !used.has(k));
    if (!sources.length) return;
    used.add(target);
    for (const key of sources) used.add(key);
    plans.push({ target, sources });
  };

  // The current submitter's freshest name wins over its stored (possibly
  // stale) name, so evaluate the hintName group first.
  const hint = String(hintName || '').trim();
  if (submitted && hint && !registry.identities[submitted] && !registry.aliases[submitted]) {
    buildPlan(hint, submitted);
  }
  const names = new Set([...unregistered.keys(), ...nameToCanonicals.keys()]);
  for (const name of [...names].sort()) {
    if (name === hint) continue; // already evaluated
    buildPlan(name, '');
  }
  return plans;
}

function boundCanonicalKey(registry, key) {
  if (registry.identities[key]) return key;
  return registry.aliases[key]?.canonicalInterfaceKey || '';
}

// Pull the interface name from the first usable raw webhook event. Only the
// explicit configName fields qualify — raw.name/raw.title are vodName aliases
// and must never be mistaken for the interface name.
function firstEventConfigName(rawEvents) {
  for (const raw of Array.isArray(rawEvents) ? rawEvents : []) {
    if (!raw || typeof raw !== 'object') continue;
    const name = cleanString(raw.configName || raw.config_name, 2048);
    if (name) return name;
  }
  return '';
}

// WebHTV adaptation: the official resolver with lazy auto-merge. When
// hooks.autoMergeGroups is provided it runs before the official resolution
// (collapsing registered same-interface groups), and a confirm_required
// answer — which the official App can never act on because it never sends
// confirm:true — is re-submitted once with confirm to run the official merge
// path, but only for strong address matches (exact URL / endpoint / legacy
// URL hash). Host-only confirm_required results are returned untouched:
// a shared proxy domain must never silently merge unrelated interfaces.
// The retry uses a derived requestId so it cannot hit the resolver's
// idempotency cache, which would otherwise return the confirm_required body
// again.
export async function resolveWithAutoMerge(store, token, input, hooks = {}) {
  const autoMerge = typeof hooks.autoMergeGroups === 'function' ? hooks.autoMergeGroups : null;
  if (autoMerge) {
    try {
      await autoMerge();
    } catch {
      // Best effort: a failed auto-merge must never block the App's sync. The
      // next resolve retries the collapse.
    }
  }
  const result = await resolveIdentity(store, token, input);
  if (autoMerge && result.status === 200 && result.body && result.body.action === 'confirm_required') {
    // Auto-answer only strong address matches (exact URL / endpoint / legacy
    // URL hash). A host-only match can be shared by unrelated interfaces that
    // happen to sit behind the same proxy domain, so it stays confirm_required:
    // the App keeps its own identity and the dashboard merge stays manual.
    const matchedBy = result.body.matchedBy;
    if (matchedBy !== 'strictAddressKey' && matchedBy !== 'endpointMatchKey' && matchedBy !== 'legacyConfigKey') {
      return result;
    }
    const retryInput = { ...input, confirm: true };
    if (input.requestId) retryInput.requestId = `${input.requestId}-auto`.slice(0, 160);
    try {
      return await resolveIdentity(store, token, retryInput);
    } catch {
      return result;
    }
  }
  return result;
}

function mergeKeyList(targetList, sourceList) {
  for (const item of Array.isArray(sourceList) ? sourceList : []) {
    if (!targetList.includes(item) && targetList.length < 32) targetList.push(item);
  }
}

function playbackHttpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}
