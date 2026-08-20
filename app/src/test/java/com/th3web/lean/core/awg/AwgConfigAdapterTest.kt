package com.th3web.lean.core.awg

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.th3web.lean.core.tun.TunRuntimePolicy
import com.th3web.lean.data.PerAppMode
import com.th3web.lean.data.model.AmneziaParams
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AwgConfigAdapterTest {
    private val adapter = AwgConfigAdapter()

    @Test
    fun `serializer follows pinned official order and preserves zero preshared key`() {
        val profile = profile(
            preSharedKey = key(0),
            allowedIps = listOf("0.0.0.0/0", "10.0.0.0/8"),
            persistentKeepalive = 25,
            awg = AmneziaParams(
                jc = 4,
                jmin = 8,
                jmax = 80,
                s1 = 15,
                s2 = 16,
                s3 = 17,
                s4 = 18,
                h1 = " 4294967291 ",
                h2 = " 4294967292 ",
                h3 = " 4294967293 ",
                h4 = " 4294967294 ",
                i1 = " <b 0x01> ",
                i2 = " <b 0x02> ",
                i3 = " <b 0x03> ",
                i4 = " <b 0x04> ",
                i5 = " <b 0x05> ",
            ),
        )

        val prepared = adapter.prepare(profile, policy())
        val lines = adapter.userspaceConfig(prepared, "203.0.113.8:51820").lines()

        assertEquals(
            listOf(
                "private_key", "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
                "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
                "replace_peers", "public_key", "allowed_ip", "allowed_ip",
                "endpoint", "persistent_keepalive_interval", "preshared_key", "",
            ),
            lines.map { it.substringBefore('=') },
        )
        assertTrue(lines[0].substringAfter('=').matches(Regex("[0-9a-f]{64}")))
        assertEquals("h1=4294967291", lines[8])
        assertEquals("h4=4294967294", lines[11])
        assertEquals("i1=<b 0x01>", lines[12])
        assertEquals("i5=<b 0x05>", lines[16])
        assertTrue(lines[23].substringAfter('=').matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `zero optional numeric and blank opaque fields are omitted`() {
        val prepared = adapter.prepare(profile(), policy())
        val serialized = adapter.userspaceConfig(prepared, "[2001:db8::1]:51820")

        listOf("jc=", "jmin=", "jmax=", "s1=", "h1=", "i1=", "preshared_key=").forEach {
            assertFalse(serialized.contains(it))
        }
        assertTrue(serialized.contains("endpoint=[2001:db8::1]:51820\n"))
        assertTrue(serialized.endsWith("\n"))
    }

    /**
     * Without a keepalive the core never starts a handshake by itself: bringing the device
     * up sends one only for a peer whose interval is above zero, so a profile at zero sat
     * waiting for whatever app on the phone first pushed a packet into the fresh tun. A
     * config that names its own interval keeps it.
     */
    @Test
    fun `a profile with no keepalive is given one so the handshake starts at once`() {
        assertEquals(25, adapter.prepare(profile(), policy()).persistentKeepalive)
        assertEquals(
            9,
            adapter.prepare(profile(persistentKeepalive = 9), policy()).persistentKeepalive,
        )
    }

    /**
     * What made a tunnel handshake, carry a few kilobytes and then go quiet.
     *
     * s4 pads EVERY transport packet, and those bytes ride OUTSIDE the tun's MTU — so a
     * perfectly ordinary 1280 still puts 1280 + 80 + s4 on the wire. Past the path MTU the
     * full-size packets vanish while everything small keeps working. The junk is therefore
     * subtracted from the frame the tun is allowed to hand the core.
     */
    @Test
    fun `transport junk is taken out of the tunnel's own frame size`() {
        val padded = adapter.prepare(
            profile(awg = AmneziaParams(s4 = 300), allowedIps = listOf("0.0.0.0/0")),
            policy(ipv6 = false),
        )
        // 1500 path - 80 outer - 300 junk.
        assertEquals(1120, padded.tunSpec.mtu)
    }

    /** With no per-packet padding the chosen number stands, untouched. */
    @Test
    fun `without transport junk the setting is what the tunnel gets`() {
        val plain = adapter.prepare(
            profile(awg = AmneziaParams(jc = 4), allowedIps = listOf("0.0.0.0/0")),
            policy(ipv6 = false),
        )
        assertEquals(1280, plain.tunSpec.mtu)
    }

    /**
     * A tunnel that routes IPv6 cannot go below its minimum link MTU whatever the junk
     * costs — dropping under it would break more than the padding does.
     */
    @Test
    fun `an ipv6 route holds the floor at 1280`() {
        val v6 = adapter.prepare(
            profile(
                localAddresses = listOf("10.0.0.2/32", "2001:db8::2/128"),
                allowedIps = listOf("0.0.0.0/0", "::/0"),
                awg = AmneziaParams(s4 = 400),
            ),
            policy(ipv6 = true),
        )
        assertEquals(1280, v6.tunSpec.mtu)
    }

    @Test
    fun `cidr parser emits canonical address text from the pinned parser`() {
        val prepared = adapter.prepare(
            profile(
                localAddresses = listOf(
                    "  2001:0db8:0000:0000:0000:0000:0000:0002/128  ",
                ),
                allowedIps = listOf(
                    "  2001:0db8:0000:0000:0000:0000:0000:0000/64  ",
                ),
            ),
            policy(),
        )

        assertEquals(
            listOf("2001:db8:0:0:0:0:0:2"),
            prepared.tunSpec.addresses.map { it.address },
        )
        assertEquals(
            "private_key=${prepared.privateKeyHex}\n" +
                "replace_peers=true\n" +
                "public_key=${prepared.publicKeyHex}\n" +
                "allowed_ip=2001:db8:0:0:0:0:0:0/64\n" +
                "endpoint=[2001:db8:0:0:0:0:0:8]:51820\n" +
                // The fixture asks for no keepalive; the adapter supplies one anyway,
                // because the core starts a handshake on its own ONLY for a peer that has
                // one. See DEFAULT_KEEPALIVE_SECONDS.
                "persistent_keepalive_interval=25\n",
            adapter.userspaceConfig(prepared, "[2001:db8:0:0:0:0:0:8]:51820"),
        )
    }

    @Test
    fun `validation rejects malformed values without echoing key material`() {
        val badKey = "not-a-wireguard-key"
        val error = runCatching {
            adapter.prepare(profile(privateKey = badKey), policy())
        }.exceptionOrNull()

        checkNotNull(error)
        assertTrue(error.message.orEmpty().contains("приват"))
        assertFalse(error.message.orEmpty().contains(badKey))

        assertFails("CIDR") { adapter.prepare(profile(localAddresses = listOf("10.0.0.2/99")), policy()) }
        assertFails("MTU") { adapter.prepare(profile(mtu = 100), policy()) }
        assertFails("keepalive") { adapter.prepare(profile(persistentKeepalive = 65_536), policy()) }
        assertFails("параметр") {
            adapter.prepare(profile(awg = AmneziaParams(jc = -1)), policy())
        }

        listOf(
            "***************************************=",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0xfb.toByte() }),
            Base64.getEncoder().encodeToString(ByteArray(31)),
            Base64.getEncoder().encodeToString(ByteArray(33)),
        ).forEach { invalid ->
            val failure = runCatching {
                adapter.prepare(profile(privateKey = invalid), policy())
            }.exceptionOrNull()
            checkNotNull(failure)
            assertFalse(failure.message.orEmpty().contains(invalid))
        }
    }

    @Test
    fun `validation rejects missing load bearing profile fields`() {
        assertFails("адрес") { adapter.prepare(profile(localAddresses = emptyList()), policy()) }
        assertFails("приват") { adapter.prepare(profile(privateKey = ""), policy()) }
        assertFails("публич") {
            adapter.prepare(profile().copy(
                outbound = (profile().outbound as Outbound.WireGuard).copy(peerPublicKey = ""),
            ), policy())
        }
        assertFails("хост") {
            adapter.prepare(profile().copy(
                outbound = (profile().outbound as Outbound.WireGuard).copy(server = ""),
            ), policy())
        }
        assertFails("порт") {
            adapter.prepare(profile().copy(
                outbound = (profile().outbound as Outbound.WireGuard).copy(serverPort = 0),
            ), policy())
        }
    }

    @Test
    fun `tun request uses profile data dns fallback and ipv6 gate`() {
        val enabled = adapter.prepare(
            profile(
                localAddresses = listOf("10.0.0.2/32", "2001:db8::2/128"),
                allowedIps = listOf("0.0.0.0/0", "::/0"),
                dnsServers = emptyList(),
                mtu = 0,
            ),
            policy(ipv6 = true),
        ).tunSpec
        assertEquals(1280, enabled.mtu)
        assertEquals(listOf("10.0.0.2"), enabled.ipv4Addresses.map { it.address })
        assertEquals(
            listOf("2001:db8:0:0:0:0:0:2"),
            enabled.ipv6Addresses.map { it.address },
        )
        assertEquals(listOf("0.0.0.0", "0:0:0:0:0:0:0:0"), enabled.routes.map { it.address })
        assertEquals(listOf("8.8.8.8", "2001:4860:4860::8888"), enabled.dnsServers)

        val disabled = adapter.prepare(
            profile(
                localAddresses = listOf("10.0.0.2/32", "2001:db8::2/128"),
                allowedIps = listOf("0.0.0.0/0", "::/0"),
                dnsServers = listOf("1.1.1.1", "2606:4700:4700::1111"),
            ),
            policy(ipv6 = false),
        ).tunSpec
        assertEquals(listOf("10.0.0.2"), disabled.addresses.map { it.address })
        assertEquals(listOf("0.0.0.0"), disabled.routes.map { it.address })
        assertEquals(listOf("1.1.1.1"), disabled.dnsServers)
    }

    private fun assertFails(fragment: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        checkNotNull(error)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(fragment, ignoreCase = true))
    }

    private fun policy(ipv6: Boolean = true) = TunRuntimePolicy(
        ipv6Enabled = ipv6,
        bypassPrivateNetworks = true,
        killSwitch = true,
        perAppMode = PerAppMode.OFF,
        perAppPackages = emptySet(),
    )

    private fun profile(
        privateKey: String = key(1),
        preSharedKey: String = "",
        localAddresses: List<String> = listOf("10.0.0.2/32"),
        allowedIps: List<String> = listOf("0.0.0.0/0"),
        persistentKeepalive: Int = 0,
        mtu: Int = 0,
        dnsServers: List<String> = emptyList(),
        awg: AmneziaParams = AmneziaParams(),
    ) = Profile(
        id = "awg",
        name = "AWG",
        outbound = Outbound.WireGuard(
            server = "vpn.example",
            serverPort = 51820,
            privateKey = privateKey,
            peerPublicKey = key(2),
            preSharedKey = preSharedKey,
            localAddresses = localAddresses,
            allowedIps = allowedIps,
            persistentKeepalive = persistentKeepalive,
            mtu = mtu,
            dnsServers = dnsServers,
            awg = awg,
        ),
    )

    private fun key(fill: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })
}
