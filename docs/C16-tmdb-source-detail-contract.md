# C16：T3/T4 详情内嵌 TMDB 元数据设计

> 状态：设计完成，补充评估 `power721/alist-tvbox` 与 `power721/atv-player` 后待用户评审；本轮只交付设计，不授权修改运行代码。

## Recovery anchor

- 目标：扩展现有详情返回协议，使 T3 客户端爬虫和 T4 服务端接口可在 `detailContent` 结果中直接携带 TMDB 数据；APP 优先采用身份匹配的源数据，仅为缺失能力按需访问 TMDB。
- 基线：WebHTV `dev4@32a52698e5dab09fe18e49d18849a947057ca717`；OmniBox `main@d57b3e6672337febd46feca0d8ad59c6a2b507c3`；alist-tvbox `master@8a222f69a80291e836db702c34daf1ed5bdd5630`；atv-player `master@09feed1d5e5102f13bf91c9fbb76ea92808cb76d`。
- 范围：`assessment`；仅本文档与总评估索引，不修改 APP、T3/T4 运行代码、爬虫 ABI、依赖或构建产物。
- 回滚：删除本文档并撤销总评估索引中的 C16 条目即可。
- 追加结论：alist-tvbox 适合作为 T4 的元数据持久化和图片访问参考，但当前 `/vod` 输出仍是平铺 `vod_*` 字段；atv-player 适合作为 APP 的字段级合并、季级身份、缓存和异步取消参考，不能直接作为 Android 协议实现。
- 下一动作：等待用户评审；明确批准后，从协议模型和纯逻辑测试开始实施。

## 1. 设计结论

推荐在现有详情结果的 `Vod` 对象中增加可选 `tmdb` 字段，而不是照搬 OmniBox 的独立元数据请求接口。

核心规则：

1. 保持 `detailContent(ids)` 方法签名和顶层 JSON 不变，旧源和旧客户端继续兼容。
2. T3 与 T4 返回完全相同的数据合同，不在 APP 中按来源类型分叉。
3. `tmdb.id + tmdb.media_type` 是剧集级身份锚点；若详情处于选中季上下文，还必须包含 `season_number`。
4. APP 按能力组判断缺口。源数据完整时不得连接 TMDB；部分缺失时只补所需能力。
5. 合并采用 fill-only，源提供的合法非空字段优先，网络结果不得覆盖它们。
6. 空值默认表示未知。只有能力组列入 `complete` 时，空数组才表示明确为空。
7. 季、集、视频以及更多推荐等延迟能力只在用户实际访问时补齐。

## 2. 两项目现状分析

### 2.1 WebHTV 当前链路

`SiteViewModel.detailContent()` 最终进入 `SiteApi.detailContent()`：

- T3 调用爬虫的 `detailContent(List<String> ids)`，再由 `Result.fromJson()` 解析。
- T4 以 `ac=detail`、`ids=<id>` 等参数调用服务端，再由 `Result.fromType()` 解析。
- 两种来源随后统一进入 `Result.list<Vod>`、播放线路解析和 `VodDetailCache`。
- 当前 `Vod` 只有传统详情和播放字段，没有结构化 TMDB 数据。
- TMDB 详情模式当前通过 `TmdbService` 独立访问 TMDB，详情请求覆盖图片、演职员、翻译、外部 ID、视频、推荐和相似内容；电视内容另有季与单集请求。
- `TmdbDetailPrefetch` 使用 `tmdbId + mediaType` 标识请求并负责取消与身份隔离。

因此，扩展点应是 `Vod` 的结构化可选字段，并由统一仓库层消费，不能让 Activity 或 T3/T4 各自实现合并逻辑。

### 2.2 OmniBox 可借鉴逻辑

OmniBox 固定提交 `d57b3e6672337febd46feca0d8ad59c6a2b507c3` 使用 `TMDBMetadata` 容器保存资源身份、刮削类型、`scrapeData.tmdb`、更新时间和视频映射。`TMDBDetails` 包含 ID、电影/剧集名称、海报、背景、简介、评分、日期、类型、演职员和剧集信息。页面异步获取元数据，存在时显示增强内容，不存在时退回普通源详情。

可复用部分是结构化对象、媒体类型、资源映射及页面降级。不能直接照搬的部分是：

- 独立元数据接口会在详情之后增加一次请求，不符合源随详情直接返回的目标。
- OmniBox 字段不足以覆盖 WebHTV 的图片集、外部 ID、视频、推荐、相似内容和季集分层能力。
- 整体 `hasData()` 不能表达“已有核心信息但缺少演职员”这类局部缺口。

### 2.3 alist-tvbox：T4 服务端参考

固定版本 `8a222f69a80291e836db702c34daf1ed5bdd5630` 是 AList/TVBox 服务端。它的相关实现分成两条链：

- `src/main/java/cn/har01d/alist_tvbox/dto/TmdbDto.java:9-29` 和 `TmdbCredits.java:7-11` 将 TMDB 的 ID、电影/剧集标题、简介、类型、语言、国家、演职员、海报、评分和日期收敛为服务端 DTO。
- `src/main/java/cn/har01d/alist_tvbox/entity/Tmdb.java:20-38` 保存较窄的 TMDB 媒体快照；`TmdbMeta.java:23-40` 将本地路径、站点和 TMDB 记录关联起来。
- `src/main/java/cn/har01d/alist_tvbox/entity/MediaMetadata.java:15-53` 是更接近本需求的实现：以 `provider + metaId + season` 唯一索引保存 `status`、完整 JSON `payload` 和 `fetchTime`，用于完结剧长期零网络、在播剧按状态/TTL 刷新。
- `src/main/java/cn/har01d/alist_tvbox/dto/MetadataDetails.java:7-62` 已覆盖简介、季总数、集数、播出状态、别名、分集、评分、外部 ID、播放链接、导演、编剧、演员、背景图和背景图候选，比单纯 `TmdbDto` 更适合作为服务端快照字段清单。
- `src/main/java/cn/har01d/alist_tvbox/service/metadata/MetadataService.java:93-214` 实现“持久层 -> provider 内存/网络 -> 持久化”的读路径；旧快照或坏快照视为过期，失败的空对象不覆盖已有快照。
- `src/main/java/cn/har01d/alist_tvbox/service/TmdbEndpoint.java:18-32,126-174` 处理 TMDB API/图片镜像、Worker 池、URL 安全和 API key/Bearer token 隔离。
- `/vod` 详情入口位于 `web/TvBoxController.java:66-103`，最终由 `TvBoxService` 返回 `MovieDetail`；`tvbox/MovieDetail.java:11-35` 没有 `tmdb` 对象。`TvBoxService.java:2687-2768` 将 TMDB 记录映射回 `vod_pic`、`vod_year`、`vod_remarks`、`vod_actor`、`vod_director`、`vod_area`、`type_name`、`vod_lang` 和 `vod_content`。

因此，alist-tvbox 对 C16 的结论是“**服务端生产和快照设计有帮助，输出协议不能直接复用**”：它证明 T4 可以在服务端预抓取并持久化 TMDB，且季必须进入缓存键；但若只照搬其当前 `/vod` 平铺映射，APP 无法区分源字段和 TMDB 字段，也无法表达局部能力完整性。C16 应保留 `Vod.tmdb` 结构化对象，同时允许 T4 内部采用 `provider + tmdbId + season` 的持久化键。

### 2.4 atv-player：客户端参考

固定版本 `09feed1d5e5102f13bf91c9fbb76ea92808cb76d` 是桌面客户端，当前 HEAD 包含完整 metadata 子系统；TMDB Worker 轮询池提交 `e33fc859752f46547b992ecfffab7f5e48b902e1` 不是 HEAD 的祖先，因此本评估不把该提交的轮询实现当作冻结基线，只采纳 HEAD 中已存在的 provider、缓存和详情代码。

可借鉴证据：

- `src/atv_player/metadata/models.py:14-99` 将查询、匹配和详情记录分层；`MetadataRecord` 明确保存标题、原始标题、年份、海报、背景图候选、简介、评分、演员/导演、角色/演职员详情、类型、别名、季集、IMDb/TMDB ID 和详情字段。
- `src/atv_player/metadata/providers/tmdb.py:39-52,880-925` 将电视剧季号编码进 provider ID（`<tmdbId>:season:<n>`），并从 TMDB 原始响应保留 `external_ids`、`images`、cast/crew 角色、别名、季简介和背景图候选；`get_detail_full` 在 `:927-958` 进一步补充季/集和剧集级统计字段。
- `src/atv_player/metadata/merge.py:13-73,140-185` 采用按字段的 provider 优先级，而不是整条记录覆盖；简介还区分 `tmdb` 与 `tmdb_season`，说明剧集总简介和选中季简介不能混作一个无上下文字符串。
- `src/atv_player/metadata/cache.py:48-102` 对 provider/detail ID 做 TTL 缓存并使用 SHA-256 文件键；空结果支持更短 TTL。`cache_key.py:6-25` 在已有 TMDB 外部锚点时跳过标题搜索。
- `src/atv_player/metadata/async_runner.py:17-54` 将 provider 搜索/详情封装为可并行、可捕获单 provider 异常的任务；详情控制器在 `controllers/media_detail_controller.py:509-539,584-620,667-729` 以 TMDB 记录为基础再加载其他 provider，并按当前视图身份清理缓存。
- 测试 `tests/test_metadata_cache.py:26-101` 覆盖详情往返、空结果短 TTL 和通用 payload；`tests/test_metadata_cache_key.py:6-25` 覆盖 TMDB 外部锚点；`tests/test_media_detail_page_ui.py:25-67` 使用 `tv:1399:season:1` 和角色、评分、IMDb/TMDB ID、集列表验证详情展示。

因此，atv-player 对 C16 的结论是“**客户端消费机制有帮助，数据模型不直接移植**”：WebHTV 应引入字段级缺口规划、季级身份和缓存隔离，但仍以本项目已有 `TmdbService`、`TmdbDetailPrefetch`、Gson/Parcelable 和 Android 生命周期为实现边界。其 provider 优先级不能原样变成 WebHTV 的覆盖规则，因为用户要求源内嵌 TMDB 字段优先于 APP 网络补齐；在 C16 中它只作为“网络补齐内部不同 provider 的次级策略”参考。

## 3. 方案比较

### 3.1 保持现状

APP 继续自行匹配并请求 TMDB。兼容风险最低，但无法满足源数据直用，会继续产生重复请求、凭据依赖和匹配误差，不推荐。

### 3.2 复制 OmniBox 独立接口

模型清晰，但 T3 难以统一实现，而且页面仍需额外网络往返，不推荐。

### 3.3 在 Vod 中增加可选 tmdb 对象

T3/T4 现有响应、解析、缓存和页面入口不变；新客户端识别该字段，旧客户端忽略未知字段。APP 按能力组定向补齐，兼容性和可回滚性最佳，推荐。

## 4. 统一返回协议

在详情结果的第一个 `Vod` 中增加可选 `tmdb`：

```json
{
  "list": [{
    "vod_id": "source-item-id",
    "vod_name": "示例剧集",
    "vod_play_from": "线路一",
    "vod_play_url": "第1集$https://example/1.m3u8",
    "tmdb": {
      "schema": 1,
      "id": 1399,
      "media_type": "tv",
      "language": "zh-CN",
      "fetched_at": "2026-09-19T00:00:00Z",
      "complete": ["core", "credits", "images"],
      "detail": {}
    }
  }]
}
```

字段约束：

- `schema`：协议主版本，首版固定为整数 `1`。
- `id`：正整数 TMDB ID。
- `media_type`：仅允许 `movie` 或 `tv`。
- `season_number`：可选非负整数；仅 `tv` 使用，表示选中季上下文。电影固定为 0。
- `language`：可选，表示数据主要语言，例如 `zh-CN`。
- `fetched_at`：可选 ISO 8601 UTC 时间，只用于新鲜度判断。
- `complete`：可选能力组数组，表示生产者确认该组完整，即使内容为空。
- `detail`：保持 TMDB v3 的 snake_case JSON 形状，生产者只需返回实际拥有的字段。

### 4.1 detail 支持字段

首版支持当前 TMDB 详情模式使用的字段：

- 身份与标题：`id`、`title`、`name`、`original_title`、`original_name`。
- 摘要与状态：`overview`、`tagline`、`status`。
- 图片：`poster_path`、`backdrop_path`、`images`。
- 日期与评分：`release_date`、`first_air_date`、`vote_average`、`vote_count`。
- 分类与地区：`genres`、`origin_country`、`original_language`。
- 时长：电影 `runtime`，电视 `episode_run_time`。
- 电视：`number_of_seasons`、`number_of_episodes`、`seasons`。
- 扩展：`external_ids`、`credits`、`videos`、`recommendations`、`similar`。

图片字段可为 TMDB 相对路径或绝对 HTTPS URL。绝对 URL 原样使用；以 `/` 开头的路径由 APP 使用当前图片基址和尺寸策略拼接；其他形式无效。源不能通过该对象提供 TMDB API 地址、Authorization 或请求头。

### 4.2 季与集

`seasons` 可内嵌已知季；季对象可带 `episodes`。单集沿用 TMDB 字段，如 `id`、`season_number`、`episode_number`、`name`、`overview`、`air_date`、`still_path`、`runtime`、`guest_stars` 和 `crew`。

当详情对应“某一选中季”而不是整部剧时，`season_number` 参与缓存和合并身份；不能只用剧集级 `id + media_type`，否则不同季的简介、集列表或海报可能互相污染。等价身份为 `id + media_type + season_number(无则 0)`。

只有 `complete` 包含 `season:1` 时，该季空 `episodes` 才表示确认无数据；否则用户进入该季时仍可按需补齐。单集完整声明使用 `episode:1:1`。

### 4.3 能力组

首版定义：

- `core`
- `credits`
- `images`
- `external_ids`
- `videos`
- `recommendations`
- `similar`
- `season:N`
- `season_videos:N`
- `episode:N:E`
- `episode_videos:N:E`

推荐和相似内容的 `complete` 只表示首批结果完整，不表示所有分页已加载。

## 5. 缺失、空值与合并语义

- 字符串缺失、`null` 或空白均视为缺失。
- ID 必须大于 0。
- 分数必须有限且在合理范围内。未声明完整时零分视为未知；完整组中的零分可表示真实值。
- 对象类型错误时只丢弃该字段，不得使普通详情失败。
- 非空数组可直接采用；空数组只有在相应组包含于 `complete` 时才表示明确为空。
- 图片加载失败只尝试同组候选或普通 `Vod` 图片，不因此自动发起 TMDB 元数据请求。

同一字段优先级：

1. 身份匹配且合法的源内嵌字段。
2. 本地缓存中同 `id + media_type + season_number + language` 的有效字段。
3. 本次按需请求 TMDB 得到的字段。
4. 普通 `Vod` 的标题、封面和简介回退。
5. UI 默认值。

合并必须为 fill-only。用户刷新来源时重新执行详情函数；用户刷新 TMDB 时只更新非源字段。

身份最小校验：`schema == 1`、`id > 0`、媒体类型合法；若 `detail.id` 存在，必须与外层 ID 一致。强冲突时拒绝增强对象，继续显示普通详情并使用现有 TMDB 匹配流程。

## 6. APP 请求规划

### 6.1 首屏

1. 解析并验证 `tmdb`。
2. 在启动 `TmdbDetailPrefetch` 之前计算当前页面所需能力组。
3. 所需字段完整时直接渲染，TMDB 请求数必须为 0。
4. 部分缺失且用户 TMDB 配置可用时，复用现有详情请求，并只填缺口。
5. 只有合法 ID 时跳过标题搜索，直接按 ID 补齐。
6. ID 缺失或身份无效时维持现有匹配流程。
7. TMDB 未配置或补齐失败时，保留源增强数据与普通详情，不把增强失败升级为整页失败。

### 6.2 延迟能力

- 选择某季时检查 `season:N`。
- 打开单集扩展信息时检查 `episode:N:E`。
- 打开视频区时检查对应视频组。
- 推荐和相似内容先用内嵌首批结果，加载更多继续走现有分页。
- 页面关闭、切源或身份变化时继续使用现有取消与 identity 机制，旧请求不得覆盖新页面。

内嵌数据不依赖 APP 的 TMDB API Key。没有凭据时仍应完整显示已有源数据，只是不补缺口。

## 7. 数据模型与代码边界

批准实施后建议新增：

- `Vod.tmdb: TmdbSourcePayload`
- `TmdbSourcePayload`：版本、身份、语言、时间、完整组及详情对象
- `TmdbCapabilityPlanner`：纯函数计算缺口
- `TmdbSourceMerger`：校验、图片规范化和 fill-only 合并
- `TmdbDetailRepository`：统一源数据、本地缓存与网络补齐

同步覆盖 Gson、`Vod` Parcelable 或替代的大对象传递策略、`Result` 复制路径、`VodDetailCache`、页面生命周期和缓存键。Activity 不直接解析 JSON，源对象也不能未经校验写入 `TmdbService` 缓存。

## 8. 缓存、安全与负载

- 详情缓存继续保存整个 Result，旧缓存没有 `tmdb` 时按旧逻辑工作。
- TMDB 补齐缓存键必须包含 `id + media_type + season_number + language`，不能让同一剧不同季共享详情或简介。
- T4 内部持久化可采用 `provider + tmdbId + season_number` 唯一键，并保存状态、抓取时间和 JSON 快照；完结数据可长 TTL，在播数据按状态刷新。网络失败或空白快照不得覆盖已有有效快照。
- 合并后保留字段来源归属，防止缓存命中后误覆盖源字段。
- `fetched_at` 只影响刷新建议，不阻止旧数据先展示。
- APP 补齐只能使用用户当前 TMDB 配置，禁止源控制 API 地址、图片基址或凭据。
- 建议限制 `tmdb` 对象为 2 MiB、单数组 500 项、单字符串 64 KiB；超限时局部丢弃增强组，不影响播放线路。
- 日志只记录站点 key、Vod ID、TMDB ID、组命中和拒绝原因，不记录凭据或完整原始内容。

## 9. T3 与 T4 生产者指南

### 9.1 T3

继续返回原有 `detailContent(List<String> ids)` JSON，只在目标 Vod 内增加 `tmdb`。不新增回调，也不要求宿主向爬虫提供 TMDB 凭据。Java、JS 和 Python 桥接均将其作为普通 JSON 字段。

### 9.2 T4

继续响应现有 detail 请求。有合法 TMDB 数据时内嵌相同合同；没有时省略 `tmdb`，不得返回伪造 ID 或滥用 `complete`。

只有身份数据也有价值：

```json
"tmdb": {
  "schema": 1,
  "id": 1399,
  "media_type": "tv",
  "detail": {}
}
```

完整首屏对象应准确声明已有能力。若 `credits`、`images`、`videos`、`recommendations` 或 `similar` 已列入 `complete`，其中的空数组就是确认为空，APP 不再访问 TMDB。

T4 内部若有持久化元数据，建议按 `provider=tmdb、metaId=id、season=season_number` 保存，再在 `/vod` 的 `MovieDetail`/详情 `Vod` 序列化阶段组装为 `tmdb`。不能仅把 `Tmdb` 表的 `cover/score/actors` 平铺字段当作协议，因为这会丢失来源、季上下文、能力组和空值语义。

## 10. 收益、风险与取舍

收益：完整数据可实现零 TMDB 请求，降低延迟和凭据依赖；合法 ID 可避免标题误匹配；部分数据可增量补齐；旧源和普通详情兼容。

主要风险及控制：

- 错误 ID：严格身份校验，冲突时降级。
- 大型季集数据：能力组、延迟加载和大小上限。
- 空数组歧义：使用 `complete`。
- 文案或图片差异：源优先，fill-only。
- 数据陈旧：记录语言和抓取时间，时间敏感组按访问场景判断。
- T3/T4 分叉：两端只生产同一合同，APP 集中解析和消费。

## 11. 分阶段实施计划

### 阶段 1：协议和纯逻辑

新增模型、能力组、校验与合并器；给 `Vod` 增加可选字段；覆盖 Gson、Parcelable、复制和缓存合同测试。

### 阶段 2：详情模式接入

在预取前规划缺口；完整首屏直接渲染；部分缺失复用现有 `TmdbService.detail()`；保持取消和身份隔离。

### 阶段 3：季集与延迟组

接入季、集和各级视频组；推荐/相似首批内嵌，分页保持现状；补充诊断和生产者文档。

三个阶段独立可回滚。本轮不授权任何阶段实现。

## 12. 验收矩阵

1. 无 `tmdb` 的旧 T3/T4 响应与现状一致。
2. T3/T4 返回相同 JSON 时解析结果和请求计划一致。
3. 完整首屏打开详情时 TMDB 请求数为 0。
4. 只缺 `credits` 时最多发一次补齐请求，源字段不被覆盖。
5. 只有合法 ID 时跳过搜索并直接按 ID 补齐。
6. ID、媒体类型或 `detail.id` 冲突时拒绝增强对象，普通详情和播放仍可用。
7. 未声明完整的空数组触发按需补齐；已声明完整的空数组不联网。
8. 未配置 TMDB Key 时仍展示全部源增强数据。
9. 绝对图片 URL、相对路径和非法路径分别按合同处理。
10. 详情缓存往返后字段、来源归属和 `complete` 不丢失，旧缓存可读。
11. Parcelable 或复制后身份与组不丢失，且不产生超大 Binder 传输。
12. 切源、快速退出或切换条目时，旧请求不能覆盖新页面。
13. 电视内容只在打开缺失季或集时访问对应接口。
14. 超限或类型错误 payload 被局部拒绝，普通详情和播放线路不受影响。

最小实机验证：移动端和电视端各测试一条完整 T3、一条部分 T4 和一条无 TMDB 的旧源，记录首屏请求数、字段渲染、季集访问和切源隔离。

## 13. 证据与边界

- WebHTV 基线 `32a52698e5dab09fe18e49d18849a947057ca717`：`SiteViewModel`、`SiteApi`、`Vod`、`TmdbService`、`TmdbDetailPrefetch` 证明 T3/T4 已汇聚到 Result/Vod，并说明当前 TMDB 请求和生命周期边界。
- OmniBox 基线 `d57b3e6672337febd46feca0d8ad59c6a2b507c3`：`TMDBMetadata`、`TMDBDetails`、服务端元数据处理和页面消费证明结构化元数据及降级方式，也显示独立请求模型不满足本需求。
- alist-tvbox `8a222f69a80291e836db702c34daf1ed5bdd5630`：`dto/TmdbDto.java`、`dto/MetadataDetails.java`、`entity/MediaMetadata.java`、`service/metadata/MetadataService.java`、`service/metadata/TmdbMetadataProvider.java`、`service/TmdbEndpoint.java`、`web/TvBoxController.java` 和 `service/TvBoxService.java`。证据等级 A/B：实际源码和服务端测试；支持“服务端可按季持久化 TMDB 快照、失败不覆盖旧值、图片/凭据应隔离”，同时证明其当前 `/vod` 是平铺 `vod_*` 输出而非 C16 结构化合同。
- atv-player `09feed1d5e5102f13bf91c9fbb76ea92808cb76d`：`metadata/models.py`、`metadata/providers/tmdb.py`、`metadata/merge.py`、`metadata/cache.py`、`metadata/cache_key.py`、`metadata/async_runner.py`、`controllers/media_detail_controller.py` 及 `tests/test_metadata_cache.py`、`test_metadata_cache_key.py`、`test_media_detail_page_ui.py`。证据等级 A/B：实际源码和测试；支持“客户端应按字段合并、按季隔离身份、缓存外部锚点、异步任务隔离旧视图”。TMDB Worker 轮询提交 `e33fc859752f46547b992ecfffab7f5e48b902e1` 非该 HEAD 祖先，未纳入采用范围。
- 外部 PR、Issue、论文和性能基准不适用：本阶段只设计兼容 JSON 合同，不升级依赖、不改变运行行为，也不提出未经测量的性能结论。

## 14. 最终建议

**建议实施** `Vod.tmdb` 可选对象、TMDB 原生字段形状、能力组完整声明及 fill-only 合并；增加 `season_number` 上下文和 `id + media_type + season_number + language` 缓存键。吸收 alist-tvbox 的服务端按季快照、状态/TTL、失败不覆盖和图片/凭据隔离，吸收 atv-player 的字段级合并、外部 ID 直达、季级 provider ID、空结果短 TTL 和异步隔离。不要增加独立 T3/T4 元数据接口，不要在数据完整时连接 TMDB，也不要让源控制 APP 的 TMDB 地址或凭据。
