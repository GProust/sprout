# 19. A reminder decided when it is scheduled

Date: 2026-09-10

## Status

Accepted.

Applies the reminders Android already ships to the second app
([ADR-0015](0015-native-ios-in-this-repository.md)). It changes nothing about
what Sprout reminds a parent of, or when — only about *where the decision is
made*, because the two platforms do not offer the same place.

## Context

Sprout has three reminders, all opt-in and all off until asked for: a nudge when
it has been too long since a feed, a treatment's doses, and a heads-up when a
baby enters a typical growth-spurt window.

**On Android each of them is a decision taken at fire time.** `AlarmManager`
wakes a `BroadcastReceiver`, and the receiver reads the database *then*: it
checks whether the baby has been fed since the alarm was armed, picks the
sentence, and often decides not to notify at all. The alarm is a wake-up call to
our own code, not a message.

**iOS has no such thing.** `UNUserNotificationCenter` takes a notification —
title, body, and a moment — and delivers it. Nothing of ours runs when it fires.
The nearest equivalents are all wrong for this: `BGTaskScheduler` is
best-effort, opportunistic, and explicitly not a scheduler; a background mode
would be a claim about this app that
[ADR-0014](0014-the-way-in-is-an-allow-list.md) does not want to make and Apple
would not accept; and a push notification needs a server, which
[ADR-0003](0003-no-first-party-backend-user-owned-sync.md) says there will never
be.

So the decision moves forward in time, from when the reminder fires to when it
is scheduled. That is a real behavioural difference and it deserves writing
down rather than discovering.

## Decision

**Local notifications only, with their content decided at scheduling time, and
the schedule rebuilt whenever the answer could have changed.**

- **Nothing is asked for until a reminder is turned on.** Authorization is
  requested at the moment a parent enables one, never at launch. A phone whose
  owner wants no reminders is never asked about notifications at all.
- **The schedule is rebuilt from scratch** — cancel everything of ours, then
  re-add — at two moments: when the app **goes to the background**, and when it
  **returns to the foreground**. It is a few dozen entries; correctness is worth
  more than the arithmetic saved.

  Those two moments rather than a hook on every write, because they bracket
  every write there is. A parent logs a feed and puts the phone down: the
  backgrounding rebuild re-arms from the feed they just logged, which is the
  whole point of the feeding reminder and the failure it exists to avoid. A
  household exchange or a passing midnight changes what is due: the foreground
  rebuild catches it, and tops up the bounded schedules below. Two hooks in one
  place beat a call threaded through every screen that writes, which is the
  version that eventually misses one.
- **An overdue reminder is not delivered retroactively.** If the moment has
  already passed by the time we are scheduling, it is dropped. On Android the
  alarm would have fired while the app was closed; here the only occasion to
  notice is the app being opened, and a notification that arrives *because the
  parent opened Sprout* tells them something they are already looking at.
- **Repeating reminders are bounded.** A daily treatment is one repeating
  calendar trigger and needs no refreshing. Anything on a longer cycle — every
  second day, every third — cannot be expressed as one, so a fixed number of
  occurrences are scheduled ahead and topped up on each foreground. iOS keeps at
  most 64 pending notifications per app and silently drops the rest, so the
  horizon is chosen to stay well inside that with several babies and several
  treatments.
- **The arithmetic is shared, the delivery is not.** *When* a reminder is due is
  the same question on both phones, so it lives in `SproutData` as pure
  functions with tests that need no device. Handing that moment to the operating
  system is the per-platform half.

## Consequences

- **A reminder can be stale in one narrow case.** The content is written in
  advance, so a feed logged on *another* phone in the household could in
  principle leave ours saying something out of date. In practice it cannot get
  far: a merge only happens with the app open (ADR-0010), and opening the app
  rebuilds the schedule. The window is a household exchange followed by no
  foreground on this phone before the reminder fires.
- **The wording is slightly less specific than Android's.** "It's been 3 hours
  since the last feed" is computed when scheduled, so it states the interval the
  reminder was armed for rather than the elapsed time at the instant it arrives.
  They are the same number unless the first case above applies.
- **A parent who never turns a reminder on is never prompted for permission**,
  which is the behaviour a privacy-first app should have and the one the App
  Store review guidelines prefer.
- **No new entitlement, no background mode, no push.** The iOS surface stays
  what ADR-0014's sibling will pin: local notifications are not a way *in*.
- **The reminder switches in Settings stop being a note.** They were shown as an
  untranslated "Not on iPhone yet." line precisely because a switch that stores a
  preference and schedules nothing is a promise.

## Alternatives considered

- **`BGTaskScheduler` to re-decide near fire time**, reproducing Android's
  receiver. It is opportunistic by design: iOS runs it when it feels like it,
  which for a 3 a.m. feeding reminder is "possibly never". It would make the
  behaviour less predictable, not more, and it would add a background mode to the
  app's declared surface for nothing.
- **A repeating notification every N minutes** instead of one armed at the right
  moment. Simple, and wrong: it nags a parent whose baby has just fed, which is
  the exact failure the "re-arm on every feed" design exists to avoid.
- **Notification Service / Content extensions** to rewrite the body as it
  arrives. They only apply to *push* notifications, which this app does not and
  will not have.
- **Ask for notification permission at first launch.** Higher opt-in rate, and a
  worse app: it asks a question about a feature the parent has not met yet, on
  the screen where they are trying to enter their baby's name.
