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

/** The user's data collection consent, sent as `meta.consent.state`. */
enum class Consent(val value: String) {
    IN("in"),
    OUT("out"),
    PENDING("pending")
}

/**
 * One conversation turn.
 *
 * @property sessionId the conversation session. Reuse it for every turn of one conversation.
 * @property surfaces the surface URIs this conversation is configured for. At least one is required.
 * @property identityMap the XDM identity map, sent as `xdm.identityMap`. For example
 *   `mapOf("ECID" to listOf(mapOf("id" to ecid, "primary" to true)))`.
 * @property consent the user's consent state.
 * @property message the user's message. It may be empty only when [xdm] carries data (data handoff).
 * @property xdm additional XDM fields merged into the event's `xdm`. It must not contain `identityMap`.
 * @property authToken an optional token sent as `query.conversation.data` with type `auth`.
 */
data class ConversationRequest(
    val sessionId: String,
    val surfaces: List<String>,
    val identityMap: Map<String, Any?>,
    val consent: Consent,
    val message: String = "",
    val xdm: Map<String, Any?> = emptyMap(),
    val authToken: String? = null
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank." }
        require(surfaces.any { it.isNotBlank() }) { "At least one non-blank surface is required." }
        require(message.isNotBlank() || xdm.isNotEmpty()) { "Either message or xdm must be provided." }
        require(IDENTITY_MAP !in xdm) { "Pass identityMap through the identityMap property, not xdm." }
    }

    private companion object {
        const val IDENTITY_MAP = "identityMap"
    }
}

enum class FeedbackRating { THUMBS_UP, THUMBS_DOWN }

/**
 * Feedback for one response.
 *
 * @property interactionId the [ConversationEvent.interactionId] of the response being rated.
 * @property conversationId the [ConversationEvent.conversationId] of the response, if known.
 * @property reasons the reasons the user selected.
 * @property notes free-form text from the user.
 */
data class FeedbackRequest(
    val sessionId: String,
    val identityMap: Map<String, Any?>,
    val consent: Consent,
    val interactionId: String,
    val conversationId: String?,
    val rating: FeedbackRating,
    val reasons: List<String> = emptyList(),
    val notes: String = "",
    val authToken: String? = null
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank." }
        require(interactionId.isNotBlank()) { "interactionId must not be blank." }
    }
}
