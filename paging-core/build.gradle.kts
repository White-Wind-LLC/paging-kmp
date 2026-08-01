@file:OptIn(ExperimentalWasmDsl::class)
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Action
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.maven.publish)
    // AGP 9 removed Kotlin Multiplatform support from com.android.library; this is its
    // multiplatform replacement, configured through the kotlin { android(...) } block below.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.buildkonfig)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    jvm()
    // AGP registers its multiplatform Android target as an extension named "android" on the
    // Kotlin extension. A bare `android { }` cannot be used here: in a plain build script it
    // binds to KGP's own deprecated `android()` target shortcut, which shadows the accessor and
    // fails to compile. Passing an Action makes that shortcut inapplicable, so the AGP
    // extension wins. (Convention plugins can write `android { }` directly.)
    android(
        Action<KotlinMultiplatformAndroidLibraryTarget> {
            namespace = "ua.wwind.paging.core"
            compileSdk = 37
            minSdk = 21

            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        },
    )
    js {
        nodejs {
            // DiagnosticsFindingsTest runs micro-benchmarks that exceed Mocha's 2s default.
            testTask {
                useMocha { timeout = "120s" }
            }
        }
    }
    wasmJs {
        nodejs {
            testTask {
                useMocha { timeout = "120s" }
            }
        }
    }
    linuxX64()
    linuxArm64()
    mingwX64()
    macosArm64()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.arrow.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.assertions.core)
        }
    }
}

// POM/Publishing properties are sourced from gradle.properties
val pomName: String = providers.gradleProperty("POM_NAME").get()
val pomDescription: String = providers.gradleProperty("POM_DESCRIPTION").get()
val pomInceptionYear: String = providers.gradleProperty("POM_INCEPTION_YEAR").get()
val pomUrl: String = providers.gradleProperty("POM_URL").get()
val pomLicenseName: String = providers.gradleProperty("POM_LICENSE_NAME").get()
val pomLicenseUrl: String = providers.gradleProperty("POM_LICENSE_URL").get()
val pomLicenseDist: String = providers.gradleProperty("POM_LICENSE_DIST").get()
val pomDeveloperId: String = providers.gradleProperty("POM_DEVELOPER_ID").get()
val pomDeveloperName: String = providers.gradleProperty("POM_DEVELOPER_NAME").get()
val pomDeveloperUrl: String = providers.gradleProperty("POM_DEVELOPER_URL").get()
val pomScmUrl: String = providers.gradleProperty("POM_SCM_URL").get()
val pomScmConnection: String = providers.gradleProperty("POM_SCM_CONNECTION").get()
val pomScmDevConnection: String = providers.gradleProperty("POM_SCM_DEV_CONNECTION").get()

// Configure Maven Central publishing & signing
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(project.group.toString(), "paging-core", project.version.toString())

    pom {
        name.set(pomName)
        description.set(pomDescription)
        inceptionYear.set(pomInceptionYear)
        url.set(pomUrl)
        licenses {
            license {
                name.set(pomLicenseName)
                url.set(pomLicenseUrl)
                distribution.set(pomLicenseDist)
            }
        }
        developers {
            developer {
                id.set(pomDeveloperId)
                name.set(pomDeveloperName)
                url.set(pomDeveloperUrl)
            }
        }
        scm {
            url.set(pomScmUrl)
            connection.set(pomScmConnection)
            developerConnection.set(pomScmDevConnection)
        }
    }
}

// Javadoc jar for Maven Central, based on Dokka HTML output
tasks.register<org.gradle.jvm.tasks.Jar>("javadocJar") {
    dependsOn("dokkaGeneratePublicationHtml")
    from(layout.buildDirectory.dir("dokka/html"))
    archiveClassifier.set("javadoc")
}

// Ensure build fails if tests fail
tasks.named("build").configure {
    dependsOn("check")
}

buildkonfig {
    packageName = "ua.wwind.paging.core"
    defaultConfigs {
        val logLevel: String = providers.gradleProperty("LOG_LEVEL").orNull
            ?: providers.environmentVariable("LOG_LEVEL").orNull
            ?: "Debug"
        buildConfigField(com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING, "LOG_LEVEL", logLevel)
    }
}