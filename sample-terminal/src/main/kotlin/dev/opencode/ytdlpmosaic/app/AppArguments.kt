package com.lizz.ytdl.sample.terminal.app

import com.lizz.ytdl.core.DownloadOptions
import com.lizz.ytdl.core.DownloadRequest

data class AppArguments(
    val request: DownloadRequest,
    val showHelp: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): AppArguments {
            var url: String? = null
            var outputTarget: String? = null
            var strictCertificates = false
            var showHelp = false

            args.forEach { arg ->
                when {
                    arg == "--help" || arg == "-h" -> showHelp = true
                    arg == "--strict-certs" -> strictCertificates = true

                    url == null -> url = arg
                    outputTarget == null -> outputTarget = arg
                }
            }

            return AppArguments(
                request = DownloadRequest(
                    url = url ?: "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    options = DownloadOptions(
                        outputPath = outputTarget,
                        strictCertificates = strictCertificates,
                    ),
                ),
                showHelp = showHelp,
            )
        }
    }
}
