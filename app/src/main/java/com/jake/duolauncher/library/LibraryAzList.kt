@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher.library

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.LocalDuoTypography
import com.jake.duolauncher.design.duoColors
import kotlinx.coroutines.launch

/**
 * FR-69's A–Z view: sectioned list plus the side letter scrubber.
 *
 * Section positions come from [AlphabetIndexer], which has already applied the hidden-app and
 * locked-private exclusions (FR-75, FR-77), so a filtered-out app is absent from both the list and
 * the scrubber rather than leaving a gap in one of them.
 */
@Composable
internal fun LibraryAzView(
    index: AlphabetIndex,
    icons: LibraryIcons,
    listState: LazyListState,
    onLaunch: (LibraryApp, android.graphics.Rect?) -> Unit,
    onActions: (LibraryApp) -> Unit,
    modifier: Modifier = Modifier,
    combined: Boolean = true,
    dragModifier: @Composable (LibraryApp) -> Modifier = { Modifier },
    trailing: @Composable ((LibraryApp) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    Row(modifier) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("all-apps-list"),
            state = listState,
            contentPadding = PaddingValues(bottom = DuoTokens.space.md),
        ) {
            index.sections.forEach { section ->
                stickyHeader(key = "library-section-${section.letter}") {
                    SectionHeader(section.letter)
                }
                items(section.apps, key = { it.id }) { app ->
                    LibraryAppRow(
                        app = app,
                        icons = icons,
                        onClick = { bounds -> onLaunch(app, bounds) },
                        onActions = { onActions(app) },
                        modifier = dragModifier(app),
                        combined = combined,
                        trailing = trailing?.let { row -> { row(app) } },
                    )
                }
            }
        }
        if (index.scrubber.size > 1) {
            AlphabetScrubber(
                index = index,
                onJump = { stop -> scope.launch { listState.scrollToItem(stop.headerIndex) } },
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SectionHeader(letter: String, modifier: Modifier = Modifier) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = DuoTokens.space.sm, bottom = DuoTokens.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A denser glass chip keeps the sticky letter readable over the rows scrolling beneath it.
        GlassSurface(
            level = GlassLevel.MENU,
            shape = DuoTokens.radius.tile,
            modifier = Modifier.size(width = HEADER_CHIP_WIDTH, height = HEADER_CHIP_HEIGHT),
        ) {
            Text(
                text = letter,
                modifier = Modifier.align(Alignment.Center),
                style = type.caption2,
                color = colors.label1,
            )
        }
        HorizontalDivider(
            modifier = Modifier
                .weight(1f)
                .padding(start = DuoTokens.space.md),
            color = colors.separator,
        )
    }
}

/**
 * The side letter scrubber (FR-69).
 *
 * The whole strip is one continuous drag target rather than a stack of small buttons, which is what
 * makes it usable with a thumb: the finger never has to find a 12dp letter, and dragging past
 * either end clamps instead of dropping the gesture. Letters stay individually addressable for
 * accessibility services, and the strip reports the letter under the finger as its state.
 */
@Composable
private fun AlphabetScrubber(
    index: AlphabetIndex,
    onJump: (ScrubberStop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    var height by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf<String?>(null) }
    val stops = index.scrubber

    fun jumpTo(y: Float) {
        val position = scrubberIndexFor(y, height, stops.size)
        val stop = stops.getOrNull(position) ?: return
        if (active != stop.letter) {
            active = stop.letter
            onJump(stop)
        }
    }

    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Column(
            modifier = Modifier
                .width(SCRUBBER_WIDTH)
                .fillMaxHeight()
                .padding(vertical = DuoTokens.space.sm)
                .onSizeChanged { height = it.height.toFloat() }
                .pointerInput(stops, height) {
                    detectVerticalDragGestures(
                        onDragStart = { offset -> jumpTo(offset.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    ) { change, _ -> jumpTo(change.position.y) }
                }
                .semantics {
                    contentDescription = "Alphabet scrubber"
                    active?.let { stateDescription = it }
                }
                .testTag("library-scrubber"),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            stops.forEach { stop ->
                Text(
                    text = stop.letter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DuoTokens.radius.tile)
                        .semantics { contentDescription = "Jump to ${stop.letter}" }
                        .testTag("scrubber-${stop.letter}"),
                    style = type.caption2,
                    color = if (active == stop.letter) colors.accent else colors.label2,
                    textAlign = TextAlign.Center,
                )
            }
        }
        active?.let { letter ->
            GlassSurface(
                level = GlassLevel.MENU,
                shape = DuoTokens.radius.card,
                modifier = Modifier
                    .padding(end = SCRUBBER_WIDTH)
                    .size(SCRUB_BUBBLE),
            ) {
                Text(
                    text = letter,
                    modifier = Modifier.align(Alignment.Center),
                    style = type.title2,
                    color = colors.label1,
                )
            }
        }
    }
}

/** Wide enough to find with a thumb without stealing width from the labels. */
private val SCRUBBER_WIDTH: Dp = 32.dp
private val SCRUB_BUBBLE: Dp = 64.dp
private val HEADER_CHIP_WIDTH: Dp = 34.dp
private val HEADER_CHIP_HEIGHT: Dp = 28.dp
