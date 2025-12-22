package com.tuneurlradio.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tuneurlradio.app.data.local.dao.HistoryEngagementDao
import com.tuneurlradio.app.data.local.dao.SavedEngagementDao
import com.tuneurlradio.app.data.local.entity.HistoryEngagementEntity
import com.tuneurlradio.app.data.local.entity.SavedEngagementEntity

@Database(
    entities = [SavedEngagementEntity::class, HistoryEngagementEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedEngagementDao(): SavedEngagementDao
    abstract fun historyEngagementDao(): HistoryEngagementDao
}
