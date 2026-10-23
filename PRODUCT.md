# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Retro handheld owners — Retroid Pocket-class Android devices, controller in hand,
screen held at arm's length. They want ROMs on the SD card without a desktop, a
cable, or a browser session.

## Product Purpose

A controller-first download engine: add a repository, pick a system, pick a game,
and the file lands verified at `<roms>/<system>/` where ES-DE already looks. Success
is a game playable in ES-DE minutes after deciding to get it, with no PC involved.

## Positioning

The engine, not the catalog. Caddy ships no sources; a repository is user-supplied
JSON, and one download queue drives three transports (HTTP, BitTorrent, browser)
without the UI special-casing any of them. A neighboring app either bundles a store
or leaves you to a browser download.

## Operating Context

- Landscape only, 1920×1080 @ 369 dpi → 832×444 dp of usable app area on the
  target device. Held two-handed; D-pad and face buttons are the primary input.
- Android 8+ sideload, all-files access so downloads land at a real path.
- Sources are slow and rate-limited; a 15 GB game outlives the activity, so the
  queue is a foreground service and must stay observable.
- Long-running state: queued / running / paused / done / failed, with byte counts,
  attempt counts and error text that must survive process death.
- No cover art exists in any source. Rows are text: title, size, region, extras.
- Downloads can be interrupted; verification (SHA-1/CRC32) happens before the file
  is moved into place, so a failure must be explainable, not silent.

## Capabilities and Constraints

- Screens: systems home, browse one system (list + search, thousands of rows),
  game detail (base / update / DLC chooser), download queue, repository setup and
  settings (URL, pasted JSON, ROMs root, enabled systems, update check).
- A game with no variants downloads on a single tap; one with variants opens the
  chooser. Variants are never separate catalog rows.
- Updates: one manual check, no background polling. Every network call is
  user-initiated.
- Storage permission is requested only when a download needs it.
- 2,000+ row lists must scroll at 60 fps on a low-power ARM SoC: no per-row
  images, no heavy effects, no continuous animation while idle.

## Evidence on Hand

Working engine (`core/`), queue schema (`DownloadQueue.kt`), and the real adapter
contracts. No cover art, no screenshots, no marketing claims.

## Product Principles

1. The queue is the product: it is visible, resumable, and honest about failure.
2. Every screen is reachable and operable with a D-pad.
3. No network call the user did not ask for.
4. Density beats decoration — the device is held close and read fast.
5. Performance is a feature: static rows, flat surfaces, no idle animation.

## Accessibility & Inclusion

Focus must be visible at all times for controller users; text contrast must hold at
arm's length under varied ambient light. Touch targets ≥ 48 dp for the touch path.
