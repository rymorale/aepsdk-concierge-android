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

import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.SharedStateResult
import com.adobe.marketing.mobile.SharedStateStatus
import com.adobe.marketing.mobile.concierge.network.ConciergeHttpStatusException
import com.adobe.marketing.mobile.concierge.network.ConversationService
import com.adobe.marketing.mobile.concierge.network.ConversationState
import com.adobe.marketing.mobile.concierge.network.CtaButton
import com.adobe.marketing.mobile.concierge.network.LinkHint
import com.adobe.marketing.mobile.concierge.network.MultimodalElement
import com.adobe.marketing.mobile.concierge.network.ParsedConversationMessage
import com.adobe.marketing.mobile.concierge.network.ParsedMultimodalItem
import com.adobe.marketing.mobile.concierge.network.RawMultimodalElement
import com.adobe.marketing.mobile.concierge.ui.state.Feedback
import com.adobe.marketing.mobile.concierge.ui.state.FeedbackType
import com.adobe.marketing.mobile.services.ServiceProvider
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ConciergeConversationTest {

    private val keys = ConciergeConstants.TrackingEvent.EventData.Key
    private val names = ConciergeConstants.TrackingEvent.Name
    private val dispatched = mutableListOf<Event>()
    private val service = FakeService()
    private lateinit var sessionManager: ConciergeSessionManager
    private lateinit var repository: ConciergeStateRepository
    private val conversations = mutableListOf<ConciergeConversation>()
    private val handoffResults = mutableListOf<DataHandoffDeliveryResult>()

    @Before
    fun setUp() {
        mockkStatic(ServiceProvider::class)
        every { ServiceProvider.getInstance() } returns mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        every { sessionManager.getSessionId() } returns "session-1"
        every { sessionManager.currentSessionIdOrNull() } returns "session-1"
        repository = ConciergeStateRepository(
            sessionManager = sessionManager,
            resetWarningScope = CoroutineScope(StandardTestDispatcher())
        )
    }

    @After
    fun tearDown() {
        conversations.forEach { it.close() }
        unmockkStatic(ServiceProvider::class)
    }

    @Test
    fun `send streams the response and maps every element type`() = runTest {
        val responses = Channel<ParsedConversationMessage>(Channel.UNLIMITED)
        service.responses = responses.receiveAsFlow()
        val conversation = conversation()

        conversation.send("  running shoes  ")
        assertEquals(ConciergeConversationState.Responding, conversation.state.value)
        assertEquals("running shoes", conversation.messages.value.single().text)

        responses.send(inProgress("Here are"))
        val streaming = conversation.messages.value.last()
        assertEquals(ConciergeMessage.Role.AGENT, streaming.role)
        assertEquals("Here are", streaming.text)
        assertTrue(streaming.isStreaming)

        responses.send(
            completed(
                "Here are some shoes.",
                listOf(productElement(), ctaElement(), customElement(), unknownElement())
            )
        )
        responses.close()

        val reply = conversation.messages.value.last()
        assertEquals(ConciergeConversationState.Idle, conversation.state.value)
        assertEquals("Here are some shoes.", reply.text)
        assertFalse(reply.isStreaming)
        assertEquals(listOf("More like this"), reply.promptSuggestions)
        assertEquals(listOf(ConciergeSource("Guide", "https://example.com/guide", 1)), reply.sources)

        val card = reply.elements[0] as ConciergeElement.ProductCard
        assertEquals("Runner", card.productName)
        assertEquals("https://example.com/runner.jpg", card.productImageUrl)
        assertEquals("https://example.com/runner", card.productPageUrl)
        assertEquals(ConciergeAction("Buy", "https://example.com/buy"), card.primary)
        assertNull(card.secondary)
        assertEquals(ConciergeElement.CtaButton("Shop all", "https://example.com/all", mapOf("type" to "cta")), reply.elements[1])
        assertEquals("store-map", (reply.elements[2] as ConciergeElement.Custom).rendererId)
        assertEquals("video", (reply.elements[3] as ConciergeElement.Unknown).type)

        assertEquals(
            listOf(names.SESSION_INITIALIZED, names.QUERY_SUBMITTED, names.RESPONSE_STARTED, names.RESPONSE_COMPLETED),
            dispatched.map { it.name }
        )
        assertEquals("running shoes", dispatched[1].eventData[keys.QUERY])
        assertEquals("conv-1", dispatched[3].eventData[keys.CONVERSATION_ID])
        assertEquals("interaction-1", dispatched[3].eventData[keys.INTERACTION_ID])
    }

    @Test
    fun `an error response removes the agent message and reports the error`() = runTest {
        service.responses = flowOf(inProgress("Partial"), message("Server unavailable", ConversationState.ERROR))
        val conversation = conversation()

        conversation.send("hello")

        assertEquals(ConciergeConversationState.Failed(ConciergeError.InvalidResponseData), conversation.state.value)
        assertEquals(listOf(ConciergeMessage.Role.USER), conversation.messages.value.map { it.role })
        val error = dispatched.single { it.name == names.ERROR_OCCURRED }
        assertEquals("Conversation error: Server unavailable", error.eventData[keys.ERROR_MESSAGE])
    }

    @Test
    fun `a response with no content fails`() = runTest {
        service.responses = emptyFlow()
        val conversation = conversation()

        conversation.send("hello")

        assertEquals(ConciergeConversationState.Failed(ConciergeError.InvalidResponseData), conversation.state.value)
        assertEquals(1, conversation.messages.value.size)
    }

    @Test
    fun `cancel keeps partial text and a new message can be sent`() = runTest {
        val responses = Channel<ParsedConversationMessage>(Channel.UNLIMITED)
        service.responses = responses.receiveAsFlow()
        val conversation = conversation()
        conversation.send("hello")
        responses.send(inProgress("Partial"))

        conversation.send("ignored while responding")
        assertEquals(2, conversation.messages.value.size)

        conversation.cancel()

        assertEquals(ConciergeConversationState.Idle, conversation.state.value)
        val partial = conversation.messages.value.last()
        assertEquals("Partial", partial.text)
        assertFalse(partial.isStreaming)

        service.responses = flowOf(completed("Done"))
        conversation.send("again")
        assertEquals("Done", conversation.messages.value.last().text)
    }

    @Test
    fun `sendFeedback rates the message and sends it to the service`() = runTest {
        service.responses = flowOf(completed("Answer", feedbackEligible = true))
        val conversation = conversation()
        conversation.send("hello")
        val reply = conversation.messages.value.last()
        assertTrue(reply.feedbackEnabled)

        conversation.sendFeedback(reply.id, ConciergeFeedback(ConciergeFeedback.Rating.POSITIVE, listOf("Helpful"), "Great"))

        assertEquals(ConciergeFeedback.Rating.POSITIVE, conversation.messages.value.last().feedback)
        assertEquals(
            Feedback("interaction-1", FeedbackType.POSITIVE, listOf("Helpful"), "Great", "conv-1"),
            service.feedback.single()
        )
        val event = dispatched.single { it.name == names.FEEDBACK_SUBMITTED }
        assertEquals(ConciergeConstants.ChatInteraction.POSITIVE, event.eventData[keys.FEEDBACK_TYPE])
    }

    @Test
    fun `feedback is disabled when the response has CTA buttons`() = runTest {
        service.responses = flowOf(completed("Answer", listOf(ctaElement()), feedbackEligible = true))
        val conversation = conversation()
        conversation.send("hello")
        val reply = conversation.messages.value.last()

        conversation.sendFeedback(reply.id, ConciergeFeedback(ConciergeFeedback.Rating.NEGATIVE))

        assertFalse(reply.feedbackEnabled)
        assertTrue(service.feedback.isEmpty())
        assertTrue(dispatched.none { it.name == names.FEEDBACK_SUBMITTED })
    }

    @Test
    fun `an identity reset clears and closes the conversation and acknowledges the teardown`() = runTest {
        val responses = Channel<ParsedConversationMessage>(Channel.UNLIMITED)
        service.responses = responses.receiveAsFlow()
        val conversation = conversation()
        conversation.send("hello")
        responses.send(inProgress("Partial"))
        val resetEvents = mutableListOf<Event>()

        repository.beginIdentityReset(mockk(relaxed = true)) { resetEvents.add(it) }

        assertTrue(conversation.messages.value.isEmpty())
        assertEquals(ConciergeConversationState.Closed(ConciergeCloseReason.IDENTITY_RESET), conversation.state.value)
        val ended = resetEvents.single { it.name == names.CONVERSATION_ENDED }
        assertEquals("conv-1", ended.eventData[keys.CONVERSATION_ID])
        assertEquals(true, ended.eventData[keys.HAD_ACTIVE_TURN])

        assertEquals(ConciergeSendResult.CLOSED, conversation.send("after the reset"))
        assertTrue(conversation.messages.value.isEmpty())
    }

    @Test
    fun `track reports the same events as the built-in UI`() = runTest {
        val conversation = conversation()
        val card = ConciergeElement.ProductCard(
            id = "p1", productName = "Runner", productPageUrl = "https://example.com/runner"
        )

        conversation.track(ConciergeInteraction.CtaClicked("Shop all", "https://example.com/all"))
        conversation.track(ConciergeInteraction.ProductActionClicked(card, ConciergeAction("Buy", "https://example.com/buy")))
        conversation.track(ConciergeInteraction.LinkClicked(""))

        val events = dispatched.drop(1)
        assertEquals(
            listOf(names.CTA_BUTTON_CLICKED, names.LINK_CLICKED, names.CARD_CLICKED, names.LINK_CLICKED),
            events.map { it.name }
        )
        assertEquals("cta", events[1].eventData[keys.ORIGIN])
        assertEquals(
            mapOf("productName" to "Runner", "productPageURL" to "https://example.com/buy"),
            events[2].eventData[keys.ELEMENT]
        )
        assertEquals("productCard", events[3].eventData[keys.ORIGIN])
    }

    @Test
    fun `close stops the response and ignores later messages`() = runTest {
        val responses = Channel<ParsedConversationMessage>(Channel.UNLIMITED)
        service.responses = responses.receiveAsFlow()
        val conversation = conversation()
        conversation.send("hello")
        responses.send(inProgress("Partial"))

        conversation.close()
        conversation.send("ignored")
        conversation.close()

        assertEquals(ConciergeConversationState.Closed(ConciergeCloseReason.CLOSED_BY_APP), conversation.state.value)
        assertEquals(listOf("hello", "Partial"), conversation.messages.value.map { it.text })
        assertFalse(conversation.messages.value.last().isStreaming)
        assertEquals(1, service.cleanups)
    }

    @Test
    fun `a data handoff shows the local message and streams the response`() = runTest {
        repository.updateXDMContext(mapOf("fan" to mapOf("seat" to "A1")))
        service.handoffResponses = flowOf(inProgress("Thanks"), completed("Thanks for your order"))
        val conversation = conversation()

        handoff(localMessage = "Order placed")

        assertEquals(listOf(DataHandoffDeliveryResult.Delivered), handoffResults)
        assertEquals(ConciergeConversationState.Idle, conversation.state.value)
        assertEquals(
            listOf(ConciergeMessage.Role.APP to "Order placed", ConciergeMessage.Role.AGENT to "Thanks for your order"),
            conversation.messages.value.map { it.role to it.text }
        )
        assertEquals("successful-checkout", service.handoffRoutingHint)
        assertEquals(mapOf("seat" to "A1"), service.handoffXdmFields?.get("fan"))
        assertEquals(mapOf("orderId" to "1"), service.handoffXdmFields?.get("commerce"))
    }

    @Test
    fun `a data handoff is rejected while responding and after close`() = runTest {
        service.responses = Channel<ParsedConversationMessage>().receiveAsFlow()
        val conversation = conversation()
        conversation.send("hello")

        handoff()
        conversation.close()
        handoff()

        assertEquals(
            listOf(
                DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.CHAT_IN_PROGRESS),
                DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.NO_ACTIVE_SESSION)
            ),
            handoffResults
        )
    }

    @Test
    fun `a data handoff with no first response times out and keeps the local message`() = runTest {
        service.handoffResponses = Channel<ParsedConversationMessage>().receiveAsFlow()
        val conversation = conversation()

        handoff(localMessage = "Order placed")
        assertTrue(handoffResults.isEmpty())
        testScheduler.advanceTimeBy(ConciergeConstants.DataHandoff.FIRST_CHUNK_TIMEOUT_MS + 1)

        assertEquals(
            listOf(DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.DELIVERY_TIMEOUT)),
            handoffResults
        )
        assertEquals(ConciergeConversationState.Failed(ConciergeError.Timeout), conversation.state.value)
        assertEquals(listOf("Order placed"), conversation.messages.value.map { it.text })
    }

    @Test
    fun `an empty or cancelled data handoff reports a failure`() = runTest {
        val conversation = conversation()
        handoff()

        service.handoffResponses = Channel<ParsedConversationMessage>().receiveAsFlow()
        handoff()
        conversation.cancel()

        assertEquals(
            listOf(
                DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.EMPTY_RESPONSE),
                DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.DELIVERY_FAILED, "Cancelled by the app")
            ),
            handoffResults
        )
        assertEquals(ConciergeConversationState.Idle, conversation.state.value)
    }

    @Test
    fun `send reports why a message was ignored`() = runTest {
        service.responses = Channel<ParsedConversationMessage>().receiveAsFlow()
        val conversation = conversation()

        assertEquals(ConciergeSendResult.BLANK, conversation.send("   "))
        assertEquals(ConciergeSendResult.ACCEPTED, conversation.send("hello"))
        assertEquals(ConciergeSendResult.IN_PROGRESS, conversation.send("again"))
        assertEquals(ConciergeSendResult.IN_PROGRESS, conversation.retry())
        conversation.cancel()
        assertEquals(ConciergeSendResult.NOTHING_TO_RETRY, conversation.retry())
        conversation.close()
        assertEquals(ConciergeSendResult.CLOSED, conversation.send("closed"))

        repository.beginIdentityReset(mockk(relaxed = true)) {}
        assertEquals(ConciergeSendResult.RESET_PENDING, conversation().send("during reset"))
    }

    @Test
    fun `retry resends the failed message without adding it again`() = runTest {
        service.responses = emptyFlow()
        val conversation = conversation()
        conversation.send("hello")
        assertEquals(ConciergeConversationState.Failed(ConciergeError.InvalidResponseData), conversation.state.value)

        service.responses = flowOf(completed("Answer"))
        assertEquals(ConciergeSendResult.ACCEPTED, conversation.retry())

        assertEquals(ConciergeConversationState.Idle, conversation.state.value)
        assertEquals(listOf("hello", "Answer"), conversation.messages.value.map { it.text })
        assertEquals(listOf("hello", "hello"), service.queries)
        assertEquals(ConciergeSendResult.NOTHING_TO_RETRY, conversation.retry())
    }

    @Test
    fun `a failed data handoff can't be retried`() = runTest {
        val conversation = conversation()
        handoff()

        assertTrue(conversation.state.value is ConciergeConversationState.Failed)
        assertEquals(ConciergeSendResult.NOTHING_TO_RETRY, conversation.retry())
    }

    @Test
    fun `the conversation keeps its session and closes when the session expires`() = runTest {
        service.responses = flowOf(completed("Answer"))
        val conversation = conversation()
        conversation.send("hello")
        assertEquals("session-1", conversation.sessionId)
        assertEquals(listOf("session-1"), service.sessionIds)
        verify(exactly = 0) { sessionManager.getSessionId() }

        every { sessionManager.currentSessionIdOrNull() } returns "session-2"

        assertEquals(ConciergeSendResult.CLOSED, conversation.send("again"))
        assertEquals(ConciergeConversationState.Closed(ConciergeCloseReason.SESSION_EXPIRED), conversation.state.value)
        assertEquals(listOf("hello", "Answer"), conversation.messages.value.map { it.text })
    }

    @Test
    fun `a new conversation on the same surfaces replaces the open one`() = runTest {
        val first = conversation()
        val other = conversation(listOf("other"))
        val second = conversation()

        assertEquals(ConciergeConversationState.Closed(ConciergeCloseReason.REPLACED), first.state.value)
        assertEquals(ConciergeConversationState.Idle, other.state.value)
        assertEquals(ConciergeConversationState.Idle, second.state.value)
    }

    @Test
    fun `data handoffs go to the conversation most recently opened or used`() = runTest {
        service.responses = flowOf(completed("Answer"))
        val first = conversation()
        val second = conversation(listOf("other"))

        handoff(localMessage = "To second")
        first.send("hello")
        handoff(localMessage = "To first")

        assertEquals("To second", second.messages.value.first().text)
        assertEquals(listOf("hello", "Answer", "To first"), first.messages.value.map { it.text })
    }

    @Test
    fun `a message interrupted in the background is resent once in the foreground`() = runTest {
        val background = MutableStateFlow(true)
        service.responses = flow { throw IOException("Connection reset") }
        val conversation = conversation(appInBackground = background)

        conversation.send("hello")
        assertEquals(ConciergeConversationState.Responding, conversation.state.value)
        assertEquals(listOf("hello"), conversation.messages.value.map { it.text })
        assertTrue(dispatched.none { it.name == names.ERROR_OCCURRED })

        background.value = false

        assertEquals(ConciergeConversationState.Failed(ConciergeError.Unreachable), conversation.state.value)
        assertEquals(listOf("hello", "hello"), service.queries)
        assertEquals(1, dispatched.count { it.name == names.ERROR_OCCURRED })
    }

    @Test
    fun `the background resend is skipped when the session expired`() = runTest {
        val background = MutableStateFlow(true)
        service.responses = flow { throw IOException("Connection reset") }
        val conversation = conversation(appInBackground = background)
        conversation.send("hello")

        every { sessionManager.currentSessionIdOrNull() } returns null
        service.responses = flowOf(completed("Answer"))
        background.value = false

        assertEquals(ConciergeConversationState.Failed(ConciergeError.Unreachable), conversation.state.value)
        assertEquals(listOf("hello"), service.queries)
    }

    @Test
    fun `an HTTP status error in the background is not resent`() = runTest {
        service.responses = flow { throw ConciergeHttpStatusException(429, "HTTP error: 429 Too Many Requests") }
        val conversation = conversation(appInBackground = MutableStateFlow(true))

        conversation.send("hello")

        assertEquals(ConciergeConversationState.Failed(ConciergeError.HttpStatus(429)), conversation.state.value)
        assertEquals(listOf("hello"), service.queries)
    }

    @Test
    fun `link hints are mapped and a tapped hint is tracked`() = runTest {
        service.responses = flowOf(
            completed("Call us").copy(linkHints = listOf(LinkHint(kind = "phone", href = "tel:5551234")))
        )
        val conversation = conversation()
        conversation.send("hello")
        val hint = conversation.messages.value.last().linkHints.single()
        assertEquals(ConciergeLinkHint("tel:5551234", "phone"), hint)

        conversation.track(ConciergeInteraction.LinkHintClicked(hint))

        val event = dispatched.last()
        assertEquals(names.LINK_CLICKED, event.name)
        assertEquals("linkHint", event.eventData[keys.ORIGIN])
    }

    @Test
    fun `an HTTP status error after a complete response fails the turn`() = runTest {
        service.responses = flow {
            emit(completed("Answer"))
            throw ConciergeHttpStatusException(500, "HTTP error: 500 Internal Server Error")
        }
        val conversation = conversation()

        conversation.send("hello")

        assertEquals(ConciergeConversationState.Failed(ConciergeError.HttpStatus(500)), conversation.state.value)
        assertEquals(listOf(ConciergeMessage.Role.USER), conversation.messages.value.map { it.role })
        val error = dispatched.single { it.name == names.ERROR_OCCURRED }
        assertEquals("Failed to process response: HTTP error: 500 Internal Server Error", error.eventData[keys.ERROR_MESSAGE])
    }

    @Test
    fun `conversation waits for configuration and uses only non-blank surfaces`() = runTest {
        val notReady = ConciergeStateRepository(
            initialState = ConciergeState(experienceCloudId = "ecid"),
            sessionManager = sessionManager,
            resetWarningScope = CoroutineScope(StandardTestDispatcher())
        )
        var created: List<String>? = null
        val result = async {
            Concierge.conversation(listOf("", "surface"), notReady, sessionManager, 5_000L) { surfaces, _ ->
                created = surfaces
                conversation()
            }
        }
        testScheduler.runCurrent()
        assertNull(created)

        notReady.updateConfiguration(SharedStateResult(SharedStateStatus.SET, mapOf("concierge.server" to "server")))
        result.await()

        assertEquals(listOf("surface"), created)
    }

    @Test
    fun `conversation throws when configuration isn't ready in time or surfaces are blank`() = runTest {
        val configurationError = runCatching {
            Concierge.conversation(listOf("surface"), repository, sessionManager, 5_000L) { _, _ -> conversation() }
        }.exceptionOrNull()
        val surfacesError = runCatching {
            Concierge.conversation(listOf(" "), repository, sessionManager, 5_000L) { _, _ -> conversation() }
        }.exceptionOrNull()

        assertTrue(configurationError is ConciergeConfigurationException)
        assertTrue(surfacesError is IllegalArgumentException)
    }

    private fun handoff(localMessage: String? = null) {
        ActiveConciergeDataHandoffForwarder.forward(
            ConciergeDataHandoffEvent("successful-checkout", mapOf("commerce" to mapOf("orderId" to "1")), localMessage)
        ) { handoffResults.add(it) }
    }

    private fun TestScope.conversation(
        surfaces: List<String> = listOf("surface"),
        appInBackground: MutableStateFlow<Boolean>? = null
    ): ConciergeConversation {
        return ConciergeConversation(
            surfaces = surfaces,
            sessionId = "session-1",
            stateRepository = repository,
            sessionManager = sessionManager,
            service = service,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            appInBackground = appInBackground,
            dispatch = { dispatched.add(it) },
            currentTimeMillis = { 1_000L }
        ).also { conversations.add(it) }
    }

    private fun message(text: String, state: ConversationState) = ParsedConversationMessage(
        messageContent = text,
        state = state,
        conversationId = "conv-1",
        interactionId = "interaction-1"
    )

    private fun inProgress(text: String) = message(text, ConversationState.IN_PROGRESS)

    private fun completed(
        text: String,
        elements: List<RawMultimodalElement> = emptyList(),
        feedbackEligible: Boolean = false
    ) = message(text, ConversationState.COMPLETED).copy(
        rawElements = elements,
        orderedElements = elements.mapNotNull { it.parsedBuiltIn },
        promptSuggestions = listOf("More like this"),
        sources = listOf(com.adobe.marketing.mobile.concierge.network.Citation("Guide", "https://example.com/guide", 1)),
        feedbackEligible = feedbackEligible
    )

    private fun productElement(): RawMultimodalElement {
        val content = mapOf(
            "productName" to "Runner",
            "productImageURL" to "https://example.com/runner.jpg",
            "productPageURL" to "https://example.com/runner",
            "primaryText" to "Buy",
            "primaryUrl" to "https://example.com/buy"
        )
        return RawMultimodalElement(
            0, "product", null, mapOf("type" to "product"),
            ParsedMultimodalItem.Card(MultimodalElement("p1", content = content))
        )
    }

    private fun ctaElement() = RawMultimodalElement(
        1, "cta", null, mapOf("type" to "cta"),
        ParsedMultimodalItem.Cta(CtaButton("Shop all", "https://example.com/all"))
    )

    private fun customElement() =
        RawMultimodalElement(2, "custom", "store-map", mapOf("type" to "custom"), null)

    private fun unknownElement() = RawMultimodalElement(3, "video", null, mapOf("type" to "video"), null)

    private class FakeService : ConversationService {
        var responses: Flow<ParsedConversationMessage> = emptyFlow()
        var handoffResponses: Flow<ParsedConversationMessage> = emptyFlow()
        var handoffRoutingHint: String? = null
        var handoffXdmFields: Map<String, Any>? = null
        val feedback = mutableListOf<Feedback>()
        val queries = mutableListOf<String>()
        val sessionIds = mutableListOf<String?>()
        var cleanups = 0

        override fun chat(message: String, xdmFields: Map<String, Any>, sessionId: String?): Flow<ParsedConversationMessage> {
            queries.add(message)
            sessionIds.add(sessionId)
            return responses
        }
        override fun sendDataHandoff(routingHint: String, xdmFields: Map<String, Any>, sessionId: String?): Flow<ParsedConversationMessage> {
            handoffRoutingHint = routingHint
            handoffXdmFields = xdmFields
            return handoffResponses
        }
        override suspend fun sendFeedback(feedback: Feedback): Boolean = this.feedback.add(feedback)
        override fun cleanup() {
            cleanups++
        }
    }
}
