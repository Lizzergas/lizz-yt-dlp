package dev.lizz.ytdl.providers.youtube

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.lizz.ytdl.androidmedia.LameEncoderBridge
import dev.lizz.ytdl.core.MediaEvent
import dev.lizz.ytdl.core.TransferSnapshot
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidMp3Transcoder(
) {
    suspend fun transcodeFileToMp3(
        inputFile: File,
        outputFile: File,
        durationSeconds: Int?,
        emit: suspend (MediaEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        emit(MediaEvent.LogEmitted("Android transcoder opening local file source: ${inputFile.absolutePath}"))
        transcodeSource(
            configureExtractor = { extractor -> extractor.setDataSource(inputFile.absolutePath) },
            outputFile = outputFile,
            durationSeconds = durationSeconds,
            label = "Converting audio to mp3",
            emit = emit,
        )
    }

    private suspend fun transcodeSource(
        configureExtractor: suspend (MediaExtractor) -> Unit,
        outputFile: File,
        durationSeconds: Int?,
        label: String,
        emit: suspend (MediaEvent) -> Unit,
    ) {
        outputFile.parentFile?.mkdirs()
        val extractor = MediaExtractor()
        emit(MediaEvent.LogEmitted("Android transcoder configuring MediaExtractor"))
        try {
            configureExtractor(extractor)
        } catch (e: Exception) {
            emit(MediaEvent.LogEmitted("Android MediaExtractor setup failed: ${e::class.simpleName}: ${e.message}"))
            extractor.release()
            throw e
        }
        emit(MediaEvent.LogEmitted("Android transcoder track count: ${extractor.trackCount}"))
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalStateException("No audio track found for Android transcoding. Track count=${extractor.trackCount}")

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("Missing audio mime type")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val bitrateKbps = (format.getIntegerOrNull(MediaFormat.KEY_BIT_RATE)?.div(1000) ?: 192).coerceAtLeast(64)
        emit(MediaEvent.LogEmitted("Android transcoder selected track=$trackIndex mime=$mime sampleRate=$sampleRate channelCount=$channelCount bitrateKbps=$bitrateKbps"))

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        emit(MediaEvent.LogEmitted("Android transcoder decoder started"))

        FileOutputStream(outputFile).use { outputStream ->
            LameEncoderBridge(sampleRate, channelCount, bitrateKbps).use { encoder ->
                decodeAndEncode(extractor, codec, encoder, outputStream, durationSeconds, label, emit)
            }
        }

        emit(MediaEvent.LogEmitted("Android transcoder decoder finished; stopping codec"))
        codec.stop()
        codec.release()
        extractor.release()
        emit(MediaEvent.LogEmitted("Android transcoder wrote MP3 to ${outputFile.absolutePath}"))
    }

    private suspend fun decodeAndEncode(
        extractor: MediaExtractor,
        codec: MediaCodec,
        encoder: LameEncoderBridge,
        outputStream: FileOutputStream,
        durationSeconds: Int?,
        label: String,
        emit: suspend (MediaEvent) -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoderOutput = ByteArray(64 * 1024)
        var inputDone = false
        var outputDone = false
        var decodedFrames = 0L
        var lastLoggedPercent = -1

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Missing codec input buffer")
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                        emit(MediaEvent.LogEmitted("Android transcoder queued end-of-stream to decoder"))
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
                        decodedFrames++

                        val percent = durationSeconds
                            ?.takeIf { it > 0 }
                            ?.let { ((bufferInfo.presentationTimeUs.toDouble() / 1_000_000.0 / it) * 100).roundToInt().coerceIn(0, 100) }
                        if (percent != null && percent / 10 != lastLoggedPercent / 10) {
                            lastLoggedPercent = percent
                            emit(MediaEvent.LogEmitted("Android transcoder progress: $percent% decodedFrames=$decodedFrames bytesWritten=${outputFileSize(outputStream)}"))
                        }
                        emit(
                            MediaEvent.ProgressChanged(
                                TransferSnapshot(
                                    label = label,
                                    progressPercent = percent,
                                    downloadedBytes = outputFileSize(outputStream),
                                )
                            )
                        )
                    }
                    outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (outputDone) {
                        emit(MediaEvent.LogEmitted("Android transcoder reached decoder end-of-stream"))
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    emit(MediaEvent.LogEmitted("Android transcoder decoder output format changed: ${codec.outputFormat}"))
                }

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            }
        }

        val flushed = encoder.flush(encoderOutput)
        if (flushed > 0) {
            outputStream.write(encoderOutput, 0, flushed)
        }
        emit(MediaEvent.LogEmitted("Android transcoder flushed encoder bytes=$flushed"))
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
