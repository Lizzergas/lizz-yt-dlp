import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.kotlin.multiplatform.library")
    kotlin("multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    android {
        compileSdk = libs.findVersion("android-compile-sdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("android-min-sdk").get().requiredVersion.toInt()
    }

    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    jvmToolchain(21)
    explicitApi()

    sourceSets.configureEach {
        languageSettings.progressiveMode = true
    }
}
