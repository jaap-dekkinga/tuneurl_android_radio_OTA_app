package com.tuneurlradio.app.ui.screens.settings

import androidx.lifecycle.viewModelScope
import com.tuneurlradio.app.core.mvi.MviViewModel
import com.tuneurlradio.app.data.local.SettingsDataStore
import com.tuneurlradio.app.domain.model.EngagementDisplayMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val engagementDisplayMode: EngagementDisplayMode = EngagementDisplayMode.MODAL,
    val storeAllEngagementsHistory: Boolean = false,
    val voiceCommands: Boolean = true,
    val otaMatchThreshold: Int = 10,
    val streamMatchThreshold: Int = 10
)

sealed interface SettingsIntent {
    data class SetEngagementDisplayMode(val mode: EngagementDisplayMode) : SettingsIntent
    data class SetStoreAllEngagementsHistory(val value: Boolean) : SettingsIntent
    data class SetVoiceCommands(val value: Boolean) : SettingsIntent
    data class SetOtaMatchThreshold(val value: Int) : SettingsIntent
    data class SetStreamMatchThreshold(val value: Int) : SettingsIntent
    data object OpenWebsite : SettingsIntent
    data object OpenPrivacyPolicy : SettingsIntent
}

sealed interface SettingsEffect {
    data class OpenUrl(val url: String) : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : MviViewModel<SettingsState, SettingsIntent, SettingsEffect>(SettingsState()) {

    init {
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.engagementDisplayMode,
                settingsDataStore.storeAllEngagementsHistory,
                settingsDataStore.voiceCommands,
                settingsDataStore.otaMatchThreshold,
                settingsDataStore.streamMatchThreshold
            ) { displayMode, storeHistory, voiceCommands, otaThreshold, streamThreshold ->
                SettingsState(
                    engagementDisplayMode = displayMode,
                    storeAllEngagementsHistory = storeHistory,
                    voiceCommands = voiceCommands,
                    otaMatchThreshold = otaThreshold,
                    streamMatchThreshold = streamThreshold
                )
            }.collect { state ->
                updateState { state }
            }
        }
    }

    override fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetEngagementDisplayMode -> setEngagementDisplayMode(intent.mode)
            is SettingsIntent.SetStoreAllEngagementsHistory -> setStoreAllEngagementsHistory(intent.value)
            is SettingsIntent.SetVoiceCommands -> setVoiceCommands(intent.value)
            is SettingsIntent.SetOtaMatchThreshold -> setOtaMatchThreshold(intent.value)
            is SettingsIntent.SetStreamMatchThreshold -> setStreamMatchThreshold(intent.value)
            SettingsIntent.OpenWebsite -> sendEffect(SettingsEffect.OpenUrl("https://www.tuneurl.com/"))
            SettingsIntent.OpenPrivacyPolicy -> sendEffect(SettingsEffect.OpenUrl("https://www.tuneurl.com/privacy-policy"))
        }
    }

    private fun setEngagementDisplayMode(mode: EngagementDisplayMode) {
        viewModelScope.launch {
            settingsDataStore.setEngagementDisplayMode(mode)
        }
    }

    private fun setStoreAllEngagementsHistory(value: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setStoreAllEngagementsHistory(value)
        }
    }

    private fun setVoiceCommands(value: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setVoiceCommands(value)
        }
    }

    private fun setOtaMatchThreshold(value: Int) {
        viewModelScope.launch {
            settingsDataStore.setOtaMatchThreshold(value)
        }
    }

    private fun setStreamMatchThreshold(value: Int) {
        viewModelScope.launch {
            settingsDataStore.setStreamMatchThreshold(value)
        }
    }
}
