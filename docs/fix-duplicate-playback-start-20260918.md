# 播放器重复启播修复

## 根因

第一轮修复解决了 TV 端重复取址请求的竞态，但 `192.168.50.3:5559` 实测表明问题仍存在于更早的播放阶段：同一条 `PlaybackTrace` 只有一次 `startPlayer`，首帧后约 0.6 秒就启动了 `PreCache` 后台 HLS 预加载，前台随后连续进入两次 `BUFFERING`，累计等待约 4 秒至 13 秒后才稳定。也就是说，用户看到的“反复加载”是前台播放与后台预取争抢同一远端流，而不是重复 `playerContent`。

## 修复

在 TV 端每次开始新的 `playerContent` 请求前调用 `mViewModel.cancelPlayerContent()`，使同一时刻只有最新取址请求可以继续。保留现有请求代次校验和已应用结果去重作为第二道防线。

同时让 Exo 预加载在首帧或恢复后等待 5 秒的稳定播放窗口（正在播放且不再 loading）再启动；遇到 buffering 或 seek 时重新计时。这样保留用户的预加载设置，但优先保证前台启播和恢复，不让后台任务在最脆弱的阶段抢占带宽。

## 验证计划

1. 运行 `ReaderPlaybackRoutingSourceTest`，确认移动端和 TV 端都在新请求前取消旧请求。
2. 编译 TV debug APK 并安装至 `192.168.50.3:5559`。
3. 清空 logcat 后复现一次播放，确认一笔用户启播只产生一条 `startPlayer dispatch`，并进入持续播放态。
4. 额外确认 `playback-buffer` 在首帧后的稳定窗口内不再被后台预加载触发，设备上不出现连续的首播恢复加载。
