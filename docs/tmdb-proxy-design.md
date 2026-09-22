# TMDB 可配置代理与线路选择

## Recovery anchor

- 目标：为 TMDB API 与图片请求提供清晰、可回退的代理线路选择，并修复配置弹窗提示重叠。
- 验收：TMDB API 与图片线路分开显示；API 和图片都默认官方直连；可分别选择已验证的 itv666 或输入自定义 endpoint；旧配置字段仍兼容；聚焦单测和 Debug 资源/Java 编译通过。
- 当前实现文件：`TmdbProxy.java`、`TmdbConfig.java`、`TmdbSourceDialog.java`、`TmdbConfigTestService.java`、TMDB 配置布局/文案及对应测试。
- 下一步：完成聚焦验证和 Debug 覆盖安装，若通过则用 task guard 原子提交并创建恢复标签。

## 需求与语义决定

截图暴露了两个问题：

1. 代理输入框曾使用 `TextInputLayout` 外层 hint，同时子输入框也有 hint，导致中文提示和占位符重叠；
2. `API 域名`、`图片域名` 与 `TMDB 代理地址` 的职责没有说明，用户无法判断它们是否重复。

本次将 API 与图片线路拆成两个独立的下拉输入框：分别默认显示官方 API 和官方图片地址，可分别选择已验证的 itv666 线路或输入自定义 endpoint。旧配置中的 `apiBase`、`imageBase` 和历史 `proxyBase` 仍保留兼容。

本次明确采用 **TMDB endpoint/mirror base** 语义，而不是 GitHub 那种把完整原始 URL 直接拼在通用前缀后的语义：

- 代理端点接收 `/3/...` API 路径和 `/t/p/...` 图片路径；
- API 线路只影响 TMDB API `/3/...`；图片线路只影响 `/t/p/...` 图片；
- 当前已验证并保留的内置线路只有 `itv666`：API 与图片均使用 `http://tmdb.itv666.cc`；
- Worker 轮询池和 NAStool 已从 UI 和新配置入口移除，因为本次实测分别无法建立 TLS 连接、返回 HTML/404，不能视为可用 TMDB 线路；
- API 与图片可以一边直连、一边使用代理，或分别输入两个自定义 endpoint。

## 上游参考证据

- 仓库：`https://github.com/power721/alist-tvbox`
- 读取版本：commit `8e6c5a9c8c5d790b234d2f93e3067c22ec136da2`，访问日期 2026-09-22。
- 主要实现：`src/main/java/cn/har01d/alist_tvbox/service/TmdbEndpoint.java`。
- 主要 UI：`web-ui/src/views/ConfigView.vue`，其中提供 `worker-pool`、`http://tmdb.itv666.cc`、`https://tmdb.nastool.org` 预设。
- 本次对这些候选做了真实请求探测：Worker 池各地址均出现 TLS connect EOF；NAStool API 返回 HTML、图片返回 404；itv666 API 返回有效 JSON、图片返回有效 PNG。因此 WebHTV 只保留 itv666，并把 API/图片线路拆开，避免一个失效镜像同时影响两类请求。

证据等级：高（直接读取上游实现和测试，而非搜索摘要）。适用性：WebHTV 的 TMDB 请求同样集中在 `TmdbConfig` 基址和图片基址，能够采用同样的 endpoint 路径契约。注意：第三方免费线路的可用性、速度、额度和隐私不由 WebHTV 保证。

## 方案比较

| 方案 | 结果 |
| --- | --- |
| 不改现状 | API/图片自定义域名继续存在，但国内网络下用户仍需手工寻找可用线路；无法对应截图中的预设选择需求。 |
| GitHub 式通用前缀 | 只有当代理服务保证“完整原始 URL 作为后缀”时成立；TMDB Worker、itv666、NAStool 使用的是 endpoint 路径契约，直接拼接会出现错误路径或无法处理图片。 |
| 直接把代理覆盖成 API 域名 | 能工作，但与已有 API 域名字段重复，且无法表达 NAStool 的独立图床。 |
| **本项目选择：API/图片双线路 + 实测内置 itv666** | 借鉴 alist-tvbox 的 endpoint 思路，但不照搬未经验证的 Worker/NAStool；API 与图片独立选择，保留自定义地址和历史字段兼容。 |

## 实现约束

- 内置值：官方 API 直连、官方图片直连、itv666 API、itv666 图片；Worker 轮询池和 NAStool 不再进入新配置。
- 自定义线路接受一个 `http(s)` endpoint；为兼容旧配置，若输入包含多个候选只取第一个有效地址。
- 代理地址只允许 HTTP/HTTPS，无用户信息、查询串或 fragment；非法项丢弃，全部非法时回退直连。
- 配置对象在首次解析时固定一个已解析线路，避免同一请求链因重复 getter 改变 host；配置变更后重新解析。
- API 自定义 endpoint 只影响 API；图片自定义 endpoint 只影响图片；两者可独立配置。
- 临时订阅凭据仍强制使用官方 HTTPS API，并清除代理，保持既有凭据安全边界。
- UI 显示两个独立的 TMDB API/图片下拉输入控件；独立标签与控件 hint 不重复，避免 Android 9 上的文字重叠。

## 验收与回滚

验收至少包括：

1. XML 中 `ScrollView` 只有一个直接子节点，TMDB API/图片各有一个下拉输入控件且不再使用会重叠的双 hint 结构；
2. 内置官方直连与 itv666 API/图片线路能够互转；单个自定义 endpoint 能够归一化；Worker/NAStool 历史值会被识别为无效并回退直连；
3. API 线路请求使用 `/3`，图片线路请求使用 `/t/p/w342`；两者可分别验证；
4. 原有 TMDB 配置和未配置代理的聚焦测试继续通过；
5. `:app:processMobileArm64_v8aDebugResources` 和聚焦 `testMobileArm64_v8aDebugUnitTest` 通过。

回滚方式：回滚本任务原子提交即可；旧配置中的 `apiBase`、`imageBase` 字段保持兼容，删除 `proxyBase` 后立即恢复旧线路。

## 无效线路移除与 API/图片拆分（2026-09-22）

根据真实探测结果，已将线路入口恢复为 API 和图片两个独立字段：

- API 默认 `https://api.tmdb.org`，图片默认 `https://image.tmdb.org`；
- 两个字段分别支持官方直连、已验证的 `itv666` 和自定义 endpoint；
- Worker 轮询池不再提供，NAStool 不再提供；旧配置中的这两类历史值在归一化时会被丢弃；
- 旧版 `proxyBase` 的 itv666 配置仍可读取，下一次保存会转换为独立 `apiBase`/`imageBase`。

## 实施与验证记录（2026-09-22）

- 已将 TMDB 控件拆为独立的 `apiHostInput` 与 `imageHostInput` 下拉输入框；移除无效 Worker/NAStool 线路，不使用外层 label hint 与子控件 hint，解决截图中的文字重叠。
- 已保留内置线路：官方 API/图片直连、itv666 API/图片；支持自定义 endpoint。代理采用 endpoint 语义，不拼接完整 TMDB URL 前缀。
- 已验证 itv666 API 路径 `/3/...` 返回 200 有效 JSON、图片路径 `/t/p/...` 返回 200 有效 PNG；Worker 池各地址 TLS connect EOF，NAStool API 返回 HTML 且图片 404，因此两者已移除；临时订阅凭据保持官方 API 安全边界。
- 布局结构检查通过：`ScrollView` 只有一个直接子节点，`apiHostInput` 与 `imageHostInput` 各出现一次，`proxyHostInput` 不再存在。
- 聚焦测试通过：`TmdbProxyTest`、`TmdbConfigImageHostTest`、`TmdbConfigEffectiveTest`、`TmdbConfigTestServiceTest`、`TmdbSourceDialogInflationContractTest`；最终 `BUILD SUCCESSFUL`。
- 已使用 `scripts/build_arm64_debug_install.sh --flavor mobile --abi arm64-v8a --serial 192.168.50.3:5557` 构建 Debug 包；一次脚本守护进程在构建后被外部停止，随后使用同一 APK 通过 `adb install -r` 覆盖安装到 192.168.50.3:5557；运行时确认 API/图片为两个独立线路输入，并实际打开下拉查看线路；未卸载已有包，未生成正式包。
- 已停止 Gradle 守护进程并清理临时截图、XML、上游临时仓库；当前状态：待本任务原子提交和恢复标签。
