package com.th3web.lean.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The one failure mode in SettingsRepository that nothing else can see.
 *
 * A [Settings] field forgotten in `setAll` compiles, serializes, survives the backup
 * round trip and passes every hand-written assertion — it just never reaches DataStore.
 * The user restores a backup and that single setting is quietly back to its default.
 * Same for a field forgotten in the read mapping: it writes fine and reads back stale.
 *
 * So this test never names a field. It walks the serial descriptor, which is generated
 * from the class itself, and therefore grows the moment someone adds a knob.
 *
 * The descriptor is used instead of `Settings::class.memberProperties` because
 * kotlin-reflect is not on the classpath (and adding it for one test would ship a
 * megabyte to every user); `elementsCount`/`getElementName` are stable, non-experimental
 * API, and the names they return are the JSON keys — exactly what the comparison needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class SetAllCoversEveryFieldTest {

    /**
     * Every field moved off its default. Its own completeness is asserted below, so a
     * new knob added without a fixture value fails here rather than hiding a missing
     * `setAll` write behind a value that happened to already match.
     */
    private val mutated = Settings(
        selectedProfileId = "profile-setall",
        routingMode = RoutingMode.GLOBAL,
        bypassLan = false,
        ipv6 = true,
        allowInsecure = true,
        remoteDns = "https://setall.example/dns-query",
        directDns = "https://setall.example/direct",
        accentColor = 0xFF98D1A6L,
        themeMode = "system",
        language = "en",
        perAppMode = PerAppMode.EXCLUDE,
        perAppPackages = setOf("ru.example.one"),
        autoConnect = true,
        autoFailover = true,
        logLevel = "debug",
        killSwitch = true,
        dozePause = true,
        batteryWarningHidden = true,
        mux = true,
        fragment = true,
        autoUpdate = true,
        checkAppUpdates = false,
        sendHwid = false,
        pingProtocol = "ICMP",
        pingUrl = "https://setall.example/ping",
        pingTimeoutMs = 7_000,
        ipStrategy = "ipv6_only",
        serverSort = "ping",
        pingOnLaunch = false,
        pingOnUpdate = false,
        bgRefreshMinutes = 360,
        showSpeedInNotification = false,
        userAgent = "SetAll/1.0",
        wgMtu = 1_408,
        appIcon = "outline",
        crashReporting = true,
        tcpFastOpen = true,
        utlsFingerprint = "firefox",
        sniOverride = "vk.com",
        ruDirect = true,
        customRuleSets = listOf("https://setall.example/geoip.srs"),
        serviceMode = "proxy",
        proxyPort = 7_890,
        proxyAllowLan = true,
        tunStack = "system",
        tunMtu = 1_500,
        sniffEnabled = false,
        sniffOverrideDestination = true,
        sniffResolveDestination = true,
        dnsRouting = false,
        fakeDns = false,
        resetConnectionsOnNetworkChange = false,
        activeConnectionTestTimeoutMs = 4_000,
        appearancePreview = false,
        appearancePreset = "Полночь",
        customPresets = listOf(NamedAppearance("Мой образ", AppearanceProfile(themeMode = "light"))),
        accentRecent = listOf(0xFF9FD2CBL, 0xFFDCC18CL),
        themeSchedule = true,
        themeSchedFrom = 1_320,
        themeSchedTo = 480,
        themeSchedMode = "dark",
        contrastLevel = 2,
        amoledDepth = "soft",
        amoledTint = true,
        accentSource = "wallpaper",
        accentChroma = 85,
        surfaceTint = 14,
        connectedMode = "accent",
        errorColor = "amber",
        wordmarkAccent = false,
        roleOverrides = mapOf(AppearanceRoles.BACKGROUND to 0xFF101319L),
        fontDisplay = "system",
        fontBody = "mono",
        textScale = 120,
        fontWeightDelta = 100,
        tabularNums = false,
        sectionCaps = false,
        cornerStyle = "round",
        uiDensity = "compact",
        outlineWeight = "strong",
        showDividers = false,
        dividerIndent = "full",
        cardShadow = "deep",
        heroStyle = "minimal",
        heroSize = 115,
        heroGlyph = "shield",
        heroBreath = false,
        heroFloating = true,
        trafficRow = "compact",
        quickPeek = 6,
        homeBlocks = 5,
        currentServerLabel = "name_proto",
        latencyPalette = "gradient",
        latT1 = 200,
        latT2 = 400,
        latT3 = 900,
        latencyMeter = "bars",
        showTags = false,
        serverTagKinds = "p",
        bgImageDim = 33,
        bgImageBlur = 40,
        bgImageSaturation = 25,
        bgImageZoom = 150,
        bgImageAlign = "top",
        glassPanels = true,
        glassTint = 40,
        serverRow = "detailed",
        selectionCue = "stripe",
        selectionWash = 25,
        motionLevel = "lively",
        respectSystemAnimations = false,
        bannerSheen = false,
        colorCrossfade = "off",
        haptics = "light",
        bgStyle = "grain",
        sysbarInk = "dark",
        splashTheme = false,
        wgDnsThroughTunnel = false,
    )

    @Test
    fun `the fixture moves every field off its default`() {
        val untouched = fieldsWhere(
            Settings.serializer().descriptor,
            json(mutated),
            json(Settings()),
        ) { a, b -> a == b }

        assertEquals(
            "give these fields a non-default value in `mutated`, otherwise a missing " +
                "setAll write or read mapping stays invisible",
            emptyList<String>(),
            untouched,
        )
    }

    @Test
    fun `setAll persists every field and the read mapping returns it`() = runBlocking {
        withRepository("set_all_coverage") { repository ->
            repository.setAll(mutated)

            val readBack = repository.flow.first()
            val lost = fieldsWhere(
                Settings.serializer().descriptor,
                json(mutated),
                json(readBack),
            ) { a, b -> a != b }

            assertEquals(
                "these fields did not survive setAll -> DataStore -> read mapping; the " +
                    "usual cause is a missing line in setAll, the next is a missing line " +
                    "in the .map { } read block",
                emptyList<String>(),
                lost,
            )
        }
    }

    /**
     * applyAppearance is the second writer, and it has the same blind spot: a knob it
     * forgets keeps its previous value while every other knob moves, so a preset applies
     * "almost". Anchored on the preset that moves the most fields.
     */
    @Test
    fun `applyAppearance writes the whole look in one transaction`() = runBlocking {
        withRepository("apply_appearance") { repository ->
            val look = AppearancePresets.Terminal

            repository.applyAppearance(look.profile, preset = look.name)

            val settings = repository.flow.first()
            val lost = fieldsWhere(
                AppearanceProfile.serializer().descriptor,
                json(look.profile),
                json(settings.toAppearanceProfile()),
            ) { a, b -> a != b }

            assertEquals("applyAppearance skipped these", emptyList<String>(), lost)
            assertEquals(look.name, settings.appearancePreset)
        }
    }

    /**
     * Nothing materialises the new keys for an existing install (SCHEMA_VERSION was
     * deliberately not bumped), so the `?:` fallback in the read mapping IS the runtime
     * default and has to agree with the data-class default it is documented against.
     */
    @Test
    fun `an install with no appearance keys resolves to the shipping look`() = runBlocking {
        withRepository("appearance_absent") { repository ->
            val settings = repository.flow.first()

            assertEquals(AppearanceProfile.Default, settings.toAppearanceProfile())
            assertEquals(AppearancePresets.Steel.profile, settings.toAppearanceProfile())
            assertTrue(settings.appearancePreview)
            assertEquals("custom", settings.appearancePreset)
            assertEquals(emptyList<NamedAppearance>(), settings.customPresets)
            assertEquals(emptyList<Long>(), settings.accentRecent)
        }
    }

    /**
     * "system" is a new VALUE of the existing theme_mode key. The old mapping folded
     * anything unrecognised into "dark", so this is the assertion that would have caught
     * the branch never being added.
     */
    @Test
    fun `theme mode accepts system and still rejects foreign values`() = runBlocking {
        withRepository("theme_mode_system") { repository ->
            repository.setThemeMode("system")
            assertEquals("system", repository.flow.first().themeMode)

            repository.setThemeMode("neon")
            assertEquals("dark", repository.flow.first().themeMode)
        }
    }

    /** The core reads this verbatim; an unrecognised level fails the whole tunnel start. */
    @Test
    fun `log level normalizes a foreign value away from the core config`() = runBlocking {
        withRepository("log_level") { repository ->
            repository.setLogLevel("debug")
            assertEquals("debug", repository.flow.first().logLevel)

            repository.setLogLevel("verbose")
            assertEquals(SettingsDefaults.LOG_LEVEL, repository.flow.first().logLevel)

            // "info" is a real level, not the fallback it used to double as: it must
            // survive being chosen, or the one setting that turns per-connection
            // narration back on would be impossible to select.
            repository.setLogLevel("info")
            assertEquals("info", repository.flow.first().logLevel)
        }
    }

    private fun json(value: Settings): JsonObject =
        Serialization.json.encodeToJsonElement(value).jsonObject

    private fun json(value: AppearanceProfile): JsonObject =
        Serialization.json.encodeToJsonElement(value).jsonObject

    private fun fieldsWhere(
        descriptor: SerialDescriptor,
        a: JsonObject,
        b: JsonObject,
        predicate: (JsonElement?, JsonElement?) -> Boolean,
    ): List<String> = (0 until descriptor.elementsCount)
        .map(descriptor::getElementName)
        .filter { predicate(a[it], b[it]) }

    private suspend fun withRepository(
        suffix: String,
        block: suspend (SettingsRepository) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val file = context.preferencesDataStoreFile("set_all_coverage_$suffix")
        if (file.exists()) assertTrue(file.delete())
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        try {
            block(SettingsRepository(context, store))
        } finally {
            job.cancelAndJoin()
            if (file.exists()) assertTrue(file.delete())
        }
    }
}
