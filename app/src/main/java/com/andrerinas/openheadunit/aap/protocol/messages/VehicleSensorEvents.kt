package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message

/**
 * Vehicle speed sensor event (`Sensors.SensorType.CAR_SPEED`, `speed_e3` in `m/s * 1000`).
 */
class CarSpeedEvent(val speedMetersPerSecE3: Int)
    : SensorEvent(Sensors.SensorType.CAR_SPEED_VALUE, makeProto(speedMetersPerSecE3)) {

    companion object {
        fun fromKmh(speedKmh: Float): CarSpeedEvent {
            val speedE3 = ((speedKmh / 3.6f) * 1000f).toInt().coerceAtLeast(0)
            return CarSpeedEvent(speedE3)
        }

        private fun makeProto(speedMetersPerSecE3: Int): Message =
            Sensors.SensorBatch.newBuilder()
                .addSpeedData(
                    Sensors.SensorBatch.SpeedData.newBuilder()
                        .setSpeedE3(speedMetersPerSecE3.coerceAtLeast(0))
                )
                .build()
    }
}

/**
 * Engine RPM sensor event (`Sensors.SensorType.RPM`, `rpm` in `1/min` or `rpm * 1000` depending on unit, raw RPM).
 */
class RpmEvent(val rpm: Int)
    : SensorEvent(Sensors.SensorType.RPM_VALUE, makeProto(rpm)) {

    companion object {
        private fun makeProto(rpm: Int): Message =
            Sensors.SensorBatch.newBuilder()
                .addRpm(
                    Sensors.SensorBatch.RpmData.newBuilder()
                        .setRpm(rpm.coerceAtLeast(0))
                )
                .build()
    }
}

/**
 * Parking brake / handbrake sensor event (`Sensors.SensorType.PARKING_BRAKE`).
 */
class ParkingBrakeEvent(val isEngaged: Boolean)
    : SensorEvent(Sensors.SensorType.PARKING_BRAKE_VALUE, makeProto(isEngaged)) {

    companion object {
        private fun makeProto(isEngaged: Boolean): Message =
            Sensors.SensorBatch.newBuilder()
                .addParkingbrakeData(
                    Sensors.SensorBatch.ParkingBrakeData.newBuilder()
                        .setIsEngaged(isEngaged)
                )
                .build()
    }
}

/**
 * Transmission gear sensor event (`Sensors.SensorType.GEAR`).
 */
class GearEvent(val gear: Sensors.SensorBatch.GearData.GEAR)
    : SensorEvent(Sensors.SensorType.GEAR_VALUE, makeProto(gear)) {

    companion object {
        fun fromReverseAndHandbrake(reverseGear: Boolean, handbrakeEngaged: Boolean?): GearEvent {
            val gear = when {
                reverseGear -> Sensors.SensorBatch.GearData.GEAR.REVERSE
                handbrakeEngaged == true -> Sensors.SensorBatch.GearData.GEAR.PARK
                else -> Sensors.SensorBatch.GearData.GEAR.DRIVE
            }
            return GearEvent(gear)
        }

        private fun makeProto(gear: Sensors.SensorBatch.GearData.GEAR): Message =
            Sensors.SensorBatch.newBuilder()
                .addGearData(
                    Sensors.SensorBatch.GearData.newBuilder()
                        .setGear(gear)
                )
                .build()
    }
}

/**
 * Fuel level & remaining range sensor event (`Sensors.SensorType.FUEL_LEVEL`).
 */
class FuelLevelEvent(
    val rangeKm: Int,
    val fuelPercent: Int = (rangeKm * 100 / 650).coerceIn(0, 100),
    val lowFuel: Boolean = rangeKm in 1..60,
) : SensorEvent(Sensors.SensorType.FUEL_LEVEL_VALUE, makeProto(rangeKm, fuelPercent, lowFuel)) {

    companion object {
        private fun makeProto(rangeKm: Int, fuelPercent: Int, lowFuel: Boolean): Message =
            Sensors.SensorBatch.newBuilder()
                .addFuelData(
                    Sensors.SensorBatch.FuelData.newBuilder()
                        .setFuellevel(fuelPercent.coerceIn(0, 100))
                        .setRange(rangeKm.coerceAtLeast(0))
                        .setLowfuel(lowFuel)
                )
                .build()
    }
}
