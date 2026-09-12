pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"

    // Picks the right loom variant per Minecraft version (26.1+ vs older).
    id("dev.kikugie.loom-back-compat") version "0.4.2"

    // Auto-provisions JDK toolchains (17 / 21 / 25) when not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        versions("1.19.4", "1.21.10", "1.21.11")
        version("26.2.x", "26.2")
        vcsVersion = "26.2.x"
    }
}

rootProject.name = "clone-improved"
