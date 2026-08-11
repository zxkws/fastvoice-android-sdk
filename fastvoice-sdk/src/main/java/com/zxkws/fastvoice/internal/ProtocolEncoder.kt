package com.zxkws.fastvoice.internal

/** Owns every JSON field in the single FastVoice wire protocol. */
internal object ProtocolEncoder {
    @JvmSynthetic
    fun hello(): String = JsonEncoder.encode(
        linkedMapOf(
            "type" to "hello",
            "wake" to true,
        ),
    )

    @JvmSynthetic
    fun locationUpdate(
        park: String?,
        spot: String?,
        latitude: Double? = null,
        longitude: Double? = null,
    ): String = JsonEncoder.encode(
        linkedMapOf<String, Any?>(
            "type" to "location.update",
        ).apply {
            if (park != null) put("park", park)
            if (spot != null) put("spot", spot)
            if (latitude != null) put("latitude", latitude)
            if (longitude != null) put("longitude", longitude)
        },
    )

    @JvmSynthetic
    fun locationClear(): String = JsonEncoder.encode(
        linkedMapOf("type" to "location.clear"),
    )

    @JvmSynthetic
    fun welcomePlay(park: String, spot: String?): String = JsonEncoder.encode(
        linkedMapOf<String, Any?>(
            "type" to "welcome.play",
            "park" to park,
        ).apply {
            if (spot != null) put("spot", spot)
        },
    )

    @JvmSynthetic
    fun wake(word: String): String = JsonEncoder.encode(
        linkedMapOf("type" to "wake", "word" to word),
    )

    @JvmSynthetic
    fun controlCandidate(id: String, playbackId: Int, name: String): String = JsonEncoder.encode(
        linkedMapOf(
            "type" to "control.candidate",
            "id" to id,
            "playback_id" to playbackId,
            "name" to name,
        ),
    )

    @JvmSynthetic
    fun turnCancel(): String = JsonEncoder.encode(linkedMapOf("type" to "turn.cancel"))

    @JvmSynthetic
    fun controlResult(id: String, ok: Boolean, code: String? = null): String = JsonEncoder.encode(
        linkedMapOf<String, Any?>(
            "type" to "control.result",
            "id" to id,
            "ok" to ok,
        ).apply {
            code?.takeIf(String::isNotBlank)?.let { put("code", it) }
        },
    )

    @JvmSynthetic
    fun playbackProgress(id: Int, positionMs: Long): String {
        require(positionMs >= 0L) { "positionMs must be non-negative" }
        return JsonEncoder.encode(
            linkedMapOf(
                "type" to "playback.progress",
                "id" to id,
                "position_ms" to positionMs,
            ),
        )
    }

    @JvmSynthetic
    fun playbackFinished(id: Int): String = JsonEncoder.encode(
        linkedMapOf("type" to "playback.finished", "id" to id),
    )

    @JvmSynthetic
    fun playbackFailed(id: Int, code: String): String {
        require(code.isNotBlank()) { "playback failure code must not be blank" }
        return JsonEncoder.encode(
            linkedMapOf(
                "type" to "playback.failed",
                "id" to id,
                "code" to code,
            ),
        )
    }
}

/** Small dependency-free JSON writer usable from plain JVM unit tests. */
internal object JsonEncoder {
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
            is Number -> append(value.toString())
            is Map<*, *> -> {
                append('{')
                var first = true
                value.forEach { (key, item) ->
                    require(key is String) { "JSON object keys must be strings" }
                    if (!first) append(',')
                    first = false
                    appendString(key)
                    append(':')
                    appendValue(item)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                var first = true
                value.forEach { item ->
                    if (!first) append(',')
                    first = false
                    appendValue(item)
                }
                append(']')
            }
            is Array<*> -> appendValue(value.asIterable())
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
                    if (character < ' ') {
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
