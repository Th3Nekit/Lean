package com.th3web.lean.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashRedactorTest {

    @Test
    fun redactsEverySupportedSecretClassAcrossAllCrashFields() {
        val samples = listOf(
            "PrivateKey = synthetic-private-key",
            "PresharedKey=synthetic-preshared-key",
            "password: synthetic-password",
            "passwd=synthetic-passwd",
            "pwd=synthetic-pwd",
            "authStr=synthetic-auth",
            "auth_token=synthetic-auth-token",
            "Authorization: Bearer synthetic-bearer",
            "Cookie: session=synthetic-cookie; password=synthetic-cookie-password",
            "access_token=synthetic-access",
            "refreshToken=synthetic-refresh",
            "api_key=synthetic-api-key",
            "client_secret=synthetic-client-secret",
            """{"client_secret":"synthetic-json-secret"}""",
            "sub=https://subscription.example/synthetic-subscription",
            "https://synthetic-user:synthetic-pass@example.invalid/path?token=synthetic-query#secret=synthetic-fragment",
            "-----BEGIN PRIVATE KEY-----\nsynthetic-pem-body\n-----END PRIVATE KEY-----",
            "-----BEGIN PRIVATE KEY-----\nsynthetic-incomplete-pem",
            "vless://018f8f10-87ea-7cc5-9c6d-123456789abc@example.invalid:443",
            "vmess://c3ludGhldGljLXZtZXNz",
            "trojan://synthetic-trojan@example.invalid:443",
            "ss://synthetic-shadowsocks@example.invalid:443",
            "hysteria2://synthetic-hysteria@example.invalid:443",
            "tuic://synthetic-tuic@example.invalid:443",
            "wireguard://synthetic-wireguard@example.invalid:443",
            "awg://synthetic-awg@example.invalid:443",
            "connection failed for vless://synthetic-inline@example.invalid:443",
            "profile id 018f8f10-87ea-7cc5-9c6d-123456789abc",
        )

        val input = samples.joinToString("\n")
        val outputs = listOf(
            CrashRedactor.message(input),
            CrashRedactor.stackTrace(input),
            CrashRedactor.logLine(input),
        )

        val forbidden = listOf(
            "synthetic-private-key",
            "synthetic-preshared-key",
            "synthetic-password",
            "synthetic-passwd",
            "synthetic-pwd",
            "synthetic-auth",
            "synthetic-auth-token",
            "synthetic-bearer",
            "synthetic-cookie",
            "synthetic-access",
            "synthetic-refresh",
            "synthetic-api-key",
            "synthetic-client-secret",
            "synthetic-json-secret",
            "synthetic-subscription",
            "synthetic-user",
            "synthetic-pass",
            "synthetic-query",
            "synthetic-fragment",
            "synthetic-pem-body",
            "synthetic-incomplete-pem",
            "018f8f10-87ea-7cc5-9c6d-123456789abc",
            "c3ludGhldGljLXZtZXNz",
            "synthetic-trojan",
            "synthetic-shadowsocks",
            "synthetic-hysteria",
            "synthetic-tuic",
            "synthetic-wireguard",
            "synthetic-awg",
            "synthetic-inline",
        )

        outputs.forEach { output ->
            forbidden.forEach { secret ->
                assertFalse("$secret leaked in $output", output.contains(secret, ignoreCase = true))
            }
            assertTrue(output.contains(CrashRedactor.REDACTED))
        }
    }

    @Test
    fun redactionIsIdempotentAndRemovesUnsafeControls() {
        val input = "password=synthetic-password\u0000\u0008\u001B\u007F\u202E\nnext"
        val once = CrashRedactor.stackTrace(input)

        assertEquals(once, CrashRedactor.stackTrace(once))
        assertFalse(once.any { it == '\u0000' || it == '\u0008' || it == '\u001B' || it == '\u007F' || it == '\u202E' })
        assertTrue(once.contains('\n'))
    }

    @Test
    fun eachFieldAndLogCollectionIsStrictlyBounded() {
        val huge = "x".repeat(200_000) + " password=synthetic-tail-secret"
        val message = CrashRedactor.message(huge)
        val stack = CrashRedactor.stackTrace(huge)
        val logs = CrashRedactor.logLines(
            (0 until 200).map { index ->
                "$index " + "y".repeat(2_000) + " token=synthetic-log-$index"
            },
        )

        assertTrue(message.length <= CrashRedactor.MAX_MESSAGE_CHARS)
        assertTrue(stack.length <= CrashRedactor.MAX_STACK_TRACE_CHARS)
        assertTrue(logs.size <= CrashRedactor.MAX_LOG_LINES)
        assertTrue(logs.all { it.length <= CrashRedactor.MAX_LOG_LINE_CHARS })
        assertTrue(logs.sumOf(String::length) <= CrashRedactor.MAX_LOG_TOTAL_CHARS)
        assertFalse(logs.joinToString().contains("synthetic-log"))
    }

    @Test
    fun truncatingASecretDoesNotExposeItsRetainedPrefix() {
        val input = "z".repeat(CrashRedactor.MAX_MESSAGE_CHARS + 100) +
            " password=synthetic-secret-that-crosses-the-boundary"

        val output = CrashRedactor.message(input)

        assertTrue(output.length <= CrashRedactor.MAX_MESSAGE_CHARS)
        assertFalse(output.contains("synthetic", ignoreCase = true))
        assertTrue(output.endsWith(CrashRedactor.TRUNCATED))
    }
}
