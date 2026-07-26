// Top-level build file
plugins {
    // 8.4.0 predates API 35 (Android 15) support entirely — compileSdk 35
    // fails to configure under it. 8.6.1 is the AGP version bumped to here;
    // its minimum required Gradle version is 8.7, which gradle-wrapper.properties
    // already pins, so no wrapper bump was needed alongside this.
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
