package com.tuneurlradio.app.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tuneurlradio.app.data.local.AppDatabase
import com.tuneurlradio.app.data.local.dao.HistoryEngagementDao
import com.tuneurlradio.app.data.local.dao.SavedEngagementDao
import com.tuneurlradio.app.data.remote.RssFeedParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRssFeedParser(): RssFeedParser = RssFeedParser()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "tuneurlradio.db"
        ).build()
    }

    @Provides
    fun provideSavedEngagementDao(database: AppDatabase): SavedEngagementDao {
        return database.savedEngagementDao()
    }

    @Provides
    fun provideHistoryEngagementDao(database: AppDatabase): HistoryEngagementDao {
        return database.historyEngagementDao()
    }
}
