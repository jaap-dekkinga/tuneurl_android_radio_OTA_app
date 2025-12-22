package com.tuneurlradio.app.ui.screens.saved

import androidx.lifecycle.viewModelScope
import com.tuneurlradio.app.core.mvi.MviViewModel
import com.tuneurlradio.app.data.local.StationsDataSource
import com.tuneurlradio.app.data.local.entity.SavedEngagementEntity
import com.tuneurlradio.app.data.repository.EngagementsRepository
import com.tuneurlradio.app.domain.model.Station
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SavedEngagementsState(
    val engagements: List<SavedEngagementEntity> = emptyList(),
    val stations: List<Station> = emptyList()
)

sealed interface SavedEngagementsIntent {
    data class Delete(val engagement: SavedEngagementEntity) : SavedEngagementsIntent
    data class ItemClicked(val engagement: SavedEngagementEntity) : SavedEngagementsIntent
}

sealed interface SavedEngagementsEffect {
    data class OpenUrl(val url: String) : SavedEngagementsEffect
}

@HiltViewModel
class SavedEngagementsViewModel @Inject constructor(
    private val engagementsRepository: EngagementsRepository,
    private val stationsDataSource: StationsDataSource
) : MviViewModel<SavedEngagementsState, SavedEngagementsIntent, SavedEngagementsEffect>(SavedEngagementsState()) {

    init {
        loadStations()
        observeEngagements()
    }

    private fun loadStations() {
        val stations = stationsDataSource.loadStations()
        updateState { copy(stations = stations) }
    }

    private fun observeEngagements() {
        viewModelScope.launch {
            engagementsRepository.getSavedEngagements().collect { engagements ->
                updateState { copy(engagements = engagements) }
            }
        }
    }

    override fun handleIntent(intent: SavedEngagementsIntent) {
        when (intent) {
            is SavedEngagementsIntent.Delete -> deleteEngagement(intent.engagement)
            is SavedEngagementsIntent.ItemClicked -> openEngagement(intent.engagement)
        }
    }

    private fun deleteEngagement(engagement: SavedEngagementEntity) {
        viewModelScope.launch {
            engagementsRepository.deleteSavedEngagement(engagement)
        }
    }

    private fun openEngagement(engagement: SavedEngagementEntity) {
        engagement.info?.let { url ->
            sendEffect(SavedEngagementsEffect.OpenUrl(url))
        }
    }
}
