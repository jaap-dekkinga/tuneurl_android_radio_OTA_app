package com.tuneurlradio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tuneurlradio.app.data.local.entity.HistoryEngagementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryEngagementDao {
    @Query("SELECT * FROM history_engagements ORDER BY createdAt DESC")
    fun getAll(): Flow<List<HistoryEngagementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(engagement: HistoryEngagementEntity)

    @Delete
    suspend fun delete(engagement: HistoryEngagementEntity)

    @Query("DELETE FROM history_engagements")
    suspend fun clearAll()
}
