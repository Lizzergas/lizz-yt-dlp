val lameVersion = "3.100"
val lameArchive = rootProject.file("third_party/lame-$lameVersion.tar.gz")
val extractedLameRoot = layout.buildDirectory.dir("third_party/lame-$lameVersion")

val prepareLameSource = tasks.register<Sync>("prepareLameSource") {
    group = "build setup"
    description = "Extracts vendored LAME source from the tracked tar.gz archive"

    if (!lameArchive.exists()) {
        throw GradleException("Missing third-party archive: ${lameArchive.absolutePath}")
    }

    from(tarTree(resources.gzip(lameArchive)))
    into(layout.buildDirectory.dir("third_party"))
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lizz.ytdl.androidmedia"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.min.sdk.get().toInt()
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20")
                arguments += listOf(
                    "-DLAME_ROOT=${extractedLameRoot.get().asFile.absolutePath.replace("\\", "/")}",
                )
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

tasks.matching { it.name.startsWith("configureCMake") || it.name == "preBuild" }
    .configureEach {
        dependsOn(prepareLameSource)
    }
