package com.checky.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.checky.app.R
import com.checky.app.domain.model.ProviderMeta

/**
 * Keeps provider identity in the domain model while resolving user-facing
 * metadata through Android resources. Unknown/future providers retain their
 * provider-supplied metadata until they add localized labels here.
 */
@Composable
fun localizedProviderName(meta: ProviderMeta): String = when (meta.id) {
    "miyoushe_genshin_experimental" -> stringResource(R.string.provider_miyoushe_genshin_name)
    "miyoushe_community_signin" -> stringResource(R.string.provider_miyoushe_community_name)
    "miyoushe_zzz_experimental" -> stringResource(R.string.provider_miyoushe_zzz_name)
    "taygedo_nte" -> stringResource(R.string.provider_taygedo_nte_name)
    "taygedo_community" -> stringResource(R.string.provider_taygedo_community_name)
    else -> meta.displayName
}

@Composable
fun localizedProviderDescription(meta: ProviderMeta): String = when (meta.id) {
    "miyoushe_genshin_experimental" -> stringResource(R.string.provider_miyoushe_genshin_description)
    "miyoushe_community_signin" -> stringResource(R.string.provider_miyoushe_community_description)
    "miyoushe_zzz_experimental" -> stringResource(R.string.provider_miyoushe_zzz_description)
    "taygedo_nte" -> stringResource(R.string.provider_taygedo_nte_description)
    "taygedo_community" -> stringResource(R.string.provider_taygedo_community_description)
    else -> meta.description
}

@Composable
fun localizedProviderCategory(meta: ProviderMeta): String = when (meta.id) {
    "miyoushe_genshin_experimental" -> stringResource(R.string.provider_miyoushe_genshin_category)
    "miyoushe_community_signin" -> stringResource(R.string.provider_miyoushe_community_category)
    "miyoushe_zzz_experimental" -> stringResource(R.string.provider_miyoushe_zzz_category)
    "taygedo_nte" -> stringResource(R.string.provider_taygedo_nte_category)
    "taygedo_community" -> stringResource(R.string.provider_taygedo_community_category)
    else -> meta.category
}
