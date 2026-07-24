# FastVoice Android SDK

FastVoice Android SDK 把录音、离线唤醒与控制词、Opus、WebSocket、播放、打断、
重连和协议状态机收进一个 Android Library。宿主 App 使用强类型 API，不拼接或解析
WebSocket JSON。

当前产物只包含 `arm64-v8a` 原生库，最低支持 Android 7.0（API 24）。SDK 只实现
仓库当前的唯一协议，不兼容旧版 `commands-v1`、`context_update`、
`spot_arrival` 或 `sendTrustedMessage`。

## 安装

在 `settings.gradle.kts` 中加入 JitPack：

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

在 App 模块中加入依赖：

```kotlin
dependencies {
    implementation("com.github.zxkws:fastvoice-android-sdk:0.4.0")
}
```

SDK Manifest 已声明 `INTERNET` 和 `RECORD_AUDIO`。`RECORD_AUDIO` 仍是运行时权限，
必须由宿主在 `start()` 前取得。

## 最小接入

设备 ID 和短期 token 是必需配置。`DeviceTokenProvider` 是同步接口，应从安全缓存
立即返回 token；需要联网刷新时，请在启动 SDK 前完成。

```kotlin
val client = FastVoiceClient.builder(applicationContext)
    .endpoint("wss://voice.example.com/ws")
    .device(deviceId) { id -> credentialStore.currentToken(id) }
    .listener { event ->
        when (event) {
            is FastVoiceEvent.StateChanged ->
                stateView.text = event.state.value

            is FastVoiceEvent.Transcript -> {
                // role、text、final 均按服务端原值交给业务层。
                transcriptView.text = event.text
            }

            is FastVoiceEvent.OrderAck ->
                orderResultView.text = "${event.action}:${event.id}:${event.rev}"

            is FastVoiceEvent.TourAck ->
                tourResultView.text = event.id

            is FastVoiceEvent.PlaybackFinished ->
                playbackResultView.text = "${event.playbackId}:${event.tourId}"

            is FastVoiceEvent.PlaybackFailed ->
                playbackResultView.text =
                    "${event.playbackId}:${event.tourId}:${event.code}"

            is FastVoiceEvent.Error -> {
                errorCodeView.text = event.error.code
                errorMessageView.text = event.error.message
            }
        }
    }
    .build()

client.start()
```

回调在 Android 主线程执行。一个前台语音会话复用一个客户端实例：

```kotlin
client.interrupt()
client.stop()
client.close()
```

`interrupt()` 会先停止当前本地输出，再取消服务端当前轮次。`stop()` 后同一实例仍可
再次 `start()`；`close()` 后不可复用。

## 订单会话

`OrderSnapshot` 是完整快照，不是 merge patch。`rev` 在同一订单内严格递增。

```kotlin
val started = client.startOrder(
    OrderSnapshot(
        id = "order-20260724-1",
        rev = 1,
        context = mapOf(
            "park_id" to "nanyuan",
            "route_id" to "route-a",
            "current_spot_id" to "dapaozi_wetland",
        ),
    ),
)

client.updateOrder(
    OrderSnapshot(
        id = "order-20260724-1",
        rev = 2,
        context = mapOf(
            "park_id" to "nanyuan",
            "route_id" to "route-a",
            "current_spot_id" to "yanjing_tower",
        ),
    ),
)

client.endOrder("order-20260724-1", rev = 3, reason = "completed")
```

`startOrder()`、`updateOrder()` 和 `endOrder()` 返回 `true`，只表示 SDK 接受了
期望状态；它不代表服务端已经确认。连接尚未 `ready` 时，SDK 会保留最新活动快照，
并在首次 `ready` 或重连后使用 `order.start` 恢复。服务端结果只以
`FastVoiceEvent.OrderAck` 或 `FastVoiceEvent.Error` 为准。

同一操作、订单 ID、rev 和 payload 的精确重试是幂等的。同一 rev 换数据会被 SDK
或服务端拒绝。`endOrder()` 在本地立即终止旧播放，但会保留结束请求直到收到
对应 `OrderAck(action="end")`。

SDK 区分 desired 与 acknowledged 快照。`start` / `update` 只有在匹配 ACK 后才提交；
当前操作收到订单错误时回滚到最后一个 acknowledged 快照，失败的首次 start 会清空
订单，失败的 end 会解除 pending 并恢复原订单音频资格。旧 rev 的迟到错误或 ACK
不会覆盖较新的 desired 状态。

## 到点与巡游固定播报

宿主不传任意播报文本，只传服务端维护的固定内容键。

```kotlin
client.playArrival(
    id = "arrival-1",
    orderId = "order-20260724-1",
    orderRev = 2,
    spotId = "yanjing_tower",
) // content 默认 arrival_prompt

client.playCruise(
    id = "cruise-1",
    content = "park_welcome",
)
```

到点请求必须与当前订单的 ID、rev 和 `current_spot_id` 完全一致。巡游播报只允许在
没有活动订单时调用。`TourAck` 仅表示固定播报请求被接受；真正物理播完或失败分别看
`PlaybackFinished(playbackId, tourId)` 和
`PlaybackFailed(playbackId, tourId, code)`。

到点/巡游请求不会跨断线自动排队，传输不可用时方法返回 `false`。

## 凭证与网络安全

- WebSocket Upgrade 固定携带 `X-FastVoice-Device-Id` 和
  `Authorization: Bearer <token>`。
- 公网只使用 `wss://`；`ws://` 必须显式允许，且只用于本机联调。
- 不要把 token、服务端密钥或模型供应商密钥写入源码、资源或日志。
- 同设备的新连接会由服务端 fence 旧连接；SDK 也会忽略旧连接的迟到回调。

## 音频与控制边界

- 上行固定为 Opus、16 kHz、单声道、每个 WebSocket 二进制消息 20 ms。
- 下行固定为 Opus、48 kHz、单声道、每个二进制消息 20 ms。
- 生产录音源固定为 `MIC`。SDK 会尝试在录音启动前绑定平台 AEC；不可用时保留 raw
  MIC 并上报诊断。
- `capture.start(pre_roll_ms)` 最多取 1800 ms 录音环；唤醒后固定取满 1800 ms。
  预滚只发送一次，随后接续实时帧，不重复边界帧。
- 无活动订单的 idle 阶段关闭上行与唤醒 KWS，并拒绝 `capture.start`。订单开始后，
  必须先收到匹配的 `order.ack(start)`：`wakeEnabled=true` 才进入仅本地 KWS 的
  sleeping 模式；`wakeEnabled=false` 才接受服务端下发的 `capture.start` 进入持续
  listening。重连恢复同样等待 start ACK，订单结束会立即关闭上行与订单 KWS。
- KWS 在普通播放和 prompt 期间持续运行：WAKE 会被抑制，CONTROL 仍可命中。
  WAKE 路由不会降级为 CONTROL。
- 本地控制词只会可逆预暂停当前播放；服务端接受、拒绝或超时都会结束该预暂停。
- `playback.finished` 是唯一成功终态。网络 `playback.end` 只代表音频包发送完毕。
- 每个下行 Opus 包必须恰好解码为 20 ms；解码异常、错误帧长或零有效音频都会进入
  `playback.failed`，不会在随后收到 `playback.end` 时误报成功。
- 重播和跳过由服务端以新 playback ID 和新音频流实现；SDK 不保留重播缓存。

## 本地错误提示

所有服务端错误都通过 `FastVoiceEvent.Error` 原样回调。只有服务端明确提供
`fallback_text` 时，SDK 才使用 Android 系统 TTS 做最后兜底；兜底期间仍保持 KWS
运行，但抑制 WAKE。

## 构建与验证

```bash
./gradlew :fastvoice-sdk:testDebugUnitTest
./gradlew :fastvoice-sdk:lint
./gradlew :sample:assembleDebug
```

连接本机 8100 端口时：

```bash
adb reverse tcp:8100 tcp:8100
```

完整可运行接入见 [`sample`](sample)。SDK 负责音频、协议和本地控制；ASR、意图、
知识库、内容安全、LLM 与正常 TTS 均由服务端统一处理。
