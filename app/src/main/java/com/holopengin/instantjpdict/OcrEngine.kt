package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    // PP-OCRv6 detection session
    private var detectSession: OrtSession? = null

    // PP-OCRv6 recognition session
    private var recSession: OrtSession? = null
    private var ppocrVocab: List<String> = emptyList()

    companion object {
        private const val TAG = "PPOCREngine"

        // Detection constants
        private const val PPOCR_DET_LONG_SIDE = 960
        private const val PPOCR_DET_THRESH = 0.3f
        private const val PPOCR_DET_UNCLIP_RATIO = 1.1f
        private const val X_OVERLAP_THRESHOLD = 0.3f

        // Recognition constants
        private const val REC_TARGET_H = 48
        private const val REC_NUM_CLASSES = 18710  // 0=blank, 1..18708=chars, 18709=space
        private const val REC_CONFIDENCE_THRESHOLD = 0.1f
        private const val BATCH_SIZE = 1
        const val GAP_CHAR = '\u25CC'
    }

    init {
        try {
            // ── Load PP-OCRv6 detection model ──
            val detOptions = SessionOptions()
            detOptions.addNnapi()
            detOptions.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            detOptions.setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
            detectSession = env.createSession(
                loadModel("PP-OCRv6_small_det_onnx/inference.onnx"), detOptions
            )
            Log.d(TAG, "Detection model loaded (NNAPI+XNNPACK)")

            // ── Load PP-OCRv6 recognition session ──
            val recOptions = SessionOptions()
            recOptions.addNnapi()
            // XNNPACK on ARM64 is typically faster than NNAPI fallback for CNN+BiLSTM
            recOptions.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            recOptions.setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
            recSession = env.createSession(
                loadModel("PP-OCRv6_small_rec_onnx/inference.onnx"), recOptions
            )
            Log.d(TAG, "Recognition session loaded (NNAPI+XNNPACK)")

            // ── Load vocabulary ──
            val vocabJson = context.assets.open("PP-OCRv6_small_rec_onnx/vocab.json")
                .bufferedReader().use { it.readText() }
            val listType = object : TypeToken<List<String>>() {}.type
            ppocrVocab = Gson().fromJson(vocabJson, listType)
            Log.d(TAG, "Vocabulary loaded: ${ppocrVocab.size} entries")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load models", e)
        }
    }

    private fun loadModel(path: String): ByteArray {
        return context.assets.open(path).readBytes()
    }

    fun isReady(): Boolean =
        detectSession != null && recSession != null && ppocrVocab.isNotEmpty()

    // ═════════════════════════════════════════════════════════════════════════
    //  PP-OCRv6 DETECTION (DB segmentation → contours → bounding boxes)
    // ═════════════════════════════════════════════════════════════════════════

    fun detect(bitmap: Bitmap): List<JpDictRect> {
        val session = detectSession ?: return emptyList()
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()

        // 1. Resize keeping aspect ratio, longest side = 960
        val scale = PPOCR_DET_LONG_SIDE.toFloat() / maxOf(origW, origH)
        val resizeW = maxOf((origW * scale).roundToInt(), 32)
        val resizeH = maxOf((origH * scale).roundToInt(), 32)

        // Pad to multiples of 32
        val padW = ((resizeW + 31) / 32) * 32
        val padH = ((resizeH + 31) / 32) * 32

        val resized = Bitmap.createScaledBitmap(bitmap, resizeW, resizeH, true)

        // 2. Build NCHW input with ImageNet normalisation (pad with gray 128)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val w = padW
        val h = padH
        val n = 3 * h * w
        val imgData = FloatArray(n)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val px: Int
                val xClip = x.coerceAtMost(resizeW - 1)
                val yClip = y.coerceAtMost(resizeH - 1)
                px = resized.getPixel(xClip, yClip)

                val b = ((px shr 16 and 0xFF) / 255f - mean[0]) / std[0]
                val g = ((px shr 8 and 0xFF) / 255f - mean[1]) / std[1]
                val r = ((px and 0xFF) / 255f - mean[2]) / std[2]

                val idx = y * w + x
                imgData[idx] = b
                imgData[h * w + idx] = g
                imgData[2 * h * w + idx] = r
            }
        }
        resized.recycle()

        val inputTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(imgData), longArrayOf(1, 3, h.toLong(), w.toLong())
        )

        // 3. Run detection
        val inputs = mutableMapOf<String, OnnxTensor>()
        val inputName = session.inputNames.iterator().next()
        Log.d(TAG, "detect: input name='$inputName' shape=[1,3,$h,$w] total=${imgData.size} resize=${resizeW}x${resizeH}")
        inputs[inputName] = inputTensor

        val results = session.run(inputs)
        Log.d(TAG, "detect: output names=${session.outputNames.toList()}")
        inputs.values.forEach { it.close() }

        // 4. Extract probability map [1,1,outH,outW]
        val outputName = session.outputNames.iterator().next()
        val outputVal = results.get(outputName).get()
        val outputTensor = outputVal as? ai.onnxruntime.OnnxTensor
        val probShape = outputTensor?.info?.shape
        val outH = if (probShape != null && probShape.size == 4) probShape[2].toInt() else h
        val outW = if (probShape != null && probShape.size == 4) probShape[3].toInt() else w
        Log.d(TAG, "output name=$outputName shape=${probShape?.contentToString()} outH=$outH outW=$outW")

        // Read output directly from OnnxTensor
        val probArr: FloatArray
        if (outputTensor != null && outH > 0 && outW > 0) {
            val value = outputTensor.value
            when (value) {
                is java.nio.FloatBuffer -> {
                    val remaining = value.remaining()
                    Log.d(TAG, "output FloatBuffer remaining=$remaining expected=${outH * outW}")
                    probArr = FloatArray(remaining)
                    value.duplicate().get(probArr)
                }
                is Array<*> -> {
                    // ONNX Runtime returns 4D Java arrays on Android ([[[[F)
                    // Flatten to 1D: shape [1,1,outH,outW]
                    Log.d(TAG, "output is Array<*> (${value.javaClass.name}) dims=${value.size}")
                    val expected = 1 * 1 * outH * outW
                    val flat = mutableListOf<Float>()
                    flattenFloats(value, flat)
                    Log.d(TAG, "flattened ${flat.size} values, expected $expected")
                    probArr = flat.toFloatArray()
                }
                else -> {
                    Log.w(TAG, "unexpected output value type: ${value?.javaClass?.name}")
                    probArr = extractFloatArray(value) ?: floatArrayOf()
                }
            }
        } else {
            probArr = extractFloatArray(outputVal.value) ?: floatArrayOf()
        }
        Log.d(TAG, "probArr size=${probArr.size}")

        // 5. Threshold → binary image
        val probMap = Array(outH) { y ->
            FloatArray(outW) { x ->
                probArr.getOrElse(y * outW + x) { 0f }
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

        // Scale factors from padded model output to original image
        // The model output covers the entire padded area. Image content
        // occupies only resizeW × resizeH within the padded space.
        val scaleWOut = origW / resizeW.toFloat()
        val scaleHOut = origH / resizeH.toFloat()
        val outScaleW = w.toFloat() / outW.toFloat()
        val outScaleH = h.toFloat() / outH.toFloat()

        // 6. Find connected components (contours) via simple flood-fill
        val visited = Array(outH) { BooleanArray(outW) }
        val rawBoxes = mutableListOf<JpDictRect>()

        for (y in 0 until outH) {
            for (x in 0 until outW) {
                if (visited[y][x] || probMap[y][x] <= PPOCR_DET_THRESH) continue

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
                                !visited[ny][nx] && probMap[ny][nx] > PPOCR_DET_THRESH
                            ) {
                                visited[ny][nx] = true
                                queue.addLast(nx to ny)
                            }
                        }
                    }
                }

                if (pixelCount < 3) continue // noise filter

                // Convert from output coords to original image coords
                val bx = (minX.toFloat() * outScaleW * scaleWOut).roundToInt()
                val by = (minY.toFloat() * outScaleH * scaleHOut).roundToInt()
                val bx2 = ((maxX + 1).toFloat() * outScaleW * scaleWOut).roundToInt()
                val by2 = ((maxY + 1).toFloat() * outScaleH * scaleHOut).roundToInt()

                // Unclip: expand box using proper PP-OCR formula
                val bw = (bx2 - bx).toFloat()
                val bh = (by2 - by).toFloat()
                val area = bw * bh
                val perimeter = 2f * (bw + bh)
                val expand = if (perimeter > 0f) area * PPOCR_DET_UNCLIP_RATIO / perimeter else 0f

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
        if (iom < X_OVERLAP_THRESHOLD) return false

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
     * Run PP-OCRv6 CTC recognition on multiple image crops in one batch.
     * Input: crops preprocessed to height 48, variable width (padded).
     * Output: CTC-decoded text + char boxes + alternatives.
     */
    private fun recognizePpocrBatch(
        sess: OrtSession,
        crops: List<Bitmap>,
    ): List<PPOcrResult> {
        val numCrops = crops.size
        if (numCrops == 0 || ppocrVocab.isEmpty()) return emptyList()

        // ── Preprocess each crop ──
        data class PrepRes(val width: Int, val seqLen: Int, val data: FloatArray)
        val targetH = REC_TARGET_H
        val prepped = mutableListOf<PrepRes>()
        var maxW = 0

        for (crop in crops) {
            val cw = crop.width; val ch = crop.height
            if (cw < 4 || ch < 4) {
                prepped.add(PrepRes(0, 0, FloatArray(0)))
                continue
            }

            // For portrait crops (height >= 1.5× width), rotate 270° CCW
            // to make them horizontal for the horizontal-only recognition model.
            val rotated: Bitmap = if (ch >= cw * 3 / 2) {
                val mat = android.graphics.Matrix().apply { postRotate(270f) }
                Bitmap.createBitmap(crop, 0, 0, cw, ch, mat, true)
            } else {
                crop
            }

            val rw = rotated.width; val rh = rotated.height
            val targetW = maxOf(4, minOf(3200,
                (rw.toFloat() * targetH / rh.toFloat()).roundToInt()
            ))

            val resized = Bitmap.createScaledBitmap(rotated, targetW, targetH, true)
            if (rotated !== crop) rotated.recycle()

            // Grayscale → pixel/128 - 1 → 3 channels
            val n = targetH * targetW
            val data = FloatArray(3 * n)
            val pixels = IntArray(targetW * targetH)
            resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            resized.recycle()

            for (yi in 0 until targetH) {
                for (xi in 0 until targetW) {
                    val px = pixels[yi * targetW + xi]
                    val gray = ((px shr 16 and 0xFF) * 0.299f +
                                (px shr 8 and 0xFF) * 0.587f +
                                (px and 0xFF) * 0.114f)
                    val val_ = gray / 128f - 1f
                    val idx = yi * targetW + xi
                    data[idx] = val_
                    data[n + idx] = val_
                    data[2 * n + idx] = val_
                }
            }

            val seqLen = maxOf(1, ceil(targetW / 4f).toInt())
            maxW = maxOf(maxW, targetW)
            prepped.add(PrepRes(targetW, seqLen, data))
        }

        // ── Build batched input tensor (pad to maxW with zeros) ──
        val nChan = 3
        val total = nChan * targetH * maxW
        val batchData = FloatArray(numCrops * total)

        for (i in prepped.indices) {
            val p = prepped[i]
            if (p.width == 0) continue
            val sw = p.width
            val n = targetH * sw
            for (c in 0 until nChan) {
                for (y in 0 until targetH) {
                    val srcOff = c * n + y * sw
                    val dstOff = i * total + c * targetH * maxW + y * maxW
                    System.arraycopy(p.data, srcOff, batchData, dstOff, sw)
                }
            }
        }

        // ── Run inference ──
        val inputShape = longArrayOf(numCrops.toLong(), 3, targetH.toLong(), maxW.toLong())
        val inputTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(batchData), inputShape
        )
        val inputs = mutableMapOf<String, OnnxTensor>()
        val inputName = sess.inputNames.iterator().next()
        inputs[inputName] = inputTensor

        val outputs = sess.run(inputs)
        inputs.values.forEach { it.close() }

        // Extract logits — expected shape [N, seqLenTotal, REC_NUM_CLASSES]
        val outputName = sess.outputNames.iterator().next()
        val outputVal = outputs.get(outputName).get()
        val outputTensor = outputVal as? ai.onnxruntime.OnnxTensor
        val logitShape = outputTensor?.info?.shape
        Log.d(TAG, "rec output name=$outputName shape=${logitShape?.contentToString()}")

        // Decode directly from the nested Java array to avoid massive flatten copies.
        // ONNX Runtime on Android returns [[[F (Array<Array<FloatArray>>) for 3D output.
        // Shape is [batch=1, seqLen, 18710].
        val numClasses = REC_NUM_CLASSES
        val seqLenTotal = if (logitShape != null && logitShape.size == 3) logitShape[1].toInt() else 0

        val outputArray = outputTensor?.value as? Array<*>
        outputs.close()

        // ── CTC decode ──
        val results = mutableListOf<PPOcrResult>()

        for (i in 0 until numCrops) {
            val seqLen = minOf(prepped[i].seqLen, seqLenTotal)

            // Get logits for this crop as Array<FloatArray> (2D: [seqLen, 18710])
            val cropLogits = outputArray?.getOrNull(i) as? Array<*>

            // Collect top-15 alternatives for EVERY timestep (including blanks).
            val rawAlts = mutableListOf<List<Pair<Char, Float>>>()
            for (t in 0 until seqLen) {
                val slice = cropLogits?.getOrNull(t) as? FloatArray
                if (slice == null || slice.size < numClasses) {
                    rawAlts.add(emptyList())
                    continue
                }
                // Priority queue keeps top 15 in O(N log 15) instead of O(N log N) full sort
                val pq = java.util.PriorityQueue<Int>(16, compareBy { slice[it] })
                for (k in slice.indices) {
                    pq.add(k)
                    if (pq.size > 15) pq.poll()
                }
                val top15 = pq.toList().sortedByDescending { slice[it] }
                rawAlts.add(top15.map { decodeChar(it) to slice[it] })
            }

            // Decode with blank threshold = 0.0 (default: no placeholders)
            val result = ctcDecode(cropLogits, seqLen, numClasses, 0f, seqLenTotal)
            results.add(result.copy(rawAlternatives = rawAlts))
        }

        return results
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
            val avgColW = cropH.toFloat() / seqLenTotal.toFloat()
            val avgChH = if (n > 1) {
                val span = charCols.last() - charCols.first()
                maxOf((span / (n - 1).toFloat()) * avgColW, 3f)
            } else {
                maxOf(avgColW, 3f)
            }

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
        if (recSession == null || ppocrVocab.isEmpty()) return@coroutineScope

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
        val sess = recSession ?: return@coroutineScope

        // Process in batches of BATCH_SIZE, delivering each result as it completes.
        for ((batchIdx, batch) in sortedJobs.chunked(BATCH_SIZE).withIndex()) {
            val tBatch = System.nanoTime()
            try {
                val crops = batch.map { it.crop }
                val ppocrResults = recognizePpocrBatch(sess, crops)

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
    fun processLineFromRawChunks(
        oldLine: LineResult,
        crop: Bitmap,
    ): LineResult {
        val sess = recSession ?: return oldLine
        try {
            val ppocrResults = recognizePpocrBatch(sess, listOf(crop))
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
    //  ONNX Runtime helpers
    // ═════════════════════════════════════════════════════════════════════════

    private fun extractFloatArray(value: Any): FloatArray? {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val list = value.mapNotNull { it as? Float }
                if (list.isNotEmpty()) list.toFloatArray() else null
            }
            else -> {
                try {
                    (value as? java.nio.Buffer)?.let { buf ->
                        if (buf is java.nio.FloatBuffer) {
                            val arr = FloatArray(buf.remaining())
                            buf.duplicate().get(arr)
                            arr
                        } else null
                    }
                } catch (e: Exception) { null }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Japanese text utilities
    // ═════════════════════════════════════════════════════════════════════════

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
        try { detectSession?.close() } catch (_: Exception) {}
        try { recSession?.close() } catch (_: Exception) {}
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

/** Recursively flatten nested Java arrays of Float into a MutableList. */
private fun flattenFloats(value: Any?, out: MutableList<Float>) {
    when (value) {
        is FloatArray -> { for (v in value) out.add(v) }
        is DoubleArray -> { for (v in value) out.add(v.toFloat()) }
        is IntArray -> { for (v in value) out.add(v.toFloat()) }
        is LongArray -> { for (v in value) out.add(v.toFloat()) }
        is Array<*> -> { for (e in value) flattenFloats(e, out) }
        is Number -> out.add(value.toFloat())
    }
}
