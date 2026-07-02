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

What stays manual: record the changes in [`CHANGELOG.md`](../CHANGELOG.md) and
in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

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
> Because Play gets an **AAB**, the R8 mapping file and the symbol tables of
> dependencies' native libraries (`ndk.debugSymbolLevel = "SYMBOL_TABLE"`) are
> embedded in the bundle automatically — Play uses them to deobfuscate crash
> reports and ANRs. Nothing extra to upload.

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

## 6. GitHub Releases (direct APK download)

Because Sprout is GPLv3, the source is public and the signed APK is also
published on GitHub for anyone who prefers to sideload.

The [release workflow](../.github/workflows/release.yml) is **manual**. In the
GitHub UI go to **Actions → Release → Run workflow**, and enter the version to
publish (e.g. `1.3.0`). It bumps `versionName`/`versionCode` and commits if
needed (see §1), builds the signed `.aab`/`.apk` from that commit, uploads
them as run artifacts, and creates a `1.3.0` tag plus a GitHub Release with
the APK attached. Leave the version empty to only build the artifacts.

> **Trademark note:** the GPL covers the *code*, not the *name and icon* (see
> [`TRADEMARK.md`](../TRADEMARK.md)). Anyone publishing a **fork** to a store
> must rename and re-brand it; only the official build should ship as "Sprout".

---

## 7. Release checklist

- [ ] New version picked (the Release workflow bumps `versionCode`/`versionName` itself)
- [ ] `CHANGELOG.md` + `changelogs/<versionCode>.txt` updated
- [ ] `./gradlew lintRelease testReleaseUnitTest` pass
- [ ] Store listing text + screenshots current
- [ ] Privacy policy URL reachable; Data safety form matches it
- [ ] Release workflow run (Actions → Release) with the new version — builds the signed `.aab`, tags, publishes the GitHub Release
- [ ] Installed and smoke-tested the release APK on a device
- [ ] Production rollout started in Play Console
