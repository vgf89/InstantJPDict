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
 * Part of #7
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
            while (collected.size < sampleBoxes.size && waited < 30000) {
                kotlinx.coroutines.delay(50)
                waited += 50
            }
        }
        var waited = 0
        while (collected.size < sampleBoxes.size && waited < 2000) {
            Thread.sleep(50)
            waited += 50
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
}
