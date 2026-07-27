# Changelog

## 0.8.1 — 2026-07-27

- 播放期间端侧 KWS 候选支路改用原始 MIC 并保留 12 dB 增益，避免 AEC3
  抑制近端短命令；ASR 上行和预卷仍使用纯 AEC3 输出。
- “停止/停一下”继续降低端侧候选阈值；候选仍须服务端确认，不直接执行动作。

## 0.8.0 — 2026-07-27

- 删除依赖设备 HAL 的 Android 平台 AEC，统一使用 C++ WebRTC M131 AEC3。
- 将 AudioTrack 实际接受的 48 kHz PCM 作为回声参考；播放期间 KWS、预卷和
  16 kHz Opus 上行统一使用 AEC3 处理后的 MIC。
- 播放期间只对端侧 KWS 副本增加 12 dB，并降低“停止/停一下”的候选阈值；
  FunASR 上行仍保留未经增益的 AEC 输出，由服务端继续完成否定句确认。
- 非播放阶段继续使用原始 MIC，避免软件 AEC 改写正常唤醒与问答录音。
- 原生实现只发布 `arm64-v8a`，与 SDK 已有 sherpa-onnx ABI 范围一致。

## 0.7.0 — 2026-07-24

- 公共 API 只保留 `FastVoiceConfig`、`FastVoiceClient`、通用 session/content 模型和
  单一事件回调。
- 删除未使用的 `FastVoiceClient.Builder`、`FastVoiceListenerAdapter` 和协议辅助
  包装；Java 使用 `FastVoiceConfig.builder(...)` 后直接构造 client。
- 客户端只发送 Bearer token；token 必须为 1–4096 个非空白字符。
- 保留端侧 sherpa KWS、MIC 录音、Opus、物理播放终态、打断和断线恢复。
