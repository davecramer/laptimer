plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

android {
    namespace = "laptimer.raceboxemulator"
    compileSdk = 37

    defaultConfig {
        applicationId = "laptimer.raceboxemulator"
        // Android 12 for the BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT permissions,
        // the same minimum as the lap timer.
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
}
