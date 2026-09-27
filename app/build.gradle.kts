plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---- QNN HTP arch trimming ----------------------------------------------------------
// QAIRT ships one HTP Skel/Stub pair per DSP arch and only the pair matching the target
// SoC is loadable at runtime (QAIRT SDK support table: SM8850 -> soc_id 87 -> V81,
// SM8750 -> 69 -> V79, SM8650 -> 57 -> V75, SM8550 -> 43 -> V73). Trimming the other
// arches drops ~23 MB from the APK (compressed; ~63 MB of raw .so).
//
// Default policy (when no -P flag is given):
//   * non-release variants -> keep v81 only (dev device is SM8850)
//   * release variant      -> keep every arch the SDK provides (the GitHub Release build)
// Override for every variant:
//   -Pdroid.qnnHtpVersions=79,81   keep the listed arches ("v" prefix optional)
//   -Pdroid.qnnHtpVersions=all     keep every arch, same as the release default
// See GenieConfigResolver.SOC_TO_HTP for the SoC -> htp_config asset (which carries the
// dsp_arch) mapping that decides which arch a device actually loads.
// The candidate range is a deliberate superset: AGP ignores exclude patterns that match
// nothing, so future SDK arch versions are covered without editing this file.
val qnnHtpCandidateVersions = 60..89
val qnnHtpDefaultVersion = 81
val qnnHtpExplicitKeep: Set<Int>? =
    ((findProperty("droid.qnnHtpVersions") as String?) ?: (findProperty("qnnHtpVersions") as String?))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
            if (raw.equals("all", ignoreCase = true)) {
                qnnHtpCandidateVersions.toSet()
            } else {
                raw.split(',', ' ', ';')
                    .mapNotNull { it.trim().removePrefix("v").removePrefix("V").toIntOrNull() }
                    .filter { it in qnnHtpCandidateVersions }
                    .toSet()
            }
        }

android {
    namespace = "io.github.pisces312.droidllm"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pisces312.droidllm"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-P0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Release signs with the user's env-var keystore (KEY_STORE / KEY_ALIAS / ...).
    // Debug keeps the default debug key and a ".debug" applicationId so both can stay installed.
    signingConfigs {
        create("release") {
            val storePath = System.getenv("KEY_STORE") ?: System.getenv("KEY_STORE_LOCATION")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("KEY_STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "droid-llm debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            resValue("string", "app_name", "droid-llm")
            val storePath = System.getenv("KEY_STORE") ?: System.getenv("KEY_STORE_LOCATION")
            if (!storePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Per-variant HTP arch trimming happens in androidComponents below.
        }
    }
    // noCompress for model extensions is reserved (models stay external).
    androidResources {
        noCompress += listOf("bin", "json", "mnn", "gguf", "litertlm", "task")
    }
}

dependencies {
    implementation(project(":core:engine-api"))
    implementation(project(":core:common"))
    implementation(project(":core:benchmark"))
    implementation(project(":core:chattemplate"))
    implementation(project(":engine:litert"))
    implementation(project(":engine:mnn"))
    implementation(project(":engine:genie"))
    implementation(project(":engine:llamacpp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Per-variant QNN HTP arch trimming: packaging must be narrowed after variants exist,
// so the DSL-level `packaging {}` block above only sets useLegacyPackaging.
androidComponents {
    onVariants { variant ->
        val keep = qnnHtpExplicitKeep
            ?: if (variant.buildType == "release") {
                qnnHtpCandidateVersions.toSet()
            } else {
                setOf(qnnHtpDefaultVersion)
            }
        val drop = qnnHtpCandidateVersions.toSet() - keep
        if (drop.isNotEmpty()) {
            variant.packaging.jniLibs.excludes.addAll(
                drop.flatMap { v -> listOf("**/libQnnHtpV${v}Skel.so", "**/libQnnHtpV${v}Stub.so") },
            )
            logger.lifecycle(
                "QNN HTP [${variant.name}]: keeping v${keep.sorted().joinToString(", v")}; " +
                    "trimming the rest.",
            )
        } else {
            logger.lifecycle("QNN HTP [${variant.name}]: keeping every arch the SDK provides.")
        }
    }
}
