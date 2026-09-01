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
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.ConnectionType
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
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
) : CheckInProvider, QrLoginProvider {

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

        val json = JSONObject(response)
        val retcode = json.optInt("retcode", 0)
        if (retcode in QR_EXPIRED_RETCODES) {
            return@withContext QrLoginPollResult.Expired()
        }
        if (retcode != 0) {
            // Surfacing the code matters here: the app logs nothing, so the
            // on-screen text is the only way to tell a rejection from an
            // expired or malformed ticket.
            return@withContext QrLoginPollResult.Failed(
                "米游社拒绝了社区二维码请求（错误码 $retcode），请重新生成。"
            )
        }
        val data = json.optJSONObject("data") ?:
            return@withContext QrLoginPollResult.Failed("米游社返回了无法识别的二维码状态。")
        val status = data.optString("status").ifBlank { data.optString("stat") }
        when (status) {
            "Init", "Created" -> QrLoginPollResult.Waiting
            "Scanned" -> QrLoginPollResult.Scanned
            "Confirmed" -> {
                val token = data.optJSONArray("tokens")
                    ?.optJSONObject(0)
                    ?.optString("token")
                    .orEmpty()
                val mid = data.optJSONObject("user_info")?.optString("mid").orEmpty()
                val accountId = data.optJSONObject("user_info")?.optString("aid").orEmpty()
                if (accountId.isBlank() || mid.isBlank() || token.isBlank()) {
                    val missing = buildList {
                        if (token.isBlank()) add("token")
                        if (mid.isBlank()) add("mid")
                        if (accountId.isBlank()) add("aid")
                    }.joinToString("、")
                    QrLoginPollResult.Failed(
                        "扫码已确认，但米游社返回的社区凭证缺少 $missing，请重新生成二维码。"
                    )
                } else {
                    runCatching { buildCommunityCookie(token, mid, accountId) }
                        .fold(
                            onSuccess = { credentialStore.save(meta.id, it); QrLoginPollResult.Confirmed(accountId) },
                            onFailure = { QrLoginPollResult.Failed("扫码已确认，但社区凭证保存失败，请重试。") }
                        )
                }
            }
            else -> QrLoginPollResult.Failed(
                "米游社返回了未知的二维码状态（$status），请重新生成。"
            )
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
        val outcome = withContext(Dispatchers.IO) { runCheckIn(cookie) }
        emit(CheckInEvent.Done(result(outcome)))
    }

    private fun runCheckIn(cookie: String): CheckInOutcome = try {
        val body = JSONObject().put("gids", GENSHIN_GID).toString()
        val response = request(cookie, body)
        mapMiyousheCommunityResponse(response)
    } catch (_: Exception) {
        CheckInOutcome.TemporaryFailure(
            "米游社讨论区暂时无法连接，请稍后重试。",
            "MIYOUSHE_COMMUNITY_NETWORK"
        )
    }

    private fun request(cookie: String, body: String): String {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(API_HOST)
            .addPathSegments("apihub/app/api/signIn")
            .build()
        val deviceId = deviceId()
        val request = Request.Builder()
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
            .header("DS", generateDataDs(body))
            .header("Referer", "https://app.mihoyo.com")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Connection", "Keep-Alive")
            .header("Accept-Encoding", "gzip")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        check(META.allowedHosts.contains(url.host)) { "Provider host is not allowlisted" }
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP_${response.code}")
            response.body?.string().orEmpty()
        }
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

    private fun buildCommunityCookie(stoken: String, mid: String, bbsUid: String): String {
        var cookie = mergeCookies(
            "stoken=$stoken; stoken_v2=$stoken; mid=$mid; stuid=$bbsUid; account_id=$bbsUid; account_id_v2=$bbsUid",
            emptyList()
        )
        runCatching { fetchCookieToken(stoken, mid, bbsUid) }.getOrNull()?.let { extra ->
            cookie = mergeCookies(cookie, extra)
        }
        runCatching { fetchLToken(stoken, mid, bbsUid) }.getOrNull()?.let { extra ->
            cookie = mergeCookies(cookie, extra)
        }
        return cookie
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
            allowedHosts = setOf(API_HOST, PASSPORT_HOST)
        )
    }
}

internal fun mapMiyousheCommunityResponse(body: String): CheckInOutcome {
    val json = runCatching { JSONObject(body) }.getOrNull()
        ?: return CheckInOutcome.PermanentFailure(
            "米游社返回了无法识别的签到结果，已停止操作。",
            "MIYOUSHE_COMMUNITY_BAD_RESPONSE"
        )
    val retcode = json.optInt("retcode", Int.MIN_VALUE).takeIf { json.has("retcode") }
    val message = json.optString("message")
    val data = json.optJSONObject("data")
    return mapMiyousheCommunityFields(
        retcode = retcode,
        message = message,
        points = data?.optInt("points")?.takeIf { data.has("points") }
    )
}

internal fun mapMiyousheCommunityFields(
    retcode: Int?,
    message: String,
    points: Int?
): CheckInOutcome {
    if (retcode == null) {
        return CheckInOutcome.PermanentFailure(
            "米游社返回了缺少状态码的签到结果，已停止操作。",
            "MIYOUSHE_COMMUNITY_BAD_RESPONSE"
        )
    }
    return when {
        retcode == 0 -> {
            if (points == null) {
                CheckInOutcome.PermanentFailure(
                    "米游社返回了无法识别的签到结果，已停止操作。",
                    "MIYOUSHE_COMMUNITY_BAD_RESPONSE"
                )
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
