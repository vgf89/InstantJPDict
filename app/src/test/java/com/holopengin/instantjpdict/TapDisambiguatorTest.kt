package com.holopengin.instantjpdict

import org.junit.Test

import org.junit.Assert.*

// #61: tap-vs-drag/pinch disambiguation must keep taps working while letting
// drags and pinches escape to the root layout's pan/zoom handling.
class TapDisambiguatorTest {
    @Test
    fun tap_within_slop_stays_candidate() {
        val tap = TapDisambiguator(10f)
        tap.onDown(100f, 100f)
        assertFalse(tap.shouldCancelOnMove(105f, 105f))
        assertTrue(tap.isTapAtUp(105f, 105f))
    }

    @Test
    fun move_past_slop_cancels_tap() {
        val tap = TapDisambiguator(10f)
        tap.onDown(100f, 100f)
        assertTrue(tap.shouldCancelOnMove(115f, 100f))
    }

    @Test
    fun slop_boundary_is_inclusive() {
        val tap = TapDisambiguator(10f)
        tap.onDown(0f, 0f)
        // Exactly at slop: dx^2+dy^2 == slop^2, not past it.
        assertFalse(tap.shouldCancelOnMove(6f, 8f))
        assertTrue(tap.isTapAtUp(6f, 8f))
    }

    @Test
    fun diagonal_drag_cancels() {
        val tap = TapDisambiguator(10f)
        tap.onDown(50f, 50f)
        assertTrue(tap.shouldCancelOnMove(60f, 60f))
        assertFalse(tap.isTapAtUp(60f, 60f))
    }

    @Test
    fun cancel_clears_candidate() {
        val tap = TapDisambiguator(10f)
        tap.onDown(100f, 100f)
        tap.cancel()
        assertFalse(tap.hasCandidate)
        assertFalse(tap.isTapAtUp(100f, 100f))
    }

    @Test
    fun no_down_means_no_tap() {
        val tap = TapDisambiguator(10f)
        assertFalse(tap.isTapAtUp(0f, 0f))
        assertTrue(tap.shouldCancelOnMove(0f, 0f))
    }

    @Test
    fun second_finger_cancels_tap() {
        // Models ACTION_POINTER_DOWN: the view cancels the candidate so the
        // pinch stream belongs to the parent's ScaleGestureDetector.
        val tap = TapDisambiguator(10f)
        tap.onDown(100f, 100f)
        tap.cancel()
        assertFalse(tap.isTapAtUp(100f, 100f))
    }

    @Test
    fun movedPastSlop_static_matches_instance() {
        assertTrue(TapDisambiguator.movedPastSlop(0f, 0f, 11f, 0f, 10f))
        assertFalse(TapDisambiguator.movedPastSlop(0f, 0f, 9f, 0f, 10f))
    }
}
