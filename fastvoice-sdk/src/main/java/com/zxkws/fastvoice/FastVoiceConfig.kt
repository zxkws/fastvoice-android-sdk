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
 *
 * On-device wake, automatic reconnect, and system-proxy bypass are mandatory SDK behaviour and
 * are therefore not configurable. Both `ws://` and `wss://` endpoints are accepted; deployments
 * that need transport encryption are responsible for configuring a `wss://` endpoint.
 */
class FastVoiceConfig @JvmOverloads constructor(
    val endpoint: String,
    val tokenProvider: DeviceTokenProvider,
    preferredWakeWords: List<String> = emptyList(),
    val routeAudioToSpeaker: Boolean = true,
    val logger: FastVoiceLogger? = null,
) {
    val preferredWakeWords: List<String> =
        Collections.unmodifiableList(ArrayList(preferredWakeWords))

    init {
        require(endpoint.startsWith("wss://", ignoreCase = true) ||
            endpoint.startsWith("ws://", ignoreCase = true)) {
            "endpoint must use ws:// or wss://"
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
        append(", preferredWakeWords=")
        append(preferredWakeWords)
        append(", routeAudioToSpeaker=")
        append(routeAudioToSpeaker)
        append(", logger=")
        append(if (logger == null) "null" else "[configured]")
        append(')')
    }

    class Builder(private val endpoint: String) {
        private var tokenProvider: DeviceTokenProvider? = null
        private var preferredWakeWords: List<String> = emptyList()
        private var routeAudioToSpeaker: Boolean = true
        private var logger: FastVoiceLogger? = null

        fun token(token: String) = apply {
            tokenProvider = DeviceTokenProvider.fixed(token)
        }

        fun tokenProvider(provider: DeviceTokenProvider) = apply {
            tokenProvider = provider
        }

        fun preferredWakeWords(words: List<String>) = apply {
            preferredWakeWords = ArrayList(words)
        }

        fun routeAudioToSpeaker(enabled: Boolean) = apply { routeAudioToSpeaker = enabled }

        fun logger(logger: FastVoiceLogger?) = apply { this.logger = logger }

        fun build(): FastVoiceConfig = FastVoiceConfig(
            endpoint = endpoint,
            tokenProvider = requireNotNull(tokenProvider) { "token is required" },
            preferredWakeWords = preferredWakeWords,
            routeAudioToSpeaker = routeAudioToSpeaker,
            logger = logger,
        )
    }

    companion object {
        @JvmStatic
        fun builder(endpoint: String): Builder = Builder(endpoint)
    }
}
