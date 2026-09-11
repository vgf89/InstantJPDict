package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.BlankRecovery
import org.junit.Test

import org.junit.Assert.*

// #44 blank-recovery policy. The constants are measured, not chosen: 218 paired
// bench lines give 17 dropped characters and 6,548 genuine gaps. 5 of the 17 are
// "contests" (a confident alternative sits just below blank) and 12 are "confident
// blanks" (blank ≈ 0.999, best alternative ≤ 0.01) where no threshold can help.
class BlankRecoveryTest {

    @Test
    fun disabled_by_default() {
        assertFalse(BlankRecovery.shouldSurface(0.42f, 0.98f, BlankRecovery.DISABLED))
        assertFalse(BlankRecovery.shouldSurface(0.01f, 0.99f, 0f))
    }

    @Test
    fun surfaces_on_a_contest() {
        // measured deletion site: blank 0.42 with a confident alternative
        assertTrue(BlankRecovery.shouldSurface(0.42f, 0.98f))
        assertTrue(BlankRecovery.shouldSurface(0.30f, 0.60f))
    }

    @Test
    fun does_not_surface_a_confident_blank() {
        // the 12-site regime: blank dominates and the alternative is noise
        assertFalse(BlankRecovery.shouldSurface(0.9999f, 0.0f))
        assertFalse(BlankRecovery.shouldSurface(0.9969f, 0.0028f))
        assertFalse(BlankRecovery.shouldSurface(0.9991f, 0.0006f))
    }

    @Test
    fun candidate_must_clear_the_floor() {
        // beats blank, but not confident enough to displace it
        assertFalse(BlankRecovery.shouldSurface(0.2f, 0.4f))
        assertTrue(BlankRecovery.shouldSurface(0.2f, 0.5f))   // exactly at the floor
    }

    @Test
    fun ties_do_not_surface() {
        assertFalse(BlankRecovery.shouldSurface(0.5f, 0.5f))
        assertFalse(BlankRecovery.shouldSurface(0.9f, 0.9f))
    }

    @Test
    fun caller_can_raise_the_floor() {
        assertFalse(BlankRecovery.shouldSurface(0.1f, 0.6f, 0.9f))
        assertTrue(BlankRecovery.shouldSurface(0.1f, 0.95f, 0.9f))
    }
}
