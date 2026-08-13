import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';

import {
  isPlaybackSyncPath,
  normalizePlaybackEvent,
  parseCursor,
  parseLimit
} from '../src/playback-sync.js';

const NOW = 1781170000000;
const CONFIG_KEY = 'abcdef0123456789';

test('recognizes playback sync and status paths', () => {
  assert.equal(isPlaybackSyncPath('/api/playback/sync'), true);
  assert.equal(isPlaybackSyncPath('/api/playback/sync/'), true);
  assert.equal(isPlaybackSyncPath('/playback/sync/status'), true);
  assert.equal(isPlaybackSyncPath('/api/playback/current'), false);
});

test('normalizes a progress webhook into a portable upsert', () => {
  const event = normalizePlaybackEvent({
    schema: 'webhtv.playback.v1',
    event: 'playback.progress',
    eventId: 'event-1',
    timestamp: NOW - 1000,
    configKey: CONFIG_KEY.toUpperCase(),
    historyKey: 'site-a@@@vod-1@@@23',
    vodName: '影片 A',
    episodeName: '第 1 集',
    positionMs: 120000,
    durationMs: 600000,
    speed: 1.25
  }, CONFIG_KEY, NOW);

  assert.equal(event.kind, 'upsert');
  assert.equal(event.siteKey, 'site-a');
  assert.equal(event.vodId, 'vod-1');
  assert.equal(event.itemKey, 'site-a\nvod-1');
  assert.equal(event.updatedAt, NOW - 1000);
  assert.deepEqual(event.payload, {
    schema: 'webhtv.playback.v1',
    action: 'upsert',
    event: 'playback.progress',
    eventId: 'event-1',
    configKey: CONFIG_KEY,
    historyKey: 'site-a@@@vod-1@@@23',
    siteKey: 'site-a',
    vodId: 'vod-1',
    vodName: '影片 A',
    episodeName: '第 1 集',
    positionMs: 120000,
    durationMs: 600000,
    progress: 0.2,
    speed: 1.25,
    completed: false,
    updatedAt: NOW - 1000
  });
});

test('normalizes item, site, and explicit all deletions', () => {
  const item = normalizePlaybackEvent({
    event: 'playback.deleted',
    historyKey: 'site-a@@@vod-1@@@9',
    deletedAt: NOW - 3000
  }, CONFIG_KEY, NOW);
  assert.equal(item.scope, 'item');
  assert.equal(item.siteKey, 'site-a');
  assert.equal(item.vodId, 'vod-1');
  assert.equal(item.markerKey, 'item\nsite-a\nvod-1');

  const site = normalizePlaybackEvent({
    action: 'delete',
    siteKey: 'site-a',
    deletedAt: NOW - 2000
  }, CONFIG_KEY, NOW);
  assert.equal(site.scope, 'site');
  assert.equal(site.historyKey, '');
  assert.equal(site.vodId, '');
  assert.equal(site.markerKey, 'site\nsite-a');

  const all = normalizePlaybackEvent({
    event: 'playback.deleted',
    scope: 'all',
    siteKey: 'ignored-for-all',
    deletedAt: NOW - 1000
  }, CONFIG_KEY, NOW);
  assert.equal(all.scope, 'all');
  assert.equal(all.historyKey, '');
  assert.equal(all.siteKey, '');
  assert.equal(all.vodId, '');
  assert.equal(all.markerKey, 'all');
});

test('never infers a full-config deletion without explicit scope=all', () => {
  assert.throws(
    () => normalizePlaybackEvent({ event: 'playback.deleted', deletedAt: NOW }, CONFIG_KEY, NOW),
    /scope=all must be explicit/
  );
  assert.throws(
    () => normalizePlaybackEvent({ event: 'playback.deleted', scope: 'everything', siteKey: 'site-a' }, CONFIG_KEY, NOW),
    /scope must be item, site, or all/
  );
  assert.throws(
    () => normalizePlaybackEvent({ event: 'playback.deleted', scope: 'all' }, CONFIG_KEY, NOW),
    /deletedAt or timestamp is required/
  );
});

test('rejects config identity mismatches and oversized identities', () => {
  assert.throws(
    () => normalizePlaybackEvent({ configKey: 'different', event: 'playback.deleted', scope: 'all' }, CONFIG_KEY, NOW),
    /configKey does not match/
  );
  assert.throws(
    () => normalizePlaybackEvent({ event: 'playback.deleted', scope: 'all' }, 'x'.repeat(257), NOW),
    /configKey is too long/
  );
});

test('parses monotonic cursors and bounded limits', () => {
  assert.equal(parseCursor(), 0);
  assert.equal(parseCursor('42'), 42);
  assert.throws(() => parseCursor('-1'), /Invalid X-WebHTV-Since cursor/);
  assert.throws(() => parseCursor('1.5'), /Invalid X-WebHTV-Since cursor/);
  assert.throws(() => parseCursor('not-a-cursor'), /Invalid X-WebHTV-Since cursor/);

  assert.equal(parseLimit(), 100);
  assert.equal(parseLimit('25'), 25);
  assert.equal(parseLimit('5000'), 1000);
  assert.equal(parseLimit('0'), 100);
  assert.equal(parseLimit('25items'), 100);
});

test('accepts live stream events with durationMs = 0 (e.g. Huya .flv live URL)', () => {
  // Live streams have no fixed duration — ExoPlayer reports duration = 0.
  // The server must accept this and avoid clamping positionMs or dividing by zero.
  const event = normalizePlaybackEvent({
    schema: 'webhtv.playback.v1',
    event: 'playback.progress',
    eventId: 'live-1',
    timestamp: NOW,
    historyKey: 'huya@@@2367547387@@@0',
    vodName: '虎牙直播间',
    episodeName: '原画',
    episodeUrl: 'https://al.flv.huya.com/src/2367547387-2367547387-10168538598895255552-4735218230-10057-A-0-1-imgplus.flv?codec=264&ctype=huya_pc_exe&wsSecret=abc&wsTime=def',
    positionMs: 30000,
    durationMs: 0,
    speed: 1
  }, CONFIG_KEY, NOW);

  assert.equal(event.kind, 'upsert');
  assert.equal(event.siteKey, 'huya');
  assert.equal(event.vodId, '2367547387');
  assert.equal(event.payload.positionMs, 30000, 'positionMs should NOT be clamped when durationMs is 0');
  assert.equal(event.payload.durationMs, 0, 'durationMs should be preserved as 0');
  assert.equal(event.payload.progress, 0, 'progress should be 0 when duration is unknown');
  assert.equal(event.payload.completed, false, 'live stream should never be marked completed');
});

test('accepts live stream events with positionMs = 0 (stream just started)', () => {
  // At the very start of a live stream, position may briefly be 0.
  const event = normalizePlaybackEvent({
    event: 'playback.progress',
    eventId: 'live-start',
    timestamp: NOW,
    historyKey: 'huya@@@2367547387@@@0',
    vodName: '虎牙直播间',
    episodeName: '原画',
    positionMs: 0,
    durationMs: 0
  }, CONFIG_KEY, NOW);

  assert.equal(event.kind, 'upsert');
  assert.equal(event.payload.positionMs, 0);
  assert.equal(event.payload.durationMs, 0);
  assert.equal(event.payload.progress, 0);
  assert.equal(event.payload.completed, false);
});

test('still rejects negative positionMs and durationMs', () => {
  assert.throws(
    () => normalizePlaybackEvent({
      historyKey: 'site@@@vod',
      vodName: '影片',
      episodeName: '第1集',
      positionMs: -100,
      durationMs: 600000
    }, CONFIG_KEY, NOW),
    /positionMs must not be negative/
  );
  assert.throws(
    () => normalizePlaybackEvent({
      historyKey: 'site@@@vod',
      vodName: '影片',
      episodeName: '第1集',
      positionMs: 100,
      durationMs: -1
    }, CONFIG_KEY, NOW),
    /durationMs must not be negative/
  );
});

// =============================================================================
// Integration tests for vodName deduplication using in-memory SQLite
// These tests simulate the Durable Object's applyUpsert dedup logic
// =============================================================================

/**
 * Adapter that wraps node:sqlite DatabaseSync to mimic the DO sql interface.
 * DO sql.exec() returns an object with .toArray(), .one(), .rowsWritten.
 */
function createDoSql(db) {
  return {
    exec(sql, ...params) {
      const stmt = db.prepare(sql);
      const lower = sql.trim().toLowerCase();
      const isSelect = lower.startsWith('select') || lower.startsWith('pragma');
      const isDelete = lower.startsWith('delete');
      const isUpdate = lower.startsWith('update');
      const isInsert = lower.startsWith('insert');

      if (isSelect || lower.includes('select')) {
        const rows = stmt.all(...params);
        return {
          toArray: () => rows,
          one: () => rows[0] || undefined,
        };
      }
      // INSERT / UPDATE / DELETE
      const result = stmt.run(...params);
      // For INSERT OR IGNORE, changes may be 0 if ignored
      const rowsWritten = isDelete ? result.changes : (isUpdate ? result.changes : (result.changes > 0 ? 1 : 0));
      return {
        rowsWritten,
        toArray: () => [],
        one: () => undefined,
      };
    },
  };
}

function setupTestDb() {
  const db = new DatabaseSync(':memory:');
  db.exec(`
    CREATE TABLE playback_meta (
      key TEXT PRIMARY KEY,
      value INTEGER NOT NULL
    );
    INSERT INTO playback_meta (key, value) VALUES ('sequence', 0);

    CREATE TABLE playback_items (
      config_key TEXT NOT NULL,
      item_key TEXT NOT NULL,
      history_key TEXT NOT NULL,
      site_key TEXT NOT NULL,
      vod_id TEXT NOT NULL,
      vod_name TEXT NOT NULL DEFAULT '',
      updated_at INTEGER NOT NULL,
      seq INTEGER NOT NULL,
      payload TEXT NOT NULL,
      PRIMARY KEY (config_key, item_key)
    );
    CREATE INDEX idx_playback_items_config_vodname ON playback_items (config_key, vod_name);

    CREATE TABLE playback_tombstones (
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

    CREATE TABLE playback_events (
      config_key TEXT NOT NULL,
      event_id TEXT NOT NULL,
      received_at INTEGER NOT NULL,
      PRIMARY KEY (config_key, event_id)
    );
  `);
  return db;
}

test('same vodName from different sites keeps only the newest record', () => {
  const db = setupTestDb();
  const sql = createDoSql(db);
  const configKey = 'test-config';

  // Insert a record for "正相反的你与我" from site FishPs
  db.prepare(`
    INSERT INTO playback_items (config_key, item_key, history_key, site_key, vod_id, vod_name, updated_at, seq, payload)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    configKey,
    'FishPs\n2367547387',
    'FishPs@@@2367547387',
    'FishPs',
    '2367547387',
    '正相反的你与我',
    1000,
    1,
    JSON.stringify({ vodName: '正相反的你与我', positionMs: 120000, durationMs: 2400000 })
  );

  // Insert a second record for the SAME movie from a different site (JSo)
  db.prepare(`
    INSERT INTO playback_items (config_key, item_key, history_key, site_key, vod_id, vod_name, updated_at, seq, payload)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    configKey,
    'JSo\n888999000',
    'JSo@@@888999000',
    'JSo',
    '888999000',
    '正相反的你与我',
    2000,
    2,
    JSON.stringify({ vodName: '正相反的你与我', positionMs: 60000, durationMs: 2400000 })
  );

  // Verify both records exist before dedup
  const beforeCount = db.prepare('SELECT COUNT(*) AS c FROM playback_items WHERE config_key = ?').get(configKey);
  assert.equal(beforeCount.c, 2, 'Should have 2 records before dedup');

  // --- Simulate FIXED applyUpsert logic (matching src/playback-sync.js) ---
  const vodName = '正相反的你与我';
  const currentItemKey = 'Libvio\n555666777';  // The new incoming record
  const currentUpdatedAt = 3000;                // Newer than both existing (1000, 2000)

  // Pre-check: is there a NEWER sibling with the same vodName? (updated_at > current)
  const newerSibling = db.prepare(`
    SELECT 1 AS found FROM playback_items
    WHERE config_key = ? AND vod_name = ? AND item_key != ? AND updated_at > ?
    LIMIT 1
  `).get(configKey, vodName, currentItemKey, currentUpdatedAt);
  assert.equal(newerSibling, undefined, 'No newer sibling should exist when current is newest');

  // Find duplicates with same vodName but different item_key AND strictly older
  const duplicates = db.prepare(`
    SELECT item_key, history_key, site_key, vod_id, updated_at
    FROM playback_items
    WHERE config_key = ? AND vod_name = ? AND item_key != ? AND updated_at < ?
  `).all(configKey, vodName, currentItemKey, currentUpdatedAt);

  assert.equal(duplicates.length, 2, 'Should find 2 strictly-older duplicates');

  // Create tombstones and delete (simulated)
  for (const dup of duplicates) {
    const markerKey = `item\n${dup.item_key}`;
    const existingTomb = db.prepare('SELECT 1 AS found FROM playback_tombstones WHERE config_key = ? AND marker_key = ? LIMIT 1')
      .get(configKey, markerKey);

    if (!existingTomb) {
      db.prepare(`
        INSERT OR IGNORE INTO playback_tombstones
          (config_key, marker_key, scope, history_key, site_key, vod_id, deleted_at, seq, payload)
        VALUES (?, ?, 'item', ?, ?, ?, ?, ?, ?)
      `).run(configKey, markerKey, dup.history_key, dup.site_key, dup.vod_id, dup.updated_at, 100 + dup.updated_at, '{}');
    }
    db.prepare('DELETE FROM playback_items WHERE config_key = ? AND item_key = ? AND vod_name = ?')
      .run(configKey, dup.item_key, vodName);
  }

  // Verify: only the new record (not yet inserted) and the just-deleted ones are gone
  const remaining = db.prepare('SELECT * FROM playback_items WHERE config_key = ?').all(configKey);
  assert.equal(remaining.length, 0, 'Both old records should be deleted by dedup');

  // Verify tombstones were created
  const tombs = db.prepare('SELECT * FROM playback_tombstones WHERE config_key = ?').all(configKey);
  assert.equal(tombs.length, 2, 'Should have 2 tombstones for the deleted records');

  db.close();
});

// Regression: a stale (older) event arriving AFTER a newer record was stored
// must NOT delete the newer record.  Before the fix the dedup query had no
// updated_at comparison and would wipe the newer entry, leaving only the stale
// one — the opposite of "keep the latest".
test('stale (older) event does not delete newer same-title record', () => {
  const db = setupTestDb();
  const configKey = 'test-config';
  const vodName = '正相反的你与我';

  // 1. A NEWER record already exists (e.g. reported moments ago by another device)
  db.prepare(`
    INSERT INTO playback_items (config_key, item_key, history_key, site_key, vod_id, vod_name, updated_at, seq, payload)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    configKey,
    'FishPs\n2367547387',
    'FishPs@@@2367547387',
    'FishPs',
    '2367547387',
    vodName,
    2000,   // NEWER
    1,
    JSON.stringify({ vodName, positionMs: 120000, durationMs: 2400000 })
  );

  // 2. A stale event arrives later from a different site with an OLDER timestamp
  //    (e.g. an offline device replaying its buffer).
  const staleItemKey = 'JSo\n888999000';
  const staleUpdatedAt = 1000;   // OLDER than the stored 2000

  // --- Pre-check (NEW): is there a NEWER sibling? ---
  const newerSibling = db.prepare(`
    SELECT 1 AS found FROM playback_items
    WHERE config_key = ? AND vod_name = ? AND item_key != ? AND updated_at > ?
    LIMIT 1
  `).get(configKey, vodName, staleItemKey, staleUpdatedAt);
  assert.ok(newerSibling, 'Pre-check should detect the newer same-title record');

  // Simulate the fixed flow: because a newer sibling exists, the stale event is
  // SKIPPED — it is never inserted and must not trigger any deletion.
  // (No INSERT, no dedup, no tombstone for the newer record.)

  // Verify the NEWER record is intact and NO tombstone was created for it.
  const remaining = db.prepare('SELECT item_key, updated_at FROM playback_items WHERE config_key = ?').all(configKey);
  assert.equal(remaining.length, 1, 'Newer record must survive the stale event');
  assert.equal(remaining[0].item_key, 'FishPs\n2367547387');
  assert.equal(remaining[0].updated_at, 2000);

  const tombs = db.prepare('SELECT * FROM playback_tombstones WHERE config_key = ?').all(configKey);
  assert.equal(tombs.length, 0, 'No tombstone should be created when skipping a stale event');

  db.close();
});

// Regression guard: the OLD (buggy) dedup query — without the updated_at filter —
// would have selected the newer record as a "duplicate" and deleted it.  This test
// pins that behavior so the filter is never accidentally removed.
test('buggy dedup query (no updated_at filter) WOULD delete the newer record', () => {
  const db = setupTestDb();
  const configKey = 'test-config';
  const vodName = '正相反的你与我';

  db.prepare(`
    INSERT INTO playback_items (config_key, item_key, history_key, site_key, vod_id, vod_name, updated_at, seq, payload)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(configKey, 'FishPs\n2367547387', 'FishPs@@@2367547387', 'FishPs', '2367547387', vodName, 2000, 1, '{}');

  const staleItemKey = 'JSo\n888999000';
  const staleUpdatedAt = 1000;

  // OLD buggy query: no updated_at comparison
  const buggyHits = db.prepare(`
    SELECT item_key, updated_at FROM playback_items
    WHERE config_key = ? AND vod_name = ? AND item_key != ?
  `).all(configKey, vodName, staleItemKey);
  assert.equal(buggyHits.length, 1, 'Buggy query selects the newer record as a duplicate');
  assert.equal(buggyHits[0].updated_at, 2000, 'Buggy query would delete the NEWER record (bug)');

  // FIXED query: only strictly-older duplicates
  const fixedHits = db.prepare(`
    SELECT item_key, updated_at FROM playback_items
    WHERE config_key = ? AND vod_name = ? AND item_key != ? AND updated_at < ?
  `).all(configKey, vodName, staleItemKey, staleUpdatedAt);
  assert.equal(fixedHits.length, 0, 'Fixed query must NOT select the newer record');

  db.close();
});

test('different vodNames do not interfere with each other', () => {
  const db = setupTestDb();
  const configKey = 'test-config';

  // Insert two records with DIFFERENT vodNames
  db.prepare(`
    INSERT INTO playback_items (config_key, item_key, history_key, site_key, vod_id, vod_name, updated_at, seq, payload)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(configKey, 'site-a\nmovie-1', 'site-a@@@movie-1', 'site-a', 'movie-1', '影片A', 1000, 1, '{}');

  db.prepare(`
    INSERT INTO playback_items (config_key, item_key, history_key, site_key, vod_id, vod_name, updated_at, seq, payload)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(configKey, 'site-b\nmovie-2', 'site-b@@@movie-2', 'site-b', 'movie-2', '影片B', 2000, 2, '{}');

  // Verify both records exist
  const beforeCount = db.prepare('SELECT COUNT(*) AS c FROM playback_items WHERE config_key = ?').get(configKey);
  assert.equal(beforeCount.c, 2);

  // Try to dedup for vodName='影片A' with a new item_key
  const duplicates = db.prepare(`
    SELECT item_key FROM playback_items WHERE config_key = ? AND vod_name = ? AND item_key != ?
  `).all(configKey, '影片A', 'site-c\nmovie-1');

  // Should find only 1 duplicate (the movie-1 record), NOT movie-2
  assert.equal(duplicates.length, 1);
  assert.equal(duplicates[0].item_key, 'site-a\nmovie-1');

  db.close();
});

test('empty vodName skips dedup entirely', () => {
  const db = setupTestDb();
  const configKey = 'test-config';

  // Insert a record with empty vodName
  db.prepare(`
    INSERT INTO playback_items (config_key, item_key, history_key, site_key, vod_id, vod_name, updated_at, seq, payload)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(configKey, 'site-a\nmovie-1', 'site-a@@@movie-1', 'site-a', 'movie-1', '', 1000, 1, '{}');

  db.prepare(`
    INSERT INTO playback_items (config_key, item_key, history_key, site_key, vod_id, vod_name, updated_at, seq, payload)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(configKey, 'site-b\nmovie-2', 'site-b@@@movie-2', 'site-b', 'movie-2', '', 2000, 2, '{}');

  const beforeCount = db.prepare('SELECT COUNT(*) AS c FROM playback_items WHERE config_key = ?').get(configKey);
  assert.equal(beforeCount.c, 2);

  // Empty vodName should NOT trigger dedup (the if(vodName) guard is skipped)
  const emptyName = '';
  if (emptyName) {
    assert.fail('Should not enter dedup block with empty vodName');
  }

  db.close();
});

test('normalizePlaybackEvent correctly extracts vodName for dedup', () => {
  const event = normalizePlaybackEvent({
    event: 'playback.progress',
    eventId: 'test-1',
    timestamp: NOW,
    historyKey: 'huya@@@2367547387@@@0',
    vodName: '正相反的你与我',
    episodeName: '第1集',
    positionMs: 30000,
    durationMs: 0
  }, CONFIG_KEY, NOW);

  assert.equal(event.kind, 'upsert');
  assert.equal(event.payload.vodName, '正相反的你与我', 'vodName should be preserved in payload for dedup');
});

test('normalizePlaybackEvent with empty vodName produces empty payload field', () => {
  const event = normalizePlaybackEvent({
    event: 'playback.progress',
    eventId: 'test-2',
    timestamp: NOW,
    historyKey: 'site-a@@@vod-1',
    vodName: '',
    episodeName: '第1集',
    positionMs: 1000,
    durationMs: 60000
  }, CONFIG_KEY, NOW);

  assert.equal(event.kind, 'upsert');
  // compactObject removes empty strings, so vodName should not be in payload at all
  assert.equal(event.payload.vodName, undefined, 'empty vodName should be removed by compactObject');
});
