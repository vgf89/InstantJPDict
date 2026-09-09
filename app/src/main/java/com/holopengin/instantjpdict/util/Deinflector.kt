package com.holopengin.instantjpdict.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.Reader

data class DeinflectionRule(
    val kanaIn: String,
    val kanaOut: String,
    val rulesIn: List<String>,
    val rulesOut: List<String>,
    /** Top-level deinflect.json group key (e.g. "past", "causative passive").
     *  Not in the JSON payloads — filled in at load time from the map key. */
    val reason: String = ""
)

data class DeinflectionResult(
    val term: String,
    /** Human-readable reason labels, outermost step first (e.g. ["past"]). */
    val reasons: List<String>,
    val type: List<String>
)

/** Surface form plus the deinflection steps that produced a dictionary term.
 *  Carried alongside lookup candidates so the popup can show the chain
 *  (e.g. 食べた → 食べる · past) without extra deinflect runs. */
data class DeinflectionChain(
    val surface: String,
    val steps: List<String>
) {
    /** Compact one-line rendering: "surface → term · step1 · step2". */
    fun label(term: String): String =
        if (steps.isEmpty()) "$surface → $term"
        else "$surface → $term · " + steps.joinToString(" · ")
}

class Deinflector(reader: Reader) {
    private val rules: List<DeinflectionRule>

    init {
        val loadedRules = mutableListOf<DeinflectionRule>()
        try {
            val type = object : TypeToken<Map<String, List<DeinflectionRule>>>() {}.type
            val rawRules: Map<String, List<DeinflectionRule>> = Gson().fromJson(reader, type)

            rawRules.forEach { (reason, ruleList) ->
                loadedRules.addAll(ruleList.map { it.copy(reason = reason) })
            }
        } catch (e: Exception) {
            // Simplified logging or pass a logger
        }
        rules = loadedRules
    }

    fun deinflect(text: String): List<DeinflectionResult> {
        val results = mutableListOf<DeinflectionResult>()
        results.add(DeinflectionResult(text, emptyList(), emptyList()))

        var i = 0
        while (i < results.size) {
            val current = results[i]
            if (current.term.length < 2) {
                i++
                continue
            }

            for (rule in rules) {
                if (current.term.endsWith(rule.kanaIn)) {
                    val root = current.term.substring(0, current.term.length - rule.kanaIn.length) + rule.kanaOut

                    if (root.isNotEmpty()) {
                        val newResult = DeinflectionResult(
                            term = root,
                            reasons = current.reasons + rule.reason,
                            type = rule.rulesOut
                        )

                        if (!results.any { it.term == newResult.term }) {
                            results.add(newResult)
                        }
                    }
                }
            }
            i++
        }
        return results
    }
}
