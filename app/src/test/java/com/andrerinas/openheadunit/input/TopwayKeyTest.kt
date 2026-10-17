package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopwayKeyTest {

    @Test
    fun `extractKeyCode accepts 0x0201 and 0x8201 key presses`() {
        assertEquals(0x05, TopwayKey.extractKeyCode(0x0201, 1, 0x05))
        assertEquals(0x5A, TopwayKey.extractKeyCode(0x8201, 1, 0x5A))
    }

    @Test
    fun `extractKeyCode rejects non-key messages and zero arg1`() {
        assertEquals(TopwayKey.NO_KEY, TopwayKey.extractKeyCode(0x0102, 1, 0x05))
        assertEquals(TopwayKey.NO_KEY, TopwayKey.extractKeyCode(0x0201, 0, 0x05))
        assertEquals(TopwayKey.NO_KEY, TopwayKey.extractKeyCode(0x0201, 1, 0))
    }

    @Test
    fun `virtual key codes round-trip and format labels`() {
        val vNext = TopwayKey.toKeyCode(0x05)
        assertEquals(5005, vNext)
        assertEquals(0x05, TopwayKey.topwayCodeOf(vNext))
        assertEquals("Topway Next (0x05)", TopwayKey.label(vNext))

        val vVoice = TopwayKey.toKeyCode(0x5A)
        assertEquals(5090, vVoice)
        assertEquals("Topway Voice (0x5A)", TopwayKey.label(vVoice))

        val vUnknown = TopwayKey.toKeyCode(0x7F)
        assertEquals("Topway key (0x7F)", TopwayKey.label(vUnknown))
        assertNull(TopwayKey.label(4002))
    }

    @Test
    fun `defaultLogicalKeyCode maps media phone and voice keys while leaving system keys null`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, TopwayKey.defaultLogicalKeyCode(TopwayKey.toKeyCode(0x05)))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, TopwayKey.defaultLogicalKeyCode(TopwayKey.toKeyCode(0x06)))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, TopwayKey.defaultLogicalKeyCode(TopwayKey.toKeyCode(0x14)))
        assertEquals(KeyEvent.KEYCODE_CALL, TopwayKey.defaultLogicalKeyCode(TopwayKey.toKeyCode(0x18)))
        assertEquals(KeyEvent.KEYCODE_ENDCALL, TopwayKey.defaultLogicalKeyCode(TopwayKey.toKeyCode(0x2E)))
        assertEquals(KeyEvent.KEYCODE_SEARCH, TopwayKey.defaultLogicalKeyCode(TopwayKey.toKeyCode(0x5A)))

        // Volume and Mode are handled by MCU/TWService unless explicitly bound by the user
        assertNull(TopwayKey.defaultLogicalKeyCode(TopwayKey.toKeyCode(0x03)))
        assertNull(TopwayKey.defaultLogicalKeyCode(TopwayKey.toKeyCode(0x12)))
    }

    @Test
    fun `debounceWindowMs uses shorter window for rotary and volume keys than push buttons`() {
        assertEquals(TopwayKey.ROTARY_DEBOUNCE_MS, TopwayKey.debounceWindowMs(0x03))
        assertEquals(TopwayKey.ROTARY_DEBOUNCE_MS, TopwayKey.debounceWindowMs(0x04))
        assertEquals(TopwayKey.ROTARY_DEBOUNCE_MS, TopwayKey.debounceWindowMs(0x45))
        assertEquals(TopwayKey.BUTTON_DEBOUNCE_MS, TopwayKey.debounceWindowMs(0x10))
        assertEquals(TopwayKey.BUTTON_DEBOUNCE_MS, TopwayKey.debounceWindowMs(0x5A))
    }
}
