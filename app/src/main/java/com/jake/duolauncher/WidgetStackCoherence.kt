package com.jake.duolauncher

// Keeping widget stacks and the Today column in step with the placements they name. Split out of
// LauncherSchema.kt so the rule sits on its own and can be read without the whole schema around it.

/** The prefix that marks a [WidgetStack] id, so the Today column can tell one from a slot. */
const val STACK_ID_PREFIX: String = "stack:"

/**
 * Drops stack members and Today-column entries that no longer name anything (FR-57, FR-61…FR-64).
 *
 * Stacks and the Today column both reference widget *placement slots*, which live inside the
 * layouts. Nothing kept those two sides in step: `removePlacement` drops a placement without
 * touching `stacks`, and a v1/v2 import replaces the active layout's placements wholesale while
 * deliberately keeping the stacks a legacy backup never described. Either way a stack could be left
 * naming a slot that had vanished or now held a different widget.
 *
 * This is the repair rather than a rejection, and that is deliberate. Dangling stacks already exist
 * on disk from builds that shipped before this function, so a [validate] rule alone would turn an
 * ordinary upgrade into "Saved Home layout could not be read" and an empty Home. Pruning first, at
 * every point a payload is decoded or merged, makes the invariant true before it is asserted.
 *
 * A Today entry that is neither a slot number nor a stack id is left alone. The column also carries
 * built-in widget ids, whose grammar belongs to the Today track and is opaque here on purpose.
 */
internal fun stackCoherence(
    stacks: List<WidgetStack>,
    today: List<String>,
    liveSlots: Set<Int>,
): Pair<List<WidgetStack>, List<String>> {
    val prunedStacks = stacks.mapNotNull { stack ->
        val kept = stack.placementSlots.filter { it in liveSlots }
        when {
            // FR-64: one widget left is a plain widget again, and an empty stack disappears. The
            // placement itself survives either way; only the grouping goes.
            kept.size <= 1 -> null
            kept == stack.placementSlots -> stack
            else -> {
                // Keep showing the widget that was showing, when it survived.
                val active = stack.placementSlots.getOrNull(stack.activeIndex)
                stack.copy(placementSlots = kept, activeIndex = kept.indexOf(active).coerceAtLeast(0))
            }
        }
    }
    val stackIds = prunedStacks.mapTo(mutableSetOf(), WidgetStack::id)
    val prunedToday = today.filter { entry ->
        val slot = entry.toIntOrNull()
        when {
            slot != null -> slot in liveSlots
            entry.startsWith(STACK_ID_PREFIX) -> entry in stackIds
            else -> true
        }
    }
    return prunedStacks to prunedToday
}

/** Every widget placement slot that exists, across all three layouts. */
internal fun LayoutSet.liveWidgetSlots(): Set<Int> =
    LayoutTarget.entries.flatMapTo(mutableSetOf()) { target ->
        layout(target).widgetPlacements.map(WidgetPlacement::slot)
    }

/** [stackCoherence] applied to a whole decoded document, as an identity when nothing dangles. */
internal fun LauncherPersistedState.withCoherentStacks(): LauncherPersistedState {
    val (prunedStacks, prunedToday) = stackCoherence(stacks, leadingPage.today, layoutSet.liveWidgetSlots())
    return if (prunedStacks == stacks && prunedToday == leadingPage.today) this
    else copy(stacks = prunedStacks, leadingPage = leadingPage.copy(today = prunedToday))
}
