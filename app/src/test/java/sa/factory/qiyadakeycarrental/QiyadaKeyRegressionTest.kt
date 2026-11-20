package sa.factory.qiyadakeycarrental

import sa.factory.qiyadakeycarrental.data.repository.QiyadaKeyRepositoryImpl
import sa.factory.qiyadakeycarrental.domain.engine.SaudiCommercialFleetBleKeyEngine
import sa.factory.qiyadakeycarrental.domain.engine.SaudiJ1939CanBusTruckEngine
import sa.factory.qiyadakeycarrental.hardware.HardwareSensorDriver
import sa.factory.qiyadakeycarrental.model.QiyadaKeyItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QiyadaKeyRegressionTest {

    // 1. Checkout & Tax Calculation
    @Test
    fun testCheckoutCalculationAndTax() = runBlocking {
        val sensorDriver = HardwareSensorDriver()
        val repository = QiyadaKeyRepositoryImpl(sensorDriver)

        val item1 = QiyadaKeyItem("t1", "تأجير شاحنة تجارية ثقيلة", "الوصف", 500.0, "معتمد", "عام")
        val item2 = QiyadaKeyItem("t2", "خدمة التتبع اللحظي للأسطول", "الوصف", 200.0, "معتمد", "عام")

        repository.addToCart(item1)
        repository.addToCart(item2)

        val order = repository.checkout("Mada EMV")

        assertEquals(700.0, order.subtotalSar, 0.01)
        assertEquals(105.0, order.vatSar, 0.01)
        assertEquals(805.0, order.totalSar, 0.01)
        assertEquals(0, repository.activeCart.value.size)
    }

    // 2. Saudi National ID & Iqama Validation (10 digits, starts with 1 or 2)
    @Test
    fun testDriverNationalIdValidation() {
        val engine = SaudiCommercialFleetBleKeyEngine()

        // Valid Citizen ID
        assertTrue(engine.validateDriverId("1010101010"))
        // Valid Resident Iqama
        assertTrue(engine.validateDriverId("2000000008"))

        // Invalid: Starts with 5
        assertFalse(engine.validateDriverId("5010101010"))
        // Invalid length
        assertFalse(engine.validateDriverId("101010101"))
        // Invalid Luhn checksum
        assertFalse(engine.validateDriverId("1010101011"))
    }

    // 3. Commercial Truck BLE Command Frame Verification
    @Test
    fun testCommercialTruckCommandFrameVerification() {
        val engine = SaudiCommercialFleetBleKeyEngine()
        val now = 1700000000000L

        val manifest = SaudiCommercialFleetBleKeyEngine.DriverTransportManifest(
            manifestId = "MANIFEST-TGA-771",
            truckVin = "939TRUCKSAUDI001",
            driverNationalId = "1010101010",
            validFromEpochMs = now - 3600000L,
            validToEpochMs = now + 86400000L,
            secretKeyHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            isHazmatCertified = true
        )

        val frame = engine.buildCommandFrame(
            manifest = manifest,
            commandCode = SaudiCommercialFleetBleKeyEngine.CMD_CARGO_BAY_UNLOCK,
            sequenceNumber = 101,
            nonce = 998877L,
            rssiDbm = -55,
            timestampMs = now
        )

        val result = engine.verifyCommandFrame(frame, manifest, currentEpochMs = now + 400L)
        assertTrue(result.isSuccess)
        assertEquals(SaudiCommercialFleetBleKeyEngine.STATUS_SUCCESS, result.statusCode)
        assertEquals("51590010", result.responseAcknowledgeHex)
    }

    // 4. Expired Transport Manifest Rejection
    @Test
    fun testExpiredTransportManifestRejection() {
        val engine = SaudiCommercialFleetBleKeyEngine()
        val now = 1700000000000L

        val expiredManifest = SaudiCommercialFleetBleKeyEngine.DriverTransportManifest(
            manifestId = "MANIFEST-EXPIRED",
            truckVin = "939TRUCKSAUDI001",
            driverNationalId = "1010101010",
            validFromEpochMs = now - 7200000L,
            validToEpochMs = now - 1000L, // expired
            secretKeyHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )

        val frame = engine.buildCommandFrame(
            manifest = expiredManifest,
            commandCode = SaudiCommercialFleetBleKeyEngine.CMD_ENGINE_IMMOBILIZER_DISARM,
            sequenceNumber = 102,
            nonce = 554433L,
            rssiDbm = -50,
            timestampMs = now
        )

        val result = engine.verifyCommandFrame(frame, expiredManifest, currentEpochMs = now)
        assertFalse(result.isSuccess)
        assertEquals(SaudiCommercialFleetBleKeyEngine.STATUS_EXPIRED_MANIFEST, result.statusCode)
    }

    // 5. Anti-Relay Proximity Barrier (RSSI < -70 dBm)
    @Test
    fun testRelayAttackProximityRssiBarrier() {
        val engine = SaudiCommercialFleetBleKeyEngine()
        val now = 1700000000000L

        val manifest = SaudiCommercialFleetBleKeyEngine.DriverTransportManifest(
            manifestId = "MANIFEST-PROX-01",
            truckVin = "939TRUCKSAUDI001",
            driverNationalId = "1010101010",
            validFromEpochMs = now - 3600000L,
            validToEpochMs = now + 86400000L,
            secretKeyHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )

        // Weak signal -80 dBm (outside safe cab/cargo proximity)
        val weakFrame = engine.buildCommandFrame(
            manifest = manifest,
            commandCode = SaudiCommercialFleetBleKeyEngine.CMD_CABIN_UNLOCK,
            sequenceNumber = 103,
            nonce = 112233L,
            rssiDbm = -80,
            timestampMs = now
        )

        val result = engine.verifyCommandFrame(weakFrame, manifest, currentEpochMs = now)
        assertFalse(result.isSuccess)
        assertEquals(SaudiCommercialFleetBleKeyEngine.STATUS_OUT_OF_RANGE, result.statusCode)
    }

    // 6. Replay Attack Timestamp Freshness Tolerance
    @Test
    fun testReplayAttackTimestampDrift() {
        val engine = SaudiCommercialFleetBleKeyEngine()
        val now = 1700000000000L

        val manifest = SaudiCommercialFleetBleKeyEngine.DriverTransportManifest(
            manifestId = "MANIFEST-REPLAY-01",
            truckVin = "939TRUCKSAUDI001",
            driverNationalId = "1010101010",
            validFromEpochMs = now - 3600000L,
            validToEpochMs = now + 86400000L,
            secretKeyHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )

        // Timestamp 5000ms old (> 3000ms tolerance)
        val oldFrame = engine.buildCommandFrame(
            manifest = manifest,
            commandCode = SaudiCommercialFleetBleKeyEngine.CMD_ENGINE_IMMOBILIZER_DISARM,
            sequenceNumber = 104,
            nonce = 778899L,
            rssiDbm = -50,
            timestampMs = now - 5000L
        )

        val result = engine.verifyCommandFrame(oldFrame, manifest, currentEpochMs = now)
        assertFalse(result.isSuccess)
        assertEquals(SaudiCommercialFleetBleKeyEngine.STATUS_INVALID_TIMESTAMP, result.statusCode)
    }

    // 7. Binary Frame Serialization (Magic "QY", Protocol Version 3)
    @Test
    fun testBinaryFrameSerializationMagicBytes() {
        val engine = SaudiCommercialFleetBleKeyEngine()
        val frame = SaudiCommercialFleetBleKeyEngine.FleetKeyCommandFrame(
            commandCode = SaudiCommercialFleetBleKeyEngine.CMD_CARGO_BAY_LOCK,
            sequenceNumber = 50,
            manifestId = "MAN-1",
            nonce = 445566L,
            rssiDbm = -60,
            timestampEpochMs = 1700000000000L,
            signatureHmac = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        )

        val binary = engine.serializeToBinary(frame)
        assertTrue(binary.size > 25)
        assertEquals(0x51.toByte(), binary[0]) // 'Q'
        assertEquals(0x59.toByte(), binary[1]) // 'Y'
        assertEquals(0x03.toByte(), binary[2]) // Protocol Version 3
        assertEquals(SaudiCommercialFleetBleKeyEngine.CMD_CARGO_BAY_LOCK, binary[3])
    }

    // 8. J1939 EEC1 Engine Speed & Torque Decoding
    @Test
    fun testJ1939EngineRpmAndTorqueParsing() {
        val engine = SaudiJ1939CanBusTruckEngine()

        // PGN 61444 EEC1
        // Data: [0, 0, torqueByte=175, rpmLow=0x00, rpmHigh=0x40, ...]
        // rawRpm = (0x40 << 8) | 0x00 = 16384 -> 16384 * 0.125 = 2048.0 RPM
        // rawTorque = 175 -> 175 - 125 = 50%
        val rawData = byteArrayOf(0, 0, 175.toByte(), 0x00.toByte(), 0x40.toByte(), 0, 0, 0)
        val reading = engine.parseJ1939Pgn(SaudiJ1939CanBusTruckEngine.PGN_EEC1, rawData)

        assertEquals(2048.0, reading.primaryValue, 0.1)
        assertEquals(50.0, reading.secondaryValue ?: 0.0, 0.1)
        assertFalse(reading.isWarning)
    }

    // 9. J1939 Commercial Speed Limiter (100 km/h)
    @Test
    fun testJ1939CommercialSpeedLimiter() {
        val engine = SaudiJ1939CanBusTruckEngine()

        // CCVS: speed = raw / 256.0
        // Speed 90 km/h -> raw = 90 * 256 = 23040 -> low=0x00, high=0x5A
        val normalData = byteArrayOf(0, 0x00.toByte(), 0x5A.toByte(), 0, 0, 0, 0, 0)
        val normalReading = engine.parseJ1939Pgn(SaudiJ1939CanBusTruckEngine.PGN_CCVS, normalData)
        assertEquals(90.0, normalReading.primaryValue, 0.1)
        assertFalse(normalReading.isWarning)

        // Speed 108 km/h (> 100 km/h limit) -> raw = 108 * 256 = 27648 -> low=0x00, high=0x6C
        val overData = byteArrayOf(0, 0x00.toByte(), 0x6C.toByte(), 0, 0, 0, 0, 0)
        val overReading = engine.parseJ1939Pgn(SaudiJ1939CanBusTruckEngine.PGN_CCVS, overData)
        assertEquals(108.0, overReading.primaryValue, 0.1)
        assertTrue(overReading.isWarning)
    }

    // 10. Northern Logistics Hub Geofencing & Cargo Overload
    @Test
    fun testNorthernLogisticsHubGeofencingAndOverload() {
        val engine = SaudiJ1939CanBusTruckEngine()

        val hubs = listOf(
            SaudiJ1939CanBusTruckEngine.LogisticsHub(
                hubId = "HUB_TABUK",
                hubNameAr = "ميناء تبوك الجاف",
                center = SaudiJ1939CanBusTruckEngine.GeoCoordinates(28.3835, 36.5662),
                radiusKm = 25.0
            ),
            SaudiJ1939CanBusTruckEngine.LogisticsHub(
                hubId = "HUB_ARAR",
                hubNameAr = "مركز لوجستيات عرعر",
                center = SaudiJ1939CanBusTruckEngine.GeoCoordinates(30.9753, 41.0381),
                radiusKm = 20.0
            )
        )

        // Location inside Tabuk hub (~10km from center) with legal weight (38 tons) and legal speed (85 km/h)
        val locationInside = SaudiJ1939CanBusTruckEngine.GeoCoordinates(28.4200, 36.6000)
        val auditInside = engine.auditFleetRoute(
            currentLocation = locationInside,
            currentSpeedKmh = 85.0,
            grossWeightTons = 38.0,
            hubs = hubs
        )
        assertEquals("HUB_TABUK", auditInside.activeLogisticsHub)
        assertFalse(auditInside.isSpeedingViolation)
        assertFalse(auditInside.isOverloaded)

        // Location outside with gross overload (48 tons > 45 ton legal limit) and speeding (105 km/h)
        val locationOutside = SaudiJ1939CanBusTruckEngine.GeoCoordinates(29.5000, 39.0000)
        val auditOverload = engine.auditFleetRoute(
            currentLocation = locationOutside,
            currentSpeedKmh = 105.0,
            grossWeightTons = 48.0,
            hubs = hubs
        )
        assertNull(auditOverload.activeLogisticsHub)
        assertTrue(auditOverload.isSpeedingViolation)
        assertTrue(auditOverload.isOverloaded)
    }
}
