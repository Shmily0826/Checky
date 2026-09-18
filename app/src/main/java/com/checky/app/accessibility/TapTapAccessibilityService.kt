package com.checky.app.accessibility

import android.app.ActivityManager
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TapTapAccessibilityService : AccessibilityService() {
    private val settleHandler = Handler(Looper.getMainLooper())
    // Slow WebViews get 8 seconds to expose the deterministic game-sign tree.
    private val settleDelaysMs = longArrayOf(150L, 350L, 750L, 1_500L, 3_000L, 5_000L, 8_000L)
    private val confirmationDelaysMs = longArrayOf(250L, 500L, 1_000L, 2_000L, 4_000L)
    private enum class PollPhase { INITIAL, CONFIRMATION }
    private var settleRun: TapTapPendingRun? = null
    private var settlePhase: PollPhase? = null
    private var settleGeneration = 0
    private val settleCallbacks = mutableListOf<Runnable>()
    private var serviceActive = false
    private val runActivationListener: (TapTapPendingRun) -> Unit = { run ->
        settleHandler.post {
            if (serviceActive) scheduleSettle(run, PollPhase.INITIAL)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        synchronized(serviceLock) { activeService = this }
        TapTapLabTrace.initialize(this)
        serviceActive = true
        TapTapAutomationCoordinator.registerRunActivationListener(runActivationListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != TAPTAP_PACKAGE) return

        val run = currentRun() ?: return
        evaluate(run, allowClick = false)
        if (!run.completion.isCompleted) {
            scheduleSettle(
                run,
                if (run.state == TapTapRunState.CONFIRMING) PollPhase.CONFIRMATION else PollPhase.INITIAL
            )
        }
    }

    private fun evaluate(run: TapTapPendingRun, allowClick: Boolean) {
        if (currentRun() !== run || run.completion.isCompleted) return

        val root = rootInActiveWindow
        root ?: return
        if (root.packageName?.toString() != TAPTAP_PACKAGE) return

        val buttons = mutableListOf<Pair<AccessibilityNodeInfo, TapTapAccessibilityButton>>()
        val texts = mutableListOf<String>()
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            if (node.className?.toString() == "android.widget.Button") {
                buttons += node to TapTapAccessibilityButton(
                    text = node.text?.toString().orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    enabled = node.isEnabled,
                    clickable = node.isClickable
                )
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(nodes::add)
            }
        }

        TapTapAutomationCoordinator.observe(
            run = run,
            snapshot = TapTapAccessibilitySnapshot(TAPTAP_PACKAGE, texts, buttons.map { it.second }),
            allowClick = allowClick
        ) {
            val target = buttons.singleOrNull {
                it.second.className == "android.widget.Button" &&
                    it.second.text.trim() == "\u7acb\u5373\u7b7e\u5230" &&
                    it.second.enabled &&
                    it.second.clickable
            }?.first
            val dispatched = target?.let(::dispatchSignInGesture) == true
            dispatched
        }
    }

    private fun dispatchSignInGesture(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val path = Path().apply {
            moveTo(bounds.exactCenterX(), bounds.exactCenterY())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 1L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun scheduleSettle(run: TapTapPendingRun, phase: PollPhase) {
        if (currentRun() !== run || run.completion.isCompleted) {
            cancelSettles()
            return
        }
        if (settleRun === run && settlePhase == phase) return

        cancelSettles()
        settleRun = run
        settlePhase = phase
        val generation = settleGeneration
        val delays = if (phase == PollPhase.INITIAL) settleDelaysMs else confirmationDelaysMs
        var remaining = delays.size
        delays.forEachIndexed { index, delayMs ->
            val allowClick = phase == PollPhase.INITIAL && index == delays.lastIndex
            val callback = Runnable {
                if (generation != settleGeneration || currentRun() !== run || run.completion.isCompleted) {
                    if (generation == settleGeneration) cancelSettles()
                    return@Runnable
                }

                evaluate(run, allowClick = allowClick)
                remaining -= 1
                if (currentRun() !== run || run.completion.isCompleted) {
                    cancelSettles()
                } else if (remaining == 0) {
                    cancelSettles()
                    if (phase == PollPhase.INITIAL && run.state == TapTapRunState.CONFIRMING) {
                        scheduleSettle(run, PollPhase.CONFIRMATION)
                    } else if (phase == PollPhase.INITIAL) {
                        TapTapAutomationCoordinator.finish(run, TapTapRunResult.PAGE_NOT_READY)
                    } else {
                        TapTapAutomationCoordinator.finish(run, TapTapRunResult.CONFIRMATION_TIMEOUT)
                    }
                }
            }
            settleCallbacks += callback
            settleHandler.postDelayed(callback, delayMs)
        }
    }

    private fun cancelSettles() {
        settleCallbacks.forEach(settleHandler::removeCallbacks)
        settleCallbacks.clear()
        settleRun = null
        settlePhase = null
        settleGeneration += 1
    }

    override fun onInterrupt() {
        serviceActive = false
        cancelSettles()
    }

    override fun onDestroy() {
        serviceActive = false
        synchronized(serviceLock) {
            if (activeService === this) activeService = null
        }
        TapTapAutomationCoordinator.unregisterRunActivationListener(runActivationListener)
        cancelSettles()
        super.onDestroy()
    }

    private fun currentRun(): TapTapPendingRun? = TapTapAutomationCoordinator.current()

    private fun requestCheckyForeground(): Boolean {
        if (!serviceActive || activePackage() != TAPTAP_PACKAGE) return false
        return try {
            val checkyTask = getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull { appTask ->
                val taskInfo = appTask.taskInfo ?: return@firstOrNull false
                taskInfo.baseActivity?.packageName == packageName &&
                    taskInfo.topActivity?.packageName == packageName
            } ?: return false
            checkyTask.moveToFront()
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun activePackage(): String? = rootInActiveWindow?.packageName?.toString()

    companion object {
        private val serviceLock = Any()
        private var activeService: TapTapAccessibilityService? = null

        fun requestCheckyForeground(): Boolean = synchronized(serviceLock) {
            activeService
        }?.requestCheckyForeground() == true
    }
}
