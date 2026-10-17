package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.KeyEvent
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import com.andrerinas.openheadunit.input.TopwayKey
import com.andrerinas.openheadunit.utils.AppLog
import java.io.File

/**
 * Steering-wheel and front-panel key receiver for Topway / XYAUTO head units
 * (Allwinner T3/T3L/T8, Spreadtrum SC9853i, TS9/TS10/TS18).
 *
 * These units expose MCU messages on `/dev/tw` (`0666`) through `android.tw.john.TWUtil` in
 * `/system/framework/framework.jar`. Opening a read listener for `0x0201` and `0x8201` receives
 * every physical button press in real time without requiring root or hijacking audio focus from
 * Topway's stock services when Open Headunit is idle.
 */
class CarTopwayReceiver : CarKeyReceiver {

    companion object {
        private const val TWUTIL_CLASS = "android.tw.john.TWUtil"
        private const val HANDLER_TAG = "OpenHeadunitKeyRx"
        const val LONG_PRESS_THRESHOLD_MS = 600L
        const val SHORT_PRESS_TIMEOUT_MS = 380L

        val DUAL_ACTION_KEYS = setOf(
            0x07, // Mute
            0x12, // Mode
            0x05, // Next
            0x06, // Prev
        )
    }

    private var twUtil: Any? = null
    private var handler: Handler? = null
    private var lastTopwayCode: Int = TopwayKey.NO_KEY
    private var lastKeyAtMs: Long = 0L

    private var currentHoldingKey: Int? = null
    private var firstKeyPressMs: Long = 0L
    private var longPressTriggered: Boolean = false
    private var pendingShortRunnable: Runnable? = null
    private var pendingLongRunnable: Runnable? = null

    override val isSupported: Boolean by lazy {
        runCatching {
            Class.forName(TWUTIL_CLASS)
            File("/dev/tw").exists()
        }.getOrDefault(false)
    }

    override val isSUNeeded: Boolean = false

    override fun register(context: Context) {
        if (twUtil != null) return

        val appContext = context.applicationContext
        try {
            val clazz = Class.forName(TWUTIL_CLASS)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val openMethod = clazz.getMethod("open", ShortArray::class.java)
            val filter = shortArrayOf(
                TopwayKey.WHAT_KEY_EVENT.toShort(),
                TopwayKey.WHAT_SYNTH_KEY_EVENT.toShort(),
            )
            val result = openMethod.invoke(instance, filter) as? Int ?: -1
            if (result != 0) {
                AppLog.w("CarKeyReceiver: Topway TWUtil.open(/dev/tw) returned $result")
                return
            }

            val msgHandler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    val topwayCode = TopwayKey.extractKeyCode(msg.what, msg.arg1, msg.arg2)
                    if (topwayCode == TopwayKey.NO_KEY) return

                    val now = SystemClock.elapsedRealtime()

                    if (topwayCode in DUAL_ACTION_KEYS) {
                        if (topwayCode != currentHoldingKey) {
                            pendingShortRunnable?.run()
                            pendingShortRunnable = null
                            pendingLongRunnable?.let { removeCallbacks(it) }
                            pendingLongRunnable = null

                            currentHoldingKey = topwayCode
                            firstKeyPressMs = now
                            longPressTriggered = false

                            val keyCode = TopwayKey.toKeyCode(topwayCode)
                            val shortRunnable = Runnable {
                                if (!longPressTriggered && currentHoldingKey == topwayCode) {
                                    currentHoldingKey = null
                                    dispatchShortPress(appContext, topwayCode, keyCode, msg.what, msg.arg1)
                                }
                            }
                            pendingShortRunnable = shortRunnable
                            postDelayed(shortRunnable, SHORT_PRESS_TIMEOUT_MS)

                            val longRunnable = Runnable {
                                if (currentHoldingKey == topwayCode && !longPressTriggered) {
                                    longPressTriggered = true
                                    removeCallbacks(shortRunnable)
                                    currentHoldingKey = null
                                    dispatchLongPress(appContext, topwayCode)
                                }
                            }
                            pendingLongRunnable = longRunnable
                            postDelayed(longRunnable, LONG_PRESS_THRESHOLD_MS)
                        } else {
                            if (now - firstKeyPressMs >= LONG_PRESS_THRESHOLD_MS && !longPressTriggered) {
                                longPressTriggered = true
                                pendingShortRunnable?.let { removeCallbacks(it) }
                                pendingShortRunnable = null
                                currentHoldingKey = null
                                dispatchLongPress(appContext, topwayCode)
                            } else if (!longPressTriggered) {
                                pendingShortRunnable?.let {
                                    removeCallbacks(it)
                                    postDelayed(it, SHORT_PRESS_TIMEOUT_MS)
                                }
                            }
                        }
                        return
                    }

                    val debounceMs = TopwayKey.debounceWindowMs(topwayCode)
                    if (topwayCode == lastTopwayCode && now - lastKeyAtMs < debounceMs) {
                        AppLog.d(
                            "CarKeyReceiver: Debounced duplicate Topway key 0x%02X (%d ms < %d ms)".format(
                                topwayCode,
                                now - lastKeyAtMs,
                                debounceMs,
                            ),
                        )
                        return
                    }
                    lastTopwayCode = topwayCode
                    lastKeyAtMs = now

                    val keyCode = TopwayKey.toKeyCode(topwayCode)
                    val name = TopwayKey.nameOf(topwayCode) ?: "unnamed"
                    AppLog.i(
                        "CarKeyReceiver: Topway key %s 0x%02X (what=0x%04X arg1=%d) -> keycode %d".format(
                            name,
                            topwayCode,
                            msg.what,
                            msg.arg1,
                            keyCode,
                        ),
                    )

                    broadcastDebugKey(appContext, keyCode, topwayCode, name, msg.what, msg.arg1, "direct")
                    handleClick(appContext, keyCode)
                }
            }

            clazz.getMethod("start").invoke(instance)
            clazz.getMethod("addHandler", String::class.java, Handler::class.java)
                .invoke(instance, HANDLER_TAG, msgHandler)

            this.twUtil = instance
            this.handler = msgHandler
            AppLog.i("CarKeyReceiver: Registered Topway TWUtil key receiver on /dev/tw")
        } catch (e: Exception) {
            AppLog.e("CarKeyReceiver: Failed to initialize Topway TWUtil receiver", e)
        }
    }

    private fun dispatchShortPress(context: Context, topwayCode: Int, keyCode: Int, what: Int, arg1: Int) {
        val name = TopwayKey.nameOf(topwayCode) ?: "unnamed"
        AppLog.i("CarKeyReceiver: Topway short press %s 0x%02X -> keycode %d".format(name, topwayCode, keyCode))
        broadcastDebugKey(context, keyCode, topwayCode, name, what, arg1, "short")
        handleClick(context, keyCode)
    }

    private fun dispatchLongPress(context: Context, topwayCode: Int) {
        val name = TopwayKey.nameOf(topwayCode) ?: "unnamed"
        AppLog.i("CarKeyReceiver: Topway LONG press %s 0x%02X triggered".format(name, topwayCode))
        broadcastDebugKey(context, TopwayKey.toKeyCode(topwayCode), topwayCode, name, 0, 0, "long")
        when (topwayCode) {
            0x07 -> toggleVoiceAssistant(context)
            0x12 -> handleClick(context, KeyEvent.KEYCODE_HOME)
            0x05 -> handleClick(context, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            0x06 -> handleClick(context, KeyEvent.KEYCODE_MEDIA_REWIND)
            else -> handleClick(context, TopwayKey.toKeyCode(topwayCode))
        }
    }

    private fun broadcastDebugKey(
        context: Context,
        keyCode: Int,
        topwayCode: Int,
        name: String,
        what: Int,
        arg1: Int,
        pressType: String,
    ) {
        context.sendBroadcast(
            Intent("com.andrerinas.openheadunit.DEBUG_KEY").apply {
                setPackage(context.packageName)
                putExtra("action", "Topway.TWUtil")
                putExtra("keyCode", keyCode)
                putExtra("topway_code", "0x%02X (%s)".format(topwayCode, name))
                putExtra("what", "0x%04X".format(what))
                putExtra("arg1", arg1)
                putExtra("press_type", pressType)
            },
        )
    }

    override fun unregister() {
        val instance = twUtil ?: return
        twUtil = null
        handler?.let { h ->
            pendingShortRunnable?.let { h.removeCallbacks(it) }
            pendingLongRunnable?.let { h.removeCallbacks(it) }
            h.removeCallbacksAndMessages(null)
        }
        handler = null
        currentHoldingKey = null

        try {
            val clazz = instance.javaClass
            runCatching {
                clazz.getMethod("removeHandler", String::class.java).invoke(instance, HANDLER_TAG)
            }
            runCatching {
                clazz.getMethod("stop").invoke(instance)
            }
            runCatching {
                clazz.getMethod("close").invoke(instance)
            }
            AppLog.i("CarKeyReceiver: Unregistered Topway TWUtil key receiver")
        } catch (e: Exception) {
            AppLog.e("CarKeyReceiver: Error closing Topway TWUtil receiver", e)
        }
    }
}
