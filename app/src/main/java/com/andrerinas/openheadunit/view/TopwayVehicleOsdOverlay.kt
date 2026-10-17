package com.andrerinas.openheadunit.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.TopwayCarState
import java.util.Locale

/**
 * Non-intrusive Android Auto-styled floating OSD pill drawn at the top center of
 * [com.andrerinas.openheadunit.aap.AapProjectionActivity] when Topway CANbus door (`0x24`, `0x7D`
 * sub `0x0E`) or climate / defrost (`0x21`) events occur.
 *
 * Touch-transparent (`isClickable = false`, `isFocusable = false`) so it never steals touch input
 * or focus from Android Auto.
 */
class TopwayVehicleOsdOverlay(
    private val settings: Settings
) {
    private var textView: TextView? = null
    private var released = false
    private var started = false
    private val handler = Handler(Looper.getMainLooper())

    private var activeDoorLine: String? = null
    private var activeClimateLine: String? = null
    private var activeAlertLine: String? = null
    private var activeTripSummaryLine: String? = null
    private var lastDoorAnyOpen: Boolean = false

    private var tripStartOdometerKm: Float? = null
    private var tripDistanceKm: Float = 0f
    private var tripMaxSpeedKmh: Float = 0f
    private var tripSummaryShownForCurrentStop = false
    private var lastHandbrakeEngaged: Boolean? = null

    private val clearDoorClosedToastRunnable = Runnable {
        if (!TopwayCarState.doorState.anyOpen) {
            activeDoorLine = null
            renderPill()
        }
    }

    private val clearClimateToastRunnable = Runnable {
        activeClimateLine = null
        renderPill()
    }

    private val clearAlertToastRunnable = Runnable {
        activeAlertLine = null
        renderPill()
    }

    private val clearTripSummaryToastRunnable = Runnable {
        activeTripSummaryLine = null
        renderPill()
    }

    private val carStateListener = object : TopwayCarState.Listener {
        override fun onDoorsChanged(doors: TopwayCarState.DoorState) {
            handler.post {
                if (released || !started) return@post
                if (!settings.showVehicleOsd) {
                    hideAll()
                    return@post
                }
                val isSpanish = isSpanishLocale()
                if (doors.anyOpen) {
                    lastDoorAnyOpen = true
                    handler.removeCallbacks(clearDoorClosedToastRunnable)
                    activeDoorLine = formatDoorLine(
                        doors = doors,
                        outsideTempC = TopwayCarState.telemetryState.exteriorTempC,
                        spanish = isSpanish
                    )
                    renderPill()
                } else if (lastDoorAnyOpen) {
                    lastDoorAnyOpen = false
                    activeDoorLine = if (isSpanish) "🚗 Puertas cerradas" else "🚗 All doors closed"
                    renderPill()
                    handler.removeCallbacks(clearDoorClosedToastRunnable)
                    handler.postDelayed(clearDoorClosedToastRunnable, DOOR_CLOSED_DISMISS_MS)
                }
            }
        }

        override fun onClimateChanged(climate: TopwayCarState.ClimateState) {
            handler.post {
                if (released || !started) return@post
                if (!settings.showVehicleOsd) {
                    hideAll()
                    return@post
                }
                val isSpanish = isSpanishLocale()
                activeClimateLine = formatClimateLine(
                    climate = climate,
                    outsideTempC = climate.exteriorTempC ?: TopwayCarState.telemetryState.exteriorTempC,
                    spanish = isSpanish
                )
                renderPill()
                handler.removeCallbacks(clearClimateToastRunnable)
                handler.postDelayed(clearClimateToastRunnable, CLIMATE_DISMISS_MS)
            }
        }

        override fun onTelemetryChanged(telemetry: TopwayCarState.VehicleTelemetryState) {
            handler.post {
                if (released || !started || !settings.showVehicleOsd) return@post
                val isSpanish = isSpanishLocale()
                val speed = telemetry.speedKmh ?: 0f

                // Track trip distance & max speed
                val odo = telemetry.odometerKm
                if (odo != null && odo > 0f) {
                    if (tripStartOdometerKm == null) {
                        tripStartOdometerKm = odo
                    } else {
                        val currentDistance = (odo - tripStartOdometerKm!!).coerceAtLeast(0f)
                        if (currentDistance > tripDistanceKm) {
                            tripDistanceKm = currentDistance
                        }
                    }
                }
                if (speed > tripMaxSpeedKmh) {
                    tripMaxSpeedKmh = speed
                }

                // Safety alerts
                var newAlert: String? = null
                when {
                    telemetry.handbrakeEngaged == true && speed >= 10.0f -> {
                        newAlert = if (isSpanish) "⚠️ ¡Freno de mano accionado en marcha!" else "⚠️ Handbrake engaged while driving!"
                    }
                    telemetry.driverSeatBeltFastened == false && speed >= 15.0f -> {
                        newAlert = if (isSpanish) "⚠️ Cinturón de seguridad desabrochado" else "⚠️ Seatbelt unfastened"
                    }
                    (telemetry.coolantTempC ?: 0) >= 105 -> {
                        newAlert = if (isSpanish) "🔥 ¡Alerta! Temp. refrigerante alta: ${telemetry.coolantTempC}°C" else "🔥 Warning: Engine coolant hot: ${telemetry.coolantTempC}°C"
                    }
                    telemetry.accOn == true && (telemetry.rpm ?: 0) < 400 && (telemetry.batteryVoltage ?: 14f) < 11.8f -> {
                        newAlert = if (isSpanish) "⚠️ Batería 12V baja: %.1f V".format(Locale.US, telemetry.batteryVoltage) else "⚠️ Low 12V battery: %.1f V".format(Locale.US, telemetry.batteryVoltage)
                    }
                    (telemetry.rpm ?: 0) >= 600 && (telemetry.batteryVoltage ?: 14f) < 12.8f -> {
                        newAlert = if (isSpanish) "⚠️ Alternador no carga: %.1f V".format(Locale.US, telemetry.batteryVoltage) else "⚠️ Alternator not charging: %.1f V".format(Locale.US, telemetry.batteryVoltage)
                    }
                }

                if (newAlert != null) {
                    activeAlertLine = newAlert
                    handler.removeCallbacks(clearAlertToastRunnable)
                    handler.postDelayed(clearAlertToastRunnable, ALERT_DISMISS_MS)
                    renderPill()
                } else if (activeAlertLine != null) {
                    activeAlertLine = null
                    renderPill()
                }

                // Trip summary on parking
                val handbrake = telemetry.handbrakeEngaged == true
                if (handbrake && lastHandbrakeEngaged != true && speed < 3.0f) {
                    if ((tripDistanceKm >= 0.1f || tripMaxSpeedKmh >= 15.0f) && !tripSummaryShownForCurrentStop) {
                        tripSummaryShownForCurrentStop = true
                        val avgKmL = telemetry.avgKmPerLiter ?: (telemetry.avgFuelL100km?.let { if (it > 0f) 100f / it else null })
                        activeTripSummaryLine = formatTripSummary(
                            distanceKm = tripDistanceKm,
                            avgKmL = avgKmL,
                            voltage = telemetry.batteryVoltage,
                            spanish = isSpanish
                        )
                        renderPill()
                        handler.removeCallbacks(clearTripSummaryToastRunnable)
                        handler.postDelayed(clearTripSummaryToastRunnable, TRIP_SUMMARY_DISMISS_MS)
                    }
                } else if (!handbrake && speed >= 5.0f) {
                    tripSummaryShownForCurrentStop = false
                    if (activeTripSummaryLine != null) {
                        activeTripSummaryLine = null
                        renderPill()
                    }
                }
                lastHandbrakeEngaged = handbrake
            }
        }

        override fun onAccChanged(accOn: Boolean) {
            handler.post {
                if (released || !started || !settings.showVehicleOsd) return@post
                if (!accOn && (tripDistanceKm >= 0.1f || tripMaxSpeedKmh >= 15.0f)) {
                    val isSpanish = isSpanishLocale()
                    val avgKmL = TopwayCarState.telemetryState.avgKmPerLiter
                    activeTripSummaryLine = formatTripSummary(
                        distanceKm = tripDistanceKm,
                        avgKmL = avgKmL,
                        voltage = TopwayCarState.telemetryState.batteryVoltage,
                        spanish = isSpanish
                    )
                    renderPill()
                    handler.removeCallbacks(clearTripSummaryToastRunnable)
                    handler.postDelayed(clearTripSummaryToastRunnable, TRIP_SUMMARY_DISMISS_MS)
                }
            }
        }
    }

    val isAttached: Boolean get() = textView != null

    fun attachTo(container: FrameLayout) {
        if (textView != null) return
        val context = container.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#D9181C20"))
            setStroke(dp(1), Color.parseColor("#66FFFFFF"))
        }

        val view = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            background = bg
            setPadding(dp(18), dp(10), dp(18), dp(10))
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 110f
                translationZ = 110f
            }
            if (settings.hudMirroring) {
                scaleX = -1.0f
            }
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setMargins(dp(16), dp(16), dp(16), 0)
        }

        textView = view
        container.addView(view, params)
    }

    fun start(context: Context) {
        if (released || !TopwayCarState.isSupported) return
        if (started) {
            if (!settings.showVehicleOsd) hideAll()
            return
        }
        started = true
        val currentDoors = TopwayCarState.doorState
        lastDoorAnyOpen = currentDoors.anyOpen
        if (currentDoors.anyOpen && settings.showVehicleOsd) {
            activeDoorLine = formatDoorLine(
                doors = currentDoors,
                outsideTempC = TopwayCarState.telemetryState.exteriorTempC,
                spanish = isSpanishLocale()
            )
            renderPill()
        }
        TopwayCarState.registerListener(context.applicationContext, carStateListener)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(clearDoorClosedToastRunnable)
        handler.removeCallbacks(clearClimateToastRunnable)
        handler.removeCallbacks(clearAlertToastRunnable)
        handler.removeCallbacks(clearTripSummaryToastRunnable)
        TopwayCarState.unregisterListener(carStateListener)
        hideAll()
    }

    fun release() {
        stop()
        released = true
        textView = null
    }

    fun bringToFront() {
        textView?.bringToFront()
    }

    fun setMirror(scaleX: Float) {
        textView?.scaleX = scaleX
    }

    private fun hideAll() {
        activeDoorLine = null
        activeClimateLine = null
        activeAlertLine = null
        activeTripSummaryLine = null
        textView?.visibility = View.GONE
    }

    private fun renderPill() {
        val view = textView ?: return
        if (!settings.showVehicleOsd) {
            view.visibility = View.GONE
            return
        }
        val lines = listOfNotNull(activeAlertLine, activeTripSummaryLine, activeDoorLine, activeClimateLine)
        if (lines.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        view.text = lines.joinToString("\n")
        val density = view.resources.displayMetrics.density
        val strokeColor = when {
            activeAlertLine != null || TopwayCarState.doorState.anyOpen -> Color.parseColor("#FFFF5252")
            activeTripSummaryLine != null -> Color.parseColor("#FF4CAF50")
            else -> Color.parseColor("#664FC3F7")
        }
        (view.background as? GradientDrawable)?.setStroke((1.5f * density).toInt().coerceAtLeast(1), strokeColor)
        view.visibility = View.VISIBLE
        view.bringToFront()
    }

    private fun isSpanishLocale(): Boolean {
        val lang = Locale.getDefault().language.lowercase(Locale.ROOT)
        return lang.startsWith("es")
    }

    companion object {
        private const val DOOR_CLOSED_DISMISS_MS = 2000L
        private const val CLIMATE_DISMISS_MS = 4000L
        private const val ALERT_DISMISS_MS = 6000L
        private const val TRIP_SUMMARY_DISMISS_MS = 8000L

        internal fun formatTripSummary(
            distanceKm: Float,
            avgKmL: Float?,
            voltage: Float?,
            spanish: Boolean,
        ): String {
            val parts = mutableListOf<String>()
            parts.add(if (spanish) "🏁 Fin de viaje: %.1f km".format(Locale.US, distanceKm) else "🏁 Trip: %.1f km".format(Locale.US, distanceKm))
            if (avgKmL != null && avgKmL > 0f) {
                parts.add(if (spanish) "Rendimiento: %.1f km/L".format(Locale.US, avgKmL) else "Avg: %.1f km/L".format(Locale.US, avgKmL))
            }
            if (voltage != null) {
                parts.add(if (spanish) "Batería: %.1f V".format(Locale.US, voltage) else "Battery: %.1f V".format(Locale.US, voltage))
            }
            return parts.joinToString("  •  ")
        }

        internal fun formatDoorLine(
            doors: TopwayCarState.DoorState,
            outsideTempC: Int?,
            spanish: Boolean
        ): String {
            val openParts = mutableListOf<String>()
            if (doors.frontLeftOpen) openParts.add(if (spanish) "Conductor" else "Front Left")
            if (doors.frontRightOpen) openParts.add(if (spanish) "Pasajero" else "Front Right")
            if (doors.rearLeftOpen) openParts.add(if (spanish) "Trasera Izq." else "Rear Left")
            if (doors.rearRightOpen) openParts.add(if (spanish) "Trasera Der." else "Rear Right")
            if (doors.trunkOpen) openParts.add(if (spanish) "Maletero" else "Trunk")
            if (doors.hoodOpen) openParts.add(if (spanish) "Capó" else "Hood")

            val prefix = if (spanish) "🚗 Puerta abierta: " else "🚗 Door open: "
            val base = if (openParts.isEmpty()) {
                if (spanish) "🚗 Puertas cerradas" else "🚗 All doors closed"
            } else {
                prefix + openParts.joinToString(", ")
            }
            return if (outsideTempC != null) {
                "$base  •  Ext: ${outsideTempC}°C"
            } else {
                base
            }
        }

        internal fun formatClimateLine(
            climate: TopwayCarState.ClimateState,
            outsideTempC: Int?,
            spanish: Boolean
        ): String {
            if (!climate.powerOn && !climate.frontDefrostMax && !climate.rearHeater) {
                val offText = if (spanish) "❄️ Climatizador OFF" else "❄️ Climate OFF"
                return if (outsideTempC != null) "$offText  •  Ext: ${outsideTempC}°C" else offText
            }

            val parts = mutableListOf<String>()
            if (climate.frontDefrostMax) {
                parts.add(if (spanish) "🔥 Desempañador Frontal" else "🔥 Front Defrost")
            }
            if (climate.rearHeater) {
                parts.add(if (spanish) "🔥 Calefactor Luneta" else "🔥 Rear Defrost")
            }
            if (climate.acOn) {
                parts.add("❄️ A/C ON")
            }
            if (climate.autoMode) {
                parts.add("AUTO")
            }
            if (climate.fanSpeed > 0) {
                parts.add(if (spanish) "💨 Vent: ${climate.fanSpeed}" else "💨 Fan: ${climate.fanSpeed}")
            }
            val left = climate.leftTempText
            val right = climate.rightTempText
            val tempDisplay = when {
                !left.isNullOrBlank() && !right.isNullOrBlank() && left != right ->
                    "$left / $right"
                !left.isNullOrBlank() -> left
                !right.isNullOrBlank() -> right
                else -> null
            }
            if (tempDisplay != null) {
                parts.add("🌡️ $tempDisplay")
            }
            parts.add(
                if (climate.innerLoop) {
                    if (spanish) "🔄 Recirculación" else "🔄 Recirc"
                } else {
                    if (spanish) "🌬️ Aire Ext." else "🌬️ Fresh Air"
                }
            )
            if (outsideTempC != null) {
                parts.add("Ext: ${outsideTempC}°C")
            }
            return parts.joinToString("  •  ")
        }
    }
}
