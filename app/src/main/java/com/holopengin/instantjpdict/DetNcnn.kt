package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * ncnn Det — loads det.param/bin (19KB/4.8MB, 217 layers) via JNI,
 * runs DB segmentation 960×960, returns prob map [960*960] float.
 */
class DetNcnn private constructor(private val handle: Long) {

    fun infer(floats: FloatArray, w: Int, h: Int): FloatArray? {
        if (floats.size != 3 * w * h) {
            Log.e(TAG, "infer: bad floats ${floats.size} vs ${3*w*h}")
            return null
        }
        var bb = tlBuffer.get()
        val needed = floats.size * 4
        if (bb == null || bb.capacity() < needed) {
            bb = ByteBuffer.allocateDirect(needed.coerceAtLeast(3 * 960 * 960 * 4)).order(java.nio.ByteOrder.nativeOrder())
            tlBuffer.set(bb)
        } else {
            bb.clear()
            bb.order(java.nio.ByteOrder.nativeOrder())
        }
        bb.asFloatBuffer().put(floats)
        bb.position(0)
        return inferNative(handle, bb, w, h)
    }

    /** Legacy alias for older call sites. */
    fun detect(floats: FloatArray, w: Int, h: Int): FloatArray? = infer(floats, w, h)

    fun close() {
        destroy(handle)
    }

    companion object {
        private const val TAG = "DetNcnn"
        private val tlBuffer = ThreadLocal<ByteBuffer>()
        private var loaded = false
        fun ensureLoaded() {
            if (!loaded) {
                try {
                    System.loadLibrary("ncnn_jni")
                    loaded = true
                    Log.d(TAG, "libncnn_jni loaded for Det")
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
                context.assets.open("PP-OCRv6_small_ncnn/det.param").use { ins -> paramFile.outputStream().use { ins.copyTo(it) } }
                context.assets.open("PP-OCRv6_small_ncnn/det.bin").use { ins -> binFile.outputStream().use { ins.copyTo(it) } }
            } catch (e: Exception) {
                Log.e(TAG, "copy asset PP-OCRv6_small_ncnn/det.* failed", e)
                return null
            }
            val h = create(paramFile.absolutePath, binFile.absolutePath)
            if (h == 0L) {
                Log.e(TAG, "DetNcnn.create failed")
                return null
            }
            Log.d(TAG, "DetNcnn handle=$h")
            return DetNcnn(h)
        }

        @JvmStatic private external fun create(paramPath: String, binPath: String): Long
        @JvmStatic private external fun destroy(handle: Long)
        @JvmStatic private external fun inferNative(handle: Long, buffer: ByteBuffer, w: Int, h: Int): FloatArray?
    }
}
