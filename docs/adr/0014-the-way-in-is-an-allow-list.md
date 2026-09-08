# 14. The way in is an allow-list, and untrusted bytes are fuzzed

Date: 2026-09-08

## Status

Accepted.

Says how [ADR-0002](0002-local-first-on-device-storage.md)'s "private database on
the device" is actually kept private, and what is done about the one input that
does not come from the parent holding the phone. Changes nothing about what
syncs or how — that is
[ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md) and its
amendments.

## Context

The question that started this was whether the database should be encrypted at
rest, behind a fingerprint. Answering it needed an inventory of what is actually
stored and what actually reaches it, and the inventory turned out to be the more
useful artefact.

**What is on disk.** `sprout.db`, a plain Room/SQLite file in the app's private
storage, and three preferences files — `settings.xml`, `device.xml`,
`nursing_session.xml`. One value in there is already encrypted at rest: the
household's sync secret, wrapped by a hardware-backed Keystore key
(`KeystoreVault`), because that one has to survive a backup landing on a phone
that was never invited ([ADR-0011](0011-what-survives-a-new-phone.md)).

**What keeps it private.** The app sandbox — the file is owned by Sprout's UID,
mode 0600, SELinux-labelled — and the platform's file-based encryption, which
ties that storage to the lock screen. Neither is something Sprout implements;
both are stronger than anything it could implement, because an attacker who is
past them is also past any key the app itself can hold.

Against that, encrypting the database buys very little and costs a great deal.
It defends against offline access to a file the sandbox already refuses, while
the attacker who does get the file — root, a custom recovery — also gets the
process memory the key sits in. Meanwhile the widget reads the database from a
Glance update with no UI to prompt from, and all three reminder receivers
re-arm alarms on `BOOT_COMPLETED`; a key gated on user authentication means a
feeding reminder silently does not fire after a reboot until someone opens the
app and touches a sensor. Converting an existing database is a one-shot,
non-idempotent whole-file rewrite that has to survive being killed part-way,
against a record a parent cannot re-enter and with CI as the only verifier
([ADR-0006](0006-ci-as-build-verifier-and-screenshots.md)). And a
biometric-bound key is permanently invalidated by a lock-screen change, which
`PairingStore` can afford — a pairing is re-invitable — and the record cannot.

**What the inventory did turn up** is that "nothing reaches Sprout from outside"
is not quite true. Two things do.

The first is bytes. The manifest offers Sprout for `application/octet-stream`,
and has to: an invitation coming back out of WhatsApp arrives as a `content://`
URI with no file name to match a pattern against, so a narrower filter means
invitations that never open. The cost is that **any app on the phone can hand
Sprout a file**, and three parsers read it. `SyncInvitationCodec` reads one with
nothing authenticated at all — there is no shared key yet, that being the point
of an invitation. `SyncCrypto.open` handles a replica's magic, version, framing
and lengths before the AES-GCM tag has said anything. `SyncPayloadCodec` sits
behind the tag, so its attacker is a phone already in the household.

The second is the surface itself, which is small and was never written down.
Five permissions; two exported components; one content provider, closed, scoped
to two cache directories. All of it correct, all of it correct *by care* — and
absence is not what a feature test notices going missing. A permission added, a
component exported, a `<files-path>` added so one screen can share one more
thing: each compiles, passes every other test, and ships.

## Decision

**Pin the surface as an exact list, and assert a property over the bytes that
cross it.**

- `AttackSurfaceTest` pins Sprout's own components and which of them are
  exported, the FileProvider's terms, every path in `@xml/file_paths`, and the
  permissions the manifest Sprout writes declares. Those lists are exact.
  Widening the surface stays allowed; it just cannot happen quietly, because the
  test fails and the diff has to say so.
- The *merged* manifest is checked differently, and deliberately so. It carries
  whatever every AndroidX artifact declares, so an exact list there fails on a
  routine version bump while saying nothing about Sprout. What it is asked
  instead is the pair of questions that would cost something: that Sprout's five
  permissions survive the merge, and that nothing anywhere in the build asks for
  one of the permissions the app tells its users it does not have — a socket of
  any kind, where the phone is, shared storage, a foreground service.
- `UntrustedInputFuzzTest` asserts the property the hand-picked negative tests
  could only sample: **over thousands of mutations of a valid file, every parser
  either reads it or throws the exception it documents.** Anything else — an
  index out of bounds, an `OutOfMemoryError`, a `StackOverflowError` — is a file
  that closes the app, sent by an app that only needed permission to share.
- Two ceilings make that property reachable. `SyncLimits.MAX_FILE_BYTES` caps
  what `SyncFiles.read` will pull into memory, the same eight megabytes
  `SyncSession` already puts on the radio. `SyncLimits.MAX_JSON_DEPTH` refuses a
  document nested deeper than the format can be, *before* parsing rather than
  around it.

  That second one closed a live bug rather than hardening against a theory.
  Android's `org.json` is the Harmony implementation, and `JSONTokener` recurses
  once per nested value; measured against the real runtime with an 8 MB stack,
  around twenty thousand nested brackets — **a file of roughly forty kilobytes**
  — exhausts it. Exhausting it raises `StackOverflowError`, which is an `Error`
  and not an `Exception`, so the `catch (e: Exception)` each decoder wrapped its
  parse in let it straight through. Any app able to share a file could close
  Sprout on the tap that opened it, before anything was authenticated.
- **The database stays as it is.** Plaintext Room, inside the sandbox, under the
  platform's encryption. Reopening this needs a threat the sandbox does not
  already answer.

Both tests run in the ordinary JVM unit-test job — no emulator, no dependency,
no new infrastructure.

## Consequences

- The privacy claim is checkable rather than remembered. `SupportLinksTest`
  already asserted the absence of `INTERNET`; this is the rest of the sentence,
  and it fails in CI rather than in a review someone was tired for.
- **Some failures will be the test working, not a bug.** A dependency that
  starts shipping an exported provider fails `AttackSurfaceTest`, and that is
  the notification, not the noise. The fix is to look, then to widen the list
  deliberately.
- **A legitimate new door costs a line and a sentence.** Adding a receiver or a
  permission means editing the pinned list in the same commit, which is the
  whole mechanism: the list is where "should this be exported?" gets asked.
- Third-party declarations are outside the exact pin, in both senses. Compose's
  debug tooling contributes an exported preview activity, the profile installer
  an exported receiver, and the merged manifest carries permissions no Sprout
  file mentions — the first version of this test pinned that merged list and
  failed on exactly that. Pinning a dependency's declarations turns every
  routine bump into a failing build while saying nothing about Sprout's own
  doors, so the components pin is scoped to this package and the permission pin
  reads Sprout's own manifest file. The refusal list above is what still covers
  the merged result, and the pull-request checklist asks the rest.
- Sprout will now refuse a file that is implausibly large or implausibly nested,
  instead of being closed by one. No file it has ever written comes close to
  either ceiling — a replica is four levels deep and a few hundred kilobytes.
- **Not proven on a device.** Robolectric reads the merged manifest, not the
  installed package, and the fuzzer exercises the parsers, not the intent
  plumbing that reaches them. What CI proves is that the surface is what we meant
  and the parsers hold; that Android enforces it as we read it stays on the
  release checklist, like the backup rules in ADR-0011.

## What this does not decide

**An app lock.** The one threat the sandbox genuinely does not answer is the
unlocked phone handed to someone else, and the postpartum check-ins — mood,
bleeding, delivery type — are the rows that matters for. The answer to that is a
screen gate, not ciphertext: an encrypted database is decrypted and on screen
the moment the app is open. Whether Sprout should have one, off by default, is a
product decision and belongs in a BDR, together with the two things that leak
past any such gate — reminder notifications naming a baby and a medication on
the lock screen, and the absence of `FLAG_SECURE`.

## Alternatives considered

- **SQLCipher over the Room database.** Rejected above: it defends against an
  attacker the sandbox already stops, breaks the widget and the boot-time alarm
  re-arm, needs a one-shot conversion of a record that cannot be re-entered, and
  adds native libraries to an app that
  [ADR-0013](0013-writing-the-pdf-and-the-workbook-by-hand.md) declined a
  dependency for.
- **A biometric-bound Keystore key for the database.** Rejected: invalidated
  permanently by a lock-screen or fingerprint change. Survivable for a pairing,
  not for the record — and the passphrase-with-recovery it would need to be safe
  is a login by another name, in an app that decided not to have accounts
  ([ADR-0003](0003-no-first-party-backend-user-owned-sync.md)).
- **An automated penetration test.** Rejected as a recurring job: the scanners
  that matter need a service to talk to, and there is no network surface at all
  to point one at. An APK static scanner does produce output, but on this app
  every finding is a decision with an ADR behind it — the broad intent filter,
  `allowBackup`, the FileProvider — so it emits a triage list that regenerates
  itself every run and trains its readers to ignore it. Worth running once as a
  second opinion; not worth wiring to CI. Fuzzing the parsers is the part of
  that idea with something to find here.
- **Leaving the surface to review.** It is what we were doing, and it was
  working. It scales exactly as far as the reviewer's memory of what the list
  used to be.
