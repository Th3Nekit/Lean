package com.th3web.lean.data.net

import java.util.Collections
import java.util.IdentityHashMap

internal class CrashPayloadFactory(
    private val appVersion: () -> String,
    private val logs: () -> List<String>,
) {
    fun create(throwable: Throwable): CrashPayload {
        val type = throwable.javaClass.name.take(MAX_EXCEPTION_TYPE_CHARS)
            .ifEmpty { Throwable::class.java.name }
        return CrashPayload(
            appVersion = appVersion().take(MAX_APP_VERSION_CHARS).ifEmpty { "unknown" },
            exceptionType = type,
            message = CrashRedactor.message(throwable.message ?: throwable.javaClass.simpleName),
            stackTrace = CrashRedactor.stackTrace(ThrowableFormatter.format(throwable)),
            logTail = CrashRedactor.logLines(runCatching(logs).getOrDefault(emptyList())),
        )
    }

    fun createManual(): CrashPayload =
        CrashPayload(
            appVersion = appVersion().take(MAX_APP_VERSION_CHARS).ifEmpty { "unknown" },
            exceptionType = "ManualDiagnostics",
            message = "User submitted diagnostics",
            stackTrace = "",
            logTail = CrashRedactor.logLines(runCatching(logs).getOrDefault(emptyList())),
        )

    private companion object {
        const val MAX_APP_VERSION_CHARS = 32
        const val MAX_EXCEPTION_TYPE_CHARS = 120
    }
}

private object ThrowableFormatter {
    private const val MAX_CAUSES = 16
    private const val MAX_SUPPRESSED_PER_THROWABLE = 4
    private const val MAX_FRAMES_PER_THROWABLE = 48
    private const val BUILD_LIMIT = CrashRedactor.MAX_STACK_TRACE_CHARS * 2

    fun format(root: Throwable): String {
        val output = StringBuilder(minOf(BUILD_LIMIT, 4_096))
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = root
        var causeCount = 0
        while (current != null && causeCount < MAX_CAUSES && output.length < BUILD_LIMIT) {
            if (!visited.add(current)) {
                appendLine(output, "[CIRCULAR REFERENCE]")
                break
            }
            if (causeCount > 0) append(output, "Caused by: ")
            appendThrowable(output, current)
            current.suppressed.take(MAX_SUPPRESSED_PER_THROWABLE).forEach { suppressed ->
                if (output.length < BUILD_LIMIT) {
                    append(output, "Suppressed: ")
                    appendThrowable(output, suppressed)
                }
            }
            current = current.cause
            causeCount++
        }
        if (current != null && output.length < BUILD_LIMIT) {
            appendLine(output, "Caused by: ${CrashRedactor.TRUNCATED}")
        }
        return output.toString()
    }

    private fun appendThrowable(output: StringBuilder, throwable: Throwable) {
        append(output, throwable.javaClass.name.take(256))
        throwable.message?.let {
            append(output, ": ")
            append(output, CrashRedactor.message(it))
        }
        appendLine(output, "")
        throwable.stackTrace.take(MAX_FRAMES_PER_THROWABLE).forEach { frame ->
            appendLine(output, "\tat ${frame.toString().take(512)}")
        }
        if (throwable.stackTrace.size > MAX_FRAMES_PER_THROWABLE) {
            appendLine(output, "\t${CrashRedactor.TRUNCATED}")
        }
    }

    private fun appendLine(output: StringBuilder, value: String) {
        append(output, value)
        append(output, "\n")
    }

    private fun append(output: StringBuilder, value: String) {
        if (output.length >= BUILD_LIMIT) return
        output.append(value, 0, minOf(value.length, BUILD_LIMIT - output.length))
    }
}
