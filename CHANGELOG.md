# Changelog

## 0.13.0 — 2026-09-01

- 破坏性变更：`FastVoiceConfig` 的 endpoint 改为必填，删除内置地址、空白回退、
  `DEFAULT_ENDPOINT`、`resolveEndpoint()` 和无参 `builder()`。
- 公开 API 使用 `stationName`，wire protocol 使用 `station_name`；不传 `station_id`。

## 0.12.2 — 2026-08-13

- `FastVoiceConfig` 未传 endpoint 或传入空白值时，使用内置地址
  `ws://192.168.105.165:8100/ws`；非空自定义地址仍须使用 `ws://` 或 `wss://`。
- SDK Manifest 默认允许当前内网部署所需的明文 WebSocket；迁移到 WSS 后宿主可以
  在自己的 Manifest 中覆盖为禁止明文流量。
- Sample 支持输入并持久化自定义地址，空值使用内置地址，并提供“恢复默认”按钮。

## 0.12.1 — 2026-08-11

- 新增初始化时配置的 `getLocation` 匿名函数。SDK 在连接就绪和唤醒时调用该函数，
  自行解析经纬度 JSON 并上传；宿主无需手动更新坐标。
- 定位方法返回 `null`、非法结果或抛出异常均不影响语音连接。

## 0.12.0 — 2026-08-11

- 破坏性变更：删除 `DeviceTokenProvider`、`token()`、`tokenProvider()` 和 WebSocket
  `Authorization` 请求头；SDK 只连接由部署方网关保护的 endpoint。
- 新增 `updateCoordinates(latitude, longitude)`。宿主异步定位完成后直接传两个数值，
  SDK 在重连时恢复缓存坐标；定位失败时无需调用。
- `location.update` 支持独立坐标更新，不会因坐标刷新重复触发到站播报。

## 0.11.2 — 2026-08-10

- 修复 `0.11.x` 切换到 location/welcome API 后留下的过期 Sample 和单元测试，恢复
  SDK、Sample、Maven Local 发布链路的完整构建。
- 文档明确 Android 只连接 FastVoice，讯飞 ASR/TTS、MaxKB 和大模型密钥只存在
  服务端；服务端替换 TTS 不改变 SDK API 或音频协议。
- 补充 JitPack/AAR 引入、当前公开 API、位置恢复、原始事件展示和发布流程文档。
- 修正版本号；先前 `0.11.0/0.11.1` 标签因 Gradle 版本仍为 `0.10.0`
  而未生成 GitHub Release。

## 0.11.1 — 2026-08-06

- `location.ack` / `welcome.ack` 允许服务端不回传 park/spot，适配精简确认帧。

## 0.11.0 — 2026-08-06

- 公开 API 从 session/content 替换为 `updateLocation`、`clearLocation` 和
  `playWelcome`。
- 重连后自动恢复最后一次位置上下文。

## 0.10.0 — 2026-08-06

破坏性变更：`FastVoiceConfig` 移除四个配置项，对应行为改为固定。已接入的宿主
需要删除对应的构造参数或 `Builder` 调用。

- 删除 `wakeEnabled`：端侧唤醒是必备能力，`hello` 固定上报 `wake: true`，
  并从 `SessionAudioPolicy.wakeKwsEnabled` 一路移除恒为真的 `wakeRequested`
  参数。
- 删除 `autoReconnect`：断线自动重连是必备能力。
- 删除 `bypassSystemProxy`：SDK 始终使用 `Proxy.NO_PROXY`，不再读取系统代理。
- 删除 `allowInsecureConnection`：`ws://` 与 `wss://` 一律接受，端点是否加密
  由部署方决定。`endpoint` 仍必须是这两种 scheme 之一。

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
