pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(org.gradle.api.initialization.resolve.RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val lizzYtdlRepoPath = gradle.startParameter.projectProperties["lizzYtdlRepoPath"]
        ?: error("Missing -PlizzYtdlRepoPath for smoke test repository")

    repositories {
        maven {
            url = uri(lizzYtdlRepoPath)
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "kmp-consumer"
