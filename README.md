# 🌱 Sprout

A simple, private, **offline-first Android app** for tracking a newborn's first weeks and
months — and for keeping an eye on the mother's postpartum recovery too.

All data stays on the device (Room/SQLite). No accounts, no cloud, no tracking.

## Features

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

## License

MIT — see [LICENSE](LICENSE).
