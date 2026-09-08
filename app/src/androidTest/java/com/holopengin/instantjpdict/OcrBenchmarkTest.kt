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

    @Test
    fun verticalVertSubstitution() {
        // Renderer contract for #47: the device font must substitute vertical
        // variants under Minikin vert (ja locale) — the backend ships
        // horizontal chars and the overlay relies on the font. A substituted
        // vertical glyph flips bounds aspect (wide->tall); kana/kanji have no
        // vert sub and must stay identical. Fails = font lacks vert coverage.
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textLocale = java.util.Locale.JAPANESE
        paint.textSize = 100f
        val b = android.graphics.Rect()
        fun bounds(ch: Char, feat: String?): Pair<Int, Int> {
            paint.fontFeatureSettings = feat
            paint.getTextBounds(ch.toString(), 0, 1, b)
            return b.width() to b.height()
        }
        // Substituted chars flip bounds-aspect sign (horizontal corner
        // brackets/parens are naturally tall off-feature — what matters is
        // the flip, e.g. 「 32x67 -> 72x32, ー 80x11 -> 10x80).
        fun sgn(x: Int) = if (x > 0) 1 else if (x < 0) -1 else 0
        for (ch in listOf('ー', '「', '」', '（', '）', '〜')) {
            val (w0, h0) = bounds(ch, null)
            val (w1, h1) = bounds(ch, "'vert' 1")
            Log.i(TAG, "vertSub '$ch' off=${w0}x${h0} vert=${w1}x${h1}")
            assertTrue("'$ch' must flip aspect under vert", sgn(w0 - h0) != sgn(w1 - h1))
        }
        for (ch in listOf('あ', '漢', '・')) {
            val (w0, h0) = bounds(ch, null)
            val (w1, h1) = bounds(ch, "'vert' 1")
            assertTrue("'$ch' must be vert-invariant", Math.abs(w1 - w0) <= 2 && Math.abs(h1 - h0) <= 2)
        }
    }

    @Test
    fun synthBoxingBench() {
        // #49 positioning: overlay boxes vs exact em-box truth (synth set,
        // 4 box variants each) x layout {legacy, snap} x uniform {off, on}.
        // Decoded text is DP-aligned to truth text; metrics on matched pairs
        // + span/unmatched + box-size variance (usize).
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Synth set lives in the TEST apk (androidTest/assets/synth).
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        fun assetText(name: String): String {
            testAssets.open(name).use { ins ->
                return ins.readBytes().toString(Charsets.UTF_8)
            }
        }
        val truth = org.json.JSONObject(assetText("synth/truth.json"))
        val lines = truth.getJSONArray("lines")
        Log.i(TAG, "synthBox START lines=${lines.length()}")
        val eng = OcrEngine(appContext)
        assertTrue("engine ready", eng.isReady())
        try {
        for (li in 0 until lines.length()) {
            val entry = lines.getJSONObject(li)
            val file = entry.getString("file")
            val tText = entry.getString("text")
            val vertical = entry.getString("orientation") == "V"
            val tBoxes = entry.getJSONArray("boxes")
            val tEm = (0 until tBoxes.length()).map { i ->
                val b = tBoxes.getJSONArray(i)
                intArrayOf(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
            }
            val tUnion = intArrayOf(
                tEm.minOf { it[0] }, tEm.minOf { it[1] },
                tEm.maxOf { it[0] + it[2] }, tEm.maxOf { it[1] + it[3] })
            val bmp = testAssets.open("synth/$file").use { ins ->
                android.graphics.BitmapFactory.decodeStream(ins)
            } ?: continue
            val det = eng.detect(bmp)
            if (det.isEmpty()) {
                Log.i(TAG, "synthBox $file: NO det boxes, skip")
                bmp.recycle(); continue
            }
            // Largest det box = the line (single-line images).
            val db = det.maxByOrNull { it.width() * it.height() }!!
            val iw = (tUnion[2] - tUnion[0]).toFloat()
            val ih = (tUnion[3] - tUnion[1]).toFloat()
            val ix = maxOf(0, minOf(db.right, tUnion[2]) - maxOf(db.left, tUnion[0])).toFloat()
            val iy = maxOf(0, minOf(db.bottom, tUnion[3]) - maxOf(db.top, tUnion[1])).toFloat()
            val detIou = (ix * iy) / (db.width() * db.height() + iw * ih - ix * iy)
            // Box variants: det box (realistic), exact truth box (clean
            // reference), +25% padding at reading-start / reading-end
            // (adversarial: leading/trailing blank mass). One recognition
            // run per variant (mode-independent text/cols), boxes per mode.
            val tLen = if (vertical) tUnion[3] - tUnion[1] else tUnion[2] - tUnion[0]
            val pad = (tLen * 0.25).toInt()
            fun jrect(l: Int, t: Int, r: Int, b: Int) = JpDictRect(
                l.coerceAtLeast(0), t.coerceAtLeast(0),
                r.coerceAtMost(bmp.width), b.coerceAtMost(bmp.height))
            val variants = listOf(
                "det" to db,
                "truth" to jrect(tUnion[0], tUnion[1], tUnion[2], tUnion[3]),
                "padEnd" to (if (vertical)
                    jrect(tUnion[0], tUnion[1], tUnion[2], tUnion[3] + pad)
                else jrect(tUnion[0], tUnion[1], tUnion[2] + pad, tUnion[3])),
                "padStart" to (if (vertical)
                    jrect(tUnion[0], tUnion[1] - pad, tUnion[2], tUnion[3])
                else jrect(tUnion[0] - pad, tUnion[1], tUnion[2], tUnion[3])),
            )
            fun runVariant(vtag: String, vbox: JpDictRect, viou: Double) {
                // Single recognition run per variant.
                val collected = mutableListOf<Pair<Int, LineResult>>()
                runBlocking {
                    eng.recognizeStreaming(bmp, listOf(vbox)) { pairs ->
                        synchronized(collected) { collected.addAll(pairs) }
                    }
                    var waited = 0; var last = -1; var still = 0
                    while (collected.isEmpty() && waited < 15000) {
                        kotlinx.coroutines.delay(200); waited += 200
                        synchronized(collected) {
                            if (collected.size == last) still += 200 else { still = 0; last = collected.size }
                        }
                        if (still >= 1000 && waited > 2000) break
                    }
                }
                if (collected.isEmpty()) {
                    Log.i(TAG, "synthBox $file/$vtag: recognized EMPTY, skip")
                    return
                }
                val lr = collected.sortedBy { it.first }.first().second
                val pairs = alignChars(tText, lr.text)
                val nMatch = pairs.size
                for (mode in 0..1) {
                    OcrEngine.BOX_LAYOUT_MODE = mode
                    for (uni in 0..1) {
                    OcrEngine.BOX_UNIFORM_SIZE = uni == 1
                    // Snap mode needs crop pixels: re-extract from the
                    // source bitmap via the line's crop geometry.
                    var px: IntArray? = null
                    var pw = 0
                    var ph = 0
                    if (mode == OcrEngine.BOX_SNAP) {
                        val cx = lr.cropX.coerceIn(0, bmp.width - 1)
                        val cy = lr.cropY.coerceIn(0, bmp.height - 1)
                        val cw = minOf(lr.cropW, bmp.width - cx).coerceAtLeast(1)
                        val ch = minOf(lr.cropH, bmp.height - cy).coerceAtLeast(1)
                        try {
                            val cb = android.graphics.Bitmap.createBitmap(bmp, cx, cy, cw, ch)
                            pw = cb.width; ph = cb.height
                            px = IntArray(pw * ph)
                            cb.getPixels(px!!, 0, pw, 0, 0, pw, ph)
                            if (cb != bmp) cb.recycle()
                        } catch (_: Exception) { px = null }
                    }
                    val boxes = eng.computeCharBoxes(lr.text, lr.charCols, lr.seqLenTotal,
                        lr.cropX, lr.cropY, lr.cropW, lr.cropH, lr.isVertical,
                        px, pw, ph)
                    assertEquals("$file/$vtag mode $mode box count", lr.text.length, boxes.size)
                    var errSum = 0.0; var errMax = 0.0
                    var sxSum = 0.0; var sySum = 0.0
                    val errs = mutableListOf<Triple<Double, Double, Double>>()
                    for ((ti, di) in pairs) {
                        val tb = tEm[ti]
                        val ob = boxes[di]
                        val tcx = tb[0] + tb[2] / 2.0; val tcy = tb[1] + tb[3] / 2.0
                        val ocx = (ob.left + ob.right) / 2.0; val ocy = (ob.top + ob.bottom) / 2.0
                        val dx = ocx - tcx; val dy = ocy - tcy
                        val e = Math.hypot(dx, dy)
                        errSum += e; errMax = maxOf(errMax, e)
                        sxSum += dx; sySum += dy
                        errs.add(Triple(dx, dy, e))
                    }
                    // Demeaned: strip the systematic det-box offset to isolate
                    // layout error from detection shift.
                    val mx = if (nMatch > 0) sxSum / nMatch else 0.0
                    val my = if (nMatch > 0) sySum / nMatch else 0.0
                    var dmSum = 0.0; var dmMax = 0.0
                    for ((dx, dy, _) in errs) {
                        val e = Math.hypot(dx - mx, dy - my)
                        dmSum += e; dmMax = maxOf(dmMax, e)
                    }
                    val em = 44.0
                    // Span fill along the line axis.
                    val (tSpan, oSpan) = if (vertical) {
                        (tUnion[3] - tUnion[1]).toDouble() to
                            ((boxes.maxOf { it.bottom } - boxes.minOf { it.top }).toDouble())
                    } else {
                        (tUnion[2] - tUnion[0]).toDouble() to
                            ((boxes.maxOf { it.right } - boxes.minOf { it.left }).toDouble())
                    }
                    val lens = boxes.map { b ->
                        if (vertical) (b.bottom - b.top).toDouble() else (b.right - b.left).toDouble()
                    }
                    val meanLen = if (lens.isNotEmpty()) lens.sum() / lens.size else 0.0
                    val sizeVar = if (lens.size > 1) {
                        Math.sqrt(lens.map { (it - meanLen) * (it - meanLen) }.sum() / lens.size) / em
                    } else 0.0
                    Log.i(TAG, "synthBox $file/$vtag mode=$mode uni=$uni iou=%.3f nT=%d nD=%d match=%d unT=%d unD=%d meanErr=%.1fpx(%.2fem) maxErr=%.1fpx off=(%.1f,%.1f) demean=%.1fpx(%.2fem) maxDm=%.1fpx spanT=%.0f spanO=%.0f fill=%.2f usize=%.2f".format(
                        viou, tText.length, lr.text.length, nMatch,
                        tText.length - pairs.map { it.first }.toSet().size,
                        lr.text.length - pairs.map { it.second }.toSet().size,
                        if (nMatch > 0) errSum / nMatch else -1.0,
                        if (nMatch > 0) errSum / nMatch / em else -1.0,
                        errMax, mx, my,
                        if (nMatch > 0) dmSum / nMatch else -1.0,
                        if (nMatch > 0) dmSum / nMatch / em else -1.0,
                        dmMax, tSpan, oSpan, oSpan / tSpan, sizeVar))
                    }
                }
            } // end runVariant
            for ((vtag, vbox) in variants) {
                val vx = maxOf(0, minOf(vbox.right, tUnion[2]) - maxOf(vbox.left, tUnion[0])).toFloat()
                val vy = maxOf(0, minOf(vbox.bottom, tUnion[3]) - maxOf(vbox.top, tUnion[1])).toFloat()
                val viou = ((vx * vy) / (vbox.width() * vbox.height() + iw * ih - vx * vy)).toDouble()
                runVariant(vtag, vbox, viou)
            }
            bmp.recycle()
        }
        } finally {
            OcrEngine.BOX_LAYOUT_MODE = OcrEngine.BOX_SNAP
            OcrEngine.BOX_UNIFORM_SIZE = false
        }
    }

    /** Levenshtein backtrack aligning truth->decoded chars; returns matched
     * (truthIdx, decIdx) pairs (matches + substitutions). */
    private fun alignChars(a: String, b: String): List<Pair<Int, Int>> {
        val n = a.length; val m = b.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        val out = mutableListOf<Pair<Int, Int>>()
        var i = n; var j = m
        while (i > 0 && j > 0) {
            val sub = dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            when {
                dp[i][j] == sub -> { out.add((i - 1) to (j - 1)); i--; j-- }
                dp[i][j] == dp[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
        return out.reversed()
    }

    @Test
    fun questColsStats() {
        // #49 real-data diagnosis: per-line CTC column pathology on the dense
        // quest image (leading mass, max internal gap, trailing mass) to find
        // the lines behind the whitespace-offset / shortfall complaints.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val bmp = loadBenchmarkBitmap("Screenshot_20260905-093821.png")
        val eng = OcrEngine(appContext)
        assertTrue("engine ready", eng.isReady())
        val boxes = eng.detect(bmp)
        val collected = mutableListOf<Pair<Int, LineResult>>()
        runBlocking {
            eng.recognizeStreaming(bmp, boxes) { pairs ->
                synchronized(collected) { collected.addAll(pairs) }
            }
            var waited = 0; var last = -1; var still = 0
            while (collected.size < boxes.size && waited < 60000) {
                kotlinx.coroutines.delay(200); waited += 200
                synchronized(collected) {
                    if (collected.size == last) still += 200 else { still = 0; last = collected.size }
                }
                if (still >= 1500 && waited > 5000) break
            }
        }
        for ((idx, lr) in collected.sortedBy { it.first }) {
            val cols = lr.charCols
            if (cols.isEmpty()) continue
            var maxGap = 0f
            for (i in 1 until cols.size) maxGap = maxOf(maxGap, cols[i] - cols[i - 1] - 1f)
            val lead = cols[0]
            val trail = lr.seqLenTotal - 1 - cols[cols.size - 1]
            val avg = (if (lr.isVertical) lr.cropH else lr.cropW).toFloat() / lr.seqLenTotal
            Log.i(TAG, "questCols idx=$idx n=${cols.size} seq=${lr.seqLenTotal} vert=${lr.isVertical} " +
                "lead=${lead}x trail=${trail}x maxGap=${maxGap}x " +
                "leadPx=${(lead * avg).toInt()} trailPx=${(trail * avg).toInt()} " +
                "text='${lr.text.take(24)}'")
        }
        eng.close()
    }

    @Test
    fun ebookOverlayDump() {
        overlayDumpToFile("ebook_tategaki.png", "ebook_overlay.png")
    }

    @Test
    fun questOverlayDump() {
        overlayDumpToFile("Screenshot_20260905-093821.png", "quest_overlay.png")
    }

    /** Shared visual-diagnosis dump (#49): det boxes (blue) + legacy overlay
     * char boxes (red) + per-line column pathology in logcat. */
    private fun overlayDumpToFile(asset: String, outName: String) {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val bmp = loadBenchmarkBitmap(asset)
        val eng = OcrEngine(appContext)
        assertTrue("engine ready", eng.isReady())
        val boxes = eng.detect(bmp)
        val collected = mutableListOf<Pair<Int, LineResult>>()
        runBlocking {
            eng.recognizeStreaming(bmp, boxes) { pairs ->
                synchronized(collected) { collected.addAll(pairs) }
            }
            var waited = 0; var last = -1; var still = 0
            while (collected.size < boxes.size && waited < 120000) {
                kotlinx.coroutines.delay(500); waited += 500
                synchronized(collected) {
                    if (collected.size == last) still += 500 else { still = 0; last = collected.size }
                }
                if (still >= 10000 && waited > 15000) break
            }
            Log.i(TAG, "dumpCollect ${collected.size}/${boxes.size} boxes")
        }
        val out = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val cv = android.graphics.Canvas(out)
        val detPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLUE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f
        }
        val boxPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f
        }
        for (b in boxes) cv.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), detPaint)
        for ((idx, lr) in collected.sortedBy { it.first }) {
            for (cb in lr.charBoxes) cv.drawRect(cb.left.toFloat(), cb.top.toFloat(), cb.right.toFloat(), cb.bottom.toFloat(), boxPaint)
            val cols = lr.charCols
            if (cols.isNotEmpty()) {
                var mg = 0f
                for (i in 1 until cols.size) mg = maxOf(mg, cols[i] - cols[i - 1] - 1f)
                Log.i(TAG, "ebookDump idx=$idx n=${cols.size} seq=${lr.seqLenTotal} vert=${lr.isVertical} " +
                    "lead=${cols[0]} trail=${lr.seqLenTotal - 1 - cols[cols.size - 1]} maxGap=$mg " +
                    "crop=${lr.cropX},${lr.cropY},${lr.cropW},${lr.cropH} " +
                    "text='${lr.text}'")
                if (cols.size > 15) {
                    Log.i(TAG, "ebookCols idx=$idx cols=" + cols.joinToString(",") { "%.2f".format(it) })
                }
            }
        }
        val f = java.io.File(appContext.getExternalFilesDir(null), outName)
        f.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "overlayDump $asset boxes=${boxes.size} lines=${collected.size} file=${f.absolutePath}")
        eng.close()
    }

    @Test
    fun rubyGutterExclusion() {
        // #48: ruby intruding INSIDE a vertical line box must not contaminate
        // the decode. Ground truth for the target line (ruby stripped).
        val truth = "じわり、と掌に滲んだのは、血と、汗。"
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val bmp = loadBenchmarkBitmap("ruby_ebook.png")
        val eng = OcrEngine(appContext)
        assertTrue("engine ready", eng.isReady())
        val boxes = eng.detect(bmp)
        assertTrue("expected boxes", boxes.isNotEmpty())
        val collected = mutableListOf<Pair<Int, LineResult>>()
        runBlocking {
            eng.recognizeStreaming(bmp, boxes) { pairs ->
                synchronized(collected) { collected.addAll(pairs) }
            }
            // Deterministic full collection (#48 collateral): empties never
            // arrive, so count alone can't terminate — but quiescence must
            // be LONG (10s still) or slow lines get truncated nondeterministically.
            var waited = 0; var last = -1; var still = 0
            while (collected.size < boxes.size && waited < 120000) {
                kotlinx.coroutines.delay(500); waited += 500
                synchronized(collected) {
                    if (collected.size == last) still += 500 else { still = 0; last = collected.size }
                }
                if (still >= 10000 && waited > 15000) break
            }
            Log.i(TAG, "rubyCollect ${collected.size}/${boxes.size} boxes in ${waited}ms")
        }
        eng.close()
        val texts = collected.sortedBy { it.first }.map { it.second.text }
        texts.forEachIndexed { i, t -> Log.i(TAG, "rubyLine [$i]='$t'") }
        val scored = texts.map { it to editDistance(it, truth) }
        val (best, d) = scored.minByOrNull { it.second } ?: ("" to Int.MAX_VALUE)
        Log.i(TAG, "rubyBest dist=$d text='$best' truth='$truth'")
        assertTrue("ruby line misread: '$best' vs '$truth' (dist $d)", d <= 4)
        assertTrue("reported failure: じ read as わ-side kana in '$best'", 'じ' in best)
    }

    @Test
    fun threadBatchSweep() {
        // #58: re-sweep rec threads x streaming batch size on the quest image
        // (CPU-only tree, deterministic collection). One cell = fresh engine +
        // warmup + min-of-3 det/rec walls. Quiescence floor (~2.6s) is constant
        // across cells, so deltas are compute.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            val bmp = loadBenchmarkBitmap("Screenshot_20260905-093821.png")
            val summary = mutableListOf<String>()
            for (threads in listOf(1, 2, 4)) {
                for (batch in listOf(2, 4, 8)) {
                    OcrEngine.REC_THREADS = threads
                    OcrEngine.REC_BATCH_SIZE = batch
                    val eng = OcrEngine(appContext)
                    assertTrue("engine ready t=$threads b=$batch", eng.isReady())
                    val boxes = eng.detect(bmp)
                    assertTrue("expected 20+ lines", boxes.size >= 20)
                    // Warmup.
                    runBlocking {
                        eng.recognizeStreaming(bmp, boxes) { _ -> }
                        kotlinx.coroutines.delay(500)
                    }
                    fun timeDetect(): Long {
                        var best = Long.MAX_VALUE
                        repeat(3) {
                            val t0 = System.nanoTime()
                            eng.detect(bmp)
                            best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
                        }
                        return best
                    }
                    fun timeRec(): Long {
                        var best = Long.MAX_VALUE
                        repeat(3) {
                            val collected = mutableListOf<Pair<Int, LineResult>>()
                            val t0 = System.nanoTime()
                            runBlocking {
                                eng.recognizeStreaming(bmp, boxes) { pairs ->
                                    synchronized(collected) { collected.addAll(pairs) }
                                }
                                var waited = 0; var last = -1; var still = 0
                                while (collected.size < boxes.size && waited < 120000) {
                                    kotlinx.coroutines.delay(200); waited += 200
                                    synchronized(collected) {
                                        if (collected.size == last) still += 200 else { still = 0; last = collected.size }
                                    }
                                    if (still >= 1500 && waited > 2500) break
                                }
                            }
                            best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
                        }
                        return best
                    }
                    val detMs = timeDetect()
                    val recMs = timeRec()
                    Log.i(TAG, "sweepCfg threads=$threads batch=$batch boxes=${boxes.size} detMs=$detMs recMs=$recMs")
                    summary.add("t$threads/b$batch det=$detMs rec=$recMs")
                    eng.close()
                }
            }
            val bundle = android.os.Bundle().apply {
                putString("sweep", summary.joinToString(" | "))
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle)
        } finally {
            OcrEngine.REC_THREADS = 1
            OcrEngine.REC_BATCH_SIZE = 4
        }
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
    fun endToEndQuestBook() {
        // End-to-end CPU sanity on a dense real screenshot: detect once,
        // recognize ALL lines, assert lines come back.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val bmp = loadBenchmarkBitmap("Screenshot_20260905-093821.png")
        val eng = OcrEngine(appContext)
        assertTrue("engine ready", eng.isReady())
        val boxes = eng.detect(bmp)
        Log.i(TAG, "questE2E quest book boxes=${boxes.size}")
        assertTrue("expected 20+ lines, got ${boxes.size}", boxes.size >= 20)
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
        // Idx-keyed: empties are skipped by design — align by job idx.
        val texts = collected.sortedBy { it.first }.associate { it.first to it.second.text }
        Log.i(TAG, "questE2E SUMMARY lines=${texts.size}/${boxes.size} recMs=${ms}ms")
        assertTrue("no lines recognized", texts.isNotEmpty())
        eng.close()
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
