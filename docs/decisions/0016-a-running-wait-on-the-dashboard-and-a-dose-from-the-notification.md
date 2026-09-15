# BDR-16. A running wait on the dashboard, and a dose from the notification

Date: 2026-09-15
Type: Product / UX

## Status

Accepted

## Context

[BDR-15](0015-medicine-given-when-needed-and-the-wait-between-doses.md) built the
as-needed medicine screen: a medicine the parent set up themselves, the doses
they logged, and a traffic light restating their own two numbers. It answers
*can I give her another one yet?* — correctly, and in words.

It answers it on a screen nobody is looking at.

The dashboard is the app's front door and the thing a parent actually opens
([BDR-9](0009-the-dashboard-is-the-household.md)). As-needed medicine is a tile
in the log grid there, which is the right place for *going to log something*
and the wrong place for *a wait that is running right now*. A parent who gave
paracetamol at 03:10 and is holding the same hot baby at 06:40 has to remember
that the screen exists, find the tile, and read the card — three steps between
them and a number they are already anxious about. The likely outcomes are both
bad: guessing, or giving a second dose without checking.

The reminder has the same shape of problem from the other end. It fires when the
wait is over and says so, which is exactly right; then it asks the parent to
unlock the phone, open Sprout, find the screen and press *Give a dose* — at
3 a.m., one-handed, with the dose already given. That is the moment a dose stops
being logged at all, and **a dose given and not logged is the failure the whole
feature exists to prevent** (BDR-15). Worse, a dose logged at nine that was
given at three puts the *next* wait six hours late, so one missed log corrupts
every answer after it.

Both gaps are the same gap: the information is a screen away from the moment.

## Decision

**A running wait appears on the dashboard, and a dose can be logged from the
notification itself.**

### The dashboard card

A card above the feed buttons on each baby, listing the medicines that have
something to say right now. It is the same sentence, the same icon and the same
colour as the as-needed screen — drawn from the same helpers
(`ui/medicines/MedicineLabels.kt`, `Screens/Medicines/MedicineLabels.swift`), so
the two cannot disagree about the same medicine at the same instant. Each line
carries a *Give a dose* button, which is never disabled, for the reason BDR-15
gives.

**A medicine earns a line when a wait is running, or when it was given within
the last day and the wait has since passed.** That second case is the one the
parent is waiting for and the reason the card is worth having. A medicine that
has never been given, or whose last dose is older than the window, is left off:
it is *set up* rather than *in play*.

**The card is absent, not empty, when nothing is in play.** A household that is
not in the middle of anything has exactly the dashboard it had before, which is
what stops a feature used a few days a year from taking up permanent space on
the screen used every hour. Three medicines is the most it draws; beyond that it
says how many it is not showing rather than growing to fit.

**A dose given from the card is recorded against the medicine's own baby**, not
against whichever baby is selected. With twins the card that was touched and the
active selection are not the same thing, and resolving it from the selection is
how a sibling's paracetamol lands in the wrong history — so `giveMedicineDose`
takes the medicine and reads the baby off it.

**The card ticks.** A wait that runs out while the app is open turns green with
nothing written and nobody touching anything — which means the summary itself is
recomputed once a minute, not merely reformatted.

### The notification's two buttons

*Give a dose* and *Dismiss*, on the reminder BDR-15 already sends.

*Give a dose* logs the dose **at the moment the button was tapped** and re-arms
the next reminder from it. It does not require unlocking the phone, does not
open the app, and does not ask for confirmation — each of those is a place where
the dose stops being logged.

*Dismiss* takes the notification away and changes nothing. There is no snooze:
the wait really is over, and an app that offers to remind you again about
something that is no longer waiting is inventing a state the data does not have.

On Android both go back to the existing `MedicineReminderReceiver`, which is
already declared and not exported — so this opens no new door and costs no line
in `AttackSurfaceTest`'s pinned list
([ADR-0014](../adr/0014-the-way-in-is-an-allow-list.md)). On iOS they are a
`UNNotificationCategory` with two actions and a delegate that answers them.

**That delegate does not reopen [ADR-0019](../adr/0019-a-reminder-decided-when-it-is-scheduled.md).**
What that record settles is that iOS cannot decide *what a reminder says* when
it fires, so the sentence is written in advance. A notification *response* is
not the reminder firing — it is the parent answering it, and iOS delivers that
to the app on both platforms alike. Nothing here makes a decision at fire time;
it records one the parent made.

## Consequences

- One more thing on the dashboard, in the arrangement most likely to be in play
  at 3 a.m. and absent the rest of the time.
- `MedicineWatch` and `medicinesNeedingAttention` are shared arithmetic, tested
  with the same cases on both platforms — a household with one phone of each has
  to see the same medicines on the same card.
- The dashboard's summary now recomputes on a timer as well as on a write. It is
  a fold over a week of rows, once a minute, and only while the screen is
  subscribed.
- **A mis-tap on the notification logs a dose that was not given.** It is
  correctable — every dose is editable and deletable on the as-needed screen —
  and the trade is deliberate: a confirmation step would cost the button most of
  what it is for. BDR-15's rule is that Sprout records what happened and does not
  argue; the same rule applies when what happened was a fumble.
- iOS gains a `UIApplicationDelegate`, for one reason: `UNUserNotificationCenter`'s
  delegate must be set before launching finishes, or an action tapped while the
  app was not running is delivered to nobody. A dose tapped from the lock screen
  is held until the database is open rather than dropped.
- The card and the as-needed screen say the same things from one set of helpers.
  Adding a fourth state, or changing a sentence, is one edit — which is the point
  of extracting them, because the screen that would have drifted is the one
  nobody was looking at.
