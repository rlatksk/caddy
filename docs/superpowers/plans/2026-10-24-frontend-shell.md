# Frontend Shell & Controller Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the unified Android landscape frontend shell for Retroid Pocket 6 (1920×1080 @ 369 dpi, 832×444 dp) with spatial D-pad controller navigation, Rail, Browse with live Detail pane and Libretro poster cache, Queue with character-cell progress, Sources settings, and FirstRun pane, consolidating the app into a single activity.

**Architecture:** A single container activity (`MainActivity`) with a fixed 88 dp left `RailView` and dynamic right content panel (`BrowsePane`, `QueuePane`, `SourcesPane`, `FirstRunPane`). All UI built programmatically with flat 0-radius Android Views, 1 px hairlines, and monospace text. Hardware controller input routed through a pure Kotlin spatial state machine (`DpadNavigator`).

**Tech Stack:** Kotlin 2.4, Android SDK (compileSdk 36, minSdk 26), Android Views & RecyclerView, OkHttp 3, Coroutines.

**Spec:** `docs/superpowers/specs/2026-10-24-frontend-shell-design.md`

## Global Constraints

- **Platform:** Android, landscape only, 12 dp safe-area padding on all sides (`832 × 444 dp` app frame).
- **Views only:** No Compose, no external image libraries. Hand-rolled poster cache.
- **Palette tokens:** Ground `#08090a`, Panel `#0b0c0d`, Field `#0e1011`, Line `#1c1e1f`, Line-soft `#141617`, Ink `#d7d9d6`, Mute `#7d827c`, Amber `#f0b429`, Green `#3ecf72`, Red `#ff6b6e`, Hold `#8bb4e6`.
- **Typography:** Monospace typeface throughout. Minimum font floor is 11 dp.
- **Selection:** Inverts completely (ground becomes `ink`, text becomes `ground`).
- **Controller buttons:** D-pad for spatial movement, A for forward/action, B for back, L1/R1 for filter cycle, X for queue, Y for search. Double-tap B within 2000 ms to exit from root.

---

### Task 1: UI Theme Tokens and View Helpers

**Files:**
- Create: `app/src/main/kotlin/caddy/android/ui/CaddyTheme.kt`
- Test: `app/src/test/kotlin/caddy/android/CaddyThemeTest.kt`

**Interfaces:**
- Consumes: Nothing
- Produces: Color constants, dp-to-px converters, border drawables, inverted selection drawables.

- [ ] **Step 1: Write unit test for CaddyTheme color tokens and dp conversion**

Create `app/src/test/kotlin/caddy/android/CaddyThemeTest.kt`:
```kotlin
package caddy.android

import caddy.android.ui.CaddyTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class CaddyThemeTest {
    @Test
    fun testColorTokens() {
        assertEquals(0xFF08090A.toInt(), CaddyTheme.GROUND)
        assertEquals(0xFF0B0C0D.toInt(), CaddyTheme.PANEL)
        assertEquals(0xFF0E1011.toInt(), CaddyTheme.FIELD)
        assertEquals(0xFF1C1E1F.toInt(), CaddyTheme.LINE)
        assertEquals(0xFF141617.toInt(), CaddyTheme.LINE_SOFT)
        assertEquals(0xFFD7D9D6.toInt(), CaddyTheme.INK)
        assertEquals(0xFF7D827C.toInt(), CaddyTheme.MUTE)
        assertEquals(0xFFF0B429.toInt(), CaddyTheme.AMBER)
        assertEquals(0xFF3ECF72.toInt(), CaddyTheme.GREEN)
        assertEquals(0xFFFF6B6E.toInt(), CaddyTheme.RED)
        assertEquals(0xFF8BB4E6.toInt(), CaddyTheme.HOLD)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:test --tests caddy.android.CaddyThemeTest`
Expected: FAIL (class `CaddyTheme` does not exist).

- [ ] **Step 3: Write CaddyTheme implementation**

Create `app/src/main/kotlin/caddy/android/ui/CaddyTheme.kt`:
```kotlin
package caddy.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View

object CaddyTheme {
    val GROUND = 0xFF08090A.toInt()
    val PANEL = 0xFF0B0C0D.toInt()
    val FIELD = 0xFF0E1011.toInt()
    val LINE = 0xFF1C1E1F.toInt()
    val LINE_SOFT = 0xFF141617.toInt()
    val INK = 0xFFD7D9D6.toInt()
    val MUTE = 0xFF7D827C.toInt()
    val AMBER = 0xFFF0B429.toInt()
    val GREEN = 0xFF3ECF72.toInt()
    val RED = 0xFFFF6B6E.toInt()
    val HOLD = 0xFF8BB4E6.toInt()

    fun dp(context: Context, value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).toInt()
    }

    fun border(color: Int, borderWidth: Int = 1, backgroundColor: Int = Color.TRANSPARENT): Drawable {
        return GradientDrawable().apply {
            setColor(backgroundColor)
            setStroke(borderWidth, color)
        }
    }

    fun bottomBorder(lineColor: Int, lineWidth: Int = 1, backgroundColor: Int = Color.TRANSPARENT): Drawable {
        val bg = ColorDrawable(backgroundColor)
        val line = ColorDrawable(lineColor)
        val layers = arrayOf(bg, line)
        return LayerDrawable(layers).apply {
            setLayerInset(0, 0, 0, 0, 0)
            setLayerInset(1, 0, 0, 0, 0) // adjusted dynamically or used via standard divider
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :app:test --tests caddy.android.CaddyThemeTest`
Expected: PASS

---

### Task 2: Spatial D-pad State Navigator and Key Handler

**Files:**
- Create: `app/src/main/kotlin/caddy/android/ui/DpadNavigator.kt`
- Test: `app/src/test/kotlin/caddy/android/DpadNavigatorTest.kt`

**Interfaces:**
- Consumes: Nothing (pure Kotlin)
- Produces: `DpadNavigator`, `Zone`, `FocusTarget`, `FilterMode`, `ScreenMode`

- [ ] **Step 1: Write DpadNavigator unit tests**

Create `app/src/test/kotlin/caddy/android/DpadNavigatorTest.kt`:
```kotlin
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
        nav.screen = ScreenMode.BROWSE
        nav.focus = FocusTarget(Zone.RAIL, 0)

        // Right from Rail moves to List
        val target1 = nav.move(Direction.RIGHT, listCount = 10, gridCols = 4, gridCount = 8)
        assertEquals(Zone.LIST, target1.zone)
        assertEquals(0, target1.index)

        // Left from List moves back to Rail
        nav.focus = target1
        val target2 = nav.move(Direction.LEFT, listCount = 10, gridCols = 4, gridCount = 8)
        assertEquals(Zone.RAIL, target2.zone)
    }

    @Test
    fun testFilterRotation() {
        assertEquals(FilterMode.ON_DISK, FilterMode.ALL.next())
        assertEquals(FilterMode.IN_QUEUE, FilterMode.ON_DISK.next())
        assertEquals(FilterMode.FAILED, FilterMode.IN_QUEUE.next())
        assertEquals(FilterMode.ALL, FilterMode.FAILED.next())

        assertEquals(FilterMode.FAILED, FilterMode.ALL.prev())
        assertEquals(FilterMode.IN_QUEUE, FilterMode.FAILED.prev())
    }

    @Test
    fun testDoubleBackGuard() {
        val nav = DpadNavigator()
        nav.screen = ScreenMode.BROWSE
        nav.focus = FocusTarget(Zone.RAIL, 0)

        // First back at root: does not exit, sets timestamp
        val exit1 = nav.handleBack(currentTimeMs = 1000)
        assertFalse(exit1)

        // Second back within 2000ms: exits
        val exit2 = nav.handleBack(currentTimeMs = 2500)
        assertTrue(exit2)

        // Back after > 2000ms: does not exit
        val exit3 = nav.handleBack(currentTimeMs = 5000)
        assertFalse(exit3)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:test --tests caddy.android.DpadNavigatorTest`
Expected: FAIL (DpadNavigator does not exist).

- [ ] **Step 3: Implement DpadNavigator**

Create `app/src/main/kotlin/caddy/android/ui/DpadNavigator.kt`:
```kotlin
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
        val z = focus.zone
        val i = focus.index

        val next = when (screen) {
            ScreenMode.BROWSE -> moveBrowse(dir, z, i, listCount, railCount)
            ScreenMode.QUEUE -> moveQueue(dir, z, i, listCount, railCount)
            ScreenMode.SOURCES -> moveSources(dir, z, i, gridCols, gridCount, railCount)
            ScreenMode.FIRST_RUN -> FocusTarget(Zone.CENTER, 0)
        }
        focus = next
        return next
    }

    private fun moveBrowse(dir: Direction, z: Zone, i: Int, listCount: Int, railCount: Int): FocusTarget {
        return when (z) {
            Zone.RAIL -> when (dir) {
                Direction.UP -> FocusTarget(Zone.RAIL, (i - 1).coerceAtLeast(0))
                Direction.DOWN -> FocusTarget(Zone.RAIL, (i + 1).coerceAtMost(railCount - 1))
                Direction.RIGHT -> FocusTarget(Zone.LIST, 0)
                Direction.LEFT -> FocusTarget(Zone.RAIL, i)
            }
            Zone.SEARCH -> when (dir) {
                Direction.DOWN -> FocusTarget(Zone.FILTERS, 0)
                Direction.LEFT -> FocusTarget(Zone.RAIL, 0)
                else -> FocusTarget(Zone.SEARCH, 0)
            }
            Zone.FILTERS -> when (dir) {
                Direction.UP -> FocusTarget(Zone.SEARCH, 0)
                Direction.DOWN -> FocusTarget(Zone.LIST, 0)
                Direction.LEFT -> if (i > 0) FocusTarget(Zone.FILTERS, i - 1) else FocusTarget(Zone.RAIL, 0)
                Direction.RIGHT -> FocusTarget(Zone.FILTERS, (i + 1).coerceAtMost(FilterMode.entries.size - 1))
            }
            Zone.LIST -> when (dir) {
                Direction.UP -> if (i > 0) FocusTarget(Zone.LIST, i - 1) else FocusTarget(Zone.FILTERS, 0)
                Direction.DOWN -> if (listCount > 0) FocusTarget(Zone.LIST, (i + 1).coerceAtMost(listCount - 1)) else FocusTarget(Zone.LIST, 0)
                Direction.LEFT -> FocusTarget(Zone.RAIL, 0)
                Direction.RIGHT -> FocusTarget(Zone.DETAIL, 0)
            }
            Zone.DETAIL -> when (dir) {
                Direction.LEFT -> FocusTarget(Zone.LIST, i)
                else -> FocusTarget(Zone.DETAIL, 0)
            }
            else -> FocusTarget(Zone.LIST, 0)
        }
    }

    private fun moveQueue(dir: Direction, z: Zone, i: Int, qCount: Int, railCount: Int): FocusTarget {
        return when (z) {
            Zone.RAIL -> when (dir) {
                Direction.UP -> FocusTarget(Zone.RAIL, (i - 1).coerceAtLeast(0))
                Direction.DOWN -> FocusTarget(Zone.RAIL, (i + 1).coerceAtMost(railCount - 1))
                Direction.RIGHT -> FocusTarget(Zone.QLIST, 0)
                Direction.LEFT -> FocusTarget(Zone.RAIL, i)
            }
            Zone.QHEAD -> when (dir) {
                Direction.DOWN -> FocusTarget(Zone.QLIST, 0)
                Direction.LEFT -> if (i > 0) FocusTarget(Zone.QHEAD, i - 1) else FocusTarget(Zone.RAIL, 0)
                Direction.RIGHT -> FocusTarget(Zone.QHEAD, (i + 1).coerceAtMost(1))
                Direction.UP -> FocusTarget(Zone.QHEAD, i)
            }
            Zone.QLIST -> when (dir) {
                Direction.UP -> if (i > 0) FocusTarget(Zone.QLIST, i - 1) else FocusTarget(Zone.QHEAD, 0)
                Direction.DOWN -> if (qCount > 0) FocusTarget(Zone.QLIST, (i + 1).coerceAtMost(qCount - 1)) else FocusTarget(Zone.QLIST, 0)
                Direction.LEFT -> FocusTarget(Zone.RAIL, 0)
                Direction.RIGHT -> FocusTarget(Zone.QLIST, i)
            }
            else -> FocusTarget(Zone.QLIST, 0)
        }
    }

    private fun moveSources(dir: Direction, z: Zone, i: Int, cols: Int, gridCount: Int, railCount: Int): FocusTarget {
        return when (z) {
            Zone.RAIL -> when (dir) {
                Direction.RIGHT -> FocusTarget(Zone.FIELD, 0)
                Direction.UP -> FocusTarget(Zone.RAIL, (i - 1).coerceAtLeast(0))
                Direction.DOWN -> FocusTarget(Zone.RAIL, (i + 1).coerceAtMost(railCount - 1))
                Direction.LEFT -> FocusTarget(Zone.RAIL, i)
            }
            Zone.FIELD -> when (dir) {
                Direction.DOWN -> if (gridCount > 0) FocusTarget(Zone.GRID, 0) else FocusTarget(Zone.META, 0)
                Direction.LEFT -> FocusTarget(Zone.RAIL, 0)
                else -> FocusTarget(Zone.FIELD, 0)
            }
            Zone.GRID -> when (dir) {
                Direction.UP -> if (i - cols >= 0) FocusTarget(Zone.GRID, i - cols) else FocusTarget(Zone.FIELD, 0)
                Direction.DOWN -> if (i + cols < gridCount) FocusTarget(Zone.GRID, i + cols) else FocusTarget(Zone.META, 0)
                Direction.LEFT -> if (i % cols > 0) FocusTarget(Zone.GRID, i - 1) else FocusTarget(Zone.RAIL, 0)
                Direction.RIGHT -> if (i + 1 < gridCount && (i + 1) % cols != 0) FocusTarget(Zone.GRID, i + 1) else FocusTarget(Zone.GRID, i)
            }
            Zone.META -> when (dir) {
                Direction.UP -> if (gridCount > 0) FocusTarget(Zone.GRID, gridCount - 1) else FocusTarget(Zone.FIELD, 0)
                Direction.LEFT -> FocusTarget(Zone.RAIL, 0)
                else -> FocusTarget(Zone.META, 0)
            }
            else -> FocusTarget(Zone.FIELD, 0)
        }
    }

    fun handleBack(currentTimeMs: Long): Boolean {
        // If in detail, step back to list
        if (focus.zone == Zone.DETAIL) {
            focus = FocusTarget(Zone.LIST, focus.index)
            return false
        }
        // If in list or filters or search, step back to rail
        if (focus.zone in listOf(Zone.LIST, Zone.FILTERS, Zone.SEARCH)) {
            focus = FocusTarget(Zone.RAIL, 0)
            return false
        }
        // If in another screen, step back to Browse
        if (screen != ScreenMode.BROWSE) {
            screen = ScreenMode.BROWSE
            focus = FocusTarget(Zone.LIST, 0)
            return false
        }

        // At root level (RAIL on Browse screen): double-tap check
        val diff = currentTimeMs - lastBackPressMs
        lastBackPressMs = currentTimeMs
        return diff in 1..2000
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :app:test --tests caddy.android.DpadNavigatorTest`
Expected: PASS

---

### Task 3: Libretro Poster Cache and Platform Mapper

**Files:**
- Create: `app/src/main/kotlin/caddy/android/ui/PosterCache.kt`
- Test: `app/src/test/kotlin/caddy/android/PosterCacheTest.kt`

**Interfaces:**
- Consumes: OkHttp, Android Bitmap
- Produces: `PosterCache`, `LibretroPlatform.resolve(systemId, filename)`

- [ ] **Step 1: Write PosterCacheTest**

Create `app/src/test/kotlin/caddy/android/PosterCacheTest.kt`:
```kotlin
package caddy.android

import caddy.android.ui.LibretroPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterCacheTest {
    @Test
    fun testPlatformMapping() {
        assertEquals("Nintendo - Game Boy Advance", LibretroPlatform.folder("gba"))
        assertEquals("Sega - Mega Drive - Genesis", LibretroPlatform.folder("genesis"))
        assertEquals("Sega - Mega Drive - Genesis", LibretroPlatform.folder("gen"))
        assertEquals("Sega - Mega Drive - Genesis", LibretroPlatform.folder("megadrive"))
        assertEquals("Nintendo - Super Nintendo Entertainment System", LibretroPlatform.folder("snes"))
        assertEquals("Sony - PlayStation", LibretroPlatform.folder("psx"))
        assertEquals("Nintendo - Nintendo 64", LibretroPlatform.folder("n64"))
    }

    @Test
    fun testThumbnailUrlResolution() {
        val url = LibretroPlatform.thumbnailUrl("gba", "Pokemon - Emerald Version (USA, Europe).zip")
        assertEquals(
            "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts/Pokemon%20-%20Emerald%20Version%20(USA%2C%20Europe).png",
            url
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:test --tests caddy.android.PosterCacheTest`
Expected: FAIL (LibretroPlatform does not exist).

- [ ] **Step 3: Implement LibretroPlatform & PosterCache**

Create `app/src/main/kotlin/caddy/android/ui/PosterCache.kt`:
```kotlin
package caddy.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

object LibretroPlatform {
    private val MAP = mapOf(
        "gba" to "Nintendo - Game Boy Advance",
        "gb" to "Nintendo - Game Boy",
        "gbc" to "Nintendo - Game Boy Color",
        "nes" to "Nintendo - Nintendo Entertainment System",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "n64" to "Nintendo - Nintendo 64",
        "nds" to "Nintendo - Nintendo DS",
        "genesis" to "Sega - Mega Drive - Genesis",
        "gen" to "Sega - Mega Drive - Genesis",
        "megadrive" to "Sega - Mega Drive - Genesis",
        "mastersystem" to "Sega - Master System - Mark III",
        "gamegear" to "Sega - Game Gear",
        "psx" to "Sony - PlayStation",
        "ps2" to "Sony - PlayStation 2",
        "psp" to "Sony - PlayStation Portable",
        "saturn" to "Sega - Saturn",
        "dreamcast" to "Sega - Dreamcast",
        "neogeo" to "SNK - Neo Geo",
        "tg16" to "NEC - PC Engine - TurboGrafx 16",
        "pce" to "NEC - PC Engine - TurboGrafx 16"
    )

    fun folder(systemId: String): String {
        return MAP[systemId.lowercase()] ?: systemId
    }

    fun cleanName(filename: String): String {
        val dot = filename.lastIndexOf('.')
        return if (dot > 0) filename.substring(0, dot) else filename
    }

    fun thumbnailUrl(systemId: String, filename: String): String {
        val platform = folder(systemId)
        val name = cleanName(filename)
        val encPlatform = URLEncoder.encode(platform, "UTF-8").replace("+", "%20")
        val encName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "https://thumbnails.libretro.com/$encPlatform/Named_Boxarts/$encName.png"
    }
}

class PosterCache(context: Context, private val http: OkHttpClient) {
    private val diskDir = File(context.cacheDir, "posters").apply { mkdirs() }
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8 // 1/8th of memory

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    suspend fun getPoster(systemId: String, filename: String, targetWidth: Int, targetHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        val url = LibretroPlatform.thumbnailUrl(systemId, filename)
        val key = Integer.toHexString(url.hashCode())

        // 1. Check memory cache
        memoryCache.get(key)?.let { return@withContext it }

        // 2. Check disk cache
        val diskFile = File(diskDir, "$key.png")
        if (diskFile.exists()) {
            val bitmap = decodeSampledBitmap(diskFile.absolutePath, targetWidth, targetHeight)
            if (bitmap != null) {
                memoryCache.put(key, bitmap)
                return@withContext bitmap
            }
        }

        // 3. Network fetch
        try {
            val request = Request.Builder().url(url).build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val bytes = response.body?.bytes() ?: return@withContext null

            // Write to disk
            FileOutputStream(diskFile).use { it.write(bytes) }

            // Decode downsampled
            val bitmap = decodeSampledBitmap(diskFile.absolutePath, targetWidth, targetHeight)
            if (bitmap != null) {
                memoryCache.put(key, bitmap)
            }
            return@withContext bitmap
        } catch (_: Exception) {
            return@withContext null
        }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)

        var inSampleSize = 1
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        return BitmapFactory.decodeFile(path, options)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :app:test --tests caddy.android.PosterCacheTest`
Expected: PASS

---

### Task 4: Left Rail View Component

**Files:**
- Create: `app/src/main/kotlin/caddy/android/ui/RailView.kt`

**Interfaces:**
- Consumes: `CaddyTheme`, `ScreenMode`
- Produces: `RailView(context)` with `setSystems(list)`, `setSelected(index)`, `setQueueHasActive(boolean)`

- [ ] **Step 1: Implement RailView**

Create `app/src/main/kotlin/caddy/android/ui/RailView.kt`:
- Width fixed to 88 dp (`CaddyTheme.dp(context, 88f)`).
- Vertical LinearLayout with `#08090a` background, 1 px right border (`#1c1e1f`).
- ScrollView with systems list: system codes uppercase, 12 dp monospace.
- Selected system item inverts (`#d7d9d6` background, `#08090a` text).
- Footer area with two buttons: Queue icon (with amber dot if downloading) and Sources icon.

---

### Task 5: Browse Pane and Game Detail View

**Files:**
- Create: `app/src/main/kotlin/caddy/android/ui/BrowsePane.kt`

**Interfaces:**
- Consumes: `CatalogEntry`, `CaddyTheme`, `FilterMode`, `PosterCache`
- Produces: `BrowsePane(context)` with `setCatalog(entries, romsDir)`, `setFilter(mode)`, `setSearchQuery(q)`, `focusZone(target)`

- [ ] **Step 1: Implement BrowsePane with Fixed Row Height RecyclerView and Detail Pane**

Create `app/src/main/kotlin/caddy/android/ui/BrowsePane.kt`:
- **Header:** 46 dp height. System title and subtitle left, 196 dp search box right.
- **Filter Row:** 23 dp height. Chips: `all (N)`, `on disk (N)`, `in queue (N)`, `failed (N)`. Active chip has inverted pill background.
- **RecyclerView:** Fixed-height 34 dp items. Columns: Title (monospace 12 dp), Size, Status (`ok`, `run`, `que`, `pau`, `err`). Focused row inverts completely.
- **Detail Pane:** 300 dp width right side. Poster box (140×110 dp), Title/Meta, Dotted specs, Synopsis clamped to 4 lines, and full-width Primary Action Button (`[DOWNLOAD]`, `[PAUSE]`, `[RESUME]`, `[RETRY]`).

---

### Task 6: Queue Pane and Sources Pane

**Files:**
- Create: `app/src/main/kotlin/caddy/android/ui/QueuePane.kt`
- Create: `app/src/main/kotlin/caddy/android/ui/SourcesPane.kt`
- Create: `app/src/main/kotlin/caddy/android/ui/FirstRunPane.kt`

**Interfaces:**
- Consumes: `DownloadQueue`, `RepoStore`
- Produces: `QueuePane`, `SourcesPane`, `FirstRunPane`

- [ ] **Step 1: Implement QueuePane**
- Fixed-height 38 dp items with character-cell progress `▮▮▮▮▮▯▯▯▯▯ 47%`, transfer rate, status badges.
- Top action buttons: `[Resume Queue]` and `[Clear Finished]`.

- [ ] **Step 2: Implement SourcesPane & FirstRunPane**
- Sources: URL/JSON inputs, 4-col system checkbox grid, metadata switch, ROMs path.
- FirstRun: Centered welcome card prompting for index URL/JSON when no repo is configured.

---

### Task 7: Integrate Consolidated MainActivity & Controller Routing

**Files:**
- Modify: `app/src/main/kotlin/caddy/android/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: All UI components (`RailView`, `BrowsePane`, `QueuePane`, `SourcesPane`, `FirstRunPane`, `DpadNavigator`, `PosterCache`)
- Produces: Complete working application shell

- [ ] **Step 1: Wire MainActivity to host the Shell, DpadNavigator, and Panes**
- Setup 12 dp inset outer container.
- Connect `RailView` on the left (88 dp) and content `FrameLayout` on the right.
- Override `dispatchKeyEvent` to capture D-pad / Arrow keys, A, B (double-tap exit), L1/R1 (filter cycle), X (queue), Y (search).
- Wire 1-second queue refresh tick to update active download states, badges, and the amber dot.
- Keep `RepoStore`, `RepoFetcher`, storage permissions, and crash logger.

- [ ] **Step 2: Run Full Compilation and All Unit Tests**

Run: `gradle compileDebugSources && gradle test`
Expected: BUILD SUCCESSFUL with all tests passing.
