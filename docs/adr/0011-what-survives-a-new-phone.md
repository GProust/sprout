# 11. What survives a new phone

Date: 2026-09-05

## Status

Accepted.

Decides what Android's backup and its phone-to-phone transfer carry, which
[ADR-0002](0002-local-first-on-device-storage.md) left to the platform defaults.
Does not change what a *replica* carries — that is
[ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md) and is a
separate mechanism entirely.

## Context

Google Play's [app-quality
requirements](https://android-developers.googleblog.com/2026/08/app-quality-memory-optimization-secure-onboarding.html)
add a device-migration bar from April 2027. Most of it — Zero-Tap Sign-In,
restoring an account with the Restore Credentials API — asks nothing of Sprout,
which has no accounts and never will
([ADR-0003](0003-no-first-party-backend-user-owned-sync.md)). The part that does
apply is the plainer half: an app should arrive on the parent's new phone as the
app they left, settings and all.

Sprout has always said `android:allowBackup="true"` and pointed at two rule
files, and both files were empty since the project template generated them. That
is not the same as having decided anything. Empty means the platform default,
and the platform default is *everything*: the Room database, all three
preferences files, on both routes (the backup to the parent's own Google
account, and the cable or Wi-Fi copy during new-phone setup).

For the record itself that default is exactly right, and it is the only backup
Sprout has — losing the phone loses everything, which ADR-0007 already says out
loud. It is wrong for two files, for the same reason in both cases: they
describe *this handset*, and a restore does not retire the handset it copied.

- **`DeviceIdentity`'s id.** It lived in `settings.xml` with the ordinary
  preferences. A parent who restores onto a new phone and keeps the old one
  running — handed to the other parent, not wiped yet — ends up with two
  handsets answering to one id. `HouseholdDevices` keys on that id, so the
  household list shows one entry where there are two phones, and "remove this
  phone" ([ADR-0009](0009-the-household-is-a-group-not-a-pair.md)) removes
  whichever of them the entry happened to be describing.
- **The widget diagnostics log.** Breadcrumbs about one launcher, on one
  handset. A report from a new phone that opens with the old phone's history is
  worse than a report with none.

The pairing needs no rule, and could not have one: the household secret in
`settings.xml` is wrapped by a Keystore key, and Keystore keys do not leave the
handset. What lands on the new phone is ciphertext nothing there can open. That
is the right outcome — membership of a household is possession of the secret
(ADR-0009), and a phone that has never been invited should not have it — but
until now it left a half-record behind: a household id and a `first_merge_done`
flag beside a secret that opens nothing. `first_merge_done` is the one that
bites. It is what makes
[ADR-0008](0008-pairing-by-invitation-and-the-first-merge.md)'s question — keep
both histories, or share from the pairing forward — get asked once and not
again, so a restored phone carrying it would skip that question the next time it
was invited into a household, and adopt silently.

## Decision

**State the rules rather than inherit them, and hold back only what describes
the handset.**

- `@xml/backup_rules` (API ≤ 30) and `@xml/data_extraction_rules` (API 31+)
  exclude `device.xml` and `widget_diagnostics.xml`, and nothing else. The
  31+ file says it twice, once for `cloud-backup` and once for
  `device-transfer`: a section left out means "everything", and the cable copy
  lands on a second handset just as surely as a restore does.
- No `<include>` anywhere. An include list is an allow-list, and a stray one
  would quietly stop the database — the record itself — from travelling at all.
- The device id moves to a preferences file of its own, `device.xml`, so that
  the exclusion can be file-shaped: the rules exclude files, not keys, and the
  id cannot be held back while it shares a file with the language setting.
  An install that predates the move carries its existing id across rather than
  minting a new one — the other phones know it by that name, and a fresh one
  would leave a ghost in their lists that nothing can clear.
- A pairing whose secret no longer unwraps clears itself. It is not a transient
  failure — the key is gone, and gone permanently — so `PairingStore.current()`
  treats it as `unpair()` and the leftovers, `first_merge_done` included, go
  with it.

## Consequences

- A parent moving to a new phone arrives with their record, their language,
  their reminder settings and their nursing session in progress, and with an
  identity of their own. Both phones can be in the household at once, and the
  list can tell them apart.
- **They arrive unpaired.** Sprout cannot carry the household key across, and
  will not pretend otherwise: rejoining is a fresh invitation from a phone that
  is already in the household, and the first merge asks its question properly.
  This is the one piece of friction the decision accepts, and it is the same
  friction that makes removal-by-rotation mean anything.
- **One backup taken before this change still carries the old id.** Restoring a
  pre-1.9 backup onto a new phone hands it the id from `settings.xml`, exactly
  as before. Nothing can reach into a backup already taken; the first launch on
  the new version moves the id out of that file, so it is the last backup that
  can do it.
- The rules are now testable, and tested: `BackupRulesTest` reads both files
  back and checks them against the code that names the preferences files.
  Nothing else reads them — Android does — so without that they would be two
  files that can be wrong for a year without anyone noticing.
- **Not tested end to end.** Proving a restore actually behaves would need
  `bmgr` against a real device, which
  [ADR-0006](0006-ci-as-build-verifier-and-screenshots.md) does not have. What
  CI proves is that the rules say what we meant; that they mean what we think
  Android reads is on the release checklist, not in a test.

## Alternatives considered

- **Turn `allowBackup` off.** Honest about the app being local-first, and it
  would make every new phone a blank one. Sprout's data is a record a parent
  cannot re-enter; the platform backup is the only safety net it has. Rejected
  without much thought.
- **Exclude the whole `settings.xml`.** Simpler than moving one value out of
  it, and it would throw away the language, the reminder intervals and the
  nearby switch to hold back a single UUID — the opposite of the migration this
  is for.
- **A `BackupAgent` to filter by key.** The only way to hold back one key of one
  file without moving it, and it means owning the whole backup and restore
  protocol by hand — including the Room database — for one string. A second
  preferences file costs four lines.
- **Keep the secret out of the backup explicitly.** Unnecessary: it is already
  useless off the handset. Writing a rule for it would suggest the rule is what
  protects it, when the Keystore is.
- **Carry the pairing across some other way** — the secret in the invitation
  file, or re-derived. Rejected: it would mean a household key that survives
  leaving the phone, which is the thing ADR-0009's removal-by-rotation depends
  on not being true.
