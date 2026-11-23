# Styling a headless UI

A [headless conversation](headless.md) returns data, not UI. It doesn't use the theme JSON, `ConciergeThemeLoader` or `ConciergeTheme`. Your app draws every part of the chat, so it styles the chat the same way it styles the rest of the app: with your own Compose theme, or XML themes and styles.

This guide shows where each part of the theme JSON goes in a headless app, and which rendering work the extension did that your app now owns.

## Where theme JSON settings go

| Theme JSON key | In a headless app |
| --- | --- |
| `theme` (CSS variables) | Your theme: `MaterialTheme` color scheme, typography and shapes, plus your own tokens for chat-specific values. See [Theme tokens](#theme-tokens). |
| `behavior` | Your UI code. Each toggle becomes a choice in your composables. See [Behaviors](#behaviors). |
| `header` | Your screen's top bar. |
| `text`, `disclaimer` | String resources (`strings.xml`), so they localize with the rest of the app. |
| `arrays["welcome.examples"]` | Your empty-state prompts. Send a prompt's text with `conversation.send`. |
| `arrays["feedback.positive.options"]`, `arrays["feedback.negative.options"]` | Your feedback reasons. Send the selected ones as `ConciergeFeedback.categories`. |
| `assets` | Drawables, or your image loader for remote URLs. |
| `components` | Your own components. |
| `metadata` | Not needed. |
| Custom `--` keys (`cssVariables`) | Fields on your own theme object. |

If you want to keep a file-driven or server-driven theme, load and parse it yourself and map it into your theme object. A headless conversation does not read theme files.

## Define a chat theme

Put brand-wide values in `MaterialTheme`. Put values that only the chat uses in a small object provided through a `CompositionLocal`.

```kotlin
@Immutable
data class ChatColors(
    val userBubble: Color,
    val onUserBubble: Color,
    val agentText: Color,
    val link: Color,
    val citationBadge: Color,
    val onCitationBadge: Color
)

val LocalChatColors = staticCompositionLocalOf<ChatColors> { error("Wrap the chat in BrandChatTheme.") }

private val BrandFont = FontFamily(Font(R.font.brand_regular), Font(R.font.brand_bold, FontWeight.Bold))

private val LightChatColors = ChatColors(
    userBubble = Color(0xFFEDE7F6),
    onUserBubble = Color(0xFF1C1B1F),
    agentText = Color(0xFF1C1B1F),
    link = Color(0xFF5E35B1),
    citationBadge = Color(0xFFE0E0E0),
    onCitationBadge = Color(0xFF424242)
)

@Composable
fun BrandChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Color(0xFF5E35B1)),
        typography = Typography(bodyLarge = TextStyle(fontFamily = BrandFont, fontSize = 16.sp, lineHeight = 24.sp)),
        shapes = Shapes(medium = RoundedCornerShape(12.dp))
    ) {
        CompositionLocalProvider(LocalChatColors provides LightChatColors, content = content)
    }
}
```

Read the values in your chat composables with `MaterialTheme.colorScheme`, `MaterialTheme.typography` and `LocalChatColors.current`. For dark mode, choose a second `ChatColors` and color scheme from `isSystemInDarkTheme()`. Without a theme, the extension falls back to light or dark defaults that follow the device setting. A headless app needs to handle both modes itself.

For XML views, define the same values as theme attributes and styles, and apply them to your chat layouts.

## Theme tokens

The theme JSON tokens map to your theme as follows.

| Token group | Example tokens | In Compose |
| --- | --- | --- |
| Typography | `--font-family`, `--line-height-body`, `--message-font-size`, `--message-font-weight` | `MaterialTheme.typography`, for example `bodyLarge` for messages |
| Brand and surface | `--color-primary`, `--color-text`, `--main-container-background` | `colorScheme.primary`, `onBackground` and `background` |
| Messages | `--message-user-background`, `--message-user-text`, `--message-concierge-text`, `--message-concierge-link-color` | Chat colors such as `userBubble` and `link` |
| Buttons and CTAs | `--button-primary-background`, `--button-secondary-border`, `--cta-button-background-color` | `ButtonDefaults.buttonColors()`, `ButtonDefaults.outlinedButtonColors()` and a `BorderStroke` |
| Input | `--input-background`, `--input-outline-color`, `--input-send-icon-color` | `OutlinedTextFieldDefaults.colors()` and your send button's tint |
| Suggestions and welcome prompts | `--suggestion-background-color`, `--welcome-prompt-text-color` | Chip colors |
| Citations | `--citations-background-color`, `--citations-text-color` | Citation badge colors |
| Feedback | `--feedback-sheet-background-color`, `--feedback-submit-button-fill-color` | Your feedback dialog or sheet |
| Product cards | `--product-card-background-color`, `--product-card-price-color`, `--product-card-badge-background-color` | `CardDefaults.cardColors()` and your card's text styles |
| Other | `--disclaimer-color`, `--thinking-dot-color` | Chat colors |
| Layout | `--message-border-radius`, `--message-padding`, `--input-border-radius-mobile`, `--chat-history-padding` | `MaterialTheme.shapes` and your own dimension constants |

The [style guide](style-guide.md#theme-tokens) describes what each token controls in the extension's UI. Use it as a checklist of what to style.

## Behaviors

Each `behavior` setting becomes a decision in your UI.

| Theme JSON setting | In a headless app |
| --- | --- |
| `multimodalCarousel.carouselStyle` | Use a `HorizontalPager` for paged cards or a `LazyRow` for free scrolling. |
| `multimodalCarousel.cardClickAction` | Your card click handler. Report the tap with `ConciergeInteraction.ProductCardClicked`. |
| `input.*` | Your input bar. For voice input, use Android speech recognition and send the transcript with `conversation.send`. |
| `chat.messageAlignment`, `chat.messageWidth`, `chat.userMessageBubbleStyle` | Your message layout, `widthIn(max = ...)` and bubble shapes. |
| `privacyNotice` | Your own notice, shown before the first message. It is not sent to the service. |
| `productCard.cardStyle`, `productCard.cardsAlignment` | Your product card composable. |
| `welcomeCard.*` | Your empty state. |
| `promptSuggestions.itemMaxLines`, `promptSuggestions.showHeader` | Your suggestion chips. |
| `feedback.*` | Your feedback controls and dialog. Show them when a message's `feedbackEnabled` is true. `alwaysDisplay` has no equivalent: `sendFeedback` ignores other messages. |
| `citations.showCitationNumbers` | Whether you insert citation markers. See [Citations](#citations). |
| `citations.showLinkIcon`, `phoneIcon`, `storeIcon`, `defaultLinkIcon`, `linkIconStyle` | Icons you draw next to links. See [Links](#links). |

## Render a response

Each agent reply is a `ConciergeMessage` in `conversation.messages`. While it streams, `text` holds the full text so far. These sections cover what the built-in UI does with each field.

### Message text

`text` is Markdown. The extension renders bold, italic, links, headings, lists, block quotes, inline code and code blocks. Use a Markdown renderer that supports the same elements, and style its output with your typography and chat colors.

While a turn streams, the text may contain unfinished Markdown, such as an unclosed `**`. Re-render it on every update. The text is complete when `isStreaming` is false.

While `state` is `RESPONDING` and the agent message hasn't appeared, show your own loading indicator. The extension shows animated dots, colored with `--thinking-dot-color`.

### Citations

Each `ConciergeSource` can locate the text it supports with `startIndex` and `endIndex`. The indexes refer to positions in the Markdown text, before rendering. The extension inserts a `[^n]` marker at each `endIndex` and draws it as a circular badge. Below the message, it lists one source per citation number, in citation-number order.

Insert markers once `isStreaming` is false, so the indexes match the final text:

```kotlin
fun insertCitationMarkers(message: String, sources: List<ConciergeSource>): String {
    val result = StringBuilder(message)
    sources
        .mapNotNull { source ->
            val number = source.citationNumber
            val start = source.startIndex
            val end = source.endIndex
            if (number != null && start != null && end != null && end > start && end <= message.length) {
                end to number
            } else {
                null
            }
        }
        .sortedByDescending { it.first }
        .forEach { (end, number) -> result.insert(end, "[^$number]") }
    return result.toString()
}

fun sourceList(sources: List<ConciergeSource>): List<ConciergeSource> =
    sources.filter { it.citationNumber != null }.distinctBy { it.citationNumber }.sortedBy { it.citationNumber }
```

Inserting from the end keeps the earlier indexes valid. Use any marker your Markdown renderer can turn into a badge.

### Links

When `citations.showLinkIcon` is on, the built-in UI draws an icon after each inline link. It picks the icon from the service's link hints, which tag a link as a `phone` or `store` link. `ConciergeMessage` doesn't include link hints yet, so draw one icon for every link, or pick one from the URL scheme, such as `tel:`.

When the user taps a link in the text, report it with `ConciergeInteraction.LinkClicked`.

Links in responses come from the service. Before opening one, check its scheme against an allowlist. The built-in UI ignores `javascript`, `file`, `content`, `intent` and `data` URLs.

```kotlin
private val allowedSchemes = setOf("http", "https", "tel", "mailto")

fun openResponseLink(context: Context, url: String) {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase() ?: return
    if (scheme !in allowedSchemes) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}
```

### Elements

| Element | How to render it |
| --- | --- |
| `ProductCard` | Your card composable. Load `productImageUrl` with your image loader. Show `productBadge` as a label on the image. The service can also send styling values that are only in `raw`: `backgroundColor` and `logo` (in `raw["entity_info"]` or `raw`), and `width`, `height`, `thumbnailWidth` and `thumbnailHeight` (in `raw`). Decide whether they override your theme. Parse colors yourself, and fall back to your theme when a value is invalid. |
| `CtaButton` | Your button style. Open `url` with your link handler. |
| `Custom` | Your own renderer, looked up by `rendererId`. Read its data from `raw`. |
| `Unknown` | Skip it, or draw a fallback. `raw` holds the element as received. |

Keep custom renderers in one place by matching on `rendererId`:

```kotlin
@Composable
fun CustomElement(element: ConciergeElement.Custom) {
    when (element.rendererId) {
        "store-locator" -> StoreLocatorCard(element.raw)
        else -> Unit
    }
}
```

### Suggestions and feedback

Show the latest message's `promptSuggestions` as chips once it stops streaming. When one is tapped, report `ConciergeInteraction.PromptSuggestionClicked` and send its text with `conversation.send`.

Show feedback controls when `feedbackEnabled` is true. Send the rating, selected reasons and notes with `conversation.sendFeedback`. Once `feedback` is set, show the selected rating. Placement, dialog style and copy are yours.

## Accessibility

In a headless UI, these are yours to handle:

- Content descriptions for icon-only controls, such as thumbs, send, voice and link icons.
- Text that scales with the system font size. Use `sp` units and let messages and chips wrap.
- Color contrast in both light and dark mode.
- Right-to-left layouts. Use `start` and `end` rather than `left` and `right`.

## Sample

The test app's **Headless Chat** screen (`HeadlessChatActivity`) styles the chat with the app's `MaterialTheme`.

Its product cards follow the extension's `productDetail` style: image and badge, name, description, price and was-price, then CTAs. Two or more cards scroll in a paged carousel with arrows and page dots. The carousel is a scrolling `Row` with `Modifier.height(IntrinsicSize.Max)`, and each card uses `fillMaxHeight()`, so every card matches the tallest one and the price and CTAs line up at the bottom. A `LazyRow` can't size its items to each other this way.

The **Grid** switch in the header shows the cards as a two-column grid of compact tiles instead: square image and badge, name, price and was-price, then the primary CTA. The extension has no built-in grid style. Each row is a `Row` with `Modifier.height(IntrinsicSize.Max)` and two `weight(1f)` tiles, so the tiles in a row match heights. A lone last tile keeps half the width.

Otherwise it is a minimal example: it shows message text as plain text, without Markdown, citation markers or link icons.
