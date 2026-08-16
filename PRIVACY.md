# Privacy Policy — Sprout

_Last updated: 2026-08-16 — see [Change history](#change-history) at the end._

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
operating system, not something Sprout sends or controls.

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
