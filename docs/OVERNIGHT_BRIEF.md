# Brief for the overnight build

> **Historical document.** This was written before voice was dropped and before the pink full-body elephant. It still mentions the microphone, "open X" voice commands, the green blob and hammer scenes. The README is the current description of the app.

Paste this into a Claude Code cloud session (Fable) opened on this repo.

Build GAL by following `docs/GAME_LAYER_PLAN.md`, which is approved. Read it fully first. Then read `README.md`, the code under `app/src/main/java/com/sangar/gal/`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `tools/phrases/build_phrases.py` and `app/src/test`.

## Order of work

1. **Phase 0, code items only.**
   - Item 2: drop the daily limit card leftovers, and fix the README and the comment in NagController.kt.
   - Item 3: change the session card rules.
     - Slider stays at 1 minute to 8 hours, default 30.
     - The gap between cards equals the threshold.
     - Exactly 12 cards per session. Remove the auto 6/4/3 cap and the Advanced override.
     - The 12th card is a give-up line in both packs, with the bored face. Write about 80 original give-up lines for "You may cry" in `tools/phrases/cry_10_giveup.txt` and regenerate `phrases.json` with the builder.
     - Update the tests and the README.
   - Item 4: persist the 2-minute gap guard.
   - Skip push, keystore and real-phone testing.
2. **Phase 1:** stage overlay, movement engine and a debug playground scene.
3. **Phase 2:** crate, props, SceneDirector, the NormalOpen, RoastOpen and NotFound scenes, AppHabits, about 150 original app-roast lines in `tools/phrases/batch_15_app_roast.txt` with an `{app}` placeholder, and voice "open X" routed through SceneDirector.
4. **Phase 3:**
   - Quick open switch.
   - Respect reduce-motion.
   - Haptics on hits.
   - Optional original sounds, off by default. Only add them if we can generate them ourselves.
   - Xiaomi pop-up permission hint in setup.

Skip Phase 4. Commit after each phase. If the budget runs short, stop at a clean commit.

## Hard rules

- **Offline.** No INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE or QUERY_ALL_PACKAGES. No Accessibility Service. No new libraries unless truly needed.
- **Our own character only.** Use our green blob (mint #5CE79B to #1FA463, rim #14663C, ink #0E2B1B). It should move like a platformer hero, but only in feel. Nothing Nintendo-like: no red cap, moustache, overalls, question blocks, pipes, Nintendo-style coins, catchphrases or sound imitations. The app shows up as our own wooden crate with the real app icon.
- **Short scenes.** Each scene can be skipped with a tap and lasts at most about 3.5 s. The full-screen stage window exists only while a scene plays. Launch the app while the stage is still visible.
- **Roast lines.** They must be original, cheeky and only about phone habits. No slurs, body shaming, mental health, self-harm or appearance jokes.
- **Docs voice.** Plain English, "we", no em-dashes.

## Verification

Run `./gradlew testDebugUnitTest lintDebug assembleDebug` if the environment can reach Google Maven and Gradle. If it cannot, build a kotlinc compile check against `android.jar`, plus pure-JVM unit tests, and say exactly what was and was not verified.

## Finish

Write `docs/OVERNIGHT_REPORT.md` and commit it. It should cover:

- what was built in each phase, with commit hashes
- verification results
- anything stubbed or unverified
- decisions made along the way
- risks
- morning steps for Sangar: run the Gradle command above in Android Studio, then a real-phone checklist (tap to skip, app opens on the first try, no lost touches, keyboard open, landscape, the Xiaomi pop-up setting)
