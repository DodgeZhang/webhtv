# CF fix — same-name primary/backup interfaces must not stay two main spaces

Task ID: `cf-fix-same-name-merge`
Lane: `standard`
Base HEAD: `bc535571e1637cacfe4e961f381f5e361bfa2d4e` (branch `main`)
Scope: `serverless/webhtv-remote-cloudflare/src/playback-sync.js`, this document.

## Objective

Every tvbox interface in this deployment is configured with a primary and a backup
address. The two addresses share no address fingerprint at all (different scheme,
host and path), so two devices that each reported only one of them registered two
separate canonical identities — two "main spaces" of one interface that no
fingerprint-based grouping can ever join.

Completion sentence: two same-named interfaces that only ever reported different
primary/backup URLs collapse into one main space, different interface names never
merge, and the dashboard labels each space with the name the majority of its rows
carry.

Acceptance criteria:

1. `planNameUnifyGroups` merges two registered canonicals whose stored spaces
   carry the same exact interface name.
2. Two spaces whose names differ are never merged by this path.
3. A space whose rows mix two interface names only contributes a name when that
   name covers a strict majority (`nameItems * 2 > items`); a mixed space cannot
   name another space at all.
4. `GET /api/playback/sync/configs` and `.../identity/spaces` name a space by the
   name the majority of its rows carry, not by its most recent row.
5. No change to `identity.js`, to the address-fingerprint merge path, or to how
   playback rows are stored.

## Evidence — hash check of the four URLs

`legacyConfigKey` = SHA-256 of the trimmed raw URL; strict/endpoint keys are the
`webhtv.address.*.v1` material hashes. Verified against the live registry
(`adminInspectIdentity`, epoch 846):

| Interface | URL | strict | endpoint | legacy |
| --- | --- | --- | --- | --- |
| 摸鱼 | `cnb.cool/.../moyu/my.json` | `550f0adf…` | `5921f0b2…` | `de872298…` |
| 摸鱼 | `gh-proxy.org/https://raw.githubusercontent.com/.../moyu/my.json` | `df98f2ab…` | `b8ca392d…` | `44fb6599…` |
| 饭太硬 | `cnb.cool/.../fantaiying/fty.json` | `cc2f3f76…` | `eafd7066…` | `64e93960…` |
| 饭太硬 | `gh-proxy.org/https://raw.githubusercontent.com/.../fantaiying/fty.json` | `47e39637…` | `d5880cad…` | `cb71041e…` |

Conclusions:

- Both interfaces already hold **both** address tiers on their canonical
  (`b27dbd82` = 摸鱼, `4c1c5c5b` = 饭太硬). The primary/backup pair itself is
  therefore not broken; the defect only appears when devices report the two URLs
  separately and each registers its own canonical.
- Host-tier fingerprints are shared by both interfaces (`0e427618…` cnb.cool,
  `acfafdd5…` gh-proxy.org), so host matching must never decide a merge — the
  existing guard (never host-only) is preserved unchanged.
- The "two 摸鱼 main spaces" the operator saw were mostly a **naming** bug: space
  `4c1c5c5b` holds 4 饭太硬 rows plus 1 newer 摸鱼 row, and the dashboard labelled
  it by that newest row.

## Change

All in `playback-sync.js`; no `identity.js` change is needed because
`mergeIdentityRegistry` copies key lists through `mergeKeyList`, which does not go
through the `addIdentityKeys` cross-interface guard.

1. `spaceNameIndex(where, args)` — one aggregated `GROUP BY config_key, name`
   scan per space: record count, earliest/latest timestamp, and the name carried
   by the largest group of rows, plus that group's size (`nameItems`). Ties
   prefer the more recently used name, then key order, so a label never flickers.
2. `listConfigs()` and `listIdentitySpaces()` now take the space name from
   `spaceNameIndex` instead of "the configName of the newest row".
3. `spaceSnapshot(configType)` uses the same index and additionally returns
   `firstAt` / `nameItems` for the merge gate.
4. `planNameUnifyGroups(registry, spaces, submitted, hintName)` — allows
   registered canonical identities to fold, not just unregistered spaces: it
   groups canonicals by their majority name, elects the winner (most records,
   then earliest first record, then key order), and merges the other same-named
   canonicals plus the unregistered spaces of that name into it. Empty names, a
   space without a strict-majority name, and a name spread over several
   canonicals stay untouched (the last case is handled by the election, which
   picks one target). Each key participates in at most one plan, and the
   submitter's own `hintName` group is evaluated first so a renamed interface
   cannot be dragged into its stale-name group.
5. `adminMoveRows` maintenance op — reuses `movePlaybackRows` to move rows
   between two **existing** spaces without touching the identity registry, for
   the historical cleanup below.

Reversibility: a wrong merge can be undone with `adminSplitIdentity` /
`adminUnbindIdentity` (rows are copied, the source stays as an alias), and rows
are never reinterpreted.

## Local verification

- `planNameUnifyGroups` pure-function suite: 13/13 PASS — same-name registered
  collapse (primary/backup pair), different names never merge, mixed space
  (the live `4c1c5c5b` shape) contributes no name, 3:3 majority gate does not
  merge, canonical + unregistered join, single canonical + unregistered keeps old
  behaviour, two unregistered spaces elect a target, a lone unregistered space is
  untouched, alias spaces never participate, the submitter's hint group joins, an
  empty name never merges, a three-way tie elects the earliest, and a key is never
  reused across two plans.
- Syntax: each changed region re-parsed in isolation (methods wrapped in a class
  body) — `listConfigs`/`spaceNameIndex`, `listIdentitySpaces`,
  `runAdminMoveRows`, `runMaintenance`, `spaceSnapshot`, `planNameUnifyGroups`
  all parse OK; a whole-file reconstruction of HEAD plus every diff hunk also
  parses OK.

Note for future readers: chunked reads of this file through the reader tool can
duplicate or shift a single line at a chunk boundary, which produces a bogus
"Unexpected token" during whole-file string parsing. Verify regions by content
(anchor on the declaration line), not by raw line numbers.

## Historical cleanup (run after the Worker deploys)

Via `POST /api/playback/sync/maintenance` with the operator token:

1. `{"op":"adminMoveRows","configType":"vod","moves":[{"from":"4c1c5c5b-…","to":"b27dbd82-…","name":"\u6478\u9c7c"}]}`
   — move the single 摸鱼 row out of the 饭太硬 space back into the 摸鱼 space.
   The `name` filter must be passed as `\uXXXX` escapes; a literal Chinese string
   silently matches 0 rows.
2. `{"op":"adminMoveRows","configType":"vod","moves":[{"from":"d51503ab-…","to":"4c1c5c5b-…"}]}`
   — the three rows in the `d51503ab` alias space are duplicates of rows already
   present in `4c1c5c5b` (same `item_key` + `updated_at`), so the
   `WHERE excluded.updated_at > playback_items.updated_at` guard writes nothing
   and the source rows are removed, emptying that space.

Expected after cleanup: `4c1c5c5b` = 饭太硬, 4 rows; `b27dbd82` = 摸鱼, 32 rows;
`d51503ab` gone or empty.

## Risk

- A wrong same-name merge couples two unrelated interfaces. Mitigated by exact
  (trimmed, case-sensitive) name equality, the strict-majority rule, and the
  fact that every merged source becomes an alias that can be split again.
- Naming now depends on row distribution; a space with an evenly mixed name
  history keeps its largest group's name, which is the intended
  "majority wins" semantics.

## Next action

Commit and tag, push `main`, wait for the Cloudflare build, then run the two
`adminMoveRows` calls and re-check `GET /api/playback/sync/configs`.
