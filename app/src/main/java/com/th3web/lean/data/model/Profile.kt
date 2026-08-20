package com.th3web.lean.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A saved server entry. Wraps an [Outbound] with UI/bookkeeping metadata.
 *
 * [@Immutable]: all fields are `val`s of stable types ([Outbound] is itself
 * @Immutable), and instances are replaced, never mutated. This makes [ServerRow]
 * skippable so the 50-row list stops recomposing every visible row on unrelated
 * state changes, the key fix for scroll jank.
 */
@Immutable
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val outbound: Outbound,
    /** Subscription this profile came from, or null if added manually. */
    val subscriptionId: String? = null,
    /** Last measured latency in ms; null = untested, -1 = unreachable. */
    val latencyMs: Int? = null,
    /**
     * When [latencyMs] was recorded (epoch ms; 0 = never). Compared against the core's
     * own urltest timestamp so the newer of the two wins in the row, without it, a
     * manual ping taken while connected computed and stored a number the row kept
     * hiding behind a stale core reading.
     */
    val latencyAtMs: Long = 0,
    val createdAt: Long = 0L,
    /** User-starred. Survives subscription refresh via reconcile(). */
    val favorite: Boolean = false,
    /**
     * Excluded from the automatic speed test: skipped by bulk pings and left out
     * of the urltest group (still manually selectable/pingable). defaulted for
     * lean_store.json backward-compat. Survives subscription refresh via
     * reconcile().
     */
    val excludedFromTest: Boolean = false,
) {
    val displayHost: String get() = "${outbound.server}:${outbound.serverPort}"
}

/**
 * A subscription source: a URL whose body is a (usually base64) list of share
 * links. Updated on demand or on a schedule.
 *
 * @Immutable for the same Compose-skippability reason as [Profile] (rendered in
 * the subscription cards). All fields beyond the original set are defaulted so pre-existing
 * `lean_store.json` files keep decoding (the store loader falls back to an
 * empty StoreData on any decode failure: a non-defaulted field would
 * silently wipe the store).
 */
@Immutable
@Serializable
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,
    val profileCount: Int = 0,
    /** profile-web-page-url header (fallback: support-url), the "Поддержка" link. */
    val webPageUrl: String = "",
    // ---- new, all defaulted (lean_store.json backward-compat) ----
    /** profile-title header / #profile-title: directive ("base64:"-aware). "" = none. */
    val serverTitle: String = "",
    /** profile-description header / profile-title '|' tail, provider subtitle. "" = none. */
    val description: String = "",
    /** support-url, stored separately even though webPageUrl keeps its fallback. */
    val supportUrl: String = "",
    /** announce header / #announce: directive ("base64:"-aware). Stored now, UI later. */
    val announce: String = "",
    /** subscription-userinfo: bytes. null = provider never reported. */
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    /** null = unknown, 0 = unlimited. */
    val totalBytes: Long? = null,
    /** Unix seconds (normalized). null = no expiry reported. */
    val expireEpochSec: Long? = null,
    /** profile-update-interval, ms. 0 = not set → follow global auto-update. */
    val updateIntervalMs: Long = 0L,
    /** Id of the [Folder] this subscription sits in. "" = loose, at the top level. */
    val folderId: String = "",
) {
    /** Upload+download when at least one was reported; null = provider never reported. */
    val usedBytes: Long? get() =
        if (uploadBytes == null && downloadBytes == null) null
        else (uploadBytes ?: 0L) + (downloadBytes ?: 0L)

    /** Incy semantics: total == 0 means unlimited (null stays "unknown"). */
    val isUnlimited: Boolean get() = totalBytes == 0L

    /** True when there is anything worth rendering in a traffic strip. */
    val hasTraffic: Boolean get() = usedBytes != null || totalBytes != null

    // Clamp to the same upper bound as normalizeExpire (32e9): expireEpochSec*1000 on a
    // corrupt/crafted value > ~9.2e15 overflows Long → a far-future expiry would read as
    // "expired". Out-of-range values are treated as "no expiry".
    private val expireMsOrNull: Long? get() =
        expireEpochSec?.takeIf { it in 1L..32_000_000_000L }?.times(1000)

    val isExpired: Boolean get() =
        expireMsOrNull?.let { it < System.currentTimeMillis() } ?: false

    /** Whole days remaining, floored; 0 when <24h left or expired; null when no expiry. */
    val daysLeft: Int? get() =
        expireMsOrNull?.let { ((it - System.currentTimeMillis()).coerceAtLeast(0L) / 86_400_000L).toInt() }

    /** User-typed name wins; server title used only when the user left name blank (name==url). */
    val displayName: String get() =
        if ((name == url || name.isBlank()) && serverTitle.isNotBlank()) serverTitle else name
}

/**
 * A user-made group of subscriptions on the servers screen.
 *
 * Purely an arrangement: a folder owns no servers of its own and changes no routing, it
 * only decides which subscriptions are drawn together and behind one collapsible header.
 * A subscription refers to its folder rather than the folder listing its members, so
 * deleting a folder is one pass that clears [Subscription.folderId] and can never leave a
 * dangling id behind.
 */
@Immutable
@Serializable
data class Folder(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Position in the list, ascending. Ties fall back to [name]. */
    val order: Int = 0,
)
