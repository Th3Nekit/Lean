package com.th3web.lean.core.plugin

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.th3web.lean.data.model.Outbound

/**
 * Config files for the external protocol helpers, in each upstream's own format.
 *
 * Ported from the reference client's builders (`MieruFmt.kt`, `NaiveFmt.kt`) rather than
 * invented: these are third-party binaries whose config schema is theirs, and the reason
 * to match the reference exactly is that its shapes are the ones proven against real
 * servers.
 *
 * Every helper listens on a local SOCKS port ([localPort]), and reaches the internet only
 * through [mappedHost]:[mappedPort], an inbound owned by the core. See [PluginSession]
 * for why that indirection is what makes these protocols work at all. What the mapping is
 * differs by helper: a fixed redirect to the one server mieru and naive dial, and a SOCKS
 * endpoint for olcRTC and Xray, which each name their own destinations.
 */
internal object PluginConfig {

    private val json = kotlinx.serialization.json.Json { prettyPrint = true }

    fun forMieru(
        outbound: Outbound.Mieru,
        localPort: Int,
        mappedHost: String,
        mappedPort: Int,
    ): String {
        val config = buildJsonObject {
            put("activeProfile", "default")
            put("socks5Port", localPort)
            put("loggingLevel", "WARN")
            putJsonArray("profiles") {
                add(
                    buildJsonObject {
                        put("profileName", "default")
                        putJsonObject("user") {
                            put("name", outbound.username)
                            put("password", outbound.password)
                        }
                        putJsonArray("servers") {
                            add(
                                buildJsonObject {
                                    put("ipAddress", mappedHost)
                                    putJsonArray("portBindings") {
                                        add(
                                            buildJsonObject {
                                                put("port", mappedPort)
                                                put("protocol", transportOf(outbound))
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        // Upstream only reads mtu for the UDP transport; emitting it for
                        // TCP is harmless but the reference omits it, so we do too.
                        if (transportOf(outbound) == "UDP") put("mtu", outbound.mtu)
                    },
                )
            }
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), config)
    }

    private fun transportOf(outbound: Outbound.Mieru): String =
        if (outbound.transport.equals("UDP", ignoreCase = true)) "UDP" else "TCP"

    /**
     * naive's config.
     *
     * The subtle part is the pair of `proxy` and `host-resolver-rules`, and getting it
     * wrong silently defeats the protocol. The URI's hostname is what naive puts in the
     * TLS SNI, and it has to stay the real server name: a censor must see an ordinary
     * browser visiting an ordinary site, which is the entire premise. But the connection
     * itself has to land on the core's local mapping port, not on the real server.
     *
     * `host-resolver-rules` reconciles the two: naive resolves that real name to the
     * mapping address itself, so it presents genuine SNI while dialling locally. Without
     * the rule naive would resolve the real hostname for real and connect straight out,
     * unprotected, from inside our own tun, i.e. the loop that has no protect() to save
     * it (naive has no protect support at all).
     */
    fun forNaive(
        outbound: Outbound.Naive,
        localPort: Int,
        mappedHost: String,
        mappedPort: Int,
    ): String {
        // Which name goes in the URI, and whether it needs a resolver rule at all.
        // An explicit SNI wins; otherwise the server's own hostname is the name, unless
        // the "hostname" is a literal IP, in which case there is no name to map and no
        // SNI worth spoofing, so naive is pointed straight at the mapping address.
        val sniName = outbound.sni.ifBlank { outbound.server }
        val useName = outbound.sni.isNotBlank() || !isIpLiteral(outbound.server)
        val uriHost = if (useName) wrapIpv6(sniName) else wrapIpv6(mappedHost)

        val config = buildJsonObject {
            if (useName) put("host-resolver-rules", "MAP $sniName ${wrapIpv6(mappedHost)}")
            put("listen", "socks://$LOCALHOST:$localPort")
            put("proxy", proxyUri(outbound, uriHost, mappedPort))
            if (outbound.extraHeaders.isNotBlank()) {
                // naive wants CRLF-separated headers on one line.
                put("extra-headers", outbound.extraHeaders.split("\n").joinToString("\r\n"))
            }
            if (outbound.insecureConcurrency > 0) {
                put("insecure-concurrency", outbound.insecureConcurrency)
            }
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), config)
    }

    /**
     * olcRTC's client YAML.
     *
     * Two SOCKS blocks, doing opposite jobs, and getting them the right way round is the
     * whole trick:
     *
     *  - `socks.host` / `socks.port`, the listener the core dials, same as every other
     *    helper here.
     *  - `socks.proxy_addr` / `socks.proxy_port`, olcRTC's own egress. Unlike mieru and
     *    naive, this helper does not dial one fixed server: it joins a meeting on Jitsi /
     *    Telemost / WB Stream, so its destinations are whatever the provider resolves to.
     *    That rules out the host-mapping trick the other two use. Upstream threads this
     *    upstream-proxy setting into both halves of the client, the HTTP calls that issue
     *    the meeting credentials (`auth.Config`), and the WebRTC session itself
     *    (`engine.Config`, see pkg/olcrtc/olcrtc.go), so pointing it at the core's
     *    mapping inbound sends every byte it originates back out through a protected
     *    socket. Without it the helper's traffic would re-enter the tunnel it is providing.
     *
     * `mode: cnc` is the client role. `data` must exist because the provider picks a
     * plausible display name for the fake participant from the name lists in there.
     */
    fun forOlcrtc(
        outbound: Outbound.Olcrtc,
        localPort: Int,
        mappedHost: String,
        mappedPort: Int,
        dataDir: String,
    ): String = buildString {
        appendLine("mode: cnc")
        appendLine("auth:")
        appendLine("  provider: ${yaml(outbound.provider)}")
        appendLine("room:")
        appendLine("  id: ${yaml(outbound.roomId)}")
        appendLine("crypto:")
        appendLine("  key: ${yaml(outbound.key)}")
        appendLine("net:")
        appendLine("  transport: ${yaml(outbound.transport)}")
        // Its own resolver: the helper runs outside the core's DNS, and a name it cannot
        // resolve is a session that never starts.
        appendLine("  dns: ${yaml(OLCRTC_DNS)}")
        appendLine("socks:")
        appendLine("  host: ${yaml(LOCALHOST)}")
        appendLine("  port: $localPort")
        appendLine("  proxy_addr: ${yaml(mappedHost)}")
        appendLine("  proxy_port: $mappedPort")
        // Transport tuning arrives as flat `key=value` pairs from the share link; each
        // upstream key belongs under the block named by its prefix.
        olcrtcTuning(outbound).forEach { (block, entries) ->
            appendLine("$block:")
            entries.forEach { (key, value) -> appendLine("  $key: ${yaml(value)}") }
        }
        appendLine("data: ${yaml(dataDir)}")
        append("debug: false")
    }

    /**
     * Groups the link's payload into the YAML blocks upstream expects.
     *
     * The share notation flattens them (`vp8-fps`, `video-w`, `ack-ms`), and docs/uri.md
     * gives the mapping to `vp8.fps`, `video.width`, `sei.ack_timeout_ms` and so on.
     * Anything unrecognised is dropped rather than guessed at: a key olcrtc does not
     * know makes it reject the whole config, which would break the profile outright.
     */
    private fun olcrtcTuning(outbound: Outbound.Olcrtc): Map<String, Map<String, String>> {
        val grouped = linkedMapOf<String, MutableMap<String, String>>()
        outbound.options.forEach { (rawKey, value) ->
            val target = OLCRTC_OPTION_FIELDS[rawKey.lowercase()] ?: return@forEach
            val block = target.substringBefore('.')
            val field = target.substringAfter('.')
            grouped.getOrPut(block) { linkedMapOf() }[field] = value
        }
        return grouped
    }

    /** docs/uri.md's own table: link key -> `block.field` in the YAML. */
    private val OLCRTC_OPTION_FIELDS = mapOf(
        "vp8-fps" to "vp8.fps",
        "vp8-batch" to "vp8.batch_size",
        "fps" to "sei.fps",
        "batch" to "sei.batch_size",
        "frag" to "sei.fragment_size",
        "ack-ms" to "sei.ack_timeout_ms",
        "video-w" to "video.width",
        "video-h" to "video.height",
        "video-fps" to "video.fps",
        "video-bitrate" to "video.bitrate",
        "video-hw" to "video.hw",
        "video-codec" to "video.codec",
        "video-qr-size" to "video.qr_size",
        "video-qr-recovery" to "video.qr_recovery",
        "video-tile-module" to "video.tile_module",
        "video-tile-rs" to "video.tile_rs",
    )

    /**
     * Xray's config for one VLESS node the core cannot speak.
     *
     * ## Why the egress is wired differently from mieru and naive
     *
     * Those two dial exactly one server, so their mapping inbound can be a fixed redirect
     * and the helper can be lied to about the address. Xray cannot be lied to the same
     * way and should not be: XHTTP's `host`, TLS's `serverName` and Reality's own
     * `serverName` all default to the dial address, so rewriting that address to
     * 127.0.0.1 would silently put "127.0.0.1" in the SNI and the Host header, the two
     * fields a CDN routes on, and the two a censor looks at.
     *
     * So the real address stays in the config, and the redirection moves down a layer:
     * `sockopt.dialerProxy` sends every socket this outbound opens through a second,
     * local SOCKS outbound that lands on the core's mapping inbound. The core then
     * resolves and dials on a protected fd, the same escape every helper needs (see
     * [PluginSession]). This also covers connections we do not write ourselves, XHTTP's
     * separate download link, declared inside `extra.downloadSettings`, is threaded
     * through the same dialer below rather than being left to leak.
     *
     * [mappedHost]/[mappedPort] are therefore the SOCKS endpoint the core listens on, not
     * a stand-in for the server.
     */
    fun forXray(
        outbound: Outbound.Vless,
        localPort: Int,
        mappedHost: String,
        mappedPort: Int,
    ): String {
        val config = buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning") }
            putJsonArray("inbounds") {
                add(
                    buildJsonObject {
                        put("tag", "in")
                        put("listen", LOCALHOST)
                        put("port", localPort)
                        put("protocol", "socks")
                        putJsonObject("settings") {
                            put("auth", "noauth")
                            put("udp", true)
                            put("ip", LOCALHOST)
                        }
                        // Destination sniffing belongs to the core, which already did it
                        // before the request reached this port. Doing it twice only costs
                        // latency and can rewrite a destination the core set.
                        putJsonObject("sniffing") { put("enabled", false) }
                    },
                )
            }
            // Order is load-bearing: Xray sends untagged traffic to the first outbound,
            // and this config has no routing rules at all.
            putJsonArray("outbounds") {
                add(xrayVlessOutbound(outbound, XRAY_EGRESS_TAG))
                add(
                    buildJsonObject {
                        put("tag", XRAY_EGRESS_TAG)
                        put("protocol", "socks")
                        putJsonObject("settings") {
                            putJsonArray("servers") {
                                add(
                                    buildJsonObject {
                                        put("address", mappedHost)
                                        put("port", mappedPort)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), config)
    }

    /**
     * A throwaway Xray config for measuring one node, with no tunnel in the picture.
     *
     * Same shape as [forXray] minus the egress indirection: there is no core to loop back
     * through, so the outbound dials the network itself. That is what makes the number
     * honest, the probe speaks the node's real protocol over its real transport, so what
     * it times is the whole path (CDN edge, origin, handshake), not a TCP connect to
     * whatever answers first.
     *
     * Only valid while the tunnel is DOWN. A probe process runs as this app's UID with no
     * way to protect its sockets, so with a tun up it would measure itself; the ping UI
     * already refuses to run non-core probes in that state.
     */
    fun forXrayProbe(outbound: Outbound.Vless, localPort: Int): String {
        val config = buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning") }
            putJsonArray("inbounds") {
                add(
                    buildJsonObject {
                        put("tag", "in")
                        put("listen", LOCALHOST)
                        put("port", localPort)
                        put("protocol", "socks")
                        putJsonObject("settings") {
                            put("auth", "noauth")
                            put("udp", false)
                            put("ip", LOCALHOST)
                        }
                        putJsonObject("sniffing") { put("enabled", false) }
                    },
                )
            }
            putJsonArray("outbounds") { add(xrayVlessOutbound(outbound, egressTag = null)) }
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), config)
    }

    private fun xrayVlessOutbound(
        o: Outbound.Vless,
        egressTag: String?,
    ): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            put("tag", "proxy")
            put("protocol", "vless")
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    add(
                        buildJsonObject {
                            put("address", o.server)
                            put("port", o.serverPort)
                            putJsonArray("users") {
                                add(
                                    buildJsonObject {
                                        put("id", o.uuid)
                                        put("encryption", o.encryption.ifBlank { "none" })
                                        // XTLS Vision rides on raw TCP and is rejected
                                        // outright on top of a stream transport, so the
                                        // flow only survives when there is no transport.
                                        if (o.flow.isNotBlank() && xrayNetwork(o) == "tcp") {
                                            put("flow", o.flow)
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
            put("streamSettings", xrayStreamSettings(o, egressTag))
        }

    private fun xrayStreamSettings(
        o: Outbound.Vless,
        egressTag: String?,
    ): kotlinx.serialization.json.JsonObject {
        val network = xrayNetwork(o)
        val tls = o.tls
        val security = when {
            tls == null || !tls.enabled -> "none"
            tls.reality != null -> "reality"
            else -> "tls"
        }
        // What the server is known by. Blank means the link carried no SNI, and the
        // server's own hostname is then the only honest answer, never the dial address,
        // which sockopt.dialerProxy has moved to a local socket.
        val serverName = tls?.serverName?.ifBlank { o.server }.orEmpty()
        return buildJsonObject {
            put("network", network)
            put("security", security)
            when (security) {
                "tls" -> putJsonObject("tlsSettings") {
                    put("serverName", serverName)
                    put("allowInsecure", tls?.insecure == true)
                    tls?.utlsFingerprint?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
                    tls?.alpn?.takeIf { it.isNotEmpty() }?.let { list ->
                        putJsonArray("alpn") { list.forEach { add(it) } }
                    }
                }
                "reality" -> putJsonObject("realitySettings") {
                    put("serverName", serverName)
                    put("publicKey", tls?.reality?.publicKey.orEmpty())
                    put("shortId", tls?.reality?.shortId.orEmpty())
                    put("spiderX", tls?.reality?.spiderX.orEmpty())
                    // Reality is a uTLS handshake: a fingerprint is not optional, and a
                    // blank one makes Xray refuse the outbound at load. Chrome is the
                    // value every panel emits when it emits one at all.
                    put("fingerprint", tls?.utlsFingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
                }
            }
            when (network) {
                "xhttp" -> put("xhttpSettings", xhttpSettings(o, egressTag))
                "ws" -> putJsonObject("wsSettings") {
                    put("path", o.transport?.path.orEmpty().ifEmpty { "/" })
                    o.transport?.host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                }
                "httpupgrade" -> putJsonObject("httpupgradeSettings") {
                    put("path", o.transport?.path.orEmpty().ifEmpty { "/" })
                    o.transport?.host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                }
                "grpc" -> putJsonObject("grpcSettings") {
                    put("serviceName", o.transport?.serviceName.orEmpty())
                }
                "h2" -> putJsonObject("httpSettings") {
                    put("path", o.transport?.path.orEmpty().ifEmpty { "/" })
                    o.transport?.host?.takeIf { it.isNotBlank() }?.let {
                        putJsonArray("host") { add(it) }
                    }
                }
            }
            // No tag means dial the network directly, the probe case, where no
            // tunnel exists to escape from and nothing is listening on a mapping port.
            if (egressTag != null) putJsonObject("sockopt") { put("dialerProxy", egressTag) }
        }
    }

    /**
     * `xhttpSettings`, with the link's opaque `extra` object merged over the top.
     *
     * `extra` is Xray's own schema (padding sizes, post intervals, xmux, downloadSettings)
     * and panels hand it over verbatim, so it is merged rather than modelled, a key this
     * app has never heard of is the case that has to keep working. Unparseable
     * JSON is dropped instead of failing the connect: the defaults are what most nodes run
     * anyway, and a node that starts is worth more than one that refuses over tuning.
     */
    private fun xhttpSettings(
        o: Outbound.Vless,
        egressTag: String?,
    ): kotlinx.serialization.json.JsonObject {
        val t = o.transport
        val host = t?.host?.takeIf { it.isNotBlank() }
            ?: o.tls?.serverName?.takeIf { it.isNotBlank() }
            ?: o.server
        val base = buildJsonObject {
            put("path", t?.path.orEmpty().ifEmpty { "/" })
            put("host", host)
            t?.mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
        }
        val extra = t?.extra?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject }
                .getOrNull()
        } ?: return base
        return buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            extra.forEach { (key, value) ->
                // The download half of XHTTP opens its own sockets from its own stream
                // settings, so it needs the same dialer or it would leave through the tun
                // it is helping to provide.
                if (egressTag != null && key == "downloadSettings" &&
                    value is kotlinx.serialization.json.JsonObject
                ) {
                    put(key, withEgressDialer(value, egressTag))
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun withEgressDialer(
        settings: kotlinx.serialization.json.JsonObject,
        egressTag: String,
    ): kotlinx.serialization.json.JsonObject = buildJsonObject {
        settings.forEach { (key, value) -> if (key != "sockopt") put(key, value) }
        putJsonObject("sockopt") {
            (settings["sockopt"] as? kotlinx.serialization.json.JsonObject)
                ?.forEach { (key, value) -> if (key != "dialerProxy") put(key, value) }
            put("dialerProxy", egressTag)
        }
    }

    /** The transport name in Xray's spelling; "tcp" when the node carries none. */
    private fun xrayNetwork(o: Outbound.Vless): String = when (o.transport?.type?.lowercase()) {
        "xhttp", "splithttp" -> "xhttp"
        "ws" -> "ws"
        "httpupgrade" -> "httpupgrade"
        "grpc" -> "grpc"
        "http", "h2" -> "h2"
        else -> "tcp"
    }

    /**
     * Double-quoted with the few characters YAML would otherwise read as syntax escaped.
     * Room ids are URLs and keys are hex, so quoting is what keeps `https://…` from being
     * parsed as a comment or a mapping.
     */
    private fun yaml(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun proxyUri(outbound: Outbound.Naive, host: String, port: Int): String {
        val scheme = if (outbound.proto.equals("quic", ignoreCase = true)) "quic" else "https"
        val credentials = buildString {
            if (outbound.username.isNotBlank()) {
                append(encode(outbound.username))
                if (outbound.password.isNotBlank()) append(':').append(encode(outbound.password))
                append('@')
            }
        }
        return "$scheme://$credentials$host:$port"
    }

    /** A bare IPv6 literal needs brackets before it can carry a port in a URI. */
    private fun wrapIpv6(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private fun isIpLiteral(host: String): Boolean {
        if (host.isBlank()) return false
        if (host.contains(':')) return true // IPv6
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    const val LOCALHOST = "127.0.0.1"

    /**
     * Resolver for olcRTC's own lookups. A plain public IP:port because the helper is a
     * separate process with no access to the core's DNS, and this address is dialled
     * through the mapping inbound like everything else it originates.
     */
    private const val OLCRTC_DNS = "8.8.8.8:53"

    /**
     * The tag of Xray's local SOCKS outbound onto the core's mapping inbound. Named, not
     * inlined, because it appears in three places (the outbound itself, the proxy's
     * sockopt, and the download half's), and a typo in any of them is a silent leak.
     */
    private const val XRAY_EGRESS_TAG = "core-egress"
}
