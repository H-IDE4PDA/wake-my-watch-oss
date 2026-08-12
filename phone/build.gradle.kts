plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.h_ide4pda.wakemywatch.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.h_ide4pda.wakemywatch"
        minSdk = 28
        targetSdk = 36
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
            // R8/shrinking off on both modules — not worth the risk (see watch module's history
            // with the off-body sensor); signing and R8 are independent, so release stays signed.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
