package com.holopengin.instantjpdict

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.util.Deinflector
import com.holopengin.instantjpdict.util.JapaneseUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class OcrAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var screenshotOverlay: View? = null
    private lateinit var ocrEngine: MeikiOcrEngine
    private lateinit var deinflector: Deinflector
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        ocrEngine = MeikiOcrEngine(this)
        deinflector = Deinflector(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        addFloatingButton()
    }

    private fun addFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        val frameLayout = FrameLayout(this)
        val button = Button(this).apply {
            text = "OCR"
        }
        frameLayout.addView(button)
        
        button.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                        params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                        windowManager?.updateViewLayout(frameLayout, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = event.rawX - initialTouchX
                        val diffY = event.rawY - initialTouchY
                        if (abs(diffX) < 10 && abs(diffY) < 10) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        button.setOnClickListener {
            floatingView?.visibility = View.GONE
            // Give the system a moment to hide the view before taking the screenshot
            it.postDelayed({
                triggerCapture { bitmap ->
                    showScreenshotOverlay(bitmap)
                }
            }, 50)
        }
        
        floatingView = frameLayout
        windowManager?.addView(floatingView, params)
    }

    private fun triggerCapture(onSuccessAction: (Bitmap) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    floatingView?.visibility = View.VISIBLE
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, true)
                    buffer.close()
                    if (bitmap != null) {
                        onSuccessAction(bitmap)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    floatingView?.visibility = View.VISIBLE
                    Log.e("OcrAccessibilityService", "Screenshot capture failed with error code: $errorCode")
                    Toast.makeText(this@OcrAccessibilityService, "Screenshot failed: $errorCode", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun showScreenshotOverlay(bitmap: Bitmap) {
        if (screenshotOverlay != null) return

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.FILL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val rootLayout = FrameLayout(this).apply {
            setOnClickListener {
                hideScreenshotOverlay()
            }
        }

        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
        }
        rootLayout.addView(imageView)

        val debugTextView = TextView(this).apply {
            setTextColor(android.graphics.Color.YELLOW)
            setBackgroundColor(android.graphics.Color.argb(200, 0, 0, 0))
            setPadding(40, 20, 40, 20)
            textSize = 16f
            text = "Initializing OCR..."
        }
        val debugParams = FrameLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 100
        }
        rootLayout.addView(debugTextView, debugParams)

        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val progressParams = FrameLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        rootLayout.addView(progressBar, progressParams)

        screenshotOverlay = rootLayout
        windowManager?.addView(screenshotOverlay, params)

        // Run OCR in background
        Thread {
            try {
                if (ocrEngine.isReady()) {
                    rootLayout.post { debugTextView.text = "Running detection..." }
                    val lineBoxes = ocrEngine.detect(bitmap)
                    
                    rootLayout.post { debugTextView.text = "Found ${lineBoxes.size} lines. Recognizing..." }
                    val results = ocrEngine.recognize(bitmap, lineBoxes)
                    
                    rootLayout.post {
                        debugTextView.text = "Done. Found ${results.sumOf { it.charBoxes.size }} characters."
                        debugTextView.bringToFront()
                        progressBar.visibility = View.GONE
                        
                        // Draw character results
                        drawResults(rootLayout, results)
                        
                        // Log results
                        results.forEach { line ->
                            Log.i("OcrAccessibilityService", "Recognized: ${line.text}")
                        }
                    }
                } else {
                    rootLayout.post {
                        debugTextView.text = "Error: OCR Engine not ready"
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.toString()
                Log.e("OcrAccessibilityService", "OCR Inference Error", e)
                rootLayout.post {
                    debugTextView.text = "Error: $errorMsg"
                    debugTextView.bringToFront()
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "OCR Error: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun drawResults(rootLayout: FrameLayout, results: List<LineResult>) {
        val borderDrawable = GradientDrawable().apply {
            setStroke(2, android.graphics.Color.CYAN)
            setColor(android.graphics.Color.argb(76, 0, 255, 255))
            cornerRadius = 4f
        }

        val highlightDrawable = GradientDrawable().apply {
            setStroke(4, android.graphics.Color.YELLOW)
            setColor(android.graphics.Color.argb(120, 255, 255, 0))
            cornerRadius = 4f
        }

        // Flatten results for easy index lookup
        val allChars = results.flatMap { line -> 
            line.text.indices.map { i -> line.text[i] } 
        }
        
        var charIndex = 0
        val boxViews = mutableListOf<View>()

        for (line in results) {
            for (i in line.charBoxes.indices) {
                val box = line.charBoxes[i]
                val char = line.text.getOrNull(i)?.toString() ?: ""
                val currentIdx = charIndex++
                
                // 1. Draw the actual OCR box (cyan border)
                val boxView = View(this).apply {
                    background = borderDrawable.constantState?.newDrawable()
                    isClickable = true
                    isFocusable = true
                }
                val boxParams = FrameLayout.LayoutParams(box.width(), box.height()).apply {
                    leftMargin = box.left
                    topMargin = box.top
                }
                rootLayout.addView(boxView, boxParams)
                boxViews.add(boxView)

                // 2. Draw the text (scaled and potentially overflowing to prevent clipping)
                val textView = TextView(this).apply {
                    text = char
                    setTextColor(android.graphics.Color.RED)
                    alpha = 0.7f
                    gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, box.height().toFloat())
                    setPadding(0, 0, 0, 0)
                    includeFontPadding = false
                    // Let clicks pass through to the boxView below
                    isClickable = false
                }

                val extraHeight = (box.height() * 0.8).toInt()
                val textParams = FrameLayout.LayoutParams(box.width(), box.height() + extraHeight).apply {
                    leftMargin = box.left
                    topMargin = box.top - (extraHeight / 2)
                }
                rootLayout.addView(textView, textParams)

                boxView.setOnClickListener {
                    // Reset all highlights
                    boxViews.forEach { it.background = borderDrawable.constantState?.newDrawable() }
                    // Highlight selected
                    boxView.background = highlightDrawable
                    
                    // Extract following text (capped at 20 chars for performance)
                    val endIdx = minOf(currentIdx + 20, allChars.size)
                    val followingText = allChars.subList(currentIdx, endIdx).joinToString("")
                    
                    Log.i("OcrAccessibilityService", "Tapped '$char'. Searching sequence: $followingText")
                    
                    serviceScope.launch {
                        val db = AppDatabase.getDatabase(applicationContext)
                        val allMatches = mutableListOf<Pair<String, List<com.holopengin.instantjpdict.data.DictionaryEntry>>>()

                        // Greedy Search Loop: Longest string to shortest
                        for (len in followingText.length downTo 1) {
                            val queryTextRaw = followingText.substring(0, len)
                            
                            // Normalization Pass
                            val queryText = JapaneseUtil.normalize(queryTextRaw)
                            val queryTextHiragana = JapaneseUtil.katakanaToHiragana(queryText)
                            val queryTextCollapsed = JapaneseUtil.collapseEmphatic(queryText)

                            // Try variants: normalized, hiragana-only, and collapsed emphatic sequences
                            val variants = listOf(queryText, queryTextHiragana, queryTextCollapsed).distinct()
                            
                            for (variant in variants) {
                                val dbResults = withContext(Dispatchers.IO) {
                                    db.dictionaryDao().findByText(variant)
                                }
                                if (dbResults.isNotEmpty()) {
                                    allMatches.add(variant to dbResults.sortedByDescending { it.popularity })
                                }
                            }
                            
                            // Deinflection Pass
                            val deinflections = deinflector.deinflect(queryText)
                            for (deinflection in deinflections) {
                                if (deinflection.term == queryText) continue 
                                
                                val dbResults = withContext(Dispatchers.IO) {
                                    db.dictionaryDao().findByText(deinflection.term)
                                }
                                
                                // Filter based on rules (PoS tags)
                                val validResults = dbResults.filter { entry ->
                                    val entryTags = entry.rules.split(" ")
                                    deinflection.type.isEmpty() || 
                                    deinflection.type.any { it in entryTags } ||
                                    (entryTags.any { it.startsWith("v") } && deinflection.type.any { it.startsWith("v") })
                                }

                                if (validResults.isNotEmpty()) {
                                    allMatches.add(deinflection.term to validResults.sortedByDescending { it.popularity })
                                }
                            }
                        }
                        
                        // Display all unique matches found
                        val uniqueMatches = allMatches.distinctBy { it.first }
                        if (uniqueMatches.isNotEmpty()) {
                            Log.i("OcrAccessibilityService", "FOUND ${uniqueMatches.size} UNIQUE MATCHES:")
                            uniqueMatches.forEach { (term, entries) ->
                                Log.i("OcrAccessibilityService", "  MATCH: '$term' (${entries.size} entries)")
                                entries.forEach { entry ->
                                    Log.i("OcrAccessibilityService", "    - [${entry.reading}] ${entry.definitions}")
                                }
                            }
                        } else {
                            Log.i("OcrAccessibilityService", "No dictionary results found for any part of '$followingText'")
                        }
                    }
                    
                    Toast.makeText(this@OcrAccessibilityService, "Lookup started...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hideScreenshotOverlay() {
        screenshotOverlay?.let {
            windowManager?.removeView(it)
            screenshotOverlay = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager?.removeView(it) }
        hideScreenshotOverlay()
        ocrEngine.close()
    }
}
