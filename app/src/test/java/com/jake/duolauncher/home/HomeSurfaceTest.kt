package com.jake.duolauncher.home

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jake.duolauncher.DeviceStatus
import com.jake.duolauncher.DockSide
import com.jake.duolauncher.MAX_DOCK_CAPACITY
import com.jake.duolauncher.MIN_DOCK_CAPACITY
import com.jake.duolauncher.deviceStatusDescription
import com.jake.duolauncher.postures.DuoPosture
import com.jake.duolauncher.postures.FoldOrientation
import com.jake.duolauncher.postures.PostureRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home surface's pure rules: the dock's capacity and rejection (FR-38, FR-40), the fold band and
 * the grid gutter that keeps cells off the hinge (FR-42), the mirroring of the rail (FR-37), and the
 * status description the rail and the cluster share (FR-41).
 */
class HomeSurfaceTest {

    // -----------------------------------------------------------------------
    // Dock capacity and the dock-full rejection (FR-38, FR-40)
    // -----------------------------------------------------------------------

    @Test fun `dock capacity is clamped to the three to six the spec allows`() {
        assertEquals(MIN_DOCK_CAPACITY, sanitizedDockCapacity(0))
        assertEquals(MIN_DOCK_CAPACITY, sanitizedDockCapacity(2))
        assertEquals(3, sanitizedDockCapacity(3))
        assertEquals(6, sanitizedDockCapacity(6))
        assertEquals(MAX_DOCK_CAPACITY, sanitizedDockCapacity(9))
        assertEquals(MAX_DOCK_CAPACITY, sanitizedDockCapacity(Int.MAX_VALUE))
    }

    @Test fun `raising the capacity adds empty slots and lowering it drops trailing ones`() {
        val four = listOf("a", "b", null, "d")

        assertEquals(listOf("a", "b", null, "d", null, null), dockSlots(four, 6))
        assertEquals(listOf("a", "b", null), dockSlots(four, 3))
        assertEquals(four, dockSlots(four, 4))
    }

    @Test fun `a stored dock longer than the capacity is never rendered past its slots`() {
        val six = listOf("a", "b", "c", "d", "e", "f")

        assertEquals(3, dockSlots(six, 3).size)
        assertEquals(listOf("a", "b", "c"), dockSlots(six, 3))
    }

    @Test fun `a full dock rejects a new item but still accepts one it already holds`() {
        val full = listOf("a", "b", "c", "d")

        assertFalse("FR-40: a full dock rejects the drop", dockAcceptsDrop(full, "new", capacity = 4))
        assertTrue("moving within the dock vacates its own slot", dockAcceptsDrop(full, "b", capacity = 4))
        assertTrue("the same dock at capacity 6 has room", dockAcceptsDrop(full, "new", capacity = 6))
    }

    @Test fun `a dock with a free slot accepts apps, shortcuts and folders alike`() {
        val room = listOf("a", null, "c", "d")

        assertTrue(dockAcceptsDrop(room, "com.example/.Main", capacity = 4))
        assertTrue(dockAcceptsDrop(room, "duo.shortcut.abc", capacity = 4))
        assertTrue(dockAcceptsDrop(room, "duo.folder.abc", capacity = 4))
        assertFalse("a blank id is not an item", dockAcceptsDrop(room, "", capacity = 4))
    }

    @Test fun `capacity below the stored contents still refuses rather than overflowing`() {
        // Capacity was lowered to 3 while four apps are stored: the visible dock is full.
        assertFalse(dockAcceptsDrop(listOf("a", "b", "c", null), "new", capacity = 3))
    }

    // -----------------------------------------------------------------------
    // Mirroring (FR-37)
    // -----------------------------------------------------------------------

    @Test fun `the rail, the page area and the indicator all mirror with the dock side`() {
        assertEquals(androidx.compose.ui.Alignment.TopEnd, topRailAlignment(DockSide.RIGHT))
        assertEquals(androidx.compose.ui.Alignment.TopStart, topRailAlignment(DockSide.LEFT))
        assertEquals(androidx.compose.ui.Alignment.BottomEnd, bottomRailAlignment(DockSide.RIGHT))
        assertEquals(androidx.compose.ui.Alignment.BottomStart, bottomRailAlignment(DockSide.LEFT))
        // The page area is opposite the rail, and the indicator sits in it on the rail's own side.
        assertEquals(androidx.compose.ui.Alignment.TopStart, pagerAlignment(DockSide.RIGHT))
        assertEquals(androidx.compose.ui.Alignment.TopEnd, pagerAlignment(DockSide.LEFT))
        assertEquals(androidx.compose.ui.Alignment.BottomStart, pageIndicatorAlignment(DockSide.RIGHT))
        assertEquals(androidx.compose.ui.Alignment.BottomEnd, pageIndicatorAlignment(DockSide.LEFT))
    }

    @Test fun `the default surface is a right dock, a 4 by 6 grid and an unlocked layout`() {
        val config = HomeSurfaceConfig()

        assertEquals(DockSide.RIGHT, config.dockSide)
        assertTrue(config.dockOnRight)
        assertEquals(4, config.grid.columns)
        assertEquals(6, config.grid.rows)
        assertFalse(config.lockLayout)
        assertTrue(config.duoStatus)
    }

    // -----------------------------------------------------------------------
    // The fold band (FR-42)
    // -----------------------------------------------------------------------

    @Test fun `only a half-opened posture produces a band to avoid`() {
        assertNull(hingeBandOf(DuoPosture.Flat, density = 2f))
        assertNull(hingeBandOf(DuoPosture.Unknown, density = 2f))
        assertNotNull(
            hingeBandOf(
                DuoPosture.HalfOpened(PostureRect(500, 0, 500, 1000), FoldOrientation.VERTICAL),
                density = 2f,
            ),
        )
    }

    @Test fun `a vertical fold band is the hinge plus sixteen density-scaled dp on each side`() {
        val band = hingeBandOf(
            DuoPosture.HalfOpened(PostureRect(500, 0, 520, 1000), FoldOrientation.VERTICAL),
            density = 2f,
        )!!

        // 16dp at density 2 is 32px on each side.
        assertEquals(468f, band.leftPx, 0.001f)
        assertEquals(552f, band.rightPx, 0.001f)
        assertTrue(band.vertical)
        assertEquals(84f, band.widthPx, 0.001f)
        assertEquals(510f, band.centerXPx, 0.001f)
    }

    @Test fun `a horizontal fold grows the band across the top and bottom instead`() {
        val band = hingeBandOf(
            DuoPosture.HalfOpened(PostureRect(0, 400, 1000, 400), FoldOrientation.HORIZONTAL),
            density = 1f,
        )!!

        assertFalse(band.vertical)
        assertEquals(384f, band.topPx, 0.001f)
        assertEquals(416f, band.bottomPx, 0.001f)
    }

    // -----------------------------------------------------------------------
    // The grid gutter (FR-42, FR-43)
    // -----------------------------------------------------------------------

    private fun verticalBand(left: Float, right: Float) =
        HingeBand(leftPx = left, rightPx = right, topPx = 0f, bottomPx = 1000f, vertical = true)

    @Test fun `no fold means no gutter and the grid keeps its natural columns`() {
        assertNull(hingeColumnGutter(gridLeftPx = 0f, gridWidthPx = 800f, columns = 6, band = null))
    }

    @Test fun `a fold that misses the grid entirely opens no gutter`() {
        val band = verticalBand(900f, 960f)

        assertNull(hingeColumnGutter(gridLeftPx = 0f, gridWidthPx = 800f, columns = 6, band = band))
    }

    @Test fun `a fold down the middle of an even grid splits it cleanly between columns`() {
        // Apple's rule: prefer an even column count so the grid divides at the fold.
        val gutter = hingeColumnGutter(gridLeftPx = 0f, gridWidthPx = 800f, columns = 6, band = verticalBand(380f, 420f))!!

        assertEquals("the boundary nearest the fold is between columns 3 and 4", 3, gutter.afterColumn)
        assertEquals(40f, gutter.widthPx, 0.001f)
        // The remaining 760px is shared by 6 columns rather than any column being dropped.
        assertEquals(760f / 6f, gutter.cellWidthPx, 0.001f)
        assertEquals(0f, gutter.columnLeftPx(0), 0.001f)
        assertEquals(3 * gutter.cellWidthPx, gutter.columnLeftPx(2) + gutter.cellWidthPx, 0.001f)
    }

    @Test fun `every cell clears the fold once the gutter is open`() {
        val band = verticalBand(380f, 420f)
        val gutter = hingeColumnGutter(0f, 800f, 6, band)!!

        (0 until 6).forEach { column ->
            val left = gutter.columnLeftPx(column)
            val right = left + gutter.cellWidthPx
            assertTrue(
                "column $column still overlaps the fold band",
                right <= band.leftPx + 0.001f || left >= band.rightPx - 0.001f,
            )
        }
    }

    @Test fun `an off-centre fold moves the gutter to the nearest column boundary`() {
        val gutter = hingeColumnGutter(0f, 800f, 4, verticalBand(190f, 210f))!!

        assertEquals(1, gutter.afterColumn)
    }

    @Test fun `a fold too wide to absorb leaves the grid alone rather than crushing it`() {
        // A band taking most of the width would leave unusably narrow columns; overlapping the
        // fold is the documented lesser evil, exactly as HingeAvoidance.avoidable decides.
        assertNull(hingeColumnGutter(0f, 800f, 6, verticalBand(100f, 700f)))
    }

    @Test fun `a horizontal fold never opens a vertical gutter`() {
        val horizontal = HingeBand(0f, 800f, 380f, 420f, vertical = false)

        assertNull(hingeColumnGutter(0f, 800f, 6, horizontal))
    }

    // -----------------------------------------------------------------------
    // Single rectangles: folders, menus and sheets (FR-42)
    // -----------------------------------------------------------------------

    @Test fun `a panel already clear of the fold is left exactly where it was`() {
        val container = Rect(0f, 0f, 1000f, 800f)
        val panel = Rect(40f, 100f, 400f, 700f)

        assertEquals(panel, avoidHinge(container, panel, verticalBand(460f, 540f)))
    }

    @Test fun `a panel straddling the fold moves onto the side it already sits mostly on`() {
        val container = Rect(0f, 0f, 1000f, 800f)
        val panel = Rect(300f, 100f, 520f, 700f)

        val moved = avoidHinge(container, panel, verticalBand(460f, 540f))

        assertTrue("the panel must not overlap the fold band", moved.right <= 460f || moved.left >= 540f)
        assertEquals("its size is kept where the side can hold it", panel.width, moved.width, 0.001f)
        assertEquals(panel.height, moved.height, 0.001f)
    }

    @Test fun `with no fold a panel and the largest region are unchanged`() {
        val container = Rect(0f, 0f, 1000f, 800f)
        val panel = Rect(300f, 100f, 520f, 700f)

        assertEquals(panel, avoidHinge(container, panel, null))
        assertEquals(container, largestRegion(container, null))
    }

    @Test fun `the largest region is the roomier side of the fold`() {
        val container = Rect(0f, 0f, 1000f, 800f)

        val region = largestRegion(container, verticalBand(300f, 340f))

        assertEquals(340f, region.left, 0.001f)
        assertEquals(1000f, region.right, 0.001f)
    }

    @Test fun `a band is rebased onto a child's own origin`() {
        val band = verticalBand(460f, 540f)

        val local = band.relativeTo(Rect(100f, 50f, 900f, 750f))

        assertEquals(360f, local.leftPx, 0.001f)
        assertEquals(440f, local.rightPx, 0.001f)
    }

    // -----------------------------------------------------------------------
    // The context menu keeps clear of the fold too (FR-42)
    // -----------------------------------------------------------------------

    @Test fun `the context menu slides off the fold when there is room beside it`() {
        val offset = contextMenuOffset(
            anchor = Rect(440f, 200f, 520f, 280f),
            menuSize = IntSize(248, 400),
            container = IntSize(1000, 900),
            gap = 8f,
            margin = 16f,
            exclusion = Rect(460f, 0f, 540f, 900f),
        )

        val right = offset.x + 248
        assertTrue("the menu still crosses the fold", right <= 460 || offset.x >= 540)
    }

    @Test fun `the context menu stays put when neither side of the fold can hold it`() {
        val exclusion = Rect(100f, 0f, 700f, 900f)
        val without = contextMenuOffset(Rect(300f, 200f, 380f, 280f), IntSize(600, 400), IntSize(800, 900), 8f, 16f)
        val with = contextMenuOffset(Rect(300f, 200f, 380f, 280f), IntSize(600, 400), IntSize(800, 900), 8f, 16f, exclusion)

        assertEquals("a menu pushed off screen would be worse", without, with)
    }

    @Test fun `without a fold the menu offset is unchanged`() {
        val anchor = Rect(440f, 200f, 520f, 280f)
        val expected: IntOffset = contextMenuOffset(anchor, IntSize(248, 400), IntSize(1000, 900), 8f, 16f)

        assertEquals(expected, contextMenuOffset(anchor, IntSize(248, 400), IntSize(1000, 900), 8f, 16f, null))
    }

    // -----------------------------------------------------------------------
    // The shared status description (FR-41)
    // -----------------------------------------------------------------------

    @Test fun `the cluster and the rail announce the same facts`() {
        val description = deviceStatusDescription(
            status = DeviceStatus(battery = 72, charging = true, wifiConnected = true, wifiLevel = 3, cellularLevel = 2),
            dateTimeText = "Tuesday, September 15, 9:41",
        )

        assertEquals(
            "Tuesday, September 15, 9:41. Battery 72 percent, charging. " +
                "Wi-Fi connected, signal 3 of 4. Cellular signal 2 of 4",
            description,
        )
    }

    @Test fun `an unknown reading is reported as unavailable, never as full signal`() {
        val description = deviceStatusDescription(
            status = DeviceStatus(battery = null, wifiConnected = true, wifiLevel = null, cellularLevel = null),
            dateTimeText = "Tuesday, September 15, 9:41",
        )

        assertTrue(description.contains("Battery unavailable"))
        assertTrue("a connected radio with no level claims no strength", description.contains("Wi-Fi connected."))
        assertTrue(description.contains("Cellular signal unavailable"))
    }

    @Test fun `airplane mode replaces the cellular reading and location is announced first`() {
        val description = deviceStatusDescription(
            status = DeviceStatus(battery = 10, wifiConnected = false, airplane = true, cellularLevel = 4),
            dateTimeText = "Tuesday, September 15, 9:41",
            locationInUse = true,
        )

        assertTrue(description.startsWith("Location in use."))
        assertTrue(description.contains("Wi-Fi disconnected"))
        assertTrue(description.contains("Airplane mode"))
        assertFalse(description.contains("Cellular signal 4"))
    }
}
