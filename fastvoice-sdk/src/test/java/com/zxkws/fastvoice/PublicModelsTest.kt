package com.zxkws.fastvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicModelsTest {
    @Test
    fun endpointIsRequired() {
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig(endpoint = "  ", areaId = "18")
        }
    }

    @Test
    fun endpointIsTrimmed() {
        val config = FastVoiceConfig(
            endpoint = "  ws://10.0.0.8:8100/ws  ",
            areaId = "18",
        )
        assertEquals("ws://10.0.0.8:8100/ws", config.endpoint)
    }

    @Test
    fun configNeedsOnlyEndpointAndRedactsItsQuery() {
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws?private=value",
            areaId = "18",
        )

        assertEquals(
            "FastVoiceConfig(endpoint=[configured], areaId=[configured], preferredWakeWords=[], " +
                "routeAudioToSpeaker=true, logger=null, getLocation=null)",
            config.toString(),
        )
    }

    @Test
    fun areaIdIsRequiredAndTrimmed() {
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig.builder("ws://127.0.0.1:8100/ws").build()
        }
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig(areaId = "   ", endpoint = "ws://127.0.0.1:8100/ws")
        }
        assertEquals(
            "18",
            FastVoiceConfig(
                areaId = " 18 ",
                endpoint = "ws://127.0.0.1:8100/ws",
            ).areaId,
        )
    }

    @Test
    fun endpointMustUseAWebSocketScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig(
                endpoint = "http://127.0.0.1:8100/ws",
                areaId = "18",
            )
        }

        val config = FastVoiceConfig(
            endpoint = "ws://127.0.0.1:8100/ws",
            areaId = "18",
        )
        assertEquals("ws://127.0.0.1:8100/ws", config.endpoint)
    }

    @Test
    fun unifiedEventsExposeServerValuesWithoutFormatting() {
        assertEquals(
            "原始文本\n",
            FastVoiceEvent.Transcript("assistant", "原始文本\n", false).text,
        )
        assertEquals(
            FastVoiceEvent.LocationAck("station_1907"),
            FastVoiceEvent.LocationAck("station_1907"),
        )
        assertEquals("station_1907", FastVoiceEvent.DestinationAck("station_1907").stationName)
        assertEquals(null, FastVoiceEvent.WelcomeAck(null).stationName)
        assertEquals(
            "content-1",
            FastVoiceEvent.PlaybackFinished(7, "content-1").contentId,
        )
        assertEquals(null, FastVoiceEvent.PlaybackFinished(8, null).contentId)
        assertEquals(
            "invalid_location",
            FastVoiceEvent.Error(
                FastVoiceError(
                    scope = "location",
                    code = "invalid_location",
                    recoverable = false,
                ),
            ).error.code,
        )
    }

    @Test
    fun validProtocolStatesRemainVerbatim() {
        assertEquals("idle", FastVoiceState.IDLE.value)
        assertEquals("prompting", FastVoiceState.PROMPTING.value)
        assertEquals("listening", FastVoiceState("listening").value)
    }
}
