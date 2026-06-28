# 5. Localization with Android string resources

Date: 2026-06-28

## Status

Accepted

## Context

Sprout should be usable in more than one language and should follow the user's
device language without extra setup. Early screens hard-coded English strings,
which would have made any second language a large retrofit.

This ADR is the **technical mechanism**; the product decision to default to the
device's language is [BDR-0004](../decisions/0004-default-to-system-language.md).

## Decision

All user-facing text lives in **Android string resources**:

- `res/values/strings.xml` is the default/fallback (English);
  `res/values-fr/strings.xml` is French. Adding a language is dropping in a new
  `res/values-<code>/strings.xml` plus a line in `res/xml/locales_config.xml`.
- Language **follows the system locale automatically**, with English as fallback —
  no runtime language-switching code. `localeConfig` (wired via the manifest) also
  enables the Android 13+ per-app language picker, and the app exposes a Settings
  screen for it.
- Locale-aware formatting (dates, durations, relative time, baby age, greetings)
  is done through helper functions that take a `Context`, and composables read
  strings via `stringResource`.
- The brand name `app_name` is `translatable="false"`.
- **Lint treats `MissingTranslation` as fatal**, so a new string without
  translations fails the build rather than silently shipping English.
- Unit tests that exercise resource-backed helpers run under **Robolectric** so
  they can resolve resources.

## Consequences

- Clean internationalization and a real per-app language picker.
- **Every new user-facing string must be added to all locales or CI fails** — this
  is deliberate; it keeps translations from rotting.
- Formatting helpers and some ViewModels take a `Context`, and resource-dependent
  tests need the Robolectric harness.
