package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.aap.protocol.messages.CarSpeedEvent
import com.andrerinas.openheadunit.aap.protocol.messages.FuelLevelEvent
import com.andrerinas.openheadunit.aap.protocol.messages.GearEvent
import com.andrerinas.openheadunit.aap.protocol.messages.ParkingBrakeEvent
import com.andrerinas.openheadunit.aap.protocol.messages.RpmEvent
import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.andrerinas.openheadunit.view.TopwayVehicleOsdOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopwayCarStateTest {

    @Test
    fun `parseHeadlightNight parses 0x0204 and 0x0102 binary states`() {
        assertFalse(TopwayCarState.parseHeadlightNight(TopwayCarState.WHAT_HEADLIGHT, 0)!!)
        assertTrue(TopwayCarState.parseHeadlightNight(TopwayCarState.WHAT_HEADLIGHT, 1)!!)
        // arg1 == 3 on 0x0204 is a non-headlight status frame in TWService
        assertNull(TopwayCarState.parseHeadlightNight(TopwayCarState.WHAT_HEADLIGHT, 3))

        assertFalse(TopwayCarState.parseHeadlightNight(TopwayCarState.WHAT_BACKLIGHT, 0)!!)
        assertTrue(TopwayCarState.parseHeadlightNight(TopwayCarState.WHAT_BACKLIGHT, 1)!!)
        assertNull(TopwayCarState.parseHeadlightNight(0x0201, 1))
    }

    @Test
    fun `extractBacklightLevels unpacks low byte as day and high byte as night`() {
        val arg2 = (3 shl 8) or 10
        val (day, night) = TopwayCarState.extractBacklightLevels(arg2)
        assertEquals(10, day)
        assertEquals(3, night)
    }

    @Test
    fun `scaleTopwayLevelTo255 maps Topway 0_10 scale to 0_255`() {
        assertEquals(-1, TopwayCarState.scaleTopwayLevelTo255(-1))
        assertEquals(0, TopwayCarState.scaleTopwayLevelTo255(0))
        assertEquals(77, TopwayCarState.scaleTopwayLevelTo255(3))
        assertEquals(128, TopwayCarState.scaleTopwayLevelTo255(5))
        assertEquals(255, TopwayCarState.scaleTopwayLevelTo255(10))
    }

    @Test
    fun `resolveActiveBrightness255 picks day or night level based on headlight state`() {
        assertEquals(
            255,
            TopwayCarState.resolveActiveBrightness255(isNight = false, dayRaw = 10, nightRaw = 3),
        )
        assertEquals(
            77,
            TopwayCarState.resolveActiveBrightness255(isNight = true, dayRaw = 10, nightRaw = 3),
        )
    }

    @Test
    fun `parseDfmCanbusHeadlightNight decodes Raise Dongfeng 0x7D sub 2 and sub 5 frames`() {
        // sub=2: bit 7 (0x80) = width lights, bit 6 (0x40) = low-beam headlights
        assertFalse(
            TopwayCarState.parseDfmCanbusHeadlightNight(
                TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
                byteArrayOf(0x02, 0x00),
            )!!,
        )
        assertTrue(
            TopwayCarState.parseDfmCanbusHeadlightNight(
                TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
                byteArrayOf(0x02, 0x40),
            )!!,
        )
        assertTrue(
            TopwayCarState.parseDfmCanbusHeadlightNight(
                TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
                byteArrayOf(0x02, 0x80.toByte()),
            )!!,
        )

        // sub=5: bit 2 (0x04) = rzcdf_light vehicle illumination
        assertFalse(
            TopwayCarState.parseDfmCanbusHeadlightNight(
                TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
                byteArrayOf(0x05, 0x08), // ACC on (bit 3), light off (bit 2 = 0)
            )!!,
        )
        assertTrue(
            TopwayCarState.parseDfmCanbusHeadlightNight(
                TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
                byteArrayOf(0x05, 0x0C), // ACC on (bit 3) + light on (bit 2 = 1)
            )!!,
        )

        // Other sub-frames (e.g. sub=4 speed) do not affect headlight state
        assertNull(
            TopwayCarState.parseDfmCanbusHeadlightNight(
                TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
                byteArrayOf(0x04, 0xFF.toByte()),
            ),
        )
    }

    @Test
    fun `parseDfmTelemetry decodes speed kmh to m_s_e3, range, handbrake, reverse, ACC, RPM, and outside temp`() {
        var state = TopwayCarState.VehicleTelemetryState()

        // sub=4: speed = 72.00 km/h (7200 = 0x1C20 -> b1=0x20, b2=0x1C) -> 20.0 m/s -> 20000 e3
        // range = 425.0 km (4250 = 0x109A -> b4=0x9A, b5=0x10)
        state = TopwayCarState.parseDfmTelemetry(
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            payload = byteArrayOf(0x04, 0x20, 0x1C, 0x00, 0x9A.toByte(), 0x10),
            previous = state,
        )!!
        assertEquals(72.0f, state.speedKmh!!, 0.01f)
        assertEquals(20_000, state.speedMetersPerSecE3)
        assertEquals(425, state.rangeKm)

        // sub=5: bit0=1 (handbrake), bit1=1 (reverse), bit3=1 (ACC ON) -> 0x0B
        state = TopwayCarState.parseDfmTelemetry(
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            payload = byteArrayOf(0x05, 0x0B),
            previous = state,
        )!!
        assertEquals(true, state.handbrakeEngaged)
        assertEquals(true, state.reverseGear)
        assertEquals(true, state.accOn)

        // sub=6: RPM = 2500 (0x09C4 -> b1=0xC4, b2=0x09)
        state = TopwayCarState.parseDfmTelemetry(
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            payload = byteArrayOf(0x06, 0xC4.toByte(), 0x09),
            previous = state,
        )!!
        assertEquals(2500, state.rpm)

        // sub=7: outside temp = 62 - 40 = 22°C
        state = TopwayCarState.parseDfmTelemetry(
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            payload = byteArrayOf(0x07, 0x00, 62),
            previous = state,
        )!!
        assertEquals(22, state.exteriorTempC)
    }

    @Test
    fun `parseRaiseDoors decodes 0x24 and 0x7D sub 0x0E door bitmasks`() {
        // 0x24 requires bit0 (0x01) validity flag + bit7 (0x80 = frontRight) + bit3 (0x08 = trunk)
        val doors24 = TopwayCarState.parseRaiseDoors(
            arg1 = TopwayCarState.CAN_CMD_DOORS,
            payload = byteArrayOf((0x80 or 0x08).toByte()),
        )
        assertNotNull(doors24)
        assertTrue(doors24!!.frontLeftOpen)
        assertFalse(doors24.frontRightOpen)
        assertTrue(doors24.trunkOpen)
        assertTrue(doors24.anyOpen)

        val doors7d = TopwayCarState.parseRaiseDoors(
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            payload = byteArrayOf(0x0E, (0x40 or 0x20).toByte()), // Front right + rear left
        )
        assertNotNull(doors7d)
        assertFalse(doors7d!!.frontLeftOpen)
        assertTrue(doors7d.frontRightOpen)
        assertTrue(doors7d.rearLeftOpen)
        assertTrue(doors7d.anyOpen)
    }

    @Test
    fun `parseRaiseClimate decodes 0x21 climate and glass heater defrost state`() {
        // b0: powerOn (0x80) + acOn (0x40) + frontDefrostMax (0x02) + rearHeater (0x01) = 0xC3
        // b1: fanSpeed = 4
        // b2: leftTemp = 11 -> 18.0 + (11 - 1) * 0.5 = 23.0°C
        // b3: rightTemp = 0x1F -> HI
        val climate = TopwayCarState.parseRaiseClimate(
            arg1 = TopwayCarState.CAN_CMD_HVAC,
            payload = byteArrayOf(0xC3.toByte(), 0x04, 11, 0x1F),
            exteriorTempC = 19,
        )
        assertNotNull(climate)
        assertTrue(climate!!.powerOn)
        assertTrue(climate.acOn)
        assertTrue(climate.frontDefrostMax)
        assertTrue(climate.rearHeater)
        assertEquals(4, climate.fanSpeed)
        assertEquals("23.0°C", climate.leftTempText)
        assertEquals("HI", climate.rightTempText)

        val osdEs = TopwayVehicleOsdOverlay.formatClimateLine(climate, outsideTempC = 19, spanish = true)
        assertTrue(osdEs.contains("Desempañador Frontal"))
        assertTrue(osdEs.contains("Calefactor Luneta"))
        assertTrue(osdEs.contains("A/C ON"))
        assertTrue(osdEs.contains("23.0°C / HI"))
        assertTrue(osdEs.contains("Ext: 19°C"))
    }

    @Test
    fun `vehicle sensor protobuf messages serialize valid SensorBatch payloads`() {
        val speedMsg = CarSpeedEvent(20_000)
        val speedBatch = Sensors.SensorBatch.parseFrom(speedMsg.data.copyOfRange(speedMsg.dataOffset, speedMsg.size))
        assertEquals(20_000, speedBatch.speedDataList[0].speedE3)

        val rpmMsg = RpmEvent(2500)
        val rpmBatch = Sensors.SensorBatch.parseFrom(rpmMsg.data.copyOfRange(rpmMsg.dataOffset, rpmMsg.size))
        assertEquals(2500, rpmBatch.rpmList[0].rpm)

        val brakeMsg = ParkingBrakeEvent(true)
        val brakeBatch = Sensors.SensorBatch.parseFrom(brakeMsg.data.copyOfRange(brakeMsg.dataOffset, brakeMsg.size))
        assertTrue(brakeBatch.parkingbrakeDataList[0].isEngaged)

        val gearMsg = GearEvent.fromReverseAndHandbrake(reverseGear = true, handbrakeEngaged = false)
        val gearBatch = Sensors.SensorBatch.parseFrom(gearMsg.data.copyOfRange(gearMsg.dataOffset, gearMsg.size))
        assertEquals(Sensors.SensorBatch.GearData.GEAR.REVERSE, gearBatch.gearDataList[0].gear)

        val fuelMsg = FuelLevelEvent(rangeKm = 380, lowFuel = false)
        val fuelBatch = Sensors.SensorBatch.parseFrom(fuelMsg.data.copyOfRange(fuelMsg.dataOffset, fuelMsg.size))
        assertEquals(380, fuelBatch.fuelDataList[0].range)
    }

    @Test
    fun `stalk rotation Auto to Off to posicion to bajas distinguishes manual highway lights from Auto tunnel lights`() {
        TopwayCarState.resetForTests()

        // 1. AUTO mode entering a tunnel during daytime: 0x00 -> 0xC0 (width + low beam simultaneously)
        TopwayCarState.handleTwMessage(
            what = TopwayCarState.WHAT_CANBUS,
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            arg2 = 0,
            obj = byteArrayOf(0x02, 0x00),
            nowMs = 1_000L,
        )
        assertFalse(TopwayCarState.headlightNight!!)
        assertFalse(TopwayCarState.isManualHeadlightOn)

        TopwayCarState.handleTwMessage(
            what = TopwayCarState.WHAT_CANBUS,
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            arg2 = 0,
            obj = byteArrayOf(0x02, 0xC0.toByte()),
            nowMs = 10_000L,
        )
        assertTrue(TopwayCarState.headlightNight!!)
        assertFalse(TopwayCarState.isManualHeadlightOn)
        // Daytime in a tunnel -> Night Mode = true
        assertTrue(TopwayCarState.resolveEffectiveCarSignalNight(isAstronomicalNight = false)!!)

        // Exit tunnel -> lights off (stay off for > 2.5s)
        TopwayCarState.handleTwMessage(
            what = TopwayCarState.WHAT_CANBUS,
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            arg2 = 0,
            obj = byteArrayOf(0x02, 0x00),
            nowMs = 40_000L,
        )
        assertFalse(TopwayCarState.headlightNight!!)
        assertFalse(TopwayCarState.resolveEffectiveCarSignalNight(isAstronomicalNight = false)!!)

        // 2. Manual rotation entering highway during daytime: Auto -> Off -> posición (0x80) -> bajas (0xC0)
        TopwayCarState.handleTwMessage(
            what = TopwayCarState.WHAT_CANBUS,
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            arg2 = 0,
            obj = byteArrayOf(0x02, 0x80.toByte()),
            nowMs = 60_000L,
        )
        assertTrue(TopwayCarState.headlightNight!!)
        assertTrue(TopwayCarState.isManualHeadlightOn)

        TopwayCarState.handleTwMessage(
            what = TopwayCarState.WHAT_CANBUS,
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            arg2 = 0,
            obj = byteArrayOf(0x02, 0xC0.toByte()),
            nowMs = 60_250L,
        )
        assertTrue(TopwayCarState.headlightNight!!)
        assertTrue(TopwayCarState.isManualHeadlightOn)
        // Daytime on highway with manual lights -> stays in Day Mode (false)
        assertFalse(TopwayCarState.resolveEffectiveCarSignalNight(isAstronomicalNight = false)!!)
        // Once sunset arrives (isAstronomicalNight = true) -> switches to Night Mode (true)
        assertTrue(TopwayCarState.resolveEffectiveCarSignalNight(isAstronomicalNight = true)!!)
    }

    @Test
    fun `daytime highway continuous speed filter marks lights as manual after 3 minutes at highway speed`() {
        TopwayCarState.resetForTests()

        // Lights turn on without sampling 0x80 (e.g. ultra-fast twist or already on at boot)
        TopwayCarState.handleTwMessage(
            what = TopwayCarState.WHAT_CANBUS,
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            arg2 = 0,
            obj = byteArrayOf(0x02, 0xC0.toByte()),
            nowMs = 1_000L,
        )
        assertTrue(TopwayCarState.headlightNight!!)
        assertFalse(TopwayCarState.isManualHeadlightOn)

        // Driving at 90.00 km/h (9000 = 0x2328 -> b1=0x28, b2=0x23) at t = 2s (inside tunnel window)
        TopwayCarState.handleTwMessage(
            what = TopwayCarState.WHAT_CANBUS,
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            arg2 = 0,
            obj = byteArrayOf(0x04, 0x28, 0x23, 0x00, 0x00, 0x00),
            nowMs = 2_000L,
        )
        assertTrue(TopwayCarState.resolveEffectiveCarSignalNight(isAstronomicalNight = false)!!)

        // Still driving at 90 km/h after 3 minutes + 1s (183_000 ms) -> exceeds tunnel duration -> manual highway lights
        TopwayCarState.handleTwMessage(
            what = TopwayCarState.WHAT_CANBUS,
            arg1 = TopwayCarState.CAN_CMD_DFM_VEHICLE_INFO,
            arg2 = 0,
            obj = byteArrayOf(0x04, 0x28, 0x23, 0x00, 0x00, 0x00),
            nowMs = 183_000L,
        )
        assertTrue(TopwayCarState.isManualHeadlightOn)
        assertFalse(TopwayCarState.resolveEffectiveCarSignalNight(isAstronomicalNight = false)!!)
        assertTrue(TopwayCarState.resolveEffectiveCarSignalNight(isAstronomicalNight = true)!!)
    }
}
