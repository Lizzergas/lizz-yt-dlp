import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk = libs.findVersion("android-compile-sdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = libs.findVersion("android-min-sdk").get().requiredVersion.toInt()
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}
