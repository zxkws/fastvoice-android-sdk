package com.zxkws.fastvoice.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolEncoderTest {
    @Test
    fun helloAlwaysRequestsWakeAndBindsAreaOnce() {
        assertEquals(
            "{\"type\":\"hello\",\"wake\":true,\"area_id\":\"18\"}",
            ProtocolEncoder.hello("18"),
        )
    }

    @Test
    fun locationAndWelcomeMessagesUseExactShapes() {
        assertEquals(
            "{\"type\":\"location.update\",\"station_name\":\"藻园门站-靠近西苑地铁\"}",
            ProtocolEncoder.locationUpdate("藻园门站-靠近西苑地铁"),
        )
        assertEquals(
            "{\"type\":\"location.update\",\"latitude\":39.81,\"longitude\":116.37}",
            ProtocolEncoder.locationUpdate(null, 39.81, 116.37),
        )
        assertEquals("{\"type\":\"location.clear\"}", ProtocolEncoder.locationClear())
        assertEquals(
            "{\"type\":\"welcome.play\",\"station_name\":\"藻园门站-靠近西苑地铁\"}",
            ProtocolEncoder.welcomePlay("藻园门站-靠近西苑地铁"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun helloRejectsBlankAreaId() {
        ProtocolEncoder.hello("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun locationUpdateRejectsEmptyPayload() {
        ProtocolEncoder.locationUpdate(null)
    }

    @Test
    fun playbackControlAndCandidateMessagesUseCurrentNames() {
        assertEquals(
            "{\"type\":\"wake\",\"word\":\"咘嘀\"}",
            ProtocolEncoder.wake("咘嘀"),
        )
        assertEquals(
            "{\"type\":\"control.candidate\",\"id\":\"lc-7\",\"playback_id\":3," +
                "\"name\":\"换\\\"一个\\n\"}",
            ProtocolEncoder.controlCandidate("lc-7", 3, "换\"一个\n"),
        )
        assertEquals("{\"type\":\"turn.cancel\"}", ProtocolEncoder.turnCancel())
        assertEquals(
            "{\"type\":\"control.result\",\"id\":\"k1\",\"ok\":false,\"code\":\"stale\"}",
            ProtocolEncoder.controlResult("k1", false, "stale"),
        )
        assertEquals(
            "{\"type\":\"playback.progress\",\"id\":3,\"position_ms\":1250}",
            ProtocolEncoder.playbackProgress(3, 1_250),
        )
        assertEquals(
            "{\"type\":\"playback.finished\",\"id\":3}",
            ProtocolEncoder.playbackFinished(3),
        )
        assertEquals(
            "{\"type\":\"playback.failed\",\"id\":3,\"code\":\"audio_track_write_failed\"}",
            ProtocolEncoder.playbackFailed(3, "audio_track_write_failed"),
        )
    }
}
