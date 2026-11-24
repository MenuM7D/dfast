# DFast Rebrand (name, package, icon) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the YTDLnis fork in `dfast/` into the first official DFast release: product name `DFast` everywhere, application ID `dfast.app`, a new brand icon set, then publish signed APKs from GitHub Actions.

**Architecture:** The Android project stays a single Gradle app module. The work is identity surgery: move Kotlin/Java sources from `com/deniscerri/ytdl` to `dfast/app`, rewrite the namespace/applicationId, replace the product-name strings and public identifiers, regenerate every launcher asset from one vector geometry, then let CI compile (debug job on push) before the release job builds and publishes.

**Tech Stack:** Android Gradle Plugin, Kotlin, Android resources (adaptive icons, vector drawables), PIL (offline raster generation), GitHub Actions.

**Spec:** This plan (no separate spec was supplied; the user brief is the spec).

## Global Constraints

- Do **not** run Gradle/Android SDK locally (sandbox OOM). Compilation proof comes from the GitHub Actions debug job.
- Preserve the YTDLnis project's existing conventions and attribution requirements (GPLv3, upstream helper packages, yt-dlp/ffmpeg dependencies). Only the fork's own identity changes.
- Application ID and Kotlin namespace must both become `dfast.app`.
- Brand palette: Signal Coral `#FF5A36`, Deep Ink `#16121F`, Warm White `#FFF7F2`.
- Version stays `1.0.0` / `versionCode 1000000` (already set).
- First public release tag: `v1.0.0` (numeric tag required by the in-app update checker).

## Review Focus

- Third-party helper APK packages (`com.deniscerri.ytdl.python`, `.ffmpeg`, `.nodejs`, `.deno`, `.aria2c`) are **external** package IDs and must survive the rename untouched; changing them silently breaks runtime downloads.
- Intent action strings changed by the rename must match between the manifest and every `Intent(...)` sender (`ytdlnis.settings.SettingsActivity`, `ytdlnis.TerminalActivity`, `ytdlnis.ResumeActivity`, `ytdlnis.WebViewActivity`, `ytdlnis.PoTokenWebViewLoginActivity`).
- `UpdateUtil` must read this fork's releases (`MenuM7D/dfast`) and the release tag must remain numeric-only (`v1.0.0`), or `tagNameToVersionNumber()` throws and update checks die.
- Icon assets must stay inside the adaptive-icon safe zone and remain legible at 48 px; monochrome layer must be a single opaque shape (notification/status icon uses the same geometry).
- Old pre-`v26` fallback PNGs must exist for all five densities or launchers on API 24–25 show a blank icon.

---

### Task 1: Package identity (`com.deniscerri.ytdl` → `dfast.app`)

**Files:**
- Move: `dfast/app/src/main/java/com/deniscerri/ytdl` → `dfast/app/src/main/java/dfast/app`
- Move: `dfast/app/src/test/java/com/deniscerri/ytdl` → `dfast/app/src/test/java/dfast/app`
- Move: `dfast/app/src/androidTest/java/com/deniscerri/ytdl` → `dfast/app/src/androidTest/java/dfast/app`
- Modify: every `.kt`, `.xml`, `.gradle`, `.pro` file containing the package string (304 files)
- Modify: `dfast/app/build.gradle` (`namespace`, `applicationId`, `archivesBaseName`, APK file names)
- Modify: `dfast/settings.gradle` (`rootProject.name = "DFast"`)

**Interfaces:**
- Produces: Kotlin namespace + `R`/`BuildConfig` package `dfast.app`; helper package constants `com.deniscerri.ytdl.{python,ffmpeg,nodejs,deno,aria2c}` unchanged.

- [ ] **Step 1: Move source trees and rewrite package declarations/imports**

`git mv` the three source roots, then replace `com.deniscerri.ytdl` → `dfast.app` in all tracked text files **except** the five helper package literals, which are restored from a placeholder afterwards. Verify: `grep -rn "com.deniscerri.ytdl"` only reports the helper packages and the Gradle dependency coordinates `io.github.deniscerri:ytdlnis_packages.*`.

- [ ] **Step 2: Update build identity**

`namespace 'dfast.app'`, `applicationId "dfast.app"`, `archivesBaseName = "DFast-$versionName"`, APK names `DFast-…`, `rootProject.name = "DFast"`.

- [ ] **Step 3: Verify no stale namespace remains**

Run: `grep -rn "com.deniscerri.ytdl" dfast/app/src dfast/app/build.gradle | grep -vE "ytdl\.(python|ffmpeg|nodejs|deno|aria2c)"`
Expected: no output. Run: `grep -c "dfast.app" dfast/app/src/main/AndroidManifest.xml`
Expected: matches `${applicationId}`-free direct uses only where intended.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "refactor: move the app to the dfast.app package"
```

### Task 2: Product identity (YTDLnis → DFast)

**Files:**
- Modify: `dfast/app/src/main/AndroidManifest.xml` (`android:label="@string/app_name"`)
- Modify: `dfast/app/src/main/res/values/strings.xml` (add `app_name`, rebrand copy)
- Modify: every `res/values*/strings.xml`, `arrays.xml`, `xml/*.xml`, `layout/app_icon_item.xml` (brand copy)
- Modify: `dfast/app/src/main/java/.../util/FileUtil.kt` (cache dir, default download folder)
- Modify: `dfast/app/src/main/java/.../util/UpdateUtil.kt` (release repo URL)
- Modify: `dfast/app/src/main/res/xml/root_preferences.xml` (source/license/security links → fork repo)
- Modify: `dfast/fastlane/metadata/android/en-US/{title.txt,full_description.txt}`

**Interfaces:**
- Produces: `@string/app_name` = `DFast`; internal identifiers `dfast_theme`, `dfast_icon`, `dfast-TRENDING`; intent actions `dfast.*`.

- [ ] **Step 1: Rename brand text**

Replace `YTDLnis` → `DFast` in `app/src` resources/code and in `fastlane` copy; replace lowercase internal identifiers `ytdlnis` → `dfast` (preference keys, trending sentinel, intent actions, cache dir), keeping URLs that are upstream support channels (`t.me/ytdlnis`, Discord) intact.

- [ ] **Step 2: Add the app label resource and wire the manifest**

Add `<string name="app_name" translatable="false">DFast</string>` to `values/strings.xml`; set `android:label="@string/app_name"`.

- [ ] **Step 3: Point self-referential links at the fork**

`UpdateUtil` → `https://api.github.com/repos/MenuM7D/dfast/releases`; source/security/license preferences → `https://github.com/MenuM7D/dfast…`.

- [ ] **Step 4: Verify**

Run: `grep -rni "ytdlnis" dfast/app/src dfast/fastlane | grep -vE "deniscerri|t\.me/ytdlnis|discord|ytdlnis_packages"` → no output.
Run: `grep -rn "intent-filter" -A2 dfast/app/src/main/AndroidManifest.xml | grep action` and confirm each action has one `Intent(` sender in Kotlin.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: rebrand the product as DFast"
```

### Task 3: Brand icon set

**Files:**
- Rewrite: `dfast/app/src/main/res/drawable/ic_launcher_foreground.xml`, `…_light`, `…_dark`, `…_blue`, `…_green`, `…_large`, `ic_app_icon.xml`
- Rewrite: `dfast/app/src/main/res/drawable/ic_launcher_background.xml` (+ `_light`, `_dark`)
- Modify: `dfast/app/src/main/res/values/colors.xml`, `values-night/colors.xml`
- Rewrite: `dfast/app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` and `ic_launcher_round.png`
- Modify: `dfast/app/src/main/res/mipmap-anydpi-v26/ic_launcher_light.xml`, `ic_launcher_dark.xml`

**Interfaces:**
- Produces: glyph path on a 108×108 viewport, mark occupying `34..84 × 26..82`; same path reused by adaptive foreground, monochrome and notification icon.

- [ ] **Step 1: Generate the legacy raster set from the vector geometry**

PIL script (supersampled 4×) draws the D-with-arrow mark on the coral rounded square (48/72/96/144/192 px) and coral circle for `_round`. Inspect the 48 px render before committing.

- [ ] **Step 2: Author the adaptive/vector layers**

Foreground: ink glyph, `fillType="evenOdd"` (arrow is a hole). Backgrounds: coral / warm white / deep ink. Dark & light aliases point at the matching foreground. Monochrome layer uses the plain glyph. `ic_launcher_foreground_large` keeps the 2× group for the status-bar icon with a white fill.

- [ ] **Step 3: Verify resources resolve and glyph is legible**

Run: `grep -rn "ic_launcher_foreground" dfast/app/src/main/res/mipmap-anydpi-v26` → every referenced drawable exists in `res/drawable`. Render the 48 px PNG and check the arrow hole is visible (no solid blob).
Run: `file dfast/app/src/main/res/mipmap-*/ic_launcher*.png` → correct dimensions (48/72/96/144/192).

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: new DFast launcher and in-app icon set"
```

### Task 4: Compile check on CI

- [ ] **Step 1: Push and wait for the Debug APK job** (`workflow_dispatch`/push to `main`). Expected: `assembleGithubDebug` succeeds. If it fails, read the first Kotlin/AAPT error and fix the rename, never the build config.
- [ ] **Step 2: Confirm the produced APK exists** in the job log (`app/build/outputs/apk/github/debug/`).

### Task 5: First official release

- [ ] **Step 1: Dispatch `build.yml`** with `build_type=release`, `flavors=github`, `release_tag=v1.0.0`.
- [ ] **Step 2: Verify** `Prepare signing material`, `Build signed release APKs`, `Verify APK signatures`, `Publish APKs to GitHub Releases` all succeed and the `v1.0.0` release carries the DFast APKs + `SHA256SUMS.txt`.
