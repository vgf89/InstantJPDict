package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import com.google.gson.Gson
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OcrEngine(private val context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var detectSession: OrtSession? = null
    private var recognizeSession: OrtSession? = null
    private var recognizeSessionVertical: OrtSession? = null
    private var charVocab: IntArray? = null

    companion object {
        private const val DETECT_WIDTH = 960
        private const val DETECT_HEIGHT = 544
        private const val REC_WIDTH = 960
        private const val REC_HEIGHT = 32
        private const val VERT_REC_WIDTH = 32
        private const val VERT_REC_HEIGHT = 480
        private const val BATCH_SIZE = 8
        private const val REC_CONFIDENCE_THRESHOLD = 0.1f
        private const val X_OVERLAP_THRESHOLD = 0.3f
        private val MEIKI_SWAPPED_PAIRS = mapOf(
            "儡傀" to "傀儡",
            "談冗" to "冗談",
            "汰淘" to "淘汰",
            "沱滂" to "滂沱",
            "攣痙" to "痙攣",
            "酊酩" to "酩酊",
            "麭麺" to "麺麭",
            "哭慟" to "慟哭",
        )
    }

    init {
        try {
            val options = SessionOptions()
            options.addNnapi()
            options.setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)

            detectSession = env.createSession(loadModel("meiki.text.detect.v0.1.960x544.onnx"), options)
            recognizeSession = env.createSession(loadModel("meiki.text.rec.v0.960x32.with_logits.onnx"), options)
            recognizeSessionVertical = env.createSession(loadModel("meiki.text.rec.v0.vertical.32x480.with_logits.onnx"), options)

            // Load character vocabulary mapping
            try {
                val vocabJson = context.assets.open("char_vocab.json").bufferedReader().use { it.readText() }
                charVocab = Gson().fromJson(vocabJson, IntArray::class.java)
                Log.d("MeikiOcrEngine", "Loaded vocabulary of size: ${charVocab?.size}")
            } catch (ve: Exception) {
                Log.e("MeikiOcrEngine", "Failed to load char_vocab.json", ve)
            }

            Log.d("MeikiOcrEngine", "Models loaded successfully with NNAPI acceleration")
        } catch (e: Exception) {
            Log.e("MeikiOcrEngine", "Failed to load models", e)
        }
    }

    private fun loadModel(fileName: String): ByteArray {
        return context.assets.open(fileName).readBytes()
    }

    fun isReady(): Boolean = detectSession != null && recognizeSession != null && recognizeSessionVertical != null

    fun detect(bitmap: Bitmap): List<JpDictRect> {
        val session = detectSession ?: return emptyList()
        val resized = Bitmap.createScaledBitmap(bitmap, DETECT_WIDTH, DETECT_HEIGHT, true)
        val imgData = bitmapToFloatBuffer(resized, DETECT_WIDTH, DETECT_HEIGHT)

        val inputTensor = OnnxTensor.createTensor(env, imgData, longArrayOf(1, 3, DETECT_HEIGHT.toLong(), DETECT_WIDTH.toLong()))
        val inputs = mutableMapOf<String, OnnxTensor>()
        val imageInputName = session.inputNames.find { it.contains("image") || it.contains("input") } ?: session.inputNames.iterator().next()
        inputs[imageInputName] = inputTensor

        if (session.inputNames.contains("orig_target_sizes")) {
            val sizeData = LongBuffer.wrap(longArrayOf(bitmap.width.toLong(), bitmap.height.toLong()))
            inputs["orig_target_sizes"] = OnnxTensor.createTensor(env, sizeData, longArrayOf(1, 2))
        }

        val results = session.run(inputs)
        val detectedBoxes = mutableListOf<JpDictRect>()

        try {
            val outputNames = session.outputNames.toList()
            val boxesResult = results.get(outputNames.find { it.contains("boxes") } ?: outputNames[0])
            val scoresResult = results.get(outputNames.find { it.contains("scores") } ?: outputNames[1])

            if (boxesResult.isPresent && scoresResult.isPresent) {
                val boxesArr = extractFloatArray2D(boxesResult.get().value) ?: emptyArray()
                val scoresArr = extractFloatArray(scoresResult.get().value) ?: floatArrayOf()

                for (i in scoresArr.indices) {
                    if (scoresArr[i] > 0.4f) {
                        val box = boxesArr[i]
                        var left = box[0].toInt()
                        var top = box[1].toInt()
                        var right = box[2].toInt()
                        var bottom = box[3].toInt()

                        // Add small margins to avoid clipping
                        if (bottom - top > right - left) {
                            // Vertical lines: top and right margins
                            val vMargin = ((right - left) * 0.1f).toInt().coerceAtLeast(2)
                            val hMargin = ((right - left) * 0.05f).toInt().coerceAtLeast(1)
                            top = (top - vMargin).coerceAtLeast(0)
                            right = (right + hMargin).coerceAtMost(bitmap.width)
                        } else {
                            // Horizontal lines: right margin
                            val hMargin = ((bottom - top) * 0.1f).toInt().coerceAtLeast(4)
                            right = (right + hMargin).coerceAtMost(bitmap.width)
                        }

                        detectedBoxes.add(JpDictRect(left, top, right, bottom))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MeikiOcrEngine", "Detection post-processing failed", e)
        } finally {
            inputs.values.forEach { it.close() }
            results.close()
            resized.recycle()
        }

        // Merge redundant, highly overlapping boxes
        val mergedBoxes = mergeOverlappingBoxes(detectedBoxes)

        // Use custom sorting logic to handle mixed horizontal/vertical and RTL/LTR
        return sortDetectedBoxes(mergedBoxes)
    }

    private fun mergeOverlappingBoxes(boxes: List<JpDictRect>): List<JpDictRect> {
        if (boxes.size < 2) return boxes

        val result = mutableListOf<JpDictRect>()
        val handled = BooleanArray(boxes.size)

        // Sort by size to keep the larger/more robust box as the primary
        val sortedBoxes = boxes.withIndex().sortedByDescending { it.value.width() * it.value.height() }

        for (i in sortedBoxes.indices) {
            val idx = sortedBoxes[i].index
            if (handled[idx]) continue

            val boxA = sortedBoxes[i].value
            handled[idx] = true

            var currentMerged = boxA

            for (j in i + 1 until sortedBoxes.size) {
                val idxB = sortedBoxes[j].index
                if (handled[idxB]) continue

                val boxB = sortedBoxes[j].value

                if (shouldMergeBoxes(currentMerged, boxB)) {
                    // Merge by taking the bounding box of both
                    currentMerged = JpDictRect(
                        min(currentMerged.left, boxB.left),
                        min(currentMerged.top, boxB.top),
                        max(currentMerged.right, boxB.right),
                        max(currentMerged.bottom, boxB.bottom)
                    )
                    handled[idxB] = true
                }
            }
            result.add(currentMerged)
        }
        return result
    }

    private fun shouldMergeBoxes(a: JpDictRect, b: JpDictRect): Boolean {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        if (interLeft >= interRight || interTop >= interBottom) return false

        val interArea = (interRight - interLeft).toFloat() * (interBottom - interTop)
        val areaA = (a.right - a.left).toFloat() * (a.bottom - a.top)
        val areaB = (b.right - b.left).toFloat() * (b.bottom - b.top)

        // Use Intersection over Minimum (IoM) instead of IoU
        // This handles cases where one box is much shorter than the other
        val minArea = min(areaA, areaB)
        val iom = interArea / minArea

        if (iom < 0.8f) return false

        // Additionally, verify that they align in their "thickness" dimension
        val isAVertical = a.height() > a.width()
        val isBVertical = b.height() > b.width()

        if (isAVertical != isBVertical) return false

        return if (isAVertical) {
            // Vertical lines should have similar X and Width
            val xDiff = abs(a.centerX() - b.centerX())
            val wDiff = abs(a.width() - b.width())
            val avgWidth = (a.width() + b.width()) / 2f
            xDiff < avgWidth * 0.3f && wDiff < avgWidth * 0.4f
        } else {
            // Horizontal lines should have similar Y and Height
            val yDiff = abs(a.centerY() - b.centerY())
            val hDiff = abs(a.height() - b.height())
            val avgHeight = (a.height() + b.height()) / 2f
            yDiff < avgHeight * 0.3f && hDiff < avgHeight * 0.4f
        }
    }

    private fun sortDetectedBoxes(boxes: List<JpDictRect>): List<JpDictRect> {
        if (boxes.isEmpty()) return emptyList()

        // 1. Sort primarily by top coordinate
        val sortedByTop = boxes.sortedBy { it.top }

        val rowGroups = mutableListOf<MutableList<JpDictRect>>()
        if (sortedByTop.isNotEmpty()) {
            var currentGroup = mutableListOf<JpDictRect>()
            currentGroup.add(sortedByTop[0])
            rowGroups.add(currentGroup)

            for (i in 1 until sortedByTop.size) {
                val box = sortedByTop[i]
                val prevBox = sortedByTop[i-1]

                // Group lines that are vertically similar.
                // For vertical columns, they often start at similar 'top'.
                // For horizontal text, they are on the same 'line'.
                // We use a threshold based on the height of the line.
                val height = if (box.height() > box.width()) box.width() else box.height()
                val threshold = height * 0.8

                if (abs(box.top - prevBox.top) < threshold) {
                    currentGroup.add(box)
                } else {
                    currentGroup = mutableListOf<JpDictRect>()
                    currentGroup.add(box)
                    rowGroups.add(currentGroup)
                }
            }
        }

        val result = mutableListOf<JpDictRect>()
        for (group in rowGroups) {
            // Within each row group:
            // Separate horizontal and vertical lines
            val horizontal = group.filter { it.width() >= it.height() }.sortedBy { it.left } // LTR
            val vertical = group.filter { it.height() > it.width() }.sortedByDescending { it.right } // RTL

            // Usually, headers (horizontal) come before the main text (vertical columns)
            // or we just output them in a stable order.
            result.addAll(horizontal)
            result.addAll(vertical)
        }

        return result
    }

    suspend fun recognizeStreaming(bitmap: Bitmap, lineBoxes: List<JpDictRect>, onLinesRecognized: (List<Pair<Int, LineResult>>) -> Unit) = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val horizontalShort = mutableListOf<Pair<Int, JpDictRect>>()
        val verticalShort = mutableListOf<Pair<Int, JpDictRect>>()
        val longLines = mutableListOf<Pair<Int, JpDictRect>>()

        lineBoxes.forEachIndexed { index, box ->
            val isVertical = box.height() > box.width()
            val isLong = if (isVertical) {
                box.height() * (32f / box.width()) > 350
            } else {
                box.width() * (32f / box.height()) > 960
            }

            if (isLong) longLines.add(index to box)
            else if (isVertical) verticalShort.add(index to box)
            else horizontalShort.add(index to box)
        }

        val semaphore = Semaphore(3)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()

        horizontalShort.chunked(BATCH_SIZE).forEach { batch ->
            jobs.add(launch {
                semaphore.withPermit {
                    val results = recognizeShortLinesBatch(bitmap, batch, false)
                    withContext(Dispatchers.Main) { onLinesRecognized(results) }
                }
            })
        }

        verticalShort.chunked(BATCH_SIZE).forEach { batch ->
            jobs.add(launch {
                semaphore.withPermit {
                    val results = recognizeShortLinesBatch(bitmap, batch, true)
                    withContext(Dispatchers.Main) { onLinesRecognized(results) }
                }
            })
        }

        longLines.forEach { (index, box) ->
            jobs.add(launch {
                semaphore.withPermit {
                    val result = recognizeSingleLine(bitmap, box)
                    if (result != null) {
                        withContext(Dispatchers.Main) { onLinesRecognized(listOf(index to result)) }
                    }
                }
            })
        }

        jobs.joinAll()

        val totalTime = System.currentTimeMillis() - startTime
        Log.d("MeikiOcrEngine", "Streaming recognition for ${lineBoxes.size} lines took ${totalTime}ms")
    }

    private fun recognizeShortLinesBatch(bitmap: Bitmap, batch: List<Pair<Int, JpDictRect>>, isVertical: Boolean): List<Pair<Int, LineResult>> {
        val targetW = if (isVertical) VERT_REC_WIDTH else REC_WIDTH
        val targetH = if (isVertical) VERT_REC_HEIGHT else REC_HEIGHT
        
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val tempBitmaps = mutableListOf<Bitmap>()
        val effectiveSizes = mutableListOf<Pair<Int, Int>>()

        for ((_, box) in batch) {
            val effW: Int
            val effH: Int
            if (isVertical) {
                val scaleFactor = 32f / box.width().coerceAtLeast(1)
                effW = 32
                effH = min(VERT_REC_HEIGHT, (box.height() * scaleFactor).toInt())
            } else {
                val scaleFactor = 32f / box.height().coerceAtLeast(1)
                effH = 32
                effW = min(REC_WIDTH, (box.width() * scaleFactor).toInt())
            }
            effectiveSizes.add(effW to effH)

            val padded = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(padded)
            canvas.drawColor(Color.BLACK)
            
            val srcRect = android.graphics.Rect(
                max(0, box.left),
                max(0, box.top),
                min(bitmap.width, box.right),
                min(bitmap.height, box.bottom)
            )
            val dstRect = android.graphics.Rect(0, 0, effW, effH)
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            tempBitmaps.add(padded)
        }

        val chunkResults = recognizeChunksBatched(tempBitmaps, isVertical, effectiveSizes)

        val results = mutableListOf<Pair<Int, LineResult>>()
        for (i in batch.indices) {
            val index = batch[i].first
            val box = batch[i].second
            val (allCandidates, effW, effH) = chunkResults[i]

            val cropX = max(0, box.left)
            val cropY = max(0, box.top)
            val cropW = min(bitmap.width - cropX, box.width())
            val cropH = min(bitmap.height - cropY, box.height())

            val rawChunk = RawChunkResult(allCandidates, 0, 0, cropW, cropH, effW, effH)
            val result = processLineFromRawChunks(listOf(rawChunk), isVertical, cropX, cropY)

            results.add(index to result)
            tempBitmaps[i].recycle()
        }
        return results
    }

    private fun postProcessLineResult(result: LineResult) {
        for (pair in MEIKI_SWAPPED_PAIRS) {
            val i = result.text.indexOf(pair.key)
            if (i != -1) {
                result.text = result.text.replace(pair.key, pair.value)
                if (i < result.alternatives.size) {
                    result.alternatives[i][0] = Pair(pair.key[1], 1.0f)
                    result.alternatives[i][1] = Pair(pair.key[0], 1.0f)
                }
            }
        }
    }

    fun processLineFromRawChunks(rawChunks: List<RawChunkResult>, isVertical: Boolean, cropX: Int, cropY: Int, threshold: Float = 0.1f): LineResult {
        val chunkResults = rawChunks.map { rc ->
            val filtered = filterCandidates(rc.candidates, isVertical, threshold)
            ChunkResult(filtered, rc.offsetX, rc.offsetY, rc.chunkW, rc.chunkH, rc.effW, rc.effH)
        }

        val result = if (isVertical) {
            stitchVerticalChunks(chunkResults, cropX, cropY)
        } else {
            stitchHorizontalChunks(chunkResults, cropX, cropY)
        }
        
        val finalResult = result.copy(rawChunks = rawChunks, cropX = cropX, cropY = cropY)
        postProcessLineResult(finalResult)
        return finalResult
    }

    private fun filterCandidates(candidates: List<CharCandidate>, isVertical: Boolean, threshold: Float): List<CharCandidate> {
        val aboveThreshold = candidates.filter { it.score > threshold }.sortedByDescending { it.score }
        val filtered = mutableListOf<CharCandidate>()
        for (cand in aboveThreshold) {
            var keep = true
            for (f in filtered) {
                val overlap = if (isVertical) calculateYOverlap(cand.box, f.box) else calculateXOverlap(cand.box, f.box)
                if (overlap > X_OVERLAP_THRESHOLD) {
                    keep = false
                    break
                }
            }
            if (keep) filtered.add(cand)
        }
        return if (isVertical) filtered.sortedBy { it.box[1] } else filtered.sortedBy { it.box[0] }
    }

    private fun recognizeSingleLine(bitmap: Bitmap, box: JpDictRect): LineResult? {
        val lineStartTime = System.currentTimeMillis()
        if (recognizeSession == null || recognizeSessionVertical == null) return null

        val isVertical = box.height() > box.width()
        try {
            val cropX = max(0, box.left)
            val cropY = max(0, box.top)
            val cropW = min(bitmap.width - cropX, box.width())
            val cropH = min(bitmap.height - cropY, box.height())

            if (cropW <= 0 || cropH <= 0) return null

            val crop = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)

            val result: LineResult?
            if (isVertical && (crop.height * (32f / crop.width) > 350)) {
                result = recognizeVerticalLongLine(crop, cropX, cropY)
            } else if (!isVertical && (crop.width * (32f / crop.height) > 960)) {
                result = recognizeHorizontalLongLine(crop, cropX, cropY)
            } else {
                val (allCandidates, effW, effH) = recognizeSingleChunk(crop, isVertical)
                val rawChunk = RawChunkResult(allCandidates, 0, 0, crop.width, crop.height, effW, effH)
                result = processLineFromRawChunks(listOf(rawChunk), isVertical, cropX, cropY)
            }

            crop.recycle()
            val lineTime = System.currentTimeMillis() - lineStartTime
            Log.d("MeikiOcrEngine", "Line recognition took ${lineTime}ms")
            return result
        } catch (e: Exception) {
            Log.e("MeikiOcrEngine", "Recognition failed for a line", e)
            return null
        }
    }

    private fun recognizeSingleChunk(chunk: Bitmap, isVertical: Boolean): Triple<List<CharCandidate>, Int, Int> {
        val results = recognizeChunksBatched(listOf(chunk), isVertical)
        return results.firstOrNull() ?: Triple(emptyList(), 0, 0)
    }

    private fun recognizeChunksBatched(chunks: List<Bitmap>, isVertical: Boolean, providedEffectiveSizes: List<Pair<Int, Int>>? = null): List<Triple<List<CharCandidate>, Int, Int>> {
        if (chunks.isEmpty()) return emptyList()
        val session = (if (isVertical) recognizeSessionVertical else recognizeSession) ?: return chunks.map { Triple(emptyList(), 0, 0) }

        val targetW = if (isVertical) VERT_REC_WIDTH else REC_WIDTH
        val targetH = if (isVertical) VERT_REC_HEIGHT else REC_HEIGHT
        val batchSize = chunks.size

        val paddedBitmaps = mutableListOf<Bitmap>()
        val effectiveSizes = mutableListOf<Pair<Int, Int>>()

        if (providedEffectiveSizes != null) {
            paddedBitmaps.addAll(chunks)
            effectiveSizes.addAll(providedEffectiveSizes)
        } else {
            for (chunk in chunks) {
                val effW: Int
                val effH: Int
                if (isVertical) {
                    val scaleFactor = 32f / chunk.width.coerceAtLeast(1)
                    effW = 32
                    effH = min(VERT_REC_HEIGHT, (chunk.height * scaleFactor).toInt())
                } else {
                    val scaleFactor = 32f / chunk.height.coerceAtLeast(1)
                    effH = 32
                    effW = min(REC_WIDTH, (chunk.width * scaleFactor).toInt())
                }
                effectiveSizes.add(effW to effH)

                val resizedLine = Bitmap.createScaledBitmap(chunk, effW, effH, true)
                val paddedLine = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(paddedLine)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(resizedLine, 0f, 0f, null)
                paddedBitmaps.add(paddedLine)
                resizedLine.recycle()
            }
        }

        val imgData = bitmapsToFloatBuffer(paddedBitmaps, targetW, targetH)
        val inputTensor = OnnxTensor.createTensor(env, imgData, longArrayOf(batchSize.toLong(), 3, targetH.toLong(), targetW.toLong()))

        val inputs = mutableMapOf<String, OnnxTensor>()
        val inputNames = session.inputNames.toList()
        val imageInputName = inputNames.find { it.contains("image") || it.contains("input") } ?: inputNames.iterator().next()
        inputs[imageInputName] = inputTensor

        val sizeDataArr = LongArray(batchSize * 2)
        for (i in 0 until batchSize) {
            sizeDataArr[i * 2] = targetW.toLong()
            sizeDataArr[i * 2 + 1] = targetH.toLong()
        }
        val sizeData = LongBuffer.wrap(sizeDataArr)
        inputs["orig_target_sizes"] = OnnxTensor.createTensor(env, sizeData, longArrayOf(batchSize.toLong(), 2))

        val output = session.run(inputs)
        inputs.values.forEach { it.close() }
        if (providedEffectiveSizes == null) {
            paddedBitmaps.forEach { it.recycle() }
        }

        val outputNames = session.outputNames.toList()
        val labelsName = outputNames.find { it.contains("labels") || it.contains("char_codes") } ?: outputNames[0]
        val boxesName = outputNames.find { it.contains("boxes") } ?: outputNames.getOrNull(1) ?: outputNames[0]
        val scoresName = outputNames.find { it.contains("scores") } ?: outputNames.getOrNull(2) ?: outputNames[0]
        val logitsName = outputNames.find { it.contains("logits") }
        val indicesName = outputNames.find { it.contains("indices") }

        val labelsVal = output.get(labelsName).get().value
        val boxesVal = output.get(boxesName).get().value
        val scoresVal = output.get(scoresName).get().value
        val logitsVal = logitsName?.let { output.get(it).get().value }
        val indicesVal = indicesName?.let { output.get(it).get().value }

        val finalResults = mutableListOf<Triple<List<CharCandidate>, Int, Int>>()

        for (b in 0 until batchSize) {
            val labelsArr = extractLongArrayAt(labelsVal, b)
            val boxesArr = extractFloatArray2DAt(boxesVal, b)
            val scoresArr = extractFloatArrayAt(scoresVal, b)
            val indicesArr = extractLongArrayAt(indicesVal, b)
            val rawLogits = extractFloatArrayAt(logitsVal, b)

            val effW = effectiveSizes[b].first
            val effH = effectiveSizes[b].second

            if (labelsArr == null || boxesArr == null || scoresArr == null) {
                finalResults.add(Triple(emptyList(), effW, effH))
                continue
            }

            val numQueries = 48
            val logitsMatrix = if (rawLogits != null && rawLogits.size % numQueries == 0) {
                val numClasses = rawLogits.size / numQueries
                Array(numQueries) { q ->
                    FloatArray(numClasses) { c -> rawLogits[q * numClasses + c] }
                }
            } else null

            val candidates = mutableListOf<CharCandidate>()
            for (i in scoresArr.indices) {
                if (scoresArr[i] > 0.01f) { // Low floor for dynamic thresholding
                    val alternatives = if (logitsMatrix != null && indicesArr != null && i < indicesArr.size) {
                        val numClasses = rawLogits?.let { it.size / numQueries } ?: 0
                        if (numClasses > 0) {
                            val queryIdx = (indicesArr[i] / numClasses).toInt()
                            if (queryIdx < logitsMatrix.size) {
                                val qLogits = logitsMatrix[queryIdx]
                                qLogits.withIndex()
                                    .sortedByDescending { it.value }
                                    .take(15)
                                    .map {
                                        val char = charVocab?.getOrNull(it.index)?.toChar() ?: ' '
                                        char to it.value
                                    }.toMutableList()
                            } else emptyList<Pair<Char, Float>>().toMutableList()
                        } else emptyList<Pair<Char, Float>>().toMutableList()
                    } else emptyList<Pair<Char, Float>>().toMutableList()

                    candidates.add(
                        CharCandidate(
                            char = labelsArr[i].toInt().toChar(),
                            score = scoresArr[i],
                            box = boxesArr[i],
                            alternatives = alternatives
                        )
                    )
                }
            }
            finalResults.add(Triple(candidates, effW, effH))
        }

        output.close()
        return finalResults
    }

    private fun recognizeVerticalLongLine(crop: Bitmap, cropX: Int, cropY: Int): LineResult {
        val scaleFactor = 32f / crop.width
        val maxChunkHeight = (350 / scaleFactor).toInt()

        val rawChunks = mutableListOf<RawChunkResult>()
        var currentY = 0
        while (currentY < crop.height) {
            val remainingH = crop.height - currentY
            if (remainingH < 10) break

            val h = min(maxChunkHeight, remainingH)
            val chunkBitmap = Bitmap.createBitmap(crop, 0, currentY, crop.width, h)
            val (candidates, effW, effH) = recognizeSingleChunk(chunkBitmap, true)

            if (effH <= 0) {
                chunkBitmap.recycle()
                currentY += (h * 0.8f).toInt()
                continue
            }

            rawChunks.add(RawChunkResult(candidates, 0, currentY, crop.width, h, effW, effH))
            chunkBitmap.recycle()

            if (currentY + h >= crop.height) break

            if (candidates.isNotEmpty()) {
                val filteredCandidates = filterCandidates(candidates, true, 0.1f)
                if (filteredCandidates.isNotEmpty()) {
                    val anchorCandidate = if (filteredCandidates.size >= 2) filteredCandidates[filteredCandidates.size - 2] else filteredCandidates.last()
                    val localYTop = (anchorCandidate.box[1] / effH.toFloat()) * h
                    val chunkMargin = (crop.width * 0.1f).toInt().coerceAtLeast(2)
                    val nextY = (currentY + localYTop.toInt() - chunkMargin).coerceAtLeast(0)
                    if (nextY <= currentY || nextY >= currentY + h - 10) {
                        currentY += (h * 0.8f).toInt()
                    } else {
                        currentY = nextY
                    }
                } else {
                    currentY += (h * 0.8f).toInt()
                }
            } else {
                currentY += (h * 0.8f).toInt()
            }
        }

        return processLineFromRawChunks(rawChunks, true, cropX, cropY)
    }

    private fun recognizeHorizontalLongLine(crop: Bitmap, cropX: Int, cropY: Int): LineResult {
        val scaleFactor = 32f / crop.height
        val maxChunkWidth = (960 / scaleFactor).toInt()

        val rawChunks = mutableListOf<RawChunkResult>()
        var currentX = 0
        while (currentX < crop.width) {
            val remainingW = crop.width - currentX
            if (remainingW < 10) break

            val w = min(maxChunkWidth, remainingW)
            val chunkBitmap = Bitmap.createBitmap(crop, currentX, 0, w, crop.height)
            val (candidates, effW, effH) = recognizeSingleChunk(chunkBitmap, false)

            if (effW <= 0) {
                chunkBitmap.recycle()
                currentX += (w * 0.8f).toInt()
                continue
            }

            rawChunks.add(RawChunkResult(candidates, currentX, 0, w, crop.height, effW, effH))
            chunkBitmap.recycle()

            if (currentX + w >= crop.width) break

            if (candidates.isNotEmpty()) {
                val filteredCandidates = filterCandidates(candidates, false, 0.1f)
                if (filteredCandidates.isNotEmpty()) {
                    val anchorCandidate = if (filteredCandidates.size >= 2) filteredCandidates[filteredCandidates.size - 2] else filteredCandidates.last()
                    val localXLeft = (anchorCandidate.box[0] / effW.toFloat()) * w
                    val chunkMargin = (crop.height * 0.1f).toInt().coerceAtLeast(2)
                    val nextX = (currentX + localXLeft.toInt() - chunkMargin).coerceAtLeast(0)
                    if (nextX <= currentX || nextX >= currentX + w - 10) {
                        currentX += (w * 0.8f).toInt()
                    } else {
                        currentX = nextX
                    }
                } else {
                    currentX += (w * 0.8f).toInt()
                }
            } else {
                currentX += (w * 0.8f).toInt()
            }
        }

        return processLineFromRawChunks(rawChunks, false, cropX, cropY)
    }

    private data class ChunkResult(
        val candidates: List<CharCandidate>,
        val offsetX: Int,
        val offsetY: Int,
        val chunkW: Int,
        val chunkH: Int,
        val effW: Int,
        val effH: Int
    )

    private fun stitchVerticalChunks(chunkResults: List<ChunkResult>, cropX: Int, cropY: Int): LineResult {
        if (chunkResults.isEmpty()) return LineResult("", emptyList(), emptyList(), true)

        data class GlobalCandidate(val cand: CharCandidate, val globalRect: JpDictRect)

        val allGlobalChunks = chunkResults.map { cr ->
            cr.candidates.map { cand ->
                GlobalCandidate(cand, cand.getGlobalRect(true, cr.chunkW, cr.chunkH, cropX + cr.offsetX, cropY + cr.offsetY, cr.effW, cr.effH))
            }
        }

        val chunkBoxes = chunkResults.map { cr ->
            JpDictRect(cropX + cr.offsetX, cropY + cr.offsetY, cropX + cr.offsetX + cr.chunkW, cropY + cr.offsetY + cr.chunkH)
        }

        val result = mutableListOf<GlobalCandidate>()
        result.addAll(allGlobalChunks[0])

        for (i in 1 until allGlobalChunks.size) {
            val prevChunk = result
            val currentChunk = allGlobalChunks[i]
            if (currentChunk.isEmpty()) continue

            var bestPrevIdx = -1
            var bestCurrIdx = -1
            var bestMatchScore = -1f

            val pStart = max(0, prevChunk.size - 10)
            val cEnd = min(currentChunk.size, 10)

            for (pIdx in prevChunk.size - 1 downTo pStart) {
                val pGC = prevChunk[pIdx]
                for (cIdx in 0 until cEnd) {
                    val cGC = currentChunk[cIdx]

                    val dist = abs(pGC.globalRect.centerY() - cGC.globalRect.centerY())
                    if (dist > 30) continue

                    val predictionScore = comparePredictionVectors(pGC.cand.alternatives, cGC.cand.alternatives)
                    if (predictionScore < 0.4f) continue

                    val score = (1f - dist/30f) * 0.3f + predictionScore * 0.7f
                    if (score > bestMatchScore) {
                        bestMatchScore = score
                        bestPrevIdx = pIdx
                        bestCurrIdx = cIdx
                    }
                }
            }

            if (bestPrevIdx != -1) {
                // Interleave alternatives for the anchor character to improve replacement list quality
                val prevAnchor = result[bestPrevIdx]
                val currAnchor = currentChunk[bestCurrIdx]
                val mergedAlts = interleaveAlternatives(prevAnchor.cand.alternatives, currAnchor.cand.alternatives).toMutableList()

                val toKeep = bestPrevIdx + 1
                while (result.size > toKeep) result.removeAt(result.size - 1)

                // Update the anchor in result with merged alternatives
                result[bestPrevIdx] = prevAnchor.copy(cand = prevAnchor.cand.copy(alternatives = mergedAlts))

                for (j in bestCurrIdx + 1 until currentChunk.size) {
                    result.add(currentChunk[j])
                }
            } else {
                val lastY = result.lastOrNull()?.globalRect?.centerY() ?: -1
                for (cGC in currentChunk) {
                    if (cGC.globalRect.centerY() > lastY + 10) {
                        result.add(cGC)
                    }
                }
            }
        }

        return LineResult(
            result.joinToString("") { it.cand.char.toString() },
            result.map { it.globalRect },
            result.map { it.cand.alternatives.toMutableList() },
            true,
            chunkBoxes
        )
    }

    private fun stitchHorizontalChunks(chunkResults: List<ChunkResult>, cropX: Int, cropY: Int): LineResult {
        if (chunkResults.isEmpty()) return LineResult("", emptyList(), emptyList(), false)

        data class GlobalCandidate(val cand: CharCandidate, val globalRect: JpDictRect)

        val allGlobalChunks = chunkResults.map { cr ->
            cr.candidates.map { cand ->
                GlobalCandidate(cand, cand.getGlobalRect(false, cr.chunkW, cr.chunkH, cropX + cr.offsetX, cropY + cr.offsetY, cr.effW, cr.effH))
            }
        }

        val chunkBoxes = chunkResults.map { cr ->
            JpDictRect(cropX + cr.offsetX, cropY + cr.offsetY, cropX + cr.offsetX + cr.chunkW, cropY + cr.offsetY + cr.chunkH)
        }

        val result = mutableListOf<GlobalCandidate>()
        result.addAll(allGlobalChunks[0])

        for (i in 1 until allGlobalChunks.size) {
            val prevChunk = result
            val currentChunk = allGlobalChunks[i]
            if (currentChunk.isEmpty()) continue

            var bestPrevIdx = -1
            var bestCurrIdx = -1
            var bestMatchScore = -1f

            val pStart = max(0, prevChunk.size - 10)
            val cEnd = min(currentChunk.size, 10)

            for (pIdx in prevChunk.size - 1 downTo pStart) {
                val pGC = prevChunk[pIdx]
                for (cIdx in 0 until cEnd) {
                    val cGC = currentChunk[cIdx]

                    val dist = abs(pGC.globalRect.centerX() - cGC.globalRect.centerX())
                    if (dist > 30) continue

                    val predictionScore = comparePredictionVectors(pGC.cand.alternatives, cGC.cand.alternatives)
                    if (predictionScore < 0.4f) continue

                    val score = (1f - dist/30f) * 0.3f + predictionScore * 0.7f
                    if (score > bestMatchScore) {
                        bestMatchScore = score
                        bestPrevIdx = pIdx
                        bestCurrIdx = cIdx
                    }
                }
            }

            if (bestPrevIdx != -1) {
                val prevAnchor = result[bestPrevIdx]
                val currAnchor = currentChunk[bestCurrIdx]
                val mergedAlts = interleaveAlternatives(prevAnchor.cand.alternatives, currAnchor.cand.alternatives).toMutableList()

                val toKeep = bestPrevIdx + 1
                while (result.size > toKeep) result.removeAt(result.size - 1)

                result[bestPrevIdx] = prevAnchor.copy(cand = prevAnchor.cand.copy(alternatives = mergedAlts))

                for (j in bestCurrIdx + 1 until currentChunk.size) {
                    result.add(currentChunk[j])
                }
            } else {
                val lastX = result.lastOrNull()?.globalRect?.centerX() ?: -1
                for (cGC in currentChunk) {
                    if (cGC.globalRect.centerX() > lastX + 10) {
                        result.add(cGC)
                    }
                }
            }
        }

        return LineResult(
            result.joinToString("") { it.cand.char.toString() },
            result.map { it.globalRect },
            result.map { it.cand.alternatives.toMutableList() },
            false,
            chunkBoxes
        )
    }

    private fun comparePredictionVectors(alt1: List<Pair<Char, Float>>, alt2: List<Pair<Char, Float>>): Float {
        if (alt1.isEmpty() || alt2.isEmpty()) return 0f
        if (alt1[0].first == alt2[0].first) {
            val set2 = alt2.take(5).map { it.first }.toSet()
            var matches = 0
            alt1.take(5).forEach { if (set2.contains(it.first)) matches++ }
            return 0.6f + (matches / 5f) * 0.4f
        }
        val char1 = alt1[0].first
        val char2 = alt2[0].first
        if (alt2.take(3).any { it.first == char1 } || alt1.take(3).any { it.first == char2 }) return 0.5f
        return 0f
    }

    private fun interleaveAlternatives(alt1: List<Pair<Char, Float>>, alt2: List<Pair<Char, Float>>): List<Pair<Char, Float>> {
        val merged = mutableMapOf<Char, Float>()
        // We use a weighted sum approach.
        // Characters appearing in both chunks get their scores combined.
        alt1.forEach { (char, score) -> merged[char] = score }
        alt2.forEach { (char, score) ->
            val existing = merged[char] ?: 0f
            if (existing > 0f) {
                // Character found in both chunks - strong signal
                merged[char] = (existing + score) * 0.8f
            } else {
                merged[char] = score * 0.6f // Slightly penalize characters only seen once compared to joint matches
            }
        }
        return merged.toList().sortedByDescending { it.second }.take(15)
    }


    private fun bitmapToFloatBuffer(bitmap: Bitmap, width: Int, height: Int): FloatBuffer {
        return bitmapsToFloatBuffer(listOf(bitmap), width, height)
    }

    private fun bitmapsToFloatBuffer(bitmaps: List<Bitmap>, width: Int, height: Int): FloatBuffer {
        val numPixels = width * height
        val capacity = bitmaps.size * 3 * numPixels
        val floatBuffer = java.nio.ByteBuffer.allocateDirect(capacity * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()

        val pixels = IntArray(numPixels)
        val inv255 = 1.0f / 255.0f

        for (b in bitmaps.indices) {
            bitmaps[b].getPixels(pixels, 0, width, 0, 0, width, height)
            val rBase = b * 3 * numPixels
            val gBase = rBase + numPixels
            val bBase = gBase + numPixels
            
            for (i in 0 until numPixels) {
                val p = pixels[i]
                floatBuffer.put(rBase + i, ((p shr 16) and 0xFF) * inv255)
                floatBuffer.put(gBase + i, ((p shr 8) and 0xFF) * inv255)
                floatBuffer.put(bBase + i, (p and 0xFF) * inv255)
            }
        }
        floatBuffer.rewind()
        return floatBuffer
    }

    private fun calculateXOverlap(box1: FloatArray, box2: FloatArray): Float {
        val x1_min = box1[0]
        val x1_max = box1[2]
        val x2_min = box2[0]
        val x2_max = box2[2]
        val intersection = max(0f, min(x1_max, x2_max) - max(x1_min, x2_min))
        val w1 = x1_max - x1_min
        val w2 = x2_max - x2_min
        if (w1 <= 0 || w2 <= 0) return 0f
        return intersection / min(w1, w2)
    }
    private fun calculateYOverlap(box1: FloatArray, box2: FloatArray): Float {
        val y1_min = box1[1]
        val y1_max = box1[3]
        val y2_min = box2[1]
        val y2_max = box2[3]
        val intersection = max(0f, min(y1_max, y2_max) - max(y1_min, y2_min))
        val h1 = y1_max - y1_min
        val h2 = y2_max - y2_min
        if (h1 <= 0 || h2 <= 0) return 0f
        return intersection / min(h1, h2)
    }

    private fun extractLongArrayAt(value: Any?, index: Int): LongArray? {
        if (value is Array<*>) {
            return if (index < value.size) extractLongArray(value[index]) else null
        }
        return if (index == 0) extractLongArray(value) else null
    }

    private fun extractFloatArrayAt(value: Any?, index: Int): FloatArray? {
        if (value is Array<*>) {
            return if (index < value.size) extractFloatArray(value[index]) else null
        }
        return if (index == 0) extractFloatArray(value) else null
    }

    private fun extractFloatArray2DAt(value: Any?, index: Int): Array<FloatArray>? {
        if (value is Array<*>) {
            return if (index < value.size) extractFloatArray2D(value[index]) else null
        }
        return if (index == 0) extractFloatArray2D(value) else null
    }

    private fun extractLongArray(value: Any?): LongArray? {
        return when (value) {
            is LongArray -> value
            is IntArray -> value.map { it.toLong() }.toLongArray()
            is FloatArray -> value.map { it.toLong() }.toLongArray()
            is DoubleArray -> value.map { it.toLong() }.toLongArray()
            is Array<*> -> if (value.isNotEmpty()) extractLongArray(value[0]) else null
            else -> null
        }
    }

    private fun extractFloatArray(value: Any?): FloatArray? {
        return when (value) {
            is FloatArray -> value
            is IntArray -> value.map { it.toFloat() }.toFloatArray()
            is LongArray -> value.map { it.toFloat() }.toFloatArray()
            is DoubleArray -> value.map { it.toFloat() }.toFloatArray()
            is Array<*> -> if (value.isNotEmpty()) extractFloatArray(value[0]) else null
            else -> null
        }
    }

    private fun extractFloatArray2D(value: Any?): Array<FloatArray>? {
        if (value !is Array<*>) return null
        if (value.isEmpty()) return null
        val first = value[0] ?: return null

        return when (first) {
            is FloatArray -> {
                @Suppress("UNCHECKED_CAST")
                value as Array<FloatArray>
            }
            is IntArray -> {
                Array(value.size) { i -> (value[i] as IntArray).map { it.toFloat() }.toFloatArray() }
            }
            is LongArray -> {
                Array(value.size) { i -> (value[i] as LongArray).map { it.toFloat() }.toFloatArray() }
            }
            is DoubleArray -> {
                Array(value.size) { i -> (value[i] as DoubleArray).map { it.toFloat() }.toFloatArray() }
            }
            is Array<*> -> {
                extractFloatArray2D(first)
            }
            else -> null
        }
    }

    fun close() {
        detectSession?.close()
        recognizeSession?.close()
        recognizeSessionVertical?.close()
        env.close()
    }
}
