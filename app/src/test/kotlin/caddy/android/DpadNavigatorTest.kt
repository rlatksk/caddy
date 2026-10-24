package caddy.android

import caddy.android.ui.Direction
import caddy.android.ui.DpadNavigator
import caddy.android.ui.FilterMode
import caddy.android.ui.FocusTarget
import caddy.android.ui.ScreenMode
import caddy.android.ui.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadNavigatorTest {

    @Test
    fun testBrowseHorizontalTransitions() {
        val nav = DpadNavigator()
        assertEquals(ScreenMode.BROWSE, nav.screen)
        assertEquals(FocusTarget(Zone.LIST, 0), nav.focus)

        // LIST -> RAIL on LEFT
        val railTarget = nav.move(Direction.LEFT, listCount = 5)
        assertEquals(FocusTarget(Zone.RAIL, 0), railTarget)
        assertEquals(FocusTarget(Zone.RAIL, 0), nav.focus)

        // RAIL -> LIST on RIGHT
        val listTarget = nav.move(Direction.RIGHT, listCount = 5)
        assertEquals(FocusTarget(Zone.LIST, 0), listTarget)
        assertEquals(FocusTarget(Zone.LIST, 0), nav.focus)

        // LIST -> DETAIL on RIGHT
        val detailTarget = nav.move(Direction.RIGHT, listCount = 5)
        assertEquals(FocusTarget(Zone.DETAIL, 0), detailTarget)
        assertEquals(FocusTarget(Zone.DETAIL, 0), nav.focus)

        // DETAIL -> LIST on LEFT
        val backToList = nav.move(Direction.LEFT, listCount = 5)
        assertEquals(FocusTarget(Zone.LIST, 0), backToList)
        assertEquals(FocusTarget(Zone.LIST, 0), nav.focus)
    }
    @Test
    fun testListIndexPreservedOnDetailTransition() {
        val nav = DpadNavigator()
        nav.move(Direction.DOWN, listCount = 10)
        nav.move(Direction.DOWN, listCount = 10)
        assertEquals(FocusTarget(Zone.LIST, 2), nav.focus)

        // LIST -> DETAIL on RIGHT preserves index 2
        val detailTarget = nav.move(Direction.RIGHT, listCount = 10)
        assertEquals(FocusTarget(Zone.DETAIL, 2), detailTarget)
        assertEquals(FocusTarget(Zone.DETAIL, 2), nav.focus)

        // DETAIL -> LIST on LEFT preserves index 2
        val listTarget = nav.move(Direction.LEFT, listCount = 10)
        assertEquals(FocusTarget(Zone.LIST, 2), listTarget)
        assertEquals(FocusTarget(Zone.LIST, 2), nav.focus)
    }

    @Test
    fun testSourcesFieldVerticalNavigation() {
        val nav = DpadNavigator()
        nav.screen = ScreenMode.SOURCES
        nav.focus = FocusTarget(Zone.FIELD, 0)

        // DOWN from field 0 -> field 1
        val f1 = nav.move(Direction.DOWN, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.FIELD, 1), f1)

        // DOWN from field 1 -> field 2
        val f2 = nav.move(Direction.DOWN, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.FIELD, 2), f2)

        // DOWN from field 2 -> grid 0
        val grid0 = nav.move(Direction.DOWN, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.GRID, 0), grid0)

        // UP from grid 0 -> field 0
        val backToField = nav.move(Direction.UP, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.FIELD, 0), backToField)

        // Move to field 2 then UP -> field 1 then UP -> field 0
        nav.focus = FocusTarget(Zone.FIELD, 2)
        val upTo1 = nav.move(Direction.UP, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.FIELD, 1), upTo1)
        val upTo0 = nav.move(Direction.UP, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.FIELD, 0), upTo0)
        val upClamp = nav.move(Direction.UP, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.FIELD, 0), upClamp)
    }


    @Test
    fun testFilterRotation() {
        var filter = FilterMode.ALL
        assertEquals("all", filter.label)

        filter = filter.next()
        assertEquals(FilterMode.ON_DISK, filter)
        assertEquals("on disk", filter.label)

        filter = filter.next()
        assertEquals(FilterMode.IN_QUEUE, filter)
        assertEquals("in queue", filter.label)

        filter = filter.next()
        assertEquals(FilterMode.FAILED, filter)
        assertEquals("failed", filter.label)

        filter = filter.next()
        assertEquals(FilterMode.ALL, filter)

        filter = filter.prev()
        assertEquals(FilterMode.FAILED, filter)

        filter = filter.prev()
        assertEquals(FilterMode.IN_QUEUE, filter)
    }

    @Test
    fun testDoubleBackGuard() {
        val nav = DpadNavigator()
        nav.screen = ScreenMode.BROWSE
        nav.focus = FocusTarget(Zone.RAIL, 0)

        // First back: returns false
        assertFalse(nav.handleBack(1000L))

        // Second back within 2000ms: returns true
        assertTrue(nav.handleBack(2500L))

        // Reset state or move away and back to test after 5000ms
        nav.focus = FocusTarget(Zone.RAIL, 0)
        assertFalse(nav.handleBack(3000L))
        // 5000ms later (8000L)
        assertFalse(nav.handleBack(8000L))
    }

    @Test
    fun testFiltersRightClamping() {
        val nav = DpadNavigator()
        nav.screen = ScreenMode.BROWSE
        nav.focus = FocusTarget(Zone.FILTERS, 0)

        val maxIndex = FilterMode.entries.size - 1
        for (i in 0 until maxIndex) {
            val target = nav.move(Direction.RIGHT)
            assertEquals(FocusTarget(Zone.FILTERS, i + 1), target)
            assertEquals(FocusTarget(Zone.FILTERS, i + 1), nav.focus)
        }

        // Try to move RIGHT past the last filter chip; should remain clamped at maxIndex
        val targetAfterMax = nav.move(Direction.RIGHT)
        assertEquals(FocusTarget(Zone.FILTERS, maxIndex), targetAfterMax)
        assertEquals(FocusTarget(Zone.FILTERS, maxIndex), nav.focus)
    }
}
