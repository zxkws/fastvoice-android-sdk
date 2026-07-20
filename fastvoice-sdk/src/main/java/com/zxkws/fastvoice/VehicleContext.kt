package com.zxkws.fastvoice

/**
 * Trusted vehicle context accepted by the service.
 *
 * Every property maps one-to-one to a server allow-list field. Null properties are omitted from
 * an update. Free-form maps are intentionally not accepted, so a passenger cannot inject an
 * extra prompt or instruction field through the Android integration.
 */
data class VehicleContext(
    val parkId: String? = null,
    val routeId: String? = null,
    val currentSpotId: String? = null,
    val currentStationId: String? = null,
    val nextStationId: String? = null,
    val destinationId: String? = null,
    val vehicleName: String? = null,
    val currentLocation: String? = null,
    val routeName: String? = null,
    val destination: String? = null,
    val nextStop: String? = null,
    val operationStatus: String? = null,
    val speedMps: Double? = null,
    val batteryPercent: Double? = null,
    val passengerCount: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    init {
        validateOptionalIdentifier("parkId", parkId)
        validateOptionalIdentifier("routeId", routeId)
        validateOptionalIdentifier("currentSpotId", currentSpotId)
        validateOptionalIdentifier("currentStationId", currentStationId)
        validateOptionalIdentifier("nextStationId", nextStationId)
        validateOptionalIdentifier("destinationId", destinationId)
        validateOptionalText("vehicleName", vehicleName)
        validateOptionalText("currentLocation", currentLocation)
        validateOptionalText("routeName", routeName)
        validateOptionalText("destination", destination)
        validateOptionalText("nextStop", nextStop)
        validateOptionalText("operationStatus", operationStatus)
        require(operationStatus == null || operationStatus in OPERATION_STATUSES) {
            "operationStatus must be one of $OPERATION_STATUSES"
        }
        validateOptionalRange("speedMps", speedMps, 0.0, 100.0)
        validateOptionalRange("batteryPercent", batteryPercent, 0.0, 100.0)
        require(passengerCount == null || passengerCount in 0..100) {
            "passengerCount must be between 0 and 100"
        }
        validateOptionalRange("latitude", latitude, -90.0, 90.0)
        validateOptionalRange("longitude", longitude, -180.0, 180.0)
    }

    class Builder {
        private var parkId: String? = null
        private var routeId: String? = null
        private var currentSpotId: String? = null
        private var currentStationId: String? = null
        private var nextStationId: String? = null
        private var destinationId: String? = null
        private var vehicleName: String? = null
        private var currentLocation: String? = null
        private var routeName: String? = null
        private var destination: String? = null
        private var nextStop: String? = null
        private var operationStatus: String? = null
        private var speedMps: Double? = null
        private var batteryPercent: Double? = null
        private var passengerCount: Int? = null
        private var latitude: Double? = null
        private var longitude: Double? = null

        fun parkId(value: String?) = apply { parkId = value }
        fun routeId(value: String?) = apply { routeId = value }
        fun currentSpotId(value: String?) = apply { currentSpotId = value }
        fun currentStationId(value: String?) = apply { currentStationId = value }
        fun nextStationId(value: String?) = apply { nextStationId = value }
        fun destinationId(value: String?) = apply { destinationId = value }
        fun vehicleName(value: String?) = apply { vehicleName = value }
        fun currentLocation(value: String?) = apply { currentLocation = value }
        fun routeName(value: String?) = apply { routeName = value }
        fun destination(value: String?) = apply { destination = value }
        fun nextStop(value: String?) = apply { nextStop = value }
        fun operationStatus(value: String?) = apply { operationStatus = value }
        fun speedMps(value: Double?) = apply { speedMps = value }
        fun batteryPercent(value: Double?) = apply { batteryPercent = value }
        fun passengerCount(value: Int?) = apply { passengerCount = value }
        fun latitude(value: Double?) = apply { latitude = value }
        fun longitude(value: Double?) = apply { longitude = value }

        fun build(): VehicleContext = VehicleContext(
            parkId = parkId,
            routeId = routeId,
            currentSpotId = currentSpotId,
            currentStationId = currentStationId,
            nextStationId = nextStationId,
            destinationId = destinationId,
            vehicleName = vehicleName,
            currentLocation = currentLocation,
            routeName = routeName,
            destination = destination,
            nextStop = nextStop,
            operationStatus = operationStatus,
            speedMps = speedMps,
            batteryPercent = batteryPercent,
            passengerCount = passengerCount,
            latitude = latitude,
            longitude = longitude,
        )
    }

    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_NAVIGATING = "navigating"
        const val STATUS_ARRIVED = "arrived"
        const val STATUS_BOARDING = "boarding"
        const val STATUS_PAUSED = "paused"
        const val STATUS_OFFLINE = "offline"

        private val OPERATION_STATUSES = setOf(
            STATUS_IDLE,
            STATUS_NAVIGATING,
            STATUS_ARRIVED,
            STATUS_BOARDING,
            STATUS_PAUSED,
            STATUS_OFFLINE,
        )

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/** Identifies an arrival that should trigger the service's trusted arrival flow. */
data class SpotArrival(
    val parkId: String,
    val routeId: String,
    val stationId: String,
    val spotId: String,
) {
    init {
        requireIdentifier("parkId", parkId)
        requireIdentifier("routeId", routeId)
        requireIdentifier("stationId", stationId)
        requireIdentifier("spotId", spotId)
    }
}

private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
private val FORBIDDEN_CONTROL_CHARACTER = Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]")

private fun validateOptionalIdentifier(name: String, value: String?) {
    value?.let { requireIdentifier(name, it) }
}

private fun requireIdentifier(name: String, value: String) {
    require(IDENTIFIER_PATTERN.matches(value)) {
        "$name must match ${IDENTIFIER_PATTERN.pattern}"
    }
}

private fun validateOptionalText(name: String, value: String?) {
    if (value == null) return
    require(value.length <= 256 && !FORBIDDEN_CONTROL_CHARACTER.containsMatchIn(value)) {
        "$name must contain at most 256 characters and no unsupported control characters"
    }
}

private fun validateOptionalRange(name: String, value: Double?, minimum: Double, maximum: Double) {
    if (value == null) return
    require(value.isFinite() && value in minimum..maximum) {
        "$name must be finite and between $minimum and $maximum"
    }
}
