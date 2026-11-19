/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.adobe.marketing.mobile.concierge.headless.internal

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal class HttpRequest(
    val url: String,
    val body: String,
    val headers: Map<String, String>
)

/** An open HTTP response. [close] may be called from any thread to abort a blocked read. */
internal interface HttpResponse : Closeable {
    val statusCode: Int

    /** The response body for 2xx responses, otherwise the error body (may be null). */
    val body: InputStream?
}

/** POSTs a request. Blocking; call off the main thread. */
internal fun interface HttpTransport {
    @Throws(IOException::class)
    fun post(request: HttpRequest): HttpResponse
}

internal class UrlConnectionTransport : HttpTransport {

    override fun post(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val bytes = request.body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return ConnectionResponse(connection, code, stream)
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    private class ConnectionResponse(
        private val connection: HttpURLConnection,
        override val statusCode: Int,
        override val body: InputStream?
    ) : HttpResponse {
        // Cancellation calls this from a different thread than the one blocked reading the body.
        // Android's okhttp-backed stream is not thread-safe, and closing it concurrently can throw
        // "IllegalStateException: Unbalanced enter/exit". Disconnecting first aborts the socket and
        // unblocks the reader. The body is then closed best-effort.
        override fun close() {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
            try {
                body?.close()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}
