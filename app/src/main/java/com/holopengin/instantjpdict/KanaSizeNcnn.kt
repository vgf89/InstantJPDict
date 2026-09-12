package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import com.holopengin.instantjpdict.util.KanaSizeEncoder
import java.io.File

/**
 * Kana size model (#44), run through the ncnn this app already links for OCR.
 *
 * A 47,425-param byte-CNN decides whether a confusable kana position is the small or the big
 * form. The converted graph reproduces the model author's ten published logits to 3.8e-06 on the
 * host. Three conversion defects had to be fixed, none of which errored — each produced plausible
 * numbers instead: an int64 initializer written as 8 bytes while the param declared 4, shifting
 * every later weight block by one float; a 3-D conv input that `Convolution1D` reads as one
 * channel; and fp16, which costs ~1.5e-03 against an fp32 reference. The native side pins fp32.
 *
 * Inputs match [KanaSizeEncoder] exactly: a 40-byte window of *context only* (the target
 * character is excluded; the pair identity travels via [base]) and the pair index.
 *
 * ncnn loads from paths, so the param and bin are copied next to the app's files once per process
 * — the same arrangement the OCR models use. Callers should use [load] (idempotent, cached) and
 * [logits] with one entry per position.
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
     * This is the on-device form of the numeric gate that was passed on the host: the same ten
     * text/target/base triples with their expected logits from the artifact's
     * `validation_vectors.json`. It proves the whole path (asset bytes, the param/bin load, JNI
     * marshalling, ARM float behaviour) without adb, and it costs ten positions.
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

        private const val PARAM_ASSET = "kana_size/nb_all.param"
        private const val BIN_ASSET = "kana_size/nb_all.bin"
        private const val PARAM_FILE = "kana_size.param"
        private const val BIN_FILE = "kana_size.bin"

        /**
         * Copy an asset into the app's own files, where ncnn can open it by path.
         *
         * Always rewritten rather than compared by size: a future artifact swap can keep the same
         * byte length, and a stale file would then be loaded silently. It is ~190 KB, once per
         * process, because [load] caches.
         */
        private fun materialise(context: Context, asset: String, name: String): String? {
            return try {
                val data = context.assets.open(asset).use { it.readBytes() }
                if (data.isEmpty()) {
                    Log.e(TAG, "$asset is empty")
                    return null
                }
                val out = File(context.filesDir, name)
                out.writeBytes(data)
                out.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "cannot materialise $asset", e)
                null
            }
        }

        /** Copies an asset and reports its size, for the trace. */
        private fun materialiseTraced(context: Context, asset: String, name: String, trace: (String) -> Unit): String? {
            val path = materialise(context, asset, name) ?: return null
            trace("      ${File(path).length()} bytes -> $path")
            return path
        }

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

            trace("--- kana probe, attempt starting (ncnn path) ---")
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

                trace("step 2: materialise $PARAM_ASSET")
                val param = materialiseTraced(context, PARAM_ASSET, PARAM_FILE, ::trace)
                    ?: return finish("step 2 FAILED: param unavailable")
                trace("step 2 ok")

                trace("step 3: materialise $BIN_ASSET")
                val bin = materialiseTraced(context, BIN_ASSET, BIN_FILE, ::trace)
                    ?: return finish("step 3 FAILED: bin unavailable")
                trace("step 3 ok")

                trace("step 4: native create(param, bin) - loads the graph")
                handle = create(param, bin)
                if (handle == 0L) return finish("step 4 FAILED: ncnn refused the converted model")
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
         * Load once per process from the APK assets. Returns null when the param or bin is missing
         * or ncnn refuses them — callers treat null as "correction unavailable" rather than
         * falling back to anything.
         */
        @Synchronized
        fun load(context: Context): KanaSizeNcnn? {
            instance?.let { return it }
            if (attempted) return null
            attempted = true
            ensureLoaded()
            val param = materialise(context, PARAM_ASSET, PARAM_FILE) ?: return null
            val bin = materialise(context, BIN_ASSET, BIN_FILE) ?: return null
            val h = create(param, bin)
            if (h == 0L) {
                Log.e(TAG, "ncnn refused the converted model")
                return null
            }
            Log.i(TAG, "kana size model loaded (ncnn)")
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

        @JvmStatic private external fun create(paramPath: String, binPath: String): Long
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
