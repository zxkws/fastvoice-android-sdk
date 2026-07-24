package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.ContentRequest
import com.zxkws.fastvoice.SessionRef
import com.zxkws.fastvoice.SessionSnapshot
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
    fun sessionSnapshotsAreCompleteTypedMessages() {
        val snapshot = SessionSnapshot(
            "s1",
            2,
            linkedMapOf("locale" to "zh-CN", "participant_count" to 3),
        )

        assertEquals(
            "{\"type\":\"session.start\",\"id\":\"s1\",\"rev\":2," +
                "\"attributes\":{\"locale\":\"zh-CN\",\"participant_count\":3}}",
            ProtocolEncoder.sessionStart(snapshot),
        )
        assertEquals(
            "{\"type\":\"session.update\",\"id\":\"s1\",\"rev\":2," +
                "\"attributes\":{\"locale\":\"zh-CN\",\"participant_count\":3}}",
            ProtocolEncoder.sessionUpdate(snapshot),
        )
        assertEquals(
            "{\"type\":\"session.end\",\"id\":\"s1\",\"rev\":3,\"reason\":\"completed\"}",
            ProtocolEncoder.sessionEnd("s1", 3, "completed"),
        )
    }

    @Test
    fun contentRequestsHaveExactBoundAndUnboundShapes() {
        assertEquals(
            "{\"type\":\"content.play\",\"id\":\"c1\",\"key\":\"welcome\"," +
                "\"attributes\":{\"variant\":\"short\"},\"session_id\":\"s1\"," +
                "\"session_rev\":2}",
            ProtocolEncoder.contentPlay(
                ContentRequest(
                    "c1",
                    "welcome",
                    SessionRef("s1", 2),
                    mapOf("variant" to "short"),
                ),
            ),
        )
        assertEquals(
            "{\"type\":\"content.play\",\"id\":\"c2\",\"key\":\"idle_message\"," +
                "\"attributes\":{}}",
            ProtocolEncoder.contentPlay(
                ContentRequest("c2", "idle_message", null, emptyMap()),
            ),
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
