# 9. The household is a group, not a pair

Date: 2026-08-16

## Status

Accepted.

Amends [ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md) and
[ADR-0008](0008-pairing-by-invitation-and-the-first-merge.md), which are written
throughout as *two* phones. The merge rules, the payload, the invitation and the
stash switch are unchanged; what changes is how many devices they are for, and
what it takes to remove one.

## Context

A newborn is rarely looked after by exactly two people. Grandparents who have
the baby two days a week, a parent's sibling staying for a month, a childminder
— these are ordinary, and they log feeds and naps like anyone else. The
co-parent is the *common* case, not the only one.

The good news came out of writing phase 1 rather than planning it: **the merge
already works for any number of devices.** A union keyed on `uid`, arbitrated by
`updatedAt`, with tombstones that always win, is idempotent and commutative — it
does not count participants. Three phones exchanging replicas converge exactly
as two do, and every property the tests pin down holds unchanged. Nothing in the
engine has to move.

What is genuinely pair-shaped is narrower, and all of it is above the engine:

- **The words.** Every screen and all seven locales say "the other parent",
  "both phones", "your partner's phone".
- **Removal.** With one shared secret, "unpair" means *this* phone stops taking
  part. With four devices, "remove the childminder, keep everyone else" is a
  different operation, and it does not exist.
- **The stash switch is all-or-nothing.** One secret and one replica means the
  expressed-milk log can be shared with the household or with nobody — not with
  a partner but not a grandparent.

## Decision

### A household is a set of devices sharing one secret

The wording follows: **household**, and *the phones in your household*. The
co-parent stays the everyday example in the body text — that is who most people
are sharing with — with grandparents named so that nobody has to wonder whether
the feature is meant for them.

`householdId` and the shared secret already work this way. Inviting a third
phone is sending it an invitation, exactly as the second one was.

### Removing a device means rotating the secret

There is no server to revoke against, and no per-device key: possession of the
secret *is* membership. So removal is:

1. generate a new secret, keeping the same `householdId`;
2. re-invite every device that stays.

The device being removed keeps the data it already holds. We cannot reach into
someone's phone and erase it, and the UI must not imply otherwise — what
rotation stops is everything from that point on.

Two consequences we accept:

- **A device that is not re-invited goes mute.** It can neither open new
  replicas nor produce ones the others can read, until someone sends it a fresh
  invitation. A phone that happened to be away when the secret rotated looks
  exactly like a removed one, and the UI has to warn before rotating rather than
  explain afterwards.
- **The verification code changes**, because it is derived from the secret. That
  is a feature: everyone still in the household sees the same new code, and
  anyone still showing the old one has not been re-invited yet.

### There is a device list, and it is a courtesy, not a gate

Every replica already carries the `deviceId` of the phone that produced it
(phase 0), so a phone can remember which devices it has heard from, and when.
Replicas now also carry the sender's **display name**, so the list can say
"Mamie's phone" instead of a UUID — an optional field, absent from replicas
written by older versions, which read and merge as before.

This list is a record of who has been seen, kept per device. It is **not access
control**: the secret is what grants access, and a device that never sends a
replica never appears. Removing someone from the list is not what removes them
— rotating the secret is.

### Per-recipient sharing is out

Sharing the stash with a partner but not with a grandparent would need a secret
per participant and a replica per recipient: a different transport, a different
pairing model, and an access-control story this design deliberately does not
have. It is rejected, not deferred.

The honest statement, which the app should be able to make plainly: **everyone
in a household sees everything that is shared, and can add to it.** There is no
read-only member and there cannot be one under a shared symmetric secret.

## Consequences

- **No engine change.** Phase 0's merge and phase 1's payload carry this
  decision without modification, which is the strongest evidence the original
  model was the right shape.
- **The parent's own data is unchanged, and matters more.** `wellbeing` and
  `parent_profile` still never leave the device
  ([ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md)), and the
  refusal to make that a setting looks better with four devices than with two:
  there is no toggle for a relative to ask about.
- **Rotation is disruptive by design**, and there is no way to make it less so
  without a server to arbitrate. Removing one person costs everyone else one
  invitation.
- **The stash switch keeps its meaning** — whether *this* phone's expressed-milk
  log leaves at all — but its blast radius grows with the household, which the
  wording has to reflect.
- **Phase 2 is unaffected.** Automatic exchange on a local network is the same
  payload and the same merge, whether there are two phones or five.

## Alternatives considered

- **A key per device, so one can be revoked alone.** The right answer if there
  were a server, or if we were willing to ship a key-agreement protocol between
  phones that meet only through a messaging app. Both are far past what phase 1
  is.
- **Leaving it at two and telling people to share one phone.** Rejected: the
  data model already supports the general case, and the only thing standing in
  the way was the copy.
- **A "guest" who can log but not read history.** Needs enforcement that a
  shared secret cannot provide; a device holding the key can read everything it
  receives regardless of what the UI offers.
