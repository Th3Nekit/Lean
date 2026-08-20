package com.th3web.lean.data.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class CrashHttpTransport(
    val endpoint: URL = URL(DEFAULT_ENDPOINT),
    /**
     * How the connection is opened, so a report can leave over the network under the
     * tunnel. See [PhysicalNetwork]: a report is wanted precisely when the tunnel carries
     * nothing, and opened the default way it went into that tunnel and timed out, the
     * button looked broken exactly when it mattered.
     */
    private val open: (URL) -> java.net.URLConnection = URL::openConnection,
) : CrashTransport {
    val followRedirects = false
    val connectTimeoutMs = 10_000
    val readTimeoutMs = 10_000
    val requestHeaders = mapOf("Content-Type" to "application/json; charset=utf-8")

    init {
        require(endpoint.protocol.equals("https", ignoreCase = true))
    }

    override fun post(body: ByteArray): CrashHttpResponse {
        require(body.size < CrashCodec.MAX_REQUEST_BYTES)
        val connection = open(endpoint)
        require(connection is HttpsURLConnection)
        return connection.run {
            requestMethod = "POST"
            instanceFollowRedirects = followRedirects
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            useCaches = false
            requestHeaders.forEach(::setRequestProperty)
            setFixedLengthStreamingMode(body.size)
            try {
                outputStream.use { it.write(body) }
                val status = responseCode
                val input = if (status >= 400) errorStream else inputStream
                val response = input?.use(::readBounded) ?: BoundedResponse(ByteArray(0), false)
                CrashHttpResponse(status, response.bytes, response.truncated)
            } finally {
                disconnect()
            }
        }
    }

    data class BoundedResponse(val bytes: ByteArray, val truncated: Boolean)

    companion object {
        const val MAX_RESPONSE_BYTES = 4_096
        private const val DEFAULT_ENDPOINT = "https://th3web.com/lean/crash"

        fun readBounded(input: InputStream): BoundedResponse {
            val output = ByteArrayOutputStream(MAX_RESPONSE_BYTES)
            val buffer = ByteArray(1_024)
            var remaining = MAX_RESPONSE_BYTES
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) return BoundedResponse(output.toByteArray(), false)
                if (read == 0) continue
                output.write(buffer, 0, read)
                remaining -= read
            }
            return BoundedResponse(output.toByteArray(), input.read() != -1)
        }
    }
}
