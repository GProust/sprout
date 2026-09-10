# 18. Where iOS keeps what must not travel

Date: 2026-09-10

## Status

Accepted.

Applies [ADR-0011](0011-what-survives-a-new-phone.md) to the second app
([ADR-0015](0015-native-ios-in-this-repository.md)). It decides *where* two
values live on iOS; it does not change what a backup ought to carry, which
ADR-0011 already settled, nor what a replica carries
([ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md)).

## Context

ADR-0011 divided Sprout's stored values in two. Almost everything should arrive
on the parent's new phone — the record above all, but the language, the reminder
intervals, the nearby switch. Two things must not, because they describe *this
handset* and a restore does not retire the handset it copied: the device's own
sync id, and the household secret.

Android could express that as a file list. Preferences are several files, the
backup rules exclude files, so the id moved into a `device.xml` of its own and
the rules name it. The secret needed no rule at all: it sits in `settings.xml`
wrapped by a Keystore key, and Keystore keys do not leave the handset, so what
lands on a new phone is ciphertext nothing there can open.

**Neither mechanism exists on iOS.** `UserDefaults` is one property list per
app, taken by a backup whole or not at all; there is no per-key exclusion and no
second file to move a key into. And there is no Keystore — the equivalent
hardware-backed key store *is* the keychain, so the wrap-a-value-in-preferences
pattern has nowhere to put the wrapping key that is not already the place the
value could have gone.

What iOS does have is a per-item flag. A keychain item stored
`kSecAttrAccessibleWhenUnlockedThisDeviceOnly` is excluded from the iCloud
backup and from the cable transfer during new-phone setup — the same two routes
ADR-0011's rules name, and the same exclusion, expressed per value instead of
per file.

## Decision

**Two stores, chosen by whether a backup should carry the value.**

- `UserDefaultsStore` holds the ordinary settings, including the household id,
  the pairing moment and the stash switch. A backup carries these, as it does on
  Android.
- `KeychainStore` holds the device id and the household secret, every item
  `…ThisDeviceOnly`. A backup carries neither.

Both are `DeviceLocalStore`, which is the same seam Android's `SecretVault`
interface opens and is there for the same reason: the keychain needs an
entitlement a test bundle does not have, so tests use an in-memory third
implementation rather than reaching a real one and failing on the runner.

The keychain is used here for its **accessibility flag as much as its secrecy**.
The device id is not a secret — it travels in the clear inside every replica —
but it is the one value that must not be in a backup, and the keychain is the
only store on the platform that can say so about a single value.

`WhenUnlocked` rather than `AfterFirstUnlock`: nothing here is read while the
phone is locked, because the exchange happens when the app is open
([ADR-0010](0010-automatic-exchange-over-bluetooth-when-the-app-is-open.md)).

## Consequences

- **A restored iPhone arrives unpaired**, exactly as a restored Android phone
  does, and for a reason a reader can check: the secret was never in the backup.
  `PairingStore.current()` finds a household id with no key behind it, treats it
  the way Android treats a secret that no longer unwraps, and clears the
  leftovers — `first_merge_done` included, so
  [ADR-0008](0008-pairing-by-invitation-and-the-first-merge.md)'s question gets
  asked properly the next time.
- **The two phones in a household can be told apart**, because each has an id of
  its own and a restore does not duplicate one.
- **A keychain item outlives the app.** iOS does not clear the keychain on
  uninstall, so a reinstall can find the old secret still there. It is inert:
  the household id lives in `UserDefaults`, which *is* cleared, so
  `current()` returns nil and never reads it. `unpair()` deletes it outright.
- **Not tested end to end, for the same reason as Android's rules.** What the
  tests prove is that an absent secret reads as "not paired" and takes the
  leftovers with it. That the platform really leaves a `…ThisDeviceOnly` item
  out of a backup is Apple's documented behaviour, checked on the release
  checklist rather than in CI — [ADR-0006](0006-ci-as-build-verifier-and-screenshots.md)
  has no device to restore onto.

## Alternatives considered

- **Wrap the secret with a keychain key and keep the blob in `UserDefaults`**,
  mirroring Android literally. It is strictly worse: the same protection, one
  more moving part, and the wrapping key would have to be a keychain item
  anyway — so the secret would be one indirection away from a store it could
  simply have lived in.
- **A file in Application Support with `isExcludedFromBackupKey`.** This does
  work, and it is the closest analogue to `device.xml`. Rejected for the secret,
  which belongs in the keychain on its merits, and then rejected for the device
  id too rather than keep two exclusion mechanisms that have to be right
  separately.
- **Exclude the whole `UserDefaults` plist.** Not possible per app, and it would
  throw away the language and the reminder settings to hold back one uuid — the
  opposite of the migration ADR-0011 is for.
- **Let the pairing survive a restore.** It would mean a household key that
  outlives the phone, which is the thing ADR-0009's removal-by-rotation depends
  on not being true.
