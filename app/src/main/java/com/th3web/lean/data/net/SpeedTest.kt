package com.th3web.lean.data.net

import android.content.Context
import android.net.Network
import android.util.Log
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val MIB = 1_048_576L

/** The first upload body, and the range [nextUploadSize] may choose from. */
private const val UPLOAD_FIRST_BODY_BYTES = 1 * MIB
private const val UPLOAD_MIN_BODY_BYTES = 256 * 1024L
private const val UPLOAD_MAX_BODY_BYTES = 32 * MIB

/** How much of the time left one upload body aims to fill. */
private const val UPLOAD_FILL = 0.9

/**
 * The measurement behind «Скорость», on the same infrastructure Speedtest itself uses —
 * and on a built-in list of targets for the networks where that infrastructure cannot be
 * reached.
 *
 * ## Why Ookla's servers and not a fixed list of hosts
 *
 * The first version of this probed a handful of hand-picked public files (Hetzner, OVH,
 * Tele2, a Russian host). That was wrong in three ways at once, and all three showed up
 * the moment it was used on a real connection: several of those hosts are unreachable or
 * throttled from RU, none of them accepts an upload, so «Отдача» was permanently a dash,
 * and a hand-picked list cannot answer the only question that matters — "how fast is it
 * from where my traffic actually leaves".
 *
 * Ookla publishes the server list its own client uses, as plain JSON, keyed off the
 * caller's address ([SERVER_LIST_URL]). Three things follow:
 *
 *  * the list is already ordered by distance from whoever is asking, so with a tunnel up
 *    it names the servers nearest the **exit node**, and without one the servers nearest
 *    the carrier — which is exactly the comparison the screen is built around;
 *  * every one of those servers speaks the same documented endpoints, including an upload
 *    endpoint, so both directions are measurable everywhere;
 *  * they are provisioned for this: a Speedtest server is not a static file on someone's
 *    web host, it is sized to saturate a gigabit line.
 *
 * ## …and why there is a built-in list anyway
 *
 * `www.speedtest.net` is blocked from Russia, so on a Russian carrier — which is to say
 * on the direct leg of every comparison this app's users run, and on the whole screen
 * whenever the tunnel is down — the directory returns nothing at all. The fallback used
 * to be Cloudflare alone, which left the screen with one unchoosable target measured
 * across a border. [BUILT_IN_TARGETS] are therefore shipped in the app: verified Russian
 * endpoints that speak one of the protocols below, so the chooser has entries and the
 * numbers are in-country, with Cloudflare kept as the last resort.
 *
 * ## Protocols
 *
 * Both verified against live servers before this was written.
 *
 * Ookla ([Server.Kind.Ookla]):
 *  * `GET https://<host>/speedtest/latency.txt` — ten bytes, for the round trip;
 *  * `GET https://<host>/download?size=<bytes>` — returns exactly that many bytes;
 *  * `POST https://<host>/upload` — accepts a body of a declared length.
 *
 * LibreSpeed ([Server.Kind.LibreSpeed]), which is what the Russian endpoint runs:
 *  * `GET https://<host>/empty.php` — empty 200, for the round trip;
 *  * `GET https://<host>/garbage.php?ckSize=<megabytes>` — that many MiB;
 *  * `POST https://<host>/empty.php` — body discarded, answered immediately.
 *
 * Cloudflare ([Server.Kind.Cloudflare]) keeps `__down` / `__up`, with one hard limit
 * worth stating because it cost a release: **`__down` answers 403 to `bytes=100000000`
 * and anything above it**, which is exactly the size this code used to ask for. Every
 * download stream died on its first response, so «Приём» was a dash on the only target
 * reachable from Russia, while «Отдача» — whose request carries no size — kept working.
 * Sizes now come from [Server.downloadChunkBytes] per protocol, and [verify] re-checks
 * the real request shape before a run, so a cap like that cannot silently empty the
 * screen again.
 *
 * All over HTTPS on the server's own port, so no cleartext exemption is needed.
 *
 * ## Many streams, because one is not a speed
 *
 * A single TCP connection does not fill a fast link: window scaling, one flow's loss
 * recovery and a proxy's per-connection buffers all cap it well below the line rate,
 * which is why every real speed test opens several. [STREAMS] run in parallel and their
 * bytes are summed; on a gigabit tunnel the difference between one stream and four is the
 * difference between reporting 200 Мбит/с and reporting the truth.
 */
object SpeedTest {

    /** One measurement target. */
    data class Server(
        val id: String,
        /** Who runs it, e.g. "Rostelecom" — the label the chooser shows. */
        val sponsor: String,
        /** Where it is, e.g. "Москва" or "Helsinki, Finland". */
        val place: String,
        /** host, or host:port, already HTTPS-capable. */
        val host: String,
        /** Kilometres from the address that asked for the list; 0 when unknown. */
        val distanceKm: Int,
        val kind: Kind = Kind.Ookla,
    ) {
        enum class Kind { Ookla, Cloudflare, LibreSpeed }

        val latencyUrl: String
            get() = when (kind) {
                Kind.Ookla -> "https://$host/speedtest/latency.txt"
                Kind.Cloudflare -> "https://$host/__down?bytes=0"
                Kind.LibreSpeed -> "https://$host/empty.php"
            }

        /**
         * How much one download request asks for.
         *
         * Far more than can arrive inside the window, so the clock ends the transfer
         * rather than the body running out — but never more than the server will serve.
         * Cloudflare's ceiling is a hard 403 (see the class note), so its figure stays
         * well under it.
         */
        val downloadChunkBytes: Long
            get() = when (kind) {
                Kind.Ookla -> OOKLA_CHUNK_BYTES
                Kind.Cloudflare -> CLOUDFLARE_CHUNK_BYTES
                Kind.LibreSpeed -> LIBRESPEED_CHUNK_BYTES
            }

        fun downloadUrl(bytes: Long): String = when (kind) {
            Kind.Ookla -> "https://$host/download?size=$bytes&nocache=${nonce()}"
            Kind.Cloudflare -> "https://$host/__down?bytes=$bytes"
            // ckSize is in MiB, and the endpoint treats a zero as "no data at all".
            Kind.LibreSpeed ->
                "https://$host/garbage.php?ckSize=${(bytes / MIB).coerceAtLeast(1)}&nocache=${nonce()}"
        }

        val uploadUrl: String
            get() = when (kind) {
                Kind.Ookla -> "https://$host/upload"
                Kind.Cloudflare -> "https://$host/__up"
                Kind.LibreSpeed -> "https://$host/empty.php"
            }
    }

    /**
     * Targets that need no directory lookup, in the order they are offered.
     *
     * Each was probed for all three endpoints over HTTPS before being listed; the Russian
     * ones are what make the screen work on a Russian carrier, where Ookla's directory is
     * blocked. A host that rots simply loses the latency probe and is never picked, so a
     * stale entry costs a run nothing — [pickFastest] times the candidates and [verify]
     * checks that the winner really transfers.
     */
    val BUILT_IN_TARGETS: List<Server> = listOf(
        Server("selectel", "Selectel", "Москва", "speedtest.selectel.ru", 0, Server.Kind.LibreSpeed),
        Server("46685", "fdcservers.net", "Москва", "speedtest.dme.fdcservers.net:8080", 0),
        Server("58664", "ProCloud", "Москва", "speedtest.procloud.ru:8080", 0),
        Server("12461", "PG-19", "Таганрог", "speedtesttgn.pg19.ru:8080", 0),
        Server("27144", "Yacolo", "Кемерово", "speedtest.yacolo.com:8080", 0),
        Server("40162", "RIKT", "Междуреченск", "speedtest.rikt.ru.prod.hosts.ooklaserver.net:8080", 0),
        Server("46700", "fdcservers.net", "Хабаровск", "speedtest.khv.fdcservers.net:8080", 0),
        cloudflareFallback(),
    )

    /** The target of last resort: reachable almost everywhere, measured across a border. */
    fun cloudflareFallback(): Server = Server(
        id = "cloudflare",
        sponsor = "Cloudflare",
        place = "ближайший узел",
        host = "speed.cloudflare.com",
        distanceKm = 0,
        kind = Server.Kind.Cloudflare,
    )

    /** Which part of the run is happening, for the live readout. */
    enum class Phase { Latency, Download, Upload }

    /**
     * One live sample. [mbps] is the smoothed rate over the last [SAMPLE_SPAN_MS] (what
     * the needle shows), [fraction] how far this phase has got (0..1).
     */
    data class Progress(val phase: Phase, val mbps: Double, val fraction: Float)

    /**
     * One finished run. A null rate means "this direction could not be measured" — never
     * zero, because zero is a claim about the link and null is a claim about the test.
     * When a direction is null its note says in one phrase why, so the screen can answer
     * «сервер не отвечает» instead of showing a dash and leaving the user to guess.
     */
    data class Result(
        val downloadMbps: Double?,
        val uploadMbps: Double?,
        val pingMs: Int?,
        val jitterMs: Int?,
        val viaTunnel: Boolean,
        val server: Server,
        val downloadNote: String? = null,
        val uploadNote: String? = null,
        /**
         * Which physical link carried it, for a leg that was measured past the tunnel;
         * null for a leg that went through one.
         *
         * A comparison is only readable if both sides are named. Before this was here, a
         * direct leg that had landed on the cellular radio while the tunnel ran over
         * Wi-Fi looked exactly like a tunnel that was twenty times faster than its own
         * underlay — see [PhysicalNetwork].
         */
        val path: String? = null,
        val atMillis: Long = System.currentTimeMillis(),
    ) {
        val isEmpty: Boolean get() = downloadMbps == null && uploadMbps == null && pingMs == null
    }

    /** Why a direction has no number. A closed set, so the phrases can be translated. */
    const val NOTE_NO_ANSWER = "сервер не отвечает"
    const val NOTE_REFUSED = "сервер отклонил запрос"
    const val NOTE_TLS = "TLS не прошёл"
    const val NOTE_DNS = "адрес не разрешается"
    const val NOTE_BLOCKED = "соединение отклонено"
    const val NOTE_NOTHING = "нет данных"

    /**
     * The servers nearest whoever is asking, by proximity, or an empty list when the
     * directory cannot be reached — which on a Russian carrier is always, and is what
     * [BUILT_IN_TARGETS] exists for.
     *
     * [viaTunnel] decides whose neighbourhood that is: over the tunnel the list comes
     * back for the exit node's address, bypassing it for the carrier's. Both are correct
     * answers to different questions, and the screen asks for each in turn.
     *
     * A browser User-Agent is required — the endpoint answers 403 to anything else, which
     * is how the first attempt at this failed.
     */
    suspend fun discoverServers(
        context: Context,
        viaTunnel: Boolean,
        limit: Int = SERVER_LIST_LIMIT,
    ): List<Server> = withContext(Dispatchers.IO) {
        val network = if (viaTunnel) null else PhysicalNetwork.find(context)
        val body = runCatching { fetchText(network, "$SERVER_LIST_URL&limit=$limit") }
            .onFailure { Log.i(TAG, "каталог Ookla недоступен: ${reasonOf(it)}") }
            .getOrNull()
            ?: return@withContext emptyList()
        runCatching { parseServers(body) }.getOrDefault(emptyList())
    }

    private fun parseServers(json: String): List<Server> {
        val array = JSONArray(json)
        val servers = mutableListOf<Server>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            // https_functional is the server's own statement that its HTTPS endpoints
            // work. Anything else would need a cleartext exemption in the manifest, and
            // a VPN client asking for one to run a speed test is not a trade worth making.
            if (item.optInt("https_functional", 0) != 1) continue
            val host = item.optString("host").takeIf { it.isNotBlank() } ?: continue
            servers += Server(
                id = item.optString("id", host),
                sponsor = item.optString("sponsor").ifBlank { host.substringBefore(':') },
                place = listOf(item.optString("name"), item.optString("country"))
                    .filter { it.isNotBlank() }
                    .joinToString(", "),
                host = host,
                distanceKm = item.optDouble("distance", 0.0).toInt(),
            )
        }
        return servers
    }

    /**
     * Of [candidates], the one that answers fastest **and** actually transfers.
     *
     * Distance is a proxy for latency, not a measurement of it: the nearest server by
     * kilometres is routinely not the nearest by route, especially through a tunnel whose
     * exit is somewhere else entirely. So the first few are probed, sorted by what they
     * answered, and then the quickest is asked for a real download before the run commits
     * to it — a server can greet and still refuse the transfer, which is precisely how an
     * entire direction came back empty once already.
     */
    suspend fun pickFastest(
        context: Context,
        candidates: List<Server>,
        viaTunnel: Boolean,
    ): Server? = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext null
        val network = if (viaTunnel) null else PhysicalNetwork.find(context)
        val timed = mutableListOf<Pair<Server, Int>>()
        for (server in candidates.take(LATENCY_CANDIDATES)) {
            if (!currentCoroutineContext().isActive) break
            val ms = timeToFirstByte(network, server.latencyUrl) ?: continue
            timed += server to ms
        }
        timed.sortBy { it.second }
        for ((server, ms) in timed) {
            if (!currentCoroutineContext().isActive) break
            if (serves(network, server)) {
                Log.i(TAG, "цель: ${server.host}, $ms мс")
                return@withContext server
            }
            Log.w(TAG, "${server.host} отвечает, но данные не отдаёт — беру следующий")
        }
        // Nothing could be verified: the quickest to answer is still the best guess
        // available, and an attempt at a measurement beats an empty screen.
        timed.firstOrNull()?.first ?: candidates.firstOrNull()
    }

    /** Whether [server] serves a real download right now, over the path a run would take. */
    suspend fun verify(
        context: Context,
        server: Server,
        viaTunnel: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        serves(if (viaTunnel) null else PhysicalNetwork.find(context), server)
    }

    /**
     * Runs latency, download and upload against [server], reporting live samples.
     * Returns whatever was measured; never throws.
     */
    suspend fun run(
        context: Context,
        server: Server,
        viaTunnel: Boolean,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val network = if (viaTunnel) null else PhysicalNetwork.find(context)
        if (!viaTunnel) Log.i(TAG, "в обход туннеля: ${PhysicalNetwork.describe(context, network)}")
        val latency = measureLatency(network, server, onProgress)
        val download = measureTransfer(network, server, Phase.Download, onProgress)
        val upload = measureTransfer(network, server, Phase.Upload, onProgress)
        Result(
            downloadMbps = download.mbps,
            uploadMbps = upload.mbps,
            pingMs = latency?.first,
            jitterMs = latency?.second,
            viaTunnel = viaTunnel,
            server = server,
            downloadNote = download.note,
            uploadNote = upload.note,
            path = if (viaTunnel) null else PhysicalNetwork.describe(context, network),
        )
    }

    /**
     * Round trip and jitter from [LATENCY_SAMPLES] requests to the server's latency
     * endpoint.
     *
     * The first sample is dropped: it carries DNS, the TCP handshake and TLS, the same
     * cold-start distortion the per-server probes warm away (see [Pinger.tcpLatency]).
     * The reported figure is the minimum of the rest, since the floor is what the link
     * can do and the spread is what jitter already describes.
     */
    private suspend fun measureLatency(
        network: Network?,
        server: Server,
        onProgress: (Progress) -> Unit,
    ): Pair<Int, Int>? {
        val samples = mutableListOf<Int>()
        repeat(LATENCY_SAMPLES) { index ->
            if (!currentCoroutineContext().isActive) return null
            val ms = timeToFirstByte(network, server.latencyUrl)
            if (ms != null && index > 0) samples += ms
            onProgress(Progress(Phase.Latency, 0.0, (index + 1f) / LATENCY_SAMPLES))
        }
        if (samples.isEmpty()) return null
        val ping = samples.min()
        val jitter = if (samples.size < 2) {
            0
        } else {
            samples.zipWithNext { a, b -> abs(a - b) }.average().toInt()
        }
        return ping to jitter
    }

    /** What one direction came to: a rate, or the reason there is none. */
    private data class Leg(val mbps: Double?, val note: String?)

    /**
     * One direction, measured over [STREAMS] parallel connections for [TRANSFER_WINDOW_MS].
     *
     * The shape is the same for download and upload, which is why they share this: start
     * N workers that move bytes into a shared counter, sample that counter on a clock for
     * the live readout, and compute the result from what moved **after** the ramp. The
     * ramp matters more here than anywhere else in the app: TCP slow start, TLS, and a
     * proxy's own session setup all land inside the first second or so, and including
     * them is how a fast link gets reported as a mediocre one.
     *
     * The live figure is deliberately not the raw rate of the last tick. A tick is a
     * third of a second, four streams start and finish bodies inside one, and a proxy
     * hands data over in bursts, so the raw series swings by a factor of several while
     * the link is perfectly steady — on screen that is a needle in convulsions and a
     * readout nobody can follow. [RateSmoother] turns it into the rate over the last
     * [SAMPLE_SPAN_MS], which is both calmer and the honest answer to "how fast is it
     * right now".
     */
    private suspend fun measureTransfer(
        network: Network?,
        server: Server,
        phase: Phase,
        onProgress: (Progress) -> Unit,
    ): Leg = coroutineScope {
        val moved = AtomicLong(0)
        val failure = AtomicReference<String?>(null)
        val fail: (String) -> Unit = { reason -> failure.compareAndSet(null, reason) }
        val started = System.nanoTime()
        val rampEnd = started + RAMP_MS * 1_000_000L
        val deadline = started + TRANSFER_WINDOW_MS * 1_000_000L
        // Bytes and time at the moment the ramp ended, captured by the sampler, which is
        // the only thing running on a clock.
        val rampMark = AtomicLong(-1)
        val rampMarkAt = AtomicLong(0)

        val sampler = launch {
            val smoother = RateSmoother()
            while (isActive && System.nanoTime() < deadline) {
                delay(SAMPLE_TICK_MS)
                val now = System.nanoTime()
                val total = moved.get()
                if (rampMark.get() < 0 && now >= rampEnd) {
                    rampMark.set(total)
                    rampMarkAt.set(now)
                }
                onProgress(
                    Progress(
                        phase = phase,
                        mbps = smoother.observe(now, total),
                        fraction = ((now - started).toFloat() /
                            (TRANSFER_WINDOW_MS * 1_000_000f)).coerceIn(0f, 1f),
                    ),
                )
            }
        }

        val workers = (0 until STREAMS).map {
            launch {
                var chunk = UPLOAD_FIRST_BODY_BYTES
                while (isActive && System.nanoTime() < deadline) {
                    val carried = when (phase) {
                        Phase.Download -> pumpDownload(network, server, deadline, moved, fail)
                        Phase.Upload -> pumpUpload(network, server, deadline, moved, chunk, fail)
                            .also { chunk = it.nextChunk }
                            .carried
                        Phase.Latency -> 0L
                    }
                    // A stream that could not carry anything is a dead end (refused,
                    // reset, no route): retrying it in a tight loop would spin for the
                    // whole window, so it stops and lets the others carry the test.
                    if (carried <= 0L) break
                }
            }
        }
        workers.joinAll()
        sampler.cancel()

        val total = moved.get()
        val mark = rampMark.get()
        val markAt = rampMarkAt.get()
        val now = System.nanoTime()
        val rate = when {
            // The normal case: rate over the steady-state part of the window.
            mark >= 0 && now > markAt && total > mark -> mbps(total - mark, now - markAt)
            // Too short or too slow to have a steady state; the whole transfer is the
            // honest answer rather than nothing.
            total > 0 -> mbps(total, now - started)
            else -> null
        }
        if (rate == null) {
            val note = failure.get() ?: NOTE_NOTHING
            Log.w(TAG, "$phase не измерен на ${server.host}: $note")
            Leg(null, note)
        } else {
            Leg(rate, null)
        }
    }

    /**
     * Pulls one sized body, adding to [moved] as it reads. Returns how much it carried.
     *
     * The request asks for far more than can arrive inside the window, so the transfer is
     * ended by the clock, not by the body running out; a worker only comes back here when
     * the server closed early, and then it opens the next one.
     */
    private fun pumpDownload(
        network: Network?,
        server: Server,
        deadline: Long,
        moved: AtomicLong,
        fail: (String) -> Unit,
    ): Long {
        var conn: HttpURLConnection? = null
        var carried = 0L
        return try {
            conn = open(network, server.downloadUrl(server.downloadChunkBytes), "GET")
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "приём: ${server.host} ответил $code")
                fail(NOTE_REFUSED)
                return 0
            }
            val buffer = ByteArray(BUFFER_BYTES)
            conn.inputStream.use { input ->
                while (System.nanoTime() < deadline) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    carried += read
                    moved.addAndGet(read.toLong())
                }
            }
            carried
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (carried <= 0L) fail(reasonOf(error))
            carried
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * Pushes bodies of a declared length until the deadline, adding to [moved] as it
     * writes. Returns how much this body carried.
     *
     * **Fixed length, not chunked.** This was measured against a live server, and the
     * difference is why «Отдача» used to be a dash: an Ookla server reads a
     * `Transfer-Encoding: chunked` body to its end before it answers, so a stream that
     * runs until a clock expires never gets a response at all — the request times out,
     * the code is never 2xx, and a direction that transferred tens of megabytes is
     * reported as unmeasurable. With `Content-Length` set, each body is acknowledged
     * (`200`, `size=<bytes>`) and the next one starts.
     *
     * **A body the clock cut short still counts.** The first version of this subtracted
     * every unacknowledged byte back out of the counter, which looks careful and is
     * wrong: bodies grew geometrically, so the last one — the one the deadline always
     * lands in — was as large as everything before it put together, and taking it back
     * could drive a worker's contribution to zero or below. A fast uplink measured as a
     * dash. Bytes written before our own clock cut the body did go out on the wire, give
     * or take a socket buffer, so they are kept; only a body the *server* rejected is
     * taken back, because those bytes are evidence about nothing.
     *
     * Each body is sized to the time left ([nextUploadSize]) rather than doubled blindly,
     * so the cut is small when it happens at all.
     */
    private fun pumpUpload(
        network: Network?,
        server: Server,
        deadline: Long,
        moved: AtomicLong,
        chunkBytes: Long,
        fail: (String) -> Unit,
    ): UploadOutcome {
        val size = chunkBytes.coerceIn(UPLOAD_MIN_BODY_BYTES, UPLOAD_MAX_BODY_BYTES)
        var conn: HttpURLConnection? = null
        var written = 0L
        var cutByClock = false
        return try {
            conn = open(network, server.uploadUrl, "POST").apply {
                doOutput = true
                setFixedLengthStreamingMode(size)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            val payload = ByteArray(BUFFER_BYTES) { (it * 31 + 7).toByte() }
            val startedAt = System.nanoTime()
            val out = conn.outputStream
            try {
                while (written < size) {
                    val slice = minOf(payload.size.toLong(), size - written).toInt()
                    out.write(payload, 0, slice)
                    written += slice
                    moved.addAndGet(slice.toLong())
                    if (System.nanoTime() >= deadline) {
                        cutByClock = true
                        break
                    }
                }
            } finally {
                // Closing a declared-length body short of its length throws, which is
                // expected once the clock has cut it; the bytes already written stand.
                runCatching { out.close() }
            }
            if (cutByClock) {
                // Asking for the response would block until the read timeout: the server
                // is still waiting for bytes that are never coming.
                return UploadOutcome(carried = written, nextChunk = size)
            }
            val elapsed = System.nanoTime() - startedAt
            val code = runCatching { conn.responseCode }.getOrDefault(-1)
            if (code in 200..299) {
                UploadOutcome(
                    carried = written,
                    nextChunk = nextUploadSize(size, elapsed, deadline - System.nanoTime()),
                )
            } else {
                Log.w(TAG, "отдача: ${server.host} ответил $code")
                fail(NOTE_REFUSED)
                // Those bytes are not evidence about the link: take them back out of the
                // counter so the reported rate stays honest.
                moved.addAndGet(-written)
                UploadOutcome(carried = 0, nextChunk = size)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (cutByClock) return UploadOutcome(carried = written, nextChunk = size)
            fail(reasonOf(error))
            moved.addAndGet(-written)
            UploadOutcome(carried = 0, nextChunk = size)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** What one upload body achieved, and how big the next one should be. */
    private data class UploadOutcome(val carried: Long, val nextChunk: Long)

    /** One small read over the real request shape, to catch a server that will not transfer. */
    private fun serves(network: Network?, server: Server): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(network, server.downloadUrl(server.downloadChunkBytes), "GET").apply {
                connectTimeout = VERIFY_TIMEOUT_MS
                readTimeout = VERIFY_TIMEOUT_MS
            }
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { it.read(ByteArray(BUFFER_BYTES)) > 0 }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun timeToFirstByte(network: Network?, url: String): Int? {
        val started = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = open(network, url, "GET").apply {
                connectTimeout = VERIFY_TIMEOUT_MS
                readTimeout = VERIFY_TIMEOUT_MS
            }
            if (conn.responseCode !in 200..299) return null
            // An empty 200 is a legal latency answer — LibreSpeed's empty.php is exactly
            // that — so the read is for the round trip, not for its result.
            conn.inputStream.use { it.read() }
            ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun fetchText(network: Network?, url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = open(network, url, "GET")
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * One connection, bound to [network] when the caller asked to bypass the tunnel.
     *
     * [Network.openConnection] is the documented way to pin a connection to a link: the
     * socket is created on that network's routes, so it never enters the tun. A null
     * network is the ordinary path — the tunnel, while one is up.
     *
     * The User-Agent is a browser's because the server-list endpoint requires one (it
     * answers 403 otherwise) and the measurement endpoints are indifferent.
     */
    private fun open(network: Network?, url: String, method: String): HttpURLConnection {
        val target = URL(url)
        val connection = if (network != null) {
            network.openConnection(target) as HttpURLConnection
        } else {
            target.openConnection() as HttpURLConnection
        }
        return connection.apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = method
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", BROWSER_UA)
            // No compression: a gzipped stream of a synthetic payload measures the
            // middlebox, not the link.
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    /** A failure in the words the screen can show. */
    private fun reasonOf(error: Throwable): String = when (error) {
        is SocketTimeoutException -> NOTE_NO_ANSWER
        is SSLException -> NOTE_TLS
        is UnknownHostException -> NOTE_DNS
        is ConnectException -> NOTE_BLOCKED
        else -> error.javaClass.simpleName
    }

    /** Non-negative: a negative query value is a needless thing to hand a server. */
    private fun nonce(): Int = Random.nextInt(Int.MAX_VALUE)

    private const val TAG = "SpeedTest"

    /**
     * Ookla's own server directory, as its web client calls it. Keyed off the caller's
     * address, ordered by distance, it refuses a non-browser User-Agent — and it is
     * unreachable from Russia, which is what [BUILT_IN_TARGETS] answers.
     */
    private const val SERVER_LIST_URL =
        "https://www.speedtest.net/api/js/servers?engine=js&https_functional=true"

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    /** How many servers to fetch, and how many of those to actually time. */
    private const val SERVER_LIST_LIMIT = 10
    private const val LATENCY_CANDIDATES = 4

    /**
     * Parallel connections per direction.
     *
     * Four, which is what it takes to fill a fast link: a single flow is bounded by its
     * own window and loss recovery, and through a proxy also by that proxy's per-stream
     * buffers. More than this stops helping and starts measuring the device's own ability
     * to schedule sockets.
     */
    private const val STREAMS = 4

    /** Asked-for size per download request, per protocol. See [Server.downloadChunkBytes]. */
    private const val OOKLA_CHUNK_BYTES = 100_000_000L

    /**
     * Cloudflare's `__down` answers 403 from `bytes=100000000` upwards — measured, with
     * `99999999` still served. Half of that leaves no doubt and costs nothing: a stream
     * that empties one body simply opens the next.
     */
    private const val CLOUDFLARE_CHUNK_BYTES = 50_000_000L

    /** LibreSpeed counts in MiB; a hundred of them is past what any window can drain. */
    private const val LIBRESPEED_CHUNK_BYTES = 100 * MIB

    /** How long each direction transfers for. */
    private const val TRANSFER_WINDOW_MS = 8_000L

    /** Excluded from the rate: slow start, TLS, and a proxy session coming up. */
    private const val RAMP_MS = 2_000L

    /** One live sample per this much time. ~3 needle updates a second. */
    private const val SAMPLE_TICK_MS = 300L

    private const val BUFFER_BYTES = 64 * 1024

    /** Six, of which the first is thrown away as the cold one. */
    private const val LATENCY_SAMPLES = 6

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** A probe answers or it loses; nothing is gained by waiting ten seconds for one. */
    private const val VERIFY_TIMEOUT_MS = 4_000
}

/**
 * How large the next upload body should be to finish inside the time left.
 *
 * Sized from the rate the last body actually achieved, so a 100-Мбит/с uplink gets bodies
 * that take about a second and a 2-Мбит/с one gets bodies it can finish. The alternative —
 * doubling until a ceiling — guarantees that the last body is the biggest and is the one
 * the clock interrupts, which is the case the counter used to handle worst.
 *
 * [UPLOAD_FILL] leaves a margin, because the rate is an estimate and overshooting it just
 * means another interrupted body.
 */
internal fun nextUploadSize(size: Long, elapsedNanos: Long, remainingNanos: Long): Long {
    if (elapsedNanos <= 0L || remainingNanos <= 0L) return size
    val bytesPerNano = size.toDouble() / elapsedNanos
    val want = (bytesPerNano * remainingNanos * UPLOAD_FILL).toLong()
    return want.coerceIn(UPLOAD_MIN_BODY_BYTES, UPLOAD_MAX_BODY_BYTES)
}

/**
 * A byte counter turned into a rate calm enough to put in front of someone.
 *
 * Two things are going on. The window: the rate is taken over the last [spanNanos] rather
 * than over the last tick, so one burst from a proxy, or one stream finishing a body,
 * moves it by a fraction instead of multiplying it. The weighting: each new reading is
 * blended into the previous one by [alpha], which takes out what is left of the jitter
 * without the lag of a longer window.
 *
 * Neither is cosmetic. The dial's needle and its 64-sp readout are driven by this, and a
 * raw per-tick rate makes both shake while the link is perfectly steady — the first thing
 * anyone noticed about the screen.
 */
internal class RateSmoother(
    private val spanNanos: Long = SAMPLE_SPAN_MS * 1_000_000L,
    private val alpha: Double = SMOOTHING_ALPHA,
) {
    private val times = ArrayDeque<Long>()
    private val totals = ArrayDeque<Long>()
    private var blended = Double.NaN

    /** Takes a cumulative byte count at a moment, gives back the smoothed rate in Мбит/с. */
    fun observe(nowNanos: Long, totalBytes: Long): Double {
        times.addLast(nowNanos)
        totals.addLast(totalBytes)
        // Keep one reading older than the span, so the window stays full rather than
        // collapsing onto the newest pair.
        while (times.size > 2 && nowNanos - times[1] >= spanNanos) {
            times.removeFirst()
            totals.removeFirst()
        }
        val raw = mbps(totalBytes - totals.first(), nowNanos - times.first())
        blended = if (blended.isNaN()) raw else blended + alpha * (raw - blended)
        return blended
    }

    /** The last smoothed value, or zero before anything was observed. */
    val value: Double get() = if (blended.isNaN()) 0.0 else blended
}

/** The rate is taken over this much of the recent past. */
internal const val SAMPLE_SPAN_MS = 1_200L

/** How much of each new reading is taken; the rest is what the needle already showed. */
internal const val SMOOTHING_ALPHA = 0.45

/** Megabits per second from bytes and nanoseconds. */
private fun mbps(bytes: Long, nanos: Long): Double {
    if (nanos <= 0 || bytes <= 0) return 0.0
    return bytes * 8.0 / (nanos / 1_000_000_000.0) / 1_000_000.0
}
