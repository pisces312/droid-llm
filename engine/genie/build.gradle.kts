plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// QAIRT/QNN SDK root. Located via local env — do not hardcode machine paths:
//   droid.qairtSdkRoot  or  QAIRT_PATH  or  QAIRT_SDK_ROOT
// Missing SDK auto-skips the native build (Kotlin still compiles).
val qairtSdkRoot: String = (findProperty("droid.qairtSdkRoot") as String?)
    ?: System.getenv("QAIRT_PATH")
    ?: System.getenv("QAIRT_SDK_ROOT")
    ?: ""

val qairtLibDir = File(qairtSdkRoot, "lib/aarch64-android")
val libGenie = File(qairtLibDir, "libGenie.so")
val genieHeaders = File(qairtSdkRoot, "include/Genie")

// Auto-skip native build when QAIRT is absent or droid.skipGenie=true.
// Kotlin still compiles so the engine can report MissingDependency in UI.
val skipGenieProp = (
    (findProperty("droid.skipGenie") as String?) ?: (findProperty("skipGenie") as String?)
    )?.toBoolean() == true
val qairtReady = qairtSdkRoot.isNotBlank() && libGenie.isFile && genieHeaders.isDirectory
val skipGenie = skipGenieProp || !qairtReady
if (skipGenieProp) {
    logger.lifecycle(":engine:genie native build SKIPPED (droid.skipGenie=true)")
} else if (!qairtReady) {
    logger.warn(
        ":engine:genie native build SKIPPED — QAIRT SDK not found " +
            "(need lib/aarch64-android/libGenie.so + include/Genie). " +
            "Set env QAIRT_PATH (or QAIRT_SDK_ROOT / droid.qairtSdkRoot).",
    )
}

// Provenance for the chat/benchmark engine label. The QAIRT release has no git
// metadata, so the version comes from the SDK folder name documented in
// AGENTS.md / docs/ENGINE_INTEGRATION.md (e.g. `2.50.0.260828`).
val qairtVersion = Regex("\\d+\\.\\d+[\\w.]*")
    .find(qairtSdkRoot.trim().substringAfterLast('/').substringAfterLast('\\'))
    ?.value
    .orEmpty()

android {
    namespace = "io.github.pisces312.droidllm.engine.genie"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "ENGINE_VERSION", "\"$qairtVersion\"")
        buildConfigField("String", "ENGINE_COMMIT", "\"\"")

        if (!skipGenie) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DQNN_SDK_ROOT_PATH=${qairtSdkRoot.replace("\\", "/")}",
                    )
                    cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
                }
            }
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    if (!skipGenie) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    // Keep the native shim optimised in debug builds too: AGP would otherwise
    // pass CMAKE_BUILD_TYPE=Debug (-O0) and skew cross-engine comparison.
    if (!skipGenie) {
        buildTypes {
            debug {
                externalNativeBuild {
                    cmake {
                        arguments += listOf(
                            "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                            "-DCMAKE_CXX_FLAGS_DEBUG=-g -O2",
                            "-DCMAKE_C_FLAGS_DEBUG=-g -O2",
                        )
                    }
                }
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libc++_shared.so")
            if (skipGenie) {
                // Do not ship QAIRT/QNN or our JNI so in skip builds.
                excludes += listOf("**/*.so")
            }
        }
    }
}

// Package prebuilt QAIRT/QNN runtime libs into jniLibs (flat arm64-v8a).
// Follows chatapp_android CopyQnnLibs. Skipped with the native build.
val copyQnnJniLibs = tasks.register<Copy>("copyQnnJniLibs") {
    onlyIf { !skipGenie }
    from(qairtLibDir) {
        include(
            "libGenie.so",
            "libQnnHtp.so",
            "libQnnHtpPrepare.so",
            "libQnnSystem.so",
            "libQnnSaver.so",
            // Required: without it Genie logs "Failure in initializing backend extensions",
            // then null-derefs inside GenieDialog_create. Verified on BKQ-AN80 / SM8850 /
            // Android 17 with QAIRT 2.50.0.260828 (genie-t2t-run: SIGSEGV without, EXIT=0 with).
            "libQnnHtpNetRunExtensions.so",
            "libQnnHtpV*Stub.so",
        )
        exclude("libQnnHtpV*CalculatorStub.so")
    }
    // Flatten hexagon Skel libs into arm64-v8a (QNN expects flat jniLibs).
    from(
        fileTree("$qairtSdkRoot/lib") {
            include("hexagon-v*/unsigned/libQnnHtpV*Skel.so")
        }.files.map { it.absolutePath },
    )
    into(layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a"))
}

// Drop stale nested hexagon dirs from earlier flat-copy attempts.
tasks.register<Delete>("cleanQnnJniLibsLayout") {
    delete(
        fileTree("src/main/jniLibs/arm64-v8a") {
            include("hexagon-v*/**")
        },
    )
}
tasks.named("copyQnnJniLibs") { finalizedBy("cleanQnnJniLibsLayout") }

if (!skipGenie) {
    tasks.named("preBuild") {
        dependsOn(copyQnnJniLibs)
    }
}

dependencies {
    api(project(":core:engine-api"))
    implementation(project(":core:common"))
    implementation(project(":core:chattemplate"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
