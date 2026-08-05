# Releasing & Publishing Sprout

This guide is the end-to-end process for shipping Sprout to users. It covers
**Google Play** (the main Android "app store") and **GitHub Releases** for
direct APK downloads.

> **Note on the iOS App Store:** Sprout is a native Android app (Kotlin +
> Jetpack Compose). It cannot be published to Apple's App Store without being
> rewritten for iOS. "App store" below therefore means **Google Play**.

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

Versioning lives in [`app/build.gradle.kts`](../app/build.gradle.kts):

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
  `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` for each
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
> `isShrinkResources` in `app/build.gradle.kts`), so **exercise the release
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

Listing text lives in `fastlane/metadata/android/en-US/` so it is version-
controlled and reusable:

- `title.txt` — app name (≤ 30 chars)
- `short_description.txt` — ≤ 80 chars
- `full_description.txt` — ≤ 4000 chars
- `changelogs/<versionCode>.txt` — "What's new" for that release (≤ 500 chars,
  plain text — Play renders no markdown). Written by the release workflow from
  what you type into its form; the same layout exists per language under
  `fastlane/metadata/android/<locale>/`.

You also need **graphic assets** (uploaded in the Console, not stored here):

| Asset | Spec |
|-------|------|
| App icon | 512×512 PNG (already in `app/src/main/res/mipmap-*`) |
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
