# 12. The widget diagnostics screen comes out

Date: 2026-09-06

## Status

Accepted.

Amends [ADR-0011](0011-what-survives-a-new-phone.md), whose decision section
says the backup rules "exclude `device.xml` and `widget_diagnostics.xml`, and
nothing else". One of those two files no longer exists.

## Context

The widget diagnostics were built to catch one specific bug, and they caught
it. `GlanceAppWidget.update()` accepted an update on minified release builds,
returned cleanly, and then never started its session — no exception anywhere,
and on a Play build no practical way to reach logcat. The widget sat on the
launcher's loading spinner for ever and said nothing.

Diagnosing that needed a screen, because the failure only happened in a release
build on a real launcher: a breadcrumb log in `SharedPreferences` that survived
the receiver's process dying, a self-test that composed and inflated the widget
from inside the app, a survey of which of Glance's layouts a shrunk build could
still resolve, and a Share button to get the report off the phone. It worked.
The fix — composing to `RemoteViews` and pushing them through `AppWidgetManager`
ourselves — has been in the app for several releases and the widget has been
steady since.

What is left is the cost. It is a screen, a route, a `SharedPreferences` file,
nine strings in seven locales, a backup-rule exclusion and a clause in a
decision record, all carried for a bug that is fixed. Every widget update wrote
a line to disk. And the surface it presents to a parent is a Troubleshooting
section offering to diagnose something that, as far as they can tell, works —
which reads less like care than like an admission that it might not.

The instinct to keep it "in case it comes back" is worth naming and refusing.
If the widget breaks again it will break differently, and the diagnostics that
found this bug were written *for* this bug — the Glance layout survey exists
because we suspected R8 was stripping resources. The next one would need its
own. Meanwhile a permanent debugging surface in a shipped app is a permanent
tax on everyone who does not need it.

## Decision

**The diagnostics come out; the error state stays.**

- `WidgetDiagnostics`, `WidgetDiagnosticsScreen` and the
  `settings/widget-diagnostics` route are removed, with their strings in all
  seven locales and the Troubleshooting section that led to them. Settings
  loses a section rather than gaining an empty one.
- **The three paths that can fail still say so, to logcat only** — a failed
  draw, a failed read, a failed render in the receiver. `Log.e` under the
  `SproutWidget` tag, reachable with `adb logcat -s SproutWidget` when a cable
  is an option, written nowhere and surfaced nowhere. The informational
  breadcrumbs ("render finished", "receiver: onUpdate for 1 widget") only ever
  existed to fill the on-disk log and go with it.
- **`R.layout.widget_error` stays.** A widget that cannot draw still says so on
  the home screen rather than leaving the launcher spinning — that was the
  worst part of the original bug and it is worth keeping whether or not anyone
  can produce a report. Its string no longer points at Settings; it now says
  what to try instead.
- **`widget_diagnostics.xml` drops out of both backup rule files**, amending
  ADR-0011's list to `device.xml` alone. `BackupRulesTest` is the thing that
  notices if this is got wrong, so it is updated in the same change.

## Consequences

- If a parent reports a broken widget, there is no longer a report to ask them
  for. The honest reckoning: that report was only ever legible to its author,
  and reproducing a launcher bug from a stranger's description is roughly where
  we were before it existed.
- One less `SharedPreferences` file written on every widget update.
- ADR-0011's reasoning is untouched and still correct — the *rule* it states is
  that only what describes the handset is held back. The list got shorter
  because one such thing stopped existing, not because the rule changed.
- Bringing diagnostics back means writing them for whatever the next bug is,
  which is the right way round.

## Relationship to other decisions

- Amends the exclusion list in
  [ADR-0011](0011-what-survives-a-new-phone.md); its rule and its reasoning
  stand.
- The widget's own architecture — compose to `RemoteViews` rather than
  `GlanceAppWidget.update()` — is unchanged, and is what made this removable.
