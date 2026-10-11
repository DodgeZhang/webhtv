import test from 'node:test';
import assert from 'node:assert/strict';

import {
  isPlaybackSyncPath,
  normalizePlaybackEvent,
  parseCursor,
  parseLimit,
  groupRegisteredIdentities,
  mergeIdentityRegistry,
  planAutoMergeGroups,
  planNameMerge,
  resolveWithAutoMerge
} from '../src/playback-sync.js';
import { IDENTITY_SCHEMA, identityRegistryKey, normalizeIdentityRegistry } from '../../playback-identity-fixtures/identity.js';

const NOW = Date.now() + 1000;
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
    /scope must be item, site, season, or all/
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

test('routes the dashboard identity spaces and merge endpoints', () => {
  assert.equal(isPlaybackSyncPath('/api/playback/sync/identity/spaces'), true);
  assert.equal(isPlaybackSyncPath('/playback/sync/identity/merge'), true);
  assert.equal(isPlaybackSyncPath('/api/playback/identity/spaces'), false);
  assert.equal(isPlaybackSyncPath('/api/playback/sync/identity/other'), false);
});

test('groups registered identities only by strong address clues', () => {
  const registry = normalizeIdentityRegistry({
    identities: {
      'key-a': { strictAddressKeys: ['strict-shared'], endpointMatchKeys: [], hostMatchKeys: ['host-1'], legacyConfigKeys: [] },
      'key-b': { strictAddressKeys: [], endpointMatchKeys: ['strict-shared'], hostMatchKeys: ['host-1'], legacyConfigKeys: [] },
      'key-c': { strictAddressKeys: [], endpointMatchKeys: [], hostMatchKeys: ['host-1'], legacyConfigKeys: [] }
    },
    aliases: {}
  });
  const { groups, groupIdOf } = groupRegisteredIdentities(registry);
  const multi = groups.find((group) => group.members.length > 1);
  assert.ok(multi, 'expected one multi-member group');
  assert.deepEqual(multi.members, ['key-a', 'key-b']);
  assert.equal(groupIdOf.get('key-a'), groupIdOf.get('key-b'));
  assert.ok(!groupIdOf.has('key-c'), 'host-only clues must not fuse identities');
});

function createFakeStore(initialRegistries = {}) {
  const state = new Map();
  for (const [key, value] of Object.entries(initialRegistries)) {
    state.set(key, { version: 1, state: JSON.parse(JSON.stringify(value)) });
  }
  const migrations = [];
  return {
    migrations,
    async load(key) {
      const entry = state.get(key);
      return entry
        ? { version: entry.version, state: JSON.parse(JSON.stringify(entry.state)) }
        : { version: null, state: null };
    },
    async compareAndSet(key, version, nextState) {
      const entry = state.get(key);
      const currentVersion = entry ? entry.version : null;
      if (currentVersion !== version && !(version == null && currentVersion == null)) return false;
      const nextVersion = currentVersion == null ? 1 : currentVersion + 1;
      state.set(key, { version: nextVersion, state: JSON.parse(JSON.stringify(nextState)) });
      return true;
    },
    async migrateIdentitySpaces(token, configType, canonical, sources) {
      migrations.push({ token, configType, canonical, sources: [...sources] });
      return { migrated: sources.length > 0, pending: false, resetSince: true };
    }
  };
}

test('mergeIdentityRegistry demotes a source identity to an alias and unions clues', async () => {
  const token = 'merge-token';
  const registryKey = await identityRegistryKey(token, 'vod');
  const store = createFakeStore({
    [registryKey]: {
      schema: 1, epoch: 3, updatedAt: 1, requests: {},
      identities: {
        'target-key': { canonicalInterfaceKey: 'target-key', strictAddressKeys: ['strict-t'], endpointMatchKeys: [], hostMatchKeys: [], legacyConfigKeys: [], createdAt: 1, updatedAt: 1 },
        'source-key': { canonicalInterfaceKey: 'source-key', strictAddressKeys: ['strict-s'], endpointMatchKeys: ['endpoint-s'], hostMatchKeys: ['host-s'], legacyConfigKeys: ['legacy-s'], createdAt: 2, updatedAt: 2 }
      },
      aliases: {}
    }
  });

  const result = await mergeIdentityRegistry(store, token, 'vod', 'target-key', ['source-key']);
  assert.equal(result.canonical, 'target-key');
  assert.deepEqual(result.merged, ['source-key']);
  assert.deepEqual(result.skipped, []);
  assert.deepEqual(store.migrations, [{ token, configType: 'vod', canonical: 'target-key', sources: ['source-key'] }]);

  const registry = normalizeIdentityRegistry((await store.load(registryKey)).state);
  assert.ok(registry.identities['target-key'], 'target identity survives');
  assert.ok(!registry.identities['source-key'], 'source identity is demoted');
  assert.equal(registry.aliases['source-key'].canonicalInterfaceKey, 'target-key');
  for (const clue of ['strict-s', 'endpoint-s', 'host-s', 'legacy-s']) {
    const target = registry.identities['target-key'];
    const merged = [...target.strictAddressKeys, ...target.endpointMatchKeys, ...target.hostMatchKeys, ...target.legacyConfigKeys];
    assert.ok(merged.includes(clue), `clue ${clue} must be unioned into the target`);
  }
});

test('mergeIdentityRegistry creates an unregistered target and keeps merges idempotent', async () => {
  const token = 'merge-token-2';
  const registryKey = await identityRegistryKey(token, 'vod');
  const store = createFakeStore();

  const first = await mergeIdentityRegistry(store, token, 'vod', 'target-key', ['source-key', 'source-key']);
  assert.equal(first.canonical, 'target-key');
  assert.deepEqual(first.merged, ['source-key']);
  assert.deepEqual(store.migrations, [{ token, configType: 'vod', canonical: 'target-key', sources: ['source-key'] }]);

  // Re-running the same merge is a no-op because the source is now an alias
  // of the target.
  const second = await mergeIdentityRegistry(store, token, 'vod', 'target-key', ['source-key']);
  assert.equal(second.alreadyMerged, true);
  assert.deepEqual(second.merged, []);
  assert.equal(store.migrations.length, 1);

  // Submitting the target through its alias resolves to the same canonical.
  const third = await mergeIdentityRegistry(store, token, 'vod', 'source-key', ['another-key']);
  assert.equal(third.canonical, 'target-key');
  assert.deepEqual(third.merged, ['another-key']);

  const registry = normalizeIdentityRegistry((await store.load(registryKey)).state);
  assert.deepEqual(Object.keys(registry.identities), ['target-key']);
  assert.equal(registry.aliases['source-key'].canonicalInterfaceKey, 'target-key');
  assert.equal(registry.aliases['another-key'].canonicalInterfaceKey, 'target-key');
});

test('mergeIdentityRegistry rejects invalid keys and empty sources', async () => {
  const store = createFakeStore();
  await assert.rejects(
    () => mergeIdentityRegistry(store, 'token', 'vod', 'BAD KEY WITH SPACE', ['source-key']),
    /targetKey is invalid/
  );
  await assert.rejects(
    () => mergeIdentityRegistry(store, 'token', 'vod', 'target-key', ['   ']),
    /sourceKeys is required/
  );
});

test('planAutoMergeGroups picks the member with the most records as the target', () => {
  const registry = normalizeIdentityRegistry({
    identities: {
      'key-a': { strictAddressKeys: ['strict-shared'], endpointMatchKeys: [], hostMatchKeys: [], legacyConfigKeys: [] },
      'key-b': { strictAddressKeys: [], endpointMatchKeys: ['strict-shared'], hostMatchKeys: [], legacyConfigKeys: [] },
      'solo-key': { strictAddressKeys: ['strict-solo'], endpointMatchKeys: [], hostMatchKeys: [], legacyConfigKeys: [] }
    },
    aliases: {}
  });
  const counts = new Map([['key-a', 3], ['key-b', 9], ['solo-key', 5]]);
  const plans = planAutoMergeGroups(registry, (key) => counts.get(key) || 0);
  assert.deepEqual(plans, [{ target: 'key-b', sources: ['key-a'] }]);
});

test('planNameMerge keeps only the newest row per exact vodName', () => {
  const groups = planNameMerge([
    { itemKey: 'woog\n1', vodName: '战争鸽子', updatedAt: 100, seq: 1 },
    { itemKey: 'hunhan\n9', vodName: '战争鸽子', updatedAt: 300, seq: 2 },
    { itemKey: 'x\n3', vodName: '战争鸽子', updatedAt: 300, seq: 7 },
    { itemKey: 'woog\n2', vodName: '白夜追凶', updatedAt: 500, seq: 3 },
    { itemKey: 'y\n4', vodName: '', updatedAt: 900, seq: 8 },
    { itemKey: 'y\n5', vodName: '   ', updatedAt: 900, seq: 9 }
  ]);
  // Only the duplicated name forms a group; uniques and empty names are ignored.
  assert.equal(groups.length, 1);
  assert.equal(groups[0].name, '战争鸽子');
  // Equal updatedAt is broken by the newer seq, so the newest write survives.
  assert.equal(groups[0].keep.itemKey, 'x\n3');
  assert.deepEqual(groups[0].drop.map((row) => row.itemKey), ['hunhan\n9', 'woog\n1']);
});

test('planNameMerge leaves a bucket alone when TMDB identities disagree', () => {
  const groups = planNameMerge([
    // Same series name, two different seasons: the shared name is a coincidence,
    // so the whole bucket is skipped instead of deleting the wrong season.
    { itemKey: 'a\n1', vodName: '物怪', updatedAt: 100, seq: 1, tmdbId: 9, seasonNumber: 3 },
    { itemKey: 'b\n2', vodName: '物怪', updatedAt: 200, seq: 2, tmdbId: 9, seasonNumber: 1 },
    // An identical identity on two sources stays mergeable, and a row that never
    // matched TMDB neither forms nor contradicts one, so it does not block it.
    { itemKey: 'c\n3', vodName: '战争鸽子', updatedAt: 300, seq: 3, tmdbId: 7, seasonNumber: -1 },
    { itemKey: 'd\n4', vodName: '战争鸽子', updatedAt: 400, seq: 4, tmdbId: 7, seasonNumber: -1 },
    { itemKey: 'e\n5', vodName: '战争鸽子', updatedAt: 500, seq: 5 }
  ]);
  assert.equal(groups.length, 2);
  const guarded = groups.find((group) => group.name === '物怪');
  assert.deepEqual(guarded.identities, ['9#3', '9#1']);
  assert.equal(guarded.keep, null);
  assert.deepEqual(guarded.drop, []);
  const merged = groups.find((group) => group.name === '战争鸽子');
  assert.deepEqual(merged.identities, []);
  assert.equal(merged.keep.itemKey, 'e\n5');
  assert.deepEqual(merged.drop.map((row) => row.itemKey), ['d\n4', 'c\n3']);
});

test('resolveWithAutoMerge re-submits confirm_required with confirm automatically', async () => {
  const token = 'auto-merge-token';
  const registryKey = await identityRegistryKey(token, 'vod');
  const store = createFakeStore({
    [registryKey]: {
      schema: 1, epoch: 1, updatedAt: 1, requests: {},
      identities: {
        'target-key': { canonicalInterfaceKey: 'target-key', strictAddressKeys: ['strict-shared'], endpointMatchKeys: [], hostMatchKeys: [], legacyConfigKeys: [], createdAt: 1, updatedAt: 1 }
      },
      aliases: {}
    }
  });
  const input = {
    schema: IDENTITY_SCHEMA,
    operation: 'resolve',
    configType: 'vod',
    interfaceKey: 'source-key',
    sourceDataState: 'has_data',
    requestId: 'req-1',
    strictAddressKeys: ['strict-shared'],
    endpointMatchKeys: [],
    hostMatchKeys: [],
    legacyConfigKeys: []
  };

  const result = await resolveWithAutoMerge(store, token, input, { autoMergeGroups: async () => {} });
  assert.equal(result.status, 200);
  assert.equal(result.body.action, 'merge');
  assert.equal(result.body.canonicalInterfaceKey, 'target-key');

  const registry = normalizeIdentityRegistry((await store.load(registryKey)).state);
  assert.ok(!registry.identities['source-key'], 'source identity must not be registered');
  assert.equal(registry.aliases['source-key'].canonicalInterfaceKey, 'target-key');
  assert.ok(registry.requests['req-1-auto'], 'the confirm retry must use its own idempotency cache entry');
  assert.equal(registry.requests['req-1'].body.action, 'confirm_required');
});

test('resolveWithAutoMerge keeps confirm_required when auto-merge is disabled', async () => {
  const token = 'auto-merge-off-token';
  const registryKey = await identityRegistryKey(token, 'vod');
  const store = createFakeStore({
    [registryKey]: {
      schema: 1, epoch: 1, updatedAt: 1, requests: {},
      identities: {
        'target-key': { canonicalInterfaceKey: 'target-key', strictAddressKeys: ['strict-shared'], endpointMatchKeys: [], hostMatchKeys: [], legacyConfigKeys: [], createdAt: 1, updatedAt: 1 }
      },
      aliases: {}
    }
  });
  const input = {
    schema: IDENTITY_SCHEMA,
    operation: 'resolve',
    configType: 'vod',
    interfaceKey: 'source-key',
    sourceDataState: 'has_data',
    requestId: 'req-1',
    strictAddressKeys: ['strict-shared'],
    endpointMatchKeys: [],
    hostMatchKeys: [],
    legacyConfigKeys: []
  };

  const result = await resolveWithAutoMerge(store, token, input);
  assert.equal(result.status, 200);
  assert.equal(result.body.action, 'confirm_required');

  const registry = normalizeIdentityRegistry((await store.load(registryKey)).state);
  assert.ok(!registry.aliases['source-key'], 'no merge must happen without the hook');
  assert.ok(!registry.requests['req-1-auto'], 'no confirm retry must be recorded');
});
