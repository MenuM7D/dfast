# DFast Flux — New Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the inherited YTDLnis interface with a complete, original DFast interface ("Flux") that keeps every feature and every view ID, and permanently fix Arabic text clipping.

**Architecture:** The app is a fork whose Fragments/Adapters bind views by ID (`findViewById`, view binding). The redesign therefore replaces the *shell* (navigation, home, hub, cards, welcome, launcher art) and the *token layer* (colour, type, shape, components) while preserving every ID that Kotlin touches. Feature code is not rewritten; only `ThemeUtil`'s accent wiring and copy strings change.

**Tech Stack:** Kotlin, Android Views, Material Components 3, XML resources, GitHub Actions (no local Android SDK).

**Spec:** `docs/superpowers/plans/2026-11-24-dfast-flux-new-identity.md` (this document; the design brief is the user's request of 2026-11-24).

## Global Constraints

- `applicationId` stays `dfast.app`; Kotlin packages stay `com.deniscerri.ytdl.*` and `dfast.app.*`; GPLv3 attribution stays.
- Every view ID referenced from Kotlin must still exist with the same name. `MoreFragment` resolves nine IDs as `TextView`; they must remain `TextView` subclasses.
- Allowed outbound user-facing destinations: `https://github.com/MenuM7D/dfast` and `https://t.me/m7d_devvv`. No Discord, no Weblate, no YTDLnis links.
- No local Gradle/Android SDK (OOM). Verification happens only through GitHub Actions; nothing is pushed to `main` or released before the branch build is green.
- `minSdk` supports `drawableTint` (API 23+); no API 26-only resource may be used outside `-v26` qualifiers.
- Arabic and English are both first-class: no tracking (letterSpacing), no all-caps, no single-line ellipsis on labels shorter than a sentence.

## Bug: Arabic text clipping ("مقصوصة")

**Feedback loop.** No device or emulator exists in this environment, so the loop is static but red-capable: a shell assertion that the shipped resource set contains no `letterSpacing`, no `sp`-sized layout dimensions, and no `maxLines="1"` on hub/navigation labels.

```
$ grep -rn "letterSpacing" app/src/main/res/values | wc -l
18
```

**Reproduced mechanism (from code):** the DFast type scale applied `android:letterSpacing` to 15 text appearances, including **positive** tracking on labels (`0.01`–`0.04`). Arabic is cursive: positive tracking breaks glyph joining, and both signs inflate the measured run width. Those appearances are inherited by labels rendered in single-line, ellipsized containers (preference titles, nav labels, card chips), so a short Arabic word such as `الإعدادات` or `التلوين` is measured wider than its slot and ellipsized mid-word. Two secondary contributors were found: `no_results.xml` sizes an `ImageView` in `sp` (scales with the user's font size), and `download_card.xml` constrains the title against a `0dp`-wide action button.

**Fix:** remove tracking from every DFast text appearance (hierarchy comes from weight and size), set `includeFontPadding` off only where the container is not fixed, replace `sp` layout dimensions with `dp`, give card chips multi-line room, and use `labelVisibilityMode="selected"` so dock labels are never squeezed.

## Review Focus

- An Arabic label in the dock, hub tile, or settings row is ellipsized or shows disconnected letters — expected: full word, joined script.
- A card title in Arabic overflows its media card or is hidden behind the action buttons — expected: two lines, ellipsized only at the end of a full phrase.
- A view ID removed during a layout rewrite crashes its Fragment at runtime — expected: every ID listed per task still resolves, with the same class where Kotlin casts.
- Dark theme text on the brand gradient (hero, welcome) falls below 4.5:1 — expected: on-gradient text is always `#FFFFFF` at ≥ 0.9 alpha on a gradient of ≤ 60% luminance.
- A download action (audio/video button, FAB, card action) stops responding because its view became non-clickable inside a new card wrapper — expected: the same click targets, only restyled.

---

### Task 1: Flux token layer

**Files:**
- Modify: `dfast/app/src/main/res/values/colors.xml` (replace the `dfast_*` block)
- Modify: `dfast/app/src/main/res/values-night/colors.xml` (same)
- Modify: `dfast/app/src/main/res/values/dfast_styles.xml` (full rewrite)
- Modify: `dfast/app/src/main/res/values/themes.xml`, `values-night/themes.xml` (point `bottomNavigationStyle`, `android:windowBackground`)

**Interfaces:**
- Consumes: nothing.
- Produces: colour tokens `dfast_*` (same names as before, new values), type styles `TextAppearance.DFast.*` with `letterSpacing = 0`, shape styles `ShapeAppearance.DFast.*`, component styles `Widget.DFast.{Card,Button,ExtendedFab,Dock,NavIndicator,SearchBar,AppBar,Toolbar,Tile,Row,GroupLabel,Divider}`.

- [ ] **Step 1: Write the failing check** — `grep -rn "letterSpacing" values` must return nothing.
- [ ] **Step 2: Confirm it fails** — 18 matches today.
- [ ] **Step 3: Replace tokens** — indigo `#4A3FE0` / aqua `#00C2B8` brand, `#F5F6FA` mist day background, `#07080F` night background, 12/16/22/28 dp corners, zero tracking.
- [ ] **Step 4: Re-run the check** — expected: no output.
- [ ] **Step 5: Commit** — `feat(ui): install the Flux token layer`.

### Task 2: Brand art

**Files:**
- Modify: `drawable/ic_dfast_mark.xml` (new mark), `drawable/ic_dfast_wordmark.xml` (recolour), `drawable/ic_app_icon.xml`
- Modify: `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_background.xml`
- Modify: `drawable/bg_dfast_nav.xml` (dock), `drawable/bg_dfast_header.xml` (brand gradient panel), `drawable/bg_dfast_media_scrim.xml`, `drawable/bg_dfast_tile.xml` (new), `drawable/dfast_list_divider.xml`
- Modify: `color/dfast_nav_item.xml`

**Interfaces:**
- Produces: `@drawable/ic_dfast_mark` (24 dp vector), `@drawable/ic_dfast_wordmark` (89×26 dp vector), `@drawable/bg_dfast_nav` (24 dp rounded surface), `@drawable/bg_dfast_header` (28 dp gradient), `@drawable/bg_dfast_tile`, `@drawable/bg_dfast_media_scrim`.

- [ ] **Step 1: Draw the mark** — a double-chevron "fast down" glyph in white on the indigo→aqua gradient; 24 dp viewport, stroke-free.
- [ ] **Step 2: Draw the launcher foreground** — the same glyph, 108 dp viewport, 66% safe zone.
- [ ] **Step 3: Verify parse** — `python3 -c "import xml.dom.minidom,glob;[xml.dom.minidom.parse(f) for f in glob.glob('res/drawable/*.xml')]"`.
- [ ] **Step 4: Commit**.

### Task 3: Shell — app frame and dock

**Files:**
- Modify: `layout/activity_main.xml`
- Modify: `menu/bottom_nav_menu.xml`

**Interfaces:**
- Consumes: `Widget.DFast.Dock`, `bg_dfast_nav`.
- Produces: unchanged IDs `frame_layout`, `bottomNavigationView`, `notification_container`, `incognito_header`; menu items `homeFragment`, `historyFragment`, `downloadQueueMainFragment`, `moreFragment`, `terminalActivity`.

- [ ] **Step 1: Rebuild the frame** — ambient gradient band behind the status bar, fragment host, floating dock card (16 dp side margins, 24 dp radius) holding the `BottomNavigationView` with `labelVisibilityMode="selected"` and transparent background.
- [ ] **Step 2: Reorder the menu** — Home, Downloads, Queue, More (visible); Terminal stays `android:visible="false"`.
- [ ] **Step 3: Check IDs** — `grep -c "bottomNavigationView"` = 1 and `NavbarUtil` still finds it.
- [ ] **Step 4: Commit**.

### Task 4: Home — hero search and media cards

**Files:**
- Modify: `layout/fragment_home.xml`, `layout/result_card.xml`
- Modify: `values/strings.xml`, `values-ar/strings.xml` (`dfast_home_tagline`)

**Interfaces:**
- Consumes: Flux tokens, `ic_dfast_mark`.
- Produces: unchanged IDs `search_view`, `providers`, `queries`, `init_search_query`, `search_suggestions_recycler`, `recyclerViewHome`, `shimmer_results_framelayout`, `home_appbarlayout`, `home_toolbar`, `search_bar`, `progress`, `playlist_selection_chips_scrollview`, `playlist_selection_chips`, `all`, `home_fabs`, `copied_url_fab`, `download_all_fab`, `download_selected_fab`; card IDs `result_card_view`, `result_image_view`, `download_progress`, `result_title`, `author`, `duration`, `download_music`, `download_video`.

- [ ] **Step 1: Hero** — toolbar carries the mark as `app:logo`, the title keeps being set from `ThemeUtil`, and a tagline TextView sits above a 56 dp search field with 20 dp corners.
- [ ] **Step 2: Card** — 22 dp corners, 16:9 art, bottom scrim, duration pill top-end, two 48 dp circular action buttons bottom-end.
- [ ] **Step 3: Verify no repeated IDs** — script asserts each `@+id` appears once per file.
- [ ] **Step 4: Commit**.

### Task 5: Hub (More) — tile grid

**Files:**
- Modify: `layout/fragment_more.xml`

**Interfaces:**
- Consumes: `Widget.DFast.Tile`, `bg_dfast_header`.
- Produces: `app_icon` (ImageView), and `terminal`, `logs`, `command_templates`, `downloads`, `download_queue`, `cookies`, `observe_sources`, `settings`, `terminate` — all `TextView`.

- [ ] **Step 1: Hero card** — gradient panel, 64 dp mark, wordmark, tagline.
- [ ] **Step 2: Grid** — two-column rows of `TextView` tiles (`drawableTop` icon, centred label, `minHeight` not fixed height, no `maxLines`).
- [ ] **Step 3: Danger row** — `terminate` full width in `colorError`.
- [ ] **Step 4: Verify types** — the nine IDs are `TextView` elements.
- [ ] **Step 5: Commit**.

### Task 6: Downloads, history and welcome

**Files:**
- Modify: `layout/download_card.xml`, `layout/history_card.xml`, `layout/fragment_downloads.xml`, `layout/fragment_history.xml`, `layout/fragment_download_queue_main_screen.xml`, `layout/no_results.xml`, `layout/activity_welcome.xml`, `layout/fragment_welcome_intro.xml`

**Interfaces:**
- Produces: every existing ID in those files unchanged; `download_card` chips become multi-line; `result`/`history` cards keep `MaterialCardView` checkability.

- [ ] **Step 1: Rows** — 20 dp radius, 104×64 dp thumbnail (14 dp radius), title two lines, chips wrap.
- [ ] **Step 2: Sheet header / toolbars** — bold titles, breathing room, no `maxLines="1"` on titles.
- [ ] **Step 3: Empty state** — `dp` illustration size, centred copy, no `sp` dimensions.
- [ ] **Step 4: Welcome** — gradient hero with mark and wordmark, pill actions, wider progress.
- [ ] **Step 5: Commit**.

### Task 7: Copy, accent wiring, version

**Files:**
- Modify: `values/strings.xml`, `values-ar/strings.xml`
- Modify: `java/dfast/app/util/ThemeUtil.kt` (accent label "Flux")
- Modify: `dfast/app/build.gradle` (`versionMinor = 1`, `versionPatch = 0`)
- Create: `dfast/fastlane/metadata/android/en-US/changelogs/1010000.txt`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Produces: `dfast_home_tagline`, `dfast_group_library`, `dfast_group_app` in both locales; version `1.1.0` / code `1010000`.

- [ ] **Step 1: Copy** — Arabic-first taglines, no marketing filler.
- [ ] **Step 2: Version** — bump and changelog.
- [ ] **Step 3: Commit**.

### Task 8: Verify on CI and publish

- [ ] **Step 1:** push branch `redesign/flux`, dispatch `build_type=debug`, read `::error::` annotations until green.
- [ ] **Step 2:** merge to `main`, dispatch `build_type=release` with `release_tag=v1.1.0`.
- [ ] **Step 3:** confirm the release is published with five APKs plus `SHA256SUMS.txt`.

## Self-Review

- Spec coverage: text clipping (Task 1, 6), new identity (Tasks 1–6), no YTDLnis resemblance (Tasks 2–6), GitHub release (Task 8). ✓
- Step scan: each step names the file and the concrete change; no "handle edge cases" lines. ✓
- Type consistency: IDs and style names reused verbatim from the current resources. ✓
- Review Focus: five uncovered failure modes listed, each pinned to the task that owns the code. ✓
- Proportion: the plan names decisions, not transcripts; bodies live in the resource files. ✓
