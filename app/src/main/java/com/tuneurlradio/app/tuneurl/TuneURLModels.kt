package com.tuneurlradio.app.tuneurl

data class TuneURLMatch(
    val id: String,
    val name: String,
    val description: String,
    val info: String,
    val type: String,
    val matchPercentage: Float,
    val time: String?,
    val date: String?
)
