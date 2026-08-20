package com.th3web.lean.data

object SettingsDefaults {
    const val SCHEMA_VERSION = 8

    /**
     * How the tunnel is exposed: "vpn" (system TUN), "proxy" (a local SOCKS/HTTP
     * listener only) or "vpn_proxy" (both).
     *
     * "vpn" keeps the behaviour every install has had. In "proxy" the core emits no TUN
     * inbound at all, so [com.th3web.lean.core.tun.VpnTunController.openTun] is never
     * called and no VpnService.establish happens: nothing is captured system-wide and
     * only apps pointed at the listener go through.
     */
    const val SERVICE_MODE = "vpn"
    const val SERVICE_MODE_VPN = "vpn"
    const val SERVICE_MODE_PROXY = "proxy"
    const val SERVICE_MODE_VPN_PROXY = "vpn_proxy"

    /** Local listener port for the proxy modes. 2080 is the ecosystem's usual pick. */
    const val PROXY_PORT = 2080

    /**
     * Off by default: bound to 127.0.0.1 the listener is reachable only from this device.
     * Opening it to the LAN turns the phone into an open proxy for everyone on the same
     * Wi-Fi, which is a decision the user has to make.
     */
    const val PROXY_ALLOW_LAN = false

    /**
     * Core log verbosity. "warn", not sing-box's own "info" default, at info the core
     * writes a line per connection ("inbound connection from…", "outbound connection
     * to…", every DNS answer), and each of those lines is then read by the log tailer,
     * pushed through a StateFlow and appended to a rotating file. Under a busy app,
     * a game opening connections continuously: that is a steady write load that
     * rotates the 256 KB log every few seconds and eventually starves the tunnel: it
     * still reads "подключено" while traffic stalls.
     *
     * "warn" keeps everything that diagnoses a problem (warnings, errors, the FATAL
     * header a native crash report needs), and drops only the per-connection narration.
     * «Лаборатория» still offers info/debug/trace for when that narration is wanted.
     */
    const val LOG_LEVEL = "warn"
    const val IPV6 = false
    const val TUN_STACK = "gvisor"
    const val TUN_MTU = 9_000
    const val WG_MTU = 1_280
    const val SNIFF_ENABLED = true
    const val SNIFF_OVERRIDE_DESTINATION = false
    const val SNIFF_RESOLVE_DESTINATION = false
    const val DNS_ROUTING = true
    const val FAKE_DNS = true
    const val RESET_CONNECTIONS_ON_NETWORK_CHANGE = true
    const val ALLOW_INSECURE = false
    const val REMOTE_DNS = "https://dns.google/dns-query"

    /**
     * Direct-side resolver, and the bootstrap `route.default_domain_resolver` points at
     * i.e. what resolves the proxy/endpoint hostname before any tunnel exists, over
     * the RAW physical network, before there is a tunnel to hide behind.
     *
     * The Android platform resolver rather than the reference client's fixed public one
     * (AliDNS over HTTPS), and the difference is not cosmetic: on
     * a restrictive/"whitelist" mobile tariff, common in RU, where only a specific set
     * of destinations is reachable and everything else is blocked outright until a VPN
     * is up, a bootstrap query to an arbitrary Chinese IP is the kind of traffic
     * such a network drops. With the bootstrap resolver unreachable, the server's own
     * hostname can never resolve, `route.default_domain_resolver` never completes, and
     * no profile can connect, the app cannot even get FAR enough to need the tunnel it
     * exists to provide. "local" resolves through Android's own `DnsResolver` on the
     * active network, i.e. whatever the carrier hands its own subscribers, plain DNS
     * service is not what these tariffs restrict, so it keeps working exactly where the
     * China DoH did not.
     */
    const val DIRECT_DNS = "local"
    /**
     * Per-server ping protocol: «URL Test», the real protocol-level measurement (see
     * [com.th3web.lean.core.UrlTestPinger]), named after NekoBox's own test of the same
     * design. It boots a throwaway headless core per server and times a real HTTP round
     * trip through that server's actual handshake, which is the only probe that answers
     * "does this server work" rather than "does its port answer a SYN".
     *
     * Owner's call to keep it as the default after it first shipped measuring nothing on
     * device. What makes that safe is not the probe itself but its contract: it now
     * answers null when it could not run, and [com.th3web.lean.data.net.Pinger] falls
     * through to the raw socket probe on null. So the worst case is a TCP number, never
     * the row of dashes that made the ping look broken.
     *
     * must equal [com.th3web.lean.data.net.Pinger.URL_TEST_PROTOCOL]: that is what
     * Pinger.measure's dispatch matches on; PingProtocolDefaultTest pins it.
     */
    const val PING_PROTOCOL = "URLTEST"
    /** Shared GET/HEAD + «Проверить соединение» target, a purpose-built generate_204
     * endpoint (HTTPS, no redirects, not Cloudflare-owned). URL Test uses it too (it is
     * the URL the throwaway instance fetches through the proxy). */
    const val TEST_URL = "https://connectivitycheck.grapheneos.network/generate_204"
    const val PROFILE_TEST_TIMEOUT_MS = 5_000
    const val ACTIVE_CONNECTION_TEST_TIMEOUT_MS = 3_000
    /**
     * The user's «Тип IP» preference, which IP version to prefer when resolving domains
     * inside the tunnel, exactly as that screen describes it. "auto" ties it to the IPv6
     * toggle, so while IPv6 is off it resolves A-only: the TUN carries no v6 address and
     * no v6 route, and an AAAA answer would point apps at a road that was never built.
     *
     * Note this is not the strategy used to dial the proxy server itself: that one is
     * [SERVER_DIAL_STRATEGY], a separate axis, as it is in the reference client.
     */
    const val SERVER_DOMAIN_STRATEGY = "auto"

    /**
     * How the proxy server's own hostname is resolved and dialed. Matches the reference
     * client's `domain_strategy_for_server` default.
     *
     * Separate from [SERVER_DOMAIN_STRATEGY] because it is a different question: this
     * dial happens over the underlying physical network, not inside the TUN, so
     * accepting AAAA costs nothing here and lets a v6-only carrier link reach the server
     * at all. Used only when the user leaves «Тип IP» on "auto"; an explicit choice there
     * applies to both axes.
     */
    const val SERVER_DIAL_STRATEGY = "prefer_ipv4"
    const val HYSTERIA2_PORT = 443
    const val HYSTERIA2_HOP_INTERVAL_SECONDS = 10
    const val HYSTERIA2_BANDWIDTH_MBPS = 0

    // ---- Appearance: only the defaults whose value needs an explanation ----

    /**
     * The accent seed shipped since the monochrome era. It matches no entry in
     * LeanAccents, and the resolver maps it to Steel, every install that
     * never touched the accent picker carries this exact number, so the special case is
     * load-bearing, not legacy cruft.
     */
    const val ACCENT_LEGACY_DEFAULT = 0xFFF3F4F7L

    /** Minutes from midnight: 23:00 and 07:00. */
    const val THEME_SCHED_FROM = 1_380
    const val THEME_SCHED_TO = 420

    /** Ceiling on the synthesized accent's saturation, in percent of the seed's own. */
    const val ACCENT_CHROMA = 60

    /** Percent, matching the 0.06f the surface tint has always been applied at. */
    const val SURFACE_TINT = 6

    /** Servers shown inline on the home screen before "see all". */
    const val QUICK_PEEK = 3

    /** Bitmask: subscription | quick pick | connection test | Telegram banner, all on. */
    const val HOME_BLOCKS = 15

    /** Latency tier boundaries in ms, as they were hardcoded in ServerRow. */
    const val LAT_T1 = 120
    const val LAT_T2 = 250
    const val LAT_T3 = 500

    /** Selected-row wash, in percent, the 0.10f alpha the row has always used. */
    const val SELECTION_WASH = 10

    /**
     * Which server-tag kinds a row states, as a flag string over p/s/t,
     * protocol / security / transport (see ui.components.TagKind).
     *
     * All three by default: every row answers the same questions in the same order, and
     * hiding one would make rows state different facts in the same place.
     */
    const val SERVER_TAG_KINDS = "pst"

    /**
     * Scrim over «своя картинка», percent. 55 keeps a typical photo clearly visible while
     * the tonal surface ladder and hairlines the whole design rests on stay legible on top
     * of it, the two things a picture background trades against each other.
     */
    const val BG_IMAGE_DIM = 55

    /**
     * Soft focus over the picture, percent. 0 = untouched. Off by default because a
     * background the user chose should first appear as they chose it.
     */
    const val BG_IMAGE_BLUR = 0

    /** Colour kept in the background, percent. 100 = the original image. */
    const val BG_IMAGE_SATURATION = 100

    /** Magnification over cover-fit, percent. 100 = exactly covering the screen. */
    const val BG_IMAGE_ZOOM = 100

    /** Which part of a taller-than-screen picture survives the crop. */
    const val BG_IMAGE_ALIGN = "center"

    /**
     * How opaque a glass panel stays, percent. 62 keeps enough surface under the text for
     * the tonal ladder to still separate a card from the canvas, below roughly half the
     * panel stops reading as a panel and its labels start fighting the picture.
     */
    const val GLASS_TINT = 62
}
