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
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min

class MeikiOcrEngine(private val context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var detectSession: OrtSession? = null
    private var recognizeSession: OrtSession? = null

    companion object {
        private const val DETECT_WIDTH = 960
        private const val DETECT_HEIGHT = 544
        private const val REC_WIDTH = 960
        private const val REC_HEIGHT = 32
        private const val REC_CONFIDENCE_THRESHOLD = 0.1f
        private const val X_OVERLAP_THRESHOLD = 0.3f
    }

    init {
        try {
            detectSession = env.createSession(loadModel("meiki.text.detect.v0.1.960x544.onnx"))
            recognizeSession = env.createSession(loadModel("meiki.text.rec.v0.960x32.onnx"))
            Log.d("MeikiOcrEngine", "Models loaded successfully")
        } catch (e: Exception) {
            Log.e("MeikiOcrEngine", "Failed to load models", e)
        }
    }

    private fun loadModel(fileName: String): ByteArray {
        return context.assets.open(fileName).readBytes()
    }

    fun isReady(): Boolean = detectSession != null && recognizeSession != null

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
        val results = mutableListOf<LineResult>()

        for (box in lineBoxes) {
            try {
                val cropX = max(0, box.left)
                val cropY = max(0, box.top)
                val cropW = min(bitmap.width - cropX, box.width())
                val cropH = min(bitmap.height - cropY, box.height())
                if (cropW <= 0 || cropH <= 0) continue

                val crop = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
                
                val scaleFactor = 32f / crop.height
                val targetW = min(960, (crop.width * scaleFactor).toInt())
                val resizedLine = Bitmap.createScaledBitmap(crop, targetW, 32, true)
                
                val paddedLine = Bitmap.createBitmap(REC_WIDTH, REC_HEIGHT, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(paddedLine)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(resizedLine, 0f, 0f, null)

                val imgData = bitmapToFloatBuffer(paddedLine, REC_WIDTH, REC_HEIGHT)
                val inputTensor = OnnxTensor.createTensor(env, imgData, longArrayOf(1, 3, REC_HEIGHT.toLong(), REC_WIDTH.toLong()))
                
                val inputs = mutableMapOf<String, OnnxTensor>()
                val imageInputName = session.inputNames.find { it.contains("image") || it.contains("input") } ?: session.inputNames.iterator().next()
                inputs[imageInputName] = inputTensor

                // FIX: provide orig_target_sizes [960, 32] as required by the model architecture
                val sizeData = LongBuffer.wrap(longArrayOf(REC_WIDTH.toLong(), REC_HEIGHT.toLong()))
                inputs["orig_target_sizes"] = OnnxTensor.createTensor(env, sizeData, longArrayOf(1, 2))

                val output = session.run(inputs)

                val outputNames = session.outputNames.toList()
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
                                box = boxesArr[i] // [x1, y1, x2, y2]
                            )
                        )
                    }
                }

                candidates.sortByDescending { it.score }
                val filtered = mutableListOf<CharCandidate>()
                for (cand in candidates) {
                    var keep = true
                    for (f in filtered) {
                        if (calculateXOverlap(cand.box, f.box) > X_OVERLAP_THRESHOLD) {
                            keep = false
                            break
                        }
                    }
                    if (keep) filtered.add(cand)
                }

                filtered.sortBy { it.box[0] }
                val text = filtered.joinToString("") { it.char.toString() }
                
                val charBoxes = filtered.map { cand ->
                    val rx1 = cand.box[0]
                    val ry1 = cand.box[1]
                    val rx2 = cand.box[2]
                    val ry2 = cand.box[3]
                    
                    val effectiveW = targetW.toFloat()
                    val x1 = (min(rx1, effectiveW) / effectiveW) * crop.width + cropX
                    val y1 = (ry1 / 32f) * crop.height + cropY
                    val x2 = (min(rx2, effectiveW) / effectiveW) * crop.width + cropX
                    val y2 = (ry2 / 32f) * crop.height + cropY

                    Log.d("MeikiOcrEngine", "Char Box: [$x1, $y1, $x2, $y2] size: ${x2-x1}x${y2-y1}")
                    Rect(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
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

    fun close() {
        detectSession?.close()
        recognizeSession?.close()
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
