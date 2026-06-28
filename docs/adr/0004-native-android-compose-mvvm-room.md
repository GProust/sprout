# 4. Native Android with Compose, MVVM and Room

Date: 2026-06-28

## Status

Accepted

## Context

Sprout is, for now, a single-platform (Android) app built by a very small team. We
want a modern, idiomatic, maintainable stack that works well offline and pairs
naturally with local persistence ([ADR-0002](0002-local-first-on-device-storage.md)).

## Decision

Build natively for Android in **Kotlin**, with:

- **Jetpack Compose + Material 3** for UI.
- **MVVM** — `ViewModel`s exposing state via Kotlin `StateFlow`; screens are
  stateless composables that collect that state.
- **Room** over SQLite for persistence, with a repository layer
  (`SproutRepository`) as the single point of data access for all ViewModels.
- **Navigation Compose** for in-app navigation.
- **Gradle Kotlin DSL + a version catalog** (`libs.versions.toml`) for the build.
- **minSdk 26**, target/compile **SDK 35**.

## Consequences

- A modern, declarative UI and a clean unidirectional data flow that is easy to
  test (pure functions and ViewModel state).
- The repository indirection means cross-cutting data rules live in one place —
  e.g. scoping every log to the active baby
  ([ADR-0008](0008-multiple-babies-active-baby-model.md)).
- **Android only.** No iOS or web; reaching another platform later would mean a
  rewrite or a move to a cross-platform stack.
- Tied to the Compose/Material 3 release cadence and its learning curve.

## Alternatives considered

- **Android Views (XML layouts).** Rejected: legacy, more boilerplate, weaker
  state handling than Compose.
- **Kotlin Multiplatform / Flutter / React Native.** Rejected for now: cross-
  platform complexity isn't justified for a solo, Android-first personal project.
