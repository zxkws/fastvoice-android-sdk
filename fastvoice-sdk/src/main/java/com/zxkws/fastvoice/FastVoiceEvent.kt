package com.zxkws.fastvoice

/** A state value emitted only after validation against the current wire protocol. */
data class FastVoiceState(val value: String) {
    companion object {
        @JvmField val IDLE = FastVoiceState("idle")
        @JvmField val SLEEPING = FastVoiceState("sleeping")
        @JvmField val LISTENING = FastVoiceState("listening")
        @JvmField val RECOGNIZING = FastVoiceState("recognizing")
        @JvmField val GENERATING = FastVoiceState("generating")
        @JvmField val SPEAKING = FastVoiceState("speaking")
        @JvmField val PROMPTING = FastVoiceState("prompting")
    }
}

/** Error fields are passed through exactly as received. */
data class FastVoiceError @JvmOverloads constructor(
    val scope: String? = null,
    val ref: String? = null,
    val rev: Long? = null,
    val code: String? = null,
    val message: String? = null,
    val recoverable: Boolean = true,
    val fallbackText: String? = null,
    val cause: Throwable? = null,
)

/** Events emitted by [FastVoiceClient]. No event contains a device credential. */
sealed class FastVoiceEvent {
    data class StateChanged(val state: FastVoiceState) : FastVoiceEvent()

    data class Transcript(
        val role: String,
        val text: String,
        val final: Boolean,
    ) : FastVoiceEvent()

    data class Error(val error: FastVoiceError) : FastVoiceEvent()

    data class SessionAck(
        val action: String,
        val id: String,
        val rev: Long,
    ) : FastVoiceEvent()

    data class ContentAck(val id: String) : FastVoiceEvent()

    data class PlaybackFinished(
        val playbackId: Int,
        val contentId: String?,
    ) : FastVoiceEvent()

    data class PlaybackFailed(
        val playbackId: Int,
        val contentId: String?,
        val code: String,
    ) : FastVoiceEvent()
}

/**
 * Receives SDK callbacks. [FastVoiceClient] invokes these methods on Android's main thread.
 *
 * This is deliberately one callback: one wire event is never delivered twice through a generic
 * callback and a second specialised callback.
 */
fun interface FastVoiceListener {
    fun onEvent(event: FastVoiceEvent)
}
