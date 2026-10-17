package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
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
    }

    private var twUtil: Any? = null
    private var handler: Handler? = null
    private var lastTopwayCode: Int = TopwayKey.NO_KEY
    private var lastKeyAtMs: Long = 0L

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

                    appContext.sendBroadcast(
                        Intent("com.andrerinas.openheadunit.DEBUG_KEY").apply {
                            setPackage(appContext.packageName)
                            putExtra("action", "Topway.TWUtil")
                            putExtra("keyCode", keyCode)
                            putExtra("topway_code", "0x%02X (%s)".format(topwayCode, name))
                            putExtra("what", "0x%04X".format(msg.what))
                            putExtra("arg1", msg.arg1)
                        },
                    )

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

    override fun unregister() {
        val instance = twUtil ?: return
        twUtil = null
        handler?.removeCallbacksAndMessages(null)
        handler = null

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
