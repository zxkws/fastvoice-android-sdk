package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.OrderSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderOperationStateTest {
    private fun snapshot(rev: Long, spot: String = "s$rev") =
        OrderSnapshot("o1", rev, mapOf("current_spot_id" to spot))

    @Test
    fun freshStartAndReconnectWaitForMatchingStartAckBeforeAudio() {
        val state = OrderOperationState()
        val order = snapshot(1)

        assertTrue(state.start(order).accepted)
        assertFalse(state.captureAllowed())
        assertFalse(state.wakeKwsEnabled(started = true, ready = true, wakeRequested = true))

        assertTrue(state.acknowledge("start", "o1", 1).startAccepted)
        assertTrue(state.captureAllowed())
        assertTrue(state.wakeKwsEnabled(started = true, ready = true, wakeRequested = true))
        assertFalse(state.wakeKwsEnabled(started = true, ready = true, wakeRequested = false))

        state.markConnectionUnready()
        assertFalse(state.captureAllowed())
        assertEquals(order, state.prepareConnectionStart())
        assertFalse(state.captureAllowed())
        assertFalse(state.acknowledge("start", "o1", 99).startAccepted)
        assertFalse(state.captureAllowed())
        assertTrue(state.acknowledge("start", "o1", 1).startAccepted)
        assertTrue(state.captureAllowed())
    }

    @Test
    fun rejectedInitialStartClearsDesiredAndAllowsAnotherOrder() {
        val state = OrderOperationState()
        state.start(snapshot(1))

        val failed = state.fail("o1", 1)

        assertTrue(failed.matchedCurrent)
        assertFalse(state.hasDesiredOrder())
        assertFalse(state.acknowledge("start", "o1", 1).matched)
        assertTrue(
            state.start(OrderSnapshot("o2", 1, mapOf("current_spot_id" to "s2"))).accepted,
        )
    }

    @Test
    fun rejectedInitialStartAlsoDropsAnEndQueuedBehindIt() {
        val state = OrderOperationState()
        state.start(snapshot(1))
        assertTrue(state.end("o1", 2, "cancelled", restoreCapture = false).accepted)

        assertTrue(state.fail("o1", 1).matchedCurrent)

        assertFalse(state.hasDesiredOrder())
        assertTrue(
            state.start(OrderSnapshot("o2", 1, mapOf("current_spot_id" to "s2"))).accepted,
        )
    }

    @Test
    fun rejectedUpdateRollsBackToLastAcknowledgedSnapshotForReconnect() {
        val state = OrderOperationState()
        val accepted = snapshot(1)
        val rejected = snapshot(2)
        state.start(accepted)
        state.acknowledge("start", "o1", 1)
        state.update(rejected)

        assertTrue(state.fail("o1", 2).matchedCurrent)
        assertEquals(accepted, state.desired())

        state.markConnectionUnready()
        assertEquals(accepted, state.prepareConnectionStart())
    }

    @Test
    fun delayedOldRevisionErrorCannotRollbackNewerDesiredSnapshot() {
        val state = OrderOperationState()
        state.start(snapshot(1))
        state.acknowledge("start", "o1", 1)
        state.update(snapshot(2))
        val newest = snapshot(3)
        state.update(newest)

        assertFalse(state.fail("o1", 2).matchedCurrent)
        assertEquals(newest, state.desired())
    }

    @Test
    fun rejectedEndClearsPendingAndRestoresAcknowledgedOrderAudio() {
        val state = OrderOperationState()
        val accepted = snapshot(1)
        state.start(accepted)
        state.acknowledge("start", "o1", 1)
        assertTrue(state.end("o1", 2, "completed", restoreCapture = true).accepted)
        assertFalse(state.captureAllowed())

        val failed = state.fail("o1", 2)

        assertTrue(failed.matchedCurrent)
        assertTrue(failed.restoreOrderAudio)
        assertTrue(failed.restoreCapture)
        assertEquals(accepted, state.desired())
        assertTrue(state.captureAllowed())
    }

    @Test
    fun endAckClearsDesiredAndAcknowledgedState() {
        val state = OrderOperationState()
        state.start(snapshot(1))
        state.acknowledge("start", "o1", 1)
        state.end("o1", 2, "completed", restoreCapture = false)

        assertTrue(state.acknowledge("end", "o1", 2).ended)
        assertNull(state.desired())
        assertFalse(state.captureAllowed())
    }

    @Test
    fun unsolicitedDuplicateAndRegressiveAcksDoNotMutateState() {
        val state = OrderOperationState()
        state.start(snapshot(1))
        assertFalse(state.acknowledge("start", "other", 1).matched)
        assertTrue(state.acknowledge("start", "o1", 1).matched)
        assertFalse(state.acknowledge("start", "o1", 1).matched)

        state.update(snapshot(2))
        state.update(snapshot(3))
        assertTrue(state.acknowledge("update", "o1", 3).matched)
        assertFalse(state.acknowledge("update", "o1", 2).matched)
        assertEquals(snapshot(3), state.desired())
    }
}
