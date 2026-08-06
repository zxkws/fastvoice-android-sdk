# FastVoice Android SDK

FastVoice 是一个面向 Android 的通用实时语音 SDK。它负责设备鉴权、WebSocket
连接、麦克风采集、Opus 上下行、端侧唤醒与控制词、流式播放、打断和重连。

SDK 只理解通用的会话、内容请求和播放生命周期。应用自己的字段放在
`attributes` 中，SDK 会验证并原样发送，不解释业务含义。
SDK 不调用 Android `TextToSpeech`；所有可听语音都来自服务端下发的音频。

## 环境要求

- Android API 23+（Android 6.0）
- `arm64-v8a` 或 `armeabi-v7a`
- `android.permission.RECORD_AUDIO`
- JDK 17（构建 SDK）

`armeabi-v7a` 的原生库要求设备支持 NEON（ARMv7 Android 设备普遍具备）。
NDK 自 r17 起已移除 `armeabi`（ARMv5/v6），因此 SDK 无法提供该 ABI。

## 构建并引入当前 AAR

先构建当前源码：

```shell
./gradlew :fastvoice-sdk:check :fastvoice-sdk:assembleRelease
```

把 `fastvoice-sdk/build/outputs/aar/fastvoice-sdk-release.aar` 复制到应用模块的
`libs/`。独立 AAR 不携带 Maven metadata，因此应用模块还要显式声明运行时依赖：

```kotlin
dependencies {
    implementation(files("libs/fastvoice-sdk-release.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.25")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
}
```

## Kotlin

```kotlin
val config = FastVoiceConfig(
    endpoint = "wss://voice.example.com/ws",
    tokenProvider = DeviceTokenProvider { loadCurrentDeviceToken() },
)

val client = FastVoiceClient(applicationContext, config) { event ->
    when (event) {
        is FastVoiceEvent.StateChanged -> stateView.text = event.state.value
        is FastVoiceEvent.Transcript -> transcriptView.text = event.text
        is FastVoiceEvent.SessionAck -> sessionView.text = event.toString()
        is FastVoiceEvent.ContentAck -> contentView.text = event.toString()
        is FastVoiceEvent.PlaybackFinished -> playbackView.text = event.toString()
        is FastVoiceEvent.PlaybackFailed -> playbackView.text = event.toString()
        is FastVoiceEvent.Error -> errorView.text = event.error.toString()
    }
}

client.start()

client.startSession(
    SessionSnapshot(
        id = "session-001",
        rev = 1,
        attributes = mapOf(
            "locale" to "zh-CN",
            "application_mode" to "guided",
        ),
    ),
)

client.updateSession(
    SessionSnapshot(
        id = "session-001",
        rev = 2,
        attributes = mapOf(
            "locale" to "zh-CN",
            "application_mode" to "self_service",
        ),
    ),
)

client.playContent(
    ContentRequest(
        id = "content-request-001",
        key = "welcome",
        session = SessionRef("session-001", 2),
        attributes = mapOf("variant" to "short"),
    ),
)

client.playContent(
    ContentRequest(
        id = "content-request-002",
        key = "idle_message",
        session = null,
        attributes = emptyMap(),
    ),
)

client.endSession("session-001", rev = 3, reason = "completed")
```

在宿主不再拥有前台语音时调用 `stop()`；永久释放实例时调用 `close()`。

## Java

```java
FastVoiceConfig config = FastVoiceConfig.builder("wss://voice.example.com/ws")
    .token("short-lived-token")
    .build();

FastVoiceClient client = new FastVoiceClient(
    getApplicationContext(),
    config,
    event -> eventView.setText(event.toString())
);

client.start();

SessionSnapshot snapshot = SessionSnapshot.builder("session-001", 1L)
    .putAttribute("locale", "zh-CN")
    .putAttribute("application_mode", "guided")
    .build();
client.startSession(snapshot);

ContentRequest request = ContentRequest.builder("content-request-001", "welcome")
    .session(SessionRef.of("session-001", 1L))
    .putAttribute("variant", "short")
    .build();
client.playContent(request);

client.endSession("session-001", 2L, "completed");
```

## 设备鉴权

宿主只配置一个不透明 token。SDK 只发送
`Authorization: Bearer <token>`。token 必须为 1–4096 个非空白字符。服务端由 token
解析自己的稳定设备身份，用它完成重连 fencing 和设备状态隔离。token 可以轮换，
但不能把 token 本身当作业务设备 ID。

## 会话语义

`SessionSnapshot` 是完整快照，不是 merge patch：

- `id` 在一次会话生命周期中保持不变。
- `rev` 从 1 开始并严格递增；完全相同的请求可以安全重试。
- `attributes` 支持 JSON 的 null、字符串、布尔值、有限数值、对象和数组。
- SDK 会递归复制并冻结 `attributes`，调用方后续修改原集合不会改变待发送数据。
- SDK 与服务端共同限制最多 16 KiB、512 个节点、4 层嵌套、每个对象或数组
  128 项、单个字符串 2048 个 Unicode code point，并拒绝非法控制字符。
- 只有匹配的 `SessionAck(action="start")` 到达后才开放该连接的录音和唤醒。
- 断线后 SDK 先恢复最新期望快照，再恢复尚未确认的内容请求。
- 服务端拒绝当前操作时，SDK 回滚到最后一次确认的快照。
- `endSession` 立即停止采集和播放，并取消全部仍在等待的内容请求。每个被取消的
  请求都会产生 `scope="sdk"`、`code="content_cancelled_session_end"` 的本地终态。

`startSession`、`updateSession`、`endSession` 和 `playContent` 返回 `true` 只表示
SDK 接受了调用。最终结果以 `SessionAck`、`ContentAck` 或 `Error` 为准。

## 内容请求

`ContentRequest` 包含：

- `id`：幂等请求 ID。
- `key`：由服务端解释的内容键。
- `session`：可选的精确 `SessionRef(id, rev)`。
- `attributes`：服务端解释的扩展字段。

SDK 不根据 `key` 或 `attributes` 推断规则。绑定请求必须引用当前期望会话快照；
未绑定请求不受当前是否存在会话影响。未确认的请求会跨断线重发；相同 `id`
只能对应完全相同的请求。

`ContentAck` 表示服务端接受请求。物理播放结果分别通过
`PlaybackFinished(playbackId, contentId)` 和
`PlaybackFailed(playbackId, contentId, code)` 返回。普通语音回复的
`contentId` 为 `null`。

## 线程与安全

- 回调统一在 Android 主线程触发。
- 一个 `FastVoiceClient` 对应一个前台语音所有权。
- `start()`、`stop()` 幂等，`close()` 永久关闭实例。
- 正式环境使用 `wss://` 和短期设备令牌。
- SDK 的日志、`toString()` 和事件不包含设备令牌。
- 默认不读取系统代理；本机调试可显式配置 `bypassSystemProxy`。

## 固定音频协议

- SDK 始终使用 `AUDIO_SOURCE_MIC`；不依赖设备的
  `VOICE_COMMUNICATION` 或平台 `AcousticEchoCanceler`。
- 麦克风上行：16 kHz、单声道、20 ms Opus。播放期间使用 C++ WebRTC M131
  AEC3 处理后的 MIC，其他时间使用原始 MIC。
- 服务端下行：48 kHz、单声道、20 ms Opus。
- AudioTrack 实际接受的 48 kHz PCM 同步作为 AEC3 回声参考；处理核心固定按
  WebRTC 原生 10 ms 子帧运行。
- 播放期间端侧 KWS 使用放大后的原始 MIC 副本；上行 ASR 与 pre-roll 使用
  AEC3 处理后的音频且不附加该增益，
  控制词仍由服务端 ASR 复核后执行。
- `playback.end` 只表示服务端不再发送帧；只有物理写入成功才会上报
  `playback.finished`。
- 解码、帧长或 AudioTrack 写入异常会上报 `playback.failed`，不会误报成功。

## 开发验证

```bash
./fastvoice-sdk/build-webrtc-native.sh

./gradlew \
  :fastvoice-sdk:testDebugUnitTest \
  :fastvoice-sdk:lintDebug \
  :fastvoice-sdk:assembleRelease \
  :sample:assembleDebug
```

协议字段以服务端仓库的 `PROTOCOL.md` 为唯一标准。
