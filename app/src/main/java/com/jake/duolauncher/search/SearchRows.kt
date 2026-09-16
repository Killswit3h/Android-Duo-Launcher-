@file:OptIn(ExperimentalComposeUiApi::class)

package com.jake.duolauncher.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.icons.IconRenderer
import com.jake.duolauncher.icons.IconStyle

/**
 * The rows, sections and controls the Search screen is built from (FR-71, FR-72).
 *
 * Every row here is at least [ROW_MIN_HEIGHT] tall, merges its own semantics into one TalkBack
 * announcement, and ellipsizes rather than clipping, so the screen stays usable at font scale 1.3
 * (NFR-A1, NFR-A3). No colour literal appears in this file: every colour is a semantic role from
 * the token module (AC-1).
 */

// ---------------------------------------------------------------------------
// The field, Ask and Cancel
// ---------------------------------------------------------------------------

/**
 * The search field. It takes focus and raises the keyboard as soon as Search opens (FR-71), and its
 * search action launches the top result (FR-74).
 */
@Composable
internal fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onAsk: () -> Unit,
    assistantAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    GlassSurface(
        level = GlassLevel.BAR,
        shape = DuoTokens.radius.dock,
        modifier = modifier.heightIn(min = SEARCH_FIELD_HEIGHT),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .semantics { contentDescription = SearchText.FIELD_LABEL }
                .testTag("search-field"),
            textStyle = DuoTokens.type.body.copy(color = colors.label1),
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { field ->
                Row(
                    modifier = Modifier.padding(horizontal = DuoTokens.space.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = colors.label2,
                        modifier = Modifier.size(FIELD_ICON_SIZE),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = SearchText.PLACEHOLDER,
                                style = DuoTokens.type.body,
                                color = colors.label3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        field()
                    }
                    if (query.isNotEmpty()) {
                        FieldAction(
                            label = SearchText.CLEAR,
                            onClick = { onQuery("") },
                            testTag = "search-clear",
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                tint = colors.label2,
                                modifier = Modifier.size(FIELD_ICON_SIZE),
                            )
                        }
                    }
                    // FR-73 / error table: no assistant configured means no Ask control at all.
                    if (assistantAvailable) {
                        FieldAction(
                            label = SearchText.ASK,
                            onClick = onAsk,
                            testTag = "search-ask",
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(FIELD_ICON_SIZE),
                            )
                        }
                    }
                }
            },
        )
    }
}

/** An icon control inside the field, sized to a 48dp touch target and labelled for TalkBack. */
@Composable
private fun FieldAction(
    label: String,
    onClick: () -> Unit,
    testTag: String,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(TOUCH_TARGET)
            .clip(DuoTokens.radius.tile)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
        content = { icon() },
    )
}

/** Closes Search. Keeps a labelled, focusable dismiss target for TalkBack alongside the gesture. */
@Composable
internal fun CancelButton(onDismiss: () -> Unit) {
    val colors = currentDuoColors()
    Box(
        modifier = Modifier
            .heightIn(min = TOUCH_TARGET)
            .clip(DuoTokens.radius.tile)
            .clickable(onClick = onDismiss)
            .padding(horizontal = DuoTokens.space.sm)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .testTag("search-cancel"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = SearchText.CANCEL,
            style = DuoTokens.type.body,
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Sections and rows
// ---------------------------------------------------------------------------

/** The section title, in the engine's fixed order (FR-72). */
@Composable
internal fun SectionHeader(section: SearchSection) {
    val colors = currentDuoColors()
    Text(
        text = SearchText.sectionTitle(section),
        style = DuoTokens.type.footnote,
        color = colors.label2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DuoTokens.space.md, top = DuoTokens.space.md, bottom = DuoTokens.space.xxs)
            .testTag("search-section-${section.name.lowercase()}"),
    )
}

/** One result row, drawn according to its type and launched through [onOpen]. */
@Composable
internal fun SearchResultRow(
    result: SearchResult,
    icons: IconRenderer?,
    iconStyle: IconStyle,
    onOpen: (SearchResult) -> Unit,
) {
    val colors = currentDuoColors()
    when (result) {
        is AppResult -> SearchRow(
            title = result.app.label,
            subtitle = if (result.app.isWork) SearchText.WORK else null,
            description = SearchText.appRow(result.app.label, result.app.isWork),
            testTag = "search-app-${result.app.id}",
            onClick = { onOpen(result) },
        ) {
            AppIcon(
                appId = result.app.id,
                label = result.app.label,
                icons = icons,
                iconStyle = iconStyle,
                size = ROW_ICON_SIZE,
            )
        }

        is ShortcutResult -> SearchRow(
            title = result.shortcut.label,
            subtitle = result.shortcut.appLabel.takeIf(String::isNotBlank),
            description = SearchText.shortcutRow(result.shortcut.label, result.shortcut.appLabel),
            testTag = "search-shortcut-${result.shortcut.appId}-${result.shortcut.id}",
            onClick = { onOpen(result) },
        ) {
            // The shortcut's own icon belongs to the shortcut repository; the owning app's icon is
            // what Search can draw without reaching across the seam, and it identifies the row just
            // as well because the app label is the second line.
            AppIcon(
                appId = result.shortcut.appId,
                label = result.shortcut.label,
                icons = icons,
                iconStyle = iconStyle,
                size = ROW_ICON_SIZE,
            )
        }

        is SettingResult -> SearchRow(
            title = result.destination.title,
            subtitle = SearchText.sectionTitle(SearchSection.SETTINGS),
            description = SearchText.settingRow(result.destination.title),
            testTag = "search-setting-${result.destination.id}",
            onClick = { onOpen(result) },
        ) {
            GlyphIcon(Icons.Rounded.Settings, colors.label1)
        }

        is ContactResult -> SearchRow(
            title = result.contact.displayName,
            subtitle = null,
            description = SearchText.contactRow(result.contact.displayName),
            testTag = "search-contact-${result.contact.lookupKey}",
            onClick = { onOpen(result) },
        ) {
            GlyphIcon(Icons.Rounded.Person, colors.label1)
        }

        is CalculationResult -> SearchRow(
            title = result.formatted,
            subtitle = result.expression,
            description = SearchText.calculationRow(result.expression, result.formatted),
            testTag = "search-calculation",
            onClick = { onOpen(result) },
        ) {
            GlyphIcon(Icons.Rounded.Calculate, colors.accent)
        }

        is WebResult -> SearchRow(
            title = result.query,
            subtitle = SearchText.sectionTitle(SearchSection.WEB),
            description = SearchText.webRow(result.query),
            testTag = "search-web",
            onClick = { onOpen(result) },
        ) {
            GlyphIcon(Icons.Rounded.Language, colors.accent)
        }
    }
}

/**
 * The shared row shape: leading visual, title, optional second line.
 *
 * The whole row is one merged TalkBack node with [description] as its label, so a row announces
 * once and completely instead of spelling out its parts (NFR-A1).
 */
@Composable
private fun SearchRow(
    title: String,
    subtitle: String?,
    description: String,
    testTag: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    val colors = currentDuoColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DuoTokens.radius.tile)
            .clickable(onClick = onClick)
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(horizontal = DuoTokens.space.md, vertical = DuoTokens.space.sm)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
    ) {
        leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = DuoTokens.type.body,
                color = colors.label1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = DuoTokens.type.footnote,
                    color = colors.label2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pre-typing suggestions (FR-71)
// ---------------------------------------------------------------------------

/**
 * The Siri-Suggestions-style rows shown before anything is typed.
 *
 * Capped at eight by the engine, so the grid is laid out directly rather than lazily: two rows of
 * four cost nothing to compose and nesting a lazy grid inside the results list would fight it for
 * scrolling.
 */
@Composable
internal fun SuggestionsSection(
    suggestions: List<AppResult>,
    icons: IconRenderer?,
    iconStyle: IconStyle,
    onOpen: (SearchResult) -> Unit,
) {
    if (suggestions.isEmpty()) {
        EmptyHint()
        return
    }
    val colors = currentDuoColors()
    Column(modifier = Modifier.fillMaxWidth().testTag("search-suggestions")) {
        Text(
            text = SearchText.SUGGESTIONS,
            style = DuoTokens.type.footnote,
            color = colors.label2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = DuoTokens.space.md,
                top = DuoTokens.space.md,
                bottom = DuoTokens.space.sm,
            ),
        )
        suggestions.chunked(SUGGESTION_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
            ) {
                row.forEach { suggestion ->
                    SuggestionCell(
                        suggestion = suggestion,
                        icons = icons,
                        iconStyle = iconStyle,
                        onOpen = onOpen,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps a short final row's cells the same width as a full row's.
                repeat(SUGGESTION_COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SuggestionCell(
    suggestion: AppResult,
    icons: IconRenderer?,
    iconStyle: IconStyle,
    onOpen: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    Column(
        modifier = modifier
            .clip(DuoTokens.radius.tile)
            .clickable { onOpen(suggestion) }
            .defaultMinSize(minHeight = TOUCH_TARGET)
            .padding(vertical = DuoTokens.space.sm, horizontal = DuoTokens.space.xs)
            .semantics(mergeDescendants = true) {
                contentDescription = SearchText.suggestionCell(suggestion.app.label)
                role = Role.Button
            }
            .testTag("search-suggestion-${suggestion.app.id}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
    ) {
        AppIcon(
            appId = suggestion.app.id,
            label = suggestion.app.label,
            icons = icons,
            iconStyle = iconStyle,
            size = SUGGESTION_ICON_SIZE,
        )
        Text(
            text = suggestion.app.label,
            style = DuoTokens.type.iconLabel,
            color = colors.label1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Empty and permission states (error table)
// ---------------------------------------------------------------------------

/** "Search with no results": the message. The web row is a section and renders itself below. */
@Composable
internal fun NoResultsMessage() {
    val colors = currentDuoColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DuoTokens.space.md, vertical = DuoTokens.space.xl)
            .semantics(mergeDescendants = true) { contentDescription = SearchText.NO_RESULTS }
            .testTag("search-no-results"),
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
    ) {
        Text(text = SearchText.NO_RESULTS, style = DuoTokens.type.headline, color = colors.label1)
        Text(text = SearchText.NO_RESULTS_HINT, style = DuoTokens.type.footnote, color = colors.label2)
    }
}

/** Shown before typing when launch history has nothing to suggest yet. */
@Composable
private fun EmptyHint() {
    val colors = currentDuoColors()
    Text(
        text = SearchText.EMPTY_HINT,
        style = DuoTokens.type.footnote,
        color = colors.label2,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DuoTokens.space.md, vertical = DuoTokens.space.xl)
            .testTag("search-empty-hint"),
    )
}

/**
 * "Contacts access denied": one row, and only one. Tapping it calls back to the host, which owns
 * the runtime permission request — a composable is the wrong place to ask for one.
 */
@Composable
internal fun AllowContactsRow(onAllowContacts: () -> Unit) {
    val colors = currentDuoColors()
    SearchRow(
        title = SearchText.ALLOW_CONTACTS,
        subtitle = SearchText.ALLOW_CONTACTS_HINT,
        description = SearchText.ALLOW_CONTACTS,
        testTag = "search-allow-contacts",
        onClick = onAllowContacts,
    ) {
        GlyphIcon(Icons.Rounded.Person, colors.label2)
    }
}

// ---------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------

/**
 * An app icon from the icon pipeline.
 *
 * A cache hit is taken synchronously during composition, so a scrolling list draws without a frame
 * of placeholder; only a miss suspends, and until it resolves the lettered placeholder stands in.
 */
@Composable
private fun AppIcon(
    appId: String,
    label: String,
    icons: IconRenderer?,
    iconStyle: IconStyle,
    size: Dp,
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(
        initialValue = icons?.request(appId, sizePx, iconStyle),
        icons,
        appId,
        sizePx,
        iconStyle,
    ) {
        if (icons != null) {
            value = icons.request(appId, sizePx, iconStyle) ?: icons.load(appId, sizePx, iconStyle)
        }
    }

    val rendered = bitmap
    if (rendered == null) {
        LetterPlaceholder(label = label, size = size)
    } else {
        Image(
            bitmap = rendered,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(DuoTokens.radius.iconRadiusFor(size)),
        )
    }
}

/** Stands in for an icon that has not rendered yet, and for rows drawn without a renderer. */
@Composable
private fun LetterPlaceholder(label: String, size: Dp) {
    val colors = currentDuoColors()
    GlassSurface(
        level = GlassLevel.WIDGET,
        shape = DuoTokens.radius.iconRadiusFor(size),
        modifier = Modifier.size(size),
    ) {
        Text(
            text = label.take(1).uppercase(),
            style = DuoTokens.type.subhead,
            color = colors.label2,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** A symbol row icon — settings, contacts, calculation, web — on a glass tile. */
@Composable
private fun GlyphIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
) {
    GlassSurface(
        level = GlassLevel.WIDGET,
        shape = DuoTokens.radius.iconRadiusFor(ROW_ICON_SIZE),
        modifier = Modifier.size(ROW_ICON_SIZE),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(GLYPH_SIZE).align(Alignment.Center),
        )
    }
}

// ---------------------------------------------------------------------------
// Keys and copy
// ---------------------------------------------------------------------------

/** Stable, section-unique list keys, so a refining result list never re-creates identical rows. */
internal object SearchRowKeys {
    fun of(result: SearchResult): String = when (result) {
        is AppResult -> "app:${result.app.id}"
        is ShortcutResult -> "shortcut:${result.shortcut.appId}:${result.shortcut.id}"
        is SettingResult -> "setting:${result.destination.id}"
        is ContactResult -> "contact:${result.contact.lookupKey}"
        is CalculationResult -> "calculation"
        is WebResult -> "web"
    }
}

/**
 * Every user-visible string in Search, in one place.
 *
 * English literals, matching the rest of the launcher. Gathering them here is what makes the
 * localization pass of NFR-M4 a mechanical change to one object rather than a hunt through the UI.
 */
internal object SearchText {
    const val PLACEHOLDER = "Search or Ask"
    const val FIELD_LABEL = "Search apps, shortcuts, settings, contacts and the web"
    const val CLEAR = "Clear search"
    const val ASK = "Ask"
    const val CANCEL = "Cancel"
    const val SUGGESTIONS = "Suggestions"
    const val WORK = "Work"
    const val NO_RESULTS = "No results"
    const val NO_RESULTS_HINT = "Try a different word, or search the web."
    const val EMPTY_HINT = "Search apps, shortcuts, settings, contacts and the web."
    const val ALLOW_CONTACTS = "Allow contacts in Search"
    const val ALLOW_CONTACTS_HINT = "Contacts are looked up on your device and never stored."

    fun sectionTitle(section: SearchSection): String = when (section) {
        SearchSection.APPS -> "Apps"
        SearchSection.SHORTCUTS -> "App shortcuts"
        SearchSection.SETTINGS -> "Settings"
        SearchSection.CONTACTS -> "Contacts"
        SearchSection.CALCULATION -> "Calculation"
        SearchSection.WEB -> "Search the web"
    }

    fun appRow(label: String, isWork: Boolean): String = if (isWork) "$label, work app" else label

    fun shortcutRow(label: String, appLabel: String): String =
        if (appLabel.isBlank()) "$label, shortcut" else "$label, shortcut in $appLabel"

    fun settingRow(title: String): String = "$title, settings"

    fun contactRow(name: String): String = "$name, contact"

    fun calculationRow(expression: String, formatted: String): String = "$expression equals $formatted"

    fun webRow(query: String): String = "Search the web for $query"

    fun suggestionCell(label: String): String = "$label, suggestion"
}

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------

/** Comfortably above the 48dp minimum, and tall enough for two lines at font scale 1.3 (NFR-A1). */
internal val ROW_MIN_HEIGHT: Dp = 56.dp

/** The minimum touch target every control in Search meets (NFR-A1). */
internal val TOUCH_TARGET: Dp = 48.dp

internal val SEARCH_FIELD_HEIGHT: Dp = 52.dp

private val ROW_ICON_SIZE: Dp = 40.dp
private val SUGGESTION_ICON_SIZE: Dp = 56.dp
private val FIELD_ICON_SIZE: Dp = 22.dp
private val GLYPH_SIZE: Dp = 22.dp

private const val SUGGESTION_COLUMNS = 4

/** How far the surface eases back at a fully dragged back gesture (FR-85). */
internal const val BACK_PREVIEW_MIN_SCALE = 0.92f
internal const val BACK_PREVIEW_MIN_ALPHA = 0.85f
