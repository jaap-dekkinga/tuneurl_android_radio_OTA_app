package com.tuneurlradio.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_engagements")
data class SavedEngagementEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val engagementId: Int,
    val rawType: String,
    val name: String?,
    val description: String?,
    val info: String?,
    val heardAt: Long,
    val sourceStationId: Int?,
    val createdAt: Long = System.currentTimeMillis()
)
