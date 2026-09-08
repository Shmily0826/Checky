package com.checky.app.debug

import android.content.Context
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.providers.TaygedoClient
import com.checky.app.domain.providers.TaygedoCommunityProvider
import com.checky.app.domain.providers.TaygedoShareFailureEvidence
import com.checky.app.domain.providers.TaygedoShareOutcome
import com.checky.app.domain.providers.runTaygedoShareTask
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

internal class TaygedoDebugShareVerifier @Inject constructor(
    @ApplicationContext context: Context,
    credentials: CredentialStore,
    httpClient: OkHttpClient
) {
    private val client = TaygedoClient(context, credentials, httpClient)

    suspend fun verifyOnce(): TaygedoShareVerification = withContext(Dispatchers.IO) {
        val result = runTaygedoShareTask { request ->
            client.request(
                meta = TaygedoCommunityProvider.META,
                path = request.path,
                method = request.method,
                query = request.query,
                form = request.form,
                useDs = request.useDs,
                authV2 = request.authV2,
                jsonBody = request.jsonBody
            )
        }
        TaygedoShareVerification(
            before = result.before?.let { TaygedoShareCounter(it.complete, it.limit) },
            after = result.after?.let { TaygedoShareCounter(it.complete, it.limit) },
            attempted = result.outcome.mutationWasAttempted(),
            outcome = result.outcome,
            failureEvidence = result.failureEvidence
        )
    }
}

internal data class TaygedoShareCounter(val complete: Int, val limit: Int) {
    val remaining: Int get() = (limit - complete).coerceAtLeast(0)

    fun render() = "complete=$complete limit=$limit remaining=$remaining"
}

internal data class TaygedoShareVerification(
    val before: TaygedoShareCounter?,
    val after: TaygedoShareCounter?,
    val attempted: Boolean,
    val outcome: TaygedoShareOutcome,
    val failureEvidence: TaygedoShareFailureEvidence? = null
) {
    fun render() = "outcome=$outcome attempted=${if (attempted) "yes" else "no"} " +
        "before=${before?.render() ?: "UNKNOWN"} after=${after?.render() ?: "UNKNOWN"}" +
        failureEvidence.renderIfPresent()
}

private fun TaygedoShareFailureEvidence?.renderIfPresent(): String = this?.let {
    " failure=httpStatus=${it.httpStatus} code=${it.businessCode ?: "UNKNOWN"} " +
        "message=${it.message ?: "UNKNOWN"}"
}.orEmpty()

private fun TaygedoShareOutcome.mutationWasAttempted(): Boolean = when (this) {
    TaygedoShareOutcome.COMPLETED,
    TaygedoShareOutcome.STOPPED_MUTATION_FAILURE,
    TaygedoShareOutcome.STOPPED_MUTATION_UNCERTAIN,
    TaygedoShareOutcome.STOPPED_POST_STATE_UNKNOWN,
    TaygedoShareOutcome.STOPPED_POST_STATE_INCOMPLETE -> true
    TaygedoShareOutcome.ALREADY_COMPLETED,
    TaygedoShareOutcome.STOPPED_PRE_STATE_UNKNOWN,
    TaygedoShareOutcome.STOPPED_RECOMMEND_UNKNOWN,
    TaygedoShareOutcome.STOPPED_INCOMPLETE -> false
}

internal fun isTaygedoShareVerificationConsumed(
    state: TaygedoShareVerificationUiState
): Boolean = state !is TaygedoShareVerificationUiState.Idle &&
    state !is TaygedoShareVerificationUiState.Confirming

internal fun claimTaygedoShareVerification(
    state: TaygedoShareVerificationUiState
): TaygedoShareVerificationUiState? =
    state.takeIf { it == TaygedoShareVerificationUiState.Confirming }
        ?.let { TaygedoShareVerificationUiState.Loading }

internal sealed interface TaygedoShareVerificationUiState {
    data object Idle : TaygedoShareVerificationUiState
    data object Confirming : TaygedoShareVerificationUiState
    data object Loading : TaygedoShareVerificationUiState
    data class Completed(val value: TaygedoShareVerification) : TaygedoShareVerificationUiState
    data class Failed(val message: String) : TaygedoShareVerificationUiState
}
