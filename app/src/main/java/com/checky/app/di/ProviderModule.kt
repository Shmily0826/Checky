package com.checky.app.di

import android.content.Context
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.providers.MiyousheProvider
import com.checky.app.domain.providers.MiyousheCommunityProvider
import com.checky.app.domain.providers.TaygedoClient
import com.checky.app.domain.providers.TaygedoCommunityProvider
import com.checky.app.domain.providers.TaygedoNteProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    /** Catalog of every connectable provider. */
    @Provides
    @Singleton
    @JvmSuppressWildcards
    fun provideProviderCatalog(): List<ProviderMeta> = listOf(
        MiyousheProvider.META,
        MiyousheCommunityProvider.META,
        TaygedoNteProvider.META,
        TaygedoCommunityProvider.META
    )

    /** Only connectable providers are registered here. */
    @Provides
    @Singleton
    @JvmSuppressWildcards
    fun provideProviders(
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
        credentialStore: CredentialStore,
        httpClient: okhttp3.OkHttpClient
    ): List<CheckInProvider> {
        val taygedo = TaygedoClient(context, credentialStore, httpClient)
        val miyoushe = MiyousheProvider(context, credentialStore, httpClient)
        return listOf(
            miyoushe,
            MiyousheCommunityProvider(context, credentialStore, httpClient),
            TaygedoNteProvider(taygedo),
            TaygedoCommunityProvider(taygedo)
        )
    }
}
