import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
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

rootProject.name = "yt-dlp-mosaic-demo"

include(
    ":youtube-downloader-core",
    ":youtube-downloader-engine-youtube",
    ":android-native-media",
    ":sample-terminal",
    ":sample-cli",
    ":sample-compose",
    ":androidApp",
    ":iosApp",
)
