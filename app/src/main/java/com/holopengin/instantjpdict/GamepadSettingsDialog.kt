package com.holopengin.instantjpdict

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GamepadSettingsDialog(
    onDismiss: () -> Unit,
    context: Context
) {
    val prefs = remember { context.getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE) }
    
    var layoutSwap by remember { mutableStateOf(prefs.getBoolean("layout_swap", false)) }
    var globalShortcutEnabled by remember { mutableStateOf(prefs.getBoolean("global_shortcut_enabled", true)) }
    var repeatDelay by remember { mutableStateOf(prefs.getInt("repeat_delay", 500).toFloat()) }
    var repeatRate by remember { mutableStateOf(prefs.getInt("repeat_rate", 20).toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gamepad Controls") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Layout: ${if (layoutSwap) "B/A (Nintendo)" else "A/B (Xbox)"}", modifier = Modifier.weight(1f))
                    Switch(checked = layoutSwap, onCheckedChange = { 
                        layoutSwap = it
                        prefs.edit().putBoolean("layout_swap", it).apply()
                    })
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("L1+R1 Global Shortcut", modifier = Modifier.weight(1f))
                    Switch(checked = globalShortcutEnabled, onCheckedChange = { 
                        globalShortcutEnabled = it
                        prefs.edit().putBoolean("global_shortcut_enabled", it).apply()
                    })
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Repeat Delay: ${repeatDelay.toInt()}ms")
                Slider(
                    value = repeatDelay,
                    onValueChange = { 
                        repeatDelay = it
                        prefs.edit().putInt("repeat_delay", it.toInt()).apply()
                    },
                    valueRange = 100f..1000f
                )

                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Repeat Rate: ${repeatRate.toInt()} repeats/s")
                Slider(
                    value = repeatRate,
                    onValueChange = { 
                        repeatRate = it
                        prefs.edit().putInt("repeat_rate", it.toInt()).apply()
                    },
                    valueRange = 1f..60f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
