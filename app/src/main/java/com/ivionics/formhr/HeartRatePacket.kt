package com.ivionics.formhr

// Final nibble C satisfies Polar's public device-ID checksum for prefix 1234567.
const val EMULATED_POLAR_DEVICE_ID = "1234567C"

// Polar's public OH1 manufacturer-data example embeds the legacy identifier 37447F.
// Polar's published expansion/checksum algorithm maps it to this full device ID.
const val POLAR_OH1_EXAMPLE_DEVICE_ID = "37447F17"

/** Bluetooth SIG Heart Rate Measurement (0x2A37) encoder. */
object HeartRatePacket {
    fun encode(beatsPerMinute: Int): ByteArray {
        require(beatsPerMinute in 0..65535) { "Heart rate must fit in an unsigned 16-bit value" }

        return if (beatsPerMinute <= 0xFF) {
            byteArrayOf(0x00, beatsPerMinute.toByte())
        } else {
            byteArrayOf(
                0x01,
                (beatsPerMinute and 0xFF).toByte(),
                ((beatsPerMinute ushr 8) and 0xFF).toByte(),
            )
        }
    }
}

/** OH1-style Polar manufacturer block, excluding the 0x006B company identifier. */
object PolarManufacturerData {
    fun encode(beatsPerMinute: Int, embeddedDeviceId: Int): ByteArray {
        require(beatsPerMinute in 0..255) { "Advertised heart rate must fit in one byte" }
        require(embeddedDeviceId in 0..0x0FFFFFFF) { "Polar device ID must fit in seven hex digits" }

        // Polar's public SDK labels this leading six-byte general-purpose block as an OH1
        // example. Its sample contains 7F 44 37 00 00 00 for the legacy ID 37447F, so keep
        // the little-endian embedded identity aligned with the ID in the advertised name.
        return byteArrayOf(
            0x72,
            0x06,
            (embeddedDeviceId and 0xFF).toByte(),
            ((embeddedDeviceId ushr 8) and 0xFF).toByte(),
            ((embeddedDeviceId ushr 16) and 0xFF).toByte(),
            ((embeddedDeviceId ushr 24) and 0xFF).toByte(),
            0x00,
            0x00,
            0x33,
            0x00,
            beatsPerMinute.toByte(),
        )
    }
}

enum class DiscoveryProfile(
    val title: String,
    val subtitle: String,
    val advertisedName: String,
    val includePolarCompanyId: Boolean,
    val polarDeviceId: String? = null,
    val embeddedManufacturerDeviceId: Int? = null,
    val includePolarFileTransfer: Boolean = false,
    val requiresPublicAddressBridge: Boolean = false,
) {
    GENERIC(
        title = "Test 1 · Generic",
        subtitle = "Wayne HR + Heart Rate service",
        advertisedName = "Wayne HR",
        includePolarCompanyId = false,
    ),
    POLAR_NAME(
        title = "Test 2 · Polar name",
        subtitle = "Polar Sense $EMULATED_POLAR_DEVICE_ID + Heart Rate service",
        advertisedName = "Polar Sense $EMULATED_POLAR_DEVICE_ID",
        includePolarCompanyId = false,
        polarDeviceId = EMULATED_POLAR_DEVICE_ID,
    ),
    POLAR_NAME_AND_ID(
        title = "Test 3 · Polar + ID",
        subtitle = "Sense name + full Polar packet",
        advertisedName = "Polar Sense $EMULATED_POLAR_DEVICE_ID",
        includePolarCompanyId = true,
        polarDeviceId = EMULATED_POLAR_DEVICE_ID,
        embeddedManufacturerDeviceId = 0x1234567,
    ),
    POLAR_OH1_FULL(
        title = "Test 4 · OH1 matched",
        subtitle = "OH1 sample name + matching packet ID",
        advertisedName = "Polar OH1 $POLAR_OH1_EXAMPLE_DEVICE_ID",
        includePolarCompanyId = true,
        polarDeviceId = POLAR_OH1_EXAMPLE_DEVICE_ID,
        embeddedManufacturerDeviceId = 0x37447F,
    ),
    POLAR_OH1_WITH_PFTP(
        title = "Test 5 · OH1 + FEEE",
        subtitle = "Matched OH1 + Polar discovery service",
        advertisedName = "Polar OH1 $POLAR_OH1_EXAMPLE_DEVICE_ID",
        includePolarCompanyId = true,
        polarDeviceId = POLAR_OH1_EXAMPLE_DEVICE_ID,
        embeddedManufacturerDeviceId = 0x37447F,
        includePolarFileTransfer = true,
    ),
    PUBLIC_ADDRESS_BRIDGE(
        title = "Test 6 · Public address",
        subtitle = "Working FORM mode · requires ADB helper",
        advertisedName = "Polar OH1 $POLAR_OH1_EXAMPLE_DEVICE_ID",
        includePolarCompanyId = true,
        polarDeviceId = POLAR_OH1_EXAMPLE_DEVICE_ID,
        embeddedManufacturerDeviceId = 0x37447F,
        includePolarFileTransfer = true,
        requiresPublicAddressBridge = true,
    ),
}
