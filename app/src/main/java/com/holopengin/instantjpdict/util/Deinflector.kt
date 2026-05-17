package com.holopengin.instantjpdict.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.util.Log
import java.io.InputStreamReader

data class DeinflectionRule(
    val kanaIn: String,
    val kanaOut: String,
    val rulesIn: Int,
    val rulesOut: Int
)

data class DeinflectionResult(
    val term: String,
    val reasons: List<String>,
    val rules: Int // Bitmask of PoS tags
)

class Deinflector(context: Context) {
    private val rules: List<DeinflectionRule>

    init {
        val loadedRules = mutableListOf<DeinflectionRule>()
        try {
            val inputStream = context.assets.open("deinflect.json")
            val reader = InputStreamReader(inputStream)
            // Yomitan deinflect.json is an array of rule groups
            val type = object : TypeToken<List<DeinflectionGroup>>() {}.type
            val groups: List<DeinflectionGroup> = Gson().fromJson(reader, type)
            
            groups.forEach { group ->
                loadedRules.addAll(group.rules)
            }
            Log.d("Deinflector", "Loaded ${loadedRules.size} rules from deinflect.json")
        } catch (e: Exception) {
            Log.e("Deinflector", "Failed to load deinflect.json", e)
        }
        rules = loadedRules
    }

    private data class DeinflectionGroup(
        val name: String,
        val rules: List<DeinflectionRule>
    )

    fun deinflect(text: String): List<DeinflectionResult> {
        val results = mutableListOf<DeinflectionResult>()
        // Initial state: 0 means no specific PoS requirement yet
        results.add(DeinflectionResult(text, emptyList(), 0))

        var i = 0
        while (i < results.size) {
            val current = results[i]
            
            for (rule in rules) {
                if (current.term.endsWith(rule.kanaIn)) {
                    // Bitwise check: if current.rules is 0, it's the start (can match any rule)
                    // Otherwise, the current rule's output must satisfy the required input
                    val canApply = current.rules == 0 || (current.rules and rule.rulesOut) != 0
                    
                    if (canApply) {
                        val root = current.term.substring(0, current.term.length - rule.kanaIn.length) + rule.kanaOut
                        val newResult = DeinflectionResult(
                            term = root,
                            reasons = current.reasons + listOf(rule.kanaIn),
                            rules = rule.rulesIn
                        )
                        
                        if (!results.any { it.term == newResult.term && it.rules == newResult.rules }) {
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
