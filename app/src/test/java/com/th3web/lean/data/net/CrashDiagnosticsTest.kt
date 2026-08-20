package com.th3web.lean.data.net

import android.content.Context
import android.content.ContextWrapper
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CrashDiagnosticsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun payloadIsExactBoundedVersionOneJsonWithoutDeviceOrCredentials() {
        val payload = payload()
        val encoded = CrashCodec.encodePayload(payload)
        val json = JSONObject(encoded.toString(Charsets.UTF_8))

        assertEquals(
            setOf(
                "schema_version",
                "app_version",
                "exception_type",
                "message",
                "stack_trace",
                "log_tail",
            ),
            json.keys().asSequence().toSet(),
        )
        assertEquals(1, json.getInt("schema_version"))
        assertTrue(encoded.size < CrashCodec.MAX_REQUEST_BYTES)
        listOf(
            "device",
            "android",
            "abi",
            "hwid",
            "profile",
            "subscription",
            "token",
            "authorization",
            "cookie",
        ).forEach { forbidden ->
            assertFalse(encoded.toString(Charsets.UTF_8).contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun factoryAndCodecMatchServerFieldLimits() {
        val payload = CrashPayloadFactory(
            appVersion = { "v".repeat(100) },
            logs = { emptyList() },
        ).create(IllegalStateException("bounded"))

        assertEquals(32, payload.appVersion.length)
        assertTrue(payload.exceptionType.length <= 120)
        assertThrows(IllegalArgumentException::class.java) {
            CrashCodec.encodePayload(payload.copy(appVersion = "v".repeat(33)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CrashCodec.encodePayload(payload.copy(exceptionType = "e".repeat(121)))
        }
    }

    @Test
    fun manualDiagnosticsContainRedactedLogsWithoutFakeCrashData() {
        val payload = CrashPayloadFactory(
            appVersion = { "0.9.4" },
            logs = { listOf("connect failed token=secret-value") },
        ).createManual()

        assertEquals("ManualDiagnostics", payload.exceptionType)
        assertEquals("User submitted diagnostics", payload.message)
        assertEquals("", payload.stackTrace)
        assertFalse(payload.logTail.single().contains("secret-value"))
    }

    @Test
    fun throwableAndLogsAreRedactedAndBoundedBeforeAtomicPersistence() {
        val secret = "synthetic-private-material"
        val store = AtomicCrashStore(temporaryFolder.newFolder("redacted"))
        val factory = CrashPayloadFactory(
            appVersion = { "0.9.4" },
            logs = {
                (0 until 200).map {
                    "$it Authorization: Bearer $secret " + "l".repeat(1_000)
                }
            },
        )

        store.save(
            CrashEnvelope.create(
                payload = factory.create(
                    IllegalStateException("password=$secret"),
                ),
                capturedAtEpochMs = 1_000L,
            ),
        )

        val bytes = store.baseFile.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains(secret))
        assertTrue(text.contains(CrashRedactor.REDACTED))
        assertTrue(bytes.size <= CrashCodec.MAX_LOCAL_BYTES)
        assertEquals(CrashRedactor.MAX_LOG_LINES, store.load()!!.payload.logTail.size)
    }

    @Test
    fun hugeCauseAndSuppressedGraphsStayBounded() {
        var root: Throwable = IllegalStateException(
            "password=synthetic-secret-" + "x".repeat(200_000),
        )
        repeat(200) { index ->
            root = IllegalArgumentException("cause-$index token=synthetic-$index", root)
            repeat(20) { suppressed ->
                root.addSuppressed(RuntimeException("suppressed-$suppressed password=synthetic"))
            }
        }

        val payload = CrashPayloadFactory({ "0.9.4" }, { emptyList() }).create(root)

        assertTrue(payload.message.length <= CrashRedactor.MAX_MESSAGE_CHARS)
        assertTrue(payload.stackTrace.length <= CrashRedactor.MAX_STACK_TRACE_CHARS)
        assertFalse(payload.message.contains("synthetic"))
        assertFalse(payload.stackTrace.contains("synthetic"))
        assertTrue(CrashCodec.encodePayload(payload).size < CrashCodec.MAX_REQUEST_BYTES)
    }

    @Test
    fun corruptOversizedExtraAndOldLocalEnvelopesAreDeleted() {
        val cases = listOf(
            "{not-json",
            "x".repeat(CrashCodec.MAX_LOCAL_BYTES + 1),
            CrashCodec.encodeEnvelope(CrashEnvelope.create(payload(), 1_000L))
                .toString(Charsets.UTF_8)
                .dropLast(1) + ""","extra":"rejected"}""",
            CrashCodec.encodeEnvelope(CrashEnvelope.create(payload(), 1_000L))
                .toString(Charsets.UTF_8)
                .replace("\"local_schema_version\":1", "\"local_schema_version\":2"),
        )

        cases.forEachIndexed { index, content ->
            val store = AtomicCrashStore(temporaryFolder.newFolder("invalid-$index"))
            store.baseFile.writeText(content)
            assertNull(store.load())
            assertFalse(store.hasArtifacts())
        }
    }

    @Test
    fun boundedCrashReadAcceptsTheLimitAndRejectsOverflow() {
        val exact = ByteArray(4) { it.toByte() }

        assertTrue(exact.contentEquals(ByteArrayInputStream(exact).readBounded(4)))
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(5)).readBounded(4)
        }
    }

    @Test
    fun atomicBackupIsRecoveredAndTempArtifactsAreRemovedOnOptOut() {
        val store = AtomicCrashStore(temporaryFolder.newFolder("atomic"))
        val expected = CrashEnvelope.create(payload(), 2_000L)
        store.save(expected)
        val backup = File(store.baseFile.path + ".bak")
        assertTrue(store.baseFile.renameTo(backup))
        File(store.baseFile.path + ".new").writeText("partial")

        assertEquals(expected, store.load())
        store.delete()

        assertFalse(store.hasArtifacts())
    }

    @Test
    fun consentDefaultsOffAndDisablingFlipsBeforeDeleteAndCancelsWork() {
        val store = MemoryCrashStore(CrashEnvelope.create(payload(), 1_000L))
        val scheduler = FakeCrashScheduler()
        lateinit var controller: CrashConsentController
        store.onDelete = { assertFalse(controller.isEnabled()) }
        controller = CrashConsentController(store, scheduler)

        assertFalse(controller.isEnabled())
        controller.setEnabled(false)

        assertNull(store.load())
        assertEquals(1, scheduler.cancelCount)
        assertEquals(0, scheduler.enqueueCount)
    }

    @Test
    fun diagnosticsCannotCrashAppWhenPrivateStorageIsUnavailable() {
        val base: Context = RuntimeEnvironment.getApplication()
        val blockedFilesDir = temporaryFolder.newFile("not-a-directory")
        val brokenContext = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = blockedFilesDir
        }

        CrashReporter.resetForTest()
        try {
            CrashReporter.install(brokenContext)
            CrashReporter.setEnabled(brokenContext, true)
            assertEquals(CrashDeliveryResult.NothingToDo, CrashReporter.deliver(brokenContext))
        } finally {
            CrashReporter.resetForTest()
        }
    }

    @Test
    fun enablingCreatesNothingAndCannotResurrectDeletedPreConsentData() {
        val store = MemoryCrashStore(CrashEnvelope.create(payload(), 1_000L))
        val scheduler = FakeCrashScheduler()
        val controller = CrashConsentController(store, scheduler)

        controller.setEnabled(false)
        controller.setEnabled(true)

        assertTrue(controller.isEnabled())
        assertNull(store.load())
        assertEquals(0, scheduler.enqueueCount)
    }

    @Test
    fun enablingWithCurrentConsentedEnvelopeSchedulesOneUniqueUpload() {
        val store = MemoryCrashStore(CrashEnvelope.create(payload(), 1_000L))
        val scheduler = FakeCrashScheduler()
        val controller = CrashConsentController(store, scheduler)

        controller.setEnabled(true)
        controller.setEnabled(true)

        assertEquals(2, scheduler.enqueueCount)
        assertEquals(CrashWorkScheduler.UNIQUE_WORK_NAME, scheduler.lastWorkName)
    }

    @Test
    fun handlerCapturesOnlyWithConsentAndAlwaysChainsExactlyOnce() {
        val chained = AtomicInteger()
        val captured = AtomicInteger()
        val previous = Thread.UncaughtExceptionHandler { _, _ -> chained.incrementAndGet() }
        var consent = false
        val handler = CrashUncaughtHandler(
            previous = previous,
            enabled = { consent },
            capture = { captured.incrementAndGet() },
        )

        handler.uncaughtException(Thread.currentThread(), RuntimeException("off"))
        consent = true
        handler.uncaughtException(Thread.currentThread(), RuntimeException("on"))

        assertEquals(1, captured.get())
        assertEquals(2, chained.get())
    }

    @Test
    fun handlerStillChainsOnceWhenCaptureFailsAndInstallerWrapsOnlyOnce() {
        val chained = AtomicInteger()
        val previous = Thread.UncaughtExceptionHandler { _, _ -> chained.incrementAndGet() }
        var current: Thread.UncaughtExceptionHandler? = previous
        var replacements = 0
        val installer = CrashHandlerInstaller(
            current = { current },
            replace = {
                replacements++
                current = it
            },
            create = { prior ->
                CrashUncaughtHandler(prior, { true }) { error("synthetic capture failure") }
            },
        )

        installer.install()
        installer.install()
        current!!.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(1, replacements)
        assertEquals(1, chained.get())
    }

    @Test
    fun deliveryDeletesOnlyExactSuccessAndPermanentResponses() {
        val successStore = MemoryCrashStore(CrashEnvelope.create(payload(), NOW))
        assertEquals(
            CrashDeliveryResult.Delivered,
            delivery(successStore, 202, """{"report_id":"abcdefghij","status":"accepted"}""").deliver(),
        )
        assertNull(successStore.load())

        listOf(
            400 to """{"detail":"bad"}""",
            404 to """{"detail":"missing"}""",
            413 to """{"detail":"large"}""",
            415 to """{"detail":"type"}""",
            422 to """{"detail":"schema"}""",
        ).forEach { (status, body) ->
            val store = MemoryCrashStore(CrashEnvelope.create(payload(), NOW))
            val result = delivery(store, status, body).deliver()
            assertEquals(CrashDeliveryResult.Dropped, result)
            assertNull(store.load())
        }
    }

    @Test
    fun deliveryPersistsAttemptBeforeCallingTransport() {
        val store = MemoryCrashStore(CrashEnvelope.create(payload(), NOW))
        var observedAttempt: CrashEnvelope? = null
        val result = CrashDelivery(
            store = store,
            transport = CrashTransport {
                observedAttempt = store.load()
                throw java.io.IOException("synthetic offline")
            },
            clock = CrashClock { NOW },
            enabled = { true },
        ).deliver()

        assertEquals(CrashDeliveryResult.Retry, result)
        assertEquals(1, observedAttempt!!.attemptCount)
        assertTrue(observedAttempt!!.nextAttemptAtEpochMs > NOW)
        assertEquals(observedAttempt, store.load())
    }

    @Test
    fun deliveryRetainsTransientFailuresWithBoundedBackoff() {
        listOf(408, 429, 500, 503).forEach { status ->
            val store = MemoryCrashStore(CrashEnvelope.create(payload(), NOW))
            val result = delivery(store, status, """{"detail":"later"}""").deliver()

            assertEquals(CrashDeliveryResult.Retry, result)
            val retained = store.load()!!
            assertEquals(1, retained.attemptCount)
            assertTrue(retained.nextAttemptAtEpochMs > NOW)
        }
    }

    @Test
    fun ioFailureRetainsButRedirectAuthMalformedAndLargeSuccessExpire() {
        val ioStore = MemoryCrashStore(CrashEnvelope.create(payload(), NOW))
        val ioResult = CrashDelivery(
            store = ioStore,
            transport = CrashTransport { throw java.io.IOException("synthetic offline") },
            clock = CrashClock { NOW },
            enabled = { true },
        ).deliver()
        assertEquals(CrashDeliveryResult.Retry, ioResult)
        assertTrue(ioStore.load() != null)

        listOf(
            CrashHttpResponse(302, ByteArray(0)),
            CrashHttpResponse(401, ByteArray(0)),
            CrashHttpResponse(202, """{"status":"accepted"}""".toByteArray()),
            CrashHttpResponse(202, ByteArray(4_097), bodyTruncated = true),
        ).forEach { response ->
            val store = MemoryCrashStore(CrashEnvelope.create(payload(), NOW))
            val result = CrashDelivery(
                store,
                CrashTransport { response },
                CrashClock { NOW },
                enabled = { true },
            ).deliver()
            assertEquals(CrashDeliveryResult.Dropped, result)
            assertNull(store.load())
        }
    }

    @Test
    fun deliveryExpiresByConsentAgeAttemptsAndNextAttemptWithoutSending() {
        val cases = listOf(
            CrashEnvelope.create(payload(), NOW - CrashDelivery.MAX_AGE_MS - 1),
            CrashEnvelope.create(payload(), NOW).copy(attemptCount = CrashDelivery.MAX_ATTEMPTS),
        )
        cases.forEach { envelope ->
            val store = MemoryCrashStore(envelope)
            val transport = CountingTransport()
            val result = CrashDelivery(store, transport, CrashClock { NOW }, { true }).deliver()
            assertEquals(CrashDeliveryResult.Dropped, result)
            assertEquals(0, transport.calls)
            assertNull(store.load())
        }

        val future = CrashEnvelope.create(payload(), NOW).copy(nextAttemptAtEpochMs = NOW + 1_000)
        val futureStore = MemoryCrashStore(future)
        val transport = CountingTransport()
        assertEquals(
            CrashDeliveryResult.Retry,
            CrashDelivery(futureStore, transport, CrashClock { NOW }, { true }).deliver(),
        )
        assertEquals(0, transport.calls)

        val offStore = MemoryCrashStore(CrashEnvelope.create(payload(), NOW))
        val offTransport = CountingTransport()
        assertEquals(
            CrashDeliveryResult.Dropped,
            CrashDelivery(offStore, offTransport, CrashClock { NOW }, { false }).deliver(),
        )
        assertEquals(0, offTransport.calls)
        assertNull(offStore.load())
    }

    @Test
    fun productionTransportPolicyIsStrictHttpsJsonFiniteAndCredentialFree() {
        val transport = CrashHttpTransport()

        assertEquals("https", transport.endpoint.protocol)
        assertEquals("th3web.com", transport.endpoint.host)
        assertEquals("/lean/crash", transport.endpoint.path)
        assertFalse(transport.followRedirects)
        assertTrue(transport.connectTimeoutMs in 1..30_000)
        assertTrue(transport.readTimeoutMs in 1..30_000)
        assertEquals(
            mapOf("Content-Type" to "application/json; charset=utf-8"),
            transport.requestHeaders,
        )
        val headerText = transport.requestHeaders.toString().lowercase()
        assertFalse(headerText.contains("authorization"))
        assertFalse(headerText.contains("token"))
        assertFalse(headerText.contains("cookie"))
    }

    @Test
    fun responseReaderIsBoundedAndMarksOverflow() {
        val response = CrashHttpTransport.readBounded(
            ByteArrayInputStream(ByteArray(10_000) { 1 }),
        )

        assertEquals(CrashHttpTransport.MAX_RESPONSE_BYTES, response.bytes.size)
        assertTrue(response.truncated)
    }

    @Test
    fun manualDiagnosticsRequireExactAcceptedResponse() {
        val factory = CrashPayloadFactory({ "0.9.4" }, { listOf("native start failed") })
        val accepted = ManualDiagnosticsSender(
            factory,
            CrashTransport {
                CrashHttpResponse(
                    202,
                    """{"report_id":"AbCdEf123456","status":"accepted"}"""
                        .toByteArray(Charsets.UTF_8),
                )
            },
        )
        val rejected = ManualDiagnosticsSender(
            factory,
            CrashTransport {
                CrashHttpResponse(202, """{"status":"accepted"}""".toByteArray(Charsets.UTF_8))
            },
        )

        assertEquals(ManualDiagnosticsResult.Sent, accepted.send())
        assertEquals(ManualDiagnosticsResult.Failed, rejected.send())
    }

    @Test
    fun publicIssuesMetadataAndDisclosureAreStable() {
        assertEquals("https://github.com/Th3Nekit/Lean/issues", CrashReporter.PUBLIC_ISSUES_URL)
        assertEquals("Сообщить об ошибке", CrashReporter.PUBLIC_ISSUES_LABEL)
        assertTrue(CrashReporter.CONSENT_DISCLOSURE.contains("обезличенный стектрейс"))
        assertTrue(CrashReporter.CONSENT_DISCLOSURE.contains("отключение удаляет"))
    }

    private fun delivery(
        store: MemoryCrashStore,
        status: Int,
        responseBody: String,
    ) = CrashDelivery(
        store = store,
        transport = CrashTransport {
            CrashHttpResponse(status, responseBody.toByteArray(Charsets.UTF_8))
        },
        clock = CrashClock { NOW },
        enabled = { true },
    )

    private fun payload() = CrashPayload(
        appVersion = "0.9.4",
        exceptionType = "java.lang.IllegalStateException",
        message = "bounded message",
        stackTrace = "java.lang.IllegalStateException\n\tat synthetic.Test.run(Test.kt:1)",
        logTail = listOf("safe log"),
    )

    private class MemoryCrashStore(
        private var value: CrashEnvelope?,
    ) : CrashStore {
        var onDelete: (() -> Unit)? = null

        override fun save(envelope: CrashEnvelope) {
            value = envelope
        }

        override fun load(): CrashEnvelope? = value

        override fun delete() {
            onDelete?.invoke()
            value = null
        }

        override fun hasArtifacts(): Boolean = value != null
    }

    private class FakeCrashScheduler : CrashWorkScheduler {
        var enqueueCount = 0
        var cancelCount = 0
        var lastWorkName: String? = null

        override fun enqueue() {
            enqueueCount++
            lastWorkName = CrashWorkScheduler.UNIQUE_WORK_NAME
        }

        override fun cancel() {
            cancelCount++
        }
    }

    private class CountingTransport : CrashTransport {
        var calls = 0

        override fun post(body: ByteArray): CrashHttpResponse {
            calls++
            return CrashHttpResponse(500, ByteArray(0))
        }
    }

    private companion object {
        const val NOW = 2_000_000_000L
    }
}
