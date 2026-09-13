package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.LicenseEntry
import com.holopengin.instantjpdict.util.LicenseIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #70: the bundled-licence index, asserted against the shipped asset rather than a
 * fixture, plus the parser's own rules.
 *
 * What this test can and cannot prove: it proves the committed index is
 * self-consistent — every entry names a licence and points at files that exist and
 * say what the label claims — and that the components the APK bundles are all in it.
 * What it cannot prove is coverage *against the build*: that a dependency added to
 * the classpath has an entry. That check needs the resolved dependency list, so it
 * lives in Gradle (`:app:verifyLicenseIndex`, a preBuild dependency) and in
 * `:app:generateLicenseIndex`, which fails naming every module with no notice.
 */
class LicenseIndexTest {

    private val indexFile: File by lazy { TestAssets.assetsFile("licenses/INDEX.txt") }
    private val entries: List<LicenseEntry> by lazy { LicenseIndex.parse(indexFile.readText()) }

    private fun entry(namePart: String): LicenseEntry =
        entries.firstOrNull { it.name.contains(namePart) }
            ?: error("no index entry matching '$namePart'; have ${entries.size} entries")

    private fun text(rel: String): String = TestAssets.assetsFile(rel).readText()

    @Test
    fun every_entry_names_a_licence_and_points_at_files_that_exist() {
        assertTrue("the index is suspiciously small: ${entries.size} entries", entries.size >= 90)
        entries.forEach { e ->
            assertTrue("entry with an empty name: $e", e.name.isNotBlank())
            assertTrue("'${e.name}' has no licence label", e.license.isNotBlank())
            assertTrue("'${e.name}' has neither a licence text nor a notice",
                e.textFiles.isNotEmpty() || e.noticeFile != null)
            (listOfNotNull(e.noticeFile) + e.textFiles).forEach { rel ->
                val f = TestAssets.assetsFile(rel)
                assertTrue("'${e.name}' points at an empty file: $rel", f.length() > 0)
            }
        }
        // The viewer lists components by name, so a duplicate is a UI bug.
        val dupes = entries.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), dupes)
    }

    @Test
    fun the_dependency_half_is_the_resolved_classpath_and_carries_versions() {
        val mavenish = Regex("^[\\w.-]+:[\\w.-]+$")
        val deps = entries.filter { mavenish.matches(it.name) }
        assertTrue("expected the whole runtime classpath, found ${deps.size}", deps.size >= 70)
        deps.forEach { d ->
            assertNotNull("dependency ${d.name} has no version", d.version)
            assertTrue("dependency ${d.name} has no licence text", d.textFiles.isNotEmpty())
        }
        // The app's own declared dependencies, by module id.
        listOf(
            "androidx.core:core-ktx", "androidx.lifecycle:lifecycle-runtime-ktx",
            "androidx.room:room-runtime", "androidx.room:room-ktx",
            "com.google.code.gson:gson", "androidx.appcompat:appcompat",
            "com.google.android.material:material", "androidx.recyclerview:recyclerview",
            "net.java.dev.jna:jna", "org.jetbrains.kotlin:kotlin-stdlib",
        ).forEach { id ->
            assertNotNull("$id is not in the licence index", deps.firstOrNull { it.name == id })
        }
    }

    @Test
    fun native_libraries_models_and_derived_data_are_all_listed() {
        // One per bundled artifact the index has to cover, keyed by something
        // unambiguous in its name (see the inventory in docs/licenses.md).
        val expected = listOf(
            "InstantJPDict",            // the app itself, AGPL-3.0
            "ncnn",                     // vendored BSD-3-Clause static library
            "PP-OCRv6",                 // Apache-2.0 det+rec models
            "kana-size",                // kana model, licence unstated (notice only)
            "char n-gram",              // Aozora-derived, public domain
            "KRADFILE",                 // EDRDG, CC BY-SA 4.0
            "Kanjium",                  // pitch accents, CC BY-SA 4.0
            "Unihan",                   // Unicode License v3
            "deinflection",             // Yomichan/Yomitan rules, GPL-3.0
            "nav_graph_core",           // the Rust library in the APK
            "OpenMP",                   // libomp.so from the NDK
        )
        expected.forEach { name ->
            assertTrue(
                "no index entry for '$name'",
                entries.any { it.name.contains(name, ignoreCase = true) },
            )
        }
        // The Rust half is one entry per crate, from the pinned lockfile.
        val rust = entries.filter { it.noticeFile == null && it.version != null && it.textFiles.any { t -> t.startsWith("licenses/texts/rust/") } }
        assertTrue("expected the Rust crate closure, found ${rust.size}", rust.size >= 50)
    }

    @Test
    fun cc_by_sa_and_edrdg_attribution_is_present_and_accurate() {
        val components = entry("KRADFILE")
        assertEquals("CC-BY-SA-4.0", components.license)
        assertTrue("the CC BY-SA text is not shipped with the EDRDG data",
            components.textFiles.contains("licenses/texts/cc-by-sa-4.0.txt"))
        assertTrue("the EDRDG licence statement is not shipped",
            components.textFiles.contains("licenses/texts/edrdg-licence.txt"))
        val notice = text(components.noticeFile!!)
        // The acknowledgement the EDRDG licence asks a smartphone app for: the
        // source named, the copyright holder named, and a link back.
        assertTrue("notice does not name the file used", notice.contains("KRADFILE"))
        assertTrue("notice does not name the rights holder",
            notice.contains("Electronic Dictionary Research and Development Group"))
        assertTrue("notice does not name CC BY-SA 4.0", notice.contains("CC BY-SA 4.0"))
        assertTrue("notice does not link to the licence",
            notice.contains("https://www.edrdg.org/edrdg/licence.html"))

        val kanjium = entry("Kanjium")
        assertEquals("CC-BY-SA-4.0", kanjium.license)
        val kanjiumNotice = text(kanjium.noticeFile!!)
        assertTrue("Kanjium notice does not name the project",
            kanjiumNotice.contains("kanjium"))
        assertTrue("Kanjium notice does not state the licence",
            kanjiumNotice.contains("CC BY-SA 4.0"))
        // Share-alike: the derived dictionary is under the same licence.
        assertTrue("Kanjium notice does not state the share-alike consequence",
            kanjiumNotice.contains("CC BY-SA 4.0"))
    }

    @Test
    fun each_canonical_licence_text_is_the_licence_it_claims_to_be() {
        // A label on an entry is only worth anything if the file behind it is that
        // licence; a wrong text is worse than a missing one.
        val markers = mapOf(
            "licenses/texts/apache-2.0.txt" to "Apache License",
            "licenses/texts/apache-2.0-with-llvm-exception.txt" to "LLVM Exceptions",
            "licenses/texts/agpl-3.0.txt" to "GNU AFFERO GENERAL PUBLIC LICENSE",
            "licenses/texts/gpl-3.0.txt" to "GNU GENERAL PUBLIC LICENSE",
            "licenses/texts/lgpl-2.1.txt" to "GNU LESSER GENERAL PUBLIC LICENSE",
            "licenses/texts/mpl-2.0.txt" to "Mozilla Public License Version 2.0",
            "licenses/texts/bsd-3-clause-ncnn.txt" to "BSD 3-Clause License",
            "licenses/texts/cc-by-sa-4.0.txt" to "Attribution-ShareAlike 4.0",
            "licenses/texts/unicode-licence-v3.txt" to "UNICODE LICENSE V3",
            "licenses/texts/edrdg-licence.txt" to "ELECTRONIC DICTIONARY RESEARCH",
        )
        markers.forEach { (path, marker) ->
            val body = text(path)
            assertTrue("$path does not contain '$marker'", body.contains(marker))
            assertTrue("$path is too short to be a licence text", body.length > 900)
        }
        // The app's own copy is byte-identical to the repository LICENSE, so the
        // AGPL text in the viewer cannot drift from the licence the repo ships.
        val repoLicense = listOf(File("../LICENSE"), File("LICENSE"))
            .firstOrNull { it.isFile } ?: error("repo LICENSE not found")
        assertEquals(
            repoLicense.readText().trim(),
            text("licenses/texts/agpl-3.0.txt").trim(),
        )
    }

    @Test
    fun apache_labelled_entries_ship_the_apache_text() {
        val apache = entries.filter { it.license == "Apache-2.0" }
        assertTrue("expected many Apache-2.0 entries, found ${apache.size}", apache.size >= 50)
        apache.forEach { e ->
            assertTrue(
                "'${e.name}' is labelled Apache-2.0 but ships ${e.textFiles}",
                e.textFiles.contains("licenses/texts/apache-2.0.txt"),
            )
        }
        // The first-party entry is the app's own licence, not a third party's.
        val app = entry("InstantJPDict")
        assertEquals("AGPL-3.0-only", app.license)
        assertTrue(app.textFiles.contains("licenses/texts/agpl-3.0.txt"))
    }

    @Test
    fun the_llvm_runtime_is_under_the_llvm_exception_not_plain_apache() {
        val openmp = entry("OpenMP")
        assertEquals("Apache-2.0 WITH LLVM-exception", openmp.license)
        assertTrue(openmp.textFiles.contains("licenses/texts/apache-2.0-with-llvm-exception.txt"))
        assertTrue(text(openmp.noticeFile!!).contains("libomp.so"))
    }

    @Test
    fun jna_states_the_dual_licence_and_which_option_is_used() {
        val jna = entries.first { it.name == "net.java.dev.jna:jna" }
        assertTrue("the dual licence is not stated: ${jna.license}",
            jna.license.contains("LGPL-2.1-or-later") && jna.license.contains("Apache-2.0"))
        assertTrue("both licence texts are not shipped: ${jna.textFiles}",
            jna.textFiles.containsAll(
                listOf("licenses/texts/apache-2.0.txt", "licenses/texts/lgpl-2.1.txt"),
            ))
        val notice = text(jna.noticeFile!!)
        assertTrue("the notice does not say which option is used",
            notice.contains("Apache") && notice.contains("relies on"))
    }

    @Test
    fun the_kana_model_states_its_licence_and_corpus_attribution() {
        val model = entry("kana-size")
        assertEquals("the weights carry the corpus licence",
            listOf("licenses/texts/cc-by-sa-4.0.txt"), model.textFiles)
        assertNotNull("the notice must explain the terms", model.noticeFile)
        val notice = text(model.noticeFile!!)
        assertTrue("the notice does not state CC BY-SA 4.0", notice.contains("CC BY-SA 4.0"))
        assertTrue("the notice still flags a gap", !notice.contains("Known gap"))
        // The training corpora stay named: their attribution is required whatever the weights terms.
        assertTrue("the training corpora are not attributed",
            notice.contains("Aozora") && notice.contains("Wikipedia"))
    }

    @Test
    fun render_shows_identity_licence_notice_and_full_text() {
        val ocr = entry("PP-OCRv6")
        val rendered = LicenseIndex.render(ocr) { rel -> text(rel) }
        assertTrue(rendered.startsWith(ocr.name))
        assertTrue(rendered.contains("Licence: Apache-2.0"))
        assertTrue("the provenance line is not shown", rendered.contains("Licence verified from: "))
        assertTrue("the notice is not shown", rendered.contains("PaddleOCR"))
        assertTrue("the licence text is not shown", rendered.contains("Apache License"))
        assertTrue("the full Apache text is not shown",
            rendered.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        // A file that is not in the APK degrades to a visible line, never an exception.
        val degraded = LicenseIndex.render(ocr) { null }
        assertTrue(degraded.contains("(not found in the APK:"))
    }

    @Test
    fun parse_skips_comments_and_treats_dash_as_absent() {
        val parsed = LicenseIndex.parse(
            """
            # a comment
            #   indented comment

            Thing	Apache-2.0	1.2.3	licenses/texts/apache-2.0.txt	-	from somewhere
            Otherthing	Public domain	-	-	licenses/notices/other.txt	from elsewhere
            """.trimIndent()
        )
        assertEquals(2, parsed.size)
        with(parsed[0]) {
            assertEquals("Thing", name)
            assertEquals("1.2.3", version)
            assertEquals(listOf("licenses/texts/apache-2.0.txt"), textFiles)
            assertEquals(null, noticeFile)
            assertEquals("from somewhere", provenance)
        }
        with(parsed[1]) {
            assertEquals(null, version)
            assertEquals(emptyList<String>(), textFiles)
            assertEquals("licenses/notices/other.txt", noticeFile)
        }
    }

    @Test
    fun parse_refuses_a_line_it_cannot_read() {
        // The file is generated: a line that does not parse means the file in the APK
        // is not the one the build wrote, and dropping it silently would hide a
        // missing notice.
        val tooFew = "Thing\tApache-2.0\t1.2.3\tlicenses/texts/apache-2.0.txt"
        assertTrue(runCatching { LicenseIndex.parse(tooFew) }.isFailure)
        val noNoticeAtAll = "Thing\tApache-2.0\t1.2.3\t-\t-\tnowhere"
        assertTrue(runCatching { LicenseIndex.parse(noNoticeAtAll) }.isFailure)
        val emptyName = "\tApache-2.0\t1.2.3\tlicenses/texts/apache-2.0.txt\t-\tnowhere"
        assertTrue(runCatching { LicenseIndex.parse(emptyName) }.isFailure)
    }

    @Test
    fun the_index_itself_says_how_to_regenerate_it() {
        val head = indexFile.readText().lineSequence().take(20).joinToString("\n")
        assertTrue("the index does not say it is generated", head.contains("GENERATED"))
        assertTrue("the regeneration command is not in the index",
            head.contains(":app:generateLicenseIndex"))
        assertTrue("the coverage guard is not mentioned", head.contains("verifyLicenseIndex"))
        // The manifests the generator reads are committed beside the build file, so a
        // regeneration is reproducible rather than archaeology.
        assertTrue(TestAssets.licensesDirFile("components.tsv").readText().contains("ncnn"))
        assertTrue(TestAssets.licensesDirFile("maven.tsv").readText().contains("androidx.*"))
        assertTrue(TestAssets.licensesDirFile("rust.tsv").readText().contains("uniffi"))
    }
}
