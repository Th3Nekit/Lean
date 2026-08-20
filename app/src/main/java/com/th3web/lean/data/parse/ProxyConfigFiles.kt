package com.th3web.lean.data.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.RealitySettings
import com.th3web.lean.data.model.TlsSettings
import com.th3web.lean.data.model.TransportSettings

/**
 * Importing the CONFIG files of the two helper protocols, as opposed to their share links.
 *
 * Three formats exist and are handled here. A fourth that people ask for does not exist
 * and is absent: **there is no YAML form of a naive config**. naive reads a
 * single JSON object and nothing else, and no Clash-family client implements naive as a
 * proxy type, so there is nothing to parse. Writing a speculative parser for it would
 * only produce servers that fail at connect time.
 *
 * 1. **mieru client JSON**, what `mieru apply config <file.json>` takes. One file can
 *     hold several profiles, each with several servers, each with several port bindings,
 *     so it fans out to many entries.
 * 2. **naive JSON**, a flat object of CLI-switch names; the server lives in the `proxy`
 *     URI.
 * 3. **mihomo / Clash.Meta YAML**, a `proxies:` list. mieru rides here as
 *     `type: mieru`, and so do the ordinary protocols (vless/vmess/trojan/ss/hysteria2/
 *     tuic), which is what a panel's .yaml export actually contains.
 */
object ProxyConfigFiles {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Every profile the file describes; empty when it is not one of these formats. */
    fun parse(text: String, fallbackName: String = ""): List<Profile> {
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return emptyList()
        return when {
            looksLikeJson(trimmed) -> parseJson(trimmed, fallbackName)
            else -> parseClashYaml(trimmed)
        }
    }

    private fun looksLikeJson(text: String): Boolean = text.startsWith("{") || text.startsWith("[")

    private fun parseJson(text: String, fallbackName: String): List<Profile> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return emptyList()
        // Discriminate on a key only that format has. mieru's config is the one with
        // "profiles"; naive's is the one with "proxy"/"listen". Neither can be mistaken
        // for the other, and neither collides with a Xray/sing-box config (those lead
        // with "outbounds"/"inbounds"), so an unrelated JSON file yields nothing rather
        // than a bogus server.
        return when {
            // A sing-box-shaped config: an "outbounds" array whose entries carry "type".
            // Checked first because such a file has neither of the other two markers at
            // its root, and because it is what a panel hands out for these protocols.
            root.containsKey("outbounds") -> parseSingBoxOutbounds(root)
            root.containsKey("profiles") -> parseMieruJson(root)
            root.containsKey("proxy") || root.containsKey("listen") -> parseNaiveJson(root, fallbackName)
            else -> emptyList()
        }
    }

    /**
     * Picks the helper-protocol servers out of a sing-box-style config.
     *
     * ```
     *   { "outbounds": [
     *       { "type": "mieru", "tag": "…", "server": …, "server_port": …,
     *         "username": …, "password": …, "transport": "TCP" },
     *       { "type": "naive", "tag": "…", "server": …, "server_port": …,
     *         "username": …, "password": …, "tls": { "server_name": … } } ] }
     * ```
     *
     * This shape is not what [XrayConfig] reads: that one keys on `protocol`, the Xray
     * spelling, so a `type`-keyed file went through every importer and matched none,
     * which is how a naive config could "not add at all" with no error to show
     * for it.
     *
     * Only mieru and naive are taken. Nothing is lost by that today, because a config in
     * this shape imports nothing at all right now; and claiming the whole file when other
     * outbound types are present would hide them rather than leave them to a parser that
     * might one day handle them.
     */
    private fun parseSingBoxOutbounds(root: JsonObject): List<Profile> {
        val outbounds = root["outbounds"] as? JsonArray ?: return emptyList()
        return outbounds.filterIsInstance<JsonObject>().mapNotNull { o ->
            val server = o.str("server")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val port = o.int("server_port")?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            val name = o.str("tag")?.takeIf { it.isNotBlank() } ?: server
            when (o.str("type")?.lowercase()) {
                "mieru" -> Profile(
                    name = name,
                    outbound = Outbound.Mieru(
                        server = server,
                        serverPort = port,
                        transport = if (o.str("transport").equals("UDP", ignoreCase = true)) "UDP" else "TCP",
                        username = o.str("username").orEmpty(),
                        password = o.str("password").orEmpty(),
                        mtu = o.int("mtu")?.takeIf { it in ShareLinks.MIERU_MTU_RANGE }
                            ?: ShareLinks.DEFAULT_MIERU_MTU,
                    ),
                )
                "naive" -> {
                    val tls = o["tls"] as? JsonObject
                    Profile(
                        name = name,
                        outbound = Outbound.Naive(
                            server = server,
                            serverPort = port,
                            // No transport field exists in this shape; naive's own default
                            // is HTTP/2 over TLS, and "quic" is only ever set explicitly.
                            proto = if (o.str("proto").equals("quic", ignoreCase = true)) "quic" else "https",
                            username = o.str("username").orEmpty(),
                            password = o.str("password").orEmpty(),
                            // server_name is the SNI, and naive takes the SNI as the host
                            // of its proxy URI. Only meaningful when it differs from the
                            // address we dial, which is what our own config builder keys
                            // its host-resolver rule on.
                            sni = tls?.str("server_name")?.takeIf { it != server }.orEmpty(),
                        ),
                    )
                }
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------ mieru ----

    private fun parseMieruJson(root: JsonObject): List<Profile> {
        val profiles = root["profiles"] as? JsonArray ?: return emptyList()
        return profiles.filterIsInstance<JsonObject>().flatMap { profile ->
            val name = profile.str("profileName").orEmpty()
            val user = profile["user"] as? JsonObject
            val username = user?.str("name").orEmpty()
            val password = user?.str("password").orEmpty()
            val mtu = profile.int("mtu")?.takeIf { it in ShareLinks.MIERU_MTU_RANGE }
                ?: ShareLinks.DEFAULT_MIERU_MTU
            val servers = profile["servers"] as? JsonArray ?: JsonArray(emptyList())
            val endpoints = servers.filterIsInstance<JsonObject>().flatMap { server ->
                // domainName wins over ipAddress: upstream ignores the IP entirely when a
                // domain is set, so preferring the IP would dial a different host than
                // mieru itself does.
                val host = server.str("domainName")?.takeIf { it.isNotBlank() }
                    ?: server.str("ipAddress").orEmpty()
                if (host.isBlank()) return@flatMap emptyList()
                val bindings = server["portBindings"] as? JsonArray ?: JsonArray(emptyList())
                bindings.filterIsInstance<JsonObject>().mapNotNull { binding ->
                    // "port" and "portRange" are alternatives, and upstream reads the
                    // range only when port is absent/zero.
                    val port = binding.int("port")?.takeIf { it != 0 }
                        ?: binding.str("portRange")?.substringBefore('-')?.trim()?.toIntOrNull()
                    port?.takeIf { it in 1..65535 }?.let { it to binding.str("protocol").orEmpty() }
                }.map { (port, protocol) ->
                    Outbound.Mieru(
                        server = host,
                        serverPort = port,
                        transport = if (protocol.equals("UDP", ignoreCase = true)) "UDP" else "TCP",
                        username = username,
                        password = password,
                        mtu = mtu,
                    )
                }
            }
            label(endpoints, name.ifBlank { endpoints.firstOrNull()?.server.orEmpty() })
        }
    }

    // ------------------------------------------------------------------ naive ----

    /**
     * naive's config. Everything that identifies the server is inside the `proxy` URI,
     * there is no host/port/sni field, because the URI's hostname is the TLS SNI.
     *
     * `host-resolver-rules` is read for exactly that reason: a config that pins an IP
     * writes `MAP <sni-host> <real-ip>` and keeps the SNI name in `proxy`. To reproduce
     * that we have to take the SNI from the URI and the address from the rule, or the
     * imported server would dial the SNI name directly and lose the pinning.
     */
    private fun parseNaiveJson(root: JsonObject, fallbackName: String): List<Profile> {
        val proxy = root.str("proxy")?.trim().orEmpty()
        if (proxy.isBlank()) return emptyList()
        val uri = runCatching { android.net.Uri.parse(proxy) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "https" && scheme != "quic") return emptyList()
        val uriHost = uri.host?.removeSurrounding("[", "]").orEmpty()
        if (uriHost.isBlank()) return emptyList()
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val userInfo = uri.userInfo.orEmpty()

        // MAP <host> <address> [, more rules]. Only the rule for our host matters.
        val mapped = root.str("host-resolver-rules")
            ?.split(',')
            ?.map { it.trim() }
            ?.firstNotNullOfOrNull { rule ->
                val parts = rule.split(Regex("\\s+"))
                if (parts.size >= 3 && parts[0].equals("MAP", ignoreCase = true) && parts[1] == uriHost) {
                    parts[2]
                } else {
                    null
                }
            }

        val outbound = Outbound.Naive(
            // When a resolver rule pinned the host, the real address is the target and
            // the URI host becomes the SNI. Without a rule they are the same thing.
            server = mapped ?: uriHost,
            serverPort = port,
            proto = if (scheme == "quic") "quic" else "https",
            username = android.net.Uri.decode(userInfo.substringBefore(':')),
            password = if (':' in userInfo) android.net.Uri.decode(userInfo.substringAfter(':')) else "",
            sni = if (mapped != null) uriHost else "",
            extraHeaders = root.str("extra-headers")?.replace("\r\n", "\n").orEmpty(),
            insecureConcurrency = root.int("insecure-concurrency") ?: 0,
        )
        return listOf(Profile(name = fallbackName.ifBlank { uriHost }, outbound = outbound))
    }

    // ------------------------------------------------------------------- yaml ----

    /**
     * mihomo / Clash.Meta `proxies:` entries of `type: mieru`.
     *
     * A bounded reader rather than a YAML library: the app carries no YAML
     * dependency, and the shape that matters here is one list of flat string maps. It
     * handles block entries (`- name: x` with indented keys), and inline flow maps
     * (`- {name: x, type: mieru}`), quoted values, and mihomo's own key normalisation
     * (case-insensitive, `_` interchangeable with `-`).
     *
     * It does not handle anchors, aliases, multi-document files, nested structures inside
     * a proxy, or block scalars, none of which appear in a mieru proxy entry. Anything
     * it cannot read yields no server rather than a wrong one.
     */
    private fun parseClashYaml(text: String): List<Profile> {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val start = lines.indexOfFirst { it.trimEnd().let { l -> l == "proxies:" || l.startsWith("proxies:") } }
        if (start < 0) return emptyList()

        val entries = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (raw in lines.drop(start + 1)) {
            val line = raw.substringBefore(" #").trimEnd()
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            val body = line.trim()
            // A non-indented, non-list line ends the proxies block (the next top-level key).
            if (indent == 0 && !body.startsWith("-")) break
            if (body.startsWith("-")) {
                current = mutableMapOf()
                entries += current
                val inline = body.removePrefix("-").trim()
                if (inline.startsWith("{")) {
                    current.putAll(parseFlowMap(inline))
                } else if (inline.isNotEmpty()) {
                    putPair(current, inline)
                }
            } else if (current != null) {
                putPair(current, body)
            }
        }

        return entries.mapNotNull(::proxyFromClash)
    }

    /**
     * One `proxies:` entry -> a profile.
     *
     * Until now only mieru was recognised here, so an ordinary Clash/mihomo file, the
     * kind every panel hands out, full of vless/vmess/trojan/ss entries, produced no
     * profiles at all and the import failed with «Не удалось разобрать файл». A 4PDA
     * report put it plainly: a dozen .yaml files tried, none accepted, while the same
     * servers imported fine from .conf.
     *
     * The entry arrives as a flat key -> string map: [parseClashYaml] does not track
     * indentation, so the children of `ws-opts:` / `grpc-opts:` / `reality-opts:` land
     * beside their parent's own keys. That is why the transport and reality fields below
     * are read by their leaf names. It cannot express two nested blocks that use the same
     * leaf name, which no real proxy entry does.
     */
    private fun proxyFromClash(entry: Map<String, String>): Profile? {
        val type = entry["type"]?.lowercase() ?: return null
        if (type == "mieru") return mieruFromClash(entry)

        val server = entry["server"]?.takeIf { it.isNotBlank() } ?: return null
        val port = entry["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val name = entry["name"].orEmpty().ifBlank { server }
        val outbound = when (type) {
            "vless" -> Outbound.Vless(
                server = server,
                serverPort = port,
                uuid = entry["uuid"].orEmpty().ifBlank { return null },
                flow = entry["flow"].orEmpty(),
                network = clashNetwork(entry),
                tls = clashTls(entry, defaultEnabled = true),
                transport = clashTransport(entry),
            )
            "vmess" -> Outbound.Vmess(
                server = server,
                serverPort = port,
                uuid = entry["uuid"].orEmpty().ifBlank { return null },
                alterId = entry["alterid"]?.toIntOrNull() ?: 0,
                // mihomo calls it `cipher`; "auto" is its own default.
                security = entry["cipher"].orEmpty().ifBlank { "auto" },
                network = clashNetwork(entry),
                tls = clashTls(entry, defaultEnabled = false),
                transport = clashTransport(entry),
            )
            "trojan" -> Outbound.Trojan(
                server = server,
                serverPort = port,
                password = entry["password"].orEmpty().ifBlank { return null },
                network = clashNetwork(entry),
                // Trojan is TLS-only, so an entry that never mentions tls still gets it.
                tls = clashTls(entry, defaultEnabled = true),
                transport = clashTransport(entry),
            )
            "ss", "shadowsocks" -> Outbound.Shadowsocks(
                server = server,
                serverPort = port,
                method = entry["cipher"].orEmpty().ifBlank { return null },
                password = entry["password"].orEmpty(),
            )
            "hysteria2", "hy2" -> Outbound.Hysteria2(
                server = server,
                serverPort = port,
                // mihomo accepts either spelling for the same field.
                password = (entry["password"] ?: entry["auth"]).orEmpty(),
                obfsType = entry["obfs"].orEmpty(),
                obfsPassword = entry["obfs-password"].orEmpty(),
                tls = clashTls(entry, defaultEnabled = true),
            )
            "tuic" -> Outbound.Tuic(
                server = server,
                serverPort = port,
                uuid = entry["uuid"].orEmpty(),
                password = entry["password"].orEmpty(),
                congestionControl = entry["congestion-controller"].orEmpty().ifBlank { "bbr" },
                udpRelayMode = entry["udp-relay-mode"].orEmpty().ifBlank { "native" },
                tls = clashTls(entry, defaultEnabled = true),
            )
            else -> null
        } ?: return null
        return Profile(name = name, outbound = outbound)
    }

    /** mihomo writes `network: ws|grpc|h2|http`; absent means plain TCP. */
    private fun clashNetwork(entry: Map<String, String>): String =
        entry["network"].orEmpty().lowercase().ifBlank { "tcp" }

    private fun clashTransport(entry: Map<String, String>): TransportSettings? =
        when (val network = clashNetwork(entry)) {
            "ws", "httpupgrade" -> TransportSettings(
                type = network,
                path = entry["path"].orEmpty(),
                // Under `ws-opts.headers` the Host header flattens to a bare `host`.
                host = entry["host"].orEmpty(),
            )
            "grpc" -> TransportSettings(
                type = "grpc",
                serviceName = entry["grpc-service-name"].orEmpty(),
            )
            "http", "h2" -> TransportSettings(
                type = "http",
                path = entry["path"].orEmpty(),
                host = entry["host"].orEmpty(),
            )
            else -> null
        }

    private fun clashTls(entry: Map<String, String>, defaultEnabled: Boolean): TlsSettings? {
        val enabled = entry["tls"]?.let { it.equals("true", ignoreCase = true) } ?: defaultEnabled
        val reality = entry["public-key"]?.takeIf { it.isNotBlank() }?.let {
            RealitySettings(publicKey = it, shortId = entry["short-id"].orEmpty())
        }
        if (!enabled && reality == null) return null
        return TlsSettings(
            enabled = true,
            // mihomo spells it `servername`; `sni` and `peer` are the older aliases and
            // panels still emit all three.
            serverName = (entry["servername"] ?: entry["sni"] ?: entry["peer"]).orEmpty(),
            insecure = entry["skip-cert-verify"].equals("true", ignoreCase = true),
            alpn = entry["alpn"].orEmpty()
                .trim()
                .removeSurrounding("[", "]")
                .split(',')
                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .filter { it.isNotEmpty() },
            utlsFingerprint = entry["client-fingerprint"].orEmpty(),
            reality = reality,
        )
    }

    private fun parseFlowMap(text: String): Map<String, String> {
        val inner = text.trim().removePrefix("{").removeSuffix("}")
        val map = mutableMapOf<String, String>()
        // Split on commas that are not inside quotes, a password may legitimately
        // contain one.
        var depth = 0
        var quote: Char? = null
        val field = StringBuilder()
        fun flush() {
            if (field.isNotBlank()) putPair(map, field.toString())
            field.setLength(0)
        }
        for (ch in inner) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null
                    field.append(ch)
                }
                ch == '"' || ch == '\'' -> { quote = ch; field.append(ch) }
                ch == '{' || ch == '[' -> { depth++; field.append(ch) }
                ch == '}' || ch == ']' -> { depth--; field.append(ch) }
                ch == ',' && depth == 0 -> flush()
                else -> field.append(ch)
            }
        }
        flush()
        return map
    }

    private fun putPair(map: MutableMap<String, String>, text: String) {
        val idx = text.indexOf(':')
        if (idx <= 0) return
        val key = text.substring(0, idx).trim().lowercase().replace('_', '-')
        val value = text.substring(idx + 1).trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
        map[key] = value
    }

    private fun mieruFromClash(entry: Map<String, String>): Profile? {
        if (!entry["type"].equals("mieru", ignoreCase = true)) return null
        val server = entry["server"].orEmpty()
        if (server.isBlank()) return null
        // port and port-range are mutually exclusive upstream; the range's first port is
        // the one dialled.
        val port = entry["port"]?.toIntOrNull()
            ?: entry["port-range"]?.substringBefore('-')?.trim()?.toIntOrNull()
            ?: return null
        if (port !in 1..65535) return null
        return Profile(
            name = entry["name"].orEmpty().ifBlank { server },
            outbound = Outbound.Mieru(
                server = server,
                serverPort = port,
                // mihomo compares this literally against "TCP"/"UDP".
                transport = if (entry["transport"].equals("UDP", ignoreCase = true)) "UDP" else "TCP",
                username = entry["username"].orEmpty(),
                password = entry["password"].orEmpty(),
                // Not representable in mihomo YAML at all, mihomo never sets mieru's
                // MTU, so an imported entry takes the default rather than inventing one.
                mtu = ShareLinks.DEFAULT_MIERU_MTU,
            ),
        )
    }

    // ---------------------------------------------------------------- helpers ----

    private fun label(outbounds: List<Outbound>, base: String): List<Profile> =
        outbounds.mapIndexed { index, outbound ->
            Profile(
                name = if (outbounds.size > 1) "$base #${index + 1}" else base,
                outbound = outbound,
            )
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = when (val v = this[key]) {
        is JsonPrimitive -> v.intOrNull ?: v.contentOrNull?.toIntOrNull()
        else -> null
    }
}
