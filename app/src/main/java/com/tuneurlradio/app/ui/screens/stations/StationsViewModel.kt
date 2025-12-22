package com.tuneurlradio.app.ui.screens.stations

import com.tuneurlradio.app.core.mvi.MviViewModel
import com.tuneurlradio.app.data.local.StationsDataSource
import com.tuneurlradio.app.domain.model.Station
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class StationsState(
    val stations: List<Station> = emptyList()
)

sealed interface StationsIntent {
    data class StationClicked(val station: Station) : StationsIntent
}

sealed interface StationsEffect {
    data class NavigateToPlayer(val station: Station) : StationsEffect
}

@HiltViewModel
class StationsViewModel @Inject constructor(
    private val stationsDataSource: StationsDataSource
) : MviViewModel<StationsState, StationsIntent, StationsEffect>(StationsState()) {

    init {
        loadStations()
    }

    private fun loadStations() {
        val stations = stationsDataSource.loadStations()
        updateState { copy(stations = stations) }
    }

    override fun handleIntent(intent: StationsIntent) {
        when (intent) {
            is StationsIntent.StationClicked -> {
                sendEffect(StationsEffect.NavigateToPlayer(intent.station))
            }
        }
    }
}
