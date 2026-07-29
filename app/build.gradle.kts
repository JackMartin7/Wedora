import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// Release signing credentials. keystore.properties (repo root, gitignored —
// see .gitignore) holds the store/key passwords in plain text and is never
// committed; absent entirely on a machine that only builds debug, or before
// part 2's keytool walkthrough has been run. Loaded here rather than inline
// in signingConfigs so a missing file fails with a clear "release build
// needs keystore.properties" story instead of a cryptic NPE deep in AGP.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.wedora.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wedora.app"
        minSdk = 24
        // Play Console requires targeting API 35 (Android 15) as of this
        // bump. See this file's own note by the AGP version in the root
        // build.gradle.kts for why that plugin needed bumping alongside
        // this. Apps targeting 35 get edge-to-edge display forced on by
        // default — WedoraBaseActivity's enableEdgeToEdge()/inset handling
        // is what actually covers that now, wired app-wide across every
        // screen rather than left as a bare manifest bump.
        targetSdk = 35
        versionCode = 9
        versionName = "1.1.2"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only actually usable once keystore.properties exists — see
            // above. Referencing it unconditionally (rather than guarding
            // this line too) is deliberate: a release build attempted
            // before the keystore is set up should fail loudly at sign
            // time, not silently produce an unsigned/debug-signed .aab.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    viewBinding {
        enable = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // App-level foreground/background detection, for presence (lastSeen)
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    // Profile photo hosting — see PhotoUploadService.
    implementation("com.google.firebase:firebase-storage-ktx")
    // Push notifications — see WedoraFirebaseMessagingService.
    implementation("com.google.firebase:firebase-messaging-ktx")
    // The disableUserAccount admin callable — see AdminReportDetailActivity.
    implementation("com.google.firebase:firebase-functions-ktx")
    // Google Sign-In — see GoogleAuthHelper.
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Image loading (signed-in user avatars from Firebase photoUrl)
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Fused location provider, for city-level location detection on Complete Profile
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Native ads in the Home swipe stack, Explore grid, and Likes list
    // (free users only) — see NativeAdLoader.kt for the three placements'
    // ad unit IDs.
    implementation("com.google.android.gms:play-services-ads:23.3.0")

    // Play Billing for Premium subscriptions — see BillingManager.
    implementation("com.android.billingclient:billing-ktx:7.1.1")
}
