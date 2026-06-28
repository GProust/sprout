# BDR-4. Default to the device's system language

Date: 2026-06-28
Type: Product / UX

## Status

Accepted

## Context

Sprout is used by tired new parents who want it to "just work" — not to be
interrogated about preferences on first run. A new parent's phone is already set to
the language they read in. At the same time, some people deliberately run their
phone in one language but prefer an app in another.

This is a product/UX decision about **what language the app speaks by default**,
separate from the technical question of *how* we store translations (that's
[ADR-0005](../adr/0005-localization-with-android-resources.md)).

## Decision

The app **follows the device's system language by default**, falling back to
**English** when we don't ship that language. There is **no language question
during onboarding** — the right language is simply used.

Users who want a different language can override it explicitly in **Settings**
(and, on Android 13+, via the system's per-app language picker). The override is
the exception; the default is "match the phone".

## Consequences

- Zero-setup correctness for the overwhelming majority: the app appears in the
  user's language with no action.
- An unsupported system language degrades gracefully to English rather than to a
  half-translated or empty UI.
- We take on the obligation to **keep translations complete** for every shipped
  language, so "match the phone" doesn't surprise a user with missing text — this
  is enforced technically by treating missing translations as a build failure
  ([ADR-0005](../adr/0005-localization-with-android-resources.md)).
- Adding a language is a content task (a new translation set), not a product
  redesign — the default behaviour already covers it.
