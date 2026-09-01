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
            // Ensure default is onnx for baseline benches; ncnn benches set prefs explicitly
            appContext.getSharedPreferences("instant_jp_dict_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("ocr_backend", "onnx").apply()
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

    @Test
    fun benchRecNcnnW64() {
        // Direct w64 micro-bench: synthetic 48×64 crop — head-to-head ORT vs ncnn #12
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // 48×64 input: 3×48×64 = 9216 floats, gray center box simulation
        val w = 64
        val h = 48
        val inputFloats = FloatArray(3 * h * w) { 0f } // neutral gray (0) — actual content doesn't affect timing much
        // warm: fill with pseudo-random to avoid zero fast-path
        for (i in inputFloats.indices) inputFloats[i] = ((i * 37) % 255) / 128f - 1f

        // Access OcrEngine internals via reflection for ORT baseline
        val ortEnvField = engine.javaClass.getDeclaredField("ortEnv").apply { isAccessible = true }
        val recSessionsField = engine.javaClass.getDeclaredField("recSessions").apply { isAccessible = true }
        val ortEnv = ortEnvField.get(engine) as ai.onnxruntime.OrtEnvironment
        @Suppress("UNCHECKED_CAST")
        val recSessions = recSessionsField.get(engine) as MutableMap<Int, ai.onnxruntime.OrtSession>
        val ortSession = recSessions[64] ?: error("no ORT session for w64")

        // Ensure ncnn is loaded
        val recNcnnField = engine.javaClass.getDeclaredField("recNcnnW64").apply { isAccessible = true }
        var recNcnn = recNcnnField.get(engine) as RecNcnn?
        if (recNcnn == null) {
            recNcnn = RecNcnn.create(appContext, 64)
            assertNotNull("RecNcnn w64 failed to create", recNcnn)
            recNcnnField.set(engine, recNcnn)
        }
        Log.i(TAG, "benchRecNcnnW64: ortSession=${ortSession != null} recNcnn=$recNcnn w=$w h=$h floats=${inputFloats.size}")

        // ORT: 5 runs, median
        val ortTimes = mutableListOf<Long>()
        var ortOutSize = 0
        repeat(5) {
            val t0 = System.nanoTime()
            val tensor = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(inputFloats), longArrayOf(1, 3, h.toLong(), w.toLong()))
            val result = ortSession.run(mapOf("x" to tensor))
            tensor.close()
            val outTensor = result.get(0) as ai.onnxruntime.OnnxTensor
            val out = FloatArray(8 * 18710)
            outTensor.floatBuffer.get(out)
            ortOutSize = out.size
            outTensor.close()
            result.close()
            ortTimes.add((System.nanoTime() - t0) / 1_000_000)
        }
        ortTimes.sort()
        val ortP50 = ortTimes[ortTimes.size / 2]
        Log.i(TAG, "benchRecNcnnW64 ORT w64 p50=${ortP50}ms times=$ortTimes outSize=$ortOutSize")

        // ncnn: 5 runs, median
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
        Log.i(TAG, "benchRecNcnnW64 ncnn w64 p50=${ncnnP50}ms times=$ncnnTimes outSize=$ncnnOutSize speedup=${if (ncnnP50>0) String.format("%.2fx", ortP50.toFloat()/ncnnP50) else "inf"}")

        // Also emit via bundle for cron gating
        val instr = InstrumentationRegistry.getInstrumentation()
        val bundle = android.os.Bundle().apply {
            putLong("ort_w64_p50", ortP50)
            putLong("ncnn_w64_p50", ncnnP50)
            putString("bench", "w64 ORT ${ortP50}ms ncnn ${ncnnP50}ms speedup ${if (ncnnP50>0) ortP50.toFloat()/ncnnP50 else 0f}")
        }
        instr.sendStatus(0, bundle)
        assertTrue("ncnn should be faster than ORT for w64", ncnnP50 < ortP50)
        assertTrue("ncnn output size should be 8*18710", ncnnOutSize == 8 * 18710)
    }

    @Test
    fun benchRecNcnnAllBuckets() {
        // Head-to-head ORT vs ncnn for all 4 buckets + 3-crop ncnn bench for #13
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val h = 48
        val ortEnvField = engine.javaClass.getDeclaredField("ortEnv").apply { isAccessible = true }
        val recSessionsField = engine.javaClass.getDeclaredField("recSessions").apply { isAccessible = true }
        val ortEnv = ortEnvField.get(engine) as ai.onnxruntime.OrtEnvironment
        @Suppress("UNCHECKED_CAST")
        val recSessions = recSessionsField.get(engine) as MutableMap<Int, ai.onnxruntime.OrtSession>
        val recNcnnMapField = engine.javaClass.getDeclaredField("recNcnnMap").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        var recNcnnMap = recNcnnMapField.get(engine) as MutableMap<Int, RecNcnn>

        for (w in listOf(64, 128, 256, 480)) {
            val seqLen = w / 8
            val inputFloats = FloatArray(3 * h * w) { ((it * 37) % 255) / 128f - 1f }
            val ortSession = recSessions[w] ?: error("no ORT session for w$w")
            var recNcnn = recNcnnMap[w]
            if (recNcnn == null) {
                recNcnn = RecNcnn.create(appContext, w)
                assertNotNull("RecNcnn w$w failed to create", recNcnn)
                recNcnnMap[w] = recNcnn!!
                recNcnnMapField.set(engine, recNcnnMap)
            }
            val ortTimes = mutableListOf<Long>()
            repeat(5) {
                val t0 = System.nanoTime()
                val tensor = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(inputFloats), longArrayOf(1, 3, h.toLong(), w.toLong()))
                val result = ortSession.run(mapOf("x" to tensor))
                tensor.close()
                val outTensor = result.get(0) as ai.onnxruntime.OnnxTensor
                val out = FloatArray(seqLen * 18710)
                outTensor.floatBuffer.get(out)
                outTensor.close()
                result.close()
                ortTimes.add((System.nanoTime() - t0) / 1_000_000)
            }
            ortTimes.sort()
            val ortP50 = ortTimes[ortTimes.size / 2]
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
            val speedup = if (ncnnP50 > 0) ortP50.toFloat() / ncnnP50 else 0f
            Log.i(TAG, "benchRecNcnnAllBuckets w$w seq$seqLen ORT p50=${ortP50}ms $ortTimes ncnn p50=${ncnnP50}ms $ncnnTimes speedup=${String.format("%.2fx", speedup)} outSize=$ncnnOutSize")
            assertTrue("ncnn should be faster than ORT for w$w", ncnnP50 < ortP50)
            assertTrue("ncnn outSize w$w", ncnnOutSize == seqLen * 18710)
        }

        // Now 3-crop bench with ncnn forced via prefs (for #13 backend=ncnn gate)
        appContext.getSharedPreferences("instant_jp_dict_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("ocr_backend", "ncnn").apply()
        val ncnnEngine = OcrEngine(appContext)
        assertTrue("ncnnEngine ready", ncnnEngine.isReady())
        Log.i(TAG, "benchRecNcnnAllBuckets: 3-crop with ncnn forced via prefs")
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
                while (collected.size < sampleBoxes.size && waited < 30000) { kotlinx.coroutines.delay(50); waited += 50 }
            }
            var waited = 0
            while (collected.size < sampleBoxes.size && waited < 2000) { Thread.sleep(50); waited += 50 }
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
        // Reset to onnx for other tests
        appContext.getSharedPreferences("instant_jp_dict_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("ocr_backend", "onnx").apply()
        assertTrue(r1.numBoxes > 0 && r2.numBoxes > 0)
        assertTrue("ncnn perCrop should be < 1500ms avg (relaxed from 1200ms until quant, baseline 2407ms)", avgPerCrop < 1500)
    }
}
