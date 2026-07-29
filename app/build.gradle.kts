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
    implementation(libs.litert)
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

// Build nav_graph_core Rust library for Android (only if NDK available)
tasks.register<Exec>("buildNavGraphCore") {
    workingDir = file("${project.rootDir}/nav_graph_core")
    commandLine("bash", "./build_nav_graph.sh")
    // Skip if NDK not installed or if running on CI without NDK
    isIgnoreExitValue = true
}

// Ensure Rust library is built before merging JNI libs
tasks.whenTaskAdded {
    if (name.contains("mergeDebugJniLibFolders") || name.contains("mergeReleaseJniLibFolders")) {
        dependsOn("buildNavGraphCore")
    }
}
