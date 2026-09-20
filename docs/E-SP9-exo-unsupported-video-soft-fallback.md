# E-SP9：Exo 无硬件解码器时的软解兜底

状态：设备验证通过，待 guard 原子提交与 recovery tag（2026-09-20，Asia/Shanghai）

## 目标与验收

- 修复直播有声音、无画面：当 Exo 处于硬解模式，而平台对当前视频 MIME（本次为 `video/hevc`）没有任何可用硬件解码器时，允许 FFmpeg 软件渲染器接管视频轨。
- 保持硬解优先契约：平台存在候选硬件解码器时，FFmpeg 不得抢轨；音频回退策略不变。
- 验收：目标直播在 5561 上出现可见视频，同时日志显示视频解码器初始化、视频尺寸和首帧；其他已有硬解路径不改变。

## 现场证据

- 用户给出的最后可用发布为 `v5.6.0-beta-202609141608`，目标提交 `7e8fdbd4c2c27c141f89ff2e9a84aaf7f52a6966`；首次失效发布为 `v5.6.0-beta-202609160047`，目标提交 `32640b193f7585353d3120c236b932afa1548165`。
- 两个发布之间的播放器链路差异是 Exo HLS DataSource 包装，入口提交为 `6bc447a14c1334100bd52a3cb5577f4c95a29c4c`；该差异没有修改 `ExoUtil` 的渲染器选择。
- 5561 现场 URL 为 `http://45.192.97.170:8880/play/1.m3u8`，302 后得到咪咕 H.265 主清单；媒体清单为 4 个 `6s` 分片。
- 同一现场日志中，Media3 解析出 `video/hevc`、`hvc1.1.2.L153.80`、`1280x720`，但报告 `supported=NO_UNSUPPORTED_SUBTYPE`；AAC 音频被选中并初始化，链路没有视频解码器初始化、视频尺寸或首帧事件。UI 因此一直停留在 shutter，形成“有声音没画面”。
- 当前 `ExoUtil.getVideoRenderMode()` 在硬解时返回 `EXTENSION_RENDERER_MODE_OFF`，且 `buildVideoRenderers()` 直接返回，不注册任何 FFmpeg 视频渲染器。MediaCodec 没有候选解码器时不会产生可归因的播放错误，音频继续推进，现有失败回退无法触发。

## 最佳实践与方案比较

- Android Media3 官方定制文档允许通过自定义 `RenderersFactory`/renderer 注入扩展解码器；本地 Media3 渲染器选择以 `RendererCapabilities` 为准。
- 本地 `CompatFfmpegVideoRenderer` 已实现“只按候选 MediaCodec 类型让轨，不按精确 Format 支持度让轨”的合同，并通过注入的 `MediaCodecSelector` 检查平台是否拥有该 MIME。它正是为避免 4K HEVC 等级超限时错误抢轨而设计。
- 方案 A（不改）：保留现状。无硬件 HEVC 的设备继续静默黑屏，拒绝。
- 方案 B（硬解模式无条件注册普通 `FfmpegVideoRenderer`）：可恢复画面，但 FFmpeg 会与硬件解码器竞争，改变既有硬解优先和掉帧契约，拒绝。
- 方案 C（窄适配）：仅在硬解模式注册 `CompatFfmpegVideoRenderer(systemDecoderFallbackOnly=true)`，并传入硬件过滤后的同一 selector。只有平台连该 MIME 的候选硬件解码器都没有时，FFmpeg 才接管；存在硬件候选时仍由 MediaCodec 解码。采用此方案。

## 实施边界与回滚

- 只修改 `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java`、定向源码合同测试和本任务文档/索引。
- 不修改 Media3 AAR、nextlib、FFmpeg、MPV、native lock、输出面或音频路由。
- 回滚：撤销本轮 ExoUtil 注册逻辑和测试即可恢复“硬解无候选时保持空白”的旧行为，不涉及二进制资产。

## 验证计划

1. 运行 `CompatFfmpegVideoRendererSourceTest`，覆盖硬解模式注册 platform-yielding FFmpeg fallback 的源码合同。
2. 使用 `bash scripts/build_arm64_debug_install.sh --serial 192.168.50.3:5561` 构建测试包并覆盖安装。
3. 复播现场 URL，确认视频解码器初始化、视频尺寸、首帧和可见画面；确认不是 FFmpeg 抢占已有硬件解码轨道。

## 实施记录

- 2026-09-20：任务 guard `E-SP9-unsupported-video-soft-fallback` 已启动；完成现场证据、方案比较和窄修复设计。
- 实现：`ExoUtil` 在硬解模式注册 `CompatFfmpegVideoRenderer(systemDecoderFallbackOnly=true)`，并传入已经过硬件过滤的 `MediaCodecSelector`；平台有候选时不接管，没有候选时才由 FFmpeg 接管。
- 定向测试：`bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests io.github.anilbeesetti.nextlib.media3ext.ffdecoder.CompatFfmpegVideoRendererSourceTest`，`BUILD SUCCESSFUL`。
- 打包安装：`bash scripts/build_arm64_debug_install.sh --serial 192.168.50.3:5561`，覆盖安装成功；测试 APK SHA-256 为 `a2cc6af0d432556c2ea2b16b1f81e48652a911e7a76a2409944f4453e86a085d`。
- 设备复播：同一 CCTV1 H.265 直播恢复可见画面。日志显示 `decode=hardware` 的请求上下文仍保留，实际 `videoMime=video/hevc`、`videoCodec=hvc1.1.2.L153.80`、`videoSize=1280x720`、`decoder=ffmpegLavc63.3.100-hevc`、`actualDecode=software`、`firstFrameMs=9798`；截图确认画面正常，不再是 shutter 黑屏。
- 风险记录：该模拟器没有 HEVC 硬件候选，只能使用 FFmpeg 软解；高分辨率或高帧率场景仍受 CPU 能力限制。本次修复不改变“平台有候选时仍由硬件解码”的轨道归属合同。
