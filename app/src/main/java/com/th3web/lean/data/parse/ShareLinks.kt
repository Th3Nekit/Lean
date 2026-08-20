package com.th3web.lean.data.parse

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.th3web.lean.data.Serialization
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.RealitySettings
import com.th3web.lean.data.model.TlsSettings
import com.th3web.lean.data.model.TransportSettings

/**
 * Parses proxy share links into [Profile]s. Supports the formats used across
 * the Xray/sing-box ecosystem: vless, vmess, trojan, ss, hysteria (v1),
 * hysteria2, tuic.
 *
 * All parsers are best-effort and return null on malformed input rather than
 * throwing, so a bad line in a subscription doesn't abort the whole import.
 */
object ShareLinks {

    /**
     * Xray-only stream transports with no sing-box equivalent in Lean's Outbound
     * model. Skipped: Incy stores them and flags requires-xray, and a
     * parsed-but-broken config is worse than an absent one.
     *
     * xhttp/splithttp are in the set but no longer absolutely: VLESS now has an Xray
     * path (the helper process), so [parseVless] admits them while every other
     * protocol still drops them. kcp and quic have no path anywhere and drop always.
     */
    private val UNSUPPORTED_NETWORKS = setOf("splithttp", "xhttp", "kcp", "quic")

    /** The two spellings of Xray's XHTTP. Normalised to [XHTTP] on import. */
    private val XHTTP_NETWORKS = setOf("xhttp", "splithttp")

    /** The single spelling stored in [TransportSettings.type]. */
    internal const val XHTTP = "xhttp"

    /**
     * Incy accepts '|' as a query-pair separator (some panels emit it). Normalize
     * '|' → '&' within the query segment only, leaving scheme/host/fragment intact.
     */
    private fun normalizePipes(link: String): String {
        val q = link.indexOf('?')
        if (q < 0 || '|' !in link) return link
        val end = link.indexOf('#', q).let { if (it < 0) link.length else it }
        return link.substring(0, q) + link.substring(q, end).replace('|', '&') + link.substring(end)
    }

    fun parse(raw: String): Profile? {
        val link = raw.trim()
        return runCatching {
            when {
                link.startsWith("vless://") -> parseVless(link)
                link.startsWith("vmess://") -> parseVmess(link)
                link.startsWith("trojan://") -> parseTrojan(link)
                link.startsWith("ss://") -> parseShadowsocks(link)
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2(link)
                link.startsWith("hysteria://") -> parseHysteria(link)
                link.startsWith("tuic://") -> parseTuic(link)
                // naive+https:// and naive+quic:// are upstream's own share form; the
                // bare naive:// spelling shows up in the wild too.
                link.startsWith("naive+") || link.startsWith("naive://") -> parseNaive(link)
                link.startsWith("mieru://") || link.startsWith("mierus://") -> parseMieru(link)
                link.startsWith(OLCRTC_SCHEME) -> parseOlcrtc(link)
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Parse a blob that may contain many links (one per line).
     *
     * flatMap, not mapNotNull: one mieru link can describe several endpoints (its ports
     * are repeatable query params), and taking only the first would quietly drop the
     * rest of a server list the user pasted in full.
     */
    fun parseMany(text: String): List<Profile> =
        text.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { parseAll(it) }

    /** Every profile a single link describes, one for all schemes except mieru's. */
    fun parseAll(raw: String): List<Profile> = runCatching {
        val link = raw.trim()
        if (link.startsWith("mieru://") || link.startsWith("mierus://")) {
            parseMieruAll(link)
        } else {
            listOfNotNull(parse(link))
        }
    }.getOrDefault(emptyList())

    // ---- serializers (share-link export) ----

    /**
     * Best-effort inverse of [parse]: serializes a profile back to a share link
     * for «Скопировать ссылку». Invariant: `parse(toShareLink(p)!!)` reproduces
     * `outbound` and `name` for parser-produced profiles.
     *
     * Known lossy spot: VMess `insecure`/Reality have no slot in the canonical
     * base64-JSON form and are dropped (documented loss). VMess also carries the
     * name in `ps` instead of a URI fragment: a `#fragment` would corrupt the
     * base64 payload for our own (and most third-party) parsers.
     */
    fun toShareLink(profile: Profile): String? = runCatching {
        when (val o = profile.outbound) {
            is Outbound.Vless -> vlessLink(profile.name, o)
            is Outbound.Vmess -> vmessLink(profile.name, o)
            is Outbound.Trojan -> trojanLink(profile.name, o)
            is Outbound.Shadowsocks -> ssLink(profile.name, o)
            is Outbound.Hysteria2 -> hysteria2Link(profile.name, o)
            is Outbound.Hysteria -> hysteriaLink(profile.name, o)
            is Outbound.Tuic -> tuicLink(profile.name, o)
            // WireGuard has no canonical share-link URI (it's a whole-interface
            // .conf, not a one-line proxy URI). Honestly unrepresentable → null,
            // which the UI surfaces as «Ссылку нельзя сформировать».
            is Outbound.WireGuard -> null
            is Outbound.Naive -> naiveLink(profile.name, o)
            is Outbound.Mieru -> mieruLink(profile.name, o)
            is Outbound.Olcrtc -> olcrtcLink(profile.name, o)
        }
    }.getOrNull()

    /** `user:pass@` for a URI's authority, omitted entirely when there is no username. */
    private fun credentials(username: String, password: String): String = buildString {
        if (username.isNotBlank()) {
            append(Uri.encode(username))
            if (password.isNotBlank()) append(':').append(Uri.encode(password))
            append('@')
        }
    }

    private fun naiveLink(name: String, o: Outbound.Naive): String {
        val proto = if (o.proto.equals("quic", ignoreCase = true)) "quic" else "https"
        val params = buildList {
            add("sni" to o.sni)
            add("cert" to o.certificates)
            // Upstream carries these CRLF-separated inside the link.
            add("extra-headers" to o.extraHeaders.replace("\n", "\r\n"))
            add("insecure-concurrency" to o.insecureConcurrency.takeIf { it > 0 }?.toString().orEmpty())
        }
        return "naive+$proto://${credentials(o.username, o.password)}" +
            "${hostPart(o.server)}:${o.serverPort}" + query(params) + fragment(name)
    }

    /**
     * Emits the `mierus://` simple form, which is the one other clients can read.
     *
     * Note what does not go in the authority: mieru puts the port in a `port` query
     * param, paired with a `protocol` param, and the authority carries only the host.
     * `profile` is mandatory upstream (`mieru import config` rejects a link without it).
     * The bare `mieru://` form is base64-encoded protobuf and is not
     * emitted: we can read it, but writing it would mean hand-rolling a protobuf
     * encoder to no benefit.
     */
    private fun mieruLink(name: String, o: Outbound.Mieru): String {
        val params = buildList {
            add("profile" to "default")
            add("port" to o.serverPort.toString())
            add("protocol" to o.transport)
            // Only applies to the UDP transport; omitted at its default so the link
            // carries no inert fields.
            add("mtu" to o.mtu.takeIf { o.transport == "UDP" && it != DEFAULT_MIERU_MTU }?.toString().orEmpty())
        }
        return "mierus://${credentials(o.username, o.password)}" +
            hostPart(o.server) + query(params) + fragment(name)
    }

    /**
     * The community's compact olcRTC notation, the single line a bot can hand out.
     *
     * ```
     * olcrtc://<provider>?<transport>[<k=v&k=v>]@<room>#<key>$<comment>
     * ```
     *
     * Not a format of our own. olcrtc itself does not parse this: it reads
     * YAML, but the convention is published in the project's docs/uri.md and is what its
     * other clients already exchange, so a link minted anywhere works here and ours works
     * there. Inventing a second spelling would only split that.
     *
     * The separators are positional (`?`, `@`, `#`, `$`), and the fields may legitimately
     * contain characters that would break a URI parser: a jitsi room is a whole
     * `https://host/room` URL, so this is cut by hand, left to right, exactly as the
     * convention describes.
     */
    private fun parseOlcrtc(link: String): Profile? {
        val body = link.removePrefix(OLCRTC_SCHEME)
        // Comment last: it is free text and may contain anything, including separators.
        val comment = body.substringAfter('$', "").trim()
        val head = body.substringBefore('$')

        val provider = head.substringBefore('?', "").trim().lowercase()
        if (provider.isEmpty()) return null
        val afterProvider = head.substringAfter('?', "")

        val roomAndKey = afterProvider.substringAfter('@', "")
        if (roomAndKey.isEmpty()) return null
        val roomId = roomAndKey.substringBefore('#').trim()
        val key = roomAndKey.substringAfter('#', "").trim()
        if (roomId.isEmpty() || key.isEmpty()) return null

        // `<k=v&k=v>` rides directly after the transport name; absent when defaults are used.
        val transportPart = afterProvider.substringBefore('@')
        val transport = transportPart.substringBefore('<').trim().lowercase()
        if (transport.isEmpty()) return null
        val options = transportPart
            .substringAfter('<', "")
            .substringBefore('>')
            .split('&')
            .mapNotNull { pair ->
                val k = pair.substringBefore('=').trim()
                val v = pair.substringAfter('=', "").trim()
                if (k.isEmpty() || v.isEmpty()) null else k to v
            }
            .toMap()

        // There is no server to dial, so the label falls back to the room's own host,
        // the one address in the link a user would recognise.
        val host = roomHost(roomId)
        return Profile(
            name = comment.ifBlank { "olcRTC $provider" },
            outbound = Outbound.Olcrtc(
                server = host,
                provider = provider,
                transport = transport,
                roomId = roomId,
                key = key,
                options = options,
            ),
        )
    }

    /** The recognisable host inside a room id, for labels and ping targets. */
    private fun roomHost(roomId: String): String =
        roomId.substringAfter("://").substringBefore('/').substringBefore(':')
            .ifBlank { roomId }

    /** Round-trips [parseOlcrtc]; see it for why this notation and not one of ours. */
    private fun olcrtcLink(name: String, o: Outbound.Olcrtc): String {
        val payload = if (o.options.isEmpty()) {
            ""
        } else {
            o.options.entries.joinToString("&", prefix = "<", postfix = ">") { "${it.key}=${it.value}" }
        }
        return OLCRTC_SCHEME + o.provider + "?" + o.transport + payload +
            "@" + o.roomId + "#" + o.key +
            if (name.isBlank()) "" else "$$name"
    }

    private fun vlessLink(name: String, o: Outbound.Vless): String {
        val params = buildList {
            add("type" to o.network)
            add("security" to securityOf(o.tls))
            add("flow" to o.flow)
            // Omitted at "none", the default every reader assumes, and a query param
            // that says nothing is a query param that can go.
            add("encryption" to o.encryption.takeUnless { it == "none" }.orEmpty())
            addAll(tlsParams(o.tls))
            addAll(transportParams(o.network, o.transport))
        }
        return "vless://${Uri.encode(o.uuid)}@${hostPart(o.server)}:${o.serverPort}" +
            query(params) + fragment(name)
    }

    private fun trojanLink(name: String, o: Outbound.Trojan): String {
        val params = buildList {
            add("type" to o.network)
            add("security" to securityOf(o.tls))
            addAll(tlsParams(o.tls))
            addAll(transportParams(o.network, o.transport))
        }
        return "trojan://${Uri.encode(o.password)}@${hostPart(o.server)}:${o.serverPort}" +
            query(params) + fragment(name)
    }

    /** Canonical vmess base64-JSON (v2 fields). Name rides in `ps`, no fragment. */
    private fun vmessLink(name: String, o: Outbound.Vmess): String {
        val grpc = o.network.lowercase() == "grpc"
        val obj = buildJsonObject {
            put("v", "2")
            put("ps", name)
            put("add", o.server)
            put("port", o.serverPort.toString())
            put("id", o.uuid)
            put("aid", o.alterId.toString())
            put("scy", o.security)
            put("net", o.network)
            put("type", "none")
            put("host", o.transport?.host.orEmpty())
            put("path", if (grpc) o.transport?.serviceName.orEmpty() else o.transport?.path.orEmpty())
            put("tls", if (o.tls?.enabled == true) "tls" else "")
            put("sni", o.tls?.serverName.orEmpty())
            put("alpn", o.tls?.alpn?.joinToString(",").orEmpty())
            put("fp", o.tls?.utlsFingerprint.orEmpty())
        }
        return "vmess://" + Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** SIP002: ss://base64(method:password)@host:port[?plugin=…]#name */
    private fun ssLink(name: String, o: Outbound.Shadowsocks): String {
        val userInfo = Base64.encodeToString(
            "${o.method}:${o.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
        )
        val plugin = if (o.plugin.isBlank()) "" else {
            val value = if (o.pluginOpts.isBlank()) o.plugin else "${o.plugin};${o.pluginOpts}"
            "?plugin=" + Uri.encode(value)
        }
        return "ss://$userInfo@${hostPart(o.server)}:${o.serverPort}$plugin" + fragment(name)
    }

    private fun hysteria2Link(name: String, o: Outbound.Hysteria2): String {
        val params = buildList {
            add("sni" to (o.tls?.serverName).orEmpty())
            if (o.tls?.insecure == true) add("insecure" to "1")
            add("alpn" to (o.tls?.alpn?.joinToString(",")).orEmpty())
            add("obfs" to o.obfsType)
            add("obfs-password" to o.obfsPassword)
        }
        return "hysteria2://${Uri.encode(o.password)}@${hostPart(o.server)}:${o.serverPort}" +
            query(params) + fragment(name)
    }

    /** Hysteria v1 canonical URI: auth/peer/upmbps/downmbps/obfs=xplus&obfsParam. */
    private fun hysteriaLink(name: String, o: Outbound.Hysteria): String {
        val params = buildList {
            add("auth" to o.authStr)
            add("peer" to (o.tls?.serverName).orEmpty())
            if (o.tls?.insecure == true) add("insecure" to "1")
            add("alpn" to (o.tls?.alpn?.joinToString(",")).orEmpty())
            if (o.upMbps > 0) add("upmbps" to o.upMbps.toString())
            if (o.downMbps > 0) add("downmbps" to o.downMbps.toString())
            if (o.obfs.isNotEmpty()) {
                add("obfs" to "xplus")
                add("obfsParam" to o.obfs)
            }
            // Stored "start:end" ranges back to the link convention "start-end,…".
            add("mport" to o.serverPorts.joinToString(",") { it.replace(':', '-') })
        }
        return "hysteria://${hostPart(o.server)}:${o.serverPort}" + query(params) + fragment(name)
    }

    private fun tuicLink(name: String, o: Outbound.Tuic): String {
        val params = buildList {
            add("congestion_control" to o.congestionControl)
            add("udp_relay_mode" to o.udpRelayMode)
            add("sni" to (o.tls?.serverName).orEmpty())
            add("alpn" to (o.tls?.alpn?.joinToString(",")).orEmpty())
            if (o.tls?.insecure == true) add("allow_insecure" to "1")
        }
        return "tuic://${Uri.encode(o.uuid)}:${Uri.encode(o.password)}@${hostPart(o.server)}:${o.serverPort}" +
            query(params) + fragment(name)
    }

    // ---- serializer helpers ----

    /**
     * Brackets a bare IPv6 literal for URI authority. Parsers built on
     * android.net.Uri store URI-form IPv6 hosts already bracketed (Uri.getHost
     * keeps brackets), while splitHostPort-based ones (ss) strip them, guard
     * against double-bracketing so both conventions round-trip.
     */
    private fun hostPart(server: String): String =
        if (':' in server && !server.startsWith("[")) "[$server]" else server

    private fun fragment(name: String): String = "#" + Uri.encode(name)

    /** Ordered k=v pairs; blank values skipped; values Uri.encode-d; "" when empty. */
    private fun query(params: List<Pair<String, String>>): String {
        val joined = params
            .filter { it.second.isNotBlank() }
            .joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" }
        return if (joined.isEmpty()) "" else "?$joined"
    }

    private fun securityOf(tls: TlsSettings?): String = when {
        tls == null || !tls.enabled -> "none"
        tls.reality != null -> "reality"
        else -> "tls"
    }

    private fun tlsParams(tls: TlsSettings?): List<Pair<String, String>> {
        if (tls == null || !tls.enabled) return emptyList()
        return buildList {
            add("sni" to tls.serverName)
            add("alpn" to tls.alpn.joinToString(","))
            add("fp" to tls.utlsFingerprint)
            tls.reality?.let {
                add("pbk" to it.publicKey)
                add("sid" to it.shortId)
                add("spx" to it.spiderX)
            }
            if (tls.insecure) add("allowInsecure" to "1")
        }
    }

    private fun transportParams(network: String, t: TransportSettings?): List<Pair<String, String>> =
        when (network.lowercase()) {
            "ws", "httpupgrade", "http", "h2" -> listOf(
                "path" to (t?.path).orEmpty(),
                "host" to (t?.host).orEmpty(),
            )
            "grpc" -> listOf("serviceName" to (t?.serviceName).orEmpty())
            // Re-exported under the name it was imported as, so a link handed back to the
            // panel it came from is the same link. `extra` is opaque JSON, Uri.encode in
            // query() is what keeps its braces and quotes from breaking the URI.
            "xhttp", "splithttp" -> listOf(
                "path" to (t?.path).orEmpty(),
                "host" to (t?.host).orEmpty(),
                "mode" to (t?.mode).orEmpty(),
                "extra" to (t?.extra).orEmpty(),
            )
            else -> emptyList()
        }

    // ---- per-protocol ----

    /**
     * A URI authority port is valid only in 1..65535; android.net.Uri.getPort()
     * returns -1 when absent. Reject out-of-range ports (e.g. ":99999") here so a
     * crafted/typo'd link does not propagate an impossible server_port into the
     * config, mirrors WgConfig.splitHostPort's `port !in 1..65535` guard.
     */
    private fun validPort(p: Int): Int? = p.takeIf { it in 1..65535 }

    /** Mieru's own default MTU (`pkg/common/mtu.go`), applied to UDP egress only. */
    internal const val DEFAULT_MIERU_MTU = 1400

    /** Scheme of the community olcRTC notation (docs/uri.md upstream). */
    internal const val OLCRTC_SCHEME = "olcrtc://"

    /**
     * What mieru's validator actually accepts. The docs say 1280-1400, the code checks
     * 1280-1500, accept the wider range on import so a config mieru itself would run is
     * never rejected here.
     */
    internal val MIERU_MTU_RANGE = 1280..1500

    private fun parseVless(link: String): Profile? {
        val uri = Uri.parse(normalizePipes(link))
        val uuid = uri.userInfo ?: return null
        val host = unbracket(uri.host ?: return null)
        val port = validPort(uri.port) ?: return null
        val q = { k: String -> uri.getQueryParameter(k) }
        val network = q("type") ?: "tcp"
        // VLESS is the one protocol that can leave the core: an xhttp node is handed to
        // the Xray helper (see PluginSession.pluginFor), so it must not be rejected here
        // the way it still is for VMess and Trojan, which have no such path.
        if (network.lowercase() in UNSUPPORTED_NETWORKS && network.lowercase() !in XHTTP_NETWORKS) {
            return null
        }
        val security = q("security") ?: "none"
        val tls = tlsFrom(
            security = security,
            sni = q("sni") ?: q("host"),
            alpn = q("alpn"),
            fp = q("fp"),
            pbk = q("pbk"),
            sid = q("sid"),
            insecure = isTruthy(q("allowInsecure")) || isTruthy(q("insecure")),
            spx = q("spx"),
        )
        val outbound = Outbound.Vless(
            server = host,
            serverPort = port,
            uuid = uuid,
            flow = q("flow").orEmpty(),
            network = network,
            encryption = q("encryption").orEmpty().ifBlank { "none" },
            tls = tls,
            transport = transportFrom(
                network, q("path"), q("host"), q("serviceName") ?: q("servicename"),
                mode = q("mode"), extra = q("extra"),
            ),
        )
        return profile(name(uri, host), outbound)
    }

    private fun parseVmess(link: String): Profile? {
        // URI form: vmess://uuid@host:port?type=…  (VLESS-style params, no base64 JSON).
        if ('@' in link.removePrefix("vmess://").substringBefore('#')) return parseVmessUri(link)
        val decoded = decodeBase64Tolerant(link.removePrefix("vmess://")) ?: return null
        val obj = runCatching {
            Serialization.json.parseToJsonElement(decoded) as? JsonObject
        }.getOrNull() ?: return null
        val s = { k: String -> obj[k]?.jsonPrimitive?.contentOrNull }
        val host = s("add") ?: return null
        val port = validPort(s("port")?.toIntOrNull() ?: return null) ?: return null
        val net = s("net") ?: "tcp"
        // Same guard as the VLESS / VMess-URI paths: a kcp/quic/xhttp/splithttp net
        // has no sing-box transport here, so the base64-JSON form would otherwise
        // import a silently-broken (transport-less) outbound. Drop it instead.
        if (net.lowercase() in UNSUPPORTED_NETWORKS) return null
        val tlsOn = (s("tls") ?: "").equals("tls", ignoreCase = true)
        val tls = if (tlsOn) TlsSettings(
            enabled = true,
            serverName = s("sni").orEmpty().ifEmpty { s("host").orEmpty() },
            alpn = alpnList(s("alpn")),
            utlsFingerprint = s("fp").orEmpty(),
        ) else null
        val outbound = Outbound.Vmess(
            server = host,
            serverPort = port,
            uuid = s("id") ?: return null,
            alterId = s("aid")?.toIntOrNull() ?: 0,
            security = s("scy") ?: "auto",
            network = net,
            tls = tls,
            transport = transportFrom(net, s("path"), s("host"), s("path")),
        )
        return profile(s("ps") ?: host, outbound)
    }

    /** VMess in URI form (uuid@host:port?…), VLESS params minus flow/reality. */
    private fun parseVmessUri(link: String): Profile? {
        val uri = Uri.parse(normalizePipes(link))
        val uuid = uri.userInfo ?: return null
        val host = unbracket(uri.host ?: return null)
        val port = validPort(uri.port) ?: return null
        val q = { k: String -> uri.getQueryParameter(k) }
        val network = q("type") ?: "tcp"
        if (network.lowercase() in UNSUPPORTED_NETWORKS) return null
        val tls = tlsFrom(
            security = q("security") ?: "none",
            sni = q("sni") ?: q("host"),
            alpn = q("alpn"),
            fp = q("fp"),
            pbk = null,
            sid = null,
            insecure = isTruthy(q("allowInsecure")) || isTruthy(q("insecure")),
        )
        val outbound = Outbound.Vmess(
            server = host,
            serverPort = port,
            uuid = uuid,
            alterId = q("aid")?.toIntOrNull() ?: 0,
            security = q("scy") ?: q("encryption") ?: "auto",
            network = network,
            tls = tls,
            transport = transportFrom(network, q("path"), q("host"), q("serviceName") ?: q("servicename")),
        )
        return profile(name(uri, host), outbound)
    }

    private fun parseTrojan(link: String): Profile? {
        val uri = Uri.parse(normalizePipes(link))
        val password = uri.userInfo ?: return null
        val host = unbracket(uri.host ?: return null)
        val port = validPort(uri.port) ?: return null
        val q = { k: String -> uri.getQueryParameter(k) }
        val network = q("type") ?: "tcp"
        if (network.lowercase() in UNSUPPORTED_NETWORKS) return null
        val tls = tlsFrom(
            security = q("security") ?: "tls",
            sni = q("sni") ?: q("peer") ?: q("host"),
            alpn = q("alpn"),
            fp = q("fp"),
            pbk = q("pbk"),
            sid = q("sid"),
            insecure = isTruthy(q("allowInsecure")) || isTruthy(q("insecure")),
        )
        val outbound = Outbound.Trojan(
            server = host,
            serverPort = port,
            password = password,
            network = network,
            tls = tls,
            transport = transportFrom(network, q("path"), q("host"), q("serviceName") ?: q("servicename")),
        )
        return profile(name(uri, host), outbound)
    }

    private fun parseShadowsocks(link: String): Profile? {
        val hashIdx = link.indexOf('#')
        val label = if (hashIdx >= 0) Uri.decode(link.substring(hashIdx + 1)) else ""
        var body = (if (hashIdx >= 0) link.substring(0, hashIdx) else link).removePrefix("ss://")
        var query = ""
        val qIdx = body.indexOf('?')
        if (qIdx >= 0) {
            query = body.substring(qIdx + 1)
            body = body.substring(0, qIdx)
        }

        val method: String
        val password: String
        val host: String
        val port: Int

        val atIdx = body.lastIndexOf('@')
        if (atIdx >= 0) {
            // SIP002: base64(method:password)@host:port
            val userPart = body.substring(0, atIdx)
            // SIP002 allows an optional '/' between authority and '?query'
            // (…:port [ "/" ] [ "?"plugin ]); strip it or the port won't parse.
            val hostPort = body.substring(atIdx + 1).removeSuffix("/")
            val creds = if (userPart.contains(':')) userPart else decodeBase64Tolerant(userPart) ?: return null
            val cIdx = creds.indexOf(':')
            if (cIdx < 0) return null
            // Plaintext userinfo (method:password) percent-encodes the password
            // (Shadowrocket/Clash form); the base64 branch is already literal.
            method = if (userPart.contains(':')) Uri.decode(creds.substring(0, cIdx)) else creds.substring(0, cIdx)
            password = if (userPart.contains(':')) Uri.decode(creds.substring(cIdx + 1)) else creds.substring(cIdx + 1)
            val hp = splitHostPort(hostPort) ?: return null
            host = hp.first; port = hp.second
        } else {
            // Legacy: base64(method:password@host:port)
            val decoded = decodeBase64Tolerant(body) ?: return null
            val da = decoded.lastIndexOf('@')
            if (da < 0) return null
            val creds = decoded.substring(0, da)
            val cIdx = creds.indexOf(':')
            if (cIdx < 0) return null
            method = creds.substring(0, cIdx)
            password = creds.substring(cIdx + 1)
            val hp = splitHostPort(decoded.substring(da + 1)) ?: return null
            host = hp.first; port = hp.second
        }

        val plugin = parseQuery(query)["plugin"].orEmpty()
        val pluginName = plugin.substringBefore(';')
        val pluginOpts = if (plugin.contains(';')) plugin.substringAfter(';') else ""

        // sing-box implements Shadowsocks-2022 EIH (multi-user key "iPSK:uPSK")
        // only for AES ciphers. A non-AES 2022 method carrying such a key fails at
        // create-service and aborts the whole sing-box instance, every other
        // server dies too ("ShadowSocks не работает"). Drop just this one unrunnable
        // node, mirroring the UNSUPPORTED_NETWORKS / VLESS-encryption guards.
        // Fire only on a genuine multi-segment EIH key (two or more parts that each
        // base64-decode to a 16/32-byte PSK), so a single-PSK chacha20 password that
        // merely contains an incidental ':' is not dropped. (Mirrors XrayConfig.)
        method.lowercase().let { m ->
            if (m.startsWith("2022-blake3-") && "aes" !in m) {
                val parts = password.split(':')
                val isEih = parts.size >= 2 && parts.all {
                    decodeBase64TolerantBytes(it)?.size.let { n -> n == 16 || n == 32 }
                }
                if (isEih) return null
            }
        }

        val outbound = Outbound.Shadowsocks(
            server = host,
            serverPort = port,
            method = method,
            password = password,
            plugin = pluginName,
            pluginOpts = pluginOpts,
        )
        return profile(label.ifEmpty { host }, outbound)
    }

    private fun parseHysteria2(link: String): Profile? {
        val uri = Uri.parse(normalizePipes(link))
        val host = unbracket(uri.host ?: return null)
        val q = { k: String -> uri.getQueryParameter(k) }
        // Port hopping (mport/ports): sing-box hysteria2 hop fields aren't wired
        // up here, so keep the base port, first port of the hop spec when the
        // authority omits one. Default 443 (Incy default for hy2).
        val hop = q("mport") ?: q("ports")
        val port = validPort(uri.port) ?: hopBasePort(hop)?.let(::validPort) ?: 443
        // Auth rides in userinfo or ?auth=/?password= depending on the panel;
        // hy2 allows auth-less servers, so an absent password is still valid.
        val password = (uri.userInfo ?: q("password") ?: q("auth")).orEmpty()
        // sing-box has no certificate pinning: a pinSHA256 link usually fronts a
        // self-signed cert, so honor it by skipping name verification instead of
        // importing a profile whose TLS handshake can never succeed.
        val pinned = !q("pinSHA256").isNullOrEmpty()
        val tls = TlsSettings(
            enabled = true,
            serverName = (q("sni") ?: q("peer")).orEmpty(),
            insecure = isTruthy(q("insecure")) || isTruthy(q("allowInsecure")) || pinned,
            alpn = alpnList(q("alpn")),
        )
        // Salamander is hy2's only obfs mode and requires a password; a typed
        // obfs without one would make sing-box reject the whole config, so it
        // degrades to "no obfs". A bare password implies salamander.
        // obfs=none must fully disable obfuscation: previously an "obfs=none" link that
        // still carried an obfs-password kept the password and defaulted the type to
        // salamander → obfuscation turned on against a plain-hy2 server, breaking the
        // handshake. Clear both fields when obfs is explicitly none.
        val rawObfsParam = q("obfs")
        val obfsDisabled = rawObfsParam?.equals("none", ignoreCase = true) == true
        val obfsPassword = if (obfsDisabled) "" else (q("obfs-password") ?: q("obfs_password") ?: q("obfsParam")).orEmpty()
        val rawObfs = rawObfsParam?.takeIf { !obfsDisabled }.orEmpty()
        val obfsType = when {
            obfsPassword.isEmpty() -> ""
            rawObfs.isEmpty() -> "salamander"
            else -> rawObfs
        }
        val outbound = Outbound.Hysteria2(
            server = host,
            serverPort = port,
            password = password,
            obfsType = obfsType,
            obfsPassword = obfsPassword,
            tls = tls,
        )
        return profile(name(uri, host), outbound)
    }

    /**
     * Hysteria v1: hysteria://[auth@]host:port?protocol=udp&auth=…&peer=…&
     * upmbps=…&downmbps=…&obfs=xplus&obfsParam=…&mport=…, query keys vary by
     * panel, so each field accepts its known aliases.
     */
    private fun parseHysteria(link: String): Profile? {
        val uri = Uri.parse(normalizePipes(link))
        val host = unbracket(uri.host ?: return null)
        val q = { k: String -> uri.getQueryParameter(k) }
        // sing-box implements only v1's default UDP transport; faketcp /
        // wechat-video links are unrepresentable (would fail at connect time).
        val proto = q("protocol")
        if (!proto.isNullOrEmpty() && !proto.equals("udp", ignoreCase = true)) return null
        val hop = q("mport") ?: q("ports")
        val port = validPort(uri.port) ?: hopBasePort(hop)?.let(::validPort) ?: 443
        val auth = uri.userInfo ?: q("auth") ?: q("auth_str") ?: q("password")
        // v1 obfs is xplus-only; panels carry the password in obfsParam
        // (canonical), obfs-password, or directly in obfs when it isn't the
        // mode name itself.
        val obfsPassword = q("obfsParam") ?: q("obfs-password")
            ?: q("obfs")?.takeIf {
                !it.equals("xplus", ignoreCase = true) && !it.equals("none", ignoreCase = true)
            }
        val tls = TlsSettings(
            enabled = true,
            serverName = (q("peer") ?: q("sni")).orEmpty(),
            insecure = isTruthy(q("insecure")) || isTruthy(q("allowInsecure")),
            alpn = alpnList(q("alpn")),
        )
        val outbound = Outbound.Hysteria(
            server = host,
            serverPort = port,
            authStr = auth.orEmpty(),
            upMbps = mbps(q("upmbps") ?: q("up_mbps") ?: q("up")),
            downMbps = mbps(q("downmbps") ?: q("down_mbps") ?: q("down")),
            obfs = obfsPassword.orEmpty(),
            serverPorts = hopRanges(hop),
            tls = tls,
        )
        return profile(name(uri, host), outbound)
    }

    private fun parseTuic(link: String): Profile? {
        val uri = Uri.parse(normalizePipes(link))
        val host = unbracket(uri.host ?: return null)
        val port = validPort(uri.port) ?: return null
        val userInfo = uri.userInfo ?: return null
        val uuid = userInfo.substringBefore(':')
        val password = if (userInfo.contains(':')) userInfo.substringAfter(':') else ""
        val q = { k: String -> uri.getQueryParameter(k) }
        val tls = TlsSettings(
            enabled = true,
            serverName = q("sni").orEmpty(),
            insecure = q("allow_insecure") == "1" || q("allowInsecure") == "1",
            alpn = alpnList(q("alpn")),
        )
        val outbound = Outbound.Tuic(
            server = host,
            serverPort = port,
            uuid = uuid,
            password = password,
            congestionControl = q("congestion_control") ?: "bbr",
            udpRelayMode = q("udp_relay_mode") ?: "native",
            tls = tls,
        )
        return profile(name(uri, host), outbound)
    }

    /**
     * NaiveProxy: `naive+https://user:pass@host:port?sni=…&extra-headers=…#name`
     * (also `naive+quic://`, and the bare `naive://` some panels emit).
     *
     * The scheme's second half is the transport naive itself uses, https is HTTP/2 over
     * TLS, quic is HTTP/3, so it is kept rather than normalised away.
     */
    private fun parseNaive(link: String): Profile? {
        // Uri.parse handles the `naive+https` scheme fine, but the `+` makes some callers
        // hand us a pre-decoded string; normalise to the inner scheme for parsing and
        // remember which one it was.
        val proto = when {
            link.startsWith("naive+quic://") -> "quic"
            link.startsWith("naive+https://") -> "https"
            else -> "https"
        }
        val stripped = link.substringAfter("naive+", link).let {
            if (it.startsWith("naive://")) "https://" + it.removePrefix("naive://") else it
        }
        val uri = Uri.parse(normalizePipes(stripped))
        val host = unbracket(uri.host ?: return null)
        // 443 is naive's own default and the only sane one: it fronts a real website.
        val port = validPort(uri.port) ?: 443
        val userInfo = uri.userInfo.orEmpty()
        val q = { k: String -> uri.getQueryParameter(k) }
        val outbound = Outbound.Naive(
            server = host,
            serverPort = port,
            proto = proto,
            username = Uri.decode(userInfo.substringBefore(':')),
            password = if (':' in userInfo) Uri.decode(userInfo.substringAfter(':')) else "",
            sni = q("sni").orEmpty(),
            certificates = q("cert").orEmpty(),
            // Upstream writes these CRLF-separated and url-encodes them into the link.
            extraHeaders = q("extra-headers")?.replace("\r\n", "\n").orEmpty(),
            insecureConcurrency = q("insecure-concurrency")?.toIntOrNull() ?: 0,
        )
        return profile(name(uri, host), outbound)
    }

    /** The first endpoint only; [parseMieruAll] is what callers importing a list want. */
    private fun parseMieru(link: String): Profile? = parseMieruAll(link).firstOrNull()

    /**
     * Mieru's two real share-link forms. Both come straight from upstream's
     * `pkg/appctl/url.go`, neither resembles the usual `scheme://user:pass@host:port`
     * shape, which is the trap: a link that looks parseable by the generic rules
     * is not, and guessing produces a profile that silently points nowhere.
     *
     *  - `mierus://user:password@host?profile=…&port=…&protocol=…`
     *    The "simple", human-readable form (`mieru export config simple`). The port is
     * not in the authority: it is one or more `port` query params, positionally
     *    paired with the same number of `protocol` params, and a port may itself be a
     *    range ("9998-9999"). One link therefore describes several endpoints.
     *
     *  - `mieru://<base64>` where the payload is a protobuf-encoded ClientConfig
     *    (`mieru export config`). Handled by [MieruConfigProto].
     *
     * Returns one profile per endpoint, because our model holds a single server:port.
     */
    private fun parseMieruAll(link: String): List<Profile> {
        if (link.startsWith("mieru://")) {
            return MieruConfigProto.parse(link.removePrefix("mieru://").trim())
                .map { profile(it.name.ifBlank { it.outbound.server }, it.outbound) }
        }
        val uri = Uri.parse(normalizePipes(link))
        val host = unbracket(uri.host ?: return emptyList())
        val userInfo = uri.userInfo.orEmpty()
        val username = Uri.decode(userInfo.substringBefore(':'))
        val password = if (':' in userInfo) Uri.decode(userInfo.substringAfter(':')) else ""
        // Repeatable and positionally paired. getQueryParameters (plural) preserves both
        // order and duplicates; getQueryParameter would silently keep only the first and
        // reduce a four-endpoint link to one.
        val ports = uri.getQueryParameters("port")
        val protocols = uri.getQueryParameters("protocol")
        if (ports.isEmpty()) return emptyList()
        val mtu = uri.getQueryParameter("mtu")?.toIntOrNull()?.takeIf { it in MIERU_MTU_RANGE }
            ?: DEFAULT_MIERU_MTU
        val label = name(uri, host)

        return ports.mapIndexedNotNull { index, raw ->
            // A range's first port is the one to dial; upstream's own Addr() does the
            // same. Ranges are kept as endpoints rather than expanded, the rest of the
            // range is a server-side hopping detail, not N separate servers.
            val port = validPort(raw.substringBefore('-').trim().toIntOrNull() ?: -1)
                ?: return@mapIndexedNotNull null
            // Upstream looks the protocol up in a map with no miss-check, so a lowercase
            // or misspelled value silently becomes unknown there. We validate instead and
            // fall back to TCP, which is the transport that actually works by default.
            val transport = if (protocols.getOrNull(index).equals("UDP", ignoreCase = true)) "UDP" else "TCP"
            profile(
                if (ports.size > 1) "$label #${index + 1}" else label,
                Outbound.Mieru(
                    server = host,
                    serverPort = port,
                    transport = transport,
                    username = username,
                    password = password,
                    mtu = mtu,
                ),
            )
        }
    }

    // ---- helpers ----

    private fun name(uri: Uri, fallback: String): String =
        // android.net.Uri.getFragment() already returns the decoded fragment; a second
        // Uri.decode() mangles a literal `%` in the name (and diverged from parseShadowsocks).
        uri.fragment?.takeIf { it.isNotBlank() } ?: fallback

    private fun profile(name: String, outbound: Outbound) =
        Profile(name = dedupeFlags(name), outbound = outbound)

    /** Regional-indicator symbols U+1F1E6–U+1F1FF; a country flag is a pair of these. */
    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    /**
     * Collapses consecutive repeats of the same country-flag emoji in a display
     * name, e.g. some providers (nimarko) put the flag in both a title segment and
     * the per-server name, yielding "🇩🇪🇩🇪 Frankfurt". A country flag is exactly two
     * regional-indicator code points (U+1F1E6–U+1F1FF); we keep the first
     * occurrence and drop an immediately-following identical flag, whether it is
     * directly adjacent ("🇩🇪🇩🇪") or separated only by spaces ("🇩🇪 🇩🇪"). Distinct
     * flags (e.g. "🇩🇪🇳🇱"), and all other text are left untouched.
     */
    fun dedupeFlags(name: String): String {
        // Cheap guard: no regional indicators → nothing to do (the common case).
        // Regional indicators live in the supplementary plane (high surrogate
        // U+D83C, low surrogate U+DDE6–U+DDFF), so check the surrogate pair.
        val hasFlag = name.indices.any { idx ->
            name[idx] == '\uD83C' && idx + 1 < name.length && name[idx + 1] in '\uDDE6'..'\uDDFF'
        }
        if (!hasFlag) return name
        val cps = name.codePointCount(0, name.length)
        val out = StringBuilder(name.length)
        var i = 0
        var prevFlag: Pair<Int, Int>? = null // last emitted flag, for repeat detection
        var spacesSincePrevFlag = 0          // spaces emitted after that flag, not yet broken
        while (i < cps) {
            val cp = name.codePointAt(name.offsetByCodePoints(0, i))
            val next = if (i + 1 < cps) name.codePointAt(name.offsetByCodePoints(0, i + 1)) else -1
            if (isRegionalIndicator(cp) && isRegionalIndicator(next)) {
                val flag = cp to next
                // Drop this flag if it exactly repeats the previous emitted flag
                // with nothing but spaces in between (adjacent or space-separated).
                if (flag == prevFlag) {
                    // Trim the spaces this duplicate would have hung off of, so
                    // "🇩🇪 🇩🇪 Berlin" collapses cleanly to "🇩🇪 Berlin".
                    repeat(spacesSincePrevFlag) {
                        if (out.isNotEmpty() && out.last() == ' ') out.deleteCharAt(out.length - 1)
                    }
                    spacesSincePrevFlag = 0
                    i += 2
                    continue
                }
                out.appendCodePoint(cp)
                out.appendCodePoint(next)
                prevFlag = flag
                spacesSincePrevFlag = 0
                i += 2
            } else {
                if (cp == ' '.code) {
                    if (prevFlag != null) spacesSincePrevFlag++
                } else {
                    prevFlag = null // any non-space text breaks the repeat run
                }
                out.appendCodePoint(cp)
                i += 1
            }
        }
        return out.toString()
    }

    private fun alpnList(alpn: String?): List<String> =
        alpn?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Boolean query flags come as "1" or "true" depending on the panel. */
    private fun isTruthy(v: String?): Boolean =
        v == "1" || v.equals("true", ignoreCase = true)

    /** Bandwidth values come as "100", "100mbps" or "100 Mbps", leading digits win. */
    private fun mbps(v: String?): Int =
        v?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    /** First port of a hop spec like "443,5000-6000", used as the base server_port. */
    private fun hopBasePort(spec: String?): Int? =
        spec?.split(',', ';')
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()?.substringBefore('-')?.substringBefore(':')?.toIntOrNull()

    /**
     * Normalizes a port-hop spec ("443,5000-6000" or "5000:6000") to sing-box
     * "start:end" entries; malformed items are dropped, never fatal.
     */
    private fun hopRanges(spec: String?): List<String> =
        spec.orEmpty().split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { item ->
                val parts = item.replace('-', ':').split(':')
                when (parts.size) {
                    1 -> parts[0].toIntOrNull()?.let { "$it:$it" }
                    2 -> {
                        val a = parts[0].toIntOrNull()
                        val b = parts[1].toIntOrNull()
                        if (a != null && b != null) "$a:$b" else null
                    }
                    else -> null
                }
            }

    private fun tlsFrom(
        security: String,
        sni: String?,
        alpn: String?,
        fp: String?,
        pbk: String?,
        sid: String?,
        insecure: Boolean,
        spx: String? = null,
    ): TlsSettings? = when (security.lowercase()) {
        "reality" -> TlsSettings(
            enabled = true,
            serverName = sni.orEmpty(),
            alpn = alpnList(alpn),
            utlsFingerprint = fp.orEmpty(),
            reality = pbk?.takeIf { it.isNotEmpty() }?.let {
                RealitySettings(it, sid.orEmpty(), spx.orEmpty())
            },
        )
        "tls", "xtls" -> TlsSettings(
            enabled = true,
            serverName = sni.orEmpty(),
            insecure = insecure,
            alpn = alpnList(alpn),
            utlsFingerprint = fp.orEmpty(),
        )
        // No recognised security value. A bare `allowInsecure=1` with security absent is
        // the old shorthand for "TLS, don't verify", so it still turns TLS on. An
        // explicit security=none must not: it says the node speaks plaintext, and
        // enabling TLS anyway made the client open a handshake against, say, a ws:80
        // endpoint, connects, then never carries traffic.
        else -> if (insecure && security.isBlank()) {
            TlsSettings(enabled = true, serverName = sni.orEmpty(), insecure = true)
        } else {
            null
        }
    }

    private fun transportFrom(
        network: String?,
        path: String?,
        host: String?,
        serviceName: String?,
        mode: String? = null,
        extra: String? = null,
    ): TransportSettings? =
        when (network?.lowercase()) {
            "ws" -> TransportSettings("ws", path.orEmpty().ifEmpty { "/" }, host.orEmpty())
            "httpupgrade" -> TransportSettings("httpupgrade", path.orEmpty().ifEmpty { "/" }, host.orEmpty())
            "grpc" -> TransportSettings("grpc", serviceName = serviceName.orEmpty())
            "http", "h2" -> TransportSettings("http", path.orEmpty().ifEmpty { "/" }, host.orEmpty())
            // Normalised to "xhttp": `splithttp` is the same transport under the name it
            // shipped with, and one spelling downstream beats two.
            "xhttp", "splithttp" -> TransportSettings(
                type = XHTTP,
                path = path.orEmpty().ifEmpty { "/" },
                host = host.orEmpty(),
                mode = mode.orEmpty(),
                extra = extra.orEmpty(),
            )
            else -> null // tcp / raw: no stream transport
        }

    /**
     * Strips surrounding brackets from an IPv6 literal authority. android.net.Uri
     * .getHost() returns IPv6 literals with their '[ ]' (see hostPart above), but
     * sing-box's `server` field wants a bare host/IP, so unbracket before storing.
     */
    private fun unbracket(h: String): String =
        if (h.startsWith("[") && h.endsWith("]")) h.substring(1, h.length - 1) else h

    private fun splitHostPort(hostPort: String): Pair<String, Int>? {
        // IPv6 in brackets: [::1]:443
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            val h = hostPort.substring(1, close)
            val p = validPort(hostPort.substringAfter("]:", "").toIntOrNull() ?: return null) ?: return null
            return h to p
        }
        val idx = hostPort.lastIndexOf(':')
        if (idx < 0) return null
        val h = hostPort.substring(0, idx)
        val p = validPort(hostPort.substring(idx + 1).toIntOrNull() ?: return null) ?: return null
        return h to p
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else Uri.decode(it.substring(0, eq)) to Uri.decode(it.substring(eq + 1))
        }.toMap()
    }
}
