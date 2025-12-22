package com.tuneurlradio.app.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tuneurlradio.app.domain.model.Station
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StationsDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    fun loadStations(): List<Station> {
        return try {
            val json = context.assets.open("stations.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<Station>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
