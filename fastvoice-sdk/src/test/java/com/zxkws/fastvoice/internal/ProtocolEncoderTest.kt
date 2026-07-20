package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.DeviceCredentials
import com.zxkws.fastvoice.DeviceTokenProvider
import com.zxkws.fastvoice.FastVoiceConfig
import com.zxkws.fastvoice.FastVoiceLogLevel
import com.zxkws.fastvoice.FastVoiceLogger
import com.zxkws.fastvoice.SpotArrival
import com.zxkws.fastvoice.VehicleContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolEncoderTest {
    @Test
    fun helloEncodesAudioWakeAndDeviceWithoutAdvertisingExperimentalControl() {
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
                "\"word\":\"你好布丁\",\"words\":[\"你好布丁\",\"布丁\"]}," +
                "\"device\":{\"id\":\"rover-1\",\"token\":\"secret-1\"}}",
            encoded,
        )
        assertFalse(encoded.contains("commands-v1"))
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
                "\"words\":[]}}",
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
            "{\"type\":\"local_command_candidate\",\"text\":\"换\\\"一个\\n\"}",
            ProtocolEncoder.localCommandCandidate("换\"一个\n"),
        )
        assertEquals(
            "{\"type\":\"wake_prompt_done\"}",
            ProtocolEncoder.promptDone("wake_prompt_done"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolEncoder.promptDone("made_up_prompt_done")
        }
    }

    @Test
    fun contextUpdateUsesOnlyTheServerAllowListAndOmitsNulls() {
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws",
            credentials = DeviceCredentials("rover-1", "secret-1"),
            contextTtlSeconds = 30.0,
        )
        val context = VehicleContext(
            parkId = "park-1",
            routeId = "route-a",
            currentSpotId = "spot-3",
            currentStationId = "station-3",
            nextStationId = "station-4",
            destinationId = "spot-8",
            vehicleName = "布丁车 1 号",
            currentLocation = "燕景台",
            routeName = "环湖线",
            destination = "1907 火车站",
            nextStop = "上林湖",
            operationStatus = VehicleContext.STATUS_ARRIVED,
            speedMps = 0.0,
            batteryPercent = 81.5,
            passengerCount = 3,
            latitude = 31.25,
            longitude = 121.5,
        )

        assertEquals(
            "{\"type\":\"context_update\",\"device_id\":\"rover-1\",\"version\":7," +
                "\"timestamp\":1000.25,\"ttl_seconds\":30.0,\"context\":{" +
                "\"park_id\":\"park-1\",\"route_id\":\"route-a\",\"current_spot_id\":\"spot-3\"," +
                "\"current_station_id\":\"station-3\",\"next_station_id\":\"station-4\"," +
                "\"destination_id\":\"spot-8\",\"vehicle_name\":\"布丁车 1 号\"," +
                "\"current_location\":\"燕景台\",\"route_name\":\"环湖线\"," +
                "\"destination\":\"1907 火车站\",\"next_stop\":\"上林湖\"," +
                "\"operation_status\":\"arrived\",\"speed_mps\":0.0," +
                "\"battery_percent\":81.5,\"passenger_count\":3,\"latitude\":31.25," +
                "\"longitude\":121.5}}",
            ProtocolEncoder.contextUpdate(config, context, 7L, 1000.25),
        )
    }

    @Test
    fun spotArrivalAddsTrustedEnvelopeFields() {
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws",
            credentials = DeviceCredentials("rover-1", "secret-1"),
            arrivalTtlSeconds = 45.0,
        )

        assertEquals(
            "{\"type\":\"spot_arrival\",\"device_id\":\"rover-1\"," +
                "\"event_id\":\"arrival-001\",\"version\":9,\"timestamp\":1001.5," +
                "\"ttl_seconds\":45.0,\"park_id\":\"park-1\",\"route_id\":\"route-a\"," +
                "\"station_id\":\"station-3\",\"spot_id\":\"spot-3\"}",
            ProtocolEncoder.spotArrival(
                config = config,
                arrival = SpotArrival("park-1", "route-a", "station-3", "spot-3"),
                version = 9L,
                timestampSeconds = 1001.5,
                eventId = "arrival-001",
            ),
        )
    }

    @Test
    fun autoGeneratedArrivalEventIdUsesServerAcceptedCharacters() {
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws",
            credentials = DeviceCredentials("rover-1", "secret-1"),
        )
        val encoded = ProtocolEncoder.spotArrival(
            config,
            SpotArrival("park-1", "route-a", "station-3", "spot-3"),
            1L,
            1000.0,
        )

        assertTrue(encoded.contains(Regex("\\\"event_id\\\":\\\"[0-9a-f-]{36}\\\"")))
    }

    @Test
    fun nonFiniteNumbersNeverProduceInvalidJson() {
        val config = FastVoiceConfig(
            endpoint = "wss://voice.example/ws",
            credentials = DeviceCredentials("rover-1", "secret-1"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProtocolEncoder.contextUpdate(config, VehicleContext(), 1L, Double.NaN)
        }
    }
}
