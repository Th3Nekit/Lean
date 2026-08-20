package com.th3web.lean.data.parse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.th3web.lean.data.Serialization
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.RealitySettings
import com.th3web.lean.data.model.TlsSettings
import com.th3web.lean.data.model.TransportSettings

/**
 * Parses Xray/V2Ray JSON subscription bodies into [Profile]s. Some gating
 * panels serve this format (a JSON array of full client configs) to v2rayNG-
 * family user agents instead of the base64 share-link list, and it is the
 * only variant that carries their Hysteria2 servers, expressed in Xray's
 * schema (protocol "hysteria" + streamSettings.hysteriaSettings.version 2).
 *
 * Accepted shapes:
 * - a top-level array of config objects;
 *  - a single config object ({ remarks, outbounds: [...], dns, routing, … });
 *  - a single bare outbound object ({ protocol, settings, streamSettings }).
 *
 * Best-effort like [ShareLinks]: malformed elements are skipped, never fatal.
 */
object XrayConfig {

    /** Real proxy protocols; freedom/blackhole/dns/selector/urltest are routing plumbing. */
    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria")

    /** Xray-only stream transports Lean can't run on sing-box (see [ShareLinks]). */
    private val UNSUPPORTED_NETWORKS = setOf("splithttp", "xhttp", "kcp", "quic")

    /**
     * The subset of the above that VLESS can still import, because VLESS is the one
     * protocol with a path out of the core: an xhttp node runs in the bundled Xray
     * helper instead. VMess and Trojan have no such path and still drop.
     */
    private val XHTTP_NETWORKS = setOf("xhttp", "splithttp")

    fun parse(body: String): List<Profile> {
        val root = runCatching {
            Serialization.json.parseToJsonElement(body.trim())
        }.getOrNull() ?: return emptyList()
        val configs: List<JsonObject> = when (root) {
            is JsonArray -> root.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        // Dedupe by outbound value-equality. Panels emit "balancer" configs that
        // list every server under one generic remarks ("Автовыбор"/"АНТИБЛОК")
        // Plus a dedicated single-proxy config per server with its real display
        // name, when the same outbound appears in both, the dedicated config's
        // name wins. LinkedHashMap keeps first-seen order across the replace.
        val byOutbound = LinkedHashMap<Outbound, Named>()
        for (config in configs) {
            val outbounds = config.arr("outbounds")?.filterIsInstance<JsonObject>()
                // Bare outbound: the element itself carries protocol/settings.
                ?: if (config.string("protocol") != null) listOf(config) else emptyList()
            val proxies = outbounds.filter { it.string("protocol")?.lowercase() in PROXY_PROTOCOLS }
            val remarks = config.string("remarks")?.trim()?.takeIf { it.isNotEmpty() }
            val solo = proxies.size == 1
            for (p in proxies) {
                val outbound = mapOutbound(p) ?: continue
                val name = remarks
                    ?: p.string("tag")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "${outbound.server}:${outbound.serverPort}"
                val existing = byOutbound[outbound]
                if (existing == null || (solo && !existing.fromSoloConfig)) {
                    byOutbound[outbound] = Named(Profile(name = name, outbound = outbound), solo)
                }
            }
        }
        return byOutbound.values.map { it.profile }
    }

    private data class Named(val profile: Profile, val fromSoloConfig: Boolean)

    // ---- per-protocol outbound mapping ----

    private fun mapOutbound(o: JsonObject): Outbound? = runCatching {
        val stream = o.obj("streamSettings")
        when (o.string("protocol")?.lowercase()) {
            "vless" -> mapVless(o, stream)
            "vmess" -> mapVmess(o, stream)
            "trojan" -> mapTrojan(o, stream)
            "shadowsocks" -> mapShadowsocks(o)
            "hysteria" -> mapHysteria(o, stream)
            else -> null
        }
    }.getOrNull()

    private fun mapVless(o: JsonObject, stream: JsonObject?): Outbound? {
        val vnext = o.obj("settings")?.arr("vnext")?.firstObj() ?: return null
        val user = vnext.arr("users")?.firstObj() ?: return null
        val network = (stream?.string("network") ?: "tcp").lowercase()
        if (network in UNSUPPORTED_NETWORKS && network !in XHTTP_NETWORKS) return null
        // "VLESS encryption" (e.g. "mlkem768x25519…") is a real wire feature the pinned
        // sing-box cannot decode, so building it in the core would negotiate and then
        // decrypt to garbage. The node is kept rather than dropped: the Xray helper
        // speaks it, and the same gate that sends an xhttp node there sends this one too.
        // Empty or absent encryption is the normal "none" case and stays in the core.
        val encryption = user.string("encryption").orEmpty().ifBlank { "none" }
        return Outbound.Vless(
            server = vnext.string("address") ?: return null,
            serverPort = vnext.port("port") ?: return null,
            uuid = user.string("id") ?: return null,
            flow = user.string("flow").orEmpty(),
            network = network,
            encryption = encryption,
            tls = tlsFrom(stream),
            transport = transportFrom(network, stream),
        )
    }

    private fun mapVmess(o: JsonObject, stream: JsonObject?): Outbound? {
        val vnext = o.obj("settings")?.arr("vnext")?.firstObj() ?: return null
        val user = vnext.arr("users")?.firstObj() ?: return null
        val network = (stream?.string("network") ?: "tcp").lowercase()
        if (network in UNSUPPORTED_NETWORKS) return null
        return Outbound.Vmess(
            server = vnext.string("address") ?: return null,
            serverPort = vnext.port("port") ?: return null,
            uuid = user.string("id") ?: return null,
            alterId = user.int("alterId") ?: 0,
            security = user.string("security") ?: "auto",
            network = network,
            tls = tlsFrom(stream),
            transport = transportFrom(network, stream),
        )
    }

    private fun mapTrojan(o: JsonObject, stream: JsonObject?): Outbound? {
        val server = o.obj("settings")?.arr("servers")?.firstObj() ?: return null
        val network = (stream?.string("network") ?: "tcp").lowercase()
        if (network in UNSUPPORTED_NETWORKS) return null
        return Outbound.Trojan(
            server = server.string("address") ?: return null,
            serverPort = server.port("port") ?: return null,
            password = server.string("password") ?: return null,
            network = network,
            tls = tlsFrom(stream),
            transport = transportFrom(network, stream),
        )
    }

    private fun mapShadowsocks(o: JsonObject): Outbound? {
        val server = o.obj("settings")?.arr("servers")?.firstObj() ?: return null
        val method = server.string("method") ?: return null
        val password = server.string("password").orEmpty()
        // Same guard as ShareLinks.parseShadowsocks, with the same exact predicate:
        // sing-box implements Shadowsocks-2022 EIH (multi-user key "iPSK:uPSK") only
        // for AES ciphers; a non-AES 2022 method carrying such a key aborts the whole
        // instance. Fire only on a genuine multi-segment EIH key (2+ parts that each
        // base64-decode to a 16/32-byte PSK), so a single-PSK chacha20 password that
        // merely contains an incidental ':' is not dropped. (Was an over-broad
        // `password.contains(':')` that killed legitimate single-PSK nodes.)
        method.lowercase().let { m ->
            if (m.startsWith("2022-blake3-") && "aes" !in m) {
                val parts = password.split(':')
                val isEih = parts.size >= 2 && parts.all {
                    decodeBase64TolerantBytes(it)?.size.let { n -> n == 16 || n == 32 }
                }
                if (isEih) return null
            }
        }
        return Outbound.Shadowsocks(
            server = server.string("address") ?: return null,
            serverPort = server.port("port") ?: return null,
            method = method,
            password = password,
        )
    }

    /**
     * Xray-schema Hysteria: settings { address, port, version } +
     * streamSettings.hysteriaSettings { version, auth }. version 1 maps to
     * Lean's [Outbound.Hysteria]; everything else (2 or unspecified, the
     * panels that emit this format run Hysteria2) maps to [Outbound.Hysteria2].
     * The extra finalmask/quicParams tuning block has no sing-box slot, dropped.
     */
    private fun mapHysteria(o: JsonObject, stream: JsonObject?): Outbound? {
        val settings = o.obj("settings") ?: return null
        val hy = stream?.obj("hysteriaSettings")
        val address = settings.string("address") ?: return null
        val port = settings.port("port") ?: return null
        val version = hy?.int("version") ?: settings.int("version") ?: 2
        // SNI can live under tlsSettings, realitySettings (some panels set the
        // hysteria node's security:"reality"), or settings.sni. Falling back
        // across all three avoids an empty server_name → SingBoxConfig then
        // defaulting SNI to the bare host → QUIC cert mismatch → no traffic.
        val tlsObj = stream?.obj("tlsSettings") ?: stream?.obj("realitySettings")
        val sni = (tlsObj?.string("serverName")
            ?: settings.string("sni")
            ?: hy?.string("sni")).orEmpty()
        val tls = TlsSettings(
            enabled = true,
            serverName = sni,
            insecure = tlsObj?.bool("allowInsecure") == true || settings.bool("allowInsecure"),
            // hysteria2 requires ALPN h3; default it when the panel omits alpn. not for
            // hysteria v1, whose ALPN is "hysteria", forcing h3 on it made every v1 node
            // from an Xray-JSON subscription fail its QUIC handshake. Left empty there so
            // the core supplies its own per-protocol default.
            alpn = (tlsObj?.stringList("alpn") ?: stream?.stringList("alpn")).orEmpty()
                .ifEmpty { if (version == 1) emptyList() else listOf("h3") },
            // Never persist a uTLS fingerprint on a QUIC protocol: uTLS is
            // unsupported over QUIC and would poison every dial. SingBoxConfig's
            // tls(quic=true) already force-drops it, but keeping the model clean
            // means it can't leak regardless of how that layer evolves.
            utlsFingerprint = "",
        )
        if (version == 1) {
            return Outbound.Hysteria(
                server = address,
                serverPort = port,
                authStr = hy?.string("auth").orEmpty(),
                upMbps = hy?.int("upMbps") ?: hy?.int("up") ?: 0,
                downMbps = hy?.int("downMbps") ?: hy?.int("down") ?: 0,
                obfs = hy?.string("obfs").orEmpty(),
                tls = tls,
            )
        }
        // hy2 obfs (if a panel ever sends it here): nested { type, password } or
        // a flat obfsParam. Same degrade rule as ShareLinks: no password → no obfs.
        val obfs = hy?.obj("obfs")
        val obfsPassword = obfs?.string("password") ?: hy?.string("obfsParam") ?: ""
        return Outbound.Hysteria2(
            server = address,
            serverPort = port,
            password = hy?.string("auth").orEmpty(),
            obfsType = if (obfsPassword.isEmpty()) "" else (obfs?.string("type") ?: "salamander"),
            obfsPassword = obfsPassword,
            tls = tls,
        )
    }

    // ---- streamSettings → TlsSettings / TransportSettings ----

    private fun tlsFrom(stream: JsonObject?): TlsSettings? {
        stream ?: return null
        return when (stream.string("security")?.lowercase()) {
            "reality" -> {
                val r = stream.obj("realitySettings")
                // Newer Xray / newer panels renamed the REALITY client public key
                // field "publicKey" -> "password"; read both. If neither is
                // present `reality` stays null and the node would silently degrade
                // to plain TLS against a borrowed SNI (cert check fails, zero
                // traffic), so the fallback is load-bearing, not cosmetic.
                val pbk = (r?.string("publicKey") ?: r?.string("password")).orEmpty()
                TlsSettings(
                    enabled = true,
                    serverName = r?.string("serverName").orEmpty(),
                    // A REALITY node that pins an ALPN (e.g. ["h2"]) must carry it
                    // through, else sing-box negotiates a default the server rejects.
                    alpn = r?.stringList("alpn").orEmpty(),
                    utlsFingerprint = r?.string("fingerprint").orEmpty(),
                    reality = pbk.takeIf { it.isNotEmpty() }?.let {
                        RealitySettings(
                            it,
                            r?.string("shortId").orEmpty(),
                            r?.string("spiderX").orEmpty(),
                        )
                    },
                )
            }
            "tls", "xtls" -> {
                val t = stream.obj("tlsSettings") ?: stream.obj("xtlsSettings")
                TlsSettings(
                    enabled = true,
                    serverName = t?.string("serverName").orEmpty(),
                    insecure = t?.bool("allowInsecure") == true,
                    alpn = t?.stringList("alpn").orEmpty(),
                    utlsFingerprint = t?.string("fingerprint").orEmpty(),
                )
            }
            else -> null
        }
    }

    private fun transportFrom(network: String, stream: JsonObject?): TransportSettings? {
        stream ?: return null
        return when (network) {
            "ws" -> {
                val ws = stream.obj("wsSettings")
                // Xray's canonical Host location is wsSettings.headers.Host; the
                // flat wsSettings.host is the newer/rarer form. When a panel sets
                // both (header = real CDN domain, flat = stale), preferring the
                // header avoids sending the wrong Host/SNI to the CDN → 404/no
                // traffic on ws+tls nodes.
                val host = ws?.obj("headers")?.string("Host")
                    ?: ws?.obj("headers")?.string("host")
                    ?: ws?.string("host")
                TransportSettings(
                    "ws",
                    path = ws?.string("path").orEmpty().ifEmpty { "/" },
                    host = host.orEmpty(),
                )
            }
            "httpupgrade" -> {
                val hu = stream.obj("httpupgradeSettings")
                TransportSettings(
                    "httpupgrade",
                    path = hu?.string("path").orEmpty().ifEmpty { "/" },
                    host = hu?.string("host").orEmpty(),
                )
            }
            "grpc" -> TransportSettings(
                "grpc",
                serviceName = stream.obj("grpcSettings")?.string("serviceName").orEmpty(),
            )
            "http", "h2" -> {
                val h = stream.obj("httpSettings")
                TransportSettings(
                    "http",
                    path = h?.string("path").orEmpty().ifEmpty { "/" },
                    // Xray httpSettings.host is an array; single string tolerated.
                    host = (h?.stringList("host")?.firstOrNull() ?: h?.string("host")).orEmpty(),
                )
            }
            "xhttp", "splithttp" -> {
                val x = stream.obj("xhttpSettings") ?: stream.obj("splithttpSettings")
                TransportSettings(
                    ShareLinks.XHTTP,
                    path = x?.string("path").orEmpty().ifEmpty { "/" },
                    host = x?.string("host").orEmpty(),
                    mode = x?.string("mode").orEmpty(),
                    // Everything else in the block is Xray's own tuning, and it is handed
                    // back to Xray unread, the same contract the share link's `extra`
                    // has. Re-serialised rather than modelled so a key this app has never
                    // heard of survives the import.
                    extra = x?.let { block ->
                        val rest = block.filterKeys { it !in XHTTP_MODELLED_KEYS }
                        if (rest.isEmpty()) "" else JsonObject(rest).toString()
                    }.orEmpty(),
                )
            }
            else -> null // tcp / raw / hysteria: no stream transport
        }
    }

    /** The `xhttpSettings` keys [transportFrom] reads into fields of its own. */
    private val XHTTP_MODELLED_KEYS = setOf("path", "host", "mode")

    // ---- tolerant JsonObject accessors (panels mix string/number primitives) ----

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

    /**
     * A server port read from an Xray config, bounded to 1..65535. An out-of-range
     * value (crafted/typo'd panel JSON, e.g. port 99999) is treated as absent so the
     * node is dropped rather than building an impossible server_port, mirrors the
     * `validPort` guard in ShareLinks and WgConfig.splitHostPort.
     */
    private fun JsonObject.port(key: String): Int? = int(key)?.takeIf { it in 1..65535 }

    private fun JsonObject.bool(key: String): Boolean =
        string(key)?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false

    /** Accepts a JSON array of strings or a single comma-separated primitive. */
    private fun JsonObject.stringList(key: String): List<String> = when (val e = this[key]) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
        is JsonPrimitive -> e.contentOrNull?.split(',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }

    private fun JsonArray.firstObj(): JsonObject? = firstOrNull() as? JsonObject
}
