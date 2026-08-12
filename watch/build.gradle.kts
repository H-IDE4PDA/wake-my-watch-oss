plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.h_ide4pda.wakemywatch.watch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.h_ide4pda.wakemywatch"
        minSdk = 33
        // Deliberately below 34 (Android 14): targeting 34+ makes the platform route
        // NotificationManager.setInterruptionFilter() into a per-app implicit AutomaticZenRule
        // instead of the legacy global manualRule, so the effective interruption filter is
        // still applied correctly but the system's manual DND indicator never lights up on the
        // watch face/quick tile — confirmed by comparing against a reference app on targetSdk 31.
        targetSdk = 33
        versionCode = rootProject.extra["wmwVersionCode"] as Int
        versionName = rootProject.extra["wmwVersionName"] as String
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    val wmwKeystorePropsExist = rootProject.extra["wmwKeystorePropsExist"] as Boolean
    signingConfigs {
        create("release") {
            if (wmwKeystorePropsExist) {
                storeFile = file(rootProject.extra["wmwKeystoreStoreFile"] as String)
                storePassword = rootProject.extra["wmwKeystoreStorePassword"] as String
                keyAlias = rootProject.extra["wmwKeystoreKeyAlias"] as String
                keyPassword = rootProject.extra["wmwKeystoreKeyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            if (wmwKeystorePropsExist) signingConfig = signingConfigs.getByName("release")
            // R8/shrinking stays off: the watch module is almost entirely system-driven
            // (sensors, WearableListenerService, screen wake) and R8 previously broke the
            // off-body sensor and phone<->watch connectivity in testing. Signing and R8 are
            // independent, so release stays signed.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    lint {
        // Compose's registerForActivityResult trips this on Wear OS's older Fragment transitively
        // pulled in by Wear Compose; not a real bug for our usage. Debug builds are unaffected.
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.wear.compose:compose-material:1.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
