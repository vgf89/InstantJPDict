package com.holopengin.instantjpdict

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Handles adb shell am broadcast -a com.holopengin.instantjpdict.SET_BACKEND --es backend onnx|ncnn
 * Writes SharedPreferences instant_jp_dict_prefs ocr_backend for A/B switching without UI.
 * Part of #14.
 */
class BackendSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_BACKEND) return
        val backend = intent.getStringExtra(EXTRA_BACKEND)?.lowercase()?.trim() ?: run {
            Log.w(TAG, "SET_BACKEND missing --es backend extra")
            return
        }
        if (backend != "onnx" && backend != "ncnn") {
            Log.w(TAG, "SET_BACKEND invalid backend=$backend expected onnx|ncnn")
            Toast.makeText(context, "Invalid backend: $backend", Toast.LENGTH_SHORT).show()
            return
        }
        context.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(OcrEngine.PREF_BACKEND, backend).apply()
        Log.i(TAG, "ocr_backend set to $backend via broadcast $ACTION_SET_BACKEND")
        // Keep a toast for adb visibility via logcat; toasts need UI context, use app context
        try {
            Toast.makeText(context.applicationContext, "OCR backend: $backend", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
        // Also broadcast a result for cron gate
        setResultCode(android.app.Activity.RESULT_OK)
        setResultData(backend)
    }

    companion object {
        const val TAG = "BackendSwitchReceiver"
        const val ACTION_SET_BACKEND = "com.holopengin.instantjpdict.SET_BACKEND"
        const val EXTRA_BACKEND = "backend"
    }
}
