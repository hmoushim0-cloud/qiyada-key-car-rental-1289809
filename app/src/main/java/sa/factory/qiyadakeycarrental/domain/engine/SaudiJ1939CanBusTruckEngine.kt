package sa.factory.qiyadakeycarrental.domain.engine

import kotlin.math.*

/**
 * Heavy-Duty Commercial Truck SAE J1939 CAN-Bus & Logistics Geofence Engine.
 * Supports PGN parameter parsing (EEC1 RPM, CCVS Truck Speed, ET1 Temperature, DD Fuel Level),
 * Saudi Ministry of Transport highway speed limiting (100 km/h),
 * Northern Province logistics hub geofencing, and cargo axle overload auditing.
 *
 * Strictly zero emojis.
 */
class SaudiJ1939CanBusTruckEngine {

    companion object {
        const val PGN_EEC1: Long = 61444L   // Electronic Engine Controller 1 (RPM, Torque)
        const val PGN_CCVS: Long = 65265L   // Cruise Control / Vehicle Speed
        const val PGN_ET1: Long = 65262L    // Engine Temperature 1 (Coolant)
        const val PGN_DD: Long = 65276L     // Dash Display (Fuel Level, Cargo)
        const val PGN_LFE: Long = 65257L    // Fuel Economy

        const val SAUDI_COMMERCIAL_TRUCK_MAX_SPEED_KMH = 100.0
        const val MAX_LEGAL_AXLE_WEIGHT_TONS = 45.0
        const val EARTH_RADIUS_KM = 6371.0
    }

    data class J1939TelemetryReading(
        val pgn: Long,
        val parameterName: String,
        val primaryValue: Double,
        val secondaryValue: Double? = null,
        val unit: String,
        val isWarning: Boolean,
        val alertMessage: String? = null
    )

    data class GeoCoordinates(
        val latitude: Double,
        val longitude: Double
    )

    data class LogisticsHub(
        val hubId: String,
        val hubNameAr: String,
        val center: GeoCoordinates,
        val radiusKm: Double
    )

    data class RouteAuditReport(
        val currentLocation: GeoCoordinates,
        val currentSpeedKmh: Double,
        val isSpeedingViolation: Boolean,
        val activeLogisticsHub: String?,
        val nearestHubDistanceKm: Double,
        val totalGrossWeightTons: Double,
        val isOverloaded: Boolean
    )

    /**
     * Parses SAE J1939 CAN-Bus 8-byte PGN payload.
     */
    fun parseJ1939Pgn(pgn: Long, data: ByteArray): J1939TelemetryReading {
        return when (pgn) {
            PGN_EEC1 -> {
                // Bytes 4-5: Engine Speed (Resolution: 0.125 RPM / bit, Offset: 0)
                val rawRpm = if (data.size >= 5) {
                    ((data[4].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                } else 0
                val rpm = rawRpm * 0.125

                // Byte 3: Driver Demand Torque (Resolution: 1% / bit, Offset: -125%)
                val rawTorque = if (data.size >= 3) data[2].toInt() and 0xFF else 0
                val torquePct = (rawTorque - 125).toDouble()

                val isWarning = rpm > 2600.0
                J1939TelemetryReading(
                    pgn = pgn,
                    parameterName = "EEC1 Engine RPM & Torque",
                    primaryValue = round(rpm * 10.0) / 10.0,
                    secondaryValue = torquePct,
                    unit = "RPM / %",
                    isWarning = isWarning,
                    alertMessage = if (isWarning) "[ALERT] Heavy engine over-revving detected (${rpm.toInt()} RPM)" else null
                )
            }
            PGN_CCVS -> {
                // Bytes 2-3: Wheel-based Vehicle Speed (Resolution: 1/256 km/h per bit, Offset: 0)
                val rawSpeed = if (data.size >= 3) {
                    ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                } else 0
                val speedKmh = rawSpeed / 256.0
                val isWarning = speedKmh > SAUDI_COMMERCIAL_TRUCK_MAX_SPEED_KMH

                J1939TelemetryReading(
                    pgn = pgn,
                    parameterName = "CCVS Wheel-based Speed",
                    primaryValue = round(speedKmh * 10.0) / 10.0,
                    unit = "km/h",
                    isWarning = isWarning,
                    alertMessage = if (isWarning) "[ALERT] Commercial truck speed limit exceeded (${speedKmh.toInt()} km/h > 100 km/h)" else null
                )
            }
            PGN_ET1 -> {
                // Byte 1: Engine Coolant Temperature (Resolution: 1 C / bit, Offset: -40 C)
                val rawTemp = if (data.isNotEmpty()) data[0].toInt() and 0xFF else 0
                val tempC = (rawTemp - 40).toDouble()
                val isWarning = tempC > 102.0

                J1939TelemetryReading(
                    pgn = pgn,
                    parameterName = "ET1 Coolant Temperature",
                    primaryValue = tempC,
                    unit = "C",
                    isWarning = isWarning,
                    alertMessage = if (isWarning) "[ALERT] High engine coolant temperature (${tempC.toInt()} C)" else null
                )
            }
            PGN_DD -> {
                // Byte 2: Fuel Level (Resolution: 0.4 % / bit, Offset: 0)
                val rawFuel = if (data.size >= 2) data[1].toInt() and 0xFF else 0
                val fuelPct = rawFuel * 0.4
                val isWarning = fuelPct < 12.0

                J1939TelemetryReading(
                    pgn = pgn,
                    parameterName = "DD Fuel Tank Level",
                    primaryValue = round(fuelPct * 10.0) / 10.0,
                    unit = "%",
                    isWarning = isWarning,
                    alertMessage = if (isWarning) "[ALERT] Low diesel fuel reserve (${fuelPct.toInt()}%)" else null
                )
            }
            else -> {
                J1939TelemetryReading(
                    pgn = pgn,
                    parameterName = "Unknown J1939 PGN",
                    primaryValue = 0.0,
                    unit = "",
                    isWarning = false
                )
            }
        }
    }

    /**
     * Computes geodesic distance in kilometers between two points via Haversine formula.
     */
    fun calculateDistanceKm(p1: GeoCoordinates, p2: GeoCoordinates): Double {
        val lat1Rad = Math.toRadians(p1.latitude)
        val lat2Rad = Math.toRadians(p2.latitude)
        val deltaLat = Math.toRadians(p2.latitude - p1.latitude)
        val deltaLon = Math.toRadians(p2.longitude - p1.longitude)

        val a = sin(deltaLat / 2.0).pow(2.0) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_KM * c
    }

    /**
     * Evaluates route telemetry, logistics hub presence, and gross vehicle axle weight.
     */
    fun auditFleetRoute(
        currentLocation: GeoCoordinates,
        currentSpeedKmh: Double,
        grossWeightTons: Double,
        hubs: List<LogisticsHub>
    ): RouteAuditReport {
        var activeHub: String? = null
        var minDistance = Double.MAX_VALUE

        for (hub in hubs) {
            val dist = calculateDistanceKm(currentLocation, hub.center)
            if (dist <= hub.radiusKm && activeHub == null) {
                activeHub = hub.hubId
            }
            if (dist < minDistance) {
                minDistance = dist
            }
        }

        val isSpeeding = currentSpeedKmh > SAUDI_COMMERCIAL_TRUCK_MAX_SPEED_KMH
        val isOverloaded = grossWeightTons > MAX_LEGAL_AXLE_WEIGHT_TONS

        return RouteAuditReport(
            currentLocation = currentLocation,
            currentSpeedKmh = currentSpeedKmh,
            isSpeedingViolation = isSpeeding,
            activeLogisticsHub = activeHub,
            nearestHubDistanceKm = round(minDistance * 100.0) / 100.0,
            totalGrossWeightTons = grossWeightTons,
            isOverloaded = isOverloaded
        )
    }
}
