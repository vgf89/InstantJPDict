package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class MeikiOcrEngine(private val context: Context) {
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
        private const val REC_CONFIDENCE_THRESHOLD = 0.1f
        private const val X_OVERLAP_THRESHOLD = 0.3f
    }

    init {
        try {
            detectSession = env.createSession(loadModel("meiki.text.detect.v0.1.960x544.onnx"))
            recognizeSession = env.createSession(loadModel("meiki.text.rec.v0.960x32.with_logits.onnx"))
            recognizeSessionVertical = env.createSession(loadModel("meiki.text.rec.v0.vertical.32x480.with_logits.onnx"))
            
            // Load character vocabulary mapping
            try {
                val vocabJson = context.assets.open("char_vocab.json").bufferedReader().use { it.readText() }
                charVocab = Gson().fromJson(vocabJson, IntArray::class.java)
                Log.d("MeikiOcrEngine", "Loaded vocabulary of size: ${charVocab?.size}")
            } catch (ve: Exception) {
                Log.e("MeikiOcrEngine", "Failed to load char_vocab.json", ve)
            }
            
            Log.d("MeikiOcrEngine", "Models loaded successfully")
        } catch (e: Exception) {
            Log.e("MeikiOcrEngine", "Failed to load models", e)
        }
    }

    private fun loadModel(fileName: String): ByteArray {
        return context.assets.open(fileName).readBytes()
    }

    fun isReady(): Boolean = detectSession != null && recognizeSession != null && recognizeSessionVertical != null

    fun detect(bitmap: Bitmap): List<Rect> {
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
        val detectedBoxes = mutableListOf<Rect>()
        
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
                        detectedBoxes.add(Rect(box[0].toInt(), box[1].toInt(), box[2].toInt(), box[3].toInt()))
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
        // Sort detected lines by their Y-coordinate (top-to-bottom)
        // If they were "backwards", the model likely returned them bottom-to-top.
        return detectedBoxes.sortedBy { it.top }
    }

    suspend fun recognize(bitmap: Bitmap, lineBoxes: List<Rect>): List<LineResult> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val results = lineBoxes.map { box ->
            async {
                recognizeSingleLine(bitmap, box)
            }
        }.awaitAll().filterNotNull()
        val totalTime = System.currentTimeMillis() - startTime
        Log.d("MeikiOcrEngine", "Full recognition for ${lineBoxes.size} lines took ${totalTime}ms")
        results
    }

    private fun recognizeSingleLine(bitmap: Bitmap, box: Rect): LineResult? {
        val lineStartTime = System.currentTimeMillis()
        val session = recognizeSession ?: return null
        val sessionVertical = recognizeSessionVertical ?: return null

        val isVertical = box.height() > box.width()
        try {
            val cropX = max(0, box.left)
            val cropY = max(0, box.top)
            val cropW = min(bitmap.width - cropX, box.width())
            val cropH = min(bitmap.height - cropY, box.height())

            if (cropW <= 0 || cropH <= 0) return null

            val crop = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)

            val targetW = if (isVertical) VERT_REC_WIDTH else REC_WIDTH
            val targetH = if (isVertical) VERT_REC_HEIGHT else REC_HEIGHT

            val effectiveW: Int
            val effectiveH: Int
            if (isVertical) {
                val scaleFactor = 32f / crop.width
                effectiveW = 32
                effectiveH = min(VERT_REC_HEIGHT, (crop.height * scaleFactor).toInt())
            } else {
                val scaleFactor = 32f / crop.height
                effectiveH = 32
                effectiveW = min(REC_WIDTH, (crop.width * scaleFactor).toInt())
            }

            val resizedLine = Bitmap.createScaledBitmap(crop, effectiveW, effectiveH, true)
            val paddedLine = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(paddedLine)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(resizedLine, 0f, 0f, null)

            val imgData = bitmapToFloatBuffer(paddedLine, targetW, targetH)
            val inputTensor = OnnxTensor.createTensor(env, imgData, longArrayOf(1, 3, targetH.toLong(), targetW.toLong()))
            
            val inputs = mutableMapOf<String, OnnxTensor>()
            val activeSession = if (isVertical) sessionVertical else session
            val inputNames = activeSession.inputNames.toList()
            val imageInputName = inputNames.find { it.contains("image") || it.contains("input") } ?: inputNames.iterator().next()
            inputs[imageInputName] = inputTensor

            val sizeData = LongBuffer.wrap(longArrayOf(targetW.toLong(), targetH.toLong()))
            inputs["orig_target_sizes"] = OnnxTensor.createTensor(env, sizeData, longArrayOf(1, 2))

            val output = activeSession.run(inputs)
            
            // Clean up inputs immediately after run
            inputs.values.forEach { it.close() }

            val outputNames = activeSession.outputNames.toList()
            val outputTensors = mutableMapOf<String, OnnxTensor>()
            for (name in outputNames) {
                val res = output.get(name)
                if (res.isPresent) {
                    outputTensors[name] = res.get() as OnnxTensor
                }
            }

            val labelsName = outputNames.find { it.contains("labels") || it.contains("char_codes") }
            val boxesName = outputNames.find { it.contains("boxes") }
            val scoresName = outputNames.find { it.contains("scores") }
            val logitsName = outputNames.find { it.contains("logits") }
            val indicesName = outputNames.find { it.contains("indices") }

            val labelsVal = labelsName?.let { outputTensors[it]?.value } ?: outputTensors[outputNames[0]]?.value
            val boxesVal = boxesName?.let { outputTensors[it]?.value } ?: outputTensors[outputNames.getOrNull(1)]?.value
            val scoresVal = scoresName?.let { outputTensors[it]?.value } ?: outputTensors[outputNames.getOrNull(2)]?.value
            val logitsVal = logitsName?.let { outputTensors[it]?.value }
            val indicesVal = indicesName?.let { outputTensors[it]?.value }

            val labelsArr = extractLongArray(labelsVal)
            val boxesArr = extractFloatArray2D(boxesVal)
            val scoresArr = extractFloatArray(scoresVal)
            val indicesArr = extractLongArray(indicesVal)
            
            if (labelsArr == null || boxesArr == null || scoresArr == null) {
                Log.w("MeikiOcrEngine", "Skipping line: failed to extract mandatory outputs")
                return null
            }
            
            // Extract and Reshape Logits (Full Sigmoid Output)
            val rawLogits = extractFloatArray(logitsVal)
            val numQueries = 48
            val logitsMatrix = if (rawLogits != null && rawLogits.size % numQueries == 0) {
                val numClasses = rawLogits.size / numQueries
                Array(numQueries) { q ->
                    FloatArray(numClasses) { c -> rawLogits[q * numClasses + c] }
                }
            } else null

            val candidates = mutableListOf<CharCandidate>()
            for (i in scoresArr.indices) {
                if (scoresArr[i] > REC_CONFIDENCE_THRESHOLD) {
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
                                    }
                            } else emptyList()
                        } else emptyList()
                    } else emptyList()

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

            candidates.sortByDescending { it.score }
            val filtered = mutableListOf<CharCandidate>()
            for (cand in candidates) {
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

            if (isVertical) {
                filtered.sortBy { it.box[1] }
            } else {
                filtered.sortBy { it.box[0] }
            }
            
            val text = filtered.joinToString("") { it.char.toString() }
            val alternativesList = filtered.map { it.alternatives }
            
            val charBoxes = filtered.map { cand ->
                val rx1 = cand.box[0]
                val ry1 = cand.box[1]
                val rx2 = cand.box[2]
                val ry2 = cand.box[3]

                val x1: Float
                val y1: Float
                val x2: Float
                val y2: Float
                
                if (isVertical) {
                    x1 = (rx1 / 32f) * crop.width + cropX
                    y1 = (ry1 / effectiveH) * crop.height + cropY
                    x2 = (rx2 / 32f) * crop.width + cropX
                    y2 = (ry2 / effectiveH) * crop.height + cropY
                } else {
                    x1 = (rx1 / effectiveW) * crop.width + cropX
                    y1 = (ry1 / 32f) * crop.height + cropY
                    x2 = (rx2 / effectiveW) * crop.width + cropX
                    y2 = (ry2 / 32f) * crop.height + cropY
                }
                
                Rect(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2))
            }

            val result = LineResult(text, charBoxes, alternativesList, isVertical)
            
            output.close()
            crop.recycle()
            resizedLine.recycle()
            paddedLine.recycle()
            
            val lineTime = System.currentTimeMillis() - lineStartTime
            Log.d("MeikiOcrEngine", "Line recognition took ${lineTime}ms")
            
            return result
        } catch (e: Exception) {
            Log.e("MeikiOcrEngine", "Recognition failed for a line", e)
            return null
        } finally {
            // No-op - outputs are closed manually above
        }
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap, width: Int, height: Int): FloatBuffer {
        val imgData = FloatBuffer.allocate(1 * 3 * height * width)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (c in 0 until 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    val value = when (c) {
                        0 -> Color.red(pixel)
                        1 -> Color.green(pixel)
                        else -> Color.blue(pixel)
                    } / 255f
                    imgData.put(value)
                }
            }
        }
        imgData.rewind()
        return imgData
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

data class LineResult(
    var text: String,
    val charBoxes: List<Rect>,
    val alternatives: List<List<Pair<Char, Float>>> = emptyList(),
    val isVertical: Boolean = false
)

private data class CharCandidate(
    val char: Char,
    val score: Float,
    val box: FloatArray,
    val alternatives: List<Pair<Char, Float>> = emptyList()
)
