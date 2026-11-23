// Flowtype's text pipeline with no Android in it (PLAN §7 Phase 5): spoken
// commands, the dictionary pass and AI cleanup. The phone app and the desktop
// command both use it, so a prompt or dictionary change reaches both.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // org.json is part of Android. Bundling a second copy into the APK would
    // shadow the platform's, so the desktop command brings its own instead.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
