plugins {
    id("lizz-ytdl-kmp-library")
    id("lizz-ytdl-publish")
    id("lizz-ytdl-compatibility")
    alias(libs.plugins.kotlin.serialization)
}

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.ByteArrayOutputStream

fun runTool(vararg command: String): String {
    val process = ProcessBuilder(*command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw GradleException("Command failed (${command.joinToString(" ")}): $output")
    }
    return output.trim()
}

fun runExec(vararg command: String) {
    val process = ProcessBuilder(*command)
        .inheritIO()
        .start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw GradleException("Command failed (${command.joinToString(" ")})")
    }
}

val lameVersion = "3.100"
val lameArchive = rootProject.file("third_party/lame-$lameVersion.tar.gz")
val extractedLameRoot = layout.buildDirectory.dir("third_party/lame-$lameVersion")

description = "Pure Kotlin YouTube extraction and download engine for lizz-yt-dlp."

val prepareLameSourceApple = tasks.register<Sync>("prepareLameSourceApple") {
    group = "build setup"
    description = "Extracts vendored LAME source for Apple native builds"

    if (!lameArchive.exists()) {
        throw GradleException("Missing third-party archive: ${lameArchive.absolutePath}")
    }

    from(tarTree(resources.gzip(lameArchive)))
    into(layout.buildDirectory.dir("third_party"))
}

kotlin {
    android {
        namespace = "dev.lizz.ytdl.engine.youtube"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":youtube-downloader-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.darwin)
        }

        androidMain.dependencies {
            implementation(project(":android-native-media"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    targets.withType(KotlinNativeTarget::class.java).configureEach {
        if (konanTarget.family.isAppleFamily) {
            val sdk = when (name) {
                "iosArm64" -> "iphoneos"
                "iosX64", "iosSimulatorArm64" -> "iphonesimulator"
                else -> null
            } ?: return@configureEach
            val arch = when (name) {
                "iosArm64" -> "arm64"
                "iosX64" -> "x86_64"
                "iosSimulatorArm64" -> "arm64"
                else -> return@configureEach
            }
            val minVersionFlag = if (sdk == "iphoneos") "-mios-version-min=15.0" else "-mios-simulator-version-min=15.0"
            val outDir = layout.buildDirectory.dir("apple-media/$name")
            val buildAppleMedia = tasks.register("buildAppleMedia${name.replaceFirstChar { it.uppercase() }}") {
                dependsOn(prepareLameSourceApple)
                doLast {
                    val out = outDir.get().asFile
                    val objDir = out.resolve("obj")
                    objDir.mkdirs()
                    val sdkRoot = runTool("xcrun", "--sdk", sdk, "--show-sdk-path")
                    val clang = runTool("xcrun", "--sdk", sdk, "--find", "clang")
                    val lameRoot = extractedLameRoot.get().asFile
                    val includeDir = project.file("src/iosInterop/include")
                    val wrapperSource = project.file("src/iosInterop/c/ytdl_ios_media.m")
                    val sources = listOf(
                        wrapperSource,
                        lameRoot.resolve("libmp3lame/bitstream.c"),
                        lameRoot.resolve("libmp3lame/encoder.c"),
                        lameRoot.resolve("libmp3lame/fft.c"),
                        lameRoot.resolve("libmp3lame/gain_analysis.c"),
                        lameRoot.resolve("libmp3lame/id3tag.c"),
                        lameRoot.resolve("libmp3lame/lame.c"),
                        lameRoot.resolve("libmp3lame/newmdct.c"),
                        lameRoot.resolve("libmp3lame/presets.c"),
                        lameRoot.resolve("libmp3lame/psymodel.c"),
                        lameRoot.resolve("libmp3lame/quantize.c"),
                        lameRoot.resolve("libmp3lame/quantize_pvt.c"),
                        lameRoot.resolve("libmp3lame/reservoir.c"),
                        lameRoot.resolve("libmp3lame/set_get.c"),
                        lameRoot.resolve("libmp3lame/tables.c"),
                        lameRoot.resolve("libmp3lame/takehiro.c"),
                        lameRoot.resolve("libmp3lame/util.c"),
                        lameRoot.resolve("libmp3lame/vbrquantize.c"),
                        lameRoot.resolve("libmp3lame/VbrTag.c"),
                        lameRoot.resolve("libmp3lame/version.c"),
                    )

                    sources.forEach { source ->
                        val output = objDir.resolve(source.nameWithoutExtension + ".o")
                        val args = mutableListOf(
                            clang,
                            "-c",
                            source.absolutePath,
                            "-o",
                            output.absolutePath,
                            "-DHAVE_CONFIG_H=1",
                            "-arch", arch,
                            "-isysroot", sdkRoot,
                            minVersionFlag,
                            "-I${includeDir.absolutePath}",
                            "-I${lameRoot.resolve("include").absolutePath}",
                            "-I${lameRoot.resolve("libmp3lame").absolutePath}",
                        )
                        if (source.extension == "m") {
                            args += listOf("-fobjc-arc")
                        } else {
                            args += listOf("-std=c11")
                        }
                        runExec(*args.toTypedArray())
                    }

                    runExec(
                        "xcrun", "libtool", "-static",
                        "-o", out.resolve("libytdl_ios_media.a").absolutePath,
                        *objDir.listFiles()!!.map { it.absolutePath }.toTypedArray(),
                    )
                }
            }

            compilations.getByName("main").cinterops.create("ytdliosmedia") {
                defFile(project.file("src/iosInterop/cinterop/ytdliosmedia.def"))
                includeDirs(project.file("src/iosInterop/include"))
            }

            compilations.getByName("main").compileTaskProvider.configure {
                dependsOn(buildAppleMedia)
            }

            binaries.all {
                linkerOpts(
                    "-L${outDir.get().asFile.absolutePath}",
                    "-lytdl_ios_media",
                    "-framework", "Foundation",
                    "-framework", "AVFoundation",
                    "-framework", "CoreMedia",
                    "-framework", "AudioToolbox",
                )
            }
        }
    }
}
