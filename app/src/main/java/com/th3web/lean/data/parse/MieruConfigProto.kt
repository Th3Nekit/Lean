package com.th3web.lean.data.parse

import android.util.Base64
import com.th3web.lean.data.model.Outbound

/**
 * Reads mieru's `mieru://` share link, base64 of a protobuf-encoded `ClientConfig`.
 *
 * There is no protobuf runtime in this app and adding one for a single link format would
 * be absurd, so this walks the wire format directly. That is viable because protobuf's
 * encoding is self-describing enough for the job: every field is a varint tag carrying a
 * field number and a wire type, so a reader can skip what it does not know and pick out
 * the handful of fields it does.
 *
 * The field numbers below were not guessed. They were read off the example link in
 * mieru's own `docs/client-install.md` by decoding it and matching the values against the
 * documented plaintext, the user/password/host/ports/MTU/multiplexing in the decode line
 * up with what the docs say that link contains.
 *
 * ```
 *   ClientConfig
 *     1  profiles[]        ClientProfile
 *          1  profileName  string
 *          2  user         { 1 name, 2 password, 3 hashedPassword }
 *          3  servers[]    { 1 ipAddress, 2 domainName, 3 portBindings[] }
 *          4  mtu          varint
 *     2  activeProfile     string
 * ```
 *
 * A `portBindings` entry is `{ 1 port, 2 protocol, 3 portRange }`, `port` and
 * `portRange` are alternatives, matching upstream, which reads `portRange` only when
 * `port` is zero.
 */
internal object MieruConfigProto {

    /** One endpoint from the config, with the profile name it came from. */
    data class Entry(val name: String, val outbound: Outbound.Mieru)

    fun parse(payload: String): List<Entry> = runCatching {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        val out = mutableListOf<Entry>()
        Reader(bytes).forEachField { field, reader ->
            if (field == FIELD_PROFILES) out += profile(reader.lengthDelimited())
            else reader.skip(field)
        }
        out
    }.getOrDefault(emptyList())

    private fun profile(bytes: ByteArray): List<Entry> {
        var name = ""
        var username = ""
        var password = ""
        var mtu = ShareLinks.DEFAULT_MIERU_MTU
        val servers = mutableListOf<ByteArray>()
        Reader(bytes).forEachField { field, reader ->
            when (field) {
                1 -> name = reader.string()
                2 -> {
                    val user = reader.lengthDelimited()
                    Reader(user).forEachField { f, r ->
                        when (f) {
                            1 -> username = r.string()
                            2 -> password = r.string()
                            // 3 = hashedPassword. Ignored: our config
                            // writer emits a plaintext password, and a config that
                            // carries only the hash cannot be used by us at all, better
                            // to import it with an empty password the user can see and
                            // fix than to invent something.
                            else -> r.skip(f)
                        }
                    }
                }
                3 -> servers += reader.lengthDelimited()
                4 -> mtu = reader.varint().toInt().takeIf { it in ShareLinks.MIERU_MTU_RANGE } ?: mtu
                else -> reader.skip(field)
            }
        }
        return servers.flatMap { endpoints(it, name, username, password, mtu) }
    }

    private fun endpoints(
        bytes: ByteArray,
        profileName: String,
        username: String,
        password: String,
        mtu: Int,
    ): List<Entry> {
        var host = ""
        var domain = ""
        val bindings = mutableListOf<Pair<Int, String>>()
        Reader(bytes).forEachField { field, reader ->
            when (field) {
                1 -> host = reader.string()
                2 -> domain = reader.string()
                3 -> binding(reader.lengthDelimited())?.let { bindings += it }
                else -> reader.skip(field)
            }
        }
        // A domain name wins over the IP, upstream ignores ipAddress entirely when
        // domainName is set, so honouring the IP here would dial a different host than
        // mieru itself would.
        val server = domain.ifBlank { host }
        if (server.isBlank()) return emptyList()
        val label = profileName.ifBlank { server }
        return bindings.mapIndexed { index, (port, transport) ->
            Entry(
                name = if (bindings.size > 1) "$label #${index + 1}" else label,
                outbound = Outbound.Mieru(
                    server = server,
                    serverPort = port,
                    transport = transport,
                    username = username,
                    password = password,
                    mtu = mtu,
                ),
            )
        }
    }

    private fun binding(bytes: ByteArray): Pair<Int, String>? {
        var port = 0
        var protocol = 0L
        var range = ""
        Reader(bytes).forEachField { field, reader ->
            when (field) {
                1 -> port = reader.varint().toInt()
                2 -> protocol = reader.varint()
                3 -> range = reader.string()
                else -> reader.skip(field)
            }
        }
        // Upstream reads portRange only when port is zero; the first port of a range is
        // the one it dials.
        val resolved = if (port != 0) port else range.substringBefore('-').trim().toIntOrNull() ?: 0
        if (resolved !in 1..65535) return null
        return resolved to transportOf(protocol)
    }

    /**
     * TransportProtocol, verbatim from upstream's `appctl/proto/base.proto`:
     *
     * ```
     *   UNKNOWN_TRANSPORT_PROTOCOL = 0;
     *   UDP = 1;
     *   TCP = 2;
     * ```
     *
     * UDP first, the reverse of the conventional ordering, and worth stating because
     * assuming the usual TCP=1 silently inverts the transport of every imported endpoint,
     * which shows up as a server that connects to nothing rather than as a parse error.
     *
     * Anything else, including the 0 sentinel that upstream's own unchecked map lookup
     * produces from a malformed link, falls back to TCP: the transport that works by
     * default.
     */
    private fun transportOf(value: Long): String = if (value == 1L) "UDP" else "TCP"

    private const val FIELD_PROFILES = 1

    /** A minimal protobuf wire-format reader: varints, length-delimited, and skipping. */
    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun forEachField(block: (field: Int, reader: Reader) -> Unit) {
            while (pos < bytes.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                wireType = (key and 0x7).toInt()
                if (field <= 0) return
                block(field, this)
            }
        }

        private var wireType = 0

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (pos < bytes.size) {
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                // A varint cannot exceed 10 bytes; a longer run means the payload is not
                // what we think it is, and continuing would read past everything.
                if (shift > 63) break
            }
            return result
        }

        fun lengthDelimited(): ByteArray {
            val length = varint().toInt()
            if (length < 0 || pos + length > bytes.size) {
                pos = bytes.size
                return ByteArray(0)
            }
            val slice = bytes.copyOfRange(pos, pos + length)
            pos += length
            return slice
        }

        fun string(): String = String(lengthDelimited(), Charsets.UTF_8)

        /** Consumes a field whose contents we do not care about, per its wire type. */
        fun skip(@Suppress("UNUSED_PARAMETER") field: Int) {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> lengthDelimited()
                5 -> pos += 4
                // Groups (3/4) are proto2-only and never appear here; anything else is a
                // corrupt payload, so stop rather than walk off the end.
                else -> pos = bytes.size
            }
            if (pos > bytes.size) pos = bytes.size
        }
    }
}
