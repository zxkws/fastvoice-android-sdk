package com.zxkws.fastvoice

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
 * Automatic reconnect and system-proxy bypass are mandatory SDK behaviour and are therefore not
 * configurable. Both `ws://` and `wss://` endpoints are accepted; deployments
 * that need transport encryption are responsible for configuring a `wss://` endpoint.
 */
class FastVoiceConfig @JvmOverloads constructor(
    areaId: String,
    endpoint: String,
    val routeAudioToSpeaker: Boolean = true,
    val logger: FastVoiceLogger? = null,
    val getLocation: FastVoiceGetLocation? = null,
) {
    /** Required trimmed WebSocket endpoint supplied by the host application. */
    val endpoint: String = endpoint.trim()

    /** Required immutable area id for one order/WebSocket session. */
    val areaId: String = areaId.trim()

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
        private var routeAudioToSpeaker: Boolean = true
        private var logger: FastVoiceLogger? = null
        private var getLocation: FastVoiceGetLocation? = null

        fun areaId(areaId: String) = apply { this.areaId = areaId }

        fun routeAudioToSpeaker(enabled: Boolean) = apply { routeAudioToSpeaker = enabled }

        fun logger(logger: FastVoiceLogger?) = apply { this.logger = logger }

        fun getLocation(block: FastVoiceGetLocation?) = apply {
            getLocation = block
        }

        fun build(): FastVoiceConfig = FastVoiceConfig(
            endpoint = endpoint,
            areaId = areaId,
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
