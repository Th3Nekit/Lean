package com.th3web.lean.core

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.th3web.lean.core.engine.NekoBox
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile

class CoreStatusClientTest {
    @Test
    fun `failure tail keeps only newest sanitized native lines`() {
        val file = Files.createTempFile("lean-native-log", ".log").toFile()
        file.writeText(
            (0..9).joinToString("\n") { "$it token=secret-$it" },
        )

        val lines = NativeLogTail(file).readLastLines(3)

        assertEquals(listOf("7 token=<redacted>", "8 token=<redacted>", "9 token=<redacted>"), lines)
    }

    @Test
    fun `native log sanitizer removes credentials from structured and query values`() {
        val cases = listOf(
            """private_key="private-value", peer=public""",
            "preshared-key=psk-value peer=public",
            "password: password-value host=example.com",
            """{"token":"token-value","event":"connected"}""",
            "https://example.com/path?access_token=query-value&mode=safe",
            "uuid=12345678-1234-1234-1234-123456789abc node=one",
        )
        val secrets = listOf(
            "private-value",
            "psk-value",
            "password-value",
            "token-value",
            "query-value",
            "12345678-1234-1234-1234-123456789abc",
        )

        cases.zip(secrets).forEach { (line, secret) ->
            val sanitized = CoreStatusLogSanitizer.sanitize(line)

            assertFalse("$secret leaked in $sanitized", sanitized.contains(secret))
            assertTrue(sanitized.contains("<redacted>"))
        }
    }

    @Test
    fun `native log sanitizer removes authentication headers and cookies`() {
        listOf(
            "Authorization: Bearer bearer-value",
            "Proxy-Authorization: Basic proxy-value",
            "Cookie: session=cookie-value; theme=dark",
            "Set-Cookie: session=set-cookie-value; Secure",
        ).forEach { line ->
            val sanitized = CoreStatusLogSanitizer.sanitize(line)

            assertFalse(sanitized.contains("value"))
            assertTrue(sanitized.endsWith("<redacted>"))
        }
    }

    @Test
    fun `native log sanitizer removes share links and generic uri user info`() {
        val share = CoreStatusLogSanitizer.sanitize(
            "dial failed vless://user-secret@example.com:443?security=tls#node",
        )
        val generic = CoreStatusLogSanitizer.sanitize(
            "upstream https://api-user:api-password@example.com/path unavailable",
        )

        assertEquals("dial failed <redacted>", share)
        assertEquals("upstream https://<redacted>@example.com/path unavailable", generic)
    }

    @Test
    fun `native log sanitizer bounds hostile lines before publishing`() {
        val sanitized = CoreStatusLogSanitizer.sanitize("x".repeat(8_192))

        assertEquals(4_096, sanitized.length)
    }

    @Test
    fun `close waits for in flight native query before returning`() = runBlocking {
        val queryEntered = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val client = client(
            box = BlockingBox(queryEntered, releaseQuery),
            profiles = listOf(profile("a")),
        )
        client.start("a")
        assertTrue(queryEntered.await(5, TimeUnit.SECONDS))

        val closing = async(Dispatchers.Default) { client.close() }
        delay(100)

        assertFalse("close returned while JNI query was still running", closing.isCompleted)
        releaseQuery.countDown()
        closing.await()
    }

    @Test
    fun `automatic profile publishes the auto group contract`() = runBlocking {
        val client = client(
            box = IdleBox,
            profiles = listOf(profile("a"), profile("b")),
        )

        client.start(CoreManager.AUTO_PROFILE_ID)

        assertEquals("auto", CoreManager.groups.value.single().tag)
        client.selectorChanged("proxy", "node-b")
        assertEquals("node-b", CoreManager.groups.value.single().selected)
        client.close()
    }

    private fun client(box: NekoBox, profiles: List<Profile>) = CoreStatusClient(
        parentScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default),
        generation = 1L,
        isCurrent = { true },
        box = box,
        profiles = profiles,
        cacheDir = Files.createTempDirectory("lean-status-test").toFile(),
    )

    private fun profile(id: String) = Profile(
        id = id,
        name = id,
        outbound = Outbound.WireGuard(
            server = "1.2.3.4",
            serverPort = 51820,
            privateKey = "private",
            peerPublicKey = "public",
            localAddresses = listOf("10.0.0.2/32"),
        ),
    )

    private class BlockingBox(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : NekoBox by IdleBox {
        override fun queryStats(tag: String, direction: String): Long {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return 1L
        }
    }

    private object IdleBox : NekoBox {
        override fun setAsMain() = Unit
        override fun setV2rayStats(tags: String) = Unit
        override fun start() = Unit
        override fun close() = Unit
        override fun queryStats(tag: String, direction: String): Long = 0L
        override fun selectOutbound(tag: String): Boolean = true
    }
}
