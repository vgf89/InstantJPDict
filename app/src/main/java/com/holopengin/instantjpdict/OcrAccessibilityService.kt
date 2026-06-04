package com.holopengin.instantjpdict

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.DecelerateInterpolator
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
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
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.gson.Gson
import com.holopengin.instantjpdict.util.Deinflector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
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
    private lateinit var ocrEngine: OcrEngine
    private val controller = OcrOverlayStateController()
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ocrJob: kotlinx.coroutines.Job? = null
    private var lookupJob: kotlinx.coroutines.Job? = null
    
    private var cursorView: View? = null
    private var scrollAnimator: ObjectAnimator? = null
    private var targetScrollY = 0
    private var viewportAnimator: AnimatorSet? = null
    private var neighborAnimator: ObjectAnimator? = null
    
    private val overlayControllerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    hideScreenshotOverlay()
                    if (intent.action == Intent.ACTION_SCREEN_OFF) {
                        floatingView?.visibility = View.GONE
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    floatingView?.visibility = View.VISIBLE
                }
            }
        }
    }
    
    private val textViews = mutableMapOf<Pair<Int, Int>, TextView>()
    
    private var repeatJob: kotlinx.coroutines.Job? = null
    private var currentRepeatingKeyCode = 0
    private val pressedKeys = mutableSetOf<Int>()
    private var lastGlobalTriggerTime = 0L

    private val borderDrawable by lazy {
        GradientDrawable().apply {
            setColor(android.graphics.Color.argb(100, 0, 0, 0))
            cornerRadius = 4f
        }
    }

    private fun createButtonBackground() = ContextCompat.getDrawable(this, R.drawable.logo)!!

    override fun onCreate() {
        super.onCreate()
        ocrEngine = OcrEngine(this)
        controller.deinflector = Deinflector(java.io.InputStreamReader(assets.open("deinflect.json")))
        controller.dictionaryProvider = AndroidDictionaryProvider(this)
        controller.gson = gson

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }
        
        ContextCompat.registerReceiver(
            this,
            overlayControllerReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (screenshotOverlay != null) {
            hideScreenshotOverlay()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
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
        ocrButton = CenteredButton(this).apply {
            background = createButtonBackground()
            val size = (44 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size)
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            gravity = Gravity.CENTER
            includeFontPadding = false
            alpha = 0.3f
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
                        val displayMetrics = resources.displayMetrics
                        val maxX = displayMetrics.widthPixels - v.width
                        val maxY = displayMetrics.heightPixels - v.height

                        val newX = (initialX + (event.rawX - initialTouchX).roundToInt()).coerceIn(0, maxX)
                        val newY = (initialY + (event.rawY - initialTouchY).roundToInt()).coerceIn(0, maxY)
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
        controller.resetState()

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.FILL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        var lastFocusX = 0f
        var lastFocusY = 0f
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isScaling = false
        var hasPanned = false

        val rootLayout = object : FrameLayout(this) {
            private fun isTouchOnView(tag: String, ev: MotionEvent): Boolean {
                val v = findViewWithTag<View>(tag) ?: return false
                return v.isVisible && Rect().also { v.getGlobalVisibleRect(it) }.contains(ev.rawX.toInt(), ev.rawY.toInt())
            }

            private fun updateFocusState(ev: MotionEvent) {
                val (fx, fy) = ev.getFocusCoords()
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    initialTouchX = fx; initialTouchY = fy; lastFocusX = fx; lastFocusY = fy
                    isScaling = false; hasPanned = false
                } else if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    isScaling = true; lastFocusX = fx; lastFocusY = fy
                }
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (listOf("correction_ui_root", "manual_input_blocker", "close_button").any { isTouchOnView(it, ev) }) return false
                updateFocusState(ev)
                if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
                    if (ev.pointerCount > 1 || abs(ev.x - initialTouchX) > 10 || abs(ev.y - initialTouchY) > 10) return true
                } else if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) return true
                return super.onInterceptTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.argb(140, 0, 0, 0))
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            setOnClickListener {
                if (findViewWithTag<View>("manual_input_blocker") != null) { closeManualInput(this); return@setOnClickListener }
                if (controller.isAlternativesVisible) {
                    toggleAlternativesPanel(this, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, width > height)
                    return@setOnClickListener
                }
                findViewWithTag<View>("correction_ui_root")?.let { removeView(it); resetHighlights(); return@setOnClickListener }
                if (!controller.isNearCharacter(initialTouchX, initialTouchY, 20f, resources.displayMetrics.density)) hideScreenshotOverlay()
            }
            setOnGenericMotionListener { _, event ->
                handleJoystick(
                    event,
                    controller.lastJoystickKeyCode,
                    { controller.lastJoystickKeyCode = it },
                    { handleGamepad(it) }
                )
            }
        }

            // Viewport container to support Pan & Zoom for image AND results
            val contentContainer = FrameLayout(this).apply {
                tag = "content_container"
                pivotX = 0f
                pivotY = 0f
            }
            rootLayout.addView(contentContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            
            val imageView = android.widget.ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
            }
            contentContainer.addView(imageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val gestureDetector = android.view.ScaleGestureDetector(this@OcrAccessibilityService, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val oldScale = controller.currentScale
                    controller.currentScale = (controller.currentScale * detector.scaleFactor).coerceIn(1f, 5f)
                    val factor = controller.currentScale / oldScale

                    contentContainer.scaleX = controller.currentScale
                    contentContainer.scaleY = controller.currentScale

                    // Zoom around the focus point. 
                    // Note: transX/Y already include the focus shift pan from the current event
                    // because we update them in the touch listener before calling gestureDetector.onTouchEvent
                    controller.currentTransX = detector.focusX - (detector.focusX - controller.currentTransX) * factor
                    controller.currentTransY = detector.focusY - (detector.focusY - controller.currentTransY) * factor
                    
                    contentContainer.translationX = controller.currentTransX
                    contentContainer.translationY = controller.currentTransY
                    return true
                }
            })

            rootLayout.setOnTouchListener { v, event ->
                val (focusX, focusY) = event.getFocusCoords()

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = focusX; initialTouchY = focusY
                        lastFocusX = focusX; lastFocusY = focusY
                        isScaling = false; hasPanned = false
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        isScaling = true; lastFocusX = focusX; lastFocusY = focusY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = focusX - lastFocusX
                        val dy = focusY - lastFocusY
                        
                        if (initialTouchY < 100 && focusY - initialTouchY > 50 && !isScaling && !hasPanned) {
                            hideScreenshotOverlay(); return@setOnTouchListener true
                        }

                        if (!isScaling && !hasPanned && (abs(focusX - initialTouchX) > 10 || abs(focusY - initialTouchY) > 10)) {
                            hasPanned = true
                        }

                        if ((hasPanned && !isScaling) || event.pointerCount > 1) {
                            controller.currentTransX += dx
                            controller.currentTransY += dy
                            contentContainer.translationX = controller.currentTransX
                            contentContainer.translationY = controller.currentTransY
                        }
                        lastFocusX = focusX; lastFocusY = focusY
                    }
                    MotionEvent.ACTION_POINTER_UP -> { lastFocusX = focusX; lastFocusY = focusY }
                }

                gestureDetector.onTouchEvent(event)
                
                if (event.actionMasked == MotionEvent.ACTION_UP && !hasPanned && !isScaling) {
                    if (abs(focusX - initialTouchX) < 10 && abs(focusY - initialTouchY) < 10) v.performClick()
                }
                true
            }
        
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

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        val progressParams = FrameLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (4 * resources.displayMetrics.density).toInt()
        ).apply {
            gravity = Gravity.TOP
        }
        rootLayout.addView(progressBar, progressParams)

        screenshotOverlay = rootLayout
        windowManager?.addView(screenshotOverlay, params)
        
        val closeButton = CenteredButton(this).apply {
            tag = "close_button"
            background = createButtonBackground()
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            gravity = Gravity.CENTER
            includeFontPadding = false
            alpha = 1.0f
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
                            v.parent.requestDisallowInterceptTouchEvent(true)
                            initialX = lp.leftMargin.toFloat()
                            initialY = lp.topMargin.toFloat()
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val displayMetrics = resources.displayMetrics
                            val maxX = displayMetrics.widthPixels - v.width
                            val maxY = displayMetrics.heightPixels - v.height

                            val newX = (initialX + (event.rawX - initialTouchX)).roundToInt().coerceIn(0, maxX)
                            val newY = (initialY + (event.rawY - initialTouchY)).roundToInt().coerceIn(0, maxY)
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
        val size = (44 * resources.displayMetrics.density).toInt()
        val lp = FrameLayout.LayoutParams(size, size).apply {
            leftMargin = floatingParams?.x ?: 100
            topMargin = floatingParams?.y ?: 100
        }
        rootLayout.addView(closeButton, lp)

        ocrJob = serviceScope.launch {
            try {
                if (ocrEngine.isReady()) {
                    debugTextView.text = "Running detection..."
                    val lineBoxes = withContext(Dispatchers.IO) { ocrEngine.detect(bitmap) }
                    controller.activeLineBoxes = lineBoxes
                    
                    debugTextView.text = "Found ${lineBoxes.size} lines. Recognizing..."
                    
                    val linesBorderLayer = FrameLayout(this@OcrAccessibilityService)
                    contentContainer.addView(linesBorderLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                    lineBoxes.forEach { box ->
                        val lineView = View(this@OcrAccessibilityService).apply {
                            background = borderDrawable
                        }
                        val lineParams = FrameLayout.LayoutParams(box.width(), box.height()).apply {
                            leftMargin = box.left
                            topMargin = box.top
                        }
                        linesBorderLayer.addView(lineView, lineParams)
                    }

                    val clicksLayer = FrameLayout(this@OcrAccessibilityService).apply { tag = "clicks_layer" }
                    contentContainer.addView(clicksLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                    // Pre-create line containers to maintain Z-order and simplify updates
                    lineBoxes.forEachIndexed { i, _ ->
                        val lineContainer = FrameLayout(this@OcrAccessibilityService).apply { tag = "line_clicks_$i" }
                        clicksLayer.addView(lineContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    }

                    textViews.clear()
                    controller.activeLineResults = MutableList(lineBoxes.size) { null as LineResult? }
                    controller.activeAllChars = mutableListOf()
                    controller.activeAllAlternatives = mutableListOf()

                    val startTime = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        ocrEngine.recognizeStreaming(bitmap, lineBoxes) { index, lineResult ->
                            if (screenshotOverlay == null) return@recognizeStreaming
                            serviceScope.launch {
                                addLineToResults(rootLayout, clicksLayer, index, lineResult)
                                if (controller.currentTappedLineIdx == -1) {
                                    updateCursor()
                                }
                                debugTextView.text = "Recognized ${controller.activeLineResults.count { it != null }}/${lineBoxes.size} lines..."
                                debugTextView.bringToFront()
                            }
                        }
                    }
                    val endTime = System.currentTimeMillis() - startTime
                    debugTextView.text = "Found ${controller.activeAllChars.size} characters. Time: ${endTime}ms"
                    progressBar.visibility = View.GONE
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

    private fun addLineToResults(rootLayout: FrameLayout, clicksLayer: FrameLayout, lineIdx: Int, line: LineResult) {
        if (screenshotOverlay == null) return
        controller.activeLineResults[lineIdx] = line
        controller.updateGlobalData()

        val lineContainer = clicksLayer.findViewWithTag<FrameLayout>("line_clicks_$lineIdx") ?: clicksLayer
        val displayBoxes = controller.calculateDisplayBoxes(line)
        val fixedSize = if (line.isVertical) {
            line.charBoxes.map { it.width() }.maxOrNull() ?: 0
        } else {
            line.charBoxes.map { it.height() }.maxOrNull() ?: 0
        }

        for (i in line.charBoxes.indices) {
            val box = displayBoxes[i]
            val char = line.text.getOrNull(i)?.toString() ?: ""
            
            val charContainer = FrameLayout(this)
            val charParams = FrameLayout.LayoutParams(fixedSize, fixedSize).apply {
                leftMargin = box.left
                topMargin = box.top
            }
            if (charContainer.parent != null) {
                (charContainer.parent as ViewGroup).removeView(charContainer)
            }
            lineContainer.addView(charContainer, charParams)

            val textView = CenteredTextView(this).apply {
                text = char
                setTextColor(android.graphics.Color.parseColor("#FF7777"))
                typeface = android.graphics.Typeface.DEFAULT
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fixedSize.toFloat() * 0.90f)
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
                isClickable = false
                
                if (line.isVertical) {
                    textLocale = java.util.Locale.JAPANESE
                    fontFeatureSettings = "'vert' 1"
                }
            }
            textViews[Pair(lineIdx, i)] = textView
            charContainer.addView(textView, FrameLayout.LayoutParams(fixedSize, fixedSize, Gravity.CENTER))

            val boxView = View(this).apply {
                background = null
                isClickable = true
            }
            charContainer.addView(boxView, FrameLayout.LayoutParams(box.width(), box.height(), Gravity.CENTER))

            boxView.setOnClickListener {
                performLookup(lineIdx, i, rootLayout)
            }
        }
        updateNeighborPanelForLine(rootLayout, lineIdx)
    }

    private fun updateNeighborPanelForLine(rootLayout: FrameLayout, lineIdx: Int) {
        val neighborPanel = rootLayout.findViewWithTag<LinearLayout>("neighbor_scroll_panel") ?: return
        val neighborScrollView = rootLayout.findViewWithTag<View>("neighbor_scroll_view") ?: return
        val lineContainer = neighborPanel.findViewWithTag<LinearLayout>("line_neighbor_$lineIdx") ?: return
        
        val isLandscape = rootLayout.width > rootLayout.height
        
        val isAbove = lineIdx < controller.currentTappedLineIdx
        val oldDim = if (isAbove) (if (isLandscape) lineContainer.height else lineContainer.width) else 0

        fillLineNeighborContainer(lineContainer, lineIdx, isLandscape, rootLayout)
        
        if (isAbove) {
            lineContainer.post {
                val newDim = if (isLandscape) lineContainer.height else lineContainer.width
                val diff = newDim - oldDim
                if (diff != 0) {
                    if (isLandscape) {
                        (neighborScrollView as ScrollView).scrollBy(0, diff)
                    } else {
                        (neighborScrollView as HorizontalScrollView).scrollBy(diff, 0)
                    }
                }
            }
        }
    }

    private fun fillLineNeighborContainer(lineContainer: LinearLayout, lineIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout) {
        lineContainer.removeAllViews()
        val neighborState = controller.getNeighborUiState().getOrNull(lineIdx) ?: return
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)
        val itemLp = LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) }

        neighborState.chars.forEach { charState ->
            val neighborTextView = TextView(this).apply {
                tag = "neighbor_char_${charState.lineIdx}-${charState.charIdx}"
                text = charState.text
                setTextColor(if (charState.isSelected) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                textSize = estimatedTextSize
                gravity = Gravity.CENTER
                setBackgroundColor(if (charState.isSelected) android.graphics.Color.YELLOW else android.graphics.Color.argb(255, 65, 65, 65))
                
                if (isLandscape) {
                    textLocale = java.util.Locale.JAPANESE
                    fontFeatureSettings = "'vert' 1"
                }
                
                setOnClickListener {
                    if (controller.currentTappedLineIdx == charState.lineIdx && controller.currentTappedCharIdxInLine == charState.charIdx) {
                        toggleAlternativesPanel(rootLayout, charState.lineIdx, charState.charIdx, isLandscape)
                    } else {
                        val oldLineIdx = controller.currentTappedLineIdx
                        val oldCharIdx = controller.currentTappedCharIdxInLine
                        controller.currentTappedLineIdx = charState.lineIdx
                        controller.currentTappedCharIdxInLine = charState.charIdx
                        
                        val oldPanel = rootLayout.findViewWithTag<LinearLayout>("neighbor_scroll_panel")
                        oldPanel?.findViewWithTag<View>("neighbor_char_$oldLineIdx-$oldCharIdx")?.let { 
                            it.setBackgroundColor(android.graphics.Color.argb(255, 65, 65, 65))
                            (it as TextView).setTextColor(android.graphics.Color.WHITE) 
                        }
                        
                        this.setBackgroundColor(android.graphics.Color.YELLOW)
                        this.setTextColor(android.graphics.Color.BLACK)
                        
                        val altContainer = rootLayout.findViewWithTag<FrameLayout>("alternatives_container")
                        if (altContainer != null && altContainer.childCount > 0) {
                            updateAlternativesPanelContent(altContainer, charState.lineIdx, charState.charIdx, isLandscape, rootLayout)
                        }
                        
                        performLookup(charState.lineIdx, charState.charIdx, rootLayout, skipCenter = true)
                    }
                }
            }
            lineContainer.addView(neighborTextView, itemLp)
        }
    }

    private fun performLookup(lineIdx: Int, charIdx: Int, rootLayout: FrameLayout, skipCenter: Boolean = false) {
        // Instant visual feedback for the cursor movement
        updateLookupHighlights(lineIdx, charIdx, 1)

        lookupJob?.cancel()
        lookupJob = serviceScope.launch {
            // Wait for user to stop navigating before doing heavy DB/UI work
            kotlinx.coroutines.delay(200)

            val result = controller.lookup(lineIdx, charIdx) ?: return@launch

            val formattedMatches = result.first
            val maxMatchedLen = result.second
            val finalTappedBox = result.third

            updateLookupHighlights(lineIdx, charIdx, maxMatchedLen)

            showResultsUi(rootLayout, formattedMatches, finalTappedBox, skipCenter)
        }
    }

    private fun resetHighlights() {
        controller.lastHighlightedCoords.forEach { coords ->
            textViews[coords]?.let { tv ->
                tv.setTextColor(android.graphics.Color.parseColor("#FF7777"))
                tv.typeface = android.graphics.Typeface.DEFAULT
            }
        }
    }

    private fun updateLookupHighlights(lineIdx: Int, charIdx: Int, wordLength: Int) {
        resetHighlights()
        controller.updateHighlightCoords(lineIdx, charIdx, wordLength)
        controller.lastHighlightedCoords.forEach { coords ->
            textViews[coords]?.let { tv ->
                tv.setTextColor(android.graphics.Color.YELLOW)
                tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun showResultsUi(rootLayout: FrameLayout, matches: List<FormattedEntry>, tappedBox: JpDictRect, skipCenter: Boolean = false) {
        controller.isDictionaryVisible = true
        val existingRoot = rootLayout.findViewWithTag<LinearLayout>("correction_ui_root")
        
        if (existingRoot != null) {
            val dictionaryContainer = existingRoot.findViewWithTag<LinearLayout>("dictionary_content_container")
            if (dictionaryContainer != null) updateDictionaryPanel(dictionaryContainer, matches)
            
            val neighborPanel = existingRoot.findViewWithTag<LinearLayout>("neighbor_scroll_panel")
            if (neighborPanel != null) updateNeighborHighlights(neighborPanel)
            
            if (!skipCenter) {
                val neighborScrollView = existingRoot.findViewWithTag<View>("neighbor_scroll_view")
                if (neighborScrollView != null) centerNeighborScrollView(neighborScrollView, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine)
            }
            centerWordInVisibleArea(rootLayout, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine)
            existingRoot.bringToFront()
            updateCursor()
            return
        }

        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val isLandscape = rootWidth > rootHeight
        
        controller.updateGravity(rootWidth, rootHeight, tappedBox)
        val (panelWidthF, panelHeightF) = controller.getPanelDimensions(rootWidth, rootHeight)
        val panelWidth = if (isLandscape) panelWidthF.toInt() else FrameLayout.LayoutParams.MATCH_PARENT
        val panelHeight = if (isLandscape) FrameLayout.LayoutParams.MATCH_PARENT else panelHeightF.toInt()

        val dictionaryPanel = LinearLayout(this).apply {
            tag = "dictionary_content_container"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.argb(245, 25, 25, 25))
            setPadding(40, 40, 40, 40)
            elevation = 20f
            setOnClickListener { }
        }
        updateDictionaryPanel(dictionaryPanel, matches)

        val correctionPanel = createCorrectionPanel(controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, isLandscape, rootLayout, skipCenter)
        val alternativesPanelContainer = FrameLayout(this).apply {
            tag = "alternatives_container"
            layoutParams = if (isLandscape) LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val mainContainer = LinearLayout(this).apply {
            tag = "correction_ui_root"
            elevation = 100f
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = (if (isLandscape) controller.lastLandscapeGravity else controller.lastPortraitGravity).toAndroidGravity()

            val isReverse = if (isLandscape) controller.lastLandscapeGravity == JpDictGravity.END else controller.lastPortraitGravity == JpDictGravity.BOTTOM
            if (isReverse) {
                addView(alternativesPanelContainer)
                addView(correctionPanel)
                addView(dictionaryPanel, LinearLayout.LayoutParams(panelWidth, panelHeight))
            } else {
                addView(dictionaryPanel, LinearLayout.LayoutParams(panelWidth, panelHeight))
                addView(correctionPanel)
                addView(alternativesPanelContainer)
            }
        }

        val rootParams = FrameLayout.LayoutParams(
            if (isLandscape) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT,
            if (isLandscape) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = mainContainer.gravity }

        rootLayout.addView(mainContainer, rootParams)
        mainContainer.bringToFront()

        centerWordInVisibleArea(rootLayout, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine)
        updateCursor()
    }

    private fun updateDictionaryPanel(container: LinearLayout, matches: List<FormattedEntry>) {
        container.removeAllViews()
        targetScrollY = 0
        scrollAnimator?.cancel()
        if (matches.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No results found"
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                textSize = 16f
                setPadding(0, 150, 0, 0)
            })
            return
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 0, 10, 150)
        }

        matches.forEach { entry ->
            val termSection = LinearLayout(this).apply { 
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 40)
            }
            
            entry.readingGroups.forEach { group ->
                renderHeadwordSection(termSection, group)
                renderSensesForReading(termSection, group)
                
                termSection.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 20 }
                    setBackgroundColor(Color.DKGRAY)
                    alpha = 0.3f
                })
            }
            scrollContent.addView(termSection)
        }

        val scrollView = ScrollView(this).apply {
            tag = "dictionary_scroll_view"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
            addView(scrollContent)
        }
        container.addView(scrollView)
    }

    private fun renderHeadwordSection(container: LinearLayout, group: FormattedReadingGroup) {
        val headwordList = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 10)
        }

        if (group.isKanjiEntry) {
            group.headwords.forEach { hw ->
                val kanjiHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 5, 0, 5)
                }
                kanjiHeader.addView(TextView(this).apply {
                    text = hw.kanji
                    setTextColor(Color.CYAN)
                    textSize = 48f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 30, 0)
                })
                val readingStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                hw.onyomi?.takeIf { it.isNotEmpty() }?.let { readingStack.addView(TextView(this).apply { text = "ON: ${it.replace(" ", "、")}"; setTextColor(Color.LTGRAY); textSize = 14f }) }
                hw.kunyomi?.takeIf { it.isNotEmpty() }?.let { readingStack.addView(TextView(this).apply { text = "KUN: ${it.replace(" ", "、")}"; setTextColor(Color.LTGRAY); textSize = 14f }) }
                kanjiHeader.addView(readingStack)
                headwordList.addView(kanjiHeader)
            }
        } else {
            val flow = FlowLayout(this).apply { setPadding(0, 5, 0, 5) }
            group.headwords.forEachIndexed { i, hw ->
                flow.addView(createRubyView(hw.kanji, group.reading))
                if (i < group.headwords.size - 1) {
                    flow.addView(TextView(this).apply { text = "、"; setTextColor(Color.GRAY); textSize = 24f; setPadding(5, 0, 5, 0) })
                }
            }
            headwordList.addView(flow)
        }
        container.addView(headwordList)
    }

    private fun renderSensesForReading(container: LinearLayout, group: FormattedReadingGroup) {
        group.senseGroups.forEach { senseGroup ->
            renderSenseGroup(container, senseGroup)
        }
    }

    private fun updateNeighborHighlights(panel: LinearLayout) {
        // Clear previous highlight
        if (controller.lastNeighborHighlightedLine != -1 && controller.lastNeighborHighlightedChar != -1) {
            val oldLineContainer = panel.findViewWithTag<LinearLayout>("line_neighbor_${controller.lastNeighborHighlightedLine}")
            val oldView = oldLineContainer?.getChildAt(controller.lastNeighborHighlightedChar) as? TextView
            oldView?.let {
                it.setBackgroundColor(android.graphics.Color.argb(255, 65, 65, 65))
                it.setTextColor(android.graphics.Color.WHITE)
            }
        }
        
        // Set new highlight
        val lineContainer = panel.findViewWithTag<LinearLayout>("line_neighbor_${controller.currentTappedLineIdx}")
        val newView = lineContainer?.getChildAt(controller.currentTappedCharIdxInLine) as? TextView
        newView?.let {
            it.setBackgroundColor(android.graphics.Color.YELLOW)
            it.setTextColor(android.graphics.Color.BLACK)
        }
        
        controller.lastNeighborHighlightedLine = controller.currentTappedLineIdx
        controller.lastNeighborHighlightedChar = controller.currentTappedCharIdxInLine
    }

    private fun centerNeighborScrollView(scrollView: View, lIdx: Int, cIdx: Int) {
        val panel = scrollView.findViewWithTag<LinearLayout>("neighbor_scroll_panel") ?: return
        val lineContainer = panel.findViewWithTag<LinearLayout>("line_neighbor_$lIdx") ?: return
        val targetView = lineContainer.getChildAt(cIdx) ?: return
        scrollView.post {
            val target = if (scrollView is ScrollView) {
                lineContainer.top + targetView.top - (scrollView.height / 2) + (targetView.height / 2)
            } else {
                lineContainer.left + targetView.left - (scrollView.width / 2) + (targetView.width / 2)
            }
            
            neighborAnimator?.cancel()
            neighborAnimator = ObjectAnimator.ofInt(scrollView, if (scrollView is ScrollView) "scrollY" else "scrollX", target).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }

    private fun createCorrectionPanel(tappedLIdx: Int, tappedCIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout, skipCenter: Boolean = false): View {
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11

        val outerContainer = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.argb(255, 45, 45, 45)); elevation = 25f }
        val scrollView = if (isLandscape) ScrollView(this) else HorizontalScrollView(this)
        scrollView.apply {
            tag = "neighbor_scroll_view"; isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            layoutParams = if (isLandscape) FrameLayout.LayoutParams(itemSize + 12, FrameLayout.LayoutParams.MATCH_PARENT) else FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, itemSize + 12)
        }

        val panel = LinearLayout(this).apply { tag = "neighbor_scroll_panel"; orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(6, 6, 6, 6) }

        controller.activeLineResults.forEachIndexed { lIdx, line ->
            val lineContainer = LinearLayout(this).apply {
                tag = "line_neighbor_$lIdx"
                orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            }
            panel.addView(lineContainer)
            if (line != null) {
                fillLineNeighborContainer(lineContainer, lIdx, isLandscape, rootLayout)
            }
        }

        scrollView.addView(panel); outerContainer.addView(scrollView)
        if (!skipCenter) centerNeighborScrollView(scrollView, tappedLIdx, tappedCIdx)
        return outerContainer
    }

    private fun toggleAlternativesPanel(rootLayout: FrameLayout, lIdx: Int, cIdx: Int, isLandscape: Boolean) {
        val container = rootLayout.findViewWithTag<FrameLayout>("alternatives_container") ?: return
        if (container.childCount > 0) { 
            container.removeAllViews()
            controller.isAlternativesVisible = false
            return 
        }
        controller.isAlternativesVisible = true
        val altState = controller.getAlternativesUiState() ?: return
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)

        // Added Preview Image
        val bitmap = screenshotBitmap
        val line = controller.activeLineResults[lIdx]
        val box = line?.charBoxes?.getOrNull(cIdx)
        val previewView = android.widget.ImageView(this).apply {
            tag = "preview_image"
            if (bitmap != null && box != null) {
                val padding = (box.height() * 0.2).toInt() // Tightened
                val cropRect = Rect((box.left - padding).coerceAtLeast(0), (box.top - padding).coerceAtLeast(0), (box.right + padding).coerceAtMost(bitmap.width), (box.bottom + padding).coerceAtMost(bitmap.height))
                val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
                setImageBitmap(cropped)
            }
            layoutParams = LinearLayout.LayoutParams(itemSize, itemSize).apply { gravity = Gravity.CENTER; setMargins(2, 2, 2, 2) }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }

        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.argb(255, 55, 55, 55)); setPadding(6, 6, 6, 6); elevation = 30f; setOnClickListener { } }
        mainLayout.addView(previewView)
        
        val scrollContent = LinearLayout(this).apply { orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val scrollView = if (isLandscape) ScrollView(this) else HorizontalScrollView(this)
        scrollView.apply {
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            layoutParams = if (isLandscape) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 10f) else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 10f)
        }
        val candidateList = LinearLayout(this).apply { tag = "candidate_list_panel"; orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        refreshCandidateList(candidateList, lIdx, cIdx, isLandscape, rootLayout)
        scrollView.addView(candidateList); scrollContent.addView(scrollView)
        
        // Add manual stub next to candidate list if possible
        if (altState.showManualInput) {
            val stubView = TextView(this).apply { tag = "manual_input_stub"; text = "⌨"; setTextColor(android.graphics.Color.GRAY); textSize = estimatedTextSize; gravity = Gravity.CENTER; setBackgroundColor(android.graphics.Color.argb(255, 40, 40, 40)); setOnClickListener { showManualInput(lIdx, cIdx, rootLayout) } }
            scrollContent.addView(stubView, if (isLandscape) LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) } else LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) })
        }
        mainLayout.addView(scrollContent, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        
        if (mainLayout.parent != null) {
            (mainLayout.parent as ViewGroup).removeView(mainLayout)
        }
        container.addView(mainLayout)

        // Find and scroll to selected
        scrollView.post {
            var selectedView: View? = null
            for (i in 0 until candidateList.childCount) {
                val v = candidateList.getChildAt(i) as? TextView ?: continue
                if (altState.candidates.getOrNull(i)?.isSelected == true) {
                    selectedView = v; break
                }
            }
            selectedView?.let { view -> if (isLandscape) scrollView.scrollTo(0, view.top) else (scrollView as HorizontalScrollView).scrollTo(view.left, 0) }
        }

        if (mainLayout.parent != null) {
            (mainLayout.parent as ViewGroup).removeView(mainLayout)
        }
        container.addView(mainLayout)
    }

    private fun refreshCandidateList(candidateList: LinearLayout, lIdx: Int, cIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout) {
        candidateList.removeAllViews()
        val altState = controller.getAlternativesUiState() ?: return
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)

        altState.candidates.forEach { cand ->
            val textView = TextView(this).apply {
                text = cand.char.toString(); setTextColor(android.graphics.Color.WHITE); textSize = estimatedTextSize; gravity = Gravity.CENTER
                if (cand.isSelected) { setBackgroundColor(android.graphics.Color.YELLOW); setTextColor(android.graphics.Color.BLACK) }
                else setBackgroundColor(android.graphics.Color.argb(255, 85, 85, 85))
                
                if (isLandscape) {
                    textLocale = java.util.Locale.JAPANESE
                    fontFeatureSettings = "'vert' 1"
                }

                setOnClickListener { 
                    if (cand.isSelected) {
                        toggleAlternativesPanel(rootLayout, lIdx, cIdx, isLandscape)
                    } else {
                        replaceCharacter(lIdx, cIdx, cand.char, rootLayout)
                    }
                }
            }
            candidateList.addView(textView, LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) })
        }
    }

    private fun updateAlternativesPanelContent(container: FrameLayout, lIdx: Int, cIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout) {
        val candidateList = container.findViewWithTag<LinearLayout>("candidate_list_panel") ?: return
        refreshCandidateList(candidateList, lIdx, cIdx, isLandscape, rootLayout)
        
        // Update Preview
        val previewView = container.findViewWithTag<android.widget.ImageView>("preview_image")
        val bitmap = screenshotBitmap
        val line = controller.activeLineResults[lIdx]
        val box = line?.charBoxes?.getOrNull(cIdx)
        if (previewView != null && bitmap != null && box != null) {
            val padding = (box.height() * 0.2).toInt()
            val cropRect = Rect((box.left - padding).coerceAtLeast(0), (box.top - padding).coerceAtLeast(0), (box.right + padding).coerceAtMost(bitmap.width), (box.bottom + padding).coerceAtMost(bitmap.height))
            val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            previewView.setImageBitmap(cropped)
        }
        
        // Update manual input stub too
        val stub = container.findViewWithTag<TextView>("manual_input_stub")
        stub?.setOnClickListener { showManualInput(lIdx, cIdx, rootLayout) }

        val scrollView = candidateList.parent as? View ?: return
        scrollView.post {
            var selectedView: View? = null
            val altState = controller.getAlternativesUiState()
            for (i in 0 until candidateList.childCount) {
                val v = candidateList.getChildAt(i) as? TextView ?: continue
                if (altState?.candidates?.getOrNull(i)?.isSelected == true) {
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

    private fun replaceCharacter(lIdx: Int, cIdx: Int, newChar: Char, rootLayout: FrameLayout) {
        controller.updateCharacter(lIdx, cIdx, newChar)
        
        textViews[Pair(lIdx, cIdx)]?.text = newChar.toString()
        
        val browserPanel = rootLayout.findViewWithTag<LinearLayout>("neighbor_scroll_panel")
        browserPanel?.findViewWithTag<TextView>("neighbor_char_$lIdx-$cIdx")?.text = newChar.toString()

        val altContainer = rootLayout.findViewWithTag<FrameLayout>("alternatives_container")
        if (altContainer != null && altContainer.childCount > 0) {
            val isLandscape = rootLayout.width > rootLayout.height
            updateAlternativesPanelContent(altContainer, lIdx, cIdx, isLandscape, rootLayout)
        }

        performLookup(lIdx, cIdx, rootLayout, skipCenter = true)
    }

    private fun showManualInput(lIdx: Int, cIdx: Int, rootLayout: FrameLayout) {
        val bitmap = screenshotBitmap ?: return
        val line = controller.activeLineResults[lIdx] ?: return
        val box = line.charBoxes[cIdx]
        val padding = (box.height() * 0.5).toInt()
        val cropRect = Rect((box.left - padding).coerceAtLeast(0), (box.top - padding).coerceAtLeast(0), (box.right + padding).coerceAtMost(bitmap.width), (box.bottom + padding).coerceAtMost(bitmap.height))
        val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        val blocker = FrameLayout(this).apply { tag = "manual_input_blocker"; setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0)); setOnClickListener { closeManualInput(rootLayout) }; elevation = 200f }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.argb(255, 35, 35, 35)); setPadding(60, 60, 60, 60); gravity = Gravity.CENTER_HORIZONTAL; elevation = 201f; setOnClickListener { } }
        panel.addView(android.widget.ImageView(this).apply { setImageBitmap(cropped); val size = (resources.displayMetrics.density * 120).toInt(); layoutParams = LinearLayout.LayoutParams(size, size); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER })
        panel.addView(TextView(this).apply { text = "Enter character manually"; setTextColor(android.graphics.Color.GRAY); textSize = 14f; setPadding(0, 30, 0, 10) })
        val editText = EditText(this).apply { setTextColor(android.graphics.Color.WHITE); textSize = 36f; gravity = Gravity.CENTER; maxLines = 1; imeOptions = EditorInfo.IME_ACTION_DONE; inputType = android.text.InputType.TYPE_CLASS_TEXT; background.setTint(android.graphics.Color.CYAN) }
        panel.addView(editText, LinearLayout.LayoutParams(250, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(Button(this).apply { text = "Confirm"; setOnClickListener { val text = editText.text.toString(); if (text.isNotEmpty()) { replaceCharacter(lIdx, cIdx, text[0], rootLayout); closeManualInput(rootLayout) } } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })
        blocker.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val params = screenshotOverlay?.layoutParams as? WindowManager.LayoutParams
        if (params != null) { params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE; windowManager?.updateViewLayout(screenshotOverlay, params) }
        rootLayout.addView(blocker, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        blocker.bringToFront()
        editText.requestFocus()
        editText.postDelayed({ (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) }, 100)
        editText.setOnEditorActionListener { _, actionId, event -> if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) { val text = editText.text.toString(); if (text.isNotEmpty()) { replaceCharacter(lIdx, cIdx, text[0], rootLayout); closeManualInput(rootLayout) }; true } else false }
    }

    private fun closeManualInput(rootLayout: FrameLayout) {
        val blocker = rootLayout.findViewWithTag<View>("manual_input_blocker") ?: return
        controller.lastManualInputCloseTime = System.currentTimeMillis()
        rootLayout.removeView(blocker)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(rootLayout.windowToken, 0)
    }

    private fun createTagView(tag: String, category: String = "general"): View {
        val color = when { 
            category == "pos" || tag.startsWith("v") || tag == "adj-i" || tag == "adj-na" -> Color.parseColor("#3a5a7a")
            tag == "n" || tag == "adv" || tag == "pn" -> Color.parseColor("#3a7a5a")
            category == "meta" || tag.startsWith("jlpt") || tag.startsWith("grade") || tag == "★" -> Color.parseColor("#7a3a3a")
            else -> Color.parseColor("#444444") 
        }
        return TextView(this).apply { 
            text = tag
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(12, 2, 12, 2)
            background = GradientDrawable().apply { 
                setColor(color)
                cornerRadius = 6f 
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { 
                setMargins(0, 0, 12, 0) 
            }
            includeFontPadding = false
        }
    }

    private fun createRubyView(term: String, reading: String, isMini: Boolean = false): View {
        if (term == reading) {
            return TextView(this).apply {
                text = term
                setTextColor(Color.CYAN)
                textSize = if (isMini) 15f else 32f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isBaselineAligned = true
            
            addView(TextView(this@OcrAccessibilityService).apply {
                text = reading
                setTextColor(Color.LTGRAY)
                textSize = if (isMini) 9f else 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            addView(TextView(this@OcrAccessibilityService).apply {
                text = term
                setTextColor(Color.CYAN)
                textSize = if (isMini) 15f else 32f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            baselineAlignedChildIndex = 1
        }
    }

    private fun renderSenseGroup(container: LinearLayout, senseGroup: FormattedSenseGroup) {
        if (senseGroup.senses.isEmpty()) return
        
        if (senseGroup.tags.isNotEmpty()) {
            val header = FlowLayout(this).apply { setPadding(20, 15, 0, 5) }
            senseGroup.tags.forEach { header.addView(createTagView(it)) }
            container.addView(header)
        }
        
        if (senseGroup.isForms) {
            val table = LinearLayout(this).apply { 
                orientation = LinearLayout.VERTICAL
                setPadding(30, 5, 10, 5)
            }
            senseGroup.senses.forEach { sense ->
                val row = FlowLayout(this).apply { setPadding(0, 5, 0, 5) }
                renderDefinition(row, sense.nodes)
                table.addView(row)
            }
            container.addView(table)
        } else {
            senseGroup.senses.forEach { sense ->
                val senseLayout = LinearLayout(this).apply { 
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(30, 5, 10, 5)
                }
                senseLayout.addView(TextView(this).apply {
                    text = "${sense.index}. "
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setPadding(0, 0, 10, 0)
                })
                
                val contentContainer = FlowLayout(this).apply {
                    setPadding(0, 0, 0, 15)
                }
                renderDefinition(contentContainer, sense.nodes)
                senseLayout.addView(contentContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                
                container.addView(senseLayout)
            }
        }
    }

    private fun renderDefinition(container: ViewGroup, nodes: List<DefinitionNode>) {
        var i = 0
        while (i < nodes.size) {
            when (val node = nodes[i]) {
                is DefinitionNode.Text -> {
                    val sb = StringBuilder()
                    var j = i
                    while (j < nodes.size && nodes[j] is DefinitionNode.Text) {
                        sb.append((nodes[j] as DefinitionNode.Text).text)
                        j++
                    }
                    container.addView(TextView(this).apply {
                        text = sb.toString()
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        includeFontPadding = false
                    })
                    i = j
                }
                is DefinitionNode.Ruby -> {
                    container.addView(createRubyView(node.term, node.reading, node.isMini))
                    i++
                }
                is DefinitionNode.Tag -> {
                    container.addView(createTagView(node.text, node.category))
                    i++
                }
                is DefinitionNode.Example -> {
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(30, 15, 30, 25)
                        background = GradientDrawable().apply {
                            setColor(android.graphics.Color.argb(10, 255, 255, 255))
                            setStroke(3, android.graphics.Color.argb(80, 255, 255, 255))
                            cornerRadius = 12f
                        }
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 15, 0, 15) }
                    }
                    if (node.japanese != null) {
                        box.addView(TextView(this).apply { text = node.japanese; setTextColor(Color.WHITE); textSize = 16f; setPadding(0, 0, 0, 10) })
                        node.english?.let { en -> box.addView(TextView(this).apply { text = en; setTextColor(Color.LTGRAY); textSize = 14f }) }
                    } else if (node.content != null) {
                        val flow = FlowLayout(this)
                        renderDefinition(flow, node.content)
                        box.addView(flow)
                    }
                    container.addView(box)
                    i++
                }
                is DefinitionNode.ListBlock -> {
                    val block = LinearLayout(this).apply { 
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 10) } 
                    }
                    node.items.forEach { itemNodes ->
                        val itemRow = FlowLayout(this).apply { setPadding(0, 0, 0, 5) }
                        renderDefinition(itemRow, itemNodes)
                        block.addView(itemRow)
                    }
                    container.addView(block)
                    i++
                }
                is DefinitionNode.Table -> {
                    // Skip table for now
                    i++
                }
                is DefinitionNode.Group -> {
                    val groupContainer = if (node.isInline) FlowLayout(this) else LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    if (groupContainer is FlowLayout) {
                        renderDefinition(groupContainer, node.nodes)
                    } else {
                        // If it's a vertical group, each child should still be rendered as a flow if it has inline elements
                        // But for simplicity, let's just use a nested FlowLayout for everything for now
                        val flow = FlowLayout(this)
                        renderDefinition(flow, node.nodes)
                        (groupContainer as LinearLayout).addView(flow)
                    }
                    container.addView(groupContainer)
                    i++
                }
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val keyEvent = event ?: return super.onKeyEvent(event)
        
        // Handle global shortcut when overlay is NOT showing
        if (screenshotOverlay == null) {
            val prefs = getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)
            val globalShortcutEnabled = prefs.getBoolean("global_shortcut_enabled", true)
            
            if (!globalShortcutEnabled) {
                pressedKeys.clear()
                return super.onKeyEvent(event)
            }

            when (keyEvent.action) {
                KeyEvent.ACTION_DOWN -> {
                    pressedKeys.add(keyEvent.keyCode)
                    if (pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_L1) && pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_R1)) {
                        val now = System.currentTimeMillis()
                        if (now - lastGlobalTriggerTime > 1500) { // 1.5s cooldown
                            lastGlobalTriggerTime = now
                            // Clear keys to prevent immediate repeat and consume the event
                            pressedKeys.clear()
                            
                            // Trigger OCR (same logic as floating button click)
                            floatingView?.visibility = View.GONE
                            ocrButton?.postDelayed({
                                triggerCapture { bitmap ->
                                    showScreenshotOverlay(bitmap)
                                }
                            }, 50)
                            return true
                        }
                    }
                }
                KeyEvent.ACTION_UP -> {
                    pressedKeys.remove(keyEvent.keyCode)
                }
            }
            return super.onKeyEvent(event)
        }

        Log.d("OcrAccessibilityService", "onKeyEvent: keyCode=${keyEvent.keyCode}, action=${keyEvent.action}")
        if (handleGamepad(keyEvent)) return true
        return super.onKeyEvent(keyEvent)
    }


    private fun handleGamepad(event: KeyEvent): Boolean {
        val root = screenshotOverlay as? FrameLayout ?: return false
        if (root.findViewWithTag<View>("manual_input_blocker") != null) return false
        
        val prefs = getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)
        val layoutSwap = prefs.getBoolean("layout_swap", false)
        val keyCode = event.keyCode
        
        if (!controller.isHandledKey(keyCode)) return false
        if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == currentRepeatingKeyCode) stopRepeat()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true

        val action = controller.resolveGamepadAction(keyCode, layoutSwap)
        if (action == GamepadAction.NONE) return false
        if (keyCode != currentRepeatingKeyCode) startRepeat(keyCode)

        when (action) {
            GamepadAction.BACK -> handleGamepadBack(root)
            GamepadAction.CONFIRM -> handleGamepadConfirm(root)
            GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT, GamepadAction.NAVIGATE_UP, GamepadAction.NAVIGATE_DOWN -> executeNavigation(keyCode)
            GamepadAction.SCROLL_UP, GamepadAction.SCROLL_DOWN -> scrollDictionary(keyCode, root)
            else -> return false
        }
        return true
    }

    private fun handleGamepadBack(root: FrameLayout) {
        if (controller.isAlternativesVisible) {
            toggleAlternativesPanel(root, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, root.width > root.height)
        } else if (controller.isDictionaryVisible) {
            root.removeView(root.findViewWithTag("correction_ui_root"))
            controller.isDictionaryVisible = false
            resetHighlights()
            updateCursor()
        } else {
            hideScreenshotOverlay()
        }
    }

    private fun handleGamepadConfirm(root: FrameLayout) {
        if (controller.isAlternativesVisible || controller.isDictionaryVisible) {
            toggleAlternativesPanel(root, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, root.width > root.height)
        } else if (controller.currentTappedLineIdx != -1 && controller.currentTappedCharIdxInLine != -1) {
            performLookup(controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, root)
        }
    }

    private fun startRepeat(keyCode: Int) {
        stopRepeat()
        currentRepeatingKeyCode = keyCode
        val prefs = getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)
        val delay = prefs.getInt("repeat_delay", 500).toLong()
        val rate = prefs.getInt("repeat_rate", 20)
        val interval = controller.getRepeatInterval(rate)
        val layoutSwap = prefs.getBoolean("layout_swap", false)
        val action = controller.resolveGamepadAction(keyCode, layoutSwap)

        repeatJob = serviceScope.launch {
            kotlinx.coroutines.delay(delay)
            while (isActive && currentRepeatingKeyCode == keyCode) {
                val root = screenshotOverlay as? FrameLayout ?: break
                
                when (action) {
                    GamepadAction.SCROLL_UP, GamepadAction.SCROLL_DOWN -> scrollDictionary(keyCode, root)
                    GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT, GamepadAction.NAVIGATE_UP, GamepadAction.NAVIGATE_DOWN -> executeNavigation(keyCode)
                    else -> {}
                }
                kotlinx.coroutines.delay(interval)
            }
        }
    }

    private fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
        currentRepeatingKeyCode = 0
    }

    private fun executeNavigation(keyCode: Int) {
        val root = screenshotOverlay as? FrameLayout ?: return
        
        controller.isControllerNavigation = true

        if (controller.isAlternativesVisible) {
            navigateAlternatives(keyCode, root)
            return
        }

        navigateOverlay(keyCode)
        if (controller.isDictionaryVisible) {
            performLookup(controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, root)
        }
    }

    private fun navigateOverlay(keyCode: Int) {
        val root = screenshotOverlay as? FrameLayout ?: return
        val rootWidth = root.width.toDouble().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toDouble()
        val rootHeight = root.height.toDouble().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toDouble()

        if (controller.navigate(keyCode, rootWidth, rootHeight)) {
            updateCursor()
            centerWordInVisibleArea(root, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine)
        } else if (controller.currentTappedLineIdx == -1) {
            updateCursor()
        }
    }


    private fun scrollDictionary(keyCode: Int, root: FrameLayout) {
        val dictionaryRoot = root.findViewWithTag<LinearLayout>("correction_ui_root") ?: return
        val scrollView = dictionaryRoot.findViewWithTag<ScrollView>("dictionary_scroll_view") ?: return
        val content = scrollView.getChildAt(0) ?: return
        val maxScroll = (content.height - scrollView.height).coerceAtLeast(0)

        val delta = (120 * resources.displayMetrics.density).toInt()
        val direction = if (keyCode == JpDictKeyEvent.KEYCODE_BUTTON_R1 || keyCode == JpDictKeyEvent.KEYCODE_BUTTON_R2) 1 else -1
        
        if (abs(targetScrollY - scrollView.scrollY) > delta * 2) {
            targetScrollY = scrollView.scrollY
        }

        targetScrollY = (targetScrollY + direction * delta).coerceIn(0, maxScroll)

        scrollAnimator?.cancel()
        scrollAnimator = ObjectAnimator.ofInt(scrollView, "scrollY", targetScrollY).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun navigateAlternatives(keyCode: Int, root: FrameLayout) {
        val isLandscape = root.width > root.height
        controller.navigateAlternatives(keyCode, isLandscape)?.let {
            replaceCharacter(controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, it, root)
        }
    }

    private fun updateCursor() {
        val root = screenshotOverlay as? FrameLayout ?: return
        val contentContainer = root.findViewWithTag<FrameLayout>("content_container") ?: return
        controller.ensureCursorPosition()

        val charBox = controller.activeLineResults.getOrNull(controller.currentTappedLineIdx)?.charBoxes?.getOrNull(controller.currentTappedCharIdxInLine)
        if (charBox != null) {
            if (cursorView == null) {
                cursorView = View(this).apply {
                    background = GradientDrawable().apply {
                        setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
                        cornerRadius = 4f
                    }
                    tag = "cursor_view"
                    layoutParams = FrameLayout.LayoutParams(0, 0)
                }
                contentContainer.addView(cursorView)
            }
            
            cursorView?.let { v ->
                val desiredW = charBox.width() + (4 * resources.displayMetrics.density).toInt()
                val desiredH = charBox.height() + (4 * resources.displayMetrics.density).toInt()
                
                if (v.layoutParams.width != desiredW || v.layoutParams.height != desiredH) {
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    lp.width = desiredW
                    lp.height = desiredH
                    v.layoutParams = lp
                }
                
                v.translationX = (charBox.left - (2 * resources.displayMetrics.density).toInt()).toFloat()
                v.translationY = (charBox.top - (2 * resources.displayMetrics.density).toInt()).toFloat()
                v.visibility = View.VISIBLE
                v.bringToFront()
            }
        } else cursorView?.visibility = View.GONE
    }

    private fun centerWordInVisibleArea(root: FrameLayout, lineIdx: Int, charIdx: Int) {
        val contentContainer = root.findViewWithTag<FrameLayout>("content_container") ?: return
        val rootWidth = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        
        if (controller.centerOnCharacter(lineIdx, charIdx, rootWidth, rootHeight)) {
            viewportAnimator?.cancel()
            val animX = ObjectAnimator.ofFloat(contentContainer, "translationX", controller.currentTransX)
            val animY = ObjectAnimator.ofFloat(contentContainer, "translationY", controller.currentTransY)
            viewportAnimator = AnimatorSet().apply {
                playTogether(animX, animY)
                duration = 250
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }

    private fun hideScreenshotOverlay() {
        ocrJob?.cancel()
        ocrJob = null
        lookupJob?.cancel()
        lookupJob = null
        val root = screenshotOverlay ?: return
        (floatingView?.parent as? android.view.ViewGroup)?.removeView(floatingView)
        if (root.isAttachedToWindow) try { windowManager?.removeViewImmediate(root) } catch (e: Exception) { Log.e("OcrAccessibilityService", "Error removing overlay", e) }
        screenshotOverlay = null; screenshotBitmap = null; floatingView?.visibility = View.VISIBLE
        controller.resetState()
        try { windowManager?.updateViewLayout(floatingView, floatingParams) } catch (e: Exception) { Log.e("OcrAccessibilityService", "Error restoring button", e) }
        textViews.clear()
        cursorView = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (screenshotOverlay == null) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventPackage = event.packageName?.toString()
            if (eventPackage == null || eventPackage == packageName) return
            val root = screenshotOverlay as? FrameLayout
            if (root?.findViewWithTag<View>("manual_input_blocker") != null) return
            if (System.currentTimeMillis() - controller.lastManualInputCloseTime < 1000) return
            if (event.isFullScreen != true) return
            hideScreenshotOverlay()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() { 
        super.onDestroy()
        try { unregisterReceiver(overlayControllerReceiver) } catch (e: Exception) {} 
        hideScreenshotOverlay()
        floatingView?.let { if (it.isAttachedToWindow) windowManager?.removeView(it) }
        ocrEngine.close() 
    }


    private class FlowLayout(context: Context) : android.view.ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val heightMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSize = MeasureSpec.getSize(heightMeasureSpec)
            
            val maxWidth = width - paddingLeft - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var rowHeight = 0
            var rowBaseline = 0
            
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                
                val childWidthSpec = if (child.layoutParams.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                    MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY)
                } else {
                    MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
                }
                child.measure(
                    childWidthSpec,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                
                val measuredWidth = if (child.layoutParams.width == ViewGroup.LayoutParams.MATCH_PARENT) maxWidth else child.measuredWidth
                
                if (x + measuredWidth > width - paddingRight && x > paddingLeft) {
                    x = paddingLeft
                    y += rowHeight
                    rowHeight = 0
                    rowBaseline = 0
                }
                x += measuredWidth
                rowHeight = maxOf(rowHeight, child.measuredHeight)
                rowBaseline = maxOf(rowBaseline, child.baseline)
            }
            
            val calculatedHeight = y + rowHeight + paddingBottom
            val finalHeight = if (heightMode == MeasureSpec.EXACTLY) heightSize else maxOf(calculatedHeight, minimumHeight)
            setMeasuredDimension(width, finalHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            val maxWidth = width - paddingLeft - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var rowHeight = 0
            var rowBaseline = 0
            var rowStartIndex = 0

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                
                val measuredWidth = if (child.layoutParams.width == ViewGroup.LayoutParams.MATCH_PARENT) maxWidth else child.measuredWidth

                if (x + measuredWidth > width - paddingRight && x > paddingLeft) {
                    layoutRow(rowStartIndex, i, y, rowHeight, rowBaseline, maxWidth)
                    x = paddingLeft
                    y += rowHeight
                    rowHeight = 0
                    rowBaseline = 0
                    rowStartIndex = i
                }
                x += measuredWidth
                rowHeight = maxOf(rowHeight, child.measuredHeight)
                rowBaseline = maxOf(rowBaseline, child.baseline)
            }
            layoutRow(rowStartIndex, childCount, y, rowHeight, rowBaseline, maxWidth)
        }

        private fun layoutRow(start: Int, end: Int, top: Int, rowHeight: Int, rowBaseline: Int, maxWidth: Int) {
            var x = paddingLeft
            for (i in start until end) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                
                val childBaseline = child.baseline
                val childTop = if (childBaseline != -1 && rowBaseline != -1) {
                    top + rowBaseline - childBaseline
                } else {
                    top + rowHeight - child.measuredHeight
                }
                
                val measuredWidth = if (child.layoutParams.width == ViewGroup.LayoutParams.MATCH_PARENT) maxWidth else child.measuredWidth
                child.layout(x, childTop, x + measuredWidth, childTop + child.measuredHeight)
                x += measuredWidth
            }
        }

        override fun getBaseline(): Int {
            if (childCount == 0) return -1
            return getChildAt(0).baseline
        }
    }

}
