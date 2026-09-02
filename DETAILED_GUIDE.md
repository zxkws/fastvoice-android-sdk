# FastVoice Android SDK 详细指南

适用版本：`0.14.0`

## 1. 能力边界

SDK 只负责 Android 端实时音频和 FastVoice WebSocket 协议：

- 麦克风采集、16kHz/20ms Opus 上行。
- Sherpa-ONNX 端侧唤醒和播放期控制词候选。
- 48kHz/20ms Opus 下行解码和 `AudioTrack` 播放。
- WebRTC AEC3 回声消除。
- 播放物理进度/终态、打断和断线重连。
- 位置上下文、欢迎词请求和服务端原始事件。

SDK 不包含 ASR、TTS、MaxKB 或大模型客户端。当前服务端使用讯飞 ASR/TTS，
但这是服务端实现细节；Android 不保存讯飞 AppID/APIKey/APISecret 或 MaxKB/
模型密钥。只要 FastVoice 保持上下行音频协议，更换服务端 TTS 无需发布新 SDK。

## 2. 集成

### 2.1 Maven Central

FastVoice Android SDK 仅通过 Maven Central 提供官方客户端接入。

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

### 2.2 Android 配置

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

SDK 已声明网络和录音权限，并默认允许当前内网部署使用的 `ws://` 明文连接。宿主
Manifest 如果明确设置了 `android:usesCleartextTraffic="false"`，需改为 `true`。
要求 Android API 23+，支持 `arm64-v8a` 和 `armeabi-v7a`。

## 3. 创建客户端

```kotlin
val client = FastVoiceClient(
    applicationContext,
    FastVoiceConfig(
        // 必填；由宿主业务明确提供
        endpoint = savedEndpointOrUserInput,
        // 必填；一个订单/SDK 实例固定一个园区 ID
        areaId = "18",
        getLocation = { callback ->
            hostLocationService.getLocationAsync(callback)
        },
    ),
) { event ->
    // 直接展示服务端原始值，不改写 transcript/code/ID。
    render(event)
}

client.start()
```

`FastVoiceConfig` 的 **endpoint 和 areaId 都必填且不能为空**。areaId 是当前订单/WebSocket
Session 的不可变区域主键，不是可选展示信息。endpoint 去除首尾空格后必须以
`ws://` 或 `wss://` 开头；SDK 不提供默认地址或失败回退地址。
SDK 不接收 token，也不发送
`Authorization`，公网访问控制由前置网关完成。

`start()` 和 `stop()` 幂等；`close()` 永久释放实例，之后不能再 `start()`。

## 4. 公开 API

| API | 作用 |
|---|---|
| `start()` | 启动音频和 WebSocket；`hello` 一次性发送 `area_id`，收到 `ready` 后开放唤醒 |
| `stop()` | 停止并允许后续重启 |
| `interrupt()` | 立即停止本地播放并取消服务端当前回合 |
| `updateLocation(stationName)` | 更新当前园区内的站点名称；新名称可触发到站播报 |
| `clearLocation()` | 清除站点名称/坐标与对话历史，保留 Session 的 `area_id` |
| `playWelcome(stationName?)` | 请求欢迎词；园区由当前 Session 固定 |
| `close()` | 永久释放实例 |

返回 `true` 只表示 SDK 已接受/发送操作，服务端确认以 `LocationAck`、
`WelcomeAck` 或 `Error` 为准。

## 5. 事件

`FastVoiceListener` 在 Android 主线程收到：

- `StateChanged`：原始服务状态。
- `Transcript`：`role/text/final` 按服务端原值传递。
- `LocationAck` / `WelcomeAck`。
- `PlaybackFinished` / `PlaybackFailed`：物理播放终态。
- `Error`：`scope/ref/rev/code/message/recoverable/fallbackText` 按原值传递。

宿主 App 不应替换服务端 ASR 文字、翻译 code 或重新格式化 ID。

## 6. 位置和重连

```kotlin
client.updateLocation("藻园门站-靠近西苑地铁")
client.playWelcome("藻园门站-靠近西苑地铁")
client.clearLocation()
```

`FastVoiceConfig.areaId` 在 Client 创建后不可变。每次建立新的 WebSocket 连接，SDK
都在第一条 `hello` 里发送同一个 `area_id`；服务端 `ready` 返回后立即可以唤醒，
不再存在 area ack 门禁。`clearLocation()` 清掉站点名称/坐标和对话历史，但 Session 的
`area_id` 继续保留。

同一订单/Client 不支持切换区域。业务订单的区域变化时，关闭旧 Client 并使用新的
`areaId` 创建新 Client。知识库隔离由服务端把 `area_id` 作为 MaxKB 工作流的同名输入后，在
检索前选择 `knowledge_ids` 完成。

SDK 不申请定位权限，也不依赖具体定位供应商。初始化时提供宿主已有的方法：

```kotlin
getLocation = { callback ->
    hostLocationService.getLocationAsync(callback)
}
```

匿名函数异步回调 `JSONObject` 或 `null`；对象只需包含数值型 `latitude` 和
`longitude`。SDK 在连接就绪和每次唤醒时调用该方法，自行解析并上传，重连时先
恢复缓存。宿主不需要在其他业务位置调用 SDK 的坐标方法。

## 7. 唤醒、打断与音频

- `hello` 始终上报 `wake=true` 和当前实例不可变的 `area_id`。
- 服务端通过 `ready.wake_words` 选择 SDK 内置模型支持的唤醒词。
- 回答播放期 KWS 检测控制词候选，候选仍由服务端 ASR 确认。
- `interrupt()` 是宿主按钮/生命周期使用的确定性打断。
- 上行固定为 16kHz、单声道、20ms Opus。
- 下行固定为 48kHz、单声道、20ms Opus。
- `AudioTrack` 实际接收的 PCM 同步作为 AEC3 远端参考。

## 8. 构建和发布

```bash
JAVA_HOME="/path/to/jdk17" ./gradlew \
  :fastvoice-sdk:testDebugUnitTest \
  :fastvoice-sdk:lint \
  :fastvoice-sdk:assembleRelease \
  :fastvoice-sdk:publishReleasePublicationToMavenLocal \
  :sample:assembleDebug

JAVA_HOME="/path/to/jdk17" ./gradlew \
  :sample:clean :sample:assembleDebug -PusePublishedSdk
```

发布版本由 `gradle.properties` 的 `VERSION_NAME` 唯一决定。推送同名标签后，GitHub Actions 会：

1. 校验 tag 与 Gradle 版本一致。
2. 运行测试、lint、AAR/Sample 构建和 Maven Local 坐标验证。
3. 检查 AAR 中的 ABI、原生库、许可证，以及 Central 所需的 sources/javadoc/POM。
4. 使用 GitHub Secrets 中的 Sonatype Central Portal token 与 GPG 私钥签名并发布到 Maven Central。
5. Maven Central 发布成功后创建 GitHub Release，上传带版本的 AAR、Sample APK 和 `SHA256SUMS.txt`。

首次发布前，需要在 Sonatype Central Portal 验证 `io.github.zxkws` namespace，并在 GitHub 仓库配置：

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_IN_MEMORY_KEY`
- `SIGNING_IN_MEMORY_KEY_PASSWORD`

其中 Central 用户名/密码应使用 Portal 生成的发布 token，签名私钥使用 ASCII-armored GPG private key。
客户端只使用 `mavenCentral()`，不需要 JitPack 或本地 AAR。
