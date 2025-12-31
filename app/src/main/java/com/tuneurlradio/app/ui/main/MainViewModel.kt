package com.tuneurlradio.app.ui.main

import androidx.lifecycle.viewModelScope
import com.tuneurlradio.app.core.mvi.MviViewModel
import com.tuneurlradio.app.data.local.SettingsDataStore
import com.tuneurlradio.app.data.local.StationsDataSource
import com.tuneurlradio.app.domain.model.PlayerState
import com.tuneurlradio.app.domain.model.Station
import com.tuneurlradio.app.player.RadioPlayerManager
import com.tuneurlradio.app.tuneurl.TuneURLManager
import com.tuneurlradio.app.tuneurl.TuneURLMatch
import com.tuneurlradio.app.voice.VoiceCommandManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainState(
    val stations: List<Station> = emptyList(),
    val currentStation: Station? = null,
    val isPlaying: Boolean = false,
    val isOTAListening: Boolean = false,      // OTA (microphone) listening when radio is OFF
    val isStreamParsing: Boolean = false,     // Stream parsing when radio is ON
    val playerState: PlayerState = PlayerState.OFFLINE,
    val expandedPlayer: Boolean = false,
    val trackName: String? = null,
    val artistName: String? = null,
    val volume: Float = 1f,
    val currentMatch: TuneURLMatch? = null,
    val showEngagementSheet: Boolean = false,
    val voiceCommandsEnabled: Boolean = true,
    val sleepTimerEndTime: Long? = null
) {
    // For backward compatibility - isListening now means OTA listening
    val isListening: Boolean get() = isOTAListening
}

sealed interface MainIntent {
    data class SelectStation(val stationId: Int) : MainIntent
    data object TogglePlayback : MainIntent
    data object ToggleOTAListening : MainIntent  // Toggle OTA (microphone) listening
    data object ExpandPlayer : MainIntent
    data object CollapsePlayer : MainIntent
    data class SetVolume(val volume: Float) : MainIntent
    data object DismissEngagement : MainIntent
    data class RecordInterest(val action: String) : MainIntent
    data class SetSleepTimer(val durationSeconds: Int?) : MainIntent
    data class ShowEngagementFromNotification(val match: TuneURLMatch) : MainIntent
}

sealed interface MainEffect {
    data object NavigateToPlayer : MainEffect
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val stationsDataSource: StationsDataSource,
    private val radioPlayerManager: RadioPlayerManager,
    private val tuneURLManager: TuneURLManager,
    private val settingsDataStore: SettingsDataStore,
    val voiceCommandManager: VoiceCommandManager
) : MviViewModel<MainState, MainIntent, MainEffect>(MainState()) {

    private var sleepTimerJob: Job? = null

    init {
        loadStations()
        observePlayerState()
        observeTuneURLState()
        observeVoiceCommandsSetting()
    }

    private fun loadStations() {
        val stations = stationsDataSource.loadStations()
        updateState { copy(stations = stations) }
    }

    private fun observePlayerState() {
        viewModelScope.launch {
            radioPlayerManager.state.collect { playerState ->
                updateState {
                    copy(
                        currentStation = playerState.currentStation ?: currentStation,
                        isPlaying = playerState.isPlaying,
                        playerState = playerState.playerState,
                        trackName = playerState.trackName,
                        artistName = playerState.artistName,
                        volume = playerState.volume
                    )
                }
            }
        }
    }

    private fun observeTuneURLState() {
        viewModelScope.launch {
            tuneURLManager.state.collect { tuneURLState ->
                updateState {
                    copy(
                        isOTAListening = tuneURLState.isListening,
                        isStreamParsing = tuneURLState.isStreamParsing,
                        currentMatch = tuneURLState.currentMatch,
                        showEngagementSheet = tuneURLState.showEngagementSheet
                    )
                }
            }
        }
    }

    private fun observeVoiceCommandsSetting() {
        viewModelScope.launch {
            settingsDataStore.voiceCommands.collect { enabled ->
                updateState { copy(voiceCommandsEnabled = enabled) }
            }
        }
    }

    override fun handleIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.SelectStation -> selectStation(intent.stationId)
            MainIntent.TogglePlayback -> togglePlayback()
            MainIntent.ToggleOTAListening -> toggleOTAListening()
            MainIntent.ExpandPlayer -> expandPlayer()
            MainIntent.CollapsePlayer -> collapsePlayer()
            is MainIntent.SetVolume -> setVolume(intent.volume)
            MainIntent.DismissEngagement -> dismissEngagement()
            is MainIntent.RecordInterest -> recordInterest(intent.action)
            is MainIntent.SetSleepTimer -> setSleepTimer(intent.durationSeconds)
            is MainIntent.ShowEngagementFromNotification -> showEngagementFromNotification(intent.match)
        }
    }

    /**
     * Select and play a station
     * This starts stream parsing automatically (iOS behavior)
     */
    private fun selectStation(stationId: Int) {
        val station = currentState.stations.find { it.id == stationId } ?: return
        
        updateState {
            copy(
                currentStation = station,
                expandedPlayer = true
            )
        }
        
        // Play the station - this will automatically:
        // 1. Stop OTA listening (if active)
        // 2. Start stream parsing
        radioPlayerManager.play(station)
        station.streamURL?.let { streamUrl ->
            tuneURLManager.startStreamParsing(streamUrl, station.id)
        }
        
        sendEffect(MainEffect.NavigateToPlayer)
    }

    /**
     * Toggle radio playback
     * iOS behavior:
     * - When radio plays: OTA listening stops, stream parsing starts
     * - When radio stops: Stream parsing stops, OTA listening starts
     */
    private fun togglePlayback() {
        val station = currentState.currentStation ?: return
        
        if (currentState.isPlaying) {
            // Stop radio - this will start OTA listening automatically
            radioPlayerManager.stop()
            tuneURLManager.stopStreamParsing() // This starts OTA listening
        } else {
            // Play radio - this will stop OTA listening and start stream parsing
            radioPlayerManager.play(station)
            station.streamURL?.let { streamUrl ->
                tuneURLManager.startStreamParsing(streamUrl, station.id)
            }
        }
    }

    /**
     * Toggle OTA (microphone) listening manually
     * iOS behavior: If radio is playing, stop it first, then toggle OTA
     */
    private fun toggleOTAListening() {
        if (currentState.isPlaying) {
            // Stop radio first, then OTA listening will start automatically
            radioPlayerManager.stop()
            tuneURLManager.stopStreamParsing()
        } else if (currentState.isOTAListening) {
            // Stop OTA listening
            tuneURLManager.stopOTAListening()
        } else {
            // Start OTA listening
            tuneURLManager.startOTAListening()
        }
    }

    private fun dismissEngagement() {
        // Clear local state
        updateState { 
            copy(
                showEngagementSheet = false,
                currentMatch = null
            )
        }
        // Also clear TuneURLManager state
        tuneURLManager.dismissEngagement()
    }

    private fun recordInterest(action: String) {
        currentState.currentMatch?.let { match ->
            tuneURLManager.recordInterest(match, action)
        }
        // Clear local state
        updateState { 
            copy(
                showEngagementSheet = false,
                currentMatch = null
            )
        }
        // Also clear TuneURLManager state
        tuneURLManager.dismissEngagement()
    }

    private fun expandPlayer() {
        if (currentState.currentStation != null) {
            updateState { copy(expandedPlayer = true) }
            sendEffect(MainEffect.NavigateToPlayer)
        }
    }

    private fun collapsePlayer() {
        updateState { copy(expandedPlayer = false) }
    }

    /**
     * Show engagement sheet from notification click
     */
    private fun showEngagementFromNotification(match: TuneURLMatch) {
        // Update state directly to show engagement sheet immediately
        // This bypasses TuneURLManager to ensure the sheet shows
        updateState { 
            copy(
                expandedPlayer = true,
                currentMatch = match,
                showEngagementSheet = true
            ) 
        }
    }

    private fun setVolume(volume: Float) {
        radioPlayerManager.setVolume(volume)
    }

    private fun setSleepTimer(durationSeconds: Int?) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        
        if (durationSeconds == null) {
            updateState { copy(sleepTimerEndTime = null) }
            return
        }
        
        val endTime = System.currentTimeMillis() + (durationSeconds * 1000L)
        updateState { copy(sleepTimerEndTime = endTime) }
        
        sleepTimerJob = viewModelScope.launch {
            delay(durationSeconds * 1000L)
            if (currentState.isPlaying) {
                radioPlayerManager.stop()
                tuneURLManager.stopStreamParsing()
            }
            if (currentState.isOTAListening) {
                tuneURLManager.stopOTAListening()
            }
            updateState { copy(sleepTimerEndTime = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        radioPlayerManager.release()
        tuneURLManager.release()
    }
}
