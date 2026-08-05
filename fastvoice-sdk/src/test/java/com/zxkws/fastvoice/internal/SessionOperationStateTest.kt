package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOperationStateTest {
    private fun snapshot(rev: Long, spot: String = "s$rev") =
        SessionSnapshot("s1", rev, mapOf("mode" to spot))

    @Test
    fun freshStartAndReconnectWaitForMatchingStartAckBeforeAudio() {
        val state = SessionOperationState()
        val session = snapshot(1)

        assertTrue(state.start(session).accepted)
        assertFalse(state.captureAllowed())
        assertFalse(state.wakeKwsEnabled(started = true, ready = true, wakeRequested = true))

        assertTrue(state.acknowledge("start", "s1", 1).startAccepted)
        assertTrue(state.captureAllowed())
        assertTrue(state.wakeKwsEnabled(started = true, ready = true, wakeRequested = true))
        assertFalse(state.wakeKwsEnabled(started = true, ready = true, wakeRequested = false))

        state.markConnectionUnready()
        assertFalse(state.captureAllowed())
        assertEquals(session, state.prepareConnectionStart())
        assertFalse(state.captureAllowed())
        assertFalse(state.acknowledge("start", "s1", 99).startAccepted)
        assertFalse(state.captureAllowed())
        assertTrue(state.acknowledge("start", "s1", 1).startAccepted)
        assertTrue(state.captureAllowed())
    }

    @Test
    fun staleStartAckCannotRearmWakeBetweenDisconnectAndReconnectStart() {
        val state = SessionOperationState()
        val session = snapshot(1)
        state.start(session)
        state.acknowledge("start", session.id, session.rev)

        state.markConnectionUnready()

        assertFalse(state.acknowledge("start", session.id, session.rev).matched)
        assertFalse(state.captureAllowed())
        assertFalse(state.wakeKwsEnabled(started = true, ready = true, wakeRequested = true))

        assertEquals(session, state.prepareConnectionStart())
        assertTrue(state.acknowledge("start", session.id, session.rev).startAccepted)
        assertTrue(state.captureAllowed())
        assertTrue(state.wakeKwsEnabled(started = true, ready = true, wakeRequested = true))
    }

    @Test
    fun rejectedInitialStartClearsDesiredAndAllowsAnotherSession() {
        val state = SessionOperationState()
        state.start(snapshot(1))

        val failed = state.fail("s1", 1)

        assertTrue(failed.matchedCurrent)
        assertNull(state.desired())
        assertFalse(state.acknowledge("start", "s1", 1).matched)
        assertTrue(
            state.start(SessionSnapshot("s2", 1, mapOf("mode" to "s2"))).accepted,
        )
    }

    @Test
    fun rejectedInitialStartAlsoDropsAnEndQueuedBehindIt() {
        val state = SessionOperationState()
        state.start(snapshot(1))
        assertTrue(state.end("s1", 2, "cancelled", restoreCapture = false).accepted)

        assertTrue(state.fail("s1", 1).matchedCurrent)

        assertNull(state.desired())
        assertTrue(
            state.start(SessionSnapshot("s2", 1, mapOf("mode" to "s2"))).accepted,
        )
    }

    @Test
    fun rejectedUpdateRollsBackToLastAcknowledgedSnapshotForReconnect() {
        val state = SessionOperationState()
        val accepted = snapshot(1)
        val rejected = snapshot(2)
        state.start(accepted)
        state.acknowledge("start", "s1", 1)
        state.update(rejected)

        assertTrue(state.fail("s1", 2).matchedCurrent)
        assertEquals(accepted, state.desired())

        state.markConnectionUnready()
        assertEquals(accepted, state.prepareConnectionStart())
    }

    @Test
    fun delayedOldRevisionErrorCannotRollbackNewerDesiredSnapshot() {
        val state = SessionOperationState()
        state.start(snapshot(1))
        state.acknowledge("start", "s1", 1)
        state.update(snapshot(2))
        val newest = snapshot(3)
        state.update(newest)

        assertFalse(state.fail("s1", 2).matchedCurrent)
        assertEquals(newest, state.desired())
    }

    @Test
    fun rejectedEndClearsPendingAndRestoresAcknowledgedSessionAudio() {
        val state = SessionOperationState()
        val accepted = snapshot(1)
        state.start(accepted)
        state.acknowledge("start", "s1", 1)
        assertTrue(state.end("s1", 2, "completed", restoreCapture = true).accepted)
        assertFalse(state.captureAllowed())

        val failed = state.fail("s1", 2)

        assertTrue(failed.matchedCurrent)
        assertTrue(failed.restoreSessionAudio)
        assertTrue(failed.restoreCapture)
        assertEquals(accepted, state.desired())
        assertTrue(state.captureAllowed())
    }

    @Test
    fun endAckClearsDesiredAndAcknowledgedState() {
        val state = SessionOperationState()
        state.start(snapshot(1))
        state.acknowledge("start", "s1", 1)
        state.end("s1", 2, "completed", restoreCapture = false)

        assertTrue(state.acknowledge("end", "s1", 2).ended)
        assertNull(state.desired())
        assertFalse(state.captureAllowed())
    }

    @Test
    fun unsolicitedDuplicateAndRegressiveAcksDoNotMutateState() {
        val state = SessionOperationState()
        state.start(snapshot(1))
        assertFalse(state.acknowledge("start", "other", 1).matched)
        assertTrue(state.acknowledge("start", "s1", 1).matched)
        assertFalse(state.acknowledge("start", "s1", 1).matched)

        state.update(snapshot(2))
        state.update(snapshot(3))
        assertTrue(state.acknowledge("update", "s1", 3).matched)
        assertFalse(state.acknowledge("update", "s1", 2).matched)
        assertEquals(snapshot(3), state.desired())
    }

    @Test
    fun localRejectionsUseProtocolLevelSessionCodes() {
        val state = SessionOperationState()
        state.start(snapshot(2))

        assertEquals(
            "session_already_active",
            state.start(SessionSnapshot("s2", 1, emptyMap())).errorCode,
        )
        assertEquals(
            "revision_conflict",
            state.update(SessionSnapshot("s1", 2, mapOf("mode" to "different"))).errorCode,
        )
        assertEquals("stale_revision", state.update(snapshot(1)).errorCode)
        assertEquals("stale_revision", state.end("s1", 2, "completed", false).errorCode)
    }
}
