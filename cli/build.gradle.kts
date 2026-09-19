plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Shell #3. The engine from a terminal: a table listed as the tree the IDE strip draws, one
// node's rows, the integrity check as an exit code a script can branch on, and the exports the
// desktop offers from its menu written to a file. No layout unless an export needs positions, no
// window, nothing read that the command did not ask for.
//
// Over :core only, like the IDE plugin — the vocabulary it prints is `GraphTree`'s, which is
// core's for exactly this reason: a third shell with its own words for one set of things would
// drift from the other two the first time a node type is added.
dependencies {
    implementation(project(":core"))
    implementation(libs.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}

kotlin {
    compilerOptions {
        jvmToolchain(17)
    }
}

application {
    mainClass = "cli.MainKt"
    applicationName = "icelens"
}

tasks.test {
    useJUnitPlatform()
    // The command tests open the checked-in fixtures, the larger of which the readers decode whole.
    maxHeapSize = "2g"
}

// Version is written here as in :desktop: the binary says which build it is.
tasks.processResources {
    val versionFile = layout.buildDirectory.file("resources/main/version.properties")
    val projectVersion = version.toString()
    doFirst {
        val file = versionFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText("version=$projectVersion\n")
    }
}
