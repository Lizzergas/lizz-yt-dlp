package dev.lizz.ytdl.providers.youtube.hls

internal object HlsAudioSegments {
    fun stripLeadingId3Tags(bytes: ByteArray): ByteArray {
        var offset = 0
        while (true) {
            if (bytes.size - offset < 10) break
            if (bytes[offset] != 'I'.code.toByte() || bytes[offset + 1] != 'D'.code.toByte() || bytes[offset + 2] != '3'.code.toByte()) {
                break
            }

            val flags = bytes[offset + 5].toInt() and 0xFF
            val payloadSize = synchsafeInt(bytes, offset + 6)
            val footerSize = if (flags and 0x10 != 0) 10 else 0
            val tagSize = 10 + payloadSize + footerSize
            if (tagSize <= 0 || offset + tagSize > bytes.size) break
            offset += tagSize
        }

        return if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)
    }

    private fun synchsafeInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }
}
