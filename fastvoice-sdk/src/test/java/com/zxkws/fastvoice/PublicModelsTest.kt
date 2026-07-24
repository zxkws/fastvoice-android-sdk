package com.zxkws.fastvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicModelsTest {
    private fun credentials() = DeviceCredentials("rover-1", "never-print-this-token")

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
    fun deviceAuthenticationIsMandatory() {
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig(endpoint = "wss://voice.example/ws")
        }
    }

    @Test
    fun insecureWebSocketRequiresExplicitOptIn() {
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig(
                endpoint = "ws://127.0.0.1:8100/ws",
                credentials = credentials(),
            )
        }

        val config = FastVoiceConfig(
            endpoint = "ws://127.0.0.1:8100/ws",
            credentials = credentials(),
            allowInsecureConnection = true,
        )
        assertEquals("ws://127.0.0.1:8100/ws", config.endpoint)
    }

    @Test
    fun orderSnapshotCopiesItsCompleteContext() {
        val nested = linkedMapOf<String, Any?>("label" to "original")
        val list = mutableListOf<Any?>("first", nested)
        val mutable = linkedMapOf<String, Any?>(
            "park_id" to "p1",
            "extension" to list,
        )
        val snapshot = OrderSnapshot("o1", 7, mutable)
        mutable["park_id"] = "changed"
        list[0] = "changed"
        nested["label"] = "changed"

        assertEquals("p1", snapshot.context["park_id"])
        val frozenList = snapshot.context["extension"] as List<*>
        assertEquals("first", frozenList[0])
        assertEquals("original", (frozenList[1] as Map<*, *>)["label"])
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.context as MutableMap<String, Any?>)["x"] = "y"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (frozenList as MutableList<Any?>)[0] = "y"
        }
    }

    @Test
    fun orderSnapshotRejectsValuesTheWireCannotRepresent() {
        assertThrows(IllegalArgumentException::class.java) {
            OrderSnapshot("o1", 1, mapOf("invalid key" to "value"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrderSnapshot("o1", 1, mapOf("number" to Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrderSnapshot("o1", 1, mapOf("object" to Any()))
        }
    }

    @Test
    fun unifiedEventsExposeServerValuesWithoutFormatting() {
        assertEquals(
            "原始文本\n",
            FastVoiceEvent.Transcript("assistant", "原始文本\n", false).text,
        )
        assertEquals(8L, FastVoiceEvent.OrderAck("update", "o1", 8).rev)
        assertEquals("tour-1", FastVoiceEvent.TourAck("tour-1").id)
        assertEquals(
            "tour-1",
            FastVoiceEvent.PlaybackFinished(7, "tour-1").tourId,
        )
        assertEquals(
            "invalid_context",
            FastVoiceEvent.Error(
                FastVoiceError(
                    scope = "order",
                    ref = "o1",
                    rev = 2,
                    code = "invalid_context",
                    recoverable = false,
                ),
            ).error.code,
        )
    }

    @Test
    fun validAndUnknownStatesRemainVerbatim() {
        assertEquals("idle", FastVoiceState.IDLE.value)
        assertEquals("prompting", FastVoiceState.PROMPTING.value)
        assertEquals("future_state:原样", FastVoiceState.fromRaw("future_state:原样").value)
    }
}
