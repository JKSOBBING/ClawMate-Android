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
include(":shared-app", ":android-capabilities")
include(":mobiinfer")
include(":mika-core")
project(":mika-core").projectDir = file("third_party/mika/core")
include(":gui-control-fixture") // Disposable SDK-only cross-app test actor, not a product host.
val meetingSdkRoot = providers.gradleProperty("meetingSdkRoot")
    .orElse(providers.environmentVariable("MEETING_SDK_ROOT")).orElse("meeting")
include(":meeting-sdk")
project(":meeting-sdk").projectDir = file(meetingSdkRoot.get()).resolve("android/meeting-sdk")
