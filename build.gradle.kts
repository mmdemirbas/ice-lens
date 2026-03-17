import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.10"
    id("org.jetbrains.compose") version "1.10.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
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
    implementation("org.jetbrains.compose.material3:material3-desktop:1.10.0-alpha05")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3")

    // Serialization & Metadata Parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("com.github.avro-kotlin.avro4k:avro4k-core:2.10.0")
    implementation("org.apache.avro:avro:1.12.1")

    // Graph Layout Engine (ELK)
    implementation("org.eclipse.elk:org.eclipse.elk.core:0.11.0")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.11.0")
    implementation("org.eclipse.xtext:org.eclipse.xtext.xbase.lib:2.33.0")

    // Data Inspection
    implementation("org.duckdb:duckdb_jdbc:1.4.4.0")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.17")
}

compose.desktop {
    application {
        mainClass = "app.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(false)
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
