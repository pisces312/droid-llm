plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Path to MNN tree with prebuilt libMNN.so (MNN_BUILD_LLM=ON).
// Located via local env — do not hardcode machine paths in the repo:
//   MNN_ROOT  or gradle property droid.mnnRoot
val mnnRoot: String = (findProperty("droid.mnnRoot") as String?)
    ?: System.getenv("MNN_ROOT")
    ?: error(
        "MNN_ROOT is not set. Point it at a local MNN checkout with " +
            "project/android/build_64/lib/libMNN.so (MNN_BUILD_LLM=ON), " +
            "or set gradle property droid.mnnRoot.",
    )

android {
    namespace = "io.github.pisces312.droidllm.engine.mnn"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DMNN_ROOT=${mnnRoot.replace("\\", "/")}",
                )
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
            // libMNN.so is an imported dependency; avoid duplicate if also in jniLibs
            pickFirsts += listOf("**/libMNN.so", "**/libc++_shared.so")
        }
    }
}

// Package prebuilt libMNN.so into the AAR/APK jniLibs.
val mnnLibCandidates = listOf(
    "$mnnRoot/project/android/build_64/lib/libMNN.so",
    "$mnnRoot/project/android/build_64/libMNN.so",
)
val mnnLibFile = mnnLibCandidates.firstOrNull { File(it).isFile }
    ?: error("libMNN.so not found. Build MNN with MNN_BUILD_LLM=ON or set droid.mnnRoot. Tried: $mnnLibCandidates")

val copyMnnJniLibs = tasks.register<Copy>("copyMnnJniLibs") {
    from(mnnLibFile)
    into(layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a"))
}

tasks.named("preBuild") {
    dependsOn(copyMnnJniLibs)
}

dependencies {
    api(project(":core:engine-api"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
