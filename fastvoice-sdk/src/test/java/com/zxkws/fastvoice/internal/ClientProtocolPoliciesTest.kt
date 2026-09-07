package com.zxkws.fastvoice.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientProtocolPoliciesTest {
    @Test
    fun stateReasonIsReservedForSleepingInactivityTimeout() {
        assertTrue(CurrentProtocol.acceptsState("sleeping", null))
        assertTrue(CurrentProtocol.acceptsState("sleeping", "inactivity_timeout"))
        assertFalse(CurrentProtocol.acceptsState("sleeping", "other"))
        assertFalse(CurrentProtocol.acceptsState("listening", "inactivity_timeout"))
        assertFalse(CurrentProtocol.acceptsState(null, null))
    }

    @Test
    fun onlySleepingAuthorizesFreshWake() {
        assertTrue(CurrentProtocol.authorizesWake("sleeping"))
        for (state in CurrentProtocol.STATE_VALUES - "sleeping") {
            assertFalse(state, CurrentProtocol.authorizesWake(state))
        }
        assertFalse(CurrentProtocol.authorizesWake(null))
    }

    @Test
    fun localCandidateRejectAndTimeoutReleaseOnlyTheirOwnHold() {
        val state = LocalCommandPrePauseState()
        assertTrue(state.begin("lc1", 7, 20))
        assertEquals(
            LocalCommandPrePauseState.Outcome.RESUME,
            state.decide("lc1", 7, "rejected", 7, 20, serverPaused = false),
        )

        assertTrue(state.begin("lc2", 7, 20))
        assertEquals(
            LocalCommandPrePauseState.Outcome.CLEARED,
            state.timeout("lc2", 7, 20, 7, 20, serverPaused = true, requireAccepted = false),
        )
    }

    @Test
    fun acceptedCandidateHoldsUntilFormalActionOrBoundedWatchdog() {
        val state = LocalCommandPrePauseState()
        state.begin("lc", 8, 30)
        assertEquals(
            LocalCommandPrePauseState.Outcome.ACCEPTED_HOLD,
            state.decide("lc", 8, "accepted", 8, 30, serverPaused = false),
        )
        assertEquals(
            LocalCommandPrePauseState.Outcome.IGNORED,
            state.timeout("lc", 8, 30, 8, 30, serverPaused = false, requireAccepted = false),
        )
        assertEquals(
            LocalCommandPrePauseState.Outcome.RESUME,
            state.timeout("lc", 8, 30, 8, 30, serverPaused = false, requireAccepted = true),
        )
    }

    @Test
    fun failureTerminalBlocksSuccessAndRetainsFirstReason() {
        val state = PlaybackTerminalState()
        state.begin(7, 10)
        assertEquals(
            PlaybackTerminalState.FailureResult.RECORDED,
            state.fail(7, 10, "write=-6"),
        )
        assertEquals(
            PlaybackTerminalState.FailureResult.ALREADY_FAILED,
            state.fail(7, 10, "later"),
        )
        assertFalse(state.finish(7, 10))
        assertEquals("write=-6", state.failureReason(7, 10))
        state.begin(8, 11)
        assertNull(state.failureReason(8, 11))
        assertTrue(state.finish(8, 11))

        state.invalidate(8, 12)
        assertEquals(
            PlaybackTerminalState.FailureResult.STALE_OR_FINISHED,
            state.fail(8, 12, "late callback after stop"),
        )
        assertNull(state.failureReason(8, 12))
    }

    @Test
    fun localCommandTimeoutAddsGraceWithinAdvertisedBounds() {
        assertEquals(1_050, LocalCommandTimeoutPolicy.clientTimeoutMs(800))
        assertEquals(450, LocalCommandTimeoutPolicy.clientTimeoutMs(100))
        assertEquals(3_250, LocalCommandTimeoutPolicy.clientTimeoutMs(8_000))
    }

    @Test
    fun playbackRequiresExactTwentyMillisecondFramesAndSomePhysicalAudio() {
        assertTrue(PlaybackFramePolicy.acceptsDecodedFrame(960, 960))
        assertFalse(PlaybackFramePolicy.acceptsDecodedFrame(0, 960))
        assertFalse(PlaybackFramePolicy.acceptsDecodedFrame(1_920, 960))
        assertFalse(PlaybackFramePolicy.canFinish(0))
        assertTrue(PlaybackFramePolicy.canFinish(1_920))
    }
}
