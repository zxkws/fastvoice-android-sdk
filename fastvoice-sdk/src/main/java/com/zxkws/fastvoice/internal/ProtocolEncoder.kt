package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.FastVoiceConfig
import com.zxkws.fastvoice.SpotArrival
import com.zxkws.fastvoice.VehicleContext
import java.util.UUID

/** Owns the wire field names so they never leak into the host application's integration code. */
internal object ProtocolEncoder {
    private const val INPUT_RATE = 16_000
    private const val OUTPUT_RATE = 48_000
    private const val FRAME_MS = 20
    private val promptDoneTypes = setOf("wake_prompt_done", "spot_prompt_done")

    /**
     * Encodes the initial capability negotiation.
     *
     * `commands-v1` is intentionally not advertised yet: the deployed legacy control path remains
     * the compatibility baseline while the SDK keeps that protocol choice private.
     */
    @JvmSynthetic
    fun hello(config: FastVoiceConfig, supportedWakeWords: Collection<String>): String {
        val wakeWords = selectedWakeWords(config, supportedWakeWords)
        val wake = linkedMapOf<String, Any?>(
            "enabled" to config.wakeEnabled,
        )
        if (wakeWords.isNotEmpty()) {
            // `word` keeps compatibility with older servers; `words` is the current capability set.
            wake["word"] = wakeWords.first()
        }
        wake["words"] = wakeWords

        val message = linkedMapOf<String, Any?>(
            "type" to "hello",
            "audio" to linkedMapOf(
                "encoding" to "opus",
                "input_rate" to INPUT_RATE,
                "output_rate" to OUTPUT_RATE,
                "frame_ms" to FRAME_MS,
            ),
            "wake" to wake,
        )
        config.deviceId?.let { deviceId ->
            message["device"] = linkedMapOf(
                "id" to deviceId,
                "token" to config.requireDeviceToken(),
            )
        }
        return JsonEncoder.encode(message)
    }

    @JvmSynthetic
    fun wake(word: String): String = JsonEncoder.encode(
        linkedMapOf(
            "type" to "wake",
            "word" to word,
        ),
    )

    @JvmSynthetic
    fun localCommandCandidate(text: String): String = JsonEncoder.encode(
        linkedMapOf(
            "type" to "local_command_candidate",
            "text" to text,
        ),
    )

    @JvmSynthetic
    fun promptDone(type: String): String {
        require(type in promptDoneTypes) { "unsupported prompt completion type: $type" }
        return JsonEncoder.encode(linkedMapOf("type" to type))
    }

    @JvmSynthetic
    fun contextUpdate(
        config: FastVoiceConfig,
        context: VehicleContext,
        version: Long,
        timestampSeconds: Double,
    ): String {
        require(version >= 0L) { "version must be non-negative" }
        require(timestampSeconds.isFinite()) { "timestampSeconds must be finite" }
        val deviceId = requireNotNull(config.deviceId) { "device credentials are required" }
        return JsonEncoder.encode(
            linkedMapOf(
                "type" to "context_update",
                "device_id" to deviceId,
                "version" to version,
                "timestamp" to timestampSeconds,
                "ttl_seconds" to config.contextTtlSeconds,
                "context" to context.toProtocolMap(),
            ),
        )
    }

    @JvmSynthetic
    fun spotArrival(
        config: FastVoiceConfig,
        arrival: SpotArrival,
        version: Long,
        timestampSeconds: Double,
        eventId: String = UUID.randomUUID().toString(),
    ): String {
        require(version >= 0L) { "version must be non-negative" }
        require(timestampSeconds.isFinite()) { "timestampSeconds must be finite" }
        val deviceId = requireNotNull(config.deviceId) { "device credentials are required" }
        return JsonEncoder.encode(
            linkedMapOf(
                "type" to "spot_arrival",
                "device_id" to deviceId,
                "event_id" to eventId,
                "version" to version,
                "timestamp" to timestampSeconds,
                "ttl_seconds" to config.arrivalTtlSeconds,
                "park_id" to arrival.parkId,
                "route_id" to arrival.routeId,
                "station_id" to arrival.stationId,
                "spot_id" to arrival.spotId,
            ),
        )
    }

    private fun selectedWakeWords(
        config: FastVoiceConfig,
        supportedWakeWords: Collection<String>,
    ): List<String> {
        require(supportedWakeWords.none(String::isBlank)) {
            "supportedWakeWords must not contain blank values"
        }
        val supported = supportedWakeWords.distinct()
        val preferred = config.preferredWakeWords
        require(preferred.none(String::isBlank)) {
            "preferredWakeWords must not contain blank values"
        }
        val unsupported = preferred.filterNot(supported.toSet()::contains)
        require(unsupported.isEmpty()) {
            "preferredWakeWords contains words unsupported by the local model: $unsupported"
        }
        val selected = if (preferred.isEmpty()) supported else preferred.distinct()
        require(!config.wakeEnabled || selected.isNotEmpty()) {
            "wakeEnabled requires at least one locally supported wake word"
        }
        return selected
    }
}

private fun VehicleContext.toProtocolMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>().apply {
        parkId?.let { put("park_id", it) }
        routeId?.let { put("route_id", it) }
        currentSpotId?.let { put("current_spot_id", it) }
        currentStationId?.let { put("current_station_id", it) }
        nextStationId?.let { put("next_station_id", it) }
        destinationId?.let { put("destination_id", it) }
        vehicleName?.let { put("vehicle_name", it) }
        currentLocation?.let { put("current_location", it) }
        routeName?.let { put("route_name", it) }
        destination?.let { put("destination", it) }
        nextStop?.let { put("next_stop", it) }
        operationStatus?.let { put("operation_status", it) }
        speedMps?.let { put("speed_mps", it) }
        batteryPercent?.let { put("battery_percent", it) }
        passengerCount?.let { put("passenger_count", it) }
        latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }
    }

/** Small dependency-free JSON writer so the codec remains usable in plain JVM unit tests. */
private object JsonEncoder {
    fun encode(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendString(value)
            is Boolean -> append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> append(value.toString())
            is Float -> {
                require(value.isFinite()) { "JSON numbers must be finite" }
                append(value.toString())
            }
            is Double -> {
                require(value.isFinite()) { "JSON numbers must be finite" }
                append(value.toString())
            }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    val key = entry.key as? String
                        ?: throw IllegalArgumentException("JSON object keys must be strings")
                    appendString(key)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is Array<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException(
                "unsupported JSON value: ${value::class.java.name}",
            )
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
}
