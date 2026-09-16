package com.jake.duolauncher.postures

import kotlin.math.roundToInt

/**
 * The usable areas of a container once the hinge is kept clear.
 *
 * @param regions usable areas in visual order (left to right, or top to bottom). Never empty:
 *   when nothing has to be avoided it holds the container itself.
 * @param exclusion the area to keep content out of — hinge bounds plus the margin, clipped to the
 *   container — or null when there is nothing to avoid.
 * @param avoidable false when the exclusion swallows the container, so no region survives. The
 *   documented fallback is then [regions] = the whole container: content is laid out as if there
 *   were no hinge, because refusing to lay out at all would be worse than overlapping the fold.
 */
data class HingeRegions(
    val regions: List<PostureRect>,
    val exclusion: PostureRect?,
    val avoidable: Boolean,
) {
    /** True when the hinge splits the container into two usable areas. */
    val isSplit: Boolean get() = regions.size > 1

    /** The roomiest usable area; the natural home for a folder, menu or sheet. */
    val largest: PostureRect get() = regions.maxByOrNull { it.area } ?: PostureRect.EMPTY
}

/**
 * Pure hinge geometry (FR-42).
 *
 * Nothing here touches Android, a density-aware `Dp`, or a frame of layout: give it pixels and it
 * answers in pixels, so every case below is a unit test rather than a device check.
 */
object HingeAvoidance {
    /** FR-42's margin on each side of the hinge bounds. */
    const val MARGIN_DP = 16f

    /** [MARGIN_DP] in pixels. Callers inside Compose can equally use `16.dp.roundToPx()`. */
    fun marginPx(density: Float): Int = (MARGIN_DP * density).roundToInt().coerceAtLeast(0)

    /**
     * The usable regions of [container] for the given [posture].
     *
     * [DuoPosture.Flat] and [DuoPosture.Unknown] both yield the whole container with no exclusion,
     * which is exactly the width-only fallback the error table asks for.
     */
    fun regionsFor(container: PostureRect, posture: DuoPosture, marginPx: Int): HingeRegions =
        when (posture) {
            is DuoPosture.HalfOpened ->
                regions(container, posture.hingeBoundsPx, posture.orientation, marginPx)
            DuoPosture.Flat, DuoPosture.Unknown -> whole(container)
        }

    /**
     * The usable regions of [container] once [hinge] plus [marginPx] on each side is excluded.
     *
     * Handles, in order: no hinge at all; a hinge that misses the container; a hinge along one
     * edge (one region, no split); a hinge crossing the container (two regions); and a container
     * too small to fit anything beside the hinge (see [HingeRegions.avoidable]).
     */
    fun regions(
        container: PostureRect,
        hinge: PostureRect?,
        orientation: FoldOrientation?,
        marginPx: Int,
    ): HingeRegions {
        if (container.isEmpty || hinge == null || orientation == null) return whole(container)
        val margin = marginPx.coerceAtLeast(0)
        // Grow the hinge across the fold and span the container along it, then clip. A hinge that
        // misses the container, or a zero-thickness hinge with no margin, clips away to nothing.
        val band = when (orientation) {
            FoldOrientation.VERTICAL ->
                PostureRect(hinge.left - margin, container.top, hinge.right + margin, container.bottom)
            FoldOrientation.HORIZONTAL ->
                PostureRect(container.left, hinge.top - margin, container.right, hinge.bottom + margin)
        }
        val exclusion = band.intersect(container)
        if (exclusion.isEmpty) return whole(container)
        val sides = when (orientation) {
            FoldOrientation.VERTICAL -> listOf(
                PostureRect(container.left, container.top, exclusion.left, container.bottom),
                PostureRect(exclusion.right, container.top, container.right, container.bottom),
            )
            FoldOrientation.HORIZONTAL -> listOf(
                PostureRect(container.left, container.top, container.right, exclusion.top),
                PostureRect(container.left, exclusion.bottom, container.right, container.bottom),
            )
        }
        val usable = sides.filterNot { it.isEmpty }
        return if (usable.isEmpty()) HingeRegions(listOf(container), exclusion, avoidable = false)
        else HingeRegions(usable, exclusion, avoidable = true)
    }

    /**
     * [content] moved — and only if necessary shrunk — so it no longer sits under the fold.
     *
     * Content that already clears the exclusion is returned untouched. Otherwise it moves into the
     * region it already overlaps most (ties go to the roomier region, then to the first in visual
     * order, so the result is deterministic), keeping its size where the region can hold it and
     * clamping to the region where it cannot.
     *
     * Returns [content] unchanged when there is nothing to avoid or when avoidance is impossible.
     */
    fun avoid(content: PostureRect, regions: HingeRegions): PostureRect {
        val exclusion = regions.exclusion ?: return content
        if (!regions.avoidable || content.isEmpty) return content
        if (!content.overlaps(exclusion)) return content
        val target = regions.regions.maxWithOrNull(
            compareBy({ content.intersect(it).area }, { it.area }),
        ) ?: return content
        return fitInto(content, target)
    }

    /** [avoid] for callers that hold a posture rather than pre-computed regions. */
    fun avoid(
        container: PostureRect,
        content: PostureRect,
        posture: DuoPosture,
        marginPx: Int,
    ): PostureRect = avoid(content, regionsFor(container, posture, marginPx))

    private fun whole(container: PostureRect) =
        HingeRegions(listOf(container), exclusion = null, avoidable = true)

    private fun fitInto(content: PostureRect, region: PostureRect): PostureRect {
        if (region.isEmpty) return content
        val width = minOf(content.width, region.width)
        val height = minOf(content.height, region.height)
        val left = content.left.coerceIn(region.left, region.right - width)
        val top = content.top.coerceIn(region.top, region.bottom - height)
        return PostureRect(left, top, left + width, top + height)
    }
}
