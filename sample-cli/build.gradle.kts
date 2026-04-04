plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.lizz.ytdl.sample.cli.Main")
}

dependencies {
    implementation(project(":youtube-downloader-core"))
    implementation(project(":youtube-downloader-engine-youtube"))
    implementation(libs.clikt)
    implementation(libs.mordant)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
