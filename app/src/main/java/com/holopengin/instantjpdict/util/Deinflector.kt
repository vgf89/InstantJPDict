package com.holopengin.instantjpdict.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.util.Log
import java.io.InputStreamReader

data class DeinflectionRule(
    val kanaIn: String,
    val kanaOut: String,
    val rulesIn: List<String>,
    val rulesOut: List<String>
)

data class DeinflectionResult(
    val term: String,
    val reasons: List<String>,
    val type: List<String>
)

class Deinflector(context: Context) {
    private val rules: List<DeinflectionRule>

    init {
        val loadedRules = mutableListOf<DeinflectionRule>()
        try {
            val inputStream = context.assets.open("deinflect.json")
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<Map<String, List<DeinflectionRule>>>() {}.type
            val rawRules: Map<String, List<DeinflectionRule>> = Gson().fromJson(reader, type)
            
            rawRules.forEach { (_, ruleList) ->
                loadedRules.addAll(ruleList)
            }
            Log.d("Deinflector", "Loaded ${loadedRules.size} rules")
        } catch (e: Exception) {
            Log.e("Deinflector", "Failed to load deinflect.json", e)
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
                    // For maximum recall, we allow the rule if it produced a valid root
                    // even if the intermediate PoS tags don't perfectly align.
                    // This is especially helpful with contracted forms like -chau.
                    val root = current.term.substring(0, current.term.length - rule.kanaIn.length) + rule.kanaOut
                    
                    if (root.isNotEmpty()) {
                        val newResult = DeinflectionResult(
                            term = root,
                            reasons = current.reasons + listOf(rule.kanaIn),
                            type = rule.rulesOut // These are the tags the DB entry should have
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
