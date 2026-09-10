package com.holopengin.instantjpdict

import org.junit.Test

import org.junit.Assert.*

// #59: double-tap zoom toggle math — pure logic, JVM-testable. The overlay
// applies ZoomState to contentContainer + controller so pinch stays consistent.
class DoubleTapZoomTest {
    @Test
    fun toggle_from_rest_zooms_to_default_level() {
        val next = DoubleTapZoom.toggle(1f, 0f, 0f, 100f, 200f)
        assertEquals(2.5f, next.scale)
    }

    @Test
    fun toggle_from_zoomed_resets_to_identity() {
        val next = DoubleTapZoom.toggle(2.5f, -300f, -400f, 100f, 200f)
        assertEquals(1f, next.scale)
        assertEquals(0f, next.transX)
        assertEquals(0f, next.transY)
    }

    @Test
    fun zoom_in_keeps_tap_point_stationary() {
        // focus * newScale + newTrans must equal focus * oldScale + oldTrans.
        val next = DoubleTapZoom.toggle(1f, 0f, 0f, 100f, 200f)
        assertEquals(100f, 100f * next.scale + next.transX, 1e-3f)
        assertEquals(200f, 200f * next.scale + next.transY, 1e-3f)
    }

    @Test
    fun zoom_in_from_panned_state_keeps_focus_stationary() {
        // The content point under the finger stays under the finger:
        // newScale * local + newTrans == focus, with local = (focus - oldTrans) / oldScale.
        val next = DoubleTapZoom.toggle(1f, 50f, -30f, 400f, 500f)
        assertEquals(2.5f, next.scale)
        val localX = (400f - 50f) / 1f
        val localY = (500f + 30f) / 1f
        assertEquals(400f, localX * next.scale + next.transX, 1e-3f)
        assertEquals(500f, localY * next.scale + next.transY, 1e-3f)
    }

    @Test
    fun zoom_target_clamps_to_max_scale() {
        val next = DoubleTapZoom.toggle(1f, 0f, 0f, 100f, 200f, zoomedScale = 9f)
        assertEquals(5f, next.scale)
    }

    @Test
    fun pinch_zoomed_state_double_tap_resets() {
        // Any scale clearly above rest (e.g. pinched to 1.8x) resets to 1x.
        val next = DoubleTapZoom.toggle(1.8f, 10f, 20f, 100f, 200f)
        assertEquals(1f, next.scale)
        assertEquals(0f, next.transX)
        assertEquals(0f, next.transY)
    }

    @Test
    fun slight_drift_above_one_still_counts_as_rest() {
        val next = DoubleTapZoom.toggle(1.02f, 0f, 0f, 100f, 200f)
        assertEquals(2.5f, next.scale)
    }
}
