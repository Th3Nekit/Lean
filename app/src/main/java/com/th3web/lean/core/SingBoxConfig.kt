package com.th3web.lean.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.th3web.lean.core.plugin.PluginSession
import com.th3web.lean.data.RoutingMode
import com.th3web.lean.data.Settings
import com.th3web.lean.data.SettingsDefaults
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.TlsSettings
import com.th3web.lean.data.model.TransportSettings

/**
 * Builds a configuration for the pinned Neko sing-box 1.12.x core for a single
 * selected [Profile], honoring user [Settings].
 *
 * This is a pure function (no Android dependencies), so it can be unit-tested on
 * the JVM and the output validated in CI with `sing-box check`.
 *
 * Schema notes for the 1.12+ format used here (differs from older 1.10/1.11):
 *  - DNS uses the typed server format ({ "type": "https" | "udp", ... }).
 *  - TUN inbound uses the unified "address" list (not inet4_address/inet6_address).
 *  - DNS hijack uses route actions. Sniff settings use the pinned Neko inbound
 *    contract so sniff_override_destination retains its native semantics.
 *  - Outbound server domains are resolved via route.default_domain_resolver,
 *    which 1.12+ requires when any server is a hostname.
 *
 * Tags are stable: the proxy is "proxy", direct is "direct", DNS servers are
 * "dns-remote" / "dns-direct".
 */
object SingBoxConfig {

    // TUN interface parameters. These drive the [TunOptions] the core later
    // hands back to PlatformInterface.openTun() to build the VpnService.
    const val TUN_INET4 = "172.19.0.1/30"
    const val TUN_INET6 = "fdfe:dcba:9876::1/126"
    const val TUN_MTU = 9000
    const val TUN_STACK = "gvisor"
    private const val TUN_MIN_MTU = 1280

    // Default WireGuard inner-interface MTU (the seed for Settings.wgMtu). 1280 is
    // the IPv6 minimum, it never fragments on any path, including mobile (whose
    // path MTU is already below 1500), which is the safest default against the #1
    // cause of "WG is slow". The user can raise it (e.g. 1408/1420) for more
    // throughput on a clean network via Connection settings. The effective value
    // comes from Settings.wgMtu, applied to both the WG endpoint mtu and the
    // matched TUN MTU (see [tunMtuFor]), so app packets fit the tunnel exactly.
    const val WG_DEFAULT_MTU = 1280

    /** «Отпечаток TLS → Выключено»: send the core's own ClientHello, mimic nothing. */
    const val UTLS_OFF = "off"

    private const val TAG_PROXY = "proxy"
    /** Tag of the local listener the proxy modes expose. */
    private const val TAG_PROXY_IN = "proxy-in"
    private const val TAG_AUTO = "auto"
    private const val TAG_DIRECT = "direct"
    private const val TAG_DNS_REMOTE = "dns-remote"
    private const val TAG_DNS_DIRECT = "dns-direct"
    private const val TAG_DNS_LOCAL = "dns-local"
    private const val TAG_DNS_FAKE = "dns-fake"

    /** Where the external protocol helpers listen, and where they dial the core. */
    private const val LOCALHOST = "127.0.0.1"

    fun build(
        profile: Profile,
        settings: Settings,
        smoke: Boolean = false,
    ): JsonObject = build(listOf(profile), settings, smoke)

    /**
     * Builds a config for a *group* of profiles. With a single profile this is the
     * classic single-`proxy` outbound. With several, the core measures each node's
     * latency itself (`urltest`, tag "auto"), and a `selector` (tag "proxy",
     * default "auto") fronts it: this is the real "Авто · быстрейший" pick. The
     * choice itself is persisted by the app (Settings.selectedProfileId), not by the
     * core: `store_selected` is absent from [experimental], so the core
     * starts every session from its own default and the app is the single source of
     * truth for what the user picked.
     */
    fun build(
        profiles: List<Profile>,
        settings: Settings,
        smoke: Boolean = false,
        plugins: Map<String, PluginPorts> = emptyMap(),
    ): JsonObject = buildJsonObject {
        require(profiles.isNotEmpty()) { "SingBoxConfig.build: empty profile list" }
        require(profiles.none { (it.outbound as? Outbound.WireGuard)?.awg != null }) {
            "AmneziaWG должен запускаться отдельным движком Amnezia Go"
        }
        // Drop duplicate ids: multi-profile tags are "node-<id>", so a duplicate id
        // (a corrupt store / crafted backup) would emit duplicate tags and sing-box
        // rejects the whole config. distinctBy keeps the first and is a no-op for a
        // normal (unique) list, graceful degradation, never a hard failure.
        val profiles = profiles.distinctBy { it.id }
        put("log", buildJsonObject {
            put("level", settings.logLevel)
            put("timestamp", true)
        })
        val fakeDnsEnabled = settings.fakeDns && !smoke
        put("dns", dns(settings, fakeDnsEnabled))
        // `smoke` swaps the TUN inbound for a loopback mixed inbound so the config
        // can be `sing-box run`-tested in CI (no NET_ADMIN / TUN device needed),
        // which catches start-time errors that `sing-box check` does not.
        putJsonArray("inbounds") {
            // «TUN | Прокси | TUN + Прокси». In proxy-only there is no TUN
            // inbound at all: that is the mechanism: the core asks the platform
            // to open a TUN when it starts that inbound, so with none emitted
            // VpnTunController.openTun is never called and VpnService.establish never
            // happens. Nothing is captured system-wide; only apps pointed at the listener
            // below go through. The service still runs in the foreground, because it is
            // the core that has to keep running.
            if (smoke) {
                add(smokeInbound())
            } else {
                if (usesTun(settings)) add(tunInbound(settings, tunMtuFor(profiles, settings)))
                if (usesProxy(settings)) add(proxyInbound(settings))
            }
            // One mapping inbound per helper-backed profile. The helper dials this
            // instead of the real server, and the core forwards it on a protected
            // socket, see PluginSession for why that detour is mandatory.
            profiles.forEach { p ->
                plugins[p.id]?.let { ports -> add(pluginMappingInbound(p, ports)) }
            }
        }
        // WireGuard is a top-level "endpoints" entry, not an "outbounds" one, but
        // its tag shares the flat outbound tag namespace, so it feeds urltest /
        // selector / route.final exactly like a regular outbound. Both arrays use
        // the same tag scheme ("node-<id>" in multi/auto, TAG_PROXY when single).
        putJsonArray("outbounds") {
            if (profiles.size > 1) {
                // Stable per-profile tags ("node-<profileId>", not "node-<index>")
                // so the UI can map the core's selected/urltest node back to the
                // exact server even after the list is reconciled/reordered/removed
                // while connected, index tags pointed at whatever now sits at that
                // slot, mislabelling the live node (see HomeViewModel.autoStatus).
                val tags = profiles.map { "node-${it.id}" }
                // Only NON-WireGuard profiles go to "outbounds"; WG ones are emitted
                // into "endpoints" below (with the matching "node-<id>" tag).
                profiles.forEachIndexed { i, p ->
                    if (p.outbound !is Outbound.WireGuard) {
                        add(pluginOrDirect(p, settings, tags[i], plugins))
                    }
                }
                // The urltest group is the speed test: race only the servers the
                // user did not exclude. Every node (incl. WG endpoints) still gets a
                // selector entry, so an excluded server stays manually selectable
                // it just never wins (or slows down) the auto pick. If the user
                // excluded everything, fall back to all nodes: an empty urltest
                // outbounds list is invalid and would fail `sing-box check`.
                val testTags = profiles.filterNot { it.excludedFromTest }.map { "node-${it.id}" }
                add(urltest(testTags.ifEmpty { tags }, settings.pingUrl))
                add(selector(tags))
            } else {
                val only = profiles.first()
                if (only.outbound !is Outbound.WireGuard) {
                    add(pluginOrDirect(only, settings, TAG_PROXY, plugins))
                }
            }
            add(buildJsonObject { put("type", "direct"); put("tag", TAG_DIRECT) })
        }
        // Emit "endpoints" only when there is at least one WireGuard profile: an
        // empty endpoints array is rejected by `sing-box check`.
        val wgProfiles = profiles.filter { it.outbound is Outbound.WireGuard }
        if (wgProfiles.isNotEmpty()) {
            putJsonArray("endpoints") {
                if (profiles.size > 1) {
                    wgProfiles.forEach { p ->
                        add(wireguardEndpoint(p.outbound as Outbound.WireGuard, "node-${p.id}", settings.wgMtu))
                    }
                } else {
                    add(wireguardEndpoint(profiles.first().outbound as Outbound.WireGuard, TAG_PROXY, settings.wgMtu))
                }
            }
        }
        put("route", route(settings, profiles.filter { plugins.containsKey(it.id) }.map { pluginMappingTag(it.id) }))
        put("experimental", experimental())
    }

    /** Convenience: build and serialize to a string for the core / `sing-box check`. */
    fun buildJson(
        profile: Profile,
        settings: Settings,
        smoke: Boolean = false,
    ): String = buildJson(listOf(profile), settings, smoke)

    fun buildJson(
        profiles: List<Profile>,
        settings: Settings,
        smoke: Boolean = false,
        plugins: Map<String, PluginPorts> = emptyMap(),
    ): String =
        PrettyJson.encodeToString(JsonObject.serializer(), build(profiles, settings, smoke, plugins))

    /**
     * A throwaway, headless config for exactly one [outbound], no TUN, no other
     * inbound, no rule-sets, no cache_file. This is Lean's «URL Test» ping type:
     * matches NekoBox's own per-profile test config (`ConfigBuilder.buildConfig(profile,
     * forTest=true)`), which likewise emits zero inbounds and a bare direct-DNS-only
     * bootstrap. It wants a real HTTP round trip through the outbound's actual
     * protocol handshake (TLS/Reality/VMess/etc.), not a raw socket probe, so the
     * instance needs nothing beyond "dial this one outbound and answer its own DNS."
     *
     * Not valid for WireGuard: it has no "outbound" form in sing-box (only an
     * "endpoints" one), callers must check before calling this (see UrlTestPinger).
     */
    fun buildUrlTestJson(outbound: Outbound, settings: Settings): String =
        PrettyJson.encodeToString(JsonObject.serializer(), buildUrlTestConfig(outbound, settings))

    private fun buildUrlTestConfig(o: Outbound, settings: Settings): JsonObject = buildJsonObject {
        require(o !is Outbound.WireGuard) { "URL Test не поддерживает WireGuard/AmneziaWG" }
        put("log", buildJsonObject { put("level", "error"); put("timestamp", false) })
        put("dns", buildJsonObject {
            // Same bootstrap-only shape NekoBox's forTest config uses (dns.final_ =
            // "dns-direct", no dns-remote entry at all): there is no tunnel here for a
            // remote resolver to hide behind, and the outbound's own server hostname is
            // the only thing that ever needs resolving.
            putJsonArray("servers") { add(directDnsServer(TAG_DNS_DIRECT, settings.directDns, serverDialStrategy(settings))) }
            put("final", TAG_DNS_DIRECT)
            put("strategy", answerStrategy(settings))
        })
        putJsonArray("outbounds") {
            add(outbound(o, settings, TAG_PROXY))
            add(buildJsonObject { put("type", "direct"); put("tag", TAG_DIRECT) })
        }
        put("route", buildJsonObject {
            putJsonObject("default_domain_resolver") {
                put("server", TAG_DNS_DIRECT)
                put("strategy", serverDialStrategy(settings))
            }
            put("final", TAG_PROXY)
            // Required even though this instance has no TUN of its own. The flag is the
            // only thing that makes sing-box attach a Control to its sockets: the dialer
            // takes the AutoDetectInterface branch and appends ProtectFunc, which reaches
            // LeanNativePlatform.autoDetectInterfaceControl and the service's protect().
            //
            // Without it the probe follows the system default route, which, while the
            // tunnel is up, is the TUN. The probe then dials the server from inside the
            // tunnel it is measuring, and on Hysteria2 (one QUIC socket for every stream)
            // cancelling that shared dial kills all traffic, not just the probe.
            put("auto_detect_interface", true)
        })
    }

    // ---------------------------------------------------------------- DNS ----

    /**
     * Strategy for the answers handed back to apps inside the tunnel, the user's «Тип
     * IP» choice, else derived from the IPv6 toggle.
     *
     * On "auto" it must follow the TUN: while IPv6 is off the TUN carries no v6 address
     * and no v6 route, so handing an app a AAAA record points it at a road that was
     * never built and the connection simply hangs. Hence A-only until the toggle is on.
     */
    private fun answerStrategy(settings: Settings): String = when (settings.ipStrategy) {
        "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only" -> settings.ipStrategy
        else -> if (settings.ipv6) "prefer_ipv4" else "ipv4_only" // "auto"
    }

    /**
     * Strategy for dialing the proxy server's own hostname, a different question from
     * [answerStrategy], and the reference client keeps the two apart as well (only its
     * server strategy defaults to prefer_ipv4; its DNS strategy follows the IPv6 mode).
     *
     * This dial happens over the underlying physical network, not inside the v4-only
     * TUN, so accepting AAAA costs nothing here and is what lets a v6-only carrier link
     * reach the server at all. An explicit «Тип IP» choice still wins, a user who asked
     * for IPv4-only means it everywhere.
     */
    private fun serverDialStrategy(settings: Settings): String = when (settings.ipStrategy) {
        "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only" -> settings.ipStrategy
        else -> SettingsDefaults.SERVER_DIAL_STRATEGY // "auto"
    }

    private fun dns(settings: Settings, fakeDnsEnabled: Boolean): JsonObject = buildJsonObject {
        putJsonArray("servers") {
            // Remote: resolved through the proxy (no DNS leak). Direct: bootstraps the
            // proxy server's own address (see default_domain_resolver).
            //
            // dns-remote must carry an explicit resolver for its own hostname. It defaults
            // to a DoH endpoint named by hostname, dialed through the proxy; without this
            // the lookup of that name re-enters the DNS module, matches nothing, falls to
            // `final` (dns-remote itself), and the core aborts it as a loopback, after
            // which the tunnel comes up and resolves nothing.
            // route.default_domain_resolver does not cover this slot; the outbound:any
            // rule below is a second line of defence, not a substitute.
            add(
                dnsServer(
                    TAG_DNS_REMOTE,
                    settings.remoteDns,
                    detour = TAG_PROXY,
                    resolverTag = TAG_DNS_DIRECT,
                    resolverStrategy = serverDialStrategy(settings),
                ),
            )
            // The direct resolver carries no detour, and must not. In the 1.12+ typed
            // format a server with no `detour` is dialed by the default dialer, outside
            // the routing engine, so its query cannot fall through to route.final
            // ("proxy"), and loop while the proxy is still down. A detour to the plain
            // {"type":"direct"} outbound is worse than useless: since 1.13 the core
            // refuses to start with "detour to an empty direct outbound makes no sense".
            //
            // [directDnsServer], not the generic [dnsServer]: this is the bootstrap
            // resolver route.default_domain_resolver points at, so its own upstream host
            // must not itself need resolving, a DoH URL on a hostname would depend on
            // dns-direct, a cycle the core rejects. directDnsServer falls back to a
            // plain-IP default when the configured spec is not dialable without a
            // resolver.
            //
            // The platform resolver, always present under its own tag, NekoBox's
            // "dns-local". It is what lets dns-direct below be a DoH on a hostname
            // (the reference client's own default is https://223.5.5.5/dns-query):
            // that host is resolved by dns-local instead of by dns-direct itself,
            // which would be the cycle the core rejects. Without this slot the only
            // safe bootstrap was "local", so any hostname DoH the user picked was
            // silently downgraded to the system resolver.
            add(localDnsServer(TAG_DNS_LOCAL))
            add(directDnsServer(TAG_DNS_DIRECT, settings.directDns, serverDialStrategy(settings)))
            if (fakeDnsEnabled) {
                add(buildJsonObject {
                    put("type", "fakeip")
                    put("tag", TAG_DNS_FAKE)
                    // Both ranges are declared like the reference client does. The v6
                    // half cannot leak into a v4-only TUN even so: [answerStrategy]
                    // pins the DNS module to A-only while IPv6 is off, so no AAAA is
                    // ever answered and no fc00::/18 address is ever minted. (The
                    // typed fakeip server takes only these two fields: it has no
                    // per-server `strategy`, unlike the legacy format.)
                    put("inet4_range", "198.18.0.0/15")
                    put("inet6_range", "fc00::/18")
                })
            }
        }
        putJsonArray("rules") {
            // «DNS для WireGuard → напрямую»: every query issued by an outbound is
            // answered by the direct resolver.
            //
            // It no longer breaks any loop, dns-remote resolves its own hostname through
            // its explicit domain_resolver, and an outbound's server address goes through
            // route.default_domain_resolver. What it decides now is where a WireGuard
            // endpoint's destination lookups go, and only WireGuard's: a WG tunnel carries
            // packets rather than names, so the client resolves each destination itself,
            // while VLESS/Trojan/Shadowsocks hand the proxy a hostname.
            //
            // That means the rule sends every site a WG user visits to the direct
            // resolver, in the clear from their real address. Off by default for that
            // reason; on for local-CDN answers.
            if (!settings.wgDnsThroughTunnel) {
                add(buildJsonObject {
                    putJsonArray("outbound") { add("any") }
                    put("action", "route")
                    put("server", TAG_DNS_DIRECT)
                })
            }
            if (fakeDnsEnabled) {
                // must precede the fakeip rule below. The fakeip transport answers A and
                // AAAA only and rejects every other query type. Browsers and Android's
                // own resolver ask for the HTTPS record (RR 65) of practically every
                // hostname before connecting, so without this rule each of those takes
                // the fakeip path, errors, and the client waits out its timeout, seconds
                // per name, which is what «подключено, но всё грузится вечно» is.
                //
                // NOERROR with an empty answer is the honest reply: the name has no HTTPS
                // record, so the client falls through to A/AAAA immediately. NXDOMAIN
                // would deny the name itself and SERVFAIL invites retries.
                //
                // Scoped to the fakeip case: with fakeip off the HTTPS record resolves
                // normally through dns-remote and Encrypted Client Hello keeps working.
                add(buildJsonObject {
                    putJsonArray("inbound") { add("tun-in") }
                    putJsonArray("query_type") { add("HTTPS"); add("SVCB") }
                    put("action", "predefined")
                    put("rcode", "NOERROR")
                })
                add(buildJsonObject {
                    putJsonArray("inbound") { add("tun-in") }
                    put("action", "route")
                    put("server", TAG_DNS_FAKE)
                    put("disable_cache", true)
                })
            }
        }
        put("final", TAG_DNS_REMOTE)
        put("strategy", answerStrategy(settings))
        put("independent_cache", true)
    }

    /**
     * Maps a DNS spec string to a typed sing-box DNS server. Accepts:
     *  - `https://host[:port][/path]` -> { type: https, server: host, server_port, path }
     *  - `tls://host[:port]` -> { type: tls, server: host, server_port }
     *  - `quic://host[:port]` -> { type: quic, server: host, server_port }
     *  - `h3://host[:port][/path]` -> { type: h3, server: host, server_port, path }
     *  - bare `1.1.1.1[:port]` -> { type: udp, server: 1.1.1.1, server_port }
     *
     * In the 1.12+ typed-server format the port belongs in `server_port`, not
     * glued to `server` (which is parsed as the bare hostname), so every form,
     * not just https://, must split a trailing :port. IPv6 literals are written
     * bracketed (`[2606:4700:4700::1111]:853`), so the host's own colons are not
     * mistaken for the port separator.
     */
    private fun dnsServer(
        tag: String,
        spec: String,
        detour: String?,
        resolverTag: String? = null,
        resolverStrategy: String? = null,
    ): JsonObject {
        val s = spec.trim()
        // Blank or scheme-only ("https://") spec has no dialable host → emitting
        // {"type":"udp","server":""} makes sing-box reject the whole config (the
        // tunnel won't start for any profile). Fall back to the system "local"
        // resolver, graceful degradation that always starts. directDnsServer already
        // does this for the bootstrap slot; this covers the remote slot too.
        if (bootstrapHost(s) == null) return localDnsServer(tag)
        return buildJsonObject {
            put("tag", tag)
            when {
                s.startsWith("https://") -> {
                    val (hostPort, path) = splitAuthorityPath(s.removePrefix("https://"))
                    val (host, port) = splitHostPort(hostPort)
                    put("type", "https")
                    put("server", host)
                    if (port != null) put("server_port", port)
                    if (path.isNotEmpty() && path != "/" && path != "/dns-query") put("path", path)
                }
                s.startsWith("tls://") -> {
                    val (host, port) = splitHostPort(splitAuthorityPath(s.removePrefix("tls://")).first)
                    put("type", "tls"); put("server", host)
                    if (port != null) put("server_port", port)
                }
                s.startsWith("quic://") -> {
                    val (host, port) = splitHostPort(splitAuthorityPath(s.removePrefix("quic://")).first)
                    put("type", "quic"); put("server", host)
                    if (port != null) put("server_port", port)
                }
                s.startsWith("h3://") -> {
                    val (hostPort, path) = splitAuthorityPath(s.removePrefix("h3://"))
                    val (host, port) = splitHostPort(hostPort)
                    put("type", "h3"); put("server", host)
                    if (port != null) put("server_port", port)
                    if (path.isNotEmpty() && path != "/" && path != "/dns-query") put("path", path)
                }
                else -> {
                    val (host, port) = splitHostPort(s)
                    put("type", "udp"); put("server", host)
                    if (port != null) put("server_port", port)
                }
            }
            if (detour != null) put("detour", detour)
            // Only a hostname needs resolving; an IP-literal server would name a resolver
            // it never consults, and pointing one at itself is the cycle the core rejects.
            if (resolverTag != null && bootstrapHost(s)?.let { !isIpLiteral(it) } == true) {
                putJsonObject("domain_resolver") {
                    put("server", resolverTag)
                    if (resolverStrategy != null) put("strategy", resolverStrategy)
                }
            }
        }
    }

    /**
     * The bootstrap (dns-direct) server that route.default_domain_resolver targets.
     *
     * Unlike the generic [dnsServer] this slot must be dialable without domain
     * resolution, since the only resolver available to it is itself:
     *  - "" / "local" / "system" -> the Android platform resolver ([localDnsServer]),
     *    the default: the underlying network's own DNS, no upstream IP needed;
     *  - a bare IP -> a plain udp server;
     *  - a DoH/DoT/quic/h3 URL on an IP literal -> kept verbatim;
     *  - a DoH/DoT/quic/h3 URL on a hostname -> would have to resolve its own host
     *    through dns-direct, a cycle sing-box rejects by refusing to start the whole
     *    config, so it falls back to [localDnsServer]. A config that starts beats a
     *    DoH preference that can never come up.
     */
    private fun directDnsServer(tag: String, spec: String, strategy: String): JsonObject {
        val s = spec.trim()
        // Empty or the explicit "local"/"system" sentinel → the Android platform
        // resolver. This is the DEFAULT bootstrap (Settings.directDns defaults to
        // "local"): it resolves the proxy / WG-endpoint hostname through the
        // underlying network's own DNS servers (exposed through
        // LocalDNSTransport), so it works without a hardcoded public resolver.
        // A plain-UDP public resolver is widely refused on Russian mobile networks, and
        // a refused bootstrap black-holes the WG endpoint's hostname and with it the whole
        // tunnel. "local" uses whatever DNS the network hands out, which always resolves a
        // plain public domain.
        if (s.isEmpty() || s.equals("local", ignoreCase = true) || s.equals("system", ignoreCase = true)) {
            return localDnsServer(tag)
        }
        val host = bootstrapHost(s) ?: return localDnsServer(tag)
        val server = dnsServer(tag, s, detour = null)
        if (isIpLiteral(host)) {
            // Host is an IP literal (plain 1.1.1.1, or a DoH/DoT URL on an IP),
            // dialable without resolution, so it needs no resolver of its own.
            return server
        }
        // A hostname spec (e.g. https://dns.google/dns-query) cannot resolve itself:
        // pointing it at default_domain_resolver would be dns-direct → dns-direct, the
        // cycle the core rejects, taking the whole config down. Hand it the platform
        // resolver instead, which is address_resolver=dns-local spelled in the 1.12+
        // typed form. Degrading to "local" here would silently discard a hostname DoH the
        // user chose.
        return JsonObject(
            server + ("domain_resolver" to buildJsonObject {
                put("server", TAG_DNS_LOCAL)
                put("strategy", strategy)
            }),
        )
    }

    /**
     * The Android platform/system resolver ({"type":"local"}, sing-box >= 1.12).
     * Resolves through the underlying network's DNS (via the PlatformInterface), so
     * it needs no upstream IP and is never blocked the way a fixed public resolver
     * can be. Carries no detour, dialed by the core's default dialer, outside the
     * routing engine, so it can't loop through the not-yet-up proxy.
     */
    private fun localDnsServer(tag: String): JsonObject = buildJsonObject {
        put("tag", tag)
        put("type", "local")
    }

    /**
     * Extracts the host of a DNS spec for bootstrap validation, regardless of
     * scheme. Returns null only for an empty spec. IPv6 literals come back
     * unbracketed (e.g. `2606:4700:4700::1111`).
     */
    private fun bootstrapHost(spec: String): String? {
        if (spec.isEmpty()) return null
        val authority = when {
            spec.startsWith("https://") -> spec.removePrefix("https://")
            spec.startsWith("tls://") -> spec.removePrefix("tls://")
            spec.startsWith("quic://") -> spec.removePrefix("quic://")
            spec.startsWith("h3://") -> spec.removePrefix("h3://")
            else -> spec
        }
        val hostPort = splitAuthorityPath(authority).first
        val host = splitHostPort(hostPort).first
        return host.ifEmpty { null }
    }

    /** Splits an `authority[/path]` into (authority, path) where path keeps its leading '/'. */
    private fun splitAuthorityPath(s: String): Pair<String, String> {
        val slash = s.indexOf('/')
        return if (slash < 0) s to "" else s.substring(0, slash) to s.substring(slash)
    }

    /**
     * Splits `host[:port]` into (host, port?), handling bracketed IPv6
     * (`[::1]:853` -> `::1`, 853), and a bare unbracketed IPv6 literal
     * (`2606:4700:4700::1111`, which has no port and whose colons must not be
     * read as a port separator). port is null when absent or non-numeric.
     */
    private fun splitHostPort(s: String): Pair<String, Int?> {
        if (s.startsWith("[")) {
            // Bracketed IPv6: [host]:port  or  [host]
            val close = s.indexOf(']')
            if (close > 0) {
                val host = s.substring(1, close)
                val rest = s.substring(close + 1)
                val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
                return host to port
            }
            return s to null
        }
        // Unbracketed: a single ':' is host:port; 2+ colons is a bare IPv6 literal
        // with no port (e.g. "2606:4700:4700::1111").
        val firstColon = s.indexOf(':')
        if (firstColon < 0) return s to null
        if (s.indexOf(':', firstColon + 1) >= 0) return s to null // bare IPv6, no port
        val host = s.substring(0, firstColon)
        val port = s.substring(firstColon + 1).toIntOrNull()
        return (if (port != null) host else s) to port
    }

    /** True when [host] is an IPv4 or IPv6 literal (no DNS resolution needed to dial it). */
    private fun isIpLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        // IPv6: contains a colon (already unbracketed by the callers).
        if (host.contains(':')) {
            return host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        }
        // IPv4: exactly four 0-255 dotted octets.
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
    }

    // ------------------------------------------------------------- INBOUND ----

    /**
     * The TUN MTU to advertise to apps. For any group containing a WireGuard or
     * AmneziaWG node it is lowered to min([Settings.tunMtu], [Settings.wgMtu]), so app packets
     * fit the WG tunnel without fragmenting, the WireGuard speed fix. Previously this
     * only matched a single WG profile, so an "Авто" group with a WG member kept TUN
     * at 9000 over a 1280 tunnel → fragmentation/stalls on large transfers. Every WG
     * endpoint in the group is emitted with the same settings.wgMtu, so one min() is
     * correct. Invalid restored values are normalized to the supported range.
     */
    private fun tunMtuFor(profiles: List<Profile>, settings: Settings): Int {
        val tunMtu = settings.tunMtu.coerceIn(TUN_MIN_MTU, TUN_MTU)
        return if (profiles.any { it.outbound is Outbound.WireGuard }) {
            minOf(tunMtu, settings.wgMtu.coerceIn(TUN_MIN_MTU, TUN_MTU))
        } else {
            tunMtu
        }
    }

    private fun tunInbound(settings: Settings, mtu: Int): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        putJsonArray("address") {
            add(TUN_INET4)
            if (settings.ipv6) add(TUN_INET6)
        }
        put("mtu", mtu)
        put("auto_route", true)
        put("strict_route", true)
        // Same as the reference client: one NAT mapping per source regardless of
        // destination, which is what lets peer-to-peer UDP (games, calls) work
        // through the tunnel instead of getting a fresh mapping per remote.
        put("endpoint_independent_nat", true)
        put(
            "stack",
            settings.tunStack.trim().lowercase().takeIf { it in SUPPORTED_TUN_STACKS } ?: TUN_STACK,
        )
        if (settings.sniffEnabled) put("sniff", true)
        if (settings.sniffEnabled && settings.sniffOverrideDestination) {
            put("sniff_override_destination", true)
        }
        if (settings.sniffResolveDestination) put("domain_strategy", answerStrategy(settings))
        // Per-app split tunnel is not emitted into the tun config. A
        // config-level include_package/exclude_package makes the core enforce the package
        // filter itself, which on the gVisor/"mixed" stack requires resolving each flow's
        // owning package via the platform findConnectionOwner, unreliable on Android
        // API29+, so it silently drops whitelisted flows ("connects, 0 B/s"). It is applied
        // instead at the OS level in LeanVpnService.openTun via
        // addAllowedApplication/addDisallowedApplication (the v2rayNG / SFA approach), which
        // needs no owner resolution. So nothing per-app is written here.
    }

    /** Does this mode capture the whole device through a TUN? */
    internal fun usesTun(settings: Settings): Boolean =
        settings.serviceMode != SettingsDefaults.SERVICE_MODE_PROXY

    /** Does this mode expose a local listener apps can be pointed at? */
    internal fun usesProxy(settings: Settings): Boolean =
        settings.serviceMode == SettingsDefaults.SERVICE_MODE_PROXY ||
            settings.serviceMode == SettingsDefaults.SERVICE_MODE_VPN_PROXY

    /**
     * The local SOCKS5 + HTTP listener for the proxy modes.
     *
     * "mixed" so one port serves both, which is what a browser or a terminal expects to
     * find. It binds 127.0.0.1 unless the user opens it: on 0.0.0.0 it is an
     * open proxy for everyone on the same Wi-Fi, not a default anyone should
     * receive by surprise.
     */
    private fun proxyInbound(settings: Settings): JsonObject = buildJsonObject {
        put("type", "mixed")
        put("tag", TAG_PROXY_IN)
        put("listen", if (settings.proxyAllowLan) "0.0.0.0" else LOCALHOST)
        put("listen_port", settings.proxyPort)
    }

    /** Loopback inbound used only for CI `sing-box run` smoke tests (no TUN). */
    private fun smokeInbound(): JsonObject = buildJsonObject {
        put("type", "mixed")
        put("tag", "smoke-in")
        put("listen", "127.0.0.1")
        put("listen_port", 12345)
    }

    // ------------------------------------------------------------- OUTBOUND ---

    /**
     * The local ports one helper-backed profile was assigned.
     *
     * [socksPort] is where the helper listens (the core dials it); [mappingPort] is where
     * the core listens for the helper's own outbound connection.
     */
    data class PluginPorts(val socksPort: Int, val mappingPort: Int)

    /**
     * The outbound for [profile], either the protocol itself, or, for the two protocols
     * that run as an external process, a plain `socks` outbound pointed at the helper.
     *
     * A helper-backed profile with no allocated ports falls through to [outbound], which
     * throws for those types. That is deliberate: silently emitting something else would
     * produce a tunnel that connects and carries nothing.
     */
    private fun pluginOrDirect(
        profile: Profile,
        settings: Settings,
        tag: String,
        plugins: Map<String, PluginPorts>,
    ): JsonObject {
        val ports = plugins[profile.id]
        return if (ports != null && PluginSession.isPluginOutbound(profile.outbound)) {
            buildJsonObject {
                put("type", "socks")
                put("tag", tag)
                put("server", LOCALHOST)
                put("server_port", ports.socksPort)
                put("version", "5")
            }
        } else {
            outbound(profile.outbound, settings, tag)
        }
    }

    /**
     * The core-side inbound a helper connects to instead of the real server.
     *
     * `override_address`/`override_port` rewrite the destination to the real server, and
     * the route rule keyed on this inbound's tag sends it to TAG_DIRECT, the direct
     * outbound dials with a protected socket, which is the only way out of our own tun.
     */
    private fun pluginMappingInbound(profile: Profile, ports: PluginPorts): JsonObject =
        buildJsonObject {
            if (needsSocksMapping(profile.outbound)) {
                // A SOCKS listener, not a fixed redirect. mieru and naive dial one server,
                // so their mapping inbound can simply override the destination. olcRTC
                // dials whatever its provider resolves to, the meeting service, its API,
                // a DNS server, and Xray must keep the real address in its own config
                // (its SNI and Host default to whatever it is told to dial), so both are
                // handed this as an upstream proxy and name each destination themselves.
                // The route rule above still sends this inbound straight to `direct`,
                // which is what gets the helper's traffic out past the tunnel it is
                // providing.
                put("type", "mixed")
                put("tag", pluginMappingTag(profile.id))
                put("listen", LOCALHOST)
                put("listen_port", ports.mappingPort)
            } else {
                put("type", "direct")
                put("tag", pluginMappingTag(profile.id))
                put("listen", LOCALHOST)
                put("listen_port", ports.mappingPort)
                put("override_address", profile.outbound.server)
                put("override_port", profile.outbound.serverPort)
            }
        }

    private fun pluginMappingTag(profileId: String): String = "plugin-map-$profileId"

    /**
     * Whether this helper names its own destinations (SOCKS mapping) or always dials the
     * one server it was configured with (fixed redirect).
     */
    private fun needsSocksMapping(outbound: Outbound): Boolean = when (outbound) {
        is Outbound.Olcrtc -> true
        is Outbound.Vless -> PluginSession.needsXray(outbound)
        else -> false
    }

    private fun outbound(o: Outbound, settings: Settings, tag: String): JsonObject = buildJsonObject {
        put("tag", tag)
        put("server", o.server)
        put("server_port", o.serverPort)
        when (o) {
            is Outbound.Vless -> {
                // Same contract as the helper-only protocols below, for the subset of
                // VLESS that is one: an xhttp (or encrypted) node reaching this function
                // means it got no port allocation, and the core would emit an outbound
                // that drops its transport on the floor, connects, carries nothing.
                if (PluginSession.needsXray(o)) {
                    error("VLESS ${o.transport?.type ?: o.encryption} требует внешний процесс — используйте pluginOrDirect")
                }
                put("type", "vless")
                put("uuid", o.uuid)
                // Flow (xtls-rprx-vision) needs raw TCP; it's incompatible only with a
                // real STREAM transport (ws/grpc/http), sing-box rejects "flow + transport".
                // Gate on the emitted transport, not the raw field: a plain tcp node may carry
                // a non-null TransportSettings(type="tcp") that emits nothing, and vision must
                // keep its flow there, dropping it leaves the reality node handshaking fine
                // but passing zero traffic ("connects, 0 B/s"). transport()==null ⇒ tcp/none.
                val vlessTransport = transport(o.transport)
                if (o.flow.isNotEmpty() && vlessTransport == null) put("flow", o.flow)
                // xray-compatible default; emitted explicitly so UDP-over-VLESS
                // (QUIC browsing, calls) isn't silently mis-handled when TCP works.
                put("packet_encoding", "xudp")
                tls(o.tls, settings, o.server, defaultUtls = true)?.let { put("tls", it) }
                vlessTransport?.let { put("transport", it) }
                if (settings.tcpFastOpen) tcpFastOpen()
                // sing-box rejects "XTLS is not supported in multiplex": with an
                // xtls-rprx-vision flow (every subscription reality node), emitting
                // multiplex breaks the outbound. Only mux when there is no flow.
                if (settings.mux && o.flow.isEmpty()) put("multiplex", multiplex())
            }
            is Outbound.Vmess -> {
                put("type", "vmess")
                put("uuid", o.uuid)
                put("security", o.security)
                put("alter_id", o.alterId)
                tls(o.tls, settings, o.server, defaultUtls = true)?.let { put("tls", it) }
                transport(o.transport)?.let { put("transport", it) }
                if (settings.tcpFastOpen) tcpFastOpen()
                if (settings.mux) put("multiplex", multiplex())
            }
            is Outbound.Trojan -> {
                put("type", "trojan")
                put("password", o.password)
                tls(o.tls, settings, o.server, defaultUtls = true)?.let { put("tls", it) }
                transport(o.transport)?.let { put("transport", it) }
                if (settings.tcpFastOpen) tcpFastOpen()
                if (settings.mux) put("multiplex", multiplex())
            }
            is Outbound.Shadowsocks -> {
                put("type", "shadowsocks")
                put("method", o.method)
                put("password", o.password)
                if (o.plugin.isNotEmpty()) {
                    put("plugin", o.plugin)
                    if (o.pluginOpts.isNotEmpty()) put("plugin_opts", o.pluginOpts)
                }
                // NB: no tcp_fast_open for Shadowsocks. SS folds its first
                // request bytes into the TFO SYN payload; when the server or any path
                // middlebox doesn't truly support TFO those bytes are swallowed and the
                // stream half-works, "connects, uploads, but no download" (sing-box
                // #1903). VLESS/VMess/Trojan keep TFO (a TLS handshake goes first there).
                // mux on SS only over 2022 ciphers, the sole combo sing-box SS mux supports
                // (h2mux+padding stalls the return stream on legacy AEAD servers).
                if (settings.mux && o.method.startsWith("2022-blake3-")) put("multiplex", multiplex())
            }
            is Outbound.Hysteria2 -> {
                put("type", "hysteria2")
                put("password", o.password)
                // Emit obfs only with both type and password: salamander obfs with no
                // password is rejected by sing-box and kills the whole instance. Both
                // parsers guarantee a password whenever obfsType is set, so valid
                // configs are byte-identical; only the no-password case changes.
                if (o.obfsType.isNotEmpty() && o.obfsPassword.isNotEmpty()) {
                    putJsonObject("obfs") {
                        put("type", o.obfsType)
                        put("password", o.obfsPassword)
                    }
                }
                tls(o.tls, settings, o.server, defaultEnabled = true, quic = true)?.let { put("tls", it) }
                udpFragment()
            }
            is Outbound.Hysteria -> {
                put("type", "hysteria")
                // The v1 handshake requires nonzero bandwidth hints; sing-box
                // rejects zero speeds, so fall back to safe defaults when the
                // link omitted upmbps/downmbps.
                put("up_mbps", if (o.upMbps > 0) o.upMbps else 10)
                put("down_mbps", if (o.downMbps > 0) o.downMbps else 50)
                if (o.authStr.isNotEmpty()) put("auth_str", o.authStr)
                if (o.obfs.isNotEmpty()) put("obfs", o.obfs)
                // o.serverPorts (port hopping) is intentionally not emitted:
                // strict JSON decoding makes an unsupported field fatal for the
                // whole config; the base server_port above is always valid.
                tls(o.tls, settings, o.server, defaultEnabled = true, quic = true)?.let { put("tls", it) }
                udpFragment()
            }
            is Outbound.Tuic -> {
                put("type", "tuic")
                put("uuid", o.uuid)
                put("password", o.password)
                put("congestion_control", o.congestionControl)
                put("udp_relay_mode", o.udpRelayMode)
                tls(o.tls, settings, o.server, defaultEnabled = true, quic = true)?.let { put("tls", it) }
                udpFragment()
            }
            // WireGuard is never an "outbounds" entry, build() routes it to
            // wireguardEndpoint(), and the top-level "endpoints" array instead.
            // This arm keeps the when() exhaustive; it is not reached in practice.
            is Outbound.WireGuard -> put("type", "wireguard")
            // Not expressible as a sing-box outbound at all: both are external binaries
            // fronted by a local socks port, which pluginOrDirect emits instead. Reaching
            // here means a helper-backed profile got no port allocation, a wiring bug,
            // and one that must be loud, because the quiet version is a tunnel that
            // connects and carries nothing.
            is Outbound.Naive, is Outbound.Mieru, is Outbound.Olcrtc ->
                error("${o.protocol} требует внешний процесс — используйте pluginOrDirect")
        }
    }

    // ------------------------------------------------------------ ENDPOINT ----

    /**
     * Emits a sing-box 1.11+ wireguard ENDPOINT (top-level "endpoints" array, a
     * sibling of "outbounds"; the [tag] is interchangeable with an outbound tag in
     * route/selector/urltest). Runs entirely in sing-box's in-process userspace
     * (gVisor) netstack ("system": false), which is the model Lean's TUN inbound
     * (stack "mixed", auto_route) already uses; a system/kernel WG interface needs
     * privileges an unprivileged Android VpnService doesn't have.
     *
     * Port + persistent_keepalive_interval are bare ints
     * (keepalive in seconds), address/allowed_ips are CIDR string lists, reserved is
     * exactly three ints (emitted only when present).
     */
    private fun wireguardEndpoint(o: Outbound.WireGuard, tag: String, mtu: Int): JsonObject {
        require(o.awg == null) { "AmneziaWG должен запускаться отдельным движком Amnezia Go" }
        require(o.localAddresses.isNotEmpty()) {
            "WireGuard localAddresses не должен быть пустым"
        }
        return buildJsonObject {
            put("type", "wireguard")
            put("tag", tag)
            put("system", false)
            // MTU comes from Settings.wgMtu (default 1280), the same value the TUN is
            // matched to (see tunMtuFor), so app packets fit the WG tunnel exactly,
            // preventing the fragmentation that throttles WireGuard. The .conf's own
            // MTU is intentionally overridden by the user-tunable setting.
            put("mtu", mtu)
            putJsonArray("address") { o.localAddresses.forEach { add(it) } }
            put("private_key", o.privateKey)
            putJsonArray("peers") {
                add(buildJsonObject {
                    put("address", o.server)
                    put("port", o.serverPort)
                    put("public_key", o.peerPublicKey)
                    if (o.preSharedKey.isNotEmpty()) put("pre_shared_key", o.preSharedKey)
                    putJsonArray("allowed_ips") {
                        o.allowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") }.forEach { add(it) }
                    }
                    if (o.persistentKeepalive > 0) put("persistent_keepalive_interval", o.persistentKeepalive)
                    // reserved must be exactly three ints when present (Cloudflare WARP);
                    // omit for plain WG.
                    if (o.reserved.size == 3) putJsonArray("reserved") { o.reserved.forEach { add(it) } }
                })
            }
        }
    }

    /**
     * TCP Fast Open dial field, saves one RTT on every new TCP connection for
     * TCP-based protocols (VLESS/VMess/Trojan/Shadowsocks). Meaningless for the
     * QUIC protocols, so it is only emitted from their branches. A few mobile
     * middleboxes drop the TFO SYN payload; harmless here because the kernel
     * silently falls back to a normal handshake when the peer doesn't ACK it.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.tcpFastOpen() {
        put("tcp_fast_open", true)
    }

    /**
     * UDP fragmentation dial field for the QUIC protocols (hysteria2/hysteria/
     * tuic): lets oversized QUIC datagrams fragment at the IP layer instead of
     * black-holing on low-MTU mobile paths while the userspace stack owns
     * fragmentation for the large default TUN MTU.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.udpFragment() {
        put("udp_fragment", true)
    }

    private fun tls(
        t: TlsSettings?,
        settings: Settings,
        serverHost: String,
        defaultEnabled: Boolean = false,
        defaultUtls: Boolean = false,
        quic: Boolean = false,
    ): JsonObject? {
        if (t == null) {
            return if (defaultEnabled) buildJsonObject {
                put("enabled", true)
                if (serverHost.isNotEmpty()) put("server_name", serverHost)
                // Honour «Небезопасный TLS» on the QUIC default-TLS path too (parity with
                // the explicit-TLS branch), camouflage-SNI hy2/tuic nodes need it as well.
                if (settings.allowInsecure) put("insecure", true)
            } else null
        }
        if (!t.enabled) return null
        return buildJsonObject {
            put("enabled", true)
            // Default SNI to the server host when the link omits it, otherwise
            // sing-box verifies the server certificate against the *destination*
            // domain (e.g. google.com), and the TLS handshake fails.
            val reality = t.reality
            // Client-side SNI camouflage: when set, OVERRIDE server_name with a whitelisted
            // domain on NON-Reality TLS, so the ClientHello looks like an allowed site under
            // SNI-filtering whitelists, no server change needed (pair with «Небезопасный
            // TLS»; the cert won't match). Reality keeps its own stolen-cert SNI. Else default
            // the SNI to the server host when the link omits it (otherwise the cert is checked
            // against the destination domain and the handshake fails).
            val sni = settings.sniOverride.takeIf { it.isNotEmpty() && reality == null }
                ?: t.serverName.ifEmpty { serverHost }
            if (sni.isNotEmpty()) put("server_name", sni)
            if (t.insecure || settings.allowInsecure) put("insecure", true)
            // TLS fragmentation against SNI-based DPI (sing-box native, TCP-TLS only).
            // not for Reality nodes: Reality does its own anti-DPI ClientHello handling,
            // and fragmenting it can break the server's ClientHello interception.
            if (defaultUtls && settings.fragment && reality == null) put("fragment", true)
            if (t.alpn.isNotEmpty()) putJsonArray("alpn") { t.alpn.forEach { add(it) } }
            // Mimic a real Chrome ClientHello unless told otherwise, which defeats
            // JA3/JA4 fingerprinting, but only on TCP-TLS. uTLS is unsupported over QUIC
            // (hysteria2/hysteria/tuic): the QUIC dialer rejects the uTLS config, so the
            // outbound builds and every dial fails. Fingerprints are dropped there.
            //
            // Precedence: what the LINK asked for wins. Otherwise Reality always gets a
            // fingerprint (its client is built on uTLS and has no no-fingerprint mode),
            // and plain TLS follows the setting, «Выключено» included: that is the only
            // way to send the core's own ClientHello, and it matters because mimicking is
            // our default rather than the link's: a server that dislikes the mimicked
            // hello fails here and works in a client that only applies `fp` when the share
            // link carries one.
            val forced = settings.utlsFingerprint
            val fp = if (quic) {
                ""
            } else {
                t.utlsFingerprint.ifEmpty {
                    when {
                        reality != null -> forced.takeIf { it.isNotEmpty() && it != UTLS_OFF } ?: "chrome"
                        defaultUtls && forced != UTLS_OFF -> forced.ifEmpty { "chrome" }
                        else -> ""
                    }
                }
            }
            if (fp.isNotEmpty()) {
                putJsonObject("utls") { put("enabled", true); put("fingerprint", fp) }
            }
            // REALITY is invalid on a QUIC outbound (hysteria2/hysteria/tuic), the
            // core rejects/fails the dial. Mirror the QUIC guards on uTLS (711) and
            // fragment (697). Reachable only via a crafted/corrupt store, but
            // TlsSettings.reality is a shared model field, so guard it.
            if (reality != null && !quic) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", reality.publicKey)
                    if (reality.shortId.isNotEmpty()) put("short_id", reality.shortId)
                }
            }
        }
    }

    private fun transport(t: TransportSettings?): JsonObject? {
        if (t == null) return null
        return when (t.type.lowercase()) {
            "ws" -> buildJsonObject {
                put("type", "ws")
                if (t.path.isNotEmpty()) put("path", t.path)
                if (t.host.isNotEmpty()) putJsonObject("headers") { put("Host", t.host) }
            }
            "httpupgrade" -> buildJsonObject {
                put("type", "httpupgrade")
                if (t.host.isNotEmpty()) put("host", t.host)
                if (t.path.isNotEmpty()) put("path", t.path)
            }
            "grpc" -> buildJsonObject {
                put("type", "grpc")
                if (t.serviceName.isNotEmpty()) put("service_name", t.serviceName)
            }
            "http", "h2" -> buildJsonObject {
                put("type", "http")
                if (t.host.isNotEmpty()) putJsonArray("host") { add(t.host) }
                if (t.path.isNotEmpty()) put("path", t.path)
            }
            else -> null
        }
    }

    /** Stream multiplexing (sing-box mux), reduces handshakes; needs server support. */
    private fun multiplex(): JsonObject = buildJsonObject {
        put("enabled", true)
        put("protocol", "h2mux")
        put("max_connections", 4)
        put("padding", true)
    }

    // ------------------------------------------------------- AUTO (urltest) ---

    /** Latency-tested group: the core picks the lowest-RTT node and re-tests it. */
    private fun urltest(nodeTags: List<String>, url: String): JsonObject = buildJsonObject {
        put("type", "urltest")
        put("tag", TAG_AUTO)
        putJsonArray("outbounds") { nodeTags.forEach { add(it) } }
        put("url", url.ifBlank { "https://www.gstatic.com/generate_204" })
        // Short enough to react to a node degrading. It runs for as long as the tunnel
        // does, screen on or off: the core must never be paused to confine it, because
        // sing-box's pause manager holds new connections: a paused core is a phone with
        // no tunnel.
        put("interval", "1m")
        // Hysteresis, and it has to be generous because a re-pick is not free here:
        // `interrupt_exist_connections` below means every change of mind drops every live
        // connection. protocol/group/urltest.go's Select() seeds the search with the
        // current node, so a challenger only wins by being faster than it by more than
        // this, and on a mobile link, where the same node's RTT wanders by several tens
        // of milliseconds between sweeps, 50 ms was inside the noise. Two equally good
        // nodes then traded places every few minutes, and each trade restarted whatever
        // the user was doing. 100 ms is wide enough that jitter rarely clears it, and
        // still narrow enough to leave a node that has genuinely degraded.
        put("tolerance", 100)
        put("idle_timeout", "30m")
        // When the measured-fastest node changes, drop connections still pinned to the
        // previous one and re-dial, rather than leaving live flows on a node that has
        // just been measured as worse.
        put("interrupt_exist_connections", true)
    }

    /** Selector fronting the urltest group so a node can be pinned through the native API. */
    private fun selector(nodeTags: List<String>): JsonObject = buildJsonObject {
        put("type", "selector")
        put("tag", TAG_PROXY)
        putJsonArray("outbounds") {
            add(TAG_AUTO)
            nodeTags.forEach { add(it) }
        }
        put("default", TAG_AUTO)
        // Re-dial through the newly-selected node when the user (or the urltest
        // default) changes the pick, instead of leaving live flows on the old one.
        put("interrupt_exist_connections", true)
    }

    // -------------------------------------------------------------- ROUTE -----

    private fun route(
        settings: Settings,
        pluginMappingTags: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        // User-supplied custom rule-sets (add-your-own geoip/geosite .srs, like
        // Incy/Happ). Trimmed to real http(s) URLs so a blank / half-typed line
        // never emits a broken rule_set entry; tags are stable ("custom-<i>") so
        // the cache_file keeps their downloads across restarts.
        val customUrls = settings.customRuleSets.map { it.trim() }.filter { it.startsWith("http") }
        val customTags = customUrls.indices.map { "custom-$it" }
        // GLOBAL mode's own promise is "весь трафик через прокси", literally everything,
        // no exceptions, same reasoning as the bypassLan gate below. ruDirect and custom
        // rule-sets exist specifically to send some destinations around the tunnel, which
        // is what Global overrides. Before this gate, turning Global mode on did
        // not actually stop .ru/.su (or a user's own rule-set) traffic from going direct,
        // indistinguishable from Global simply not working. On a restrictive network where
        // direct traffic to a non-whitelisted destination is dropped outright, that showed
        // up as "the VPN doesn't work" for exactly the sites the rule matched, while the
        // tunnel itself reported Connected.
        val globalMode = settings.routingMode == RoutingMode.GLOBAL
        val ruDirectActive = settings.ruDirect && !globalMode
        val customDirectActive = customUrls.isNotEmpty() && !globalMode
        val hasRuleSets = ruDirectActive || customDirectActive
        // Object form (server + strategy) rather than the bare "dns-direct" string:
        // pinning the IP-version strategy resolves the proxy server's hostname without
        // waiting on absent AAAA records on v4-only mobile links, and never to an IPv6
        // the network cannot route. It replaces the deprecated per-outbound
        // domain_strategy and covers every outbound at once.
        //
        // The target has no detour, so bootstrap is dialed outside the routing engine and
        // cannot loop through a proxy that is not up yet. It must also be self-resolvable
        // [directDnsServer] guarantees that by falling back to the system resolver.
        putJsonObject("default_domain_resolver") {
            put("server", TAG_DNS_DIRECT)
            put("strategy", serverDialStrategy(settings))
        }
        putJsonArray("rules") {
            // first, before anything else can claim it: whatever arrives on a helper's
            // mapping inbound goes straight out.
            //
            // This is the rule that makes every external protocol work. Traffic here
            // is the helper's own connection to its server; if any later rule sent it to
            // the proxy instead: the helper would be proxying itself through the tunnel
            // it provides. Order is the guarantee, route.final alone would not do, since
            // a GLOBAL-mode or rule-set match placed above would win first.
            if (pluginMappingTags.isNotEmpty()) {
                add(buildJsonObject {
                    putJsonArray("inbound") { pluginMappingTags.forEach { add(it) } }
                    put("outbound", TAG_DIRECT)
                })
            }
            // Send DNS queries to the DNS module when DNS routing is enabled.
            if (settings.dnsRouting) {
                add(buildJsonObject {
                    put("port", 53)
                    put("action", "hijack-dns")
                })
                add(buildJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })
            }
            // Multicast/broadcast discovery chatter (mDNS, SSDP, IGMP…) has no
            // business crossing a tunnel: the reference client drops it outright
            // rather than paying to carry it to the exit and back.
            add(buildJsonObject {
                putJsonArray("ip_cidr") { add("224.0.0.0/3"); add("ff00::/8") }
                putJsonArray("source_ip_cidr") { add("224.0.0.0/3"); add("ff00::/8") }
                put("action", "reject")
            })
            // Keep LAN / private-range traffic off the tunnel. In GLOBAL mode
            //    everything (incl. private ranges) is forced through the proxy.
            if (settings.bypassLan && settings.routingMode != RoutingMode.GLOBAL) {
                add(buildJsonObject {
                    put("ip_is_private", true)
                    put("outbound", TAG_DIRECT)
                })
            }
            // 4) GeoIP/GeoSite: Russian sites & IPs go straight out (not through the
            // foreign exit), faster RU content + less correlation, complements the
            //    per-app split. The .ru/.su suffix rule matches immediately; the
            //    geoip-ru/geosite-category-ru rule-sets add the full set once
            //    downloaded (through the proxy, then cached by experimental.cache_file).
            if (ruDirectActive) {
                add(buildJsonObject {
                    putJsonArray("domain_suffix") { add(".ru"); add(".su") }
                    put("outbound", TAG_DIRECT)
                })
                add(buildJsonObject {
                    // geosite-category-ru, not geosite-ru: SagerNet ships no
                    // "geosite-ru.srs" (it 404s, which fails rule-set init and stops
                    // the whole tunnel, the reported bug). geoip-ru exists & is right.
                    putJsonArray("rule_set") { add("geoip-ru"); add("geosite-category-ru") }
                    put("outbound", TAG_DIRECT)
                })
            }
            // User's custom rule-sets route direct too, same "go straight out"
            // semantics as the built-in RU sets ("помимо автоматических").
            if (customDirectActive) {
                add(buildJsonObject {
                    putJsonArray("rule_set") { customTags.forEach { add(it) } }
                    put("outbound", TAG_DIRECT)
                })
            }
        }
        if (hasRuleSets) {
            putJsonArray("rule_set") {
                if (ruDirectActive) {
                    add(ruleSet("geoip-ru", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs"))
                    add(ruleSet("geosite-category-ru", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs"))
                }
                customUrls.forEachIndexed { i, url -> add(ruleSet(customTags[i], url)) }
            }
        }
        put("final", TAG_PROXY)
        put("auto_detect_interface", true)
    }

    /** Remote binary rule-set (geoip/geosite .srs), downloaded through the proxy + cached. */
    private fun ruleSet(tag: String, url: String): JsonObject = buildJsonObject {
        put("type", "remote")
        put("tag", tag)
        put("format", "binary")
        put("url", url)
        put("download_detour", TAG_PROXY)
        put("update_interval", "7d")
    }

    // --------------------------------------------------------- EXPERIMENTAL ---

    /**
     * Persists the URL-test history and the remote rule-set downloads across restarts.
     *
     * Both are worth keeping: the rule-sets are megabytes fetched through the tunnel, and
     * the latency history is what lets a reconnect skip re-probing every node (the group
     * ignores any node measured within the last interval). Neither the selector choice nor
     * the fake-IP table is stored here, see [build] and the note below.
     */
    private fun experimental(): JsonObject =
        buildJsonObject {
            putJsonObject("cache_file") {
                put("enabled", true)
                put("path", "cache.db")
                put("store_rdrc", true)
                // store_fakeip stays OFF. It puts the whole fake-IP table in this same
                // bbolt file, written on every new name the device looks up, the busiest
                // writer there, and Android kills a VPN service at will. A kill inside
                // one of those writes corrupts the file, after which the core panics on
                // every start and no server works again.
                //
                // Persisting it buys almost nothing: fake IPs are handed out fresh on each
                // connect and a stale one simply re-resolves. [NekoEngine] still repairs a
                // cache broken by the remaining writers.
            }
        }

    private val SUPPORTED_TUN_STACKS = setOf("gvisor", "system", "mixed")

    /** Pretty JSON for readability of dumped configs and `sing-box check` output. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val PrettyJson = kotlinx.serialization.json.Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }
}
