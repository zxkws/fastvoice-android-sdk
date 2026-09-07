package com.zxkws.fastvoice.internal

internal data class ReadyMessage(
    val connectionId: String?,
    val wakeWords: List<String>?,
    val controlTimeoutMs: Long?,
)

/** Constants and validation for the only FastVoice wire protocol. */
internal object CurrentProtocol {
    const val INPUT_RATE = 16_000
    const val OUTPUT_RATE = 48_000
    const val FRAME_MS = 20
    const val MAX_CAPTURE_PRE_ROLL_MS = 1_800
    const val INACTIVITY_TIMEOUT_REASON = "inactivity_timeout"
    val READY_FIELDS = setOf("type", "connection_id", "wake_words", "control_timeout_ms")

    val CONTROL_ACTIONS = setOf(
        "capture.start",
        "capture.stop",
        "playback.stop",
        "playback.pause",
        "playback.resume",
        "volume.up",
        "volume.down",
    )

    val STATE_VALUES = setOf(
        "idle",
        "sleeping",
        "listening",
        "recognizing",
        "generating",
        "speaking",
        "prompting",
    )

    /** `reason` is an additive field reserved for the inactivity transition into sleeping. */
    fun acceptsState(value: String?, reason: String?): Boolean =
        value in STATE_VALUES &&
            (reason == null || (value == "sleeping" && reason == INACTIVITY_TIMEOUT_REASON))

    /** The server state machine is authoritative: only sleeping authorizes a fresh local wake. */
    fun authorizesWake(value: String?): Boolean = value == "sleeping"

    fun acceptsReady(
        ready: ReadyMessage,
        supportedWakeWords: Set<String>,
    ): Boolean {
        val words = ready.wakeWords ?: return false
        return ready.connectionId?.isNotBlank() == true &&
            words.none(String::isBlank) &&
            words.distinct().size == words.size &&
            words.all(supportedWakeWords::contains) &&
            ready.controlTimeoutMs?.let { it > 0L } == true
    }

}
