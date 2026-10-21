# TMDB 详情加载与渲染速度分析与优化

- 任务 ID：`TMDB-DETAIL-PERF-01`
- 范围：原生 TMDB 详情页（`TmdbDetailActivity`）与它依赖的 `TmdbService` / `TmdbImageSelector`
- 状态：分析完成；W3（消歧瘦身）、W4（图片档位）已实施并验证；W1（站点详情 ∥ TMDB 详情）因与 C16 契约冲突待用户裁决；W2/W5 见 §7
- 基线：`dev4 @ 3964eeae7ccf4f10f5a2e3696a2909c7a92b3952`

## 1. 结论：能优化，收益集中在两处可量化项

| 项 | 现状 | 实测代价 | 优化后 | 结论 |
|---|---|---|---|---|
| 自动匹配消歧 | 串行拉取最多 6 个候选的**完整** detail（含 images/translations/external_ids/content_ratings/recommendations/similar） | TV 样例 `446,404 B`/次；电影样例 `192,107 B`/次 | 只取打分真正用到的 `credits`（TV 另加 `aggregate_credits`）：TV `335,387 B`（−25%）、电影 `56,056 B`（−71%） | **已优化** |
| 详情页图片 | 详情页把所有 TMDB 图片强制升到 `/original/`（hero、轮播、海报/图集 rail） | poster original `1,187,694 B` vs w500 `85,680 B`（−93%）；backdrop original `982,744 B` vs w1280 `121,009 B`（−88%） | rail 用 w500/w780；轮播在 ≤1080p 屏用 w1280 并限制解码尺寸；hero 与全屏查看/保存仍用 original | **已优化** |
| 首屏串行等待 | `站点详情 → TMDB 详情 → 分季 → 绑定` 全部串行 | 两次网络 RTT + 一次分季 RTT | 站点详情 ∥ TMDB 详情可省 1 个 RTT，但与 C16「源内嵌完整 payload 时 TMDB 请求数为 0」冲突 | **待裁决**（§6） |
| 共享线程池阻塞 | 协调任务占用全局 5 线程池一个线程并阻塞在 `tmdbFuture.get()` | 并发打开详情/首页加载时排队 | 改回调续接或换池 | 暂缓（§7） |
| 主线程重复渲染 | 一轮 apply 内重复清理/重复渲染/多次 `bindTmdbSection` | 低端 TV 上屏帧偏重 | 去重 + 签名短路 | 暂缓（§7，需先量化） |

## 2. 研究门记录（AGENTS.md §7/§8）

| 来源 | 版本/访问 | 支持的事实 | 决策影响 |
|---|---|---|---|
| `https://developer.themoviedb.org/docs/append-to-response` | 2026-10-21 | `append_to_response` 只是把子请求拼进**同一个** HTTP 响应，不产生额外请求；`include_image_language` 影响 images 过滤 | 消歧可以用同一接口换参数瘦身，不需要新增请求类型；缩字段不会改变请求语义 |
| `https://developer.themoviedb.org/docs/image-basics` | 2026-10-21 | 图片 URL = `base_url + file_size + file_path`，如 `/t/p/w500/...` | 档位替换只需替换 `/t/p/<size>/` 一段 |
| `https://developer.themoviedb.org/openapi/tmdb-api.json`（官方 OAS） | 2026-10-21 | `poster_sizes=[w92,w154,w185,w342,w500,w780,original]`、`backdrop_sizes=[w300,w780,w1280,original]`、`still_sizes=[w92,w185,w300,original]`、`profile_sizes=[w45,w185,h632,original]` | 只使用官方声明过的档位，避免自造尺寸 |
| `https://bumptech.github.io/glide/javadocs/.../BaseRequestOptions.html` | 2026-10-21 | `override(width,height)` 覆盖 Target 尺寸（即按该尺寸解码） | 轮播/背景可限制解码尺寸而不改 URL 质量 |
| 仓库 `docs/tmdb-crawler-parallel-prefetch-design.md` | 已实现 | `TmdbDetailPrefetch` 单槽 + 身份 + 取消模式已用于 `TmdbUIAdapter`（VideoActivity 路径） | 仓库内已有并行预取先例；但 `TmdbDetailActivity` 未接入 |
| 仓库 `docs/C16-tmdb-source-detail-contract.md` | 已实现 | §6.1/§19：首屏只要求 `core/credits/images`；**完整 payload 打开详情时 TMDB 请求数为 0**；TV 选季后才要求 `season:N` | 决定了 W1 的取舍边界（见 §6） |

## 3. 现状关键路径（代码证据）

冷启动进入详情（identity 已知，TV，单遍独立模式）：

```text
loadContent (TmdbDetailActivity.java:2428, 在 Task.executor() 的 5 线程池内)
  ├─ SiteApi.detailContent(key, id)                    :2446   ← 站点网络
  ├─ 解析 source payload / 运行时策略                    :2474-2496
  ├─ detailTasks.submitCallable(largeExecutor, loadTmdbResult) :2537
  ├─ tmdbFuture.get()  ← 阻塞当前共享池线程               :2555
  │     └─ loadTmdbBundle → TmdbService.detail()        :2906 / TmdbService:119-165  ← TMDB 网络（单个 append 请求）
  ├─ preloadInitialStandaloneSeason → TmdbService.season :2589 / :2625                ← 又一次网络（分季）
  └─ applyLoaded（UI 线程绑定/渲染）                      :2580-2610
```

自动匹配未命中时（无缓存匹配、无 identity）：

```text
loadTmdbResult :2837
  └─ searchResolvedTmdbMatch → 搜索
       └─ chooseTmdbBundle :12523 / chooseSmartTmdbBundle :12607
            依次 loadTmdbBundle(candidate) 最多 6 次 :12541 / :12620   ← 每次都是完整 append
```

图片：

```text
highResTmdbImage :7283 → TmdbImageSelector.originalUrl :82-91（强制 /original/）
  ├─ bindBackdropImage :4007 / showStaticBackdropImage :4025 / loadBackdropImage :4037
  ├─ refreshBackdropSlideshow :4063 → loadNextBackdropSlide :4101（每 10s 一张）
  └─ bindTmdbSection :6424 → posterAdapter / episodePhotoAdapter（rail）
```

## 4. 实测数据

TMDB 真实响应（语言 `zh-CN`，`include_image_language=zh-CN,null`，2026-10-21；样例 `tv/1399`、`movie/11`）：

| 请求 | 字节 |
|---|---|
| TV 完整 append（images,credits,aggregate_credits,translations,external_ids,content_ratings,recommendations,similar） | 446,404 |
| TV 消歧 lite（credits,aggregate_credits） | 335,387（−25%） |
| 电影完整 append（+release_dates,recommendations,similar） | 192,107 |
| 电影消歧 lite（credits） | 56,056（−71%） |

TMDB 图片（同一条目 poster/backdrop）：

| 资源 | 字节 |
|---|---|
| poster `w342` | 43,525 |
| poster `w500` | 85,680 |
| poster `original` | 1,187,694 |
| backdrop `w780` | 48,585 |
| backdrop `w1280` | 121,009 |
| backdrop `original` | 982,744 |

推论：详情页首屏（poster + backdrop）与 rail 图片从 original 降到档位后，图片字节下降约 88%–93%；消歧路径在 TV 上节省 25%、电影上节省 71%，且候选数最多 6 个，字节节省按倍数累积。

## 5. 决策

- 采用「不改动匹配判定输入」的最小改造：消歧用 lite detail，最终赢家再取完整 detail；不改变 `scoreTmdbTitle/Year/MediaType/People`、`TmdbMatchPolicy` 的任何判据。
- 图片只在 rail/轮播降档，hero 与全屏查看/保存仍用 original；档位只使用官方 OAS 声明的尺寸。
- 不做：匹配阈值、源数据优先级、C16 请求顺序契约、线程池容量、新依赖。

## 6. W1（站点 ∥ TMDB）待裁决

现状可省的真实收益：站点详情与 TMDB 详情相互独立，可重叠 1 个 RTT（约 200ms–1.5s，取决于站点与网络）。

但 C16 契约规定：源内嵌**完整** TMDB payload（T3/T4）时，打开详情 **TMDB 请求数为 0**；该判断只能等站点详情返回、解析 `Vod.getTmdb()` 之后才能做出，而那时请求已经发出。因此：

- 若允许为「已知 identity 的页面」提前发 1 次 TMDB 详情请求（T3/T4 源会多 1 次请求，但结果会被丢弃/不应用），则 W1 可实施；
- 若必须保持 0 请求保证，则 W1 只能在「站点详情返回后」开始，无法获得重叠收益。

本任务默认**保持 C16 契约不变**，W1 暂不实施，等待明确选择。

## 7. W2/W5 暂缓理由

- W2（不阻塞共享 5 线程池）：需要把 `loadContent` 的 `Future.get()` 改为回调续接，涉及 180 行主流程与代际/凭据校验位置，属结构性改动；收益是并发场景下的排队改善，不是单页首屏时延，先不做。
- W5（主线程去重）：需先在设备上量化「loading 消失那一帧」的 UI 线程耗时，避免无证据优化；待补设备基线后再决定。

## 8. 实施结果（W3 + W4）

### 8.1 W3 消歧瘦身 + 有界并行

- `TmdbService`
  - 新增 `detailForDisambiguation(item, config)`：`append_to_response` 只取 `credits,aggregate_credits`（TV）/`credits`（movie），走独立缓存类型 `detail-lite` 与独立键 `disambiguationCacheKey`。
  - `detailUrl(...)` 增加 `disambiguation` 重载；`disambiguationAppend(...)` 明确排除 images/translations/external_ids/content_ratings/release_dates/recommendations/similar。
  - 保留 `aggregate_credits` 的原因：`cast()` 优先读它，去掉会改变人物打分输入。
- `TmdbDetailActivity`
  - `TmdbCandidate` 由持有完整 `TmdbBundle` 改为持有 lite `JsonObject detail`。
  - 新增 `fetchDisambiguationDetails(...)`：按候选原顺序、每次最多 `DISAMBIGUATION_CONCURRENCY = 3` 个在途请求并行拉取 lite 详情；**打分仍按原顺序串行执行**，因此排序/分差/阈值判定与改造前一致。
  - 新增 `loadDisambiguationWinner(...)`：赢家才回到完整 `loadTmdbBundle(...)`（多命中一次 7 天文件缓存时不产生网络请求）。
  - `chooseTmdbBundle`、`chooseSmartTmdbBundle` 均改为 lite 打分 + 赢家升级；请求数从「N 次完整」变为「N 次 lite + 1 次完整」，字节数与墙钟时间见 §4。

### 8.2 W4 图片档位

- `TmdbImageSelector` 新增 `tierUrl/tierUrl(url,size)`、`posterTier`（w500）、`photoTier`（w780）、`heroPosterTier`（w780）；只替换官方 OAS 声明的档位，非 `/t/p/` 地址原样返回。
- `TmdbDetailActivity` 新增 `setRailPosters/setRailPhotos`，图集 rail、海报 rail、季剧照 rail 全部走小档位（`applyTmdbBundle`、`loadTmdbMediaBlocks`、`bindSeasonTmdbMedia` 三处）。
- hero 海报改用 `heroPosterTier`（w780）；hero 大背景、背景轮播、全屏查看、保存图片**仍是 original**（`highResTmdbImage` 未改）。

### 8.3 改动文件

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/fongmi/android/tv/service/TmdbService.java` | lite 详情入口、append 策略、缓存键 |
| `app/src/main/java/com/fongmi/android/tv/ui/activity/TmdbDetailActivity.java` | 消歧并行 + 赢家升级、rail 档位、hero 海报档位 |
| `app/src/main/java/com/fongmi/android/tv/utils/TmdbImageSelector.java` | 档位工具 |
| `app/src/test/java/.../service/TmdbDisambiguationDetailParityTest.java` | lite 与完整详情打分输入等价性 |
| `app/src/test/java/.../service/TmdbServiceCacheKeyTest.java` | lite 缓存键隔离 |
| `app/src/test/java/.../ui/activity/TmdbDetailDisambiguationPerfSourceTest.java` | 并行/lite/档位的源码级契约 |
| `app/src/test/java/.../utils/TmdbImageSelectorTierTest.java` | 档位替换与可回退性 |

## 9. 验证证据

执行时间 2026-10-21，设备和命令：

```text
bash gradlew :app:assembleMobileArm64_v8aDebug \
             :app:compileLeanbackArm64_v8aDebugJavaWithJavac \
             :app:testMobileArm64_v8aDebugUnitTest \
  --tests 'com.fongmi.android.tv.service.Tmdb*' \
  --tests 'com.fongmi.android.tv.utils.Tmdb*' \
  --tests 'com.fongmi.android.tv.ui.activity.TmdbDetail*' \
  --tests 'com.fongmi.android.tv.ui.helper.Tmdb*' \
  --tests 'com.fongmi.android.tv.bean.Tmdb*' \
  --tests 'com.fongmi.android.tv.setting.SettingTmdb*'
→ BUILD SUCCESSFUL；57 个测试类 516 例，0 失败 0 错误；APK app-mobile-arm64_v8a-debug.apk 205,522,144B
```

新增测试（9 例）：

- `TmdbDisambiguationDetailParityTest`（2）：lite 与完整详情的 `cast()` 输出逐条相同、`splitSeasonDetailScore` 对 4 组源文本取值相同；lite append 只保留 `credits`/`aggregate_credits` 且不含被剥离块。
- `TmdbImageSelectorTierTest`（4）：档位替换、未知域名/空值原样保留、代理包装 URL 同样可换档、`originalUrl(tier(x)) == x`。
- `TmdbServiceCacheKeyTest#disambiguationCacheKeyIsIsolatedFromDetailCaches`（1）：lite 键与 full/core 键不同、语言与媒体类型仍分开、API base/Key 不影响。
- `TmdbDetailDisambiguationPerfSourceTest`（2）：消歧走 lite + 有界并发 + 赢家升级；rail 走小档位而 hero/viewer/保存保持 original。

### 9.1 未验证项（如实记录）

- **未做设备端端到端计时**：当前配置好的模拟器（`emulator-5554`，`debug_log=true`、TMDB 已配置、`detail_open_mode=1`）里没有可复现的视频 `key/id`（历史库只有音频条目），无法在不改动用户设备配置的前提下构造一次真实详情加载。字节收益来自对真实 TMDB 响应的直接测量（§4），墙钟收益的端到端数字待有可复现条目后补测。
- 复现方式（供补测）：`adb shell am start -n com.silent.android.webhtv/com.fongmi.android.tv.ui.activity.TmdbDetailActivity --es key <站点key> --es id <vodId> --es name "<片名>" --ei tmdb_id <id> --es tmdb_media_type tv --ei detail_mode 1`，抓 `tmdb-detail-flow` 与 `tmdb` 两个 tag 的日志。
- 4K 屏下 hero 海报由 original 降到 w780：hero 显示尺寸远小于原图，按显示尺寸解码后无可见差异；若后续有 4K 实机反馈明显变软，只需把 `heroPosterTier` 改回 `originalUrl`（单点回退）。

## 10. 后续（需用户裁决）

1. **W1 站点详情 ∥ TMDB 详情**：可再省 1 个 RTT，但会为 T3/T4 源多发 1 次 TMDB 请求、破坏 C16「完整 payload 请求数为 0」的保证。需明确取舍后才能实施。
2. **W2 共享线程池阻塞**：把 `loadContent` 的 `Future.get()` 换成回调续接，属结构性改动，收益在并发场景。
3. **W5 主线程渲染去重**：需先补设备端「loading 消失那一帧」的 UI 线程耗时基线。

## 11. 回滚

W3、W4 互不依赖：W3 回滚 = 还原 `TmdbService` 的 lite 入口与 `TmdbCandidate` 结构；W4 回滚 = 去掉 `setRail*` 档位替换与 `heroPosterTier`。二者均不触碰匹配阈值、源数据优先级与 C16 请求顺序。
