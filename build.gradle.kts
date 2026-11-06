plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Firebase Phase 1 (Crashlytics + Analytics). Versions are pinned to
    // the project's toolchain (Gradle 8.9 wrapper, AGP 8.7.3, Kotlin 2.0.21):
    // google-services 4.4.2 was built with Gradle 7.6 and is the proven
    // pairing for AGP 8.7; 4.5.0 was built with Gradle 9.0.0 and is NOT
    // used while the wrapper is Gradle 8.9. Crashlytics Gradle plugin
    // 3.0.3 is the 3.0.x line (requires AGP 8.1+, project has 8.7.3).
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.3" apply false
}
