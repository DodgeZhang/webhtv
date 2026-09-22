# TMDB 可配置代理与线路选择

## Recovery anchor

- 目标：为 TMDB API 与图片请求提供清晰、可回退的代理线路选择，并修复配置弹窗提示重叠。
- 验收：代理字段不再与提示重叠；可选择内置线路或输入自定义 endpoint；API 与默认图片请求随线路切换；自定义 API/图片域名仍可用；直连行为不变；聚焦单测和 Debug 资源/Java 编译通过。
- 当前实现文件：`TmdbProxy.java`、`TmdbConfig.java`、`TmdbSourceDialog.java`、`TmdbConfigTestService.java`、TMDB 配置布局/文案及对应测试。
- 下一步：完成聚焦验证，若通过则用 task guard 原子提交并创建恢复标签。

## 需求与语义决定

截图暴露了两个问题：

1. 代理输入框曾使用 `TextInputLayout` 外层 hint，同时子输入框也有 hint，导致中文提示和占位符重叠；
2. `API 域名`、`图片域名` 与 `TMDB 代理地址` 的职责没有说明，用户无法判断它们是否重复。

本次明确采用 **TMDB endpoint/mirror base** 语义，而不是 GitHub 那种把完整原始 URL 直接拼在通用前缀后的语义：

- 代理端点接收 `/3/...` API 路径和 `/t/p/...` 图片路径；
- 选中代理后，TMDB API 请求基址和未单独覆盖的默认图片基址切换到该代理；
- `NAStool` 的 API 与图片域名分离，API 使用 `tmdb.nastool.org`，图片使用 `img.nastool.org`；
- 上方 API/图片域名保留为高级直连/自定义项：API 域名在未选代理时生效，显式图片域名即使选中代理也优先，用于兼容已有配置和特殊自建服务；
- 未选代理时，保持现有官方直连和自定义域名行为。

## 上游参考证据

- 仓库：`https://github.com/power721/alist-tvbox`
- 读取版本：commit `8e6c5a9c8c5d790b234d2f93e3067c22ec136da2`，访问日期 2026-09-22。
- 主要实现：`src/main/java/cn/har01d/alist_tvbox/service/TmdbEndpoint.java`。
- 主要 UI：`web-ui/src/views/ConfigView.vue`，其中提供 `worker-pool`、`http://tmdb.itv666.cc`、`https://tmdb.nastool.org` 预设；`NAStool` 单独映射 `https://img.nastool.org`。
- 参考实现还支持多个镜像地址轮询；本项目以 `worker-pool` 哨兵和自定义地址池复用这一思路，避免把全部 Worker 地址写入设置值。

证据等级：高（直接读取上游实现和测试，而非搜索摘要）。适用性：WebHTV 的 TMDB 请求同样集中在 `TmdbConfig` 基址和图片基址，能够采用同样的 endpoint 路径契约。注意：第三方免费线路的可用性、速度、额度和隐私不由 WebHTV 保证。

## 方案比较

| 方案 | 结果 |
| --- | --- |
| 不改现状 | API/图片自定义域名继续存在，但国内网络下用户仍需手工寻找可用线路；无法对应截图中的预设选择需求。 |
| GitHub 式通用前缀 | 只有当代理服务保证“完整原始 URL 作为后缀”时成立；TMDB Worker、itv666、NAStool 使用的是 endpoint 路径契约，直接拼接会出现错误路径或无法处理图片。 |
| 直接把代理覆盖成 API 域名 | 能工作，但与已有 API 域名字段重复，且无法表达 NAStool 的独立图床。 |
| **本项目选择：endpoint 线路 + 高级域名覆盖** | 与 alist-tvbox 的实际实现一致；内置 Worker 轮询池、itv666、NAStool，并支持自定义单地址/地址池；默认图片跟随代理，显式自定义图片域名仍优先。 |

## 实现约束

- 内置值：官方直连、Worker 轮询池、itv666、NAStool；Worker 池使用 `worker-pool` 哨兵，运行时轮询上游内置地址。
- 自定义代理支持逗号、中文逗号、分号或空白分隔的多个 `http(s)` endpoint，归一化后去重并轮询。
- 代理地址只允许 HTTP/HTTPS，无用户信息、查询串或 fragment；非法项丢弃，全部非法时回退直连。
- 配置对象在首次解析时固定一个已解析线路，避免同一请求链因重复 getter 改变 host；配置变更后重新解析。
- API 自定义域名仅在未选代理时生效；只有默认官方图片域名会自动跟随代理，显式自定义图片域名保持原样并优先。
- 临时订阅凭据仍强制使用官方 HTTPS API，并清除代理，保持既有凭据安全边界。
- UI 使用独立标签 + 单个输入控件；不在同一控件叠加 `TextInputLayout` hint 和子控件 hint。

## 验收与回滚

验收至少包括：

1. XML 中 `ScrollView` 只有一个直接子节点，代理控件存在一次且不再使用会重叠的双 hint 结构；
2. 内置线路值与显示标签能够互转；Worker 池和自定义地址池能解析并轮询；NAStool 图片域名映射正确；
3. 代理配置下 API 请求使用 `/3`，默认图片请求使用 `/t/p/w342`；自定义图片域名不被覆盖；
4. 原有 TMDB 配置和未配置代理的聚焦测试继续通过；
5. `:app:processMobileArm64_v8aDebugResources` 和聚焦 `testMobileArm64_v8aDebugUnitTest` 通过。

回滚方式：回滚本任务原子提交即可；旧配置中的 `apiBase`、`imageBase` 字段保持兼容，删除 `proxyBase` 后立即恢复旧线路。

## 实施与验证记录（2026-09-22）

- 已将代理控件改为独立标签 + 下拉选择框；代理控件不再同时使用外层 label hint 和子控件 hint，解决截图中的文字重叠。
- 已增加内置线路：官方直连、Worker 轮询池、itv666、NAStool；支持自定义 endpoint/地址池。代理采用 endpoint 语义，不拼接完整 TMDB URL 前缀。
- 已验证 API 代理路径为 `/3/...`，默认图片随线路使用 `/t/p/...`；NAStool 图片映射到 `img.nastool.org`；显式自定义图片域名覆盖代理；临时订阅凭据清除代理并保持官方 API 安全边界。
- 布局结构检查通过：`ScrollView` 只有一个直接子节点，`proxyHostInput` 仅出现一次。
- 聚焦测试通过：`TmdbProxyTest`、`TmdbConfigImageHostTest`、`TmdbConfigEffectiveTest`、`TmdbConfigTestServiceTest`、`TmdbSourceDialogInflationContractTest`；最终 `BUILD SUCCESSFUL`。
- 已使用 `scripts/build_arm64_debug_install.sh --flavor mobile --abi arm64-v8a --serial 192.168.50.3:5557` 覆盖安装 Debug 包并进入 TMDB 配置弹窗检查；未卸载已有包，未生成正式包。
- 已停止 Gradle 守护进程并清理临时截图、XML、上游临时仓库；当前状态：待原子提交和恢复标签。
