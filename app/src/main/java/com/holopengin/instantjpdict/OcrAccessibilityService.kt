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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
    private var screenshotBitmap: Bitmap? = null
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
            if (screenshotOverlay != null) {
                hideScreenshotOverlay()
                return@setOnClickListener
            }
            
            floatingView?.visibility = View.GONE
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
        screenshotBitmap = bitmap

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
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            setOnClickListener {
                if (findViewWithTag<View>("manual_input_blocker") != null) {
                    closeManualInput(this)
                    return@setOnClickListener
                }
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

                    drawResults(rootLayout, results)
                    
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

        val marginsLayer = FrameLayout(this)
        val clicksLayer = FrameLayout(this)
        
        rootLayout.addView(marginsLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootLayout.addView(clicksLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        
        var charIndex = 0
        val margin = 50

        for (line in results) {
            for (i in line.charBoxes.indices) {
                val box = line.charBoxes[i]
                val char = line.text.getOrNull(i)?.toString() ?: ""
                val currentIdx = charIndex++
                
                val marginBlocker = View(this).apply {
                    isClickable = true
                    setOnClickListener { }
                }
                val marginParams = FrameLayout.LayoutParams(box.width() + 2 * margin, box.height() + 2 * margin).apply {
                    leftMargin = box.left - margin
                    topMargin = box.top - margin
                }
                marginsLayer.addView(marginBlocker, marginParams)

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
                    val fontSize = if (line.isVertical) box.width().toFloat() else box.height().toFloat()
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSize)
                    setPadding(0, 0, 0, 0)
                    includeFontPadding = false
                    isClickable = false
                    
                    if (line.isVertical) {
                        textLocale = java.util.Locale.JAPANESE
                        fontFeatureSettings = "'vert' 1"
                    }
                }
                textView.tag = currentIdx
                charContainer.addView(textView, FrameLayout.LayoutParams(box.width(), FrameLayout.LayoutParams.MATCH_PARENT))
                textViews.add(textView)

                val boxView = View(this).apply {
                    background = null
                    isClickable = true
                }
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

    private fun performLookup(currentIdx: Int, rootLayout: FrameLayout, skipCenter: Boolean = false) {
        currentTappedIdx = currentIdx
        val tappedBox = activeCharInfos[currentIdx].box

        activeAllAlternatives.getOrNull(currentIdx)?.let { alternatives ->
            val logMsg = alternatives.joinToString(", ") { (char, score) ->
                "$char (${String.format(java.util.Locale.US, "%.8f", score)})"
            }
            Log.i("OcrAccessibilityService", "Top 15 candidates: $logMsg")
        }

        resetHighlights()

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

            val allTermsToSearch = mutableSetOf<String>()
            val candidatesByLength = mutableListOf<Pair<Int, List<Pair<String, List<String>?>>>>()

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
                variants.forEach { lengthCandidates.add(it to null); allTermsToSearch.add(it) }
                deinflections.forEach { if (it.term != queryText) { lengthCandidates.add(it.term to it.type); allTermsToSearch.add(it.term) } }
                candidatesByLength.add(len to lengthCandidates)
            }

            val dbResults = withContext(Dispatchers.IO) {
                db.dictionaryDao().findByTexts(allTermsToSearch.toList())
            }

            val resultsByTerm = mutableMapOf<String, MutableList<com.holopengin.instantjpdict.data.DictionaryEntry>>()
            dbResults.forEach { entry ->
                if (entry.kanji in allTermsToSearch) resultsByTerm.getOrPut(entry.kanji) { mutableListOf() }.add(entry)
                if (entry.reading in allTermsToSearch) resultsByTerm.getOrPut(entry.reading) { mutableListOf() }.add(entry)
            }

            for ((len, candidates) in candidatesByLength) {
                var foundInThisLen = false
                for ((term, requiredTypes) in candidates) {
                    val termEntries = resultsByTerm[term] ?: continue
                    val filteredResults = if (requiredTypes == null) {
                        val queryText = JapaneseUtil.normalize(followingText.substring(0, len))
                        termEntries.filter { entry ->
                            val isKanjiEntry = entry.onyomi != null || entry.kunyomi != null
                            !isKanjiEntry || entry.kanji == queryText
                        }
                    } else {
                        termEntries.filter { entry ->
                            val entryTags = entry.rules.split(" ")
                            requiredTypes.isEmpty() || requiredTypes.any { it in entryTags } ||
                                    (entryTags.any { it.startsWith("v") } && requiredTypes.any { it.startsWith("v") })
                        }
                    }
                    if (filteredResults.isNotEmpty()) {
                        allMatches.add(term to filteredResults.distinctBy { it.id })
                        foundInThisLen = true
                    }
                }
                if (foundInThisLen && maxMatchedLen == 0) maxMatchedLen = len
            }

            val uniqueMatches = allMatches.distinctBy { it.first }
            withContext(Dispatchers.Main) {
                for (i in 0 until maxMatchedLen) {
                    val targetIdx = currentIdx + i
                    if (targetIdx < textViews.size) {
                        textViews[targetIdx].setTextColor(android.graphics.Color.YELLOW)
                        textViews[targetIdx].typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                }
                showResultsUi(rootLayout, uniqueMatches, tappedBox, skipCenter)
            }
        }
    }

    private fun resetHighlights() {
        textViews.forEach { 
            it.setTextColor(android.graphics.Color.RED)
            it.typeface = android.graphics.Typeface.DEFAULT
        }
    }

    private fun showResultsUi(rootLayout: FrameLayout, matches: List<Pair<String, List<com.holopengin.instantjpdict.data.DictionaryEntry>>>, tappedBox: Rect, skipCenter: Boolean = false) {
        val existingRoot = rootLayout.findViewWithTag<LinearLayout>("correction_ui_root")
        
        if (existingRoot != null) {
            val dictionaryContainer = existingRoot.findViewWithTag<LinearLayout>("dictionary_content_container")
            if (dictionaryContainer != null) updateDictionaryPanel(dictionaryContainer, matches)
            
            val neighborPanel = existingRoot.findViewWithTag<LinearLayout>("neighbor_scroll_panel")
            if (neighborPanel != null) updateNeighborHighlights(neighborPanel)
            
            if (!skipCenter) {
                val neighborScrollView = existingRoot.findViewWithTag<View>("neighbor_scroll_view")
                if (neighborScrollView != null) centerNeighborScrollView(neighborScrollView, currentTappedIdx)
            }
            return
        }

        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val isLandscape = rootWidth > rootHeight
        val panelWidth = if (isLandscape) (rootWidth * 0.4).toInt() else FrameLayout.LayoutParams.MATCH_PARENT
        val panelHeight = if (isLandscape) FrameLayout.LayoutParams.MATCH_PARENT else (rootHeight * 0.4).toInt()

        val dictionaryPanel = LinearLayout(this).apply {
            tag = "dictionary_content_container"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.argb(245, 25, 25, 25))
            setPadding(40, 40, 40, 40)
            elevation = 20f
            setOnClickListener { }
        }
        updateDictionaryPanel(dictionaryPanel, matches)

        val mainContainer = LinearLayout(this).apply {
            tag = "correction_ui_root"
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (isLandscape) {
                if (lastLandscapeGravity == Gravity.END) {
                    if (tappedBox.right > rootWidth - panelWidth) lastLandscapeGravity = Gravity.START
                } else {
                    if (tappedBox.left < panelWidth) lastLandscapeGravity = Gravity.END
                }
                lastLandscapeGravity
            } else {
                if (lastPortraitGravity == Gravity.BOTTOM) {
                    if (tappedBox.bottom > rootHeight - panelHeight) lastPortraitGravity = Gravity.TOP
                } else {
                    if (tappedBox.top < panelHeight) lastPortraitGravity = Gravity.BOTTOM
                }
                lastPortraitGravity
            }
        }

        val correctionPanel = createCorrectionPanel(currentTappedIdx, isLandscape, rootLayout, skipCenter)
        val alternativesPanelContainer = FrameLayout(this).apply {
            tag = "alternatives_container"
            layoutParams = if (isLandscape) LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        if (isLandscape) {
            if (lastLandscapeGravity == Gravity.END) {
                mainContainer.addView(alternativesPanelContainer); mainContainer.addView(correctionPanel); mainContainer.addView(dictionaryPanel, LinearLayout.LayoutParams(panelWidth, panelHeight))
            } else {
                mainContainer.addView(dictionaryPanel, LinearLayout.LayoutParams(panelWidth, panelHeight)); mainContainer.addView(correctionPanel); mainContainer.addView(alternativesPanelContainer)
            }
        } else {
            if (lastPortraitGravity == Gravity.BOTTOM) {
                mainContainer.addView(alternativesPanelContainer); mainContainer.addView(correctionPanel); mainContainer.addView(dictionaryPanel, LinearLayout.LayoutParams(panelWidth, panelHeight))
            } else {
                mainContainer.addView(dictionaryPanel, LinearLayout.LayoutParams(panelWidth, panelHeight)); mainContainer.addView(correctionPanel); mainContainer.addView(alternativesPanelContainer)
            }
        }

        val rootParams = FrameLayout.LayoutParams(
            if (isLandscape) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT,
            if (isLandscape) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = mainContainer.gravity }

        rootLayout.addView(mainContainer, rootParams)
    }

    private fun updateDictionaryPanel(container: LinearLayout, matches: List<Pair<String, List<com.holopengin.instantjpdict.data.DictionaryEntry>>>) {
        container.removeAllViews()
        if (matches.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No results found"
                setTextColor(android.graphics.Color.GRAY)
                gravity = Gravity.CENTER
                textSize = 16f
                setPadding(0, 150, 0, 0)
            })
            return
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 0, 10, 150)
        }

        for ((_, entries) in matches) {
            val termSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 50) }
            val entriesByReading = entries.groupBy { it.reading }

            for ((reading, readingEntries) in entriesByReading) {
                val firstEntry = readingEntries.first()
                val isKanjiEntry = firstEntry.onyomi != null || firstEntry.kunyomi != null
                val allGlobalTags = mutableSetOf<String>()
                val allInfoText = mutableSetOf<String>()

                for (e in readingEntries) {
                    e.jlpt?.takeIf { it.isNotEmpty() }?.let { allInfoText.add("jlpt: N$it") }
                    "grade:([^\\s]+)".toRegex().find(e.rules)?.groupValues?.get(1)?.let { allInfoText.add("grade: $it") }
                    val segments = e.rules.split(" | ")
                    val t1 = segments.getOrNull(0)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                    val rs = segments.getOrNull(1)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                    val t2 = segments.getOrNull(2)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                    (t1 + rs + t2).forEach { tag -> if (tag.toIntOrNull() == null && !tag.startsWith("grade:")) allGlobalTags.add(tag) }
                }

                val headwordList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 10) }
                val kanjiVariants = readingEntries.map { it.kanji }.distinct()

                if (isKanjiEntry) {
                    kanjiVariants.forEach { kanji ->
                        val entry = readingEntries.find { it.kanji == kanji } ?: firstEntry
                        val kanjiHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 5, 0, 5) }
                        kanjiHeader.addView(TextView(this).apply { text = kanji; setTextColor(android.graphics.Color.CYAN); textSize = 48f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, 0, 30, 0) })
                        val readingStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        entry.onyomi?.takeIf { it.isNotEmpty() }?.let { readingStack.addView(TextView(this).apply { text = "ON: ${it.replace(" ", "、")}"; setTextColor(android.graphics.Color.LTGRAY); textSize = 14f }) }
                        entry.kunyomi?.takeIf { it.isNotEmpty() }?.let { readingStack.addView(TextView(this).apply { text = "KUN: ${it.replace(" ", "、")}"; setTextColor(android.graphics.Color.LTGRAY); textSize = 14f }) }
                        kanjiHeader.addView(readingStack)
                        headwordList.addView(kanjiHeader)
                    }
                } else {
                    val headwordScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
                    val flow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 5, 0, 5); gravity = Gravity.BOTTOM }
                    kanjiVariants.forEachIndexed { i, kanji ->
                        flow.addView(createRubyView(kanji, reading))
                        if (i < kanjiVariants.size - 1) {
                            flow.addView(TextView(this).apply { text = "、"; setTextColor(android.graphics.Color.GRAY); textSize = 24f; gravity = Gravity.BOTTOM; includeFontPadding = false })
                        }
                    }
                    headwordScroll.addView(flow); headwordList.addView(headwordScroll)
                }
                termSection.addView(headwordList)
                if (allGlobalTags.isNotEmpty()) addTagsToContainer(termSection, allGlobalTags.toList(), "pos")
                if (allInfoText.isNotEmpty()) renderFrequency(termSection, allInfoText.joinToString(", "))

                for (e in readingEntries) {
                    try {
                        val definitions = gson.fromJson<Any>(e.definitions, Any::class.java)
                        if (definitions is List<*>) {
                            val segments = e.rules.split(" | ")
                            val t1 = segments.getOrNull(0)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                            val t2 = segments.getOrNull(2)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                            val senseToTags = mutableMapOf<Int, MutableList<String>>()
                            val unnumberedTags = mutableListOf<String>()
                            fun parseTags(tagList: List<String>) {
                                var curr: Int? = null
                                for (tag in tagList) { val n = tag.toIntOrNull(); if (n != null) curr = n else { if (curr != null) senseToTags.getOrPut(curr) { mutableListOf() }.add(tag) else unnumberedTags.add(tag) } }
                            }
                            parseTags(t1); parseTags(t2)
                            definitions.forEachIndexed { index, sense ->
                                val senseNum = index + 1
                                val senseBox = LinearLayout(this@OcrAccessibilityService).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 4, 0, 4) }
                                val tagsForSense = (senseToTags[senseNum] ?: mutableListOf<String>()) + unnumberedTags
                                val filteredTags = tagsForSense.distinct().filter { it.toIntOrNull() == null && !it.startsWith("grade:") }
                                if (filteredTags.isNotEmpty()) {
                                    val tagFlow = LinearLayout(this@OcrAccessibilityService).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 4) }
                                    filteredTags.forEach { tag -> tagFlow.addView(TextView(this@OcrAccessibilityService).apply { text = formatTag(tag); setTextColor(android.graphics.Color.parseColor("#999999")); textSize = 10f; setPadding(0, 0, 15, 0); typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC) }) }
                                    senseBox.addView(tagFlow)
                                }
                                renderDefinition(senseBox, sense, 0, forceBullet = definitions.size > 1, senseIndex = senseNum)
                                termSection.addView(senseBox)
                            }
                        } else renderDefinition(termSection, definitions)
                    } catch (ex: Exception) {
                        termSection.addView(TextView(this).apply { text = e.definitions; setTextColor(android.graphics.Color.WHITE); textSize = 15f; setPadding(40, 10, 0, 10) })
                    }
                }
                termSection.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 30; bottomMargin = 10 }; setBackgroundColor(android.graphics.Color.DKGRAY); alpha = 0.2f })
            }
            scrollContent.addView(termSection)
        }
        scrollView.addView(scrollContent); container.addView(scrollView)
    }

    private fun updateNeighborHighlights(panel: LinearLayout) {
        for (i in 0 until panel.childCount) {
            val view = panel.getChildAt(i) as? TextView ?: continue
            if (i == currentTappedIdx) {
                view.setBackgroundColor(android.graphics.Color.YELLOW)
                view.setTextColor(android.graphics.Color.BLACK)
            } else {
                view.setBackgroundColor(android.graphics.Color.argb(255, 65, 65, 65))
                view.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }

    private fun centerNeighborScrollView(scrollView: View, currentIdx: Int) {
        val panel = scrollView.findViewWithTag<LinearLayout>("neighbor_scroll_panel") ?: return
        val targetView = panel.getChildAt(currentIdx) ?: return
        scrollView.post {
            if (scrollView is ScrollView) scrollView.scrollTo(0, targetView.top - (scrollView.height / 2) + (targetView.height / 2))
            else if (scrollView is HorizontalScrollView) scrollView.scrollTo(targetView.left - (scrollView.width / 2) + (targetView.width / 2), 0)
        }
    }

    private fun createCorrectionPanel(currentIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout, skipCenter: Boolean = false): View {
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)

        val outerContainer = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.argb(255, 45, 45, 45)); elevation = 25f }
        val scrollView = if (isLandscape) ScrollView(this) else HorizontalScrollView(this)
        scrollView.apply {
            tag = "neighbor_scroll_view"; isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            layoutParams = if (isLandscape) FrameLayout.LayoutParams(itemSize + 12, FrameLayout.LayoutParams.MATCH_PARENT) else FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, itemSize + 12)
        }

        val panel = LinearLayout(this).apply { tag = "neighbor_scroll_panel"; orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(6, 6, 6, 6) }
        val itemLp = LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) }

        for (i in activeAllChars.indices) {
            val textView = TextView(this).apply {
                tag = "neighbor_char_$i"; text = activeAllChars[i].toString(); setTextColor(android.graphics.Color.WHITE); textSize = estimatedTextSize; gravity = Gravity.CENTER
                if (i == currentIdx) { setBackgroundColor(android.graphics.Color.YELLOW); setTextColor(android.graphics.Color.BLACK) }
                else setBackgroundColor(android.graphics.Color.argb(255, 65, 65, 65))
                
                if (isLandscape) {
                    textLocale = java.util.Locale.JAPANESE
                    fontFeatureSettings = "'vert' 1"
                }

                setOnClickListener {
                    if (currentTappedIdx == i) toggleAlternativesPanel(rootLayout, i, isLandscape)
                    else {
                        val oldIdx = currentTappedIdx; currentTappedIdx = i
                        panel.findViewWithTag<View>("neighbor_char_$oldIdx")?.let { it.setBackgroundColor(android.graphics.Color.argb(255, 65, 65, 65)); (it as TextView).setTextColor(android.graphics.Color.WHITE) }
                        this.setBackgroundColor(android.graphics.Color.YELLOW); this.setTextColor(android.graphics.Color.BLACK)
                        
                        // If alternatives are open, update them for the new character
                        val altContainer = rootLayout.findViewWithTag<FrameLayout>("alternatives_container")
                        if (altContainer != null && altContainer.childCount > 0) {
                            // Don't recreate the whole container, just refresh its contents
                            updateAlternativesPanelContent(altContainer, i, isLandscape, rootLayout)
                        }
                        
                        performLookup(i, rootLayout, skipCenter = true)
                    }
                }
            }
            panel.addView(textView, itemLp)
        }
        scrollView.addView(panel); outerContainer.addView(scrollView)
        if (!skipCenter) centerNeighborScrollView(scrollView, currentIdx)
        return outerContainer
    }

    private fun toggleAlternativesPanel(rootLayout: FrameLayout, currentIdx: Int, isLandscape: Boolean) {
        val container = rootLayout.findViewWithTag<FrameLayout>("alternatives_container") ?: return
        if (container.childCount > 0) { container.removeAllViews(); return }
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)

        val mainLayout = LinearLayout(this).apply { orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setBackgroundColor(android.graphics.Color.argb(255, 55, 55, 55)); setPadding(6, 6, 6, 6); elevation = 30f; setOnClickListener { } }
        val scrollView = if (isLandscape) ScrollView(this) else HorizontalScrollView(this)
        scrollView.apply {
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            layoutParams = if (isLandscape) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 10f) else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 10f)
        }
        val candidateList = LinearLayout(this).apply { tag = "candidate_list_panel"; orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        refreshCandidateList(candidateList, currentIdx, isLandscape, rootLayout)
        scrollView.addView(candidateList); mainLayout.addView(scrollView)
        
        // Find and scroll to selected
        scrollView.post {
            var selectedView: View? = null
            for (i in 0 until candidateList.childCount) {
                val v = candidateList.getChildAt(i) as? TextView ?: continue
                if (v.text.toString() == activeAllChars[currentIdx].toString()) {
                    selectedView = v; break
                }
            }
            selectedView?.let { view -> if (isLandscape) scrollView.scrollTo(0, view.top) else (scrollView as HorizontalScrollView).scrollTo(view.left, 0) }
        }

        val stubView = TextView(this).apply { tag = "manual_input_stub"; text = "⌨"; setTextColor(android.graphics.Color.GRAY); textSize = estimatedTextSize; gravity = Gravity.CENTER; setBackgroundColor(android.graphics.Color.argb(255, 40, 40, 40)); setOnClickListener { showManualInput(currentIdx, rootLayout) } }
        mainLayout.addView(stubView, if (isLandscape) LinearLayout.LayoutParams(itemSize, 0, 1f).apply { setMargins(2, 2, 2, 2) } else LinearLayout.LayoutParams(0, itemSize, 1f).apply { setMargins(2, 2, 2, 2) })
        container.addView(mainLayout)
    }

    private fun refreshCandidateList(candidateList: LinearLayout, currentIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout) {
        candidateList.removeAllViews()
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)

        val alts = activeAllAlternatives.getOrNull(currentIdx) ?: emptyList()
        alts.take(15).forEach { (altChar, _) ->
            val textView = TextView(this).apply {
                text = altChar.toString(); setTextColor(android.graphics.Color.WHITE); textSize = estimatedTextSize; gravity = Gravity.CENTER
                if (altChar == activeAllChars[currentIdx]) { setBackgroundColor(android.graphics.Color.YELLOW); setTextColor(android.graphics.Color.BLACK) }
                else setBackgroundColor(android.graphics.Color.argb(255, 85, 85, 85))
                
                if (isLandscape) {
                    textLocale = java.util.Locale.JAPANESE
                    fontFeatureSettings = "'vert' 1"
                }

                setOnClickListener { replaceCharacter(currentIdx, altChar, rootLayout) }
            }
            candidateList.addView(textView, LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) })
        }
    }

    private fun updateAlternativesPanelContent(container: FrameLayout, currentIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout) {
        val candidateList = container.findViewWithTag<LinearLayout>("candidate_list_panel") ?: return
        refreshCandidateList(candidateList, currentIdx, isLandscape, rootLayout)
        
        // Update manual input stub too
        val stub = container.findViewWithTag<TextView>("manual_input_stub")
        stub?.setOnClickListener { showManualInput(currentIdx, rootLayout) }

        // Optional: Scroll to new selection if switching neighbors
        val scrollView = candidateList.parent as? View ?: return
        scrollView.post {
            var selectedView: View? = null
            for (i in 0 until candidateList.childCount) {
                val v = candidateList.getChildAt(i) as? TextView ?: continue
                if (v.text.toString() == activeAllChars[currentIdx].toString()) {
                    selectedView = v; break
                }
            }
            selectedView?.let { view ->
                if (scrollView is ScrollView) {
                    val scrollY = view.top - (scrollView.height / 2) + (view.height / 2)
                    scrollView.smoothScrollTo(0, scrollY)
                } else if (scrollView is HorizontalScrollView) {
                    val scrollX = view.left - (scrollView.width / 2) + (view.width / 2)
                    scrollView.smoothScrollTo(scrollX, 0)
                }
            }
        }
    }

    private fun updateAlternativesHighlight(container: FrameLayout, currentChar: Char) {
        val candidateList = container.findViewWithTag<LinearLayout>("candidate_list_panel") ?: return
        for (i in 0 until candidateList.childCount) {
            val v = candidateList.getChildAt(i) as? TextView ?: continue
            if (v.text.toString() == currentChar.toString()) {
                v.setBackgroundColor(android.graphics.Color.YELLOW)
                v.setTextColor(android.graphics.Color.BLACK)
            } else {
                v.setBackgroundColor(android.graphics.Color.argb(255, 85, 85, 85))
                v.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }

    private fun replaceCharacter(index: Int, newChar: Char, rootLayout: FrameLayout) {
        activeAllChars[index] = newChar
        val info = activeCharInfos[index]
        val line = activeLineResults[info.lineIdx]
        val charArray = line.text.toCharArray(); charArray[info.charIdxInLine] = newChar; line.text = String(charArray)
        if (index < textViews.size) textViews[index].text = newChar.toString()
        
        // Update browser view too
        val browserPanel = rootLayout.findViewWithTag<LinearLayout>("neighbor_scroll_panel")
        browserPanel?.findViewWithTag<TextView>("neighbor_char_$index")?.text = newChar.toString()

        // Update alternatives panel highlight instead of recreating
        val altContainer = rootLayout.findViewWithTag<FrameLayout>("alternatives_container")
        if (altContainer != null && altContainer.childCount > 0) {
            updateAlternativesHighlight(altContainer, newChar)
        }

        performLookup(index, rootLayout, skipCenter = true)
    }

    private fun showManualInput(index: Int, rootLayout: FrameLayout) {
        val bitmap = screenshotBitmap ?: return
        val box = activeCharInfos[index].box
        val padding = (box.height() * 0.5).toInt()
        val cropRect = Rect((box.left - padding).coerceAtLeast(0), (box.top - padding).coerceAtLeast(0), (box.right + padding).coerceAtMost(bitmap.width), (box.bottom + padding).coerceAtMost(bitmap.height))
        val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        val blocker = FrameLayout(this).apply { tag = "manual_input_blocker"; setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0)); setOnClickListener { closeManualInput(rootLayout) } }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.argb(255, 35, 35, 35)); setPadding(60, 60, 60, 60); gravity = Gravity.CENTER_HORIZONTAL; elevation = 50f; setOnClickListener { } }
        panel.addView(android.widget.ImageView(this).apply { setImageBitmap(cropped); val size = (resources.displayMetrics.density * 120).toInt(); layoutParams = LinearLayout.LayoutParams(size, size); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER })
        panel.addView(TextView(this).apply { text = "Enter character manually"; setTextColor(android.graphics.Color.GRAY); textSize = 14f; setPadding(0, 30, 0, 10) })
        val editText = EditText(this).apply { setTextColor(android.graphics.Color.WHITE); textSize = 36f; gravity = Gravity.CENTER; maxLines = 1; imeOptions = EditorInfo.IME_ACTION_DONE; inputType = android.text.InputType.TYPE_CLASS_TEXT; background.setTint(android.graphics.Color.CYAN) }
        panel.addView(editText, LinearLayout.LayoutParams(250, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(Button(this).apply { text = "Confirm"; setOnClickListener { val text = editText.text.toString(); if (text.isNotEmpty()) { replaceCharacter(index, text[0], rootLayout); closeManualInput(rootLayout) } } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })
        blocker.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val params = screenshotOverlay?.layoutParams as? WindowManager.LayoutParams
        if (params != null) { params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv(); params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE; windowManager?.updateViewLayout(screenshotOverlay, params) }
        rootLayout.addView(blocker, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        editText.requestFocus()
        editText.postDelayed({ (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) }, 100)
        editText.setOnEditorActionListener { _, actionId, event -> if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) { val text = editText.text.toString(); if (text.isNotEmpty()) { replaceCharacter(index, text[0], rootLayout); closeManualInput(rootLayout) }; true } else false }
    }

    private fun closeManualInput(rootLayout: FrameLayout) {
        val blocker = rootLayout.findViewWithTag<View>("manual_input_blocker") ?: return
        rootLayout.removeView(blocker)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(rootLayout.windowToken, 0)
        val params = screenshotOverlay?.layoutParams as? WindowManager.LayoutParams
        if (params != null) { params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windowManager?.updateViewLayout(screenshotOverlay, params) }
    }

    private fun createTagView(tag: String, category: String = "general"): View {
        val color = when { category == "pos" || tag.startsWith("v") || tag == "adj-i" || tag == "adj-na" -> android.graphics.Color.parseColor("#3a5a7a"); tag == "n" || tag == "adv" || tag == "pn" -> android.graphics.Color.parseColor("#3a7a5a"); category == "meta" || tag.startsWith("jlpt") || tag.startsWith("grade") || tag == "★" -> android.graphics.Color.parseColor("#7a3a3a"); else -> android.graphics.Color.parseColor("#444444") }
        return TextView(this).apply { text = formatTag(tag); setTextColor(android.graphics.Color.WHITE); textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(12, 2, 12, 2); background = GradientDrawable().apply { setColor(color); cornerRadius = 6f }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 12, 0) } }
    }

    private fun addTagsToContainer(container: LinearLayout, tags: List<String>, category: String = "general") {
        if (tags.isEmpty()) return
        val tagContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(20, 8, 20, 8) }
        tags.distinct().forEach { tag -> tagContainer.addView(createTagView(tag, category)) }
        val hScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }; hScroll.addView(tagContainer); container.addView(hScroll)
    }

    private fun renderFrequency(container: LinearLayout, frequencyText: String) {
        val flow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(20, 4, 20, 4) }
        frequencyText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { chip -> flow.addView(TextView(this).apply { text = chip; setTextColor(android.graphics.Color.parseColor("#888888")); textSize = 9f; setPadding(10, 2, 10, 2); background = GradientDrawable().apply { setStroke(1, android.graphics.Color.parseColor("#333333")); cornerRadius = 4f }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 10, 0) } }) }
        container.addView(flow)
    }

    private fun formatTag(tag: String): String = when(tag) { "v1" -> "1-dan"; "v5", "v5k", "v5s", "v5t", "v5n", "v5m", "v5r", "v5w", "v5g", "v5z", "v5b" -> "5-dan"; "vs", "vs-i", "vs-s" -> "suru"; "vi" -> "intrans"; "vt" -> "trans"; "adj-i" -> "adj-i"; "adj-na" -> "adj-na"; "n" -> "noun"; "adv" -> "adv"; "pn" -> "pronoun"; "p" -> "particle"; "exp" -> "phrase"; "aux" -> "aux"; "ctr" -> "count"; "conj" -> "conj"; "num" -> "num"; "int" -> "intj"; "suf" -> "suffix"; "pref" -> "prefix"; "arch" -> "archaic"; "dated" -> "dated"; "hist" -> "hist"; "sl" -> "slang"; "col" -> "colloq"; "obs" -> "obsolete"; else -> tag }

    private fun createRubyView(term: String, reading: String, isMini: Boolean = false): View {
        if (term == reading) {
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            if (!isMini) container.addView(TextView(this).apply { text = " "; textSize = 13f; setPadding(0, 0, 0, 0); includeFontPadding = false })
            container.addView(TextView(this).apply { text = term; setTextColor(android.graphics.Color.CYAN); textSize = if (isMini) 20f else 32f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, 0); includeFontPadding = false })
            return container
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        var prefixLen = 0; while (prefixLen < term.length && prefixLen < reading.length && term[prefixLen] == reading[prefixLen]) prefixLen++
        var suffixLen = 0; while (suffixLen < (term.length - prefixLen) && suffixLen < (reading.length - prefixLen) && term[term.length - 1 - suffixLen] == reading[reading.length - 1 - suffixLen]) suffixLen++
        val prefix = term.substring(0, prefixLen); val termMid = term.substring(prefixLen, term.length - suffixLen); val readingMid = reading.substring(prefixLen, reading.length - suffixLen); val suffix = term.substring(term.length - suffixLen)
        if (prefix.isNotEmpty()) container.addView(TextView(this).apply { text = prefix; setTextColor(android.graphics.Color.CYAN); textSize = if (isMini) 20f else 32f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, 0); includeFontPadding = false })
        if (termMid.isNotEmpty()) {
            val rubyItem = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            rubyItem.addView(TextView(this).apply { text = readingMid; setTextColor(android.graphics.Color.LTGRAY); textSize = if (isMini) 10f else 13f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0); includeFontPadding = false })
            rubyItem.addView(TextView(this).apply { text = termMid; setTextColor(android.graphics.Color.CYAN); textSize = if (isMini) 20f else 32f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0); includeFontPadding = false })
            container.addView(rubyItem)
        }
        if (suffix.isNotEmpty()) container.addView(TextView(this).apply { text = suffix; setTextColor(android.graphics.Color.CYAN); textSize = if (isMini) 20f else 32f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, 0); includeFontPadding = false })
        return container
    }

    private fun renderDefinition(container: LinearLayout, data: Any?, level: Int = 0, forceBullet: Boolean = false, senseIndex: Int? = null) {
        when (data) {
            is String -> container.addView(TextView(this).apply { val prefix = when { senseIndex != null -> listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩").getOrNull(senseIndex - 1) ?: "$senseIndex." ; forceBullet || level > 0 -> "•"; else -> "" }; text = if (prefix.isNotEmpty()) "$prefix $data" else data; setTextColor(android.graphics.Color.WHITE); textSize = 15f; setPadding(25 * level, 2, 0, 2); setLineSpacing(0f, 1.1f) })
            is List<*> -> data.forEachIndexed { i, item -> renderDefinition(container, item, level, forceBullet = forceBullet || data.size > 1, senseIndex = if (i == 0) senseIndex else null) }
            is Map<*, *> -> {
                val tag = data["tag"] as? String; val scClass = data["data-sc-class"] as? String; val scContent = data["data-sc-content"] as? String; val content = data["content"] ?: data["list"]
                if (tag == "ruby") { (content as? List<*>)?.let { if (it.size >= 2) { container.addView(createRubyView(it[0].toString(), (it[1] as? Map<*, *>)?.get("content")?.toString() ?: "", isMini = true)); return } } }
                if (scClass == "example-sentence" || scContent?.startsWith("example-sentence") == true) {
                    val exampleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 0, 10); background = GradientDrawable().apply { setColor(android.graphics.Color.argb(15, 255, 255, 255)); cornerRadius = 8f }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10; bottomMargin = 10 } }
                    renderDefinition(exampleBox, content, 0); container.addView(exampleBox); return
                }
                if (content != null) renderDefinition(container, content, level + (if (tag == "li") 1 else 0), forceBullet = tag == "li")
                else data.forEach { (k, v) -> if (k !in listOf("tag", "content", "list", "data-sc-type", "data-sc-class", "data-sc-content", "ucs", "strokes", "skip")) container.addView(TextView(this).apply { text = "$k: $v"; setTextColor(android.graphics.Color.GRAY); textSize = 12f; setPadding(25 * level, 2, 0, 2) }) }
            }
        }
    }

    private fun hideScreenshotOverlay() {
        val root = screenshotOverlay ?: return
        (floatingView?.parent as? android.view.ViewGroup)?.removeView(floatingView)
        if (root.isAttachedToWindow) try { windowManager?.removeViewImmediate(root) } catch (e: Exception) { Log.e("OcrAccessibilityService", "Error removing overlay", e) }
        screenshotOverlay = null; screenshotBitmap = null; floatingView?.visibility = View.VISIBLE
        try { windowManager?.updateViewLayout(floatingView, floatingParams) } catch (e: Exception) { Log.e("OcrAccessibilityService", "Error restoring button", e) }
        boxViews.clear(); textViews.clear()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && screenshotOverlay != null && event.packageName == "com.android.systemui") hideScreenshotOverlay() }
    override fun onInterrupt() {}
    override fun onKeyEvent(event: KeyEvent?): Boolean { if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && screenshotOverlay != null) { val root = screenshotOverlay as FrameLayout; if (root.findViewWithTag<View>("manual_input_blocker") != null) closeManualInput(root) else hideScreenshotOverlay(); return true }; return super.onKeyEvent(event) }
    override fun onDestroy() { super.onDestroy(); try { unregisterReceiver(screenOffReceiver) } catch (e: Exception) {} ; hideScreenshotOverlay(); floatingView?.let { if (it.isAttachedToWindow) windowManager?.removeView(it) }; ocrEngine.close() }
}
