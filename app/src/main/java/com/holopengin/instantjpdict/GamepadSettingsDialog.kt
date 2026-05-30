package com.holopengin.instantjpdict

import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

object GamepadSettingsDialog {
    fun show(context: Context) {
        val prefs = context.getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)
        
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        // Layout Swap
        val layoutRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val layoutText = TextView(context).apply {
            val isNintendo = prefs.getBoolean("layout_swap", false)
            text = "Layout: ${if (isNintendo) "B/A (Nintendo)" else "A/B (Xbox)"}"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val layoutSwitch = SwitchCompat(context).apply {
            text = ""
            isChecked = prefs.getBoolean("layout_swap", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("layout_swap", isChecked).apply()
                layoutText.text = "Layout: ${if (isChecked) "B/A (Nintendo)" else "A/B (Xbox)"}"
            }
        }
        layoutRow.addView(layoutText)
        layoutRow.addView(layoutSwitch)
        layout.addView(layoutRow)

        // Global Shortcut
        val shortcutRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 32, 0, 32)
        }
        val shortcutText = TextView(context).apply {
            text = "L1+R1 Global Shortcut"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val shortcutSwitch = SwitchCompat(context).apply {
            text = ""
            isChecked = prefs.getBoolean("global_shortcut_enabled", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("global_shortcut_enabled", isChecked).apply()
            }
        }
        shortcutRow.addView(shortcutText)
        shortcutRow.addView(shortcutSwitch)
        layout.addView(shortcutRow)

        // Repeat Delay
        val delayValue = prefs.getInt("repeat_delay", 500)
        val delayText = TextView(context).apply { text = "Repeat Delay: ${delayValue}ms" }
        val delaySeek = SeekBar(context).apply {
            max = 900 // 100 to 1000
            progress = delayValue - 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + 100
                    delayText.text = "Repeat Delay: ${value}ms"
                    prefs.edit().putInt("repeat_delay", value).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(delayText)
        layout.addView(delaySeek)

        // Repeat Rate
        val rateValue = prefs.getInt("repeat_rate", 20)
        val rateText = TextView(context).apply { 
            text = "Repeat Rate: $rateValue repeats/s" 
            setPadding(0, 32, 0, 0)
        }
        val rateSeek = SeekBar(context).apply {
            max = 59 // 1 to 60
            progress = rateValue - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + 1
                    rateText.text = "Repeat Rate: $value repeats/s"
                    prefs.edit().putInt("repeat_rate", value).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(rateText)
        layout.addView(rateSeek)

        AlertDialog.Builder(context)
            .setTitle("Gamepad Controls")
            .setView(layout)
            .setPositiveButton("Close", null)
            .show()
    }
}
