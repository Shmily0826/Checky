package com.checky.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TapTapAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != TAPTAP_PACKAGE) return

        val root = rootInActiveWindow ?: return
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

        val run = currentRun() ?: return
        TapTapAutomationCoordinator.observe(
            run = run,
            snapshot = TapTapAccessibilitySnapshot(TAPTAP_PACKAGE, texts, buttons.map { it.second })
        ) {
            buttons.singleOrNull { it.second.text.trim() == "立即签到" }?.first
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    override fun onInterrupt() = Unit

    private fun currentRun(): TapTapPendingRun? = TapTapAutomationCoordinator.current()
}
