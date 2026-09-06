import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

// Shell #2. The same engine, inside the IDE where the tables are worked on.
//
// ## Why this shell draws with Swing rather than reusing :desktop's cards
//
// It cannot reuse them. Compose Desktop 1.10.1 binds to Skiko **0.9.37.4**; IntelliJ 2026.2 ships
// Skiko **0.144.5** in `lib/intellij.libraries.skiko.jar` together with its own native library,
// and a plugin cannot override a platform class. Bundling Compose here produced
//
//     java.lang.UnsatisfiedLinkError: 'void org.jetbrains.skiko.paragraph.
//     ParagraphStyleKt._nSetFontRastrSettings(long, int, int, boolean)'
//
// on the first text layout — the Kotlin bindings from one version calling into the native library
// of another. The IDE also ships no Material3 at all; Jewel replaces it. Building against the
// platform's Compose instead would mean recompiling every card in :desktop against whatever
// version pairs with Skiko 0.144.5, and re-doing them in Jewel on top, which is a different
// product rather than a port.
//
// So this module depends on :core only — the readers, the model and the analysis, which is the
// part worth sharing — and draws with the IDE's own components. That is also the better tool
// window: it matches the editor beside it, follows the IDE theme for free, and adds ~150KB to the
// plugin instead of the ~100 Compose artifacts the other design would have shipped.

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// The IDE to build against. A checkout with no IDE downloads one; a machine that has one already:
//     ./gradlew :intellij:buildPlugin -PintellijLocalPath="$HOME/Applications/IntelliJ IDEA.app"
val localIde: String? = providers.gradleProperty("intellijLocalPath").orNull

dependencies {
    intellijPlatform {
        if (localIde != null) local(localIde) else create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7")
    }

    implementation(project(":core"))

    // No `testFramework(TestFrameworkType.Platform)`. These tests build a tree model from a real
    // fixture and touch no IDE service, and pulling the platform test framework in registers a
    // JUnit 5 session listener that cannot start outside a real platform test — which fails the
    // whole task before a single test runs.
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    // Not for writing tests — see the catalog entry. The platform's own listener needs it on the
    // classpath or the test executor fails to start with `org/junit/rules/TestRule`.
    testRuntimeOnly(libs.junit4)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.mmdemirbas.icelens"
        name = "Iceberg Lens"
        version = project.version.toString()
        // 251 is 2025.1. No untilBuild: this uses tool windows, actions and the VFS, none of which
        // have moved in years, so an upper bound would only make it stop working on an IDE it is
        // fine in.
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

kotlin {
    compilerOptions {
        // 21, not the 17 the other modules use: IntelliJ 2025.1 runs on Java 21 and refuses a
        // plugin compiled against a newer bytecode level than the IDE it is loaded into. The
        // engine in :core stays at 17, so it still builds for the desktop shell's own targets.
        jvmToolchain(21)
    }
}

tasks.test {
    useJUnitPlatform()
}

// Named for the product, not for the directory it happens to be built in.
tasks.buildPlugin {
    archiveBaseName = "iceberg-lens"
}
