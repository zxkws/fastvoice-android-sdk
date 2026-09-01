package com.zxkws.fastvoice

import java.util.Collections
import org.json.JSONObject

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
    areaId: String,
    endpoint: String,
    preferredWakeWords: List<String> = emptyList(),
    val routeAudioToSpeaker: Boolean = true,
    val logger: FastVoiceLogger? = null,
    val getLocation: FastVoiceGetLocation? = null,
) {
    /** Required trimmed WebSocket endpoint supplied by the host application. */
    val endpoint: String = endpoint.trim()

    /** Required immutable area id for one order/WebSocket session. */
    val areaId: String = areaId.trim()

    val preferredWakeWords: List<String> =
        Collections.unmodifiableList(ArrayList(preferredWakeWords))

    init {
        require(this.areaId.isNotEmpty()) { "areaId must not be blank" }
        require(this.endpoint.isNotEmpty()) { "endpoint must not be blank" }
        require(this.endpoint.startsWith("wss://", ignoreCase = true) ||
            this.endpoint.startsWith("ws://", ignoreCase = true)) {
            "endpoint must use ws:// or wss://"
        }
    }

    /** Never renders endpoint query parameters or configured callbacks. */
    override fun toString(): String = buildString {
        append("FastVoiceConfig(endpoint=[configured]")
        append(", areaId=[configured]")
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

    class Builder(private val endpoint: String) {
        private var areaId: String = ""
        private var preferredWakeWords: List<String> = emptyList()
        private var routeAudioToSpeaker: Boolean = true
        private var logger: FastVoiceLogger? = null
        private var getLocation: FastVoiceGetLocation? = null

        fun areaId(areaId: String) = apply { this.areaId = areaId }

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
            areaId = areaId,
            preferredWakeWords = preferredWakeWords,
            routeAudioToSpeaker = routeAudioToSpeaker,
            logger = logger,
            getLocation = getLocation,
        )
    }

    companion object {
        @JvmStatic
        fun builder(endpoint: String): Builder = Builder(endpoint)
    }
}
