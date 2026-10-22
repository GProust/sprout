# BDR-24. A running feed is shown outside the app, and can be paused, switched and stopped from there

Date: 2026-10-22
Type: Product / UX

## Status

Accepted

## Context

A breastfeed is timed with the phone in one hand and a baby in the other arm.
Pausing for a burp, switching sides or saving at the end has meant unlocking the
phone, opening Sprout and finding the timer — at 3 a.m., with the screen at full
brightness — or not doing it, and saving a feed that carries a burp as time at
the breast ([BDR-17](0017-a-breastfeed-can-stop-and-carry-on.md)).

The only place outside the app that showed a running feed was the Android
home-screen widget, which can only display. iOS had nothing at all.

A stopwatch app is the model parents already know: the clock in the
notifications, ticking, with its buttons beside it.

## Decision

**A running breastfeed can be shown outside the app, with the timer's own
buttons.**

- On Android it is an **ongoing notification**; on iOS a **Live Activity** on the
  lock screen and in the Dynamic Island, because a notification there cannot
  tick ([ADR-0020](../adr/0020-the-live-activity-is-the-one-ios-extension.md)).
  Same states, same buttons, same saved feed.
- **The buttons are the timer's.** While nursing: *Pause*, *Switch side*,
  *Stop & save*. On a break: *Resume left*, *Resume right*, *Stop & save* —
  coming back names the breast, as on the timer. A tap there is the same act on
  the same feed as the same tap on the timer screen, and *Stop & save* saves the
  same feed, ending when its last stretch ended.
- **A tap that no longer fits does nothing.** A second *Pause* does not restart
  the break; a second *Stop & save* finds nothing left to save; a *Switch* still
  on screen after the feed went on a break does not resume it.
- **One per running feed** ([BDR-23](0023-one-running-breastfeed-per-baby.md)),
  each with its own buttons.
- **The clock is time at the breast.** It ticks while nursing and shows a still
  figure on a break — "On a break · 12 min at the breast" — because a break is
  not time at the breast and the system cannot be asked to stop a clock it draws.
- **Nothing about a break is judged here either.** No "are you still feeding?",
  no reminder after twenty minutes, no automatic stop. That is still a decision
  of its own, as BDR-17 left it.

**It is the parent's choice, made in Settings.**

- **Off until turned on**, like every other thing Sprout shows outside the app.
  Turning it on in Settings is where Android asks for the notification
  permission. The moment a feed starts is never the moment to ask — the reasoning
  of [BDR-6](0006-check-in-waits-on-the-dashboard.md).
- One switch per phone, kept with the other settings and backed up like them
  ([ADR-0011](../adr/0011-what-survives-a-new-phone.md)). Not synced: the other
  parent decides for their own phone.

**The lock screen shows what a feed is, never whose it is.**

- On a locked phone it reads *Feeding · Left* and the clock. With two feeds it
  reads *Feed 1* and *Feed 2*, so the two can still be told apart.
- Android shows the baby's name only in the notification's private version,
  which a lock screen shows only if the parent's phone is set to show all
  notification content. The Live Activity never shows it.

**It stays on this phone.** The running feed is device-local
([BDR-17](0017-a-breastfeed-can-stop-and-carry-on.md)): the other parent's phone
is told nothing until a feed is saved, the Android notification is not mirrored
to a watch, and the Live Activity is updated by the app alone — there is no
push, and no server to push from.

## Consequences

- **Nothing runs to keep it up to date.** Both platforms draw the clock from a
  start time, so there is no foreground service on Android — none was ever
  accepted ([ADR-0010](../adr/0010-automatic-exchange-over-bluetooth-when-the-app-is-open.md)),
  and the permission it needs would fail `AttackSurfaceTest` — and nothing
  ticking on iOS. It is redrawn when the feed changes, and when the app or the
  phone comes up with a feed still running.
- **Android adds no door.** The buttons go to the existing, non-exported
  `FeedingReminderReceiver`, as the medicine buttons went to theirs
  ([BDR-16](0016-a-running-wait-on-the-dashboard-and-a-dose-from-the-notification.md)):
  no new component, no new permission, no line in `AttackSurfaceTest`.
- **iOS adds its first extension**, and the checks that hold the privacy claim
  grow to cover it (ADR-0020).
- **Swiping the notification away ends nothing.** Android 14 lets a parent
  dismiss an ongoing notification that is not a service's; the feed carries on
  and its next change posts it again. iOS ends a Live Activity on its own after
  some hours; the feed carries on there too.
- **Tapping the notification opens the dashboard**, where every running feed has
  a row with its baby's name.
- `PRIVACY.md` says what the lock screen shows.
- A feed started by voice (#91) could confirm itself through this very
  notification; that is for its own decision.

## Alternatives considered

- **On by default once the permission is granted.** Fewer taps, but an ongoing
  notification that appears for the first time mid-feed after an update is a
  surprise at the worst moment, and some parents will not want a feed on their
  lock screen at all. A switch they turn on is the same feature, wanted.
- **A foreground service on Android.** It would keep a process alive for a clock
  the system can already draw from a timestamp, and it costs a permission
  ADR-0010 refused.
- **The baby's name on the lock screen.** With twins it is the most useful thing
  to show, and it is also the one thing on the screen that says something about
  the family to whoever picks the phone up. *Feed 1* and *Feed 2* tell the two
  apart without it.
- **A local notification on iOS, re-delivered every minute.** It would buzz or
  stack up, show a clock that is wrong between deliveries, and still need the
  app awake to deliver it.
