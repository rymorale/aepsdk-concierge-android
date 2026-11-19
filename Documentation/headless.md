# Headless conversation client

The `concierge-headless` module is a small Kotlin client for the Brand Concierge conversation API. Use it when the app draws its own chat UI. It has no AEP SDK dependency and no UI. Its request, response and event types mirror the published conversation API.

The app owns everything that the extension would otherwise manage:

- The Edge Network server, datastream ID and optional region.
- The session ID. Reuse it for every turn of one conversation.
- The XDM identity map, for example the ECID from Edge Identity.
- The consent state.
- Any auth token.

The module is not published yet. Inside this repository, depend on it with `implementation(project(":concierge-headless"))`.

## Usage

```kotlin
val client = ConciergeClient(ConciergeConfig(server = "edge.adobedc.net", datastreamId = "<datastream ID>"))

val request = ConversationRequest(
    sessionId = sessionId,
    surfaces = listOf("web://example.com/index.html"),
    identityMap = mapOf("ECID" to listOf(mapOf("id" to ecid, "primary" to true))),
    consent = Consent.IN,
    message = "What running shoes do you have?"
)

scope.launch {
    try {
        client.converse(request).accumulate().collect { event ->
            render(event.response)
        }
    } catch (e: ConciergeException) {
        showError(e.message)
    }
}
```

`converse` returns a cold `Flow<ConversationEvent>`. Each collection sends one request. The flow completes after the first `COMPLETED` or `ERROR` event, or when the stream ends. Network work runs on `Dispatchers.IO`.

`accumulate()` turns the raw events into snapshots that each carry the full text so far:

- In-progress text is appended. A snapshot is emitted once there is visible text.
- A `COMPLETED` event that has text or elements replaces the streamed text. Otherwise it is emitted with the streamed text.
- An `ERROR` event throws `ConciergeException`.

Without `accumulate()`, an `IN_PROGRESS` event's `response.message` holds only the newly streamed text.

### Feedback

When `response.feedbackEligible` is true, rate the response with its `interactionId` and `conversationId`:

```kotlin
client.sendFeedback(
    FeedbackRequest(sessionId, identityMap, Consent.IN, event.interactionId!!, event.conversationId, FeedbackRating.THUMBS_UP)
)
```

### Data handoff

To send application data without a chat message, leave `message` empty and pass XDM fields in `xdm`. They are merged into the event's `xdm`. Pass the identity map through `identityMap`, not `xdm`.

## Errors and cancellation

Every failure is a `ConciergeException`:

| Failure | Message | `statusCode` |
| --- | --- | --- |
| Non-2xx response | `The service returned HTTP <code>: <body>` (up to 1,000 characters of body) | The HTTP status |
| Connection failure | `Could not reach the Brand Concierge service.` | `null` |
| Stream read failure | `The response stream failed.` | `null` |
| `ERROR` event, through `accumulate()` | The event's message, or `The service returned an error.` | `null` |

Cancelling the collecting coroutine closes the connection, even while a read is blocked.

Invalid arguments throw `IllegalArgumentException` when the config or request is built. For example, a blank session ID, no surfaces, or neither a message nor XDM data.

## Wire format

Both calls `POST` to:

```
https://{server}/brand-concierge[/{region}]/conversations?configId={datastreamId}&sessionId={sessionId}&requestId={uuid}
```

A conversation request sends `Accept: text/event-stream` and reads the server-sent event stream. A feedback request sends JSON and ignores the response body.

### Conversation request

| Field | Source |
| --- | --- |
| `events[0].meta.consent.state` | `consent` (`in`, `out` or `pending`) |
| `events[0].query.conversation.surfaces` | `surfaces` |
| `events[0].query.conversation.message` | `message` |
| `events[0].query.conversation.data` | `{ "type": "auth", "payload": { "token": authToken } }`, when `authToken` is set |
| `events[0].xdm.identityMap` | `identityMap` |
| `events[0].xdm.*` | `xdm` |

### Feedback request

| Field | Source |
| --- | --- |
| `events[0].xdm.conversation.feedback.rating.classification` | `Thumbs Up` or `Thumbs Down` |
| `events[0].xdm.conversation.feedback.rating.score` | `1` or `0` |
| `events[0].xdm.conversation.feedback.rating.reasons` | `reasons` |
| `events[0].xdm.conversation.feedback.raw` | `[{ "text": notes, "purpose": "user input" }]`, when `notes` is not blank |
| `events[0].xdm.conversation.turnID` | `interactionId` |
| `events[0].xdm.conversation.conversationID` | `conversationId`, when set |
| `events[0].xdm.eventType` | `conversation.feedback` |
| `events[0].xdm.implementationDetails.version` | `headless+<client version>` |

The body also carries `identityMap`, consent, a UTC `timestamp` and `placeContext` with the device's local time and offset.

### Response

Only `handle` entries of type `brand-concierge:conversation` are read. Each entry in their `payload` becomes one `ConversationEvent`. In-progress payloads with no text are skipped.

| `ConversationEvent` / `ConversationResponse` | Payload field |
| --- | --- |
| `state` | `state` (`in-progress`, `completed`, `error`) |
| `conversationId` | `conversationId` |
| `interactionId` | `interactionId` |
| `message` | `response.message` |
| `promptSuggestions` | `response.promptSuggestions` |
| `multimodalElements` | `response.multimodalElements.elements` |
| `sources` | `response.sources`. Entries need a `title` and `url`. |
| `linkHints` | `response.linkHints`. Entries need a `kind` and `href`. |
| `feedbackEligible` | `response.feedback.eligible` |

Elements are classified the same way as the extension does:

- `ctaButton` with `entity_info.primary.text` and `url` is a `CtaButton`.
- `custom` with a `rendererId` is a `Custom` element for an app-defined renderer.
- Any other element with an `id` is a `ProductCard`. Card fields are read from `entity_info` first, then from the element.
- Everything else is `Unknown`.

Every element keeps the original JSON in `raw`, for fields the types do not model.

## Sample

The test app's **Headless Chat** screen (`HeadlessChatActivity`) is a complete example. It streams turns, renders product cards (images loaded with [Coil](https://coil-kt.github.io/coil/), several cards in a paged carousel), CTAs, sources and suggestions, sends feedback and starts new sessions.

To style a headless chat without a theme JSON, see [Styling a headless UI](headless-styling.md).
