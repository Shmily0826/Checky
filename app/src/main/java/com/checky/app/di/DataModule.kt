package com.checky.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.checky.app.data.preferences.MockScenarioStoreImpl
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.data.repository.CheckInRepositoryImpl
import com.checky.app.data.security.KeystoreCredentialStore
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.MockScenarioStore
import com.checky.app.domain.model.ProviderMeta
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    @Named("userPrefs")
    fun provideUserPrefsDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create {
        // DataStore requires the .preferences_pb suffix for Preferences files.
        File(context.filesDir, "user_prefs.preferences_pb")
    }

    @Provides
    @Singleton
    fun provideUserPreferencesRepository(
        @Named("userPrefs") dataStore: DataStore<Preferences>
    ): UserPreferencesRepository = UserPreferencesRepository(dataStore)

    @Provides
    @Singleton
    fun provideMockScenarioStore(
        @Named("userPrefs") dataStore: DataStore<Preferences>
    ): MockScenarioStore = MockScenarioStoreImpl(dataStore)

    /**
     * Production credential vault. Secrets are encrypted with an Android
     * Keystore-backed AES-256/GCM key and remain on this device.
     */
    @Provides
    @Singleton
    fun provideCredentialStore(
        @ApplicationContext context: Context
    ): CredentialStore = KeystoreCredentialStore(context)

    @Provides
    @Singleton
    @JvmSuppressWildcards
    fun provideCheckInRepository(
        dao: com.checky.app.data.local.CheckyDao,
        metas: List<ProviderMeta>
    ): CheckInRepository = CheckInRepositoryImpl(dao, metas)
}
