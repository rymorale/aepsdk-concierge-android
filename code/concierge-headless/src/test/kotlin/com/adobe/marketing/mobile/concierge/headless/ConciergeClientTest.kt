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

import com.adobe.marketing.mobile.concierge.headless.internal.HttpRequest
import com.adobe.marketing.mobile.concierge.headless.internal.HttpResponse
import com.adobe.marketing.mobile.concierge.headless.internal.HttpTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConciergeClientTest {

    private val config = ConciergeConfig("edge.example.com", "ds-1")
    private val request = ConversationRequest("session-1", listOf("web://surface"), emptyMap(), Consent.IN, "hi")

    private class FakeResponse(override val statusCode: Int, override val body: InputStream?) : HttpResponse {
        var closed = false
        override fun close() {
            closed = true
            body?.close()
        }
    }

    private class FakeTransport(private val response: () -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun post(request: HttpRequest): HttpResponse = response().also { requests += request }
    }

    private fun sse(state: String, message: String, extra: String = ""): String =
        "data: {\"handle\":[{\"type\":\"brand-concierge:conversation\",\"payload\":[{\"state\":\"$state\"," +
            "\"interactionId\":\"i1\",\"response\":{\"message\":\"$message\"$extra}}]}]}\n\n"

    private fun stream(vararg events: String) = ByteArrayInputStream(events.joinToString("").toByteArray())

    private fun event(state: ConversationState, message: String, elements: List<MultimodalElement> = emptyList()) =
        ConversationEvent(state, null, null, ConversationResponse(message, multimodalElements = elements))

    @Test
    fun `streams events in order and stops after completed`() = runBlocking {
        val response = FakeResponse(
            200,
            stream(sse("in-progress", "Hel"), sse("in-progress", "lo"), sse("completed", "Hello"), sse("in-progress", "late"))
        )
        val transport = FakeTransport { response }

        val events = ConciergeClient(config, transport).converse(request).toList()

        assertEquals(listOf("Hel", "lo", "Hello"), events.map { it.response.message })
        assertEquals(ConversationState.COMPLETED, events.last().state)
        assertTrue(response.closed)
        val sent = transport.requests.single()
        assertTrue(sent.url.startsWith("https://edge.example.com/brand-concierge/conversations?configId=ds-1&sessionId=session-1"))
        assertEquals("text/event-stream", sent.headers["Accept"])
    }

    @Test
    fun `non-2xx responses throw with the status code and body`() = runBlocking {
        val response = FakeResponse(429, ByteArrayInputStream("slow down".toByteArray()))

        val error = assertConciergeException { ConciergeClient(config, FakeTransport { response }).converse(request).toList() }

        assertEquals(429, error.statusCode)
        assertEquals("The service returned HTTP 429: slow down", error.message)
        assertTrue(response.closed)
    }

    @Test
    fun `network failures are wrapped`() = runBlocking {
        val connectFailure = assertConciergeException {
            ConciergeClient(config, FakeTransport { throw IOException("offline") }).converse(request).toList()
        }
        assertEquals("offline", connectFailure.cause?.message)

        val failingBody = object : InputStream() {
            override fun read(): Int = throw IOException("reset")
        }
        val readFailure = assertConciergeException {
            ConciergeClient(config, FakeTransport { FakeResponse(200, failingBody) }).converse(request).toList()
        }
        assertEquals("reset", readFailure.cause?.message)
    }

    @Test
    fun `cancelling the collector closes a blocked response`() = runBlocking {
        val closed = CountDownLatch(1)
        val blockingBody = object : InputStream() {
            private val first = sse("in-progress", "partial").toByteArray().inputStream()
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val count = first.read(b, off, len)
                if (count != -1) return count
                closed.await()
                throw IOException("closed")
            }
            override fun close() = closed.countDown()
        }
        val received = CompletableDeferred<ConversationEvent>()

        val job = launch {
            ConciergeClient(config, FakeTransport { FakeResponse(200, blockingBody) })
                .converse(request)
                .collect { received.complete(it) }
        }
        withTimeout(5_000) { assertEquals("partial", received.await().response.message) }
        withTimeout(5_000) { job.cancelAndJoin() }

        assertTrue(closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `sendFeedback posts JSON and throws on failure`() = runBlocking {
        val transport = FakeTransport { FakeResponse(204, null) }
        val feedback = FeedbackRequest("s", emptyMap(), Consent.IN, "i1", null, FeedbackRating.THUMBS_UP)

        ConciergeClient(config, transport).sendFeedback(feedback)

        assertEquals(mapOf("Content-Type" to "application/json"), transport.requests.single().headers)
        val error = assertConciergeException {
            ConciergeClient(config, FakeTransport { FakeResponse(500, null) }).sendFeedback(feedback)
        }
        assertEquals(500, error.statusCode)
    }

    @Test
    fun `accumulate appends deltas and keeps streamed text when completed is empty`() = runBlocking {
        val snapshots = flowOf(
            event(ConversationState.IN_PROGRESS, " "),
            event(ConversationState.IN_PROGRESS, "Hel"),
            event(ConversationState.IN_PROGRESS, "lo "),
            event(ConversationState.COMPLETED, "")
        ).accumulate().toList()

        assertEquals(listOf(" Hel", " Hello ", " Hello "), snapshots.map { it.response.message })
        assertEquals(ConversationState.COMPLETED, snapshots.last().state)
    }

    @Test
    fun `accumulate lets a completed event with content replace the streamed text`() = runBlocking {
        val card = MultimodalElement.ProductCard(id = "p1")

        val withText = flowOf(event(ConversationState.IN_PROGRESS, "draft"), event(ConversationState.COMPLETED, "Final"))
            .accumulate().toList()
        val withCards = flowOf(event(ConversationState.IN_PROGRESS, "draft"), event(ConversationState.COMPLETED, "", listOf(card)))
            .accumulate().toList()

        assertEquals("Final", withText.last().response.message)
        assertEquals("", withCards.last().response.message)
        assertEquals(listOf(card), withCards.last().response.multimodalElements)
    }

    @Test
    fun `accumulate throws on an error event`() = runBlocking {
        val error = assertConciergeException {
            flowOf(event(ConversationState.IN_PROGRESS, "a"), event(ConversationState.ERROR, "")).accumulate().toList()
        }
        assertEquals("The service returned an error.", error.message)
    }

    private suspend fun assertConciergeException(block: suspend () -> Unit): ConciergeException {
        try {
            block()
        } catch (e: ConciergeException) {
            return e
        }
        fail("Expected ConciergeException")
        throw AssertionError()
    }
}
