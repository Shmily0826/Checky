package com.checky.app.domain.providers

import android.content.Context
import android.provider.Settings
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.QrLoginPollResult
import com.checky.app.domain.QrLoginProvider
import com.checky.app.domain.QrLoginSession
import com.checky.app.domain.SavedCredentialRevalidator
import com.checky.app.domain.SavedCredentialValidation
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.ConnectionType
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
import java.time.ZoneId
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RiskLevel
import com.checky.app.domain.model.SupportStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/**
 * Personal experiment only: MiYouShe discussion-area check-in.
 *
 * This intentionally submits only the once-daily sign-in request. It does not
 * read posts, like, comment, share, redeem, or complete any other MiYou coin
 * mission.
 */
class MiyousheCommunityProvider(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val httpClient: OkHttpClient
) : CheckInProvider, QrLoginProvider, SavedCredentialRevalidator {

    override val meta: ProviderMeta = META
    override val requiresCredentials: Boolean = true

    override suspend fun validateCredentials(secret: String): CredentialValidation {
        val cookie = secret.trim().let(::decodeCookie)
        val names = cookieNames(cookie)
        val hasStoken = "stoken" in names || "stoken_v2" in names
        val hasLtokenPair = ("ltoken" in names && "ltuid" in names) ||
            ("ltoken_v2" in names && "ltmid_v2" in names)
        return when {
            cookie.length < 40 -> CredentialValidation.Invalid("Cookie 内容太短，未保存。")
            !hasStoken && !hasLtokenPair -> CredentialValidation.Invalid(
                "社区签到需要 stoken 或完整的 ltoken 登录字段。"
            )
            else -> CredentialValidation.Valid
        }
    }

    override fun supportsDisconnect(): Boolean = true

    override suspend fun disconnect() {
        // This provider owns a separate app-authorized community session. Removing
        // it must not affect the independent Genshin game-sign-in session.
        credentialStore.delete(meta.id)
    }

    override suspend fun revalidateSavedCredential(): SavedCredentialValidation = withContext(Dispatchers.IO) {
        val saved = credentialStore.get(meta.id)
        val cookie = saved?.let(::decodeCookie)
        if (cookie.isNullOrBlank()) return@withContext SavedCredentialValidation.Expired

        when (readOnlyPreflight(cookie)) {
            MiyousheCommunityPreflight.AlreadyCompleted,
            MiyousheCommunityPreflight.Incomplete -> SavedCredentialValidation.Valid
            MiyousheCommunityPreflight.VerificationRequired ->
                SavedCredentialValidation.Unverified("米游社要求官方验证。")
            MiyousheCommunityPreflight.Unknown ->
                SavedCredentialValidation.Unverified("米游社社区状态暂时无法确认。")
            MiyousheCommunityPreflight.AuthExpired -> when (val renewed = renewCommunityCookie(cookie)) {
                MiyousheCommunityRenewal.MissingRoot -> SavedCredentialValidation.Expired
                MiyousheCommunityRenewal.Failed ->
                    SavedCredentialValidation.Unverified("米游社社区凭证续期未完成。")
                is MiyousheCommunityRenewal.Complete -> when (readOnlyPreflight(renewed.cookie)) {
                    MiyousheCommunityPreflight.AlreadyCompleted,
                    MiyousheCommunityPreflight.Incomplete -> {
                        if (persistRenewedCookie(renewed.cookie)) {
                            SavedCredentialValidation.Valid
                        } else {
                            SavedCredentialValidation.Unverified("米游社社区凭证续期后保存失败。")
                        }
                    }
                    MiyousheCommunityPreflight.AuthExpired -> SavedCredentialValidation.Expired
                    MiyousheCommunityPreflight.VerificationRequired ->
                        SavedCredentialValidation.Unverified("米游社要求官方验证。")
                    MiyousheCommunityPreflight.Unknown ->
                        SavedCredentialValidation.Unverified("米游社社区状态暂时无法确认。")
                }
            }
        }
    }

    /**
     * Community sign-in needs a higher-privilege session than the normal web
     * login. Use the current Hyperion/passport app QR flow so the confirmed
     * response contains an SToken rather than the legacy Genshin GameToken.
     */
    override suspend fun createQrLoginSession(): QrLoginSession = withContext(Dispatchers.IO) {
        val deviceId = deviceId()
        val response = postQrApi(
            host = QR_HOST,
            path = "/account/ma-cn-passport/app/createQRLogin",
            body = "{}",
            deviceId = deviceId
        )
        val json = JSONObject(response)
        if (json.optInt("retcode", -1) != 0) {
            throw IllegalStateException("米游社社区二维码生成失败。")
        }
        val data = json.optJSONObject("data")
        val url = data?.optString("url").orEmpty()
        val ticket = data?.optString("ticket").orEmpty()
        if (url.isBlank() || ticket.isBlank()) {
            throw IllegalStateException("米游社社区二维码无效。")
        }
        QrLoginSession(qrPayload = url, sessionId = "$deviceId|$ticket")
    }

    override suspend fun pollQrLogin(session: QrLoginSession): QrLoginPollResult = withContext(Dispatchers.IO) {
        val parts = session.sessionId.split('|', limit = 2)
        if (parts.size != 2) return@withContext QrLoginPollResult.Failed("二维码会话无效，请重新生成。")
        val deviceId = parts[0]
        val ticket = parts[1]
        val response = runCatching {
            postQrApi(
                host = QR_HOST,
                path = "/account/ma-cn-passport/app/queryQRLoginStatus",
                body = JSONObject().put("ticket", ticket).toString(),
                deviceId = deviceId
            )
        }.getOrElse {
            return@withContext QrLoginPollResult.Failed("米游社社区二维码状态暂时无法查询，请稍后重试。")
        }

        val parsed = parseMiyousheCommunityQrResponse(response)
        when (parsed) {
            MiyousheCommunityQrParseResult.Waiting -> QrLoginPollResult.Waiting
            MiyousheCommunityQrParseResult.Scanned -> QrLoginPollResult.Scanned
            is MiyousheCommunityQrParseResult.Expired -> QrLoginPollResult.Expired(parsed.message)
            is MiyousheCommunityQrParseResult.Failed -> QrLoginPollResult.Failed(parsed.message)
            is MiyousheCommunityQrParseResult.Confirmed -> {
                when (val built = buildCommunityCookie(
                    parsed.token,
                    parsed.mid,
                    parsed.accountId
                )) {
                    MiyousheCommunityCookieBuildResult.EnrichmentIncomplete ->
                        QrLoginPollResult.Failed("扫码已确认，但未获得完整的社区凭证，请重试。")
                    is MiyousheCommunityCookieBuildResult.Complete -> {
                        val cookie = built.cookie
                        when (validateCredentials(cookie)) {
                            is CredentialValidation.Valid -> {
                                runCatching { credentialStore.save(meta.id, cookie) }
                                    .fold(
                                        onSuccess = { QrLoginPollResult.Confirmed(parsed.accountId) },
                                        onFailure = { QrLoginPollResult.Failed("扫码已确认，但社区凭证保存失败，请重试。") }
                                    )
                            }
                            is CredentialValidation.Invalid ->
                                QrLoginPollResult.Failed("扫码已确认，但未获得可用于签到的社区凭证，请重试。")
                        }
                    }
                }
            }
        }
    }

    override fun checkIn() = flow {
        emit(CheckInEvent.Progress(0.15f, "读取米游社会话…"))
        val saved = credentialStore.get(meta.id)
        val cookie = saved?.let(::decodeCookie)
        if (cookie.isNullOrBlank()) {
            emit(CheckInEvent.Done(result(CheckInOutcome.AuthenticationExpired(
                "请先连接米游社账号。",
                "MIYOUSHE_COMMUNITY_AUTH_REQUIRED"
            ))))
            return@flow
        }

        emit(CheckInEvent.Progress(0.45f, "提交米游社讨论区签到…"))
        val outcome = withContext(Dispatchers.IO) { runCheckInWithRecovery(cookie) }
        emit(CheckInEvent.Done(result(outcome)))
    }

    private suspend fun runCheckInWithRecovery(cookie: String): CheckInOutcome {
        return when (readOnlyPreflight(cookie)) {
            MiyousheCommunityPreflight.AlreadyCompleted ->
                CheckInOutcome.AlreadyCompleted(
                    "米游社讨论区今天已经签到。",
                    "MIYOUSHE_COMMUNITY_ALREADY"
                )
            MiyousheCommunityPreflight.Incomplete -> runCheckIn(cookie)
            MiyousheCommunityPreflight.VerificationRequired ->
                CheckInOutcome.ActionRequired(
                    "米游社要求官方验证，请在官方 App 处理后再试。",
                    "MIYOUSHE_COMMUNITY_VERIFICATION"
                )
            MiyousheCommunityPreflight.AuthExpired -> when (val renewed = renewCommunityCookie(cookie)) {
                MiyousheCommunityRenewal.MissingRoot -> CheckInOutcome.AuthenticationExpired(
                    "米游社会话已失效，请重新扫码连接。",
                    "MIYOUSHE_COMMUNITY_AUTH_EXPIRED"
                )
                MiyousheCommunityRenewal.Failed -> CheckInOutcome.TemporaryFailure(
                    "米游社社区凭证续期未完成，请稍后重试。",
                    "MIYOUSHE_COMMUNITY_RENEWAL_FAILED"
                )
                is MiyousheCommunityRenewal.Complete -> when (readOnlyPreflight(renewed.cookie)) {
                    MiyousheCommunityPreflight.AlreadyCompleted -> saveRenewedCookieOrFail(renewed.cookie) {
                        CheckInOutcome.AlreadyCompleted(
                            "米游社讨论区今天已经签到。",
                            "MIYOUSHE_COMMUNITY_ALREADY"
                        )
                    }
                    MiyousheCommunityPreflight.Incomplete -> saveRenewedCookieOrFail(renewed.cookie) {
                        runCheckIn(renewed.cookie)
                    }
                    MiyousheCommunityPreflight.VerificationRequired ->
                        CheckInOutcome.ActionRequired(
                            "米游社要求官方验证，请在官方 App 处理后再试。",
                            "MIYOUSHE_COMMUNITY_VERIFICATION"
                        )
                    MiyousheCommunityPreflight.AuthExpired -> CheckInOutcome.AuthenticationExpired(
                        "米游社会话已失效，请重新扫码连接。",
                        "MIYOUSHE_COMMUNITY_AUTH_EXPIRED"
                    )
                    MiyousheCommunityPreflight.Unknown -> communityPreflightUnknown()
                }
            }
            MiyousheCommunityPreflight.Unknown -> communityPreflightUnknown()
        }
    }

    private suspend fun saveRenewedCookieOrFail(
        cookie: String,
        next: () -> CheckInOutcome
    ): CheckInOutcome {
        return try {
            if (!persistRenewedCookie(cookie)) {
                CheckInOutcome.TemporaryFailure(
                    "米游社社区凭证续期后保存失败，请稍后重试。",
                    "MIYOUSHE_COMMUNITY_RENEWAL_SAVE_FAILED"
                )
            } else {
                next()
            }
        } catch (_: Exception) {
            CheckInOutcome.TemporaryFailure(
                "米游社社区凭证续期后保存失败，请稍后重试。",
                "MIYOUSHE_COMMUNITY_RENEWAL_SAVE_FAILED"
            )
        }
    }

    private suspend fun persistRenewedCookie(cookie: String): Boolean {
        return try {
            credentialStore.save(meta.id, cookie)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun communityPreflightUnknown() = CheckInOutcome.TemporaryFailure(
        "米游社社区状态暂时无法确认，请稍后重试。",
        "MIYOUSHE_COMMUNITY_PREFLIGHT_UNKNOWN"
    )

    private fun readOnlyPreflight(cookie: String): MiyousheCommunityPreflight {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(API_HOST)
            .addPathSegments("apihub/wapi/getUserMissionsState")
            .addQueryParameter("point_sn", "myb")
            .build()
        val request = communityRequestBuilder(url, cookie)
            .header("DS", generateDataDs(""))
            .get()
            .build()
        val result = runCatching {
            check(META.allowedHosts.contains(url.host)) { "Provider host is not allowlisted" }
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) MiyousheCommunityPreflight.Unknown
                else classifyMiyousheCommunityPreflight(response.body?.string().orEmpty())
            }
        }.getOrDefault(MiyousheCommunityPreflight.Unknown)
        return result
    }

    private fun renewCommunityCookie(cookie: String): MiyousheCommunityRenewal {
        val values = cookiePairs(cookie)
        val stoken = values["stoken"].orEmpty().ifBlank { values["stoken_v2"].orEmpty() }
        val mid = values["mid"].orEmpty()
        val bbsUid = values["stuid"].orEmpty()
            .ifBlank { values["account_id"].orEmpty() }
            .ifBlank { values["account_id_v2"].orEmpty() }
        if (stoken.isBlank() || mid.isBlank() || bbsUid.isBlank()) {
            return MiyousheCommunityRenewal.MissingRoot
        }
        return when (val built = buildCommunityCookie(stoken, mid, bbsUid)) {
            is MiyousheCommunityCookieBuildResult.Complete ->
                MiyousheCommunityRenewal.Complete(built.cookie)
            MiyousheCommunityCookieBuildResult.EnrichmentIncomplete ->
                MiyousheCommunityRenewal.Failed
        }
    }

    private fun runCheckIn(cookie: String): CheckInOutcome = try {
        val body = JSONObject().put("gids", GENSHIN_GID).toString()
        runMiyousheCommunityMutation { request(cookie, body) }
    } catch (_: Exception) {
        // Keep unexpected local failures terminal as well; no subsequent
        // mutation is safe after this provider has started the sign-in flow.
        miyousheCommunityUncertainMutationOutcome()
    }

    private fun request(cookie: String, body: String): String {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(API_HOST)
            .addPathSegments("apihub/app/api/signIn")
            .build()
        val request = communityRequestBuilder(url, cookie)
            .header("DS", generateDataDs(body))
            .header("Content-Type", "application/json; charset=UTF-8")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        check(META.allowedHosts.contains(url.host)) { "Provider host is not allowlisted" }
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP_${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun communityRequestBuilder(
        url: okhttp3.HttpUrl,
        cookie: String
    ): Request.Builder {
        val deviceId = deviceId()
        return Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", COMMUNITY_USER_AGENT)
            .header("x-rpc-app_version", APP_VERSION)
            .header("x-rpc-channel", "miyousheluodi")
            .header("x-rpc-client_type", "2")
            .header("x-rpc-sys_version", "12")
            .header("x-rpc-device_id", deviceId)
            .header("x-rpc-device_name", "Android")
            .header("x-rpc-device_model", "Android")
            .header("x-rpc-verify_key", PASSPORT_APP_ID)
            .header("x-rpc-csm_source", "discussion")
            .header("x-rpc-h265_supported", "1")
            .header("Referer", "https://app.mihoyo.com")
            .header("Connection", "Keep-Alive")
            .header("Accept-Encoding", "gzip")
    }

    private fun postQrApi(host: String, path: String, body: String, deviceId: String): String {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .addPathSegments(path.removePrefix("/"))
            .build()
        check(META.allowedHosts.contains(url.host)) { "Provider host is not allowlisted" }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", QR_USER_AGENT)
            .header("x-rpc-app_id", PASSPORT_APP_ID)
            .header("x-rpc-client_type", "3")
            .header("x-rpc-device_id", deviceId)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP_${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun buildCommunityCookie(
        stoken: String,
        mid: String,
        bbsUid: String
    ): MiyousheCommunityCookieBuildResult {
        val baseCookie = mergeCookies(
            "stoken=$stoken; stoken_v2=$stoken; mid=$mid; stuid=$bbsUid; account_id=$bbsUid; account_id_v2=$bbsUid",
            emptyList()
        )
        val cookieTokenFields = runCatching { fetchCookieToken(stoken, mid, bbsUid) }
            .getOrElse { return MiyousheCommunityCookieBuildResult.EnrichmentIncomplete }
        val withCookieToken = mergeCookies(baseCookie, cookieTokenFields)
        val lTokenFields = runCatching { fetchLToken(stoken, mid, bbsUid) }
            .getOrElse { return MiyousheCommunityCookieBuildResult.EnrichmentIncomplete }
        return classifyMiyousheCommunityCookie(
            mergeCookies(withCookieToken, lTokenFields)
        )
    }

    private fun fetchCookieToken(stoken: String, mid: String, bbsUid: String): List<String> {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(PASSPORT_HOST)
            .addPathSegments("account/auth/api/getCookieAccountInfoBySToken")
            .build()
        check(META.allowedHosts.contains(url.host)) { "Provider host is not allowlisted" }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "mid=$mid; stoken=$stoken; stuid=$bbsUid")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.optInt("retcode", Int.MIN_VALUE) != 0) return emptyList()
            val data = json.optJSONObject("data") ?: return emptyList()
            val cookieToken = data.optString("cookie_token")
            if (cookieToken.isBlank()) emptyList() else listOf(
                "cookie_token=$cookieToken",
                "cookie_token_v2=$cookieToken"
            )
        }
    }

    private fun fetchLToken(stoken: String, mid: String, bbsUid: String): List<String> {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(PASSPORT_HOST)
            .addPathSegments("account/auth/api/getLTokenBySToken")
            .build()
        check(META.allowedHosts.contains(url.host)) { "Provider host is not allowlisted" }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "mid=$mid; stoken=$stoken; stuid=$bbsUid")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.optInt("retcode", Int.MIN_VALUE) != 0) return emptyList()
            val ltoken = json.optJSONObject("data")?.optString("ltoken").orEmpty()
            if (ltoken.isBlank()) emptyList() else listOf(
                "ltoken=$ltoken",
                "ltoken_v2=$ltoken",
                "ltuid=$bbsUid",
                "ltmid_v2=$mid"
            )
        }
    }

    private fun generateDataDs(body: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val random = Random.nextInt(100000, 200001)
        val input = "salt=$DATA_DS_SALT&t=$timestamp&r=$random&b=$body&q="
        val digest = MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$timestamp,$random,$digest"
    }

    private fun decodeCookie(value: String): String =
        runCatching { JSONObject(value).optString("cookie").ifBlank { value } }.getOrDefault(value)

    private fun cookieNames(cookie: String): Set<String> = cookie.split(';')
        .map { it.substringBefore('=').trim() }
        .filter { it.isNotBlank() }
        .toSet()

    private fun cookiePairs(cookie: String): Map<String, String> = cookie.split(';')
        .mapNotNull { item ->
            val name = item.substringBefore('=').trim()
            val value = item.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank()) null else name to value
        }
        .toMap()

    private fun mergeCookies(cookie: String, extra: List<String>): String {
        val merged = linkedMapOf<String, String>()
        cookie.split(';').forEach { item ->
            val name = item.substringBefore('=').trim()
            val value = item.substringAfter('=', "").trim()
            if (name.isNotBlank() && value.isNotBlank()) merged[name] = value
        }
        extra.forEach { item ->
            val name = item.substringBefore('=').trim()
            val value = item.substringAfter('=', "").trim()
            if (name.isNotBlank() && value.isNotBlank()) merged[name] = value
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun deviceId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
        return UUID.nameUUIDFromBytes((androidId.ifBlank { UUID.randomUUID().toString() }).toByteArray()).toString()
    }

    private fun result(outcome: CheckInOutcome) =
        CheckInResult(meta.id, meta.displayName, outcome, System.currentTimeMillis())

    companion object {
        private const val API_HOST = "bbs-api.miyoushe.com"
        private const val QR_HOST = "passport-api.mihoyo.com"
        private const val PASSPORT_HOST = "passport-api.mihoyo.com"
        private const val APP_VERSION = "2.109.0"
        private const val QR_USER_AGENT = "HYPContainer/1.3.3.182"
        private const val COMMUNITY_USER_AGENT = "okhttp/4.9.3"
        private const val PASSPORT_APP_ID = "bll8iq97cem8"
        private val QR_EXPIRED_RETCODES = setOf(-106, -3501, -3505)
        private const val DATA_DS_SALT = "t0qEgfub6cvueAPgR5m9aQWWVciEer7v"
        private const val GENSHIN_GID = 2
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Checky Android Build/UP1A.231005.007) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 " +
                "Mobile Safari/537.36 miHoYoBBS/2.36.1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val META = ProviderMeta(
            id = "miyoushe_community_signin",
            displayName = "米游社讨论区签到",
            description = "非官方接口，仅限本人账号测试；每天只做一次讨论区打卡，不点赞、不评论、不阅读、不兑换。",
            category = "米游社社区",
            iconKey = "star",
            accentColor = 0xFFEF7D32,
            isEnabledByDefault = false,
            connectionType = ConnectionType.HTTP_SESSION,
            riskLevel = RiskLevel.HIGH,
            credentialType = CredentialType.SESSION_TOKEN,
            supportStatus = SupportStatus.SUPPORTED,
            allowedHosts = setOf(API_HOST, PASSPORT_HOST),
            businessZone = ZoneId.of("Asia/Shanghai")
        )
    }
}

private enum class MiyousheCommunityPreflight {
    AlreadyCompleted,
    Incomplete,
    AuthExpired,
    VerificationRequired,
    Unknown
}

private sealed interface MiyousheCommunityRenewal {
    data class Complete(val cookie: String) : MiyousheCommunityRenewal
    data object MissingRoot : MiyousheCommunityRenewal
    data object Failed : MiyousheCommunityRenewal
}

private fun classifyMiyousheCommunityPreflight(body: String): MiyousheCommunityPreflight {
    val json = runCatching { JSONObject(body) }.getOrNull()
        ?: return MiyousheCommunityPreflight.Unknown
    val retcode = json.optionalInteger("retcode")
        ?: return MiyousheCommunityPreflight.Unknown
    val message = json.optString("message")
    if (retcode == -100 || retcode == -101 || retcode == -10001) {
        return MiyousheCommunityPreflight.AuthExpired
    }
    if (retcode == 1034 || message.contains("captcha", ignoreCase = true) ||
        message.contains("verification", ignoreCase = true) ||
        message.contains("geetest", ignoreCase = true) ||
        message.contains("risk", ignoreCase = true)
    ) {
        return MiyousheCommunityPreflight.VerificationRequired
    }
    if (retcode != 0) return MiyousheCommunityPreflight.Unknown

    val states = json.optJSONObject("data")?.optJSONArray("states")
        ?: return MiyousheCommunityPreflight.Unknown
    val matching = (0 until states.length()).mapNotNull { index ->
        states.optJSONObject(index)?.takeIf { it.optString("mission_key") == "continuous_sign" }
    }
    if (matching.size > 1) return MiyousheCommunityPreflight.Unknown
    if (matching.isEmpty()) return MiyousheCommunityPreflight.Incomplete
    return when (val happenedTimes = matching.single().optionalInteger("happened_times")) {
        null -> MiyousheCommunityPreflight.Unknown
        0 -> MiyousheCommunityPreflight.Incomplete
        in 1..Int.MAX_VALUE -> MiyousheCommunityPreflight.AlreadyCompleted
        else -> MiyousheCommunityPreflight.Unknown
    }
}

internal sealed interface MiyousheCommunityCookieBuildResult {
    data class Complete(val cookie: String) : MiyousheCommunityCookieBuildResult
    data object EnrichmentIncomplete : MiyousheCommunityCookieBuildResult
}

/** App safety gate for the complete live-verified session shape; not an external API contract. */
internal fun classifyMiyousheCommunityCookie(cookie: String): MiyousheCommunityCookieBuildResult {
    val names = cookie.split(';')
        .map { it.substringBefore('=').trim() }
        .filter { it.isNotBlank() }
        .toSet()
    return if (LIVE_VERIFIED_COMMUNITY_COOKIE_FIELDS.all(names::contains)) {
        MiyousheCommunityCookieBuildResult.Complete(cookie)
    } else {
        MiyousheCommunityCookieBuildResult.EnrichmentIncomplete
    }
}

internal sealed interface MiyousheCommunityQrParseResult {
    data object Waiting : MiyousheCommunityQrParseResult
    data object Scanned : MiyousheCommunityQrParseResult
    data class Confirmed(
        val token: String,
        val mid: String,
        val accountId: String
    ) : MiyousheCommunityQrParseResult
    data class Expired(val message: String = "二维码已过期，请重新生成二维码。") : MiyousheCommunityQrParseResult
    data class Failed(val message: String) : MiyousheCommunityQrParseResult
}

private val LIVE_VERIFIED_COMMUNITY_COOKIE_FIELDS = setOf(
    "stoken",
    "stoken_v2",
    "mid",
    "stuid",
    "account_id",
    "account_id_v2",
    "cookie_token_v2",
    "ltoken",
    "ltoken_v2",
    "ltuid",
    "ltmid_v2"
)

/** Parses only the narrow pre-existing community QR schema contract; unknown variants fail closed. */
internal fun parseMiyousheCommunityQrResponse(body: String): MiyousheCommunityQrParseResult {
    val json = runCatching { JSONObject(body) }.getOrNull()
        ?: return MiyousheCommunityQrParseResult.Failed("米游社返回了无法识别的二维码状态，请重新生成。")
    if (!json.has("retcode")) {
        return MiyousheCommunityQrParseResult.Failed("米游社返回了无法识别的二维码状态，请重新生成。")
    }
    val retcode = json.optInt("retcode", Int.MIN_VALUE)
    if (retcode in setOf(-106, -3501, -3505)) {
        return MiyousheCommunityQrParseResult.Expired()
    }
    if (retcode != 0) {
        return MiyousheCommunityQrParseResult.Failed("米游社拒绝了社区二维码请求，请重新生成。")
    }
    val data = json.optJSONObject("data")
        ?: return MiyousheCommunityQrParseResult.Failed("米游社返回了无法识别的二维码状态，请重新生成。")
    return when (data.optString("status").ifBlank { data.optString("stat") }) {
        "Init", "Created" -> MiyousheCommunityQrParseResult.Waiting
        "Scanned" -> MiyousheCommunityQrParseResult.Scanned
        "Confirmed" -> {
            val token = data.optJSONArray("tokens")
                ?.optJSONObject(0)
                ?.optString("token")
                .orEmpty()
            val userInfo = data.optJSONObject("user_info")
            val mid = userInfo?.optString("mid").orEmpty()
            val accountId = userInfo?.optString("aid").orEmpty()
            if (token.isBlank() || mid.isBlank() || accountId.isBlank()) {
                MiyousheCommunityQrParseResult.Failed("扫码已确认，但未获得可用于签到的社区凭证，请重试。")
            } else {
                MiyousheCommunityQrParseResult.Confirmed(token, mid, accountId)
            }
        }
        else -> MiyousheCommunityQrParseResult.Failed("米游社返回了未知的二维码状态，请重新生成。")
    }
}

internal fun mapMiyousheCommunityResponse(body: String): CheckInOutcome {
    val json = runCatching { JSONObject(body) }.getOrNull()
        ?: return miyousheCommunityUncertainMutationOutcome()
    val retcode = json.optionalInteger("retcode")
    val message = json.optString("message")
    val data = json.optJSONObject("data")
    return mapMiyousheCommunityFields(
        retcode = retcode,
        message = message,
        points = data?.optionalInteger("points")
    )
}

private fun JSONObject.optionalInteger(name: String): Int? {
    if (!has(name)) return null
    val number = opt(name) as? Number ?: return null
    val value = number.toDouble()
    return if (value.isFinite() && value % 1.0 == 0.0 &&
        value >= Int.MIN_VALUE && value <= Int.MAX_VALUE
    ) {
        value.toInt()
    } else {
        null
    }
}

/** A transport failure or malformed mutation response leaves its outcome unknown. */
internal fun miyousheCommunityUncertainMutationOutcome(): CheckInOutcome =
    CheckInOutcome.PermanentFailure(
        "米游社签到请求可能已发送，但结果无法确认，已停止自动重试。",
        "MIYOUSHE_COMMUNITY_UNCERTAIN"
    )

/** Maps the result of the single community mutation without permitting retry. */
internal fun runMiyousheCommunityMutation(request: () -> String): CheckInOutcome = try {
    mapMiyousheCommunityResponse(request())
} catch (_: Exception) {
    // The POST may have reached the server before the client observed the
    // transport failure. There is no verified read-only preflight here, so
    // do not let CheckInAllUseCase blindly send the same mutation again.
    miyousheCommunityUncertainMutationOutcome()
}

internal fun mapMiyousheCommunityFields(
    retcode: Int?,
    message: String,
    points: Int?
): CheckInOutcome {
    if (retcode == null) {
        return miyousheCommunityUncertainMutationOutcome()
    }
    return when {
        retcode == 0 -> {
            if (points == null) {
                miyousheCommunityUncertainMutationOutcome()
            } else {
                if (points > 0) {
                    CheckInOutcome.Success(
                        "米游社讨论区签到成功，米游币 +$points。",
                        "MIYOUSHE_COMMUNITY_SUCCESS",
                        Reward(com.checky.app.domain.model.RewardType.POINTS, points)
                    )
                } else {
                    CheckInOutcome.AlreadyCompleted(
                        "米游社讨论区今天已经签到。",
                        "MIYOUSHE_COMMUNITY_ALREADY"
                    )
                }
            }
        }
        retcode == 1008 -> CheckInOutcome.AlreadyCompleted(
            "米游社讨论区今天已经签到。",
            "MIYOUSHE_COMMUNITY_ALREADY_1008"
        )
        retcode == -100 || retcode == -101 || retcode == -10001 ->
            CheckInOutcome.AuthenticationExpired(
                "米游社会话已失效，请重新扫码连接。",
                "MIYOUSHE_COMMUNITY_AUTH_EXPIRED"
            )
        retcode == 1034 || message.contains("验证") || message.contains("风险") ||
            message.contains("captcha", ignoreCase = true) ->
            CheckInOutcome.ActionRequired(
                "米游社要求官方验证，请在官方 App 处理后再试。",
                "MIYOUSHE_COMMUNITY_VERIFICATION"
            )
        else -> CheckInOutcome.TemporaryFailure(
            "米游社讨论区签到失败（错误码 $retcode），请稍后重试。",
            "MIYOUSHE_COMMUNITY_REJECTED_$retcode"
        )
    }
}
