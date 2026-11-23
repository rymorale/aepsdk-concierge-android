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
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.concierge.network.CUSTOM_ELEMENT_TYPE
import com.adobe.marketing.mobile.concierge.network.ConciergeConversationServiceClient
import com.adobe.marketing.mobile.concierge.network.ConversationService
import com.adobe.marketing.mobile.concierge.network.ConversationState
import com.adobe.marketing.mobile.concierge.network.MultimodalElement
import com.adobe.marketing.mobile.concierge.network.ParsedConversationMessage
import com.adobe.marketing.mobile.concierge.network.ParsedMultimodalItem
import com.adobe.marketing.mobile.concierge.network.RawMultimodalElement
import com.adobe.marketing.mobile.concierge.network.RequestStartedFlow
import com.adobe.marketing.mobile.concierge.ui.state.Feedback
import com.adobe.marketing.mobile.concierge.ui.state.FeedbackType
import com.adobe.marketing.mobile.services.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** The state of a [ConciergeConversation]. */
enum class ConciergeConversationState {
    /** Ready to send a message. */
    IDLE,

    /** Waiting for or receiving an agent response. */
    RESPONDING,

    /** The last response failed. The conversation can still send messages. */
    FAILED,

    /** [ConciergeConversation.close] was called. */
    CLOSED
}

/**
 * A Brand Concierge conversation with no UI, for apps that draw the chat themselves. Create one
 * with [Concierge.conversation].
 *
 * The conversation sends messages, streams responses into [messages], sends feedback and reports
 * analytics events. It uses the same session, identity, consent, auth token and XDM context as the
 * built-in chat UI, and the settings on [Concierge] apply to both.
 *
 * While open, the conversation receives [Concierge.sendDataHandoff] requests, and their responses
 * stream into [messages]. Only one conversation or built-in chat receives handoffs at a time: the
 * most recently opened one.
 *
 * Call its methods on the main thread. Call [close] when the conversation is no longer needed,
 * for example in `ViewModel.onCleared`.
 */
class ConciergeConversation internal constructor(
    private val stateRepository: ConciergeStateRepository = ConciergeStateRepository.instance,
    private val sessionManager: ConciergeSessionManager = ConciergeSessionManager.instance,
    private val service: ConversationService = ConciergeConversationServiceClient(stateRepository, sessionManager),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val sessionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val dispatch: (Event) -> Unit = MobileCore::dispatchEvent,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) : Closeable {

    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + dispatcher)

    // Turns and feedback requests run under this job, so an identity reset can cancel them all.
    private var conversationJob: CompletableJob = SupervisorJob(rootJob)
    private var turnJob: Job? = null
    private val resetParticipant = Any()
    private var observedResetGeneration = stateRepository.state.value.resetGeneration
    private var conversationId: String? = null
    private val interactionIds = mutableMapOf<String, String>()
    private var openedAt: Long? = null
    private var handoff: DataHandoff? = null

    private val handoffForwarder = object : ConciergeDataHandoffForwarder {
        // Called on the event hub thread.
        override fun forward(result: ConciergeDataHandoffEvent, completion: (DataHandoffDeliveryResult) -> Unit) {
            val request = DataHandoff(result, completion)
            scope.launch { startDataHandoff(request) }.invokeOnCompletion { cause ->
                if (cause != null) request.complete(DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.NO_ACTIVE_SESSION))
            }
        }
    }

    private val _messages = MutableStateFlow<List<ConciergeMessage>>(emptyList())
    private val _state = MutableStateFlow(ConciergeConversationState.IDLE)

    /** The messages in the conversation, oldest first. */
    val messages: StateFlow<List<ConciergeMessage>> = _messages.asStateFlow()

    /** The state of the conversation. */
    val state: StateFlow<ConciergeConversationState> = _state.asStateFlow()

    init {
        stateRepository.registerResetParticipant(resetParticipant)
        scope.launch { stateRepository.state.collect { observeIdentityReset(it) } }
        ActiveConciergeDataHandoffForwarder.register(handoffForwarder)
        report(ConciergeTrackingEvent.SessionInitialized)
    }

    /**
     * Sends [text] as a user message and streams the response into [messages].
     *
     * The message is ignored if it is blank, while a response is in progress, while an identity
     * reset is pending, or after [close].
     */
    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        observeIdentityReset(stateRepository.state.value)
        val rejection = when {
            _state.value == ConciergeConversationState.CLOSED -> "the conversation is closed"
            turnJob?.isActive == true -> "a response is in progress"
            stateRepository.state.value.resetInProgress -> "an identity reset is pending"
            else -> null
        }
        if (rejection != null) {
            Log.warning(ConciergeConstants.EXTENSION_NAME, TAG, "Message ignored because $rejection.")
            return
        }

        val snapshot = stateRepository.captureXDMContext()
        val turn = Turn(newId(), snapshot.generation)
        _messages.update { it + ConciergeMessage(newId(), ConciergeMessage.Role.USER, message) }
        _state.value = ConciergeConversationState.RESPONDING
        turnJob = scope.launch(conversationJob) {
            runTurn(turn, snapshot) { sessionId, xdmFields ->
                report(ConciergeTrackingEvent.QuerySubmitted(message, xdmFields))
                service.chat(message, xdmFields, sessionId)
            }
        }
    }

    /**
     * Stops the response in progress. The text received so far stays in [messages]. A data handoff
     * stopped this way reports [ConciergeDataHandoffRejectReason.DELIVERY_FAILED].
     */
    fun cancel() {
        val job = turnJob?.takeIf { it.isActive } ?: return
        handoff?.complete(
            DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.DELIVERY_FAILED, "Cancelled by the app")
        )
        turnJob = null
        job.cancel()
        stopStreaming()
        if (_state.value == ConciergeConversationState.RESPONDING) {
            _state.value = ConciergeConversationState.IDLE
        }
    }

    /**
     * Sends [feedback] on the agent message with [messageId]. The message must have
     * [ConciergeMessage.feedbackEnabled] set.
     */
    fun sendFeedback(messageId: String, feedback: ConciergeFeedback) {
        observeIdentityReset(stateRepository.state.value)
        val repositoryState = stateRepository.state.value
        val interactionId = interactionIds[messageId]
        val rejection = when {
            _state.value == ConciergeConversationState.CLOSED -> "the conversation is closed"
            repositoryState.resetInProgress -> "an identity reset is pending"
            interactionId == null -> "message $messageId does not accept feedback"
            else -> null
        }
        if (rejection != null || interactionId == null) {
            Log.warning(ConciergeConstants.EXTENSION_NAME, TAG, "Feedback ignored because $rejection.")
            return
        }

        _messages.update { messages ->
            messages.map { if (it.id == messageId) it.copy(feedback = feedback.rating) else it }
        }
        val type = when (feedback.rating) {
            ConciergeFeedback.Rating.POSITIVE -> FeedbackType.POSITIVE
            ConciergeFeedback.Rating.NEGATIVE -> FeedbackType.NEGATIVE
        }
        report(
            ConciergeTrackingEvent.FeedbackSubmitted(
                conversationId = conversationId ?: "",
                interactionId = interactionId,
                feedbackType = when (type) {
                    FeedbackType.POSITIVE -> ConciergeConstants.ChatInteraction.POSITIVE
                    FeedbackType.NEGATIVE -> ConciergeConstants.ChatInteraction.NEGATIVE
                },
                selectedOptions = feedback.categories,
                notes = feedback.notes
            )
        )
        val request = Feedback(interactionId, type, feedback.categories, feedback.notes, conversationId)
        val generation = repositoryState.resetGeneration
        scope.launch(conversationJob) {
            stateRepository.withConversation(generation) { }
            if (!service.sendFeedback(request, generation)) {
                Log.warning(ConciergeConstants.EXTENSION_NAME, TAG, "Failed to send feedback for $messageId.")
            }
        }
    }

    /**
     * Reports a user interaction to analytics. Each interaction sends the same events as the
     * built-in chat UI. Events reach Edge only when [Concierge.setEdgeTrackingEnabled] is on.
     *
     * Reporting doesn't act on the interaction: the app opens links and sends prompt suggestions.
     */
    fun track(interaction: ConciergeInteraction) {
        val origin = ConciergeConstants.TrackingEvent.LinkClickOrigin
        when (interaction) {
            ConciergeInteraction.Opened -> {
                val now = currentTimeMillis()
                openedAt = now
                report(ConciergeTrackingEvent.ChatOpened(now))
            }
            ConciergeInteraction.Closed -> {
                val now = currentTimeMillis()
                report(ConciergeTrackingEvent.ChatClosed(now, openedAt?.let { now - it } ?: 0L))
                openedAt = null
            }
            is ConciergeInteraction.PromptSuggestionClicked ->
                report(ConciergeTrackingEvent.PromptSuggestionClicked(interaction.suggestion))
            is ConciergeInteraction.CtaClicked -> {
                report(ConciergeTrackingEvent.CtaButtonClicked(interaction.label, interaction.url))
                reportLink(interaction.url, origin.CTA)
            }
            is ConciergeInteraction.ProductCardClicked -> {
                val card = interaction.card
                val details = listOfNotNull(
                    card.productName?.let { "productName" to it },
                    card.productDescription?.let { "productDescription" to it },
                    card.productPageUrl?.let { "productPageURL" to it },
                    card.productPrice?.let { "productPrice" to it },
                    card.productBadge?.let { "productBadge" to it }
                ).toMap()
                report(ConciergeTrackingEvent.CardClicked(details))
                reportLink(card.productPageUrl, origin.PRODUCT_CARD)
            }
            is ConciergeInteraction.ProductActionClicked -> {
                val action = interaction.action
                val details = listOfNotNull(
                    (interaction.card.productName ?: action.text)?.let { "productName" to it },
                    action.url?.let { "productPageURL" to it }
                ).toMap()
                report(ConciergeTrackingEvent.CardClicked(details))
                reportLink(action.url, origin.PRODUCT_CARD)
            }
            is ConciergeInteraction.SourceClicked -> reportLink(interaction.source.url, origin.CITATION)
            is ConciergeInteraction.LinkClicked -> reportLink(interaction.url, origin.INLINE)
        }
    }

    /**
     * Stops the response in progress and releases the conversation. After this, [state] is
     * [ConciergeConversationState.CLOSED] and new messages are ignored. Calling it again does
     * nothing.
     */
    override fun close() {
        if (_state.value == ConciergeConversationState.CLOSED) return
        val hadActiveTurn = turnJob?.isActive == true
        val hadConversation = _messages.value.isNotEmpty() || hadActiveTurn
        ActiveConciergeDataHandoffForwarder.unregister(handoffForwarder)
        rootJob.cancel()
        service.cleanup()
        stateRepository.acknowledgeIdentityReset(
            resetParticipant, stateRepository.state.value.resetGeneration,
            hadConversation, conversationId, hadActiveTurn
        )
        stateRepository.unregisterResetParticipant(resetParticipant)
        stopStreaming()
        _state.value = ConciergeConversationState.CLOSED
    }

    private class Turn(val agentMessageId: String, val generation: Long) {
        val text = StringBuilder()
        var interactionId: String? = null
        var responseStarted = false
        var completed = false
    }

    private enum class TurnOutcome { DELIVERED, EMPTY, FAILED }

    /** A [Concierge.sendDataHandoff] request whose [completion] runs exactly once. */
    private class DataHandoff(
        val event: ConciergeDataHandoffEvent,
        private val completion: (DataHandoffDeliveryResult) -> Unit
    ) {
        private val completed = AtomicBoolean(false)

        fun complete(result: DataHandoffDeliveryResult) {
            if (completed.compareAndSet(false, true)) completion(result)
        }
    }

    private class DataHandoffTimeoutException : Exception("Data handoff timed out")

    private fun startDataHandoff(request: DataHandoff) {
        observeIdentityReset(stateRepository.state.value)
        val rejection = when {
            _state.value == ConciergeConversationState.CLOSED || stateRepository.state.value.resetInProgress ->
                ConciergeDataHandoffRejectReason.NO_ACTIVE_SESSION
            turnJob?.isActive == true -> ConciergeDataHandoffRejectReason.CHAT_IN_PROGRESS
            else -> null
        }
        if (rejection != null) {
            request.complete(DataHandoffDeliveryResult.Failed(rejection))
            return
        }

        val snapshot = stateRepository.captureXDMContext()
        val turn = Turn(newId(), snapshot.generation)
        // The local message is app-supplied copy, not something the user typed.
        request.event.localMessage?.let { text ->
            _messages.update { it + ConciergeMessage(newId(), ConciergeMessage.Role.AGENT, text) }
        }
        _state.value = ConciergeConversationState.RESPONDING
        handoff = request
        val job = scope.launch(conversationJob) { request.complete(runDataHandoff(turn, snapshot, request.event)) }
        // Covers a close, an identity reset or a cancel before the handoff finished.
        job.invokeOnCompletion {
            request.complete(DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.NO_ACTIVE_SESSION))
        }
        turnJob = job
    }

    /**
     * Runs a data handoff turn with the same two caps as the built-in chat: one for the whole turn,
     * and one for the wait between sending the request and receiving the first response.
     */
    private suspend fun runDataHandoff(
        turn: Turn,
        snapshot: ConciergeStateRepository.XdmContextSnapshot,
        event: ConciergeDataHandoffEvent
    ): DataHandoffDeliveryResult = try {
        coroutineScope {
            val requestStarted = CompletableDeferred<Unit>()
            val firstResponse = CompletableDeferred<Unit>()
            launch {
                delay(ConciergeConstants.DataHandoff.DELIVERY_TIMEOUT_MS)
                throw DataHandoffTimeoutException()
            }
            launch {
                requestStarted.await()
                withTimeoutOrNull(ConciergeConstants.DataHandoff.FIRST_CHUNK_TIMEOUT_MS) { firstResponse.await() }
                    ?: throw DataHandoffTimeoutException()
            }
            val outcome = runTurn(turn, snapshot) { sessionId, xdmFields ->
                val request = service.sendDataHandoff(
                    event.routingHint,
                    stateRepository.mergeXdmFields(xdmFields, event.xdmFields),
                    sessionId
                )
                @Suppress("UNCHECKED_CAST")
                val started = (request as? RequestStartedFlow<ParsedConversationMessage>)
                    ?.onRequestStarted { requestStarted.complete(Unit) }
                    ?: request.onStart { requestStarted.complete(Unit) }
                started.onEach { firstResponse.complete(Unit) }
            }
            coroutineContext.cancelChildren()
            when (outcome) {
                TurnOutcome.DELIVERED -> DataHandoffDeliveryResult.Delivered
                TurnOutcome.EMPTY -> DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.EMPTY_RESPONSE)
                TurnOutcome.FAILED -> DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.DELIVERY_FAILED)
            }
        }
    } catch (e: DataHandoffTimeoutException) {
        failTurn(turn, e.message ?: "Data handoff timed out")
        DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.DELIVERY_TIMEOUT)
    }

    /**
     * Streams one agent response into [messages]. [request] receives the session ID and the
     * resolved XDM context, and returns the response flow.
     */
    private suspend fun runTurn(
        turn: Turn,
        snapshot: ConciergeStateRepository.XdmContextSnapshot,
        request: (sessionId: String, xdmFields: Map<String, Any>) -> Flow<ParsedConversationMessage>
    ): TurnOutcome {
        try {
            val sessionId = withContext(sessionDispatcher) {
                stateRepository.withConversation(turn.generation) { sessionManager.getSessionId() }
            }
            val responses = stateRepository.withConversation(turn.generation) {
                request(sessionId, stateRepository.resolveXDMContext(snapshot, sessionId))
            }
            var errorDetail: String? = null
            responses
                .onEach { stateRepository.withConversation(turn.generation) { } }
                .takeWhile { response ->
                    if (response.state == ConversationState.ERROR) {
                        errorDetail = "Conversation error: ${response.messageContent}"
                    }
                    errorDetail == null
                }
                .collect { onResponse(turn, it) }
            return when {
                errorDetail != null -> failTurn(turn, errorDetail!!, TurnOutcome.FAILED)
                hasResponse(turn) -> finishTurn()
                else -> failTurn(turn, "Conversation ended with no response content", TurnOutcome.EMPTY)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A connection dropped after the full response arrived leaves nothing to recover.
            return if (turn.completed && e is IOException && hasResponse(turn)) {
                finishTurn()
            } else {
                failTurn(turn, "Failed to process response: ${e.message}")
            }
        }
    }

    private fun onResponse(turn: Turn, response: ParsedConversationMessage) {
        response.conversationId?.let { conversationId = it }
        response.interactionId?.let { turn.interactionId = it }
        if (response.state == ConversationState.COMPLETED) {
            completeTurn(turn, response)
            return
        }
        turn.text.append(response.messageContent)
        if (turn.text.isBlank()) return
        reportResponseStarted(turn)
        upsert(ConciergeMessage(turn.agentMessageId, ConciergeMessage.Role.AGENT, turn.text.toString(), isStreaming = true))
    }

    private fun completeTurn(turn: Turn, response: ParsedConversationMessage) {
        turn.completed = true
        val text = response.messageContent.ifBlank { turn.text.toString() }
        val elements = response.rawElements.map { it.toConciergeElement() }
        val interactionId = turn.interactionId
        // Responses with CTA buttons are deterministic, so the built-in UI doesn't ask for feedback.
        val hasCta = response.orderedElements.any { it is ParsedMultimodalItem.Cta }
        val feedbackEnabled = response.feedbackEligible && !hasCta && interactionId != null
        if (text.isNotBlank() || elements.isNotEmpty()) {
            reportResponseStarted(turn)
            if (feedbackEnabled && interactionId != null) interactionIds[turn.agentMessageId] = interactionId
            upsert(
                ConciergeMessage(
                    id = turn.agentMessageId,
                    role = ConciergeMessage.Role.AGENT,
                    text = text,
                    elements = elements,
                    sources = response.sources.map {
                        ConciergeSource(it.title, it.url, it.citationNumber, it.startIndex, it.endIndex)
                    },
                    promptSuggestions = response.promptSuggestions,
                    feedbackEnabled = feedbackEnabled
                )
            )
        }
        report(ConciergeTrackingEvent.ResponseCompleted(conversationId ?: "", interactionId ?: ""))
    }

    private fun reportResponseStarted(turn: Turn) {
        if (turn.responseStarted) return
        turn.responseStarted = true
        report(ConciergeTrackingEvent.ResponseStarted(conversationId ?: "", turn.interactionId ?: ""))
    }

    private fun hasResponse(turn: Turn) = _messages.value.any { it.id == turn.agentMessageId }

    private fun finishTurn(): TurnOutcome {
        stopStreaming()
        _state.value = ConciergeConversationState.IDLE
        return TurnOutcome.DELIVERED
    }

    private fun failTurn(turn: Turn, detail: String, outcome: TurnOutcome = TurnOutcome.FAILED): TurnOutcome {
        _messages.update { messages -> messages.filterNot { it.id == turn.agentMessageId } }
        interactionIds.remove(turn.agentMessageId)
        Log.error(ConciergeConstants.EXTENSION_NAME, TAG, detail)
        report(ConciergeTrackingEvent.ErrorOccurred(detail))
        _state.value = ConciergeConversationState.FAILED
        return outcome
    }

    private fun observeIdentityReset(state: ConciergeState) {
        if (state.resetGeneration == observedResetGeneration) return
        observedResetGeneration = state.resetGeneration
        if (_state.value == ConciergeConversationState.CLOSED) return

        val hadActiveTurn = turnJob?.isActive == true
        val hadConversation = _messages.value.isNotEmpty() || hadActiveTurn
        val endedConversationId = conversationId
        conversationJob.cancel()
        conversationJob = SupervisorJob(rootJob)
        turnJob = null
        _messages.value = emptyList()
        interactionIds.clear()
        conversationId = null
        _state.value = ConciergeConversationState.IDLE
        stateRepository.acknowledgeIdentityReset(
            resetParticipant, state.resetGeneration, hadConversation, endedConversationId, hadActiveTurn
        )
    }

    private fun upsert(message: ConciergeMessage) {
        _messages.update { messages ->
            val index = messages.indexOfFirst { it.id == message.id }
            if (index == -1) messages + message else messages.toMutableList().apply { set(index, message) }
        }
    }

    private fun stopStreaming() {
        _messages.update { messages -> messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it } }
    }

    private fun reportLink(url: String?, origin: String) {
        if (!url.isNullOrBlank()) report(ConciergeTrackingEvent.LinkClicked(url, origin))
    }

    private fun report(trackingEvent: ConciergeTrackingEvent) {
        val event = trackingEvent.toEvent()
        // Logs only the event name: payloads can hold user text and XDM fields.
        Log.debug(ConciergeConstants.EXTENSION_NAME, TAG, "Dispatching tracking event: ${event.name}")
        dispatch(event)
    }

    private fun newId() = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "ConciergeConversation"
    }
}

private fun RawMultimodalElement.toConciergeElement(): ConciergeElement =
    when (val builtIn = parsedBuiltIn) {
        is ParsedMultimodalItem.Card -> builtIn.element.toProductCard(payload)
        is ParsedMultimodalItem.Cta -> ConciergeElement.CtaButton(builtIn.button.label, builtIn.button.url, payload)
        null -> rendererId?.takeIf { type == CUSTOM_ELEMENT_TYPE }
            ?.let { ConciergeElement.Custom(it, payload) }
            ?: ConciergeElement.Unknown(type, payload)
    }

private fun MultimodalElement.toProductCard(raw: Map<String, Any?>): ConciergeElement.ProductCard {
    fun field(key: String) = content[key] as? String
    fun action(prefix: String) =
        ConciergeAction(field("${prefix}Text"), field("${prefix}Url")).takeIf { it.text != null || it.url != null }
    return ConciergeElement.ProductCard(
        id = id,
        productName = field("productName"),
        productDescription = field("productDescription"),
        description = field("description"),
        productPageUrl = field("productPageURL"),
        productImageUrl = field("productImageURL"),
        productPrice = field("productPrice"),
        productWasPrice = field("productWasPrice"),
        productBadge = field("productBadge"),
        primary = action("primary"),
        secondary = action("secondary"),
        raw = raw
    )
}
