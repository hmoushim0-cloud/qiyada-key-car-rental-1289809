package sa.factory.qiyadakeycarrental.domain.engine

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Saudi Transport General Authority (TGA) Compliant Commercial Fleet BLE Key Engine.
 * Handles electronic cargo bay locks, cabin access, engine immobilizer control,
 * driver National ID / Iqama validation, and anti-relay RSSI bounding.
 *
 * Strictly zero emojis.
 */
class SaudiCommercialFleetBleKeyEngine {

    companion object {
        const val MAGIC_BYTE_1: Byte = 0x51 // 'Q'
        const val MAGIC_BYTE_2: Byte = 0x59 // 'Y'
        const val PROTOCOL_VERSION: Byte = 0x03

        const val CMD_CARGO_BAY_UNLOCK: Byte = 0x10
        const val CMD_CARGO_BAY_LOCK: Byte = 0x11
        const val CMD_CABIN_UNLOCK: Byte = 0x12
        const val CMD_CABIN_LOCK: Byte = 0x13
        const val CMD_ENGINE_IMMOBILIZER_DISARM: Byte = 0x14
        const val CMD_ENGINE_IMMOBILIZER_ARM: Byte = 0x15

        const val STATUS_SUCCESS: Byte = 0x00
        const val STATUS_UNAUTHORIZED: Byte = 0x01
        const val STATUS_EXPIRED_MANIFEST: Byte = 0x02
        const val STATUS_OUT_OF_RANGE: Byte = 0x03
        const val STATUS_INTEGRITY_MISMATCH: Byte = 0x04
        const val STATUS_INVALID_TIMESTAMP: Byte = 0x05

        const val MAX_ALLOWED_TIMESTAMP_DRIFT_MS = 3000L
        const val MIN_RSSI_SAFE_PROXIMITY_DBM = -70
    }

    data class DriverTransportManifest(
        val manifestId: String,
        val truckVin: String,
        val driverNationalId: String,
        val validFromEpochMs: Long,
        val validToEpochMs: Long,
        val secretKeyHex: String,
        val isHazmatCertified: Boolean = false
    )

    data class FleetKeyCommandFrame(
        val commandCode: Byte,
        val sequenceNumber: Int,
        val manifestId: String,
        val nonce: Long,
        val rssiDbm: Int,
        val timestampEpochMs: Long,
        val signatureHmac: String
    )

    data class KeyVerificationResult(
        val isSuccess: Boolean,
        val statusCode: Byte,
        val message: String,
        val responseAcknowledgeHex: String
    )

    /**
     * Validates Saudi National ID or Iqama (10 digits starting with 1 or 2).
     */
    fun validateDriverId(id: String): Boolean {
        if (!Regex("^[12]\\d{9}$").matches(id)) {
            return false
        }
        var sum = 0
        for (i in 0 until 10) {
            val digit = id[i] - '0'
            if (i % 2 == 0) {
                val doubled = digit * 2
                sum += if (doubled > 9) doubled - 9 else doubled
            } else {
                sum += digit
            }
        }
        return sum % 10 == 0
    }

    /**
     * Computes HMAC-SHA256 signature string for truck control frames.
     */
    fun computeHmacSha256(keyHex: String, data: String): String {
        val keyBytes = hexStringToByteArray(keyHex)
        val secretKey = SecretKeySpec(keyBytes, "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        val rawHmac = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return rawHmac.joinToString("") { "%02x".format(it) }
    }

    /**
     * Builds and signs a commercial truck control frame.
     */
    fun buildCommandFrame(
        manifest: DriverTransportManifest,
        commandCode: Byte,
        sequenceNumber: Int,
        nonce: Long,
        rssiDbm: Int,
        timestampMs: Long
    ): FleetKeyCommandFrame {
        val hmac = computeHmacSha256(
            keyHex = manifest.secretKeyHex,
            data = "${manifest.manifestId}|${manifest.truckVin}|${commandCode}|${sequenceNumber}|$nonce|$timestampMs"
        )

        return FleetKeyCommandFrame(
            commandCode = commandCode,
            sequenceNumber = sequenceNumber,
            manifestId = manifest.manifestId,
            nonce = nonce,
            rssiDbm = rssiDbm,
            timestampEpochMs = timestampMs,
            signatureHmac = hmac
        )
    }

    /**
     * Verifies incoming key command against driver manifest, RSSI barrier, and replay window.
     */
    fun verifyCommandFrame(
        frame: FleetKeyCommandFrame,
        manifest: DriverTransportManifest,
        currentEpochMs: Long
    ): KeyVerificationResult {
        // 1. Manifest Validity Window
        if (currentEpochMs < manifest.validFromEpochMs || currentEpochMs > manifest.validToEpochMs) {
            return KeyVerificationResult(
                isSuccess = false,
                statusCode = STATUS_EXPIRED_MANIFEST,
                message = "[ERR] Driver transport manifest outside active validity window",
                responseAcknowledgeHex = "51590302"
            )
        }

        // 2. Driver National ID Check
        if (!validateDriverId(manifest.driverNationalId)) {
            return KeyVerificationResult(
                isSuccess = false,
                statusCode = STATUS_UNAUTHORIZED,
                message = "[ERR] Unauthorized or invalid Saudi driver identification number",
                responseAcknowledgeHex = "51590301"
            )
        }

        // 3. Proximity / Relay Attack Prevention
        if (frame.rssiDbm < MIN_RSSI_SAFE_PROXIMITY_DBM) {
            return KeyVerificationResult(
                isSuccess = false,
                statusCode = STATUS_OUT_OF_RANGE,
                message = "[ERR] Relay-attack prevention: Signal strength (${frame.rssiDbm} dBm) below threshold",
                responseAcknowledgeHex = "51590303"
            )
        }

        // 4. Timestamp Freshness Drift Check
        val drift = kotlin.math.abs(currentEpochMs - frame.timestampEpochMs)
        if (drift > MAX_ALLOWED_TIMESTAMP_DRIFT_MS) {
            return KeyVerificationResult(
                isSuccess = false,
                statusCode = STATUS_INVALID_TIMESTAMP,
                message = "[ERR] Replay protection: Timestamp drift ($drift ms) exceeded tolerance",
                responseAcknowledgeHex = "51590305"
            )
        }

        // 5. Cryptographic Signature Verification
        val expectedHmac = computeHmacSha256(
            keyHex = manifest.secretKeyHex,
            data = "${manifest.manifestId}|${manifest.truckVin}|${frame.commandCode}|${frame.sequenceNumber}|${frame.nonce}|${frame.timestampEpochMs}"
        )
        if (!expectedHmac.equals(frame.signatureHmac, ignoreCase = true)) {
            return KeyVerificationResult(
                isSuccess = false,
                statusCode = STATUS_INTEGRITY_MISMATCH,
                message = "[ERR] Frame cryptographic signature HMAC mismatch",
                responseAcknowledgeHex = "51590304"
            )
        }

        val successHex = "515900" + String.format("%02X", frame.commandCode)
        return KeyVerificationResult(
            isSuccess = true,
            statusCode = STATUS_SUCCESS,
            message = "[OK] Commercial truck command ${frame.commandCode} verified and executed",
            responseAcknowledgeHex = successHex
        )
    }

    /**
     * Serializes frame into binary buffer for over-the-air BLE transmission.
     */
    fun serializeToBinary(frame: FleetKeyCommandFrame): ByteArray {
        val manifestBytes = frame.manifestId.toByteArray(StandardCharsets.UTF_8)
        val hmacBytes = hexStringToByteArray(frame.signatureHmac)

        val buffer = ByteBuffer.allocate(
            2 + 1 + 1 + 4 + 8 + 4 + 8 + 2 + manifestBytes.size + hmacBytes.size
        )
        buffer.put(MAGIC_BYTE_1)
        buffer.put(MAGIC_BYTE_2)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(frame.commandCode)
        buffer.putInt(frame.sequenceNumber)
        buffer.putLong(frame.nonce)
        buffer.putInt(frame.rssiDbm)
        buffer.putLong(frame.timestampEpochMs)
        buffer.putShort(manifestBytes.size.toShort())
        buffer.put(manifestBytes)
        buffer.put(hmacBytes)

        return buffer.array()
    }

    fun hexStringToByteArray(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
