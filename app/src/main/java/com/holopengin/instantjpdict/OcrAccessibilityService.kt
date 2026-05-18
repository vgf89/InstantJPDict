package com.holopengin.instantjpdict

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                hideScreenshotOverlay()
            }
        }
    }
    
    private val boxViews = mutableListOf<View>()
    private val textViews = mutableListOf<TextView>()
    private var lastLandscapeGravity = Gravity.END
    private var lastPortraitGravity = Gravity.BOTTOM

    private val borderDrawable by lazy {
        GradientDrawable().apply {
            setColor(android.graphics.Color.argb(100, 0, 0, 0))
            cornerRadius = 4f
        }
    }

    private val highlightDrawable by lazy {
        GradientDrawable().apply {
            setStroke(4, android.graphics.Color.YELLOW)
            cornerRadius = 4f
        }
    }

    override fun onCreate() {
        super.onCreate()
        ocrEngine = MeikiOcrEngine(this)
        deinflector = Deinflector(this)
        
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)
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
            // Task 5: Toggle overlay
            if (screenshotOverlay != null) {
                hideScreenshotOverlay()
                return@setOnClickListener
            }
            
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

        // Task 2: Underneath system UI (try FLAG_LAYOUT_IN_SCREEN without FLAG_LAYOUT_NO_LIMITS)
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.FILL
        }

        val rootLayout = FrameLayout(this).apply {
            setOnClickListener {
                val resultsPanel = findViewWithTag<View>("results_panel")
                if (resultsPanel != null) {
                    removeView(resultsPanel)
                    resetHighlights()
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
            tag = "debug_text"
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
                        debugTextView.text = "Found ${results.sumOf { it.charBoxes.size }} characters."
                        debugTextView.bringToFront()
                        progressBar.visibility = View.GONE
                        
                        // Draw character results
                        drawResults(rootLayout, results)

                        // Task 1: Draw line boxes with translucent black background
                        lineBoxes.forEach { box ->
                            val lineView = View(this@OcrAccessibilityService).apply {
                                background = borderDrawable
                            }
                            val lineParams = FrameLayout.LayoutParams(box.width(), box.height()).apply {
                                leftMargin = box.left
                                topMargin = box.top
                            }
                            rootLayout.addView(lineView, lineParams)
                        }
                        
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
        boxViews.clear()
        textViews.clear()

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
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    typeface = android.graphics.Typeface.DEFAULT
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, box.height().toFloat())
                    setPadding(0, 0, 0, 0)
                    includeFontPadding = false
                    isClickable = false
                }
                textView.tag = currentIdx
                charContainer.addView(textView, FrameLayout.LayoutParams(box.width(), FrameLayout.LayoutParams.MATCH_PARENT))
                textViews.add(textView)

                val boxView = View(this).apply {
                    background = highlightDrawable // Visible border for debugging
                    alpha = 0.5f
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
                    resetHighlights()
                    
                    // Immediate feedback for the first character
                    textView.setTextColor(android.graphics.Color.YELLOW)
                    textView.typeface = android.graphics.Typeface.DEFAULT_BOLD

                    val endIdx = minOf(currentIdx + 20, allChars.size)
                    val followingText = allChars.subList(currentIdx, endIdx).joinToString("")
                    
                    serviceScope.launch {
                        val db = AppDatabase.getDatabase(applicationContext)
                        val allMatches = mutableListOf<Pair<String, List<com.holopengin.instantjpdict.data.DictionaryEntry>>>()
                        var maxMatchedLen = 0

                        for (len in followingText.length downTo 1) {
                            val queryTextRaw = followingText.substring(0, len)
                            val queryText = JapaneseUtil.normalize(queryTextRaw)
                            val queryTextHiragana = JapaneseUtil.katakanaToHiragana(queryText)
                            val queryTextCollapsed = JapaneseUtil.collapseEmphatic(queryText)
                            val variants = listOf(queryText, queryTextHiragana, queryTextCollapsed).distinct()
                            
                            var foundInThisLen = false
                            for (variant in variants) {
                                val dbResults = withContext(Dispatchers.IO) {
                                    db.dictionaryDao().findByText(variant)
                                }
                                if (dbResults.isNotEmpty()) {
                                    allMatches.add(variant to dbResults.sortedByDescending { it.popularity })
                                    foundInThisLen = true
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
                                    foundInThisLen = true
                                }
                            }

                            if (foundInThisLen && maxMatchedLen == 0) {
                                maxMatchedLen = len
                            }
                        }
                        
                        val uniqueMatches = allMatches.distinctBy { it.first }
                        if (uniqueMatches.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                // Highlight the entire matched sequence in text
                                for (i in 0 until maxMatchedLen) {
                                    val targetIdx = currentIdx + i
                                    if (targetIdx < textViews.size) {
                                        textViews[targetIdx].setTextColor(android.graphics.Color.YELLOW)
                                        textViews[targetIdx].typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    }
                                }
                                showResultsUi(rootLayout, uniqueMatches, box)
                            }
                        } else {
                            // Fallback highlight if no dictionary match but was tapped
                            withContext(Dispatchers.Main) {
                                textViews[currentIdx].setTextColor(android.graphics.Color.YELLOW)
                                textViews[currentIdx].typeface = android.graphics.Typeface.DEFAULT_BOLD

                                val debugView = rootLayout.findViewWithTag<TextView>("debug_text")
                                if (debugView != null) {
                                    debugView.text = "No entry found"
                                    debugView.postDelayed({
                                        val currentStatus = debugView.text.toString()
                                        if (currentStatus == "No entry found") {
                                            val count = results.sumOf { line -> line.charBoxes.size }
                                            debugView.text = "Found $count characters."
                                        }
                                    }, 2000)
                                }
                            }
                        }
                    }
                    Toast.makeText(this@OcrAccessibilityService, "Lookup started...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resetHighlights() {
        textViews.forEach { 
            it.setTextColor(android.graphics.Color.RED)
            it.typeface = android.graphics.Typeface.DEFAULT
        }
    }

    private fun showResultsUi(rootLayout: FrameLayout, matches: List<Pair<String, List<com.holopengin.instantjpdict.data.DictionaryEntry>>>, tappedBox: Rect) {
        // Task 3: Group identical readings, furigana, short POS
        
        // Remove existing result view if any
        rootLayout.findViewWithTag<View>("results_panel")?.let { rootLayout.removeView(it) }

        val container = LinearLayout(this).apply {
            tag = "results_panel"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.argb(245, 25, 25, 25))
            setPadding(40, 40, 40, 40)
            elevation = 20f
            setOnClickListener { }
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 100)
        }

        for ((term, entries) in matches) {
            val termSection = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 20, 0, 40)
            }

            // Group entries by reading to combine them
            val entriesByReading = entries.groupBy { it.reading }

            for ((reading, readingEntries) in entriesByReading) {
                // Term with Furigana
                val termHeader = createRubyView(term, reading)
                termSection.addView(termHeader)

                // Tags / Parts of Speech (combined from all entries with this reading)
                val allTags = readingEntries.flatMap { it.rules.split(" ") }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .map { formatTag(it) }
                
                if (allTags.isNotEmpty()) {
                    termSection.addView(TextView(this).apply {
                        text = allTags.joinToString(", ")
                        setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                        textSize = 14f
                        setPadding(0, 5, 0, 10)
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
                    })
                }

                for (entry in readingEntries) {
                    try {
                        val definitions = gson.fromJson<Any>(entry.definitions, Any::class.java)
                        renderDefinition(termSection, definitions)
                    } catch (e: Exception) {
                        termSection.addView(TextView(this).apply {
                            text = entry.definitions
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 15f
                            setPadding(20, 10, 0, 10)
                        })
                    }
                }
                
                termSection.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                        topMargin = 20
                        bottomMargin = 20
                    }
                    setBackgroundColor(android.graphics.Color.DKGRAY)
                    alpha = 0.3f
                })
            }
            scrollContent.addView(termSection)
        }

        scrollView.addView(scrollContent)
        container.addView(scrollView)

        val isLandscape = rootLayout.width > rootLayout.height
        val params = if (isLandscape) {
            val panelWidth = (rootLayout.width * 0.4).toInt()
            
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

    private fun formatTag(tag: String): String {
        return when(tag) {
            "v1" -> "v1"
            "v5" -> "v5"
            "v5k", "v5k-s" -> "v5k"
            "v5s" -> "v5s"
            "v5t" -> "v5t"
            "v5n" -> "v5n"
            "v5m" -> "v5m"
            "v5r", "v5r-i" -> "v5r"
            "v5w" -> "v5w"
            "v5g" -> "v5g"
            "v5z" -> "v5z"
            "v5b" -> "v5b"
            "vs", "vs-i", "vs-s" -> "vs"
            "vi" -> "vi"
            "vt" -> "vt"
            "adj-i" -> "adj-i"
            "adj-na" -> "adj-na"
            "n" -> "n"
            "adv" -> "adv"
            "pn" -> "pn"
            "p" -> "p"
            else -> tag
        }
    }

    private fun createRubyView(term: String, reading: String): View {
        if (term == reading) {
            return TextView(this).apply {
                text = term
                setTextColor(android.graphics.Color.CYAN)
                textSize = 28f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        var prefixLen = 0
        while (prefixLen < term.length && prefixLen < reading.length && term[prefixLen] == reading[prefixLen]) {
            prefixLen++
        }

        var suffixLen = 0
        while (suffixLen < (term.length - prefixLen) && suffixLen < (reading.length - prefixLen) && 
               term[term.length - 1 - suffixLen] == reading[reading.length - 1 - suffixLen]) {
            suffixLen++
        }

        val prefix = term.substring(0, prefixLen)
        val termMid = term.substring(prefixLen, term.length - suffixLen)
        val readingMid = reading.substring(prefixLen, reading.length - suffixLen)
        val suffix = term.substring(term.length - suffixLen)

        if (prefix.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = prefix
                setTextColor(android.graphics.Color.CYAN)
                textSize = 28f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
            })
        }

        if (termMid.isNotEmpty()) {
            val rubyItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            rubyItem.addView(TextView(this).apply {
                text = readingMid
                setTextColor(android.graphics.Color.LTGRAY)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
            })
            rubyItem.addView(TextView(this).apply {
                text = termMid
                setTextColor(android.graphics.Color.CYAN)
                textSize = 28f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
            })
            container.addView(rubyItem)
        }

        if (suffix.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = suffix
                setTextColor(android.graphics.Color.CYAN)
                textSize = 28f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
            })
        }

        return container
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
        boxViews.clear()
        textViews.clear()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Task 4: Close overlay if status bar is pulled down or app loses focus
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (screenshotOverlay != null) {
                if (event.packageName == "com.android.systemui") {
                    hideScreenshotOverlay()
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        // Task 4: Close on back button
        if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            if (screenshotOverlay != null) {
                hideScreenshotOverlay()
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Task 4 cleanup
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        floatingView?.let { windowManager?.removeView(it) }
        hideScreenshotOverlay()
        ocrEngine.close()
    }
}
