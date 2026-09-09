package com.holopengin.instantjpdict.util

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

/**
 * JMdict redirect (pointer-entry) handling (#65).
 *
 * Variant spellings / search-only forms are entries whose definitions carry
 * no glossary text — only `a`-tag links with `?query=<headword>&wildcards=off`
 * hrefs (e.g. あかーん → あかん, rendered today as dead "⟶, あかん" text).
 * [extractTargets] returns those headwords so lookup can resolve them;
 * entries with any real definitional content yield nothing (their `see also`
 * links are not redirects).
 *
 * Pure Kotlin + Gson so it is JVM-unit-testable.
 */
object DictionaryRedirects {
    /** `data.content` values that carry no definitional text. */
    private val NON_DEFINITIONAL = setOf("references", "refGlosses")

    /**
     * Headwords this entry redirects to, or empty when the entry is not a
     * pure pointer. Malformed JSON also yields empty. Capped at
     * [maxTargets] (observed data tops out at 2).
     */
    fun extractTargets(definitionsJson: String, maxTargets: Int = 3): List<String> {
        val root = try {
            Gson().fromJson(definitionsJson, JsonElement::class.java)
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()
        val targets = mutableListOf<String>()
        var hasDefinitional = false
        fun visit(el: JsonElement) {
            when {
                el.isJsonObject -> {
                    val o = el.asJsonObject
                    if ((o.get("tag") as? JsonPrimitive)?.asString == "a") {
                        val href = (o.get("href") as? JsonPrimitive)?.asString ?: ""
                        val raw = href.substringAfter("?query=", "").substringBefore("&")
                        if (raw.isNotEmpty()) {
                            val target = try {
                                java.net.URLDecoder.decode(raw, "UTF-8")
                            } catch (_: Exception) {
                                raw
                            }
                            if (target.isNotBlank()) targets.add(target)
                        }
                    }
                    val dataContent = (o.get("data") as? com.google.gson.JsonObject)
                        ?.get("content") as? JsonPrimitive
                    val dc = dataContent?.asString
                    if (dc != null && dc !in NON_DEFINITIONAL) hasDefinitional = true
                    for ((_, v) in o.entrySet()) visit(v)
                }
                el.isJsonArray -> el.asJsonArray.forEach(::visit)
            }
        }
        visit(root)
        if (hasDefinitional) return emptyList()
        return targets.distinct().take(maxTargets)
    }
}
