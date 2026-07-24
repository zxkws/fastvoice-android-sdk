package com.zxkws.fastvoice.internal

/** Pure session boundary for microphone uplink and idle wake KWS. */
internal object SessionAudioPolicy {
    fun captureAllowed(
        hasActiveSession: Boolean,
        endPending: Boolean,
        sessionAcknowledgedOnConnection: Boolean,
    ): Boolean = hasActiveSession && !endPending && sessionAcknowledgedOnConnection

    fun wakeKwsEnabled(
        started: Boolean,
        ready: Boolean,
        wakeRequested: Boolean,
        hasActiveSession: Boolean,
        endPending: Boolean,
        sessionAcknowledgedOnConnection: Boolean,
    ): Boolean = started && ready && wakeRequested &&
        captureAllowed(hasActiveSession, endPending, sessionAcknowledgedOnConnection)
}
