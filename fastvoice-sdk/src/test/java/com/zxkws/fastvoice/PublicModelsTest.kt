package com.zxkws.fastvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicModelsTest {
    @Test
    fun endpointDefaultsToBuiltInWsServer() {
        assertEquals(DEFAULT_FASTVOICE_ENDPOINT, FastVoiceConfig().endpoint)
        assertEquals(DEFAULT_FASTVOICE_ENDPOINT, FastVoiceConfig(endpoint = "  ").endpoint)
        assertEquals(DEFAULT_FASTVOICE_ENDPOINT, FastVoiceConfig.resolveEndpoint(null))
        assertEquals(DEFAULT_FASTVOICE_ENDPOINT, FastVoiceConfig.builder().build().endpoint)
    }

    @Test
    fun customEndpointOverridesDefaultAndIsTrimmed() {
        val config = FastVoiceConfig(endpoint = "  ws://10.0.0.8:8100/ws  ")
        assertEquals("ws://10.0.0.8:8100/ws", config.endpoint)
    }

    @Test
    fun configNeedsOnlyEndpointAndRedactsItsQuery() {
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws?private=value",
        )

        assertEquals(
            "FastVoiceConfig(endpoint=[configured], preferredWakeWords=[], " +
                "routeAudioToSpeaker=true, logger=null, getLocation=null)",
            config.toString(),
        )
    }

    @Test
    fun endpointMustUseAWebSocketScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig(
                endpoint = "http://127.0.0.1:8100/ws",
            )
        }

        val config = FastVoiceConfig(
            endpoint = "ws://127.0.0.1:8100/ws",
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
            FastVoiceEvent.LocationAck("nanyuan", "station_1907"),
            FastVoiceEvent.LocationAck("nanyuan", "station_1907"),
        )
        assertEquals("nanyuan", FastVoiceEvent.WelcomeAck("nanyuan", null).park)
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
