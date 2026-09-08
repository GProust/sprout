# Privacy Policy — Sprout

_Last updated: 2026-09-08 — see [Change history](#change-history) at the end._

Sprout is a newborn and postpartum tracker for Android. This policy explains
what the app does — and does not do — with your information.

## The short version

**Sprout collects nothing.** All data you enter stays on your device.

## What data Sprout stores

Everything you log — feeding, sleep, diapers, growth measurements, the
mother's postpartum check-ins, and your profile details — is saved in a
private database (Room/SQLite) **on your device only**.

- There are **no user accounts** and no sign-in.
- There is **no cloud sync** and **no server** operated by us.
- The app makes **no network requests** to send your data anywhere.
- We do **not** use analytics, advertising, crash-reporting, or tracking SDKs.
- We do **not** collect, sell, or share any personal or health information.

## Where your data lives

Your data is stored in the app's private storage sandbox. It is removed when
you uninstall the app or clear the app's data.

If you have enabled Android's system backup ("Back up to Google Drive") on
your device, Android may include app data in that encrypted backup according
to **your own Google account settings** — this is a feature of the Android
operating system, not something Sprout sends or controls. The same applies to
the copy Android makes when you set up a new phone from your old one.

Sprout tells Android what to include, so that moving to a new phone brings your
record and your settings with you. One thing is deliberately left out: the
identifier this particular phone uses when sharing with your household — it
describes the handset rather than you, and copying it would leave two phones
claiming to be the same one.

**Your household's sharing key never travels.** It is locked to the phone's
own secure hardware, so the copy in a backup cannot be opened anywhere else.
A new phone therefore starts unpaired: to share again, ask a phone that is
already in your household for a fresh invitation.

## Sharing a record with a doctor

Sprout can turn what you have logged over a stretch of days into two files: a
**PDF report** to hand over or print, and a **spreadsheet** with one sheet per
kind of entry. You start it from the share button on your baby's page, and you
choose the period it covers.

- **The files are made on your phone.** Sprout has no internet permission and
  cannot send them anywhere. It writes the file and hands it to Android's share
  sheet — the same one every app uses — and *you* choose where it goes.
- **What is in them** is your baby's record: feeding, sleep, nappies, growth
  measurements and the treatments you set up, over the period you picked. The
  free text you type on entries (the "notes") is **left out unless you switch it
  on**.
- **Your own check-ins and your pumping log are never included.** They belong to
  you rather than to your baby, and a report for a paediatric appointment is not
  where they go.
- **Sprout still never asks your baby's sex.** Growth centiles are printed
  against both WHO references as a span. You can choose to read them against one
  reference instead, and that choice is used for that one file and then
  forgotten — it is not saved, not synced, and not remembered the next time.
- **Once you send it, it is out of our hands.** The moment the file reaches a
  messaging app, a mail client or a cloud drive, it lives under *that* service's
  policy, not this one. A health record is worth a thought about which app you
  hand it to.
- **The file is not kept.** It waits in the app's private cache until you choose
  where to send it, and the next report you make replaces it.
- **You can lock it with a password.** Switch on "Protect with a password" and
  both files go out as a single zip encrypted with AES-256, using a password you
  type. Sprout keeps no copy of that password — not in your settings, not
  anywhere — so nobody, us included, can open the file for you if you forget it.
  Give the password to the doctor in person or by phone rather than in the same
  message as the file, and bear in mind what it does not cover: once they open
  it, the decrypted copy is theirs and lives under their own arrangements.

## Children's data

Sprout is a tool used by parents/caregivers to record information about an
infant. That information never leaves the device, is never transmitted to us,
and is not used for any purpose other than displaying it back to you in the
app.

## Sharing with the other people caring for your baby

Sprout can share one baby's record between the phones of the people looking
after them — usually two parents, sometimes a grandparent as well. It works
**without any server and without any cloud storage**, ours or anyone else's:

- The phones exchange a file **directly**, either by you sending it through an
  app you already use (a messaging app, Quick Share, Bluetooth), or — if you
  turn it on — automatically over Bluetooth when they are a few metres apart.
- What travels is **encrypted** with a key that exists only on the phones you
  have paired. Nobody else can read it, including us and including whichever
  app carried the file.
- **Your own check-ins are never shared.** Mood, bleeding, recovery and breast
  comfort stay on your phone, and there is no setting that changes that.
- The expressed-milk log **can** be shared with your household, and there is a
  switch to keep it to yourself.

Nothing is uploaded anywhere at any point. Sharing is entirely optional and the
app works fully with it switched off.

## Supporting the app

Settings has a **Support Sprout** section with two links — GitHub Sponsors and
Buy Me a Coffee. They are optional in every sense: nothing in the app is behind
them, no feature is unlocked by using them, and the app never asks you about
them anywhere else.

Be aware of what tapping one does:

- It **opens the page in your browser**, which is a different app. Sprout itself
  fetches nothing — it still has no internet permission and still cannot open a
  network connection.
- From that point **you are on GitHub's or Buy Me a Coffee's website**, and
  their privacy policies apply, not this one. Like any website you visit, they
  will see your IP address and whatever your browser tells them.
- If you go on to donate, **that payment is between you and them.** We never see
  your card details, and none of your Sprout data — not one feed, not one
  check-in — is involved, sent, or attached in any way.

Not tapping them changes nothing about how the app works.

## Permissions

Sprout requests **no internet permission**. The app cannot open a network
connection at all — that is not a promise about our intentions, it is something
you can verify for yourself by looking at the app's manifest.

If — and only if — you switch on automatic sharing with nearby phones, Sprout
asks for permission to **find and connect to nearby devices** (Bluetooth). It is
declared `neverForLocation`, which tells Android the app must not use it to work
out where you are, and Sprout never asks for the location permission at all. The
scan is used solely to spot a phone from your own household, lasts a few seconds
when you open the app, and never runs in the background.

Sprout also asks to show notifications, if you turn on reminders.

## Changes to this policy

If this policy changes, the updated version will be published in the app's
source repository with a new "Last updated" date.

## Change history

Every change to this policy, with what actually changed. The repository keeps
the full history, but a policy you have to run `git log` to understand is not
one you can check.

### 2026-09-08 (2)

- Added **password protection for the exported files**. Both files can now go
  out as one zip encrypted with AES-256, under a password you type. The section
  above says what that protects and what it does not, and that Sprout keeps no
  copy of the password and cannot recover it. Nothing else changed: the files
  are still made on your phone, still go nowhere until you choose where, and the
  app still has no internet permission.

### 2026-09-08

- Added the section on **sharing a record with a doctor**. Sprout can now make a
  PDF report and a spreadsheet from what you have logged, over a period you
  choose. Both files are written on your phone and go nowhere until you pick
  where to send them — the app still has no internet permission and still makes
  no network request of its own. The section says what is in the files, that
  your notes are left out unless you switch them on, that your own check-ins and
  pumping log are never included, and that once you send a file it lives under
  the policy of whichever app you sent it with.
- Restated, because a printed page travels further than a screen: choosing to
  read the growth curves against one WHO reference is used for that one file and
  then forgotten. Sprout still never asks your baby's sex and still stores none.

### 2026-09-06

- **The widget diagnostic log is gone**, so it is no longer named as something
  a backup leaves behind — there is nothing left to leave behind. The
  Troubleshooting screen that read it has been removed from Settings along with
  the log itself, which means one less file written on your phone. What a
  backup carries is otherwise unchanged, and the identifier this phone uses
  inside your household is still held back.

### 2026-09-05

- **Said what a backup and a new phone actually carry.** The section on where
  your data lives now spells out that your record and settings are included,
  that this phone's sharing identifier and the widget diagnostic log are not,
  and that the household's sharing key cannot be opened on any other phone — so
  a new phone starts unpaired and needs a fresh invitation. Nothing about what
  Sprout collects or sends has changed; this is the same behaviour, described.
- Added the section on **supporting the app**. Settings now offers two donation
  links, and while Sprout still fetches nothing itself, tapping one hands the
  page to your browser and puts you on someone else's website under their
  policy — so this policy says that outright rather than leaving it implied.
  Nothing about what Sprout stores, shares or transmits has changed, and the
  internet permission is still absent.

### 2026-08-16

- Added the section on **sharing between the phones of a household**: what
  travels, that it is encrypted, and that the parent's own check-ins never
  travel at all.
- **Corrected the permissions section.** It previously said Sprout requests "no
  sensitive runtime permissions". That stopped being true when sharing over
  Bluetooth was added: turning that feature on now asks for permission to find
  nearby devices. The statement about the internet permission is unchanged and
  still verifiable in the app's manifest.

### 2026-06-27

- First version.

## Contact

Questions about privacy? Contact the developer:
**Guillaume Proust — guillaume.proust13@gmail.com**
