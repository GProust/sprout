# Changelog

All notable changes to Sprout are documented here. This project follows
[Semantic Versioning](https://semver.org/) for `versionName`.

## [Unreleased]

- 📟 Fixed **tapping the widget opening Household sharing instead of Feeding**,
  usually with a "nothing was merged" message on top. The widget asked for a
  screen using the same kind of intent Sprout uses to open an invitation or a
  replica, so a tap could be read as a file to merge — and a file the app had
  been handed earlier stayed pending, ready to reappear the next time the app
  was reopened or the screen rotated. The widget now asks in a way that cannot
  be mistaken for a file, and a file is only ever opened once.

## [1.6.0] — 2026-08-16

- 🏠 **You can share a baby's record with the people looking after them.**
  Usually the other parent, sometimes a grandparent too — and still with no
  account, no server and nothing stored anywhere but your phones. One of you
  sends an **invitation file** through whatever you already use to send each
  other things; from then on either phone can send the other its entries, and
  both sides end up with everything. Nothing is overwritten and nothing is
  duplicated: entries added on both phones simply come together, the most
  recent version of a changed entry wins, and a deletion travels like anything
  else. The first exchange asks the one question that can't be guessed —
  whether to keep both histories or to start sharing from the day you paired.
  Your own **check-ins never leave your phone**, whatever you share; expressed
  milk does, unless you'd rather keep the stash to yourself. The Sharing screen
  lists the phones you've heard from and can **remove one**, which changes the
  household's key — everyone who stays needs a new invitation, which is the
  honest version of taking access away.
- 📶 **When you're in the same room, it can happen on its own.** Turn on *Sync
  when you're together* and, whenever you open Sprout, it looks for the
  household's phones over Bluetooth for a few seconds and exchanges with
  whoever is there. It never runs in the background, never searches while the
  app is away, and never asks for your location — it only ever recognises a
  phone that already has your household's key. Sending a file still works from
  anywhere, and is what phones on Android 11 or older keep using.
- 💚 **The daily check-in is now a choice from the first launch.** The setup
  step that asks what to call you also asks whether you'd like it: a card on
  your dashboard for how you're doing, never a notification. Turn it down and
  Sprout never offers it — no card, no wellbeing shortcut — instead of showing
  it until you find the switch in Settings, where it stays if you change your
  mind. The next step still asks about giving birth and breastfeeding, since
  those also shape the wellbeing entries you add yourself.

## [1.5.0] — 2026-08-15

- 🥛 **Expressed milk now has a place of its own.** Pumping gets its own screen
  from the dashboard's log list: the amount, the time (with the date, since it
  usually gets entered once the bottle is already away), optionally the side,
  and where the milk went — fridge, freezer, room temperature, or straight to
  the baby. Above the history sits the **milk stash**: how much is actually
  available in each place, leaving out what has been used and anything kept
  past the usual 4 hours / 4 days / 6 months guidance, so the number is what
  can be given today. Each batch carries its own keep-by date and can be
  **marked used in one tap**, with an undo. The stash belongs to the parent
  rather than to a baby — with twins it feeds either of them.
- 📟 A bottle no longer hides which breast comes next. When the last feed is a
  bottle or some solids, the widget adds a small line — **"Last breast: Left ·
  3 h ago"** — for any breastfeed in the past 24 hours, so the alternation
  survives a feed in between. It's the side the breastfeed *started* on, the
  one the next feed alternates away from.

## [1.4.6] — 2026-08-05

- 🌍 Fixed **choosing a language doing nothing** for installs from Play. Play
  only delivers the languages matching the device, so picking any other one
  silently fell back to the device's language — the choice was saved, the app
  just had no translation to show. Every install now carries all 7.
- 📟 The widget now shows the side the last breastfeed **started** on, not the
  one it ended on. A session that went left then right reads "Left", because
  the side you need at the next feed is the opposite of the one you began with.
- ⏱️ The widget's **"x min ago" now keeps counting**. It was written once when
  the widget was drawn and the system only refreshes widgets every half hour,
  so a feed from a quarter of an hour ago could still read "just now". It ticks
  each minute while the phone is awake, and refreshes whenever you open Sprout.

## [1.4.5] — 2026-08-05

- 📟 The widget is now **drawn by Sprout itself** instead of through the
  widget library's update mechanism, which on Play builds accepted every
  refresh and then quietly did nothing — the cause of the spinner that four
  previous attempts didn't shift. Nothing about how the widget looks changes.

## [1.4.4] — 2026-08-05

- 📟 Another go at the **widget stuck on its loading spinner** in installs from
  Play. Pinning the widget's class name in 1.4.3 wasn't it — a report showed
  the widget rendering fine on demand while the update the launcher asks for
  quietly did nothing. Release builds were stripping parts of the widget
  library they only reach indirectly; those are now kept whole.
- 🩺 Widget diagnostics gained a **"Force a refresh"** button, and now records
  what the refresh and the drawing step threw, if anything — so a failure that
  used to disappear silently ends up in the report.

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
