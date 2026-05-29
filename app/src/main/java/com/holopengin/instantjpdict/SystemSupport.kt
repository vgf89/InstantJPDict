package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryEntry
import kotlin.math.abs

data class JpDictRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun centerX(): Int = left + width() / 2
    fun centerY(): Int = top + height() / 2
}

fun JpDictRect.toAndroidRect(): Rect = Rect(left, top, right, bottom)
fun Rect.toJpDictRect(): JpDictRect = JpDictRect(left, top, right, bottom)

interface DictionaryProvider {
    suspend fun findByTexts(texts: List<String>): List<DictionaryEntry>
}

class AndroidDictionaryProvider(private val context: Context) : DictionaryProvider {
    override suspend fun findByTexts(texts: List<String>): List<DictionaryEntry> {
        val db = AppDatabase.getDatabase(context)
        return db.dictionaryDao().findByTexts(texts)
    }
}

object JpDictGravity {
    const val NONE = 0
    const val TOP = 1
    const val BOTTOM = 2
    const val LEFT = 4
    const val RIGHT = 8
    const val START = 16
    const val END = 32
    const val CENTER_HORIZONTAL = 64
    const val CENTER_VERTICAL = 128
    const val CENTER = CENTER_HORIZONTAL or CENTER_VERTICAL
}

fun Int.toAndroidGravity(): Int {
    var g = Gravity.NO_GRAVITY
    if (this and JpDictGravity.TOP != 0) g = g or Gravity.TOP
    if (this and JpDictGravity.BOTTOM != 0) g = g or Gravity.BOTTOM
    if (this and JpDictGravity.LEFT != 0) g = g or Gravity.LEFT
    if (this and JpDictGravity.RIGHT != 0) g = g or Gravity.RIGHT
    if (this and JpDictGravity.START != 0) g = g or Gravity.START
    if (this and JpDictGravity.END != 0) g = g or Gravity.END
    if (this and JpDictGravity.CENTER_HORIZONTAL != 0) g = g or Gravity.CENTER_HORIZONTAL
    if (this and JpDictGravity.CENTER_VERTICAL != 0) g = g or Gravity.CENTER_VERTICAL
    return g
}

fun Int.toJpDictGravity(): Int {
    var g = JpDictGravity.NONE
    if (this and Gravity.TOP != 0) g = g or JpDictGravity.TOP
    if (this and Gravity.BOTTOM != 0) g = g or JpDictGravity.BOTTOM
    if (this and Gravity.LEFT != 0) g = g or JpDictGravity.LEFT
    if (this and Gravity.RIGHT != 0) g = g or JpDictGravity.RIGHT
    if (this and Gravity.START != 0) g = g or JpDictGravity.START
    if (this and Gravity.END != 0) g = g or JpDictGravity.END
    if (this and Gravity.CENTER_HORIZONTAL != 0) g = g or JpDictGravity.CENTER_HORIZONTAL
    if (this and Gravity.CENTER_VERTICAL != 0) g = g or JpDictGravity.CENTER_VERTICAL
    return g
}

object JpDictKeyEvent {
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_BACK = 4
    const val KEYCODE_ENTER = 66
    const val KEYCODE_ESCAPE = 111
    
    const val KEYCODE_BUTTON_A = 96
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_BUTTON_X = 99
    const val KEYCODE_BUTTON_Y = 100
    const val KEYCODE_BUTTON_L1 = 102
    const val KEYCODE_BUTTON_R1 = 103
    const val KEYCODE_BUTTON_L2 = 104
    const val KEYCODE_BUTTON_R2 = 105
}

fun MotionEvent.getJoystickKeyCode(): Int {
    fun getCenteredAxis(event: MotionEvent, axis1: Int, axis2: Int): Float {
        val range1 = event.device?.getMotionRange(axis1, event.source)
        val v1 = if (range1 != null) event.getAxisValue(axis1) else 0f
        val range2 = event.device?.getMotionRange(axis2, event.source)
        val v2 = if (range2 != null) event.getAxisValue(axis2) else 0f
        
        val v = if (abs(v1) > abs(v2)) v1 else v2
        return if (abs(v) > 0.3f) v else 0f
    }

    val x = getCenteredAxis(this, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X)
    val y = getCenteredAxis(this, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y)

    val threshold = 0.5f
    return when {
        x > threshold -> KeyEvent.KEYCODE_DPAD_RIGHT
        x < -threshold -> KeyEvent.KEYCODE_DPAD_LEFT
        y > threshold -> KeyEvent.KEYCODE_DPAD_DOWN
        y < -threshold -> KeyEvent.KEYCODE_DPAD_UP
        else -> 0
    }
}

fun MotionEvent.getFocusCoords(): Pair<Float, Float> {
    var (sx, sy, c) = Triple(0f, 0f, 0)
    for (i in 0 until pointerCount) {
        if (actionMasked == MotionEvent.ACTION_POINTER_UP && i == actionIndex) continue
        sx += getX(i); sy += getY(i); c++
    }
    return if (c > 0) sx / c to sy / c else 0f to 0f
}

fun handleJoystick(
    event: MotionEvent,
    lastKeyCode: Int,
    onKeyChange: (Int) -> Unit,
    onSimulatedKeyEvent: (KeyEvent) -> Unit
): Boolean {
    if (event.action != MotionEvent.ACTION_MOVE) return false

    val currentKeyCode = event.getJoystickKeyCode()
    if (currentKeyCode != lastKeyCode) {
        if (lastKeyCode != 0) {
            onSimulatedKeyEvent(KeyEvent(KeyEvent.ACTION_UP, lastKeyCode))
        }
        onKeyChange(currentKeyCode)
        if (currentKeyCode != 0) {
            onSimulatedKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, currentKeyCode))
        }
    }
    return true
}
