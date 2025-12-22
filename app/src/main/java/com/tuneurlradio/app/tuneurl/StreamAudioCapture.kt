package com.tuneurlradio.app.tuneurl

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

@OptIn(UnstableApi::class)
class StreamAudioCapture {

    private val MAX_BUFFER_SIZE = 900_000
    private val audioChunks = ConcurrentLinkedQueue<ByteArray>()
    private var currentBufferSize = 0

    @Volatile
    private var isCapturing = false

    @Volatile
    var capturedSampleRate = 0
        private set

    @Volatile
    var capturedChannelCount = 0
        private set

    fun createCapturingAudioSink(context: Context): AudioSink {
        val defaultSink = DefaultAudioSink.Builder(context).build()

        return object : AudioSink {
            override fun setListener(listener: AudioSink.Listener) {
                defaultSink.setListener(listener)
            }

            override fun supportsFormat(format: Format): Boolean {
                return defaultSink.supportsFormat(format)
            }

            override fun getFormatSupport(format: Format): Int {
                return defaultSink.getFormatSupport(format)
            }

            override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                return defaultSink.getCurrentPositionUs(sourceEnded)
            }

            override fun configure(
                inputFormat: Format,
                specifiedBufferSize: Int,
                outputChannels: IntArray?
            ) {
                capturedSampleRate = inputFormat.sampleRate
                capturedChannelCount = inputFormat.channelCount
                defaultSink.configure(inputFormat, specifiedBufferSize, outputChannels)
            }

            override fun play() {
                defaultSink.play()
            }

            override fun handleDiscontinuity() {
                defaultSink.handleDiscontinuity()
            }

            override fun handleBuffer(
                buffer: ByteBuffer,
                presentationTimeUs: Long,
                encodedAccessUnitCount: Int
            ): Boolean {
                if (isCapturing && buffer.hasRemaining()) {
                    val size = buffer.remaining()
                    val audioData = ByteArray(size)
                    val position = buffer.position()
                    buffer.get(audioData)
                    buffer.position(position)
                    addAudioChunk(audioData)
                }
                return defaultSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }

            override fun playToEndOfStream() {
                defaultSink.playToEndOfStream()
            }

            override fun isEnded(): Boolean {
                return defaultSink.isEnded
            }

            override fun hasPendingData(): Boolean {
                return defaultSink.hasPendingData()
            }

            override fun setPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters) {
                defaultSink.playbackParameters = playbackParameters
            }

            override fun getPlaybackParameters(): androidx.media3.common.PlaybackParameters {
                return defaultSink.playbackParameters
            }

            override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
                defaultSink.skipSilenceEnabled = skipSilenceEnabled
            }

            override fun getSkipSilenceEnabled(): Boolean {
                return defaultSink.skipSilenceEnabled
            }

            override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) {
                defaultSink.setAudioAttributes(audioAttributes)
            }

            override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? {
                return null
            }

            override fun setAudioSessionId(audioSessionId: Int) {
                try {
                    val method = defaultSink.javaClass.getMethod("setAudioSessionId", Int::class.javaPrimitiveType)
                    method.invoke(defaultSink, audioSessionId)
                } catch (e: Exception) {}
            }

            override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {
                defaultSink.setAuxEffectInfo(auxEffectInfo)
            }

            override fun enableTunnelingV21() {
                defaultSink.enableTunnelingV21()
            }

            override fun disableTunneling() {
                defaultSink.disableTunneling()
            }

            override fun setVolume(volume: Float) {
                try {
                    val method = defaultSink.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
                    method.invoke(defaultSink, volume)
                } catch (e: Exception) {}
            }

            override fun pause() {
                defaultSink.pause()
            }

            override fun flush() {
                defaultSink.flush()
            }

            override fun reset() {
                defaultSink.reset()
            }

            override fun release() {
                defaultSink.release()
            }

            override fun getAudioTrackBufferSizeUs(): Long {
                return try {
                    val method = defaultSink.javaClass.getMethod("getAudioTrackBufferSizeUs")
                    method.invoke(defaultSink) as Long
                } catch (e: Exception) {
                    0L
                }
            }
        }
    }

    fun startCapture() {
        if (isCapturing) return
        isCapturing = true
        audioChunks.clear()
        currentBufferSize = 0
    }

    fun stopCapture() {
        isCapturing = false
        audioChunks.clear()
        currentBufferSize = 0
    }

    private fun addAudioChunk(chunk: ByteArray) {
        audioChunks.offer(chunk)
        currentBufferSize += chunk.size

        while (currentBufferSize > MAX_BUFFER_SIZE && audioChunks.isNotEmpty()) {
            val removed = audioChunks.poll()
            if (removed != null) {
                currentBufferSize -= removed.size
            }
        }
    }

    fun getCurrentAudioBuffer(): ByteArray {
        if (audioChunks.isEmpty()) {
            return ByteArray(0)
        }

        val totalSize = audioChunks.sumOf { it.size }
        val combined = ByteArray(totalSize)
        var offset = 0

        for (chunk in audioChunks) {
            if (offset + chunk.size <= combined.size) {
                System.arraycopy(chunk, 0, combined, offset, chunk.size)
                offset += chunk.size
            } else {
                break
            }
        }

        return combined
    }

    fun isCapturing(): Boolean = isCapturing

    fun clearBuffer() {
        audioChunks.clear()
        currentBufferSize = 0
    }
}
