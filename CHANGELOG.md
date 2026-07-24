# Changelog

## 0.5.0 — 2026-07-24

- 公共 API 收敛为通用 `SessionSnapshot`、`SessionRef`、`ContentRequest`，并删除
  `OrderSnapshot`、`startOrder/updateOrder/endOrder`、`playArrival/playCruise`，
  不保留未上线版本的兼容层。
- wire 收敛为 `session.start/update/end`、`session.ack`、`content.play`、
  `content.ack` 和 `playback.start.content_id`；SDK 不解释业务 key 或 attributes。
- 会话完整快照支持正整数 revision、精确幂等、错误回滚和断线恢复；未确认的内容请求
  在恢复 session 后按原始顺序重发，同 ID 异 payload 会被拒绝。
- `endSession` 立即停止音频并取消所有未确认内容；播放事件通过可空 `contentId`
  区分内容请求与普通语音回复。
- attributes 的 JSON 大小、深度、节点数、集合长度、字符串长度和控制字符限制与
  服务端协议一致。
- 保留 0.4 的端侧唤醒、播放期控制词、Opus 上下行、播放终态和录音恢复能力。

## 0.4.0 — 2026-07-24

- SDK 只实现新的 `/ws` 协议：必需设备请求头鉴权、`hello/ready` 握手和固定
  16 kHz 上行、48 kHz 下行 Opus 参数，不兼容旧协议 JSON。
- 新增强类型 `OrderSnapshot`、`startOrder`、`updateOrder`、`endOrder`、
  `playArrival` 和 `playCruise`；订单完整快照支持精确幂等重试和重连恢复。
- 回调统一为 `FastVoiceEvent`，到点/巡游从服务端接收确认，并通过带 `tourId` 的
  `PlaybackFinished` / `PlaybackFailed` 报告物理播放终态。
- `capture.start` 使用最多 1800 ms 的有界预滚，唤醒使用完整录音环，预滚与实时帧
  不重复。
- KWS 在播放和 prompt 期间持续运行，只抑制 WAKE，仍允许 CONTROL；WAKE 不会
  降级为 CONTROL。
- 删除端侧重播/跳过缓存和旧的通用 JSON 发送 API；重播和跳过统一由服务端创建新
  playback ID 与音频流。

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
