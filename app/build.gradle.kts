plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ethanward.flowtype"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ethanward.flowtype"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"

        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // sherpa-onnx's own Android AAR (native libraries + Kotlin API), put here by
    // scripts/fetch-sherpa-onnx.sh on the build box. Not committed.
    implementation(files("libs/sherpa-onnx.aar"))

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
