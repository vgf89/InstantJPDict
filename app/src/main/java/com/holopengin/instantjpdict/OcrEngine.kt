package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.holopengin.instantjpdict.util.InferLog
import com.holopengin.instantjpdict.util.JapaneseUtil
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** PP-OCRv6 [OcrEngine] — ncnn detect (DB, DET_MODEL_SIZE²) + dynamic-width rec (48×W) + CTC.
 *
 * Seams (single file by decision, #29): Detect §§ (detect + unclip + furigana +
 * merge/split/ruby-trim/sort) → Rec batch/stream §§ (recognizePpocrBatch + recognizeStreaming,
 * completion-order select emit) → Stitch §§ (long-line horiz/vert chunk + anchor
 * stitch) → CTC + CharBox §§ (ctcDecode, computeCharBoxes, vertical glyphs) →
 * Tunables + init §§ (SharedPreferences, vocab). Stitch chunks run unsquished
 * full-res; only the single-pass batch path applies [recSquish] (#24).
 */

class OcrEngine(private val context: Context) {
    // Detection model (DB, #51) + vocabulary + single dynamic-width rec model (#23).
    private var detNcnn: DetNcnn? = null
    private var ppocrVocab: List<String> = emptyList()
    private var classRemap: IntArray = IntArray(0) // pruned-out -> orig class id (#39)
    /** Pruned CTC-head width, derived from rec_remap.txt when it loads (#44). */
    private var recNumOutputs = 0
    private var recDynNcnn: RecNcnn? = null

    // One line awaiting recognition; results stay keyed by idx.
    private data class Job(val idx: Int, val bbox: JpDictRect, val isVertical: Boolean)

    companion object {
        private const val TAG = "PPOCREngine"

        // SharedPreferences keys for tunables — #14 (ncnn-only; no backend switch)
        const val PREFS_NAME = "instant_jp_dict_prefs"
        const val PREF_DET_THRESH = "ppocr_det_thresh"
        const val PREF_DET_UNCLIP = "ppocr_det_unclip_ratio"
        const val PREF_X_OVERLAP = "x_overlap_thresh"
        const val PREF_REC_SQUISH = "rec_squish_factor"

        // Defaults (previous hard constants)
        const val DEF_DET_LONG_SIDE = 960
        /** Det net input side (#51): 896 holds box counts within ±4% on all 8
         * bench images (host study, app-exact postprocess port) for ~13%
         * less compute than 960; 832+ breaks dense screenshots. Test-flippable
         * to 960 for A/B walls. */
        var DET_MODEL_SIZE = 896
        /** Char-box layout (#49): 0=legacy uniform columns, 1=legacy +
         * image-evidence snapping (idea 4, default — quest failing line mean
         * 5.7->4.0px, max 25->15px; synth worst-case ~= legacy). Null pixels
         * (re-decode path) always take legacy. Test-flippable. */
        var BOX_LAYOUT_MODE = 1
        const val BOX_LEGACY = 0
        const val BOX_SNAP = 1
        /** Uniform em sizing (#49, default): JP + fullwidth latin share one
         * em box, em = median center-to-center distance; halfwidth = 0.5em.
         * Widths-only pass over resolved centers (positions bit-identical to
         * legacy path); punct expand runs after as before. Test-flippable. */
        var BOX_UNIFORM_SIZE = true
        /** Ruby-gutter trim for vertical lines (#48, default): detector boxes
         * that swallowed the furigana strip (~2x normal column width) are cut
         * back to the main column geometrically — crops, char boxes and overlay
         * all derive from the trimmed box, so the ruby width never re-enters.
         * Test-flippable for A/B walls. */
        var RUBY_TRIM_VERTICAL = true
        /** Halfwidth test shared with the overlay renderer (#49): ASCII +
         * halfwidth katakana get 0.5em boxes and line-height-driven sizing. */
        internal fun isHalfWidth(ch: Char): Boolean {
            val cp = ch.code
            return cp <= 0x7E || (cp in 0xFF61..0xFFDC)
        }

        /** Consistent em from width-normalized pitches (#49): raw median
         * center distance collapses in mixed JP/ASCII lines (ASCII sits
         * ~0.5em apart, so an ASCII majority drags em to ~0.5x and kanji
         * boxes shrink to half width, rendering tiny). Each gap is divided
         * by the mean advance of its two chars in em units (0.5 halfwidth,
         * 1.0 fullwidth), so every normalized gap estimates one full em
         * regardless of script mix; em is the median of those. Pure
         * function for JVM unit tests. Returns 0f when unestimable. */
        internal fun estimateEm(text: String, centers: List<Float>): Float {
            if (text.length != centers.size || centers.size < 2) return 0f
            val norm = mutableListOf<Float>()
            for (i in 0 until centers.size - 1) {
                val gap = centers[i + 1] - centers[i]
                if (gap <= 0f) continue
                val units = ((if (isHalfWidth(text[i])) 0.5f else 1.0f) +
                        (if (isHalfWidth(text[i + 1])) 0.5f else 1.0f)) / 2f
                norm.add(gap / units)
            }
            if (norm.isEmpty()) return 0f
            norm.sort()
            return norm[norm.size / 2]
        }
        const val DEF_DET_THRESH = 0.3f
        const val DEF_DET_UNCLIP = 1.50f
        const val DEF_X_OVERLAP = 0.40f
        const val DEF_REC_SQUISH = 0.5f

        // Helpers for static access (no engine instance needed)
        fun getDetThresh(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(PREF_DET_THRESH, DEF_DET_THRESH)
        fun getDetUnclip(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(PREF_DET_UNCLIP, DEF_DET_UNCLIP)
        fun getDetLongSide(ctx: Context): Int = DEF_DET_LONG_SIDE
        fun getXOverlap(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(PREF_X_OVERLAP, DEF_X_OVERLAP)
        fun getRecSquish(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(PREF_REC_SQUISH, DEF_REC_SQUISH)

        /** Furigana (ruby) filter thresholds (#28) — conservative: better to
         * recognize ruby than to drop real small text. Matching runs on RAW
         * contour geometry (pre-unclip: unclip padding fabricates overlap for
         * stacked fragments); only the gap test uses UNCLIPPED boxes (raw gutters
         * are real pixels, unclip closes them to ruby distance). Vertical ruby
         * additionally requires the small-box center to lie OUTSIDE the big box
         * x-range, so stacked column tails are never dropped. */
        private const val FURIGANA_SIZE_RATIO = 0.3f    // small long-side < 30% of large long-side
        private const val FURIGANA_THIN_RATIO = 0.75f   // horizontal ruby runs long but thin;
                                                       // measured ~0.65-0.70 of main height here
                                                       // (vertical ruby stays short-only)
        private const val FURIGANA_HSHORT_RATIO = 0.85f // horizontal short-side ceiling: thin ruby here
                                                       // runs ~0.7 of main height; full-height short
                                                       // lines (≈1.0) must survive
        private const val FURIGANA_WIDTH_RATIO = 0.65f  // small short-side < 65% of large short-side:
                                                       // ruby glyphs run smaller; full-width short
                                                       // lines (か？」) and short columns survive this
        private const val FURIGANA_GAP_RATIO = 0.5f     // gap <= 50% of large short-side
        private const val FURIGANA_OVERLAP_RATIO = 0.5f // overlap >= 50% of small long-side
        private const val VERTICAL_MIN_ASPECT = 1.25f   // h >= 1.25w → vertical; squarer → horizontal
        // Absolute ceiling: real short columns (e.g. 458px) dwarf ruby runs even when the
        // ratio matches — ruby longer than 12% of the image side is not ruby. #28
        private const val FURIGANA_MAX_FRAC = 0.12f
        // Absolute floor on the ANNOTATED box: ruby hugs full-size body text, not compact
        // blocks (logo boxes, badges). Catches caption strips above logo blocks. #28
        private const val FURIGANA_BIG_MIN_FRAC = 0.2f

        // Recognition constants (not tunable)
        private const val REC_TARGET_H = 48
        private const val REC_NUM_CLASSES = 18710  // 0=blank, 1..18708=chars, 18709=space (orig id space)
        // Pruned CTC-head width (#39): gemm_8 emits one out per rec_remap entry,
        // and CLASS_REMAP[new] = orig id. Deliberately NOT a constant — the width
        // is derived from the loaded remap (`recNumOutputs`), so re-pruning the
        // head with a wider keep list (#44 added 160 CJK classes) cannot leave the
        // model and this file disagreeing. A stale constant used to be able to
        // disable the engine silently via isReady().
        private const val REC_STRIDE = 8
        /** Streaming batch size (#58 retune knob; was const 4). Batches of line
         * crops created/recycled per batch; per-batch concurrency = fanout. */
        var REC_BATCH_SIZE = 4
        /** Rec ncnn thread count (#58 retune knob; was hardcoded 1, #20). */
        var REC_THREADS = 1
        /** Alternative-list cap everywhere (per-timestep top-K, per-char alts, stitch merges). */
        private const val TOP_K = 15
        /** Single-pass targetW cap and stitch entry gate (targetW = rw*48/rh). #24 */
        private const val LONG_LINE_GATE = 2000
        /** Per-chunk targetW cap in the stitch paths. */
        private const val CHUNK_TARGET_MAX = 480
        /** Stitch best-pair window (last N stitched × first N current). */
        private const val STITCH_WINDOW = 10
        /** Stitch best-pair gates: center distance and prediction overlap. */
        private const val STITCH_MAX_DIST_PX = 30f
        private const val STITCH_MIN_PRED = 0.4f
        /** Stitch append rule: chars past last center + this gap start a new tail. */
        private const val STITCH_APPEND_GAP_PX = 10f
        // Lengthwise squish (#24): resample the length axis before inference (debug slider
        // 0.2–1.0, default 0.5). CTC tolerates it — JP prose holds to 0.5 (knee at 0.33),
        // narrow Latin glyphs are the first casualty (accepted: JP is the target).
	const val GAP_CHAR = '\u25CC'

        // Precomputed pixel math — bit-identical to the replaced float expressions
        // (verified over 2M random pixels in float32: same mults, same add order). #20
        // GRAY_LUT: per-channel 0.299/0.587/0.114 contributions; gray = R+G+B slots.
        private val GRAY_LUT: FloatArray = FloatArray(768) { i ->
            val v = (i % 256).toFloat()
            when (i / 256) { 0 -> v * 0.299f; 1 -> v * 0.587f; else -> v * 0.114f }
        }
        // DET_NORM_LUT: per-channel ImageNet (v/255-mean)/std for det NCHW input.
        private val DET_NORM_LUT: FloatArray = FloatArray(768) { i ->
            val v = (i % 256).toFloat() / 255f
            when (i / 256) {
                0 -> (v - 0.485f) / 0.229f
                1 -> (v - 0.456f) / 0.224f
                else -> (v - 0.406f) / 0.225f
            }
        }

        /** Squished content width for a target width: factor× length, min 8.
         * Model width snaps up to mult-of-8 with zero padding (existing pattern). #24 */
        private fun squishTarget(targetW: Int, factor: Float): Int =
            maxOf(8, (targetW * factor).roundToInt())

        /** Gray contribution sum for one pixel — replaces R*0.299+G*0.587+B*0.114. #20 */
        private fun grayLUT(r: Int, g: Int, b: Int): Float = GRAY_LUT[r] + GRAY_LUT[256 + g] + GRAY_LUT[512 + b]

        /** Pack gray pixels into 3-channel rec input with PP-OCR normalize
         * `(gray/127.5-1)`. Shared by the single-pass batch path and both stitch
         * chunk paths (was 3 copies, #29). Zero-pads columns `[contentW, modelW)`. */
        private fun buildRecInput(pixels: IntArray, contentW: Int, targetH: Int, modelW: Int): FloatArray {
            val inputFloats = FloatArray(1 * 3 * targetH * modelW)
            for (c in 0 until 3) {
                val cOff = c * targetH * modelW
                for (y in 0 until targetH) {
                    for (x in 0 until contentW) {
                        val px = pixels[y * contentW + x]
                        val gray = grayLUT(px shr 16 and 0xFF, px shr 8 and 0xFF, px and 0xFF)
                        inputFloats[cOff + y * modelW + x] = gray / 127.5f - 1f
                    }
                }
            }
            return inputFloats
        }

        // Pooled det buffers — same ThreadLocal pattern as RecNcnn.tlBuffer. detect()
        // repaints the letterbox fully (opaque gray drawColor) and overwrites both
        // arrays end-to-end every call, so reuse is stale-safe. Sized by modelSize (#20, #51).
        private val tlDetImgData = ThreadLocal<FloatArray>()
        private val tlDetPixels = ThreadLocal<IntArray>()
        private val tlDetLetterbox = ThreadLocal<android.graphics.Bitmap>()
    }

    // ——— Tunable getters (live SharedPreferences, defaults from companion) ———
    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    /** Content long side pref; clamped to the net input side at detect() (#51). */
    private val detLongSide: Int
        get() = DEF_DET_LONG_SIDE
    private val detThresh: Float
        get() = prefs.getFloat(PREF_DET_THRESH, DEF_DET_THRESH)
    private val detUnclip: Float
        get() = prefs.getFloat(PREF_DET_UNCLIP, DEF_DET_UNCLIP)
    private val xOverlapThresh: Float
        get() = prefs.getFloat(PREF_X_OVERLAP, DEF_X_OVERLAP)
    /** Live squish factor from debug slider (0.2–1.0, default 0.5). #24 */
    val recSquish: Float
        get() = prefs.getFloat(PREF_REC_SQUISH, DEF_REC_SQUISH).coerceIn(0.2f, 1.0f)

    init {
        try {
            val cacheDir = File(context.cacheDir, "model_cache")
            cacheDir.mkdirs()
            // Clear stale cached models so asset updates take effect
            cacheDir.listFiles()?.forEach { it.delete() }

            // ── Load PP-OCRv6 detection model (ncnn DB) ──
            try {
                detNcnn = DetNcnn.create(context)
                Log.d(TAG, "DetNcnn loaded: $detNcnn")
            } catch (e: Exception) {
                Log.e(TAG, "DetNcnn failed", e)
            }

            // ── Load PP-OCRv6 recognition model (single dynamic-width ncnn, #23) ──
            try {
                recDynNcnn = RecNcnn.create(context, numThreads = REC_THREADS)
                Log.d(TAG, "RecNcnn dyn loaded: $recDynNcnn")
            } catch (e: Exception) {
                Log.e(TAG, "RecNcnn dyn failed", e)
            }

            // ── Load vocabulary ──
            val vocabJson = context.assets.open("PP-OCRv6_small_ncnn/vocab.json")
                .bufferedReader().use { it.readText() }
            val listType = object : TypeToken<List<String>>() {}.type
            ppocrVocab = Gson().fromJson(vocabJson, listType)
            Log.d(TAG, "Vocabulary loaded: ${ppocrVocab.size} entries")

            // ── CTC-head remap: pruned-out id -> orig class id (#39) ──
            val remapTxt = context.assets.open("PP-OCRv6_small_ncnn/rec_remap.txt")
                .bufferedReader().use { it.readText() }
            classRemap = remapTxt.lineSequence()
                .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull() }
                .toList().toIntArray()
            recNumOutputs = classRemap.size
            Log.d(TAG, "Class remap loaded: ${classRemap.size} entries (head width $recNumOutputs)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load models", e)
        }
    }

    fun isReady(): Boolean =
        detNcnn != null && recDynNcnn != null && ppocrVocab.isNotEmpty() && recNumOutputs > 0

    // ═════════════════════════════════════════════════════════════════════════
    //  Detect — DB segmentation → contours → boxes (#28 furigana, unclip)
    // ═════════════════════════════════════════════════════════════════════════

    fun detect(bitmap: Bitmap): List<JpDictRect> {
        val det = detNcnn ?: return emptyList()
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()

        // 1. Resize keeping longest side = min(pref, modelSize), pad to
        // modelSize×modelSize letterbox (#51: net runs at DET_MODEL_SIZE).
        val modelSize = DET_MODEL_SIZE.coerceIn(320, 960)
        val targetLong = minOf(detLongSide, modelSize)
        Log.d(TAG, "detect tunables thresh=$detThresh unclip=$detUnclip longSide=$targetLong xOverlap=$xOverlapThresh modelSize=$modelSize")
        val scale = targetLong.toFloat() / maxOf(origW, origH)
        val resizeW = maxOf((origW * scale).roundToInt(), 32)
        val resizeH = maxOf((origH * scale).roundToInt(), 32)

        val resized = Bitmap.createScaledBitmap(bitmap, resizeW, resizeH, true)

        // Letterbox to modelSize × modelSize (pooled — fully repainted below, never recycled) #20
        val letterbox = tlDetLetterbox.get()
            ?.takeIf { !it.isRecycled && it.width == modelSize && it.height == modelSize }
            ?: android.graphics.Bitmap.createBitmap(modelSize, modelSize, android.graphics.Bitmap.Config.ARGB_8888)
                .also { tlDetLetterbox.set(it) }
        val canvas = android.graphics.Canvas(letterbox)
        canvas.drawColor(Color.rgb(128, 128, 128))
        canvas.drawBitmap(resized, (modelSize - resizeW) / 2f, (modelSize - resizeH) / 2f, null)
        canvas.setBitmap(null)
        resized.recycle()

        // 2. Build NCHW input with ImageNet normalisation [3,S,S].
        // Buffers pooled ThreadLocal; fully overwritten below. #20
        val needFloats = 3 * modelSize * modelSize
        // Exact-size pool match: DetNcnn.infer strict-checks array length, so a
        // 960-sized reuse under an 896 run (or vice versa) hard-fails (#51).
        val imgData: FloatArray = tlDetImgData.get()?.takeIf { it.size == needFloats }
            ?: FloatArray(needFloats).also { tlDetImgData.set(it) }
        val needInts = modelSize * modelSize
        val pixels: IntArray = tlDetPixels.get()?.takeIf { it.size >= needInts }
            ?: IntArray(needInts).also { tlDetPixels.set(it) }
        letterbox.getPixels(pixels, 0, modelSize, 0, 0, modelSize, modelSize)
        // Single-pass NCHW write.
        for (y in 0 until modelSize) {
            for (x in 0 until modelSize) {
                val px = pixels[y * modelSize + x]
                val r = DET_NORM_LUT[px shr 16 and 0xFF]
                val g = DET_NORM_LUT[256 + (px shr 8 and 0xFF)]
                val b = DET_NORM_LUT[512 + (px and 0xFF)]
                val base = y * modelSize + x
                imgData[base] = r
                imgData[modelSize * modelSize + base] = g
                imgData[2 * modelSize * modelSize + base] = b
            }
        }

        // 3. Run detection via ncnn.
        val probArr = det.infer(imgData, modelSize, modelSize) ?: return emptyList()
        // probArr should be S×S float prob map; upsample a downsampled
        // square output (e.g. 240×240) via nearest.
        val outH: Int
        val outW: Int
        val probArrNorm: FloatArray
        if (probArr.size == modelSize * modelSize) {
            outH = modelSize
            outW = modelSize
            probArrNorm = probArr
        } else {
            val dim = kotlin.math.sqrt(probArr.size.toDouble()).toInt()
            if (dim * dim == probArr.size && dim <= modelSize) {
                // Upsample small prob map to modelSize via nearest for postprocess
                outH = modelSize
                outW = modelSize
                probArrNorm = FloatArray(modelSize * modelSize)
                val scaleSmall = dim.toFloat() / modelSize
                for (y in 0 until modelSize) {
                    for (x in 0 until modelSize) {
                        val sx = (x * scaleSmall).toInt().coerceIn(0, dim - 1)
                        val sy = (y * scaleSmall).toInt().coerceIn(0, dim - 1)
                        probArrNorm[y * modelSize + x] = probArr[sy * dim + sx]
                    }
                }
                Log.d(TAG, "detect: upsampled det output ${dim}x${dim} -> ${modelSize}x${modelSize}")
            } else {
                // Fallback: treat as flat and use as is
                val total = probArr.size
                val side = kotlin.math.sqrt(total.toDouble()).toInt()
                outH = side
                outW = side
                probArrNorm = probArr
                Log.w(TAG, "detect: unexpected prob size $total, using ${outH}x${outW}")
            }
        }
        Log.d(TAG, "detect: output size=${probArrNorm.size} expected=${modelSize * modelSize} out=${outW}x${outH}")
        InferLog.add("detect out=${outW}x${outH} size=${probArrNorm.size}")

        val probArrFinal = probArrNorm

        // Prob map stats.
        var pMin = Float.MAX_VALUE
        var pMax = Float.MIN_VALUE
        var pSum = 0f
        var pCount = 0
        for (i in probArrFinal.indices) {
            val v = probArrFinal[i]
            pMin = minOf(pMin, v)
            pMax = maxOf(pMax, v)
            pSum += v
            pCount++
        }
        Log.d(TAG, "prob_map: min=$pMin max=$pMax mean=${if (pCount > 0) pSum / pCount else 0f}")

        // Scale factors from model output to original image (accounting for letterbox)
        val scaleWOut = origW / (modelSize.toFloat())
        val scaleHOut = origH / (modelSize.toFloat())

        // 6. Connected components (contours) via flat flood-fill.
        val visited = ByteArray(outH * outW)
        val rawBoxes = mutableListOf<JpDictRect>()
        // Pre-unclip contour boxes, parallel to rawBoxes — furigana matching runs on these
        // because unclip padding fabricates overlap for stacked fragments. #28
        val rawPreBoxes = mutableListOf<JpDictRect>()
        // Reused Int queue (no per-component alloc).
        val q = IntArray(outH * outW)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val idx = y * outW + x
                if (visited[idx].toInt() != 0 || probArrFinal[idx] <= detThresh) continue

                // Flood-fill via Int queue (y*W+x).
                var qHead = 0
                var qTail = 0
                q[qTail++] = idx
                visited[idx] = 1

                var minX = x; var maxX = x
                var minY = y; var maxY = y
                var pixelCount = 0

                while (qHead < qTail) {
                    val cur = q[qHead++]
                    val cx = cur % outW
                    val cy = cur / outW
                    pixelCount++
                    minX = minOf(minX, cx); maxX = maxOf(maxX, cx)
                    minY = minOf(minY, cy); maxY = maxOf(maxY, cy)

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx; val ny = cy + dy
                            if (nx in 0 until outW && ny in 0 until outH) {
                                val nIdx = ny * outW + nx
                                if (visited[nIdx].toInt() == 0 && probArrFinal[nIdx] > detThresh) {
                                    visited[nIdx] = 1
                                    q[qTail++] = nIdx
                                }
                            }
                        }
                    }
                }

                if (pixelCount < 3) continue // noise filter

                // Convert from output coords to original image coords
                // (output space is letterbox image centered in modelSize×modelSize)
                val imgLeft = (modelSize - resizeW) / 2f
                val imgTop = (modelSize - resizeH) / 2f
                val resScaleW = origW / resizeW.toFloat()
                val resScaleH = origH / resizeH.toFloat()
                val bx = ((minX - imgLeft) * resScaleW).roundToInt().coerceAtLeast(0)
                val by = ((minY - imgTop) * resScaleH).roundToInt().coerceAtLeast(0)
                val bx2 = ((maxX + 1 - imgLeft) * resScaleW).roundToInt()
                    .coerceAtMost(origW.roundToInt())
                val by2 = ((maxY + 1 - imgTop) * resScaleH).roundToInt()
                    .coerceAtMost(origH.roundToInt())

                /** Unclip (PP-OCR DB): dilate the contour box outward by
                 * `expand = area × unclipRatio / perimeter`. E.g. a 100×20 box
                 * (area 2000, perimeter 240) at ratio 1.5 expands by 12.5px per
                 * side. Clamped to the image; boxes < 4px after expansion drop. */
                val bw = (bx2 - bx).toFloat()
                val bh = (by2 - by).toFloat()
                val area = bw * bh
                val perimeter = 2f * (bw + bh)
                val expand = if (perimeter > 0f) area * detUnclip / perimeter else 0f

                val ux = (bx - expand).coerceAtLeast(0f).roundToInt()
                val uy = (by - expand).coerceAtLeast(0f).roundToInt()
                val ux2 = (bx2 + expand).coerceAtMost(origW).roundToInt()
                val uy2 = (by2 + expand).coerceAtMost(origH).roundToInt()

                if (ux2 - ux < 4 || uy2 - uy < 4) continue
                rawPreBoxes.add(JpDictRect(bx, by, bx2, by2))
                rawBoxes.add(JpDictRect(ux, uy, ux2, uy2))
            }
        }

        Log.d(TAG, "detect: raw ${rawBoxes.size} boxes")

        // 6b. Furigana filter (#28) on RAW contour geometry (pre-unclip, pre-merge):
        // unclip padding inflates overlap and fabricates ruby matches out of stacked
        // column fragments (only their padding overlaps). Kept indices gate rawBoxes.
        val keepNonRuby = filterFurigana(rawPreBoxes, rawBoxes, bitmap.width, bitmap.height)
        val keptUnclipped = rawBoxes.filterIndexed { i, _ -> keepNonRuby.getOrElse(i) { true } }
        if (keptUnclipped.size != rawBoxes.size) {
            val dropped = rawPreBoxes.filterIndexed { i, _ -> !keepNonRuby.getOrElse(i) { true } }
            Log.d(TAG, "detect: furigana ${rawBoxes.size} → ${keptUnclipped.size} boxes, dropped=${
                dropped.joinToString(";") { "${it.width()}x${it.height()}@${it.left},${it.top}" }
            }")
        }

        // 7. Post-processing: merge overlapping boxes
        val merged = mergeOverlappingBoxes(keptUnclipped)

        // 8. Filter degenerate boxes
        val filtered = merged.filter { it.width() >= 10 && it.height() >= 10 }

        // 9. Shrink vertical box widths by 10% (centered)
        val shrunk = filtered.map { box ->
            if (box.height() > box.width()) {
                val shrink = (box.width() * 0.05f).roundToInt()
                JpDictRect(box.left + shrink, box.top, box.right - shrink, box.bottom)
            } else box
        }

        // 9b. Ruby-gutter trim (#48): furigana-widened vertical boxes are cut
        // back to the main column HERE. Jobs, crops, char boxes, LineResult
        // and overlay all derive from the returned boxes, so the ruby width
        // can never re-enter downstream (no mask-then-keep-geometry).
        val trimmed = if (RUBY_TRIM_VERTICAL) trimRubyGutterVertical(shrunk, bitmap) else shrunk

        // 10. Split overlapping horizontal boxes at overlap midpoint
        val horizontals = trimmed.mapIndexedNotNull { i, b ->
            if (b.width() >= b.height()) i to b else null
        }
        val splitBoxes = trimmed.toMutableList()
        for (i in horizontals.indices) {
            for (j in (i + 1) until horizontals.size) {
                val ai = horizontals[i].first
                val bi = horizontals[j].first
                val a = splitBoxes[ai]; val b = splitBoxes[bi]

                val hOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
                if (hOverlap <= 0) continue

                val (upper, lower) = if (a.top <= b.top) ai to bi else bi to ai
                val upperBottom = splitBoxes[upper].bottom
                val lowerBottom = splitBoxes[lower].bottom

                if (upperBottom > splitBoxes[lower].top) {
                    val overlapMid = (splitBoxes[lower].top + minOf(upperBottom, lowerBottom)) / 2
                    splitBoxes[upper] = JpDictRect(
                        splitBoxes[upper].left, splitBoxes[upper].top,
                        splitBoxes[upper].right, overlapMid.coerceAtLeast(splitBoxes[upper].top + 1)
                    )
                    splitBoxes[lower] = JpDictRect(
                        splitBoxes[lower].left, splitBoxes[upper].bottom,
                        splitBoxes[lower].right, lowerBottom
                    )
                }
            }
        }

        // 11. Sort: horizontal top-bottom/left-right, vertical right-left/top-bottom
        val sorted = sortDetectedBoxes(splitBoxes)
        Log.d(TAG, "detect: final ${sorted.size} boxes")
        InferLog.add("detect final=${sorted.size} boxes")
        return sorted
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Box merging & sorting
    // ═════════════════════════════════════════════════════════════════════════

    private fun mergeOverlappingBoxes(boxes: List<JpDictRect>): List<JpDictRect> {
        if (boxes.size < 2) return boxes
        val result = mutableListOf<JpDictRect>()
        val handled = BooleanArray(boxes.size)
        val sortedBoxes = boxes.withIndex().sortedByDescending { it.value.width() * it.value.height() }

        for (i in sortedBoxes.indices) {
            val idx = sortedBoxes[i].index
            if (handled[idx]) continue
            var current = sortedBoxes[i].value
            handled[idx] = true

            for (j in i + 1 until sortedBoxes.size) {
                val jdx = sortedBoxes[j].index
                if (handled[jdx]) continue

                if (shouldMerge(current, sortedBoxes[j].value)) {
                    current = JpDictRect(
                        minOf(current.left, sortedBoxes[j].value.left),
                        minOf(current.top, sortedBoxes[j].value.top),
                        maxOf(current.right, sortedBoxes[j].value.right),
                        maxOf(current.bottom, sortedBoxes[j].value.bottom)
                    )
                    handled[jdx] = true
                }
            }
            result.add(current)
        }
        return result
    }

    private fun shouldMerge(a: JpDictRect, b: JpDictRect): Boolean {
        val ix = maxOf(a.left, b.left)
        val iy = maxOf(a.top, b.top)
        val ix2 = minOf(a.right, b.right)
        val iy2 = minOf(a.bottom, b.bottom)
        if (ix >= ix2 || iy >= iy2) return false

        val interArea = (ix2 - ix).toFloat() * (iy2 - iy)
        val minArea = minOf(a.width() * a.height(), b.width() * b.height())
        if (minArea <= 0) return false

        val iom = interArea / minArea.toFloat()
        if (iom < xOverlapThresh) return false

        val yDiff = abs((a.top + a.bottom) / 2f - (b.top + b.bottom) / 2f)
        val avgH = (a.height() + b.height()) / 2f
        return yDiff <= avgH
    }

    /** Shared orientation rule (#28): near-square boxes count as horizontal so lone
     * upright characters never enter the model sideways. */
    private fun isVerticalBox(box: JpDictRect): Boolean =
        box.height().toFloat() >= box.width() * VERTICAL_MIN_ASPECT

    /** Near-square (single-kanji-like) box: checked against both furigana rules. #28 */
    private fun isSquareBox(box: JpDictRect): Boolean {
        val w = box.width(); val h = box.height()
        return minOf(w, h).toFloat() >= maxOf(w, h) / VERTICAL_MIN_ASPECT
    }

    private fun overlapLen(a1: Int, a2: Int, b1: Int, b2: Int): Int =
        (minOf(a2, b2) - maxOf(a1, b1)).coerceAtLeast(0)

    private fun gapLen(a1: Int, a2: Int, b1: Int, b2: Int): Int =
        maxOf(0, maxOf(a1, b1) - minOf(a2, b2))

    /** Tiny vertical box hugging a much larger vertical box (either side). #28
     * Center must lie OUTSIDE the big box: stacked column fragments (tail of the
     * column above/below, overlapping only via unclip padding) share its x-range.
     * Size/center/overlap use RAW contour geometry; gap uses UNCLIPPED (raw gutters
     * are real pixels, unclipped closes them to ruby distance). */
    private fun isRubyVertical(
        sRaw: JpDictRect, bRaw: JpDictRect, sUn: JpDictRect, bUn: JpDictRect, imgH: Int,
    ): Boolean {
        if (bRaw.height() < imgH * FURIGANA_BIG_MIN_FRAC) return false
        if (sRaw.height() >= bRaw.height() * FURIGANA_SIZE_RATIO) return false
        if (sRaw.height() >= imgH * FURIGANA_MAX_FRAC) return false
        if (sUn.width() >= bUn.width() * FURIGANA_WIDTH_RATIO) return false
        val cx = (sRaw.left + sRaw.right) / 2
        if (cx >= bRaw.left && cx <= bRaw.right) return false
        if (gapLen(sUn.left, sUn.right, bUn.left, bUn.right) > bUn.width() * FURIGANA_GAP_RATIO) return false
        if (overlapLen(sRaw.top, sRaw.bottom, bRaw.top, bRaw.bottom) < sRaw.height() * FURIGANA_OVERLAP_RATIO) return false
        return true
    }

    /** Tiny horizontal box right above a much larger horizontal box. #28 (same split).
     * Judged by THINNESS alone, not length: horizontal ruby runs long or short, but its
     * glyphs are always smaller. (Vertical keeps an additional shortness gate — narrow
     * full-height columns exist; horizontal has no such case.) */
    private fun isRubyHorizontal(
        sRaw: JpDictRect, bRaw: JpDictRect, sUn: JpDictRect, bUn: JpDictRect, imgW: Int, imgH: Int,
    ): Boolean {
        if (bRaw.width() < imgW * FURIGANA_BIG_MIN_FRAC) return false
        if (sRaw.height() >= bRaw.height() * FURIGANA_THIN_RATIO) return false
        if (sRaw.height() >= imgH * FURIGANA_MAX_FRAC) return false
        if (sUn.height() >= bUn.height() * FURIGANA_HSHORT_RATIO) return false
        // Above-ness on RAW geometry: unclip grows both boxes toward each other (~18px
        // mutual encroachment here), flipping genuinely-above ruby to overlapping. #28
        if (sRaw.bottom > bRaw.top + 2) return false
        if (bUn.top - sUn.bottom > bUn.height() * FURIGANA_GAP_RATIO) return false
        if (overlapLen(sRaw.left, sRaw.right, bRaw.left, bRaw.right) < sRaw.width() * FURIGANA_OVERLAP_RATIO) return false
        return true
    }

    /** Keep-flags for likely-furigana boxes. raw/uncl are index-aligned (raw contours
     * vs unclipped detect boxes). #28 */
    private fun filterFurigana(raw: List<JpDictRect>, uncl: List<JpDictRect>, imgW: Int, imgH: Int): BooleanArray {
        if (raw.size < 2) return BooleanArray(raw.size) { true }
        return BooleanArray(raw.size) { i ->
            val small = raw[i]
            val checkVert = isVerticalBox(small) || isSquareBox(small)
            val checkHoriz = !isVerticalBox(small) || isSquareBox(small)
            !(raw.indices.any { j ->
                j != i && (
                    (checkVert && isVerticalBox(raw[j]) && isRubyVertical(raw[i], raw[j], uncl[i], uncl[j], imgH)) ||
                    (checkHoriz && !isVerticalBox(raw[j]) && isRubyHorizontal(raw[i], raw[j], uncl[i], uncl[j], imgW, imgH))
                )
            })
        }
    }

    /** Ruby-gutter trim for vertical lines (#48): detector boxes that swallowed
     * the furigana strip come out ~2x normal column width (measured 127px vs
     * 61px median on ruby_ebook). The trim is GEOMETRIC — the right side of
     * the box is cut off here in detect(), so jobs, crops, char boxes,
     * LineResult and overlay all derive from the trimmed box and the ruby
     * width can never re-enter. (Masking pixels while keeping the wide box
     * keeps the bad geometry and its timestep crush — rejected per owner.)
     *
     * A vertical box is a candidate when wider than 1.35x the median vertical
     * width with a removable strip >= 12px. The cut is image-evidence:
     * per-column ink profile over the full box height (polarity + thresholds
     * shared with snapping); the leftmost run of >= 3 near-empty (< 4%) columns
     * inside [L+0.40W, L+0.80W] with ink following it is the main/ruby gutter —
     * cut at its start. Touching ruby with no clean gutter but a thin spot
     * (window minimum < 6%) falls back to half width ("remove the right
     * half"); solid-wide boxes (headings) are left alone. */
    private fun trimRubyGutterVertical(boxes: List<JpDictRect>, bitmap: Bitmap): List<JpDictRect> {
        val vertW = boxes.filter { isVerticalBox(it) }.map { it.width() }.sorted()
        if (vertW.size < 2) return boxes
        val medW = vertW[vertW.size / 2]
        if (medW <= 0) return boxes
        return boxes.map { box ->
            if (!isVerticalBox(box)) return@map box
            val w = box.width()
            if (w <= medW * 1.35f || w - medW < 12) return@map box
            val cut = findRubyGutterCut(box, bitmap) ?: return@map box
            if (cut <= box.left + 20 || cut >= box.right - 8) return@map box
            if (cut - box.left < (w * 0.4f).roundToInt()) return@map box
            Log.d(TAG, "detect: rubyTrim ${box.width()}x${box.height()}@${box.left},${box.top} → w=${cut - box.left} (medW=$medW)")
            InferLog.add("rubyTrim w=$w→${cut - box.left} @${box.left},${box.top}")
            JpDictRect(box.left, box.top, cut, box.bottom)
        }
    }

    /** Cut x (global coords) for a ruby-widened vertical box, or null to keep.
     * Gutter cut preferred; half-width fallback only on a thin spot. */
    private fun findRubyGutterCut(box: JpDictRect, bitmap: Bitmap): Int? {
        val x0 = box.left.coerceIn(0, bitmap.width - 1)
        val x1 = box.right.coerceIn(1, bitmap.width)
        val y0 = box.top.coerceIn(0, bitmap.height - 1)
        val y1 = box.bottom.coerceIn(1, bitmap.height)
        val bw = x1 - x0
        val bh = y1 - y0
        if (bw < 24 || bh < 64) return null
        val px = IntArray(bw * bh)
        try {
            bitmap.getPixels(px, 0, bw, x0, y0, bw, bh)
        } catch (_: Exception) {
            return null
        }
        val lum = FloatArray(bw * bh) { i ->
            (((px[i] shr 16) and 0xFF) + (((px[i] shr 8) and 0xFF)) + (px[i] and 0xFF)) / 3f
        }
        // Background polarity from border samples (shared with snapping).
        val border = mutableListOf<Float>()
        var bi = 0
        while (bi < bw) {
            border.add(lum[bi]); border.add(lum[(bh - 1) * bw + bi]); bi += 7
        }
        bi = 0
        while (bi < bh) {
            border.add(lum[bi * bw]); border.add(lum[bi * bw + bw - 1]); bi += 7
        }
        border.sort()
        if (border.isEmpty()) return null
        val bgLight = border[border.size / 2] > 128f
        fun isInk(v: Float) = if (bgLight) v < 110f else v > 145f
        // Per-column ink fraction over the full box height.
        val frac = FloatArray(bw) { x ->
            var m = 0
            for (y in 0 until bh) if (isInk(lum[y * bw + x])) m++
            m.toFloat() / bh.toFloat()
        }
        val lo = (bw * 0.40f).toInt().coerceIn(0, bw - 1)
        val hi = (bw * 0.80f).toInt().coerceIn(lo + 1, bw)
        // Leftmost clean gutter with ink following it (not trailing padding).
        var x = lo
        while (x + 2 < hi) {
            if (frac[x] < 0.04f && frac[x + 1] < 0.04f && frac[x + 2] < 0.04f) {
                var follows = false
                for (k in x + 3 until minOf(x + 11, bw)) {
                    if (frac[k] >= 0.04f) { follows = true; break }
                }
                if (follows) return x0 + x
                x += 3
            } else x++
        }
        // Touching-ruby fallback: thin spot → half width ("remove the right half").
        var minF = Float.MAX_VALUE
        for (k in lo until hi) minF = minOf(minF, frac[k])
        if (minF < 0.06f) return x0 + bw / 2
        return null
    }

    private fun sortDetectedBoxes(boxes: List<JpDictRect>): List<JpDictRect> {
        val horizontal = boxes.filter { !isVerticalBox(it) }
            .sortedWith(compareBy({ it.top }, { it.left }))
        val vertical = boxes.filter { isVerticalBox(it) }
            .sortedWith(compareByDescending<JpDictRect> { it.right }.thenBy { it.top })
        return horizontal + vertical
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Rec — single-pass batch + streaming + CTC + char boxes
    // ═════════════════════════════════════════════════════════════════════════

    data class PPOcrResult(
        val text: String,
        val alternatives: List<List<Pair<Char, Float>>>,
        val charCols: FloatArray,   // CTC timestep positions
        val seqLenTotal: Int,
        /** Top-15 alternatives for EVERY timestep (including blanks), for cache re-decode. */
        val rawAlternatives: List<List<Pair<Char, Float>>> = emptyList(),
    )

    /** Top-15 char alternatives for one CTC timestep, descending by logit. Shared
     * by the batch path, both stitch chunk paths, and [ctcDecode] (was 4 copies). */
    private fun top15Alternatives(slice: FloatArray): List<Pair<Char, Float>> {
        val pq = java.util.PriorityQueue<Int>(TOP_K + 1, compareBy { slice[it] })
        for (k in slice.indices) { pq.add(k); if (pq.size > TOP_K) pq.poll() }
        return pq.toList().sortedByDescending { slice[it] }.map { decodeChar(remapClass(it)) to slice[it] }
    }

    /** Sub-column peak offset (#49): parabolic interpolation of the winning
     * class value across neighboring timesteps. CTC columns quantize truth
     * peaks to integers (up to 0.5 col ≈ 0.2em error); the fractional peak
     * recovers most of it. Returns 0 when the peak is flat, at a boundary,
     * or neighbor values are unavailable. */
    private fun peakOffset(v0: Float, v1: Float, v2: Float): Float {
        val denom = v0 - 2f * v1 + v2
        if (denom >= -1e-6f) return 0f
        // Prominence gate (#49): on flat plateaus any nonzero offset is noise
        // (it regressed clean truth boxes 0.7 -> 1.6px). Fire only when the
        // peak stands clearly above BOTH neighbors.
        if (minOf(v1 - v0, v1 - v2) <= 1.0f) return 0f
        return (0.5f * (v0 - v2) / denom).coerceIn(-0.5f, 0.5f)
    }

    /** Pruned-out id -> orig class id (#39); identity fallback if remap failed to load. */
    private fun remapClass(prunedIdx: Int): Int = classRemap.getOrElse(prunedIdx) { prunedIdx }

    /** Resize [src] to `targetW×targetH`, run dynamic-width rec, CTC-decode.
     *
     * Shared single-crop inference for the batch path and both stitch chunk
     * paths (was 3 copies). Model width snaps up to mult-of-8 with zero padding;
     * `actualSeqLen = ceil(targetW/8)` trims the padding timesteps. Returns null
     * when inference fails (callers fall back: batch emits empty, stitch falls
     * through to crush). Does NOT recycle [src] — callers own their bitmaps.
     * Cooperative cancellation via `coroutineContext.ensureActive()`. */
    private suspend fun inferResizedRec(src: Bitmap, targetW: Int, targetH: Int, engine: RecNcnn? = null): PPOcrResult? {
        coroutineContext.ensureActive()
        val recNcnn = engine ?: recDynNcnn ?: return null
        val modelW = ((targetW + 7) / 8) * 8
        val resized = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        resized.recycle()
        val inputFloats = buildRecInput(pixels, targetW, targetH, modelW)
        val seqLen = modelW / REC_STRIDE
        val actualSeqLen = maxOf(1, ceil(targetW / REC_STRIDE.toFloat()).toInt())
        // Preferred path (#42): native top-15 per timestep — downloads
        // seqLen*30 floats instead of seqLen*13193 (up to ~880x smaller).
        // Falls back to full logits + Java top-15 if the native entry is missing.
        val packed = try { recNcnn.inferTopK(inputFloats, modelW, targetH) } catch (_: UnsatisfiedLinkError) { null }
        if (packed != null && packed.size == seqLen * TOP_K * 2) {
            // Native emits descending top-15 with lowest-id-wins ties; entry 0
            // is the argmax, so decode text is identical to the full-logits path.
            val topPruned = Array(actualSeqLen) { t ->
                IntArray(TOP_K) { k -> packed[(t * TOP_K + k) * 2].toInt() }
            }
            val rawAlts = (0 until actualSeqLen).map { t ->
                (0 until TOP_K).map { k ->
                    decodeChar(remapClass(topPruned[t][k])) to packed[(t * TOP_K + k) * 2 + 1]
                }
            }
            val decoded = ctcDecodeTopK(topPruned, rawAlts, actualSeqLen, 0f)
            return decoded.copy(rawAlternatives = rawAlts)
        }
        if (packed != null) Log.w(TAG, "recNcnn w$modelW topK bad size ${packed.size} — full-logits fallback")
        if (packed != null) InferLog.add("rec w=$modelW topK BAD size=${packed.size} expect=${seqLen * TOP_K * 2}")
        val flatOutput = recNcnn.infer(inputFloats, modelW, targetH) ?: run {
            Log.e(TAG, "recNcnn w$modelW infer null")
            InferLog.add("rec w=$modelW infer NULL")
            return null
        }
        // Head width comes from the loaded remap; this is also the one place the
        // model and the remap are checked against each other at runtime.
        val numOut = recNumOutputs
        if (flatOutput.size != seqLen * numOut) {
            Log.e(TAG, "recNcnn w$modelW bad output ${flatOutput.size} vs ${seqLen * numOut}")
            InferLog.add("rec w=$modelW BAD out=${flatOutput.size} expect=${seqLen * numOut}")
            return null
        }
        val cropLogits = Array(actualSeqLen) { t ->
            FloatArray(numOut) { c -> flatOutput[t * numOut + c] }
        }
        val rawAlts = (0 until actualSeqLen).map { t -> top15Alternatives(cropLogits[t]) }
        val decoded = ctcDecode(cropLogits, actualSeqLen, numOut, 0f, actualSeqLen)
        return decoded.copy(rawAlternatives = rawAlts)
    }

    /**
     * Run CTC recognition over dynamic-width ncnn rec.
     *
     * Portrait crops rotate 270° so the model always sees horizontal text.
     * Lines wider than 2000 (at 48px height) go through the stitch paths;
     * everything else takes the single-pass path below with live [recSquish]
     * applied pre-inference (#24). Cooperative cancellation: checks
     * coroutineContext.isActive.
     */
    /** One batch end-to-end: crops, inference on [engine], emit, recycle.
     * Cooperative cancellation via ensureActive; crops always recycled. */
    private suspend fun processOneBatch(
        batchIdx: Int,
        batch: List<Job>,
        engine: RecNcnn,
        bitmap: Bitmap,
        mainHandler: android.os.Handler,
        onLinesRecognized: (List<Pair<Int, LineResult>>) -> Unit,
        fanout: Int = 4,
    ) {
        coroutineContext.ensureActive()
        val tBatch = System.nanoTime()
        // Create crops per batch (4 bitmaps pinned at a time).
        val cropsWithJobs = batch.mapNotNull { job ->
            val cropX = maxOf(job.bbox.left, 0)
            val cropY = maxOf(job.bbox.top, 0)
            val cropW = minOf(bitmap.width - cropX, job.bbox.width()).coerceAtLeast(1)
            val cropH = minOf(bitmap.height - cropY, job.bbox.height()).coerceAtLeast(1)
            if (cropW < 4 || cropH < 4) return@mapNotNull null
            val crop = try {
                Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
            } catch (e: Exception) {
                Log.e(TAG, "createBitmap failed for $job", e)
                return@mapNotNull null
            }
            if (crop.width < 4 || crop.height < 4) { crop.recycle(); return@mapNotNull null }
            job to crop
        }
        if (cropsWithJobs.isEmpty()) return
        val crops = cropsWithJobs.map { it.second }
        val batchJobs = cropsWithJobs.map { it.first }
        try {
            // Early exit if cancelled before batch
            if (!coroutineContext.isActive) {
                crops.forEach { try { it.recycle() } catch (_: Exception) {} }
                return
            }
            // Stream per line as each infer completes (completion order, not
            // batch order) — same 4-concurrent throughput, faster first
            // result. Results stay keyed by job idx. #21
            var doneLines = 0
            val emitLine = emit@{ index: Int, result: PPOcrResult ->
                val job = batch.getOrNull(index) ?: return@emit
                if (result.text.isEmpty()) return@emit

                // Crop pixels for idea-4 snapping (crop alive until batch
                // recycle below; null-safe when sizes mismatch).
                val crop = crops.getOrNull(index)
                var snapPx: IntArray? = null
                var snapW = 0
                var snapH = 0
                if (BOX_LAYOUT_MODE == BOX_SNAP && crop != null && !crop.isRecycled && crop.width >= 8 && crop.height >= 8) {
                    try {
                        snapW = crop.width; snapH = crop.height
                        val arr = IntArray(snapW * snapH)
                        crop.getPixels(arr, 0, snapW, 0, 0, snapW, snapH)
                        snapPx = arr
                    } catch (_: Exception) {
                        snapPx = null
                    }
                }
                // Vertical punctuation (#56, #63): PP-OCR emits ASCII `?` where
                // JP wants fullwidth `？`, and horizontal `…`/`‥` where
                // vertical text wants `︙`/`︰`. All three lack a `vert`
                // alternate and mis-center in the vertical em box. Normalizes
                // BEFORE char boxes so the substitutes get full-em metrics
                // (lookup folds them back via JapaneseUtil.normalize, so
                // dictionary search is unaffected).
                // Covers single-pass and long-line stitch paths (both emit here).
                val recText = if (job.isVertical) JapaneseUtil.verticalPunctuation(result.text) else result.text
                val recAlts = if (job.isVertical) {
                    result.alternatives.map { alts ->
                        alts.map { (c, s) -> JapaneseUtil.verticalPunctuationChar(c) to s }.toMutableList()
                    }
                } else {
                    result.alternatives.map { it.toMutableList() }
                }
                val recRaw = if (job.isVertical) {
                    result.rawAlternatives.map { alts ->
                        alts.map { (c, s) -> JapaneseUtil.verticalPunctuationChar(c) to s }
                    }
                } else {
                    result.rawAlternatives.map { it.toList() }
                }
                val charBoxes = computeCharBoxes(
                    recText, result.charCols, result.seqLenTotal,
                    job.bbox.left, job.bbox.top,
                    job.bbox.width(), job.bbox.height(),
                    job.isVertical,
                    snapPx, snapW, snapH,
                )

                // Vertical lines keep horizontal chars end-to-end (#47): the
                // overlay renderer applies the font's vert subs at draw time.
                val finalText = recText
                val finalAlts = recAlts

                val lineResult = LineResult(
                    text = finalText,
                    charBoxes = charBoxes,
                    alternatives = finalAlts,
                    isVertical = job.isVertical,
                    rawAlternatives = recRaw,
                    seqLenTotal = result.seqLenTotal,
                    cropW = job.bbox.width(),
                    cropH = job.bbox.height(),
                    cropX = job.bbox.left,
                    cropY = job.bbox.top,
                    charCols = result.charCols,
                )
                doneLines++
                InferLog.add("line idx=${job.idx} len=${lineResult.text.length} vert=${lineResult.isVertical}")
                mainHandler.post { onLinesRecognized(listOf(job.idx to lineResult)) }
            }
            recognizePpocrBatch(crops, engine, fanout) { index, result -> emitLine(index, result) }
            val elapsed = (System.nanoTime() - tBatch) / 1_000_000
            Log.d(TAG, "Batch $batchIdx ${batch.size} jobs → $doneLines lines in ${elapsed}ms")
            InferLog.add("batch $batchIdx jobs=${batch.size} lines=$doneLines ${elapsed}ms")
            // Per-batch recycle.
            crops.forEach { try { it.recycle() } catch (_: Exception) {} }
        } catch (e: Exception) {
            Log.e(TAG, "Batch $batchIdx failed", e)
            // Ensure crops recycled even on failure
            try { crops.forEach { it.recycle() } } catch (_: Exception) {}
        }
    }

    /**
     * @param fanout max concurrent line infers in this batch (waves run
     * sequentially; completion-order streaming holds within a wave).
     */
    private suspend fun recognizePpocrBatch(
        crops: List<Bitmap>,
        engine: RecNcnn? = null,
        fanout: Int = 4,
        onEach: ((index: Int, result: PPOcrResult) -> Unit)? = null,
    ): List<PPOcrResult> {
        coroutineContext.ensureActive()
        val numCrops = crops.size
        if (numCrops == 0 || ppocrVocab.isEmpty() || (engine ?: recDynNcnn) == null) return emptyList()

        val targetH = REC_TARGET_H

        val ordered = arrayOfNulls<PPOcrResult>(numCrops)
        for (wave in (0 until numCrops).chunked(fanout.coerceAtLeast(1))) {
            coroutineContext.ensureActive()
            coroutineScope {
            val deferreds = wave.map { ci ->
                async(Dispatchers.Default) {
                    coroutineContext.ensureActive()
                    val crop = crops[ci]
                    val cw = crop.width; val ch = crop.height
                    if (cw < 4 || ch < 4) return@async null as PPOcrResult?

            val rotated: Bitmap = if (ch >= cw * 3 / 2) {
                val mat = android.graphics.Matrix().apply { postRotate(270f) }
                Bitmap.createBitmap(crop, 0, 0, cw, ch, mat, true)
            } else crop

            val rw = rotated.width; val rh = rotated.height
            // ——— Long-line split for extreme aspects (rw*48/rh>2000) — PP-OCR crush fix ———
            // Exact-width inference validated to aspect ~1992 on tategaki ebook lines (#24);
            // cap + gate rounded up to an 8-divisible 2000. Very long lines crush timesteps.
            // Split into overlapping exact-width chunks (10% fallback overlap), then stitch.
            // Threshold 2000 to avoid over-splitting normal lines while fixing extremes.
            val isLongHoriz = rw >= rh * 3 / 2 && (rw.toFloat() * targetH / rh.toFloat() > LONG_LINE_GATE)
            val isLongVert = rh >= rw * 3 / 2 && (rh.toFloat() * targetH / rw.toFloat() > LONG_LINE_GATE)
            if (isLongHoriz || isLongVert) {
                val stitched = if (isLongHoriz) {
                    recognizeAndStitchLongHoriz(rotated, targetH, engine)
                } else {
                    recognizeAndStitchLongVert(rotated, targetH, engine)
                }
                if (stitched != null) {
                    if (rotated !== crop) rotated.recycle()
                    return@async stitched
                }
                Log.w(TAG, "long-line stitch failed rw=$rw rh=$rh — falling through to crush")
            }
            // Dynamic width (#23): exact targetW capped at LONG_LINE_GATE (validated #24),
            // then squish (#24) applied pre-inference; stitch paths skip squish.
            // Model width snaps to mult-of-8 (≤7px pad).
            val targetW = maxOf(4, minOf(LONG_LINE_GATE,
                (rw.toFloat() * targetH / rh.toFloat()).roundToInt()
            ))
            val sqTarget = squishTarget(targetW, recSquish).let {
                // Crush floor (#48): squish must leave >= 32 timesteps; short
                // dense lines (e.g. ruby-widened vertical crops at targetW 363
                // -> 23 steps for 16 chars) keep full resolution instead.
                if (it / REC_STRIDE < 32) targetW else it
            }
            InferLog.add("crop rw=$rw rh=$rh targetW=$targetW sq=$sqTarget seq=${sqTarget / REC_STRIDE}")
            val result = inferResizedRec(rotated, sqTarget, targetH, engine)
            if (rotated !== crop) rotated.recycle()
            if (result == null) {
                Log.e(TAG, "recDynNcnn w$sqTarget infer failed — skip crop")
                return@async null
            }
                    return@async result
                }
            }
            // Await in completion order so callers can stream per-line results (#21, §D5):
            // the returned list stays index-aligned; onEach fires on the selecting
            // worker and recognizeStreaming re-posts to the main thread.
            val pending = deferreds.mapIndexed { wi, d -> d to wave[wi] }.toMap().toMutableMap()
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                val (done, res) = select<Pair<Deferred<PPOcrResult?>, PPOcrResult?>> {
                    pending.keys.forEach { d -> d.onAwait { d to it } }
                }
                val ci = pending.remove(done) ?: continue
                val final = res ?: PPOcrResult("", emptyList(), floatArrayOf(), 0)
                ordered[ci] = final
                if (res != null) {
                    try { onEach?.invoke(ci, res) } catch (_: Exception) {}
                }
            }
            } // end wave scope
        } // end waves
        return ordered.map { it ?: PPOcrResult("", emptyList(), floatArrayOf(), 0) }
    }

    // ——— Long-line stitch (lines wider than 2000 @48px; CTC crush fix) ———
    // Split into overlapping exact-width chunks (≤480 targetW each), infer each
    // via inferResizedRec UNSQUISHED, then stitch (Phase 2). Chunks overlap by
    // anchor (below) with a 10% fallback step.
    /** One inferred chunk: decoded text plus the geometry to place it globally.
     * `chunkW`/`offsetX`/`offsetY` are full-res source pixels; `charCols` are
     * chunk-local timesteps; `actualSeqLen` trims mult-of-8 padding. */
    private data class ChunkInfo(
        val text: String,
        val charCols: FloatArray,
        val altsPerChar: List<List<Pair<Char, Float>>>,
        val rawAltsPerTimestep: List<List<Pair<Char, Float>>>,
        val actualSeqLen: Int,
        val targetW: Int,
        val chunkW: Int,
        val offsetX: Int,
        val offsetY: Int,
    )

    // Stitch chunks stay UNSQUISHED at full resolution: anchor/stitch geometry
    // (localXLeft, offsetGeomT, totalSeqLen) is all full-res timesteps, while the
    // single-pass batch path applies recSquish pre-inference. CTC tolerates the
    // squish for JP prose (holds to 0.5, knee at 0.33); narrow Latin glyphs go
    // first (accepted: JP is the target). #24
    /** Stitch a long horizontal line (rw*48/rh > 2000).
     *
     * Phase 1 chunks left→right: each chunk's next start anchors on its
     * second-to-last decoded char center (`localXLeft = (t+0.5)/seqLen*cw`),
     * minus a 10%-of-height margin; degenerate anchors fall back to a 90% step.
     * Phase 2 aligns chunks by identical timestep size (`rh/6` px): for each new
     * Phase 2 aligns chunks by identical timestep size (`rh/6` px): for each new
     * chunk, the best pair in the [STITCH_WINDOW] overlap window with center
     * distance ≤ [STITCH_MAX_DIST_PX] and prediction overlap ≥ [STITCH_MIN_PRED]
     * wins (`score = 0.3·(1-dist/30) + 0.7·pred`); the winner's alternatives merge
     * via [interleaveAlternatives] and later chars re-base onto it. No winner →
     * append chars past the last global center (+10px), or single-char chunks
     * unconditionally; total stall → +1-timestep fallback offset. Double spaces
     * collapse at the end. `charCols` stay global timesteps scaled by
     * `totalSeqLen = ceil(rw*48/rh/8)`. */
    private suspend fun recognizeAndStitchLongHoriz(
        rotated: Bitmap, targetH: Int, engine: RecNcnn? = null
    ): PPOcrResult? {
        coroutineContext.ensureActive()
        val rw = rotated.width; val rh = rotated.height
        val scale = targetH.toFloat() / rh.toFloat()
        val maxChunkW = (480 / scale).toInt().coerceAtLeast(64)
        if (maxChunkW <= 0) return null
        val chunkMargin = (rh * 0.1f).toInt().coerceAtLeast(2)

        // ——— Phase 1: chunk with anchor-driven nextX (second-to-last char) ———
        val chunks = mutableListOf<ChunkInfo>()
        var x = 0
        while (x < rw) {
            coroutineContext.ensureActive()
            val w = minOf(maxChunkW, rw - x)
            if (w < 16) break
            val chunkBmp = Bitmap.createBitmap(rotated, x, 0, w, rh)
            val cw = chunkBmp.width; val ch = chunkBmp.height
            // Preserve aspect w → targetW via cw*48/ch (not stretch); cap at CHUNK_TARGET_MAX.
            val targetW = minOf(CHUNK_TARGET_MAX, (cw.toFloat() * targetH / ch.toFloat()).roundToInt().coerceAtLeast(4))
            val decoded = inferResizedRec(chunkBmp, targetW, targetH, engine)
            chunkBmp.recycle()
            if (decoded == null) { Log.e(TAG, "recNcnn chunk w$targetW infer null"); return null }
            val actualSeqLen = decoded.seqLenTotal
            val rawAlts = decoded.rawAlternatives
            chunks.add(ChunkInfo(decoded.text, decoded.charCols, decoded.alternatives, rawAlts, actualSeqLen, targetW, cw, x, 0))
            if (x + w >= rw) break
            // Anchor on the second-to-last char: it is fully observed, the last
            // char may be cut by the chunk edge.
            val txt = decoded.text
            if (txt.isNotEmpty() && decoded.charCols.isNotEmpty()) {
                val anchorIdx = if (txt.length >= 2) txt.length - 2 else 0
                val anchorT = decoded.charCols.getOrNull(anchorIdx) ?: decoded.charCols.last()
                // localXLeft in chunk pixel coords: (t+0.5)/actualSeqLen * cw (center)
                val localXLeft = ((anchorT + 0.5f) / actualSeqLen.toFloat()) * cw
                val nextX = (x + localXLeft.toInt() - chunkMargin).coerceAtLeast(0)
                if (nextX <= x || nextX >= x + w - 10) {
                    x += (w * 0.9f).toInt().coerceAtLeast(16)
                } else {
                    x = nextX
                }
            } else {
                x += (w * 0.9f).toInt().coerceAtLeast(16)
            }
        }
        if (chunks.isEmpty()) return null
        if (chunks.size == 1) {
            val c = chunks[0]
            Log.d(TAG, "long-line stitch horiz rw=$rw rh=$rh chunks=1 stitchedLen=${c.text.length} seqLen=${c.actualSeqLen} text=${c.text.take(40)}")
            return PPOcrResult(c.text, c.altsPerChar, c.charCols, c.actualSeqLen, c.rawAltsPerTimestep)
        }
        // ——— Phase 2: stitch via anchor alignment with identical timestep size ———
        // Each timestep is rh/6 px source (48px height / stride 8); both chunks
        // share the size. The second chunk's anchor lands exactly over the
        // first's, then positions progress normally; totalSeqLen rescales to the
        // full line (ceil(rw*48/rh/8)).
        val timestepPx = rh.toFloat() / 6f
        val totalSeqLen = maxOf(1, ceil(rw.toFloat() * targetH.toFloat() / rh.toFloat() / REC_STRIDE.toFloat()).toInt())
        val chunkGlobalCenters = chunks.map { ci ->
            ci.charCols.map { t -> ci.offsetX.toFloat() + (t + 0.5f) * timestepPx }
        }
        var stitchedText = StringBuilder(chunks[0].text)
        var stitchedAlts = chunks[0].altsPerChar.toMutableList()
        var stitchedCols = chunks[0].charCols.toMutableList()
        var stitchedGlobal = chunkGlobalCenters[0].toMutableList()
        val stitchedRawAll = chunks[0].rawAltsPerTimestep.toMutableList()
        for (i in 1 until chunks.size) {
            val curr = chunks[i]
            val currGlobal = chunkGlobalCenters[i]
            val currAlts = curr.altsPerChar
            if (currAlts.isEmpty() || stitchedAlts.isEmpty()) {
                val lastPx = stitchedGlobal.lastOrNull() ?: -100f
                val offsetGeomT = curr.offsetX.toFloat() * 6f / rh.toFloat()
                for (j in currAlts.indices) {
                    val candT = offsetGeomT + curr.charCols[j]
                    val candPx = currGlobal.getOrNull(j) ?: continue
                    if (candPx > lastPx + STITCH_APPEND_GAP_PX) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last() == ' ' && ch == ' ') continue
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(candT)
                        stitchedGlobal.add(candPx)
                    }
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
                continue
            }
            var bestPrevIdx = -1
            var bestCurrIdx = -1
            var bestScore = -1f
            val pStart = maxOf(0, stitchedAlts.size - STITCH_WINDOW)
            val cEnd = minOf(currAlts.size, STITCH_WINDOW)
            for (pIdx in stitchedAlts.size - 1 downTo pStart) {
                val pGC = stitchedGlobal[pIdx]
                for (cIdx in 0 until cEnd) {
                    val cGX = currGlobal[cIdx]
                    val dist = abs(pGC - cGX)
                    if (dist > STITCH_MAX_DIST_PX) continue
                    val pred = comparePredictionVectors(stitchedAlts[pIdx], currAlts[cIdx])
                    if (pred < STITCH_MIN_PRED) continue
                    val score = (1f - dist / STITCH_MAX_DIST_PX) * 0.3f + pred * 0.7f
                    if (score > bestScore) {
                        bestScore = score
                        bestPrevIdx = pIdx
                        bestCurrIdx = cIdx
                    }
                }
            }
            if (bestPrevIdx != -1) {
                val merged = interleaveAlternatives(stitchedAlts[bestPrevIdx], currAlts[bestCurrIdx]).toMutableList()
                val toKeep = bestPrevIdx + 1
                while (stitchedText.length > toKeep) {
                    stitchedText.deleteCharAt(stitchedText.length - 1)
                    stitchedAlts.removeAt(stitchedAlts.size - 1)
                    stitchedCols.removeAt(stitchedCols.size - 1)
                    stitchedGlobal.removeAt(stitchedGlobal.size - 1)
                }
                stitchedAlts[bestPrevIdx] = merged
                val offsetT = stitchedCols[bestPrevIdx] - curr.charCols[bestCurrIdx]
                val offsetPx = stitchedGlobal[bestPrevIdx] - currGlobal[bestCurrIdx]
                for (j in bestCurrIdx + 1 until currAlts.size) {
                    val ch = curr.text.getOrNull(j) ?: continue
                    if (stitchedText.isNotEmpty() && stitchedText.last() == ' ' && ch == ' ') continue
                    stitchedText.append(ch)
                    stitchedAlts.add(currAlts[j])
                    stitchedCols.add(curr.charCols[j] + offsetT)
                    stitchedGlobal.add(currGlobal[j] + offsetPx)
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
            } else {
                val lastPx = stitchedGlobal.lastOrNull() ?: -100f
                var appended = 0
                for (j in currAlts.indices) {
                    val candPx = currGlobal[j]
                    if (candPx > lastPx + STITCH_APPEND_GAP_PX || (appended == 0 && currAlts.size == 1)) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last() == ' ' && ch == ' ') continue
                        val candT = curr.offsetX.toFloat() * 6f / rh.toFloat() + curr.charCols[j]
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(candT)
                        stitchedGlobal.add(candPx)
                        appended++
                    }
                }
                if (appended == 0) {
                    val lastT = stitchedCols.lastOrNull() ?: 0f
                    val lastPx2 = stitchedGlobal.lastOrNull() ?: 0f
                    val fallbackOffsetT = (lastT + 1f) - curr.charCols[0]
                    val fallbackOffsetPx = (lastPx2 + timestepPx) - currGlobal[0]
                    for (j in currAlts.indices) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last() == ' ' && ch == ' ') continue
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(curr.charCols[j] + fallbackOffsetT)
                        stitchedGlobal.add(currGlobal[j] + fallbackOffsetPx)
                    }
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
            }
        }
        // Final scaling: charCols are global timesteps with identical size (rh/6). Scale to bbox via totalSeqLen.
        val finalTextRaw = stitchedText.toString()
        var finalText = finalTextRaw
        while (finalText.contains("  ")) finalText = finalText.replace("  ", " ")
        Log.d(TAG, "long-line stitch horiz rw=$rw rh=$rh chunks=${chunks.size} stitchedLen=${finalText.length} seqLen=$totalSeqLen text=${finalText.take(40)}")
        return PPOcrResult(finalText, stitchedAlts, stitchedCols.toFloatArray(), totalSeqLen, stitchedRawAll)
    }

    /** Stitch a long vertical line (rh*48/rw > 2000). Same algorithm as
     * [recognizeAndStitchLongHoriz] rotated 90°: chunk top→bottom with a
     * second-to-last-char anchor, then align by identical timestep size
     * (`rw/6` px) with the same 30px / 0.4 / 0.3-0.7 best-pair rule. */
    private suspend fun recognizeAndStitchLongVert(
        rotated: Bitmap, targetH: Int, engine: RecNcnn? = null
    ): PPOcrResult? {
        coroutineContext.ensureActive()
        val rw = rotated.width; val rh = rotated.height
        val scale = targetH.toFloat() / rw.toFloat()
        val maxChunkH = (480 / scale).toInt().coerceAtLeast(64)
        if (maxChunkH <= 0) return null
        val chunkMargin = (rw * 0.1f).toInt().coerceAtLeast(2)
        val chunks = mutableListOf<ChunkInfo>()
        var y = 0
        while (y < rh) {
            coroutineContext.ensureActive()
            val h = minOf(maxChunkH, rh - y)
            if (h < 16) break
            val chunkBmp = Bitmap.createBitmap(rotated, 0, y, rw, h)
            val cw = chunkBmp.width; val ch = chunkBmp.height
            val targetW = minOf(CHUNK_TARGET_MAX, (cw.toFloat() * targetH / ch.toFloat()).roundToInt().coerceAtLeast(4))
            val decoded = inferResizedRec(chunkBmp, targetW, targetH, engine)
            chunkBmp.recycle()
            if (decoded == null) { Log.e(TAG, "recNcnn chunk w$targetW infer null"); return null }
            val actualSeqLen = decoded.seqLenTotal
            val rawAlts = decoded.rawAlternatives
            chunks.add(ChunkInfo(decoded.text, decoded.charCols, decoded.alternatives, rawAlts, actualSeqLen, targetW, h, 0, y))
            if (y + h >= rh) break
            val txt = decoded.text
            if (txt.isNotEmpty() && decoded.charCols.isNotEmpty()) {
                val anchorIdx = if (txt.length >= 2) txt.length - 2 else 0
                val anchorT = decoded.charCols.getOrNull(anchorIdx) ?: decoded.charCols.last()
                val localYTop = ((anchorT + 0.5f) / actualSeqLen.toFloat()) * h
                val nextY = (y + localYTop.toInt() - chunkMargin).coerceAtLeast(0)
                if (nextY <= y || nextY >= y + h - 10) {
                    y += (h * 0.9f).toInt().coerceAtLeast(16)
                } else {
                    y = nextY
                }
            } else {
                y += (h * 0.9f).toInt().coerceAtLeast(16)
            }
        }
        if (chunks.isEmpty()) return null
        if (chunks.size==1) {
            val c = chunks[0]
            Log.d(TAG, "long-line stitch vert rh=$rh rw=$rw chunks=1 stitchedLen=${c.text.length} seqLen=${c.actualSeqLen}")
            return PPOcrResult(c.text, c.altsPerChar, c.charCols, c.actualSeqLen, c.rawAltsPerTimestep)
        }
        // ——— stitch via anchor alignment with identical timestep size (rw/6) ———
        val timestepPx = rw.toFloat() / 6f
        val totalSeqLen = maxOf(1, ceil(rh.toFloat() * targetH.toFloat() / rw.toFloat() / REC_STRIDE.toFloat()).toInt())
        val chunkGlobalCentersY = chunks.map { ci ->
            ci.charCols.map { t -> ci.offsetY.toFloat() + (t + 0.5f) * timestepPx }
        }
        var stitchedText = StringBuilder(chunks[0].text)
        var stitchedAlts = chunks[0].altsPerChar.toMutableList()
        var stitchedCols = chunks[0].charCols.toMutableList()
        var stitchedGlobal = chunkGlobalCentersY[0].toMutableList()
        val stitchedRawAll = chunks[0].rawAltsPerTimestep.toMutableList()
        for (i in 1 until chunks.size) {
            val curr = chunks[i]
            val currGlobal = chunkGlobalCentersY[i]
            val currAlts = curr.altsPerChar
            if (currAlts.isEmpty() || stitchedAlts.isEmpty()) {
                val lastPx = stitchedGlobal.lastOrNull() ?: -100f
                val offsetGeomT = curr.offsetY.toFloat() * 6f / rw.toFloat()
                for (j in currAlts.indices) {
                    val candT = offsetGeomT + curr.charCols[j]
                    val candPx = currGlobal.getOrNull(j) ?: continue
                    if (candPx > lastPx + STITCH_APPEND_GAP_PX) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last() == ' ' && ch == ' ') continue
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(candT)
                        stitchedGlobal.add(candPx)
                    }
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
                continue
            }
            var bestPrevIdx = -1
            var bestCurrIdx = -1
            var bestScore = -1f
            val pStart = maxOf(0, stitchedAlts.size - STITCH_WINDOW)
            val cEnd = minOf(currAlts.size, STITCH_WINDOW)
            for (pIdx in stitchedAlts.size - 1 downTo pStart) {
                val pGC = stitchedGlobal[pIdx]
                for (cIdx in 0 until cEnd) {
                    val cGY = currGlobal[cIdx]
                    val dist = abs(pGC - cGY)
                    if (dist > STITCH_MAX_DIST_PX) continue
                    val pred = comparePredictionVectors(stitchedAlts[pIdx], currAlts[cIdx])
                    if (pred < STITCH_MIN_PRED) continue
                    val score = (1f - dist/STITCH_MAX_DIST_PX)*0.3f + pred*0.7f
                    if (score > bestScore) { bestScore = score; bestPrevIdx = pIdx; bestCurrIdx = cIdx }
                }
            }
            if (bestPrevIdx != -1) {
                val merged = interleaveAlternatives(stitchedAlts[bestPrevIdx], currAlts[bestCurrIdx]).toMutableList()
                val toKeep = bestPrevIdx + 1
                while (stitchedText.length > toKeep) {
                    stitchedText.deleteCharAt(stitchedText.length-1)
                    stitchedAlts.removeAt(stitchedAlts.size-1)
                    stitchedCols.removeAt(stitchedCols.size-1)
                    stitchedGlobal.removeAt(stitchedGlobal.size-1)
                }
                stitchedAlts[bestPrevIdx] = merged
                val offsetT = stitchedCols[bestPrevIdx] - curr.charCols[bestCurrIdx]
                val offsetPx = stitchedGlobal[bestPrevIdx] - currGlobal[bestCurrIdx]
                for (j in bestCurrIdx+1 until currAlts.size) {
                    val ch = curr.text.getOrNull(j) ?: continue
                    if (stitchedText.isNotEmpty() && stitchedText.last()==' ' && ch==' ') continue
                    stitchedText.append(ch)
                    stitchedAlts.add(currAlts[j])
                    stitchedCols.add(curr.charCols[j] + offsetT)
                    stitchedGlobal.add(currGlobal[j] + offsetPx)
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
            } else {
                val lastPx = stitchedGlobal.lastOrNull() ?: -100f
                var appended=0
                for (j in currAlts.indices) {
                    val candPx = currGlobal[j]
                    if (candPx > lastPx + STITCH_APPEND_GAP_PX || (appended==0 && currAlts.size==1)) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last()==' ' && ch==' ') continue
                        val candT = curr.offsetY.toFloat() * 6f / rw.toFloat() + curr.charCols[j]
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(candT)
                        stitchedGlobal.add(candPx)
                        appended++
                    }
                }
                if (appended==0) {
                    val lastT = stitchedCols.lastOrNull() ?: 0f
                    val lastPx2 = stitchedGlobal.lastOrNull() ?: 0f
                    val fallbackOffsetT = (lastT + 1f) - curr.charCols[0]
                    val fallbackOffsetPx = (lastPx2 + timestepPx) - currGlobal[0]
                    for (j in currAlts.indices) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last()==' ' && ch==' ') continue
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(curr.charCols[j] + fallbackOffsetT)
                        stitchedGlobal.add(currGlobal[j] + fallbackOffsetPx)
                    }
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
            }
        }
        var finalText = stitchedText.toString()
        while (finalText.contains("  ")) finalText = finalText.replace("  ", " ")
        Log.d(TAG, "long-line stitch vert rh=$rh rw=$rw chunks=${chunks.size} stitchedLen=${finalText.length} seqLen=$totalSeqLen")
        return PPOcrResult(finalText, stitchedAlts, stitchedCols.toFloatArray(), totalSeqLen, stitchedRawAll)
    }

    /** Prediction overlap 0–1 for a stitch candidate pair: 0.6–1.0 when top-1
     * agrees (scaled by top-5 overlap), 0.5 when either top-1 appears in the
     * other's top-3, else 0. Pairs below [STITCH_MIN_PRED] never stitch. */
    private fun comparePredictionVectors(alt1: List<Pair<Char,Float>>, alt2: List<Pair<Char,Float>>): Float {
        if (alt1.isEmpty()||alt2.isEmpty()) return 0f
        if (alt1[0].first==alt2[0].first) {
            val set2=alt2.take(5).map{ it.first }.toSet(); var m=0; alt1.take(5).forEach{ if(set2.contains(it.first)) m++ }
            return 0.6f + (m/5f)*0.4f
        }
        val c1=alt1[0].first; val c2=alt2[0].first
        if (alt2.take(3).any{ it.first==c1 } || alt1.take(3).any{ it.first==c2 }) return 0.5f
        return 0f
    }

    /** Merge two alternative lists at a stitch anchor: shared chars average up
     * (×0.8), unique chars discount (×0.6), keep top 15 by score. */
    private fun interleaveAlternatives(alt1: List<Pair<Char, Float>>, alt2: List<Pair<Char, Float>>): List<Pair<Char, Float>> {
        val merged = mutableMapOf<Char, Float>()
        alt1.forEach { (ch, sc) -> merged[ch] = sc }
        alt2.forEach { (ch, sc) ->
            val ex = merged[ch] ?: 0f
            if (ex > 0f) merged[ch] = (ex + sc) * 0.8f else merged[ch] = sc * 0.6f
        }
        return merged.toList().sortedByDescending { it.second }.take(TOP_K)
    }

    /**
     * Greedy CTC decode: argmax per timestep, skip blank 0, collapse repeats,
     * class 18709 → space. `blankThreshold` 0 means pure greedy (default PP-OCR
     * behaviour); values >0 surface a non-blank char at blank timesteps whose
     * best alternative scores within `blankThreshold` of blank, with [GAP_CHAR]
     * kept as a selectable alternative. Emits per-char top-15 [alternatives]
     * plus timestep columns; full per-timestep top-15 lives in
     * [PPOcrResult.rawAlternatives] for cache re-decode.
     */
    private fun ctcDecode(
        cropLogits: Array<FloatArray>?,
        seqLen: Int,
        numClasses: Int,
        blankThreshold: Float,
        seqLenTotal: Int,
    ): PPOcrResult {
        val text = StringBuilder()
        val alts = mutableListOf<MutableList<Pair<Char, Float>>>()
        val charCols = mutableListOf<Float>()
        var prevClass = 0

        // Fractional peaks (#49): winner values at neighboring timesteps for
        // sub-column interpolation (charCols carry fractions downstream).
        fun wval(tt: Int, cls: Int): Float? {
            if (tt < 0 || tt >= seqLen) return null
            val s = cropLogits?.getOrNull(tt) as? FloatArray ?: return null
            return s.getOrNull(cls)
        }
        for (t in 0 until seqLen) {
            val slice = cropLogits?.getOrNull(t) as? FloatArray
            if (slice == null || slice.size < numClasses) continue

            // Argmax
            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (k in slice.indices) {
                if (slice[k] > maxVal) { maxVal = slice[k]; maxIdx = k }
            }
            val w0 = wval(t - 1, maxIdx)
            val w2 = wval(t + 1, maxIdx)
            val tFrac = if (w0 != null && w2 != null) t + peakOffset(w0, maxVal, w2) else t.toFloat()

            val classIdx = remapClass(maxIdx)

            // Top-15 alternatives (shared helper).
            val indexed = top15Alternatives(slice).toMutableList()

            // CTC: skip blank (0). Collapse repeats.
            when {
                classIdx == 0 -> {
                    if (blankThreshold > 0f) {
                        // Check if a non-blank alternative has meaningful score.
                        val topNonBlank = indexed.firstOrNull { (ch, sc) ->
                            ch != GAP_CHAR && ch != '　' && (1f / (1f + abs(maxVal - sc)) > blankThreshold)
                        }
                        if (topNonBlank != null) {
                            // Show the best non-blank character; put GAP_CHAR as an alternative
                            text.append(topNonBlank.first)
                            charCols.add(tFrac)
                            val reordered = mutableListOf(topNonBlank)
                            reordered.add(GAP_CHAR to 0f) // blank as selectable option
                            for (alt in indexed) {
                                if (alt != topNonBlank && alt.first != '\u3000' && alt !in reordered) {
                                    reordered.add(alt)
                                }
                            }
                            alts.add(reordered)
                        }
                    }
                    prevClass = 0
                }
                classIdx == 18709 -> {
                    text.append(' ')
                    prevClass = 18709
                    charCols.add(tFrac)
                    alts.add(indexed)
                }
                classIdx == prevClass -> { /* collapse repeat */ }
                else -> {
                    val ch = decodeChar(classIdx)
                    if (ch != '\uFFFD') {
                        text.append(ch)
                        charCols.add(tFrac)
                        alts.add(indexed)
                        prevClass = classIdx
                    }
                }
            }
        }

        return PPOcrResult(text.toString(), alts, charCols.toFloatArray(), seqLenTotal)
    }

    /** Greedy CTC decode from native top-15 lists (#42): same collapse/blank/
     * space rules as [ctcDecode], but the argmax comes from entry 0 (native
     * emits descending, lowest-id-wins ties) and blank/space tests use the
     * remapped pruned indices — decoded chars alone can't flag blanks. */
    private fun ctcDecodeTopK(
        topPruned: Array<IntArray>,
        topChars: List<List<Pair<Char, Float>>>,
        seqLen: Int,
        blankThreshold: Float,
    ): PPOcrResult {
        val text = StringBuilder()
        val alts = mutableListOf<MutableList<Pair<Char, Float>>>()
        val charCols = mutableListOf<Float>()
        var prevClass = 0

        // Fractional peaks (#49): winner (entry 0) score at neighboring
        // timesteps, matched by pruned class id (absent from top-15 → 0 offset).
        fun wval(tt: Int, cls: Int): Float? {
            val p = topPruned.getOrNull(tt) ?: return null
            val c = topChars.getOrNull(tt) ?: return null
            val k = p.indexOf(cls)
            return if (k < 0) null else c.getOrNull(k)?.second
        }
        for (t in 0 until seqLen) {
            val pruned = topPruned.getOrNull(t) ?: continue
            val indexed = topChars.getOrNull(t)?.toMutableList() ?: continue
            if (pruned.isEmpty() || indexed.isEmpty()) continue
            val maxVal = indexed[0].second
            val classIdx = remapClass(pruned[0])
            val w0 = wval(t - 1, pruned[0])
            val w2 = wval(t + 1, pruned[0])
            val tFrac = if (w0 != null && w2 != null) t + peakOffset(w0, maxVal, w2) else t.toFloat()

            when {
                classIdx == 0 -> {
                    if (blankThreshold > 0f) {
                        val topNonBlank = indexed.firstOrNull { (ch, sc) ->
                            ch != GAP_CHAR && ch != '　' && (1f / (1f + abs(maxVal - sc)) > blankThreshold)
                        }
                        if (topNonBlank != null) {
                            text.append(topNonBlank.first)
                            charCols.add(tFrac)
                            val reordered = mutableListOf(topNonBlank)
                            reordered.add(GAP_CHAR to 0f)
                            for (alt in indexed) {
                                if (alt != topNonBlank && alt.first != '　' && alt !in reordered) {
                                    reordered.add(alt)
                                }
                            }
                            alts.add(reordered)
                        }
                    }
                    prevClass = 0
                }
                classIdx == 18709 -> {
                    text.append(' ')
                    prevClass = 18709
                    charCols.add(tFrac)
                    alts.add(indexed)
                }
                classIdx == prevClass -> { /* collapse repeat */ }
                else -> {
                    val ch = decodeChar(classIdx)
                    if (ch != '\uFFFD') {
                        text.append(ch)
                        charCols.add(tFrac)
                        alts.add(indexed)
                        prevClass = classIdx
                    }
                }
            }
        }

        return PPOcrResult(text.toString(), alts, charCols.toFloatArray(), seqLen)
    }

    private fun decodeChar(classIdx: Int): Char {        return when {
            classIdx == 18709 -> ' '
            classIdx == 18708 -> '\u3000' // full-width space for last vocab slot
            classIdx in 1..18708 -> {
                val s = ppocrVocab.getOrNull(classIdx - 1) ?: return '\uFFFD'
                s.firstOrNull() ?: '\uFFFD'
            }
            else -> '\u3000'
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Char boxes from CTC columns
    // ═════════════════════════════════════════════════════════════════════════

    /** Per-character boxes from CTC timestep columns.
     *
     * Horizontal: center each char at `(t+0.5)*avgColW` (`avgColW=cropW/seqLen`)
     * with width `cropH`, then split overlaps evenly. Vertical: same on the
     * y-axis with height `cropW` (`avgColW=cropH/seqLen`; trailing blank
     * timesteps absorb at the bottom), except punctuation: closing marks shrink
     * onto the next box's start and opening marks onto the previous box's end,
     * then expand back to the mean non-punctuation height (bounded by the next
     * non-punctuation edge).
     *
     * @param pixels optional crop pixels (idea 4): snap box centers to ink
     * evidence when BOX_LAYOUT_MODE is BOX_SNAP; null/legacy skips snapping. */
    private fun isHalfWidthEm(ch: Char): Boolean = isHalfWidth(ch)

    /** Ink-aware overlap resolution for horizontal lines (#49): legacy code
     * split every box overlap evenly, jittering centers even when glyph
     * bearings absorb the touch. Measure each glyph's ink half-width at the
     * render text size (mirrors LineOverlayView horizontal measuring config:
     * DEFAULT typeface, ROOT locale, no features, per-char bounds) and move
     * centers only on true ink collision, splitting just the collision.
     * Widths are preserved; only centers move. Local Paint per call, so this
     * is safe on any dispatcher. */
    private fun resolveInkCollisions(
        cells: MutableList<Pair<Float, Float>>,
        text: String,
        renderTextSize: Float,
    ) {
        val n = cells.size
        if (n < 2) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = renderTextSize.coerceAtLeast(1f)
            textLocale = java.util.Locale.ROOT
        }
        val bounds = Rect()
        val centers = cells.map { (a, b) -> (a + b) / 2f }.toMutableList()
        val inkHalf = FloatArray(n) { i ->
            val s = text.getOrNull(i)?.toString() ?: ""
            if (s.isEmpty()) 0f
            else {
                paint.getTextBounds(s, 0, s.length, bounds)
                bounds.width() / 2f
            }
        }
        for (ci in 0 until n - 1) {
            val inkR = centers[ci] + inkHalf[ci]
            val inkL = centers[ci + 1] - inkHalf[ci + 1]
            if (inkR <= inkL) continue
            val shift = (inkR - inkL) / 2f
            centers[ci] -= shift
            centers[ci + 1] += shift
        }
        for (i in 0 until n) {
            val half = (cells[i].second - cells[i].first) / 2f
            val c = centers[i]
            cells[i] = (c - half) to (c + half)
        }
    }

    /** Consistent em sizing (#49): uniform WIDTHS around existing centers
     * (centers bit-identical to the resolve path — positioning untouched).
     * em = median WIDTH-NORMALIZED pitch ([estimateEm]: each gap divided by
     * its chars' mean advance, so ASCII-majority mixed lines no longer drag
     * em to ~0.5x and shrink kanji); fullwidth = em, halfwidth = 0.5em;
     * edges clamped legacy-style. Needs 2+ cells; otherwise returns input
     * unchanged. */
    private fun uniformCells(
        cells: List<Pair<Float, Float>>,
        text: String,
        L: Float,
    ): List<Pair<Float, Float>> {
        if (cells.size < 2 || text.length != cells.size) return cells
        val centers = cells.map { (a, b) -> (a + b) / 2f }
        val em = estimateEm(text, centers)
        if (em <= 0f) return cells
        return List(cells.size) { i ->
            val w = (if (isHalfWidthEm(text[i])) 0.5f else 1.0f) * em
            val half = w / 2f
            // Clamp EDGES (legacy convention), never move centers: center
            // coercion showed up as +0.7px demean on ground truth.
            val c = centers[i]
            (maxOf(c - half, 0f)) to (minOf(c + half, L))
        }
    }

    /** Legacy uniform column mapping (#49 verdict: gap surgery and affine
     * refits all lost to this on ground truth — model timing is uniform;
     * fractional charCols from peak interpolation flow here). */
    private fun legacyCells(cols: FloatArray, seqLen: Int, L: Float, cross: Float): List<Pair<Float, Float>> {
        val avgColW = L / seqLen.toFloat()
        val half = cross / 2f
        return cols.map { t ->
            val c = (t + 0.5f) * avgColW
            (maxOf(c - half, 0f)) to (minOf(c + half, L))
        }.sortedBy { it.first }
    }

    /** Chars kept on legacy boxes (conservative): corner/side punctuation
     * whose ink centroid is NOT the em center, small kana, and centered
     * midline marks (harmless either way — left untouched to minimize
     * behavior surface). The renderer centers ink itself. */
    private fun isSnapSkipped(ch: Char): Boolean {
        if (ch in "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ") return true
        return ch in "、。．，,．「」『』（）〔〕［］｛｝〈〉《》【】〘〙〚〛'\"\"‘’“”()[]{}-+*/<>＜＞＝…‥︙︰：；"
    }

    /** Idea 4 (#49 positioning): snap legacy box centers to image-ink
     * evidence along the line axis. Profiled over the central 60% cross-band
     * (dodges ruby at the edges); each center moves to its window ink
     * centroid, clamped to its Voronoi cell (ordering preserved, worst case
     * ~= legacy) with a 0.4-pitch leash. Polarity auto-detects (dark-on-light
     * vs light-on-dark) from border pixels. Needs crop pixels; null = skip. */
    private data class Peak(val argmax: Float, val centroid: Float, val mass: Float)

    private fun snapCells(
        cells: List<Pair<Float, Float>>,
        text: String,
        pixels: IntArray,
        pixW: Int,
        pixH: Int,
        vertical: Boolean,
        L: Float,
    ): List<Pair<Float, Float>> {
        if (cells.isEmpty() || pixels.size != pixW * pixH || pixW < 8 || pixH < 8) return cells
        val lum = FloatArray(pixW * pixH) { i ->
            val px = pixels[i]
            (((px shr 16) and 0xFF) + ((px shr 8) and 0xFF) + (px and 0xFF)) / 3f
        }
        // Background polarity from border samples.
        val border = mutableListOf<Float>()
        var bi = 0
        while (bi < pixW) {
            border.add(lum[bi]); border.add(lum[(pixH - 1) * pixW + bi]); bi += 7
        }
        bi = 0
        while (bi < pixH) {
            border.add(lum[bi * pixW]); border.add(lum[bi * pixW + pixW - 1]); bi += 7
        }
        border.sort()
        val bgLight = border[border.size / 2] > 128f
        fun isInk(v: Float) = if (bgLight) v < 110f else v > 145f
        // Axis profile over the central cross-band.
        val profLen = if (vertical) pixH else pixW
        val prof = FloatArray(profLen)
        if (vertical) {
            val x0 = (pixW * 0.2f).toInt(); val x1 = (pixW * 0.8f).toInt()
            for (y in 0 until pixH) {
                var m = 0f
                for (x in x0 until x1) if (isInk(lum[y * pixW + x])) m += 1f
                prof[y] = m
            }
        } else {
            val y0 = (pixH * 0.2f).toInt(); val y1 = (pixH * 0.8f).toInt()
            for (x in 0 until pixW) {
                var m = 0f
                for (y in y0 until y1) if (isInk(lum[y * pixW + x])) m += 1f
                prof[x] = m
            }
        }
        // Box blur radius 2.
        val sm = FloatArray(profLen) { i ->
            var a = 0f; var c = 0
            for (k in -2..2) {
                val j = (i + k).coerceIn(0, profLen - 1); a += prof[j]; c++
            }
            a / c
        }
        // Cells live in crop coords; pixels may be the clamped subset at image
        // edges — scale defensively (normally identity).
        val centers = cells.map { (a, b) -> (a + b) / 2f }
        // Ink peaks along the axis (local maxima with prominence): in dense
        // text a window centroid is contaminated by neighbors, so each box
        // snaps to the NEAREST peak instead (#49: single rushed transitions
        // accumulate downstream; peak assignment removes cumulativity).
        // Each peak carries ARGMAX + CENTROID: region growing can walk
        // through shallow valleys and merge neighbors (seen: し centroid
        // pulled 21px into the gap toward 失). When they disagree the window
        // is contaminated and the box keeps legacy (veto below); when they
        // agree the centroid is the stable snap target.
        val peaks = mutableListOf<Peak>()
        run {
            var p = 1
            while (p < profLen - 1) {
                if (sm[p] > sm[p - 1] && sm[p] >= sm[p + 1]) {
                    var l = p
                    while (l > 0 && sm[l - 1] >= sm[l] * 0.5f) l--
                    var r = p
                    while (r < profLen - 1 && sm[r + 1] >= sm[r] * 0.5f) r++
                    var m2 = 0f; var mo = 0f; var am = -1f; var ap = p
                    for (q in l..r) {
                        m2 += sm[q]; mo += sm[q] * q
                        if (sm[q] > am) { am = sm[q]; ap = q }
                    }
                    if (m2 > 0f) peaks.add(Peak(ap.toFloat(), mo / m2, m2))
                    p = r + 1
                } else p++
            }
        }
        // Peak prominence gate: speck peaks (ruby fragments, noise) carry
        // little mass vs a full glyph; measured against the median peak.
        val medMass = peaks.map { it.mass }.sorted()
            .let { if (it.isEmpty()) 0f else it[it.size / 2] }
        return cells.mapIndexed { i, (a, b) ->
            if (i >= text.length || isSnapSkipped(text[i])) return@mapIndexed a to b
            val c = centers[i]
            val pitch = (b - a).coerceAtLeast(4f)
            // Position on the pixel axis.
            val scale = profLen.toFloat() / L.coerceAtLeast(1f)
            val cp = (c * scale).coerceIn(0f, profLen - 1f)
            // Nearest peak within half pitch; must clear the mass floor.
            var best: Peak? = null
            var bestD = 0.5f * pitch * scale + 1f
            for (pk in peaks) {
                if (pk.mass < maxOf(6f, 0.35f * medMass)) continue
                val d = kotlin.math.abs(pk.centroid - cp)
                if (d < bestD) { bestD = d; best = pk }
            }
            if (best == null) return@mapIndexed a to b
            // Agreement veto: centroid far from argmax = merged neighbors
            // (the し case) — keep legacy rather than snap into a gap.
            if (kotlin.math.abs(best.centroid - best.argmax) > 0.3f * pitch * scale) {
                return@mapIndexed a to b
            }
            // Min-move gate: sub-visible moves (< 3px) carry measurement risk
            // without visible benefit — they regressed exact boxes ~1px.
            if (kotlin.math.abs(best.centroid / scale - c) < 3f) {
                return@mapIndexed a to b
            }
            var nc = best.centroid / scale
            // Voronoi clamp between neighbor centers (ends: line bounds).
            val loB = if (i > 0) (centers[i - 1] + c) / 2f else 0f
            val hiB = if (i < centers.size - 1) (c + centers[i + 1]) / 2f else L
            nc = nc.coerceIn(loB, hiB)
            nc = nc.coerceIn(c - 0.4f * pitch, c + 0.4f * pitch)
            val len = b - a
            val s0 = (nc - len / 2f).coerceIn(0f, maxOf(L - len, 0f))
            s0 to minOf(s0 + len, L)
        }
    }

    fun computeCharBoxes(
        text: String,
        charCols: FloatArray,
        seqLenTotal: Int,
        cropX: Int, cropY: Int,
        cropW: Int, cropH: Int,
        isVertical: Boolean,
        pixels: IntArray? = null,
        pixW: Int = 0,
        pixH: Int = 0,
    ): List<JpDictRect> {
        val n = charCols.size
        if (n == 0 || seqLenTotal <= 0) return emptyList()

        if (!isVertical) {
            // ── HORIZONTAL: x-axis char boxes ──
            val avgColW = cropW.toFloat() / seqLenTotal.toFloat()
            val charW = maxOf(cropH.toFloat(), 3f)
            val L = cropW.toFloat()

            val base = legacyCells(charCols, seqLenTotal, L, charW)
            val cells = if (BOX_LAYOUT_MODE == BOX_SNAP && pixels != null) {
                snapCells(base, text, pixels, pixW, pixH, vertical = false, L)
            } else base

            // Resolve overlaps, ink-aware (#49): boxes may touch — glyph
            // bearings absorb it. Measure each glyph's ink half-width at the
            // render text size (line height * 0.90, mirrors LineOverlayView
            // horizontal config) and move centers only on true ink collision,
            // splitting just the collision instead of the whole box overlap.
            val resolved = cells.toMutableList()
            resolveInkCollisions(resolved, text, cropH.toFloat() * 0.90f)
            // Consistent widths around resolved centers (#49).
            val sized = if (BOX_UNIFORM_SIZE) uniformCells(resolved, text, L) else resolved

            return sized.map { (xl, xr) ->
                JpDictRect(
                    (cropX + xl).roundToInt(), cropY,
                    (cropX + xr).roundToInt(), cropY + cropH
                )
            }
        } else {
            // ── VERTICAL: y-axis char boxes with punctuation handling ──
            // Char height = short side (cropW) like horizontal's charW=cropH.
            // Each timestep identical avgColW=cropH/seqLen, empty at end =
            // trailingNulls*avgColW where trailingNulls=seqLen-(lastT+1),
            // final timestep (seqLen) aligns with bbox bottom.
            val avgColW = cropH.toFloat() / seqLenTotal.toFloat()
            val avgChH = maxOf(cropW.toFloat(), 3f)
            val L = cropH.toFloat()

            val base = legacyCells(charCols, seqLenTotal, L, avgChH)
            val cells = if (BOX_LAYOUT_MODE == BOX_SNAP && pixels != null) {
                snapCells(base, text, pixels, pixW, pixH, vertical = true, L)
            } else base

            val isCp = text.map { ch ->
                ch in "。.．、,，)）〕》」』】〙〗〟’”］"
            }
            val isOp = text.map { ch ->
                ch in "(（〔《「『【〘〖〝‘“［"
            }

            // Resolve overlaps with punctuation rules (always; positions).
            val resolved = cells.toMutableList()
            for (ci in 0 until n - 1) {
                if (resolved[ci].second <= resolved[ci + 1].first) continue
                when {
                    isCp[ci] -> resolved[ci] = resolved[ci].first to resolved[ci + 1].first
                    isOp[ci + 1] -> resolved[ci + 1] = resolved[ci].second to resolved[ci + 1].second
                    isCp[ci + 1] -> resolved[ci + 1] = resolved[ci].second to resolved[ci + 1].second
                    isOp[ci] -> resolved[ci] = resolved[ci].first to resolved[ci + 1].first
                    else -> {
                        val h = (resolved[ci].second - resolved[ci + 1].first) / 2f
                        resolved[ci] = resolved[ci].first to (resolved[ci].second - h)
                        resolved[ci + 1] = (resolved[ci + 1].first + h) to resolved[ci + 1].second
                    }
                }
            }
            // Expand punctuation cells to average non-punctuation height.
            // Runs on the uniform-sized list when enabled (avgNpH ~= em, so
            // punct lands corner-placed at consistent size, as before).
            val sized = if (BOX_UNIFORM_SIZE) uniformCells(resolved, text, L).toMutableList() else resolved
            val avgNpH = sized.filterIndexed { i, _ -> !isCp[i] && !isOp[i] }
                .let { hs -> if (hs.isEmpty()) cropW.toFloat() else hs.sumOf { (it.second - it.first).toDouble() }.toFloat() / hs.size }
            for (ci in 0 until n) {
                val (yt, yb) = sized[ci]
                if (isCp[ci]) {
                    val nx = ((ci + 1) until n)
                        .firstOrNull { !isCp[it] && !isOp[it] }
                        ?.let { sized[it].first } ?: Float.POSITIVE_INFINITY
                    sized[ci] = yt to maxOf(yb, minOf(yt + avgNpH, nx))
                } else if (isOp[ci]) {
                    val pl = (0 until ci)
                        .lastOrNull { !isCp[it] && !isOp[it] }
                        ?.let { sized[it].second } ?: Float.NEGATIVE_INFINITY
                    sized[ci] = minOf(yt, maxOf(pl, yb - avgNpH)) to yb
                }
            }

            return sized.map { (yt, yb) ->
                val ch = maxOf(yb - yt, 1f)
                JpDictRect(
                    cropX, (cropY + yt).roundToInt(),
                    cropX + cropW, (cropY + yt + ch).roundToInt()
                )
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Streaming rec — batches of 4, completion-order emit
    // ═════════════════════════════════════════════════════════════════════════

    /** Recognize all line boxes, streaming per-line results in completion order.
     *
     * One dynamic-width model serves both orientations: portrait crops rotate
     * 270° pre-inference, char boxes compute on the x- vs y-axis post-inference.
     * Jobs sort into reading order (vertical right-to-left, then horizontal
     * top-to-bottom; results stay keyed by job idx so only arrival order
     * changes), run in batches of [REC_BATCH_SIZE] with crops created per batch
     * (4 bitmaps pinned at a time) and recycled after each batch. Within a
     * batch, [recognizePpocrBatch] awaits via `select` over the deferreds so
     * each line emits as its infer completes; the callback re-posts to the main
     * thread. Cooperative cancellation (#18): overlay close cancels the job and
     * each batch boundary checks `ensureActive()`. */
    suspend fun recognizeStreaming(
        bitmap: Bitmap,
        lineBoxes: List<JpDictRect>,
        onLinesRecognized: (List<Pair<Int, LineResult>>) -> Unit
    ) = coroutineScope {
        val startTime = System.currentTimeMillis()
        if (recDynNcnn == null || ppocrVocab.isEmpty()) return@coroutineScope

        // Build job queue — no Bitmaps yet; crops are created per batch below.

        val jobs = lineBoxes.mapIndexedNotNull { i, box ->
            if (box.width() < 4 || box.height() < 4) null
            else Job(i, box, isVerticalBox(box))
        }
        if (jobs.isEmpty()) return@coroutineScope

        Log.d(TAG, "Processing ${jobs.size} boxes in batches of $REC_BATCH_SIZE")
        InferLog.add("stream start boxes=${jobs.size} batch=$REC_BATCH_SIZE squish=$recSquish")

        // Reading order: vertical lines right-to-left first, then horizontal
        // lines top-to-bottom (same per-group comparators as sortDetectedBoxes).
        // Results stay keyed by job idx, so only arrival order changes.
        val verticals = jobs.filter { it.isVertical }
            .sortedWith(compareByDescending<Job> { it.bbox.right }.thenBy { it.bbox.top })
        val horizontals = jobs.filter { !it.isVertical }
            .sortedWith(compareBy<Job> { it.bbox.top }.thenBy { it.bbox.left })
        val sortedJobs = verticals + horizontals

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        // Process in batches — cooperative cancellation for #18 (overlay closed → cancel).
        val batches = sortedJobs.chunked(REC_BATCH_SIZE.coerceAtLeast(1))
        val engine = recDynNcnn ?: return@coroutineScope
        for ((batchIdx, batch) in batches.withIndex()) {
            coroutineContext.ensureActive()
            processOneBatch(batchIdx, batch, engine, bitmap, mainHandler, onLinesRecognized)
        }
        Log.d(TAG, "All batches finished")

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Streaming recognition for ${lineBoxes.size} lines took ${elapsed}ms")
        InferLog.add("stream done lines=${lineBoxes.size} total=${elapsed}ms")
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Re-decode from cache (no re-inference)
    // ═════════════════════════════════════════════════════════════════════════

    /** Re-decode a [LineResult] from its cached [rawAlternatives] without
     * re-running recognition. [blankThreshold] mirrors [ctcDecode]: 0 is pure
     * greedy; >0 surfaces non-blank chars at blank timesteps (with [GAP_CHAR]
     * as a selectable alternative). Char boxes recompute when crop geometry
     * is known; user [overrides][LineResult.overrides] carry over. */
    fun reDecodeLineResult(oldLine: LineResult, blankThreshold: Float): LineResult {
        val raw = oldLine.rawAlternatives
        if (raw.isEmpty()) return oldLine

        val text = StringBuilder()
        val newAlts = mutableListOf<MutableList<Pair<Char, Float>>>()
        val charCols = mutableListOf<Float>()
        var prevChar: Char? = null

        fun wval(t: Int, ch: Char): Float? {
            if (t < 0 || t >= raw.size) return null
            return raw[t].firstOrNull { (c, _) -> c == ch }?.second
        }
        fun fracFor(t: Int, ch: Char, v1: Float): Float {
            val w0 = wval(t - 1, ch)
            val w2 = wval(t + 1, ch)
            return if (w0 != null && w2 != null) peakOffset(w0, v1, w2) else 0f
        }
        for ((t, alts) in raw.withIndex()) {
            val top = alts.firstOrNull() ?: continue
            val blankScore = alts.firstOrNull { (ch, _) -> ch == '\u3000' }?.second ?: top.second
            val topChar = top.first

            when {
                topChar == '\u3000' -> { // blank
                    if (blankThreshold > 0f) {
                        val topNonBlank = alts.firstOrNull { (ch, sc) ->
                            ch != GAP_CHAR && ch != '\u3000' && (1f / (1f + abs(blankScore - sc)) > blankThreshold)
                        }
                        if (topNonBlank != null) {
                            // Show the best non-blank character; put GAP_CHAR as an alternative
                            text.append(topNonBlank.first)
                            charCols.add(t + fracFor(t, topNonBlank.first, topNonBlank.second))
                            val reordered = mutableListOf(topNonBlank)
                            reordered.add(GAP_CHAR to 0f) // blank as selectable option
                            for (alt in alts) {
                                if (alt != topNonBlank && alt.first != '\u3000' && alt !in reordered) {
                                    reordered.add(alt)
                                }
                            }
                            newAlts.add(reordered)
                        }
                    }
                    prevChar = null
                }
                topChar == ' ' -> {
                    text.append(' ')
                    prevChar = ' '
                    charCols.add(t + fracFor(t, ' ', top.second))
                    newAlts.add(alts.toMutableList())
                }
                topChar == prevChar -> { /* collapse */ }
                else -> {
                    text.append(topChar)
                    charCols.add(t + fracFor(t, topChar, top.second))
                    newAlts.add(alts.toMutableList())
                    prevChar = topChar
                }
            }
        }

        // Vertical punctuation (#56, #63, safety net): the emit path
        // normalizes, but cached raw alternatives may predate the fix —
        // re-decode must not reintroduce ASCII `?` or horizontal `…`/`‥`
        // into vertical lines.
        val decodedText = text.toString()
        val vertText = if (oldLine.isVertical) JapaneseUtil.verticalPunctuation(decodedText) else decodedText
        if (oldLine.isVertical) {
            for (alts in newAlts) {
                for (i in alts.indices) {
                    val (c, s) = alts[i]
                    val n = JapaneseUtil.verticalPunctuationChar(c)
                    if (n != c) alts[i] = n to s
                }
            }
        }

        val newCharBoxes = if (oldLine.cropW > 0 && oldLine.cropH > 0) {
            computeCharBoxes(
                vertText, charCols.toFloatArray(), oldLine.seqLenTotal,
                oldLine.cropX, oldLine.cropY, oldLine.cropW, oldLine.cropH,
                oldLine.isVertical,
            )
        } else oldLine.charBoxes

        return LineResult(
            text = vertText,
            charBoxes = newCharBoxes,
            alternatives = newAlts,
            isVertical = oldLine.isVertical,
            overrides = oldLine.overrides,
            rawAlternatives = oldLine.rawAlternatives,
            seqLenTotal = oldLine.seqLenTotal,
            cropW = oldLine.cropW,
            cropH = oldLine.cropH,
            cropX = oldLine.cropX,
            cropY = oldLine.cropY,
            charCols = charCols.toFloatArray(),
        )
    }

    fun close() {
        try { detNcnn?.close() } catch (_: Exception) {}
        try { recDynNcnn?.close() } catch (_: Exception) {}
    }
}

// Horizontal → vertical glyph equivalents (most CJK brackets are already upright
// in the font; only chōonpu and ASCII-ish dashes need remapping).
// Ellipses need no entry here: `…`/`‥` → `︙`/`︰` is handled vertical-only in
// JapaneseUtil.verticalPunctuation at emit time (#63).


