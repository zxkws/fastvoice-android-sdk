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
    fun insecureWebSocketRequiresExplicitOptIn() {
        assertThrows(IllegalArgumentException::class.java) {
            FastVoiceConfig(
                endpoint = "ws://127.0.0.1:8100/ws",
                tokenProvider = tokenProvider(),
            )
        }

        val config = FastVoiceConfig(
            endpoint = "ws://127.0.0.1:8100/ws",
            tokenProvider = tokenProvider(),
            allowInsecureConnection = true,
        )
        assertEquals("ws://127.0.0.1:8100/ws", config.endpoint)
    }

    @Test
    fun sessionSnapshotCopiesItsCompleteAttributes() {
        val nested = linkedMapOf<String, Any?>("label" to "original")
        val list = mutableListOf<Any?>("first", nested)
        val mutable = linkedMapOf<String, Any?>(
            "locale" to "zh-CN",
            "extension" to list,
        )
        val snapshot = SessionSnapshot("s1", 7, mutable)
        mutable["locale"] = "changed"
        list[0] = "changed"
        nested["label"] = "changed"

        assertEquals("zh-CN", snapshot.attributes["locale"])
        val frozenList = snapshot.attributes["extension"] as List<*>
        assertEquals("first", frozenList[0])
        assertEquals("original", (frozenList[1] as Map<*, *>)["label"])
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.attributes as MutableMap<String, Any?>)["x"] = "y"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (frozenList as MutableList<Any?>)[0] = "y"
        }
    }

    @Test
    fun structuredModelsRejectValuesTheWireCannotRepresent() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionSnapshot("s1", 0, emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionRef("s1", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionSnapshot("s1", 1, mapOf("invalid key" to "value"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionSnapshot("s1", 1, mapOf("number" to Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentRequest("c1", "welcome", null, mapOf("object" to Any()))
        }
    }

    @Test
    fun attributesEnforceServerStructuralBounds() {
        val acceptedDepth = mapOf(
            "a" to mapOf(
                "b" to mapOf(
                    "c" to mapOf("d" to "value"),
                ),
            ),
        )
        SessionSnapshot("s1", 1, acceptedDepth)
        SessionSnapshot("s1", 1, mapOf("values" to List(128) { it }))
        SessionSnapshot("s1", 1, mapOf("line" to "first\nsecond"))
        SessionSnapshot("s1", 1, mapOf("emoji" to "😀".repeat(2_048)))

        assertThrows(IllegalArgumentException::class.java) {
            SessionSnapshot(
                "s1",
                1,
                mapOf("a" to mapOf("b" to mapOf("c" to mapOf("d" to mapOf("e" to 1))))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionSnapshot("s1", 1, mapOf("values" to List(129) { it }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionSnapshot("s1", 1, (1..129).associate { "key$it" to it })
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionSnapshot("s1", 1, mapOf("text" to "x".repeat(2_049)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionSnapshot("s1", 1, mapOf("text" to "bad\u0000value"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionSnapshot(
                "s1",
                1,
                mapOf("groups" to List(128) { listOf(1, 2, 3) }),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionSnapshot(
                "s1",
                1,
                (1..9).associate { "text$it" to "x".repeat(2_048) },
            )
        }
    }

    @Test
    fun contentRequestAndReferenceHaveImmutableValueSemantics() {
        val mutable = linkedMapOf<String, Any?>("variant" to "short")
        val reference = SessionRef.of("s1", 4)
        val request = ContentRequest("c1", "welcome", reference, mutable)
        mutable["variant"] = "changed"

        assertEquals(SessionRef("s1", 4), reference)
        assertEquals("short", request.attributes["variant"])
        assertEquals(
            request,
            ContentRequest("c1", "welcome", SessionRef("s1", 4), mapOf("variant" to "short")),
        )
    }

    @Test
    fun unifiedEventsExposeServerValuesWithoutFormatting() {
        assertEquals(
            "原始文本\n",
            FastVoiceEvent.Transcript("assistant", "原始文本\n", false).text,
        )
        assertEquals(8L, FastVoiceEvent.SessionAck("update", "s1", 8).rev)
        assertEquals("content-1", FastVoiceEvent.ContentAck("content-1").id)
        assertEquals(
            "content-1",
            FastVoiceEvent.PlaybackFinished(7, "content-1").contentId,
        )
        assertEquals(null, FastVoiceEvent.PlaybackFinished(8, null).contentId)
        assertEquals(
            "invalid_session",
            FastVoiceEvent.Error(
                FastVoiceError(
                    scope = "session",
                    ref = "s1",
                    rev = 2,
                    code = "invalid_session",
                    recoverable = false,
                ),
            ).error.code,
        )
    }

    @Test
    fun validProtocolStatesRemainVerbatim() {
        assertEquals("idle", FastVoiceState.IDLE.value)
        assertEquals("prompting", FastVoiceState.PROMPTING.value)
        assertEquals("listening", FastVoiceState.fromRaw("listening").value)
    }
}
