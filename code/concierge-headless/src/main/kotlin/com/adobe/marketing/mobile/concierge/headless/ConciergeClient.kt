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

package com.adobe.marketing.mobile.concierge.headless

import com.adobe.marketing.mobile.concierge.headless.internal.EventParser
import com.adobe.marketing.mobile.concierge.headless.internal.HttpRequest
import com.adobe.marketing.mobile.concierge.headless.internal.HttpResponse
import com.adobe.marketing.mobile.concierge.headless.internal.HttpTransport
import com.adobe.marketing.mobile.concierge.headless.internal.RequestBodies
import com.adobe.marketing.mobile.concierge.headless.internal.SseParser
import com.adobe.marketing.mobile.concierge.headless.internal.UrlConnectionTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Where to send conversation requests.
 *
 * @property server the Edge Network host name, for example `edge.adobedc.net`.
 * @property datastreamId the datastream configured for Brand Concierge, sent as `configId`.
 * @property region an optional Edge Network region, for example `va7`.
 */
data class ConciergeConfig(
    val server: String,
    val datastreamId: String,
    val region: String? = null
) {
    init {
        require(server.isNotBlank() && '/' !in server) { "server must be a host name, for example edge.adobedc.net." }
        require(datastreamId.isNotBlank()) { "datastreamId must not be blank." }
        require(region == null || (region.isNotBlank() && '/' !in region)) { "region must be a single path segment." }
    }
}

/**
 * A request failed. [statusCode] is set when the service answered with a non-2xx status.
 */
class ConciergeException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null
) : Exception(message, cause)

/**
 * A client for the Brand Concierge conversation API.
 *
 * The client is stateless and safe to share. The app owns the session ID, identity map, consent,
 * and any auth token, and passes them with each request.
 */
class ConciergeClient internal constructor(
    private val config: ConciergeConfig,
    private val transport: HttpTransport
) {
    constructor(config: ConciergeConfig) : this(config, UrlConnectionTransport())

    /**
     * Sends one turn and streams its events in the order received.
     *
     * The returned flow is cold: each collection sends a new request. It completes after the first
     * [ConversationState.COMPLETED] or [ConversationState.ERROR] event, or when the stream ends.
     * Cancelling the collector aborts the request. Failures are thrown as [ConciergeException].
     */
    fun converse(request: ConversationRequest): Flow<ConversationEvent> = flow {
        val response = post(RequestBodies.conversation(config, request))
        coroutineScope {
            // Closing the response is the only way to unblock a read that is waiting on the network.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    response.close()
                }
            }
            try {
                checkStatus(response)
                emitEvents(response)
            } finally {
                closer.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Sends feedback for one response.
     *
     * @throws ConciergeException if the request fails.
     */
    suspend fun sendFeedback(request: FeedbackRequest) {
        withContext(Dispatchers.IO) {
            post(RequestBodies.feedback(config, request)).use { checkStatus(it) }
        }
    }

    private suspend fun post(request: HttpRequest): HttpResponse = try {
        transport.post(request)
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw ConciergeException("Could not reach the Brand Concierge service.", e)
    }

    private fun checkStatus(response: HttpResponse) {
        val code = response.statusCode
        if (code in 200..299) return
        val body = try {
            response.body?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.take(MAX_ERROR_BODY)
        } catch (e: IOException) {
            null
        }
        val detail = if (body.isNullOrBlank()) "" else ": $body"
        throw ConciergeException("The service returned HTTP $code$detail", statusCode = code)
    }

    private suspend fun FlowCollector<ConversationEvent>.emitEvents(response: HttpResponse) {
        val reader = response.body?.bufferedReader(Charsets.UTF_8) ?: return
        val sse = SseParser()
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw ConciergeException("The response stream failed.", e)
            }
            val data = if (line == null) sse.finish() else sse.feed(line)
            for (event in data?.let(EventParser::parse).orEmpty()) {
                emit(event)
                if (event.state == ConversationState.COMPLETED || event.state == ConversationState.ERROR) return
            }
            if (line == null) return
        }
    }

    companion object {
        /** The version of this library. */
        const val VERSION = "1.0.0"

        private const val MAX_ERROR_BODY = 1_000
    }
}
