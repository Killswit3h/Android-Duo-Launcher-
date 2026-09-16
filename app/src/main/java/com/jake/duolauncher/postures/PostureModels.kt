package com.jake.duolauncher.postures

/**
 * Integer pixel rectangle for the posture layer.
 *
 * Deliberately not `android.graphics.Rect` or a Compose `Rect`: every rule in [HingeAvoidance]
 * is pure arithmetic, so it stays covered by JVM unit tests (this module has no Robolectric and
 * unmocked framework classes throw). Callers convert at the edge, which is one `toRect()` away.
 *
 * A rectangle is half-open: [right] and [bottom] are exclusive. Zero-thickness rectangles are
 * legal and meaningful — a book-style hinge usually reports `left == right`.
 */
data class PostureRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    /** True when the rectangle encloses no area, including the zero-thickness hinge line case. */
    val isEmpty: Boolean get() = right <= left || bottom <= top

    val area: Long get() = width.toLong() * height.toLong()

    /** The overlap with [other], or [EMPTY] when they do not overlap. */
    fun intersect(other: PostureRect): PostureRect {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (r <= l || b <= t) EMPTY else PostureRect(l, t, r, b)
    }

    fun overlaps(other: PostureRect): Boolean = !intersect(other).isEmpty

    companion object {
        val EMPTY = PostureRect(0, 0, 0, 0)
    }
}

/**
 * Which way the fold runs, mirroring `FoldingFeature.Orientation`.
 *
 * [VERTICAL] is a vertical hinge line separating left from right (the Fold's book posture, the
 * case FR-42 names). [HORIZONTAL] separates top from bottom (tabletop/flip posture).
 */
enum class FoldOrientation { VERTICAL, HORIZONTAL }

/** The fold states Duo reacts to, mirroring `FoldingFeature.State`. */
enum class FoldState { FLAT, HALF_OPENED }

/**
 * One folding feature, flattened to pure data.
 *
 * This is the boundary type: the Android adapter converts `FoldingFeature` into this, and
 * everything downstream ([postureOf], [HingeAvoidance]) is testable without a device.
 *
 * @param bounds the hinge bounds in window pixels; commonly zero-width on a Galaxy Fold.
 * @param isSeparating true when the feature splits the window into logically separate areas.
 */
data class FoldSignal(
    val bounds: PostureRect,
    val state: FoldState,
    val orientation: FoldOrientation,
    val isSeparating: Boolean = state == FoldState.HALF_OPENED,
)

/**
 * The launcher's view of the device's fold posture — the contract's `FLAT`, `HALF_OPENED` and
 * `UNKNOWN`.
 *
 * [Unknown] is the honest answer whenever the posture API is unavailable, reports nothing, or
 * fails. Per the spec's error table ("Posture API unavailable → width-only layout"), callers
 * treat [Unknown] exactly like today's width-only behavior rather than guessing.
 */
sealed interface DuoPosture {
    /** The display is flat: one continuous surface, no hinge to avoid. */
    data object Flat : DuoPosture

    /** The display is half-opened, with [hingeBoundsPx] to be kept clear (FR-42). */
    data class HalfOpened(
        val hingeBoundsPx: PostureRect,
        val orientation: FoldOrientation,
    ) : DuoPosture

    /** No usable posture information. Callers fall back to width-only layout. */
    data object Unknown : DuoPosture
}
