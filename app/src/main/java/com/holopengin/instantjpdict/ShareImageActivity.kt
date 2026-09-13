package com.holopengin.instantjpdict

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * #57 (Feat — image share intent): receive a single shared image and OCR it
 * through exactly the same surface as the accessibility overlay.
 *
 * The activity does no overlay code of its own. It decodes the shared image
 * (honouring EXIF orientation, which the screenshot path never needed — a
 * camera JPEG arrives rotated) into a bitmap the size of the overlay view, then
 * hands it to [OcrOverlayView], which runs the shared detect/recognise render
 * pass and owns the boxes, lookup, popup and close behaviour.
 *
 * Exit is the same as the overlay's, per the issue: a tap on empty space or the
 * close button. Both reach [OcrOverlayView]'s single close path; when there is
 * no layer left to close it calls [dismissOverlay] and this activity finishes.
 *
 * ACTION_SEND_MULTIPLE is a deliberate later follow-up — only ACTION_SEND is
 * handled here.
 */
class ShareImageActivity : AppCompatActivity(), OcrOverlayView.Host {

    /** Overlay-scoped work: the image decode, the shared environment loads and
     *  the OCR run. Cancelled in [onDestroy]. */
    private val overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var engine: OcrEngine
    private val overlayState = OcrOverlayStateController()
    private var image: Bitmap? = null
    private var overlayView: OcrOverlayView? = null

    // ---- OcrOverlayView.Host ----

    override val bitmap: Bitmap
        get() = image ?: error("overlay requested before the image was decoded")
    override val ocrEngine: OcrEngine get() = engine
    override val controller: OcrOverlayStateController get() = overlayState

    override fun dismissOverlay() {
        finish()
    }

    override fun requestSoftInputResize() {
        // The overlay's manual-input IME handling is a no-op here: an activity
        // already resizes its own window.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /** No floating button to sit under; a fixed corner is all the button needs. */
    override fun closeButtonOrigin(): Pair<Int, Int> = 100 to 100

    /** No floating button to keep in step. */
    override fun onCloseButtonMoved(x: Int, y: Int) {}

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = OcrEngine(this)
        OverlayEnvironment.prepare(this, overlayState, overlayScope)

        // Fill the display like the overlay window does, so the bitmap the view
        // is given maps 1:1 onto its own pixels (the overlay's box coordinates
        // are bitmap pixels; any scale factor between the two would misalign
        // every hit rect).
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val container = FrameLayout(this)
        setContentView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val uri = sharedImageUri(intent)
        if (uri == null) {
            Toast.makeText(this, "No image to read", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Compose at the container's own size, so the image the view receives is
        // exactly the size of the view.
        container.post {
            val width = container.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val height = container.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            overlayScope.launch {
                val composed = withContext(Dispatchers.IO) { composeForScreen(uri, width, height) }
                if (isFinishing || isDestroyed) {
                    composed?.recycle()
                    return@launch
                }
                if (composed == null) {
                    Toast.makeText(this@ShareImageActivity, "Could not read image", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                image = composed
                val view = OcrOverlayView(this@ShareImageActivity, this@ShareImageActivity)
                overlayView = view
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                view.startOcr()
            }
        }
    }

    /**
     * The view tree is our own, but the activity's default back handler would
     * finish on the first press. Route every press through the view's shared
     * close so back closes one layer at a time, exactly like the overlay.
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val view = overlayView
        if (view == null) {
            super.onBackPressed()
        } else {
            view.handleBackKey()
        }
    }

    override fun onDestroy() {
        overlayView?.onClosed()
        overlayView = null
        overlayScope.cancel()
        if (::engine.isInitialized) engine.close()
        val bmp = image
        image = null
        super.onDestroy()
        // The views are gone by now; nothing is drawing this bitmap any more.
        if (bmp != null && !bmp.isRecycled) bmp.recycle()
    }

    // ---- image input ----

    private fun sharedImageUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    /**
     * Decode the shared image, apply its EXIF orientation, and draw it
     * fit-centred onto a [targetW] x [targetH] black canvas. The result is the
     * exact size of the overlay view, so OCR boxes and hit rects line up with
     * what is on screen.
     */
    private fun composeForScreen(uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        val decoded = decodeOriented(uri, targetW, targetH) ?: return null
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val fit = ImageShareFit.fitCenter(decoded.width, decoded.height, targetW, targetH)
        val dst = RectF(
            fit.left.toFloat(), fit.top.toFloat(),
            (fit.left + fit.width).toFloat(), (fit.top + fit.height).toFloat()
        )
        canvas.drawBitmap(decoded, null, dst, paint)
        decoded.recycle()
        return out
    }

    /** Stream-decode via [android.content.ContentResolver]; the shared URI is a
     *  content URI, not a file path, so it is never opened as a file. */
    private fun decodeOriented(uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        val orientation = readExifOrientation(uri)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = ImageShareFit.sampleSize(bounds.outWidth, bounds.outHeight, max(targetW, targetH))

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val correction = ExifOrientation.correction(orientation)
        if (correction.isIdentity) return decoded

        val matrix = Matrix()
        matrix.setRotate(correction.rotationDegrees.toFloat())
        if (correction.flipHorizontal) matrix.postScale(-1f, 1f)
        if (correction.flipVertical) matrix.postScale(1f, -1f)

        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun readExifOrientation(uri: Uri): Int = try {
        contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifOrientation.NORMAL
            )
        } ?: ExifOrientation.NORMAL
    } catch (e: Exception) {
        // Some providers serve streams without EXIF; treat as already upright.
        Log.w("ShareImageActivity", "EXIF orientation unreadable", e)
        ExifOrientation.NORMAL
    }
}
