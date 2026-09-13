package com.holopengin.instantjpdict

import java.io.File

/**
 * Locate committed assets from a JVM unit test. Gradle's default working directory
 * for `:app:testDebugUnitTest` is the module directory, but a runner invoked from the
 * repo root is plausible enough that both are tried — and a miss fails the test
 * loudly rather than skipping it, because a consistency test that silently vanishes
 * is worse than no test.
 */
object TestAssets {
    fun variantsFile(): File = assetsFile("variants/kanji_variants.txt")

    /** A file under `app/src/main/assets/`, by its asset-relative path. */
    fun assetsFile(rel: String): File {
        val candidates = listOf(
            File("src/main/assets/$rel"),
            File("app/src/main/assets/$rel"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("$rel not found; tried ${candidates.joinToString { it.path }} " +
                "(working dir ${File(".").absolutePath})")
    }

    /** A file under `app/licenses/` (generation inputs), by its relative path. */
    fun licensesDirFile(rel: String): File {
        val candidates = listOf(
            File("licenses/$rel"),
            File("app/licenses/$rel"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("licenses/$rel not found; tried ${candidates.joinToString { it.path }} " +
                "(working dir ${File(".").absolutePath})")
    }
}
