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
     * High/low per mora for a downstep [position] (Yomitan semantics: 0 =
     * heiban, no downstep; N = pitch falls after mora N).
     *
     * Tokyo contour: heiban is L then H (no fall inside the word); atamadaka
     * (1) is H then L; otherwise mora 1 is L, morae up to the accented one
     * are H, the rest fall. A position at or beyond the last mora therefore
     * renders like heiban *within* the word — the difference only shows on
     * the following particle, which the renderer draws as a placeholder.
     */
    fun pattern(moraCount: Int, position: Int): List<Boolean> {
        if (moraCount <= 0) return emptyList()
        return when {
            position <= 0 -> List(moraCount) { it >= 1 }
            position == 1 -> List(moraCount) { it == 0 }
            else -> List(moraCount) { it >= 1 && it < position }
        }
    }

    /**
     * True when the downstep lands past the final mora (odaka, or the one
     * known-bad row whose position exceeds its mora count): the following
     * particle carries the fall, so the renderer draws [beyond-word
     * placeholder][com.holopengin.instantjpdict.PitchAccentLine.BEYOND_WORD].
     */
    fun fallsBeyondWord(moraCount: Int, position: Int): Boolean =
        moraCount > 0 && position > 0 && position >= moraCount

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
