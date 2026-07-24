package com.zxkws.fastvoice.internal

/** Pure order boundary for microphone uplink and idle wake KWS. */
internal object OrderAudioPolicy {
    fun captureAllowed(
        hasActiveOrder: Boolean,
        endPending: Boolean,
        orderAcknowledgedOnConnection: Boolean,
    ): Boolean = hasActiveOrder && !endPending && orderAcknowledgedOnConnection

    fun wakeKwsEnabled(
        started: Boolean,
        ready: Boolean,
        wakeRequested: Boolean,
        hasActiveOrder: Boolean,
        endPending: Boolean,
        orderAcknowledgedOnConnection: Boolean,
    ): Boolean = started && ready && wakeRequested &&
        captureAllowed(hasActiveOrder, endPending, orderAcknowledgedOnConnection)
}
