package com.holopengin.instantjpdict.util

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.holopengin.instantjpdict.OcrEngine

/**
 * Pitch-accent support (#43): Yamitan-compatible pitch payloads from any
 * imported pitch dictionary (we ship a Kanjium-derived one), plus the pure
 * mora/contour math the renderer draws.
 *
 * No Android dependency in the math — only [isEnabled]/[setEnabled] touch
 * SharedPreferences, so the rest is JVM-unit-testable.
 */
object PitchAccent {
    /** SharedPreferences key for the main-activity checkbox. */
    const val PREF_PITCH_ENABLED = "pitch_accent_enabled"
    const val DEF_PITCH_ENABLED = false

    /**
     * Small kana (拗音) fuse with the preceding kana into one mora: きょ is one
     * mora. っ, ー and ん each count as their OWN mora — がっこう is 4 morae
     * (が・っ・こ・う) and コーヒー is 4 (コ・ー・ヒ・ー).
     */
    private const val FUSING = "ぁぃぅぇぉゃゅょゎゕゖァィゥェォャュョヮヵヶ"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_PITCH_ENABLED, DEF_PITCH_ENABLED)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_PITCH_ENABLED, enabled).apply()
    }

    /**
     * Split a reading into morae. Each kana is one mora, except small kana
     * (ゃゅょ…), which fuse with the preceding kana: きょう = きょ + う (2).
     * っ, ー and ん keep their own mora: がっこう = 4, コーヒー = 4.
     */
    fun moraeOf(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        val out = mutableListOf<StringBuilder>()
        for (c in reading) {
            if (out.isNotEmpty() && FUSING.contains(c)) out[out.size - 1].append(c)
            else out.add(StringBuilder().append(c))
        }
        return out.map { it.toString() }
    }

    /**
     * Mora index to mark as the downstep carrier, or null when there is none.
     *
     * The accent position alone determines the Tokyo contour: after an initial
     * low, morae up to the accent are high and everything after falls — so a
     * single mark on the mora before the fall encodes the same information as
     * the whole contour. It is also *strictly* better than drawing the contour
     * alone: heiban (0) and odaka (position == mora count) produce an identical
     * in-word contour (L H…H), and only the mark tells them apart. Verified over
     * all 124k Kanjium rows — the only contour→position collisions are exactly
     * those two classes.
     */
    fun markIndex(moraCount: Int, position: Int): Int? {
        if (moraCount <= 0) return null
        if (position <= 0) return null // heiban: no fall within the word
        return (position - 1).coerceIn(0, moraCount - 1)
    }

    /** Numeric accent position, circled for 0..9 (Yomitan-style fallback label). */
    fun formatPosition(position: Int): String {
        val circled = "⓪①②③④⑤⑥⑦⑧⑨"
        return if (position in 0..9) circled[position].toString() else position.toString()
    }

    /**
     * Downstep positions from a stored definition payload, or null when this
     * entry is not pitch data. Detection is by payload shape
     * (`{"reading":…, "pitches":[{"position":N},…]}`), so it works for ANY
     * Yomitan pitch dictionary the user imports, not just the Kanjium build.
     */
    fun positionsOf(definitionsJson: String): List<Int>? {
        val root: JsonElement = try {
            JsonParser.parseString(definitionsJson)
        } catch (_: Exception) {
            return null
        }
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val pitches = obj.get("pitches") ?: return null
        if (!pitches.isJsonArray) return null
        if (!obj.has("reading")) return null
        val out = mutableListOf<Int>()
        for (p in pitches.asJsonArray) {
            val n = p.takeIf { it.isJsonObject }?.asJsonObject?.get("position") ?: continue
            val value = n.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asInt ?: continue
            out.add(value)
        }
        return out.distinct().sorted()
    }

    /** Reading of a stored pitch payload (identity when absent). */
    fun readingOf(definitionsJson: String): String? {
        val root: JsonElement = try {
            JsonParser.parseString(definitionsJson)
        } catch (_: Exception) {
            return null
        }
        if (!root.isJsonObject) return null
        val r = root.asJsonObject.get("reading") ?: return null
        return r.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString
    }
}
