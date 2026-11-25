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
    var conversation by mutableStateOf<ConciergeConversation?>(null)
        private set

    init {
        viewModelScope.launch {
            try {
                conversation = Concierge.conversation(listOf("web://example.com/index.html"))
                    .also { it.track(ConciergeInteraction.Opened) }
            } catch (e: ConciergeConfigurationException) {
                // The extension isn't configured, or there's no Experience Cloud ID yet.
            }
        }
    }

    override fun onCleared() {
        conversation?.track(ConciergeInteraction.Closed)
        conversation?.close()
    }
}
```

`Concierge.conversation(surfaces)` is a `suspend` function that returns a `ConciergeConversation`. Call it from a coroutine on the main thread, and call the conversation's methods on the main thread. Call `close()` when you no longer need it. After `close()`, the conversation ignores new messages.

`Concierge.conversation` waits up to five seconds for the extension to be ready: the Brand Concierge configuration has been received, Edge Identity has an Experience Cloud ID, and no identity reset is in progress. If any of these is still missing, it throws `ConciergeConfigurationException`. It throws `IllegalArgumentException` if `surfaces` has no non-blank surface.

The surfaces belong to the conversation. They don't change the surfaces of the built-in chat UI, and two conversations can use different surfaces. Only one conversation is open per set of surfaces: starting another conversation with the same surfaces closes the open one with `REPLACED`.

The conversation uses the session that was current when it started; `sessionId` returns it. When that session expires, the conversation closes with `SESSION_EXPIRED` the next time you send a message, and `send` returns `CLOSED`. Start a new conversation to continue.

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
| `role` | `USER`, `AGENT`, or `APP` for text the app added, such as a data handoff's `localMessage`. |
| `text` | The message text, which can contain Markdown. While `isStreaming` is true, it holds the text received so far. |
| `isStreaming` | True while the agent is still writing this message. |
| `elements` | Product cards, CTA buttons and other elements, in response order. Set when the response completes. |
| `sources` | Citations. `startIndex` and `endIndex` locate the cited text. |
| `linkHints` | Links in `text` that the agent tagged with a `kind`, such as `phone` or `store`. Each `href` matches a link's URL in the text, so you can mark it, for example with an icon. Set when the response completes. |
| `promptSuggestions` | Follow-up prompts the user can send. |
| `feedbackEnabled` | True when the message can receive feedback. |
| `feedback` | The rating sent for this message, or null. |

`state` is one of:

| State | Meaning |
| --- | --- |
| `Idle` | Ready to send. |
| `Responding` | Waiting for or receiving a reply. Show a loading indicator until the agent message appears. |
| `Failed(error)` | The last reply failed. Its agent message is removed and the user message stays. The user can send again, or call `retry()`. |
| `Closed(reason)` | The conversation has ended. Start a new one to continue. |

`Failed.error` is a `ConciergeError`:

| Error | Meaning |
| --- | --- |
| `Unreachable` | The service couldn't be reached, or the connection dropped. |
| `Timeout` | The request timed out. |
| `InvalidResponseData` | The service returned an error state, or a response with nothing to show. |
| `HttpStatus(code)` | The service answered with a non-2xx HTTP status, such as 401 (check the auth token), 429 (too many requests) or 500. |
| `Unknown` | Any other error. |

Every failure is also reported as an `ErrorOccurred` event with the detail, including the HTTP status code.

`Closed.reason` is a `ConciergeCloseReason`:

| Reason | Meaning |
| --- | --- |
| `CLOSED_BY_APP` | `close()` was called. |
| `REPLACED` | Another conversation started with the same surfaces. |
| `SESSION_EXPIRED` | The conversation's session expired. |
| `IDENTITY_RESET` | Identities were reset. The messages are cleared. |

Only one reply runs at a time. `send` ignores a message while the state is `Responding`; messages are not queued. Call `cancel()` to stop a reply. The text received so far stays in `messages`.

After a failure, `retry()` sends the failed message again without adding another user message. A failed data handoff can't be retried.

If the app goes to the background during a reply and the reply then fails with `Unreachable` or `Timeout`, the conversation sends the message once more when the app returns to the foreground, unless the session has expired. If that also fails, `state` is `Failed`.

`send` returns a `ConciergeSendResult`. You can ignore it, or use it to keep the user's text when a message wasn't sent:

| Result | Meaning |
| --- | --- |
| `ACCEPTED` | The message was sent. |
| `BLANK` | The message is blank. |
| `IN_PROGRESS` | A reply or data handoff is in progress. |
| `CLOSED` | The conversation is closed. |
| `RESET_PENDING` | An identity reset is in progress. Send again after it finishes. |
| `NOTHING_TO_RETRY` | Returned only by `retry()`: the last reply didn't fail, or it was a data handoff. |

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
| `LinkHintClicked` | A link with a link hint, or your marker for it, is tapped. |

`track` only reports, and is ignored after the conversation closes. Your app opens the link or sends the suggestion:

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
        is ConciergeInteraction.LinkHintClicked -> openLink(interaction.hint.href)
        else -> Unit
    }
}
```

Events are forwarded to Edge only after `Concierge.setEdgeTrackingEnabled(true)`. Tracking is off by default.

Links come from the service. Before opening one, check its scheme. See [Links](headless-styling.md#links).

## Identity reset

When identities are reset, the conversation stops any reply in progress, clears its messages and closes with `IDENTITY_RESET`. Start a new conversation; `Concierge.conversation` waits for the reset to finish.

## Data handoff

While a conversation is open, [`Concierge.sendDataHandoff`](implementation-guide.md#data-handoff) delivers to it. There is no separate headless API:

```kotlin
Concierge.sendDataHandoff(
    routingHint = "successful-checkout",
    xdmFields = mapOf("commerce" to mapOf("order" to mapOf("purchaseID" to orderId))),
    localMessage = "Your order is confirmed!"
) { accepted, rejectReason -> }
```

- `localMessage`, if set, is added to `messages` as an `APP` message, because the user didn't type it. The response then streams in like a reply to `send`, and `state` is `Responding` until it finishes.
- The handoff is rejected with `CHAT_IN_PROGRESS` while a reply is in progress, and with `NO_ACTIVE_SESSION` after `close` or during an identity reset.
- If the handoff fails or times out, the response is removed, `localMessage` stays, and `state` is `Failed`. `cancel` stops it and reports `DELIVERY_FAILED`.
- Only one chat receives handoffs: the conversation, `ConciergeChat` or `ConciergeChatView` most recently opened or sent a message.

## Sample

The test app's **Headless Chat** screen (`HeadlessChatActivity`) is a complete example. It streams replies, shows product cards in a paged carousel or a two-column grid with images loaded by [Coil](https://coil-kt.github.io/coil/), and shows CTAs, sources and suggestions. It also sends feedback, reports taps, stops and retries replies, and starts new chats. Tapping a product's primary CTA (for example **Buy now**) simulates a completed checkout and calls `Concierge.sendDataHandoff`, so the agent replies in the same chat.

To style a headless chat without a theme JSON, see [Styling a headless UI](headless-styling.md).
