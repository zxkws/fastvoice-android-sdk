package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.FastVoiceConfig

/** Owns the wire field names so they never leak into the host application's integration code. */
internal object ProtocolEncoder {
    /**
     * Encodes the initial capability negotiation.
     *
     * The host app does not implement the control protocol. The SDK advertises the exact current
     * contract and rejects a server that negotiates anything else.
     */
    @JvmSynthetic
    fun hello(config: FastVoiceConfig, supportedWakeWords: Collection<String>): String {
        val wakeWords = selectedWakeWords(config, supportedWakeWords)
        val wake = linkedMapOf<String, Any?>(
            "enabled" to config.wakeEnabled,
        )
        wake["words"] = wakeWords

        val message = linkedMapOf<String, Any?>(
            "type" to "hello",
            "audio" to linkedMapOf(
                "encoding" to "opus",
                "input_rate" to CurrentProtocol.INPUT_RATE,
                "output_rate" to CurrentProtocol.OUTPUT_RATE,
                "frame_ms" to CurrentProtocol.FRAME_MS,
            ),
            "wake" to wake,
            "control" to linkedMapOf(
                "protocol" to "commands-v1",
                "actions" to CurrentProtocol.CONTROL_ACTIONS,
            ),
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
    fun localCommandCandidate(id: String, text: String, generation: Int): String {
        require(id.isNotEmpty()) { "local command candidate id must not be empty" }
        return JsonEncoder.encode(
            linkedMapOf(
                "type" to "local_command_candidate",
                "id" to id,
                "text" to text,
                "gen" to generation,
            ),
        )
    }

    @JvmSynthetic
    fun interrupt(): String = JsonEncoder.encode(linkedMapOf("type" to "interrupt"))

    @JvmSynthetic
    fun commandAck(id: String): String = JsonEncoder.encode(
        linkedMapOf(
            "type" to "command_ack",
            "id" to id,
        ),
    )

    @JvmSynthetic
    fun playbackFinished(generation: Int): String = JsonEncoder.encode(
        linkedMapOf(
            "type" to "playback_finished",
            "gen" to generation,
        ),
    )

    @JvmSynthetic
    fun playbackProgress(generation: Int, playedMs: Long): String {
        require(playedMs >= 0L) { "playedMs must be non-negative" }
        return JsonEncoder.encode(
            linkedMapOf(
                "type" to "playback_progress",
                "gen" to generation,
                "played_ms" to playedMs,
            ),
        )
    }

    @JvmSynthetic
    fun playbackFailed(generation: Int, reason: String, commandId: String? = null): String {
        require(reason.isNotEmpty()) { "playback failure reason must not be empty" }
        return JsonEncoder.encode(
            linkedMapOf<String, Any?>(
                "type" to "playback_failed",
                "gen" to generation,
                "reason" to reason,
            ).apply {
                commandId?.takeIf(String::isNotEmpty)?.let { put("command_id", it) }
            },
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
