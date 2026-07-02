# Changelog

All notable changes to Sprout are documented here. This project follows
[Semantic Versioning](https://semver.org/) for `versionName`.

## [1.3.0] — 2026-07-02

First version published on **Google Play**.

- 🌍 Store listing localized into the app's 7 languages (with pt-PT and
  pt-BR variants).
- 🖼️ Store graphics (icon, feature graphic) and high-resolution screenshots.
- No app-behaviour changes since 1.2.0.

## [1.2.0] — 2026-07-01

- 🍼 Breastfeeding sessions now track **per-stretch timing**: each stretch on
  a side is recorded with its time range, sessions can be logged manually as
  a sequence of sides (e.g. left → right → left), and the feeding history
  shows an expandable per-stretch breakdown.

## [1.1.0] — 2026-06-30

- 🍼 Breastfeeding timer is now a **full-screen session view**; feeds can be
  logged with a manual length, and existing feeding logs are editable.
- 🧷 Diaper log reworked — a change is now a checklist of what's present
  (urine and/or stool) rather than a single type, and stool changes can
  record a colour from a predefined scale inspired by infant stool colour
  cards (healthy yellow/green/brown, the pale/clay/white range, plus black
  and red). Existing entries are migrated automatically.
- 🏠 Fixed the Home tab restoring the wrong screen after using a Home
  shortcut.

## [1.0.0] — 2026-06-28

First public release (GitHub).

- 🍼 Feeding log — breast (left/right/both), bottle (ml), and solids.
- ⏱️ Live breastfeeding timer with side switching.
- 😴 Sleep log — naps and nights with automatic durations.
- 🧷 Diaper log — wet / dirty / mixed.
- 📏 Growth — weight, height, head circumference, with a weight-trend chart.
- 💊 Treatments — per-baby medications with scheduled reminders.
- ⏰ Feeding reminders (opt-in) — max time between feeds, with a per-baby
  override.
- 👶 Multiple babies (twins/siblings), with stop-tracking and delete.
- 💚 Postpartum check-ins — mood, bleeding, breast comfort, notes — one
  question per page, tailored by capability (gave birth / breastfeeding)
  rather than role.
- 🏠 Dashboard summarising today's feeds, sleep and diapers.
- Two-tab wellbeing board with per-parent edit permissions.
- 🌍 7 languages — English, French, Italian, German, Spanish, Polish and
  Portuguese — following the system language, with an in-app picker.
- Fully offline (Room/SQLite); no accounts, no cloud, no tracking.
