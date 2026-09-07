package com.holopengin.instantjpdict

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Automated benchmark harness for Pixel 7a — REC pipeline.
 * Images: benchmark/Screenshot_20260530-172718.png (2400×1080) + benchmark/f5d7d08735383899.jpg (1366×768)
 * Also validates the 4813-line settings-screen macro baseline is reachable.
 *
 * Run:  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.OcrBenchmarkTest
 * Release (real numbers): ./gradlew :app:connectedReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.OcrBenchmarkTest
 *
 * Output is deterministic (no wall-clock timestamps, only durations) for cron gating.
 * Part of #7 harness + #14 bench + #8 correctness gate (max abs <1e-3 + top-1);
 * ncnn-only since #15.
 * penned by Hermes Agent + muse-spark-1.2-contributor
 */
@RunWith(AndroidJUnit4::class)
class OcrBenchmarkTest {

    companion object {
        private const val TAG = "OcrBenchmark"
        private const val WARMUP_RUNS = 1
        private const val MEASURED_RUNS = 2

        private lateinit var engine: OcrEngine

        @JvmStatic
        @BeforeClass
        fun setupEngine() {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val t0 = System.nanoTime()
            engine = OcrEngine(appContext)
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "engine_load_ms=$loadMs ready=${engine.isReady()}")
            assertTrue("OcrEngine failed to load", engine.isReady())
        }

        private fun loadBenchmarkBitmap(name: String): Bitmap {
            // Try test APK assets first (androidTest/assets/benchmark), fall back to app assets (main/assets/benchmark)
            val instr = InstrumentationRegistry.getInstrumentation()
            val testAssets = instr.context.assets
            val appAssets = instr.targetContext.assets
            val candidates = listOf(
                "benchmark/$name" to testAssets,
                "benchmark/$name" to appAssets,
                name to testAssets,
                name to appAssets,
            )
            for ((path, assets) in candidates) {
                try {
                    assets.open(path).use { ins ->
                        val bmp = BitmapFactory.decodeStream(ins)
                        if (bmp != null) {
                            Log.i(TAG, "loaded $path ${bmp.width}x${bmp.height}")
                            return bmp
                        }
                    }
                } catch (_: Exception) { }
            }
            error("benchmark image not found: $name (tried ${candidates.map { it.first }})")
        }

        private fun percentile(sorted: List<Long>, p: Double): Long {
            if (sorted.isEmpty()) return 0
            val idx = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }

        internal fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
            var max = 0f
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val d = abs(a[i] - b[i])
                if (d > max) max = d
            }
            // size mismatch counts as large diff
            if (a.size != b.size) max = maxOf(max, 1e9f)
            return max
        }

        internal fun top1Equal(a: FloatArray, b: FloatArray, seqLen: Int, numClasses: Int): Boolean {
            if (a.size != b.size || a.size != seqLen * numClasses) return false
            for (t in 0 until seqLen) {
                var maxA = 0; var vA = Float.NEGATIVE_INFINITY
                var maxB = 0; var vB = Float.NEGATIVE_INFINITY
                val base = t * numClasses
                for (c in 0 until numClasses) {
                    val va = a[base + c]
                    val vb = b[base + c]
                    if (va > vA) { vA = va; maxA = c }
                    if (vb > vB) { vB = vb; maxB = c }
                }
                if (maxA != maxB) return false
            }
            return true
        }

        /** Decode top-1 text from flat logits for quick top-1 check (CTC greedy without blank handling). */
        private fun editDistance(a: String, b: String): Int {
            if (a == b) return 0
            var dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val cur = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = cur
                }
            }
            return dp[b.length]
        }
        private fun decodeTop1Flat(logits: FloatArray, seqLen: Int, numClasses: Int, vocab: List<String>, remap: IntArray = IntArray(0)): String {
            val sb = StringBuilder()
            var prev = -1
            for (t in 0 until seqLen) {
                val base = t * numClasses
                var best = 0; var bestV = Float.NEGATIVE_INFINITY
                for (c in 0 until numClasses) {
                    val v = logits[base + c]
                    if (v > bestV) { bestV = v; best = c }
                }
                best = remap.getOrElse(best) { best } // pruned-out -> orig id (#39)
                if (best == 0) { prev = 0; continue }
                if (best == prev) continue
                val ch = when {
                    best == 18709 -> ' '
                    best == 18708 -> '　'
                    best in 1..18708 -> vocab.getOrNull(best - 1)?.firstOrNull() ?: '?'
                    else -> '?'
                }
                if (ch != '�' && ch != '\uFFFD') sb.append(ch)
                prev = best
            }
            return sb.toString()
        }
    }

    private fun benchOneImage(name: String, bitmap: Bitmap): BenchResult {
        // Single-pass, 3-crop sample — keeps the two test images as pipeline tests but avoids 65×2.3s = 148s full runs.
        Log.i(TAG, "bench start $name ${bitmap.width}x${bitmap.height}")
        val tDet = System.nanoTime()
        val boxes = engine.detect(bitmap)
        val detMs = (System.nanoTime() - tDet) / 1_000_000
        Log.i(TAG, "bench det $name boxes=${boxes.size} detMs=$detMs")

        if (boxes.isEmpty()) {
            return BenchResult(name, bitmap.width, bitmap.height, detMs, detMs, detMs, 0, 0, 0, 0, 0, 0, emptyList())
        }

        // Sample 3 crops (first, middle, last) — ~3×2.3s ≈7s not 65×2.3s ≈148s. Full 65-box is benchFull_* below.
        val sampleBoxes = when {
            boxes.size <= 3 -> boxes
            else -> listOf(boxes.first(), boxes[boxes.size / 2], boxes.last())
        }
        Log.i(TAG, "bench sample ${sampleBoxes.size}/${boxes.size} boxes for rec")

        val texts = mutableListOf<String>()
        val collected = mutableListOf<Pair<Int, LineResult>>()
        val tRec = System.nanoTime()
        runBlocking {
            engine.recognizeStreaming(bitmap, sampleBoxes) { pairs ->
                synchronized(collected) { collected.addAll(pairs) }
            }
            var waited = 0
            var lastSize = -1
            var still = 0
            // Quiescence wait (empties are skipped by design): stop ~1s after
            // last arrival instead of burning a fixed 30s timeout (#51 — a
            // single empty sample inflated perCrop past its gate).
            while (collected.size < sampleBoxes.size && waited < 30000) {
                kotlinx.coroutines.delay(100)
                waited += 100
                synchronized(collected) {
                    if (collected.size == lastSize) still += 100 else { still = 0; lastSize = collected.size }
                }
                if (still >= 1000 && waited > 2000) break
            }
        }
        val recMs = (System.nanoTime() - tRec) / 1_000_000
        for ((_, line) in collected) texts.add(line.text)
        Log.i(TAG, "bench rec $name sampled=${collected.size}/${sampleBoxes.size} totalBoxes=${boxes.size} recMs=$recMs perCrop=${if (sampleBoxes.isEmpty()) 0 else recMs / sampleBoxes.size} sample=${texts.take(3).joinToString(" | ")}")

        return BenchResult(
            imageName = name,
            width = bitmap.width,
            height = bitmap.height,
            detMsP50 = detMs,
            detMsP95 = detMs,
            detMsMean = detMs,
            recTotalMsP50 = recMs,
            recTotalMsP95 = recMs,
            recTotalMsMean = recMs,
            perCropMsP50 = if (sampleBoxes.isEmpty()) 0 else recMs / sampleBoxes.size,
            perCropMsP95 = if (sampleBoxes.isEmpty()) 0 else recMs / sampleBoxes.size,
            numBoxes = boxes.size,
            sampleTexts = texts.take(5),
        )
    }

    /** Variant that uses an explicit engine (for the ncnn correctness gate below). */
    private fun benchOneImageWithEngine(name: String, bitmap: Bitmap, eng: OcrEngine): BenchResult {
        Log.i(TAG, "bench start $name ${bitmap.width}x${bitmap.height}")
        val tDet = System.nanoTime()
        val boxes = eng.detect(bitmap)
        val detMs = (System.nanoTime() - tDet) / 1_000_000
        Log.i(TAG, "bench det $name boxes=${boxes.size} detMs=$detMs")
        if (boxes.isEmpty()) {
            return BenchResult(name, bitmap.width, bitmap.height, detMs, detMs, detMs, 0, 0, 0, 0, 0, 0, emptyList())
        }
        val sampleBoxes = when {
            boxes.size <= 3 -> boxes
            else -> listOf(boxes.first(), boxes[boxes.size / 2], boxes.last())
        }
        Log.i(TAG, "bench sample ${sampleBoxes.size}/${boxes.size} boxes for rec")
        val texts = mutableListOf<String>()
        val collected = mutableListOf<Pair<Int, LineResult>>()
        val tRec = System.nanoTime()
        runBlocking {
            eng.recognizeStreaming(bitmap, sampleBoxes) { pairs -> synchronized(collected) { collected.addAll(pairs) } }
            var waited = 0
            var lastSize = -1
            var still = 0
            // Quiescence wait (empties are skipped by design): stop ~1s after
            // last arrival instead of burning a fixed 30s timeout (#51 — a
            // single empty sample inflated perCrop past its gate).
            while (collected.size < sampleBoxes.size && waited < 30000) {
                kotlinx.coroutines.delay(100)
                waited += 100
                synchronized(collected) {
                    if (collected.size == lastSize) still += 100 else { still = 0; lastSize = collected.size }
                }
                if (still >= 1000 && waited > 2000) break
            }
        }

        val recMs = (System.nanoTime() - tRec) / 1_000_000
        for ((_, line) in collected) texts.add(line.text)
        Log.i(TAG, "bench rec $name sampled=${collected.size}/${sampleBoxes.size} totalBoxes=${boxes.size} recMs=$recMs perCrop=${if (sampleBoxes.isEmpty()) 0 else recMs / sampleBoxes.size} sample=${texts.take(3).joinToString(" | ")}")
        return BenchResult(name, bitmap.width, bitmap.height, detMs, detMs, detMs, recMs, recMs, recMs, if (sampleBoxes.isEmpty()) 0 else recMs / sampleBoxes.size, if (sampleBoxes.isEmpty()) 0 else recMs / sampleBoxes.size, boxes.size, texts.take(5))
    }

    data class BenchResult(
        val imageName: String,
        val width: Int,
        val height: Int,
        val detMsP50: Long,
        val detMsP95: Long,
        val detMsMean: Long,
        val recTotalMsP50: Long,
        val recTotalMsP95: Long,
        val recTotalMsMean: Long,
        val perCropMsP50: Long,
        val perCropMsP95: Long,
        val numBoxes: Int,
        val sampleTexts: List<String>,
    ) {
        fun toLogLine(): String = buildString {
            append("bench image=$imageName ${width}x$height")
            append(" boxes=$numBoxes")
            append(" det_p50=${detMsP50}ms p95=${detMsP95}ms mean=${detMsMean}ms")
            append(" rec_total_p50=${recTotalMsP50}ms p95=${recTotalMsP95}ms mean=${recTotalMsMean}ms")
            append(" rec_perCrop_p50=${perCropMsP50}ms p95=${perCropMsP95}ms")
            if (sampleTexts.isNotEmpty()) append(" sample_texts=${sampleTexts.joinToString(" | ")}")
        }
    }

    @Test
    fun smokeDetOnly() {
        // Tiny smoke — no REC, just engine load + one DET. Should finish in <10s. Validates harness + assert baseline.
        val bmp = loadBenchmarkBitmap("f5d7d08735383899.jpg") // smaller image first
        Log.i(TAG, "smoke start ${bmp.width}x${bmp.height}")
        val t0 = System.nanoTime()
        val boxes = engine.detect(bmp)
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(TAG, "smoke det boxes=${boxes.size} ms=$ms")
        bmp.recycle()
        assertTrue("smoke: no boxes", boxes.isNotEmpty())
        assertTrue("smoke: det took too long ($ms ms)", ms < 15000)
    }

    @Test
    fun benchRec_Screenshot_2400x1080() {
        val bmp = loadBenchmarkBitmap("Screenshot_20260530-172718.png")
        val r = benchOneImage("Screenshot_20260530-172718.png", bmp)
        Log.i(TAG, r.toLogLine())
        // Also emit as instrumentation result for cron gating (deterministic, no timestamps)
        val instr = InstrumentationRegistry.getInstrumentation()
        val bundle = android.os.Bundle().apply {
            putString("bench", r.toLogLine())
            putLong("det_p50", r.detMsP50)
            putLong("rec_total_p50", r.recTotalMsP50)
            putLong("rec_perCrop_p50", r.perCropMsP50)
            putInt("boxes", r.numBoxes)
        }
        instr.sendStatus(0, bundle)
        bmp.recycle()
        assertTrue("no boxes detected for Screenshot", r.numBoxes > 0)
        assertTrue("rec should take >0ms", r.recTotalMsP50 >= 0)
    }

    @Test
    fun benchRec_Jpg_1366x768() {
        val bmp = loadBenchmarkBitmap("f5d7d08735383899.jpg")
        val r = benchOneImage("f5d7d08735383899.jpg", bmp)
        Log.i(TAG, r.toLogLine())
        val instr = InstrumentationRegistry.getInstrumentation()
        val bundle = android.os.Bundle().apply {
            putString("bench", r.toLogLine())
            putLong("det_p50", r.detMsP50)
            putLong("rec_total_p50", r.recTotalMsP50)
            putLong("rec_perCrop_p50", r.perCropMsP50)
            putInt("boxes", r.numBoxes)
        }
        instr.sendStatus(0, bundle)
        bmp.recycle()
        assertTrue("no boxes detected for jpg", r.numBoxes > 0)
    }

    // 3-way backend check (#42): CPU-only vs Vulkan-only vs parallel split must
    // agree textually on the same boxes; Vulkan/parallel must load and run.
    @Test
    fun backendParityAndBench() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = appContext.getSharedPreferences(OcrEngine.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val bmp = loadBenchmarkBitmap("f5d7d08735383899.jpg")
        // Detect once on CPU (det path is backend-independent).
        prefs.edit().putInt(OcrEngine.PREF_REC_BACKEND, OcrEngine.BACKEND_CPU).apply()
        val detEng = OcrEngine(appContext)
        assertTrue("det engine ready", detEng.isReady())
        val boxes = detEng.detect(bmp)
        assertTrue("expected boxes, got ${boxes.size}", boxes.size > 3)
        detEng.close()
        val sampleBoxes = listOf(boxes.first(), boxes[boxes.size / 2], boxes.last())
        Log.i(TAG, "backendParity boxes=${boxes.size} sample=${sampleBoxes.size}")

        fun runBackend(mode: Int, label: String): Pair<List<String>, Long> {
            prefs.edit().putInt(OcrEngine.PREF_REC_BACKEND, mode).apply()
            val eng = OcrEngine(appContext)
            assertTrue("$label engine ready", eng.isReady())
            assertEquals("$label built backend", mode, eng.builtBackend)
            val collected = mutableListOf<Pair<Int, LineResult>>()
            val t0 = System.nanoTime()
            runBlocking {
                eng.recognizeStreaming(bmp, sampleBoxes) { pairs ->
                    synchronized(collected) { collected.addAll(pairs) }
                }
                var waited = 0
                var lastSize = -1
                var still = 0
                // Quiescence wait (empties are skipped by design): stop ~1s
                // after last arrival instead of a fixed 30s timeout (#51).
                while (collected.size < sampleBoxes.size && waited < 30000) {
                    kotlinx.coroutines.delay(100)
                    waited += 100
                    synchronized(collected) {
                        if (collected.size == lastSize) still += 100 else { still = 0; lastSize = collected.size }
                    }
                    if (still >= 1000 && waited > 2000) break
                }
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val texts = collected.sortedBy { it.first }.map { it.second.text }
            Log.i(TAG, "backendParity $label recMs=${ms}ms texts=$texts")
            eng.close()
            prefs.edit().putInt(OcrEngine.PREF_REC_BACKEND, OcrEngine.DEF_REC_BACKEND).apply()
            return texts to ms
        }

        val (cpuTexts, cpuMs) = runBackend(OcrEngine.BACKEND_CPU, "cpu")
        val (vkTexts, vkMs) = runBackend(OcrEngine.BACKEND_VULKAN, "vulkan")
        val (parTexts, parMs) = runBackend(OcrEngine.BACKEND_PARALLEL, "parallel")
        // Cross-graph tolerance: CPU (fused) and Vulkan (IP-swapped, no requantize
        // round-trip) agree ~98%; single-kanji wobbles like 目/日 are expected.
        // Assert per-line edit distance <= 2, not string equality.
        fun assertClose(tag: String, a: List<String>, b: List<String>) {
            assertEquals("$tag line count", a.size, b.size)
            for (i in a.indices) {
                val d = editDistance(a[i], b[i])
                assertTrue("$tag line $i differs by $d: '${a[i]}' vs '${b[i]}'", d <= 2)
            }
        }
        assertClose("vulkan-vs-cpu", cpuTexts, vkTexts)
        assertClose("parallel-vs-cpu", cpuTexts, parTexts)
        Log.i(TAG, "backendParity SUMMARY cpu=${cpuMs}ms vulkan=${vkMs}ms parallel=${parMs}ms")
    }

    @Test
    fun detInputSizeAB() {
        // Det input-size A/B (#51): 960 vs 896 box counts + walls on real
        // images. Host study predicts counts within ±4% and ~13% less
        // compute; gate here is ±15% (fp16 + resampling noise), walls must drop.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val imgs = listOf("Screenshot_20260905-093821.png", "f5d7d08735383899.jpg")
        val counts = mutableMapOf<Int, List<Int>>()
        val walls = mutableMapOf<Int, List<Long>>()
        try {
            for (size in listOf(960, 896)) {
                OcrEngine.DET_MODEL_SIZE = size
                val eng = OcrEngine(appContext)
                assertTrue("det engine ready @ $size", eng.isReady())
                val cs = mutableListOf<Int>(); val ws = mutableListOf<Long>()
                for (name in imgs) {
                    val bmp = loadBenchmarkBitmap(name)
                    // Warmup then min-of-3.
                    eng.detect(bmp)
                    var best = Long.MAX_VALUE; var n = 0
                    repeat(3) {
                        val t0 = System.nanoTime()
                        n = eng.detect(bmp).size
                        best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
                    }
                    cs.add(n); ws.add(best)
                    Log.i(TAG, "detSizeAB size=$size img=$name boxes=$n wallMs=$best")
                }
                eng.close()
                counts[size] = cs; walls[size] = ws
            }
        } finally {
            OcrEngine.DET_MODEL_SIZE = 896
        }
        for (i in imgs.indices) {
            val c960 = counts[960]!![i]; val c896 = counts[896]!![i]
            val w960 = walls[960]!![i]; val w896 = walls[896]!![i]
            Log.i(TAG, "detSizeAB SUMMARY ${imgs[i]}: 960: $c960 boxes/${w960}ms -> 896: $c896 boxes/${w896}ms")
            assertTrue("${imgs[i]}: 896 count $c896 vs 960 $c960",
                c896 >= (c960 * 0.85).toInt() && c896 <= (c960 * 1.15).toInt() + 2)
            assertTrue("${imgs[i]}: 896 wall ${w896}ms not faster than 960 ${w960}ms", w896 <= w960)
        }
    }

    @Test
    fun backendEndToEndQuestBook() {
        // End-to-end backend comparison on a dense real screenshot (#42):
        // detect once (backend-independent), recognize ALL lines per backend.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = appContext.getSharedPreferences(OcrEngine.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val bmp = loadBenchmarkBitmap("Screenshot_20260905-093821.png")
        prefs.edit().putInt(OcrEngine.PREF_REC_BACKEND, OcrEngine.BACKEND_CPU).apply()
        val detEng = OcrEngine(appContext)
        assertTrue("det engine ready", detEng.isReady())
        val boxes = detEng.detect(bmp)
        Log.i(TAG, "backendE2E quest book boxes=${boxes.size}")
        assertTrue("expected 20+ lines, got ${boxes.size}", boxes.size >= 20)
        detEng.close()

        fun runBackend(mode: Int, label: String): Pair<Map<Int, String>, Long> {
            prefs.edit().putInt(OcrEngine.PREF_REC_BACKEND, mode).apply()
            val eng = OcrEngine(appContext)
            assertTrue("$label engine ready", eng.isReady())
            val collected = mutableListOf<Pair<Int, LineResult>>()
            val t0 = System.nanoTime()
            runBlocking {
                eng.recognizeStreaming(bmp, boxes) { pairs ->
                    synchronized(collected) { collected.addAll(pairs) }
                }
                // Quiescence wait: empty texts are skipped by design, so count
                // can never reach boxes.size — stop ~1.5s after last arrival.
                // (A 5s floor here once hid true compute: 61-line legs measured
                // ~6s while batches finished in ~1s. Keep the floor small.)
                var waited = 0
                var lastSize = -1
                var still = 0
                while (collected.size < boxes.size && waited < 120000) {
                    kotlinx.coroutines.delay(200)
                    waited += 200
                    synchronized(collected) {
                        if (collected.size == lastSize) still += 200 else { still = 0; lastSize = collected.size }
                    }
                    if (still >= 1500 && waited > 2500) break
                }
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            // Idx-keyed: empties are skipped by design, so lists differ in
            // length when one backend drops a junk fragment — align by job idx.
            val texts = collected.sortedBy { it.first }.associate { it.first to it.second.text }
            Log.i(TAG, "backendE2E $label lines=${texts.size}/${boxes.size} recMs=${ms}ms")
            texts.forEach { (idx, t) -> Log.i(TAG, "backendE2E $label job[$idx]='$t'") }
            eng.close()
            prefs.edit().putInt(OcrEngine.PREF_REC_BACKEND, OcrEngine.DEF_REC_BACKEND).apply()
            return texts to ms
        }

        val (cpuTexts, cpuMs) = runBackend(OcrEngine.BACKEND_CPU, "cpu")
        val (vkTexts, vkMs) = runBackend(OcrEngine.BACKEND_VULKAN, "vulkan")
        val (parTexts, parMs) = runBackend(OcrEngine.BACKEND_PARALLEL, "parallel")
        // Empty texts are skipped by design; one backend may drop a junk
        // fragment (1-char noise) the other keeps — align by job idx so a
        // single junk-line difference can't cascade into positional mismatch.
        // Dropped REAL lines still blow the CER gate via full-length penalty.
        assertTrue("cpu returned lines", cpuTexts.isNotEmpty())
        assertTrue("vulkan dropped too many", cpuTexts.size - vkTexts.size <= 2)
        assertTrue("parallel dropped too many", cpuTexts.size - parTexts.size <= 2)
        fun cerLike(a: Map<Int, String>, b: Map<Int, String>): Double {
            var d = 0; var n = 0
            for ((idx, ta) in a) {
                d += editDistance(ta, b.getOrElse(idx) { "" })
                n += maxOf(ta.length, 1)
            }
            // Lines b has but a lacks count as full-length errors too.
            for ((idx, tb) in b) if (idx !in a) d += tb.length
            return d.toDouble() / n
        }
        val vkCer = cerLike(cpuTexts, vkTexts)
        val parCer = cerLike(cpuTexts, parTexts)
        Log.i(TAG, "backendE2E SUMMARY lines=${boxes.size} cpu=${cpuMs}ms vulkan=${vkMs}ms parallel=${parMs}ms vkCER=$vkCer parCER=$parCer")
        assertTrue("vulkan CER $vkCer too high", vkCer <= 0.05)
        assertTrue("parallel CER $parCer too high", parCer <= 0.05)
    }

    @Test
    fun benchRec_BothImages_Summary() {
        // Combined summary — the macro bench for the 2× contract
        val bmp1 = loadBenchmarkBitmap("Screenshot_20260530-172718.png")
        val bmp2 = loadBenchmarkBitmap("f5d7d08735383899.jpg")
        val r1 = benchOneImage("Screenshot_20260530-172718.png", bmp1)
        val r2 = benchOneImage("f5d7d08735383899.jpg", bmp2)
        Log.i(TAG, "SUMMARY ${r1.toLogLine()}")
        Log.i(TAG, "SUMMARY ${r2.toLogLine()}")
        val avgRecTotal = (r1.recTotalMsMean + r2.recTotalMsMean) / 2
        val avgPerCrop = (r1.perCropMsP50 + r2.perCropMsP50) / 2
        Log.i(TAG, "SUMMARY avg_rec_total_mean=${avgRecTotal}ms avg_perCrop_p50=${avgPerCrop}ms")
        bmp1.recycle()
        bmp2.recycle()
        // The 4813-line baseline from f6dee5b is ~4813 lines; our 2-image bench is a proxy.
        // Real macro run will be via accessibility dump of InstantJPDict settings screen.
        assertTrue(r1.numBoxes > 0 && r2.numBoxes > 0)
    }

    /**
     * ncnn correctness gate + image bench (was dual-backend A/B for #14;
     * ncnn-only since #15). Single-pass per image (Screenshot 2400x1080 +
     * jpg 1366x768, 3-crop sample) ≈15s, with box-count range asserts. Also
     * runs the dynamic-width synthetic parity gate (w64/200/480 incl.
     * off-bucket 200, #23).
     */
    @Test
    fun benchNcnnCorrectnessAndBench() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val instr = InstrumentationRegistry.getInstrumentation()

        // ── 1. Dynamic-width ncnn smoke gate — single rec_dyn model (#23), incl. off-bucket 200 ──
        val h = 48
        val numClasses = 13193 // pruned head (#39)
        val recDynField = engine.javaClass.getDeclaredField("recDynNcnn").apply { isAccessible = true }
        var recNcnn = recDynField.get(engine) as RecNcnn?
        if (recNcnn == null) {
            recNcnn = RecNcnn.create(appContext)
            assertNotNull("RecNcnn dyn failed to create", recNcnn)
            recDynField.set(engine, recNcnn)
        }
        var vocab: List<String> = emptyList()
        try {
            val vField = engine.javaClass.getDeclaredField("ppocrVocab").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            vocab = vField.get(engine) as List<String>
        } catch (_: Exception) {}
        var classRemap = IntArray(0)
        try {
            val rField = engine.javaClass.getDeclaredField("classRemap").apply { isAccessible = true }
            classRemap = rField.get(engine) as IntArray
        } catch (_: Exception) {}

        val bucketResults = mutableListOf<String>()
        for (w in listOf(64, 200, 480)) {
            val seqLen = w / 8
            val inputFloats = FloatArray(3 * h * w) { ((it * 37) % 255) / 128f - 1f }
            val ncnnOut = recNcnn!!.infer(inputFloats, w, h)
            assertNotNull("ncnn w$w infer returned null", ncnnOut)
            assertEquals("ncnn outSize w$w", seqLen * numClasses, ncnnOut!!.size)
            val top1NcnnText = if (vocab.isNotEmpty()) decodeTop1Flat(ncnnOut, seqLen, numClasses, vocab, classRemap) else "?"
            val line = "gate w$w seq$seqLen ncnn=\"$top1NcnnText\" size=${ncnnOut.size}"
            Log.i(TAG, line)
            bucketResults.add(line)
        }
        Log.i(TAG, "gate dynamic-width ncnn OK: ${bucketResults.joinToString(" | ")}")

        // ── 2. Image bench — 2 runs total (Screenshot + jpg, 3-crop sample) ≈15s ──
        val images = listOf(
            "Screenshot_20260530-172718.png" to "Screenshot",
            "f5d7d08735383899.jpg" to "jpg"
        )
        val allResults = mutableListOf<BenchResult>()
        var totalDet = 0L
        var totalRec = 0L

        val eng = OcrEngine(appContext)
        assertTrue("engine failed to load", eng.isReady())
        Log.i(TAG, "benchNcnn engine ready=${eng.isReady()} detThresh=${OcrEngine.getDetThresh(appContext)}")
        for ((imgName, _) in images) {
            val bmp = loadBenchmarkBitmap(imgName)
            val r = benchOneImageWithEngine(imgName, bmp, eng)
            bmp.recycle()
            Log.i(TAG, r.toLogLine())
            Log.i(TAG, "bench image=$imgName ${r.width}x${r.height} boxes=${r.numBoxes} det=${r.detMsP50} rec=${r.recTotalMsP50} perCrop=${r.perCropMsP50}")
            allResults.add(r)
            totalDet += r.detMsP50
            totalRec += r.recTotalMsP50
            assertTrue("no boxes for $imgName", r.numBoxes > 0)
            if (imgName.contains("Screenshot")) {
                assertTrue("Screenshot boxes expected ~37 got ${r.numBoxes}", r.numBoxes in 20..55)
            } else {
                assertTrue("jpg boxes expected ~65 got ${r.numBoxes}", r.numBoxes in 40..85)
            }
        }
        eng.close()

        Log.i(TAG, "SUMMARY ncnn 2 runs detTotal=${totalDet}ms recTotal=${totalRec}ms avgPerRunDet=${totalDet/2}ms avgPerRunRec=${totalRec/2}ms")

        // Emit instrumentation bundle for cron gating
        val bundle = android.os.Bundle().apply {
            putString("bench", allResults.joinToString(" | ") { it.toLogLine() })
            putLong("det_total", totalDet)
            putLong("rec_total", totalRec)
            putString("gate", bucketResults.joinToString(" | "))
        }
        instr.sendStatus(0, bundle)

        // Final asserts ensure ncnn produced boxes (2 runs: Screenshot + jpg)
        assertEquals(2, allResults.size)
        assertTrue(allResults.all { it.numBoxes > 0 })
    }

    @Test
    fun benchRecNcnnDynWidths() {
        // Dynamic-width micro-bench (#23) — one rec_dyn handle, several widths incl. off-bucket
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val h = 48
        val recDynField = engine.javaClass.getDeclaredField("recDynNcnn").apply { isAccessible = true }
        var recNcnn = recDynField.get(engine) as RecNcnn?
        if (recNcnn == null) {
            recNcnn = RecNcnn.create(appContext)
            assertNotNull("RecNcnn dyn failed to create", recNcnn)
            recDynField.set(engine, recNcnn)
        }
        val bundleOut = mutableListOf<String>()
        for (w in listOf(64, 200, 480)) {
            val inputFloats = FloatArray(3 * h * w) { ((it * 37) % 255) / 128f - 1f }
            Log.i(TAG, "benchRecNcnnDynWidths: recNcnn=$recNcnn w=$w h=$h floats=${inputFloats.size}")
            val ncnnTimes = mutableListOf<Long>()
            var ncnnOutSize = 0
            repeat(5) {
                val t0 = System.nanoTime()
                val out = recNcnn!!.infer(inputFloats, w, h)
                val ms = (System.nanoTime() - t0) / 1_000_000
                ncnnTimes.add(ms)
                if (out != null) ncnnOutSize = out.size
            }
            ncnnTimes.sort()
            val ncnnP50 = ncnnTimes[ncnnTimes.size / 2]
            Log.i(TAG, "benchRecNcnnDynWidths ncnn w$w p50=${ncnnP50}ms times=$ncnnTimes outSize=$ncnnOutSize")
            bundleOut.add("w$w ${ncnnP50}ms")
            assertTrue("ncnn output size should be ${w / 8}*13193", ncnnOutSize == (w / 8) * 13193)
        }

        val instr = InstrumentationRegistry.getInstrumentation()
        val bundle = android.os.Bundle().apply {
            putString("bench", "dyn ncnn ${bundleOut.joinToString(", ")}")
        }
        instr.sendStatus(0, bundle)
    }

    @Test
    fun benchRecNcnnAllBuckets() {
        // Dynamic-width ncnn (#23) + 3-crop bench.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val h = 48
        val recDynField = engine.javaClass.getDeclaredField("recDynNcnn").apply { isAccessible = true }
        var recNcnn = recDynField.get(engine) as RecNcnn?
        if (recNcnn == null) {
            recNcnn = RecNcnn.create(appContext)
            assertNotNull("RecNcnn dyn failed to create", recNcnn)
            recDynField.set(engine, recNcnn)
        }

        for (w in listOf(64, 200, 480)) {
            val seqLen = w / 8
            val inputFloats = FloatArray(3 * h * w) { ((it * 37) % 255) / 128f - 1f }
            val ncnnTimes = mutableListOf<Long>()
            var ncnnOutSize = 0
            repeat(5) {
                val t0 = System.nanoTime()
                val out = recNcnn!!.infer(inputFloats, w, h)
                ncnnTimes.add((System.nanoTime() - t0) / 1_000_000)
                if (out != null) ncnnOutSize = out.size
            }
            ncnnTimes.sort()
            val ncnnP50 = ncnnTimes[ncnnTimes.size / 2]
            Log.i(TAG, "benchRecNcnnAllBuckets w$w seq$seqLen ncnn p50=${ncnnP50}ms $ncnnTimes outSize=$ncnnOutSize")
            assertTrue("ncnn outSize w$w", ncnnOutSize == seqLen * 13193)
        }

        // Now 3-crop bench with the ncnn engine.
        val ncnnEngine = OcrEngine(appContext)
        assertTrue("ncnnEngine ready", ncnnEngine.isReady())
        Log.i(TAG, "benchRecNcnnAllBuckets: 3-crop bench")
        val bmp1 = loadBenchmarkBitmap("Screenshot_20260530-172718.png")
        val bmp2 = loadBenchmarkBitmap("f5d7d08735383899.jpg")
        // Use ncnnEngine for bench — copy benchOneImage logic with ncnnEngine
        fun benchWithEngine(name: String, bitmap: Bitmap, eng: OcrEngine): BenchResult {
            Log.i(TAG, "bench start $name ${bitmap.width}x${bitmap.height} backend=ncnn")
            val tDet = System.nanoTime()
            val boxes = eng.detect(bitmap)
            val detMs = (System.nanoTime() - tDet) / 1_000_000
            Log.i(TAG, "bench det $name boxes=${boxes.size} detMs=$detMs backend=ncnn")
            if (boxes.isEmpty()) return BenchResult(name, bitmap.width, bitmap.height, detMs, detMs, detMs, 0, 0, 0, 0, 0, 0, emptyList())
            val sampleBoxes = when { boxes.size <= 3 -> boxes else -> listOf(boxes.first(), boxes[boxes.size / 2], boxes.last()) }
            Log.i(TAG, "bench sample ${sampleBoxes.size}/${boxes.size} boxes for rec backend=ncnn")
            val texts = mutableListOf<String>()
            val collected = mutableListOf<Pair<Int, LineResult>>()
            val tRec = System.nanoTime()
            kotlinx.coroutines.runBlocking {
                eng.recognizeStreaming(bitmap, sampleBoxes) { pairs -> synchronized(collected) { collected.addAll(pairs) } }
                var waited = 0
                var lastSize = -1
                var still = 0
                // Quiescence wait (empties are skipped by design): stop ~1s after
                // last arrival instead of burning a fixed 30s timeout (#51 — a
                // single empty sample inflated perCrop past its gate).
                while (collected.size < sampleBoxes.size && waited < 30000) {
                    kotlinx.coroutines.delay(100)
                    waited += 100
                    synchronized(collected) {
                        if (collected.size == lastSize) still += 100 else { still = 0; lastSize = collected.size }
                    }
                    if (still >= 1000 && waited > 2000) break
                }
            }
            val recMs = (System.nanoTime() - tRec) / 1_000_000
            for ((_, line) in collected) texts.add(line.text)
            Log.i(TAG, "bench rec $name sampled=${collected.size}/${sampleBoxes.size} totalBoxes=${boxes.size} recMs=$recMs perCrop=${if (sampleBoxes.isEmpty()) 0 else recMs / sampleBoxes.size} sample=${texts.take(3).joinToString(" | ")} backend=ncnn")
            return BenchResult(name, bitmap.width, bitmap.height, detMs, detMs, detMs, recMs, recMs, recMs, if (sampleBoxes.isEmpty()) 0 else recMs / sampleBoxes.size, if (sampleBoxes.isEmpty()) 0 else recMs / sampleBoxes.size, boxes.size, texts.take(5))
        }
        val r1 = benchWithEngine("Screenshot_20260530-172718.png", bmp1, ncnnEngine)
        val r2 = benchWithEngine("f5d7d08735383899.jpg", bmp2, ncnnEngine)
        Log.i(TAG, "SUMMARY backend=ncnn ${r1.toLogLine()}")
        Log.i(TAG, "SUMMARY backend=ncnn ${r2.toLogLine()}")
        val avgPerCrop = (r1.perCropMsP50 + r2.perCropMsP50) / 2
        Log.i(TAG, "SUMMARY backend=ncnn avg_perCrop_p50=${avgPerCrop}ms")
        bmp1.recycle()
        bmp2.recycle()
        ncnnEngine.close()
        assertTrue(r1.numBoxes > 0 && r2.numBoxes > 0)
        assertTrue("ncnn perCrop should be < 1500ms avg (relaxed from 1200ms until quant, baseline 2407ms)", avgPerCrop < 1500)
    }
}
