# Changelog

All notable changes to Sprout are documented here. This project follows
[Semantic Versioning](https://semver.org/) for `versionName`.

## [Unreleased]

- 🩺 **The doctor's record is reachable again if you track one baby.** The share
  action shipped in 1.10.0 sat on the baby's own tab — which a family with a
  single child never sees, because with nothing to choose between, the dashboard
  *is* that page. It has moved onto the page itself, under the totals beside
  "See the statistics", so it is in the same place whether you are tracking one
  baby or three.

## [1.10.0] — 2026-09-08

- 🩺 **A record you can hand to a doctor.** The share button on your baby's page
  now makes two files out of what you have already logged: a **PDF report** to
  print or pass across the desk, and a **spreadsheet** with one sheet for each
  kind of entry, for whoever wants the rows. Pick the period — the last 7, 30 or
  90 days, everything since the birth, or two dates of your own — and Sprout
  totals the feeds, the sleep and the nappies day by day, draws the week's shape
  as charts, plots weight, length and head circumference against the WHO curves,
  and lays the treatments out as a timeline over the same dates. It says what was
  logged and stops there: there is no verdict in it anywhere, and the page says
  as much.
- 🔒 **Both files are made on your phone**, and go nowhere until you choose where
  to send them. Sprout still has no internet permission and still cannot make a
  network request of its own. Your notes stay out of the file unless you switch
  them on, and your own check-ins and pumping log are never in it — they are
  yours, not your baby's.
- 📏 **The growth curves can be read against one reference.** The report offers
  the same both / girls / boys choice the Statistics screen has, so a doctor gets
  a single centile instead of a span if you want one. It applies to that one file
  and is then forgotten: Sprout still never asks your baby's sex, and still
  stores none.
- 🔐 **Lock the report with a password.** The share screen can now put the PDF
  and the spreadsheet into a single zip encrypted with **AES-256**, using a
  password you choose — for when the file is going to sit in a message thread or
  an inbox rather than being handed over in person. Sprout keeps no copy of the
  password: give it to the doctor by phone or face to face, not in the same
  message as the file, and know that nobody can open the file for you if you
  forget it. The screen says which tools open it before you make it — 7-Zip,
  WinZip, Keka and most Android file managers do; the unzip built into Windows
  and macOS does not.
- 😴 **A sleep can say where it happened, and how they were lying.** Logging a
  nap now offers two optional rows of chips — their own bed, the bedside cot,
  your bed, on a parent, at the breast, or somewhere else you name yourself;
  on their back, on their side, on their tummy. Both are entirely optional and
  tapping the chosen chip again clears it, so a nap is still two taps if that
  is all you want it to be. What you pick shows on the entry alongside the
  times.
- 📊 **The sleep statistics show how those nights divide up.** Under the sleep
  chart, two new breakdowns say how much of the period was slept in each place
  and in each position — how many sleeps, how long they came to, and a bar for
  the share. Sleeps you didn't say anything about are the last line rather
  than being left out, so the shares are shares of *all* the sleep on the card.
  Nothing appears until you have recorded something, and Sprout offers no
  opinion on any of it: it is your record, not a report card.
- 🩺 **The record you hand to a doctor carries them too.** The PDF report now
  prints where the sleeps happened and how your baby was lying underneath the
  sleep chart — each one with the time it came to and how many sleeps it was,
  and the sleeps you noted nothing for still counted, so nothing reads as more
  certain than it is. The spreadsheet carries both on its sleep sheet, so
  nothing you logged is left out of the copy you hand over.

## [1.9.0] — 2026-09-07

- 🤱 **One breastfeed is logged once.** Starting a feed, leaving the app and
  coming back through the widget could leave a second copy of the timer behind
  the screen you landed on — so stopping the feed and then going back a screen
  put you in front of the very same feed, still running, ready to be saved
  again. Sprout now keeps a single running feed that every place showing it —
  the feeding screen, the dashboard card, the widget — sees end at the same
  moment, and the timer can only be saved by whoever stops it first.
- 💊 **Finished treatments step out of the way.** The treatments list now keeps
  the courses that are still running at the top, under **Active**, and gathers
  the ones that are over below them under **Past**, greyed out and with the day
  they ended. Nothing is hidden — a past course still opens with a tap, and
  still shows everything it did before.
- 📱 **Moving to a new phone keeps things straight.** Sprout now says exactly
  what belongs in an Android backup and in the copy made when you set up a new
  handset: your record and your settings come with you, while the identifier
  this phone uses inside your household stays behind. Restore onto a new phone
  and pass the old one on, and the two are now told apart properly — before,
  your household could see one phone where there were two, and "remove this
  phone" could take out the wrong one.
- 🔑 **A new phone says it is unpaired, and means it.** Your household's sharing
  key is locked to the handset and cannot come across, so a restored phone was
  quietly left half-paired: it could not sync, and the next invitation it
  accepted would have skipped the question about what to do with both phones'
  histories. It now starts genuinely fresh and asks properly.
- 🧹 **The widget troubleshooting screen is gone.** It existed to chase one
  bug in the home-screen widget — the one that used to leave it stuck on a
  loading spinner — and that bug has been fixed for several releases now.
  Settings is one section shorter, and your phone writes one less file every
  time the widget refreshes. If the widget ever *can't* draw, it still says so
  on your home screen rather than sitting there silently.
- 💚 **A way to support Sprout, for anyone who wants one.** Settings now ends
  with two links — GitHub Sponsors and Buy Me a Coffee. That is the whole of it:
  there is no prompt, no reminder and no dialog, and **nothing in the app is
  behind them.** Every feature is here for someone who never gives a penny, and
  it will stay that way. Both links open in your browser; Sprout still has no
  internet permission and still cannot make a network request of its own.

## [1.8.0] — 2026-09-05

- 🏠 **Home is now the whole household.** Instead of showing whichever baby was
  selected in a menu, the dashboard gives every baby you track a card of their
  own — how long since the last feed, the last sleep and the last change, and
  their own buttons. With twins that means a feed can no longer land on the
  wrong child because the menu at the top was set to the other one. If you track
  one baby, nothing gets further away: the card simply opens out into the full
  screen, where it always was.
- 🤱 **Start a feed from the dashboard.** One tap, on the breast you did not
  start on last time — Sprout already worked that out for the widget, and now
  says it where you are looking. The other side is right beside it, because it
  is a reminder and not a rule.
- ⏱️ **A running feed or sleep now says so.** If you started a breastfeed and
  came back later, the timer is on the dashboard where you left it instead of
  only on the home-screen widget. A baby who is still asleep is on there too.
- 😴 **A sleep you left open can be closed.** Logging a nap as "still asleep"
  used to be a one-way door: the only way out was to delete it and type it in
  again, and until you did, it went on adding to the day's total by itself.
  There is now a **Woke up** button on the entry, and on the dashboard.
- 🧭 **A simpler bottom bar.** Four places rather than four of the things you
  log: your household, the baby you picked, your trends, and you. Pumping, your
  wellbeing and the daily check-in finally sit together, and everything there is
  to log — medication included, which never used to fit — is a tile on the
  dashboard.

## [1.7.1] — 2026-08-30

- 🧰 **Housekeeping only — nothing in the app itself has changed.** The tools
  and libraries Sprout is built on moved up a version: the Android Gradle
  plugin to 9.3.2, Gradle to 9.7.1 and Jetpack Navigation to 2.10.0. Keeping
  those current is what makes the next fix that *is* visible a small step
  rather than a big one.

## [1.7.0] — 2026-08-16

- 📊 **Statistics.** A new screen, one tap from the dashboard, showing what the
  last 7, 30 or 90 days actually added up to: feeds a day and how much of each
  kind — time at the breast, millilitres of bottle, grams of solids — hours
  asleep and how many times your baby settled, and how many changes were wet and
  how many were dirty. Each with a bar per day, so a week has a shape, and a
  daily average that leaves today out of it while today is still being lived —
  and that never counts days from before your baby was born.
- 📈 **Growth against the WHO curves.** Weight, length and head circumference
  are drawn over the World Health Organization's child growth standards, with
  the latest measurement placed in the band — so "are we in the normal range?"
  has an answer without a chart to hunt for. Sprout still never asks your baby's
  sex, so it reads both references and tells you the span; one measurement means
  little on its own, and the screen says so. You can also choose to look at just
  the girls' or just the boys' curve — that only changes what is on screen,
  nothing is remembered. Tap a measurement to read its percentile.
- 👆 **Tap a day to see what it was made of** — the feeds with their times and
  amounts, the sleeps with how much of a night fell on that day, and the changes
  with the colour of each stool. One tap opens the same day in all three cards.
- ↔️ **Slide back through time.** Drag any chart sideways, or use the arrows, to
  walk the whole window into the past — as far back as the day your baby was
  born, and no further.
- 🍽️ **Pick what the feeding chart draws** — number of feeds, time at the breast,
  millilitres of bottle or grams of solids. Four different units, so they take
  turns rather than crowd one chart.
- 🧷 **Wet and dirty, on the same bar.** Each day's changes are split into urine,
  both, and stool, in colours picked to stay distinguishable for colour-blind
  readers.
- 🥄 **Solids can now be weighed.** A purée or a bowl of porridge takes a
  quantity in grams, the way a bottle takes millilitres — and it is optional,
  because most of them are given nowhere near a scale.

## [1.6.3] — 2026-08-16

- 📄 Fixed **Sprout still not being offered for a `.sprout` file** sent through
  WhatsApp. The previous attempt matched the file's name, which sounds right
  and never works: a messaging app hands the file over under an internal
  address with no name in it at all, so there was nothing to match. Tapping an
  invitation now offers Sprout, wherever it was sent from.

## [1.6.2] — 2026-08-16

- 📶 Fixed **two phones of the same household never finding each other** over
  Bluetooth, even side by side with both apps open. Sprout was listening for
  the wrong part of what the other phone broadcasts, so the search could not
  have matched anything, ever — "nobody nearby" was the only possible answer.
  Two smaller things that hid it are fixed with it: a search the radio refuses
  to start now says so instead of reporting an empty room, and two phones that
  meet either side of a half-hour boundary recognise each other rather than
  missing by minutes. Pairing the phones in Android's Bluetooth settings was
  never needed and makes no difference.
- 📄 **Sprout files sent through WhatsApp, Gmail or Drive can now be opened.**
  An invitation keeps its meaning when Sprout hands it over, but a messaging
  app stores what it was given and passes it back as an anonymous file — so
  Sprout wasn't offered when you tapped it, which is exactly where invitations
  travel. Tapping a `.sprout` file now opens Sprout, and so does sharing one to
  it from another app.

## [1.6.1] — 2026-08-16

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
