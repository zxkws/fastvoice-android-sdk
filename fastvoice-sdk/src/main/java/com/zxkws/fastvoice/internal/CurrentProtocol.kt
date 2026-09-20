package com.zxkws.fastvoice.internal

internal data class ReadyMessage(
    val connectionId: String?,
    val controlTimeoutMs: Long?,
)

/** Constants and validation for the only FastVoice wire protocol. */
internal object CurrentProtocol {
    const val INPUT_RATE = 16_000
    const val OUTPUT_RATE = 48_000
    const val FRAME_MS = 20
    const val MAX_CAPTURE_PRE_ROLL_MS = 1_800
    val READY_FIELDS = setOf("type", "connection_id", "control_timeout_ms")

    val STATE_VALUES = setOf(
        "listening",
        "recognizing",
        "generating",
        "speaking",
        "prompting",
    )

    fun acceptsState(value: String?): Boolean = value in STATE_VALUES

    fun acceptsReady(
        ready: ReadyMessage,
    ): Boolean {
        return ready.connectionId?.isNotBlank() == true &&
            ready.controlTimeoutMs?.let { it > 0L } == true
    }

}
