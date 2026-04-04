plugins {
    id("lizz-ytdl-kmp-library")
    id("lizz-ytdl-publish")
    id("lizz-ytdl-compatibility")
    alias(libs.plugins.kotlin.serialization)
}

description = "Public multiplatform API for lizz-yt-dlp."

kotlin {
    android {
        namespace = "dev.lizz.ytdl.core"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
