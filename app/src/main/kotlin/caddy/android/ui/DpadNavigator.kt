package caddy.android.ui

enum class ScreenMode {
    BROWSE, QUEUE, SOURCES, FIRST_RUN
}

enum class Zone {
    RAIL, SEARCH, FILTERS, LIST, DETAIL, QHEAD, QLIST, FIELD, GRID, META, CENTER
}

enum class Direction {
    UP, DOWN, LEFT, RIGHT
}

enum class FilterMode(val label: String) {
    ALL("all"),
    ON_DISK("on disk"),
    IN_QUEUE("in queue"),
    FAILED("failed");

    fun next(): FilterMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    fun prev(): FilterMode {
        val values = entries
        return values[(ordinal - 1 + values.size) % values.size]
    }
}

data class FocusTarget(val zone: Zone, val index: Int)

class DpadNavigator {
    var screen: ScreenMode = ScreenMode.BROWSE
    var focus: FocusTarget = FocusTarget(Zone.LIST, 0)
    var filter: FilterMode = FilterMode.ALL

    private var lastBackPressMs: Long = 0L

    fun move(
        dir: Direction,
        listCount: Int = 0,
        gridCols: Int = 4,
        gridCount: Int = 0,
        railCount: Int = 1
    ): FocusTarget {
        val nextFocus = when (screen) {
            ScreenMode.BROWSE -> moveBrowse(dir, listCount, railCount)
            ScreenMode.QUEUE -> moveQueue(dir, listCount, railCount)
            ScreenMode.SOURCES -> moveSources(dir, gridCols, gridCount, railCount)
            ScreenMode.FIRST_RUN -> FocusTarget(Zone.CENTER, 0)
        }
        focus = nextFocus
        return nextFocus
    }

    private fun moveBrowse(dir: Direction, listCount: Int, railCount: Int): FocusTarget {
        return when (focus.zone) {
            Zone.RAIL -> when (dir) {
                Direction.UP -> FocusTarget(Zone.RAIL, (focus.index - 1).coerceAtLeast(0))
                Direction.DOWN -> FocusTarget(Zone.RAIL, (focus.index + 1).coerceAtMost((railCount - 1).coerceAtLeast(0)))
                Direction.RIGHT -> FocusTarget(Zone.LIST, 0)
                Direction.LEFT -> focus
            }
            Zone.SEARCH -> when (dir) {
                Direction.DOWN -> FocusTarget(Zone.FILTERS, 0)
                Direction.LEFT -> FocusTarget(Zone.RAIL, 0)
                else -> focus
            }
            Zone.FILTERS -> when (dir) {
                Direction.UP -> FocusTarget(Zone.SEARCH, 0)
                Direction.DOWN -> FocusTarget(Zone.LIST, 0)
                Direction.LEFT -> if (focus.index == 0) FocusTarget(Zone.RAIL, 0) else FocusTarget(Zone.FILTERS, focus.index - 1)
                Direction.RIGHT -> FocusTarget(Zone.FILTERS, focus.index + 1)
            }
            Zone.LIST -> when (dir) {
                Direction.UP -> if (focus.index == 0) FocusTarget(Zone.FILTERS, 0) else FocusTarget(Zone.LIST, focus.index - 1)
                Direction.DOWN -> {
                    val maxIdx = (listCount - 1).coerceAtLeast(0)
                    FocusTarget(Zone.LIST, (focus.index + 1).coerceAtMost(maxIdx))
                }
                Direction.LEFT -> FocusTarget(Zone.RAIL, 0)
                Direction.RIGHT -> FocusTarget(Zone.DETAIL, 0)
            }
            Zone.DETAIL -> when (dir) {
                Direction.LEFT -> FocusTarget(Zone.LIST, focus.index)
                else -> focus
            }
            else -> focus
        }
    }

    private fun moveQueue(dir: Direction, listCount: Int, railCount: Int): FocusTarget {
        return when (focus.zone) {
            Zone.RAIL -> when (dir) {
                Direction.UP -> FocusTarget(Zone.RAIL, (focus.index - 1).coerceAtLeast(0))
                Direction.DOWN -> FocusTarget(Zone.RAIL, (focus.index + 1).coerceAtMost((railCount - 1).coerceAtLeast(0)))
                Direction.RIGHT -> FocusTarget(Zone.QLIST, 0)
                Direction.LEFT -> focus
            }
            Zone.QHEAD -> when (dir) {
                Direction.DOWN -> FocusTarget(Zone.QLIST, 0)
                Direction.LEFT -> if (focus.index == 0) FocusTarget(Zone.RAIL, 0) else FocusTarget(Zone.QHEAD, 0)
                Direction.RIGHT -> FocusTarget(Zone.QHEAD, 1)
                else -> focus
            }
            Zone.QLIST -> when (dir) {
                Direction.UP -> if (focus.index == 0) FocusTarget(Zone.QHEAD, 0) else FocusTarget(Zone.QLIST, focus.index - 1)
                Direction.DOWN -> {
                    val maxIdx = (listCount - 1).coerceAtLeast(0)
                    FocusTarget(Zone.QLIST, (focus.index + 1).coerceAtMost(maxIdx))
                }
                Direction.LEFT -> FocusTarget(Zone.RAIL, 0)
                else -> focus
            }
            else -> focus
        }
    }

    private fun moveSources(dir: Direction, gridCols: Int, gridCount: Int, railCount: Int): FocusTarget {
        return when (focus.zone) {
            Zone.RAIL -> when (dir) {
                Direction.UP -> FocusTarget(Zone.RAIL, (focus.index - 1).coerceAtLeast(0))
                Direction.DOWN -> FocusTarget(Zone.RAIL, (focus.index + 1).coerceAtMost((railCount - 1).coerceAtLeast(0)))
                Direction.RIGHT -> FocusTarget(Zone.FIELD, 0)
                Direction.LEFT -> focus
            }
            Zone.FIELD -> when (dir) {
                Direction.DOWN -> FocusTarget(Zone.GRID, 0)
                Direction.LEFT -> FocusTarget(Zone.RAIL, 0)
                else -> focus
            }
            Zone.GRID -> {
                val cols = gridCols.coerceAtLeast(1)
                val total = gridCount.coerceAtLeast(1)
                val cur = focus.index
                val col = cur % cols
                val row = cur / cols
                val totalRows = (total + cols - 1) / cols

                when (dir) {
                    Direction.UP -> if (row == 0) FocusTarget(Zone.FIELD, 0) else FocusTarget(Zone.GRID, cur - cols)
                    Direction.DOWN -> if (row == totalRows - 1 || cur + cols >= total) FocusTarget(Zone.META, 0) else FocusTarget(Zone.GRID, (cur + cols).coerceAtMost(total - 1))
                    Direction.LEFT -> if (col == 0) FocusTarget(Zone.RAIL, 0) else FocusTarget(Zone.GRID, cur - 1)
                    Direction.RIGHT -> if (cur + 1 < total && col + 1 < cols) FocusTarget(Zone.GRID, cur + 1) else focus
                }
            }
            Zone.META -> when (dir) {
                Direction.UP -> FocusTarget(Zone.GRID, (gridCount - 1).coerceAtLeast(0))
                Direction.LEFT -> FocusTarget(Zone.RAIL, 0)
                else -> focus
            }
            else -> focus
        }
    }

    fun handleBack(currentTimeMs: Long): Boolean {
        if (focus.zone == Zone.DETAIL) {
            focus = FocusTarget(Zone.LIST, focus.index)
            return false
        }

        if (focus.zone == Zone.LIST || focus.zone == Zone.FILTERS || focus.zone == Zone.SEARCH) {
            focus = FocusTarget(Zone.RAIL, 0)
            return false
        }

        if (screen != ScreenMode.BROWSE) {
            screen = ScreenMode.BROWSE
            focus = FocusTarget(Zone.LIST, 0)
            return false
        }

        // At root: RAIL on BROWSE (or other unhandled zone on BROWSE)
        val elapsed = currentTimeMs - lastBackPressMs
        if (lastBackPressMs != 0L && elapsed in 1..2000) {
            lastBackPressMs = 0L
            return true
        }

        lastBackPressMs = currentTimeMs
        return false
    }
}
