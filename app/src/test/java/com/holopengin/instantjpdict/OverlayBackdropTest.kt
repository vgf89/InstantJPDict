package com.holopengin.instantjpdict

import org.junit.Test

import org.junit.Assert.*

/**
 * Backdrop math for the translucent-overlay experiment (refs #64).
 * Pure JVM: pins the strip ramp, mode behavior and dismiss-zone predicate
 * so later default-nudges stay deliberate.
 */
class OverlayBackdropTest {
    @Test
    fun defaults_areSane() {
        assertTrue(OverlayBackdrop.SCREENSHOT_ALPHA in 0f..1f)
        assertEquals(StatusStripMode.FADE, OverlayBackdrop.STATUS_STRIP_MODE)
        assertTrue(OverlayBackdrop.STATUS_BAR_HEIGHT_FALLBACK_DP > 0)
        assertTrue(OverlayBackdrop.SWIPE_DISMISS_MIN_TRAVEL_PX > 0)
    }

    @Test
    fun solidMode_keepsEveryRow() {
        for (y in listOf(-5, 0, 10, 79, 80, 500)) {
            assertEquals(255, OverlayBackdrop.stripAlphaAt(y, 80, StatusStripMode.SOLID))
        }
    }

    @Test
    fun cutMode_isHardBand() {
        val strip = 80
        assertEquals(0, OverlayBackdrop.stripAlphaAt(0, strip, StatusStripMode.CUT))
        assertEquals(0, OverlayBackdrop.stripAlphaAt(strip - 1, strip, StatusStripMode.CUT))
        assertEquals(255, OverlayBackdrop.stripAlphaAt(strip, strip, StatusStripMode.CUT))
        assertEquals(255, OverlayBackdrop.stripAlphaAt(strip + 40, strip, StatusStripMode.CUT))
    }

    @Test
    fun fadeMode_rampsTransparentToOpaque() {
        val strip = 80
        assertEquals(0, OverlayBackdrop.stripAlphaAt(-1, strip, StatusStripMode.FADE))
        val top = OverlayBackdrop.stripAlphaAt(0, strip, StatusStripMode.FADE)
        val mid = OverlayBackdrop.stripAlphaAt(strip / 2, strip, StatusStripMode.FADE)
        val bottom = OverlayBackdrop.stripAlphaAt(strip - 1, strip, StatusStripMode.FADE)
        assertTrue(top < mid)
        assertTrue(mid < bottom)
        assertEquals(255, bottom)
        assertEquals(255, OverlayBackdrop.stripAlphaAt(strip, strip, StatusStripMode.FADE))
        assertEquals(255, OverlayBackdrop.stripAlphaAt(strip + 100, strip, StatusStripMode.FADE))
    }

    @Test
    fun zeroStrip_disablesTreatment() {
        for (mode in StatusStripMode.entries) {
            assertEquals(255, OverlayBackdrop.stripAlphaAt(0, 0, mode))
            assertEquals(255, OverlayBackdrop.stripAlphaAt(0, -10, mode))
        }
    }

    @Test
    fun dismissZone_isExactlyTheStrip() {
        val strip = 80
        assertTrue(OverlayBackdrop.swipeDismissStartsInStrip(0f, strip))
        assertTrue(OverlayBackdrop.swipeDismissStartsInStrip((strip - 1).toFloat(), strip))
        assertFalse(OverlayBackdrop.swipeDismissStartsInStrip(strip.toFloat(), strip))
        assertFalse(OverlayBackdrop.swipeDismissStartsInStrip((strip + 50).toFloat(), strip))
        assertFalse(OverlayBackdrop.swipeDismissStartsInStrip(-1f, strip))
    }

    @Test
    fun dismissZone_needsKnownStrip() {
        assertFalse(OverlayBackdrop.swipeDismissStartsInStrip(10f, 0))
        assertFalse(OverlayBackdrop.swipeDismissStartsInStrip(10f, -5))
    }
}
