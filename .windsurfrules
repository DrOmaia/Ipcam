<!-- Copy of AGENTS.md made by tools/sync-ai.py. Edit AGENTS.md, then run: python3 tools/sync-ai.py -->

# Instructions for any AI tool (or person) changing this app

This file is the single source. `CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`,
`.cursor/rules/ipcam.mdc` and `.windsurfrules` are copies made by `python3 tools/sync-ai.py`.
Edit only this file, then run the script.

## 1. Who asks and how to answer
- The owner is a father in Riyadh. He writes in Arabic (often spoken / Levantine, with typos): read for meaning.
- Answer in **Arabic, masculine address**, short and clear. No technical jargon unless he asks.
- If a request is ambiguous, changes how something already works, or has more than one sensible way:
  **ask first** with 2–4 short multiple-choice questions (put your recommended option first), then build.
- Do **exactly** what he asked. Do not redesign, rename, reorder or "improve" things he did not mention.
  If you notice another problem, mention it in one line at the end; do not fix it unasked.

## 2. What the app is
«كاميرات البيت»: watch the IP cameras on the home Wi-Fi from an **Android phone** (also runs on Android TV with the remote).
Home network only (no internet relay, no cloud accounts). Arabic only, right to left, night colours (navy + gold, like prayer-tv).
- Makes: **Hikvision / HiLook**, **Dahua / Imou**, **TP-Link Tapo**, and any other camera by a full **RTSP link**.
- Add a camera by hand, or **search the network** (ONVIF discovery + knocking on RTSP port 554 of every address).
- Watch one camera full screen (full quality, sound button, quality button) or **all cameras**, four per page (light stream).
- Update from inside the app (works while the repository is public).
User-facing description: `README.md` (Arabic). History of changes: `CHANGELOG.md`.

## 3. Files
| Path | What |
| --- | --- |
| `app/src/main/java/com/dromaia/ipcam/MainActivity.kt` | Home: list of cameras, add, search, watch all, version and update button. |
| `EditActivity.kt` | Add / change / delete a camera; make buttons, hints per make, preview of the stream link. |
| `ScanActivity.kt` | Network search screen. |
| `Discovery.kt` | ONVIF WS-Discovery (multicast 239.255.255.250:3702) and the port-554 scan; guesses the make. |
| `WatchActivity.kt` | Live view: one camera, or the grid (1, 2, 2 × 2; pages of four). |
| `Tile.kt` | One video (Media3 ExoPlayer, RTSP over TCP, small buffer), error message in Arabic, retry every 5 s. |
| `Camera.kt` | `Camera`, `Brand` (RTSP link of each make), `Store` (saved in SharedPreferences `ipcam` / key `cameras`). |
| `Update.kt` | Download the latest release and open the installer (same way as prayer-tv). |
| `Ui.kt` | Colours, buttons, fields, Arabic number agreement (`count`, `cameras`, `devices`). |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/` | Permissions, icon, TV banner, app name. |
| `.github/workflows/build.yml` | Builds, signs and publishes every push to `main` as release `vN`; "mark stable" and "restore stable". |
| `app/release.keystore` | The signing key (PKCS12, EC P-256, alias `ipcam`). Public in this repository by the owner's choice (same as prayer-tv and Mani). **Never change, delete or replace it**: a different key stops installed apps from updating. |

RTSP links (`Camera.rtsp`): Hikvision `/Streaming/Channels/{ch}01` (main) and `{ch}02` (sub);
Dahua `/cam/realmonitor?channel={ch}&subtype=0|1`; Tapo `/stream1` and `/stream2`. User and password are URL-encoded.

## 4. Hard rules (do not break the app)
1. **No new libraries, build steps or servers** beyond Media3 (already used). Plain Kotlin, screens built in code.
2. **Keep the saved cameras.** Never rename or remove a stored key (`ipcam` / `cameras`, the JSON fields) without
   reading the old one. Passwords stay on the device only (`allowBackup=false`); never send them anywhere.
3. **Never touch** `app/release.keystore`, the package name `com.dromaia.ipcam`, or the version lines in
   `app/build.gradle.kts` (version = build number, automatic).
4. **Arabic text** is correct Modern Standard Arabic, masculine when addressing him, with correct number agreement (`Ui.count`).
5. **Phone and TV** share the code: everything must also work with the TV remote (buttons and cards are focusable).
6. **Small, focused changes.** Change only the files the request needs. Never delete features.
7. **Git safety:** never force-push `main`, never rewrite history, never delete tags or releases (`vN`).
   Work on `main` (it publishes directly), one clear commit per request.

## 5. Backups and going back (the owner must never lose a working app)
- Every build is kept as a GitHub release `vN` with its APK (`ipcam.apk`).
- The file **`STABLE`** holds the tag (`vN`) of the last version the owner tested and approved (`v0` = none yet).
- **Mark stable** — when he says a version works ("تمام", "اعتمد", "شغال"): write that tag into `STABLE`, commit with
  `[skip ci]` in the message, push. Or GitHub → Actions → *Build APK* → *Run workflow* → `mark-stable`.
- **Restore stable** — when he says something broke ("ارجع للنسخة المستقرة"): Actions → *Build APK* → *Run workflow* →
  `restore-stable`. It makes a **new** commit with the stable app files and a new, higher release, so it installs over
  the broken one without losing the saved cameras.

## 6. Checklist for every change
1. Read the relevant code first. Ask if unclear (section 1).
2. Make the minimal change.
3. Kotlin cannot be built locally without the Android SDK: re-read changes carefully (imports, null safety,
   API level checks for minSdk 24).
4. Commit with a clear message (what changed for the user), push to `main`.
5. Wait for GitHub Actions *Build APK* to finish green. If red: read the log, fix, push again. Never leave it red.
6. Add a line to `CHANGELOG.md` (version = the build's `vN`) and update `README.md` if behaviour changed.
7. Tell him in Arabic what changed, and to say «اعتمد» after testing so it becomes the stable version.
