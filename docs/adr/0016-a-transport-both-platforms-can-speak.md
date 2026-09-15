# 16. A transport both platforms can speak

Date: 2026-09-10

## Status

Accepted (decision) — Android implementation **Proposed**, to land in its own
pull request.

Amends [ADR-0010](0010-automatic-exchange-over-bluetooth-when-the-app-is-open.md)
on two points: what carries the exchange, and how a phone advertises. Everything
else in ADR-0010 — the bounded triggered window, no background scan, no
foreground service, the rotating derived value, the half-hour window — stands
unchanged.

## Context

[ADR-0015](0015-native-ios-in-this-repository.md) adds an iPhone to the
household. Two pieces of ADR-0010 turn out to be Android-only, and neither is a
detail that can be worked around in the iOS app.

**RFCOMM is closed to iOS.** The exchange runs over
`createInsecureRfcommSocketToServiceRecord` with a fixed service UUID. Bluetooth
Classic serial is not available to third-party iOS apps at all — the only door to
it is the External Accessory framework, which requires MFi-certified hardware and
does not apply between two phones. There is no entitlement to request and no
workaround to find.

**Service data cannot be advertised from iOS.** The beacon goes out as BLE
service data, which ADR-0010 was explicit about: *"`setServiceData`, **not**
`setServiceUuid`, and the distinction is the point."* `CBPeripheralManager`
honours exactly two advertising keys, the local name and the service UUID list.
Arbitrary service data is not one of them, foreground or background.

So an iPhone can neither be found by an Android phone nor connect to one. Both
halves of ADR-0010's mechanism need replacing, and ADR-0010 anticipated the first
of them: it listed L2CAP CoC among the alternatives, rejected because *"the PSM
has to be published somewhere, which means a GATT server for a transfer RFCOMM
does with a fixed UUID"*, and closed with *"reconsider if RFCOMM proves flaky in
the field."* iOS is a second reason to reconsider, and a harder one — flakiness
is a quality problem, this is an impossibility.

## Decision

### The exchange moves to L2CAP connection-oriented channels

BLE L2CAP CoC, which both platforms have had for years: `CBL2CAPChannel` since
iOS 11, `listenUsingInsecureL2capChannel` / `createInsecureL2capChannel` since
Android API 29. Both give a pair of streams, which is all
[`SyncSession`](../../spec/wire-format.md#4-the-direct-exchange) has ever
needed — it is deliberately ignorant of Bluetooth and moves bytes over an
`InputStream` and an `OutputStream`, which is why the whole exchange is tested
over a pipe with no radio in sight.

The API-29 floor costs nothing: ADR-0010 already offers the automatic exchange on
API 31+ only, because `BLUETOOTH_SCAN` with `neverForLocation` is what keeps
location out of the manifest. `minSdk` stays 26, and phones below 31 keep the
manual file exchange they have today.

The PSM is published over GATT, as ADR-0010 said it would have to be: a single
read-only characteristic on the service the listener advertises, returning the
PSM it is listening on. That is the cost of this decision, paid once.

### The advertisement moves into the service UUID

The rotating value stops being service data and becomes the service UUID itself:

```
advertUuid = HMAC-SHA256(secret, ascii("sprout-adv-v1:" + window))[0..16]
```

A phone advertises the UUID for the current window and scans for its own two —
current and previous, the same tolerance ADR-0010 already has for clock drift.
Android filters on it with `ScanFilter.setServiceUuid`; iOS passes it to
`scanForPeripherals(withServices:)`, which it must do anyway since iOS will not
scan for everything.

This is **more** private than what it replaces, not less. Today a fixed
`SERVICE_UUID` goes out in the clear next to the rotating value: anyone nearby
can tell a Sprout is present, and count how many. After this, nothing fixed is
broadcast at all — an observer sees a 128-bit value that is meaningless without
the secret and different every half hour. The property ADR-0010 wanted is
preserved and strengthened; only its location changes.

The trade is that "is that a Sprout?" is no longer answerable from the air, by us
either. A phone can only recognise households it holds the secret for, which is
the only question it needs to answer.

## Consequences

- **An iPhone and an Android phone in one household can find each other and
  sync.** Which is the point.
- **Android sync code changes**, behind the existing `NearbyTransport` interface:
  a new L2CAP transport, a small GATT server for the PSM, and a different
  advertisement and scan filter. `SyncSession`, `SyncCrypto`, the payload, the
  merge and the household are all untouched.
- **This lands as its own pull request.** It changes shipped behaviour on a
  feature whose failures are quiet, on radios CI cannot exercise. Bundling it
  into the iOS scaffolding would make one diff nobody can review as a unit.
- **Two Sprouts on different versions must still meet.** During the rollout one
  phone advertises service data and the other a derived UUID, and they are
  invisible to each other. The Android implementation therefore advertises and
  scans **both** for one release cycle, and drops the old form afterwards. The
  cost is a slightly larger advertisement for one version; the alternative is
  every existing paired household silently failing to sync until both phones
  update.
- **`spec/` now covers the advertisement**, with vectors for both the 8-byte
  beacon and the derived UUID, so the two implementations are checked against the
  format rather than against each other.
- **L2CAP is less battle-tested than RFCOMM across Android manufacturers**, which
  is a real risk and the reason ADR-0010 avoided it. The mitigation is that the
  manual file exchange stays, and is still the only path below API 31.

## Alternatives considered

- **Keep RFCOMM for Android↔Android, add something else for iOS.** Two transports
  to maintain, two sets of failures, and the cross-platform pair — the case this
  exists for — still needs the new one. All of the cost, none of the
  simplification.
- **GATT characteristic writes instead of L2CAP.** Works on both platforms and
  needs no PSM, but moving a few hundred kilobytes through 20-byte-ish writes is
  slow enough to blow well past ADR-0010's ten-second window, and the chunking
  and reassembly would be ours to get right. L2CAP is a stream; that is exactly
  what we want.
- **A fixed service UUID plus the rotating value in the local name.** iOS will
  advertise a local name, so this is possible. Rejected: the local name is
  visible in system Bluetooth UI on some platforms, it reintroduces the fixed
  identifier this design exists to avoid, and it abuses a field meant to be
  human-readable.
- **Wi-Fi Aware / Multipeer Connectivity.** Neither is available on both
  platforms; Multipeer is Apple-only, which is the exact problem being solved.
