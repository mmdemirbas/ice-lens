import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.github.mmdemirbas.icelens"
version = "1.0.2"

repositories {
    mavenCentral()
    google() // Required for JetBrains Compose to fetch underlying androidx.* dependencies
}

dependencies {
    // UI
    implementation(compose.desktop.currentOs)
    implementation(libs.material3.desktop)
    implementation(libs.material.icons.extended)

    // Serialization & Metadata Parsing
    implementation(libs.serialization.json)
    implementation(libs.avro4k.core)
    implementation(libs.avro)

    // Graph Layout Engine (ELK)
    implementation(libs.elk.core)
    implementation(libs.elk.layered)
    implementation(libs.xtext.xbase.lib)

    // Data Inspection
    implementation(libs.duckdb.jdbc)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

// Generate a version.properties file accessible at runtime
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
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "IcebergLens"
            modules("java.sql")
            macOS {
                bundleID = "com.iceberglens.desktop"
            }
        }
    }
}
