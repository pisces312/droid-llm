plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---- QNN HTP arch trimming (opt-in) -------------------------------------------------
// QAIRT ships one HTP Skel/Stub pair per DSP arch; only the pair matching the target SoC
// is usable at runtime. Trim the rest to shrink the APK by ~70 MB per dropped arch:
//   -Pdroid.qnnHtpVersions=81        keep only libQnnHtpV81{Skel,Stub}.so
//   -Pdroid.qnnHtpVersions=79,81     keep both
//   unset / empty                    keep every version the SDK provides (default)
// Values accept an optional "v" prefix. See GenieConfigResolver.SOC_TO_HTP for the
// SoC -> dsp_arch mapping that decides which version a device actually needs.
// The candidate range is a deliberate superset: AGP ignores exclude patterns that match
// nothing, so future SDK arch versions are covered without editing this file.
val qnnHtpCandidateVersions = 60..89
val qnnHtpKeepVersions: Set<Int> =
    ((findProperty("droid.qnnHtpVersions") as String?) ?: (findProperty("qnnHtpVersions") as String?))
        ?.split(',', ' ', ';')
        ?.mapNotNull { it.trim().removePrefix("v").removePrefix("V").toIntOrNull() }
        ?.filter { it in qnnHtpCandidateVersions }
        ?.toSet()
        ?: emptySet()

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
            if (qnnHtpKeepVersions.isNotEmpty()) {
                excludes += (qnnHtpCandidateVersions.toSet() - qnnHtpKeepVersions)
                    .flatMap { v -> listOf("**/libQnnHtpV${v}Skel.so", "**/libQnnHtpV${v}Stub.so") }
                logger.lifecycle(
                    "QNN HTP: keeping v${qnnHtpKeepVersions.sorted().joinToString(", v")}; " +
                        "trimming all other HTP Skel/Stub.",
                )
            }
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
