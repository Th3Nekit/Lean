package com.th3web.lean.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.core.CoreManager
import com.th3web.lean.core.VpnState
import com.th3web.lean.data.SpeedTestHistoryStore
import com.th3web.lean.data.net.SpeedTest

/**
 * Drives «Скорость»: finds the servers, runs the test, keeps the history.
 *
 * A ViewModel rather than screen-local state because a run lasts tens of seconds and must
 * survive what happens during them: a rotation, the keyboard, a configuration change, the
 * user scrolling to the history and back. It is scoped to the screen's entry, so leaving
 * the screen cancels the transfer instead of leaving it running on the user's data.
 *
 * ## The user decides what gets measured
 *
 * With a tunnel up there are two different questions — "what do I get through the VPN"
 * and "what does this network actually carry" — and the answer to both takes twice the
 * time and twice the traffic. [Scope] is that choice, remembered between visits: someone
 * who only wants the tunnel figure should not pay for a comparison they never read, and
 * someone debugging their carrier wants the bare network on its own. «Сравнить» stays the
 * default because the difference is the one number this screen can give that a web
 * speedtest cannot.
 *
 * ## Automatic by default, choosable when it matters
 *
 * The server list comes back ordered by distance from whoever asked, and the first few are
 * timed so the quickest that actually transfers wins ([SpeedTest.pickFastest]) — which is
 * what Speedtest's own client does. That is the right default: nobody opening this screen
 * wants to pick a city first. The list stays visible because the pick is occasionally
 * wrong for a reason the app cannot see (a sponsor that throttles, a server that is busy),
 * and because "measure to Moscow specifically" is a legitimate thing to want.
 *
 * The chooser always has entries, even where Ookla's directory is blocked, because
 * [SpeedTest.BUILT_IN_TARGETS] is offered alongside whatever was discovered.
 *
 * ## The comparison measures one server, not two
 *
 * The second leg — the link underneath the tunnel — reuses the **same** target as the
 * first whenever that target answers directly, and only looks for its own when it does
 * not. An earlier version discovered a server per leg, which reads as the more careful
 * choice and is not: with the directory blocked on a Russian carrier the direct leg would
 * land on a Moscow server while the tunnel leg measured to the exit node's city, and the
 * «туннель стоит −60 %» that follows is a statement about two different distances, not
 * about the tunnel. One endpoint, two paths, one honest difference.
 */
class SpeedTestViewModel : ViewModel() {

    /**
     * What a run measures. The ids are persisted, so they are spelled out rather than
     * taken from [Enum.name].
     */
    enum class Scope(val id: String) {
        /** Only the path through the tunnel: one leg, the figure most people came for. */
        Tunnel("tunnel"),

        /** Only the link underneath it: one leg, measured past the tunnel on purpose. */
        Direct("direct"),

        /** Both, in that order, so the screen can state what the tunnel costs. */
        Both("both"),
        ;

        companion object {
            fun of(id: String?): Scope = entries.firstOrNull { it.id == id } ?: Both
        }
    }

    sealed interface Stage {
        data object Idle : Stage

        data class Running(
            /**
             * What is being measured, or null while a target is still being chosen —
             * the search is part of the run and the screen has to be able to say so.
             */
            val phase: SpeedTest.Phase?,
            val viaTunnel: Boolean,
            val mbps: Double,
            /**
             * How far the whole run has got, 0..1, and it never goes backwards. Driven by
             * the clock ([RunProgress]), not by the arrival of samples: the samples stop
             * entirely during the parts of a run that take longest, and an indicator that
             * stops with them is the "скачет" this field was rewritten for.
             */
            val overall: Float,
        ) : Stage

        data object Done : Stage
    }

    private val app get() = LeanApp.instance
    private val history by lazy { SpeedTestHistoryStore(app) }

    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    /** Servers to measure against: what was discovered, plus the built-in targets. */
    private val _servers = MutableStateFlow<List<SpeedTest.Server>>(emptyList())
    val servers: StateFlow<List<SpeedTest.Server>> = _servers.asStateFlow()

    /** The chosen server, or null while «Авто» is in effect. */
    private val _pinned = MutableStateFlow<SpeedTest.Server?>(null)
    val pinned: StateFlow<SpeedTest.Server?> = _pinned.asStateFlow()

    /** What the last/current run is actually measuring against. */
    private val _active = MutableStateFlow<SpeedTest.Server?>(null)
    val active: StateFlow<SpeedTest.Server?> = _active.asStateFlow()

    /** Which legs the next run measures. Remembered between visits. */
    private val _scope = MutableStateFlow(Scope.Both)
    val scope: StateFlow<Scope> = _scope.asStateFlow()

    /**
     * Whether a tunnel is up **right now**, which is what decides whether there is a
     * choice to make at all. Deliberately not [hadTunnel], which describes the figures on
     * screen: after a run made without a tunnel, that one stays false while the user may
     * well have connected since.
     */
    val tunnelUp: StateFlow<Boolean> = CoreManager.state
        .map { it is VpnState.Connected }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CoreManager.state.value is VpnState.Connected,
        )

    private val _primary = MutableStateFlow<SpeedTest.Result?>(null)
    val primary: StateFlow<SpeedTest.Result?> = _primary.asStateFlow()

    private val _direct = MutableStateFlow<SpeedTest.Result?>(null)
    val direct: StateFlow<SpeedTest.Result?> = _direct.asStateFlow()

    private val _runs = MutableStateFlow(emptyList<SpeedTestHistoryStore.Entry>())
    val runs: StateFlow<List<SpeedTestHistoryStore.Entry>> = _runs.asStateFlow()

    /** Whether the figures on screen were measured through a tunnel. */
    private val _hadTunnel = MutableStateFlow(false)
    val hadTunnel: StateFlow<Boolean> = _hadTunnel.asStateFlow()

    /**
     * The largest rate the dial has to fit, so it can scale itself to the connection
     * instead of to a guess. A 10-gigabit line and a 10-megabit one cannot share a scale.
     *
     * It only ever grows **within** a run, and a new run inherits the figure the last one
     * reached rather than starting from zero. Starting from zero is what made the dial
     * step up three or four times in the first seconds of every run, and a dial that
     * re-scales moves its needle *backwards* while the number beside it is going up: on a
     * 300-Мбит/с line the needle fell by nineteen points of the arc as the rate passed 96
     * Мбит/с. Inheriting the last run's figure costs nothing when the connection is the
     * same, and one zoom-out when it is not.
     */
    private val _dialPeak = MutableStateFlow(0.0)
    val dialPeak: StateFlow<Double> = _dialPeak.asStateFlow()

    private var job: Job? = null

    /**
     * The clock the indicator runs on. Replaced per run, read from whichever thread a
     * sample happens to arrive on, hence volatile.
     */
    @Volatile
    private var progress = RunProgress(legs = 1)

    init {
        viewModelScope.launch {
            _runs.value = history.load()
            _scope.value = Scope.of(history.scopeId)
        }
    }

    val running: Boolean get() = _stage.value is Stage.Running

    /**
     * Loads the chooser's list without starting a test, and restores the target the user
     * pinned last time if it is in it.
     *
     * The id was already being stored on every pick and never read back, so «запомнили
     * сервер» was a promise the screen did not keep. It is matched against the list rather
     * than trusted: the saved target may belong to a connection that is no longer up, and
     * a pin nobody can see is worse than «Авто».
     */
    fun loadServers() {
        if (_servers.value.isNotEmpty() || running) return
        viewModelScope.launch {
            val viaTunnel = CoreManager.state.value is VpnState.Connected
            val offered = offer(SpeedTest.discoverServers(app, viaTunnel))
            _servers.value = offered
            val remembered = history.selectedEndpointId
            // Only if the user has not picked something in the meantime: discovery is a
            // network request and the chooser is live while it runs.
            if (remembered != null && _pinned.value == null) {
                _pinned.value = offered.firstOrNull { it.id == remembered }
            }
        }
    }

    /** Pins a server, or passes null to go back to «Авто». Clears stale figures. */
    fun selectServer(server: SpeedTest.Server?) {
        if (_pinned.value?.id == server?.id) return
        _pinned.value = server
        history.selectedEndpointId = server?.id
        if (!running) {
            _primary.value = null
            _direct.value = null
            _dialPeak.value = 0.0
            _stage.value = Stage.Idle
        }
    }

    /**
     * Picks what the next run measures.
     *
     * The figures already on screen stay — they were measured, and they are still true of
     * the path they were measured on. The comparison is the one thing that cannot survive
     * the change: with «Сравнить» switched off nothing will refresh the second half of it,
     * and a pair where one number is from a previous run is the kind of chart that invents
     * a result.
     */
    fun selectScope(scope: Scope) {
        if (_scope.value == scope) return
        _scope.value = scope
        history.scopeId = scope.id
        if (scope != Scope.Both && !running) _direct.value = null
    }

    /**
     * Runs the test, as [Scope] asks: through the tunnel, past it, or both in that order.
     *
     * The tunnel figure is what the user came for, so it lands first and the comparison
     * fills in after; a direct leg that cannot run (no non-VPN network, a carrier that
     * blocks the target) therefore costs the screen nothing.
     */
    fun start() {
        if (running) return
        job?.cancel()
        job = viewModelScope.launch {
            // Read before the figures are cleared: the dial opens at the range the last
            // run ended on, see [dialPeak].
            _dialPeak.value = maxOf(
                _primary.value?.downloadMbps ?: 0.0,
                _primary.value?.uploadMbps ?: 0.0,
            )
            _primary.value = null
            _direct.value = null

            val connectedState = CoreManager.state.value as? VpnState.Connected
            val connected = connectedState != null
            // With no tunnel up there is nothing to choose between: the one measurable
            // path is the default network, which is what the first leg always takes.
            val mode = if (connected) _scope.value else Scope.Tunnel
            val compare = mode == Scope.Both
            val bypass = mode == Scope.Direct
            val legs = if (compare) 2 else 1
            _hadTunnel.value = connected && !bypass

            val serverName = connectedState?.let { state ->
                app.profiles.state.value.profiles.firstOrNull { it.id == state.profileId }?.name
            }.orEmpty()

            progress = RunProgress(legs)
            // A child of this job, so cancelling the run stops the indicator with it.
            val ticker = launch {
                while (true) {
                    delay(TICK_MS)
                    tick()
                }
            }
            try {
                searching(viaTunnel = !bypass)
                val target = resolveTarget(viaTunnel = !bypass)
                _active.value = target
                val primaryResult = SpeedTest.run(app, target, viaTunnel = !bypass) {
                    onSample(viaTunnel = !bypass, sample = it)
                }
                _primary.value = primaryResult

                val directResult = if (compare) {
                    searching(viaTunnel = false)
                    val directTarget = resolveDirectTarget(target)
                    SpeedTest.run(app, directTarget, viaTunnel = false) {
                        onSample(viaTunnel = false, sample = it)
                    }
                } else {
                    null
                }
                _direct.value = directResult?.takeUnless { it.isEmpty }

                if (!primaryResult.isEmpty) {
                    _runs.value = history.add(
                        SpeedTestHistoryStore.Entry(
                            atMillis = primaryResult.atMillis,
                            serverName = if (_hadTunnel.value) serverName else "",
                            downloadMbps = primaryResult.downloadMbps,
                            uploadMbps = primaryResult.uploadMbps,
                            pingMs = primaryResult.pingMs,
                            jitterMs = primaryResult.jitterMs,
                            directDownloadMbps = _direct.value?.downloadMbps,
                            directUploadMbps = _direct.value?.uploadMbps,
                            endpointId = target.id,
                            endpointLabel = "${target.sponsor} · ${target.place}",
                            direct = !_hadTunnel.value,
                        ),
                    )
                }
            } finally {
                ticker.cancel()
            }
            _stage.value = Stage.Done
        }
    }

    /**
     * Which server this leg measures against: the pinned one, else the best of the
     * freshly discovered list, else of the built-in targets.
     *
     * The fallback is not cosmetic. Ookla's directory is the first thing a filtering
     * network blocks — and is blocked outright from Russia — and a screen that answers
     * "не удалось" where it could have answered "42 Мбит/с" is worse than one that
     * measures somewhere slightly less ideal.
     */
    private suspend fun resolveTarget(viaTunnel: Boolean): SpeedTest.Server {
        _pinned.value?.let { return it }
        val found = SpeedTest.discoverServers(app, viaTunnel)
        if (viaTunnel) _servers.value = offer(found)
        val candidates = found.ifEmpty { SpeedTest.BUILT_IN_TARGETS }
        return SpeedTest.pickFastest(app, candidates, viaTunnel)
            ?: SpeedTest.BUILT_IN_TARGETS.first()
    }

    /** The same target as the tunnel leg when it answers directly too; see the class note. */
    private suspend fun resolveDirectTarget(shared: SpeedTest.Server): SpeedTest.Server =
        if (SpeedTest.verify(app, shared, viaTunnel = false)) shared else resolveTarget(viaTunnel = false)

    /** Whatever was discovered, with the built-in targets behind it, one entry per host. */
    private fun offer(found: List<SpeedTest.Server>): List<SpeedTest.Server> =
        (found + SpeedTest.BUILT_IN_TARGETS).distinctBy { it.host }

    /** A live sample: a rate to show, and the step it says the run has reached. */
    private fun onSample(viaTunnel: Boolean, sample: SpeedTest.Progress) {
        if (sample.mbps > _dialPeak.value) _dialPeak.value = sample.mbps
        val now = System.nanoTime()
        progress.enter(RunProgress.Step.of(sample.phase), viaTunnel, now)
        _stage.value = Stage.Running(
            phase = sample.phase,
            viaTunnel = viaTunnel,
            mbps = sample.mbps,
            overall = progress.at(now),
        )
    }

    /** The run is choosing a target: no rate yet, but the indicator keeps moving. */
    private fun searching(viaTunnel: Boolean) {
        val now = System.nanoTime()
        progress.enter(RunProgress.Step.Search, viaTunnel, now)
        _stage.value = Stage.Running(
            phase = null,
            viaTunnel = viaTunnel,
            mbps = 0.0,
            overall = progress.at(now),
        )
    }

    /**
     * Advances the indicator between samples, which is most of a run: discovery, the
     * verification before the second leg and every phase boundary pass without a single
     * sample, and the progress reported before this was a clock stood still through all
     * of them and then leapt.
     *
     * An atomic update rather than a plain write: a sample can land on an IO thread
     * between this read and its write, and that sample is the newer truth about
     * everything except the one field this touches.
     */
    private fun tick() {
        val now = System.nanoTime()
        _stage.update { stage ->
            if (stage is Stage.Running) stage.copy(overall = progress.at(now)) else stage
        }
    }

    /** Stops a run in flight. Partial numbers stay on screen; nothing is recorded. */
    fun stop() {
        job?.cancel()
        job = null
        _stage.value = if (_primary.value == null) Stage.Idle else Stage.Done
    }

    fun clearHistory() {
        history.clear()
        _runs.value = emptyList()
    }

    /**
     * What the tunnel costs, in percent of the direct rate, or null when there is no pair.
     * Negative means slower through the tunnel, which is the normal case; a positive
     * number happens on carriers that shape plain traffic harder than tunnelled traffic,
     * and it is worth showing rather than clamping away.
     */
    val downloadDeltaPercent: Int?
        get() {
            val tunnel = _primary.value?.downloadMbps ?: return null
            val direct = _direct.value?.downloadMbps ?: return null
            if (direct <= 0.0) return null
            return (((tunnel - direct) / direct) * 100).toInt()
        }

    private companion object {
        /**
         * How often the indicator is refreshed between samples. Ten a second: enough that
         * the crawl is continuous even at «Анимации: off», where there is no interpolation
         * to cover the gaps, and cheap enough on a screen whose only live element is one
         * arc drawn in a draw-phase modifier.
         */
        const val TICK_MS = 100L
    }
}

/** [SpeedTest]'s own transfer window. The two transfers are the bulk of a run. */
private const val TRANSFER_MS = 8_000L

/**
 * How far a run has got, as one number that only ever grows.
 *
 * ## Why the clock and not the measurement
 *
 * The first version of this was handed the current *phase's* fraction, and a two-leg run
 * has six phases, so the indicator ran to the end and snapped back six times. Weighting
 * the phases fixed the snapping back and left the worse half of the problem: progress only
 * existed when a sample arrived, and samples arrive only while bytes are moving. Choosing
 * a server, verifying that it answers directly before the second leg, and every handover
 * in between emit nothing at all for seconds at a time — the indicator sat still through
 * the longest parts of the run and then jumped, which is what "скачет" describes.
 *
 * So progress is a function of time. [enter] says which step the run is in and when it
 * started; [at] answers where that puts the bar now, for any instant, with or without
 * samples.
 *
 * ## Why these shares
 *
 * Each step owns the share of the bar that matches how long it takes, so the bar advances
 * at a roughly constant pace instead of crawling through an eight-second transfer and
 * sprinting through a handful of latency probes. The two transfers therefore own about
 * four fifths of a leg between them, which is also where four fifths of the time goes.
 *
 * A step with a known length — a transfer, which is a fixed window — fills its share at
 * an even pace. A step whose length nobody can know, such as finding a server behind a
 * filtering carrier, fills it on a curve instead: half of it by the time it usually takes,
 * ever slower after that, never quite all of it. Which means the bar keeps moving for as
 * long as a wait lasts and can never claim a step is done before it is. See [fill].
 */
internal class RunProgress(
    private val legs: Int,
    startedAt: Long = System.nanoTime(),
) {

    /** The steps of one leg, in order, with how long each usually takes. */
    enum class Step(val expectedMs: Long, val timed: Boolean) {
        /** Discovery and probing, or — on the second leg — verifying the shared target. */
        Search(2_500, timed = false),

        /** Six quick requests, whose length is the latency being measured. */
        Latency(1_500, timed = false),

        Download(TRANSFER_MS, timed = true),
        Upload(TRANSFER_MS, timed = true),
        ;

        companion object {
            fun of(phase: SpeedTest.Phase): Step = when (phase) {
                SpeedTest.Phase.Latency -> Latency
                SpeedTest.Phase.Download -> Download
                SpeedTest.Phase.Upload -> Upload
            }
        }
    }

    /** Where the run is, as one value so a reader never sees half of a change. */
    private data class Mark(val step: Step, val leg: Int, val atNanos: Long)

    @Volatile
    private var mark = Mark(Step.Search, leg = 0, atNanos = startedAt)

    /**
     * The highest value handed out so far. Belt and braces: the shares already make the
     * bar monotonic, but a sample that arrives out of order — a straggler from the leg
     * just finished — must not be able to pull it back.
     */
    @Volatile
    private var highest = 0f

    /**
     * Notes that the run has reached [step] of the leg [viaTunnel] identifies, at
     * [nowNanos]. Repeating the step the run is already in changes nothing, which is what
     * makes this safe to call from every sample.
     */
    fun enter(step: Step, viaTunnel: Boolean, nowNanos: Long) {
        val leg = if (legs > 1 && !viaTunnel) 1 else 0
        val current = mark
        if (current.step == step && current.leg == leg) return
        mark = Mark(step, leg, nowNanos)
    }

    /** Where the bar sits at [nowNanos], 0..1. */
    fun at(nowNanos: Long): Float {
        val current = mark
        val elapsedMs = (nowNanos - current.atNanos).coerceAtLeast(0L) / 1_000_000f
        val raw = position(current.step, current.leg, elapsedMs, legs)
        val next = maxOf(highest, raw)
        highest = next
        return next
    }

    internal companion object {

        private val STEPS = Step.entries
        private val TOTAL_MS = STEPS.sumOf { it.expectedMs }.toFloat()

        /** Where [step] at [elapsedMs] of leg [leg] puts a [legs]-leg run, 0..1. */
        fun position(step: Step, leg: Int, elapsedMs: Float, legs: Int): Float {
            val filled = fill(step, elapsedMs.coerceAtLeast(0f))
            val within = floorOf(step) + shareOf(step) * filled
            return ((leg + within) / legs.coerceAtLeast(1)).coerceIn(0f, 1f)
        }

        /**
         * How much of its own share [step] has filled after [elapsedMs].
         *
         * Two regimes, because there are two kinds of waiting. A transfer is a window the
         * test itself times, so it fills linearly: an even pace for eight seconds, which
         * is what makes the whole bar move at one speed. Everything else is a wait nobody
         * can put a number on — a server being chosen behind a filtering carrier — and
         * fills on a curve instead: half the share by the time it usually takes, slower
         * and slower after that, never arriving.
         *
         * Even the timed window stops just short and finishes on the curve, because a
         * window overruns: the workers are joined after the deadline and a body already in
         * flight has to be read out first, which is a second or two with nothing to
         * report. The bar has to keep moving through that too. A stopped indicator is the
         * whole reason this class exists.
         */
        private fun fill(step: Step, elapsedMs: Float): Float {
            val expected = step.expectedMs.toFloat()
            if (!step.timed) return creep(elapsedMs, expected)
            val linear = (elapsedMs / expected).coerceAtMost(1f)
            val over = (elapsedMs - expected).coerceAtLeast(0f)
            return TIMED_FILL * linear + (1f - TIMED_FILL) * creep(over, expected / 4f)
        }

        /** Half of it at [expected], ever slower after that, never all of it. */
        private fun creep(elapsedMs: Float, expected: Float): Float =
            elapsedMs / (elapsedMs + expected)

        /** How much of a timed step's share the window itself fills; the rest is overrun. */
        private const val TIMED_FILL = 0.95f

        private fun shareOf(step: Step): Float = step.expectedMs / TOTAL_MS

        private fun floorOf(step: Step): Float =
            STEPS.take(step.ordinal).sumOf { it.expectedMs } / TOTAL_MS
    }
}
