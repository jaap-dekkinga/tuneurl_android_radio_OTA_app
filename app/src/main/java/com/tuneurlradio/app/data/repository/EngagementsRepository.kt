package com.tuneurlradio.app.data.repository

import com.tuneurlradio.app.data.local.dao.HistoryEngagementDao
import com.tuneurlradio.app.data.local.dao.SavedEngagementDao
import com.tuneurlradio.app.data.local.entity.HistoryEngagementEntity
import com.tuneurlradio.app.data.local.entity.SavedEngagementEntity
import com.tuneurlradio.app.domain.model.Engagement
import com.tuneurlradio.app.domain.model.EngagementType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngagementsRepository @Inject constructor(
    private val savedEngagementDao: SavedEngagementDao,
    private val historyEngagementDao: HistoryEngagementDao
) {
    fun getSavedEngagements(): Flow<List<SavedEngagementEntity>> {
        return savedEngagementDao.getAll()
    }

    fun getHistoryEngagements(): Flow<List<HistoryEngagementEntity>> {
        return historyEngagementDao.getAll()
    }

    suspend fun saveEngagement(engagement: Engagement) {
        val entity = SavedEngagementEntity(
            engagementId = engagement.id,
            rawType = engagement.rawType,
            name = engagement.name,
            description = engagement.description,
            info = engagement.info,
            heardAt = engagement.heardAt.time,
            sourceStationId = engagement.sourceStationId
        )
        savedEngagementDao.insert(entity)
    }

    suspend fun saveToHistory(engagement: Engagement) {
        val entity = HistoryEngagementEntity(
            engagementId = engagement.id,
            rawType = engagement.rawType,
            name = engagement.name,
            description = engagement.description,
            info = engagement.info,
            heardAt = engagement.heardAt.time,
            sourceStationId = engagement.sourceStationId
        )
        historyEngagementDao.insert(entity)
    }

    suspend fun deleteSavedEngagement(entity: SavedEngagementEntity) {
        savedEngagementDao.delete(entity)
    }

    suspend fun deleteHistoryEngagement(entity: HistoryEngagementEntity) {
        historyEngagementDao.delete(entity)
    }

    suspend fun clearHistory() {
        historyEngagementDao.clearAll()
    }
}

fun SavedEngagementEntity.toEngagement(): Engagement {
    return Engagement(
        id = engagementId,
        rawType = rawType,
        type = EngagementType.fromString(rawType),
        name = name,
        description = description,
        info = info,
        heardAt = Date(heardAt),
        sourceStationId = sourceStationId
    )
}

fun HistoryEngagementEntity.toEngagement(): Engagement {
    return Engagement(
        id = engagementId,
        rawType = rawType,
        type = EngagementType.fromString(rawType),
        name = name,
        description = description,
        info = info,
        heardAt = Date(heardAt),
        sourceStationId = sourceStationId
    )
}
