package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * ncnn Det for #15 — loads det.param/bin (19KB/4.8MB, 217 layers) via JNI,
 * runs DB segmentation 960×960, returns prob map [960*960] float.
 * No LiteRT fallback — ncnn is the only det path.
 */
class DetNcnn private constructor(private val handle: Long) {

    fun infer(floats: FloatArray, w: Int, h: Int): FloatArray? {
        if (floats.size != 3 * w * h) {
            Log.e(TAG, "infer: bad floats ${floats.size} vs ${3*w*h}")
            return null
        }
        val bb = ByteBuffer.allocateDirect(floats.size * 4).order(java.nio.ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(floats)
        return inferNative(handle, bb, w, h)
    }

    /** Legacy alias for older call sites. */
    fun detect(floats: FloatArray, w: Int, h: Int): FloatArray? = infer(floats, w, h)

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
