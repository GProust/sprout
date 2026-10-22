# BDR-23. A breastfeed belongs to the baby it was started for, and each baby can have one running

Date: 2026-10-22
Type: Product / Domain

## Status

Accepted

## Context

The live timer has always held exactly one session on the phone. That was a
reasonable shape for one baby, and it is the shape
[BDR-17](0017-a-breastfeed-can-stop-and-carry-on.md) describes when it talks
about "the running session". With twins it fails in two ways.

**Tandem feeding cannot be timed.** One baby at each breast is the everyday case
for parents of twins. The timer refused a second session on purpose — a guard so
that two ways into the timer opening at once could not time one feed twice — so
the second baby's feed went unrecorded or was typed in afterwards from memory.

**A feed could be filed under the wrong baby.** The single session carried no
baby, so the feed was saved to whoever was *selected* when *Stop & save* was
tapped. Starting a feed from Léa's card and then tapping Noah's card a minute
later opened Léa's running timer, and saving it filed Léa's feed under Noah,
without a word. That is the failure
[BDR-9](0009-the-dashboard-is-the-household.md) describes ("with twins, the
baby a feed belongs to is a **mode** — set on one screen, read minutes later by
the insert"), and the one place it had not been fixed was the timer.

The second has been fixed already (#116): a session now records the baby it was
started for, and is saved to that baby. This record decides the first, and
writes down the rule the fix applied.

## Decision

**A breastfeed belongs to the baby it was started for.**

- A session names its baby when it starts, by uid — never by the per-device id,
  for the same reason a dose names its medicine by uid
  ([BDR-15](0015-medicine-given-when-needed-and-the-wait-between-doses.md)).
- *Stop & save* writes the feed to that baby, whoever is selected by then.
- A session left running by a version that did not record its baby is read once,
  when the update is first opened, as belonging to the baby selected at that
  moment — where it would have been saved anyway. A feed running through the
  update is neither lost nor moved.

**Each baby can have one running at a time, and any number of babies can.**

- A baby has one mouth. A second start for a baby whose feed is running hands
  back the running one, which is the old race guard kept per baby. A start for
  another baby is a second timer.
- **Every rule of BDR-17 holds per feed.** Pausing one does not pause the other;
  pausing twice does not restart a break; each saved feed ends when *its* last
  stretch ended; every clock counts time at the breast. "The running session"
  in BDR-17 now reads "each running session"; nothing else there changes.
- **The second feed is offered the free breast.** With another baby already at
  one breast, the start buttons lead with the other. It is a default, not a
  rule: nothing prevents two feeds on one side, because the parent model does
  not assume a single breastfeeding parent
  ([BDR-1](0001-inclusive-parent-model.md)). Each feed switches on its own.

**Every running feed is shown with its baby's name.**

- **The dashboard** has one live row per running feed, each naming its baby. A
  baby whose feed is running shows that feed on its own card — *Feeding · Left*
  and the clock — and the tap opens it, instead of offering to start another.
- **The timer is a baby's timer.** With two running, each is reached from its own
  row or card, it shows whose it is, and *Stop & save* on one leaves the other
  running. The feeding screen's bar is the selected baby's, because the history
  above it is.
- **The home-screen widget** shows one feed as before, two as two small clocks
  under their babies' names, and more than two as a count.

## Consequences

- **Nothing saved changes.** Each feed is an ordinary breastfeed row for its own
  baby: no wire-format change and no Room or iOS migration. Joining two feeds
  ([BDR-19](0019-two-breastfeeds-saved-apart-can-be-joined.md)) is still between
  feeds of one baby, and the statistics were per baby already.
- **The running feeds stay device-local and backed up**, as the one session was
  ([ADR-0011](../adr/0011-what-survives-a-new-phone.md)): a feed in progress
  travels to a new phone, and the other parent's phone is told nothing until a
  feed is saved ([ADR-0007](../adr/0007-partner-sync-by-direct-device-to-device-exchange.md)).
  Only the shape of what is stored changes — a list where there was one value —
  and both apps still read the old shape once.
- **The rules are pure functions** (`NursingSessions.kt`,
  `SproutData/NursingSessions.swift`) with the same cases tested on both apps:
  start, a second start for the same baby, a start for a twin, pause, switch,
  stop, the free breast, and the session from before the update.
- **A baby removed while its feed runs** leaves a feed with no card to show it
  under. Saved, it goes to the selected baby, which is what keeping the feed
  costs.
- **Anything that shows a running feed outside the app** — a notification, a
  Live Activity — shows one per feed, each with its own controls.

## Alternatives considered

- **One session, with a baby picker at save time.** It fixes the misattribution
  and leaves tandem feeding untimeable, and it asks a question at the one moment
  a parent has no hand free to answer it.
- **One session timing two babies.** A feed is one baby's record: its stretches,
  its sides and its breaks are that baby's, and two babies feeding together
  rarely start, switch and stop at the same instant. Two timers is what happens.
- **Moving the selection with the timer.** Making the running feed's baby the
  selected one is the mode BDR-9 set out to remove; it would also be wrong the
  moment a second feed started.
