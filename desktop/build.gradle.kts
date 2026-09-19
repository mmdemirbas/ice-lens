import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
    // The command line rides in the installers: its jar on the app's classpath, and a second
    // jpackage launcher (`icelensLauncher`, below) that starts `cli.MainKt` on it.
    implementation(project(":cli"))

    implementation(compose.desktop.currentOs)
    implementation(libs.material3.desktop)
    implementation(libs.material.icons.extended)
    implementation(libs.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit5)
    // Test-only: CardHeightTest asks the sealed GraphNode hierarchy what its subtypes are,
    // so a node kind added without a card is a failure rather than a number to bump by hand.
    testImplementation(libs.kotlin.reflect)
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

// Version is written here rather than in core: it identifies the shipped application. A
// generated directory registered as a resource root (see :cli for why not processResources' own).
val writeVersion by tasks.registering {
    val dir = layout.buildDirectory.dir("generated/version")
    val projectVersion = version.toString()
    inputs.property("version", projectVersion)
    outputs.dir(dir)
    doLast { dir.get().file("version.properties").asFile.writeText("version=$projectVersion\n") }
}
sourceSets.main { resources.srcDir(writeVersion) }

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
            // The runtime is jlinked from this list and nothing else — a module it lacks is a
            // NoClassDefFoundError on the first use, inside the installed app and nowhere in the
            // suite, which runs on a full JDK. `./gradlew :desktop:suggestRuntimeModules` is
            // jdeps over the jars; `java.naming` is what it misses, because logback's JNDI
            // handler is reached by reflection — without it every installer built since
            // logging arrived failed on the first logger with `javax/naming/NamingException`.
            modules("java.compiler", "java.instrument", "java.naming", "java.prefs", "java.sql", "jdk.unsupported")

            // Generated and committed by `java tools/icon/GenerateIcon.java`. Committed rather
            // than generated at build time, because a packaging step that draws its own icon is a
            // step that can fail on a machine nobody has tested it on — and the three platforms
            // want three container formats, not three sizes of the same one.
            val icons = project.file("src/main/resources/icon")
            macOS {
                bundleID = "com.iceberglens.desktop"
                iconFile.set(icons.resolve("icon.icns"))
            }
            windows {
                iconFile.set(icons.resolve("icon.ico"))
            }
            linux {
                iconFile.set(icons.resolve("icon.png"))
            }
        }
    }
}

// `icelens` inside the installers: jpackage's --add-launcher builds a second native launcher —
// `Contents/MacOS/icelens` in the .app, `bin/icelens` under /opt on Linux, `icelens.exe` beside
// the app's on Windows — from `launchers/icelens.properties`, over the same jars and runtime.
// A launcher rather than a script under `app/resources/`, because the runtime Compose jlinks
// carries no `bin/java` (jlink's --strip-native-commands) and jpackage copies a resource
// without its execute bit — both seen on the macOS bundle. Only the app image is built with
// it: the .dmg, .msi and .deb are packaged *from* that image (`--app-image`), where jpackage
// refuses the option.
val icelensLauncher = layout.projectDirectory.file("launchers/icelens.properties")
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    if (targetFormat == org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage) {
        inputs.file(icelensLauncher)
        freeArgs.addAll("--add-launcher", "icelens=${icelensLauncher.asFile.absolutePath}")
    }
}

// ProGuard rewrites every class it passes, and ELK's jars are signed by Eclipse: the rewritten
// classes no longer match `META-INF/ECLIPSE_.SF`, and the JVM refuses the first one loaded —
// `SecurityException: SHA-256 digest error for org/eclipse/elk/core/data/LayoutMetaDataService.class`
// on the first table the release app opens, and on every command of the release `icelens`.
// Compose hands ProGuard each jar unfiltered, so the signature entries are dropped from what it
// wrote; a jar without them is an unsigned jar, which is what every other jar here is.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask>().configureEach {
    doLast {
        destinationDir.get().asFile.listFiles { f -> f.extension == "jar" }.orEmpty().forEach { stripJarSignatures(it) }
    }
}

fun stripJarSignatures(jar: File) {
    val signature = Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")
    val stripped = File(jar.path + ".unsigned")
    var dropped = 0
    ZipFile(jar).use { zip ->
        ZipOutputStream(stripped.outputStream().buffered()).use { out ->
            zip.entries().asSequence().forEach { entry ->
                if (signature.matches(entry.name)) { dropped++; return@forEach }
                out.putNextEntry(ZipEntry(entry.name).also { it.time = entry.time })
                if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
    if (dropped == 0) stripped.delete()
    else Files.move(stripped.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
}
