package com.zxkws.fastvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicModelsTest {
    @Test
    fun credentialsAndConfigRedactSecretsFromDebugRepresentations() {
        val token = "never-print-this-token"
        val credentials = DeviceCredentials("rover-1", token)
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws?access_token=$token",
            credentials = credentials,
        )

        assertFalse(credentials.toString().contains(token))
        assertFalse(config.toString().contains(token))
        assertFalse(config.toString().contains("access_token"))
        assertTrue(credentials.toString().contains("[redacted]"))
    }

    @Test
    fun customProviderToStringIsNeverCalledByConfigToString() {
        var providerRendered = false
        val provider = object : DeviceTokenProvider {
            override fun tokenFor(deviceId: String): String = "secret"

            override fun toString(): String {
                providerRendered = true
                return "secret"
            }
        }
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws",
            deviceId = "rover-1",
            tokenProvider = provider,
        )

        assertFalse(config.toString().contains("secret"))
        assertFalse(providerRendered)
    }

    @Test
    fun insecureWebSocketRequiresExplicitOptIn() {
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig("ws://127.0.0.1:8100/ws")
        }

        val config = FastVoiceConfig(
            endpoint = "ws://127.0.0.1:8100/ws",
            allowInsecureConnection = true,
        )
        assertEquals("ws://127.0.0.1:8100/ws", config.endpoint)
    }

    @Test
    fun localFallbackPromptIsEnabledByDefaultAndMayBeDisabled() {
        assertTrue(
            FastVoiceConfig("wss://voice.example/ws").localFallbackPromptEnabled,
        )
        assertFalse(
            FastVoiceConfig.builder("wss://voice.example/ws")
                .localFallbackPromptEnabled(false)
                .build()
                .localFallbackPromptEnabled,
        )
    }

    @Test
    fun unknownServerStateIsPreservedVerbatim() {
        val value = "future_state:原样"
        assertEquals(value, FastVoiceState.fromRaw(value).value)
    }

    @Test
    fun contextAndArrivalEventsExposeStableServerFields() {
        assertEquals(7L, FastVoiceEvent.ContextUpdated(7L).version)
        assertEquals(
            "e1",
            FastVoiceEvent.ArrivalAccepted("e1", 8L, "spot-1").eventId,
        )
        assertEquals(
            "expired",
            FastVoiceEvent.ArrivalRejected("expired", "too old").code,
        )
    }
}
