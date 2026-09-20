package com.zxkws.fastvoice.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KwsAndCapturePolicyTest {
    @Test
    fun wakeLabelsAreIgnoredAndControlsRemainRoutable() {
        assertEquals(
            LocalCommandSpotter.KeywordRoute.IGNORE,
            LocalCommandSpotter.routeKeyword("咘嘀"),
        )
        assertEquals(
            KeywordRoutingPolicy.Dispatch.CONTROL,
            KeywordRoutingPolicy.dispatch(LocalCommandSpotter.routeKeyword("停止")),
        )
        assertEquals(
            KeywordRoutingPolicy.Dispatch.IGNORE,
            KeywordRoutingPolicy.dispatch(LocalCommandSpotter.KeywordRoute.IGNORE),
        )
    }

    @Test
    fun controlTargetsServerPlaybackOnly() {
        assertEquals(
            KeywordRoutingPolicy.ControlTarget.NONE,
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
    fun playbackAndEchoTailUplinkUseUnmodifiedAecOutput() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val processed = byteArrayOf(0, 0, 1, 0)
        assertSame(processed, CaptureFramePolicy.uplink(raw, processed, hasRecentRender = true))
    }

    @Test
    fun idleUplinkPreservesRawMicAndSwitchesWithRenderAvailability() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val processed = byteArrayOf(0, 0, 1, 0)
        for (hasRender in listOf(false, true, true, false)) {
            assertSame(
                if (hasRender) processed else raw,
                CaptureFramePolicy.uplink(raw, processed, hasRecentRender = hasRender),
            )
        }
    }

    @Test
    fun aecProcessingFailureCanRetainExistingRawFallback() {
        val raw = byteArrayOf(1, 2, 3, 4)
        // AudioEngine reports AEC failures and supplies raw MIC as the failed processing result.
        assertSame(raw, CaptureFramePolicy.uplink(raw, raw, hasRecentRender = true))
    }

    @Test
    fun capturePreRollIsFrameAlignedAndBounded() {
        assertEquals(0, CapturePreRollPolicy.frameCount(0))
        assertEquals(40, CapturePreRollPolicy.frameCount(800))
        assertEquals(90, CapturePreRollPolicy.frameCount(5_000))
        assertEquals(50, CapturePreRollPolicy.startIndex(90, 40))
        assertEquals(0, CapturePreRollPolicy.startIndex(20, 40))
    }

}
