plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

android {
    namespace = "laptimer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "laptimer.app"
        // Android 12 is the first version with the BLUETOOTH_SCAN and
        // BLUETOOTH_CONNECT permissions. Starting there avoids the older
        // rule that BLE scanning needs the location permission.
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        // MapLibre includes native code for four CPU types, about 20 MB
        // each. Phones from recent years are all arm64. Add "x86_64" here
        // to run on the emulator.
        ndk {
            abiFilters += "arm64-v8a"
        }
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

    // Map for the track editor. MapLibre is the open-source fork of
    // Mapbox GL and needs no account or API key.
    implementation("org.maplibre.gl:android-sdk:13.7.1")

    // TrackEditor is plain state code, so it is tested on the JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.21")
}
