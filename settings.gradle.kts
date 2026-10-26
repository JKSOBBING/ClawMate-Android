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

rootProject.name = "ClawMate"
include(":app")
include(":shared-app", ":android-capabilities")
val meetingSdkRoot = providers.gradleProperty("meetingSdkRoot")
    .orElse(providers.environmentVariable("MEETING_SDK_ROOT")).orNull
    ?: error("Set -PmeetingSdkRoot=<on-device-meeting-notes checkout> or MEETING_SDK_ROOT; see docs/MEETING_INTEGRATION.md")
include(":meeting-sdk")
project(":meeting-sdk").projectDir = file(meetingSdkRoot).resolve("android/meeting-sdk")
