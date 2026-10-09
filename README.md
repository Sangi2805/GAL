# GAL (GetALife)

GetALife's screen-time roast cards plus a floating character: a chubby, full-body pink cartoon elephant
(called Sidekick in the code; the app itself never names her). She delivers the roast cards, wearing
GetALife's four faces (smug, bored, disappointed, horrified), walks around your screen in between, and reacts
to what you open. The old monkey mascot and the green blob are gone.

The app has one job: keeping you off your phone. Voice commands were dropped (speech recognition did not work
reliably on real phones), so GAL no longer asks for the microphone at all.

The original `GetALife` and `AI Assistant` folders are untouched; this project reuses their code.

**No network access.** GAL declares no `INTERNET` permission, and the build fails if any dependency adds
`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` or `QUERY_ALL_PACKAGES` to the merged manifest
(`verify<Variant>NoInternet` in `app/build.gradle.kts`).

## What it does

- **One simple setup screen.** First-time users see no feature names and no switches: one elephant, one
  list of what GAL needs, one **Allow** button that walks through every missing permission in turn (usage
  access, display over other apps, notifications), then offers the battery exemption once. **Start** switches
  everything on together. Settings is where the elephant can be hidden or kept still.

- **Screen Time Roasts** is GetALife's roast cards with simpler session rules (below): session clock,
  both phrase packs (Spicy, You may cry), give-up sign-offs, stats, exclusions and quiet rules (locked, in a
  call, excluded app). `../GetALife/README.md` still explains the phrase engine, stats and quiet rules, but
  its card cap, its 10 minute gap and its daily limit card do not apply to GAL. The card label reads
  "GAL · 12 min on screen" and shows the elephant's face for the tier.
- **The floating elephant** lives in a small window over every app. She walks like an elephant (one leg at a
  time, a heavy bob, swinging trunk and tail). Every 5 to 12 seconds she picks something to do: stroll along
  the screen edge, now and then walk across to the other side, hop, look around, or nap for a while. She keeps
  still while you hold her, while a card is up and while the screen is off. Tap her and she hops; drag her
  anywhere and she snaps to an edge. "Let her wander" in Settings turns the walking off; "Show her on screen"
  hides her.
- **Reactions** (`sidekick/Reactions.kt`, every 1.5 s from usage access):
  - Open a social or video app (Instagram, TikTok, YouTube, X, Reddit and the like, or anything the store
    files as social or video; messaging apps do not count) and she hurries to the middle of the screen,
    horrified, then shakes her head and wags her trunk "nooo" at you before walking back.
  - Open something useful (Docs, Keep, Calendar, Notion, Duolingo, Kindle, chess and the like, or anything
    filed as productivity) and she puffs up, grins, raises her trunk, sparkles and hops.
  - At most one reaction every 20 seconds.
  - The longer the phone session, the crosser she gets: calm up to half the "nag me after" time, then
    redder, with an angry face and steam from the threshold on, fully furious at twice the threshold. Too
    cross to nap.
- **First launch:** a box asks you to describe yourself in one word. Three seconds later, typed or not, it cuts
  you off: "Never mind. We don't care." Nothing typed is kept.
- **Both on:** while a roast card is up, the floating elephant wears the same face (and the sweat drop for
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

## Sidekick scenes (debug only for now)

These were built for voice commands, which are gone. The engine stays for a future use, and debug builds can
still play the scenes from adb (see Debug builds). When Voice Sidekick opened an app, the elephant played a
short scene on a see-through stage over whatever is on screen. The stage window exists only while a scene plays (Android 12+ lets a full-screen overlay swallow
every touch, so it must not linger), and a tap anywhere skips the scene and opens the app at once.

| Scene | When | What happens | Length |
| --- | --- | --- | --- |
| NormalOpen | any app | A crate with the app's real icon floats down on a parachute and lands, the elephant trots up and boops it once with her trunk, the icon pops out and the app opens. | about 1.8 s |
| RoastOpen | a social app you use a lot | The crate thuds down, the elephant turns to you with a roast line in a bubble (and out loud), winds her trunk up and smacks the crate open in three blows. | about 3 to 3.5 s |
| NotFound | no app matched | The elephant drops in, looks left and right, says it has never heard of it, and leaves. | about 2 s |

- The elephant moves like a platformer hero (acceleration, braking, variable jump height, heavier fall, squash
  and stretch), all tuned in `sidekick/stage/MovementTuning.kt`. The character, the crate and every sound
  (`tools/sounds/make_sounds.py`) are our own.
- The app-roast rules (`AppHabits`), their settings and the Xiaomi pop-up hint were removed with voice. The 150
  app roast lines are still in `tools/phrases/app_15_roasts.txt` and phrases.json, unused, in case a tap-to-open
  feature comes back.

## One foreground service, two types

`GalService` hosts both features and combines their types in one `startForeground` call:

| Part | Type | Why | Restart |
| --- | --- | --- | --- |
| Screen Time Roasts | `specialUse` | Measuring screen time for an optional card is not one of Android's named types; the manifest property explains the use. No runtime permission is needed, and it may start from the background (boot, update). | **Sticky.** After a kill, Android restarts it and the session is picked back up. |
| Floating elephant | `specialUse` | Keeps the floating elephant on screen. It needs only "Display over other apps". | **Sticky**, like roasts. |

If the elephant should be on but is not and Android will not restart the service, GAL posts **"The elephant
took a break. Tap to bring her back."**; a WorkManager job (`SidekickWatchdog`, every 30 minutes while she is
on) is the safety net for phones that kill apps without restarting them. Opening GAL also brings her back. The
ongoing notification has a **Hide the elephant** button.

## Package visibility

No `QUERY_ALL_PACKAGES`. The `<queries>` block lists `MAIN`/`LAUNCHER` (the exclusion picker and per-app
stats), `MAIN`/`HOME`, and the camera, maps and clock intents for default exclusions.

## Branding

She is a full-body pink cartoon elephant with a bow, lashes, toenails, a little tail and a bendy trunk, our
own design (no hammer, no circus hat, nothing borrowed). `tools/mascot/generate_elephant.py` writes every picture of her from one list of shapes: the
adaptive launcher icon with its monochrome layer (`drawable/ic_launcher_*.xml`, mint `#BDF2D5` background),
the Android 12+ splash (`drawable/splash_icon.xml` on `@color/splash_background`), the four roast card
elephants (`drawable/mascot_*.xml`) and the notification icon. Previews land in `tools/mascot/preview/`,
including the 512 px store icon (`logo.png`) and a 1024 x 500 cover picture (`cover.png`). The landing
page draws the same splash drawable at the same size and position, so the splash hands over without a jump.

The live elephant (floating Sidekick and the stage) is drawn in code by `sidekick/BlobPainter.kt` with the same
colours and proportions. Her trunk angle is part of each frame's pose, which is how it sways, lifts while
listening and smacks crates in the scenes.

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

## Versions

Every APK sent to testers gets a new version in `app/build.gradle.kts`: `versionCode` goes up by one (Android
refuses to install an APK over one with a higher or equal code from the same key), and `versionName` is what
people see in App info. The commit it was built from gets a matching git tag, for example `v0.2.0`.

| Version | Code | What changed |
| --- | --- | --- |
| 0.1.0 | 1 | First builds: roast cards, the green blob, then the pink elephant with voice. |
| 0.2.0 | 2 | Voice dropped, full-body walking elephant, reactions to apps, one-button setup, audit fixes. |

## Debug builds

Everything logs under one tag: `adb logcat -s NAG`. That includes `[Service]` (foreground types, what is
running), `[Sidekick]` (on screen, the face it is wearing, listen results, why an open was or was not a
roast), `[Stage]` (scenes starting, opening the app, ending) and `[Notify]`.

```bash
adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver -a com.sangar.gal.debug.SET_THRESHOLD --ei minutes 1
```

```bash
adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver -a com.sangar.gal.debug.TEST_CARD --ei tier 3
```

The stage, without speaking. `PLAYGROUND` lets you drive the elephant with your fingers to tune the movement
(hold the left or right third to run, tap the middle to jump, the top strip closes). `DEMO_SCENE` plays a
scene with GAL's own icon (`normal`, `roast` or `notfound`).

```bash
adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver -a com.sangar.gal.debug.PLAYGROUND
```

```bash
adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver -a com.sangar.gal.debug.DEMO_SCENE --es kind roast
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
├── sidekick/                 SidekickOverlay, SidekickView (+ roast moods), BlobPainter (the elephant), wandering
├── sidekick/stage/           stage window, frame loop, world, elephant physics, crate, props, renderer
├── sidekick/scene/           SceneDirector, the scenes, AppHabits (when to roast), SceneSounds
├── overlay/ phrases/ data/   GetALife, plus session card rules, app roast lines and scene settings
└── ui/                       Landing, SetupScreen (one Allow button), Home, Stats, Settings (+ scenes card)

tools/phrases/                phrase batches and build_phrases.py (writes assets/phrases.json)
tools/sounds/make_sounds.py   writes res/raw/scene_*.wav
```
