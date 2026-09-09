package com.holopengin.instantjpdict

/**
 * Restore policy for the floating OCR button (refs #60).
 *
 * The system can drop the button's overlay window without telling the
 * service — e.g. the secure camera launched via double-tap power removes
 * overlay windows, and screen-off/user-present only toggles visibility, so
 * a detached button never comes back until the service is re-enabled.
 *
 * This dependency-free predicate captures the re-attach decision so it can
 * be unit-tested on the host; the service applies it in
 * [OcrAccessibilityService.ensureFloatingButton]. Re-attaching preserves the
 * view's visibility flag (a button hidden for screen-off or the capture
 * flow stays hidden) and its layout params (dragged position is kept).
 */
internal fun shouldReattachFloatingButton(
    viewExists: Boolean,
    isAttachedToWindow: Boolean,
    serviceDestroyed: Boolean,
): Boolean = viewExists && !isAttachedToWindow && !serviceDestroyed

/**
 * SCREEN_ON visibility rule (refs #60): re-show the button when the screen
 * comes back on an unlocked phone (power-double-tap camera gesture), but
 * stay hidden on the keyguard — USER_PRESENT handles the unlock path.
 */
internal fun shouldShowOnScreenOn(isKeyguardLocked: Boolean): Boolean = !isKeyguardLocked
