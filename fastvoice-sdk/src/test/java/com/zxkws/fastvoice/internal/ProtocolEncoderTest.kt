package com.zxkws.fastvoice.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolEncoderTest {
    @Test
    fun helloAlwaysRequestsWake() {
        assertEquals(
            "{\"type\":\"hello\",\"wake\":true}",
            ProtocolEncoder.hello(),
        )
    }

    @Test
    fun locationAndWelcomeMessagesUseExactShapes() {
        assertEquals(
            "{\"type\":\"location.update\",\"park\":\"nanyuan\",\"spot\":\"station_1907\"}",
            ProtocolEncoder.locationUpdate("nanyuan", "station_1907"),
        )
        assertEquals(
            "{\"type\":\"location.update\",\"park\":\"nanyuan\"}",
            ProtocolEncoder.locationUpdate("nanyuan", null),
        )
        assertEquals("{\"type\":\"location.clear\"}", ProtocolEncoder.locationClear())
        assertEquals(
            "{\"type\":\"welcome.play\",\"park\":\"nanyuan\",\"spot\":\"north_gate\"}",
            ProtocolEncoder.welcomePlay("nanyuan", "north_gate"),
        )
    }

    @Test
    fun playbackControlAndCandidateMessagesUseCurrentNames() {
        assertEquals(
            "{\"type\":\"wake\",\"word\":\"布丁\"}",
            ProtocolEncoder.wake("布丁"),
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
