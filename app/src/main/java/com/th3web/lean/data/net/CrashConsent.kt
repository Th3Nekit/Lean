package com.th3web.lean.data.net

import java.util.concurrent.atomic.AtomicBoolean

internal interface CrashWorkScheduler {
    fun enqueue()
    fun cancel()

    companion object {
        const val UNIQUE_WORK_NAME = "lean-crash-upload"
    }
}

internal class CrashConsentController(
    private val store: CrashStore,
    private val scheduler: CrashWorkScheduler,
) {
    private val enabled = AtomicBoolean(false)

    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(value: Boolean) {
        if (!value) {
            enabled.set(false)
            store.delete()
            scheduler.cancel()
            return
        }
        enabled.set(true)
        if (store.load() != null) scheduler.enqueue()
    }
}

internal class CrashUncaughtHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val enabled: () -> Boolean,
    private val capture: (Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (enabled()) capture(throwable)
        } catch (_: Throwable) {
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }
}

internal class CrashHandlerInstaller(
    private val current: () -> Thread.UncaughtExceptionHandler?,
    private val replace: (Thread.UncaughtExceptionHandler) -> Unit,
    private val create: (Thread.UncaughtExceptionHandler?) -> Thread.UncaughtExceptionHandler,
) {
    private var installed: Thread.UncaughtExceptionHandler? = null

    @Synchronized
    fun install() {
        if (installed != null) return
        val handler = create(current())
        replace(handler)
        installed = handler
    }
}
