package com.lizz.ytdl.androidmedia

class LameEncoderBridge(
    sampleRate: Int,
    channelCount: Int,
    bitrateKbps: Int,
) : AutoCloseable {
    private val handle: Long = nativeInit(sampleRate, channelCount, bitrateKbps)

    fun encodeInterleaved(samples: ShortArray, samplesPerChannel: Int, outputBuffer: ByteArray): Int {
        return nativeEncodeInterleaved(handle, samples, samplesPerChannel, outputBuffer)
    }

    fun flush(outputBuffer: ByteArray): Int {
        return nativeFlush(handle, outputBuffer)
    }

    override fun close() {
        nativeClose(handle)
    }

    private external fun nativeInit(sampleRate: Int, channelCount: Int, bitrateKbps: Int): Long
    private external fun nativeEncodeInterleaved(handle: Long, samples: ShortArray, samplesPerChannel: Int, outputBuffer: ByteArray): Int
    private external fun nativeFlush(handle: Long, outputBuffer: ByteArray): Int
    private external fun nativeClose(handle: Long)

    companion object {
        init {
            System.loadLibrary("ytdl_android_media")
        }
    }
}
