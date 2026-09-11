package com.checky.app.accessibility

import kotlinx.coroutines.CompletableDeferred

class TapTapPendingRun internal constructor(
    internal val completion: CompletableDeferred<TapTapRunResult> = CompletableDeferred()
) {
    internal var clickConsumed: Boolean = false
}

/** One bounded bridge between the provider coroutine and the TapTap service. */
object TapTapAutomationCoordinator {
    private val lock = Any()
    private var pending: TapTapPendingRun? = null

    fun begin(): TapTapPendingRun? = synchronized(lock) {
        if (pending != null) return@synchronized null
        TapTapPendingRun().also { pending = it }
    }

    fun current(): TapTapPendingRun? = synchronized(lock) { pending }

    fun observe(
        run: TapTapPendingRun,
        snapshot: TapTapAccessibilitySnapshot,
        click: () -> Boolean
    ) {
        var shouldClick = false
        synchronized(lock) {
            if (pending !== run || run.completion.isCompleted) return
            when (TapTapPageMatcher.classify(snapshot)) {
                TapTapPageDisposition.NOT_TARGET,
                TapTapPageDisposition.WAITING -> Unit
                TapTapPageDisposition.READY -> {
                    if (!run.clickConsumed) {
                        run.clickConsumed = true
                        shouldClick = true
                    }
                }
                TapTapPageDisposition.ALREADY_COMPLETED ->
                    if (run.clickConsumed) complete(run, TapTapRunResult.UNKNOWN)
                    else complete(run, TapTapRunResult.ALREADY_COMPLETED)
                TapTapPageDisposition.SUCCESS -> complete(run, TapTapRunResult.SUCCESS)
                TapTapPageDisposition.UNKNOWN -> complete(run, TapTapRunResult.UNKNOWN)
            }
        }
        if (shouldClick && !click()) complete(run, TapTapRunResult.UNKNOWN)
    }

    fun clear(run: TapTapPendingRun) = synchronized(lock) {
        if (pending === run) pending = null
    }

    private fun complete(run: TapTapPendingRun, result: TapTapRunResult) {
        if (pending === run) pending = null
        run.completion.complete(result)
    }
}
