# BDR-5. Growth spurt periods: gentle guidance, opt-in alerts

Date: 2026-08-03
Type: Product / Domain rule

## Status

Accepted

## Context

Growth spurts are one of the most disorienting parts of the first months: a baby
who was settling suddenly feeds constantly, sleeps worse and fusses more, and
parents wonder what they're doing wrong. The pattern is well known — spurts
cluster around certain ages — and simply *knowing it's a normal phase* is
reassuring.

Sprout already knows each baby's birth date, so it can surface this without any
new data entry. The risk is over-promising: spurt timings are tendencies, not a
schedule, and every baby is different. Guidance that reads like a prediction
("your baby WILL have a growth spurt on Tuesday") would be wrong often enough to
erode trust — and could worry parents whose baby doesn't follow the script.

## Decision

Show the **typical growth spurt periods** and offer an **opt-in heads-up**:

- The windows used are the commonly cited ones over the first year: around
  1 week, 3 weeks, 6 weeks, 3 months, 6 months and 9 months, each spanning a
  few days.
- The **home dashboard** shows a gentle note while the baby's age is inside a
  window, and a "one may be coming" note in the few days before one. It
  disappears on its own; there is nothing to configure, log or dismiss.
- A **notification** when a window opens is **opt-in** (Settings, off by
  default), consistent with feeding reminders — Sprout never nags by default.
  One alert per window per baby, at a civil hour (09:00), never at night.
- **Wording is deliberately hedged everywhere** — "babies often", "may be
  starting", "usually passes in a few days" — and frames extra hunger and
  fussiness as normal. No dates, no countdowns, no claims about *this* baby.
- This is **information, not tracking**: nothing is stored, so there is no new
  table, no migration, and stopping is just toggling the setting off.

## Technical realization

- Windows and lookups are pure functions in `ui/common/GrowthSpurts.kt`,
  unit-tested; ages are calendar days in local time.
- Alerts follow the feeding-reminder pattern: one inexact `AlarmManager` alarm
  per baby (`notifications/GrowthSpurtReminders.kt`), re-armed on launch, on
  reboot, when the setting or a birth date changes, and after each firing; the
  receiver re-checks state before posting so stale alarms never nag. Its own
  notification channel and id namespace (base 3 000 000).
