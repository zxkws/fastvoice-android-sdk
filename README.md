# FastVoice Android SDK

FastVoice 是面向 Android 的实时语音 SDK。负责 WebSocket 连接、麦克风
采集、Opus 编解码、端侧唤醒词、流式播放、打断和断线重连。

SDK 当前版本为 `0.12.0`。SDK 不调用 Android `TextToSpeech`；所有可听语音
都来自服务端。ASR、TTS、MaxKB 和大模型均是服务端实现细节，Android 不保存
上游密钥，也不需要因服务端替换语音供应商而改代码。

## 环境要求

- Android API 23+（Android 6.0）
- `arm64-v8a` 或 `armeabi-v7a`
- `android.permission.RECORD_AUDIO`
- JDK 17（构建 SDK）

## 引入 AAR

```shell
./gradlew :fastvoice-sdk:check :fastvoice-sdk:assembleRelease
```

将 `fastvoice-sdk/build/outputs/aar/fastvoice-sdk-release.aar` 复制到应用模块
`libs/`，再声明运行时依赖：

```kotlin
dependencies {
    implementation(files("libs/fastvoice-sdk-release.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.25")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
}
```

也可以通过 JitPack 使用发布标签：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.zxkws:fastvoice-android-sdk:0.12.0")
}
```

## 快速集成（Kotlin）

```kotlin
// 1. 创建客户端，只需要 endpoint
val client = FastVoiceClient(
    applicationContext,
    FastVoiceConfig(
        endpoint = "wss://voice.example.com/ws",
    ),
) { event ->
    when (event) {
        is FastVoiceEvent.StateChanged -> updateState(event.state.value)
        is FastVoiceEvent.Transcript  -> showTranscript(event.text)
        is FastVoiceEvent.LocationAck -> log("位置已确认")
        is FastVoiceEvent.WelcomeAck  -> log("欢迎词已确认")
        is FastVoiceEvent.PlaybackFinished -> log("播放完成")
        is FastVoiceEvent.PlaybackFailed   -> log("播放失败: ${event.code}")
        is FastVoiceEvent.Error       -> showError(event.error)
    }
}

// 2. 启动 — 连上就能唤醒说话
client.start()

// 宿主异步定位完成后，可选上报天气坐标
hostLocationService.getLocationAsync { json ->
    client.updateCoordinates(
        json.getDouble("latitude"),
        json.getDouble("longitude"),
    )
}

// 3. 上报位置 — 服务端自动播到站介绍
client.updateLocation(park = "南苑森林湿地公园", spot = "北一门")

// 4. 到下一站
client.updateLocation(park = "南苑森林湿地公园", spot = "1907站")

// 5. 播放欢迎词（主动触发）
client.playWelcome(park = "南苑森林湿地公园")

// 6. 清除位置 + 对话历史
client.clearLocation()
```

## 快速集成（Java）

```java
FastVoiceConfig config = FastVoiceConfig.builder("wss://voice.example.com/ws")
    .build();

FastVoiceClient client = new FastVoiceClient(
    getApplicationContext(),
    config,
    event -> Log.d("FV", event.toString())
);

client.start();
hostLocationService.getLocationAsync(json -> client.updateCoordinates(
    json.optDouble("latitude"), json.optDouble("longitude")
));
client.updateLocation("南苑森林湿地公园", "北一门");
client.playWelcome("南苑森林湿地公园", null);
client.clearLocation();
```

## 完整 API

| 方法 | 说明 |
|------|------|
| `start(): Boolean` | 启动连接和音频。连接成功后立即可唤醒对话。 |
| `stop()` | 断开连接，停止音频。可重新 `start()`。 |
| `interrupt()` | 打断当前播放并取消服务端当前回合。 |
| `updateCoordinates(latitude, longitude)` | 上报天气等位置服务使用的经纬度。 |
| `updateLocation(park, spot?)` | 上报当前位置。服务端收到后自动播放到站内容（如有）。 |
| `clearLocation()` | 清除位置上下文并重置对话历史。 |
| `playWelcome(park, spot?)` | 请求服务端播放欢迎词。 |
| `close()` | 永久释放实例。 |

`start()`、位置和欢迎词方法返回 `Boolean`：`true` 表示 SDK 接受调用，不代表
服务端已确认。服务端结果通过事件回调返回。

## 事件

| 事件 | 说明 |
|------|------|
| `StateChanged(state)` | 状态变化：sleeping / listening / recognizing / generating / speaking / prompting |
| `Transcript(role, text, final)` | 语音转文字（role=user）或回答文字（role=assistant） |
| `LocationAck` | 服务端确认位置上报 |
| `WelcomeAck` | 服务端确认欢迎词请求 |
| `PlaybackFinished(playbackId, contentId)` | 音频物理播放完成 |
| `PlaybackFailed(playbackId, contentId, code)` | 音频播放失败 |
| `Error(error)` | 错误（含 code、recoverable、fallbackText） |

回调统一在 Android 主线程触发。

## 网关与密钥

SDK 不接收 token，也不会在 WebSocket 握手中发送 `Authorization`。公网入口的访问
控制、WSS 和限流由前置网关负责。

讯飞、MaxKB 和大模型密钥只配置在服务端或网关，不能下发到 Android。

## 经纬度

定位是可选能力。宿主继续负责定位权限及高德、百度、车载定位等实现，SDK 不申请
定位权限。宿主的异步方法完成后，直接传入两个数值：

```kotlin
hostLocationService.getLocationAsync { json ->
    client.updateCoordinates(
        json.getDouble("latitude"),
        json.getDouble("longitude"),
    )
}
```

纬度范围为 `-90..90`，经度范围为 `-180..180`。定位失败时不调用即可，不影响
语音连接。SDK 缓存最后一次坐标并在断线重连后自动恢复。

## 唤醒词机制

SDK 内置 Sherpa-ONNX 端侧关键词检测（KWS），唤醒词由服务端下发，宿主无需配置。

1. **握手**：连接建立后 SDK 自动发送 `{"type":"hello","wake":true}`，告知服务端
   客户端已启用端侧 KWS。
2. **下发**：服务端返回 `{"type":"ready","wake_words":["布丁",...]}`，SDK 将词
   表加载到 Sherpa KWS 引擎。
3. **唤醒确认**：用户说出任一唤醒词 → SDK 检测命中后上报服务端 → 服务端通过
   TTS 回复"在呢"并进入监听状态，等待用户提问。
4. **连续追问**：回答结束后，服务端在追问窗口内继续监听；窗口到期后回到
   `sleeping`，下一次提问需要重新唤醒。时长由服务端配置，SDK 无需感知。
5. **播放期间唤醒**：播放期间 KWS 使用放大后的原始 MIC 副本（绕过 AEC3 抑制），
   确保"停止""换一个"等打断指令不被回声消除吞掉。

宿主 App 不需要管理唤醒词列表或 KWS 引擎——`start()` 之后一切自动就绪。

## LLM 上下文行为

服务端 LLM 的回答质量取决于当前位置上下文：

| 状态 | 行为 |
|------|------|
| 已调用 `updateLocation(park, spot)` | LLM 自动携带该景点的知识库内容作为上下文，回答与当前站点相关 |
| 未上报位置 / 调用 `clearLocation()` 后 | LLM 按通用知识回答，不包含特定景点信息 |
| 切换到新位置（再次 `updateLocation`） | 上下文立即更新为新站点，旧对话历史清除 |

`clearLocation()` 同时清除位置和对话历史。游客下车或订单结束时应调用此方法。

## 协议帧参考

以下是一次完整交互的 wire-level JSON 帧序列。`←` 表示客户端发送，`→` 表示
服务端返回。SDK 已将这些帧封装为上层 API，宿主通常不需要直接处理。

```text
// 连接建立
← {"type":"hello","wake":true}
→ {"type":"ready","connection_id":"c1","wake_words":["布丁"]}

// 位置上报 → 自动播到站介绍
← {"type":"location.update","park":"nanyuan","spot":"北一门","latitude":39.81,"longitude":116.37}
→ {"type":"location.ack"}
→ （服务端自动播报该站点介绍音频）

// 到下一站
← {"type":"location.update","park":"nanyuan","spot":"1907_station"}
→ {"type":"location.ack"}
→ （自动播报）

// 离开（清除位置 + 对话历史）
← {"type":"location.clear"}
→ {"type":"location.ack"}
```

唤醒对话的帧序列：

```text
// 端侧 KWS 检测到唤醒词后 SDK 自动上报
← （音频帧中包含唤醒词）
→ {"type":"state","value":"listening"}
→ （TTS 播报 "在呢"）

// 用户提问（持续上行 Opus 音频帧）
→ {"type":"transcript","role":"user","text":"这里有什么好玩的","final":true}
→ {"type":"transcript","role":"assistant","text":"...","final":true}
→ （48 kHz Opus 音频流）
→ {"type":"state","value":"sleeping"}
```

## 使用场景

### 导览车到站播报

```kotlin
// 到北一门
client.updateLocation("南苑森林湿地公园", "北一门")
// → 服务端自动查知识库，播放北一门介绍
// → 播完后游客可以唤醒提问："布丁，这里有什么好玩的？"

// 到下一站
client.updateLocation("南苑森林湿地公园", "运河广场")
// → 自动播运河广场介绍
```

### 欢迎词

```kotlin
// 游客上车时播欢迎词
client.playWelcome("南苑森林湿地公园")
```

### 纯对话（不上报位置）

```kotlin
// 连上就能说话，不需要上报位置
client.start()
// 用户说唤醒词 → 提问 → 服务端回答
// 没有位置上下文时，服务端按通用知识回答
```

### 结束

```kotlin
// 游客下车，清空上下文
client.clearLocation()
```

## 断线重连

SDK 自动重连。重连后：
- 如果之前上报过位置，自动重发 `location.update`
- 唤醒词立即恢复可用
- 不需要宿主做任何操作

## 线程与安全

- 回调统一在 Android 主线程触发
- 一个 `FastVoiceClient` 对应一个前台语音所有权
- `start()`、`stop()` 幂等，`close()` 永久释放
- 端侧唤醒、断线重连和绕过系统代理是固定行为
- 正式环境使用 `wss://` 和短期设备令牌

## 固定音频协议

- 上行：16 kHz 单声道 20 ms Opus（播放期间使用 WebRTC AEC3 处理后的音频）
- 下行：48 kHz 单声道 20 ms Opus
- 播放期间端侧 KWS 使用放大后的原始 MIC 副本
- AudioTrack 48 kHz PCM 同步作为 AEC3 回声参考

## USB 调试

```bash
adb reverse tcp:8100 tcp:8100
```

Demo 使用 `ws://127.0.0.1:8100/ws`。局域网直连时填 `ws://<服务器IP>:8100/ws`。

## 构建验证

```bash
./fastvoice-sdk/build-webrtc-native.sh

./gradlew \
  :fastvoice-sdk:testDebugUnitTest \
  :fastvoice-sdk:lintDebug \
  :fastvoice-sdk:assembleRelease \
  :sample:assembleDebug
```

发布前还会用 `-PusePublishedSdk` 让 Sample 从 Maven Local 重新解析已发布坐标，
确认不是只有 project dependency 能编译。
