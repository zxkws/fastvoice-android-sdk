package com.zxkws.fastvoice

import java.util.Collections

private fun requireValidToken(token: String): String {
    require(token.isNotEmpty() && token.length <= 4_096 && token.none(Char::isWhitespace)) {
        "token must be 1..4096 non-whitespace characters"
    }
    return token
}

/** Supplies the current opaque device token immediately before a connection is opened. */
fun interface DeviceTokenProvider {
    /**
     * Returns the current token. Returning `null` rejects the connection attempt.
     * Implementations must not log the returned value.
     */
    fun token(): String?

    companion object {
        /** Creates a provider for installations whose token is provisioned with the app. */
        @JvmStatic
        fun fixed(token: String): DeviceTokenProvider {
            return FixedDeviceTokenProvider(requireValidToken(token))
        }
    }
}

private class FixedDeviceTokenProvider(private val token: String) : DeviceTokenProvider {
    override fun token(): String = token

    override fun toString(): String = "DeviceTokenProvider([redacted])"
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

/**
 * Immutable configuration for one [FastVoiceClient] instance.
 *
 * The client sends only the opaque bearer token. The server resolves its stable internal device
 * identity; host applications never configure or transmit a separate device ID.
 */
class FastVoiceConfig @JvmOverloads constructor(
    val endpoint: String,
    val tokenProvider: DeviceTokenProvider,
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
    }

    internal fun requireDeviceToken(): String {
        val token = requireNotNull(tokenProvider.token()) {
            "DeviceTokenProvider returned no token"
        }
        return requireValidToken(token)
    }

    /** Never renders endpoint query parameters, a provider, or a credential. */
    override fun toString(): String = buildString {
        append("FastVoiceConfig(endpoint=[configured], tokenProvider=[configured]")
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
        private var tokenProvider: DeviceTokenProvider? = null
        private var wakeEnabled: Boolean = true
        private var preferredWakeWords: List<String> = emptyList()
        private var autoReconnect: Boolean = true
        private var bypassSystemProxy: Boolean = false
        private var allowInsecureConnection: Boolean = false
        private var routeAudioToSpeaker: Boolean = true
        private var logger: FastVoiceLogger? = null
        private var localFallbackPromptEnabled: Boolean = true

        fun token(token: String) = apply {
            tokenProvider = DeviceTokenProvider.fixed(token)
        }

        fun tokenProvider(provider: DeviceTokenProvider) = apply {
            tokenProvider = provider
        }

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
            tokenProvider = requireNotNull(tokenProvider) { "token is required" },
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
