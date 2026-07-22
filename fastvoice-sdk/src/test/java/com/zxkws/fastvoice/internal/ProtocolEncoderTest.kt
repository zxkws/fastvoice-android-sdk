package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.DeviceTokenProvider
import com.zxkws.fastvoice.FastVoiceConfig
import com.zxkws.fastvoice.FastVoiceLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolEncoderTest {
    @Test
    fun helloEncodesAudioWakeDeviceAndSdkOwnedControlProtocol() {
        val logs = mutableListOf<String>()
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws",
            deviceId = "rover-1",
            tokenProvider = DeviceTokenProvider.fixed("secret-1"),
            preferredWakeWords = listOf("你好布丁", "布丁"),
            logger = FastVoiceLogger { level, message, error ->
                logs += "$level:$message:$error"
            },
        )

        val encoded = ProtocolEncoder.hello(
            config,
            listOf("布丁", "布丁布丁", "你好布丁", "布丁你好"),
        )

        assertEquals(
                "{\"type\":\"hello\",\"audio\":{\"encoding\":\"opus\",\"input_rate\":16000," +
                "\"output_rate\":48000,\"frame_ms\":20},\"wake\":{\"enabled\":true," +
                "\"words\":[\"你好布丁\",\"布丁\"]},\"control\":{" +
                "\"protocol\":\"commands-v1\",\"actions\":[\"playback.begin\",\"playback.end\"," +
                "\"playback.stop\",\"playback.pause\",\"playback.resume\",\"playback.replay\"," +
                "\"playback.skip\",\"audio.volume.adjust\",\"uplink.start\",\"uplink.stop\"]}," +
                "\"device\":{\"id\":\"rover-1\",\"token\":\"secret-1\"}}",
            encoded,
        )
        assertTrue(encoded.contains("commands-v1"))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun helloRejectsWakeWordsThatThePackagedModelCannotRecognize() {
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws",
            preferredWakeWords = listOf("不在模型里"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProtocolEncoder.hello(config, listOf("布丁"))
        }
    }

    @Test
    fun helloMayDisableWakeWithoutLocalKeywords() {
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws",
            wakeEnabled = false,
        )

        assertEquals(
                "{\"type\":\"hello\",\"audio\":{\"encoding\":\"opus\",\"input_rate\":16000," +
                "\"output_rate\":48000,\"frame_ms\":20},\"wake\":{\"enabled\":false," +
                "\"words\":[]},\"control\":{\"protocol\":\"commands-v1\",\"actions\":[" +
                "\"playback.begin\",\"playback.end\",\"playback.stop\",\"playback.pause\"," +
                "\"playback.resume\",\"playback.replay\",\"playback.skip\"," +
                "\"audio.volume.adjust\",\"uplink.start\",\"uplink.stop\"]}}",
            ProtocolEncoder.hello(config, emptyList()),
        )
    }

    @Test
    fun simpleMessagesPreserveTextAndEscapeOnlyForJson() {
        assertEquals(
            "{\"type\":\"wake\",\"word\":\"布丁\"}",
            ProtocolEncoder.wake("布丁"),
        )
        assertEquals(
            "{\"type\":\"local_command_candidate\",\"id\":\"lc-7\"," +
                "\"text\":\"换\\\"一个\\n\",\"gen\":3}",
            ProtocolEncoder.localCommandCandidate("lc-7", "换\"一个\n", 3),
        )
        assertEquals("{\"type\":\"interrupt\"}", ProtocolEncoder.interrupt())
        assertEquals(
            "{\"type\":\"command_ack\",\"id\":\"c7\"}",
            ProtocolEncoder.commandAck("c7"),
        )
        assertEquals(
            "{\"type\":\"playback_finished\",\"gen\":3}",
            ProtocolEncoder.playbackFinished(3),
        )
        assertEquals(
            "{\"type\":\"playback_progress\",\"gen\":3,\"played_ms\":1250}",
            ProtocolEncoder.playbackProgress(3, 1_250),
        )
        assertEquals(
            "{\"type\":\"playback_failed\",\"gen\":3,\"reason\":\"write=0\"}",
            ProtocolEncoder.playbackFailed(3, "write=0"),
        )
        assertEquals(
            "{\"type\":\"playback_failed\",\"gen\":3,\"reason\":\"write=-6\"," +
                "\"command_id\":\"c7\"}",
            ProtocolEncoder.playbackFailed(3, "write=-6", "c7"),
        )
    }
}
