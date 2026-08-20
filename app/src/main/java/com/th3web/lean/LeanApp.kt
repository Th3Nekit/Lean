package com.th3web.lean

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.th3web.lean.core.LeanForeground
import com.th3web.lean.core.SubscriptionRefreshWorker
import com.th3web.lean.data.ClientSpoof
import com.th3web.lean.data.HwId
import com.th3web.lean.data.ProfileRepository
import com.th3web.lean.data.SettingsRepository
import com.th3web.lean.ui.theme.LeanPower
import com.th3web.lean.data.net.CrashReporter
import com.th3web.lean.data.net.Http
import com.th3web.lean.data.net.UpdateChecker
import java.util.concurrent.TimeUnit

/**
 * Application entry point. Owns process-wide singletons (repositories). Simple
 * service-locator style access via [LeanApp.instance] keeps the app
 * dependency-injection-framework-free.
 */
class LeanApp : Application() {

    val profiles: ProfileRepository by lazy { ProfileRepository(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Opt-in crash reporter: install the handler as early as possible and seed the on/off flag
        // synchronously so a crash early in this launch is still captured if enabled.
        CrashReporter.install(this)
        // Follow the system's battery saver so the expensive visual layers can switch
        // themselves off without the user having to know they exist.
        LeanPower.start(this)
        // Lets the core's status polling drop to a trickle when no UI is watching it.
        LeanForeground.track(this)
        CrashReporter.setEnabled(this, settings.initial.crashReporting)
        Http.deviceLabel = HwId.deviceLabel()
        Http.appVersion = BuildConfig.VERSION_NAME
        // UA-gating panels serve different subscription formats by User-Agent:
        // sing-box-family UAs (incl. "LEAN/…/android") get a base64 share-link
        // list without the Hysteria2 servers, while v2rayNG-family UAs get the
        // full Xray JSON config array including them (parsed by XrayConfig).
        //
        // The presented UA is now a user-spoofable Settings field (Provider hub
        // UA picker), so the user can switch panels' gating themselves; the default
        // "Lean/…" is for panels that recognise Lean. Seed Http.userAgent from the
        // persisted setting synchronously (so the very first subscription fetch
        // uses it), and keep it mirrored on every change below. hwid still travels
        // only in the gated x-hwid header, nothing else changes here. If a "Lean"
        // default lands on a v2rayNG-gating panel and parses zero servers,
        // ProfileRepository.fetchSub retries once with v2rayNG to recover the list.
        // Empty UA setting = the dynamic "Lean/<versionName>" default (never goes stale).
        // A "happ:<platform>" / "v2raytun" token resolves (via ClientSpoof) to that
        // client's real UA and a client-shaped hwid + extra headers, so impersonation
        // matches both gating axes; any other value is a literal UA with the default
        // hex hwid.
        val defaultUa = "Lean/${BuildConfig.VERSION_NAME}"
        applySpoof(settings.initial.userAgent, defaultUa)
        // Mirror the send_hwid pref and the spoofable client identity into Http.
        appScope.launch {
            settings.flow.collect {
                Http.sendHwid = it.sendHwid
                applySpoof(it.userAgent, defaultUa)
                CrashReporter.setEnabled(this@LeanApp, it.crashReporting)
            }
        }
        // App-update check on launch (opt-in, APK not subscription): wait for the
        // canonical DataStore value instead of acting on a startup-mirror fallback.
        // Best-effort, silent on failure / GitHub-blocked / no permission.
        appScope.launch {
            if (settings.flow.first().checkAppUpdates) {
                runCatching { UpdateChecker.checkAndNotify(this@LeanApp, BuildConfig.VERSION_NAME) }
            }
        }
        // Background subscription refresh: bgRefreshMinutes (0 = off) is the single
        // source of truth, the legacy autoUpdate launch-refresh pref is ignored here.
        appScope.launch {
            var firstEmission = true
            settings.flow.map { it.bgRefreshMinutes }.distinctUntilChanged().collect { minutes ->
                scheduleBackgroundRefresh(minutes, firstEmission)
                firstEmission = false
            }
        }
        prewarmColdStart()
    }

    /** Resolve the stored UA-preset token to the wire UA + client-shaped hwid + headers. */
    private fun applySpoof(token: String, defaultUa: String) {
        val r = ClientSpoof.resolve(token, defaultUa, this)
        Http.userAgent = r.userAgent
        Http.hwid = r.hwid
        Http.extraHeaders = r.extraHeaders
    }

    /**
     * Pre-warm the one-time, on-the-tile-tap-critical work so a Quick Settings tile
     * tap from a cold process (which spins this process up, runs onCreate, then
     * LeanTileService.onClick→connect) doesn't pay it inline.
     *
     * Two synchronous reads otherwise happen on the tile-click path:
     *  - [profiles] is `by lazy`; its first touch (render()/connect() reads
     *    `profiles.state.value`) constructs ProfileRepository, which reads + parses
     *    lean_store.json on the calling thread. Touch it here on a background thread
     *    so the tile's synchronous state read finds it already loaded.
     *  - [settings] is also `by lazy`; it is already forced just above. Its small
     *    startup mirror seeds [SettingsRepository.state] while the canonical
     *    DataStore flow refreshes it in the background. We re-touch the state here
     *    only to keep both cold-start warmers explicit in one place.
     *
     * The native runtime is intentionally not pre-warmed here. It needs the active
     * service bridge and resolver, so the first connection initializes it only after
     * TUN ownership has been installed.
     */
    private fun prewarmColdStart() {
        appScope.launch {
            // Force the lazies + their disk reads off the main/tile thread.
            runCatching {
                profiles.state.value
                settings.state.value
            }
        }
    }

    /**
     * (Re)schedules or cancels the unique [SubscriptionRefreshWorker] job.
     *
     * In-session change: cancel first, then re-enqueue with keep, this resets
     * the period timer to "now" so a freshly chosen interval starts counting
     * immediately.
     *
     * First emission (process start / a restored Backup that changed the
     * interval out-of-session): use update, not keep. keep would keep an already
     * scheduled job's old period until the next in-session change, so a restored
     * interval would be ignored. update applies the new period to the existing
     * unique work in place (or enqueues it if none exists) without dropping the
     * job, so a restore takes effect on the very next start. (update: WorkManager
     * 2.8+; we depend on 2.9.x.)
     */
    private fun scheduleBackgroundRefresh(minutes: Int, firstEmission: Boolean) {
        val wm = WorkManager.getInstance(this)
        if (minutes <= 0) {
            wm.cancelUniqueWork(SubscriptionRefreshWorker.WORK_NAME)
            return
        }
        if (!firstEmission) wm.cancelUniqueWork(SubscriptionRefreshWorker.WORK_NAME)
        val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
            // WorkManager's hard floor for periodic work is 15 minutes.
            minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        // update on first emission reconciles a restored interval with the live
        // schedule; the in-session path already cancelled, so keep there just
        // re-creates with the new period.
        val policy = if (firstEmission) {
            ExistingPeriodicWorkPolicy.UPDATE
        } else {
            ExistingPeriodicWorkPolicy.KEEP
        }
        wm.enqueueUniquePeriodicWork(
            SubscriptionRefreshWorker.WORK_NAME,
            policy,
            request,
        )
    }

    companion object {
        lateinit var instance: LeanApp
            private set
    }
}
