package com.checky.app.di

import android.content.Context
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.MockScenarioStore
import com.checky.app.domain.model.ConnectionType
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.RiskLevel
import com.checky.app.domain.model.SupportStatus
import com.checky.app.domain.providers.CloudBoxProvider
import com.checky.app.domain.providers.GamePassDailyProvider
import com.checky.app.domain.providers.StudyClubProvider
import com.checky.app.domain.providers.MiyousheProvider
import com.checky.app.domain.providers.MiyousheCommunityProvider
import com.checky.app.domain.providers.TaygedoClient
import com.checky.app.domain.providers.TaygedoCommunityProvider
import com.checky.app.domain.providers.TaygedoNteProvider
import okhttp3.OkHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    /** Catalog of every known provider, including high-risk "not supported yet" ones. */
    @Provides
    @Singleton
    @JvmSuppressWildcards
    fun provideProviderCatalog(): List<ProviderMeta> = listOf(
        GamePassDailyProvider.META,
        CloudBoxProvider.META,
        StudyClubProvider.META,
        MiyousheProvider.META,
        MiyousheCommunityProvider.META,
        TaygedoNteProvider.META,
        TaygedoCommunityProvider.META,
        ProviderMeta(
            id = "videoshelf",
            displayName = "VideoShelf Rewards",
            description = "Automates daily video-watch rewards. Requires UI automation, which Checky does not support.",
            category = "Video",
            iconKey = "movie",
            accentColor = 0xFFD9480F,
            isEnabledByDefault = false,
            connectionType = ConnectionType.UI_ASSISTED,
            riskLevel = RiskLevel.HIGH,
            credentialType = CredentialType.NONE,
            supportStatus = SupportStatus.NOT_SUPPORTED_YET
        )
    )

    /** Only connectable providers are registered here. */
    @Provides
    @Singleton
    @JvmSuppressWildcards
    fun provideProviders(
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
        credentialStore: CredentialStore,
        scenarioStore: MockScenarioStore,
        httpClient: OkHttpClient
    ): List<CheckInProvider> {
        val taygedo = TaygedoClient(context, credentialStore, httpClient)
        val miyoushe = MiyousheProvider(context, credentialStore, httpClient)
        return listOf(
            GamePassDailyProvider(scenarioStore),
            CloudBoxProvider(credentialStore, scenarioStore),
            StudyClubProvider(scenarioStore),
            miyoushe,
            MiyousheCommunityProvider(context, credentialStore, httpClient),
            TaygedoNteProvider(taygedo),
            TaygedoCommunityProvider(taygedo)
        )
    }
}
