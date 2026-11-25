# Account feed safeguards (do not remove)

The Subscriptions tab, the YouTube tab account shelves (recommended, watch later,
liked, history) and the channel row depend on YouTube's own web requests with the
user's saved session. YouTube changes these responses without notice. This file
lists what keeps them working and what must not change without care.

## Fallback chain
1. Direct web request (`YtAccountApi.videos`), with one automatic retry.
2. Continuation pages (up to 3) when the first page has few videos.
3. yt-dlp fallback with the account cookie file (in `HomeFeed`).
4. Last good copy on disk (`FeedCache`), shown when the live load is empty.
5. A visible error line ("Could not load your feed...", tap to retry). Never a blank screen.

## Do not change without running the tests
- `util/YtAccountApi.kt`: `post()` (SAPISIDHASH, Cookie, Origin, X-Origin, X-Goog-AuthUser headers), `cookies()`, `collect()` / `parseVideoItems()`, `webCont()`.
- `util/GoogleAccount.kt`: session file path, `isSignedIn`, `shelvesOn`, `saveSession`.
- The Subscriptions feed must depend only on being signed in, not on the "shelves" switch.
- Downloads must not use the account unless the separate switch is on.

## Tests and CI
- `app/src/test/java/dfast/app/util/YtFeedParserTest.kt` reads sample response shapes. When YouTube
  changes a shape, add the new sample to the test first, then fix the parser.
- CI runs the tests (step "Feed safeguard tests") and a guard (step "Feed guard") that fails when a
  protected file changes without updating `docs/feed_guard.sha256`. To update it after a reviewed change:
  `sha256sum <protected files> > docs/feed_guard.sha256` (see the file for the list), then record why in the commit.

## Release checklist
- [ ] Sign in, open Subscriptions: feed shows videos.
- [ ] YouTube tab: Recommended and Watch later appear (or the error line, never blank).
- [ ] Sign out and back in: feeds load again.

## What this cannot do
It cannot predict a new YouTube response shape or a YouTube-side block of the session. In that case the
cached copy or the error line shows, and a new sample plus a parser fix is needed.
