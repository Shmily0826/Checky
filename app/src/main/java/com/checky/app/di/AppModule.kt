package com.checky.app.di

import android.content.Context
import androidx.room.Room
import com.checky.app.data.local.CheckyDao
import com.checky.app.data.local.CheckyDatabase
import com.checky.app.data.network.RedactingLoggingInterceptor
import okhttp3.OkHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): CheckyDatabase = Room.databaseBuilder(
        context,
        CheckyDatabase::class.java,
        CheckyDatabase.DATABASE_NAME
    ).addMigrations(CheckyDatabase.MIGRATION_1_2).build()

    @Provides
    fun provideDao(database: CheckyDatabase): CheckyDao = database.dao()

    @Provides
    @Singleton
    fun provideProviderHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(RedactingLoggingInterceptor(logBody = false))
        .build()
}
