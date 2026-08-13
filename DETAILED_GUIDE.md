# FastVoice Android SDK 详细指南

适用版本：`0.12.2`

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

### 2.1 JitPack

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
    implementation("com.github.zxkws:fastvoice-android-sdk:0.12.2")
}
```

### 2.2 本地 AAR

```bash
./gradlew :fastvoice-sdk:assembleRelease
```

复制 `fastvoice-sdk/build/outputs/aar/fastvoice-sdk-release.aar` 到 App 的 `libs/`，并加入：

```kotlin
dependencies {
    implementation(files("libs/fastvoice-sdk-release.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.25")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
}
```

### 2.3 Android 配置

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
        // 用户输入为空时自动使用内置 ws://192.168.105.165:8100/ws
        endpoint = savedEndpointOrUserInput.orEmpty(),
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

`FastVoiceConfig` 可以不传 endpoint，此时使用 `FastVoiceConfig.DEFAULT_ENDPOINT`。
也可以传入用户输入的 `ws://` 或 `wss://` 地址覆盖；空白输入仍使用默认值。SDK
不接收 token，也不发送
`Authorization`，公网访问控制由前置网关完成。

`start()` 和 `stop()` 幂等；`close()` 永久释放实例，之后不能再 `start()`。

## 4. 公开 API

| API | 作用 |
|---|---|
| `start()` | 启动音频和 WebSocket |
| `stop()` | 停止并允许后续重启 |
| `interrupt()` | 立即停止本地播放并取消服务端当前回合 |
| `updateLocation(park, spot?)` | 更新位置；有 spot 时服务端可触发到站播报 |
| `clearLocation()` | 清除位置与对话历史 |
| `playWelcome(park, spot?)` | 请求欢迎词 |
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
client.updateLocation("南苑森林湿地公园", "1907_station")
client.playWelcome("南苑森林湿地公园", "北一门")
client.clearLocation()
```

SDK 内存中保留最后一次 park/spot，断线重连并收到 `ready` 后自动重发。
`clearLocation()` 后不再恢复旧位置。

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

- `hello` 始终上报 `wake=true`。
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
2. 运行测试、lint、AAR/Sample 构建和 Maven Local 验证。
3. 检查 AAR 中的 ABI、原生库和许可证。
4. 创建 GitHub Release，上传带版本的 AAR、Sample APK 和 `SHA256SUMS.txt`。

JitPack 使用同一 tag 构建 Maven 坐标。
