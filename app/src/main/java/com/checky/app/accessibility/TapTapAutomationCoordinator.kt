package com.checky.app.accessibility

import kotlinx.coroutines.CompletableDeferred

class TapTapPendingRun internal constructor(
    internal val completion: CompletableDeferred<TapTapRunResult> = CompletableDeferred(),
    internal val onPositiveTerminal: () -> Unit = {}
) {
    internal var clickConsumed: Boolean = false
    internal var state: TapTapRunState = TapTapRunState.READY
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
            TapTapLabTrace.stage(disposition.name)
            when (run.state) {
                TapTapRunState.READY -> when (disposition) {
                    TapTapPageDisposition.NOT_TARGET,
                    TapTapPageDisposition.WAITING -> Unit
                    TapTapPageDisposition.READY -> {
                        if (allowClick && !run.clickConsumed) {
                            run.clickConsumed = true
                            run.state = TapTapRunState.CONFIRMING
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
                TapTapRunState.CONFIRMING -> when (disposition) {
                    TapTapPageDisposition.SUCCESS -> {
                        complete(run, TapTapRunResult.SUCCESS)
                        shouldReturnToChecky = true
                    }
                    TapTapPageDisposition.ALREADY_COMPLETED -> {
                        complete(run, TapTapRunResult.ALREADY_COMPLETED)
                        shouldReturnToChecky = true
                    }
                    TapTapPageDisposition.UNKNOWN -> {
                        if (TapTapPageMatcher.isUnsafe(snapshot)) {
                            complete(run, TapTapRunResult.UNKNOWN)
                        }
                    }
                    else -> Unit
                }
                else -> Unit
            }
        }
        if (shouldClick) {
            val dispatched = click()
            TapTapLabTrace.gestureDispatched(dispatched)
            if (dispatched) {
                TapTapLabTrace.stage("GESTURE_DISPATCHED")
                TapTapLabTrace.stage(TapTapRunState.CONFIRMING.name)
            } else {
                complete(run, TapTapRunResult.GESTURE_FAILED)
            }
        }
        if (shouldReturnToChecky) run.onPositiveTerminal()
    }

    fun finish(run: TapTapPendingRun, result: TapTapRunResult) = synchronized(lock) {
        if (pending === run && !run.completion.isCompleted) complete(run, result)
    }

    fun clear(run: TapTapPendingRun) = synchronized(lock) {
        if (pending === run) pending = null
    }

    private fun complete(run: TapTapPendingRun, result: TapTapRunResult) {
        if (pending === run) pending = null
        run.state = when (result) {
            TapTapRunResult.SUCCESS -> TapTapRunState.SUCCESS
            TapTapRunResult.ALREADY_COMPLETED -> TapTapRunState.ALREADY_COMPLETED
            TapTapRunResult.GESTURE_FAILED -> TapTapRunState.GESTURE_FAILED
            TapTapRunResult.PAGE_NOT_READY -> TapTapRunState.PAGE_NOT_READY
            TapTapRunResult.CONFIRMATION_TIMEOUT -> TapTapRunState.CONFIRMATION_TIMEOUT
            TapTapRunResult.UNKNOWN -> TapTapRunState.UNSAFE_OR_UNKNOWN_PAGE
        }
        TapTapLabTrace.stage(run.state.name)
        run.completion.complete(result)
    }
}
