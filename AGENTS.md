# Rules

## Device safety — the Retroid Pocket 6 is the user's personal device

The Android handheld (`192.168.2.33`, wireless debugging) is **not a test rig**.
It is the user's own console with their own games, emulators and settings on it.

**Never do any of the following without asking first and getting an explicit yes:**

- Delete, move, rename or overwrite anything under `/sdcard/ROMs/`, `/storage/emulated/0/`,
  `/sdcard/ES-DE/`, or any other user data path.
- Change device settings: display, sound, network, developer options, battery,
  storage, date/time, locale, accessibility, or any `settings put` command.
- Install, uninstall or clear data for **any package other than `caddy.android`**.
- Grant or revoke permissions, or toggle app-ops, for any app other than `caddy.android`.
- Modify, disable or interact with emulators (RetroArch, Dolphin, PPSSPP, AetherSX2,
  DuckStation, …) or frontends (ES-DE, Daijishō).
- Wipe cache, run `pm clear`, `am force-stop` on other apps, or reboot the device.
- Read or exfiltrate personal data: photos, messages, accounts, saved games,
  config files belonging to other apps.

**Always allowed without asking** (scoped to this project's app):

- `adb install -r` / `uninstall` of `caddy.android` only.
- `adb shell am start` / `am force-stop` of `caddy.android` only.
- `adb logcat`, `adb shell dumpsys`, `adb shell getprop`, `uiautomator dump`,
  `screencap` — read-only inspection.
- Reading and writing files under `/sdcard/Android/data/caddy.android/`.
- Writing test downloads into a folder the user has explicitly designated for
  Caddy test output.

**When in doubt, ask.** A blocked question costs one message. Deleting someone's
save file costs their afternoon. State plainly what command would run, on what
path, and why — then wait.

## Releases

Only publish an APK to GitHub releases **after** it has been verified running on
the device. Broken builds get deleted, not left up as history.

## Source data

This repository publishes no source list. `repo/index.json` and
`tools/sources.local.json` are gitignored and must never be committed, quoted in
docs, or included in a release. See README "Sources are not in this repository".
