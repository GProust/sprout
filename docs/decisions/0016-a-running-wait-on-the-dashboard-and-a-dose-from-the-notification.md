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

A card **below the feed buttons** on each baby, listing the medicines that have
something to say right now, **one line each and no more**.

A line is the medicine's name in its state's colour, the state's own icon, the
number still to wait if there is one, and the two things there are to do about
it: *Give* and *Dismiss*.

The first version of this card said everything the as-needed screen says — name,
dose, interval, the full state sentence, the last dose and its count, four lines
per medicine — above the feed buttons. The first thing users said back was that
the dashboard had got heavy, and they were right. This is a glance at something
that is usually not happening, on the screen that is opened every hour: feeding
is what the dashboard is *for*, and a medicine is what it is for a few days a
year. So the card went to one line per medicine and moved below the feeds.

**The abbreviation is visual only.** A screen reader is given the medicine's name
and the full state sentence, unshortened, because the reason the line is short is
that it is being *looked* at, and none of that reasoning applies to someone
listening. The words themselves still come from the shared helpers
(`ui/medicines/MedicineLabels.kt`, `Screens/Medicines/MedicineLabels.swift`), so
the card and the screen cannot disagree about the same medicine at the same
instant.

**The number keeps the word that says what it is.** "4 h 12 m" alone does not say
whether it is a wait or a head start, and the two mean opposite things, so the
line reads "4 h 12 m to wait" or "1 h early". Green prints no number at all,
because there is nothing left to count — there the colour and the tick beside the
name are the whole message, which is why BDR-15's rule that the colour is never
the only channel is kept by the *icon*, not by a sentence the line has no room
for.

*Give* is never disabled, for the reason BDR-15 gives.

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

**The order is how freely it may be given**: the full wait passed, then allowed
but sooner than ideal, then not yet. Green above amber is the part worth writing
down, because both of those may be given and the first arrangement of this card
simply grouped them together. They are not the same thing to a parent: one is the
dose they were told to give, the other is the dose they are *allowed* to give
early. So the medicine that needs no second thought is the one at the top.

It is an ordering, not a recommendation. The amber line says exactly what it said
before, and its *Give* works exactly as well — BDR-15's rule is that nothing here
argues, and putting a row second is not an argument.

**Dismiss puts a line away until the medicine is next given.** It is not a
setting and not a snooze: a dismissal names *the dose it was made against*, so it
expires by itself — give another dose and the medicine's last dose is no longer
the one that was put away, and the line comes back. Nothing has to clear it,
which means nothing can forget to. It is device-local and **never synced**: "I
have seen this" is a fact about a person looking at a screen, and the parent
holding the other phone has not seen anything.

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

- One line per medicine on the dashboard, below the feeds, in the arrangement
  most likely to be in play at 3 a.m. and absent the rest of the time.
- **Everything the line drops is a tap away**, on the screen that was always
  going to be the place for it. What the card is for is noticing; what the
  as-needed screen is for is reading.
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
- **A dismissal survives a restore to a new phone**, because it lives with the
  ordinary settings rather than in `device.xml` (ADR-0011). That is harmless and
  self-correcting: the worst case is a line missing until the next dose, which is
  exactly what was asked for.
- The card and the as-needed screen say the same things from one set of helpers.
  Adding a fourth state, or changing a sentence, is one edit — which is the point
  of extracting them, because the screen that would have drifted is the one
  nobody was looking at.
