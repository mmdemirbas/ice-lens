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

    // Every transitive version is written down, not resolved afresh.
    //
    // The version catalog pins what this project *asks* for; it says nothing about what those
    // asks drag in, and that is most of the classpath — Compose alone brings in over a hundred
    // androidx artifacts whose versions are decided by conflict resolution. Without a lock, two
    // builds of the same commit can differ, which is the one thing a build should never do.
    //
    // Regenerate after any dependency change:
    //
    //     ./gradlew resolveAndLockAll --write-locks
    //
    // A build that resolves something the lock does not list fails rather than picking a version,
    // which is the point: the failure names the drift instead of absorbing it.
    dependencyLocking {
        lockAllConfigurations()
    }
}

/**
 * Resolves everything lockable so `--write-locks` has something to write.
 *
 * Gradle only records a lock for a configuration it actually resolves, and an ordinary build
 * resolves whichever subset the requested tasks need — so locking from `build` writes a partial
 * lockfile and the next build fails on the first configuration it missed. This walks every
 * resolvable configuration in every project instead.
 */
tasks.register("resolveAndLockAll") {
    group = "build setup"
    description = "Resolves every lockable configuration. Use with --write-locks."
    notCompatibleWithConfigurationCache("Resolves configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "Run with --write-locks, or this resolves everything and records nothing"
        }
    }
    doLast {
        allprojects.forEach { project ->
            project.configurations
                .filter { it.isCanBeResolved }
                .forEach { runCatching { it.resolve() } }
        }
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
