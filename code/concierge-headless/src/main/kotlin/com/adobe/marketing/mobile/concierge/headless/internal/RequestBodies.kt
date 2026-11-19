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

import com.adobe.marketing.mobile.concierge.headless.ConciergeClient
import com.adobe.marketing.mobile.concierge.headless.ConciergeConfig
import com.adobe.marketing.mobile.concierge.headless.Consent
import com.adobe.marketing.mobile.concierge.headless.ConversationRequest
import com.adobe.marketing.mobile.concierge.headless.FeedbackRating
import com.adobe.marketing.mobile.concierge.headless.FeedbackRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Builds conversation API requests. */
internal object RequestBodies {

    private const val IMPLEMENTATION_NAME = "https://ns.adobe.com/experience/mobilesdk/android"

    private val STREAM_HEADERS = mapOf(
        "Accept" to "text/event-stream",
        "Cache-Control" to "no-cache",
        "Content-Type" to "application/json"
    )
    private val JSON_HEADERS = mapOf("Content-Type" to "application/json")

    fun conversation(config: ConciergeConfig, request: ConversationRequest): HttpRequest =
        HttpRequest(endpoint(config, request.sessionId), conversationBody(request), STREAM_HEADERS)

    fun feedback(config: ConciergeConfig, request: FeedbackRequest): HttpRequest =
        HttpRequest(endpoint(config, request.sessionId), feedbackBody(request), JSON_HEADERS)

    fun endpoint(
        config: ConciergeConfig,
        sessionId: String,
        requestId: String = UUID.randomUUID().toString()
    ): String {
        val region = config.region?.let { "/${encode(it)}" }.orEmpty()
        return "https://${config.server}/brand-concierge$region/conversations" +
            "?configId=${encode(config.datastreamId)}" +
            "&sessionId=${encode(sessionId)}" +
            "&requestId=${encode(requestId)}"
    }

    fun conversationBody(request: ConversationRequest): String {
        val conversation = JSONObject()
            .put("surfaces", JSONArray(request.surfaces))
            .put("message", request.message)
            .putOpt("data", authData(request.authToken))
        val xdm = JSONObject().put("identityMap", json(request.identityMap))
        request.xdm.forEach { (key, value) -> xdm.put(key, json(value)) }
        val event = JSONObject()
            .put("meta", consentMeta(request.consent))
            .put("query", JSONObject().put("conversation", conversation))
            .put("xdm", xdm)
        return JSONObject().put("events", JSONArray().put(event)).toString()
    }

    fun feedbackBody(
        request: FeedbackRequest,
        now: Date = Date(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val positive = request.rating == FeedbackRating.THUMBS_UP
        val raw = JSONArray()
        if (request.notes.isNotBlank()) {
            raw.put(JSONObject().put("text", request.notes).put("purpose", "user input"))
        }
        val rating = JSONObject()
            .put("score", if (positive) 1 else 0)
            .put("classification", if (positive) "Thumbs Up" else "Thumbs Down")
            .put("reasons", JSONArray(request.reasons))
        val conversation = JSONObject()
            .put("feedback", JSONObject().put("source", "end-user").put("raw", raw).put("rating", rating))
            .putOpt("conversationID", request.conversationId)
            .put("turnID", request.interactionId)
            .putOpt("data", authData(request.authToken))

        val offsetMillis = timeZone.getOffset(now.time)
        val xdm = JSONObject()
            .put("identityMap", json(request.identityMap))
            .put("conversation", conversation)
            .put("eventType", "conversation.feedback")
            .put("timestamp", format(now, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC")))
            .put(
                "placeContext",
                JSONObject()
                    .put("localTimezoneOffset", offsetMillis / 60_000)
                    .put("localTime", format(now, "yyyy-MM-dd'T'HH:mm:ss.SSS", timeZone) + isoOffset(offsetMillis))
            )
            .put(
                "implementationDetails",
                JSONObject()
                    .put("environment", "app")
                    .put("name", IMPLEMENTATION_NAME)
                    .put("version", "headless+${ConciergeClient.VERSION}")
            )
        val event = JSONObject()
            .put("meta", consentMeta(request.consent))
            .put("xdm", xdm)
        return JSONObject().put("events", JSONArray().put(event)).toString()
    }

    // The "XXX" date pattern needs API 24, so the ISO 8601 offset is formatted by hand.
    fun isoOffset(offsetMillis: Int): String {
        if (offsetMillis == 0) return "Z"
        val minutes = Math.abs(offsetMillis) / 60_000
        val sign = if (offsetMillis < 0) '-' else '+'
        return String.format(Locale.US, "%c%02d:%02d", sign, minutes / 60, minutes % 60)
    }

    private fun format(date: Date, pattern: String, timeZone: TimeZone): String =
        SimpleDateFormat(pattern, Locale.US).apply { this.timeZone = timeZone }.format(date)

    private fun consentMeta(consent: Consent): JSONObject =
        JSONObject().put("consent", JSONObject().put("state", consent.value))

    private fun authData(token: String?): JSONObject? = token?.let {
        JSONObject().put("type", "auth").put("payload", JSONObject().put("token", it))
    }

    private fun json(value: Any?): Any = JSONObject.wrap(value) ?: JSONObject.NULL

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
