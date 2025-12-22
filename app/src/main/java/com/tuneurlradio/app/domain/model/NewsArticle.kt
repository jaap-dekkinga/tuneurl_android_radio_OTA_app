package com.tuneurlradio.app.domain.model

import java.util.Date
import java.util.UUID

data class NewsArticle(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val summary: String,
    val link: String?,
    val pubDate: Date?,
    val categories: List<String>
)
