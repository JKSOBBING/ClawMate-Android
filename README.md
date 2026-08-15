# ClawMate Android — Phase 1 (skeleton, copy-ready tree)

This directory contains the **phase 1** deliverables for the ClawMate
HarmonyOS→Android migration. It is meant to be copied into the migration
target repository `/root/ClawMate-Android` (see `MIGRATION_STATUS.md`, section
9 note about the write-access blocker when this was generated).

## Layout

```
.
├── MIGRATION_STATUS.md            # Feature mapping + Android strategy (phase tracking)
├── .gitignore                     # Android/Gradle/secret exclusions
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml      # Version catalog (AGP 8.5.2, Kotlin 1.9.24, Compose BOM)
└── app/
    ├── build.gradle.kts           # applicationId com.clawmate.app
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/clawmate/app/
        │   │   ├── ClawMateApplication.kt
        │   │   ├── MainActivity.kt                  # Compose single-activity host
        │   │   ├── ui/ClawMateRoot.kt               # 5-tab bottom navigation shell
        │   │   ├── ui/home/HomeScreen.kt
        │   │   ├── ui/settings/SettingsScreen.kt
        │   │   ├── ui/FeatureScreens.kt             # Summary/Chat/Task placeholders
        │   │   ├── ui/theme/Theme.kt, Color.kt
        │   │   ├── profile/ProfileShareActivity.kt  # .mobi-profile import stub
        │   │   └── config/EndpointConfig.kt         # secrets-free endpoint config
        │   └── res/ (themes, colors, strings, network/backup XML, launcher, raw)
        ├── test/java/com/clawmate/app/              # JUnit unit tests
        └── androidTest/java/com/clawmate/app/       # Compose instrumented smoke test
```

## Build

Prereqs: JDK 17+, Android SDK (compileSdk 34), and a Gradle wrapper. Generate
the wrapper on a machine with Gradle, then:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest        # unit tests
./gradlew :app:connectedAndroidTest     # instrumented (device/emulator)
```

On the machine that generated the phase-1 tree, JDK / Android SDK / Gradle were
not installed, so no build was executed — see `MIGRATION_STATUS.md` §0 and §6.

## No secrets

Endpoints/keys are not in this tree. Copy
`app/src/main/res/raw/config.example.properties` to
`app/src/main/res/raw/local_config.properties` (git-ignored) and fill in real
values.
