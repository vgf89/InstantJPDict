package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

class MeikiOcrEngine(private val context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var detectSession: OrtSession? = null
    private var recognizeSession: OrtSession? = null

    companion object {
        private const val DETECT_WIDTH = 960
        private const val DETECT_HEIGHT = 544
    }

    init {
        try {
            val detectModel = loadModel("meiki.text.detect.v0.1.960x544.onnx")
            detectSession = env.createSession(detectModel)
        } catch (e: Exception) {
            Log.e("MeikiOcrEngine", "Failed to load model", e)
        }
    }

    private fun loadModel(fileName: String): ByteArray {
        return context.assets.open(fileName).readBytes()
    }

    fun isReady(): Boolean = detectSession != null

    fun detect(bitmap: Bitmap): List<Rect> {
        val session = detectSession ?: return emptyList()

        // Match the reference script exactly: Stretch to 960x544
        val resized = Bitmap.createScaledBitmap(bitmap, DETECT_WIDTH, DETECT_HEIGHT, true)

        val imgData = FloatBuffer.allocate(1 * 3 * DETECT_HEIGHT * DETECT_WIDTH)
        val pixels = IntArray(DETECT_WIDTH * DETECT_HEIGHT)
        resized.getPixels(pixels, 0, DETECT_WIDTH, 0, 0, DETECT_WIDTH, DETECT_HEIGHT)

        for (c in 0 until 3) {
            for (y in 0 until DETECT_HEIGHT) {
                for (x in 0 until DETECT_WIDTH) {
                    val pixel = pixels[y * DETECT_WIDTH + x]
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

        val inputTensor = OnnxTensor.createTensor(env, imgData, longArrayOf(1, 3, DETECT_HEIGHT.toLong(), DETECT_WIDTH.toLong()))
        val inputs = mutableMapOf<String, OnnxTensor>()
        
        val imageInputName = session.inputNames.find { it.contains("image") || it.contains("input") } ?: session.inputNames.iterator().next()
        inputs[imageInputName] = inputTensor
        
        // Provide the original screen size so the model can scale its own outputs
        // Order: [Width, Height] to match sizes_input_tensor = [[960, 544]] logic
        if (session.inputNames.contains("orig_target_sizes")) {
            val sizeData = LongBuffer.wrap(longArrayOf(bitmap.width.toLong(), bitmap.height.toLong()))
            inputs["orig_target_sizes"] = OnnxTensor.createTensor(env, sizeData, longArrayOf(1, 2))
        } else if (session.inputNames.contains("image_size")) {
             // Fallback if the name is different
            val sizeData = LongBuffer.wrap(longArrayOf(bitmap.width.toLong(), bitmap.height.toLong()))
            inputs["image_size"] = OnnxTensor.createTensor(env, sizeData, longArrayOf(1, 2))
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

                val CONFIDENCE_THRESHOLD = 0.4f

                for (i in scoresArr.indices) {
                    if (scoresArr[i] > CONFIDENCE_THRESHOLD) {
                        val box = boxesArr[i] as FloatArray
                        // The model should be returning [x1, y1, x2, y2] already scaled to the orig_target_sizes
                        detectedBoxes.add(Rect(
                            box[0].toInt(),
                            box[1].toInt(),
                            box[2].toInt(),
                            box[3].toInt()
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MeikiOcrEngine", "Post-processing failed", e)
        } finally {
            inputs.values.forEach { it.close() }
            results.close()
            resized.recycle()
        }
        
        return detectedBoxes
    }

    fun close() {
        detectSession?.close()
        recognizeSession?.close()
        env.close()
    }
}

data class OcrResult(
    val text: String,
    val confidence: Float,
    val boundingBox: Rect
)
