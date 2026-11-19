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

import com.adobe.marketing.mobile.concierge.headless.ConversationState
import com.adobe.marketing.mobile.concierge.headless.ElementAction
import com.adobe.marketing.mobile.concierge.headless.LinkHint
import com.adobe.marketing.mobile.concierge.headless.MultimodalElement
import com.adobe.marketing.mobile.concierge.headless.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventParserTest {

    private fun handle(vararg payloads: String, type: String = "brand-concierge:conversation") =
        """{"type":"$type","payload":[${payloads.joinToString(",")}]}"""

    private fun data(vararg handles: String) = """{"handle":[${handles.joinToString(",")}]}"""

    private fun payload(state: String, response: String? = """{"message":"hi"}""") =
        """{"state":"$state","conversationId":"c1","interactionId":"i1"""" +
            (response?.let { ""","response":$it""" } ?: "") + "}"

    private fun response(fields: String) = EventParser.parse(data(handle(payload("completed", fields)))).single().response

    @Test
    fun `reads payloads from conversation handles only`() {
        val events = EventParser.parse(
            data(
                handle(payload("in-progress"), type = "state:store"),
                handle(payload("in-progress"), payload("completed"))
            )
        )

        assertEquals(listOf(ConversationState.IN_PROGRESS, ConversationState.COMPLETED), events.map { it.state })
        assertEquals("c1", events[0].conversationId)
        assertEquals("i1", events[0].interactionId)
        assertEquals("hi", events[0].response.message)
    }

    @Test
    fun `drops empty in-progress payloads and keeps terminal payloads without a response`() {
        val events = EventParser.parse(
            data(
                handle(
                    payload("in-progress", """{"message":""}"""),
                    payload("completed", null),
                    payload("error", null),
                    payload("surprise")
                )
            )
        )

        assertEquals(
            listOf(ConversationState.COMPLETED, ConversationState.ERROR, ConversationState.UNKNOWN),
            events.map { it.state }
        )
        assertEquals("", events[0].response.message)
    }

    @Test
    fun `returns nothing for data that is not a JSON object`() {
        assertTrue(EventParser.parse("[DONE]").isEmpty())
        assertTrue(EventParser.parse("""{"handle":"nope"}""").isEmpty())
    }

    @Test
    fun `reads suggestions, sources, link hints and feedback eligibility`() {
        val response = response(
            """{
                "message": "m",
                "promptSuggestions": ["a", "", 3, "b"],
                "sources": [
                    {"title": "T", "url": "https://u", "citation_number": 2, "start_index": 0, "end_index": 4},
                    {"title": "No index", "url": "https://v", "citation_number": 0, "start_index": -1},
                    {"title": "No url"}
                ],
                "linkHints": [{"kind": "product", "href": "https://p"}, {"kind": "missing-href"}],
                "feedback": {"eligible": true}
            }"""
        )

        assertEquals(listOf("a", "b"), response.promptSuggestions)
        assertEquals(
            listOf(Source("T", "https://u", 2, 0, 4), Source("No index", "https://v")),
            response.sources
        )
        assertEquals(listOf(LinkHint("product", "https://p")), response.linkHints)
        assertTrue(response.feedbackEligible)
        assertFalse(response("""{"message":"m"}""").feedbackEligible)
    }

    @Test
    fun `classifies multimodal elements in order`() {
        val elements = response(
            """{"multimodalElements": {"elements": [
                {"id": "p1", "width": 300, "height": 0, "productName": "Root name", "productPrice": "$5",
                 "entity_info": {"productName": "Info name", "productImageURL": "https://img",
                                 "primary": {"text": "Buy", "url": "https://buy"}}},
                {"type": "ctaButton", "entity_info": {"primary": {"text": "Go", "url": "https://go"}}},
                {"type": "custom", "rendererId": "rating"},
                {"type": "custom"},
                {"type": "ctaButton", "entity_info": {"primary": {"text": "No url"}}},
                {"productName": "No id"}
            ]}}"""
        ).multimodalElements

        val card = elements[0] as MultimodalElement.ProductCard
        assertEquals("p1", card.id)
        assertEquals("Info name", card.productName)
        assertEquals("$5", card.productPrice)
        assertEquals("https://img", card.productImageUrl)
        assertEquals(ElementAction("Buy", "https://buy"), card.primary)
        assertEquals(null, card.secondary)
        assertEquals(300, card.width)
        assertEquals(null, card.height)
        assertEquals("Root name", card.raw["productName"])

        assertEquals("Go", (elements[1] as MultimodalElement.CtaButton).text)
        assertEquals("rating", (elements[2] as MultimodalElement.Custom).rendererId)
        assertEquals(
            listOf("custom", "ctaButton", null),
            elements.drop(3).map { (it as MultimodalElement.Unknown).type }
        )
    }

    @Test
    fun `ignores the list form of multimodal elements`() {
        assertTrue(response("""{"multimodalElements": [{"id": "p1"}]}""").multimodalElements.isEmpty())
    }
}
