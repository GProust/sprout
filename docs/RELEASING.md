# Releasing & Publishing Sprout

This guide is the end-to-end process for shipping Sprout to users. It covers
**Google Play** (the main Android "app store") and **open distribution**
routes (GitHub Releases and F-Droid) that fit Sprout's GPLv3 license well.

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

For each release: bump `versionName` (e.g. `1.1`) and **always** increment
`versionCode` by 1 (Play rejects re-uploads with an existing code). Record the
change in [`CHANGELOG.md`](../CHANGELOG.md) and in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

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

> **Optional — shrink the app with R8.** Minification is currently **off** in
> `app/build.gradle.kts` so an untested R8 pass can't ship a broken build. To
> enable it: set `isMinifyEnabled = true` (and optionally
> `isShrinkResources = true`), then **install and fully exercise a release
> build on a device** — open every screen, add/edit/delete records — to confirm
> Room and Compose still behave before uploading. Add keep rules to
> `app/proguard-rules.pro` if anything misbehaves.

---

## 4. Prepare the store listing

Listing text lives in `fastlane/metadata/android/en-US/` so it is version-
controlled and reusable:

- `title.txt` — app name (≤ 30 chars)
- `short_description.txt` — ≤ 80 chars
- `full_description.txt` — ≤ 4000 chars
- `changelogs/<versionCode>.txt` — "What's new" for that release

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

---

## 6. Open distribution (fits the GPLv3 license)

Because Sprout is GPLv3, the source is public and you can also distribute the
binary openly:

### GitHub Releases
Tag a version and let CI build and attach the signed APK (see
[`.github/workflows/release.yml`](../.github/workflows/release.yml)):

```bash
git tag v1.0 && git push origin v1.0
```

### F-Droid (free, FOSS-only store)
F-Droid is a natural home for a GPL app, and Sprout already meets F-Droid's
inclusion requirements:

- ✅ **FOSS license** — GPL-3.0-only.
- ✅ **No proprietary dependencies** — only AndroidX / Jetpack Compose / Room /
  Kotlin / KSP, all Apache-2.0. No Google Play Services, Firebase or ads.
- ✅ **No anti-features** — the app declares **no permissions** (no `INTERNET`),
  has no trackers, and is fully offline.
- ✅ **Listing metadata** — `fastlane/metadata/android/en-US/` (title,
  descriptions, changelogs, and `images/phoneScreenshots/`).
- ✅ **Tag-based versioning** — releases are tagged `vX.Y` matching `versionName`.

**To submit (one-time):**

1. Tag the release so F-Droid has a commit to build: `git tag v1.0 && git push origin v1.0`.
2. Fork <https://gitlab.com/fdroid/fdroiddata>.
3. Copy [`docs/fdroid/com.gproust.sprout.yml`](fdroid/com.gproust.sprout.yml)
   into the fork as `metadata/com.gproust.sprout.yml`.
4. Test the recipe locally with `fdroid build -v -l com.gproust.sprout`
   (via `fdroidserver`), then open a merge request.

F-Droid builds from source on its own server and **signs with its own key**, so
none of the `SPROUT_*` signing secrets are involved — the recipe is all that's
needed. For each new release, bump `versionCode`/`versionName`, tag it, and
F-Droid auto-detects the new tag (`UpdateCheckMode: Tags`).

> **Trademark note:** the GPL covers the *code*, not the *name and icon* (see
> [`TRADEMARK.md`](../TRADEMARK.md)). Anyone publishing a **fork** to a store
> must rename and re-brand it; only the official build should ship as "Sprout".

---

## 7. Release checklist

- [ ] `versionCode` incremented, `versionName` bumped
- [ ] `CHANGELOG.md` + `changelogs/<versionCode>.txt` updated
- [ ] `./gradlew lintRelease testReleaseUnitTest` pass
- [ ] `./gradlew bundleRelease` produces a **signed** `.aab`
- [ ] Installed and smoke-tested a release build on a device
- [ ] Store listing text + screenshots current
- [ ] Privacy policy URL reachable; Data safety form matches it
- [ ] Git tag pushed (`vX.Y`)
- [ ] Production rollout started in Play Console
