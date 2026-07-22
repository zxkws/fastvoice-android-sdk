package com.zxkws.fastvoice.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientProtocolPoliciesTest {
    @Test
    fun invalidationCannotSplitAValidatedCallbackFromItsMutation() {
        val sessions = ClientSessionEpoch()
        val token = sessions.begin()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidationStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val effect = executor.submit<Boolean> {
                sessions.runIfCurrent(token) {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val invalidation = executor.submit {
                invalidationStarted.countDown()
                sessions.invalidate()
            }
            assertTrue(invalidationStarted.await(2, TimeUnit.SECONDS))
            assertFalse(invalidation.isDone)
            release.countDown()
            assertTrue(effect.get(2, TimeUnit.SECONDS))
            invalidation.get(2, TimeUnit.SECONDS)
            val staleEffect = AtomicBoolean(false)
            assertFalse(sessions.runIfCurrent(token) { staleEffect.set(true) })
            assertFalse(staleEffect.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun playbackGenerationNeverRollsBackAndEndMustMatch() {
        assertFalse(PlaybackCommandGenerationPolicy.acceptsReset(4, 5))
        assertTrue(PlaybackCommandGenerationPolicy.acceptsReset(5, 5))
        assertTrue(PlaybackCommandGenerationPolicy.acceptsReset(6, 5))
        assertFalse(PlaybackCommandGenerationPolicy.acceptsEnd(4, 5))
        assertTrue(PlaybackCommandGenerationPolicy.acceptsEnd(5, 5))
        assertFalse(PlaybackCommandGenerationPolicy.acceptsEnd(6, 5))
    }

    @Test
    fun commandsV1StatesAreDisplayOnlyButLegacyStatesStillDriveTransport() {
        assertFalse(ServerStateSideEffectPolicy.appliesLegacySleepingSideEffects(true, "sleeping"))
        assertTrue(ServerStateSideEffectPolicy.appliesLegacySleepingSideEffects(false, "sleeping"))
        assertFalse(ServerStateSideEffectPolicy.appliesLegacyListeningSideEffects(true, "listening"))
        assertTrue(ServerStateSideEffectPolicy.appliesLegacyListeningSideEffects(false, "listening"))
    }

    @Test
    fun localCandidateRejectAndTimeoutReleaseOnlyTheirOwnHold() {
        val state = LocalCommandPrePauseState()
        assertTrue(state.begin("lc1", 7, 20))
        assertEquals(
            LocalCommandPrePauseState.Outcome.RESUME,
            state.decide("lc1", 7, "rejected", 7, 20, serverPaused = false),
        )
        assertFalse(state.blocksPlayback(7, 20))

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
        assertTrue(state.blocksPlayback(8, 30))
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
    fun turnErrorPromptCompletionUsesLatestServerUplinkRequest() {
        val state = TurnErrorUplinkState()
        assertFalse(state.onServerCommand(true, localPromptPlaying = true))
        assertTrue(state.afterTerminal(commandProtocol = true, legacyListening = false))
        assertFalse(state.onServerCommand(false, localPromptPlaying = true))
        assertFalse(state.afterTerminal(commandProtocol = true, legacyListening = true))
    }

    @Test
    fun localCommandTimeoutAddsGraceWithinAdvertisedBounds() {
        assertEquals(1_050, LocalCommandTimeoutPolicy.clientTimeoutMs(800))
        assertEquals(450, LocalCommandTimeoutPolicy.clientTimeoutMs(100))
        assertEquals(3_250, LocalCommandTimeoutPolicy.clientTimeoutMs(8_000))
    }
}
