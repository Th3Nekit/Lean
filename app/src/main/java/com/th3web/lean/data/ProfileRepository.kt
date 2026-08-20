package com.th3web.lean.data

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Folder
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.Subscription
import com.th3web.lean.data.net.Http
import com.th3web.lean.data.parse.ProxyConfigFiles
import com.th3web.lean.data.parse.ShareLinks
import com.th3web.lean.data.parse.SubscriptionMeta
import com.th3web.lean.data.parse.WgConfig
import com.th3web.lean.data.parse.Subscriptions
import com.th3web.lean.data.parse.parseSubscriptionHeaders
import com.th3web.lean.data.parse.withFallback
import java.io.File

@Serializable
data class StoreData(
    val profiles: List<Profile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    /** Defaulted like every other addition, so an older lean_store.json still decodes. */
    val folders: List<Folder> = emptyList(),
)

/**
 * Single source of truth for saved servers and subscriptions. Persists to a
 * JSON file in the app's private storage.
 */
class ProfileRepository(context: Context) {

    private val file = File(context.filesDir, "lean_store.json")
    private val bakFile = File(context.filesDir, "lean_store.json.bak")
    private val tmpFile = File(context.filesDir, "lean_store.json.tmp")
    private val mutex = Mutex()

    // True when the on-disk store could not be fully decoded (a torn write, a
    // forward-compat/unknown entry, one malformed profile). While degraded we must
    // never overwrite the file with an empty store: that would turn a single bad
    // entry into a total wipe of every server + subscription. Declared before
    // [_state] so it is initialized before loadBlocking() (run in _state's init).
    @Volatile private var loadDegraded = false

    // True once the store has held real data this session (loaded non-empty, or a
    // non-empty write happened). Lets the anti-wipe guard tell a load-glitch empty
    // (block) from a user "delete all" in a degraded session (must persist, else the
    // deleted servers resurrect on next launch). See StoreCodec.shouldPersist.
    @Volatile private var everHadData = false

    private val _state = MutableStateFlow(loadBlocking())
    val state: StateFlow<StoreData> = _state.asStateFlow()

    private fun loadBlocking(): StoreData {
        // Delegates to the Context-free [StoreCodec], which falls back to the .bak when
        // the main file is torn-but-readable, before accepting an empty degraded result.
        // Consulting .bak only when the read fails misses a partial decode.
        val loaded = StoreCodec.loadFrom(file, bakFile)
        loadDegraded = loaded.degraded
        if (loaded.data.profiles.isNotEmpty() || loaded.data.subscriptions.isNotEmpty()) everHadData = true
        return loaded.data
    }

    // clearDegraded=true only for writes that re-establish a clean on-disk store (a
    // non-empty recovery write through update()). The latency-burst flush
    // (persistCurrent) passes clearDegraded=false so a lossy load is never silently
    // committed as truth, and, via shouldSnapshotBak: the good .bak is never
    // overwritten by the torn main while degraded.
    private suspend fun persist(data: StoreData, clearDegraded: Boolean = true) {
        if (data.profiles.isNotEmpty() || data.subscriptions.isNotEmpty()) everHadData = true
        if (!StoreCodec.shouldPersist(loadDegraded, everHadData, data)) return
        // Encode + blocking file IO off the Main/caller thread (a 50-server store
        // serialized on Main was a real jank source). Ordering and the anti-wipe/.bak/
        // atomic-tmp guards are preserved: every caller holds `mutex`, and a coroutine
        // Mutex keeps its lock across this inner withContext suspension, so writes stay
        // strictly serialized in call order and StoreCodec.writeStore runs single-writer.
        withContext(Dispatchers.IO) {
            runCatching {
                StoreCodec.writeStore(
                    data, file, bakFile, tmpFile,
                    snapshotBak = StoreCodec.shouldSnapshotBak(loadDegraded),
                )
                if (clearDegraded) loadDegraded = false
            }
        }
    }

    private suspend fun update(transform: (StoreData) -> StoreData) = mutex.withLock {
        val next = transform(_state.value)
        _state.value = next
        persist(next)
    }

    /**
     * In-memory-only mutation: updates the live state without writing the store
     * to disk. Used for latency, which is transient (re-measured every launch),
     * persisting it on every single ping serialized the whole store to disk N
     * times per ping-burst, which froze the UI for minutes on a 50-server list.
     */
    private suspend fun updateInMemory(transform: (StoreData) -> StoreData) = mutex.withLock {
        _state.value = transform(_state.value)
    }

    /**
     * Persist the current in-memory state to disk once. Used to flush a whole
     * latency burst at its end (not per-ping, that serialized the store N times
     * per burst and froze the UI), so measured pings survive process death and
     * "Sort by ping" works on return without a manual re-ping.
     */
    private suspend fun persistCurrent() = mutex.withLock { persist(_state.value, clearDegraded = false) }

    // ---- profiles ----

    suspend fun addProfile(profile: Profile) = update { s ->
        s.copy(profiles = s.profiles + profile.withTimestamp())
    }

    suspend fun addProfiles(profiles: List<Profile>) = update { s ->
        s.copy(profiles = s.profiles + profiles.map { it.withTimestamp() })
    }

    suspend fun deleteProfile(id: String) = update { s ->
        s.copy(profiles = s.profiles.filterNot { it.id == id })
    }

    suspend fun replaceProfile(profile: Profile) = update { s ->
        s.copy(profiles = s.profiles.map { if (it.id == profile.id) profile else it })
    }

    /**
     * Single-server latency write. Routes through the persisting [updateLatencies]
     * so a manual per-row ping and the post-refresh per-server re-ping survive a
     * return to Servers / process death (one in-memory emission + one disk flush),
     * exactly like the batch path. Previously this was in-memory only, so those
     * single-id results were silently lost.
     */
    suspend fun updateLatency(id: String, latencyMs: Int) =
        updateLatencies(mapOf(id to latencyMs), persist = true)

    /**
     * Writes a batch of measured latencies to the live state, one emission for the whole
     * batch rather than one per server.
     *
     * [persist] defaults to false. A ping burst publishes on a clock, so persisting each
     * batch would serialize the whole store dozens of times in the first seconds of a
     * cold start. Callers running a burst flush once at the end via [persistLatencies];
     * a lone measurement persists on the spot.
     */
    suspend fun updateLatencies(latencies: Map<String, Int>, persist: Boolean = false) {
        if (latencies.isEmpty()) return
        updateInMemory { s ->
            val now = System.currentTimeMillis()
            s.copy(
                profiles = s.profiles.map { p ->
                    latencies[p.id]?.let { p.copy(latencyMs = it, latencyAtMs = now) } ?: p
                },
            )
        }
        if (persist) persistCurrent()
    }

    /**
     * Flushes measured latencies to disk once, after a burst.
     *
     * Keeps the measured order durable so «сортировка по пингу» survives leaving the
     * screen, without paying for that on every intermediate batch.
     */
    suspend fun persistLatencies() = persistCurrent()

    suspend fun clearProfiles() = update { it.copy(profiles = emptyList()) }

    /** Flips [Profile.favorite]. No-op when the id is unknown. */
    suspend fun toggleFavorite(id: String) = update { s ->
        s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
    }

    /** Sets [Profile.excludedFromTest]. No-op when the id is unknown. */
    suspend fun setExcludedFromTest(id: String, value: Boolean) = update { s ->
        s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(excludedFromTest = value) else it })
    }

    /**
     * Excludes, or re-includes, every server of one subscription in one write.
     *
     * Per-server exclusion is a long-press menu each, which on a subscription of sixty is
     * not something anyone does.
     *
     * One update rather than a loop of them: the store is a single state flow, so sixty
     * separate writes would be sixty emissions and sixty recompositions of whatever is
     * showing the list.
     */
    suspend fun setSubscriptionExcludedFromTest(subscriptionId: String, value: Boolean) = update { s ->
        s.copy(
            profiles = s.profiles.map {
                if (it.subscriptionId == subscriptionId) it.copy(excludedFromTest = value) else it
            },
        )
    }

    /**
     * Sets (or clears, when [awg] is null) the AmneziaWG params on a WireGuard profile.
     * A populated value routes the profile to the separate AmneziaWG-Go runtime.
     * A null value keeps the profile on the standard WireGuard runtime.
     * No-op for non-WireGuard profiles or an unknown id.
     */
    suspend fun setAwgParams(id: String, awg: com.th3web.lean.data.model.AmneziaParams?) = update { s ->
        s.copy(profiles = s.profiles.map { p ->
            val o = p.outbound
            if (p.id == id && o is com.th3web.lean.data.model.Outbound.WireGuard) {
                p.copy(outbound = o.copy(awg = awg))
            } else {
                p
            }
        })
    }

    /**
     * Renames a profile. The name is trimmed; a blank name is a no-op.
     * Durable for manual servers; best-effort for subscription servers, the
     * next subscription refresh restores the provider-supplied name (reconcile
     * carries over id/latency/createdAt/favorite/excludedFromTest, but not the
     * name).
     */
    suspend fun renameProfile(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        update { s ->
            s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(name = trimmed) else it })
        }
    }

    fun findProfile(id: String?): Profile? = id?.let { pid -> _state.value.profiles.find { it.id == pid } }

    /** Outcome of a single .conf import; [isAmnezia] => it carried AmneziaWG obfs. */
    data class ConfImportResult(val count: Int, val isAmnezia: Boolean)

    /**
     * Import from pasted text (one or many links, or a base64 blob). A WireGuard /
     * AmneziaWG `.conf` (detected by the `[Interface]` header) is parsed first,
     * before the share-link path, and counts as a single profile (0 on parse
     * failure). To learn whether an imported .conf was AmneziaWG (obfuscation
     * dropped), call [importConfFile] instead: it returns the richer result.
     */
    suspend fun importFromText(text: String): Int {
        if (WgConfig.looksLikeWgConf(text)) {
            val profile = WgConfig.parseProfile(text, fallbackName = "") ?: return 0
            addProfiles(listOf(profile))
            return 1
        }
        // A pasted mieru/naive config, not just a file-picked one, people copy these out
        // of a panel as readily as they copy a link.
        ProxyConfigFiles.parse(text).takeIf { it.isNotEmpty() }?.let { parsed ->
            addProfiles(parsed)
            return parsed.size
        }
        // parseAll, not parse: one mieru link can describe several endpoints, and taking
        // the first would silently drop the rest.
        val single = ShareLinks.parseAll(text.trim())
        val parsed = if (single.isNotEmpty()) single else Subscriptions.parseBody(text)
        if (parsed.isNotEmpty()) addProfiles(parsed)
        return parsed.size
    }

    /**
     * Import a `.conf` file's contents. When the text is a WireGuard/AmneziaWG
     * config it is parsed and added (count 1), and [ConfImportResult.isAmnezia]
     * reports whether it carried AmneziaWG obfuscation.
     * When the text is not a `.conf`, falls back to [importFromText] so a misnamed
     * file full of share links still imports (isAmnezia = false).
     */
    suspend fun importConfFile(text: String, name: String): ConfImportResult {
        if (WgConfig.looksLikeWgConf(text)) {
            val profile = WgConfig.parseProfile(text, fallbackName = name)
                ?: return ConfImportResult(0, false)
            addProfiles(listOf(profile))
            val isAmnezia = (profile.outbound as? com.th3web.lean.data.model.Outbound.WireGuard)
                ?.awg != null
            return ConfImportResult(1, isAmnezia)
        }
        // Helper-protocol config files (mieru's JSON, naive's JSON, a mihomo YAML
        // carrying mieru proxies). Tried before the share-link path because a mieru
        // config is valid JSON and would otherwise fall through to the subscription
        // parser, which looks for an Xray config and finds nothing.
        ProxyConfigFiles.parse(text, fallbackName = name).takeIf { it.isNotEmpty() }?.let { parsed ->
            addProfiles(parsed)
            return ConfImportResult(parsed.size, false)
        }
        return ConfImportResult(importFromText(text), false)
    }

    // ---- subscriptions ----

    /** Fetch result: profiles already tagged with the subscription id + merged metadata. */
    private class FetchedSub(val profiles: List<Profile>, val meta: SubscriptionMeta)

    /**
     * Downloads and parses a subscription, with a one-shot User-Agent fallback.
     *
     * Panels gate the response format by UA: a v2rayNG-family UA yields the full
     * Xray-JSON list including Hysteria2, a sing-box/Lean-family one yields only base64
     * share links. The primary UA is whatever the user configured. If that attempt parses
     * zero servers, it retries once with [FALLBACK_UA] and keeps whichever attempt parsed
     * more, ties keep the primary, so a failed fetch plus an empty fallback surfaces as a
     * failure rather than as an empty list that would wipe the subscription's profiles.
     */
    private suspend fun fetchSub(url: String, subId: String, previousCount: Int = 0): Result<FetchedSub> {
        val primaryUa = Http.userAgent
        val primary = Http.getFull(url)
        var parseError: Throwable? = null
        var resp = primary.getOrNull()
        var parsed = resp?.let { r ->
            runCatching { Subscriptions.parseBodyFull(r.body) }
                .onFailure { parseError = it }
                .getOrNull()
        }
        var winnerUa = primaryUa
        // Safe-call, not smart cast: `parsed` is mutated by the closure below.
        if (parsed?.profiles.isNullOrEmpty()) {
            Http.getFull(url, userAgentOverride = FALLBACK_UA).onSuccess { retryResp ->
                val retryParsed = runCatching { Subscriptions.parseBodyFull(retryResp.body) }.getOrNull()
                if (retryParsed != null && retryParsed.profiles.size > (parsed?.profiles?.size ?: 0)) {
                    resp = retryResp
                    parsed = retryParsed
                    winnerUa = FALLBACK_UA
                }
            }
        }
        // A transient panel hiccup (mid-refresh backend inconsistency, a brief rate
        // limit, a truncated response) can come back 200 OK with a real but tiny
        // profile list instead of throwing or going empty, the isEmpty() guard above
        // never sees it, so a previously healthy subscription (e.g. 20 servers)
        // silently collapses to 1 on a bad refresh: reconcile() only ever emits
        // fresh.size profiles, so updateSubscription deletes the other 19 for real.
        // One same-UA retry, keeping whichever attempt has more profiles, catches
        // this without touching the legitimate case of a panel trimming a few dead
        // servers, it only fires when the count dropped to under a third of what
        // this subscription previously had.
        val afterFirstPass = parsed?.profiles?.size ?: 0
        if (previousCount > 0 && afterFirstPass in 1 until previousCount && afterFirstPass * 3 <= previousCount) {
            Http.getFull(url, userAgentOverride = winnerUa).onSuccess { retryResp ->
                val retryParsed = runCatching { Subscriptions.parseBodyFull(retryResp.body) }.getOrNull()
                if (retryParsed != null && retryParsed.profiles.size > (parsed?.profiles?.size ?: 0)) {
                    resp = retryResp
                    parsed = retryParsed
                }
            }
        }
        val r = resp
        val p = parsed
        // Treat an empty parse as failure, not success: a 200 with an empty/HTML/captcha
        // body parses to zero servers, and a successful fetch then reconcile(old, [])
        // would delete every server of the subscription (auto-fires in the refresh
        // worker). Failing here makes the callers' mapCatching skip the update, so the
        // existing servers are kept.
        if (r == null || p == null || p.profiles.isEmpty()) {
            return Result.failure(
                primary.exceptionOrNull() ?: parseError
                    ?: IllegalStateException("subscription body yielded no servers"),
            )
        }
        Log.i(
            TAG,
            "fetchSub: ua='$winnerUa' servers=${p.profiles.size}" +
                if (winnerUa != primaryUa) " (primary ua='$primaryUa' parsed none — fallback UA won)" else "",
        )
        return runCatching {
            // Header wins; body #directive fills gaps. userinfo only ever comes from headers.
            val meta = parseSubscriptionHeaders(r.headers).withFallback(p.directives)
            FetchedSub(p.profiles.map { it.copy(subscriptionId = subId) }, meta)
        }
    }

    /** Header/directive values overwrite only when present; userinfo replaces all four as a unit. */
    private fun applyMeta(sub: Subscription, f: FetchedSub): Subscription {
        val base = sub.copy(
            lastUpdated = System.currentTimeMillis(),
            profileCount = f.profiles.size,
            // exact previous semantics preserved: profile-web-page-url ?: support-url ?: old
            webPageUrl = f.meta.webPageUrl ?: f.meta.supportUrl ?: sub.webPageUrl,
            supportUrl = f.meta.supportUrl ?: sub.supportUrl,
            serverTitle = f.meta.title ?: sub.serverTitle,
            description = f.meta.description ?: sub.description,
            announce = f.meta.announce ?: sub.announce,
            updateIntervalMs = f.meta.updateIntervalMs ?: sub.updateIntervalMs,
        )
        val ui = f.meta.userInfo ?: return base
        return base.copy(
            uploadBytes = ui.upload,
            downloadBytes = ui.download,
            totalBytes = ui.total,
            expireEpochSec = ui.expire,
        )
    }

    /**
     * Keep profile identity stable across refreshes: match fresh rows to old rows by
     * outbound equality (data-class equals covers host/port/credentials/tls/transport),
     * falling back to name+host:port; carry over id, latencyMs, createdAt and the user
     * flags (favorite, excludedFromTest).
     *
     * AWG params (awg / amneziaUnsupported on a WireGuard outbound) are local user state,
     * not provider state: the user converts a plain-WG node to AmneziaWG on device, and a
     * refresh re-emits it with awg=null, which breaks value equality and would make the
     * node look brand new, losing its favourite flag, its params and its latency. So two
     * WireGuard outbounds also match while ignoring those fields ([sameWgIgnoringAwg]),
     * and the old profile's values are carried forward.
     *
     * Not a host-only or positional match: that corrupts Reality/CDN nodes sharing one
     * host:port.
     */
    @VisibleForTesting
    internal fun reconcile(old: List<Profile>, fresh: List<Profile>): List<Profile> {
        val pool = old.toMutableList()
        return fresh.map { f ->
            val m = pool.firstOrNull { it.outbound == f.outbound }
                ?: pool.firstOrNull { sameWgIgnoringAwg(it.outbound, f.outbound) }
                ?: pool.firstOrNull { it.name == f.name && it.displayHost == f.displayHost }
            if (m != null) {
                pool.remove(m)
                val mo = m.outbound
                val fo = f.outbound
                // Carry the user's local AWG state forward onto the fresh outbound.
                val outbound = if (fo is Outbound.WireGuard && mo is Outbound.WireGuard) {
                    fo.copy(awg = mo.awg, amneziaUnsupported = mo.amneziaUnsupported)
                } else {
                    fo
                }
                f.copy(
                    outbound = outbound,
                    id = m.id,
                    latencyMs = m.latencyMs,
                    createdAt = m.createdAt,
                    favorite = m.favorite,
                    excludedFromTest = m.excludedFromTest,
                )
            } else {
                f
            }
        }
    }

    /**
     * True when two WireGuard outbounds are the same peer, comparing everything except the
     * volatile local AmneziaWG fields (awg, amneziaUnsupported), so a refresh that drops
     * awg back to null still matches the node the user converted to AmneziaWG on-device.
     */
    private fun sameWgIgnoringAwg(a: Outbound, b: Outbound): Boolean =
        a is Outbound.WireGuard && b is Outbound.WireGuard &&
            a.copy(awg = null, amneziaUnsupported = false) == b.copy(awg = null, amneziaUnsupported = false)

    suspend fun addSubscription(name: String, url: String): Result<Int> {
        val sub = Subscription(name = name.ifBlank { url }, url = url)
        // Save the subscription first so an offline/failed fetch never loses the URL
        // (4pda: "вставляю ссылку, она хочет её скачать, и если нельзя, сохранить нельзя").
        update { s -> s.copy(subscriptions = s.subscriptions + sub) }
        // Then populate it; success fills profiles+meta, failure leaves the saved
        // (empty) sub for the user to refresh later via the card's ↻ button.
        // The fetch failure is propagated (no .recover), so the Add dialog can show
        // an inline error instead of toasting a misleading "Добавлено серверов: 0";
        // the URL is not lost because the sub was already persisted above.
        return fetchSub(url, sub.id).mapCatching { f ->
            update { s ->
                // If the user deleted this sub while the fetch was in flight, the
                // delete wins: don't re-add orphan profiles tagged to a gone sub.
                if (s.subscriptions.none { it.id == sub.id }) return@update s
                s.copy(
                    subscriptions = s.subscriptions.map { if (it.id == sub.id) applyMeta(sub, f) else it },
                    profiles = s.profiles + f.profiles.map { it.withTimestamp() },
                )
            }
            f.profiles.size
        }
    }

    /** When the last automatic sweep ran, so two entry points cannot double-fetch. */
    @Volatile private var lastAutoRefreshAt = 0L

    /**
     * Refreshes every subscription whose data is older than [staleMs].
     *
     * Exists for the usage counter. The traffic figure on a subscription card arrives with
     * the server list and nowhere else, so without a periodic re-fetch it moves only when
     * the user pulls to refresh by hand. Returning to the app is the natural moment to
     * ask, since that is when someone looks at the number.
     *
     * [lastAutoRefreshAt] guards the whole sweep, not each subscription: a cold start runs
     * the launch effect and onResume within a moment of each other, and both would
     * otherwise see the same stale timestamp and fetch twice.
     */
    suspend fun refreshStaleSubscriptions(staleMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastAutoRefreshAt < staleMs) return
        val due = _state.value.subscriptions.filter { now - it.lastUpdated >= staleMs }
        if (due.isEmpty()) return
        lastAutoRefreshAt = now
        due.forEach { runCatching { updateSubscription(it.id) } }
    }

    suspend fun updateSubscription(id: String): Result<Int> {
        val sub = _state.value.subscriptions.find { it.id == id }
            ?: return Result.failure(IllegalArgumentException("subscription not found"))
        return fetchSub(sub.url, id, previousCount = sub.profileCount).mapCatching { f ->
            update { s ->
                // Deleted mid-fetch → delete wins; don't resurrect orphan profiles.
                if (s.subscriptions.none { it.id == id }) return@update s
                // Replace this subscription's profiles, keep manual & other-sub profiles.
                val fresh = reconcile(s.profiles.filter { it.subscriptionId == id }, f.profiles)
                s.copy(
                    profiles = s.profiles.filterNot { it.subscriptionId == id } +
                        fresh.map { it.withTimestamp() },
                    subscriptions = s.subscriptions.map { if (it.id == id) applyMeta(it, f) else it },
                )
            }
            f.profiles.size
        }
    }

    /**
     * Edits a subscription's name and/or URL.
     *  - Same URL → rename only, no network; Result.success(current profileCount).
     *  - New URL → fetch the new URL, reconcile-replace this subscription's
     *   profiles and apply the new metadata; Result = fresh profile count.
     *  - Fetch failure → nothing changes (atomic: the name edit is dropped too).
     *  - Unknown id → Result.failure(IllegalArgumentException).
     */
    suspend fun editSubscription(id: String, name: String, url: String): Result<Int> {
        val sub = _state.value.subscriptions.find { it.id == id }
            ?: return Result.failure(IllegalArgumentException("subscription not found"))
        val newUrl = url.trim()
        val newName = name.trim().ifBlank { newUrl }
        if (newUrl == sub.url) {
            // Rename only, no network round-trip.
            update { s ->
                s.copy(subscriptions = s.subscriptions.map {
                    if (it.id == id) it.copy(name = newName) else it
                })
            }
            return Result.success(sub.profileCount)
        }
        return fetchSub(newUrl, id).mapCatching { f ->
            update { s ->
                // Deleted mid-fetch → delete wins; don't resurrect orphan profiles.
                if (s.subscriptions.none { it.id == id }) return@update s
                val fresh = reconcile(s.profiles.filter { it.subscriptionId == id }, f.profiles)
                s.copy(
                    profiles = s.profiles.filterNot { it.subscriptionId == id } +
                        fresh.map { it.withTimestamp() },
                    subscriptions = s.subscriptions.map {
                        if (it.id == id) applyMeta(it.copy(name = newName, url = newUrl), f) else it
                    },
                )
            }
            f.profiles.size
        }
    }

    suspend fun deleteSubscription(id: String, keepProfiles: Boolean = false) = update { s ->
        s.copy(
            subscriptions = s.subscriptions.filterNot { it.id == id },
            profiles = if (keepProfiles) s.profiles else s.profiles.filterNot { it.subscriptionId == id },
        )
    }

    // ---- folders ----

    /**
     * Create a folder and return it.
     *
     * The name is not required to be unique: two providers can reasonably be filed under
     * the same word, and refusing that would be a rule the user did not ask for. Ordering
     * appends, so a new folder lands at the bottom where it was made rather than jumping
     * into the middle of the list.
     */
    suspend fun addFolder(name: String): Folder {
        val folder = Folder(name = name.trim(), order = (state.value.folders.maxOfOrNull { it.order } ?: -1) + 1)
        update { s -> s.copy(folders = s.folders + folder) }
        return folder
    }

    suspend fun renameFolder(id: String, name: String) = update { s ->
        s.copy(folders = s.folders.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    /**
     * Delete a folder. Its subscriptions are kept and move back to the top level.
     *
     * Deleting servers here would be a trap: a folder is an arrangement, and nothing about
     * dragging a subscription into one says the user agreed to lose it later.
     */
    suspend fun deleteFolder(id: String) = update { s ->
        s.copy(
            folders = s.folders.filterNot { it.id == id },
            subscriptions = s.subscriptions.map { if (it.folderId == id) it.copy(folderId = "") else it },
        )
    }

    /** Move a subscription into [folderId], or out to the top level when it is blank. */
    suspend fun moveSubscriptionToFolder(subscriptionId: String, folderId: String) = update { s ->
        s.copy(
            subscriptions = s.subscriptions.map {
                if (it.id == subscriptionId) it.copy(folderId = folderId) else it
            },
        )
    }

    // ---- backup ----

    /** Backup "Replace" mode: the incoming store becomes the store, verbatim. */
    suspend fun replaceStore(data: StoreData) = update { data }

    /** What [mergeStore] actually added (both counts after de-duplication). */
    data class MergeResult(val addedProfiles: Int, val addedSubscriptions: Int)

    /**
     * Backup "Merge" mode, adds, never deletes. Returns the number of profiles
     * and subscriptions actually added (see [MergeResult]).
     *
     * Subscriptions: an incoming subscription is skipped when its URL already
     * exists and its profiles are remapped to the existing subscription. An id
     * collision with a different URL is resolved with a fresh unique id.
     * Profiles are de-duplicated by outbound only within the same subscription
     * ownership; id collisions with different profiles are assigned unique ids.
     */
    suspend fun mergeStore(data: StoreData): MergeResult {
        var added = MergeResult(0, 0)
        update { s ->
            // Folders first: an incoming subscription names the folder it came from, and
            // that id means nothing in this store. Same name means the same folder, so a
            // restore lands the provider back where the user had filed it instead of
            // building a second «Работа» beside the first one.
            val foldersByName = s.folders.associateBy { it.name.trim().lowercase() }
            val existingFolderIds = s.folders.map { it.id }.toMutableSet()
            val newFolders = mutableListOf<Folder>()
            val folderIdRemap = mutableMapOf<String, String>()
            var nextOrder = (s.folders.maxOfOrNull { it.order } ?: -1) + 1
            for (folder in data.folders) {
                val match = foldersByName[folder.name.trim().lowercase()]
                if (match != null) {
                    folderIdRemap[folder.id] = match.id
                } else {
                    val targetId = uniqueId(folder.id, existingFolderIds)
                    newFolders += folder.copy(id = targetId, order = nextOrder++)
                    existingFolderIds += targetId
                    folderIdRemap[folder.id] = targetId
                }
            }

            val existingSubIds = s.subscriptions.map { it.id }.toMutableSet()
            val subIdByUrl = s.subscriptions.associate { it.url to it.id }.toMutableMap()
            val newSubs = mutableListOf<Subscription>()
            val subIdRemap = mutableMapOf<String, String>()
            for (sub in data.subscriptions) {
                val byUrl = subIdByUrl[sub.url]
                if (byUrl != null) {
                    subIdRemap[sub.id] = byUrl
                } else {
                    val targetId = uniqueId(sub.id, existingSubIds)
                    // A folderId this store never heard of would file the subscription
                    // behind a header that is not drawn, so it would simply vanish.
                    val folder = sub.folderId.takeIf { it.isNotEmpty() }?.let { folderIdRemap[it] } ?: ""
                    val mapped = sub.copy(id = targetId, folderId = folder)
                    newSubs += mapped
                    existingSubIds += targetId
                    subIdByUrl[sub.url] = targetId
                    subIdRemap[sub.id] = targetId
                }
            }
            val existingProfileIds = s.profiles.map { it.id }.toMutableSet()
            val existingProfiles = s.profiles
                .map { it.subscriptionId to it.outbound }
                .toMutableSet()
            val newProfiles = mutableListOf<Profile>()
            for (p in data.profiles) {
                var mapped = p.subscriptionId?.let { subIdRemap[it] }
                    ?.let { p.copy(subscriptionId = it) } ?: p
                val profileKey = mapped.subscriptionId to mapped.outbound
                if (profileKey in existingProfiles) continue
                val targetId = uniqueId(mapped.id, existingProfileIds)
                if (targetId != mapped.id) mapped = mapped.copy(id = targetId)
                newProfiles += mapped.withTimestamp()
                existingProfileIds += mapped.id
                existingProfiles += profileKey
            }
            added = MergeResult(addedProfiles = newProfiles.size, addedSubscriptions = newSubs.size)
            s.copy(
                profiles = s.profiles + newProfiles,
                subscriptions = s.subscriptions + newSubs,
                folders = s.folders + newFolders,
            )
        }
        return added
    }

    private fun Profile.withTimestamp(): Profile =
        if (createdAt == 0L) copy(createdAt = System.currentTimeMillis()) else this

    private fun uniqueId(preferred: String, used: Set<String>): String {
        if (preferred !in used) return preferred
        var suffix = 1
        while ("$preferred-$suffix" in used) suffix++
        return "$preferred-$suffix"
    }

    private companion object {
        private const val TAG = "ProfileRepository"

        /**
         * UA for the fetchSub retry: the v2rayNG-family gating-unlock UA. When the
         * configured primary UA (now user-spoofable, defaulting to "Lean/…") lands
         * on a panel that only serves the full list to v2rayNG and parses zero
         * servers, this retry recovers the complete Xray-JSON list incl. Hysteria2.
         */
        private const val FALLBACK_UA = "v2rayNG/1.9.5"
    }
}
