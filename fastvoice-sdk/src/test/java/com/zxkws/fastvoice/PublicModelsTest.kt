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
    fun unknownServerStateIsPreservedVerbatim() {
        val value = "future_state:原样"
        assertEquals(value, FastVoiceState.fromRaw(value).value)
    }

    @Test
    fun contextValidationMatchesServerAllowListRules() {
        assertEquals(
            VehicleContext.STATUS_ARRIVED,
            VehicleContext(operationStatus = VehicleContext.STATUS_ARRIVED).operationStatus,
        )
        assertThrows(IllegalArgumentException::class.java) {
            VehicleContext(currentStationId = "中文不是服务端允许的 ID")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleContext(operationStatus = "execute-user-instructions")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleContext(vehicleName = "bad\u0000name")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleContext(speedMps = 100.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleContext(batteryPercent = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleContext(passengerCount = 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleContext(latitude = -90.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleContext(longitude = 180.01)
        }
    }

    @Test
    fun arrivalIdentifiersMatchServerValidation() {
        val valid = SpotArrival("park-1", "route:a", "station.3", "spot_3")
        assertEquals("spot_3", valid.spotId)

        assertThrows(IllegalArgumentException::class.java) {
            SpotArrival("南苑", "route-a", "station-3", "spot-3")
        }
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
