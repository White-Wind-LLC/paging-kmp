pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "paging-kmp"

include("paging-core")

// Conditionally include samples to avoid configuring/running its tasks in CI/publish
val excludeSamples: Boolean = gradle.startParameter.projectProperties["excludeSamples"]?.toBoolean() ?: false
if (!excludeSamples) {
    include("paging-samples")
}
