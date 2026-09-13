package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.holopengin.instantjpdict.util.LicenseEntry
import com.holopengin.instantjpdict.util.LicenseIndex
import java.io.BufferedInputStream

/**
 * #70: the licences and attribution notices for everything the APK bundles.
 *
 * Reads `assets/licenses/INDEX.txt` and the files it points at — there is no
 * network path, because the app declares no `INTERNET` permission. Layout is a
 * component list on top and the selected component's notice plus full licence text
 * below, each individually scrollable: several components share one licence text
 * (every AndroidX module is Apache-2.0), so showing the text once per selection
 * beats repeating 11 KB of it twenty times down a single page.
 *
 * The EDRDG licence requires exactly this shape for a smartphone app — the
 * acknowledgement on a screen reached from a menu, not a line on a launch screen.
 */
object LicenseDialog {

    fun show(context: Context) {
        val index = try {
            readAsset(context, LicenseIndex.INDEX_ASSET)
                ?: error("${LicenseIndex.INDEX_ASSET} is missing from the APK")
        } catch (t: Throwable) {
            errorDialog(context, "Could not read ${LicenseIndex.INDEX_ASSET}: ${t.message}")
            return
        }

        val entries = try {
            LicenseIndex.parse(index)
        } catch (t: Throwable) {
            errorDialog(context, t.message ?: "Could not parse ${LicenseIndex.INDEX_ASSET}")
            return
        }
        if (entries.isEmpty()) {
            errorDialog(context, "${LicenseIndex.INDEX_ASSET} lists no components")
            return
        }

        val textCache = HashMap<String, String?>()
        fun read(path: String): String? = textCache.getOrPut(path) { readAsset(context, path) }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), 0)
        }

        root.addView(TextView(context).apply {
            text = "${entries.size} bundled components, models and dictionaries. " +
                "Every entry below is read from the APK — no network is used."
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(context, 8))
        })

        val detail = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        val detailScroll = ScrollView(context).apply {
            addView(detail)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        fun select(entry: LicenseEntry) {
            detail.text = LicenseIndex.render(entry) { read(it) }
            detailScroll.scrollTo(0, 0)
        }

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rows = mutableListOf<Pair<View, LicenseEntry>>()

        entries.forEach { entry ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
                isClickable = true
                addView(TextView(context).apply {
                    text = entry.summary()
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                })
                setOnClickListener {
                    rows.forEach { (v, _) ->
                        v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                    setBackgroundColor(0x22000000)
                    select(entry)
                }
            }
            rows += row to entry
            listContainer.addView(row)
        }

        val listScroll = ScrollView(context).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(listScroll)
        root.addView(TextView(context).apply {
            text = "Licence text"
            textSize = 11f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(context, 8), 0, 0)
        })
        root.addView(detailScroll)

        rows.firstOrNull()?.let { (view, entry) ->
            view.setBackgroundColor(0x22000000)
            select(entry)
        }

        AlertDialog.Builder(context)
            .setTitle("Licenses")
            .setView(root)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun errorDialog(context: Context, message: String) {
        AlertDialog.Builder(context)
            .setTitle("Licenses")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Read one asset as UTF-8, or null if it is not in the APK. */
    private fun readAsset(context: Context, path: String): String? = try {
        BufferedInputStream(context.assets.open(path)).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        null
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** Kept for callers that want the parsed list without opening a dialog. */
    fun entries(context: Context): List<LicenseEntry> {
        val index = readAsset(context, LicenseIndex.INDEX_ASSET) ?: return emptyList()
        return LicenseIndex.parse(index)
    }
}
