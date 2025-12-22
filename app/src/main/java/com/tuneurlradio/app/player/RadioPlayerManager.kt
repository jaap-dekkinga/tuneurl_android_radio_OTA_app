package com.tuneurlradio.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.tuneurlradio.app.domain.model.PlayerState
import com.tuneurlradio.app.domain.model.Station
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class RadioPlayerState(
    val currentStation: Station? = null,
    val isPlaying: Boolean = false,
    val playerState: PlayerState = PlayerState.OFFLINE,
    val trackName: String? = null,
    val artistName: String? = null
)

@Singleton
class RadioPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val _state = MutableStateFlow(RadioPlayerState())
    val state: StateFlow<RadioPlayerState> = _state.asStateFlow()

    private var pendingStation: Station? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlayerState(playbackState)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(
                playerState = PlayerState.OFFLINE,
                isPlaying = false
            )
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            _state.value = _state.value.copy(
                trackName = mediaMetadata.title?.toString(),
                artistName = mediaMetadata.artist?.toString()
            )
        }
    }

    private fun getOrCreateController(onReady: (MediaController) -> Unit) {
        mediaController?.let {
            onReady(it)
            return
        }

        val sessionToken = SessionToken(
            context,
            ComponentName(context, RadioPlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                if (controller != null) {
                    mediaController = controller
                    controller.addListener(playerListener)
                    onReady(controller)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun updatePlayerState(playbackState: Int) {
        val state = when (playbackState) {
            Player.STATE_IDLE -> PlayerState.OFFLINE
            Player.STATE_BUFFERING -> PlayerState.LOADING
            Player.STATE_READY -> if (mediaController?.isPlaying == true) PlayerState.LIVE else PlayerState.LOADING
            Player.STATE_ENDED -> PlayerState.OFFLINE
            else -> PlayerState.OFFLINE
        }
        _state.value = _state.value.copy(playerState = state)
    }

    fun play(station: Station) {
        _state.value = _state.value.copy(
            currentStation = station,
            playerState = PlayerState.LOADING,
            trackName = null,
            artistName = null
        )

        getOrCreateController { controller ->
            val mediaItem = MediaItem.Builder()
                .setUri(station.streamURL)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(station.shortDescription)
                        .setArtworkUri(station.imageURL?.let { android.net.Uri.parse(it) })
                        .build()
                )
                .build()

            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        }
    }

    fun resume() {
        mediaController?.play()
    }

    fun pause() {
        mediaController?.pause()
    }

    fun stop() {
        mediaController?.stop()
        _state.value = _state.value.copy(
            isPlaying = false,
            playerState = PlayerState.OFFLINE
        )
    }

    fun togglePlayback() {
        val controller = mediaController
        if (controller != null && controller.isPlaying) {
            stop()
        } else {
            _state.value.currentStation?.let { play(it) }
        }
    }

    fun release() {
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        controllerFuture = null
    }

    fun setVolume(volume: Float) {
        mediaController?.volume = volume.coerceIn(0f, 1f)
    }
}
