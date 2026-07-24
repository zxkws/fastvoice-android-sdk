# Changelog

## 0.7.0 — 2026-07-24

- 公共 API 只保留 `FastVoiceConfig`、`FastVoiceClient`、通用 session/content 模型和
  单一事件回调。
- 删除未使用的 `FastVoiceClient.Builder`、`FastVoiceListenerAdapter` 和协议辅助
  包装；Java 使用 `FastVoiceConfig.builder(...)` 后直接构造 client。
- 客户端只发送 Bearer token；token 必须为 1–4096 个非空白字符。
- 保留端侧 sherpa KWS、MIC 录音、Opus、物理播放终态、打断和断线恢复。
