# Unified Frontend Shell & Controller Navigation Design

- **Date:** 2026-10-24
- **Platform:** Android 8+ (minSdk 26, targetSdk 36), Retroid Pocket 6 (1920×1080 @ 369 dpi, 832×444 dp app frame)
- **Visual Reference:** `DESIGN.md`, `design/ui.html`, `design/shots/`
- **Product Reference:** `PRODUCT.md`

## 1. Goal & Architecture Overview

Consolidate the multi-activity setup (`MainActivity`, `BrowseActivity`, `DownloadsActivity`) into a single high-performance `MainActivity` shell using programmatic Android Views (`LinearLayout`, `FrameLayout`, `RecyclerView`, `TextView`), strictly flat 0-radius styling, and full D-pad spatial controller routing.

### 1.1 Core Layout Shell
- **App Boundary:** Fixed landscape, 12 dp safe-area frame inset on all four sides (`832 × 444 dp` usable area).
- **Background:** `#08090a` (ground), primary text `#d7d9d6` (ink), secondary `#7d827c` (mute), hairline rules `#1c1e1f` (1 px).
- **Font:** Monospace with tabular numerals throughout. Minimum font floor is 11 dp.
- **Left Rail (88 dp):**
  - Vertically scrollable system list displaying system codes (`GBA`, `GEN`, `SNES`, `NES`, `PSX`, etc.).
  - Active/selected system row inverts (`#d7d9d6` ground, `#08090a` text).
  - Footer (34 dp height) with two action buttons:
    - Queue button: inverts when Queue screen is active; shows amber dot (`#f0b429`) when downloads are active.
    - Sources button: inverts when Sources screen is active.
- **Main Content Area (~720 dp):**
  - Dynamically hosts one of four screen panes: `BrowsePane`, `QueuePane`, `SourcesPane`, or `FirstRunPane`.

---

## 2. Controller Navigation & Input Model

### 2.1 Hardware Keys & Controller Buttons
- **D-pad / Arrow Keys:** Spatial navigation between zones (`moveFocus(dir)`).
- **Button A (`KEYCODE_BUTTON_A` / `KEYCODE_ENTER`):**
  - In Rail: activates selected system, loads catalog, transfers focus to Game List.
  - In Game List: transfers focus directly to Detail Pane Primary Button.
  - In Detail Pane: triggers primary action (`Download`, `Pause`, `Resume`, `Retry`).
  - In Queue Row: pauses or resumes that individual transfer.
  - In Sources Grid: toggles system checkbox.
- **Button B (`KEYCODE_BUTTON_B` / `KEYCODE_BACK` / `KEYCODE_ESCAPE`):**
  - In Detail Pane: steps focus back to Game List.
  - In Game List: steps focus back to Rail.
  - In Queue / Sources Pane: returns immediately to Browse.
  - In Rail / Root Browse: requires **double-tap within 2000 ms** to exit app (with visual warning notice).
- **L1 / R1 (`KEYCODE_BUTTON_L1` / `KEYCODE_BUTTON_R1`):**
  - In Browse: instantly cycles filter modes (`all` $\leftrightarrow$ `on disk` $\leftrightarrow$ `in queue` $\leftrightarrow$ `failed`), updating list and counts immediately without moving focus.
- **Button X (`KEYCODE_BUTTON_X` / `X` key):** Jump directly to Queue pane.
- **Button Y (`KEYCODE_BUTTON_Y` / `Y` key):** Jump focus directly into Search query input.

### 2.2 Spatial Zones Matrix
```
Browse:   rail  |  search
                |  filters
                |  list  ->  detail

Queue:    rail  |  qhead (resume all, clear)
                |  qlist (transfers)

Sources:  rail  |  field (URL / JSON / ROMs root)
                |  grid (4-col system toggle chips)
                |  meta (metadata toggle & actions)

FirstRun: center (URL / JSON input & submit)
```

Focus state is tracked as `(zone: Zone, index: Int)`:
- In list rows: focus inverts the whole row (`#d7d9d6` background, `#08090a` text).
- In buttons and text fields: focus applies a 1 px `#d7d9d6` outline.
- Lists automatically scroll to keep focused index visible.

---

## 3. Screen Specifications

### 3.1 Browse Pane
- **Header Bar (46 dp):**
  - Left: System name uppercase (12 dp) + meta line (game count, source name) (11 dp).
  - Right: Search input field (196 dp wide, `#0e1011` bg, 12 dp font).
- **Filter Row (23 dp):**
  - Background `#0b0c0d`, 1 px line below.
  - Chips: `all (N)  ·  on disk (N)  ·  in queue (N)  ·  failed (N)`.
  - Active chip has inverted pill background (`#d7d9d6` bg, `#08090a` text).
- **Game List (`RecyclerView`, 34 dp fixed row height):**
  - Column 1: Game title (monospace 12 dp, single line, ellipsize end).
  - Column 2: Formatted file size (monospace 12 dp tabular, right-aligned).
  - Column 3: Status code: `ok` (green `#3ecf72`), `run` (amber `#f0b429`), `que` (mute `#7d827c`), `pau` (blue `#8bb4e6`), `err` (red `#ff6b6e`).
- **Detail Pane (300 dp fixed width):**
  - Box art frame: max 140 dp wide × 110 dp tall, `fitCenter`, diagonal hatch placeholder when loading or unavailable.
  - Title & Subtitle / region / revision tags.
  - Dotted-leader metadata rows:
    - `size .......... 12.4 MB`
    - `region ........ USA`
    - `hash/crc32 ... 8A3F109B`
    - `path .......... /ROMs/GBA/...`
  - Synopsis: clamped to 4 lines, 11 dp monospace.
  - State notice line.
  - Primary Action Button: full-width white button (`#d7d9d6` bg, `#08090a` text, 12.5 dp bold), dynamic label based on download state.

### 3.2 Queue Pane
- **Header Bar:** Upper action buttons: `[Resume Queue]` (starts service) and `[Clear Finished]` (removes `done`/`failed` rows).
- **Queue List (`RecyclerView`, 38 dp fixed row height):**
  - Character progress bar: e.g. `▮▮▮▮▮▯▯▯▯▯ 47%`.
  - Title, byte progress / transfer rate, status badge (`downloading`, `waiting`, `paused`, `on disk`, `failed`).
  - Row action toggle on Button A.

### 3.3 Sources Pane
- Repository URL field + JSON paste area.
- 4-column system checkbox grid (toggle enabled systems).
- Metadata toggle switch.
- ROMs root path field.

### 3.4 First Run Pane
- Triggered when `store.indexJson` is null or empty.
- Centered card prompting for repo URL or index JSON with submit action.

---

## 4. Libretro Poster Cache (`PosterCache`)

- **URL Pattern:**
  `https://thumbnails.libretro.com/<Platform>/Named_Boxarts/<filename without extension>.png`
- **Platform Mapping (`LibretroPlatform`):**
  Maps repository system codes (from `systems.json`) to official Libretro folder names:
  - `gba` $\to$ `Nintendo - Game Boy Advance`
  - `snes` $\to$ `Nintendo - Super Nintendo Entertainment System`
  - `nes` $\to$ `Nintendo - Nintendo Entertainment System`
  - `gb` $\to$ `Nintendo - Game Boy`
  - `gbc` $\to$ `Nintendo - Game Boy Color`
  - `genesis` / `gen` / `megadrive` $\to$ `Sega - Mega Drive - Genesis`
  - `psx` $\to$ `Sony - PlayStation`
  - `n64` $\to$ `Nintendo - Nintendo 64`
  - `nds` $\to$ `Nintendo - Nintendo DS`
  - `psp` $\to$ `Sony - PlayStation Portable`
  - (and standard fallbacks)
- **Caching Mechanism:**
  - Memory: `LruCache<String, Bitmap>` capped at 16 MB.
  - Disk: Cached under `context.cacheDir/posters/<sha256>.png`.
  - Network: Downsampled on load using `BitmapFactory.Options.inSampleSize` to target dimensions (max 140×110 dp).
  - Debounce: Selection change is debounced by 110 ms before issuing request.

---

## 5. Verification & Testing

1. **`DpadNavigatorTest`:**
   - Zone transitions: `rail` $\leftrightarrow$ `list` $\leftrightarrow$ `detail`.
   - L1 / R1 filter rotation: `ALL` $\to$ `ONDISK` $\to$ `INQUEUE` $\to$ `FAILED` $\to$ `ALL`.
   - Back double-tap timeout: second press within 2000 ms returns true (exit), after 2000 ms resets guard.
2. **`LibretroMapperTest`:**
   - Validates mapping from system codes in `schema/systems.json` to Libretro folder names.
   - Validates sanitization of ROM filenames to thumbnail image paths.
3. **Compile and Build:**
   - `gradle compileDebugSources` and `gradle test` run clean.
