package com.lizz.ytdl.sample.compose

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.sink
import io.github.vinceglb.filekit.source
import kotlinx.io.buffered

internal object FileKitIo {
    fun copy(source: PlatformFile, destination: PlatformFile): Long {
        source.source().buffered().use { input ->
            destination.sink(append = false).buffered().use { output ->
                val transferred = input.transferTo(output)
                output.flush()
                return transferred
            }
        }
    }
}
