package com.example.imageedit.core.edit

import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.tools.BitmapToolExecutor
import com.example.imageedit.core.tools.ToolInvocation
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Parameter-based undo: history stores tool invocations, never bitmap snapshots.
 *
 * All mutations are serialized by [lock]; callers no longer need the UI's
 * "busy" flag to avoid corrupting the session. `checkActive` lets large pixel
 * loops abort promptly when the coroutine that owns the edit is cancelled.
 *
 * [onHistoryChanged] runs on the thread that performed the edit (never the main
 * thread in production paths) and is used by the ViewModel to persist the
 * parameter history after every successful step.
 */
class EditSession(
    val original: ImageContext,
    initialHistory: List<ToolInvocation> = emptyList(),
    replayInitialHistory: Boolean = true,
    private val onHistoryChanged: (() -> Unit)? = null,
) {
    private val lock = ReentrantLock()
    private val invocations = initialHistory.toMutableList()

    @Volatile
    private var historyView: List<ToolInvocation> = initialHistory.toList()

    @Volatile
    var current: ImageContext =
        if (replayInitialHistory && invocations.isNotEmpty()) rebuildUnlocked() else original
        private set

    fun history(): List<ToolInvocation> = historyView

    fun apply(
        invocation: ToolInvocation,
        checkActive: () -> Unit = {},
    ): ImageContext = lock.withLock {
        checkActive()
        val next = BitmapToolExecutor.execute(current, invocation, checkActive)
        invocations += invocation
        current = next
        historyView = invocations.toList()
        onHistoryChanged?.invoke()
        current
    }

    fun undo(checkActive: () -> Unit = {}): ImageContext = lock.withLock {
        if (invocations.isEmpty()) return current
        val removed = invocations.removeAt(invocations.lastIndex)
        val rebuilt = try {
            rebuildUnlocked(checkActive)
        } catch (error: Throwable) {
            // Keep the parameter history consistent if replaying fails.
            invocations += removed
            throw error
        }
        current = rebuilt
        historyView = invocations.toList()
        onHistoryChanged?.invoke()
        current
    }

    fun rebuild(checkActive: () -> Unit = {}): ImageContext =
        lock.withLock { rebuildUnlocked(checkActive) }

    fun replayOn(
        fullResolution: ImageContext,
        recycleInput: Boolean = false,
        checkActive: () -> Unit = {},
        onStep: ((index: Int, invocation: ToolInvocation) -> Unit)? = null,
    ): ImageContext = lock.withLock {
        var context = fullResolution
        for ((index, invocation) in invocations.withIndex()) {
            checkActive()
            val next = BitmapToolExecutor.execute(context, invocation, checkActive)
            recycleIntermediate(context, next, fullResolution, recycleInput)
            context = next
            onStep?.invoke(index, invocation)
        }
        context
    }

    private fun rebuildUnlocked(checkActive: () -> Unit = {}): ImageContext {
        var context = original
        for (invocation in invocations) {
            checkActive()
            val next = BitmapToolExecutor.execute(context, invocation, checkActive)
            recycleIntermediate(context, next, original, recycleInput = false)
            context = next
        }
        return context
    }

    private fun recycleIntermediate(
        current: ImageContext,
        next: ImageContext,
        fullResolution: ImageContext,
        recycleInput: Boolean,
    ) {
        val bitmap = current.bitmap
        val keepBitmap = if (recycleInput) null else fullResolution.bitmap
        if (next.bitmap !== bitmap && bitmap !== keepBitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
