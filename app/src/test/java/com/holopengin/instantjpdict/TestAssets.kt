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
    fun variantsFile(): File {
        val candidates = listOf(
            File("src/main/assets/variants/kanji_variants.txt"),
            File("app/src/main/assets/variants/kanji_variants.txt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("kanji_variants.txt not found; tried ${candidates.joinToString { it.path }} " +
                "(working dir ${File(".").absolutePath})")
    }
}
