package com.holopengin.instantjpdict

import kotlin.math.max
import kotlin.math.min

/**
 * #57: the pure math behind ingesting a shared image, kept free of Android
 * types so the JVM unit tests can pin it. The activity that hosts it is view
 * construction and cannot be unit-tested; this is the part that can.
 */
object ImageShareFit {

    /** Fit-centre placement, in the target bitmap's pixel space. */
    data class Placement(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * Largest power-of-two [android.graphics.BitmapFactory.Options.inSampleSize]
     * that keeps the decoded image's longest side under roughly twice
     * [maxSide]. The overlay renders at the view's own size, so detail finer
     * than that never reaches the screen; bound the decode instead of the
     * display.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 1
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= maxSide) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    /**
     * Scale [srcW] x [srcH] to fit inside [targetW] x [targetH] preserving
     * aspect ratio, and centre it. The overlay's box coordinates are bitmap
     * pixels, so the source has to be laid out onto the exact canvas the view
     * will display; letterboxing is what keeps that mapping 1:1.
     */
    fun fitCenter(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Placement {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) {
            return Placement(0, 0, targetW.coerceAtLeast(0), targetH.coerceAtLeast(0))
        }
        val scale = min(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val width = (srcW * scale).toInt().coerceAtLeast(1)
        val height = (srcH * scale).toInt().coerceAtLeast(1)
        return Placement((targetW - width) / 2, (targetH - height) / 2, width, height)
    }
}

/**
 * #57: EXIF orientation -> the correction to apply after decoding. A camera-app
 * JPEG is routinely stored sideways with the orientation in its EXIF header;
 * the accessibility service never needed this because its input is already an
 * upright screenshot. Pure ints in, a plain description out, so the eight cases
 * are pinned by unit tests rather than by holding a phone the right way up.
 */
object ExifOrientation {

    // Values as defined by the EXIF/TIFF orientation tag.
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    /** Clockwise rotation first, then the mirror. */
    data class Correction(
        val rotationDegrees: Int,
        val flipHorizontal: Boolean,
        val flipVertical: Boolean,
    ) {
        val isIdentity: Boolean
            get() = rotationDegrees == 0 && !flipHorizontal && !flipVertical
    }

    /** Unknown or absent tags decode as already upright. */
    fun correction(orientation: Int): Correction = when (orientation) {
        FLIP_HORIZONTAL -> Correction(0, flipHorizontal = true, flipVertical = false)
        ROTATE_180 -> Correction(180, flipHorizontal = false, flipVertical = false)
        FLIP_VERTICAL -> Correction(0, flipHorizontal = false, flipVertical = true)
        TRANSPOSE -> Correction(90, flipHorizontal = true, flipVertical = false)
        ROTATE_90 -> Correction(90, flipHorizontal = false, flipVertical = false)
        TRANSVERSE -> Correction(-90, flipHorizontal = true, flipVertical = false)
        ROTATE_270 -> Correction(-90, flipHorizontal = false, flipVertical = false)
        else -> Correction(0, flipHorizontal = false, flipVertical = false)
    }
}
