import test from 'node:test';
import assert from 'node:assert/strict';

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
