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
