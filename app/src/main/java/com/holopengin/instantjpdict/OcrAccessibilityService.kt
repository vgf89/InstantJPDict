package com.holopengin.instantjpdict

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.gson.Gson
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
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var lastLandscapeGravity = Gravity.END
    private var lastPortraitGravity = Gravity.BOTTOM

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
                val resultsPanel = findViewWithTag<View>("results_panel")
                if (resultsPanel != null) {
                    removeView(resultsPanel)
                } else {
                    hideScreenshotOverlay()
                }
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

        // --- LAYERED ARCHITECTURE ---
        // 1. Bottom layer: Margins that block the overlay from closing but do nothing else.
        // 2. Top layer: The actual characters and their click listeners.
        
        val marginsLayer = FrameLayout(this)
        val clicksLayer = FrameLayout(this)
        
        rootLayout.addView(marginsLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootLayout.addView(clicksLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        
        var charIndex = 0
        val boxViews = mutableListOf<View>()
        val margin = 50 // Safe zone margin

        for (line in results) {
            for (i in line.charBoxes.indices) {
                val box = line.charBoxes[i]
                val char = line.text.getOrNull(i)?.toString() ?: ""
                val currentIdx = charIndex++
                
                // Add margin to the background layer
                val marginBlocker = View(this).apply {
                    isClickable = true
                    setOnClickListener { } // Block clicks from reaching rootLayout
                }
                val marginParams = FrameLayout.LayoutParams(box.width() + 2 * margin, box.height() + 2 * margin).apply {
                    leftMargin = box.left - margin
                    topMargin = box.top - margin
                }
                marginsLayer.addView(marginBlocker, marginParams)

                // Add character interaction to the foreground layer
                val charContainer = FrameLayout(this)
                val charParams = FrameLayout.LayoutParams(box.width(), box.height() + (box.height() * 0.8).toInt()).apply {
                    leftMargin = box.left
                    topMargin = box.top - (box.height() * 0.4).toInt()
                }
                clicksLayer.addView(charContainer, charParams)

                val textView = TextView(this).apply {
                    text = char
                    setTextColor(android.graphics.Color.RED)
                    alpha = 0.7f
                    gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, box.height().toFloat())
                    setPadding(0, 0, 0, 0)
                    includeFontPadding = false
                    isClickable = false
                }
                charContainer.addView(textView, FrameLayout.LayoutParams(box.width(), FrameLayout.LayoutParams.MATCH_PARENT))

                val boxView = View(this).apply {
                    background = borderDrawable.constantState?.newDrawable()
                    isClickable = true
                }
                // Center the clickable box vertically in the container to match character position
                val boxViewParams = FrameLayout.LayoutParams(box.width(), box.height()).apply {
                    gravity = Gravity.CENTER
                }
                charContainer.addView(boxView, boxViewParams)
                boxViews.add(boxView)

                boxView.setOnClickListener {
                    // Reset all highlights
                    boxViews.forEach { it.background = borderDrawable.constantState?.newDrawable() }
                    // Highlight selected
                    boxView.background = highlightDrawable
                    
                    val endIdx = minOf(currentIdx + 20, allChars.size)
                    val followingText = allChars.subList(currentIdx, endIdx).joinToString("")
                    
                    serviceScope.launch {
                        val db = AppDatabase.getDatabase(applicationContext)
                        val allMatches = mutableListOf<Pair<String, List<com.holopengin.instantjpdict.data.DictionaryEntry>>>()

                        for (len in followingText.length downTo 1) {
                            val queryTextRaw = followingText.substring(0, len)
                            val queryText = JapaneseUtil.normalize(queryTextRaw)
                            val queryTextHiragana = JapaneseUtil.katakanaToHiragana(queryText)
                            val queryTextCollapsed = JapaneseUtil.collapseEmphatic(queryText)
                            val variants = listOf(queryText, queryTextHiragana, queryTextCollapsed).distinct()
                            
                            for (variant in variants) {
                                val dbResults = withContext(Dispatchers.IO) {
                                    db.dictionaryDao().findByText(variant)
                                }
                                if (dbResults.isNotEmpty()) {
                                    allMatches.add(variant to dbResults.sortedByDescending { it.popularity })
                                }
                            }
                            
                            val deinflections = deinflector.deinflect(queryText)
                            for (deinflection in deinflections) {
                                if (deinflection.term == queryText) continue 
                                val dbResults = withContext(Dispatchers.IO) {
                                    db.dictionaryDao().findByText(deinflection.term)
                                }
                                
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
                        
                        val uniqueMatches = allMatches.distinctBy { it.first }
                        if (uniqueMatches.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                showResultsUi(rootLayout, uniqueMatches, box)
                            }
                        }
                    }
                    Toast.makeText(this@OcrAccessibilityService, "Lookup started...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showResultsUi(rootLayout: FrameLayout, matches: List<Pair<String, List<com.holopengin.instantjpdict.data.DictionaryEntry>>>, tappedBox: Rect) {
        // Remove existing result view if any
        rootLayout.findViewWithTag<View>("results_panel")?.let { rootLayout.removeView(it) }

        val container = LinearLayout(this).apply {
            tag = "results_panel"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.argb(235, 30, 30, 30))
            setPadding(40, 40, 40, 40)
            elevation = 20f
            // Consume clicks so they don't reach rootLayout and close the overlay
            setOnClickListener { }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(this).apply {
            text = "Dictionary Results"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        header.addView(Button(this).apply {
            text = "X"
            setOnClickListener { rootLayout.removeView(container) }
            layoutParams = LinearLayout.LayoutParams(120, 120)
        })

        container.addView(header)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 80)
        }

        for ((term, entries) in matches) {
            val termSection = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 30, 0, 30)
            }

            termSection.addView(TextView(this).apply {
                text = term
                setTextColor(android.graphics.Color.CYAN)
                textSize = 24f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })

            for (entry in entries) {
                if (entry.reading.isNotEmpty() && entry.reading != term) {
                    termSection.addView(TextView(this).apply {
                        text = "Reading: ${entry.reading}"
                        setTextColor(android.graphics.Color.LTGRAY)
                        textSize = 16f
                    })
                }

                try {
                    val definitions = gson.fromJson<Any>(entry.definitions, Any::class.java)
                    renderDefinition(termSection, definitions)
                } catch (e: Exception) {
                    termSection.addView(TextView(this).apply {
                        text = entry.definitions
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 14f
                    })
                }
                
                termSection.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                    setBackgroundColor(android.graphics.Color.DKGRAY)
                    alpha = 0.5f
                })
            }
            scrollContent.addView(termSection)
        }

        scrollView.addView(scrollContent)
        container.addView(scrollView)

        val isLandscape = rootLayout.width > rootLayout.height
        val params = if (isLandscape) {
            val panelWidth = (rootLayout.width * 0.4).toInt()
            
            // Overlap check for current side
            val overlaps = if (lastLandscapeGravity == Gravity.END) {
                tappedBox.right > rootLayout.width - panelWidth
            } else {
                tappedBox.left < panelWidth
            }
            
            if (overlaps) {
                lastLandscapeGravity = if (lastLandscapeGravity == Gravity.END) Gravity.START else Gravity.END
            }

            FrameLayout.LayoutParams(
                panelWidth,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = lastLandscapeGravity
            }
        } else {
            val panelHeight = (rootLayout.height * 0.4).toInt()
            
            // Overlap check for current side
            val overlaps = if (lastPortraitGravity == Gravity.BOTTOM) {
                tappedBox.bottom > rootLayout.height - panelHeight
            } else {
                tappedBox.top < panelHeight
            }
            
            if (overlaps) {
                lastPortraitGravity = if (lastPortraitGravity == Gravity.BOTTOM) Gravity.TOP else Gravity.BOTTOM
            }

            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                panelHeight
            ).apply {
                gravity = lastPortraitGravity
            }
        }

        rootLayout.addView(container, params)
    }

    private fun renderDefinition(container: LinearLayout, data: Any?, level: Int = 0) {
        when (data) {
            is String -> {
                container.addView(TextView(this).apply {
                    text = if (level == 0) "• $data" else data
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    setPadding(20 * (level + 1), 5, 0, 5)
                })
            }
            is List<*> -> {
                for (item in data) {
                    renderDefinition(container, item, level)
                }
            }
            is Map<*, *> -> {
                // Handle Yomitan Structured Content
                val content = data["content"]
                if (content != null) {
                    renderDefinition(container, content, level + 1)
                } else {
                    val text = data["text"]
                    if (text is String) {
                        container.addView(TextView(this).apply {
                            this.text = text
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 16f
                            setPadding(20 * (level + 1), 5, 0, 5)
                        })
                    }
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
