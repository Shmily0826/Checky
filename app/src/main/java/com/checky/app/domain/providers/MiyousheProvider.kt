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
import com.checky.app.domain.QrLoginPollResult
import com.checky.app.domain.QrLoginProvider
import com.checky.app.domain.QrLoginSession
import com.checky.app.domain.SavedCredentialRevalidator
import com.checky.app.domain.SavedCredentialValidation
import com.checky.app.domain.GameRole
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
import java.util.UUID

/**
 * Personal experiment only: Genshin daily check-in through the non-public
 * MiYouShe HTTP surface.
 *
 * This provider deliberately does not implement likes, comments, missions,
 * redemptions, CAPTCHA handling, or risk-control workarounds. The session
 * cookie is supplied by the user or the official MiYouShe web QR confirmation
 * response, and is never included in a result or log.
 */
class MiyousheProvider(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val httpClient: OkHttpClient
) : CheckInProvider, QrLoginProvider, GameAccountConfigProvider, SavedCredentialRevalidator {

    override val meta: ProviderMeta = META
    override val requiresCredentials: Boolean = true

    override suspend fun validateCredentials(secret: String): CredentialValidation {
        val normalized = secret.trim()
        val cookie = decodeSession(normalized).cookie
        val names = cookieNames(cookie)
        val hasCookieToken = "cookie_token" in names || "cookie_token_v2" in names
        val hasAccountId = names.any { it in ACCOUNT_ID_COOKIE_NAMES }
        val hasLtokenPair = ("ltoken" in names && "ltuid" in names) ||
            ("ltoken_v2" in names && "ltmid_v2" in names)
        return when {
            cookie.length < 40 -> CredentialValidation.Invalid("Cookie 内容太短，未保存。")
            !hasLtokenPair && !(hasCookieToken && hasAccountId) -> CredentialValidation.Invalid(
                "Cookie 中缺少可识别的登录字段。"
            )
            else -> CredentialValidation.Valid
        }
    }

    override fun supportsDisconnect(): Boolean = true

    override suspend fun disconnect() {
        credentialStore.delete(meta.id)
    }

    override suspend fun gameAccountConfig(): GameAccountConfig? =
        credentialStore.get(meta.id)?.let(::decodeSession)?.takeIf { it.uid.isNotBlank() }?.let {
            GameAccountConfig(it.uid, it.region)
        }

    /** Performs only the authenticated role-binding GET; it never refreshes or mutates. */
    override suspend fun revalidateSavedCredential(): SavedCredentialValidation = withContext(Dispatchers.IO) {
        val saved = credentialStore.get(meta.id)
            ?: return@withContext SavedCredentialValidation.Unverified("未找到已保存的米游社会话。")
        val session = runCatching { decodeSession(saved) }.getOrNull()
            ?: return@withContext SavedCredentialValidation.Unverified("米游社会话格式无法识别。")
        val names = cookieNames(session.cookie)
        val hasLtokenPair = ("ltoken" in names && "ltuid" in names) ||
            ("ltoken_v2" in names && "ltmid_v2" in names)
        if (session.cookie.isBlank() || !hasLtokenPair) {
            return@withContext SavedCredentialValidation.Unverified("已保存会话缺少可用于只读检查的 LToken 字段。")
        }
        if (!session.uid.matches(Regex("\\d{9,10}")) || session.region !in SUPPORTED_REGIONS) {
            return@withContext SavedCredentialValidation.Unverified("已保存会话缺少可用于签到状态检查的 UID 或区服。")
        }
        runCatching {
            request(
                method = "GET",
                path = "/event/luna/hk4e/info",
                query = dailyRewardQuery(session),
                cookie = session.cookie
            )
        }.fold(
            onSuccess = ::mapMiyousheGameCheckInReadOnlyResponse,
            onFailure = { SavedCredentialValidation.Unverified("米游社只读连接检查暂时无法确认。") }
        )
    }

    /**
     * Reads the Genshin roles bound to the connected account via the miyoushe
     * binding API (same allowlisted host as the check-in itself). Best-effort
     * and fail-closed: any error or unrecognized schema yields an empty list
     * so the UI falls back to manual UID entry.
     */
    override suspend fun fetchGameRoles(): List<GameRole> = withContext(Dispatchers.IO) {
        runCatching {
            val cookie = credentialStore.get(meta.id)
                ?.let(::decodeSession)?.cookie.orEmpty()
            if (cookie.isBlank()) return@runCatching emptyList()
            val body = request(
                method = "GET",
                path = "/binding/api/getUserGameRolesByCookie",
                query = emptyMap(),
                cookie = cookie
            )
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@runCatching emptyList()
            if (json.optInt("retcode", -1) != 0) return@runCatching emptyList()
            val list = json.optJSONObject("data")?.optJSONArray("list")
                ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until list.length()) {
                    val role = list.optJSONObject(index) ?: continue
                    // Genshin only: cn official (hk4e_cn) and B服 (hk4e_bili).
                    if (!role.optString("game_biz").startsWith("hk4e")) continue
                    val uid = role.optString("game_uid")
                    val region = role.optString("region")
                    if (!uid.matches(Regex("\\d{9,10}"))) continue
                    if (region !in SUPPORTED_REGIONS) continue
                    this += GameRole(
                        config = GameAccountConfig(uid, region),
                        label = "${role.optString("region_name")} · " +
                            "${role.optString("nickname")} Lv.${role.optInt("level")}"
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun saveGameAccountConfig(uid: String, region: String): CredentialValidation {
        val normalizedUid = uid.trim()
        if (!normalizedUid.matches(Regex("\\d{9,10}"))) {
            return CredentialValidation.Invalid("请输入 9 至 10 位原神游戏 UID。")
        }
        if (region !in SUPPORTED_REGIONS) {
            return CredentialValidation.Invalid("区服仅支持 cn_gf01（官服）或 cn_qd01（B服）。")
        }
        val existing = credentialStore.get(meta.id)
            ?: return CredentialValidation.Invalid("请先完成米游社扫码连接。")
        val session = decodeSession(existing)
        when (val validation = validateCredentials(session.cookie)) {
            is CredentialValidation.Invalid -> return validation
            is CredentialValidation.Valid -> Unit
        }
        credentialStore.save(meta.id, encodeSession(session.copy(uid = normalizedUid, region = region)))
        return CredentialValidation.Valid
    }

    /** Starts the short-lived MiYouShe web QR flow. */
    override suspend fun createQrLoginSession(): QrLoginSession = withContext(Dispatchers.IO) {
        val response = postJson(
            host = WEB_QR_HOST,
            path = "/account/ma-cn-passport/web/createQRLogin",
            body = "{}",
            deviceId = deviceId
        )
        val json = JSONObject(response.body)
        if (json.optInt("retcode", -1) != 0) {
            throw IllegalStateException(json.optString("message", "米游社二维码生成失败。"))
        }
        val qrUrl = json.optJSONObject("data")?.optString("url").orEmpty()
        val ticket = json.optJSONObject("data")?.optString("ticket").orEmpty()
        if (qrUrl.isBlank() || ticket.isBlank()) {
            throw IllegalStateException("米游社返回的二维码无效。")
        }
        QrLoginSession(
            qrPayload = qrUrl,
            // This value remains in memory only and is deleted with the UI.
            sessionId = "$deviceId|$ticket"
        )
    }

    override suspend fun pollQrLogin(session: QrLoginSession): QrLoginPollResult = withContext(Dispatchers.IO) {
        val parts = session.sessionId.split('|', limit = 2)
        if (parts.size != 2) return@withContext QrLoginPollResult.Failed("二维码会话无效，请重新生成。")
        val deviceId = parts[0]
        val ticket = parts[1]
        val response = runCatching {
            postJson(
                host = WEB_QR_HOST,
                path = "/account/ma-cn-passport/web/queryQRLoginStatus",
                body = JSONObject().apply {
                    put("ticket", ticket)
                }.toString(),
                deviceId = deviceId
            )
        }.getOrElse { return@withContext QrLoginPollResult.Failed("米游社网页登录状态暂时无法查询，请稍后重试。") }

        val json = JSONObject(response.body)
        when (json.optInt("retcode", 0)) {
            -3501 -> return@withContext QrLoginPollResult.Expired()
            -3505 -> return@withContext QrLoginPollResult.Expired("扫码登录已取消，请重新生成二维码。")
        }
        if (json.optInt("retcode", 0) != 0) {
            return@withContext QrLoginPollResult.Failed(
                json.optString("message", "米游社拒绝了网页登录二维码请求。")
            )
        }
        val data = json.optJSONObject("data") ?: return@withContext QrLoginPollResult.Failed(
            "米游社返回了无法识别的二维码状态。"
        )
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
                        credentialStore.save(meta.id, encodeSession(Session(cookie = normalizedCookie)))
                        QrLoginPollResult.Confirmed(accountId.ifBlank { null })
                    }
                    is CredentialValidation.Invalid -> QrLoginPollResult.Failed(
                        "网页登录已确认，但未获得可用于签到的 Cookie：${validation.reason}"
                    )
                }
            }
            else -> QrLoginPollResult.Failed("米游社返回了未知的二维码状态。")
        }
    }

    private data class JsonResponse(
        val body: String,
        val setCookies: List<String>
    )

    private fun List<String>.toCookieHeader(): String =
        mapNotNull { header -> header.substringBefore(';').takeIf { '=' in it } }
            .distinctBy { it.substringBefore('=') }
            .joinToString("; ")

    override fun checkIn() = flow {
        emit(CheckInEvent.Progress(0.15f, "读取米游社会话…"))
        val savedSession = credentialStore.get(meta.id)
        if (savedSession.isNullOrBlank()) {
            emit(CheckInEvent.Done(result(authExpired())))
            return@flow
        }
        val session = decodeSession(savedSession)
        if (session.uid.isBlank()) {
            emit(CheckInEvent.Done(result(CheckInOutcome.ActionRequired(
                userMessage = "请先在连接页填写原神游戏 UID 和区服。",
                diagnosticCode = "MIYOUSHE_GAME_ROLE_REQUIRED"
            ))))
            return@flow
        }

        emit(CheckInEvent.Progress(0.35f, "查询原神今日签到状态…"))
        val outcome = withContext(Dispatchers.IO) { runCheckIn(session) }
        emit(CheckInEvent.Done(result(outcome)))
    }

    private fun runCheckIn(session: Session): CheckInOutcome {
        return try {
            val info = request(
                method = "GET",
                path = "/event/luna/hk4e/info",
                query = dailyRewardQuery(session),
                cookie = session.cookie
            )
            val infoOutcome = mapResponse(info, checkingOnly = true)
            when (infoOutcome) {
                is CheckInOutcome.AlreadyCompleted,
                is CheckInOutcome.AuthenticationExpired,
                is CheckInOutcome.ActionRequired,
                is CheckInOutcome.TemporaryFailure,
                is CheckInOutcome.PermanentFailure -> infoOutcome
                else -> {
                    val json = JSONObject(info)
                    val data = json.optJSONObject("data")
                    if (data?.optBoolean("is_sign", false) == true) {
                        CheckInOutcome.AlreadyCompleted(
                            userMessage = "原神今天已经签到。",
                            diagnosticCode = "ALREADY"
                        )
                    } else {
                        val signed = request(
                            method = "POST",
                            path = "/event/luna/hk4e/sign",
                            query = dailyRewardQuery(session),
                            cookie = session.cookie,
                            body = "{}"
                        )
                        mapResponse(signed, checkingOnly = false)
                    }
                }
            }
        } catch (error: HttpStatusException) {
            CheckInOutcome.TemporaryFailure(
                userMessage = "米游社服务器返回 HTTP ${error.statusCode}。",
                diagnosticCode = "MIYOUSHE_HTTP_${error.statusCode}"
            )
        } catch (_: Exception) {
            CheckInOutcome.TemporaryFailure(
                userMessage = "米游社暂时无法连接，请稍后重试。",
                diagnosticCode = "MIYOUSHE_NETWORK"
            )
        }
    }

    private fun request(
        method: String,
        path: String,
        query: Map<String, String>,
        cookie: String,
        body: String? = null
    ): String {
        val urlBuilder = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(API_HOST)
            .addPathSegments(path.removePrefix("/"))
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val url = urlBuilder.build()
        check(HostPolicy.isAllowed(META, url.toString())) { "Provider host is not allowlisted" }

        val builder = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", USER_AGENT)
            .header("x-rpc-app_version", SIGN_IN_APP_VERSION)
            .header("x-rpc-client_type", "5")
            .header("x-rpc-sys_version", "12")
            .header("x-rpc-platform", "android")
            .header("x-rpc-channel", "miyousheluodi")
            .header("x-rpc-signgame", "hk4e")
            .header("x-rpc-device_id", deviceId)
            .header("x-rpc-device_name", "Android")
            .header("x-rpc-device_model", accountId(cookie).ifBlank { "Android" })
            .header("Origin", "https://app.mihoyo.com")
            .header("Referer", "https://app.mihoyo.com/")
            .header("X-Requested-With", "com.mihoyo.hyperion")

        if (method == "POST") {
            builder.post((body ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.get()
        }

        httpClient.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code)
            }
            return responseBody
        }
    }

    private fun postJson(host: String, path: String, body: String, deviceId: String): JsonResponse {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .addPathSegments(path.removePrefix("/"))
            .build()
        check(HostPolicy.isAllowed(META, url.toString())) { "Provider host is not allowlisted" }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("x-rpc-app_id", WEB_QR_APP_ID)
            .header("x-rpc-device_id", deviceId)
            .header("x-rpc-client_type", "4")
            .header("x-rpc-app_version", QR_APP_VERSION)
            .header("Origin", "https://user.mihoyo.com")
            .header("Referer", "https://user.mihoyo.com/")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP_${response.code}")
            return JsonResponse(responseBody, response.headers.values("Set-Cookie"))
        }
    }

    /*
     * The original GameToken QR endpoints intentionally are not used here:
     * they present a Genshin login rather than the MiYouShe web login and are
     * commonly rejected by risk control on emulator or overseas networks.
     */

    private val deviceId: String by lazy {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
        val source = androidId.ifBlank { UUID.randomUUID().toString() }
        UUID.nameUUIDFromBytes(source.toByteArray()).toString()
    }

    private fun mapResponse(body: String, checkingOnly: Boolean): CheckInOutcome {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return CheckInOutcome.TemporaryFailure(
                userMessage = "米游社返回了无法识别的结果。",
                diagnosticCode = "MIYOUSHE_BAD_RESPONSE"
            )
        val retcode = json.optInt("retcode", Int.MIN_VALUE)
        val message = json.optString("message").lowercase()
        return when (retcode) {
            0 -> if (checkingOnly) {
                val data = json.optJSONObject("data")
                if (data == null || data.opt("is_sign") !is Boolean) {
                    CheckInOutcome.PermanentFailure(
                        userMessage = "米游社返回了无法识别的签到状态，已停止操作。",
                        diagnosticCode = "MIYOUSHE_BAD_STATE"
                    )
                } else {
                    CheckInOutcome.Success(
                        userMessage = "签到状态已确认。",
                        diagnosticCode = "STATUS_OK",
                        reward = Reward.empty()
                    )
                }
            } else {
                CheckInOutcome.Success(
                    userMessage = "原神签到完成。",
                    diagnosticCode = "MIYOUSHE_SUCCESS",
                    reward = Reward.empty()
                )
            }
            -100, -101, -10001 -> authExpired()
            -5003 -> CheckInOutcome.AlreadyCompleted(
                userMessage = "原神今天已经签到。",
                diagnosticCode = "ALREADY"
            )
            1034 -> CheckInOutcome.ActionRequired(
                userMessage = "米游社要求验证，请打开官方 App 处理后再试。",
                diagnosticCode = "MIYOUSHE_VERIFICATION"
            )
            else -> when {
                message.contains("captcha") ||
                    message.contains("geetest") ||
                    message.contains("验证") ||
                    message.contains("风险") -> CheckInOutcome.ActionRequired(
                        userMessage = "米游社要求官方验证（错误码 $retcode），请在官方 App 处理后再试。",
                        diagnosticCode = "MIYOUSHE_VERIFICATION_$retcode"
                    )
                message.contains("ds") ||
                    message.contains("signature") ||
                    message.contains("签名") -> CheckInOutcome.PermanentFailure(
                        userMessage = "该接口要求官方客户端签名（错误码 $retcode），当前实验版不能安全完成。",
                        diagnosticCode = "MIYOUSHE_SIGNATURE_REQUIRED_$retcode"
                    )
                else -> CheckInOutcome.TemporaryFailure(
                    userMessage = "米游社拒绝了请求（错误码 $retcode）。",
                    diagnosticCode = "MIYOUSHE_REJECTED_$retcode"
                )
            }
        }
    }

    private fun mapMiyousheGameCheckInReadOnlyResponse(body: String): SavedCredentialValidation =
        when (mapResponse(body, checkingOnly = true)) {
            is CheckInOutcome.Success -> SavedCredentialValidation.Valid
            is CheckInOutcome.AuthenticationExpired -> SavedCredentialValidation.Expired
            else -> SavedCredentialValidation.Unverified("米游社未返回可确认的签到状态。")
        }

    private fun accountId(cookie: String): String {
        val preferredNames = listOf("account_id_v2", "account_id", "ltuid_v2", "ltuid")
        val pairs = cookie.split(';').mapNotNull { item ->
            val name = item.substringBefore('=').trim()
            val value = item.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank()) null else name to value
        }.toMap()
        return preferredNames.firstNotNullOfOrNull(pairs::get).orEmpty()
    }

    private class HttpStatusException(val statusCode: Int) : Exception()

    private data class Session(
        val cookie: String,
        val uid: String = "",
        val region: String = DEFAULT_REGION
    )

    private fun decodeSession(value: String): Session {
        val json = runCatching { JSONObject(value) }.getOrNull()
        return if (json?.optInt("version") == SESSION_VERSION) {
            Session(
                cookie = json.optString("cookie"),
                uid = json.optString("uid"),
                region = json.optString("region", DEFAULT_REGION)
            )
        } else {
            // Backward compatibility for sessions saved before role selection existed.
            Session(cookie = value)
        }
    }

    private fun encodeSession(session: Session): String = JSONObject().apply {
        put("version", SESSION_VERSION)
        put("cookie", session.cookie)
        put("uid", session.uid)
        put("region", session.region)
    }.toString()

    private fun cookieNames(cookie: String): Set<String> = cookie
        .split(';')
        .map { it.substringBefore('=').trim() }
        .filter { it.isNotBlank() }
        .toSet()

    private fun dailyRewardQuery(session: Session) = mapOf(
        "lang" to "zh-cn",
        "act_id" to GENSHIN_ACT_ID,
        "uid" to session.uid,
        "region" to session.region
    )

    private fun authExpired() = CheckInOutcome.AuthenticationExpired(
        userMessage = "米游社会话已失效，请重新连接。",
        diagnosticCode = "MIYOUSHE_AUTH_EXPIRED"
    )

    private fun result(outcome: CheckInOutcome) =
        CheckInResult(meta.id, meta.displayName, outcome, System.currentTimeMillis())

    companion object {
        private const val API_HOST = "api-takumi.mihoyo.com"
        private const val WEB_QR_HOST = "passport-api.miyoushe.com"
        private const val WEB_QR_APP_ID = "bll8iq97cem8"
        private const val QR_APP_VERSION = "2.112.0"
        private const val SIGN_IN_APP_VERSION = "2.70.1"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Checky Android Build/UP1A.231005.007; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/120.0.6099.230 Mobile Safari/537.36 miHoYoBBS/$QR_APP_VERSION"
        private const val GENSHIN_ACT_ID = "e202311201442471"
        private const val SESSION_VERSION = 1
        private const val DEFAULT_REGION = "cn_gf01"
        private val SUPPORTED_REGIONS = setOf("cn_gf01", "cn_qd01")
        private val ACCOUNT_ID_COOKIE_NAMES = setOf(
            "account_id", "account_id_v2", "ltuid", "ltuid_v2", "login_uid", "stuid"
        )
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val META = ProviderMeta(
            id = "miyoushe_genshin_experimental",
            displayName = "米游社签到（原神实验版）",
            description = "非官方接口，仅限本人账号测试；使用米游社网页登录扫码，只执行签到，不执行点赞、评论或兑换。",
            category = "游戏社区",
            iconKey = "game",
            accentColor = 0xFF7C4DFF,
            isEnabledByDefault = false,
            connectionType = ConnectionType.HTTP_SESSION,
            riskLevel = RiskLevel.HIGH,
            credentialType = CredentialType.SESSION_TOKEN,
            supportStatus = SupportStatus.SUPPORTED,
            allowedHosts = setOf(API_HOST, WEB_QR_HOST),
            businessZone = ZoneId.of("Asia/Shanghai")
        )
    }
}

internal fun mapMiyousheGameReadOnlyResponse(body: String): SavedCredentialValidation {
    val json = runCatching { JSONObject(body) }.getOrNull()
        ?: return SavedCredentialValidation.Unverified("米游社返回了无法识别的连接状态。")
    val retcode = json.optInt("retcode", Int.MIN_VALUE)
    return when {
        retcode == 0 && json.optJSONObject("data")?.optJSONArray("list") != null ->
            SavedCredentialValidation.Valid
        retcode == -100 -> SavedCredentialValidation.Expired
        else -> SavedCredentialValidation.Unverified("米游社未返回可确认的连接状态。")
    }
}
