# GAL (GetALife)

One offline Android app that combines **GetALife** (screen-time roast cards) and **Pocket Sidekick** (a
floating character you tap and talk to). The Sidekick blob is the only character: it delivers the roast
cards, wearing GetALife's four faces (smug, bored, disappointed, horrified), and it opens apps by voice.
The old monkey mascot is gone.

The original `GetALife` and `AI Assistant` folders are untouched; this project reuses their code.

**No network access.** GAL declares no `INTERNET` permission, and the build fails if any dependency adds
`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` or `QUERY_ALL_PACKAGES` to the merged manifest
(`verify<Variant>NoInternet` in `app/build.gradle.kts`).

## What it does

- **One setup screen, two switches.** *Screen Time Roasts* and *Voice Sidekick*. Nothing is asked for up
  front. Turning a switch on walks through that feature's missing permissions one at a time:

  | Permission | Screen Time Roasts | Voice Sidekick |
  | --- | --- | --- |
  | Usage access | required | not asked |
  | Display over other apps | required | required |
  | Notifications | required | required |
  | Microphone | not asked | required |
  | Battery exemption | offered once, optional | not asked |

- **Screen Time Roasts** is GetALife's roast cards with simpler session rules (below): session clock,
  both phrase packs (Spicy, You may cry), give-up sign-offs, stats, exclusions and quiet rules (locked, in a
  call, excluded app). `../GetALife/README.md` still explains the phrase engine, stats and quiet rules, but
  its card cap, its 10 minute gap and its daily limit card do not apply to GAL. The card label reads
  "Sidekick · 12 min on screen" and shows the blob's face for the tier.
- **Voice Sidekick** is Pocket Sidekick: tap the blob, say "open Maps", and it opens it. Drag it anywhere
  and it snaps to an edge. Speech recognition asks for on-device recognition (`EXTRA_PREFER_OFFLINE`). If
  the phone has no offline pack for the language, Sidekick says so instead of going online.
- **Both on:** while a roast card is up, the floating blob wears the same face (and the sweat drop for
  horrified), then goes back to normal when the card goes.

## Session cards

- The "nag me after" slider runs from 1 minute to 8 hours, default 30 minutes.
- A session is continuous, unlocked screen time. It ends once the screen has been off for more than a minute.
- The first card comes when the session reaches the threshold, then one more each time another threshold of
  session time passes. With 10 minutes that is a card at 10, 20, 30 minutes and so on.
- Every session gets 12 cards at most. Card 12 is always the give-up sign-off, in both packs: Spicy uses its
  80 `give_up` lines, You may cry has 80 of its own (`tools/phrases/cry_10_giveup.txt`). The sign-off wears the
  bored face. After it the app stays quiet until the next session.
- *Snooze 10 min* silences cards for 10 minutes of real time. Any two cards are at least a minute apart.
- It keeps going until Screen Time Roasts (or the cards switch on home) is turned off.
- There is no daily limit card any more. Today's total still shows on the home and stats screens.
- The session's card count, a running snooze and the time of the last card are saved
  (`service/NagStateStore.kt`). A service killed and restarted mid-session carries on at the same card, and a
  crash loop cannot put a card on screen after every restart.

## One foreground service, two types

`GalService` hosts both features and combines their types in one `startForeground` call:

| Part | Type | Why | Restart |
| --- | --- | --- | --- |
| Screen Time Roasts | `specialUse` | Measuring screen time for an optional card is not one of Android's named types; the manifest property explains the use. No runtime permission is needed, and it may start from the background (boot, update). | **Sticky.** After a kill, Android restarts it and the session is picked back up. |
| Voice Sidekick | `microphone` | It opens the mic while you are in another app. Android 14+ refuses this type without `RECORD_AUDIO`, and Android 11+ only lets the mic work if the service was started from the foreground or from a notification tap. | **Not sticky.** A system restart comes from the background and could not use the mic, so it is never restarted automatically. |

When Sidekick should be on but is not (after a kill, a reboot or an update), GAL posts **"Tap to bring
Sidekick back"**. Tapping it starts the service straight from the notification, which Android allows to
open the mic. Opening GAL also brings Sidekick back. If only Sidekick was running when Android killed GAL,
nothing is left running to post that notification, so a WorkManager job (`SidekickWatchdog`, every 30
minutes while Voice Sidekick is on) checks and posts it.

The service returns `START_STICKY` while roasts run and `START_NOT_STICKY` when only Sidekick runs. The
ongoing notification has a **Turn off Sidekick** button.

## Package visibility

No `QUERY_ALL_PACKAGES`. The `<queries>` block lists `MAIN`/`LAUNCHER` (Sidekick's app index, the exclusion
picker, per-app stats), `MAIN`/`HOME`, the camera, maps and clock intents for default exclusions, and the
speech recogniser and TTS services.

## Branding

Concept C (Peekaboo) from `../Combined App Branding`: adaptive launcher icon with a monochrome layer
(`mipmap-anydpi/ic_launcher*.xml`, `drawable/ic_launcher_*.xml`), the Android 12+ splash
(`drawable/splash_icon.xml` on `@color/splash_background`), and `values/brand_colors.xml`. The landing page
draws the same splash drawable at the same size and position, so the splash hands over without a jump.

The four roast faces on the blob are generated by `tools/mascot/generate_blob_faces.py` (it also writes
`blob_faces_preview.svg`). The live blob draws the same faces in code (`SidekickView.Mood`).

## Build

Needs JDK 17+, the Android SDK with platform 37, and Gradle 9.6 (the wrapper fetches it). Android Studio's
bundled JDK works.

```bash
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Other tasks:

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew lintDebug
```

For a release build, run `./gradlew createReleaseKeystore` once (it writes `keystore/gal-release.jks` and
`keystore.properties`, which you must back up), then `./gradlew assembleRelease`. GAL has its own
application ID (`com.sangar.gal`) and key, so it installs next to GetALife and Pocket Sidekick. It does not
replace them, and it does not import their history.

## Debug builds

Everything logs under one tag: `adb logcat -s NAG`. That includes `[Service]` (foreground types, what is
running), `[Sidekick]` (on screen, the face it is wearing, listen results) and `[Notify]`.

```bash
adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver -a com.sangar.gal.debug.SET_THRESHOLD --ei minutes 1
```

```bash
adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver -a com.sangar.gal.debug.TEST_CARD --ei tier 3
```

The **GAL diagnostics** launcher entry and the `NAG` button on home exist only in debug builds.

## Layout

```
app/src/main/java/com/sangar/gal/
├── Permissions.kt            Feature, Need, per-feature requirements
├── service/GalService.kt     the one foreground service
├── service/ScreenTimeTracker.kt  GetALife's session clock (was TrackerService)
├── service/GalNotifications.kt   ongoing + "tap to bring Sidekick back"
├── service/GalServiceStarter.kt  foreground/background starts, BootReceiver, SidekickWatchdog
├── sidekick/                 SidekickOverlay, SidekickView (+ roast moods), VoiceEngine, AppResolver
├── overlay/ phrases/ data/   GetALife, unchanged apart from the card label and face events
└── ui/                       Landing, SetupScreen (two switches), Home, Stats, Settings
```
