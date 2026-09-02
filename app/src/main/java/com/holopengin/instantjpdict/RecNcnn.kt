package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ncnn Rec wrapper — all buckets w64/128/256/480 for #13 (w64 for #12).
 * Loads 179-layer pnnx→ncnn 10.56 MB bin per bucket via JNI.
 */
class RecNcnn private constructor(private val handle: Long, val targetW: Int) {

    fun infer(floats: FloatArray, w: Int, h: Int = 48): FloatArray? {
        if (floats.size != 3 * 48 * w) {
            Log.e(TAG, "infer: bad floats ${floats.size} vs ${3*48*w}")
            return null
        }
        var bb = tlBuffer.get()
        val needed = floats.size * 4
        if (bb == null || bb.capacity() < needed) {
            bb = ByteBuffer.allocateDirect(needed.coerceAtLeast(3 * 48 * 480 * 4)).order(ByteOrder.nativeOrder())
            tlBuffer.set(bb)
        } else {
            bb.clear()
            bb.order(ByteOrder.nativeOrder())
        }
        bb.asFloatBuffer().put(floats)
        bb.position(0)
        val out = inferNative(handle, bb, w, h) ?: return null
        return out
    }

    fun close() {
        destroy(handle)
    }

    companion object {
        private const val TAG = "RecNcnn"
        private val tlBuffer = ThreadLocal<ByteBuffer>()
        private var loaded = false
        fun ensureLoaded() {
            if (!loaded) {
                try {
                    System.loadLibrary("ncnn_jni")
                    loaded = true
                    Log.i(TAG, "libncnn_jni loaded")
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "loadLibrary ncnn_jni failed", e)
                }
            }
        }

        fun create(context: Context, targetW: Int = 64): RecNcnn? {
            ensureLoaded()
            val cache = File(context.cacheDir, "ncnn")
            cache.mkdirs()
            val paramName = "rec_w${targetW}.param"
            val binName = "rec_w${targetW}.bin"
            val paramFile = File(cache, paramName)
            val binFile = File(cache, binName)
            try {
                context.assets.open("PP-OCRv6_small_ncnn/$paramName").use { ins -> paramFile.outputStream().use { ins.copyTo(it) } }
                context.assets.open("PP-OCRv6_small_ncnn/$binName").use { ins -> binFile.outputStream().use { ins.copyTo(it) } }
            } catch (e: Exception) {
                Log.e(TAG, "copy asset PP-OCRv6_small_ncnn/$paramName failed", e)
                return null
            }
            val h = create(paramFile.absolutePath, binFile.absolutePath, targetW)
            if (h == 0L) {
                Log.e(TAG, "RecNcnn.create failed for W=$targetW")
                return null
            }
            Log.i(TAG, "RecNcnn W=$targetW handle=$h")
            return RecNcnn(h, targetW)
        }

        @JvmStatic private external fun create(paramPath: String, binPath: String, targetW: Int): Long
        @JvmStatic private external fun destroy(handle: Long)
        @JvmStatic private external fun inferNative(handle: Long, buffer: ByteBuffer, w: Int, h: Int): FloatArray?
    }
}
