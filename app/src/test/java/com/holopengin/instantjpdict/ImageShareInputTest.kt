package com.holopengin.instantjpdict

import org.junit.Test

import org.junit.Assert.*

/**
 * #57: the image-share ingestion math. The activity that hosts it is view
 * construction (and an Android Context), so it cannot be JVM-tested; the
 * decode bound, the fit-centre placement and the EXIF correction are pure int
 * math, so they are pinned here instead. A camera-app JPEG arriving rotated is
 * the common case this covers, and the service's screenshot path never
 * exercised it.
 */
class ImageShareInputTest {

    @Test
    fun exif_corrections_matchTheStandardOrientationTag() {
        assertEquals(ExifOrientation.Correction(0, false, false), ExifOrientation.correction(ExifOrientation.NORMAL))
        assertEquals(ExifOrientation.Correction(0, true, false), ExifOrientation.correction(ExifOrientation.FLIP_HORIZONTAL))
        assertEquals(ExifOrientation.Correction(180, false, false), ExifOrientation.correction(ExifOrientation.ROTATE_180))
        assertEquals(ExifOrientation.Correction(0, false, true), ExifOrientation.correction(ExifOrientation.FLIP_VERTICAL))
        assertEquals(ExifOrientation.Correction(90, true, false), ExifOrientation.correction(ExifOrientation.TRANSPOSE))
        assertEquals(ExifOrientation.Correction(90, false, false), ExifOrientation.correction(ExifOrientation.ROTATE_90))
        assertEquals(ExifOrientation.Correction(-90, true, false), ExifOrientation.correction(ExifOrientation.TRANSVERSE))
        assertEquals(ExifOrientation.Correction(-90, false, false), ExifOrientation.correction(ExifOrientation.ROTATE_270))
    }

    @Test
    fun exif_absentOrUnknownTagDecodesUpright() {
        assertTrue(ExifOrientation.correction(0).isIdentity)
        assertTrue(ExifOrientation.correction(99).isIdentity)
        assertTrue(ExifOrientation.correction(ExifOrientation.NORMAL).isIdentity)
    }

    @Test
    fun sampleSize_halvesUntilTheLongestSideFits() {
        assertEquals(4, ImageShareFit.sampleSize(4000, 3000, 1000))
        assertEquals(2, ImageShareFit.sampleSize(8000, 1000, 2400))
        assertEquals(1, ImageShareFit.sampleSize(1000, 500, 1000))
        assertEquals(1, ImageShareFit.sampleSize(100, 100, 100))
    }

    @Test
    fun sampleSize_degenerateInputsStaysOne() {
        assertEquals(1, ImageShareFit.sampleSize(0, 100, 100))
        assertEquals(1, ImageShareFit.sampleSize(100, 0, 100))
        assertEquals(1, ImageShareFit.sampleSize(100, 100, 0))
        assertEquals(1, ImageShareFit.sampleSize(-5, -5, -5))
    }

    @Test
    fun fitCenter_letterboxesAndCentres() {
        assertEquals(ImageShareFit.Placement(0, 100, 400, 200), ImageShareFit.fitCenter(100, 50, 400, 400))
        assertEquals(ImageShareFit.Placement(100, 0, 200, 400), ImageShareFit.fitCenter(50, 100, 400, 400))
        assertEquals(ImageShareFit.Placement(0, 100, 200, 200), ImageShareFit.fitCenter(100, 100, 200, 400))
        assertEquals(ImageShareFit.Placement(0, 25, 100, 50), ImageShareFit.fitCenter(200, 100, 100, 100))
    }

    @Test
    fun fitCenter_degenerateSourceStaysTargetSized() {
        assertEquals(ImageShareFit.Placement(0, 0, 40, 40), ImageShareFit.fitCenter(0, 10, 40, 40))
        assertEquals(ImageShareFit.Placement(0, 0, 0, 0), ImageShareFit.fitCenter(10, 10, 0, 0))
    }
}
