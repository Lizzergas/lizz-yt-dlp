package dev.lizz.ytdl.engine.youtube.hls

import kotlin.test.Test
import kotlin.test.assertContentEquals

class HlsAudioSegmentsTest {
    @Test
    fun `strips single leading id3 tag`() {
        val adtsPayload = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte())
        val bytes = id3Tag(byteArrayOf(1, 2, 3)) + adtsPayload

        assertContentEquals(adtsPayload, HlsAudioSegments.stripLeadingId3Tags(bytes))
    }

    @Test
    fun `strips multiple leading id3 tags`() {
        val adtsPayload = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x11)
        val bytes = id3Tag(byteArrayOf(1)) + id3Tag(byteArrayOf(2, 3)) + adtsPayload

        assertContentEquals(adtsPayload, HlsAudioSegments.stripLeadingId3Tags(bytes))
    }

    @Test
    fun `leaves non id3 payload unchanged`() {
        val payload = byteArrayOf(0x00, 0x11, 0x22, 0x33)

        assertContentEquals(payload, HlsAudioSegments.stripLeadingId3Tags(payload))
    }

    private fun id3Tag(payload: ByteArray): ByteArray {
        val size = payload.size
        val header = byteArrayOf(
            'I'.code.toByte(),
            'D'.code.toByte(),
            '3'.code.toByte(),
            4,
            0,
            0,
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte(),
        )
        return header + payload
    }
}
