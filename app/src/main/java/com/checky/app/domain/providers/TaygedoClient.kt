package com.checky.app.domain.providers

import android.content.Context
import android.provider.Settings
import android.util.Base64
import com.checky.app.data.network.HostPolicy
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.model.ProviderMeta
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Shared local session and HTTP client for the two Taygedo check-in providers. */
class TaygedoClient(
    context: Context,
    private val credentials: CredentialStore,
    private val http: OkHttpClient
) {
    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val uid: String,
        val deviceId: String
    )

    data class ApiResult(val code: Int, val data: Any?, val message: String, val raw: JSONObject)

    private val deviceId: String = UUID.nameUUIDFromBytes(
        (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()).toByteArray()
    ).toString().replace("-", "")

    suspend fun hasSession(): Boolean = credentials.has(SESSION_KEY)
    suspend fun disconnect() = credentials.delete(SESSION_KEY)

    suspend fun sendSms(phone: String): String? {
        val result = laohu(
            "/m/newApi/sendPhoneCaptchaWithOutLogin",
            linkedMapOf("cellphone" to phone, "type" to "16", "areaCodeId" to "1"),
            encryptSensitive = false
        )
        return if (result.optInt("code", -1) == 0) null
        else result.optString("message", "验证码发送失败。")
    }

    suspend fun login(phone: String, code: String): String? {
        val checked = laohu(
            "/m/newApi/checkPhoneCaptchaWithOutLogin",
            linkedMapOf("cellphone" to phone, "captcha" to code),
            encryptSensitive = false
        )
        if (checked.optInt("code", -1) != 0) {
            throw IllegalStateException(checked.optString("message", "验证码无效。"))
        }
        val login = laohu(
            "/openApi/sms/new/login",
            linkedMapOf(
                "cellphone" to phone, "captcha" to code, "type" to "16",
                "areaCodeId" to "1", "version" to "", "idfa" to "",
                "adm" to "", "mac" to "", "sign" to ""
            ),
            encryptSensitive = true
        )
        if (login.optInt("code", -1) != 0) {
            throw IllegalStateException(login.optString("message", "塔吉多登录失败。"))
        }
        val payload = login.optJSONObject("result") ?: throw IllegalStateException("登录结果不完整。")
        val token = payload.optString("token")
        val userId = payload.optString("userId")
        if (token.isBlank() || userId.isBlank()) throw IllegalStateException("登录凭证不完整。")

        val exchanged = bbs(
            meta = TaygedoNteProvider.META,
            path = "/usercenter/api/login",
            method = "POST",
            form = mapOf("token" to token, "userIdentity" to userId, "appId" to "10551"),
            auth = "",
            useDs = true,
            extraHeaders = mapOf("uid" to "0")
        )
        val data = exchanged.raw.optJSONObject("data")
            ?: throw IllegalStateException(exchanged.message.ifBlank { "无法换取塔吉多会话。" })
        val session = Session(
            accessToken = data.optString("accessToken"),
            refreshToken = data.optString("refreshToken"),
            uid = data.optString("uid"),
            deviceId = deviceId
        )
        if (session.accessToken.isBlank() || session.refreshToken.isBlank()) {
            throw IllegalStateException("塔吉多会话不完整。")
        }
        credentials.save(SESSION_KEY, encode(session))
        return payload.optString("nickname").ifBlank { session.uid }
    }

    suspend fun request(
        meta: ProviderMeta,
        path: String,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        form: Map<String, String> = emptyMap(),
        webHeaders: Boolean = false,
        useDs: Boolean = false
    ): ApiResult {
        var session = load() ?: throw AuthException()
        fun headersFor(current: Session) = buildMap {
            put("uid", current.uid)
            if (webHeaders) putAll(WEB_HEADERS)
        }
        var response = bbs(meta, path, method, query, form, session.accessToken, useDs,
            headersFor(session))
        if (response.code == 401 || response.code == -401) {
            session = refresh(session, meta) ?: throw AuthException()
            response = bbs(meta, path, method, query, form, session.accessToken, useDs,
                headersFor(session))
        }
        return response
    }

    private suspend fun refresh(old: Session, meta: ProviderMeta): Session? = runCatching {
        val result = bbs(
            meta, "/usercenter/api/refreshToken", "POST", auth = old.refreshToken,
            useDs = true, extraHeaders = mapOf("uid" to "0")
        )
        val data = result.raw.optJSONObject("data") ?: return null
        old.copy(
            accessToken = data.optString("accessToken"),
            refreshToken = data.optString("refreshToken")
        ).also { credentials.save(SESSION_KEY, encode(it)) }
    }.getOrNull()

    private fun bbs(
        meta: ProviderMeta,
        path: String,
        method: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String> = emptyMap(),
        auth: String,
        useDs: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ): ApiResult {
        val url = HttpUrl.Builder().scheme("https").host(BBS_HOST)
            .addPathSegments(path.removePrefix("/"))
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        check(HostPolicy.isAllowed(meta, url.toString()))
        val builder = Request.Builder().url(url)
            .header("Authorization", auth)
            .header("deviceid", deviceId)
            .header("appversion", APP_VERSION)
            .header("platform", "android")
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json, text/plain, */*")
        if (useDs) builder.header("ds", ds())
        extraHeaders.forEach(builder::header)
        if (method == "POST") {
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            builder.post(body)
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            val code = if (response.code == 401) 401 else json.optInt("code", response.code)
            val message = json.optString("msg").ifBlank { json.optString("message") }
            return ApiResult(code, json.opt("data"), message, json)
        }
    }

    private fun laohu(path: String, input: LinkedHashMap<String, String>, encryptSensitive: Boolean): JSONObject {
        val params = LinkedHashMap(input)
        if (encryptSensitive) {
            listOf("cellphone", "captcha").forEach { key -> params[key] = aes(params[key].orEmpty()) }
        }
        params.putAll(deviceParams())
        params["t"] = if (encryptSensitive) System.currentTimeMillis().toString()
        else (System.currentTimeMillis() / 1000).toString()
        params["sign"] = md5(params.toSortedMap().values.joinToString("") + LAOHU_APP_KEY)
        val url = HttpUrl.Builder().scheme("https").host(LAOHU_HOST)
            .addPathSegments(path.removePrefix("/")).build()
        check(HostPolicy.isAllowed(TaygedoNteProvider.META, url.toString()))
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("塔吉多登录服务返回异常（HTTP ${response.code}）。")
            }
        }
    }

    private fun deviceParams() = mapOf(
        "deviceId" to deviceId, "deviceType" to "Pixel 6", "deviceName" to "Pixel 6",
        "deviceModel" to "Pixel 6", "deviceSys" to "14", "sdkVersion" to "4.327.0",
        "versionCode" to "17", "bid" to "com.pwrd.htassistant",
        "appId" to "10550", "channelId" to "1"
    )

    private fun aes(value: String): String {
        val key = LAOHU_APP_KEY.takeLast(16).toByteArray()
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun ds(): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = buildString { repeat(8) { append(alphabet[SecureRandom().nextInt(alphabet.length)]) } }
        return "$ts,$random,${md5(ts + random + APP_VERSION + DS_SALT)}"
    }

    private fun md5(value: String) = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun load(): Session? = credentials.get(SESSION_KEY)?.let(::decode)
    private fun encode(s: Session) = JSONObject().apply {
        put("accessToken", s.accessToken); put("refreshToken", s.refreshToken)
        put("uid", s.uid); put("deviceId", s.deviceId)
    }.toString()
    private fun decode(raw: String) = runCatching { JSONObject(raw) }.getOrNull()?.let {
        Session(it.optString("accessToken"), it.optString("refreshToken"), it.optString("uid"), it.optString("deviceId"))
    }

    class AuthException : Exception()

    companion object {
        const val SESSION_KEY = "taygedo.shared.session"
        private const val BBS_HOST = "bbs-api.tajiduo.com"
        private const val LAOHU_HOST = "user.laohu.com"
        private const val APP_VERSION = "1.2.5"
        private const val DS_SALT = "pUds3dfMkl"
        private const val LAOHU_APP_KEY = "89155cc4e8634ec5b1b6364013b23e3e"
        private val WEB_HEADERS = mapOf(
            "Accept" to "application/json",
            "Origin" to "https://webstatic.tajiduo.com",
            "Referer" to "https://webstatic.tajiduo.com/",
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Tajiduo/1.2.2"
        )
    }
}
