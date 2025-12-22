package com.tuneurlradio.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tuneurlradio.app.domain.model.EngagementDisplayMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val OTA_MATCH_THRESHOLD = intPreferencesKey("ota_match_threshold")
        val STREAM_MATCH_THRESHOLD = intPreferencesKey("stream_match_threshold")
        val ENGAGEMENT_DISPLAY_MODE = stringPreferencesKey("engagement_display_mode")
        val STORE_ALL_ENGAGEMENTS_HISTORY = booleanPreferencesKey("store_all_engagements")
        val VOICE_COMMANDS = booleanPreferencesKey("enable_voice_commands")
    }

    val otaMatchThreshold: Flow<Int> = context.dataStore.data.map { it[Keys.OTA_MATCH_THRESHOLD] ?: 10 }
    val streamMatchThreshold: Flow<Int> = context.dataStore.data.map { it[Keys.STREAM_MATCH_THRESHOLD] ?: 10 }
    val engagementDisplayMode: Flow<EngagementDisplayMode> = context.dataStore.data.map {
        EngagementDisplayMode.valueOf(it[Keys.ENGAGEMENT_DISPLAY_MODE] ?: EngagementDisplayMode.MODAL.name)
    }
    val storeAllEngagementsHistory: Flow<Boolean> = context.dataStore.data.map { it[Keys.STORE_ALL_ENGAGEMENTS_HISTORY] ?: false }
    val storeHistory: Flow<Boolean> = storeAllEngagementsHistory
    val voiceCommands: Flow<Boolean> = context.dataStore.data.map { it[Keys.VOICE_COMMANDS] ?: true }

    suspend fun setOtaMatchThreshold(value: Int) {
        context.dataStore.edit { it[Keys.OTA_MATCH_THRESHOLD] = value }
    }

    suspend fun setStreamMatchThreshold(value: Int) {
        context.dataStore.edit { it[Keys.STREAM_MATCH_THRESHOLD] = value }
    }

    suspend fun setEngagementDisplayMode(mode: EngagementDisplayMode) {
        context.dataStore.edit { it[Keys.ENGAGEMENT_DISPLAY_MODE] = mode.name }
    }

    suspend fun setStoreAllEngagementsHistory(value: Boolean) {
        context.dataStore.edit { it[Keys.STORE_ALL_ENGAGEMENTS_HISTORY] = value }
    }

    suspend fun setVoiceCommands(value: Boolean) {
        context.dataStore.edit { it[Keys.VOICE_COMMANDS] = value }
    }
}
