import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Shell #1. Compose Desktop over :core, in-process — no server, no network, direct java.nio
// and DuckDB JDBC. That combination is the product's defining property, so this module stays
// the reference shell even once a server exists.
dependencies {
    api(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(libs.material3.desktop)
    implementation(libs.material.icons.extended)
    implementation(libs.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    // AppState's tests build real tables on disk to load, which means writing Avro manifests.
    // Core keeps Avro as an implementation detail, so the fixture writers ask for it directly
    // rather than core widening its API to suit a test.
    testImplementation(libs.avro4k.core)
    testImplementation(libs.avro)
}

kotlin {
    compilerOptions {
        jvmToolchain(17)
    }
}

tasks.test {
    useJUnitPlatform()
}

// Version is written here rather than in core: it identifies the shipped application.
tasks.processResources {
    val versionFile = layout.buildDirectory.file("resources/main/version.properties")
    val projectVersion = version.toString()
    doFirst {
        val file = versionFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText("version=$projectVersion\n")
    }
}

compose.desktop {
    application {
        mainClass = "app.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(true)
            configurationFiles.from(rootProject.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "IcebergLens"
            packageVersion = project.version.toString()
            modules("java.sql")
            macOS {
                bundleID = "com.iceberglens.desktop"
            }
        }
    }
}
