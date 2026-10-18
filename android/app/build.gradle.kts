import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Reads android/app/google-services.json (gitignored; see README).
    id("com.google.gms.google-services")
}

android {
    namespace = "tech.miyukivigil.totem"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.miyukivigil.totem"
        // 28 = Android 9, the first version with StrongBox-backed Keystore keys.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // Same application ID as release (private project, one Firebase app).
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // App lock (fingerprint / face / screen-lock PIN).
    implementation("androidx.biometric:biometric:1.1.0")

    // QR scanning: CameraX for the camera, ZXing core (offline, no Google services) to decode.
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.zxing:core:3.5.3")

    // Relay WebSocket.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
