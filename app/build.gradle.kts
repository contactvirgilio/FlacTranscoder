plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.flac2mp3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.flac2mp3"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"

        // The maintained FFmpegKit fork publishes arm64-v8a prebuilts.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // FFmpeg with LAME (audio package: lame, opus, vorbis, ...). Same API as the retired com.arthenica artifacts.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:6.0.3")
    // FFmpegKit calls into this helper, but the fork's POM does not pull it in. Without it every
    // conversion fails with NoClassDefFoundError. The two variants use different packages, so both can coexist.
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("com.arthenica:smart-exception-java9:0.2.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
