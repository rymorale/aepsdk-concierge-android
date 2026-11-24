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
import com.adobe.marketing.mobile.concierge.network.ConciergeHttpStatusException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** The state of a [ConciergeConversation]. */
sealed class ConciergeConversationState {
    /** Ready to send a message. */
    object Idle : ConciergeConversationState() {
        override fun toString() = "Idle"
    }

    /** Waiting for or receiving an agent response. */
    object Responding : ConciergeConversationState() {
        override fun toString() = "Responding"
    }

    /**
     * The last response failed with [error]. The conversation can still send messages, or
     * [ConciergeConversation.retry] the failed one.
     */
    data class Failed(val error: ConciergeError) : ConciergeConversationState()

    /** The conversation ended for [reason] and sends nothing more. Create a new one. */
    data class Closed(val reason: ConciergeCloseReason) : ConciergeConversationState()

    /** True once the conversation is [Closed]. */
    val isClosed: Boolean get() = this is Closed
}

/** Why a [ConciergeConversation] response failed. */
enum class ConciergeError {
    /** The service couldn't be reached, or the connection dropped. */
    UNREACHABLE,

    /** The request or the data handoff took too long. */
    TIMEOUT,

    /** The service returned an error, or a response with nothing to show. */
    INVALID_RESPONSE_DATA,

    /** Any other failure. */
    UNKNOWN
}

/** Why a [ConciergeConversation] closed. */
enum class ConciergeCloseReason {
    /** The app called [ConciergeConversation.close]. */
    CLOSED_BY_APP,

    /** Another conversation was opened on the same surfaces. One conversation is open per set of surfaces. */
    REPLACED,

    /** The session expired after 30 minutes without a request, or another chat started a new one. */
    SESSION_EXPIRED,

    /** `MobileCore.resetIdentities()` was called. */
    IDENTITY_RESET
}

/** The outcome of [ConciergeConversation.send] and [ConciergeConversation.retry]. */
enum class ConciergeSendResult {
    /** The message was sent. Its response streams into [ConciergeConversation.messages]. */
    ACCEPTED,

    /** The message was ignored because it is blank. */
    BLANK,

    /** The message was ignored because a response or data handoff is in progress. */
    IN_PROGRESS,

    /** The message was ignored because the conversation is closed. See [ConciergeConversationState.Closed]. */
    CLOSED,

    /** The message was ignored because an identity reset is in progress. Retry after it finishes. */
    RESET_PENDING,

    /** [ConciergeConversation.retry] was called, but the last response didn't fail or wasn't for a typed message. */
    NOTHING_TO_RETRY
}

/**
 * Thrown by [Concierge.conversation] when the extension isn't ready in time: the Brand Concierge
 * configuration hasn't been received, there's no Experience Cloud ID yet, or an identity reset
 * hasn't finished.
 */
class ConciergeConfigurationException internal constructor(message: String) : IllegalStateException(message)

/**
 * A Brand Concierge conversation with no UI, for apps that draw the chat themselves. Create one
 * with [Concierge.conversation].
 *
 * The conversation sends messages, streams responses into [messages], sends feedback and reports
 * analytics events. It uses the same identity, consent, auth token and XDM context as the
 * built-in chat UI, and the settings on [Concierge] apply to both.
 *
 * One conversation is open per set of [surfaces]: opening another on the same surfaces closes this
 * one with [ConciergeCloseReason.REPLACED]. The conversation runs on one session, [sessionId], for
 * its whole life.
 *
 * While open, the conversation receives [Concierge.sendDataHandoff] requests, and their responses
 * stream into [messages]. Only one conversation or built-in chat receives handoffs at a time: the
 * conversation most recently opened or sent a message, or the built-in chat most recently opened.
 *
 * Once [ConciergeConversationState.Closed] - by [close], replacement, session expiry or an identity
 * reset - the conversation sends nothing more; create a new one.
 *
 * Call its methods on the main thread. Call [close] when the conversation is no longer needed,
 * for example in `ViewModel.onCleared`.
 */
class ConciergeConversation internal constructor(
    surfaces: List<String>,
    /** The session this conversation runs on. It never changes. */
    val sessionId: String,
    private val stateRepository: ConciergeStateRepository = ConciergeStateRepository.instance,
    private val sessionManager: ConciergeSessionManager = ConciergeSessionManager.instance,
    private val service: ConversationService =
        ConciergeConversationServiceClient(stateRepository, sessionManager, surfaces = surfaces),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    // Null turns off the resend of a message interrupted while the app was in the background.
    private val appInBackground: StateFlow<Boolean>? = null,
    private val dispatch: (Event) -> Unit = MobileCore::dispatchEvent,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) : Closeable {

    /** The surfaces this conversation was created with. */
    val surfaces: List<String> = surfaces.toList()

    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + dispatcher)

    private var turnJob: Job? = null
    private var activeTurn: Turn? = null
    private val resetParticipant = Any()
    private var observedResetGeneration = stateRepository.state.value.resetGeneration
    private var conversationId: String? = null
    private val interactionIds = mutableMapOf<String, String>()
    private var openedAt: Long? = null
    private var handoff: DataHandoff? = null

    // The typed message whose response failed last, for retry().
    private var retryableQuery: String? = null

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
    private val _state = MutableStateFlow<ConciergeConversationState>(ConciergeConversationState.Idle)

    /** The messages in the conversation, oldest first. */
    val messages: StateFlow<List<ConciergeMessage>> = _messages.asStateFlow()

    /** The state of the conversation. */
    val state: StateFlow<ConciergeConversationState> = _state.asStateFlow()

    init {
        OpenConciergeConversations.open(this)?.close(ConciergeCloseReason.REPLACED)
        stateRepository.registerResetParticipant(resetParticipant)
        scope.launch { stateRepository.state.collect { observeIdentityReset(it) } }
        appInBackground?.let { flow ->
            scope.launch { flow.collect { inBackground -> if (inBackground) activeTurn?.overlappedBackground = true } }
        }
        ActiveConciergeDataHandoffForwarder.markUsed(handoffForwarder)
        report(ConciergeTrackingEvent.SessionInitialized)
    }

    /**
     * Sends [text] as a user message and streams the response into [messages].
     *
     * @return [ConciergeSendResult.ACCEPTED] if the message was sent, otherwise why it was
     * ignored. Ignoring the result is safe.
     */
    fun send(text: String): ConciergeSendResult {
        val message = text.trim()
        val result = if (message.isEmpty()) ConciergeSendResult.BLANK else admissionRefusal() ?: ConciergeSendResult.ACCEPTED
        if (result != ConciergeSendResult.ACCEPTED) {
            Log.warning(ConciergeConstants.EXTENSION_NAME, TAG, "Message ignored: $result.")
            return result
        }
        _messages.update { it + ConciergeMessage(newId(), ConciergeMessage.Role.USER, message) }
        startChatTurn(message)
        return result
    }

    /**
     * Sends the typed message whose response failed again, without adding it to [messages] again.
     * Only valid while [state] is [ConciergeConversationState.Failed] after a typed message.
     *
     * @return [ConciergeSendResult.ACCEPTED] if the message was sent, otherwise why it wasn't.
     */
    fun retry(): ConciergeSendResult {
        val query = retryableQuery
        val result = admissionRefusal()
            ?: if (_state.value is ConciergeConversationState.Failed && query != null) {
                ConciergeSendResult.ACCEPTED
            } else {
                ConciergeSendResult.NOTHING_TO_RETRY
            }
        if (result != ConciergeSendResult.ACCEPTED || query == null) {
            Log.warning(ConciergeConstants.EXTENSION_NAME, TAG, "Retry ignored: $result.")
            return result
        }
        startChatTurn(query)
        return result
    }

    /** Why a message or data handoff can't start now, or null if it can. */
    private fun admissionRefusal(): ConciergeSendResult? {
        observeIdentityReset(stateRepository.state.value)
        if (!_state.value.isClosed && isSessionExpired()) close(ConciergeCloseReason.SESSION_EXPIRED)
        return when {
            _state.value.isClosed -> ConciergeSendResult.CLOSED
            stateRepository.state.value.resetInProgress -> ConciergeSendResult.RESET_PENDING
            turnJob?.isActive == true -> ConciergeSendResult.IN_PROGRESS
            else -> null
        }
    }

    private fun isSessionExpired() = sessionManager.currentSessionIdOrNull() != sessionId

    /**
     * Streams the response to [query]. If the response fails while the app is in the background,
     * the message is sent once more when the app returns to the foreground.
     */
    private fun startChatTurn(query: String) {
        retryableQuery = null
        ActiveConciergeDataHandoffForwarder.markUsed(handoffForwarder)
        _state.value = ConciergeConversationState.Responding
        turnJob = scope.launch {
            var allowsBackgroundResend = appInBackground != null
            while (true) {
                val snapshot = stateRepository.captureXDMContext()
                val turn = Turn(newId(), snapshot.generation, query, allowsBackgroundResend)
                turn.overlappedBackground = appInBackground?.value == true
                activeTurn = turn
                val outcome = runTurn(turn, snapshot) { sessionId, xdmFields ->
                    report(ConciergeTrackingEvent.QuerySubmitted(query, xdmFields))
                    service.chat(query, xdmFields, sessionId)
                }
                activeTurn = null
                if (outcome != TurnOutcome.INTERRUPTED) break
                appInBackground?.first { !it }
                if (isSessionExpired()) {
                    reportFailure(turn, turn.failure ?: "Response interrupted", turn.error)
                    break
                }
                allowsBackgroundResend = false
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
        activeTurn = null
        job.cancel()
        stopStreaming()
        if (_state.value == ConciergeConversationState.Responding) {
            _state.value = ConciergeConversationState.Idle
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
            _state.value.isClosed -> "the conversation is closed"
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
        scope.launch {
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
     * Interactions are ignored once the conversation is closed.
     */
    fun track(interaction: ConciergeInteraction) {
        if (_state.value.isClosed) return
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
            is ConciergeInteraction.LinkHintClicked -> reportLink(interaction.hint.href, origin.LINK_HINT)
        }
    }

    /**
     * Stops the response in progress and releases the conversation. After this, [state] is
     * [ConciergeConversationState.Closed] with [ConciergeCloseReason.CLOSED_BY_APP] and new
     * messages are ignored. Calling it again does nothing.
     */
    override fun close() = close(ConciergeCloseReason.CLOSED_BY_APP)

    internal fun close(reason: ConciergeCloseReason, resetGeneration: Long = stateRepository.state.value.resetGeneration) {
        if (_state.value.isClosed) return
        val hadActiveTurn = turnJob?.isActive == true
        val hadConversation = _messages.value.isNotEmpty() || hadActiveTurn
        val endedConversationId = conversationId
        OpenConciergeConversations.closed(this)
        ActiveConciergeDataHandoffForwarder.unregister(handoffForwarder)
        rootJob.cancel()
        service.cleanup()
        stateRepository.acknowledgeIdentityReset(
            resetParticipant, resetGeneration, hadConversation, endedConversationId, hadActiveTurn
        )
        stateRepository.unregisterResetParticipant(resetParticipant)
        turnJob = null
        activeTurn = null
        retryableQuery = null
        if (reason == ConciergeCloseReason.IDENTITY_RESET) {
            _messages.value = emptyList()
            interactionIds.clear()
            conversationId = null
        } else {
            stopStreaming()
        }
        Log.debug(ConciergeConstants.EXTENSION_NAME, TAG, "Conversation closed: $reason.")
        _state.value = ConciergeConversationState.Closed(reason)
    }

    /** One agent response. [query] is the typed message it answers, or null for a data handoff. */
    private class Turn(
        val agentMessageId: String,
        val generation: Long,
        val query: String? = null,
        val allowsBackgroundResend: Boolean = false
    ) {
        val text = StringBuilder()
        var interactionId: String? = null
        var responseStarted = false
        var completed = false
        var overlappedBackground = false
        var failure: String? = null
        var error = ConciergeError.UNKNOWN
    }

    // INTERRUPTED: the response failed while the app was in the background and will be sent again.
    private enum class TurnOutcome { DELIVERED, EMPTY, FAILED, INTERRUPTED }

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
        val rejection = when (admissionRefusal()) {
            null -> null
            ConciergeSendResult.IN_PROGRESS -> ConciergeDataHandoffRejectReason.CHAT_IN_PROGRESS
            else -> ConciergeDataHandoffRejectReason.NO_ACTIVE_SESSION
        }
        if (rejection != null) {
            request.complete(DataHandoffDeliveryResult.Failed(rejection))
            return
        }

        retryableQuery = null
        val snapshot = stateRepository.captureXDMContext()
        val turn = Turn(newId(), snapshot.generation)
        request.event.localMessage?.let { text ->
            _messages.update { it + ConciergeMessage(newId(), ConciergeMessage.Role.APP, text) }
        }
        _state.value = ConciergeConversationState.Responding
        handoff = request
        val job = scope.launch { request.complete(runDataHandoff(turn, snapshot, request.event)) }
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
                TurnOutcome.FAILED, TurnOutcome.INTERRUPTED ->
                    DataHandoffDeliveryResult.Failed(ConciergeDataHandoffRejectReason.DELIVERY_FAILED)
            }
        }
    } catch (e: DataHandoffTimeoutException) {
        failTurn(turn, e.message ?: "Data handoff timed out", ConciergeError.TIMEOUT)
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
                errorDetail != null -> failTurn(turn, errorDetail!!, ConciergeError.INVALID_RESPONSE_DATA)
                hasResponse(turn) -> finishTurn()
                else -> failTurn(
                    turn, "Conversation ended with no response content", ConciergeError.INVALID_RESPONSE_DATA,
                    TurnOutcome.EMPTY
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.toConciergeError()
            // A connection dropped after the full response arrived leaves nothing to recover.
            return if (turn.completed && (error == ConciergeError.UNREACHABLE || error == ConciergeError.TIMEOUT) &&
                hasResponse(turn)
            ) {
                finishTurn()
            } else {
                failTurn(turn, "Failed to process response: ${e.message}", error)
            }
        }
    }

    // An HTTP status error is a server rejection, not a dropped connection.
    private fun Exception.toConciergeError() = when (this) {
        is ConciergeHttpStatusException -> ConciergeError.INVALID_RESPONSE_DATA
        is SocketTimeoutException -> ConciergeError.TIMEOUT
        is IOException -> ConciergeError.UNREACHABLE
        else -> ConciergeError.UNKNOWN
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
                    linkHints = response.linkHints.map { ConciergeLinkHint(it.href, it.kind) },
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
        _state.value = ConciergeConversationState.Idle
        return TurnOutcome.DELIVERED
    }

    private fun failTurn(
        turn: Turn,
        detail: String,
        error: ConciergeError,
        outcome: TurnOutcome = TurnOutcome.FAILED
    ): TurnOutcome {
        _messages.update { messages -> messages.filterNot { it.id == turn.agentMessageId } }
        interactionIds.remove(turn.agentMessageId)
        val interruptedInBackground = turn.allowsBackgroundResend && turn.overlappedBackground && !turn.completed &&
            (error == ConciergeError.UNREACHABLE || error == ConciergeError.TIMEOUT)
        if (interruptedInBackground) {
            Log.debug(ConciergeConstants.EXTENSION_NAME, TAG, "$detail while in the background. Resending in the foreground.")
            turn.failure = detail
            turn.error = error
            return TurnOutcome.INTERRUPTED
        }
        reportFailure(turn, detail, error)
        return outcome
    }

    private fun reportFailure(turn: Turn, detail: String, error: ConciergeError) {
        Log.error(ConciergeConstants.EXTENSION_NAME, TAG, detail)
        report(ConciergeTrackingEvent.ErrorOccurred(detail))
        retryableQuery = turn.query
        _state.value = ConciergeConversationState.Failed(error)
    }

    // An identity reset ends the conversation: its messages belonged to the previous identity.
    private fun observeIdentityReset(state: ConciergeState) {
        if (state.resetGeneration == observedResetGeneration) return
        observedResetGeneration = state.resetGeneration
        close(ConciergeCloseReason.IDENTITY_RESET, state.resetGeneration)
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

/** The open [ConciergeConversation] for each set of surfaces. */
internal object OpenConciergeConversations {
    private val open = mutableMapOf<Set<String>, WeakReference<ConciergeConversation>>()

    /** Records [conversation] as open, and returns the conversation it replaces, if any. */
    @Synchronized
    fun open(conversation: ConciergeConversation): ConciergeConversation? =
        open.put(conversation.surfaces.toSet(), WeakReference(conversation))?.get()?.takeIf { it !== conversation }

    @Synchronized
    fun closed(conversation: ConciergeConversation) {
        val key = conversation.surfaces.toSet()
        if (open[key]?.get() === conversation) open.remove(key)
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
