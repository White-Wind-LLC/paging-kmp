import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Action
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    // AGP 9 removed Kotlin Multiplatform support from com.android.library; this is its
    // multiplatform replacement, configured through the kotlin { android(...) } block below.
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // See paging-core/build.gradle.kts: the Action wrapper is what keeps this bound to AGP's
    // "android" extension instead of KGP's deprecated android() target shortcut.
    android(
        Action<KotlinMultiplatformAndroidLibraryTarget> {
            namespace = "ua.wwind.paging.sample"
            compileSdk = 37
            minSdk = 21

            // Required so Compose composeResources keep packaging into consumers' APKs (CMP-9547)
            androidResources {
                enable = true
            }

            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        },
    )

    jvm()

    js {
        browser {
            commonWebpackConfig {
                outputFileName = "paging-sample.js"
                devServer = devServer?.copy() ?: KotlinWebpackConfig.DevServer()
            }
        }
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "paging-sample.js"
                devServer = devServer?.copy() ?: KotlinWebpackConfig.DevServer()
            }
        }
        binaries.executable()
    }

    // Compose Multiplatform 1.11 no longer publishes iosX64 artifacts, so the sample skips that target.
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "Paging sample"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.collections.immutable)

            implementation(project(":paging-core"))
        }

        androidMain.dependencies {
            implementation(compose.uiTooling)
            implementation(libs.androidx.activityCompose)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }

    }
}

// `paging-core` is built without the Compose compiler plugin, so the compiler cannot infer
// stability for its types and would treat every one of them as unstable. The configuration file
// declares them stable; it lives at the repository root so that consumers can copy it verbatim.
composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose_compiler_config.conf"),
    )

    // `./gradlew :paging-samples:compileKotlinJvm -PcomposeReports` writes the compiler's own
    // stability report next to the classes, which is how the configuration above is verified.
    if (providers.gradleProperty("composeReports").isPresent) {
        val reports = layout.buildDirectory.dir("compose-reports")
        reportsDestination.set(reports)
        metricsDestination.set(reports)
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Paging sample"
            packageVersion = "1.0.0"

            linux {
                iconFile.set(project.file("desktopAppIcons/LinuxIcon.png"))
            }
            windows {
                iconFile.set(project.file("desktopAppIcons/WindowsIcon.ico"))
            }
            macOS {
                iconFile.set(project.file("desktopAppIcons/MacosIcon.icns"))
                bundleID = "ua.wwind.paging.sample"
            }
        }
    }
}
