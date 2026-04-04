import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    includeBuild("build-logic")

    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("com.gradleup.nmcp.settings") version "1.4.4"
}

nmcpSettings {
    centralPortal {
        providers.environmentVariable("CENTRAL_PORTAL_USERNAME")
            .orElse(providers.environmentVariable("OSSRH_USERNAME"))
            .orNull
            ?.let { username = it }

        providers.environmentVariable("CENTRAL_PORTAL_PASSWORD")
            .orElse(providers.environmentVariable("OSSRH_PASSWORD"))
            .orNull
            ?.let { password = it }

        publishingType = "AUTOMATIC"
        publicationName = "lizz-yt-dlp:${providers.gradleProperty("version").orNull ?: "dev"}"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lizz-yt-dlp"

include(
    ":youtube-downloader-core",
    ":youtube-downloader-engine-youtube",
    ":android-native-media",
    ":samples",
    ":androidApp",
    ":iosApp",
)

project(":samples").projectDir = file("sample-compose")
