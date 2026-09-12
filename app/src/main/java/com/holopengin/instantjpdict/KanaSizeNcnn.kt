package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import com.holopengin.instantjpdict.util.KanaSizeEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kana size model (#44) — the direct implementation, not a converted graph.
 *
 * A 47,425-param byte-CNN decides whether a confusable kana position is the small or the big
 * form. It is not hosted in ncnn: the conversion of this model produced degenerate output twice
 * (a degenerate `Permute 0=1` that never reordered, then a weight stream shifted by a layer
 * removed from the param while its bytes stayed in the bin), both failing silently. The
 * arithmetic is twelve float operations and lives in `kana_size_forward.cpp` instead — no ONNX,
 * no ncnn, no ML runtime — verified against the author's published logits to 2.86e-06.
 *
 * Inputs match [KanaSizeEncoder] exactly: a 40-byte window of *context only* (the target
 * character is excluded; the pair identity travels via [base]) and the pair index.
 *
 * Callers should use [load] (idempotent, cached) and [logits] with one entry per position.
 */
class KanaSizeNcnn private constructor(private val handle: Long) {

    /**
     * Logits for every position, in the same order as the input: [wins] is `n * 40` byte values
     * (each 0..255, from [KanaSizeEncoder.window]) and [bases] is `n` pair indices.
     * Returns null rather than a partial result if the arrays do not match.
     */
    fun logits(wins: IntArray, bases: IntArray): FloatArray? {
        val n = bases.size
        if (n == 0) return FloatArray(0)
        if (wins.size < n * WINDOW_BYTES) {
            Log.e(TAG, "logits: wins=${wins.size} for n=$n positions")
            return null
        }
        return batch(handle, wins, bases, n)
    }

    /** Probability the pair is the *big* form, from the logit. */
    fun probBig(logit: Float): Float = 1f / (1f + kotlin.math.exp(-logit).toFloat())

    /**
     * Run the model author's ten published vectors through this device's own encoder and JNI.
     *
     * This is the on-device form of the gate that was passed on the host: the same ten
     * text/target/base triples with their expected logits. It proves the whole path (asset
     * bytes, JNI marshalling, ARM float behaviour) without adb, and it costs ten positions.
     * Expected values from the author's INTERFACE.md; the last four are real corpus rows.
     */
    fun selfCheck(): String {
        val n = VECTORS.size
        val wins = IntArray(n * WINDOW_BYTES)
        val bases = IntArray(n)
        for ((i, v) in VECTORS.withIndex()) {
            KanaSizeEncoder.window(v.text, v.index).copyInto(wins, i * WINDOW_BYTES)
            bases[i] = v.base
        }
        val out = logits(wins, bases) ?: return "kana model: JNI refused the batch"
        var worst = 0f
        var worstIdx = -1
        for (i in 0 until n) {
            val d = kotlin.math.abs(out[i] - VECTORS[i].expected)
            if (d > worst) { worst = d; worstIdx = i }
        }
        return if (worst <= 1e-4f) {
            "kana model OK: worst %.2e over %d vectors".format(worst)
        } else {
            "kana model MISMATCH: case %d off by %.3e (got %.6f, want %.6f)".format(
                worstIdx, worst, out[worstIdx], VECTORS[worstIdx].expected)
        }
    }

    private data class Vector(val text: String, val index: Int, val base: Int, val expected: Float)

    companion object {
        private const val TAG = "KanaSizeNcnn"
        const val WINDOW_BYTES = KanaSizeEncoder.WINDOW_BYTES   // 40

        private const val TABLES_FLOATS = 256 * 32 + 20 * 16 + 40 * 16
        private const val WEIGHTS_FLOATS =
            64 * 48 * 5 + 64 + 64 * 64 * 3 + 64 + 128 * 80 + 128 + 128 + 1

        @Volatile private var instance: KanaSizeNcnn? = null
        @Volatile private var attempted = false

        /**
         * Load once per process from the APK assets. Returns null when the tables are missing or
         * the native layer refuses them — callers treat null as "correction unavailable" rather
         * than falling back to anything.
         */
        @Synchronized
        fun load(context: Context): KanaSizeNcnn? {
            instance?.let { return it }
            if (attempted) return null
            attempted = true
            ensureLoaded()
            val tables = concatFloats(
                context, listOf("kana_size/byte_emb.f32", "kana_size/base_emb.f32", "kana_size/pos_block.f32"),
                TABLES_FLOATS) ?: return null
            val weights = assetBuffer(context, "kana_size/weights.bin", WEIGHTS_FLOATS * 4) ?: return null
            val h = create(tables, TABLES_FLOATS, weights, WEIGHTS_FLOATS)
            if (h == 0L) {
                Log.e(TAG, "native create refused the tables")
                return null
            }
            Log.i(TAG, "kana size model loaded")
            return KanaSizeNcnn(h).also { instance = it }
        }

        private var libLoaded = false

        private fun ensureLoaded() {
            if (!libLoaded) {
                try {
                    System.loadLibrary("ncnn_jni")
                    libLoaded = true
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "loadLibrary ncnn_jni failed", e)
                }
            }
        }

        /** Raw asset bytes as a direct buffer, with the length asserted. */
        private fun assetBuffer(context: Context, path: String, expectedBytes: Int): ByteBuffer? {
            return try {
                val data = context.assets.open(path).use { it.readBytes() }
                if (data.size != expectedBytes) {
                    Log.e(TAG, "$path: ${data.size} bytes, expected $expectedBytes")
                    return null
                }
                ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()).apply {
                    put(data)
                    position(0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "cannot read asset $path", e)
                null
            }
        }

        /** The three lookup tables concatenated in the order the native code reads them. */
        private fun concatFloats(context: Context, paths: List<String>, totalFloats: Int): ByteBuffer? {
            val bb = ByteBuffer.allocateDirect(totalFloats * 4).order(ByteOrder.nativeOrder())
            var seen = 0
            for (p in paths) {
                val data = try {
                    context.assets.open(p).use { it.readBytes() }
                } catch (e: Exception) {
                    Log.e(TAG, "cannot read asset $p", e)
                    return null
                }
                if (data.size % 4 != 0) {
                    Log.e(TAG, "$p: ${data.size} bytes is not whole floats")
                    return null
                }
                bb.put(data)
                seen += data.size / 4
            }
            if (seen != totalFloats) {
                Log.e(TAG, "tables hold $seen floats, expected $totalFloats")
                return null
            }
            bb.position(0)
            return bb
        }

        @JvmStatic private external fun create(
            tables: ByteBuffer, tablesFloats: Int, weights: ByteBuffer, weightsFloats: Int): Long
        @JvmStatic private external fun destroy(handle: Long)
        @JvmStatic private external fun batch(
            handle: Long, win: IntArray, base: IntArray, n: Int): FloatArray?

        /**
         * The model author's ten published vectors (INTERFACE.md + validation_vectors.json),
         * logits as produced by onnxruntime fp32. Bases are pair indices in
         * [KanaSizeEncoder.BASE_ORDER]; the last four rows are real held-out corpus lines.
         */
        private val VECTORS = listOf(
            Vector("かれはいっとう。", 4, 5, 0.2103874683380127f),
            Vector("きょうはいいてんきですね、まつ。", 14, 5, 1.1135261058807373f),
            Vector("みんなでサッカーをするつもりです。", 5, 15, -5.5936374664f),
            Vector("シーツをあらう。", 2, 15, 11.8762025833f),
            Vector("きょうのてんきはいいですね。", 1, 8, -4.6365633011f),
            Vector("キャンプにいく。", 1, 16, -5.2449283600f),
            Vector("昌仙も、おもわず床几を立って、\n「あッ」\n\u3000と、櫓", 12, 5, -3.3966207504f),
            Vector("なろうかと……」\n\u3000おえつは、片手に、腕白を抱きな", 12, 5, 4.4889340401f),
            Vector("は、変化多き世の中にもちょっと例の少ない並ならぬ三", 12, 8, -7.8713731766f),
            Vector("。\n\u3000そして、ザッザ、ザッザと、草の波を分けて、押", 12, 15, -2.4389185905f),
        )
    }
}
