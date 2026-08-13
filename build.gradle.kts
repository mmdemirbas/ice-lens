plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
}

// The root project carries no source. It holds the version, the shared repositories and the
// coverage aggregation; the modules are :core (headless engine) and :desktop (Compose shell).
allprojects {
    group = "com.github.mmdemirbas.icelens"
    version = "1.0.2"

    repositories {
        mavenCentral()
        google() // JetBrains Compose resolves its underlying androidx.* artifacts here.
    }
}

dependencies {
    kover(project(":core"))
    kover(project(":desktop"))
}

/** Convenience: `./gradlew run` from the root still starts the desktop app. */
tasks.register("run") {
    group = "application"
    description = "Runs the desktop shell."
    dependsOn(":desktop:run")
}
