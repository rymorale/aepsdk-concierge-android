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

import com.adobe.marketing.mobile.concierge.headless.ConciergeConfig
import com.adobe.marketing.mobile.concierge.headless.Consent
import com.adobe.marketing.mobile.concierge.headless.ConversationRequest
import com.adobe.marketing.mobile.concierge.headless.FeedbackRating
import com.adobe.marketing.mobile.concierge.headless.FeedbackRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class RequestBodiesTest {

    private val identityMap = mapOf("ECID" to listOf(mapOf("id" to "ecid-1", "primary" to true)))

    private fun event(body: String): JSONObject = JSONObject(body).getJSONArray("events").getJSONObject(0)

    @Test
    fun `endpoint encodes values and adds the region segment`() {
        val url = RequestBodies.endpoint(ConciergeConfig("edge.adobedc.net", "ds 1", "va7"), "s&1", "r1")

        assertEquals(
            "https://edge.adobedc.net/brand-concierge/va7/conversations?configId=ds+1&sessionId=s%261&requestId=r1",
            url
        )
        assertEquals(
            "https://h/brand-concierge/conversations?configId=d&sessionId=s&requestId=r",
            RequestBodies.endpoint(ConciergeConfig("h", "d"), "s", "r")
        )
    }

    @Test
    fun `conversation body carries consent, surfaces, message, auth and xdm`() {
        val request = ConversationRequest(
            sessionId = "s",
            surfaces = listOf("web://surface"),
            identityMap = identityMap,
            consent = Consent.IN,
            message = "hello",
            xdm = mapOf("web" to mapOf("webPageDetails" to mapOf("name" to "home")), "flag" to null),
            authToken = "t"
        )

        val event = event(RequestBodies.conversationBody(request))
        val conversation = event.getJSONObject("query").getJSONObject("conversation")
        val xdm = event.getJSONObject("xdm")

        assertEquals("in", event.getJSONObject("meta").getJSONObject("consent").getString("state"))
        assertEquals("web://surface", conversation.getJSONArray("surfaces").getString(0))
        assertEquals("hello", conversation.getString("message"))
        assertEquals("auth", conversation.getJSONObject("data").getString("type"))
        assertEquals("t", conversation.getJSONObject("data").getJSONObject("payload").getString("token"))
        assertEquals("ecid-1", xdm.getJSONObject("identityMap").getJSONArray("ECID").getJSONObject(0).getString("id"))
        assertEquals("home", xdm.getJSONObject("web").getJSONObject("webPageDetails").getString("name"))
        assertEquals(JSONObject.NULL, xdm.get("flag"))
    }

    @Test
    fun `conversation body omits auth data without a token`() {
        val request = ConversationRequest("s", listOf("web://x"), emptyMap(), Consent.PENDING, message = "hi")

        val event = event(RequestBodies.conversationBody(request))

        assertFalse(event.getJSONObject("query").getJSONObject("conversation").has("data"))
        assertEquals("pending", event.getJSONObject("meta").getJSONObject("consent").getString("state"))
    }

    @Test
    fun `conversation request rejects invalid input`() {
        fun request(message: String = "hi", xdm: Map<String, Any?> = emptyMap(), surfaces: List<String> = listOf("s")) =
            ConversationRequest("s", surfaces, emptyMap(), Consent.IN, message, xdm)

        assertThrows(IllegalArgumentException::class.java) { request(message = " ") }
        assertThrows(IllegalArgumentException::class.java) { request(xdm = mapOf("identityMap" to emptyMap<String, Any>())) }
        assertThrows(IllegalArgumentException::class.java) { request(surfaces = listOf(" ")) }
        request(message = "", xdm = mapOf("data" to 1))
    }

    @Test
    fun `feedback body matches the extension wire format`() {
        val request = FeedbackRequest(
            sessionId = "s",
            identityMap = identityMap,
            consent = Consent.OUT,
            interactionId = "turn-1",
            conversationId = "conv-1",
            rating = FeedbackRating.THUMBS_DOWN,
            reasons = listOf("Inaccurate"),
            notes = "wrong price"
        )
        val now = Date(1_767_225_600_000L) // 2026-01-01T00:00:00Z

        val event = event(RequestBodies.feedbackBody(request, now, TimeZone.getTimeZone("GMT-05:30")))
        val xdm = event.getJSONObject("xdm")
        val conversation = xdm.getJSONObject("conversation")
        val feedback = conversation.getJSONObject("feedback")
        val rating = feedback.getJSONObject("rating")

        assertEquals("out", event.getJSONObject("meta").getJSONObject("consent").getString("state"))
        assertEquals("conversation.feedback", xdm.getString("eventType"))
        assertEquals("2026-01-01T00:00:00.000Z", xdm.getString("timestamp"))
        assertEquals(-330, xdm.getJSONObject("placeContext").getInt("localTimezoneOffset"))
        assertEquals("2025-12-31T18:30:00.000-05:30", xdm.getJSONObject("placeContext").getString("localTime"))
        assertEquals("headless+1.0.0", xdm.getJSONObject("implementationDetails").getString("version"))
        assertEquals("conv-1", conversation.getString("conversationID"))
        assertEquals("turn-1", conversation.getString("turnID"))
        assertEquals("end-user", feedback.getString("source"))
        assertEquals("wrong price", feedback.getJSONArray("raw").getJSONObject(0).getString("text"))
        assertEquals(0, rating.getInt("score"))
        assertEquals("Thumbs Down", rating.getString("classification"))
        assertEquals("Inaccurate", rating.getJSONArray("reasons").getString(0))
    }

    @Test
    fun `feedback body omits optional fields`() {
        val request = FeedbackRequest("s", emptyMap(), Consent.IN, "turn-1", null, FeedbackRating.THUMBS_UP)

        val conversation = event(RequestBodies.feedbackBody(request)).getJSONObject("xdm").getJSONObject("conversation")

        assertFalse(conversation.has("conversationID"))
        assertFalse(conversation.has("data"))
        assertEquals(0, conversation.getJSONObject("feedback").getJSONArray("raw").length())
        assertEquals("Thumbs Up", conversation.getJSONObject("feedback").getJSONObject("rating").getString("classification"))
    }

    @Test
    fun `iso offset formats zero and signed offsets`() {
        assertEquals("Z", RequestBodies.isoOffset(0))
        assertEquals("+05:45", RequestBodies.isoOffset(345 * 60_000))
        assertEquals("-10:00", RequestBodies.isoOffset(-600 * 60_000))
    }
}
