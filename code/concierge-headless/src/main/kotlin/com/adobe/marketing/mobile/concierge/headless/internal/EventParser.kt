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

import com.adobe.marketing.mobile.concierge.headless.ConversationEvent
import com.adobe.marketing.mobile.concierge.headless.ConversationResponse
import com.adobe.marketing.mobile.concierge.headless.ConversationState
import com.adobe.marketing.mobile.concierge.headless.ElementAction
import com.adobe.marketing.mobile.concierge.headless.LinkHint
import com.adobe.marketing.mobile.concierge.headless.MultimodalElement
import com.adobe.marketing.mobile.concierge.headless.Source
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Server-Sent Events framing. Feed one line at a time without its line terminator. Only `data`
 * fields are kept, because the conversation stream carries everything in them.
 */
internal class SseParser {
    private val data = StringBuilder()
    private var hasData = false
    private var firstLine = true

    /** Returns the data of the event that this line completes, if any. */
    fun feed(rawLine: String): String? {
        val line = if (firstLine && rawLine.startsWith('\uFEFF')) rawLine.substring(1) else rawLine
        firstLine = false
        if (line.isEmpty()) return flush()
        if (line.startsWith(':')) return null
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        if (field != "data") return null
        val value = if (colon == -1) "" else line.substring(colon + 1).removePrefix(" ")
        if (hasData) data.append('\n')
        data.append(value)
        hasData = true
        return null
    }

    /** Returns the data of an event left unterminated at the end of the stream, if any. */
    fun finish(): String? = flush()

    private fun flush(): String? {
        if (!hasData) return null
        return data.toString().also {
            data.setLength(0)
            hasData = false
        }
    }
}

/** Reads conversation events from the JSON data of one SSE event. */
internal object EventParser {

    private const val CONVERSATION_HANDLE = "brand-concierge:conversation"

    fun parse(data: String): List<ConversationEvent> {
        val root = try {
            toMap(JSONObject(data))
        } catch (e: JSONException) {
            return emptyList()
        }
        return root.maps("handle")
            .filter { it["type"] == CONVERSATION_HANDLE }
            .flatMap { it.maps("payload") }
            .mapNotNull(::event)
    }

    private fun event(payload: Map<String, Any?>): ConversationEvent? {
        val state = ConversationState.from(payload["state"] as? String)
        val response = payload.map("response")
        val message = response?.string("message").orEmpty()
        val terminal = state == ConversationState.COMPLETED || state == ConversationState.ERROR
        if (message.isEmpty() && !terminal) return null
        return ConversationEvent(
            state = state,
            conversationId = payload.string("conversationId"),
            interactionId = payload.string("interactionId"),
            response = response?.let { response(it, message) } ?: ConversationResponse()
        )
    }

    private fun response(response: Map<String, Any?>, message: String) = ConversationResponse(
        message = message,
        promptSuggestions = (response["promptSuggestions"] as? List<*>).orEmpty()
            .filterIsInstance<String>()
            .filter { it.isNotEmpty() },
        multimodalElements = response.map("multimodalElements")?.maps("elements").orEmpty().map(::element),
        sources = response.maps("sources").mapNotNull { source ->
            Source(
                title = source.string("title") ?: return@mapNotNull null,
                url = source.string("url") ?: return@mapNotNull null,
                citationNumber = source.int("citation_number")?.takeIf { it > 0 },
                startIndex = source.int("start_index")?.takeIf { it >= 0 },
                endIndex = source.int("end_index")?.takeIf { it >= 0 }
            )
        },
        linkHints = response.maps("linkHints").mapNotNull { hint ->
            LinkHint(
                kind = hint.string("kind") ?: return@mapNotNull null,
                href = hint.string("href") ?: return@mapNotNull null
            )
        },
        feedbackEligible = response.map("feedback")?.get("eligible") == true
    )

    private fun element(raw: Map<String, Any?>): MultimodalElement {
        val type = raw["type"] as? String
        val info = raw.map("entity_info")
        return when (type) {
            "ctaButton" -> {
                val primary = info?.map("primary")
                val text = primary?.string("text")
                val url = primary?.string("url")
                if (text != null && url != null) MultimodalElement.CtaButton(text, url, raw) else null
            }
            "custom" -> (raw["rendererId"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { MultimodalElement.Custom(it, raw) }
            else -> raw.string("id")?.let { id -> productCard(id, info, raw) }
        } ?: MultimodalElement.Unknown(type, raw)
    }

    private fun productCard(id: String, info: Map<String, Any?>?, raw: Map<String, Any?>): MultimodalElement {
        fun field(name: String) = info?.string(name) ?: raw.string(name)
        fun action(name: String) = (info?.map(name) ?: raw.map(name))
            ?.let { ElementAction(it.string("text"), it.string("url")) }
            ?.takeIf { it.text != null || it.url != null }
        fun dimension(name: String) = raw.int(name)?.takeIf { it > 0 }
        return MultimodalElement.ProductCard(
            id = id,
            productName = field("productName"),
            description = field("description"),
            productDescription = field("productDescription"),
            productPageUrl = field("productPageURL"),
            productImageUrl = field("productImageURL"),
            productPrice = field("productPrice"),
            productWasPrice = field("productWasPrice"),
            productBadge = field("productBadge"),
            backgroundColor = field("backgroundColor"),
            learningResource = field("learningResource"),
            logo = field("logo"),
            details = field("details"),
            primary = action("primary"),
            secondary = action("secondary"),
            width = dimension("width"),
            height = dimension("height"),
            thumbnailWidth = dimension("thumbnail_width"),
            thumbnailHeight = dimension("thumbnail_height"),
            raw = raw
        )
    }

    private fun Map<String, Any?>.string(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.maps(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>() as List<Map<String, Any?>>

    private fun toMap(json: JSONObject): Map<String, Any?> =
        json.keys().asSequence().associateWith { toValue(json.opt(it)) }

    private fun toValue(value: Any?): Any? = when (value) {
        is JSONObject -> toMap(value)
        is JSONArray -> (0 until value.length()).map { toValue(value.opt(it)) }
        JSONObject.NULL -> null
        else -> value
    }
}
