plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.holopengin.instantjpdict"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.holopengin.instantjpdict"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Pinned: AGP resolved 28.2.13676358 for the JNI build (#29 Track 1).
        // Keep in sync with nav_graph_core/build_nav_graph.sh fallback.
        ndkVersion = "28.2.13676358"
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "*/arm64-v8a/*.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.gson)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// Guard: the vendored libncnn.a must contain the fused-GELU code required by
// rec_dyn.param's 13x `9=7` conv layers (#41). A lib without it links fine
// but silently mis-infers rec (dense garbage, blank collapse — caught
// 2026-09 after a fresh-tree rebuild silently dropped the patch). Rebuild it with
// tools/build_ncnn.sh (marker + artifact gates enforced there too).
tasks.register<Exec>("verifyNcnnBlob") {
    workingDir = file("src/main/cpp/ncnn/lib/arm64-v8a")
    commandLine(
        "bash", "-c",
        "grep -q activation_ss libncnn.a || " +
            "(echo 'ERROR: libncnn.a lacks fused GELU (activation_ss); rebuild with tools/build_ncnn.sh' >&2; exit 1)"
    )
}

// Fail fast on a bad blob before any native build
tasks.named("preBuild") {
    dependsOn("verifyNcnnBlob")
}

// Build nav_graph_core Rust library for Android (only if NDK available)
tasks.register<Exec>("buildNavGraphCore") {
    workingDir = file("${project.rootDir}/nav_graph_core")
    commandLine("bash", "./build_nav_graph.sh")
    // Skip if NDK not installed or if running on CI without NDK
    isIgnoreExitValue = true
}

// Ensure Rust library is built before merging JNI libs
tasks.whenTaskAdded {
    if (name.contains("mergeDebugJniLibFolders") || name.contains("mergeReleaseJniLibFolders") || name.contains("mergeBenchmarkJniLibFolders")) {
        dependsOn("buildNavGraphCore")
    }
}

// ————— #70: bundled licence / attribution index —————
//
// See docs/licenses.md. Two tasks:
//
//   generateLicenseIndex — rewrites src/main/assets/licenses/INDEX.txt from the
//     hand-maintained components (licenses/components.tsv), the resolved runtime
//     classpath (every module mapped through licenses/maven.tsv) and the Rust
//     crate closure compiled into libnav_graph_core.so (licenses/rust.tsv). It
//     also refreshes the AGPL-3.0 copy in the assets so what the APK ships
//     cannot drift from the repository's LICENSE.
//
//   verifyLicenseIndex — same computation, no writing; wired into preBuild. A
//     dependency added (or removed) without regenerating the index fails the
//     build here instead of shipping with no notice.
//
// No licence text is assembled by this script. Every entry points at a file
// under src/main/assets/licenses/, and both the generator and the verifier
// refuse an index that references a file which is not there.
//
// The index is computed while the build is configured rather than inside the
// task actions: a Gradle script function cannot be referenced from a task action
// without capturing the script object, which the configuration cache rejects.
// The actions therefore close over plain Strings and Files only.
val licenseDir = layout.projectDirectory.dir("licenses")
val licenseComponentsTsv = licenseDir.file("components.tsv").asFile
val licenseMavenTsv = licenseDir.file("maven.tsv").asFile
val licenseRustTsv = licenseDir.file("rust.tsv").asFile
val licenseAssetsRoot = layout.projectDirectory.dir("src/main/assets").asFile
val licenseIndexFile = File(licenseAssetsRoot, "licenses/INDEX.txt")
val licenseAgplRel = "licenses/texts/agpl-3.0.txt"
val licenseAgplCopy = File(licenseAssetsRoot, licenseAgplRel)
val licenseRepoLicense = File(rootDir, "LICENSE")

/** Rows of a TAB-separated manifest: comments (`#`) and blanks dropped. */
fun licenseTsvRows(file: File): List<List<String>> =
    if (!file.isFile) emptyList() else file.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }

/** Fails the build if a referenced licence/notice file is missing or empty.
 *
 *  [regenerated] holds asset paths this build writes itself (the AGPL-3.0 copy);
 *  they are checked by content instead, so the first run can create them. */
fun licenseRequireFiles(rows: List<List<String>>, where: String, regenerated: Set<String> = emptySet()) {
    if (rows.isEmpty()) throw GradleException("$where: no entries")
    rows.forEachIndexed { i, cols ->
        if (cols.size < 6) {
            throw GradleException(
                "$where line ${i + 1}: expected 6 TAB-separated columns, got ${cols.size}: " +
                    cols.joinToString(" | ")
            )
        }
        val name = cols[0].trim()
        if (name.isEmpty() || cols[1].trim().isEmpty()) {
            throw GradleException("$where line ${i + 1}: empty name or licence label")
        }
        fun check(rel: String, what: String) {
            if (rel in regenerated) return
            val f = File(licenseAssetsRoot, rel)
            if (!f.isFile) throw GradleException("$where line ${i + 1}: '$name' references missing $what '$rel'")
            if (f.length() == 0L) throw GradleException("$where line ${i + 1}: '$name' references empty $what '$rel'")
        }
        val texts = cols[3].split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "-" }
        if (texts.isEmpty() && cols[4].trim() == "-") {
            // A bundled asset with no licence of its own (public-domain data) is
            // allowed, but it must still be noticed: the notice is where that is said.
            throw GradleException("$where line ${i + 1}: '$name' has neither a licence text nor a notice")
        }
        texts.forEach { check(it, "licence text") }
        if (cols[4].trim() != "-") check(cols[4].trim(), "notice")
    }
}

/** Dependency rows, or a build failure naming every module with no notice. */
fun licenseDependencyRows(modules: List<String>, maven: List<List<String>>): List<List<String>> {
    val rows = maven.filter { it.size >= 5 }
    val exact = rows.filterNot { it[0].trim().endsWith("*") }.associateBy { it[0].trim() }
    // A row whose module id ends in '*' is a prefix rule: it covers every resolved
    // module whose "group:artifact" starts with that prefix (longest wins), e.g.
    // `androidx.*` for the whole AndroidX family.
    val prefixes = rows.filter { it[0].trim().endsWith("*") }
        .map { it[0].trim().removeSuffix("*") to it }
        .sortedByDescending { it.first.length }

    fun rowFor(module: String): List<String>? {
        val ga = module.substringBeforeLast(':')
        return exact[ga] ?: prefixes.firstOrNull { ga.startsWith(it.first) }?.second
    }
    val missing = modules.filter { rowFor(it) == null }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "No licence notice for ${missing.size} dependency/dependencies on the runtime classpath:\n" +
                missing.joinToString("\n") { "  $it" } +
                "\n\nAdd each one to app/licenses/maven.tsv (group:artifact or group:*, licence label,\n" +
                "licence text file(s) under src/main/assets/licenses/, notice file or '-', and where the\n" +
                "licence was verified from), then run ./gradlew :app:generateLicenseIndex.\n" +
                "See docs/licenses.md."
        )
    }
    return modules.map { module ->
        val cols = rowFor(module)!!
        listOf(
            module.substringBeforeLast(':'),
            cols[1].trim(),
            module.substringAfterLast(':'),
            cols[2].trim(),
            cols[3].trim(),
            cols[4].trim(),
        )
    }
}

/** The whole index, or a build failure. Shared by both tasks. */
fun licenseBuildIndex(): String {
    val components = licenseTsvRows(licenseComponentsTsv)
    val rust = licenseTsvRows(licenseRustTsv)
    val maven = licenseTsvRows(licenseMavenTsv)
    licenseRequireFiles(components, "app/licenses/components.tsv", setOf(licenseAgplRel))
    licenseRequireFiles(rust, "app/licenses/rust.tsv")
    val modules = licenseRuntimeModules.get()
    val deps = licenseDependencyRows(modules, maven)
    licenseRequireFiles(deps, "app/licenses/maven.tsv")

    val sb = StringBuilder()
    sb.append(
        """
        # InstantJPDict bundled-licence index — GENERATED, do not edit by hand.
        #
        # Everything the APK bundles that carries a licence: the app itself, its
        # Gradle runtime dependencies, the vendored native libraries, the OCR and
        # kana models, and the dictionary data derived from upstream corpora.
        #
        # Regenerate with:  ./gradlew :app:generateLicenseIndex
        # Coverage is enforced: :app:verifyLicenseIndex (a preBuild dependency)
        # fails when a dependency is added without an entry in app/licenses/maven.tsv.
        # Rust crates: tools/gen_rust_license_bundle.py.  Docs: docs/licenses.md.
        #
        # TAB-separated columns:
        #   name | licence label | version | licence text file(s) | notice file | provenance
        # Licence text and notice paths are relative to app/src/main/assets/.
        # '#' at the start of a line is a comment; blank lines are ignored.
        #
        """.trimIndent()
    )
    fun section(title: String, rows: List<List<String>>) {
        sb.append("\n# ---- ").append(title).append(' ')
            .append("-".repeat(maxOf(3, 74 - title.length))).append('\n')
        rows.forEach { sb.append(it.joinToString("\t")).append('\n') }
    }
    section("the app and its bundled components (app/licenses/components.tsv)", components)
    section("Gradle runtime dependencies — ${modules.size} modules (resolved classpath)", deps)
    section("Rust crates compiled into lib/arm64-v8a/libnav_graph_core.so", rust)
    return sb.toString()
}

/** The app's resolved runtime classpath, as sorted "group:artifact:version".
 *
 *  AGP creates `debugRuntimeClasspath` in its own afterEvaluate, so this is filled
 *  in from this script's afterEvaluate (which runs after the plugin's). An
 *  unresolvable value would be worse than no guard: a dependency with no notice
 *  must fail the build, not quietly produce an empty list. */
val licenseRuntimeModules = objects.listProperty(String::class.java)

afterEvaluate {
    licenseRuntimeModules.set(
        configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
            .sorted()
    )

    // Computed here, so the task actions below capture only values.
    val wanted = licenseBuildIndex()
    val wantedModules = licenseRuntimeModules.get()
    val wantedAgpl = licenseRepoLicense.readText().trimEnd() + "\n"
    val index = licenseIndexFile
    val agpl = licenseAgplCopy
    val componentsTsv = licenseComponentsTsv
    val mavenTsv = licenseMavenTsv
    val rustTsv = licenseRustTsv
    val repoLicense = licenseRepoLicense

    tasks.register("generateLicenseIndex") {
        group = "licence"
        description = "Regenerate app/src/main/assets/licenses/INDEX.txt (#70)"
        inputs.files(componentsTsv, mavenTsv, rustTsv, repoLicense)
        inputs.property("runtimeModules", wantedModules)
        outputs.files(index, agpl)
        doLast {
            index.writeText(wanted)
            agpl.writeText(wantedAgpl)
            logger.lifecycle(
                "generateLicenseIndex: ${wantedModules.size} dependencies, " +
                    "${index.length()} bytes -> $index"
            )
        }
    }

    tasks.register("verifyLicenseIndex") {
        group = "licence"
        description = "Fail if the committed licence index no longer matches the build (#70)"
        inputs.files(componentsTsv, mavenTsv, rustTsv, repoLicense)
        inputs.property("runtimeModules", wantedModules)
        doLast {
            val have = if (index.isFile) index.readText() else ""
            if (wanted != have) {
                throw GradleException(
                    "The bundled licence index is stale: app/src/main/assets/licenses/INDEX.txt does\n" +
                        "not match this build's resolved dependencies and manifests. Run\n" +
                        "  ./gradlew :app:generateLicenseIndex\n" +
                        "and commit the result. (A dependency was added or removed, or an entry in\n" +
                        "app/licenses/{components,maven,rust}.tsv changed.) See docs/licenses.md."
                )
            }
            if (!agpl.isFile || agpl.readText().trim() != repoLicense.readText().trim()) {
                throw GradleException(
                    "app/src/main/assets/licenses/texts/agpl-3.0.txt no longer matches the repository\n" +
                        "LICENSE. Run ./gradlew :app:generateLicenseIndex and commit the result."
                )
            }
        }
    }

    tasks.named("preBuild") {
        dependsOn("verifyLicenseIndex")
    }
}
