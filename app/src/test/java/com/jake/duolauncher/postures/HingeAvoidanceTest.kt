package com.jake.duolauncher.postures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HingeAvoidanceTest {
    private val container = PostureRect(0, 0, 1000, 800)
    private val margin = 16

    @Test fun `no hinge leaves the whole container usable`() {
        val regions = HingeAvoidance.regions(container, hinge = null, orientation = null, marginPx = margin)
        assertEquals(listOf(container), regions.regions)
        assertNull(regions.exclusion)
        assertFalse(regions.isSplit)
        assertTrue(regions.avoidable)
    }

    @Test fun `a vertical hinge line splits the container with 16dp clear on each side`() {
        val regions = HingeAvoidance.regions(
            container, PostureRect(500, 0, 500, 800), FoldOrientation.VERTICAL, margin,
        )
        assertEquals(PostureRect(484, 0, 516, 800), regions.exclusion)
        assertEquals(
            listOf(PostureRect(0, 0, 484, 800), PostureRect(516, 0, 1000, 800)),
            regions.regions,
        )
        assertTrue(regions.isSplit)
        assertTrue(regions.avoidable)
    }

    @Test fun `a vertical hinge with physical width is excluded plus the margin`() {
        val regions = HingeAvoidance.regions(
            container, PostureRect(480, 0, 520, 800), FoldOrientation.VERTICAL, margin,
        )
        assertEquals(PostureRect(464, 0, 536, 800), regions.exclusion)
        assertEquals(
            listOf(PostureRect(0, 0, 464, 800), PostureRect(536, 0, 1000, 800)),
            regions.regions,
        )
    }

    @Test fun `a horizontal hinge splits top from bottom`() {
        val regions = HingeAvoidance.regions(
            container, PostureRect(0, 400, 1000, 400), FoldOrientation.HORIZONTAL, margin,
        )
        assertEquals(PostureRect(0, 384, 1000, 416), regions.exclusion)
        assertEquals(
            listOf(PostureRect(0, 0, 1000, 384), PostureRect(0, 416, 1000, 800)),
            regions.regions,
        )
    }

    @Test fun `a hinge outside the container changes nothing`() {
        for (hinge in listOf(PostureRect(2000, 0, 2000, 800), PostureRect(-900, 0, -900, 800))) {
            val regions = HingeAvoidance.regions(container, hinge, FoldOrientation.VERTICAL, margin)
            assertEquals(listOf(container), regions.regions)
            assertNull(regions.exclusion)
            assertFalse(regions.isSplit)
        }
    }

    @Test fun `a hinge on the container edge leaves one usable region and no split`() {
        val regions = HingeAvoidance.regions(
            container, PostureRect(0, 0, 0, 800), FoldOrientation.VERTICAL, margin,
        )
        assertEquals(PostureRect(0, 0, 16, 800), regions.exclusion)
        assertEquals(listOf(PostureRect(16, 0, 1000, 800)), regions.regions)
        assertFalse(regions.isSplit)
        assertTrue(regions.avoidable)
    }

    @Test fun `a container too small to clear the hinge falls back to the whole container`() {
        val narrow = PostureRect(0, 0, 20, 800)
        val regions = HingeAvoidance.regions(
            narrow, PostureRect(10, 0, 10, 800), FoldOrientation.VERTICAL, margin,
        )
        assertFalse(regions.avoidable)
        assertEquals(listOf(narrow), regions.regions)
        assertEquals(narrow, regions.exclusion)
        // The fallback must still lay content out rather than collapse it.
        val content = PostureRect(0, 0, 20, 100)
        assertEquals(content, HingeAvoidance.avoid(content, regions))
    }

    @Test fun `an empty container is returned untouched`() {
        val empty = PostureRect(10, 10, 10, 10)
        val regions = HingeAvoidance.regions(
            empty, PostureRect(10, 0, 10, 800), FoldOrientation.VERTICAL, margin,
        )
        assertEquals(listOf(empty), regions.regions)
        assertNull(regions.exclusion)
    }

    @Test fun `a zero-thickness hinge with no margin excludes nothing`() {
        val regions = HingeAvoidance.regions(
            container, PostureRect(500, 0, 500, 800), FoldOrientation.VERTICAL, marginPx = 0,
        )
        assertNull(regions.exclusion)
        assertEquals(listOf(container), regions.regions)
    }

    @Test fun `content clear of the fold is left exactly where it is`() {
        val regions = HingeAvoidance.regions(
            container, PostureRect(500, 0, 500, 800), FoldOrientation.VERTICAL, margin,
        )
        val content = PostureRect(0, 0, 400, 200)
        assertEquals(content, HingeAvoidance.avoid(content, regions))
    }

    @Test fun `content straddling the fold moves into the side it already occupies most`() {
        val regions = HingeAvoidance.regions(
            container, PostureRect(500, 0, 500, 800), FoldOrientation.VERTICAL, margin,
        )
        val content = PostureRect(400, 100, 700, 300)
        val moved = HingeAvoidance.avoid(content, regions)
        assertEquals(PostureRect(516, 100, 816, 300), moved)
        assertEquals(content.width, moved.width)
        assertEquals(content.height, moved.height)
        assertFalse(moved.overlaps(regions.exclusion!!))
    }

    @Test fun `content wider than a region is clamped to it deterministically`() {
        val regions = HingeAvoidance.regions(
            container, PostureRect(500, 0, 500, 800), FoldOrientation.VERTICAL, margin,
        )
        val content = PostureRect(0, 0, 1000, 100)
        val fitted = HingeAvoidance.avoid(content, regions)
        assertEquals(PostureRect(0, 0, 484, 100), fitted)
        assertFalse(fitted.overlaps(regions.exclusion!!))
        assertEquals(fitted, HingeAvoidance.avoid(content, regions))
    }

    @Test fun `content moves up out of a horizontal fold`() {
        val regions = HingeAvoidance.regions(
            container, PostureRect(0, 400, 1000, 400), FoldOrientation.HORIZONTAL, margin,
        )
        val content = PostureRect(100, 200, 300, 420)
        val moved = HingeAvoidance.avoid(content, regions)
        assertEquals(PostureRect(100, 164, 300, 384), moved)
        assertFalse(moved.overlaps(regions.exclusion!!))
    }

    @Test fun `posture drives the regions and flat or unknown keeps width-only behavior`() {
        val half = DuoPosture.HalfOpened(PostureRect(500, 0, 500, 800), FoldOrientation.VERTICAL)
        assertTrue(HingeAvoidance.regionsFor(container, half, margin).isSplit)
        for (posture in listOf(DuoPosture.Flat, DuoPosture.Unknown)) {
            val regions = HingeAvoidance.regionsFor(container, posture, margin)
            assertEquals(listOf(container), regions.regions)
            assertNull(regions.exclusion)
            val content = PostureRect(400, 100, 700, 300)
            assertEquals(content, HingeAvoidance.avoid(container, content, posture, margin))
        }
        assertEquals(
            PostureRect(516, 100, 816, 300),
            HingeAvoidance.avoid(container, PostureRect(400, 100, 700, 300), half, margin),
        )
    }

    @Test fun `the margin converts to whole pixels at real Fold densities`() {
        assertEquals(16, HingeAvoidance.marginPx(1f))
        assertEquals(44, HingeAvoidance.marginPx(2.75f))
        assertEquals(48, HingeAvoidance.marginPx(3f))
        assertEquals(0, HingeAvoidance.marginPx(0f))
    }

    @Test fun `the largest region is the one with the most room`() {
        val regions = HingeAvoidance.regions(
            container, PostureRect(300, 0, 300, 800), FoldOrientation.VERTICAL, margin,
        )
        assertEquals(PostureRect(316, 0, 1000, 800), regions.largest)
    }
}
