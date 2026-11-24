/*
  Copyright 2026 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

package com.adobe.marketing.mobile.concierge

import com.adobe.marketing.mobile.Extension
import com.adobe.marketing.mobile.concierge.cards.ConciergeCardRenderers
import com.adobe.marketing.mobile.concierge.ui.chat.ConciergeChat
import com.adobe.marketing.mobile.concierge.ui.chat.ConciergeChatView
import com.adobe.marketing.mobile.services.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Public class containing APIs for the Brand Concierge extension. */
object Concierge {

    /** Reference to the Concierge Extension class used for registration via `MobileCore.registerExtensions`. */
    @JvmField
    val EXTENSION: Class<out Extension> = ConciergeExtension::class.java

    /** Returns the version of the Brand Concierge extension. */
    @JvmStatic
    fun extensionVersion(): String = ConciergeConstants.VERSION

    /**
     * Starts a conversation with no UI, for apps that draw the chat themselves. See
     * [ConciergeConversation].
     *
     * Call from a coroutine on the main thread, after the extension is registered. Waits up to
     * five seconds for the Brand Concierge configuration and the Experience Cloud ID, then
     * returns the conversation.
     *
     * [surfaces] belong to this conversation. They don't change the surfaces the built-in chat UI
     * uses, and other conversations can use different surfaces. Starting a conversation closes the
     * open one on the same surfaces with [ConciergeCloseReason.REPLACED].
     *
     * @param surfaces the surface URLs that identify the Brand Concierge experience.
     * @throws IllegalArgumentException if [surfaces] has no non-blank surface.
     * @throws ConciergeConfigurationException if the configuration or the Experience Cloud ID
     * isn't ready in time.
     */
    suspend fun conversation(surfaces: List<String>): ConciergeConversation =
        conversation(surfaces, ConciergeStateRepository.instance, ConciergeSessionManager.instance, CONFIGURATION_TIMEOUT_MS) { validSurfaces, sessionId ->
            val inBackground = withContext(Dispatchers.Main.immediate) { AppBackgroundState.inBackground }
            ConciergeConversation(validSurfaces, sessionId, appInBackground = inBackground)
        }

    internal suspend fun conversation(
        surfaces: List<String>,
        repository: ConciergeStateRepository,
        sessionManager: ConciergeSessionManager,
        timeoutMillis: Long,
        create: suspend (surfaces: List<String>, sessionId: String) -> ConciergeConversation
    ): ConciergeConversation {
        val validSurfaces = surfaces.filter { it.isNotBlank() }
        require(validSurfaces.isNotEmpty()) { "conversation() requires at least one non-blank surface." }
        withTimeoutOrNull(timeoutMillis) {
            repository.state.first { it.configurationReady && it.experienceCloudId != null && !it.resetInProgress }
        } ?: run {
            val state = repository.state.value
            val missing = when {
                !state.configurationReady -> "the Brand Concierge configuration"
                state.experienceCloudId == null -> "the Experience Cloud ID"
                else -> "the end of the identity reset"
            }
            Log.warning(ConciergeConstants.EXTENSION_NAME, "Concierge", "conversation() failed: $missing is not ready.")
            throw ConciergeConfigurationException("Concierge isn't ready: waiting for $missing.")
        }
        val sessionId = withContext(Dispatchers.IO) { sessionManager.getSessionId() }
        return create(validSurfaces, sessionId)
    }

    private const val CONFIGURATION_TIMEOUT_MS = 5_000L

    /**
     * Enables tracking of user interactions with the concierge chat interface. This allows the extension to collect data on user behavior and interactions, which can be used for analytics and improving the concierge experience.
     *
     */
    @JvmStatic
    fun setEdgeTrackingEnabled(enabled: Boolean) {
        ConciergeEventTracker.enableTracking(enabled)
    }

    /**
     * Registers the provider the SDK consults for an authentication token before building each
     * conversation turn, for both chat and feedback requests.
     *
     * Pass null to clear a previously registered provider. Setting a provider replaces any
     * previously registered one.
     *
     * @param provider the token provider, or null to clear.
     * @param timeoutMillis how long to wait for [provider] before sending the turn without a
     * token. Defaults to 3000ms (3 seconds); clamped range rather than rejected if out of bounds.
     */
    @JvmStatic
    @JvmOverloads
    fun setAuthTokenProvider(
        provider: ConciergeAuthTokenProvider?,
        timeoutMillis: Long = ConciergeAuthTokenHolder.DEFAULT_PROVIDE_TOKEN_TIMEOUT_MS
    ) {
        ConciergeAuthTokenHolder.setProvider(provider, timeoutMillis)
    }

    /**
     * Renders `custom` response elements whose `rendererId` exactly matches [rendererId] with
     * [renderer], inline in the transcript. Registering a `rendererId` again replaces its renderer
     * for cards activated afterwards. Other element types keep their built-in rendering. Custom
     * elements without a valid registered `rendererId`, or rejected during activation, are omitted
     * and logged.
     *
     * Register once at app start; renderers are held for the life of the process, so they must not
     * capture an `Activity` or a `View`. Cards talk back to Brand Concierge with [sendDataHandoff]
     * and [updateXDMContext].
     *
     * @throws IllegalArgumentException if [rendererId] is blank.
     */
    @JvmStatic
    fun registerCardRenderer(rendererId: String, renderer: ConciergeCardRenderer) {
        ConciergeCardRenderers.register(rendererId, renderer)
    }

    /**
     * Applies an RFC 7396 JSON Merge Patch to XDM context included in every conversational turn.
     *
     * Context applies to normal chat messages and [sendDataHandoff] requests, but not feedback.
     * Nested maps are recursively merged; lists and scalar values replace existing values. A null
     * object value removes the matching key at that level, for example:
     * `mapOf("fan" to mapOf("seatSection" to null))` removes `fan.seatSection`.
     * Nulls inside lists are retained as JSON null values.
     *
     * The context is held in memory for the current Concierge conversation session. It may be set
     * before the first chat request without starting the session's inactivity clock. Each update
     * checks for an existing valid session without creating or refreshing one. When no valid
     * session exists, fresh context is held pending and adopted by the next request's session.
     * Stale context from an expired session is cleared before applying a fresh patch. There is no
     * separate reset API; use null-valued patch entries to remove specific values.
     *
     * @param fields a JSON-safe object to merge into the held XDM context. The top-level
     * `identityMap` key is reserved for the SDK.
     * @throws IllegalArgumentException if a value is not JSON-compatible, exceeds 20 nesting
     * levels below a top-level field value (including cyclic input), or `identityMap` is used.
     */
    @JvmStatic
    fun updateXDMContext(fields: Map<String, Any?>) {
        ConciergeStateRepository.instance.updateXDMContext(fields)
    }

    /**
     * Hands data to the SDK to forward toward the Brand Concierge agent pipeline,
     * outside of normal user-typed chat.
     *
     * Keep a configured [ConciergeChat] or [ConciergeChatView] rendered, or a [ConciergeConversation]
     * open, while calling this API. The active chat session provides both routing surfaces and the
     * transcript that renders the handoff response. If more than one is active, the most recently
     * opened one receives the handoff.
     *
     * @param routingHint a keyword the end user never sees, consumed by Brand Concierge's
     * phrase-based router (e.g. "successful-checkout"). Defaults to an empty string, which
     * forwards an empty service query for callers whose XDM fields already determine routing.
     * @param xdmFields arbitrary XDM data merged into the root of the outbound XDM object; the
     * SDK does not interpret its contents. Must be non-empty and JSON-safe (null is allowed inside
     * lists), and must not use `identityMap` (or any other SDK-reserved top-level XDM key).
     * @param localMessage text to render in chat when this handoff starts, distinct from the data
     * forwarded to Brand Concierge. The handoff is rejected with [ConciergeDataHandoffRejectReason.CHAT_IN_PROGRESS]
     * when a chat turn or another handoff is active or waiting; callers can retry after it completes.
     * @param completion invoked exactly once with the outcome, on a background thread.
     * `accepted == true` confirms the service response completed and rendered successfully.
     */
    @JvmStatic
    @JvmOverloads
    fun sendDataHandoff(
        routingHint: String = "",
        xdmFields: Map<String, Any>,
        localMessage: String? = null,
        completion: ConciergeDataHandoffCallback? = null
    ) {
        ConciergeDataHandoffSender.send(routingHint, xdmFields, localMessage, completion)
    }
}
