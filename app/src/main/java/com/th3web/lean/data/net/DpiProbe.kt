package com.th3web.lean.data.net

import android.content.Context
import android.net.Network
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Почему не работает»: what this provider is doing to the connection, measured rather
 * than guessed.
 *
 * ## Why the app has to answer this
 *
 * When a server does not work in RU, the user has one symptom ("не грузит") and five
 * possible causes, each with a different fix: the address is blocked outright, the TLS
 * handshake is killed by SNI, UDP is dropped so the QUIC protocols can never work, DNS
 * answers are forged, or the connection survives the handshake and is torn down once real
 * data flows (the TSPU signature). Support threads are mostly people guessing between
 * those. Every one of them is directly measurable, so this measures them.
 *
 * ## Everything here runs OFF the tunnel
 *
 * That is the point: these probes describe the carrier link, not the proxy. Each one binds
 * to the physical network under the VPN ([PhysicalNetwork]), so the diagnosis is the same
 * whether the user is connected or not — they do not have to disconnect to find out why
 * they needed to connect.
 *
 * Every probe is best-effort and returns a verdict rather than throwing; an inconclusive
 * result is reported as such, because "we could not tell" is an honest answer and a
 * confident wrong one costs the user an evening of changing settings at random.
 */
object DpiProbe {

    enum class Verdict {
        /** The thing works. */
        Clear,
        /** The thing is being interfered with, and the probe can say so. */
        Blocked,
        /** The probe ran but could not decide. */
        Unknown,
    }

    /**
     * One finding. [detail] is the evidence in one short Russian phrase, shown under the
     * verdict, because a verdict with no evidence is an opinion.
     */
    data class Finding(
        val id: String,
        val title: String,
        val verdict: Verdict,
        val detail: String,
    )

    /** The whole report, in the order the screen lists it. */
    data class Report(val findings: List<Finding>) {
        fun verdictOf(id: String): Verdict =
            findings.firstOrNull { it.id == id }?.verdict ?: Verdict.Unknown

        /** Does anything here call for a settings change? */
        val needsTweaks: Boolean
            get() = verdictOf(ID_TLS_SNI) == Verdict.Blocked ||
                verdictOf(ID_SUSTAINED) == Verdict.Blocked ||
                verdictOf(ID_DNS) == Verdict.Blocked
    }

    const val ID_TCP = "tcp"
    const val ID_TLS_SNI = "tls_sni"
    const val ID_UDP = "udp"
    const val ID_DNS = "dns"
    const val ID_SUSTAINED = "sustained"

    /**
     * Runs the five probes in order, reporting each as it finishes.
     *
     * Sequential, not parallel: several of them are about how the link behaves under
     * inspection, and running them at once would have them interfere with each other's
     * evidence (a DPI box that has just torn down a handshake treats the next flow from
     * the same address differently).
     */
    suspend fun run(
        context: Context,
        onFinding: (Finding) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) {
        val network = PhysicalNetwork.find(context)
        val findings = mutableListOf<Finding>()
        fun add(finding: Finding) {
            findings += finding
            onFinding(finding)
        }
        add(probeTcp(network))
        add(probeTlsSni(network))
        add(probeUdp(network))
        add(probeDns(network))
        add(probeSustained(network))
        Report(findings)
    }

    /**
     * Plain TCP 443 to a host nobody blocks.
     *
     * The control for everything below it: if this fails there is no internet to diagnose,
     * and every later "blocked" would be a lie about the provider.
     */
    private fun probeTcp(network: Network?): Finding {
        val ms = connectTcp(network, CONTROL_HOST, 443)
        return Finding(
            id = ID_TCP,
            title = "Выход в сеть",
            verdict = if (ms != null) Verdict.Clear else Verdict.Blocked,
            detail = if (ms != null) {
                "TCP 443 открывается за %d мс".format(ms)
            } else {
                "порт 443 не открывается даже до %s — проблема в самой сети".format(CONTROL_HOST)
            },
        )
    }

    /**
     * The SNI test, and the one that needs a control to mean anything.
     *
     * Two connections to a domain RU providers filter: a bare TCP connect to its port,
     * and a full TLS handshake with its name in the SNI. A box that filters by name lets
     * the first through and kills the second — which is precisely the signature, and the
     * reason a plain "port is open" check reports a blocked site as reachable.
     *
     * A handshake that fails while the TCP connect succeeded is therefore Blocked; both
     * failing is about the address, not the name, and says nothing about SNI.
     */
    private fun probeTlsSni(network: Network?): Finding {
        val tcp = connectTcp(network, SNI_TEST_HOST, 443)
        val tls = handshake(network, SNI_TEST_HOST)
        return when {
            tcp != null && tls -> Finding(
                ID_TLS_SNI,
                "Фильтр по имени сайта (SNI)",
                Verdict.Clear,
                "рукопожатие TLS с %s проходит".format(SNI_TEST_HOST),
            )
            tcp != null && !tls -> Finding(
                ID_TLS_SNI,
                "Фильтр по имени сайта (SNI)",
                Verdict.Blocked,
                "порт открыт, но TLS с именем %s обрывают — классический DPI".format(SNI_TEST_HOST),
            )
            else -> Finding(
                ID_TLS_SNI,
                "Фильтр по имени сайта (SNI)",
                Verdict.Unknown,
                "адрес %s недоступен целиком — по имени судить нельзя".format(SNI_TEST_HOST),
            )
        }
    }

    /**
     * Does UDP leave the device at all?
     *
     * This decides which protocols can ever work here: Hysteria2, TUIC and WireGuard are
     * UDP-only, so on a link that drops UDP they fail in a way no setting can fix, and
     * the user needs to be told that rather than left switching servers. Measured with a
     * real DNS query to a public resolver, because a valid query gets a valid answer —
     * a garbage datagram proves nothing when it goes unanswered.
     */
    private fun probeUdp(network: Network?): Finding {
        val answered = dnsQuery(network, UDP_RESOLVER, CONTROL_HOST) != null
        return Finding(
            id = ID_UDP,
            title = "UDP наружу",
            verdict = if (answered) Verdict.Clear else Verdict.Blocked,
            detail = if (answered) {
                "UDP работает — Hysteria2, TUIC и WireGuard поедут"
            } else {
                "UDP до %s не проходит — Hysteria2, TUIC и WireGuard здесь не поедут".format(UDP_RESOLVER)
            },
        )
    }

    /**
     * Is DNS being answered by someone else?
     *
     * The system resolver's answer for a filtered domain is compared with a public
     * resolver's over UDP. Providers that redirect or blackhole hand back an address of
     * their own, and a mismatch on a domain whose real hosting is stable is the tell.
     *
     * Deliberately cautious: CDNs legitimately answer differently per resolver, so a
     * mismatch alone is reported only for the test domain, whose answer is not
     * CDN-balanced, and anything inconclusive stays Unknown rather than becoming an
     * accusation.
     */
    private fun probeDns(network: Network?): Finding {
        val public = dnsQuery(network, UDP_RESOLVER, DNS_TEST_HOST)
        val system = runCatching {
            if (network != null) {
                network.getAllByName(DNS_TEST_HOST).map { it.hostAddress }
            } else {
                InetAddress.getAllByName(DNS_TEST_HOST).map { it.hostAddress }
            }
        }.getOrNull()?.filterNotNull().orEmpty()
        return when {
            public == null && system.isEmpty() -> Finding(
                ID_DNS,
                "Подмена DNS",
                Verdict.Unknown,
                "ни один резолвер не ответил",
            )
            public == null -> Finding(
                ID_DNS,
                "Подмена DNS",
                Verdict.Blocked,
                "публичный DNS по UDP не отвечает, остаётся только провайдерский",
            )
            system.isEmpty() -> Finding(
                ID_DNS,
                "Подмена DNS",
                Verdict.Blocked,
                "системный резолвер не отдаёт адрес для %s".format(DNS_TEST_HOST),
            )
            system.any { it in public } -> Finding(
                ID_DNS,
                "Подмена DNS",
                Verdict.Clear,
                "системный и публичный DNS согласны",
            )
            else -> Finding(
                ID_DNS,
                "Подмена DNS",
                Verdict.Blocked,
                "системный DNS отдаёт %s, публичный — %s".format(
                    system.first(),
                    public.first(),
                ),
            )
        }
    }

    /**
     * Does a connection survive real data?
     *
     * The RU-specific one. A handshake and a small request sail through, then the stream
     * is torn down once a couple of tens of kilobytes have flowed — which is why a server
     * can pass every quick check and still be useless. [ConnectionChecker.sustainedCheck]
     * already implements the measurement; this runs it on the bare link so the verdict is
     * about the provider rather than about the tunnel.
     */
    private suspend fun probeSustained(network: Network?): Finding {
        val result = sustainedOnPhysical(network)
        return when (result) {
            is ConnectionChecker.SustainedResult.Survived -> Finding(
                ID_SUSTAINED,
                "Обрыв на объёме (ТСПУ)",
                Verdict.Clear,
                "256 КБ прошли целиком, %d КБ/с".format(result.kbps),
            )
            is ConnectionChecker.SustainedResult.Torn -> Finding(
                ID_SUSTAINED,
                "Обрыв на объёме (ТСПУ)",
                Verdict.Blocked,
                "соединение оборвали после %d КБ — помогает фрагментация".format(result.bytes / 1024),
            )
            ConnectionChecker.SustainedResult.Failed -> Finding(
                ID_SUSTAINED,
                "Обрыв на объёме (ТСПУ)",
                Verdict.Unknown,
                "загрузка не началась — судить не о чем",
            )
        }
    }

    /**
     * [ConnectionChecker.sustainedCheck] against the physical network.
     *
     * It opens its own connection on the default route, which is the tunnel while one is
     * up, so for a verdict about the provider the same transfer is repeated here bound to
     * the link underneath. Short and duplicated rather than threaded through the checker's
     * public API, which belongs to the Home screen's own check and has no business
     * growing a network parameter for this.
     */
    private fun sustainedOnPhysical(network: Network?): ConnectionChecker.SustainedResult {
        val url = ConnectionChecker.stressUrl(ConnectionChecker.SUSTAINED_TARGET_BYTES)
        var conn: HttpURLConnection? = null
        return try {
            val target = URL(url)
            conn = (network?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
            conn.apply {
                connectTimeout = SUSTAINED_TIMEOUT_MS
                readTimeout = SUSTAINED_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", Http.userAgent)
                setRequestProperty("Accept-Encoding", "identity")
            }
            val code = conn.responseCode
            if (code != 200 && code != 206) return ConnectionChecker.SustainedResult.Failed
            val started = System.nanoTime()
            var total = 0
            val buffer = ByteArray(16 * 1024)
            val torn = try {
                conn.inputStream.use { input ->
                    while (total < ConnectionChecker.SUSTAINED_TARGET_BYTES) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                }
                false
            } catch (_: IOException) {
                true
            }
            val ms = ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
            when {
                total >= ConnectionChecker.SUSTAINED_TARGET_BYTES -> {
                    val kbps = ((total.toLong() * 1000L) / (ms.toLong() * 1024L)).toInt()
                    ConnectionChecker.SustainedResult.Survived(total, ms, kbps.coerceAtLeast(1))
                }
                total > 0 || torn -> ConnectionChecker.SustainedResult.Torn(total, ms)
                else -> ConnectionChecker.SustainedResult.Failed
            }
        } catch (_: Throwable) {
            ConnectionChecker.SustainedResult.Failed
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ---- primitives ----

    /** Milliseconds to an open TCP connection, or null when it will not open. */
    private fun connectTcp(network: Network?, host: String, port: Int): Int? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            network?.bindSocket(socket)
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * A full TLS handshake with [host] in the SNI, over a socket bound to [network].
     *
     * `startHandshake()` explicitly, rather than relying on the first write: the whole
     * question is whether the handshake itself survives, and the failure has to surface
     * here rather than inside a later read.
     */
    private fun handshake(network: Network?, host: String): Boolean {
        var plain: Socket? = null
        var tls: SSLSocket? = null
        return try {
            plain = Socket()
            network?.bindSocket(plain)
            plain.connect(InetSocketAddress(host, 443), CONNECT_TIMEOUT_MS)
            tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, host, 443, true) as SSLSocket
            tls.soTimeout = CONNECT_TIMEOUT_MS
            tls.startHandshake()
            true
        } catch (_: SSLException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { tls?.close() }
            runCatching { plain?.close() }
        }
    }

    /**
     * One A-record query over UDP, hand-rolled.
     *
     * A DNS packet is twelve bytes of header, the name in length-prefixed labels and four
     * bytes of question; the answer's addresses are the four-byte RDATA of each A record.
     * Writing it out is a few dozen lines and avoids a dependency, and it is the only way
     * to ask a specific resolver over a specific socket — which is the whole point here,
     * since the question is what a non-provider resolver says.
     *
     * Returns the addresses as strings, or null when nothing usable came back.
     */
    private fun dnsQuery(network: Network?, resolver: String, host: String): List<String>? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            network?.bindSocket(socket)
            socket.soTimeout = DNS_TIMEOUT_MS
            val query = buildDnsQuery(host)
            val address = InetAddress.getByName(resolver)
            socket.send(DatagramPacket(query, query.size, address, 53))
            val buffer = ByteArray(512)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            parseDnsAnswers(buffer, response.length).takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun buildDnsQuery(host: String): ByteArray {
        val labels = host.split('.').filter { it.isNotEmpty() }
        val body = ArrayList<Byte>(host.length + 18)
        // Header: a fixed id (nothing here multiplexes queries), standard query with
        // recursion desired, one question.
        body += byteArrayOf(0x4C, 0x4E, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00).toList()
        labels.forEach { label ->
            body += label.length.toByte()
            body += label.toByteArray(Charsets.US_ASCII).toList()
        }
        body += 0 // root label
        body += byteArrayOf(0x00, 0x01, 0x00, 0x01).toList() // QTYPE A, QCLASS IN
        return body.toByteArray()
    }

    /**
     * Pulls the A records out of a response.
     *
     * Names in the answer section are usually compressed pointers, so the parser walks
     * the records by length rather than trying to decode names: it only needs the type,
     * the class and the four bytes of an A record's RDATA.
     */
    private fun parseDnsAnswers(buffer: ByteArray, length: Int): List<String> {
        if (length < 12) return emptyList()
        fun u16(at: Int) = ((buffer[at].toInt() and 0xFF) shl 8) or (buffer[at + 1].toInt() and 0xFF)
        val questions = u16(4)
        val answers = u16(6)
        var cursor = 12
        // Skip the questions: a name in labels, then QTYPE and QCLASS.
        repeat(questions) {
            while (cursor < length && buffer[cursor] != 0.toByte()) {
                val len = buffer[cursor].toInt() and 0xFF
                if (len and 0xC0 == 0xC0) { cursor += 2; return@repeat }
                cursor += len + 1
            }
            cursor += 1 + 4
        }
        val addresses = mutableListOf<String>()
        repeat(answers) {
            if (cursor + 12 > length) return addresses
            // Name: a pointer (two bytes) or labels terminated by a zero.
            if (buffer[cursor].toInt() and 0xC0 == 0xC0) {
                cursor += 2
            } else {
                while (cursor < length && buffer[cursor] != 0.toByte()) {
                    cursor += (buffer[cursor].toInt() and 0xFF) + 1
                }
                cursor += 1
            }
            if (cursor + 10 > length) return addresses
            val type = u16(cursor)
            val rdLength = u16(cursor + 8)
            cursor += 10
            if (type == 1 && rdLength == 4 && cursor + 4 <= length) {
                addresses += (0..3).joinToString(".") { (buffer[cursor + it].toInt() and 0xFF).toString() }
            }
            cursor += rdLength
        }
        return addresses
    }

    /** Reachable from everywhere this app runs; the control for the rest. */
    private const val CONTROL_HOST = "www.google.com"

    /**
     * A domain RU providers filter by name while leaving its address reachable, which is
     * what makes it a usable SNI probe. Chosen for being filtered rather than for its
     * content; nothing is downloaded from it.
     */
    private const val SNI_TEST_HOST = "rutracker.org"

    /** Same domain, used for the DNS comparison: its hosting is stable, not CDN-balanced. */
    private const val DNS_TEST_HOST = "rutracker.org"

    /** Public resolver for the UDP and DNS probes. */
    private const val UDP_RESOLVER = "8.8.8.8"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val DNS_TIMEOUT_MS = 3_000
    private const val SUSTAINED_TIMEOUT_MS = 15_000
}
