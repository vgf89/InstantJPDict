package com.holopengin.instantjpdict

import org.junit.Test

import org.junit.Assert.*

/**
 * Restore policy for the floating OCR button (refs #60).
 */
class FloatingButtonRestoreTest {
    @Test
    fun detachedButton_isRestored() {
        assertTrue(shouldReattachFloatingButton(
            viewExists = true, isAttachedToWindow = false, serviceDestroyed = false))
    }

    @Test
    fun attachedButton_isLeftAlone() {
        assertFalse(shouldReattachFloatingButton(
            viewExists = true, isAttachedToWindow = true, serviceDestroyed = false))
    }

    @Test
    fun missingView_isNotRestored() {
        assertFalse(shouldReattachFloatingButton(
            viewExists = false, isAttachedToWindow = false, serviceDestroyed = false))
    }

    @Test
    fun destroyedService_neverReattaches() {
        assertFalse(shouldReattachFloatingButton(
            viewExists = true, isAttachedToWindow = false, serviceDestroyed = true))
        assertFalse(shouldReattachFloatingButton(
            viewExists = true, isAttachedToWindow = true, serviceDestroyed = true))
    }

    @Test
    fun screenOn_showsOnlyWhenUnlocked() {
        assertTrue(shouldShowOnScreenOn(isKeyguardLocked = false))
        assertFalse(shouldShowOnScreenOn(isKeyguardLocked = true))
    }
}
