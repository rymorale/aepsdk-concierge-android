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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The `state` of a conversation payload. */
enum class ConversationState {
    IN_PROGRESS,
    COMPLETED,
    ERROR,
    UNKNOWN;

    internal companion object {
        fun from(value: String?): ConversationState = when (value?.lowercase()) {
            "in-progress" -> IN_PROGRESS
            "completed" -> COMPLETED
            "error" -> ERROR
            else -> UNKNOWN
        }
    }
}

/**
 * One `brand-concierge:conversation` payload from the response stream.
 *
 * While the state is [ConversationState.IN_PROGRESS], [ConversationResponse.message] holds only
 * the newly streamed text. Use [accumulate] to get the full text so far instead.
 */
data class ConversationEvent(
    val state: ConversationState,
    val conversationId: String?,
    val interactionId: String?,
    val response: ConversationResponse
)

/** The `response` object of a conversation payload. */
data class ConversationResponse(
    val message: String = "",
    val promptSuggestions: List<String> = emptyList(),
    val multimodalElements: List<MultimodalElement> = emptyList(),
    val sources: List<Source> = emptyList(),
    val linkHints: List<LinkHint> = emptyList(),
    /** True when the response can receive feedback through [ConciergeClient.sendFeedback]. */
    val feedbackEligible: Boolean = false
)

/**
 * An entry of `response.multimodalElements.elements`, in response order.
 *
 * [raw] is the element exactly as received, for fields this type does not model.
 */
sealed class MultimodalElement {
    abstract val raw: Map<String, Any?>

    /**
     * A product or content card. Any element that is not a `ctaButton` or `custom` element and
     * has an `id` is a card. Fields are read from `entity_info` first, then from the element.
     */
    data class ProductCard(
        val id: String,
        val productName: String? = null,
        val description: String? = null,
        val productDescription: String? = null,
        val productPageUrl: String? = null,
        val productImageUrl: String? = null,
        val productPrice: String? = null,
        val productWasPrice: String? = null,
        val productBadge: String? = null,
        val backgroundColor: String? = null,
        val learningResource: String? = null,
        val logo: String? = null,
        val details: String? = null,
        val primary: ElementAction? = null,
        val secondary: ElementAction? = null,
        val width: Int? = null,
        val height: Int? = null,
        val thumbnailWidth: Int? = null,
        val thumbnailHeight: Int? = null,
        override val raw: Map<String, Any?> = emptyMap()
    ) : MultimodalElement()

    /** A `ctaButton` element, read from `entity_info.primary`. */
    data class CtaButton(
        val text: String,
        val url: String,
        override val raw: Map<String, Any?> = emptyMap()
    ) : MultimodalElement()

    /** A `custom` element for an app-defined renderer, matched by [rendererId]. */
    data class Custom(
        val rendererId: String,
        override val raw: Map<String, Any?> = emptyMap()
    ) : MultimodalElement()

    /** An element that matches none of the other types or is missing required fields. */
    data class Unknown(
        val type: String?,
        override val raw: Map<String, Any?> = emptyMap()
    ) : MultimodalElement()
}

data class ElementAction(val text: String?, val url: String?)

/** A citation. [startIndex] and [endIndex] locate the cited range in the message. */
data class Source(
    val title: String,
    val url: String,
    val citationNumber: Int? = null,
    val startIndex: Int? = null,
    val endIndex: Int? = null
)

data class LinkHint(val kind: String, val href: String)

/**
 * Turns the raw events of one turn into snapshots that each carry the full message so far.
 *
 * - In-progress text is appended, and a snapshot is emitted once there is visible text.
 * - A completed event that has text or elements replaces the streamed text. Otherwise it is
 *   emitted with the streamed text.
 * - An error event throws [ConciergeException].
 */
fun Flow<ConversationEvent>.accumulate(): Flow<ConversationEvent> = flow {
    val text = StringBuilder()
    collect { event ->
        val response = event.response
        when (event.state) {
            ConversationState.ERROR ->
                throw ConciergeException(response.message.ifBlank { "The service returned an error." })
            ConversationState.COMPLETED ->
                if (response.message.isNotBlank() || response.multimodalElements.isNotEmpty()) {
                    emit(event)
                } else {
                    emit(event.copy(response = response.copy(message = text.toString())))
                }
            else -> {
                text.append(response.message)
                if (text.isNotBlank()) {
                    emit(event.copy(response = response.copy(message = text.toString())))
                }
            }
        }
    }
}
