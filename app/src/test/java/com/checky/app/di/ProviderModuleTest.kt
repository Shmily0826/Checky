package com.checky.app.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.data.security.MockCredentialStore
import com.checky.app.domain.CheckInProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderModuleTest {

    @Test
    fun runtimeRegistrationsAuthoritativelyDefineCatalogInOrder() {
        val providers = runtimeProviders()
        val catalog = ProviderModule.provideProviderCatalog(providers)

        assertEquals(6, providers.size)
        assertEquals(6, catalog.size)
        assertEquals(providers.map { it.meta.id }, catalog.map { it.id })
        assertEquals(6, providers.map { it.meta.id }.toSet().size)
        assertEquals(
            listOf(
                "miyoushe_genshin_experimental",
                "miyoushe_community_signin",
                "miyoushe_zzz_experimental",
                "taygedo_nte",
                "taygedo_community",
                "taptap_game_sign"
            ),
            providers.map { it.meta.id }
        )
    }

    private fun runtimeProviders(): List<CheckInProvider> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val credentials = MockCredentialStore()
        val httpClient = OkHttpClient()
        val preferences = UserPreferencesRepository(
            PreferenceDataStoreFactory.create {
                File.createTempFile("checky_provider_module", ".preferences_pb").apply {
                    deleteOnExit()
                }
            }
        )
        return ProviderModule.provideProviders(
            context = context,
            credentialStore = credentials,
            httpClient = httpClient,
            userPreferencesRepository = preferences
        )
    }
}
