plugins {
    kotlin("multiplatform") version "2.3.20"
}

val lizzYtdlVersion = providers.gradleProperty("lizzYtdlVersion").get()

kotlin {
    jvm()
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation("dev.lizz.ytdl:youtube-downloader-core:$lizzYtdlVersion")
        }
        jvmMain.dependencies {
            implementation("dev.lizz.ytdl:youtube-downloader-engine-youtube:$lizzYtdlVersion")
        }
    }
}
