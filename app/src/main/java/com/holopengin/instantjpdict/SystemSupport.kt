package com.holopengin.instantjpdict

import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent

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
