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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
            webHeaders = true, useDs = true
        )
        if (state.code != 0) return failure(state, "无法查询异环签到状态。")
        val stateData = state.data as? JSONObject
        if (stateData?.optBoolean("todaySign") == true) {
            return CheckInOutcome.AlreadyCompleted(
                "异环今天已经签到（本月累计 ${stateData.optInt("days")} 天）。", "TAYGEDO_NTE_ALREADY"
            )
        }
        val signed = client.request(
            meta, "/apihub/awapi/sign", "POST",
            form = mapOf("roleId" to roleId, "gameId" to "1289"), webHeaders = true, useDs = true
        )
        if (signed.code != 0) return failure(signed, "异环签到失败。")
        val newState = client.request(
            meta, "/apihub/awapi/signin/state", query = mapOf("gameId" to "1289"),
            webHeaders = true, useDs = true
        ).data as? JSONObject
        val rewards = client.request(
            meta, "/apihub/awapi/sign/rewards",
            query = mapOf("gameId" to "1289", "roleId" to roleId), webHeaders = true, useDs = true
        ).data as? JSONArray
        val day = (newState?.optInt("day") ?: 1).coerceAtLeast(1)
        val reward = rewards?.optJSONObject(day - 1)
        val rewardText = reward?.let { "，获得 ${it.optString("name")} ×${it.optInt("num")}" }.orEmpty()
        return CheckInOutcome.Success(
            "异环签到成功（本月累计 ${newState?.optInt("days") ?: day} 天）$rewardText。",
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
        val state = client.request(meta, "/apihub/api/getSignState", query = mapOf("communityId" to "2"))
        if (state.code != 0) return failure(state, "无法查询异环社区签到状态。")
        if (state.data == true) return CheckInOutcome.AlreadyCompleted(
            "异环社区今天已经签到。", "TAYGEDO_COMMUNITY_ALREADY"
        )
        val signed = client.request(
            meta, "/apihub/api/signin", "POST", form = mapOf("communityId" to "2")
        )
        if (signed.code != 0) return failure(signed, "异环社区签到失败。")
        val data = signed.data as? JSONObject
        val exp = data?.optInt("exp") ?: 0
        val coin = data?.optInt("goldCoin") ?: 0
        return CheckInOutcome.Success(
            "异环社区签到成功：经验 +$exp，金币 +$coin。",
            "TAYGEDO_COMMUNITY_SUCCESS", Reward(RewardType.EXPERIENCE, exp)
        )
    }

    companion object {
        val META = ProviderMeta(
            id = "taygedo_community", displayName = "塔吉多社区签到",
            description = "仅完成异环社区每日签到，不发帖、不点赞、不评论。",
            category = "社区", iconKey = "star", accentColor = 0xFFEE7B45,
            isEnabledByDefault = false, connectionType = ConnectionType.HTTP_SESSION,
            riskLevel = RiskLevel.HIGH, credentialType = CredentialType.SESSION_TOKEN,
            supportStatus = SupportStatus.SUPPORTED, allowedHosts = HOSTS
        )
    }
}

private fun failure(result: TaygedoClient.ApiResult, fallback: String): CheckInOutcome {
    val message = result.message.ifBlank { fallback }
    return when {
        result.code == 401 || result.code == -401 -> CheckInOutcome.AuthenticationExpired(
            "塔吉多登录已失效，请重新连接。", "TAYGEDO_AUTH_EXPIRED"
        )
        message.contains("验证") || message.contains("风控") -> CheckInOutcome.ActionRequired(
            "塔吉多要求额外验证，请打开官方 App 处理。", "TAYGEDO_VERIFICATION"
        )
        else -> CheckInOutcome.TemporaryFailure(fallback, "TAYGEDO_${result.code}")
    }
}
