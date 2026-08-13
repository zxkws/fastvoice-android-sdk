package com.zxkws.fastvoice

import java.util.Collections
import org.json.JSONObject

/** Built-in endpoint used when the host does not provide a custom server address. */
const val DEFAULT_FASTVOICE_ENDPOINT = "ws://192.168.105.165:8100/ws"

/** Asynchronous location function supplied once by the host application. */
typealias FastVoiceGetLocation = ((JSONObject?) -> Unit) -> Unit

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
 * On-device wake, automatic reconnect, and system-proxy bypass are mandatory SDK behaviour and
 * are therefore not configurable. Both `ws://` and `wss://` endpoints are accepted; deployments
 * that need transport encryption are responsible for configuring a `wss://` endpoint.
 */
class FastVoiceConfig @JvmOverloads constructor(
    endpoint: String = DEFAULT_FASTVOICE_ENDPOINT,
    preferredWakeWords: List<String> = emptyList(),
    val routeAudioToSpeaker: Boolean = true,
    val logger: FastVoiceLogger? = null,
    val getLocation: FastVoiceGetLocation? = null,
) {
    /** Trimmed custom endpoint, or [DEFAULT_FASTVOICE_ENDPOINT] when input is blank. */
    val endpoint: String = resolveEndpoint(endpoint)

    val preferredWakeWords: List<String> =
        Collections.unmodifiableList(ArrayList(preferredWakeWords))

    init {
        require(this.endpoint.startsWith("wss://", ignoreCase = true) ||
            this.endpoint.startsWith("ws://", ignoreCase = true)) {
            "endpoint must use ws:// or wss://"
        }
    }

    /** Never renders endpoint query parameters or configured callbacks. */
    override fun toString(): String = buildString {
        append("FastVoiceConfig(endpoint=[configured]")
        append(", preferredWakeWords=")
        append(preferredWakeWords)
        append(", routeAudioToSpeaker=")
        append(routeAudioToSpeaker)
        append(", logger=")
        append(if (logger == null) "null" else "[configured]")
        append(", getLocation=")
        append(if (getLocation == null) "null" else "[configured]")
        append(')')
    }

    class Builder(private val endpoint: String = DEFAULT_FASTVOICE_ENDPOINT) {
        private var preferredWakeWords: List<String> = emptyList()
        private var routeAudioToSpeaker: Boolean = true
        private var logger: FastVoiceLogger? = null
        private var getLocation: FastVoiceGetLocation? = null

        fun preferredWakeWords(words: List<String>) = apply {
            preferredWakeWords = ArrayList(words)
        }

        fun routeAudioToSpeaker(enabled: Boolean) = apply { routeAudioToSpeaker = enabled }

        fun logger(logger: FastVoiceLogger?) = apply { this.logger = logger }

        fun getLocation(block: FastVoiceGetLocation?) = apply {
            getLocation = block
        }

        fun build(): FastVoiceConfig = FastVoiceConfig(
            endpoint = endpoint,
            preferredWakeWords = preferredWakeWords,
            routeAudioToSpeaker = routeAudioToSpeaker,
            logger = logger,
            getLocation = getLocation,
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT: String = DEFAULT_FASTVOICE_ENDPOINT

        /** Resolves nullable user input without hiding invalid non-empty values. */
        @JvmStatic
        fun resolveEndpoint(customEndpoint: String?): String =
            customEndpoint?.trim()?.ifEmpty { DEFAULT_FASTVOICE_ENDPOINT }
                ?: DEFAULT_FASTVOICE_ENDPOINT

        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        fun builder(endpoint: String): Builder = Builder(endpoint)
    }
}
