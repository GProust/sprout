# 15. Native iOS, in this repository

Date: 2026-09-10

## Status

Accepted.

Reopens the "Android only" consequence recorded in
[ADR-0004](0004-native-android-compose-mvvm-room.md), which anticipated this:
*"reaching another platform later would mean a rewrite or a move to a
cross-platform stack."* ADR-0004 is otherwise unchanged and still governs the
Android app.

## Context

A household is now a group of phones that merge a baby's record between them
([ADR-0009](0009-the-household-is-a-group-not-a-pair.md)). That works only if
every phone in it runs Sprout, and Sprout runs on Android.

The couple this app was written for is not guaranteed to carry the same handset.
One iPhone in a household turns a shipped feature into a feature that household
cannot use at all — not a degraded experience, an absent one. That is the reason
to reconsider, and it did not exist when ADR-0004 was written: there was nothing
then for a second platform to *join*.

Against that, the cost is real and permanent. It is a second app to write and
then to keep: every feature twice, every release twice, every translation twice,
by the same one person. It needs a Mac, an Apple Developer Program membership at
$99 a year against Google Play's $25 once, and App Store review on every release
rather than on none.

## Decision

Build a **second native app, in Swift and SwiftUI**, in **this repository**.

### Native, not shared

No Kotlin Multiplatform, no Compose Multiplatform, no Flutter. Three reasons,
in the order they matter:

- **The interesting code is platform code.** The sync layer needs `CoreBluetooth`
  directly for advertising and L2CAP channels
  ([ADR-0016](0016-a-transport-both-platforms-can-speak.md)); the PDF and the
  workbook are written byte by byte with no dependency
  ([ADR-0013](0013-writing-the-pdf-and-the-workbook-by-hand.md)); the allow-list
  in [ADR-0014](0014-the-way-in-is-an-allow-list.md) is a *manifest* on one
  platform and an entitlements file plus `Info.plist` on the other. None of that
  shares.
- **What would share is the cheap part.** The repository and the view models are
  thin and near-mechanical to write twice. Kotlin Multiplatform would buy that
  back at the price of moving Room to SQLDelight across fourteen hand-written
  migrations, on a shipping app whose users cannot re-enter their history
  ([ADR-0002](0002-local-first-on-device-storage.md)).
- **Compose Multiplatform would ship a Material app on an iPhone**, and would
  still leave the widget, the notifications and the radio to be written in Swift
  anyway.

### One repository

`android/`, `ios/` and `spec/` side by side, with `docs/`, `CHANGELOG.md`,
`PRIVACY.md`, `LICENSE` and `TRADEMARK.md` shared at the root.

The deciding argument is [`spec/`](../../spec/). What two phones say to each
other is a *format*, and a format has exactly one correct number of homes. Split
the repository and a change to the framing is two pull requests in two places
that must land together and cannot be checked together; the failure when they
drift is silent, because a household whose phones no longer recognise each other
does not raise anything — it just stops syncing, and a parent finds out weeks
later.

In one repository that change is one pull request, one CI run, and one set of
conformance vectors that both test suites read.

The decision records settle it a second time. There are fourteen ADRs and
fourteen BDRs, immutable once merged, and they describe *the product* rather than
either app. Duplicated they diverge; cross-linked they rot. An iOS decision
belongs in the same numbered sequence as this one.

### What the iOS app is not, at first

The first shippable iOS version carries what a phone needs to be a member of a
household and to log a day: the record, the logging screens, the statistics, the
sync, the reminders, the widget, and the seven languages.

It does **not** carry the in-app language picker (iOS has offered a per-app one
in Settings since iOS 13, so `AppLocale`'s job is done by the system), Material
You (there is nothing on iOS to derive a palette from — the existing static
scheme applies), or the tablet layouts.

## Consequences

- **A household can contain an iPhone.** That is the whole point.
- **Every user-visible change is now two changes**, and they are not
  simultaneous: Google Play publishes when we upload, the App Store publishes
  when review says so. The two apps will be at different versions in the field,
  routinely, which the format versioning in `spec/` already has to tolerate.
- **`spec/` becomes load-bearing.** A change to a wire format that lands without
  its vectors is a window where CI is green and the two apps disagree.
- **CI grows a macOS job**, free on this public repository but roughly ten times
  the cost in minutes if it ever goes private. Path filters keep Android pull
  requests off the Mac and vice versa.
- **The privacy claim has to be made twice, differently.** No `INTERNET`
  permission is Android's phrasing; on iOS the equivalent evidence is the
  entitlements file, the absence of any networking symbol, and the App Privacy
  card. `AttackSurfaceTest` needs an iOS sibling that pins entitlements and
  `Info.plist` keys, or the claim is only checked on one platform.
- **`PRIVACY.md`, `README.md` and `docs/RELEASING.md` stop being about an Android
  app** and become about a product with two.
- **A Mac becomes a dependency of the project**, which
  [ADR-0006](0006-ci-as-build-verifier-and-screenshots.md) had so far avoided for
  Android by making CI the only build machine. CI can compile and test the iOS
  app without one; it cannot take a screenshot for the App Store or run on a real
  radio, and neither can a Linux container.

## Alternatives considered

- **Do nothing.** Honest, and it was the right answer for as long as sync did not
  exist. It stopped being right when the product became a group of phones.
- **A separate `sprout-ios` repository.** Reasonable on every axis except the one
  that matters — the shared format — and that axis is where the silent failures
  live. Revisit only if the repository becomes unwieldy for reasons unrelated to
  sync.
- **Kotlin Multiplatform**, sharing the domain and data layers. Rejected above:
  the wrong code shared, at the price of the migration chain.
- **A web app instead**, sidestepping the App Store. Rejected: no Bluetooth on
  iOS Safari, no home-screen widget, no local notifications worth the name, and
  it would put the data somewhere other than the device.
