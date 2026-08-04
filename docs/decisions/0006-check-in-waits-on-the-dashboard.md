# BDR-6. The daily check-in waits on the dashboard, and can be stopped

Date: 2026-08-04
Type: Product / UX

## Status

Accepted

## Context

The parent wellbeing check-in ([BDR-1](0001-inclusive-parent-model.md)) opened
as a full-screen flow at launch whenever a day had gone by without one. It was
skippable, but it still stood between the parent and the app.

A parent breastfeeding in the middle of the night reported exactly the wrong
outcome: phone in one hand, baby in the other, and the first thing to do is
dismiss questions about their own healing and mood — then hunt for the feeding
screen. The check-in is not urgent; it is *a look at how you're doing*, and
that can wait an hour. Launch is also the one moment where the parent is most
likely to be in the middle of something.

The same feedback raised a second point: some parents don't want this at all —
never did, or not anymore once recovery stops being a daily concern. Sprout
already let each *body question* be opted out of individually, but the check-in
itself had no off switch.

## Decision

**Nothing about the parent's wellbeing interrupts the app.**

- The check-in is offered as a **card on the dashboard**, below the greeting.
  Tapping it opens the same guided flow; "Not today" puts the card away until
  tomorrow without saving anything. Launch goes straight to the dashboard.
- Wellbeing tracking has a **master off switch** (Settings → Daily check-in).
  Off means no card and no dashboard shortcut — the check-in is simply not part
  of the app anymore. The per-question toggles stay, nested under it.
- **Stopping never deletes.** Past check-ins are kept, stay reachable from the
  heart in the top bar, and reappear intact if tracking is turned back on.
- The check-in is **never a notification**. Consistent with feeding reminders
  and growth-spurt alerts, Sprout doesn't nag — and unlike those two, this one
  isn't even opt-in-able, because a notification about your own mood at a bad
  moment is precisely what this decision is about.

## Consequences

- A parent who opens Sprout mid-feed lands on the dashboard, always.
- The check-in is more discoverable *and* easier to ignore: it is visible where
  the parent already looks, rather than shown once and gone.
- Being visible rather than modal means it can be missed on a busy day. That is
  accepted — a missed check-in costs nothing, and daily completeness was never
  the goal.
- Startup has one fewer stage (`Startup` is now just onboarding or the main
  app), and the check-in became an ordinary navigation destination with its own
  ViewModel.
- One new profile column, `trackWellbeing` (default on, so nothing changes for
  existing users).
