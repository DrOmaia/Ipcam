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
«كاميرات البيت» / "Home Cameras": watch the IP cameras on the home Wi-Fi. **One APK for phones and Android TV**
(his TCL Google TV), each with its own look: phone = list by group, touch; TV = Google-TV-style rows of big cards,
everything reachable with the D-pad remote (gold focus ring, cards grow on focus). Home network only (no cloud).
**Arabic (default) and English** (Settings → Language; every text is `L.t(c, "عربي", "English")` / `t(…)` in a `Base`
screen; Arabic right to left). Night colours (navy + gold, like prayer-tv).
- Makes: **Hikvision / HiLook**, **Dahua / Imou**, **EZVIZ**, **TP-Link Tapo**, **TP-Link VIGI**, **Reolink**, **Uniview**,
  **Foscam**, **eufy**, **Wyze**, **Yoosee**, **Vstarcam / Eye4**, **IP Webcam** (an old phone with that app),
  **a phone running this app's camera mode**, and any camera by a full **RTSP link**. The camera type is one button that
  opens the list; under it «طريقة الإضافة» shows that make's steps (`Guides.kt`: where the address is, which user and
  password, what to turn on in the make's own app).
- Add a camera by hand, or **search the network** (ONVIF discovery, RTSP 554, IP Webcam 8080, phones in camera mode).
- **Groups** (e.g. «داخل البيت», «الحوش»), a green / red **status dot** per camera, **shortcuts** (app icon long-press,
  pinned shortcut per camera) and a **home-screen widget**. A second widget, **«كاميرا حيّة» / "Live camera"**: each one
  shows one or several chosen cameras as a picture refreshed every 1–10 s (switching camera every 10–60 s), ▶ / ⏸ / ⏭,
  ⧉ = floating live video (picture-in-picture over the home screen), stops by itself after 5 minutes.
- **Watch**: one camera (full quality, sound, snapshot, recording, pinch zoom, picture-in-picture on phones, PTZ arrows
  for moving cameras, push-to-talk to phone cameras) or **all cameras** N per page (Settings → All cameras: 1–16, chosen on each device, `Prefs.perPage`; light stream); **tour** (one
  camera after the other); remote keys: ◄ ► next / previous, 1–9 camera N, 0 grid, OK menu.
- **Move cameras over the same Wi-Fi** to the TV or a family phone (Settings → Send / Receive, 4-digit code on the
  receiving screen). This is the only way camera passwords leave a device, and only by the owner's action.
- **Motion alerts** (ONVIF events): phone = notification with a snapshot; TV = the camera pops up in a corner over any
  app for 30 s; alert hours (all day / night / custom); **prayer time** (optional): for 15–45 min from each adhan
  (Friday's Dhuhr at least 60) the phone alert comes silently (channel `motion_quiet`) and the TV does not pop up.
  Times computed on the device like prayer-tv (`Prayer.kt`: Umm al-Qura for Saudi cities, Jordan for Amman / Irbid).
- **Phone as a camera**: an old phone becomes an RTSP camera (built-in server, port 8554) that the other devices find;
  viewers can talk through it.
- **Hikvision recorders (DVR / NVR)**: add all channels at once with their names (ISAPI), **recordings** (day timeline,
  play from any time), **events** list (person / vehicle / motion / line), alerts by type (`Camera.alertWhat`, AcuSense).
- **Watching from outside the home** (e.g. the owner's recorder in Irbid): per camera an outside address
  (`remoteHost/remotePort/remoteHttp`); `Route` tries the local address first on the camera's own network, else the outside one.
  A Tapo camera at another house (no router access): the camera's menu has «افتح في تطبيق Tapo» (opens `com.tplink.iot`,
  or its Google Play page; `<queries>` in the manifest).
- **Network changes** (`Net.kt`): leaving the Wi-Fi and coming back (or another Wi-Fi) reconnects by itself: videos on
  screen, status dots, alert watchers (their waits end), and refused-password blocks / remembered routes are forgotten.
  A live picture frozen for 15 s reconnects too.
- Optional **PIN lock**. **Updates** from Settings: shows this version, the newest and the stable one.
User-facing description: `README.md` (Arabic). History of changes: `CHANGELOG.md`.

## 3. Files
| Path | What |
| --- | --- |
| `app/src/main/java/com/dromaia/ipcam/Prefs.kt` | Device settings (`Prefs`, SharedPreferences `ipcam-settings`), language `L`, `Device.isTv/name`. |
| `Base.kt` | `Base` (every screen: direction, PIN lock), `Watch` intent extras, `Lock`, `App` (Application). |
| `MainActivity.kt` | Home: phone layout and TV layout, groups, status dots, shortcuts, «new version» banner, open-on-all. |
| `EditActivity.kt` | Add / change / delete a camera: make, group, address, channel / link, ONVIF options, alerts, PTZ. |
| `ScanActivity.kt`, `Discovery.kt` | Network search: ONVIF WS-Discovery, RTSP 554 / 8080 scan, phones (`PhoneCams`); `reachable()`. |
| `WatchActivity.kt`, `Tile.kt` | Live view (grid / single / tour / PiP / remote keys / PTZ pad / talk); one video with zoom & snapshot. |
| `Media.kt`, `Recorder.kt` | Save snapshots and recorded clips to the gallery. |
| `SettingsActivity.kt` | Language, opening, tour, all cameras (per page, arrange), motion alerts + hours + permissions, PIN, moving cameras, phone camera, updates. |
| `ArrangeActivity.kt` | «ترتيب الكاميرات»: order of the cameras in each group and of the groups (phone: drag by ☰ / the group title; TV: OK, ▲ ▼, OK); saved with `Store.reorder`. |
| `LockActivity.kt` | PIN pad (also choosing a PIN). |
| `Transfer.kt`, `TransferActivity.kt` | Send / receive cameras on the same Wi-Fi (NSD `_ipcamshare._tcp`, code). |
| `Onvif.kt`, `Alerts.kt` | ONVIF client (PTZ, snapshot, events); alert service (alerts by type), TV pop-up, boot receiver. |
| `Net.kt` | Follows the device's network; `Net.add/remove` listeners, `Net.epoch`; resets `Route`, `Hik` / `Onvif` / `Snap` refusals. |
| `Prayer.kt` | Prayer times on the device (cities, Umm al-Qura / Jordan, Ramadan Isha), `Prayer.quiet()` for the alerts. |
| `Hik.kt` | Hikvision ISAPI: device name, channels, recordings search, events search, alert stream, recorder clock. |
| `PlaybackActivity.kt`, `EventsActivity.kt` | Recordings (24 h timeline) and events list of Hikvision recorders. |
| `CameraModeActivity.kt`, `CameraService.kt`, `RtspServer.kt`, `PhoneCams.kt`, `Talk.kt` | Phone as a camera + talk. |
| `CamWidget.kt`, `res/layout/widget_cams.xml` | Home-screen widget. |
| `LiveWidget.kt`, `WidgetSetupActivity.kt`, `Snap.kt`, `res/layout/widget_live.xml`, `res/xml/widget_live_info.xml` | Live camera widget: settings per widget (`LiveConf`, prefs `lw_<id>`), refresh service `LiveService` (5 min), one picture of a camera (`Snap.grab`: snapshot address, else one frame of the light stream); ⧉ opens `Watch.floating`. |
| `Update.kt` | Newest / stable version from GitHub, download and install. |
| `Camera.kt` | `Camera`, `Brand` (RTSP link, ports, default user of each make), `Store` (SharedPreferences `ipcam` / key `cameras`). |
| `Guides.kt` | «How to add it»: numbered steps per make (Arabic / English), shown on the add-camera screen. |
| `Ui.kt` | Colours, buttons, fields (1.3× text on TV), Arabic / English number agreement. |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/` | Permissions, services, icon, TV banner, widget. |
| `.github/workflows/build.yml` | Builds, signs and publishes every push to `main` as release `vN`; "mark stable" and "restore stable". |
| `app/release.keystore` | The signing key (PKCS12, EC P-256, alias `ipcam`). Public in this repository by the owner's choice (same as prayer-tv and Mani). **Never change, delete or replace it**: a different key stops installed apps from updating. |

RTSP links (`Camera.rtsp`): Hikvision `/Streaming/Channels/{ch}01` (main) and `{ch}02` (sub);
Dahua `/cam/realmonitor?channel={ch}&subtype=0|1`; Tapo and VIGI `/stream1` and `/stream2`; EZVIZ `/h264/ch1/main|sub/av_stream`;
Reolink `/Preview_{ch:02}_main` and `/h264Preview_{ch:02}_sub`; Uniview `/unicast/c{ch}/s0|s1/live`; Foscam `:88/videoMain|videoSub`;
eufy `/live0`; Wyze `/live`; Yoosee `/onvif1|onvif2` (UDP only: `Brand.tcp`); Vstarcam `:10554/tcp/av0_0|av0_1`; IP Webcam `:8080/h264_ulaw.sdp`;
phone camera `:8554/live` and `/low` (user `ipcam`); Hikvision playback `/Streaming/tracks/{ch}01?starttime=yyyyMMddTHHmmssZ`
(recorder wall clock). User and password are URL-encoded.

## 4. Hard rules (do not break the app)
1. **No new libraries, build steps or servers** beyond Media3 (already used). Plain Kotlin, screens built in code
   (XML only where Android requires it: the widget and the PlayerView with a TextureView).
2. **Keep the saved cameras.** Never rename or remove a stored key (`ipcam` / `cameras`, the JSON fields) without
   reading the old one (new JSON fields get defaults in `Camera.fromJson`). Passwords stay on the device
   (`allowBackup=false`); they leave it only through the owner's own Wi-Fi transfer, never to the internet.
3. **Never touch** `app/release.keystore`, the package name `com.dromaia.ipcam`, or the version lines in
   `app/build.gradle.kts` (version = build number, automatic).
4. **Every text in Arabic and English** (`t("…","…")`). Arabic: correct Modern Standard Arabic, masculine when
   addressing him, correct number agreement (`Ui.count`). Icon-only buttons need a `contentDescription` in both.
5. **Phone and TV** share the code but each has its own look (`tv` in `Base`): everything must also work with the
   TV remote (focusable buttons and cards, visible gold focus), and with touch on phones (targets ≥ 48 dp).
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
