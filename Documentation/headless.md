# Headless conversations

`Concierge.conversation()` starts a Brand Concierge conversation with no UI. Use it when the app draws its own chat. The conversation sends messages, streams responses, sends feedback and reports analytics. The app draws every message and decides what each tap does.

A headless conversation is part of the Brand Concierge extension. It uses the same setup as the built-in chat UI and shares its state:

- The configuration, and the session ID.
- Identities from Edge Identity, and consent.
- The auth token from `Concierge.setAuthTokenProvider`.
- The XDM context from `Concierge.updateXDMContext`.
- Analytics settings from `Concierge.setEdgeTrackingEnabled`.

Set up the extension as described in the [Implementation Guide](implementation-guide.md#configuration). No other dependency is needed.

## Start a conversation

```kotlin
class ChatViewModel : ViewModel() {
    val conversation = Concierge.conversation(listOf("web://example.com/index.html"))

    init {
        conversation.track(ConciergeInteraction.Opened)
    }

    override fun onCleared() {
        conversation.track(ConciergeInteraction.Closed)
        conversation.close()
    }
}
```

`Concierge.conversation(surfaces)` returns a `ConciergeConversation`. Create it and call its methods on the main thread. Call `close()` when you no longer need it. After `close()`, the conversation ignores new messages.

The surfaces are shared with the built-in chat UI. If both set surfaces, the last ones set are used for every request.

## Send messages

```kotlin
conversation.send("What running shoes do you have?")
```

`send` adds a user message, then streams the agent's reply. Collect `messages` and `state` to draw the chat:

```kotlin
val messages by conversation.messages.collectAsStateWithLifecycle()
val state by conversation.state.collectAsStateWithLifecycle()
```

`messages` holds every `ConciergeMessage`, oldest first:

| Property | Meaning |
| --- | --- |
| `id` | Identifies the message. Use it as a list key and for feedback. |
| `role` | `USER` or `AGENT`. |
| `text` | The message text, which can contain Markdown. While `isStreaming` is true, it holds the text received so far. |
| `isStreaming` | True while the agent is still writing this message. |
| `elements` | Product cards, CTA buttons and other elements, in response order. Set when the response completes. |
| `sources` | Citations. `startIndex` and `endIndex` locate the cited text. |
| `promptSuggestions` | Follow-up prompts the user can send. |
| `feedbackEnabled` | True when the message can receive feedback. |
| `feedback` | The rating sent for this message, or null. |

`state` is one of:

| State | Meaning |
| --- | --- |
| `IDLE` | Ready to send. |
| `RESPONDING` | Waiting for or receiving a reply. Show a loading indicator until the agent message appears. |
| `FAILED` | The last reply failed. Its agent message is removed and the user message stays. The user can send again. |
| `CLOSED` | `close()` was called. |

Only one reply runs at a time. `send` ignores a message while the state is `RESPONDING`; messages are not queued. Call `cancel()` to stop a reply. The text received so far stays in `messages`.

## Elements

Each agent message's `elements` are `ConciergeElement` values:

| Element | Contents |
| --- | --- |
| `ProductCard` | Product name, descriptions, page and image URLs, price, was-price, badge, and primary and secondary `ConciergeAction` buttons. |
| `CtaButton` | A label and URL. |
| `Custom` | A `custom` element with a `rendererId`, for your own renderer. |
| `Unknown` | Any other element. |

Every element keeps the element exactly as received in `raw`, for fields the types do not model.

## Feedback

When `feedbackEnabled` is true, send the user's rating with the message ID:

```kotlin
conversation.sendFeedback(message.id, ConciergeFeedback(ConciergeFeedback.Rating.POSITIVE, categories = listOf("Helpful")))
```

The message's `feedback` changes to the rating. Like the built-in UI, a response is not eligible for feedback when it has CTA buttons.

## Analytics

The conversation reports its own events, such as a submitted query and a started or completed response. Report the user's taps with `track`. Each interaction sends the same events as the built-in UI:

| Interaction | When |
| --- | --- |
| `Opened`, `Closed` | The chat is shown or hidden. `Closed` reports the time since `Opened`. |
| `PromptSuggestionClicked` | A prompt suggestion is tapped. |
| `CtaClicked` | A CTA button is tapped. |
| `ProductCardClicked` | A product card is tapped. |
| `ProductActionClicked` | A product card button is tapped. |
| `SourceClicked` | A citation is tapped. |
| `LinkClicked` | A link in the message text is tapped. |

`track` only reports. Your app opens the link or sends the suggestion:

```kotlin
fun onInteraction(interaction: ConciergeInteraction) {
    conversation.track(interaction)
    when (interaction) {
        is ConciergeInteraction.PromptSuggestionClicked -> conversation.send(interaction.suggestion)
        is ConciergeInteraction.CtaClicked -> openLink(interaction.url)
        is ConciergeInteraction.ProductCardClicked -> interaction.card.productPageUrl?.let(::openLink)
        is ConciergeInteraction.ProductActionClicked -> interaction.action.url?.let(::openLink)
        is ConciergeInteraction.SourceClicked -> openLink(interaction.source.url)
        is ConciergeInteraction.LinkClicked -> openLink(interaction.url)
        else -> Unit
    }
}
```

Events are forwarded to Edge only after `Concierge.setEdgeTrackingEnabled(true)`. Tracking is off by default.

Links come from the service. Before opening one, check its scheme. See [Links](headless-styling.md#links).

## Identity reset

When identities are reset, the conversation clears its messages and stops any reply in progress, like the built-in UI. Messages sent before the extension is ready again are ignored.

## Data handoff

While a conversation is open, [`Concierge.sendDataHandoff`](implementation-guide.md#data-handoff) delivers to it. There is no separate headless API:

```kotlin
Concierge.sendDataHandoff(
    routingHint = "successful-checkout",
    xdmFields = mapOf("commerce" to mapOf("order" to mapOf("purchaseID" to orderId))),
    localMessage = "Your order is confirmed!"
) { accepted, rejectReason -> }
```

- `localMessage`, if set, is added to `messages` as an `AGENT` message, because the user didn't type it. The response then streams in like a reply to `send`, and `state` is `RESPONDING` until it finishes.
- The handoff is rejected with `CHAT_IN_PROGRESS` while a reply is in progress, and with `NO_ACTIVE_SESSION` after `close` or during an identity reset.
- If the handoff fails or times out, the response is removed, `localMessage` stays, and `state` is `FAILED`. `cancel` stops it and reports `DELIVERY_FAILED`.
- Only one chat receives handoffs: the most recently opened conversation, `ConciergeChat` or `ConciergeChatView`.

## Not yet supported

- Link hints, which tag links in the text as phone or store links, are not in `ConciergeMessage`.
- Messages are not queued or resent in the background.

## Sample

The test app's **Headless Chat** screen (`HeadlessChatActivity`) is a complete example. It streams replies, shows product cards in a paged carousel or a two-column grid with images loaded by [Coil](https://coil-kt.github.io/coil/), and shows CTAs, sources and suggestions. It also sends feedback, reports taps, stops replies and starts new chats. Tapping a product's primary CTA (for example **Buy now**) simulates a completed checkout and calls `Concierge.sendDataHandoff`, so the agent replies in the same chat.

To style a headless chat without a theme JSON, see [Styling a headless UI](headless-styling.md).
