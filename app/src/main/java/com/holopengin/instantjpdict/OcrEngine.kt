package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// PP-OCRv6 data types
// ─────────────────────────────────────────────────────────────────────────────
// PP-OCRv6 OcrEngine — replaces meiki DETR with PP-OCR segmentation + CTC
// ─────────────────────────────────────────────────────────────────────────────

class OcrEngine(private val context: Context) {
    // PP-OCRv6 detection model — ncnn only for #15 (LiteRT removed)
    private var detNcnn: DetNcnn? = null

    // PP-OCRv6 recognition models — ncnn only for #15 (onnxruntime removed)
    private var ppocrVocab: List<String> = emptyList()
    // ncnn buckets w64/128/256/480 — map for O(1) bucket switch W/8→seq
    private val recNcnnMap = mutableMapOf<Int, RecNcnn>()
    // compat for #12 benchRecNcnnW64 reflection (maps to 64)
    @Suppress("unused")
    private var recNcnnW64: RecNcnn?
        get() = recNcnnMap[64]
        set(v) { if (v != null) recNcnnMap[64] = v else recNcnnMap.remove(64) }

    companion object {
        private const val TAG = "PPOCREngine"

        // SharedPreferences keys for tunables — #14
        const val PREFS_NAME = "instant_jp_dict_prefs"
        const val PREF_BACKEND = "ocr_backend"
        const val PREF_DET_THRESH = "ppocr_det_thresh"
        const val PREF_DET_UNCLIP = "ppocr_det_unclip_ratio"
        const val PREF_DET_LONG_SIDE = "ppocr_det_long_side"
        const val PREF_X_OVERLAP = "x_overlap_thresh"
        const val PREF_REC_CONF = "rec_confidence_thresh"

        // Defaults (previous hard constants)
        const val DEF_DET_LONG_SIDE = 960
        const val DEF_DET_THRESH = 0.3f
        const val DEF_DET_UNCLIP = 1.50f
        const val DEF_X_OVERLAP = 0.40f
        const val DEF_REC_CONF = 0.1f

        // Legacy aliases — keep source compatibility for old const refs
        const val PPOCR_DET_LONG_SIDE = DEF_DET_LONG_SIDE
        const val PPOCR_DET_THRESH = DEF_DET_THRESH
        const val PPOCR_DET_UNCLIP_RATIO = DEF_DET_UNCLIP
        const val X_OVERLAP_THRESHOLD = DEF_X_OVERLAP
        const val REC_CONFIDENCE_THRESHOLD = DEF_REC_CONF

        // Helpers for static access (no engine instance needed)
        fun getDetThresh(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(PREF_DET_THRESH, DEF_DET_THRESH)
        fun getDetUnclip(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(PREF_DET_UNCLIP, DEF_DET_UNCLIP)
        fun getDetLongSide(ctx: Context): Int = DEF_DET_LONG_SIDE
        fun getXOverlap(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(PREF_X_OVERLAP, DEF_X_OVERLAP)
        fun getRecConf(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(PREF_REC_CONF, DEF_REC_CONF)

        // Recognition constants (not tunable)
        private const val REC_TARGET_H = 48
        private const val REC_NUM_CLASSES = 18710  // 0=blank, 1..18708=chars, 18709=space
        private const val BATCH_SIZE = 1
        private const val REC_STRIDE = 8
	const val GAP_CHAR = '\u25CC'
    }

    // ——— Tunable getters (live SharedPreferences, defaults from companion) ———
    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val detLongSide: Int
        get() = DEF_DET_LONG_SIDE
    private val detThresh: Float
        get() = prefs.getFloat(PREF_DET_THRESH, DEF_DET_THRESH)
    private val detUnclip: Float
        get() = prefs.getFloat(PREF_DET_UNCLIP, DEF_DET_UNCLIP)
    private val xOverlapThresh: Float
        get() = prefs.getFloat(PREF_X_OVERLAP, DEF_X_OVERLAP)
    val recConfThresh: Float
        get() = prefs.getFloat(PREF_REC_CONF, DEF_REC_CONF)

    init {
        try {
            val cacheDir = File(context.cacheDir, "model_cache")
            cacheDir.mkdirs()
            // Clear stale cached models so asset updates take effect
            cacheDir.listFiles()?.forEach { it.delete() }

            // Helper: copy asset to cache file
            fun copyAsset(assetPath: String): String {
                val out = File(cacheDir, assetPath.replace('/', '_'))
                context.assets.open(assetPath).use { src ->
                    out.outputStream().use { dst -> src.copyTo(dst) }
                }
                return out.absolutePath
            }

            // ── Load PP-OCRv6 detection model — ncnn only for #15 ──
            // LiteRT removed, DetNcnn is the only det path (960×960 DB)
            try {
                detNcnn = DetNcnn.create(context)
                Log.d(TAG, "DetNcnn loaded: $detNcnn")
            } catch (e: Exception) {
                Log.e(TAG, "DetNcnn failed", e)
            }

            // ── Load PP-OCRv6 recognition models — ncnn only (onnxruntime removed) ──
            for (w in listOf(64, 128, 256, 480)) {
                try {
                    val ncnn = RecNcnn.create(context, w)
                    if (ncnn != null) {
                        recNcnnMap[w] = ncnn
                        Log.d(TAG, "RecNcnn w$w loaded: $ncnn")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "RecNcnn w$w failed", e)
                }
            }
            Log.d(TAG, "RecNcnn buckets loaded: ${recNcnnMap.keys}")

            // ── Load vocabulary — now from PP-OCRv6_small_ncnn (was PP-OCRv6_small_rec_onnx) ──
            val vocabJson = context.assets.open("PP-OCRv6_small_ncnn/vocab.json")
                .bufferedReader().use { it.readText() }
            val listType = object : TypeToken<List<String>>() {}.type
            ppocrVocab = Gson().fromJson(vocabJson, listType)
            Log.d(TAG, "Vocabulary loaded: ${ppocrVocab.size} entries")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load models", e)
        }
    }

    private fun loadModelBytes(path: String): ByteArray {
        return context.assets.open(path).readBytes()
    }

    fun isReady(): Boolean =
        detNcnn != null && recNcnnMap.isNotEmpty() && ppocrVocab.isNotEmpty()

    // ═════════════════════════════════════════════════════════════════════════
    //  PP-OCRv6 DETECTION (DB segmentation → contours → bounding boxes)
    // ═════════════════════════════════════════════════════════════════════════

    fun detect(bitmap: Bitmap): List<JpDictRect> {
        val det = detNcnn ?: return emptyList()
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()

        // 1. Resize keeping longest side = detLongSide (fixed 960), pad to modelSize×modelSize square
        // modelSize is fixed ncnn input (960); detLongSide fixed to avoid clipping/short boxes
        val targetLong = detLongSide
        val modelSize = 960
        Log.d(TAG, "detect tunables thresh=$detThresh unclip=$detUnclip longSide=$targetLong xOverlap=$xOverlapThresh modelSize=$modelSize")
        val scale = targetLong.toFloat() / maxOf(origW, origH)
        val resizeW = maxOf((origW * scale).roundToInt(), 32)
        val resizeH = maxOf((origH * scale).roundToInt(), 32)

        val resized = Bitmap.createScaledBitmap(bitmap, resizeW, resizeH, true)

        // Letterbox to modelSize × modelSize (gray padding)
        val letterbox = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(letterbox)
        canvas.drawColor(Color.rgb(128, 128, 128))
        canvas.drawBitmap(resized, (modelSize - resizeW) / 2f, (modelSize - resizeH) / 2f, null)
        canvas.setBitmap(null)
        resized.recycle()

        // 2. Build NCHW input with ImageNet normalisation for ncnn [3,960,960]
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val imgData = FloatArray(3 * modelSize * modelSize)

        for (y in 0 until modelSize) {
            for (x in 0 until modelSize) {
                val px = letterbox.getPixel(x, y)
                val r = ((px shr 16 and 0xFF) / 255f - mean[0]) / std[0]
                val g = ((px shr 8 and 0xFF) / 255f - mean[1]) / std[1]
                val b = ((px and 0xFF) / 255f - mean[2]) / std[2]
                // NCHW: c*H*W + y*W + x
                imgData[0 * modelSize * modelSize + y * modelSize + x] = r
                imgData[1 * modelSize * modelSize + y * modelSize + x] = g
                imgData[2 * modelSize * modelSize + y * modelSize + x] = b
            }
        }
        letterbox.recycle()

        // 3. Run detection via ncnn — no LiteRT fallback
        val probArr = det.infer(imgData, modelSize, modelSize) ?: return emptyList()
        // probArr should be modelSize*modelSize (960*960) float prob map
        // Handle possible downsampled output (e.g., 240*240) by upsampling via nearest
        val outH: Int
        val outW: Int
        val probArrNorm: FloatArray
        if (probArr.size == modelSize * modelSize) {
            outH = modelSize
            outW = modelSize
            probArrNorm = probArr
        } else {
            // Try to infer square size from total
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

        val probArrFinal = probArrNorm

        // 5. Threshold → binary image
        val probMap = Array(outH) { y ->
            FloatArray(outW) { x ->
                probArrFinal.getOrElse(y * outW + x) { 0f }
            }
        }

        // Debug: prob map stats
        var pMin = Float.MAX_VALUE
        var pMax = Float.MIN_VALUE
        var pSum = 0f
        var pCount = 0
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val v = probMap[y][x]
                pMin = minOf(pMin, v)
                pMax = maxOf(pMax, v)
                pSum += v
                pCount++
            }
        }
        Log.d(TAG, "prob_map: min=$pMin max=$pMax mean=${if (pCount > 0) pSum / pCount else 0f}")

        // Scale factors from model output to original image (accounting for letterbox)
        val scaleWOut = origW / (modelSize.toFloat())
        val scaleHOut = origH / (modelSize.toFloat())

        // 6. Find connected components (contours) via simple flood-fill
        val visited = Array(outH) { BooleanArray(outW) }
        val rawBoxes = mutableListOf<JpDictRect>()

        for (y in 0 until outH) {
            for (x in 0 until outW) {
                if (visited[y][x] || probMap[y][x] <= detThresh) continue

                // Flood-fill to find connected component
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.addLast(x to y)
                visited[y][x] = true

                var minX = x; var maxX = x
                var minY = y; var maxY = y
                var pixelCount = 0

                while (queue.isNotEmpty()) {
                    val (cx, cy) = queue.removeFirst()
                    pixelCount++
                    minX = minOf(minX, cx); maxX = maxOf(maxX, cx)
                    minY = minOf(minY, cy); maxY = maxOf(maxY, cy)

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx; val ny = cy + dy
                            if (nx in 0 until outW && ny in 0 until outH &&
                                !visited[ny][nx] && probMap[ny][nx] > detThresh
                            ) {
                                visited[ny][nx] = true
                                queue.addLast(nx to ny)
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

                // Unclip: expand box using proper PP-OCR formula
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
                rawBoxes.add(JpDictRect(ux, uy, ux2, uy2))
            }
        }

        Log.d(TAG, "detect: raw ${rawBoxes.size} boxes")

        // 7. Post-processing: merge overlapping boxes
        val merged = mergeOverlappingBoxes(rawBoxes)

        // 8. Filter degenerate boxes
        val filtered = merged.filter { it.width() >= 10 && it.height() >= 10 }

        // 9. Shrink vertical box widths by 10% (centered)
        val shrunk = filtered.map { box ->
            if (box.height() > box.width()) {
                val shrink = (box.width() * 0.05f).roundToInt()
                JpDictRect(box.left + shrink, box.top, box.right - shrink, box.bottom)
            } else box
        }

        // 10. Split overlapping horizontal boxes at overlap midpoint
        val horizontals = shrunk.mapIndexedNotNull { i, b ->
            if (b.width() >= b.height()) i to b else null
        }
        val splitBoxes = shrunk.toMutableList()
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
        return sorted
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Box merging & sorting (same logic as accessibility_daemon)
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

    private fun sortDetectedBoxes(boxes: List<JpDictRect>): List<JpDictRect> {
        val horizontal = boxes.filter { it.width() >= it.height() }
            .sortedWith(compareBy({ it.top }, { it.left }))
        val vertical = boxes.filter { it.height() > it.width() }
            .sortedWith(compareByDescending<JpDictRect> { it.right }.thenBy { it.top })
        return horizontal + vertical
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PP-OCRv6 RECOGNITION — CTC decoding + char box computation
    // ═════════════════════════════════════════════════════════════════════════

    data class PPOcrResult(
        val text: String,
        val alternatives: List<List<Pair<Char, Float>>>,
        val charCols: FloatArray,   // CTC timestep positions
        val seqLenTotal: Int,
        /** Top-15 alternatives for EVERY timestep (including blanks), for cache re-decode. */
        val rawAlternatives: List<List<Pair<Char, Float>>> = emptyList(),
    )

    /**
     * Run PP-OCRv6 CTC recognition via ncnn buckets (no onnxruntime).
     * Cooperative cancellation: checks coroutineContext.isActive.
     */
    private suspend fun recognizePpocrBatch(
        crops: List<Bitmap>,
    ): List<PPOcrResult> {
        coroutineContext.ensureActive()
        val numCrops = crops.size
        if (numCrops == 0 || ppocrVocab.isEmpty() || recNcnnMap.isEmpty()) return emptyList()

        val targetH = REC_TARGET_H
        val results = arrayOfNulls<PPOcrResult?>(numCrops)
        val modelWidths = recNcnnMap.keys.sorted()

        for (ci in 0 until numCrops) {
            coroutineContext.ensureActive()
            val crop = crops[ci]
            val cw = crop.width; val ch = crop.height
            if (cw < 4 || ch < 4) continue

            val rotated: Bitmap = if (ch >= cw * 3 / 2) {
                val mat = android.graphics.Matrix().apply { postRotate(270f) }
                Bitmap.createBitmap(crop, 0, 0, cw, ch, mat, true)
            } else crop

            val rw = rotated.width; val rh = rotated.height
            // ——— Long-line split for >640px (rw*48/rh>640, also >960) — PP-OCR 48×480 crush fix ———
            // Very long lines (e.g. 976×39 → 1201→480, 1003×45→1070) crush timesteps and skip っ/punct.
            // Split into overlapping w480 chunks (20% overlap) via largest fitting bucket, then stitch.
            // Threshold 640 (not 480, 960 also valid; aspect >15:1 also) to avoid over-splitting 675×58→558→480 while fixing 726×50→696, preserve aspect cw*48/rh.
            val isLongHoriz = rw >= rh * 3 / 2 && (rw.toFloat() * targetH / rh.toFloat() > 640)
            val isLongVert = rh >= rw * 3 / 2 && (rh.toFloat() * targetH / rw.toFloat() > 640)
            if (isLongHoriz || isLongVert) {
                val stitched = if (isLongHoriz) {
                    recognizeAndStitchLongHoriz(rotated, targetH, modelWidths)
                } else {
                    recognizeAndStitchLongVert(rotated, targetH, modelWidths)
                }
                if (stitched != null) {
                    if (rotated !== crop) rotated.recycle()
                    results[ci] = stitched
                    continue
                }
                Log.w(TAG, "long-line stitch failed rw=$rw rh=$rh — falling through to crush")
            }
            val targetW = maxOf(4, minOf(modelWidths.last(),
                (rw.toFloat() * targetH / rh.toFloat()).roundToInt()
            ))
            val resized = Bitmap.createScaledBitmap(rotated, targetW, targetH, true)
            if (rotated !== crop) rotated.recycle()

            val modelW = modelWidths.first { it >= targetW }

            val pixels = IntArray(targetW * targetH)
            resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            resized.recycle()

            val inputFloats = FloatArray(1 * 3 * targetH * modelW)
            for (c in 0 until 3) {
                val cOff = c * targetH * modelW
                for (y in 0 until targetH) {
                    for (x in 0 until targetW) {
                        val px = pixels[y * targetW + x]
                        val gray = ((px shr 16 and 0xFF) * 0.299f +
                                    (px shr 8 and 0xFF) * 0.587f +
                                    (px and 0xFF) * 0.114f)
                        inputFloats[cOff + y * modelW + x] = gray / 128f - 1f
                    }
                }
            }

            val seqLen = modelW / 8
            val recNcnn = recNcnnMap[modelW]
            if (recNcnn == null) {
                Log.e(TAG, "recNcnn w$modelW missing — skip crop")
                continue
            }
            val flatOutput: FloatArray = recNcnn.infer(inputFloats, modelW, targetH)
                ?: run {
                    Log.e(TAG, "recNcnn w$modelW infer failed — skip crop")
                    continue
                }
            if (flatOutput.size != seqLen * REC_NUM_CLASSES) {
                Log.e(TAG, "recNcnn w$modelW bad output ${flatOutput.size} vs ${seqLen * REC_NUM_CLASSES}")
                continue
            }
            Log.d(TAG, "ncnn w$modelW infer ok seq=$seqLen")

            val actualSeqLen = maxOf(1, ceil(targetW / REC_STRIDE.toFloat()).toInt())
            val cropLogits: Array<*>? = Array<Any>(actualSeqLen) { t ->
                FloatArray(REC_NUM_CLASSES) { c -> flatOutput[t * REC_NUM_CLASSES + c] }
            } as Array<*>

            val rawAlts = mutableListOf<List<Pair<Char, Float>>>()
            for (t in 0 until actualSeqLen) {
                val slice = cropLogits?.getOrNull(t) as? FloatArray
                if (slice == null || slice.size < REC_NUM_CLASSES) {
                    rawAlts.add(emptyList())
                    continue
                }
                val pq = java.util.PriorityQueue<Int>(16, compareBy { slice[it] })
                for (k in slice.indices) { pq.add(k); if (pq.size > 15) pq.poll() }
                rawAlts.add(pq.toList().sortedByDescending { slice[it] }
                    .map { decodeChar(it) to slice[it] })
            }

            val result = ctcDecode(cropLogits, actualSeqLen, REC_NUM_CLASSES, 0f, actualSeqLen)
            results[ci] = result.copy(rawAlternatives = rawAlts)
        }

        return results.map { it ?: PPOcrResult("", emptyList(), floatArrayOf(), 0) }
    }

    // ——— Long-line split helpers — PP-OCR 48×960 crush fix ———
    // Very long horizontal >960px (976×39 → 1201) and vertical >960px crush timesteps.
    // Split into overlapping w480 chunks (20% overlap) via largest fitting bucket, then stitch.
    // Port of meiki b3babc7^ OcrEngine.kt 709: REC_WIDTH 960/32, maxChunkWidth 960/scale,
    // anchor second-to-last char localXLeft/nextX 0.8 + stitchHorizontalChunks centerX distance 30
    // + predictionScore 0.4 + interleaveAlternatives, scaled to PP-OCR 48×480 correctly.
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

    private suspend fun recognizeAndStitchLongHoriz(
        rotated: Bitmap, targetH: Int, modelWidths: List<Int>
    ): PPOcrResult? {
        coroutineContext.ensureActive()
        val rw = rotated.width; val rh = rotated.height
        val scale = targetH.toFloat() / rh.toFloat()
        val maxChunkW = (480 / scale).toInt().coerceAtLeast(64)
        if (maxChunkW <= 0) return null
        val chunkMargin = (rh * 0.1f).toInt().coerceAtLeast(2)

        // ——— Phase 1: chunk with anchor-driven nextX (meiki second-to-last char) ———
        val chunks = mutableListOf<ChunkInfo>()
        var x = 0
        while (x < rw) {
            coroutineContext.ensureActive()
            val w = minOf(maxChunkW, rw - x)
            if (w < 16) break
            val chunkBmp = Bitmap.createBitmap(rotated, x, 0, w, rh)
            val cw = chunkBmp.width; val ch = chunkBmp.height
            // preserve aspect w → targetW via cw*48/rh, not stretch; cap at 480
            val targetW = minOf(480, (cw.toFloat() * targetH / ch.toFloat()).roundToInt().coerceAtLeast(4))
            val modelW = modelWidths.firstOrNull { it >= targetW } ?: modelWidths.last()
            val resized = Bitmap.createScaledBitmap(chunkBmp, targetW, targetH, true)
            val pixels = IntArray(targetW * targetH)
            resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            resized.recycle()
            // do not recycle chunkBmp yet for anchor calc? we already have cw
            chunkBmp.recycle()
            val inputFloats = FloatArray(1 * 3 * targetH * modelW)
            for (c in 0 until 3) {
                val cOff = c * targetH * modelW
                for (y in 0 until targetH) for (xx in 0 until targetW) {
                    val px = pixels[y * targetW + xx]
                    val gray = ((px shr 16 and 0xFF) * 0.299f + (px shr 8 and 0xFF) * 0.587f + (px and 0xFF) * 0.114f)
                    inputFloats[cOff + y * modelW + xx] = gray / 128f - 1f
                }
            }
            val seqLen = modelW / 8
            val recNcnn = recNcnnMap[modelW] ?: run { Log.e(TAG, "recNcnn $modelW missing"); return null }
            val flatOutput = recNcnn.infer(inputFloats, modelW, targetH) ?: run { Log.e(TAG, "recNcnn $modelW infer null"); return null }
            val actualSeqLen = maxOf(1, ceil(targetW / REC_STRIDE.toFloat()).toInt())
            val cropLogits: Array<*>? = Array<Any>(actualSeqLen) { t -> FloatArray(REC_NUM_CLASSES) { c -> flatOutput[t * REC_NUM_CLASSES + c] } } as Array<*>
            val rawAlts = (0 until actualSeqLen).map { t ->
                val slice = cropLogits?.getOrNull(t) as? FloatArray ?: return@map emptyList<Pair<Char,Float>>()
                val pq = java.util.PriorityQueue<Int>(16, compareBy { slice[it] }); for (k in slice.indices) { pq.add(k); if(pq.size>15) pq.poll() }
                pq.toList().sortedByDescending { slice[it] }.map { decodeChar(it) to slice[it] }
            }
            val decoded = ctcDecode(cropLogits, actualSeqLen, REC_NUM_CLASSES, 0f, actualSeqLen)
            chunks.add(ChunkInfo(decoded.text, decoded.charCols, decoded.alternatives, rawAlts, actualSeqLen, targetW, cw, x, 0))
            if (x + w >= rw) break
            // anchor second-to-last char localXLeft/nextX 0.8 (meiki)
            val txt = decoded.text
            if (txt.isNotEmpty() && decoded.charCols.isNotEmpty()) {
                val anchorIdx = if (txt.length >= 2) txt.length - 2 else 0
                val anchorT = decoded.charCols.getOrNull(anchorIdx) ?: decoded.charCols.last()
                // localXLeft in chunk pixel coords: (t+0.5)/actualSeqLen * cw (center)
                val localXLeft = ((anchorT + 0.5f) / actualSeqLen.toFloat()) * cw
                val nextX = (x + localXLeft.toInt() - chunkMargin).coerceAtLeast(0)
                if (nextX <= x || nextX >= x + w - 10) {
                    x += (w * 0.8f).toInt().coerceAtLeast(16)
                } else {
                    x = nextX
                }
            } else {
                x += (w * 0.8f).toInt().coerceAtLeast(16)
            }
        }
        if (chunks.isEmpty()) return null
        if (chunks.size == 1) {
            val c = chunks[0]
            Log.d(TAG, "long-line stitch horiz rw=$rw rh=$rh chunks=1 stitchedLen=${c.text.length} seqLen=${c.actualSeqLen} text=${c.text.take(40)}")
            return PPOcrResult(c.text, c.altsPerChar, c.charCols, c.actualSeqLen, c.rawAltsPerTimestep)
        }
        // ——— Phase 2: stitch via anchor alignment with identical timestep size ———
        // Each timestep is identical: rh/6 px original (48/8 stride). Both chunks share same size.
        // Align second chunk's anchor perfectly over first's same anchor, then progress normally.
        // Finally scale positions to fit bbox length via totalSeqLen = ceil(rw*48/rh/8).
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
                    if (candPx > lastPx + 10f) {
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
            val pStart = maxOf(0, stitchedAlts.size - 10)
            val cEnd = minOf(currAlts.size, 10)
            for (pIdx in stitchedAlts.size - 1 downTo pStart) {
                val pGC = stitchedGlobal[pIdx]
                for (cIdx in 0 until cEnd) {
                    val cGX = currGlobal[cIdx]
                    val dist = abs(pGC - cGX)
                    if (dist > 30) continue
                    val pred = comparePredictionVectors(stitchedAlts[pIdx], currAlts[cIdx])
                    if (pred < 0.4f) continue
                    val score = (1f - dist / 30f) * 0.3f + pred * 0.7f
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
                    if (candPx > lastPx + 10f || (appended == 0 && currAlts.size == 1)) {
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

    private suspend fun recognizeAndStitchLongVert(
        rotated: Bitmap, targetH: Int, modelWidths: List<Int>
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
            val targetW = minOf(480, (cw.toFloat() * targetH / ch.toFloat()).roundToInt().coerceAtLeast(4))
            val modelW = modelWidths.firstOrNull { it >= targetW } ?: modelWidths.last()
            val resized = Bitmap.createScaledBitmap(chunkBmp, targetW, targetH, true)
            val pixels = IntArray(targetW * targetH)
            resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            resized.recycle(); chunkBmp.recycle()
            val inputFloats = FloatArray(1 * 3 * targetH * modelW)
            for (c in 0 until 3) { val cOff = c * targetH * modelW; for (yy in 0 until targetH) for (xx in 0 until targetW) {
                val px = pixels[yy * targetW + xx]; val gray = ((px shr 16 and 0xFF)*0.299f + (px shr 8 and 0xFF)*0.587f + (px and 0xFF)*0.114f)
                inputFloats[cOff + yy * modelW + xx] = gray/128f -1f
            }}
            val seqLen = modelW/8
            val recNcnn = recNcnnMap[modelW] ?: run { Log.e(TAG, "recNcnn $modelW missing"); return null }
            val flatOutput = recNcnn.infer(inputFloats, modelW, targetH) ?: run { Log.e(TAG, "recNcnn $modelW infer null"); return null }
            val actualSeqLen = maxOf(1, ceil(targetW/REC_STRIDE.toFloat()).toInt())
            val cropLogits: Array<*>? = Array<Any>(actualSeqLen){ t-> FloatArray(REC_NUM_CLASSES){ c-> flatOutput[t*REC_NUM_CLASSES+c] } } as Array<*>
            val rawAlts = (0 until actualSeqLen).map{ t->
                val s=cropLogits?.getOrNull(t) as? FloatArray ?: return@map emptyList<Pair<Char,Float>>()
                val pq=java.util.PriorityQueue<Int>(16, compareBy{ s[it] }); for(k in s.indices){ pq.add(k); if(pq.size>15) pq.poll() }
                pq.toList().sortedByDescending{ s[it] }.map{ decodeChar(it) to s[it] }
            }
            val decoded = ctcDecode(cropLogits, actualSeqLen, REC_NUM_CLASSES, 0f, actualSeqLen)
            chunks.add(ChunkInfo(decoded.text, decoded.charCols, decoded.alternatives, rawAlts, actualSeqLen, targetW, h, 0, y))
            if (y + h >= rh) break
            val txt = decoded.text
            if (txt.isNotEmpty() && decoded.charCols.isNotEmpty()) {
                val anchorIdx = if (txt.length >= 2) txt.length - 2 else 0
                val anchorT = decoded.charCols.getOrNull(anchorIdx) ?: decoded.charCols.last()
                val localYTop = ((anchorT + 0.5f) / actualSeqLen.toFloat()) * h
                val nextY = (y + localYTop.toInt() - chunkMargin).coerceAtLeast(0)
                if (nextY <= y || nextY >= y + h - 10) {
                    y += (h * 0.8f).toInt().coerceAtLeast(16)
                } else {
                    y = nextY
                }
            } else {
                y += (h * 0.8f).toInt().coerceAtLeast(16)
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
                    if (candPx > lastPx + 10f) {
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
            val pStart = maxOf(0, stitchedAlts.size - 10)
            val cEnd = minOf(currAlts.size, 10)
            for (pIdx in stitchedAlts.size - 1 downTo pStart) {
                val pGC = stitchedGlobal[pIdx]
                for (cIdx in 0 until cEnd) {
                    val cGY = currGlobal[cIdx]
                    val dist = abs(pGC - cGY)
                    if (dist > 30) continue
                    val pred = comparePredictionVectors(stitchedAlts[pIdx], currAlts[cIdx])
                    if (pred < 0.4f) continue
                    val score = (1f - dist/30f)*0.3f + pred*0.7f
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
                    if (candPx > lastPx + 10f || (appended==0 && currAlts.size==1)) {
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

    // helpers for stitch — ported from meiki
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

    private fun interleaveAlternatives(alt1: List<Pair<Char, Float>>, alt2: List<Pair<Char, Float>>): List<Pair<Char, Float>> {
        val merged = mutableMapOf<Char, Float>()
        alt1.forEach { (ch, sc) -> merged[ch] = sc }
        alt2.forEach { (ch, sc) ->
            val ex = merged[ch] ?: 0f
            if (ex > 0f) merged[ch] = (ex + sc) * 0.8f else merged[ch] = sc * 0.6f
        }
        return merged.toList().sortedByDescending { it.second }.take(15)
    }

    /**
     * CTC-decoded text + alternatives.  `blankThreshold` 0 means pure greedy
     * (default PP-OCR behaviour); values >0 insert [GAP_CHAR] placeholders for
     * blank timesteps where the top non-blank alternative score exceeds
     * `blank_threshold * blank_score`.
     */
    private fun ctcDecode(
        cropLogits: Array<*>?,
        seqLen: Int,
        numClasses: Int,
        blankThreshold: Float,
        seqLenTotal: Int,
    ): PPOcrResult {
        val text = StringBuilder()
        val alts = mutableListOf<MutableList<Pair<Char, Float>>>()
        val charCols = mutableListOf<Float>()
        var prevClass = 0

        for (t in 0 until seqLen) {
            val slice = cropLogits?.getOrNull(t) as? FloatArray
            if (slice == null || slice.size < numClasses) continue

            // Argmax
            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (k in slice.indices) {
                if (slice[k] > maxVal) { maxVal = slice[k]; maxIdx = k }
            }

            val classIdx = maxIdx

            // Collect top-15 alternatives
            val indexed = {
                val pq = java.util.PriorityQueue<Int>(16, compareBy { slice[it] })
                for (k in slice.indices) {
                    pq.add(k)
                    if (pq.size > 15) pq.poll()
                }
                pq.toList().sortedByDescending { slice[it] }
                    .map { decodeChar(it) to slice[it] }
                    .toMutableList()
            }()

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
                            charCols.add(t.toFloat())
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
                    charCols.add(t.toFloat())
                    alts.add(indexed)
                }
                classIdx == prevClass -> { /* collapse repeat */ }
                else -> {
                    val ch = decodeChar(classIdx)
                    if (ch != '\uFFFD') {
                        text.append(ch)
                        charCols.add(t.toFloat())
                        alts.add(indexed)
                        prevClass = classIdx
                    }
                }
            }
        }

        return PPOcrResult(text.toString(), alts, charCols.toFloatArray(), seqLenTotal)
    }

    private fun decodeChar(classIdx: Int): Char {
        return when {
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
    //  Char box computation from CTC timestep positions
    //  (mirrors accessibility_daemon/src/ocr_engine.rs logic exactly)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Compute per-character bounding boxes from CTC timestep columns.
 * The logic matches accessibility_daemon exactly:
 *   - Horizontal: x-axis centers from (t+0.5)*avg_col_width, split overlaps evenly
 *   - Vertical:   y-axis centers, with punctuation squashing/expansion
 */
fun computeCharBoxes(
        text: String,
        charCols: FloatArray,
        seqLenTotal: Int,
        cropX: Int, cropY: Int,
        cropW: Int, cropH: Int,
        isVertical: Boolean,
    ): List<JpDictRect> {
        val n = charCols.size
        if (n == 0 || seqLenTotal <= 0) return emptyList()

        if (!isVertical) {
            // ── HORIZONTAL: x-axis char boxes ──
            val avgColW = cropW.toFloat() / seqLenTotal.toFloat()
            val charW = maxOf(cropH.toFloat(), 3f)

            val cells = charCols.map { t ->
                val c = (t + 0.5f) * avgColW
                val half = charW / 2f
                (maxOf(c - half, 0f)) to (minOf(c + half, cropW.toFloat()))
            }.sortedBy { it.first }

            // Resolve overlaps
            val resolved = cells.toMutableList()
            for (ci in 0 until n - 1) {
                if (resolved[ci].second <= resolved[ci + 1].first) continue
                val half = (resolved[ci].second - resolved[ci + 1].first) / 2f
                resolved[ci] = resolved[ci].first to (resolved[ci].second - half)
                resolved[ci + 1] = (resolved[ci + 1].first + half) to resolved[ci + 1].second
            }

            return resolved.map { (xl, xr) ->
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
            Log.d(TAG, "vert char spacing: n=$n seqLenTotal=$seqLenTotal cropH=$cropH cropW=$cropW avgColW=$avgColW avgChH=$avgChH trailingNulls=${seqLenTotal - (charCols.lastOrNull()?.toInt()?.plus(1) ?: seqLenTotal)}")

            val cells = charCols.map { t ->
                val c = (t + 0.5f) * avgColW
                val half = avgChH / 2f
                (maxOf(c - half, 0f)) to (minOf(c + half, cropH.toFloat()))
            }.sortedBy { it.first }

            val isCp = text.map { ch ->
                ch in "。.．、,，)）〕》」』】〙〗〟’”］"
            }
            val isOp = text.map { ch ->
                ch in "(（〔《「『【〘〖〝‘“［"
            }

            // Resolve overlaps with punctuation rules
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

            // Expand punctuation cells to average non-punctuation height
            val avgNpH = resolved.filterIndexed { i, _ -> !isCp[i] && !isOp[i] }
                .let { hs -> if (hs.isEmpty()) cropW.toFloat() else hs.sumOf { (it.second - it.first).toDouble() }.toFloat() / hs.size }
            for (ci in 0 until n) {
                val (yt, yb) = resolved[ci]
                if (isCp[ci]) {
                    val nx = ((ci + 1) until n)
                        .firstOrNull { !isCp[it] && !isOp[it] }
                        ?.let { resolved[it].first } ?: Float.POSITIVE_INFINITY
                    resolved[ci] = yt to maxOf(yb, minOf(yt + avgNpH, nx))
                } else if (isOp[ci]) {
                    val pl = (0 until ci)
                        .lastOrNull { !isCp[it] && !isOp[it] }
                        ?.let { resolved[it].second } ?: Float.NEGATIVE_INFINITY
                    resolved[ci] = minOf(yt, maxOf(pl, yb - avgNpH)) to yb
                }
            }

            return resolved.map { (yt, yb) ->
                val ch = maxOf(yb - yt, 1f)
                JpDictRect(
                    cropX, (cropY + yt).roundToInt(),
                    cropX + cropW, (cropY + yt + ch).roundToInt()
                )
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Streaming recognition — pool-based parallelism
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Recognize all detected line boxes using the PP-OCR recognition pool.
     * Both horizontal and vertical lines use the SAME model (same session pool).
     * The only difference is pre-processing (portrait rotation) and
     * post-processing (char box computation in X vs Y axis).
     *
     * Workers pull jobs from a concurrent queue — matches accessibility_daemon.
     */
    suspend fun recognizeStreaming(
        bitmap: Bitmap,
        lineBoxes: List<JpDictRect>,
        onLinesRecognized: (List<Pair<Int, LineResult>>) -> Unit
    ) = coroutineScope {
        val startTime = System.currentTimeMillis()
        if (recNcnnMap.isEmpty() || ppocrVocab.isEmpty()) return@coroutineScope

        // Build job queue: crop each box, determine orientation
        data class Job(val idx: Int, val bbox: JpDictRect, val crop: Bitmap, val isVertical: Boolean)

        val jobs = mutableListOf<Job>()
        for ((i, box) in lineBoxes.withIndex()) {
            val cropX = maxOf(box.left, 0)
            val cropY = maxOf(box.top, 0)
            val cropW = minOf(bitmap.width - cropX, box.width()).coerceAtLeast(1)
            val cropH = minOf(bitmap.height - cropY, box.height()).coerceAtLeast(1)
            val crop = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
            if (crop.width < 4 || crop.height < 4) { crop.recycle(); continue }
            jobs.add(Job(i, box, crop, box.height() > box.width()))
        }

        if (jobs.isEmpty()) return@coroutineScope

        Log.d(TAG, "Processing ${jobs.size} boxes in batches of $BATCH_SIZE")

        // Sort by effective width so similar-sized lines batch together,
        // minimising padding waste in the recognition model.
        val sortedJobs = jobs.sortedBy { job ->
            if (job.isVertical) job.bbox.height() else job.bbox.width()
        }

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        // Process in batches — cooperative cancellation for #18 (overlay closed → cancel)
        for ((batchIdx, batch) in sortedJobs.chunked(BATCH_SIZE).withIndex()) {
            coroutineContext.ensureActive()
            val tBatch = System.nanoTime()
            try {
                // Early exit if cancelled before batch
                if (!coroutineContext.isActive) break
                val crops = batch.map { it.crop }
                val ppocrResults = recognizePpocrBatch(crops)

                for ((index, job) in batch.withIndex()) {
                    val result = ppocrResults.getOrNull(index) ?: continue
                    if (result.text.isEmpty()) continue

                    val charBoxes = computeCharBoxes(
                        result.text, result.charCols, result.seqLenTotal,
                        job.bbox.left, job.bbox.top,
                        job.bbox.width(), job.bbox.height(),
                        job.isVertical,
                    )

                    val finalText = if (job.isVertical) {
                        result.text.map { ch -> toVerticalGlyph(ch) }.joinToString("")
                    } else result.text
                    val finalAlts = if (job.isVertical) {
                        result.alternatives.map { alts ->
                            alts.map { (ch, s) -> toVerticalGlyph(ch) to s }.toMutableList()
                        }
                    } else result.alternatives.map { it.toMutableList() }

                    val lineResult = LineResult(
                        text = finalText,
                        charBoxes = charBoxes,
                        alternatives = finalAlts,
                        isVertical = job.isVertical,
                        rawAlternatives = result.rawAlternatives.map { row ->
                            if (job.isVertical) row.map { (ch, s) -> toVerticalGlyph(ch) to s }
                            else row
                        },
                        seqLenTotal = result.seqLenTotal,
                        cropW = job.bbox.width(),
                        cropH = job.bbox.height(),
                        cropX = job.bbox.left,
                        cropY = job.bbox.top,
                    )

                    val elapsed = (System.nanoTime() - tBatch) / 1_000_000
                    Log.d(TAG, "Batch $batchIdx job ${job.idx} (${job.bbox.width()}x${job.bbox.height()}${if (job.isVertical) "V" else "H"}) "
                            + "→ '${finalText}' ${charBoxes.size} chars in ${elapsed}ms")

                    // Stream result to UI immediately
                    mainHandler.post { onLinesRecognized(listOf(job.idx to lineResult)) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch $batchIdx failed", e)
            }
        }

        Log.d(TAG, "All batches finished")

        // Recycle all crop bitmaps
        jobs.forEach { it.crop.recycle() }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Streaming recognition for ${lineBoxes.size} lines took ${elapsed}ms")
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Re-processing with new threshold (re-CTC-decode only, no re-inference)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Re-process a single line's recognition from cached raw data.
     * Since PP-OCR CTC decoding is post-inference, we can adjust thresholds
     * without re-running the model (the logits are discarded after first decode).
     *
     * For simplicity, this re-runs recognition. In the Rust version the raw
     * logits are cached for threshold adjustment, but for Android it's fast
     * enough to re-run.
     */
    suspend fun processLineFromRawChunks(
        oldLine: LineResult,
        crop: Bitmap,
    ): LineResult {
        if (recNcnnMap.isEmpty()) return oldLine
        try {
            val ppocrResults = recognizePpocrBatch(listOf(crop))
            if (ppocrResults.isEmpty()) return oldLine
            val result = ppocrResults[0]
            if (result.text.isEmpty()) return oldLine

            // Use actual crop bitmap dimensions
            val isVertical = oldLine.isVertical
            val cropW = crop.width; val cropH = crop.height
            val cropX = oldLine.charBoxes.firstOrNull()?.left ?: 0
            val cropY = oldLine.charBoxes.firstOrNull()?.top ?: 0

            val charBoxes = computeCharBoxes(
                result.text, result.charCols, result.seqLenTotal,
                cropX, cropY, cropW, cropH, isVertical,
            )

            val finalText = if (isVertical) {
                result.text.map { toVerticalGlyph(it) }.joinToString("")
            } else result.text
            val finalAlts = if (isVertical) {
                result.alternatives.map { alts ->
                    alts.map { (ch, s) -> toVerticalGlyph(ch) to s }.toMutableList()
                }
            } else result.alternatives.map { it.toMutableList() }

            val newLine = LineResult(
                text = finalText,
                charBoxes = charBoxes,
                alternatives = finalAlts,
                isVertical = isVertical,
            )
            // Apply overrides by char index to the new text
            val textChars = newLine.text.toCharArray()
            for ((charIdx, override) in oldLine.overrides) {
                if (charIdx in textChars.indices) {
                    textChars[charIdx] = override.first
                }
            }
            newLine.text = String(textChars)
            newLine.overrides.putAll(oldLine.overrides)
            return newLine
        } catch (e: Exception) {
            Log.e(TAG, "processLineFromRawChunks failed", e)
            return oldLine
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Japanese text utilities
    /**
     * Re-decode a [LineResult] from its cached [rawAlternatives] without
     * re-running recognition.  [blankThreshold] 0 = default (nothing shown
     * for blanks); values > 0 insert [GAP_CHAR] placeholders for blank
     * timesteps with a non-blank alternative exceeding `blank × blankThreshold`.
     */
    fun reDecodeLineResult(oldLine: LineResult, blankThreshold: Float): LineResult {
        val raw = oldLine.rawAlternatives
        if (raw.isEmpty()) return oldLine

        val text = StringBuilder()
        val newAlts = mutableListOf<MutableList<Pair<Char, Float>>>()
        val charCols = mutableListOf<Float>()
        var prevChar: Char? = null

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
                            charCols.add(t.toFloat())
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
                    charCols.add(t.toFloat())
                    newAlts.add(alts.toMutableList())
                }
                topChar == prevChar -> { /* collapse */ }
                else -> {
                    text.append(topChar)
                    charCols.add(t.toFloat())
                    newAlts.add(alts.toMutableList())
                    prevChar = topChar
                }
            }
        }

        val newCharBoxes = if (oldLine.cropW > 0 && oldLine.cropH > 0) {
            computeCharBoxes(
                text.toString(), charCols.toFloatArray(), oldLine.seqLenTotal,
                oldLine.cropX, oldLine.cropY, oldLine.cropW, oldLine.cropH,
                oldLine.isVertical,
            )
        } else oldLine.charBoxes

        return LineResult(
            text = text.toString(),
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
        )
    }

    fun close() {
        try { detNcnn?.close() } catch (_: Exception) {}
        recNcnnMap.values.forEach { try { it.close() } catch (_: Exception) {} }
    }
}

// Map horizontal glyphs to vertical equivalents (file-level for easy access)
private val VERTICAL_GLYPH_MAP = mapOf(
    '「' to '「', '」' to '」', '『' to '『', '』' to '』',
    '（' to '（', '）' to '）', '［' to '［', '］' to '］',
    '〔' to '〔', '〕' to '〕', '｛' to '｛', '｝' to '｝',
    '〈' to '〈', '〉' to '〉', '《' to '《', '》' to '》',
    '【' to '【', '】' to '】', '〘' to '〘', '〙' to '〙',
    '〚' to '〚', '〛' to '〛',
    '、' to '、', '。' to '。', '・' to '・',
    '—' to '—', '…' to '…', '‥' to '‥',
    '〜' to '〜',
    'ー' to '｜', // chōonpu → vertical bar
)

private fun toVerticalGlyph(ch: Char): Char {
    return VERTICAL_GLYPH_MAP[ch] ?:
        // Dash/hyphen → vertical bar
        if (ch == '-' || ch == '‐' || ch == '–' || ch == '—') '｜'
        else ch
}


