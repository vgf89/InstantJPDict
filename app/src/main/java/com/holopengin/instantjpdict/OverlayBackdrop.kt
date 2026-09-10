package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * Translucent-overlay experiment (refs #64): named tunables plus the pure
 * math behind them, kept free of Android calls so unit tests can pin the
 * behavior on the JVM.
 *
 * Design notes (headless picks — maintainer eyeballs defaults on-device):
 * - Translucency is driven by the screenshot ImageView's alpha (the window
 *   itself was already TRANSLUCENT); the dark scrim stays to keep the
 *   `#FF7777` overlay glyphs readable on a now-busier background.
 * - The status strip is display-side alpha only (pixels of the *display*
 *   bitmap copy + a scrim strip view). Nothing moves: no layout param,
 *   margin, translation, scale or OCR box coordinate is shifted by any
 *   mode, so hit-testing (`isNearCharacter`, LineOverlayView, centering)
 *   is identical across modes by construction. A true bitmap crop (top
 *   rows removed, content shifted up) was rejected: it would need a Y
 *   remap at every box-coordinate site (post-detect offset, recognize
 *   input in original coords, result offset, touch unmapping) — exactly
 *   the desync risk the ticket calls out. CUT (hard transparent band)
 *   achieves the same ambient-awareness look with zero coordinate risk.
 * - The overlay consumes all touches (full-screen overlay window), so the
 *   strip is visual only — no shade interaction passes through. The old
 *   magic 100px top-edge dismiss zone is therefore tied to the strip
 *   itself: a downward swipe starting *inside* the visible strip reads as
 *   a shade-pull and dismisses; drags starting below it never can.
 */
enum class StatusStripMode {
    /** Linear ramp: transparent at the very top, opaque below the strip. */
    FADE,
    /** Hard transparent band over the strip height. */
    CUT,
    /** No strip treatment — uniform screenshot alpha everywhere. */
    SOLID,
}

object OverlayBackdrop {
    /**
     * Screenshot translucency. Axis from the ticket: 1.0 / 0.85 / 0.7.
     * Default 0.85: notifications ghost through while the screenshot still
     * dominates. Nudge in one place; OCR input always stays pristine.
     */
    const val SCREENSHOT_ALPHA = 0.85f

    /** Axis endpoints, kept named so the experiment can flip quickly. */
    const val SCREENSHOT_ALPHA_OPAQUE = 1.0f
    const val SCREENSHOT_ALPHA_DIM = 0.7f

    /**
     * Strip treatment default. FADE is gentlest (a CUT edge can read as a
     * rendering bug); SOLID restores near-previous look with translucency.
     */
    val STATUS_STRIP_MODE = StatusStripMode.FADE

    /** Fallback if the framework `status_bar_height` dimen is missing. */
    const val STATUS_BAR_HEIGHT_FALLBACK_DP = 24

    /** Scrim color below the strip: 0x8C000000 = argb(140, 0, 0, 0), unchanged pre-#64. */
    const val SCRIM_COLOR: Int = -1946157056

    /** Downward travel that turns a strip touch into a dismiss (was inline 50). */
    const val SWIPE_DISMISS_MIN_TRAVEL_PX = 50

    /**
     * Strip alpha multiplier (0 = transparent … 255 = keep) for bitmap row
     * [yPx] given a strip of [stripHeightPx] rows. Pure function of its
     * inputs — the display-bitmap fade and the scrim fade both derive here.
     */
    fun stripAlphaAt(yPx: Int, stripHeightPx: Int, mode: StatusStripMode): Int {
        if (mode == StatusStripMode.SOLID || stripHeightPx <= 0) return 255
        if (yPx < 0) return 0
        if (yPx >= stripHeightPx) return 255
        if (mode == StatusStripMode.CUT) return 0
        // FADE: linear ramp, transparent at the very top row, back to 255
        // on the last strip row.
        return ((yPx + 1) * 255 / stripHeightPx).coerceIn(0, 255)
    }

    /**
     * Whether a touch starting at vertical position [touchY] (overlay-local
     * px, same space as the strip height) may begin a swipe-to-dismiss.
     * Confined to the visible status strip (#64 conflict resolution).
     */
    fun swipeDismissStartsInStrip(touchY: Float, stripHeightPx: Int): Boolean {
        if (stripHeightPx <= 0) return false
        return touchY >= 0f && touchY < stripHeightPx.toFloat()
    }
}

/**
 * Full-screen scrim behind the overlay content (#64). Transparent (CUT) or
 * ramped (FADE) over the status strip so live notification pixels show
 * through; flat [OverlayBackdrop.SCRIM_COLOR] everywhere below it, so
 * pan/zoom reveals and the main area look exactly like the old flat scrim.
 * Pure background — never intercepts touches.
 */
class StatusStripScrimView(context: Context) : View(context) {
    var scrimColor: Int = OverlayBackdrop.SCRIM_COLOR
        set(value) {
            field = value
            invalidate()
        }
    var stripHeightPx: Int = 0
        set(value) {
            field = value
            invalidate()
        }
    var mode: StatusStripMode = OverlayBackdrop.STATUS_STRIP_MODE
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val strip = stripHeightPx.coerceIn(0, height).toFloat()
        if (mode == StatusStripMode.SOLID || strip <= 0f) {
            paint.shader = null
            paint.color = scrimColor
            canvas.drawRect(0f, 0f, w, h, paint)
            return
        }
        if (mode == StatusStripMode.FADE) {
            paint.shader = LinearGradient(
                0f, 0f, 0f, strip,
                Color.TRANSPARENT, scrimColor,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, strip, paint)
        }
        // CUT: draw nothing over the strip — it stays fully see-through.
        paint.shader = null
        paint.color = scrimColor
        canvas.drawRect(0f, strip, w, h, paint)
    }
}
