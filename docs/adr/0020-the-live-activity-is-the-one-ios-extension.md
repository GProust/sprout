# 20. The Live Activity is the one iOS extension

Date: 2026-10-22

## Status

Accepted.

Applies [BDR-24](../decisions/0024-a-running-feed-outside-the-app.md) — a
running breastfeed is shown outside the app and can be paused, switched and
stopped from there — to the second app. It changes nothing that record decided:
the same states, the same buttons, the same feed saved. Only the mechanism is
iOS's own, because the one Android uses does not exist there.

## Context

On Android a running feed is an ongoing notification: the system draws the
clock itself from a timestamp, the buttons are broadcasts to a receiver the app
already has, and nothing new is declared.

**iOS has no equivalent of that notification.** A local notification is static
text: it cannot show a clock that moves, and it cannot be updated in place
without being delivered again. The form iOS gives a live, glanceable activity —
on the lock screen and in the Dynamic Island, with a clock the system counts —
is a **Live Activity**, and a Live Activity is drawn by a **widget extension**:
a separate bundle, shipped inside the app, running in a process of its own.

Sprout has never had an extension. Everything the privacy claim rests on on
this side — `check_attack_surface.py` pinning the app's Info.plist, linked
libraries and entitlements; `check_no_network.py` proving no code could open a
connection ([ADR-0014](0014-the-way-in-is-an-allow-list.md)) — looked at the app
target and its binary only. An extension would have been code that ships in the
app and that neither check reads.

## Decision

**One widget extension, `SproutLiveActivity`, that draws a running feed and
nothing else.**

- **It only draws.** Every value it shows is worked out by the app, already in
  the parent's language, and handed over as the activity's content: the title,
  the break's sentence, the clock to count from, and which buttons to show — the
  same `nursingControls(for:)` list Android's notification shows. The extension
  links no SproutData, opens no database, reads no session store and holds no
  string catalog.
- **Its buttons run in the app.** Each is a `LiveActivityIntent`, which the
  system performs in the app's process — launching it in the background if it is
  not running. There it is the timer's own act on the timer's own store, and
  *Stop & save* goes through the repository like every other write. The intent's
  type lives in a folder both targets compile (`ios/Sprout/Shared/`); what
  performing it means is the app's.
- **Updates come from the app only.** No push token is ever requested, and the
  `aps-environment` entitlement stays on `check_attack_surface.py`'s refusal
  list. The activity is told where its clock started and the system counts from
  there, so nothing ticks and nothing runs while the app is away
  ([ADR-0010](0010-automatic-exchange-over-bluetooth-when-the-app-is-open.md),
  [ADR-0019](0019-a-reminder-decided-when-it-is-scheduled.md)).
- **No App Group, no entitlement at all.** A Live Activity's content is handed
  over by ActivityKit, so the extension never reads the app's container, and the
  App Group `Sprout.entitlements` expected "when the widget lands" is still not
  needed. The extension's own entitlements are pinned empty.
- **It never names the baby.** A Live Activity is on the lock screen first.
  Android shows the name only in the private version of its notification; here
  there is no private version, so there is no name — one feed is
  *Feeding · Left*, two are *Feed 1* and *Feed 2*.
- **One database per process, opened once.** Because an intent can run before
  any window exists, the on-disk environment is opened through a single
  `AppEnvironment.live()` that the window and the intents share, never twice.

**The checks grow to cover it, in the same commit.**

- `check_attack_surface.py` pins which extensions exist, by target name and
  extension point, at both layers: the source (`project.yml`) and the built app
  (`Sprout.app/PlugIns/*.appex`). Each extension's Info.plist is pinned key by
  key and walked for the same refused keys as the app's; its linked libraries
  and, on a signed build, its entitlements are held to the same refusals. The
  app's own pin gains `NSSupportsLiveActivities`.
- `check_no_network.py` scans the extension's sources and the shared folder,
  as well as the app's.

## Consequences

- **A second signing profile.** Every bundle in an App Store upload is signed
  for its own identifier, so the release needs an App ID
  `com.gproust.sprout.LiveActivity` (no capabilities) and an App Store profile
  for it, as a fourth signing secret (`APPLE_LIVE_ACTIVITY_PROFILE_BASE64`,
  `docs/RELEASING.md` §9). `ios-release.yml` checks it against the certificate
  with `check_signing.py` before archiving, and stops with the secret's name if
  it is missing. The version and build are project-wide settings now, because an
  extension whose version differs from its app's is refused at upload.
- **A Live Activity has limits Android's notification does not.** iOS ends one
  after about eight hours and the parent can turn them off for Sprout in iOS
  Settings. Neither touches the feed: the activity is a view of the session, never
  the record, and the timer on the app's screens is unaffected.
- **Starting one needs the app in the foreground**, which is where a feed is
  started. One that could not be started is simply absent; the feed runs as it
  always did.
- **A second extension is a decision of its own.** The pin lists exactly one,
  and a home-screen widget for iOS — the obvious next one — would need the App
  Group this one avoids, and a record saying so.
