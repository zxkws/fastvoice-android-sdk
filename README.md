# FastVoice Android SDK

FastVoice 是面向 Android 的实时语音 SDK。负责 WebSocket 连接、麦克风
采集、Opus 编解码、播放期控制词检测、流式播放、打断和断线重连。

SDK 当前源码版本为 `1.0.0`。从 `1.0.0` 起，SDK 与 FastVoice 服务端镜像共享同一套
兼容性版本：不兼容的 wire / 公共 API 变更升级主版本，兼容新增升级次版本，兼容修复升级补丁版本。
`1.0.0` 是新的破坏性基线，不兼容 0.x wire/API；宿主与服务端应按同一主版本升级，不提供旧字段或旧消息 fallback。
SDK 不调用 Android `TextToSpeech`；所有助手语音都来自服务端。当前 no-wake 主链路不再使用本地休眠提示音。
ASR、TTS、MaxKB 和大模型均是服务端实现细节，Android 不保存
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
    implementation("io.github.zxkws:fastvoice-android-sdk:1.0.0")
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
        is FastVoiceEvent.DestinationAck -> log("目的地讲解已确认")
        is FastVoiceEvent.WelcomeAck  -> log("欢迎词已确认")
        is FastVoiceEvent.PlaybackFinished -> log("播放完成")
        is FastVoiceEvent.PlaybackFailed   -> log("播放失败: ${event.code}")
        is FastVoiceEvent.Error       -> showError(event.error)
    }
}

// 2. 启动 — hello 一次性上报 area_id，ready 后直接进入 capture/listening
client.start()

// 3. 同步宿主所选目的地；不会自动播放
client.updateLocation("藻园门站-靠近西苑地铁")

// 4. 明确需要讲解这个目的地时再调用
client.playDestination("藻园门站-靠近西苑地铁")

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
client.playDestination("北一门");
client.playWelcome();
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
| `start(): Boolean` | 启动连接和音频。SDK 在 `hello` 一次性上报必填 `area_id`；服务端 ready 后直接开放 capture 并进入 listening。 |
| `stop()` | 断开连接，停止音频。可重新 `start()`。 |
| `interrupt()` | 打断当前播放并取消服务端当前回合。 |
| `updateLocation(stationName)` | 同步宿主当前所选目的地；可在 `start()` 前写入最新状态，连接 `ready` 后自动补发；不会自动播放。 |
| `playDestination(stationName)` | 显式选择并播放一个目的地介绍；重复调用同一名称表示明确重播。 |
| `clearLocation()` | 清除 SDK 缓存的站点名称/坐标；已连接时同时让服务端重置对应上下文，保留本 Session 的 `area_id`。 |
| `playWelcome()` | 请求服务端播放当前区域欢迎词；区域只由 `FastVoiceConfig.areaId` 固定。 |
| `close()` | 永久释放实例。 |

`start()`、位置、目的地讲解和欢迎词方法返回 `Boolean`。对 `updateLocation()` /
`updateCoordinates()` / `clearLocation()`，`true` 表示 SDK 已接受本地状态；即使尚未连接也会保留
最新快照，连接 `ready` 后补发。服务端是否接受仍以 `LocationAck` 为准。播放类动作不会离线排队。

## 事件

| 事件 | 说明 |
|------|------|
| `StateChanged(state)` | 状态变化：listening / recognizing / generating / speaking / prompting |
| `Transcript(role, text, final)` | 语音转文字（role=user）或回答文字（role=assistant） |
| `LocationAck` | 服务端确认位置上报 |
| `DestinationAck(stationName)` | 服务端确认显式目的地讲解请求 |
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
`area_id` 不由定位回调返回，而是来自必填的 `FastVoiceConfig.areaId`。SDK 每次收到服务端
`state=listening` 时调用、解析和上传坐标；服务端首次 ready、回答/重置恢复以及真实用户
VAD 起声都会发送 listening。坐标刷新帧只包含经纬度，不会重复携带 `area_id`，也不会触发目的地讲解。当前 GPS 只供服务端天气能力使用，不能用于推断
所选目的地或真实到站状态。宿主通常无需手动调用 `updateCoordinates()`。如果宿主显式调用它，SDK 会把该坐标视为比已经在途的旧 `getLocation` 请求更新，并丢弃随后迟到的旧回调，避免旧坐标覆盖新坐标。

## 园区与知识库范围

`FastVoiceConfig.areaId` 是一个订单/SDK 实例的必填且不可变字段。连接建立后 SDK
只在第一条 `hello` 中发送一次 `area_id`。服务端把它固定在 WebSocket Session，随后
调用 MaxKB 时通过 `form_data.area_id` 交给多园区工作流，由工作流在知识库检索之前
选择当前园区的 `knowledge_ids`。

同一实例不支持运行中切换园区。订单换园区时应关闭旧 `FastVoiceClient`，用新的
`areaId` 创建新实例。`areaId` 只表示服务园区/知识范围，不证明车辆物理位置。
`updateLocation()` 只同步所选目的地；`playDestination()` 才显式启动目的地讲解。

## 独立按压麦克风与 no-wake

当前正式协议不再使用语音唤醒。目标硬件的物理按压本身提供输入授权：

1. SDK 建连后发送 `{"type":"hello","area_id":"18"}`。
2. 服务端返回 `ready`，随后发送 `capture.start(pre_roll_ms=0)` 与 `state=listening`。
3. 用户按住独立麦克风即可直接说问题；不需要先说“咘嘀”，也没有“在呢”唤醒提示。
4. 回答、错误恢复、`location.clear` 和自动重连后都会重新回到 capture/listening。
5. 本地 Sherpa KWS 仍保留，但只在播放/提示阶段检测“停止 / 继续 / 换一个”等控制候选，候选最终仍由服务端结合 ASR 证据确认。

`FastVoiceClient.start()/stop()` 是长生命周期操作，管理 WebSocket、AudioRecord、AEC 和自动重连，**不要**把它们映射到物理按键按下/松开。当前也不提供额外 `pressToTalkStart/Stop` API；目标麦克风应由硬件自身门控输入。

目标独立麦克风仍需真机确认松开后 `AudioRecord.read()` 的实际行为、快速连续按压稳定性、播放期近端收音以及长期空闲 CPU/网络行为。普通手机内置麦克风可以验证协议，但不能替代这些硬件验收。

## LLM 上下文行为

| 状态 | 行为 |
|------|------|
| `FastVoiceConfig.areaId = "18"` | 整个 WebSocket Session 固定为区域 18，知识请求都发送同一个 `form_data.area_id` |
| 已调用 `updateLocation("北一门")` | 把“北一门”保存为所选目的地上下文；不自动播放，也不证明已经到达 |
| 已调用 `playDestination("北一门")` | 在当前园区隔离范围内显式查询并播放“北一门”目的地介绍 |
| 仅 GPS 刷新 | 只更新天气使用的坐标，不改变园区或所选目的地，不触发目的地讲解 |
| 调用 `clearLocation()` 后 | 清除站点名称/坐标和旧对话，但 Session 的 `area_id` 不变 |
| 订单切换到新区域 | 关闭旧 Client，以新的 `areaId` 创建新 Client / 新 WebSocket Session |

## 协议帧参考

以下是一次完整交互的 wire-level JSON 帧序列。`←` 表示客户端发送，`→` 表示
服务端返回。SDK 已将这些帧封装为上层 API，宿主通常不需要直接处理。

```text
// 连接建立
← {"type":"hello","area_id":"18"}
→ {"type":"ready","connection_id":"c1","control_timeout_ms":2500}
→ {"type":"control","id":"k1","action":"capture.start","pre_roll_ms":0}
→ {"type":"state","value":"listening"}

// 同步所选目的地/坐标；不会自动播放
← {"type":"location.update","station_name":"藻园门站-靠近西苑地铁","latitude":39.81,"longitude":116.37}
→ {"type":"location.ack","station_name":"藻园门站-靠近西苑地铁"}

// 显式请求目的地讲解
← {"type":"destination.play","station_name":"藻园门站-靠近西苑地铁"}
→ {"type":"destination.ack","station_name":"藻园门站-靠近西苑地铁"}
→ （服务端查询当前 area 的知识并播放该目的地介绍）

// 离开（清除位置 + 对话历史）
← {"type":"location.clear"}
→ {"type":"location.ack"}
```

no-wake 对话帧序列：

```text
// 用户按住物理麦克风直接说问题；客户端持续上行 Opus
→ {"type":"state","value":"listening"}
→ {"type":"transcript","role":"user","text":"这里有什么好玩的","final":true}
→ {"type":"transcript","role":"assistant","text":"...","final":true}
→ （48 kHz Opus 音频流）
→ {"type":"state","value":"listening"}
```

## 使用场景

### 所选目的地讲解

```kotlin
// 宿主业务先同步当前所选目的地
client.updateLocation("北一门")

// 真正需要播放介绍时显式触发
client.playDestination("北一门")
// → 服务端在当前 area 的知识范围内查询并播放北一门介绍

// 切换所选目的地本身不自动播放
client.updateLocation("运河广场")
client.playDestination("运河广场")
```

### 欢迎词

```kotlin
// 南苑森林湿地公园的 ID 已在创建 Client 时通过 areaId = "18" 固定
// 游客上车时只需主动触发欢迎词，不再重复传公园名或公园 ID
client.playWelcome()
```

### 纯对话（不上报位置）

```kotlin
// 连上就能说话，不需要上报位置
client.start()
// 用户按住独立麦克风直接提问 → 服务端回答
// 没有位置上下文时，服务端按通用知识回答
```

### 结束

```kotlin
// 游客下车，清空上下文
client.clearLocation()
```

## 断线重连

SDK 自动重连。重连后：
- 如果之前同步过目的地/坐标，自动重发 `location.update` 恢复状态
- 不会自动发送 `destination.play`，因此不会因为重连重复讲解
- 服务端重新开放 capture/listening，可直接进行下一轮
- 不需要宿主做任何操作

## 线程与安全

- 回调统一在 Android 主线程触发
- 一个 `FastVoiceClient` 对应一个前台语音所有权
- `start()`、`stop()` 幂等，`close()` 永久释放
- no-wake、断线重连和绕过系统代理是固定行为
- 当前内网部署使用 `ws://`；以后具备证书和域名后再切换 `wss://`

## 固定音频协议

- 上行：16 kHz 单声道 PCM，播放及回声尾窗使用 WebRTC AEC3 输出，无近期播放时使用原始 MIC；按 20 ms 编为 Opus 传输
- 下行：48 kHz 单声道 20 ms Opus
- 播放期间控制词 KWS 使用放大后的原始 MIC 副本
- AudioTrack 实际接受的 48 kHz PCM 作为客户端 AEC3 参考；预录缓冲与实时上行使用相同的音频来源，播放控制 KWS 分支独立

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
