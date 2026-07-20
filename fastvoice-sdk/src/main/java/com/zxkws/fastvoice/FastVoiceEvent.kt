package com.zxkws.fastvoice

import java.util.Collections

/** A server state value. Unknown future values are preserved verbatim in [value]. */
data class FastVoiceState(val value: String) {
    companion object {
        @JvmField val SLEEPING = FastVoiceState("sleeping")
        @JvmField val LISTENING = FastVoiceState("listening")
        @JvmField val RECOGNIZING = FastVoiceState("recognizing")
        @JvmField val GENERATING = FastVoiceState("generating")
        @JvmField val SPEAKING = FastVoiceState("speaking")
        @JvmField val ACKNOWLEDGING = FastVoiceState("acknowledging")

        /** Does not normalize, translate, or otherwise change the server value. */
        @JvmStatic
        fun fromRaw(value: String): FastVoiceState = when (value) {
            SLEEPING.value -> SLEEPING
            LISTENING.value -> LISTENING
            RECOGNIZING.value -> RECOGNIZING
            GENERATING.value -> GENERATING
            SPEAKING.value -> SPEAKING
            ACKNOWLEDGING.value -> ACKNOWLEDGING
            else -> FastVoiceState(value)
        }
    }
}

/** Error fields are passed through exactly as received. */
data class FastVoiceError @JvmOverloads constructor(
    val code: String? = null,
    val message: String? = null,
    val prompt: String? = null,
    val cause: Throwable? = null,
)

/** Events emitted by [FastVoiceClient]. No event contains a device credential. */
sealed class FastVoiceEvent {
    data class StateChanged(val state: FastVoiceState) : FastVoiceEvent()

    data class Asr(val text: String) : FastVoiceEvent()

    data class ReplyDelta(val text: String) : FastVoiceEvent()

    data class Error(val error: FastVoiceError) : FastVoiceEvent()

    data class ContextUpdated(
        val version: Long,
    ) : FastVoiceEvent()

    data class ArrivalAccepted(
        val eventId: String,
        val version: Long,
        val spotId: String,
    ) : FastVoiceEvent()

    data class ArrivalRejected(
        val code: String?,
        val message: String?,
    ) : FastVoiceEvent()

    class ActiveWakeWords(words: Collection<String>) : FastVoiceEvent() {
        val words: List<String> = Collections.unmodifiableList(ArrayList(words))

        override fun equals(other: Any?): Boolean =
            other is ActiveWakeWords && words == other.words

        override fun hashCode(): Int = words.hashCode()

        override fun toString(): String = "ActiveWakeWords(words=$words)"
    }
}

/**
 * Receives SDK callbacks. [FastVoiceClient] invokes these methods on Android's main thread.
 *
 * Kotlin callers may implement this interface directly. Java callers can extend
 * [FastVoiceListenerAdapter] and override only the callbacks they need.
 */
interface FastVoiceListener {
    fun onEvent(event: FastVoiceEvent) = Unit

    fun onStateChanged(state: FastVoiceState) = Unit

    fun onAsr(text: String) = Unit

    fun onReplyDelta(text: String) = Unit

    fun onError(error: FastVoiceError) = Unit

    fun onContextUpdated(event: FastVoiceEvent.ContextUpdated) = Unit

    fun onArrivalAccepted(event: FastVoiceEvent.ArrivalAccepted) = Unit

    fun onArrivalRejected(event: FastVoiceEvent.ArrivalRejected) = Unit

    fun onActiveWakeWords(words: List<String>) = Unit
}

/** Java-friendly no-op listener. */
open class FastVoiceListenerAdapter : FastVoiceListener
