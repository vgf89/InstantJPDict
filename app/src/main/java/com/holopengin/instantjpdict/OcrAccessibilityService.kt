package com.holopengin.instantjpdict

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
import android.view.InputDevice
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
import com.google.gson.Gson
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.util.Deinflector
import com.holopengin.instantjpdict.util.JapaneseUtil
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
    private lateinit var deinflector: Deinflector
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ocrJob: kotlinx.coroutines.Job? = null
    
    private var currentScale = 1f
    private var currentTransX = 0f
    private var currentTransY = 0f
    private var currentWordLength = 0
    private var cursorView: View? = null
    
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
    
    private val boxViews = mutableListOf<View>()
    private val textViews = mutableMapOf<Pair<Int, Int>, TextView>()

    private var activeLineBoxes: List<Rect> = emptyList()
    private var activeAllChars = mutableListOf<String>()
    private var activeAllAlternatives = mutableListOf<List<Pair<Char, Float>>>()
    
    private var currentTappedIdx = -1
    private var currentTappedLineIdx = -1
    private var currentTappedCharIdxInLine = -1

    private var lastLandscapeGravity = Gravity.END
    private var lastPortraitGravity = Gravity.BOTTOM
    private var lastManualInputCloseTime = 0L

    private var activeLineResults: MutableList<LineResult?> = mutableListOf()
    
    private var joystickLastX = 0f
    private var joystickLastY = 0f
    
    private var repeatJob: kotlinx.coroutines.Job? = null
    private var currentRepeatingKeyCode = 0

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
        deinflector = Deinflector(this)
        
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
        currentScale = 1f
        currentTransX = 0f
        currentTransY = 0f
        currentTappedIdx = -1
        currentTappedLineIdx = -1
        currentTappedCharIdxInLine = -1
        joystickLastX = 0f
        joystickLastY = 0f

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
                if (v.visibility != View.VISIBLE) return false
                val rect = Rect()
                v.getGlobalVisibleRect(rect)
                return rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
            }

            private fun updateFocusState(ev: MotionEvent) {
                var sumX = 0f
                var sumY = 0f
                val count = ev.pointerCount
                val isPointerUp = ev.actionMasked == MotionEvent.ACTION_POINTER_UP
                val div = if (isPointerUp) count - 1 else count
                if (div > 0) {
                    for (i in 0 until count) {
                        if (isPointerUp && i == ev.actionIndex) continue
                        sumX += ev.getX(i)
                        sumY += ev.getY(i)
                    }
                }
                val fx = if (div > 0) sumX / div else 0f
                val fy = if (div > 0) sumY / div else 0f

                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = fx; initialTouchY = fy
                        lastFocusX = fx; lastFocusY = fy
                        isScaling = false; hasPanned = false
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        isScaling = true
                        lastFocusX = fx; lastFocusY = fy
                    }
                }
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (isTouchOnView("correction_ui_root", ev)) return false
                if (isTouchOnView("manual_input_blocker", ev)) return false
                if (isTouchOnView("close_button", ev)) return false

                updateFocusState(ev)

                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(ev.x - initialTouchX)
                        val dy = abs(ev.y - initialTouchY)
                        if (ev.pointerCount > 1 || dx > 10 || dy > 10) return true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> return true
                }
                return super.onInterceptTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.argb(140, 0, 0, 0))
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
                    return@setOnClickListener
                }

                // Screen-space margin check: if click is within 20dp of any character box, ignore it
                val s = currentScale
                val tx = currentTransX
                val ty = currentTransY
                val ix = (initialTouchX - tx) / s
                val iy = (initialTouchY - ty) / s
                val m = (20 * resources.displayMetrics.density) / s
                
                val isNear = activeLineResults.filterNotNull().any { line ->
                    line.charBoxes.any { b ->
                        ix >= b.left - m && ix <= b.right + m && iy >= b.top - m && iy <= b.bottom + m
                    }
                } || activeLineBoxes.any { b ->
                    ix >= b.left - m && ix <= b.right + m && iy >= b.top - m && iy <= b.bottom + m
                }
                if (isNear) return@setOnClickListener

                hideScreenshotOverlay()
            }
            
            setOnGenericMotionListener { _, event ->
                handleJoystick(event)
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
                    val oldScale = currentScale
                    currentScale = (currentScale * detector.scaleFactor).coerceIn(1f, 5f)
                    val factor = currentScale / oldScale

                    contentContainer.scaleX = currentScale
                    contentContainer.scaleY = currentScale

                    // Zoom around the focus point. 
                    // Note: transX/Y already include the focus shift pan from the current event
                    // because we update them in the touch listener before calling gestureDetector.onTouchEvent
                    currentTransX = detector.focusX - (detector.focusX - currentTransX) * factor
                    currentTransY = detector.focusY - (detector.focusY - currentTransY) * factor
                    
                    contentContainer.translationX = currentTransX
                    contentContainer.translationY = currentTransY
                    return true
                }
            })

            rootLayout.setOnTouchListener { v, event ->
                // Calculate current focus (midpoint of all active pointers)
                var sumX = 0f
                var sumY = 0f
                val count = event.pointerCount
                val isPointerUp = event.actionMasked == MotionEvent.ACTION_POINTER_UP
                val div = if (isPointerUp) count - 1 else count
                
                if (div > 0) {
                    for (i in 0 until count) {
                        if (isPointerUp && i == event.actionIndex) continue
                        sumX += event.getX(i)
                        sumY += event.getY(i)
                    }
                }
                val focusX = if (div > 0) sumX / div else 0f
                val focusY = if (div > 0) sumY / div else 0f

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = focusX
                        initialTouchY = focusY
                        lastFocusX = focusX
                        lastFocusY = focusY
                        isScaling = false
                        hasPanned = false
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        isScaling = true
                        lastFocusX = focusX
                        lastFocusY = focusY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = focusX - lastFocusX
                        val dy = focusY - lastFocusY
                        
                        // Close if user swipes down from the top edge (status bar area)
                        if (initialTouchY < 100 && focusY - initialTouchY > 50 && !isScaling && !hasPanned) {
                            hideScreenshotOverlay()
                            return@setOnTouchListener true
                        }

                        if (!isScaling && !hasPanned && (abs(focusX - initialTouchX) > 10 || abs(focusY - initialTouchY) > 10)) {
                            hasPanned = true
                        }

                        // We pan if we are either in a 1-finger pan mode (!isScaling) 
                        // or if we have multiple fingers down (allowing pan-while-zoom)
                        if ((hasPanned && !isScaling) || count > 1) {
                            currentTransX += dx
                            currentTransY += dy
                        }
                        
                        lastFocusX = focusX
                        lastFocusY = focusY
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        lastFocusX = focusX
                        lastFocusY = focusY
                    }
                }

                gestureDetector.onTouchEvent(event)
                
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    if ((hasPanned && !isScaling) || count > 1) {
                        contentContainer.translationX = currentTransX
                        contentContainer.translationY = currentTransY
                    }
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    // Only performClick if we didn't pan or zoom
                    if (!hasPanned && !isScaling) {
                        val dx = focusX - initialTouchX
                        val dy = focusY - initialTouchY
                        if (abs(dx) < 10 && abs(dy) < 10) {
                            v.performClick()
                        }
                    }
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
                    activeLineBoxes = lineBoxes
                    
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

                    boxViews.clear()
                    textViews.clear()
                    activeLineResults = MutableList(lineBoxes.size) { null }
                    activeAllChars = mutableListOf()
                    activeAllAlternatives = mutableListOf()

                    val startTime = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        ocrEngine.recognizeStreaming(bitmap, lineBoxes) { index, lineResult ->
                            if (screenshotOverlay == null) return@recognizeStreaming
                            serviceScope.launch {
                                addLineToResults(rootLayout, clicksLayer, index, lineResult)
                                if (currentTappedLineIdx == -1) {
                                    updateCursor()
                                }
                                debugTextView.text = "Recognized ${activeLineResults.count { it != null }}/${lineBoxes.size} lines..."
                                debugTextView.bringToFront()
                            }
                        }
                    }
                    val endTime = System.currentTimeMillis() - startTime
                    debugTextView.text = "Found ${activeAllChars.size} characters. Time: ${endTime}ms"
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
        activeLineResults[lineIdx] = line
        updateGlobalData()

        val lineContainer = clicksLayer.findViewWithTag<FrameLayout>("line_clicks_$lineIdx") ?: clicksLayer

        val fixedSize = if (line.isVertical) {
            line.charBoxes.map { it.width() }.maxOrNull() ?: 0
        } else {
            line.charBoxes.map { it.height() }.maxOrNull() ?: 0
        }

        var lastX = -1
        var lastY = -1

        for (i in line.charBoxes.indices) {
            val originalBox = line.charBoxes[i]
            var centerX = originalBox.centerX()
            var centerY = originalBox.centerY()
            
            if (i == line.charBoxes.size - 1 && i > 0) {
                if (line.isVertical) centerY = lastY + fixedSize
                else centerX = lastX + fixedSize
            }

            val box = Rect(
                centerX - fixedSize / 2,
                centerY - fixedSize / 2,
                centerX + fixedSize / 2,
                centerY + fixedSize / 2
            )
            
            lastX = centerX
            lastY = centerY

            val char = line.text.getOrNull(i)?.toString() ?: ""
            
            val charContainer = FrameLayout(this)
            val charParams = FrameLayout.LayoutParams(fixedSize, fixedSize).apply {
                leftMargin = box.left
                topMargin = box.top
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
        updateNeighborPanelForLine(rootLayout, lineIdx, line)
    }

    private fun updateNeighborPanelForLine(rootLayout: FrameLayout, lineIdx: Int, line: LineResult) {
        val neighborPanel = rootLayout.findViewWithTag<LinearLayout>("neighbor_scroll_panel") ?: return
        val neighborScrollView = rootLayout.findViewWithTag<View>("neighbor_scroll_view") ?: return
        val lineContainer = neighborPanel.findViewWithTag<LinearLayout>("line_neighbor_$lineIdx") ?: return
        
        val isLandscape = rootLayout.width > rootLayout.height
        
        val isAbove = lineIdx < currentTappedLineIdx
        val oldDim = if (isAbove) (if (isLandscape) lineContainer.height else lineContainer.width) else 0

        fillLineNeighborContainer(lineContainer, lineIdx, line, isLandscape, rootLayout)
        
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

    private fun fillLineNeighborContainer(lineContainer: LinearLayout, lineIdx: Int, line: LineResult, isLandscape: Boolean, rootLayout: FrameLayout) {
        lineContainer.removeAllViews()
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)
        val itemLp = LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) }

        for (i in line.text.indices) {
            val char = line.text[i].toString()
            val neighborTextView = TextView(this).apply {
                tag = "neighbor_char_$lineIdx-$i"
                text = char
                setTextColor(android.graphics.Color.WHITE)
                textSize = estimatedTextSize
                gravity = Gravity.CENTER
                setBackgroundColor(android.graphics.Color.argb(255, 65, 65, 65))
                
                if (isLandscape) {
                    textLocale = java.util.Locale.JAPANESE
                    fontFeatureSettings = "'vert' 1"
                }
                
                if (lineIdx == currentTappedLineIdx && i == currentTappedCharIdxInLine) {
                    setBackgroundColor(android.graphics.Color.YELLOW)
                    setTextColor(android.graphics.Color.BLACK)
                }
                
                setOnClickListener {
                    if (currentTappedLineIdx == lineIdx && currentTappedCharIdxInLine == i) {
                        toggleAlternativesPanel(rootLayout, lineIdx, i, isLandscape)
                    } else {
                        val oldLineIdx = currentTappedLineIdx
                        val oldCharIdx = currentTappedCharIdxInLine
                        currentTappedLineIdx = lineIdx
                        currentTappedCharIdxInLine = i
                        
                        val oldPanel = rootLayout.findViewWithTag<LinearLayout>("neighbor_scroll_panel")
                        oldPanel?.findViewWithTag<View>("neighbor_char_$oldLineIdx-$oldCharIdx")?.let { 
                            it.setBackgroundColor(android.graphics.Color.argb(255, 65, 65, 65))
                            (it as TextView).setTextColor(android.graphics.Color.WHITE) 
                        }
                        
                        this.setBackgroundColor(android.graphics.Color.YELLOW)
                        this.setTextColor(android.graphics.Color.BLACK)
                        
                        val altContainer = rootLayout.findViewWithTag<FrameLayout>("alternatives_container")
                        if (altContainer != null && altContainer.childCount > 0) {
                            updateAlternativesPanelContent(altContainer, lineIdx, i, isLandscape, rootLayout)
                        }
                        
                        performLookup(lineIdx, i, rootLayout, skipCenter = true)
                    }
                }
            }
            lineContainer.addView(neighborTextView, itemLp)
        }
    }

    private fun performLookup(lineIdx: Int, charIdx: Int, rootLayout: FrameLayout, skipCenter: Boolean = false) {
        currentTappedLineIdx = lineIdx
        currentTappedCharIdxInLine = charIdx
        currentTappedIdx = getGlobalIdx(lineIdx, charIdx)

        val line = activeLineResults.getOrNull(lineIdx) ?: return
        val tappedBox = line.charBoxes.getOrNull(charIdx) ?: Rect()

        activeLineResults.getOrNull(lineIdx)?.alternatives?.getOrNull(charIdx)?.let { alternatives ->
            val logMsg = alternatives.joinToString(", ") { (char, score) ->
                "$char (${String.format(java.util.Locale.US, "%.8f", score)})"
            }
            Log.i("OcrAccessibilityService", "Top 15 candidates: $logMsg")
        }

        resetHighlights()

        textViews[Pair(lineIdx, charIdx)]?.let {
            it.setTextColor(android.graphics.Color.YELLOW)
            it.typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val endIdx = kotlin.math.min(currentTappedIdx + 20, activeAllChars.size)
        val followingText = activeAllChars.subList(currentTappedIdx, endIdx).joinToString("")

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
                currentWordLength = maxMatchedLen
                for (i in 0 until maxMatchedLen) {
                    val targetGlobalIdx = currentTappedIdx + i
                    val coords = getCoordsFromGlobalIdx(targetGlobalIdx)
                    if (coords != null) {
                        textViews[coords]?.setTextColor(android.graphics.Color.YELLOW)
                        textViews[coords]?.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                }
                showResultsUi(rootLayout, uniqueMatches, tappedBox, skipCenter)
            }
        }
    }

    private fun resetHighlights() {
        textViews.values.forEach { 
            it.setTextColor(android.graphics.Color.parseColor("#FF7777"))
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
                if (neighborScrollView != null) centerNeighborScrollView(neighborScrollView, currentTappedLineIdx, currentTappedCharIdxInLine)
            }
            centerWordInVisibleArea(rootLayout, currentTappedLineIdx, currentTappedCharIdxInLine, currentWordLength)
            existingRoot.bringToFront()
            updateCursor()
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
            elevation = 100f
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

        val correctionPanel = createCorrectionPanel(currentTappedLineIdx, currentTappedCharIdxInLine, isLandscape, rootLayout, skipCenter)
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
        mainContainer.bringToFront()

        centerWordInVisibleArea(rootLayout, currentTappedLineIdx, currentTappedCharIdxInLine, currentWordLength)
        updateCursor()
    }

    private fun updateDictionaryPanel(container: LinearLayout, matches: List<Pair<String, List<com.holopengin.instantjpdict.data.DictionaryEntry>>>) {
        container.removeAllViews()
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

        matches.forEach { (_, entries) ->
            val termSection = LinearLayout(this).apply { 
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 40)
            }
            
            entries.groupBy { it.reading }.forEach { (reading, readingEntries) ->
                renderHeadwordSection(termSection, reading, readingEntries)
                renderSensesForReading(termSection, readingEntries)
                
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

    private fun renderHeadwordSection(container: LinearLayout, reading: String, entries: List<com.holopengin.instantjpdict.data.DictionaryEntry>) {
        val headwordList = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 10)
        }
        val isKanjiEntry = entries.firstOrNull()?.let { it.onyomi != null || it.kunyomi != null } ?: false
        val kanjiVariants = entries.map { it.kanji }.distinct()

        if (isKanjiEntry) {
            kanjiVariants.forEach { kanji ->
                val entry = entries.find { it.kanji == kanji } ?: entries.first()
                val kanjiHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 5, 0, 5)
                }
                kanjiHeader.addView(TextView(this).apply {
                    text = kanji
                    setTextColor(Color.CYAN)
                    textSize = 48f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 30, 0)
                })
                val readingStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                entry.onyomi?.takeIf { it.isNotEmpty() }?.let { readingStack.addView(TextView(this).apply { text = "ON: ${it.replace(" ", "、")}"; setTextColor(Color.LTGRAY); textSize = 14f }) }
                entry.kunyomi?.takeIf { it.isNotEmpty() }?.let { readingStack.addView(TextView(this).apply { text = "KUN: ${it.replace(" ", "、")}"; setTextColor(Color.LTGRAY); textSize = 14f }) }
                kanjiHeader.addView(readingStack)
                headwordList.addView(kanjiHeader)
            }
        } else {
            val flow = FlowLayout(this).apply { setPadding(0, 5, 0, 5) }
            kanjiVariants.forEachIndexed { i, kanji ->
                flow.addView(createRubyView(kanji, reading))
                if (i < kanjiVariants.size - 1) {
                    flow.addView(TextView(this).apply { text = "、"; setTextColor(Color.GRAY); textSize = 24f; setPadding(5, 0, 5, 0) })
                }
            }
            headwordList.addView(flow)
        }
        container.addView(headwordList)
    }

    private fun renderSensesForReading(container: LinearLayout, entries: List<com.holopengin.instantjpdict.data.DictionaryEntry>) {
        var globalSenseNum = 1
        val groupSeenTags = mutableSetOf<String>()
        var currentGroupTags: List<String>? = null
        var currentGroupSenses = mutableListOf<Pair<Int, Any?>>()

        for (e in entries) {
            val definitionsJson = try { gson.fromJson<Any>(e.definitions, Any::class.java) } catch (ex: Exception) { e.definitions }
            val definitionsList = if (definitionsJson is List<*>) definitionsJson else listOf(definitionsJson)

            val metaTags = mutableListOf<String>()
            val senseTagsMap = mutableMapOf<Int, MutableList<String>>()
            e.jlpt?.takeIf { it.isNotEmpty() }?.let { metaTags.add("jlpt: N$it") }
            "grade:([^\\S]+)".toRegex().find(e.rules)?.groupValues?.get(1)?.let { metaTags.add("grade: $it") }

            val segments = e.rules.split(" | ")
            fun parseToMaps(s: String?) {
                var currentSense: Int? = null
                s?.split(" ")?.filter { it.isNotEmpty() }?.forEach { tag ->
                    val n = tag.toIntOrNull()
                    if (n != null) currentSense = n
                    else if (!tag.startsWith("grade:")) {
                        if (currentSense != null) senseTagsMap.getOrPut(currentSense!!) { mutableListOf() }.add(tag)
                        else metaTags.add(tag)
                    }
                }
            }
            parseToMaps(segments.getOrNull(0))
            parseToMaps(segments.getOrNull(2))

            val senseIdx = globalSenseNum++
            val tags = (metaTags + (senseTagsMap[1] ?: emptyList())).distinct()

            if (currentGroupTags == null || tags == currentGroupTags) {
                currentGroupTags = tags
                currentGroupSenses.add(senseIdx to definitionsList)
            } else {
                renderSenseGroup(container, currentGroupTags?.filter { groupSeenTags.add(it) }, currentGroupSenses)
                currentGroupTags = tags
                currentGroupSenses = mutableListOf(senseIdx to definitionsList)
            }
        }
        renderSenseGroup(container, currentGroupTags?.filter { groupSeenTags.add(it) }, currentGroupSenses)
    }

    private fun updateNeighborHighlights(panel: LinearLayout) {
        activeLineResults.forEachIndexed { lIdx, line ->
            val lineContainer = panel.findViewWithTag<LinearLayout>("line_neighbor_$lIdx") ?: return@forEachIndexed
            for (i in 0 until lineContainer.childCount) {
                val view = lineContainer.getChildAt(i) as? TextView ?: continue
                if (lIdx == currentTappedLineIdx && i == currentTappedCharIdxInLine) {
                    view.setBackgroundColor(android.graphics.Color.YELLOW)
                    view.setTextColor(android.graphics.Color.BLACK)
                } else {
                    view.setBackgroundColor(android.graphics.Color.argb(255, 65, 65, 65))
                    view.setTextColor(android.graphics.Color.WHITE)
                }
            }
        }
    }

    private fun centerNeighborScrollView(scrollView: View, lIdx: Int, cIdx: Int) {
        val panel = scrollView.findViewWithTag<LinearLayout>("neighbor_scroll_panel") ?: return
        val lineContainer = panel.findViewWithTag<LinearLayout>("line_neighbor_$lIdx") ?: return
        val targetView = lineContainer.getChildAt(cIdx) ?: return
        scrollView.post {
            if (scrollView is ScrollView) {
                val top = lineContainer.top + targetView.top
                scrollView.scrollTo(0, top - (scrollView.height / 2) + (targetView.height / 2))
            } else if (scrollView is HorizontalScrollView) {
                val left = lineContainer.left + targetView.left
                scrollView.scrollTo(left - (scrollView.width / 2) + (targetView.width / 2), 0)
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

        activeLineResults.forEachIndexed { lIdx, line ->
            val lineContainer = LinearLayout(this).apply {
                tag = "line_neighbor_$lIdx"
                orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            }
            panel.addView(lineContainer)
            if (line != null) {
                fillLineNeighborContainer(lineContainer, lIdx, line, isLandscape, rootLayout)
            }
        }

        scrollView.addView(panel); outerContainer.addView(scrollView)
        if (!skipCenter) centerNeighborScrollView(scrollView, tappedLIdx, tappedCIdx)
        return outerContainer
    }

    private fun toggleAlternativesPanel(rootLayout: FrameLayout, lIdx: Int, cIdx: Int, isLandscape: Boolean) {
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
        refreshCandidateList(candidateList, lIdx, cIdx, isLandscape, rootLayout)
        scrollView.addView(candidateList); mainLayout.addView(scrollView)
        
        // Find and scroll to selected
        scrollView.post {
            var selectedView: View? = null
            val line = activeLineResults.getOrNull(lIdx)
            val currentChar = line?.text?.getOrNull(cIdx)
            for (i in 0 until candidateList.childCount) {
                val v = candidateList.getChildAt(i) as? TextView ?: continue
                if (v.text.toString() == currentChar?.toString()) {
                    selectedView = v; break
                }
            }
            selectedView?.let { view -> if (isLandscape) scrollView.scrollTo(0, view.top) else (scrollView as HorizontalScrollView).scrollTo(view.left, 0) }
        }

        val stubView = TextView(this).apply { tag = "manual_input_stub"; text = "⌨"; setTextColor(android.graphics.Color.GRAY); textSize = estimatedTextSize; gravity = Gravity.CENTER; setBackgroundColor(android.graphics.Color.argb(255, 40, 40, 40)); setOnClickListener { showManualInput(lIdx, cIdx, rootLayout) } }
        mainLayout.addView(stubView, if (isLandscape) LinearLayout.LayoutParams(itemSize, 0, 1f).apply { setMargins(2, 2, 2, 2) } else LinearLayout.LayoutParams(0, itemSize, 1f).apply { setMargins(2, 2, 2, 2) })
        container.addView(mainLayout)
    }

    private fun refreshCandidateList(candidateList: LinearLayout, lIdx: Int, cIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout) {
        candidateList.removeAllViews()
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)

        val line = activeLineResults.getOrNull(lIdx)
        val alts = line?.alternatives?.getOrNull(cIdx) ?: emptyList()
        val currentChar = line?.text?.getOrNull(cIdx)
        
        alts.take(15).forEach { (altChar, _) ->
            val textView = TextView(this).apply {
                text = altChar.toString(); setTextColor(android.graphics.Color.WHITE); textSize = estimatedTextSize; gravity = Gravity.CENTER
                if (altChar == currentChar) { setBackgroundColor(android.graphics.Color.YELLOW); setTextColor(android.graphics.Color.BLACK) }
                else setBackgroundColor(android.graphics.Color.argb(255, 85, 85, 85))
                
                if (isLandscape) {
                    textLocale = java.util.Locale.JAPANESE
                    fontFeatureSettings = "'vert' 1"
                }

                setOnClickListener { 
                    if (altChar == currentChar) {
                        toggleAlternativesPanel(rootLayout, lIdx, cIdx, isLandscape)
                    } else {
                        replaceCharacter(lIdx, cIdx, altChar, rootLayout)
                    }
                }
            }
            candidateList.addView(textView, LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) })
        }
    }

    private fun updateAlternativesPanelContent(container: FrameLayout, lIdx: Int, cIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout) {
        val candidateList = container.findViewWithTag<LinearLayout>("candidate_list_panel") ?: return
        refreshCandidateList(candidateList, lIdx, cIdx, isLandscape, rootLayout)
        
        // Update manual input stub too
        val stub = container.findViewWithTag<TextView>("manual_input_stub")
        stub?.setOnClickListener { showManualInput(lIdx, cIdx, rootLayout) }

        val scrollView = candidateList.parent as? View ?: return
        scrollView.post {
            var selectedView: View? = null
            val line = activeLineResults.getOrNull(lIdx)
            val currentChar = line?.text?.getOrNull(cIdx)
            for (i in 0 until candidateList.childCount) {
                val v = candidateList.getChildAt(i) as? TextView ?: continue
                if (v.text.toString() == currentChar?.toString()) {
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
        val line = activeLineResults[lIdx] ?: return
        val charArray = line.text.toCharArray()
        charArray[cIdx] = newChar
        line.text = String(charArray)
        
        updateGlobalData()
        
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
        val line = activeLineResults[lIdx] ?: return
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
        lastManualInputCloseTime = System.currentTimeMillis()
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

    private fun renderSenseGroup(container: LinearLayout, tags: List<String>?, senses: List<Pair<Int, Any?>>) {
        if (senses.isEmpty()) return
        
        val isForms = tags?.any { it.equals("Forms", ignoreCase = true) || it.equals("Other forms", ignoreCase = true) } == true

        if (!tags.isNullOrEmpty()) {
            val header = FlowLayout(this).apply { setPadding(20, 15, 0, 5) }
            tags.forEach { header.addView(createTagView(it)) }
            container.addView(header)
        }
        
        if (isForms) {
            val table = LinearLayout(this).apply { 
                orientation = LinearLayout.VERTICAL
                setPadding(30, 5, 10, 5)
            }
            senses.forEach { (_, content) ->
                val row = FlowLayout(this).apply { setPadding(0, 5, 0, 5) }
                renderDefinition(row, content, false)
                table.addView(row)
            }
            container.addView(table)
        } else {
            senses.forEach { (idx, content) ->
                val senseLayout = LinearLayout(this).apply { 
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(30, 5, 10, 5)
                }
                senseLayout.addView(TextView(this).apply {
                    text = "$idx. "
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setPadding(0, 0, 10, 0)
                })
                
                val contentContainer = FlowLayout(this).apply {
                    setPadding(0, 0, 0, 15)
                }
                renderDefinition(contentContainer, content, false)
                senseLayout.addView(contentContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                
                container.addView(senseLayout)
            }
        }
    }

    private fun getAttr(data: Map<*, *>, key: String) = 
        (data["data"] as? Map<*, *>)?.get(key) as? String ?: data["data-$key"] as? String ?: data[key] as? String

    private fun isExample(data: Map<*, *>) = 
        data["type"] == "sentence" || data.containsKey("japanese") || getAttr(data, "content")?.let { it == "examples" || it == "example-sentence" } == true

    private fun isBlock(item: Any?): Boolean {
        val data = item as? Map<*, *> ?: return false
        if (isExample(data)) return true
        val scClass = getAttr(data, "class")
        val scContent = getAttr(data, "content")
        val tag = data["tag"] as? String
        
        return scClass == "extra-box" || scContent == "info-gloss" || scContent == "sense-note" || 
               scContent == "lang-source" || scContent == "xref" || scContent == "antonym" ||
               tag == "table" || 
               ((tag == "ul" || tag == "ol") && scContent !in listOf("glossary", "infoGlossary", "sourceLanguages"))
    }

    private fun renderDefinition(container: ViewGroup, data: Any?, prevWasInline: Boolean): Boolean {
        var currentlyInline = prevWasInline
        when (data) {
            is String -> {
                val replaced = data.replace("; ", "\n").replace(";", "\n")
                val trimmed = replaced.trim()
                if (trimmed.isEmpty()) return currentlyInline
                
                if (currentlyInline && container is FlowLayout) {
                    container.addView(TextView(this).apply { text = ", "; setTextColor(Color.WHITE); textSize = 15f; includeFontPadding = false })
                }
                
                container.addView(TextView(this).apply { text = trimmed; setTextColor(Color.WHITE); textSize = 15f; includeFontPadding = false })
                return true
            }
            is List<*> -> {
                data.forEach { item ->
                    if (currentlyInline && isBlock(item) && container is FlowLayout) {
                        val punctuation = if (item is Map<*, *> && isExample(item)) ". " else " "
                        container.addView(TextView(this).apply { text = punctuation; setTextColor(Color.WHITE); textSize = 15f; includeFontPadding = false })
                        currentlyInline = false
                    }
                    currentlyInline = renderDefinition(container, item, currentlyInline)
                }
                return currentlyInline
            }
            is Map<*, *> -> {
                val tag = data["tag"] as? String
                val content = data["content"] ?: data["list"]
                val scContent = getAttr(data, "content")
                val scClass = getAttr(data, "class")

                when {
                    isExample(data) -> {
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
                        val jp = (data["japanese"] as? String) ?: (content as? String)
                        if (jp != null) {
                            box.addView(TextView(this).apply { text = jp; setTextColor(Color.WHITE); textSize = 16f; setPadding(0, 0, 0, 10) })
                            (data["english"] as? String)?.let { en -> box.addView(TextView(this).apply { text = en; setTextColor(Color.LTGRAY); textSize = 14f }) }
                        } else renderDefinition(box, content, false)
                        container.addView(box)
                        return false
                    }
                    scClass == "tag" || (tag == "span" && scContent?.endsWith("-info") == true) -> {
                        container.addView(createTagView(content?.toString() ?: ""))
                        return false
                    }
                    tag == "ruby" -> {
                        val rubyList = content as? List<*>
                        if (rubyList != null && rubyList.size >= 2) {
                            if (currentlyInline && container is FlowLayout) {
                                container.addView(TextView(this).apply { text = ", "; setTextColor(Color.WHITE); textSize = 15f; includeFontPadding = false })
                            }
                            container.addView(createRubyView(rubyList[0].toString(), (rubyList[1] as? Map<*, *>)?.get("content")?.toString() ?: "", isMini = true).apply { setPadding(0, 0, 15, 0) })
                            return true
                        }
                    }
                    tag == "table" -> {
                        val table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 10); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10; bottomMargin = 10 } }
                        renderDefinition(table, content, false); container.addView(table); return false
                    }
                    tag == "tr" -> {
                        val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
                        renderDefinition(tr, content, false); container.addView(tr); return false
                    }
                    tag == "td" || tag == "th" -> {
                        val cell = FlowLayout(this).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); setPadding(10, 5, 10, 5); background = GradientDrawable().apply { setStroke(1, android.graphics.Color.argb(80, 255, 255, 255)) }; minimumHeight = (24 * resources.displayMetrics.density).toInt() }
                        renderDefinition(cell, content, false); container.addView(cell); return false
                    }
                    tag == "ul" || tag == "ol" -> {
                        if (scContent in listOf("glossary", "infoGlossary", "sourceLanguages")) return renderDefinition(container, content, currentlyInline)
                        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setTag(scContent); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 10) } }
                        renderDefinition(block, content, false); container.addView(block); return false
                    }
                    content != null -> return renderDefinition(container, content, currentlyInline)
                }
            }
        }
        return currentlyInline
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (screenshotOverlay == null) return super.onKeyEvent(event)
        val keyEvent = event ?: return super.onKeyEvent(event)
        Log.d("OcrAccessibilityService", "onKeyEvent: keyCode=${keyEvent.keyCode}, action=${keyEvent.action}")
        if (handleGamepad(keyEvent)) return true
        return super.onKeyEvent(keyEvent)
    }

    private fun handleGamepad(event: KeyEvent): Boolean {
        val root = screenshotOverlay as? FrameLayout ?: return false
        val dictionaryRoot = root.findViewWithTag<LinearLayout>("correction_ui_root")
        val altContainer = root.findViewWithTag<FrameLayout>("alternatives_container")
        val isAltOpen = (altContainer?.childCount ?: 0) > 0
        val isDictionaryOpen = dictionaryRoot != null
        val isManualInputOpen = root.findViewWithTag<View>("manual_input_blocker") != null
        
        if (isManualInputOpen) return false
        
        val prefs = getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)
        val layoutSwap = prefs.getBoolean("layout_swap", false)

        val keyCode = event.keyCode
        val isDpad = keyCode in listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN
        )

        val isHandledKey = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, 
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BACK -> true
            else -> false
        }
        
        if (!isHandledKey) return false
        
        if (event.action == KeyEvent.ACTION_UP) {
            if (isDpad && keyCode == currentRepeatingKeyCode) {
                stopRepeat()
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true

        if (isDpad && keyCode != currentRepeatingKeyCode) {
            startRepeat(keyCode)
        }

        // Map buttons based on layout setting
        val mappedEnter: Int
        val mappedBack: Int
        if (layoutSwap) {
            // Nintendo: B=Enter, A=Back
            mappedEnter = KeyEvent.KEYCODE_BUTTON_B
            mappedBack = KeyEvent.KEYCODE_BUTTON_A
        } else {
            // Xbox: A=Enter, B=Back
            mappedEnter = KeyEvent.KEYCODE_BUTTON_A
            mappedBack = KeyEvent.KEYCODE_BUTTON_B
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, mappedBack, KeyEvent.KEYCODE_ESCAPE -> {
                if (isAltOpen) {
                    toggleAlternativesPanel(root, currentTappedLineIdx, currentTappedCharIdxInLine, root.width > root.height)
                } else if (isDictionaryOpen) {
                    root.removeView(dictionaryRoot)
                    resetHighlights()
                    updateCursor()
                } else {
                    hideScreenshotOverlay()
                }
                return true
            }
            mappedEnter, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (isAltOpen) {
                    toggleAlternativesPanel(root, currentTappedLineIdx, currentTappedCharIdxInLine, root.width > root.height)
                } else if (isDictionaryOpen) {
                    toggleAlternativesPanel(root, currentTappedLineIdx, currentTappedCharIdxInLine, root.width > root.height)
                } else {
                    if (currentTappedLineIdx != -1 && currentTappedCharIdxInLine != -1) {
                        performLookup(currentTappedLineIdx, currentTappedCharIdxInLine, root)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, 
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                executeNavigation(keyCode, root, isAltOpen, isDictionaryOpen)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (isDictionaryOpen) {
                    scrollDictionary(event.keyCode, root)
                } else {
                    val direction = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1
                    navigateLinesWithDirection(direction)
                }
                return true
            }
        }
        return false
    }

    private fun startRepeat(keyCode: Int) {
        stopRepeat()
        currentRepeatingKeyCode = keyCode
        val prefs = getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)
        val delay = prefs.getInt("repeat_delay", 500).toLong()
        val rate = prefs.getInt("repeat_rate", 20)
        val interval = (1000L / rate).coerceAtLeast(16L)

        repeatJob = serviceScope.launch {
            kotlinx.coroutines.delay(delay)
            while (isActive && currentRepeatingKeyCode == keyCode) {
                val root = screenshotOverlay as? FrameLayout ?: break
                val dictionaryRoot = root.findViewWithTag<LinearLayout>("correction_ui_root")
                val altContainer = root.findViewWithTag<FrameLayout>("alternatives_container")
                val isAltOpen = (altContainer?.childCount ?: 0) > 0
                val isDictionaryOpen = dictionaryRoot != null
                
                executeNavigation(keyCode, root, isAltOpen, isDictionaryOpen)
                kotlinx.coroutines.delay(interval)
            }
        }
    }

    private fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
        currentRepeatingKeyCode = 0
    }

    private fun executeNavigation(keyCode: Int, root: FrameLayout, isAltOpen: Boolean, isDictionaryOpen: Boolean) {
        if (isAltOpen) {
            navigateAlternatives(keyCode, root)
        } else if (isDictionaryOpen) {
            navigateDictionarySelection(keyCode, root)
        } else {
            navigateOverlay(keyCode)
        }
    }

    private fun navigateOverlay(keyCode: Int) {
        if (activeLineResults.isEmpty()) return
        if (currentTappedLineIdx == -1) { 
            updateCursor() 
            if (currentTappedLineIdx == -1) return
        }
        
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val line = activeLineResults.getOrNull(currentTappedLineIdx) ?: return
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (currentTappedCharIdxInLine > 0) {
                        currentTappedCharIdxInLine--
                    } else if (currentTappedLineIdx > 0) {
                        for (i in currentTappedLineIdx - 1 downTo 0) {
                            val prevLine = activeLineResults[i]
                            if (prevLine != null && prevLine.text.isNotEmpty()) {
                                currentTappedLineIdx = i
                                currentTappedCharIdxInLine = prevLine.text.length - 1
                                break
                            }
                        }
                    }
                } else {
                    if (currentTappedCharIdxInLine < line.text.length - 1) {
                        currentTappedCharIdxInLine++
                    } else if (currentTappedLineIdx < activeLineResults.size - 1) {
                        for (i in currentTappedLineIdx + 1 until activeLineResults.size) {
                            val nextLine = activeLineResults[i]
                            if (nextLine != null && nextLine.text.isNotEmpty()) {
                                currentTappedLineIdx = i
                                currentTappedCharIdxInLine = 0
                                break
                            }
                        }
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                navigateLinesWithDirection(direction)
            }
        }
        updateCursor()
    }

    private fun navigateLinesWithDirection(direction: Int) {
        if (currentTappedLineIdx == -1) { updateCursor(); if (currentTappedLineIdx == -1) return }
        val currentLine = activeLineResults[currentTappedLineIdx] ?: return
        val currentCharBox = currentLine.charBoxes[currentTappedCharIdxInLine]
        val centerX = currentCharBox.centerX()
        val centerY = currentCharBox.centerY()
        
        var nextLineIdx = currentTappedLineIdx + direction
        while (nextLineIdx in activeLineResults.indices) {
            val nextLine = activeLineResults[nextLineIdx]
            if (nextLine != null && nextLine.text.isNotEmpty()) {
                var minInfo: Pair<Int, Double>? = null
                for (i in nextLine.charBoxes.indices) {
                    val box = nextLine.charBoxes[i]
                    val dx = (box.centerX() - centerX).toDouble()
                    val dy = (box.centerY() - centerY).toDouble()
                    val dist = dx * dx + dy * dy
                    if (minInfo == null || dist < minInfo.second) minInfo = i to dist
                }
                if (minInfo != null) {
                    currentTappedLineIdx = nextLineIdx
                    currentTappedCharIdxInLine = minInfo.first
                    updateCursor()
                    return
                }
            }
            nextLineIdx += direction
        }
    }

    private fun handleJoystick(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false

        val x = getCenteredAxis(event, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X)
        val y = getCenteredAxis(event, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y)

        fun detectClick(curr: Float, last: Float): Int {
            if (last < 0.5f && curr >= 0.5f) return 1
            if (last > -0.5f && curr <= -0.5f) return -1
            return 0
        }
        
        val clickX = detectClick(x, joystickLastX)
        val clickY = detectClick(y, joystickLastY)
        
        joystickLastX = x
        joystickLastY = y
        
        if (clickX != 0 || clickY != 0) {
            val keyCode = when {
                clickX == 1 -> KeyEvent.KEYCODE_DPAD_RIGHT
                clickX == -1 -> KeyEvent.KEYCODE_DPAD_LEFT
                clickY == 1 -> KeyEvent.KEYCODE_DPAD_DOWN
                clickY == -1 -> KeyEvent.KEYCODE_DPAD_UP
                else -> 0
            }
            if (keyCode != 0) {
                // Synthesize Dpad events that the system's onKeyEvent will pick up
                // or just call handleGamepad directly with appropriate key event actions
                handleGamepad(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                handleGamepad(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
        }
        
        return true
    }

    private fun getCenteredAxis(event: MotionEvent, axis1: Int, axis2: Int): Float {
        val range1 = event.device?.getMotionRange(axis1, event.source)
        val v1 = if (range1 != null) event.getAxisValue(axis1) else 0f
        val range2 = event.device?.getMotionRange(axis2, event.source)
        val v2 = if (range2 != null) event.getAxisValue(axis2) else 0f
        
        val v = if (abs(v1) > abs(v2)) v1 else v2
        return if (abs(v) > 0.3f) v else 0f
    }
    private fun navigateDictionarySelection(keyCode: Int, root: FrameLayout) {
        val isLandscape = root.width > root.height
        val next = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isLandscape) 1 else 0
            KeyEvent.KEYCODE_DPAD_UP -> if (isLandscape) -1 else 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!isLandscape) 1 else 0
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!isLandscape) -1 else 0
            else -> 0
        }
        if (next == 0) return
        val line = activeLineResults.getOrNull(currentTappedLineIdx) ?: return
        var newCharIdx = currentTappedCharIdxInLine + next
        var newLineIdx = currentTappedLineIdx
        if (newCharIdx < 0) {
            for (i in currentTappedLineIdx - 1 downTo 0) {
                val prevLine = activeLineResults[i]
                if (prevLine != null && prevLine.text.isNotEmpty()) {
                    newLineIdx = i; newCharIdx = prevLine.text.length - 1; break
                }
            }
        } else if (newCharIdx >= line.text.length) {
            for (i in currentTappedLineIdx + 1 until activeLineResults.size) {
                val nextLine = activeLineResults[i]
                if (nextLine != null && nextLine.text.isNotEmpty()) {
                    newLineIdx = i; newCharIdx = 0; break
                }
            }
        }
        if (newLineIdx != currentTappedLineIdx || newCharIdx != currentTappedCharIdxInLine) {
            performLookup(newLineIdx, newCharIdx, root)
        }
    }

    private fun scrollDictionary(keyCode: Int, root: FrameLayout) {
        val dictPanel = root.findViewWithTag<LinearLayout>("dictionary_content_container") ?: return
        val scrollView = dictPanel.findViewWithTag<View>("dictionary_scroll_view") as? ScrollView ?: return
        val amount = (100 * resources.displayMetrics.density).toInt()
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R1) scrollView.smoothScrollBy(0, amount)
        else scrollView.smoothScrollBy(0, -amount)
    }

    private fun navigateAlternatives(keyCode: Int, root: FrameLayout) {
        val isLandscape = root.width > root.height
        val diff = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isLandscape) 1 else 0
            KeyEvent.KEYCODE_DPAD_UP -> if (isLandscape) -1 else 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!isLandscape) 1 else 0
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!isLandscape) -1 else 0
            else -> 0
        }
        if (diff == 0) return
        val container = root.findViewWithTag<FrameLayout>("alternatives_container") ?: return
        val candidateList = container.findViewWithTag<LinearLayout>("candidate_list_panel") ?: return
        val line = activeLineResults.getOrNull(currentTappedLineIdx) ?: return
        val currentChar = line.text.getOrNull(currentTappedCharIdxInLine)
        var currentIndex = -1
        for (i in 0 until candidateList.childCount) {
            val v = candidateList.getChildAt(i) as? TextView
            if (v?.text.toString() == currentChar?.toString()) { currentIndex = i; break }
        }
        if (currentIndex != -1) {
            val newIndex = (currentIndex + diff).coerceIn(0, candidateList.childCount - 1)
            if (newIndex != currentIndex) {
                val newView = candidateList.getChildAt(newIndex) as? TextView
                newView?.text?.getOrNull(0)?.let { replaceCharacter(currentTappedLineIdx, currentTappedCharIdxInLine, it, root) }
            }
        }
    }

    private fun updateCursor() {
        val root = screenshotOverlay as? FrameLayout ?: return
        val contentContainer = root.findViewWithTag<FrameLayout>("content_container") ?: return
        if (currentTappedLineIdx == -1 || currentTappedCharIdxInLine == -1) {
            for (i in activeLineResults.indices) {
                val line = activeLineResults[i]
                if (line != null && line.text.isNotEmpty()) {
                    currentTappedLineIdx = i; currentTappedCharIdxInLine = 0; break
                }
            }
        }
        val line = activeLineResults.getOrNull(currentTappedLineIdx)
        val charBox = line?.charBoxes?.getOrNull(currentTappedCharIdxInLine)
        if (charBox != null) {
            if (cursorView == null) {
                cursorView = View(this).apply {
                    background = GradientDrawable().apply {
                        setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
                        cornerRadius = 4f
                    }
                    tag = "cursor_view"
                }
                contentContainer.addView(cursorView)
            }
            val lp = cursorView?.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(0, 0)
            lp.width = charBox.width() + (4 * resources.displayMetrics.density).toInt()
            lp.height = charBox.height() + (4 * resources.displayMetrics.density).toInt()
            lp.leftMargin = charBox.left - (2 * resources.displayMetrics.density).toInt()
            lp.topMargin = charBox.top - (2 * resources.displayMetrics.density).toInt()
            cursorView?.layoutParams = lp
            cursorView?.visibility = View.VISIBLE
            cursorView?.bringToFront()
        } else cursorView?.visibility = View.GONE
    }

    private fun centerWordInVisibleArea(root: FrameLayout, lineIdx: Int, charIdx: Int, wordLen: Int) {
        val contentContainer = root.findViewWithTag<FrameLayout>("content_container") ?: return
        val line = activeLineResults.getOrNull(lineIdx) ?: return
        val wordBoxes = line.charBoxes.subList(charIdx, (charIdx + wordLen).coerceAtMost(line.charBoxes.size))
        if (wordBoxes.isEmpty()) return
        val wordRect = Rect(wordBoxes[0])
        wordBoxes.forEach { wordRect.union(it) }
        val rootWidth = root.width.toFloat()
        val rootHeight = root.height.toFloat()
        if (rootWidth == 0f || rootHeight == 0f) return
        val isLandscape = rootWidth > rootHeight
        if (root.findViewWithTag<View>("correction_ui_root") == null) return
        val panelWidth = if (isLandscape) (rootWidth * 0.4f) else rootWidth
        val panelHeight = if (isLandscape) rootHeight else (rootHeight * 0.4f)
        val visibleCenterX: Float
        val visibleCenterY: Float
        if (isLandscape) {
            val isEnd = lastLandscapeGravity == Gravity.END
            visibleCenterX = if (isEnd) (rootWidth - panelWidth) / 2f else panelWidth + (rootWidth - panelWidth) / 2f
            visibleCenterY = rootHeight / 2f
        } else {
            val isBottom = lastPortraitGravity == Gravity.BOTTOM
            visibleCenterX = rootWidth / 2f
            visibleCenterY = if (isBottom) (rootHeight - panelHeight) / 2f else panelHeight + (rootHeight - panelHeight) / 2f
        }
        currentTransX = visibleCenterX - wordRect.centerX() * currentScale
        currentTransY = visibleCenterY - wordRect.centerY() * currentScale
        contentContainer.translationX = currentTransX
        contentContainer.translationY = currentTransY
    }

    private fun hideScreenshotOverlay() {
        ocrJob?.cancel()
        ocrJob = null
        val root = screenshotOverlay ?: return
        (floatingView?.parent as? android.view.ViewGroup)?.removeView(floatingView)
        if (root.isAttachedToWindow) try { windowManager?.removeViewImmediate(root) } catch (e: Exception) { Log.e("OcrAccessibilityService", "Error removing overlay", e) }
        screenshotOverlay = null; screenshotBitmap = null; floatingView?.visibility = View.VISIBLE
        activeLineBoxes = emptyList()
        try { windowManager?.updateViewLayout(floatingView, floatingParams) } catch (e: Exception) { Log.e("OcrAccessibilityService", "Error restoring button", e) }
        boxViews.clear(); textViews.clear()
        cursorView = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (screenshotOverlay == null) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventPackage = event.packageName?.toString()
            if (eventPackage == null || eventPackage == packageName) return
            val root = screenshotOverlay as? FrameLayout
            if (root?.findViewWithTag<View>("manual_input_blocker") != null) return
            if (System.currentTimeMillis() - lastManualInputCloseTime < 1000) return
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

    private fun getGlobalIdx(lineIdx: Int, charIdxInLine: Int): Int {
        var count = 0
        for (i in 0 until lineIdx) {
            count += activeLineResults[i]?.text?.length ?: 0
        }
        return count + charIdxInLine
    }

    private fun getCoordsFromGlobalIdx(globalIdx: Int): Pair<Int, Int>? {
        var count = 0
        for (lineIdx in activeLineResults.indices) {
            val line = activeLineResults[lineIdx] ?: continue
            if (globalIdx < count + line.text.length) {
                return Pair(lineIdx, globalIdx - count)
            }
            count += line.text.length
        }
        return null
    }

    private fun updateGlobalData() {
        activeAllChars.clear()
        activeAllAlternatives.clear()
        activeLineResults.forEach { line ->
            line?.let {
                it.text.forEach { char -> activeAllChars.add(char.toString()) }
                it.alternatives.forEach { alts -> activeAllAlternatives.add(alts) }
            }
        }
    }
}
