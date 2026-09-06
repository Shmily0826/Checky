package com.checky.app.debug

import android.content.Context
import android.provider.Settings
import com.checky.app.data.network.HostPolicy
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.AuthHealthStore
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.providers.TaygedoClient
import com.checky.app.domain.providers.TaygedoCoinStateResult
import com.checky.app.domain.providers.TaygedoCommunityProvider
import com.checky.app.domain.providers.TaygedoNteProvider
import com.checky.app.domain.providers.MiyousheCommunityProvider
import com.checky.app.domain.providers.MiyousheProvider
import com.checky.app.domain.providers.nteRewardIndexForSignedDays
import com.checky.app.domain.providers.parseCommunityTaskState
import com.checky.app.domain.providers.parseNteSignState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.io.File
import javax.inject.Inject

/**
 * Temporary debug-only Taygedo reader.
 *
 * This intentionally does not call TaygedoClient.request(): that method can
 * refresh a session after a 401. Every request here is a GET, uses the current
 * Checky endpoint contracts, and fails immediately on auth/error responses.
 */
internal class TaygedoDebugReadClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: CredentialStore,
    private val authHealth: AuthHealthStore
) {
    private val readHttp = OkHttpClient.Builder().apply {
        followRedirects(false)
        followSslRedirects(false)
    }.build()
    private val productionClient = TaygedoClient(context, credentials, readHttp)

    suspend fun load(): TaygedoDiagnostics = withContext(Dispatchers.IO) {
        val audit = DiagnosticsAudit(readCredentialContinuity())
        try {
            writeAudit(audit, null)
            loadInternal(audit).also {
                writeAudit(audit, null)
            }
        } catch (failure: DebugReadFailure) {
            val report = audit.render(failure.message)
            writeAudit(report)
            throw DebugReadFailure(failure.message ?: "Read-only diagnostics failed.", report)
        } catch (_: Exception) {
            val report = audit.render("Read-only diagnostics failed; no mutation was attempted.")
            writeAudit(report)
            throw DebugReadFailure("Read-only diagnostics failed; no mutation was attempted.", report)
        }
    }

    /** Read only Taygedo's local continuity metadata; no provider request is made. */
    suspend fun loadTaygedoLocalState(): CredentialContinuityDiagnostic =
        withContext(Dispatchers.IO) {
            readCredentialContinuityEntry("taygedo_shared", TaygedoClient.SESSION_KEY)
        }

    /** Perform exactly one authenticated, read-only coin-state GET. */
    suspend fun loadOneShotCoinState(): CoinStateDiagnostic = withContext(Dispatchers.IO) {
        when (val result = productionClient.getUserCoinTaskState()) {
            is TaygedoCoinStateResult.Known -> CoinStateDiagnostic.Known(
                todayCoin = result.state.todayCoin,
                limitCoin = result.state.limitCoin
            )
            TaygedoCoinStateResult.Unknown -> CoinStateDiagnostic.Unknown
        }
    }

    private suspend fun loadInternal(audit: DiagnosticsAudit): TaygedoDiagnostics {
        val session = loadSession()

        val community = get(
            session = session,
            meta = TaygedoCommunityProvider.META,
            path = "/apihub/api/getUserTasks",
            query = mapOf("communityId" to "2", "gid" to "2"),
            useDs = true,
            authV2 = true
        )
        audit.observations += community.observation
        requireSuccess("community task state", community)
        val taskList = (community.data as? JSONObject)?.optJSONArray("task_list3")
        val tasks = parseCommunityTasks(taskList, community.observation)
        val browseRemaining = parseCommunityTaskState(taskList)?.browseRemaining
            ?: fail("Community task state could not be parsed safely.", community.observation)
        val coinState = when (val result = productionClient.getUserCoinTaskState()) {
            is TaygedoCoinStateResult.Known -> CoinStateDiagnostic.Known(
                todayCoin = result.state.todayCoin,
                limitCoin = result.state.limitCoin
            )
            TaygedoCoinStateResult.Unknown -> CoinStateDiagnostic.Unknown
        }

        val home = get(
            session = session,
            meta = TaygedoNteProvider.META,
            path = "/apihub/awapi/yh/roleHome",
            useDs = true
        )
        audit.observations += home.observation
        requireSuccess("NTE role state", home)
        val roleId = (home.data as? JSONObject)?.optString("roleid").orEmpty()
        if (roleId.isBlank()) fail("NTE role state is missing the required role binding field.", home.observation)

        val signState = get(
            session = session,
            meta = TaygedoNteProvider.META,
            path = "/apihub/awapi/signin/state",
            query = mapOf("gameId" to "1289"),
            webHeaders = true
        )
        audit.observations += signState.observation
        requireSuccess("NTE sign state", signState)
        val state = parseNteSignState(signState.data)
            ?: fail("NTE sign state is missing expected fields todaySign, days, day.", signState.observation)

        val rewards = get(
            session = session,
            meta = TaygedoNteProvider.META,
            path = "/apihub/awapi/sign/rewards",
            query = mapOf("gameId" to "1289", "roleId" to roleId),
            webHeaders = true
        )
        audit.observations += rewards.observation
        requireSuccess("NTE sign rewards", rewards)
        val rewardItems = parseNteRewards(rewards.data, rewards.observation)
        if (nteRewardIndexForSignedDays(state.days) !in rewardItems.indices) {
            fail("NTE sign state does not map to a known reward item.", rewards.observation)
        }

        val diagnostics = TaygedoDiagnostics(
            communityTasks = tasks,
            browseRemaining = browseRemaining,
            coinState = coinState,
            nte = NteDiagnostics(
                todaySigned = state.todaySigned,
                days = state.days,
                day = state.day,
                rewardItems = rewardItems
            ),
            credentialContinuity = audit.credentials,
            observations = audit.observations,
            diagnosticsFile = diagnosticsFile().absolutePath
        )
        return diagnostics
    }

    private fun writeAudit(audit: DiagnosticsAudit, error: String?) {
        writeAudit(audit.render(error))
    }

    private fun writeAudit(report: String) {
        diagnosticsFile().writeText(report)
    }

    private suspend fun readCredentialContinuity(): List<CredentialContinuityDiagnostic> =
        KNOWN_CREDENTIALS.map { (label, ownerId) ->
            readCredentialContinuityEntry(label, ownerId)
        }

    private suspend fun readCredentialContinuityEntry(
        label: String,
        ownerId: String
    ): CredentialContinuityDiagnostic {
        val exists = runCatching { credentials.has(ownerId) }.getOrDefault(false)
        val decryptable = if (exists) runCatching {
            credentials.get(ownerId)?.let { true } ?: false
        }.getOrDefault(false) else false
        return CredentialContinuityDiagnostic(
            label = label,
            entryExists = exists,
            decryptable = decryptable,
            authHealth = runCatching { authHealth.get(ownerId) }.getOrDefault(AuthHealth.UNVERIFIED),
            hasAuthHealthRecord = runCatching { authHealth.hasRecord(ownerId) }.getOrDefault(false)
        )
    }

    private suspend fun loadSession(): ReadSession {
        val raw = credentials.get(TaygedoClient.SESSION_KEY)
            ?: fail("No Taygedo session is available.")
        val json = runCatching { JSONObject(raw) }
            .getOrElse { fail("The Taygedo session is malformed.") }
        val accessToken = json.optString("accessToken")
        val uid = json.optString("uid")
        if (accessToken.isBlank() || uid.isBlank()) {
            fail("The Taygedo session is incomplete.")
        }
        return ReadSession(accessToken, uid)
    }

    private fun get(
        session: ReadSession,
        meta: ProviderMeta,
        path: String,
        query: Map<String, String> = emptyMap(),
        webHeaders: Boolean = false,
        useDs: Boolean = false,
        authV2: Boolean = false
    ): ReadResult {
        if (path !in ALLOWED_READ_PATHS) {
            fail("The temporary diagnostics reader rejected an unapproved path.")
        }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(BBS_HOST)
            .addPathSegments(path.removePrefix("/"))
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        check(HostPolicy.isAllowed(meta, url.toString()))

        val builder = Request.Builder()
            .url(url)
            .get()
            .header(if (authV2) "AuthorizationV2" else "Authorization", session.accessToken)
            .header("uid", session.uid)
            .header("deviceid", deviceId)
            .header("appversion", APP_VERSION)
            .header("platform", "android")
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json, text/plain, */*")
        if (useDs) builder.header("ds", ds())
        if (webHeaders) {
            builder.header("Origin", "https://webstatic.tajiduo.com")
                .header("Referer", "https://webstatic.tajiduo.com/")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
                        "AppleWebKit/605.1.15 Mobile/15E148 Tajiduo/1.2.2"
                )
        }

        return try {
            readHttp.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                val code = if (response.code == 401) 401 else json.optInt("code", response.code)
                ReadResult(
                    code = code,
                    data = json.opt("data"),
                    observation = observeTaygedoSchema(path, response.code, code, json)
                )
            }
        } catch (_: Exception) {
            fail("The read-only $path request could not be completed.")
        }
    }

    private fun requireSuccess(label: String, result: ReadResult) {
        if (result.code == 401 || result.code == -401) {
            fail("Taygedo authentication expired during $label; no refresh was attempted.", result.observation)
        }
        if (result.code != 0) {
            fail("The read-only $label request failed; no mutation was attempted.", result.observation)
        }
    }

    private fun parseCommunityTasks(
        taskList: JSONArray?, observation: SafeSchemaObservation
    ): List<CommunityTaskDiagnostic> {
        if (taskList == null) fail("Community task state is missing expected field task_list3.", observation)
        val result = mutableListOf<CommunityTaskDiagnostic>()
        for (index in 0 until taskList.length()) {
            val task = taskList.optJSONObject(index) ?: continue
            val code = task.optString("taskKey").ifBlank { task.optString("code") }
            if (code !in COMMUNITY_TASK_CODES) continue
            if (result.any { it.code == code }) fail("Community task state contained a duplicate task.")
            val name = task.optString("title").ifBlank { task.optString("name") }
            val complete = requiredNonNegativeInt(task, "completeTimes")
            val limit = requiredNonNegativeInt(task, "limitTimes")
            if (name.isBlank() || complete > limit) {
                fail("Community task state contained an invalid task shape.", observation)
            }
            result += CommunityTaskDiagnostic(
                code = code,
                name = safeText(name),
                complete = complete,
                limit = limit,
                rewardFields = parseRewardFields(task)
            )
        }
        if (result.isEmpty()) fail("Community task state contained no known task fields.", observation)
        return result
    }

    private fun parseNteRewards(data: Any?, observation: SafeSchemaObservation): List<NteRewardDiagnostic> {
        val array = data as? JSONArray
            ?: fail("NTE sign rewards are missing the expected array.", observation)
        if (array.length() == 0) fail("NTE sign rewards array was empty.", observation)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: fail("NTE sign rewards contained an invalid item shape.", observation)
                val name = item.optString("name")
                val count = requiredNonNegativeInt(item, "num")
                if (name.isBlank()) fail("NTE sign rewards contained an unnamed item.", observation)
                add(NteRewardDiagnostic(safeText(name), count))
            }
        }
    }

    private fun parseRewardFields(task: JSONObject): List<SafeRewardField> = buildList {
        val keys = task.keys().asSequence()
            .filter { key -> isSafeRewardKey(key) && isRewardRelatedKey(key) }
            .take(MAX_REWARD_FIELDS)
            .toList()
        for (key in keys) {
            val rendered = renderSafeRewardValue(task.opt(key)) ?: continue
            add(SafeRewardField(key, rendered))
        }
    }

    private fun renderSafeRewardValue(value: Any?): String? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }?.toString()
        is Boolean -> value.toString()
        is String -> safeText(value).takeIf { it.isNotBlank() }
        is JSONObject -> value.keys().asSequence()
            .filter(::isSafeRewardKey)
            .take(MAX_NESTED_REWARD_FIELDS)
            .mapNotNull { key ->
                renderSafeScalar(value.opt(key))?.let { "$key=$it" }
            }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "{", postfix = "}")
        else -> null
    }

    private fun renderSafeScalar(value: Any?): String? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }?.toString()
        is Boolean -> value.toString()
        is String -> safeText(value).takeIf { it.isNotBlank() }
        else -> null
    }

    private fun isRewardRelatedKey(key: String): Boolean {
        val lower = key.lowercase()
        return REWARD_KEY_MARKERS.any(lower::contains)
    }

    private fun isSafeRewardKey(key: String): Boolean {
        val lower = key.lowercase()
        return key.isNotBlank() && key.length <= MAX_DISPLAY_TEXT &&
            key.none(Char::isISOControl) &&
            SENSITIVE_KEY_MARKERS.none(lower::contains)
    }

    private fun requiredNonNegativeInt(json: JSONObject, name: String): Int {
        val value = json.opt(name) as? Number ?: fail("A required numeric field was malformed.")
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 || number < 0 || number > Int.MAX_VALUE) {
            fail("A required numeric field was malformed.")
        }
        return number.toInt()
    }

    private fun safeText(value: String): String = value
        .filterNot { it.isISOControl() }
        .trim()
        .take(MAX_DISPLAY_TEXT)

    private fun ds(): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val random = buildString {
            repeat(8) { append(DS_ALPHABET[SecureRandom().nextInt(DS_ALPHABET.length)]) }
        }
        return "$timestamp,$random,${md5(timestamp + random + APP_VERSION + DS_SALT)}"
    }

    private fun md5(value: String) = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fail(message: String, observation: SafeSchemaObservation? = null): Nothing =
        throw DebugReadFailure(message + (observation?.let { "\nSafe shape: ${it.render()}" } ?: ""))

    private fun diagnosticsFile(): File {
        val debugDirectory = File(context.cacheDir, DEBUG_DIRECTORY_NAME)
        check(debugDirectory.exists() || debugDirectory.mkdirs()) {
            "Temporary diagnostics directory is unavailable."
        }
        return File(debugDirectory, DIAGNOSTICS_FILE_NAME)
    }

    private val deviceId: String = UUID.nameUUIDFromBytes(
        (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()).toByteArray()
    ).toString().replace("-", "")

    private companion object {
        const val BBS_HOST = "bbs-api.tajiduo.com"
        const val APP_VERSION = "1.2.6"
        const val DS_SALT = "pUds3dfMkl"
        const val MAX_DISPLAY_TEXT = 80
        const val MAX_REWARD_FIELDS = 8
        const val MAX_NESTED_REWARD_FIELDS = 8
        const val DS_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val REWARD_KEY_MARKERS = setOf("reward", "exp", "coin", "gold", "point")
        val SENSITIVE_KEY_MARKERS = setOf(
            "token", "cookie", "auth", "header", "secret", "session", "uid", "user",
            "account", "role", "device", "identity", "password", "credential", "id"
        )
        val ALLOWED_READ_PATHS = setOf(
            "/apihub/api/getUserTasks",
            "/apihub/awapi/yh/roleHome",
            "/apihub/awapi/signin/state",
            "/apihub/awapi/sign/rewards"
        )
        const val DEBUG_DIRECTORY_NAME = "debug"
        const val DIAGNOSTICS_FILE_NAME = "taygedo-diagnostics.txt"
        val KNOWN_CREDENTIALS = listOf(
            "miyoushe_genshin" to MiyousheProvider.META.id,
            "miyoushe_community" to MiyousheCommunityProvider.META.id,
            "taygedo_shared" to TaygedoClient.SESSION_KEY
        )
        val COMMUNITY_TASK_CODES = setOf("browse_post_c", "browse_post_exp", "like_post_c", "share")
    }

    private data class DiagnosticsAudit(
        val credentials: List<CredentialContinuityDiagnostic>,
        val observations: MutableList<SafeSchemaObservation> = mutableListOf()
    ) {
        fun render(error: String?): String = buildString {
            appendLine("Taygedo read-only diagnostics")
            credentials.forEach { appendLine("credential ${it.render()}") }
            observations.forEach { appendLine("schema ${it.render()}") }
            if (!error.isNullOrBlank()) appendLine("failure ${error.lineSequence().first()}")
        }
    }
}

internal class DebugReadFailure(message: String, val safeReport: String = "") : Exception(message)

private data class ReadSession(val accessToken: String, val uid: String)
private data class ReadResult(
    val code: Int,
    val data: Any?,
    val observation: SafeSchemaObservation
)

internal data class TaygedoDiagnostics(
    val communityTasks: List<CommunityTaskDiagnostic>,
    val browseRemaining: Int,
    val coinState: CoinStateDiagnostic,
    val nte: NteDiagnostics,
    val credentialContinuity: List<CredentialContinuityDiagnostic>,
    val observations: List<SafeSchemaObservation>,
    val diagnosticsFile: String
)

internal sealed interface CoinStateDiagnostic {
    data class Known(val todayCoin: Int, val limitCoin: Int) : CoinStateDiagnostic
    data object Unknown : CoinStateDiagnostic
}

internal data class CommunityTaskDiagnostic(
    val code: String,
    val name: String,
    val complete: Int,
    val limit: Int,
    val rewardFields: List<SafeRewardField>
)

internal data class SafeRewardField(val name: String, val value: String)

internal data class NteDiagnostics(
    val todaySigned: Boolean,
    val days: Int,
    val day: Int,
    val rewardItems: List<NteRewardDiagnostic>
)

internal data class NteRewardDiagnostic(val name: String, val count: Int)
