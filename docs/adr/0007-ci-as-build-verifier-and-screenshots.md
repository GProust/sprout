# 7. CI as the build verifier, with screenshots in PRs

Date: 2026-06-28

## Status

Accepted

## Context

The maintainer develops without a local Android toolchain (no JDK/Gradle/SDK/Studio
on the working machine). We still need confidence that changes compile, pass tests,
validate the Room schema, and look right — and we want UI changes to be reviewable
visually, not just as code.

## Decision

Treat **GitHub Actions as the build verifier**:

- `ci.yml` runs **lint + unit tests + `assembleDebug`** on every push and pull
  request. This is the authoritative "does it build and pass?" signal.
- A second workflow (`screenshots.yml`) boots an **Android emulator**, runs an
  instrumented `ScreenshotTest` that seeds sample data and renders each screen
  (clicking through multi-step flows), and **commits the PNGs to the PR branch** so
  they show in the PR's *Files changed* tab.
- `main` is protected by a ruleset: changes land via **pull requests**, not direct
  pushes.

## Consequences

- Reproducible, environment-independent builds; no "works on my machine".
- **Visual review** of every UI change is built into the PR.
- Feedback is slower than a local build (CI minutes), and the emulator path has
  the usual flakiness and setup sharp edges (documented in the workflow).
- Some operational quirks to remember: the screenshot auto-commit uses
  `[skip ci]`, and squash-merges can absorb that marker — so after a squash merge,
  `main`'s push build may be skipped and is re-triggered manually.

## Notes

Because there is no local build, **schema and migration correctness is only proven
in CI** — an extra reason migrations are written carefully
([ADR-0002](0002-local-first-on-device-storage.md)).
