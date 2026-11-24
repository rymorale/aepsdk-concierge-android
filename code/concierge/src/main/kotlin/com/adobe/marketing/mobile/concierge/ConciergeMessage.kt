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

package com.adobe.marketing.mobile.concierge

/**
 * A message in a [ConciergeConversation].
 *
 * @property id identifies the message in its conversation. Pass it to [ConciergeConversation.sendFeedback].
 * @property role who wrote the message.
 * @property text the message text, which can contain Markdown. While [isStreaming] is true, it
 * holds the text received so far.
 * @property isStreaming true while the agent is still writing this message.
 * @property elements product cards, CTA buttons and other elements, in response order. They are
 * set when the response completes.
 * @property sources the citations for [text].
 * @property linkHints links in [text] that the agent tagged with a kind, such as a phone or store
 * link, so the app can mark them, for example with an icon.
 * @property promptSuggestions follow-up prompts the user can send.
 * @property feedbackEnabled true when the message can receive feedback.
 * @property feedback the rating sent for this message, or null if none was sent.
 */
data class ConciergeMessage(
    val id: String,
    val role: Role,
    val text: String,
    val isStreaming: Boolean = false,
    val elements: List<ConciergeElement> = emptyList(),
    val sources: List<ConciergeSource> = emptyList(),
    val linkHints: List<ConciergeLinkHint> = emptyList(),
    val promptSuggestions: List<String> = emptyList(),
    val feedbackEnabled: Boolean = false,
    val feedback: ConciergeFeedback.Rating? = null
) {
    /** Who wrote a [ConciergeMessage]. */
    enum class Role {
        USER,
        AGENT,

        /** Text the app added to the conversation, such as a data handoff's `localMessage`. */
        APP
    }
}

/**
 * An element of an agent response, such as a product card or a CTA button.
 */
sealed class ConciergeElement {
    /** The element exactly as received, for fields this type does not model. */
    abstract val raw: Map<String, Any?>

    /** A product or content card. Fields are read from `entity_info` first, then from the element. */
    data class ProductCard(
        val id: String,
        val productName: String? = null,
        val productDescription: String? = null,
        val description: String? = null,
        val productPageUrl: String? = null,
        val productImageUrl: String? = null,
        val productPrice: String? = null,
        val productWasPrice: String? = null,
        val productBadge: String? = null,
        val primary: ConciergeAction? = null,
        val secondary: ConciergeAction? = null,
        override val raw: Map<String, Any?> = emptyMap()
    ) : ConciergeElement()

    /** A standalone call-to-action button. */
    data class CtaButton(
        val label: String,
        val url: String,
        override val raw: Map<String, Any?> = emptyMap()
    ) : ConciergeElement()

    /** A `custom` element for an app-defined renderer, matched by [rendererId]. */
    data class Custom(
        val rendererId: String,
        override val raw: Map<String, Any?> = emptyMap()
    ) : ConciergeElement()

    /** An element of an unsupported type, or one that is missing required fields. */
    data class Unknown(
        val type: String?,
        override val raw: Map<String, Any?> = emptyMap()
    ) : ConciergeElement()
}

/** A button on a [ConciergeElement.ProductCard]. */
data class ConciergeAction(val text: String?, val url: String?)

/** A citation. [startIndex] and [endIndex] locate the cited range in the message text. */
data class ConciergeSource(
    val title: String,
    val url: String,
    val citationNumber: Int? = null,
    val startIndex: Int? = null,
    val endIndex: Int? = null
)

/**
 * A link in a message's text that the agent tagged with a [kind], such as `phone` or `store`.
 * [href] matches the link's URL in the text.
 */
data class ConciergeLinkHint(val href: String, val kind: String)

/**
 * Feedback on an agent message.
 *
 * @property rating whether the response was helpful.
 * @property categories the reasons the user selected, if any.
 * @property notes free-form comments from the user.
 */
data class ConciergeFeedback @JvmOverloads constructor(
    val rating: Rating,
    val categories: List<String> = emptyList(),
    val notes: String = ""
) {
    /** Whether a response was helpful. */
    enum class Rating { POSITIVE, NEGATIVE }
}

/**
 * A user interaction to report with [ConciergeConversation.track]. Each one sends the same
 * analytics events as the matching interaction in the built-in chat UI.
 */
sealed class ConciergeInteraction {
    /** The conversation was shown. */
    object Opened : ConciergeInteraction()

    /** The conversation was hidden. Reports the time since the last [Opened]. */
    object Closed : ConciergeInteraction()

    /** A prompt suggestion was tapped. This doesn't send it; call [ConciergeConversation.send] as well. */
    data class PromptSuggestionClicked(val suggestion: String) : ConciergeInteraction()

    /** A [ConciergeElement.CtaButton] was tapped. */
    data class CtaClicked(val label: String, val url: String) : ConciergeInteraction()

    /** A product card was tapped, outside its buttons. */
    data class ProductCardClicked(val card: ConciergeElement.ProductCard) : ConciergeInteraction()

    /** A product card button, such as Buy Now, was tapped. */
    data class ProductActionClicked(
        val card: ConciergeElement.ProductCard,
        val action: ConciergeAction
    ) : ConciergeInteraction()

    /** A citation was tapped. */
    data class SourceClicked(val source: ConciergeSource) : ConciergeInteraction()

    /** A link in the message text was tapped. */
    data class LinkClicked(val url: String) : ConciergeInteraction()

    /** A link with a [ConciergeLinkHint], or the app's marker for it, was tapped. */
    data class LinkHintClicked(val hint: ConciergeLinkHint) : ConciergeInteraction()
}
