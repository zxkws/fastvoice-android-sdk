package com.zxkws.fastvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicModelsTest {
    private fun tokenProvider() = DeviceTokenProvider.fixed("never-print-this-token")

    @Test
    fun tokenProviderAndConfigRedactSecretsFromDebugRepresentations() {
        val token = "never-print-this-token"
        val provider = DeviceTokenProvider.fixed(token)
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws?access_token=$token",
            tokenProvider = provider,
        )

        assertFalse(provider.toString().contains(token))
        assertFalse(config.toString().contains(token))
        assertFalse(config.toString().contains("access_token"))
        assertTrue(provider.toString().contains("[redacted]"))
    }

    @Test
    fun tokenAuthenticationIsMandatory() {
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig.builder("wss://voice.example/ws").build()
        }
    }

    @Test
    fun rotatingTokenProviderHasNoDeviceIdArgument() {
        var calls = 0
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws",
            tokenProvider = DeviceTokenProvider {
                calls += 1
                "rotated-token"
            },
        )

        assertEquals("rotated-token", config.requireDeviceToken())
        assertEquals(1, calls)
    }

    @Test
    fun tokensRejectWhitespaceAndExcessiveLength() {
        for (token in listOf("has space", "line\nbreak", "x".repeat(4_097))) {
            assertThrows(IllegalArgumentException::class.java) {
                DeviceTokenProvider.fixed(token)
            }
            val config = FastVoiceConfig(
                endpoint = "wss://voice.example/ws",
                tokenProvider = DeviceTokenProvider { token },
            )
            assertThrows(IllegalArgumentException::class.java) {
                config.requireDeviceToken()
            }
        }
    }

    @Test
    fun endpointMustUseAWebSocketScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig(
                endpoint = "http://127.0.0.1:8100/ws",
                tokenProvider = tokenProvider(),
            )
        }

        val config = FastVoiceConfig(
            endpoint = "ws://127.0.0.1:8100/ws",
            tokenProvider = tokenProvider(),
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
