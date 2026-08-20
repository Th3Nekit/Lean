package com.th3web.lean.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import com.th3web.lean.LeanApp
import com.th3web.lean.MainActivity
import com.th3web.lean.R
import com.th3web.lean.awg.JniAmneziaWgNative
import com.th3web.lean.core.awg.AwgConfigAdapter
import com.th3web.lean.core.awg.AwgEngine
import com.th3web.lean.core.awg.AwgNativeLog
import com.th3web.lean.core.awg.AwgPublicationSnapshot
import com.th3web.lean.core.awg.AwgSessionObserver
import com.th3web.lean.core.awg.AwgTunnelSession
import com.th3web.lean.core.awg.EngineSelector
import com.th3web.lean.core.awg.GenerationGuardedAwgPublisher
import com.th3web.lean.core.awg.SelectedNetworkEndpointResolver
import com.th3web.lean.core.connection.ConnectionCommand
import com.th3web.lean.core.connection.ConnectionCoordinator
import com.th3web.lean.core.connection.DesiredConnection
import com.th3web.lean.core.connection.ServiceStatePublisher
import com.th3web.lean.core.connection.ServiceStateTarget
import com.th3web.lean.core.engine.ActiveNativeService
import com.th3web.lean.core.engine.EngineConfig
import com.th3web.lean.core.engine.LibcoreNekoCore
import com.th3web.lean.core.engine.NativeServiceBridge
import com.th3web.lean.core.engine.NekoEngine
import com.th3web.lean.core.engine.NekoNativeRuntime
import com.th3web.lean.core.engine.NekoSessionObserver
import com.th3web.lean.core.engine.NekoTunnelSession
import com.th3web.lean.core.engine.TrafficAccumulator
import com.th3web.lean.core.network.DefaultNetworkTransition
import com.th3web.lean.core.plugin.PluginBinding
import com.th3web.lean.core.plugin.PluginFailure
import com.th3web.lean.core.plugin.PluginSession
import com.th3web.lean.core.tun.AwgTunSpec
import com.th3web.lean.core.tun.TunRuntimePolicy
import com.th3web.lean.core.tun.VpnTunController
import com.th3web.lean.data.Settings
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.resolveProfileSelection
import com.th3web.lean.ui.formatBytes
import com.th3web.lean.ui.tr

internal fun isExplicitVpnStartAction(action: String?): Boolean =
    action == LeanVpnService.ACTION_START

/**
 * Did this start come from the app, or from Android?
 *
 * Every start the app issues names one of its own actions (see [CoreManager.connect] /
 * [CoreManager.disconnect] and the notification's buttons). The service is
 * `exported="false"` behind BIND_VPN_SERVICE, so a start carrying anything else, most of
 * all a NULL intent, can only have come from the system, and it means one of two things:
 *
 *  - always-on VPN is on and Android is bringing the tunnel up, possibly at boot;
 *  - Android killed a running service and is re-creating it (START_STICKY).
 *
 * Both say "the tunnel is supposed to be up". This is the detection the platform docs
 * themselves recommend, since there is no API to ask: flag the starts you make, and treat
 * unflagged ones as the system's.
 */
internal fun isSystemVpnStart(action: String?): Boolean = action !in LEAN_VPN_ACTIONS

private val LEAN_VPN_ACTIONS = setOf(
    LeanVpnService.ACTION_START,
    LeanVpnService.ACTION_STOP,
    LeanVpnService.ACTION_PAUSE,
)

class LeanVpnService : VpnService(), NativeServiceBridge {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val networkMonitor by lazy { DefaultNetworkMonitor(this) }
    private val tunController by lazy { VpnTunController(this) }

    @Volatile private var activeGeneration = 0L
    @Volatile private var activeSettings: Settings? = null

    /** «Спать в глубоком сне», read live so the switch takes effect at once. */
    @Volatile private var dozePauseEnabled: Boolean = false

    /** Opt-in Doze pause of the core. See [DozePause] for what it costs. */
    private val dozePause = DozePause(box = { activeBox }, enabled = { dozePauseEnabled })

    private var idleReceiver: BroadcastReceiver? = null
    @Volatile private var activeUsesNeko = false
    @Volatile private var statusClient: CoreStatusClient? = null

    /**
     * The running core, kept only so a network change can reach [NekoBox.resetNetwork].
     * Volatile because the ConnectivityManager callback runs off the coordinator's thread.
     */
    @Volatile private var activeBox: com.th3web.lean.core.engine.NekoBox? = null

    /**
     * Survives the process, which is the requirement here: the crash-loop guard has to remember what
     * happened in the incarnation that died. Preferences, not DataStore: this is read on
     * the way into a foreground service that has seconds to show its notification.
     */
    private val servicePrefs by lazy {
        getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
    }
    @Volatile private var serviceDestroyed = false
    @Volatile private var connectedName: String? = null

    /** Profile the live session was built for, what «Автопереключение» retries. */
    @Volatile private var activeProfileId: String? = null
    @Volatile private var connectedAtMillis = 0L
    @Volatile private var showSpeed = true
    @Volatile private var notifJob: Job? = null

    private val statePublisher by lazy {
        ServiceStatePublisher(
            object : ServiceStateTarget {
                override fun setState(state: VpnState) {
                    if (state is VpnState.Stopping) stopNotificationUpdates()
                    CoreManager.setState(state)
                }

                override fun clearTraffic() = CoreManager.setTraffic(TrafficStats())
                override fun clearGroups() = CoreManager.setGroups(emptyList())

                override fun showConnectingNotification() {
                    stopNotificationUpdates()
                    startForeground(NOTIFICATION_ID, notification(getString(R.string.notif_connecting)))
                }

                override fun showConnectedNotification(profileId: String) {
                    // Reaching this means the tunnel came up, so whatever the system-start
                    // guard was counting was not a crash loop.
                    servicePrefs.edit().putInt(KEY_SYSTEM_START_STREAK, 0).apply()
                    startNotificationUpdates()
                }

                override fun removeForeground() {
                    stopNotificationUpdates()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            },
        )
    }

    /** One instance: the engine drives it, and [pollAwgTraffic] reads its UAPI dump. */
    private val awgNative = JniAmneziaWgNative()

    /**
     * The external protocol helpers (naive, mieru) for the current session.
     *
     * Ports are allocated in [buildEngineConfig] because the core's config has to name
     * them; the processes themselves start in [NekoTunnelSession.begin] and stop in its
     * close, so their lifetime is exactly the tunnel's.
     */
    private val pluginSession by lazy { PluginSession(this) }

    /** Port allocations for the config currently being built, keyed by profile id. */
    private var pluginBindings: List<PluginBinding> = emptyList()

    private val tunnelSession = object : NekoTunnelSession {
        override fun begin(generation: Long, config: EngineConfig) {
            check(!serviceDestroyed) { "VPN service destroyed" }
            check(activeGeneration == 0L) { "Previous VPN generation is still active" }
            val policy = requireNotNull(config.tunnelPolicy)
            try {
                tunController.begin(generation, policy)
                activeGeneration = generation
                activeUsesNeko = true
                ActiveNativeService.install(this@LeanVpnService)
                tunController.setUnderlyingNetwork(generation, networkMonitor.currentNetwork())
                // Helpers come up before the core: the core's socks outbound points at a
                // port the helper owns, and a core that starts first would simply find it
                // closed. They are torn down in close(), which this same generation guard
                // makes exactly-once.
                startPluginHelpers(config)
            } catch (error: Throwable) {
                runCatching { pluginSession.stopAll() }
                ActiveNativeService.uninstall(this@LeanVpnService)
                runCatching { tunController.closeGeneration(generation) }
                activeGeneration = 0L
                activeUsesNeko = false
                throw error
            }
        }

        override fun close(generation: Long) {
            if (activeGeneration != generation) return
            // Before the tun goes: a helper left running would hold its port and make the
            // next connect fail to bind: a protocol that works once and never again.
            runCatching { pluginSession.stopAll() }
            ActiveNativeService.uninstall(this@LeanVpnService)
            activeGeneration = 0L
            activeUsesNeko = false
            tunController.closeGeneration(generation)
            activeSettings = null
            connectedName = null
            CoreManager.setProtectHook(null)
        }
    }

    private val awgTunnelSession = object : AwgTunnelSession {
        override fun prepareNetwork(
            generation: Long,
            policy: TunRuntimePolicy,
            networkToken: Any,
        ): Boolean {
            check(!serviceDestroyed) { "VPN service destroyed" }
            val network = networkToken as? Network
                ?: throw IllegalArgumentException("Некорректная физическая сеть AmneziaWG")
            try {
                return networkMonitor.withCurrentNetwork(network) {
                    check(activeGeneration == 0L) {
                        "Previous VPN generation is still active"
                    }
                    tunController.begin(generation, policy)
                    activeGeneration = generation
                    activeUsesNeko = false
                    tunController.setUnderlyingNetwork(generation, network)
                }
            } catch (error: Throwable) {
                if (activeGeneration == generation) {
                    runCatching { tunController.closeGeneration(generation) }
                    activeGeneration = 0L
                    activeUsesNeko = false
                }
                throw error
            }
        }

        override fun establishAndDetach(generation: Long, spec: AwgTunSpec): Int {
            check(activeGeneration == generation) { "AmneziaWG generation is stale" }
            return tunController.openAwgTun(generation, spec)
        }

        override fun closeDetachedFd(generation: Long, fd: Int) {
            tunController.closeDetachedFd(generation, fd)
        }

        override fun close(generation: Long) {
            if (activeGeneration != generation) return
            activeGeneration = 0L
            activeUsesNeko = false
            tunController.closeGeneration(generation)
            activeSettings = null
            connectedName = null
            CoreManager.setProtectHook(null)
        }
    }

    private val coordinator: ConnectionCoordinator by lazy {
        val neko = NekoEngine(
            profileProvider = ::resolveProfiles,
            configProvider = ::buildEngineConfig,
            core = LibcoreNekoCore(this),
            generationIsCurrent = ::isGenerationCurrent,
            tunnel = tunnelSession,
            onStarted = ::onEngineStarted,
            discardCoreCache = ::discardCoreCache,
        )
        val awg = AwgEngine(
            policyProvider = ::loadTunPolicy,
            adapter = AwgConfigAdapter(),
            endpointResolver = SelectedNetworkEndpointResolver<Network>(
                networkProvider = ::awaitPhysicalNetwork,
                lookup = { network, host -> network.getAllByName(host).toList() },
                timeoutMs = AWG_NETWORK_TIMEOUT_MS,
            ),
            native = awgNative,
            generationIsCurrent = ::isGenerationCurrent,
            tunnel = awgTunnelSession,
            protectSocket = { fd -> protect(fd) },
            onStarted = ::onAwgStarted,
            onProgress = CoreManager::appendLog,
            nativeLog = AwgNativeLog()::tail,
        )
        ConnectionCoordinator(
            scope,
            EngineSelector(::resolveProfiles, neko, awg),
            statePublisher,
            ::logConnectionFailure,
        )
    }

    private val awgStartPublisher by lazy {
        val settingsRepository = LeanApp.instance.settings
        GenerationGuardedAwgPublisher(
            generationIsCurrent = coordinator::isCurrentGeneration,
            loadSnapshot = {
                val settings = settingsRepository.flow.first()
                AwgPublicationSnapshot(settings, settings.selectedProfileId)
            },
            writeSelectedProfileOwned = settingsRepository::writeSelectedProfileOwned,
            restoreSelectedProfileIfOwned = settingsRepository::restoreSelectedProfileIfOwned,
            commitIfCurrent = coordinator::commitIfCurrentGeneration,
        )
    }

    private fun isGenerationCurrent(generation: Long): Boolean =
        coordinator.isCurrentGeneration(generation)

    // --- «Автопереключение (Beta)» ---------------------------------------------
    // Which profile this round is fighting for, how many times it has failed, and who
    // has already been tried, reset whenever the user connects by hand, so a fresh
    // choice always gets the full retry budget.
    private var failoverProfileId: String? = null
    private var failoverAttempts: Int = 0
    private val failoverTried = mutableSetOf<String>()
    private var failoverJob: Job? = null

    /** Forget the round. Called on a user-driven connect and on any clean stop. */
    private fun resetFailover() {
        failoverJob?.cancel()
        failoverJob = null
        failoverProfileId = null
        failoverAttempts = 0
        failoverTried.clear()
    }

    /**
     * Reacts to a connection that failed, when the user has opted in.
     *
     * Driven off the failure path rather than off a traffic watchdog: a
     * tunnel that is up but carrying nothing is a much harder thing to judge (idle looks
     * identical to broken), and acting on a wrong judgement would disconnect a working
     * VPN. A failure is unambiguous.
     */
    private fun onConnectionFailed() {
        if (activeSettings?.autoFailover != true) return
        val current = activeProfileId ?: return
        if (current != failoverProfileId) {
            // A different profile than the one we were retrying: start a fresh round.
            failoverProfileId = current
            failoverAttempts = 0
            failoverTried.clear()
        }
        failoverAttempts++
        failoverTried += current
        val profiles = LeanApp.instance.profiles.state.value.profiles
        val move = FailoverPolicy.next(current, failoverAttempts, failoverTried, profiles)
        val wait = FailoverPolicy.delayMs(failoverAttempts)
        failoverJob?.cancel()
        failoverJob = scope.launch {
            delay(wait)
            if (serviceDestroyed || activeSettings?.autoFailover != true) return@launch
            when (move) {
                is FailoverPolicy.Move.Retry -> {
                    CoreManager.appendLog(tr("↻ автопереключение: повтор подключения"))
                    // restart = true: reconnecting to the profile you are already on is
                    // a deliberate no-op in the coordinator, which would make every
                    // retry silently do nothing.
                    coordinator.submit(
                        DesiredConnection.Running(profileId = move.profileId, restart = true),
                    )
                }
                is FailoverPolicy.Move.Switch -> {
                    val name = profiles.firstOrNull { it.id == move.to }?.name.orEmpty()
                    CoreManager.appendLog(tr("↻ автопереключение: переход на «%s»").format(name))
                    coordinator.submit(DesiredConnection.Running(profileId = move.to))
                }
                FailoverPolicy.Move.Stop -> {
                    CoreManager.appendLog(tr("✖ автопереключение: рабочих серверов не осталось"))
                    resetFailover()
                }
            }
        }
    }

    /**
     * Deletes the core's on-disk cache so the next start builds a fresh one.
     *
     * Called only after a start failure that carries bbolt's corruption wording (see
     * [com.th3web.lean.core.engine.NekoEngine]). The path must stay in step with
     * `experimental.cache_file.path` in the emitted config, which is relative to the
     * core's working directory, [LeanNativePlatform] sets that to filesDir.
     *
     * Returns whether anything was actually removed: a false stops the engine from
     * spending a second start attempt on an unchanged situation.
     */
    private fun discardCoreCache(): Boolean {
        val removed = CORE_CACHE_FILES
            .map { java.io.File(filesDir, it) }
            .filter { it.exists() }
            .count { it.delete() }
        if (removed > 0) {
            Log.w(TAG, "Core cache looked corrupt; removed $removed file(s), retrying start")
            CoreManager.appendLog(tr("⚠ Кэш ядра был повреждён — очищен, повтор запуска"))
        }
        return removed > 0
    }

    private fun logConnectionFailure(error: Throwable) {
        CoreManager.appendLog(tr("✖ Ошибка подключения"))
        onConnectionFailed()
        NativeLogTail(java.io.File(cacheDir, "neko.log"))
            .readLastLines(MAX_CONNECTION_ERROR_LOG_LINES)
            .forEach(CoreManager::appendLog)
        error.stackTraceToString()
            .lineSequence()
            .take(MAX_CONNECTION_ERROR_LOG_LINES)
            .map(CoreStatusLogSanitizer::sanitize)
            .forEach(CoreManager::appendLog)
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        trimCoreLog()
        networkMonitor.start { network, transition ->
            val generation = activeGeneration
            if (generation != 0L) handleDefaultNetwork(generation, network, transition)
        }
        startIdleWatch()
        scope.launch {
            LeanApp.instance.settings.flow
                .map { it.dozePause }
                .distinctUntilChanged()
                .collect { enabled ->
                    dozePauseEnabled = enabled
                    // Turning it OFF has to wake a core that is paused right now, or the
                    // switch would look dead until the device next left Doze.
                    dozePause.settingChanged()
                }
        }
    }

    /**
     * Watches Doze, so [dozePause] can stop the core's periodic work while the system says
     * the device is genuinely unattended.
     *
     * ACTION_DEVICE_IDLE_MODE_CHANGED cannot be declared in the manifest, the system only
     * delivers it to receivers registered at runtime.
     *
     * Doze, and never the screen. A dark screen is also music, a download, a call or a
     * hotspot, so keying this off SCREEN_OFF leaves a phone in a pocket with no tunnel.
     */
    private fun startIdleWatch() {
        if (idleReceiver != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) return
                dozePause.idleChanged(deviceIsIdle())
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(listener, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(listener, filter)
            }
        }.onSuccess { idleReceiver = listener }
    }

    /** True when the system currently has the device in Doze. */
    private fun deviceIsIdle(): Boolean = runCatching {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true
    }.getOrDefault(false)

    /**
     * Caps the core's log file, which nothing else ever shortens.
     *
     * It is libcore's redirected stderr, so it only grows, a device running the app for
     * weeks had it at 18.8 MB of cache, while only its tail is ever read, for a
     * diagnostics report.
     *
     * Guarded on the core not being up in this process, and done here for that reason:
     * Once libcore is initialised it holds an open descriptor on the file, and truncating
     * under a writer is only safe if that descriptor is in append mode, which is not ours
     * to assume. A service created into a process that already ran a core simply skips a
     * round; the next cold start catches it.
     */
    private fun trimCoreLog() {
        if (NekoNativeRuntime.isInitialized) return
        runCatching {
            val freed = NativeLogTail(java.io.File(cacheDir, "neko.log"))
                .trimTo(CORE_LOG_MAX_BYTES, CORE_LOG_KEEP_BYTES)
            if (freed > 0) Log.i(TAG, "Trimmed neko.log, reclaimed $freed bytes")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (serviceDestroyed) return START_NOT_STICKY
        val action = intent?.action
        if (isSystemVpnStart(action)) return startFromSystem()
        val desired = when {
            isExplicitVpnStartAction(action) -> {
                CoreManager.appendLog(tr("… Запуск подключения"))
                // A hand-driven connect starts a new failover round: the user has just
                // stated a preference, so it gets the full retry budget rather than
                // inheriting the exhausted one from whatever failed before.
                resetFailover()
                DesiredConnection.Running(
                    profileId = resolveProfileSelection(
                        savedId = intent?.getStringExtra(EXTRA_PROFILE_ID)
                            ?: LeanApp.instance.settings.state.value.selectedProfileId,
                        profiles = LeanApp.instance.profiles.state.value.profiles,
                    )
                        ?: "",
                    restart = intent?.getBooleanExtra(EXTRA_RESTART, false) == true,
                )
            }
            action == ACTION_PAUSE -> {
                CoreManager.appendLog(tr("⏸ приостановлено"))
                // Stopping is a decision, not a failure: cancel any pending retry so the
                // tunnel does not come back a few seconds after the user paused it.
                resetFailover()
                DesiredConnection.Stopped
            }
            else -> {
                if (action == ACTION_STOP) CoreManager.appendLog(tr("■ Остановка подключения"))
                // Same reason as the pause branch: an explicit stop must not be undone
                // by a retry that was already in flight.
                resetFailover()
                DesiredConnection.Stopped
            }
        }
        coordinator.submit(desired)
        // STICKY only while a tunnel is wanted. A stop already calls stopSelf(), which
        // Android never resurrects, so this asks to be re-created after a kill and
        // never after a deliberate stop.
        return if (desired is DesiredConnection.Running) START_STICKY else START_NOT_STICKY
    }

    /**
     * Brings the tunnel back up after Android started the service itself.
     *
     * Two cases arrive here: always-on VPN bringing the tunnel up, and the re-creation of
     * a service the system killed. Answering either with a stop is what "VPN just
     * silently turns off sometimes" looks like from the outside.
     *
     * The work is asynchronous because this can be a cold process, at boot, always-on
     * starts the service before anything has read the settings, so the selected profile
     * is awaited rather than read. [startForeground] therefore happens first and
     * synchronously: a foreground service that has not shown its notification within a few
     * seconds is killed with ForegroundServiceDidNotStartInTimeException, and waiting on
     * DataStore is the kind of delay that gets there.
     */
    private fun startFromSystem(): Int {
        // A tunnel that crashes the process turns START_STICKY into a loop: the system
        // re-creates the service, this reconnects, it dies again, seconds apart. So
        // repeated system starts that never reached «подключено» give up and let the user
        // decide rather than restarting forever.
        val now = System.currentTimeMillis()
        val previous = servicePrefs.getLong(KEY_LAST_SYSTEM_START, 0L)
        val streak = if (now - previous <= SYSTEM_START_WINDOW_MS) {
            servicePrefs.getInt(KEY_SYSTEM_START_STREAK, 0) + 1
        } else {
            1
        }
        servicePrefs.edit()
            .putLong(KEY_LAST_SYSTEM_START, now)
            .putInt(KEY_SYSTEM_START_STREAK, streak)
            .apply()
        if (streak > MAX_SYSTEM_START_STREAK) {
            CoreManager.appendLog(
                tr("✖ подключение падало %d раза подряд — автозапуск остановлен")
                    .format(streak - 1),
            )
            stopSelf()
            return START_NOT_STICKY
        }
        CoreManager.appendLog(tr("… Запуск подключения системой (always-on / восстановление)"))
        runCatching {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.notif_connecting)))
        }.onFailure {
            // Nothing else can run without this, and lingering would leave a headless
            // service the user cannot see or stop.
            CoreManager.appendLog(tr("✖ система не дала показать уведомление: %s").format(it.message.orEmpty()))
            stopSelf()
            return START_NOT_STICKY
        }
        resetFailover()
        scope.launch {
            // The profile store loads synchronously in its constructor; settings do not.
            val settings = LeanApp.instance.settings.flow.first()
            val profiles = LeanApp.instance.profiles.state.value.profiles
            val profileId = resolveProfileSelection(settings.selectedProfileId, profiles)
            if (profileId.isNullOrEmpty()) {
                // Through the coordinator, not straight to stopSelf(): that is the path
                // that also drops the notification this method just put up.
                CoreManager.appendLog(tr("✖ нечего подключать: список серверов пуст"))
                coordinator.submit(DesiredConnection.Stopped)
                return@launch
            }
            coordinator.submit(DesiredConnection.Running(profileId = profileId))
        }
        return START_STICKY
    }

    override fun onRevoke() {
        CoreManager.appendLog(tr("⚠ VPN отозван системой"))
        if (!serviceDestroyed) coordinator.submit(DesiredConnection.Stopped)
    }

    override fun onDestroy() {
        serviceDestroyed = true
        statePublisher.onDestroyed()
        // onDestroy runs on the main thread, and the coordinator we are draining here may
        // itself be parked inside a native call. Draining it without a deadline turned a
        // hung connect into an ANR and a service that could never finish dying (the user
        // saw an endless "Отключение"). Give the graceful drain a bounded window, then
        // fall through to the unconditional resource release below regardless.
        runBlocking {
            withTimeoutOrNull(DESTROY_DRAIN_TIMEOUT_MS) {
                runCatching { coordinator.shutdown() }
            }
            withTimeoutOrNull(DESTROY_DRAIN_TIMEOUT_MS) {
                runCatching { statusClient?.close() }
            }
        }
        statusClient = null
        ActiveNativeService.uninstall(this)
        // Unconditional, like the releases around it: the graceful drain above can time
        // out, and a helper is a real OS process that outlives the service if nobody
        // kills it, still holding its port, so the next connect cannot bind. Idempotent
        // with the stop in tunnelSession.close().
        runCatching { pluginSession.stopAll() }
        idleReceiver?.let { receiver ->
            idleReceiver = null
            runCatching { unregisterReceiver(receiver) }
        }
        runCatching { networkMonitor.close() }
        runCatching { tunController.close() }
        scope.cancel()
        super.onDestroy()
    }

    override fun openTun(tunJson: String, platformOptionsJson: String): Long {
        val generation = activeGeneration
        check(generation != 0L && coordinator.isCurrentGeneration(generation)) {
            "Native TUN request belongs to a stale generation"
        }
        return tunController.openTun(generation, tunJson, platformOptionsJson)
    }

    /**
     * Keeps one socket off the tunnel. Called by the native core for every socket it
     * opens, on a Go-owned thread, which is why it must never throw: an exception at that
     * JNI boundary ends as a process death with no Java stack.
     *
     * A failed protect is not fatal. `VpnService.protect` returns false once the interface
     * is no longer established, which is the state a profile switch passes through
     * while the old session's core is still opening sockets. It means that one socket
     * rides the tunnel instead of bypassing it, and a socket opened by a core that is
     * going away has no traffic to leak.
     */
    override fun protectSocket(fd: Int) {
        val ok = runCatching { protect(fd) }.getOrDefault(false)
        if (!ok) Log.d(TAG, "protect($fd) failed — socket stays on the tunnel")
    }

    override fun currentNetwork(): Network? = networkMonitor.currentNetwork()

    override fun selectorChanged(tag: String, selected: String) {
        statusClient?.selectorChanged(tag, selected)
    }

    private suspend fun resolveProfiles(profileId: String): List<Profile> {
        val all = LeanApp.instance.profiles.state.value.profiles
        if (profileId == CoreManager.AUTO_PROFILE_ID) {
            return all.filterNot { (it.outbound as? Outbound.WireGuard)?.awg != null }
                .ifEmpty { error(getString(R.string.error_no_profile)) }
        }
        return listOf(
            LeanApp.instance.profiles.findProfile(profileId)
                ?: error(getString(R.string.error_no_profile)),
        )
    }

    private suspend fun resolveProfiles(command: ConnectionCommand): List<Profile> {
        val desired = command.desired as? DesiredConnection.Running
            ?: throw IllegalArgumentException("Нельзя выбрать профиль для команды остановки")
        return resolveProfiles(desired.profileId)
    }

    private suspend fun loadTunPolicy(): TunRuntimePolicy {
        val settings = LeanApp.instance.settings.flow.first()
        return settings.toTunRuntimePolicy()
    }

    private fun Settings.toTunRuntimePolicy() = TunRuntimePolicy(
        ipv6Enabled = ipv6,
        bypassPrivateNetworks = bypassLan,
        killSwitch = killSwitch,
        perAppMode = perAppMode,
        perAppPackages = perAppPackages,
        wgMtu = wgMtu,
    )

    private suspend fun buildEngineConfig(profileId: String): EngineConfig {
        val app = LeanApp.instance
        val settings = app.settings.flow.first()
        val profiles = resolveProfiles(profileId)
        require(profiles.none { (it.outbound as? Outbound.WireGuard)?.awg != null })
        val connectedId = if (profileId == CoreManager.AUTO_PROFILE_ID) {
            CoreManager.AUTO_PROFILE_ID
        } else {
            profiles.single().id
        }
        // Allocate a socks + mapping port for every profile that needs a helper. Done
        // here because the ports have to be written into the core's config; the processes
        // themselves are started by the tunnel session, so a config that is built but
        // never used leaves nothing running.
        pluginBindings = pluginSession.plan(profiles.map { it.id to it.outbound })
        val config = SingBoxConfig.buildJson(
            profiles,
            settings,
            plugins = pluginBindings.associate {
                it.profileId to SingBoxConfig.PluginPorts(it.localPort, it.mappingPort)
            },
        )
        activeSettings = settings
        activeProfileId = connectedId
        showSpeed = settings.showSpeedInNotification
        connectedName = if (connectedId == CoreManager.AUTO_PROFILE_ID) {
            // Goes straight into the notification title, so it has to be translated here:
            // unlike a server's own name, this one is the app's word for a mode, and the
            // string is in the EN table; this is the one place that has to look it up.
            tr("Авто · быстрейший")
        } else {
            profiles.single().name
        }
        return EngineConfig(
            profileId = connectedId,
            profiles = profiles,
            json = config,
            tunnelPolicy = settings.toTunRuntimePolicy(),
        )
    }

    /**
     * Brings up the helper process for every plugin-backed profile in [config].
     *
     * All of them are spawned first and waited for together: they are independent
     * processes, and waiting one at a time charges the readiness budget per helper.
     *
     * A helper that never comes up disables its node, not the tunnel. For a single pinned
     * server the helper is the protocol, so that fails loudly; inside a large subscription
     * one broken helper must not take the other nodes down with it. The core never picks
     * a node whose probe fails, so leaving it in place costs nothing, and the connect is
     * abandoned only when nothing is left to carry traffic.
     */
    private fun startPluginHelpers(config: EngineConfig) {
        if (pluginBindings.isEmpty()) return
        val byId = config.profiles.associateBy { it.id }
        val failures = mutableListOf<PluginFailure>()
        val spawned = pluginBindings.filter { binding ->
            val outbound = byId[binding.profileId]?.outbound ?: return@filter false
            val reason = pluginSession.spawn(binding, outbound)
            if (reason != null) failures += PluginFailure(binding.profileId, reason)
            reason == null
        }
        failures += pluginSession.awaitAllReady(spawned)
        if (failures.isEmpty()) return

        val deadIds = failures.map { it.profileId }.toSet()
        val survivors = config.profiles.count { it.id !in deadIds }
        failures.forEach { CoreManager.appendLog("✖ ${it.reason}") }
        if (survivors > 0) {
            CoreManager.appendLog(
                tr("⚠ серверов отключено: %d, продолжаем на остальных (%d)")
                    .format(failures.size, survivors),
            )
            return
        }
        error(
            failures.joinToString("; ") { it.reason } +
                ". " + tr("Подключение отменено, чтобы туннель не поднялся пустым."),
        )
    }

    private fun onEngineStarted(
        generation: Long,
        box: com.th3web.lean.core.engine.NekoBox,
        config: EngineConfig,
    ): NekoSessionObserver {
        check(coordinator.isCurrentGeneration(generation))
        val status = CoreStatusClient(
            parentScope = scope,
            generation = generation,
            isCurrent = coordinator::isCurrentGeneration,
            box = box,
            profiles = config.profiles,
            cacheDir = cacheDir,
        )
        statusClient = status
        activeBox = box
        // The device can already be dozing when a tunnel comes up, an always-on VPN
        // restarting the service, a scheduled reconnect, and no broadcast is coming then.
        dozePause.coreStarted(deviceIdle = deviceIsIdle())
        status.start(config.profileId)
        connectedAtMillis = System.currentTimeMillis()
        CoreManager.setProtectHook(::protectRawFd)
        scope.launch { LeanApp.instance.settings.setSelectedProfile(config.profileId) }
        val description = if (config.profileId == CoreManager.AUTO_PROFILE_ID) {
            tr("▶ Авто · быстрейший (%d серверов)").format(config.profiles.size)
        } else {
            val profile = config.profiles.single()
            "▶ ${profile.name} (${profile.outbound.protocol})"
        }
        CoreManager.appendLog(description)
        return NekoSessionObserver {
            if (statusClient === status) statusClient = null
            if (activeBox === box) {
                activeBox = null
                dozePause.coreStopped()
            }
            status.close()
        }
    }

    private suspend fun onAwgStarted(
        generation: Long,
        profile: Profile,
        handle: Int,
    ): AwgSessionObserver =
        awgStartPublisher.publish(generation, profile.id) { settings ->
            activeSettings = settings
            // Real counters exist for AmneziaWG now (see [pollAwgTraffic]), so the speed
            // line in the notification is no longer forced off for it.
            showSpeed = settings.showSpeedInNotification
            connectedName = profile.name
            connectedAtMillis = System.currentTimeMillis()
            // Same hook the sing-box path installs. Without it every per-server probe
            // runs inside the AmneziaWG tunnel, so each row measures "me → WG exit →
            // server", poisoning the sort order and the «Авто» prediction with it.
            CoreManager.setProtectHook(::protectRawFd)
            CoreManager.appendLog("▶ ${profile.name} (AmneziaWG)")
            val traffic = scope.launch { pollAwgTraffic(generation, handle) }
            AwgSessionObserver { traffic.cancel() }
        }

    /**
     * Publishes ↓/↑ for an AmneziaWG session.
     *
     * [CoreStatusClient] only exists on the sing-box path, so this is the sole source of
     * traffic figures for an AmneziaWG tunnel.
     *
     * The numbers come from the device's UAPI dump, which lists `rx_bytes` / `tx_bytes`
     * per peer. Unlike the v2ray stats the other path reads, these are running totals, so
     * deltas are taken here before handing them to the accumulator, which expects
     * increments. A total that goes backwards, a re-created device reusing the handle,
     * starts a fresh baseline rather than a negative delta.
     */
    private suspend fun pollAwgTraffic(generation: Long, handle: Int) {
        val accumulator = TrafficAccumulator(System.nanoTime())
        var lastRx = -1L
        var lastTx = -1L
        // One verdict, once, a few seconds in. A handshake proves the peer is there; it
        // does not prove the tunnel carries anything, and the difference between those two
        // is the whole of "подключено, но не работает". Reading it off the device's own
        // counters costs nothing: the dump is already being fetched every second, and it
        // turns a silent failure into a statement about which direction is dead.
        val verdictAt = System.nanoTime() + AWG_VERDICT_DELAY_MS * 1_000_000L
        var verdictGiven = false
        // when the receive side stops matters as much as that it stopped. Dying after a
        // few kilobytes points at packet size, everything small got through, the first
        // full-size frame did not. Dying at around two minutes points at the rekey, which
        // is a different fault entirely. Both look the same to a user, so the log says
        // which one it was, once.
        val startedAt = System.nanoTime()
        var lastRxGrowth = startedAt
        var stallReported = false
        while (activeGeneration == generation && coordinator.isCurrentGeneration(generation)) {
            val dump = runCatching { awgNative.getConfig(handle) }.getOrNull()
            val rx = sumUapiCounter(dump, "rx_bytes=")
            val tx = sumUapiCounter(dump, "tx_bytes=")
            if (rx >= 0 && tx >= 0) {
                val upDelta = if (lastTx in 0..tx) tx - lastTx else 0
                val downDelta = if (lastRx in 0..rx) rx - lastRx else 0
                lastRx = rx
                lastTx = tx
                val now = System.nanoTime()
                if (downDelta > 0) lastRxGrowth = now
                if (!stallReported && verdictGiven && rx > 0 &&
                    now - lastRxGrowth >= AWG_STALL_AFTER_MS * 1_000_000L
                ) {
                    stallReported = true
                    CoreManager.appendLog(
                        "AmneziaWG: ⚠ приём встал на %d Б через %d с после подключения"
                            .format(rx, (lastRxGrowth - startedAt) / 1_000_000_000L),
                    )
                }
                if (!verdictGiven && System.nanoTime() >= verdictAt) {
                    verdictGiven = true
                    CoreManager.appendLog(
                        when {
                            rx > 0 && tx > 0 -> "AmneziaWG: обмен идёт (↑%d ↓%d Б)".format(tx, rx)
                            tx > 0 -> "AmneziaWG: ⚠ отправлено %d Б, с сервера НЕ пришло ничего"
                                .format(tx)
                            else -> "AmneziaWG: ⚠ в туннель не ушло ни байта"
                        },
                    )
                }
                val sample = accumulator.add(upDelta, downDelta, System.nanoTime())
                CoreManager.setTraffic(
                    TrafficStats(
                        uplink = sample.uplink,
                        downlink = sample.downlink,
                        uplinkTotal = sample.uplinkTotal,
                        downlinkTotal = sample.downlinkTotal,
                    ),
                )
            }
            delay(
                if (LeanForeground.visible) {
                    AWG_TRAFFIC_INTERVAL_MS
                } else {
                    AWG_TRAFFIC_IDLE_INTERVAL_MS
                },
            )
        }
    }

    /** Sums one UAPI counter across every peer; -1 when the dump carried none. */
    private fun sumUapiCounter(dump: String?, key: String): Long {
        if (dump.isNullOrEmpty()) return -1
        var total = -1L
        dump.lineSequence().forEach { line ->
            if (line.startsWith(key)) {
                val value = line.substringAfter('=').trim().toLongOrNull() ?: return@forEach
                total = (if (total < 0) 0L else total) + value
            }
        }
        return total
    }

    private suspend fun awaitPhysicalNetwork(): Network {
        while (true) {
            networkMonitor.currentNetwork()?.let { return it }
            delay(PHYSICAL_NETWORK_POLL_MS)
        }
    }

    private fun handleDefaultNetwork(
        generation: Long,
        network: Network?,
        transition: DefaultNetworkTransition?,
    ) {
        if (activeGeneration != generation || !coordinator.isCurrentGeneration(generation)) return
        // Everything here is best-effort. setUnderlyingNetworks is advisory metadata for
        // the OS, and these callbacks fire during exactly the moments the link is flaky
        // (handover, transient loss), so a single failure must never tear down a tunnel
        // that is otherwise fine. Tearing down from a network callback turned an ordinary
        // Wi-Fi/LTE blip into a dropped connection.
        runCatching { tunController.setUnderlyingNetwork(generation, network) }
            .onFailure { error ->
                CoreManager.appendLog(
                    tr("⚠ смена сети: %s").format(error.message ?: error.javaClass.simpleName),
                )
            }
        if (activeUsesNeko &&
            transition is DefaultNetworkTransition.Handover &&
            activeSettings?.resetConnectionsOnNetworkChange == true
        ) {
            resetConnections(generation)
        }
    }

    /**
     * Drops every existing connection so the core re-dials on the new network, twice.
     *
     * Once is not enough, and the captured logs show why. The callback fires the instant
     * the new default is announced, which is before it is necessarily usable: in one
     * session the reset ran and the very next dials still failed with
     * "connect: network is unreachable" for several seconds, after which nothing tried
     * again and the tunnel stayed dead while still reporting «подключено». The repeat is
     * the cheap insurance, resetting an already-healthy core costs one round of
     * re-dials, which is what a handover needs anyway.
     */
    private fun resetConnections(generation: Long) {
        fun reset() = runCatching {
            // resetAllConnections() closes only the sockets conntrack knows about. Two
            // things would still point at the interface that just died:
            //
            // * every DNS transport and the resolver cache: the core keeps asking a DoH
            //    endpoint bound to the dead link and keeps serving the stale negative
            //    answers, so names stop resolving entirely while the tunnel still reports
            //    «подключено»;
            //  * WireGuard endpoints, whose UDP socket is only re-bound by the
            //    InterfaceUpdated() pass.
            //
            // Router().ResetNetwork() does all three. The core exposes it per instance,
            // hence activeBox; the static call covers the window where a box is starting
            // or already gone.
            activeBox?.resetNetwork() ?: Libcore.resetAllConnections(true)
        }
            .onFailure { error ->
                CoreManager.appendLog(
                    tr("⚠ сброс соединений: %s").format(error.message ?: error.javaClass.simpleName),
                )
            }
        reset()
        scope.launch {
            delay(NETWORK_SETTLE_RESET_MS)
            // The generation guard is what keeps this from touching a tunnel that was
            // torn down (or replaced), while we waited.
            if (activeGeneration == generation &&
                activeUsesNeko &&
                coordinator.isCurrentGeneration(generation)
            ) {
                reset()
            }
        }
    }

    /**
     * Protects a probe socket (per-server ping) from the tunnel.
     *
     * Best-effort: a failure must not abort the probe, [Pinger] then measures the proxied
     * path instead of the real one, but it is logged rather than swallowed, because
     * "the ping went through the VPN" is otherwise a symptom with no signal behind it.
     */
    private fun protectRawFd(fd: FileDescriptor) {
        runCatching {
            ParcelFileDescriptor.dup(fd).use { duplicate ->
                protectSocket(duplicate.fd)
            }
        }.onFailure { e ->
            Log.w(TAG, "probe protect() failed — this ping will run through the tunnel", e)
        }
    }

    private fun notification(text: String): Notification = baseBuilder().setContentText(text).build()

    private fun baseBuilder(): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            // not ic_launcher_foreground: that vector pads its mark into a 108dp
            // adaptive-icon safe zone, and Android draws a notification's small icon
            // at native size with no equivalent crop, the mark rendered tiny inside
            // its own bounds. ic_mono_mark is the same star, full-bleed in a 24dp
            // viewport with no padding, which is what a status-bar icon actually wants.
            .setSmallIcon(R.drawable.ic_mono_mark)
            .setOngoing(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
    }

    /**
     * The line that varies between two connected notifications, or null when nothing does.
     *
     * Null is what lets [startNotificationUpdates] skip a rebuild entirely: with the speed
     * line turned off, every notification for a whole session is byte-for-byte identical.
     */
    private fun notificationSpeedLine(traffic: TrafficStats): String? =
        if (showSpeed) {
            "↓ ${formatBytes(traffic.downlink)}/s · ↑ ${formatBytes(traffic.uplink)}/s"
        } else {
            null
        }

    private fun connectedNotification(traffic: TrafficStats): Notification {
        val builder = baseBuilder()
            .setContentTitle(getString(R.string.notif_connected, connectedName.orEmpty()))
            .setWhen(connectedAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
        notificationSpeedLine(traffic)?.let(builder::setContentText)
        builder.addAction(0, tr("Пауза"), servicePendingIntent(ACTION_PAUSE, REQ_PAUSE))
        builder.addAction(0, tr("Отключить"), servicePendingIntent(ACTION_STOP, REQ_STOP))
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, LeanVpnService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun startNotificationUpdates() {
        stopNotificationUpdates()
        val generation = activeGeneration
        if (serviceDestroyed || generation == 0L) return
        val manager = getSystemService(NotificationManager::class.java)
        val first = CoreManager.traffic.value
        manager.notify(NOTIFICATION_ID, connectedNotification(first))
        var shown = notificationSpeedLine(first)
        notifJob = scope.launch {
            CoreManager.traffic.collect { traffic ->
                if (serviceDestroyed || activeGeneration != generation) return@collect
                // Only when the visible text actually changed.
                //
                // Traffic ticks once a second for as long as the tunnel is up, and each
                // notify() is a binder round trip to the system NotificationManager plus a
                // freshly built Notification. On an idle tunnel, a phone in a pocket, the
                // common case, every one of those rebuilds identical text; and with the
                // speed line turned off in settings the notification never changes at all,
                // so the whole loop was pure cost for the entire session. The elapsed-time
                // chronometer keeps ticking on its own: the system runs it, not us.
                val line = notificationSpeedLine(traffic)
                if (line == shown) return@collect
                shown = line
                manager.notify(NOTIFICATION_ID, connectedNotification(traffic))
            }
        }
    }

    private fun stopNotificationUpdates() {
        notifJob?.cancel()
        notifJob = null
    }

    companion object {
        private const val TAG = "LeanVpnService"
        const val ACTION_START = "com.th3web.lean.action.START"
        const val ACTION_STOP = "com.th3web.lean.action.STOP"
        const val ACTION_PAUSE = "com.th3web.lean.action.PAUSE"
        const val EXTRA_PROFILE_ID = "profile_id"

        /** See [DesiredConnection.Running.restart], rebuild even if already on this profile. */
        const val EXTRA_RESTART = "restart"
        private const val CHANNEL_ID = "lean_vpn"
        private const val NOTIFICATION_ID = 0x1EA4
        private const val REQ_STOP = 1
        private const val REQ_PAUSE = 2
        private const val AWG_NETWORK_TIMEOUT_MS = 10_000L
        private const val MAX_CONNECTION_ERROR_LOG_LINES = 96
        private const val PHYSICAL_NETWORK_POLL_MS = 25L

        /**
         * The core's cache file and the sidecars bbolt may leave beside it. Names must
         * match `experimental.cache_file.path` in the generated config.
         */
        private val CORE_CACHE_FILES = listOf("cache.db", "cache.db.lock", "cache.db-wal")

        /**
         * How long after a handover to reset connections a second time. Long enough for a
         * carrier link to finish coming up (the observed failures ran a few seconds past
         * the announcement), short enough that a user who is actively browsing sees the
         * tunnel recover rather than wonder whether it is dead.
         */
        private const val NETWORK_SETTLE_RESET_MS = 4_000L

        /** Matches the sing-box status client so both paths tick the UI alike. */
        private const val AWG_TRAFFIC_INTERVAL_MS = 1_000L
        /** How long to let a fresh tunnel run before judging whether it carries. */
        private const val AWG_VERDICT_DELAY_MS = 8_000L
        /** Silence on the receive side for this long is a stall worth naming. */
        private const val AWG_STALL_AFTER_MS = 15_000L
        private const val AWG_TRAFFIC_IDLE_INTERVAL_MS = 10_000L

        /** Bounded window for the graceful coordinator drain in [onDestroy] (main thread). */
        private const val DESTROY_DRAIN_TIMEOUT_MS = 2_000L

        /** Enough tail for a diagnostics report to be useful, far below what it reached. */
        private const val CORE_LOG_MAX_BYTES = 2L * 1024 * 1024
        private const val CORE_LOG_KEEP_BYTES = 512L * 1024

        private const val SERVICE_PREFS = "lean_service"
        private const val KEY_LAST_SYSTEM_START = "last_system_start"
        private const val KEY_SYSTEM_START_STREAK = "system_start_streak"

        /**
         * Two system starts closer together than this are treated as one loop rather than
         * two unrelated events, long enough to cover a connect that dies on its way up,
         * short enough that a genuine reboot or a much later always-on start counts fresh.
         */
        private const val SYSTEM_START_WINDOW_MS = 90_000L
        private const val MAX_SYSTEM_START_STREAK = 3
    }
}
