import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("PotsDatabase") {
            packageName.set("com.pusios.pots.db")
            // The schema lives on user devices — structural changes need a
            // numbered .sqm migration file (see conversation.sq header).
            // This makes the build verify that migrations reproduce the schema.
            verifyMigrations.set(true)
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose_stability.conf"))
}

kotlin {
    android {
        namespace = "com.pusios.pots.shared"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        androidResources {
            enable = true
        }
        withHostTest {}
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            // Dynamic so LiteRT-LM's `-Xlinker -all_load` (in its Package.swift) doesn't
            // sweep up ComposeApp's static archive too and trip thousands of duplicate
            // symbols at link time. Each framework gets its own link context.
            isStatic = false
            // Must differ from the iosApp bundle identifier — iOS refuses to install a
            // .app whose embedded framework shares its parent's identifier (MIInstaller
            // error 57 / DuplicateIdentifier).
            binaryOption("bundleId", "com.pusios.pots.composeapp")
        }
    }

    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "composeApp"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer =
                    (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                        // Serve sources to debug inside browser
                        static(rootDirPath)
                        static(projectDirPath)
                    }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/src/commonMain/kotlin"))
            dependencies {
                implementation(libs.compose.material3)
                implementation(libs.compose.material.icons.core)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.components.uiToolingPreview)

                implementation(libs.androidx.navigation.compose)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)

                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)

                implementation(libs.tts)
                implementation(libs.tts.compose)

                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.koin.core)

                implementation(libs.multiplatform.settings)
                implementation(libs.multiplatform.settings.no.arg)

                implementation(libs.filekit.core)
                implementation(libs.filekit.compose)

                implementation(libs.coil.compose)
                implementation(libs.coil.svg)
                implementation(libs.coil.network.ktor3)

                implementation(libs.reorderable)

                implementation(libs.sqldelight.runtime)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.multiplatform.settings.test)
            }
        }
        androidMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.documentfile)
                implementation(libs.androidx.lifecycle.process)
                implementation(libs.spght.encryptedprefs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.koin.android)
                implementation(libs.material)
                implementation(libs.bouncycastle.provider)
                implementation(libs.litert.lm)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.nanohttpd)
                implementation(libs.xz)
            }
        }
        getByName("desktopMain") {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation(libs.bouncycastle.provider)
                implementation(libs.slf4j.nop)
                implementation(libs.litert.lm.jvm)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.nanohttpd)
            }
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.ktor.network)
            implementation(libs.ktor.network.tls)
            implementation(libs.sqldelight.native.driver)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.pusios.pots.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(
                project.file("proguard-rules.pro"),
                project.file("proguard-desktop.pro"),
            )
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Pots"
            packageVersion = libs.versions.appVersion.get()

            macOS {
                iconFile.set(project.file("icon.icns"))
            }
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "Pots"
            }
            linux {
                iconFile.set(project.file("icon.png"))
                // Applies to the shared jlink runtime image for every platform, not just Linux —
                // java.sql is required since the SQLDelight/SQLite migration (9d6ceec9).
                modules("jdk.security.auth", "java.sql")
            }
        }
    }
}

// BouncyCastle is a cryptographically signed JCE provider jar. ProGuard rewrites
// it and strips the META-INF signatures, causing "SHA-256 digest error" at
// runtime. After ProGuard finishes, replace the processed jar with the original.
afterEvaluate {
    tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
        doLast {
            val proguardDir =
                layout.buildDirectory
                    .dir("compose/tmp/main-release/proguard")
                    .get()
                    .asFile
            val processedJar = proguardDir.listFiles()?.find { it.name.startsWith("bcprov") } ?: return@doLast
            val originalJar =
                configurations["desktopRuntimeClasspath"]
                    .resolve()
                    .find { it.name.startsWith("bcprov") } ?: return@doLast
            originalJar.copyTo(processedJar, overwrite = true)
            logger.lifecycle("Restored original signed BouncyCastle jar: ${processedJar.name}")
        }
    }
}

class VersionGeneratorPlugin : Plugin<Project> {
    /** Escapes a value so it can be embedded in a Kotlin string literal. */
    private fun String.escapeKotlinString(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\${'$'}")

    override fun apply(project: Project) {
        project.afterEvaluate {
            val appVersion = libs.versions.appVersion.get()

            // Generate Kotlin version file
            val versionFile =
                layout.buildDirectory
                    .file("generated/src/commonMain/kotlin/com/pusios/pots/Version.kt")
                    .get()
                    .asFile
            versionFile.parentFile?.mkdirs()
            versionFile.writeText(
                """
                package com.pusios.pots

                object Version {
                    const val appVersion = "$appVersion"
                }
                """.trimIndent(),
            )

            // Generate the build-time secrets file.
            //
            // Secrets are never committed: they are read from `local.properties` in the repo
            // root (git-ignored) and baked into the binary. A build without that file simply
            // ships without the bundled credentials — the user can still enter their own key
            // in the settings, which always takes precedence over the bundled default.
            val secretsFile =
                layout.buildDirectory
                    .file("generated/src/commonMain/kotlin/com/pusios/pots/BuildSecrets.kt")
                    .get()
                    .asFile
            secretsFile.parentFile?.mkdirs()
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localPropertiesFile.inputStream().use { localProperties.load(it) }
            }
            val freeTierApiKey = localProperties.getProperty("pots.free.apiKey", "").trim()
            secretsFile.writeText(
                """
                package com.pusios.pots

                /**
                 * Build-time credentials, generated by the Gradle build from `local.properties`.
                 *
                 * The values are intentionally *not* checked into version control. A build
                 * without a populated `local.properties` produces empty strings, and the app
                 * then relies on the key the user enters in the settings.
                 */
                object BuildSecrets {
                    /** Default API key for the hosted free tier. Empty when not configured. */
                    const val FREE_TIER_API_KEY: String = "${freeTierApiKey.escapeKotlinString()}"
                }
                """.trimIndent(),
            )

            // Update iOS Config.xcconfig with version
            val xcConfigFile = rootProject.file("iosApp/Configuration/Config.xcconfig")
            if (xcConfigFile.exists()) {
                val content = xcConfigFile.readText()
                val updatedContent =
                    if (content.contains("APP_VERSION=")) {
                        content.replace(Regex("APP_VERSION=.*"), "APP_VERSION=$appVersion")
                    } else {
                        content.trimEnd() + "\nAPP_VERSION=$appVersion\n"
                    }
                xcConfigFile.writeText(updatedContent)
            }
        }
    }
}

apply<VersionGeneratorPlugin>()
