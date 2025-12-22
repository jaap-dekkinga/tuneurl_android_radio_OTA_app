package com.tuneurlradio.app.tuneurl

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink

@OptIn(UnstableApi::class)
class CapturingRenderersFactory(
    context: Context,
    private val audioCapture: StreamAudioCapture
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return audioCapture.createCapturingAudioSink(context)
    }
}
