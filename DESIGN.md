# Design

The app's visual contract. Derived from the shipped artifact, not from intentions:
`design/ui.html` is the reference, `design/shots/` are its captures.

**Platform:** Android, landscape only, Android Views (not Compose).
**Target screen:** Retroid Pocket 6 — 1920×1080 @ 369 dpi, app area **832 × 444 dp**.

---

## The world

A No-Intro / Redump manifest. The list *is* the document: fixed-width columns,
hairline rules, hashes, region tags. Monospace throughout, because every column
is data or measurement. Nothing is decorative; a rule earns its place by
separating two kinds of information.

Layout is a standard app shell — rail, header, list, detail. The world supplies
type, palette, density and one signature move (the verification line), never the
navigation model.

## Screen metrics

| | dp |
|---|---|
| Screen | 832 × 444 |
| Safe-area frame (all sides) | 12 |
| Rail width | 88 |
| Detail pane | 300 |
| Header bar height | 46 |
| Filter row | 23 |
| Column header row | 22 |
| Browse row | 34 |
| Verification line | 27 |
| Queue row | 38 |
| Rail footer buttons | 36 × 34 |

Content is inset 12 dp from the physical edge on every side. Nothing touches it.

## Palette

| Token | Hex | Use |
|---|---|---|
| ground | `#08090a` | screen background |
| panel | `#0b0c0d` | column header, filter row |
| field | `#0e1011` | inputs, unchecked boxes |
| line | `#1c1e1f` | panel rules |
| line-soft | `#141617` | row separators |
| ink | `#d7d9d6` | primary text, selection ground |
| mute | `#7d827c` | secondary text, labels |
| amber | `#f0b429` | running, active count |
| green | `#3ecf72` | verified, on disk |
| red | `#ff6b6e` | failed |
| hold | `#8bb4e6` | paused |

Selection inverts: ground becomes `ink`, text becomes `ground`. On an inverted
row, state colours darken (`run #8a5f00`, `done #1f6b41`, `fail #a32a2a`,
`hold #2c5c8f`) to stay legible on light.

## Type

One family: `monospace`. Tabular figures throughout.

| Role | Size | Case |
|---|---|---|
| Detail title | 14 | sentence |
| List title, rail entry | 12–12.5 | sentence |
| Row size, values | 12 | sentence |
| Column heads, labels | 11 | UPPERCASE, `.1em` |
| State codes | 11 | lowercase |
| Synopsis | 11 | sentence |

**Floor is 11 dp.** A 9–10 dp label at 369 dpi is unreadable at arm's length.

## Components

**Rail** — system codes only (`GEN`, `SNE`, `GBA`), no counts. Selected entry
inverts. Footer holds two 36×34 icon buttons: download (opens queue, amber dot
while transfers run) and sliders (opens Sources). Both invert when their screen
is open.

**Header bar** — screen name uppercase + meta line left, search field right.

**Filter row** — `all · on disk · in queue · failed`, each with a count. The
active one gets an inverted chip. Never a mystery icon.

**Browse row** — `title | size | state`. No system column; the rail already says
which system you are in.

**Detail pane** — poster, kicker, title, sub, hairline, dotted-leader key/value
rows (size, region, hash, path), hairline, synopsis clamped to 4 lines, state
line, one full-width primary button.

**Queue row** — title + note, character-cell bar `▮▮▮▮▮▯▯▯▯▯ 47%`, bytes, state,
and per-row pause/resume/remove.

**Primary button** — always white ground, dark label, centred, `8dp 16dp`
inset. Every action uses it: Download, Pause, Resume, Re-download, Retry.

## State vocabulary

Browse stays terse because the word is scanned down a column:

| Code | Meaning | Colour |
|---|---|---|
| `ok` | downloaded | green |
| `run` | downloading | amber |
| `que` | queued | grey |
| `pau` | paused | blue |
| `err` | failed | red |

The queue screen spells each one out in full (`downloading`, `waiting`,
`paused`, `on disk`, `failed`) because that is where you go to diagnose.

## Focus and the D-pad

The device has a controller. Every control is reachable without touching glass.

Spatial model, identical on every screen:

```
rail  |  search / filters
      |  list  →  detail
```

Up/down walks a column. Left walks back toward the rail. Right walks toward the
detail pane. No diagonal moves, so one D-pad press has exactly one meaning.

Key map: arrows = D-pad, `Enter` = A, `Backspace` = B, `X` = queue, `Y` = search.

Focus is a 1 px ring in `ink`, inset. On a list row the existing invert *is* the
cursor. The list scrolls to keep focus visible; an empty system parks the cursor
in the rail rather than losing it.

## Rules

- Flat. No shadows, no blur, no rounded corners above 0, no gradients except the
  poster placeholder hatch.
- No images in the list. A 2,000-row list loads zero artwork.
- No idle animation. Transitions only on state change.
- Monospace carries data. It is not decoration.
- Rules are 1 px and never coloured.
- Contrast ≥ 4.5:1 for body text.

---

## What the backend does not have yet

`CatalogEntry` carries `system, title, filename, sizeBytes, region, revision,
disc, junkTags, torrentFileIndex, sourceRef, kind, titleId, variants`. It carries
**no** description, genre, year, developer, players or artwork. Everything in the
detail pane above the synopsis is derivable from the filename and the source;
the synopsis and the poster are not.

That layer has to be built. Probed on 2026-10-23, not assumed:

| Source | Key | Gives | Measured |
|---|---|---|---|
| libretro thumbnails | none | box art | 20/26 by exact filename |
| ScreenScraper | devid + account | synopsis, genre, year, dev, players | rejects unauthenticated calls; quota per minute and per day |
| SteamGridDB | per-user key | grids, heroes, logos | 401 without a key; artwork only |
| Wikipedia summary | none | description | resolved 9/9 but wrong article 4/9 — unusable unsupervised |

**Box art, exact rule.** The catalog's filename already is the No-Intro name, so
art is a direct lookup:

```
https://thumbnails.libretro.com/<Platform>/Named_Boxarts/<filename without extension>.png
```

`<Platform>` is libretro's own folder name, not the ES-DE system name
(`Sega - Mega Drive - Genesis`, `Nintendo - Game Boy Advance`, …). The 6/26
misses came from region-suffix differences; one `GET` of the system's
`Named_Boxarts/` listing returns every valid name (Genesis: 603 KB, 2,352 names,
2.2 s) and resolves them by title match. Cache that index, then cache the image.

libretro sends **no `Cache-Control`** — the app owns the cache.

Art is fetched for the title under the cursor only, debounced ~110 ms, never per
row. The frame takes the image's own ratio (measured 0.70–1.78), capped at
110 dp tall / 140 dp wide, `object-fit: contain` → `ImageView` with
`adjustViewBounds` and a max height.

## Implementation notes

- **Views, not Compose.** The design is text and hairlines; Compose would add
  2–4 MB and startup cost for nothing. The app is Views today.
- **`RecyclerView`** with fixed-height items for the list and the queue. Update a
  row only when its string changes — `DownloadsActivity` already establishes
  this pattern.
- **D-pad:** Android's focus traversal is built for tab order, not spatial zones.
  Mirror `moveFocus()` from `design/ui.html` with a root key listener; it is ~80
  lines and predictable, where `nextFocus*` hints on dynamic views are not.
- **Poster cache:** no image library in the app. Hand-rolled is
  `OkHttp → BitmapFactory downsample → disk cache → LruCache`, ~120 lines.
- **One shell.** The prototype is one shell with swappable content; the app has
  three activities. Consolidate.

## Screens to build

1. **Browse** — rail, header, filters, list, detail (the reference screen).
2. **Queue** — bulk pause/clear plus per-transfer actions.
3. **Sources (settings)** — repository URL, system checkboxes, metadata toggle,
   ROMs root. Reached from the rail footer.
4. **First run** — add a repository; ships no sources.