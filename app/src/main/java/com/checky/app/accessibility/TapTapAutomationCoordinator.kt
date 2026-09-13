package com.checky.app.accessibility

import kotlinx.coroutines.CompletableDeferred

class TapTapPendingRun internal constructor(
    internal val completion: CompletableDeferred<TapTapRunResult> = CompletableDeferred(),
    internal val onPositiveTerminal: () -> Unit = {}
) {
    internal var clickConsumed: Boolean = false
}

/** One bounded bridge between the provider coroutine and the TapTap service. */
object TapTapAutomationCoordinator {
    private val lock = Any()
    private var pending: TapTapPendingRun? = null
    private var onRunActivated: ((TapTapPendingRun) -> Unit)? = null

    fun begin(onPositiveTerminal: () -> Unit = {}): TapTapPendingRun? {
        val activation = synchronized(lock) {
            if (pending != null) {
                null
            } else {
                TapTapPendingRun(onPositiveTerminal = onPositiveTerminal).also { pending = it }
                    .let { it to onRunActivated }
            }
        } ?: return null

        activation.second?.invoke(activation.first)
        return activation.first
    }

    fun current(): TapTapPendingRun? = synchronized(lock) { pending }

    fun registerRunActivationListener(listener: (TapTapPendingRun) -> Unit) {
        val active = synchronized(lock) {
            onRunActivated = listener
            pending
        }
        active?.let(listener)
    }

    fun unregisterRunActivationListener(listener: (TapTapPendingRun) -> Unit) = synchronized(lock) {
        if (onRunActivated === listener) onRunActivated = null
    }

    fun observe(
        run: TapTapPendingRun,
        snapshot: TapTapAccessibilitySnapshot,
        allowClick: Boolean = true,
        click: () -> Boolean
    ) {
        var shouldClick = false
        var shouldReturnToChecky = false
        synchronized(lock) {
            if (pending !== run || run.completion.isCompleted) return
            val disposition = TapTapPageMatcher.classify(snapshot)
            when (disposition) {
                TapTapPageDisposition.NOT_TARGET,
                TapTapPageDisposition.WAITING -> Unit
                TapTapPageDisposition.READY -> {
                    if (allowClick && !run.clickConsumed) {
                        run.clickConsumed = true
                        shouldClick = true
                    }
                }
                TapTapPageDisposition.ALREADY_COMPLETED -> {
                    complete(run, TapTapRunResult.ALREADY_COMPLETED)
                    shouldReturnToChecky = true
                }
                TapTapPageDisposition.SUCCESS -> {
                    complete(run, TapTapRunResult.SUCCESS)
                    shouldReturnToChecky = true
                }
                TapTapPageDisposition.UNKNOWN -> complete(run, TapTapRunResult.UNKNOWN)
            }
        }
        if (shouldClick && !click()) complete(run, TapTapRunResult.UNKNOWN)
        if (shouldReturnToChecky) run.onPositiveTerminal()
    }

    fun clear(run: TapTapPendingRun) = synchronized(lock) {
        if (pending === run) pending = null
    }

    private fun complete(run: TapTapPendingRun, result: TapTapRunResult) {
        if (pending === run) pending = null
        run.completion.complete(result)
    }
}
