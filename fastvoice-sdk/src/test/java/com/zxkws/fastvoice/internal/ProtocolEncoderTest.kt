package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.OrderSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolEncoderTest {
    @Test
    fun helloContainsOnlyTheWakePreference() {
        assertEquals(
            "{\"type\":\"hello\",\"wake\":true}",
            ProtocolEncoder.hello(true),
        )
        assertEquals(
            "{\"type\":\"hello\",\"wake\":false}",
            ProtocolEncoder.hello(false),
        )
    }

    @Test
    fun orderSnapshotsAreCompleteTypedMessages() {
        val snapshot = OrderSnapshot(
            "o1",
            2,
            linkedMapOf("park_id" to "p1", "passenger_count" to 3),
        )

        assertEquals(
            "{\"type\":\"order.start\",\"id\":\"o1\",\"rev\":2," +
                "\"context\":{\"park_id\":\"p1\",\"passenger_count\":3}}",
            ProtocolEncoder.orderStart(snapshot),
        )
        assertEquals(
            "{\"type\":\"order.update\",\"id\":\"o1\",\"rev\":2," +
                "\"context\":{\"park_id\":\"p1\",\"passenger_count\":3}}",
            ProtocolEncoder.orderUpdate(snapshot),
        )
        assertEquals(
            "{\"type\":\"order.end\",\"id\":\"o1\",\"rev\":3,\"reason\":\"completed\"}",
            ProtocolEncoder.orderEnd("o1", 3, "completed"),
        )
    }

    @Test
    fun arrivalAndCruiseHaveDifferentExactShapes() {
        assertEquals(
            "{\"type\":\"tour.play\",\"id\":\"a1\",\"source\":\"arrival\"," +
                "\"content\":\"arrival_prompt\",\"order_id\":\"o1\",\"order_rev\":2," +
                "\"spot_id\":\"s1\"}",
            ProtocolEncoder.tourPlay(
                "a1", "arrival", "arrival_prompt", "o1", 2, "s1",
            ),
        )
        assertEquals(
            "{\"type\":\"tour.play\",\"id\":\"c1\",\"source\":\"cruise\"," +
                "\"content\":\"park_welcome\"}",
            ProtocolEncoder.tourPlay("c1", "cruise", "park_welcome"),
        )
    }

    @Test
    fun playbackControlAndCandidateMessagesUseTheNewNames() {
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
