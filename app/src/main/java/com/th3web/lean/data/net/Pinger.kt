package com.th3web.lean.data.net

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.th3web.lean.core.plugin.PluginSession
import com.th3web.lean.data.model.Outbound
import java.io.FileDescriptor
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val LINUX_SOCK_NONBLOCK = 0x800

/**
 * Measures server reachability/latency. Returns latency in ms, or -1 if
 * unreachable, matching [com.th3web.lean.data.model.Profile.latencyMs].
 *
 * The user-selected ping protocol decides how:
 *  - TCP (default): a warmed TCP connect to server:port (see [tcpLatency]). QUIC/UDP
 *    protocols (Hysteria2, Hysteria, TUIC, flagged by the caller as `udpService`)
 *    listen on UDP only, so a TCP connect to their port always fails; those go through
 *    [udpLatency], where any inbound packet including an ICMP error proves the host
 *    answered, and fall back to [icmpLatency].
 *  - ICMP: a rootless echo over an unprivileged datagram socket, falling back to TCP
 *    where the OEM restricts net.ipv4.ping_group_range.
 *  - GET/HEAD: a real HTTP round trip to the configured pingUrl, timed to the response
 *    status line. The overload without a URL falls back to TCP.
 *  - URL Test: the real thing GET/HEAD approximates, a round trip through the
 *    outbound's own protocol handshake, see [com.th3web.lean.core.UrlTestPinger].
 *    Needs [outbound] and [urlTestProbe]; without them it falls back to TCP/UDP.
 *
 * Every raw-socket probe accepts an optional [protect] hook, VpnService.protect(rawFd),
 * that keeps the probe off the tunnel, so the number is the RTT to the real server rather
 * than to the proxy egress. HTTP GET/HEAD stays on the tunnel.
 */
object Pinger {

    /** The stored `Settings.pingProtocol` value for "URL Test", shared so callers
     * that need to special-case it (e.g. exempting it from a VPN-active gate,
     * see HomeViewModel/ServersScreen) match [measure]'s own dispatch exactly. */
    const val URL_TEST_PROTOCOL = "URLTEST"

    // IP protocol numbers for the ICMP datagram socket. OsConstants doesn't
    // expose IPPROTO_ICMP/IPPROTO_ICMPV6, so we use the IANA-assigned values.
    private const val IPPROTO_ICMP = 1
    private const val IPPROTO_ICMPV6 = 58

    // ICMP echo message types (RFC 792 / RFC 4443).
    private const val ICMP_ECHO_REQUEST = 8
    private const val ICMP_ECHO_REPLY = 0
    private const val ICMPV6_ECHO_REQUEST = 128
    private const val ICMPV6_ECHO_REPLY = 129

    /**
     * URL-less entry point (per-server probe with no HTTP target). ICMP and TCP
     * behave as documented; GET/HEAD have no URL to hit here, so they fall back to
     * a TCP connect, the honest offline measurement. Use the [pingUrl] overload
     * to get a real HTTP GET/HEAD round-trip.
     */
    suspend fun measure(
        host: String,
        port: Int,
        protocol: String,
        timeoutMs: Int = 3000,
        protect: ((FileDescriptor) -> Unit)? = null,
    ): Int = measure(host, port, protocol, timeoutMs, pingUrl = null, protect = protect)

    /**
     * Full entry point.
     *
     * @param pingUrl target for GET/HEAD, timed to the response status line and falling
     *   back to a TCP connect when the request cannot complete. Ignored by ICMP and TCP.
     * @param udpService set by the caller (see [isUdpService]) for QUIC/UDP protocols. It
     *   redirects the TCP branch only, since a TCP connect to a UDP-only port is dead by
     *   construction.
     * @param protect VpnService.protect(rawFd), so a raw-socket probe escapes the tunnel
     *   while it is up. Null off-VPN, where it would be a no-op.
     * @param outbound the profile being probed, for "URL Test".
     * @param urlTestProbe the "URL Test" runner, injected as a lambda so this object stays
     *   testable on the JVM without the native core. Without it, "URL Test" falls back to
     *   the same TCP/UDP probe TCP uses.
     */
    suspend fun measure(
        host: String,
        port: Int,
        protocol: String,
        timeoutMs: Int = 3000,
        pingUrl: String?,
        udpService: Boolean = false,
        protect: ((FileDescriptor) -> Unit)? = null,
        outbound: Outbound? = null,
        urlTestProbe: (suspend (Outbound, String, Int) -> Int?)? = null,
    ): Int = when (protocol.uppercase()) {
        "ICMP" -> icmpLatency(host, port, timeoutMs, protect)
        "GET", "HEAD" -> {
            // through the server on the row, first and by preference.
            //
            // A plain HTTP round trip to pingUrl over the app's own network measures the
            // tunnel while connected, and the phone's own internet while not, handing the
            // same number to all sixty rows of a sweep. An option offered, rendered and
            // sorted on per row has to be about that row's server.
            //
            // So it goes through a probe instance for this outbound, the same way URL Test
            // does. The direct request stays as the fallback, and is the right answer
            // while connected, where the app's own traffic already rides the tunnel.
            val viaCore = if (
                outbound != null && supportsUrlTest(outbound) &&
                urlTestProbe != null && !pingUrl.isNullOrBlank()
            ) {
                urlTestProbe(outbound, pingUrl, timeoutMs)
            } else {
                null
            }
            if (viaCore == null && outbound != null && supportsUrlTest(outbound)) {
                PingState.countSubstitution()
            }
            viaCore ?: if (!pingUrl.isNullOrBlank()) {
                httpLatency(pingUrl, protocol.uppercase(), timeoutMs)
                    .takeIf { it >= 0 } ?: tcpLatency(host, port, timeoutMs, protect)
            } else {
                tcpLatency(host, port, timeoutMs, protect)
            }
        }
        "URLTEST" -> {
            // supportsUrlTest keeps WireGuard OUT of the probe: it has no "outbound" form
            // to build a test instance from, so the probe could never measure it.
            //
            // The probe answers null when it could not run at all, no core instance, a
            // config it refused. That says nothing about the server, so it falls through
            // to the raw probe below and the row shows a real number. Only a negative
            // number means the test ran and the server failed it. Conflating those two is
            // what made a probe that worked nowhere look like a list of dead servers.
            val viaCore = if (
                outbound != null && supportsUrlTest(outbound) &&
                urlTestProbe != null && !pingUrl.isNullOrBlank()
            ) {
                urlTestProbe(outbound, pingUrl, timeoutMs)
            } else {
                null
            }
            // Nothing ran, so whatever comes back next answers a different question than
            // the one the user asked. Counted, so the sweep can say so afterwards, the
            // alternative is a list that looks measured and is not. Only for nodes that
            // could have been tested: a protocol with no outbound form to build a probe
            // from was never going to be, and saying so every time would be noise.
            if (viaCore == null && outbound != null && supportsUrlTest(outbound)) {
                PingState.countSubstitution()
            }
            viaCore ?: if (udpService) {
                udpLatency(host, port, timeoutMs, protect).takeIf { it >= 0 }
                    ?: icmpLatency(host, port, timeoutMs, protect)
            } else {
                tcpLatency(host, port, timeoutMs, protect)
            }
        }
        else ->
            if (udpService) {
                // QUIC stacks silently drop garbage datagrams, so silence from
                // the UDP probe is common even when the service is healthy,
                // fall back to an ICMP echo for the path RTT to the host.
                udpLatency(host, port, timeoutMs, protect)
                    .takeIf { it >= 0 } ?: icmpLatency(host, port, timeoutMs, protect)
            } else {
                tcpLatency(host, port, timeoutMs, protect)
            }
    }

    /**
     * True when the server speaks only UDP, so a TCP connect to its port can never
     * succeed no matter how healthy it is. Callers pass the result as [measure]'s
     * `udpService`, which reroutes the TCP branch through [udpLatency] with an
     * [icmpLatency] fallback.
     *
     * WireGuard belongs here as much as the QUIC protocols do: it is UDP-only by design,
     * with no TCP listener to accept anything, so probing its port over TCP returns
     * unreachable every time rather than merely often.
     */
    fun isUdpService(o: Outbound): Boolean = when (o) {
        is Outbound.Hysteria2, is Outbound.Hysteria, is Outbound.Tuic, is Outbound.WireGuard -> true
        // These two are UDP or TCP depending on how they were configured, and guessing
        // wrong reproduces exactly the WireGuard bug described above: a TCP probe against
        // a UDP-only server is unreachable every time,.
        is Outbound.Naive -> o.proto.equals("quic", ignoreCase = true)
        is Outbound.Mieru -> o.transport.equals("UDP", ignoreCase = true)
        else -> false
    }

    /**
     * True when «URL Test» can actually measure this outbound.
     *
     * Two kinds of exception, for the same underlying reason, the probe builds a config
     * with one outbound in it, and these cannot be that outbound:
     *
     *  - WireGuard/AmneziaWG: sing-box models it as an "endpoint", not an "outbound".
     *  - Naive/Mieru/olcRTC, and the slice of VLESS that runs on Xray: the protocol lives
     *    in an external binary, and the core-side outbound is just a socks pointer at a
     *    helper process that only exists while a tunnel is up. A probe instance has no
     *    helper of its own, so it would measure a dead local port rather than the server.
     *    XHTTP nodes are the exception that proves it: they get a real protocol test, but
     *    from [com.th3web.lean.core.plugin.XrayUrlTest], which spawns the helper the core
     *    cannot be, see [com.th3web.lean.core.UrlTestPinger.measure]'s dispatch.
     *
     * Both fall back to the raw UDP/TCP probe, which measures the real server address and
     * is honest about what it is.
     *
     * Single source of truth: [com.th3web.lean.core.UrlTestPinger.supports] delegates here,
     * so the dispatch in [measure] and the probe itself can never disagree about which
     * outbounds are testable.
     */
    fun supportsUrlTest(o: Outbound): Boolean = when {
        o is Outbound.WireGuard -> false
        // Every VLESS node has a real protocol test: the ones the core speaks through a
        // headless core instance, and the xhttp/encrypted ones through a one-shot Xray
        // (XrayUrlTest). Which of the two is [UrlTestPinger.measure]'s business.
        o is Outbound.Vless -> true
        // The rest of the helper-backed protocols have no probe of their own yet, so a
        // test instance would measure a local port with nothing behind it.
        else -> !PluginSession.isPluginOutbound(o)
    }

    /**
     * Real HTTP latency: time from connect start to the response status line for a
     * GET or HEAD against [url]. Redirects are not followed and only a clean 204/200
     * counts as success: a generate_204-style probe must answer exactly 204 (or
     * 200); any 3xx redirect or other status is a captive-portal / DPI stub, not a
     * working path, and must read as a miss (mirrors [ConnectionChecker]). Any
     * non-204/200 status, or any I/O failure, returns -1 so the caller can fall
     * back to TCP. Never throws.
     */
    suspend fun httpLatency(url: String, method: String, timeoutMs: Int = 3000): Int =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = method
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", Http.userAgent)
                    setRequestProperty("Accept", "*/*")
                }
                val code = conn.responseCode
                if (code == 204 || code == 200) {
                    ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
                } else {
                    -1
                }
            } catch (e: Exception) {
                -1
            } finally {
                conn?.disconnect()
            }
        }

    /**
     * Rootless ICMP echo via an unprivileged datagram socket
     * (Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)). Works on non-rooted Android
     * when the app GID is inside net.ipv4.ping_group_range, the default on modern
     * Android (`0 2147483647`), and exactly how the system `ping` binary works
     * without CAP_NET_RAW / raw sockets.
     *
     * Returns RTT in ms, or -1 on timeout. If the kernel denies the ICMP socket
     * (a restrictive ping_group_range, rare), this gracefully falls back to
     * [tcpLatency] so the caller always gets a usable number.
     *
     * @param protect optional hook to call VpnService.protect(rawFd), so the probe
     *                escapes the tunnel and hits the real server. Pass null off-VPN.
     */
    suspend fun icmpLatency(
        host: String,
        port: Int,
        timeoutMs: Int,
        protect: ((FileDescriptor) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        var fd: FileDescriptor? = null
        try {
            val addr = withTimeoutOrNull(timeoutMs.toLong()) {
                runInterruptible { InetAddress.getByName(host) }
            } ?: return@withContext -1
            val isV6 = addr is Inet6Address
            val proto = if (isV6) IPPROTO_ICMPV6 else IPPROTO_ICMP
            val domain = if (isV6) OsConstants.AF_INET6 else OsConstants.AF_INET
            val echoType = if (isV6) ICMPV6_ECHO_REQUEST else ICMP_ECHO_REQUEST
            val expectReply = if (isV6) ICMPV6_ECHO_REPLY else ICMP_ECHO_REPLY

            // Open the unprivileged ICMP datagram socket. Throws ErrnoException
            // (EACCES) when ping_group_range excludes us → caught below → TCP.
            fd = Os.socket(domain, OsConstants.SOCK_DGRAM, proto)

            // When the tunnel is up, keep the probe off the VPN so it reaches the
            // real server (mirrors what the core does for its own outbounds).
            protect?.invoke(fd)

            // Identifier is informational only: on a SOCK_DGRAM ICMP socket the
            // kernel rewrites it to the socket's port. We match replies by sequence.
            val id = Process.myPid() and 0xFFFF
            val seq = 1
            val packet = buildEchoRequest(echoType, id, seq, isV6)

            val start = System.nanoTime()
            Os.sendto(fd, packet, 0, packet.size, 0, addr, 0)
            if (!awaitReadable(fd, timeoutMs)) return@withContext -1

            // For SOCK_DGRAM ICMP the kernel strips the IPv4 header, so buf[0] is
            // the ICMP type directly: there is no 20-byte IP header to skip.
            val buf = ByteArray(1500)
            val n = Os.recvfrom(fd, buf, 0, buf.size, 0, null)
            val rttMs = ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)

            // Validate: an echo reply of the right type with our sequence number.
            // Anything else (or a truncated read) is treated as no answer.
            val replyType = if (n >= 1) buf[0].toInt() and 0xFF else -1
            val replySeq = if (n >= 8) ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF) else -1
            if (n >= 8 && replyType == expectReply && replySeq == seq) rttMs else -1
        } catch (e: ErrnoException) {
            // EACCES => device's ping_group_range excludes us. Fall back to TCP so
            // the user still gets a latency number instead of a dead "ICMP" column.
            // (EAGAIN from a timed-out recvfrom also lands here, Android's
            // OsConstants has no EWOULDBLOCK; on Linux it equals EAGAIN anyway.)
            if (e.errno == OsConstants.EAGAIN) {
                -1
            } else {
                tcpLatency(host, port, timeoutMs, protect)
            }
        } catch (e: Exception) {
            -1
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    /**
     * Builds an ICMP / ICMPv6 echo request:
     *   type, code=0, checksum, identifier, sequence, payload.
     * The IPv4 checksum is computed here; for ICMPv6 it's left 0 because the kernel
     * fills it in (it needs the pseudo-header it alone has access to).
     */
    private fun buildEchoRequest(type: Int, id: Int, seq: Int, isV6: Boolean): ByteArray {
        val payload = ByteArray(32) { it.toByte() }
        val pkt = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        pkt.put(type.toByte())            // type: 8 (v4) / 128 (v6)
        pkt.put(0)                        // code
        pkt.putShort(0)                   // checksum placeholder
        pkt.putShort(id.toShort())        // identifier (kernel may overwrite)
        pkt.putShort(seq.toShort())       // sequence
        pkt.put(payload)
        val bytes = pkt.array()
        if (!isV6) {
            val csum = checksum(bytes)
            bytes[2] = ((csum ushr 8) and 0xFF).toByte()
            bytes[3] = (csum and 0xFF).toByte()
        }
        return bytes
    }

    /** Standard 16-bit one's-complement checksum over the whole ICMP message. */
    private fun checksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < data.size - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    /**
     * UDP reachability probe for QUIC/UDP services: open a CONNECTED datagram
     * socket to host:port, send a tiny payload and measure RTT to any inbound
     * event,
     *
     *  - an actual datagram back (some stacks answer even a garbage probe), or
     *  - an ICMP port-unreachable which the kernel, because the socket is
     *    connect()ed, surfaces on recvfrom() as ECONNREFUSED. That error packet
     *    travelled the full path back from the host, so its timing is a real RTT
     *    and the host is provably alive (just that the UDP port is closed).
     *    EHOSTUNREACH / ENETUNREACH (also surfaced here) mean the opposite, the
     *    host/network is unreachable, so they are treated as a miss, not alive.
     *
     * Built on raw [Os] syscalls (not java.net.DatagramSocket) for two reasons:
     *  1. the [protect] hook needs the underlying [FileDescriptor] so the probe
     *     can escape the VPN tunnel, and
     * 2. it lets us read the precise errno, so only a real reply or an
     * ICMP-error-as-recv-failure counts as a hit, a send/route failure
     *     (ENETUNREACH/EPERM on sendto) or a plain timeout (EAGAIN) is a miss.
     * Catching every IOException as "alive" (what the java.net path does) scores
     *     a local connect or route error as a positive RTT.
     *
     * Pure silence (the common case for a healthy QUIC listener, which silently
     * drops malformed initials) returns -1; [measure] then falls back to
     * [icmpLatency] for the path RTT. Never throws.
     *
     * @param protect optional hook to call VpnService.protect(rawFd), so the probe
     *                escapes the tunnel and hits the real server. Pass null off-VPN.
     */
    suspend fun udpLatency(
        host: String,
        port: Int,
        timeoutMs: Int = 3000,
        protect: ((FileDescriptor) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        var fd: FileDescriptor? = null
        try {
            val addr = withTimeoutOrNull(timeoutMs.toLong()) {
                runInterruptible { InetAddress.getByName(host) }
            } ?: return@withContext -1
            val isV6 = addr is Inet6Address
            val domain = if (isV6) OsConstants.AF_INET6 else OsConstants.AF_INET

            val sock = Os.socket(domain, OsConstants.SOCK_DGRAM, 0)
            fd = sock

            // Escape the tunnel before any traffic leaves the socket, so both the
            // datagram out and the returning ICMP error traverse the real stack.
            protect?.invoke(sock)

            // connect() pins the peer so the kernel reports that peer's ICMP
            // errors on this socket (an unconnected socket swallows them), the
            // whole point of the probe.
            Os.connect(sock, addr, port)

            val payload = ByteArray(8)
            val start = System.nanoTime()
            // A failed send (network down → ENETUNREACH, EPERM, …) throws here and
            // is caught below as a miss: only events after a successful send can
            // count as the host answering.
            Os.sendto(sock, payload, 0, payload.size, 0, addr, port)

            val buf = ByteArray(1500)
            if (!awaitReadable(sock, timeoutMs)) return@withContext -1
            try {
                Os.recvfrom(sock, buf, 0, buf.size, 0, null)
                // A datagram actually came back, the host answered.
                ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
            } catch (e: ErrnoException) {
                when (e.errno) {
                    // Timeout: nothing came back inside timeoutMs → miss.
                    OsConstants.EAGAIN -> -1
                    // ICMP port-unreachable: the host itself answered (just that the
                    // UDP port is closed) → it is provably alive, a real RTT.
                    OsConstants.ECONNREFUSED ->
                        ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
                    // EHOSTUNREACH / ENETUNREACH mean the host or its network is not
                    // reachable: that is the definition of a dead node, so it must
                    // be a miss, not a faked positive RTT. Anything else (EINTR,
                    // async close, …) is likewise not proof of a path round-trip.
                    else -> -1
                }
            }
        } catch (e: Exception) {
            -1
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    /**
     * TCP connect latency, warmed: a bare first connect to a "cold" destination can
     * eat a real ~1s SYN-retransmit RTO even though the server is perfectly healthy,
     * some stateful firewalls / anti-scan layers drop the very first SYN and only
     * pass traffic once a connection-tracking entry exists, so the first touch pays a
     * full retransmit timer while every touch after is instant. That is why a
     * server would show ~1400ms once and ~200ms on every manual re-ping: the user was
     * unknowingly warming the path with the first tap. This makes that automatic: a
     * successful first connect is immediately followed by a second, and the smaller
     * (warm) number is what gets reported, so one probe now reads the way a manual
     * re-ping already did, instead of a number that depends on whether this happens
     * to be the first touch since the process started.
     *
     * A first attempt that fails outright is returned as-is with no retry: a genuinely
     * dead server does not become reachable by trying again immediately, and retrying
     * would just double the worst-case wait for every truly unreachable row.
     *
     * @param protect optional hook to call VpnService.protect(rawFd), so the probe
     *                escapes the tunnel and hits the real server. Pass null off-VPN.
     */
    suspend fun tcpLatency(
        host: String,
        port: Int,
        timeoutMs: Int = 3000,
        protect: ((FileDescriptor) -> Unit)? = null,
    ): Int {
        val first = tcpConnectOnce(host, port, timeoutMs, protect)
        if (first < 0) return first
        val second = tcpConnectOnce(host, port, timeoutMs, protect)
        return warmResult(first, second)
    }

    /**
     * Picks the reported RTT out of a warm double-probe: the smaller of the two when
     * the second attempt also hit, otherwise the first (a first-hit-then-miss second
     * is very unlikely (momentary loss on an already-open path), but must not be
     * allowed to turn a working server into a reported miss). Pure and Os-free on
     * purpose: [tcpLatency] itself can't be exercised in a plain JVM unit test here
     * (`android.system.Os` throws unless running on-device or under Robolectric with
     * a real network sandbox, neither of which this module's tests use), so this is
     * the seam that stays testable.
     */
    @VisibleForTesting
    internal fun warmResult(first: Int, second: Int): Int =
        if (second in 0 until first) second else first

    private suspend fun tcpConnectOnce(
        host: String,
        port: Int,
        timeoutMs: Int,
        protect: ((FileDescriptor) -> Unit)?,
    ): Int = withContext(Dispatchers.IO) {
        var fd: FileDescriptor? = null
        try {
            val addr = withTimeoutOrNull(timeoutMs.toLong()) {
                runInterruptible { InetAddress.getByName(host) }
            } ?: return@withContext -1
            val isV6 = addr is Inet6Address
            val domain = if (isV6) OsConstants.AF_INET6 else OsConstants.AF_INET

            val sock = Os.socket(
                domain,
                OsConstants.SOCK_STREAM or LINUX_SOCK_NONBLOCK,
                0,
            )
            fd = sock

            // Escape the tunnel before connecting so the handshake hits the real
            // server, not the proxy egress.
            protect?.invoke(sock)

            // Non-blocking connect: connect() returns EINPROGRESS, then poll()
            // bounds the wait to timeoutMs and reports writability on completion.
            val start = System.nanoTime()
            val connectedImmediately =
                try {
                    Os.connect(sock, addr, port)
                    true // rare: completed synchronously (e.g. loopback)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINPROGRESS) {
                        false // expected: wait for writability below
                    } else {
                        throw e // ECONNREFUSED / ENETUNREACH / … → miss
                    }
                }

            if (!connectedImmediately) {
                val pfd = StructPollfd().apply {
                    this.fd = sock
                    events = OsConstants.POLLOUT.toShort()
                }
                val ready = Os.poll(arrayOf(pfd), timeoutMs)
                if (ready == 0) return@withContext -1 // poll timed out → unreachable

                // Distinguish a real connect from an async failure. android.system.Os
                // has no getsockoptInt(SO_ERROR), so re-issue connect(): on a completed
                // connection it throws EISCONN (success); any other errno
                // (ECONNREFUSED/ENETUNREACH/…) is the real async failure → miss.
                try {
                    Os.connect(sock, addr, port)
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.EISCONN) return@withContext -1
                }
            }

            ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            -1
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    private fun awaitReadable(fd: FileDescriptor, timeoutMs: Int): Boolean {
        val pollFd = StructPollfd().apply {
            this.fd = fd
            events = (OsConstants.POLLIN or OsConstants.POLLERR).toShort()
        }
        return Os.poll(arrayOf(pollFd), timeoutMs) > 0
    }
}
