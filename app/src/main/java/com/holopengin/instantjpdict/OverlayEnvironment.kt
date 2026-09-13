package com.holopengin.instantjpdict

import android.content.Context
import com.google.gson.Gson
import com.holopengin.instantjpdict.util.CharLm
import com.holopengin.instantjpdict.util.ComponentTable
import com.holopengin.instantjpdict.util.Deinflector
import com.holopengin.instantjpdict.util.KanaOrthography
import com.holopengin.instantjpdict.util.KanjiVariants
import com.holopengin.instantjpdict.util.OovCandidates
import com.holopengin.instantjpdict.util.OovSuggestions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #57: the host-side wiring for [OcrOverlayStateController] in one place, so
 * the accessibility service and the image-share activity load exactly the same
 * stack. Duplicating it would let the two hosts drift on what the dictionary
 * popup can show — the same class of bug the "one assembly path" rule targets.
 *
 * Only the tunable-reading calls take a [Context]; the decision logic itself
 * lives in the controller, which is JVM-unit-testable.
 */
object OverlayEnvironment {
    private val gson = Gson()

    /**
     * Configure [controller] and start the optional #44 table loads on
     * [scope]. Called once per host, exactly like the service's `onCreate`.
     * The popup degrades to the head's own list until the tables land.
     */
    fun prepare(context: Context, controller: OcrOverlayStateController, scope: CoroutineScope) {
        controller.deinflector = Deinflector(java.io.InputStreamReader(context.assets.open("deinflect.json")))
        controller.dictionaryProvider = AndroidDictionaryProvider(context)
        controller.gson = gson

        // #44: component-derived popup candidates. Parsing the 266 KB component table is
        // cheap but not free, so it happens once off the main thread; until it lands (and
        // if it fails) the popup shows the head's own list, exactly as before.
        scope.launch {
            val table = withContext(Dispatchers.IO) {
                runCatching { ComponentTable.load(context) }.getOrNull()
            } ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching { KanjiVariants.install(context) }
                // #75: pre-reform orthography for the lookup query. Loaded here rather than
                // in either host so the share activity normalises exactly like the overlay.
                runCatching { KanaOrthography.install(context) }
            }
            controller.installOovSuggestions(OovCandidates(table)) {
                OovSuggestions.isEnabled(context)
            }
            // The 14 MB packed model behind the blank's candidate ranking. Mapped the same
            // way and off the main thread; a failure here only narrows the blank's list.
            controller.installCharLm(
                withContext(Dispatchers.IO) {
                    runCatching { CharLm.load(context) }.getOrNull()
                })
        }
    }
}
