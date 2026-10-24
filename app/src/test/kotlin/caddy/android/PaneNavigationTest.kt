package caddy.android

import caddy.android.ui.Direction
import caddy.android.ui.DpadNavigator
import caddy.android.ui.FocusTarget
import caddy.android.ui.ScreenMode
import caddy.android.ui.Zone
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneNavigationTest {

    @Test
    fun testQueueNavigation() {
        val nav = DpadNavigator().apply {
            screen = ScreenMode.QUEUE
            focus = FocusTarget(Zone.QLIST, 0)
        }

        // QLIST -> QHEAD on UP when at index 0
        val headTarget = nav.move(Direction.UP, listCount = 3)
        assertEquals(FocusTarget(Zone.QHEAD, 0), headTarget)

        // QHEAD index 0 -> index 1 on RIGHT
        val headRight1 = nav.move(Direction.RIGHT, listCount = 3)
        assertEquals(FocusTarget(Zone.QHEAD, 1), headRight1)

        // QHEAD index 1 -> index 2 ([OPEN FILES]) on RIGHT
        val headRight2 = nav.move(Direction.RIGHT, listCount = 3)
        assertEquals(FocusTarget(Zone.QHEAD, 2), headRight2)

        // QHEAD index 2 -> Config on RIGHT
        val toConfig = nav.move(Direction.RIGHT, listCount = 3, railCount = 1)
        assertEquals(FocusTarget(Zone.RAIL, 2), toConfig)

        // Reset to QHEAD 0
        nav.focus = FocusTarget(Zone.QHEAD, 0)
        // QHEAD -> QLIST on DOWN
        val listTarget = nav.move(Direction.DOWN, listCount = 3)
        assertEquals(FocusTarget(Zone.QLIST, 0), listTarget)

        // QLIST moving down
        val listNext = nav.move(Direction.DOWN, listCount = 3)
        assertEquals(FocusTarget(Zone.QLIST, 1), listNext)

        // QLIST -> RAIL on LEFT (focuses [Q] at index railCount=1)
        val railTarget = nav.move(Direction.LEFT, listCount = 3, railCount = 1)
        assertEquals(FocusTarget(Zone.RAIL, 1), railTarget)
    }

    @Test
    fun testSourcesNavigation() {
        val nav = DpadNavigator().apply {
            screen = ScreenMode.SOURCES
            focus = FocusTarget(Zone.FIELD, 0)
        }

        // FIELD 0 -> FIELD 1 -> FIELD 2 -> GRID on DOWN
        val field1 = nav.move(Direction.DOWN, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.FIELD, 1), field1)
        val field2 = nav.move(Direction.DOWN, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.FIELD, 2), field2)
        val gridTarget = nav.move(Direction.DOWN, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.GRID, 0), gridTarget)
        // GRID -> GRID move right
        val gridRight = nav.move(Direction.RIGHT, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.GRID, 1), gridRight)

        // GRID -> META on DOWN when moving past last row
        val toSecondRow = nav.move(Direction.DOWN, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.GRID, 5), toSecondRow)

        val toMeta = nav.move(Direction.DOWN, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.META, 0), toMeta)

        // META -> GRID on UP
        val backToGrid = nav.move(Direction.UP, gridCols = 4, gridCount = 8)
        assertEquals(FocusTarget(Zone.GRID, 7), backToGrid)
    }
}
