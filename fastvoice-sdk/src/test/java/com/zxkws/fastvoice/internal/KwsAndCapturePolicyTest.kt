package com.zxkws.fastvoice.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KwsAndCapturePolicyTest {
    @Test
    fun wakeNeverFallsThroughToControl() {
        assertEquals(
            KeywordRoutingPolicy.Dispatch.WAKE,
            KeywordRoutingPolicy.dispatch(
                LocalCommandSpotter.KeywordRoute.WAKE,
                playbackOrPromptExpected = false,
                playbackActive = false,
                wakeArmed = true,
            ),
        )
        assertEquals(
            KeywordRoutingPolicy.Dispatch.IGNORE,
            KeywordRoutingPolicy.dispatch(
                LocalCommandSpotter.KeywordRoute.WAKE,
                playbackOrPromptExpected = true,
                playbackActive = true,
                wakeArmed = true,
            ),
        )
    }

    @Test
    fun playbackSuppressesWakeButNotControl() {
        assertEquals(
            KeywordRoutingPolicy.Dispatch.CONTROL,
            KeywordRoutingPolicy.dispatch(
                LocalCommandSpotter.KeywordRoute.CONTROL,
                playbackOrPromptExpected = true,
                playbackActive = true,
                wakeArmed = false,
            ),
        )
    }

    @Test
    fun disablingAnEnabledKwsSessionResetsItsNativeStream() {
        assertTrue(KeywordRoutingPolicy.shouldResetStream(true, false))
        assertFalse(KeywordRoutingPolicy.shouldResetStream(false, false))
        assertFalse(KeywordRoutingPolicy.shouldResetStream(true, true))
        assertTrue(KeywordRoutingPolicy.shouldResetPromptStream(true, false, false))
        assertFalse(KeywordRoutingPolicy.shouldResetPromptStream(true, false, true))
    }

    @Test
    fun controlDuringClientFallbackTargetsTheLocalPrompt() {
        assertEquals(
            KeywordRoutingPolicy.ControlTarget.LOCAL_PROMPT,
            KeywordRoutingPolicy.controlTarget(
                serverPlaybackGeneration = -1,
                playbackOrPromptExpected = true,
                playbackActive = false,
            ),
        )
        assertEquals(
            KeywordRoutingPolicy.ControlTarget.SERVER_PLAYBACK,
            KeywordRoutingPolicy.controlTarget(
                serverPlaybackGeneration = 7,
                playbackOrPromptExpected = true,
                playbackActive = false,
            ),
        )
    }

    @Test
    fun capturePreRollIsFrameAlignedBoundedAndWakeUsesFullRing() {
        assertEquals(0, CapturePreRollPolicy.frameCount(0, wakeCapturePending = false))
        assertEquals(40, CapturePreRollPolicy.frameCount(800, wakeCapturePending = false))
        assertEquals(90, CapturePreRollPolicy.frameCount(5_000, wakeCapturePending = false))
        assertEquals(90, CapturePreRollPolicy.frameCount(1, wakeCapturePending = true))
        assertEquals(50, CapturePreRollPolicy.startIndex(90, 40))
        assertEquals(0, CapturePreRollPolicy.startIndex(20, 40))
    }

    @Test
    fun idleAndEndingOrdersCannotCaptureOrArmWakeKws() {
        assertFalse(
            OrderAudioPolicy.captureAllowed(
                hasActiveOrder = false,
                endPending = false,
                orderAcknowledgedOnConnection = false,
            ),
        )
        assertFalse(
            OrderAudioPolicy.captureAllowed(
                hasActiveOrder = true,
                endPending = true,
                orderAcknowledgedOnConnection = true,
            ),
        )
        assertFalse(
            OrderAudioPolicy.wakeKwsEnabled(
                started = true,
                ready = true,
                wakeRequested = true,
                hasActiveOrder = false,
                endPending = false,
                orderAcknowledgedOnConnection = false,
            ),
        )
        assertTrue(
            OrderAudioPolicy.wakeKwsEnabled(
                started = true,
                ready = true,
                wakeRequested = true,
                hasActiveOrder = true,
                endPending = false,
                orderAcknowledgedOnConnection = true,
            ),
        )
        assertFalse(
            OrderAudioPolicy.wakeKwsEnabled(
                started = true,
                ready = true,
                wakeRequested = false,
                hasActiveOrder = true,
                endPending = false,
                orderAcknowledgedOnConnection = true,
            ),
        )
    }
}
