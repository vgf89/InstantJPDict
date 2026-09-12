package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.CharLm
import com.holopengin.instantjpdict.util.GapCandidates
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped model, read through the app's own loader (#44). A unit test over a synthetic
 * table proves the format; this proves the *asset in the repository* is the format, which is
 * the difference between "candidates are mis-ranked" and "candidates never arrive".
 */
class CharLmAssetTest {

    private fun asset(): File {
        val candidates = listOf(
            File("src/main/assets/lm/char_lm.bin"),
            File("app/src/main/assets/lm/char_lm.bin"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("lm/char_lm.bin not found from ${File(".").absolutePath} — " +
                     "run tools/pack_char_lm.py, it is a build input, not a generated file")
    }

    @Test
    fun the_shipped_asset_parses_with_the_apps_loader() {
        val bytes = asset().readBytes()
        assertTrue("asset looks truncated: ${bytes.size} bytes", bytes.size > 14_000_000)
        val lm = CharLm.fromBytes(bytes)
        assertNotNull("CharLm.fromBytes rejected the shipped asset", lm)
    }

    @Test
    fun the_model_knows_kana_and_punctuation_not_just_kanji() {
        val lm = CharLm.fromBytes(asset().readBytes())!!
        // The gap's evidence in vertical text is so often punctuation that a model which did
        // not carry it could not rank the candidates the recogniser offers.
        assertTrue("の missing", lm.count("の") > 0)
        assertTrue("、 missing", lm.count("、") > 0)
        assertTrue("。 missing", lm.count("。") > 0)
        // A 1-gram is at least as frequent as any extension of it.
        assertTrue(lm.count("の") >= lm.count("のは"))
    }

    @Test
    fun the_shipped_model_ranks_a_gap_pool() {
        val lm = CharLm.fromBytes(asset().readBytes())!!
        val pool = listOf('、', '思', '阿', 'ゐ')
        val ranked = lm.rank("私は", pool)
        assertTrue("ranking dropped candidates: $ranked", ranked.toSet() == pool.toSet())
        // Concrete evidence, not a general law about rare characters: after 私は the model
        // has seen 思 (思う) constantly and ゐ essentially never, so the text prior must
        // prefer the former. This is the whole point of shipping the asset.
        assertTrue("the prior did not prefer 思 over ゐ in 私は: $ranked",
            ranked.indexOf('思') < ranked.indexOf('ゐ'))
    }

    @Test
    fun offering_never_returns_the_placeholder_itself() {
        assertTrue(!GapCandidates.isOfferable('\u25CC'))
        assertTrue(GapCandidates.isOfferable('、'))
        assertTrue(GapCandidates.isOfferable('の'))
        assertTrue(!GapCandidates.isOfferable(' '))
    }
}
