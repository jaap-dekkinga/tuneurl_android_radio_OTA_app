package com.tuneurlradio.app.ui.screens.turls

import androidx.lifecycle.viewModelScope
import com.tuneurlradio.app.core.mvi.MviViewModel
import com.tuneurlradio.app.data.local.StationsDataSource
import com.tuneurlradio.app.data.local.entity.HistoryEngagementEntity
import com.tuneurlradio.app.data.repository.EngagementsRepository
import com.tuneurlradio.app.domain.model.Station
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TurlsHistoryState(
    val engagements: List<HistoryEngagementEntity> = emptyList(),
    val stations: List<Station> = emptyList()
)

sealed interface TurlsHistoryIntent {
    data class Delete(val engagement: HistoryEngagementEntity) : TurlsHistoryIntent
    data object ClearAll : TurlsHistoryIntent
}

/**
 * No effects today. The previous `OpenUrl` effect was removed: row taps now
 * navigate to [com.tuneurlradio.app.ui.screens.engagement.EngagementDetailScreen]
 * via the navigation graph rather than being dispatched as an effect from the
 * VM. Kept as a sealed interface for future-proofing.
 */
sealed interface TurlsHistoryEffect

@HiltViewModel
class TurlsHistoryViewModel @Inject constructor(
    private val engagementsRepository: EngagementsRepository,
    private val stationsDataSource: StationsDataSource
) : MviViewModel<TurlsHistoryState, TurlsHistoryIntent, TurlsHistoryEffect>(
    TurlsHistoryState()
) {

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
            engagementsRepository.getHistoryEngagements().collect { engagements ->
                updateState { copy(engagements = engagements) }
            }
        }
    }

    override fun handleIntent(intent: TurlsHistoryIntent) {
        when (intent) {
            is TurlsHistoryIntent.Delete -> deleteEngagement(intent.engagement)
            TurlsHistoryIntent.ClearAll -> clearAll()
        }
    }

    private fun deleteEngagement(engagement: HistoryEngagementEntity) {
        viewModelScope.launch {
            engagementsRepository.deleteHistoryEngagement(engagement)
        }
    }

    private fun clearAll() {
        viewModelScope.launch {
            engagementsRepository.clearHistory()
        }
    }
}
