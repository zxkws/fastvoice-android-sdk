package com.zxkws.fastvoice

import java.util.Collections

/** Supplies a short-lived device token immediately before a connection is opened. */
fun interface DeviceTokenProvider {
    /**
     * Returns the current token for [deviceId]. Returning `null` rejects the connection attempt.
     * Implementations must not log the returned value.
     */
    fun tokenFor(deviceId: String): String?

    companion object {
        /** Creates a provider for installations whose token is provisioned with the app. */
        @JvmStatic
        fun fixed(token: String): DeviceTokenProvider {
            require(token.isNotBlank()) { "token must not be blank" }
            return FixedDeviceTokenProvider(token)
        }
    }
}

private class FixedDeviceTokenProvider(private val token: String) : DeviceTokenProvider {
    override fun tokenFor(deviceId: String): String = token

    override fun toString(): String = "DeviceTokenProvider([redacted])"
}

/**
 * Convenience value used when a device has a fixed id and token.
 *
 * The token deliberately has no public getter and is redacted from [toString]. For rotating
 * credentials, configure [FastVoiceConfig] with a custom [DeviceTokenProvider] instead.
 */
class DeviceCredentials(
    val deviceId: String,
    token: String,
) {
    private val fixedProvider = DeviceTokenProvider.fixed(token)

    init {
        requireDeviceId(deviceId)
    }

    internal fun asTokenProvider(): DeviceTokenProvider = fixedProvider

    override fun toString(): String =
        "DeviceCredentials(deviceId=$deviceId, token=[redacted])"
}

enum class FastVoiceLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** Receives SDK diagnostics. Protocol payloads and credentials are never passed to this logger. */
fun interface FastVoiceLogger {
    fun log(level: FastVoiceLogLevel, message: String, error: Throwable?)
}

/** Immutable configuration for one [FastVoiceClient] instance. */
class FastVoiceConfig @JvmOverloads constructor(
    val endpoint: String,
    val deviceId: String? = null,
    val tokenProvider: DeviceTokenProvider? = null,
    val wakeEnabled: Boolean = true,
    preferredWakeWords: List<String> = emptyList(),
    val autoReconnect: Boolean = true,
    val bypassSystemProxy: Boolean = false,
    val allowInsecureConnection: Boolean = false,
    val routeAudioToSpeaker: Boolean = true,
    val logger: FastVoiceLogger? = null,
    val localFallbackPromptEnabled: Boolean = true,
) {
    val preferredWakeWords: List<String> =
        Collections.unmodifiableList(ArrayList(preferredWakeWords))

    init {
        require(endpoint.startsWith("wss://", ignoreCase = true) ||
            endpoint.startsWith("ws://", ignoreCase = true)) {
            "endpoint must use ws:// or wss://"
        }
        require(allowInsecureConnection || !endpoint.startsWith("ws://", ignoreCase = true)) {
            "ws:// requires allowInsecureConnection=true"
        }
        require((deviceId == null) == (tokenProvider == null)) {
            "deviceId and tokenProvider must be configured together"
        }
        deviceId?.let(::requireDeviceId)
    }

    /** Kotlin-friendly constructor for fixed device credentials. */
    @JvmOverloads
    constructor(
        endpoint: String,
        credentials: DeviceCredentials,
        wakeEnabled: Boolean = true,
        preferredWakeWords: List<String> = emptyList(),
        autoReconnect: Boolean = true,
        bypassSystemProxy: Boolean = false,
        allowInsecureConnection: Boolean = false,
        routeAudioToSpeaker: Boolean = true,
        logger: FastVoiceLogger? = null,
        localFallbackPromptEnabled: Boolean = true,
    ) : this(
        endpoint = endpoint,
        deviceId = credentials.deviceId,
        tokenProvider = credentials.asTokenProvider(),
        wakeEnabled = wakeEnabled,
        preferredWakeWords = preferredWakeWords,
        autoReconnect = autoReconnect,
        bypassSystemProxy = bypassSystemProxy,
        allowInsecureConnection = allowInsecureConnection,
        routeAudioToSpeaker = routeAudioToSpeaker,
        logger = logger,
        localFallbackPromptEnabled = localFallbackPromptEnabled,
    )

    internal fun requireDeviceToken(): String {
        val id = requireNotNull(deviceId) { "device credentials are required" }
        return requireNotNull(tokenProvider?.tokenFor(id)?.takeIf(String::isNotBlank)) {
            "DeviceTokenProvider returned no token"
        }
    }

    /** Never renders endpoint query parameters, a provider, or a credential. */
    override fun toString(): String = buildString {
        append("FastVoiceConfig(endpoint=[configured], deviceId=")
        append(deviceId)
        append(", tokenProvider=")
        append(if (tokenProvider == null) "null" else "[configured]")
        append(", wakeEnabled=")
        append(wakeEnabled)
        append(", preferredWakeWords=")
        append(preferredWakeWords)
        append(", autoReconnect=")
        append(autoReconnect)
        append(", bypassSystemProxy=")
        append(bypassSystemProxy)
        append(", allowInsecureConnection=")
        append(allowInsecureConnection)
        append(", routeAudioToSpeaker=")
        append(routeAudioToSpeaker)
        append(", logger=")
        append(if (logger == null) "null" else "[configured]")
        append(", localFallbackPromptEnabled=")
        append(localFallbackPromptEnabled)
        append(')')
    }

    class Builder(private val endpoint: String) {
        private var deviceId: String? = null
        private var tokenProvider: DeviceTokenProvider? = null
        private var wakeEnabled: Boolean = true
        private var preferredWakeWords: List<String> = emptyList()
        private var autoReconnect: Boolean = true
        private var bypassSystemProxy: Boolean = false
        private var allowInsecureConnection: Boolean = false
        private var routeAudioToSpeaker: Boolean = true
        private var logger: FastVoiceLogger? = null
        private var localFallbackPromptEnabled: Boolean = true

        fun device(
            deviceId: String,
            tokenProvider: DeviceTokenProvider,
        ) = apply {
            this.deviceId = deviceId
            this.tokenProvider = tokenProvider
        }

        fun device(credentials: DeviceCredentials) =
            device(credentials.deviceId, credentials.asTokenProvider())

        fun wakeEnabled(enabled: Boolean) = apply { wakeEnabled = enabled }

        fun preferredWakeWords(words: List<String>) = apply {
            preferredWakeWords = ArrayList(words)
        }

        fun autoReconnect(enabled: Boolean) = apply { autoReconnect = enabled }

        fun bypassSystemProxy(enabled: Boolean) = apply { bypassSystemProxy = enabled }

        fun allowInsecureConnection(allowed: Boolean) = apply {
            allowInsecureConnection = allowed
        }

        fun routeAudioToSpeaker(enabled: Boolean) = apply { routeAudioToSpeaker = enabled }

        fun logger(logger: FastVoiceLogger?) = apply { this.logger = logger }

        fun localFallbackPromptEnabled(enabled: Boolean) = apply {
            localFallbackPromptEnabled = enabled
        }

        fun build(): FastVoiceConfig = FastVoiceConfig(
            endpoint = endpoint,
            deviceId = deviceId,
            tokenProvider = tokenProvider,
            wakeEnabled = wakeEnabled,
            preferredWakeWords = preferredWakeWords,
            autoReconnect = autoReconnect,
            bypassSystemProxy = bypassSystemProxy,
            allowInsecureConnection = allowInsecureConnection,
            routeAudioToSpeaker = routeAudioToSpeaker,
            logger = logger,
            localFallbackPromptEnabled = localFallbackPromptEnabled,
        )
    }

    companion object {
        @JvmStatic
        fun builder(endpoint: String): Builder = Builder(endpoint)
    }
}

private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

private fun requireDeviceId(deviceId: String) {
    require(DEVICE_ID_PATTERN.matches(deviceId)) {
        "deviceId must match ${DEVICE_ID_PATTERN.pattern}"
    }
}
