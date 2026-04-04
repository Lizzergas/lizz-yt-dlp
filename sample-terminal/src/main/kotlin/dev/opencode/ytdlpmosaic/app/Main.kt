@file:JvmName("Main")

package com.lizz.ytdl.sample.terminal.app

import com.github.ajalt.mordant.rendering.TextColors.brightBlue
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.terminal.Terminal
import com.jakewharton.mosaic.NonInteractivePolicy.Ignore
import com.jakewharton.mosaic.runMosaicBlocking
import com.lizz.ytdl.engine.youtube.JvmNativeYoutubeDownloaderFactory
import com.lizz.ytdl.sample.terminal.presentation.DownloaderScreen
import kotlin.jvm.JvmName

fun main(args: Array<String>) {
    val arguments = AppArguments.parse(args)
    if (arguments.showHelp) {
        printHelp()
        return
    }

    val viewModel = AppViewModel(JvmNativeYoutubeDownloaderFactory.createDefault())

    runMosaicBlocking(onNonInteractive = Ignore) {
        DownloaderScreen(viewModel, arguments.request)
    }
}

private fun printHelp() {
    val terminal = Terminal()
    terminal.println(brightBlue("kt-yt-dlp Terminal Sample"))
    terminal.println(yellow("Sample terminal app using the KMP downloader facade and native JVM YouTube mp3 engine"))
    terminal.println("")
    terminal.println("Usage:")
    terminal.println("  ./build/install/sample-terminal/bin/sample-terminal <youtube-url> [output-dir-or-file]")
    terminal.println("")
    terminal.println("Optional flags:")
    terminal.println("  --strict-certs")
    terminal.println("  --help")
}
