plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// The headless engine. Everything that reads, decodes, analyses or lays out a table lives
// here, and nothing here may depend on a UI toolkit — that is the property which lets the
// desktop app, a server, a CLI and an IDE plugin all sit over the same code.
//
// The `noComposeOnCoreClasspath` check below turns that from a convention into a build
// failure, because a stray import is exactly how this kind of boundary erodes.
dependencies {
    implementation(libs.serialization.json)
    implementation(libs.avro4k.core)
    implementation(libs.avro)
    implementation(libs.zstd.jni)

    implementation(libs.elk.core)
    implementation(libs.elk.layered)
    implementation(libs.xtext.xbase.lib)

    implementation(libs.duckdb.jdbc)
    implementation(libs.logback.classic)
    // Iceberg's Bucket transform hashes with this exact function; see model/BucketTransform.kt.
    implementation(libs.guava)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}

kotlin {
    compilerOptions {
        jvmToolchain(17)
    }
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Fails the build if a Compose or AndroidX artifact reaches core's compile classpath.
 *
 * A comment saying "no UI dependency here" is not a boundary; a resolved-configuration check
 * is. This runs before compilation so the failure names the offending dependency rather than
 * surfacing later as an unresolved reference in another module.
 */
val noComposeOnCoreClasspath by tasks.registering {
    val classpath = configurations.named("compileClasspath")
    doLast {
        val offenders = classpath.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .filter { it.group.startsWith("androidx.") || it.group.startsWith("org.jetbrains.compose") }
            .map { "${it.group}:${it.name}:${it.version}" }
            .distinct()
            .sorted()
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "core must stay UI-free, but its compile classpath contains:\n" +
                    offenders.joinToString("\n") { "  - $it" } +
                    "\n\nMove the type that needs it into :desktop, or replace it with a plain " +
                    "core type (see model.Point, which exists because Compose's Offset does not " +
                    "belong here)."
            )
        }
    }
}

tasks.named("compileKotlin") { dependsOn(noComposeOnCoreClasspath) }
