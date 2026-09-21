package com.checky.app.domain.providers

import android.content.Context
import android.provider.Settings
import com.checky.app.data.network.HostPolicy
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.GameAccountConfig
import com.checky.app.domain.GameAccountConfigProvider
import com.checky.app.domain.GameRole
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
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RiskLevel
import com.checky.app.domain.model.SupportStatus
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

/** CN ZZZ daily check-in through the non-public MiYouShe HTTP surface. */
class MiyousheZzzProvider(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val httpClient: OkHttpClient
) : CheckInProvider, QrLoginProvider, GameAccountConfigProvider, SavedCredentialRevalidator {

    override val meta: ProviderMeta = META
    override val requiresCredentials: Boolean = true

    override suspend fun validateCredentials(secret: String): CredentialValidation {
        val cookie = decodeSession(secret.trim()).cookie
        val names = cookieNames(cookie)
        val hasCookieToken = "cookie_token" in names || "cookie_token_v2" in names
        val hasAccountId = names.any { it in ACCOUNT_ID_COOKIE_NAMES }
        val hasLtokenPair = ("ltoken" in names && "ltuid" in names) ||
            ("ltoken_v2" in names && "ltmid_v2" in names)
        return when {
            cookie.length < 40 -> CredentialValidation.Invalid("Cookie is too short to save.")
            !hasLtokenPair && !(hasCookieToken && hasAccountId) ->
                CredentialValidation.Invalid("Cookie is missing recognizable login fields.")
            else -> CredentialValidation.Valid
        }
    }

    override fun supportsDisconnect(): Boolean = true

    override suspend fun disconnect() {
        credentialStore.delete(meta.id)
    }

    override suspend fun gameAccountConfig(): GameAccountConfig? =
        credentialStore.get(meta.id)?.let(::decodeSession)?.takeIf {
            it.uid.isNotBlank() && it.region.isNotBlank()
        }?.let { GameAccountConfig(it.uid, it.region) }

    override suspend fun revalidateSavedCredential(): SavedCredentialValidation = withContext(Dispatchers.IO) {
        val saved = credentialStore.get(meta.id)
            ?: return@withContext SavedCredentialValidation.Unverified("No saved HoYoverse session.")
        val session = decodeSession(saved)
        val names = cookieNames(session.cookie)
        val hasLtokenPair = ("ltoken" in names && "ltuid" in names) ||
            ("ltoken_v2" in names && "ltmid_v2" in names)
        if (!hasLtokenPair) {
            return@withContext SavedCredentialValidation.Unverified("Saved session lacks a readable LToken pair.")
        }
        if (!session.uid.matches(UID_PATTERN) || session.region.isBlank()) {
            return@withContext SavedCredentialValidation.Unverified("Saved session lacks a usable ZZZ UID or region.")
        }
        runCatching {
            request(
                ZZZ_HOST,
                "GET",
                "/event/luna/zzz/info",
                infoQuery(session),
                session.cookie
            )
        }.fold(
            onSuccess = ::mapMiyousheZzzCheckInReadOnlyResponse,
            onFailure = { SavedCredentialValidation.Unverified("HoYoverse connection could not be confirmed.") }
        )
    }

    override suspend fun fetchGameRoles(): List<GameRole> = withContext(Dispatchers.IO) {
            val cookie = credentialStore.get(meta.id)?.let(::decodeSession)?.cookie.orEmpty()
            check(cookie.isNotBlank()) { "HoYoverse session is unavailable." }
            val body = request(
                API_HOST,
                "GET",
                "/binding/api/getUserGameRolesByCookie",
                mapOf("game_biz" to ZZZ_GAME_BIZ),
                cookie,
                includeRoleDs = true
            )
            val json = JSONObject(body)
            check(json.optInt("retcode", Int.MIN_VALUE) == 0) { "HoYoverse role lookup failed." }
            val list = json.optJSONObject("data")?.optJSONArray("list")
                ?: error("HoYoverse role response is missing a role list.")
            buildList {
                for (index in 0 until list.length()) {
                    val role = list.optJSONObject(index) ?: continue
                    if (role.optString("game_biz") != ZZZ_GAME_BIZ) continue
                    val uid = role.optString("game_uid")
                    val region = role.optString("region")
                    if (!uid.matches(UID_PATTERN) || region.isBlank()) continue
                    val regionName = role.optString("region_name").ifBlank { region }
                    this += GameRole(
                        config = GameAccountConfig(uid, region),
                        label = "$regionName · ${role.optString("nickname")} Lv.${role.optInt("level")}"
                    )
                }
            }
    }

    override suspend fun saveGameAccountConfig(uid: String, region: String): CredentialValidation {
        val normalizedUid = uid.trim()
        val normalizedRegion = region.trim()
        if (!normalizedUid.matches(UID_PATTERN)) {
            return CredentialValidation.Invalid("Enter an 8–10 digit ZZZ game UID.")
        }
        if (normalizedRegion.isBlank()) {
            return CredentialValidation.Invalid("Enter the ZZZ region returned by HoYoverse.")
        }
        val existing = credentialStore.get(meta.id)
            ?: return CredentialValidation.Invalid("Complete HoYoverse QR connection first.")
        val session = decodeSession(existing)
        when (val validation = validateCredentials(session.cookie)) {
            is CredentialValidation.Invalid -> return validation
            is CredentialValidation.Valid -> Unit
        }
        credentialStore.save(meta.id, encodeSession(session.copy(uid = normalizedUid, region = normalizedRegion)))
        return CredentialValidation.Valid
    }

    override suspend fun createQrLoginSession(): QrLoginSession = withContext(Dispatchers.IO) {
        val response = postJson(
            WEB_QR_HOST,
            "/account/ma-cn-passport/web/createQRLogin",
            "{}",
            deviceId
        )
        val json = JSONObject(response.body)
        if (json.optInt("retcode", -1) != 0) {
            throw IllegalStateException(json.optString("message", "HoYoverse QR generation failed."))
        }
        val data = json.optJSONObject("data")
        val qrUrl = data?.optString("url").orEmpty()
        val ticket = data?.optString("ticket").orEmpty()
        if (qrUrl.isBlank() || ticket.isBlank()) throw IllegalStateException("HoYoverse returned an invalid QR code.")
        QrLoginSession(qrUrl, "$deviceId|$ticket")
    }

    override suspend fun pollQrLogin(session: QrLoginSession): QrLoginPollResult = withContext(Dispatchers.IO) {
        val parts = session.sessionId.split('|', limit = 2)
        if (parts.size != 2) return@withContext QrLoginPollResult.Failed("Invalid QR session; generate a new one.")
        val response = runCatching {
            postJson(
                WEB_QR_HOST,
                "/account/ma-cn-passport/web/queryQRLoginStatus",
                JSONObject().put("ticket", parts[1]).toString(),
                parts[0]
            )
        }.getOrElse {
            return@withContext QrLoginPollResult.Failed("HoYoverse QR status could not be checked.")
        }
        val json = JSONObject(response.body)
        when (json.optInt("retcode", 0)) {
            -3501 -> return@withContext QrLoginPollResult.Expired()
            -3505 -> return@withContext QrLoginPollResult.Expired("QR login was cancelled; generate a new one.")
        }
        if (json.optInt("retcode", 0) != 0) {
            return@withContext QrLoginPollResult.Failed(json.optString("message", "HoYoverse rejected QR login."))
        }
        val data = json.optJSONObject("data") ?:
            return@withContext QrLoginPollResult.Failed("HoYoverse returned an unknown QR state.")
        when (data.optString("status")) {
            "Created" -> QrLoginPollResult.Waiting
            "Scanned" -> QrLoginPollResult.Scanned
            "Confirmed" -> {
                val cookie = response.setCookies.toCookieHeader()
                val accountId = data.optJSONObject("user_info")?.optString("aid").orEmpty()
                val normalizedCookie = if (
                    accountId.isNotBlank() && !cookie.contains("account_id=") && !cookie.contains("ltuid")
                ) "$cookie; account_id=$accountId" else cookie
                when (val validation = validateCredentials(normalizedCookie)) {
                    is CredentialValidation.Valid -> {
                        credentialStore.save(meta.id, encodeSession(Session(normalizedCookie)))
                        QrLoginPollResult.Confirmed(accountId.ifBlank { null })
                    }
                    is CredentialValidation.Invalid -> QrLoginPollResult.Failed(
                        "QR login succeeded but did not return a usable check-in cookie: ${validation.reason}"
                    )
                }
            }
            else -> QrLoginPollResult.Failed("HoYoverse returned an unknown QR state.")
        }
    }

    override fun checkIn() = flow {
        emit(CheckInEvent.Progress(0.15f, "Reading HoYoverse session…"))
        val saved = credentialStore.get(meta.id)
        if (saved.isNullOrBlank()) {
            emit(CheckInEvent.Done(result(authExpired())))
            return@flow
        }
        val session = decodeSession(saved)
        if (session.uid.isBlank() || session.region.isBlank()) {
            emit(CheckInEvent.Done(result(CheckInOutcome.ActionRequired(
                "Enter the ZZZ UID and region on the connection screen.",
                "MIYOUSHE_ZZZ_ROLE_REQUIRED"
            ))))
            return@flow
        }
        emit(CheckInEvent.Progress(0.35f, "Checking today’s ZZZ check-in status…"))
        emit(CheckInEvent.Done(result(withContext(Dispatchers.IO) { runCheckIn(session) })))
    }

    private fun runCheckIn(session: Session): CheckInOutcome = try {
        val info = request(ZZZ_HOST, "GET", "/event/luna/zzz/info", infoQuery(session), session.cookie)
        when (val infoOutcome = mapMiyousheZzzResponse(info, checkingOnly = true)) {
            is CheckInOutcome.AlreadyCompleted,
            is CheckInOutcome.AuthenticationExpired,
            is CheckInOutcome.ActionRequired,
            is CheckInOutcome.TemporaryFailure,
            is CheckInOutcome.PermanentFailure -> infoOutcome
            is CheckInOutcome.Success -> mapMiyousheZzzResponse(
                request(
                    ZZZ_HOST,
                    "POST",
                    "/event/luna/zzz/sign",
                    emptyMap(),
                    session.cookie,
                    JSONObject().apply {
                        put("act_id", ZZZ_ACT_ID)
                        put("region", session.region)
                        put("uid", session.uid)
                    }.toString()
                ),
                checkingOnly = false
            )
            is CheckInOutcome.Unsupported -> infoOutcome
        }
    } catch (error: HttpStatusException) {
        CheckInOutcome.TemporaryFailure(
            "HoYoverse returned HTTP ${error.statusCode}.",
            "MIYOUSHE_ZZZ_HTTP_${error.statusCode}"
        )
    } catch (_: Exception) {
        CheckInOutcome.TemporaryFailure("HoYoverse is temporarily unavailable; try again later.", "MIYOUSHE_ZZZ_NETWORK")
    }

    private fun mapMiyousheZzzCheckInReadOnlyResponse(body: String): SavedCredentialValidation =
        when (mapMiyousheZzzResponse(body, checkingOnly = true)) {
            is CheckInOutcome.Success,
            is CheckInOutcome.AlreadyCompleted -> SavedCredentialValidation.Valid
            is CheckInOutcome.AuthenticationExpired -> SavedCredentialValidation.Expired
            else -> SavedCredentialValidation.Unverified("HoYoverse did not return a confirmed ZZZ check-in state.")
        }

    private fun request(
        host: String,
        method: String,
        path: String,
        query: Map<String, String>,
        cookie: String,
        body: String? = null,
        includeRoleDs: Boolean = false
    ): String {
        val builder = okhttp3.HttpUrl.Builder().scheme("https").host(host)
            .addPathSegments(path.removePrefix("/"))
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val url = builder.build()
        check(HostPolicy.isAllowed(META, url.toString())) { "Provider host is not allowlisted" }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", USER_AGENT)
            .header("x-rpc-app_version", SIGN_IN_APP_VERSION)
            .header("x-rpc-client_type", "5")
            .header("x-rpc-sys_version", "12")
            .header("x-rpc-platform", "android")
            .header("x-rpc-channel", "miyousheluodi")
            .header("x-rpc-signgame", "zzz")
            .header("x-rpc-device_id", deviceId)
            .header("x-rpc-device_name", "Android")
            .header("x-rpc-device_model", accountId(cookie).ifBlank { "Android" })
            .header("Origin", "https://app.mihoyo.com")
            .header("Referer", "https://app.mihoyo.com/")
            .header("X-Requested-With", "com.mihoyo.hyperion")
        if (includeRoleDs) request.header("DS", roleDs(url, body.orEmpty()))
        if (method == "POST") request.post((body ?: "{}").toRequestBody(JSON_MEDIA_TYPE)) else request.get()
        httpClient.newCall(request.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            return responseBody
        }
    }

    private fun postJson(host: String, path: String, body: String, deviceId: String): JsonResponse {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host(host)
            .addPathSegments(path.removePrefix("/")).build()
        check(HostPolicy.isAllowed(META, url.toString())) { "Provider host is not allowlisted" }
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("x-rpc-app_id", WEB_QR_APP_ID)
            .header("x-rpc-device_id", deviceId)
            .header("x-rpc-client_type", "4")
            .header("x-rpc-app_version", QR_APP_VERSION)
            .header("Origin", "https://user.mihoyo.com")
            .header("Referer", "https://user.mihoyo.com/")
            .post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP_${response.code}")
            return JsonResponse(responseBody, response.headers.values("Set-Cookie"))
        }
    }

    private val deviceId: String by lazy {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        UUID.nameUUIDFromBytes((androidId.ifBlank { UUID.randomUUID().toString() }).toByteArray()).toString()
    }

    private data class JsonResponse(val body: String, val setCookies: List<String>)

    private fun List<String>.toCookieHeader() = mapNotNull {
        it.substringBefore(';').takeIf { pair -> '=' in pair }
    }.distinctBy { it.substringBefore('=') }.joinToString("; ")

    private data class Session(
        val cookie: String,
        val uid: String = "",
        val region: String = DEFAULT_REGION
    )

    private fun decodeSession(value: String): Session {
        val json = runCatching { JSONObject(value) }.getOrNull()
        return if (json?.optInt("version") == SESSION_VERSION) {
            Session(json.optString("cookie"), json.optString("uid"), json.optString("region", DEFAULT_REGION))
        } else Session(value)
    }

    private fun encodeSession(session: Session) = JSONObject().apply {
        put("version", SESSION_VERSION)
        put("cookie", session.cookie)
        put("uid", session.uid)
        put("region", session.region)
    }.toString()

    private fun cookieNames(cookie: String) = cookie.split(';').map {
        it.substringBefore('=').trim()
    }.filter { it.isNotBlank() }.toSet()

    private fun infoQuery(session: Session) = mapOf(
        "lang" to "zh-cn",
        "act_id" to ZZZ_ACT_ID,
        "region" to session.region,
        "uid" to session.uid
    )

    private fun roleDs(url: okhttp3.HttpUrl, body: String): String {
        val t = System.currentTimeMillis() / 1000
        val r = kotlin.random.Random.nextInt(100001, 200001)
        val query = url.query.orEmpty().takeIf(String::isNotEmpty)
            ?.split('&')?.sorted()?.joinToString("&").orEmpty()
        val main = "salt=$ROLE_DS_SALT&t=$t&r=$r&b=$body&q=$query"
        val digest = MessageDigest.getInstance("MD5")
            .digest(main.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$t,$r,$digest"
    }

    private fun accountId(cookie: String): String {
        val pairs = cookie.split(';').mapNotNull { item ->
            val name = item.substringBefore('=').trim()
            val value = item.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank()) null else name to value
        }.toMap()
        return listOf("account_id_v2", "account_id", "ltuid_v2", "ltuid").firstNotNullOfOrNull(pairs::get).orEmpty()
    }

    private fun authExpired() = CheckInOutcome.AuthenticationExpired(
        "HoYoverse session expired; reconnect to continue.",
        "MIYOUSHE_ZZZ_AUTH_EXPIRED"
    )

    private fun result(outcome: CheckInOutcome) =
        CheckInResult(meta.id, meta.displayName, outcome, System.currentTimeMillis())

    private class HttpStatusException(val statusCode: Int) : Exception()

    companion object {
        private const val API_HOST = "api-takumi.mihoyo.com"
        private const val ZZZ_HOST = "act-nap-api.mihoyo.com"
        private const val WEB_QR_HOST = "passport-api.miyoushe.com"
        private const val WEB_QR_APP_ID = "bll8iq97cem8"
        private const val QR_APP_VERSION = "2.112.0"
        private const val SIGN_IN_APP_VERSION = "2.70.1"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Checky Android Build/UP1A.231005.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36 miHoYoBBS/$QR_APP_VERSION"
        private const val ZZZ_GAME_BIZ = "nap_cn"
        private const val ROLE_DS_SALT = "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs"
        private const val ZZZ_ACT_ID = "e202406242138391"
        private const val SESSION_VERSION = 1
        private const val DEFAULT_REGION = ""
        private val UID_PATTERN = Regex("\\d{8,10}")
        private val ACCOUNT_ID_COOKIE_NAMES = setOf("account_id", "account_id_v2", "ltuid", "ltuid_v2", "login_uid", "stuid")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val META = ProviderMeta(
            id = "miyoushe_zzz_experimental",
            displayName = "HoYoverse ZZZ check-in (experimental)",
            description = "Unofficial CN ZZZ interface for your own account; check-in only.",
            category = "Gaming community",
            iconKey = "game",
            accentColor = 0xFF5C6BC0,
            isEnabledByDefault = false,
            connectionType = ConnectionType.HTTP_SESSION,
            riskLevel = RiskLevel.HIGH,
            credentialType = CredentialType.SESSION_TOKEN,
            supportStatus = SupportStatus.SUPPORTED,
            allowedHosts = setOf(API_HOST, ZZZ_HOST, WEB_QR_HOST),
            businessZone = ZoneId.of("Asia/Shanghai")
        )
    }
}

internal fun mapMiyousheZzzResponse(body: String, checkingOnly: Boolean): CheckInOutcome {
    val json = runCatching { JSONObject(body) }.getOrNull()
        ?: return zzzMalformedOutcome()
    val message = json.optString("message").lowercase()
    val riskCode = (json.opt("risk_code") ?: json.optJSONObject("data")?.opt("risk_code"))?.toString()
    if ((riskCode != null && riskCode != "0" && riskCode.isNotBlank()) ||
        listOf("captcha", "geetest", "verification", "risk", "验证", "风控").any(message::contains)
    ) {
        return CheckInOutcome.ActionRequired(
            "HoYoverse requires official verification; no automatic bypass is attempted.",
            "MIYOUSHE_ZZZ_VERIFICATION"
        )
    }
    return when (json.optInt("retcode", Int.MIN_VALUE)) {
        0 -> if (checkingOnly) {
            val data = json.optJSONObject("data") ?: return zzzMalformedOutcome()
            val isSign = data.opt("is_sign") as? Boolean ?: return zzzMalformedOutcome()
            if (isSign) CheckInOutcome.AlreadyCompleted("ZZZ is already checked in today.", "ALREADY")
            else CheckInOutcome.Success("ZZZ check-in status is unsigned.", "STATUS_UNSIGNED", Reward.empty())
        } else CheckInOutcome.Success("ZZZ check-in completed.", "MIYOUSHE_ZZZ_SUCCESS", Reward.empty())
        -100, 10001 -> CheckInOutcome.AuthenticationExpired(
            "HoYoverse session expired; reconnect to continue.",
            "MIYOUSHE_ZZZ_AUTH_EXPIRED"
        )
        -5003 -> CheckInOutcome.AlreadyCompleted("ZZZ is already checked in today.", "ALREADY")
        else -> CheckInOutcome.TemporaryFailure(
            "HoYoverse rejected the ZZZ request (code ${json.optInt("retcode", Int.MIN_VALUE)}).",
            "MIYOUSHE_ZZZ_REJECTED"
        )
    }
}

private fun zzzMalformedOutcome() = CheckInOutcome.PermanentFailure(
    "HoYoverse returned an unrecognized ZZZ response; no sign request was sent.",
    "MIYOUSHE_ZZZ_BAD_RESPONSE"
)

internal fun mapMiyousheZzzRoleReadOnlyResponse(body: String): SavedCredentialValidation {
    val json = runCatching { JSONObject(body) }.getOrNull()
    ?: return SavedCredentialValidation.Unverified("HoYoverse returned an unrecognized role response.")
    return when {
        json.optInt("retcode", Int.MIN_VALUE) == 0 && json.optJSONObject("data")?.optJSONArray("list") != null ->
            SavedCredentialValidation.Valid
        json.optInt("retcode", Int.MIN_VALUE) == -100 || json.optInt("retcode", Int.MIN_VALUE) == 10001 ->
            SavedCredentialValidation.Expired
        else -> SavedCredentialValidation.Unverified("HoYoverse did not return a confirmed role response.")
    }
}
