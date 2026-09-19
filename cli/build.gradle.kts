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
    // What `main` sets before the first logger: the tests call `run` directly, so it is set here,
    // or logback's default configuration prints DEBUG to standard output under the answer.
    systemProperty("logback.configurationFile", "logback-cli.xml")
}

// Version is written here as in :desktop: the binary says which build it is. A generated
// directory of its own, registered as a resource root, rather than a file dropped into
// processResources' output: Gradle treats a file there that no task declares as stale and
// removes it the next time the resources change, which a renamed logback.xml did.
val writeVersion by tasks.registering {
    val dir = layout.buildDirectory.dir("generated/version")
    val projectVersion = version.toString()
    inputs.property("version", projectVersion)
    outputs.dir(dir)
    doLast { dir.get().file("version.properties").asFile.writeText("version=$projectVersion\n") }
}
sourceSets.main { resources.srcDir(writeVersion) }
