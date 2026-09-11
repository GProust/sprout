# Releasing & Publishing Sprout

This guide is the end-to-end process for shipping Sprout to users. It covers
**Google Play** (the main Android "app store") and **GitHub Releases** for
direct APK downloads.

> **Note on the iOS App Store:** this guide covers **Google Play** only. The iOS
> app ([ADR-0015](adr/0015-native-ios-in-this-repository.md)) is not shippable
> yet — see [§8](#8-ios-what-is-not-needed-yet-and-what-will-be) for what it
> already builds without, and what publishing it will require.

---

## 0. One-time prerequisites

| What | Why | Cost |
|------|-----|------|
| **Google Play Developer account** | Required to publish on Google Play | **$25 one-time** |
| **JDK 17 + Android SDK** (via Android Studio) | Build the release artifact | Free |
| **An upload keystore** | Sign the app (see §2) | Free |
| **A public Privacy Policy URL** | Play requires one — we ship [`PRIVACY.md`](../PRIVACY.md) | Free |

Sign up at <https://play.google.com/console>. Account verification (identity +
sometimes a D-U-N-S/address check) can take a few days, so start this early.

---

## 1. Pick the version

Versioning lives in [`android/app/build.gradle.kts`](../android/app/build.gradle.kts):

```kotlin
versionCode = 1      // integer, MUST increase with every Play upload
versionName = "1.0"  // human-readable, shown to users
```

You normally don't edit these by hand: the [release workflow](../.github/workflows/release.yml)
takes a **version** input (e.g. `1.3.0`) and, when it differs from the current
`versionName`, commits a bump that sets `versionName` to it and increments
`versionCode` by 1 (Play rejects re-uploads with an existing code) before
building.

The same run also finishes the paperwork, in that one commit:

- [`CHANGELOG.md`](../CHANGELOG.md)'s `## [Unreleased]` heading becomes
  `## [<version>] — <date>`. So the only manual part is keeping entries under
  `[Unreleased]` as pull requests land — the release dates them.
- The "What's new" text typed into the run form is written to
  `android/fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` for each
  of the 7 listed languages (§4).

---

## 2. Create the signing keystore (one-time)

Every Play app must be signed. Generate an **upload key** once and keep it
safe forever — losing it complicates future updates.

```bash
keytool -genkeypair -v \
  -keystore sprout-upload.jks \
  -alias sprout \
  -keyalg RSA -keysize 2048 -validity 10000
```

Store the keystore **outside** the repo (it is git-ignored anyway). Then
create `keystore.properties` in the project root — also git-ignored — using
[`keystore.properties.example`](../keystore.properties.example) as a template:

```properties
storeFile=/absolute/path/to/sprout-upload.jks
storePassword=********
keyAlias=sprout
keyPassword=********
```

The Gradle build reads this file automatically (or the matching `SPROUT_*`
environment variables in CI). When neither is present, the release build is
produced **unsigned** — which is why local debug work and CI keep working.

> **Recommended:** also enable **Play App Signing** in the Console (default for
> new apps). Google then manages the final app-signing key; your local key is
> only the *upload* key, and Google can help you reset it if it's ever lost.

### Back up the keystore

Keep `sprout-upload.jks` and its passwords in a password manager / secure
backup. Treat them like the keys to your house.

---

## 3. Build the release artifact

> Run every Gradle command in this section from **`android/`**.

Google Play requires an **Android App Bundle (`.aab`)**:

```bash
./gradlew bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab
```

For sideloading / GitHub Releases, also build a signed APK:

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Before publishing, sanity-check the build:

```bash
./gradlew lintRelease testReleaseUnitTest
```

> **R8 minification is ON** for release builds (`isMinifyEnabled` +
> `isShrinkResources` in `android/app/build.gradle.kts`), so **exercise the release
> build on a device before promoting to production** — open every screen,
> add/edit/delete records — to confirm Room and Compose still behave. The
> internal testing track is the natural place for this. Add keep rules to
> `app/proguard-rules.pro` if anything misbehaves.
>
> Because Play gets an **AAB**, the R8 mapping file is embedded in the bundle
> automatically — Play uses it to deobfuscate crash reports and ANRs. Nothing
> extra to upload. Native debug symbols (`ndk.debugSymbolLevel = "SYMBOL_TABLE"`)
> are configured the same way but end up empty; see
> [Play Console warnings you can ignore](#play-console-warnings-you-can-ignore).

---

## 4. Prepare the store listing

Listing text lives in `android/fastlane/metadata/android/en-US/` so it is version-
controlled and reusable:

- `title.txt` — app name (≤ 30 chars)
- `short_description.txt` — ≤ 80 chars
- `full_description.txt` — ≤ 4000 chars
- `changelogs/<versionCode>.txt` — "What's new" for that release (≤ 500 chars,
  plain text — Play renders no markdown). Written by the release workflow from
  what you type into its form; the same layout exists per language under
  `android/fastlane/metadata/android/<locale>/`.

You also need **graphic assets** (uploaded in the Console, not stored here):

| Asset | Spec |
|-------|------|
| App icon | 512×512 PNG (already in `android/app/src/main/res/mipmap-*`) |
| Feature graphic | 1024×500 PNG/JPG |
| Phone screenshots | 2–8 images (use the [`screenshots/`](../screenshots) the CI pipeline generates) |

Google Play also requires you to complete, in the Console:

- **Data safety** form → declare **no data collected / no data shared**
  (matches [`PRIVACY.md`](../PRIVACY.md)).
- **Privacy policy URL** → host `PRIVACY.md` publicly. Easiest options:
  the raw GitHub URL, or enable **GitHub Pages** and link the rendered page.
- **Content rating** questionnaire.
- **Target audience / ads** declaration (Sprout has **no ads**).

---

## 5. Publish on Google Play

1. **Play Console → Create app** — name "Sprout", language, app (not game),
   free, accept declarations.
2. Complete the **App content** tasks (privacy policy, data safety, ads,
   content rating, target audience).
3. **Testing first (strongly recommended):** create an **Internal testing**
   release, upload `app-release.aab`, add your own email as a tester, install
   via the opt-in link, and verify on a real device.
4. **Production → Create new release** → upload the `.aab` → paste release
   notes → **Review** → **Start rollout to production**.
5. First-time review can take from a few hours to several days. After approval
   the app is live on Google Play.

For later updates: bump `versionCode`/`versionName` (§1), rebuild (§3), and
upload a new production release.

### Play Console warnings you can ignore

Every upload gets this one on the release's details page:

> This App Bundle contains native code, and you've not uploaded debug symbols.
> We recommend you upload a symbol file to make your crashes and ANRs easier to
> analyze and debug.

Nothing is broken and there is nothing to fix. Sprout has no native code of its
own — it is Kotlin all the way down. The only `.so` in the bundle is
`libandroidx.graphics.path.so` (one per ABI), a prebuilt that Compose drags in
through `androidx.graphics:graphics-path`, and AndroidX publishes it already
stripped: the shipped library has a `.dynsym` and no symbol table or debug
sections at all. `ndk.debugSymbolLevel` (§3) therefore has nothing to extract,
which is what the release build log is saying when it prints

```
> Task :app:extractReleaseNativeSymbolTables
> Task :app:mergeReleaseNativeDebugMetadata NO-SOURCE
```

and why the bundle carries no `BUNDLE-METADATA/…/debugsymbols` entry for Play
to find. Usable symbols for that library only ever existed on the machine where
Google built it; we cannot generate them.

None of this touches crash reports for our own code — those frames are
Kotlin/Java and the embedded R8 mapping file already deobfuscates them.

If you want the banner gone anyway, Play takes a symbol file per bundle under
**Release → App bundle explorer → Downloads → Native debug symbols**. Uploading
a zip of the (stripped) `.so` files satisfies the check without adding any real
symbol information, so it buys nothing but silence. The build setting stays in
place regardless: the day Sprout ships native code of its own, its symbols will
be packaged automatically.

---

## 6. GitHub Releases (direct APK download)

Because Sprout is GPLv3, the source is public and the signed APK is also
published on GitHub for anyone who prefers to sideload.

The [release workflow](../.github/workflows/release.yml) is **manual**. In the
GitHub UI go to **Actions → Release → Run workflow** and fill in the form:

| Field | What to put in it |
|-------|-------------------|
| **Version** | e.g. `1.3.0`. Leave empty to only build the artifacts — no commit, no tag, no release. |
| **Play release notes, English** | The "What's new" users will read on Play (≤ 500 chars). Required when releasing a version. |
| **…the six other languages** | Same, per language. Any left empty falls back to the English text, with a warning in the run log. |

It then prepares one commit — version bump, dated changelog section, and the
per-language release-note files (§1) — pushes it, builds the signed
`.aab`/`.apk` from it, uploads them as run artifacts, and creates a `1.3.0`
tag plus a GitHub Release with the APK attached.

Bad input is rejected before anything is pushed: a malformed version, missing
English notes, or notes over Play's 500-character limit fail the run with the
branch untouched. Re-running with a version that is already current re-writes
the notes without bumping again.

> **Trademark note:** the GPL covers the *code*, not the *name and icon* (see
> [`TRADEMARK.md`](../TRADEMARK.md)). Anyone publishing a **fork** to a store
> must rename and re-brand it; only the official build should ship as "Sprout".

---

## 7. Release checklist

- [ ] New version picked (the Release workflow bumps `versionCode`/`versionName` itself)
- [ ] `CHANGELOG.md` has the entries under `[Unreleased]` (the workflow dates them)
- [ ] "What's new" written for the 7 languages, ready to paste into the run form
- [ ] `./gradlew lintRelease testReleaseUnitTest` pass
- [ ] Store listing text + screenshots current
- [ ] Privacy policy URL reachable; Data safety form matches it
- [ ] Release workflow run (Actions → Release) with the version + release notes — commits the bump, changelog and notes, builds the signed `.aab`, tags, publishes the GitHub Release
- [ ] Installed and smoke-tested the release APK on a device
- [ ] Production rollout started in Play Console

---

## 8. iOS: what is *not* needed yet, and what will be

The iOS app ([ADR-0015](adr/0015-native-ios-in-this-repository.md)) is built by
CI on every push, and none of it needs an Apple account, a certificate or a Mac.
That is deliberate — it keeps CI the build verifier on this side too
([ADR-0006](adr/0006-ci-as-build-verifier-and-screenshots.md)).

### What CI does today, with no credentials at all

| Job | Runner | What it proves |
|-----|--------|----------------|
| `Specification & strings` | `ubuntu-latest` | `spec/vectors/` is exactly what `generate.mjs` produces, the string catalog is exactly what Android's resources produce, every key the app uses exists, and nothing in the app can open a connection |
| `SproutKit` | `macos-15` | the wire formats and the data layer build **and match the vectors** |
| `App` | `macos-15` | the project spec, entitlements and `Info.plist` are coherent, and the app and its UI tests compile for the simulator |
| `Screenshots` | `macos-15` | every screen renders — and the images land in [`ios/screenshots/`](../ios/screenshots) |

A simulator build needs no signing identity, which is why `App` passes
`CODE_SIGNING_ALLOWED=NO`. macOS runners are **free on this repository** because
it is public; on a private repo they bill at ten times the Linux rate, which is
what the `paths:` filters in `ci.yml` and `ios.yml` exist to keep in check.

### What CI cannot do, ever

- **Run on a real radio.** The Bluetooth exchange
  ([ADR-0016](adr/0016-a-transport-both-platforms-can-speak.md)) is testable over
  a pipe and not otherwise. Two physical phones are the only proof.
- **Produce anything installable.** Not a TestFlight build, not an `.ipa`, not
  something you can put on your own phone.
- **Capture the *listing* screenshots.** The review set below is captured on
  whatever simulator the runner happens to ship. App Store Connect wants
  specific device sizes, which means naming those simulators and a job of its
  own — the counterpart of `screenshots.yml`'s `tablet-7` / `tablet-10` runs.
  Not built, because there is nothing to upload it to yet.

### The review screenshots, in the repository

`ios/screenshots/` holds one PNG per screen, committed by the `Screenshots`
job — the same arrangement as [`android/screenshots/`](../android/screenshots),
and for the same reason: a pull request is easier to review when the diff shows
what changed on screen, and nobody on this project has a Mac to look at the app
on.

The two jobs share their rules, deliberately:

- **Only visibly-changed images are promoted.** A capture is never
  byte-identical to the last one — text antialiasing and the renderer vary
  between runs — so committing on any byte difference means a churn commit on
  nearly every pull request. The threshold is the same 0.05% of pixels, and it
  should not be raised to swallow a residue: a changed word in a list row is
  itself well under 1% of the screen.
- **The commit carries `[skip ci]`**, or the run would capture, commit, and
  capture again.
- **Two clocks are frozen, not one.** Android pins the emulator's, which covers
  both. iOS needs both pinned separately: `ScreenshotSeed.now` for the app,
  because every "now" it renders goes through `Clock`; and
  `simctl status_bar override` for the status bar, which is in every capture and
  ticks on its own. The first run showed the cost of missing the second — one
  screenshot read 5:47 and the next 5:48, so the images differed from each other
  *within* a single run. At roughly 0.1% of the frame that clears the promote
  threshold, and all seventeen would be recommitted on every pull request.
- **`ios/screenshots/` and `ios/fastlane/` are excluded from the workflow's
  `paths:`.** Neither can change what the app renders, and a macOS runner
  proving that a PNG is still a PNG is fifteen minutes for nothing.

Names come out of the `.xcresult` bundle as UUIDs, so
[`ios/tools/name_screenshots.py`](../ios/tools/name_screenshots.py) reads the
manifest and turns them back into `01-home.png`. It fails loudly when it names
nothing — a capture that quietly committed an empty set would read as "the
screens are fine" when nobody looked.

### The release notes, in the repository

`ios/fastlane/metadata/<locale>/changelogs/<build>.txt`, one file per build,
exactly as [`android/fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`](../android/fastlane/metadata/android)
does. Same seven languages, same voice, written for the person reading the
store listing rather than the person reading the diff.

Two differences, both Apple's rather than ours:

- **The locale codes are not Google Play's.** App Store Connect has no region on
  Italian or Polish, so `it-IT` and `pl-PL` become `it` and `pl`. The other five
  match.
- **`deliver` reads one `release_notes.txt` per locale, not a directory.** The
  numbered files are the history — worth keeping, and the shape the other app
  already uses — so the release step, when there is one, copies the highest
  build's file into place rather than the notes being edited in situ and lost.

The rest of the listing — title, subtitle, description, keywords — is
**deliberately not duplicated here**. Android's already exists in all seven
languages, and a second copy of the same marketing text is a second thing to
keep in step; the App Store's field limits differ enough (a 30-character
subtitle against Play's 80-character short description) that it wants
generating from one source rather than copying, the way the string catalog
already is. Not needed until there is a listing to fill in.

### What publishing will need, when the screens land

None of this is required to keep developing, and none of it should be bought
before the Phase 0 spike says the app is worth shipping.

| What | Why | Cost |
|------|-----|------|
| **A Mac** | Xcode runs nowhere else. Apple Silicon; Intel Macs stop getting macOS | second-hand from ~CHF 350 |
| **Apple Developer Program** | required to sign anything that runs on a device | **$99/year** |
| **A distribution certificate + provisioning profile** | signs the build | free with the above |
| **An App Store Connect API key** | lets CI upload to TestFlight without a human | free with the above |
| **App Privacy answers** in App Store Connect | Apple requires them | free — every answer is *Data Not Collected* |
| **An export-compliance answer** | the export is AES-256 (BDR-13) | free — "exempt, standard cryptography" |

Those two are the least work here and the most work for most apps: every answer
on the App Privacy card is *Data Not Collected*, because there is no network call
to collect anything with.

### Two things the upload refuses without, and both are in the repository

- **`ios/Sprout/Sources/PrivacyInfo.xcprivacy`.** Apple's privacy manifest,
  required since 2024. Tracking and collection are empty because they are empty.
  Two "required reason" APIs are declared: `UserDefaults` (the reminder switches
  and the household id, reason `CA92.1`) and file timestamps (SQLite stats the
  database it owns, reason `C617.1`). If a later App Store Connect check names a
  category we have not declared, it says exactly which — one entry each, and
  cheaper than guessing now.
- **The app icon**, at `Sources/Assets.xcassets/AppIcon.appiconset/`. It is
  **generated from Android's launcher vector** by
  [`ios/tools/make_app_icon.py`](../ios/tools/make_app_icon.py), for the same
  reason the string catalog is generated from Android's resources: one product,
  one icon, and no second copy to drift. CI re-renders and compares on every
  push. 1024×1024 with no alpha channel, because an icon with transparency is
  rejected at upload.

When those exist, they become repository secrets alongside the Android ones
already listed in [`release.yml`](../.github/workflows/release.yml) — an
`APP_STORE_CONNECT_KEY_ID`, `_ISSUER_ID` and `_PRIVATE_KEY` — and a fourth job
archives and uploads. Not before.
