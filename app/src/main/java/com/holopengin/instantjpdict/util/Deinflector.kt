package com.holopengin.instantjpdict.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.Reader

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

class Deinflector(reader: Reader) {
    private val rules: List<DeinflectionRule>

    init {
        val loadedRules = mutableListOf<DeinflectionRule>()
        try {
            val type = object : TypeToken<Map<String, List<DeinflectionRule>>>() {}.type
            val rawRules: Map<String, List<DeinflectionRule>> = Gson().fromJson(reader, type)
            
            rawRules.forEach { (_, ruleList) ->
                loadedRules.addAll(ruleList)
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
                            reasons = current.reasons + listOf(rule.kanaIn),
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
