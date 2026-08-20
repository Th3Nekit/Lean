package com.th3web.lean.core.awg

import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgEndpointResolverTest {
    @Test
    fun `uses selected network and formats ipv4 and ipv6`() = runBlocking {
        val selected = Any()
        var used: Any? = null
        val ipv4 = SelectedNetworkEndpointResolver(
            networkProvider = { selected },
            lookup = { network, _ ->
                used = network
                listOf(InetAddress.getByName("203.0.113.7"))
            },
            timeoutMs = 1_000,
        )
        val resolvedV4 = ipv4.resolve("vpn.example", 51820)
        assertEquals("203.0.113.7:51820", resolvedV4.endpoint)
        assertSame(selected, resolvedV4.networkToken)
        assertSame(selected, used)

        val ipv6 = SelectedNetworkEndpointResolver(
            networkProvider = { selected },
            lookup = { _, _ -> listOf(InetAddress.getByName("2001:db8::7")) },
            timeoutMs = 1_000,
        )
        val resolvedV6 = ipv6.resolve("vpn.example", 443)
        assertEquals("[2001:db8:0:0:0:0:0:7]:443", resolvedV6.endpoint)
        assertSame(selected, resolvedV6.networkToken)
    }

    @Test
    fun `rejects input timeout no result and lookup failure with stable errors`() = runBlocking {
        val resolver = SelectedNetworkEndpointResolver(
            networkProvider = { Any() },
            lookup = { _, _ -> emptyList() },
            timeoutMs = 1_000,
        )
        assertError("Хост") { resolver.resolve(" ", 51820) }
        assertError("Порт") { resolver.resolve("vpn.example", 0) }
        assertError("адрес не найден") { resolver.resolve("vpn.example", 51820) }

        val timeout = SelectedNetworkEndpointResolver(
            networkProvider = { Any() },
            lookup = { _, _ -> delay(100); emptyList() },
            timeoutMs = 5,
        )
        assertError("время ожидания") { timeout.resolve("vpn.example", 51820) }

        val failure = SelectedNetworkEndpointResolver(
            networkProvider = { Any() },
            lookup = { _, _ -> error("resolver internals") },
            timeoutMs = 1_000,
        )
        val message = assertError("физическую сеть") { failure.resolve("vpn.example", 51820) }
        assertTrue(!message.contains("resolver internals"))
    }

    @Test
    fun `caller cancellation is never translated into a resolver error`() {
        val error = runCatching {
            runBlocking {
                coroutineScope {
                    val resolver = SelectedNetworkEndpointResolver(
                        networkProvider = { Any() },
                        lookup = { _, _ ->
                            cancel("caller stopped")
                            delay(1)
                            emptyList()
                        },
                        timeoutMs = 1_000,
                    )
                    resolver.resolve("vpn.example", 51820)
                }
            }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }

    private suspend fun assertError(fragment: String, block: suspend () -> Unit): String {
        val error = runCatching { block() }.exceptionOrNull()
        checkNotNull(error)
        val message = error.message.orEmpty()
        assertTrue(message, message.contains(fragment, ignoreCase = true))
        return message
    }
}
