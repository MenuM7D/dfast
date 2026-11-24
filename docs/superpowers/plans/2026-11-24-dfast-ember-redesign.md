# DFast "Ember" Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every trace of the inherited YTDLnis visual language with a DFast-owned design system ("Ember") and ship it as release v1.0.2.

**Architecture:** A single token layer (colour roles, Inter type scale, corner scale, component styles, window motion) is installed in `BaseTheme`, so every existing XML screen inherits the new look without per-screen rewrites. The five screens users see first — shell/nav, home, downloads sheet, more hub, welcome — are then re-laid-out by hand, and media cards are re-cut. All original view IDs are preserved because fragments bind them by ID.

**Tech Stack:** Android XML views, Material Components 1.10.0 (M3), AppCompat, Inter (SIL OFL) bundled as `res/font`, GitHub Actions for build/sign/publish.

**Spec:** User brief in this conversation (2026-11-24): full redesign with own identity, GitHub/Telegram as the only outbound destinations, new release.

## Global Constraints

- Outbound links allowed: `https://github.com/MenuM7D/dfast` and `https://t.me/m7d_devvv` only. Functional endpoints (YouTube/Google APIs, lrclib, yt-dlp release feeds) are not user destinations and stay.
- No Discord assets, no Weblate references, no upstream publisher links.
- `applicationId` stays `dfast.app`; upstream `com.deniscerri.ytdl.*` packages and GPLv3 attribution stay (binaries + licence).
- No local Gradle/JDK/Android SDK: OOM. Verification is GitHub Actions only.
- Version `1.0.2`, `versionCode 1000200`, tag `v1.0.2` (numeric-only suffix).
- Every layout edit preserves existing `@+id` names used from Kotlin.
- Palette: ink `#16121F`, ember `#FF5A36`, deep ember `#C1441F` (AA on white), warm paper surfaces; dark set is a warm near-black.

## Review Focus

- Bottom nav on 3-button/gesture devices and with 2–5 items after `NavbarUtil.applyNavBarStyle()`: floating pill must not clip labels or overlap content.
- `fragment_more` rows driven by `isVisible` (terminal/downloads/queue): hidden rows must not leave double dividers or gaps.
- Dark theme: `Theme.DFast` is defined once and inherits the qualifier-specific `BaseTheme`; any hard-coded light colour in a new drawable breaks night mode.
- SearchBar inside AppBarLayout with the new margins: it must keep its measured height and not collide with the toolbar.
- Cards that set `app:cardBackgroundColor` or `app:strokeWidth` explicitly keep their own values; global `materialCardViewStyle` must not erase them.

---

### Task 1: Ember design tokens

**Files:**
- Modify: `dfast/app/src/main/res/values/colors.xml`
- Modify: `dfast/app/src/main/res/values-night/colors.xml`
- Modify: `dfast/app/src/main/res/values/themes.xml`
- Modify: `dfast/app/src/main/res/values-night/themes.xml`

**Interfaces:**
- Produces: colour resources `dfast_primary … dfast_container_highest` (identical names in both qualifiers), style `Theme.DFast`, and `BaseTheme` items consumed by every screen.

- [x] **Step 1:** Add the 30 light tokens and their dark counterparts with the exact hex values from the constraint block.
- [x] **Step 2:** Add `<style name="Theme.DFast" parent="BaseTheme">` mapping each M3 role to its token, including the six `colorSurfaceContainer*` roles and `colorSurfaceBright/Dim`.
- [x] **Step 3:** Add the shared `BaseTheme` items (fonts, type scale, corner scale, component styles, window animation) to both qualifier files.

### Task 2: Type scale and component styles

**Files:**
- Create: `dfast/app/src/main/res/font/inter_regular.ttf`, `inter_medium.ttf`, `inter_semibold.ttf`, `inter_bold.ttf`, `inter_display_semibold.ttf`, `inter_display_bold.ttf`
- Modify: `dfast/app/src/main/res/values/styles.xml`

**Interfaces:**
- Produces: `TextAppearance.DFast.*` (15 roles), `Widget.DFast.Card`, `Widget.DFast.Button`, `Widget.DFast.BottomNavigation`, `Widget.DFast.NavIndicator`, `Widget.DFast.Row`, `Widget.DFast.SearchBar`, `Widget.DFast.ExtendedFab`, `ShapeAppearance.DFast.Corner.*`, `ShapeAppearanceOverlay.DFast.Thumb`.

- [x] **Step 1:** Bundle Inter weights; reference one file per role so API 24–25 resolves without a font-family provider.
- [x] **Step 2:** Define the type scale (display 52/44/38 display-bold −0.02em; headline 30/26/22 display-semibold −0.015em; title 20/16/14 semibold; body 16/14/12 regular 1.35 line height; label 14/12/11 medium).
- [x] **Step 3:** Define card/button/nav/fab/search/row styles and the corner scale (8/12/18/24/30dp) plus pill overlay.

### Task 3: Motion and shell

**Files:**
- Create: `dfast/app/src/main/res/anim/dfast_slide_in.xml`, `dfast_slide_out.xml`, `dfast_hold.xml`
- Create: `dfast/app/src/main/res/drawable/bg_dfast_nav.xml`, `bg_dfast_header.xml`, `bg_dfast_media_scrim.xml`
- Create: `dfast/app/src/main/res/color/dfast_nav_item.xml`
- Modify: `dfast/app/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Produces: window animation style, floating nav background.

- [x] **Step 1:** One orchestrated activity transition (fade + 12% rise, 280 ms) applied once in `BaseTheme`; everything else stays quiet.
- [x] **Step 2:** Rebuild the nav as a floating pill (12dp side margins, 10dp bottom margin, hairline stroke, ember active indicator).
- [x] **Step 3:** Restyle the incognito banner as a rounded notice.

### Task 4: Home

**Files:**
- Modify: `dfast/app/src/main/res/layout/fragment_home.xml`

- [x] **Step 1:** Search bar: 16dp side margins, surface-container-high fill, 18dp radius (from theme), no elevation.
- [x] **Step 2:** Chips 14dp radius, FABs use `Widget.DFast.ExtendedFab`.
- [x] **Step 3:** Keep every ID (`search_view`, `search_bar`, `providers`, `queries`, `init_search_query`, `recyclerViewHome`, `home_fabs`, `copied_url_fab`, `download_all_fab`, `download_selected_fab`, `progress`, `playlist_selection_chips`, `home_appbarlayout`, `home_toolbar`).

### Task 5: More hub

**Files:**
- Modify: `dfast/app/src/main/res/layout/fragment_more.xml`
- Modify: `dfast/app/src/main/res/values/strings.xml` (group labels)

- [x] **Step 1:** Ember gradient header carrying `app_icon` and the DFast wordmark.
- [x] **Step 2:** Two grouped cards ("Library", "Application") of `Widget.DFast.Row` rows, dividers inset 52dp; terminate row tinted `colorError`.
- [x] **Step 3:** Preserve IDs `terminal, logs, command_templates, downloads, download_queue, cookies, observe_sources, terminate, settings, app_icon`.

### Task 6: Welcome and media cards

**Files:**
- Modify: `dfast/app/src/main/res/layout/fragment_welcome_intro.xml`
- Modify: `dfast/app/src/main/res/layout/result_card.xml`
- Modify: `dfast/app/src/main/res/layout/active_download_card.xml`
- Modify: `dfast/app/src/main/res/layout/download_card.xml`
- Modify: `dfast/app/src/main/res/layout/fragment_downloads.xml`

- [x] **Step 1:** Welcome hero: ember gradient tile with icon + wordmark, display type, 52dp buttons.
- [x] **Step 2:** Media cards: 24dp radius, hairline stroke, bottom scrim for legibility, pill action buttons.
- [x] **Step 3:** Thumbnails via `ShapeAppearanceOverlay.Avatar = 12dp`, `Avatar2 = 14dp`; queue sheet header in display type.

### Task 7: Own the destinations

**Files:**
- Delete: `dfast/app/src/main/res/drawable/ic_discord.xml`
- Modify: `dfast/app/src/main/java/dfast/app/ui/more/settings/TranslatorsDialog.kt`
- Modify: `dfast/app/src/main/assets/translators.json`
- Modify: `dfast/app/src/main/java/dfast/app/util/UiUtil.kt`
- Modify: `dfast/app/src/main/res/xml/general_preferences.xml`, `res/values/arrays.xml`, `res/values/strings.xml`
- Modify: every `res/values-*/strings.xml` that still mentions Weblate

- [x] **Step 1:** Remove the Discord vector and the remote Weblate avatar fetch (initials only, no network).
- [x] **Step 2:** Point the two yt-dlp "?" help buttons at the project repository.
- [x] **Step 3:** Strip every `help_translate_summary` translation that advertises Weblate so the owned copy is shown.
- [x] **Step 4:** Register the "Ember" accent as the default in arrays, preferences and `ThemeUtil`.

### Task 8: Release

**Files:**
- Modify: `dfast/app/build.gradle` (`versionPatch = 2`)
- Create: `dfast/fastlane/metadata/android/en-US/changelogs/1000200.txt`
- Modify: `dfast/CHANGELOG.md`

- [x] **Step 1:** Verify all touched XML parses and every new resource reference resolves.
- [x] **Step 2:** Commit and push to `main`.
- [x] **Step 3:** Dispatch `build.yml` with `build_type=release`, `flavors=github`, `release_tag=v1.0.2`; confirm signing, `apksigner verify` and publish.
- [x] **Step 4:** Inspect the published release and its five APK assets.
