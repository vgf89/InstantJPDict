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
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min

class MeikiOcrEngine(private val context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var detectSession: OrtSession? = null
    private var recognizeSession: OrtSession? = null
    private var recognizeSessionVertical: OrtSession? = null

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
            recognizeSession = env.createSession(loadModel("meiki.text.rec.v0.960x32.onnx"))
            recognizeSessionVertical = env.createSession(loadModel("meiki.text.rec.v0.vertical.32x480.onnx"))
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
                val boxesData = boxesResult.get().value as Array<*> 
                val scoresData = scoresResult.get().value as Array<*> 
                val boxesArr = boxesData[0] as Array<*>
                val scoresArr = scoresData[0] as FloatArray

                for (i in scoresArr.indices) {
                    if (scoresArr[i] > 0.4f) {
                        val box = boxesArr[i] as FloatArray
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

    fun recognize(bitmap: Bitmap, lineBoxes: List<Rect>): List<LineResult> {
        val session = recognizeSession ?: return emptyList()
        val sessionVertical = recognizeSessionVertical ?: return emptyList()
        val results = mutableListOf<LineResult>()

        for (box in lineBoxes) {
            val isVertical = box.height() > box.width()
            try {
                val cropX = max(0, box.left)
                val cropY = max(0, box.top)
                val cropW = min(bitmap.width - cropX, box.width())
                val cropH = min(bitmap.height - cropY, box.height())

                if (cropW <= 0 || cropH <= 0) continue

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
                val imageInputName = activeSession.inputNames.find { it.contains("image") || it.contains("input") } ?: activeSession.inputNames.iterator().next()
                inputs[imageInputName] = inputTensor

                val sizeData = LongBuffer.wrap(longArrayOf(targetW.toLong(), targetH.toLong()))
                inputs["orig_target_sizes"] = OnnxTensor.createTensor(env, sizeData, longArrayOf(1, 2))

                val output = activeSession.run(inputs)

                val outputNames = activeSession.outputNames.toList()
                val labelsResult = output.get(outputNames.find { it.contains("labels") } ?: outputNames[0])
                val boxesResult = output.get(outputNames.find { it.contains("boxes") } ?: outputNames[1])
                val scoresResult = output.get(outputNames.find { it.contains("scores") } ?: outputNames[2])

                val labelsVal = labelsResult.get().value
                val labelsArr: LongArray = when (labelsVal) {
                    is Array<*> -> {
                        val first = labelsVal[0]
                        if (first is LongArray) first else (first as IntArray).map { it.toLong() }.toLongArray()
                    }
                    is LongArray -> labelsVal
                    is IntArray -> labelsVal.map { it.toLong() }.toLongArray()
                    else -> throw Exception("Unknown labels type")
                }

                val boxesArr = (boxesResult.get().value as Array<*>)[0] as Array<FloatArray>
                val scoresArr = (scoresResult.get().value as Array<*>)[0] as FloatArray

                val candidates = mutableListOf<CharCandidate>()
                for (i in scoresArr.indices) {
                    if (scoresArr[i] > REC_CONFIDENCE_THRESHOLD) {
                        candidates.add(
                            CharCandidate(
                                char = labelsArr[i].toInt().toChar(),
                                score = scoresArr[i],
                                box = boxesArr[i] // [rx1, ry1, rx2, ry2]
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

                results.add(LineResult(text, charBoxes))
                Log.d("MeikiOcrEngine", "Recognized: $text")

                inputs.values.forEach { it.close() }
                output.close()
                crop.recycle()
                resizedLine.recycle()
                paddedLine.recycle()
            } catch (e: Exception) {
                Log.e("MeikiOcrEngine", "Recognition failed for a line", e)
            }
        }
        return results
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

    fun close() {
        detectSession?.close()
        recognizeSession?.close()
        recognizeSessionVertical?.close()
        env.close()
    }
}

data class LineResult(
    val text: String,
    val charBoxes: List<Rect>
)

private data class CharCandidate(
    val char: Char,
    val score: Float,
    val box: FloatArray
)
