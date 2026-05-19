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
import android.widget.HorizontalScrollView
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
    private var floatingParams: WindowManager.LayoutParams? = null
    private var ocrButton: Button? = null
    private var screenshotOverlay: View? = null
    private lateinit var ocrEngine: MeikiOcrEngine
    private lateinit var deinflector: Deinflector
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    hideScreenshotOverlay()
                    floatingView?.visibility = View.GONE
                }
                Intent.ACTION_USER_PRESENT -> {
                    floatingView?.visibility = View.VISIBLE
                }
            }
        }
    }
    
    private val boxViews = mutableListOf<View>()
    private val textViews = mutableListOf<TextView>()
    private var lastLandscapeGravity = Gravity.END
    private var lastPortraitGravity = Gravity.BOTTOM

    private var activeLineResults: List<LineResult> = emptyList()
    private var activeAllChars: MutableList<Char> = mutableListOf()
    private var activeAllAlternatives: List<List<Pair<Char, Float>>> = emptyList()
    private data class CharInfo(val lineIdx: Int, val charIdxInLine: Int, val box: Rect)
    private var activeCharInfos: List<CharInfo> = emptyList()
    private var currentTappedIdx: Int = -1

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
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenOffReceiver, filter)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        addFloatingButton()
    }

    private fun addFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        floatingParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        val frameLayout = FrameLayout(this)
        ocrButton = Button(this).apply {
            text = "OCR"
        }
        frameLayout.addView(ocrButton)
        
        ocrButton?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = floatingParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newX = initialX + (event.rawX - initialTouchX).roundToInt()
                        val newY = initialY + (event.rawY - initialTouchY).roundToInt()
                        params.x = newX
                        params.y = newY
                        
                        val fv = floatingView ?: return false
                        if (fv.parent == screenshotOverlay) {
                            val lp = fv.layoutParams as FrameLayout.LayoutParams
                            lp.leftMargin = newX
                            lp.topMargin = newY
                            fv.layoutParams = lp
                        } else if (fv.isAttachedToWindow) {
                            windowManager?.updateViewLayout(fv, params)
                        }
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

        ocrButton?.setOnClickListener {
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
        windowManager?.addView(floatingView, floatingParams)
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

        // Task 2: Underneath system UI (try FLAG_LAYOUT_IN_SCREEN with FLAG_LAYOUT_NO_LIMITS for full screen)
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
            // Task 2: Try to keep system UI visible
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            setOnClickListener {
                val panel = findViewWithTag<View>("correction_ui_root")
                if (panel != null) {
                    removeView(panel)
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
            setPadding(20, 10, 20, 10)
            textSize = 12f
            text = "Initializing OCR..."
        }
        val debugParams = FrameLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 0
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
        
        // Task 5: Add a separate Close button in the overlay
        val closeButton = Button(this).apply {
            text = "Close OCR"
            setOnClickListener { hideScreenshotOverlay() }
            setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0f
                private var initialY = 0f
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = lp.leftMargin.toFloat()
                            initialY = lp.topMargin.toFloat()
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val newX = (initialX + (event.rawX - initialTouchX)).roundToInt()
                            val newY = (initialY + (event.rawY - initialTouchY)).roundToInt()
                            lp.leftMargin = newX
                            lp.topMargin = newY
                            v.layoutParams = lp
                            
                            // Update the base position so the main button appears here later
                            floatingParams?.x = newX
                            floatingParams?.y = newY
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
        }
        val lp = FrameLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = floatingParams?.x ?: 100
            topMargin = floatingParams?.y ?: 100
        }
        rootLayout.addView(closeButton, lp)

        // Run OCR in background
        serviceScope.launch {
            try {
                if (ocrEngine.isReady()) {
                    debugTextView.text = "Running detection..."
                    val lineBoxes = withContext(Dispatchers.IO) { ocrEngine.detect(bitmap) }
                    
                    debugTextView.text = "Found ${lineBoxes.size} lines. Recognizing..."
                    val results = withContext(Dispatchers.IO) { ocrEngine.recognize(bitmap, lineBoxes) }
                    
                    debugTextView.text = "Found ${results.sumOf { it.charBoxes.size }} characters."
                    debugTextView.bringToFront()
                    progressBar.visibility = View.GONE
                    
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

                    // Draw character results
                    drawResults(rootLayout, results)
                    
                    // Log results
                    results.forEach { line ->
                        Log.i("OcrAccessibilityService", "Recognized: ${line.text}")
                    }
                } else {
                    debugTextView.text = "Error: OCR Engine not ready"
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.toString()
                Log.e("OcrAccessibilityService", "OCR Inference Error", e)
                debugTextView.text = "Error: $errorMsg"
                debugTextView.bringToFront()
                progressBar.visibility = View.GONE
                Toast.makeText(this@OcrAccessibilityService, "OCR Error: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun drawResults(rootLayout: FrameLayout, results: List<LineResult>) {
        boxViews.clear()
        textViews.clear()

        activeLineResults = results
        activeAllChars = results.flatMap { line -> line.text.toList() }.toMutableList()
        activeAllAlternatives = results.flatMap { it.alternatives }
        
        val infos = mutableListOf<CharInfo>()
        results.forEachIndexed { lineIdx, line ->
            line.charBoxes.indices.forEach { charIdx ->
                infos.add(CharInfo(lineIdx, charIdx, line.charBoxes[charIdx]))
            }
        }
        activeCharInfos = infos

        Log.i("OcrAccessibilityService", "drawResults: allChars.size=${activeAllChars.size}, allAlternatives.size=${activeAllAlternatives.size}")

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
                    gravity = Gravity.CENTER
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
                    background = null // Entirely transparent
                    isClickable = true
                }
                // Center the clickable box vertically in the container to match character position
                val boxViewParams = FrameLayout.LayoutParams(box.width(), box.height()).apply {
                    gravity = Gravity.CENTER
                }
                charContainer.addView(boxView, boxViewParams)
                boxViews.add(boxView)

                boxView.setOnClickListener {
                    performLookup(currentIdx, rootLayout)
                }
            }
        }
    }

    private fun performLookup(currentIdx: Int, rootLayout: FrameLayout) {
        currentTappedIdx = currentIdx
        val tappedBox = activeCharInfos[currentIdx].box

        // Print top-15 candidates to logcat
        activeAllAlternatives.getOrNull(currentIdx)?.let { alternatives ->
            val logMsg = alternatives.joinToString(", ") { (char, score) ->
                "$char (${String.format(java.util.Locale.US, "%.8f", score)})"
            }
            Log.i("OcrAccessibilityService", "Top 15 candidates for '${activeAllChars[currentIdx]}': $logMsg")
        }

        // Reset all highlights
        resetHighlights()

        // Immediate feedback for the first character
        if (currentIdx < textViews.size) {
            textViews[currentIdx].setTextColor(android.graphics.Color.YELLOW)
            textViews[currentIdx].typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val endIdx = kotlin.math.min(currentIdx + 20, activeAllChars.size)
        val followingText = activeAllChars.subList(currentIdx, endIdx).joinToString("")

        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val allMatches = mutableListOf<Pair<String, List<com.holopengin.instantjpdict.data.DictionaryEntry>>>()
            var maxMatchedLen = 0

            // 1. Pre-calculate all candidate terms to minimize DB round-trips
            val candidatesByLength = mutableListOf<Pair<Int, List<Pair<String, List<String>?>>>>()
            val allTermsToSearch = mutableSetOf<String>()

            for (len in followingText.length downTo 1) {
                val queryTextRaw = followingText.substring(0, len)
                val queryText = JapaneseUtil.normalize(queryTextRaw)

                val variants = listOf(
                    queryText,
                    JapaneseUtil.katakanaToHiragana(queryText),
                    JapaneseUtil.collapseEmphatic(queryText)
                ).distinct()

                val deinflections = deinflector.deinflect(queryText)

                val lengthCandidates = mutableListOf<Pair<String, List<String>?>>()
                variants.forEach {
                    lengthCandidates.add(it to null)
                    allTermsToSearch.add(it)
                }
                deinflections.forEach {
                    if (it.term != queryText) {
                        lengthCandidates.add(it.term to it.type)
                        allTermsToSearch.add(it.term)
                    }
                }
                candidatesByLength.add(len to lengthCandidates)
            }

            // 2. Single Batch Query
            val dbResults = withContext(Dispatchers.IO) {
                db.dictionaryDao().findByTexts(allTermsToSearch.toList())
            }

            // Group results by the search term (kanji or reading) for fast lookup
            val resultsByTerm = mutableMapOf<String, MutableList<com.holopengin.instantjpdict.data.DictionaryEntry>>()
            dbResults.forEach { entry ->
                if (entry.kanji in allTermsToSearch) resultsByTerm.getOrPut(entry.kanji) { mutableListOf() }.add(entry)
                if (entry.reading in allTermsToSearch) resultsByTerm.getOrPut(entry.reading) { mutableListOf() }.add(entry)
            }

            // 3. Process results in order of length (longest first)
            for ((len, candidates) in candidatesByLength) {
                var foundInThisLen = false

                for ((term, requiredTypes) in candidates) {
                    val termEntries = resultsByTerm[term] ?: continue

                    val filteredResults = if (requiredTypes == null) {
                        // Variant/Direct match: Apply standard Kanji filter
                        val queryTextRaw = followingText.substring(0, len)
                        val queryText = JapaneseUtil.normalize(queryTextRaw)
                        termEntries.filter { entry ->
                            val isKanjiEntry = entry.onyomi != null || entry.kunyomi != null
                            !isKanjiEntry || entry.kanji == queryText
                        }
                    } else {
                        // Deinflection match: Apply type filter
                        termEntries.filter { entry ->
                            val entryTags = entry.rules.split(" ")
                            requiredTypes.isEmpty() ||
                                    requiredTypes.any { it in entryTags } ||
                                    (entryTags.any { it.startsWith("v") } && requiredTypes.any { it.startsWith("v") })
                        }
                    }

                    if (filteredResults.isNotEmpty()) {
                        allMatches.add(term to filteredResults.distinctBy { it.id })
                        foundInThisLen = true
                    }
                }

                if (foundInThisLen && maxMatchedLen == 0) {
                    maxMatchedLen = len
                }
            }

            val uniqueMatches = allMatches.distinctBy { it.first }
            withContext(Dispatchers.Main) {
                if (uniqueMatches.isNotEmpty()) {
                    // Highlight the entire matched sequence in text
                    for (i in 0 until maxMatchedLen) {
                        val targetIdx = currentIdx + i
                        if (targetIdx < textViews.size) {
                            textViews[targetIdx].setTextColor(android.graphics.Color.YELLOW)
                            textViews[targetIdx].typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                    }
                    showResultsUi(rootLayout, uniqueMatches, tappedBox)
                } else {
                    // Fallback highlight if no dictionary match but was tapped
                    if (currentIdx < textViews.size) {
                        textViews[currentIdx].setTextColor(android.graphics.Color.YELLOW)
                        textViews[currentIdx].typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }

                    val debugView = rootLayout.findViewWithTag<TextView>("debug_text")
                    if (debugView != null) {
                        debugView.text = "No entry found"
                        debugView.postDelayed({
                            val currentStatus = debugView.text.toString()
                            if (currentStatus == "No entry found") {
                                val count = activeAllChars.size
                                debugView.text = "Found $count characters."
                            }
                        }, 2000)
                    }
                    showResultsUi(rootLayout, emptyList(), tappedBox)
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
        rootLayout.findViewWithTag<View>("correction_ui_root")?.let { rootLayout.removeView(it) }

        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val isLandscape = rootWidth > rootHeight
        
        // Determine panel sizes
        val panelWidth = if (isLandscape) (rootWidth * 0.4).toInt() else FrameLayout.LayoutParams.MATCH_PARENT
        val panelHeight = if (isLandscape) FrameLayout.LayoutParams.MATCH_PARENT else (rootHeight * 0.4).toInt()

        // Dictionary Panel (the original container)
        val dictionaryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.argb(245, 25, 25, 25))
            setPadding(40, 40, 40, 40)
            elevation = 20f
            setOnClickListener { }
        }

        if (matches.isEmpty()) {
            dictionaryPanel.addView(TextView(this).apply {
                text = "No results found for \"${activeAllChars.getOrNull(currentTappedIdx)}\""
                setTextColor(android.graphics.Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, 100, 0, 0)
            })
        } else {
            val scrollView = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                isVerticalScrollBarEnabled = false
            }
            val scrollContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 100)
            }
            // ... (fill dictionary results, same as before)
            for ((_, entries) in matches) {
                val termSection = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 20, 0, 40)
                }
                val entriesByReading = entries.groupBy { it.reading }
                for ((_, readingEntries) in entriesByReading) {
                    val entry = readingEntries.first()
                    val isKanjiEntry = entry.onyomi != null || entry.kunyomi != null
                    val allGlobalTags = mutableSetOf<String>()
                    val allInfoText = mutableSetOf<String>()
                    for (e in readingEntries) {
                        e.jlpt?.takeIf { it.isNotEmpty() }?.let { allInfoText.add("jlpt: N$it") }
                        val gradeMatch = "grade:([^\\s]+)".toRegex().find(e.rules)
                        gradeMatch?.groupValues?.get(1)?.let { allInfoText.add("grade: $it") }
                        val segments = e.rules.split(" | ")
                        val t1 = segments.getOrNull(0)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                        val rs = segments.getOrNull(1)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                        val t2 = segments.getOrNull(2)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                        (t1 + rs + t2).forEach { tag -> if (tag.toIntOrNull() == null && !tag.startsWith("grade:")) allGlobalTags.add(tag) }
                    }
                    if (isKanjiEntry) {
                        val termHeader = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 10, 0, 10)
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        termHeader.addView(TextView(this).apply {
                            text = entry.kanji
                            setTextColor(android.graphics.Color.CYAN)
                            textSize = 48f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setPadding(0, 0, 40, 0)
                        })
                        val readingContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        entry.onyomi?.takeIf { it.isNotEmpty() }?.let { readingContainer.addView(TextView(this).apply { text = "on: ${it.replace(" ", "、")}"; setTextColor(android.graphics.Color.LTGRAY); textSize = 14f; includeFontPadding = false }) }
                        entry.kunyomi?.takeIf { it.isNotEmpty() }?.let { readingContainer.addView(TextView(this).apply { text = "kun: ${it.replace(" ", "、")}"; setTextColor(android.graphics.Color.LTGRAY); textSize = 14f; includeFontPadding = false }) }
                        if (allInfoText.isNotEmpty()) { readingContainer.addView(TextView(this).apply { text = "${allInfoText.joinToString(", ")}"; setTextColor(android.graphics.Color.parseColor("#666666")); textSize = 11f; includeFontPadding = false }) }
                        termHeader.addView(readingContainer)
                        termSection.addView(termHeader)
                    } else {
                        termSection.addView(createRubyView(entry.kanji, entry.reading))
                    }
                    val metadataContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 5, 0, 10) }
                    termSection.addView(metadataContainer)
                    if (!isKanjiEntry && allInfoText.isNotEmpty()) {
                        metadataContainer.addView(TextView(this).apply { text = "${allInfoText.joinToString(", ")}"; setTextColor(android.graphics.Color.parseColor("#666666")); textSize = 11f; setPadding(20, 5, 0, 0) })
                    }
                    for (e in readingEntries) {
                        try {
                            val definitions = gson.fromJson<Any>(e.definitions, Any::class.java)
                            if (definitions is List<*>) {
                                val segments = e.rules.split(" | ")
                                val t1 = segments.getOrNull(0)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                                val t2 = segments.getOrNull(2)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                                val senseToTags = mutableMapOf<Int, MutableList<String>>()
                                val unnumberedTags = mutableListOf<String>()
                                fun parseSenseTags(tagList: List<String>) {
                                    var currentSenseNum: Int? = null
                                    for (tag in tagList) {
                                        val num = tag.toIntOrNull()
                                        if (num != null) currentSenseNum = num
                                        else { if (currentSenseNum != null) senseToTags.getOrPut(currentSenseNum) { mutableListOf() }.add(tag) else unnumberedTags.add(tag) }
                                    }
                                }
                                parseSenseTags(t1); parseSenseTags(t2)
                                definitions.forEachIndexed { index, sense ->
                                    val senseNum = index + 1
                                    val tagsForThisSense = mutableListOf<String>()
                                    senseToTags[senseNum]?.let { tagsForThisSense.addAll(it) }
                                    if (definitions.size == 1 && senseToTags.isNotEmpty()) senseToTags.values.forEach { tagsForThisSense.addAll(it) }
                                    tagsForThisSense.addAll(unnumberedTags)
                                    val finalSenseTags = tagsForThisSense.distinct().filter { it.toIntOrNull() == null && !it.startsWith("grade:") }
                                    if (finalSenseTags.isNotEmpty()) { termSection.addView(TextView(this@OcrAccessibilityService).apply { text = finalSenseTags.joinToString(", "); setTextColor(android.graphics.Color.parseColor("#666666")); textSize = 11f; setPadding(20, 5, 0, 0) }) }
                                    renderDefinition(termSection, sense, 0)
                                }
                            } else { renderDefinition(termSection, definitions) }
                        } catch (ex: Exception) {
                            termSection.addView(TextView(this).apply { text = e.definitions; setTextColor(android.graphics.Color.WHITE); textSize = 15f; setPadding(20, 10, 0, 10) })
                        }
                    }
                    termSection.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 20; bottomMargin = 20 }; setBackgroundColor(android.graphics.Color.DKGRAY); alpha = 0.3f })
                }
                scrollContent.addView(termSection)
            }
            scrollView.addView(scrollContent)
            dictionaryPanel.addView(scrollView)
        }

        // --- NEW CORRECTION UI ---

        val mainContainer = LinearLayout(this).apply {
            tag = "correction_ui_root"
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (isLandscape) {
                if (lastLandscapeGravity == Gravity.END) {
                    val overlaps = tappedBox.right > rootWidth - panelWidth
                    if (overlaps) lastLandscapeGravity = Gravity.START
                } else {
                    val overlaps = tappedBox.left < panelWidth
                    if (overlaps) lastLandscapeGravity = Gravity.END
                }
                lastLandscapeGravity
            } else {
                if (lastPortraitGravity == Gravity.BOTTOM) {
                    val overlaps = tappedBox.bottom > rootHeight - panelHeight
                    if (overlaps) lastPortraitGravity = Gravity.TOP
                } else {
                    val overlaps = tappedBox.top < panelHeight
                    if (overlaps) lastPortraitGravity = Gravity.BOTTOM
                }
                lastPortraitGravity
            }
        }

        val correctionPanel = createCorrectionPanel(currentTappedIdx, isLandscape, rootLayout).apply {
            layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
        val alternativesPanelContainer = FrameLayout(this).apply {
            tag = "alternatives_container"
            layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }

        if (isLandscape) {
            if (lastLandscapeGravity == Gravity.END) {
                mainContainer.addView(alternativesPanelContainer)
                mainContainer.addView(correctionPanel)
                mainContainer.addView(dictionaryPanel, LinearLayout.LayoutParams(panelWidth, panelHeight))
            } else {
                mainContainer.addView(dictionaryPanel, LinearLayout.LayoutParams(panelWidth, panelHeight))
                mainContainer.addView(correctionPanel)
                mainContainer.addView(alternativesPanelContainer)
            }
        } else {
            if (lastPortraitGravity == Gravity.BOTTOM) {
                mainContainer.addView(alternativesPanelContainer)
                mainContainer.addView(correctionPanel)
                mainContainer.addView(dictionaryPanel, LinearLayout.LayoutParams(panelWidth, panelHeight))
            } else {
                mainContainer.addView(dictionaryPanel, LinearLayout.LayoutParams(panelWidth, panelHeight))
                mainContainer.addView(correctionPanel)
                mainContainer.addView(alternativesPanelContainer)
            }
        }

        val rootParams = FrameLayout.LayoutParams(
            if (isLandscape) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT,
            if (isLandscape) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = mainContainer.gravity
        }

        rootLayout.addView(mainContainer, rootParams)
    }

    private fun createCorrectionPanel(currentIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout): View {
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val totalSpace = if (isLandscape) rootHeight else rootWidth
        val itemSize = totalSpace / 11
        val density = resources.displayMetrics.density
        val estimatedTextSize = (itemSize * 0.45 / density).toFloat().coerceIn(12f, 22f)

        val panel = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.argb(255, 45, 45, 45))
            setPadding(6, 6, 6, 6)
            elevation = 25f
            setOnClickListener { }
        }

        val lp = if (isLandscape) {
            LinearLayout.LayoutParams(itemSize, 0, 1f).apply { setMargins(2, 2, 2, 2) }
        } else {
            LinearLayout.LayoutParams(0, itemSize, 1f).apply { setMargins(2, 2, 2, 2) }
        }

        // Always show 11 slots for consistency
        for (offset in -5..5) {
            val i = currentIdx + offset
            if (i in activeAllChars.indices) {
                val char = activeAllChars[i]
                val textView = TextView(this).apply {
                    text = char.toString()
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = estimatedTextSize
                    gravity = Gravity.CENTER
                    
                    if (i == currentIdx) {
                        setBackgroundColor(android.graphics.Color.YELLOW)
                        setTextColor(android.graphics.Color.BLACK)
                    } else {
                        setBackgroundColor(android.graphics.Color.argb(255, 65, 65, 65))
                    }

                    setOnClickListener {
                        if (i == currentIdx) {
                            toggleAlternativesPanel(rootLayout, i, isLandscape)
                        } else {
                            performLookup(i, rootLayout)
                        }
                    }
                }
                panel.addView(textView, lp)
            } else {
                // Spacer for out-of-bounds
                panel.addView(View(this), lp)
            }
        }
        return panel
    }

    private fun toggleAlternativesPanel(rootLayout: FrameLayout, currentIdx: Int, isLandscape: Boolean) {
        val container = rootLayout.findViewWithTag<FrameLayout>("alternatives_container") ?: return
        if (container.childCount > 0) {
            container.removeAllViews()
            return
        }

        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val totalSpace = if (isLandscape) rootHeight else rootWidth
        val itemSize = totalSpace / 11
        val density = resources.displayMetrics.density
        val estimatedTextSize = (itemSize * 0.45 / density).toFloat().coerceIn(12f, 22f)

        val mainLayout = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.argb(255, 55, 55, 55))
            setPadding(6, 6, 6, 6)
            elevation = 30f
            setOnClickListener { }
        }

        val scrollView = if (isLandscape) ScrollView(this) else HorizontalScrollView(this)
        scrollView.apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 10f)
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 10f)
            }
        }

        val candidateList = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }

        val alts = activeAllAlternatives.getOrNull(currentIdx) ?: emptyList()
        val top15 = alts.take(15)

        var selectedView: View? = null
        top15.forEach { (altChar, _) ->
            val textView = TextView(this).apply {
                text = altChar.toString()
                setTextColor(android.graphics.Color.WHITE)
                textSize = estimatedTextSize
                gravity = Gravity.CENTER
                
                if (altChar == activeAllChars[currentIdx]) {
                    setBackgroundColor(android.graphics.Color.YELLOW)
                    setTextColor(android.graphics.Color.BLACK)
                    selectedView = this
                } else {
                    setBackgroundColor(android.graphics.Color.argb(255, 85, 85, 85))
                }

                setOnClickListener {
                    replaceCharacter(currentIdx, altChar, rootLayout)
                }
            }
            val lp = LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) }
            candidateList.addView(textView, lp)
        }
        scrollView.addView(candidateList)
        mainLayout.addView(scrollView)

        // Scroll to selected if it exists
        selectedView?.let { view ->
            scrollView.post {
                if (isLandscape) {
                    scrollView.scrollTo(0, view.top)
                } else {
                    (scrollView as HorizontalScrollView).scrollTo(view.left, 0)
                }
            }
        }

        // Fixed Stub for manual input (the 11th visible slot)
        val stubView = TextView(this).apply {
            text = "⌨"
            setTextColor(android.graphics.Color.GRAY)
            textSize = estimatedTextSize
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(255, 40, 40, 40))
            setOnClickListener {
                Toast.makeText(this@OcrAccessibilityService, "Manual input coming soon", Toast.LENGTH_SHORT).show()
            }
        }
        val stubLp = if (isLandscape) {
            LinearLayout.LayoutParams(itemSize, 0, 1f).apply { setMargins(2, 2, 2, 2) }
        } else {
            LinearLayout.LayoutParams(0, itemSize, 1f).apply { setMargins(2, 2, 2, 2) }
        }
        mainLayout.addView(stubView, stubLp)

        container.addView(mainLayout)
    }

    private fun replaceCharacter(index: Int, newChar: Char, rootLayout: FrameLayout) {
        activeAllChars[index] = newChar
        
        // Update the LineResult object
        val info = activeCharInfos[index]
        val line = activeLineResults[info.lineIdx]
        val charArray = line.text.toCharArray()
        charArray[info.charIdxInLine] = newChar
        line.text = String(charArray)

        // Update the UI TextView
        if (index < textViews.size) {
            textViews[index].text = newChar.toString()
        }

        // Re-run lookup
        performLookup(index, rootLayout)
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

    private fun renderDefinition(container: LinearLayout, data: Any?, level: Int = 0, forceBullet: Boolean = false) {
        when (data) {
            is String -> {
                container.addView(TextView(this).apply {
                    val shouldBullet = level == 0 || forceBullet
                    text = if (shouldBullet) "• $data" else data
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    setPadding(20 * level, 0, 0, 0)
                })
            }
            is List<*> -> {
                for (item in data) {
                    renderDefinition(container, item, level, forceBullet)
                }
            }
            is Map<*, *> -> {
                // Handle Yomitan Structured Content
                val tag = data["tag"] as? String
                val isListItem = tag == "li"
                val content = data["content"] ?: data["list"]
                if (content != null) {
                    // Carry over the bullet requirement if we are at the top level of a sense
                    // or if this specific element is a list item.
                    renderDefinition(container, content, level + 1, forceBullet || level == 0 || isListItem)
                } else {
                    // KANJIDIC: sometimes just a map of fields
                    data.forEach { (key, value) ->
                         // Filter out keys we might not want to display
                         if (key !in listOf("ucs", "strokes", "skip")) {
                             container.addView(TextView(this).apply {
                                text = "$key: $value"
                                setTextColor(android.graphics.Color.LTGRAY)
                                textSize = 14f
                             })
                         }
                    }
                }
            }
        }
    }

    private fun hideScreenshotOverlay() {
        val root = screenshotOverlay ?: return
        val fv = floatingView ?: return
        
        // 1. Remove floating button from its current parent (the overlay)
        (fv.parent as? android.view.ViewGroup)?.removeView(fv)
        
        // 2. Remove the overlay from WindowManager
        if (root.isAttachedToWindow) {
            try {
                windowManager?.removeViewImmediate(root)
            } catch (e: Exception) {
                Log.e("OcrAccessibilityService", "Error removing screenshot overlay", e)
            }
        }
        screenshotOverlay = null
        
        // 3. Restore the floating button to the WindowManager
        fv.visibility = View.VISIBLE
        
        try {
            windowManager?.updateViewLayout(fv, floatingParams)
        } catch (e: Exception) {
            Log.e("OcrAccessibilityService", "Error restoring floating button to WindowManager", e)
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
        
        hideScreenshotOverlay()
        
        floatingView?.let { 
            if (it.isAttachedToWindow) {
                windowManager?.removeView(it)
            }
        }
        ocrEngine.close()
    }
}
