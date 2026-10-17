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
    private var lastDoorAnyOpen: Boolean = false

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
        textView?.visibility = View.GONE
    }

    private fun renderPill() {
        val view = textView ?: return
        if (!settings.showVehicleOsd) {
            view.visibility = View.GONE
            return
        }
        val lines = listOfNotNull(activeDoorLine, activeClimateLine)
        if (lines.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        view.text = lines.joinToString("\n")
        val density = view.resources.displayMetrics.density
        val strokeColor = if (TopwayCarState.doorState.anyOpen) {
            Color.parseColor("#FFFF5252")
        } else {
            Color.parseColor("#664FC3F7")
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
