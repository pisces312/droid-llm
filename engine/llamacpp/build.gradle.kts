plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Provenance for the chat/benchmark engine label. llama.cpp is vendored into
// this repo, so its identity is the repo commit that last touched the tree —
// the only honest "commit" available without a submodule checkout.
val vendoredCommit = runCatching {
    val proc = ProcessBuilder("git", "log", "-1", "--format=%h", "--", "third_party/llama.cpp")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0) out else ""
}.getOrDefault("")

android {
    namespace = "io.github.pisces312.droidllm.engine.llamacpp"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "ENGINE_VERSION", "\"vendored\"")
        buildConfigField("String", "ENGINE_COMMIT", "\"$vendoredCommit\"")

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        }
    }
}

dependencies {
    api(project(":core:engine-api"))
    implementation(project(":core:common"))
    implementation(project(":core:chattemplate"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
