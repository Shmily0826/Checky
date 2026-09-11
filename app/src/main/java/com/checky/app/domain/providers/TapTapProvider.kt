package com.checky.app.domain.providers

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import com.checky.app.accessibility.TAPTAP_PACKAGE
import com.checky.app.accessibility.TapTapAutomationCoordinator
import com.checky.app.accessibility.TapTapRunResult
import com.checky.app.accessibility.TapTapAccessibilityService
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.ConnectionType
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RiskLevel
import com.checky.app.domain.model.SupportStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.ZoneId

class TapTapProvider(private val context: Context) : CheckInProvider {
    override val meta: ProviderMeta = META

    override suspend fun validateCredentials(secret: String): CredentialValidation =
        CredentialValidation.Valid

    override fun checkIn() = flow {
        emit(CheckInEvent.Progress(0.1f, "Opening TapTap game-sign event…"))
        val outcome = checkInOnce()
        emit(
            CheckInEvent.Done(
                CheckInResult(
                    serviceId = meta.id,
                    serviceName = meta.displayName,
                    outcome = outcome,
                    timestamp = System.currentTimeMillis()
                )
            )
        )
    }

    private suspend fun checkInOnce(): CheckInOutcome {
        if (!isAccessibilityServiceEnabled(context)) {
            return CheckInOutcome.ActionRequired(
                "Enable Checky in Android Settings > Accessibility before using TapTap check-in.",
                "TAPTAP_ACCESSIBILITY_DISABLED"
            )
        }

        val run = TapTapAutomationCoordinator.begin() ?: return unknown("TAPTAP_RUN_BUSY")
        try {
            val intent = Intent(Intent.ACTION_VIEW, buildDeepLink(DEFAULT_EVENT_URL)).apply {
                setPackage(TAPTAP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.resolveActivity(intent, 0) == null) {
                return CheckInOutcome.Unsupported(
                    "TapTap is not installed or cannot open the game-sign event.",
                    "TAPTAP_UNAVAILABLE"
                )
            }
            withContext(Dispatchers.Main.immediate) { context.startActivity(intent) }
            return when (withTimeout(TIMEOUT_MS) { run.completion.await() }) {
                TapTapRunResult.SUCCESS -> CheckInOutcome.Success(
                    "TapTap check-in succeeded.", "TAPTAP_SUCCESS", Reward.empty()
                )
                TapTapRunResult.ALREADY_COMPLETED -> CheckInOutcome.AlreadyCompleted(
                    "TapTap is already checked in today.", "TAPTAP_ALREADY"
                )
                TapTapRunResult.UNKNOWN -> unknown("TAPTAP_UNKNOWN")
            }
        } catch (_: TimeoutCancellationException) {
            return unknown("TAPTAP_TIMEOUT")
        } catch (_: ActivityNotFoundException) {
            return CheckInOutcome.Unsupported(
                "TapTap is not installed or cannot open the game-sign event.",
                "TAPTAP_UNAVAILABLE"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return unknown("TAPTAP_UNKNOWN")
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    private fun unknown(code: String) = CheckInOutcome.PermanentFailure(
        "TapTap check-in could not be confirmed; no further action was taken.", code
    )

    companion object {
        const val ID = "taptap_game_sign"
        const val DEFAULT_EVENT_URL = "https://www.taptap.cn/events/game-sign/7fva1f6e"
        private const val TIMEOUT_MS = 45_000L

        fun buildDeepLink(eventUrl: String): Uri {
            val target = Uri.parse(eventUrl)
            require(target.scheme == "https" && target.host == "www.taptap.cn") {
                "TapTap event URL must be an HTTPS www.taptap.cn URL"
            }
            return Uri.Builder()
                .scheme("taptap")
                .authority("taptap.com")
                .path("/to")
                .appendQueryParameter("url", eventUrl)
                .build()
        }

        private val META = ProviderMeta(
            id = ID,
            displayName = "TapTap game check-in",
            description = "Opens the known TapTap game-sign event and clicks only its check-in button. Enable Checky in Android Settings > Accessibility first.",
            category = "Gaming",
            iconKey = "gamepad",
            accentColor = 0xFF00A4DE,
            isEnabledByDefault = false,
            connectionType = ConnectionType.UI_ASSISTED,
            riskLevel = RiskLevel.HIGH,
            credentialType = CredentialType.NONE,
            supportStatus = SupportStatus.SUPPORTED,
            allowedHosts = setOf("www.taptap.cn"),
            businessZone = ZoneId.of("Asia/Shanghai")
        )

        private fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo
                    serviceInfo?.packageName == context.packageName &&
                        serviceInfo.name == TapTapAccessibilityService::class.java.name
                }
        }
    }
}
