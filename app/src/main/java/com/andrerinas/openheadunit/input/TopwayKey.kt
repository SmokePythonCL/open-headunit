package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import java.util.Locale

/**
 * Decodes steering-wheel and front-panel key messages from Topway / XYAUTO head units
 * (Allwinner T3/T3L/T8, Spreadtrum SC9853i, TS9/TS10/TS18) delivered over `/dev/tw` via
 * `android.tw.john.TWUtil`.
 *
 * On Topway firmware (`com.tw.service.TWService`), physical steering-wheel and panel buttons arrive
 * from the MCU as `TWUtil` Handler messages with `what = 0x0201` (513) or `what = 0x8201` (33281),
 * where `msg.arg2` carries the button ID (`1..115`) and `msg.arg1` carries the press state/flags.
 *
 * When a stock Topway audio source (Radio, Music, BT, Video, AUX) owns `mSource`, `TWService`
 * consumes media keys internally by writing `0x9e03` commands to Topway's own apps instead of
 * injecting Android `KeyEvent`s, and dedicated buttons like Mode (`0x12`), Phone (`0x18`),
 * Navi (`0x20`), and Voice (`0x5A`) never become `KeyEvent`s at all.
 */
object TopwayKey {

    /** Primary MCU key press message ID on `/dev/tw`. */
    const val WHAT_KEY_EVENT = 0x0201

    /** Secondary/internal synthetic key press message ID on `/dev/tw`. */
    const val WHAT_SYNTH_KEY_EVENT = 0x8201

    /** Not a valid Topway key code. */
    const val NO_KEY = -1

    /**
     * Base for virtual key codes assigned to Topway keys (`5000..5255`), staying clear of
     * NWD (`1000+`), Eryanet (`2001+`), BZ (`3001+`), and BYD (`4000..4255`).
     */
    const val VIRTUAL_BASE = 5000

    val VIRTUAL_RANGE = VIRTUAL_BASE until (VIRTUAL_BASE + 256)

    /**
     * Catalogue of `msg.arg2` button codes decoded from `com.tw.service.G.handleMessage`
     * (`packed-switch` at `0x0e83`).
     */
    private val NAMES = mapOf(
        0x01 to "Power",
        0x03 to "VolumeUp",
        0x04 to "VolumeDown",
        0x05 to "Next",
        0x06 to "Prev",
        0x07 to "Mute",
        0x10 to "Home",
        0x11 to "Menu",
        0x12 to "Mode",
        0x13 to "SeekNext",
        0x14 to "PlayPause",
        0x15 to "SeekPrev",
        0x16 to "Play",
        0x17 to "Pause",
        0x18 to "Phone",
        0x1A to "Back",
        0x1F to "Brightness",
        0x20 to "Navi",
        0x21 to "Radio",
        0x22 to "DVD",
        0x23 to "Eject",
        0x24 to "AUX",
        0x25 to "DTV",
        0x29 to "Music",
        0x2B to "Photo",
        0x2C to "Video",
        0x2D to "AVIn",
        0x2E to "Hangup",
        0x2F to "Answer",
        0x45 to "RollRight",
        0x46 to "RollLeft",
        0x47 to "RollEnter",
        0x52 to "EQ",
        0x57 to "DVR",
        0x5A to "Voice",
    )

    /**
     * Default Android Auto logical key codes for unambiguous steering-wheel media, phone, and
     * voice buttons when the user has not overridden the button in the Keymap screen.
     *
     * System-wide buttons that `TWService` already acts on globally (`VolumeUp`, `VolumeDown`,
     * `Mute`, `Home`, `Back`, `Brightness`, `Mode`) are intentionally excluded here so they do
     * not double-fire unless explicitly bound by the user in Keymap.
     */
    private val DEFAULT_LOGICAL_KEYS = mapOf(
        0x05 to KeyEvent.KEYCODE_MEDIA_NEXT,
        0x06 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        0x13 to KeyEvent.KEYCODE_MEDIA_NEXT,
        0x14 to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        0x15 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        0x16 to KeyEvent.KEYCODE_MEDIA_PLAY,
        0x17 to KeyEvent.KEYCODE_MEDIA_PAUSE,
        0x18 to KeyEvent.KEYCODE_CALL,
        0x2E to KeyEvent.KEYCODE_ENDCALL,
        0x2F to KeyEvent.KEYCODE_CALL,
        0x5A to KeyEvent.KEYCODE_SEARCH,
    )

    /**
     * Extracts the Topway button code (`1..255`) from a `TWUtil` message (`what`, `arg1`, `arg2`),
     * or returns [NO_KEY] if the message is not a valid button press.
     */
    fun extractKeyCode(what: Int, arg1: Int, arg2: Int): Int {
        if (what != WHAT_KEY_EVENT && what != WHAT_SYNTH_KEY_EVENT) return NO_KEY
        if (arg2 !in 1..255) return NO_KEY
        // arg1 == 0 on Topway 0x0201 is not a key press
        if (arg1 == 0) return NO_KEY
        return arg2
    }

    /** Virtual key code (`5000 + topwayCode`) used for Keymap learning and `CommManager` dispatch. */
    fun toKeyCode(topwayCode: Int): Int = VIRTUAL_BASE + topwayCode

    /** Extracts the raw Topway code (`1..255`) from a virtual key code, or null if not in [VIRTUAL_RANGE]. */
    fun topwayCodeOf(keyCode: Int): Int? =
        if (keyCode in VIRTUAL_RANGE) keyCode - VIRTUAL_BASE else null

    /** Catalogued human-readable name for a raw Topway key code (`0x01..0x73`), or null if unknown. */
    fun nameOf(topwayCode: Int): String? = NAMES[topwayCode]

    /**
     * Default Android `KeyEvent` code for a virtual Topway key code when the user has not mapped
     * it in Keymap, or null if the button should remain inert until mapped.
     */
    fun defaultLogicalKeyCode(keyCode: Int): Int? {
        val topwayCode = topwayCodeOf(keyCode) ?: return null
        return DEFAULT_LOGICAL_KEYS[topwayCode]
    }

    /** Debounce window for rotary encoders and volume buttons (`0x03`, `0x04`, `0x45`, `0x46`). */
    const val ROTARY_DEBOUNCE_MS = 80L

    /**
     * Debounce window for discrete push buttons. Topway MCUs emit duplicate `0x0201`/`0x8201`
     * frames ~33–364 ms apart on a single physical press/release cycle.
     */
    const val BUTTON_DEBOUNCE_MS = 400L

    private val ROTARY_KEYS = setOf(0x03, 0x04, 0x45, 0x46)

    /** Per-key debounce interval in milliseconds for a raw Topway key code. */
    fun debounceWindowMs(topwayCode: Int): Long =
        if (topwayCode in ROTARY_KEYS) ROTARY_DEBOUNCE_MS else BUTTON_DEBOUNCE_MS

    /**
     * Human-readable label for the Keymap screen (e.g., `"Topway Next (0x05)"`), or null when
     * [keyCode] is not a Topway virtual key code.
     */
    fun label(keyCode: Int): String? {
        val topwayCode = topwayCodeOf(keyCode) ?: return null
        return String.format(Locale.US, "Topway %s (0x%02X)", nameOf(topwayCode) ?: "key", topwayCode)
    }
}
