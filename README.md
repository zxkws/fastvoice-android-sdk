# FastVoice Android SDK

FastVoice Android SDK 把录音、离线唤醒、Opus 编解码、WebSocket、播放、打断、重连和协议细节收进一个 Android Library。业务 App 只负责申请麦克风权限、提供设备凭证、监听原始事件，以及在可信业务事件发生时更新车辆上下文或上报到点。

当前版本仅包含 `arm64-v8a` 原生库，最低支持 Android 7.0（API 24）。

## 安装

在项目的 `settings.gradle.kts` 中加入 JitPack：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

在 App 模块中加入一行依赖：

```kotlin
dependencies {
    implementation("com.github.zxkws:fastvoice-android-sdk:0.1.0")
}
```

GitHub Release 同时附带 AAR 和 SHA-256，供离线留档；正常集成仍推荐上面的 Maven
坐标，因为 OkHttp、Concentus 等传递依赖会自动解析。

SDK 的 Manifest 已包含网络和录音权限，合并后的 App Manifest 会包含：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

宿主无需重复声明，但 `RECORD_AUDIO` 是运行时权限，仍必须由宿主 App 在启动 SDK 前取得。完整可运行接入见 [`sample`](sample)。

## 最小接入边界

生产 App 只需完成以下工作：

1. 创建并持有一个 SDK 客户端实例；
2. 在页面或服务进入工作状态后启动，在不再使用时停止，并在持有者销毁时释放；
3. 把 SDK 回调的 `state`、`asr`、`reply` 和 `error` 原始字段交给业务层；
4. 从设备登录体系或自己的服务端取得短期设备 token；
5. 从受信任的定位、路线和站点模块更新上下文、上报到点。

业务 App 不需要接入 ASR、LLM、TTS、内容安全或知识库供应商，也不需要理解内部 WebSocket 消息、音频帧、重连和连续对话窗口。

具体调用以同一版本仓库中的 [`sample`](sample) 为准；不要复制 SDK 的 internal 类到业务工程。

## 最小代码

SDK 回调在 Android 主线程执行，可以直接更新界面。下面的 `deviceCredentialStore` 代表业务 App 自己的安全设备凭证模块：

```kotlin
val listener = object : FastVoiceListenerAdapter() {
    override fun onStateChanged(state: FastVoiceState) {
        stateView.text = state.value
    }

    override fun onAsr(text: String) {
        asrView.text = text
    }

    override fun onReplyDelta(text: String) {
        replyView.append(text)
    }

    override fun onError(error: FastVoiceError) {
        errorCodeView.text = error.code
        errorMessageView.text = error.message
        errorPromptView.text = error.prompt
    }

    override fun onContextUpdated(event: FastVoiceEvent.ContextUpdated) {
        contextVersionView.text = event.version.toString()
    }

    override fun onArrivalAccepted(event: FastVoiceEvent.ArrivalAccepted) {
        arrivalEventIdView.text = event.eventId
    }

    override fun onArrivalRejected(event: FastVoiceEvent.ArrivalRejected) {
        arrivalErrorCodeView.text = event.code
        arrivalErrorMessageView.text = event.message
    }
}

val client = FastVoiceClient.builder(applicationContext)
    .endpoint("wss://voice.example.com/ws")
    .device(deviceId) { id -> deviceCredentialStore.currentToken(id) }
    .listener(listener)
    .build()

client.start()
```

客户端实例还提供：

```kotlin
client.interrupt()
client.updateContext(VehicleContext(currentStationId = stationId))
client.reportArrival(
    SpotArrival(
        parkId = parkId,
        routeId = routeId,
        stationId = stationId,
        spotId = spotId,
    ),
)
client.stop()
client.close()
```

`start()`、`updateContext()` 和 `reportArrival()` 返回 `Boolean`，表示请求是否已被当前客户端接受；实际连接状态和服务端结果仍以回调为准。`DeviceTokenProvider` 是同步接口，应从安全缓存立即返回当前短期 token；如果取 token 需要网络请求，应在启动 SDK 前完成获取。

## 凭证与网络安全

- 示例页的 endpoint、device ID 和 token 输入框只用于本机联调，不会持久化；生产界面不要提供这些输入框。
- 不要把 token、服务端密钥或任何模型供应商密钥写进源码、`BuildConfig`、资源文件或 Git 仓库。
- 生产环境通过安全的凭证 provider 获取短期 token，并在 token 过期时刷新；SDK 只在建立连接时读取凭证。
- 公网环境只使用 `wss://`。`ws://127.0.0.1` 仅用于 `adb reverse` 本机调试。
- 不要记录 token；服务端还应校验 device ID 与 token 的绑定关系，并实施过期、撤销、限流和连接审计。

## 上下文与到点事件

上下文用于让服务端知道车辆当前所在园区、路线、景点或站点。游客的自然语言不能直接改写这些字段。生产 App 应只从受信任的定位/调度模块调用上下文和到点 API；服务端仍要校验设备权限、字段白名单、版本、时效和路线归属。

示例 App 的上下文和到点输入框同样只用于联调，不能照搬到游客可操作的生产页面。

## 生命周期

- 一个前台语音会话复用一个客户端实例，不要每句话都重新创建。
- 获得麦克风权限后再启动。
- SDK 回调已经切到主线程，宿主不需要再次切线程。
- 页面短暂切到后台是否继续工作由宿主业务决定；需要后台语音时，宿主 App 必须按 Android 要求使用带麦克风类型的前台服务并向用户展示通知。
- 明确停止工作时调用停止；持有客户端的 Activity、Service 或其他组件销毁时务必释放。
- 不要让两个 SDK 实例同时占用麦克风。

## 唤醒词

SDK 内置离线唤醒模型。服务端返回本设备启用的唤醒词集合，SDK 仅启用“本地模型支持集合”和“服务端配置集合”的交集。增加模型从未训练或预置的新唤醒词仍需要重新发布 SDK；仅切换已预置词无需业务 App 改代码。

## 示例工程

```bash
./gradlew :sample:assembleDebug
adb install -r sample/build/outputs/apk/debug/sample-debug.apk
```

连接开发机服务时可执行：

```bash
adb reverse tcp:8100 tcp:8100
```

然后在示例页使用 `ws://127.0.0.1:8100/ws`。生产联调应使用真实 `wss://` 地址和后端签发的测试设备凭证。

## SDK 职责

- 16 kHz 单声道麦克风采集与 20 ms Opus 上行；
- 48 kHz Opus 下行播放；
- 离线唤醒、播放期间打断和连续对话；
- WebSocket 建连、心跳、重连与超时处理；
- 服务端控制协议、上下文版本和到点事件 ID；
- 将服务端状态、识别文本、回复文本和错误字段原样回调给宿主，同时保持内部协议私有。

内容安全、意图识别、天气/日期/景点知识、ASR、LLM 与 TTS 均由服务端统一处理。
