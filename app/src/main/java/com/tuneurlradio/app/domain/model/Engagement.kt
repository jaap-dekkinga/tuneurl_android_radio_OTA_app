package com.tuneurlradio.app.domain.model

import java.util.Date

data class Engagement(
    val id: Int,
    val rawType: String,
    val type: EngagementType,
    val name: String?,
    val description: String?,
    val info: String?,
    val heardAt: Date,
    val sourceStationId: Int?
) {
    val isPage: Boolean get() = type == EngagementType.OPEN_PAGE || type == EngagementType.SAVE_PAGE
    val canSave: Boolean get() = isPage || type == EngagementType.COUPON
    val handleURL: String? get() = info
}
