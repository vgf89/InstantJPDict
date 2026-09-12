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
            "kana model OK: worst %.2e over %d vectors".format(worst, n)
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

        /**
         * Run the self-check while tracing every step to `filesDir/kana_probe.log`, flushing
         * before each one, and return the trace.
         *
         * This exists because the app crashed when this was first wired up and the device has no
         * accessible logcat. A line is written *before* the step it describes, so if the process
         * dies inside a native call the trace still shows which call it was - readable on the
         * next launch, since the file accumulates. Otherwise the probe is a plain sequence of
         * guarded steps and cannot itself crash the app for a Java-level reason.
         */
        @Synchronized
        fun probeWithTrace(context: Context): String {
            val sb = StringBuilder()
            val file = java.io.File(context.filesDir, "kana_probe.log")
            fun trace(line: String) {
                sb.append(line).append('\n')
                try {
                    file.appendText(line + "\n")
                } catch (_: Throwable) {
                    // tracing must never be the thing that fails
                }
            }

            trace("--- kana probe, attempt starting ---")
            // Local, so it closes over `trace`: a local named function cannot be passed where a
            // Function1 is expected.
            fun finish(verdict: String): String {
                trace(verdict)
                trace("--- attempt done ---")
                return verdict
            }
            var handle = 0L
            try {
                trace("step 1: System.loadLibrary(ncnn_jni)")
                ensureLoaded()
                trace("step 1 ok")

                trace("step 2: read tables from assets")
                val tables = concatFloats(
                    context,
                    listOf("kana_size/byte_emb.f32", "kana_size/base_emb.f32", "kana_size/pos_block.f32"),
                    TABLES_FLOATS)
                if (tables == null) return finish("step 2 FAILED: tables unavailable (assets missing or short)")
                trace("step 2 ok ($TABLES_FLOATS floats)")

                trace("step 3: read weights.bin")
                val weights = assetBuffer(context, "kana_size/weights.bin", WEIGHTS_FLOATS * 4)
                if (weights == null) return finish("step 3 FAILED: weights unavailable")
                trace("step 3 ok ($WEIGHTS_FLOATS floats)")

                trace("step 4: native create()")
                handle = create(tables, TABLES_FLOATS, weights, WEIGHTS_FLOATS)
                if (handle == 0L) return finish("step 4 FAILED: native create refused the tables")
                trace("step 4 ok (handle $handle)")

                trace("step 5: native batch() over ${VECTORS.size} published vectors")
                val n = VECTORS.size
                val wins = IntArray(n * WINDOW_BYTES)
                val bases = IntArray(n)
                for ((i, v) in VECTORS.withIndex()) {
                    KanaSizeEncoder.window(v.text, v.index).copyInto(wins, i * WINDOW_BYTES)
                    bases[i] = v.base
                }
                val out = batch(handle, wins, bases, n) ?: return finish("step 5 FAILED: batch returned null")
                trace("step 5 ok (${out.size} logits)")

                var worst = 0f
                var worstIdx = -1
                for (i in 0 until n) {
                    val d = kotlin.math.abs(out[i] - VECTORS[i].expected)
                    if (d > worst) { worst = d; worstIdx = i }
                }
                trace("step 6: destroy()")
                destroy(handle)
                handle = 0L
                trace("step 6 ok")
                return finish(if (worst <= 1e-4f) {
                    "kana model OK: worst %.2e over %d vectors".format(worst, n)
                } else {
                    "kana model MISMATCH: case %d off by %.3e (got %.6f, want %.6f)".format(
                        worstIdx, worst, out[worstIdx], VECTORS[worstIdx].expected)
                })
            } catch (t: Throwable) {
                trace("FAILED: ${t.javaClass.name}: ${t.message}")
                return finish("kana model FAILED: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                if (handle != 0L) {
                    try { destroy(handle) } catch (_: Throwable) {}
                }
            }
        }

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
         * The model author's ten published vectors (INTERFACE_NB.md + validation_vectors.json,
         * retrain "nb_all"), logits as produced by onnxruntime fp32. Bases are pair indices in
         * [KanaSizeEncoder.BASE_ORDER]; the last four rows are real corpus lines.
         *
         * These exercise the line-domain clip: four of them carry a full stop or a newline
         * within reach of the target, so an encoder without the clip fails here rather than in
         * the field.
         */
        private val VECTORS = listOf(
            Vector("かれはいっとう。", 4, 5, -0.863643f),
            Vector("きょうはいいてんきですね、まつ。", 14, 5, 0.265265f),
            Vector("みんなでサッカーをするつもりです。", 5, 15, -4.868875f),
            Vector("シーツをあらう。", 2, 15, 9.617793f),
            Vector("きょうのてんきはいいですね。", 1, 8, -6.163191f),
            Vector("キャンプにいく。", 1, 16, -4.094974f),
            Vector("昌仙も、おもわず床几を立って、\n「あッ」\n　と、櫓", 12, 5, -1.286511f),
            Vector("なろうかと……」\n　おえつは、片手に、腕白を抱きな", 12, 5, 4.041704f),
            Vector("は、変化多き世の中にもちょっと例の少ない並ならぬ三", 12, 8, -7.952802f),
            Vector("。\n　そして、ザッザ、ザッザと、草の波を分けて、押", 12, 15, -1.359567f),
        )
    }
}
