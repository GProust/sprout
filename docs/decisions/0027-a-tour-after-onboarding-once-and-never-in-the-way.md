# BDR-27. A tour after onboarding, once, and never in the way

Date: 2026-10-22
Type: Product / UX

## Status

Accepted

## Context

Onboarding asks four things — a welcome, the parent, the baby, how they care
for them — and then leaves the parent on the dashboard to find everything else
alone. Several things a new parent needs on the first day are not where they
would guess:

- the logs are tiles in a grid, not a tab each
  ([BDR-10](0010-the-bottom-bar-is-four-kinds-of-place.md));
- a breastfeed starts from the baby's own buttons, and its timer then waits at
  the top of the dashboard ([BDR-9](0009-the-dashboard-is-the-household.md));
- the bottom bar is kinds of place — Trends, You, and a baby's own tab only
  once there are two;
- sharing with the other parent's phone is behind the gear, in Settings.

The usual answer is a tour. It is easy to get wrong here in four ways.

- **It stands in front of the app.**
  [BDR-6](0006-check-in-waits-on-the-dashboard.md) says a parent who opens
  Sprout mid-feed lands on the dashboard, always, and
  [BDR-22](0022-whats-new-is-a-card-the-parent-puts-away.md) kept "what's new"
  off the launch for the same reason. The one launch that cannot be mid-feed is
  the one onboarding has just finished: the parent has spent a few minutes
  setting up, with both hands.
- **It shows up for the wrong people.** Everyone who already uses Sprout has
  found their way round. So has a phone restored from a backup, which skips
  onboarding because its profile came with it
  ([ADR-0011](../adr/0011-what-survives-a-new-phone.md)).
- **It overrules what the parent asked for.** The first launch can be a widget
  tap, or an invitation or a record file opened from a messaging app — a second
  phone joining a household
  ([ADR-0008](../adr/0008-pairing-by-invitation-and-the-first-merge.md)). The
  parent opened the app for that.
- **It costs a door.** A tour library on Android brings its own manifest into
  the merged build, which `AttackSurfaceTest` pins
  ([ADR-0014](../adr/0014-the-way-in-is-an-allow-list.md)). On iOS, TipKit can
  only put tips in a sequence with `TipGroup`, which needs iOS 18; the
  deployment target is 17.

## Decision

**Once, right after onboarding, a short tour of the dashboard.**

- Each step is a card pointing at one area, with the rest of the screen dimmed
  around a cut-out:
  1. the log tiles — feed, sleep, nappy and the rest;
  2. starting a breastfeed, and where the running timer shows;
  3. the bottom bar — Home, Trends, You, and a baby's own tab with two or more
     babies;
  4. Settings, and sharing with the other parent's phone.
- **A step with nothing to point at is left out.** With no baby yet there are
  no tiles and no feed buttons, and the dashboard's own card already asks for a
  baby: the tour is the last two steps. With two or more babies the tiles are on
  each baby's tab, which the bottom bar's step names: the tour has no tiles step.
- The help on each page (#109) is not part of this. A step pointing at it can be
  added, last, once it has landed: it would be the place the tour hands over.

**Skipping is always one tap away.**

- *Skip tour* is on every card, and it is the first thing a screen reader
  reaches. Back on Android, VoiceOver's escape gesture on iOS, and a tap
  anywhere outside the card all skip too.
- Finished or skipped, it never comes back on its own.
  **Settings → Replay the tour** brings it back, from the dashboard, on demand
  — on any phone, including one that never had it.

**It never stands in the way.**

- **It does not start if the launch was for something else**: a widget tap, or
  a Sprout file. The app goes where the tap or the file said, and the tour does
  not wait for a later launch either.
- **It gets out of the way the moment the parent does anything**: opening a
  tile, a tab or Settings, a widget tap or a file arriving, or a feed starting.
  The cut-out is live — tapping the thing a card describes does that thing, and
  the tour ends.
- **It is never offered on a later launch.** Any later launch could be the one
  in the middle of a feed. A tour cut short by the app being closed is not
  resumed.

**Who sees it is decided by one stored mark, and absent means never.**

- `tour` in the ordinary settings — `settings.xml` on Android, the
  `UserDefaultsStore` on iOS — holds `pending` or `done`.
  - Finishing onboarding writes `pending`, before the profile is saved. Nothing
    else ever writes it.
  - The first time the dashboard comes up after a launch, `pending` becomes
    `done`, whether the tour started or gave way to a widget tap or a file.
  - Nothing stored is every phone that was set up before this decision, and
    every phone restored from a backup. They never get the tour on their own.
- It is backed up like any other setting (ADR-0011): having been shown round is
  about a person, not a handset. It is not synced: the other parent has been
  shown nothing on this phone. `device.xml`, the Keychain and the backup rules
  do not move.
- **The rules are pure functions on each side** (`data/Tour.kt`,
  `SproutData/Tour.swift`) with the same cases tested on both: a phone that has
  just finished onboarding gets the tour; an existing user, a restored phone, or
  one that has had it does not; a replay does; a launch from a widget or a file
  does not; leaving the dashboard or starting a feed ends it.

**It is drawn by hand, the same on both apps.**

- A dimmed scrim with a cut-out around the area, a ring round the cut-out, and
  the card on whichever side has more room. No library on either side.
- It is drawn over the whole shell — `MainScaffold` on Android, the tab view in
  `RootView` on iOS — and never inside the dashboard's own screen. The areas
  report where they are only while the shell is showing the dashboard.
- Android rings each area exactly. iOS rings the two bars whole, because they
  are UIKit's and have no view of their own to measure: the dashboard reports
  where its safe area ends at each edge, and the bar is what lies beyond.
- **The cut-out is visual only.** Each card says in words where its area is
  ("At the bottom of the screen…", "At the top right…"). While a step is up,
  what the scrim dims is hidden from TalkBack and VoiceOver, and the card is
  announced as it appears and as each step replaces the last.
- **Reduce Motion is respected.** The only movement is a short fade as the tour
  appears, and it is dropped when the system asks for less motion.
- The words are `tour_step_<n>_title` and `_body` in Android's strings, in all
  seven languages, generated into iOS's like every other string.

## Consequences

- A parent who has just set Sprout up is shown, in under a minute, the four
  places they will use most, on the screen where they will use them. A parent
  who would rather find out alone loses one tap.
- Nobody else sees anything. Existing users are not told about the tour by a
  "what's new" announcement either: a tour of a dashboard they already use is
  not news. The Settings row is there for anyone who looks.
- A tour cut short — the phone rang, the app was closed — is not offered again.
  That is accepted for the reason BDR-6 gives: the next launch could be at 3 a.m.
  with a baby in one arm. Settings has it.
- The screenshot runs show the dashboard of every other day. Android's captures
  render screens without the shell, so they never meet the tour, except one
  composed on purpose: the first step over the one-baby dashboard. iOS's seeded
  run marks it done, and the first-run capture files the tour's first step and
  then skips it by its identifier, as a parent would.
- Settings gains one row, *Replay the tour*, above *Support Sprout*.

## Alternatives considered

- **Slides at the end of onboarding.** The conventional first-run carousel. It
  describes an app the parent has not seen yet, rather than pointing at the
  screen in front of them, and it is a fifth onboarding step to get past.
- **Tips that appear the first time each screen is opened.** They spread the
  interruptions over the first days instead of the first minute — the days when
  a parent opening the app is most likely to be in the middle of something.
- **A `tour_done` flag that starts false.** Simpler to read, and it would show
  the tour to every existing user on the update that ships it, and to every
  restored phone.
- **Offering an interrupted tour again on the next launch.** It is the one
  launch the parent did not choose to spend on a tour.
- **A library, or TipKit.** Each costs more than the tour is worth: a manifest
  `AttackSurfaceTest` would have to admit on Android, iOS 18 for sequencing on
  iOS, and two apps that would no longer show the same steps the same way.
