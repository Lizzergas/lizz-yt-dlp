plugins {
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "dev.lizz.ytdl.sample.compose.library"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
    }
    jvm("desktop")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        val appleMediaTaskPath = when (iosTarget.name) {
            "iosX64" -> ":youtube-downloader-engine-youtube:buildAppleMediaIosX64"
            "iosArm64" -> ":youtube-downloader-engine-youtube:buildAppleMediaIosArm64"
            "iosSimulatorArm64" -> ":youtube-downloader-engine-youtube:buildAppleMediaIosSimulatorArm64"
            else -> null
        }
        val appleMediaDir = rootProject.file("youtube-downloader-engine-youtube/build/apple-media/${iosTarget.name}")

        iosTarget.binaries.framework {
            baseName = "SampleCompose"
            isStatic = true
            transitiveExport = true
            export(project(":youtube-downloader-engine-youtube"))
            appleMediaTaskPath?.let { taskPath -> linkTaskProvider.configure { dependsOn(taskPath) } }
            linkerOpts(
                "-L${appleMediaDir.absolutePath}",
                "-lytdl_ios_media",
                "-framework", "Foundation",
                "-framework", "AVFoundation",
                "-framework", "CoreMedia",
                "-framework", "AudioToolbox",
            )
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":youtube-downloader-core"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.filekit.core)
                implementation(libs.filekit.dialogs.compose)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.core)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(project(":youtube-downloader-engine-youtube"))
            }
        }

        val iosX64Main by getting {
            dependencies {
                api(project(":youtube-downloader-engine-youtube"))
            }
        }

        val iosArm64Main by getting {
            dependencies {
                api(project(":youtube-downloader-engine-youtube"))
            }
        }

        val iosSimulatorArm64Main by getting {
            dependencies {
                api(project(":youtube-downloader-engine-youtube"))
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(project(":youtube-downloader-engine-youtube"))
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.lizz.ytdl.sample.compose.DesktopSampleDownloaderKt"
    }
}
