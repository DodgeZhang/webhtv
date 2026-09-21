# TMDB 可配置代理

WebHTV 在现有 `apiBase` 与图片域名配置之外增加可选 `proxyBase`。未配置时保持原有请求行为；配置后，仅 TMDB API 请求基址切换到代理地址，凭据、查询参数、缓存和图片地址逻辑保持不变。

设计参考 alist-tvbox 的 `TmdbEndpoint`：把访问线路作为独立配置处理，并确保空值或非法值回落到原配置。WebHTV 当前先支持单个代理地址，不引入上游内置 Worker 池，避免固化第三方免费服务。
