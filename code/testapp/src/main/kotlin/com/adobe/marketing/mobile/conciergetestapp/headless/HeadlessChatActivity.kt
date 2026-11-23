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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.adobe.marketing.mobile.concierge.Concierge
import com.adobe.marketing.mobile.concierge.ConciergeAction
import com.adobe.marketing.mobile.concierge.ConciergeConversation
import com.adobe.marketing.mobile.concierge.ConciergeConversationState
import com.adobe.marketing.mobile.concierge.ConciergeElement
import com.adobe.marketing.mobile.concierge.ConciergeFeedback
import com.adobe.marketing.mobile.concierge.ConciergeInteraction
import com.adobe.marketing.mobile.concierge.ConciergeMessage
import com.adobe.marketing.mobile.conciergetestapp.theme.ConciergeAppTheme
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "HeadlessChat"
private const val SURFACE =
    "web://brand-concierge-demo-stage.corp.ethos270-stage-va7.ethos.adobe.net/customer-pages/745F37C35E4B776E0A49421B@AdobeOrg/acom_m15/index.html"

// Brand Concierge routes the handoff by this keyword. Use the one your agent is configured for.
private const val CHECKOUT_ROUTING_HINT = "successful-checkout"
private val CARD_WIDTH = 240.dp
private val CARD_SPACING = 12.dp

/**
 * A chat UI drawn by the app on top of [ConciergeConversation]. The extension handles the session,
 * identity, consent and analytics; the app draws every message.
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

/** Keeps the conversation across configuration changes and closes it with the screen. */
class HeadlessChatViewModel : ViewModel() {

    var conversation by mutableStateOf(start())
        private set

    /** Replaces the conversation with an empty one. The Concierge session stays the same. */
    fun newChat() {
        end()
        conversation = start()
    }

    /**
     * Simulates a completed checkout for [card] and hands it to Brand Concierge, which replies in
     * the conversation. A real app would call this after its own checkout flow finishes.
     */
    fun buy(card: ConciergeElement.ProductCard) {
        val name = card.productName ?: card.id
        Concierge.sendDataHandoff(
            routingHint = CHECKOUT_ROUTING_HINT,
            xdmFields = mapOf(
                "commerce" to mapOf(
                    "purchases" to mapOf("value" to 1),
                    "order" to mapOf("purchaseID" to UUID.randomUUID().toString())
                ),
                "productListItems" to listOf(mapOf("name" to name, "quantity" to 1))
            ),
            localMessage = "Your order for $name is confirmed!"
        ) { accepted, rejectReason ->
            Log.d(TAG, "Checkout handoff for $name: accepted=$accepted, rejectReason=$rejectReason")
        }
    }

    override fun onCleared() = end()

    private fun start() = Concierge.conversation(listOf(SURFACE)).also { it.track(ConciergeInteraction.Opened) }

    private fun end() {
        conversation.track(ConciergeInteraction.Closed)
        conversation.close()
    }
}

@Composable
private fun HeadlessChatScreen(viewModel: HeadlessChatViewModel) {
    val conversation = viewModel.conversation
    val messages by conversation.messages.collectAsStateWithLifecycle()
    val state by conversation.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current
    val open: (String?) -> Unit = { url -> url?.let { runCatching { uriHandler.openUri(it) } } }

    // The conversation only reports interactions; the app sends suggestions, opens links and
    // runs checkout. The primary product CTA (Buy now) checks out; other CTAs open their link.
    val onInteraction: (ConciergeInteraction) -> Unit = { interaction ->
        conversation.track(interaction)
        when (interaction) {
            is ConciergeInteraction.PromptSuggestionClicked -> conversation.send(interaction.suggestion)
            is ConciergeInteraction.CtaClicked -> open(interaction.url)
            is ConciergeInteraction.ProductCardClicked -> open(interaction.card.productPageUrl)
            is ConciergeInteraction.ProductActionClicked ->
                if (interaction.action == interaction.card.primary) viewModel.buy(interaction.card) else open(interaction.action.url)
            is ConciergeInteraction.SourceClicked -> open(interaction.source.url)
            is ConciergeInteraction.LinkClicked -> open(interaction.url)
            else -> Unit
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Headless chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Row(
                Modifier
                    .toggleable(value = grid, role = Role.Switch, onValueChange = { grid = it })
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Grid", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Switch(checked = grid, onCheckedChange = null)
            }
            TextButton(onClick = viewModel::newChat) { Text("New chat") }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageView(
                    message = message,
                    showSuggestions = message.id == messages.last().id && state == ConciergeConversationState.IDLE,
                    grid = grid,
                    onInteraction = onInteraction,
                    onRate = { conversation.sendFeedback(message.id, ConciergeFeedback(it)) }
                )
            }
            if (state == ConciergeConversationState.RESPONDING && messages.lastOrNull()?.isStreaming != true) {
                item { Text("…") }
            }
            if (state == ConciergeConversationState.FAILED) {
                item { Text("Something went wrong. Try again.", color = MaterialTheme.colorScheme.error) }
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
            if (state == ConciergeConversationState.RESPONDING) {
                OutlinedButton(onClick = conversation::cancel) { Text("Stop") }
            } else {
                Button(
                    onClick = {
                        conversation.send(input)
                        input = ""
                    },
                    enabled = input.isNotBlank()
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageView(
    message: ConciergeMessage,
    showSuggestions: Boolean,
    grid: Boolean,
    onInteraction: (ConciergeInteraction) -> Unit,
    onRate: (ConciergeFeedback.Rating) -> Unit
) {
    if (message.role == ConciergeMessage.Role.USER) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = message.text,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.text.isNotEmpty()) Text(message.text)
        val (products, others) = message.elements.partition { it is ConciergeElement.ProductCard }
        val cards = products.filterIsInstance<ConciergeElement.ProductCard>()
        if (cards.size > 1 && grid) {
            ProductGrid(cards, onInteraction)
        } else if (cards.size > 1) {
            ProductCarousel(cards, onInteraction)
        } else {
            products.forEach { ElementView(it, onInteraction) }
        }
        others.forEach { ElementView(it, onInteraction) }
        if (message.sources.isNotEmpty()) {
            Text("Sources", style = MaterialTheme.typography.labelMedium)
            message.sources.forEachIndexed { i, source ->
                Text(
                    text = "${source.citationNumber ?: (i + 1)}. ${source.title}",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onInteraction(ConciergeInteraction.SourceClicked(source)) }
                )
            }
        }
        if (message.feedbackEnabled) {
            Row {
                ConciergeFeedback.Rating.values().forEach { rating ->
                    val label = if (rating == ConciergeFeedback.Rating.POSITIVE) "👍" else "👎"
                    TextButton(onClick = { onRate(rating) }, enabled = message.feedback == null) {
                        Text(if (message.feedback == rating) "$label Sent" else label)
                    }
                }
            }
        }
        if (showSuggestions && message.promptSuggestions.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                message.promptSuggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = { onInteraction(ConciergeInteraction.PromptSuggestionClicked(suggestion)) },
                        label = { Text(suggestion) }
                    )
                }
            }
        }
    }
}


@Composable
private fun ElementView(element: ConciergeElement, onInteraction: (ConciergeInteraction) -> Unit) {
    when (element) {
        is ConciergeElement.ProductCard -> ProductCardView(element, onInteraction, Modifier.width(CARD_WIDTH))
        is ConciergeElement.CtaButton -> OutlinedButton(
            onClick = { onInteraction(ConciergeInteraction.CtaClicked(element.label, element.url)) }
        ) { Text(element.label) }
        is ConciergeElement.Custom ->
            Text("Custom element: ${element.rendererId}", style = MaterialTheme.typography.labelSmall)
        is ConciergeElement.Unknown ->
            Text("Unsupported element: ${element.type ?: "untyped"}", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Two or more product cards scroll sideways with previous and next arrows and page dots, like the
 * extension's default paged carousel.
 */
@Composable
private fun ProductCarousel(cards: List<ConciergeElement.ProductCard>, onInteraction: (ConciergeInteraction) -> Unit) {
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
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(CARD_SPACING)
        ) {
            cards.forEach { card ->
                ProductCardView(card, onInteraction, Modifier.width(CARD_WIDTH).fillMaxHeight())
            }
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
 * Two or more product cards as a catalog-style grid of compact tiles, two per row. Each row is as
 * tall as its tallest tile, so prices and CTAs line up across the row.
 */
@Composable
private fun ProductGrid(cards: List<ConciergeElement.ProductCard>, onInteraction: (ConciergeInteraction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(CARD_SPACING)) {
        cards.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(CARD_SPACING)
            ) {
                row.forEach { ProductTile(it, onInteraction, Modifier.weight(1f).fillMaxHeight()) }
                // Keeps a lone last tile at half width.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A compact grid tile: square image with an optional badge, name, price and was-price, then the
 * primary CTA. Tapping the tile opens the product page.
 */
@Composable
private fun ProductTile(
    card: ConciergeElement.ProductCard,
    onInteraction: (ConciergeInteraction) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier.clickable { onInteraction(ConciergeInteraction.ProductCardClicked(card)) }) {
        card.productImageUrl?.let { url ->
            ProductImage(url, card, Modifier.fillMaxWidth().aspectRatio(1f))
        }
        Column(Modifier.fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = card.productName ?: card.id,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // Pushes the price and CTA to the bottom so they line up across a row.
            Spacer(Modifier.weight(1f))
            card.productPrice?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            card.productWasPrice?.let {
                Text(
                    text = "was $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            card.primary?.takeIf { it.isUsable() }?.let { action ->
                Button(
                    onClick = { onInteraction(ConciergeInteraction.ProductActionClicked(card, action)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(action.text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The product image with its badge, if any, in the bottom-left corner. */
@Composable
private fun ProductImage(url: String, card: ConciergeElement.ProductCard, modifier: Modifier) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    Box {
        AsyncImage(
            model = url,
            contentDescription = card.productName,
            modifier = modifier,
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

/**
 * A product card in the style of the extension's `productDetail` cards: image with an optional
 * badge, name, description, price and was-price, then primary (filled) and secondary (outlined)
 * CTAs.
 */
@Composable
private fun ProductCardView(
    card: ConciergeElement.ProductCard,
    onInteraction: (ConciergeInteraction) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier.clickable { onInteraction(ConciergeInteraction.ProductCardClicked(card)) }) {
        card.productImageUrl?.let { url ->
            ProductImage(url, card, Modifier.fillMaxWidth().height(190.dp))
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
                    val onAction = { action: ConciergeAction ->
                        onInteraction(ConciergeInteraction.ProductActionClicked(card, action))
                    }
                    primary?.let {
                        Button(onClick = { onAction(it) }, modifier = ctaModifier, contentPadding = ctaPadding) {
                            Text(it.text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    secondary?.let {
                        OutlinedButton(onClick = { onAction(it) }, modifier = ctaModifier, contentPadding = ctaPadding) {
                            Text(it.text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Like the extension, a CTA needs both a label and a URL. */
private fun ConciergeAction.isUsable() = !text.isNullOrBlank() && !url.isNullOrBlank()
