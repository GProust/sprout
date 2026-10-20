# BDR-22. What's new is a card on the dashboard, and the parent puts it away

Date: 2026-10-20
Type: Product / UX

## Status

Accepted

## Context

Sprout's new features are found by accident. Several releases have added things
a parent would use if they knew about them:

- a breastfeed that pauses for a burp ([BDR-17](0017-a-breastfeed-can-stop-and-carry-on.md));
- two feeds joined back into one ([BDR-19](0019-two-breastfeeds-saved-apart-can-be-joined.md));
- the First foods page and a baby's allergies
  ([BDR-20](0020-a-solid-meal-says-what-was-in-it.md),
  [BDR-21](0021-a-babys-allergies-as-the-household-knows-them.md)).

The only place any of them is announced is the store listing's release notes,
which almost nobody reads after an automatic update. `CHANGELOG.md` is not
shipped in either app.

The obvious answer is a "what's new" screen on the first launch after an update.
That is exactly what [BDR-6](0006-check-in-waits-on-the-dashboard.md) took out
of the app: "a parent who opens Sprout mid-feed lands on the dashboard, always".
The first launch after an update is like any other: it can be at 3 a.m., with a
baby in one arm.

Two more things make it easy to get wrong:

- **The two apps number themselves apart.** Android is 1.13.0 / versionCode 23,
  and iOS's `MARKETING_VERSION` is 1.0. "Seen version 1.14" means nothing on the
  other phone.
- **A first install is not an update.** Everything is new to a parent who has
  just arrived, and nothing has *changed*. A card that greets them with "new in
  this version" is noise on the one day they are learning the app.

## Decision

**What's new is a card on the dashboard, not a screen in front of it.**

- **The card.** After an update that carries an announcement, the dashboard shows
  one line, "New in Sprout", with the announcement's title.
  - It sits below what is running (a feed on the timer, a baby asleep) and above
    the babies.
  - Tapping it opens a sheet with the announcements: a title, a few sentences,
    and *Show me* when there is a screen to go to.
  - The cross puts it away.
- **It never stands in front of anything.** Launch goes straight to the
  dashboard, as before. A widget tap, a sync or invitation file, or a running
  feed all land where they always did. It is never a notification.
- **Read or put away, it is gone.** Opening the sheet counts as reading it.
  Either way the card does not come back until there is a newer announcement.

**Announcements are highlights, numbered once for both apps.**

- An announcement is written for a release that brings something a parent would
  want to know is there. A release of fixes has none. The changelog stays the
  full record.
- Each has an id from one sequence shared by both apps:
  - Android: `ANNOUNCEMENT_IDS` in `data/WhatsNew.kt`.
  - iOS: `announcementIds` in `SproutData/WhatsNew.swift`.
- Its text is `whats_new_<id>_title` / `_body` in Android's strings, in all seven
  languages, generated into iOS's. The same id is the same words on both phones.
- Ids are appended, never renumbered or reused. A phone remembers the newest id
  it has shown, so a reused number would read as already seen.
- Each app lists only the announcements whose feature it actually has.

**Who sees it is decided by what this phone has read, not by its version.**

- **The store.** The phone stores the newest id it has shown (`whats_new_last_seen`):
  - Android: in `settings.xml`.
  - iOS: in the `UserDefaultsStore`.

  It is backed up like any other setting, because "I have read this" is about a
  person, not a handset ([ADR-0011](../adr/0011-what-survives-a-new-phone.md)). It
  is not synced: the other parent has not read anything on this phone.
- **A fresh install sees nothing.** Finishing onboarding marks every announcement
  seen.
- **A phone updating from a version before this decision** has a profile and
  nothing stored. It sees the **newest** announcement only, not the history.
- **A phone that is several announcements behind** sees all of them, newest
  first.
- **The rule** is one pure function on each side (`announcementsToShow`), with
  the same cases tested on both.

## Consequences

- An update with something worth saying says it once, where the parent already
  is, and asks for nothing. A parent mid-feed can scroll past it, and a parent
  who never reads it loses nothing.
- Writing an announcement is a release step: `docs/RELEASING.md` asks whether
  the release carries one. Forgetting it costs the announcement, nothing else.
- The first announcement is First foods: the page, the foods in a meal, and the
  allergies. It is the biggest change since sharing, and the one most likely to
  go unfound.
- The screenshot runs mark everything seen in their seeds, so the dashboard
  captures show the dashboard of every other day.
- Being a card means it can be missed on a busy day. That is accepted for the
  same reason BDR-6 accepted it for the check-in: a missed announcement costs
  nothing.

## Alternatives considered

- **A screen or sheet at launch.** It is the conventional "what's new", and the
  one most certain to be read. It is also exactly what BDR-6 removed, and it
  would need its own rules for when to yield to a running feed, a widget tap or
  a sync file. This decision would have to amend BDR-6 to allow it, and nothing
  here is urgent enough for that.
- **Keyed by app version.** That is simpler on one platform, but it breaks
  across two that number themselves apart. It would also announce every release,
  including those with nothing to say.
- **Showing the changelog.** It is the full record, written for the store, in
  English only in the repository. A parent wants the one thing worth knowing, in
  their language.
- **Showing every announcement to a phone that never stored one.** A parent
  updating after a year would be handed the whole history. Only the newest one is
  news.
- **A notification.** BDR-6 says why Sprout does not nag, and an announcement is
  the least urgent thing it could say.
