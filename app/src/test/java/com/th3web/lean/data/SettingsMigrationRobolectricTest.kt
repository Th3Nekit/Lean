package com.th3web.lean.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsMigrationRobolectricTest {

    @Test
    fun `fresh settings use the NekoBox baseline`() = runBlocking {
        val preferences = SettingsMigration.migrate(emptyPreferences())

        assertEquals(SettingsDefaults.SCHEMA_VERSION, preferences[SettingsKeys.SCHEMA_VERSION])
        assertEquals(SettingsDefaults.SERVICE_MODE, preferences[SettingsKeys.SERVICE_MODE])
        assertEquals(SettingsDefaults.IPV6, preferences[SettingsKeys.IPV6])
        assertEquals(SettingsDefaults.TUN_STACK, preferences[SettingsKeys.TUN_STACK])
        assertEquals(SettingsDefaults.TUN_MTU, preferences[SettingsKeys.TUN_MTU])
        assertEquals(SettingsDefaults.WG_MTU, preferences[SettingsKeys.WG_MTU])
        assertEquals(SettingsDefaults.SNIFF_ENABLED, preferences[SettingsKeys.SNIFF_ENABLED])
        assertFalse(preferences[SettingsKeys.SNIFF_OVERRIDE_DESTINATION]!!)
        assertFalse(preferences[SettingsKeys.SNIFF_RESOLVE_DESTINATION]!!)
        assertTrue(preferences[SettingsKeys.DNS_ROUTING]!!)
        assertTrue(preferences[SettingsKeys.FAKE_DNS]!!)
        assertTrue(preferences[SettingsKeys.RESET_CONNECTIONS_ON_NETWORK_CHANGE]!!)
        assertFalse(preferences[SettingsKeys.ALLOW_INSECURE]!!)
        assertEquals(SettingsDefaults.REMOTE_DNS, preferences[SettingsKeys.REMOTE_DNS])
        // Not actually NekoBox's own value any more — DIRECT_DNS is the one field this
        // baseline deliberately departs from it on, after v4 reverted the AliDNS bootstrap
        // regression. Left in this shared "everything resolves through the constant" test
        // because the assertion is still correct; the deviation is documented on the
        // constant itself and covered explicitly by the v3→v4 migration tests below.
        assertEquals(SettingsDefaults.DIRECT_DNS, preferences[SettingsKeys.DIRECT_DNS])
        assertEquals(SettingsDefaults.TEST_URL, preferences[SettingsKeys.PING_URL])
        assertEquals(SettingsDefaults.PROFILE_TEST_TIMEOUT_MS, preferences[SettingsKeys.PING_TIMEOUT])
        assertEquals(SettingsDefaults.ACTIVE_CONNECTION_TEST_TIMEOUT_MS, preferences[SettingsKeys.ACTIVE_CONNECTION_TEST_TIMEOUT])
        assertEquals(SettingsDefaults.SERVER_DOMAIN_STRATEGY, preferences[SettingsKeys.IP_STRATEGY])
        assertEquals(443, SettingsDefaults.HYSTERIA2_PORT)
        assertEquals(10, SettingsDefaults.HYSTERIA2_HOP_INTERVAL_SECONDS)
        assertEquals(0, SettingsDefaults.HYSTERIA2_BANDWIDTH_MBPS)
    }

    @Test
    fun `migration preserves explicit existing values and adds only missing keys`() = runBlocking {
        val existing = mutablePreferencesOf(
            SettingsKeys.IPV6 to true,
            SettingsKeys.TUN_MTU to 1_400,
            SettingsKeys.WG_MTU to 1_420,
            SettingsKeys.SNIFF_ENABLED to false,
            SettingsKeys.FAKE_DNS to false,
            SettingsKeys.RESET_CONNECTIONS_ON_NETWORK_CHANGE to false,
            SettingsKeys.ALLOW_INSECURE to true,
            SettingsKeys.REMOTE_DNS to "https://custom.example/dns-query",
            // Deliberately NOT a value any build shipped as the default: the schema
            // steps rewrite the previous DEFAULT, so a fixture using one would be
            // testing the rewrite, not the "explicit choice survives" contract.
            SettingsKeys.DIRECT_DNS to "https://dns.quad9.net/dns-query",
            SettingsKeys.PING_URL to "https://custom.example/ping",
            SettingsKeys.PING_TIMEOUT to 9_000,
            SettingsKeys.IP_STRATEGY to "prefer_ipv6",
        )

        val preferences = SettingsMigration.migrate(existing)

        assertTrue(preferences[SettingsKeys.IPV6]!!)
        assertEquals(1_400, preferences[SettingsKeys.TUN_MTU])
        assertEquals(1_420, preferences[SettingsKeys.WG_MTU])
        assertFalse(preferences[SettingsKeys.SNIFF_ENABLED]!!)
        assertFalse(preferences[SettingsKeys.FAKE_DNS]!!)
        assertFalse(preferences[SettingsKeys.RESET_CONNECTIONS_ON_NETWORK_CHANGE]!!)
        assertTrue(preferences[SettingsKeys.ALLOW_INSECURE]!!)
        assertEquals("https://custom.example/dns-query", preferences[SettingsKeys.REMOTE_DNS])
        assertEquals("https://dns.quad9.net/dns-query", preferences[SettingsKeys.DIRECT_DNS])
        assertEquals("https://custom.example/ping", preferences[SettingsKeys.PING_URL])
        assertEquals(9_000, preferences[SettingsKeys.PING_TIMEOUT])
        assertEquals("prefer_ipv6", preferences[SettingsKeys.IP_STRATEGY])
        assertEquals(SettingsDefaults.SERVICE_MODE, preferences[SettingsKeys.SERVICE_MODE])
        assertEquals(SettingsDefaults.ACTIVE_CONNECTION_TEST_TIMEOUT_MS, preferences[SettingsKeys.ACTIVE_CONNECTION_TEST_TIMEOUT])
    }

    /**
     * v1 shipped NekoBox's AliDNS default; v2 reverted it to "local"; v3 restored NekoBox
     * parity again; v4 reverts it a second time — for real this time, after a diagnostics
     * report showed the AliDNS bootstrap simply cannot be reached on a restrictive/
     * "whitelist" mobile tariff, so no profile could connect there at all. A v1 install
     * replays all four steps and lands on TODAY's default ("local"), not NekoBox's.
     */
    @Test
    fun `a v1 install ends on today's bootstrap resolver after replaying all four steps`() = runBlocking {
        val v1Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 1,
            SettingsKeys.DIRECT_DNS to "https://223.5.5.5/dns-query",
            SettingsKeys.IP_STRATEGY to "prefer_ipv4",
        )

        val preferences = SettingsMigration.migrate(v1Install)

        assertEquals(SettingsDefaults.DIRECT_DNS, preferences[SettingsKeys.DIRECT_DNS])
        assertEquals("local", preferences[SettingsKeys.DIRECT_DNS])
        // v2 rewrote the strategy to "auto" and v3 leaves it there: "auto" IS the right
        // in-tunnel default, and NekoBox's prefer_ipv4 belongs to the server-dialing axis.
        assertEquals("auto", preferences[SettingsKeys.IP_STRATEGY])
        assertEquals(SettingsDefaults.SCHEMA_VERSION, preferences[SettingsKeys.SCHEMA_VERSION])
    }

    /**
     * v2 shipped "local"; v3 moved exactly that value to NekoBox's AliDNS; a v2 install
     * therefore passes through the AliDNS value INSIDE the chain before v4 reverts it back
     * to "local" — the intermediate step matters (it is why v4's condition is written
     * against the v3-shipped constant, not against whatever a v2 install started with).
     */
    @Test
    fun `upgrade from v2 lands back on the local resolver via v3 then v4`() = runBlocking {
        val v2Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 2,
            SettingsKeys.DIRECT_DNS to "local",
            SettingsKeys.IP_STRATEGY to "auto",
        )

        val preferences = SettingsMigration.migrate(v2Install)

        assertEquals("local", preferences[SettingsKeys.DIRECT_DNS])
        assertEquals("auto", preferences[SettingsKeys.IP_STRATEGY])
        assertEquals(SettingsDefaults.SCHEMA_VERSION, preferences[SettingsKeys.SCHEMA_VERSION])
    }

    /**
     * The regression fix itself: an install actually sitting at v3 with the AliDNS value it
     * shipped (not replayed from further back — this is the direct v3→v4 step real installs
     * on the released build take) gets rewritten to the current default, and a resolver the
     * user picked themselves survives untouched.
     */
    @Test
    fun `upgrade from v3 reverts the AliDNS bootstrap regression`() = runBlocking {
        val v3Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 3,
            SettingsKeys.DIRECT_DNS to "https://223.5.5.5/dns-query",
        )

        val preferences = SettingsMigration.migrate(v3Install)

        assertEquals("local", preferences[SettingsKeys.DIRECT_DNS])
        assertEquals(SettingsDefaults.SCHEMA_VERSION, preferences[SettingsKeys.SCHEMA_VERSION])
    }

    @Test
    fun `upgrade from v3 keeps a user-chosen bootstrap resolver`() = runBlocking {
        val v3Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 3,
            SettingsKeys.DIRECT_DNS to "1.1.1.1",
        )

        val preferences = SettingsMigration.migrate(v3Install)

        assertEquals("1.1.1.1", preferences[SettingsKeys.DIRECT_DNS])
    }

    /**
     * v1-v4 all shipped a bare TCP ping with an unused Cloudflare URL sitting dormant as
     * the GET/HEAD fallback. Replaying the FULL chain from v4 moves both: v5 rewrites the
     * dormant URL to the GrapheneOS generate_204 target, then v6 moves the protocol
     * default from TCP to URL Test.
     */
    @Test
    fun `upgrade from v4 moves both the dormant ping URL and the TCP protocol default`() = runBlocking {
        val v4Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 4,
            SettingsKeys.PING_PROTOCOL to "TCP",
            SettingsKeys.PING_URL to "http://cp.cloudflare.com/",
        )

        val preferences = SettingsMigration.migrate(v4Install)

        assertEquals(SettingsDefaults.PING_PROTOCOL, preferences[SettingsKeys.PING_PROTOCOL])
        assertEquals(SettingsDefaults.TEST_URL, preferences[SettingsKeys.PING_URL])
        assertEquals(SettingsDefaults.SCHEMA_VERSION, preferences[SettingsKeys.SCHEMA_VERSION])
    }

    @Test
    fun `upgrade from v4 keeps a user-chosen ping protocol and url`() = runBlocking {
        val v4Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 4,
            SettingsKeys.PING_PROTOCOL to "ICMP",
            SettingsKeys.PING_URL to "https://my-own-check.example.com/204",
        )

        val preferences = SettingsMigration.migrate(v4Install)

        assertEquals("ICMP", preferences[SettingsKeys.PING_PROTOCOL])
        assertEquals("https://my-own-check.example.com/204", preferences[SettingsKeys.PING_URL])
    }

    /**
     * The v5→v6 step on its own: an install sitting at v5 (the short-lived build where
     * the URL had already moved but TCP was still the ping default) picks up URL Test.
     */
    @Test
    fun `upgrade from v5 switches the TCP ping default to URL Test`() = runBlocking {
        val v5Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 5,
            SettingsKeys.PING_PROTOCOL to "TCP",
            SettingsKeys.PING_URL to SettingsDefaults.TEST_URL,
        )

        val preferences = SettingsMigration.migrate(v5Install)

        assertEquals(SettingsDefaults.PING_PROTOCOL, preferences[SettingsKeys.PING_PROTOCOL])
        assertEquals(SettingsDefaults.TEST_URL, preferences[SettingsKeys.PING_URL])
        assertEquals(SettingsDefaults.SCHEMA_VERSION, preferences[SettingsKeys.SCHEMA_VERSION])
    }

    /**
     * The v6→v7 step: "info" was the shipped default and is a performance problem, not a
     * preference — at info the core narrates every connection, and that write load
     * eventually stalls the tunnel while it still reports «подключено».
     */
    @Test
    fun `upgrade from v6 quiets the default info log level`() = runBlocking {
        val v6Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 6,
            SettingsKeys.LOG_LEVEL to "info",
        )

        val preferences = SettingsMigration.migrate(v6Install)

        assertEquals(SettingsDefaults.LOG_LEVEL, preferences[SettingsKeys.LOG_LEVEL])
        assertEquals(SettingsDefaults.SCHEMA_VERSION, preferences[SettingsKeys.SCHEMA_VERSION])
    }

    /** Any level other than the old default is a deliberate choice and survives. */
    @Test
    fun `upgrade from v6 keeps a user-chosen log level`() = runBlocking {
        val v6Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 6,
            SettingsKeys.LOG_LEVEL to "debug",
        )

        val preferences = SettingsMigration.migrate(v6Install)

        assertEquals("debug", preferences[SettingsKeys.LOG_LEVEL])
    }

    @Test
    fun `upgrade from v5 keeps a user-chosen ping protocol`() = runBlocking {
        val v5Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 5,
            SettingsKeys.PING_PROTOCOL to "HEAD",
        )

        val preferences = SettingsMigration.migrate(v5Install)

        assertEquals("HEAD", preferences[SettingsKeys.PING_PROTOCOL])
    }

    /** A fresh install materialises the CURRENT ping default, with no TCP intermediate. */
    @Test
    fun `fresh settings materialise the URL Test ping default`() = runBlocking {
        val preferences = SettingsMigration.migrate(emptyPreferences())

        assertEquals(SettingsDefaults.PING_PROTOCOL, preferences[SettingsKeys.PING_PROTOCOL])
        assertEquals(SettingsDefaults.TEST_URL, preferences[SettingsKeys.PING_URL])
    }

    /** A v2 user who picked their own resolver keeps it across the v3 step. */
    @Test
    fun `upgrade from v2 keeps a user-chosen bootstrap resolver`() = runBlocking {
        val v2Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 2,
            SettingsKeys.DIRECT_DNS to "9.9.9.9",
            SettingsKeys.IP_STRATEGY to "ipv6_only",
        )

        val preferences = SettingsMigration.migrate(v2Install)

        assertEquals("9.9.9.9", preferences[SettingsKeys.DIRECT_DNS])
        assertEquals("ipv6_only", preferences[SettingsKeys.IP_STRATEGY])
    }

    /** A resolver the user chose themselves is theirs to keep. */
    @Test
    fun `upgrade keeps a user-chosen bootstrap resolver`() = runBlocking {
        val v1Install = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to 1,
            SettingsKeys.DIRECT_DNS to "https://1.1.1.1/dns-query",
            SettingsKeys.IP_STRATEGY to "ipv4_only",
        )

        val preferences = SettingsMigration.migrate(v1Install)

        assertEquals("https://1.1.1.1/dns-query", preferences[SettingsKeys.DIRECT_DNS])
        assertEquals("ipv4_only", preferences[SettingsKeys.IP_STRATEGY])
    }

    /**
     * The v1 step lists the appearance keys for parity, so the step keeps describing the
     * FULL key set and a forgotten one is visible in review. Nothing depends on the
     * values: an install that never replays this step reads exactly the same defaults
     * through the repository's `?:` fallbacks.
     */
    @Test
    fun `fresh settings also materialise the appearance defaults`() = runBlocking {
        val preferences = SettingsMigration.migrate(emptyPreferences())

        assertEquals(SettingsDefaults.THEME_SCHED_FROM, preferences[SettingsKeys.THEME_SCHED_FROM])
        assertEquals(SettingsDefaults.THEME_SCHED_TO, preferences[SettingsKeys.THEME_SCHED_TO])
        assertEquals(SettingsDefaults.SURFACE_TINT, preferences[SettingsKeys.SURFACE_TINT])
        assertEquals(SettingsDefaults.ACCENT_CHROMA, preferences[SettingsKeys.ACCENT_CHROMA])
        assertEquals(SettingsDefaults.QUICK_PEEK, preferences[SettingsKeys.QUICK_PEEK])
        assertEquals(SettingsDefaults.HOME_BLOCKS, preferences[SettingsKeys.HOME_BLOCKS])
        assertEquals(SettingsDefaults.LAT_T1, preferences[SettingsKeys.LAT_T1])
        assertEquals(SettingsDefaults.LAT_T2, preferences[SettingsKeys.LAT_T2])
        assertEquals(SettingsDefaults.LAT_T3, preferences[SettingsKeys.LAT_T3])
        assertEquals(SettingsDefaults.SELECTION_WASH, preferences[SettingsKeys.SELECTION_WASH])
        assertEquals("normal", preferences[SettingsKeys.CORNER_STYLE])
        assertEquals("custom", preferences[SettingsKeys.APPEARANCE_PRESET])
        assertTrue(preferences[SettingsKeys.SHOW_DIVIDERS]!!)
        assertFalse(preferences[SettingsKeys.AMOLED_TINT]!!)
    }

    /**
     * The whole appearance tab landed WITHOUT touching SCHEMA_VERSION, and this is the
     * assertion that keeps it that way. Purely additive keys resolve through `?:`, so a
     * bump would buy nothing and cost a replay of the chain on every install — with an
     * `error(...)` waiting in the `when` if the constant ever moves ahead of its branch.
     * That error is raised INSIDE a DataStore migration, where it is not an IOException,
     * so the repository's `.catch` rethrows it and settings become unreadable for the
     * rest of the process.
     */
    @Test
    fun `a current install is not migrated again and gains no appearance keys`() = runBlocking {
        val current = mutablePreferencesOf(
            SettingsKeys.SCHEMA_VERSION to SettingsDefaults.SCHEMA_VERSION,
            SettingsKeys.THEME_MODE to "amoled",
        )

        assertFalse(SettingsMigration.shouldMigrate(current))
        val preferences = SettingsMigration.migrate(current)

        assertFalse(preferences.contains(SettingsKeys.CORNER_STYLE))
        assertFalse(preferences.contains(SettingsKeys.SURFACE_TINT))
        assertEquals("amoled", preferences[SettingsKeys.THEME_MODE])
    }

    @Test
    fun `running the migration again is idempotent`() = runBlocking {
        val first = SettingsMigration.migrate(emptyPreferences())

        assertFalse(SettingsMigration.shouldMigrate(first))
        assertEquals(first, SettingsMigration.migrate(first))
    }

    @Test
    fun `production DataStore migration preserves legacy custom rule sets for repository`() = runBlocking {
        val file = context.preferencesDataStoreFile("lean_settings_migration_test")
        if (file.exists()) assertTrue(file.delete())
        val seedJob = SupervisorJob()
        val seedStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + seedJob),
            produceFile = { file },
        )
        seedStore.edit { preferences ->
            preferences[SettingsKeys.CUSTOM_RULE_SETS] = setOf(
                " https://example.com/geoip.srs ",
                "https://example.com/geosite.srs",
                "not-a-url",
            )
            preferences[SettingsKeys.DIRECT_DNS] = "https://dns.quad9.net/dns-query"
        }
        seedJob.cancelAndJoin()

        val migrationJob = SupervisorJob()
        val migratedStore = PreferenceDataStoreFactory.create(
            migrations = listOf(SettingsMigration),
            scope = CoroutineScope(Dispatchers.IO + migrationJob),
            produceFile = { file },
        )
        try {
            val migrated = migratedStore.data.first()
            assertEquals(SettingsDefaults.SCHEMA_VERSION, migrated[SettingsKeys.SCHEMA_VERSION])
            assertFalse(migrated.contains(SettingsKeys.CUSTOM_RULE_SETS_STR))

            val settings = SettingsRepository(context, migratedStore).flow.first()
            assertEquals(
                setOf("https://example.com/geoip.srs", "https://example.com/geosite.srs"),
                settings.customRuleSets.toSet(),
            )
            assertEquals("https://dns.quad9.net/dns-query", settings.directDns)
        } finally {
            migrationJob.cancelAndJoin()
            if (file.exists()) assertTrue(file.delete())
        }
    }

    private val context
        get() = RuntimeEnvironment.getApplication()
}
