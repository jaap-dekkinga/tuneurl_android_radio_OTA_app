package com.tuneurlradio.app.domain.model

data class Station(
    val id: Int,
    val name: String,
    val streamURL: String,
    val imageURL: String?,
    val shortDescription: String,
    val socialURL: String?
)
