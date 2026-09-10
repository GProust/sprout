# 🌱 Sprout

A private, **offline-first app** for tracking a newborn's first weeks and months —
feeds, pumping, sleep, diapers, growth and medications — while also looking after the parent's own
postpartum recovery.

All data stays on the device. **No accounts, no cloud, no tracking.** Sprout speaks 7
languages and can gently remind you about feeds and treatments.

The **Android** app ships on Google Play. An **iOS** app is being built alongside it
([ADR-0015](docs/adr/0015-native-ios-in-this-repository.md)) — natively, in this same
repository, sharing the format two phones sync over rather than sharing code.

## Features

- **👶 Multiple babies** — track twins or siblings side by side. Switch the active baby from
  the home top bar; add, edit, **stop tracking** (keeps the history), restore, or permanently
  delete a baby and all of their logs from the Babies screen.
- **🍼 Feeding** — breast, bottle (ml) and solids with timestamps, plus a **live breastfeeding
  timer**: start a session, tap to switch sides, and it's saved with the time spent on each side.
- **🥛 Pumping** — log expressed milk: when, how much, which side, and where it went
  (fridge, freezer, room temperature — or straight to the baby). A **milk stash** card adds
  up what's actually available in each place, and each batch shows how long it still keeps
  (the usual 4 hours / 4 days / 6 months guidance). Once a bottle is drunk, **one tap marks
  it used** — with an undo, in case of a mistap.
- **😴 Sleep** — log naps and nights with start/end times and automatic durations.
- **🧷 Diapers** — wet / dirty / mixed changes.
- **📏 Growth** — weight, height and head circumference, with a weight-trend chart.
- **🌱 Growth spurts** — the dashboard gently flags the typical growth-spurt periods
  (around 1, 3, 6 and 9 weeks, then 3, 6 and 9 months) while your baby is in or near one,
  and an opt-in alert can give you a heads-up when such a period begins.
- **💊 Treatments & medication reminders** — recurring treatments (e.g. *Vitamin D, 1 drop, every
  day for a year*): name, optional dose, frequency (daily / every N days / weekly) and one or more
  reminder times, with local notifications.
- **⏰ Feeding reminders** — an optional nudge when it's been too long since the last feed; set a
  default interval and, if you like, override it per baby.
- **💚 Parent wellbeing** — a gentle daily check-in tailored to *you*, by capability rather than
  role: mood and notes for everyone; healing and bleeding (lochia) if you gave birth (worded for a
  vaginal or C-section delivery); breast comfort if you're breastfeeding. Covers every kind of
  family — co-nursing, adoptive, single parents. It **waits on the dashboard** instead of opening
  at launch — nothing to dismiss during a 3 a.m. feed — and can be **turned off entirely**, whole
  check-in or question by question, without losing what you've already logged. Setup asks whether
  you want it at all, right where it asks your name, so it's a choice from the first launch.
- **🏠 Dashboard** — baby's age, today's feeds, sleep total and diaper count, and time since the
  last feed, at a glance.
- **🏠 Sharing with your household** — one baby's record, on the phones of everyone looking after
  them, with **no account, no server and no cloud storage**. You pair by sending an invitation file
  through whatever channel you already use; after that the phones exchange **encrypted** replicas
  that merge without duplicating or overwriting anything. On Android 12+ it can also happen
  **automatically over Bluetooth** when you're in the same room with the app open — a few seconds
  of looking, never a background service, and never the location permission. Your own check-ins
  stay on your phone whatever you share.
- **🌍 7 languages** — English, French, German, Spanish, Italian, Polish and Portuguese. Sprout
  follows your device language (falling back to English) and also offers an in-app language picker
  with flags.

Reminders are local notifications scheduled on-device (no server), and survive a reboot.

## Tech stack

**Android** (shipping):

| Area | Choice |
|------|--------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM (ViewModel + StateFlow) |
| Persistence | Room (SQLite), fully offline |
| Navigation | Navigation Compose |
| Notifications | AlarmManager + notification channels (on-device reminders) |
| Localization | 7 locales; system-default with an in-app override |
| Min SDK | 26 (Android 8.0) · Target SDK 35 |
| Build | Gradle (Kotlin DSL) + version catalog |

**iOS** ([ADR-0015](docs/adr/0015-native-ios-in-this-repository.md), in progress):

| Area | Choice |
|------|--------|
| Language | Swift |
| UI | SwiftUI |
| Crypto & compression | CryptoKit + Compression — system frameworks, no dependencies |
| Deployment target | iOS 17 |
| Build | Swift Package Manager; the Xcode project is generated from `project.yml` |

Neither app depends on the other's code. What they share is
[`spec/`](spec/) — the formats two phones sync over, and the vectors that keep
the two implementations honest.

## Project layout

```
android/   The Android app — Kotlin, Compose, Room. Ships on Play.
ios/       The iOS app — Swift, SwiftUI. SproutKit/ is the wire formats;
           Sprout/ is the app, still a shell.
spec/      What the two apps must agree on, plus conformance vectors both
           test suites check themselves against.
docs/      Decision records (ADRs and BDRs), shared by both.
```

```
android/app/src/main/java/com/gproust/sprout/
├── data/
│   ├── local/        # Room entities, DAOs, database, type converters
│   ├── sync/         # Household sharing: replicas, merge, pairing (+ nearby/ for Bluetooth)
│   └── SproutRepository.kt
├── notifications/    # Treatment & feeding reminders (AlarmManager + receivers)
└── ui/
    ├── common/       # Shared composables + date/format helpers
    ├── theme/        # Material 3 theme
    ├── navigation/   # NavHost + bottom navigation
    ├── onboarding/  startup/  checkin/   # First-run + daily check-in flow
    ├── home/  feeding/  pumping/  sleep/  diaper/  growth/  treatments/
    ├── health/       # Parent wellbeing
    ├── profile/      # Babies manager
    ├── sync/         # Sharing with the household
    └── settings/     # Language picker
```

Localized strings live in `android/app/src/main/res/values/` (English, the default) and
`values-{fr,de,es,it,pl,pt}/`.

```
ios/
├── SproutKit/        # Swift package: the formats two phones exchange —
│   │                 #   secret, sealed replica, invitation, session framing,
│   │                 #   household beacon. No UI, no radio, no database.
│   └── Tests/        #   checked against spec/vectors/ on every CI run
└── Sprout/           # The app. project.yml is the Xcode project (XcodeGen);
                      #   the .xcodeproj is generated, not committed.
```

## Design decisions

The significant decisions behind Sprout are recorded with their rationale, split into two logs:

- **[docs/adr/](docs/adr/)** — *architecture* decisions: local-only storage, no first-party
  backend, the tech stack, the localization mechanism, CI, and more.
- **[docs/decisions/](docs/decisions/)** — *business & product* decisions: the inclusive parent
  model, supporting multiple babies, defaulting to the device language, licensing, and other
  domain/policy rules.

## Building

> All Gradle commands below run from **`android/`** — that is where the Android
> app lives since [ADR-0015](docs/adr/0015-native-ios-in-this-repository.md).

You'll need **JDK 17** and the **Android SDK** (easiest via [Android Studio](https://developer.android.com/studio)).

```bash
# Debug APK
./gradlew assembleDebug

# Unit tests
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug
```

The built APK lands in `android/app/build/outputs/apk/debug/app-debug.apk`.

To run it, open the project in Android Studio and press **Run**, or install the APK on a device
with `adb install`.

## Continuous integration

Every push and pull request runs [GitHub Actions](.github/workflows/ci.yml): lint, unit tests, and
a debug APK build. The APK is uploaded as a build artifact. A second workflow renders every screen
on an emulator and attaches the screenshots to the pull request.

The [iOS workflow](.github/workflows/ios.yml) builds and tests `SproutKit` and compiles the app for
the simulator on a macOS runner, and checks that `spec/vectors/` is exactly what its generator
produces. It needs **no Apple Developer account, no certificate and no Mac** — a simulator build is
unsigned — so iOS is verified by CI the same way Android is
([ADR-0006](docs/adr/0006-ci-as-build-verifier-and-screenshots.md)). Path filters keep an
Android-only change off the Mac and an iOS-only change off the Android job.

The [release workflow](.github/workflows/release.yml) is **manual** — run it from
**Actions → Release → Run workflow** with a version (e.g. `1.3.0`) to bump the app version, build
the signed `.aab`/`.apk`, and publish a GitHub Release with the APK attached.

## Releasing & publishing

See **[docs/RELEASING.md](docs/RELEASING.md)** for the full step-by-step process for publishing to
the Google Play Store (plus the GitHub Release route).

## Privacy

Sprout collects nothing — all data stays on your device. See [PRIVACY.md](PRIVACY.md).

## Supporting Sprout

Sprout is free, open-source, ad-free and account-free, and it will stay that
way — **every feature is in the app whether or not anyone ever donates.**
Nothing is unlocked by giving, because nothing is locked.

If it helped you through the early weeks and you'd like to chip in towards its
development:

- [**GitHub Sponsors**](https://github.com/sponsors/gproust) — one-off or monthly.
- [**Buy Me a Coffee**](https://buymeacoffee.com/gproust) — a one-off tip, no account needed.

The same two links sit at the bottom of the app's Settings screen, and nowhere
else: there is no prompt, no dialog and no reminder. Both simply open in your
browser — the app still has **no internet permission** and still cannot make a
network request of its own. The reasoning is recorded in
[BDR-11](docs/decisions/0011-donations-are-a-link-out-and-buy-nothing.md).

## License

Sprout is free, open-source software licensed under the **GNU General Public License v3.0** — see
[LICENSE](LICENSE).

Anyone may use, study, modify and redistribute Sprout, **provided any distributed version stays
open-source under the GPLv3** (copyleft). This keeps forks honest: a redistributed, modified
version must also publish its source — no one can hide proprietary or malicious changes inside a
closed fork.

**Forking and improving Sprout is welcome.** The **name "Sprout" and the app icon/logo are
reserved** and are *not* covered by the GPL — but that only means you should use your own name and
icon if you *publish* a build others could mistake for the official app. Forking, improving and
contributing back are unrestricted. See [TRADEMARK.md](TRADEMARK.md).

Copyright © 2026 Guillaume Proust.
