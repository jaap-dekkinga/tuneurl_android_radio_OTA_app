package com.tuneurlradio.app.domain.model

enum class EngagementType(val value: String) {
    UNKNOWN("unknown"),
    OPEN_PAGE("open_page"),
    SAVE_PAGE("save_page"),
    PHONE("phone"),
    SMS("sms"),
    COUPON("coupon"),
    POLL("poll"),
    API_CALL("api_call");

    companion object {
        fun fromString(value: String): EngagementType {
            return entries.find { it.value == value.lowercase() } ?: UNKNOWN
        }
    }
}
