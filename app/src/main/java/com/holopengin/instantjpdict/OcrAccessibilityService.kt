package com.holopengin.instantjpdict

import android.accessibilityservice.AccessibilityService
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
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.ContextCompat
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
    private lateinit var ocrEngine: OcrEngine
    private lateinit var deinflector: Deinflector
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ocrJob: kotlinx.coroutines.Job? = null
    
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

    private val borderDrawable by lazy {
        GradientDrawable().apply {
            setColor(android.graphics.Color.argb(100, 0, 0, 0))
            cornerRadius = 4f
        }
    }

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
            text = "辞典"
            setTextColor(Color.CYAN)
            typeface = ResourcesCompat.getFont(this@OcrAccessibilityService, R.font.yujimai_regular)
            background = getDrawable(R.drawable.ic_logo_ring)
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

        var scale = 1f
        var transX = 0f
        var transY = 0f
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
                val s = scale
                val tx = transX
                val ty = transY
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
                    val oldScale = scale
                    scale = (scale * detector.scaleFactor).coerceIn(1f, 5f)
                    val factor = scale / oldScale

                    contentContainer.scaleX = scale
                    contentContainer.scaleY = scale

                    // Zoom around the focus point. 
                    // Note: transX/Y already include the focus shift pan from the current event
                    // because we update them in the touch listener before calling gestureDetector.onTouchEvent
                    transX = detector.focusX - (detector.focusX - transX) * factor
                    transY = detector.focusY - (detector.focusY - transY) * factor
                    
                    contentContainer.translationX = transX
                    contentContainer.translationY = transY
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
                            transX += dx
                            transY += dy
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
                        contentContainer.translationX = transX
                        contentContainer.translationY = transY
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
            text = "辞典"
            setTextColor(Color.CYAN)
            typeface = ResourcesCompat.getFont(this@OcrAccessibilityService, R.font.yujimai_regular)
            background = getDrawable(R.drawable.ic_logo_ring)
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
            existingRoot.bringToFront()
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
            val termSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 40) }
            val entriesByReading = entries.groupBy { it.reading }

            for ((reading, readingEntries) in entriesByReading) {
                val headwordList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 10) }
                val firstEntry = readingEntries.first()
                val isKanjiEntry = firstEntry.onyomi != null || firstEntry.kunyomi != null
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
                    val flow = FlowLayout(this).apply { setPadding(0, 5, 0, 5) }
                    kanjiVariants.forEachIndexed { i, kanji ->
                        flow.addView(createRubyView(kanji, reading))
                        if (i < kanjiVariants.size - 1) {
                            flow.addView(TextView(this).apply { text = "、"; setTextColor(android.graphics.Color.GRAY); textSize = 24f; setPadding(5, 0, 5, 0) })
                        }
                    }
                    headwordList.addView(flow)
                }
                termSection.addView(headwordList)

                var globalSenseNum = 1
                val groupSeenTags = mutableSetOf<String>()
                var currentGroupTags: List<String>? = null
                var currentGroupSenses = mutableListOf<Pair<Int, Any?>>()

                for (e in readingEntries) {
                    val definitionsJson = try { gson.fromJson<Any>(e.definitions, Any::class.java) } catch (ex: Exception) { e.definitions }
                    val definitionsList = if (definitionsJson is List<*>) definitionsJson else listOf(definitionsJson)

                    val senseTagsMap = mutableMapOf<Int, MutableList<String>>()
                    val metaTags = mutableListOf<String>()
                    e.jlpt?.takeIf { it.isNotEmpty() }?.let { metaTags.add("jlpt: N$it") }
                    "grade:([^\\S]+)".toRegex().find(e.rules)?.groupValues?.get(1)?.let { metaTags.add("grade: $it") }

                    val segments = e.rules.split(" | ")
                    fun parseTags(tagStr: String?) {
                        if (tagStr == null) return
                        var currentSense: Int? = null
                        tagStr.split(" ").filter { it.isNotEmpty() }.forEach { tag ->
                            val n = tag.toIntOrNull()
                            if (n != null) currentSense = n
                            else if (!tag.startsWith("grade:")) {
                                if (currentSense != null) senseTagsMap.getOrPut(currentSense!!) { mutableListOf() }.add(tag)
                                else metaTags.add(tag)
                            }
                        }
                    }
                    parseTags(segments.getOrNull(0))
                    parseTags(segments.getOrNull(2))

                    val senseIdx = globalSenseNum++
                    val tags = metaTags + (senseTagsMap[1] ?: emptyList())
                    val distinctTags = tags.distinct()

                    if (currentGroupTags == null) {
                        currentGroupTags = distinctTags
                        currentGroupSenses.add(senseIdx to definitionsList)
                    } else if (distinctTags == currentGroupTags) {
                        currentGroupSenses.add(senseIdx to definitionsList)
                    } else {
                        val filtered = currentGroupTags?.filter { groupSeenTags.add(it) }
                        renderSenseGroup(termSection, filtered, currentGroupSenses)
                        currentGroupTags = distinctTags
                        currentGroupSenses = mutableListOf(senseIdx to definitionsList)
                    }
                }
                val filtered = currentGroupTags?.filter { groupSeenTags.add(it) }
                renderSenseGroup(termSection, filtered, currentGroupSenses)

                termSection.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 20 }; setBackgroundColor(android.graphics.Color.DKGRAY); alpha = 0.3f })
            }
            scrollContent.addView(termSection)
        }
        scrollView.addView(scrollContent); container.addView(scrollView)
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
        if (params != null) { params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv(); params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE; windowManager?.updateViewLayout(screenshotOverlay, params) }
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
        val params = screenshotOverlay?.layoutParams as? WindowManager.LayoutParams
        if (params != null) { params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windowManager?.updateViewLayout(screenshotOverlay, params) }
    }

    private fun createTagView(tag: String, category: String = "general"): View {
        val color = when { 
            category == "pos" || tag.startsWith("v") || tag == "adj-i" || tag == "adj-na" -> android.graphics.Color.parseColor("#3a5a7a")
            tag == "n" || tag == "adv" || tag == "pn" -> android.graphics.Color.parseColor("#3a7a5a")
            category == "meta" || tag.startsWith("jlpt") || tag.startsWith("grade") || tag == "★" -> android.graphics.Color.parseColor("#7a3a3a")
            else -> android.graphics.Color.parseColor("#444444") 
        }
        return TextView(this).apply { 
            text = tag
            setTextColor(android.graphics.Color.WHITE)
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
                setTextColor(android.graphics.Color.CYAN)
                textSize = if (isMini) 15f else 32f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
            }
        }
        val rubyItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isBaselineAligned = true
        }
        // Reading (Furigana)
        rubyItem.addView(TextView(this).apply {
            text = reading
            setTextColor(android.graphics.Color.LTGRAY)
            textSize = if (isMini) 9f else 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        })
        // Term (Kanji)
        rubyItem.addView(TextView(this).apply {
            text = term
            setTextColor(android.graphics.Color.CYAN)
            textSize = if (isMini) 15f else 32f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        })
        rubyItem.baselineAlignedChildIndex = 1
        return rubyItem
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
                    setTextColor(android.graphics.Color.WHITE)
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

    private fun getAttr(data: Map<*, *>, key: String): String? {
        val attrMap = data["data"] as? Map<*, *>
        return attrMap?.get(key) as? String ?: data["data-$key"] as? String ?: data[key] as? String
    }

    private fun isExample(data: Map<*, *>): Boolean {
        if (data["type"] == "sentence" || data.containsKey("japanese")) return true
        val scContent = getAttr(data, "content")
        return scContent == "examples" || scContent == "example-sentence"
    }

    private fun isBlock(item: Any?): Boolean {
        if (item !is Map<*, *>) return false
        if (isExample(item)) return true
        val scClass = getAttr(item, "class")
        val scContent = getAttr(item, "content")
        val tag = item["tag"] as? String
        if (scClass == "extra-box" || scContent == "info-gloss" || scContent == "sense-note" || scContent == "lang-source" || scContent == "xref" || scContent == "antonym") return true
        if (tag == "ul" || tag == "ol") return !(scContent == "glossary" || scContent == "infoGlossary" || scContent == "sourceLanguages")
        if (tag == "table") return true
        return false
    }

    private fun renderDefinition(container: ViewGroup, data: Any?, prevWasInline: Boolean): Boolean {
        var currentlyInline = prevWasInline
        when (data) {
            is String -> {
                val trimmed = data.trim()
                if (trimmed.isEmpty()) return currentlyInline
                
                if (currentlyInline && container is FlowLayout) {
                    container.addView(TextView(this).apply {
                        text = ", "
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 15f
                        includeFontPadding = false
                    })
                }
                
                container.addView(TextView(this).apply { 
                    text = trimmed
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 15f
                    includeFontPadding = false
                })
                return true
            }
            is List<*> -> {
                data.forEach { item ->
                    val isEx = if (item is Map<*, *>) isExample(item) else false
                    val isBlk = if (item is Map<*, *>) isBlock(item) else false

                    if (currentlyInline && isBlk && container is FlowLayout) {
                        val punctuation = if (isEx) ". " else " "
                        container.addView(TextView(this).apply {
                            text = punctuation
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 15f
                            includeFontPadding = false
                        })
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

                if (isExample(data)) {
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(30, 15, 30, 25)
                        background = GradientDrawable().apply {
                            setColor(android.graphics.Color.argb(30, 255, 255, 255))
                            setStroke(3, android.graphics.Color.argb(220, 255, 255, 255))
                            cornerRadius = 12f
                        }
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, 15, 0, 15)
                        }
                    }
                    
                    val jp = (data["japanese"] as? String) ?: (if (content is String) content else null)
                    val en = data["english"] as? String
                    
                    if (jp != null) {
                        box.addView(TextView(this).apply { text = jp; setTextColor(android.graphics.Color.WHITE); textSize = 16f; setPadding(0, 0, 0, 10) })
                        if (!en.isNullOrEmpty()) box.addView(TextView(this).apply { text = en; setTextColor(android.graphics.Color.LTGRAY); textSize = 14f })
                    } else {
                        renderDefinition(box, content, false)
                    }
                    container.addView(box)
                    return false
                }

                if (scClass == "tag" || (tag == "span" && scContent?.endsWith("-info") == true)) {
                    container.addView(createTagView(content?.toString() ?: ""))
                    return false
                }

                if (tag == "ruby") {
                    val rubyList = content as? List<*>
                    if (rubyList != null && rubyList.size >= 2) {
                        if (currentlyInline && container is FlowLayout) {
                            container.addView(TextView(this).apply { text = ", "; setTextColor(android.graphics.Color.WHITE); textSize = 15f; includeFontPadding = false })
                        }
                        val base = rubyList[0].toString()
                        val rt = (rubyList[1] as? Map<*, *>)?.get("content")?.toString() ?: ""
                        container.addView(createRubyView(base, rt, isMini = true).apply { setPadding(0, 0, 15, 0) })
                        return true
                    }
                }

                if (tag == "table") {
                    val table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 10); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10; bottomMargin = 10 } }
                    renderDefinition(table, content, false); container.addView(table); return false
                }
                if (tag == "tr") {
                    val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
                    renderDefinition(tr, content, false); container.addView(tr); return false
                }
                if (tag == "td" || tag == "th") {
                    val cell = FlowLayout(this).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); setPadding(10, 5, 10, 5); background = GradientDrawable().apply { setStroke(1, android.graphics.Color.argb(80, 255, 255, 255)) }; minimumHeight = (24 * resources.displayMetrics.density).toInt() }
                    renderDefinition(cell, content, false); container.addView(cell); return false
                }

                if (tag == "ul" || tag == "ol") {
                    if (scContent == "glossary" || scContent == "infoGlossary" || scContent == "sourceLanguages") {
                        return renderDefinition(container, content, currentlyInline)
                    }
                    val block = LinearLayout(this).apply { 
                        orientation = LinearLayout.VERTICAL
                        setTag(scContent)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, 10, 0, 10)
                        }
                    }
                    renderDefinition(block, content, false)
                    container.addView(block)
                    return false
                }

                if (content != null) return renderDefinition(container, content, currentlyInline)
            }
        }
        return currentlyInline
    }

    private fun isExampleSentence(item: Any?): Boolean {
        if (item is Map<*, *>) return isExample(item)
        if (item is List<*> && item.isNotEmpty()) return isExampleSentence(item[0])
        return false
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (screenshotOverlay == null) return

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventPackage = event.packageName?.toString()
            if (eventPackage == null || eventPackage == packageName) return

            // Don't close if we are in manual input mode (allowing the keyboard to open)
            val root = screenshotOverlay as? FrameLayout
            if (root?.findViewWithTag<View>("manual_input_blocker") != null) return
            
            // Ignore events immediately after closing manual input (like keyboard dismissal)
            if (System.currentTimeMillis() - lastManualInputCloseTime < 1000) return

            // Don't close for non-fullscreen events like heads-up notifications
            if (event.isFullScreen == false) return

            hideScreenshotOverlay()
        }
    }
    override fun onInterrupt() {}
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (screenshotOverlay == null) return super.onKeyEvent(event)
        
        if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            val root = screenshotOverlay as? FrameLayout ?: return true
            if (root.findViewWithTag<View>("manual_input_blocker") != null) {
                closeManualInput(root)
            } else {
                hideScreenshotOverlay()
            }
            return true
        }
        return super.onKeyEvent(event)
    }
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
