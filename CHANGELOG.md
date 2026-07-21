# Changelog

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
