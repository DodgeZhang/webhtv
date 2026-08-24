# E-SP1：Exo 首帧立即可见

- 任务 ID：`E-SP1`
- 类别：Exo 性能专项
- 唯一文档：`docs/E-SP1-exo-first-frame-visible.md`
- 状态：已完成并提交。
- 下一动作：无；后续真实首帧耗时优化归 `E-SP2`，不得继续扩展本任务。

## 目标与边界

当 Exo 已触发 `onRenderedFirstFrame()`、但完整 `STATE_READY` 仍因音频或其他轨道初始化而延后时，立即解除视频 shutter 和已有启动遮罩，让已渲染首帧可见。

本任务只修正 UI 可见时机，不承诺降低 `stage=first-frame` 的实际耗时，也不修改 `STATE_READY`、`PlaybackStartupPolicy`、缓冲参数、解码器选择、DV7→P8.1/HDR10 fallback、TrueHD、seek、Range/cache、软解降载或 MPV 输出策略。

## 实现

- `PlayerManager` 在 Exo `onRenderedFirstFrame()` 后发出 Exo 专用首帧通知；原有 trace、超时取消和 telemetry 保持不变。
- `PlaybackService` 只转发该通知，MPV/native 不进入此路径。
- `PlaybackActivity` 只对当前 owner 隐藏 `exo_shutter` 和 `R.id.progress` 启动遮罩。

## 验证

- `bash ./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac`
- 结果：`BUILD SUCCESSFUL`。
- `git diff --check` 通过。

验证边界：编译证明通知链可构建，不等同于设备首帧耗时、音频初始化或后续缓冲性能结论。

## 提交、Tag 与回滚

- 实施提交：`c07e2b27eddbbee3240ed25fd6e2c8e5a64c5c7e`
- Recovery tag：`recovery/exo-sp1-first-frame-visible-20260822/20260822224344-c07e2b27eddb`
- 回滚：恢复该原子提交即可；不涉及依赖锁、AAR 或 native 二进制。

## 与 E-SP2 的关系

`E-SP1` 只解决“首帧已产生但仍被遮挡”的体感问题。远程大 MKV 在首帧产生前被尾部 Cues 读取阻塞的问题属于 [`E-SP2`](E-SP2-exo-remote-mkv-deferred-cues.md)，两者保持独立提交和回滚边界。
