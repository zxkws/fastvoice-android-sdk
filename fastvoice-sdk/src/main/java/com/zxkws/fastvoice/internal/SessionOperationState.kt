package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.SessionSnapshot

/**
 * Connection-aware desired/acknowledged application session state.
 *
 * Desired snapshots may lead the server while an operation is in flight. Only acknowledgements
 * commit [acknowledgedSession], and only a start acknowledgement makes a connection audio-ready.
 */
internal class SessionOperationState {
    data class PendingEnd(
        val id: String,
        val rev: Long,
        val reason: String,
        val restoreCapture: Boolean,
    )

    data class Acceptance(
        val accepted: Boolean,
        val exactRetry: Boolean = false,
        val errorCode: String? = null,
        val errorRef: String? = null,
    )

    data class AckEffect(
        val matched: Boolean = false,
        val startAccepted: Boolean = false,
        val ended: Boolean = false,
        val pendingEnd: PendingEnd? = null,
    )

    data class ErrorEffect(
        val matchedCurrent: Boolean = false,
        val restoreSessionAudio: Boolean = false,
        val restoreCapture: Boolean = false,
    )

    private data class OperationKey(val action: String, val id: String, val rev: Long)

    private val pendingSnapshots = linkedMapOf<OperationKey, SessionSnapshot>()
    private var desiredSession: SessionSnapshot? = null
    private var acknowledgedSession: SessionSnapshot? = null
    private var acknowledgedAction: String? = null
    private var latestAction: String? = null
    private var pendingEnd: PendingEnd? = null
    private var connectionSessionReady = false

    @Synchronized
    fun start(snapshot: SessionSnapshot): Acceptance {
        val current = desiredSession
        if (current != null && current.id != snapshot.id) {
            return Acceptance(
                accepted = false,
                errorCode = "session_already_active",
                errorRef = "active=${current.id} requested=${snapshot.id}",
            )
        }
        if (pendingEnd != null) {
            return Acceptance(false, errorCode = "session_end_pending", errorRef = snapshot.id)
        }
        if (current != null) {
            if (latestAction == "start" && current == snapshot) {
                pendingSnapshots[OperationKey("start", snapshot.id, snapshot.rev)] = snapshot
                return Acceptance(accepted = true, exactRetry = true)
            }
            return Acceptance(
                accepted = false,
                errorCode = if (snapshot.rev == current.rev) {
                    "revision_conflict"
                } else {
                    "session_already_active"
                },
                errorRef = snapshot.id,
            )
        }
        desiredSession = snapshot
        acknowledgedSession = null
        acknowledgedAction = null
        latestAction = "start"
        connectionSessionReady = false
        pendingSnapshots[OperationKey("start", snapshot.id, snapshot.rev)] = snapshot
        return Acceptance(accepted = true)
    }

    @Synchronized
    fun update(snapshot: SessionSnapshot): Acceptance {
        val current = desiredSession
        if (current == null || current.id != snapshot.id) {
            return Acceptance(false, errorCode = "session_not_active", errorRef = snapshot.id)
        }
        if (pendingEnd != null) {
            return Acceptance(false, errorCode = "session_end_pending", errorRef = snapshot.id)
        }
        if (snapshot.rev == current.rev) {
            if (latestAction == "update" && snapshot == current) {
                pendingSnapshots[OperationKey("update", snapshot.id, snapshot.rev)] = snapshot
                return Acceptance(accepted = true, exactRetry = true)
            }
            return Acceptance(
                false,
                errorCode = "revision_conflict",
                errorRef = snapshot.rev.toString(),
            )
        }
        if (snapshot.rev < current.rev) {
            return Acceptance(
                false,
                errorCode = "stale_revision",
                errorRef = snapshot.rev.toString(),
            )
        }
        desiredSession = snapshot
        latestAction = "update"
        pendingSnapshots[OperationKey("update", snapshot.id, snapshot.rev)] = snapshot
        return Acceptance(accepted = true)
    }

    @Synchronized
    fun end(id: String, rev: Long, reason: String, restoreCapture: Boolean): Acceptance {
        val current = desiredSession
        if (current == null || current.id != id) {
            return Acceptance(false, errorCode = "session_not_active", errorRef = id)
        }
        if (rev <= current.rev) {
            return Acceptance(
                false,
                errorCode = "stale_revision",
                errorRef = rev.toString(),
            )
        }
        val existing = pendingEnd
        if (existing != null) {
            return if (existing.id == id && existing.rev == rev && existing.reason == reason) {
                Acceptance(accepted = true, exactRetry = true)
            } else {
                Acceptance(false, errorCode = "session_end_pending", errorRef = id)
            }
        }
        pendingEnd = PendingEnd(id, rev, reason, restoreCapture)
        latestAction = "end"
        return Acceptance(accepted = true)
    }

    /** Invalidates connection-scoped audio authority while retaining desired/acknowledged data. */
    @Synchronized
    fun markConnectionUnready() {
        connectionSessionReady = false
        pendingSnapshots.clear()
    }

    /** Prepares the desired snapshot as a start on the current fresh connection. */
    @Synchronized
    fun prepareConnectionStart(): SessionSnapshot? {
        connectionSessionReady = false
        pendingSnapshots.clear()
        return desiredSession?.also {
            latestAction = "start"
            pendingSnapshots[OperationKey("start", it.id, it.rev)] = it
        }
    }

    @Synchronized
    fun acknowledge(action: String, id: String, rev: Long): AckEffect {
        if (action == "end") {
            val end = pendingEnd
            if (end == null || end.id != id || end.rev != rev) return AckEffect()
            desiredSession = null
            acknowledgedSession = null
            acknowledgedAction = null
            latestAction = null
            pendingEnd = null
            pendingSnapshots.clear()
            connectionSessionReady = false
            return AckEffect(matched = true, ended = true)
        }

        val key = OperationKey(action, id, rev)
        val snapshot = pendingSnapshots.remove(key) ?: return AckEffect()
        if (desiredSession?.id != id) return AckEffect()
        val previous = acknowledgedSession
        if (previous != null && previous.id == id && snapshot.rev < previous.rev) {
            return AckEffect()
        }
        acknowledgedSession = snapshot
        acknowledgedAction = action
        if (action == "start") connectionSessionReady = true
        return AckEffect(
            matched = true,
            startAccepted = action == "start",
            pendingEnd = pendingEnd?.takeIf { action == "start" && it.id == id },
        )
    }

    /**
     * Rolls back only an error for the current desired operation. A delayed older-revision error
     * is consumed without overwriting a newer desired snapshot.
     */
    @Synchronized
    fun fail(ref: String?, rev: Long): ErrorEffect {
        val id = ref ?: return ErrorEffect()
        val end = pendingEnd
        if (end != null && end.id == id && end.rev == rev) {
            pendingEnd = null
            desiredSession = acknowledgedSession ?: desiredSession
            latestAction = acknowledgedAction
            pendingSnapshots.keys.removeAll { it.id == id }
            return ErrorEffect(
                matchedCurrent = true,
                restoreSessionAudio = connectionSessionReady && desiredSession != null,
                restoreCapture = end.restoreCapture,
            )
        }

        val failedSnapshotPending = pendingSnapshots.keys.any { it.id == id && it.rev == rev }
        pendingSnapshots.keys.removeAll { it.id == id && it.rev == rev }
        val desired = desiredSession
        if (!failedSnapshotPending || desired == null || desired.id != id || desired.rev != rev) {
            return ErrorEffect()
        }
        if (acknowledgedSession == null) {
            // An end queued behind an initial start can never succeed after that start is
            // rejected. Drop it now so a disconnect between the two server errors cannot leave
            // this reusable client permanently stuck in end-pending state.
            pendingEnd = null
        }
        pendingSnapshots.keys.removeAll { it.id == id && it.rev <= rev }
        desiredSession = acknowledgedSession
        latestAction = acknowledgedAction
        if (desiredSession == null) connectionSessionReady = false
        return ErrorEffect(
            matchedCurrent = true,
            restoreSessionAudio = connectionSessionReady && desiredSession != null,
        )
    }

    @Synchronized
    fun desired(): SessionSnapshot? = desiredSession

    @Synchronized
    fun captureAllowed(): Boolean = SessionAudioPolicy.captureAllowed(
        hasActiveSession = desiredSession != null,
        endPending = pendingEnd != null,
        sessionAcknowledgedOnConnection = connectionSessionReady,
    )

    @Synchronized
    fun wakeKwsEnabled(started: Boolean, ready: Boolean, wakeRequested: Boolean): Boolean =
        SessionAudioPolicy.wakeKwsEnabled(
            started = started,
            ready = ready,
            wakeRequested = wakeRequested,
            hasActiveSession = desiredSession != null,
            endPending = pendingEnd != null,
            sessionAcknowledgedOnConnection = connectionSessionReady,
        )
}
