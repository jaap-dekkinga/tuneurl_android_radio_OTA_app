package com.tuneurlradio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tuneurlradio.app.data.local.entity.SavedEngagementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedEngagementDao {
    @Query("SELECT * FROM saved_engagements ORDER BY createdAt DESC")
    fun getAll(): Flow<List<SavedEngagementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(engagement: SavedEngagementEntity)

    @Delete
    suspend fun delete(engagement: SavedEngagementEntity)
}
