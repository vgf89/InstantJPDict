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
            // The file is a Map<String, List<DeinflectionRule>>
            val type = object : TypeToken<Map<String, List<DeinflectionRule>>>() {}.type
            val rawRules: Map<String, List<DeinflectionRule>> = Gson().fromJson(reader, type)
            
            rawRules.forEach { (_, ruleList) ->
                loadedRules.addAll(ruleList)
            }
            Log.d("Deinflector", "Loaded ${loadedRules.size} rules from deinflect.json")
        } catch (e: Exception) {
            Log.e("Deinflector", "Failed to load deinflect.json", e)
        }
        rules = loadedRules
    }

    fun deinflect(text: String): List<DeinflectionResult> {
        val results = mutableListOf<DeinflectionResult>()
        // Initial state: empty types means it's the original word
        results.add(DeinflectionResult(text, emptyList(), emptyList()))

        var i = 0
        while (i < results.size) {
            val current = results[i]
            
            for (rule in rules) {
                if (current.term.endsWith(rule.kanaIn)) {
                    // Check if current.type (what the word IS) matches rule.rulesOut (what the rule EXPECTS)
                    // At the start, current.type is empty, so we allow anything.
                    // Subsequent steps require the types to intersect.
                    val canApply = current.type.isEmpty() || rule.rulesOut.any { it in current.type }
                    
                    if (canApply) {
                        val root = current.term.substring(0, current.term.length - rule.kanaIn.length) + rule.kanaOut
                        val newResult = DeinflectionResult(
                            term = root,
                            reasons = current.reasons + listOf(rule.kanaIn),
                            type = rule.rulesIn
                        )
                        
                        // Prevent duplicates and cycles
                        if (!results.any { it.term == newResult.term && it.type == newResult.type }) {
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
