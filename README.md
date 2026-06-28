# 🌱 Sprout

A private, **offline-first Android app** for tracking a newborn's first weeks and months —
feeds, sleep, diapers, growth and medications — while also looking after the parent's own
postpartum recovery.

All data stays on the device (Room/SQLite). **No accounts, no cloud, no tracking.** Sprout
speaks 7 languages and can gently remind you about feeds and treatments.

## Features

- **👶 Multiple babies** — track twins or siblings side by side. Switch the active baby from
  the home top bar; add, edit, **stop tracking** (keeps the history), restore, or permanently
  delete a baby and all of their logs from the Babies screen.
- **🍼 Feeding** — breast, bottle (ml) and solids with timestamps, plus a **live breastfeeding
  timer**: start a session, tap to switch sides, and it's saved with the time spent on each side.
- **😴 Sleep** — log naps and nights with start/end times and automatic durations.
- **🧷 Diapers** — wet / dirty / mixed changes.
- **📏 Growth** — weight, height and head circumference, with a weight-trend chart.
- **💊 Treatments & medication reminders** — recurring treatments (e.g. *Vitamin D, 1 drop, every
  day for a year*): name, optional dose, frequency (daily / every N days / weekly) and one or more
  reminder times, with local notifications.
- **⏰ Feeding reminders** — an optional nudge when it's been too long since the last feed; set a
  default interval and, if you like, override it per baby.
- **💚 Parent wellbeing** — a gentle daily check-in tailored to *you*, by capability rather than
  role: mood and notes for everyone; healing and bleeding (lochia) if you gave birth (worded for a
  vaginal or C-section delivery); breast comfort if you're breastfeeding. Covers every kind of
  family — co-nursing, adoptive, single parents.
- **🏠 Dashboard** — baby's age, today's feeds, sleep total and diaper count, and time since the
  last feed, at a glance.
- **🌍 7 languages** — English, French, German, Spanish, Italian, Polish and Portuguese. Sprout
  follows your device language (falling back to English) and also offers an in-app language picker
  with flags.

Reminders are local notifications scheduled on-device (no server), and survive a reboot.

## Tech stack

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

## Project layout

```
app/src/main/java/com/gproust/sprout/
├── data/
│   ├── local/        # Room entities, DAOs, database, type converters
│   └── SproutRepository.kt
├── notifications/    # Treatment & feeding reminders (AlarmManager + receivers)
└── ui/
    ├── common/       # Shared composables + date/format helpers
    ├── theme/        # Material 3 theme
    ├── navigation/   # NavHost + bottom navigation
    ├── onboarding/  startup/  checkin/   # First-run + daily check-in flow
    ├── home/  feeding/  sleep/  diaper/  growth/  treatments/
    ├── health/       # Parent wellbeing
    ├── profile/      # Babies manager
    └── settings/     # Language picker
```

Localized strings live in `app/src/main/res/values/` (English, the default) and
`values-{fr,de,es,it,pl,pt}/`.

## Design decisions

The significant decisions behind Sprout are recorded with their rationale, split into two logs:

- **[docs/adr/](docs/adr/)** — *architecture* decisions: local-only storage, no first-party
  backend, the tech stack, the localization mechanism, CI, and more.
- **[docs/decisions/](docs/decisions/)** — *business & product* decisions: the inclusive parent
  model, supporting multiple babies, defaulting to the device language, licensing, and other
  domain/policy rules.

## Building

You'll need **JDK 17** and the **Android SDK** (easiest via [Android Studio](https://developer.android.com/studio)).

```bash
# Debug APK
./gradlew assembleDebug

# Unit tests
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug
```

The built APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

To run it, open the project in Android Studio and press **Run**, or install the APK on a device
with `adb install`.

## Continuous integration

Every push and pull request runs [GitHub Actions](.github/workflows/ci.yml): lint, unit tests, and
a debug APK build. The APK is uploaded as a build artifact. A second workflow renders every screen
on an emulator and attaches the screenshots to the pull request.

The [release workflow](.github/workflows/release.yml) is **manual** — run it from
**Actions → Release → Run workflow** to build the signed `.aab`/`.apk`; pass a tag (e.g. `v1.0`) to
also publish a GitHub Release with the APK attached.

## Releasing & publishing

See **[docs/RELEASING.md](docs/RELEASING.md)** for the full step-by-step process for publishing to
the Google Play Store (plus the GitHub Release / F-Droid routes).

## Privacy

Sprout collects nothing — all data stays on your device. See [PRIVACY.md](PRIVACY.md).

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
