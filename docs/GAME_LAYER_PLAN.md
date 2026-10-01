# GAL game layer plan

Status: proposal, waiting for Sangar's approval. Nothing here is built yet.

## Goal

GAL should feel like a small platform game living on top of the phone. The blob runs, jumps and lands with weight, like a classic platformer hero. When we ask it to open an app, it plays a short scene and then opens the app. If the app is a social media app we use a lot, the scene turns into a roast: the blob stops, says something like "Are you married to this app?", pulls out a hammer and smashes the app open.

## Ground rules

These do not change:

- GAL stays offline. No INTERNET permission, no QUERY_ALL_PACKAGES, and no Accessibility Service (Play policy risk, and it is not needed).
- The character stays our own blob. "Moves like Mario" means the platformer feel only: acceleration, jump arcs, squash on landing. Nothing that looks or sounds like Nintendo's: no red cap, moustache or overalls, no question blocks, green pipes or Nintendo-style coins, no "It's-a me", no game sounds. The app is our own crate with the app's icon on it, and all sounds are our own.
- Every scene can be skipped with a tap, lasts at most about 3.5 seconds, and has an off switch ("Quick open").

## What Android allows (research summary)

**A full-screen animation layer on top of other apps is possible.** We already hold "Display over other apps" for the roast cards and the floating blob. A second overlay window (TYPE_APPLICATION_OVERLAY, full screen, laid out under the status and navigation bars) can be the stage.

**The catch is touches.** From Android 12, a full-screen overlay from another app blocks touches to the app underneath while it is visible, unless the window is mostly transparent (alpha 0.8 or less). So the stage cannot sit on the screen all the time. We show it only while a scene plays and remove it right after. The small floating blob window stays as it is now. During a scene the stage is touchable, so a tap skips the scene.

**We cannot see where app icons are on the home screen.** Android does not tell other apps where the launcher draws its icons. The only ways around that are an Accessibility Service (ruled out above) or making GAL the home screen launcher itself. So in the scene we bring the app to the blob: we load the app's real icon from PackageManager (allowed with our current <queries> block) and draw it on a crate that drops onto the stage. The blob then runs to the crate and hits it.

**Knowing whether an app is social media is possible offline.** Android 8+ apps declare a category (ApplicationInfo.category == CATEGORY_SOCIAL). Some apps do not set it, so we add a small built-in list of well-known social package names as a fallback. Usage comes from UsageStatsManager, which Screen Time Roasts already has permission for.

**Opening the app at the end works.** Launching an activity from the background is allowed because we hold the overlay permission. Android 15 also wants our overlay to be visible at that moment, and it is, because the scene is still on screen.

**OEM limits.** Xiaomi (MIUI/HyperOS) has an extra "Display pop-up windows while running in the background" switch. Setup should detect Xiaomi and point to it, the same way GetALife handles battery killers.

## Options we looked at

| Option | What it is | Verdict |
| --- | --- | --- |
| A. Stage overlay scenes | Short full-screen scenes on top of whatever is open. The app's icon drops in on a crate. | **Recommended for v1.** Works on any launcher and stays inside current permissions. |
| B. GAL as a launcher | GAL becomes the home screen. The home screen is a real level and the blob runs to the actual icon. | Best "game" feel, but it is a whole launcher (widgets, app drawer, settings) and people have to switch launchers. Keep as a v2 experiment. |
| C. Accessibility Service | Read icon positions from the real launcher. | Rejected. Play policy rejects non-accessibility use, and it reads everything on screen. |

For drawing we also compared engines. libGDX or Korge are full game engines and too heavy for one character. Lottie only plays fixed animations and cannot react to physics. Rive has interactive state machines but would mean redrawing the blob in a new tool. **We recommend extending the current Canvas drawing in SidekickView** (the blob is already drawn in code), driven by a small game loop of our own. Rive can come later if we want hand-made animations.

## Architecture

```
sidekick/
  stage/StageOverlay.kt     adds and removes the full-screen stage window, tap to skip
  stage/StageView.kt        View + Choreographer frame loop, fixed 60 Hz physics step
  stage/World.kt            ground line from window insets, gravity, entity list
  stage/BlobActor.kt        position, velocity, state machine, squash and stretch, draws via SidekickView's painter
  stage/AppCrate.kt         crate with the app icon; bump, crack and pop states
  stage/Props.kt            hammer, speech bubble, dust and star particles, "BONK" text
  stage/MovementTuning.kt   all feel numbers in one data class (run accel, max speed, jump impulse, gravity, land squash)
  scene/SceneDirector.kt    picks and runs a scene script, calls AppResolver.launch() at the end
  scene/Scenes.kt           NormalOpen, RoastOpen, NotFound
  scene/AppHabits.kt        is this app social, how often is it used, cooldowns
```

The painting code moves out of SidekickView into a shared BlobPainter, so the small floating blob and the stage blob look identical. The frame loop runs only while a scene is on screen. When nothing is playing, there is no loop and no battery cost.

## The scenes

**NormalOpen (any app, about 2.5 s).** The stage fades in. The blob hops in from its current edge position. A crate with the app icon drops from the top and lands with dust. The blob runs to it, jumps and head-bumps it from below. The crate pops, the icon flies up and grows, and the app opens.

**RoastOpen (frequent social app, about 3.5 s).** It starts like NormalOpen, but the blob stops in front of the crate and turns to us with the SMUG or DISAPPOINTED face. A speech bubble shows a line such as "Are you married to this app?" or "Again? It's been 6 minutes." The blob pulls out a hammer and hits the crate three times, cracking it a little more each time. On the third hit the crate breaks, the icon pops out and the app opens. The face follows the same tiers as the roast cards.

**NotFound (about 1.5 s).** The blob shrugs, a bubble says "Never heard of it", and the stage leaves.

Roast lines go in a new phrase batch (tools/phrases/batch_15_app_roast.txt) with an {app} placeholder and the same pattern and duplicate checks as the other packs. We will need about 150 lines across three tiers.

## When it roasts

An app gets RoastOpen only if all of these are true:

1. It is social. Either the category is CATEGORY_SOCIAL or it is on the fallback list.
2. It is heavy use. Opened at least 8 times today, or more than 45 minutes today, or a 7-day daily average above 60 minutes. All three numbers can be changed in Settings.
3. It is not on cooldown. Each app gets at most one roast every 2 hours and at most 5 roasts a day in total.
4. Quiet rules allow it (not in a call, not in quiet hours).

Otherwise the app gets NormalOpen. If Screen Time Roasts is turned off, we never roast and everything gets NormalOpen.

## Phases

**Phase 0: finish the merge.** Do this before any game work.
1. `git init` in GAL, with a .gitignore matching GetALife's (keystore, keystore.properties, local.properties, build folders). Then make the first commit.
2. Decide on the daily limit card. The code removed it but the README and NagController still describe it. Either restore it from GetALife commit 5855a7b or remove it from the docs.
3. Persist the 2-minute gap guard so a crash loop cannot show a card on every restart.
4. Create the release keystore and back it up. Build the release APK.
5. Do a real-phone pass: speech, open by voice, "tap to bring Sidekick back" after a reboot, and one phone with a strict battery manager.

**Phase 1: stage and movement.** Build StageOverlay, StageView, World, BlobActor and MovementTuning. Add a debug-only "playground" scene where the blob runs and jumps across the screen, so we can tune the feel on a real phone. Done when the movement feels good to Sangar and frame time stays under 8 ms on a mid-range phone.

**Phase 2: scenes.** Add AppCrate, Props, SceneDirector, the three scenes, AppHabits and the roast phrase batch. Voice "open X" goes through SceneDirector. Done when all three scenes play end to end on a real phone and every scene can be skipped with a tap.

**Phase 3: polish and settings.** Add short original sound effects (off by default), haptics on hits, a Quick open switch, and respect the system "remove animations" setting (no scene, just open). Add the Xiaomi pop-up permission hint in setup.

**Phase 4 (later, optional): launcher mode.** Experiment with GAL as a home screen where the blob runs to real icons.

## Tests

- Unit tests: physics step is deterministic for a fixed timestep, jump apex and landing positions, the SceneDirector choice table, the AppHabits thresholds and cooldowns, and phrase checks for the new batch.
- Manual checklist on a real phone for every scene: skip by tap, app opens on the first try, no touches lost after the stage leaves, and it works with the keyboard open and in landscape.

## Risks

| Risk | Plan |
| --- | --- |
| Stage blocks touches while visible | Short scenes, tap to skip, remove the window right after |
| Feels slow after the novelty wears off | Quick open switch and shorter NormalOpen. Only roast scenes are long. |
| OEM pop-up restrictions (Xiaomi) | Detect it and show the setting in setup |
| Category missing on some apps | Fallback package list, plus a "treat as social" toggle per app in Settings |
| Play review of overlay plus mic service | Same as today. A sideload or limited distribution release first. |
