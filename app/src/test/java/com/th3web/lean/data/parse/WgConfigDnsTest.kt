package com.th3web.lean.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.th3web.lean.data.Serialization
import com.th3web.lean.data.StoreCodec
import com.th3web.lean.data.StoreData
import com.th3web.lean.data.model.Outbound

class WgConfigDnsTest {
    @Test
    fun `import keeps only literal dns addresses in order`() {
        val parsed = checkNotNull(
            WgConfig.parse(
                """
                [Interface]
                PrivateKey = synthetic
                Address = 10.0.0.2/32
                DNS = 1.1.1.1, https://dns.example/dns-query, 2606:4700:4700::1111
                Jc = 4
                [Peer]
                PublicKey = synthetic
                Endpoint = vpn.example:51820
                AllowedIPs = 0.0.0.0/0
                """.trimIndent(),
                "AWG",
            ),
        )

        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), parsed.dnsServers)
    }

    @Test
    fun `legacy store without dns decodes and new store round trips dns`() {
        val legacy =
            """{"profiles":[{"id":"p","name":"AWG","outbound":{"type":"wireguard","server":"vpn.example","serverPort":51820,"privateKey":"synthetic","peerPublicKey":"synthetic","localAddresses":["10.0.0.2/32"],"awg":{"jc":4}}}]}"""
        val decoded = StoreCodec.decodeStore(legacy)
        assertTrue(!decoded.degraded)
        val oldOutbound = decoded.data.profiles.single().outbound as Outbound.WireGuard
        assertEquals(emptyList<String>(), oldOutbound.dnsServers)

        val updated = decoded.data.copy(
            profiles = listOf(
                decoded.data.profiles.single().copy(
                    outbound = oldOutbound.copy(dnsServers = listOf("9.9.9.9")),
                ),
            ),
        )
        val json = Serialization.json.encodeToString(StoreData.serializer(), updated)
        val roundTrip = StoreCodec.decodeStore(json)
        val newOutbound = roundTrip.data.profiles.single().outbound as Outbound.WireGuard
        assertEquals(listOf("9.9.9.9"), newOutbound.dnsServers)
    }
}
