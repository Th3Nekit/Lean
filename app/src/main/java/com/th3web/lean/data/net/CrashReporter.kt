package com.th3web.lean.data.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.th3web.lean.BuildConfig
import com.th3web.lean.core.CoreManager

object CrashReporter {
    const val PUBLIC_ISSUES_URL = "https://github.com/Th3Nekit/Lean/issues"
    const val PUBLIC_ISSUES_LABEL = "Сообщить об ошибке"
    const val CONSENT_DISCLOSURE =
        "Отправляет обезличенный стектрейс и последние строки лога; " +
            "отключение удаляет ожидающий отчёт."

    private val lock = Any()
    @Volatile
    private var runtime: Runtime? = null

    fun install(context: Context) {
        runCatching {
            runtime(context).installer.install()
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            runtime(context).consent.setEnabled(enabled)
        }
    }

    internal fun deliver(context: Context): CrashDeliveryResult =
        runCatching {
            val current = runtime(context)
            CrashDelivery(
                store = current.store,
                transport = CrashHttpTransport(),
                clock = CrashClock(System::currentTimeMillis),
                enabled = current.consent::isEnabled,
            ).deliver()
        }.getOrDefault(CrashDeliveryResult.NothingToDo)

    internal suspend fun sendDiagnostics(context: Context): ManualDiagnosticsResult =
        withContext(Dispatchers.IO) {
            runCatching {
                ManualDiagnosticsSender(
                    factory = runtime(context).factory,
                    transport = CrashHttpTransport(open = openOffTunnel(context)),
                ).send()
            }.getOrDefault(ManualDiagnosticsResult.Failed)
        }

    /**
     * Opens the report's connection on the physical network when there is one, and the
     * ordinary way when there is not. Never throws: failing to find the network under the
     * tunnel must degrade to an ordinary connection, not to no report at all.
     */
    private fun openOffTunnel(context: Context): (java.net.URL) -> java.net.URLConnection {
        val network = PhysicalNetwork.find(context) ?: return java.net.URL::openConnection
        return { url -> runCatching { network.openConnection(url) }.getOrElse { url.openConnection() } }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            runtime = null
        }
    }

    private fun runtime(context: Context): Runtime {
        runtime?.let { return it }
        return synchronized(lock) {
            runtime ?: createRuntime(context.applicationContext).also { runtime = it }
        }
    }

    private fun createRuntime(context: Context): Runtime {
        val store = AtomicCrashStore(File(context.filesDir, "crash-diagnostics"))
        val scheduler = WorkManagerCrashScheduler(context)
        val consent = CrashConsentController(store, scheduler)
        val factory = CrashPayloadFactory(
            appVersion = { BuildConfig.VERSION_NAME },
            // The app's own narration first and in full, then the core's tail. They share
            // one capped buffer and are nothing alike in volume, a busy core writes
            // thousands of lines an hour, the app a handful, so the core's output alone
            // fills a report without saying what the app thought it was doing.
            logs = { CoreManager.ownLog() + CoreManager.logs.value },
        )
        val installer = CrashHandlerInstaller(
            current = Thread::getDefaultUncaughtExceptionHandler,
            replace = Thread::setDefaultUncaughtExceptionHandler,
            create = { previous ->
                CrashUncaughtHandler(previous, consent::isEnabled) { throwable ->
                    store.save(
                        CrashEnvelope.create(
                            payload = factory.create(throwable),
                            capturedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            },
        )
        return Runtime(store, consent, installer, factory)
    }

    private data class Runtime(
        val store: CrashStore,
        val consent: CrashConsentController,
        val installer: CrashHandlerInstaller,
        val factory: CrashPayloadFactory,
    )
}

private class WorkManagerCrashScheduler(context: Context) : CrashWorkScheduler {
    private val appContext = context.applicationContext

    override fun enqueue() {
        val request = OneTimeWorkRequestBuilder<CrashUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                CrashWorkScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    override fun cancel() {
        runCatching {
            WorkManager.getInstance(appContext)
                .cancelUniqueWork(CrashWorkScheduler.UNIQUE_WORK_NAME)
        }
    }
}

class CrashUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        when (CrashReporter.deliver(applicationContext)) {
            CrashDeliveryResult.Retry -> Result.retry()
            else -> Result.success()
        }
}
