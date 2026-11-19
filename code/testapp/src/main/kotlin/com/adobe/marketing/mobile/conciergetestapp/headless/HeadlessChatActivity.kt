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

package com.adobe.marketing.mobile.conciergetestapp.headless

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.adobe.marketing.mobile.AdobeCallbackWithError
import com.adobe.marketing.mobile.AdobeError
import com.adobe.marketing.mobile.concierge.headless.ConciergeClient
import com.adobe.marketing.mobile.concierge.headless.ConciergeConfig
import com.adobe.marketing.mobile.concierge.headless.ConciergeException
import com.adobe.marketing.mobile.concierge.headless.Consent
import com.adobe.marketing.mobile.concierge.headless.ConversationEvent
import com.adobe.marketing.mobile.concierge.headless.ConversationRequest
import com.adobe.marketing.mobile.concierge.headless.ConversationState
import com.adobe.marketing.mobile.concierge.headless.ElementAction
import com.adobe.marketing.mobile.concierge.headless.FeedbackRating
import com.adobe.marketing.mobile.concierge.headless.FeedbackRequest
import com.adobe.marketing.mobile.concierge.headless.MultimodalElement
import com.adobe.marketing.mobile.concierge.headless.accumulate
import com.adobe.marketing.mobile.conciergetestapp.theme.ConciergeAppTheme
import com.adobe.marketing.mobile.edge.identity.Identity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "HeadlessChat"
private const val SERVER = "edge-int.adobedc.net"
private const val DATASTREAM_ID = "9572b56a-d65e-474a-9815-c811d12df33d"
private const val SURFACE =
    "web://brand-concierge-demo-stage.corp.ethos270-stage-va7.ethos.adobe.net/customer-pages/745F37C35E4B776E0A49421B@AdobeOrg/acom_m15/index.html"
private val CARD_WIDTH = 240.dp
private val CARD_SPACING = 12.dp

/**
 * A chat UI built on the headless client. The app owns the UI, the session ID, the identity map
 * and consent. Only the ECID comes from the AEP SDK, through Edge Identity.
 */
class HeadlessChatActivity : ComponentActivity() {

    private val viewModel: HeadlessChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConciergeAppTheme {
                Surface(Modifier.fillMaxSize()) {
                    HeadlessChatScreen(viewModel)
                }
            }
        }
    }
}

data class Turn(
    val id: Long,
    val prompt: String,
    val reply: ConversationEvent? = null,
    val error: String? = null,
    val pending: Boolean = true,
    val rating: FeedbackRating? = null
)

class HeadlessChatViewModel : ViewModel() {

    private val client = ConciergeClient(ConciergeConfig(SERVER, DATASTREAM_ID))
    private var sessionId = UUID.randomUUID().toString()
    private var identityMap: Map<String, Any?>? = null
    private var nextId = 0L
    private var turnJob: Job? = null

    val turns = mutableStateListOf<Turn>()
    val isStreaming: Boolean get() = turns.any { it.pending }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || isStreaming) return
        val id = nextId++
        turns += Turn(id, message)
        turnJob = viewModelScope.launch {
            try {
                val request = ConversationRequest(sessionId, listOf(SURFACE), identityMap(), Consent.IN, message)
                client.converse(request).accumulate().collect { event -> update(id) { it.copy(reply = event) } }
            } catch (e: ConciergeException) {
                Log.w(TAG, "Turn failed", e)
                update(id) { it.copy(error = e.message) }
            } finally {
                update(id) { it.copy(pending = false) }
            }
        }
    }

    fun rate(turn: Turn, rating: FeedbackRating) {
        val reply = turn.reply ?: return
        val interactionId = reply.interactionId ?: return
        update(turn.id) { it.copy(rating = rating) }
        viewModelScope.launch {
            try {
                client.sendFeedback(
                    FeedbackRequest(sessionId, identityMap(), Consent.IN, interactionId, reply.conversationId, rating)
                )
            } catch (e: ConciergeException) {
                Log.w(TAG, "Feedback failed", e)
                update(turn.id) { it.copy(rating = null) }
            }
        }
    }

    fun newSession() {
        turnJob?.cancel()
        turns.clear()
        sessionId = UUID.randomUUID().toString()
    }

    private fun update(id: Long, change: (Turn) -> Turn) {
        val index = turns.indexOfFirst { it.id == id }
        if (index != -1) turns[index] = change(turns[index])
    }

    private suspend fun identityMap(): Map<String, Any?> = identityMap
        ?: fetchEcid()?.let { ecid ->
            mapOf("ECID" to listOf(mapOf("id" to ecid, "primary" to true))).also { identityMap = it }
        }
        ?: emptyMap()

    private suspend fun fetchEcid(): String? = suspendCancellableCoroutine { continuation ->
        Identity.getExperienceCloudId(object : AdobeCallbackWithError<String> {
            override fun call(ecid: String?) = continuation.resume(ecid?.takeIf { it.isNotEmpty() })
            override fun fail(error: AdobeError?) = continuation.resume(null)
        })
    }
}

@Composable
private fun HeadlessChatScreen(viewModel: HeadlessChatViewModel) {
    val turns = viewModel.turns
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Headless chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::newSession) { Text("New session") }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(turns, key = { it.id }) { turn ->
                TurnView(
                    turn = turn,
                    showSuggestions = turn.id == turns.last().id,
                    onSuggestion = viewModel::send,
                    onRate = { viewModel.rate(turn, it) }
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask something") },
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.send(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !viewModel.isStreaming
            ) { Text("Send") }
        }
    }
}

@Composable
private fun TurnView(
    turn: Turn,
    showSuggestions: Boolean,
    onSuggestion: (String) -> Unit,
    onRate: (FeedbackRating) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val open: (String?) -> Unit = { url -> url?.let { runCatching { uriHandler.openUri(it) } } }
    val reply = turn.reply

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = turn.prompt,
            modifier = Modifier
                .align(Alignment.End)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                .padding(12.dp)
        )
        if (reply == null && turn.pending) Text("…")
        if (reply != null) {
            val response = reply.response
            if (response.message.isNotEmpty()) Text(response.message)
            val (products, others) = response.multimodalElements.partition { it is MultimodalElement.ProductCard }
            if (products.size > 1) {
                ProductCarousel(products.filterIsInstance<MultimodalElement.ProductCard>(), open)
            } else {
                products.forEach { ElementView(it, open) }
            }
            others.forEach { ElementView(it, open) }
            if (response.sources.isNotEmpty()) {
                Text("Sources", style = MaterialTheme.typography.labelMedium)
                response.sources.forEachIndexed { i, source ->
                    Text(
                        text = "${source.citationNumber ?: (i + 1)}. ${source.title}",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { open(source.url) }
                    )
                }
            }
            if (!turn.pending && reply.state == ConversationState.COMPLETED && response.feedbackEligible) {
                Row {
                    FeedbackRating.values().forEach { rating ->
                        val label = if (rating == FeedbackRating.THUMBS_UP) "👍" else "👎"
                        TextButton(onClick = { onRate(rating) }, enabled = turn.rating == null) {
                            Text(if (turn.rating == rating) "$label Sent" else label)
                        }
                    }
                }
            }
            if (showSuggestions && !turn.pending && response.promptSuggestions.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    response.promptSuggestions.forEach { suggestion ->
                        SuggestionChip(onClick = { onSuggestion(suggestion) }, label = { Text(suggestion) })
                    }
                }
            }
        }
        turn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ElementView(element: MultimodalElement, open: (String?) -> Unit) {
    when (element) {
        is MultimodalElement.ProductCard -> ProductCardView(element, open, Modifier.width(CARD_WIDTH))
        is MultimodalElement.CtaButton -> OutlinedButton(onClick = { open(element.url) }) { Text(element.text) }
        is MultimodalElement.Custom ->
            Text("Custom element: ${element.rendererId}", style = MaterialTheme.typography.labelSmall)
        is MultimodalElement.Unknown ->
            Text("Unsupported element: ${element.type ?: "untyped"}", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Two or more product cards scroll sideways with previous and next arrows and page dots, like the
 * extension's default paged carousel.
 */
@Composable
private fun ProductCarousel(cards: List<MultimodalElement.ProductCard>, open: (String?) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stride = with(LocalDensity.current) { (CARD_WIDTH + CARD_SPACING).roundToPx() }
    // The last cards can't scroll to the start of the row, so the end of the row is the last page.
    val page = if (scroll.canScrollForward) (scroll.value + stride / 2) / stride else cards.lastIndex
    val scrollTo: (Int) -> Unit = { index -> scope.launch { scroll.animateScrollTo(index * stride) } }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // IntrinsicSize.Max makes the row as tall as its tallest card, and fillMaxHeight stretches
        // the others to match.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(CARD_SPACING)
        ) {
            cards.forEach { ProductCardView(it, open, Modifier.width(CARD_WIDTH).fillMaxHeight()) }
        }
        if (scroll.maxValue > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scrollTo(page - 1) }, enabled = scroll.canScrollBackward) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
                }
                cards.indices.forEach { index ->
                    val color = if (index == page) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .clickable { scrollTo(index) }
                            .padding(4.dp)
                            .size(8.dp)
                            .background(color, CircleShape)
                    )
                }
                IconButton(onClick = { scrollTo(page + 1) }, enabled = scroll.canScrollForward) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
                }
            }
        }
    }
}

/**
 * A product card in the style of the extension's `productDetail` cards: image with an optional
 * badge, name, description, price and was-price, then primary (filled) and secondary (outlined)
 * CTAs.
 */
@Composable
private fun ProductCardView(
    card: MultimodalElement.ProductCard,
    open: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier.clickable { open(card.productPageUrl ?: card.primary?.url) }) {
        card.productImageUrl?.let { url ->
            val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
            Box {
                AsyncImage(
                    model = url,
                    contentDescription = card.productName,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                    placeholder = placeholder,
                    error = placeholder,
                    contentScale = ContentScale.Crop,
                    onError = { Log.w(TAG, "Failed to load image: $url", it.result.throwable) }
                )
                card.productBadge?.let {
                    Text(
                        text = it,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Column(Modifier.fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = card.productName ?: card.id,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            (card.productDescription ?: card.description)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Pushes the price and CTAs to the bottom so they line up across a carousel.
            Spacer(Modifier.weight(1f))
            card.productPrice?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            card.productWasPrice?.let {
                Text(
                    text = "was $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val primary = card.primary?.takeIf { it.isUsable() }
            val secondary = card.secondary?.takeIf { it.isUsable() }
            if (primary != null || secondary != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Two CTAs share the row; a single CTA wraps its text.
                    val ctaModifier = if (primary != null && secondary != null) Modifier.weight(1f) else Modifier
                    val ctaPadding = PaddingValues(horizontal = 12.dp)
                    primary?.let {
                        Button(onClick = { open(it.url) }, modifier = ctaModifier, contentPadding = ctaPadding) {
                            Text(it.text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    secondary?.let {
                        OutlinedButton(onClick = { open(it.url) }, modifier = ctaModifier, contentPadding = ctaPadding) {
                            Text(it.text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Like the extension, a CTA needs both a label and a URL. */
private fun ElementAction.isUsable() = !text.isNullOrBlank() && !url.isNullOrBlank()
