package com.th3web.lean.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.data.resolveProfileSelection

/**
 * Automation surface shared by the [AutomationReceiver] broadcasts and the
 * `lean://` deep links handled in MainActivity. Action strings are also
 * repeated verbatim in AndroidManifest.xml (a manifest cannot reference
 * Kotlin consts), keep both in sync.
 */
object Automation {

    private const val TAG = "Automation"

    const val ACTION_CONNECT = "com.th3web.lean.CONNECT"
    const val ACTION_DISCONNECT = "com.th3web.lean.DISCONNECT"
    const val ACTION_TOGGLE = "com.th3web.lean.TOGGLE"
    const val ACTION_STATUS = "com.th3web.lean.STATUS"
    const val ACTION_STATUS_RESULT = "com.th3web.lean.STATUS_RESULT"

    /** Vendor-specific cold-boot broadcast some OEMs send instead of BOOT_COMPLETED. */
    const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

    /** Extra on [ACTION_STATUS_RESULT]; value is one of [statusName]'s strings. */
    const val EXTRA_STATUS = "status" // DISCONNECTED | CONNECTING | CONNECTED | DISCONNECTING | ERROR

    /** Maps the internal [VpnState] to the stable automation vocabulary. */
    fun statusName(state: VpnState): String = when (state) {
        is VpnState.Connected -> "CONNECTED"
        VpnState.Connecting -> "CONNECTING"
        VpnState.Stopping -> "DISCONNECTING"
        is VpnState.Error -> "ERROR"
        else -> "DISCONNECTED"
    }

    /** The profile automation should connect to: user selection, else the first saved server. */
    suspend fun resolveProfileId(app: LeanApp): String? = resolveProfileSelection(
        savedId = app.settings.flow.first().selectedProfileId,
        profiles = app.profiles.state.value.profiles,
    )

    /**
     * Connect without any UI. Returns false (with a warning log) when VPN
     * consent has not been granted yet, `VpnService.prepare()` returns a
     * consent Intent only an Activity can launch, so the user must connect
     * from the app once, or when there is no profile to connect to.
     */
    suspend fun connectSilently(context: Context): Boolean {
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "connect skipped: VPN consent not granted yet (connect from the app once)")
            return false
        }
        val profileId = resolveProfileId(LeanApp.instance)
        if (profileId == null) {
            Log.w(TAG, "connect skipped: no saved profile to connect to")
            return false
        }
        CoreManager.connect(context, profileId)
        return true
    }

    /**
     * Boot path: bring the tunnel up after a device restart, but only when the
     * user opted in via the `autoConnect` setting toggle ("Автоподключение").
     * Same flag the in-app launch auto-connect uses, so one switch governs both
     * "on app launch" and "after reboot". Silently no-ops (logging a warning)
     * when consent is missing (`VpnService.prepare()` needs an Activity), so a
     * first boot before the user has ever connected from the app just waits.
     */
    suspend fun connectOnBoot(context: Context) {
        if (!LeanApp.instance.settings.flow.first().autoConnect) {
            Log.i(TAG, "boot connect skipped: autoConnect is off")
            return
        }
        if (CoreManager.isActive) return
        connectSilently(context)
    }
}

/**
 * External automation entry point (Tasker, MacroDroid, adb, ...).
 *
 * Handles [Automation.ACTION_CONNECT] / [Automation.ACTION_DISCONNECT] /
 * [Automation.ACTION_TOGGLE] / [Automation.ACTION_STATUS]; status replies with
 * an [Automation.ACTION_STATUS_RESULT] broadcast carrying
 * [Automation.EXTRA_STATUS].
 *
 * Since Android 8 implicit broadcasts are not delivered to manifest-declared
 * receivers, so senders must address the component explicitly:
 * ```
 * adb shell am broadcast \
 *   -n com.th3web.lean/.core.AutomationReceiver \
 *   -a com.th3web.lean.CONNECT
 * ```
 * (debug builds: `-n com.th3web.lean.debug/com.th3web.lean.core.AutomationReceiver`).
 *
 * The receiver is exported but guarded by the normal-level
 * `${applicationId}.permission.AUTOMATION` permission: automation apps simply
 * declare `<uses-permission android:name="com.th3web.lean.permission.AUTOMATION"/>`
 * and it is granted at install time; the shell (`am broadcast`) bypasses it.
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            Automation.ACTION_CONNECT -> goAsyncLaunch {
                Automation.connectSilently(appContext)
            }

            Automation.ACTION_DISCONNECT -> CoreManager.disconnect(appContext)

            Automation.ACTION_TOGGLE -> goAsyncLaunch {
                if (CoreManager.isActive) CoreManager.disconnect(appContext)
                else Automation.connectSilently(appContext)
            }

            // status is answered synchronously: CoreManager is a process-wide
            // singleton, so state.value is authoritative for this process. The
            // only soft edge is a cold start where the broadcast itself spun the
            // process up and a connect is racing to begin, status may briefly
            // report DISCONNECTED before CONNECTING latches. Senders that care
            // can re-poll; for the steady state this is exact.
            Automation.ACTION_STATUS -> appContext.sendBroadcast(
                Intent(Automation.ACTION_STATUS_RESULT)
                    .putExtra(Automation.EXTRA_STATUS, Automation.statusName(CoreManager.state.value)),
            )

            Intent.ACTION_BOOT_COMPLETED,
            Automation.ACTION_QUICKBOOT_POWERON,
            -> goAsyncLaunch {
                Automation.connectOnBoot(appContext)
            }
        }
    }

    /** Runs a suspend path past onReceive's lifetime via goAsync (10 s budget, plenty). */
    private fun goAsyncLaunch(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }
}
