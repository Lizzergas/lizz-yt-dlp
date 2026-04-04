package com.lizz.ytdl.engine.youtube

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lizz.ytdl.androidmedia.LameEncoderBridge
import com.lizz.ytdl.core.DownloadEvent
import com.lizz.ytdl.core.TransferSnapshot
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidMp3Transcoder(
    private val context: Context,
) {
    suspend fun transcodeFileToMp3(
        inputFile: File,
        outputFile: File,
        durationSeconds: Int?,
        emit: suspend (DownloadEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        transcodeSource(
            configureExtractor = { extractor -> extractor.setDataSource(inputFile.absolutePath) },
            outputFile = outputFile,
            durationSeconds = durationSeconds,
            label = "Converting audio to mp3",
            emit = emit,
        )
    }

    suspend fun transcodeManifestToMp3(
        manifestUrl: String,
        headers: Map<String, String>,
        outputFile: File,
        durationSeconds: Int?,
        emit: suspend (DownloadEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        transcodeSource(
            configureExtractor = { extractor -> extractor.setDataSource(manifestUrl, headers) },
            outputFile = outputFile,
            durationSeconds = durationSeconds,
            label = "Downloading audio from manifest",
            emit = emit,
        )
    }

    private suspend fun transcodeSource(
        configureExtractor: (MediaExtractor) -> Unit,
        outputFile: File,
        durationSeconds: Int?,
        label: String,
        emit: suspend (DownloadEvent) -> Unit,
    ) {
        outputFile.parentFile?.mkdirs()
        val extractor = MediaExtractor()
        configureExtractor(extractor)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalStateException("No audio track found for Android transcoding")

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("Missing audio mime type")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val bitrateKbps = (format.getIntegerOrNull(MediaFormat.KEY_BIT_RATE)?.div(1000) ?: 192).coerceAtLeast(64)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        FileOutputStream(outputFile).use { outputStream ->
            LameEncoderBridge(sampleRate, channelCount, bitrateKbps).use { encoder ->
                decodeAndEncode(extractor, codec, encoder, outputStream, durationSeconds, label, emit)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
    }

    private suspend fun decodeAndEncode(
        extractor: MediaExtractor,
        codec: MediaCodec,
        encoder: LameEncoderBridge,
        outputStream: FileOutputStream,
        durationSeconds: Int?,
        label: String,
        emit: suspend (DownloadEvent) -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoderOutput = ByteArray(64 * 1024)
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Missing codec input buffer")
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: error("Missing codec output buffer")
                    if (bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val pcmBytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmBytes)
                        encodePcmChunk(encoder, pcmBytes, outputStream, encoderOutput)

                        val percent = durationSeconds
                            ?.takeIf { it > 0 }
                            ?.let { ((bufferInfo.presentationTimeUs.toDouble() / 1_000_000.0 / it) * 100).roundToInt().coerceIn(0, 100) }
                        emit(
                            DownloadEvent.ProgressChanged(
                                TransferSnapshot(
                                    label = label,
                                    progressPercent = percent,
                                    downloadedBytes = outputFileSize(outputStream),
                                )
                            )
                        )
                    }
                    outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            }
        }

        val flushed = encoder.flush(encoderOutput)
        if (flushed > 0) {
            outputStream.write(encoderOutput, 0, flushed)
        }
    }

    private fun encodePcmChunk(
        encoder: LameEncoderBridge,
        pcmBytes: ByteArray,
        outputStream: FileOutputStream,
        encoderOutput: ByteArray,
    ) {
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shortArray = ShortArray(shortBuffer.remaining())
        shortBuffer.get(shortArray)
        val channelSamples = shortArray.size / 2
        val encoded = encoder.encodeInterleaved(shortArray, channelSamples, encoderOutput)
        if (encoded > 0) {
            outputStream.write(encoderOutput, 0, encoded)
        }
    }

    private fun outputFileSize(outputStream: FileOutputStream): Long {
        return runCatching { outputStream.channel.size() }.getOrDefault(0L)
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? {
        return if (containsKey(key)) getInteger(key) else null
    }
}
