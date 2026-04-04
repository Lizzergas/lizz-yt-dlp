plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.lizz.ytdl.sample.terminal.app.Main")
}

dependencies {
    implementation(project(":youtube-downloader-core"))
    implementation(project(":youtube-downloader-engine-youtube"))
    implementation(libs.mosaic.runtime)
    implementation(libs.mordant)
    implementation(libs.kotlinx.coroutines.core)
}
