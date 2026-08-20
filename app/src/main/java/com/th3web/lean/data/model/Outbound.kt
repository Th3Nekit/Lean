package com.th3web.lean.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Internal, protocol-agnostic representation of a proxy server. This is the
 * app's own model: it is persisted as-is (kotlinx.serialization), and mapped to
 * a sing-box outbound by [com.th3web.lean.core.SingBoxConfig] in Increment C.
 *
 * Polymorphism uses the default "type" class discriminator so the JSON on disk
 * is readable and stable.
 *
 * [@Immutable]: instances are never mutated in place (all subtypes are `val`
 * data classes; latency/edits create new objects). This promise makes Compose
 * treat an `Outbound`-typed parameter as stable, so a list row taking a
 * [Profile] becomes skippable and stops recomposing on unrelated state changes
 * (selection, ping bursts, search), the recomposition churn behind list jank.
 */
@Immutable
@Serializable
sealed class Outbound {
    abstract val server: String
    abstract val serverPort: Int

    /** Short human label of the protocol, for the UI. */
    abstract val protocol: String

    @Serializable
    @SerialName("vless")
    /**
     * [encryption] is VLESS's own post-quantum layer (Xray 25.9+), carried in the link as
     * `encryption=mlkem768x25519plus…`. "none" (the value every ordinary node uses) means
     * no such layer. Anything else, like an xhttp transport, is something sing-box cannot
     * speak, and sends the node to the Xray helper instead
     * (see [com.th3web.lean.core.plugin.PluginSession.pluginFor]).
     */
    data class Vless(
        override val server: String,
        override val serverPort: Int,
        val uuid: String,
        val flow: String = "",
        val network: String = "tcp",
        val encryption: String = "none",
        val tls: TlsSettings? = null,
        val transport: TransportSettings? = null,
    ) : Outbound() {
        override val protocol get() = "VLESS"
    }

    @Serializable
    @SerialName("vmess")
    data class Vmess(
        override val server: String,
        override val serverPort: Int,
        val uuid: String,
        val alterId: Int = 0,
        val security: String = "auto",
        val network: String = "tcp",
        val tls: TlsSettings? = null,
        val transport: TransportSettings? = null,
    ) : Outbound() {
        override val protocol get() = "VMess"
    }

    @Serializable
    @SerialName("trojan")
    data class Trojan(
        override val server: String,
        override val serverPort: Int,
        val password: String,
        val network: String = "tcp",
        val tls: TlsSettings? = null,
        val transport: TransportSettings? = null,
    ) : Outbound() {
        override val protocol get() = "Trojan"
    }

    @Serializable
    @SerialName("shadowsocks")
    data class Shadowsocks(
        override val server: String,
        override val serverPort: Int,
        val method: String,
        val password: String,
        val plugin: String = "",
        val pluginOpts: String = "",
    ) : Outbound() {
        override val protocol get() = "Shadowsocks"
    }

    @Serializable
    @SerialName("hysteria2")
    data class Hysteria2(
        override val server: String,
        override val serverPort: Int,
        val password: String,
        val obfsType: String = "",
        val obfsPassword: String = "",
        val tls: TlsSettings? = null,
    ) : Outbound() {
        override val protocol get() = "Hysteria2"
    }

    /**
     * Hysteria v1 (sing-box outbound type "hysteria"). [obfs] is the xplus
     * obfuscation password (v1's only mode: sing-box takes the bare string).
     * [serverPorts] stores port-hop ranges in sing-box "start:end" form; it is
     * kept for round-tripping share links but not emitted yet (see
     * [com.th3web.lean.core.SingBoxConfig]).
     */
    @Serializable
    @SerialName("hysteria")
    data class Hysteria(
        override val server: String,
        override val serverPort: Int,
        val authStr: String = "",
        val upMbps: Int = 0,
        val downMbps: Int = 0,
        val obfs: String = "",
        val serverPorts: List<String> = emptyList(),
        val tls: TlsSettings? = null,
    ) : Outbound() {
        override val protocol get() = "Hysteria"
    }

    @Serializable
    @SerialName("tuic")
    data class Tuic(
        override val server: String,
        override val serverPort: Int,
        val uuid: String,
        val password: String,
        val congestionControl: String = "bbr",
        val udpRelayMode: String = "native",
        val tls: TlsSettings? = null,
    ) : Outbound() {
        override val protocol get() = "TUIC"
    }

    /**
     * WireGuard. [server] /
     * [serverPort] are the PEER endpoint (the Outbound contract demands them and
     * Pinger/search read them); the local interface address lives in
     * [localAddresses].
     *
     * Every new field is defaulted so an older lean_store.json (which never held
     * a WireGuard profile) still decodes.
     *
     * [awg]: when non-null the source carried AmneziaWG obfuscation params and the
     * profile is routed to the separate AmneziaWG-Go runtime.
     * [amneziaUnsupported] is the legacy flag from when AWG was dropped rather than
     * emitted; it is kept only so older lean_store.json still decodes and is otherwise
     * unused now that AWG is supported.
     */
    @Serializable
    @SerialName("wireguard")
    data class WireGuard(
        override val server: String,            // [Peer] Endpoint host
        override val serverPort: Int,           // [Peer] Endpoint port
        val privateKey: String,                 // [Interface] PrivateKey
        val peerPublicKey: String,              // [Peer] PublicKey
        val preSharedKey: String = "",          // [Peer] PresharedKey (optional)
        val localAddresses: List<String> = emptyList(), // [Interface] Address (CIDR list)
        val dnsServers: List<String> = emptyList(),     // [Interface] literal DNS addresses
        val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"), // [Peer] AllowedIPs
        val persistentKeepalive: Int = 0,       // [Peer] PersistentKeepalive (seconds; 0 = omit)
        val mtu: Int = 0,                        // [Interface] MTU (0 => omit / core default)
        val reserved: List<Int> = emptyList(),  // WG reserved bytes (3 ints), rarely set
        val awg: AmneziaParams? = null,         // non-null => separate AmneziaWG runtime
        val amneziaUnsupported: Boolean = false, // legacy; kept for old-JSON decode only
    ) : Outbound() {
        override val protocol get() = if (awg != null) "AmneziaWG" else "WireGuard"
    }

    /**
     * NaiveProxy, HTTP/2 or HTTP/3 connect tunnelling through a real Caddy/nginx that
     * also serves a genuine website, so the traffic is ordinary browser traffic to a
     * censor rather than a protocol with a fingerprint of its own.
     *
     * Runs as an external process, not in the core: the client half of naive is
     * Chromium's network stack, and its TLS fingerprint being genuinely Chrome's is the
     * entire point: a Go reimplementation would defeat the purpose. See
     * [com.th3web.lean.core.plugin.PluginSession] for how the process is wired in.
     *
     * [certificates] is a PEM bundle for a self-signed server; empty means the system
     * trust store. [insecureConcurrency] > 1 multiplexes over several connections and is
     * upstream's own name for it: it costs padding-based traffic obfuscation, hence the
     * "insecure".
     */
    @Serializable
    @SerialName("naive")
    data class Naive(
        override val server: String,
        override val serverPort: Int = 443,
        val proto: String = "https",            // https (h2) | quic (h3)
        val username: String = "",
        val password: String = "",
        val sni: String = "",
        val certificates: String = "",
        val extraHeaders: String = "",
        val insecureConcurrency: Int = 0,
    ) : Outbound() {
        override val protocol get() = "NaiveProxy"
    }

    /**
     * Mieru, a socks5-over-obfuscated-transport protocol whose wire format is designed
     * to have no recognisable handshake or packet-length pattern at all.
     *
     * Also an external process (there is no mieru implementation in sing-box), and
     * arm64-only: upstream publishes no other Android build. [com.th3web.lean.core.plugin.NativePlugin.isAvailable]
     * is what the UI must ask before offering it.
     *
     * [mtu] applies to the UDP transport only, which is why upstream's own config
     * serialiser writes it only then.
     */
    @Serializable
    @SerialName("mieru")
    data class Mieru(
        override val server: String,
        override val serverPort: Int,
        val transport: String = "TCP",          // TCP | UDP
        val username: String = "",
        val password: String = "",
        val mtu: Int = 1400,
    ) : Outbound() {
        override val protocol get() = "Mieru"
    }

    /**
     * olcRTC, a TCP-over-WebRTC tunnel that rides an ordinary video call.
     *
     * The traffic looks like a meeting on a service that stays reachable even where the
     * network is whitelist-only (Jitsi, Yandex Telemost, WB Stream), with XChaCha20-
     * Poly1305 and smux inside. That is why it exists and why it is worth the machinery:
     * it survives conditions in which every server-address-based protocol is simply
     * unreachable.
     *
     * There is no server address here, and [server] / [serverPort] are not
     * the peer: the client never dials the far end directly, it joins [roomId] on the
     * chosen provider and meets the other side inside. The base class demands both fields
     * (Pinger and search read them), so they carry the provider's own host purely so the
     * UI and ping have something meaningful to show.
     *
     * Like mieru and naive this runs as its own process behind a local SOCKS5, so it
     * needs no support in the core.
     */
    @Serializable
    @SerialName("olcrtc")
    data class Olcrtc(
        override val server: String,
        override val serverPort: Int = 443,
        /** Auth provider: `jitsi`, `telemost`, `wbstream`. */
        val provider: String = "jitsi",
        /** `datachannel`, `vp8channel`, `seichannel`, `videochannel`. */
        val transport: String = "datachannel",
        /** Room URL (jitsi: `https://host/room`) or provider-specific room id. */
        val roomId: String = "",
        /** Shared key, 64 hex chars. Must match the far side exactly. */
        val key: String = "",
        /** Transport tuning, verbatim `key=value` pairs from the link's payload block. */
        val options: Map<String, String> = emptyMap(),
    ) : Outbound() {
        override val protocol get() = "olcRTC"
    }
}

/**
 * AmneziaWG obfuscation parameters (from an AWG `.conf` `[Interface]`). They map
 * 1:1 to the official userspace configuration fields. [h1]-[h4] (header magic) and
 * [i1]-[i5] (signature junk packets) are kept as strings, h-values are uint32 and
 * routinely exceed Int range in real configs, and i-values are opaque packet specs.
 * Junk/size knobs [jc]/[jmin]/[jmax]/[s1]-[s4] are small ints. Zero/empty => omitted.
 */
@Serializable
data class AmneziaParams(
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
    val h1: String = "",
    val h2: String = "",
    val h3: String = "",
    val h4: String = "",
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
)

/** TLS / Reality settings shared across protocols. */
@Serializable
data class TlsSettings(
    val enabled: Boolean = true,
    val serverName: String = "",
    val insecure: Boolean = false,
    val alpn: List<String> = emptyList(),
    val utlsFingerprint: String = "",
    val reality: RealitySettings? = null,
)

/**
 * [spiderX] is Reality's decoy path. sing-box has no such field: it is Xray's, and it
 * only reaches a server through the XHTTP helper; carried here so a link that has it
 * round-trips intact instead of losing it on import.
 */
@Serializable
data class RealitySettings(
    val publicKey: String,
    val shortId: String = "",
    val spiderX: String = "",
)

/**
 * Stream transport (v2ray-style). [type] is one of tcp/ws/grpc/http/httpupgrade/xhttp.
 *
 * `xhttp` is the odd one out: it is Xray's transport and the pinned sing-box has no
 * implementation of it under any name, so a profile carrying it is routed to the Xray
 * helper process instead of to the core (see
 * [com.th3web.lean.core.plugin.PluginSession.pluginFor]). [mode] and [extra] exist only
 * for it, respectively XHTTP's upload strategy (`auto` / `packet-up` / `stream-up` /
 * `stream-one`), and the raw JSON object panels put in the link's `extra=` parameter,
 * which is merged verbatim into `xhttpSettings` because its keys are Xray's to define.
 */
@Serializable
data class TransportSettings(
    val type: String,
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    val mode: String = "",
    val extra: String = "",
)
