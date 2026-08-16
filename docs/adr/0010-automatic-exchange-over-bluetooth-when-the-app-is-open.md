# 10. Automatic exchange over Bluetooth, while the app is open

Date: 2026-08-16

## Status

Accepted.

Finalizes the transport left open by
[ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md) for phase 2,
and **overrules its two candidates**: neither `NsdManager` + TCP nor Wi-Fi
Direct. The payload, the merge and the household are unchanged
([ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md),
[ADR-0008](0008-pairing-by-invitation-and-the-first-merge.md),
[ADR-0009](0009-the-household-is-a-group-not-a-pair.md)).

## Context

Phase 1 works, and it is entirely manual: someone exports a file and sends it
through a messaging app. Phase 2 was always meant to remove that gesture when
the phones are near each other.

ADR-0007 assumed the transport would be IP on the local network. Costing it
properly changed the answer.

**`android.permission.INTERNET` is misnamed.** It does not grant access to the
internet; it grants the ability to open a network socket at all — Android
implements it by putting the process in the `AID_INET` group, and without it the
kernel refuses `socket(AF_INET, …)` regardless of the destination. A TCP
connection to a phone on the same Wi-Fi needs it exactly as much as one to a
server on the other side of the world. Android has no "local network only"
permission the way iOS does.

That matters here more than it would in most apps, because today the claim in
[PRIVACY.md](../../PRIVACY.md) is **verifiable without trusting us**: anyone can
unpack the APK, read the manifest, and see that Sprout physically cannot open a
socket. Adding `INTERNET` would not change what the code does — it would change
a checkable fact into a promise, in an app whose entire pitch is that nothing
leaves the phone. Wi-Fi Direct is worse still: it needs `INTERNET` *and*
`NEARBY_WIFI_DEVICES`.

Bluetooth is the only transport that keeps the manifest honest. Three things
that used to make it unattractive no longer do:

- **Location.** `BLUETOOTH_SCAN` (API 31+) can be declared
  `neverForLocation`, so scanning no longer drags in `ACCESS_FINE_LOCATION` —
  which, for an app about a baby's feeds, was an unacceptable thing to ask.
- **A second pairing.** `createInsecureRfcommSocketToServiceRecord` needs no
  OS-level bonding, so there is no second pairing ceremony after the one Sprout
  already does.
- **Throughput.** Three months of tracking is roughly 2,500 rows — around 500 kB
  of JSON, which gzips to **50–70 kB**. Any Bluetooth link moves that in
  seconds. Bandwidth was never the constraint.

The real constraint is that **automatic sync needs someone listening**, and
Android does not give that away: a process that accepts connections in the
background means a foreground service, and a permanent notification. That is
true of Wi-Fi too — the radio was never what made sync automatic.

## Decision

### Bluetooth, and only above API 31

Discovery over **BLE advertising**; the exchange itself over **insecure
RFCOMM** with a fixed service UUID.

"Insecure" refers to the Bluetooth link layer having no authentication or
encryption of its own. That is deliberate and safe *here*, and only here: the
bytes on the wire are the same sealed replica phase 1 writes to a file —
AES-256-GCM under the household secret, with an authenticated header. Link-layer
security would protect data that is already protected, at the cost of a second
pairing dialog. The next person to read that method name should find this
paragraph.

The feature is offered **only on API 31+**. Below that, scanning would require
`ACCESS_FINE_LOCATION`, and asking a parent for their location to sync a feeding
log is not a trade we will make. `minSdk` stays at 26 and older phones keep the
manual exchange, which works. `ACCESS_FINE_LOCATION` is never declared, so it is
never requested from anyone, on any version.

### Discovery is bounded and triggered, never continuous

There is **no background scanning, no periodic job, and no foreground service.**
Nothing happens while the app is closed.

A **discovery window** is short (on the order of ten seconds) and opens only
when:

1. the app comes to the foreground, at most once every few minutes; or
2. the user asks, with an explicit *Sync now*.

During a window the phone both advertises and scans, then stops. Radio use is
bounded to seconds per app launch, which is the point: latency measured in
minutes is fine for a feeding log, and battery is not.

When two household phones find each other, they exchange in **both directions in
one session** — something the file flow cannot do in a single step — merge, and
say what changed, exactly as an imported file does.

### The beacon must not become a household tracker

A phone advertising a fixed identifier would be a beacon that anyone nearby could
follow from week to week. So what is advertised is derived: **HMAC(household
secret, current half-hour)**, truncated. It rotates on its own, it is
recognisable only to phones holding the secret, and a listener accepts the
current and previous window so two slightly skewed clocks still meet.

### What this does *not* promise

Automatic means **"when both phones have Sprout open, near each other"**. A feed
logged at 3 a.m. while the other parent's phone is asleep in another room syncs
the next time both apps are open — not at 3 a.m.

The manual exchange from phase 1 **stays, unchanged and equally prominent**. It
is the reliable path, and the only one that works when the two people are not in
the same place. Phase 2 removes a gesture; it does not replace a feature.

## Consequences

- **The manifest stays honest.** No `INTERNET`, so "Sprout cannot open a network
  socket" remains a fact a reader can verify rather than a claim they must
  accept. This is the whole reason for choosing the slower radio.
- **[PRIVACY.md](../../PRIVACY.md) needs an amendment, not a rewrite.** Its
  sentence — *"Sprout requests no sensitive runtime permissions and no internet
  permission"* — becomes half false: the internet half stays true, the runtime
  half does not. It must say that finding nearby devices is asked for only if
  automatic sync is switched on, that it is declared `neverForLocation`, and
  that nothing is transmitted beyond the phones in the household. ADR-0007
  expected a full rewrite of both documents; the Bluetooth route makes
  `README.md` a smaller edit and `PRIVACY.md` a paragraph.
- **Two runtime permissions**, requested at the moment the feature is turned on
  rather than at launch, and refusable with the app fully working.
- **Best effort, by construction.** Two parents who never have the app open at
  the same time never sync automatically. Saying so on the screen is part of the
  work, not a caveat to bury.
- **RFCOMM is uneven across manufacturers**, and discovery costs a few seconds.
  Both are tolerable when discovery is rare and the payload is small.
- **Nothing below the transport changes.** The same sealed bytes, the same
  merge, the same tests. Phase 2 is a new way to deliver a replica, not a new
  kind of sync.

## Alternatives considered

- **`NsdManager` + TCP on the local Wi-Fi** (ADR-0007's leading candidate).
  Rejected: costs `INTERNET`, and with it the verifiable promise. It is the
  easiest to write and the most expensive to explain.
- **Wi-Fi Direct.** Rejected: `INTERNET` *and* `NEARBY_WIFI_DEVICES`, for a
  painful API.
- **Google Nearby Connections.** Still rejected, as in ADR-0007: Play Services
  in an app that has no Google dependency.
- **A foreground service listening continuously.** It is the only way to make
  sync genuinely background, and it costs a permanent notification and real
  battery. For an app opened several times a day anyway, the notification buys
  little and costs a lot.
- **A periodic `WorkManager` scan.** Two phones would have to wake in the same
  ten seconds; Doze defers jobs enough to make that mostly chance. It would
  spend battery to buy unreliability.
- **BLE scanning with a `PendingIntent`**, which wakes the app without a
  foreground service. Genuinely interesting, and heavily throttled with the
  screen off. Worth revisiting *after* the simple version ships and there is
  real usage to judge it against — not before.
- **L2CAP CoC instead of RFCOMM.** Cleaner and BLE-native, but the PSM has to be
  published somewhere, which means a GATT server for a transfer RFCOMM does with
  a fixed UUID. Reconsider if RFCOMM proves flaky in the field.
