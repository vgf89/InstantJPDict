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
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.gson.Gson
import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.KanaSizeFix
import com.holopengin.instantjpdict.util.CharLm
import com.holopengin.instantjpdict.util.InferLog
import com.holopengin.instantjpdict.util.Deinflector
import com.holopengin.instantjpdict.util.DeinflectionChain
import com.holopengin.instantjpdict.util.ComponentTable
import com.holopengin.instantjpdict.util.FuriganaAligner
import com.holopengin.instantjpdict.util.JapaneseUtil
import com.holopengin.instantjpdict.util.KanjiVariants
import com.holopengin.instantjpdict.util.OovCandidates
import com.holopengin.instantjpdict.util.OovSuggestions
import com.holopengin.instantjpdict.util.PitchAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class OcrAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var ocrButton: Button? = null
    /** Set in onDestroy so restore paths never re-add windows during teardown. (#60) */
    private var isDestroyed = false
    /** #57: the shared OCR overlay surface; null when no overlay is showing. */
    private var overlayView: OcrOverlayView? = null
    private lateinit var ocrEngine: OcrEngine
    private val controller = OcrOverlayStateController()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    

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
                Intent.ACTION_SCREEN_ON -> {
                    // Double-tap power to camera turns the screen off and on
                    // without locking: USER_PRESENT never follows, so the
                    // button would stay GONE until the next unlock. (#60)
                    // Stay hidden on the keyguard itself; USER_PRESENT shows it.
                    ensureFloatingButton()
                    val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (!km.isKeyguardLocked) floatingView?.visibility = View.VISIBLE
                }
                Intent.ACTION_USER_PRESENT -> {
                    ensureFloatingButton()
                    floatingView?.visibility = View.VISIBLE
                }
            }
        }
    }
    
    
    private val pressedKeys = mutableSetOf<Int>()
    private var lastGlobalTriggerTime = 0L



    override fun onCreate() {
        super.onCreate()
        ocrEngine = OcrEngine(this)
        OverlayEnvironment.prepare(this, controller, serviceScope)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
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
        if (overlayView != null) {
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
        // Reconnects must not duplicate the button; creation happens once. (#60)
        if (floatingView == null) addFloatingButton() else ensureFloatingButton()
    }

    private fun addFloatingButton() {
        if (floatingView?.isAttachedToWindow == true) return
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
            background = logoButtonBackground(this@OcrAccessibilityService)
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
                        if (fv.parent == overlayView) {
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
            if (overlayView != null) {
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

    /**
     * Re-add the floating button's existing view (with its visibility and
     * dragged position intact) if the system dropped its window without
     * telling us — e.g. the secure camera from double-tap power. (#60)
     * Cheap no-op when attached, so it is safe to call from every
     * window-state change. Never creates a second view.
     */
    private fun ensureFloatingButton() {
        val fv = floatingView ?: return
        if (!shouldReattachFloatingButton(true, fv.isAttachedToWindow, isDestroyed)) return
        try {
            val wm = windowManager ?: getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager = wm
            wm.addView(fv, floatingParams)
        } catch (e: Exception) {
            Log.e("OcrAccessibilityService", "Error restoring floating button", e)
        }
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

    private fun showScreenshotOverlay(image: Bitmap) {
        if (overlayView != null) return
        controller.resetState()
        Log.d("OcrAccessibilityService", "showScreenshotOverlay detThresh=${OcrEngine.getDetThresh(this)} longSide=${OcrEngine.getDetLongSide(this)}")

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

        // #57: the overlay UI is the shared OcrOverlayView now, hosted here on
        // an accessibility-overlay window and by ShareImageActivity on an
        // ordinary activity window. Only the host-specific bits differ: the
        // image source, the dismiss request and the floating-button sync.
        val host = object : OcrOverlayView.Host {
            override val bitmap: Bitmap get() = image
            override val ocrEngine: OcrEngine get() = this@OcrAccessibilityService.ocrEngine
            override val controller: OcrOverlayStateController get() = this@OcrAccessibilityService.controller

            override fun dismissOverlay() {
                hideScreenshotOverlay()
            }

            override fun requestSoftInputResize() {
                val root = overlayView ?: return
                val p = root.layoutParams as? WindowManager.LayoutParams ?: return
                p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                windowManager?.updateViewLayout(root, p)
            }

            override fun closeButtonOrigin(): Pair<Int, Int> =
                (floatingParams?.x ?: 100) to (floatingParams?.y ?: 100)

            override fun onCloseButtonMoved(x: Int, y: Int) {
                floatingParams?.x = x
                floatingParams?.y = y
            }
        }

        val view = OcrOverlayView(this, host)
        overlayView = view
        windowManager?.addView(view, params)
        view.startOcr()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val keyEvent = event ?: return super.onKeyEvent(event)
        
        // Handle global shortcut when overlay is NOT showing
        if (overlayView == null) {
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

        if (overlayView?.handleKeyEvent(keyEvent) == true) return true
        return super.onKeyEvent(keyEvent)
    }


    private fun hideScreenshotOverlay() {
        val view = overlayView ?: return
        view.onClosed()
        overlayView = null
        (floatingView?.parent as? android.view.ViewGroup)?.removeView(floatingView)
        if (view.isAttachedToWindow) try { windowManager?.removeViewImmediate(view) } catch (e: Exception) { Log.e("OcrAccessibilityService", "Error removing overlay", e) }
        floatingView?.visibility = View.VISIBLE
        controller.resetState()
        ensureFloatingButton()
        floatingView?.let { fv ->
            if (fv.isAttachedToWindow) try { windowManager?.updateViewLayout(fv, floatingParams) } catch (e: Exception) { Log.e("OcrAccessibilityService", "Error syncing floating button layout", e) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val view = overlayView
        if (view == null) {
            // No overlay open: restore the floating button if the system
            // dropped its window (e.g. returning from the camera). (#60)
            ensureFloatingButton()
            return
        }
        val eventPackage = event.packageName?.toString()
        if (eventPackage == null || eventPackage == packageName) return
        if (view.hasManualInputBlocker()) return
        if (System.currentTimeMillis() - controller.lastManualInputCloseTime < 1000) return
        if (event.isFullScreen != true) return
        hideScreenshotOverlay()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        isDestroyed = true
        super.onDestroy()
        try { unregisterReceiver(overlayControllerReceiver) } catch (e: Exception) {}
        hideScreenshotOverlay()
        floatingView?.let { if (it.isAttachedToWindow) windowManager?.removeView(it) }
        ocrEngine.close()
    }

}
