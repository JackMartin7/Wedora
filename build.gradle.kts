// Top-level build file
plugins {
    // AGP 9.0+ ships Kotlin support built in and drops the standalone
    // org.jetbrains.kotlin.android plugin entirely — see app/build.gradle.kts,
    // which no longer declares it. AGP 9.0.1's minimum Gradle is 9.1.0, which
    // gradle-wrapper.properties is bumped to match. Picked 9.0.1 over the
    // newer 9.3.0 line deliberately: 9.3.0 requires Gradle 9.5.0, a much
    // bigger wrapper jump for no benefit here — Play's warning only asks for
    // "9.0+".
    id("com.android.application") version "9.0.1" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    // Crash reporting. The plugin is what uploads the R8 mapping file on a
    // release build (app/build.gradle.kts has isMinifyEnabled = true), without
    // which every production stack trace comes through obfuscated — the SDK
    // dependency alone does not do that.
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
}
