package com.ivionics.formhr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRatePacketTest {
    @Test
    fun `145 bpm uses the standard two byte packet`() {
        assertArrayEquals(byteArrayOf(0x00, 0x91.toByte()), HeartRatePacket.encode(145))
    }

    @Test
    fun `values above 255 use little endian uint16`() {
        assertArrayEquals(byteArrayOf(0x01, 0x2C, 0x01), HeartRatePacket.encode(300))
    }

    @Test
    fun `polar manufacturer frame carries current heart rate`() {
        assertArrayEquals(
            byteArrayOf(
                0x72,
                0x06,
                0x7F,
                0x44,
                0x37,
                0x00,
                0x00,
                0x00,
                0x33,
                0x00,
                0x91.toByte(),
            ),
            PolarManufacturerData.encode(145, 0x37447F),
        )
    }

    @Test
    fun `sense manufacturer frame embeds the same id prefix as its name`() {
        assertArrayEquals(
            byteArrayOf(
                0x72,
                0x06,
                0x67,
                0x45,
                0x23,
                0x01,
                0x00,
                0x00,
                0x33,
                0x00,
                0x91.toByte(),
            ),
            PolarManufacturerData.encode(145, 0x1234567),
        )
    }

    @Test
    fun `polar company profile uses assigned identifier`() {
        assertEquals(0x006B, BleHeartRatePeripheral.POLAR_COMPANY_ID)
        assertEquals("1234567C", EMULATED_POLAR_DEVICE_ID)
        assertEquals("Polar Sense 1234567C", DiscoveryProfile.POLAR_NAME_AND_ID.advertisedName)
        assertEquals("Polar OH1 37447F17", DiscoveryProfile.POLAR_OH1_FULL.advertisedName)
        assertEquals(0x1234567, DiscoveryProfile.POLAR_NAME_AND_ID.embeddedManufacturerDeviceId)
        assertEquals(0x37447F, DiscoveryProfile.POLAR_OH1_FULL.embeddedManufacturerDeviceId)
        assertEquals(true, DiscoveryProfile.POLAR_OH1_WITH_PFTP.includePolarFileTransfer)
        assertEquals(true, DiscoveryProfile.PUBLIC_ADDRESS_BRIDGE.requiresPublicAddressBridge)
        assertEquals("Polar OH1 37447F17", DiscoveryProfile.PUBLIC_ADDRESS_BRIDGE.advertisedName)
        assertEquals("0000feee-0000-1000-8000-00805f9b34fb", BleHeartRatePeripheral.POLAR_FILE_TRANSFER_SERVICE.toString())
        assertEquals(true, DiscoveryProfile.POLAR_NAME_AND_ID.includePolarCompanyId)
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", BleHeartRatePeripheral.BATTERY_SERVICE.toString())
        assertEquals("0000180a-0000-1000-8000-00805f9b34fb", BleHeartRatePeripheral.DEVICE_INFORMATION_SERVICE.toString())
    }
}
