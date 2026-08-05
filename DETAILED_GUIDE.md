# FastVoice Android SDK — 新手详解文档

> 本文档面向 Android 开发新手，逐文件、逐行解释项目中每个文件的作用、代码含义和关键字用途。

---

## 目录

1. [项目整体结构](#1-项目整体结构)
2. [根目录构建配置文件](#2-根目录构建配置文件)
   - [settings.gradle.kts](#21-settingsgradlekts)
   - [build.gradle.kts（根）](#22-buildgradlekts根)
   - [gradle.properties](#23-gradleproperties)
   - [gradle/libs.versions.toml](#24-gradlelibsversionstom)
   - [gradle/wrapper/gradle-wrapper.properties](#25-gradlewrappergradlewrapperproperties)
3. [fastvoice-sdk 模块构建配置](#3-fastvoice-sdk-模块构建配置)
   - [fastvoice-sdk/build.gradle.kts](#31-fastvoice-sdkbuildgradlekts)
   - [fastvoice-sdk/consumer-rules.pro](#32-consumer-rulespro)
   - [fastvoice-sdk/src/main/AndroidManifest.xml](#33-androidmanifestxml)
4. [公共 API 源码](#4-公共-api-源码)
   - [FastVoiceConfig.kt](#41-fastvoiceconfigkt)
   - [FastVoiceClient.kt](#42-fastvoiceclientkt)
   - [FastVoiceEvent.kt](#43-fastvoiceeventkt)
   - [SessionSnapshot.kt](#44-sessionsnapshotkt)
   - [ContentRequest.kt](#45-contentrequestkt)
   - [SessionRef.kt](#46-sessionrefkt)
   - [StructuredAttributes.kt](#47-structuredattributeskt)
5. [内部实现（internal 包）](#5-内部实现internal-包)
   - [CurrentProtocol.kt](#51-currentprotocolkt)
   - [ProtocolEncoder.kt](#52-protocolencoderkt)
   - [AudioEngine.kt](#53-audioenginekt)
   - [WebRtcEchoCanceller.kt](#54-webrtcechocancellerkt)
   - [LocalCommandSpotter.java](#55-localcommandspotterjava)
   - [KeywordLineRegistry.java](#56-keywordlineregistryjava)
   - [KeywordRoutingPolicy.kt](#57-keywordroutingpolicykt)
   - [SessionOperationState.kt](#58-sessionoperationstatekt)
   - [ContentRequestState.kt](#59-contentrequeststatekt)
   - [SessionAudioPolicy.kt](#510-sessionaudiopolicykt)
   - [PlaybackTerminalState.kt](#511-playbackterminalstatekt)
   - [PlaybackFramePolicy.kt](#512-playbackframepolicykt)
   - [CapturePreRollPolicy.kt](#513-captureprerollpolicykt)
   - [ClientSessionEpoch.kt](#514-clientsessionepochkt)
   - [LocalCommandPrePauseState.kt](#515-localcommandprepausestatekt)
   - [LocalCommandTimeoutPolicy.kt](#516-localcommandtimeoutpolicykt)
   - [ReplaceOnSuccess.java](#517-replaceonsuccessjava)
   - [AudioTrackResumePolicy.java](#518-audiotrackresumepo1icyjava)
   - [AudioWriteRecoveryPolicy.java](#519-audiowriterecoverypolicyjava)
   - [AudioReadRecoveryPolicy.java](#520-audioreadrecoverypolicyjava)
6. [Native C++ / JNI 层](#6-native-c--jni-层)
   - [webrtc_aec3_jni.cpp](#61-webrtc_aec3_jnicpp)
   - [build-webrtc-native.sh](#62-build-webrtc-nativesh)
7. [示例模块（sample）](#7-示例模块sample)
   - [sample/build.gradle.kts](#71-samplebuildgradlekts)
   - [sample/AndroidManifest.xml](#72-sampleandroidmanifestxml)
   - [sample/MainActivity.kt](#73-samplemainactivitykt)
8. [CI/CD 配置](#8-cicd-配置)
   - [.github/workflows/android.yml](#81-githubworkflowsandroidyml)
   - [jitpack.yml](#82-jitpackyml)
9. [关键概念总结](#9-关键概念总结)

---

## 1. 项目整体结构

```
fastvoice-android-sdk/
├── settings.gradle.kts          ← Gradle 多模块项目入口
├── build.gradle.kts             ← 根级插件声明
├── gradle.properties            ← 全局 Gradle 属性
├── gradle/
│   ├── libs.versions.toml       ← 版本目录（统一管理依赖版本）
│   └── wrapper/                 ← Gradle Wrapper（让每个人用同一版本 Gradle）
├── gradlew / gradlew.bat        ← Gradle Wrapper 执行脚本
├── fastvoice-sdk/               ← SDK 核心模块（library）
│   ├── build.gradle.kts
│   ├── consumer-rules.pro       ← 给使用者的 ProGuard 规则
│   ├── build-webrtc-native.sh   ← 编译 WebRTC AEC3 原生库的脚本
│   ├── libs/                    ← 本地 JAR（sherpa-onnx KWS 引擎）
│   ├── native/webrtc-aec3/      ← C++ JNI 源码
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/fastvoice/    ← KWS 模型文件（随 AAR 打包）
│       ├── jniLibs/arm64-v8a/   ← 预编译的 .so 原生库
│       └── java/com/zxkws/fastvoice/
│           ├── *.kt             ← 5 个公共 API 文件
│           └── internal/        ← 14 个内部实现文件
├── sample/                      ← 示例 App 模块（application）
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/.../MainActivity.kt
├── .github/workflows/           ← GitHub Actions CI/CD
├── jitpack.yml                  ← JitPack 发布配置
├── README.md
├── LICENSE
└── CHANGELOG.md
```

### 新手须知：Android 项目的基本概念

| 概念 | 解释 |
|------|------|
| **模块（module）** | 一个独立的构建单元。本项目有两个模块：`fastvoice-sdk`（库）和 `sample`（应用） |
| **Gradle** | Android 的构建工具，负责编译、打包、测试 |
| **AAR** | Android Archive，是一个库的打包格式（类似 Java 的 JAR，但包含资源和 native 库） |
| **JNI** | Java Native Interface，让 Java/Kotlin 调用 C/C++ 代码 |
| **WebSocket** | 一种全双工网络协议，客户端和服务端可以同时发送接收数据 |
| **Opus** | 一种高效的音频编解码格式，SDK 用它压缩/解压语音数据 |
| **KWS** | Keyword Spotting，关键词检测，让设备能本地识别唤醒词 |
| **AEC** | Acoustic Echo Cancellation，回声消除，防止扬声器的声音被麦克风重新采集 |


---

## 2. 根目录构建配置文件

### 2.1 settings.gradle.kts

这是 Gradle 项目的"入口配置"，告诉 Gradle 这个项目有哪些模块。

```kotlin
// pluginManagement — 告诉 Gradle 从哪里下载构建插件
pluginManagement {
    repositories {
        google()              // Google 的 Maven 仓库（Android 插件在这里）
        mavenCentral()        // 最大的公共 Maven 仓库
        gradlePluginPortal()  // Gradle 官方插件门户
    }
}

// dependencyResolutionManagement — 统一管理所有模块的依赖仓库
dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS：禁止模块自己声明仓库，强制使用这里的统一配置
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 如果设置了 usePublishedSdk 属性，加入本地 Maven（用于测试发布后的 SDK）
        if (providers.gradleProperty("usePublishedSdk").isPresent) {
            mavenLocal()
        }
        google()
        mavenCentral()
    }
}

// rootProject.name — 整个项目的名字
rootProject.name = "fastvoice-android-sdk"

// include — 声明项目包含哪些模块（子文件夹）
include(":fastvoice-sdk")  // SDK 库模块
include(":sample")         // 示例应用模块
```

**关键字解释：**
- `pluginManagement`：配置 Gradle 插件的下载源
- `repositories`：仓库列表，Gradle 从这里下载依赖
- `include`：注册子模块，冒号 `:` 表示根级子模块

---

### 2.2 build.gradle.kts（根）

根目录的 `build.gradle.kts` 只声明所有模块可能用到的插件，但用 `apply false` 表示不在根级应用。

```kotlin
plugins {
    // alias(libs.plugins.xxx) — 引用 libs.versions.toml 中定义的插件
    alias(libs.plugins.android.application) apply false  // Android 应用插件
    alias(libs.plugins.android.library) apply false      // Android 库插件
    alias(libs.plugins.kotlin.android) apply false       // Kotlin Android 插件
}
```

**为什么用 `apply false`？**
因为根项目本身不需要编译代码，它只是一个"容器"。每个子模块自己决定要应用哪些插件。

---

### 2.3 gradle.properties

全局属性文件，所有模块共享。

```properties
# JVM 最大内存 3GB，文件编码 UTF-8（Gradle 守护进程参数）
org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8

# 启用并行构建（多模块同时编译，加快速度）
org.gradle.parallel=true

# 启用构建缓存（没改的代码不重新编译）
org.gradle.caching=true

# 使用 AndroidX 库（Google 推荐的新版支持库）
android.useAndroidX=true

# 非传递 R 类（每个模块只能看到自己的资源 ID，不会意外引用其他模块的）
android.nonTransitiveRClass=true

# Kotlin 代码风格：official（官方推荐）
kotlin.code.style=official

# SDK 版本号，所有模块通过 providers.gradleProperty("VERSION_NAME") 读取
VERSION_NAME=0.9.0
```

---

### 2.4 gradle/libs.versions.toml

这是 Gradle 7+ 引入的"版本目录"，集中管理所有依赖的版本号。

```toml
[versions]
# AGP = Android Gradle Plugin，用于编译 Android 项目
agp = "8.7.3"
# Kotlin 编译器版本
kotlin = "1.9.25"
# JUnit 单元测试框架版本
junit = "4.13.2"
# OkHttp：流行的 HTTP/WebSocket 客户端库
okhttp = "4.12.0"
# Concentus：纯 Java 的 Opus 编解码库
concentus = "1.0.2"

[libraries]
# 格式：名字 = { module = "group:artifact", version.ref = "版本引用" }
junit = { module = "junit:junit", version.ref = "junit" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
concentus = { module = "io.github.jaredmdobson:concentus", version.ref = "concentus" }

[plugins]
# 格式：名字 = { id = "插件ID", version.ref = "版本引用" }
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

**好处：** 如果要升级 OkHttp 版本，只需改一处数字即可。

---

### 2.5 gradle/wrapper/gradle-wrapper.properties

指定项目使用的 Gradle 版本。

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
# 使用 Gradle 8.9
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

**Gradle Wrapper 的意义：** 团队中每个人不需要手动安装 Gradle，运行 `./gradlew` 时会自动下载这里指定的版本，确保构建一致性。


---

## 3. fastvoice-sdk 模块构建配置

### 3.1 fastvoice-sdk/build.gradle.kts

这是 SDK 库的构建脚本，定义了编译选项、依赖和发布配置。

```kotlin
import org.gradle.api.publish.maven.MavenPublication
// ↑ 导入 Maven 发布相关类

plugins {
    alias(libs.plugins.android.library)   // 应用 Android 库插件（输出 AAR 而不是 APK）
    alias(libs.plugins.kotlin.android)    // 启用 Kotlin 编译
    `maven-publish`                       // 启用 Maven 发布（让 SDK 可以被其他项目引用）
}

// group — Maven 坐标的 groupId（像包名一样标识作者/组织）
group = "com.github.zxkws"
// version — 从 gradle.properties 中读取 VERSION_NAME
version = providers.gradleProperty("VERSION_NAME").get()

android {
    // namespace — Android 资源的 R 类所在包名
    namespace = "com.zxkws.fastvoice"
    // compileSdk = 35 — 使用 API 35 来编译（能用最新 API）
    compileSdk = 35

    defaultConfig {
        // minSdk = 24 — SDK 最低支持 Android 7.0（API 24）
        minSdk = 24

        ndk {
            // 只编译 arm64-v8a 架构（现代手机的 64 位 ARM）
            abiFilters += "arm64-v8a"
        }

        // consumerProguardFiles — 给使用者的混淆规则（打包进 AAR）
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        // Java 11 兼容级别
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        // Kotlin 编译目标也是 JVM 11
        jvmTarget = "11"
    }

    publishing {
        // 只发布 release 变体，并带上源码 JAR
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        // 单元测试可以访问 Android 资源
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // implementation — 编译时和运行时都需要的依赖
    // files("libs/...") — 引用本地 JAR 文件（sherpa-onnx KWS 引擎的 Java 类）
    implementation(files("libs/sherpa-onnx-1.13.2-classes.jar"))
    // OkHttp：WebSocket 通信库
    implementation(libs.okhttp)
    // Concentus：纯 Java Opus 编解码
    implementation(libs.concentus)

    // testImplementation — 只在测试时使用
    testImplementation(libs.junit)
}

// afterEvaluate — 在 Gradle 评估完所有配置后执行（发布配置需要在 Android 组件就绪后）
afterEvaluate {
    publishing {
        publications {
            // 创建一个名为 "release" 的 Maven 发布
            create<MavenPublication>("release") {
                groupId = "com.github.zxkws"
                artifactId = "fastvoice-android-sdk"
                version = project.version.toString()
                // from(components["release"]) — 发布 Android release 组件（AAR）
                from(components["release"])

                // pom — Maven POM 元数据（描述库的信息）
                pom {
                    name = "FastVoice Android SDK"
                    description = "Low-friction Android client SDK for the FastVoice voice assistant service."
                    url = "https://github.com/zxkws/fastvoice-android-sdk"
                    licenses { /* Apache 2.0 */ }
                    developers { /* 开发者信息 */ }
                    scm { /* 源码管理地址 */ }
                }
            }
        }
    }
}
```

**关键字速查：**

| 关键字 | 含义 |
|--------|------|
| `plugins {}` | 声明此模块使用的 Gradle 插件 |
| `android {}` | Android 特定配置块 |
| `namespace` | 生成的 R 类的包名 |
| `compileSdk` | 编译时使用的 Android API 版本 |
| `minSdk` | 应用能运行的最低 Android 版本 |
| `ndk` | Native Development Kit，编译 C/C++ 代码相关配置 |
| `abiFilters` | 限制支持的 CPU 架构 |
| `implementation` | 依赖声明：编译+运行时都可见 |
| `testImplementation` | 依赖声明：仅测试时可见 |
| `afterEvaluate` | 延迟执行代码块 |
| `MavenPublication` | 发布产物到 Maven 仓库的配置对象 |

---

### 3.2 consumer-rules.pro

ProGuard/R8 规则文件。打包进 AAR，使用 SDK 的应用在代码混淆时会自动应用这些规则。

```proguard
# sherpa-onnx 的 Java 类通过 JNI 被 C++ 代码反射调用，不能混淆/移除
-keep class com.k2fsa.sherpa.onnx.** { *; }

# 所有包含 native 方法的类，保留方法名（JNI 靠方法名匹配 C++ 函数）
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
```

**什么是 ProGuard/R8？**
发布时，Android 会"混淆"代码：把类名/方法名改成 a、b、c 来缩小 APK。但 JNI 依赖精确的类名和方法名，所以必须用 `-keep` 规则排除它们。

---

### 3.3 AndroidManifest.xml

SDK 模块的清单文件，声明需要的权限。

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- INTERNET 权限：使用 WebSocket 连接服务器 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <!-- RECORD_AUDIO 权限：使用麦克风 -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
</manifest>
```

**注意：** 库模块的 Manifest 只声明自己需要的权限。Android 构建工具会自动把库和应用的 Manifest 合并。


---

## 4. 公共 API 源码

这些是使用 SDK 的开发者直接接触的类。它们位于 `com.zxkws.fastvoice` 包下。

### 4.1 FastVoiceConfig.kt

配置类，创建 SDK 客户端时传入。一个实例对应一组不可变配置。

```kotlin
package com.zxkws.fastvoice
// ↑ package 声明：这个文件属于 com.zxkws.fastvoice 包

import java.util.Collections
// ↑ 导入 Java 集合工具类

// --- 私有辅助函数 ---

// private fun — 私有函数，只有本文件内能调用
// 验证 token 格式：不为空、不超过4096字符、不含空白字符
private fun requireValidToken(token: String): String {
    // require() — Kotlin 标准库函数，条件不满足时抛出 IllegalArgumentException
    require(token.isNotEmpty() && token.length <= 4_096 && token.none(Char::isWhitespace)) {
        "token must be 1..4096 non-whitespace characters"
    }
    return token
}
```

#### DeviceTokenProvider — 设备令牌提供者

```kotlin
// fun interface — 函数式接口（只有一个抽象方法，可以用 Lambda 实现）
// 在每次连接前被调用，提供当前设备令牌
fun interface DeviceTokenProvider {
    // 返回 String? — 问号表示可以为 null，返回 null 则拒绝连接
    fun token(): String?

    // companion object — 伴生对象，相当于 Java 的 static 部分
    companion object {
        // @JvmStatic — 让 Java 代码可以直接用 DeviceTokenProvider.fixed(...) 调用
        @JvmStatic
        fun fixed(token: String): DeviceTokenProvider {
            return FixedDeviceTokenProvider(requireValidToken(token))
        }
    }
}

// private class — 私有类，外部不可见
// 固定 token 实现：每次都返回同一个 token
private class FixedDeviceTokenProvider(private val token: String) : DeviceTokenProvider {
    override fun token(): String = token
    // toString 不暴露 token 内容（安全考虑）
    override fun toString(): String = "DeviceTokenProvider([redacted])"
}
```

#### FastVoiceLogLevel — 日志级别枚举

```kotlin
// enum class — 枚举类，列出所有可能的值
enum class FastVoiceLogLevel {
    DEBUG,   // 调试信息（最详细）
    INFO,    // 一般信息
    WARN,    // 警告
    ERROR,   // 错误（最严重）
}
```

#### FastVoiceLogger — 日志接口

```kotlin
// fun interface — 函数式接口
// SDK 用这个接口输出日志，使用者可以自定义日志去向
fun interface FastVoiceLogger {
    fun log(level: FastVoiceLogLevel, message: String, error: Throwable?)
    // Throwable? — 可选的异常对象，? 表示可以为 null
}
```

#### FastVoiceConfig — 主配置类

```kotlin
// class ... @JvmOverloads constructor — 主构造函数
// @JvmOverloads — 为 Java 生成多个重载构造函数（Kotlin 有默认参数，Java 没有）
class FastVoiceConfig @JvmOverloads constructor(
    val endpoint: String,                           // WebSocket 服务端地址
    val tokenProvider: DeviceTokenProvider,          // 令牌提供者
    val wakeEnabled: Boolean = true,                // 是否启用语音唤醒
    preferredWakeWords: List<String> = emptyList(), // 偏好唤醒词列表
    val autoReconnect: Boolean = true,              // 断线是否自动重连
    val bypassSystemProxy: Boolean = false,         // 是否绕过系统代理
    val allowInsecureConnection: Boolean = false,   // 是否允许非加密连接(ws://)
    val routeAudioToSpeaker: Boolean = true,        // 是否将音频路由到扬声器
    val logger: FastVoiceLogger? = null,            // 可选的日志记录器
) {
    // 将唤醒词列表做成不可修改副本（防御性编程）
    val preferredWakeWords: List<String> =
        Collections.unmodifiableList(ArrayList(preferredWakeWords))

    // init 块 — 构造时自动执行的验证逻辑
    init {
        // endpoint 必须以 wss:// 或 ws:// 开头
        require(endpoint.startsWith("wss://", ignoreCase = true) ||
            endpoint.startsWith("ws://", ignoreCase = true)) {
            "endpoint must use ws:// or wss://"
        }
        // ws:// 必须显式设置 allowInsecureConnection=true
        require(allowInsecureConnection || !endpoint.startsWith("ws://", ignoreCase = true)) {
            "ws:// requires allowInsecureConnection=true"
        }
    }

    // internal fun — 模块内可见函数（SDK 内部调用，外部用不到）
    internal fun requireDeviceToken(): String {
        val token = requireNotNull(tokenProvider.token()) {
            "DeviceTokenProvider returned no token"
        }
        return requireValidToken(token)
    }

    // toString 不暴露敏感信息
    override fun toString(): String = buildString { /* ... */ }

    // Builder 模式 — 为 Java 用户提供链式构造方式
    class Builder(private val endpoint: String) {
        private var tokenProvider: DeviceTokenProvider? = null
        // ... 各种属性

        // apply {} — Kotlin 作用域函数，在对象上执行代码块并返回自身
        fun token(token: String) = apply {
            tokenProvider = DeviceTokenProvider.fixed(token)
        }

        fun build(): FastVoiceConfig = FastVoiceConfig(/* 所有参数 */)
    }

    companion object {
        @JvmStatic
        fun builder(endpoint: String): Builder = Builder(endpoint)
    }
}
```

**新手重点关键字：**

| 关键字/语法 | 含义 |
|-------------|------|
| `val` | 不可变变量（只读，相当于 Java `final`） |
| `var` | 可变变量 |
| `fun` | 函数声明 |
| `fun interface` | 只有一个方法的接口，可以用 Lambda 实例化 |
| `private` | 只有本文件/本类可访问 |
| `internal` | 只有本模块可访问 |
| `companion object` | 伴生对象，放静态成员 |
| `@JvmStatic` | 生成真正的 Java 静态方法 |
| `@JvmOverloads` | 为带默认参数的函数生成 Java 重载 |
| `require()` | 前置条件检查，不满足抛 IllegalArgumentException |
| `requireNotNull()` | 非空检查 |
| `= emptyList()` | 默认参数值 |
| `String?` | 可空类型（可以是 null） |


---

### 4.2 FastVoiceClient.kt

SDK 唯一的公共入口类。这是最核心、最大的一个文件（~500 行），负责：
- WebSocket 连接管理
- 生命周期（start/stop/close）
- 会话管理（startSession/updateSession/endSession）
- 内容请求（playContent）
- 协议消息收发
- 重连逻辑

```kotlin
package com.zxkws.fastvoice

// --- 导入区 ---
import android.Manifest                    // Android 权限常量
import android.content.Context             // Android 上下文（访问系统服务）
import android.content.pm.PackageManager   // 检查权限
import android.media.AudioManager          // 音量控制
import android.os.Build                    // 设备信息（API 版本、CPU 架构）
import android.os.Handler                  // 在指定线程执行代码
import android.os.Looper                   // 线程消息循环（主线程）
// ... 内部类导入 ...
import java.io.Closeable                   // 可关闭资源接口
import java.net.Proxy                      // 网络代理设置
import java.util.concurrent.Executors       // 线程池工厂
import java.util.concurrent.ScheduledFuture // 定时任务句柄
import java.util.concurrent.TimeUnit        // 时间单位
import java.util.concurrent.atomic.*        // 原子操作类（线程安全）
import okhttp3.*                           // OkHttp WebSocket 客户端
import okio.ByteString                     // 二进制数据容器
import org.json.JSONObject                 // JSON 解析

/**
 * 唯一公共入口。保持一个实例即可持有前台语音所有权。
 */
class FastVoiceClient @JvmOverloads constructor(
    context: Context,          // Android Context（通常传 applicationContext）
    val config: FastVoiceConfig,
    private val listener: FastVoiceListener = FastVoiceListener { },
    // ↑ 默认空监听器，使用 fun interface 的 Lambda 语法
) : Closeable {
    // ↑ 实现 Closeable 接口，支持 try-with-resources 和 use {} 语法
```

#### 关键成员变量

```kotlin
    // applicationContext — 避免 Activity 泄漏，使用应用级 Context
    private val appContext = context.applicationContext

    // Handler + Looper.getMainLooper() — 在主线程执行回调
    private val mainHandler = Handler(Looper.getMainLooper())

    // AtomicBoolean — 线程安全的布尔值，多线程读写不需要加锁
    private val started = AtomicBoolean(false)   // 是否已启动
    private val closed = AtomicBoolean(false)    // 是否已永久关闭
    private val ready = AtomicBoolean(false)     // WebSocket 是否握手完成

    // AtomicReference — 线程安全的引用
    private val socket = AtomicReference<WebSocket?>(null)  // 当前 WebSocket 连接

    // AtomicInteger — 线程安全的整数
    private val reconnectAttempt = AtomicInteger(0)  // 重连尝试计数
    private val playbackId = AtomicInteger(-1)       // 当前播放 ID（-1=无播放）

    // ScheduledExecutorService — 单线程定时执行器（用于重连延迟）
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fastvoice-scheduler").apply { isDaemon = true }
        // isDaemon=true — 守护线程，不会阻止 JVM 退出
    }

    // OkHttpClient — WebSocket 的底层 HTTP 客户端
    private val httpClient = OkHttpClient.Builder()
        .apply { if (config.bypassSystemProxy) proxy(Proxy.NO_PROXY) }
        .connectTimeout(10, TimeUnit.SECONDS)   // 连接超时 10 秒
        .pingInterval(15, TimeUnit.SECONDS)     // 每 15 秒发 ping 保活
        .readTimeout(0, TimeUnit.MILLISECONDS)  // 读取永不超时（WebSocket 长连接）
        .build()
```

#### start() — 启动 SDK

```kotlin
    @Synchronized  // 同步方法，防止并发调用
    fun start(): Boolean {
        // check() — 和 require() 类似，但表达的是状态前置条件
        check(!closed.get()) { "FastVoiceClient is closed" }
        if (started.get()) return true  // 幂等：已启动则直接返回

        // 运行时权限检查
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            emitLocalError("microphone_permission_missing")
            return false
        }

        // 检查 CPU 架构
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            emitLocalError("unsupported_abi", Build.SUPPORTED_ABIS.joinToString())
            return false
        }

        // compareAndSet — 原子地「如果当前是 false，则设为 true」，保证只有一个线程成功
        if (!started.compareAndSet(false, true)) return true

        // 启动音频引擎
        if (!audio.start()) {
            started.set(false)
            return false
        }

        // 建立 WebSocket 连接
        connect(sessions.begin())
        return true
    }
```

#### WebSocket 连接与消息处理

```kotlin
    // 内部类 SocketListener 继承 OkHttp 的 WebSocketListener
    private inner class SocketListener(
        private val session: Long,  // 会话纪元（用于判断回调是否过期）
    ) : WebSocketListener() {

        // onOpen — 连接成功打开时调用
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 发送 hello 消息（协议握手第一步）
            webSocket.send(ProtocolEncoder.hello(config.wakeEnabled))
        }

        // onMessage(bytes) — 收到二进制消息（音频数据）
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // 将 Opus 编码的音频送入音频引擎解码播放
            audio.enqueueOpus(bytes.toByteArray())
        }

        // onMessage(text) — 收到文本消息（JSON 控制指令）
        override fun onMessage(webSocket: WebSocket, text: String) {
            val parsed = runCatching { JSONObject(text) }
            // runCatching {} — Kotlin 安全调用，捕获所有异常返回 Result
            parsed.fold(
                onSuccess = ::handleServerMessage,    // 解析成功，处理消息
                onFailure = { terminateProtocol(...) }, // 解析失败，终止连接
            )
        }

        // onFailure — 连接意外断开
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnected(session, webSocket, "connection_failed", t.message, t)
        }
    }
```

#### 服务端消息路由

```kotlin
    private fun handleServerMessage(message: JSONObject) {
        val type = message.strictString("type")  // 读取消息类型
        // 根据 type 分发到不同处理函数
        when (type) {
            "ready"           -> handleReady(message)       // 握手完成
            "state"           -> handleState(message)       // 状态变化
            "transcript"      -> handleTranscript(message)  // 语音转文字
            "session.ack"     -> handleSessionAck(message)  // 会话确认
            "content.ack"     -> handleContentAck(message)  // 内容确认
            "playback.start"  -> handlePlaybackStart(message) // 开始播放
            "playback.end"    -> handlePlaybackEnd(message)   // 播放结束
            "control"         -> handleControl(message)      // 控制指令
            "control.decision"-> handleControlDecision(message) // 控制决策
            "error"           -> handleError(message)        // 错误
        }
    }
```

#### 重连机制

```kotlin
    private fun scheduleReconnect(session: Long) {
        if (!started.get() || !config.autoReconnect) return
        val attempt = reconnectAttempt.getAndIncrement()
        // 指数退避：1s, 2s, 4s, 8s, 15s, 30s
        val delays = longArrayOf(1, 2, 4, 8, 15, 30)
        val delay = delays[minOf(attempt, delays.lastIndex)]
        reconnectFuture = scheduler.schedule({ connect(session) }, delay, TimeUnit.SECONDS)
    }
```

#### 事件发射

```kotlin
    // 所有事件都通过 mainHandler 投递到主线程
    private fun emit(event: FastVoiceEvent) {
        mainHandler.post {
            // runCatching — 防止用户回调抛异常导致 SDK 崩溃
            runCatching { listener.onEvent(event) }.onFailure {
                log(FastVoiceLogLevel.ERROR, "listener callback failed", it)
            }
        }
    }
```

**核心设计模式：**
1. **幂等操作**：start/stop 多次调用安全
2. **原子变量**：多线程状态管理不用重量级锁
3. **纪元（Epoch）机制**：区分"当前"和"过期"的回调
4. **主线程回调**：所有事件在 UI 线程触发，使用者不需要 runOnUiThread


---

### 4.3 FastVoiceEvent.kt

定义所有 SDK 向外发射的事件类型。

```kotlin
package com.zxkws.fastvoice

/** 状态值，经过协议验证后才发射 */
data class FastVoiceState(val value: String) {
    // data class — 自动生成 equals/hashCode/toString/copy
    companion object {
        // @JvmField — 让 Java 可以直接用 FastVoiceState.IDLE 访问
        @JvmField val IDLE = FastVoiceState("idle")           // 空闲
        @JvmField val SLEEPING = FastVoiceState("sleeping")   // 休眠
        @JvmField val LISTENING = FastVoiceState("listening") // 正在听
        @JvmField val RECOGNIZING = FastVoiceState("recognizing") // 正在识别
        @JvmField val GENERATING = FastVoiceState("generating")   // 正在生成回复
        @JvmField val SPEAKING = FastVoiceState("speaking")       // 正在说话
        @JvmField val PROMPTING = FastVoiceState("prompting")     // 正在播报
    }
}

/** 错误信息，字段原样透传 */
data class FastVoiceError @JvmOverloads constructor(
    val scope: String? = null,       // 错误范围：sdk / session / content
    val ref: String? = null,         // 关联的引用 ID
    val rev: Long? = null,           // 会话版本号
    val code: String? = null,        // 错误码
    val message: String? = null,     // 可读描述
    val recoverable: Boolean = true, // 是否可恢复
    val fallbackText: String? = null,// 备选文本
    val cause: Throwable? = null,    // 原始异常
)

/** SDK 发射的所有事件的基类 */
// sealed class — 密封类，子类必须定义在同一文件中
// 好处：when 表达式编译器能检查是否覆盖了所有情况
sealed class FastVoiceEvent {
    data class StateChanged(val state: FastVoiceState) : FastVoiceEvent()
    data class Transcript(val role: String, val text: String, val final: Boolean) : FastVoiceEvent()
    data class Error(val error: FastVoiceError) : FastVoiceEvent()
    data class SessionAck(val action: String, val id: String, val rev: Long) : FastVoiceEvent()
    data class ContentAck(val id: String) : FastVoiceEvent()
    data class PlaybackFinished(val playbackId: Int, val contentId: String?) : FastVoiceEvent()
    data class PlaybackFailed(val playbackId: Int, val contentId: String?, val code: String) : FastVoiceEvent()
}

/** 事件监听器 — 函数式接口 */
fun interface FastVoiceListener {
    fun onEvent(event: FastVoiceEvent)
}
```

**新手注意：**
- `sealed class` 让你在 `when` 表达式中覆盖所有子类，编译器会警告遗漏
- `data class` 自动生成 `equals()`、`hashCode()`、`toString()`、`copy()`
- 所有事件类型都是不可变的（只有 `val`）

---

### 4.4 SessionSnapshot.kt

会话快照——一次完整的会话状态。不是增量更新（merge patch），而是整体替换。

```kotlin
package com.zxkws.fastvoice

class SessionSnapshot @JvmOverloads constructor(
    val id: String,                              // 会话 ID
    val rev: Long,                               // 版本号（从 1 开始，严格递增）
    attributes: Map<String, Any?> = emptyMap(),  // 业务属性（SDK 不解释内容）
) {
    // 通过 StructuredAttributes.freeze() 做深拷贝和冻结
    // 调用方后续修改原 Map 不会影响 SDK 持有的数据
    val attributes: Map<String, Any?> = StructuredAttributes.freeze(attributes)

    init {
        // 验证 ID 格式：字母数字开头，最多 128 字符
        StructuredAttributes.requireIdentifier("session id", id)
        // rev 必须 >= 1
        require(rev >= 1L) { "session rev must be positive" }
    }

    // 手动实现 equals/hashCode（因为不是 data class，可以自定义不可变逻辑）
    override fun equals(other: Any?): Boolean = /* ... */
    override fun hashCode(): Int = /* ... */
    override fun toString(): String = "SessionSnapshot(id=$id, rev=$rev, attributes=$attributes)"

    // Builder 模式（给 Java 用户）
    class Builder(private val id: String, private val rev: Long) {
        private val attributes = linkedMapOf<String, Any?>()

        fun putAttribute(name: String, value: Any?) = apply { attributes[name] = value }
        fun putAllAttributes(values: Map<String, Any?>) = apply { attributes.putAll(values) }
        fun build(): SessionSnapshot = SessionSnapshot(id, rev, attributes)
    }

    companion object {
        @JvmStatic
        fun builder(id: String, rev: Long): Builder = Builder(id, rev)
    }
}
```

---

### 4.5 ContentRequest.kt

内容请求——请求服务端播放特定内容。

```kotlin
package com.zxkws.fastvoice

class ContentRequest @JvmOverloads constructor(
    val id: String,                              // 幂等请求 ID（重试安全）
    val key: String,                             // 内容键（由服务端解释含义）
    val session: SessionRef? = null,             // 可选：绑定到哪个会话版本
    attributes: Map<String, Any?> = emptyMap(),  // 扩展属性
) {
    val attributes: Map<String, Any?> = StructuredAttributes.freeze(attributes)

    init {
        StructuredAttributes.requireIdentifier("content request id", id)
        StructuredAttributes.requireIdentifier("content key", key)
    }

    // Builder 模式
    class Builder(private val id: String, private val key: String) {
        private var session: SessionRef? = null
        private val attributes = linkedMapOf<String, Any?>()

        fun session(value: SessionRef?) = apply { session = value }
        fun putAttribute(name: String, value: Any?) = apply { attributes[name] = value }
        fun build(): ContentRequest = ContentRequest(id, key, session, attributes)
    }

    companion object {
        @JvmStatic
        fun builder(id: String, key: String): Builder = Builder(id, key)
    }
}
```

---

### 4.6 SessionRef.kt

会话引用——精确指向某个会话的某个版本。

```kotlin
package com.zxkws.fastvoice

// data class — 自动 equals/hashCode/toString
data class SessionRef(
    val id: String,   // 会话 ID
    val rev: Long,    // 版本号
) {
    init {
        StructuredAttributes.requireIdentifier("session id", id)
        require(rev >= 1L) { "session rev must be positive" }
    }

    companion object {
        // Java 友好的工厂方法
        @JvmStatic
        fun of(id: String, rev: Long): SessionRef = SessionRef(id, rev)
    }
}
```

---

### 4.7 StructuredAttributes.kt

属性验证和深度冻结工具。确保 SDK 持有的属性数据不会被外部代码修改，并验证大小和格式限制。

```kotlin
package com.zxkws.fastvoice

import com.zxkws.fastvoice.internal.JsonEncoder
import java.util.Collections
import kotlin.text.Charsets.UTF_8

// internal object — 模块内可见的单例对象
internal object StructuredAttributes {
    // Regex — 正则表达式
    // ID 格式：字母/数字开头，后面可以有 ._ :-，最多 128 字符
    private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val ATTRIBUTE_KEY_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.:-]{0,127}$")

    // 验证限制常量
    private const val MAX_DEPTH = 4           // 最多 4 层嵌套
    private const val MAX_NODES = 512         // 最多 512 个节点
    private const val MAX_COLLECTION_SIZE = 128   // 对象/数组最多 128 项
    private const val MAX_STRING_CODE_POINTS = 2_048  // 单个字符串最多 2048 字符
    private const val MAX_ENCODED_BYTES = 16_384      // JSON 编码后最多 16KB

    // freeze() — 深拷贝并冻结 Map，返回不可修改的副本
    fun freeze(value: Map<*, *>): Map<String, Any?> {
        val result = freezeObject(value, 0, ValidationState())
        // 验证 JSON 编码后的大小
        require(JsonEncoder.encode(result).toByteArray(UTF_8).size <= MAX_ENCODED_BYTES) {
            "attributes exceed 16 KiB"
        }
        return result
    }

    // 递归冻结每个值
    private fun freezeValue(value: Any?, depth: Int, state: ValidationState): Any? = when (value) {
        null, is Boolean, is Byte, is Short, is Int, is Long -> {
            countNode(depth, state)  // 基本类型直接返回
            value
        }
        is String -> { /* 验证长度和控制字符 */ value }
        is Float, is Double -> { /* 验证有限数 */ value }
        is Map<*, *> -> freezeObject(value, depth, state)  // 递归冻结对象
        is Iterable<*> -> freezeList(value.toList(), depth, state) // 递归冻结列表
        is Array<*> -> freezeList(value.asList(), depth, state)
        else -> throw IllegalArgumentException("unsupported attribute value")
    }

    // Collections.unmodifiableMap/List — Java 标准的不可变包装
    // 对返回的 Map/List 调用 put/add 会抛 UnsupportedOperationException
}
```

**为什么要"冻结"？**
防止调用方这样做：
```kotlin
val map = mutableMapOf("key" to "value1")
client.startSession(SessionSnapshot("id", 1, map))
map["key"] = "value2"  // 如果不冻结，SDK 内部持有的数据也会变！
```
冻结后，SDK 内部的副本不受外部修改影响。


---

## 5. 内部实现（internal 包）

`internal` 包中的类对 SDK 使用者不可见，只有 SDK 模块自身可以访问。它们负责协议编解码、音频引擎、状态机等底层逻辑。

### 5.1 CurrentProtocol.kt

定义 FastVoice 唯一协议的常量和验证规则。

```kotlin
package com.zxkws.fastvoice.internal

// data class — 存放服务端 ready 消息的字段
internal data class ReadyMessage(
    val connectionId: String?,       // 连接 ID
    val wakeWords: List<String>?,    // 服务端支持的唤醒词列表
    val controlTimeoutMs: Long?,     // 控制指令超时时间
)

// internal object — 模块内单例，不会被 SDK 外部访问
internal object CurrentProtocol {
    const val INPUT_RATE = 16_000    // 麦克风采集采样率 16kHz
    const val OUTPUT_RATE = 48_000   // 播放采样率 48kHz
    const val FRAME_MS = 20          // 每帧 20ms
    const val MAX_CAPTURE_PRE_ROLL_MS = 1_800  // 最大预录时长 1.8 秒

    // setOf — 不可变集合
    val READY_FIELDS = setOf("type", "connection_id", "wake_words", "control_timeout_ms")

    val STATE_VALUES = setOf(
        "idle", "sleeping", "listening", "recognizing",
        "generating", "speaking", "prompting",
    )

    // 验证 ready 消息是否合法
    fun acceptsReady(ready: ReadyMessage, supportedWakeWords: Set<String>): Boolean {
        val words = ready.wakeWords ?: return false
        return ready.connectionId?.isNotBlank() == true &&
            words.none(String::isBlank) &&           // 没有空白唤醒词
            words.distinct().size == words.size &&    // 没有重复
            words.all(supportedWakeWords::contains) && // 都是本地支持的
            ready.controlTimeoutMs?.let { it > 0L } == true
    }
}
```

---

### 5.2 ProtocolEncoder.kt

负责将 SDK 的操作编码为 JSON 字符串发送给服务端。

```kotlin
internal object ProtocolEncoder {
    // @JvmSynthetic — 对 Java 隐藏（只有 Kotlin 能调用）
    @JvmSynthetic
    fun hello(wakeEnabled: Boolean): String = JsonEncoder.encode(
        linkedMapOf("type" to "hello", "wake" to wakeEnabled)
    )
    // linkedMapOf — 保持插入顺序的 Map

    @JvmSynthetic
    fun sessionStart(snapshot: SessionSnapshot): String = JsonEncoder.encode(
        linkedMapOf(
            "type" to "session.start",
            "id" to snapshot.id,
            "rev" to snapshot.rev,
            "attributes" to snapshot.attributes,
        )
    )

    // 类似的还有 sessionUpdate, sessionEnd, contentPlay, wake,
    // controlCandidate, turnCancel, controlResult, playbackProgress,
    // playbackFinished, playbackFailed 等方法
}

// JsonEncoder — 无依赖的小型 JSON 序列化器
internal object JsonEncoder {
    fun encode(value: Any?): String = buildString { appendValue(value) }
    // buildString {} — 创建 StringBuilder 并返回最终字符串

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendString(value)  // 处理转义
            is Boolean -> append(if (value) "true" else "false")
            is Map<*, *> -> { /* 输出 {"key":value,...} */ }
            is Iterable<*> -> { /* 输出 [item,...] */ }
            // ...
        }
    }
}
```

**为什么不用 Gson/Moshi？**
这个 JSON 编码器没有外部依赖，可以在纯 JVM 单元测试中运行，且 SDK 只需要编码（不需要反序列化复杂对象）。


---

### 5.3 AudioEngine.kt

音频引擎——SDK 中最复杂的内部类（~700 行），管理麦克风、扬声器、Opus 编解码、本地 KWS 和回声消除。

```kotlin
internal class AudioEngine(
    context: Context,
    private val routeToSpeaker: Boolean,  // 是否路由到扬声器
    private val callback: Callback,       // 回调接口（通知上层）
) {
    // 内部回调接口
    interface Callback {
        fun onWakeWord(word: String): Boolean       // 检测到唤醒词
        fun onLocalCommandCandidate(generation: Int, text: String)  // 本地控制词
        fun onUplinkPacket(packet: ByteArray)       // Opus 编码后的上行包
        fun onPlaybackStarted()                     // 播放开始
        fun onPlaybackProgress(generation: Int, playedMs: Long) // 播放进度
        fun onPlaybackFinished(generation: Int)     // 播放完成
        fun onPlaybackFailed(generation: Int, reason: String) // 播放失败
        fun onTrace(message: String)                // 调试跟踪
        fun onDiagnostic(code: String, message: String, error: Throwable?) // 诊断
    }

    companion object {
        const val INPUT_RATE = 16_000        // 麦克风 16kHz
        const val OUTPUT_RATE = 48_000       // 扬声器 48kHz
        const val FRAME_MS = 20              // 帧长 20ms
        const val FRAME_SAMPLES = INPUT_RATE / 1_000 * FRAME_MS  // = 320 个采样点
        const val FRAME_BYTES = FRAME_SAMPLES * 2  // = 640 字节（16bit PCM）

        // 支持的唤醒词（中文）
        val SUPPORTED_WAKE_WORDS: Set<String> = linkedSetOf(
            "布丁", "布丁布丁", "你好布丁", "布丁你好",
        )
    }
```

#### 音频引擎的三个线程

```kotlin
    // 1. 录音线程（fastvoice-recorder）
    //    - 从 AudioRecord 读取 PCM
    //    - 经过 AEC 处理
    //    - 送入 KWS 队列
    //    - 维护预录缓冲
    //    - Opus 编码后通过回调发送上行

    // 2. 播放线程（fastvoice-player）
    //    - 从 playbackQueue 取出 PCM
    //    - 写入 AudioTrack
    //    - 同时把 PCM 作为回声参考送入 AEC
    //    - 追踪播放进度

    // 3. KWS 线程（fastvoice-kws）
    //    - 从 kwsQueue 取出音频帧
    //    - 送入 sherpa-onnx 模型推理
    //    - 检测到关键词后通知上层
```

#### 录音线程核心逻辑

```kotlin
    private fun startRecorder(runGeneration: Long, echoCanceller: WebRtcEchoCanceller): Boolean {
        // AudioRecord — Android 底层录音 API
        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_RATE,                          // 采样率
            AudioFormat.CHANNEL_IN_MONO,          // 单声道
            AudioFormat.ENCODING_PCM_16BIT,       // 16位 PCM
        )

        // 创建并开始录音
        val initialCapture = AudioRecord(
            MediaRecorder.AudioSource.MIC,  // 音源：麦克风（不是 VOICE_COMMUNICATION）
            INPUT_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 6_400),
        )
        initialCapture.startRecording()

        // 启动录音处理线程
        recorderThread = Thread({
            val frame = ByteArray(FRAME_BYTES)          // 一帧 640 字节
            val preRoll = ArrayDeque<ByteArray>(MAX_PRE_ROLL_FRAMES) // 预录环形缓冲

            while (isActive(runGeneration)) {
                // 1. 读取一帧 PCM
                currentRecorder.read(frame, 0, frame.size)

                // 2. AEC 处理：消除回声
                val processed = echoCanceller.processCapture(captured)

                // 3. 送入 KWS 队列（播放时用增益放大的原始 MIC）
                val kwsFrame = if (hasRecentRender) boostPcm16(captured, 4) else speechFrame
                kwsQueue.offer(kwsFrame)

                // 4. 维护预录缓冲
                preRoll.addLast(speechFrame)
                while (preRoll.size > MAX_PRE_ROLL_FRAMES) preRoll.removeFirst()

                // 5. 如果正在采集，Opus 编码并发送
                if (uplinkEnabled.get()) {
                    val encoded = encoder.encode(speechFrame, ...)
                    callback.onUplinkPacket(encoded)
                }
            }
        }, "fastvoice-recorder")
    }
```

#### 播放线程核心逻辑

```kotlin
    // AudioTrack — Android 底层播放 API
    private fun createTrack(): AudioTrack? {
        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // 语音通话用途
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH) // 内容类型：语音
                .build(),
            AudioFormat.Builder()
                .setSampleRate(OUTPUT_RATE)              // 48kHz
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO) // 单声道
                .build(),
            maxOf(minBuffer, 19_200),  // 缓冲区大小
            AudioTrack.MODE_STREAM,     // 流模式（边写边播）
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    // 播放线程循环：
    // 1. 等待 playbackQueue 中有数据
    // 2. 预缓冲 100ms
    // 3. 调用 track.play() 开始播放
    // 4. 循环写入 PCM 并同步送入 AEC 作为参考信号
    // 5. 收到 End 标记后等待 AudioTrack 播放完毕
```

#### 音频路由配置

```kotlin
    private fun configureAudioRoute() {
        // AudioManager.MODE_IN_COMMUNICATION — 通话模式（低延迟）
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // 请求音频焦点（告诉系统我正在播放重要音频）
        audioManager.requestAudioFocus(focusRequest)
        // 路由到扬声器（而不是听筒）
        if (Build.VERSION.SDK_INT >= 31) {
            audioManager.setCommunicationDevice(speaker)
        } else {
            audioManager.isSpeakerphoneOn = true
        }
    }
```

#### PCM 增益函数

```kotlin
// 将 16 位 PCM 按倍数放大（带饱和限幅）
internal fun boostPcm16(pcm: ByteArray, gain: Int): ByteArray {
    val boosted = ByteArray(pcm.size)
    var offset = 0
    while (offset + 1 < pcm.size) {
        // Little-endian 读取 16 位有符号整数
        val low = pcm[offset].toInt() and 0xff
        val high = pcm[offset + 1].toInt() shl 8
        val sample = (low or high).toShort().toInt()
        // 放大并限幅到 Short 范围 [-32768, 32767]
        val scaled = (sample.toLong() * gain)
            .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
        boosted[offset] = scaled.toByte()
        boosted[offset + 1] = (scaled shr 8).toByte()
        offset += 2
    }
    return boosted
}
```


---

### 5.4 WebRtcEchoCanceller.kt

软件回声消除器——使用 WebRTC M131 的 AEC3 算法。通过 JNI 调用 C++ 原生库。

```kotlin
internal class WebRtcEchoCanceller(
    private val captureRate: Int,   // 麦克风采样率 (16kHz)
    private val renderRate: Int,    // 播放采样率 (48kHz)
) : Closeable {

    companion object {
        private const val SUBFRAME_MS = 10        // WebRTC 原生 10ms 子帧
        const val DEFAULT_DELAY_MS = 240          // 默认回声延迟估计
        const val RENDER_TAIL_MS = 600L           // 播放后多久还认为有回声

        init {
            // System.loadLibrary — 加载 .so 原生库
            System.loadLibrary("webrtc-audio-processing-2")  // WebRTC 核心
            System.loadLibrary("fastvoice_webrtc_aec3")       // JNI 桥接层
        }
    }

    // handle — 指向 C++ Processor 对象的指针（以 Long 存储）
    private var handle = nativeCreate(captureRate, renderRate)

    // 喂入播放 PCM 作为回声参考
    @Synchronized
    fun acceptRender(pcm: ByteArray, offset: Int, length: Int) {
        // 每凑满一个 10ms 子帧就调用一次 native 处理
        // bytesToShorts — 将 ByteArray 转为 ShortArray（JNI 接口用 short[]）
    }

    // 处理一个 20ms 麦克风帧（分成两个 10ms 子帧处理）
    @Synchronized
    fun processCapture(pcm: ByteArray): ByteArray {
        require(pcm.size == captureBytes * 2) // 必须恰好 20ms
        // 对每个 10ms 子帧调用 nativeProcessCapture
        // 返回消除回声后的 PCM
    }

    // 判断最近是否有播放（600ms 内有 render 就认为可能存在回声）
    fun hasRecentRender(): Boolean

    // external fun — JNI 方法声明，实际实现在 C++ 中
    private external fun nativeCreate(captureRate: Int, renderRate: Int): Long
    private external fun nativeProcessRender(handle: Long, input: ShortArray): Int
    private external fun nativeProcessCapture(handle: Long, input: ShortArray, output: ShortArray, delayMs: Int): Int
    private external fun nativeDelayEstimateMs(handle: Long): Int
    private external fun nativeClose(handle: Long)
}
```

**AEC 工作原理简述：**
1. 扬声器播放的音频同时被送入 AEC 作为"参考信号"
2. 麦克风录到的声音 = 用户说话 + 扬声器回声
3. AEC 通过自适应滤波器估计并减去回声分量
4. 输出"干净"的用户语音

---

### 5.5 LocalCommandSpotter.java

本地中文关键词检测器。使用 sherpa-onnx（一个高效的端侧语音识别引擎）。

```java
final class LocalCommandSpotter {
    // 关键词路由枚举
    enum KeywordRoute { WAKE, CONTROL, IGNORE }

    // 模型文件目录（在 assets 中）
    private static final String DIR =
        "fastvoice/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01";

    // 控制词列表（用户说这些词可以控制播放）
    private static final Set<String> CONTROL_WORDS = new LinkedHashSet<>(Arrays.asList(
        "换一个", "换个", "下一个", "停止", "停一下",
        "别说", "闭嘴", "等等", "打住", "重来",
        "退下", "退下吧", "继续"
    ));

    // 路由分类：判断检测到的词是唤醒词还是控制词
    static KeywordRoute routeKeyword(String keyword, Collection<String> enabledWakeWords) {
        if (enabledWakeWords.contains(keyword)) return KeywordRoute.WAKE;
        return CONTROL_WORDS.contains(keyword) ? KeywordRoute.CONTROL : KeywordRoute.IGNORE;
    }

    // 初始化模型
    public synchronized void init(AssetManager assets, Collection<String> wakeWords) {
        // 创建 Transducer 模型配置（encoder + decoder + joiner）
        // 这是一个流式端到端 ASR 模型（zipformer2 架构）
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig(
            DIR + "/encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx",  // int8 量化
            DIR + "/decoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx",
            DIR + "/joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx"
        );
        // KeywordSpotterConfig — KWS 配置
        // 1.5f = keywords_score（分数阈值，越低越容易触发）
        // 0.25f = keywords_threshold
    }

    // 接收一帧 PCM，返回检测到的关键词（或 null）
    public synchronized String accept(byte[] pcm) {
        // 1. 将 byte[] PCM 转为 float[] 归一化采样 (-1.0 ~ 1.0)
        float[] samples = new float[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int lo = pcm[i * 2] & 0xff;
            int hi = pcm[i * 2 + 1] << 8;
            samples[i] = (short)(lo | hi) / 32768.0f;
        }
        // 2. 送入模型
        activeStream.acceptWaveform(samples, 16000);
        // 3. 检查是否有结果
        while (spotter.isReady(activeStream)) {
            spotter.decode(activeStream);
            KeywordSpotterResult result = spotter.getResult(activeStream);
            if (result.getKeyword() != null && !result.getKeyword().isEmpty()) {
                spotter.reset(activeStream);
                return result.getKeyword();
            }
        }
        return null;
    }
}
```

---

### 5.6 KeywordLineRegistry.java

管理关键词文件中的发音行。sherpa-onnx 的 keywords.txt 格式是 `发音序列@标签`。

```java
final class KeywordLineRegistry {
    private static final class Entry {
        final String label;  // 关键词标签（如"布丁"）
        final String line;   // 完整行（如"b u d ing@布丁"）
    }

    // 从 assets 读取所有关键词行
    void load(BufferedReader reader) throws IOException { /* ... */ }

    // 只渲染启用的关键词行
    String render(Collection<String> enabledLabels) {
        StringBuilder result = new StringBuilder();
        for (Entry entry : entries) {
            if (enabledLabels.contains(entry.label))
                result.append(entry.line).append('\n');
        }
        return result.toString();
    }
}
```


---

### 5.7 KeywordRoutingPolicy.kt

关键词路由策略——决定检测到的关键词应该触发唤醒、控制还是忽略。

```kotlin
internal object KeywordRoutingPolicy {
    enum class Dispatch { WAKE, CONTROL, IGNORE }
    enum class ControlTarget { SERVER_PLAYBACK, NONE }

    // 核心路由规则：
    // - WAKE：没在播放、唤醒已就绪时，触发唤醒
    // - CONTROL：正在播放时，触发控制（暂停/停止等）
    // - IGNORE：其他情况忽略
    fun dispatch(
        route: LocalCommandSpotter.KeywordRoute?,
        playbackOrPromptExpected: Boolean,
        playbackActive: Boolean,
        wakeArmed: Boolean,
    ): Dispatch = when (route) {
        KeywordRoute.WAKE -> {
            if (!playbackOrPromptExpected && !playbackActive && wakeArmed)
                Dispatch.WAKE else Dispatch.IGNORE
        }
        KeywordRoute.CONTROL -> Dispatch.CONTROL
        else -> Dispatch.IGNORE
    }
}
```

---

### 5.8 SessionOperationState.kt

会话操作状态机——跟踪期望状态（desired）和已确认状态（acknowledged）。

```kotlin
internal class SessionOperationState {
    // 接受结果
    data class Acceptance(
        val accepted: Boolean,      // SDK 是否接受了调用
        val exactRetry: Boolean,    // 是否是完全相同的重试
        val errorCode: String?,     // 拒绝原因
    )

    // 确认效果
    data class AckEffect(
        val matched: Boolean,       // 是否匹配当前操作
        val startAccepted: Boolean, // 是否是 start 确认（开放录音）
        val ended: Boolean,         // 会话是否结束
    )

    private var desiredSession: SessionSnapshot? = null      // 期望状态
    private var acknowledgedSession: SessionSnapshot? = null // 服务端已确认状态

    // start() — 验证并记录新会话
    // update() — 验证并更新属性
    // end() — 标记结束
    // acknowledge() — 处理服务端确认
    // fail() — 处理服务端错误（回滚到上一个确认状态）
    // captureAllowed() — 判断当前是否允许采集
}
```

---

### 5.9 ContentRequestState.kt

内容请求状态——管理待确认的内容请求队列。

```kotlin
internal class ContentRequestState {
    enum class EnqueueResult { ACCEPTED, EXACT_RETRY, ID_CONFLICT }

    // LinkedHashMap 保持插入顺序（重连时按原顺序重发）
    private val requests = linkedMapOf<String, ContentRequest>()

    fun enqueue(request: ContentRequest): EnqueueResult  // 入队
    fun pending(): List<ContentRequest>                   // 获取所有待确认请求
    fun complete(id: String): Boolean                     // 标记完成
    fun cancelAll(): List<ContentRequest>                 // 取消所有（会话结束时）
}
```

---

### 5.10 SessionAudioPolicy.kt

纯逻辑策略——根据会话状态决定是否允许采集和 KWS。

```kotlin
internal object SessionAudioPolicy {
    // 只有会话已确认且未结束时，才允许录音
    fun captureAllowed(
        hasActiveSession: Boolean,
        endPending: Boolean,
        sessionAcknowledgedOnConnection: Boolean,
    ): Boolean = hasActiveSession && !endPending && sessionAcknowledgedOnConnection

    // KWS 启用条件更严格：还要求 started + ready + wakeRequested
    fun wakeKwsEnabled(...): Boolean = started && ready && wakeRequested && captureAllowed(...)
}
```

---

### 5.11 PlaybackTerminalState.kt

播放终态管理——确保每次播放只报告一次成功或失败。

```kotlin
internal class PlaybackTerminalState {
    enum class FailureResult { RECORDED, ALREADY_FAILED, STALE_OR_FINISHED }

    private var terminal: String? = null  // null=进行中, "finished", "failed", "stopped"

    fun begin(generation: Int, epoch: Long)   // 开始新播放
    fun finish(generation: Int, epoch: Long): Boolean  // 标记成功（必须 terminal==null）
    fun fail(generation: Int, epoch: Long, reason: String): FailureResult  // 标记失败
    fun invalidate(generation: Int, epoch: Long)  // 外部中断（不报告事件）
}
```

---

### 5.12 PlaybackFramePolicy.kt

播放帧验证——极简策略。

```kotlin
internal object PlaybackFramePolicy {
    // 解码后采样数必须精确等于预期（固定帧长）
    fun acceptsDecodedFrame(samples: Int, expectedSamples: Int): Boolean =
        samples == expectedSamples

    // 必须写入过数据才能报告完成（防止空流误报成功）
    fun canFinish(bytesWritten: Long): Boolean = bytesWritten > 0L
}
```

---

### 5.13 CapturePreRollPolicy.kt

采集预录策略——计算预录帧数。

```kotlin
internal object CapturePreRollPolicy {
    // 根据请求时长计算帧数
    fun frameCount(requestedMs: Int, wakeCapturePending: Boolean, ...): Int {
        // 如果是唤醒触发的采集，用最大预录时长（1.8s）
        val bounded = if (wakeCapturePending) maxMs else requestedMs.coerceAtMost(maxMs)
        // 向上取整到帧边界
        return if (bounded == 0) 0 else (bounded + frameMs - 1) / frameMs
    }

    // 计算从环形缓冲的哪个位置开始发送
    fun startIndex(bufferedFrames: Int, requestedFrames: Int): Int =
        (bufferedFrames - requestedFrames).coerceAtLeast(0)
}
```

---

### 5.14 ClientSessionEpoch.kt

会话纪元——用原子递增的 Long 值区分"当前"和"过期"的 WebSocket 回调。

```kotlin
internal class ClientSessionEpoch {
    private val epoch = AtomicLong()

    fun begin(): Long = epoch.incrementAndGet()      // 开始新纪元
    fun invalidate() { epoch.incrementAndGet() }     // 作废当前纪元
    fun isCurrent(token: Long): Boolean = token == epoch.get()

    // 只有当前纪元的回调才会被执行
    fun runIfCurrent(token: Long, effect: () -> Unit): Boolean {
        if (token != epoch.get()) return false
        effect()
        return true
    }
}
```

**为什么需要纪元？**
WebSocket 回调是异步的。用户调用 `stop()` 后，旧连接的回调可能还没到达。纪元机制让这些"过期"回调被安全忽略。

---

### 5.15 LocalCommandPrePauseState.kt

本地控制词预暂停状态——管理一次"暂停等待服务端确认"的生命周期。

```kotlin
internal class LocalCommandPrePauseState {
    enum class Outcome { IGNORED, ACCEPTED_HOLD, CLEARED, RESUME }

    // begin — KWS 检测到控制词，暂停播放
    // decide — 服务端回复接受/拒绝
    //   ACCEPTED_HOLD：确认执行，保持暂停
    //   RESUME：拒绝，恢复播放
    // timeout — 超时未收到回复，自动恢复
    // clear — 外部重置
}
```

---

### 5.16 LocalCommandTimeoutPolicy.kt

控制词超时策略——根据服务端广播的超时时间加上客户端宽限期。

```kotlin
internal object LocalCommandTimeoutPolicy {
    const val DEFAULT_SERVER_TIMEOUT_MS = 800L
    const val ACCEPTED_ACTION_TIMEOUT_MS = 1_500L
    private const val CLIENT_GRACE_MS = 250L  // 额外宽限期（网络延迟）

    fun clientTimeoutMs(advertisedServerTimeoutMs: Long): Long =
        advertisedServerTimeoutMs.coerceIn(200L, 3_000L) + CLIENT_GRACE_MS
}
```

---

### 5.17 ReplaceOnSuccess.java

安全资源替换——只有新资源创建成功后才替换旧资源。

```java
final class ReplaceOnSuccess<T> {
    interface Factory<T> { T create(); }
    private T current;

    // 创建新的，成功后替换，返回旧的（调用方负责释放旧资源）
    synchronized T replace(Factory<T> factory) {
        T replacement = factory.create();
        T previous = current;
        current = replacement;
        return previous;
    }
}
```

---

### 5.18 AudioTrackResumePolicy.java

AudioTrack 恢复策略——判断 `play()` 调用是否真正成功。

```java
final class AudioTrackResumePolicy {
    enum Decision { RESUMED, FAIL }

    // 必须 play() 调用成功 且 playState 变为 PLAYING 才算恢复成功
    static Decision classify(boolean playCallSucceeded, boolean playStatePlaying) {
        return playCallSucceeded && playStatePlaying ? Decision.RESUMED : Decision.FAIL;
    }
}
```

---

### 5.19 AudioWriteRecoveryPolicy.java

AudioTrack 写入恢复策略——处理写入 0、短写入等异常情况。

```java
final class AudioWriteRecoveryPolicy {
    enum Decision { COMPLETE, RETRY, FAIL }

    // 规则：
    // - written == expected → COMPLETE（正常）
    // - written == 0 且是暂停导致 → RETRY
    // - written == 0 且连续超过阈值 → FAIL
    // - 负值或短写入 → FAIL
    Decision onWriteResult(int written, int expected, boolean pauseAffectedWrite) { ... }
}
```

---

### 5.20 AudioReadRecoveryPolicy.java

AudioRecord 读取恢复策略——限制连续失败次数，防止坏 HAL 无限重试。

```java
final class AudioReadRecoveryPolicy {
    enum Decision { REOPEN, ABORT }

    // 连续失败不超过 maxReopenAttempts 次 → REOPEN（重新打开录音）
    // 超过 → ABORT（彻底放弃）
    // 连续成功 stableFramesToReset 帧后重置失败计数
    Decision onReadFailure() { ... }
    void onFrameRead() { ... }
}
```


---

## 6. Native C++ / JNI 层

### 6.1 webrtc_aec3_jni.cpp

C++ JNI 桥接层——把 WebRTC 的 AudioProcessing API 暴露给 Kotlin。

```cpp
#include <jni.h>        // JNI 头文件（Java Native Interface）
#include "api/audio/audio_processing.h"  // WebRTC 音频处理 API
#include "api/scoped_refptr.h"            // WebRTC 智能指针

namespace {

// Processor 结构体——持有一个 WebRTC AudioProcessing 实例
struct Processor {
    Processor(int capture_rate_hz, int render_rate_hz)
        : capture_stream(capture_rate_hz, 1),   // 麦克风流配置
          render_stream(render_rate_hz, 1) {     // 播放流配置

        // 配置 AEC3
        webrtc::AudioProcessing::Config config;
        config.echo_canceller.enabled = true;         // 启用回声消除
        config.echo_canceller.mobile_mode = false;    // 非移动模式（更高质量）
        config.high_pass_filter.enabled = true;       // 启用高通滤波（去低频噪声）

        // 创建 AudioProcessing 实例
        apm = webrtc::AudioProcessingBuilder().SetConfig(config).Create();
    }

    rtc::scoped_refptr<webrtc::AudioProcessing> apm;  // WebRTC 处理器
    webrtc::StreamConfig capture_stream;               // 麦克风流参数
    webrtc::StreamConfig render_stream;                // 播放流参数
};

} // namespace

// JNI 函数命名规则：Java_包名_类名_方法名（点换下划线）
extern "C" JNIEXPORT jlong JNICALL
Java_com_zxkws_fastvoice_internal_WebRtcEchoCanceller_nativeCreate(
    JNIEnv* env, jobject, jint capture_rate_hz, jint render_rate_hz) {
    // 创建 Processor 并将指针转为 jlong 返回给 Java
    return reinterpret_cast<jlong>(new Processor(capture_rate_hz, render_rate_hz));
}

// 处理播放参考信号
extern "C" JNIEXPORT jint JNICALL
Java_com_zxkws_fastvoice_internal_WebRtcEchoCanceller_nativeProcessRender(
    JNIEnv* env, jobject, jlong handle, jshortArray input) {
    Processor* processor = reinterpret_cast<Processor*>(handle);
    // GetShortArrayElements — JNI 函数，获取 Java 数组的 C 指针
    jshort* samples = env->GetShortArrayElements(input, nullptr);
    // ProcessReverseStream — 喂入参考信号（扬声器播放的音频）
    processor->apm->ProcessReverseStream(
        reinterpret_cast<int16_t*>(samples), ...);
    env->ReleaseShortArrayElements(input, samples, JNI_ABORT);
    // JNI_ABORT — 不回写修改到 Java 数组
}

// 处理麦克风采集（消除回声）
extern "C" JNIEXPORT jint JNICALL
Java_com_zxkws_fastvoice_internal_WebRtcEchoCanceller_nativeProcessCapture(
    JNIEnv* env, jobject, jlong handle,
    jshortArray input, jshortArray output, jint delay_ms) {
    // set_stream_delay_ms — 设置估计的回声延迟
    processor->apm->set_stream_delay_ms(delay_ms);
    // ProcessStream — 处理麦克风信号，输出消除回声后的结果
    processor->apm->ProcessStream(input_samples, ..., output_samples);
}

// 释放资源
extern "C" JNIEXPORT void JNICALL
Java_com_zxkws_fastvoice_internal_WebRtcEchoCanceller_nativeClose(
    JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Processor*>(handle);  // 释放 C++ 对象
}
```

**JNI 关键概念：**

| 概念 | 解释 |
|------|------|
| `JNIEnv*` | JNI 环境指针，调用 JNI 函数必须通过它 |
| `jobject` | Java 对象引用（这里是调用 native 方法的对象） |
| `jlong` | Java long 类型（用来存 C++ 指针） |
| `jshortArray` | Java short[] 类型 |
| `reinterpret_cast` | C++ 类型强转（指针↔数字） |
| `GetShortArrayElements` | 获取 Java 数组的原生内存指针 |
| `ReleaseShortArrayElements` | 释放获取的指针 |

---

### 6.2 build-webrtc-native.sh

编译 WebRTC AEC3 原生库的 Shell 脚本。

```bash
#!/bin/zsh
set -euo pipefail
# set -e：命令失败立即退出
# set -u：使用未定义变量报错
# set -o pipefail：管道中任何命令失败都算失败

# 1. 定位 Android NDK
ndk_version=27.3.13750724
toolchain_dir="$ndk_dir/toolchains/llvm/prebuilt/$host_tag"

# 2. 克隆 WebRTC 音频处理源码
git clone --depth 1 --branch v2.1 \
    "https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing.git"

# 3. 使用 Meson 构建系统交叉编译
#    --cross-file — 交叉编译配置（指定 ARM64 编译器）
meson setup "$build_dir" "$source_dir" \
    --cross-file "$cross_file" \
    --wrap-mode=forcefallback  # 自动下载缺失依赖（如 abseil）
meson compile -C "$build_dir"

# 4. 编译 JNI 桥接 .so
"$toolchain_dir/bin/aarch64-linux-android26-clang++" \
    -shared -fPIC -O3 -std=c++17 \     # 共享库、位置无关代码、O3 优化
    "$native_dir/webrtc_aec3_jni.cpp" \
    -l:libwebrtc-audio-processing-2.so \ # 链接 WebRTC 库
    -o "$jni_dir/libfastvoice_webrtc_aec3.so"

# 5. Strip 符号（减小 .so 体积）
"$toolchain_dir/bin/llvm-strip" "$jni_dir/"*.so

# 最终产物：
# - libwebrtc-audio-processing-2.so  ← WebRTC 核心库
# - libfastvoice_webrtc_aec3.so      ← JNI 桥接层
# - libc++_shared.so                 ← C++ 标准库
```

**交叉编译 = 在电脑（x86）上编译出手机（ARM64）能运行的二进制文件。**


---

## 7. 示例模块（sample）

### 7.1 sample/build.gradle.kts

示例应用的构建脚本。

```kotlin
plugins {
    alias(libs.plugins.android.application)  // 应用插件（输出 APK）
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zxkws.fastvoice.sample"  // 示例 App 包名
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zxkws.fastvoice.sample"  // APK 唯一标识
        minSdk = 24
        targetSdk = 35        // 目标 SDK（影响运行时行为）
        versionCode = 1       // 内部版本号（整数，每次发布递增）
        versionName = providers.gradleProperty("VERSION_NAME").get()  // 显示版本

        ndk { abiFilters += "arm64-v8a" }
    }
}

dependencies {
    // 根据是否设置了 usePublishedSdk 决定依赖方式
    if (providers.gradleProperty("usePublishedSdk").isPresent) {
        // 从 Maven 仓库引用（验证发布后的 SDK 是否可用）
        implementation("com.github.zxkws:fastvoice-android-sdk:${版本}")
    } else {
        // 直接依赖本地模块（开发时）
        implementation(project(":fastvoice-sdk"))
    }
}
```

---

### 7.2 sample/AndroidManifest.xml

示例应用的清单文件。

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:label="FastVoice SDK Sample"      <!-- 应用名 -->
        android:supportsRtl="true"                <!-- 支持从右到左布局 -->
        android:theme="@android:style/Theme.Material.Light.NoActionBar"  <!-- 主题 -->
        android:usesCleartextTraffic="true">      <!-- 允许明文流量（ws://调试用） -->

        <activity
            android:name=".MainActivity"          <!-- 主 Activity -->
            android:exported="true">              <!-- 可被外部启动 -->
            <intent-filter>
                <!-- MAIN + LAUNCHER = 出现在桌面图标列表中 -->
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**关键 Manifest 概念：**

| 属性 | 含义 |
|------|------|
| `applicationId` | APK 在设备上的唯一标识 |
| `android:exported="true"` | 允许其他应用或系统启动此 Activity |
| `intent-filter` | 声明 Activity 能响应的动作 |
| `MAIN` + `LAUNCHER` | 应用入口，出现在启动器 |
| `usesCleartextTraffic` | 允许 HTTP（默认 Android 9+ 禁止） |

---

### 7.3 sample/MainActivity.kt

示例页面——SDK 的最小接入演示。

```kotlin
package com.zxkws.fastvoice.sample

import android.app.Activity              // Activity 基类
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle                 // Activity 状态保存/恢复
import android.widget.*                  // UI 组件
import com.zxkws.fastvoice.*            // SDK 公共 API

class MainActivity : Activity() {
    // Activity — Android 四大组件之一，代表一个"页面"
    // 继承 Activity 而不是 AppCompatActivity（最简示例，不需要向后兼容库）

    private companion object {
        const val RECORD_AUDIO_REQUEST = 1001  // 权限请求码（自定义整数）
        const val SAMPLE_SESSION_ID = "sample-session"
    }

    // lateinit var — 延迟初始化（声明时不赋值，之后一定会赋值）
    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var startButton: Button

    private var voiceClient: FastVoiceClient? = null  // 可空引用

    // FastVoiceListener — SDK 事件监听器（Lambda 实现）
    private val voiceListener = FastVoiceListener { event ->
        when (event) {
            is FastVoiceEvent.StateChanged -> renderState(event.state.value)
            is FastVoiceEvent.Transcript -> {
                if (event.role == "user") renderAsr(event.text)
                else renderReply(event.text)
            }
            is FastVoiceEvent.Error -> renderError(event.error.toString())
            is FastVoiceEvent.SessionAck -> { /* 显示确认 */ }
            is FastVoiceEvent.ContentAck -> { /* 显示确认 */ }
            is FastVoiceEvent.PlaybackFinished -> { /* 显示完成 */ }
            is FastVoiceEvent.PlaybackFailed -> { /* 显示失败 */ }
        }
    }
```

#### Activity 生命周期

```kotlin
    // onCreate — Activity 首次创建时调用
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)       // 必须调用父类
        setContentView(buildContentView())       // 设置页面内容（纯代码构建 UI）
        bindSdk()                                // 绑定按钮点击事件
        requestMicrophonePermissionIfNeeded()    // 请求麦克风权限
    }

    // onStop — Activity 不再可见时调用（如切到后台）
    override fun onStop() {
        voiceClient?.stop()   // 释放音频资源
        super.onStop()
    }

    // onDestroy — Activity 被销毁时调用
    override fun onDestroy() {
        voiceClient?.close()  // 永久释放 SDK 实例
        voiceClient = null
        super.onDestroy()
    }
```

#### 启动语音

```kotlin
    private fun startVoice() {
        // 运行时权限检查（Android 6.0+）
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermissionIfNeeded()
            return
        }

        val endpoint = endpointInput.text.toString().trim()
        val token = tokenInput.text.toString()

        // try-catch 捕获配置错误
        try {
            val config = FastVoiceConfig(
                endpoint = endpoint,
                tokenProvider = DeviceTokenProvider.fixed(token),
                allowInsecureConnection = endpoint.startsWith("ws://"),
                bypassSystemProxy = endpoint.startsWith("ws://127.0.0.1"),
                // 自定义日志输出到 Logcat
                logger = FastVoiceLogger { level, message, error ->
                    Log.d("FastVoiceSample", "$level $message", error)
                },
            )
            voiceClient?.close()  // 关闭旧实例
            voiceClient = FastVoiceClient(applicationContext, config, voiceListener)
            voiceClient?.start()
        } catch (error: Exception) {
            renderError(error.message.orEmpty())
        }
    }
```

#### 纯代码构建 UI

```kotlin
    // 不使用 XML 布局，完全用代码创建 UI（示例的简洁写法）
    private fun buildContentView(): View {
        val density = resources.displayMetrics.density  // 屏幕密度
        val pad = (16 * density).toInt()                // 16dp 转为像素

        endpointInput = EditText(this).apply { hint = "wss://..." }
        startButton = Button(this).apply { text = "Start" }
        // ...

        // LinearLayout — 线性布局（子元素依次排列）
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL  // 垂直排列
            setPadding(pad, pad, pad, pad)
            addView(endpointInput)
            addView(startButton)
            // ...
        }

        // ScrollView — 可滚动容器
        return ScrollView(this).apply { addView(content) }
    }
```

**Activity 生命周期速查：**
```
创建 → onCreate → onStart → onResume → [运行中]
                                          ↓
                                    onPause → onStop → onDestroy
```


---

## 8. CI/CD 配置

### 8.1 .github/workflows/android.yml

GitHub Actions 持续集成配置——每次提交或发布标签时自动构建、测试、发布。

```yaml
name: Android SDK

# on — 触发条件
on:
  push:
    branches: [main]     # 推送到 main 分支
    tags: ["*.*.*"]      # 推送版本标签（如 0.9.0）
  pull_request:
    branches: [main]     # PR 到 main
  workflow_dispatch:      # 手动触发

permissions:
  contents: read          # 最小权限原则

jobs:
  verify:                 # 构建验证 Job
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - uses: actions/checkout@v4      # 检出代码
      - uses: actions/setup-java@v4    # 安装 JDK 17
        with: { distribution: temurin, java-version: "17" }
      - uses: gradle/actions/setup-gradle@v4  # 配置 Gradle 缓存

      # 验证 Git 标签 == SDK 版本号
      - name: Verify release tag matches SDK version
        if: startsWith(github.ref, 'refs/tags/')
        run: |
          version="$(./gradlew -q :fastvoice-sdk:properties | sed -n 's/^version: //p')"
          test "$GITHUB_REF_NAME" = "$version"

      # 核心构建步骤
      - name: Build, test, and publish locally
        run: |
          ./gradlew \
            :fastvoice-sdk:testDebugUnitTest \    # 运行单元测试
            :fastvoice-sdk:lint \                  # 静态代码分析
            :fastvoice-sdk:assembleRelease \       # 构建 release AAR
            :fastvoice-sdk:publishReleasePublicationToMavenLocal \  # 发布到本地 Maven
            :sample:assembleDebug                  # 构建示例 APK

      # 验证发布后的 Maven 坐标能正常引用
      - name: Compile sample against published Maven coordinate
        run: ./gradlew :sample:assembleDebug -PusePublishedSdk

      # 验证 AAR 内容完整
      - name: Verify distributable AAR
        run: |
          unzip -l "$aar" | grep -q "jni/arm64-v8a/libwebrtc-audio-processing-2.so"
          unzip -l "$aar" | grep -q "jni/arm64-v8a/libfastvoice_webrtc_aec3.so"
          # ... 验证所有必须文件都在 AAR 中

  release:               # 发布 Job（仅标签触发）
    if: startsWith(github.ref, 'refs/tags/')
    needs: verify        # 依赖 verify 成功
    steps:
      # 创建 GitHub Release 并上传 AAR + APK
      - run: gh release create "${GITHUB_REF_NAME}" release/*
```

**CI/CD 流程图：**
```
推送代码 → verify job:
  ├─ 编译 SDK
  ├─ 运行单元测试
  ├─ Lint 静态检查
  ├─ 构建 AAR
  ├─ 发布到本地 Maven
  ├─ 用发布的坐标编译示例
  └─ 验证 AAR 内容

推送版本标签 → verify → release job:
  ├─ 下载验证过的产物
  ├─ 重命名（加版本号）
  ├─ 计算 SHA256 校验和
  └─ 创建 GitHub Release
```

---

### 8.2 jitpack.yml

JitPack 发布配置——让其他开发者可以通过 `implementation 'com.github.zxkws:fastvoice-android-sdk:版本'` 引用。

```yaml
jdk:
  - openjdk17                 # 使用 JDK 17

before_install:
  - chmod +x ./gradlew       # 确保 gradlew 可执行

install:
  # JitPack 构建命令：发布到本地 Maven
  - ./gradlew :fastvoice-sdk:publishReleasePublicationToMavenLocal --no-daemon
```

**JitPack 工作原理：**
1. 用户添加 `maven { url 'https://jitpack.io' }` 仓库
2. 首次请求时 JitPack 从 GitHub 拉取代码并执行 `install` 命令
3. 构建产物被缓存并分发给请求者

---

## 9. 关键概念总结

### Kotlin 语法速查表

| 语法 | 含义 | 示例 |
|------|------|------|
| `val` | 不可变引用 | `val name = "FastVoice"` |
| `var` | 可变引用 | `var count = 0` |
| `fun` | 函数 | `fun start(): Boolean` |
| `class` | 类 | `class FastVoiceClient` |
| `data class` | 数据类（自动 equals/hashCode/toString） | `data class SessionRef(...)` |
| `sealed class` | 密封类（有限子类型） | `sealed class FastVoiceEvent` |
| `object` | 单例 | `object ProtocolEncoder` |
| `enum class` | 枚举 | `enum class FastVoiceLogLevel` |
| `interface` | 接口 | `interface Callback` |
| `fun interface` | 函数式接口（SAM） | `fun interface FastVoiceListener` |
| `internal` | 模块内可见 | `internal class AudioEngine` |
| `private` | 仅本文件/本类可见 | `private val socket` |
| `?` | 可空类型 | `String?` 可以是 null |
| `?.` | 安全调用 | `client?.start()` 为 null 则跳过 |
| `?:` | Elvis 运算符 | `name ?: "default"` 为 null 则用默认值 |
| `!!` | 非空断言（慎用） | `value!!` 为 null 抛异常 |
| `when` | 模式匹配（增强版 switch） | `when (event) { is X -> ... }` |
| `is` | 类型检查 | `event is FastVoiceEvent.Error` |
| `as` | 类型转换 | `obj as String` |
| `apply {}` | 配置对象并返回自身 | `Builder().apply { ... }` |
| `let {}` | 非空时执行 | `value?.let { use(it) }` |
| `also {}` | 做额外操作并返回自身 | `list.also { log(it) }` |
| `::` | 方法引用 | `words.filter(String::isNotBlank)` |
| `it` | Lambda 单参数默认名 | `list.map { it.name }` |

### Android 核心概念

| 概念 | 解释 |
|------|------|
| `Context` | Android 环境的"万能钥匙"，访问系统服务、资源、文件等 |
| `Activity` | 一个屏幕/页面 |
| `Manifest` | 应用声明文件（权限、组件、配置） |
| `Permission` | 权限系统，敏感操作需要用户授权 |
| `AudioRecord` | 底层录音 API |
| `AudioTrack` | 底层播放 API |
| `AudioManager` | 音频系统管理（音量、路由、焦点） |
| `Handler` | 线程间消息投递 |
| `Looper` | 消息循环 |
| `Bundle` | 键值对容器（Activity 状态保存用） |

### 项目架构图

```
┌─────────────────────────────────────────────────────────┐
│                    宿主应用 (sample)                       │
│  MainActivity → FastVoiceClient.start()/stop()/close()  │
│                 ← FastVoiceEvent (主线程回调)              │
└─────────────────────────────────────────────────────────┘
                           │
                    公共 API 层（5 个文件）
                           │
┌─────────────────────────────────────────────────────────┐
│              FastVoiceClient（协调者）                      │
│  ┌──────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │ WebSocket│  │SessionState  │  │ContentRequest   │   │
│  │ (OkHttp) │  │Machine       │  │State            │   │
│  └──────────┘  └──────────────┘  └─────────────────┘   │
└─────────────────────────────────────────────────────────┘
                           │
┌─────────────────────────────────────────────────────────┐
│                   AudioEngine                            │
│  ┌────────────┐  ┌──────────┐  ┌─────────────────┐    │
│  │ 录音线程    │  │ 播放线程  │  │ KWS 线程        │    │
│  │ AudioRecord│  │AudioTrack │  │ sherpa-onnx     │    │
│  │ + Opus编码 │  │+ Opus解码 │  │ (端侧关键词)    │    │
│  └─────┬──────┘  └─────┬────┘  └─────────────────┘    │
│        │                │                               │
│        └───── WebRTC AEC3 (C++ JNI) ─────┘             │
└─────────────────────────────────────────────────────────┘
                           │
┌─────────────────────────────────────────────────────────┐
│               Native .so 库 (arm64-v8a)                  │
│  libwebrtc-audio-processing-2.so  (WebRTC 核心)          │
│  libfastvoice_webrtc_aec3.so      (JNI 桥接)            │
│  libsherpa-onnx-jni.so            (KWS 推理引擎)        │
│  libonnxruntime.so                (ONNX 推理运行时)      │
│  libc++_shared.so                 (C++ 标准库)           │
└─────────────────────────────────────────────────────────┘
```

### 数据流向

```
麦克风 → AudioRecord → [AEC 处理] → Opus 编码 → WebSocket 上行 → 服务端
                  ↓
          [增益放大] → KWS 检测 → 唤醒/控制词 → WebSocket 通知服务端
                                                        ↓
服务端 → WebSocket 下行 → Opus 解码 → [AEC 参考] → AudioTrack → 扬声器
```

---

> **文档完**
>
> 如有疑问，建议从 `sample/MainActivity.kt` 开始阅读，它展示了 SDK 的最小接入方式。
> 理解公共 API（4.1~4.7）后再深入 internal 包的实现细节。
