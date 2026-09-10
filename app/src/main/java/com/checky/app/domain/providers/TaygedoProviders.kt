package com.checky.app.domain.providers

import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.AuthHealthStore
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.SmsLoginProvider
import com.checky.app.domain.SmsLoginResult
import com.checky.app.domain.SavedCredentialRevalidator
import com.checky.app.domain.SavedCredentialValidation
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.ConnectionType
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
import java.time.ZoneId
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import com.checky.app.domain.model.RiskLevel
import com.checky.app.domain.model.SupportStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// `browse_post_exp` is the current task key; retain the legacy key as a
// compatibility alias. The order is intentional so a response containing
// both aliases is counted once, using the current key rather than summing
// what may be two representations of the same task.
private val BROWSE_TASK_CODES = listOf("browse_post_exp", "browse_post_c")

internal fun isAllowedBrowseTaskCode(code: String): Boolean = code in BROWSE_TASK_CODES

internal fun nteRewardIndexForSignedDays(signedDays: Int): Int =
    signedDays.coerceAtLeast(1) - 1

private fun nteRewardForSignedDays(rewards: JSONArray?, signedDays: Int): JSONObject? =
    rewards?.optJSONObject(nteRewardIndexForSignedDays(signedDays))

abstract class TaygedoProvider(
    protected val client: TaygedoClient
) : CheckInProvider, SmsLoginProvider, SavedCredentialRevalidator {
    override val requiresCredentials = true
    override val credentialOwnerId: String = TaygedoClient.SESSION_KEY

    override suspend fun isSmsConnected(): Boolean = client.hasSession()

    override suspend fun revalidateSavedCredential(): SavedCredentialValidation = withContext(Dispatchers.IO) {
        when (client.revalidateSavedCredentialAuth()) {
            TaygedoClient.AuthRevalidation.ACCEPTED -> SavedCredentialValidation.Valid
            TaygedoClient.AuthRevalidation.EXPIRED -> SavedCredentialValidation.Expired
            TaygedoClient.AuthRevalidation.UNKNOWN ->
                SavedCredentialValidation.Unverified("塔吉多只读连接检查无法确认。")
        }
    }

    internal suspend fun recoverExpiredSession(authHealthStore: AuthHealthStore): Boolean =
        when (revalidateSavedCredential()) {
            SavedCredentialValidation.Valid -> {
                authHealthStore.set(credentialOwnerId, AuthHealth.VALID)
                true
            }
            else -> false
        }

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
        if (!isNteSignStateConfirmed(newState)) {
            return CheckInOutcome.PermanentFailure(
                "塔吉多返回了未确认签到成功的结果，已停止后续操作。",
                "TAYGEDO_NTE_BAD_RESULT"
            )
        }
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
            supportStatus = SupportStatus.SUPPORTED, allowedHosts = HOSTS,
            businessZone = ZoneId.of("Asia/Shanghai")
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
        val browse = runTaygedoBrowseTask { request ->
            client.request(
                meta,
                request.path,
                query = request.query,
                useDs = request.useDs,
                authV2 = request.authV2
            )
        }
        if (!browse.outcome.mayContinue) return browse.toOutcome()

        val like = runTaygedoLikeTask { request ->
            client.request(
                meta,
                request.path,
                request.method,
                query = request.query,
                form = request.form,
                useDs = request.useDs,
                authV2 = request.authV2,
                jsonBody = request.jsonBody
            )
        }
        if (!like.outcome.mayContinue) return like.toOutcome()

        val share = runTaygedoShareTask { request ->
            client.request(
                meta,
                request.path,
                request.method,
                query = request.query,
                form = request.form,
                useDs = request.useDs,
                authV2 = request.authV2,
                jsonBody = request.jsonBody
            )
        }
        if (!share.outcome.mayContinue) return share.toOutcome()

        // The automatic community flow has one deterministic BBS sign-in entry (communityId=2).
        // Treat an upstream "already signed" reply as positive so a repeat run is idempotent.
        val bbsSignin = runCommunitySignInSequence(
            readBbsState = client::getCommunityBbsSignState,
            signIn = { communityId ->
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
        ).singleOrNull() ?: return CheckInOutcome.PermanentFailure(
            "异环版区签到状态无法确认，未执行签到。",
            "TAYGEDO_COMMUNITY_BBS_STATE_UNKNOWN"
        )
        if (!bbsSignin.classification.mayContinue) {
            return bbsSignin.classification.toOutcome("异环社区版区签到失败。", bbsSignin.response.message)
        }

        val bbsLabel = if (bbsSignin.classification == CommunitySignDisposition.ALREADY_COMPLETED) {
            "版区今日已签到"
        } else {
            "版区签到成功"
        }
        val message = buildString {
            append("塔吉多社区：$bbsLabel。")
            append(" ").append(browse.toMessage()).append("。")
            append(" ").append(like.toMessage()).append("。")
            append(" ").append(share.toMessage()).append("。")
        }
        val already = browse.outcome == TaygedoBrowseOutcome.ALREADY_COMPLETED &&
            like.outcome == TaygedoLikeOutcome.ALREADY_COMPLETED &&
            share.outcome == TaygedoShareOutcome.ALREADY_COMPLETED &&
            bbsSignin.classification == CommunitySignDisposition.ALREADY_COMPLETED
        return if (already) {
            CheckInOutcome.AlreadyCompleted(message, "TAYGEDO_COMMUNITY_ALREADY")
        } else {
            CheckInOutcome.Success(
                message,
                "TAYGEDO_COMMUNITY_SUCCESS",
                Reward.empty()
            )
        }
    }

    /** Read task counters without performing like/share interactions. */
    /**
     * Read-only preflight: ask the official `getSignState` endpoint whether a
     * given community has already been signed today.
     *
     * Recovered contract (HBC 98, official 1.2.6):
     *   GET /apihub/api/getSignState?communityId=<id>
     *   headers: Authorization (access token) — official BearerAuthService contract
     * Fails closed: ambiguous or errored responses return UNKNOWN, which
     * prevents the subsequent sign-in mutation from being sent.
     */
    private suspend fun readCommunitySignState(communityId: String): CommunitySignState {
        return runCatching {
            client.request(
                meta,
                "/apihub/api/getSignState",
                query = mapOf("communityId" to communityId)
            ).toCommunitySignState()
        }.getOrElse { error ->
            if (error is TaygedoClient.AuthException) throw error
            CommunitySignState.UNKNOWN
        }
    }

    /** Complete only the read-only browse task from the official task flow. */
    companion object {
        val META = ProviderMeta(
            id = "taygedo_community", displayName = "塔吉多社区签到",
            description = "完成塔吉多 APP 与异环版区每日签到，并自动浏览与点赞任务；不自动分享。",
            category = "社区", iconKey = "star", accentColor = 0xFFEE7B45,
            isEnabledByDefault = false, connectionType = ConnectionType.HTTP_SESSION,
            riskLevel = RiskLevel.HIGH, credentialType = CredentialType.SESSION_TOKEN,
            supportStatus = SupportStatus.SUPPORTED, allowedHosts = HOSTS,
            businessZone = ZoneId.of("Asia/Shanghai")
        )
    }
}

internal enum class TaygedoBrowseStep {
    PRE_TASK_STATE,
    RECOMMEND_POSTS,
    POST_DETAIL,
    POST_TASK_STATE
}

internal data class TaygedoBrowseRequest(
    val step: TaygedoBrowseStep,
    val path: String,
    val query: Map<String, String>,
    val useDs: Boolean,
    val authV2: Boolean,
    val method: String = "GET"
)

internal fun taygedoBrowseRequest(step: TaygedoBrowseStep, postId: String = "") = when (step) {
    TaygedoBrowseStep.PRE_TASK_STATE,
    TaygedoBrowseStep.POST_TASK_STATE -> TaygedoBrowseRequest(
        step,
        "/apihub/api/getUserTasks",
        mapOf("gid" to "1"),
        useDs = true,
        authV2 = false
    )
    TaygedoBrowseStep.RECOMMEND_POSTS -> TaygedoBrowseRequest(
        step,
        "/bbs/api/getRecommendPostList",
        mapOf("communityId" to "2", "count" to "20", "page" to "1"),
        useDs = true,
        authV2 = false
    )
    TaygedoBrowseStep.POST_DETAIL -> TaygedoBrowseRequest(
        step,
        "/bbs/api/getPostFull",
        mapOf("postId" to postId),
        useDs = true,
        authV2 = false
    )
}

internal data class TaygedoBrowseTaskState(
    val complete: Int,
    val limit: Int
) {
    val remaining: Int get() = (limit - complete).coerceAtLeast(0)
}

internal fun parseTaygedoBrowseTaskState(
    result: TaygedoClient.ApiResult
): TaygedoBrowseTaskState? {
    if (!result.isKnownTaygedoReadSuccess()) return null
    val data = result.data as? JSONObject ?: return null
    val taskList = data.opt("task_list1") as? JSONArray ?: return null
    var browse: TaygedoBrowseTaskState? = null

    for (index in 0 until taskList.length()) {
        val task = taskList.optJSONObject(index) ?: continue
        val taskKeyValue = task.opt("taskKey")
        val codeValue = task.opt("code")
        val taskKey = (task.opt("taskKey") as? String)?.trim()
        val code = (task.opt("code") as? String)?.trim()
        val taskClaimsBrowse = taskKey == "browse_post_c"
        val codeClaimsBrowse = code == "browse_post_c"
        if (!taskClaimsBrowse && !codeClaimsBrowse) continue
        val otherValue = if (taskClaimsBrowse) codeValue else taskKeyValue
        val other = if (otherValue == null) null else otherValue as? String
        if (otherValue != null && other == null) return null
        if (!other.isNullOrBlank() && other != "browse_post_c") return null
        if (browse != null) return null
        val complete = taygedoNonNegativeInt(task.opt("completeTimes")) ?: return null
        val limit = taygedoNonNegativeInt(task.opt("limitTimes")) ?: return null
        if (limit == 0 || complete > limit) return null
        browse = TaygedoBrowseTaskState(complete, limit)
    }
    return browse
}

private fun taygedoNonNegativeInt(value: Any?): Int? {
    val number = value as? Number ?: return null
    val double = number.toDouble()
    return if (double.isFinite() && double % 1.0 == 0.0 && double >= 0.0 &&
        double <= Int.MAX_VALUE
    ) double.toInt() else null
}

internal fun parseTaygedoBrowsePostIds(
    result: TaygedoClient.ApiResult
): List<String>? {
    if (!result.isKnownTaygedoReadSuccess()) return null
    val posts = when (val data = result.data) {
        is JSONArray -> data
        is JSONObject -> {
            val containers = listOf("list", "posts").filter(data::has)
            if (containers.size != 1) return null
            data.optJSONArray(containers.single()) ?: return null
        }
        else -> return null
    }

    val ids = linkedSetOf<String>()
    for (index in 0 until posts.length()) {
        val post = posts.optJSONObject(index) ?: return null
        val fields = listOf("postId", "id").filter(post::has)
        if (fields.isEmpty()) continue
        val postIds = fields.map { taygedoPostId(post.opt(it)) }
        if (postIds.any { it == null }) continue
        val distinct = postIds.filterNotNull().distinct()
        if (distinct.size != 1) continue
        ids += distinct.single()
    }
    return ids.toList()
}

private fun taygedoPostId(value: Any?): String? {
    val text = when (value) {
        is String -> value.trim()
        is Number -> {
            val double = value.toDouble()
            if (!double.isFinite() || double < 0.0 || double % 1.0 != 0.0 ||
                double > Long.MAX_VALUE
            ) return null
            double.toLong().toString()
        }
        else -> return null
    }
    return text.takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
}

private fun isKnownTaygedoBrowseDetail(result: TaygedoClient.ApiResult): Boolean =
    result.isKnownTaygedoReadSuccess()

private fun TaygedoClient.ApiResult.isKnownTaygedoReadSuccess(): Boolean {
    if (httpStatus !in 200..299 || code != 0) return false
    val text = listOf(message, raw.optString("message"), raw.optString("msg"))
        .joinToString(" ")
        .lowercase()
    return listOf(
        "captcha", "verification", "verify", "risk", "recaptcha", "challenge",
        "风控", "验证", "验证码"
    ).none(text::contains)
}

internal enum class TaygedoBrowseOutcome {
    ALREADY_COMPLETED,
    COMPLETED,
    STOPPED_PRE_STATE_UNKNOWN,
    STOPPED_RECOMMEND_UNKNOWN,
    STOPPED_INSUFFICIENT_POSTS,
    STOPPED_DETAIL_FAILURE,
    STOPPED_POST_STATE_UNKNOWN,
    STOPPED_POST_STATE_INCOMPLETE
}

internal data class TaygedoBrowseRun(
    val before: TaygedoBrowseTaskState?,
    val after: TaygedoBrowseTaskState?,
    val detailPostIds: List<String>,
    val outcome: TaygedoBrowseOutcome
) {
    fun toMessage(): String = when (outcome) {
        TaygedoBrowseOutcome.ALREADY_COMPLETED -> "浏览任务今日已完成"
        TaygedoBrowseOutcome.COMPLETED -> "自动浏览 ${detailPostIds.size} 篇帖子"
        else -> "浏览任务状态未确认"
    }

    fun toOutcome(): CheckInOutcome = when (outcome) {
        TaygedoBrowseOutcome.STOPPED_POST_STATE_INCOMPLETE -> CheckInOutcome.PermanentFailure(
            "塔吉多浏览任务仅确认了部分完成，已停止后续操作。",
            "TAYGEDO_COMMUNITY_BROWSE_INCOMPLETE"
        )
        TaygedoBrowseOutcome.ALREADY_COMPLETED,
        TaygedoBrowseOutcome.COMPLETED -> error("Positive browse result cannot be converted to failure")
        else -> CheckInOutcome.PermanentFailure(
            "塔吉多浏览任务状态或结果无法确认，已停止后续操作。",
            "TAYGEDO_COMMUNITY_BROWSE_UNKNOWN"
        )
    }
}

internal val TaygedoBrowseOutcome.mayContinue: Boolean
    get() = this == TaygedoBrowseOutcome.ALREADY_COMPLETED ||
        this == TaygedoBrowseOutcome.COMPLETED

internal suspend fun runTaygedoBrowseTask(
    read: suspend (TaygedoBrowseRequest) -> TaygedoClient.ApiResult
): TaygedoBrowseRun {
    suspend fun readOrNull(request: TaygedoBrowseRequest): TaygedoClient.ApiResult? = try {
        read(request)
    } catch (error: TaygedoClient.AuthException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    val before = readOrNull(taygedoBrowseRequest(TaygedoBrowseStep.PRE_TASK_STATE))
        ?.let(::parseTaygedoBrowseTaskState)
        ?: return TaygedoBrowseRun(
            null,
            null,
            emptyList(),
            TaygedoBrowseOutcome.STOPPED_PRE_STATE_UNKNOWN
        )
    if (before.remaining == 0) {
        return TaygedoBrowseRun(
            before,
            null,
            emptyList(),
            TaygedoBrowseOutcome.ALREADY_COMPLETED
        )
    }

    val postIds = readOrNull(taygedoBrowseRequest(TaygedoBrowseStep.RECOMMEND_POSTS))
        ?.let(::parseTaygedoBrowsePostIds)
        ?: return TaygedoBrowseRun(
            before,
            null,
            emptyList(),
            TaygedoBrowseOutcome.STOPPED_RECOMMEND_UNKNOWN
        )
    val boundedPostIds = postIds.take(before.remaining)
    if (boundedPostIds.isEmpty()) {
        return TaygedoBrowseRun(
            before,
            null,
            emptyList(),
            TaygedoBrowseOutcome.STOPPED_INSUFFICIENT_POSTS
        )
    }

    val detailPostIds = mutableListOf<String>()
    for (postId in boundedPostIds) {
        detailPostIds += postId
        val detail = readOrNull(taygedoBrowseRequest(TaygedoBrowseStep.POST_DETAIL, postId))
        if (detail == null || !isKnownTaygedoBrowseDetail(detail)) {
            return TaygedoBrowseRun(
                before,
                null,
                detailPostIds,
                TaygedoBrowseOutcome.STOPPED_DETAIL_FAILURE
            )
        }
    }

    val after = readOrNull(taygedoBrowseRequest(TaygedoBrowseStep.POST_TASK_STATE))
        ?.let(::parseTaygedoBrowseTaskState)
        ?: return TaygedoBrowseRun(
            before,
            null,
            detailPostIds,
            TaygedoBrowseOutcome.STOPPED_POST_STATE_UNKNOWN
        )
    if (after.limit != before.limit || after.complete < before.complete) {
        return TaygedoBrowseRun(
            before,
            after,
            detailPostIds,
            TaygedoBrowseOutcome.STOPPED_POST_STATE_UNKNOWN
        )
    }
    return TaygedoBrowseRun(
        before,
        after,
        detailPostIds,
        if (after.remaining == 0) {
            TaygedoBrowseOutcome.COMPLETED
        } else {
            TaygedoBrowseOutcome.STOPPED_POST_STATE_INCOMPLETE
        }
    )
}

internal enum class TaygedoLikeStep {
    PRE_TASK_STATE,
    RECOMMENDATIONS,
    POST_DETAIL,
    LIKE,
    POST_TASK_STATE
}

internal data class TaygedoLikeRequest(
    val step: TaygedoLikeStep,
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val form: Map<String, String> = emptyMap(),
    val useDs: Boolean,
    val authV2: Boolean,
    val jsonBody: Boolean = false
)

internal fun taygedoLikeRequest(step: TaygedoLikeStep, postId: String = "") = when (step) {
    TaygedoLikeStep.PRE_TASK_STATE,
    TaygedoLikeStep.POST_TASK_STATE -> TaygedoLikeRequest(
        step,
        "GET",
        "/apihub/api/getUserTasks",
        mapOf("gid" to "1"),
        useDs = true,
        authV2 = false
    )
    TaygedoLikeStep.RECOMMENDATIONS -> TaygedoLikeRequest(
        step,
        "GET",
        "/bbs/api/getRecommendPostList",
        mapOf("communityId" to "2", "count" to "20", "page" to "1"),
        useDs = true,
        authV2 = false
    )
    TaygedoLikeStep.POST_DETAIL -> TaygedoLikeRequest(
        step,
        "GET",
        "/bbs/api/getPostFull",
        query = mapOf("postId" to postId),
        useDs = true,
        authV2 = false
    )
    TaygedoLikeStep.LIKE -> TaygedoLikeRequest(
        step,
        "POST",
        "/bbs/api/post/like",
        form = mapOf("postId" to postId),
        useDs = true,
        authV2 = false,
        jsonBody = true
    )
}

internal data class TaygedoLikeTaskState(
    val complete: Int,
    val limit: Int
) {
    val remaining: Int get() = (limit - complete).coerceAtLeast(0)
}

internal data class TaygedoLikeCandidate(val postId: String, val liked: Boolean?)

internal enum class TaygedoLikeOutcome {
    ALREADY_COMPLETED,
    COMPLETED,
    STOPPED_PRE_STATE_UNKNOWN,
    STOPPED_RECOMMEND_UNKNOWN,
    STOPPED_INCOMPLETE,
    STOPPED_MUTATION_FAILURE,
    STOPPED_MUTATION_UNCERTAIN,
    STOPPED_POST_STATE_UNKNOWN,
    STOPPED_POST_STATE_INCOMPLETE
}

internal data class TaygedoLikeRun(
    val before: TaygedoLikeTaskState?,
    val after: TaygedoLikeTaskState?,
    val likedPostIds: List<String>,
    val outcome: TaygedoLikeOutcome
) {
    fun toMessage(): String = when (outcome) {
        TaygedoLikeOutcome.ALREADY_COMPLETED -> "点赞任务今日已完成"
        TaygedoLikeOutcome.COMPLETED -> "自动点赞 ${likedPostIds.size} 篇帖子"
        else -> "点赞任务未完成"
    }

    fun toOutcome(): CheckInOutcome = when (outcome) {
        TaygedoLikeOutcome.ALREADY_COMPLETED,
        TaygedoLikeOutcome.COMPLETED -> error("Positive like result cannot be converted to failure")
        else -> CheckInOutcome.PermanentFailure(
            "塔吉多点赞任务状态或结果无法确认，已停止后续操作。",
            "TAYGEDO_COMMUNITY_LIKE_UNKNOWN"
        )
    }
}

internal val TaygedoLikeOutcome.mayContinue: Boolean
    get() = this == TaygedoLikeOutcome.ALREADY_COMPLETED ||
        this == TaygedoLikeOutcome.COMPLETED

internal fun parseTaygedoLikeTaskState(
    result: TaygedoClient.ApiResult
): TaygedoLikeTaskState? {
    if (!result.isKnownTaygedoReadSuccess()) return null
    val data = result.data as? JSONObject ?: return null
    val taskList = data.opt("task_list1") as? JSONArray ?: return null
    var like: TaygedoLikeTaskState? = null
    for (index in 0 until taskList.length()) {
        val task = taskList.optJSONObject(index) ?: continue
        val taskKeyValue = task.opt("taskKey")
        val codeValue = task.opt("code")
        val taskKey = (taskKeyValue as? String)?.trim()
        val code = (codeValue as? String)?.trim()
        val taskCode = taskKey?.takeIf { it.isNotBlank() } ?: code
        if (taskCode != "like_post_c") continue
        if ((taskKeyValue != null && taskKey == null) ||
            (codeValue != null && code == null)
        ) return null
        if (like != null) return null
        val complete = taygedoNonNegativeInt(task.opt("completeTimes")) ?: return null
        val limit = taygedoNonNegativeInt(task.opt("limitTimes")) ?: return null
        if (limit == 0 || complete > limit) return null
        like = TaygedoLikeTaskState(complete, limit)
    }
    return like
}

private fun parseTaygedoLikeCandidates(
    result: TaygedoClient.ApiResult
): List<TaygedoLikeCandidate>? {
    if (!result.isKnownTaygedoReadSuccess()) return null
    val posts = when (val data = result.data) {
        is JSONArray -> data
        is JSONObject -> {
            val containers = listOf("list", "posts").filter(data::has)
            if (containers.size != 1) return null
            data.optJSONArray(containers.single()) ?: return null
        }
        else -> return null
    }
    val candidates = mutableListOf<TaygedoLikeCandidate>()
    for (index in 0 until posts.length()) {
        val post = posts.optJSONObject(index) ?: return null
        val postId = taygedoLikePostId(post) ?: return null
        candidates += TaygedoLikeCandidate(postId, taygedoExplicitLiked(post))
    }
    return candidates
}

private fun parseTaygedoLikeDetail(
    result: TaygedoClient.ApiResult,
    postId: String
): TaygedoLikeCandidate? {
    if (!result.isKnownTaygedoReadSuccess()) return null
    val data = result.data as? JSONObject ?: return null
    val post = when {
        data.has("selfOperation") -> data
        data.opt("post") is JSONObject -> data.optJSONObject("post")
        data.opt("postInfo") is JSONObject -> data.optJSONObject("postInfo")
        else -> null
    } ?: return null
    return TaygedoLikeCandidate(postId, taygedoExplicitLiked(post))
}

private fun taygedoLikePostId(post: JSONObject): String? {
    val values = listOf("postId", "id").filter(post::has).map { key ->
        (post.opt(key) as? String)?.trim()
    }
    if (values.any { it == null || it.isEmpty() || it.length > 128 }) return null
    return values.filterNotNull().distinct().singleOrNull()
}

private fun taygedoExplicitLiked(post: JSONObject): Boolean? =
    (post.opt("selfOperation") as? JSONObject)?.opt("liked") as? Boolean

private fun isExplicitTaygedoLikeSuccess(result: TaygedoClient.ApiResult): Boolean =
    result.isKnownTaygedoReadSuccess() && result.raw.has("code")

internal suspend fun runTaygedoLikeTask(
    read: suspend (TaygedoLikeRequest) -> TaygedoClient.ApiResult
): TaygedoLikeRun {
    suspend fun readOrNull(request: TaygedoLikeRequest): TaygedoClient.ApiResult? = try {
        read(request)
    } catch (error: TaygedoClient.AuthException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    val before = readOrNull(taygedoLikeRequest(TaygedoLikeStep.PRE_TASK_STATE))
        ?.let(::parseTaygedoLikeTaskState)
        ?: return TaygedoLikeRun(
            null,
            null,
            emptyList(),
            TaygedoLikeOutcome.STOPPED_PRE_STATE_UNKNOWN
        )
    if (before.remaining == 0) {
        return TaygedoLikeRun(
            before,
            null,
            emptyList(),
            TaygedoLikeOutcome.ALREADY_COMPLETED
        )
    }

    val candidates = readOrNull(taygedoLikeRequest(TaygedoLikeStep.RECOMMENDATIONS))
        ?.let(::parseTaygedoLikeCandidates)
        ?: return TaygedoLikeRun(
            before,
            null,
            emptyList(),
            TaygedoLikeOutcome.STOPPED_RECOMMEND_UNKNOWN
        )
    val targetCount = before.remaining.coerceAtMost(5)
    val seen = mutableSetOf<String>()
    val eligible = mutableListOf<String>()
    for (candidate in candidates) {
        if (!seen.add(candidate.postId)) continue
        val unliked = when (candidate.liked) {
            true -> false
            false -> true
            null -> readOrNull(taygedoLikeRequest(TaygedoLikeStep.POST_DETAIL, candidate.postId))
                ?.let { parseTaygedoLikeDetail(it, candidate.postId)?.liked == false }
                ?: false
        }
        if (unliked) eligible += candidate.postId
        if (eligible.size == targetCount) break
    }
    if (eligible.isEmpty()) {
        return TaygedoLikeRun(
            before,
            null,
            emptyList(),
            TaygedoLikeOutcome.STOPPED_INCOMPLETE
        )
    }

    val likedPostIds = mutableListOf<String>()
    for (postId in eligible.take(targetCount)) {
        val response = try {
            read(taygedoLikeRequest(TaygedoLikeStep.LIKE, postId))
        } catch (error: TaygedoClient.AuthException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return TaygedoLikeRun(
                before,
                null,
                likedPostIds,
                TaygedoLikeOutcome.STOPPED_MUTATION_UNCERTAIN
            )
        }
        if (!isExplicitTaygedoLikeSuccess(response)) {
            return TaygedoLikeRun(
                before,
                null,
                likedPostIds,
                TaygedoLikeOutcome.STOPPED_MUTATION_FAILURE
            )
        }
        likedPostIds += postId
    }

    val after = readOrNull(taygedoLikeRequest(TaygedoLikeStep.POST_TASK_STATE))
        ?.let(::parseTaygedoLikeTaskState)
        ?: return TaygedoLikeRun(
            before,
            null,
            likedPostIds,
            TaygedoLikeOutcome.STOPPED_POST_STATE_UNKNOWN
        )
    if (after.limit != before.limit || after.complete < before.complete) {
        return TaygedoLikeRun(
            before,
            after,
            likedPostIds,
            TaygedoLikeOutcome.STOPPED_POST_STATE_UNKNOWN
        )
    }
    return TaygedoLikeRun(
        before,
        after,
        likedPostIds,
        if (after.remaining == 0) {
            TaygedoLikeOutcome.COMPLETED
        } else {
            TaygedoLikeOutcome.STOPPED_POST_STATE_INCOMPLETE
        }
    )
}

internal enum class TaygedoShareStep {
    PRE_TASK_STATE,
    RECOMMENDATIONS,
    SHARE,
    POST_TASK_STATE
}

internal data class TaygedoShareRequest(
    val step: TaygedoShareStep,
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val form: Map<String, String> = emptyMap(),
    val useDs: Boolean,
    val authV2: Boolean,
    val jsonBody: Boolean = false
)

internal fun taygedoShareRequest(step: TaygedoShareStep, postId: String = "") = when (step) {
    TaygedoShareStep.PRE_TASK_STATE,
    TaygedoShareStep.POST_TASK_STATE -> TaygedoShareRequest(
        step,
        "GET",
        "/apihub/api/getUserTasks",
        mapOf("gid" to "1"),
        useDs = true,
        authV2 = false
    )
    TaygedoShareStep.RECOMMENDATIONS -> TaygedoShareRequest(
        step,
        "GET",
        "/bbs/api/getRecommendPostList",
        mapOf("communityId" to "2", "count" to "20", "page" to "1"),
        useDs = true,
        authV2 = false
    )
    TaygedoShareStep.SHARE -> TaygedoShareRequest(
        step,
        "POST",
        "/bbs/api/post/share",
        form = mapOf("platform" to "qq", "postId" to postId),
        useDs = true,
        authV2 = false
    )
}

internal data class TaygedoShareTaskState(
    val complete: Int,
    val limit: Int
) {
    val remaining: Int get() = (limit - complete).coerceAtLeast(0)
}

internal enum class TaygedoShareOutcome {
    ALREADY_COMPLETED,
    COMPLETED,
    STOPPED_PRE_STATE_UNKNOWN,
    STOPPED_RECOMMEND_UNKNOWN,
    STOPPED_INCOMPLETE,
    STOPPED_MUTATION_FAILURE,
    STOPPED_MUTATION_UNCERTAIN,
    STOPPED_POST_STATE_UNKNOWN,
    STOPPED_POST_STATE_INCOMPLETE
}

internal data class TaygedoShareRun(
    val before: TaygedoShareTaskState?,
    val after: TaygedoShareTaskState?,
    val sharedPostId: String?,
    val outcome: TaygedoShareOutcome,
    val failureEvidence: TaygedoShareFailureEvidence? = null
) {
    fun toMessage(): String = when (outcome) {
        TaygedoShareOutcome.ALREADY_COMPLETED -> "分享任务今日已完成"
        TaygedoShareOutcome.COMPLETED -> "自动分享 1 篇帖子"
        else -> "分享任务未完成"
    }

    fun toOutcome(): CheckInOutcome = when (outcome) {
        TaygedoShareOutcome.ALREADY_COMPLETED,
        TaygedoShareOutcome.COMPLETED -> error("Positive share result cannot be converted to failure")
        else -> CheckInOutcome.PermanentFailure(
            "塔吉多分享任务状态或结果无法确认，已停止后续操作。",
            "TAYGEDO_COMMUNITY_SHARE_UNKNOWN"
        )
    }
}

internal data class TaygedoShareFailureEvidence(
    val httpStatus: Int,
    val businessCode: Int?,
    val message: String?
)

internal val TaygedoShareOutcome.mayContinue: Boolean
    get() = this == TaygedoShareOutcome.ALREADY_COMPLETED ||
        this == TaygedoShareOutcome.COMPLETED

internal fun parseTaygedoShareTaskState(
    result: TaygedoClient.ApiResult
): TaygedoShareTaskState? {
    if (!result.isKnownTaygedoReadSuccess()) return null
    val data = result.data as? JSONObject ?: return null
    val taskList = data.opt("task_list1") as? JSONArray ?: return null
    var share: TaygedoShareTaskState? = null
    for (index in 0 until taskList.length()) {
        val task = taskList.optJSONObject(index) ?: continue
        val taskKeyValue = task.opt("taskKey")
        val codeValue = task.opt("code")
        val taskKey = (taskKeyValue as? String)?.trim()
        val code = (codeValue as? String)?.trim()
        val taskCode = taskKey?.takeIf { it.isNotBlank() } ?: code
        if (taskCode != "share") continue
        if ((taskKeyValue != null && taskKey == null) ||
            (codeValue != null && code == null)
        ) return null
        if (share != null) return null
        val complete = taygedoNonNegativeInt(task.opt("completeTimes")) ?: return null
        val limit = taygedoNonNegativeInt(task.opt("limitTimes")) ?: return null
        if (limit == 0 || complete > limit) return null
        share = TaygedoShareTaskState(complete, limit)
    }
    return share
}

private fun isExplicitTaygedoShareSuccess(result: TaygedoClient.ApiResult): Boolean =
    result.isKnownTaygedoReadSuccess() && result.raw.has("code")

private fun TaygedoClient.ApiResult.toTaygedoShareFailureEvidence() =
    TaygedoShareFailureEvidence(
        httpStatus = httpStatus,
        businessCode = if (raw.has("code")) taygedoDiagnosticInt(raw.opt("code")) else null,
        message = taygedoDiagnosticMessage(message)
    )

private fun taygedoDiagnosticInt(value: Any?): Int? {
    if (value is String) return value.trim().toIntOrNull()
    val number = value as? Number ?: return null
    val double = number.toDouble()
    return if (double.isFinite() && double % 1.0 == 0.0 &&
        double >= Int.MIN_VALUE && double <= Int.MAX_VALUE
    ) double.toInt() else null
}

private fun taygedoDiagnosticMessage(value: String): String? {
    val compact = value.replace(Regex("\\s+"), " ").trim()
    if (compact.isBlank() || compact.length > 160) return null
    if (Regex(
            "(?i)(access[_-]?token|refresh[_-]?token|authorization|cookie|stoken|ltoken|device(id)?|uid)\\s*[:=]|bearer\\s+"
        ).containsMatchIn(compact)
    ) return null
    return compact
}

internal suspend fun runTaygedoShareTask(
    read: suspend (TaygedoShareRequest) -> TaygedoClient.ApiResult
): TaygedoShareRun {
    suspend fun readOrNull(request: TaygedoShareRequest): TaygedoClient.ApiResult? = try {
        read(request)
    } catch (error: TaygedoClient.AuthException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    val before = readOrNull(taygedoShareRequest(TaygedoShareStep.PRE_TASK_STATE))
        ?.let(::parseTaygedoShareTaskState)
        ?: return TaygedoShareRun(
            null,
            null,
            null,
            TaygedoShareOutcome.STOPPED_PRE_STATE_UNKNOWN
        )
    if (before.remaining == 0) {
        return TaygedoShareRun(
            before,
            null,
            null,
            TaygedoShareOutcome.ALREADY_COMPLETED
        )
    }

    val postId = readOrNull(taygedoShareRequest(TaygedoShareStep.RECOMMENDATIONS))
        ?.let(::parseTaygedoBrowsePostIds)
        ?.firstOrNull()
        ?: return TaygedoShareRun(
            before,
            null,
            null,
            TaygedoShareOutcome.STOPPED_RECOMMEND_UNKNOWN
        )

    val response = try {
        read(taygedoShareRequest(TaygedoShareStep.SHARE, postId))
    } catch (error: TaygedoClient.AuthException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return TaygedoShareRun(
            before,
            null,
            postId,
            TaygedoShareOutcome.STOPPED_MUTATION_UNCERTAIN
        )
    }
    if (!isExplicitTaygedoShareSuccess(response)) {
        return TaygedoShareRun(
            before,
            null,
            postId,
            TaygedoShareOutcome.STOPPED_MUTATION_FAILURE,
            failureEvidence = response.toTaygedoShareFailureEvidence()
        )
    }

    val after = readOrNull(taygedoShareRequest(TaygedoShareStep.POST_TASK_STATE))
        ?.let(::parseTaygedoShareTaskState)
        ?: return TaygedoShareRun(
            before,
            null,
            postId,
            TaygedoShareOutcome.STOPPED_POST_STATE_UNKNOWN
        )
    if (after.limit != before.limit || after.complete < before.complete) {
        return TaygedoShareRun(
            before,
            after,
            postId,
            TaygedoShareOutcome.STOPPED_POST_STATE_UNKNOWN
        )
    }
    return TaygedoShareRun(
        before,
        after,
        postId,
        if (after.remaining == 0) {
            TaygedoShareOutcome.COMPLETED
        } else {
            TaygedoShareOutcome.STOPPED_POST_STATE_INCOMPLETE
        }
    )
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
    fun remaining(codes: List<String>): Int = codes.firstNotNullOfOrNull(::remaining) ?: 0
    // Unknown task types are intentionally ignored (no automatic action).
    return CommunityTaskState(
        browseRemaining = remaining(BROWSE_TASK_CODES),
        likeRemaining = remaining("like_post_c") ?: 0,
        shareRemaining = remaining("share") ?: 0
    )
}

/** Validated, non-sensitive daily coin counters. */
internal data class TaygedoCoinState(val todayCoin: Int, val limitCoin: Int)

internal sealed interface TaygedoCoinStateResult {
    data class Known(val state: TaygedoCoinState) : TaygedoCoinStateResult
    data object Unknown : TaygedoCoinStateResult
}

/**
 * Parse the read-only getUserCoinTaskState response. Both counters are
 * required; malformed, missing, negative, fractional, or string values are
 * deliberately indistinguishable from an unavailable state.
 */
internal fun parseTaygedoCoinState(result: TaygedoClient.ApiResult): TaygedoCoinStateResult {
    if (result.code != 0) return TaygedoCoinStateResult.Unknown
    val data = result.data as? JSONObject ?: return TaygedoCoinStateResult.Unknown

    fun nonNegativeInt(name: String): Int? {
        val value = data.opt(name) as? Number ?: return null
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 || number < 0 || number > Int.MAX_VALUE) {
            return null
        }
        return number.toInt()
    }

    val todayCoin = nonNegativeInt("todayCoin") ?: return TaygedoCoinStateResult.Unknown
    val limitCoin = nonNegativeInt("limitCoin") ?: return TaygedoCoinStateResult.Unknown
    return TaygedoCoinStateResult.Known(TaygedoCoinState(todayCoin, limitCoin))
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
    MALFORMED,
    PREFLIGHT_UNKNOWN
}

/**
 * Read-only sign-state classification. The official `getSignState` response
 * schema is not pinned down by static analysis, so we only recognise an
 * unambiguous signed/unsigned flag. Anything else (error, missing data,
 * unrecognised field) is UNKNOWN and must NOT drive a mutation.
 */
internal enum class CommunitySignState { SIGNED, UNSIGNED, UNKNOWN }

internal fun TaygedoClient.ApiResult.toCommunitySignState(): CommunitySignState {
    if (!isKnownTaygedoReadSuccess()) return CommunitySignState.UNKNOWN
    val dataValue = data
    if (dataValue is Boolean) {
        return if (dataValue) CommunitySignState.SIGNED else CommunitySignState.UNSIGNED
    }
    val data = dataValue as? JSONObject ?: return CommunitySignState.UNKNOWN
    val fields = listOf("isSign", "signed", "todaySign", "signState", "status", "sign")
        .filter(data::has)
    if (fields.isEmpty()) return CommunitySignState.UNKNOWN

    val states = fields.mapNotNull { field ->
        val value = data.opt(field)
        when (field) {
            "isSign", "signed", "todaySign" -> (value as? Boolean)?.let {
                if (it) CommunitySignState.SIGNED else CommunitySignState.UNSIGNED
            }
            else -> (value as? Number)?.toDouble()?.let {
                when (it) {
                    1.0 -> CommunitySignState.SIGNED
                    0.0 -> CommunitySignState.UNSIGNED
                    else -> null
                }
            }
        }
    }
    if (states.size != fields.size || states.distinct().size != 1) return CommunitySignState.UNKNOWN
    return states.single()
}

/** Parse gid=1/task_list1/signin_c, the independent BBS sign-in preflight. */
internal fun TaygedoClient.ApiResult.toCommunityTaskSignState(): CommunitySignState {
    if (code != 0) return CommunitySignState.UNKNOWN
    val data = data as? JSONObject ?: return CommunitySignState.UNKNOWN
    val tasks = data.opt("task_list1") as? JSONArray ?: return CommunitySignState.UNKNOWN
    val signIns = (0 until tasks.length()).mapNotNull { tasks.optJSONObject(it) }
        .filter { it.optString("taskKey") == "signin_c" }
    if (signIns.size != 1) return CommunitySignState.UNKNOWN

    fun nonNegativeInt(name: String): Int? {
        val value = signIns.single().opt(name) as? Number ?: return null
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 || number < 0 || number > Int.MAX_VALUE) return null
        return number.toInt()
    }

    val completeTimes = nonNegativeInt("completeTimes") ?: return CommunitySignState.UNKNOWN
    val limitTimes = nonNegativeInt("limitTimes") ?: return CommunitySignState.UNKNOWN
    if (limitTimes == 0) return CommunitySignState.UNKNOWN
    return if (completeTimes >= limitTimes) CommunitySignState.SIGNED
    else CommunitySignState.UNSIGNED
}

internal data class CommunitySignResponse(
    val code: Int,
    val message: String,
    val hasExpectedData: Boolean,
    val exp: Int = 0,
    val goldCoin: Int = 0,
    val preflightState: CommunitySignState? = null
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
    if (response.preflightState == CommunitySignState.UNKNOWN) {
        return CommunitySignDisposition.PREFLIGHT_UNKNOWN
    }
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
    readBbsState: suspend () -> CommunitySignState,
    signIn: suspend (communityId: String) -> CommunitySignResponse
): List<CommunitySignCall> {
    val calls = mutableListOf<CommunitySignCall>()
    for (communityId in listOf("2")) {
        val state = readBbsState()
        val response = when (state) {
            CommunitySignState.SIGNED -> CommunitySignResponse(
                code = 0,
                message = "今日已签到",
                hasExpectedData = true
            )
            CommunitySignState.UNSIGNED -> signIn(communityId)
            CommunitySignState.UNKNOWN -> CommunitySignResponse(
                code = 0,
                message = "无法确认今日签到状态，未执行签到",
                hasExpectedData = false,
                preflightState = CommunitySignState.UNKNOWN
            )
        }
        val classification = classifyCommunitySignResponse(response)
        calls += CommunitySignCall(communityId, response, classification)
        if (!classification.mayContinue) break
    }
    return calls
}

internal fun TaygedoClient.ApiResult.toCommunitySignResponse(): CommunitySignResponse {
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
    CommunitySignDisposition.PREFLIGHT_UNKNOWN -> CheckInOutcome.PermanentFailure(
        "塔吉多社区签到状态无法确认，未执行签到，请在官方 App 核实后再试。",
        "TAYGEDO_COMMUNITY_STATE_UNKNOWN"
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

internal fun isNteSignStateConfirmed(state: NteSignState): Boolean = state.todaySigned

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
