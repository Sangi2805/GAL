# GAL for iOS

The iPhone version of GAL. It shares the roast lines and the elephant with the Android app, but iOS works very
differently, so this is its own app (SwiftUI, iOS 16 and later).

## What it does

iOS does not let any app draw over other apps or see which app is open. GAL uses Apple's Screen Time API instead:

- **Roasts.** You pick the apps that eat your time, once, in Apple's own picker. iOS counts the time in those apps,
  and after every "nag me after" stretch (30 minutes by default, so at 30, 60, 90...) it wakes GAL, which sends a
  notification with a roast. Up to 12 a day; the 12th is the elephant giving up on you.
- **The "Nooo" screen.** Opening a picked app shows the elephant first, with a roast that names the app
  ("Are you married to Instagram?"). *Fine, close it* goes back home; *Open anyway* lets you in for 15 minutes
  (Apple's shortest window), then the screen comes back.
- **Home.** The elephant strolls across the top of the screen. Below: roasts on or off, the "nag me after" time,
  the card style (Spicy or You may cry), the "Nooo" screen on or off, change apps, and a test roast.
- **First run.** The "describe yourself in one word" joke, one Allow button (Screen Time and notifications), pick
  apps, Start.

Not possible on iOS: the elephant walking over other apps, reacting the moment you open a useful app, and a
continuous-session timer (iOS counts time per day, not per session). GAL never sees which apps you picked, only
opaque tokens, and it has no internet access.

## Layout

| Folder | What it is |
| --- | --- |
| `GAL/` | The app: onboarding, home, the elephant (`Elephant.swift`, a port of `BlobPainter.kt`) |
| `GALMonitor/` | Device Activity Monitor extension: sends the roasts, ends the "Open anyway" window |
| `GALShieldConfig/` | Shield Configuration extension: draws the "Nooo" screen |
| `GALShieldAction/` | Shield Action extension: handles its two buttons |
| `Shared/` | Settings (App Group), phrase lines, monitoring and shield helpers, the shield picture |
| `project.yml` | The Xcode project, as an [XcodeGen](https://github.com/yonaskolb/XcodeGen) file |

`Shared/Phrases/*.txt` and the pictures are made from the Android sources by `tools/ios/prepare_assets.py`. Run it
again after changing `phrases.json` or the elephant.

## Building

You need a Mac with Xcode 16 or later.

1. `brew install xcodegen`, then in this folder run `xcodegen generate` and open `GAL.xcodeproj`.
2. In each of the four targets, open **Signing & Capabilities** and pick your team. Family Controls and App
   Groups need a paid Apple Developer membership.
3. Plug in your iPhone and press Run. Screen Time features do not work in the simulator.

Shipping to TestFlight or the App Store also needs Apple to approve the **Family Controls (Distribution)**
entitlement for each of the four bundle IDs (request it on the Apple Developer site). Development builds work
without it.

Without a Mac, GitHub checks that the app builds: `.github/workflows/ios.yml` runs on every push that changes
`ios/` (Actions tab on GitHub). It builds for the simulator without signing, so it proves the code compiles, not
that it runs.
