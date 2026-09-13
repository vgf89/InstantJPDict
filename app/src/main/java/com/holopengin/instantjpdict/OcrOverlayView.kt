package com.holopengin.instantjpdict

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.DeinflectionChain
import com.holopengin.instantjpdict.util.FuriganaAligner
import com.holopengin.instantjpdict.util.JapaneseUtil
import com.holopengin.instantjpdict.util.InferLog
import com.holopengin.instantjpdict.util.KanaSizeFix
import com.holopengin.instantjpdict.util.OovSuggestions
import com.holopengin.instantjpdict.util.PitchAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** #57: the shared OCR lookup surface. This was the anonymous root
 *  `FrameLayout` built inline in `OcrAccessibilityService.showScreenshotOverlay`
 *  plus the ~30 private UI methods that took it as `rootLayout`. It is now a
 *  plain View so the accessibility service (its own overlay window) and the
 *  image-share activity host exactly the same boxes / lookup / popup code.
 *
 *  Everything that differed between the two hosts lives in [Host]: the image,
 *  the engine, the shared [OcrOverlayStateController], the dismiss request and
 *  the floating-button bookkeeping. Nothing in the drawing, hit-testing,
 *  lookup, popup or close code is host-specific — that is the whole point.
 *
 *  View construction cannot be JVM-unit-tested; the decision logic it calls
 *  (layout geometry, alternatives/neighbour state, close ordering) lives in
 *  [OcrOverlayStateController] and the other extracted objects, which are.
 */
class OcrOverlayView(
    context: Context,
    private val host: Host,
) : FrameLayout(context) {

    /** What the overlay needs from whoever shows it. */
    interface Host {
        /** The image under the overlay: OCR input and crop source. */
        val bitmap: Bitmap
        val ocrEngine: OcrEngine
        val controller: OcrOverlayStateController
        /** Remove the whole overlay. Called once, through [closeWholeOverlay]. */
        fun dismissOverlay()
        /** Re-apply the host window's LayoutParams (manual-input keyboard). */
        fun requestSoftInputResize()
        /** The draggable close button's starting position (floating-button coords). */
        fun closeButtonOrigin(): Pair<Int, Int>
        /** The close button was dragged; keep the floating button in step. */
        fun onCloseButtonMoved(x: Int, y: Int)
    }

    private val srcBitmap: Bitmap get() = host.bitmap
    private val ocrEngine: OcrEngine get() = host.ocrEngine
    private val controller: OcrOverlayStateController get() = host.controller

    /** Overlay-scoped work. Cancelled the moment the overlay closes. */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** True once the overlay is being torn down; every async post checks it. */
    private var closed = false
    private var ocrJob: Job? = null
    private var statusGen = 0
    private var lookupJob: Job? = null
    private var repeatJob: Job? = null
    private var currentRepeatingKeyCode = 0

    private val textViews = mutableMapOf<Pair<Int, Int>, View>()
    private val lineViews = mutableMapOf<Int, LineOverlayView>()
    private var cursorView: View? = null
    private var scrollAnimator: ObjectAnimator? = null
    private var targetScrollY = 0
    private var viewportAnimator: AnimatorSet? = null
    private var neighborAnimator: ObjectAnimator? = null
    private val dictionaryViewCache = object : java.util.LinkedHashMap<String, View>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, View>?): Boolean {
            return size > 50
        }
    }

    /** #72: window back callback, so it can be unregistered with the window. */
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var backDispatcher: android.window.OnBackInvokedDispatcher? = null
    /** #72: last handled back press, to de-duplicate the two delivery paths. */
    private var lastBackHandledAt = 0L
    private val backDedupeMs = 250L

    private val borderDrawable by lazy {
        GradientDrawable().apply {
            setColor(Color.argb(100, 0, 0, 0))
            cornerRadius = 4f
        }
    }

    // Gesture/touch state (was local to showScreenshotOverlay).
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isScaling = false
    private var hasPanned = false
    private var suppressNextTapClick = false
    private var lastEmptyTapUpTime = 0L
    private var lastEmptyTapUpX = 0f
    private var lastEmptyTapUpY = 0f
    private var zoomAnimator: android.animation.ValueAnimator? = null

    private lateinit var contentContainer: FrameLayout
    private lateinit var debugTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var gestureDetector: android.view.ScaleGestureDetector
    private lateinit var tapDetector: android.view.GestureDetector

    /** #64: status-strip metrics shared by the scrim fade, the display bitmap
     *  fade and the swipe-dismiss zone. Display-only: OCR box coordinates are
     *  never shifted, so hit-testing cannot desync. */
    private val statusStripPx: Int by lazy { statusBarHeightPx() }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setOnClickListener {
            // #72: the click is just "close one layer" — the same action
            // the back key and gamepad back run, so all three stay in step.
            // A tap that landed on a character was a lookup, not a
            // dismissal, so it may not close the overlay.
            val onCharacter = controller.isNearCharacter(
                initialTouchX, initialTouchY, 20f, resources.displayMetrics.density
            )
            closeNextLayer(this, allowDismiss = !onCharacter)
        }
        setOnGenericMotionListener { _, event ->
            handleJoystick(
                event,
                controller.lastJoystickKeyCode,
                { controller.lastJoystickKeyCode = it },
                { handleGamepad(it) }
            )
        }

        // Bottom-most child: scrim with a see-through status strip (#64).
        // Never intercepts touches; pan/zoom reveals show this scrim,
        // exactly like the old flat root background.
        val scrimView = StatusStripScrimView(context).apply {
            tag = "backdrop_scrim"
            stripHeightPx = statusStripPx
            mode = OverlayBackdrop.STATUS_STRIP_MODE
        }
        addView(scrimView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Viewport container to support Pan & Zoom for image AND results
        contentContainer = FrameLayout(context).apply {
            tag = "content_container"
            pivotX = 0f
            pivotY = 0f
        }
        addView(contentContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // #64: OCR reads the pristine `bitmap`; the user sees a display
        // copy with the strip treatment baked in, at SCREENSHOT_ALPHA.
        // Same dimensions and position as before — box mapping untouched.
        val imageView = android.widget.ImageView(context).apply {
            setImageBitmap(createOverlayDisplayBitmap(srcBitmap, statusStripPx))
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
            alpha = OverlayBackdrop.screenshotAlpha(context)
        }
        contentContainer.addView(imageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        gestureDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                // Pinch takes over immediately from any running zoom animation.
                zoomAnimator?.cancel()
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

        tapDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // #72: with the feature off there is no second-tap meaning
                // to resolve, so don't claim the gesture at all.
                if (!DoubleTapZoom.isEnabled(context)) return false
                // Never hijack taps on interactive chrome.
                val onChrome = listOf("correction_ui_root", "manual_input_blocker", "close_button").any { tag ->
                    findViewWithTag<View>(tag)?.let { v ->
                        v.isVisible && Rect().also { r -> v.getGlobalVisibleRect(r) }.contains(e.rawX.toInt(), e.rawY.toInt())
                    } == true
                }
                if (onChrome) return false
                // Char taps only: empty space belongs to the root
                // listener's explicit double-tap path below (it never sees
                // char streams, and this detector seeing both would
                // double-toggle). Same 20dp neighborhood the tap-to-
                // dismiss check uses.
                if (!controller.isNearCharacter(e.x, e.y, 20f, resources.displayMetrics.density)) return false
                removeCallbacks(deferredClick)
                suppressNextTapClick = true
                animateZoomTo(
                    DoubleTapZoom.toggle(
                        controller.currentScale, controller.currentTransX, controller.currentTransY,
                        e.x, e.y
                    )
                )
                return true
            }
        })

        setOnTouchListener { v, event ->
            val (focusX, focusY) = event.getFocusCoords()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = focusX; initialTouchY = focusY
                    lastFocusX = focusX; lastFocusY = focusY
                    isScaling = false; hasPanned = false
                    // A fresh stream supersedes any tap-click deferred by
                    // a previous tap (double-tap's onDoubleTap already
                    // removed it; this covers tap-then-pinch/pan).
                    v.removeCallbacks(deferredClick)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    zoomAnimator?.cancel()
                    isScaling = true; lastFocusX = focusX; lastFocusY = focusY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = focusX - lastFocusX
                    val dy = focusY - lastFocusY

                    // #64: dismiss swipes must start inside the visible
                    // status strip (shade-pull affordance); drags/pans
                    // starting below it can never dismiss. Replaces the
                    // old magic 100px zone that overlapped the strip.
                    if (OverlayBackdrop.swipeDismissStartsInStrip(initialTouchY, statusStripPx) &&
                        focusY - initialTouchY > OverlayBackdrop.SWIPE_DISMISS_MIN_TRAVEL_PX && !isScaling && !hasPanned) {
                        closeWholeOverlay(); return@setOnTouchListener true
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
                if (suppressNextTapClick) {
                    suppressNextTapClick = false
                } else if (abs(focusX - initialTouchX) < 10 && abs(focusY - initialTouchY) < 10) {
                    // #59: explicit empty-space double-tap (this listener
                    // never sees char streams). A second tap within the
                    // system double-tap window + slop zooms instead of
                    // dismissing; otherwise the single tap dismisses as
                    // before, deferred past the window.
                    val now = android.os.SystemClock.uptimeMillis()
                    val dtWindow = android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
                    val dtSlop = android.view.ViewConfiguration.get(context).scaledDoubleTapSlop
                    val zoomEnabled = DoubleTapZoom.isEnabled(context)
                    if (!zoomEnabled) {
                        // #72: nothing to wait for — close on this tap
                        // instead of arming a timer that can never fire.
                        v.removeCallbacks(deferredClick)
                        deferredClick.run()
                    } else if (now - lastEmptyTapUpTime < dtWindow &&
                        abs(focusX - lastEmptyTapUpX) <= dtSlop &&
                        abs(focusY - lastEmptyTapUpY) <= dtSlop
                    ) {
                        lastEmptyTapUpTime = 0L
                        v.removeCallbacks(deferredClick)
                        animateZoomTo(
                            DoubleTapZoom.toggle(
                                controller.currentScale, controller.currentTransX, controller.currentTransY,
                                focusX, focusY
                            )
                        )
                    } else {
                        lastEmptyTapUpTime = now; lastEmptyTapUpX = focusX; lastEmptyTapUpY = focusY
                        v.removeCallbacks(deferredClick)
                        v.postDelayed(deferredClick, dtWindow)
                    }
                }
            }
            true
        }

        debugTextView = TextView(context).apply {
            tag = "debug_text"
            setTextColor(Color.YELLOW)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
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
        addView(debugTextView, debugParams)

        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        val progressParams = FrameLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (4 * resources.displayMetrics.density).toInt()
        ).apply {
            gravity = Gravity.TOP
        }
        addView(progressBar, progressParams)

        // Confidence Controls (Left Side)
        val controlsRoot = LinearLayout(context).apply {
            tag = "confidence_controls"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(10, 10, 10, 10)
            isVisible = false // Hidden until recognition starts
        }
        val controlsParams = FrameLayout.LayoutParams(
            (60 * resources.displayMetrics.density).toInt(),
            (450 * resources.displayMetrics.density).toInt()
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            marginStart = (5 * resources.displayMetrics.density).toInt()
        }
        addView(controlsRoot, controlsParams)

        addCloseButton()
    }

    /**
     * The draggable close button. Its position is the floating button's, and
     * dragging it keeps them in step — both host-specific bits go through
     * [Host] so the share activity gets the same button without a floating
     * button to sync.
     */
    private fun addCloseButton() {
        val closeButton = CenteredButton(context).apply {
            tag = "close_button"
            background = logoButtonBackground(context)
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            gravity = Gravity.CENTER
            includeFontPadding = false
            alpha = 1.0f
            setOnClickListener { closeWholeOverlay() }
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

                            host.onCloseButtonMoved(newX, newY)
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
        val origin = host.closeButtonOrigin()
        val lp = FrameLayout.LayoutParams(size, size).apply {
            leftMargin = origin.first
            topMargin = origin.second
        }
        addView(closeButton, lp)
    }

    /**
     * #72: this is the path back actually takes. The platform does not
     * route back navigation to an accessibility overlay window (its
     * OnBackInvokedDispatcher callback registers but is never invoked),
     * so with predictive back opted out in the manifest the back key
     * event lands on this window's view tree instead — where a plain
     * FrameLayout dropped it silently, which was the "back is blocked
     * but nothing happens" symptom.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) handleBack(this)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // #59: feed double-tap detection first — intercept sees every
        // touch, including char taps consumed by LineOverlayView
        // children that never reach the root touch listener.
        tapDetector.onTouchEvent(ev)
        if (listOf("correction_ui_root", "manual_input_blocker", "close_button").any { isTouchOnView(it, ev) }) return false
        updateFocusState(ev)
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            if (ev.pointerCount > 1 || abs(ev.x - initialTouchX) > 10 || abs(ev.y - initialTouchY) > 10) return true
        } else if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerBackCallback(this)
    }

    override fun onDetachedFromWindow() {
        unregisterBackCallback()
        super.onDetachedFromWindow()
    }

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

    // #59: animated zoom shared by char and empty-space double-tap.
    // A new toggle cancels the running animation; pinch (fresh
    // POINTER_DOWN / onScale) takes over the same way.
    private fun animateZoomTo(next: DoubleTapZoom.ZoomState) {
        zoomAnimator?.cancel()
        val fromScale = controller.currentScale
        val fromX = controller.currentTransX
        val fromY = controller.currentTransY
        zoomAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DoubleTapZoom.ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                controller.currentScale = fromScale + (next.scale - fromScale) * t
                controller.currentTransX = fromX + (next.transX - fromX) * t
                controller.currentTransY = fromY + (next.transY - fromY) * t
                contentContainer.scaleX = controller.currentScale
                contentContainer.scaleY = controller.currentScale
                contentContainer.translationX = controller.currentTransX
                contentContainer.translationY = controller.currentTransY
            }
            start()
        }
    }

    /**
     * #59: empty-area single-tap action (dismiss etc. via the click
     * listener) deferred past the double-tap window, so the first tap
     * of a double-tap doesn't dismiss before the zoom can fire.
     * Char taps are unaffected — LineOverlayView fires them
     * synchronously on UP with zero added latency.
     */
    private val deferredClick = Runnable {
        if (closed) return@Runnable
        performClick()
    }

    /**
     * Ordered status writes (#50): progress, final and error text all go
     * through the view's main queue carrying the run generation. The queue
     * is FIFO, so the final line always executes after every progress post
     * (previously an async post could land after it and clobber it), and
     * posts from a superseded run or a closed overlay are dropped.
     */
    private fun postStatus(gen: Int, text: String, hideProgress: Boolean = false) {
        debugTextView.post {
            if (gen != statusGen || closed) return@post
            debugTextView.text = text
            debugTextView.bringToFront()
            if (hideProgress) progressBar.visibility = View.GONE
        }
    }

    /**
     * #57: detect + recognise + render. The share activity and the
     * accessibility service both call this, so the boxes / lookup /
     * popup behaviour is one implementation.
     */
    fun startOcr() {
        ocrJob = scope.launch {
            val gen = ++statusGen
            try {
                if (ocrEngine.isReady()) {
                    postStatus(gen, "Detecting...")
                    val tDet = System.currentTimeMillis()
                    val lineBoxes = withContext(Dispatchers.IO) { ocrEngine.detect(srcBitmap) }
                    val detMs = System.currentTimeMillis() - tDet
                    controller.activeLineBoxes = lineBoxes
                    
                    postStatus(gen, "Recognizing...")
                    
                    val linesBorderLayer = FrameLayout(context)
                    contentContainer.addView(linesBorderLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                    lineBoxes.forEach { box ->
                        val lineView = View(context).apply {
                            background = borderDrawable
                        }
                        val lineParams = FrameLayout.LayoutParams(box.width(), box.height()).apply {
                            leftMargin = box.left
                            topMargin = box.top
                        }
                        linesBorderLayer.addView(lineView, lineParams)
                    }

                    val clicksLayer = FrameLayout(context).apply { tag = "clicks_layer" }
                    contentContainer.addView(clicksLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                    findViewWithTag<View>("confidence_controls")?.isVisible = true

                    // Pre-create line containers to maintain Z-order and simplify updates
                    lineBoxes.forEachIndexed { i, _ ->
                        val lineContainer = FrameLayout(context).apply { tag = "line_clicks_$i" }
                        clicksLayer.addView(lineContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    }

                    textViews.clear()
                    lineViews.clear()
                    controller.activeLineResults = MutableList(lineBoxes.size) { null as LineResult? }
                    controller.activeAllChars = mutableListOf()
                    controller.activeAllAlternatives = mutableListOf()

                    val startTime = System.currentTimeMillis()
                    // Render-once (#50): stash results during streaming, build
                    // all views after every line completes. Recognition is ~1s,
                    // so progressive rendering bought nothing and its per-line
                    // Main work congested the queue mid-run.
                    val finishedLines = mutableListOf<Pair<Int, LineResult>>()
                    withContext(Dispatchers.IO) {
                        ocrEngine.recognizeStreaming(srcBitmap, lineBoxes) { results ->
                            if (closed) return@recognizeStreaming
                            finishedLines.addAll(results)
                        }
                    }
                    // #44 Feature 3: kana size correction. Runs over the whole page before any
                    // view is built, because the corrected page has to be complete before the
                    // layout is derived from it.
                    val orderedLines = finishedLines.sortedBy { it.first }
                    val correctedLines = KanaSizeFix.applyIfEnabled(
                        context, orderedLines.map { it.second })
                    // Surface the outcome: without this the correction is invisible whether or not
                    // it fired, which makes "on vs off" impossible to judge from the outside.
                    InferLog.add(KanaSizeFix.lastSummary)
                    if (KanaSizeFix.lastDeclined.isNotEmpty()) {
                        // Which positions the model declined, and how close they were. No
                        // surrounding text: this log gets copied out and shared.
                        InferLog.add("kana declined: " + KanaSizeFix.lastDeclined)
                    }
                    for ((i, entry) in orderedLines.withIndex()) {
                        if (closed) break
                        addLineToResults(this@OcrOverlayView, clicksLayer, entry.first, correctedLines[i])
                    }
                    if (!closed && controller.currentTappedLineIdx == -1) {
                        updateCursor()
                    }
                    val recMs = System.currentTimeMillis() - startTime
                    // The kana outcome is part of the status line: whether it fired is otherwise
                    // invisible from outside the app, which makes on-vs-off impossible to judge.
                    val kanaNote = KanaSizeFix.lastSummary.removePrefix("kana fix: ")
                    postStatus(gen, "${finishedLines.size} ln | ${controller.activeAllChars.size} chr | Det ${detMs}ms | Rec ${recMs}ms | kana: $kanaNote", hideProgress = true)
                } else {
                    postStatus(gen, "Error: OCR Engine not ready", hideProgress = true)
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.toString()
                Log.e("OcrAccessibilityService", "OCR Inference Error", e)
                postStatus(gen, "Error: $errorMsg", hideProgress = true)
                Toast.makeText(context, "OCR Error: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addLineToResults(rootLayout: FrameLayout, clicksLayer: FrameLayout, lineIdx: Int, lineIn: LineResult) {
        if (closed) return
        // #44 Feature 2: materialise measured gaps as tappable placeholders *before* the click
        // views are built, so the placeholder gets a view like any other character. Vertical
        // only and idempotent — see BlankGaps for the measured trigger.
        val line = BlankGaps.applyIfEnabled(context, lineIn)
        controller.activeLineResults[lineIdx] = line
        controller.updateGlobalData()

        val lineContainer = clicksLayer.findViewWithTag<FrameLayout>("line_clicks_$lineIdx") ?: clicksLayer
        lineContainer.removeAllViews() // Clear existing character views for refresh

        val fixedSize = if (line.isVertical) {
            line.charBoxes.map { it.height() }.maxOrNull() ?: 0
        } else {
            line.charBoxes.map { it.height() }.maxOrNull() ?: 0
        }
        Log.d("OcrAccessibilityService", "addLineToResults line=$lineIdx text='${line.text}' boxes=${line.charBoxes.size} fixedSize=$fixedSize isVertical=${line.isVertical}")
        if (fixedSize == 0) return

        // Single View per line — was 3 Views per char (5850 Views for 65×30)
        val lineLeft = line.charBoxes.minOfOrNull { it.left } ?: 0
        val lineTop = line.charBoxes.minOfOrNull { it.top } ?: 0
        val lineRight = line.charBoxes.maxOfOrNull { it.right } ?: 0
        val lineBottom = line.charBoxes.maxOfOrNull { it.bottom } ?: 0
        val lineW = (lineRight - lineLeft).coerceAtLeast(1)
        val lineH = (lineBottom - lineTop).coerceAtLeast(1)
        val lineViewTag = "line_overlay_$lineIdx"
        var lineView = lineContainer.findViewWithTag<LineOverlayView>(lineViewTag)
        // Ink margin (#49): the view pads itself by margin on all sides so
        // overflowing halfwidth ink never clips at the view bounds.
        val m = LineOverlayView.marginFor(fixedSize)
        if (lineView == null) {
            lineView = LineOverlayView(context, line, fixedSize, lineLeft, lineTop) { charIdx ->
                performLookup(lineIdx, charIdx, rootLayout)
            }
            lineView.tag = lineViewTag
            lineContainer.addView(lineView, FrameLayout.LayoutParams(lineW + 2 * m, lineH + 2 * m).apply {
                leftMargin = lineLeft - m
                topMargin = lineTop - m
            })
        } else {
            lineView.updateLine(line, fixedSize, lineLeft, lineTop)
            // Update layout params if line bounds changed
            (lineView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = lineW + 2 * m
                lp.height = lineH + 2 * m
                lp.leftMargin = lineLeft - m
                lp.topMargin = lineTop - m
                lineView.layoutParams = lp
            }
        }
        lineViews[lineIdx] = lineView
        // Keep textViews map for legacy lookup — point to lineView for each char
        for (i in line.charBoxes.indices) {
            textViews[Pair(lineIdx, i)] = lineView
        }
        updateNeighborPanelForLine(rootLayout, lineIdx)
    }

    private fun refreshOcrResults() {
        if (closed) return
        val overlay: FrameLayout = this
        val contentContainer = overlay.findViewWithTag<FrameLayout>("content_container") ?: return
        val clicksLayer = contentContainer.findViewWithTag<FrameLayout>("clicks_layer") ?: return

        controller.activeLineResults.forEachIndexed { index, lineResult ->
            if (lineResult != null) {
                addLineToResults(overlay, clicksLayer, index, lineResult)
            }
        }
        updateCursor()

        if (controller.isAlternativesVisible) {
            val altContainer = overlay.findViewWithTag<FrameLayout>("alternatives_container")
            if (altContainer != null && altContainer.childCount > 0) {
                updateAlternativesPanelContent(altContainer, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, overlay.width > overlay.height, overlay)
            }
        }
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
        lineContainer.isBaselineAligned = false
        val neighborState = controller.getNeighborUiState().getOrNull(lineIdx) ?: return
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)
        val itemLp = LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) }

        neighborState.chars.forEach { charState ->
            val neighborTextView = TextView(context).apply {
                tag = "neighbor_char_${charState.lineIdx}-${charState.charIdx}"
                text = charState.text
                setTextColor(if (charState.isSelected) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                textSize = estimatedTextSize
                gravity = Gravity.CENTER
                includeFontPadding = false
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
        // #44 Feature 2: a blank has no definition to look up. The tap belongs to the
        // alternatives panel, which carries the manual IME entry the ground truth is typed
        // into (decision 5: show nothing, stay clickable, because a manual entry mode exists).
        if (controller.isBlankAt(lineIdx, charIdx)) {
            controller.selectBlankPosition(lineIdx, charIdx)
            // Highlight like any other character. Yellow is the only feedback that the tap
            // landed, and the neighbour chips recompute their selection only inside
            // updateNeighborHighlights — which a lookup calls and a blank never did, so a
            // blank tap showed no selection anywhere (#44).
            updateLookupHighlights(lineIdx, charIdx, 1)
            rootLayout.findViewWithTag<LinearLayout>("neighbor_scroll_panel")
                ?.let { updateNeighborHighlights(it) }
            // The panel *toggles*, so a list left over from an earlier lookup would make this
            // tap close it instead of showing the blank's own entry. Clear it first.
            rootLayout.findViewWithTag<FrameLayout>("alternatives_container")?.let { container ->
                if (container.childCount > 0) {
                    recycleCropBitmaps(container)
                    container.removeAllViews()
                    controller.isAlternativesVisible = false
                }
            }
            // A tap always opens the dictionary view, even with nothing to show: a blank has
            // no entry, so the view opens empty with the blank's candidates beside it.
            // controller.lookup() returns null for a placeholder — that null was why the
            // view never appeared at all.
            val box = controller.activeLineResults.getOrNull(lineIdx)?.charBoxes?.getOrNull(charIdx)
            if (box != null) {
                showResultsUi(rootLayout, emptyList(), box, skipCenter)
                toggleAlternativesPanel(rootLayout, lineIdx, charIdx,
                                        rootLayout.width > rootLayout.height)
            }
            return
        }

        // Instant visual feedback for the cursor movement
        updateLookupHighlights(lineIdx, charIdx, 1)

        lookupJob?.cancel()
        lookupJob = scope.launch {
            // Wait for user to stop navigating before doing heavy DB/UI work
            kotlinx.coroutines.delay(200)

            val result = controller.lookup(lineIdx, charIdx) ?: return@launch

            updateLookupHighlights(lineIdx, charIdx, result.maxLen)

            showResultsUi(rootLayout, result.matches, result.tappedBox, skipCenter, result.cacheKey)
        }
    }

    private fun resetHighlights() {
        // Clear highlights on all lineViews that had highlights
        val byLine = controller.lastHighlightedCoords.groupBy { it.first }
        for ((lineIdx, _) in byLine) {
            lineViews[lineIdx]?.setHighlighted(emptySet())
        }
        // Legacy fallback for old TextView path
        controller.lastHighlightedCoords.forEach { coords ->
            (textViews[coords] as? android.widget.TextView)?.let { tv ->
                tv.setTextColor(android.graphics.Color.parseColor("#FF7777"))
                tv.typeface = android.graphics.Typeface.DEFAULT
            }
        }
    }

    private fun updateLookupHighlights(lineIdx: Int, charIdx: Int, wordLength: Int) {
        resetHighlights()
        controller.updateHighlightCoords(lineIdx, charIdx, wordLength)
        // Group by line for LineOverlayView
        val byLine = controller.lastHighlightedCoords.groupBy { it.first }
        for ((lIdx, coords) in byLine) {
            val charIndices = coords.map { it.second }.toSet()
            lineViews[lIdx]?.setHighlighted(charIndices)
        }
        controller.lastHighlightedCoords.forEach { coords ->
            (textViews[coords] as? android.widget.TextView)?.let { tv ->
                tv.setTextColor(android.graphics.Color.YELLOW)
                tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun showResultsUi(rootLayout: FrameLayout, matches: List<FormattedEntry>, tappedBox: JpDictRect, skipCenter: Boolean = false, cacheKey: String? = null) {
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val isLandscape = rootWidth > rootHeight

        val existingRoot = rootLayout.findViewWithTag<LinearLayout>("correction_ui_root")
        
        if (existingRoot != null) {
            val dictionaryContainer = existingRoot.findViewWithTag<LinearLayout>("dictionary_content_container")
            if (dictionaryContainer != null) updateDictionaryPanel(dictionaryContainer, matches, cacheKey)
            
            val neighborPanel = existingRoot.findViewWithTag<LinearLayout>("neighbor_scroll_panel")
            if (neighborPanel != null) updateNeighborHighlights(neighborPanel)

            val altContainer = existingRoot.findViewWithTag<FrameLayout>("alternatives_container")
            if (altContainer != null && altContainer.childCount > 0) {
                updateAlternativesPanelContent(altContainer, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, isLandscape, rootLayout)
            }
            
            if (!skipCenter) {
                val neighborScrollView = existingRoot.findViewWithTag<View>("neighbor_scroll_view")
                if (neighborScrollView != null) centerNeighborScrollView(neighborScrollView, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine)
            }
            centerWordInVisibleArea(rootLayout, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine)
            existingRoot.bringToFront()
            controller.isDictionaryVisible = true
            updateCursor()
            return
        }

        controller.updateGravity(rootWidth, rootHeight, tappedBox)
        controller.isDictionaryVisible = true
        val (panelWidthF, panelHeightF) = controller.getPanelDimensions(rootWidth, rootHeight)
        val panelWidth = if (isLandscape) panelWidthF.toInt() else FrameLayout.LayoutParams.MATCH_PARENT
        val panelHeight = if (isLandscape) FrameLayout.LayoutParams.MATCH_PARENT else panelHeightF.toInt()

        val dictionaryPanel = LinearLayout(context).apply {
            tag = "dictionary_content_container"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.argb(245, 25, 25, 25))
            setPadding(40, 40, 40, 40)
            elevation = 20f
            setOnClickListener { }
        }
        updateDictionaryPanel(dictionaryPanel, matches, cacheKey)

        val correctionPanel = createCorrectionPanel(controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, isLandscape, rootLayout, skipCenter)
        val alternativesPanelContainer = FrameLayout(context).apply {
            tag = "alternatives_container"
            layoutParams = if (isLandscape) LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val mainContainer = LinearLayout(context).apply {
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

    private fun updateDictionaryPanel(container: LinearLayout, matches: List<FormattedEntry>, cacheKey: String? = null) {
        container.removeAllViews()
        targetScrollY = 0
        scrollAnimator?.cancel()

        if (cacheKey != null) {
            val cachedView = dictionaryViewCache[cacheKey]
            if (cachedView != null) {
                (cachedView.parent as? ViewGroup)?.removeView(cachedView)
                container.addView(cachedView)
                return
            }
        }

        if (matches.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "No results found"
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                textSize = 16f
                setPadding(0, 150, 0, 0)
            })
            return
        }

        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 0, 10, 150)
        }

        matches.forEach { entry ->
            val termSection = LinearLayout(context).apply { 
                orientation = LinearLayout.VERTICAL
                setPadding(0, 4, 0, 40)
            }
            renderHeadwordSection(termSection, entry.readingGroups)
            // #62: chain row directly below the headwords, above the senses.
            // Direct matches (deinflection == null) render as before.
            entry.deinflection?.let { chain ->
                if (chain.steps.isNotEmpty()) {
                    termSection.addView(createDeinflectionRow(chain, entry.term))
                }
            }
            entry.readingGroups.forEach { group ->
                renderSensesForReading(termSection, group)
                
                termSection.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 20 }
                    setBackgroundColor(Color.DKGRAY)
                    alpha = 0.3f
                })
            }
            // Dictionary source, bottom of the entry: entries never mix
            // dictionaries, so one caption per section is exact.
            entry.dictionaryName?.let { name ->
                termSection.addView(TextView(context).apply {
                    text = name
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    gravity = Gravity.END
                    setPadding(0, 8, 0, 0)
                })
            }
            scrollContent.addView(termSection)
        }

        val scrollView = ScrollView(context).apply {
            tag = "dictionary_scroll_view"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
            addView(scrollContent)
        }
        container.addView(scrollView)
        if (cacheKey != null) {
            dictionaryViewCache[cacheKey] = scrollView
        }
    }

    /** #62: compact deinflection chain row, e.g. "食べた → 食べる" + past chip.
     *  Pure view construction (no Android-string resources) so the label
     *  logic stays unit-testable via [DeinflectionChain.label]. */
    private fun createDeinflectionRow(chain: DeinflectionChain, term: String): View {
        return FlowLayout(context).apply {
            setPadding(0, 1, 0, 1)
            addView(TextView(context).apply {
                text = "${chain.surface} → $term"
                setTextColor(Color.LTGRAY)
                textSize = 12f
                setPadding(0, 0, 12, 0)
                includeFontPadding = false
            })
            chain.steps.forEach { step -> addView(createTagView(step)) }
        }
    }

    /**
     * Headword block for one entry. Kanji (KANJIDIC) entries render their big
     * glyph with 訓/音 rows; ordinary term entries render every headword with
     * its own reading in ONE comma-separated flow, so repeated kanji across
     * reading groups read like the multi-kanji kana-lookup case instead of
     * stacking.
     */
    private fun renderHeadwordSection(container: LinearLayout, groups: List<FormattedReadingGroup>) {
        val headwordList = LinearLayout(context).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 2)
        }

        val kanjiEntries = groups.filter { it.isKanjiEntry }
        kanjiEntries.forEach { group ->
            group.headwords.forEach { hw ->
                val kanjiHeader = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 1, 0, 1)
                }
                kanjiHeader.addView(TextView(context).apply {
                    text = hw.kanji
                    setTextColor(Color.CYAN)
                    textSize = 48f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 30, 0)
                })
                val readingStack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                // 訓 (kun) above 音 (on); shared row styling.
                hw.kunyomi?.takeIf { it.isNotEmpty() }?.let {
                    readingStack.addView(createKunOnRow("訓", JapaneseUtil.splitKanaList(it).joinToString("、")))
                }
                hw.onyomi?.takeIf { it.isNotEmpty() }?.let {
                    readingStack.addView(createKunOnRow("音", JapaneseUtil.splitKanaList(it).joinToString("、"), topMarginPx = kunOnRowTightenPx))
                }
                kanjiHeader.addView(readingStack)
                headwordList.addView(kanjiHeader)
            }
        }

        // Every (kanji, reading) pair across the remaining reading groups, one
        // flow row, comma separated. Entries are single-dictionary so this is
        // normally all-or-nothing with the kanji branch above.
        val termGroups = groups.filter { !it.isKanjiEntry }
        if (termGroups.isNotEmpty()) {
            val pairs = termGroups.flatMap { g -> g.headwords.map { it.kanji to g.reading } }
            val flow = FlowLayout(context).apply { setPadding(0, 0, 0, 0) }
            // #68: if any headword renders a ruby row, reserve the same ruby
            // space for all of them so baselines align in the shared flow.
            val reserveRubySpace = pairs.any { (kanji, reading) -> kanji != reading }
            pairs.forEachIndexed { i, (kanji, reading) ->
                flow.addView(createRubyView(kanji, reading, reserveRubySpace = reserveRubySpace))
                if (i < pairs.size - 1) {
                    flow.addView(TextView(context).apply { text = "、"; setTextColor(Color.GRAY); textSize = 24f; setPadding(5, 0, 5, 0) })
                }
            }
            headwordList.addView(flow)

            // #43: pitch accents for every reading of this entry, on one
            // comma-separated line. Gated by the MainActivity toggle; with it
            // off, or no reading carrying pitch data, the popup is unchanged.
            if (PitchAccent.isEnabled(context)) {
                val items = termGroups.flatMap { group ->
                    group.pitchPositions.map { PitchAccentLine.Item(group.reading, it) }
                }
                PitchAccentLine.build(context, items, pitchTextSizePx)?.let { line ->
                    headwordList.addView(line, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = pitchRowTopMarginPx })
                }
            }
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

        val outerContainer = FrameLayout(context).apply { tag = "correction_panel"; setBackgroundColor(android.graphics.Color.argb(255, 45, 45, 45)); elevation = 25f }
        val scrollView = if (isLandscape) ScrollView(context) else HorizontalScrollView(context)
        scrollView.apply {
            tag = "neighbor_scroll_view"; isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            layoutParams = if (isLandscape) FrameLayout.LayoutParams(itemSize + 12, FrameLayout.LayoutParams.MATCH_PARENT) else FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, itemSize + 12)
        }

        val panel = LinearLayout(context).apply { tag = "neighbor_scroll_panel"; orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(6, 6, 6, 6) }

        controller.activeLineResults.forEachIndexed { lIdx, line ->
            val lineContainer = LinearLayout(context).apply {
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

    /** Swap a crop bitmap into an ImageView, recycling the previous crop. All three
     * preview sites below only ever hold crops created here — never srcBitmap —
     * so the outgoing drawable is always safe to recycle. #20 */
    private fun setCropBitmap(view: android.widget.ImageView, crop: android.graphics.Bitmap?) {
        val old = (view.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (old === crop) return
        view.setImageBitmap(crop)
        if (old != null && !old.isRecycled) {
            try { old.recycle() } catch (_: Exception) {}
        }
    }

    /** Recycle crop bitmaps held by ImageViews under [root] (panel dismiss paths). #20 */
    private fun recycleCropBitmaps(root: android.view.View) {
        if (root is android.widget.ImageView) {
            val b = (root.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            root.setImageDrawable(null)
            if (b != null && !b.isRecycled) {
                try { b.recycle() } catch (_: Exception) {}
            }
            return
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) recycleCropBitmaps(root.getChildAt(i))
        }
    }

    private fun toggleAlternativesPanel(rootLayout: FrameLayout, lIdx: Int, cIdx: Int, isLandscape: Boolean) {
        val container = rootLayout.findViewWithTag<FrameLayout>("alternatives_container") ?: return
        if (container.childCount > 0) { 
            recycleCropBitmaps(container)
            container.removeAllViews()
            controller.isAlternativesVisible = false
            return 
        }
        controller.isAlternativesVisible = true
        // The indices it was handed, not the controller's fields: this call site is reached
        // from the neighbour panel without a lookup, so the fields may be stale (#44).
        val altState = controller.getAlternativesUiState(lIdx, cIdx) ?: return
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)

        // Added Preview Image
        val bitmap = srcBitmap
        val line = controller.activeLineResults[lIdx]
        val box = line?.charBoxes?.getOrNull(cIdx)
        val previewView = android.widget.ImageView(context).apply {
            tag = "preview_image"
            if (box != null) {
                val padding = (box.height() * 0.2).toInt() // Tightened
                val cropRect = Rect((box.left - padding).coerceAtLeast(0), (box.top - padding).coerceAtLeast(0), (box.right + padding).coerceAtMost(bitmap.width), (box.bottom + padding).coerceAtMost(bitmap.height))
                val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
                setCropBitmap(this, cropped)
            }
            layoutParams = LinearLayout.LayoutParams(itemSize, itemSize).apply { gravity = Gravity.CENTER; setMargins(2, 2, 2, 2) }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }

        val mainLayout = LinearLayout(context).apply { 
            orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.argb(255, 55, 55, 55))
            setPadding(6, 6, 6, 6)
            elevation = 30f
            setOnClickListener { } 
        }
        mainLayout.addView(previewView)
        
        val scrollContent = LinearLayout(context).apply { 
            orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        val scrollView = if (isLandscape) ScrollView(context) else HorizontalScrollView(context)
        scrollView.apply {
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            layoutParams = if (isLandscape) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 10f) else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 10f)
        }
        val candidateList = LinearLayout(context).apply { 
            tag = "candidate_list_panel"
            orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        refreshCandidateList(candidateList, lIdx, cIdx, isLandscape, rootLayout)
        scrollView.addView(candidateList); scrollContent.addView(scrollView)
        
        // Add manual stub next to candidate list if possible
        if (altState.showManualInput) {
            val stubView = TextView(context).apply { 
                tag = "manual_input_stub"
                text = "⌨"
                setTextColor(android.graphics.Color.GRAY)
                textSize = estimatedTextSize
                gravity = Gravity.CENTER
                includeFontPadding = false
                setBackgroundColor(android.graphics.Color.argb(255, 40, 40, 40))
                setOnClickListener { showManualInput(lIdx, cIdx, rootLayout) } 
            }
            scrollContent.addView(stubView, LinearLayout.LayoutParams(itemSize, itemSize).apply { setMargins(2, 2, 2, 2) })
        }
        mainLayout.addView(scrollContent, if (isLandscape) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        })
        
        if (mainLayout.parent != null) {
            (mainLayout.parent as ViewGroup).removeView(mainLayout)
        }
        container.addView(mainLayout, if (isLandscape) FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT) else FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

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

    }

    private fun refreshCandidateList(candidateList: LinearLayout, lIdx: Int, cIdx: Int, isLandscape: Boolean, rootLayout: FrameLayout) {
        candidateList.removeAllViews()
        candidateList.isBaselineAligned = false
        val altState = controller.getAlternativesUiState() ?: return
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val itemSize = (if (isLandscape) rootHeight else rootWidth) / 11
        val estimatedTextSize = (itemSize * 0.45 / resources.displayMetrics.density).toFloat().coerceIn(12f, 22f)

        altState.candidates.forEach { cand ->
            val textView = TextView(context).apply {
                text = cand.char.toString(); setTextColor(android.graphics.Color.WHITE); textSize = estimatedTextSize; gravity = Gravity.CENTER
                includeFontPadding = false
                if (cand.isSelected) { setBackgroundColor(android.graphics.Color.YELLOW); setTextColor(android.graphics.Color.BLACK) }
                else {
                    setBackgroundColor(android.graphics.Color.argb(255, 85, 85, 85))
                    // #44: generated entries (component neighbours, variant forms) are
                    // tinted so they stay distinguishable from what the model itself ranked.
                    if (cand.source != OovSuggestions.Source.HEAD) {
                        setTextColor(android.graphics.Color.argb(255, 150, 205, 255))
                    }
                }
                
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
        val bitmap = srcBitmap
        val line = controller.activeLineResults[lIdx]
        val box = line?.charBoxes?.getOrNull(cIdx)
        if (previewView != null && box != null) {
            val padding = (box.height() * 0.2).toInt()
            val cropRect = Rect((box.left - padding).coerceAtLeast(0), (box.top - padding).coerceAtLeast(0), (box.right + padding).coerceAtMost(bitmap.width), (box.bottom + padding).coerceAtMost(bitmap.height))
            val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            setCropBitmap(previewView, cropped)
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
        
        // Update LineOverlayView if present
        val line = controller.activeLineResults[lIdx]
        if (line != null) {
            lineViews[lIdx]?.updateLine(line, line.charBoxes.map { it.height() }.maxOrNull() ?: 0)
        }
        (textViews[Pair(lIdx, cIdx)] as? android.widget.TextView)?.text = newChar.toString()
        
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
        val bitmap = srcBitmap
        val line = controller.activeLineResults[lIdx] ?: return
        val box = line.charBoxes[cIdx]
        val padding = (box.height() * 0.5).toInt()
        val cropRect = Rect((box.left - padding).coerceAtLeast(0), (box.top - padding).coerceAtLeast(0), (box.right + padding).coerceAtMost(bitmap.width), (box.bottom + padding).coerceAtMost(bitmap.height))
        val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        val blocker = FrameLayout(context).apply { tag = "manual_input_blocker"; setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0)); setOnClickListener { closeManualInput(rootLayout) }; elevation = 200f }
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.argb(255, 35, 35, 35)); setPadding(60, 60, 60, 60); gravity = Gravity.CENTER_HORIZONTAL; elevation = 201f; setOnClickListener { } }
        panel.addView(android.widget.ImageView(context).apply { setCropBitmap(this, cropped); val size = (resources.displayMetrics.density * 120).toInt(); layoutParams = LinearLayout.LayoutParams(size, size); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER })
        panel.addView(TextView(context).apply { text = "Enter character manually"; setTextColor(android.graphics.Color.GRAY); textSize = 14f; setPadding(0, 30, 0, 10) })
        val editText = EditText(context).apply { setTextColor(android.graphics.Color.WHITE); textSize = 36f; gravity = Gravity.CENTER; maxLines = 1; imeOptions = EditorInfo.IME_ACTION_DONE; inputType = android.text.InputType.TYPE_CLASS_TEXT; background.setTint(android.graphics.Color.CYAN) }
        panel.addView(editText, LinearLayout.LayoutParams(250, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(Button(context).apply { text = "Confirm"; setOnClickListener { val text = editText.text.toString(); if (text.isNotEmpty()) { replaceCharacter(lIdx, cIdx, text[0], rootLayout); closeManualInput(rootLayout) } } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })
        blocker.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        host.requestSoftInputResize()
        rootLayout.addView(blocker, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        blocker.bringToFront()
        editText.requestFocus()
        editText.postDelayed({ (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) }, 100)
        editText.setOnEditorActionListener { _, actionId, event -> if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) { val text = editText.text.toString(); if (text.isNotEmpty()) { replaceCharacter(lIdx, cIdx, text[0], rootLayout); closeManualInput(rootLayout) }; true } else false }
    }

    private fun closeManualInput(rootLayout: FrameLayout) {
        val blocker = rootLayout.findViewWithTag<View>("manual_input_blocker") ?: return
        controller.lastManualInputCloseTime = System.currentTimeMillis()
        recycleCropBitmaps(blocker)
        rootLayout.removeView(blocker)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(rootLayout.windowToken, 0)
    }

    private fun createTagView(tag: String, category: String = "general"): View {
        val color = when { 
            category == "pos" || tag.startsWith("v") || tag == "adj-i" || tag == "adj-na" -> Color.parseColor("#3a5a7a")
            tag == "n" || tag == "adv" || tag == "pn" -> Color.parseColor("#3a7a5a")
            category == "meta" || tag.startsWith("jlpt") || tag.startsWith("grade") || tag == "★" -> Color.parseColor("#7a3a3a")
            else -> Color.parseColor("#444444") 
        }
        return TextView(context).apply { 
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

    private fun createRubyView(term: String, reading: String, isMini: Boolean = false, reserveRubySpace: Boolean = false): View {
        if (term == reading) {
            if (!reserveRubySpace) return createBaseTextView(term, isMini)
            // #68: mixed group — reserve the same ruby row a furigana-bearing
            // sibling has (empty, identical metrics) so baselines align.
            // Widths are unchanged (stack width = base width either way), so
            // FlowLayout line-wrapping is unaffected.
            return createSpacerRubyView(term, isMini)
        }
        // Minimal furigana (#55): ruby only over kanji spans, okurigana as
        // plain base text. Falls back to full-reading ruby when unalignable.
        val segments = FuriganaAligner.align(term, reading)
        if (segments == null || segments.none { it.ruby != null }) {
            return createFullRubyView(term, reading, isMini)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            var firstRubyIndex = -1
            segments.forEach { seg ->
                if (seg.ruby == null) {
                    addView(createBaseTextView(seg.base, isMini))
                } else {
                    if (firstRubyIndex == -1) firstRubyIndex = childCount
                    addView(createRubyStackView(seg.base, seg.ruby, isMini))
                }
            }
            // #68: a horizontal LinearLayout reports no baseline (-1) by
            // default, which makes FlowLayout bottom-align it instead of
            // baseline-aligning it with sibling ruby stacks. Point at the
            // first ruby stack so the row shares one baseline. Measurement
            // is untouched, so wrapping is identical.
            if (firstRubyIndex != -1) baselineAlignedChildIndex = firstRubyIndex
        }
    }

    private fun createBaseTextView(term: String, isMini: Boolean): TextView {
        return TextView(context).apply {
            text = term
            setTextColor(Color.CYAN)
            textSize = if (isMini) 15f else 32f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }
    }

    private fun createFullRubyView(term: String, reading: String, isMini: Boolean): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isBaselineAligned = true

            addView(TextView(context).apply {
                text = reading
                setTextColor(Color.LTGRAY)
                textSize = if (isMini) 9f else 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            addView(createBaseTextView(term, isMini).apply { gravity = Gravity.CENTER })
            baselineAlignedChildIndex = 1
        }
    }

    /**
     * One 訓 / 音 row — label (12f, GRAY) beside its readings (13f, LTGRAY),
     * used by the kanji (KANJIDIC) entry branch. [topMarginPx] pulls
     * consecutive rows closer (negative tightens).
     */
    private fun createKunOnRow(label: String, readings: String, topMarginPx: Int = 0): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = topMarginPx }
            addView(TextView(context).apply {
                text = label
                setTextColor(Color.GRAY)
                textSize = 12f
                setPadding(0, 0, 12, 0)
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = readings
                setTextColor(Color.LTGRAY)
                // Same size as the furigana rows (non-mini ruby).
                textSize = 13f
                includeFontPadding = false
                setLineSpacing(0f, kunOnLineSpacingMult)
            })
        }
    }

    /** Vertical gap applied above every 訓/音 row after the first (#69). */
    private val kunOnRowTightenPx = -4
    /**
     * Wrapped-line spacing inside long readings values (#69): long 訓/音
     * lines must sit no looser than the gap between the rows themselves.
     */
    private val kunOnLineSpacingMult = 0.85f
    /** Pitch-row typography (#43): matches the furigana reading size. */
    private val pitchTextSizePx: Float
        get() = 13f * resources.displayMetrics.scaledDensity
    private val pitchRowTopMarginPx = 2

    private fun createRubyStackView(base: String, ruby: String, isMini: Boolean): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isBaselineAligned = true

            addView(TextView(context).apply {
                text = ruby
                setTextColor(Color.LTGRAY)
                textSize = if (isMini) 9f else 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            addView(createBaseTextView(base, isMini).apply { gravity = Gravity.CENTER })
            baselineAlignedChildIndex = 1
        }
    }

    /** #68: spacer twin of [createRubyStackView] for ruby-less headwords in
     * mixed groups. Identical config with a non-breaking-space (U+00A0) ruby
     * row, so it measures exactly like a real stack (same height in both
     * sizes) while rendering nothing above the base text. Widths match the
     * plain base view, so FlowLayout wrapping is unaffected. */
    private fun createSpacerRubyView(term: String, isMini: Boolean): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isBaselineAligned = true

            addView(TextView(context).apply {
                text = " "
                setTextColor(Color.LTGRAY)
                textSize = if (isMini) 9f else 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            addView(createBaseTextView(term, isMini).apply { gravity = Gravity.CENTER })
            baselineAlignedChildIndex = 1
        }
    }

    private fun renderSenseGroup(container: LinearLayout, senseGroup: FormattedSenseGroup) {
        if (senseGroup.senses.isEmpty()) return
        
        if (senseGroup.tags.isNotEmpty()) {
            val header = FlowLayout(context).apply { setPadding(20, 15, 0, 5) }
            senseGroup.tags.forEach { header.addView(createTagView(it)) }
            container.addView(header)
        }
        
        if (senseGroup.isForms) {
            val table = LinearLayout(context).apply { 
                orientation = LinearLayout.VERTICAL
                setPadding(30, 5, 10, 5)
            }
            senseGroup.senses.forEach { sense ->
                val row = FlowLayout(context).apply { setPadding(0, 5, 0, 5) }
                renderDefinition(row, sense.nodes)
                table.addView(row)
            }
            container.addView(table)
        } else {
            senseGroup.senses.forEach { sense ->
                val senseLayout = LinearLayout(context).apply { 
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(30, 5, 10, 5)
                }
                senseLayout.addView(TextView(context).apply {
                    text = "${sense.index}. "
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setPadding(0, 0, 10, 0)
                })
                
                val contentContainer = FlowLayout(context).apply {
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
                    container.addView(TextView(context).apply {
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
                    val box = LinearLayout(context).apply {
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
                        box.addView(TextView(context).apply { text = node.japanese; setTextColor(Color.WHITE); textSize = 16f; setPadding(0, 0, 0, 10) })
                        node.english?.let { en -> box.addView(TextView(context).apply { text = en; setTextColor(Color.LTGRAY); textSize = 14f }) }
                    } else if (node.content != null) {
                        val flow = FlowLayout(context)
                        renderDefinition(flow, node.content)
                        box.addView(flow)
                    }
                    container.addView(box)
                    i++
                }
                is DefinitionNode.ListBlock -> {
                    val block = LinearLayout(context).apply { 
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 10) } 
                    }
                    node.items.forEach { itemNodes ->
                        val itemRow = FlowLayout(context).apply { setPadding(0, 0, 0, 5) }
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
                    val groupContainer = if (node.isInline) FlowLayout(context) else LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    if (groupContainer is FlowLayout) {
                        renderDefinition(groupContainer, node.nodes)
                    } else {
                        // If it's a vertical group, each child should still be rendered as a flow if it has inline elements
                        // But for simplicity, let's just use a nested FlowLayout for everything for now
                        val flow = FlowLayout(context)
                        renderDefinition(flow, node.nodes)
                        (groupContainer as LinearLayout).addView(flow)
                    }
                    container.addView(groupContainer)
                    i++
                }
            }
        }
    }

    private fun handleGamepad(event: KeyEvent): Boolean {
        if (closed) return false
        val root: FrameLayout = this
        if (root.findViewWithTag<View>("manual_input_blocker") != null) return false
        
        val prefs = context.getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)
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

    /**
     * #72: the single "go back one layer" action — manual input → neighbour
     * panel → dictionary panel → the whole overlay.
     *
     * Shared by the empty-space tap, the system back key and the gamepad's back
     * button, so all three can never drift apart.
     *
     * @param allowDismiss false when the gesture was a tap that landed on a
     *   character: that tap was a lookup, not a dismissal, so the overlay must
     *   stay. Back and gamepad-back have no coordinates to test, so they always
     *   dismiss.
     * @return true when something was closed, so a caller holding an input
     *   event knows whether it did anything.
     */
    private fun closeNextLayer(root: FrameLayout, allowDismiss: Boolean = true): Boolean {
        if (root.findViewWithTag<View>("manual_input_blocker") != null) {
            closeManualInput(root)
            return true
        }
        if (controller.isAlternativesVisible) {
            toggleAlternativesPanel(root, controller.currentTappedLineIdx, controller.currentTappedCharIdxInLine, root.width > root.height)
            return true
        }
        val dictPanel = root.findViewWithTag<View>("correction_ui_root")
        if (dictPanel != null || controller.isDictionaryVisible) {
            dictPanel?.let { root.removeView(it) }
            controller.isDictionaryVisible = false
            resetHighlights()
            return true
        }
        if (!allowDismiss) return false
        closeWholeOverlay()
        return true
    }

    /**
     * #72: the documented way to own back in a window, registered alongside the
     * view-tree handler that does the work here.
     *
     * On this device the registration succeeds and the callback is never
     * invoked — the platform does not route back navigation to a
     * `TYPE_ACCESSIBILITY_OVERLAY` window — so back falls through to the root
     * view's `dispatchKeyEvent`. Both are wired to [handleBack], which
     * de-duplicates, so a device that honours the dispatcher instead does not
     * close two layers per press.
     *
     * See [unregisterBackCallback] for why this must not outlive the window.
     */
    private fun registerBackCallback(root: FrameLayout) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val dispatcher = root.findOnBackInvokedDispatcher() ?: return
        val callback = android.window.OnBackInvokedCallback { handleBack(root) }
        try {
            dispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback
            )
            backDispatcher = dispatcher
            backCallback = callback
        } catch (e: Exception) {
            Log.e("OcrAccessibilityService", "Could not register back callback", e)
        }
    }

    /**
     * Unregistered before the window goes away — a callback left on a dead
     * dispatcher would keep swallowing back after the overlay closed, which is
     * worse than not handling back at all.
     */
    private fun unregisterBackCallback() {
        val callback = backCallback ?: return
        try {
            backDispatcher?.unregisterOnBackInvokedCallback(callback)
        } catch (e: Exception) {
            Log.e("OcrAccessibilityService", "Could not unregister back callback", e)
        }
        backCallback = null
        backDispatcher = null
    }

    /**
     * #72: one back press, whichever path delivered it. The window's back
     * callback and the view tree are both wired up, and a platform that used
     * both for a single press would otherwise close two layers.
     */
    private fun handleBack(root: FrameLayout) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackHandledAt < backDedupeMs) return
        lastBackHandledAt = now
        closeNextLayer(root)
    }

    private fun handleGamepadBack(root: FrameLayout) {
        if (closeNextLayer(root)) updateCursor()
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
        val prefs = context.getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)
        val delay = prefs.getInt("repeat_delay", 500).toLong()
        val rate = prefs.getInt("repeat_rate", 20)
        val interval = controller.getRepeatInterval(rate)
        val layoutSwap = prefs.getBoolean("layout_swap", false)
        val action = controller.resolveGamepadAction(keyCode, layoutSwap)

        repeatJob = scope.launch {
            kotlinx.coroutines.delay(delay)
            while (isActive && currentRepeatingKeyCode == keyCode) {
                if (closed) break
                val root: FrameLayout = this@OcrOverlayView
                
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
        if (closed) return
        val root: FrameLayout = this
        
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
        if (closed) return
        val root: FrameLayout = this
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
        if (closed) return
        val root: FrameLayout = this
        val contentContainer = root.findViewWithTag<FrameLayout>("content_container") ?: return
        controller.ensureCursorPosition()

        val charBox = controller.activeLineResults.getOrNull(controller.currentTappedLineIdx)?.charBoxes?.getOrNull(controller.currentTappedCharIdxInLine)
        if (charBox != null) {
            if (cursorView == null) {
                cursorView = View(context).apply {
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

    /**
     * Runtime status-bar height backing the #64 strip (scrim fade, display
     * bitmap fade, swipe-dismiss zone). Framework dimen when present, else
     * [OverlayBackdrop.STATUS_BAR_HEIGHT_FALLBACK_DP].
     */
    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) {
            try {
                return resources.getDimensionPixelSize(resId)
            } catch (e: Exception) {
                Log.w("OcrOverlayView", "status_bar_height lookup failed, using fallback", e)
            }
        }
        return (OverlayBackdrop.STATUS_BAR_HEIGHT_FALLBACK_DP * resources.displayMetrics.density).roundToInt()
    }

    /**
     * Display copy of the screenshot for the overlay ImageView (#64).
     * SOLID (or no strip) returns [src] itself; otherwise a mutable copy
     * whose top [stripPx] rows are faded per [OverlayBackdrop.stripAlphaAt].
     * Dimensions and position are unchanged, and OCR always reads the
     * pristine [src] — so box coordinates and hit-testing are unaffected.
     */
    private fun createOverlayDisplayBitmap(src: Bitmap, stripPx: Int): Bitmap {
        if (OverlayBackdrop.STATUS_STRIP_MODE == StatusStripMode.SOLID || stripPx <= 0) return src
        if (src.isRecycled) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val rows = stripPx.coerceAtMost(out.height)
        val pixels = IntArray(out.width)
        for (y in 0 until rows) {
            val stripAlpha = OverlayBackdrop.stripAlphaAt(y, stripPx, OverlayBackdrop.STATUS_STRIP_MODE)
            if (stripAlpha >= 255) continue
            out.getPixels(pixels, 0, out.width, 0, y, out.width, 1)
            for (x in pixels.indices) {
                val p = pixels[x]
                pixels[x] = p and 0x00FFFFFF or ((Color.alpha(p) * stripAlpha / 255) shl 24)
            }
            out.setPixels(pixels, 0, out.width, 0, y, out.width, 1)
        }
        return out
    }

    /** True while a manual-input blocker is up: an outside tap must not close. */
    fun hasManualInputBlocker(): Boolean =
        findViewWithTag<View>("manual_input_blocker") != null

    /**
     * One key event while the overlay is open, routed by whoever receives key
     * events (the service's key filtering). Back closes one layer exactly like
     * an empty-space tap; on API 30-32 and any key the system routes to the
     * service, back can arrive here instead of at [dispatchKeyEvent].
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        // #72: act on DOWN, not UP, so a press still works if the UP is never
        // delivered.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) handleBack(this)
            return true
        }
        Log.d("OcrAccessibilityService", "onKeyEvent: keyCode=${event.keyCode}, action=${event.action}")
        if (handleGamepad(event)) return true
        return false
    }

    /**
     * #57: one back press delivered by the host rather than by the key or
     * dispatcher paths — the share activity's `onBackPressed`. Same
     * de-duplicated [handleBack], so an activity and the service cannot
     * diverge on what back closes.
     */
    fun handleBackKey() {
        handleBack(this)
    }

    /**
     * Close the whole overlay through the one dismissal path. Both the close
     * button and [closeNextLayer]'s last step land here, so there is exactly
     * one way the overlay goes away.
     */
    private fun closeWholeOverlay() {
        if (closed) return
        onClosed()
        host.dismissOverlay()
    }

    /**
     * Idempotent teardown, called by [closeWholeOverlay] and by the host when
     * it removes the overlay for its own reasons (screen off, config change).
     * Every overlay-scoped job is stopped and the back callback released
     * before the window goes away.
     */
    fun onClosed() {
        if (closed) return
        closed = true
        ocrJob?.cancel()
        ocrJob = null
        statusGen++
        lookupJob?.cancel()
        lookupJob = null
        repeatJob?.cancel()
        repeatJob = null
        unregisterBackCallback()
        scope.cancel()
    }

    /** Wraps children onto rows, baseline-aligned: headword flows and sense
     *  rows. Moved verbatim from the service. */
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

/** #57: the one logo-drawable lookup for both the floating button and the
 *  overlay's close button, so the two never drift apart. */
internal fun logoButtonBackground(context: Context): Drawable =
    ContextCompat.getDrawable(context, R.drawable.logo)!!
