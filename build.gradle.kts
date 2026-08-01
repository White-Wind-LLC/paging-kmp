import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.spotless.LineEnding
import dev.detekt.gradle.extensions.DetektExtension

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.compose).apply(false)
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

allprojects {
    group = "ua.wwind.paging"
    version = libsCatalog.findVersion("version").get().requiredVersion

    repositories {
        mavenCentral()
        google()
    }

    // Apply Dokka to root and all subprojects so documentation can be generated across modules
    apply(plugin = "org.jetbrains.dokka")

    // detekt owns code smells. Its ktlint-wrapper ruleset is deliberately not on the
    // classpath, so formatting rules cannot collide with Spotless.
    apply(plugin = "dev.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel.set(true)
        basePath.set(rootDir)

        // The aggregate `detekt` task defaults to the JVM layout (src/main/kotlin) and would
        // report NO-SOURCE in a multiplatform module. Point it at the whole `src` tree so every
        // source set is analysed exactly once — the per-source-set tasks detekt also registers
        // would overlap between Android variants and are not wired into `check`.
        source.setFrom(layout.projectDirectory.dir("src"))
    }
}

// Spotless owns text layout of Kotlin sources.
//
// Configured on the root project only, with a glob covering every module, so that a single
// Spotless invocation formats the whole repository.
//
// ktlint settings are passed through editorConfigOverride rather than read from .editorconfig:
// Spotless 8.9.0 does not honour the repository-root .editorconfig for ktlint properties, and
// setEditorConfigPath does not change that — verified by toggling ktlint_standard_filename in
// both places and observing that only the override takes effect. Leaving these keys in
// .editorconfig would look configured while silently running on ktlint's own defaults.
//
// The split is by consumer, so no key is declared twice: .editorconfig holds what the IDE
// reads (charset, indentation, line length), this block holds the ktlint-only keys.
apply(plugin = "com.diffplug.spotless")
extensions.configure<SpotlessExtension> {
    // Spotless defaults to GIT_ATTRIBUTES, which reads git metadata as an undeclared build
    // input and invalidates the configuration cache on every single run. LF is what
    // .editorconfig mandates anyway, so state it directly.
    lineEndings = LineEnding.UNIX

    kotlin {
        target("*/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint().editorConfigOverride(
            mapOf(
                // Match the IDE: gradle.properties already sets kotlin.code.style=official.
                // ktlint's own `ktlint_official` style is stricter and would reject code that
                // IntelliJ's formatter produces.
                "ktlint_code_style" to "intellij_idea",
                // @Composable functions are PascalCase by Compose convention.
                "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                // Disabled: the rule contradicts conventions this project already follows —
                // platform-suffixed file names (App.android.kt) and lowercase entry points
                // (main.kt).
                "ktlint_standard_filename" to "disabled",
            ),
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Convenience task to generate documentation for the whole project
tasks.register("generateDocs") {
    // Dokka 2.0.0 provides a unified task `dokkaGenerate` for single- and multi-module builds
    dependsOn(":dokkaGenerate")
}
