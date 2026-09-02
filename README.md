# FastVoice Android SDK

FastVoice 是面向 Android 的实时语音 SDK。负责 WebSocket 连接、麦克风
采集、Opus 编解码、端侧唤醒词、流式播放、打断和断线重连。

SDK 当前版本为 `0.14.0`。SDK 不调用 Android `TextToSpeech`；所有可听语音
都来自服务端。ASR、TTS、MaxKB 和大模型均是服务端实现细节，Android 不保存
上游密钥，也不需要因服务端替换语音供应商而改代码。

## 环境要求

- Android API 23+（Android 6.0）
- `arm64-v8a` 或 `armeabi-v7a`
- `android.permission.RECORD_AUDIO`
- JDK 17（构建 SDK）

## 通过 Maven Central 引入

FastVoice Android SDK 仅通过 Maven Central 提供官方客户端接入。宿主项目通常已经
包含 `mavenCentral()`，无需增加额外仓库。

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("io.github.zxkws:fastvoice-android-sdk:0.14.0")
}
```

## 快速集成（Kotlin）

```kotlin
// 1. endpoint 和 areaId 都由宿主业务提供，不能为空
val endpoint = requireNotNull(savedEndpointOrUserInput)
val client = FastVoiceClient(
    applicationContext,
    FastVoiceConfig(
        endpoint = endpoint,
        areaId = "18", // 必填：本订单固定区域 ID
        getLocation = { callback ->
            hostLocationService.getLocationAsync(callback)
        },
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

// 2. 启动 — hello 一次性上报 area_id，ready 后本地唤醒直接可用
client.start()

// 3. 上报位置 — 服务端自动播到站介绍
client.updateLocation("藻园门站-靠近西苑地铁")

// 4. 到下一站
client.updateLocation("1907站")

// 5. 播放欢迎词（主动触发）
client.playWelcome()

// 6. 清除站点/坐标 + 对话历史；本 Session 的 area_id 仍保留
client.clearLocation()
```

## 快速集成（Java）

```java
FastVoiceConfig config = FastVoiceConfig.builder("ws://voice.example:8100/ws")
    .areaId("18")
    .getLocation(callback ->
        hostLocationService.getLocationAsync(callback::invoke)
    )
    .build();

FastVoiceClient client = new FastVoiceClient(
    getApplicationContext(),
    config,
    event -> Log.d("FV", event.toString())
);

client.start();
client.updateLocation("北一门");
client.playWelcome(null);
client.clearLocation();
```

## 服务地址

`FastVoiceConfig` 要求宿主明确提供服务地址：

```kotlin
val config = FastVoiceConfig(
    endpoint = userInput,
    areaId = "18",
)
```

- endpoint 必填且不能为空；SDK 不提供内置地址或失败回退地址。
- 输入去除首尾空格后原样使用，并且必须以 `ws://` 或 `wss://` 开头。
- 宿主可自行用 SharedPreferences 或 DataStore 保存输入；Sample 包含输入、保存和清除。

当前 SDK Manifest 默认允许局域网 `ws://` 明文连接。如果宿主 Manifest 明确设置了
`android:usesCleartextTraffic="false"`，需要改为 `true`；以后切换到 `wss://` 后可以
再关闭明文流量。

## 完整 API

| 方法 | 说明 |
|------|------|
| `start(): Boolean` | 启动连接和音频。SDK 在 `hello` 一次性上报必填 `area_id`，收到 `ready` 后开放唤醒。 |
| `stop()` | 断开连接，停止音频。可重新 `start()`。 |
| `interrupt()` | 打断当前播放并取消服务端当前回合。 |
| `updateLocation(stationName)` | 更新当前站点名称。服务端收到新名称后自动播放到站内容（如有）。 |
| `clearLocation()` | 清除站点名称/坐标并重置对话历史；保留本 Session 的 `area_id`。 |
| `playWelcome(stationName?)` | 请求服务端播放欢迎词；园区已由当前 Session 固定。 |
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
定位权限。初始化时把宿主已有的异步方法交给 SDK 即可：

```kotlin
getLocation = { callback ->
    hostLocationService.getLocationAsync(callback)
}
```

匿名函数通过 callback 返回 `{"latitude":39.81,"longitude":116.37}` 或 `null`。
`area_id` 不由定位回调返回，而是来自必填的 `FastVoiceConfig.areaId`。SDK 在连接就绪
和每次唤醒时自动调用、解析和上传坐标；坐标刷新帧只包含经纬度，不会重复携带
`area_id`，也不会重复触发到站播报。宿主通常无需手动调用 `updateCoordinates()`。

## 园区与知识库范围

`FastVoiceConfig.areaId` 是一个订单/SDK 实例的必填且不可变字段。连接建立后 SDK
只在第一条 `hello` 中发送一次 `area_id`。服务端把它固定在 WebSocket Session，随后
调用 MaxKB 时通过 `form_data.area_id` 交给多园区工作流，由工作流在知识库检索之前
选择当前园区的 `knowledge_ids`。

同一实例不支持运行中切换园区。订单换园区时应关闭旧 `FastVoiceClient`，用新的
`areaId` 创建新实例。`updateLocation()` 只负责当前园区里的站点变化。

## 唤醒词机制

SDK 内置 Sherpa-ONNX 端侧关键词检测（KWS），唤醒词由服务端下发，宿主无需配置。

1. **握手**：连接建立后 SDK 自动发送
   `{"type":"hello","wake":true,"area_id":"18"}`，同时声明端侧 KWS 和本订单固定区域。
2. **下发**：服务端返回 `{"type":"ready","wake_words":["咘嘀",...]}`，SDK 将词
   表加载到 Sherpa KWS 引擎。
3. **唤醒确认**：用户说出任一唤醒词 → SDK 检测命中后上报服务端 → 服务端通过
   TTS 回复"在呢"并进入监听状态，等待用户提问。
4. **连续追问**：回答结束后，服务端在追问窗口内继续监听；窗口到期后回到
   `sleeping`，下一次提问需要重新唤醒。时长由服务端配置，SDK 无需感知。
5. **播放期间唤醒**：播放期间 KWS 使用放大后的原始 MIC 副本（绕过 AEC3 抑制），
   确保"停止""换一个"等打断指令不被回声消除吞掉。

宿主 App 不需要管理唤醒词列表或 KWS 引擎——`start()` 之后一切自动就绪。

## LLM 上下文行为

| 状态 | 行为 |
|------|------|
| `FastVoiceConfig.areaId = "18"` | 整个 WebSocket Session 固定为区域 18，知识请求都发送同一个 `form_data.area_id` |
| 已调用 `updateLocation("北一门")` | 在当前园区隔离范围内附加站点上下文，并触发新站点到站讲解 |
| 仅 GPS 刷新 | 只更新坐标，不改变园区或站点，不重复触发到站讲解 |
| 调用 `clearLocation()` 后 | 清除站点名称/坐标和旧对话，但 Session 的 `area_id` 不变 |
| 订单切换到新区域 | 关闭旧 Client，以新的 `areaId` 创建新 Client / 新 WebSocket Session |

## 协议帧参考

以下是一次完整交互的 wire-level JSON 帧序列。`←` 表示客户端发送，`→` 表示
服务端返回。SDK 已将这些帧封装为上层 API，宿主通常不需要直接处理。

```text
// 连接建立
← {"type":"hello","wake":true,"area_id":"18"}
→ {"type":"ready","connection_id":"c1","wake_words":["咘嘀"]}

// 位置上报 → 自动播到站介绍
← {"type":"location.update","station_name":"藻园门站-靠近西苑地铁","latitude":39.81,"longitude":116.37}
→ {"type":"location.ack","station_name":"藻园门站-靠近西苑地铁"}
→ （服务端自动播报该站点介绍音频）

// 到下一站
← {"type":"location.update","station_name":"1907站"}
→ {"type":"location.ack","station_name":"1907站"}
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
client.updateLocation("北一门")
// → 服务端自动查知识库，播放北一门介绍
// → 播完后游客可以唤醒提问："咘嘀，这里有什么好玩的？"

// 到下一站
client.updateLocation("运河广场")
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
- 当前内网部署使用 `ws://`；以后具备证书和域名后再切换 `wss://`

## 固定音频协议

- 上行：16 kHz 单声道 20 ms Opus（播放期间使用 WebRTC AEC3 处理后的音频）
- 下行：48 kHz 单声道 20 ms Opus
- 播放期间端侧 KWS 使用放大后的原始 MIC 副本
- AudioTrack 48 kHz PCM 同步作为 AEC3 回声参考

## USB 调试

```bash
adb reverse tcp:8100 tcp:8100
```

Demo 必须输入 `ws://` 或 `wss://` 服务地址，启动时会保存，点击 “Clear endpoint”
可以清除已保存地址。

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
