# 🌱 Sprout

A simple, private, **offline-first Android app** for tracking a newborn's first weeks and
months — and for keeping an eye on the mother's postpartum recovery too.

All data stays on the device (Room/SQLite). No accounts, no cloud, no tracking.

## Features

- **👶 Multiple babies** — track twins or siblings side by side. Switch the active
  baby from the home top bar; add, edit, **stop tracking** (keeps the history), or
  permanently delete a baby and all of their logs from the Babies screen.
- **🍼 Feeding** — breast (left/right/both), bottle (ml), and solids, with timestamps.
- **😴 Sleep** — log naps and nights with start/end times and automatic durations.
- **🧷 Diapers** — wet / dirty / mixed changes.
- **📏 Growth** — weight, height and head circumference, with a weight-trend chart.
- **💚 Mother's health** — postpartum check-ins: mood, bleeding (lochia), breast comfort, notes.
- **🏠 Dashboard** — today's feeds, sleep total and diaper count at a glance.

## Tech stack

| Area | Choice |
|------|--------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM (ViewModel + StateFlow) |
| Persistence | Room (SQLite), fully offline |
| Navigation | Navigation Compose |
| Min SDK | 26 (Android 8.0) · Target SDK 35 |
| Build | Gradle (Kotlin DSL) + version catalog |

## Project layout

```
app/src/main/java/com/gproust/sprout/
├── data/
│   ├── local/        # Room entities, DAOs, database, type converters
│   └── SproutRepository.kt
└── ui/
    ├── common/       # Shared composables + date/format helpers
    ├── theme/        # Material 3 theme
    ├── navigation/   # NavHost + bottom navigation
    ├── home/  feeding/  sleep/  diaper/  growth/  mother/  profile/
```

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

To run it, open the project in Android Studio and press **Run**, or install the APK on a
device with `adb install`.

## Continuous integration

Every push and pull request runs [GitHub Actions](.github/workflows/ci.yml): lint, unit
tests, and a debug APK build. The APK is uploaded as a build artifact.

The [release workflow](.github/workflows/release.yml) is **manual** — run it from
**Actions → Release → Run workflow** to build the signed `.aab`/`.apk`; pass a tag
(e.g. `v1.0`) to also publish a GitHub Release with the APK attached.

## Releasing & publishing

See **[docs/RELEASING.md](docs/RELEASING.md)** for the full step-by-step process for
publishing to the Google Play Store (plus the GitHub Release / F-Droid routes).

## Privacy

Sprout collects nothing — all data stays on your device. See [PRIVACY.md](PRIVACY.md).

## License

Sprout is free, open-source software licensed under the **GNU General Public
License v3.0** — see [LICENSE](LICENSE).

Anyone may use, study, modify and redistribute Sprout, **provided any
distributed version stays open-source under the GPLv3** (copyleft). This keeps
forks honest: a redistributed, modified version must also publish its source —
no one can hide proprietary or malicious changes inside a closed fork.

The **name "Sprout" and the app icon/logo are reserved** and are *not* covered
by the GPL — forks must rename and re-brand. See [TRADEMARK.md](TRADEMARK.md).

Copyright © 2026 Guillaume Proust.
