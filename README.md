# Caddy

A controller-first download engine for Android retro handhelds. The app ships
**no sources** — users add repositories the way Mihon users add extension repos.

This repo currently holds the contract and the spike the PRD says to build first:

| Path | What |
|---|---|
| `schema/index.schema.json` | The repo format. This is the project's public API. |
| `schema/systems.json` | 195 ES-DE system names + display titles, extracted from ES-DE's Android `es_systems.xml`. |
| `repo/index.json` | **Not in this repository.** Local-only source list. |
| `tools/gen_repo.py` | Builds that local list from live probes. |
| `tools/validate_repo.py` | Offline validator: JSON Schema + ES-DE names + config/systems agreement. |
| `core/` | Kotlin: index parsing, catalog dedupe, adapters, download+verify+place. |
| `DESIGN.md` | The app's visual contract: tokens, layout metrics, state vocabulary, D-pad model. |
| `design/ui.html` | Working UI prototype at the device's real 832×444 dp, driven by the D-pad. |

## The routing model

ES-DE's Android `es_systems.xml` has 195 systems and `<name>` equals `<path>`
for **all 195**. So the ES-DE system name *is* the folder name:

```
<roms>/<system>/<file>
```

No mapping table. `genesis` → `<roms>/genesis/`, `psx` → `<roms>/psx/`. Verified
against the live file, not assumed.

## The schema

```json
{
  "schemaVersion": 1,
  "name": "Example Retro Repo",
  "sources": [
    {
      "id": "archive_org",
      "name": "Internet Archive",
      "transport": "http",
      "adapter": "archive_org",
      "systems": ["genesis", "psx"],
      "config": { "collections": { "genesis": "replace_with_a_real_collection_id" } }
    }
  ]
}
```

Rules that are load-bearing:

- **`schemaVersion` is a contract.** A newer version is rejected, never guessed.
- **Unknown adapter types are skipped, not fatal.** A repo written for a newer
  app still loads on an older one, minus the parts it doesn't understand.
- **Unknown fields are preserved** in the parsed model, so forward-compatible
  metadata survives.
- **`systems` are ES-DE names.** The validator checks them against the real list.
- **Transport is a property of the source**, so one queue drives three engines
  without special-casing in the UI.

### Adapters

| adapter | transport | config |
|---|---|---|
| `archive_org` | http | `collections`: system → archive.org collection id |
| `minerva` | torrent | `baseUrl`, `paths`: system → browse path |
| `dlarchive` | http | `baseUrl`, `extensions`: system → file extensions |
| `html_index` | http | `baseUrl`, `listings`: system → URL + CSS selectors |

`browser_capture` remains in the schema enum so a repo that declares it still
validates, but this build does not implement it: the adapter is skipped, and the
source simply does not appear. It was dropped after testing — every system it
was meant to reach is already served by `minerva` over BitTorrent, which needs no
session or captcha, while a WebView still leaves the file to a manual tap.

`index.pb` (protobuf) is likewise not read. Mihon's official repo publishes only
protobuf, but there is no protobuf schema for a retro index, so a decoder would
be guessing at field numbers. The fetcher detects the format and says so instead
of reporting "invalid JSON".

## Sources are not in this repository

Caddy ships no sources and this repository publishes no source list. The engine
is here; the map is not. `repo/` is gitignored, so `tools/gen_repo.py` produces
a working index for your own machine and nothing more.

```sh
python tools/gen_repo.py                 # writes repo/index.json, local only
python tools/validate_repo.py repo/index.json
```

A repository is any JSON file matching `schema/index.schema.json`, served from
anywhere the user chooses. Write your own, share it, or don't — the app has no
opinion and no default.

## Validating a repo

```sh
pip install jsonschema
python tools/validate_repo.py path/to/index.json
```

## Install (Android)

```sh
gradle :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Sideload only: all-files access is a Play policy violation, so there is no store
listing. arm64-v8a, minSdk 26.

Wireless debugging (no cable, no battery concern):

```sh
adb pair <ip>:<port>     # from Developer options > Wireless debugging
adb connect <ip>:<port>
adb install -r app-release.apk
```

The app has an **Export log** button; it writes to
`Android/data/caddy.android/files/logs/` and opens the share sheet, so a failure
on the device produces evidence without a connection.

## The spike

```sh
gradle :core:installDist
core/build/install/core/bin/core repo/index.json genesis /path/to/ROMs --title=Sonic
```

Reads the index, lists a system, resolves one game, downloads it to
`<roms>/<system>/`, verifies SHA-1/CRC32, and only then moves it into place.

Browse and pick parts from the command line:

```sh
core repo/index.json switch ROMs --list=20          # first 20 entries
core repo/index.json switch ROMs --page=2           # next page
core repo/index.json switch ROMs --title=zelda --list
core repo/index.json switch ROMs --title=zelda --part=base,update,dlc
```

Switch files are grouped by title id: a base game carries its update and DLC in
`variants`, and the app offers them as a chooser when you tap the game.

## Updating

The app has a **Check update** button. It reads `versions.json` from the release
channel, compares `versionCode`, downloads the APK, verifies its SHA-256, and
verifies the APK's signing certificate matches the installed app before handing
it to the system installer. That certificate check is the load-bearing one: a
swapped manifest can only point at an APK signed with this project's key.

```sh
gradle :core:test
```

## How a torrent source works

Minerva delivers by collection torrent, and the browse page already carries what
the engine needs per row:

```html
<div class="entry" data-name="[bios] game boy advance (world).zip">
  <a href="/rom?id=2874734">[BIOS] Game Boy Advance (World).zip</a>
  <span>13.37 KB</span>
  <a onclick="downloadMagnet('magnet:?xt=urn:btih:00000000deadbeef...')" data-m="3">
```

`data-m` is a **row index on the page, not the file index inside the torrent**.
The two orders differ: asking the GBA torrent for index 2128 returned
`Minicraft`, while Metroid Fusion was at 2110. So the downloader matches the
torrent's own file path by the filename the catalog showed, and keeps the page
index only as a fallback.

1. One HTTP request lists all 3,688 GBA files, each with a size.
2. Catalog dedupe collapses them to ~2,043 games.
3. Picking a game resolves the collection's magnet, waits for metadata, then
   sets file priority on exactly one file and downloads it sequentially into a
   staging folder.

No scraping per game, and no whole-collection download. The magnet on the page
carries **no trackers** — the site appends 38 of them in JavaScript — so the
adapter reads `const trackers = "&tr=..."` from the page and appends it. Without
that, a quiet collection connects peers and never exchanges metadata.

## What is verified

Probed live on 2026-10-22, not assumed:

- archive.org collections exist for 14 of the systems in scope and return
  ROM payloads with SHA-1 + CRC32 (sampled 75/75). No collection exists for
  GBA, SNES, NES, N64, NDS, PSP, Saturn, Dreamcast, 32X or Master System.
- The system -> collection map was verified against ES-DE names and Minerva's
  directory listing. It is not published; see `tools/sources.local.json`.
- No archive.org collection exists for GBA, SNES, NES, N64, NDS, PSP, Saturn,
  Dreamcast, 32X or Master System. Those come from Minerva.
- Minerva serves full No-Intro/Redump sets with four hashes per file and is
  **torrent-only** — every direct path 404s.
- `libtorrent4j-android-arm64` 2.1.0-39 ships `lib/arm64-v8a/libtorrent4j.so`
  in a plain jar, no AAR needed.
- Minerva GBA page: 3,688 rows, one infohash. The PSX collection has 11,170
  files; its swarm is thin enough that metadata exchange stalls, while Saturn
  (2,500 files) completes in seconds.
- dlarchive: 14,993 rows, 3,440 Switch files, 2,107 base games after the title-id
  rule. `file-url` returns a token-free link valid 24 h, verified by `HEAD`.
- Switch DLC/updates group with their base game by the shared 12-hex title-id
  prefix, verified by downloading three Witcher 3 DLCs.
- Download queue survives process death: killed at 54.1 MB, resumed, final file
  matched the source size exactly.
- Myrient returns a 2,334-byte shutdown tombstone. Dead since 2026-03-31.
- `romsfun` returns 403 from a datacenter IP; `vimm.net` gates on Cloudflare
  Turnstile. Neither is worth an adapter.

## Legal

Caddy ships no sources. The maintainer does not provide, endorse or support any
repository, and no source list is published here or in any release.

Sources are user-supplied **data**, not code — a repo cannot execute anything,
which is why this follows Mangayomi's declarative model rather than Mihon's APK
extensions. Do not ship or auto-download BIOS files.
