package com.th3web.lean.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * How many servers may be probed at the same time.
 *
 * Probes are IO-bound waits, so this is not about throughput: it is about not opening a
 * socket for every server in a large subscription at once. «URL Test» keeps its own,
 * much tighter cap (it boots a core per probe); this one covers the raw protocols.
 */
const val MAX_CONCURRENT_PROBES = 16

/**
 * Flush cadence for a sweep the user did not ask for, the one that fires at launch or
 * after a subscription refresh.
 *
 * Each flush re-emits the whole profile list, and the screens re-sort and re-group it, so
 * on a large subscription the default 400 ms turned a long sweep into a steady drumbeat of
 * recompositions during the very seconds the user is waiting for the first screen. When
 * the user is watching a sweep they started, the faster default still applies.
 */
const val BACKGROUND_FLUSH_MS = 1_500L

/**
 * Runs a ping burst and publishes results as they arrive rather than once at the end.
 *
 * Waiting for the whole batch and writing one map at the end is fine while every probe is
 * a one-second TCP connect. «URL Test» boots a native core per server, a few at a time, so
 * a twenty-server list takes minutes, and a screen that shows nothing for minutes reads
 * as a broken ping rather than a slow one.
 *
 * Writes are still coalesced, just on a clock instead of on completion: results collect
 * for [flushEveryMs] and go out as one store emission, which keeps the original reason
 * for batching (one recomposition per group of results, not one per server), while making
 * a slow burst visibly progress. A final flush always runs, so the last few results are
 * never left unpublished.
 */
// onTimeout is the experimental half of `select`, and it is the whole mechanism above:
// without it the loop would wait for a result that may never come instead of flushing on
// a clock. Opting in here states that rather than leaving a build warning.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
suspend fun runPingBurst(
    ids: List<String>,
    flushEveryMs: Long = 400,
    maxConcurrency: Int = MAX_CONCURRENT_PROBES,
    measure: suspend (String) -> Int,
    publish: suspend (Map<String, Int>) -> Unit,
) {
    if (ids.isEmpty()) return
    coroutineScope {
        val done = Channel<Pair<String, Int>>(Channel.UNLIMITED)
        val gate = Semaphore(maxConcurrency)
        ids.forEach { id ->
            // Dispatchers.IO, and bounded.
            //
            // Every caller reaches this from a composition scope, whose dispatcher is the
            // main one. The probes switch to IO internally, but the coroutines are still
            // created and resumed where they were launched, one per server, all at once.
            // On a large subscription that is a coroutine and a withContext return per
            // server competing with the first frames the user is waiting for.
            //
            // The permit also caps how many sockets and probe buffers exist at once. That
            // matters beyond jank: the same 57-server list produced an OutOfMemoryError at
            // the 256 MB heap limit after a long session of switching servers.
            launch(Dispatchers.IO) {
                val ms = gate.withPermit { measure(id) }
                done.send(id to ms)
            }
        }
        // The collector too. `publish` walks the whole profile list to fold the results
        // in and then emits, and every caller reaches this from a composition scope,
        // i.e. the main dispatcher, so on a 57-server list that fold ran on the UI
        // thread once per flush.
        launch(Dispatchers.IO) {
            val pending = LinkedHashMap<String, Int>()
            var received = 0
            while (received < ids.size) {
                // Take whatever has finished, then wait a beat for stragglers to join the
                // same write instead of each forcing its own.
                val first = done.receive()
                pending[first.first] = first.second
                received++
                while (received < ids.size) {
                    // Explicit type parameter: the timeout clause returns null, and
                    // without it Kotlin infers the whole select as Nothing? from that
                    // branch rather than from the receive.
                    val next = select<Pair<String, Int>?> {
                        done.onReceive { it }
                        onTimeout(flushEveryMs) { null }
                    } ?: break
                    pending[next.first] = next.second
                    received++
                }
                publish(pending.toMap())
                pending.clear()
            }
        }
    }
}
