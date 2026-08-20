package com.th3web.lean.core

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import com.th3web.lean.LeanApp
import com.th3web.lean.data.net.Pinger

/**
 * Periodic background refresh of all subscriptions (feature 8). Scheduled
 * (and cancelled) from [com.th3web.lean.LeanApp] based on the
 * `bgRefreshMinutes` setting, unique work name [WORK_NAME].
 *
 * After each successful refresh, when the ping-on-update setting is enabled,
 * that subscription's servers are re-pinged so latency badges stay honest.
 *
 * Result policy: any subscription refreshing successfully counts as success
 * (a single dead provider must not put the whole job into backoff); when all
 * fail we retry up to [MAX_ATTEMPTS] runs, then report failure and wait for
 * the next period.
 */
class SubscriptionRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as LeanApp
        val s = app.settings.flow.first()
        val subs = app.profiles.state.value.subscriptions
        if (subs.isEmpty()) return Result.success()

        // «URL Test» measures through its own standalone core instance rather than a raw
        // socket, so unlike TCP/ICMP/GET/HEAD it is unaffected by the unreliable protect()
        // that would otherwise make a probe taken while the tunnel is up report the proxy's
        // RTT instead of the server's: that is why it alone may run while connected.
        val urlTestSelected = s.pingProtocol.equals(Pinger.URL_TEST_PROTOCOL, ignoreCase = true)
        var anySuccess = false
        for (sub in subs) {
            if (app.profiles.updateSubscription(sub.id).isFailure) continue
            anySuccess = true
            // Connected/connecting routes the raw-socket probes through the tunnel
            // instead of past it, so a measured "latency" would silently be the
            // proxy's own RTT, not the server's. Worse, this worker can fire in the
            // background while the user is happily connected, quietly corrupting
            // stored latencies with tunnel numbers.
            if (s.pingOnUpdate && (!CoreManager.isActive || urlTestSelected)) {
                // Re-ping this subscription's (freshly reconciled) servers,
                // automatic, so excluded-from-test servers are skipped. Measure
                // in parallel, then flush every latency in one persisting store
                // write (updateLatencies) instead of one disk serialization per
                // server, matches the launch-time burst in MainActivity.
                val targets = app.profiles.state.value.profiles
                    .filter { it.subscriptionId == sub.id && !it.excludedFromTest }
                if (targets.isNotEmpty()) {
                    val results = coroutineScope {
                        targets.map { p ->
                            // url-overload: GET/HEAD honour the configured pingUrl
                            // (real HTTP round-trip) instead of degrading to TCP, and
                            // URL Test gets its real per-server probe here too (owner's
                            // call, automatic pings use the selected protocol like any
                            // other path; UrlTestPinger's 5-instance semaphore is what
                            // keeps a background burst from booting a core per server
                            // all at once).
                            async {
                                p.id to Pinger.measure(
                                    p.outbound.server, p.outbound.serverPort, s.pingProtocol, s.pingTimeoutMs, s.pingUrl,
                                    udpService = Pinger.isUdpService(p.outbound), protect = CoreManager.probeProtect,
                                    outbound = p.outbound, urlTestProbe = UrlTestPinger::measure,
                                )
                            }
                        }.awaitAll()
                    }
                    app.profiles.updateLatencies(results.toMap())
                }
            }
        }
        return when {
            anySuccess -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        /** Unique periodic work name; keep policy preserves the running schedule. */
        const val WORK_NAME = "subscription_refresh"
        private const val MAX_ATTEMPTS = 3
    }
}
