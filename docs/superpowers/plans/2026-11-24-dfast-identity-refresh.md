# DFast Identity Refresh & 1.0.1 Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the last YTDLnis branding from the running app, give "DFast" a real wordmark, make the project's own repository and Telegram the only links, ship a professional README, and publish version 1.0.1 through GitHub Actions.

**Architecture:** A single Kotlin helper (`ThemeUtil.getStyledAppName`) feeds both the home toolbar and the drawer header, so the wordmark is implemented once as a vector drawable and returned as an `ImageSpan`. Link ownership is enforced in resources/preferences and in the few Kotlin constants that open URLs. Documentation lives in `README.md`, `SECURITY.md`, `CONTRIBUTING.MD`, `CHANGELOG.md`, plus fastlane metadata. The existing GitHub Actions workflow (`build.yml`, repo root) builds, signs, verifies and publishes the release; the version is bumped in `app/build.gradle`.

**Tech Stack:** Android (Kotlin), AndroidX/Material 3, Gradle 8 + AGP, GitHub Actions, yt-dlp.

**Spec:** User request 2026-11-24 (identity refresh, link ownership, README, release) plus the earlier rebrand plan `docs/superpowers/plans/2026-11-24-dfast-rebrand.md`.

## Global Constraints

- Only these external links may ship in the app and repo docs: `https://github.com/MenuM7D/dfast` and `https://t.me/m7d_devvv`.
- Brand palette: Signal Coral `#FF5A36`, Deep Ink `#16121F`, night text `#F5F2FA`.
- No local Gradle/JDK/Android SDK builds (OOM in this VM); verification happens in GitHub Actions.
- Keep the upstream runtime packages (`com.deniscerri.ytdl.*`, `io.github.deniscerri:ytdlnis_packages`) unchanged; they are binaries, not links.
- Keep the GPLv3 attribution to the original author.
- Version: `1.0.1`, versionCode `1000100`, release tag `v1.0.1`.
- The Android project lives in the `dfast/` directory of the repository; CI runs from there.

## Review Focus

- **Wordmark rendering:** an `ImageSpan` inside a toolbar title must not clip or push the toolbar height; expected: a crisp 26 dp-high "DFast" with a coral D.
- **Dark theme:** the wordmark switches to the light-ink variant in night mode; expected: legible, no invisible text.
- **Stale links:** a tap on Telegram/Discord/About must never reach an upstream account; expected: only the project repo and `t.me/m7d_devvv`.
- **Version metadata:** the APK filename, versionName and release tag must agree (`DFast-1.0.1-…`, `v1.0.1`), otherwise in-app update checks mis-order releases.
- **README accuracy:** build/install instructions must match the real Gradle tasks and package `dfast.app`.

---

### Task 1: Wordmark asset and `ThemeUtil` integration

**Files:**
- Create: `app/src/main/res/drawable/ic_dfast_wordmark.xml`
- Create: `app/src/main/res/drawable-night/ic_dfast_wordmark.xml`
- Modify: `app/src/main/java/dfast/app/util/ThemeUtil.kt:154-159`
- Test: parse both XML files and compile check via GitHub Actions

**Interfaces:**
- Produces: `ThemeUtil.getStyledAppName(context: Context): Spanned` — unchanged signature, now returns the wordmark `ImageSpan`; consumed by `HomeFragment` (toolbar title) and `MainActivity` (drawer header title).

- [ ] **Step 1: Generate the wordmark outlines**

Build the two vector files from Inter Display Bold outlines ("D" in `#FF5A36`, "Fast" in ink), 26 dp high, `viewportWidth≈89.39`, `viewportHeight=26`.

- [ ] **Step 2: Write the failing check**

Run: `xmllint --noout app/src/main/res/drawable/ic_dfast_wordmark.xml app/src/main/res/drawable-night/ic_dfast_wordmark.xml`
Expected before creation: "No such file or directory"; after creation: exit 0.

- [ ] **Step 3: Replace `getStyledAppName`**

Return a `SpannableString` with an `ImageSpan` of `R.drawable.ic_dfast_wordmark`, content description `R.string.app_name`; drop the HTML "YTDL"/"nis" construction and unused imports.

- [ ] **Step 4: Verify no legacy string remains**

Run: `grep -rn "YTDL" app/src/main/java app/src/main/res | grep -v "yt-dlp" | grep -v ytdlp`
Expected: no match.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/drawable/ic_dfast_wordmark.xml app/src/main/res/drawable-night/ic_dfast_wordmark.xml app/src/main/java/dfast/app/util/ThemeUtil.kt
git commit -m "feat: draw the DFast wordmark in the home toolbar and drawer"
```

### Task 2: Own every link inside the app

**Files:**
- Modify: `app/src/main/res/xml/root_preferences.xml`
- Modify: `app/src/main/java/dfast/app/ui/more/settings/TranslatorsDialog.kt:29`
- Modify: `app/src/main/assets/yt_dlp_plugins/lyrics/yt_dlp_plugins/postprocessor/lyrics.py:13`
- Test: grep the whole project

**Interfaces:**
- Consumes: nothing.
- Produces: no behavior change beyond link destinations.

- [ ] **Step 1: Remove the Discord preference, retarget Telegram**

Delete the `discord` `Preference` block. Set the Telegram summary and intent data to `https://t.me/m7d_devvv`.

- [ ] **Step 2: Retarget the translators button**

Set `WEBLATE_URL` to `https://github.com/MenuM7D/dfast` so the dialog's only outbound link is the project repository.

- [ ] **Step 3: Fix the lyrics plugin user agent**

Change the `USER_AGENT` to `DFast (https://github.com/MenuM7D/dfast)`.

- [ ] **Step 4: Verify**

Run: `grep -rn "discord\|t\.me/ytdlnis\|hosted\.weblate\.org/projects/ytdlnis" app/src/main`
Expected: no matches.

- [ ] **Step 5: Commit**

### Task 3: Project documentation and metadata

**Files:**
- Rewrite: `README.md`
- Rewrite: `SECURITY.md`
- Rewrite: `CHANGELOG.md`
- Modify: `CONTRIBUTING.MD`
- Modify: `.github/ISSUE_TEMPLATE/config.yml`, `.github/ISSUE_TEMPLATE/bug_report.yaml`, `.github/FUNDING.yml`
- Delete: `README-ar.md`, `README-az.md`, `README-es.md`, `README-id.md`, `README-ja.md`, `README-pt.md`, `README-ro.md`, `README-ru.md`, `README-sq.md`, `README-tr.md`, `README-zh_CN.md`, stale fastlane changelogs

**Interfaces:**
- Produces: repository documentation whose only links are the project repo and Telegram.

- [ ] **Step 1: Write `README.md`** — hero with icon, name, tagline, release/Telegram/repo badges, features, install, build, package name, intents, license and fork attribution. No upstream badges, no Discord, no Weblate, no donate links.
- [ ] **Step 2: Rewrite `SECURITY.md`** — DFast privacy policy, report via GitHub issues or Telegram.
- [ ] **Step 3: Replace `CHANGELOG.md`** with DFast 1.0.0 / 1.0.1 notes.
- [ ] **Step 4: Point issue templates and CONTRIBUTING at our repository/Telegram**, drop FUNDING sponsor.
- [ ] **Step 5: Delete the inherited translated READMEs and old fastlane changelogs.**
- [ ] **Step 6: Verify**

Run: `grep -rniE "discord|weblate|buymeacoffee|patreon|deniscerri\.github|t\.me/ytdlnis" README.md SECURITY.md CONTRIBUTING.MD CHANGELOG.md fastlane .github`
Expected: no matches (the fork attribution text may name the original author without a link).

- [ ] **Step 7: Commit**

### Task 4: Version 1.0.1

**Files:**
- Modify: `app/build.gradle:12-15`
- Create: `fastlane/metadata/android/en-US/changelogs/1000100.txt`

**Interfaces:**
- Produces: `versionName=1.0.1`, `versionCode=1000100`, APK names `DFast-1.0.1-…`.

- [ ] **Step 1:** Set `versionPatch = 1` (keep major 1, minor 0, build 0).
- [ ] **Step 2:** Add the 1000100 changelog describing the wordmark, link cleanup and README.
- [ ] **Step 3: Verify** by reading the version block; `versionCode` must equal `1*1000000 + 0 + 1*100 + 0 = 1000100`.
- [ ] **Step 4: Commit**

### Task 5: Release through GitHub Actions

**Files:** none.

**Interfaces:**
- Consumes: pushed `main` at the release commit, workflow `build.yml`, secrets `SIGNING_KEY`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`.
- Produces: GitHub Release `v1.0.1` with five APKs and `SHA256SUMS.txt`.

- [ ] **Step 1:** Push `main` with `cloud_git`.
- [ ] **Step 2:** Dispatch `build.yml` with `build_type=release`, `flavors=github`, `release_tag=v1.0.1`.
- [ ] **Step 3:** Poll the run to `completed`; require `conclusion=success` (signing, build, `apksigner verify`, publish).
- [ ] **Step 4:** Verify the release exists with tag `v1.0.1`, `draft=false`, `prerelease=false`, and the expected assets.

## Execution Handoff

Native execution in this session; the user supplied the release step explicitly and asked for the work to be finished end to end.
