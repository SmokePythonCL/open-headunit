package com.andrerinas.openheadunit.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide monitor for Topway / XYAUTO (`t3_p1`, T3L/T8/TS9/TS10) MCU illumination (`0x0204`)
 * and backlight (`0x0102`) state via `/dev/tw` (`android.tw.john.TWUtil`) and `TWService` broadcasts.
 *
 * On Topway head units:
 * - `Settings.System.car_mode` does not exist; headlight (ILL+) state is delivered via `/dev/tw`
 *   messages `0x0204` (`arg1`: `0` = day, `1` = night) and `0x0102` (`arg1`: `0` = day, `1` = night),
 *   as well as system broadcasts `com.zjinnova.zlink.action.OUT_DARK_START` / `OUT_DARK_STOP`.
 * - `Settings.System.SCREEN_BRIGHTNESS` is permanently stuck at `255`; actual screen backlight is
 *   controlled by the MCU on a `0..10` scale and reported in `0x0102` (`arg2 & 0xFF` = day level,
 *   `(arg2 ushr 8) & 0xFF` = night level).
 */
object TopwayCarState {

    const val TWUTIL_CLASS = "android.tw.john.TWUtil"
    const val HANDLER_TAG = "OpenHeadunitCarState"

    /** `/dev/tw` message: Backlight state (`arg1`: 0=day, 1=night; `arg2`: low byte=day, high byte=night). */
    const val WHAT_BACKLIGHT = 0x0102

    /** `/dev/tw` message: Headlight ILL+ state (`arg1`: 0=day, 1=night). */
    const val WHAT_HEADLIGHT = 0x0204

    /** `/dev/tw` message: CANbox pass-through frame (`arg1` = CAN data type, `obj` = `ByteArray`). */
    const val WHAT_CANBUS = 0x0501

    /** Raise (`02:Raise / FF:Dongfeng`) HVAC / climate control / glass heater frame ID (`0x21`). */
    const val CAN_CMD_HVAC = 0x21

    /** Raise (`02:Raise / FF:Dongfeng`) door status frame ID (`0x24`). */
    const val CAN_CMD_DOORS = 0x24

    /** Raise (`02:Raise / FF:Dongfeng`) vehicle body & lighting telemetry frame ID (`0x7D`). */
    const val CAN_CMD_DFM_VEHICLE_INFO = 0x7D

    /** Raise CANbox query command ID (`0x90`). */
    const val CAN_CMD_QUERY = 0x90

    /** Query parameter written to `/dev/tw` to request current state from the MCU/driver. */
    const val QUERY_STATE_ARG = 0xFF

    /** Topway MCU analog radio audio ducking (`arg1`: 1=duck FM radio for navigation/speech, 0=restore FM volume). */
    const val WHAT_FM_DUCKING = 0x0308

    /** Topway MCU hardware master volume command (`arg1`: 1, `arg2`: volume level 0..40). */
    const val WHAT_MCU_VOLUME = 0x0203

    /** Topway MCU master clock synchronization command (`arg1` = time, `arg2` = date). */
    const val WHAT_CLOCK_SYNC = 0x0107

    const val ACTION_OUT_DARK_START = "com.zjinnova.zlink.action.OUT_DARK_START"
    const val ACTION_OUT_DARK_STOP = "com.zjinnova.zlink.action.OUT_DARK_STOP"
    const val ACTION_ACC_ON = "xy.android.acc.on"
    const val ACTION_ACC_OFF = "xy.android.acc.off"

    data class VehicleTelemetryState(
        val speedKmh: Float? = null,
        val rpm: Int? = null,
        val handbrakeEngaged: Boolean? = null,
        val reverseGear: Boolean? = null,
        val accOn: Boolean? = null,
        val rangeKm: Int? = null,
        val odometerKm: Float? = null,
        val exteriorTempC: Int? = null,
        val coolantTempC: Int? = null,
        val oilTempC: Int? = null,
        val batteryVoltage: Float? = null,
        val instantFuelL100km: Float? = null,
        val avgFuelL100km: Float? = null,
        val driverSeatBeltFastened: Boolean? = null,
        val passengerSeatBeltFastened: Boolean? = null,
        val tripAKm: Float? = null,
        val tripBKm: Float? = null,
    ) {
        /** Speed in `m/s * 1000` (`speed_e3` in Android Auto `Sensors.SpeedData`). */
        val speedMetersPerSecE3: Int?
            get() = speedKmh?.let { ((it / 3.6f) * 1000f).toInt().coerceAtLeast(0) }

        /** Instant fuel economy converted to km/L (Chilean standard). */
        val instantKmPerLiter: Float?
            get() = instantFuelL100km?.let { if (it > 0.05f) 100.0f / it else null }

        /** Average fuel economy converted to km/L (Chilean standard). */
        val avgKmPerLiter: Float?
            get() = avgFuelL100km?.let { if (it > 0.05f) 100.0f / it else null }
    }

    data class DoorState(
        val frontLeftOpen: Boolean = false,
        val frontRightOpen: Boolean = false,
        val rearLeftOpen: Boolean = false,
        val rearRightOpen: Boolean = false,
        val trunkOpen: Boolean = false,
        val hoodOpen: Boolean = false,
    ) {
        val anyOpen: Boolean
            get() = frontLeftOpen || frontRightOpen || rearLeftOpen || rearRightOpen || trunkOpen || hoodOpen
    }

    data class ClimateState(
        val powerOn: Boolean = false,
        val acOn: Boolean = false,
        val innerLoop: Boolean = true,
        val autoMode: Boolean = false,
        val dualMode: Boolean = false,
        val frontDefrostMax: Boolean = false,
        val rearHeater: Boolean = false,
        val windUpward: Boolean = false,
        val windParallel: Boolean = false,
        val windDownward: Boolean = false,
        val fanSpeed: Int = 0,
        val leftTempText: String? = null,
        val rightTempText: String? = null,
        val popupRequested: Boolean = false,
        val exteriorTempC: Int? = null,
    ) {
        fun hasSameHvacSettings(other: ClimateState): Boolean =
            powerOn == other.powerOn &&
                acOn == other.acOn &&
                innerLoop == other.innerLoop &&
                autoMode == other.autoMode &&
                dualMode == other.dualMode &&
                frontDefrostMax == other.frontDefrostMax &&
                rearHeater == other.rearHeater &&
                windUpward == other.windUpward &&
                windParallel == other.windParallel &&
                windDownward == other.windDownward &&
                fanSpeed == other.fanSpeed &&
                leftTempText == other.leftTempText &&
                rightTempText == other.rightTempText
    }

    interface Listener {
        fun onHeadlightChanged(isNight: Boolean) {}
        fun onBrightnessChanged(brightness0To255: Int) {}
        fun onTelemetryChanged(telemetry: VehicleTelemetryState) {}
        fun onAccChanged(accOn: Boolean) {}
        fun onDoorsChanged(doors: DoorState) {}
        fun onClimateChanged(climate: ClimateState) {}
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = CopyOnWriteArraySet<Listener>()

    private var twUtil: Any? = null
    private var twHandler: Handler? = null
    private var broadcastRegistered = false

    /** Maximum time window (ms) for a brief OFF->ON transition when rotating stalk from `Auto` through `Off` to `bajas`. */
    const val STALK_QUICK_OFF_ON_WINDOW_MS = 2500L

    /** Highway/road speed threshold (km/h) for distinguishing daytime highway driving from underground parking. */
    const val HIGHWAY_SPEED_THRESHOLD_KMH = 50.0f

    /** Maximum duration (ms) of a daytime tunnel at highway speed before continuous lights are treated as manual highway lights (3 min). */
    const val DAYTIME_TUNNEL_MAX_DURATION_MS = 180_000L

    @Volatile
    var headlightNight: Boolean? = null
        private set

    /**
     * `true` when the current headlight-ON session was initiated manually on the stalk
     * (`Auto -> Off -> posición -> bajas`, passing through position-only `0x80` or a quick `<2.5s`
     * `Auto -> Off -> bajas` toggle) or has remained continuously ON for `>= 3 minutes` at highway
     * speed (`>= 50 km/h`).
     */
    @Volatile
    var isManualHeadlightOn: Boolean = false
        private set

    private var lastSub02LightsOn: Boolean = false
    private var lastLightsOffTimestampMs: Long = 0L
    private var highwayContinuousLightStartMs: Long = 0L

    @Volatile
    var dayRawLevel: Int = -1
        private set

    @Volatile
    var nightRawLevel: Int = -1
        private set

    @Volatile
    var brightness0To255: Int = -1
        private set

    @Volatile
    var telemetryState: VehicleTelemetryState = VehicleTelemetryState()
        private set

    @Volatile
    var doorState: DoorState = DoorState()
        private set

    @Volatile
    var climateState: ClimateState? = null
        private set

    val isSupported: Boolean by lazy {
        runCatching {
            Class.forName(TWUTIL_CLASS)
            File("/dev/tw").exists()
        }.getOrDefault(false)
    }

    /**
     * Resolves the effective night mode for `CAR_SIGNAL` (and Topway light detection):
     * - Returns `null` if no Topway headlight state has been received yet.
     * - Returns `false` (Day Mode) when vehicle lights are OFF.
     * - Returns `true` (Night Mode) when it is astronomically night (between sunset and sunrise)
     *   and vehicle lights are ON (whether in `Auto` or `Manual`).
     * - During daytime (`!isAstronomicalNight`), returns `true` (Night Mode) ONLY when the lights
     *   were turned on automatically by the vehicle's ambient light sensor (`!isManualHeadlightOn`,
     *   e.g. entering a tunnel or underground parking), and returns `false` (Day Mode) when the
     *   driver turned on the lights manually (`Auto -> Off -> posición -> bajas` or continuous
     *   daytime highway driving).
     */
    fun resolveEffectiveCarSignalNight(isAstronomicalNight: Boolean): Boolean? {
        val rawNight = headlightNight ?: return null
        if (!rawNight) return false
        if (isAstronomicalNight) return true
        return !isManualHeadlightOn
    }

    /**
     * Parses `isNight` from a `/dev/tw` message `what` and `arg1`.
     * Returns `true` when headlights are ON, `false` when OFF, or `null` if the message/argument
     * does not carry a binary headlight state (e.g. `0x0204` with `arg1 == 3`).
     */
    fun parseHeadlightNight(what: Int, arg1: Int): Boolean? {
        if (what != WHAT_HEADLIGHT && what != WHAT_BACKLIGHT) return null
        return when (arg1) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    /**
     * Parses `isNight` from a Dongfeng / Forthing T5L Raise CANbox (`0x0501`, `arg1 = 0x7D`) frame,
     * which reports when the vehicle's automatic ambient light sensor or light stalk turns the
     * vehicle's lights ON or OFF:
     * - `sub == 0x02` (`payload[0] == 2`): `payload[1]` bit 7 (`0x80`, position/width lights) or
     *   bit 6 (`0x40`, low-beam headlights).
     * - `sub == 0x05` (`payload[0] == 5`): `payload[1]` bit 2 (`0x04`, `rzcdf_light` illumination).
     */
    fun parseDfmCanbusHeadlightNight(arg1: Int, payload: ByteArray?): Boolean? {
        if (arg1 != CAN_CMD_DFM_VEHICLE_INFO || payload == null || payload.size < 2) return null
        val sub = payload[0].toInt() and 0xFF
        val b1 = payload[1].toInt() and 0xFF
        return when (sub) {
            0x02 -> (b1 and 0xC0) != 0
            0x05 -> (b1 and 0x04) != 0
            else -> null
        }
    }

    /**
     * Parses DFM T5L telemetry (`0x7D` sub-frames `0x04`, `0x05`, `0x06`, `0x07`).
     *
     * Byte layouts verified against `Lcom/tw/service/n;.handleMessage` (`[02b06a..02ba64]`):
     * - `sub == 0x04`:
     *   - `speedKmh`: `(((payload[2] & 0xFF) << 8) | (payload[1] & 0xFF)) / 100.0f`
     *   - `rangeKm`: `(((payload[5] & 0xFF) << 8) | (payload[4] & 0xFF)) / 10`
     *   - `odometerKm`: `(((payload[8] & 0xFF) << 16) | ((payload[7] & 0xFF) << 8) | (payload[6] & 0xFF)) / 10.0f`
     * - `sub == 0x05`:
     *   - `handbrakeEngaged`: `(payload[1] & 0x01) != 0`
     *   - `reverseGear`: `(payload[1] & 0x02) != 0`
     *   - `accOn`: `(payload[1] & 0x08) != 0`
     * - `sub == 0x06`:
     *   - `rpm`: `((payload[2] & 0xFF) << 8) | (payload[1] & 0xFF)`
     * - `sub == 0x07`:
     *   - `exteriorTempC`: `(payload[2] & 0xFF) - 40`
     */
    fun parseDfmTelemetry(
        arg1: Int,
        payload: ByteArray?,
        previous: VehicleTelemetryState = VehicleTelemetryState(),
    ): VehicleTelemetryState? {
        if (arg1 != CAN_CMD_DFM_VEHICLE_INFO || payload == null || payload.size < 2) return null
        val sub = payload[0].toInt() and 0xFF
        return when (sub) {
            0x04 -> {
                if (payload.size < 6) return null
                val rawSpeed = ((payload[2].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                val speedKmh = if (rawSpeed == 0xFFFF) previous.speedKmh else (rawSpeed / 100.0f).coerceIn(0f, 300f)
                val rawRange = ((payload[5].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)
                val rangeKm = if (rawRange == 0xFFFF) previous.rangeKm else (rawRange / 10).coerceIn(0, 2000)
                val odometerKm = if (payload.size >= 9) {
                    val rawOdo = ((payload[8].toInt() and 0xFF) shl 16) or
                        ((payload[7].toInt() and 0xFF) shl 8) or
                        (payload[6].toInt() and 0xFF)
                    if (rawOdo == 0xFFFFFF) previous.odometerKm else (rawOdo / 10.0f)
                } else {
                    previous.odometerKm
                }
                val tripAKm = if (payload.size >= 11) {
                    val rawTripA = ((payload[10].toInt() and 0xFF) shl 8) or (payload[9].toInt() and 0xFF)
                    if (rawTripA != 0xFFFF) (rawTripA / 10.0f) else previous.tripAKm
                } else previous.tripAKm
                val tripBKm = if (payload.size >= 13) {
                    val rawTripB = ((payload[12].toInt() and 0xFF) shl 8) or (payload[11].toInt() and 0xFF)
                    if (rawTripB != 0xFFFF) (rawTripB / 10.0f) else previous.tripBKm
                } else previous.tripBKm
                previous.copy(
                    speedKmh = speedKmh,
                    rangeKm = rangeKm,
                    odometerKm = odometerKm,
                    tripAKm = tripAKm,
                    tripBKm = tripBKm,
                )
            }
            0x05 -> {
                val b1 = payload[1].toInt() and 0xFF
                val handbrake = (b1 and 0x01) != 0
                val reverse = (b1 and 0x02) != 0
                val accOn = (b1 and 0x08) != 0
                val driverSeatBelt = (b1 and 0x10) != 0
                val passengerSeatBelt = (b1 and 0x20) != 0
                previous.copy(
                    handbrakeEngaged = handbrake,
                    reverseGear = reverse,
                    accOn = accOn,
                    driverSeatBeltFastened = driverSeatBelt,
                    passengerSeatBeltFastened = passengerSeatBelt,
                )
            }
            0x06 -> {
                if (payload.size < 3) return null
                if (payload.size in 3..4) {
                    val rawRpm = ((payload[2].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    val rpm = if (rawRpm in 0..10000) rawRpm else previous.rpm
                    previous.copy(rpm = rpm)
                } else {
                    val rawInstant = ((payload[2].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    val instantFuel = if (rawInstant in 0..1000) rawInstant / 10.0f else previous.instantFuelL100km
                    val avgFuel = if (payload.size >= 5) {
                        val rawAvg = ((payload[4].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                        if (rawAvg in 0..1000) rawAvg / 10.0f else previous.avgFuelL100km
                    } else previous.avgFuelL100km
                    previous.copy(
                        instantFuelL100km = instantFuel,
                        avgFuelL100km = avgFuel,
                    )
                }
            }
            0x07 -> {
                if (payload.size < 3) return null
                val rawB1 = payload[1].toInt() and 0xFF
                val rawB2 = payload[2].toInt() and 0xFF
                val coolantTempC = if (rawB1 in 1..215) (rawB1 - 40) else previous.coolantTempC
                val oilTempC = if (payload.size >= 4 && rawB2 in 1..215) (rawB2 - 40) else previous.oilTempC
                val extTempC = when {
                    payload.size >= 4 -> {
                        val rawB3 = payload[3].toInt() and 0xFF
                        if (rawB3 in 1..215) (rawB3 - 40) else previous.exteriorTempC
                    }
                    else -> {
                        if (rawB2 in 1..215) (rawB2 - 40) else previous.exteriorTempC
                    }
                }
                previous.copy(
                    coolantTempC = coolantTempC,
                    oilTempC = oilTempC,
                    exteriorTempC = extTempC,
                )
            }
            0x08 -> {
                if (payload.size < 3) return null
                val rawRpm = ((payload[2].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                val rpm = when {
                    rawRpm in 0..10000 -> rawRpm
                    rawRpm / 4 in 0..10000 -> rawRpm / 4
                    else -> previous.rpm
                }
                previous.copy(rpm = rpm)
            }
            0x09 -> {
                if (payload.size < 3) return null
                val rawMv = ((payload[2].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                val voltage = if (rawMv in 5000..20000) (rawMv / 1000.0f) else previous.batteryVoltage
                previous.copy(batteryVoltage = voltage)
            }
            else -> null
        }
    }

    /**
     * Parses Dongfeng (`bM == 0xFF`) HVAC temperature byte from `Lcom/tw/service/n;.jB(I)` (`[029a00..029cb2]`).
     */
    fun parseDfmTemperature(rawByte: Int): String? {
        val v = rawByte and 0xFF
        return when {
            v == 0x00 -> "LO"
            v == 0x1F || v == 0xFF -> "HI"
            v in 1..29 -> String.format(java.util.Locale.US, "%.1f°C", 18.0f + (v - 1) * 0.5f)
            v in 32..35 -> String.format(java.util.Locale.US, "%.1f°C", 16.0f + (v - 32) * 0.5f)
            else -> null
        }
    }

    /**
     * Parses Raise Dongfeng HVAC / Climate Control frame (`arg1 = 0x21`).
     *
     * Note on `TWService` behavior: `Lcom/tw/service/n;.handleMessage` (`[02c470..02c8c2]`) checks
     * `payload[1] & 0x10` (popup request flag) and hides the native popup whenever bit 4 is `0`,
     * which is why the factory popup only appeared when pressing the glass heater button and ignored
     * physical A/C knob changes. By parsing the full state regardless of bit 4, we capture all knob
     * and button changes.
     */
    fun parseRaiseClimate(
        arg1: Int,
        payload: ByteArray?,
        exteriorTempC: Int? = null,
    ): ClimateState? {
        if (arg1 != CAN_CMD_HVAC || payload == null || payload.size < 4) return null
        val b0 = payload[0].toInt() and 0xFF
        val b1 = payload[1].toInt() and 0xFF
        val b2 = payload[2].toInt() and 0xFF
        val b3 = payload[3].toInt() and 0xFF
        val b4 = if (payload.size >= 5) (payload[4].toInt() and 0xFF) else 0

        val fanSpeed = b1 and 0x0F
        val frontDefrostMax = (b0 and 0x02) != 0 || (b4 and 0x08) != 0
        val rearHeater = (b0 and 0x01) != 0 || (b4 and 0x04) != 0
        val powerOn = (b0 and 0x80) != 0 || fanSpeed > 0 || frontDefrostMax || rearHeater

        return ClimateState(
            powerOn = powerOn,
            acOn = (b0 and 0x40) != 0,
            innerLoop = (b0 and 0x20) != 0,
            autoMode = (b0 and 0x08) != 0,
            dualMode = (b0 and 0x04) != 0,
            frontDefrostMax = frontDefrostMax,
            rearHeater = rearHeater,
            windUpward = (b1 and 0x80) != 0,
            windParallel = (b1 and 0x40) != 0,
            windDownward = (b1 and 0x20) != 0,
            fanSpeed = fanSpeed,
            leftTempText = parseDfmTemperature(b2),
            rightTempText = parseDfmTemperature(b3),
            popupRequested = (b1 and 0x10) != 0,
            exteriorTempC = exteriorTempC,
        )
    }

    /**
     * Parses Raise Dongfeng Door Status from either `arg1 = 0x24` (`Lcom/tw/service/g;`)
     * or `arg1 = 0x7D` with `sub = 0x0E` (`Lcom/tw/service/n;`).
     */
    fun parseRaiseDoors(arg1: Int, payload: ByteArray?): DoorState? {
        if (payload == null || payload.isEmpty()) return null
        return when (arg1) {
            CAN_CMD_DOORS -> {
                val b0 = payload[0].toInt() and 0xFF
                DoorState(
                    frontLeftOpen = (b0 and 0x80) != 0,
                    frontRightOpen = (b0 and 0x40) != 0,
                    rearLeftOpen = (b0 and 0x20) != 0,
                    rearRightOpen = (b0 and 0x10) != 0,
                    trunkOpen = (b0 and 0x08) != 0,
                    hoodOpen = (b0 and 0x04) != 0,
                )
            }
            CAN_CMD_DFM_VEHICLE_INFO -> {
                if (payload.size < 2 || (payload[0].toInt() and 0xFF) != 0x0E) return null
                val b1 = payload[1].toInt() and 0xFF
                DoorState(
                    frontLeftOpen = (b1 and 0x80) != 0,
                    frontRightOpen = (b1 and 0x40) != 0,
                    rearLeftOpen = (b1 and 0x20) != 0,
                    rearRightOpen = (b1 and 0x10) != 0,
                    trunkOpen = (b1 and 0x08) != 0,
                    hoodOpen = false,
                )
            }
            else -> null
        }
    }

    /**
     * Extracts `(dayLevel, nightLevel)` from `0x0102` `arg2`.
     */
    fun extractBacklightLevels(arg2: Int): Pair<Int, Int> {
        val day = arg2 and 0xFF
        val night = (arg2 ushr 8) and 0xFF
        return day to night
    }

    /**
     * Scales a Topway MCU backlight level (`0..10` on Allwinner T3/T3L, or `0..100` / `0..255`)
     * to Android's standard `0..255` range.
     */
    fun scaleTopwayLevelTo255(rawLevel: Int): Int {
        if (rawLevel < 0) return -1
        return when {
            rawLevel <= 10 -> ((rawLevel * 255 + 5) / 10).coerceIn(0, 255)
            rawLevel <= 100 -> ((rawLevel * 255 + 50) / 100).coerceIn(0, 255)
            else -> rawLevel.coerceIn(0, 255)
        }
    }

    /**
     * Computes the active `0..255` screen brightness given current day/night state and raw levels.
     */
    fun resolveActiveBrightness255(isNight: Boolean, dayRaw: Int, nightRaw: Int): Int {
        val raw = if (isNight) nightRaw else dayRaw
        return scaleTopwayLevelTo255(raw)
    }

    @Synchronized
    fun registerListener(context: Context, listener: Listener) {
        listeners.add(listener)
        if (!isSupported) return
        ensureStarted(context.applicationContext)
        if (headlightNight == null || brightness0To255 < 0) {
            queryCurrentState()
        }
    }

    @Synchronized
    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun ensureStarted(appContext: Context) {
        if (!broadcastRegistered) {
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(ACTION_OUT_DARK_START)
                    addAction(ACTION_OUT_DARK_STOP)
                    addAction(ACTION_ACC_ON)
                    addAction(ACTION_ACC_OFF)
                }
                ContextCompat.registerReceiver(
                    appContext,
                    darkBroadcastReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED,
                )
                broadcastRegistered = true
            }.onFailure { e ->
                AppLog.w("TopwayCarState: Failed to register broadcast receiver: ${e.message}")
            }
        }

        if (twUtil != null) return

        try {
            val clazz = Class.forName(TWUTIL_CLASS)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val openMethod = clazz.getMethod("open", ShortArray::class.java)
            val filter = shortArrayOf(
                WHAT_BACKLIGHT.toShort(),
                WHAT_HEADLIGHT.toShort(),
                WHAT_CANBUS.toShort(),
            )
            val result = openMethod.invoke(instance, filter) as? Int ?: -1
            if (result != 0) {
                AppLog.w("TopwayCarState: TWUtil.open(/dev/tw) returned $result")
                return
            }

            val handler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    handleTwMessage(msg.what, msg.arg1, msg.arg2, msg.obj)
                }
            }

            clazz.getMethod("start").invoke(instance)
            clazz.getMethod("addHandler", String::class.java, Handler::class.java)
                .invoke(instance, HANDLER_TAG, handler)

            twUtil = instance
            twHandler = handler
            AppLog.i("TopwayCarState: Listening for 0x0102 (backlight), 0x0204 (headlight), and 0x0501 (CANbus) on /dev/tw")

            queryCurrentState()
        } catch (e: Exception) {
            AppLog.e("TopwayCarState: Failed to open TWUtil on /dev/tw", e)
        }
    }

    @Synchronized
    fun queryCurrentState() {
        val instance = twUtil ?: return
        try {
            val clazz = instance.javaClass
            val write2 = runCatching {
                clazz.getMethod("write", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }.getOrNull()
            if (write2 != null) {
                write2.invoke(instance, WHAT_HEADLIGHT, QUERY_STATE_ARG)
                write2.invoke(instance, WHAT_BACKLIGHT, QUERY_STATE_ARG)
            } else {
                val write3 = clazz.getMethod(
                    "write",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                write3.invoke(instance, WHAT_HEADLIGHT, QUERY_STATE_ARG, 0)
                write3.invoke(instance, WHAT_BACKLIGHT, QUERY_STATE_ARG, 0)
            }
            runCatching {
                val write4 = clazz.getMethod(
                    "write",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Any::class.java,
                )
                for (cmd in intArrayOf(CAN_CMD_DFM_VEHICLE_INFO, CAN_CMD_HVAC, CAN_CMD_DOORS)) {
                    val queryPayload = byteArrayOf(cmd.toByte(), 0x00)
                    write4.invoke(instance, WHAT_CANBUS, CAN_CMD_QUERY, queryPayload.size, queryPayload)
                }
            }
        } catch (e: Exception) {
            AppLog.w("TopwayCarState: Failed to query initial /dev/tw state: ${e.message}")
        }
    }

    /**
     * Controls Topway MCU analog radio audio ducking (`0x0308`).
     * Setting this to `true` ducks the FM radio tuner so navigation speech and voice assistant
     * instructions are clearly audible, and restores full FM volume when speech finishes.
     */
    fun setFmDucking(enabled: Boolean) {
        if (!isSupported) return
        val instance = twUtil ?: return
        try {
            val clazz = instance.javaClass
            val writeMethod = clazz.getMethod(
                "write",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            writeMethod.invoke(instance, WHAT_FM_DUCKING, if (enabled) 1 else 0)
            AppLog.i("TopwayCarState: Set FM radio ducking: $enabled (0x0308)")
        } catch (e: Exception) {
            AppLog.w("TopwayCarState: Failed to write FM ducking: ${e.message}")
        }
    }

    /**
     * Sets the Topway MCU master hardware volume (`0x0203`, arg1=1, arg2=level 0..40).
     */
    fun setMcuVolume(volumeLevel: Int) {
        if (!isSupported) return
        val instance = twUtil ?: return
        try {
            val clamped = volumeLevel.coerceIn(0, 40)
            val clazz = instance.javaClass
            val writeMethod = clazz.getMethod(
                "write",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            writeMethod.invoke(instance, WHAT_MCU_VOLUME, 1, clamped)
            AppLog.i("TopwayCarState: Set MCU volume: $clamped (0x0203)")
        } catch (e: Exception) {
            AppLog.w("TopwayCarState: Failed to write MCU volume: ${e.message}")
        }
    }

    /**
     * Sends vehicle configuration settings to the Raise Dongfeng CANbox (`0x0501`).
     */
    fun writeDfmSetting(cmd: Int, sub: Int, value: Int) {
        if (!isSupported) return
        val instance = twUtil ?: return
        try {
            val clazz = instance.javaClass
            val write4 = clazz.getMethod(
                "write",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Any::class.java,
            )
            val payload = byteArrayOf(sub.toByte(), value.toByte())
            write4.invoke(instance, WHAT_CANBUS, cmd, payload.size, payload)
            AppLog.i("TopwayCarState: Wrote DFM setting cmd=0x%02X sub=%d val=%d".format(cmd, sub, value))
        } catch (e: Exception) {
            AppLog.w("TopwayCarState: Failed to write DFM setting: ${e.message}")
        }
    }

    /**
     * Synchronizes phone/system clock to the vehicle instrument cluster clock via Topway MCU `0x0107`.
     */
    fun syncPhoneTimeToVehicle(context: Context) {
        if (!isSupported) return
        val instance = twUtil ?: return
        try {
            val cal = java.util.Calendar.getInstance()
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val minute = cal.get(java.util.Calendar.MINUTE)
            val second = cal.get(java.util.Calendar.SECOND)
            val is24h = android.text.format.DateFormat.is24HourFormat(context)
            val year = (cal.get(java.util.Calendar.YEAR) - 2000).coerceAtLeast(0)
            val month = cal.get(java.util.Calendar.MONTH) + 1
            val day = cal.get(java.util.Calendar.DAY_OF_MONTH)

            val arg1 = (if (is24h) 0 else 1) or (hour shl 8) or (minute shl 16) or (second shl 24)
            val arg2 = year or (month shl 8) or (day shl 16)

            val clazz = instance.javaClass
            val writeMethod = clazz.getMethod(
                "write",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            writeMethod.invoke(instance, WHAT_CLOCK_SYNC, arg1, arg2)
            AppLog.i(
                "TopwayCarState: Synchronized time to cluster: %02d:%02d:%02d (24h=%b)".format(
                    hour, minute, second, is24h,
                )
            )
        } catch (e: Exception) {
            AppLog.w("TopwayCarState: Failed to sync time to vehicle: ${e.message}")
        }
    }

    /**
     * Computes GALA (Speed-sensitive Auto Volume Adjustment) volume step offset.
     * Sensitivity: 0=Off, 1=Low, 2=Medium, 3=High.
     */
    fun computeGalaOffset(speedKmh: Float, sensitivity: Int, previousOffset: Int = 0): Int {
        if (sensitivity <= 0 || speedKmh < 30.0f) return 0
        return when (sensitivity) {
            1 -> {
                when {
                    speedKmh >= 115.0f -> 2
                    previousOffset >= 2 && speedKmh >= 110.0f -> 2
                    speedKmh >= 85.0f -> 1
                    previousOffset >= 1 && speedKmh >= 80.0f -> 1
                    else -> 0
                }
            }
            2 -> {
                when {
                    speedKmh >= 115.0f -> 3
                    previousOffset >= 3 && speedKmh >= 110.0f -> 3
                    speedKmh >= 95.0f -> 2
                    previousOffset >= 2 && speedKmh >= 90.0f -> 2
                    speedKmh >= 70.0f -> 1
                    previousOffset >= 1 && speedKmh >= 65.0f -> 1
                    else -> 0
                }
            }
            3 -> {
                when {
                    speedKmh >= 120.0f -> 4
                    previousOffset >= 4 && speedKmh >= 115.0f -> 4
                    speedKmh >= 100.0f -> 3
                    previousOffset >= 3 && speedKmh >= 95.0f -> 3
                    speedKmh >= 80.0f -> 2
                    previousOffset >= 2 && speedKmh >= 75.0f -> 2
                    speedKmh >= 60.0f -> 1
                    previousOffset >= 1 && speedKmh >= 55.0f -> 1
                    else -> 0
                }
            }
            else -> 0
        }
    }

    private val darkBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_OUT_DARK_START -> {
                    AppLog.i("TopwayCarState: Received broadcast OUT_DARK_START -> night=true")
                    updateState(newNight = true, newDayRaw = null, newNightRaw = null)
                }
                ACTION_OUT_DARK_STOP -> {
                    AppLog.i("TopwayCarState: Received broadcast OUT_DARK_STOP -> night=false")
                    updateState(newNight = false, newDayRaw = null, newNightRaw = null)
                }
                ACTION_ACC_ON -> {
                    AppLog.i("TopwayCarState: Received broadcast xy.android.acc.on -> accOn=true")
                    updateTelemetryAndAcc(telemetryState.copy(accOn = true), forceAccDispatch = true)
                }
                ACTION_ACC_OFF -> {
                    AppLog.i("TopwayCarState: Received broadcast xy.android.acc.off -> accOn=false")
                    updateTelemetryAndAcc(telemetryState.copy(accOn = false), forceAccDispatch = false)
                }
            }
        }
    }

    internal fun handleTwMessage(
        what: Int,
        arg1: Int,
        arg2: Int,
        obj: Any? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        when (what) {
            WHAT_HEADLIGHT -> {
                val parsedNight = parseHeadlightNight(what, arg1)
                if (parsedNight != null) {
                    AppLog.i("TopwayCarState: /dev/tw 0x0204 headlight arg1=$arg1 arg2=$arg2 -> isNight=$parsedNight")
                    updateState(newNight = parsedNight, newDayRaw = null, newNightRaw = null, nowMs = nowMs)
                }
            }
            WHAT_BACKLIGHT -> {
                val parsedNight = parseHeadlightNight(what, arg1)
                val (dayRaw, nightRaw) = extractBacklightLevels(arg2)
                AppLog.i(
                    "TopwayCarState: /dev/tw 0x0102 backlight arg1=$arg1 dayRaw=$dayRaw nightRaw=$nightRaw -> isNight=$parsedNight"
                )
                updateState(newNight = parsedNight, newDayRaw = dayRaw, newNightRaw = nightRaw, nowMs = nowMs)
            }
            WHAT_CANBUS -> {
                val payload = obj as? ByteArray
                val manualChanged = updateDfmLightStalkState(arg1, payload, nowMs)
                val parsedNight = parseDfmCanbusHeadlightNight(arg1, payload)
                if (parsedNight != null || manualChanged) {
                    AppLog.i(
                        "TopwayCarState: /dev/tw 0x0501 CANbus 0x7D -> isNight=$parsedNight manual=$isManualHeadlightOn"
                    )
                    updateState(
                        newNight = parsedNight,
                        newDayRaw = null,
                        newNightRaw = null,
                        forceHeadlightDispatch = manualChanged,
                        nowMs = nowMs,
                    )
                }

                val parsedTelemetry = parseDfmTelemetry(arg1, payload, telemetryState)
                if (parsedTelemetry != null) {
                    updateTelemetryAndAcc(parsedTelemetry, forceAccDispatch = false, nowMs = nowMs)
                }

                val parsedDoors = parseRaiseDoors(arg1, payload)
                if (parsedDoors != null) {
                    updateDoors(parsedDoors)
                }

                val parsedClimate = parseRaiseClimate(arg1, payload, telemetryState.exteriorTempC)
                if (parsedClimate != null) {
                    updateClimate(parsedClimate)
                }
            }
        }
    }

    /**
     * Tracks the DFM T5L light stalk rotary state (`Auto -> Off -> posición -> bajas`) from
     * CANbus `0x7D` `sub = 0x02`:
     * - In `Auto`, the BCM turns on `posición` (`0x80`) and `bajas` (`0x40`) simultaneously (`0xC0`).
     * - When rotated manually (`Auto -> Off -> posición -> bajas`), the stalk either passes through
     *   `posición` alone (`widthOn && !lowBeamOn`, `0x80`) before reaching `bajas` (`0xC0`), or
     *   briefly drops through `Off` (`0x00`) and back on within [STALK_QUICK_OFF_ON_WINDOW_MS].
     *
     * Returns `true` if [isManualHeadlightOn] changed.
     */
    private fun updateDfmLightStalkState(arg1: Int, payload: ByteArray?, nowMs: Long): Boolean {
        if (arg1 != CAN_CMD_DFM_VEHICLE_INFO || payload == null || payload.size < 2) return false
        val sub = payload[0].toInt() and 0xFF
        if (sub != 0x02) return false

        val b1 = payload[1].toInt() and 0xFF
        val widthOn = (b1 and 0x80) != 0
        val lowBeamOn = (b1 and 0x40) != 0

        synchronized(this) {
            val prevManual = isManualHeadlightOn
            when {
                !widthOn && !lowBeamOn -> {
                    if (lastSub02LightsOn) {
                        lastLightsOffTimestampMs = nowMs
                    }
                    lastSub02LightsOn = false
                    isManualHeadlightOn = false
                    highwayContinuousLightStartMs = 0L
                }
                widthOn && !lowBeamOn -> {
                    // Position lights ("posición") alone are never turned on by AUTO mode.
                    isManualHeadlightOn = true
                    lastSub02LightsOn = true
                }
                else -> {
                    // Low beam is ON (0xC0 or 0x40).
                    // If we already passed through "posición" (isManualHeadlightOn == true), keep it true.
                    // Also if lights were ON in AUTO and the driver rotated Auto -> Off -> posición -> bajas
                    // fast enough to only sample 0x00 then 0xC0 within 2.5s, mark as manual.
                    if (!isManualHeadlightOn &&
                        lastLightsOffTimestampMs > 0L &&
                        (nowMs - lastLightsOffTimestampMs) in 1L..STALK_QUICK_OFF_ON_WINDOW_MS
                    ) {
                        isManualHeadlightOn = true
                    }
                    lastSub02LightsOn = true
                }
            }
            return isManualHeadlightOn != prevManual
        }
    }

    private fun updateTelemetryAndAcc(
        newTelemetry: VehicleTelemetryState,
        forceAccDispatch: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        var telemetryChanged = false
        var accChanged = false
        var manualHighwayTriggered = false
        val currentAcc: Boolean
        val currentNight: Boolean

        synchronized(this) {
            val prev = telemetryState
            if (newTelemetry != prev) {
                telemetryState = newTelemetry
                telemetryChanged = true
            }
            if (newTelemetry.accOn != null && (newTelemetry.accOn != prev.accOn || forceAccDispatch)) {
                accChanged = true
            }
            currentAcc = newTelemetry.accOn ?: false

            // Layer 2: Daytime highway filter — if lights are ON and we are driving at highway/road
            // speed (>= 50 km/h) continuously for >= 3 minutes (longer than any tunnel), classify
            // the current headlight session as manual highway lights.
            val speed = telemetryState.speedKmh ?: 0f
            if (headlightNight == true && !isManualHeadlightOn) {
                if (speed >= HIGHWAY_SPEED_THRESHOLD_KMH) {
                    if (highwayContinuousLightStartMs == 0L) {
                        highwayContinuousLightStartMs = nowMs
                    } else if (nowMs - highwayContinuousLightStartMs >= DAYTIME_TUNNEL_MAX_DURATION_MS) {
                        isManualHeadlightOn = true
                        manualHighwayTriggered = true
                        AppLog.i(
                            "TopwayCarState: Lights ON continuously for >=3 min at ${speed} km/h -> manual highway lights"
                        )
                    }
                } else if (speed < 30.0f) {
                    // Underground parking / city stop-and-go: reset highway timer
                    highwayContinuousLightStartMs = 0L
                }
            } else if (headlightNight != true) {
                highwayContinuousLightStartMs = 0L
            }
            currentNight = headlightNight ?: false
        }

        if (telemetryChanged || accChanged || manualHighwayTriggered) {
            dispatchOnMain {
                for (listener in listeners) {
                    if (telemetryChanged) {
                        listener.onTelemetryChanged(newTelemetry)
                    }
                    if (accChanged) {
                        listener.onAccChanged(currentAcc)
                    }
                    if (manualHighwayTriggered) {
                        listener.onHeadlightChanged(currentNight)
                    }
                }
            }
        }
    }

    private fun updateDoors(newDoors: DoorState) {
        var changed = false
        synchronized(this) {
            if (newDoors != doorState) {
                doorState = newDoors
                changed = true
            }
        }
        if (changed) {
            AppLog.i("TopwayCarState: Door state changed -> $newDoors")
            dispatchOnMain {
                for (listener in listeners) {
                    listener.onDoorsChanged(newDoors)
                }
            }
        }
    }

    private fun updateClimate(newClimate: ClimateState) {
        var shouldNotify = false
        synchronized(this) {
            val prev = climateState
            climateState = newClimate
            if (prev == null) {
                // On initial startup query, only show OSD if the CANbox explicitly set the popup bit.
                shouldNotify = newClimate.popupRequested
            } else if (!newClimate.hasSameHvacSettings(prev) || (newClimate.popupRequested && !prev.popupRequested)) {
                shouldNotify = true
            }
        }
        if (shouldNotify) {
            AppLog.i("TopwayCarState: Climate state changed -> $newClimate")
            dispatchOnMain {
                for (listener in listeners) {
                    listener.onClimateChanged(newClimate)
                }
            }
        }
    }

    internal fun resetForTests() {
        synchronized(this) {
            headlightNight = null
            isManualHeadlightOn = false
            lastSub02LightsOn = false
            lastLightsOffTimestampMs = 0L
            highwayContinuousLightStartMs = 0L
            dayRawLevel = -1
            nightRawLevel = -1
            brightness0To255 = -1
            telemetryState = VehicleTelemetryState()
            doorState = DoorState()
            climateState = null
        }
    }

    private fun updateState(
        newNight: Boolean?,
        newDayRaw: Int?,
        newNightRaw: Int?,
        forceHeadlightDispatch: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        var nightChanged = forceHeadlightDispatch
        var brightnessChanged = false
        val currentNight: Boolean
        val currentBrightness: Int

        synchronized(this) {
            if (newNight != null && newNight != headlightNight) {
                if (!newNight) {
                    isManualHeadlightOn = false
                    highwayContinuousLightStartMs = 0L
                } else if ((telemetryState.speedKmh ?: 0f) >= HIGHWAY_SPEED_THRESHOLD_KMH && highwayContinuousLightStartMs == 0L) {
                    highwayContinuousLightStartMs = nowMs
                }
                headlightNight = newNight
                nightChanged = true
            }
            if (newDayRaw != null) {
                dayRawLevel = newDayRaw
            }
            if (newNightRaw != null) {
                nightRawLevel = newNightRaw
            }

            currentNight = headlightNight ?: false
            if (dayRawLevel >= 0 && nightRawLevel >= 0) {
                val resolved = resolveActiveBrightness255(currentNight, dayRawLevel, nightRawLevel)
                if (resolved != brightness0To255) {
                    brightness0To255 = resolved
                    brightnessChanged = true
                }
            }
            currentBrightness = brightness0To255
        }

        if (nightChanged || brightnessChanged) {
            dispatchOnMain {
                for (listener in listeners) {
                    if (nightChanged) {
                        listener.onHeadlightChanged(currentNight)
                    }
                    if (brightnessChanged && currentBrightness >= 0) {
                        listener.onBrightnessChanged(currentBrightness)
                    }
                }
            }
        }
    }

    private fun dispatchOnMain(block: () -> Unit) {
        if (listeners.isEmpty()) return
        val isMain = runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(true)
        if (isMain) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}

