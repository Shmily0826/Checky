package com.checky.app.domain.providers

import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.SmsLoginProvider
import com.checky.app.domain.SmsLoginResult
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.ConnectionType
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import com.checky.app.domain.model.RiskLevel
import com.checky.app.domain.model.SupportStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

private val BROWSE_TASK_CODES = setOf("browse_post_c")

internal fun isAllowedBrowseTaskCode(code: String): Boolean = code in BROWSE_TASK_CODES

internal fun nteRewardIndexForSignedDays(signedDays: Int): Int =
    signedDays.coerceAtLeast(1) - 1

private fun nteRewardForSignedDays(rewards: JSONArray?, signedDays: Int): JSONObject? =
    rewards?.optJSONObject(nteRewardIndexForSignedDays(signedDays))

abstract class TaygedoProvider(
    protected val client: TaygedoClient
) : CheckInProvider, SmsLoginProvider {
    override val requiresCredentials = true

    override suspend fun isSmsConnected(): Boolean = client.hasSession()

    override suspend fun validateCredentials(secret: String): CredentialValidation =
        CredentialValidation.Invalid("请使用手机号和短信验证码连接塔吉多。")

    override suspend fun sendSmsCode(phone: String): SmsLoginResult = withContext(Dispatchers.IO) {
        if (!phone.matches(Regex("1\\d{10}"))) return@withContext SmsLoginResult.Failed("请输入 11 位中国大陆手机号。")
        runCatching { client.sendSms(phone) }.fold(
            onSuccess = { message -> if (message == null) SmsLoginResult.CodeSent() else SmsLoginResult.Failed(message) },
            onFailure = { SmsLoginResult.Failed("验证码暂时无法发送，请稍后再试。") }
        )
    }

    override suspend fun confirmSmsCode(phone: String, code: String): SmsLoginResult = withContext(Dispatchers.IO) {
        if (!code.matches(Regex("\\d{4,8}"))) return@withContext SmsLoginResult.Failed("请输入短信中的验证码。")
        runCatching { client.login(phone, code) }.fold(
            onSuccess = { SmsLoginResult.Connected(it) },
            onFailure = { SmsLoginResult.Failed(it.message ?: "塔吉多登录失败。") }
        )
    }

    override fun supportsDisconnect() = true
    override suspend fun disconnect() = client.disconnect()

    protected suspend fun execute(block: suspend () -> CheckInOutcome): CheckInOutcome = try {
        if (!client.hasSession()) authExpired() else block()
    } catch (_: TaygedoClient.AuthException) {
        authExpired()
    } catch (_: Exception) {
        CheckInOutcome.TemporaryFailure("塔吉多暂时无法连接，请稍后重试。", "TAYGEDO_NETWORK")
    }

    protected fun result(outcome: CheckInOutcome) =
        CheckInResult(meta.id, meta.displayName, outcome, System.currentTimeMillis())

    private fun authExpired() = CheckInOutcome.AuthenticationExpired(
        "塔吉多登录已失效，请重新进行短信验证。", "TAYGEDO_AUTH_EXPIRED"
    )

    companion object {
        val HOSTS = setOf("bbs-api.tajiduo.com", "user.laohu.com")
    }
}

class TaygedoNteProvider(client: TaygedoClient) : TaygedoProvider(client) {
    override val meta = META

    override fun checkIn() = flow {
        emit(CheckInEvent.Progress(0.15f, "读取塔吉多会话…"))
        val outcome = withContext(Dispatchers.IO) { execute(::checkInNte) }
        emit(CheckInEvent.Done(result(outcome)))
    }

    private suspend fun checkInNte(): CheckInOutcome {
        val home = client.request(meta, "/apihub/awapi/yh/roleHome", useDs = true)
        if (home.code != 0) return failure(home, "无法获取已绑定的异环角色。")
        val roleId = (home.data as? JSONObject)?.optString("roleid").orEmpty()
        if (roleId.isBlank()) return CheckInOutcome.ActionRequired(
            "塔吉多账号尚未绑定异环角色，请先在官方 App 中绑定。", "TAYGEDO_NTE_ROLE_REQUIRED"
        )
        val state = client.request(
            meta, "/apihub/awapi/signin/state", query = mapOf("gameId" to "1289"),
            webHeaders = true
        )
        if (state.code != 0) return failure(state, "无法查询异环签到状态。")
        val stateData = parseNteSignState(state.data)
            ?: return CheckInOutcome.PermanentFailure(
                "塔吉多返回了无法识别的异环签到状态，已停止操作。",
                "TAYGEDO_NTE_BAD_STATE"
            )
        if (stateData.todaySigned) {
            return CheckInOutcome.AlreadyCompleted(
                "异环今天已经签到（本月累计 ${stateData.days} 天）。", "TAYGEDO_NTE_ALREADY"
            )
        }
        val signed = client.request(
            meta, "/apihub/awapi/sign", "POST",
            form = mapOf("roleId" to roleId, "gameId" to "1289"), webHeaders = true
        )
        if (signed.code != 0) return failure(signed, "异环签到失败。")
        val newStateResult = client.request(
            meta, "/apihub/awapi/signin/state", query = mapOf("gameId" to "1289"),
            webHeaders = true
        )
        if (newStateResult.code != 0) return failure(newStateResult, "无法确认异环签到结果。")
        val newState = parseNteSignState(newStateResult.data)
            ?: return CheckInOutcome.PermanentFailure(
                "塔吉多返回了无法确认的异环签到结果，已停止后续操作。",
                "TAYGEDO_NTE_BAD_RESULT"
            )
        val rewards = client.request(
            meta, "/apihub/awapi/sign/rewards",
            query = mapOf("gameId" to "1289", "roleId" to roleId), webHeaders = true
        ).data as? JSONArray
        // `day` is the calendar day; the reward list is indexed by the
        // number of completed monthly sign-ins. Using the calendar day can
        // display a later day's reward on the first sign-in of the month.
        val reward = nteRewardForSignedDays(rewards, newState.days)
        val rewardText = reward?.let { "，获得 ${it.optString("name")} ×${it.optInt("num")}" }.orEmpty()
        return CheckInOutcome.Success(
            "异环签到成功（本月累计 ${newState.days} 天）$rewardText。",
            "TAYGEDO_NTE_SUCCESS", Reward.empty()
        )
    }

    companion object {
        val META = ProviderMeta(
            id = "taygedo_nte", displayName = "异环游戏签到",
            description = "通过塔吉多非公开接口领取异环月度签到奖励；仅限本人账号。",
            category = "游戏", iconKey = "gamepad", accentColor = 0xFF7257E8,
            isEnabledByDefault = false, connectionType = ConnectionType.HTTP_SESSION,
            riskLevel = RiskLevel.HIGH, credentialType = CredentialType.SESSION_TOKEN,
            supportStatus = SupportStatus.SUPPORTED, allowedHosts = HOSTS
        )
    }
}

class TaygedoCommunityProvider(client: TaygedoClient) : TaygedoProvider(client) {
    override val meta = META

    override fun checkIn() = flow {
        emit(CheckInEvent.Progress(0.15f, "查询异环社区签到状态…"))
        val outcome = withContext(Dispatchers.IO) { execute(::checkInCommunity) }
        emit(CheckInEvent.Done(result(outcome)))
    }

    private suspend fun checkInCommunity(): CheckInOutcome {
        // The current community flow has two deterministic sign-in entries:
        // the app-level daily sign-in (communityId=1) and the BBS/section
        // sign-in (communityId=2). Treat an upstream "already signed" reply
        // as positive so a repeat run is idempotent.
        val signIns = runCommunitySignInSequence { communityId ->
            // Read-only preflight: if the official endpoint already reports today's
            // sign-in, skip the mutation and treat it as AlreadyCompleted.
            val preflight = readCommunitySignState(communityId)
            if (preflight == CommunitySignState.SIGNED) {
                CommunitySignResponse(code = 0, message = "今日已签到", hasExpectedData = true)
            } else {
                // Live-verified contract (2026-08-30): plain Authorization +
                // form body + ds signature. AuthorizationV2 + JSON body made
                // the server answer 系统错误; omitting ds answered
                // invalid request.
                client.request(
                    meta, "/apihub/api/signin", "POST",
                    form = mapOf("communityId" to communityId),
                    useDs = true
                ).toCommunitySignResponse()
            }
        }
        val appSignin = signIns[0]
        if (!appSignin.classification.mayContinue) {
            return appSignin.classification.toOutcome("异环 APP 签到失败。", appSignin.response.message)
        }
        val bbsSignin = signIns[1]
        if (!bbsSignin.classification.mayContinue) {
            return bbsSignin.classification.toOutcome("异环社区版区签到失败。", bbsSignin.response.message)
        }

        val exp = appSignin.response.exp
        val coin = appSignin.response.goldCoin
        val taskState = readCommunityTaskState()
        val browsed = taskState?.let { performBrowseTasks(it.browseRemaining) } ?: 0
        val refreshedTaskState = if (browsed > 0) readCommunityTaskState() else taskState
        val appLabel = if (appSignin.classification == CommunitySignDisposition.ALREADY_COMPLETED) {
            "APP 今日已签到"
        } else {
            "APP 签到成功"
        }
        val bbsLabel = if (bbsSignin.classification == CommunitySignDisposition.ALREADY_COMPLETED) {
            "版区今日已签到"
        } else {
            "版区签到成功"
        }
        val message = buildString {
            append("塔吉多社区：$appLabel，$bbsLabel。")
            if (exp > 0 || coin > 0) append("经验 +$exp，金币 +$coin。")
            if (browsed > 0) append("自动浏览 $browsed 篇帖子。")
            if (refreshedTaskState != null) append(" ").append(refreshedTaskState.toMessage())
        }
        val already = appSignin.classification == CommunitySignDisposition.ALREADY_COMPLETED &&
            bbsSignin.classification == CommunitySignDisposition.ALREADY_COMPLETED
        return if (already) {
            CheckInOutcome.AlreadyCompleted(message, "TAYGEDO_COMMUNITY_ALREADY")
        } else {
            CheckInOutcome.Success(
                message,
                "TAYGEDO_COMMUNITY_SUCCESS",
                Reward(RewardType.EXPERIENCE, exp)
            )
        }
    }

    /** Read task counters without performing like/share interactions. */
    private suspend fun readCommunityTaskState(): CommunityTaskState? {
        return try {
            val result = client.request(
                meta,
                "/apihub/api/getUserTasks",
                query = mapOf("communityId" to "2", "gid" to "2"),
                authV2 = true,
                useDs = true
            )
            if (result.code != 0) {
                null
            } else {
                parseCommunityTaskState((result.data as? JSONObject)?.optJSONArray("task_list3"))
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Read-only preflight: ask the official `getSignState` endpoint whether a
     * given community has already been signed today.
     *
     * Recovered contract (HBC 98, official 1.2.6):
     *   GET /apihub/api/getSignState?communityId=<id>
     *   headers: Authorization (access token) — official BearerAuthService contract
     * Fails closed: ambiguous or errored responses return UNKNOWN so the
     * subsequent sign-in call (not a guessed mutation) makes the decision.
     */
    private suspend fun readCommunitySignState(communityId: String): CommunitySignState {
        return runCatching {
            client.request(
                meta,
                "/apihub/api/getSignState",
                query = mapOf("communityId" to communityId)
            ).toCommunitySignState()
        }.getOrDefault(CommunitySignState.UNKNOWN)
    }

    /** Complete only the read-only browse task from the official task flow. */
    private suspend fun performBrowseTasks(remaining: Int): Int {
        if (remaining <= 0) return 0
        return try {
            val listResult = client.request(
                meta,
                "/bbs/api/getRecommendPostList",
                query = mapOf("communityId" to "2", "count" to "20", "page" to "1"),
                useDs = true
            )
            if (listResult.code != 0) return 0
            val data = listResult.data
            val posts = when (data) {
                is JSONArray -> data
                is JSONObject -> data.optJSONArray("list") ?: data.optJSONArray("posts")
                else -> null
            } ?: return 0
            var completed = 0
            for (index in 0 until min(remaining, posts.length())) {
                val post = posts.optJSONObject(index) ?: continue
                val postId = post.optString("postId").ifBlank { post.optString("id") }
                if (postId.isBlank()) continue
                val detail = client.request(
                    meta,
                    "/bbs/api/getPostFull",
                    query = mapOf("postId" to postId),
                    useDs = true
                )
                if (detail.code == 0) completed++
                if (index + 1 < min(remaining, posts.length())) delay(350)
            }
            completed
        } catch (_: Exception) {
            0
        }
    }

    companion object {
        val META = ProviderMeta(
            id = "taygedo_community", displayName = "塔吉多社区签到",
            description = "完成塔吉多 APP 与异环版区每日签到，并读取浏览、点赞、分享任务状态；不自动点赞、不分享。",
            category = "社区", iconKey = "star", accentColor = 0xFFEE7B45,
            isEnabledByDefault = false, connectionType = ConnectionType.HTTP_SESSION,
            riskLevel = RiskLevel.HIGH, credentialType = CredentialType.SESSION_TOKEN,
            supportStatus = SupportStatus.SUPPORTED, allowedHosts = HOSTS
        )
    }
}

/**
 * Parse community task counters from a `task_list3` JSON array (read-only).
 * Like/share/follow counters are read for display only; this function never
 * performs any of those interactions. Returns null when the array is missing
 * or unusable so callers fail closed.
 */
internal fun parseCommunityTaskState(taskList: JSONArray?): CommunityTaskState? {
    if (taskList == null) return null
    fun remaining(code: String): Int? {
        for (index in 0 until taskList.length()) {
            val task = taskList.optJSONObject(index) ?: continue
            val taskCode = task.optString("taskKey").ifBlank { task.optString("code") }
            if (taskCode == code && (isAllowedBrowseTaskCode(taskCode) ||
                    taskCode == "like_post_c" || taskCode == "share")) {
                return (task.optInt("limitTimes") - task.optInt("completeTimes")).coerceAtLeast(0)
            }
        }
        return null
    }
    // Unknown task types are intentionally ignored (no automatic action).
    return CommunityTaskState(
        browseRemaining = remaining("browse_post_c") ?: 0,
        likeRemaining = remaining("like_post_c") ?: 0,
        shareRemaining = remaining("share") ?: 0
    )
}

internal data class CommunityTaskState(
    val browseRemaining: Int,
    val likeRemaining: Int,
    val shareRemaining: Int
) {
    fun toMessage() = "社区任务剩余：浏览 ${browseRemaining} 次、点赞 ${likeRemaining} 次、分享 ${shareRemaining} 次。"
}

internal enum class CommunitySignDisposition {
    SUCCESS,
    ALREADY_COMPLETED,
    AUTH_EXPIRED,
    VERIFICATION_REQUIRED,
    FAILURE,
    MALFORMED
}

/**
 * Read-only sign-state classification. The official `getSignState` response
 * schema is not pinned down by static analysis, so we only recognise an
 * unambiguous signed/unsigned flag. Anything else (error, missing data,
 * unrecognised field) is UNKNOWN and must NOT drive a mutation.
 */
internal enum class CommunitySignState { SIGNED, UNSIGNED, UNKNOWN }

internal fun TaygedoClient.ApiResult.toCommunitySignState(): CommunitySignState {
    if (code != 0) return CommunitySignState.UNKNOWN
    val data = data as? JSONObject ?: return CommunitySignState.UNKNOWN
    val signed = when {
        data.has("isSign") -> data.optBoolean("isSign")
        data.has("signed") -> data.optBoolean("signed")
        data.has("todaySign") -> data.optBoolean("todaySign")
        data.has("signState") -> data.optInt("signState") == 1
        data.has("status") -> data.optInt("status") == 1
        data.has("sign") -> data.optInt("sign") == 1
        else -> null
    }
    return when (signed) {
        true -> CommunitySignState.SIGNED
        false -> CommunitySignState.UNSIGNED
        null -> CommunitySignState.UNKNOWN
    }
}

internal data class CommunitySignResponse(
    val code: Int,
    val message: String,
    val hasExpectedData: Boolean,
    val exp: Int = 0,
    val goldCoin: Int = 0
)

internal data class CommunitySignCall(
    val communityId: String,
    val response: CommunitySignResponse,
    val classification: CommunitySignDisposition
)

internal val CommunitySignDisposition.mayContinue: Boolean
    get() = this == CommunitySignDisposition.SUCCESS ||
        this == CommunitySignDisposition.ALREADY_COMPLETED

internal fun classifyCommunitySignResponse(
    response: CommunitySignResponse
): CommunitySignDisposition {
    val message = response.message
    return when {
        response.code == 401 || response.code == -401 -> CommunitySignDisposition.AUTH_EXPIRED
        message.contains("验证") || message.contains("风控") ||
            message.contains("风险") || message.contains("captcha", ignoreCase = true) ->
            CommunitySignDisposition.VERIFICATION_REQUIRED
        isAlreadySignedMessage(message) -> CommunitySignDisposition.ALREADY_COMPLETED
        response.code != 0 -> CommunitySignDisposition.FAILURE
        !response.hasExpectedData -> CommunitySignDisposition.MALFORMED
        else -> CommunitySignDisposition.SUCCESS
    }
}

private fun isAlreadySignedMessage(message: String): Boolean {
    val text = message.lowercase()
    return text.contains("已签到") || text.contains("签到过") ||
        text.contains("重复签到") || text.contains("今日已完成") ||
        text.contains("今日已领取") || text.contains("今天已经") ||
        text.contains("already signed")
}

internal suspend fun runCommunitySignInSequence(
    signIn: suspend (communityId: String) -> CommunitySignResponse
): List<CommunitySignCall> {
    val calls = mutableListOf<CommunitySignCall>()
    for (communityId in listOf("1", "2")) {
        val response = signIn(communityId)
        val classification = classifyCommunitySignResponse(response)
        calls += CommunitySignCall(communityId, response, classification)
        if (!classification.mayContinue) break
    }
    return calls
}

private fun TaygedoClient.ApiResult.toCommunitySignResponse(): CommunitySignResponse {
    val data = data as? JSONObject
    return CommunitySignResponse(
        code = code,
        message = message.ifBlank { raw.optString("message") },
        hasExpectedData = data != null && (data.has("exp") || data.has("goldCoin")),
        exp = data?.optInt("exp") ?: 0,
        goldCoin = data?.optInt("goldCoin") ?: 0
    )
}

internal fun CommunitySignDisposition.toOutcome(
    fallback: String,
    detail: String = ""
): CheckInOutcome = when (this) {
    CommunitySignDisposition.AUTH_EXPIRED -> CheckInOutcome.AuthenticationExpired(
        "塔吉多登录已失效，请重新连接。", "TAYGEDO_AUTH_EXPIRED"
    )
    CommunitySignDisposition.VERIFICATION_REQUIRED -> CheckInOutcome.ActionRequired(
        "塔吉多要求额外验证，请打开官方 App 处理。", "TAYGEDO_VERIFICATION"
    )
    CommunitySignDisposition.MALFORMED -> CheckInOutcome.PermanentFailure(
        "塔吉多返回了无法识别的社区签到结果，已停止后续操作。",
        "TAYGEDO_COMMUNITY_BAD_RESPONSE"
    )
    CommunitySignDisposition.FAILURE -> CheckInOutcome.TemporaryFailure(
        if (detail.isBlank()) fallback else "$fallback（服务返回：$detail）",
        "TAYGEDO_COMMUNITY_FAILURE"
    )
    CommunitySignDisposition.SUCCESS,
    CommunitySignDisposition.ALREADY_COMPLETED -> error("Positive sign-in result cannot be converted to failure")
}

internal data class NteSignState(
    val todaySigned: Boolean,
    val days: Int,
    val day: Int
)

internal fun parseNteSignState(data: Any?): NteSignState? {
    val json = data as? JSONObject ?: return null
    return parseNteSignStateFields(
        todaySigned = json.optBoolean("todaySign").takeIf { json.has("todaySign") },
        days = json.optInt("days").takeIf { json.has("days") },
        day = json.optInt("day").takeIf { json.has("day") }
    )
}

internal fun parseNteSignStateFields(
    todaySigned: Boolean?,
    days: Int?,
    day: Int?
): NteSignState? = if (todaySigned == null || days == null || day == null) {
    null
} else {
    NteSignState(todaySigned, days, day)
}

private fun failure(result: TaygedoClient.ApiResult, fallback: String): CheckInOutcome {
    val message = result.message.ifBlank { fallback }
    return when {
        result.code == 401 || result.code == -401 -> CheckInOutcome.AuthenticationExpired(
            "塔吉多登录已失效，请重新连接。", "TAYGEDO_AUTH_EXPIRED"
        )
        message.contains("验证") || message.contains("风控") ||
            message.contains("风险") || message.contains("captcha", ignoreCase = true) -> CheckInOutcome.ActionRequired(
            "塔吉多要求额外验证，请打开官方 App 处理。", "TAYGEDO_VERIFICATION"
        )
        else -> CheckInOutcome.TemporaryFailure(fallback, "TAYGEDO_${result.code}")
    }
}

internal fun mapTaygedoFailure(
    code: Int,
    message: String,
    fallback: String = "塔吉多请求失败。"
): CheckInOutcome = failure(
    TaygedoClient.ApiResult(code, null, message, JSONObject()),
    fallback
)
