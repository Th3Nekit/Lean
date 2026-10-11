package com.th3web.lean.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The last few speed-test runs, so a number has something to be compared with.
 *
 * A single reading answers almost nothing: 42 Мбит/с is good on one tariff and broken on
 * another, and the question people actually ask is "is it worse than yesterday" or "is
 * this server slower than the one I used before". That needs history, and history needs
 * the server's name, which is why an entry carries it.
 *
 * Public, unlike its sibling [SettingsSnapshotStore]: [Entry] is what the screen's
 * ViewModel publishes, and an internal type behind a public StateFlow does not compile.
 *
 * SharedPreferences, like [SettingsSnapshotStore], rather than the settings DataStore:
 * this is a small append-only list read once when a screen opens, and putting it in
 * [Settings] would mean every finished run re-emits the whole settings object to every
 * collector in the app.
 */
class SpeedTestHistoryStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Serializable
    data class Entry(
        val atMillis: Long,
        /** Server the tunnel was on, or empty when the run was made without one. */
        val serverName: String = "",
        val downloadMbps: Double? = null,
        val uploadMbps: Double? = null,
        val pingMs: Int? = null,
        val jitterMs: Int? = null,
        /** The same run measured past the tunnel, when there was a tunnel to bypass. */
        val directDownloadMbps: Double? = null,
        val directUploadMbps: Double? = null,
        /**
         * Which target was measured ([com.th3web.lean.data.net.SpeedTest.Endpoint.id]).
         * Without it the history compares numbers that answer different questions: a run
         * to Moscow and a run to Nuremberg are not the same measurement.
         */
        val endpointId: String = "",
        /**
         * How to name that target in the history, e.g. "Rostelecom · Москва, Russia".
         * Stored rather than resolved: the server list is per-connection and transient,
         * so an id alone would be unprintable the next time the screen opens somewhere
         * else.
         */
        val endpointLabel: String = "",
        /** True when the run was made with no tunnel up at all. */
        val direct: Boolean = false,
    )

    fun load(): List<Entry> = runCatching {
        preferences.getString(VALUE_KEY, null)
            ?.let { Serialization.json.decodeFromString<List<Entry>>(it) }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** Adds one run and keeps the newest [MAX_ENTRIES]. Newest first. */
    fun add(entry: Entry): List<Entry> {
        val updated = (listOf(entry) + load()).take(MAX_ENTRIES)
        runCatching {
            preferences.edit()
                .putString(VALUE_KEY, Serialization.json.encodeToString(updated))
                .apply()
        }
        return updated
    }

    fun clear() {
        runCatching { preferences.edit().remove(VALUE_KEY).apply() }
    }

    /**
     * The target the user picked last, remembered because it is a preference and not a
     * per-run choice: someone whose exit node is in Germany measures against Germany
     * every time, and re-picking it on every visit would be the screen forgetting the one
     * thing it was told.
     */
    var selectedEndpointId: String?
        get() = runCatching { preferences.getString(ENDPOINT_KEY, null) }.getOrNull()
        set(value) {
            runCatching { preferences.edit().putString(ENDPOINT_KEY, value).apply() }
        }

    /**
     * Which legs a run measures ([com.th3web.lean.ui.SpeedTestViewModel.Scope]), kept for
     * the same reason as [selectedEndpointId]: someone who only ever wants the figure
     * through the tunnel should not have to say so on every visit, and the comparison
     * costs twice the time and twice the traffic.
     */
    var scopeId: String?
        get() = runCatching { preferences.getString(SCOPE_KEY, null) }.getOrNull()
        set(value) {
            runCatching { preferences.edit().putString(SCOPE_KEY, value).apply() }
        }

    companion object {
        internal const val VALUE_KEY = "runs"
        internal const val ENDPOINT_KEY = "endpoint"
        internal const val SCOPE_KEY = "scope"
        private const val PREFERENCES_NAME = "lean_speedtest_history"

        /**
         * Enough to see a trend over a few days of ordinary use, small enough that the
         * whole list is one cheap string read on the way into the screen.
         */
        const val MAX_ENTRIES = 20
    }
}
