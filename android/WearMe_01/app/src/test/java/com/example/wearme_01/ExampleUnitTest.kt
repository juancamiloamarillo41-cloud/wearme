package com.example.wearme_01

import org.junit.Test

import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExampleUnitTest {
    @Test
    fun parser_acceptsCurrentPacketWithFanStatus() {
        val packet = buildSensorPacket(SensorBleProfile.expectedPacketSize).apply {
            this[30] = 1.toByte()
            this[31] = FanReason.Model.code.toByte()
        }

        val sample = SensorPacketParser.parse(packet, timestampMillis = 123L)

        assertNotNull(sample)
        assertEquals(123L, sample!!.timestampMillis)
        assertEquals(0x1234, sample.methane)
        assertEquals(true, sample.fanOn)
        assertEquals(FanReason.Model, sample.fanReason)
    }

    @Test
    fun parser_acceptsLegacyPacketWithoutFanStatus() {
        val packet = buildSensorPacket(SensorBleProfile.legacyPacketSize)

        val sample = SensorPacketParser.parse(packet, timestampMillis = 123L)

        assertNotNull(sample)
        assertNull(sample!!.fanOn)
        assertEquals(FanReason.Unknown, sample.fanReason)
    }

    private fun buildSensorPacket(size: Int): ByteArray {
        return ByteArray(size).also { packet ->
            ByteBuffer.wrap(packet)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(1)
                .putShort(2)
                .putShort(3)
                .putShort(4)
                .putShort(5)
                .putShort(6)
                .putFloat(10.5f)
                .putFloat(65.25f)
                .putFloat(11.5f)
                .putFloat(66.25f)

            packet[28] = 0x34.toByte()
            packet[29] = 0x12.toByte()
        }
    }
}
