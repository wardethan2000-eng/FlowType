// flowtype-clean: Flowtype's cleanup for the Linux laptop, run by voxtype's
// [output.post_process] hook (PLAN §7 Phase 5). Text in on stdin, text out on
// stdout. `fatJar` makes the one jar ~/.local/bin/flowtype-clean runs.
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
    implementation(project(":core"))
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.register<Jar>("fatJar") {
    archiveFileName.set("flowtype-clean.jar")
    manifest { attributes("Main-Class" to "com.ethanward.flowtype.desktop.MainKt") }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}
