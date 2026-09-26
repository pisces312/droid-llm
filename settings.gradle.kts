pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "droid-llm"

include(":app")
include(":core:engine-api")
include(":core:common")
include(":core:benchmark")
include(":core:chattemplate")
include(":engine:litert")
include(":engine:mnn")
include(":engine:genie")
include(":engine:llamacpp")
