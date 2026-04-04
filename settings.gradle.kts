import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    includeBuild("build-logic")

    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
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
