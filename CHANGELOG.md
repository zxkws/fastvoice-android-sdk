# Changelog

## Unreleased

## 0.3.0 — 2026-07-22

- 生产录音固定使用 `MIC`，每次创建/重建 `AudioRecord` 都在开始采集前绑定并启用平台 AEC；上行和 KWS 均消费未经端侧门控改写的原始 MIC PCM。
- 麦克风连续读取失败会有界重建，耗尽重试后明确报错并关闭上行，不再永久空转。
- AudioTrack 的短写、零写、负写、暂停/恢复和播放头排空均有明确成功/失败终态；失败不会再伪装成 `playback_finished` 或成功 ACK。
- 播放进度、完成、失败、命令和二进制音频均绑定服务端 generation 与本地 session/epoch，旧连接和旧播放回调不能污染新会话。
- 本地控制词改为 `id/gen` 两阶段裁决和可逆预暂停；拒绝、超时或服务端正式动作会恢复/接管同一代播放。
- 同一控制词的多条发音词典行全部保留，不再按标签覆盖。
- `commands-v1` 的 `state` 仅用于展示，并支持 `turn_error_terminal`、显式宿主 `interrupt` 和 generation-bound `playback_failed`。
- SDK 只接受 Opus、`commands-v1` 和本地控制词服务端裁决，不再保留旧二进制播放、`audio_end`、单数 `wake.word` 或旧 prompt-done 分支。
- 移除未上线的无签名 `updateContext` / `reportArrival` API 及其强类型模型；车辆平台只通过 `sendTrustedMessage(rawJson)` 原样发送已签名消息。

## 0.2.0 — 2026-07-20

- SDK 内部启用 `commands-v1`，宿主 App 无需理解服务端控制协议。
- 支持真实暂停、继续、跳过和最多 60 秒的上一段语音本地重播。
- 支持由服务端意图触发 Android 媒体音量逐级调高或调低。
- 按硬件实际播完时间回执服务端，唤醒与到点提示音结束后再打开麦克风上行。
- 新增 `sendTrustedMessage(rawJson)`，原样转发车辆平台已做 HMAC 签名的上下文和到点消息；SDK 不持有签名密钥。
- 服务端整体 TTS 失败后，默认使用 Android 系统 TTS 播放 `turn_error.prompt`；若服务端音频已开始则不会双播。

## 0.1.0 — 2026-07-20

- 首个公开 Android SDK。
- 封装离线唤醒、16 kHz Opus 上行、48 kHz Opus 播放、连续对话与打断。
- 支持自动重连、主线程事件回调和宿主音频路由恢复。
- 支持带设备鉴权的强类型上下文更新与到点事件。
- 提供 Kotlin/Java API、最小 sample、JitPack 发布和 GitHub Release AAR。
- 当前仅支持 `arm64-v8a`，最低 Android 7.0（API 24）。
