# Sprout — working notes

Offline-first Android newborn/postpartum tracker. Kotlin, Compose + Material 3,
MVVM, Room. No accounts, no network calls, no analytics.

## Before changing anything

- The **why** behind the architecture lives in [`docs/adr/`](docs/adr/), and the
  product/domain rules in [`docs/decisions/`](docs/decisions/). Read the relevant
  record before reopening a settled question; if a decision genuinely changes,
  add a new record instead of editing the old one (they are immutable once
  merged).
- There is **no local Android toolchain** — CI is the build verifier
  ([ADR-0006](docs/adr/0006-ci-as-build-verifier-and-screenshots.md)). Schema and
  migration correctness is only ever proven there, so treat migrations with
  matching care.
- Room migrations are **hand-written, with no destructive fallback**
  ([ADR-0002](docs/adr/0002-local-first-on-device-storage.md)). A user cannot
  re-enter this history.
- `CHANGELOG.md` is user-facing release notes, in the voice of the existing
  entries. Docs-only changes don't go in it.

## Sharing a baby's record between phones

Shipped, and the one part of the app with rules that are easy to break by
accident. The **why** is in
[ADR-0007](docs/adr/0007-partner-sync-by-direct-device-to-device-exchange.md)
(direct device-to-device merge),
[ADR-0008](docs/adr/0008-pairing-by-invitation-and-the-first-merge.md) (pairing
by invitation file, no QR; the first merge adopts),
[ADR-0009](docs/adr/0009-the-household-is-a-group-not-a-pair.md) (a household,
removal by rotating the secret),
[ADR-0010](docs/adr/0010-automatic-exchange-over-bluetooth-when-the-app-is-open.md)
(Bluetooth rather than the local network; bounded, triggered discovery) and
[ADR-0011](docs/adr/0011-what-survives-a-new-phone.md) (what a backup and a new
phone carry). Read the relevant one before changing behaviour — what syncs, what
doesn't, and how conflicts resolve are decided there, not per-PR.

Five things that a change can quietly undo:

- **Deleting is two paths.** An ordinary delete flags the row (`deletedAt`);
  "permanently delete a baby" erases the rows and keeps only their uids in the
  `tombstone` table, so the deletion still travels without the data lingering.
  Both are compacted after `TOMBSTONE_RETENTION_DAYS`. Every read filters
  `deletedAt IS NULL` — a forgotten filter shows deleted entries again.
- **`SproutRepository` is the only place that stamps `uid`/`updatedAt`.** Keep
  it that way; a DAO called directly writes an unstamped row, which then loses
  every merge.
- **No `INTERNET` permission, ever.** It grants any socket at all, and its
  absence is the one privacy claim a user can check for themselves rather than
  take on trust. The same goes for `ACCESS_FINE_LOCATION`: `BLUETOOTH_SCAN` is
  declared `neverForLocation`, which is why the automatic exchange is offered on
  API 31+ only while `minSdk` stays 26.
- **Discovery is bounded and triggered** — a ~10 s window when the app comes to
  the foreground (throttled by `NearbyPolicy`) or an explicit *Sync now*. No
  background scan, no periodic job, no foreground service. Latency in minutes is
  the accepted trade for battery.
- **`device.xml` is the file backups leave alone** ([ADR-0011](docs/adr/0011-what-survives-a-new-phone.md),
  exclusion list amended by [ADR-0012](docs/adr/0012-the-widget-diagnostics-screen-comes-out.md)).
  Everything else travels to a new phone; this phone's sync identity must not,
  or a restore leaves two handsets answering to one id. Put device-local values
  there and nowhere else, and keep `@xml/backup_rules` and
  `@xml/data_extraction_rules` — which `BackupRulesTest` checks — in step.

Anything user-visible here also touches `PRIVACY.md`, which carries its own
dated change log at the end.

## Statistics and the growth curves

The Statistics screen turns the logs into per-day figures, and draws growth
against the WHO standards. The rules are in
[BDR-0008](docs/decisions/0008-statistics-and-the-who-growth-curves.md); three
that a change can quietly break:

- **The WHO tables in `ui/stats/WhoGrowth.kt` are reference data, not code.**
  They are the published LMS parameters, and `WhoGrowthTest` checks the computed
  curves against the WHO's own printed z-score values. Never "tidy" a number
  there; if a table needs to change, re-derive it from the published tables.
- **The baby's sex is never asked.** Curves are read against *both* references
  and reported as a span, and "inside the band" means inside for at least one of
  them. A `sex` field would be a product decision, not a convenience.
- **Empty days count, today doesn't, and days before the birth never do.** Days
  with nothing logged are zeroes in the series (they are what the averages
  divide by); averages cover completed days only, so a half-lived today can't
  drag a week down; and the window stops at the birth, or a twelve-day-old asked
  for "30 days" is averaged over eighteen days they did not live.
- **Chart colours are validated, not chosen.** The growth band's warm/cool pair
  and the nappy bars' two colours were picked by running a colour-vision check,
  not by eye — the obvious four-hue palettes all failed it. Re-run the check
  before changing one.
- **A sleep can also say where it happened and how the baby was lying**
  ([BDR-0014](docs/decisions/0014-what-a-sleep-records-beyond-its-hours.md)).
  Both are optional, "not recorded" is a line in the breakdown rather than a
  sleep quietly dropped from it, and the position is reported without comment —
  no warning, no colour-coding, no ordering that grades it.

## The record you hand to a doctor

Shipped, on top of those same per-day figures: a **PDF report** and an **`.xlsx`
workbook**, made over a range the parent picks, from the share action on the
baby's own page. The **why** is in
[BDR-12](docs/decisions/0012-a-record-for-the-doctor.md) (what it says, and what
it refuses to say) and [ADR-0013](docs/adr/0013-writing-the-pdf-and-the-workbook-by-hand.md)
(both formats written by hand, no dependency). Four things a change can quietly
undo:

- **The document interprets nothing.** No threshold, no flag, no colour meaning
  "low", no sentence that reads as an assessment. A screen that over-reads a
  centile is a bad moment; a printed page that does gets photocopied.
- **The WHO reference choice is never stored.** The export offers both / girls /
  boys and forgets it immediately — the other switches persist, this one resets.
  Remembering it would be keeping a baby's sex on the device by the back door,
  which is the field BDR-0008 decided not to have.
- **`ReportContent` is assembled once and rendered twice.** The PDF and the
  workbook are two renderings of one object, so the document and the spreadsheet
  cannot disagree. Add a figure there, not in a renderer.
- **The workbook's column names are English and stay English.** They are a data
  schema, not prose: a pivot table written against `bottle_ml` has to keep
  working when the phone changes language. The PDF is translated like everything
  else.
- **The export can be locked with a password, and only with AES-256**
  ([BDR-13](docs/decisions/0013-protecting-the-file-that-leaves.md)). Legacy
  ZipCrypto is the one Windows opens by double-click and is broken against
  known plaintext — which our archive supplies, since a PDF and an `.xlsx` both
  start with fixed bytes. It is not a fallback and not an option. The password
  is never stored, and `EncryptedZipTest` checks the key derivation against the
  RFC 6070 vectors and a whole entry against a vector from an outside
  implementation; if you touch that file, those are what stop a silent change
  from producing an archive nobody can open.

## Asking for support

Settings ends with two donation links (GitHub Sponsors, Buy Me a Coffee). The
rules are in [BDR-11](docs/decisions/0011-donations-are-a-link-out-and-buy-nothing.md),
and two of them are the whole point:

- **A donation buys nothing.** No feature gated, no limit lifted, no badge, no
  ad removed. The moment one unlocks anything it becomes an in-app purchase and
  Google Play requires Play Billing rather than a link out — so this is a
  policy line, not a preference.
- **It stays a link.** `ACTION_VIEW` hands the URL to the browser; fetching
  anything in-app (a supporter count, a balance, a payment page) would need the
  `INTERNET` permission and spend the privacy claim above. `SupportLinksTest`
  asserts the permission's absence so it breaks CI, not trust.

One quiet row at the bottom of Settings, and nowhere else — no prompt, no
dialog, no dashboard card. `PRIVACY.md` covers what a tap actually exposes.

## Historical — how it was built

Delivered in the order below; kept only as a map of which PR introduced what.

- **Phase 0 — make the data mergeable** (#64). `uid` / `updatedAt` / `deletedAt`
  on the synced entities, Room migration 13 → 14 with a UUID backfill, soft
  deletes across every DAO, device id, tombstone compaction.
- **Phase 1 — exchange a replica file by hand.** The engine (#65), then
  everything the user can see (#66).
- **Households — more than two phones** (#67). The merge already worked for any
  number of devices, so this was wording, a list of the phones heard from, and
  removal by rotating the shared secret.
- **Phase 2 — automatic exchange when the phones are near each other.** What CI
  can prove — beacon, session protocol, discovery policy (#69), then the radio
  and the screen.
