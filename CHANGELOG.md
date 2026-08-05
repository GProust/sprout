# Changelog

All notable changes to Sprout are documented here. This project follows
[Semantic Versioning](https://semver.org/) for `versionName`.

## [1.4.3] — 2026-08-05

- 📟 Fixed the **widget staying on its loading spinner after an app update**,
  until the app was uninstalled and reinstalled. Release builds renamed the
  widget's class on every build, and Glance identifies a placed widget by that
  name — so after an update it no longer recognised the widget already on the
  home screen. The name is now pinned across releases.

## [1.4.2] — 2026-08-05

- 📅 Entries logged by hand can now be **put on a past date**, not just a time.
  Feeding, Sleep, Diapers and Wellbeing each gained a date field in their log
  form (starting on today), so a feed remembered the next morning lands on the
  day it actually happened. Editing an existing feed can move it to another day
  the same way, and dates in the future are no longer selectable.
- 🌙 A sleep whose wake time is **earlier than its bedtime** is now stored as
  running past midnight, instead of ending before it started.
- 🛟 Deleting an entry now **asks first**, on every screen that logs one
  (Feeding, Sleep, Diapers, Growth, Wellbeing, Treatments). The bin sits right
  next to the rest of the card and entries are gone for good — one mis-tap
  while holding a baby shouldn't cost you the log.
- 📟 The widget now covers **every kind of feed, not just breastfeeds**, and
  names the baby it belongs to — so with twins you can tell at a glance whose
  feed you're looking at. Each entry is labelled by kind (Breast, Bottle,
  Solids) and shows the one detail that kind carries: which breast it ended
  on, or how much came out of the bottle.
- 📟 Fixed the **widget never updating after it was placed**: feeds logged
  afterwards still showed "No breastfeed yet", and a breastfeeding session
  stayed on screen as "in progress" after it had ended. The widget now
  follows the data live instead of showing whatever it read when it was
  first drawn.
- 🩺 **Widget diagnostics** (Settings → Troubleshooting): tests the
  home-screen widget on the spot, shows what it did recently, and offers the
  result as text to copy or share. Added because the widget still isn't
  loading for some devices and it runs where nothing is visible — no screen,
  no error, and no practical way to read logs from a Play build.
- 📟 When the widget can't render it now **says so on the home screen**
  instead of sitting on a loading spinner for ever.

## [1.4.1] — 2026-08-04

- 📟 Fixed the **home-screen widget never loading**: it sat on its loading
  spinner instead of ever showing the last breastfeed. Release builds were
  shrinking away the layouts Glance inflates to draw the widget.

## [1.4.0] — 2026-08-04

- 💚 The **daily wellbeing check-in no longer opens at launch**. It waits as a
  card on the dashboard ("Your daily check-in") until you feel like it — so
  opening Sprout for a 3 a.m. feed never means getting past questions about
  yourself first. "Not today" puts the card away until tomorrow.
- 🔕 Wellbeing tracking can now be **switched off entirely** (Settings → Daily
  check-in): no card, no shortcut, nothing asked. Past check-ins are kept and
  come back if you turn it on again — as do the per-question toggles.
- 🌱 **Growth spurt periods**: the dashboard now shows a gentle note while
  your baby is in — or a few days from — one of the typical growth spurt
  windows (around 1, 3, 6 and 9 weeks, then 3, 6 and 9 months), reassuring you
  that extra hunger and fussiness are normal. An **opt-in alert** (Settings,
  off by default) can also give you a heads-up when such a period begins.
- 🗂️ Tracking screens (Feeding, Sleep, Diapers, Growth) are now
  **history-first**: the log list fills the screen — newest entry on top,
  grouped under Today/Yesterday/date headers — and the log form opens in a
  bottom sheet from a "+" button, so recent entries stay visible while
  logging. The Growth screen keeps its weight-trend chart on top; the live
  breastfeeding bar is unchanged.
- 📟 New **home-screen widget** showing the side of the last breastfeed (the
  side of the last stretch when a session switched sides) and how long ago it
  was ("2 h 15 min ago"). While a session is being timed it switches to the
  current side with a live ticking timer. Refreshes whenever a feed is
  logged, and every 30 minutes in between; tapping it opens the app straight
  on the Feeding screen.
- ⏱️ A live breastfeeding session now **survives the app being killed**
  (restored from disk when the app reopens).

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
