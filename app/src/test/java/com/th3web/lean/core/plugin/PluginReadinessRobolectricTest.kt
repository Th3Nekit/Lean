package com.th3web.lean.core.plugin

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The check that decides whether a helper is ready to carry traffic.
 *
 * This is the gate a real failure walked straight through: a helper never bound its port,
 * the core came up anyway pointing a socks outbound at it, and a whole session logged
 *   open connection to …: dial tcp 127.0.0.1:37995: connect: connection refused
 * while the UI said «подключено». The old code only wrote a line about it; now a helper
 * that is not listening fails the connect instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginReadinessRobolectricTest {

    private val session = PluginSession(RuntimeEnvironment.getApplication())

    /**
     * "Not ready" as the production path means it.
     *
     * A refused connection or a listener that never answers surfaces as an exception, not
     * as false, which is exactly why awaitReady wraps the check — it keeps the message for
     * the error it shows the user. Asserting through the same lens keeps this test honest
     * about what the caller sees.
     */
    private fun ready(port: Int): Boolean =
        runCatching { PluginSession.speaksSocks5(port) }.getOrDefault(false)

    @Test
    fun `a port speaking SOCKS5 is ready`() {
        ServerSocket(0, 1, InetAddress.getByName(LOCALHOST)).use { server ->
            val accepting = thread {
                runCatching {
                    server.accept().use { client ->
                        // Read the greeting and answer "version 5, no authentication".
                        client.getInputStream().read(ByteArray(3))
                        client.getOutputStream().apply {
                            write(byteArrayOf(0x05, 0x00))
                            flush()
                        }
                    }
                }
            }
            assertTrue(ready(server.localPort))
            accepting.join(2_000)
        }
    }

    /**
     * Accepting a connection is NOT enough.
     *
     * A port can be held by something else entirely, and a helper still initialising can
     * accept before it can answer — which is exactly the window in which a tunnel used to
     * be declared up while carrying nothing. Only the greeting settles it.
     */
    @Test
    fun `a port that accepts but never answers is not ready`() {
        ServerSocket(0, 1, InetAddress.getByName(LOCALHOST)).use { server ->
            val accepting = thread { runCatching { server.accept() } }
            assertFalse(ready(server.localPort))
            accepting.join(2_000)
        }
    }

    /** Nothing listening at all — the case from the field report. */
    @Test
    fun `a closed port is not ready`() {
        val port = ServerSocket(0, 1, InetAddress.getByName(LOCALHOST)).use { it.localPort }
        // The socket above is closed by `use`, so nothing holds the port any more.
        assertFalse(ready(port))
    }

    /** A wrong first byte is a different protocol, not a helper. */
    @Test
    fun `a port answering something other than SOCKS5 is not ready`() {
        ServerSocket(0, 1, InetAddress.getByName(LOCALHOST)).use { server ->
            val accepting = thread {
                runCatching {
                    server.accept().use { client ->
                        client.getInputStream().read(ByteArray(3))
                        client.getOutputStream().apply {
                            write("HT".toByteArray())
                            flush()
                        }
                    }
                }
            }
            assertFalse(ready(server.localPort))
            accepting.join(2_000)
        }
    }

    /**
     * The regression this split exists for: helpers are independent processes spawned
     * together, so the readiness budget must be spent ONCE, not once per helper.
     *
     * Waiting in sequence turned an «Авто» group carrying several helper-backed nodes
     * into a connect that could sit for half a minute before admitting it had failed —
     * and charged every successful start in series too. Two dead helpers here must cost
     * about one budget between them, never two.
     */
    @Test
    fun `several helpers share one readiness budget instead of each paying its own`() {
        // Wide enough that a slow CI machine cannot blur the two outcomes: shared costs
        // about one budget, per-helper costs two.
        val budgetMs = 1_000L
        val session = PluginSession(RuntimeEnvironment.getApplication(), readyTimeoutMs = budgetMs)
        val bindings = listOf(deadBinding("a"), deadBinding("b"))

        val startedAt = System.nanoTime()
        val failed = session.awaitAllReady(bindings)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(setOf("a", "b"), failed.map { it.profileId }.toSet())
        assertTrue(
            "two dead helpers took ${elapsedMs}ms — that is a per-helper budget, not a shared one",
            elapsedMs < budgetMs * 2,
        )
    }

    /** Nothing to wait for is not a failure. */
    @Test
    fun `no helpers is not a failure`() {
        assertTrue(session.awaitAllReady(emptyList()).isEmpty())
    }

    /**
     * A helper that never binds is REPORTED, not thrown. Whether it is fatal depends on
     * what else is in the profile, and only the caller knows that — a single pinned
     * server has nothing to fall back to, while a 32-node subscription has thirty-one.
     */
    @Test
    fun `a helper that never binds comes back as a failure, not an exception`() {
        val session = PluginSession(RuntimeEnvironment.getApplication(), readyTimeoutMs = 200L)

        val failed = session.awaitAllReady(listOf(deadBinding("mieru-node")))

        assertEquals(1, failed.size)
        assertEquals("mieru-node", failed.single().profileId)
        assertTrue(failed.single().reason.contains("Mieru"))
    }

    /** A binding whose port nothing is listening on. */
    private fun deadBinding(profileId: String): PluginBinding {
        val port = ServerSocket(0, 1, InetAddress.getByName(LOCALHOST)).use { it.localPort }
        return PluginBinding(
            profileId = profileId,
            plugin = NativePlugin.Mieru,
            localPort = port,
            mappingPort = PluginSession.freePort(),
            serverHost = "example.com",
            serverPort = 443,
        )
    }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
