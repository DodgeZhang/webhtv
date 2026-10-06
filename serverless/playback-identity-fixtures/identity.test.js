import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveIdentity, resolveConfigKey, parseIdentityRequest, canonicalizeAddress, addressMaterial, sha256 } from './identity.js';

function createMemoryPlaybackStore() {
  const entries = new Map();
  return {
    async load(key) { const entry = entries.get(key); return entry ? { version: entry.version, state: JSON.parse(JSON.stringify(entry.state)) } : { version: null, state: null }; },
    async compareAndSet(key, version, state) { const current = entries.get(key); if ((current?.version ?? null) !== version) return false; entries.set(key, { version: (current?.version || 0) + 1, state: JSON.parse(JSON.stringify(state)) }); return true; }
  };
}

test('canonicalizes protocol vectors and keeps query ordering significant', async () => {
  const same = (a, b, kind) => Promise.all([sha256(addressMaterial('vod', canonicalizeAddress(a), kind)), sha256(addressMaterial('vod', canonicalizeAddress(b), kind))]).then(([x, y]) => x === y);
  assert.equal(await same('HTTPS://API.Example.com:443/config.json#x', 'https://api.example.com/config.json', 'strict'), true);
  assert.equal(await same('http://api.example.com:80/config.json', 'https://api.example.com/config.json', 'strict'), false);
  assert.equal(await same('http://api.example.com:80/config.json', 'https://api.example.com/config.json', 'endpoint'), true);
  assert.equal(await same('https://api.example.com/a.json', 'https://api.example.com/b.json', 'endpoint'), false);
  assert.equal(await same('https://api.example.com/a.json?x=1&y=2', 'https://api.example.com/a.json?y=2&x=1', 'endpoint'), false);
});

test('resolves exact aliases, refuses host-only silent merges, and isolates config types', async () => {
  const store = createMemoryPlaybackStore();
  const input = (key, strict, endpoint, host, type = 'vod', state = 'empty') => parseIdentityRequest({ schema: 'webhtv.playback.identity.v1', operation: 'resolve', interfaceKey: key, configType: type, strictAddressKeys: strict, endpointMatchKeys: endpoint, hostMatchKeys: host, legacyConfigKeys: [], sourceDataState: state }, new Headers());
  let result = await resolveIdentity(store, 'token', input('a', ['strict'], ['endpoint'], ['host']));
  assert.equal(result.body.action, 'create');
  result = await resolveIdentity(store, 'token', input('b', ['strict'], ['endpoint'], ['host']));
  assert.equal(result.body.action, 'adopt');
  assert.equal(result.body.canonicalInterfaceKey, 'a');
  result = await resolveIdentity(store, 'token', input('c', [], [], ['host']));
  assert.equal(result.body.action, 'confirm_required');
  result = await resolveIdentity(store, 'token', input('live', ['strict'], ['endpoint'], ['host'], 'live'));
  assert.equal(result.body.action, 'create');
});


test('replays the same resolve result for an idempotent request id', async () => {
  const store = createMemoryPlaybackStore();
  const headers = new Headers({ 'x-webhtv-request-id': 'request-1' });
  const input = parseIdentityRequest({ schema: 'webhtv.playback.identity.v1', operation: 'resolve', interfaceKey: 'idempotent-a', strictAddressKeys: ['strict-idempotent'], endpointMatchKeys: [], hostMatchKeys: [], legacyConfigKeys: [], sourceDataState: 'empty' }, headers);
  const first = await resolveIdentity(store, 'token-idempotent', input);
  const second = await resolveIdentity(store, 'token-idempotent', input);
  assert.deepEqual(second, first);
});

test('rebinds a device key when its strong keys now match a different canonical', async () => {
  const store = createMemoryPlaybackStore();
  const input = (key, strict, endpoint, host, state = 'has_data') => parseIdentityRequest({ schema: 'webhtv.playback.identity.v1', operation: 'resolve', interfaceKey: key, configType: 'vod', strictAddressKeys: strict, endpointMatchKeys: endpoint, hostMatchKeys: host, legacyConfigKeys: [], sourceDataState: state }, new Headers());
  // canonical 'a' owns interface A.
  let result = await resolveIdentity(store, 'token', input('a', ['strictA'], ['endpointA'], ['hostA'], 'empty'));
  assert.equal(result.body.action, 'create');
  // device 'b' joins interface A through an unambiguous strong match.
  result = await resolveIdentity(store, 'token', input('b', ['strictA'], ['endpointA'], ['hostA'], 'empty'));
  assert.equal(result.body.action, 'adopt');
  assert.equal(result.body.canonicalInterfaceKey, 'a');
  // canonical 'c' owns a different interface B.
  result = await resolveIdentity(store, 'token', input('c', ['strictC'], ['endpointC'], ['hostC'], 'empty'));
  assert.equal(result.body.action, 'create');
  // Device 'b' switches its sync source to interface B: its key is still bound
  // to 'a', but a single unambiguous strong match to 'c' means an interface
  // switch — rebind instead of the hard 409 conflict.
  result = await resolveIdentity(store, 'token', input('b', ['strictC'], ['endpointC'], ['hostC'], 'has_data'));
  assert.equal(result.body.action, 'rebind');
  assert.equal(result.body.canonicalInterfaceKey, 'c');
  // The device stays bound to interface B on later resolves.
  result = await resolveIdentity(store, 'token', input('b', ['strictC'], ['endpointC'], ['hostC'], 'has_data'));
  assert.equal(result.body.action, 'keep');
  assert.equal(result.body.canonicalInterfaceKey, 'c');
});

test('keeps a bound device on its own canonical when its accumulated keys span several', async () => {
  const store = createMemoryPlaybackStore();
  const input = (key, strict, endpoint, host, state = 'empty') => parseIdentityRequest({ schema: 'webhtv.playback.identity.v1', operation: 'resolve', interfaceKey: key, configType: 'vod', strictAddressKeys: strict, endpointMatchKeys: endpoint, hostMatchKeys: host, legacyConfigKeys: [], sourceDataState: state }, new Headers());
  let result = await resolveIdentity(store, 'token', input('a', ['strictA'], ['endpointA'], ['hostA'], 'empty'));
  assert.equal(result.body.action, 'create');
  result = await resolveIdentity(store, 'token', input('b', ['strictA'], ['endpointA'], ['hostA'], 'empty'));
  assert.equal(result.body.action, 'adopt');
  result = await resolveIdentity(store, 'token', input('c', ['strictC'], ['endpointC'], ['hostC'], 'empty'));
  assert.equal(result.body.action, 'create');
  // Device 'b' is bound to 'a'. Its source was repointed at other interfaces in
  // the past, and the App never prunes their fingerprints, so the strong keys
  // now match 'a' AND 'c'. Being bound to 'a' is not ambiguous for 'b': keeping
  // the binding is the only outcome that does not fail every sync with 409.
  result = await resolveIdentity(store, 'token', input('b', ['strictA', 'strictC'], ['endpointA', 'endpointC'], ['hostA', 'hostC'], 'has_data'));
  assert.equal(result.body.action, 'keep');
  assert.equal(result.body.canonicalInterfaceKey, 'a');
});

test('keeps a hard conflict when a bound device matches canonicals other than its own', async () => {
  const store = createMemoryPlaybackStore();
  const input = (key, strict, endpoint, host, state = 'empty') => parseIdentityRequest({ schema: 'webhtv.playback.identity.v1', operation: 'resolve', interfaceKey: key, configType: 'vod', strictAddressKeys: strict, endpointMatchKeys: endpoint, hostMatchKeys: host, legacyConfigKeys: [], sourceDataState: state }, new Headers());
  let result = await resolveIdentity(store, 'token', input('a', ['strictA'], ['endpointA'], ['hostA'], 'empty'));
  assert.equal(result.body.action, 'create');
  result = await resolveIdentity(store, 'token', input('d', ['strictA'], ['endpointA'], ['hostA'], 'empty'));
  assert.equal(result.body.action, 'adopt');
  result = await resolveIdentity(store, 'token', input('b', ['strictB'], ['endpointB'], ['hostB'], 'empty'));
  assert.equal(result.body.action, 'create');
  result = await resolveIdentity(store, 'token', input('c', ['strictC'], ['endpointC'], ['hostC'], 'empty'));
  assert.equal(result.body.action, 'create');
  // Device 'd' is bound to 'a', but its strong keys match 'b' and 'c' only —
  // genuinely ambiguous with no binding among the matches, so 409 is preserved.
  result = await resolveIdentity(store, 'token', input('d', ['strictB', 'strictC'], ['endpointB', 'endpointC'], ['hostB', 'hostC'], 'has_data'));
  assert.equal(result.status, 409);
  assert.equal(result.body.action, 'conflict');
});

test('routes sync to the requesting key binding when aliases span several canonicals', async () => {
  const store = createMemoryPlaybackStore();
  const input = (key, strict) => parseIdentityRequest({ schema: 'webhtv.playback.identity.v1', operation: 'resolve', interfaceKey: key, configType: 'vod', strictAddressKeys: strict, endpointMatchKeys: [], hostMatchKeys: [], legacyConfigKeys: [], sourceDataState: 'empty' }, new Headers());
  await resolveIdentity(store, 'token', input('a', ['strictA']));
  await resolveIdentity(store, 'token', input('b', ['strictB']));
  // Device 'd' joins interface 'a'; the aliases header later carries stale
  // fingerprints of interface 'b' as well.
  await resolveIdentity(store, 'token', input('d', ['strictA']));
  assert.equal(await resolveConfigKey(store, 'token', 'vod', 'd', ['strictB']), 'a');
  // Nothing declares an identity for this key, so the ambiguity stays an error.
  await assert.rejects(() => resolveConfigKey(store, 'token', 'vod', 'unbound-device', ['strictA', 'strictB']), (error) => error.status === 409);
});
