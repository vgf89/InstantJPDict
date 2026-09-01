package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * ncnn Det stub for #13 — builds, loads det.param/bin (19KB/4.8MB, 242 nodes, 640×640)
 * via JNI, branched on SharedPreferences ocr_backend (fallback to LiteRT until proven).
 * Real postprocess (poly unclip etc.) lands after the 2× rec proof.
 */
class DetNcnn private constructor(private val handle: Long) {

    /** Stub: returns null so OcrEngine falls back to LiteRT until proven. */
    fun detect(floats: FloatArray, w: Int, h: Int): FloatArray? {
        // Real impl will run ncnn and return [n,4] polys + scores; stub returns null → fallback
        Log.d(TAG, "DetNcnn stub detect called w=$w h=$h floats=${floats.size} — fallback")
        return null
    }

    fun close() {
        destroy(handle)
    }

    companion object {
        private const val TAG = "DetNcnn"
        private var loaded = false
        fun ensureLoaded() {
            if (!loaded) {
                try {
                    System.loadLibrary("ncnn_jni")
                    loaded = true
                    Log.i(TAG, "libncnn_jni loaded for Det")
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "loadLibrary ncnn_jni failed for Det", e)
                }
            }
        }

        fun create(context: Context): DetNcnn? {
            ensureLoaded()
            val cache = File(context.cacheDir, "ncnn")
            cache.mkdirs()
            val paramFile = File(cache, "det.param")
            val binFile = File(cache, "det.bin")
            try {
                context.assets.open("ncnn/det.param").use { ins -> paramFile.outputStream().use { ins.copyTo(it) } }
                context.assets.open("ncnn/det.bin").use { ins -> binFile.outputStream().use { ins.copyTo(it) } }
            } catch (e: Exception) {
                Log.e(TAG, "copy asset ncnn/det.* failed", e)
                return null
            }
            val h = create(paramFile.absolutePath, binFile.absolutePath)
            if (h == 0L) {
                Log.e(TAG, "DetNcnn.create failed")
                return null
            }
            Log.i(TAG, "DetNcnn handle=$h")
            return DetNcnn(h)
        }

        @JvmStatic private external fun create(paramPath: String, binPath: String): Long
        @JvmStatic private external fun destroy(handle: Long)
        @JvmStatic private external fun inferNative(handle: Long, buffer: ByteBuffer, w: Int, h: Int): FloatArray?
    }
}
