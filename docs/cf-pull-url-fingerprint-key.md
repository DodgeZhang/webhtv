# cf-pull-url-fingerprint-key — pull 重写为接口 URL 指纹，打通只认 URL 哈希的旧版 App

## Recovery anchor

- 目标：A 设备拉取 5 条全跳过（applied=0），B 设备正常。让 A 也能写入 B 侧记录。
- 根因：服务端把 pull 的 configKey 重写为设备随机 interfaceKey（UUID `3c264f95...`）；新版 App（B）cidForKey 第一重匹配 interfaceKey 成功，旧版 App（A）的 cidForKey 只认/更认接口 URL 的 SHA-256 指纹，UUID 无法映射到本地接口 → 5 条全部"接口不匹配"跳过。
- 验收：部署后 curl A 视角 pull 返回的 configKey 为 64 位十六进制 URL 指纹；A 删除重加同步源后 applied/created>0，本地出现「背着善宰跑」「深渊无间」。
- 状态：代码已提交待部署；只读 curl 验证 + A 设备验证在部署后。
- 回滚锚：revert 本次 commit 回到 577ee41cb4（重写为 submittedConfigKey）。

## 证据链（2026-10-04 09:37）

- A 日志 `playback-remote-sync: fetched=5 applied=0 deleted=0 skipped=5 failed=0 identity=keep`，删除重加（游标确认归零，PlaybackRemoteSyncStore.remove 按 id 删 + 新增生成新 id）后首次全量仍 5 条全 skip。
- A「最近观看」本地有「八仙！」「给阿嬷的情书」，无「背着善宰跑」「深渊无间」（B 当天 09:21/09:23 产生，updatedAt 新于一切昨晚墓碑）→ 本地无的记录也 skip。
- local=null 时 remote upsert 仅两个 skip 通道：cid<=0（接口不匹配）或本地墓碑压制。墓碑不可能 5 条全中（含 A 正在看的八仙）；统一 configKey 级原因只可能是 cid<=0。
- git 考古 cidForKey 三个已提交版本：
  - 5b0500d51b 初版：keyForCid 与 cidForKey **都只用 keyForUrl（URL SHA-256）**。
  - eb76ab8a82：两者都支持 interfaceKey(UUID)+URL hash。
  - cc85ad350c：三重匹配（interfaceKey / 所有 URL hash / legacyConfigKeys）。
  - 仓库 main 的 PlaybackProgressWriter.java / PlaybackRemoteSyncer.java 带未解决冲突标记（无法编译），设备实际 APK 是另一处构建的版本，无法从仓库静态锁定其 cidForKey 行为；B 成功证明 B 为新版，A 失败证明 A 落 URL-hash 识别路径。
- PlaybackConfigIdentity.snapshot：设备 resolve 上报的 legacyConfigKeys 必含主 URL + 全部备用 URL 的 keyForUrl（64 位 hex）；服务端 merge 时 mergeKeyList 已把 A、B 的 URL 指纹聚合进 canonical 身份。

## 变更（playback-sync.js pull）

- 新增 `pullRewriteKey(request, configType, canonicalConfigKey, submittedConfigKey)`：读 identity registry 的 canonical 身份，取 legacyConfigKeys 中第一个 `/^[0-9a-f]{64}$/` 的 URL 指纹；取不到或任何异常回落 submittedConfigKey。
- pull 循环把每条 change.configKey 改写为该指纹（原为 submittedConfigKey）。
- 只读 registry（DO sqlite 单行），无写入，不增加 rows-written 配额消耗。
- URL 指纹是初版/中间版/新版 cidForKey 的公共识别形态，同接口（同 URL）两台设备指纹相同，与"设备随机 UUID 是否互认"无关。

## 与前次 f9f7e434c6 的区别

f9f7e434c6（legacyConfigKeys[0] 盲取）部署后全局 1101，事后查明 1101 是同日 DO 免费版 rows-written 配额被按行迁移耗尽（cf-do-quota-rows-written 已修复为集合式），与该改动无关，故当时回滚、未验证。本次：
- 明确只取 64hex 形态（真实 URL 指纹），不盲取 [0]；
- 部署环境已健康（配额 08:00 重置、health 200）；
- 部署后先只读 curl 确认指纹再让设备同步。

## 验证

1. `curl pull?limit=2`（A key 头）：changes[].configKey 为 64hex，非 UUID。
2. A 删除→新增同步源→同步：拉取 5，created>0，本地出现 B 的两部剧；B 同步不受影响（URL 指纹同样命中）。
3. 若 A 仍 skip 5，则与 configKey 无关，根因为 A 本地删除墓碑库，下一步处理 A 本地 PlaybackDeleteTombstoneStore。
