# Changelog

## 0.9.1 — 2026-08-06

- 将 minSdk 从 24 (Android 7.0) 降至 23 (Android 6.0)，扩大设备兼容范围。
- 新增 armeabi-v7a ABI 支持：为全部 7 个原生库提供 32 位 ARM 版本，其中
  `libwebrtc-audio-processing-2.so` 与 `libfastvoice_webrtc_aec3.so` 由源码
  交叉编译并启用 NEON。
- arm64-v8a 的原生库改按 API 23 重新编译（此前为 API 26），否则在 Android 6
  的 64 位设备上会因缺少平台符号而加载失败。
- `build-webrtc-native.sh` 改为按 ABI 参数构建，两个 ABI 共享同一份固定版本
  上游源码，并统一用 `-Wl,--no-undefined` 链接以在构建期暴露缺失符号。
- 更新 ABI 检查逻辑，同时接受 arm64-v8a 和 armeabi-v7a。
- 修复 `AudioFocusRequest` (API 26+) 类引用在 API 23 设备上的潜在类加载风险。

## 0.9.0 — 2026-07-28

- 删除 Android `TextToSpeech` 本地提示音及 `localFallbackPromptEnabled` API；
  客户端的所有可听语音只接受服务端下发音频。
- 删除不再使用的 TTS service manifest query 和本地提示音 KWS 分支。
- 删除未接入音频链路的旧 MIC VAD 门控及其 Silero 模型，并清理只被测试读取或
  全仓无引用的内部状态查询。

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
