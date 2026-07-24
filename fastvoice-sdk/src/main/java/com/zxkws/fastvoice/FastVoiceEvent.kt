package com.zxkws.fastvoice

/** A server state value. Unknown future values are preserved verbatim in [value]. */
data class FastVoiceState(val value: String) {
    companion object {
        @JvmField val IDLE = FastVoiceState("idle")
        @JvmField val SLEEPING = FastVoiceState("sleeping")
        @JvmField val LISTENING = FastVoiceState("listening")
        @JvmField val RECOGNIZING = FastVoiceState("recognizing")
        @JvmField val GENERATING = FastVoiceState("generating")
        @JvmField val SPEAKING = FastVoiceState("speaking")
        @JvmField val PROMPTING = FastVoiceState("prompting")

        /** Does not normalize, translate, or otherwise change the server value. */
        @JvmStatic
        fun fromRaw(value: String): FastVoiceState = when (value) {
            IDLE.value -> IDLE
            SLEEPING.value -> SLEEPING
            LISTENING.value -> LISTENING
            RECOGNIZING.value -> RECOGNIZING
            GENERATING.value -> GENERATING
            SPEAKING.value -> SPEAKING
            PROMPTING.value -> PROMPTING
            else -> FastVoiceState(value)
        }
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

    data class OrderAck(
        val action: String,
        val id: String,
        val rev: Long,
    ) : FastVoiceEvent()

    data class TourAck(val id: String) : FastVoiceEvent()

    data class PlaybackFinished(
        val playbackId: Int,
        val tourId: String?,
    ) : FastVoiceEvent()

    data class PlaybackFailed(
        val playbackId: Int,
        val tourId: String?,
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

/** Java-friendly no-op listener. */
open class FastVoiceListenerAdapter : FastVoiceListener {
    override fun onEvent(event: FastVoiceEvent) = Unit
}
