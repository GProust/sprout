# BDR-11. Donations are a link out, and they buy nothing

Date: 2026-09-05
Type: Business / Product

## Status

Accepted

## Context

Sprout is free, GPLv3, has no accounts, no server and no ads, and costs its
author a Play Developer account, a keystore and his evenings. Asking the people
it helps to chip in is reasonable. Doing it in a newborn tracker is where it
gets delicate: the app is opened one-handed at 3 a.m. by someone who has not
slept, and [BDR-6](0006-check-in-waits-on-the-dashboard.md) already settled that
even a question about the parent's *own health* waits on the dashboard rather
than interrupting them. An ask for money has less standing than that, not more.

Three things also constrain the shape rather than just the tone:

- **No `INTERNET` permission** ([ADR-2](../adr/0002-local-first-on-device-storage.md),
  and the promise in [`PRIVACY.md`](../../PRIVACY.md)). Its absence is the one
  privacy claim a user can verify for themselves instead of taking on trust.
  Anything that fetched a payment page, a supporter count or a balance inside
  the app would need it, and would spend that claim on a donate screen.
- **Google Play requires Play Billing for in-app purchases.** Donations are not
  purchases — but only while nothing is received in return. A donation that
  unlocks a feature, lifts a limit or grants a badge is an in-app purchase by
  Play's definition, whatever it is called in the UI.
- **The GPLv3 and the reserved name** ([BDR-2](0002-gplv3-copyleft-reserved-trademark.md)).
  Anyone may fork Sprout, and a fork is entitled to strip these links out or
  point them at its own author. Nothing here may depend on them being present.

Patreon was considered and rejected. It is a membership model, built around
delivering ongoing exclusive rewards to paying members — and there is nothing
to put behind that door that would not violate the second constraint above.
Tiers with no rewards convert poorly and quietly set up an expectation of
exclusivity that the licence forbids.

## Decision

**Sprout asks once, in one place, and gives nothing back for it.**

- **Two destinations.** GitHub Sponsors, and Buy Me a Coffee for the majority of
  users who will never sign into GitHub. Both accept a one-off amount; neither
  requires a subscription.
- **A link, never a payment.** Both rows fire an `ACTION_VIEW` intent and let
  whatever browser the phone already has load the page. Sprout opens no socket,
  and the manifest still declares no `INTERNET` permission. `SupportLinksTest`
  asserts that, so the promise fails CI rather than failing quietly.
- **Nothing is ever unlocked.** No feature is gated, no limit lifted, no badge,
  no ad removed — there are no ads. Every part of Sprout is present for someone
  who never gives a penny, and the wording on the screen says so first.
- **One quiet row at the bottom of Settings.** Not the dashboard, not a dialog,
  not a bottom-bar seat ([BDR-10](0010-the-bottom-bar-is-four-kinds-of-place.md)),
  not a prompt after the tenth feed, not a notification. It is found by someone
  who goes looking, and is invisible to everyone else.
- **A globe, not a chevron.** The row is marked as leaving the app before it is
  tapped, because every other row in Settings stays inside it.
- **The same two links on GitHub**, via `.github/FUNDING.yml`.

## Consequences

- The ask reaches only the users who open Settings, which is a small fraction of
  them. That is the intended trade: the alternative reaches more people by
  interrupting a parent who is holding a baby.
- Revenue is expected to be small. Anything that would meaningfully raise it —
  a prompt, a nag, a reward, a tier — is the thing this record forbids.
- **Tapping a link leaves Sprout's privacy guarantees behind**, since the
  browser then talks to GitHub or Buy Me a Coffee under their policies, with an
  IP address attached. That is a real change to what the app can promise, so
  `PRIVACY.md` says it plainly rather than leaving it implied.
- A fork may remove or repoint the links freely; nothing depends on them.
- If a donation is ever to buy anything at all, this record must be superseded
  and the feature moved onto Play Billing. Editing the wording is not enough.

## Relationship to other decisions

- The tone it follows is [BDR-6](0006-check-in-waits-on-the-dashboard.md) —
  Sprout does not interrupt a parent for its own purposes.
- The permission it must not spend is in
  [ADR-2](../adr/0002-local-first-on-device-storage.md) and
  [ADR-3](../adr/0003-no-first-party-backend-user-owned-sync.md).
- The licence that rules out exclusive rewards is
  [BDR-2](0002-gplv3-copyleft-reserved-trademark.md).
- The placement rule it obeys is
  [BDR-10](0010-the-bottom-bar-is-four-kinds-of-place.md).
