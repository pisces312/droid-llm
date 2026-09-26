plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val skipGenie = (findProperty("droid.skipGenie") as String?)?.toBoolean() == true

android {
    namespace = "io.github.pisces312.droidllm.engine.genie"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")

        if (!skipGenie) {
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
    }

    // P3 wires CMakeLists + QAIRT libs. Skipped via droid.skipGenie=true.
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
